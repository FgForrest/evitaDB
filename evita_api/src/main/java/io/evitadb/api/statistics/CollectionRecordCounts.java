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
 * The {@link CatalogStatisticsComponent#RECORD_COUNTS} component of one entity collection - how many entities it
 * holds, split by scope. The catalog-level {@link RecordCounts} is the sum of these across all collections.
 *
 * See {@link RecordCounts} for why `totalRecords` means live **plus** archived rather than live alone, and why it
 * also counts deleted bodies that compaction has not reclaimed yet.
 *
 * @param totalRecords    entity body storage parts in this collection - live plus archived, plus any deleted body
 *                        a compaction has not reclaimed yet
 * @param liveRecords     entities in {@link io.evitadb.dataType.Scope#LIVE}
 * @param archivedRecords entities in {@link io.evitadb.dataType.Scope#ARCHIVED}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionRecordCounts(
	int totalRecords,
	int liveRecords,
	int archivedRecords
) {
}
