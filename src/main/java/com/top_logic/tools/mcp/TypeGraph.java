/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.top_logic.tools.mcp.TypeInfo.AnnotationInfo;
import com.top_logic.tools.mcp.TypeInfo.FieldAccess;
import com.top_logic.tools.mcp.TypeInfo.FieldInfo;
import com.top_logic.tools.mcp.TypeInfo.MethodInfo;
import com.top_logic.tools.mcp.TypeInfo.Parameter;

/**
 * Aggregated type index built by scanning class-file bytecode across a classpath. Name-based; no
 * classes are loaded. Exposes hierarchy, annotation, reference and call-graph queries.
 */
public class TypeGraph {

	private final Map<String, TypeInfo> _types;

	/** supertype FQN → direct subtype FQNs (covers both superclass and super-interface relations). */
	private final Map<String, List<String>> _specializations = new HashMap<>();

	/** annotation FQN → types carrying it at the class level. */
	private final Map<String, List<String>> _annotated = new HashMap<>();

	/** referenced FQN → referencing FQNs (class-level + member-signature references). */
	private final Map<String, Set<String>> _referencedBy = new HashMap<>();

	/** moduleId → types in that module. */
	private final Map<String, List<String>> _byModule = new HashMap<>();

	/** "owner#name#descriptor" → callers (FQN of caller type + caller method name + descriptor). */
	private final Map<String, List<CallerRef>> _callers = new HashMap<>();

	/** "owner#name" → readers (caller type + caller method). */
	private final Map<String, List<AccessorRef>> _fieldReaders = new HashMap<>();

	/** "owner#name" → writers. */
	private final Map<String, List<AccessorRef>> _fieldWriters = new HashMap<>();

	public record CallerRef(String ownerType, String method, String descriptor) {
	}

	public record AccessorRef(String ownerType, String method, String descriptor) {
	}

	private TypeGraph(Map<String, TypeInfo> types) {
		_types = types;
		for (TypeInfo info : types.values()) {
			for (String supertype : info.supertypes()) {
				_specializations.computeIfAbsent(supertype, k -> new ArrayList<>()).add(info.name());
			}
			for (AnnotationInfo ann : info.annotations()) {
				_annotated.computeIfAbsent(ann.name(), k -> new ArrayList<>()).add(info.name());
			}
			if (info.moduleId() != null) {
				_byModule.computeIfAbsent(info.moduleId(), k -> new ArrayList<>()).add(info.name());
			}
			collectReferences(info);
			indexBodies(info);
		}
	}

	private void collectReferences(TypeInfo info) {
		Set<String> refs = new LinkedHashSet<>();
		if (info.superclass() != null) refs.add(info.superclass());
		refs.addAll(info.interfaces());
		for (AnnotationInfo ann : info.annotations()) refs.add(ann.name());
		for (MethodInfo m : info.methods()) {
			addTypeRef(refs, m.returnType());
			for (Parameter p : m.parameters()) addTypeRef(refs, p.type());
			refs.addAll(m.exceptions());
			for (AnnotationInfo ann : m.annotations()) refs.add(ann.name());
		}
		for (FieldInfo f : info.fields()) {
			addTypeRef(refs, f.type());
			for (AnnotationInfo ann : f.annotations()) refs.add(ann.name());
		}
		refs.remove(info.name());
		for (String target : refs) {
			_referencedBy.computeIfAbsent(target, k -> new HashSet<>()).add(info.name());
		}
	}

	private void indexBodies(TypeInfo info) {
		for (MethodInfo m : info.methods()) {
			Set<String> methodRefs = new LinkedHashSet<>();
			for (var call : m.calls()) {
				addTypeRef(methodRefs, call.owner());
				String key = call.owner() + "#" + call.name() + "#" + call.descriptor();
				_callers.computeIfAbsent(key, k -> new ArrayList<>())
					.add(new CallerRef(info.name(), m.name(), m.descriptor()));
			}
			for (FieldAccess fa : m.fieldAccesses()) {
				addTypeRef(methodRefs, fa.owner());
				String key = fa.owner() + "#" + fa.name();
				AccessorRef ref = new AccessorRef(info.name(), m.name(), m.descriptor());
				if (fa.write()) {
					_fieldWriters.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
				} else {
					_fieldReaders.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
				}
			}
			methodRefs.remove(info.name());
			for (String target : methodRefs) {
				_referencedBy.computeIfAbsent(target, k -> new HashSet<>()).add(info.name());
			}
		}
	}

	private static void addTypeRef(Set<String> refs, String type) {
		if (type == null || type.isEmpty()) return;
		// strip array brackets
		while (type.endsWith("[]")) {
			type = type.substring(0, type.length() - 2);
		}
		if (isPrimitive(type) || type.equals("void") || type.startsWith("[")) return;
		refs.add(type);
	}

	private static boolean isPrimitive(String name) {
		return switch (name) {
			case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" -> true;
			default -> false;
		};
	}

	// ---------- Loading ----------

	public static TypeGraph load(List<BytecodeScanner.Source> classpath, boolean includeJdk, boolean scanBodies)
			throws IOException {
		Map<String, TypeInfo> types = BytecodeScanner.scan(classpath, includeJdk, scanBodies);
		return new TypeGraph(types);
	}

	public int size() { return _types.size(); }

	public Set<String> allTypes() { return Collections.unmodifiableSet(_types.keySet()); }

	public TypeInfo get(String fqn) { return _types.get(fqn); }

	// ---------- Name lookup ----------

	public List<String> findByName(String name) {
		if (name == null || name.isEmpty()) return List.of();
		if (_types.containsKey(name)) return List.of(name);
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

	// ---------- Hierarchy walks ----------

	public List<String> specializationsOf(String fqn, boolean transitive) {
		if (!transitive) {
			List<String> direct = _specializations.get(fqn);
			return direct == null ? List.of() : sorted(direct);
		}
		Set<String> seen = new HashSet<>();
		collectSpecializations(fqn, seen);
		seen.remove(fqn);
		return sorted(seen);
	}

	private void collectSpecializations(String fqn, Set<String> seen) {
		if (!seen.add(fqn)) return;
		List<String> direct = _specializations.get(fqn);
		if (direct == null) return;
		for (String sub : direct) collectSpecializations(sub, seen);
	}

	public List<String> generalizationsOf(String fqn, boolean transitive) {
		TypeInfo info = _types.get(fqn);
		if (info == null) return List.of();
		if (!transitive) return sorted(info.supertypes());
		Set<String> seen = new HashSet<>();
		collectGeneralizations(fqn, seen);
		seen.remove(fqn);
		return sorted(seen);
	}

	private void collectGeneralizations(String fqn, Set<String> seen) {
		if (!seen.add(fqn)) return;
		TypeInfo info = _types.get(fqn);
		if (info == null) return;
		for (String sup : info.supertypes()) collectGeneralizations(sup, seen);
	}

	// ---------- Modules ----------

	public String moduleOf(String fqn) {
		TypeInfo info = _types.get(fqn);
		return info == null ? null : info.moduleId();
	}

	public List<String> typesInModule(String moduleId) {
		List<String> list = _byModule.get(moduleId);
		return list == null ? List.of() : sorted(list);
	}

	public Set<String> allModules() {
		return Collections.unmodifiableSet(_byModule.keySet());
	}

	// ---------- References & calls ----------

	public List<String> referencesTo(String fqn) {
		Set<String> refs = _referencedBy.get(fqn);
		return refs == null ? List.of() : sorted(refs);
	}

	public List<CallerRef> callersOf(String owner, String method, String descriptor) {
		if (descriptor != null && !descriptor.isEmpty()) {
			List<CallerRef> direct = _callers.get(owner + "#" + method + "#" + descriptor);
			return direct == null ? List.of() : new ArrayList<>(direct);
		}
		// method-only: union over all descriptors
		List<CallerRef> all = new ArrayList<>();
		String prefix = owner + "#" + method + "#";
		for (Map.Entry<String, List<CallerRef>> e : _callers.entrySet()) {
			if (e.getKey().startsWith(prefix)) all.addAll(e.getValue());
		}
		return all;
	}

	public List<AccessorRef> fieldReaders(String owner, String field) {
		List<AccessorRef> r = _fieldReaders.get(owner + "#" + field);
		return r == null ? List.of() : new ArrayList<>(r);
	}

	public List<AccessorRef> fieldWriters(String owner, String field) {
		List<AccessorRef> r = _fieldWriters.get(owner + "#" + field);
		return r == null ? List.of() : new ArrayList<>(r);
	}

	// ---------- Query ----------

	public enum Kind {
		ANY, CLASS, INTERFACE, CONCRETE, ENUM, ANNOTATION
	}

	public record TypeQuery(
			String name,
			String pattern,
			boolean regex,
			String subtypeOf,
			String supertypeOf,
			boolean directOnly,
			List<String> annotatedWith,
			String inModule,
			Kind kind,
			boolean publicOnly,
			int limit) {
	}

	public record QueryResult(List<String> matches, int total, boolean truncated) {
	}

	public QueryResult query(TypeQuery q) {
		Collection<String> candidates;
		if (q.subtypeOf() != null && !q.subtypeOf().isEmpty()) {
			candidates = specializationsOf(q.subtypeOf(), !q.directOnly());
		} else if (q.supertypeOf() != null && !q.supertypeOf().isEmpty()) {
			candidates = generalizationsOf(q.supertypeOf(), !q.directOnly());
		} else if (q.inModule() != null && !q.inModule().isEmpty()) {
			candidates = typesInModule(q.inModule());
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
		String moduleFilter = q.inModule();

		List<String> result = new ArrayList<>();
		for (String fqn : candidates) {
			TypeInfo info = _types.get(fqn);
			if (info == null) continue;
			if (nameMatches != null && !nameMatches.contains(fqn)) continue;
			if (pattern != null && !pattern.matcher(fqn).find()) continue;
			if (needle != null && !fqn.toLowerCase().contains(needle)) continue;
			if (q.publicOnly() && !info.isPublic()) continue;
			if (moduleFilter != null && !moduleFilter.isEmpty() && !moduleFilter.equals(info.moduleId())) continue;
			switch (kind) {
				case CLASS -> { if (info.isInterface()) continue; }
				case INTERFACE -> { if (!info.isInterface()) continue; }
				case CONCRETE -> { if (info.isInterface() || info.isAbstract()) continue; }
				case ENUM -> { if (!info.isEnum()) continue; }
				case ANNOTATION -> { if (!info.isAnnotation()) continue; }
				case ANY -> { /* no filter */ }
			}
			if (hasRequired) {
				Set<String> annotationNames = new HashSet<>();
				for (AnnotationInfo a : info.annotations()) annotationNames.add(a.name());
				boolean allPresent = true;
				for (String ann : required) {
					if (!annotationNames.contains(ann)) { allPresent = false; break; }
				}
				if (!allPresent) continue;
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

	// ---------- Description (JSON-serializable) ----------

	public Map<String, Object> describe(String fqn) {
		TypeInfo info = _types.get(fqn);
		if (info == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", fqn);
		out.put("module", info.moduleId());
		if (info.sourceFile() != null) out.put("sourceFile", info.sourceFile());
		out.put("public", info.isPublic());
		out.put("interface", info.isInterface());
		out.put("abstract", info.isAbstract());
		if (info.isEnum()) out.put("enum", true);
		if (info.isAnnotation()) out.put("annotation", true);
		if (info.isFinal()) out.put("final", true);
		if (info.superclass() != null) out.put("superclass", info.superclass());
		if (!info.interfaces().isEmpty()) out.put("interfaces", new ArrayList<>(info.interfaces()));
		if (info.enclosing() != null) out.put("enclosing", info.enclosing());
		if (info.configuration() != null) out.put("configuration", info.configuration());
		if (info.implementation() != null) out.put("implementation", info.implementation());
		if (!info.annotations().isEmpty()) {
			List<Map<String, Object>> anns = new ArrayList<>();
			for (AnnotationInfo a : info.annotations()) anns.add(annotationMap(a));
			out.put("annotations", anns);
		}
		out.put("methodCount", info.methods().size());
		out.put("fieldCount", info.fields().size());
		return out;
	}

	public Map<String, Object> describeMembers(String fqn) {
		TypeInfo info = _types.get(fqn);
		if (info == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", fqn);
		List<Map<String, Object>> methods = new ArrayList<>();
		for (MethodInfo m : info.methods()) {
			Map<String, Object> mm = new LinkedHashMap<>();
			mm.put("name", m.name());
			mm.put("descriptor", m.descriptor());
			mm.put("returnType", m.returnType());
			List<Map<String, Object>> paramList = new ArrayList<>(m.parameters().size());
			for (Parameter p : m.parameters()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				if (p.name() != null) entry.put("name", p.name());
				entry.put("type", p.type());
				paramList.add(entry);
			}
			mm.put("parameters", paramList);
			if (!m.exceptions().isEmpty()) mm.put("exceptions", new ArrayList<>(m.exceptions()));
			mm.put("public", m.isPublic());
			if (m.isProtected()) mm.put("protected", true);
			if (m.isPrivate()) mm.put("private", true);
			if (m.isStatic()) mm.put("static", true);
			if (m.isAbstract()) mm.put("abstract", true);
			if (m.isFinal()) mm.put("final", true);
			if (!m.annotations().isEmpty()) {
				List<Map<String, Object>> anns = new ArrayList<>();
				for (AnnotationInfo a : m.annotations()) anns.add(annotationMap(a));
				mm.put("annotations", anns);
			}
			methods.add(mm);
		}
		out.put("methods", methods);
		List<Map<String, Object>> fields = new ArrayList<>();
		for (FieldInfo f : info.fields()) {
			Map<String, Object> ff = new LinkedHashMap<>();
			ff.put("name", f.name());
			ff.put("type", f.type());
			ff.put("public", f.isPublic());
			if (f.isProtected()) ff.put("protected", true);
			if (f.isPrivate()) ff.put("private", true);
			if (f.isStatic()) ff.put("static", true);
			if (f.isFinal()) ff.put("final", true);
			if (f.isEnumConstant()) ff.put("enumConstant", true);
			if (f.constantValue() != null) ff.put("constantValue", f.constantValue());
			if (!f.annotations().isEmpty()) {
				List<Map<String, Object>> anns = new ArrayList<>();
				for (AnnotationInfo a : f.annotations()) anns.add(annotationMap(a));
				ff.put("annotations", anns);
			}
			fields.add(ff);
		}
		out.put("fields", fields);
		return out;
	}

	private static Map<String, Object> annotationMap(AnnotationInfo a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", a.name());
		if (!a.values().isEmpty()) m.put("values", new LinkedHashMap<>(a.values()));
		return m;
	}

	private static List<String> sorted(Collection<String> in) {
		return new ArrayList<>(new TreeSet<>(in));
	}

}
