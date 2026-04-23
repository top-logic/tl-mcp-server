/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Maven plugin goal that starts an MCP stdio server exposing the {@link TypeGraph} of the current
 * project's compile classpath.
 *
 * <p>
 * Intended for use by AI agents: invoke via <code>mvn com.top-logic:tl-mcp-server:serve</code> in
 * the target workspace module. Maven resolves the compile classpath, from which the plugin reads
 * all <code>TypeIndex.json</code> resources and serves queries over stdio.
 * </p>
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

	/**
	 * TCP port to serve the MCP endpoint over HTTP. A value {@code <= 0} (default) keeps the
	 * server on stdio transport.
	 */
	@Parameter(property = "tl-mcp.port", defaultValue = "0")
	private int _httpPort;

	/** Bind address for HTTP transport. */
	@Parameter(property = "tl-mcp.host", defaultValue = "127.0.0.1")
	private String _httpHost;

	/** URL path of the MCP endpoint when using HTTP transport. */
	@Parameter(property = "tl-mcp.path", defaultValue = "/mcp")
	private String _httpPath;

	@Override
	public void execute() throws MojoExecutionException {
		Set<File> classpath = new LinkedHashSet<>();
		List<MavenProject> projects = _session.getProjects();
		int failed = 0;
		for (MavenProject project : projects) {
			File outputDir = new File(project.getBuild().getOutputDirectory());
			if (outputDir.isDirectory()) {
				classpath.add(outputDir);
			}
			try {
				for (String element : project.getCompileClasspathElements()) {
					classpath.add(new File(element));
				}
			} catch (DependencyResolutionRequiredException ex) {
				failed++;
				getLog().warn("Skipping classpath of " + project.getId() + ": " + ex.getMessage());
			}
		}

		TypeGraph graph;
		try {
			graph = TypeGraph.load(new ArrayList<>(classpath));
		} catch (Exception ex) {
			throw new MojoExecutionException("Failed to load type index from classpath.", ex);
		}

		getLog().info("tl-mcp-server: indexed " + graph.size() + " types from "
			+ classpath.size() + " classpath entries across " + projects.size() + " reactor module(s) (root: "
			+ _project.getId() + (failed > 0 ? ", " + failed + " unresolved" : "") + ")");

		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

		if (_httpPort > 0) {
			runHttp(graph, jsonMapper);
		} else {
			runStdio(graph, jsonMapper);
		}
	}

	private void runStdio(TypeGraph graph, McpJsonMapper jsonMapper) {
		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);
		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.tools(false)
				.build())
			.build();

		registerTools(server, jsonMapper, graph);

		try {
			Thread.currentThread().join();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void runHttp(TypeGraph graph, McpJsonMapper jsonMapper) throws MojoExecutionException {
		HttpServletStreamableServerTransportProvider transport =
			HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(jsonMapper)
				.mcpEndpoint(_httpPath)
				.build();

		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.tools(false)
				.build())
			.build();

		registerTools(server, jsonMapper, graph);

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

	private static void registerTools(McpSyncServer server, McpJsonMapper jsonMapper, TypeGraph graph) {
		server.addTool(findType(jsonMapper, graph));
		server.addTool(searchTypes(jsonMapper, graph));
		server.addTool(subtypesOf(jsonMapper, graph));
		server.addTool(supertypesOf(jsonMapper, graph));
		server.addTool(implementorsOf(jsonMapper, graph));
		server.addTool(annotatedWith(jsonMapper, graph));
	}

	private static SyncToolSpecification searchTypes(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "query": {
			      "type": "string",
			      "description": "Substring (case-insensitive) matched against each FQN, or a Java regex when 'regex' is true."
			    },
			    "regex": {
			      "type": "boolean",
			      "description": "Interpret 'query' as a Java regular expression matched (Matcher#find) against the FQN.",
			      "default": false
			    },
			    "limit": {
			      "type": "integer",
			      "description": "Maximum number of matches to return. 0 or negative means unlimited. Default 100.",
			      "default": 100
			    }
			  },
			  "required": ["query"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("search_types")
				.description("Search indexed type names. Returns FQNs sorted alphabetically. Use this for fuzzy discovery; for exact name lookup prefer find_type.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String query = stringArg(request.arguments(), "query");
				boolean regex = boolArg(request.arguments(), "regex", false);
				int limit = intArg(request.arguments(), "limit", 100);
				List<String> matches;
				try {
					matches = graph.search(query, regex, limit);
				} catch (IllegalArgumentException ex) {
					return CallToolResult.builder()
						.isError(true)
						.content(List.of(new McpSchema.TextContent(ex.getMessage())))
						.build();
				}
				int total = (limit > 0 && matches.size() == limit) ? graph.searchCount(query, regex) : matches.size();
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("query", query);
				result.put("regex", regex);
				result.put("total", total);
				result.put("truncated", total > matches.size());
				result.put("matches", matches);
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification findType(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "name": {
			      "type": "string",
			      "description": "Fully qualified class name, or a bare simple name."
			    }
			  },
			  "required": ["name"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("find_type")
				.description("Look up type metadata by exact name. If an FQN is given, returns that one type. If a bare simple name (no '.') is given, returns every type whose simple class name is exactly that (matching on '.' or '$' boundaries — a name like 'Component' will not match 'LayoutComponent'). For substring or pattern search across FQNs, use search_types.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String name = stringArg(request.arguments(), "name");
				Map<String, Object> result = new LinkedHashMap<>();
				List<String> matches = graph.findByName(name);
				if (matches.size() == 1) {
					result.put("type", graph.describe(matches.get(0)));
				} else {
					List<Map<String, Object>> descs = new ArrayList<>();
					for (String fqn : matches) {
						descs.add(graph.describe(fqn));
					}
					result.put("matches", descs);
				}
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification subtypesOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {
			      "type": "string",
			      "description": "Fully qualified name of the base type."
			    },
			    "transitive": {
			      "type": "boolean",
			      "description": "If true (default), return specializations recursively.",
			      "default": true
			    }
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("subtypes_of")
				.description("List all types that generalize (extend or implement) the given type.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				boolean transitive = boolArg(request.arguments(), "transitive", true);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("base", fqn);
				result.put("transitive", transitive);
				result.put("subtypes", graph.specializationsOf(fqn, transitive));
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification supertypesOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {
			      "type": "string",
			      "description": "Fully qualified name of the type."
			    },
			    "transitive": {
			      "type": "boolean",
			      "description": "If true (default), return ancestors recursively.",
			      "default": true
			    }
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("supertypes_of")
				.description("List all supertypes (classes and interfaces) of the given type.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				boolean transitive = boolArg(request.arguments(), "transitive", true);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("type", fqn);
				result.put("transitive", transitive);
				result.put("supertypes", graph.generalizationsOf(fqn, transitive));
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification implementorsOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {
			      "type": "string",
			      "description": "Fully qualified name of the interface or base class."
			    }
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("implementors_of")
				.description("List all public, concrete (non-abstract, non-interface) specializations of the given type, transitively.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("base", fqn);
				result.put("implementors", graph.implementorsOf(fqn));
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification annotatedWith(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "annotation": {
			      "type": "string",
			      "description": "Fully qualified name of the annotation class."
			    }
			  },
			  "required": ["annotation"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("annotated_with")
				.description("List all types directly annotated with the given annotation (by FQN).")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String annotation = stringArg(request.arguments(), "annotation");
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("annotation", annotation);
				result.put("types", graph.annotatedWith(annotation));
				return jsonResult(json, result);
			})
			.build();
	}

	private static String stringArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		return value == null ? "" : value.toString();
	}

	private static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
		Object value = args.get(key);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean b) {
			return b;
		}
		return Boolean.parseBoolean(value.toString());
	}

	private static int intArg(Map<String, Object> args, String key, int defaultValue) {
		Object value = args.get(key);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Number n) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

	private static CallToolResult jsonResult(McpJsonMapper json, Object payload) {
		try {
			String text = json.writeValueAsString(payload);
			return CallToolResult.builder()
				.content(List.of(new McpSchema.TextContent(text)))
				.build();
		} catch (Exception ex) {
			return CallToolResult.builder()
				.isError(true)
				.content(List.of(new McpSchema.TextContent("Serialization failure: " + ex.getMessage())))
				.build();
		}
	}

}
