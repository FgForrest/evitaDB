/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.exception.CatalogNotAliveException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
import io.evitadb.dataType.PaginatedList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Represents an isolated data container within an evitaDB instance, analogous to a database schema in relational systems
 * or a database in MongoDB/Elasticsearch. A catalog provides complete isolation of data, schema, and sessions, functioning
 * as a self-contained unit for storing and querying entities.
 *
 * **Architecture and Isolation**
 *
 * Each catalog maintains:
 * - **Independent schema** ({@link CatalogSchemaContract}): Defines catalog-level settings and entity type schemas
 * - **Isolated entity collections** ({@link EntityCollectionContract}): Stores entities grouped by type
 * - **Dedicated sessions** ({@link EvitaSessionContract}): Sessions are bound to a single catalog and cannot cross boundaries
 * - **Separate transaction log** (WAL): Tracks all mutations for replication and recovery
 * - **Versioned state**: Each mutation increments the catalog version for temporal queries and point-in-time operations
 *
 * **Typical Usage Pattern**
 *
 * In e-commerce scenarios, a catalog typically represents:
 * - A single online store (product catalog, customers, orders)
 * - A geographical market (e.g., US catalog vs. EU catalog)
 * - A tenant in multi-tenant SaaS deployments
 *
 * **State Management**
 *
 * Catalogs transition through different operational states ({@link CatalogState}):
 * - {@link CatalogState#WARMING_UP}: Initial bulk loading phase, single-threaded, no transactions
 * - {@link CatalogState#ALIVE}: Normal operation with full ACID transactions and concurrent access
 * - {@link CatalogState#INACTIVE}: Exists on disk but not loaded in memory
 * - {@link CatalogState#CORRUPTED}: Cannot be loaded due to data integrity issues
 *
 * **Entity Collections**
 *
 * A catalog manages multiple {@link EntityCollectionContract} instances, each representing a distinct entity type
 * (comparable to tables in SQL or document types in NoSQL). Collections are identified by
 * {@link EntityCollectionContract#getEntityType()} and can be created dynamically based on schema evolution settings.
 *
 * **Temporal Capabilities**
 *
 * Catalogs support:
 * - Point-in-time queries via version-based snapshots
 * - Historical backups at specific moments or versions
 * - Change data capture (CDC) for streaming mutations
 * - Version history navigation for auditing and rollback scenarios
 *
 * **Concurrency and Transactions**
 *
 * - Read operations are lock-free and use MVCC (Multi-Version Concurrency Control)
 * - Write operations are serialized through transactions in {@link CatalogState#ALIVE} state
 * - Bulk loading in {@link CatalogState#WARMING_UP} bypasses transactional overhead for performance
 *
 * **Lifecycle Operations**
 *
 * - Creation: {@link EvitaContract#defineCatalog(String)}
 * - State transitions: {@link #goLive()}, {@link EvitaContract#activateCatalog(String)}
 * - Duplication: {@link #duplicateTo(String)}
 * - Backup/Restore: {@link #backup}, {@link #fullBackup}
 * - Deletion: {@link #terminateAndDelete()}
 *
 * **Thread-Safety**
 *
 * Catalog implementations are thread-safe. Multiple sessions can operate concurrently in {@link CatalogState#ALIVE} state.
 * The {@link CatalogState#WARMING_UP} state requires single-threaded access.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface CatalogContract {

	/**
	 * Returns the immutable unique identifier assigned to this catalog at creation time. This UUID remains constant
	 * throughout the catalog's lifetime, even if the catalog is renamed or its schema evolves.
	 *
	 * **Use Cases**
	 *
	 * - Stable reference for catalog identification across renames
	 * - Primary key for external systems tracking evitaDB catalogs
	 * - Correlation key in distributed systems or replicas
	 *
	 * @return persistent unique identifier for this catalog
	 */
	@Nonnull
	UUID getCatalogId();

	/**
	 * Returns the current sealed (read-only) schema of the catalog, which defines catalog-level settings and
	 * contains definitions for all entity types managed by this catalog.
	 *
	 * **Schema Evolution**
	 *
	 * To modify the schema, use {@link #updateSchema} with appropriate {@link LocalCatalogSchemaMutation} instances.
	 * The schema can be converted to a builder via {@link SealedCatalogSchema#openForWrite()} to generate mutations.
	 *
	 * **Thread-Safety**
	 *
	 * The returned schema is immutable and thread-safe. However, it represents a snapshot at call time and may
	 * become stale if concurrent schema mutations occur.
	 *
	 * @return current sealed catalog schema
	 */
	@Nonnull
	SealedCatalogSchema getSchema();

	/**
	 * Updates the internal schema based on the provided schema mutations. This method processes a series of
	 * {@link LocalCatalogSchemaMutation} objects to modify the current catalog schema, handling different types
	 * of schema mutations including entity schema creation, removal, modification, and renaming. It registers
	 * mutations with an active transaction if available, applies schema modifications, and ensures persistence
	 * and proper rollback in the event of an error.
	 *
	 * @param sessionId      an optional session ID that can be used to register the mutations with an active session
	 * @param schemaMutation an array of {@link LocalCatalogSchemaMutation} objects that specify the mutations
	 *                       to apply to the catalog schema
	 * @return the updated {@link SealedCatalogSchema} reflecting all applied mutations
	 */
	@Nonnull
	SealedCatalogSchema updateSchema(
		@Nonnull EvitaContract evita,
		@Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutation
	) throws SchemaAlteringException;

	/**
	 * Returns the current operational state of this catalog instance. The state determines what operations are
	 * permitted and what concurrency guarantees apply.
	 *
	 * **State Implications**
	 *
	 * - {@link CatalogState#WARMING_UP}: Single-threaded bulk loading, no transactions
	 * - {@link CatalogState#ALIVE}: Normal operation with full transactional support
	 * - {@link CatalogState#INACTIVE}: Catalog exists on disk but is not loaded
	 * - {@link CatalogState#CORRUPTED}: Catalog is unusable due to data corruption
	 * - Transitional states: Catalog is changing state and cannot serve requests
	 *
	 * @return current catalog state
	 * @see CatalogState for detailed state descriptions
	 */
	@Nonnull
	CatalogState getCatalogState();

	/**
	 * Returns the unique name of this catalog within the evitaDB instance. This name is used in all API calls
	 * to identify the catalog and must be unique across all catalogs in the same {@link EvitaContract}.
	 *
	 * **Schema Consistency**
	 *
	 * The catalog name always matches {@link CatalogSchemaContract#getName()}. Renaming operations update both
	 * the catalog instance and its schema atomically.
	 *
	 * @return unique catalog name
	 */
	@Nonnull
	String getName();

	/**
	 * Returns the current catalog version number, which is incremented with each mutation applied to the catalog
	 * or its entity collections. This version is used for:
	 *
	 * - Optimistic locking and conflict detection
	 * - Temporal queries and point-in-time snapshots
	 * - Change data capture (CDC) stream positioning
	 * - Determining whether in-memory state needs persistence
	 *
	 * **Version Semantics**
	 *
	 * - Starts at 0 for new catalogs
	 * - Incremented by 1 for each committed transaction
	 * - Not persisted to disk; computed from WAL on startup
	 * - Used to track header changes requiring disk sync
	 *
	 * @return current catalog version number
	 */
	long getVersion();

	/**
	 * Indicates whether this catalog currently supports transactional operations. Transaction support depends on
	 * the catalog's state:
	 *
	 * - {@link CatalogState#ALIVE}: Returns true, full ACID transactions available
	 * - {@link CatalogState#WARMING_UP}: Returns false, single-threaded bulk operations only
	 * - Other states: Returns false, no write operations permitted
	 *
	 * **Use Cases**
	 *
	 * - Conditional logic for bulk loading vs. transactional updates
	 * - Validation before attempting transactional operations
	 * - Monitoring catalog readiness for production workloads
	 *
	 * @return true if catalog accepts transactional writes, false otherwise
	 */
	boolean supportsTransaction();

	/**
	 * Returns set of all maintained {@link EntityCollectionContract} - i.e. entity types.
	 */
	@Nonnull
	Set<String> getEntityTypes();

	/**
	 * Method returns a response containing entities that match passed `evitaRequest`. This is universal method for
	 * accessing multiple entities from the collection in a paginated fashion in requested form of completeness.
	 *
	 * The method is used to locate entities of up-front unknown type by their globally unique attributes.
	 */
	@Nonnull
	<S extends Serializable, T extends EvitaResponse<S>> T getEntities(
		@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Applies mutation to the catalog. This is a generic method that accepts any mutation and tries to apply it to
	 * the catalog. If the mutation is not applicable to the catalog, exception is thrown.
	 *
	 * @param session  session that is applying the mutation
	 * @param mutation mutation to be applied
	 * @throws InvalidMutationException when mutation is not applicable to the catalog
	 */
	void applyMutation(
		@Nonnull EvitaSessionContract session, @Nonnull CatalogBoundMutation mutation) throws InvalidMutationException;

	/**
	 * Returns collection maintaining all entities of same type.
	 *
	 * @param entityType type (name) of the entity
	 */
	@Nonnull
	Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType);

	/**
	 * Returns collection maintaining all entities of same type or throws standardized exception.
	 *
	 * @param entityType type (name) of the entity
	 */
	@Nonnull
	EntityCollectionContract getCollectionForEntityOrThrowException(
		@Nonnull String entityType
	) throws CollectionNotFoundException;

	/**
	 * Returns collection maintaining all entities of same type or throws standardized exception.
	 *
	 * @param entityTypePrimaryKey primary key of the entity collection - see {@link EntityCollectionContract#getEntityTypePrimaryKey()}
	 */
	@Nonnull
	EntityCollectionContract getCollectionForEntityPrimaryKeyOrThrowException(
		int entityTypePrimaryKey
	) throws CollectionNotFoundException;

	/**
	 * Returns collection maintaining all entities of same type. If no such collection exists new one is created.
	 *
	 * @param entityType type (name) of the entity
	 * @throws SchemaAlteringException when collection doesn't exist and {@link CatalogSchemaContract#getCatalogEvolutionMode()}
	 *                                 doesn't allow {@link CatalogEvolutionMode#ADDING_ENTITY_TYPES}
	 */
	@Nonnull
	EntityCollectionContract getOrCreateCollectionForEntity(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType)
		throws SchemaAlteringException;

	/**
	 * Removes entire catalog storage from persistent storage and closes the catalog instance.
	 */
	void terminateAndDelete();

	/**
	 * Replaces folder of the `catalogToBeReplaced` with contents of this catalog.
	 */
	@Nonnull
	ProgressingFuture<CatalogContract> replace(
		@Nonnull CatalogSchemaContract updatedSchema,
		@Nullable CatalogContract catalogToBeReplaced
	);

	/**
	 * Returns map with current {@link EntitySchemaContract entity schema} instances indexed by their
	 * {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current {@link EntitySchemaContract entity schema} instances indexed by their name
	 */
	@Nonnull
	Map<String, EntitySchemaContract> getEntitySchemaIndex();

	/**
	 * Returns {@link EntitySchemaContract} for passed `entityType` or throws {@link IllegalArgumentException} if schema for
	 * this type is not yet known.
	 */
	@Nonnull
	Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType);

	/**
	 * Determines whether the entity or process is going live. When the catalog is in the process of going live,
	 * no sessions should be created or mutations applied to the catalog.
	 *
	 * @return true if the entity or process is going live, otherwise false.
	 */
	boolean isGoingLive();

	/**
	 * Transitions the catalog from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}, enabling
	 * transactional operations and concurrent access. This method performs necessary initialization steps to
	 * prepare the catalog for production workloads.
	 *
	 * **Transition Process**
	 *
	 * 1. Validates catalog integrity and indexes
	 * 2. Converts internal data structures from bulk-loading to transactional mode
	 * 3. Initializes transaction management infrastructure
	 * 4. Enables concurrent access mechanisms (MVCC, locks)
	 * 5. Sets state to {@link CatalogState#ALIVE}
	 *
	 * **When to Call**
	 *
	 * Call this method after completing initial bulk data loading in {@link CatalogState#WARMING_UP} and before
	 * opening the catalog for normal application use. Only valid when current state is {@link CatalogState#WARMING_UP}.
	 *
	 * **Performance Impact**
	 *
	 * This operation may take significant time for large catalogs as it builds transactional metadata and
	 * finalizes indexes. The catalog is unavailable during transition.
	 *
	 * @return this catalog instance in {@link CatalogState#ALIVE} state
	 * @throws IllegalStateException if current state is not {@link CatalogState#WARMING_UP}
	 * @see CatalogState for state lifecycle details
	 */
	@Nonnull
	CatalogContract goLive();

	/**
	 * Creates new publisher that emits {@link ChangeCatalogCapture}s that match the request. Change catalog capture
	 * operates on WAL (Write Ahead Log) and can be enabled only when the catalog is in {@link CatalogState#ALIVE} state.
	 *
	 * @param request defines what events are captured
	 * @return publisher that emits {@link ChangeCatalogCapture}s that match the request
	 * @throws CatalogNotAliveException when the catalog is not in {@link CatalogState#ALIVE} state
	 */
	@Nonnull
	ChangeCapturePublisher<ChangeCatalogCapture> registerChangeCatalogCapture(
		@Nonnull ChangeCatalogCaptureRequest request
	)
		throws CatalogNotAliveException;

	/**
	 * Method checks whether there are new records in the WAL that haven't been incorporated into the catalog yet and
	 * processes them. The method returns completable future that is completed when all records are processed.
	 */
	void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalog);

	/**
	 * Returns information about the version that was valid at the specified moment in time. If the moment is not
	 * specified method returns the first version known to the catalog mutation history.
	 *
	 * @param moment the moment in time for which the catalog version should be returned
	 * @return the catalog version that was valid at the specified moment, or the first version known to the catalog
	 * mutation history if no moment was specified
	 * @throws TemporalDataNotAvailableException when data for the particular moment is not available anymore
	 */
	@Nonnull
	MaterializedVersionBlock getFirstCatalogVersionAfter(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException;

	/**
	 * Returns information about the last version that was valid before the specified moment in time. If the moment is
	 * not specified method returns the first version known to the catalog mutation history.
	 *
	 * @param moment the moment in time for which the last catalog version should be returned
	 * @return the catalog version that was valid at the specified moment, or the first version known to the catalog
	 * mutation history if no moment was specified
	 * @throws TemporalDataNotAvailableException when data for the particular moment is not available anymore
	 */
	@Nonnull
	MaterializedVersionBlock getLastCatalogVersionBefore(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException;

	/**
	 * Returns a paginated list of catalog versions based on the provided time flow, page number, and page size.
	 * It returns only versions that are known in history - there may be a lot of other versions for which we don't have
	 * information anymore, because the data were purged to save space.
	 *
	 * @param timeFlow the time flow used to filter the catalog versions
	 * @param page     the page number of the paginated list
	 * @param pageSize the number of versions per page
	 * @return a paginated list of {@link MaterializedVersionBlock} instances
	 */
	@Nonnull
	PaginatedList<MaterializedVersionBlock> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize);

	/**
	 * Returns a stream of {@link WriteAheadLogVersionDescriptor} instances for the given catalog versions. Descriptors will
	 * be ordered the same way as the input catalog versions, but may be missing some versions if they are not known in
	 * history. Creating a descriptor could be an expensive operation, so it's recommended to stream changes to clients
	 * gradually as the stream provides the data.
	 *
	 * @param catalogVersion the catalog versions to get descriptors for
	 * @return a list of {@link WriteAheadLogVersionDescriptor} instances
	 */
	@Nonnull
	List<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param catalogVersion version of the catalog to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param catalogVersion version of the catalog to start the stream with, if null is provided the stream will start
	 *                       with the last committed transaction
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion);

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param pastMoment     leave null for creating backup for actual dataset, or specify past moment to create backup for
	 *                       the dataset as it was at that moment
	 * @param catalogVersion precise catalog version to create backup for, or null to create backup for the latest version,
	 *                       when set not null, the pastMoment parameter is ignored
	 * @param includingWAL   if true, the backup will include the Write-Ahead Log (WAL) file and when the catalog is
	 *                       restored, it'll replay the WAL contents locally to bring the catalog to the current state
	 * @param onStart        callback that will be executed before the backup process starts
	 * @param onComplete     callback that will be executed when the backup process is completed
	 * @return jobId of the backup process
	 * @throws TemporalDataNotAvailableException when the past data is not available
	 */
	@Nonnull
	ServerTask<?, FileForFetch> backup(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a full backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 * Full backup includes all data files, WAL files, and the catalog header file from the catalog storage.
	 * After restoring catalog from the full backup, the catalog will contain all the data - so you should be able to
	 * create even point-in-time backups from it.
	 *
	 * @param onStart    callback that will be executed before the backup process starts
	 * @param onComplete callback that will be executed when the backup process is completed
	 * @return jobId of the backup process
	 */
	@Nonnull
	ServerTask<?, FileForFetch> fullBackup(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	);

	/**
	 * Duplicates the current catalog to another catalog with the specified name.
	 *
	 * @param targetCatalogName the name of the target catalog to which the current catalog will be duplicated
	 * @return a future that will be completed when the duplication is finished
	 */
	@Nonnull
	ProgressingFuture<Void> duplicateTo(@Nonnull String targetCatalogName);

	/**
	 * Returns catalog statistics aggregating basic information about the catalog and the data stored in it.
	 *
	 * @return catalog statistics
	 */
	@Nonnull
	CatalogStatistics getStatistics();

	/**
	 * Terminates catalog instance and frees all claimed resources. Prepares catalog instance to be garbage collected.
	 *
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	void terminate();

	/**
	 * Indicates whether the process or operation has been terminated.
	 *
	 * @return true if the process or operation is terminated, false otherwise.
	 */
	boolean isTerminated();
}
