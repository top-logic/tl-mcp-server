/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DocCatalog}: collecting articles from directories and jars, front matter,
 * precedence, sections and the index.
 */
class DocCatalogTest {

	private static final String VIEWS = """
		---
		description: Read before writing a .view.xml.
		---

		# FAQ: Views

		Intro text.

		## Forms

		Form text.

		### Fields

		Field text.

		```
		# not a heading
		```

		## Tables

		Table text.
		""";

	@Test
	void testCollect(@TempDir Path workspace) throws IOException {
		Path sources = workspace.resolve("module/src/main/java");
		write(sources.resolve(DocCatalog.DOCS_DIR + "/views.md"), VIEWS);

		Path jar = workspace.resolve("dep.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			entry(zip, DocCatalog.DOCS_DIR + "/views.md", "---\ndescription: Shadowed.\n---\n# Old\n");
			entry(zip, DocCatalog.DOCS_DIR + "/access.md", "# Access\n\nNo front matter.\n");
			entry(zip, DocCatalog.DOCS_DIR + "/chapter/deep.md", "---\norder: 5\n---\n# Deep\n\nSee [views](doc:views#forms) and [gone](doc:missing/article#x).\n");
			entry(zip, DocCatalog.DOCS_DIR + "/chapter/index.md", "---\ndescription: The chapter.\n---\n# Chapter\n");
			entry(zip, "META-INF/other.md", "# Ignored\n");
		}

		DocCatalog catalog = DocCatalog.fromClasspath(List.of(sources, jar, workspace.resolve("missing")));
		assertEquals(List.of("views", "access", "chapter/deep", "chapter"),
			catalog.docs().stream().map(DocCatalog.Doc::name).toList());

		DocCatalog.Doc views = catalog.get("views");
		assertEquals("Read before writing a .view.xml.", views.description());
		assertTrue(views.text().startsWith("# FAQ: Views"), views.text());
		assertEquals("", catalog.get("access").description());

		assertEquals("# FAQ: Views\n\nIntro text.", DocCatalog.intro(views));
		assertEquals(List.of("FAQ: Views", "Forms", "Fields", "Tables"),
			DocCatalog.outline(views).stream().map(DocCatalog.Heading::title).toList());

		String forms = DocCatalog.section(views, "forms");
		assertTrue(forms.startsWith("## Forms"), forms);
		assertTrue(forms.contains("Field text."), forms);
		assertTrue(!forms.contains("Table text."), forms);
		assertEquals("### Fields\n\nField text.\n\n```\n# not a heading\n```", DocCatalog.section(views, "#fields"));
		assertTrue(DocCatalog.section(views, "faq-views").startsWith("# FAQ: Views"));
		assertTrue(DocCatalog.section(views, "abl").startsWith("## Tables"));
		assertNull(DocCatalog.section(views, "missing"));

		String index = catalog.index();
		assertTrue(index.contains("- views - Read before writing a .view.xml.\n"), index);
		assertTrue(index.contains("- access\n"), index);
		assertTrue(index.contains("- chapter/ - The chapter.\n  - chapter/deep\n"), index);

		assertTrue(catalog.get("doc:chapter/deep#x").chapter() == false);
		assertTrue(catalog.get("chapter").chapter());
		assertEquals(Integer.valueOf(5), catalog.get("chapter/deep").order());
		assertEquals("forms", DocCatalog.linkSection("doc:views#forms"));
		assertNull(DocCatalog.linkSection("doc:views"));
		assertEquals(List.of("chapter/deep: doc:missing/article#x"), catalog.brokenLinks());

		Path indexFile = workspace.resolve("target/tl-docs-index.md");
		catalog.writeIndex(indexFile);
		assertEquals(index, Files.readString(indexFile, StandardCharsets.UTF_8));
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		OutputStream out = zip;
		out.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

}
