/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.top_logic.common.json.adapt.ReaderR;
import com.top_logic.common.json.gstream.JsonReader;
import com.top_logic.xref.model.IndexFile;
import com.top_logic.xref.model.TypeInfo;

/**
 * Aggregated type index loaded from all <code>TypeIndex.json</code> resources on a classpath.
 *
 * <p>
 * Name-based — no class loading is performed. Answers "who specializes X?", "who is annotated
 * with Y?" against the union of indices found in workspace module output dirs and JARs.
 * </p>
 */
public class TypeGraph {

	/** Resource location of the index file inside every TopLogic module/JAR. */
	public static final String INDEX_RESOURCE = "META-INF/com.top_logic.basic.reflect.TypeIndex.json";

	private final Map<String, TypeInfo> _types = new HashMap<>();

	private final Map<String, List<String>> _specializations = new HashMap<>();

	private final Map<String, List<String>> _annotated = new HashMap<>();

	/**
	 * Reads indices from the given classpath entries (directories or JAR files) and returns the
	 * merged {@link TypeGraph}.
	 */
	public static TypeGraph load(List<File> classpath) throws IOException {
		TypeGraph graph = new TypeGraph();
		for (File entry : classpath) {
			if (entry.isDirectory()) {
				File json = new File(entry, INDEX_RESOURCE);
				if (json.isFile()) {
					try (Reader r = Files.newBufferedReader(json.toPath(), StandardCharsets.UTF_8)) {
						graph.merge(r);
					}
				}
			} else if (entry.isFile() && entry.getName().endsWith(".jar")) {
				try (ZipFile zip = new ZipFile(entry)) {
					ZipEntry ze = zip.getEntry(INDEX_RESOURCE);
					if (ze != null) {
						try (InputStream in = zip.getInputStream(ze);
								Reader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
							graph.merge(r);
						}
					}
				}
			}
		}
		return graph;
	}

	private void merge(Reader r) throws IOException {
		IndexFile file = IndexFile.readIndexFile(new JsonReader(new ReaderR(r)));
		for (Map.Entry<String, TypeInfo> entry : file.getTypes().entrySet()) {
			String fqn = entry.getKey();
			TypeInfo info = entry.getValue();

			TypeInfo existing = _types.putIfAbsent(fqn, info);
			if (existing != null) {
				// Duplicate across JARs — first wins, indices from different modules for the same
				// type are expected to agree.
				continue;
			}

			for (String supertype : info.getGeneralizations()) {
				_specializations.computeIfAbsent(supertype, k -> new ArrayList<>()).add(fqn);
			}
			for (String annotation : info.getAnnotations().keySet()) {
				_annotated.computeIfAbsent(annotation, k -> new ArrayList<>()).add(fqn);
			}
		}
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
	 * All known types whose FQN ends with {@code "." + simpleName}, plus an exact FQN match if it
	 * exists.
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

	/** Direct or transitive specializations (subtypes + sub-interfaces) of a type. */
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

	/** Direct or transitive generalizations (superclasses + super-interfaces) of a type. */
	public List<String> generalizationsOf(String fqn, boolean transitive) {
		TypeInfo info = _types.get(fqn);
		if (info == null) {
			return List.of();
		}
		if (!transitive) {
			return sorted(info.getGeneralizations());
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
		for (String sup : info.getGeneralizations()) {
			collectGeneralizations(sup, seen);
		}
	}

	/**
	 * All transitive, public, concrete (non-abstract, non-interface) specializations of a type.
	 */
	public List<String> implementorsOf(String fqn) {
		List<String> subs = specializationsOf(fqn, true);
		List<String> result = new ArrayList<>();
		for (String sub : subs) {
			TypeInfo info = _types.get(sub);
			if (info != null && info.isPublic() && !info.isAbstract() && !info.isInterface()) {
				result.add(sub);
			}
		}
		return result;
	}

	/** All types carrying the given annotation (FQN of the annotation class). */
	public List<String> annotatedWith(String annotationFqn) {
		List<String> hits = _annotated.get(annotationFqn);
		return hits == null ? List.of() : sorted(hits);
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
		out.put("supertypes", new ArrayList<>(info.getGeneralizations()));
		out.put("annotations", new ArrayList<>(new TreeSet<>(info.getAnnotations().keySet())));
		if (info.getConfiguration() != null && !info.getConfiguration().isEmpty()) {
			out.put("configuration", info.getConfiguration());
		}
		if (info.getImplementation() != null && !info.getImplementation().isEmpty()) {
			out.put("implementation", info.getImplementation());
		}
		return out;
	}

	private static List<String> sorted(Collection<String> in) {
		List<String> out = new ArrayList<>(new TreeSet<>(in));
		return out;
	}

}
