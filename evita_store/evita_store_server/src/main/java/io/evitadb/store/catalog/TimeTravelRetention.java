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

package io.evitadb.store.catalog;

import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Defines what a **history horizon** keeps on disk, and picks the horizon that fits a byte budget.
 *
 * A bootstrap record is readable only when all three of these hold: the record is present in the bootstrap file, the
 * catalog data file it names is present, and every entity collection alive at that version has its collection data
 * file present. Call that tuple a **generation** - it, and not the bootstrap record, is the reclaimable unit, because
 * consecutive records routinely share one generation and a generation frees only once retention passes the last record
 * referencing it.
 *
 * Every index a record pins is monotonically non-decreasing along the record sequence: `catalogFileIndex` only ever
 * increments, each collection's `fileIndex` only ever increments, and entity type primary keys come from a monotonic
 * sequence and are never reused. Deleting the lowest-index file of *any* component therefore kills a **prefix** of
 * records, the reachable set is always a **suffix**, and history has exactly one horizon. Per-collection compaction
 * happening at different moments never produces a ragged frontier - it only means that advancing the horizon frees
 * different amounts per component, sometimes zero. The same property makes the retained size monotone non-increasing
 * in the horizon, which is what {@link #resolveHorizon} binary-searches over.
 *
 * The survivor rules live here rather than at either call site on purpose: {@link ObsoleteFileMaintainer} executes them
 * to delete files while the size guard evaluates them to predict bytes, and the two silently drifting apart would make
 * the guard trim history it never actually reclaims.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TimeTravelRetention {

	private TimeTravelRetention() {
		// utility class
	}

	/**
	 * Tells whether the catalog data file of the given index is unreachable from the horizon generation and may be
	 * deleted.
	 *
	 * @param fileIndex               index parsed out of the catalog data file name
	 * @param horizonCatalogFileIndex {@link CatalogBootstrap#catalogFileIndex()} of the oldest retained record
	 * @return true when the file may be reclaimed
	 */
	public static boolean isCatalogDataFileObsolete(int fileIndex, int horizonCatalogFileIndex) {
		return fileIndex < horizonCatalogFileIndex;
	}

	/**
	 * Tells whether the entity collection data file is unreachable from the horizon generation and may be deleted.
	 *
	 * A collection missing from the horizon header is ambiguous, and resolving that ambiguity is the whole point of
	 * this method: the collection either did not exist yet at the horizon version, or existed once and was dropped.
	 * Entity type primary keys come from a monotonic sequence and are never reused, so the last key assigned at the
	 * horizon version separates the two cases cleanly. Treating mere absence as "reclaim" - as this code did before -
	 * deletes the live files of every collection created after the horizon.
	 *
	 * @param entityTypePrimaryKey                  entity type primary key parsed out of the collection file name
	 * @param fileIndex                             file index parsed out of the collection file name
	 * @param pinnedFileIndexes                     entity type primary key to pinned file index, as recorded in the
	 *                                              horizon's {@link CatalogHeader#getEntityTypeFileIndexes()}
	 * @param lastEntityCollectionPrimaryKeyAtHorizon the horizon header's
	 *                                              {@link CatalogHeader#lastEntityCollectionPrimaryKey()} watermark
	 * @return true when the file may be reclaimed
	 */
	public static boolean isEntityCollectionFileObsolete(
		int entityTypePrimaryKey,
		int fileIndex,
		@Nonnull Map<Integer, Integer> pinnedFileIndexes,
		int lastEntityCollectionPrimaryKeyAtHorizon
	) {
		final Integer pinnedFileIndex = pinnedFileIndexes.get(entityTypePrimaryKey);
		if (pinnedFileIndex != null) {
			// the collection was alive at the horizon - everything below the generation it pins is unreachable
			return fileIndex < pinnedFileIndex;
		}
		// above the watermark the collection did not exist yet, so all of its files were written later and are still
		// pinned by a retained record; below it the collection existed once and was dropped, so nothing can reach it
		return entityTypePrimaryKey <= lastEntityCollectionPrimaryKeyAtHorizon;
	}

	/**
	 * Sums the sizes of all data files that survive the given horizon. The result includes the active data set, which
	 * is why callers interested in history alone subtract {@link #retainedBytes} of the newest generation.
	 *
	 * @param inventory the data files present on disk together with their sizes
	 * @param pin       the generation pinned by the candidate horizon record
	 * @return total bytes of files that survive the horizon
	 */
	public static long retainedBytes(@Nonnull DataFileInventory inventory, @Nonnull GenerationPin pin) {
		long sum = 0L;
		for (CatalogDataFile file : inventory.catalogFiles()) {
			if (!isCatalogDataFileObsolete(file.fileIndex(), pin.catalogFileIndex())) {
				sum += file.sizeInBytes();
			}
		}
		for (EntityCollectionDataFile file : inventory.entityCollectionFiles()) {
			if (!isEntityCollectionFileObsolete(
				file.entityTypePrimaryKey(), file.fileIndex(),
				pin.entityCollectionFileIndexes(), pin.lastEntityCollectionPrimaryKey()
			)) {
				sum += file.sizeInBytes();
			}
		}
		return sum;
	}

	/**
	 * Finds the oldest bootstrap record that may be retained without the history exceeding `limitBytes`.
	 *
	 * History bytes are monotone non-increasing in the record index (see the class comment), so the answer is found by
	 * binary search: `O(log n)` catalog header reads, about fifteen probes for thirty-two thousand records. The newest
	 * record always satisfies the budget - it retains no history at all - so a solution is guaranteed to exist and the
	 * search never runs off the end.
	 *
	 * @param recordCount number of records currently present in the bootstrap file, at least one
	 * @param pinResolver resolves the generation pinned by the record at the given index; each call typically costs one
	 *                    catalog header read, so it is expected to be non-trivial and is probed sparingly
	 * @param inventory   the data files present on disk together with their sizes
	 * @param limitBytes  budget for history bytes on top of the active data set, never negative
	 * @return the horizon decision, whose {@link HorizonDecision#recordIndex()} is `0` when nothing needs to be trimmed
	 */
	@Nonnull
	public static HorizonDecision resolveHorizon(
		int recordCount,
		@Nonnull IntFunction<GenerationPin> pinResolver,
		@Nonnull DataFileInventory inventory,
		long limitBytes
	) {
		Assert.isPremiseValid(recordCount > 0, "Bootstrap file is expected to hold at least one record!");
		Assert.isPremiseValid(limitBytes >= 0L, "History size limit must not be negative!");

		// the newest record pins exactly the active data set - every collection alive at it is pinned, and every
		// collection absent from it sits below its watermark and is therefore already reclaimable
		final long activeBytes = retainedBytes(inventory, pinResolver.apply(recordCount - 1));
		final long historyAtOldest = retainedBytes(inventory, pinResolver.apply(0)) - activeBytes;
		if (historyAtOldest <= limitBytes) {
			return new HorizonDecision(0, historyAtOldest, historyAtOldest);
		}

		int lowerBound = 1;
		int upperBound = recordCount - 1;
		while (lowerBound < upperBound) {
			final int middle = (lowerBound + upperBound) >>> 1;
			if (retainedBytes(inventory, pinResolver.apply(middle)) - activeBytes <= limitBytes) {
				upperBound = middle;
			} else {
				lowerBound = middle + 1;
			}
		}
		// the winning index may never have been probed directly - one extra read beats threading a stale value through
		final long retainedHistoryBytes = retainedBytes(inventory, pinResolver.apply(lowerBound)) - activeBytes;
		return new HorizonDecision(lowerBound, retainedHistoryBytes, historyAtOldest);
	}

	/**
	 * The generation a single bootstrap record pins - the tuple whose files must all be present for that record to be
	 * readable.
	 *
	 * @param catalogFileIndex               index of the catalog data file the record points at
	 * @param entityCollectionFileIndexes    entity type primary key to the collection file index alive at that version
	 * @param lastEntityCollectionPrimaryKey the highest entity type primary key assigned at that version
	 */
	public record GenerationPin(
		int catalogFileIndex,
		@Nonnull Map<Integer, Integer> entityCollectionFileIndexes,
		int lastEntityCollectionPrimaryKey
	) {
	}

	/**
	 * A catalog data file present in the catalog folder.
	 *
	 * @param fileIndex   index parsed out of the file name
	 * @param sizeInBytes size of the file on disk
	 */
	public record CatalogDataFile(
		int fileIndex,
		long sizeInBytes
	) {
	}

	/**
	 * An entity collection data file present in the catalog folder.
	 *
	 * @param entityTypePrimaryKey entity type primary key parsed out of the file name
	 * @param fileIndex            file index parsed out of the file name
	 * @param sizeInBytes          size of the file on disk
	 */
	public record EntityCollectionDataFile(
		int entityTypePrimaryKey,
		int fileIndex,
		long sizeInBytes
	) {
	}

	/**
	 * The data files present in the catalog folder at the moment the guard ran. Neither the write-ahead log nor the
	 * bootstrap file is part of it - the limit constrains historical *data* files, while WAL retention keeps its own
	 * independent bound.
	 *
	 * @param catalogFiles          all catalog data files
	 * @param entityCollectionFiles all entity collection data files
	 */
	public record DataFileInventory(
		@Nonnull CatalogDataFile[] catalogFiles,
		@Nonnull EntityCollectionDataFile[] entityCollectionFiles
	) {
	}

	/**
	 * The outcome of {@link #resolveHorizon}.
	 *
	 * @param recordIndex               index of the oldest bootstrap record that may be retained; `0` means the history
	 *                                  already fits and nothing needs to be trimmed
	 * @param retainedHistoryBytes      history bytes left once the horizon is applied
	 * @param historyBytesBeforeAdvance history bytes as they stood before the horizon moved; equal to
	 *                                  `retainedHistoryBytes` when nothing needed trimming
	 */
	public record HorizonDecision(
		int recordIndex,
		long retainedHistoryBytes,
		long historyBytesBeforeAdvance
	) {

		/**
		 * Tells whether the budget could not accommodate a single generation of history. The operator asked for a
		 * budget that a single compaction overflows, so history collapses to nothing - the correct reading of their
		 * instruction, but one worth reporting because it silently turns time travel off.
		 *
		 * @return true when history had to be given up entirely
		 */
		public boolean historyCollapsedToNothing() {
			return this.retainedHistoryBytes == 0L && this.historyBytesBeforeAdvance > 0L;
		}
	}
}
