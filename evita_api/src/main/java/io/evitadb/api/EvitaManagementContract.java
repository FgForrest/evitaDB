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

import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TaskNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.UnexpectedIOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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
 * SystemStatus status = management.getSystemStatus();
 * ```
 *
 * **Catalog statistics** are component-selected: a caller names the components it needs instead of paying for every
 * statistic of every catalog on every call. Three entry points, by how much is being asked about -
 * {@link #getCatalogStatistics(String, Set)} for one catalog, {@link #getAllCatalogStatistics(Set)} for the whole
 * instance, and {@link #getEntityCollectionStatistics(String, String, Set)} for one collection. They are the remote
 * face of {@link CatalogContract#getStatistics(Set)} and {@link EntityCollectionContract#getStatistics(Set)}, which
 * answer the same questions when the engine is embedded.
 *
 * **Thread-Safety**
 *
 * All methods in this interface are thread-safe and can be called concurrently from multiple threads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface EvitaManagementContract {

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

	/**
	 * Retrieves the curated subset of the engine configuration that clients need in order to reason
	 * about the behaviour of the server they talk to - most notably the engine-wide default
	 * conflict resolution applied when neither the catalog schema nor the entity schema declares
	 * its own.
	 *
	 * **Use Cases**
	 *
	 * - Resolving the effective conflict resolution for an entity type on the client side via
	 *   `EffectiveConflictResolutionResolver`, which needs the engine default as the base of its
	 *   precedence walk
	 * - Presenting the active engine behaviour in administration tools
	 *
	 * **Relation to {@link #getConfiguration()}**
	 *
	 * This method is not a replacement for {@link #getConfiguration()} - it exposes no sensitive
	 * values (paths, credentials) and is therefore unrestricted and readable **even when the engine
	 * runs in read-only mode**, where {@link #getConfiguration()} refuses to answer.
	 *
	 * **Thread-Safety**
	 *
	 * The returned values originate from the immutable configuration and are constant for the
	 * lifetime of the server process, so the result may safely be cached by the caller until it
	 * reconnects.
	 *
	 * @return the exposed subset of the engine configuration
	 */
	@Nonnull
	EngineSettings getEngineSettings();

	/**
	 * Returns a component-selected statistics snapshot of a single catalog.
	 *
	 * The caller names the {@link CatalogStatisticsComponent}s it wants and the engine computes only those, so a
	 * management screen refreshing on a timer pays for what it displays and nothing else. Every requested component
	 * gets an entry in {@link CatalogStatistics#componentStatus()} saying whether it was delivered and, if not, why -
	 * a component that was never requested has no entry at all, which is what lets a caller tell the two apart.
	 * {@link CatalogStatisticsComponent#IDENTITY} is delivered whether or not it was requested.
	 *
	 * **Aggregates only.** No component reported here breaks down per collection, so the size of the answer does not
	 * grow with the number of collections in the catalog. Statistics of one collection are fetched by naming it - see
	 * {@link #getEntityCollectionStatistics(String, String, Set)} - and the two are independent snapshots that may
	 * observe different catalog versions.
	 *
	 * **A corrupted catalog still answers.** It reports its identity, the components that read the file system
	 * directly, and {@link ComponentAvailability#CATALOG_UNUSABLE} for the rest, rather than failing the call.
	 *
	 * @param catalogName name of the catalog to describe
	 * @param components  the components to compute; at least one must be named
	 * @return the snapshot, carrying the requested components and the status of each
	 * @throws CatalogNotFoundException   when no catalog of that name exists
	 * @throws EvitaInvalidUsageException when no component is requested
	 */
	@Nonnull
	CatalogStatistics getCatalogStatistics(
		@Nonnull String catalogName,
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws CatalogNotFoundException, EvitaInvalidUsageException;

	/**
	 * Returns a component-selected statistics snapshot of every catalog known to this instance, ordered by catalog
	 * name. The instance-wide form of {@link #getCatalogStatistics(String, Set)}, and the replacement for the
	 * statistics call that used to compute everything for every catalog on every invocation.
	 *
	 * Corrupted catalogs are included rather than skipped - a catalog missing from the answer would be
	 * indistinguishable from a catalog that no longer exists, and a corrupted catalog is exactly what an operator
	 * opens this call to find.
	 *
	 * Everything this call returns is multiplied by the number of catalogs, so components are weighed here on
	 * **payload as much as on compute time**. The selection is opt-in and every component defined today is admitted -
	 * {@link CatalogStatisticsComponent#INDEX_CARDINALITY} included, because what it reports here is the catalog
	 * index's global unique indexes, a handful of `O(1)` counter readings whose listing stays in the same size class
	 * as {@link CatalogStatisticsComponent#COLLECTIONS}, and never the far more expensive per-collection form.
	 *
	 * @param components the components to compute for each catalog; at least one must be named
	 * @return one snapshot per catalog, ordered by catalog name
	 * @throws EvitaInvalidUsageException when nothing is requested
	 */
	@Nonnull
	Collection<CatalogStatistics> getAllCatalogStatistics(
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws EvitaInvalidUsageException;

	/**
	 * Returns a component-selected statistics snapshot of a single entity collection.
	 *
	 * This is the only way to obtain per-collection numbers: {@link #getCatalogStatistics(String, Set)} reports
	 * catalog-wide aggregates and never breaks them down by collection. The presence rules are the same as there, and
	 * the two responses are independent snapshots - compare {@link CatalogIdentity#catalogVersion()} of each when
	 * that matters.
	 *
	 * @param catalogName name of the catalog holding the collection
	 * @param entityType  name of the entity collection to describe
	 * @param components  the components to compute; every one of them must satisfy
	 *                    {@link CatalogStatisticsComponent#isCollectionLevel()}
	 * @return the snapshot, carrying the requested components and the status of each
	 * @throws CatalogNotFoundException    when no catalog of that name exists
	 * @throws CollectionNotFoundException when the catalog holds no collection of that entity type; an empty response
	 *                                     would be indistinguishable from an empty collection
	 * @throws EvitaInvalidUsageException  when a component that has no collection-level form is requested
	 */
	@Nonnull
	EntityCollectionStatistics getEntityCollectionStatistics(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws CatalogNotFoundException, CollectionNotFoundException, EvitaInvalidUsageException;

	/**
	 * Returns one page of the indexes held by a single entity collection, or of those the catalog holds itself,
	 * filtered and ordered as asked.
	 *
	 * Where {@link CatalogStatisticsComponent#INDEX_SUMMARY} counts a collection's indexes by kind and scope, this
	 * enumerates them individually. It is the drill-down that follows an alarming count: forty thousand
	 * `REFERENCED_ENTITY` indexes say something is wrong, and only a listing says which reference caused it.
	 *
	 * **`entityType` chooses the owner, and that is the only difference between the two.** Naming a collection browses
	 * its indexes; passing null browses the catalog's own - the globally-unique attribute index there is one of per
	 * scope. Both answer with the same rows under the same criteria, so a client renders one table and holds one code
	 * path. A catalog index carries no entity-index kind and no reference, so criteria naming either select none of
	 * them; see {@link BrowsedIndex}.
	 *
	 * **Never poll the collection form.** Every call walks the collection's whole index map - `O(indexes)`, unavoidably,
	 * because there is no per-kind index of the indexes to consult and building one would duplicate every key while
	 * still costing a full pass to order. Filters and ordering change the constant, not the growth. Paging keeps the
	 * *answer* small, not the work behind it, so this belongs in the same explicitly-requested category as
	 * {@link CatalogStatisticsComponent#INDEX_CARDINALITY} rather than on a refresh timer. The catalog form is bounded
	 * by the number of scopes and carries none of that cost.
	 *
	 * @param catalogName name of the catalog holding the indexes
	 * @param entityType  name of the entity collection whose indexes to browse, or null to browse the indexes the
	 *                    catalog holds itself
	 * @param criteria    which indexes to select, in what order, and which page of them to return
	 * @return the requested page, the number of indexes that matched, and the catalog version it was read at
	 * @throws CatalogNotFoundException    when no catalog of that name exists
	 * @throws CollectionNotFoundException when an entity type is named and the catalog holds no such collection; an
	 *                                     empty page would be indistinguishable from a collection holding no indexes
	 * @throws EvitaInvalidUsageException  when the criteria name a reference the entity schema does not declare. Only
	 *                                     the collection form can raise this - a catalog browse has no entity schema to
	 *                                     validate against, and answers a reference filter with an empty page
	 */
	@Nonnull
	IndexBrowseResult browseIndexes(
		@Nonnull String catalogName,
		@Nullable String entityType,
		@Nonnull IndexBrowseCriteria criteria
	) throws CatalogNotFoundException, CollectionNotFoundException, EvitaInvalidUsageException;

	/**
	 * Describes one index in full - what it occupies on the heap, and how well it discriminates.
	 *
	 * The drill-down that follows {@link #browseIndexes(String, String, IndexBrowseCriteria)}: that lists an owner's
	 * indexes cheaply, this measures one of them. Hand the same `entityType` back together with the
	 * {@link BrowsedIndex#indexPrimaryKey()} of the row that looked worth investigating - the two together are the
	 * index's identity, since the same handle under another owner is another index.
	 *
	 * **The caller names the index, and that is what bounds the cost.** Estimating an index's heap walks its contents,
	 * which no cache can amortise - a measured warm second pass came back slower than the cold one. On a production
	 * catalog the largest single index took 151 ms and the median took ~4 µs, so one named index is affordable while
	 * sweeping a collection of a quarter of a million of them is not. There is deliberately no call that measures a
	 * whole collection: a caller who wants a total issues these in parallel and sums them, which keeps the cost
	 * visible to whoever chose to pay it.
	 *
	 * @param catalogName     name of the catalog holding the index
	 * @param entityType      name of the entity collection holding the index, or null when the catalog holds it itself
	 * @param indexPrimaryKey identity of the index to describe, as reported by {@link BrowsedIndex#indexPrimaryKey()}
	 * @return the full description of that one index
	 * @throws CatalogNotFoundException    when no catalog of that name exists
	 * @throws CollectionNotFoundException when an entity type is named and the catalog holds no such collection
	 * @throws IndexNotFoundException      when that owner holds no index under that handle. A collection's index can be
	 *                                     reclaimed between the browse and the drill-down, and a catalog's is created
	 *                                     lazily per scope, so this is an ordinary outcome rather than necessarily a
	 *                                     mistake - but it can never mean the handle now denotes a *different* index
	 */
	@Nonnull
	IndexDetail getIndexDetail(
		@Nonnull String catalogName,
		@Nullable String entityType,
		int indexPrimaryKey
	) throws CatalogNotFoundException, CollectionNotFoundException, IndexNotFoundException;

	/**
	 * Returns how often each schema capability of one owner was asked for by queries, against how often mutations had
	 * to maintain it - the *"you never filter by EAN, so why are you paying to keep its filter index up to date?"*
	 * reading.
	 *
	 * Where {@link #browseIndexes(String, String, IndexBrowseCriteria)} enumerates the *physical* indexes and what each
	 * of them costs, this reports the *schema flags* those indexes exist to serve. That is the granularity an operator
	 * can act on, since dropping a flag is one schema mutation that removes every index maintaining it at once - and it
	 * is why the two are separate surfaces rather than extra columns on a browse row. Read
	 * {@link SchemaCapabilityUsageSnapshot} before acting on either count; in particular the request count is **not**
	 * physical index usage and must never be presented as such.
	 *
	 * **`entityType` chooses the owner, exactly as it does for an index browse.** Naming a collection reports what its
	 * schema declares; passing null reports what the catalog schema declares itself - the capabilities of its
	 * globally-unique attributes, which live there because a query filtering by one may name no collection at all. A
	 * client wanting the whole picture issues one call per owner and concatenates the rows, which is unambiguous
	 * because every row names its owner.
	 *
	 * **This one is cheap, and may be polled.** The response is bounded by the schema - dozens of rows per owner -
	 * rather than by the data, and producing it walks a map of that size; there is no index walk behind it and
	 * therefore no reason for the paging, filtering and ordering an index browse needs.
	 *
	 * @param catalogName name of the catalog holding the schema
	 * @param entityType  name of the entity collection whose capabilities to report, or null to report the ones the
	 *                    catalog schema declares itself
	 * @return one row per observed capability, empty when nothing has been observed since the catalog was loaded
	 * @throws CatalogNotFoundException    when no catalog of that name exists
	 * @throws CollectionNotFoundException when an entity type is named and the catalog holds no such collection; an
	 *                                     empty list would be indistinguishable from a collection nothing has queried
	 */
	@Nonnull
	List<SchemaCapabilityUsageSnapshot> listCapabilityUsage(
		@Nonnull String catalogName,
		@Nullable String entityType
	) throws CatalogNotFoundException, CollectionNotFoundException;

}
