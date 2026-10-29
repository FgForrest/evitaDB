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

/**
 * Recipes and generator of the **release upgrade fixtures**: small catalogs written by the released engines v2026.1.20
 * and v2026.2.18, which the upgrade tests (`Release2026_2UpgradeDefectsTest` in `evita_functional_tests`) open with the
 * current engine. The fixtures are never committed: the build of `evita_functional_tests` writes them in its
 * `generate-test-resources` phase into its test output directory, under `testData/release_upgrade_fixtures`, one
 * storage directory per fixture named after it.
 *
 * The generators never run in a test JVM. The recipes compile with the module against the current API — which is how
 * the tests replay them into a fresh catalog for comparison — and, compiled separately against a release's jars, they
 * write the fixtures inside that release:
 *
 * - {@link io.evitadb.test.upgrade.ReleaseFixtureRecipe} — schema and mutations of one fixture;
 * - {@link io.evitadb.test.upgrade.Release2026_1FixtureRecipes} — fixtures written by 2026.1 (this class must compile
 *   against the 2026.1 API), {@link io.evitadb.test.upgrade.Release2026_2FixtureRecipes} and its siblings — fixtures
 *   written by 2026.2;
 * - {@link io.evitadb.test.upgrade.ReleaseFixtureWriter} — runs a recipe on whichever engine is on the class path;
 * - {@link io.evitadb.test.upgrade.Release2026_1FixtureGenerator} and
 *   {@link io.evitadb.test.upgrade.Release2026_2FixtureGenerator} — the entry points run inside the releases, each
 *   with a `write` mode and a `probe` mode that prints the release's answers to the recipes' probes.
 *
 * The build runs `tools/generate-release-fixtures.sh <testDataDir>` — it resolves both releases' class paths (or takes
 * them from `RELEASE_2026_1_CLASSPATH` / `RELEASE_2026_2_CLASSPATH`), compiles this package against each, writes the
 * 2026.1 fixtures, then the 2026.2 ones, and opens the 2026.1 fixtures 2026.2 is to upgrade; it skips the run while
 * its inputs and its output are unchanged. A recipe must keep to the API the releases share with the current engine,
 * or the build fails to compile it against the release. Editing a recipe changes the fixture the next build writes,
 * so a recipe a test already relies on changes only together with that test, and a new shape gets a recipe of its
 * own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
package io.evitadb.test.upgrade;
