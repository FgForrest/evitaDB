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

package io.evitadb.core.catalog;

import com.carrotsearch.hppc.ObjectObjectIdentityHashMap;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import io.evitadb.api.CatalogVersionPin;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionRecordCounts;
import io.evitadb.api.statistics.CollectionsInfo;
import io.evitadb.api.statistics.CollectionsInfo.CollectionInfo;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.IndexSummaryStatistics;
import io.evitadb.api.statistics.RecordCounts;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CatalogNotAliveException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.ConcurrentSchemaUpdateException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.SchemaNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
import io.evitadb.comparator.CollationKeyCache;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.collection.EntityCollection.EntityCollectionHeaderWithCollection;
import io.evitadb.core.exception.StorageImplementationNotFoundException;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.expression.trigger.FacetExpressionTriggerFactory;
import io.evitadb.core.expression.trigger.HistogramExpressionTriggerFactory;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingEngine.MutationApplicationRecord;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.TransactionManager.ProcessResult;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.set.LazyHashSet;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.usage.SchemaCapabilityUsageProjection;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.store.catalog.exception.PersistenceServiceClosed;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.header.model.CollectionReference;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogFragmentationSnapshot;
import io.evitadb.spi.store.catalog.persistence.CatalogHandoverFailedException;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.DurabilitySnapshot;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory.CatalogFolderAllocator;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceServiceFactory.FileIdCarrier;
import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;
import io.evitadb.spi.store.catalog.persistence.CatalogStoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.VolatileDataFootprint;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * {@inheritDoc}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@ThreadSafe
public final class Catalog
	implements CatalogContract,
	CatalogConsumersListener,
	TransactionalLayerProducer<DataStoreChanges, Catalog>,
	EntityTypeClassifierResolver
{
	/**
	 * Per-thread stack of batch frames collecting entity types whose expression trigger registry
	 * must be rebuilt once the current `updateSchema(...)` batch finishes. While a frame is on
	 * the stack, `entitySchemaUpdated` / `entitySchemaRemoved` append the affected entity type to
	 * the topmost frame instead of rebuilding immediately — cross-entity trigger validation
	 * (e.g. histogram value expressions referencing attributes on other entities) thus only sees
	 * the final, consistent schema at the end of the batch.
	 *
	 * Nesting is supported: the recursive `updateSchema` call at the end of `updateSchema` (which
	 * applies cascading `ModifyEntitySchemaMutation`s) pushes its own frame and drains it before
	 * returning, so outer / inner frames never interfere.
	 */
	private static final ThreadLocal<Deque<Set<String>>> PENDING_TRIGGER_REBUILDS =
		ThreadLocal.withInitial(ArrayDeque::new);
	/**
	 * The answer for a catalog no session has ever been opened against - its session registry is created lazily and
	 * therefore does not exist yet. Three zeroes is the truthful reading of that state, not a missing measurement.
	 */
	private static final SessionStatistics NO_ACTIVE_SESSIONS = new SessionStatistics(0, 0, 0);

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains information about version of the catalog which corresponds to transaction commit sequence number.
	 */
	private final TransactionalReference<Long> versionId;
	/**
	 * CatalogIndex factory implementation.
	 */
	@Getter
	private final IndexMaintainer<CatalogIndexKey, CatalogIndex> catalogIndexMaintainer = new CatalogIndexMaintainerImpl();
	/**
	 * Memory store for catalogs.
	 */
	private final TransactionalMap<String, EntityCollection> entityCollections;
	/**
	 * Contains index of {@link EntityCollection} indexed by their primary keys.
	 */
	private final TransactionalMap<Integer, EntityCollection> entityCollectionsByPrimaryKey;
	/**
	 * Contains index of {@link EntitySchemaContract} indexed by their {@link EntitySchemaContract#getName()}.
	 */
	private final TransactionalMap<String, EntitySchemaContract> entitySchemaIndex;
	/**
	 * Service containing I/O related methods.
	 *
	 * Exposed so that a test driving a real engine can reach the storage layer's own seams - forcing an owed
	 * checkpoint, draining the write-ahead log - instead of polling the filesystem until the background work
	 * happens to have run. Nothing in production reads it through the getter; every engine path that needs the
	 * persistence service already holds it directly.
	 */
	@Getter
	private final CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> persistenceService;
	/**
	 * This instance is used to cover changes in transactional memory and persistent storage reference.
	 *
	 * @see TransactionalDataStoreMemoryBuffer documentation
	 */
	private final DataStoreMemoryBuffer dataStoreBuffer;
	/**
	 * This field contains flag with TRUE value if catalog is being switched to {@link CatalogState#ALIVE} state.
	 */
	private final AtomicBoolean goingLive = new AtomicBoolean();
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified by
	 * its {@link Formula#getHash()} method and when the supervisor identifies that certain formula
	 * is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	private final CacheSupervisor cacheSupervisor;
	/**
	 * Contains sequence that allows automatic assigning monotonic primary keys to the entity collections.
	 */
	private final AtomicInteger entityTypeSequence;
	/**
	 * Contains catalog configuration.
	 */
	private final TransactionalReference<CatalogSchemaDecorator> schema;
	/**
	 * Catalog-level inverted index mapping mutated entity types to expression-based index triggers that depend
	 * on their data. Rebuilt when entity schemas change. Consulted during post-processing of mutations to
	 * discover which cross-entity triggers need to fire.
	 *
	 * Follows the same copy-on-write immutability principle as {@link #schema}: within a transaction,
	 * `set()` writes to the transactional layer; outside, it writes directly to the `AtomicReference`.
	 *
	 * @see CatalogExpressionTriggerRegistry
	 */
	private final TransactionalReference<CatalogExpressionTriggerRegistry> expressionTriggerRegistry;
	/**
	 * Contains unique catalog id that doesn't change with catalog schema changes - such as renaming.
	 * The id is assigned to the catalog when it is created and never changes.
	 */
	@Nonnull @Getter
	private final UUID catalogId;
	/**
	 * Indicates state in which Catalog operates.
	 *
	 * @see CatalogState
	 */
	private final CatalogState state;
	/**
	 * Indicates whether the catalog is read-only. If TRUE, no mutations are allowed.
	 */
	private final AtomicBoolean readOnly = new AtomicBoolean(false);
	/**
	 * Contains reference to the main catalog index that allows fast lookups for entities across all types.
	 */
	private final CatalogIndex catalogIndex;
	/**
	 * Isolated sequence service for this catalog.
	 */
	private final SequenceService sequenceService = new SequenceService();
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	private final ProxyFactory proxyFactory;
	/**
	 * Reference to the current {@link EvitaConfiguration} settings.
	 */
	private final EvitaConfiguration evitaConfiguration;
	/**
	 * Reference to the engine this catalog belongs to. Held only for the state the engine owns *about* this catalog
	 * but does not store in it - today that is the session registry, which is created lazily on the first session and
	 * therefore cannot be handed to the constructor. It is carried across catalog generations like the transaction
	 * manager is, because it identifies the engine and not the version.
	 */
	private final Evita evita;
	/**
	 * Reference to the shared transactional executor service that provides carrier threads for transaction processing.
	 */
	private final ObservableExecutorService transactionalExecutor;
	/**
	 * Reference to the shared executor service that provides carrier threads for transaction processing.
	 */
	private final Scheduler scheduler;
	/**
	 * Callback function that allows to propagate reference to the new catalog version to the {@link Evita}
	 * instance that is referring to the current version of the catalog.
	 */
	private final Consumer<Catalog> newCatalogVersionConsumer;
	/**
	 * Provides access to the entity schema in the catalog.
	 */
	@Getter private final CatalogEntitySchemaAccessor entitySchemaAccessor = new CatalogEntitySchemaAccessor();
	/**
	 * Transaction manager used for processing the transactions.
	 */
	@Getter private final TransactionManager transactionManager;
	/**
	 * Traffic recorder used for recording the traffic in the catalog.
	 */
	@Getter private final TrafficRecordingEngine trafficRecordingEngine;
	/**
	 * Contains reference to the archived catalog index that allows fast lookups for entities across all types.
	 *
	 * The archived index is initialized lazily on the first archived-scope access (see
	 * {@link #getCatalogIndex(Scope)}). It is held in an {@link AtomicReference} so that the lazy
	 * transition is safe against concurrent read queries - the reference stays {@code null} until the
	 * first archived-scope query, which preserves the "null means no archived data" semantics relied
	 * upon by the copy and persistence paths.
	 */
	private final AtomicReference<CatalogIndex> archiveCatalogIndex = new AtomicReference<>();
	/**
	 * The catalog-level twin of {@link EntityCollection#getUsageRegistry()}: it counts the capabilities of the
	 * attributes the **catalog schema** declares, which is where a globally-unique attribute's numbers belong. A query
	 * that names no collection resolves its attributes against the catalog schema and is served by the
	 * {@link GlobalUniqueIndex} inside {@link CatalogIndex}, so neither its request nor the
	 * maintenance an upsert pays for that index can be attributed to any single collection.
	 *
	 * **It holds exactly what this catalog physically maintains, and nothing else** - the `FILTERABLE` and `UNIQUE`
	 * of a `uniqueGlobally()` attribute, which is what its global unique index costs. Both recording sides are held
	 * to that: {@link EntityIndexLocalMutationExecutor#reportAttributeTouched} files
	 * only those two here, and {@link AttributeSchemaAccessor#recordRequestedTraits} drops a
	 * collection-less `SORTABLE` request rather than minting a row whose maintenance count could never leave zero. A
	 * sortable global attribute's sort index belongs to each collection declaring it, and is counted there.
	 *
	 * Its three properties are those of the collection-level registry, for the same reasons stated there:
	 * non-transactional shared telemetry, **carried by reference across catalog versions** (only a brand-new catalog
	 * and one loaded from disk mint their own, which is what makes the counts "since catalog load"), and pruned when
	 * this catalog adopts a new catalog schema version - see {@link #exchangeCatalogSchema(CatalogSchemaContract,
	 * CatalogSchema)}.
	 */
	@Nonnull private final SchemaCapabilityUsageRegistry usageRegistry;
	/**
	 * Last persisted schema version of the catalog.
	 */
	private long lastPersistedSchemaVersion;

	/**
	 * Verifies whether the catalog name could be used for a new catalog.
	 *
	 * @param catalogName        the name of the catalog
	 * @param catalogFolderAllocator allocates the folder the catalog is restored into, once the restore begins
	 * @param storageOptions     storage configuration supplying the root the token resolves against
	 * @param fileId             The ID of the file to be restored.
	 * @param pathToFile         the path to the ZIP file with the catalog content
	 * @param totalBytesExpected total bytes expected to be read from the input stream
	 * @param deleteAfterRestore whether to delete the ZIP file after restore
	 * @return future that will be completed with path where the content of the catalog was restored
	 */
	@Nonnull
	public static ServerTask<? extends FileIdCarrier, Void> createRestoreCatalogTask(
		@Nonnull String catalogName,
		@Nonnull CatalogFolderAllocator catalogFolderAllocator,
		@Nonnull StorageOptions storageOptions,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalBytesExpected,
		boolean deleteAfterRestore
	) {
		return ServiceLoader.load(CatalogPersistenceServiceFactory.class)
			.findFirst()
			.map(
				it -> it.restoreCatalogTo(
					catalogName, catalogFolderAllocator, storageOptions, fileId, pathToFile,
					totalBytesExpected, deleteAfterRestore
				)
			)
			.orElseThrow(() -> new IllegalStateException("IO service is unexpectedly not available!"));
	}

	/**
	 * Loads a catalog asynchronously using provided configurations and services.
	 *
	 * @param catalogName               the name of the catalog to be loaded
	 * @param readOnly                  indicates whether the catalog shouldbe loaded in read-only mode
	 * @param cacheSupervisor           the supervisor responsible for cache management within the catalog
	 * @param evita                     reference to the main Evita instance
	 * @param exportService             service used for handling file export operations
	 * @param newCatalogVersionConsumer consumer to handle actions when a new catalog version is created
	 * @param onSuccess                 callback function to be invoked upon successful catalog load, receiving the catalog name and loaded catalog
	 * @param onFailure                 callback function to be invoked upon failure, receiving the catalog name and the encountered exception
	 * @param tracingContext            tracing context used for distributed tracing and monitoring
	 * @return a {@link ProgressingFuture} object that tracks the progress of the catalog loading process and eventually
	 * resolves to a {@link Catalog} instance
	 */
	@Nonnull
	public static ProgressingFuture<Catalog> loadCatalog(
		@Nonnull String catalogName,
		boolean readOnly,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull Evita evita,
		@Nonnull ProxyFactory proxyFactory,
		@Nonnull ExportService exportService,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		@Nonnull BiConsumer<String, Catalog> onSuccess,
		@Nonnull BiConsumer<String, Throwable> onFailure,
		@Nonnull TracingContext tracingContext
	) {
		return new ProgressingFuture<>(
			1,
			() -> {
				final Map<String, EntityCollection> collections = createHashMap(128);
				final Map<Integer, EntityCollection> collectionByPk = createHashMap(128);
				final Map<String, EntitySchemaContract> entitySchemaIndex = createHashMap(128);
				final Catalog catalog = new Catalog(
					catalogName,
					cacheSupervisor,
					evita,
					proxyFactory,
					exportService,
					fileManagementService,
					newCatalogVersionConsumer,
					tracingContext,
					collections,
					collectionByPk,
					entitySchemaIndex,
					readOnly
				);

				final CatalogHeader<? extends LogRecordReference, ? extends CollectionReference> catalogHeader =
					catalog.persistenceService.getCatalogHeader(
						catalog.persistenceService.getLastCatalogVersion()
					);
				return new CatalogInitializationBulk(
					collections,
					collectionByPk,
					entitySchemaIndex,
					catalog,
					catalogHeader,
					CollectionUtils.createConcurrentHashMap(catalogHeader.getEntityTypeFileIndexes().size())
				);
			},
			initBulk -> {
				final CatalogHeader<? extends LogRecordReference, ? extends CollectionReference> catalogHeader = initBulk.catalogHeader();
				final long catalogVersion = catalogHeader.version();
				return catalogHeader
					.getEntityTypeFileIndexes()
					.stream()
					.map(
						entityTypeFileIndex -> {
							final CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> catalogPersistenceService = initBulk.catalog().persistenceService;
							final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> entityCollectionPersistenceService = catalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
								catalogVersion, entityTypeFileIndex.entityType(),
								entityTypeFileIndex.entityTypePrimaryKey()
							);
							final EntityCollectionHeader entityHeader = entityCollectionPersistenceService.getEntityCollectionHeader();

							return new ProgressingFuture<EntityCollection>(
								entityHeader.usedEntityIndexPrimaryKeys().size(),
								() -> {
									final Catalog catalog = initBulk.catalog();
									final String entityType = entityTypeFileIndex.entityType();
									final int entityTypePrimaryKey = entityHeader.entityTypePrimaryKey();
									final EntityCollection collection = new EntityCollection(
										catalogName,
										catalogVersion,
										catalogHeader.catalogState(),
										entityTypePrimaryKey,
										entityType,
										entityHeader.usedEntityIndexPrimaryKeys().size(),
										catalog.persistenceService,
										entityCollectionPersistenceService,
										catalog.cacheSupervisor,
										catalog.sequenceService,
										catalog.trafficRecordingEngine
									);
									initBulk.collections().put(entityType, collection);
									initBulk.collectionByPk().put(entityTypePrimaryKey, collection);
									collection.attachToCatalog(null, catalog);
									initBulk.entitySchemaIndex().put(entityType, collection.getSchema());
									return collection;
								},
								(entityCollection) -> {
									// backward compatibility (currently, the global index is part of used indexes)
									final Integer globalIndexPk = entityHeader.globalEntityIndexPrimaryKey();
									final EntityIndex globalIndex;
									if (globalIndexPk != null) {
										globalIndex = entityCollectionPersistenceService.readEntityIndex(
											catalogVersion, globalIndexPk, entityCollection.getInternalSchema(),
											initBulk.catalog().isUsageStatisticsTracked()
										);
										initBulk.addGlobalIndex(entityCollection.getEntityType(), globalIndex);
									} else {
										globalIndex = null;
									}
									return entityHeader
										.usedEntityIndexPrimaryKeys()
										.stream()
										.map(
											eid -> new ProgressingFuture<EntityIndex>(
												0,
												theFuture -> {
													// the global index is also listed among the used
													// indexes (see the note above) and has already been
													// read; reading it again would deserialize the whole
													// index a second time - and rebuild every structure
													// derived on load rather than persisted, such as the
													// trigram indexes - only for the duplicate to be
													// discarded by the combiner below
													if (globalIndex != null && Objects.equals(globalIndexPk, eid)) {
														return globalIndex;
													}
													final EntityIndex loadedIndex = entityCollectionPersistenceService
														.readEntityIndex(
															catalogVersion, eid, entityCollection.getInternalSchema(),
															initBulk.catalog().isUsageStatisticsTracked()
														);
													if (loadedIndex.getIndexKey().type() == EntityIndexType.GLOBAL) {
														initBulk.addGlobalIndex(
															entityCollection.getEntityType(), loadedIndex);
													}
													return loadedIndex;
												}
											)
										)
										.toList();
								},
								(theFuture, entityCollection, loadedIndexes) -> {
									// we need to add global indexes first, other indexes might look up in these indexes for data
									// we pass them via init bulk to avoid duplicate collection iteration here (collection might be large)
									final List<EntityIndex> globalIndexes = initBulk.globalIndexes(
										entityCollection.getEntityType()
									);
									for (EntityIndex entityIndex : globalIndexes) {
										entityCollection.addIndex(entityIndex);
									}
									// then we add the rest of indexes
									for (EntityIndex entityIndex : loadedIndexes) {
										if (entityIndex.getIndexKey().type() != EntityIndexType.GLOBAL) {
											entityCollection.addIndex(entityIndex);
										}
									}
									return entityCollection;
								},
								(entityCollection, exception) -> log.error(
									"Error while loading entity collection `{}` for catalog `{}`: {}",
									entityTypeFileIndex.entityType(),
									catalogName,
									exception.getMessage(),
									exception
								)

							);
						}
					)
					.toList();
			},
			(theFuture, initBulk, entityCollections) -> {
				final Catalog catalog = initBulk.catalog();
				// perform initialization of reflected schemas
				for (EntityCollection collection : initBulk.collections().values()) {
					collection.initSchema();
				}
				// after all schemas are resolved, build the expression trigger registry
				catalog.buildInitialExpressionTriggerRegistry();
				onSuccess.accept(catalogName, catalog);
				theFuture.updateProgress(1);
				return catalog;
			},
			(initBulk, exception) -> {
				if (initBulk != null) {
					for (EntityCollection collection : initBulk.collections().values()) {
						collection.terminate();
					}
					initBulk.catalog().terminate();
				}
				onFailure.accept(catalogName, exception);
			}
		);
	}

	public Catalog(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull Evita evita,
		@Nonnull ProxyFactory proxyFactory,
		@Nonnull ExportService exportService,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		@Nonnull TracingContext tracingContext
	) {
		final String catalogName = catalogSchema.getName();
		final long catalogVersion = 0L;

		this.evita = evita;
		this.evitaConfiguration = evita.getConfiguration();
		this.scheduler = evita.getServiceExecutor();
		this.transactionalExecutor = evita.getTransactionExecutor();

		final CatalogSchema internalCatalogSchema = CatalogSchema._internalBuild(
			catalogName, catalogSchema.getNameVariants(), null, catalogSchema.getCatalogEvolutionMode(),
			getEntitySchemaAccessor()
		);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(internalCatalogSchema));
		//noinspection unchecked
		this.persistenceService = (CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader>)
			ServiceLoader.load(CatalogPersistenceServiceFactory.class)
				.findFirst()
				.map(
					it -> it.createNew(
						this, this.getSchema().getName(),
						// a brand-new catalog is not in the engine state yet, so this is where its folder
						// binding is established rather than read
						evita.getCatalogFolderContext().folderIdForBinding(catalogName),
						this.evitaConfiguration.storage(),
						this.evitaConfiguration.transaction(),
						this.scheduler,
						exportService
					)
				)
				.orElseThrow(StorageImplementationNotFoundException::new);

		this.catalogId = UUID.randomUUID();
		final CatalogStoragePartPersistenceService<LogRecordReference, CollectionReference, StorageDescriptor> storagePartPersistenceService =
			this.persistenceService.getStoragePartPersistenceService(catalogVersion);
		storagePartPersistenceService.putStoragePart(catalogVersion, new CatalogSchemaStoragePart(getInternalSchema()));

		// initialize container buffer
		this.state = CatalogState.WARMING_UP;
		this.versionId = new TransactionalReference<>(catalogVersion);
		this.dataStoreBuffer = new WarmUpDataStoreMemoryBuffer(storagePartPersistenceService);
		this.cacheSupervisor = cacheSupervisor;
		this.entityCollections = new TransactionalMap<>(createHashMap(0), EntityCollection.class, Function.identity());
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(
			createHashMap(0), EntityCollection.class, Function.identity());
		this.entitySchemaIndex = new TransactionalMap<>(createHashMap(0));
		this.expressionTriggerRegistry = new TransactionalReference<>(CatalogExpressionTriggerRegistry.EMPTY);
		this.entityTypeSequence = this.sequenceService.getOrCreateSequence(
			catalogName, SequenceType.ENTITY_COLLECTION, 0
		);
		this.catalogIndex = new CatalogIndex(Scope.LIVE, this.evitaConfiguration.server().usageStatisticsTracking());
		// a catalog created here has no history to carry - this allocation is what makes the counts "since catalog
		// load", and the alignment against the schema published above is what opens each capability's observation
		// window there rather than at first use. A brand-new catalog declares no attribute yet, so the call mints
		// nothing today; it is here so that a future constructor accepting a populated schema cannot skip it
		this.usageRegistry = new SchemaCapabilityUsageRegistry();
		this.usageRegistry.alignWith(internalCatalogSchema);
		this.proxyFactory = proxyFactory;
		this.newCatalogVersionConsumer = newCatalogVersionConsumer;
		this.lastPersistedSchemaVersion = internalCatalogSchema.version();
		this.transactionManager = new TransactionManager(
			this,
			evita,
			this.scheduler,
			evita.getRequestExecutor(),
			this.transactionalExecutor,
			newCatalogVersionConsumer,
			catalogVersion
		);
		this.trafficRecordingEngine = new TrafficRecordingEngine(
			internalCatalogSchema.getName(),
			this.state,
			tracingContext,
			this.evitaConfiguration,
			fileManagementService,
			this.scheduler
		);

		this.persistenceService.storeHeader(
			this.catalogId, CatalogState.WARMING_UP, catalogVersion, 0, null,
			Collections.emptyList(),
			this.dataStoreBuffer
		);
	}

	private Catalog(
		@Nonnull String catalogName,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull Evita evita,
		@Nonnull ProxyFactory proxyFactory,
		@Nonnull ExportService exportService,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		@Nonnull TracingContext tracingContext,
		@Nonnull Map<String, EntityCollection> collections,
		@Nonnull Map<Integer, EntityCollection> collectionByPk,
		@Nonnull Map<String, EntitySchemaContract> entitySchemaIndex,
		boolean readOnly
	) {
		this.evita = evita;
		this.evitaConfiguration = evita.getConfiguration();
		this.scheduler = evita.getServiceExecutor();
		this.transactionalExecutor = evita.getTransactionExecutor();
		//noinspection unchecked
		this.persistenceService = (CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader>)
			ServiceLoader
				.load(CatalogPersistenceServiceFactory.class)
				.findFirst()
				.map(
					it -> it.load(
						this, catalogName,
						evita.getCatalogFolderContext().folderIdFor(catalogName),
						this.evitaConfiguration.storage(),
						this.evitaConfiguration.transaction(),
						this.scheduler,
						exportService
					)
				)
				.orElseThrow(() -> new IllegalStateException("IO service is unexpectedly not available!"));

		final CatalogHeader<LogRecordReference, CollectionReference> catalogHeader = this.persistenceService.getCatalogHeader(
			this.persistenceService.getLastCatalogVersion()
		);
		final long catalogVersion = catalogHeader.version();
		this.catalogId = catalogHeader.catalogId();
		this.versionId = new TransactionalReference<>(catalogVersion);
		this.readOnly.set(readOnly);
		this.state = catalogHeader.catalogState();
		// initialize container buffer
		final StoragePartPersistenceService<StorageDescriptor> storagePartPersistenceService =
			this.persistenceService.getStoragePartPersistenceService(catalogVersion);
		// initialize schema - still in constructor
		final CatalogSchema catalogSchema = CatalogSchemaStoragePart.deserializeWithCatalog(
			this,
			() -> ofNullable(
				storagePartPersistenceService.getStoragePart(catalogVersion, 1, CatalogSchemaStoragePart.class))
				.map(CatalogSchemaStoragePart::catalogSchema)
				.orElseThrow(() -> new SchemaNotFoundException(catalogHeader.catalogName()))
		);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(catalogSchema));
		this.catalogIndex = this.persistenceService.readCatalogIndex(this, Scope.LIVE)
			.orElseGet(() -> new CatalogIndex(Scope.LIVE, this.evitaConfiguration.server().usageStatisticsTracking()));
		// nothing about the usage counters is persisted, so a catalog read back from disk starts its observation
		// window here - aligned against the schema just deserialized, so that every globally-unique attribute already
		// has its row before the first query arrives rather than from whenever one first names it
		this.usageRegistry = new SchemaCapabilityUsageRegistry();
		this.usageRegistry.alignWith(catalogSchema);
		this.persistenceService.readCatalogIndex(this, Scope.ARCHIVED)
			.filter(it -> !it.isEmpty())
			.ifPresent(this.archiveCatalogIndex::set);
		this.cacheSupervisor = cacheSupervisor;
		this.trafficRecordingEngine = new TrafficRecordingEngine(
			catalogSchema.getName(),
			this.state,
			tracingContext,
			this.evitaConfiguration,
			fileManagementService,
			this.scheduler
		);
		this.dataStoreBuffer = catalogHeader.catalogState() == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(storagePartPersistenceService) :
			new TransactionalDataStoreMemoryBuffer(this, storagePartPersistenceService);

		this.proxyFactory = proxyFactory;
		this.newCatalogVersionConsumer = newCatalogVersionConsumer;
		this.lastPersistedSchemaVersion = catalogSchema.version();
		this.transactionManager = new TransactionManager(
			this, evita,
			this.scheduler,
			evita.getRequestExecutor(),
			this.transactionalExecutor,
			newCatalogVersionConsumer,
			catalogVersion
		);
		this.entityTypeSequence = this.sequenceService.getOrCreateSequence(
			catalogName, SequenceType.ENTITY_COLLECTION, catalogHeader.lastEntityCollectionPrimaryKey()
		);
		this.entityCollections = new TransactionalMap<>(collections, EntityCollection.class, Function.identity());
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(
			collectionByPk, EntityCollection.class, Function.identity()
		);
		this.entitySchemaIndex = new TransactionalMap<>(entitySchemaIndex);
		this.expressionTriggerRegistry = new TransactionalReference<>(CatalogExpressionTriggerRegistry.EMPTY);
	}

	Catalog(
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		@Nonnull CatalogIndex catalogIndex,
		@Nullable CatalogIndex archiveCatalogIndex,
		@Nonnull Collection<EntityCollection> entityCollections,
		@Nonnull Catalog previousCatalogVersion
	) {
		this(
			catalogVersion,
			catalogState,
			catalogIndex,
			archiveCatalogIndex,
			entityCollections,
			previousCatalogVersion.persistenceService,
			previousCatalogVersion,
			false
		);
	}

	Catalog(
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		@Nonnull CatalogIndex catalogIndex,
		@Nullable CatalogIndex archiveCatalogIndex,
		@Nonnull Collection<EntityCollection> entityCollections,
		@Nonnull CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> persistenceService,
		@Nonnull Catalog previousCatalogVersion,
		boolean initSchemas
	) {
		this.catalogId = previousCatalogVersion.catalogId;
		this.versionId = new TransactionalReference<>(catalogVersion);
		this.state = catalogState;
		this.catalogIndex = catalogIndex;
		// every caller of this constructor rebuilds an EXISTING catalog - a commit, going live, a catalog rename - and
		// the registry travels with it exactly as the catalog index's own activity holder does. Minting one here would
		// reset the counters on every commit, which is to say on precisely the catalogs worth measuring
		this.usageRegistry = previousCatalogVersion.usageRegistry;
		this.archiveCatalogIndex.set(archiveCatalogIndex);
		this.persistenceService = persistenceService;
		this.cacheSupervisor = previousCatalogVersion.cacheSupervisor;
		this.trafficRecordingEngine = previousCatalogVersion.trafficRecordingEngine;
		this.entityTypeSequence = previousCatalogVersion.entityTypeSequence;
		this.proxyFactory = previousCatalogVersion.proxyFactory;
		this.evita = previousCatalogVersion.evita;
		this.evitaConfiguration = previousCatalogVersion.evitaConfiguration;
		this.scheduler = previousCatalogVersion.scheduler;
		this.transactionalExecutor = previousCatalogVersion.transactionalExecutor;
		this.newCatalogVersionConsumer = previousCatalogVersion.newCatalogVersionConsumer;
		this.transactionManager = previousCatalogVersion.transactionManager;

		final StoragePartPersistenceService<StorageDescriptor> storagePartPersistenceService =
			persistenceService.getStoragePartPersistenceService(catalogVersion);
		final CatalogSchema catalogSchema = CatalogSchema._internalBuildWithUpdatedEntitySchemaAccessor(
			previousCatalogVersion.getInternalSchema(),
			this.entitySchemaAccessor
		);

		this.trafficRecordingEngine.updateCatalogName(catalogSchema.getName(), this.state);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(catalogSchema));
		this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(storagePartPersistenceService) :
			new TransactionalDataStoreMemoryBuffer(this, storagePartPersistenceService);
		// we need to switch references working with catalog (inter index relations) to new catalog
		// the collections are not yet used anywhere - we're still safe here
		final Map<String, EntityCollection> newEntityCollections = CollectionUtils.createHashMap(
			entityCollections.size());
		final Map<Integer, EntityCollection> newEntityCollectionsIndex = CollectionUtils.createHashMap(
			entityCollections.size());
		final Map<String, EntitySchemaContract> newEntitySchemaIndex = CollectionUtils.createHashMap(
			entityCollections.size());
		for (EntityCollection entityCollection : entityCollections) {
			newEntityCollections.put(entityCollection.getEntityType(), entityCollection);
			newEntityCollectionsIndex.put(entityCollection.getEntityTypePrimaryKey(), entityCollection);
		}
		this.entityCollections = new TransactionalMap<>(
			newEntityCollections, EntityCollection.class, Function.identity());
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(
			newEntityCollectionsIndex, EntityCollection.class, Function.identity());
		this.entitySchemaIndex = new TransactionalMap<>(newEntitySchemaIndex);
		this.expressionTriggerRegistry = new TransactionalReference<>(
			previousCatalogVersion.getExpressionTriggerRegistry()
		);
		this.lastPersistedSchemaVersion = previousCatalogVersion.lastPersistedSchemaVersion;
		// finally attach every collection to this instance of the catalog
		for (EntityCollection entityCollection : entityCollections) {
			entityCollection.attachToCatalog(null, this);
		}
		// and retrieve their schemas
		for (EntityCollection entityCollection : entityCollections) {
			if (initSchemas) {
				// and init its schema
				entityCollection.initSchema();
			}
			// when the collection is attached to the catalog, we can access its schema and put it into the schema index
			newEntitySchemaIndex.put(entityCollection.getEntityType(), entityCollection.getSchema());
		}
		if (initSchemas) {
			// after all schemas are resolved (including reflected references), rebuild the expression
			// trigger registry from the fully-resolved schema state
			buildInitialExpressionTriggerRegistry();
		}
	}

	@Override
	@Nonnull
	public SealedCatalogSchema getSchema() {
		return Objects.requireNonNull(this.schema.get());
	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateSchema(
		@Nonnull EvitaContract evita,
		@Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutation
	) throws SchemaAlteringException {
		final OffsetDateTime start = OffsetDateTime.now();
		// internal schema is expected to be produced on the server side
		final CatalogSchema originalSchema = getInternalSchema();
		final AtomicReference<MutationApplicationRecord> record = new AtomicReference<>();
		// collect entity types whose cross-entity trigger registry must be rebuilt, deferring
		// the rebuild until the entire batch is applied — intermediate states may be transiently
		// inconsistent (e.g. entity B declares a histogram referencing an attribute on entity A
		// that a later mutation in the same batch will add). `LazyHashSet` avoids allocating the
		// backing `HashSet` for batches that touch no entity schemas (single-mutation catalog-level
		// calls), which is the common case.
		final Deque<Set<String>> rebuildStack = PENDING_TRIGGER_REBUILDS.get();
		final Set<String> rebuildFrame = new LazyHashSet<>(4);
		rebuildStack.push(rebuildFrame);
		try {
			// refuse the whole batch before a single schema is exchanged - see the method's own documentation for why
			// a mid-cascade refusal cannot be undone
			verifyEntitySchemaMutationsApplicable(schemaMutation);
			final Optional<Transaction> transactionRef = Transaction.getTransaction();
			ModifyEntitySchemaMutation[] modifyEntitySchemaMutations = null;
			CatalogSchema currentSchema = originalSchema;
			CatalogSchemaContract updatedSchema = originalSchema;
			final Transaction transaction = transactionRef.orElse(null);
			for (LocalCatalogSchemaMutation theMutation : schemaMutation) {
				transactionRef.ifPresent(it -> it.registerMutation(theMutation));

				// record the mutation
				if (sessionId != null) {
					record.set(
						this.trafficRecordingEngine.recordMutation(sessionId, start, theMutation)
					);
				}

				// if the mutation implements entity schema mutation apply it on the appropriate schema
				if (theMutation instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation) {
					final String entityType = modifyEntitySchemaMutation.getName();
					// if the collection doesn't exist yet - create new one
					EntityCollection entityCollection = this.entityCollections.get(entityType);
					if (entityCollection == null) {
						if (!getSchema().getCatalogEvolutionMode().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES)) {
							throw new InvalidSchemaMutationException(
								entityType, CatalogEvolutionMode.ADDING_ENTITY_TYPES);
						}
						currentSchema = createEntitySchema(
							new CreateEntitySchemaMutation(entityType), transaction, updatedSchema);
						entityCollection = Objects.requireNonNull(this.entityCollections.get(entityType));
					}
					updatedSchema = modifyEntitySchema(
						sessionId,
						modifyEntitySchemaMutation, updatedSchema,
						entityCollection
					);
				} else if (theMutation instanceof RemoveEntitySchemaMutation removeEntitySchemaMutation) {
					updatedSchema = removeEntitySchema(removeEntitySchemaMutation, transaction, updatedSchema);
				} else if (theMutation instanceof CreateEntitySchemaMutation createEntitySchemaMutation) {
					updatedSchema = createEntitySchema(createEntitySchemaMutation, transaction, updatedSchema);
				} else if (theMutation instanceof ModifyEntitySchemaNameMutation renameEntitySchemaMutation) {
					updatedSchema = modifyEntitySchemaName(
						sessionId,
						renameEntitySchemaMutation, transaction, updatedSchema
					);
				} else {
					final CatalogSchemaWithImpactOnEntitySchemas schemaWithImpactOnEntitySchemas = modifyCatalogSchema(
						theMutation, updatedSchema);
					updatedSchema = schemaWithImpactOnEntitySchemas.updatedCatalogSchema();
					modifyEntitySchemaMutations = modifyEntitySchemaMutations == null || ArrayUtils.isEmpty(
						schemaWithImpactOnEntitySchemas.entitySchemaMutations()) ?
						schemaWithImpactOnEntitySchemas.entitySchemaMutations() :
						ArrayUtils.mergeArrays(
							modifyEntitySchemaMutations, schemaWithImpactOnEntitySchemas.entitySchemaMutations());
				}

				// finish the recording
				if (sessionId != null) {
					record.get().finish();
				}

				// exchange the current catalog schema so that additional entity schema mutations can take advantage of
				// previous catalog mutations when validated
				currentSchema = exchangeCatalogSchema(updatedSchema, currentSchema);
			}
			// alter affected entity schemas
			if (modifyEntitySchemaMutations != null) {
				updateSchema(evita, sessionId, modifyEntitySchemaMutations);
			}
			// drain deferred trigger rebuilds inside the try block so any cross-entity
			// validation failure (e.g. a histogram value expression referencing a missing
			// attribute) propagates into the revert branch just like an eager rebuild would
			drainTriggerRebuildFrame(rebuildFrame);
		} catch (RuntimeException ex) {
			// revert all changes in the schema (for current transaction) if anything failed
			this.schema.set(new CatalogSchemaDecorator(originalSchema));

			// finish the recording with error
			final MutationApplicationRecord recording = record.get();
			if (recording != null) {
				recording.finishWithException(ex);
			}

			throw ex;
		} finally {
			// always pop our own frame; if no exception was thrown the frame was already
			// drained and is empty, if an exception was thrown we discard any remaining
			// pending rebuilds (the revert above restored the pre-batch schema so there is
			// nothing new to rebuild)
			rebuildStack.pop();
			if (rebuildStack.isEmpty()) {
				PENDING_TRIGGER_REBUILDS.remove();
			}
			// finally, store the updated catalog schema to disk
			final CatalogSchema currentSchema = getInternalSchema();
			if (currentSchema.version() > originalSchema.version()) {
				this.dataStoreBuffer.update(getVersion(), new CatalogSchemaStoragePart(currentSchema));
			}
		}
		return getSchema();
	}

	/**
	 * Rebuilds expression triggers for every entity type collected in the given batch frame.
	 * Called at the end of a `updateSchema(...)` batch, after every mutation in the batch has
	 * been applied and before exceptions can escape the enclosing `try` block.
	 *
	 * @param frame the batch frame holding deferred entity type names
	 */
	private void drainTriggerRebuildFrame(@Nonnull Set<String> frame) {
		if (frame.isEmpty()) {
			return;
		}
		for (final String entityType : frame) {
			rebuildExpressionTriggerRegistryForEntityType(entityType);
		}
		frame.clear();
	}

	@Override
	@Nonnull
	public CatalogState getCatalogState() {
		return this.state;
	}

	@Override
	@Nonnull
	public String getName() {
		return getInternalSchema().getName();
	}

	@Override
	public long getVersion() {
		return Objects.requireNonNull(this.versionId.get());
	}

	/**
	 * This method is part of the internal API and allows to move forward the catalog version sequence number in
	 * transactional context.
	 *
	 * @param catalogVersion the new catalog version
	 */
	public void setVersion(long catalogVersion) {
		Assert.isTrue(isTransactionAvailable(), "This method is expected to be called in transactional context only.");
		this.versionId.set(catalogVersion);
	}

	@Override
	public boolean supportsTransaction() {
		return this.state == CatalogState.ALIVE;
	}

	@Override
	@Nonnull
	public Set<String> getEntityTypes() {
		return this.entityCollections.keySet();
	}

	@Override
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(
		@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		final QueryPlan queryPlan = QueryPlanner.planQuery(queryContext);

		return this.trafficRecordingEngine.recordQuery(
			"query",
			session.getId(),
			queryPlan
		);
	}

	@Override
	public void applyMutation(
		@Nonnull EvitaSessionContract session, @Nonnull CatalogBoundMutation mutation) throws InvalidMutationException {
		Assert.isPremiseValid(
			!(mutation instanceof EngineMutation),
			"Engine mutations are not allowed to be applied on the catalog level! Use the `applyMutation` method on the evitaDB instance instead."
		);
		if (mutation instanceof LocalCatalogSchemaMutation schemaMutation) {
			// apply schema mutation to the catalog
			updateSchema(session.getEvita(), session.getId(), schemaMutation);
		} else if (mutation instanceof EntityMutation entityMutation) {
			getCollectionForEntityOrThrowException(entityMutation.getEntityType())
				.applyMutation(session, entityMutation);
		} else {
			throw new InvalidMutationException(
				"Unexpected mutation type: " + mutation.getClass().getName(),
				"Unexpected mutation type."
			);
		}
	}

	@Override
	@Nonnull
	public Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType) {
		return ofNullable(this.entityCollections.get(entityType));
	}

	@Override
	@Nonnull
	public EntityCollection getCollectionForEntityOrThrowException(
		@Nonnull String entityType
	) throws CollectionNotFoundException {
		// plain get + null-check + throw (no Optional / capturing-lambda allocation): this runs on the
		// unique-attribute write path once per value via the EntityTypeClassifierResolver delegators below.
		final EntityCollection entityCollection = this.entityCollections.get(entityType);
		if (entityCollection == null) {
			throw new CollectionNotFoundException(entityType);
		}
		return entityCollection;
	}

	@Override
	@Nonnull
	public EntityCollection getCollectionForEntityPrimaryKeyOrThrowException(
		int entityTypePrimaryKey
	) throws CollectionNotFoundException {
		// plain get + null-check + throw (no Optional / capturing-lambda allocation): this runs on the
		// unique-attribute write path once per value via the EntityTypeClassifierResolver delegators below.
		final EntityCollection entityCollection = this.entityCollectionsByPrimaryKey.get(entityTypePrimaryKey);
		if (entityCollection == null) {
			throw new CollectionNotFoundException(entityTypePrimaryKey);
		}
		return entityCollection;
	}

	@Override
	public int toEntityTypePrimaryKey(@Nonnull String entityType) {
		return getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
	}

	@Nonnull
	@Override
	public String toEntityTypeName(int entityTypePrimaryKey) {
		return getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
	}

	@Override
	@Nonnull
	public EntityCollection getOrCreateCollectionForEntity(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		return ofNullable(this.entityCollections.get(entityType))
			.orElseGet(() -> {
				if (!getSchema().getCatalogEvolutionMode().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES)) {
					throw new InvalidSchemaMutationException(entityType, CatalogEvolutionMode.ADDING_ENTITY_TYPES);
				}
				updateSchema(session.getEvita(), session.getId(), new CreateEntitySchemaMutation(entityType));
				return Objects.requireNonNull(this.entityCollections.get(entityType));
			});
	}

	@Nonnull
	@Override
	public ProgressingFuture<CatalogContract> replace(
		@Nonnull CatalogSchemaContract updatedSchema,
		@Nullable CatalogContract catalogToBeReplaced
	) {
		return new ProgressingFuture<>(
			// use "virtual" percentage to indicate progress
			100,
			theFuture -> {
				final long catalogVersion = getVersion();
				// Read before the handover, because `exchangeCatalogSchema` below rewrites what `getName()`
				// answers - so a failure past that point would otherwise report the new name as the old one.
				final String currentCatalogName = getName();
				final CatalogSchema renamedSchema = CatalogSchema._internalBuild(updatedSchema);
				final CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> newIoService =
					this.persistenceService.replaceWith(
						catalogVersion,
						updatedSchema.getName(),
						updatedSchema.getNameVariants(),
						renamedSchema,
						this.dataStoreBuffer,
						// recalculate to percentages
						(done, total) -> theFuture.updateProgress((int) (((double) done / total) * 100))
					);
				// **Everything below is as irreversible as `replaceWith` itself, and is marked to say so.** By
				// the time that call returns, the folder has been relabelled *and* the service that served this
				// catalog has been closed - so a failure in the rebuild that follows leaves `this` catalog, the
				// one still published under the name being renamed away from, holding a closed persistence
				// service in a folder whose stored identity no longer agrees with engine state. Left unmarked,
				// such a failure takes the operator's ordinary compensating path, which resumes session
				// admission and hands callers exactly that catalog: the failure mode the marker exists to
				// prevent, reached by a route that never enters `replaceWith`.
				try {
					final long catalogVersionAfterRename = newIoService.getLastCatalogVersion();
					final CatalogState catalogState = getCatalogState();
					final List<EntityCollection> newCollections = this.entityCollections
						.values()
						.stream()
						.map(
							it -> new EntityCollection(
								updatedSchema.getName(),
								catalogVersionAfterRename,
								catalogState,
								it,
								newIoService,
								this.sequenceService
							)
						)
						.toList();

					this.transactionManager.advanceVersion(catalogVersionAfterRename);
					// Exchanged **here**, not before the handover above, and this ordering is load-bearing. The
					// exchange mutates *this* catalog - the one still published under the name it is being
					// renamed away from - so performed early it hands a live catalog a schema naming a rename
					// that has not happened yet, and a failure between the two leaves it there. The damage is
					// not cosmetic: the commit pipeline looks a catalog up by the name its schema reports
					// (`ExpandedEngineState#replaceCatalogReference`), so a write accepted afterwards is
					// appended to the write-ahead log and then dies against a name the engine state has never
					// heard of - and the next boot fails replaying it. That matters most for the failures that
					// are *compensable*, where the catalog goes on serving; past the relabel the marker below
					// keeps it from serving at all.
					exchangeCatalogSchema(renamedSchema, getInternalSchema());
					return new Catalog(
						catalogVersionAfterRename,
						catalogState,
						this.catalogIndex.createShallowCopyWithResetDirtyFlag(),
						this.archiveCatalogIndex.get() == null ?
							null :
							this.archiveCatalogIndex.get().createShallowCopyWithResetDirtyFlag(),
						newCollections,
						newIoService,
						this,
						true
					);
				} catch (Throwable ex) {
					// The replacement service never reached a catalog that could close it, so it is closed
					// here or its handles into the folder outlive the operation - and the folder is one a
					// later drop or replace will want to delete. Suppressed rather than propagated: the
					// failure being reported is the one worth reporting.
					try {
						newIoService.close();
					} catch (Throwable suppressed) {
						// `Throwable`, so that an `Error` raised while closing cannot *replace* the marked
						// failure below - which would send the operator down the compensating path for a
						// handover that has already relabelled the folder.
						ex.addSuppressed(suppressed);
					}
					// `Throwable` above rather than `RuntimeException` for the same reason the storage layer
					// uses it: past the relabel an `Error` leaves the identical disagreement behind, and
					// compensating for it is the wrong answer however unsurvivable it is.
					throw new CatalogHandoverFailedException(currentCatalogName, updatedSchema.getName(), ex);
				}
			}
		);
	}

	@Nonnull
	@Override
	public Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return this.entitySchemaIndex;
	}

	@Override
	@Nonnull
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		return ofNullable(this.entityCollections.get(entityType))
			.map(EntityCollection::getSchema);
	}

	@Override
	public boolean isGoingLive() {
		return this.goingLive.get();
	}

	@Nonnull
	@Override
	public Catalog goLive() {
		try {
			Assert.isTrue(
				this.goingLive.compareAndSet(false, true),
				"Concurrent call of the `goLive` method is not supported!"
			);

			Assert.isTrue(this.state == CatalogState.WARMING_UP, "Catalog has already alive state!");
			final List<EntityCollection> newCollections = this.entityCollections
				.values()
				.stream()
				.map(collection -> collection.createCopyForNewCatalogAttachment(CatalogState.ALIVE))
				.toList();

			this.persistenceService.goLive(1L);

			final Catalog newCatalog = new Catalog(
				1L,
				CatalogState.ALIVE,
				this.catalogIndex.createShallowCopyWithResetDirtyFlag(),
				this.archiveCatalogIndex.get() == null ?
					null :
					this.archiveCatalogIndex.get().createShallowCopyWithResetDirtyFlag(),
				newCollections,
				this.persistenceService,
				this,
				true
			);

			this.transactionManager.advanceVersion(newCatalog.getVersion());
			// marks a sweep boundary at the end of bulk indexing, so the next periodic sweep reclaims what the
			// import stopped using. Gated because a lone sweep releases almost nothing - see CollationKeyCache#sweepAll
			if (this.evitaConfiguration.server().dropCollationKeysAfterSecondsOfInactivity() > 0) {
				final int releasedCollationKeys = CollationKeyCache.sweepAll();
				log.info(
					"Catalog `{}` is now alive! (released {} collation keys unused since the previous sweep)",
					newCatalog.getName(), releasedCollationKeys
				);
			} else {
				log.info("Catalog `{}` is now alive!", newCatalog.getName());
			}
			return newCatalog;

		} finally {
			this.goingLive.set(false);
		}
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeCatalogCapture> registerChangeCatalogCapture(
		@Nonnull ChangeCatalogCaptureRequest request
	) {
		Assert.isTrue(
			getCatalogState() == CatalogState.ALIVE,
			() -> new CatalogNotAliveException(getInternalSchema().getName())
		);
		return this.transactionManager.registerObserver(request);
	}

	@Override
	public void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalogConsumer) {
		final long lastTxVersionRecorded = this.persistenceService.getLastCatalogVersionInMutationStream();
		this.persistenceService.getFirstNonProcessedTransactionInWal(getVersion())
			.ifPresentOrElse(
				transactionMutation -> {
					final long start = System.nanoTime();
					final long firstNonProcessedTxVersion = transactionMutation.getVersion();
					// range [first..last] is inclusive on both ends, so the count is last - first + 1
					final long nonProcessedTxCount = lastTxVersionRecorded - firstNonProcessedTxVersion + 1;
					log.info(
						"Non-processed WAL transaction(s) found for catalog `{}`: {} (versions {}..{}). Processing it now ...",
						this.getName(), nonProcessedTxCount, firstNonProcessedTxVersion, lastTxVersionRecorded
					);
					final Optional<ProcessResult> processResult = this.transactionManager.processEntireWriteAheadLog(
						firstNonProcessedTxVersion,
						new LongConsumer() {
							private long lastPercent;
							private long lastLoggedTime = System.currentTimeMillis();

							@Override
							public void accept(long txId) {
								int percentDone = (int) ((txId - firstNonProcessedTxVersion) * 100 / Math.max(
									nonProcessedTxCount, 1));
								if (percentDone > this.lastPercent) {
									this.lastPercent = percentDone;
									if (System.currentTimeMillis() - this.lastLoggedTime >= 5000) {
										this.lastLoggedTime = System.currentTimeMillis();
										log.info(
											"Processing catalog `{}` WAL transactions: {}% done.",
											Catalog.this.getName(), percentDone
										);
									}
								}
							}
						}
					);

					processResult.ifPresent(
						pr -> {
							final Catalog newCatalog = pr.catalog();
							// post-replay state snapshot: any drift between lastAssigned/lastWritten
							// and lastFinalized/WAL head after this line is a smoking gun - new user
							// transactions will immediately collide with the WAL if lastWritten lags
							// behind either lastFinalized or the WAL's head
							log.info(
								"WAL of `{}` catalog was processed in {}. Post-replay state: " +
									"lastAssigned={}, lastWritten={}, lastFinalized={}, " +
									"walFirstVersionInCurrentFile={}, walLastWrittenVersion={}.",
								this.getName(),
								StringUtils.formatNano(System.nanoTime() - start),
								this.transactionManager.getLastAssignedCatalogVersion(),
								this.transactionManager.getLastWrittenCatalogVersion(),
								this.transactionManager.getLastFinalizedCatalogVersion(),
								newCatalog.getFirstCatalogVersionInMutationStream(),
								newCatalog.getLastCatalogVersionInMutationStream()
							);
							newCatalog.persistenceService.verifyIntegrity();
							newCatalog.persistenceService.purgeAllObsoleteFiles();
							updatedCatalogConsumer.accept(newCatalog);
						}
					);
				},
				() -> {
					this.persistenceService.verifyIntegrity();
					this.persistenceService.purgeAllObsoleteFiles();
					updatedCatalogConsumer.accept(this);
				}
			);
	}

	@Nonnull
	@Override
	public MaterializedVersionBlock getFirstCatalogVersionAfter(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException {
		return this.persistenceService.getFirstCatalogVersionAfter(moment);
	}

	@Nonnull
	@Override
	public MaterializedVersionBlock getLastCatalogVersionBefore(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException {
		return this.persistenceService.getLastCatalogVersionBefore(moment);
	}

	@Nonnull
	@Override
	public PaginatedList<MaterializedVersionBlock> getCatalogVersions(
		@Nonnull TimeFlow timeFlow, int page, int pageSize
	) {
		return this.persistenceService.getCatalogVersions(timeFlow, page, pageSize);
	}

	@Nonnull
	@Override
	public List<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		return this.persistenceService.getCatalogVersionDescriptors(catalogVersion);
	}

	@Override
	@Nonnull
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		return this.persistenceService.getCommittedMutationStream(catalogVersion);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		return this.persistenceService.getReversedCommittedMutationStream(catalogVersion);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> backup(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongFunction<CatalogVersionPin> onStart
	) throws TemporalDataNotAvailableException {
		final ServerTask<?, FileForFetch> backupTask = this.persistenceService.createBackupTask(
			pastMoment, catalogVersion, includingWAL, onStart
		);
		return submitBackupTask(backupTask);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> fullBackup(
		@Nullable LongFunction<CatalogVersionPin> onStart
	) {
		final ServerTask<?, FileForFetch> backupTask = this.persistenceService.createFullBackupTask(
			onStart
		);
		return submitBackupTask(backupTask);
	}

	/**
	 * Submits an already constructed backup task, cancelling it again if the submission itself fails.
	 *
	 * A backup task pins the catalog version it is going to read in its **constructor**, and only running it or
	 * cancelling it gives that pin back. A task that is constructed and then dropped - which is what a rejected
	 * submission leaves behind - would hold its version for the rest of the catalog's life, and since a full backup
	 * pins the oldest retained version, that permanently freezes every reclamation the catalog would otherwise do.
	 *
	 * @param backupTask the task to submit
	 * @return the very same task, now queued
	 */
	@Nonnull
	private ServerTask<?, FileForFetch> submitBackupTask(@Nonnull ServerTask<?, FileForFetch> backupTask) {
		try {
			this.scheduler.submit(backupTask);
		} catch (RuntimeException ex) {
			// releases the pin taken in the constructor by way of the task's own tear-down
			backupTask.cancel();
			throw ex;
		}
		return backupTask;
	}

	/**
	 * Copies this catalog's contents into the folder the engine allocated for the duplicate.
	 *
	 * Deliberately not on {@link CatalogContract}: the folder a duplicate lands in is engine
	 * state, and the token naming it is a storage-layer type the public contract does not expose.
	 * Duplicating is only ever driven by `DuplicateCatalogMutationOperator`, which is engine-internal and holds
	 * the allocation, so the narrower signature costs nothing and removes the only remaining way to ask for a
	 * copy into a folder named after the catalog.
	 *
	 * @param targetCatalogName name the copy will be registered under
	 * @param targetFolderId    folder the copy is written into, allocated and marked provisional by the caller
	 * @return progressing future that tracks the copy
	 */
	@Nonnull
	public ProgressingFuture<Void> duplicateTo(
		@Nonnull String targetCatalogName,
		@Nonnull CatalogFolderId targetFolderId
	) {
		return this.persistenceService.duplicateCatalog(
			targetCatalogName, targetFolderId, this.evitaConfiguration.storage()
		);
	}

	@Nonnull
	@Override
	public CatalogStatistics getStatistics(@Nonnull Set<CatalogStatisticsComponent> components) {
		CatalogStatisticsComponent.assertNotEmpty(components);
		final CatalogStatistics.Builder builder = CatalogStatistics.builder(getIdentity());
		// STORAGE_SIZE, HISTORY and FRAGMENTATION are three readings of one directory listing: the first attributes its
		// bytes, the second reports how many files each of two of those classes holds and what is pinning them, the
		// third turns the live/waste split into a share. Measuring once is not only the cheaper answer - it is the only
		// one under which the three components cannot describe different moments. The listing is flat and its sum is
		// the measured total, which is what keeps the size record's total-equals-sum invariant true by construction
		// rather than by agreement between separate measurements.
		// FRAGMENTATION needs one thing more - the compaction predicate, evaluated against those very file lengths -
		// so when it is asked for, the persistence layer measures both from the one listing and the other two read
		// their footprint back out of that snapshot. Asking for them *without* FRAGMENTATION costs no forecast at all
		final CatalogFragmentationSnapshot fragmentationSnapshot =
			components.contains(CatalogStatisticsComponent.FRAGMENTATION) ?
				this.persistenceService.measureFragmentation() : null;
		final CatalogStorageFootprint storageFootprint;
		if (fragmentationSnapshot != null) {
			storageFootprint = fragmentationSnapshot.footprint();
		} else if (components.contains(CatalogStatisticsComponent.STORAGE_SIZE) ||
			components.contains(CatalogStatisticsComponent.HISTORY)) {
			storageFootprint = this.persistenceService.measureStorageFootprint();
		} else {
			storageFootprint = null;
		}
		// COMMIT_PIPELINE and ACTIVITY both read the version watermarks, and ACTIVITY's `pipelineDepth` is by
		// definition the span between two of them. Reading them twice in one request would let the two components
		// contradict each other whenever a stage advanced in between - a client comparing them would see a depth that
		// does not match the watermarks it is derived from and would be right to call it a bug.
		// Null here means either "neither was asked for" or "this catalog has no pipeline"; inside the two arms below
		// the first is impossible, so there it reads purely as the second
		final CommitPipelineStatistics commitPipeline =
			supportsTransaction() && (components.contains(CatalogStatisticsComponent.COMMIT_PIPELINE) ||
				components.contains(CatalogStatisticsComponent.ACTIVITY)) ?
				this.transactionManager.describeCommitPipeline() : null;
		for (final CatalogStatisticsComponent component : components) {
			switch (component) {
				// always recorded by the builder itself, since nothing else can be interpreted without it
				case IDENTITY -> { }
				case COLLECTIONS -> builder.withCollections(collectCollectionInventory());
				case INDEX_SUMMARY -> builder.withIndexSummary(new IndexSummaryStatistics(countIndexes()));
				case RECORD_COUNTS -> builder.withRecordCounts(countRecords());
				case STORAGE_SIZE -> builder.withStorageSize(
					StorageSizeProjection.toStorageSizeStatistics(Objects.requireNonNull(storageFootprint))
				);
				case STORAGE_COMPOSITION -> builder.withStorageComposition(composeStorageParts());
				case SESSIONS -> builder.withSessions(countSessions());
				case COMMIT_PIPELINE -> {
					if (commitPipeline != null) {
						builder.withCommitPipeline(commitPipeline);
					} else {
						// four zeroes would render as a pipeline with nothing queued anywhere, which is the opposite of
						// the truth: in WARM_UP writes bypass the pipeline entirely and none of its watermarks move
						builder.withUnavailable(
							component, ComponentAvailability.FEATURE_DISABLED, bulkWriteModeExplanation(
								getCatalogState())
						);
					}
				}
				case HISTORY -> builder.withHistory(describeHistory(Objects.requireNonNull(storageFootprint)));
				case VOLATILE_STATE -> builder.withVolatileState(measureVolatileState());
				case FRAGMENTATION -> builder.withFragmentation(
					FragmentationProjection.toFragmentationStatistics(
						Objects.requireNonNull(fragmentationSnapshot),
						this.evitaConfiguration.storage()
					)
				);
				case ACTIVITY -> {
					if (commitPipeline != null) {
						builder.withActivity(this.transactionManager.describeActivity(commitPipeline));
					} else {
						// every counter would read zero however hard the catalog is being written, because bulk
						// ingestion never enters the pipeline that counts them - "idle and healthy" is the exact
						// inverse of the truth here, same as for COMMIT_PIPELINE above
						builder.withUnavailable(
							component, ComponentAvailability.FEATURE_DISABLED, bulkWriteModeExplanation(
								getCatalogState())
						);
					}
				}
				case DURABILITY -> {
					final DurabilitySnapshot durability = this.persistenceService.measureDurability();
					if (durability != null) {
						builder.withDurability(DurabilityProjection.toDurabilityStatistics(durability));
					} else {
						// zeroes here would read as "durability is instant and free"; with sync writes off it in fact
						// means durability is not happening at all, which is the inverse of that
						builder.withUnavailable(
							component,
							ComponentAvailability.FEATURE_DISABLED,
							"Catalog checkpoints at the end of every round, so there is no deferred-durability fence " +
								"to describe - either no checkpoint interval is configured, or writes are not synced " +
								"to the physical device."
						);
					}
				}
				case INDEX_CARDINALITY -> builder.withIndexCardinality(
					CatalogIndexCardinalityProjection.describe(collectCatalogIndexes())
				);
				// A switch *statement* is not checked for exhaustiveness the way an expression is, so a component
				// added to the enum and not to this switch would fall straight through and record nothing. The
				// resulting response is indistinguishable from one where the client never asked for it - the exact
				// ambiguity the status-and-reason model exists to remove, arrived at by omission instead
				default -> throw new GenericEvitaInternalError(
					"Catalog statistics component `" + component + "` is not handled!"
				);
			}
		}
		return builder.build();
	}

	@Nonnull
	@Override
	public IndexBrowseResult browseIndexes(@Nonnull IndexBrowseCriteria criteria) {
		// no snapshot is taken where the collection-level browse takes one: the collection seals its index map so that
		// the match count and the page cannot come from two different states, and a catalog has at most one index per
		// scope - collected here in one pass, after which nothing is read from the catalog again
		return CatalogIndexProjection.browse(
			collectCatalogIndexes(), criteria, getVersion()
		);
	}

	@Nonnull
	@Override
	public IndexDetail describeIndex(int indexPrimaryKey) throws IndexNotFoundException {
		final Scope scope = CatalogIndexProjection.toScope(indexPrimaryKey);
		// two distinct misses answered alike, deliberately: a handle that addresses no scope at all, and one that
		// addresses a scope whose index has not been created yet. Both mean "the catalog holds no such index right now",
		// and the second can start resolving later without ever denoting a different index
		final CatalogIndex catalogIndex = scope == null ? null : getCatalogIndexIfExits(scope).orElse(null);
		if (catalogIndex == null) {
			throw new IndexNotFoundException(null, indexPrimaryKey);
		}
		return CatalogIndexProjection.describe(catalogIndex);
	}

	@Nonnull
	@Override
	public List<SchemaCapabilityUsageStatistics> listCapabilityUsage() {
		// null owner rather than this catalog's name: the field names the entity collection a row belongs to, and these
		// rows belong to none - they describe attributes the catalog schema declares itself
		return SchemaCapabilityUsageProjection.project(null, this.usageRegistry, isUsageStatisticsTracked());
	}

	/**
	 * Sums the record counts of every collection in the catalog. `totalRecords` keeps its historical meaning - the
	 * number of entity body storage parts - while the live/archived split is read from the cardinality of the global
	 * index of each scope. Both are counter reads rather than walks, which is what allows this component to stay
	 * catalog-level.
	 *
	 * The two are *not* guaranteed to reconcile: a body part that belongs to neither global index counts towards
	 * `totalRecords` alone, and surfacing that difference is the point of reporting all three numbers.
	 *
	 * @return the {@link CatalogStatisticsComponent#RECORD_COUNTS} component
	 */
	@Nonnull
	private RecordCounts countRecords() {
		long totalRecords = 0L;
		long liveRecords = 0L;
		long archivedRecords = 0L;
		for (final EntityCollection collection : this.entityCollections.values()) {
			final CollectionRecordCounts collectionCounts = collection.countRecords();
			totalRecords += collectionCounts.totalRecords();
			liveRecords += collectionCounts.liveRecords();
			archivedRecords += collectionCounts.archivedRecords();
		}
		return new RecordCounts(totalRecords, liveRecords, archivedRecords);
	}

	/**
	 * Counts the sessions currently open against this catalog.
	 *
	 * The registry is owned by the engine rather than by the catalog, because it is created lazily on the first
	 * session and outlives every individual catalog generation. A catalog nobody has ever opened a session against
	 * has no registry at all, which is honestly reported as three zeroes rather than as an unavailable component -
	 * "no sessions" is exactly what it means.
	 *
	 * @return the {@link CatalogStatisticsComponent#SESSIONS} component
	 */
	@Nonnull
	private SessionStatistics countSessions() {
		return this.evita.getCatalogSessionRegistry(getName())
			.map(SessionRegistry::countActiveSessions)
			.orElse(NO_ACTIVE_SESSIONS);
	}

	/**
	 * Why {@link CatalogStatisticsComponent#COMMIT_PIPELINE} and {@link CatalogStatisticsComponent#ACTIVITY} are both
	 * withheld while the catalog is writing in bulk.
	 *
	 * The two components report different things but are unavailable for one and the same reason - bulk writes never
	 * enter the pipeline that either of them measures - so the sentence is written once rather than twice. Two copies
	 * of one explanation drift, and these two already had: identical in what they produced, differing in where the
	 * line was wrapped.
	 *
	 * @return the explanation handed to the client alongside {@link ComponentAvailability#FEATURE_DISABLED}
	 * @param catalogState current state of the catalog
	 */
	@Nonnull
	private static String bulkWriteModeExplanation(@Nonnull CatalogState catalogState) {
		return "Catalog is in `" + catalogState + "` state, where writes are applied in bulk and the " +
			"transactional commit pipeline is not used.";
	}

	/**
	 * Describes how far back this catalog can be read and what is keeping superseded files on disk.
	 *
	 * **The window is the honest one, not the one the bootstrap file lists.** The bootstrap file is never trimmed, so
	 * it names every version the catalog has ever had in both modes - but with time travel disabled
	 * `purgeAllObsoleteFiles` removes every data file the *current* header does not reference, so those older versions
	 * have nothing left to read. Reporting the bootstrap's oldest record as the start of the window would therefore
	 * promise history that is not there; with time travel off the window is the current version alone.
	 *
	 * The file counts, the byte classes and the reader floor all come from the footprint the caller already measured -
	 * see the comment at that call site for why they must not be measured again here. The *bootstrap* file is read
	 * separately, and with time travel on it is read once per direction: the pagination is directional, so one call
	 * cannot yield both ends of the window. Only the directory listing is shared with
	 * {@link CatalogStatisticsComponent#STORAGE_SIZE}, and that is what the one-snapshot-per-request rule is about -
	 * these are two bounded seek-reads of one small file, which is the cost {@link HistoryStatistics} documents.
	 *
	 * @param footprint the catalog directory listing the caller measured for this request
	 * @return the {@link CatalogStatisticsComponent#HISTORY} component
	 */
	@Nonnull
	private HistoryStatistics describeHistory(@Nonnull CatalogStorageFootprint footprint) {
		final boolean timeTravelEnabled = this.evitaConfiguration.storage().timeTravelEnabled();
		final List<MaterializedVersionBlock> newest = this.persistenceService
			.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 1, 1)
			.getData();
		final MaterializedVersionBlock newestBlock = newest.isEmpty() ? null : newest.get(0);
		final MaterializedVersionBlock oldestBlock;
		if (timeTravelEnabled) {
			final List<MaterializedVersionBlock> oldest = this.persistenceService
				.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 1)
				.getData();
			oldestBlock = oldest.isEmpty() ? null : oldest.get(0);
		} else {
			// only the current version's data files survive the purge, so the window has one version in it
			oldestBlock = newestBlock;
		}
		return new HistoryStatistics(
			timeTravelEnabled,
			oldestBlock == null ? -1L : (timeTravelEnabled ? oldestBlock.startVersion() : oldestBlock.endVersion()),
			oldestBlock == null ? null : oldestBlock.introducedAt(),
			newestBlock == null ? -1L : newestBlock.endVersion(),
			newestBlock == null ? null : newestBlock.introducedAt(),
			footprint.walFileCount(),
			footprint.walBytes(),
			footprint.activeReaderFloor(),
			footprint.awaitingDeletionFileCount(),
			footprint.awaitingDeletionBytes(),
			footprint.blockedByActiveReaderBytes(),
			footprint.purgeableBytes()
		);
	}

	/**
	 * Sums what every data store of this catalog is holding in memory rather than on disk - the catalog's own store
	 * plus each collection's.
	 *
	 * **This one *is* summed across data stores, unlike {@link CatalogStatisticsComponent#STORAGE_COMPOSITION}.** The
	 * difference is not an inconsistency: bytes held in heap add up no matter which store holds them, whereas adding
	 * counts of different storage-part types out of different stores yields a number with no meaning. The retained
	 * history timestamp is folded with `min` rather than summed - the catalog is holding history back as far as its
	 * oldest retaining store.
	 *
	 * @return the {@link CatalogStatisticsComponent#VOLATILE_STATE} component
	 */
	@Nonnull
	private VolatileStateStatistics measureVolatileState() {
		// the catalog's own data store is measured on its own and *kept*, not just used to seed the fold - it is the
		// slice that lets a client tell an unflushed backlog in the metadata store from one in a collection
		final VolatileDataFootprint catalogDataStore = this.persistenceService.measureVolatileData();
		VolatileDataFootprint footprint = catalogDataStore;
		for (final EntityCollection collection : this.entityCollections.values()) {
			footprint = footprint.plus(collection.measureVolatileData());
		}
		return new VolatileStateStatistics(
			footprint.totalSizeIncludingVolatileDataBytes(),
			footprint.nonFlushedRecordCount(),
			footprint.nonFlushedSizeBytes(),
			footprint.oldestRecordKeptTimestamp(),
			VolatileStateProjection.toDataStoreVolatileState(catalogDataStore)
		);
	}

	/**
	 * Breaks the catalog's own data store down by storage-part type - the file holding the catalog schema, the
	 * headers and the catalog-level indexes. There is deliberately no sum across the entity collections: each keeps
	 * its records - its entity schema included - in its own data store, and adding records of different types out of
	 * different data stores produces a number with no operational meaning. A collection's own breakdown is fetched
	 * through its collection-level snapshot.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component
	 */
	@Nonnull
	private StorageCompositionStatistics composeStorageParts() {
		return new StorageCompositionStatistics(
			StoragePartProjection.toStoragePartUsage(this.persistenceService.measureStoragePartComposition())
		);
	}

	/**
	 * Describes who this catalog is and what mode it runs in. Shared by the catalog-level and the collection-level
	 * statistics snapshots, so a client can tell which catalog version each of them observed.
	 *
	 * @return the identity component of the statistics model
	 */
	@Nonnull
	public CatalogIdentity getIdentity() {
		final CatalogState catalogState = getCatalogState();
		return new CatalogIdentity(
			getCatalogId(),
			getName(),
			catalogState,
			getVersion(),
			this.readOnly.get(),
			!catalogState.isActive(),
			supportsTransaction(),
			isGoingLive(),
			this.entityCollections.size()
		);
	}

	/**
	 * Lists the entity collections the catalog holds. Carries no statistics - it is the inventory a client needs
	 * before it can ask any single collection for its numbers.
	 *
	 * @return the {@link CatalogStatisticsComponent#COLLECTIONS} component
	 */
	@Nonnull
	private CollectionsInfo collectCollectionInventory() {
		final CollectionInfo[] collections = new CollectionInfo[this.entityCollections.size()];
		int index = 0;
		for (final EntityCollection collection : this.entityCollections.values()) {
			collections[index++] = new CollectionInfo(
				collection.getEntityType(),
				collection.getEntityTypePrimaryKey()
			);
		}
		return new CollectionsInfo(collections);
	}

	/**
	 * Counts the indexes of the whole catalog. Each collection answers from the size of its index map, so the cost is
	 * independent of how large those indexes are - which is what allows this component to stay catalog-level.
	 *
	 * **The catalog-level index is one per {@link Scope}, not one per catalog.** `LIVE` always exists; `ARCHIVED` is
	 * created lazily by {@link #getCatalogIndex(Scope)} the first time something is indexed in that scope, so a
	 * catalog holding archived globally-unique data has two. Counting a hard-coded one undercounted every such
	 * catalog, and would undercount further the day a third scope is added - hence the loop over
	 * {@link Scope#values()} rather than a constant.
	 *
	 * The number counts index *instances*, not non-empty ones: the `LIVE` catalog index exists from the moment the
	 * catalog does, whether or not any globally-unique attribute has ever been written to it.
	 *
	 * @return number of indexes including every scope's catalog-level index
	 */
	private long countIndexes() {
		// every scope whose catalog-level index has actually been created, then every collection adds its own
		long totalIndexCount = 0L;
		for (final Scope scope : Scope.values()) {
			if (getCatalogIndexIfExits(scope).isPresent()) {
				totalIndexCount++;
			}
		}
		for (final EntityCollection collection : this.entityCollections.values()) {
			totalIndexCount += collection.getIndexCount();
		}
		return totalIndexCount;
	}

	/**
	 * Collects the catalog-level index of every scope that has actually been created.
	 *
	 * A scope whose index has never been created contributes nothing rather than an empty entry - the `ARCHIVED` index
	 * is created lazily, and reporting it as present-but-empty would be indistinguishable from a scope that exists and
	 * genuinely holds no globally-unique value.
	 *
	 * @return the existing catalog indexes, in {@link Scope#values()} order
	 */
	@Nonnull
	private List<CatalogIndex> collectCatalogIndexes() {
		final Scope[] scopes = Scope.values();
		final List<CatalogIndex> catalogIndexes = new ArrayList<>(scopes.length);
		for (final Scope scope : scopes) {
			getCatalogIndexIfExits(scope).ifPresent(catalogIndexes::add);
		}
		return catalogIndexes;
	}

	@Override
	public void terminate() {
		Assert.isPremiseValid(!isTerminated(), "Catalog is already terminated!");
		try {
			terminateInternally();
		} finally {
			this.persistenceService.close();
		}
	}

	@Override
	public boolean isTerminated() {
		return this.persistenceService.isClosed();
	}

	/**
	 * Sets the read-only state of the object.
	 *
	 * @param readOnly a boolean value indicating whether the object should be set to read-only (true)
	 *                 or writable (false).
	 */
	public void setReadOnly(boolean readOnly) {
		this.readOnly.set(readOnly);
	}

	/**
	 * Applies a given {@link Mutation} to the appropriate target within the system.
	 * The method determines the type of the mutation and performs the required action,
	 * such as applying a schema mutation to the catalog or applying an entity mutation
	 * to the relevant entity collection.
	 *
	 * @param mutation the mutation instance to be applied; must not be null
	 * @throws InvalidMutationException if the mutation type is not recognized or supported
	 */
	public void applyMutation(
		@Nonnull EvitaContract evita, @Nonnull Mutation mutation) throws InvalidMutationException {
		if (mutation instanceof LocalCatalogSchemaMutation schemaMutation) {
			// apply schema mutation to the catalog
			updateSchema(evita, null, schemaMutation);
		} else if (mutation instanceof EntityMutation entityMutation) {
			getCollectionForEntityOrThrowException(entityMutation.getEntityType())
				.applyMutation(entityMutation);
		} else {
			throw new InvalidMutationException(
				"Unexpected mutation type: " + mutation.getClass().getName(),
				"Unexpected mutation type."
			);
		}
	}

	@Nonnull
	public Optional<EntityCollection> getCollectionForEntityInternal(@Nonnull String entityType) {
		return ofNullable(this.entityCollections.get(entityType));
	}

	/**
	 * Returns reference to the main catalog index that allows fast lookups for entities across all types or empty
	 * value if the index does not exist.
	 */
	@Nonnull
	public Optional<CatalogIndex> getCatalogIndexIfExits(@Nonnull Scope scope) {
		return scope == Scope.ARCHIVED ? ofNullable(this.archiveCatalogIndex.get()) : of(this.catalogIndex);
	}

	/**
	 * Returns reference to the main catalog index that allows fast lookups for entities across all types.
	 */
	@Nonnull
	public CatalogIndex getCatalogIndex(@Nonnull Scope scope) {
		if (scope == Scope.ARCHIVED) {
			// The archived index is lazily initialized on first archived-scope access. Create the candidate before
			// publishing it, then CAS it in; a candidate that loses the race is simply discarded (and GC'd). The
			// catalog index no longer holds a catalog back-reference, so no attach step is needed here.
			CatalogIndex existing = this.archiveCatalogIndex.get();
			if (existing == null) {
				final CatalogIndex candidate = new CatalogIndex(
					Scope.ARCHIVED, this.evitaConfiguration.server().usageStatisticsTracking()
				);
				existing = this.archiveCatalogIndex.compareAndSet(null, candidate) ?
					candidate : this.archiveCatalogIndex.get();
			}
			return existing;
		} else {
			return this.catalogIndex;
		}
	}

	/**
	 * The per-capability usage counters of the **catalog schema's own attributes** - see {@link #usageRegistry} for
	 * what they mean and how long they live. Like the collection-level registries, this is the same instance for every
	 * version of one logical catalog, so a caller may hold on to it across a commit; what it must not do is read the
	 * numbers as belonging to a particular catalog version.
	 *
	 * @return the registry counting the capabilities of this catalog's global attributes
	 */
	@Nonnull
	public SchemaCapabilityUsageRegistry getUsageRegistry() {
		return this.usageRegistry;
	}

	/**
	 * Whether this catalog counts how often its indexes and schema capabilities are queried against how often they are
	 * maintained - the server-wide `server.usageStatisticsTracking` switch, answered here so that the index-creation
	 * sites and the query and write paths all read the one value rather than each reaching for the configuration.
	 *
	 * It is deliberately **not** re-read per index: a catalog holding some indexes that observe and some that do not
	 * would report two different meanings for the same zero, and the switch cannot change under a running catalog.
	 *
	 * @return true when the usage counters are maintained
	 */
	public boolean isUsageStatisticsTracked() {
		return this.evitaConfiguration.server().usageStatisticsTracking();
	}

	/**
	 * Returns {@link EntitySchema} for passed `entityType` or throws {@link IllegalArgumentException} if schema for
	 * this type is not yet known.
	 */
	@Nonnull
	public <T extends EntityIndex> Optional<T> getEntityIndexIfExists(
		@Nonnull String entityType, @Nonnull EntityIndexKey indexKey, @Nonnull Class<T> expectedType) {
		final EntityCollection targetCollection = ofNullable(this.entityCollections.get(entityType))
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final EntityIndex entityIndex = targetCollection.getIndexByKeyIfExists(indexKey);
		if (entityIndex == null) {
			return empty();
		} else if (expectedType.isInstance(entityIndex)) {
			//noinspection unchecked
			return of((T) entityIndex);
		} else {
			throw new GenericEvitaInternalError(
				"Expected index of type " + expectedType.getName() + " but got " + entityIndex.getClass()
					.getName() + ".",
				"Expected different type of entity index."
			);
		}
	}

	/**
	 * Returns internally held {@link CatalogSchema}.
	 */
	@Nonnull
	public CatalogSchema getInternalSchema() {
		return Objects.requireNonNull(this.schema.get()).getDelegate();
	}

	/**
	 * Returns the catalog-level expression trigger registry. Used by `EntityIndexLocalMutationExecutor`
	 * post-processing to discover which cross-entity triggers need to fire when a mutation occurs.
	 *
	 * @return the current expression trigger registry (never `null`)
	 */
	@Nonnull
	public CatalogExpressionTriggerRegistry getExpressionTriggerRegistry() {
		return Objects.requireNonNull(this.expressionTriggerRegistry.get());
	}

	/**
	 * Updates schema in the map index on schema change, and rebuilds the expression trigger registry
	 * for the changed entity type.
	 *
	 * The registry rebuild uses the fully-resolved schema (including reflected reference inheritance)
	 * because this method is called after `refreshReflectedSchemas()` has resolved inheritance via
	 * the call chain: `updateSchema()` -> `refreshReflectedSchemas()` -> `exchangeSchema()` ->
	 * `entitySchemaUpdated()`. No additional cascade mechanism is needed for `ReflectedReferenceSchema`
	 * because the existing `EntityCollection.notifyAboutExternalReferenceUpdate()` already cascades
	 * schema changes to all collections with reflected references, each firing `entitySchemaUpdated()`.
	 *
	 * When called from within a batched `updateSchema(...)` call (a frame is present on
	 * {@link #PENDING_TRIGGER_REBUILDS}), the rebuild is deferred until after all mutations in the
	 * batch have been applied — cross-entity trigger validation then sees the final, consistent
	 * schema rather than a transient intermediate state.
	 *
	 * @param entitySchema updated entity schema
	 */
	public void entitySchemaUpdated(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchemaIndex.put(entitySchema.getName(), entitySchema);
		markEntityTypeForTriggerRebuild(entitySchema.getName());
	}

	/**
	 * Removes the entity schema from the map index, and purges all triggers owned by the removed
	 * entity type from the expression trigger registry. If a batched `updateSchema(...)` call is
	 * currently running, the purge is deferred like in {@link #entitySchemaUpdated}.
	 *
	 * @param entityType the type of the entity schema to be removed
	 */
	public void entitySchemaRemoved(@Nonnull String entityType) {
		this.entitySchemaIndex.remove(entityType);
		markEntityTypeForTriggerRebuild(entityType);
	}

	@Override
	public DataStoreChanges createLayer() {
		// no dirty-index-key capture: the catalog-level index map is merged whole, never pruned
		return new DataStoreChanges(
			Transaction.createTransactionalPersistenceService(
				this.persistenceService.getStoragePartPersistenceService(getVersion())
			)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.schema.removeLayer(transactionalLayer);
		this.entityCollections.removeLayer(transactionalLayer);
		this.catalogIndex.removeLayer(transactionalLayer);
		final CatalogIndex theArchiveCatalogIndex = this.archiveCatalogIndex.get();
		if (theArchiveCatalogIndex != null) {
			theArchiveCatalogIndex.removeLayer(transactionalLayer);
		}
		this.entityCollectionsByPrimaryKey.removeLayer(transactionalLayer);
		this.entitySchemaIndex.removeLayer(transactionalLayer);
		this.expressionTriggerRegistry.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public Catalog createCopyWithMergedTransactionalMemory(
		@Nullable DataStoreChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		/* the version is incremented via. {@link #setVersion} method */
		final long newCatalogVersionId = transactionalLayer.getStateCopyWithCommittedChanges(this.versionId)
			.orElseThrow();
		final CatalogSchemaDecorator newSchema = transactionalLayer.getStateCopyWithCommittedChanges(this.schema)
			.orElseThrow();
		final DataStoreChanges transactionalChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(this);

		final MapChanges<String, EntityCollection> collectionChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(
			this.entityCollections);
		if (collectionChanges != null) {
			// recognize renamed collections
			final Map<String, EntityCollection> originalCollectionContents = collectionChanges.getMapDelegate();
			final ObjectObjectIdentityHashMap<EntityCollection, String> originalCollections = new ObjectObjectIdentityHashMap<>(
				collectionChanges.getRemovedKeys().size());
			for (String removedKey : collectionChanges.getRemovedKeys()) {
				originalCollections.put(collectionChanges.getMapDelegate().get(removedKey), removedKey);
			}
			for (Entry<String, EntityCollection> updatedKey : collectionChanges.getModifiedKeys().entrySet()) {
				final EntityCollection updatedCollection = updatedKey.getValue();
				final String removedEntityType = originalCollections.get(updatedCollection);
				final String newEntityType = updatedKey.getKey();
				if (removedEntityType != null) {
					final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> newPersistenceService =
						this.persistenceService.replaceCollectionWith(
							newCatalogVersionId, removedEntityType,
							updatedCollection.getEntityTypePrimaryKey(),
							newEntityType
						);
					this.entityCollections.put(
						updatedKey.getKey(),
						updatedKey.getValue().createCopyWithNewPersistenceService(
							newCatalogVersionId, CatalogState.ALIVE, newPersistenceService
						)
					);
					originalCollections.remove(updatedCollection);
					ofNullable(originalCollectionContents.get(newEntityType)).ifPresent(
						it -> it.removeLayer(transactionalLayer));
				}
			}
			for (ObjectObjectCursor<EntityCollection, String> originalItem : originalCollections) {
				this.persistenceService.deleteEntityCollection(
					newCatalogVersionId,
					originalItem.key.getEntityCollectionHeader()
				);
				originalCollectionContents.get(originalItem.value).removeLayer(transactionalLayer);
			}

			// update catalog header with new entity collection headers
			this.persistenceService.updateEntityCollectionHeaders(
				newCatalogVersionId,
				this.entityCollections.values()
					.stream()
					.map(EntityCollection::getEntityCollectionHeader)
					.toArray(EntityCollectionHeader[]::new)
			);
		}

		final Map<String, EntityCollection> possiblyUpdatedCollections = transactionalLayer.getStateCopyWithCommittedChanges(
			this.entityCollections);
		final CatalogIndex possiblyUpdatedCatalogIndex = transactionalLayer.getStateCopyWithCommittedChanges(
			this.catalogIndex);
		final CatalogIndex theArchiveCatalogIndex = this.archiveCatalogIndex.get();
		final CatalogIndex possiblyUpdatedArchiveCatalogIndex = theArchiveCatalogIndex == null ?
			null :
			of(transactionalLayer.getStateCopyWithCommittedChanges(theArchiveCatalogIndex))
				.filter(it -> !it.isEmpty())
				.orElse(null);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.entityCollectionsByPrimaryKey);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.entitySchemaIndex);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.expressionTriggerRegistry);

		if (transactionalChanges != null) {
			final StoragePartPersistenceService<?> storagePartPersistenceService = this.persistenceService.getStoragePartPersistenceService(
				newCatalogVersionId);
			if (newSchema.version() != this.lastPersistedSchemaVersion) {
				final CatalogSchemaStoragePart storagePart = new CatalogSchemaStoragePart(newSchema.getDelegate());
				storagePartPersistenceService.putStoragePart(storagePart.getStoragePartPK(), storagePart);
			}

			// when we register all storage parts for persisting we can now release transactional memory
			transactionalLayer.removeTransactionalMemoryLayer(this);

			return new Catalog(
				newCatalogVersionId,
				getCatalogState(),
				possiblyUpdatedCatalogIndex,
				possiblyUpdatedArchiveCatalogIndex,
				possiblyUpdatedCollections.values(),
				this
			);
		} else {
			if (possiblyUpdatedCatalogIndex != this.catalogIndex ||
				possiblyUpdatedArchiveCatalogIndex != theArchiveCatalogIndex ||
				possiblyUpdatedCollections
					.entrySet()
					.stream()
					.anyMatch(it -> this.entityCollections.get(it.getKey()) != it.getValue())
			) {
				return new Catalog(
					newCatalogVersionId,
					getCatalogState(),
					possiblyUpdatedCatalogIndex,
					possiblyUpdatedArchiveCatalogIndex,
					possiblyUpdatedCollections.values(),
					this
				);
			} else {
				// no changes present we can return self
				return this;
			}
		}
	}

	/**
	 * Commits a Write-Ahead Log (WAL) for and processes the transaction.
	 *
	 * @param sessionCatalogVersion                  The catalog version the session is operating on (SNAPSHOT isolation version).
	 * @param transactionId                          The ID of the transaction to commit.
	 * @param catalogSchemaVersionAtTransactionStart catalog schema version valid at transaction start
	 * @param walPersistenceService                  The Write-Ahead Log persistence service.
	 * @param commitProgress                         The record that allows tracking the commit progress.
	 * @throws TransactionException If an unknown exception occurs while processing the transaction.
	 */
	public void commitWal(
		long sessionCatalogVersion,
		@Nonnull UUID transactionId,
		int catalogSchemaVersionAtTransactionStart,
		@Nonnull IsolatedWalPersistenceService walPersistenceService,
		@Nonnull CommitProgressRecord commitProgress
	) {
		try {
			this.transactionManager.commit(
				sessionCatalogVersion,
				transactionId,
				this.getSchema().version() - catalogSchemaVersionAtTransactionStart,
				walPersistenceService,
				commitProgress
			);
		} catch (Exception e) {
			if (e.getCause() instanceof TransactionException txException) {
				throw txException;
			} else {
				throw new TransactionException(
					"Unknown exception occurred while processing transaction!", e
				);
			}
		}
	}

	/**
	 * Retrieves the stream of committed mutations since the given catalogVersion. The first mutation in the stream
	 * will be {@link TransactionMutation} that evolved the catalog to the catalogVersion plus one. This method differs
	 * from {@link #getCommittedMutationStream(long)} in that it expects the WAL is being actively written to and
	 * the returned stream may be potentially infinite.
	 *
	 * @param startCatalogVersion     the catalog version to start reading from
	 * @param requestedCatalogVersion the minimal catalog version to finish reading
	 * @return The stream of committed mutations since the given catalogVersion
	 */
	@Nonnull
	public Stream<CatalogBoundMutation> getCommittedLiveMutationStream(
		long startCatalogVersion, long requestedCatalogVersion) {
		return this.persistenceService.getCommittedLiveMutationStream(startCatalogVersion, requestedCatalogVersion);
	}

	/**
	 * Retrieves the last catalog version written in the WAL stream.
	 *
	 * @return the last catalog version written in the WAL stream
	 */
	public long getLastCatalogVersionInMutationStream() {
		return this.persistenceService.getLastCatalogVersionInMutationStream();
	}

	/**
	 * Retrieves the first catalog version present in the current WAL file segment. Primarily useful for diagnostic
	 * logging that needs to pin down the lower bound of the replay window reachable from the current WAL file.
	 *
	 * @return the first catalog version in the current WAL file, or `-1` if the current WAL file is empty
	 */
	public long getFirstCatalogVersionInMutationStream() {
		return this.persistenceService.getFirstCatalogVersionInMutationStream();
	}

	/**
	 * Method allows to immediately flush all information held in memory to the persistent storage.
	 * This method might do nothing particular in transaction ({@link CatalogState#ALIVE}) mode.
	 * Method stores {@link EntityCollectionHeader} in case there were any changes in the file offset index executed
	 * in BULK / non-transactional mode.
	 */
	@Nonnull
	public ProgressingFuture<Void> flush() {
		// if we're going live start with TRUE (force flush), otherwise start with false
		final boolean changeOccurred = getInternalSchema().version() != this.lastPersistedSchemaVersion;
		Assert.isPremiseValid(
			getCatalogState() == CatalogState.WARMING_UP,
			"Cannot flush catalog in transactional mode. Any changes could occur only in transaction!"
		);

		// this pops the CATALOG's own trapped changes here and now, on this thread, before a single collection future is
		// even constructed - while they are written only in the combine step below, once every collection has flushed.
		// A collection whose write fails therefore strands these already-popped changes with no combine step to persist
		// them, so the catalog buffer must be poisoned alongside the collection's own (see whenComplete below).
		final TrappedChanges trappedChanges = this.dataStoreBuffer.popTrappedChanges();
		final ProgressingFuture<Void> flushFuture = new ProgressingFuture<>(
			trappedChanges.getTrappedChangesCount(),
			// first flush all entity collections
			this.entityCollections
				.values()
				.stream()
				.map(EntityCollection::createFlushFuture)
				.toList(),
			// when all entity collections are flushed, we can update the catalog header
			(progress, collectionOfHeaders) -> {
				final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(collectionOfHeaders.size());
				// start with the changeOccurred flag, which is true is schema was changed or if we are going live
				boolean resolvedChangeOccurred = changeOccurred;
				for (EntityCollectionHeaderWithCollection collectionOfHeader : collectionOfHeaders) {
					// collect correct headers for each collection
					entityHeaders.add(updateIndexIfNecessary(collectionOfHeader));
					// set it to true if any of the collections changed
					resolvedChangeOccurred = resolvedChangeOccurred || collectionOfHeader.changeOccurred();
				}
				// if any of the collections changed, we need to flush the trapped changes and store the catalog header
				if (resolvedChangeOccurred) {
					this.persistenceService.flushTrappedUpdates(
						0L,
						trappedChanges,
						progress::updateProgress
					);

					final CatalogHeader<LogRecordReference, CollectionReference> catalogHeader =
						this.persistenceService.getCatalogHeader(0L);
					Assert.isPremiseValid(
						catalogHeader != null && catalogHeader.catalogState() == CatalogState.WARMING_UP,
						"Catalog header is expected to be present in the storage in WARMING_UP flag!"
					);
					this.persistenceService.storeHeader(
						this.catalogId,
						getCatalogState(),
						0L,
						this.entityTypeSequence.get(),
						null,
						entityHeaders,
						this.dataStoreBuffer
					);
					this.lastPersistedSchemaVersion = getInternalSchema().version();
				}
				return null;
			},
			Functions.noOpConsumer()
		);
		// whether a collection's write failed or the combine step itself did, this catalog's own popped changes are lost
		// either way: refuse every later catalog flush rather than store a header describing a state never written
		flushFuture.whenComplete(
			(result, ex) -> {
				if (ex != null) {
					this.dataStoreBuffer.poison(ex);
				}
			}
		);
		return flushFuture;
	}

	/**
	 * Returns the newest catalog version that has actually been checkpointed to disk. Tells apart a failure that
	 * struck before its version was durable from one that struck after.
	 *
	 * This lags {@link #getVersion()} by an entire checkpoint interval when one is configured - not merely for the
	 * duration of a commit in flight. Everything above it is recovered by replaying the write-ahead log, so the gap
	 * is bounded but is measured in seconds rather than milliseconds.
	 *
	 * @return the last catalog version whose checkpoint completed
	 */
	public long getLastPersistedCatalogVersion() {
		return this.persistenceService.getLastCatalogVersion();
	}

	/**
	 * This method writes all changed storage parts into the file offset index of {@link EntityCollection} and then stores
	 * {@link CatalogHeader} marking the catalog version as committed.
	 */
	public void flush(long catalogVersion, @Nonnull TransactionMutation lastProcessedTransaction) {
		Assert.isPremiseValid(getCatalogState() == CatalogState.ALIVE, "Catalog is not in ALIVE state!");
		// the last APPLIED version, not the last checkpointed one: with a checkpoint interval configured the latter
		// lags by up to that interval, which would make every round in between look like a change
		boolean changeOccurred = this.persistenceService.getLastAppliedCatalogVersion() != catalogVersion ||
			getInternalSchema().version() != this.lastPersistedSchemaVersion;
		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(this.entityCollections.size());
		for (EntityCollection entityCollection : this.entityCollections.values()) {
			final long lastSeenVersion = entityCollection.getVersion();
			entityHeaders.add(entityCollection.flush(catalogVersion));
			changeOccurred = changeOccurred || entityCollection.getVersion() != lastSeenVersion;
		}

		if (changeOccurred) {
			this.persistenceService.flushTrappedUpdates(
				catalogVersion,
				this.dataStoreBuffer.popTrappedChanges(),
				Functions.noOpIntConsumer()
			);
			this.persistenceService.storeHeader(
				this.catalogId,
				CatalogState.ALIVE,
				catalogVersion,
				this.entityTypeSequence.get(),
				lastProcessedTransaction,
				entityHeaders,
				this.dataStoreBuffer
			);
			this.lastPersistedSchemaVersion = getInternalSchema().version();
		}
	}

	/**
	 * Creates an isolated WAL (Write-Ahead Log) service for the specified transaction ID.
	 *
	 * @param transactionId The ID of the transaction.
	 * @return The IsolatedWalPersistenceService instance for the specified transaction ID.
	 */
	@Nonnull
	public IsolatedWalPersistenceService createIsolatedWalService(@Nonnull UUID transactionId) {
		// thread the living schema so the WAL write path resolves the effective, schema-declared conflict
		// resolution per entity type
		return this.persistenceService.createIsolatedWalPersistenceService(
			transactionId,
			getInternalSchema(),
			entityType -> getEntitySchema(entityType).orElse(null)
		);
	}

	/**
	 * Appends the given transaction mutation to the write-ahead log (WAL) and appends its mutation chain taken from
	 * offHeapWithFileBackupReference. After that it discards the specified off-heap data with file backup reference.
	 *
	 * The append is left merely **written** rather than durable - the caller must pair it with {@link #syncWal()}
	 * before the transaction may be acknowledged or checkpointed. That is what allows one device sync to cover
	 * a whole batch of transactions instead of one each.
	 *
	 * @param transactionMutation The transaction mutation to append to the WAL.
	 * @param walReference        The off-heap data with file backup reference to discard.
	 * @return the number of Bytes written
	 */
	public long appendWalAndDiscardDeferringSync(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull LogRecordReference walReference
	) {
		return this.persistenceService.appendWalAndDiscardDeferringSync(
			getVersion(), transactionMutation, walReference
		);
	}

	/**
	 * Makes everything appended to this catalog's WAL so far durable.
	 */
	public void syncWal() {
		this.persistenceService.syncWal();
	}

	/**
	 * Notifies the system that a catalog is present in the live view.
	 * This method is used to indicate that a catalog is currently available in the live view.
	 */
	public void notifyCatalogPresentInLiveView() {
		this.transactionManager.notifyCatalogPresentInLiveView(this);
	}

	/**
	 * Method for internal use - allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents() {
		try {
			this.persistenceService.emitObservabilityEvents();
			this.transactionManager.emitObservabilityEvents();
		} catch (Throwable t) {
			log.error("Emitting observability events failed!", t);
		}
	}

	/**
	 * We need to forget all volatile data when the data written to catalog aren't going to be committed (incorporated
	 * in the final state).
	 *
	 * @see CatalogPersistenceService#forgetVolatileData()
	 */
	public void forgetVolatileData() {
		if (!this.persistenceService.isClosed()) {
			try {
				this.persistenceService.forgetVolatileData();
			} catch (PersistenceServiceClosed ignored) {
				// this might be ok in race conditions when evita is getting shut down
				// but in such case we don't need to forget volatile data anymore
				log.warn("Persistence service was closed during forgetting volatile data.");
			}
		}
	}

	@Override
	public void catalogConsumersLeft(
		long lastKnownMinimalActiveVersionRead,
		long lastKnownMinimalActiveVersionWritten
	) {
		// we may now release conflict keys, there is no active transaction that may need them
		this.transactionManager.releaseConflictKeys(lastKnownMinimalActiveVersionWritten);
		// notify persistence service as well
		if (this.persistenceService instanceof CatalogConsumersListener cvbthl) {
			cvbthl.catalogConsumersLeft(
				lastKnownMinimalActiveVersionRead,
				lastKnownMinimalActiveVersionWritten
			);
		}
	}

	@Override
	public void catalogVersionPinned(long catalogVersion) {
		if (this.persistenceService instanceof CatalogConsumersListener cvbthl) {
			cvbthl.catalogVersionPinned(catalogVersion);
		}
	}

	@Override
	public void catalogVersionReleased(long catalogVersion) {
		if (this.persistenceService instanceof CatalogConsumersListener cvbthl) {
			cvbthl.catalogVersionReleased(catalogVersion);
		}
	}

	/**
	 * Retrieves the effective conflict resolution associated with the transaction configuration.
	 *
	 * @return a non-null {@link ConflictResolution} representing the effective conflict resolution.
	 */
	@Nonnull
	public ConflictResolution getConflictResolution() {
		return this.transactionManager.getConflictResolution();
	}

	/**
	 * Marks the given entity type as dirty for trigger-registry rebuild. If a batch frame is
	 * active on {@link #PENDING_TRIGGER_REBUILDS} the entity type is appended there and the
	 * rebuild is deferred to the end of the enclosing `updateSchema(...)` call; otherwise the
	 * rebuild runs immediately so single-mutation callers keep their existing eager semantics.
	 *
	 * @param entityType the entity type whose triggers may have become stale
	 */
	private void markEntityTypeForTriggerRebuild(@Nonnull String entityType) {
		final Set<String> frame = PENDING_TRIGGER_REBUILDS.get().peek();
		if (frame != null) {
			frame.add(entityType);
		} else {
			rebuildExpressionTriggerRegistryForEntityType(entityType);
		}
	}

	/**
	 * Rebuilds the expression trigger registry for the specified entity type. If the entity type's schema exists
	 * in the schema index, builds triggers from all its reference schemas. If the schema does not exist (entity
	 * removed), passes an empty trigger list to purge stale triggers.
	 *
	 * @param entityType the owner entity type whose triggers should be rebuilt
	 */
	private void rebuildExpressionTriggerRegistryForEntityType(@Nonnull String entityType) {
		final CatalogExpressionTriggerRegistry currentRegistry = Objects.requireNonNull(
			this.expressionTriggerRegistry.get()
		);
		final EntitySchemaContract entitySchema = this.entitySchemaIndex.get(entityType);
		final List<ExpressionIndexTrigger> newTriggers;
		if (entitySchema == null) {
			newTriggers = Collections.emptyList();
		} else {
			final List<ExpressionIndexTrigger> collected = new ArrayList<>(4);
			for (final ReferenceSchemaContract refSchema : entitySchema.getReferences().values()) {
				collected.addAll(
					FacetExpressionTriggerFactory.buildTriggersForReference(entityType, refSchema)
				);
				collected.addAll(
					HistogramExpressionTriggerFactory.buildTriggersForReference(
						entityType, refSchema, this.entitySchemaIndex::get
					)
				);
			}
			newTriggers = collected;
		}
		this.expressionTriggerRegistry.set(
			currentRegistry.rebuildForEntityType(entityType, newTriggers)
		);
	}

	/**
	 * Builds the initial expression trigger registry by scanning all entity schemas in the schema index.
	 * Called after all `initSchema()` calls complete during catalog loading (cold start) or in the copy
	 * constructor when schemas are re-initialized (e.g., during `goingLive()` transition).
	 *
	 * Must be called **before** WAL replay or client mutations begin — the registry must be fully populated
	 * so that source-side detection in `ReferenceIndexMutator` can look up cross-entity triggers. At call time,
	 * the `entitySchemaIndex` contains schemas with fully resolved reflected reference inheritance (i.e.,
	 * `ReflectedReferenceSchema` instances have already inherited `facetedPartiallyInScopes` from their source
	 * references via `initSchema()` -> `withReferencedSchema()`).
	 */
	private void buildInitialExpressionTriggerRegistry() {
		final CatalogExpressionTriggerRegistry registry =
			DefaultCatalogExpressionTriggerRegistry.buildFromSchemas(this.entitySchemaIndex);
		this.expressionTriggerRegistry.set(registry);
		if (registry instanceof DefaultCatalogExpressionTriggerRegistry impl) {
			final int triggerCount = impl.getTriggerCount();
			if (triggerCount > 0) {
				log.debug(
					"Expression trigger registry initialized with {} triggers for catalog '{}'.",
					triggerCount, this.getName()
				);
			}
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method transparently updates the contents of {@link #entityCollections} map with the new collection, if the
	 * passed {@link EntityCollectionHeaderWithCollection} contains a different collection than the one stored in
	 * the index.
	 *
	 * @param flushResult The result containing the header and the entity collection to potentially update.
	 * @return The entity collection header from the flush result.
	 */
	@Nonnull
	private EntityCollectionHeader updateIndexIfNecessary(
		@Nonnull EntityCollectionHeaderWithCollection flushResult
	) {
		final EntityCollectionHeader header = flushResult.header();
		this.entityCollections.computeIfPresent(
			header.entityType(),
			(entityType, entityCollection) -> entityCollection == flushResult.collection() ?
				entityCollection : flushResult.collection()
		);
		return header;
	}

	/**
	 * Replaces reference to the catalog in this instance. The reference is stored in transactional data structure so
	 * that it doesn't affect parallel clients until committed.
	 *
	 * This is also the **only** place this catalog adopts a new catalog schema version - every catalog schema mutation
	 * and the rename handover arrive here - which is what makes it the single hook for realigning
	 * {@link #usageRegistry}. The alignment runs only after the exchange has won its race, and it carries the same two
	 * accepted errors `EntityCollection#exchangeSchema` documents in full: a rolled-back schema change that dropped a
	 * global attribute leaves the registry having discarded that attribute's counters, and a query planning against the
	 * pre-exchange schema version can re-insert a key the alignment has just dropped, which then survives until the
	 * next adoption.
	 *
	 * @param updatedSchema updated schema
	 * @param currentSchema current schema
	 * @return updated schema
	 */
	@Nonnull
	private CatalogSchema exchangeCatalogSchema(
		@Nonnull CatalogSchemaContract updatedSchema,
		@Nonnull CatalogSchema currentSchema
	) {
		final CatalogSchemaContract nextSchema = updatedSchema;
		Assert.isPremiseValid(updatedSchema != null, "Catalog cannot be dropped by updating schema!");
		Assert.isPremiseValid(
			updatedSchema instanceof CatalogSchema, "Mutation is expected to produce CatalogSchema instance!");
		final CatalogSchema updatedInternalSchema = (CatalogSchema) updatedSchema;

		if (updatedSchema.version() > currentSchema.version()) {
			final CatalogSchemaDecorator currentSchemaWrapper = Objects.requireNonNull(this.schema.get());
			Assert.isPremiseValid(
				currentSchemaWrapper.getDelegate() == currentSchema,
				"Invalid current schema used!"
			);
			final CatalogSchemaDecorator originalSchemaBeforeExchange = Objects.requireNonNull(
				this.schema.compareAndExchange(
					currentSchemaWrapper,
					new CatalogSchemaDecorator(updatedInternalSchema)
				)
			);
			Assert.isTrue(
				originalSchemaBeforeExchange.version() == currentSchema.version(),
				() -> new ConcurrentSchemaUpdateException(currentSchema, nextSchema)
			);
			// only after the exchange is known to have won the race - a losing exchange changed nothing to align
			// against
			this.usageRegistry.alignWith(updatedInternalSchema);
		}
		return updatedInternalSchema;
	}

	/**
	 * Modifies a catalog schema using the provided mutation and updated schema.
	 *
	 * @param theMutation   The mutation to be applied on the catalog schema.
	 * @param catalogSchema The updated catalog schema.
	 * @return The modified catalog schema along with its impact on entity schemas.
	 */
	@Nonnull
	private CatalogSchemaWithImpactOnEntitySchemas modifyCatalogSchema(
		@Nonnull CatalogSchemaMutation theMutation,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final CatalogSchemaWithImpactOnEntitySchemas schemaWithImpactOnEntitySchemas;
		if (theMutation instanceof LocalCatalogSchemaMutation localCatalogSchemaMutation) {
			schemaWithImpactOnEntitySchemas = localCatalogSchemaMutation.mutate(
				catalogSchema, getEntitySchemaAccessor());
		} else {
			schemaWithImpactOnEntitySchemas = theMutation.mutate(catalogSchema);
		}
		Assert.isPremiseValid(
			schemaWithImpactOnEntitySchemas != null && schemaWithImpactOnEntitySchemas.updatedCatalogSchema() != null,
			"Catalog schema mutation is expected to produce CatalogSchema instance!"
		);
		return schemaWithImpactOnEntitySchemas;
	}

	/**
	 * Modifies the name of an entity schema.
	 *
	 * @param renameEntitySchemaMutation The mutation to rename the entity schema.
	 * @param transactionRef             The reference to the transaction.
	 * @param catalogSchema              The updated catalog schema.
	 * @return The modified entity schema.
	 */
	@Nonnull
	private CatalogSchemaContract modifyEntitySchemaName(
		@Nullable UUID sessionId,
		@Nonnull ModifyEntitySchemaNameMutation renameEntitySchemaMutation,
		@Nullable Transaction transactionRef,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		if (renameEntitySchemaMutation.isOverwriteTarget() && this.entityCollections.containsKey(
			renameEntitySchemaMutation.getNewName())) {
			replaceEntityCollectionInternal(
				sessionId,
				transactionRef != null,
				renameEntitySchemaMutation
			);
		} else {
			renameEntityCollectionInternal(
				sessionId,
				transactionRef != null,
				renameEntitySchemaMutation
			);
		}
		return CatalogSchema._internalBuildWithUpdatedVersion(
			catalogSchema,
			getEntitySchemaAccessor()
		);
	}

	/**
	 * Creates a new entity schema and adds it to the catalog schema.
	 *
	 * @param createEntitySchemaMutation The mutation used to create the entity schema.
	 * @param catalogSchema              The catalog schema to add the new entity schema to.
	 * @return The updated catalog schema.
	 */
	@Nonnull
	private CatalogSchema createEntitySchema(
		@Nonnull CreateEntitySchemaMutation createEntitySchemaMutation,
		@Nullable Transaction transaction,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final String entityType = createEntitySchemaMutation.getName();
		this.persistenceService.verifyEntityType(
			this.entityCollections.values(),
			entityType
		);
		final long catalogVersion = this.getVersion();
		final int entityTypePrimaryKey = this.entityTypeSequence.incrementAndGet();
		final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> entityCollectionPersistenceService = this.persistenceService
			.getOrCreateEntityCollectionPersistenceService(
				catalogVersion, entityType, entityTypePrimaryKey
			);
		if (entityCollectionPersistenceService.isNew() && Transaction.isTransactionAvailable()) {
			Transaction.registerCloseable(entityCollectionPersistenceService);
		}
		final EntityCollection newCollection = new EntityCollection(
			this.getName(),
			catalogVersion,
			this.getCatalogState(),
			entityTypePrimaryKey,
			entityType,
			64,
			this.persistenceService,
			entityCollectionPersistenceService,
			this.cacheSupervisor,
			this.sequenceService,
			this.trafficRecordingEngine
		);
		this.entityCollectionsByPrimaryKey.put(newCollection.getEntityTypePrimaryKey(), newCollection);
		this.entityCollections.put(newCollection.getEntityType(), newCollection);
		newCollection.attachToCatalog(null, this);
		final CatalogSchema newSchema = CatalogSchema._internalBuildWithUpdatedVersion(
			catalogSchema,
			getEntitySchemaAccessor()
		);
		entitySchemaUpdated(newCollection.getSchema());
		// when the catalog is in WARM-UP state we need to execute immediate flush when collection is created
		if (transaction == null) {
			// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
			final ProgressingFuture<Void> flushFuture = this.flush();
			flushFuture.execute(ProgressingFuture.unrejectableExecutor(this.transactionalExecutor));
			flushFuture.join();
		}
		return newSchema;
	}

	/**
	 * Removes an entity schema from the catalog schema.
	 *
	 * @param removeEntitySchemaMutation The remove entity schema mutation.
	 * @param transaction                The transaction (optional).
	 * @param catalogSchema              The catalog schema (optional).
	 * @return The catalog schema contract after removing the entity schema.
	 */
	@Nonnull
	private CatalogSchemaContract removeEntitySchema(
		@Nonnull RemoveEntitySchemaMutation removeEntitySchemaMutation,
		@Nullable Transaction transaction,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final EntityCollection collectionToRemove = this.entityCollections.remove(removeEntitySchemaMutation.getName());
		if (transaction == null && collectionToRemove != null) {
			final long catalogVersion = getVersion();
			this.persistenceService.deleteEntityCollection(
				catalogVersion,
				catalogVersion > 0L ?
					collectionToRemove.flush(catalogVersion) :
					updateIndexIfNecessary(
						// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
						collectionToRemove.flush()
					)
			);
		}
		final CatalogSchemaContract result;
		if (collectionToRemove != null) {
			if (transaction != null) {
				collectionToRemove.removeLayer();
			}
			result = CatalogSchema._internalBuildWithUpdatedVersion(
				catalogSchema,
				getEntitySchemaAccessor()
			);
			entitySchemaRemoved(collectionToRemove.getEntityType());
		} else {
			result = catalogSchema;
		}
		// when the catalog is in WARM-UP state we need to execute immediate flush when collection is removed
		if (transaction == null) {
			// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
			final ProgressingFuture<Void> flushFuture = this.flush();
			flushFuture.execute(ProgressingFuture.unrejectableExecutor(this.transactionalExecutor));
			flushFuture.join();
		}
		return result;
	}

	/**
	 * Preflights every {@link ModifyEntitySchemaMutation} in a batch against the collection it targets, so that a
	 * refusal is raised **before** any schema is exchanged.
	 *
	 * The problem this solves is failure atomicity, not validation coverage. A catalog-level change to a global
	 * attribute fans out into one entity mutation per consuming collection, and the loop below applies them one at a
	 * time - each exchanging its schema and writing its storage part. If the fourth collection refuses, the `catch`
	 * restores only {@link #schema}: the three collections already updated keep the change, in memory and on disk,
	 * while the catalog schema says the operation failed. Checking all of them first is what makes the cascade
	 * all-or-nothing without holding a rollback log of exchanged schemas.
	 *
	 * Collections that do not exist yet are skipped - a batch may create one and then modify it, and there is nothing
	 * to preflight against until it exists.
	 *
	 * @param schemaMutations the batch about to be applied
	 * @throws io.evitadb.api.exception.InvalidSchemaMutationException when any affected collection refuses its share
	 */
	private void verifyEntitySchemaMutationsApplicable(
		@Nonnull LocalCatalogSchemaMutation[] schemaMutations
	) {
		final CatalogSchema currentCatalogSchema = getInternalSchema();
		for (final LocalCatalogSchemaMutation theMutation : schemaMutations) {
			if (theMutation instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation) {
				final EntityCollection entityCollection =
					this.entityCollections.get(modifyEntitySchemaMutation.getName());
				if (entityCollection != null) {
					entityCollection.verifySchemaMutationsApplicable(
						currentCatalogSchema, modifyEntitySchemaMutation.getSchemaMutations()
					);
				}
			}
		}
	}

	/**
	 * Modifies the entity schema by applying the given schema mutations.
	 *
	 * @param sessionId                  The session the modification is performed on behalf of, or null when it is
	 *                                   applied by the transactional replayer, which has no session.
	 * @param modifyEntitySchemaMutation The modifications to be applied to the entity schema.
	 * @param catalogSchema              The catalog schema associated with the entity.
	 * @param entityCollection           The collection whose schema is being modified.
	 * @return the catalog schema carrying the version bumped by this modification
	 */
	@Nonnull
	private CatalogSchemaContract modifyEntitySchema(
		@Nullable UUID sessionId,
		@Nonnull ModifyEntitySchemaMutation modifyEntitySchemaMutation,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntityCollection entityCollection
	) {
		if (!ArrayUtils.isEmpty(modifyEntitySchemaMutation.getSchemaMutations())) {
			entityCollection.updateSchema(
				sessionId,
				catalogSchema,
				modifyEntitySchemaMutation.getSchemaMutations()
			);
		}
		return CatalogSchema._internalBuildWithUpdatedVersion(
			catalogSchema,
			getEntitySchemaAccessor()
		);
	}

	/**
	 * Method creates {@link QueryPlanningContext} that is used for read operations.
	 */
	@Nonnull
	private QueryPlanningContext createQueryContext(
		@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return new QueryPlanningContext(
			this,
			null,
			session, evitaRequest,
			evitaRequest.isQueryTelemetryRequested() ? QueryTelemetry.root(QueryPhase.OVERALL) : null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			this.cacheSupervisor
		);
	}

	/**
	 * Renames the existing entity collection in catalog.
	 */
	private void renameEntityCollectionInternal(
		@Nullable UUID sessionId,
		boolean transactionOpen,
		@Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaNameMutation
	) {
		final String currentName = modifyEntitySchemaNameMutation.getName();
		final String newName = modifyEntitySchemaNameMutation.getNewName();
		this.persistenceService.verifyEntityType(this.entityCollections.values(), newName);

		final EntityCollection entityCollectionToBeRenamed = getCollectionForEntityOrThrowException(currentName);
		doReplaceEntityCollectionInternal(
			sessionId,
			modifyEntitySchemaNameMutation, newName, currentName,
			entityCollectionToBeRenamed,
			transactionOpen
		);
	}

	/**
	 * Replaces existing entity collection in catalog.
	 */
	private void replaceEntityCollectionInternal(
		@Nullable UUID sessionId,
		boolean transactionOpen,
		@Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaNameMutation
	) {
		final String currentName = modifyEntitySchemaNameMutation.getName();
		final String newName = modifyEntitySchemaNameMutation.getNewName();
		getCollectionForEntityOrThrowException(currentName);
		final EntityCollection entityCollectionToBeReplacedWith = getCollectionForEntityOrThrowException(currentName);

		doReplaceEntityCollectionInternal(
			sessionId,
			modifyEntitySchemaNameMutation, newName, currentName,
			entityCollectionToBeReplacedWith,
			transactionOpen
		);
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	private void doReplaceEntityCollectionInternal(
		@Nullable UUID sessionId,
		@Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaName,
		@Nonnull String entityCollectionNameToBeReplaced,
		@Nonnull String entityCollectionNameToBeReplacedWith,
		@Nonnull EntityCollection entityCollectionToBeReplacedWith,
		boolean transactionOpen
	) {
		entityCollectionToBeReplacedWith.updateSchema(
			sessionId,
			getSchema(),
			modifyEntitySchemaName
		);
		Assert.isPremiseValid(
			this.entityCollections.remove(entityCollectionNameToBeReplacedWith) == entityCollectionToBeReplacedWith,
			"Entity collection is expected to be removed from the catalog!"
		);
		if (!transactionOpen) {
			updateIndexIfNecessary(
				// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
				entityCollectionToBeReplacedWith.flush()
			);
			final long catalogVersion = getVersion();
			Assert.isPremiseValid(catalogVersion == 0L, "Catalog version is expected to be `0`!");
			final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> newPersistenceService = this.persistenceService.replaceCollectionWith(
				catalogVersion, entityCollectionNameToBeReplacedWith,
				entityCollectionToBeReplacedWith.getEntityTypePrimaryKey(),
				entityCollectionNameToBeReplaced
			);
			this.entityCollections.put(
				entityCollectionNameToBeReplaced,
				entityCollectionToBeReplacedWith.createCopyWithNewPersistenceService(
					catalogVersion, this.getCatalogState(), newPersistenceService
				)
			);
			// update managed reference entity types and groups that target renamed entity
			for (EntityCollection otherCollection : this.entityCollections.values()) {
				boolean schemaUpdated = otherCollection.notifyEntityTypeRenamed(
					entityCollectionNameToBeReplacedWith, entityCollectionToBeReplacedWith
				);
				if (schemaUpdated) {
					updateIndexIfNecessary(
						// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
						otherCollection.flush()
					);
				}
			}
			// store catalog with a new file pointer
			// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
			final ProgressingFuture<Void> flushFuture = this.flush();
			flushFuture.execute(ProgressingFuture.unrejectableExecutor(this.transactionalExecutor));
			flushFuture.join();
		} else {
			// update managed reference entity types and groups that target renamed entity
			for (EntityCollection otherCollection : this.entityCollections.values()) {
				otherCollection.notifyEntityTypeRenamed(
					entityCollectionNameToBeReplacedWith, entityCollectionToBeReplacedWith
				);
			}
			this.entityCollections.put(entityCollectionNameToBeReplaced, entityCollectionToBeReplacedWith);
		}
	}

	/**
	 * Handles the termination operations for the internal state of the catalog object.
	 * This method performs a cleanup of resources and ensures that any necessary persistence
	 * operations are completed, specifically during the catalog's warming up state.
	 * The termination process involves flushing and updating entity collections, storing
	 * headers if applicable, and preparing the system for garbage collection.
	 * Logs relevant information or errors during the lifecycle of this operation.
	 */
	private void terminateInternally() {
		final String catalogName = getName();
		try {
			// close transaction manager
			IOUtils.closeQuietly(this.transactionManager::close);
			// flush all entity collections and store their headers
			final List<EntityCollectionHeader> entityHeaders;
			boolean changeOccurred = this.lastPersistedSchemaVersion != getInternalSchema().version();
			final boolean warmingUpState = getCatalogState() == CatalogState.WARMING_UP;
			entityHeaders = new ArrayList<>(this.entityCollections.size());
			for (EntityCollection entityCollection : this.entityCollections.values()) {
				// in warmup state try to persist all changes in volatile memory
				if (warmingUpState) {
					final long lastSeenVersion = entityCollection.getVersion();
					entityHeaders.add(
						updateIndexIfNecessary(
							// TOBEDONE #409 - we should execute all schema operations in asynchronous manner
							entityCollection.flush()
						)
					);
					changeOccurred = changeOccurred || entityCollection.getVersion() != lastSeenVersion;
				}
				// in all states terminate collection operations
				if (!entityCollection.isTerminated()) {
					entityCollection.terminate();
				}
			}

			// if any change occurred (this may happen only in warm up state)
			if (warmingUpState && changeOccurred) {
				// store catalog header
				this.persistenceService.storeHeader(
					this.catalogId,
					getCatalogState(),
					getVersion(),
					this.entityTypeSequence.get(),
					null,
					entityHeaders,
					this.dataStoreBuffer
				);
			}
			// close all resources here, here we just hand all objects to GC
			this.entityCollections.clear();
			// log info
			log.info("Catalog {} successfully terminated.", catalogName);
		} catch (RuntimeException ex) {
			// log error
			log.error("Failed to terminate catalog {} due to: {}", catalogName, ex.getMessage(), ex);
		}
	}

	/**
	 * This class represents a bulk structure for initializing a catalog. It encapsulates
	 * the necessary data required for initializing entity collections, schemas, and the catalog itself.
	 *
	 * The {@code CatalogInitializationBulk} record holds references to mappings of entity collections
	 * by name and primary key, an index of entity schemas, and metadata about the catalog.
	 *
	 * This structure is intended to be used during operations that require batch initialization
	 * or setup of catalog-related data.
	 */
	private record CatalogInitializationBulk(
		@Nonnull Map<String, EntityCollection> collections,
		@Nonnull Map<Integer, EntityCollection> collectionByPk,
		@Nonnull Map<String, EntitySchemaContract> entitySchemaIndex,
		@Nonnull Catalog catalog,
		@Nonnull CatalogHeader<? extends LogRecordReference, ? extends CollectionReference> catalogHeader,
		@Nonnull ConcurrentHashMap<String, List<EntityIndex>> globalIndexes
	) {

		/**
		 * Adds a global index to the catalog initialization bulk for a specific entity type.
		 * This method ensures that only global indexes are added and associates them with the specified entity type.
		 * If no indexes are currently associated with the given entity type, a new list is created to store them.
		 *
		 * @param entityType the name of the entity type to which the global index should be added; must not be null
		 * @param index      the global index to be added; must not be null and must have a type of {@link EntityIndexType#GLOBAL}
		 */
		public void addGlobalIndex(@Nonnull String entityType, @Nonnull EntityIndex index) {
			Assert.isPremiseValid(
				index.getIndexKey().type() == EntityIndexType.GLOBAL,
				"Only global indexes are allowed in catalog initialization bulk!"
			);
			this.globalIndexes.computeIfAbsent(entityType, it -> new CopyOnWriteArrayList<>()).add(index);
		}

		/**
		 * Retrieves a list of global indexes associated with the specified entity type.
		 * If no global indexes are found for the given entity type, an empty list is returned.
		 *
		 * @param entityType the name of the entity type for which global indexes are requested; must not be null
		 * @return a list of {@link EntityIndex} objects that represent the global indexes for the specified entity type;
		 * an empty list if no global indexes are found
		 */
		@Nonnull
		public List<EntityIndex> globalIndexes(@Nonnull String entityType) {
			return this.globalIndexes.getOrDefault(entityType, Collections.emptyList());
		}
	}

	/**
	 * This implementation just manipulates with the set of EntityIndex in entity collection.
	 */
	public class CatalogIndexMaintainerImpl implements IndexMaintainer<CatalogIndexKey, CatalogIndex> {

		/**
		 * Returns entity index by its key. If such index doesn't exist, it is automatically created.
		 */
		@Nonnull
		@Override
		public CatalogIndex getOrCreateIndex(@Nonnull CatalogIndexKey catalogIndexKey) {
			return Catalog.this.dataStoreBuffer.getOrCreateIndexForModification(
				catalogIndexKey,
				cik -> Catalog.this.getCatalogIndex(cik.scope())
			);
		}

		/**
		 * Catalog indexes are not addressable by storage primary key — always returns null.
		 */
		@Nonnull
		@Override
		public CatalogIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			return Catalog.this.dataStoreBuffer.getOrCreateIndexForModification(
				indexPrimaryKey,
				Catalog.this.catalogIndexMaintainer::getIndexByPrimaryKey
			);
		}

		/**
		 * Returns existing index for passed `catalogIndexKey` or returns null.
		 */
		@Nullable
		@Override
		public CatalogIndex getIndexIfExists(@Nonnull CatalogIndexKey catalogIndexKey) {
			return catalogIndexKey.scope() == Scope.ARCHIVED ?
				Catalog.this.archiveCatalogIndex.get() : Catalog.this.catalogIndex;
		}

		/**
		 * Catalog indexes are not addressable by storage primary key — always returns null.
		 */
		@Nullable
		@Override
		public CatalogIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			return null;
		}

		/**
		 * Removes entity index by its key. If such index doesn't exist, exception is thrown.
		 *
		 * @throws IllegalArgumentException when entity index doesn't exist
		 */
		@Override
		public void removeIndex(@Nonnull CatalogIndexKey entityIndexKey) {
			throw new GenericEvitaInternalError("Global catalog index is not expected to be removed!");
		}

	}

	/**
	 * A class that serves as an accessor for the entity schemas in a Catalog object.
	 */
	private class CatalogEntitySchemaAccessor implements EntitySchemaProvider {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return new ArrayList<>(Catalog.this.getEntitySchemaIndex().values());
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return Catalog.this.getEntitySchema(entityType).map(EntitySchemaContract.class::cast);
		}
	}

}
