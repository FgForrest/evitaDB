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

package io.evitadb.core.collection;

import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
import io.evitadb.api.statistics.CollectionRecordCounts;
import io.evitadb.api.statistics.CollectionStorageComposition;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ConcurrentSchemaUpdateException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.SchemaNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.catalog.CatalogRelatedDataStructure;
import io.evitadb.core.catalog.FragmentationProjection;
import io.evitadb.core.catalog.StoragePartProjection;
import io.evitadb.core.catalog.VolatileStateProjection;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.fetch.ReferencedEntityFetcher;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.response.ServerBinaryEntityDecorator;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.stage.mutation.ServerEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.*;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.MapChanges.ValueMerger;
import io.evitadb.index.map.PersistentTransactionalProducerMap;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.index.mutation.EntityIndexMutation;
import io.evitadb.index.mutation.IndexMutation;
import io.evitadb.index.mutation.IndexMutationExecutor;
import io.evitadb.index.mutation.IndexMutationExecutorRegistry;
import io.evitadb.index.mutation.IndexMutationTarget;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.reference.ReferenceChanges;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.spi.store.catalog.chunk.ServerChunkTransformerAccessor;
import io.evitadb.spi.store.catalog.header.HeaderInfoSupplier;
import io.evitadb.spi.store.catalog.header.model.CollectionReference;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.CollectionStorageFootprint;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService.BinaryEntityWithFetchCount;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService.EntityWithFetchCount;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.VolatileDataFootprint;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.shared.model.PriceInternalIdContainer;
import io.evitadb.spi.store.catalog.trafficRecorder.TrafficRecorder;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Entity collection maintains all entities of same {@link Entity#getType()}. Entity collection could be imagined
 * as single table in RDBMS environment or document type in case of Elasticsearch or Mongo DB no sql databases.
 *
 * EntityCollection is set of records of the same type. In the relational world it would represent a table (or a single
 * main table with several other tables containing records referring to that main table). Entity collection maintains
 * all entities of the same type (i.e. same {@link EntitySchema}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class EntityCollection implements
	TransactionalLayerProducer<DataStoreChanges, EntityCollection>,
	EntityCollectionContract,
	DataStoreReader,
	CatalogRelatedDataStructure<EntityCollection> {

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains a unique identifier of the entity type that is assigned on entity collection creation and never changes.
	 * The primary key can be used interchangeably to {@link EntitySchema#getName() String entity type}.
	 */
	@Getter private final int entityTypePrimaryKey;
	/**
	 * Performance optimization flag that keeps information whether the collection was empty when it was created.
	 */
	private final boolean emptyOnStart;
	/**
	 * EntityIndex factory implementation.
	 */
	private final EntityIndexMaintainer entityIndexCreator = new EntityIndexMaintainer();
	/**
	 * Contains entity schema in the form it was initialized during creation.
	 */
	private final EntitySchema initialSchema;
	/**
	 * Contains sequence that allows automatic assigning monotonic primary keys to the entities.
	 *
	 * The sequence guarantees uniqueness and monotonicity, not contiguity. A drawn value cannot be
	 * un-consumed, so when a single entity mutation is reverted while the surrounding transaction keeps
	 * going (partial rollback of one failed entity in a larger batch) the assigned key is simply skipped,
	 * leaving a harmless gap — nothing relies on the keys being consecutive.
	 */
	private final AtomicInteger pkSequence;
	/**
	 * Contains sequence that allows assigning monotonic primary keys to the entity indexes.
	 *
	 * Like {@link #pkSequence}, it only guarantees uniqueness and monotonicity; a value consumed for an
	 * index that is then rolled back is left as a harmless gap (an {@link AtomicInteger} cannot be un-consumed).
	 */
	private final AtomicInteger indexPkSequence;
	/**
	 * Contains the sequence for assigning {@link PriceInternalIdContainer#getInternalPriceId()} to a newly encountered
	 * prices in the input data. See {@link PriceInternalIdContainer} to see the reasons behind it. The price sequence
	 * is shared among live and archive scope to avoid ambiguities.
	 *
	 * Like {@link #pkSequence}, it only guarantees uniqueness and monotonicity; an internal price id consumed for
	 * a price whose entity mutation is then rolled back is left as a harmless gap.
	 */
	private final AtomicInteger pricePkSequence;
	/**
	 * Service allowing to recreate I/O collection service on-demand.
	 */
	private final CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> catalogPersistenceService;
	/**
	 * Collection of search indexes prepared to handle queries.
	 *
	 * Held as a persistent (structure-sharing) map because its commit is derived, not rebuilt: the next catalog version's
	 * map is path-copied from this one over the handful of keys the transaction touched, so the untouched remainder — in
	 * a production workload hundreds of thousands of entries — is carried across the version boundary as shared trie
	 * nodes instead of being walked entry by entry. The dirty-key set driving that walk is supplied explicitly by
	 * {@link #pruneMergeIndexes(TransactionalLayerMaintainer, Set)}; iteration order is unspecified, which no consumer
	 * relies on (`getIndexPrimaryKeys` is the only walk, and its consumers load each id independently).
	 */
	private final PersistentTransactionalProducerMap<EntityIndexKey, EntityIndex> indexes;
	/**
	 * Collection of search indexes indexed by their primary keys. Contains identical data as {@link #indexes} but
	 * the key in the map is their {@link EntityIndex#getPrimaryKey()}.
	 *
	 * Persistent for the same reason as {@link #indexes} and derived from the very same transaction delta: the two maps
	 * hold the SAME index instances under two different keys, so whatever the commit does to one it does to the other.
	 * {@link #pruneMergeIndexes(TransactionalLayerMaintainer, Set)} applies that single delta to both and returns them
	 * together, which is what keeps them from drifting apart.
	 */
	private final PersistentTransactionalProducerMap<Integer, EntityIndex> indexesByPrimaryKey;
	/**
	 * How many indexes {@link #indexes} holds, split by type and scope, maintained incrementally so that reporting the
	 * split does not walk a map whose size is a function of the catalog's data volume. See {@link IndexPopulation} for
	 * why the counts move at commit rather than at the call sites that create and drop indexes.
	 */
	private final IndexPopulation indexPopulation;
	/**
	 * True if collection was already terminated. No other termination will be allowed.
	 */
	private final AtomicBoolean terminated = new AtomicBoolean(false);
	/**
	 * This instance is used to cover changes in transactional memory and persistent storage reference.
	 *
	 * @see TransactionalDataStoreMemoryBuffer documentation
	 */
	private final DataStoreMemoryBuffer dataStoreBuffer;
	/**
	 * Contains wrapped reference to {@link #dataStoreBuffer} that allows to read data from the buffer using
	 * the {@link EntitySchema} of this collection.
	 */
	private final DataStoreReader dataStoreReader;
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified
	 * by its {@link Formula#getHash()} method and when the supervisor identifies that certain
	 * formula is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	private final CacheSupervisor cacheSupervisor;
	/**
	 * Traffic recorder used for recording the traffic in the catalog.
	 */
	private final TrafficRecordingEngine trafficRecorder;
	/**
	 * Inner class that provides information for the {@link EntityCollectionHeader} when it's created in the persistence
	 * layer.
	 */
	private final HeaderInfoSupplier headerInfoSupplier = new EntityCollectionHeaderInfoSupplier();
	/**
	 * Service containing I/O related methods.
	 */
	private final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> persistenceService;
	/**
	 * Contains the default minimal query used when we need to fetch only the assigned primary key when
	 * entity is being inserted into database.
	 */
	private final EvitaRequest defaultMinimalQuery;
	/**
	 * This field contains reference to the CURRENT {@link Catalog} instance allowing to access {@link EntityCollection}
	 * for any of entity types that are known to the catalog this collection is part of. Reference to other collections
	 * is used to access their schema or their indexes from this collection.
	 *
	 * The reference pointer is used because when transaction is committed and new catalog is created to atomically swap
	 * changes and left old readers finish with old catalog, the entity collection copy is created, and we need to init
	 * the reference to this function lazily when new catalog is instantiated (existence of the new collection precedes
	 * the creation of the catalog copy).
	 */
	private Catalog catalog;
	/**
	 * Contains schema of the entity type that is used for formal verification of the data consistency and indexing
	 * prescription.
	 */
	private TransactionalReference<EntitySchemaDecorator> schema;

	/**
	 * Retrieves the last assigned internal primary key for pricing within the entity collection.
	 * The method determines the starting sequence value based on the entity header's current
	 * state and optionally fetches it from a global index if necessary.
	 *
	 * @param entityHeader The header of the entity collection containing information about pricing keys.
	 * @param entityCollectionPersistenceService A service that allows fetching data from persistent storage.
	 * @param catalogVersion The version of the catalog used for fetching the data related to pricing.
	 * @return The last assigned internal primary key for pricing. If no key is assigned, returns 0.
	 */
	private static int getLastAssignedPriceInternalPrimaryKey(
		@Nonnull EntityCollectionHeader entityHeader,
		@Nonnull EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> entityCollectionPersistenceService,
		long catalogVersion
	) {
		// if entity header has no last internal price id, initialized and there is global index available
		final Integer globalEntityIndexPrimaryKey = entityHeader.globalEntityIndexPrimaryKey();
		return entityHeader.lastInternalPriceId() == -1 && globalEntityIndexPrimaryKey != null ?
			// try to initialize sequence from deprecated storage key format
			entityCollectionPersistenceService.fetchLastAssignedInternalPriceIdFromGlobalIndex(
				catalogVersion,
				globalEntityIndexPrimaryKey
			).orElse(0) :
			// otherwise initialize from the last internal price id - when it's initialized, othewise start from 0
			entityHeader.lastInternalPriceId() == -1 ? 0 : entityHeader.lastInternalPriceId();
	}

	/**
	 * Standard constructor that loads all necessary data from the persistence service and initializes
	 * the collection.
	 */
	public EntityCollection(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		int entityTypePrimaryKey,
		@Nonnull String entityType,
		int entityIndexesExpectedCount,
		@Nonnull CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> catalogPersistenceService,
		@Nonnull EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> entityCollectionPersistenceService,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull SequenceService sequenceService,
		@Nonnull TrafficRecordingEngine trafficRecorder
	) {
		this.trafficRecorder = trafficRecorder;
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.catalogPersistenceService = catalogPersistenceService;
		this.persistenceService = entityCollectionPersistenceService;
		this.cacheSupervisor = cacheSupervisor;

		try {
			final EntityCollectionHeader entityHeader = entityCollectionPersistenceService.getEntityCollectionHeader();
			this.pkSequence = sequenceService.getOrCreateSequence(
				catalogName, SequenceType.ENTITY, entityType, entityHeader.lastPrimaryKey()
			);
			this.indexPkSequence = sequenceService.getOrCreateSequence(
				catalogName, SequenceType.INDEX, entityType, entityHeader.lastEntityIndexPrimaryKey()
			);
			// we need to initialize the price sequence here, in order to initialize correctly the last internal price id
			// from older storage format when it was stored as a part of the global index
			this.pricePkSequence = sequenceService.getOrCreateSequence(
				catalogName, SequenceType.PRICE, entityType,
				getLastAssignedPriceInternalPrimaryKey(entityHeader, entityCollectionPersistenceService, catalogVersion)
			);

			// initialize container buffer
			final StoragePartPersistenceService<StorageDescriptor> storagePartPersistenceService = this.persistenceService.getStoragePartPersistenceService();
			this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
				new WarmUpDataStoreMemoryBuffer(storagePartPersistenceService) :
				new TransactionalDataStoreMemoryBuffer(this, storagePartPersistenceService);
			this.dataStoreReader = new DataStoreReaderBridge(
				this.dataStoreBuffer,
				this::getIndexByKeyIfExists,
				this::getIndexByPrimaryKeyIfExists,
				this::getInternalSchema
			);
			// initialize schema - still in constructor
			this.initialSchema = ofNullable(storagePartPersistenceService.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class))
				.map(EntitySchemaStoragePart::entitySchema)
				.orElseGet(() -> {
					if (this.persistenceService.isNew()) {
						final EntitySchema newEntitySchema = EntitySchema._internalBuild(entityType);
						this.dataStoreBuffer.update(catalogVersion, new EntitySchemaStoragePart(newEntitySchema));
						return newEntitySchema;
					} else {
						throw new SchemaNotFoundException(catalogName, entityHeader.entityType());
					}
				});
			// init entity indexes
			if (entityHeader.globalEntityIndexPrimaryKey() == null) {
				Assert.isPremiseValid(
					entityHeader.usedEntityIndexPrimaryKeys().isEmpty(),
					"Unexpected situation - global index doesn't exist but there are " +
						entityHeader.usedEntityIndexPrimaryKeys().size() + " reduced indexes!"
				);
				this.indexes = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
					CollectionUtils.createHashMap(64),
					EntityIndex.class::cast
				);
				this.indexesByPrimaryKey = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
					CollectionUtils.createHashMap(64),
					EntityIndex.class::cast
				);
			} else {
				this.indexes = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
					CollectionUtils.createHashMap(entityIndexesExpectedCount),
					EntityIndex.class::cast
				);
				this.indexesByPrimaryKey = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
					CollectionUtils.createHashMap(entityIndexesExpectedCount),
					EntityIndex.class::cast
				);
			}
			// the maps are empty at this point either way - the load path fills them through `addIndex`, which is what
			// grows this population, so seeding it with a walk here would count nothing
			this.indexPopulation = new IndexPopulation();

			// sanity check whether we deserialized the file offset index we expect to
			Assert.isTrue(
				entityHeader.entityType().equals(this.initialSchema.getName()),
				() -> "Deserialized schema name differs from expected entity type - expected " + entityHeader.entityType() + " got " + this.initialSchema.getName()
			);
			this.emptyOnStart = this.persistenceService.isEmpty(catalogVersion, this.dataStoreReader);
			this.defaultMinimalQuery = new EvitaRequest(
				Query.query(collection(entityType)),
				OffsetDateTime.MIN, // we don't care about the time
				EntityReference.class,
				null
			);
		} catch (RuntimeException ex) {
			// close persistence service in case of exception first
			this.persistenceService.close();
			throw ex;
		}
	}

	/**
	 * Optimized constructor that takes previous instance of the collection and reuses its data.
	 */
	public EntityCollection(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		@Nonnull EntityCollection previousCollection,
		@Nonnull CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> catalogPersistenceService,
		@Nonnull SequenceService sequenceService
	) {
		this.trafficRecorder = previousCollection.trafficRecorder;
		final String entityType = previousCollection.getSchema().getName();
		this.entityTypePrimaryKey = previousCollection.entityTypePrimaryKey;
		this.initialSchema = previousCollection.getInternalSchema();
		this.catalogPersistenceService = catalogPersistenceService;

		this.persistenceService = catalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, entityType, this.entityTypePrimaryKey
		);

		final EntityCollectionHeader entityHeader = this.persistenceService.getEntityCollectionHeader();
		this.pkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.ENTITY, entityType, entityHeader.lastPrimaryKey()
		);
		this.indexPkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.INDEX, entityType, entityHeader.lastEntityIndexPrimaryKey()
		);
		// we need to initialize the price sequence here, in order to initialize correctly the last internal price id
		// from older storage format when it was stored as a part of the global index
		this.pricePkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.PRICE, entityType,
			getLastAssignedPriceInternalPrimaryKey(entityHeader, this.persistenceService, catalogVersion)
		);

		this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(this.persistenceService.getStoragePartPersistenceService()) :
			new TransactionalDataStoreMemoryBuffer(this, this.persistenceService.getStoragePartPersistenceService());
		this.dataStoreReader = new DataStoreReaderBridge(
			this.dataStoreBuffer,
			this::getIndexByKeyIfExists,
			this::getIndexByPrimaryKeyIfExists,
			this::getInternalSchema
		);
		final IndexTuple indexTuple = previousCollection.createIndexCopiesForNewCatalogAttachment();
		this.indexes = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
			indexTuple.indexes(),
			EntityIndex.class::cast
		);
		this.indexesByPrimaryKey = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
			indexTuple.indexesByPk(),
			EntityIndex.class::cast
		);
		this.indexPopulation = indexTuple.indexPopulation();
		this.cacheSupervisor = previousCollection.cacheSupervisor;
		this.emptyOnStart = this.persistenceService.isEmpty(catalogVersion, this.dataStoreReader);
		this.defaultMinimalQuery = new EvitaRequest(
			Query.query(collection(entityType)),
			OffsetDateTime.MIN, // we don't care about the time
			EntityReference.class,
			null
		);
	}

	/**
	 * Private constructor used for creating new entity collection instance on transaction commit.
	 */
	private EntityCollection(
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		int entityTypePrimaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull AtomicInteger pkSequence,
		@Nonnull AtomicInteger indexPkSequence,
		@Nonnull AtomicInteger pricePkSequence,
		@Nonnull CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> catalogPersistenceService,
		@Nonnull EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> persistenceService,
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull Map<Integer, EntityIndex> indexesByPk,
		@Nonnull IndexPopulation indexPopulation,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull TrafficRecordingEngine trafficRecorder
	) {
		this.trafficRecorder = trafficRecorder;
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.initialSchema = entitySchema;
		this.pkSequence = pkSequence;
		this.catalogPersistenceService = catalogPersistenceService;
		this.persistenceService = persistenceService;
		this.indexPkSequence = indexPkSequence;
		this.pricePkSequence = pricePkSequence;
		this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(persistenceService.getStoragePartPersistenceService()) :
			new TransactionalDataStoreMemoryBuffer(this, persistenceService.getStoragePartPersistenceService());
		this.dataStoreReader = new DataStoreReaderBridge(
			this.dataStoreBuffer,
			this::getIndexByKeyIfExists,
			this::getIndexByPrimaryKeyIfExists,
			this::getInternalSchema
		);
		// `indexes` arrives as a ChampMap on the commit path, which the persistent map adopts sealed in O(1) - a plain
		// map (bulk load, compaction re-attach) is copied into the mutable warm-up buffer exactly as before
		this.indexes = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(indexes, EntityIndex.class::cast);
		this.indexesByPrimaryKey = PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(indexesByPk, EntityIndex.class::cast);
		this.indexPopulation = indexPopulation;
		this.cacheSupervisor = cacheSupervisor;
		this.emptyOnStart = this.persistenceService.isEmpty(catalogVersion, this.dataStoreReader);
		this.defaultMinimalQuery = new EvitaRequest(
			Query.query(collection(entitySchema.getName())),
			OffsetDateTime.MIN, // we don't care about the time
			EntityReference.class,
			null
		);
	}

	@Delegate(types = DataStoreReader.class)
	public DataStoreReader getDataStoreReader() {
		return this.dataStoreReader;
	}

	/**
	 * Returns the service this collection persists its storage parts through. Unlike {@link #getDataStoreReader()},
	 * which resolves single parts by key, this exposes the store itself — the only way to enumerate the collection's
	 * live record set, which diagnostics and storage-reclaim tests need in order to assert the ABSENCE of records they
	 * cannot name in advance. Read-only use only; mutating the store behind the collection's back corrupts it.
	 *
	 * @return the storage-part persistence service backing this collection
	 */
	@Nonnull
	public StoragePartPersistenceService<StorageDescriptor> getStoragePartPersistenceService() {
		return this.persistenceService.getStoragePartPersistenceService();
	}

	@Override
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		final QueryPlan queryPlan = QueryPlanner.planQuery(queryContext);

		// record query information
		return this.trafficRecorder.recordQuery(
			"query", session.getId(), queryPlan
		);
	}

	@Override
	@Nonnull
	public Optional<SealedEntity> getEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);

		// record query information
		return this.trafficRecorder.recordFetch(
			session.getId(),
			evitaRequest,
			() -> fetchEntity(primaryKey, evitaRequest, session, referenceFetcher)
		);
	}

	@Override
	@Nonnull
	public ServerEntityDecorator enrichEntity(@Nonnull EntityContract entity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = evitaRequest.getNamedReferenceEntityFetch();
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		final ReferenceFetcher referenceFetcher = referenceEntityFetch.isEmpty() &&
			namedReferenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
				namedReferenceEntityFetch,
				evitaRequest.getDefaultReferenceRequirement(),
				queryContext.createExecutionContext(),
				entity,
				new ServerChunkTransformerAccessor(evitaRequest)
			);

		// record query information
		return this.trafficRecorder.recordEnrichment(
			session.getId(),
			entity,
			evitaRequest,
			() -> applyReferenceFetcher(
				evitaRequest,
				enrichEntityInternal(entity, evitaRequest),
				referenceFetcher
			)
		);
	}

	@Override
	@Nonnull
	public SealedEntity limitEntity(@Nonnull EntityContract entity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return limitEntityInternal((ServerEntityDecorator) entity, evitaRequest);
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity() {
		return new InitialEntityBuilder(getSchema(), null);
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(int primaryKey) {
		return new InitialEntityBuilder(getSchema(), primaryKey);
	}

	/**
	 * Same method as {@link #upsertEntity(EvitaSessionContract, EntityMutation)}, but in internal API that doesn't
	 * require session in the input. This method is used from transactional replayer that doesn't have session available.
	 */
	@Nonnull
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		return upsertEntityInternal(
			null,
			entityMutation,
			entityMutation.getEntityPrimaryKey() == null ? this.defaultMinimalQuery : null,
			EntityReference.class
		).orElseThrow(
			() -> new EntityMissingException(
				getEntityType(),
				entityMutation.getEntityPrimaryKey() == null ?
					ArrayUtils.EMPTY_INT_ARRAY :
					new int[]{entityMutation.getEntityPrimaryKey()},
				null
			)
		);
	}

	@Override
	@Nonnull
	public EntityReferenceContract upsertEntity(@Nonnull EvitaSessionContract session, @Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		return upsertEntityInternal(
			session,
			entityMutation,
			entityMutation.getEntityPrimaryKey() == null ? this.defaultMinimalQuery : null,
			EntityReferenceContract.class
		).orElseThrow(
			() -> new EntityMissingException(
				getEntityType(),
				entityMutation.getEntityPrimaryKey() == null ?
					ArrayUtils.EMPTY_INT_ARRAY :
					new int[]{entityMutation.getEntityPrimaryKey()},
				null
			)
		);
	}

	@Override
	@Nonnull
	public SealedEntity upsertAndFetchEntity(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityMutation entityMutation,
		@Nonnull EvitaRequest evitaRequest
	) {
		final ServerEntityDecorator internalEntity =
			wrapToDecorator(
				evitaRequest,
				upsertEntityInternal(session, entityMutation, evitaRequest, EntityWithFetchCount.class)
					.orElseThrow(
						() -> new EntityMissingException(
							getEntityType(),
							entityMutation.getEntityPrimaryKey() == null ?
								ArrayUtils.EMPTY_INT_ARRAY :
								new int[]{entityMutation.getEntityPrimaryKey()},
							null
						)
					),
				false
			);
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return applyReferenceFetcher(
			evitaRequest,
			internalEntity,
			referenceFetcher
		);
	}

	@Override
	public boolean deleteEntity(@Nonnull EvitaSessionContract session, int primaryKey) {
		if (this.getGlobalIndexIfExists().map(it -> it.contains(primaryKey)).orElse(false) ||
				this.getGlobalArchiveIndexIfExists().map(it -> it.contains(primaryKey)).orElse(false)) {
			deleteEntityInternal(primaryKey, session,null, Void.class);
			return true;
		} else {
			return false;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest) {
		final int[] primaryKeys = evitaRequest.getPrimaryKeys();
		Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
		if (getGlobalIndexIfExists().map(it -> it.contains(primaryKeys[0])).orElse(false)) {
			final EntityWithFetchCount removedEntity = deleteEntityInternal(primaryKeys[0], session, evitaRequest, EntityWithFetchCount.class)
				.orElseThrow(
					() -> new EntityMissingException(getEntityType(), primaryKeys, null)
				);
			final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
			final ServerEntityDecorator entity = wrapToDecorator(evitaRequest, removedEntity, false);
			//noinspection unchecked
			return this.trafficRecorder.recordEnrichment(
				session.getId(),
				entity,
				evitaRequest,
				() -> of(
					(T) applyReferenceFetcher(
						evitaRequest,
						entity,
						referenceFetcher
					)
				)
			);
		} else {
			return empty();
		}
	}

	@Override
	public int deleteEntityAndItsHierarchy(int primaryKey, @Nonnull EvitaSessionContract session) {
		return deleteEntityAndItsHierarchy(
			new EvitaRequest(
				Query.query(
					collection(getSchema().getName()),
					filterBy(entityPrimaryKeyInSet(primaryKey)),
					require(entityFetchAll())
				),
				OffsetDateTime.now(),
				EntityReference.class,
				null
			),
			session
		).deletedEntities();
	}

	@Override
	public <T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final EntityIndex globalIndex = getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		if (globalIndex != null) {
			final int[] primaryKeys = evitaRequest.getPrimaryKeys();
			Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
			final int[] entityHierarchy = globalIndex.listHierarchyNodesFromParentIncludingItself(primaryKeys[0]).getArray();
			if (entityHierarchy.length == 0) {
				return new DeletedHierarchy<>(0, entityHierarchy, null);
			} else {
				ServerEntityDecorator removedRoot = null;
				for (int entityToRemove : entityHierarchy) {
					if (removedRoot == null) {
						final EntityWithFetchCount removedEntity = deleteEntityInternal(entityToRemove, session, evitaRequest, EntityWithFetchCount.class)
							.orElseThrow(() -> new EntityMissingException(getEntityType(), primaryKeys, null));
						removedRoot = wrapToDecorator(evitaRequest, removedEntity, false);
					} else {
						deleteEntityInternal(entityToRemove, session, evitaRequest, Void.class);
					}
				}

				final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);

				//noinspection unchecked
				return new DeletedHierarchy<>(
					entityHierarchy.length,
					entityHierarchy,
					ofNullable(removedRoot)
						.map(entity -> this.trafficRecorder.recordEnrichment(
								session.getId(),
								entity,
								evitaRequest,
								() -> (T) applyReferenceFetcherInternal(
									evitaRequest,
									referenceFetcher.initReferenceIndex(entity, this),
									referenceFetcher
								)
							)
						)
						.orElse(null)
				);
			}
		}
		return new DeletedHierarchy<>(0, new int[0], null);
	}

	@Override
	public int deleteEntities(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest) {
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		final QueryPlan queryPlan = QueryPlanner.planQuery(queryContext);

		final EvitaEntityReferenceResponse result = this.trafficRecorder.recordQuery(
			"delete",
			session.getId(),
			queryPlan
		);

		return result
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.map(it -> this.deleteEntity(session, it) ? 1 : 0)
			.sum();
	}

	@Override
	public boolean archiveEntity(@Nonnull EvitaSessionContract session, int primaryKey) {
		if (this.getGlobalIndexIfExists().map(it -> it.contains(primaryKey)).orElse(false)) {
			changeEntityScopeInternal(primaryKey, Scope.ARCHIVED, session,null, Void.class);
			return true;
		} else {
			return false;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> archiveEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest) {
		final int[] primaryKeys = evitaRequest.getPrimaryKeys();
		Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
		if (getGlobalIndexIfExists().map(it -> it.contains(primaryKeys[0])).orElse(false)) {
			final EntityWithFetchCount archivedEntity = changeEntityScopeInternal(primaryKeys[0], Scope.ARCHIVED, session, evitaRequest, EntityWithFetchCount.class)
				.orElseThrow(() -> new EntityMissingException(getEntityType(), primaryKeys, null));
			final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
			final ServerEntityDecorator entity = wrapToDecorator(evitaRequest, archivedEntity, false);
			//noinspection unchecked
			return this.trafficRecorder.recordEnrichment(
				session.getId(),
				entity,
				evitaRequest,
				() -> of((T) applyReferenceFetcher(evitaRequest, entity, referenceFetcher))
			);
		} else {
			return empty();
		}
	}

	@Override
	public boolean restoreEntity(@Nonnull EvitaSessionContract session, int primaryKey) {
		if (this.getGlobalArchiveIndexIfExists().map(it -> it.contains(primaryKey)).orElse(false)) {
			changeEntityScopeInternal(primaryKey, Scope.LIVE, session, null, Void.class);
			return true;
		} else {
			return false;
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> restoreEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest) {
		final int[] primaryKeys = evitaRequest.getPrimaryKeys();
		Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
		if (getGlobalArchiveIndexIfExists().map(it -> it.contains(primaryKeys[0])).orElse(false)) {
			final EntityWithFetchCount restoredEntity = changeEntityScopeInternal(primaryKeys[0], Scope.LIVE, session, evitaRequest, EntityWithFetchCount.class)
				.orElseThrow(() -> new EntityMissingException(getEntityType(), primaryKeys, null));
			final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
			final ServerEntityDecorator entity = wrapToDecorator(evitaRequest, restoredEntity, false);
			//noinspection unchecked
			return this.trafficRecorder.recordEnrichment(
				session.getId(),
				entity,
				evitaRequest,
				() -> of((T) applyReferenceFetcher(evitaRequest, entity, referenceFetcher))
			);
		} else {
			return empty();
		}
	}

	@Override
	public boolean isEmpty() {
		return this.persistenceService.isEmpty(this.catalog.getVersion(), this.dataStoreReader);
	}

	@Override
	public int size() {
		return this.persistenceService.countEntities(this.catalog.getVersion(), this.dataStoreReader);
	}

	@Override
	@Nonnull
	public SealedEntitySchema getSchema() {
		return Objects.requireNonNull(this.schema.get());
	}

	@Nonnull
	@Override
	public String getEntityType() {
		return getInternalSchema().getName();
	}

	/**
	 * Same method as {@link #applyMutation(EvitaSessionContract, EntityMutation)}, but in internal API that doesn't
	 * require session in the input. This method is used from transactional replayer that doesn't have session available.
	 */
	public void applyMutation(@Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		applyMutationInternal(null, entityMutation);
	}

	@Override
	public void applyMutation(@Nonnull EvitaSessionContract session, @Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		applyMutationInternal(session, entityMutation);
	}

	@Nonnull
	@Override
	public SealedEntitySchema updateSchema(
		@Nullable UUID sessionId,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull LocalEntitySchemaMutation... schemaMutation
	) throws SchemaAlteringException {
		// internal schema is expected to be produced on the server side
		final EntitySchema originalSchema = getInternalSchema();
		try {
			EntitySchema updatedSchema = originalSchema;
			final Set<String> updatedReferenceSchemas = CollectionUtils.createHashSet(originalSchema.getReferences().size());
			for (EntitySchemaMutation theMutation : schemaMutation) {
				updatedSchema = (EntitySchema) theMutation.mutate(catalogSchema, updatedSchema);
				/* TOBEDONE #409 JNO - this should be diverted to separate class and handle all necessary DDL operations */
				if (theMutation instanceof SetEntitySchemaWithHierarchyMutation setHierarchy) {
					if (!originalSchema.isWithHierarchy() && setHierarchy.isWithHierarchy()) {
						getGlobalIndexIfExists()
							.ifPresent(it -> it.initRootNodes(it.getAllPrimaryKeys()));
					}
				}
				if (theMutation instanceof ReferenceSchemaMutation referenceSchemaMutation) {
					updatedReferenceSchemas.add(referenceSchemaMutation.getName());
				}
			}

			Assert.isPremiseValid(updatedSchema != null, "Entity collection cannot be dropped by updating schema!");
			Assert.isPremiseValid(updatedSchema instanceof EntitySchema, "Mutation is expected to produce EntitySchema instance!");

			updatedSchema = refreshReflectedSchemas(originalSchema, updatedSchema, updatedReferenceSchemas);

			if (updatedSchema.version() > originalSchema.version()) {
				/* TOBEDONE JNO (#501) - apply this just before commit happens in case validations are enabled */
				// assertAllReferencedEntitiesExist(newSchema);
				// assertReferences(newSchema);
				exchangeSchema(originalSchema, updatedSchema);
			}
		} catch (RuntimeException ex) {
			// revert all changes in the schema (for current transaction) if anything failed
			final EntitySchemaDecorator decorator = new EntitySchemaDecorator(() -> this.catalog.getSchema(), originalSchema);
			this.schema.set(decorator);
			throw ex;
		} finally {
			// finally, store the updated catalog schema to disk
			final EntitySchema updatedInternalSchema = getInternalSchema();
			this.dataStoreBuffer.update(this.catalog.getVersion(), new EntitySchemaStoragePart(updatedInternalSchema));
		}

		return getSchema();
	}

	@Override
	public long getVersion() {
		return this.persistenceService.getEntityCollectionHeader().version();
	}

	@Nonnull
	@Override
	public EntityCollectionStatistics getStatistics(@Nonnull Set<CatalogStatisticsComponent> components) {
		CatalogStatisticsComponent.assertCollectionLevel(components);
		// this.catalog is single-assign (see attachCatalogShell), so it is the catalog generation this collection
		// instance belongs to - the identity and the indexes walked below therefore describe the same version
		final EntityCollectionStatistics.Builder builder = EntityCollectionStatistics.builder(
			this.catalog.getIdentity(), getEntityType()
		);
		// STORAGE_SIZE and FRAGMENTATION are two readings of one listing of this collection's data store files: the
		// first attributes its bytes, the second turns the same live/waste split into a share. Measuring once is not
		// only cheaper - it is the only way the two components cannot describe different moments
		final CollectionStorageFootprint storageFootprint =
			components.contains(CatalogStatisticsComponent.STORAGE_SIZE) ||
				components.contains(CatalogStatisticsComponent.FRAGMENTATION) ?
				this.persistenceService.measureStorageFootprint() : null;
		for (final CatalogStatisticsComponent component : components) {
			switch (component) {
				// always recorded by the builder itself, since nothing else can be interpreted without it
				case IDENTITY -> { }
				case INDEX_SUMMARY -> builder.withIndexSummary(summarizeIndexes());
				case RECORD_COUNTS -> builder.withRecordCounts(countRecords());
				case STORAGE_SIZE -> builder.withStorageSize(
					measureStorageSize(Objects.requireNonNull(storageFootprint))
				);
				case STORAGE_COMPOSITION -> builder.withStorageComposition(composeStorageParts());
				case COLLECTIONS -> builder.withHeader(describeHeader());
				case VOLATILE_STATE -> builder.withVolatileState(describeVolatileState());
				case FRAGMENTATION -> builder.withFragmentation(
					describeFragmentation(Objects.requireNonNull(storageFootprint))
				);
				// `snapshot()` for the same reason `browseIndexes` takes one: the targeted lookups and the total they
				// are subtracted from have to come from ONE state of the map. Read against the live map, a warm-up
				// writer removing an index between the lookups and the count yields a NEGATIVE `omittedIndexCount`.
				// The cost is not uniform and is accepted deliberately: free once the map is sealed (transactional
				// mode hands back the existing trie), an `O(N)` throw-away build while warm-up still holds a
				// `HashMap`. A statistic that cannot be internally invalid is worth more than an `O(1)` that can
				// report a negative count, and this is a management call, not a query path
				case INDEX_CARDINALITY -> builder.withIndexCardinality(
					IndexCardinalityProjection.describe(
						this.indexes.snapshot(), getInternalSchema().getReferences().keySet()
					)
				);
				// unreachable - all of these are catalog-level only and the assertion above already rejected them
				case SESSIONS, COMMIT_PIPELINE, ACTIVITY, HISTORY, DURABILITY -> throw new GenericEvitaInternalError(
					"Catalog-level component `" + component + "` passed the collection-level check!"
				);
			}
		}
		return builder.build();
	}

	@Nonnull
	@Override
	public IndexBrowseResult browseIndexes(@Nonnull IndexBrowseCriteria criteria) {
		final Set<String> declaredReferences = getInternalSchema().getReferences().keySet();
		for (final String referenceName : criteria.referenceNames()) {
			// rejected rather than answered with an empty page: a typo would otherwise read as "this reference has no
			// indexes", which is the one answer an operator investigating index growth must not be given wrongly
			Assert.isTrue(
				declaredReferences.contains(referenceName),
				"Entity collection `" + getEntityType() + "` declares no reference named `" + referenceName + "`!"
			);
		}
		// an immutable snapshot, so the match count and the page contents cannot be taken from two different states -
		// in WARMING_UP the map is otherwise held mutably, where a concurrent bulk load could move the paging offset
		// out from under the walk.
		//
		// `snapshot()` rather than `sealed()`: the latter publishes the frozen map back into the collection's index
		// map, which is correct on the commit path but not from a read. It would make the next warm-up write thaw the
		// map again - an `O(N)` copy per browse - and, worse, publishing a view built by iterating a map another
		// thread is still writing would drop whatever landed during the iteration. A statistics call must not be able
		// to lose an index
		return IndexBrowseProjection.browse(
			getEntityType(), this.indexes.snapshot(), criteria, this.catalog.getIdentity().catalogVersion()
		);
	}

	@Nonnull
	@Override
	public IndexDetail describeIndex(int indexPrimaryKey) throws IndexNotFoundException {
		// resolved through the primary-key map the engine already maintains for its own reference-index lookups, so
		// naming an index costs a hash lookup rather than the map walk a browse pays. No snapshot is taken: exactly
		// one index is read, so there is no second reading for it to be inconsistent with
		final EntityIndex index = getIndexByPrimaryKeyIfExists(indexPrimaryKey);
		if (index == null) {
			throw new IndexNotFoundException(getEntityType(), indexPrimaryKey);
		}
		return IndexDetailProjection.describe(getEntityType(), index);
	}

	/**
	 * Returns the number of indexes this collection holds. Read from the size of the index map, so the cost does not
	 * depend on how large those indexes are - which is what lets the catalog sum it across all collections on every
	 * statistics request.
	 *
	 * @return number of indexes of this collection
	 */
	public int getIndexCount() {
		return this.indexPopulation.total();
	}

	/**
	 * Counts the records of this collection. `totalRecords` keeps the meaning it has always had - the number of entity
	 * body storage parts, which is what {@link #size()} reports - while the live/archived split is the cardinality of
	 * the global index of each scope.
	 *
	 * Archiving an entity moves it between global indexes but leaves its body storage part in place, so `totalRecords`
	 * has never distinguished the two; the split is the number a client actually wants. The three are read from
	 * separate sources and are deliberately not reconciled - a body part in neither global index counts towards
	 * `totalRecords` alone, and that difference is worth seeing rather than hiding.
	 *
	 * Both reads are counters (a storage-part count and a bitmap cardinality), never a walk, which is what allows the
	 * catalog to sum this across every collection on every statistics request.
	 *
	 * @return the {@link CatalogStatisticsComponent#RECORD_COUNTS} component of this collection
	 */
	@Nonnull
	public CollectionRecordCounts countRecords() {
		int liveRecords = 0;
		int archivedRecords = 0;
		for (final Scope scope : Scope.values()) {
			final EntityIndex globalIndex = getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
			if (globalIndex != null) {
				final int scopeRecords = globalIndex.getAllPrimaryKeys().size();
				switch (scope) {
					case LIVE -> liveRecords = scopeRecords;
					case ARCHIVED -> archivedRecords = scopeRecords;
					// a scope added without a counter here would silently vanish from the split while still counting
					// towards `totalRecords`, manufacturing the very reconciliation gap this record reports as signal
					default -> throw new GenericEvitaInternalError(
						"Scope `" + scope + "` has no record counter in the statistics component!"
					);
				}
			}
		}
		return new CollectionRecordCounts(size(), liveRecords, archivedRecords);
	}

	/**
	 * Breaks this collection's data store down by storage-part type - where its bytes actually go. Measured in bytes
	 * rather than record counts, because counts invert the answer whenever many small records share a data store with
	 * a few large ones; the counts travel alongside so the average per type stays exact.
	 *
	 * The breakdown describes the data store as it was last flushed. Records written but not yet flushed are absent
	 * from it, which is why the entity-body count here can trail
	 * {@link CatalogStatisticsComponent#RECORD_COUNTS} while writes are pending - bytes that have not reached the
	 * disk have no place in a breakdown of the disk.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component of this collection
	 */
	@Nonnull
	private CollectionStorageComposition composeStorageParts() {
		return new CollectionStorageComposition(
			StoragePartProjection.toStoragePartUsage(this.persistenceService.measureStoragePartComposition())
		);
	}

	/**
	 * Reads the counters this collection's storage header carries, plus the high-water mark of the largest record its
	 * data store has ever held.
	 *
	 * **`maxRecordSizeBytes` is *largest ever seen*, not *largest currently stored*.** It is only ever widened, so
	 * removing the biggest record never lowers it - see
	 * {@link io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService#getMaxRecordSizeBytes()}.
	 *
	 * @return the {@link CatalogStatisticsComponent#COLLECTIONS} component of this collection
	 */
	@Nonnull
	private CollectionHeaderInfo describeHeader() {
		final EntityCollectionHeader header = this.persistenceService.getEntityCollectionHeader();
		return new CollectionHeaderInfo(
			header.entityTypePrimaryKey(),
			header.version(),
			header.lastPrimaryKey(),
			header.lastEntityIndexPrimaryKey(),
			header.lastInternalPriceId(),
			header.lastKeyId(),
			this.persistenceService.getMaxRecordSizeBytes(),
			// `0` is the storage layer's "this header carries no timestamp" - a header written before 2026.3 - and it
			// becomes an explicit absence here rather than an epoch-zero instant a client would render as a date
			header.lastModifiedMillis() == 0L ?
				null :
				OffsetDateTime.ofInstant(Instant.ofEpochMilli(header.lastModifiedMillis()), ZoneId.systemDefault())
		);
	}

	/**
	 * Reports what this collection's data store holds in memory rather than on disk.
	 *
	 * @return the {@link CatalogStatisticsComponent#VOLATILE_STATE} component of this collection
	 */
	@Nonnull
	private DataStoreVolatileState describeVolatileState() {
		return VolatileStateProjection.toDataStoreVolatileState(measureVolatileData());
	}

	/**
	 * Reports what this collection's data store holds in memory rather than on disk, in the storage layer's own shape.
	 *
	 * Public rather than private because the catalog folds every collection's footprint into its own to answer
	 * {@link CatalogStatisticsComponent#VOLATILE_STATE} at catalog level and lives in another package. Folding the
	 * already-projected API records instead would put the `min` rule for the retained-history timestamp in a second
	 * place.
	 *
	 * @return what this collection's data store holds that is not on disk
	 */
	@Nonnull
	public VolatileDataFootprint measureVolatileData() {
		return this.persistenceService.measureVolatileData();
	}

	/**
	 * Projects the measured footprint of this collection's data store files onto the component that reports it.
	 *
	 * @param footprint the listing the caller measured for this request
	 * @return the {@link CatalogStatisticsComponent#STORAGE_SIZE} component of this collection
	 */
	@Nonnull
	private static CollectionStorageSize measureStorageSize(@Nonnull CollectionStorageFootprint footprint) {
		return new CollectionStorageSize(
			footprint.totalBytes(),
			footprint.liveBytes(),
			footprint.wasteBytes(),
			footprint.awaitingDeletionBytes(),
			footprint.unaccountedBytes()
		);
	}

	/**
	 * Describes how much of this collection's data store is dead weight and when the engine will reclaim it.
	 *
	 * The live and waste bytes come from the footprint the caller already measured - see the comment at that call
	 * site - while the eligibility flag and the projected time come from the persistence layer, which owns the
	 * compaction predicate and must remain the only thing that evaluates it.
	 *
	 * @param footprint the listing the caller measured for this request
	 * @return the {@link CatalogStatisticsComponent#FRAGMENTATION} component of this collection
	 */
	@Nonnull
	private DataStoreFragmentation describeFragmentation(@Nonnull CollectionStorageFootprint footprint) {
		return FragmentationProjection.toDataStoreFragmentation(
			footprint, this.persistenceService.measureCompactionForecast()
		);
	}

	/**
	 * Counts this collection's indexes per (type, scope) pair. Pairs with no index are omitted rather than reported as
	 * zero.
	 *
	 * @return the {@link CatalogStatisticsComponent#INDEX_SUMMARY} component of this collection
	 */
	@Nonnull
	private CollectionIndexSummary summarizeIndexes() {
		// read out of the maintained per-(type, scope) counters rather than walked: a production collection holds
		// hundreds of thousands of per-referenced-entity indexes and this component is polled, so the reading is over
		// a dozen cells rather than over the index map
		final EntityIndexType[] types = EntityIndexType.values();
		final Scope[] scopes = Scope.values();
		final int[][] countsByTypeAndScope = new int[types.length][scopes.length];
		int totalIndexCount = 0;
		int occupiedPairCount = 0;
		for (int type = 0; type < types.length; type++) {
			for (int scope = 0; scope < scopes.length; scope++) {
				final int count = this.indexPopulation.countOf(types[type], scopes[scope]);
				countsByTypeAndScope[type][scope] = count;
				totalIndexCount += count;
				if (count > 0) {
					occupiedPairCount++;
				}
			}
		}
		final IndexTypeCount[] byTypeAndScope = new IndexTypeCount[occupiedPairCount];
		int index = 0;
		for (int type = 0; type < types.length; type++) {
			for (int scope = 0; scope < scopes.length; scope++) {
				if (countsByTypeAndScope[type][scope] > 0) {
					byTypeAndScope[index++] = new IndexTypeCount(
						types[type], scopes[scope], countsByTypeAndScope[type][scope]
					);
				}
			}
		}
		return new CollectionIndexSummary(totalIndexCount, byTypeAndScope);
	}

	/**
	 * Checks whether the process, task, or operation has been terminated.
	 *
	 * @return true if the process is terminated, false otherwise.
	 */
	public boolean isTerminated() {
		return this.terminated.get();
	}

	@Override
	public void terminate() {
		Assert.isTrue(
			this.terminated.compareAndSet(false, true),
			"Collection was already terminated!"
		);
		Assert.isPremiseValid(
			!Transaction.isTransactionAvailable(),
			"Entity collection cannot be terminated within transaction!"
		);
		IOUtils.closeQuietly(this.persistenceService::close);
	}

	/**
	 * Notifies that other entity type in catalog has been renamed. When any of reference in this schema refers to
	 * the renamed entity schema, it needs to be automatically altered to refer to the new name.
	 *
	 * @param oldName       the old name of the entity type
	 * @param newCollection the instance of the updated (renamed) collection
	 * @return {@code true} if the schema was updated, {@code false} otherwise
	 */
	public boolean notifyEntityTypeRenamed(@Nonnull String oldName, @Nonnull EntityCollection newCollection) {
		final EntitySchema originalSchema = getInternalSchema();
		final SealedEntitySchema newSchema = newCollection.getSchema();
		final String newSchemaName = newSchema.getName();

		EntitySchema updatedSchema = originalSchema;
		for (ReferenceSchemaContract referenceSchemaContract : originalSchema.getReferences().values()) {
			if (referenceSchemaContract.isReferencedEntityTypeManaged() && referenceSchemaContract.getReferencedEntityType().equals(oldName)) {
				if (referenceSchemaContract instanceof ReflectedReferenceSchema referenceSchema) {
					final Optional<ReferenceSchemaContract> updatedReference = newSchema.getReference(referenceSchema.getReflectedReferenceName());
					if (updatedReference.isPresent()) {
						updatedSchema = updatedSchema.withReplacedReferenceSchema(
							referenceSchema.withReferencedSchema(updatedReference.get())
								.withUpdatedReferencedEntityType(newSchemaName)
						);
					} else {
						updatedSchema = updatedSchema.withReplacedReferenceSchema(
							referenceSchema.withUpdatedReferencedEntityType(newSchemaName)
						);
					}
				} else if (referenceSchemaContract instanceof ReferenceSchema referenceSchema) {
					updatedSchema = updatedSchema.withReplacedReferenceSchema(
						referenceSchema.withUpdatedReferencedEntityType(newSchemaName)
					);
				}
			}
			if (referenceSchemaContract.isReferencedGroupTypeManaged() && Objects.equals(referenceSchemaContract.getReferencedGroupType(), oldName)) {
				if (referenceSchemaContract instanceof ReferenceSchema referenceSchema) {
					updatedSchema = updatedSchema.withReplacedReferenceSchema(
						referenceSchema.withUpdatedReferencedGroupType(newSchemaName)
					);
				}
			}
		}
		if (originalSchema != updatedSchema) {
			exchangeSchema(originalSchema, updatedSchema);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Method is part of internal API and should be called at the moment entity collection is first created.
	 * It should initialize all {@link ReflectedReferenceSchemaContract} in {@link EntitySchemaContract} with copies
	 * that contain reference to the original {@link ReferenceSchemaContract} they relate to. This is necessary to
	 * properly calculate inherited properties and attributes.
	 */
	public void initSchema() {
		final EntitySchema originalSchema = getInternalSchema();
		final Collection<ReferenceSchemaContract> referenceSchemas = originalSchema.getReferences().values();
		final List<ReferenceSchemaContract> updatedReferenceSchemas = new ArrayList<>(referenceSchemas.size());
		for (ReferenceSchemaContract referenceSchema : referenceSchemas) {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				final Optional<EntitySchemaContract> targetEntitySchema;
				if (originalSchema.getName().equals(reflectedReferenceSchema.getReferencedEntityType())) {
					// self referenced schema
					targetEntitySchema = of(originalSchema);
				} else {
					targetEntitySchema = this.catalog.getCollectionForEntity(reflectedReferenceSchema.getReferencedEntityType())
						.map(EntityCollectionContract::getSchema);
				}
				targetEntitySchema
					.flatMap(it -> it.getReference(reflectedReferenceSchema.getReflectedReferenceName()))
					.ifPresent(originalReference -> updatedReferenceSchemas.add(reflectedReferenceSchema.withReferencedSchema(originalReference)));
			}
		}
		// exchange schema if it was updated
		if (!updatedReferenceSchemas.isEmpty()) {
			exchangeSchema(
				originalSchema,
				originalSchema.withReplacedReferenceSchema(
					updatedReferenceSchemas.toArray(ReferenceSchemaContract[]::new)
				)
			);
		}
	}

	/**
	 * Fetches a list of SealedEntity objects based on the provided primary keys, the EvitaRequest, and session
	 * information. Method is part of the internal API and is used to fetch entities from the underlying storage as
	 * a part of the larger query - thus it doesn't record data into {@link TrafficRecorder}.
	 *
	 * @param primaryKeys an array of integer values representing the primary keys of the entities to be fetched
	 * @param evitaRequest an instance of EvitaRequest that contains the request parameters
	 * @param session an instance of EvitaSessionContract that represents the current session context
	 *
	 * @return a list of SealedEntity objects matching the provided primary keys and request parameters
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(@Nonnull int[] primaryKeys, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return fetchEntities(primaryKeys, evitaRequest, session, referenceFetcher);
	}

	/**
	 * Fetches an entity based on the provided primary key and session details. Method is part of the internal API and
	 * is used to fetch entities from the underlying storage as a part of the larger query - thus it doesn't record data
	 * into {@link TrafficRecorder}.
	 *
	 * @param primaryKey the unique identifier for the desired entity
	 * @param evitaRequest the request object containing parameters and configurations for fetching the entity
	 * @param session the session contract that manages interaction contexts for fetching entities
	 * @return an {@link Optional} containing the sealed entity if found, otherwise an empty Optional
	 */
	@Nonnull
	public Optional<SealedEntity> fetchEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return fetchEntity(primaryKey, evitaRequest, session, referenceFetcher);
	}

	/**
	 * Fetches a list of BinaryEntity objects based on the provided primary keys, EvitaRequest, and session.
	 * For each primary key, the method attempts to fetch the corresponding {@link BinaryEntity}.
	 * Only the entities that are successfully fetched and present are returned in the list. Method is part of the
	 * internal API and is used to fetch entities from the underlying storage as a part of the larger query - thus it
	 * doesn't record data into {@link TrafficRecorder}.
	 *
	 * @param primaryKeys an array of primary keys used to identify the BinaryEntity objects
	 * @param evitaRequest the request context in which the fetching process is executed
	 * @param session the session contract containing session-related information and configurations
	 * @return a list of successfully retrieved BinaryEntity objects
	 */
	@Nonnull
	public List<BinaryEntity> fetchBinaryEntities(@Nonnull int[] primaryKeys, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return Arrays.stream(primaryKeys)
			.mapToObj(it -> fetchBinaryEntity(it, evitaRequest, session))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.toList();
	}

	/**
	 * Retrieves a binary entity based on the provided primary key, evita request, and session contract.
	 * Uses caching mechanism to analyze and potentially enrich the entity's data based on
	 * entity requirements specified in the request. Method is part of the internal API and is used to fetch entities
	 * from the underlying storage as a part of the larger query - thus it doesn't record data into {@link TrafficRecorder}.
	 *
	 * @param primaryKey the unique identifier of the binary entity to be fetched
	 * @param evitaRequest the request containing specifications and requirements for entity retrieval
	 * @param session the session contract representing the current session for database operations
	 *
	 * @return an Optional containing the fetched BinaryEntity if found, otherwise an empty Optional
	 */
	@Nonnull
	public Optional<BinaryEntity> fetchBinaryEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final long catalogVersion = this.catalog.getVersion();
		final Optional<BinaryEntity> entity = this.cacheSupervisor.analyse(
				session,
				primaryKey,
				getSchema().getName(),
				evitaRequest.getEntityRequirement(),
				() -> {
					final BinaryEntityWithFetchCount binaryEntityWithFetchCount = this.persistenceService.readBinaryEntity(
						catalogVersion,
						primaryKey,
						evitaRequest,
						getInternalSchema(),
						session,
						entityType -> entityType.equals(getEntityType()) ?
							this : this.catalog.getCollectionForEntityOrThrowException(entityType),
						this.dataStoreReader
					);

					return binaryEntityWithFetchCount == null ?
						null :
						new ServerBinaryEntityDecorator(
							binaryEntityWithFetchCount.entity(),
							binaryEntityWithFetchCount.ioFetchCount(),
							binaryEntityWithFetchCount.ioFetchedBytes()
						);
				},
				binaryEntity -> {
					final BinaryEntityWithFetchCount binaryEntityWithFetchCount = this.persistenceService.enrichEntity(
						catalogVersion,
						getInternalSchema(),
						binaryEntity,
						evitaRequest,
						this.dataStoreReader
					);
					return new ServerBinaryEntityDecorator(
						binaryEntityWithFetchCount.entity(),
						binaryEntity.getIoFetchCount() + binaryEntityWithFetchCount.ioFetchCount(),
						binaryEntity.getIoFetchedBytes() + binaryEntityWithFetchCount.ioFetchedBytes()
					);
				}
			)
			.map(it -> it);
		return entity.map(it -> limitEntity(it, Objects.requireNonNull(evitaRequest.getEntityRequirement())));
	}

	/**
	 * Retrieves a list of `SealedEntity` objects based on the given primary keys, request parameters,
	 * session context, and reference fetcher. This method ensures that each entity is retrieved
	 * in its current version and applies any necessary limitations and reference fetching specified
	 * by the request.
	 *
	 * @param primaryKeys An array of primary keys used to identify the entities to be retrieved.
	 * @param evitaRequest An object containing the request parameters and options for retrieving the entities.
	 * @param session The session context in which the entities are being retrieved.
	 * @param referenceFetcher A utility for fetching additional references associated with the entities.
	 * @return A list of `SealedEntity` objects that have been retrieved according to the specified parameters.
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(
		@Nonnull int[] primaryKeys,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		// retrieve current version of entity
		final List<ServerEntityDecorator> entityDecorators = Arrays.stream(primaryKeys)
			.mapToObj(it -> fetchEntityDecorator(it, evitaRequest, session))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.toList();

		return applyReferenceFetcher(
			evitaRequest,
			entityDecorators.stream().map(it -> limitEntity(it, evitaRequest, session)).toList(),
			referenceFetcher
		);
	}

	/**
	 * Returns the entity body that reflects input request object using provided session and primary key.
	 * The method will try to fetch the entity from cache if possible, otherwise it will fetch it from the underlying
	 * storage and registers it as an cache adept.
	 *
	 * @param primaryKey the primary key of the entity to retrieve
	 * @param evitaRequest the request context containing parameters required for fetching the entity
	 * @param session the current session associated with the request
	 * @return an {@link Optional} containing the {@link ServerEntityDecorator} if the entity is found and meets
	 * the criteria, otherwise an empty {@link Optional}
	 */
	@Nonnull
	public Optional<ServerEntityDecorator> fetchEntityDecorator(
		int primaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session
	) {
		final SealedEntitySchema theSchema = getSchema();
		return this.cacheSupervisor.analyse(
			session,
			primaryKey,
			theSchema.getName(),
			evitaRequest.getAlignedNow(),
			evitaRequest.getEntityRequirement(),
			() -> {
				final EntityWithFetchCount internalEntity = getEntityById(primaryKey, evitaRequest);
				if (internalEntity == null) {
					return null;
				} else if (
					!ofNullable(evitaRequest.getRequiredOrImplicitLocale())
						.map(it -> !theSchema.isLocalized() || internalEntity.entity().getLocales().contains(it))
						.orElse(true)
				) {
					return null;
				} else {
					return wrapToDecorator(evitaRequest, internalEntity, null);
				}
			},
			theEntity -> enrichEntityInternal(theEntity, evitaRequest)
		);
	}

	/**
	 * Applies a reference fetcher to a sealed entity, potentially enriching entity references with complex entity objects.
	 *
	 * @param sealedEntity the entity to which the reference fetcher will be applied
	 * @param referenceFetcher an instance of ReferenceFetcher used to initialize references in the entity
	 * @return a server entity decorator with the reference fetcher's modifications applied
	 * @throws EntityAlreadyRemovedException if the entity has already been removed
	 */
	@Nonnull
	public ServerEntityDecorator applyReferenceFetcher(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) throws EntityAlreadyRemovedException {
		if (referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION) {
			return (ServerEntityDecorator) sealedEntity;
		} else {
			referenceFetcher.initReferenceIndex(sealedEntity, this);
			return applyReferenceFetcherInternal(
				evitaRequest,
				(ServerEntityDecorator) sealedEntity,
				referenceFetcher
			);
		}
	}

	/**
	 * Applies the provided {@link ReferenceFetcher} to a list of {@link SealedEntity} objects,
	 * potentially enriching its references with complex entity objects.
	 *
	 * @param sealedEntities the list of sealed entities to which the reference fetcher will be applied
	 * @param referenceFetcher the reference fetcher to be applied to the list of sealed entities
	 * @return a transformed list of sealed entities after applying the reference fetcher
	 * @throws EntityAlreadyRemovedException if an entity has already been removed
	 */
	@Nonnull
	public List<SealedEntity> applyReferenceFetcher(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull List<SealedEntity> sealedEntities,
		@Nonnull ReferenceFetcher referenceFetcher
	) throws EntityAlreadyRemovedException {
		if (referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION) {
			return sealedEntities;
		} else {
			return referenceFetcher.initReferenceIndex(sealedEntities, this)
				.stream()
				.map(it -> applyReferenceFetcherInternal(evitaRequest, (ServerEntityDecorator) it, referenceFetcher))
				.map(SealedEntity.class::cast)
				.toList();
		}
	}

	/**
	 * Method checks whether the entity is {@link ServerEntityDecorator} and verifies the decorator wraps an entity with
	 * fetched reference storage part. If it does not, new entity decorator instance is created wrapping the same entity
	 * with the reference container fetched. New instance will share same predicates to that the methods of
	 * the decorator will produce the same output as the original entity decorator in the input of this method.
	 *
	 * The caller will be able to unwrap the decorator using {@link ServerEntityDecorator#getDelegate()} and access
	 * reference data in the wrapped entity instance. This is necessary for proper operation
	 * of {@link ReferencedEntityFetcher} implementation.
	 */
	@Nonnull
	public <T extends SealedEntity> T ensureReferencesFetched(@Nonnull T entity)
		throws EntityAlreadyRemovedException {
		if (entity instanceof ServerEntityDecorator partiallyLoadedEntity) {
			if (partiallyLoadedEntity.getReferencePredicate().isRequiresEntityReferences()) {
				// if the references are already available, return the input decorator without change
				return entity;
			} else {
				// if they were not fetched, re-wrap current decorator around entity with fetched references
				// no predicates are changed in the output decorator, only inner entity is more rich
				final EntityWithFetchCount entityWithFetchCount = this.persistenceService.enrichEntity(
					this.catalog.getVersion(),
					// use all data from existing entity
					partiallyLoadedEntity,
					partiallyLoadedEntity.getHierarchyPredicate(),
					partiallyLoadedEntity.getAttributePredicate(),
					partiallyLoadedEntity.getAssociatedDataPredicate(),
					new ReferenceContractSerializablePredicate(true),
					partiallyLoadedEntity.getPricePredicate(),
					this.dataStoreReader,
					partiallyLoadedEntity.getDelegate().getReferenceChunkTransformer()
				);
				//noinspection unchecked
				return (T) ServerEntityDecorator.decorate(
					// load references if missing
					entityWithFetchCount.entity(),
					// use original schema
					getInternalSchema(),
					// fetch parents if requested
					partiallyLoadedEntity.parentAvailable() ?
						partiallyLoadedEntity.getParentEntity().orElse(null) : null,
					// show / hide locales the entity is fetched in
					partiallyLoadedEntity.getLocalePredicate(),
					// show / hide parent the entity is fetched with
					partiallyLoadedEntity.getHierarchyPredicate(),
					// show / hide attributes information
					partiallyLoadedEntity.getAttributePredicate(),
					// show / hide associated data information
					partiallyLoadedEntity.getAssociatedDataPredicate(),
					// show / hide references information
					partiallyLoadedEntity.getReferencePredicate(),
					// show / hide price information
					partiallyLoadedEntity.getPricePredicate(),
					// propagate original date time
					partiallyLoadedEntity.getAlignedNow(),
					// propagate information about I/O fetch count
					entityWithFetchCount.ioFetchCount(),
					// propagate information about I/O fetched bytes
					entityWithFetchCount.ioFetchedBytes()
				);
			}
		} else {
			// cannot execute enrichment for non-decorator entity
			// we cannot extract the information, whether the entity has no references whatsoever, or they just were not fetched
			return entity;
		}
	}

	/**
	 * Returns internally held {@link EntitySchema}.
	 */
	@Nonnull
	public EntitySchema getInternalSchema() {
		return this.schema == null ? this.initialSchema : Objects.requireNonNull(this.schema.get()).getDelegate();
	}

	/**
	 * Returns entity index by its key. If such index doesn't exist, NULL is returned.
	 */
	@Nullable
	public EntityIndex getIndexByKeyIfExists(@Nonnull EntityIndexKey entityIndexKey) {
		return this.dataStoreBuffer.getIndexIfExists(entityIndexKey, this.indexes::get);
	}

	/**
	 * Returns entity index by its storage primary key, or null if not found. Used to resolve `int[]` storage PKs
	 * returned by `ReferencedTypeEntityIndex.getAllReferenceIndexes(int)` into actual `ReducedGroupEntityIndex` /
	 * `ReducedEntityIndex` instances.
	 */
	@Nullable
	public EntityIndex getIndexByPrimaryKeyIfExists(int entityIndexPrimaryKey) {
		return this.dataStoreBuffer.getIndexIfExists(entityIndexPrimaryKey, this.indexesByPrimaryKey::get);
	}

	/**
	 * Method returns {@link GlobalEntityIndex} or throws an exception if it hasn't yet exist.
	 */
	@Nonnull
	public GlobalEntityIndex getGlobalIndex() {
		final EntityIndex globalIndex = getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		Assert.isPremiseValid(globalIndex instanceof GlobalEntityIndex, "Global index not found in entity collection of `" + getSchema().getName() + "`.");
		return (GlobalEntityIndex) globalIndex;
	}

	/**
	 * Method returns {@link GlobalEntityIndex} or returns empty result if missing.
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalIndexIfExists() {
		final Optional<EntityIndex> globalIndex = ofNullable(getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL)));
		return globalIndex
			.map(it -> {
				Assert.isPremiseValid(
					it instanceof GlobalEntityIndex,
					() -> "Invalid type of the global index (`" + it.getClass() + "`) in entity collection of `" + getSchema().getName() + "`.");
				return ofNullable((GlobalEntityIndex) it);
			})
			.orElse(empty());
	}

	/**
	 * Method returns {@link GlobalEntityIndex} of archived entities or returns empty result if missing.
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalArchiveIndexIfExists() {
		final Optional<EntityIndex> globalIndex = ofNullable(getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED)));
		return globalIndex
			.map(it -> {
				Assert.isPremiseValid(
					it instanceof GlobalEntityIndex,
					() -> "Invalid type of the archive global index (`" + it.getClass() + "`) in entity collection of `" + getSchema().getName() + "`.");
				return ofNullable((GlobalEntityIndex) it);
			})
			.orElse(empty());
	}


	/**
	 * Dispatches {@link IndexMutation} instances to their registered {@link IndexMutationExecutor}. Passes the
	 * {@link EntityIndexMaintainer} (which implements {@link IndexMutationTarget}) so executors can access indexes,
	 * schema, triggers, and query evaluation without seeing the full {@link EntityCollection} API surface.
	 *
	 * Zero allocations — `entityIndexCreator` is the target (already exists as a field).
	 * No switch/case or orchestration logic in the collection.
	 *
	 * The session is temporarily set on the `entityIndexCreator` for the duration of the dispatch so that
	 * `evaluateFilter()` can create a `QueryPlanningContext`. The session reference is cleared in a `finally`
	 * block to prevent leaking beyond the dispatch scope.
	 *
	 * @param entityIndexMutation transport envelope containing mutations to dispatch
	 * @param session             active session for query evaluation during dispatch, may be null during WAL replay
	 *                            when no session context is available
	 */
	public void applyIndexMutations(
		@Nonnull EntityIndexMutation entityIndexMutation,
		@Nullable EvitaSessionContract session
	) {
		this.entityIndexCreator.setSession(session);
		try {
			for (final IndexMutation mutation : entityIndexMutation.mutations()) {
				IndexMutationExecutorRegistry.INSTANCE.dispatch(mutation, this.entityIndexCreator);
			}
		} finally {
			this.entityIndexCreator.setSession(null);
		}
	}

	/**
	 * Method creates {@link QueryPlanningContext} that is used for read operations.
	 */
	@Nonnull
	public QueryPlanningContext createQueryContext(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSession session
	) {
		return new QueryPlanningContext(
			queryContext,
			this.catalog,
			this,
			session, evitaRequest,
			queryContext.getCurrentStep(),
			this.indexes,
			this.indexesByPrimaryKey,
			this.cacheSupervisor
		);
	}

	/**
	 * Method creates {@link QueryPlanningContext} that is used for read operations.
	 */
	@Nonnull
	public QueryPlanningContext createQueryContext(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return new QueryPlanningContext(
			this.catalog,
			this,
			session, evitaRequest,
			evitaRequest.isQueryTelemetryRequested() ? QueryTelemetry.root(QueryPhase.OVERALL) : null,
			this.indexes,
			this.indexesByPrimaryKey,
			this.cacheSupervisor
		);
	}

	/**
	 * Applies limit & enrich logic on provided collection of entities fetched in unknown richness so that they conform
	 * to passed fetch request. For deep fetching of references, the reference fetcher is used.
	 *
	 * @param entities The collection of sealed entities to be processed.
	 * @param fetchRequest The request containing the parameters for fetching and limiting the entities.
	 * @param referenceFetcher The reference fetcher used to apply additional processing to the entities.
	 * @return A list of sealed entities that have been limited and processed according to the given fetch request and reference fetcher.
	 */
	@Nonnull
	public List<SealedEntity> limitAndFetchExistingEntities(
		@Nonnull Collection<? extends SealedEntity> entities,
		@Nonnull EvitaRequest fetchRequest,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		return applyReferenceFetcher(
			fetchRequest,
			entities
				.stream()
				.map(it -> enrichEntityInternal(it, fetchRequest))
				.map(it -> limitEntityInternal(it, fetchRequest))
				.map(SealedEntity.class::cast)
				.toList(),
			referenceFetcher
		);
	}

	/**
	 * Flush operation persists all information kept in non-transactional buffers to the disk asynchronously.
	 * After future is done the {@link CatalogPersistenceService} is fully synced with the disk file and will not
	 * contain any non-persisted data.
	 * Flush operation is ignored when there are no changes present in {@link CatalogPersistenceService}.
	 */
	@Nonnull
	public ProgressingFuture<EntityCollectionHeaderWithCollection> createFlushFuture() {
		final TrappedChanges trappedChanges = this.dataStoreBuffer.popTrappedChanges();
		return new ProgressingFuture<>(
			trappedChanges.getTrappedChangesCount(),
			progressingFuture -> flushInternal(
				progressingFuture::updateProgress,
				trappedChanges
			)
		);
	}

	/**
	 * Flush operation persists immediately all information kept in non-transactional buffers to the disk.
	 * At the end of this method call {@link CatalogPersistenceService} is fully synced with the disk file and will
	 * not contain any non-persisted data. Flush operation
	 * is ignored when there are no changes present in {@link CatalogPersistenceService}.
	 */
	@Nonnull
	public EntityCollectionHeaderWithCollection flush() {
		final TrappedChanges trappedChanges = this.dataStoreBuffer.popTrappedChanges();
		return flushInternal(
			Functions.noOpIntConsumer(),
			trappedChanges
		);
	}

	/**
	 * Flushes the internal state by persisting trapped changes and returning an updated
	 * entity collection header alongside an updated collection instance.
	 *
	 * @param progressObserver an {@link IntConsumer} used to observe the progress of the flush operation
	 * @param trappedChanges an instance of {@link TrappedChanges} representing changes to be persisted during the flush
	 * @return an instance of {@link EntityCollectionHeaderWithCollection} containing the updated entity collection header
	 *         and the corresponding collection state
	 */
	@Nonnull
	private EntityCollectionHeaderWithCollection flushInternal(
		@Nonnull IntConsumer progressObserver,
		@Nonnull TrappedChanges trappedChanges
	) {
		// the caller has already popped `trappedChanges` off the buffer, destructively and along with every index's
		// change-detection baseline, so nothing below can be re-collected if it fails. Both flush shapes - the
		// asynchronous `createFlushFuture` and the synchronous `flush` - run their write through here, which makes this
		// the single point where such a failure can be recorded against the collection that suffered it.
		try {
			this.persistenceService.flushTrappedUpdates(0L, trappedChanges, progressObserver);
			final EntityCollectionHeader entityCollectionHeader = this.persistenceService.getEntityCollectionHeader();
			final long previousVersion = entityCollectionHeader.version();
			return this.catalogPersistenceService.flush(
					0L,
					this.headerInfoSupplier,
					entityCollectionHeader,
					this.dataStoreBuffer
				)
				.map(
					it -> {
						final EntityCollectionHeader newHeader = it.getEntityCollectionHeader();
						return this.persistenceService == it ?
							new EntityCollectionHeaderWithCollection(
								newHeader,
								this,
								newHeader.version() > previousVersion
							) :
							new EntityCollectionHeaderWithCollection(
								newHeader,
								this.createCopyWithNewPersistenceService(newHeader.version(), CatalogState.WARMING_UP, it),
								true
							);
					}
				)
				.orElseGet(
					() -> new EntityCollectionHeaderWithCollection(
						this.getEntityCollectionHeader(), this, false
					)
				);
		} catch (Throwable ex) {
			// the collected changes are lost and this collection's persisted state is now incomplete: refuse every
			// later flush of it rather than write on top of baselines that claim the lost changes were persisted.
			// Catching Throwable rather than RuntimeException is deliberate: an Error such as an OutOfMemoryError mid
			// flush must poison too, otherwise a later collect could silently write over baselines. The cause is always
			// rethrown, so this never uses exceptions for control flow
			this.dataStoreBuffer.poison(ex);
			throw ex;
		}
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		attachCatalogShell(catalog);
		// wire the reduced indexes' price ref chains to the super price indexes of this collection's own GLOBAL
		// entity index (same scope) — the price chain no longer holds a catalog back-reference of its own
	}

	/**
	 * Attaches only the catalog-level shell of this collection: the catalog back-reference and the schema decorator
	 * that reads through it. Enforces single attachment (this is the one sanctioned catalog back-edge, so a second
	 * attach is a programming error). Deliberately does NOT wire the reduced indexes' price ref chains — callers that
	 * build a fresh collection sharing {@link #indexes} by reference (see {@link #createCopyWithNewPersistenceService})
	 * hold indexes that are already wired, and re-running the wiring would trip the price chain's single-assign guards.
	 *
	 * @param catalog the catalog version this collection is being attached to
	 */
	private void attachCatalogShell(@Nonnull Catalog catalog) {
		Assert.isPremiseValid(this.catalog == null, "Catalog was already attached to this collection!");
		this.catalog = catalog;
		this.schema = new TransactionalReference<>(
			new EntitySchemaDecorator(catalog::getSchema, this.initialSchema)
		);
	}

	@Override
	public DataStoreChanges createLayer() {
		// captures the dirty-index-key snapshot: this collection's merge prunes on it (see pruneMergeIndexes)
		return new DataStoreChanges(
			Transaction.createTransactionalPersistenceService(
				this.persistenceService.getStoragePartPersistenceService()
			),
			true
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.schema.removeLayer(transactionalLayer);
		this.indexes.removeLayer(transactionalLayer);
		this.indexesByPrimaryKey.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public EntityCollection createCopyWithMergedTransactionalMemory(@Nullable DataStoreChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final long catalogVersion = this.catalog.getVersion();
		final DataStoreChanges transactionalChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(this);
		final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> newPersistenceService = this.catalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, this.getEntityType(), this.entityTypePrimaryKey
		);
		if (transactionalChanges != null) {
			// this is the DIRTY branch, and it must stay the only route for a collection that has pending changes:
			// indexes are rebuilt here by merging their transactional layer, which yields fresh instances. The clean
			// branch below instead forwards indexes to the next catalog version BY REFERENCE
			// (createIndexCopiesForNewCatalogAttachment), which is sound only because a dirty collection never reaches
			// it — routing one through there would share a live transactional layer across two catalog versions
			transactionalLayer.removeTransactionalMemoryLayer(this);
			// this creates copy of the indexes with all changes applied - pruned: only the indexes this transaction
			// actually mutated (plus the ones its key delta added or replaced) are rebuilt, every other index is
			// carried across the catalog version by reference. Both keyings of the forest come back derived from that
			// one delta, layers disposed of
			final IndexTuple indexTuple = pruneMergeIndexes(
				transactionalLayer, transactionalChanges.popLastCommittedDirtyIndexKeys()
			);
			return new EntityCollection(
				catalogVersion,
				CatalogState.ALIVE,
				this.entityTypePrimaryKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.schema)
					.map(EntitySchemaDecorator::getDelegate)
					.orElseThrow(() -> new GenericEvitaInternalError("Schema was unexpectedly found null after transaction completion!")),
				this.pkSequence,
				this.indexPkSequence,
				this.pricePkSequence,
				this.catalogPersistenceService,
				newPersistenceService,
				indexTuple.indexes(),
				indexTuple.indexesByPk(),
				indexTuple.indexPopulation(),
				this.cacheSupervisor,
				this.trafficRecorder
			);
		} else {
			final ReferenceChanges<EntitySchemaDecorator> schemaChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(this.schema);
			if (schemaChanges != null) {
				Assert.isPremiseValid(
					Objects.requireNonNull(schemaChanges.get()).version() == getSchema().version(),
					"Schema was unexpectedly modified!"
				);
				transactionalLayer.removeTransactionalMemoryLayerIfExists(this.schema);
			}
			Assert.isPremiseValid(
				transactionalLayer.getTransactionalMemoryLayerIfExists(this.indexes) == null,
				"Indexes are unexpectedly modified!"
			);
			Assert.isPremiseValid(
				transactionalLayer.getTransactionalMemoryLayerIfExists(this.indexesByPrimaryKey) == null,
				"Indexes are unexpectedly modified!"
			);
			if (this.persistenceService != newPersistenceService) {
				// if the compaction occurred, the persistence service may have changed
				// we just create a new collection with the new persistence service, but leave the rest of the state intact
				final IndexTuple indexTuple = createIndexCopiesForNewCatalogAttachment();
				return new EntityCollection(
					catalogVersion,
					CatalogState.ALIVE,
					this.entityTypePrimaryKey,
					getInternalSchema(),
					this.pkSequence,
					this.indexPkSequence,
					this.pricePkSequence,
					this.catalogPersistenceService,
					newPersistenceService,
					indexTuple.indexes(),
					indexTuple.indexesByPk(),
					indexTuple.indexPopulation(),
					this.cacheSupervisor,
					this.trafficRecorder
				);
			} else {
				// no changes were present - we return shallow copy
				return createCopyForNewCatalogAttachment(CatalogState.ALIVE);
			}
		}
	}

	/**
	 * Creates a new copy of the EntityCollection object with a new persistence service.
	 *
	 * @param newPersistenceService the new persistence service to be set
	 * @return a new EntityCollection object with the updated persistence service
	 */
	@Nonnull
	public EntityCollection createCopyWithNewPersistenceService(
		long catalogVersion,
		@Nonnull CatalogState catalogState,
		@Nonnull EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> newPersistenceService
	) {
		final EntitySchema internalSchema = this.getInternalSchema();
		// an ALIVE collection forwards its immutable snapshots, which the target adopts in O(1); a WARMING_UP one has no
		// snapshot to forward - its backing buffers are deliberately mutable so bulk writes stay O(1) - and sealing them
		// here would only build tries that the next warm-up write immediately thaws again
		final boolean warmingUp = catalogState == CatalogState.WARMING_UP;
		final Map<EntityIndexKey, EntityIndex> forwardedIndexes = warmingUp ?
			this.indexes : this.indexes.sealed();
		final Map<Integer, EntityIndex> forwardedIndexesByPk = warmingUp ?
			this.indexesByPrimaryKey : this.indexesByPrimaryKey.sealed();
		final EntityCollection entityCollection = new EntityCollection(
			catalogVersion,
			catalogState,
			this.entityTypePrimaryKey,
			internalSchema,
			this.pkSequence,
			this.indexPkSequence,
			this.pricePkSequence,
			this.catalogPersistenceService,
			newPersistenceService,
			forwardedIndexes,
			forwardedIndexesByPk,
			// carried by value even when the maps themselves are forwarded by reference: a WARMING_UP collection hands
			// over its still-mutable buffer, and the counts must follow the collection that will be written through
			this.indexPopulation.copy(),
			this.cacheSupervisor,
			this.trafficRecorder
		);
		// the catalog remains the same here; attach only the collection shell. The fresh copy shares this.indexes by
		// reference and they are already wired to their super price indexes, so index wiring must NOT re-run here —
		// re-wiring would trip the price chain's single-assign guards. The copy's initialSchema equals internalSchema
		// (set by the constructor above), so the shell's schema decorator is identical to the previous hand-wiring.
		entityCollection.attachCatalogShell(this.catalog);
		return entityCollection;
	}

	/**
	 * Creates a new copy of the Entity collection with the same state as the current one.
	 *
	 * @return a new EntityCollection object with the same state as the current one
	 */
	@Override
	@Nonnull
	public EntityCollection createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		final IndexTuple indexTuple = createIndexCopiesForNewCatalogAttachment();
		return new EntityCollection(
			this.catalog.getVersion(),
			catalogState,
			this.entityTypePrimaryKey,
			this.getInternalSchema(),
			this.pkSequence,
			this.indexPkSequence,
			this.pricePkSequence,
			this.catalogPersistenceService,
			this.persistenceService,
			indexTuple.indexes(),
			indexTuple.indexesByPk(),
			indexTuple.indexPopulation(),
			this.cacheSupervisor,
			this.trafficRecorder
		);
	}

	/**
	 * Adds an index to the collection of indexes. If the provided index is catalog-related,
	 * it will also be attached to the corresponding catalog and entity type.
	 *
	 * @param entityIndex the index to be added, must not be null
	 */
	public void addIndex(@Nonnull EntityIndex entityIndex) {
		// disk load / WAL replay register GLOBAL indexes before reduced ones, so the shared price records a freshly
		// deserialized reduced index has to be repointed at are already present in this collection's GLOBAL entity index.
		// This is the ONLY attach-time price step left: the reduced index keeps no super-index pointer to refresh, it is
		// handed the GLOBAL's price index per operation instead.
		if (entityIndex instanceof AbstractReducedEntityIndex reducedIndex) {
			reducedIndex.getPriceIndex().restorePriceRecords(
				resolveGlobalIndex(reducedIndex.getIndexKey().scope()).getPriceIndex()
			);
		}
		this.indexes.put(entityIndex.getIndexKey(), entityIndex);
		this.indexesByPrimaryKey.put(entityIndex.getPrimaryKey(), entityIndex);
		// disk load and WAL replay attach indexes through here, outside any transaction, so the count moves inline -
		// there is no commit to derive it from and no layer a rollback could discard
		this.indexPopulation.recordCreated(entityIndex.getIndexKey());
	}

	/**
	 * Retrieves the entity collection header from the persistence service.
	 *
	 * @return the entity collection header
	 */
	@Nonnull
	public EntityCollectionHeader getEntityCollectionHeader() {
		return this.persistenceService.getEntityCollectionHeader();
	}

	/**
	 * This method writes all changed storage parts into the persistent storage of this {@link EntityCollection} and
	 * then returns updated {@link EntityCollectionHeader}.
	 */
	@Nonnull
	public EntityCollectionHeader flush(long catalogVersion) {
		this.persistenceService.flushTrappedUpdates(
			catalogVersion,
			this.dataStoreBuffer.popTrappedChanges(),
			Functions.noOpIntConsumer()
		);
		return this.catalogPersistenceService.flush(
				catalogVersion,
				this.headerInfoSupplier,
				this.persistenceService.getEntityCollectionHeader(),
				this.dataStoreBuffer
			)
			.map(EntityCollectionPersistenceService::getEntityCollectionHeader)
			.orElseGet(this::getEntityCollectionHeader);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Retrieves a single `SealedEntity` objects based on the given primary key, request parameters,
	 * session context, and reference fetcher. This method ensures that each entity is retrieved
	 * in its current version and applies any necessary limitations and reference fetching specified
	 * by the request.
	 *
	 * @param primaryKey the unique identifier for the entity.
	 * @param evitaRequest the request object containing parameters for the Evita system.
	 * @param session the session context for executing the request.
	 * @param referenceFetcher the object responsible for fetching additional references related to the entity.
	 * @return an Optional containing the SealedEntity if found; otherwise, an empty Optional.
	 */
	@Nonnull
	private Optional<SealedEntity> fetchEntity(
		int primaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		// retrieve current version of entity
		return fetchEntityDecorator(primaryKey, evitaRequest, session)
			.map(it -> limitEntity(it, evitaRequest, session))
			.map(it -> applyReferenceFetcher(evitaRequest, it, referenceFetcher));
	}

	/**
	 * Applies an entity mutation internally by assessing the type of mutation
	 * and performing the appropriate operation.
	 *
	 * @param session the Evita session that may be involved in the transaction; can be null.
	 * @param entityMutation the mutation operation to be applied to an entity; must not be null.
	 * @throws InvalidMutationException if an unsupported mutation type is encountered.
	 */
	private void applyMutationInternal(@Nullable EvitaSessionContract session, @Nonnull EntityMutation entityMutation) {
		if (entityMutation instanceof EntityUpsertMutation upsertMutation) {
			upsertEntityInternal(session, upsertMutation, null, Void.class);
		} else if (entityMutation instanceof ServerEntityRemoveMutation removeMutation) {
			applyMutations(
				session,
				entityMutation,
				removeMutation.shouldRollbackOnError(),
				removeMutation.shouldVerifyConsistency(),
				null,
				removeMutation.getImplicitMutationsBehavior(),
				new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
				Void.class
			);
		} else if (entityMutation instanceof EntityRemoveMutation) {
			applyMutations(
				session,
				entityMutation,
				true,
				true,
				null,
				EnumSet.noneOf(ImplicitMutationBehavior.class),
				new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
				Void.class
			);
		} else {
			throw new InvalidMutationException(
				"Unexpected mutation type: " + entityMutation.getClass().getName(),
				"Unexpected mutation type."
			);
		}
	}

	/**
	 * Deletes passed entity both from indexes and the storage.
	 */
	@Nonnull
	private <T> Optional<T> deleteEntityInternal(
		int primaryKey,
		@Nonnull EvitaSessionContract session,
		@Nullable EvitaRequest returnDeletedEntity,
		@Nonnull Class<T> returnType
	) {
		return applyMutations(
			session,
			new EntityRemoveMutation(getEntityType(), primaryKey),
			true,
			true,
			returnDeletedEntity,
			EnumSet.allOf(ImplicitMutationBehavior.class),
			new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
			returnType
		);
	}

	/**
	 * Changes the scope (either live/archived) of an entity identified by the given primary key and applies
	 * the specified changes.
	 *
	 * @param primaryKey the primary key of the entity whose scope is to be changed
	 * @param scope the new scope to be assigned to the entity
	 * @param returnEntity request to return the archived entity if present, otherwise null
	 * @param returnType the class type of the object to be returned
	 * @return the result of the mutation operation, which can be null if the operation fails or no entity is found
	 */
	@Nonnull
	private <T> Optional<T> changeEntityScopeInternal(
		int primaryKey,
		@Nonnull Scope scope,
		@Nonnull EvitaSessionContract session,
		@Nullable EvitaRequest returnEntity,
		@Nonnull Class<T> returnType
	) {
		return applyMutations(
			session,
			new EntityUpsertMutation(
				getEntityType(),
				primaryKey,
				EntityExistence.MAY_EXIST,
				new SetEntityScopeMutation(scope)
			),
			true,
			true,
			returnEntity,
			EnumSet.allOf(ImplicitMutationBehavior.class),
			new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
			returnType
		);
	}

	/**
	 * Refreshes the given schemas based on the references provided.
	 *
	 * @param originalSchema          the original schema to be refreshed
	 * @param updatedSchema           the updated schema containing new references
	 * @param updatedReferenceSchemas the set of updated reference schemas
	 * @return the updated entity schema
	 * @throws GenericEvitaInternalError if a reference is expected to exist but is not found
	 */
	@Nonnull
	private EntitySchema refreshReflectedSchemas(
		@Nonnull EntitySchema originalSchema,
		@Nonnull EntitySchema updatedSchema,
		@Nonnull Set<String> updatedReferenceSchemas
	) {
		for (String referenceName : updatedReferenceSchemas) {
			final Optional<ReferenceSchemaContract> updatedReference = updatedSchema.getReference(referenceName);
			final Optional<ReferenceSchemaContract> referenceInStakeRef = updatedReference
				.or(() -> originalSchema.getReference(referenceName));
			if (referenceInStakeRef.isEmpty()) {
				// the schema was not present before - we may skip it
				continue;
			}
			final ReferenceSchemaContract referenceInStake = referenceInStakeRef.get();
			if (referenceInStake instanceof ReflectedReferenceSchema reflectedReferenceSchema && updatedReference.isPresent()) {
				final Optional<EntitySchemaContract> referencedEntitySchema = reflectedReferenceSchema.getReferencedEntityType().equals(updatedSchema.getName()) ?
					of(updatedSchema) :
					this.catalog.getCollectionForEntity(reflectedReferenceSchema.getReferencedEntityType()).map(EntityCollectionContract::getSchema);
				final ReferenceSchemaContract originalReference = referencedEntitySchema
					.flatMap(it -> it.getReference(reflectedReferenceSchema.getReflectedReferenceName()))
					.orElse(null);
				if (originalReference != null) {
					updatedSchema = updatedSchema.withReplacedReferenceSchema(
						reflectedReferenceSchema.withReferencedSchema(originalReference)
					);
				}
			} else if (referenceInStake.isReferencedEntityTypeManaged() && updatedReference.isPresent()) {
				// notify the target entity schema about the reference change in our schema
				EntitySchema finalUpdatedSchema = updatedSchema;
				this.catalog.getCollectionForEntity(referenceInStake.getReferencedEntityType())
					.ifPresent(it -> ((EntityCollection) it).notifyAboutExternalReferenceUpdate(finalUpdatedSchema, updatedReference.get()));
			}
		}
		return updatedSchema;
	}

	/**
	 * Notifies about an external reference update. Method will iterate over reference schemas and finds all
	 * {@link ReflectedReferenceSchemaContract} that relate to the updated reference schema and replaces them with
	 * updated instance pointing to the current version of the original reference schema it reflects.
	 *
	 * If any of such reference is found entire entity schema is updated.
	 *
	 * @param updatedReferenceEntitySchema the updated reference entity schema, must not be null
	 * @param updatedReferenceSchema       the updated reference schema, must not be null
	 */
	private void notifyAboutExternalReferenceUpdate(
		@Nonnull EntitySchema updatedReferenceEntitySchema,
		@Nonnull ReferenceSchemaContract updatedReferenceSchema
	) {
		final EntitySchema originalSchema = getInternalSchema();
		final List<ReflectedReferenceSchema> updatedReferenceSchemas = new LinkedList<>();
		for (ReferenceSchemaContract referenceSchema : originalSchema.getReferences().values()) {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema &&
				reflectedReferenceSchema.getReferencedEntityType().equals(updatedReferenceEntitySchema.getName()) &&
				reflectedReferenceSchema.getReflectedReferenceName().equals(updatedReferenceSchema.getName())
			) {
				updatedReferenceSchemas.add(
					reflectedReferenceSchema.withReferencedSchema(updatedReferenceSchema)
				);
			}
		}
		if (!updatedReferenceSchemas.isEmpty()) {
			exchangeSchema(
				originalSchema,
				originalSchema.withReplacedReferenceSchema(
					updatedReferenceSchemas.toArray(new ReflectedReferenceSchema[0])
				)
			);
		}
	}

	/**
	 * Exchanges the schema from the original to the updated schema.
	 * Method is public only because we need to use it in tests.
	 *
	 * @param originalSchema the original schema to be exchanged
	 * @param updatedSchema  the updated schema to replace the original
	 */
	private void exchangeSchema(@Nonnull EntitySchema originalSchema, @Nonnull EntitySchema updatedSchema) {
		final EntitySchemaDecorator originalSchemaBeforeExchange = this.schema.compareAndExchange(
			this.schema.get(),
			new EntitySchemaDecorator(() -> this.catalog.getSchema(), updatedSchema)
		);
		final EntitySchemaContract finalUpdatedSchema = updatedSchema;
		Assert.isTrue(
			Objects.requireNonNull(originalSchemaBeforeExchange).version() == originalSchema.version(),
			() -> new ConcurrentSchemaUpdateException(originalSchema, finalUpdatedSchema)
		);
		this.catalog.entitySchemaUpdated(updatedSchema);
	}

	/**
	 * Limits the server entity based on the specified request requirements. This method applies or extends various
	 * predicates to the server entity to ensure that only the required information is included in the response.
	 * The data present in the internal entity are not modified in any way.
	 *
	 * @param entity The server entity to be limited.
	 * @param evitaRequest The request containing parameters that define the limitation criteria.
	 *
	 * @return A decorated ServerEntity with applied limitations as per the EvitaRequest.
	 */
	@Nonnull
	private ServerEntityDecorator limitEntityInternal(
		@Nonnull ServerEntityDecorator entity,
		@Nonnull EvitaRequest evitaRequest
	) {
		final LocaleSerializablePredicate newLocalePredicate = new LocaleSerializablePredicate(evitaRequest, entity.getLocalePredicate());
		final HierarchySerializablePredicate newHierarchyPredicate = new HierarchySerializablePredicate(evitaRequest, entity.getHierarchyPredicate());
		final AttributeValueSerializablePredicate newAttributePredicate = new AttributeValueSerializablePredicate(evitaRequest, entity.getAttributePredicate());
		final AssociatedDataValueSerializablePredicate newAssociatedDataPredicate = new AssociatedDataValueSerializablePredicate(evitaRequest, entity.getAssociatedDataPredicate());
		final ReferenceContractSerializablePredicate newReferenceContractPredicate = new ReferenceContractSerializablePredicate(evitaRequest, entity.getReferencePredicate());
		final PriceContractSerializablePredicate newPricePredicate = new PriceContractSerializablePredicate(evitaRequest, entity.getPricePredicate());
		return ServerEntityDecorator.decorate(
			entity.getDelegate(),
			// use original schema
			getInternalSchema(),
			// show / hide parent entity
			entity.parentAvailable() && evitaRequest.isRequiresParent() ?
				entity.getParentEntity().orElse(null) : null,
			// show / hide locales the entity is fetched in
			newLocalePredicate,
			// show / hide parent information
			newHierarchyPredicate,
			// show / hide attributes information
			newAttributePredicate,
			// show / hide associated data information
			newAssociatedDataPredicate,
			// show / hide references information
			newReferenceContractPredicate,
			// show / hide price information
			newPricePredicate,
			// propagate original date time
			entity.getAlignedNow(),
			// propagate original I/O fetch count
			entity.getIoFetchCount(),
			// propagate original I/O fetched bytes
			entity.getIoFetchedBytes()
		);
	}

	/**
	 * Enriches a given entity based on the specified request parameters. The method fetches additional data if they
	 * are missing, but are known to exist in the underlying storage. Or it simply widens the predicate scope, if
	 * the data are present, but are hidden by predicates.
	 *
	 * @param sealedEntity the entity to be enriched
	 * @param evitaRequest the request containing parameters for enriching the entity
	 * @return an enriched ServerEntityDecorator instance based on the provided entity and request
	 * @throws EntityAlreadyRemovedException if the entity has been removed
	 */
	@Nonnull
	private ServerEntityDecorator enrichEntityInternal(
		@Nonnull EntityContract sealedEntity,
		@Nonnull EvitaRequest evitaRequest
	) throws EntityAlreadyRemovedException {
		final ServerEntityDecorator partiallyLoadedEntity = (ServerEntityDecorator) sealedEntity;
		// return decorator that hides information not requested by original query
		final LocaleSerializablePredicate newLocalePredicate = partiallyLoadedEntity.createLocalePredicateRicherCopyWith(evitaRequest);
		final HierarchySerializablePredicate newHierarchyPredicate = partiallyLoadedEntity.createHierarchyPredicateRicherCopyWith(evitaRequest);
		final AttributeValueSerializablePredicate newAttributePredicate = partiallyLoadedEntity.createAttributePredicateRicherCopyWith(evitaRequest);
		final AssociatedDataValueSerializablePredicate newAssociatedDataPredicate = partiallyLoadedEntity.createAssociatedDataPredicateRicherCopyWith(evitaRequest);
		final ReferenceContractSerializablePredicate newReferenceContractPredicate = partiallyLoadedEntity.createReferencePredicateRicherCopyWith(evitaRequest);
		final PriceContractSerializablePredicate newPriceContractPredicate = partiallyLoadedEntity.createPricePredicateRicherCopyWith(evitaRequest);
		final EntitySchema internalSchema = getInternalSchema();

		final EntityWithFetchCount entityWithFetchCount = this.persistenceService.enrichEntity(
			this.catalog.getVersion(),
			// use all data from existing entity
			partiallyLoadedEntity,
			newHierarchyPredicate,
			newAttributePredicate,
			newAssociatedDataPredicate,
			newReferenceContractPredicate,
			newPriceContractPredicate,
			this.dataStoreReader,
			new ServerChunkTransformerAccessor(evitaRequest)
		);
		return ServerEntityDecorator.decorate(
			// load all missing data according to current evita request
			entityWithFetchCount.entity(),
			// use original schema
			internalSchema,
			// fetch parents if requested
			null,
			// show / hide locales the entity is fetched in
			newLocalePredicate,
			// show / hide parent information
			newHierarchyPredicate,
			// show / hide attributes information
			newAttributePredicate,
			// show / hide associated data information
			newAssociatedDataPredicate,
			// show / hide references information
			newReferenceContractPredicate,
			// show / hide price information
			newPriceContractPredicate,
			// propagate original date time
			partiallyLoadedEntity.getAlignedNow(),
			// propagate information about I/O fetch count
			entityWithFetchCount.ioFetchCount(),
			// propagate information about I/O fetched bytes
			entityWithFetchCount.ioFetchedBytes()
		);
	}

	/**
	 * Generates new UNIQUE primary key for the entity. Calling this
	 *
	 * @return new unique primary key
	 */
	private int getNextPrimaryKey() {
		// atomic integer takes care of concurrent access and producing unique monotonic sequence of numbers
		return this.pkSequence.incrementAndGet();
	}

	/**
	 * Method fetches the entity by its primary key from the I/O storage (taking advantage of modified parts in the
	 * {@link TransactionalDataStoreMemoryBuffer}).
	 */
	@Nullable
	private EntityWithFetchCount getEntityById(int primaryKey, @Nonnull EvitaRequest evitaRequest) {
		final Optional<GlobalEntityIndex> globalArchiveIndex = this.getGlobalArchiveIndexIfExists();
		final Set<Scope> requestedScopes = evitaRequest.getScopes();
		final boolean canReadWithoutConsultingIndexes = (globalArchiveIndex.isEmpty() && requestedScopes.contains(Scope.LIVE))
			|| requestedScopes.containsAll(Arrays.asList(Scope.values()));
		if (!canReadWithoutConsultingIndexes) {
			if (requestedScopes.contains(Scope.LIVE) && !getGlobalIndex().contains(primaryKey)) {
				return null;
			}
			if (requestedScopes.contains(Scope.ARCHIVED) && globalArchiveIndex.map(ix -> !ix.contains(primaryKey)).orElse(false)) {
				return null;
			}
		}

		return this.persistenceService.readEntity(
			this.catalog.getVersion(),
			primaryKey,
			evitaRequest,
			getInternalSchema(),
			this.dataStoreReader
		);
	}

	/**
	 * Creates a new {@link ReferenceFetcher} that is able to deeply load single entity.
	 */
	@Nonnull
	private ReferenceFetcher createReferenceFetcher(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session
	) {
		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final Map<ReferenceContentKey, RequirementContext> namedReferenceEntityFetch = evitaRequest.getNamedReferenceEntityFetch();
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		return referenceEntityFetch.isEmpty() &&
			namedReferenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
				namedReferenceEntityFetch,
				evitaRequest.getDefaultReferenceRequirement(),
				queryContext.createExecutionContext(),
				new ServerChunkTransformerAccessor(evitaRequest)
			);
	}

	/**
	 * Injects referenced entity bodies into the main entity.
	 *
	 * @param sealedEntity     main entity to be enriched
	 * @param referenceFetcher reference fetcher to be used for accessing referenced entities
	 * @return enriched entity
	 */
	@Nonnull
	private ServerEntityDecorator applyReferenceFetcherInternal(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ServerEntityDecorator sealedEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		// fetch parents if requested
		final EntityClassifierWithParent parentEntity;
		final EntitySchema internalSchema = getInternalSchema();
		if (internalSchema.isWithHierarchy() && sealedEntity.getHierarchyPredicate().isRequiresHierarchy()) {
			if (sealedEntity.getParentEntityWithoutCheckingPredicate().map(SealedEntity.class::isInstance).orElse(false)) {
				parentEntity = sealedEntity.getParentEntityWithoutCheckingPredicate().get();
			} else {
				final OptionalInt theParent = sealedEntity.getDelegate().getParent();
				parentEntity = theParent.isPresent() ?
					ofNullable(referenceFetcher.getParentEntityFetcher())
						.map(it -> it.apply(theParent.getAsInt()))
						.orElse(null) : null;
			}
		} else {
			parentEntity = null;
		}

		return new ServerEntityDecorator(evitaRequest, sealedEntity, parentEntity, referenceFetcher);
	}

	/**
	 * Wraps full entity into an {@link ServerEntityDecorator} that fulfills the requirements passed in input `evitaRequest`.
	 */
	@Nonnull
	private ServerEntityDecorator wrapToDecorator(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntityWithFetchCount fullEntityWithCount,
		@Nullable Boolean contextAvailable
	) {
		return ServerEntityDecorator.decorate(
			fullEntityWithCount.entity(),
			getInternalSchema(),
			null,
			new LocaleSerializablePredicate(evitaRequest),
			new HierarchySerializablePredicate(evitaRequest),
			new AttributeValueSerializablePredicate(evitaRequest),
			new AssociatedDataValueSerializablePredicate(evitaRequest),
			new ReferenceContractSerializablePredicate(evitaRequest),
			new PriceContractSerializablePredicate(evitaRequest, contextAvailable),
			evitaRequest.getAlignedNow(),
			fullEntityWithCount.ioFetchCount(),
			fullEntityWithCount.ioFetchedBytes()
		);
	}

	/**
	 * Creates or updates entity and returns its primary key.
	 */
	@Nonnull
	private <T> Optional<T> upsertEntityInternal(
		@Nullable EvitaSessionContract session,
		@Nonnull EntityMutation entityMutation,
		@Nullable EvitaRequest returnUpdatedEntity,
		@Nonnull Class<T> returnType
	) {
		// verify mutation against schema
		// it was already executed when mutation was created, but there are two reasons to do it again
		// - we don't trust clients - in future it may be some external JS application
		// - schema may have changed between entity was provided to the client and the moment upsert was called
		final SealedCatalogSchema catalogSchema = this.catalog.getSchema();
		final String entityType = entityMutation.getEntityType();
		Assert.isPremiseValid(
			entityType.equals(this.getEntityType()),
			() -> new GenericEvitaInternalError(
				"Entity type `" + entityType + "` is not matching the entity collection type `" + this.getEntityType() + "`!"
			)
		);
		// evitaDB reserves this primary key and never assigns it, which is what lets indexes use it as an
		// unambiguous "no entity" sentinel (see EvitaDataTypes#RESERVED_PRIMARY_KEY). Reject it at the single point
		// where entities enter the engine, so no index can ever come to hold it.
		final Integer mutatedPrimaryKey = entityMutation.getEntityPrimaryKey();
		Assert.isTrue(
			mutatedPrimaryKey == null || mutatedPrimaryKey != EvitaDataTypes.RESERVED_PRIMARY_KEY,
			() -> new InvalidMutationException(
				"Primary key `" + EvitaDataTypes.RESERVED_PRIMARY_KEY + "` is reserved by evitaDB and cannot be " +
					"assigned to an entity of type `" + entityType + "` - use a positive primary key, or let " +
					"evitaDB assign one automatically."
			)
		);
		entityMutation.verifyOrEvolveSchema(catalogSchema, getSchema(), this.emptyOnStart && isEmpty())
			.ifPresent(
				it -> {
					Assert.isPremiseValid(
						session != null,
						"Implicit schema evolution cannot happen during transactional replay without user session. " +
							"In this phase the implicit schema is converted to explicit schema change, " +
							"that is ought to be written in the WAL before the operations requiring such schema change."
					);
					this.catalog.updateSchema(
						session.getEvita(),
						session.getId(),
						new ModifyEntitySchemaMutation(
							this.getEntityType(),
							it
						)
					);
				}
			);

		// check the existence of the primary key and report error when unexpectedly (not) provided
		final SealedEntitySchema currentSchema = getSchema();

		final EntityMutation entityMutationToUpsert;
		if (entityMutation instanceof ServerEntityUpsertMutation veum) {
			entityMutationToUpsert = entityMutation;
			return applyMutations(
				session,
				entityMutationToUpsert,
				veum.shouldRollbackOnError(),
				veum.shouldVerifyConsistency(),
				returnUpdatedEntity,
				veum.getImplicitMutationsBehavior(),
				new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
				returnType
			);
		} else {
			entityMutationToUpsert = verifyPrimaryKeyAssignment(entityMutation, currentSchema);
			return applyMutations(
				session,
				entityMutationToUpsert,
				true,
				true,
				returnUpdatedEntity,
				EnumSet.allOf(ImplicitMutationBehavior.class),
				new LocalMutationExecutorCollector(this.catalog, this.persistenceService, this.dataStoreReader),
				returnType
			);
		}
	}

	/**
	 * Method checks whether entity mutation primary key is present or not, and whether it should be present or not
	 * according to the schema settings.
	 *
	 * @return primary key assigned in the mutation
	 * @throws InvalidMutationException when the expectations are not met
	 */
	@Nonnull
	private EntityMutation verifyPrimaryKeyAssignment(
		@Nonnull EntityMutation entityMutation,
		@Nonnull SealedEntitySchema currentSchema
	) throws InvalidMutationException {
		if (currentSchema.isWithGeneratedPrimaryKey()) {
			if (entityMutation instanceof EntityUpsertMutation && entityMutation.getEntityPrimaryKey() == null) {
				entityMutation = new EntityUpsertMutation(
					entityMutation.getEntityType(),
					getNextPrimaryKey(),
					entityMutation.expects(),
					entityMutation.getLocalMutations()
				);
			} else if (entityMutation.expects() == EntityExistence.MUST_NOT_EXIST) {
				throw new InvalidMutationException(
					"Entity of type `" + currentSchema.getName() +
						"` is expected to have primary key automatically generated by Evita!"
				);
			} else if (entityMutation.expects() == EntityExistence.MAY_EXIST) {
				Assert.isTrue(
					entityMutation.getEntityPrimaryKey() != null &&
						getGlobalIndex().isPrimaryKeyKnown(entityMutation.getEntityPrimaryKey()),
					() -> new InvalidMutationException(
						"Entity of type `" + currentSchema.getName() +
							"` is expected to have primary key automatically generated by Evita!"
					)
				);
			}
		} else {
			Assert.isTrue(
				entityMutation.getEntityPrimaryKey() != null,
				() -> new InvalidMutationException(
					"Entity of type " + currentSchema.getName() +
						" is expected to have primary key provided by external systems!"
				)
			);
		}
		return entityMutation;
	}

	/**
	 * Method applies all `localMutations` on entity with passed `entityPrimaryKey`.
	 *
	 * @param entityMutation            entity mutation to apply
	 * @param atomicRollback            whether a failed mutation should be atomically reverted via the
	 *                                  diff-layer savepoint; only effective when a transaction
	 *                                  is active — pass {@code false} for WAL replay, where no rollback is needed
	 * @param checkConsistency          whether to check the consistency of the entity after the mutation
	 *                                  (if set to false, the consistency will not be checked)
	 * @param generateImplicitMutations set of implicit mutations to generate
	 *                                  (if set to empty set, no implicit mutations will be generated)
	 * @return entity with fetch count
	 */
	@Nonnull
	<T> Optional<T> applyMutations(
		@Nullable EvitaSessionContract session,
		@Nonnull EntityMutation entityMutation,
		boolean atomicRollback,
		boolean checkConsistency,
		@Nullable EvitaRequest returnUpdatedEntity,
		@Nonnull EnumSet<ImplicitMutationBehavior> generateImplicitMutations,
		@Nonnull LocalMutationExecutorCollector localMutationExecutorCollector,
		@Nonnull Class<T> requestedType
	) {
		// prepare collectors
		final int entityPrimaryKey = Objects.requireNonNull(entityMutation.getEntityPrimaryKey());
		final ContainerizedLocalMutationExecutor changeCollector = new ContainerizedLocalMutationExecutor(
			this.dataStoreBuffer,
			this.dataStoreReader,
			this.catalog.getVersion(),
			entityPrimaryKey,
			entityMutation.expects(),
			this.catalog::getInternalSchema,
			this::getInternalSchema,
			theEntityType -> this.catalog.getCollectionForEntityInternal(theEntityType)
				.map(EntityCollection::getDataStoreReader)
				.orElse(null),
			this::nextInternalPriceId,
			entityMutation instanceof EntityRemoveMutation
		);
		final String entityType = getInternalSchema().getName();
		final EntityIndexLocalMutationExecutor entityIndexUpdater = new EntityIndexLocalMutationExecutor(
			changeCollector,
			entityPrimaryKey,
			this.entityIndexCreator,
			this.catalog.getCatalogIndexMaintainer(),
			this::getInternalSchema,
			this::nextInternalPriceId,
			() -> localMutationExecutorCollector.getFullEntityContents(changeCollector).entity(),
			() -> this.catalog.getExpressionTriggerRegistry(),
			(referenceName, scope) -> this.catalog.getExpressionTriggerRegistry()
				.getLocalTrigger(entityType, referenceName, scope),
			otherEntityType -> this.catalog.getCollectionForEntityInternal(otherEntityType)
				.map(EntityCollection::getInternalSchema)
				.orElseThrow(() -> new IllegalStateException(
					"No entity collection found for entity type `" + otherEntityType + "` " +
						"while resolving schema for cross-entity expression evaluation."
				)),
			this.catalog
		);

		return localMutationExecutorCollector.execute(
			session,
			getInternalSchema(),
			entityMutation,
			checkConsistency,
			atomicRollback,
			generateImplicitMutations,
			changeCollector,
			entityIndexUpdater,
			returnUpdatedEntity,
			requestedType
		);

	}

	/**
	 * Returns new, unique {@link PriceInternalIdContainer#getInternalPriceId()} from the sequence.
	 * See {@link PriceInternalIdContainer} to see the reasons behind it.
	 */
	private int nextInternalPriceId() {
		return this.pricePkSequence.incrementAndGet();
	}

	/**
	 * Method will check whether the entity contains only the content required by `fetchRequirements` and if it contains
	 * more data than requested, new instance of {@link BinaryEntity} is created referencing only the requested set
	 * of data containers.
	 */
	@Nonnull
	private BinaryEntity limitEntity(@Nonnull BinaryEntity entity, @Nonnull EntityFetch fetchRequirements) {
		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		return entity;
	}

	/**
	 * Casts an {@link EntityIndex} to its {@link TransactionalStateProducer} facet — every concrete entity index is one
	 * (via {@code VoidTransactionMemoryProducer}) — and returns its committed copy. This is the type reconciliation the
	 * merging {@code TransactionalMap} performs internally; the pruned merge needs it because it resolves individual
	 * indexes rather than merging the whole map.
	 *
	 * @param transactionalLayer the maintainer resolving committed state
	 * @param index              the (concrete) entity index to commit
	 * @return the committed copy of the index
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static EntityIndex mergeCommittedIndex(
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nonnull EntityIndex index
	) {
		return transactionalLayer.getStateCopyWithCommittedChanges((TransactionalStateProducer<? extends EntityIndex>) index);
	}

	/**
	 * Commit-merge prune for {@link #indexes}. Instead of rebuilding every entity index by merging its transactional
	 * layer — a walk that is ~99% clean-discovery, since a typical transaction dirties a handful of indexes out of the
	 * whole forest — this rebuilds only the indexes that were genuinely acquired for modification this transaction (the
	 * `dirtyIndexKeys` snapshot, ground truth captured by the just-completed flush) and carries every unchanged index
	 * across the catalog version by reference.
	 *
	 * An unchanged index is now carried **without being looked at**: the next version's map is derived from the previous
	 * immutable one by path-copying just the touched keys, so a clean index is neither merged nor visited and the trie
	 * nodes holding it are shared outright. That is the difference from the earlier form of this prune, which still had
	 * to walk all N entries to discover that N−Δ of them needed nothing done — the discovery itself was the cost.
	 *
	 * A clean index owns no transactional layer anywhere in its sub-tree (it was never acquired for modification), so
	 * skipping its merge cannot orphan a diff layer — and if that invariant were ever violated,
	 * {@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()} fails loudly at commit rather than silently
	 * dropping changes.
	 *
	 * Price wiring used to be the one subtlety here and no longer is: a reduced index's price chain kept a pointer to its
	 * scope's GLOBAL entity index, and because the GLOBAL is rebuilt whenever it changes (nearly every transaction),
	 * every clean reduced index of that scope had to be re-shelled and re-wired purely to refresh that pointer. The
	 * pointer is gone — the GLOBAL's price index is handed in per operation by a caller that is already pinned to
	 * a catalog version — so a clean reduced index is now shared **wholesale**, exactly like a clean referenced-type
	 * index (which holds a void price index and always was).
	 *
	 * GLOBAL indexes are still materialized first, so the merge hands back exactly one committed instance per GLOBAL key.
	 *
	 * A transaction that adds or removes an index gives the index map a diff layer of its own. That key delta is applied
	 * by {@link MapChanges} the ordinary way — including the layer bookkeeping of removed and created-then-removed
	 * values — while the values are still partitioned here, because the delta is tiny compared to the map (in a
	 * production workload a handful of keys out of hundreds of thousands) and merging the whole forest to record it
	 * would waste the entire prune.
	 *
	 * One consistency check is deliberately given up in exchange: the merge used to see every surviving key, so it could
	 * assert that a scope whose reduced index survives still has a GLOBAL. A walk that visits only touched keys cannot
	 * ask that question without re-introducing the very O(N) scan it removes. The invariant itself is unchanged — a
	 * scope's GLOBAL holds every entity of that scope, so it can only be dropped once its reduced indexes are empty and
	 * dropped in the same commit — it is simply no longer restated here.
	 *
	 * The by-primary-key map is derived here as well, from the very same delta rather than rebuilt from the merged
	 * result, which is what keeps the two views of the same index forest in step: one delta, applied twice.
	 *
	 * @param transactionalLayer the maintainer resolving committed state for the indexes that are rebuilt
	 * @param dirtyIndexKeys     keys of the indexes genuinely mutated this transaction
	 * @return the committed index maps, with unchanged indexes carried by reference
	 */
	@Nonnull
	private IndexTuple pruneMergeIndexes(
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nonnull Set<IndexKey> dirtyIndexKeys
	) {
		final MapChanges<EntityIndexKey, EntityIndex> indexChanges =
			transactionalLayer.getTransactionalMemoryLayerIfExists(this.indexes);
		// an index the key delta added or replaced is a fresh instance that must be merged, not carried - fold those
		// few keys into the dirty set so the per-index decision below stays a single lookup. The set is retyped to
		// EntityIndexKey because it now DRIVES the merge walk (the keys are looked up in the map), where it used only to
		// be consulted by it - a key of any other type would silently address nothing
		final Set<EntityIndexKey> rebuiltKeys = CollectionUtils.createHashSet(
			dirtyIndexKeys.size() + (indexChanges == null ? 0 : indexChanges.getModifiedKeys().size())
		);
		for (final IndexKey dirtyIndexKey : dirtyIndexKeys) {
			Assert.isPremiseValid(
				dirtyIndexKey instanceof EntityIndexKey,
				() -> "Dirty index key `" + dirtyIndexKey + "` of an entity collection is not an EntityIndexKey!"
			);
			rebuiltKeys.add((EntityIndexKey) dirtyIndexKey);
		}
		if (indexChanges != null) {
			rebuiltKeys.addAll(indexChanges.getModifiedKeys().keySet());
		}

		// Phase 1 — materialize each scope's GLOBAL index first, by direct key lookup rather than by scanning the map, so
		// the merge hands back exactly one committed instance per GLOBAL key. Two lookups per scope, whether or not the
		// GLOBAL changed; a clean one resolves to the very instance already in the map, so carrying it costs nothing.
		final Scope[] scopes = Scope.values();
		final GlobalEntityIndex[] globalsByScope = new GlobalEntityIndex[scopes.length];
		for (final Scope scope : scopes) {
			final EntityIndexKey globalKey = new EntityIndexKey(EntityIndexType.GLOBAL, scope);
			// the transaction-visible view: a GLOBAL created this transaction is already here, a removed one is gone
			final EntityIndex globalIndex = this.indexes.get(globalKey);
			if (globalIndex != null) {
				globalsByScope[scope.ordinal()] = rebuiltKeys.contains(globalKey) ?
					(GlobalEntityIndex) mergeCommittedIndex(transactionalLayer, globalIndex) :
					(GlobalEntityIndex) globalIndex;
			}
		}

		// Phase 2 — only the touched indexes, resolved through the merger below; the map applies its own key delta around
		// them and derives the next version by path-copying just those keys onto the previous immutable snapshot
		final ChampMap<EntityIndexKey, EntityIndex> mergedIndexes =
			this.indexes.createCopyWithMergedTransactionalMemory(
				indexChanges, transactionalLayer, rebuiltKeys,
				new PrunedIndexMerger(transactionalLayer, rebuiltKeys, globalsByScope)
			);

		// Phase 3 — apply the SAME delta to the by-primary-key view. Both maps hold the same index instances, so the
		// merged forest above is already the answer; only the two maps' keys differ. Rebuilding this one from the merged
		// result instead would be the last remaining O(N) pass of the commit - and it would make the two views agree only
		// by coincidence, whereas deriving both from one delta makes them agree by construction.
		// `sealed()` deliberately reads the base snapshot even though a diff layer may exist: the delta that layer holds
		// is exactly the delta applied below, from the key map's own record of it. The seal is O(1) whenever the layer
		// exists (the layer is created over the sealed state) and O(N) exactly once on the first commit after a disk
		// load, when the map is still the mutable buffer the load filled.
		ChampMap<Integer, EntityIndex> mergedIndexesByPk = this.indexesByPrimaryKey.sealed();
		final ChampMap<EntityIndexKey, EntityIndex> previousIndexes = this.indexes.sealed();
		// Pass 1 — retire every primary key the previous version held under a key this transaction touched. Both halves
		// are needed because the key delta expresses removals of KEYS, while this map is keyed on something the delta
		// cannot express at all: the primary key of the instance behind the key.
		if (indexChanges != null) {
			for (final EntityIndexKey removedKey : indexChanges.getRemovedKeys()) {
				final EntityIndex removedIndex = previousIndexes.get(removedKey);
				if (removedIndex != null) {
					mergedIndexesByPk = mergedIndexesByPk.removed(removedIndex.getPrimaryKey());
				}
			}
		}
		for (final EntityIndexKey rebuiltKey : rebuiltKeys) {
			final EntityIndex previousIndex = previousIndexes.get(rebuiltKey);
			if (previousIndex != null) {
				final EntityIndex committedIndex = mergedIndexes.get(rebuiltKey);
				// an index dropped and re-created within ONE transaction keeps its index key but is assigned a FRESH
				// storage primary key, so the key delta reports it as merely modified while the by-PK view must both
				// retire the old key and publish the new one. Retiring by the key's PREVIOUS primary key covers that
				// case and the plain removal alike, and is a no-op whenever the instance kept its primary key
				if (committedIndex == null || committedIndex.getPrimaryKey() != previousIndex.getPrimaryKey()) {
					mergedIndexesByPk = mergedIndexesByPk.removed(previousIndex.getPrimaryKey());
				}
			}
		}
		// Pass 2 — only once every retirement is applied may the survivors be published, so that a primary key retired
		// under one key and legitimately taken by another in the same transaction is not dropped after being published
		for (final EntityIndexKey rebuiltKey : rebuiltKeys) {
			// a rebuilt key absent from the merged result was removed by this transaction (or created and removed again
			// within it) - the retirement pass above has already dropped its primary key, or it never had one in the map
			final EntityIndex committedIndex = mergedIndexes.get(rebuiltKey);
			if (committedIndex != null) {
				mergedIndexesByPk = mergedIndexesByPk.updated(committedIndex.getPrimaryKey(), committedIndex);
			}
		}

		// the pruned merge does not go through TransactionalLayerMaintainer#getStateCopyWithCommittedChanges, so both
		// maps' OWN layers have to be disposed of here. Neither may be `removeLayer(...)`, which descends into every
		// value and would restore the very walk this method exists to avoid - and for the by-PK map that walk would be
		// all-miss on top, since it holds the same instances the merge above has already swept. A forgotten disposal
		// surfaces loudly in TransactionalLayerMaintainer#verifyLayerWasFullySwept.
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.indexes);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.indexesByPrimaryKey);
		return new IndexTuple(
			mergedIndexes, mergedIndexesByPk,
			mergePopulation(indexChanges, rebuiltKeys, previousIndexes, mergedIndexes)
		);
	}

	/**
	 * Derives the next catalog version's index population from this one plus the transaction's key delta.
	 *
	 * **This is the only place the transactional path moves those counts, and it is what makes them
	 * rollback-correct.** A rolled-back transaction never reaches this method - its diff layer is discarded with the
	 * counts untouched - whereas a counter bumped at {@link EntityIndexMaintainer#getOrCreateIndex(EntityIndexKey)}
	 * would already have moved and would stay wrong for the life of the process.
	 *
	 * The delta is read off the same two sources the map merge itself uses, so the counts cannot describe a different
	 * key set than the map they accompany: a removed key that the previous version actually held is a drop, and a
	 * touched key the previous version did not hold is a create. A key that is both created and dropped within one
	 * transaction appears in neither, which is correct - the collection never published it.
	 *
	 * Membership is decided against the two *maps* - the one this version published and the one the next version
	 * will - rather than against the diff layer, which by this point has already been disposed of a few lines above.
	 *
	 * @param indexChanges    the transaction's diff layer over the index map, null when it changed no keys
	 * @param rebuiltKeys     keys this transaction touched
	 * @param previousIndexes the index map as it stood before this transaction
	 * @param mergedIndexes   the index map the next catalog version will hold
	 * @return the population the next catalog version starts from
	 */
	@Nonnull
	private IndexPopulation mergePopulation(
		@Nullable MapChanges<EntityIndexKey, EntityIndex> indexChanges,
		@Nonnull Set<EntityIndexKey> rebuiltKeys,
		@Nonnull ChampMap<EntityIndexKey, EntityIndex> previousIndexes,
		@Nonnull ChampMap<EntityIndexKey, EntityIndex> mergedIndexes
	) {
		final IndexPopulation population = this.indexPopulation.copy();
		if (indexChanges != null) {
			for (final EntityIndexKey removedKey : indexChanges.getRemovedKeys()) {
				if (previousIndexes.containsKey(removedKey) && !mergedIndexes.containsKey(removedKey)) {
					population.recordRemoved(removedKey);
				}
			}
		}
		for (final EntityIndexKey rebuiltKey : rebuiltKeys) {
			// present now and absent before is a create; a key touched but held by both versions is a mutation of an
			// index that already existed, and one held by neither was created and dropped inside this transaction -
			// neither moves a count
			if (mergedIndexes.containsKey(rebuiltKey) && !previousIndexes.containsKey(rebuiltKey)) {
				population.recordCreated(rebuiltKey);
			}
		}
		return population;
	}

	/**
	 * Rebuilds an index the commit-time merge of {@link EntityCollection#indexes} decided to visit. Only touched keys are
	 * ever passed here — the merge walks the transaction's delta, so an index this transaction did not mutate never
	 * reaches this merger at all and is carried across the version boundary as a shared trie node. See
	 * {@link EntityCollection#pruneMergeIndexes(TransactionalLayerMaintainer, Set)} for the reasoning behind that
	 * partition.
	 *
	 * @param transactionalLayer    the maintainer resolving committed state for the indexes that are rebuilt
	 * @param rebuiltKeys           keys of the indexes that must be rebuilt - those genuinely mutated this transaction
	 *                              plus those the index map's own key delta added or replaced; this merger sees no other
	 * @param globalsByScope        the committed GLOBAL entity index of each scope, indexed by {@link Scope#ordinal()},
	 *                              resolved before the walk so no GLOBAL is ever merged twice
	 */
	private record PrunedIndexMerger(
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nonnull Set<EntityIndexKey> rebuiltKeys,
		@Nonnull GlobalEntityIndex[] globalsByScope
	) implements ValueMerger<EntityIndexKey, EntityIndex> {

		@Nonnull
		@Override
		public EntityIndex mergeSurviving(@Nonnull EntityIndexKey key, @Nullable EntityIndex index) {
			if (key.type() == EntityIndexType.GLOBAL) {
				// resolved (and possibly merged) upfront - hand back the very same instance every other resolution uses
				final Scope scope = key.scope();
				final GlobalEntityIndex newGlobal = this.globalsByScope[scope.ordinal()];
				Assert.isPremiseValid(
					newGlobal != null,
					() -> "GLOBAL entity index of scope `" + scope + "` survives the commit but was not resolved!"
				);
				return newGlobal;
			}
			final EntityIndex theIndex = index;
			Assert.isPremiseValid(
				theIndex != null, () -> "Entity index `" + key + "` is unexpectedly NULL!"
			);
			// the merge walks the delta, and every key of that delta is in `rebuiltKeys` by construction. A key arriving
			// here that is NOT means the walk widened behind this merger's back - which would hand a clean index to
			// getStateCopyWithCommittedChanges and discard a layer it never owned, so it must fail rather than proceed
			Assert.isPremiseValid(
				this.rebuiltKeys.contains(key),
				() -> "Entity index `" + key + "` reached the commit merge without being marked as rebuilt!"
			);
			return mergeCommittedIndex(this.transactionalLayer, theIndex);
		}

		@Override
		public void releaseRemoved(@Nonnull EntityIndexKey key, @Nonnull EntityIndex index) {
			// nothing to do - EntityIndexAccessor#removeIndex already released the whole sub-tree eagerly the moment
			// the index left the map, and every entity index implements removeLayer as exactly that same call, so the
			// ordinary commit-time release would only repeat an identical, by then all-miss descent. Should an index
			// ever leave the map without that eager release, its orphaned layer is reported by
			// TransactionalLayerMaintainer#verifyLayerWasFullySwept instead of being silently dropped.
		}

	}

	/**
	 * Creates the index maps for a new catalog attachment from a **clean** collection, carrying every index — GLOBAL,
	 * referenced-type and reduced alike — across the version boundary **by reference** inside a fresh map wrapper.
	 *
	 * Reduced indexes hold nothing that a version bump could invalidate: their price ref chain keeps no pointer to the
	 * super price indexes backing it, and is handed the GLOBAL's price index per operation by a caller that is already
	 * pinned to a catalog version. So a reduced index needs neither re-shelling nor re-wiring here - it is simply
	 * forwarded, like every other index.
	 *
	 * Carrying transactional sub-structures by reference is only sound when this collection is clean. What actually
	 * guarantees that is the routing: a dirty collection is rebuilt through the merge path and never reaches this
	 * method. The premise assertion below is a partial backstop on top of that routing, not a replacement for it — a
	 * layer on the index maps records an uncommitted change to the index *set* (an index added or removed), because a
	 * transactional map tracks its key-to-value mapping rather than mutation happening inside a mapped value. An index
	 * mutated internally therefore leaves these maps untouched and is caught by the routing, not by this check.
	 *
	 * @return the index maps whose values are the current indexes shared by reference
	 */
	@Nonnull
	private IndexTuple createIndexCopiesForNewCatalogAttachment() {
		Assert.isPremiseValid(
			Transaction.getTransactionalMemoryLayerIfExists(this.indexes) == null &&
				Transaction.getTransactionalMemoryLayerIfExists(this.indexesByPrimaryKey) == null,
			"Indexes may only be carried to a new catalog version from a clean collection, but an uncommitted change to the index set was found!"
		);
		// BOTH maps are forwarded WHOLESALE - every value is carried by reference anyway, so the previous immutable
		// snapshots already ARE the next version's maps and the target constructor adopts them in O(1). Rebuilding either
		// entry by entry would cost a full N-entry map here AND leave the next version holding a mutable buffer, which the
		// first transactional touch would then have to seal into a fresh trie: three O(N) passes for a version bump that
		// changed nothing
		// the population is carried by value: the assertion above proves no uncommitted change to the index set
		// exists, so the counts this version holds are exactly the counts the next version starts from
		return new IndexTuple(this.indexes.sealed(), this.indexesByPrimaryKey.sealed(), this.indexPopulation.copy());
	}

	/**
	 * Resolves this collection's {@link GlobalEntityIndex} of the given scope, which owns the super price indexes that
	 * back the scope's reduced indexes. The GLOBAL index is always present by the time a reduced index references it
	 * (GLOBAL indexes are registered first on load, and a reduced index cannot exist before its collection's GLOBAL) —
	 * the assertion makes that ordering assumption loud rather than silently resolving `null`.
	 *
	 * @param scope the scope whose GLOBAL entity index should be resolved
	 * @return the GLOBAL entity index of the given scope
	 */
	@Nonnull
	private GlobalEntityIndex resolveGlobalIndex(@Nonnull Scope scope) {
		final EntityIndex globalIndex = this.indexes.get(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		Assert.isPremiseValid(
			globalIndex instanceof GlobalEntityIndex,
			() -> "Global entity index of scope `" + scope + "` must exist to wire super price indexes!"
		);
		return (GlobalEntityIndex) globalIndex;
	}

	/**
	 * Method verifies that all referenced entities in updated schema are actually present in catalog.
	 */
	private void assertAllReferencedEntitiesExist(@Nonnull EntitySchema newSchema) {
		Stream.concat(
				newSchema.getReferences().values().stream().filter(ReferenceSchemaContract::isReferencedEntityTypeManaged).map(ReferenceSchemaContract::getReferencedEntityType),
				newSchema.getReferences().values().stream().filter(ReferenceSchemaContract::isReferencedGroupTypeManaged).map(ReferenceSchemaContract::getReferencedGroupType)
			)
			.distinct()
			.forEach(it -> {
				Assert.isTrue(
					this.catalog.getCollectionForEntity(it).isPresent(),
					() -> new InvalidMutationException(
						"Entity schema `" + newSchema.getName() + "` references entity `" + it + "`," +
							" but such entity is not known in catalog `" + this.catalog.getName() + "`."
					)
				);
			});
	}

	/**
	 * Method verifies that no 0..1:N reference has sortable and non-nullable attributes.
	 */
	private void assertReferences(@Nonnull EntitySchema newSchema) {
		for (ReferenceSchemaContract referenceSchema : newSchema.getReferences().values()) {
			final Cardinality cardinality = referenceSchema.getCardinality();
			if (cardinality.getMax() > 1) {
				final String[] invalidAttributes = referenceSchema.getAttributes()
					.values()
					.stream()
					.filter(it -> it.isSortableInAnyScope() && !it.isNullable())
					.map(NamedSchemaContract::getName)
					.toArray(String[]::new);
				if (invalidAttributes.length > 0) {
					throw new InvalidSchemaMutationException(
						"The attribute(s) " + Arrays.stream(invalidAttributes).map(it -> "`" + it + "`").collect(Collectors.joining(", ")) +
							" in entity `" + newSchema.getName() +
							"` schema for reference with name `" + referenceSchema.getName() + "` cannot be both sortable and non-nullable if " +
							"reference cardinality is set to " + cardinality + "! The sorting wouldn't make sense."
					);
				}
			}
		}
	}

	/**
	 * A bridge implementation of the DataStoreReader interface that delegates its operations to another DataStoreReader
	 * while providing additional context by setting the schema through the EntitySchemaContext. This instance should
	 * be used primarily for fetching data from the underlying storage.
	 */
	@RequiredArgsConstructor
	private static class DataStoreReaderBridge implements DataStoreReader {
		private final DataStoreReader dataStoreReader;
		private final Function<EntityIndexKey, EntityIndex> indexAccessor;
		private final IntFunction<EntityIndex> indexByPrimaryKeyAccessor;
		private final Supplier<EntitySchema> schemaSupplier;

		@Override
		public int countStorageParts(long catalogVersion, @Nonnull Class<? extends StoragePart> containerType) {
			return this.dataStoreReader.countStorageParts(catalogVersion, containerType);
		}

		@Nullable
		@Override
		public <T extends StoragePart> T fetch(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.schemaSupplier.get(),
				() -> this.dataStoreReader.fetch(catalogVersion, primaryKey, containerType)
			);
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] fetchBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.schemaSupplier.get(),
				() -> this.dataStoreReader.fetchBinary(catalogVersion, primaryKey, containerType)
			);
		}

		@Nullable
		@Override
		public <T extends StoragePart, U extends Comparable<U>> T fetch(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.schemaSupplier.get(),
				() -> this.dataStoreReader.fetch(catalogVersion, originalKey, containerType, compressedKeyComputer)
			);
		}

		@Nullable
		@Override
		public <T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.schemaSupplier.get(),
				() -> this.dataStoreReader.fetchBinary(catalogVersion, originalKey, containerType, compressedKeyComputer)
			);
		}

		@Override
		public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
			return this.dataStoreReader.getIndexIfExists(
				entityIndexKey,
				ik -> {
					// we need first to fall-back on index search in this collection index
					if (ik instanceof EntityIndexKey eik) {
						//noinspection unchecked
						final I index = (I) this.indexAccessor.apply(eik);
						// and apply accessor when missing only if no index in collection is found
						return index == null ? accessorWhenMissing.apply(ik) : index;
					} else {
						throw new GenericEvitaInternalError(
							"EntityIndexKey must be used as a key for EntityIndex, but got " + ik.getClass().getName() + "!"
						);
					}
				}
			);
		}

		@Nullable
		@Override
		public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(
			int entityIndexPrimaryKey,
			@Nonnull IntFunction<I> accessorWhenMissing
		) {
			return this.dataStoreReader.getIndexIfExists(
				entityIndexPrimaryKey,
				pk -> {
					// we need first to fall-back on index search in this collection index
					//noinspection unchecked
					final I index = (I) this.indexByPrimaryKeyAccessor.apply(pk);
					// and apply accessor when missing only if no index in collection is found
					return index == null ? accessorWhenMissing.apply(pk) : index;
				}
			);
		}
	}

	/**
	 * This implementation manipulates the set of {@link EntityIndex} instances in the entity collection and
	 * provides a narrow {@link IndexMutationTarget} view for {@link IndexMutationExecutor} implementations.
	 *
	 * Implements both {@link IndexMaintainer} (used by {@link EntityIndexLocalMutationExecutor} for index
	 * creation/removal during entity mutation processing) and {@link IndexMutationTarget} (used by
	 * {@link IndexMutationExecutor} implementations for cross-entity trigger dispatch). Both interfaces share
	 * a common {@link IndexProvider} super-type for index lookup methods. This dual role isolates callers from
	 * the full {@link EntityCollection} API surface — passing the entire collection would be one cast away from
	 * accessing internal methods that should not be visible to mutation executors.
	 */
	private class EntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex>, IndexMutationTarget {

		/**
		 * Active session for query evaluation during cross-entity trigger dispatch. Set temporarily by
		 * `applyIndexMutations()` for the duration of the dispatch and cleared in a `finally` block.
		 * May be null during WAL replay when no session context is available.
		 */
		@Nullable
		private EvitaSessionContract session;

		/**
		 * Sets the active session for query evaluation. Called by `applyIndexMutations()` before dispatch
		 * and cleared after dispatch completes.
		 *
		 * @param session the active session, or null to clear
		 */
		void setSession(@Nullable EvitaSessionContract session) {
			this.session = session;
		}

		/**
		 * Returns entity index by its key. If such index doesn't exist, it is automatically created.
		 */
		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			return EntityCollection.this.dataStoreBuffer.getOrCreateIndexForModification(
				entityIndexKey,
				eik ->
					// if storage container buffer doesn't have index in "dirty" memory - retrieve index from collection
					EntityCollection.this.indexes.computeIfAbsent(
						eik,
						eikAgain -> {
							final EntityIndex entityIndex;
							// if index doesn't exist even there create new one
							if (eikAgain.type() == EntityIndexType.GLOBAL) {
								entityIndex = new GlobalEntityIndex(
									EntityCollection.this.indexPkSequence.incrementAndGet(),
									EntityCollection.this.getEntityType(),
									eikAgain
								);
							} else if (
								eikAgain.type() == EntityIndexType.REFERENCED_ENTITY_TYPE ||
									eikAgain.type() == EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
							) {
								assertReferenceIndexPrerequisites(
									((String) Objects.requireNonNull(eikAgain.discriminator()))
								);
								entityIndex = new ReferencedTypeEntityIndex(
									EntityCollection.this.indexPkSequence.incrementAndGet(), EntityCollection.this.getEntityType(), eikAgain
								);
							} else if (eikAgain.type() == EntityIndexType.REFERENCED_ENTITY) {
								assertReferenceIndexPrerequisites(
									((RepresentativeReferenceKey) Objects.requireNonNull(eikAgain.discriminator()))
										.referenceName()
								);
								entityIndex = new ReducedEntityIndex(
									EntityCollection.this.indexPkSequence.incrementAndGet(),
									EntityCollection.this.getEntityType(),
									eikAgain
								);
							} else if (eikAgain.type() == EntityIndexType.REFERENCED_GROUP_ENTITY) {
								assertReferenceIndexPrerequisites(
									((RepresentativeReferenceKey) Objects.requireNonNull(eikAgain.discriminator()))
										.referenceName()
								);
								entityIndex = new ReducedGroupEntityIndex(
									EntityCollection.this.indexPkSequence.incrementAndGet(),
									EntityCollection.this.getEntityType(),
									eikAgain
								);
							} else {
								throw new GenericEvitaInternalError("Unsupported entity index type: " + eikAgain.type());
							}

							// register index also in the map by primary key for fast access
							EntityCollection.this.indexesByPrimaryKey.put(entityIndex.getPrimaryKey(), entityIndex);

							// only the non-transactional (warm-up / bulk-load) path moves the count here. In a
							// transaction the map write lands in a diff layer a rollback would discard, so the count
							// is derived at commit instead - see `mergePopulation`. Counting in both places would
							// double-count every committed index.
							//
							// The test is "is a transaction bound to this thread", NOT "does the index map already
							// have a diff layer": `computeIfAbsent` is the inherited Map default, so this lambda runs
							// BEFORE the `put` that creates the layer. Asking the map would answer "no layer" for the
							// first index created in each transaction and leak exactly one count per rolled-back
							// transaction - which is what it did until this was corrected
							if (!Transaction.isTransactionAvailable()) {
								EntityCollection.this.indexPopulation.recordCreated(eikAgain);
							}

							return entityIndex;
						}
					)
			);
		}

		/**
		 * Returns entity index by its storage primary key and registers it in the "dirty" memory so that its
		 * modified storage parts are captured on flush. Returns `null` when no index with the given PK exists.
		 * Used by cross-entity mutation executors that modify indexes fetched by storage PK.
		 */
		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			return EntityCollection.this.dataStoreBuffer.getOrCreateIndexForModification(
				indexPrimaryKey,
				EntityCollection.this.indexesByPrimaryKey::get
			);
		}

		/**
		 * Returns existing index for passed `entityIndexKey` or returns null.
		 */
		@Nullable
		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			return EntityCollection.this.getIndexByKeyIfExists(entityIndexKey);
		}

		/**
		 * Returns entity index by its storage primary key, or null if not found. Used to resolve `int[]` storage PKs
		 * returned by `ReferencedTypeEntityIndex.getAllReferenceIndexes(int)` into actual `ReducedGroupEntityIndex` /
		 * `ReducedEntityIndex` instances. Satisfies both {@link IndexProvider#getIndexByPrimaryKeyIfExists(int)} and
		 * the inherited contract on {@link IndexMaintainer} (where the throwing
		 * {@link IndexMaintainer#getIndexByPrimaryKey(int)} default delegates to this method).
		 */
		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			return EntityCollection.this.getIndexByPrimaryKeyIfExists(indexPrimaryKey);
		}

		/**
		 * Removes entity index by its key. If such index doesn't exist, exception is thrown.
		 *
		 * @throws IllegalArgumentException when entity index doesn't exist
		 */
		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			final EntityIndex removedIndex = EntityCollection.this.dataStoreBuffer.removeIndex(
				EntityCollection.this.catalog.getVersion(),
				entityIndexKey,
				eik -> {
					final EntityIndex index = Objects.requireNonNull(EntityCollection.this.indexes.remove(eik));
					// the mirror of the create above: inline outside a transaction, derived at commit within one
					if (!Transaction.isTransactionAvailable()) {
						EntityCollection.this.indexPopulation.recordRemoved(eik);
					}
					final EntityIndex indexByPk = EntityCollection.this.indexesByPrimaryKey.remove(index.getPrimaryKey());
					Assert.isPremiseValid(
						index == indexByPk,
						() -> new GenericEvitaInternalError(
							"Index by key " + eik + " and index by primary key " + index.getPrimaryKey() + " are not the same!"
						)
					);
					return index;
				}
			);
			if (removedIndex == null) {
				throw new GenericEvitaInternalError("Entity index for key " + entityIndexKey + " doesn't exists!");
			} else {
				ofNullable(getTransactionalLayerMaintainer())
					.ifPresent(removedIndex::removeTransactionalMemoryOfReferencedProducers);
			}
		}

		/**
		 * Returns the current entity schema for this collection. Used by mutation executors to look up
		 * `ReferenceSchemaContract` for the reference being modified.
		 */
		@Nonnull
		@Override
		public EntitySchema getEntitySchema() {
			return EntityCollection.this.getInternalSchema();
		}

		/**
		 * Returns the facet expression trigger for the given reference name, dependency type, and scope.
		 * Delegates to the catalog's {@link CatalogExpressionTriggerRegistry#getLocalTrigger} which stores
		 * one facet trigger per `(ownerEntityType, referenceName, scope)` triple.
		 */
		@Nullable
		@Override
		public FacetExpressionTrigger getFacetTrigger(
			@Nonnull String referenceName,
			@Nonnull DependencyType dependencyType,
			@Nonnull Scope scope
		) {
			return EntityCollection.this.catalog.getExpressionTriggerRegistry()
				.getLocalTrigger(EntityCollection.this.getEntityType(), referenceName, scope);
		}

		/**
		 * Evaluates a {@link FilterBy} constraint against this collection's `GlobalEntityIndex` for the specified
		 * scope and returns the matching entity PK bitmap. Uses the full query planning infrastructure
		 * (`FilterByVisitor` + `Formula`) to evaluate the filter.
		 *
		 * The session is used when available (normal session execution) for cache analysis but is **not required** —
		 * during WAL replay (trunk incorporation) no session exists and the evaluation proceeds without caching.
		 *
		 * @param filterBy the filter constraint to evaluate (typically a parameterized expression from a trigger)
		 * @param scope    the scope whose `GlobalEntityIndex` should be queried
		 * @return bitmap of entity primary keys matching the filter, never null (may be empty)
		 */
		@Nonnull
		@Override
		public Bitmap evaluateFilter(@Nonnull FilterBy filterBy, @Nonnull Scope scope) {
			// inject EntityScope into the filter so that EvitaRequest.getScopes() returns the correct scope;
			// without this, nested constraint processing (e.g., ReferenceHaving translator) would default to
			// LIVE scope and look up wrong indexes when evaluating ARCHIVED entities
			final FilterConstraint[] originalChildren = filterBy.getChildren();
			final FilterConstraint[] scopedChildren = new FilterConstraint[originalChildren.length + 1];
			scopedChildren[0] = scope(scope);
			System.arraycopy(originalChildren, 0, scopedChildren, 1, originalChildren.length);
			final FilterBy scopedFilterBy = new FilterBy(scopedChildren);

			final EvitaRequest evitaRequest = new EvitaRequest(
				Query.query(
					collection(EntityCollection.this.getEntityType()),
					scopedFilterBy
				),
				OffsetDateTime.now(),
				EntityReference.class,
				null
			);
			// use session-optional QueryPlanningContext — session may be null during WAL replay
			final QueryPlanningContext queryContext = new QueryPlanningContext(
				EntityCollection.this.catalog,
				EntityCollection.this,
				this.session,
				evitaRequest,
				EntityCollection.this.indexes,
				EntityCollection.this.indexesByPrimaryKey,
				EntityCollection.this.cacheSupervisor
			);
			final Set<Scope> requestedScopes = EnumSet.of(scope);
			final Formula formula = FilterByVisitor.createFormulaForTheFilter(
				queryContext,
				requestedScopes,
				scopedFilterBy,
				EntityCollection.this.getEntityType(),
				() -> "Evaluating conditional facet expression"
			);
			return formula.compute();
		}

		/**
		 * Returns the local histogram triggers for the given reference name and scope.
		 */
		@Nonnull
		@Override
		public Collection<HistogramExpressionTrigger> getHistogramTriggers(
			@Nonnull String referenceName,
			@Nonnull Scope scope
		) {
			return EntityCollection.this.catalog.getExpressionTriggerRegistry()
				.getLocalHistogramTriggers(EntityCollection.this.getEntityType(), referenceName, scope);
		}

		/**
		 * Returns the {@link FilterIndex} for a source attribute on another entity type's
		 * `GlobalEntityIndex`. Used for cross-collection value resolution in histogram processing.
		 */
		@Nullable
		@Override
		public FilterIndex getSourceFilterIndex(
			@Nonnull String entityType,
			@Nonnull String attributeName,
			@Nullable Locale locale,
			@Nonnull Scope scope
		) {
			final EntityCollection sourceCollection = EntityCollection.this.catalog
				.getCollectionForEntityOrThrowException(entityType);
			final EntityIndex globalIndex = sourceCollection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.GLOBAL, scope)
			);
			if (globalIndex == null) {
				return null;
			}
			return globalIndex.getFilterIndex(
				new AttributeIndexKey(null, attributeName, locale)
			);
		}

		/**
		 * Returns locales declared in the entity schema of the given entity type.
		 */
		@Nonnull
		@Override
		public Set<Locale> getEntitySchemaLocales(@Nonnull String entityType) {
			final EntityCollection sourceCollection = EntityCollection.this.catalog
				.getCollectionForEntityOrThrowException(entityType);
			return sourceCollection.getInternalSchema().getLocales();
		}

		/**
		 * Ensures that the reference index prerequisites are satisfied before proceeding.
		 * Verifies the existence of a global index and the presence of the specified reference in the entity schema.
		 *
		 * @param referenceName the name of the reference to check in the schema; must not be null
		 */
		private void assertReferenceIndexPrerequisites(@Nonnull String referenceName) {
			final EntityIndex globalIndex = getIndexIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
			Assert.isPremiseValid(
				globalIndex instanceof GlobalEntityIndex,
				"When a reduced index is created global one must already exist!"
			);
			// check that the reference exists in the schema
			EntityCollection.this
				.getSchema()
				.getReferenceOrThrowException(referenceName);
		}

	}

	/**
	 * A private class that implements the HeaderInfoSupplier interface.
	 * It provides information about the header of an EntityCollection.
	 */
	private class EntityCollectionHeaderInfoSupplier implements HeaderInfoSupplier {

		@Override
		public int getLastAssignedPrimaryKey() {
			return EntityCollection.this.pkSequence.get();
		}

		@Override
		public int getLastAssignedIndexKey() {
			return EntityCollection.this.indexPkSequence.get();
		}

		@Override
		public int getLastAssignedInternalPriceId() {
			return EntityCollection.this.pricePkSequence.get();
		}

		@Nonnull
		@Override
		public OptionalInt getGlobalIndexPrimaryKey() {
			return ofNullable(EntityCollection.this.indexes.get(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)))
				.map(it -> OptionalInt.of(it.getPrimaryKey()))
				.orElseGet(OptionalInt::empty);
		}

		@Nonnull
		@Override
		public List<Integer> getIndexPrimaryKeys() {
			// read the keys of the by-primary-key view rather than walking the index forest and re-deriving them: those
			// keys ARE the primary keys, already boxed, so this costs one array copy instead of a walk that unboxes and
			// re-boxes every index. This runs on every flush of every collection, alongside the commit merge that was
			// itself made proportional to the transaction
			return new ArrayList<>(EntityCollection.this.indexesByPrimaryKey.keySet());
		}
	}

	/**
	 * The EntityCollectionHeaderWithCollection record encapsulates both an EntityCollectionHeader and an EntityCollection.
	 * It's used to detect whether it's needed to replace collection instance in the catalog index.
	 */
	public record EntityCollectionHeaderWithCollection(
		@Nonnull EntityCollectionHeader header,
		@Nonnull EntityCollection collection,
		boolean changeOccurred
	) {}

	/**
	 * Represents a tuple containing two mappings related to entity indexes.
	 * This class is primarily used to aggregate and organize entity index data.
	 *
	 * The `indexes` map associates {@link EntityIndexKey} objects with corresponding {@link EntityIndex} instances.
	 * The `indexesByPk` map ties primary key integers to their respective {@link EntityIndex} instances.
	 */
	private record IndexTuple(
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull Map<Integer, EntityIndex> indexesByPk,
		@Nonnull IndexPopulation indexPopulation
	) {
	}
}
