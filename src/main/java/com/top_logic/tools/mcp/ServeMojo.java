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
		server.addTool(queryTypes(jsonMapper, graph));
		server.addTool(describeType(jsonMapper, graph));
	}

	private static SyncToolSpecification queryTypes(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "name": {
			      "type": "string",
			      "description": "Exact-match filter: an FQN returns only that type; a bare simple name (no '.') returns every type whose simple class name equals it exactly (anchored at '.' or '$' boundaries)."
			    },
			    "pattern": {
			      "type": "string",
			      "description": "Substring filter (case-insensitive) against the full FQN. Combined with 'regex=true' it is interpreted as a Java regex matched via Matcher#find."
			    },
			    "regex": {
			      "type": "boolean",
			      "description": "Interpret 'pattern' as a Java regular expression.",
			      "default": false
			    },
			    "subtype_of": {
			      "type": "string",
			      "description": "FQN of a base type; restrict results to its (transitive) specializations."
			    },
			    "supertype_of": {
			      "type": "string",
			      "description": "FQN of a type; restrict results to its (transitive) generalizations (ancestors)."
			    },
			    "direct_only": {
			      "type": "boolean",
			      "description": "When 'subtype_of' or 'supertype_of' is given, restrict to direct relations (no transitivity).",
			      "default": false
			    },
			    "annotated_with": {
			      "type": "array",
			      "items": {"type": "string"},
			      "description": "Type must carry every listed annotation (by FQN)."
			    },
			    "kind": {
			      "type": "string",
			      "enum": ["any", "class", "interface", "concrete"],
			      "description": "'class' = non-interface (may be abstract); 'concrete' = not abstract and not interface.",
			      "default": "any"
			    },
			    "public_only": {
			      "type": "boolean",
			      "description": "Exclude non-public types. Default true.",
			      "default": true
			    },
			    "limit": {
			      "type": "integer",
			      "description": "Maximum number of matches to return. 0 or negative means unlimited. Default 100.",
			      "default": 100
			    }
			  }
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("query_types")
				.description("Query the indexed Java type graph. All filters are optional and AND-combined: "
					+ "'name'/'pattern' narrow by name, 'subtype_of'/'supertype_of' walk the hierarchy, "
					+ "'annotated_with' requires annotations, 'kind' restricts class vs. interface vs. concrete. "
					+ "Returns a sorted list of FQNs with total count and a 'truncated' flag. "
					+ "For full metadata of a single type, use describe_type.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				Map<String, Object> args = request.arguments();
				try {
					TypeGraph.TypeQuery q = new TypeGraph.TypeQuery(
						nullableStringArg(args, "name"),
						nullableStringArg(args, "pattern"),
						boolArg(args, "regex", false),
						nullableStringArg(args, "subtype_of"),
						nullableStringArg(args, "supertype_of"),
						boolArg(args, "direct_only", false),
						stringListArg(args, "annotated_with"),
						kindArg(args, "kind"),
						boolArg(args, "public_only", true),
						intArg(args, "limit", 100));
					TypeGraph.QueryResult r = graph.query(q);
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("total", r.total());
					result.put("truncated", r.truncated());
					result.put("matches", r.matches());
					return jsonResult(json, result);
				} catch (IllegalArgumentException ex) {
					return CallToolResult.builder()
						.isError(true)
						.content(List.of(new McpSchema.TextContent(ex.getMessage())))
						.build();
				}
			})
			.build();
	}

	private static SyncToolSpecification describeType(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {
			      "type": "string",
			      "description": "Fully qualified name of the type."
			    }
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("describe_type")
				.description("Full metadata of a single type by FQN: public/interface/abstract flags, direct supertypes, annotations, and (for TL configuration types) paired configuration/implementation FQNs. Returns null-ish if the FQN is unknown.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				Map<String, Object> desc = graph.describe(fqn);
				Map<String, Object> result = new LinkedHashMap<>();
				if (desc == null) {
					result.put("found", false);
					result.put("fqn", fqn);
				} else {
					result.put("found", true);
					result.put("type", desc);
				}
				return jsonResult(json, result);
			})
			.build();
	}

	private static String stringArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		return value == null ? "" : value.toString();
	}

	private static String nullableStringArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			return null;
		}
		String text = value.toString();
		return text.isEmpty() ? null : text;
	}

	private static List<String> stringListArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			return List.of();
		}
		if (value instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object element : list) {
				if (element != null) {
					out.add(element.toString());
				}
			}
			return out;
		}
		return List.of(value.toString());
	}

	private static TypeGraph.Kind kindArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			return TypeGraph.Kind.ANY;
		}
		String text = value.toString().trim().toUpperCase();
		if (text.isEmpty()) {
			return TypeGraph.Kind.ANY;
		}
		try {
			return TypeGraph.Kind.valueOf(text);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid kind '" + value + "'; expected one of any/class/interface/concrete.");
		}
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
