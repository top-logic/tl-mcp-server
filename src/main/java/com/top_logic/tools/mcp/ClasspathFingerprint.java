/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.tools.mcp;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Cheap summary of the files an index was built from: changes whenever a file is added, removed,
 * replaced or touched. Reads only file metadata, so checking a typical reactor takes milliseconds.
 */
final class ClasspathFingerprint {

	private ClasspathFingerprint() {
		// utility
	}

	/**
	 * @param files
	 *        Classpath entries (directories are walked, JARs taken as a whole) and other inputs
	 *        such as pom files.
	 */
	static long of(List<File> files) {
		long hash = 17;
		for (File file : files) {
			hash = 31 * hash + file.getPath().hashCode();
			hash = 31 * hash + (file.isDirectory() ? ofDirectory(file.toPath()) : file.lastModified() ^ file.length());
		}
		return hash;
	}

	private static long ofDirectory(Path dir) {
		long[] acc = { 0, 0 };
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					acc[0]++;
					acc[1] += attrs.lastModifiedTime().toMillis() ^ attrs.size();
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return acc[0] * 1_000_003L + acc[1];
	}

}
