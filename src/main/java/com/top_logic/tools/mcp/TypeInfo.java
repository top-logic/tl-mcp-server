/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Metadata about a single indexed type, built from class-file bytecode by
 * {@link BytecodeScanner}.
 *
 * <p>
 * The {@link #configuration} / {@link #implementation} fields pair a TopLogic
 * {@link com.top_logic.basic.config.ConfigurationItem}-style config interface with its runtime
 * implementation class. They are reconstructed from the presence of a constructor with signature
 * {@code (InstantiationContext, Config)} — if such a constructor exists on {@code Impl}, then
 * {@code Impl.configuration == Config} and {@code Config.implementation == Impl}. Non-TopLogic
 * classes simply leave both fields {@code null}.
 * </p>
 */
public final class TypeInfo {

	/** FQN of the TopLogic config-framework bootstrap type used in paired constructors. */
	public static final String INSTANTIATION_CONTEXT = "com.top_logic.basic.config.InstantiationContext";

	private final String _name;

	private final boolean _isPublic;

	private final boolean _isInterface;

	private final boolean _isAbstract;

	private final List<String> _supertypes;

	private final Set<String> _annotations;

	private String _configuration;

	private String _implementation;

	TypeInfo(String name, boolean isPublic, boolean isInterface, boolean isAbstract,
			List<String> supertypes, Set<String> annotations) {
		_name = name;
		_isPublic = isPublic;
		_isInterface = isInterface;
		_isAbstract = isAbstract;
		_supertypes = List.copyOf(supertypes);
		_annotations = Set.copyOf(new LinkedHashSet<>(annotations));
	}

	public String name() {
		return _name;
	}

	public boolean isPublic() {
		return _isPublic;
	}

	public boolean isInterface() {
		return _isInterface;
	}

	public boolean isAbstract() {
		return _isAbstract;
	}

	public List<String> supertypes() {
		return _supertypes;
	}

	public Set<String> annotations() {
		return _annotations;
	}

	/** FQN of the config interface this type implements, or {@code null}. */
	public String configuration() {
		return _configuration;
	}

	/** FQN of the implementation class for this (config) interface, or {@code null}. */
	public String implementation() {
		return _implementation;
	}

	void setConfiguration(String configuration) {
		_configuration = configuration;
	}

	void setImplementation(String implementation) {
		_implementation = implementation;
	}

	/** Builder used during ASM scanning. */
	static final class Builder {
		String name;

		boolean isPublic;

		boolean isInterface;

		boolean isAbstract;

		final List<String> supertypes = new ArrayList<>(4);

		final Set<String> annotations = new LinkedHashSet<>();

		String configuration;

		TypeInfo build() {
			TypeInfo info = new TypeInfo(name, isPublic, isInterface, isAbstract, supertypes, annotations);
			info._configuration = configuration;
			return info;
		}
	}

}
