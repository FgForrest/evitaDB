/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionContract.DeletedHierarchy;
import io.evitadb.api.exception.ConcurrentSchemaUpdateException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.SchemaNotFoundException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.core.buffer.DataStoreTxMemoryBuffer;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.ReferencedEntityFetcher;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.mutation.ContainerizedLocalMutationExecutor;
import io.evitadb.index.mutation.EntityIndexLocalMutationExecutor;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.reference.ReferenceChanges;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.index.transactionalMemory.diff.DataSourceChanges;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.schema.EntitySchemaStoragePart;
import io.evitadb.store.entity.serializer.EntitySchemaContext;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.DeferredStorageOperation;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.storageParts.accessor.ReadOnlyEntityStorageContainerAccessor;
import io.evitadb.store.spi.operation.PutStoragePartOperation;
import io.evitadb.store.spi.operation.RemoveStoragePartOperation;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.core.Transaction.getTransactionalMemoryLayer;
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
public final class EntityCollection implements TransactionalLayerProducer<DataSourceChanges<EntityIndexKey, EntityIndex>, EntityCollection>, EntityCollectionContract {

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
	private final EntityIndexMaintainerImpl entityIndexCreator = new EntityIndexMaintainerImpl();
	/**
	 * Contains schema of the entity type that is used for formal verification of the data consistency and indexing
	 * prescription.
	 */
	private final TransactionalReference<EntitySchemaDecorator> schema;
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
	private final AtomicReference<Catalog> catalogAccessor;
	/**
	 * Contains sequence that allows automatic assigning monotonic primary keys to the entities.
	 */
	private final AtomicInteger pkSequence;
	/**
	 * Contains sequence that allows assigning monotonic primary keys to the entity indexes.
	 */
	private final AtomicInteger indexPkSequence;
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
	 * @see DataStoreTxMemoryBuffer documentation
	 */
	@Getter private final DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> dataStoreBuffer;
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified
	 * by its {@link Formula#computeHash(LongHashFunction)} method and when the supervisor identifies that certain
	 * formula is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	private final CacheSupervisor cacheSupervisor;
	/**
	 * The factory that reads the {@link PersistentStorageDescriptor} and creates {@link EntityCollectionHeader} from
	 * it.
	 */
	private final Function<PersistentStorageDescriptor, EntityCollectionHeader> catalogEntityHeaderFactory;
	/**
	 * Service containing I/O related methods.
	 */
	private EntityCollectionPersistenceService persistenceService;

	public EntityCollection(
		@Nonnull Catalog catalog,
		int entityTypePrimaryKey,
		@Nonnull String entityType,
		@Nonnull CatalogPersistenceService catalogPersistenceService,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull SequenceService sequenceService
	) {
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.catalogPersistenceService = catalogPersistenceService;
		this.persistenceService = catalogPersistenceService.createEntityCollectionPersistenceService(entityType, entityTypePrimaryKey);
		this.cacheSupervisor = cacheSupervisor;

		final EntityCollectionHeader entityHeader = this.persistenceService.getCatalogEntityHeader();
		this.pkSequence = sequenceService.getOrCreateSequence(
			catalog.getName(), SequenceType.ENTITY, entityType, entityHeader.getLastPrimaryKey()
		);
		this.indexPkSequence = sequenceService.getOrCreateSequence(
			catalog.getName(), SequenceType.INDEX, entityType, entityHeader.getLastEntityIndexPrimaryKey()
		);
		this.catalogAccessor = new AtomicReference<>(catalog);

		// initialize container buffer
		this.dataStoreBuffer = new DataStoreTxMemoryBuffer<>(this, this.persistenceService);
		// initialize schema - still in constructor
		this.schema = new TransactionalReference<>(
			ofNullable(this.persistenceService.getStoragePart(1, EntitySchemaStoragePart.class))
				.map(EntitySchemaStoragePart::entitySchema)
				.map(it -> new EntitySchemaDecorator(() -> getCatalog().getSchema(), it))
				.orElseGet(() -> {
					if (this.persistenceService.isNew()) {
						final EntitySchema newEntitySchema = EntitySchema._internalBuild(entityType);
						this.dataStoreBuffer.update(new EntitySchemaStoragePart(newEntitySchema));
						return new EntitySchemaDecorator(() -> getCatalog().getSchema(), newEntitySchema);
					} else {
						throw new SchemaNotFoundException(catalog.getName(), entityHeader.getEntityType());
					}
				})
		);
		// init entity indexes
		if (entityHeader.getGlobalEntityIndexId() == null) {
			Assert.isPremiseValid(
				entityHeader.getUsedEntityIndexIds().isEmpty(),
				"Unexpected situation - global index doesn't exist but there are " +
					entityHeader.getUsedEntityIndexIds().size() + " reduced indexes!"
			);
			this.indexes = new TransactionalMap<>(new HashMap<>());
		} else {
			this.indexes = loadIndexes(entityHeader);
		}
		// sanity check whether we deserialized the memtable we expect to
		Assert.isTrue(
			entityHeader.getEntityType().equals(getSchema().getName()),
			"Deserialized schema name differs from expected entity type - expected " + entityHeader.getEntityType() + " got " + getSchema().getName()
		);
		// init factory method
		this.catalogEntityHeaderFactory = getCatalogEntityHeaderFactoryFunction();
		this.emptyOnStart = isEmpty();
	}

	private EntityCollection(
		@Nonnull Catalog catalog,
		int entityTypePrimaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull AtomicInteger pkSequence,
		@Nonnull AtomicInteger indexPkSequence,
		@Nonnull CatalogPersistenceService catalogPersistenceService,
		@Nonnull EntityCollectionPersistenceService persistenceService,
		@Nonnull Map<EntityIndexKey, EntityIndex> indexes,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this.entityTypePrimaryKey = entityTypePrimaryKey;
		this.schema = new TransactionalReference<>(new EntitySchemaDecorator(() -> getCatalog().getSchema(), entitySchema));
		this.catalogAccessor = new AtomicReference<>(catalog);
		this.pkSequence = pkSequence;
		this.catalogPersistenceService = catalogPersistenceService;
		this.persistenceService = persistenceService;
		this.indexPkSequence = indexPkSequence;
		this.dataStoreBuffer = new DataStoreTxMemoryBuffer<>(this, persistenceService);
		this.indexes = new TransactionalMap<>(indexes);
		for (EntityIndex entityIndex : this.indexes.values()) {
			entityIndex.updateReferencesTo(this);
		}
		this.cacheSupervisor = cacheSupervisor;
		// init factory method
		this.catalogEntityHeaderFactory = getCatalogEntityHeaderFactoryFunction();
		this.emptyOnStart = isEmpty();
	}

	@Override
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlan queryPlan = QueryPlanner.planQuery(
			createQueryContext(evitaRequest, session)
		);
		return queryPlan.execute();
	}

	@Override
	@Nonnull
	public Optional<BinaryEntity> getBinaryEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final Optional<BinaryEntity> entity = cacheSupervisor.analyse(
			session,
			primaryKey,
			getSchema().getName(),
			evitaRequest.getEntityRequirement(),
			() -> doWithPersistenceService(
				() -> persistenceService.readBinaryEntity(
					primaryKey,
					evitaRequest,
					session,
					entityType -> entityType.equals(getEntityType()) ?
						this : getCatalog().getCollectionForEntityOrThrowException(entityType),
					dataStoreBuffer
				)
			),
			binaryEntity -> doWithPersistenceService(
				() -> persistenceService.enrichEntity(getInternalSchema(), binaryEntity, evitaRequest, dataStoreBuffer)
			)
		);
		return entity.map(it -> limitEntity(it, evitaRequest.getEntityRequirement()));
	}

	@Nonnull
	@Override
	public List<BinaryEntity> getBinaryEntities(@Nonnull int[] primaryKeys, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return Arrays.stream(primaryKeys)
			.mapToObj(it -> getBinaryEntity(it, evitaRequest, session))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.toList();
	}

	@Override
	@Nonnull
	public Optional<SealedEntity> getEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return getEntity(primaryKey, evitaRequest, session, referenceFetcher);
	}

	@Nonnull
	@Override
	public List<SealedEntity> getEntities(@Nonnull int[] primaryKeys, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return getEntities(primaryKeys, evitaRequest, session, referenceFetcher);
	}

	@Override
	@Nonnull
	public EntityDecorator enrichEntity(@Nonnull SealedEntity sealedEntity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final ReferenceFetcher referenceFetcher = referenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
				evitaRequest.getDefaultReferenceRequirement(),
				createQueryContext(evitaRequest, session),
				sealedEntity
			);

		return enrichEntity(
			referenceFetcher.initReferenceIndex((EntityDecorator) sealedEntity, this),
			evitaRequest, referenceFetcher
		);
	}

	@Override
	@Nonnull
	public SealedEntity limitEntity(@Nonnull SealedEntity sealedEntity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final EntityDecorator widerEntity = (EntityDecorator) sealedEntity;
		final LocaleSerializablePredicate newLocalePredicate = new LocaleSerializablePredicate(evitaRequest, widerEntity.getLocalePredicate());
		final AttributeValueSerializablePredicate newAttributePredicate = new AttributeValueSerializablePredicate(evitaRequest, widerEntity.getAttributePredicate());
		final AssociatedDataValueSerializablePredicate newAssociatedDataPredicate = new AssociatedDataValueSerializablePredicate(evitaRequest, widerEntity.getAssociatedDataPredicate());
		final ReferenceContractSerializablePredicate newReferenceContractPredicate = new ReferenceContractSerializablePredicate(evitaRequest, widerEntity.getReferencePredicate());
		final PriceContractSerializablePredicate newPricePredicate = new PriceContractSerializablePredicate(evitaRequest, widerEntity.getPricePredicate());
		return Entity.decorate(
			widerEntity,
			// show / hide parent entity
			evitaRequest.isRequiresParent() ? widerEntity.getParentEntity().orElse(null) : null,
			// show / hide locales the entity is fetched in
			newLocalePredicate,
			// show / hide attributes information
			newAttributePredicate,
			// show / hide associated data information
			newAssociatedDataPredicate,
			// show / hide references information
			newReferenceContractPredicate,
			// show / hide price information
			newPricePredicate,
			// propagate original date time
			((EntityDecorator) sealedEntity).getAlignedNow()
		);
	}

	@Override
	@Nonnull
	public String getEntityType() {
		return schema.get().getName();
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

	@Override
	@Nonnull
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) throws InvalidMutationException {
		final int primaryKey = upsertEntityInternal(entityMutation);
		return new EntityReference(
			entityMutation.getEntityType(),
			primaryKey
		);
	}

	@Override
	@Nonnull
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final int primaryKey = upsertEntityInternal(entityMutation);
		final Entity internalEntity = getEntityById(primaryKey, evitaRequest);
		Assert.isPremiseValid(internalEntity != null, "Entity that has been just upserted is unexpectedly not found!");
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return wrapToDecorator(
			evitaRequest,
			referenceFetcher.initReferenceIndex(internalEntity, this),
			referenceFetcher,
			false
		);
	}

	@Override
	public boolean deleteEntity(int primaryKey) {
		// fetch entire entity from the data store
		final Entity entityToRemove = getFullEntityById(primaryKey);
		if (entityToRemove == null) {
			return false;
		}

		internalDeleteEntity(entityToRemove);
		return true;
	}

	@Override
	@Nonnull
	public Optional<SealedEntity> deleteEntity(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final int[] primaryKeys = evitaRequest.getPrimaryKeys();
		Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
		// fetch entire entity from the data store
		final Entity entityToRemove = getFullEntityById(primaryKeys[0]);
		if (entityToRemove == null) {
			return empty();
		}

		internalDeleteEntity(entityToRemove);
		final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
		return of(
			wrapToDecorator(
				evitaRequest,
				referenceFetcher.initReferenceIndex(entityToRemove, this),
				referenceFetcher,
				false
			)
		);
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
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			),
			session
		).deletedEntities();
	}

	@Override
	public DeletedHierarchy deleteEntityAndItsHierarchy(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final EntityIndex globalIndex = getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		if (globalIndex != null) {
			final int[] primaryKeys = evitaRequest.getPrimaryKeys();
			Assert.isTrue(primaryKeys.length == 1, "Expected exactly one primary key to delete!");
			final List<Entity> entitiesToRemove = Arrays.stream(globalIndex.listHierarchyNodesFromParentIncludingItself(primaryKeys[0]).getArray())
				.mapToObj(epk -> getEntityById(epk, evitaRequest))
				.filter(Objects::nonNull)
				.toList();
			final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
			final List<Entity> removedEntities = referenceFetcher.initReferenceIndex(entitiesToRemove, this);
			for (Entity entityToRemove : entitiesToRemove) {
				internalDeleteEntity(entityToRemove);
			}
			return new DeletedHierarchy(
				removedEntities.size(),
				removedEntities.stream()
					.findFirst()
					.map(it -> wrapToDecorator(
							evitaRequest, it,
							referenceFetcher,
							false
						)
					)
					.orElse(null)
			);
		}
		return new DeletedHierarchy(0, null);
	}

	@Override
	public int deleteEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlan queryPlan = QueryPlanner.planQuery(
			createQueryContext(evitaRequest, session)
		);
		final EvitaEntityReferenceResponse result = queryPlan.execute();
		return result
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.map(it -> this.deleteEntity(it) ? 1 : 0)
			.sum();
	}

	@Override
	@Nonnull
	public SealedEntity[] deleteEntitiesAndReturnThem(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlan queryPlan = QueryPlanner.planQuery(
			createQueryContext(evitaRequest, session)
		);
		final EvitaResponse<? extends Serializable> result = queryPlan.execute();
		return result
			.getRecordData()
			.stream()
			.map(it -> getFullEntityById(getPrimaryKey(it)))
			.filter(Objects::nonNull)
			.map(it -> {
				final ReferenceFetcher referenceFetcher = createReferenceFetcher(evitaRequest, session);
				final Entity fullEntity = referenceFetcher.initReferenceIndex(it, this);
				internalDeleteEntity(it);
				return wrapToDecorator(
					evitaRequest,
					fullEntity,
					referenceFetcher,
					false
				);
			})
			.toArray(SealedEntity[]::new);
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public int size() {
		return doWithPersistenceService(() -> this.persistenceService.count(EntityBodyStoragePart.class));
	}

	@Override
	@Nonnull
	public SealedEntitySchema getSchema() {
		return schema.get();
	}

	@Nonnull
	@Override
	public SealedEntitySchema updateSchema(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaMutation... schemaMutation) throws SchemaAlteringException {
		// internal schema is expected to be produced on the server side
		final EntitySchema currentSchema = getInternalSchema();
		EntitySchemaContract updatedSchema = currentSchema;
		for (EntitySchemaMutation theMutation : schemaMutation) {
			updatedSchema = theMutation.mutate(catalogSchema, updatedSchema);
			/* TOBEDONE JNO - this should be diverted to separate class and handle all necessary DDL operations */
			if (theMutation instanceof SetEntitySchemaWithHierarchyMutation setHierarchy) {
				if (setHierarchy.isWithHierarchy()) {
					getGlobalIndexIfExists()
						.ifPresent(it -> it.initRootNodes(it.getAllPrimaryKeys()));
				}
			}
		}

		final EntitySchemaContract nextSchema = updatedSchema;
		Assert.isPremiseValid(updatedSchema != null, "Entity collection cannot be dropped by updating schema!");
		Assert.isPremiseValid(updatedSchema instanceof EntitySchema, "Mutation is expected to produce EntitySchema instance!");
		if (updatedSchema.version() > currentSchema.version()) {
			/* TOBEDONE JNO - apply this just before commit happens in case validations are enabled */
			// assertAllReferencedEntitiesExist(newSchema);
			// assertReferences(newSchema);
			final EntitySchema updatedInternalSchema = (EntitySchema) updatedSchema;
			doWithPersistenceService(() -> {
				this.dataStoreBuffer.update(new EntitySchemaStoragePart(updatedInternalSchema));
				return null;
			});
			final EntitySchemaDecorator originalSchemaBeforeExchange = this.schema.compareAndExchange(
				this.schema.get(),
				new EntitySchemaDecorator(() -> getCatalog().getSchema(), updatedInternalSchema)
			);
			Assert.isTrue(
				originalSchemaBeforeExchange.version() == currentSchema.version(),
				() -> new ConcurrentSchemaUpdateException(currentSchema, nextSchema)
			);
		}
		return getSchema();
	}

	@Override
	public long getVersion() {
		return doWithPersistenceService(() -> this.persistenceService.getCatalogEntityHeader().getVersion());
	}

	@Override
	public void terminate() {
		Assert.isTrue(
			this.terminated.compareAndSet(false, true),
			"Collection was already terminated!"
		);
		this.persistenceService.close();
	}

	@Override
	public Iterator<Entity> entityIterator() {
		return doWithPersistenceService(() -> this.persistenceService.entityIterator(getInternalSchema(), this.dataStoreBuffer));
	}

	@Nonnull
	public Optional<SealedEntity> getEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session, @Nonnull ReferenceFetcher referenceFetcher) {
		// retrieve current version of entity
		return getEntityDecorator(primaryKey, evitaRequest, session)
			.map(it -> enrichEntity(referenceFetcher.initReferenceIndex(it, this), evitaRequest, referenceFetcher))
			.map(it -> limitEntity(it, evitaRequest, session));
	}

	@Nonnull
	public List<SealedEntity> getEntities(@Nonnull int[] primaryKeys, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session, @Nonnull ReferenceFetcher referenceFetcher) {
		// retrieve current version of entity
		final List<EntityDecorator> entityDecorators = Arrays.stream(primaryKeys)
			.mapToObj(it -> getEntityDecorator(it, evitaRequest, session))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.toList();
		return referenceFetcher.initReferenceIndex(entityDecorators, this)
			.stream()
			.map(it -> enrichEntity(it, evitaRequest, referenceFetcher))
			.map(it -> limitEntity(it, evitaRequest, session))
			.toList();
	}

	@Nonnull
	public Optional<EntityDecorator> getEntityDecorator(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return cacheSupervisor.analyse(
			session,
			primaryKey,
			getSchema().getName(),
			evitaRequest.getAlignedNow(),
			evitaRequest.getEntityRequirement(),
			() -> {
				final Entity internalEntity = getEntityById(primaryKey, evitaRequest);
				if (internalEntity == null) {
					return null;
				} else {
					return wrapToDecorator(evitaRequest, internalEntity, ReferenceFetcher.NO_IMPLEMENTATION);
				}
			},
			theEntity -> enrichEntity(theEntity, evitaRequest, ReferenceFetcher.NO_IMPLEMENTATION)
		);
	}

	@Nonnull
	public EntityDecorator enrichEntity(
		@Nonnull SealedEntity sealedEntity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ReferenceFetcher referenceFetcher
	)
		throws EntityAlreadyRemovedException {
		final EntityDecorator partiallyLoadedEntity = (EntityDecorator) sealedEntity;
		// return decorator that hides information not requested by original query
		final LocaleSerializablePredicate newLocalePredicate = partiallyLoadedEntity.createLocalePredicateRicherCopyWith(evitaRequest);
		final AttributeValueSerializablePredicate newAttributePredicate = partiallyLoadedEntity.createAttributePredicateRicherCopyWith(evitaRequest);
		final AssociatedDataValueSerializablePredicate newAssociatedDataPredicate = partiallyLoadedEntity.createAssociatedDataPredicateRicherCopyWith(evitaRequest);
		final ReferenceContractSerializablePredicate newReferenceContractPredicate = partiallyLoadedEntity.createReferencePredicateRicherCopyWith(evitaRequest);
		final PriceContractSerializablePredicate newPriceContractPredicate = partiallyLoadedEntity.createPricePredicateRicherCopyWith(evitaRequest);
		// fetch parents if requested
		final EntityClassifierWithParent parentEntity;
		if (partiallyLoadedEntity.getParentEntity().isPresent()) {
			parentEntity = partiallyLoadedEntity.getParentEntity().get();
		} else {
			parentEntity = partiallyLoadedEntity.getParent().isPresent() ?
				ofNullable(referenceFetcher.getParentEntityFetcher())
					.map(it -> it.apply(partiallyLoadedEntity.getParent().getAsInt()))
					.orElse(null) : null;
		}

		return doWithPersistenceService(
			() -> Entity.decorate(
				// load all missing data according to current evita request
				this.persistenceService.enrichEntity(
					getInternalSchema(),
					// use all data from existing entity
					partiallyLoadedEntity,
					newAttributePredicate,
					newAssociatedDataPredicate,
					newReferenceContractPredicate,
					newPriceContractPredicate,
					dataStoreBuffer
				),
				// use original schema
				getInternalSchema(),
				// fetch parents if requested
				parentEntity,
				// show / hide locales the entity is fetched in
				newLocalePredicate,
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
				// recursive entity loader
				referenceFetcher
			)
		);
	}

	/**
	 * Method checks whether the entity is {@link EntityDecorator} and verifies the decorator wraps an entity with
	 * fetched reference storage part. If it does not, new entity decorator instance is created wrapping the same entity
	 * with the reference container fetched. New instance will share same predicates to that the methods of
	 * the decorator will produce the same output as the original entity decorator in the input of this method.
	 *
	 * The caller will be able to unwrap the decorator using {@link EntityDecorator#getDelegate()} and access
	 * reference data in the wrapped entity instance. This is necessary for proper operation
	 * of {@link ReferencedEntityFetcher} implementation.
	 */
	@Nonnull
	public <T extends SealedEntity> T ensureReferencesFetched(@Nonnull T entity)
		throws EntityAlreadyRemovedException {
		if (entity instanceof EntityDecorator partiallyLoadedEntity) {
			if (partiallyLoadedEntity.getReferencePredicate().isRequiresEntityReferences()) {
				// if the references are already available, return the input decorator without change
				return entity;
			} else {
				// if they were not fetched, re-wrap current decorator around entity with fetched references
				// no predicates are changed in the output decorator, only inner entity is more rich
				//noinspection unchecked
				return (T) Entity.decorate(
					// load references if missing
					this.persistenceService.enrichEntity(
						getInternalSchema(),
						// use all data from existing entity
						partiallyLoadedEntity,
						partiallyLoadedEntity.getAttributePredicate(),
						partiallyLoadedEntity.getAssociatedDataPredicate(),
						new ReferenceContractSerializablePredicate(true),
						partiallyLoadedEntity.getPricePredicate(),
						dataStoreBuffer
					),
					// use original schema
					getInternalSchema(),
					partiallyLoadedEntity.getParentEntity().orElse(null),
					// show / hide locales the entity is fetched in
					partiallyLoadedEntity.getLocalePredicate(),
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
	public EntitySchema getInternalSchema() {
		return schema.get().getDelegate();
	}

	/**
	 * Flush operation persists immediately all information kept in non-transactional buffers to the disk.
	 * {@link CatalogPersistenceService} is fully synced with the disk file and will not contain any non-persisted data. Flush operation
	 * is ignored when there are no changes present in {@link CatalogPersistenceService}.
	 */
	@Nonnull
	public EntityCollectionHeader flush() {
		return doWithPersistenceService(() -> {
			this.persistenceService.flushTrappedUpdates(this.dataStoreBuffer.exchangeBuffer());
			return this.persistenceService.flush(0L, catalogEntityHeaderFactory);
		});
	}

	/**
	 * Returns entity index by its key. If such index doesn't exist, NULL is returned.
	 */
	@Nullable
	public EntityIndex getIndexByKeyIfExists(EntityIndexKey entityIndexKey) {
		return doWithPersistenceService(() -> this.dataStoreBuffer.getIndexIfExists(entityIndexKey, this.indexes::get));
	}

	/**
	 * Method returns {@link PriceSuperIndex}. This method is used when deserializing {@link PriceRefIndex} which
	 * looks up for prices in super index in order to save memory consumption.
	 */
	@Nonnull
	public PriceSuperIndex getPriceSuperIndex() {
		return getGlobalIndex().getPriceIndex();
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
	 * Method returns {@link GlobalEntityIndex} or throws an exception if it hasn't yet exist.
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalIndexIfExists() {
		final Optional<EntityIndex> globalIndex = ofNullable(getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL)));
		return globalIndex.map(it -> {
				Assert.isPremiseValid(it instanceof GlobalEntityIndex, "Global index not found in entity collection of `" + getSchema().getName() + "`.");
				return ofNullable((GlobalEntityIndex) it);
			})
			.orElse(empty());
	}

	/**
	 * Method creates {@link QueryContext} that is used for read operations.
	 */
	@Nonnull
	public QueryContext createQueryContext(
		@Nonnull QueryContext queryContext,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session
	) {
		return new QueryContext(
			queryContext,
			getCatalog(),
			this,
			new ReadOnlyEntityStorageContainerAccessor(dataStoreBuffer, this::getInternalSchema),
			session, evitaRequest,
			queryContext.getCurrentStep(),
			indexes,
			cacheSupervisor
		);
	}

	/**
	 * Method creates {@link QueryContext} that is used for read operations.
	 */
	@Nonnull
	public QueryContext createQueryContext(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return new QueryContext(
			getCatalog(),
			this,
			new ReadOnlyEntityStorageContainerAccessor(dataStoreBuffer, this::getInternalSchema),
			session, evitaRequest,
			evitaRequest.isQueryTelemetryRequested() ? new QueryTelemetry(QueryPhase.OVERALL) : null,
			indexes,
			cacheSupervisor
		);
	}

	@Override
	public DataSourceChanges<EntityIndexKey, EntityIndex> createLayer() {
		return new DataSourceChanges<>();
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.schema.removeLayer(transactionalLayer);
		this.indexes.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public EntityCollection createCopyWithMergedTransactionalMemory(@Nullable DataSourceChanges<EntityIndexKey, EntityIndex> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		final DataSourceChanges<EntityIndexKey, EntityIndex> transactionalChanges = transactionalLayer.getTransactionalMemoryLayer(this);
		if (transactionalChanges != null) {
			final String entityName = getEntityType();

			if (transaction != null) {
				transactionalChanges.getModifiedStoragePartsToPersist()
					.forEach(it -> transaction.register(entityName, new PutStoragePartOperation(it)));

				transactionalChanges.getRemovedStoragePartsToPersist()
					.forEach(it -> transaction.register(entityName, new RemoveStoragePartOperation(it)));
			}

			// when we register all storage parts for persisting we can now release transactional memory
			transactionalLayer.removeTransactionalMemoryLayer(this);
			return new EntityCollection(
				this.catalogAccessor.get(),
				this.entityTypePrimaryKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.schema, transaction)
					.map(EntitySchemaDecorator::getDelegate)
					.orElse(null),
				this.pkSequence,
				this.indexPkSequence,
				this.catalogPersistenceService,
				this.persistenceService,
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexes, transaction),
				cacheSupervisor
			);
		} else {
			final ReferenceChanges<EntitySchemaDecorator> schemaChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(this.schema);
			if (schemaChanges != null) {
				Assert.isPremiseValid(
					schemaChanges.get().version() == getSchema().version(),
					"Schema was unexpectedly modified!"
				);
				transactionalLayer.removeTransactionalMemoryLayerIfExists(this.schema);
			}
			Assert.isPremiseValid(
				transactionalLayer.getTransactionalMemoryLayerIfExists(this.indexes) == null,
				"Indexes are unexpectedly modified!"
			);
			// no changes present we can return self
			return this;
		}
	}

	/**
	 * This method writes all changed storage parts into the persistent storage of this {@link EntityCollection} and
	 * then returns updated {@link EntityCollectionHeader}.
	 */
	EntityCollectionHeader flush(long transactionId, @Nonnull List<DeferredStorageOperation<?>> updateInstructions) {
		return doWithPersistenceService(() -> {
			this.persistenceService.applyUpdates(getEntityType(), transactionId, updateInstructions);
			return this.persistenceService.flush(transactionId, catalogEntityHeaderFactory);
		});
	}

	/**
	 * This method replaces references in current instance that needs to work with information outside this entity
	 * collection. When transaction is committed new catalog instance is created after entity collection instances are
	 * recreated to encapsulate them. That means that all entity collections still point to the old catalog and when
	 * new one encapsulating them is created, all of them needs to update their "pointers".
	 */
	void updateReferenceToCatalog(@Nonnull Catalog catalog) {
		this.catalogAccessor.set(catalog);
	}

	/**
	 * Retrieves the primary key of the given entity or throws an unified exception.
	 */
	private int getPrimaryKey(@Nonnull Serializable entity) {
		if (entity instanceof EntityClassifier entityClassifier) {
			return Objects.requireNonNull(entityClassifier.getPrimaryKey());
		} else if (entity instanceof SealedEntityProxy sealedEntityProxy) {
			return Objects.requireNonNull(sealedEntityProxy.getSealedEntity().getPrimaryKey());
		} else {
			throw new EvitaInvalidUsageException(
				"Unsupported entity type `" + entity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
				"Unsupported entity type!"
			);
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Generates new UNIQUE primary key for the entity. Calling this
	 *
	 * @return new unique primary key
	 */
	private int getNextPrimaryKey() {
		// atomic integer takes care of concurrent access and producing unique monotonic sequence of numbers
		return pkSequence.incrementAndGet();
	}

	/**
	 * Returns reference to current {@link Catalog} instance.
	 */
	@Nonnull
	private Catalog getCatalog() {
		return catalogAccessor.get();
	}

	/**
	 * Method creates a function that allows to create new {@link EntityCollectionHeader} instance from
	 * {@link PersistentStorageDescriptor} DTO. The catalog entity header contains additional information from this
	 * entity collection instance we need to keep and propagate to next immutable catalog entity header object.
	 */
	@Nonnull
	private Function<PersistentStorageDescriptor, EntityCollectionHeader> getCatalogEntityHeaderFactoryFunction() {
		return newDescriptor -> new EntityCollectionHeader(
			this.getSchema().getName(),
			this.entityTypePrimaryKey,
			this.size(),
			pkSequence.get(),
			indexPkSequence.get(),
			newDescriptor,
			ofNullable(this.indexes.get(new EntityIndexKey(EntityIndexType.GLOBAL)))
				.map(EntityIndex::getPrimaryKey)
				.orElse(null),
			this.indexes
				.values()
				.stream()
				.filter(it -> it.getIndexKey().getType() != EntityIndexType.GLOBAL)
				.map(EntityIndex::getPrimaryKey)
				.collect(Collectors.toList())
		);
	}

	/**
	 * Method processes all local mutations of passed `entityMutation` using passed `changeCollector`
	 * and `entityIndexUpdater`.
	 */
	private void processMutations(
		@Nonnull Collection<? extends LocalMutation<?, ?>> localMutations,
		@Nonnull LocalMutationExecutor... mutationApplicators
	) {
		do {
			for (LocalMutation<?, ?> localMutation : localMutations) {
				for (LocalMutationExecutor mutationApplicator : mutationApplicators) {
					mutationApplicator.applyMutation(localMutation);
				}
			}

			localMutations = Arrays.stream(mutationApplicators)
				.flatMap(it -> doWithPersistenceService(() -> it.applyChanges().stream()))
				.toList();
		} while (!localMutations.isEmpty());
	}

	/**
	 * Method loads all indexes mentioned in {@link EntityCollectionHeader#getGlobalEntityIndexId()} and
	 * {@link EntityCollectionHeader#getUsedEntityIndexIds()} into a transactional map indexed by their
	 * {@link EntityIndex#getIndexKey()}.
	 */
	private TransactionalMap<EntityIndexKey, EntityIndex> loadIndexes(@Nonnull EntityCollectionHeader entityHeader) {
		// we need to load global index first, this is the only one index containing all data
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) this.persistenceService.readEntityIndex(
			entityHeader.getGlobalEntityIndexId(), this::getInternalSchema,
			() -> {
				throw new EvitaInternalError("Global index is currently loading!");
			},
			this::getPriceSuperIndex
		);
		Assert.isPremiseValid(globalIndex != null, "Global index must never be null for entity type `" + getSchema().getName() + "`!");
		return new TransactionalMap<>(
			// now join global index with all other reduced indexes into single key-value index
			Stream.concat(
					Stream.of(globalIndex),
					entityHeader.getUsedEntityIndexIds()
						.stream()
						.map(eid ->
							this.persistenceService.readEntityIndex(
								eid, this::getInternalSchema,
								// this method is used just for `readEntityIndex` method to access global index until
								// it's available by `this::getPriceSuperIndex` (constructor must be finished first)
								globalIndex::getPriceIndex,
								// this method needs to be used from now on to access the super index
								this::getPriceSuperIndex
							)
						)
				)
				.collect(
					Collectors.toMap(
						EntityIndex::getIndexKey,
						Function.identity()
					)
				)
		);
	}

	/**
	 * Method fetches the full contents of the entity by its primary key from the I/O storage (taking advantage of
	 * modified parts in the {@link DataStoreTxMemoryBuffer}.
	 */
	@Nullable
	private Entity getFullEntityById(int primaryKey) {
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(getSchema().getName()),
				require(entityFetchAll())
			),
			OffsetDateTime.now(),
			Entity.class,
			EvitaRequest.CONVERSION_NOT_SUPPORTED
		);
		return getEntityById(primaryKey, evitaRequest);
	}

	/**
	 * Method fetches the entity by its primary key from the I/O storage (taking advantage of modified parts in the
	 * {@link DataStoreTxMemoryBuffer}).
	 */
	@Nullable
	private Entity getEntityById(int primaryKey, @Nonnull EvitaRequest evitaRequest) {
		return doWithPersistenceService(
			() -> persistenceService.readEntity(
				primaryKey,
				evitaRequest,
				getInternalSchema(),
				dataStoreBuffer
			)
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
		return referenceEntityFetch.isEmpty() &&
			!evitaRequest.isRequiresEntityReferences() &&
			!evitaRequest.isRequiresParent() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				evitaRequest.getHierarchyContent(),
				referenceEntityFetch,
				evitaRequest.getDefaultReferenceRequirement(),
				createQueryContext(evitaRequest, session)
			);
	}

	/**
	 * Wraps full entity into an {@link EntityDecorator} that fulfills the requirements passed in input `evitaRequest`.
	 */
	@Nonnull
	private EntityDecorator wrapToDecorator(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Entity fullEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		return wrapToDecorator(
			evitaRequest, fullEntity, referenceFetcher, null
		);
	}

	/**
	 * Wraps full entity into an {@link EntityDecorator} that fulfills the requirements passed in input `evitaRequest`.
	 */
	@Nonnull
	private EntityDecorator wrapToDecorator(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Entity fullEntity,
		@Nonnull ReferenceFetcher referenceFetcher,
		@Nullable Boolean contextAvailable
	) {
		// fetch parents if requested
		final EntityClassifierWithParent parentEntity = fullEntity.getParent().isPresent() ?
			ofNullable(referenceFetcher.getParentEntityFetcher())
				.map(it -> it.apply(fullEntity.getParent().getAsInt()))
				.orElse(null) : null;

		return Entity.decorate(
			fullEntity,
			getInternalSchema(),
			parentEntity,
			new LocaleSerializablePredicate(evitaRequest),
			new AttributeValueSerializablePredicate(evitaRequest),
			new AssociatedDataValueSerializablePredicate(evitaRequest),
			new ReferenceContractSerializablePredicate(evitaRequest),
			new PriceContractSerializablePredicate(evitaRequest, contextAvailable),
			evitaRequest.getAlignedNow(),
			referenceFetcher
		);
	}

	/**
	 * Creates or updates entity and returns its primary key.
	 */
	private int upsertEntityInternal(@Nonnull EntityMutation entityMutation) {
		// verify mutation against schema
		// it was already executed when mutation was created, but there are two reasons to do it again
		// - we don't trust clients - in future it may be some external JS application
		// - schema may have changed between entity was provided to the client and the moment upsert was called
		final SealedCatalogSchema catalogSchema = getCatalog().getSchema();
		entityMutation.verifyOrEvolveSchema(catalogSchema, getSchema(), emptyOnStart && isEmpty())
			.ifPresent(it -> updateSchema(catalogSchema, it));

		// check the existence of the primary key and report error when unexpectedly (not) provided
		final SealedEntitySchema currentSchema = getSchema();
		final int entityPrimaryKey = verifyPrimaryKeyAssignment(entityMutation, currentSchema);

		applyMutations(
			entityPrimaryKey,
			entityMutation.expects(),
			entityMutation.getLocalMutations(),
			false
		);

		return entityPrimaryKey;
	}

	/**
	 * Method checks whether entity mutation primary key is present or not, and whether it should be present or not
	 * according to the schema settings.
	 *
	 * @return primary key assigned in the mutation
	 * @throws InvalidMutationException when the expectations are not met
	 */
	private int verifyPrimaryKeyAssignment(
		@Nonnull EntityMutation entityMutation,
		@Nonnull SealedEntitySchema currentSchema
	) throws InvalidMutationException {
		if (currentSchema.isWithGeneratedPrimaryKey()) {
			if (entityMutation.getEntityPrimaryKey() == null) {
				entityMutation.setEntityPrimaryKey(getNextPrimaryKey());
			} else if (entityMutation.expects() == EntityExistence.MUST_NOT_EXIST) {
				throw new InvalidMutationException(
					"Entity of type " + currentSchema.getName() +
						" is expected to have primary key automatically generated by Evita!"
				);
			} else if (entityMutation.expects() == EntityExistence.MAY_EXIST) {
				Assert.isTrue(
					getGlobalIndex().isPrimaryKeyKnown(entityMutation.getEntityPrimaryKey()),
					() -> new InvalidMutationException(
						"Entity of type " + currentSchema.getName() +
							" is expected to have primary key automatically generated by Evita!"
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
		return entityMutation.getEntityPrimaryKey();
	}

	/**
	 * Deletes passed entity both from indexes and the storage.
	 */
	private void internalDeleteEntity(@Nonnull Entity entityToRemove) {
		// construct set of removal mutations
		final int entityToRemovePrimaryKey = Objects.requireNonNull(entityToRemove.getPrimaryKey());
		final EntityRemoveMutation entityMutation = new EntityRemoveMutation(getEntityType(), entityToRemovePrimaryKey);

		applyMutations(
			entityToRemovePrimaryKey,
			entityMutation.expects(),
			entityMutation.computeLocalMutationsForEntityRemoval(entityToRemove),
			true
		);
	}

	/**
	 * Method applies all `localMutations` on entity with passed `entityPrimaryKey`.
	 *
	 * @param entityPrimaryKey   the primary key of the affected entity
	 * @param expects            enum controlling behaviour of the code when entity is or is not found
	 * @param localMutations     set of mutations to apply
	 * @param removeEntireEntity sanitization flag that verifies that in case of complete entity removal no update
	 *                           or insertion occurs and also removes the information about the entity primary key
	 *                           from the main entity indexes
	 */
	private void applyMutations(
		int entityPrimaryKey,
		@Nonnull EntityExistence expects,
		@Nonnull Collection<? extends LocalMutation<?, ?>> localMutations,
		boolean removeEntireEntity
	) {
		// prepare collectors
		final Function<String, EntitySchema> otherEntitySchemaAccessor = entityType -> this.catalogAccessor.get()
			.getCollectionForEntityOrThrowException(entityType).getInternalSchema();

		final ContainerizedLocalMutationExecutor changeCollector = new ContainerizedLocalMutationExecutor(
			dataStoreBuffer,
			entityPrimaryKey,
			expects,
			this::getInternalSchema,
			otherEntitySchemaAccessor,
			entityIndexCreator,
			removeEntireEntity
		);

		final EntityIndexLocalMutationExecutor entityIndexUpdater = new EntityIndexLocalMutationExecutor(
			changeCollector,
			entityPrimaryKey,
			this.entityIndexCreator,
			this.getCatalog().getCatalogIndexMaintainer(),
			this::getInternalSchema,
			otherEntitySchemaAccessor
		);

		// apply mutations leading to clearing storage containers
		EntitySchemaContext.executeWithSchemaContext(
			getInternalSchema(),
			() -> processMutations(
				localMutations,
				entityIndexUpdater, changeCollector
			)
		);

		if (removeEntireEntity) {
			// remove the entity itself from the indexes
			entityIndexUpdater.removeEntity(entityPrimaryKey);
		}
	}

	/**
	 * Method executes the lambda in that interacts with {@link #persistenceService} and returns the lambda result.
	 * When the persistence service was closed in the meantime (for example the underlying file was renamed),
	 * the service is automatically recreated.
	 */
	private <T> T doWithPersistenceService(Supplier<T> lambda) {
		if (this.persistenceService.isClosed()) {
			// if the service was closed in the meantime - just recreate it
			this.persistenceService = this.catalogPersistenceService.createEntityCollectionPersistenceService(getEntityType(), getEntityTypePrimaryKey());
			this.dataStoreBuffer.setPersistenceService(persistenceService);
		}
		return lambda.get();
	}

	/**
	 * Method will check whether the entity contains only the content required by `fetchRequirements` and if it contains
	 * more data than requested, new instance of {@link BinaryEntity} is created referencing only the requested set
	 * of data containers.
	 */
	@Nonnull
	private BinaryEntity limitEntity(@Nonnull BinaryEntity entity, @Nonnull EntityFetch fetchRequirements) {
		/* TOBEDONE https://gitlab.fg.cz/hv/evita/-/issues/137 */
		return entity;
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
				final Catalog catalog = this.catalogAccessor.get();
				Assert.isTrue(
					catalog.getCollectionForEntity(it).isPresent(),
					() -> new InvalidMutationException(
						"Entity schema `" + newSchema.getName() + "` references entity `" + it + "`," +
							" but such entity is not known in catalog `" + catalog.getName() + "`."
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
					.filter(it -> it.isSortable() && !it.isNullable())
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
	 * This implementation just manipulates with the set of EntityIndex in entity collection.
	 */
	private class EntityIndexMaintainerImpl implements IndexMaintainer<EntityIndexKey, EntityIndex> {

		/**
		 * Returns entity index by its key. If such index doesn't exist, it is automatically created.
		 */
		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			return doWithPersistenceService(
				() -> EntityCollection.this.dataStoreBuffer.getOrCreateIndexForModification(
					entityIndexKey,
					eik ->
						// if storage container buffer doesn't have index in "dirty" memory - retrieve index from collection
						EntityCollection.this.indexes.computeIfAbsent(
							eik,
							eikAgain -> {
								// if index doesn't exist even there create new one
								if (eikAgain.getType() == EntityIndexType.GLOBAL) {
									return new GlobalEntityIndex(indexPkSequence.incrementAndGet(), eikAgain, EntityCollection.this::getInternalSchema);
								} else {
									final EntityIndex globalIndex = getIndexIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
									Assert.isPremiseValid(
										globalIndex instanceof GlobalEntityIndex,
										"When reduced index is created global one must already exist!"
									);
									if (eikAgain.getType() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
										return new ReferencedTypeEntityIndex(
											indexPkSequence.incrementAndGet(), eikAgain,
											EntityCollection.this::getInternalSchema
										);
									} else {
										return new ReducedEntityIndex(
											indexPkSequence.incrementAndGet(), eikAgain,
											EntityCollection.this::getInternalSchema,
											((GlobalEntityIndex) globalIndex)::getPriceIndex
										);
									}
								}
							}
						)
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
			final EntityIndex removedIndex = doWithPersistenceService(
				() -> EntityCollection.this.dataStoreBuffer.removeIndex(
					entityIndexKey, EntityCollection.this.indexes::remove
				)
			);
			if (removedIndex == null) {
				throw new EvitaInternalError("Entity index for key " + entityIndexKey + " doesn't exists!");
			} else {
				ofNullable(getTransactionalMemoryLayer())
					.ifPresent(removedIndex::removeTransactionalMemoryOfReferencedProducers);
			}
		}

	}

}
