/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core;

import io.evitadb.api.CatalogState;
import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ConcurrentSchemaUpdateException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.SchemaNotFoundException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
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
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.ReferencedEntityFetcher;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.response.ServerBinaryEntityDecorator;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.stage.mutation.ServerEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.reference.ReferenceChanges;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.entity.model.schema.EntitySchemaStoragePart;
import io.evitadb.store.entity.serializer.EntitySchemaContext;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService.BinaryEntityWithFetchCount;
import io.evitadb.store.spi.EntityCollectionPersistenceService.EntityWithFetchCount;
import io.evitadb.store.spi.HeaderInfoSupplier;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.TrafficRecorder;
import io.evitadb.store.spi.chunk.ServerChunkTransformerAccessor;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.Transaction.getTransactionalLayerMaintainer;
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
	 */
	@Getter private final String entityType;
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
	 */
	private final AtomicInteger pkSequence;
	/**
	 * Contains sequence that allows assigning monotonic primary keys to the entity indexes.
	 */
	private final AtomicInteger indexPkSequence;
	/**
	 * Contains the sequence for assigning {@link PriceInternalIdContainer#getInternalPriceId()} to a newly encountered
	 * prices in the input data. See {@link PriceInternalIdContainer} to see the reasons behind it. The price sequence
	 * is shared among live and archive scope to avoid ambiguities.
	 */
	private final AtomicInteger pricePkSequence;
	/**
	 * Service allowing to recreate I/O collection service on-demand.
	 */
	private final CatalogPersistenceService catalogPersistenceService;
	/**
	 * Collection of search indexes prepared to handle queries.
	 */
	private final TransactionalMap<EntityIndexKey, EntityIndex> indexes;
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
	private final EntityCollectionPersistenceService persistenceService;
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
		@Nonnull EntityCollectionPersistenceService entityCollectionPersistenceService,
		long catalogVersion
	) {
		// if entity header has no last internal price id, initialized and there is global index available
		return entityHeader.lastInternalPriceId() == -1 && entityHeader.globalEntityIndexId() != null ?
			// try to initialize sequence from deprecated storage key format
			entityCollectionPersistenceService.fetchLastAssignedInternalPriceIdFromGlobalIndex(
				catalogVersion,
				entityHeader.globalEntityIndexId()
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
		@Nonnull Map<EntityIndexKey, EntityIndex> entityIndexes,
		@Nonnull CatalogPersistenceService catalogPersistenceService,
		@Nonnull EntityCollectionPersistenceService entityCollectionPersistenceService,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull SequenceService sequenceService,
		@Nonnull TrafficRecordingEngine trafficRecorder
	) {
		this.trafficRecorder = trafficRecorder;
		this.entityType = entityType;
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
			final StoragePartPersistenceService storagePartPersistenceService = this.persistenceService.getStoragePartPersistenceService();
			this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
				new WarmUpDataStoreMemoryBuffer(storagePartPersistenceService) :
				new TransactionalDataStoreMemoryBuffer(this, storagePartPersistenceService);
			this.dataStoreReader = new DataStoreReaderBridge(
				this.dataStoreBuffer,
				this::getIndexByKeyIfExists,
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
			if (entityHeader.globalEntityIndexId() == null) {
				Assert.isPremiseValid(
					entityHeader.usedEntityIndexIds().isEmpty(),
					"Unexpected situation - global index doesn't exist but there are " +
						entityHeader.usedEntityIndexIds().size() + " reduced indexes!"
				);
				this.indexes = new TransactionalMap<>(
					CollectionUtils.createHashMap(64),
					EntityIndex.class::cast
				);
			} else {
				this.indexes = new TransactionalMap<>(entityIndexes, EntityIndex.class::cast);
			}

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
		@Nonnull CatalogPersistenceService catalogPersistenceService,
		@Nonnull SequenceService sequenceService
	) {
		this.trafficRecorder = previousCollection.trafficRecorder;
		this.entityType = previousCollection.getSchema().getName();
		this.entityTypePrimaryKey = previousCollection.entityTypePrimaryKey;
		this.initialSchema = previousCollection.getInternalSchema();
		this.catalogPersistenceService = catalogPersistenceService;

		this.persistenceService = catalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, this.entityType, this.entityTypePrimaryKey
		);

		final EntityCollectionHeader entityHeader = this.persistenceService.getEntityCollectionHeader();
		this.pkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.ENTITY, this.entityType, entityHeader.lastPrimaryKey()
		);
		this.indexPkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.INDEX, this.entityType, entityHeader.lastEntityIndexPrimaryKey()
		);
		// we need to initialize the price sequence here, in order to initialize correctly the last internal price id
		// from older storage format when it was stored as a part of the global index
		this.pricePkSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.PRICE, this.entityType,
			getLastAssignedPriceInternalPrimaryKey(entityHeader, this.persistenceService, catalogVersion)
		);

		this.dataStoreBuffer = catalogState == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(this.persistenceService.getStoragePartPersistenceService()) :
			new TransactionalDataStoreMemoryBuffer(this, this.persistenceService.getStoragePartPersistenceService());
		this.dataStoreReader = new DataStoreReaderBridge(
			this.dataStoreBuffer,
			this::getIndexByKeyIfExists,
			this::getInternalSchema
		);
		this.indexes = new TransactionalMap<>(
			previousCollection.createIndexCopiesForNewCatalogAttachment(catalogState),
			EntityIndex.class::cast
		);
		this.cacheSupervisor = previousCollection.cacheSupervisor;
		this.emptyOnStart = this.persistenceService.isEmpty(catalogVersion, this.dataStoreReader);
		this.defaultMinimalQuery = new EvitaRequest(
			Query.query(collection(this.entityType)),
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
		@Nonnull CatalogPersistenceService catalogPersistenceService,
		@Nonnull EntityCollectionPersistenceService persistenceService,
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull TrafficRecordingEngine trafficRecorder
	) {
		this.trafficRecorder = trafficRecorder;
		this.entityType = entitySchema.getName();
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
			this::getInternalSchema
		);
		this.indexes = new TransactionalMap<>(indexes, EntityIndex.class::cast);
		this.cacheSupervisor = cacheSupervisor;
		this.emptyOnStart = this.persistenceService.isEmpty(catalogVersion, this.dataStoreReader);
		this.defaultMinimalQuery = new EvitaRequest(
			Query.query(collection(this.entityType)),
			OffsetDateTime.MIN, // we don't care about the time
			EntityReference.class,
			null
		);
	}

	@Delegate(types = DataStoreReader.class)
	public DataStoreReader getDataStoreReader() {
		return this.dataStoreReader;
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
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		final ReferenceFetcher referenceFetcher = referenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
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
	public EntityReference upsertEntity(@Nonnull EvitaSessionContract session, @Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		return upsertEntityInternal(
			session,
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
				() -> of((T) applyReferenceFetcher(entity, referenceFetcher))
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
				() -> of((T) applyReferenceFetcher(entity, referenceFetcher))
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
	public SealedEntitySchema updateSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaMutation... schemaMutation) throws SchemaAlteringException {
		// internal schema is expected to be produced on the server side
		final EntitySchema originalSchema = getInternalSchema();
		try {
			EntitySchema updatedSchema = originalSchema;
			final Set<String> updatedReferenceSchemas = CollectionUtils.createHashSet(originalSchema.getReferences().size());
			for (EntitySchemaMutation theMutation : schemaMutation) {
				updatedSchema = (EntitySchema) theMutation.mutate(catalogSchema, updatedSchema);
				/* TOBEDONE #409 JNO - this should be diverted to separate class and handle all necessary DDL operations */
				if (theMutation instanceof SetEntitySchemaWithHierarchyMutation setHierarchy) {
					if (setHierarchy.isWithHierarchy()) {
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
	public EntityCollectionStatistics getStatistics() {
		return new EntityCollectionStatistics(
			getEntityType(),
			size(),
			this.indexes.size(),
			this.persistenceService.getSizeOnDiskInBytes()
		);
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
		this.persistenceService.close();
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
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) throws EntityAlreadyRemovedException {
		if (referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION) {
			return (ServerEntityDecorator) sealedEntity;
		} else {
			referenceFetcher.initReferenceIndex(sealedEntity, this);
			return applyReferenceFetcherInternal((ServerEntityDecorator) sealedEntity, referenceFetcher);
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
		@Nonnull List<SealedEntity> sealedEntities,
		@Nonnull ReferenceFetcher referenceFetcher
	) throws EntityAlreadyRemovedException {
		if (referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION) {
			return sealedEntities;
		} else {
			return referenceFetcher.initReferenceIndex(sealedEntities, this)
				.stream()
				.map(it -> applyReferenceFetcherInternal((ServerEntityDecorator) it, referenceFetcher))
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
					entityWithFetchCount.ioFetchedBytes(),
					// recursive entity loader
					ReferenceFetcher.NO_IMPLEMENTATION
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
	public EntityIndex getIndexByKeyIfExists(EntityIndexKey entityIndexKey) {
		return this.dataStoreBuffer.getIndexIfExists(entityIndexKey, this.indexes::get);
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
			evitaRequest.isQueryTelemetryRequested() ? new QueryTelemetry(QueryPhase.OVERALL) : null,
			this.indexes,
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
		this.persistenceService.flushTrappedUpdates(0L, trappedChanges, progressObserver);
		final long previousVersion = this.persistenceService.getEntityCollectionHeader().version();
		return this.catalogPersistenceService.flush(
				0L,
				this.headerInfoSupplier,
				this.persistenceService.getEntityCollectionHeader(),
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
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		this.catalog = catalog;
		this.schema = new TransactionalReference<>(
			new EntitySchemaDecorator(catalog::getSchema, this.initialSchema)
		);
		for (EntityIndex entityIndex : this.indexes.values()) {
			if (entityIndex instanceof CatalogRelatedDataStructure<?> catalogRelatedEntityIndex) {
				catalogRelatedEntityIndex.attachToCatalog(this.entityType, this.catalog);
			}
		}
	}

	@Override
	public DataStoreChanges createLayer() {
		return new DataStoreChanges(
			Transaction.createTransactionalPersistenceService(
				this.persistenceService.getStoragePartPersistenceService()
			)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.schema.removeLayer(transactionalLayer);
		this.indexes.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public EntityCollection createCopyWithMergedTransactionalMemory(@Nullable DataStoreChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final long catalogVersion = this.catalog.getVersion();
		final DataStoreChanges transactionalChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(this);
		final EntityCollectionPersistenceService newPersistenceService = this.catalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, this.entityType, this.entityTypePrimaryKey
		);
		if (transactionalChanges != null) {
			// when we register all storage parts for persisting, we can now release transactional memory
			transactionalLayer.removeTransactionalMemoryLayer(this);
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
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexes),
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
			if (this.persistenceService != newPersistenceService) {
				// if the compaction occurred, the persistence service may have changed
				// we just create a new collection with the new persistence service, but leave the rest of the state intact
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
					createIndexCopiesForNewCatalogAttachment(CatalogState.ALIVE),
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
		@Nonnull EntityCollectionPersistenceService newPersistenceService
	) {
		final EntitySchema internalSchema = this.getInternalSchema();
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
			this.indexes,
			this.cacheSupervisor,
			this.trafficRecorder
		);
		// the catalog remains the same here
		entityCollection.catalog = this.catalog;
		entityCollection.schema = new TransactionalReference<>(
			new EntitySchemaDecorator(this.catalog::getSchema, internalSchema)
		);
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
			createIndexCopiesForNewCatalogAttachment(catalogState),
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
		if (entityIndex instanceof CatalogRelatedDataStructure<?> crds) {
			crds.attachToCatalog(this.entityType, this.catalog);
		}
		this.indexes.put(entityIndex.getIndexKey(), entityIndex);
	}

	/**
	 * Retrieves the entity collection header from the persistence service.
	 *
	 * @return the entity collection header
	 */
	@Nonnull
	EntityCollectionHeader getEntityCollectionHeader() {
		return this.persistenceService.getEntityCollectionHeader();
	}

	/**
	 * This method writes all changed storage parts into the persistent storage of this {@link EntityCollection} and
	 * then returns updated {@link EntityCollectionHeader}.
	 */
	@Nonnull
	EntityCollectionHeader flush(long catalogVersion) {
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
			.map(it -> applyReferenceFetcher(it, referenceFetcher));
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
				removeMutation.shouldApplyUndoOnError(),
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
			entityWithFetchCount.ioFetchedBytes(),
			// recursive entity loader
			ReferenceFetcher.NO_IMPLEMENTATION
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
	 * Method loads all indexes mentioned in {@link EntityCollectionHeader#globalEntityIndexId()} and
	 * {@link EntityCollectionHeader#usedEntityIndexIds()} into a transactional map indexed by their
	 * {@link EntityIndex#getIndexKey()}.
	 */
	private void loadIndexes(
		long catalogVersion,
		@Nonnull EntityCollectionHeader entityHeader,
		@Nonnull Map<EntityIndexKey, EntityIndex> fetchedIndexes
	) {
		// we need to load the global index first, this is the only one index containing all data
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) this.persistenceService.readEntityIndex(
			catalogVersion,
			Objects.requireNonNull(entityHeader.globalEntityIndexId()),
			this.initialSchema
		);
		Assert.isPremiseValid(
			globalIndex != null,
			() -> "Global index must never be null for the entity type `" + this.initialSchema.getName() + "`!"
		);
		for (Integer eid : entityHeader.usedEntityIndexIds()) {
			final EntityIndex entityIndex = this.persistenceService.readEntityIndex(
				catalogVersion, eid, this.initialSchema
			);
			fetchedIndexes.put(entityIndex.getIndexKey(), entityIndex);
		}
		// in older versions the global index was not included in the used indexes
		final EntityIndexKey globalIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL);
		if (!fetchedIndexes.containsKey(globalIndexKey)) {
			fetchedIndexes.put(globalIndexKey, globalIndex);
		}
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
		final QueryPlanningContext queryContext = createQueryContext(evitaRequest, session);
		return referenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
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

		return new ServerEntityDecorator(sealedEntity, parentEntity, referenceFetcher);
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
			fullEntityWithCount.ioFetchedBytes(),
			ReferenceFetcher.NO_IMPLEMENTATION
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
		entityMutation.verifyOrEvolveSchema(catalogSchema, getSchema(), this.emptyOnStart && isEmpty())
			.ifPresent(
				it -> {
					Assert.isPremiseValid(
						session != null,
						"Implicit schema evolution cannot happen during transactional replay without user session. " +
							"In this phase the implicit schema is converted to explicit schema change, " +
							"that is ought to be written in the WAL before the operations requiring such schema change."
					);
					// we need to call apply mutation on the catalog level in order to insert the mutations to the WAL
					final ModifyEntitySchemaMutation modifyEntitySchemaMutation = new ModifyEntitySchemaMutation(getEntityType(), it);
					/* TODO JNO - tady je chyba, v případě transakce se tohle musí odehrát až ve chvíli, kdy je transakce commitnutá!! */
					/* TODO JNO - protože se to zapíše do engine WAL a ihned to vidí klienti, jenže to se dropne a musí se to přehrát s transakcí jako takovou */
					session.getEvita().applyMutation(
						new ModifyCatalogSchemaMutation(catalogSchema.getName(), session.getId(), modifyEntitySchemaMutation)
					)
					       // we to actively wait for the result here because the schema must be changed before
					       // we proceed with the entity upsert
					       .onCompletion()
					       .toCompletableFuture()
					       .join();
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
				veum.shouldApplyUndoOnError(),
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
	 * @param undoOnError               whether to undo the changes on error
	 *                                  (if set to false, the changes will be left in the storage)
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
		boolean undoOnError,
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
			theEntityType -> this.catalog.getCollectionForEntityInternal(theEntityType).orElse(null),
			this::nextInternalPriceId,
			entityMutation instanceof EntityRemoveMutation
		);
		final EntityIndexLocalMutationExecutor entityIndexUpdater = new EntityIndexLocalMutationExecutor(
			changeCollector,
			entityPrimaryKey,
			this.entityIndexCreator,
			this.catalog.getCatalogIndexMaintainer(),
			this::getInternalSchema,
			this::nextInternalPriceId,
			undoOnError,
			() -> localMutationExecutorCollector.getFullEntityContents(changeCollector).entity()
		);

		return localMutationExecutorCollector.execute(
			session,
			getInternalSchema(),
			entityMutation,
			checkConsistency,
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
	 * Creates a map of index copies for a new catalog attachment, based on the current indexes.
	 * If an index is a catalog-related data structure, a copy for the new catalog attachment is created;
	 * otherwise, the original index is reused.
	 *
	 * @param catalogState the state of the new catalog to which the indices will be attached
	 * @return a map containing keys and their corresponding index copies or original indexes
	 */
	@Nonnull
	private Map<EntityIndexKey, EntityIndex> createIndexCopiesForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		//noinspection unchecked
		return this.indexes.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Map.Entry::getKey,
					it -> it.getValue() instanceof CatalogRelatedDataStructure ?
						((CatalogRelatedDataStructure<? extends EntityIndex>) it.getValue()).createCopyForNewCatalogAttachment(catalogState) :
						it.getValue()
				)
			);
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
			if (cardinality == Cardinality.ONE_OR_MORE || cardinality == Cardinality.ZERO_OR_MORE) {
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

	}

	/**
	 * This implementation just manipulates with the set of EntityIndex in entity collection.
	 */
	private class EntityIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {

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
								entityIndex = new GlobalEntityIndex(EntityCollection.this.indexPkSequence.incrementAndGet(), EntityCollection.this.entityType, eikAgain);
							} else {
								final EntityIndex globalIndex = getIndexIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
								Assert.isPremiseValid(
									globalIndex instanceof GlobalEntityIndex,
									"When reduced index is created global one must already exist!"
								);
								final Serializable discriminator = eikAgain.discriminator();
								if (eikAgain.type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
									Assert.isPremiseValid(
										discriminator instanceof String,
										"Referenced type entity index must have discriminator of type String, but got " + (discriminator == null ? "NULL" : discriminator.getClass().getName())
									);
									entityIndex = new ReferencedTypeEntityIndex(
										EntityCollection.this.indexPkSequence.incrementAndGet(), EntityCollection.this.entityType, eikAgain
									);
								} else {
									Assert.isPremiseValid(
										discriminator instanceof ReferenceKey,
										"Reduced index must have discriminator of type ReferenceKey, but got " + (discriminator == null ? "NULL" : discriminator.getClass().getName())
									);
									final ReferenceSchemaContract referenceSchema = EntityCollection.this.getSchema()
										.getReferenceOrThrowException(((ReferenceKey) discriminator).referenceName());
									entityIndex = new ReducedEntityIndex(
										EntityCollection.this.indexPkSequence.incrementAndGet(), EntityCollection.this.entityType, eikAgain
									);
								}
							}

							if (entityIndex instanceof CatalogRelatedDataStructure<?> lateInitializationIndex) {
								lateInitializationIndex.attachToCatalog(EntityCollection.this.entityType, EntityCollection.this.catalog);
							}

							return entityIndex;
						}
					)
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
		 * Removes entity index by its key. If such index doesn't exist, exception is thrown.
		 *
		 * @throws IllegalArgumentException when entity index doesn't exist
		 */
		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			final EntityIndex removedIndex = EntityCollection.this.dataStoreBuffer.removeIndex(
				entityIndexKey, EntityCollection.this.indexes::remove
			);
			if (removedIndex == null) {
				throw new GenericEvitaInternalError("Entity index for key " + entityIndexKey + " doesn't exists!");
			} else {
				ofNullable(getTransactionalLayerMaintainer())
					.ifPresent(removedIndex::removeTransactionalMemoryOfReferencedProducers);
			}
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
		public OptionalInt getGlobalIndexKey() {
			return ofNullable(EntityCollection.this.indexes.get(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)))
				.map(it -> OptionalInt.of(it.getPrimaryKey()))
				.orElseGet(OptionalInt::empty);
		}

		@Nonnull
		@Override
		public List<Integer> getIndexKeys() {
			return EntityCollection.this.indexes
				.values()
				.stream()
				.map(EntityIndex::getPrimaryKey)
				.collect(Collectors.toList());
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

}
