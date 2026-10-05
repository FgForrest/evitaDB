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
 * current engine. The fixtures live in `evita_functional_tests` under
 * `src/test/resources/testData/release_upgrade_fixtures`, one storage directory per fixture named after it.
 *
 * Nothing in this package runs in a test build. The recipes compile with the module against the current API — which is
 * how the tests replay them into a fresh catalog for comparison — and, compiled separately against a release's jars,
 * they write the fixtures inside that release:
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
 * To regenerate the fixtures run `tools/generate-release-fixtures.sh <outputDir> [fixture...]` — it resolves both
 * releases' class paths (or takes them from `RELEASE_2026_1_CLASSPATH` / `RELEASE_2026_2_CLASSPATH`), compiles this
 * package against each, writes the 2026.1 fixtures, then the 2026.2 ones, and opens the 2026.1 fixtures 2026.2 is to
 * upgrade — and copy the output into the resource directory above. A recipe must keep to the API the releases share
 * with the current engine; a change that does not compile against a release belongs in a new recipe, not in an old one,
 * because the committed bytes were written from the recipe as it stands.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
package io.evitadb.test.upgrade;
