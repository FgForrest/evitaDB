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
 * The {@link CatalogStatisticsComponent#STORAGE_SIZE} component - the catalog's disk footprint broken into the classes
 * that have different remedies.
 *
 * A single "48 GB" tells a developer nothing: data they inserted, garbage awaiting compaction and time-travel history
 * they could shorten by changing WAL retention are three different problems. The physical layout already separates
 * them, so the report does too:
 *
 * ```
 * sizeOnDiskInBytes                      total, measured
 * ├── liveBytes                          active records in the current data stores
 * ├── wasteBytes                         superseded records inside them - what compaction reclaims
 * ├── walBytes                           retained write-ahead log
 * ├── awaitingDeletionBytes              superseded data files not yet purged
 * │   ├── blockedByActiveReaderBytes     pinned by an open reader or writer
 * │   └── purgeableBytes                 nothing blocks them; waiting on the purge mechanism
 * ├── bootstrapBytes                     the catalog bootstrap / version index
 * └── unaccountedBytes                   everything else in the directory
 * ```
 *
 * **The total is measured, not derived.** It is the sum of the actual lengths of every file in the catalog directory,
 * so `sizeOnDiskInBytes == liveBytes + wasteBytes + walBytes + awaitingDeletionBytes + bootstrapBytes +
 * unaccountedBytes` holds *by construction*. Anything the engine does not track deliberately - a temporary file left
 * by an interrupted compaction, a partial restore, a restore-in-progress marker - lands in `unaccountedBytes` as a
 * visible, explainable signal instead of silently disappearing from the report.
 *
 * **Files awaiting deletion are not a time-travel artefact.** Superseded data files pass through the maintainer in
 * both modes; only the purge timing differs. With time travel on they are released when the WAL files that reference
 * them are removed, so they persist for the whole history window; with it off they are released by the async purge
 * task on catalog-version exchange, so they are a short transient. `awaitingDeletionBytes` is therefore reported
 * unconditionally - only `walBytes` is genuinely gated on time travel being enabled.
 *
 * That difference decides what to *do* about a large value, which is why the class is split further. With time travel
 * on, these bytes are the price of the history window and the lever is WAL retention - compaction has already run on
 * them and will not reclaim them again. With it off, a value that stays high is not retention at all but an open
 * reader or writer pinning old catalog versions, which is what `blockedByActiveReaderBytes` isolates; if instead
 * `purgeableBytes` dominates, nothing is blocking and the purge task is simply behind. Deleting these files by hand
 * is never the remedy in either mode - a reader may still be holding them.
 *
 * **Reading for a degraded catalog**
 *
 * Delivered even when the catalog is unusable: file sizes are readable regardless of whether the catalog loads. The
 * in-memory-derived parts (`liveBytes`, `wasteBytes`) then read `0`, and the bytes they would have accounted for
 * surface in `unaccountedBytes`.
 *
 * **Per collection**
 *
 * The same decomposition for one collection's data store is fetched separately - see {@link CollectionStorageSize}.
 * Both levels are served from the same single directory listing, so the split costs no extra IO; it keeps this
 * response a fixed size regardless of how many collections the catalog holds.
 *
 * @param sizeOnDiskInBytes            measured total - the sum of the lengths of every file in the catalog directory
 * @param liveBytes                    bytes of active records across the catalog and all collection data stores
 * @param wasteBytes                   bytes of superseded records inside the current data stores; reclaimed by
 *                                     compaction
 * @param walBytes                     bytes of retained write-ahead log files; `0` when time travel is disabled
 * @param awaitingDeletionBytes        bytes of superseded data files that are no longer current but not yet deleted
 * @param blockedByActiveReaderBytes   part of `awaitingDeletionBytes` still referenced by an open reader or writer;
 *                                     a value that stays high means a long-running session is pinning disk space
 * @param purgeableBytes               part of `awaitingDeletionBytes` that no reader blocks, waiting only on the
 *                                     purge mechanism
 * @param bootstrapBytes               bytes of the catalog bootstrap file
 * @param unaccountedBytes             bytes present in the directory that belong to none of the classes above
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record StorageSizeStatistics(
	long sizeOnDiskInBytes,
	long liveBytes,
	long wasteBytes,
	long walBytes,
	long awaitingDeletionBytes,
	long blockedByActiveReaderBytes,
	long purgeableBytes,
	long bootstrapBytes,
	long unaccountedBytes
) {
}
