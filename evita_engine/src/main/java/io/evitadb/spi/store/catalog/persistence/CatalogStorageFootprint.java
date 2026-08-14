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
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.GENERIC_ENTITY_COLLECTION_PATTERN;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getIndexFromCatalogFileName;

/**
 * The storage-layer view of how many bytes a catalog occupies on disk, and which class of storage each of them
 * belongs to.
 *
 * **Produced by `CatalogStorageFootprintMeasurer` in `evita_store_server`, and by nothing else.** The reconciliation
 * described below holds by construction of that one classification loop and by no other means, so assembling this
 * record anywhere else would be free to violate it. The measurement lives in the storage module rather than here
 * because listing a directory and reading file lengths is physical contact with storage - see
 * `.claude/rules/module-boundaries.md`. This record stays in the SPI because
 * `CatalogFolderOperations#catalogFolderFootprint` returns it and the SPI cannot depend on the module that fills
 * it in; the canonical constructor is therefore reachable, and the discipline is a convention rather than an
 * enclosure the compiler enforces.
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
 * **The catalog's own data store is reported separately as well as inside the totals.** `catalogDataStoreLiveBytes`
 * and `catalogDataStoreWasteBytes` are the part of `liveBytes` / `wasteBytes` that belongs to the catalog's own file
 * rather than to any collection's, so `liveBytes == catalogDataStoreLiveBytes + the sum over every *open* collection`
 * holds within one measurement. That qualifier is the reason the split has to be measured here rather than derived
 * by a caller subtracting separately-fetched per-collection values: a current collection file whose persistence
 * service is not open contributes to neither class and falls into `unaccountedBytes`, so the subtraction is wrong by
 * exactly those collections - and the two sides of it would be snapshots at different catalog versions anyway.
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
 * @param catalogDataStoreLiveBytes  the part of `liveBytes` held by the catalog's own data store rather than by any
 *                                   collection's
 * @param catalogDataStoreWasteBytes the part of `wasteBytes` held by the catalog's own data store
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
	long catalogDataStoreLiveBytes,
	long catalogDataStoreWasteBytes,
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
