/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.catalog;

import io.evitadb.core.CatalogVersionBeyondTheHorizonListener;
import io.evitadb.scheduling.DelayedAsyncTask;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class is responsible for clearing all the files that were made obsolete either by deleting or renaming to
 * different names. We can't remove the files right away because there might be some active sessions that are still
 * reading the files. We need to wait until all the active sessions are done reading the files and then we can remove
 * the files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObsoleteFileMaintainer implements CatalogVersionBeyondTheHorizonListener, AutoCloseable {
	/**
	 * Asynchronous task that purges obsolete files.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * List of files that are maintained until they are no longer used and can be purged.
	 */
	private final List<MaintainedFile> maintainedFiles = new CopyOnWriteArrayList<>();
	/**
	 * The last catalog version whose file was added to the maintained files. This variable guards the monotonicity of
	 * the maintained files list.
	 */
	private final AtomicLong lastCatalogVersion = new AtomicLong(0L);
	/**
	 * The catalog version that is no longer used and all files with the version less or equal to this value can be
	 * safely purged.
	 */
	private final AtomicLong noLongerUsedCatalogVersion = new AtomicLong(0L);

	/**
	 * Purges the specified maintained file.
	 *
	 * This method deletes the specified maintained file from the file system. If the deletion is successful,
	 * the removalLambda associated with the maintained file is executed.
	 *
	 * @param maintainedFile the maintained file to be purged
	 */
	private static void purgeFile(@Nonnull MaintainedFile maintainedFile) {
		if (maintainedFile.path().toFile().delete()) {
			maintainedFile.removalLambda().run();
			log.debug("Deleted obsolete file {}", maintainedFile.path());
		} else {
			log.warn("Could not delete obsolete file {}", maintainedFile.path());
		}
	}

	public ObsoleteFileMaintainer(@Nonnull Scheduler scheduler) {
		this.purgeTask = new DelayedAsyncTask(
			scheduler, this::purgeObsoleteFiles,
			0L, ChronoUnit.MILLIS
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
	 * asynchronous file removal.
	 *
	 * @param catalogVersion                The new catalog version that is no longer used by any active session
	 * @param activeSessionsToOlderVersions Set to true if there are still active sessions using older versions,
	 *                                      set to false otherwise
	 */
	@Override
	public void catalogVersionBeyondTheHorizon(long catalogVersion, boolean activeSessionsToOlderVersions) {
		if (!activeSessionsToOlderVersions) {
			this.noLongerUsedCatalogVersion.accumulateAndGet(
				catalogVersion,
				Math::max
			);
			this.purgeTask.schedule();
		}
	}

	/**
	 * Method is called from {@link #purgeTask} to remove all files that are no longer used. The method iterates over
	 * all maintained files and removes the files whose catalog version is less or equal to the last catalog version
	 * that is no longer used.
	 *
	 * @return the next scheduled time for the purge task (always -1L - i.e. do not schedule again)
	 */
	private long purgeObsoleteFiles() {
		final long noLongerUsed = this.noLongerUsedCatalogVersion.get();
		final List<MaintainedFile> itemsToRemove = new LinkedList<>();
		for (MaintainedFile maintainedFile : this.maintainedFiles) {
			if (maintainedFile.catalogVersion() <= noLongerUsed) {
				purgeFile(maintainedFile);
				itemsToRemove.add(maintainedFile);
			} else {
				// the list is sorted by the catalog version, so we can break the loop
				break;
			}
		}
		this.maintainedFiles.removeAll(itemsToRemove);
		return -1L;
	}

	@Override
	public void close() {
		// clear all files immediately, database shuts down and there will be no active sessions
		this.noLongerUsedCatalogVersion.set(0L);
		for (MaintainedFile maintainedFile : maintainedFiles) {
			purgeFile(maintainedFile);
		}
		this.maintainedFiles.clear();
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

}
