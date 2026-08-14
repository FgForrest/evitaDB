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

package io.evitadb.api.statistics;

/**
 * The {@link CatalogStatisticsComponent#RECORD_COUNTS} component - how many entities the catalog holds, split by
 * scope.
 *
 * These are catalog-wide aggregates: each collection answers from an in-memory counter and the engine sums them, which
 * is cheap enough to do on every request. The same counts for one collection are fetched separately - see
 * {@link CollectionRecordCounts}.
 *
 * **Why the split matters**
 *
 * `totalRecords` counts entity body storage parts, and archiving an entity does **not** remove its body storage part -
 * it flips a `scope` field in place. The historically shipped `totalRecords` is therefore live **plus** archived
 * combined, which silently over-counts for anyone using archiving. `totalRecords` keeps that meaning for backward
 * compatibility and is documented as the sum of the two; `liveRecords` and `archivedRecords` are the numbers a caller
 * actually wants.
 *
 * **Deletion behaves the same way, and more sharply.** A deleted entity's body storage part is not removed from the
 * data store either - it is superseded, and only reclaimed when that data store is compacted. So `totalRecords` also
 * counts un-compacted tombstoned bodies, and it can stay flat for a long time after a mass delete while `liveRecords`
 * drops to zero immediately. That gap is a fragmentation signal, not a miscount: it is precisely the space compaction
 * would reclaim.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered at all for an unusable catalog - the counts require a loaded catalog. The component status carries
 * {@link ComponentAvailability#CATALOG_UNUSABLE}.
 *
 * @param totalRecords    entity body storage parts across all collections - live plus archived, plus any deleted
 *                        body a compaction has not reclaimed yet
 * @param liveRecords     entities in {@link io.evitadb.dataType.Scope#LIVE}
 * @param archivedRecords entities in {@link io.evitadb.dataType.Scope#ARCHIVED}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record RecordCounts(
	long totalRecords,
	long liveRecords,
	long archivedRecords
) {
}
