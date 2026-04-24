/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Aggregated type index built by scanning class-file bytecode across a classpath.
 *
 * <p>
 * Name-based — no classes are loaded. Answers "who specializes X?", "who is annotated with Y?"
 * against the union of classes found in the given directories and JARs.
 * </p>
 */
public class TypeGraph {

	private final Map<String, TypeInfo> _types;

	private final Map<String, List<String>> _specializations = new HashMap<>();

	private final Map<String, List<String>> _annotated = new HashMap<>();

	private TypeGraph(Map<String, TypeInfo> types) {
		_types = types;
		for (TypeInfo info : types.values()) {
			for (String supertype : info.supertypes()) {
				_specializations.computeIfAbsent(supertype, k -> new ArrayList<>()).add(info.name());
			}
			for (String annotation : info.annotations()) {
				_annotated.computeIfAbsent(annotation, k -> new ArrayList<>()).add(info.name());
			}
		}
	}

	/**
	 * Reads class files from the given classpath entries (directories or JAR files) and returns
	 * the resulting {@link TypeGraph}.
	 */
	public static TypeGraph load(List<File> classpath) throws IOException {
		return load(classpath, false);
	}

	/**
	 * @param includeJdk
	 *        When {@code true}, the JDK's own classes (JRT filesystem) are included in the scan.
	 */
	public static TypeGraph load(List<File> classpath, boolean includeJdk) throws IOException {
		Map<String, TypeInfo> types = BytecodeScanner.scan(classpath, includeJdk);
		return new TypeGraph(types);
	}

	/** Total number of indexed types. */
	public int size() {
		return _types.size();
	}

	/** All indexed fully-qualified type names. */
	public Set<String> allTypes() {
		return Collections.unmodifiableSet(_types.keySet());
	}

	/** Metadata for a type, or {@code null} if unknown. */
	public TypeInfo get(String fqn) {
		return _types.get(fqn);
	}

	/**
	 * Types whose FQN matches an exact name. When {@code name} contains no '.', it is treated as
	 * a simple name and matched at '.' or '$' boundaries (so "Component" does not match
	 * "LayoutComponent").
	 */
	public List<String> findByName(String name) {
		if (name == null || name.isEmpty()) {
			return List.of();
		}
		if (_types.containsKey(name)) {
			return List.of(name);
		}
		String suffix = "." + name;
		String innerSuffix = "$" + name;
		List<String> hits = new ArrayList<>();
		for (String fqn : _types.keySet()) {
			if (fqn.endsWith(suffix) || fqn.endsWith(innerSuffix)) {
				hits.add(fqn);
			}
		}
		Collections.sort(hits);
		return hits;
	}

	/** Direct or transitive specializations of a type. */
	public List<String> specializationsOf(String fqn, boolean transitive) {
		if (!transitive) {
			List<String> direct = _specializations.get(fqn);
			if (direct == null) {
				return List.of();
			}
			return sorted(direct);
		}
		Set<String> seen = new HashSet<>();
		collectSpecializations(fqn, seen);
		seen.remove(fqn);
		return sorted(seen);
	}

	private void collectSpecializations(String fqn, Set<String> seen) {
		if (!seen.add(fqn)) {
			return;
		}
		List<String> direct = _specializations.get(fqn);
		if (direct == null) {
			return;
		}
		for (String sub : direct) {
			collectSpecializations(sub, seen);
		}
	}

	/** Direct or transitive generalizations of a type. */
	public List<String> generalizationsOf(String fqn, boolean transitive) {
		TypeInfo info = _types.get(fqn);
		if (info == null) {
			return List.of();
		}
		if (!transitive) {
			return sorted(info.supertypes());
		}
		Set<String> seen = new HashSet<>();
		collectGeneralizations(fqn, seen);
		seen.remove(fqn);
		return sorted(seen);
	}

	private void collectGeneralizations(String fqn, Set<String> seen) {
		if (!seen.add(fqn)) {
			return;
		}
		TypeInfo info = _types.get(fqn);
		if (info == null) {
			return;
		}
		for (String sup : info.supertypes()) {
			collectGeneralizations(sup, seen);
		}
	}

	/** Kind filter values for {@link TypeQuery#kind()}. */
	public enum Kind {
		ANY, CLASS, INTERFACE, CONCRETE
	}

	/**
	 * Composite query spec: all non-null/non-empty fields are AND-combined.
	 */
	public record TypeQuery(
			String name,
			String pattern,
			boolean regex,
			String subtypeOf,
			String supertypeOf,
			boolean directOnly,
			List<String> annotatedWith,
			Kind kind,
			boolean publicOnly,
			int limit) {
	}

	/** Result of a {@link #query(TypeQuery)} call. */
	public record QueryResult(List<String> matches, int total, boolean truncated) {
	}

	/**
	 * Run a composite query against the index. See {@link TypeQuery} for the filter semantics.
	 */
	public QueryResult query(TypeQuery q) {
		Collection<String> candidates;
		if (q.subtypeOf() != null && !q.subtypeOf().isEmpty()) {
			candidates = specializationsOf(q.subtypeOf(), !q.directOnly());
		} else if (q.supertypeOf() != null && !q.supertypeOf().isEmpty()) {
			candidates = generalizationsOf(q.supertypeOf(), !q.directOnly());
		} else {
			candidates = _types.keySet();
		}

		Pattern pattern = null;
		String needle = null;
		if (q.pattern() != null && !q.pattern().isEmpty()) {
			if (q.regex()) {
				try {
					pattern = Pattern.compile(q.pattern());
				} catch (PatternSyntaxException ex) {
					throw new IllegalArgumentException("Invalid regex: " + ex.getMessage(), ex);
				}
			} else {
				needle = q.pattern().toLowerCase();
			}
		}

		Set<String> nameMatches = null;
		if (q.name() != null && !q.name().isEmpty()) {
			nameMatches = new HashSet<>(findByName(q.name()));
		}

		Kind kind = q.kind() == null ? Kind.ANY : q.kind();
		List<String> required = q.annotatedWith();
		boolean hasRequired = required != null && !required.isEmpty();

		List<String> result = new ArrayList<>();
		for (String fqn : candidates) {
			TypeInfo info = _types.get(fqn);
			if (info == null) {
				continue;
			}
			if (nameMatches != null && !nameMatches.contains(fqn)) {
				continue;
			}
			if (pattern != null && !pattern.matcher(fqn).find()) {
				continue;
			}
			if (needle != null && !fqn.toLowerCase().contains(needle)) {
				continue;
			}
			if (q.publicOnly() && !info.isPublic()) {
				continue;
			}
			switch (kind) {
				case CLASS:
					if (info.isInterface()) continue;
					break;
				case INTERFACE:
					if (!info.isInterface()) continue;
					break;
				case CONCRETE:
					if (info.isInterface() || info.isAbstract()) continue;
					break;
				case ANY:
				default:
					break;
			}
			if (hasRequired) {
				Set<String> annotations = info.annotations();
				boolean allPresent = true;
				for (String ann : required) {
					if (!annotations.contains(ann)) {
						allPresent = false;
						break;
					}
				}
				if (!allPresent) {
					continue;
				}
			}
			result.add(fqn);
		}

		Collections.sort(result);
		int total = result.size();
		boolean truncated = false;
		if (q.limit() > 0 && total > q.limit()) {
			result = new ArrayList<>(result.subList(0, q.limit()));
			truncated = true;
		}
		return new QueryResult(result, total, truncated);
	}

	/** Summary describing the type as a map for JSON serialization. */
	public Map<String, Object> describe(String fqn) {
		TypeInfo info = _types.get(fqn);
		if (info == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", fqn);
		out.put("public", info.isPublic());
		out.put("interface", info.isInterface());
		out.put("abstract", info.isAbstract());
		out.put("supertypes", new ArrayList<>(info.supertypes()));
		out.put("annotations", new ArrayList<>(new TreeSet<>(info.annotations())));
		if (info.configuration() != null) {
			out.put("configuration", info.configuration());
		}
		if (info.implementation() != null) {
			out.put("implementation", info.implementation());
		}
		return out;
	}

	private static List<String> sorted(Collection<String> in) {
		return new ArrayList<>(new TreeSet<>(in));
	}

}
