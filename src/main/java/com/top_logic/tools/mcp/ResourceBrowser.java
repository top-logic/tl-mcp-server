/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.top_logic.basic.FileManager;
import com.top_logic.basic.MultiFileManager;
import com.top_logic.basic.core.workspace.McpResourcePaths;
import com.top_logic.basic.core.workspace.ModuleLayout;
import com.top_logic.basic.core.workspace.ModuleLayoutConstants;
import com.top_logic.basic.core.workspace.PathInfo;
import com.top_logic.basic.io.binary.BinaryData;

/**
 * Read-only view of the "virtual filesystem" of a modular TopLogic web application: the stack of
 * {@code src/main/webapp} directories and {@code -web-fragment.war} overlays contributed by the
 * modules on a classpath.
 *
 * <p>
 * The resource stack and its override order are reconstructed with the same {@link PathInfo}
 * algorithm the application uses at runtime (driven from the reactor classpath by
 * {@link McpResourcePaths}), and served through the same {@link MultiFileManager} API, so a query
 * returns exactly what {@link FileManager} would return inside the running application.
 * </p>
 */
public final class ResourceBrowser {

	/** Hard cap on the number of bytes returned for a single resource read. */
	public static final int DEFAULT_MAX_BYTES = 512 * 1024;

	private final FileManager _fileManager;

	private final List<Path> _roots;

	private ResourceBrowser(FileManager fileManager, List<Path> roots) {
		_fileManager = fileManager;
		_roots = roots;
	}

	/**
	 * Builds a browser over the web resources reachable from the given classpath.
	 *
	 * @param classpath
	 *        The compile classpath of the application to inspect, in classpath order.
	 * @return A browser, or {@code null} if the classpath contributes no web resources at all (the
	 *         inspected project is not a web application).
	 */
	public static ResourceBrowser fromClasspath(Iterable<Path> classpath) throws IOException {
		PathInfo info = McpResourcePaths.getAppPaths(classpath,
			ModuleLayoutConstants.DEPLOY_FOLDER_NAME, ModuleLayout.DEFAULT_DEPLOY_ASPECTS);
		List<Path> roots = info.getResourceDirs();
		if (roots.isEmpty()) {
			return null;
		}
		return new ResourceBrowser(MultiFileManager.createMultiFileManager(roots), roots);
	}

	/** The number of resource roots (webapp dirs and web-fragment wars) in the stack. */
	public int rootCount() {
		return _roots.size();
	}

	/**
	 * The resource roots in override order (highest priority first), each as a URI (a file URI for
	 * a module webapp directory, a {@code jar:} URI for a {@code -web-fragment.war}).
	 */
	public List<String> roots() {
		List<String> result = new ArrayList<>(_roots.size());
		for (Path root : _roots) {
			result.add(root.toUri().toString());
		}
		return result;
	}

	/**
	 * Lists the immediate children of the given resource directory.
	 *
	 * @param path
	 *        The resource path to list (e.g. {@code /} or {@code /WEB-INF/conf}).
	 * @return The child resource names, unioned across all roots. Directory names end with
	 *         {@code /}.
	 */
	public Set<String> list(String path) {
		return _fileManager.getResourcePaths(withLeadingSlash(path));
	}

	/**
	 * Whether the given resource name denotes a directory (i.e. ends with {@code /}).
	 */
	public boolean isDirectory(String name) {
		return _fileManager.isDirectory(name);
	}

	/**
	 * Reads the effective (topmost) version of the given resource.
	 *
	 * @param name
	 *        The resource name (e.g. {@code /WEB-INF/web.xml}).
	 * @param maxBytes
	 *        The maximum number of content bytes to return.
	 * @return The content, or {@code null} if no such resource exists.
	 */
	public Content read(String name, int maxBytes) throws IOException {
		BinaryData data = _fileManager.getDataOrNull(withLeadingSlash(name));
		if (data == null) {
			return null;
		}

		byte[] bytes;
		boolean truncated;
		try (InputStream in = data.getStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			while (buffer.size() <= maxBytes && (read = in.read(chunk)) != -1) {
				buffer.write(chunk, 0, read);
			}
			truncated = buffer.size() > maxBytes;
			bytes = buffer.toByteArray();
			if (truncated) {
				bytes = java.util.Arrays.copyOf(bytes, maxBytes);
			}
		}

		String text = asUtf8Text(bytes);
		return new Content(contentType(data, name), data.getSize(), bytes.length, truncated, text,
			text == null ? bytes : null);
	}

	/**
	 * Best-effort MIME type. The {@link BinaryData#getContentType()} of a resource resolves the
	 * type through the {@code MimeTypesModule} service, which is not started in this tooling
	 * context; falls back to a name-based guess when that service is unavailable.
	 */
	private static String contentType(BinaryData data, String name) {
		try {
			return data.getContentType();
		} catch (Throwable unavailable) {
			return java.net.URLConnection.guessContentTypeFromName(name);
		}
	}

	/**
	 * Resolves the given resource name against every root, reporting each fragment that contributes
	 * a version of it.
	 *
	 * @param name
	 *        The resource name.
	 * @return The contributing overlays in override order (highest priority first). The first entry
	 *         is the version {@link #read(String, int)} returns.
	 */
	public List<Overlay> overlays(String name) throws IOException {
		String relative = relative(name);
		List<Overlay> result = new ArrayList<>();
		for (Path root : _roots) {
			Path file = root.resolve(relative);
			if (Files.exists(file)) {
				boolean directory = Files.isDirectory(file);
				long size = directory ? -1 : Files.size(file);
				result.add(new Overlay(file.toUri().toString(), directory, size));
			}
		}
		return result;
	}

	private static String withLeadingSlash(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		return path.charAt(0) == '/' ? path : "/" + path;
	}

	private static String relative(String name) {
		String slashed = withLeadingSlash(name);
		return slashed.substring(1);
	}

	/**
	 * Strictly decodes the bytes as UTF-8, returning {@code null} if they are not valid UTF-8 text
	 * (i.e. the resource is binary and should be returned as raw bytes).
	 */
	private static String asUtf8Text(byte[] bytes) {
		for (byte b : bytes) {
			if (b == 0) {
				return null;
			}
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
			return decoded.toString();
		} catch (CharacterCodingException ex) {
			return null;
		}
	}

	/**
	 * The content of a single resource.
	 *
	 * @param contentType
	 *        The MIME content type derived from the resource name.
	 * @param size
	 *        The full size of the resource in bytes.
	 * @param bytesReturned
	 *        The number of bytes actually returned (capped at the requested maximum).
	 * @param truncated
	 *        Whether {@link #size} exceeds {@link #bytesReturned}.
	 * @param text
	 *        The content decoded as UTF-8 text, or {@code null} if the resource is binary.
	 * @param binary
	 *        The raw bytes, present only if {@link #text} is {@code null}.
	 */
	public record Content(String contentType, long size, int bytesReturned, boolean truncated, String text,
			byte[] binary) {
		// Value carrier.
	}

	/**
	 * One fragment's contribution of a resource.
	 *
	 * @param uri
	 *        The URI the resource resolves to in that fragment.
	 * @param directory
	 *        Whether the contribution is a directory.
	 * @param size
	 *        The size in bytes, or {@code -1} for a directory.
	 */
	public record Overlay(String uri, boolean directory, long size) {
		// Value carrier.
	}
}
