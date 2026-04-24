/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Walks class files on a classpath using ASM and produces {@link TypeInfo} records without class
 * loading. Builds a two-pass result: pass 1 extracts per-class metadata (including method bodies
 * when {@code scanBodies} is on), pass 2 stamps the reverse {@code implementation} link onto
 * paired config types.
 */
public final class BytecodeScanner {

	private static final int ASM_API = Opcodes.ASM9;

	private BytecodeScanner() {
		// utility
	}

	/** One classpath entry annotated with the identifier to tag its classes with. */
	public record Source(File file, String moduleId) {
	}

	public static Map<String, TypeInfo> scan(List<Source> classpath, boolean includeJdk, boolean scanBodies)
			throws IOException {
		Map<String, TypeInfo.Builder> builders = new HashMap<>();
		for (Source source : classpath) {
			File entry = source.file();
			if (entry.isDirectory()) {
				scanDirectory(entry.toPath(), source.moduleId(), scanBodies, builders);
			} else if (entry.isFile() && entry.getName().endsWith(".jar")) {
				scanJar(entry, source.moduleId(), scanBodies, builders);
			}
		}
		if (includeJdk) {
			scanJrt(scanBodies, builders);
		}
		return finalise(builders);
	}

	private static void scanDirectory(Path root, String moduleId, boolean scanBodies,
			Map<String, TypeInfo.Builder> out) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(root)) {
			stream
				.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".class"))
				.forEach(p -> readFile(p, moduleId, scanBodies, out));
		}
	}

	private static void readFile(Path classFile, String moduleId, boolean scanBodies,
			Map<String, TypeInfo.Builder> out) {
		try (InputStream in = Files.newInputStream(classFile)) {
			ingest(in, moduleId, scanBodies, out);
		} catch (IOException | RuntimeException ex) {
			// swallow single-file failures
		}
	}

	private static void scanJar(File jar, String fallbackModuleId, boolean scanBodies,
			Map<String, TypeInfo.Builder> out) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			String moduleId = readGav(zip);
			if (moduleId == null) {
				moduleId = fallbackModuleId != null ? fallbackModuleId : jar.getName();
			}
			String finalModuleId = moduleId;
			zip.stream()
				.filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
				.forEach(e -> readZipEntry(zip, e, finalModuleId, scanBodies, out));
		}
	}

	/** Reads the GAV (groupId:artifactId:version) from the JAR's {@code META-INF/maven} if present. */
	private static String readGav(ZipFile zip) {
		ZipEntry gavEntry = null;
		var entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			String n = e.getName();
			if (n.startsWith("META-INF/maven/") && n.endsWith("/pom.properties")) {
				gavEntry = e;
				break;
			}
		}
		if (gavEntry == null) {
			return null;
		}
		Properties props = new Properties();
		try (InputStream in = zip.getInputStream(gavEntry)) {
			props.load(in);
		} catch (IOException ex) {
			return null;
		}
		String g = props.getProperty("groupId");
		String a = props.getProperty("artifactId");
		String v = props.getProperty("version");
		if (g == null || a == null) {
			return null;
		}
		return v == null ? g + ":" + a : g + ":" + a + ":" + v;
	}

	private static void readZipEntry(ZipFile zip, ZipEntry entry, String moduleId, boolean scanBodies,
			Map<String, TypeInfo.Builder> out) {
		try (InputStream in = zip.getInputStream(entry)) {
			ingest(in, moduleId, scanBodies, out);
		} catch (IOException | RuntimeException ex) {
			// swallow
		}
	}

	private static void scanJrt(boolean scanBodies, Map<String, TypeInfo.Builder> out) throws IOException {
		FileSystem jrt;
		try {
			jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
		} catch (Exception ex) {
			try {
				jrt = FileSystems.newFileSystem(URI.create("jrt:/"), Collections.emptyMap());
			} catch (Exception ex2) {
				return;
			}
		}
		for (Path root : jrt.getRootDirectories()) {
			try (Stream<Path> stream = Files.walk(root)) {
				stream
					.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".class"))
					.forEach(p -> readFile(p, "jdk", scanBodies, out));
			}
		}
	}

	private static void ingest(InputStream in, String moduleId, boolean scanBodies,
			Map<String, TypeInfo.Builder> out) throws IOException {
		ClassReader reader = new ClassReader(in);
		TypeInfo.Builder builder = new TypeInfo.Builder();
		builder.moduleId = moduleId;
		int flags = scanBodies ? ClassReader.SKIP_FRAMES : ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES;
		reader.accept(new IndexingVisitor(builder, scanBodies), flags);
		if (builder.name != null) {
			out.putIfAbsent(builder.name, builder);
		}
	}

	private static Map<String, TypeInfo> finalise(Map<String, TypeInfo.Builder> builders) {
		Map<String, TypeInfo> result = new HashMap<>(builders.size());
		for (Map.Entry<String, TypeInfo.Builder> e : builders.entrySet()) {
			result.put(e.getKey(), e.getValue().build());
		}
		for (TypeInfo info : result.values()) {
			String configFqn = info.configuration();
			if (configFqn == null) continue;
			TypeInfo config = result.get(configFqn);
			if (config != null) {
				config.setImplementation(info.name());
			}
		}
		return result;
	}

	// ---------- ASM visitors ----------

	private static final class IndexingVisitor extends ClassVisitor {

		private final TypeInfo.Builder _target;
		private final boolean _scanBodies;

		IndexingVisitor(TypeInfo.Builder target, boolean scanBodies) {
			super(ASM_API);
			_target = target;
			_scanBodies = scanBodies;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			if (name == null || (access & Opcodes.ACC_SYNTHETIC) != 0
				|| "module-info".equals(name) || "package-info".equals(name)
				|| name.endsWith("/module-info") || name.endsWith("/package-info")) {
				return;
			}
			_target.name = Type.getObjectType(name).getClassName();
			_target.access = access;
			if (superName != null && !"java/lang/Object".equals(superName)) {
				_target.superclass = Type.getObjectType(superName).getClassName();
			}
			if (interfaces != null) {
				for (String iface : interfaces) {
					_target.interfaces.add(Type.getObjectType(iface).getClassName());
				}
			}
		}

		@Override
		public void visitSource(String source, String debug) {
			if (_target.name == null) return;
			_target.sourceFile = source;
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			if (_target.name == null || outerName == null || name == null) return;
			String fqn = Type.getObjectType(name).getClassName();
			if (fqn.equals(_target.name) && _target.enclosing == null) {
				_target.enclosing = Type.getObjectType(outerName).getClassName();
			}
		}

		@Override
		public void visitOuterClass(String owner, String name, String descriptor) {
			if (_target.name == null || owner == null) return;
			if (_target.enclosing == null) {
				_target.enclosing = Type.getObjectType(owner).getClassName();
			}
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (_target.name == null || descriptor == null) return null;
			Map<String, Object> values = new LinkedHashMap<>();
			_target.annotations.add(new TypeInfo.AnnotationInfo(Type.getType(descriptor).getClassName(), values));
			return new AnnotationCollector(values);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature,
				Object value) {
			if (_target.name == null || name == null || descriptor == null) return null;
			if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;
			List<TypeInfo.AnnotationInfo> anns = new ArrayList<>(0);
			_target.fields.add(new TypeInfo.FieldInfo(
				name,
				Type.getType(descriptor).getClassName(),
				access,
				value,
				anns));
			return new FieldVisitor(ASM_API) {
				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					if (desc == null) return null;
					Map<String, Object> values = new LinkedHashMap<>();
					anns.add(new TypeInfo.AnnotationInfo(Type.getType(desc).getClassName(), values));
					return new AnnotationCollector(values);
				}
			};
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			if (_target.name == null || name == null || descriptor == null) return null;
			if ((access & Opcodes.ACC_SYNTHETIC) != 0 && !"<init>".equals(name)) return null;

			Type methodType = Type.getMethodType(descriptor);
			Type[] argTypes = methodType.getArgumentTypes();
			String[] paramTypes = new String[argTypes.length];
			String[] paramNames = new String[argTypes.length];
			int[] paramStartSlots = new int[argTypes.length];
			int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
			for (int i = 0; i < argTypes.length; i++) {
				paramTypes[i] = argTypes[i].getClassName();
				paramStartSlots[i] = slot;
				slot += argTypes[i].getSize();
			}
			List<String> exList;
			if (exceptions == null || exceptions.length == 0) {
				exList = List.of();
			} else {
				exList = new ArrayList<>(exceptions.length);
				for (String ex : exceptions) {
					exList.add(Type.getObjectType(ex).getClassName());
				}
			}

			// Constructor-signature heuristic for the config/impl pairing.
			if ("<init>".equals(name) && _target.configuration == null
				&& paramTypes.length >= 2 && TypeInfo.INSTANTIATION_CONTEXT.equals(paramTypes[0])) {
				_target.configuration = paramTypes[1];
			}

			List<TypeInfo.AnnotationInfo> anns = new ArrayList<>(0);
			List<TypeInfo.CallSite> calls = _scanBodies ? new ArrayList<>() : List.of();
			List<TypeInfo.FieldAccess> fieldAccesses = _scanBodies ? new ArrayList<>() : List.of();
			List<String> literals = _scanBodies ? new ArrayList<>() : List.of();
			List<TypeInfo.BodyTypeRef> bodyTypeRefs = _scanBodies ? new ArrayList<>() : List.of();

			int finalAccess = access;
			String finalName = name;
			String finalDescriptor = descriptor;
			String finalReturnType = methodType.getReturnType().getClassName();

			return new MethodVisitor(ASM_API) {
				int _paramIdx = 0;

				@Override
				public void visitParameter(String paramName, int paramAccess) {
					if (_paramIdx < paramNames.length) {
						paramNames[_paramIdx++] = paramName;
					}
				}

				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					if (desc == null) return null;
					Map<String, Object> values = new LinkedHashMap<>();
					anns.add(new TypeInfo.AnnotationInfo(Type.getType(desc).getClassName(), values));
					return new AnnotationCollector(values);
				}

				@Override
				public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean isIface) {
					if (!_scanBodies) return;
					if (owner == null || mname == null) return;
					calls.add(new TypeInfo.CallSite(
						owner.startsWith("[") ? owner : Type.getObjectType(owner).getClassName(),
						mname, mdesc));
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
					if (!_scanBodies) return;
					if (owner == null || fname == null) return;
					boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
					fieldAccesses.add(new TypeInfo.FieldAccess(
						Type.getObjectType(owner).getClassName(), fname, fdesc, write));
				}

				@Override
				public void visitLdcInsn(Object value) {
					if (!_scanBodies) return;
					if (value instanceof String s) {
						literals.add(s);
					}
				}

				@Override
				public void visitTypeInsn(int opcode, String type) {
					if (!_scanBodies || type == null) return;
					TypeInfo.BodyRefKind kind;
					switch (opcode) {
						case Opcodes.NEW -> kind = TypeInfo.BodyRefKind.INSTANTIATION;
						case Opcodes.CHECKCAST -> kind = TypeInfo.BodyRefKind.CAST;
						case Opcodes.INSTANCEOF -> kind = TypeInfo.BodyRefKind.INSTANCEOF;
						default -> { return; } // ANEWARRAY etc. — not tracked
					}
					String fqn = type.startsWith("[") ? type : Type.getObjectType(type).getClassName();
					bodyTypeRefs.add(new TypeInfo.BodyTypeRef(fqn, kind));
				}

				@Override
				public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
					if (!_scanBodies || type == null) return; // finally blocks have type==null
					bodyTypeRefs.add(new TypeInfo.BodyTypeRef(
						Type.getObjectType(type).getClassName(), TypeInfo.BodyRefKind.CATCH));
				}

				@Override
				public void visitLocalVariable(String varName, String varDesc, String varSig, Label start,
						Label end, int index) {
					if (varName == null || "this".equals(varName)) return;
					for (int i = 0; i < paramStartSlots.length; i++) {
						if (paramStartSlots[i] == index && paramNames[i] == null) {
							paramNames[i] = varName;
							return;
						}
					}
				}

				@Override
				public void visitEnd() {
					List<TypeInfo.Parameter> params = new ArrayList<>(paramTypes.length);
					for (int i = 0; i < paramTypes.length; i++) {
						params.add(new TypeInfo.Parameter(paramNames[i], paramTypes[i]));
					}
					_target.methods.add(new TypeInfo.MethodInfo(
						finalName, finalDescriptor, finalAccess,
						finalReturnType, List.copyOf(params), exList, anns, calls, fieldAccesses, literals,
						bodyTypeRefs));
				}
			};
		}
	}

	/** Recursively collects annotation parameters into a {@link Map}. */
	private static final class AnnotationCollector extends AnnotationVisitor {

		private final Map<String, Object> _values;

		AnnotationCollector(Map<String, Object> values) {
			super(ASM_API);
			_values = values;
		}

		@Override
		public void visit(String name, Object value) {
			put(name, encodeLiteral(value));
		}

		@Override
		public void visitEnum(String name, String descriptor, String value) {
			String enumType = descriptor == null ? null : Type.getType(descriptor).getClassName();
			put(name, Map.of("enum", (enumType == null ? "" : enumType) + "." + value));
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			Map<String, Object> nested = new LinkedHashMap<>();
			String annType = descriptor == null ? "" : Type.getType(descriptor).getClassName();
			Map<String, Object> wrapped = new LinkedHashMap<>();
			wrapped.put("annotation", annType);
			wrapped.put("values", nested);
			put(name, wrapped);
			return new AnnotationCollector(nested);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			List<Object> list = new ArrayList<>();
			put(name, list);
			return new AnnotationVisitor(ASM_API) {
				@Override
				public void visit(String n, Object value) {
					list.add(encodeLiteral(value));
				}

				@Override
				public void visitEnum(String n, String descriptor, String value) {
					String enumType = descriptor == null ? null : Type.getType(descriptor).getClassName();
					list.add(Map.of("enum", (enumType == null ? "" : enumType) + "." + value));
				}

				@Override
				public AnnotationVisitor visitAnnotation(String n, String descriptor) {
					Map<String, Object> nested = new LinkedHashMap<>();
					String annType = descriptor == null ? "" : Type.getType(descriptor).getClassName();
					Map<String, Object> wrapped = new LinkedHashMap<>();
					wrapped.put("annotation", annType);
					wrapped.put("values", nested);
					list.add(wrapped);
					return new AnnotationCollector(nested);
				}

				@Override
				public AnnotationVisitor visitArray(String n) {
					List<Object> inner = new ArrayList<>();
					list.add(inner);
					return this;
				}
			};
		}

		private void put(String name, Object value) {
			_values.put(name == null ? "value" : name, value);
		}

		private static Object encodeLiteral(Object value) {
			if (value instanceof Type t) {
				return Map.of("class", t.getClassName());
			}
			return value;
		}
	}

}
