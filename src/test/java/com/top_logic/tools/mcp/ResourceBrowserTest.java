/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ResourceBrowser}: reconstruction of a stacked web resource tree from a
 * classpath, override ordering, listing, and text/binary reads.
 */
class ResourceBrowserTest {

	@Test
	void testStackedResourceTree(@TempDir Path workspace) throws IOException {
		// A base module and an application module depending on it.
		Path base = module(workspace, "test-base", null);
		webResource(base, "WEB-INF/web.xml", "BASE");
		webResource(base, "shared.txt", "base-version");
		webResource(base, "base-only.txt", "b");

		Path app = module(workspace, "test-app", "test-base");
		webResource(app, "WEB-INF/web.xml", "APP");
		webResource(app, "shared.txt", "app-version");
		webResource(app, "app-only.txt", "a");
		webResourceBytes(app, "logo.bin", new byte[] { 1, 2, 0, 3, 4 });

		List<Path> classpath = List.of(
			base.resolve("target/classes"),
			app.resolve("target/classes"));

		ResourceBrowser browser = ResourceBrowser.fromClasspath(classpath);
		assertNotNull(browser, "Two webapp roots must yield a browser.");
		assertEquals(2, browser.rootCount());

		// The application module overrides the base module.
		assertEquals("APP", browser.read("/WEB-INF/web.xml", ResourceBrowser.DEFAULT_MAX_BYTES).text());
		assertEquals("app-version", browser.read("shared.txt", ResourceBrowser.DEFAULT_MAX_BYTES).text());

		// A base-only resource is still visible.
		assertEquals("b", browser.read("/base-only.txt", ResourceBrowser.DEFAULT_MAX_BYTES).text());

		// Overlays report every contributor, application first.
		List<ResourceBrowser.Overlay> overlays = browser.overlays("/shared.txt");
		assertEquals(2, overlays.size());
		assertTrue(overlays.get(0).uri().contains("test-app"), "Effective overlay is the application's.");
		assertTrue(overlays.get(1).uri().contains("test-base"), "Overridden overlay is the base's.");

		// A resource present in only one module has exactly one overlay.
		assertEquals(1, browser.overlays("/app-only.txt").size());

		// Directory listing unions both roots; directories end with '/'.
		Set<String> rootNames = browser.list("/");
		assertTrue(rootNames.contains("/app-only.txt"), rootNames.toString());
		assertTrue(rootNames.contains("/base-only.txt"), rootNames.toString());
		assertTrue(rootNames.contains("/shared.txt"), rootNames.toString());
		Set<String> directories = rootNames.stream().filter(browser::isDirectory).collect(Collectors.toSet());
		assertEquals(Set.of("/WEB-INF/"), directories);

		// Binary content is returned as raw bytes, not decoded text.
		ResourceBrowser.Content binary = browser.read("/logo.bin", ResourceBrowser.DEFAULT_MAX_BYTES);
		assertNull(binary.text());
		assertNotNull(binary.binary());
		assertEquals(5, binary.binary().length);

		// A missing resource reads as null.
		assertNull(browser.read("/does-not-exist", ResourceBrowser.DEFAULT_MAX_BYTES));
	}

	@Test
	void testTruncation(@TempDir Path workspace) throws IOException {
		Path module = module(workspace, "test-single", null);
		webResourceBytes(module, "big.txt", new byte[1000]);

		ResourceBrowser browser = ResourceBrowser.fromClasspath(List.of(module.resolve("target/classes")));
		assertNotNull(browser);

		ResourceBrowser.Content content = browser.read("/big.txt", 100);
		assertTrue(content.truncated());
		assertEquals(100, content.bytesReturned());
		assertEquals(1000, content.size());
	}

	@Test
	void testFindRecursivelyWithOverlayDedup(@TempDir Path workspace) throws IOException {
		Path base = module(workspace, "test-base", null);
		webResource(base, "WEB-INF/layouts/admin/users.layout.template.xml", "base-users");
		webResource(base, "WEB-INF/layouts/shared.layout.template.xml", "base-shared");
		webResource(base, "WEB-INF/layouts/notes.txt", "not a match");

		Path app = module(workspace, "test-app", "test-base");
		webResource(app, "WEB-INF/layouts/home/dashboard.layout.template.xml", "app-dashboard");
		// Same resource as in base - must be reported once.
		webResource(app, "WEB-INF/layouts/shared.layout.template.xml", "app-shared");

		List<Path> classpath = List.of(
			base.resolve("target/classes"),
			app.resolve("target/classes"));
		ResourceBrowser browser = ResourceBrowser.fromClasspath(classpath);
		assertNotNull(browser);

		ResourceBrowser.Matches matches = browser.find("*.layout.template.xml", "/WEB-INF/layouts", 1000);
		assertFalse(matches.truncated());
		assertEquals(List.of(
			"/WEB-INF/layouts/admin/users.layout.template.xml",
			"/WEB-INF/layouts/home/dashboard.layout.template.xml",
			"/WEB-INF/layouts/shared.layout.template.xml"),
			matches.names());

		// Scoping to a subtree restricts the walk.
		assertEquals(List.of("/WEB-INF/layouts/home/dashboard.layout.template.xml"),
			browser.find("*.layout.template.xml", "/WEB-INF/layouts/home", 1000).names());

		// The limit truncates and flags it.
		ResourceBrowser.Matches limited = browser.find("*.layout.template.xml", "/", 2);
		assertTrue(limited.truncated());
		assertEquals(2, limited.names().size());
	}

	@Test
	void testWebFragmentWarOverlay(@TempDir Path workspace) throws IOException {
		// A binary dependency: a jar with a sibling -web-fragment.war carrying its web resources.
		Path repo = workspace.resolve("repo");
		Files.createDirectories(repo);
		Path jar = repo.resolve("test-frag-1.jar");
		zip(jar, "META-INF/maven/test/test-frag/pom.xml",
			"<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
				+ "<artifactId>test-frag</artifactId><version>1</version></project>");
		Path war = repo.resolve("test-frag-1-web-fragment.war");
		zip(war, "WEB-INF/frag.xml", "FRAGMENT");

		ResourceBrowser browser = ResourceBrowser.fromClasspath(List.of(jar));
		assertNotNull(browser, "A -web-fragment.war sibling must contribute a resource root.");
		assertEquals(1, browser.rootCount());

		// Content is read across the war's zip filesystem.
		assertEquals("FRAGMENT", browser.read("/WEB-INF/frag.xml", ResourceBrowser.DEFAULT_MAX_BYTES).text());

		// The overlay origin is the war entry, exposed as a jar: URI.
		List<ResourceBrowser.Overlay> overlays = browser.overlays("/WEB-INF/frag.xml");
		assertEquals(1, overlays.size());
		assertTrue(overlays.get(0).uri().contains("test-frag-1-web-fragment.war"), overlays.get(0).uri());
	}

	@Test
	void testNoWebResources(@TempDir Path workspace) throws IOException {
		// A module with a pom and classes dir but no webapp folder yields no browser.
		Path module = module(workspace, "test-empty", null);
		assertFalse(Files.exists(module.resolve("src/main/webapp")));

		ResourceBrowser browser = ResourceBrowser.fromClasspath(List.of(module.resolve("target/classes")));
		assertNull(browser, "A project without web resources must disable resource browsing.");
	}

	private static Path module(Path workspace, String artifactId, String dependencyArtifactId) throws IOException {
		Path dir = workspace.resolve(artifactId);
		Files.createDirectories(dir.resolve("target/classes"));

		String dependency = dependencyArtifactId == null ? ""
			: "<dependencies><dependency><groupId>test</groupId><artifactId>" + dependencyArtifactId
				+ "</artifactId><version>1</version></dependency></dependencies>";
		String pom = "<project><modelVersion>4.0.0</modelVersion>"
			+ "<groupId>test</groupId><artifactId>" + artifactId + "</artifactId><version>1</version>"
			+ dependency + "</project>";
		Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
		return dir;
	}

	private static void webResource(Path module, String relative, String content) throws IOException {
		webResourceBytes(module, relative, content.getBytes(StandardCharsets.UTF_8));
	}

	private static void webResourceBytes(Path module, String relative, byte[] content) throws IOException {
		Path file = module.resolve("src/main/webapp").resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content);
	}

	private static void zip(Path archive, String entryName, String content) throws IOException {
		try (java.util.zip.ZipOutputStream out =
			new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
			out.putNextEntry(new java.util.zip.ZipEntry(entryName));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
	}
}
