/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Opcodes;

/**
 * Metadata about a single indexed type, built from class-file bytecode by {@link BytecodeScanner}.
 */
public final class TypeInfo {

	/** FQN of the TopLogic config-framework bootstrap type used in paired constructors. */
	public static final String INSTANTIATION_CONTEXT = "com.top_logic.basic.config.InstantiationContext";

	private final String _name;

	private final int _access;

	private final String _superclass;

	private final List<String> _interfaces;

	private final String _enclosing;

	private final String _moduleId;

	private final String _sourceFile;

	private final List<AnnotationInfo> _annotations;

	private final List<MethodInfo> _methods;

	private final List<FieldInfo> _fields;

	private String _configuration;

	private String _implementation;

	TypeInfo(Builder b) {
		_name = b.name;
		_access = b.access;
		_superclass = b.superclass;
		_interfaces = List.copyOf(b.interfaces);
		_enclosing = b.enclosing;
		_moduleId = b.moduleId;
		_sourceFile = b.sourceFile;
		_annotations = List.copyOf(b.annotations);
		_methods = List.copyOf(b.methods);
		_fields = List.copyOf(b.fields);
		_configuration = b.configuration;
	}

	public String name() { return _name; }
	public int access() { return _access; }
	public boolean isPublic() { return (_access & Opcodes.ACC_PUBLIC) != 0; }
	public boolean isInterface() { return (_access & Opcodes.ACC_INTERFACE) != 0; }
	public boolean isAbstract() { return (_access & Opcodes.ACC_ABSTRACT) != 0; }
	public boolean isEnum() { return (_access & Opcodes.ACC_ENUM) != 0; }
	public boolean isAnnotation() { return (_access & Opcodes.ACC_ANNOTATION) != 0; }
	public boolean isFinal() { return (_access & Opcodes.ACC_FINAL) != 0; }

	/** FQN of the direct superclass, or {@code null} (for interfaces or {@code java.lang.Object}). */
	public String superclass() { return _superclass; }

	/** FQNs of directly implemented interfaces (for interfaces: directly extended interfaces). */
	public List<String> interfaces() { return _interfaces; }

	/** Concatenation of {@link #superclass()} and {@link #interfaces()} for convenience. */
	public List<String> supertypes() {
		if (_superclass == null) return _interfaces;
		List<String> all = new ArrayList<>(_interfaces.size() + 1);
		all.add(_superclass);
		all.addAll(_interfaces);
		return all;
	}

	/** FQN of the enclosing outer class (for nested classes), or {@code null}. */
	public String enclosing() { return _enclosing; }

	/** Identifier of the Maven module or JAR this class was loaded from. */
	public String moduleId() { return _moduleId; }

	/** Source file name (from the {@code SourceFile} attribute), or {@code null}. */
	public String sourceFile() { return _sourceFile; }

	public List<AnnotationInfo> annotations() { return _annotations; }

	public List<MethodInfo> methods() { return _methods; }

	public List<FieldInfo> fields() { return _fields; }

	/** FQN of the config interface this type implements, or {@code null}. */
	public String configuration() { return _configuration; }

	/** FQN of the implementation class for this (config) interface, or {@code null}. */
	public String implementation() { return _implementation; }

	void setImplementation(String implementation) {
		_implementation = implementation;
	}

	/** Builder populated by the ASM visitor. */
	static final class Builder {
		String name;
		int access;
		String superclass;
		final List<String> interfaces = new ArrayList<>(2);
		String enclosing;
		String moduleId;
		String sourceFile;
		final List<AnnotationInfo> annotations = new ArrayList<>();
		final List<MethodInfo> methods = new ArrayList<>();
		final List<FieldInfo> fields = new ArrayList<>();
		String configuration;

		TypeInfo build() {
			return new TypeInfo(this);
		}
	}

	/** Immutable annotation record with recursively-parsed parameters. */
	public record AnnotationInfo(String name, Map<String, Object> values) {

		public AnnotationInfo(String name, Map<String, Object> values) {
			this.name = name;
			this.values = values == null || values.isEmpty()
				? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}
	}

	/** Method-level metadata. */
	public record MethodInfo(
			String name,
			String descriptor,
			int access,
			String returnType,
			List<String> parameters,
			List<String> exceptions,
			List<AnnotationInfo> annotations,
			List<CallSite> calls,
			List<FieldAccess> fieldAccesses,
			List<String> stringLiterals) {

		public boolean isPublic() { return (access & Opcodes.ACC_PUBLIC) != 0; }
		public boolean isProtected() { return (access & Opcodes.ACC_PROTECTED) != 0; }
		public boolean isPrivate() { return (access & Opcodes.ACC_PRIVATE) != 0; }
		public boolean isStatic() { return (access & Opcodes.ACC_STATIC) != 0; }
		public boolean isAbstract() { return (access & Opcodes.ACC_ABSTRACT) != 0; }
		public boolean isFinal() { return (access & Opcodes.ACC_FINAL) != 0; }
		public boolean isConstructor() { return "<init>".equals(name); }
	}

	/** Field-level metadata. */
	public record FieldInfo(
			String name,
			String type,
			int access,
			Object constantValue,
			List<AnnotationInfo> annotations) {

		public boolean isPublic() { return (access & Opcodes.ACC_PUBLIC) != 0; }
		public boolean isProtected() { return (access & Opcodes.ACC_PROTECTED) != 0; }
		public boolean isPrivate() { return (access & Opcodes.ACC_PRIVATE) != 0; }
		public boolean isStatic() { return (access & Opcodes.ACC_STATIC) != 0; }
		public boolean isFinal() { return (access & Opcodes.ACC_FINAL) != 0; }
		public boolean isEnumConstant() { return (access & Opcodes.ACC_ENUM) != 0; }
	}

	/** One invocation site captured from a method body. */
	public record CallSite(String owner, String name, String descriptor) {
	}

	/** One field-access site captured from a method body. */
	public record FieldAccess(String owner, String name, String descriptor, boolean write) {
	}

}
