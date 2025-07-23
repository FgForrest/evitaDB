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

package io.evitadb.api;

import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TaskNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Global management service that allows to execute various management tasks on the Evita instance and retrieve
 * global evitaDB information. These operations might require special permissions for execution and are not used
 * daily and therefore are segregated into special management class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface EvitaManagementContract {

	/**
	 * Returns complete listing of all catalogs known to the Evita instance along with their states and basic statistics.
	 */
	@Nonnull
	CatalogStatistics[] getCatalogStatistics();

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param catalogName  the name of the catalog to backup
	 * @param pastMoment   leave null for creating backup for actual dataset, or specify past moment to create backup for
	 *                     the dataset as it was at that moment
	 * @param catalogVersion precise catalog version to create backup for, or null to create backup for the latest version,
	 *                       when set not null, the pastMoment parameter is ignored
	 * @param includingWAL if true, the backup will include the Write-Ahead Log (WAL) file and when the catalog is
	 *                     restored, it'll replay the WAL contents locally to bring the catalog to the current state
	 * @return jobId of the backup process
	 * @throws TemporalDataNotAvailableException when the past data is not available
	 */
	@Nonnull
	CompletableFuture<FileForFetch> backupCatalog(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 * Full backup includes all data files, WAL files, and the catalog header file from the catalog storage.
	 * After restoring catalog from the full backup, the catalog will contain all the data - so you should be able to
	 * create even point-in-time backups from it.
	 *
	 * @param catalogName  the name of the catalog to backup
	 *
	 * @return jobId of the backup process
	 */
	@Nonnull
	CompletableFuture<FileForFetch> fullBackupCatalog(
		@Nonnull String catalogName
	);

	/**
	 * Restores a catalog from the provided InputStream which contains the binary data of a previously backed up zip
	 * file. The input stream is closed within the method.
	 *
	 * @param catalogName        the name of the catalog to restore
	 * @param totalBytesExpected total bytes expected to be read from the input stream
	 * @param inputStream        an InputStream to read the binary data of the zip file
	 * @return jobId of the restore process
	 * @throws UnexpectedIOException if an I/O error occurs
	 */
	@Nonnull
	Task<?, Void> restoreCatalog(
		@Nonnull String catalogName,
		long totalBytesExpected,
		@Nonnull InputStream inputStream
	) throws UnexpectedIOException;

	/**
	 * Restores a catalog from the provided InputStream which contains the binary data of a previously backed up zip
	 * file. The input stream is closed within the method.
	 *
	 * @param catalogName the name of the catalog to restore
	 * @param fileId      fileId of the file containing the binary data of the zip file
	 * @return jobId of the restore process
	 * @throws UnexpectedIOException if an I/O error occurs
	 */
	@Nonnull
	Task<?, Void> restoreCatalog(
		@Nonnull String catalogName,
		@Nonnull UUID fileId
	) throws FileForFetchNotFoundException;

	/**
	 * Returns list of jobs that are currently running or have been finished recently in paginated fashion.
	 *
	 * @param page     page number (1-based)
	 * @param pageSize number of items per page
	 * @param taskType allows limiting result statuses to those of a particular type
	 * @param states allows limiting result statuses to those of a particular simplified state
	 *
	 * @return list of jobs
	 */
	@Nonnull
	PaginatedList<TaskStatus<?, ?>> listTaskStatuses(
		int page, int pageSize,
		@Nullable String[] taskType,
		@Nonnull TaskSimplifiedState... states
	);

	/**
	 * Returns job status for the specified jobId or empty if the job is not found.
	 *
	 * @param jobId jobId of the job
	 * @return job status
	 * @throws TaskNotFoundException if the job with the specified jobId is not found
	 */
	@Nonnull
	Optional<TaskStatus<?, ?>> getTaskStatus(@Nonnull UUID jobId) throws TaskNotFoundException;

	/**
	 * Returns job statuses for the requested job ids. If the job with the specified jobId is not found, it is not
	 * included in the returned collection.
	 *
	 * @param jobId jobId of the job
	 * @return collection of job statuses
	 */
	@Nonnull
	Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId);

	/**
	 * Cancels the job with the specified jobId. If the job is waiting in the queue, it will be removed from the queue.
	 * If the job is already running, it must support cancelling to be interrupted and canceled.
	 *
	 * @param jobId jobId of the job
	 * @return true if the job was found and cancellation triggered, false if the job was not found
	 * @throws TaskNotFoundException if the job with the specified jobId is not found
	 */
	boolean cancelTask(@Nonnull UUID jobId) throws TaskNotFoundException;

	/**
	 * Returns list of files that are available for download.
	 *
	 * @param page     page number (1-based)
	 * @param pageSize number of items per page
	 * @param origin   optional origin of the files (derived from {@link TaskStatus#taskType()}), passing non-null value
	 *                 in this argument filters the returned files to only those that are related to the specified origin
	 * @return list of files
	 */
	@Nonnull
	PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin);

	/**
	 * Returns file with the specified fileId that is available for download or empty if the file is not found.
	 *
	 * @param fileId fileId of the file
	 * @return file to fetch
	 */
	@Nonnull
	Optional<FileForFetch> getFileToFetch(@Nonnull UUID fileId);

	/**
	 * Writes contents of the file with the specified fileId to the provided OutputStream.
	 *
	 * @param fileId fileId of the file
	 * @return the input stream to read data from
	 * @throws FileForFetchNotFoundException if the file with the specified fileId is not found
	 */
	@Nonnull
	InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException, UnexpectedIOException;

	/**
	 * Removes file with the specified fileId from the storage.
	 *
	 * @param fileId fileId of the file
	 * @throws FileForFetchNotFoundException if the file with the specified fileId is not found
	 */
	void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException;

	/**
	 * Retrieves the current system status of the EvitaDB server.
	 *
	 * @return the system status of the EvitaDB server
	 */
	@Nonnull
	SystemStatus getSystemStatus();

	/**
	 * Retrieves the current configuration of the EvitaDB server in String format with evaluated value expressions.
	 *
	 * @return the configuration of the EvitaDB server
	 */
	@Nonnull
	String getConfiguration();

}
