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
 * The {@link CatalogStatisticsComponent#STORAGE_SIZE} component of one entity collection - the same decomposition
 * {@link StorageSizeStatistics} applies to the whole catalog, narrowed to one collection's data files.
 *
 * ```
 * sizeOnDiskInBytes                total, measured
 * ├── liveBytes                    active records in the current data store
 * ├── wasteBytes                   superseded records inside it - what compacting this collection reclaims
 * ├── awaitingDeletionBytes        this collection's superseded data files not yet purged
 * └── unaccountedBytes             everything else among its files
 * ```
 *
 * **The total is measured, not derived**, exactly as at the catalog level: it is the sum of the lengths of the files
 * whose names belong to this collection, so `sizeOnDiskInBytes == liveBytes + wasteBytes + awaitingDeletionBytes +
 * unaccountedBytes` holds by construction and anything the engine does not track stays visible in
 * `unaccountedBytes` instead of disappearing from the report.
 *
 * The write-ahead log and the bootstrap file are catalog-wide and have no per-collection counterpart, so they appear
 * only in {@link StorageSizeStatistics}. Superseded data files *are* attributable to a collection through their file
 * names, so `awaitingDeletionBytes` is reported here too.
 *
 * **`awaitingDeletionBytes` is not a time-travel figure, but time travel is what makes it large.** A data file becomes
 * superseded when compaction replaces it, in both modes - what differs is only how long it then survives:
 *
 * - **Time travel disabled** - the file is released by the async purge task on the next catalog-version exchange, so
 *   the value is a short transient. A value that *stays* high means an open reader or writer is pinning old catalog
 *   versions, not that history is being retained; the catalog-level `blockedByActiveReaderBytes` names the culprit.
 * - **Time travel enabled** - there is no purge on version exchange at all. The file is released only when the WAL
 *   files that reference its catalog versions are removed, so it survives the entire history window. This is where
 *   the value is genuinely the price of time travel, and the lever that moves it is WAL retention - not compaction,
 *   which has already run on these bytes.
 *
 * The figure is therefore reported unconditionally, in both modes - as is the catalog level's `walBytes`, which time
 * travel widens rather than creates. Deleting these files by hand is never the remedy - see
 * {@link StorageSizeStatistics} for the blocked/purgeable split that says whether anything is holding them.
 *
 * The active-record share is deliberately not repeated here - it is the headline number of
 * {@link CatalogStatisticsComponent#FRAGMENTATION} and is carried by {@link CollectionFragmentation}, which is what
 * a client asks for when it wants to know whether compaction is worth running.
 *
 * @param sizeOnDiskInBytes     measured total - the sum of the lengths of this collection's data files
 * @param liveBytes             bytes of active records in this collection's data store
 * @param wasteBytes            bytes of superseded records in it - what compacting this collection reclaims
 * @param awaitingDeletionBytes bytes of this collection's superseded data files that are no longer current but not yet
 *                              purged; a short transient without time travel, retained for the whole history window
 *                              with it - see above before reading it as either
 * @param unaccountedBytes      bytes among this collection's files that belong to none of the classes above
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionStorageSize(
	long sizeOnDiskInBytes,
	long liveBytes,
	long wasteBytes,
	long awaitingDeletionBytes,
	long unaccountedBytes
) {
}
