/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;

/** Drives {@link SharedServer} over real sockets, as the launcher does. */
class SharedServerTest {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
		+ "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";

	@TempDir
	Path _dir;

	private Path _registry;

	private final AtomicLong _fingerprint = new AtomicLong(1);

	private Thread _serverThread;

	@BeforeEach
	void start() throws Exception {
		_registry = _dir.resolve("server.properties");
		SharedServer server = new SharedServer(_registry, 1_000, _fingerprint::get,
			McpJsonDefaults.getMapper(),
			transport -> McpServer.sync(transport).serverInfo("test", "1").build(),
			new SystemStreamLog());
		_serverThread = new Thread(() -> {
			try {
				server.run();
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});
		_serverThread.start();
		for (int i = 0; i < 100 && !Files.exists(_registry); i++) {
			Thread.sleep(20);
		}
		assertTrue(Files.exists(_registry), "server did not register");
	}

	@AfterEach
	void stop() throws Exception {
		_serverThread.join(5_000);
	}

	@Test
	void sessionsShareOneServer() throws Exception {
		try (Socket a = connect(); Socket b = connect()) {
			assertEquals("OK", handshake(a));
			assertEquals("OK", handshake(b));
			assertTrue(initialize(a).contains("\"serverInfo\""));
			assertTrue(initialize(b).contains("\"serverInfo\""));
		}
		// Idle timeout (1 s) ends the server and removes its registration.
		_serverThread.join(5_000);
		assertFalse(_serverThread.isAlive());
		assertFalse(Files.exists(_registry));
	}

	@Test
	void wrongTokenIsRejected() throws Exception {
		try (Socket s = connect()) {
			send(s, "TL-MCP wrong");
			assertEquals(-1, s.getInputStream().read());
		}
	}

	@Test
	void changedClasspathRetiresTheServerButKeepsRunningSessions() throws Exception {
		try (Socket running = connect()) {
			assertEquals("OK", handshake(running));
			_fingerprint.set(2);
			try (Socket late = connect()) {
				assertEquals("STALE", handshake(late));
			}
			assertFalse(Files.exists(_registry), "a stale server must deregister");
			assertTrue(initialize(running).contains("\"serverInfo\""), "running session keeps working");
			assertTrue(_serverThread.isAlive());
		}
		_serverThread.join(5_000);
		assertFalse(_serverThread.isAlive(), "a stale server ends with its last session");
	}

	private Socket connect() throws IOException {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(_registry)) {
			props.load(in);
		}
		Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(props.getProperty("port")));
		s.setSoTimeout(10_000);
		_token = props.getProperty("token");
		return s;
	}

	private String _token;

	private String handshake(Socket s) throws IOException {
		send(s, SharedServer.HELLO + " " + _token);
		return readLine(s);
	}

	private String initialize(Socket s) throws IOException {
		send(s, INITIALIZE);
		String response = readLine(s);
		assertNotNull(response);
		return response;
	}

	private static void send(Socket s, String line) throws IOException {
		OutputStream out = s.getOutputStream();
		out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	/** Reads one line byte by byte, so nothing after it is lost to a reader's buffer. */
	private static String readLine(Socket s) throws IOException {
		InputStream in = s.getInputStream();
		StringBuilder line = new StringBuilder();
		for (int b; (b = in.read()) >= 0 && b != '\n';) {
			line.append((char) b);
		}
		return line.toString();
	}

}
