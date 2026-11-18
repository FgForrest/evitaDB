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

import io.evitadb.api.query.Query;

import javax.annotation.Nonnull;

/**
 * A read-only query a release fixture is checked with. The generator's `probe` mode runs every probe of a recipe with
 * whichever engine it was started on and prints the primary keys it answers, so the answers of the release that wrote
 * the fixture can be compared with the answers of the current engine after the upgrade, and with those of a fresh
 * current engine that replayed the same recipe.
 *
 * @param label a short name of the probe, unique within its recipe
 * @param query the query; it must return entity references
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReleaseFixtureProbe(
	@Nonnull String label,
	@Nonnull Query query
) {
}
