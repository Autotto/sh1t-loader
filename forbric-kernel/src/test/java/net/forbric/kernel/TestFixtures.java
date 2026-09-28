/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Fixtures a test reads that a clean checkout does not have: the staged game artifacts, the compiled game side
 * (built only when those artifacts are present), and the local mod packs under {@code run/}.
 *
 * <p>CI is such a checkout, and it must stay green: a missing fixture skips the test there. A compatibility run
 * sets {@code FORBRIC_COMPAT_FIXTURES_REQUIRED=1}, and then a missing fixture is a failure, so a machine that is
 * supposed to have them cannot pass by quietly skipping.
 */
public final class TestFixtures {
	private TestFixtures() {
	}

	/** Skips the test when {@code present} is false, or fails it when fixtures are required. */
	public static void require(boolean present, String what) {
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, what);
		Assumptions.assumeTrue(present, what);
	}

	/** {@link #require} for files: every path must be a regular file. */
	public static void requireFiles(String what, Path... files) {
		for (Path file : files) require(Files.isRegularFile(file), what + ": " + file);
	}

	/** {@link #require} for a directory, such as a local mod pack. */
	public static void requireDirectory(String what, Path directory) {
		require(Files.isDirectory(directory), what + ": " + directory);
	}
}
