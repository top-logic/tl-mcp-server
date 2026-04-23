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

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
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
		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

		McpSyncServer server = McpServer.sync(transport)
			.serverInfo("tl-mcp-server", "0.1.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				.tools(false)
				.build())
			.build();

		server.addTool(findType(jsonMapper, graph));
		server.addTool(subtypesOf(jsonMapper, graph));
		server.addTool(supertypesOf(jsonMapper, graph));
		server.addTool(implementorsOf(jsonMapper, graph));
		server.addTool(annotatedWith(jsonMapper, graph));

		try {
			Thread.currentThread().join();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static SyncToolSpecification findType(McpJsonMapper json, TypeGraph graph) {
		String schema = """
			{
			  "type": "object",
			  "properties": {
			    "name": {
			      "type": "string",
			      "description": "Fully qualified class name, or a simple name to search by suffix."
			    }
			  },
			  "required": ["name"]
			}
			""";
		return SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("find_type")
				.description("Look up type metadata. Accepts a fully qualified name, or a simple name in which case all types whose FQN ends with that name are returned.")
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
