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

import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;
import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint.DataStoreGenerations;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileNamePattern;
import static io.evitadb.spi.store.catalog.persistence.PersistenceService.BOOT_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.PersistenceService.WAL_FILE_SUFFIX;

/**
 * Turns a catalog directory into the {@link CatalogStorageFootprint} the engine reports.
 *
 * **This is the only thing that produces a {@link CatalogStorageFootprint}**, and the record's total-equals-sum
 * identity holds by construction of the one loop below and by nothing else: every class is a sum over a disjoint
 * subset of a single listing, and `unaccountedBytes` is the arithmetic remainder rather than an independent
 * measurement. A caller assembling the record itself would be free to violate that, which is why nothing does.
 *
 * It lives here rather than beside the record because listing a directory and reading file lengths is physical
 * contact with storage, which belongs to this module alone - see `.claude/rules/module-boundaries.md`. The record
 * stays in the SPI because the engine's own `CatalogFolderOperations#catalogFolderFootprint` returns it, and the
 * SPI cannot depend on the storage module that fills it in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CatalogStorageFootprintMeasurer {

	/**
	 * This class is a collection of static measurement helpers and must never be instantiated.
	 */
	private CatalogStorageFootprintMeasurer() {
	}

	/**
	 * Measures a catalog directory and attributes every byte in it to exactly one storage class.
	 *
	 * Both callers share it: a loaded catalog, which knows which generation of each data store the header points at
	 * and how much of it is live, and one that would not load, which knows neither. That second reading is what
	 * `generations == null` expresses - see {@link DataStoreGenerations}.
	 *
	 * @param catalogName        name of the catalog, used as the storage prefix only when the folder carries no
	 *                           bootstrap file to read the real one from - see {@link #discoverStoragePrefix}
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
			return new CatalogStorageFootprint(0L, 0L, 0L, 0L, 0L, 0, 0L, 0, 0L, 0L, 0L, 0L, 0L, 0L);
		}
		return measure(catalogName, files, generations);
	}

	/**
	 * Measures an already-listed catalog directory - the same classification as
	 * {@link #measure(String, Path, DataStoreGenerations)}, for a caller that has the listing in hand and needs the
	 * file lengths for something else as well.
	 *
	 * That caller is the compaction forecast, which evaluates the compaction predicate against each data store's file
	 * length. Re-reading those lengths itself would cost one `stat` per store *and* let the size report and the
	 * forecast describe two different moments of the same file - so the listing is taken once and both readings are
	 * derived from it.
	 *
	 * @param catalogName name of the catalog, used as the storage prefix only when the listing carries no bootstrap
	 *                    file to read the real one from - see {@link #discoverStoragePrefix}
	 * @param files       the flat listing of the catalog directory
	 * @param generations what the catalog header says is current, or `null` when it could not be read
	 * @return the decomposed footprint of the listing
	 */
	@Nonnull
	public static CatalogStorageFootprint measure(
		@Nonnull String catalogName,
		@Nonnull File[] files,
		@Nullable DataStoreGenerations generations
	) {
		// every name derived below is built from the *storage prefix*, which a rename or a `replaceCatalog` leaves
		// behind while relabelling the catalog - so it cannot be assumed to equal the catalog name. Getting it wrong
		// does not merely mis-size one class: the bootstrap file, the superseded-generation pattern and the catalog's
		// own data store all stop matching at once, and the whole decomposition collapses into `unaccountedBytes`
		final String storagePrefix = discoverStoragePrefix(files, catalogName);
		final String bootstrapFile = getCatalogBootstrapFileName(storagePrefix);
		final Pattern catalogFilePattern = getCatalogDataStoreFileNamePattern(storagePrefix);

		// the catalog's own current data store file, so its share of the live/waste split can be reported apart from
		// the collections' - without a header there is no current generation to name, and the split stays at zero
		// alongside every other class the header would have been needed to attribute
		final String currentCatalogFile = generations == null ?
			null : getCatalogDataStoreFileName(storagePrefix, generations.currentCatalogFileIndex());

		long totalBytes = 0L;
		long liveBytes = 0L;
		long wasteBytes = 0L;
		long catalogDataStoreLiveBytes = 0L;
		long catalogDataStoreWasteBytes = 0L;
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
					if (fileName.equals(currentCatalogFile)) {
						catalogDataStoreLiveBytes = live;
						catalogDataStoreWasteBytes = length - live;
					}
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
			catalogDataStoreLiveBytes,
			catalogDataStoreWasteBytes,
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
	 * Reads the prefix the listing's files are actually named with, rather than assuming it equals the catalog name.
	 *
	 * A rename or a `replaceCatalog` relabels a catalog without touching a single file name - that is what makes it a
	 * pointer swap rather than a filesystem walk - so the two are free to diverge, and the bootstrap file is the
	 * authority on which one the files carry. This mirrors `DefaultCatalogPersistenceService#discoverStoragePrefix`,
	 * which is where a catalog being *opened* resolves the same question.
	 *
	 * **It differs from that one in refusing to throw**, and deliberately so. This is the measurement path, whose
	 * whole reason for existing is to describe a catalog that would not open - a folder holding files but no bootstrap
	 * file is exactly the corruption an operator is calling this to size up, and answering it with an exception would
	 * withhold the reading precisely when it is the only one available. Falling back to the catalog name leaves those
	 * bytes in `unaccountedBytes`, which is visible and honest; the alternative is no answer at all.
	 *
	 * @param files       the flat listing of the catalog directory
	 * @param catalogName fallback for a listing that carries no bootstrap file - either a catalog whose first one has
	 *                    not been written yet, or one whose folder is damaged
	 * @return prefix shared by the folder's files
	 */
	@Nonnull
	private static String discoverStoragePrefix(@Nonnull File[] files, @Nonnull String catalogName) {
		for (final File file : files) {
			final String fileName = file.getName();
			if (file.isFile() && fileName.endsWith(BOOT_FILE_SUFFIX)) {
				return fileName.substring(0, fileName.length() - BOOT_FILE_SUFFIX.length());
			}
		}
		return catalogName;
	}

}
