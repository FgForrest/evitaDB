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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.GENERIC_ENTITY_COLLECTION_PATTERN;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileNamePattern;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getIndexFromCatalogFileName;
import static io.evitadb.spi.store.catalog.persistence.PersistenceService.WAL_FILE_SUFFIX;

/**
 * The storage-layer view of how many bytes a catalog occupies on disk, and which class of storage each of them
 * belongs to. Produced by {@link #measure(String, Path, DataStoreGenerations)} from a single listing of the catalog
 * directory - which is also the *only* way it should be produced, because the reconciliation described below holds
 * by construction of that method and by nothing else.
 *
 * This is deliberately *not* the API-facing statistics record: the persistence layer knows file names, file lengths
 * and offset-index bookkeeping, and nothing about statistics components. The engine maps this record onto the
 * public shape, which keeps the store modules free of any dependency on the statistics vocabulary.
 *
 * **The total is measured; the remainder is derived.** `totalBytes` is the sum of the lengths of every file in the
 * catalog directory, and `unaccountedBytes` is what is left after subtracting every other class - so
 * `totalBytes == liveBytes + wasteBytes + walBytes + awaitingDeletionBytes + bootstrapBytes + unaccountedBytes`
 * holds by construction rather than by agreement between two independent measurements. Every class is a sum over a
 * disjoint subset of the same listing, which is what keeps that identity true even while files are being written.
 *
 * `blockedByActiveReaderBytes` and `purgeableBytes` are a partition of `awaitingDeletionBytes`, not additional
 * classes: they sum to it and must not be added to the total again.
 *
 * **The file counts and the reader floor ride along with the byte classes rather than being measured separately.**
 * Both counts are byproducts of the same listing the bytes come from, and the floor is read at the same moment the
 * superseded files are classified by it - so a caller that needs the history view and the size view together pays for
 * one directory snapshot instead of two, and the two views cannot disagree about what was on disk.
 *
 * @param totalBytes                 measured total - the sum of the lengths of every file in the catalog directory
 * @param liveBytes                  bytes of active records in the current data store files, clamped to those files'
 *                                   actual lengths
 * @param wasteBytes                 the rest of the current data store files - superseded records that compaction
 *                                   reclaims
 * @param walFileCount               number of write-ahead log files present in the directory
 * @param walBytes                   bytes of the write-ahead log files present in the directory
 * @param awaitingDeletionFileCount  number of superseded data store files that are no longer current but still on disk
 * @param awaitingDeletionBytes      bytes of those files
 * @param blockedByActiveReaderBytes part of `awaitingDeletionBytes` that an active reader or writer still pins
 * @param purgeableBytes             part of `awaitingDeletionBytes` that nothing blocks
 * @param bootstrapBytes             bytes of the catalog bootstrap file
 * @param unaccountedBytes           everything else in the directory - the derived remainder
 * @param activeReaderFloor          minimal catalog version still referenced by an active reader or writer; `0` means
 *                                   none has been observed yet and therefore that nothing is pinned - it does *not*
 *                                   mean a reader is sitting on version zero
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CatalogStorageFootprint(
	long totalBytes,
	long liveBytes,
	long wasteBytes,
	int walFileCount,
	long walBytes,
	int awaitingDeletionFileCount,
	long awaitingDeletionBytes,
	long blockedByActiveReaderBytes,
	long purgeableBytes,
	long bootstrapBytes,
	long unaccountedBytes,
	long activeReaderFloor
) {

	/**
	 * Measures a catalog directory and attributes every byte in it to exactly one storage class.
	 *
	 * **The classes are disjoint buckets of a single listing and `unaccountedBytes` is the arithmetic remainder** -
	 * never an independent measurement. That is the only construction under which the record's total-equals-sum
	 * identity cannot be violated, which is why this method exists rather than each caller summing for itself.
	 *
	 * Both callers share it: a loaded catalog, which knows which generation of each data store the header points at
	 * and how much of it is live, and one that would not load, which knows neither. That second reading is what
	 * `generations == null` expresses - see {@link DataStoreGenerations}.
	 *
	 * @param catalogName        name of the catalog, which is what its bootstrap file is named after
	 * @param catalogStoragePath directory holding the catalog's files; the listing is flat, since the layout is
	 * @param generations        what the catalog header says is current, or `null` when it could not be read
	 * @return the decomposed footprint of the directory
	 */
	@Nonnull
	public static CatalogStorageFootprint measure(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nullable DataStoreGenerations generations
	) {
		final File[] files = catalogStoragePath.toFile().listFiles();
		if (files == null) {
			// the directory is gone or unreadable - which is itself an honest answer of "nothing measurable here"
			return new CatalogStorageFootprint(0L, 0L, 0L, 0, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L);
		}

		final String bootstrapFile = getCatalogBootstrapFileName(catalogName);
		final Pattern catalogFilePattern = getCatalogDataStoreFileNamePattern(catalogName);

		long totalBytes = 0L;
		long liveBytes = 0L;
		long wasteBytes = 0L;
		int walFileCount = 0;
		long walBytes = 0L;
		int awaitingDeletionFileCount = 0;
		long awaitingDeletionBytes = 0L;
		long blockedByActiveReaderBytes = 0L;
		long bootstrapBytes = 0L;

		for (final File file : files) {
			if (!file.isFile()) {
				// the catalog directory is flat by design - anything else is neither measured nor attributed, and
				// `CatalogStorageFootprintTest` pins the equivalence with the recursive walk so this cannot rot
				continue;
			}
			final String fileName = file.getName();
			final long length = file.length();
			totalBytes += length;
			if (fileName.equals(bootstrapFile)) {
				bootstrapBytes += length;
			} else if (fileName.endsWith(WAL_FILE_SUFFIX)) {
				walFileCount++;
				walBytes += length;
			} else if (generations == null) {
				// nothing else is attributable without the header: separating live records from compaction waste, or
				// a current generation from a superseded one, is exactly what could not be read. Those bytes stay
				// visible in the remainder rather than being guessed at
				continue;
			} else if (generations.currentDataStoreFiles().contains(fileName)) {
				final Long activeSize = generations.activeSizeByCurrentFile().get(fileName);
				if (activeSize != null) {
					// the clamp is load-bearing, not defensive: with compression enabled the active size is an
					// estimate that can exceed the file it describes (see OffsetIndex#getTotalActiveSize), and an
					// unclamped value would drive `unaccountedBytes` negative and make the total-equals-sum
					// invariant of this record false
					final long live = Math.min(activeSize, length);
					liveBytes += live;
					wasteBytes += length - live;
				}
				// a current data store file whose service is not open stays in the unaccounted remainder
			} else if (generations.isSuperseded(fileName, catalogFilePattern)) {
				awaitingDeletionFileCount++;
				awaitingDeletionBytes += length;
				// a file the engine is holding for deferred removal carries the last catalog version that may use
				// it; at or above the floor an active reader or writer still pins it. A floor of zero means no
				// active version has been observed at all, which blocks nothing - and a file absent from the map is
				// one nothing is holding, so neither reads as blocked
				final Long maintainedVersion = generations.maintainedFileVersions().get(file.toPath());
				final long activeReaderFloor = generations.activeReaderFloor();
				if (activeReaderFloor > 0L && maintainedVersion != null && maintainedVersion >= activeReaderFloor) {
					blockedByActiveReaderBytes += length;
				}
			}
			// everything else - restore markers, temporary files left by an interrupted compaction, data store
			// generations *newer* than the header (compaction output whose header flip has not happened yet) -
			// falls into the unaccounted remainder below, which is where it belongs: visible, not silently dropped
		}

		return new CatalogStorageFootprint(
			totalBytes,
			liveBytes,
			wasteBytes,
			walFileCount,
			walBytes,
			awaitingDeletionFileCount,
			awaitingDeletionBytes,
			blockedByActiveReaderBytes,
			awaitingDeletionBytes - blockedByActiveReaderBytes,
			bootstrapBytes,
			totalBytes - liveBytes - wasteBytes - walBytes - awaitingDeletionBytes - bootstrapBytes,
			generations == null ? 0L : generations.activeReaderFloor()
		);
	}

	/**
	 * What a loaded catalog knows about its own data store files, and a catalog that would not load does not: which
	 * generation of each store the header points at, how much of each is live, and which superseded files are still
	 * being held for deferred removal.
	 *
	 * Passing `null` instead of an instance is not "the empty case" - it is a different classification. With no
	 * header, *every* data store file is unattributable, whereas an instance whose maps happen to be empty means
	 * "the header names no data store at all", under which any data store file found is a superseded leftover. The
	 * two readings differ, so they must not share a representation.
	 *
	 * @param currentCatalogFileIndex            generation index of the catalog's own data store the header points at
	 * @param currentDataStoreFiles              file names of the current generation of every data store
	 * @param activeSizeByCurrentFile            live size reported by the offset index of each *open* data store,
	 *                                           keyed by file name; a current file missing here cannot be split into
	 *                                           live and waste and falls to the remainder
	 * @param currentIndexByEntityTypePrimaryKey generation index of every collection the header knows, keyed by the
	 *                                           entity type primary key that its file name carries
	 * @param maintainedFileVersions             last catalog version that may use each file held for deferred
	 *                                           removal, keyed by path - see
	 *                                           `ObsoleteFileMaintainer#getMaintainedFileVersions()`
	 * @param activeReaderFloor                  minimal catalog version still referenced by an active reader or
	 *                                           writer, or `0` when none has been observed - which blocks nothing
	 */
	public record DataStoreGenerations(
		int currentCatalogFileIndex,
		@Nonnull Set<String> currentDataStoreFiles,
		@Nonnull Map<String, Long> activeSizeByCurrentFile,
		@Nonnull Map<Integer, Integer> currentIndexByEntityTypePrimaryKey,
		@Nonnull Map<Path, Long> maintainedFileVersions,
		long activeReaderFloor
	) {

		/**
		 * Returns generations describing a catalog that holds no data store files yet.
		 *
		 * @return empty generations - a header that names nothing, as opposed to a header that could not be read
		 */
		@Nonnull
		public static DataStoreGenerations empty() {
			return new DataStoreGenerations(
				0, Set.of(), Map.of(), Map.of(), Map.of(), 0L
			);
		}

		/**
		 * Tells whether the given file name is a data store file of a generation *older* than the one the header
		 * points at - the definition of a file that is superseded but not yet deleted.
		 *
		 * A generation *newer* than the current one is deliberately not superseded: compaction writes the next index
		 * before the header flips to it, so a snapshot taken mid-compaction sees output that is about to become
		 * current. Reporting it as garbage would be the exact inverse of the truth.
		 *
		 * @param fileName           name of the file to classify
		 * @param catalogFilePattern pattern matching this catalog's own data store files
		 * @return true when the file is a superseded generation of a catalog or collection data store
		 */
		public boolean isSuperseded(@Nonnull String fileName, @Nonnull Pattern catalogFilePattern) {
			if (fileName.endsWith(CATALOG_FILE_SUFFIX)) {
				return catalogFilePattern.matcher(fileName).matches() &&
					getIndexFromCatalogFileName(fileName) < this.currentCatalogFileIndex;
			} else if (fileName.endsWith(ENTITY_COLLECTION_FILE_SUFFIX)) {
				if (!GENERIC_ENTITY_COLLECTION_PATTERN.matcher(fileName).matches()) {
					return false;
				}
				final CatalogPersistenceService.EntityTypePrimaryKeyAndFileIndex reference = CatalogPersistenceService
					.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(fileName);
				final Integer currentIndex = this.currentIndexByEntityTypePrimaryKey.get(
					reference.entityTypePrimaryKey()
				);
				// a collection the header does not know at all - a leftover of a dropped collection - is superseded
				// in full, since no generation of it is current
				return currentIndex == null || reference.fileIndex() < currentIndex;
			} else {
				return false;
			}
		}
	}
}
