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
 * Provides administrative and monitoring operations for the evitaDB instance that are separate from day-to-day data
 * access operations. This contract segregates privileged management functions that require special permissions or
 * are used infrequently for operational tasks like backup/restore, monitoring, and file management.
 *
 * **Purpose**
 *
 * This interface centralizes:
 * - Catalog backup and restore operations (point-in-time and full backups)
 * - Asynchronous task tracking and management (jobs, background operations)
 * - File management for downloadable artifacts (backups, exports)
 * - System health and configuration inspection
 * - Global catalog statistics retrieval
 *
 * **Design Rationale**
 *
 * Management operations are separated from {@link EvitaContract} because they:
 * - May require elevated permissions or access control
 * - Are used primarily by administrators, not application code
 * - Operate at the instance level rather than catalog level
 * - Have different performance characteristics (long-running, resource-intensive)
 *
 * **Access Pattern**
 *
 * Obtain an instance via {@link EvitaContract#management()}:
 * ```
 * EvitaManagementContract management = evita.management();
 * CatalogStatistics[] stats = management.getCatalogStatistics();
 * ```
 *
 * **Thread-Safety**
 *
 * All methods in this interface are thread-safe and can be called concurrently from multiple threads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface EvitaManagementContract {

	/**
	 * Retrieves comprehensive statistics for all catalogs in the evitaDB instance, regardless of their state.
	 * This method provides a global view of the instance's data, including active, inactive, and corrupted catalogs.
	 *
	 * **Use Cases**
	 *
	 * - Monitoring dashboards displaying instance health and capacity
	 * - Administrative tools listing all available catalogs
	 * - Capacity planning by analyzing disk usage and entity counts
	 * - Health checks identifying corrupted or problematic catalogs
	 *
	 * **Performance Characteristics**
	 *
	 * This method aggregates data from all catalogs, which may be expensive for instances with many catalogs.
	 * Results are computed on-demand and not cached.
	 *
	 * @return array of statistics for each catalog, ordered by catalog name; never null but may be empty
	 *         if no catalogs exist
	 */
	@Nonnull
	CatalogStatistics[] getCatalogStatistics();

	/**
	 * Creates a point-in-time backup of the specified catalog as a ZIP archive. This method supports backing up
	 * the catalog at its current state or at any historical moment for which temporal data is still available.
	 *
	 * **Backup Types**
	 *
	 * - **Current backup** (`pastMoment` and `catalogVersion` both null): Captures the latest committed state
	 * - **Point-in-time backup** (`pastMoment` specified): Reconstructs catalog state at a specific moment in history
	 * - **Version-specific backup** (`catalogVersion` specified): Captures exact catalog version (overrides `pastMoment`)
	 *
	 * **WAL Inclusion**
	 *
	 * When `includingWAL` is true, the backup includes the Write-Ahead Log, enabling the restored catalog to:
	 * - Replay recent mutations not yet materialized in snapshots
	 * - Synchronize with replicas that have progressed beyond the backup point
	 * - Recover to the exact transaction state at backup time
	 *
	 * **Asynchronous Execution**
	 *
	 * This method initiates a background task and returns immediately with a future that completes when the backup
	 * file is ready for download. Track progress via {@link #getTaskStatus(UUID)}.
	 *
	 * **Temporal Data Retention**
	 *
	 * Historical backups are only possible while temporal data (WAL entries, version snapshots) remains available.
	 * Data purging policies may limit how far back you can create point-in-time backups.
	 *
	 * @param catalogName    name of the catalog to backup
	 * @param pastMoment     timestamp for point-in-time backup, or null for current state
	 * @param catalogVersion specific catalog version to backup, or null for latest; when specified, `pastMoment` is ignored
	 * @param includingWAL   true to include Write-Ahead Log in the backup for precise recovery
	 * @return future that completes with {@link FileForFetch} descriptor when backup is ready for download
	 * @throws TemporalDataNotAvailableException when requested historical data has been purged or never existed
	 */
	@Nonnull
	CompletableFuture<FileForFetch> backupCatalog(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a comprehensive full backup of the specified catalog, capturing all persistent storage artifacts
	 * including historical data, Write-Ahead Logs, and version snapshots. Unlike {@link #backupCatalog}, which
	 * creates a minimal point-in-time snapshot, a full backup preserves the complete catalog history.
	 *
	 * **Full Backup Contents**
	 *
	 * - All entity collection data files
	 * - Complete Write-Ahead Log (WAL) history
	 * - Catalog header and metadata files
	 * - Version snapshots and temporal data
	 * - Index structures and materialized views
	 *
	 * **Use Cases**
	 *
	 * - Disaster recovery requiring complete history reconstruction
	 * - Catalog migration to a different evitaDB instance
	 * - Archival storage of entire catalog including temporal capabilities
	 * - Creating a source catalog for future point-in-time backups
	 *
	 * **Restore Capabilities**
	 *
	 * Catalogs restored from full backups retain:
	 * - Ability to query historical states (if temporal data is included)
	 * - Ability to create point-in-time backups from any version
	 * - Complete WAL for synchronization or analysis
	 *
	 * **Performance Characteristics**
	 *
	 * Full backups are significantly larger and slower than point-in-time backups. They capture the entire on-disk
	 * footprint of the catalog, which may include extensive temporal history.
	 *
	 * @param catalogName name of the catalog to backup
	 * @return future that completes with {@link FileForFetch} descriptor when full backup is ready for download
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
	 * Retrieves paginated list of background task statuses for monitoring long-running operations. Tasks represent
	 * asynchronous operations like catalog backups, restores, migrations, and other resource-intensive activities.
	 *
	 * **Filtering Options**
	 *
	 * - `taskType`: Limits results to specific task types (e.g., "backup", "restore", "duplication")
	 * - `states`: Filters by execution state (QUEUED, RUNNING, FINISHED, FAILED)
	 *
	 * **Task Lifecycle**
	 *
	 * Tasks progress through states: QUEUED → RUNNING → FINISHED/FAILED. Completed tasks remain queryable
	 * for a retention period before being purged from history.
	 *
	 * **Use Cases**
	 *
	 * - Monitoring dashboards displaying active and recent operations
	 * - Polling for completion of initiated tasks
	 * - Auditing completed operations
	 * - Troubleshooting failed tasks
	 *
	 * @param page     page number, 1-based
	 * @param pageSize number of task statuses per page
	 * @param taskType optional array of task type identifiers to filter by, or null for all types
	 * @param states   simplified states to include in results; empty array means all states
	 * @return paginated list of task statuses matching filters, ordered by start time descending (most recent first)
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
	 * Retrieves comprehensive system-level status information for the evitaDB instance, including health indicators,
	 * resource utilization, uptime, and version information.
	 *
	 * **Use Cases**
	 *
	 * - Health check endpoints for load balancers and monitoring systems
	 * - Operational dashboards displaying instance vitals
	 * - Diagnostic information collection for troubleshooting
	 *
	 * **Thread-Safety**
	 *
	 * This method computes current status on-demand and is thread-safe. Results represent a snapshot and may
	 * become stale immediately.
	 *
	 * @return current system status including health, uptime, version, and resource metrics
	 */
	@Nonnull
	SystemStatus getSystemStatus();

	/**
	 * Retrieves the effective runtime configuration of the evitaDB instance as a formatted string. All configuration
	 * value expressions (environment variables, system properties) are evaluated and replaced with their actual values.
	 *
	 * **Use Cases**
	 *
	 * - Verifying active configuration in production environments
	 * - Debugging configuration issues by inspecting resolved values
	 * - Auditing security settings and resource limits
	 * - Documenting actual runtime parameters
	 *
	 * **Security Considerations**
	 *
	 * Returned configuration may contain sensitive information (credentials, API keys, connection strings).
	 * Access to this method should be restricted to authorized administrators.
	 *
	 * @return formatted configuration string with all placeholders resolved to actual values
	 */
	@Nonnull
	String getConfiguration();

}
