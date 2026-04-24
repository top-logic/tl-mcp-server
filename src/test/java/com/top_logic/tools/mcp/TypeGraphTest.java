/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that build a {@link TypeGraph} from the plugin's own {@code target/classes} +
 * {@code src/main/java} (populated by the compile phase before surefire runs) and assert against
 * known facts about this project's classes.
 */
class TypeGraphTest {

	private static final String SERVE_MOJO = "com.top_logic.tools.mcp.ServeMojo";
	private static final String TYPE_GRAPH = "com.top_logic.tools.mcp.TypeGraph";
	private static final String BYTECODE_SCANNER = "com.top_logic.tools.mcp.BytecodeScanner";
	private static final String TYPE_INFO = "com.top_logic.tools.mcp.TypeInfo";

	private static TypeGraph _graph;

	@BeforeAll
	static void buildGraph() throws Exception {
		File classes = new File("target/classes");
		assumeTrue(classes.isDirectory(), "target/classes missing — run `mvn compile` first");
		File src = new File("src/main/java");
		BytecodeScanner.Source source = new BytecodeScanner.Source(classes, "tl-mcp-server-self",
			src.isDirectory() ? src : null);
		_graph = TypeGraph.load(List.of(source), false, true);
	}

	@Test
	void scan_indexesOwnClasses() {
		Set<String> all = _graph.allTypes();
		assertTrue(all.contains(SERVE_MOJO));
		assertTrue(all.contains(TYPE_GRAPH));
		assertTrue(all.contains(BYTECODE_SCANNER));
	}

	@Test
	void describe_serveMojo_exposesSuperclassAndModule() {
		var desc = _graph.describe(SERVE_MOJO);
		assertNotNull(desc);
		assertEquals("public", desc.get("visibility"));
		assertEquals("tl-mcp-server-self", desc.get("module"));
		assertEquals("org.apache.maven.plugin.AbstractMojo", desc.get("superclass"));
		assertEquals("ServeMojo.java", desc.get("sourceFile"));
		// @Mojo annotation carried over
		@SuppressWarnings("unchecked")
		List<java.util.Map<String, Object>> anns = (List<java.util.Map<String, Object>>) desc.get("annotations");
		assertNotNull(anns);
		assertTrue(anns.stream().anyMatch(a -> "org.apache.maven.plugins.annotations.Mojo".equals(a.get("name"))));
	}

	@Test
	void query_subtypeOfAbstractMojo_yieldsServeMojo() {
		TypeGraph.TypeQuery q = new TypeGraph.TypeQuery(
			null, null, false,
			"org.apache.maven.plugin.AbstractMojo", null, false,
			List.of(), null, TypeGraph.Kind.CONCRETE,
			Set.of(TypeInfo.Visibility.PUBLIC),
			100);
		TypeGraph.QueryResult r = _graph.query(q);
		assertTrue(r.matches().contains(SERVE_MOJO), r.matches().toString());
	}

	@Test
	void references_to_TypeInfo_includeServeMojoIsFalse_describeDoes() {
		// ServeMojo doesn't reference TypeInfo directly; TypeGraph does.
		TypeGraph.UsageResult r = _graph.referencesTo(TYPE_INFO, Set.of(), null, null, 500);
		assertTrue(r.total() > 0, "TypeGraph should reference TypeInfo");
		boolean fromTypeGraph = r.usages().stream().anyMatch(u -> TYPE_GRAPH.equals(u.ownerType()));
		assertTrue(fromTypeGraph);
	}

	@Test
	void callers_of_resolveMethod_areReachedFromSourceOf() throws Exception {
		// TypeGraph.sourceOf() calls resolveMethod() internally; the call graph should see that.
		List<TypeGraph.CallerRef> callers = _graph.callersOf(TYPE_GRAPH, "resolveMethod", true);
		assertFalse(callers.isEmpty());
		boolean fromSourceOf = callers.stream().anyMatch(c -> "sourceOf".equals(c.method()));
		assertTrue(fromSourceOf, callers.toString());
	}

	@Test
	void show_source_docMode_onClass_returnsHeaderOnly() throws Exception {
		TypeGraph.SourceResult r = _graph.sourceOf(TYPE_GRAPH, null, TypeGraph.SourceMode.DOC, 0);
		assertNotNull(r);
		assertTrue(r.text().contains("public class TypeGraph"));
		assertFalse(r.text().contains("private final Map<String, TypeInfo> _types;"),
			"doc mode must not expose fields");
	}

	@Test
	void show_source_sourceMode_onMethod_includesBody() throws Exception {
		TypeGraph.SourceResult r = _graph.sourceOf(TYPE_GRAPH, "query",
			TypeGraph.SourceMode.SOURCE, 0);
		assertNotNull(r);
		assertTrue(r.text().contains("public QueryResult query"));
		assertTrue(r.text().contains("return new QueryResult"), "body should be included");
	}

	@Test
	void show_source_overloadDisambiguation() throws Exception {
		// specializationsOf has two overloads: (String) and (String, boolean).
		TypeGraph.SourceResult single = _graph.sourceOf(TYPE_GRAPH, "specializationsOf(String, boolean)",
			TypeGraph.SourceMode.DOC, 0);
		assertNotNull(single);
		assertTrue(single.text().contains("specializationsOf(String fqn, boolean transitive)"),
			single.text());
	}

	@Test
	void module_of_knownClass() {
		assertEquals("tl-mcp-server-self", _graph.moduleOf(TYPE_GRAPH));
		assertNull(_graph.moduleOf("does.not.Exist"));
	}

}
