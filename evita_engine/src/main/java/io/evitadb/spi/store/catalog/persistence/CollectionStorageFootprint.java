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

package io.evitadb.spi.store.catalog.persistence;

/**
 * The storage-layer view of one entity collection's disk footprint - the same decomposition
 * {@link CatalogStorageFootprint} applies to a whole catalog, narrowed to the data store files whose names belong to
 * a single collection. Produced by {@link EntityCollectionPersistenceService#measureStorageFootprint()}.
 *
 * The write-ahead log and the bootstrap file are catalog-wide and have no per-collection counterpart, so they appear
 * only in {@link CatalogStorageFootprint}. So does the blocked/purgeable split of `awaitingDeletionBytes`, which is
 * answered from catalog-wide reader bookkeeping the collection cannot see.
 *
 * **The total is measured; the remainder is derived**, exactly as at the catalog level:
 * `totalBytes == liveBytes + wasteBytes + awaitingDeletionBytes + unaccountedBytes` holds by construction.
 *
 * @param totalBytes            measured total - the sum of the lengths of this collection's data store files
 * @param liveBytes             bytes of active records in the current data store file, clamped to its actual length
 * @param wasteBytes            the rest of the current data store file - superseded records that compaction reclaims
 * @param awaitingDeletionBytes bytes of this collection's superseded data store files that are still on disk
 * @param unaccountedBytes      everything else among this collection's files - the derived remainder
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionStorageFootprint(
	long totalBytes,
	long liveBytes,
	long wasteBytes,
	long awaitingDeletionBytes,
	long unaccountedBytes
) {
}
