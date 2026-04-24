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
import com.top_logic.tools.mcp.TypeInfo.BodyRefKind;
import com.top_logic.tools.mcp.TypeInfo.BodyTypeRef;
import com.top_logic.tools.mcp.TypeInfo.CallSite;
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

	/** referenced FQN → structured usage sites (Eclipse "Search Java" style). */
	private final Map<String, List<Usage>> _referencedBy = new HashMap<>();

	/** moduleId → types in that module. */
	private final Map<String, List<String>> _byModule = new HashMap<>();

	/** "owner#name#descriptor" → callers (FQN of caller type + caller method name + descriptor). */
	private final Map<String, List<CallerRef>> _callers = new HashMap<>();

	/** "owner#name" → readers (caller type + caller method). */
	private final Map<String, List<AccessorRef>> _fieldReaders = new HashMap<>();

	/** "owner#name" → writers. */
	private final Map<String, List<AccessorRef>> _fieldWriters = new HashMap<>();

	public record CallerRef(String ownerType, String method, String descriptor, int count) {
	}

	public record AccessorRef(String ownerType, String method, String descriptor, int count) {
	}

	/** Kind of usage site recorded in {@link #referencesTo(String, Set, String, int)}. */
	public enum UsageKind {
		// Declaration-level
		SUPERTYPE,
		TYPE_ANNOTATION,
		FIELD_TYPE,
		FIELD_ANNOTATION,
		METHOD_RETURN,
		METHOD_PARAMETER,
		METHOD_EXCEPTION,
		METHOD_ANNOTATION,
		// Body-level (require body scan)
		INSTANTIATION,
		CAST,
		INSTANCEOF,
		CATCH,
		CALL_DISPATCH,
		FIELD_READ,
		FIELD_WRITE
	}

	/**
	 * A concrete usage site: the type, method or field (in {@code ownerType} / {@code member})
	 * where the referenced type appears. For {@link UsageKind#CALL_DISPATCH} and
	 * {@link UsageKind#FIELD_READ}/{@link UsageKind#FIELD_WRITE}, {@code targetMember} /
	 * {@code targetDescriptor} identify which member of the referenced type is touched.
	 */
	public record Usage(
			String ownerType,
			String member,
			String descriptor,
			String targetMember,
			String targetDescriptor,
			UsageKind kind,
			int count) {
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
		String owner = info.name();
		if (info.superclass() != null)
			addUsage(info.superclass(), new Usage(owner, null, null, null, null, UsageKind.SUPERTYPE, 1));
		for (String iface : info.interfaces())
			addUsage(iface, new Usage(owner, null, null, null, null, UsageKind.SUPERTYPE, 1));
		for (AnnotationInfo ann : info.annotations())
			addUsage(ann.name(), new Usage(owner, null, null, null, null, UsageKind.TYPE_ANNOTATION, 1));

		for (MethodInfo m : info.methods()) {
			String mName = m.name(), mDesc = m.descriptor();
			addTypedUsage(m.returnType(), new Usage(owner, mName, mDesc, null, null, UsageKind.METHOD_RETURN, 1));
			for (Parameter p : m.parameters())
				addTypedUsage(p.type(), new Usage(owner, mName, mDesc, null, null, UsageKind.METHOD_PARAMETER, 1));
			for (String ex : m.exceptions())
				addUsage(ex, new Usage(owner, mName, mDesc, null, null, UsageKind.METHOD_EXCEPTION, 1));
			for (AnnotationInfo ann : m.annotations())
				addUsage(ann.name(), new Usage(owner, mName, mDesc, null, null, UsageKind.METHOD_ANNOTATION, 1));
		}

		for (FieldInfo f : info.fields()) {
			addTypedUsage(f.type(), new Usage(owner, f.name(), null, null, null, UsageKind.FIELD_TYPE, 1));
			for (AnnotationInfo ann : f.annotations())
				addUsage(ann.name(),
					new Usage(owner, f.name(), null, null, null, UsageKind.FIELD_ANNOTATION, 1));
		}
	}

	private void indexBodies(TypeInfo info) {
		String owner = info.name();
		for (MethodInfo m : info.methods()) {
			String mName = m.name(), mDesc = m.descriptor();
			for (CallSite call : m.calls()) {
				addTypedUsage(call.owner(), new Usage(owner, mName, mDesc,
					call.name(), call.descriptor(), UsageKind.CALL_DISPATCH, 1));
				String key = call.owner() + "#" + call.name() + "#" + call.descriptor();
				_callers.computeIfAbsent(key, k -> new ArrayList<>())
					.add(new CallerRef(owner, mName, mDesc, 1));
			}
			for (FieldAccess fa : m.fieldAccesses()) {
				UsageKind uk = fa.write() ? UsageKind.FIELD_WRITE : UsageKind.FIELD_READ;
				addTypedUsage(fa.owner(), new Usage(owner, mName, mDesc,
					fa.name(), fa.descriptor(), uk, 1));
				String key = fa.owner() + "#" + fa.name();
				AccessorRef ref = new AccessorRef(owner, mName, mDesc, 1);
				if (fa.write()) {
					_fieldWriters.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
				} else {
					_fieldReaders.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
				}
			}
			for (BodyTypeRef btr : m.bodyTypeRefs()) {
				UsageKind uk = switch (btr.kind()) {
					case INSTANTIATION -> UsageKind.INSTANTIATION;
					case CAST -> UsageKind.CAST;
					case INSTANCEOF -> UsageKind.INSTANCEOF;
					case CATCH -> UsageKind.CATCH;
				};
				addTypedUsage(btr.type(), new Usage(owner, mName, mDesc, null, null, uk, 1));
			}
		}
	}

	private void addUsage(String target, Usage usage) {
		if (target == null || target.isEmpty()) return;
		if (target.equals(usage.ownerType())) return;
		_referencedBy.computeIfAbsent(target, k -> new ArrayList<>()).add(usage);
	}

	/** Strips array brackets / primitives before indexing. */
	private void addTypedUsage(String type, Usage usage) {
		String t = type;
		if (t == null || t.isEmpty()) return;
		while (t.endsWith("[]")) t = t.substring(0, t.length() - 2);
		if (isPrimitive(t) || t.equals("void") || t.startsWith("[")) return;
		addUsage(t, usage);
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

	/**
	 * Structured usage query.
	 *
	 * @param fqn
	 *        Target type whose usages are returned.
	 * @param kinds
	 *        When non-null and non-empty, only these usage kinds are included.
	 * @param ownerPattern
	 *        Optional Java regex matched ({@link java.util.regex.Matcher#find}) against the
	 *        declaring class of each usage.
	 * @param ownerModule
	 *        When non-null and non-empty, only usages whose owning type lives in the given module
	 *        id are kept.
	 * @param limit
	 *        Cap on returned usages; {@code <= 0} means unlimited. The total count before
	 *        truncation is returned separately.
	 */
	public UsageResult referencesTo(String fqn, Set<UsageKind> kinds, String ownerPattern, String ownerModule,
			int limit) {
		List<Usage> all = _referencedBy.get(fqn);
		if (all == null || all.isEmpty()) {
			return new UsageResult(List.of(), 0, false, Map.of());
		}
		java.util.regex.Pattern pattern = null;
		if (ownerPattern != null && !ownerPattern.isEmpty()) {
			try {
				pattern = java.util.regex.Pattern.compile(ownerPattern);
			} catch (PatternSyntaxException ex) {
				throw new IllegalArgumentException("Invalid owner_pattern regex: " + ex.getMessage(), ex);
			}
		}
		boolean filterKinds = kinds != null && !kinds.isEmpty();
		boolean filterModule = ownerModule != null && !ownerModule.isEmpty();

		// First pass: apply filters and aggregate duplicate (owner, member, descriptor, target,
		// kind) into counts, preserving first-occurrence order.
		Map<String, int[]> counts = new LinkedHashMap<>();
		Map<String, Usage> firstSeen = new LinkedHashMap<>();
		Map<UsageKind, Integer> byKind = new LinkedHashMap<>();
		for (Usage u : all) {
			if (filterKinds && !kinds.contains(u.kind())) continue;
			if (pattern != null && !pattern.matcher(u.ownerType()).find()) continue;
			if (filterModule) {
				TypeInfo owner = _types.get(u.ownerType());
				if (owner == null || !ownerModule.equals(owner.moduleId())) continue;
			}
			String key = usageKey(u);
			counts.computeIfAbsent(key, k -> new int[1])[0] += u.count();
			firstSeen.putIfAbsent(key, u);
			byKind.merge(u.kind(), u.count(), Integer::sum);
		}
		List<Usage> aggregated = new ArrayList<>(counts.size());
		for (Map.Entry<String, Usage> e : firstSeen.entrySet()) {
			Usage u = e.getValue();
			aggregated.add(new Usage(u.ownerType(), u.member(), u.descriptor(),
				u.targetMember(), u.targetDescriptor(), u.kind(), counts.get(e.getKey())[0]));
		}
		int total = aggregated.size();
		boolean truncated = false;
		if (limit > 0 && total > limit) {
			aggregated = new ArrayList<>(aggregated.subList(0, limit));
			truncated = true;
		}
		return new UsageResult(aggregated, total, truncated, byKind);
	}

	private static String usageKey(Usage u) {
		return u.ownerType() + "\0"
			+ (u.member() == null ? "" : u.member()) + "\0"
			+ (u.descriptor() == null ? "" : u.descriptor()) + "\0"
			+ (u.targetMember() == null ? "" : u.targetMember()) + "\0"
			+ (u.targetDescriptor() == null ? "" : u.targetDescriptor()) + "\0"
			+ u.kind().name();
	}

	public record UsageResult(List<Usage> usages, int total, boolean truncated, Map<UsageKind, Integer> byKind) {
	}

	/**
	 * @param includeOverrides
	 *        When {@code true}, the result also contains calls statically dispatched through any
	 *        subtype of {@code owner} that declares a method with matching name (and descriptor
	 *        when given). Bytecode records each call against its declared static-type owner, so
	 *        a {@code this.foo()} call inside an overriding subclass would not match the
	 *        supertype otherwise.
	 */
	public List<CallerRef> callersOf(String owner, String method, String descriptor, boolean includeOverrides) {
		List<String> owners = new ArrayList<>();
		owners.add(owner);
		if (includeOverrides) {
			for (String sub : specializationsOf(owner, true)) {
				if (declaresMethod(sub, method, descriptor)) {
					owners.add(sub);
				}
			}
		}

		List<CallerRef> raw = new ArrayList<>();
		boolean exactDescriptor = descriptor != null && !descriptor.isEmpty();
		for (String o : owners) {
			if (exactDescriptor) {
				List<CallerRef> direct = _callers.get(o + "#" + method + "#" + descriptor);
				if (direct != null) raw.addAll(direct);
			} else {
				String prefix = o + "#" + method + "#";
				for (Map.Entry<String, List<CallerRef>> e : _callers.entrySet()) {
					if (e.getKey().startsWith(prefix)) raw.addAll(e.getValue());
				}
			}
		}
		return raw.isEmpty() ? List.of() : aggregateCallers(raw);
	}

	private boolean declaresMethod(String fqn, String method, String descriptor) {
		TypeInfo info = _types.get(fqn);
		if (info == null) return false;
		boolean exactDescriptor = descriptor != null && !descriptor.isEmpty();
		for (MethodInfo m : info.methods()) {
			if (!method.equals(m.name())) continue;
			if (!exactDescriptor || descriptor.equals(m.descriptor())) return true;
		}
		return false;
	}

	public List<AccessorRef> fieldReaders(String owner, String field) {
		List<AccessorRef> r = _fieldReaders.get(owner + "#" + field);
		return r == null ? List.of() : aggregateAccessors(r);
	}

	public List<AccessorRef> fieldWriters(String owner, String field) {
		List<AccessorRef> r = _fieldWriters.get(owner + "#" + field);
		return r == null ? List.of() : aggregateAccessors(r);
	}

	private static List<CallerRef> aggregateCallers(List<CallerRef> raw) {
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (CallerRef c : raw) {
			counts.computeIfAbsent(c.ownerType() + "\0" + c.method() + "\0" + c.descriptor(),
				k -> new int[1])[0] += c.count();
		}
		List<CallerRef> out = new ArrayList<>(counts.size());
		// Iterate raw once to preserve first-occurrence order, skipping already-emitted identities.
		Set<String> seen = new HashSet<>();
		for (CallerRef c : raw) {
			String key = c.ownerType() + "\0" + c.method() + "\0" + c.descriptor();
			if (seen.add(key)) {
				out.add(new CallerRef(c.ownerType(), c.method(), c.descriptor(), counts.get(key)[0]));
			}
		}
		return out;
	}

	private static List<AccessorRef> aggregateAccessors(List<AccessorRef> raw) {
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (AccessorRef a : raw) {
			counts.computeIfAbsent(a.ownerType() + "\0" + a.method() + "\0" + a.descriptor(),
				k -> new int[1])[0] += a.count();
		}
		List<AccessorRef> out = new ArrayList<>(counts.size());
		Set<String> seen = new HashSet<>();
		for (AccessorRef a : raw) {
			String key = a.ownerType() + "\0" + a.method() + "\0" + a.descriptor();
			if (seen.add(key)) {
				out.add(new AccessorRef(a.ownerType(), a.method(), a.descriptor(), counts.get(key)[0]));
			}
		}
		return out;
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
			Set<TypeInfo.Visibility> visibilities,
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
		Set<TypeInfo.Visibility> vis = q.visibilities();
		boolean filterVisibility = vis != null && !vis.isEmpty();

		List<String> result = new ArrayList<>();
		for (String fqn : candidates) {
			TypeInfo info = _types.get(fqn);
			if (info == null) continue;
			if (nameMatches != null && !nameMatches.contains(fqn)) continue;
			if (pattern != null && !pattern.matcher(fqn).find()) continue;
			if (needle != null && !fqn.toLowerCase().contains(needle)) continue;
			if (filterVisibility && !vis.contains(info.visibility())) continue;
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
		out.put("visibility", info.visibility().name().toLowerCase());
		if (info.isInterface()) out.put("interface", true);
		if (info.isAbstract() && !info.isInterface()) out.put("abstract", true);
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

	public enum MemberKind {
		ANY, METHOD, FIELD
	}

	public record MemberQuery(
			String name,
			String pattern,
			boolean regex,
			MemberKind kind,
			String type,
			String parameterType,
			List<String> annotatedWith,
			Set<TypeInfo.Visibility> visibilities,
			Boolean staticOnly,
			int limit) {
	}

	public Map<String, Object> describeMembers(String fqn, MemberQuery q) {
		TypeInfo info = _types.get(fqn);
		if (info == null) return null;

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
		MemberKind kind = q.kind() == null ? MemberKind.ANY : q.kind();
		List<String> required = q.annotatedWith() == null ? List.of() : q.annotatedWith();
		boolean paramTypeFilter = q.parameterType() != null && !q.parameterType().isEmpty();
		Set<TypeInfo.Visibility> vis = q.visibilities();
		boolean filterVisibility = vis != null && !vis.isEmpty();
		Boolean staticOnly = q.staticOnly();

		List<Map<String, Object>> methods = new ArrayList<>();
		int totalMethods = 0;
		if (kind != MemberKind.FIELD) {
			for (MethodInfo m : info.methods()) {
				if (!memberNameMatches(m.name(), q.name(), pattern, needle)) continue;
				if (filterVisibility && !vis.contains(m.visibility())) continue;
				if (staticOnly != null && staticOnly.booleanValue() != m.isStatic()) continue;
				if (q.type() != null && !q.type().isEmpty() && !q.type().equals(m.returnType())) continue;
				if (paramTypeFilter && !hasParameterOfType(m, q.parameterType())) continue;
				if (!hasAllAnnotations(m.annotations(), required)) continue;
				totalMethods++;
				methods.add(methodMap(m));
			}
		} else if (paramTypeFilter) {
			// "parameter_type" is method-only; fields trivially never match.
		}

		List<Map<String, Object>> fields = new ArrayList<>();
		int totalFields = 0;
		if (kind != MemberKind.METHOD && !paramTypeFilter) {
			for (FieldInfo f : info.fields()) {
				if (!memberNameMatches(f.name(), q.name(), pattern, needle)) continue;
				if (filterVisibility && !vis.contains(f.visibility())) continue;
				if (staticOnly != null && staticOnly.booleanValue() != f.isStatic()) continue;
				if (q.type() != null && !q.type().isEmpty() && !q.type().equals(f.type())) continue;
				if (!hasAllAnnotations(f.annotations(), required)) continue;
				totalFields++;
				fields.add(fieldMap(f));
			}
		}

		boolean truncated = false;
		if (q.limit() > 0) {
			if (methods.size() > q.limit()) {
				methods = new ArrayList<>(methods.subList(0, q.limit()));
				truncated = true;
			}
			int remaining = q.limit() - methods.size();
			if (remaining <= 0) {
				if (!fields.isEmpty()) truncated = true;
				fields = List.of();
			} else if (fields.size() > remaining) {
				fields = new ArrayList<>(fields.subList(0, remaining));
				truncated = true;
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", fqn);
		out.put("totalMethods", totalMethods);
		out.put("totalFields", totalFields);
		out.put("truncated", truncated);
		out.put("methods", methods);
		out.put("fields", fields);
		return out;
	}

	private static boolean memberNameMatches(String memberName, String exact, Pattern regex, String needle) {
		if (exact != null && !exact.isEmpty() && !exact.equals(memberName)) return false;
		if (regex != null && !regex.matcher(memberName).find()) return false;
		if (needle != null && !memberName.toLowerCase().contains(needle)) return false;
		return true;
	}

	private static boolean hasParameterOfType(MethodInfo m, String paramType) {
		for (Parameter p : m.parameters()) {
			if (paramType.equals(p.type())) return true;
		}
		return false;
	}

	private static boolean hasAllAnnotations(List<AnnotationInfo> present, List<String> required) {
		if (required.isEmpty()) return true;
		Set<String> names = new HashSet<>();
		for (AnnotationInfo a : present) names.add(a.name());
		for (String required1 : required) {
			if (!names.contains(required1)) return false;
		}
		return true;
	}

	private static Map<String, Object> methodMap(MethodInfo m) {
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
		mm.put("visibility", m.visibility().name().toLowerCase());
		if (m.isStatic()) mm.put("static", true);
		if (m.isAbstract()) mm.put("abstract", true);
		if (m.isFinal()) mm.put("final", true);
		if (!m.annotations().isEmpty()) {
			List<Map<String, Object>> anns = new ArrayList<>();
			for (AnnotationInfo a : m.annotations()) anns.add(annotationMap(a));
			mm.put("annotations", anns);
		}
		return mm;
	}

	private static Map<String, Object> fieldMap(FieldInfo f) {
		Map<String, Object> ff = new LinkedHashMap<>();
		ff.put("name", f.name());
		ff.put("type", f.type());
		ff.put("visibility", f.visibility().name().toLowerCase());
		if (f.isStatic()) ff.put("static", true);
		if (f.isFinal()) ff.put("final", true);
		if (f.isEnumConstant()) ff.put("enumConstant", true);
		if (f.constantValue() != null) ff.put("constantValue", f.constantValue());
		if (!f.annotations().isEmpty()) {
			List<Map<String, Object>> anns = new ArrayList<>();
			for (AnnotationInfo a : f.annotations()) anns.add(annotationMap(a));
			ff.put("annotations", anns);
		}
		return ff;
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

	// ---------- Source lookup ----------

	public record SourceResult(String file, String text, int startLine, int endLine, int totalLines) {
	}

	public enum SourceMode {
		/** Auto: 'doc' for class-level queries, 'source' for member queries. */
		AUTO,
		/** Javadoc + annotations + signature only (no method/class body). */
		DOC,
		/** Header block plus method body or full class source. */
		SOURCE
	}

	/**
	 * Returns the source text for a type or one of its members, pulled from the companion source
	 * directory (reactor modules) or {@code -sources.jar} (external dependencies) that the
	 * scanner discovered.
	 *
	 * @param fqn
	 *        FQN of the declaring type. For nested classes the outer source file is used, because
	 *        nested classes share the enclosing {@code .java}.
	 * @param member
	 *        Method name (or {@code <init>}) to return a snippet for; {@code null} returns the
	 *        whole source.
	 * @param descriptor
	 *        Optional bytecode descriptor to disambiguate overloads.
	 * @param contextLines
	 *        For member snippets, number of lines of leading context (javadoc, annotations) to
	 *        include before the method header. Default suggested: 30.
	 */
	public SourceResult sourceOf(String fqn, String member, String descriptor, SourceMode mode, int contextLines)
			throws IOException {
		TypeInfo info = _types.get(fqn);
		if (info == null) return null;
		if (info.sourceRoot() == null || info.sourceFile() == null) return null;

		String pkgPath;
		int lastDot = fqn.lastIndexOf('.');
		pkgPath = lastDot < 0 ? "" : fqn.substring(0, lastDot).replace('.', '/') + "/";
		String entry = pkgPath + info.sourceFile();

		List<String> lines = readAllLines(info.sourceRoot(), entry);
		if (lines == null) return null;

		SourceMode effective = mode == null ? SourceMode.AUTO : mode;
		boolean memberQuery = member != null && !member.isEmpty();
		if (effective == SourceMode.AUTO) {
			effective = memberQuery ? SourceMode.SOURCE : SourceMode.DOC;
		}

		if (!memberQuery) {
			if (effective == SourceMode.SOURCE) {
				return new SourceResult(entry, String.join("\n", lines), 1, lines.size(), lines.size());
			}
			// DOC: class-level javadoc + class signature up to the opening '{'.
			String simple = simpleNameOf(fqn);
			int[] classRange = findClassDeclRange(lines, simple);
			if (classRange == null) {
				return new SourceResult(entry, String.join("\n", lines), 1, lines.size(), lines.size());
			}
			return snippet(entry, lines, classRange[0], classRange[2]);
		}

		MethodInfo m = findMethod(info, member, descriptor);
		if (m == null) {
			return new SourceResult(entry, String.join("\n", lines), 1, lines.size(), lines.size());
		}

		// DOC mode: always find header range via text search — that gives us the terminator
		// (';' or '{') which is the precise end of the declaration signature.
		if (effective == SourceMode.DOC) {
			int[] range = findByTextSearch(lines, member, descriptor);
			if (range == null) {
				return new SourceResult(entry, String.join("\n", lines), 1, lines.size(), lines.size());
			}
			int from = contextLines > 0 ? Math.max(1, range[1] - contextLines) : range[0];
			int to = Math.min(lines.size(), range[2]);
			return snippet(entry, lines, from, to);
		}

		// SOURCE mode: prefer bytecode line info, fall back to text search for abstract methods.
		int startLine = m.startLine();
		int endLine = m.endLine();
		int textSearchFrom = -1;
		if (startLine <= 0) {
			int[] range = findByTextSearch(lines, member, descriptor);
			if (range == null) {
				return new SourceResult(entry, String.join("\n", lines), 1, lines.size(), lines.size());
			}
			textSearchFrom = range[0];
			startLine = range[1];
			endLine = range[2];
		}

		int from;
		if (contextLines > 0) {
			from = Math.max(1, startLine - contextLines);
		} else if (textSearchFrom > 0) {
			from = textSearchFrom;
		} else {
			int prevEnd = 0;
			for (MethodInfo other : info.methods()) {
				if (other == m) continue;
				int end = other.endLine();
				if (end <= 0 || end >= startLine) continue;
				if (end > prevEnd) prevEnd = end;
			}
			from = prevEnd > 0 ? prevEnd + 1 : 1;
		}
		return snippet(entry, lines, from, Math.min(lines.size(), endLine + 1));
	}

	private static SourceResult snippet(String entry, List<String> lines, int from, int to) {
		if (from < 1) from = 1;
		if (to > lines.size()) to = lines.size();
		if (to < from) to = from;
		String text = String.join("\n", lines.subList(from - 1, to));
		return new SourceResult(entry, text, from, to, lines.size());
	}

	private static String simpleNameOf(String fqn) {
		int dot = fqn.lastIndexOf('.');
		String tail = dot < 0 ? fqn : fqn.substring(dot + 1);
		int dollar = tail.lastIndexOf('$');
		return dollar < 0 ? tail : tail.substring(dollar + 1);
	}

	/**
	 * Locates the class declaration in a source file.
	 *
	 * @return {@code [headerFrom, declLine, openBraceLine]} (1-based), or {@code null} when not
	 *         found.
	 */
	private static int[] findClassDeclRange(List<String> lines, String simpleName) {
		Pattern declPattern = Pattern.compile(
			"\\b(?:class|interface|record|enum|@interface)\\s+" + Pattern.quote(simpleName) + "\\b");
		int declLine = -1;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
			if (declPattern.matcher(line).find()) {
				declLine = i + 1;
				break;
			}
		}
		if (declLine < 0) return null;
		int openBraceLine = declLine;
		for (int j = declLine - 1; j < lines.size(); j++) {
			if (lines.get(j).contains("{")) {
				openBraceLine = j + 1;
				break;
			}
		}
		int headerFrom = walkUpHeader(lines, declLine);
		return new int[] { headerFrom, declLine, openBraceLine };
	}

	private static int walkUpHeader(List<String> lines, int declLine) {
		int headerFrom = declLine;
		for (int k = declLine - 2; k >= 0; k--) {
			String trimmed = lines.get(k).trim();
			if (trimmed.isEmpty()
				|| trimmed.startsWith("//")
				|| trimmed.startsWith("/**") || trimmed.startsWith("/*")
				|| trimmed.startsWith("*")
				|| trimmed.endsWith("*/")
				|| trimmed.startsWith("@")) {
				headerFrom = k + 1;
			} else {
				break;
			}
		}
		return headerFrom;
	}

	/**
	 * Text-search fallback for abstract / interface methods whose bytecode carries no line info.
	 * Returns {@code [headerFromLine, declarationLine, endLine]} (1-based, inclusive) where
	 * {@code headerFromLine} is the first line of the attached javadoc / annotation / blank
	 * block preceding the declaration. Returns {@code null} when the member is not found.
	 */
	private static int[] findByTextSearch(List<String> lines, String member, String descriptor) {
		int expectedParams = -1;
		if (descriptor != null && !descriptor.isEmpty()) {
			try {
				expectedParams = org.objectweb.asm.Type.getArgumentTypes(descriptor).length;
			} catch (RuntimeException ignore) {
				// leave as -1
			}
		}
		Pattern declPattern = Pattern.compile("\\b" + Pattern.quote(member) + "\\s*\\(");
		int declLine = -1;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (!declPattern.matcher(line).find()) continue;
			if (line.trim().startsWith("//") || line.trim().startsWith("*")) continue;
			if (expectedParams >= 0 && paramCount(lines, i) != expectedParams) continue;
			declLine = i + 1;
			break;
		}
		if (declLine < 0) return null;

		int endLine = declLine;
		for (int j = declLine - 1; j < lines.size(); j++) {
			String line = lines.get(j);
			if (line.contains(";") || line.contains("{")) {
				endLine = j + 1;
				break;
			}
		}
		return new int[] { walkUpHeader(lines, declLine), declLine, endLine };
	}

	/**
	 * Counts comma-separated parameter slots in the parenthesised argument list starting on
	 * {@code startIdx}. Bracket-balanced so that nested generics ({@code Map<K, V>}) do not
	 * inflate the count. Returns -1 if the parentheses are unbalanced across visible lines.
	 */
	private static int paramCount(List<String> lines, int startIdx) {
		int depth = 0;
		boolean seenOpen = false;
		int params = 0;
		boolean nonEmpty = false;
		int angle = 0;
		for (int i = startIdx; i < lines.size() && i < startIdx + 20; i++) {
			String line = lines.get(i);
			for (int k = 0; k < line.length(); k++) {
				char c = line.charAt(k);
				if (c == '(') { depth++; seenOpen = true; continue; }
				if (c == ')') { depth--; if (depth == 0) return nonEmpty ? params + 1 : 0; continue; }
				if (!seenOpen || depth != 1) continue;
				if (c == '<') angle++;
				else if (c == '>') angle--;
				else if (c == ',' && angle == 0) params++;
				else if (!Character.isWhitespace(c)) nonEmpty = true;
			}
		}
		return -1;
	}

	private static MethodInfo findMethod(TypeInfo info, String member, String descriptor) {
		MethodInfo fallback = null;
		for (MethodInfo m : info.methods()) {
			if (!member.equals(m.name())) continue;
			if (descriptor != null && !descriptor.isEmpty()) {
				if (descriptor.equals(m.descriptor())) return m;
			} else {
				// No descriptor filter: prefer the first with real line info, else any.
				if (m.startLine() > 0 && fallback == null) fallback = m;
				else if (fallback == null) fallback = m;
			}
		}
		return fallback;
	}

	private static List<String> readAllLines(String sourceRoot, String entry) throws IOException {
		java.io.File root = new java.io.File(sourceRoot);
		if (root.isDirectory()) {
			java.io.File f = new java.io.File(root, entry);
			if (!f.isFile()) return null;
			return java.nio.file.Files.readAllLines(f.toPath());
		}
		if (root.isFile() && sourceRoot.endsWith(".jar")) {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(root)) {
				java.util.zip.ZipEntry e = zip.getEntry(entry);
				if (e == null) return null;
				try (java.io.InputStream in = zip.getInputStream(e);
						java.io.BufferedReader r = new java.io.BufferedReader(
							new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
					List<String> out = new ArrayList<>();
					String line;
					while ((line = r.readLine()) != null) out.add(line);
					return out;
				}
			}
		}
		return null;
	}

}
