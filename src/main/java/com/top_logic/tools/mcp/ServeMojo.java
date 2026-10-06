/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Maven plugin goal that starts an MCP server (stdio or HTTP) exposing the {@link TypeGraph} built
 * from the reactor's compile classpath. Tool wiring lives in {@link McpTools}.
 */
@Mojo(
	name = "serve",
	aggregator = true,
	defaultPhase = LifecyclePhase.NONE,
	requiresDependencyResolution = ResolutionScope.COMPILE,
	threadSafe = true,
	requiresProject = true)
public class ServeMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject _project;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	private MavenSession _session;

	/** TCP port for HTTP transport. {@code <= 0} keeps the server on stdio. */
	@Parameter(property = "tl-mcp.port", defaultValue = "0")
	private int _httpPort;

	@Parameter(property = "tl-mcp.host", defaultValue = "127.0.0.1")
	private String _httpHost;

	@Parameter(property = "tl-mcp.path", defaultValue = "/mcp")
	private String _httpPath;

	/** Index JDK system modules (jrt:/) in addition to the classpath. Off by default. */
	@Parameter(property = "tl-mcp.jdk", defaultValue = "false")
	private boolean _includeJdk;

	/** Scan method bodies for call/field-access/literal information. */
	@Parameter(property = "tl-mcp.bodies", defaultValue = "true")
	private boolean _scanBodies;

	@Override
	public void execute() throws MojoExecutionException {
		List<BytecodeScanner.Source> classpath = new ArrayList<>();
		List<File> seen = new ArrayList<>();
		Set<Path> resourceClasspath = new LinkedHashSet<>();
		List<MavenProject> projects = _session.getProjects();
		int failed = 0;
		for (MavenProject project : projects) {
			File outputDir = new File(project.getBuild().getOutputDirectory());
			resourceClasspath.add(outputDir.toPath());
			if (outputDir.isDirectory() && addOnce(seen, outputDir)) {
				File sourceDir = new File(project.getBuild().getSourceDirectory());
				classpath.add(new BytecodeScanner.Source(outputDir, project.getId(),
					sourceDir.isDirectory() ? sourceDir : null));
			}
			try {
				for (String element : project.getCompileClasspathElements()) {
					File f = new File(element);
					resourceClasspath.add(f.toPath());
					if (addOnce(seen, f)) {
						classpath.add(new BytecodeScanner.Source(f, null, companionSources(f)));
					}
				}
			} catch (DependencyResolutionRequiredException ex) {
				failed++;
				getLog().warn("Skipping classpath of " + project.getId() + ": " + ex.getMessage());
			}
		}

		// The index is built in the background: the MCP client allows the server only a fixed
		// connect window (30 s by default) to answer the initialize request, and indexing a large
		// reactor takes longer. Tool calls wait for the index when it is not ready yet.
		int unresolved = failed;
		CompletableFuture<TypeGraph> index = new CompletableFuture<>();
		Thread indexer = new Thread(() -> {
			try {
				TypeGraph loaded = TypeGraph.load(classpath, _includeJdk, _scanBodies);
				getLog().info("tl-mcp-server: indexed " + loaded.size() + " types from "
					+ classpath.size() + " classpath entries across " + projects.size() + " reactor module(s)"
					+ (_includeJdk ? " (+JDK)" : "")
					+ (_scanBodies ? " (bodies)" : " (no bodies)")
					+ " (root: " + _project.getId() + (unresolved > 0 ? ", " + unresolved + " unresolved" : "")
					+ ")");
				index.complete(loaded);
			} catch (Throwable ex) {
				getLog().error("tl-mcp-server: failed to build type graph from classpath.", ex);
				index.completeExceptionally(ex);
			}
		}, "tl-mcp-index");
		indexer.setDaemon(true);
		indexer.start();
		Supplier<TypeGraph> graph = () -> awaitIndex(index);

		ResourceBrowser resources = null;
		try {
			resources = ResourceBrowser.fromClasspath(resourceClasspath);
		} catch (Exception ex) {
			getLog().warn("Resource browsing disabled; cannot build the web resource stack: " + ex.getMessage());
		}
		if (resources == null) {
			getLog().info("tl-mcp-server: no web resources found; resource tools disabled.");
		} else {
			getLog().info("tl-mcp-server: web resource stack has " + resources.rootCount() + " root(s).");
		}

		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

		if (_httpPort > 0) {
			runHttp(graph, resources, jsonMapper);
		} else {
			runStdio(graph, resources, jsonMapper);
		}
	}

	/** Waits for the background index; a failed build is reported to the calling tool. */
	private static TypeGraph awaitIndex(CompletableFuture<TypeGraph> index) {
		try {
			return index.join();
		} catch (CompletionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			throw new IllegalStateException("Failed to build type graph from classpath: " + cause, cause);
		}
	}

	private static boolean addOnce(List<File> seen, File f) {
		for (File prev : seen) {
			if (prev.equals(f)) return false;
		}
		seen.add(f);
		return true;
	}

	/** Finds the {@code -sources.jar} sibling of a dependency JAR, if present. */
	private static File companionSources(File jar) {
		if (jar == null || !jar.isFile()) return null;
		String name = jar.getName();
		if (!name.endsWith(".jar")) return null;
		File sources = new File(jar.getParentFile(), name.substring(0, name.length() - 4) + "-sources.jar");
		return sources.isFile() ? sources : null;
	}

	/**
	 * Serves on stdin/stdout until the client closes stdin. The client's end of the connection is
	 * the server's end too: without this, a server whose client is gone (or whose launcher was
	 * killed) would run on indefinitely.
	 */
	private void runStdio(Supplier<TypeGraph> graph, ResourceBrowser resources, McpJsonMapper jsonMapper) {
		EofSignalingInputStream in = new EofSignalingInputStream(System.in);
		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, in, System.out);
		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.instructions(McpTools.INSTRUCTIONS)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
			.build();
		McpTools.registerAll(server, jsonMapper, graph, resources);
		try {
			in.awaitEof();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		getLog().info("tl-mcp-server: stdin closed, shutting down.");
		server.closeGracefully();
	}

	private void runHttp(Supplier<TypeGraph> graph, ResourceBrowser resources, McpJsonMapper jsonMapper)
			throws MojoExecutionException {
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(jsonMapper)
				.mcpEndpoint(_httpPath)
				.build();
		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.instructions(McpTools.INSTRUCTIONS)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
			.build();
		McpTools.registerAll(server, jsonMapper, graph, resources);

		Server jetty = new Server();
		ServerConnector connector = new ServerConnector(jetty);
		connector.setHost(_httpHost);
		connector.setPort(_httpPort);
		jetty.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		context.setContextPath("/");
		ServletHolder holder = new ServletHolder(transport);
		holder.setAsyncSupported(true);
		context.addServlet(holder, "/*");
		jetty.setHandler(context);

		try {
			jetty.start();
		} catch (Exception ex) {
			throw new MojoExecutionException("Failed to start HTTP server on " + _httpHost + ":" + _httpPort, ex);
		}

		getLog().info("tl-mcp-server: listening on http://" + _httpHost + ":" + _httpPort + _httpPath);

		try {
			jetty.join();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
