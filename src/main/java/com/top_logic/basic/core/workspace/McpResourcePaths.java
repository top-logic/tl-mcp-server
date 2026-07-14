/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */
package com.top_logic.basic.core.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a {@link PathInfo} from an explicit classpath.
 *
 * <p>
 * {@link Workspace#getAppPaths(String, String[])} computes the resource path stack only from the
 * running JVM's own classpath. Tooling that inspects a foreign application - here a Maven plugin
 * operating on a target reactor - needs the same computation driven by that reactor's classpath
 * instead. This class provides it by driving {@link PathInfo}'s package-private builder API, which
 * is reachable because the class is declared in {@link PathInfo}'s package.
 * </p>
 *
 * <p>
 * This is a stop-gap. Once the engine exposes a public classpath-driven {@code getAppPaths}
 * overload, delete this class and call {@link Workspace} instead. The
 * {@link #addClasspathEntry(PathInfo, Path)} dispatch is a copy of the corresponding private method
 * in {@link Workspace}, kept in sync with it.
 * </p>
 */
public final class McpResourcePaths {

	private McpResourcePaths() {
		// Utility class.
	}

	/**
	 * Builds the resource path stack an application would see at runtime from the given classpath.
	 *
	 * @param classpathEntries
	 *        The compile classpath of the application to inspect, in classpath order.
	 * @param deployDir
	 *        The name of the deploy folder to look up deploy aspects in.
	 * @param deployAspects
	 *        The deploy aspects to include, see {@link ModuleLayout#DEFAULT_DEPLOY_ASPECTS}.
	 * @return The completed {@link PathInfo}; use {@link PathInfo#getResourceDirs()} for the
	 *         override-ordered resource root stack.
	 */
	public static PathInfo getAppPaths(Iterable<Path> classpathEntries, String deployDir, String[] deployAspects)
			throws IOException {
		PathInfo result = new PathInfo(deployDir, deployAspects);

		for (Path entry : classpathEntries) {
			addClasspathEntry(result, entry);
		}

		return result.complete();
	}

	private static void addClasspathEntry(PathInfo result, Path pathEntry) throws IOException {
		if (!Files.exists(pathEntry)) {
			return;
		}

		// Ensure that links are followed.
		File pathEntryFile = pathEntry.normalize().toFile().getCanonicalFile();
		if (!result.addClasspathEntry(pathEntryFile)) {
			// Was already found.
			return;
		}

		if (pathEntryFile.isDirectory()) {
			boolean isTest;
			if (pathEntryFile.getName().equals(ModuleLayoutConstants.CLASSES_DIR)) {
				isTest = false;
			} else if (pathEntryFile.getName().equals(ModuleLayoutConstants.TEST_CLASSES_DIR)) {
				isTest = true;
			} else {
				return;
			}
			File buildPath = pathEntryFile.getParentFile();
			if (!buildPath.getName().equals(ModuleLayoutConstants.TARGET_DIR)) {
				return;
			}

			result.addClassesDir(pathEntryFile);
			result.addProject(buildPath.getParentFile(), isTest);
		} else if (pathEntryFile.getName().endsWith(".jar")) {
			result.addJar(pathEntryFile);
		}
	}
}
