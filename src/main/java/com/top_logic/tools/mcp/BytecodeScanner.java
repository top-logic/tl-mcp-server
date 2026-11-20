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

	/**
	 * One classpath entry annotated with the identifier to tag its classes with, plus the
	 * companion source location (directory for reactor modules, {@code -sources.jar} for external
	 * JARs, {@code null} when unavailable).
	 */
	public record Source(File file, String moduleId, File sourceRoot) {
	}

	/** Receives the method bodies of every type accepted into the index. */
	@FunctionalInterface
	public interface BodySink {
		void accept(String owner, TypeInfo.MethodBody body);
	}

	/**
	 * Scans the classpath.
	 *
	 * @param bodySink
	 *        Receives the method bodies (when {@code scanBodies} is on) of each type that is
	 *        accepted into the result. Bodies are not kept on the returned {@link TypeInfo}s.
	 */
	public static Map<String, TypeInfo> scan(List<Source> classpath, boolean includeJdk, boolean scanBodies,
			BodySink bodySink) throws IOException {
		Context ctx = new Context(scanBodies, bodySink);
		for (Source source : classpath) {
			File entry = source.file();
			String sourceRoot = source.sourceRoot() == null ? null : ctx.intern(source.sourceRoot().getAbsolutePath());
			String moduleId = ctx.intern(source.moduleId());
			if (entry.isDirectory()) {
				scanDirectory(entry.toPath(), moduleId, sourceRoot, ctx);
			} else if (entry.isFile() && entry.getName().endsWith(".jar")) {
				scanJar(entry, moduleId, sourceRoot, ctx);
			}
		}
		if (includeJdk) {
			scanJrt(ctx);
		}
		return finalise(ctx.builders);
	}

	/**
	 * State shared by one scan. The string pool makes every occurrence of the same name,
	 * descriptor or FQN share one {@link String} instance: in a typical TopLogic application a type
	 * or method name is referenced from thousands of call sites, and without the pool each
	 * reference holds its own copy (measured: 8.7 million strings for 72k types).
	 */
	private static final class Context {
		final boolean scanBodies;
		final BodySink bodySink;
		final Map<String, TypeInfo.Builder> builders = new HashMap<>();
		private final Map<String, String> _pool = new HashMap<>();

		Context(boolean scanBodies, BodySink bodySink) {
			this.scanBodies = scanBodies;
			this.bodySink = bodySink;
		}

		String intern(String s) {
			if (s == null) return null;
			String existing = _pool.putIfAbsent(s, s);
			return existing == null ? s : existing;
		}

		/** Interned Java class name of an internal name or array descriptor. */
		String className(String internalName) {
			return intern(internalName.startsWith("[") ? internalName : Type.getObjectType(internalName).getClassName());
		}

		/** Interned Java class name of a type descriptor. */
		String typeName(String descriptor) {
			return intern(Type.getType(descriptor).getClassName());
		}
	}

	private static void scanDirectory(Path root, String moduleId, String sourceRoot, Context ctx)
			throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(root)) {
			stream
				.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".class"))
				.forEach(p -> readFile(p, moduleId, sourceRoot, ctx));
		}
	}

	private static void readFile(Path classFile, String moduleId, String sourceRoot, Context ctx) {
		try (InputStream in = Files.newInputStream(classFile)) {
			ingest(in, moduleId, sourceRoot, ctx);
		} catch (IOException | RuntimeException ex) {
			// swallow single-file failures
		}
	}

	private static void scanJar(File jar, String fallbackModuleId, String sourceRoot, Context ctx)
			throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			String moduleId = readGav(zip);
			if (moduleId == null) {
				moduleId = fallbackModuleId != null ? fallbackModuleId : jar.getName();
			}
			String finalModuleId = ctx.intern(moduleId);
			zip.stream()
				.filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
				.forEach(e -> readZipEntry(zip, e, finalModuleId, sourceRoot, ctx));
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

	private static void readZipEntry(ZipFile zip, ZipEntry entry, String moduleId, String sourceRoot,
			Context ctx) {
		try (InputStream in = zip.getInputStream(entry)) {
			ingest(in, moduleId, sourceRoot, ctx);
		} catch (IOException | RuntimeException ex) {
			// swallow
		}
	}

	private static void scanJrt(Context ctx) throws IOException {
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
					.forEach(p -> readFile(p, "jdk", null, ctx));
			}
		}
	}

	private static void ingest(InputStream in, String moduleId, String sourceRoot, Context ctx)
			throws IOException {
		ClassReader reader = new ClassReader(in);
		TypeInfo.Builder builder = new TypeInfo.Builder();
		builder.moduleId = moduleId;
		builder.sourceRoot = sourceRoot;
		int flags = ctx.scanBodies ? ClassReader.SKIP_FRAMES : ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES;
		reader.accept(new IndexingVisitor(builder, ctx), flags);
		if (builder.name != null && ctx.builders.putIfAbsent(builder.name, builder) == null) {
			// First occurrence of this type on the classpath wins; only its bodies are indexed.
			for (TypeInfo.MethodBody body : builder.bodies) {
				ctx.bodySink.accept(builder.name, body);
			}
		}
		builder.bodies.clear();
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
		private final Context _ctx;

		IndexingVisitor(TypeInfo.Builder target, Context ctx) {
			super(ASM_API);
			_target = target;
			_scanBodies = ctx.scanBodies;
			_ctx = ctx;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			if (name == null || (access & Opcodes.ACC_SYNTHETIC) != 0
				|| "module-info".equals(name) || "package-info".equals(name)
				|| name.endsWith("/module-info") || name.endsWith("/package-info")) {
				return;
			}
			_target.name = _ctx.className(name);
			_target.access = access;
			if (superName != null && !"java/lang/Object".equals(superName)) {
				_target.superclass = _ctx.className(superName);
			}
			if (interfaces != null) {
				for (String iface : interfaces) {
					_target.interfaces.add(_ctx.className(iface));
				}
			}
		}

		@Override
		public void visitSource(String source, String debug) {
			if (_target.name == null) return;
			_target.sourceFile = _ctx.intern(source);
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			if (_target.name == null || outerName == null || name == null) return;
			String fqn = Type.getObjectType(name).getClassName();
			if (fqn.equals(_target.name) && _target.enclosing == null) {
				_target.enclosing = _ctx.className(outerName);
			}
		}

		@Override
		public void visitOuterClass(String owner, String name, String descriptor) {
			if (_target.name == null || owner == null) return;
			if (_target.enclosing == null) {
				_target.enclosing = _ctx.className(owner);
			}
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (_target.name == null || descriptor == null) return null;
			Map<String, Object> values = new LinkedHashMap<>();
			_target.annotations.add(new TypeInfo.AnnotationInfo(_ctx.typeName(descriptor), values));
			return new AnnotationCollector(values);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature,
				Object value) {
			if (_target.name == null || name == null || descriptor == null) return null;
			if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;
			List<TypeInfo.AnnotationInfo> anns = new ArrayList<>(0);
			_target.fields.add(new TypeInfo.FieldInfo(
				_ctx.intern(name),
				_ctx.typeName(descriptor),
				access,
				value,
				anns));
			return new FieldVisitor(ASM_API) {
				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					if (desc == null) return null;
					Map<String, Object> values = new LinkedHashMap<>();
					anns.add(new TypeInfo.AnnotationInfo(_ctx.typeName(desc), values));
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
				paramTypes[i] = _ctx.intern(argTypes[i].getClassName());
				paramStartSlots[i] = slot;
				slot += argTypes[i].getSize();
			}
			List<String> exList;
			if (exceptions == null || exceptions.length == 0) {
				exList = List.of();
			} else {
				exList = new ArrayList<>(exceptions.length);
				for (String ex : exceptions) {
					exList.add(_ctx.className(ex));
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
			List<TypeInfo.BodyTypeRef> bodyTypeRefs = _scanBodies ? new ArrayList<>() : List.of();
			int[] lineRange = new int[] { -1, -1 };

			int finalAccess = access;
			String finalName = _ctx.intern(name);
			String finalDescriptor = _ctx.intern(descriptor);
			String finalReturnType = _ctx.intern(methodType.getReturnType().getClassName());

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
					anns.add(new TypeInfo.AnnotationInfo(_ctx.typeName(desc), values));
					return new AnnotationCollector(values);
				}

				@Override
				public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean isIface) {
					if (!_scanBodies) return;
					if (owner == null || mname == null) return;
					calls.add(new TypeInfo.CallSite(_ctx.className(owner), _ctx.intern(mname), _ctx.intern(mdesc)));
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
					if (!_scanBodies) return;
					if (owner == null || fname == null) return;
					boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
					fieldAccesses.add(new TypeInfo.FieldAccess(
						_ctx.className(owner), _ctx.intern(fname), _ctx.intern(fdesc), write));
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
					bodyTypeRefs.add(new TypeInfo.BodyTypeRef(_ctx.className(type), kind));
				}

				@Override
				public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
					if (!_scanBodies || type == null) return; // finally blocks have type==null
					bodyTypeRefs.add(new TypeInfo.BodyTypeRef(_ctx.className(type), TypeInfo.BodyRefKind.CATCH));
				}

				@Override
				public void visitLineNumber(int line, Label start) {
					if (lineRange[0] < 0 || line < lineRange[0]) lineRange[0] = line;
					if (line > lineRange[1]) lineRange[1] = line;
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
						params.add(new TypeInfo.Parameter(_ctx.intern(paramNames[i]), paramTypes[i]));
					}
					_target.methods.add(new TypeInfo.MethodInfo(
						finalName, finalDescriptor, finalAccess,
						finalReturnType, List.copyOf(params), exList, anns, lineRange[0], lineRange[1]));
					if (_scanBodies) {
						_target.bodies.add(new TypeInfo.MethodBody(finalName, finalDescriptor,
							calls, fieldAccesses, bodyTypeRefs));
					}
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
