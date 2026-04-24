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

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TypeGraph.MemberSpec}: parsing + overload matching. */
class MemberSpecTest {

	@Test
	void parse_nameOnly_hasNullParamTypes() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo");
		assertNotNull(spec);
		assertEquals("foo", spec.name());
		assertNull(spec.paramTypes(), "no parens → match any overload");
	}

	@Test
	void parse_emptyParens_marksZeroArg() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo()");
		assertEquals(List.of(), spec.paramTypes());
	}

	@Test
	void parse_simpleName_singleParam() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("setToolBar(ToolBar)");
		assertEquals("setToolBar", spec.name());
		assertEquals(List.of("ToolBar"), spec.paramTypes());
	}

	@Test
	void parse_fqn_singleParam() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("setToolBar(com.top_logic.layout.ToolBar)");
		assertEquals(List.of("com.top_logic.layout.ToolBar"), spec.paramTypes());
	}

	@Test
	void parse_multipleParams_withSpacesAndGenerics() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo(Map<String, Integer>, int, Class<?>)");
		assertEquals(List.of("Map<String, Integer>", "int", "Class<?>"), spec.paramTypes());
	}

	@Test
	void parse_stripsTrailingParameterName() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo(ToolBar tb)");
		assertEquals(List.of("ToolBar"), spec.paramTypes());
	}

	@Test
	void parse_whitespaceOnly_returnsNull() {
		assertNull(TypeGraph.MemberSpec.parse("   "));
		assertNull(TypeGraph.MemberSpec.parse(null));
		assertNull(TypeGraph.MemberSpec.parse(""));
	}

	@Test
	void matches_nameOnly_matchesAnyOverload() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo");
		assertTrue(spec.matches(method("foo", List.of())));
		assertTrue(spec.matches(method("foo", List.of("java.lang.String"))));
		assertFalse(spec.matches(method("bar", List.of())));
	}

	@Test
	void matches_simpleNameParam_matchesFqn() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("setToolBar(ToolBar)");
		assertTrue(spec.matches(method("setToolBar", List.of("com.top_logic.layout.toolbar.ToolBar"))));
	}

	@Test
	void matches_fqnParam_matchesActual() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse(
			"setToolBar(com.top_logic.layout.toolbar.ToolBar)");
		assertTrue(spec.matches(method("setToolBar", List.of("com.top_logic.layout.toolbar.ToolBar"))));
	}

	@Test
	void matches_wrongArity_rejects() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("foo(String)");
		assertFalse(spec.matches(method("foo", List.of())));
		assertFalse(spec.matches(method("foo", List.of("java.lang.String", "int"))));
	}

	@Test
	void matches_differentParamType_rejects() {
		TypeGraph.MemberSpec spec = TypeGraph.MemberSpec.parse("setToolBar(String)");
		assertFalse(spec.matches(method("setToolBar", List.of("com.top_logic.layout.toolbar.ToolBar"))));
	}

	private static TypeInfo.MethodInfo method(String name, List<String> paramTypes) {
		List<TypeInfo.Parameter> params = new java.util.ArrayList<>();
		for (String type : paramTypes) params.add(new TypeInfo.Parameter(null, type));
		return new TypeInfo.MethodInfo(name, "()V", 1, "void", params, List.of(), List.of(),
			List.of(), List.of(), List.of(), List.of(), -1, -1);
	}

}
