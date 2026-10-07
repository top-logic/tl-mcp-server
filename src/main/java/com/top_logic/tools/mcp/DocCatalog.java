/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Developer articles shipped with the modules of a classpath.
 *
 * <p>
 * An article is a Markdown file {@value #DOCS_DIR}{@code /<name>.md} on the classpath: in a jar, in a
 * class output directory, or in the {@code src/main/java} directory of a reactor module. It starts
 * with a front matter block whose {@code description} says when to read the article:
 * </p>
 *
 * <pre>
 * ---
 * description: Read before writing or changing a .view.xml ...
 * ---
 * </pre>
 *
 * <p>
 * The file name without {@code .md} is the article name. When several classpath entries contribute
 * an article of the same name, the first one wins.
 * </p>
 */
public final class DocCatalog {

	/** Classpath directory holding the articles. */
	public static final String DOCS_DIR = "META-INF/tl-docs";

	/** Suffix of an article file. */
	private static final String DOC_SUFFIX = ".md";

	/** Front matter delimiter line. */
	private static final String FRONT_MATTER_DELIMITER = "---";

	/** Front matter key of the article description. */
	private static final String DESCRIPTION_KEY = "description";

	/**
	 * A developer article.
	 *
	 * @param name
	 *        The article name (file name without {@code .md}).
	 * @param description
	 *        When to read the article, from its front matter.
	 * @param origin
	 *        The classpath entry contributing the article.
	 * @param text
	 *        The Markdown body without front matter.
	 */
	public record Doc(String name, String description, String origin, String text) {
		// record
	}

	/**
	 * A heading of an article.
	 *
	 * @param level
	 *        The number of leading {@code #}.
	 * @param title
	 *        The heading text.
	 * @param line
	 *        The index of the heading line in the article body.
	 */
	public record Heading(int level, String title, int line) {
		// record
	}

	private final Map<String, Doc> _docs;

	private DocCatalog(Map<String, Doc> docs) {
		_docs = docs;
	}

	/**
	 * Collects the articles of the given classpath entries, in priority order.
	 *
	 * @param entries
	 *        Directories (class output or source directories) and jar files.
	 */
	public static DocCatalog fromClasspath(Iterable<Path> entries) {
		Map<String, Doc> docs = new LinkedHashMap<>();
		for (Path entry : entries) {
			try {
				if (Files.isDirectory(entry)) {
					scanDirectory(docs, entry);
				} else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
					scanJar(docs, entry);
				}
			} catch (IOException ex) {
				// An unreadable classpath entry contributes no articles.
			}
		}
		return new DocCatalog(docs);
	}

	private static void scanDirectory(Map<String, Doc> docs, Path entry) throws IOException {
		Path dir = entry.resolve(DOCS_DIR);
		if (!Files.isDirectory(dir)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(dir)) {
			files = stream.filter(f -> f.getFileName().toString().endsWith(DOC_SUFFIX)).sorted().toList();
		}
		for (Path file : files) {
			add(docs, docName(file.getFileName().toString()), entry.toString(),
				Files.readString(file, StandardCharsets.UTF_8));
		}
	}

	private static void scanJar(Map<String, Doc> docs, Path jar) throws IOException {
		String prefix = DOCS_DIR + "/";
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			List<ZipEntry> entries = new ArrayList<>();
			for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements();) {
				ZipEntry zipEntry = it.nextElement();
				String path = zipEntry.getName();
				if (!zipEntry.isDirectory() && path.startsWith(prefix) && path.endsWith(DOC_SUFFIX)
					&& path.indexOf('/', prefix.length()) < 0) {
					entries.add(zipEntry);
				}
			}
			entries.sort((a, b) -> a.getName().compareTo(b.getName()));
			for (ZipEntry zipEntry : entries) {
				try (InputStream in = zip.getInputStream(zipEntry)) {
					add(docs, docName(zipEntry.getName().substring(prefix.length())), jar.toString(),
						new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		}
	}

	private static String docName(String fileName) {
		return fileName.substring(0, fileName.length() - DOC_SUFFIX.length());
	}

	private static void add(Map<String, Doc> docs, String name, String origin, String content) {
		if (docs.containsKey(name)) {
			return;
		}
		String description = "";
		String body = content;
		List<String> lines = content.lines().toList();
		if (!lines.isEmpty() && lines.get(0).strip().equals(FRONT_MATTER_DELIMITER)) {
			for (int n = 1; n < lines.size(); n++) {
				String line = lines.get(n);
				if (line.strip().equals(FRONT_MATTER_DELIMITER)) {
					body = String.join("\n", lines.subList(n + 1, lines.size())).stripLeading();
					break;
				}
				int colon = line.indexOf(':');
				if (colon > 0 && line.substring(0, colon).strip().equals(DESCRIPTION_KEY)) {
					description = line.substring(colon + 1).strip();
				}
			}
		}
		docs.put(name, new Doc(name, description, origin, body));
	}

	/** Whether no article was found. */
	public boolean isEmpty() {
		return _docs.isEmpty();
	}

	/** All articles, in classpath order. */
	public Collection<Doc> docs() {
		return _docs.values();
	}

	/** The article with the given name, or {@code null}. */
	public Doc get(String name) {
		return _docs.get(name);
	}

	/**
	 * The text of the article before its first heading below the title, or the whole text if it
	 * has no such heading.
	 */
	public static String intro(Doc doc) {
		for (Heading heading : outline(doc)) {
			if (heading.level() > 1) {
				return String.join("\n", doc.text().lines().toList().subList(0, heading.line())).strip();
			}
		}
		return doc.text();
	}

	/** The headings of the given article. */
	public static List<Heading> outline(Doc doc) {
		List<Heading> result = new ArrayList<>();
		List<String> lines = doc.text().lines().toList();
		boolean inCode = false;
		for (int n = 0; n < lines.size(); n++) {
			String line = lines.get(n);
			if (line.startsWith("```")) {
				inCode = !inCode;
				continue;
			}
			if (inCode || !line.startsWith("#")) {
				continue;
			}
			int level = 0;
			while (level < line.length() && line.charAt(level) == '#') {
				level++;
			}
			if (level < line.length() && line.charAt(level) == ' ') {
				result.add(new Heading(level, line.substring(level + 1).strip(), n));
			}
		}
		return result;
	}

	/**
	 * The text of the given section of an article: from the heading matching the given title up to
	 * the next heading of the same or a higher level.
	 *
	 * @param section
	 *        The heading text, or its anchor (as in a Markdown {@code #anchor} link). Matched
	 *        case-insensitively; an exact match wins over a substring match.
	 * @return The section text, or {@code null} if no heading matches.
	 */
	public static String section(Doc doc, String section) {
		List<Heading> headings = outline(doc);
		String wanted = section.strip().toLowerCase(Locale.ROOT);
		if (wanted.startsWith("#")) {
			wanted = wanted.substring(1);
		}
		Heading match = null;
		for (Heading heading : headings) {
			if (heading.title().toLowerCase(Locale.ROOT).equals(wanted) || anchor(heading.title()).equals(wanted)) {
				match = heading;
				break;
			}
		}
		if (match == null) {
			for (Heading heading : headings) {
				if (heading.title().toLowerCase(Locale.ROOT).contains(wanted)) {
					match = heading;
					break;
				}
			}
		}
		if (match == null) {
			return null;
		}
		List<String> lines = doc.text().lines().toList();
		int end = lines.size();
		for (Heading heading : headings) {
			if (heading.line() > match.line() && heading.level() <= match.level()) {
				end = heading.line();
				break;
			}
		}
		return String.join("\n", lines.subList(match.line(), end)).strip();
	}

	/** The anchor a Markdown renderer derives from a heading (GitHub style). */
	static String anchor(String title) {
		StringBuilder result = new StringBuilder();
		for (char ch : title.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
				result.append(ch);
			} else if (ch == ' ') {
				result.append('-');
			}
		}
		return result.toString();
	}

	/**
	 * The article index as Markdown: one line per article with its name and description, for an
	 * agent session to learn which articles exist.
	 */
	public String index() {
		StringBuilder result = new StringBuilder();
		result.append("TopLogic developer articles available in this workspace ")
			.append("(read one with the tl-mcp tool read_doc(name[, section])):\n");
		for (Doc doc : _docs.values()) {
			result.append("- ").append(doc.name());
			if (!doc.description().isEmpty()) {
				result.append(" - ").append(doc.description());
			}
			result.append('\n');
		}
		return result.toString();
	}

	/**
	 * Writes {@link #index()} to the given file, unless it already has that content.
	 */
	public void writeIndex(Path file) throws IOException {
		String content = index();
		if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
			return;
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

}
