/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.test.upgrade;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line entry point that runs inside the **v2026.1.20** engine and writes the fixtures of
 * {@link Release2026_1FixtureRecipes}. It is not part of any test run; `tools/generate-release-fixtures.sh` compiles
 * this package against the release's jars and runs it. See the package documentation for the whole procedure.
 *
 * Usage, on the 2026.1 classpath:
 *
 * ```
 * java io.evitadb.test.upgrade.Release2026_1FixtureGenerator write <outputDir> <workDir> [fixture...]
 * java io.evitadb.test.upgrade.Release2026_1FixtureGenerator probe <copyOfOutputDir> <workDir> [fixture...]
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Release2026_1FixtureGenerator {

	private Release2026_1FixtureGenerator() {
		// entry point only
	}

	/**
	 * Writes or probes the 2026.1 fixtures.
	 *
	 * @param args `write|probe <directory> <workDir> [fixture...]`
	 */
	public static void main(@Nonnull String[] args) {
		// the engine leaves non-daemon threads behind when it fails to start, so the exit status is set explicitly
		try {
			run(args);
		} catch (RuntimeException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		System.exit(0);
	}

	/**
	 * Parses the arguments and runs the requested mode.
	 *
	 * @param args `write|probe <directory> <workDir> [fixture...]`
	 */
	private static void run(@Nonnull String[] args) {
		if (args.length < 3) {
			throw new IllegalArgumentException("Usage: write|probe <directory> <workDir> [fixture...]");
		}
		final Path directory = Path.of(args[1]);
		final Path workDirectory = Path.of(args[2]);
		final List<ReleaseFixtureRecipe> recipes = ReleaseFixtureWriter.select(
			Release2026_1FixtureRecipes.all(), Arrays.asList(args).subList(3, args.length)
		);
		switch (args[0]) {
			case "write" -> ReleaseFixtureWriter.writeFixtures(recipes, directory, workDirectory);
			case "probe" -> ReleaseFixtureWriter.probeFixtures(recipes, directory, workDirectory);
			default -> throw new IllegalArgumentException("Unknown mode `" + args[0] + "`, expected write or probe.");
		}
	}

}
