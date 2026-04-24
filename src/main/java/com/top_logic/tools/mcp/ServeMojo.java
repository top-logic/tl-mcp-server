/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Maven plugin goal that starts an MCP server (stdio or HTTP) exposing the {@link TypeGraph} built
 * from the reactor's compile classpath.
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

	/**
	 * Scan method bodies to populate the call graph, field-access graph, and string-literal
	 * index. Adds ~2–3× to startup time; enable when those queries are needed.
	 */
	@Parameter(property = "tl-mcp.bodies", defaultValue = "true")
	private boolean _scanBodies;

	@Override
	public void execute() throws MojoExecutionException {
		List<BytecodeScanner.Source> classpath = new ArrayList<>();
		List<File> seen = new ArrayList<>();
		List<MavenProject> projects = _session.getProjects();
		int failed = 0;
		for (MavenProject project : projects) {
			File outputDir = new File(project.getBuild().getOutputDirectory());
			if (outputDir.isDirectory() && addOnce(seen, outputDir)) {
				classpath.add(new BytecodeScanner.Source(outputDir, project.getId()));
			}
			try {
				for (String element : project.getCompileClasspathElements()) {
					File f = new File(element);
					if (addOnce(seen, f)) {
						classpath.add(new BytecodeScanner.Source(f, null));
					}
				}
			} catch (DependencyResolutionRequiredException ex) {
				failed++;
				getLog().warn("Skipping classpath of " + project.getId() + ": " + ex.getMessage());
			}
		}

		TypeGraph graph;
		try {
			graph = TypeGraph.load(classpath, _includeJdk, _scanBodies);
		} catch (Exception ex) {
			throw new MojoExecutionException("Failed to build type graph from classpath.", ex);
		}

		getLog().info("tl-mcp-server: indexed " + graph.size() + " types from "
			+ classpath.size() + " classpath entries across " + projects.size() + " reactor module(s)"
			+ (_includeJdk ? " (+JDK)" : "")
			+ (_scanBodies ? " (bodies)" : " (no bodies)")
			+ " (root: " + _project.getId() + (failed > 0 ? ", " + failed + " unresolved" : "") + ")");

		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

		if (_httpPort > 0) {
			runHttp(graph, jsonMapper);
		} else {
			runStdio(graph, jsonMapper);
		}
	}

	private static boolean addOnce(List<File> seen, File f) {
		for (File prev : seen) {
			if (prev.equals(f)) return false;
		}
		seen.add(f);
		return true;
	}

	private void runStdio(TypeGraph graph, McpJsonMapper jsonMapper) {
		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);
		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
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
			.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
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
		server.addTool(listMembers(jsonMapper, graph));
		server.addTool(referencesTo(jsonMapper, graph));
		server.addTool(callersOf(jsonMapper, graph));
		server.addTool(fieldAccessors(jsonMapper, graph));
		server.addTool(moduleOf(jsonMapper, graph));
	}

	// ---------- Tools ----------

	private static SyncToolSpecification queryTypes(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "name": {"type": "string", "description": "Exact-match filter: FQN or bare simple name (anchored at '.' or '$')."},
			    "pattern": {"type": "string", "description": "Substring (case-insensitive) against the full FQN, or regex if 'regex' is true."},
			    "regex": {"type": "boolean", "default": false},
			    "subtype_of": {"type": "string", "description": "FQN; restrict to (transitive) specializations."},
			    "supertype_of": {"type": "string", "description": "FQN; restrict to (transitive) generalizations."},
			    "direct_only": {"type": "boolean", "default": false},
			    "annotated_with": {"type": "array", "items": {"type": "string"}, "description": "Require every listed annotation."},
			    "in_module": {"type": "string", "description": "Restrict to a module/JAR id ('groupId:artifactId[:version]' or a Maven project id)."},
			    "kind": {"type": "string", "enum": ["any", "class", "interface", "concrete", "enum", "annotation"], "default": "any"},
			    "public_only": {"type": "boolean", "default": true},
			    "limit": {"type": "integer", "default": 100}
			  }
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("query_types")
				.description("Filter the type index. All filters optional, AND-combined. Returns sorted FQNs with total count and a 'truncated' flag.")
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
						nullableStringArg(args, "in_module"),
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
					return errorResult(ex.getMessage());
				}
			})
			.build();
	}

	private static SyncToolSpecification describeType(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {"type": "string", "description": "Fully qualified name of the type."}
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("describe_type")
				.description("Metadata of a single type by FQN: modifiers, superclass, interfaces, enclosing outer class, module, source file, annotations (with parsed parameter values), and method/field counts. For the member list call list_members.")
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

	private static SyncToolSpecification listMembers(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {"type": "string", "description": "FQN of the declaring type."},
			    "name": {"type": "string", "description": "Exact member name."},
			    "pattern": {"type": "string", "description": "Substring (case-insensitive) against member name; regex when 'regex' is true."},
			    "regex": {"type": "boolean", "default": false},
			    "kind": {"type": "string", "enum": ["any", "method", "field"], "default": "any"},
			    "type": {"type": "string", "description": "For fields: the field type FQN. For methods: the return type FQN."},
			    "parameter_type": {"type": "string", "description": "Method must accept at least one parameter of this FQN. Fields are excluded when set."},
			    "annotated_with": {"type": "array", "items": {"type": "string"}, "description": "Member must carry every listed annotation."},
			    "public_only": {"type": "boolean", "default": false},
			    "limit": {"type": "integer", "default": 100}
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("list_members")
				.description("List declared methods and/or fields of a type, with optional filters (name/pattern, kind, type, parameter_type, annotations, public_only, limit). Returns signatures, modifiers, annotations with parsed parameter values, and constant values. Inherited members are not included — query each supertype separately.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				Map<String, Object> args = request.arguments();
				String fqn = stringArg(args, "fqn");
				try {
					TypeGraph.MemberQuery mq = new TypeGraph.MemberQuery(
						nullableStringArg(args, "name"),
						nullableStringArg(args, "pattern"),
						boolArg(args, "regex", false),
						memberKindArg(args, "kind"),
						nullableStringArg(args, "type"),
						nullableStringArg(args, "parameter_type"),
						stringListArg(args, "annotated_with"),
						boolArg(args, "public_only", false),
						intArg(args, "limit", 100));
					Map<String, Object> desc = graph.describeMembers(fqn, mq);
					Map<String, Object> result = new LinkedHashMap<>();
					if (desc == null) {
						result.put("found", false);
						result.put("fqn", fqn);
					} else {
						result.put("found", true);
						result.putAll(desc);
					}
					return jsonResult(json, result);
				} catch (IllegalArgumentException ex) {
					return errorResult(ex.getMessage());
				}
			})
			.build();
	}

	private static SyncToolSpecification referencesTo(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {"type": "string"},
			    "limit": {"type": "integer", "default": 200}
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("references_to")
				.description("Types that reference the given FQN: superclasses/interfaces, annotations, method and field signatures, and (when body scan is enabled) types touched from method bodies. Use for 'find usages at type level'.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				int limit = intArg(request.arguments(), "limit", 200);
				List<String> refs = graph.referencesTo(fqn);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("target", fqn);
				result.put("total", refs.size());
				boolean truncated = limit > 0 && refs.size() > limit;
				result.put("truncated", truncated);
				result.put("referenced_by", truncated ? refs.subList(0, limit) : refs);
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification callersOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "owner": {"type": "string", "description": "FQN of the class declaring the method."},
			    "method": {"type": "string", "description": "Method name (or '<init>' for constructors)."},
			    "descriptor": {"type": "string", "description": "Optional bytecode descriptor (e.g. '(Ljava/lang/String;)I'); omit to match all overloads."},
			    "limit": {"type": "integer", "default": 200}
			  },
			  "required": ["owner", "method"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("callers_of")
				.description("Call sites that invoke the given method. Requires body scan (tl-mcp.bodies=true, the default). Results are exact per bytecode descriptor: a call to a method declared on a supertype is recorded against that supertype, not its overriding subclass.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String owner = stringArg(request.arguments(), "owner");
				String method = stringArg(request.arguments(), "method");
				String descriptor = nullableStringArg(request.arguments(), "descriptor");
				int limit = intArg(request.arguments(), "limit", 200);
				List<TypeGraph.CallerRef> callers = graph.callersOf(owner, method, descriptor);
				List<Map<String, Object>> out = new ArrayList<>();
				for (TypeGraph.CallerRef ref : callers) {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("type", ref.ownerType());
					e.put("method", ref.method());
					e.put("descriptor", ref.descriptor());
					out.add(e);
				}
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("owner", owner);
				result.put("method", method);
				if (descriptor != null) result.put("descriptor", descriptor);
				result.put("total", out.size());
				boolean truncated = limit > 0 && out.size() > limit;
				result.put("truncated", truncated);
				result.put("callers", truncated ? out.subList(0, limit) : out);
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification fieldAccessors(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "owner": {"type": "string"},
			    "field": {"type": "string"},
			    "mode": {"type": "string", "enum": ["read", "write", "any"], "default": "any"},
			    "limit": {"type": "integer", "default": 200}
			  },
			  "required": ["owner", "field"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("field_accessors")
				.description("Methods that read or write a field (from bytecode). Requires body scan. 'mode' selects reads, writes, or both.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String owner = stringArg(request.arguments(), "owner");
				String field = stringArg(request.arguments(), "field");
				String mode = nullableStringArg(request.arguments(), "mode");
				int limit = intArg(request.arguments(), "limit", 200);
				boolean wantRead = mode == null || "any".equalsIgnoreCase(mode) || "read".equalsIgnoreCase(mode);
				boolean wantWrite = mode == null || "any".equalsIgnoreCase(mode) || "write".equalsIgnoreCase(mode);
				List<Map<String, Object>> out = new ArrayList<>();
				if (wantRead) {
					for (TypeGraph.AccessorRef r : graph.fieldReaders(owner, field)) {
						Map<String, Object> e = new LinkedHashMap<>();
						e.put("type", r.ownerType());
						e.put("method", r.method());
						e.put("descriptor", r.descriptor());
						e.put("mode", "read");
						out.add(e);
					}
				}
				if (wantWrite) {
					for (TypeGraph.AccessorRef r : graph.fieldWriters(owner, field)) {
						Map<String, Object> e = new LinkedHashMap<>();
						e.put("type", r.ownerType());
						e.put("method", r.method());
						e.put("descriptor", r.descriptor());
						e.put("mode", "write");
						out.add(e);
					}
				}
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("owner", owner);
				result.put("field", field);
				result.put("total", out.size());
				boolean truncated = limit > 0 && out.size() > limit;
				result.put("truncated", truncated);
				result.put("accessors", truncated ? out.subList(0, limit) : out);
				return jsonResult(json, result);
			})
			.build();
	}

	private static SyncToolSpecification moduleOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {"type": "string"}
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("module_of")
				.description("Returns the Maven module id (or JAR GAV / filename) that supplied the given class. Useful for dependency-graph questions like 'which JAR brings this class in?'.")
				.inputSchema(json, schema)
				.build())
			.callHandler((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				String module = graph.moduleOf(fqn);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("fqn", fqn);
				result.put("found", module != null);
				if (module != null) result.put("module", module);
				return jsonResult(json, result);
			})
			.build();
	}

	// ---------- Argument helpers ----------

	private static String stringArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		return v == null ? "" : v.toString();
	}

	private static String nullableStringArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return null;
		String t = v.toString();
		return t.isEmpty() ? null : t;
	}

	private static List<String> stringListArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return List.of();
		if (v instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object element : list) {
				if (element != null) out.add(element.toString());
			}
			return out;
		}
		return List.of(v.toString());
	}

	private static TypeGraph.MemberKind memberKindArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return TypeGraph.MemberKind.ANY;
		String t = v.toString().trim().toUpperCase();
		if (t.isEmpty()) return TypeGraph.MemberKind.ANY;
		try {
			return TypeGraph.MemberKind.valueOf(t);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid kind '" + v + "'; expected any/method/field.");
		}
	}

	private static TypeGraph.Kind kindArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return TypeGraph.Kind.ANY;
		String t = v.toString().trim().toUpperCase();
		if (t.isEmpty()) return TypeGraph.Kind.ANY;
		try {
			return TypeGraph.Kind.valueOf(t);
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid kind '" + v
				+ "'; expected one of any/class/interface/concrete/enum/annotation.");
		}
	}

	private static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
		Object v = args.get(key);
		if (v == null) return defaultValue;
		if (v instanceof Boolean b) return b;
		return Boolean.parseBoolean(v.toString());
	}

	private static int intArg(Map<String, Object> args, String key, int defaultValue) {
		Object v = args.get(key);
		if (v == null) return defaultValue;
		if (v instanceof Number n) return n.intValue();
		try {
			return Integer.parseInt(v.toString());
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
			return errorResult("Serialization failure: " + ex.getMessage());
		}
	}

	private static CallToolResult errorResult(String message) {
		return CallToolResult.builder()
			.isError(true)
			.content(List.of(new McpSchema.TextContent(message)))
			.build();
	}

}
