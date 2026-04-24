/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

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
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
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
			    "visibility": {
			      "type": "array",
			      "items": {"type": "string", "enum": ["public", "protected", "package", "private"]},
			      "description": "Java access levels to include. Default: public only. Pass multiple values or all four to widen."
			    },
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
			.callHandler(safe((exchange, request) -> {
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
						visibilitiesArg(args, "visibility", true),
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
			}))
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
			.callHandler(safe((exchange, request) -> {
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
			}))
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
			    "visibility": {
			      "type": "array",
			      "items": {"type": "string", "enum": ["public", "protected", "package", "private"]},
			      "description": "Java access levels to include. Default: all four."
			    },
			    "static": {
			      "type": "boolean",
			      "description": "Tri-state: true = only static members, false = only instance members, omitted = both."
			    },
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
			.callHandler(safe((exchange, request) -> {
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
						visibilitiesArg(args, "visibility", false),
						nullableBoolArg(args, "static"),
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
			}))
			.build();
	}

	private static SyncToolSpecification referencesTo(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "fqn": {"type": "string", "description": "Target type whose usages are returned."},
			    "kinds": {
			      "type": "array",
			      "items": {"type": "string", "enum": [
			        "supertype", "type_annotation",
			        "field_type", "field_annotation",
			        "method_return", "method_parameter", "method_exception", "method_annotation",
			        "instantiation", "cast", "instanceof", "catch",
			        "call_dispatch", "field_read", "field_write"
			      ]},
			      "description": "Restrict to these usage kinds. Declaration-level kinds are always available; body-level kinds (instantiation, cast, instanceof, catch, call_dispatch, field_read, field_write) require body scan (tl-mcp.bodies=true, the default)."
			    },
			    "owner_pattern": {"type": "string", "description": "Java regex matched (Matcher#find) against the declaring class FQN of each usage."},
			    "in_module": {"type": "string", "description": "Restrict to usages whose declaring class lives in this module id."},
			    "limit": {"type": "integer", "default": 200}
			  },
			  "required": ["fqn"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("references_to")
				.description("Concrete usage sites of a type, Eclipse 'Search Java' style. Each result identifies the owning type, member (if applicable), and the kind of usage (supertype, method_return, method_parameter, field_type, instantiation, cast, catch, call_dispatch, field_read, etc.). Response also carries a per-kind histogram.")
				.inputSchema(json, schema)
				.build())
			.callHandler(safe((exchange, request) -> {
				Map<String, Object> args = request.arguments();
				String fqn = stringArg(args, "fqn");
				try {
					Set<TypeGraph.UsageKind> kinds = usageKindsArg(args, "kinds");
					String ownerPattern = nullableStringArg(args, "owner_pattern");
					String ownerModule = nullableStringArg(args, "in_module");
					int limit = intArg(args, "limit", 200);
					TypeGraph.UsageResult r = graph.referencesTo(fqn, kinds, ownerPattern, ownerModule, limit);
					List<Map<String, Object>> out = new ArrayList<>(r.usages().size());
					for (TypeGraph.Usage u : r.usages()) {
						Map<String, Object> e = new LinkedHashMap<>();
						e.put("owner", u.ownerType());
						if (u.member() != null) e.put("member", u.member());
						if (u.descriptor() != null) e.put("descriptor", u.descriptor());
						if (u.targetMember() != null) e.put("target_member", u.targetMember());
						if (u.targetDescriptor() != null) e.put("target_descriptor", u.targetDescriptor());
						e.put("kind", u.kind().name().toLowerCase());
						e.put("count", u.count());
						out.add(e);
					}
					Map<String, Integer> histogram = new LinkedHashMap<>();
					for (Map.Entry<TypeGraph.UsageKind, Integer> e : r.byKind().entrySet()) {
						histogram.put(e.getKey().name().toLowerCase(), e.getValue());
					}
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("target", fqn);
					result.put("total", r.total());
					result.put("truncated", r.truncated());
					result.put("by_kind", histogram);
					result.put("usages", out);
					return jsonResult(json, result);
				} catch (IllegalArgumentException ex) {
					return errorResult(ex.getMessage());
				}
			}))
			.build();
	}

	private static Set<TypeGraph.UsageKind> usageKindsArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return Set.of();
		List<?> items = v instanceof List<?> list ? list : List.of(v);
		if (items.isEmpty()) return Set.of();
		Set<TypeGraph.UsageKind> out = java.util.EnumSet.noneOf(TypeGraph.UsageKind.class);
		for (Object item : items) {
			if (item == null) continue;
			String s = item.toString().trim().toUpperCase();
			if (s.isEmpty()) continue;
			try {
				out.add(TypeGraph.UsageKind.valueOf(s));
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid usage kind '" + item + "'.");
			}
		}
		return out;
	}

	private static SyncToolSpecification callersOf(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "owner": {"type": "string", "description": "FQN of the class declaring the method."},
			    "method": {"type": "string", "description": "Method name (or '<init>' for constructors)."},
			    "descriptor": {"type": "string", "description": "Optional bytecode descriptor (e.g. '(Ljava/lang/String;)I'); omit to match all overloads."},
			    "include_overrides": {"type": "boolean", "default": true, "description": "When true (default), also return call sites whose static owner is a subtype of 'owner' that declares an override with matching name and descriptor. Turn off for strict bytecode-owner matching."},
			    "limit": {"type": "integer", "default": 200}
			  },
			  "required": ["owner", "method"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("callers_of")
				.description("Call sites that invoke the given method. Requires body scan (tl-mcp.bodies=true, the default). Calls are recorded in bytecode against the static declared owner, so by default we also include every subtype that declares an override of the same (name, descriptor) — a 'this.foo()' inside an overriding subclass then still counts as a caller of the supertype's method. Disable via include_overrides=false for strict matching.")
				.inputSchema(json, schema)
				.build())
			.callHandler(safe((exchange, request) -> {
				String owner = stringArg(request.arguments(), "owner");
				String method = stringArg(request.arguments(), "method");
				String descriptor = nullableStringArg(request.arguments(), "descriptor");
				boolean includeOverrides = boolArg(request.arguments(), "include_overrides", true);
				int limit = intArg(request.arguments(), "limit", 200);
				List<TypeGraph.CallerRef> callers = graph.callersOf(owner, method, descriptor, includeOverrides);
				List<Map<String, Object>> out = new ArrayList<>();
				for (TypeGraph.CallerRef ref : callers) {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("type", ref.ownerType());
					e.put("method", ref.method());
					e.put("descriptor", ref.descriptor());
					e.put("count", ref.count());
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
			}))
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
			.callHandler(safe((exchange, request) -> {
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
						e.put("count", r.count());
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
						e.put("count", r.count());
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
			}))
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
			.callHandler(safe((exchange, request) -> {
				String fqn = stringArg(request.arguments(), "fqn");
				String module = graph.moduleOf(fqn);
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("fqn", fqn);
				result.put("found", module != null);
				if (module != null) result.put("module", module);
				return jsonResult(json, result);
			}))
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

	private static Boolean nullableBoolArg(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return null;
		if (v instanceof Boolean b) return b;
		String s = v.toString().trim().toLowerCase();
		if (s.isEmpty()) return null;
		return Boolean.valueOf(s);
	}

	/**
	 * @param publicOnlyDefault
	 *        When the caller omits 'visibility', restrict to public only ({@code true}) or allow
	 *        any level ({@code false}). An empty array also means "any level".
	 */
	private static Set<TypeInfo.Visibility> visibilitiesArg(Map<String, Object> args, String key,
			boolean publicOnlyDefault) {
		Object v = args.get(key);
		if (v == null) {
			return publicOnlyDefault ? java.util.EnumSet.of(TypeInfo.Visibility.PUBLIC) : Set.of();
		}
		List<?> items = v instanceof List<?> list ? list : List.of(v);
		if (items.isEmpty()) return Set.of();
		Set<TypeInfo.Visibility> out = java.util.EnumSet.noneOf(TypeInfo.Visibility.class);
		for (Object item : items) {
			if (item == null) continue;
			String s = item.toString().trim().toUpperCase();
			if (s.isEmpty()) continue;
			try {
				out.add(TypeInfo.Visibility.valueOf(s));
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid visibility '" + item
					+ "'; expected one of public/protected/package/private.");
			}
		}
		return out;
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

	/**
	 * Wraps a tool handler so that any uncaught {@link Throwable} turns into a structured error
	 * result instead of leaving the MCP client waiting for a response that never arrives.
	 */
	private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> safe(
			BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> inner) {
		return (exchange, request) -> {
			try {
				return inner.apply(exchange, request);
			} catch (Throwable t) {
				StringWriter sw = new StringWriter();
				t.printStackTrace(new PrintWriter(sw));
				String message = t.getClass().getName() + ": "
					+ (t.getMessage() == null ? "(no message)" : t.getMessage())
					+ "\n" + sw.toString().trim();
				return errorResult(message);
			}
		};
	}

}
