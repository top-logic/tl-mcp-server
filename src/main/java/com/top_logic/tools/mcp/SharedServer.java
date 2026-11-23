/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.apache.maven.plugin.logging.Log;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

/**
 * Serves one index to any number of MCP sessions, so that agent sessions on the same project
 * share one server instead of each building its own index.
 *
 * <p>
 * Protocol: the server listens on a loopback TCP port and announces itself in a registry file
 * (Java properties {@code port}, {@code token}, {@code pid}, readable only by the user). A client
 * connects, sends the line {@code TL-MCP <token>} and receives {@code OK} - after which the
 * connection carries a plain newline-delimited JSON-RPC MCP session, exactly like stdio - or
 * {@code STALE}.
 * </p>
 *
 * <p>
 * {@code STALE} means the classpath the index was built from has changed since (a module was
 * recompiled, a dependency re-installed, a pom edited). The server then removes its registry
 * entry, accepts no further sessions and exits once its current sessions end; the client starts
 * a fresh server. A new session therefore always sees an index as current as an unshared
 * server would build it, while running sessions keep working on theirs.
 * </p>
 *
 * <p>
 * The server exits when it has had no session for the idle timeout.
 * </p>
 */
final class SharedServer {

	/** First line a client sends, followed by a space and the token. */
	static final String HELLO = "TL-MCP";

	private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

	private final Path _registry;

	private final long _idleMillis;

	private final LongSupplier _fingerprint;

	private final long _initialFingerprint;

	private final McpJsonMapper _jsonMapper;

	private final Function<StdioServerTransportProvider, McpSyncServer> _serverFactory;

	private final Log _log;

	private final String _token = newToken();

	private ServerSocket _socket;

	private int _active;

	private long _idleSince = System.currentTimeMillis();

	private boolean _retiring;

	/**
	 * @param registry
	 *        File to announce the server in.
	 * @param idleMillis
	 *        How long the server stays up without any session.
	 * @param fingerprint
	 *        Current state of the indexed classpath; a change makes the server stale.
	 * @param serverFactory
	 *        Builds the MCP server (tools registered) for one session's transport.
	 */
	SharedServer(Path registry, long idleMillis, LongSupplier fingerprint,
			McpJsonMapper jsonMapper, Function<StdioServerTransportProvider, McpSyncServer> serverFactory, Log log) {
		_registry = registry;
		_idleMillis = idleMillis;
		_fingerprint = fingerprint;
		_initialFingerprint = fingerprint.getAsLong();
		_jsonMapper = jsonMapper;
		_serverFactory = serverFactory;
		_log = log;
	}

	/** Serves sessions until the server is idle or stale and its last session has ended. */
	void run() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			synchronized (this) {
				_socket = socket;
			}
			writeRegistry(socket.getLocalPort());
			_log.info("tl-mcp-server: shared server listening on " + socket.getLocalSocketAddress()
				+ ", registry " + _registry + ", idle timeout " + _idleMillis / 1000 + " s.");
			Thread acceptor = new Thread(() -> acceptLoop(socket), "tl-mcp-accept");
			acceptor.setDaemon(true);
			acceptor.start();
			awaitEnd();
		} finally {
			deregister();
		}
		_log.info("tl-mcp-server: shared server shutting down.");
	}

	private synchronized void awaitEnd() {
		while (true) {
			if (_active > 0) {
				waitQuietly(0);
				continue;
			}
			if (_retiring) {
				return;
			}
			long remaining = _idleSince + _idleMillis - System.currentTimeMillis();
			if (remaining <= 0) {
				_log.info("tl-mcp-server: no session for " + _idleMillis / 1000 + " s.");
				return;
			}
			waitQuietly(remaining);
		}
	}

	private void waitQuietly(long millis) {
		try {
			wait(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			_retiring = true;
		}
	}

	private void acceptLoop(ServerSocket socket) {
		while (!socket.isClosed()) {
			Socket client;
			try {
				client = socket.accept();
			} catch (IOException ex) {
				if (!socket.isClosed()) {
					_log.warn("tl-mcp-server: accept failed: " + ex.getMessage());
				}
				return;
			}
			Thread session = new Thread(() -> handle(client), "tl-mcp-session-" + client.getPort());
			session.setDaemon(true);
			session.start();
		}
	}

	private void handle(Socket client) {
		try (client) {
			client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			InputStream in = new BufferedInputStream(client.getInputStream());
			OutputStream out = client.getOutputStream();
			if (!(HELLO + " " + _token).equals(readLine(in))) {
				_log.warn("tl-mcp-server: rejected a connection with a wrong handshake.");
				return;
			}
			client.setSoTimeout(0);
			if (!enter()) {
				reply(out, "STALE");
				return;
			}
			try {
				reply(out, "OK");
				serve(in, out);
			} finally {
				leave();
			}
		} catch (SocketException ex) {
			// Client went away.
		} catch (IOException ex) {
			_log.warn("tl-mcp-server: session failed: " + ex.getMessage());
		}
	}

	/**
	 * Admits a new session, unless the server is retiring or its index has become stale (which
	 * makes it retire).
	 */
	private synchronized boolean enter() {
		if (_retiring) {
			return false;
		}
		if (isStale()) {
			_log.info("tl-mcp-server: the classpath changed since indexing; handing over to a new server.");
			retire();
			return false;
		}
		_active++;
		_log.info("tl-mcp-server: session started, " + _active + " active.");
		return true;
	}

	private synchronized void leave() {
		_active--;
		_log.info("tl-mcp-server: session ended, " + _active + " active.");
		if (_active == 0) {
			_idleSince = System.currentTimeMillis();
		}
		notifyAll();
	}

	private boolean isStale() {
		try {
			return _fingerprint.getAsLong() != _initialFingerprint;
		} catch (RuntimeException ex) {
			_log.warn("tl-mcp-server: cannot check the classpath for changes: " + ex.getMessage());
			return false;
		}
	}

	/** Stops accepting sessions; the server ends when the current ones have. */
	private synchronized void retire() {
		_retiring = true;
		deregister();
		try {
			_socket.close();
		} catch (IOException ex) {
			// Closing anyway.
		}
		notifyAll();
	}

	private void serve(InputStream in, OutputStream out) {
		CountDownLatch gone = new CountDownLatch(1);
		McpSyncServer server = _serverFactory.apply(
			new StdioServerTransportProvider(_jsonMapper, new EofSignal(in, gone), out));
		try {
			gone.await();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} finally {
			server.closeGracefully();
		}
	}

	private static void reply(OutputStream out, String line) throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	/** Reads one short ASCII line, without buffering beyond it. */
	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int b; (b = in.read()) >= 0 && b != '\n';) {
			if (line.size() > 256) {
				return null;
			}
			line.write(b);
		}
		return line.toString(StandardCharsets.US_ASCII).trim();
	}

	private void writeRegistry(int port) throws IOException {
		Files.createDirectories(_registry.getParent());
		Path tmp = _registry.resolveSibling(_registry.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
		Files.deleteIfExists(tmp);
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} else {
			Files.createFile(tmp);
		}
		Properties props = new Properties();
		props.setProperty("port", Integer.toString(port));
		props.setProperty("token", _token);
		props.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			props.store(w, "tl-mcp-server shared server");
		}
		try {
			Files.move(tmp, _registry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ex) {
			Files.move(tmp, _registry, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Removes the registry file, unless another server has replaced it meanwhile. */
	private synchronized void deregister() {
		try {
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(_registry)) {
				props.load(in);
			}
			if (_token.equals(props.getProperty("token"))) {
				Files.deleteIfExists(_registry);
			}
		} catch (IOException ex) {
			// Already gone.
		}
	}

	private static String newToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

}
