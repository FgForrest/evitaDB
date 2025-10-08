/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.core.CatalogConsumersListener;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.spi.CatalogPersistenceService.EntityTypePrimaryKeyAndFileIndex;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.wal.AbstractMutationLog.WalPurgeCallback;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import static io.evitadb.store.spi.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getIndexFromCatalogFileName;
import static java.util.Optional.ofNullable;

/**
 * This class is responsible for clearing all the files that were made obsolete either by deleting or renaming to
 * different names. We can't remove the files right away because there might be some active sessions that are still
 * reading the files. We need to wait until all the active sessions are done reading the files and then we can remove
 * the files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObsoleteFileMaintainer implements CatalogConsumersListener, Closeable {
	/**
	 * When time travel is enabled the files are not removed immediately but are kept until the WAL history is purged.
	 */
	private final boolean timeTravelEnabled;
	/**
	 * Folder where the catalog files are stored.
	 */
	private final Path catalogStoragePath;
	/**
	 * Asynchronous task that purges obsolete files.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * List of files that are maintained until they are no longer used and can be purged.
	 */
	private final List<MaintainedFile> maintainedFiles = new CopyOnWriteArrayList<>();
	/**
	 * The first catalog version whose file was added to the maintained files. This variable optimizes the number of
	 * purge task executions.
	 */
	private final AtomicLong firstCatalogVersion = new AtomicLong(0L);
	/**
	 * The last catalog version whose file was added to the maintained files. This variable guards the monotonicity of
	 * the maintained files list.
	 */
	private final AtomicLong lastCatalogVersion = new AtomicLong(0L);
	/**
	 * The catalog version that is no longer used and all files with the version less or equal to this value can be
	 * safely purged.
	 */
	private final AtomicLong lastKnownMinimalActiveVersion = new AtomicLong(0L);
	/**
	 * The supplier of the catalog header for the specified catalog version.
	 */
	private final LongFunction<DataFilesBulkInfo> dataFilesInfoFetcher;

	public ObsoleteFileMaintainer(
		@Nonnull String catalogName,
		@Nonnull Scheduler scheduler,
		@Nonnull Path catalogStoragePath,
		boolean timeTravelEnabled,
		@Nonnull LongFunction<DataFilesBulkInfo> dataFilesInfoFetcher
	) {
		this.catalogStoragePath = catalogStoragePath;
		this.timeTravelEnabled = timeTravelEnabled;
		this.dataFilesInfoFetcher = dataFilesInfoFetcher;
		// purge task is not present when time travel is enabled
		// in this situation the files are removed in the synchronous manner when the WAL history is purged
		this.purgeTask = new DelayedAsyncTask(
			catalogName, "Obsolete files purger",
			scheduler,
			this::purgeObsoleteFiles,
			0L, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Removes a file when it is no longer used. The file is associated with a catalog version and a path.
	 *
	 * @param catalogVersion the catalog version associated with the file
	 * @param path           the path of the file
	 * @param removalLambda  the lambda function to be executed when the file is removed
	 */
	public void removeFileWhenNotUsed(
		long catalogVersion,
		@Nonnull Path path,
		@Nonnull Runnable removalLambda
	) {
		final MaintainedFile fileToMaintain = new MaintainedFile(catalogVersion, path, removalLambda);
		if (catalogVersion <= 0L) {
			// version 0L represents catalog in WARM-UP (non-transactional) state where we apply all changes immediately
			purgeFile(fileToMaintain);
		} else {
			// if the first catalog version is not set, set it to the current catalog version
			this.firstCatalogVersion.compareAndExchange(0, catalogVersion);
			this.lastCatalogVersion.accumulateAndGet(
				catalogVersion,
				(previous, updatedValue) -> {
					Assert.isPremiseValid(previous <= updatedValue, "Catalog version must be increasing");
					return updatedValue;
				}
			);
			this.maintainedFiles.add(fileToMaintain);
		}
	}

	/**
	 * Updates the catalog version that is no longer used by any active session and plans the purge task for
	 * asynchronous file removal. This method does nothing when time travel is enabled, because the files are removed
	 * when WAL files are removed and this logic is executed in {@link ObsoleteWalPurgeCallback} callback.
	 *
	 * @param lastKnownMinimalActiveVersion the minimal catalog version that is still being used, NULL when there is no
	 *                                      active session
	 */
	@Override
	public void consumersLeft(long lastKnownMinimalActiveVersion) {
		// immediate file purging on catalog version exchange is not used when time travel is enabled
		if (!this.timeTravelEnabled) {
			if (lastKnownMinimalActiveVersion > 0L && this.firstCatalogVersion.get() < lastKnownMinimalActiveVersion) {
				this.lastKnownMinimalActiveVersion.accumulateAndGet(
					lastKnownMinimalActiveVersion,
					Math::max
				);
				this.purgeTask.schedule();
			}
		}
	}

	@Override
	public void close() {
		IOUtils.closeQuietly(this.purgeTask::close);
		// clear all files immediately, database shuts down and there will be no active sessions
		this.lastKnownMinimalActiveVersion.set(0L);
		for (MaintainedFile maintainedFile : this.maintainedFiles) {
			purgeFile(maintainedFile);
		}
		this.maintainedFiles.clear();
	}

	/**
	 * Creates the WAL purge callback that is used to remove all files that are no longer used. The callback is used
	 * when the WAL history is purged.
	 *
	 * @return the WAL purge callback
	 */
	@Nonnull
	public WalPurgeCallback createWalPurgeCallback() {
		if (this.timeTravelEnabled) {
			return new ObsoleteWalPurgeCallback(
				this.catalogStoragePath,
				this::purgeMaintainedFilesOlderThan,
				this.dataFilesInfoFetcher
			);
		} else {
			return WalPurgeCallback.NO_OP;
		}
	}

	/**
	 * Method is called from {@link ObsoleteWalPurgeCallback} that is invoked in case time travel is enabled when
	 * the bootstrap file gets reduced and old history is purged. This method first purges all maintained files and calls
	 * their respective removal lambdas.
	 */
	private void purgeMaintainedFilesOlderThan(long firstActiveCatalogVersion) {
		this.lastKnownMinimalActiveVersion.set(firstActiveCatalogVersion);
		this.purgeObsoleteFiles();
	}

	/**
	 * Method is called from {@link #purgeTask} to remove all files that are no longer used. The method iterates over
	 * all maintained files and removes the files whose catalog version is less or equal to the last catalog version
	 * that is no longer used.
	 *
	 * @return the next scheduled time for the purge task (always -1L - i.e. do not schedule again)
	 */
	private long purgeObsoleteFiles() {
		final long lastKnownMinimalActiveVersion = this.lastKnownMinimalActiveVersion.get();
		/* TOBEDONE JNO - this is only for debugging purposes, we should rely on events instead */
		log.debug(
			"Purging obsolete files - last known minimal active version: {}\nFiles waiting for removal:\n{}",
			lastKnownMinimalActiveVersion,
			this.maintainedFiles.stream()
				.map(MaintainedFile::path)
				.map(Path::toString)
				.map(path -> "\t - " + path)
				.collect(Collectors.joining("\n"))
		);
		final List<MaintainedFile> itemsToRemove = new LinkedList<>();
		long newFirstCatalogVersion = 0L;
		for (MaintainedFile maintainedFile : this.maintainedFiles) {
			if (maintainedFile.catalogVersion() < lastKnownMinimalActiveVersion) {
				purgeFile(maintainedFile);
				itemsToRemove.add(maintainedFile);
			} else {
				newFirstCatalogVersion = maintainedFile.catalogVersion();
				// the list is sorted by the catalog version, so we can break the loop
				break;
			}
		}
		this.maintainedFiles.removeAll(itemsToRemove);
		this.firstCatalogVersion.set(newFirstCatalogVersion);
		return -1L;
	}

	/**
	 * Purges the specified maintained file.
	 *
	 * This method deletes the specified maintained file from the file system. If the deletion is successful,
	 * the removalLambda associated with the maintained file is executed.
	 *
	 * @param maintainedFile the maintained file to be purged
	 */
	private void purgeFile(@Nonnull MaintainedFile maintainedFile) {
		maintainedFile.removalLambda().run();
		// when time travel is enabled, the files are removed only when bootstrap records is purged
		if (!this.timeTravelEnabled) {
			if (maintainedFile.path().toFile().delete()) {
				log.debug("Deleted obsolete file {}", maintainedFile.path());
			} else {
				log.warn("Could not delete obsolete file {}", maintainedFile.path());
			}
		}
	}

	/**
	 * Record that represents single entry of maintained file.
	 *
	 * @param catalogVersion the last catalog version that may use the file
	 * @param path           the path of the file
	 * @param removalLambda  the lambda function to be executed when the file is removed
	 */
	private record MaintainedFile(
		long catalogVersion,
		@Nonnull Path path,
		@Nonnull Runnable removalLambda
	) {
	}

	/**
	 * This record contains vital information for collecting the indexes of the first data files that are needed for
	 * time traveling snapshots. All previous records are considered obsolete and can be removed.
	 *
	 * @param bootstrapRecord bootstrap record
	 * @param catalogHeader   catalog header from particular version
	 */
	public record DataFilesBulkInfo(
		@Nonnull CatalogBootstrap bootstrapRecord,
		@Nonnull CatalogHeader catalogHeader
	) {

	}

	/**
	 * Callback synchronously removes all files which indexes are lower than the indexes mentioned in {@link CatalogHeader}
	 * of the currently first available catalog version. This callback is used only when time travel is enabled.
	 */
	@RequiredArgsConstructor
	private static class ObsoleteWalPurgeCallback implements WalPurgeCallback {
		/**
		 * Folder where the catalog files are stored.
		 */
		private final Path catalogStoragePath;
		/**
		 * The callback that is called when the catalog version is purged.
		 */
		private final LongConsumer maintainedFilePurgeCallback;
		/**
		 * The supplier of the catalog header for the specified catalog version or the first larger available
		 * catalog version.
		 */
		private final LongFunction<DataFilesBulkInfo> dataFilesInfoFetcher;
		/**
		 * The last catalog version that was observed. This variable is used to ignore calls with lower catalog version
		 * than were already processed.
		 */
		private long lastObservedCatalogVersion = -1L;

		@Override
		public void purgeFilesUpTo(long firstActiveCatalogVersion) {
			if (firstActiveCatalogVersion > this.lastObservedCatalogVersion) {
				this.lastObservedCatalogVersion = firstActiveCatalogVersion;
				// first purge all maintained files
				this.maintainedFilePurgeCallback.accept(firstActiveCatalogVersion);
				// then purge all obsolete files in the folders
				ofNullable(this.dataFilesInfoFetcher.apply(firstActiveCatalogVersion))
					.ifPresent(
						activeFiles -> {
							final int firstUsedCatalogDataFileIndex = activeFiles.bootstrapRecord().catalogFileIndex();
							final Map<Integer, Integer> entityFileIndex = activeFiles
								.catalogHeader()
								.getEntityTypeFileIndexes()
								.stream()
								.collect(
									Collectors.toMap(
										CollectionFileReference::entityTypePrimaryKey,
										CollectionFileReference::fileIndex
									)
								);

							ofNullable(
								this.catalogStoragePath.toFile()
									.listFiles((dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX))
							)
								.stream()
								.flatMap(Arrays::stream)
								.filter(file -> getIndexFromCatalogFileName(file.getName()) < firstUsedCatalogDataFileIndex)
								.forEach(file -> {
									if (file.delete()) {
										log.debug("Deleted obsolete catalog file `{}`", file.getAbsolutePath());
									} else {
										log.warn("Could not delete obsolete catalog file `{}`", file.getAbsolutePath());
									}
								});

							ofNullable(
								this.catalogStoragePath.toFile()
									.listFiles((dir, name) -> name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX))
							)
								.stream()
								.flatMap(Arrays::stream)
								.filter(file -> {
									final EntityTypePrimaryKeyAndFileIndex result = getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(file.getName());
									final Integer firstUsedEntityFileIndex = entityFileIndex.get(result.entityTypePrimaryKey());
									return firstUsedEntityFileIndex == null || result.fileIndex() < firstUsedEntityFileIndex;
								})
								.forEach(file -> {
									if (file.delete()) {
										log.debug("Deleted obsolete entity collection file `{}`", file.getAbsolutePath());
									} else {
										log.warn("Could not delete entity collection file `{}`", file.getAbsolutePath());
									}
								});
						}
					);
			} else {
				// this callback was already called with this or newer catalog version
			}
		}
	}
}
