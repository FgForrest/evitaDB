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
 * The {@link CatalogStatisticsComponent#STORAGE_SIZE} component of one entity collection.
 *
 * The write-ahead log, the bootstrap file and the unaccounted remainder are catalog-wide and therefore have no
 * per-collection counterpart - they appear only in {@link StorageSizeStatistics}. Superseded data files *are*
 * attributable to a collection through their file names, so `awaitingDeletionBytes` is reported here too.
 *
 * @param liveBytes             bytes of active records in this collection's data store
 * @param wasteBytes            bytes of superseded records in it - what compacting this collection reclaims
 * @param awaitingDeletionBytes bytes of this collection's superseded data files not yet purged
 * @param activeRecordShare     `liveBytes / (liveBytes + wasteBytes)`, the ratio the compaction predicate uses;
 *                              `1.0` for an empty data store
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionStorageSize(
	long liveBytes,
	long wasteBytes,
	long awaitingDeletionBytes,
	double activeRecordShare
) {
}
