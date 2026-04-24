/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Scans class files on a classpath using ASM and produces {@link TypeInfo} records without class
 * loading.
 *
 * <p>
 * Drives a two-pass index build:
 * <ol>
 *   <li>Pass 1 reads every {@code .class} file, extracts FQN, modifiers, superclass + interfaces,
 *       class-level annotations, and detects constructors with signature {@code (InstantiationContext, X)}
 *       from which the owning class's {@link TypeInfo#configuration()} link is set.</li>
 *   <li>Pass 2 propagates the reverse {@link TypeInfo#implementation()} link onto each paired
 *       config type.</li>
 * </ol>
 * </p>
 */
public final class BytecodeScanner {

	/** API level passed to ASM visitors. Must be updated when raising the ASM dependency. */
	private static final int ASM_API = Opcodes.ASM9;

	private BytecodeScanner() {
		// utility
	}

	/**
	 * Builds a {@link TypeInfo} map by scanning the given classpath entries. Directories and
	 * {@code .jar} files are supported.
	 */
	public static Map<String, TypeInfo> scan(List<File> classpath) throws IOException {
		return scan(classpath, false);
	}

	/**
	 * @param includeJdk
	 *        When {@code true}, also indexes the classes of the JRT file system (JDK 9+ system
	 *        modules). Adds tens of thousands of entries (java.base etc.).
	 */
	public static Map<String, TypeInfo> scan(List<File> classpath, boolean includeJdk) throws IOException {
		Map<String, TypeInfo.Builder> builders = new HashMap<>();
		for (File entry : classpath) {
			if (entry.isDirectory()) {
				scanDirectory(entry.toPath(), builders);
			} else if (entry.isFile() && entry.getName().endsWith(".jar")) {
				scanJar(entry, builders);
			}
		}
		if (includeJdk) {
			scanJrt(builders);
		}
		return finalise(builders);
	}

	private static void scanDirectory(Path root, Map<String, TypeInfo.Builder> out) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(root)) {
			stream
				.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".class"))
				.forEach(p -> readFile(p, out));
		}
	}

	private static void readFile(Path classFile, Map<String, TypeInfo.Builder> out) {
		try (InputStream in = Files.newInputStream(classFile)) {
			ingest(in, out);
		} catch (IOException | RuntimeException ex) {
			// swallow single-file failures; a corrupt class or unsupported bytecode version
			// should not abort the whole scan
		}
	}

	private static void scanJar(File jar, Map<String, TypeInfo.Builder> out) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			zip.stream()
				.filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
				.forEach(e -> readZipEntry(zip, e, out));
		}
	}

	private static void readZipEntry(ZipFile zip, ZipEntry entry, Map<String, TypeInfo.Builder> out) {
		try (InputStream in = zip.getInputStream(entry)) {
			ingest(in, out);
		} catch (IOException | RuntimeException ex) {
			// swallow, see readFile
		}
	}

	private static void scanJrt(Map<String, TypeInfo.Builder> out) throws IOException {
		FileSystem jrt;
		try {
			jrt = FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
		} catch (Exception ex) {
			try {
				jrt = FileSystems.newFileSystem(java.net.URI.create("jrt:/"), Collections.emptyMap());
			} catch (Exception ex2) {
				// JRT not available on this runtime
				return;
			}
		}
		for (Path root : jrt.getRootDirectories()) {
			try (Stream<Path> stream = Files.walk(root)) {
				stream
					.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".class"))
					.forEach(p -> readFile(p, out));
			}
		}
	}

	private static void ingest(InputStream in, Map<String, TypeInfo.Builder> out) throws IOException {
		ClassReader reader = new ClassReader(in);
		TypeInfo.Builder builder = new TypeInfo.Builder();
		reader.accept(new IndexingVisitor(builder), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
			| ClassReader.SKIP_FRAMES);
		if (builder.name != null) {
			// First occurrence wins (same class from multiple sources is expected to be identical).
			out.putIfAbsent(builder.name, builder);
		}
	}

	private static Map<String, TypeInfo> finalise(Map<String, TypeInfo.Builder> builders) {
		Map<String, TypeInfo> result = new HashMap<>(builders.size());
		for (Map.Entry<String, TypeInfo.Builder> e : builders.entrySet()) {
			result.put(e.getKey(), e.getValue().build());
		}
		// Pass 2: reverse-link implementation onto paired config types.
		for (TypeInfo info : result.values()) {
			String configFqn = info.configuration();
			if (configFqn == null) {
				continue;
			}
			TypeInfo config = result.get(configFqn);
			if (config != null) {
				config.setImplementation(info.name());
			}
		}
		return result;
	}

	private static final class IndexingVisitor extends ClassVisitor {

		private final TypeInfo.Builder _target;

		IndexingVisitor(TypeInfo.Builder target) {
			super(ASM_API);
			_target = target;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			if (name == null) {
				return;
			}
			if ((access & Opcodes.ACC_SYNTHETIC) != 0 || "module-info".equals(name) || "package-info".equals(name)
				|| name.endsWith("/module-info") || name.endsWith("/package-info")) {
				// keep _target.name null to skip this entry
				return;
			}
			_target.name = Type.getObjectType(name).getClassName();
			_target.isPublic = (access & Opcodes.ACC_PUBLIC) != 0;
			_target.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
			_target.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
			if (superName != null && !"java/lang/Object".equals(superName)) {
				_target.supertypes.add(Type.getObjectType(superName).getClassName());
			}
			if (interfaces != null) {
				for (String iface : interfaces) {
					_target.supertypes.add(Type.getObjectType(iface).getClassName());
				}
			}
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (_target.name == null || descriptor == null) {
				return null;
			}
			_target.annotations.add(Type.getType(descriptor).getClassName());
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			if (_target.name == null || !"<init>".equals(name)) {
				return null;
			}
			if (_target.configuration != null) {
				// Already picked up; don't overwrite with a later-declared constructor.
				return null;
			}
			Type[] args = Type.getArgumentTypes(descriptor);
			if (args.length >= 2 && TypeInfo.INSTANTIATION_CONTEXT.equals(args[0].getClassName())) {
				_target.configuration = args[1].getClassName();
			}
			return null;
		}
	}

}
