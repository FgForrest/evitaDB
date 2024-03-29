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

import com.carrotsearch.hppc.ObjectObjectIdentityHashMap;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.ConcurrentSchemaUpdateException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.SchemaNotFoundException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.impl.UnsatisfiedDependencyFactory;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
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
import io.evitadb.api.trace.TracingContext;
import io.evitadb.core.Transaction.CommitUpdateInstructionSet;
import io.evitadb.core.buffer.DataStoreTxMemoryBuffer;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.exception.StorageImplementationNotFoundException;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.index.transactionalMemory.diff.DataSourceChanges;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;
import io.evitadb.store.spi.DeferredStorageOperation;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.storageParts.accessor.CatalogReadOnlyEntityStorageContainerAccessor;
import io.evitadb.store.spi.operation.PutStoragePartOperation;
import io.evitadb.store.spi.operation.RemoveCollectionOperation;
import io.evitadb.store.spi.operation.RemoveStoragePartOperation;
import io.evitadb.store.spi.operation.RenameCollectionOperation;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.MAX_POWER_OF_TWO;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * {@inheritDoc}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@ThreadSafe
public final class Catalog implements CatalogContract, TransactionalLayerProducer<DataSourceChanges<CatalogIndexKey, CatalogIndex>, Catalog> {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * CatalogIndex factory implementation.
	 */
	@Getter(AccessLevel.PACKAGE)
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
	 * Contains count of concurrently opened read-write sessions connected with this catalog.
	 * This information is used to control lifecycle of {@link #ioService} object.
	 */
	private final AtomicInteger readWriteSessionCount;
	/**
	 * Contains last given transaction id - it represents sequence number that allows to generate unique number across
	 * multiple clients.
	 */
	private final AtomicLong txPkSequence;
	/**
	 * Service containing I/O related methods.
	 */
	private final CatalogPersistenceService ioService;
	/**
	 * This instance is used to cover changes in transactional memory and persistent storage reference.
	 *
	 * @see DataStoreTxMemoryBuffer documentation
	 */
	private final DataStoreTxMemoryBuffer<CatalogIndexKey, CatalogIndex, DataSourceChanges<CatalogIndexKey, CatalogIndex>> dataStoreBuffer;
	/**
	 * This field contains flag with TRUE value if catalog is being switched to {@link CatalogState#ALIVE} state.
	 */
	private final AtomicBoolean goingLive = new AtomicBoolean();
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified by
	 * its {@link Formula#computeHash(LongHashFunction)} method and when the supervisor identifies that certain formula
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
	 * Indicates state in which Catalog operates.
	 *
	 * @see CatalogState
	 */
	private final AtomicReference<CatalogState> state;
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
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * Provides access to the entity schema in the catalog.
	 */
	@Getter private final CatalogEntitySchemaAccessor entitySchemaAccessor = new CatalogEntitySchemaAccessor();
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Contains id of the transaction ({@link Transaction#getId()}) that was successfully committed to the disk.
	 */
	@Getter long lastCommittedTransactionId;

	public Catalog(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull TracingContext tracingContext
	) {
		this.tracingContext = tracingContext;
		final CatalogSchema internalCatalogSchema = CatalogSchema._internalBuild(
			catalogSchema.getName(), catalogSchema.getNameVariants(), catalogSchema.getCatalogEvolutionMode(),
			getEntitySchemaAccessor()
		);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(internalCatalogSchema));
		this.ioService = ServiceLoader.load(CatalogPersistenceServiceFactory.class)
			.findFirst()
			.map(it -> it.createNew(this.getSchema().getName(), storageOptions))
			.orElseThrow(StorageImplementationNotFoundException::new);

		try {
			this.ioService.prepare();
			this.ioService.putStoragePart(0L, new CatalogSchemaStoragePart(getInternalSchema()));
			this.ioService.storeHeader(CatalogState.WARMING_UP, 0L, 0, Collections.emptyList());
		} finally {
			this.ioService.release();
		}

		// initialize container buffer
		this.dataStoreBuffer = new DataStoreTxMemoryBuffer<>(this, this.ioService);
		final CatalogBootstrap catalogBootstrap = new CatalogBootstrap(CatalogState.WARMING_UP);
		this.state = new AtomicReference<>(CatalogState.WARMING_UP);
		this.readWriteSessionCount = new AtomicInteger(0);
		this.lastCommittedTransactionId = catalogBootstrap.getLastTransactionId();
		this.txPkSequence = sequenceService.getOrCreateSequence(getName(), SequenceType.TRANSACTION, this.lastCommittedTransactionId);
		this.cacheSupervisor = cacheSupervisor;
		this.entityCollections = new TransactionalMap<>(createHashMap(0), EntityCollection.class, Function.identity());
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(createHashMap(0), EntityCollection.class, Function.identity());
		this.entitySchemaIndex = new TransactionalMap<>(createHashMap(0));
		this.entityTypeSequence = sequenceService.getOrCreateSequence(
			catalogSchema.getName(), SequenceType.ENTITY_COLLECTION, 1
		);
		this.catalogIndex = new CatalogIndex(this);
		this.proxyFactory = ClassUtils.whenPresentOnClasspath(
			"one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator",
			() -> (ProxyFactory) Class.forName("io.evitadb.api.proxy.impl.ProxycianFactory")
				.getConstructor(ReflectionLookup.class)
				.newInstance(reflectionLookup)
		).orElse(UnsatisfiedDependencyFactory.INSTANCE);
	}

	public Catalog(
		@Nonnull String catalogName,
		@Nonnull Path catalogPath,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull TracingContext tracingContext
	) {
		this.tracingContext = tracingContext;
		this.ioService = ServiceLoader.load(CatalogPersistenceServiceFactory.class)
			.findFirst()
			.map(it -> it.load(catalogName, catalogPath, storageOptions))
			.orElseThrow(() -> new IllegalStateException("IO service is unexpectedly not available!"));
		final CatalogBootstrap catalogBootstrap = this.ioService.getCatalogBootstrap();
		this.state = new AtomicReference<>(catalogBootstrap.getCatalogState());
		// initialize container buffer
		this.dataStoreBuffer = new DataStoreTxMemoryBuffer<>(this, this.ioService);
		// initialize schema - still in constructor
		final CatalogSchema catalogSchema = CatalogSchemaStoragePart.deserializeWithCatalog(
			this,
			() -> ofNullable(this.ioService.getStoragePart(1, CatalogSchemaStoragePart.class))
				.map(CatalogSchemaStoragePart::catalogSchema)
				.orElseThrow(() -> new SchemaNotFoundException(catalogBootstrap.getCatalogHeader().getCatalogName()))
		);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(catalogSchema));
		this.catalogIndex = this.ioService.readCatalogIndex(this);

		this.readWriteSessionCount = new AtomicInteger(0);
		this.lastCommittedTransactionId = catalogBootstrap.getLastTransactionId();
		this.txPkSequence = sequenceService.getOrCreateSequence(getName(), SequenceType.TRANSACTION, this.lastCommittedTransactionId);
		this.cacheSupervisor = cacheSupervisor;

		final Collection<EntityCollectionHeader> entityCollectionHeaders = catalogBootstrap.getEntityTypeHeaders();
		final Map<String, EntityCollection> collections = createHashMap(entityCollectionHeaders.size());
		final Map<Integer, EntityCollection> collectionIndex = createHashMap(entityCollectionHeaders.size());
		for (EntityCollectionHeader entityCollectionHeader : entityCollectionHeaders) {
			final String entityType = entityCollectionHeader.getEntityType();
			final int entityTypePrimaryKey = entityCollectionHeader.getEntityTypePrimaryKey();
			final EntityCollection collection = new EntityCollection(
				this, entityTypePrimaryKey, entityType, ioService, cacheSupervisor, sequenceService, tracingContext
			);
			collections.put(entityType, collection);
			collectionIndex.put(MAX_POWER_OF_TWO, collection);
		}
		this.entityCollections = new TransactionalMap<>(collections, EntityCollection.class, Function.identity());
		this.entityTypeSequence = sequenceService.getOrCreateSequence(
			catalogName, SequenceType.ENTITY_COLLECTION, catalogBootstrap.getCatalogHeader().getLastEntityCollectionPrimaryKey()
		);
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(
			entityCollections.values()
				.stream()
				.collect(
					Collectors.toMap(
						EntityCollection::getEntityTypePrimaryKey,
						Function.identity()
					)
				),
			EntityCollection.class, Function.identity()
		);
		this.entitySchemaIndex = new TransactionalMap<>(
			entityCollections.values()
				.stream()
				.collect(
					Collectors.toMap(
						EntityCollection::getEntityType,
						EntityCollection::getSchema
					)
				)
		);
		this.proxyFactory = ClassUtils.whenPresentOnClasspath(
			"one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator",
			() -> (ProxyFactory) Class.forName("io.evitadb.api.proxy.impl.ProxycianFactory")
				.getConstructor(ReflectionLookup.class)
				.newInstance(reflectionLookup)
		).orElse(UnsatisfiedDependencyFactory.INSTANCE);
	}

	Catalog(
		@Nonnull CatalogState catalogState,
		@Nonnull CatalogIndex catalogIndex,
		@Nonnull CatalogPersistenceService ioService,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nonnull AtomicInteger readWriteSessionCount,
		@Nonnull AtomicLong txPkSequence,
		long lastCommittedTransactionId,
		@Nonnull AtomicInteger entityTypeSequence,
		@Nonnull Map<String, EntityCollection> entityCollections,
		@Nonnull ProxyFactory proxyFactory,
		@Nonnull TracingContext tracingContext
	) {
		this.tracingContext = tracingContext;
		this.state = new AtomicReference<>(catalogState);
		this.catalogIndex = catalogIndex;
		catalogIndex.updateReferencesTo(this);

		this.ioService = ioService;
		final CatalogSchema catalogSchema = CatalogSchemaStoragePart.deserializeWithCatalog(
			this,
			() -> ofNullable(this.ioService.getStoragePart(1, CatalogSchemaStoragePart.class))
				.map(CatalogSchemaStoragePart::catalogSchema)
				.orElseThrow(() -> new SchemaNotFoundException(this.ioService.getCatalogBootstrap().getCatalogHeader().getCatalogName()))
		);
		this.schema = new TransactionalReference<>(new CatalogSchemaDecorator(catalogSchema));

		this.cacheSupervisor = cacheSupervisor;
		this.readWriteSessionCount = readWriteSessionCount;
		this.txPkSequence = txPkSequence;
		this.lastCommittedTransactionId = lastCommittedTransactionId;
		this.dataStoreBuffer = new DataStoreTxMemoryBuffer<>(this, ioService);
		// we need to switch references working with catalog (inter index relations) to new catalog
		// the collections are not yet used anywhere - we're still safe here
		entityCollections.values().forEach(it -> it.updateReferenceToCatalog(this));
		this.entityTypeSequence = entityTypeSequence;
		this.entityCollections = new TransactionalMap<>(entityCollections, EntityCollection.class, Function.identity());
		this.entityCollectionsByPrimaryKey = new TransactionalMap<>(
			entityCollections.values()
				.stream()
				.collect(
					Collectors.toMap(
						EntityCollection::getEntityTypePrimaryKey,
						Function.identity()
					)
				),
			EntityCollection.class, Function.identity()
		);
		this.entitySchemaIndex = new TransactionalMap<>(
			entityCollections.values()
				.stream()
				.collect(
					Collectors.toMap(
						EntityCollection::getEntityType,
						EntityCollection::getSchema
					)
				)
		);
		this.proxyFactory = proxyFactory;
	}

	@Override
	@Nonnull
	public SealedCatalogSchema getSchema() {
		return schema.get();
	}

	@Nonnull
	@Override
	public CatalogSchemaContract updateSchema(@Nonnull EvitaSessionContract session, @Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		// internal schema is expected to be produced on the server side
		final CatalogSchema currentSchema = getInternalSchema();
		ModifyEntitySchemaMutation[] modifyEntitySchemaMutations = null;
		CatalogSchemaContract updatedSchema = currentSchema;
		for (CatalogSchemaMutation theMutation : schemaMutation) {
			// if the mutation implements entity schema mutation apply it on the appropriate schema
			if (theMutation instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation) {
				final String entityType = modifyEntitySchemaMutation.getEntityType();
				final EntityCollection entityCollection = getCollectionForEntityOrThrowException(entityType);
				entityCollection.updateSchema(updatedSchema, modifyEntitySchemaMutation.getSchemaMutations());
			} else if (theMutation instanceof RemoveEntitySchemaMutation removeEntitySchemaMutation) {
				final EntityCollection collectionToRemove = entityCollections.remove(removeEntitySchemaMutation.getName());
				if (!session.isTransactionOpen()) {
					new RemoveCollectionOperation(removeEntitySchemaMutation.getName())
						.execute("Catalog: " + getName(), -1L, ioService);
				}
				if (collectionToRemove != null) {
					if (session.isTransactionOpen()) {
						collectionToRemove.removeLayer();
					}
					updatedSchema = CatalogSchema._internalBuildWithUpdatedVersion(
						updatedSchema,
						getEntitySchemaAccessor()
					);
					entitySchemaRemoved(collectionToRemove.getEntityType());
				}
			} else if (theMutation instanceof CreateEntitySchemaMutation createEntitySchemaMutation) {
				this.ioService.verifyEntityType(
					this.entityCollections.values(),
					createEntitySchemaMutation.getName()
				);
				final EntityCollection newCollection = new EntityCollection(
					this, this.entityTypeSequence.incrementAndGet(), createEntitySchemaMutation.getName(),
					ioService, cacheSupervisor, sequenceService, tracingContext
				);
				this.entityCollectionsByPrimaryKey.put(newCollection.getEntityTypePrimaryKey(), newCollection);
				this.entityCollections.put(newCollection.getEntityType(), newCollection);
				updatedSchema = CatalogSchema._internalBuildWithUpdatedVersion(
					updatedSchema,
					getEntitySchemaAccessor()
				);
				entitySchemaUpdated(newCollection.getSchema());
			} else if (theMutation instanceof ModifyEntitySchemaNameMutation renameEntitySchemaMutation) {
				if (renameEntitySchemaMutation.isOverwriteTarget() && entityCollections.containsKey(renameEntitySchemaMutation.getNewName())) {
					replaceEntityCollectionInternal(session, renameEntitySchemaMutation);
				} else {
					renameEntityCollectionInternal(session, renameEntitySchemaMutation);
				}
				updatedSchema = CatalogSchema._internalBuildWithUpdatedVersion(
					updatedSchema,
					getEntitySchemaAccessor()
				);
			} else {
				final CatalogSchemaWithImpactOnEntitySchemas schemaWithImpactOnEntitySchemas;
				if (theMutation instanceof LocalCatalogSchemaMutation localCatalogSchemaMutation) {
					schemaWithImpactOnEntitySchemas = localCatalogSchemaMutation.mutate(updatedSchema, getEntitySchemaAccessor());
				} else {
					schemaWithImpactOnEntitySchemas = theMutation.mutate(updatedSchema);
				}
				Assert.isPremiseValid(
					schemaWithImpactOnEntitySchemas != null && schemaWithImpactOnEntitySchemas.updatedCatalogSchema() != null,
					"Catalog schema mutation is expected to produce CatalogSchema instance!"
				);
				updatedSchema = schemaWithImpactOnEntitySchemas.updatedCatalogSchema();
				modifyEntitySchemaMutations = modifyEntitySchemaMutations == null || ArrayUtils.isEmpty(schemaWithImpactOnEntitySchemas.entitySchemaMutations()) ?
					schemaWithImpactOnEntitySchemas.entitySchemaMutations() :
					ArrayUtils.mergeArrays(modifyEntitySchemaMutations, schemaWithImpactOnEntitySchemas.entitySchemaMutations());
			}
		}
		final CatalogSchemaContract nextSchema = updatedSchema;
		Assert.isPremiseValid(updatedSchema != null, "Catalog cannot be dropped by updating schema!");
		Assert.isPremiseValid(updatedSchema instanceof CatalogSchema, "Mutation is expected to produce CatalogSchema instance!");
		if (updatedSchema.getVersion() > currentSchema.getVersion()) {
			final CatalogSchema updatedInternalSchema = (CatalogSchema) updatedSchema;
			final CatalogSchemaDecorator originalSchemaBeforeExchange = this.schema.compareAndExchange(
				this.schema.get(),
				new CatalogSchemaDecorator(updatedInternalSchema)
			);
			Assert.isTrue(
				originalSchemaBeforeExchange.getVersion() == currentSchema.getVersion(),
				() -> new ConcurrentSchemaUpdateException(currentSchema, nextSchema)
			);
			this.dataStoreBuffer.update(new CatalogSchemaStoragePart(updatedInternalSchema));
		}
		// alter affected entity schemas
		if (modifyEntitySchemaMutations != null) {
			updateSchema(session, modifyEntitySchemaMutations);
		}
		return getSchema();
	}

	@Override
	@Nonnull
	public CatalogState getCatalogState() {
		return this.state.get();
	}

	@Override
	@Nonnull
	public String getName() {
		return schema.get().getName();
	}

	@Override
	public long getVersion() {
		return this.ioService.getCatalogBootstrap().getCatalogHeader().getVersion();
	}

	@Override
	public boolean supportsTransaction() {
		return state.get() == CatalogState.ALIVE;
	}

	@Override
	@Nonnull
	public Set<String> getEntityTypes() {
		return entityCollections.keySet();
	}

	@Override
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		final QueryPlan queryPlan;
		try (final QueryContext queryContext = createQueryContext(evitaRequest, session)) {
			queryPlan = QueryPlanner.planQuery(queryContext);
		}
		return tracingContext.executeWithinBlockIfParentContextAvailable(
			"query - " + queryPlan.getDescription(),
			(Supplier<T>) queryPlan::execute,
			queryPlan::getSpanAttributes
		);
	}

	@Nonnull
	@Override
	public EntityCollectionContract createCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		if (entityCollections.containsKey(entityType)) {
			return entityCollections.get(entityType);
		} else {
			updateSchema(
				session, new CreateEntitySchemaMutation(entityType)
			);
			return Objects.requireNonNull(entityCollections.get(entityType));
		}
	}

	@Override
	@Nonnull
	public Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType) {
		return ofNullable(entityCollections.get(entityType));
	}

	@Override
	@Nonnull
	public EntityCollection getCollectionForEntityOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(entityCollections.get(entityType))
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	@Override
	@Nonnull
	public EntityCollection getCollectionForEntityPrimaryKeyOrThrowException(int entityTypePrimaryKey) throws CollectionNotFoundException {
		return ofNullable(this.entityCollectionsByPrimaryKey.get(entityTypePrimaryKey))
			.orElseThrow(() -> new CollectionNotFoundException(entityTypePrimaryKey));
	}

	@Override
	@Nonnull
	public EntityCollection getOrCreateCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		return ofNullable(entityCollections.get(entityType))
			.orElseGet(() -> {
				if (!getSchema().getCatalogEvolutionMode().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES)) {
					throw new InvalidSchemaMutationException(
						"The entity collection `" + entityType + "` doesn't exist and would be automatically created," +
							" providing that catalog schema allows `" + CatalogEvolutionMode.ADDING_ENTITY_TYPES + "`" +
							" evolution mode."
					);
				}
				updateSchema(
					session, new CreateEntitySchemaMutation(entityType)
				);
				return Objects.requireNonNull(entityCollections.get(entityType));
			});
	}

	@Override
	public boolean deleteCollectionOfEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		final SealedCatalogSchema originalSchema = getSchema();
		final CatalogSchemaContract updatedSchema = updateSchema(
			session, new RemoveEntitySchemaMutation(entityType)
		);
		return updatedSchema.getVersion() > originalSchema.getVersion();
	}

	@Override
	public boolean renameCollectionOfEntity(@Nonnull String entityType, @Nonnull String newName, @Nonnull EvitaSessionContract session) {
		final SealedCatalogSchema originalSchema = getSchema();
		final CatalogSchemaContract updatedSchema = updateSchema(
			session, new ModifyEntitySchemaNameMutation(entityType, newName, false)
		);
		return updatedSchema.getVersion() > originalSchema.getVersion();
	}

	@Override
	public boolean replaceCollectionOfEntity(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith, @Nonnull EvitaSessionContract session) {
		final SealedCatalogSchema originalSchema = getSchema();
		final CatalogSchemaContract updatedSchema = updateSchema(
			session,
			new ModifyEntitySchemaNameMutation(entityTypeToBeReplacedWith, entityTypeToBeReplaced, true)
		);
		return updatedSchema.getVersion() > originalSchema.getVersion();
	}

	@Override
	public void delete() {
		ioService.delete();
	}

	@Nonnull
	@Override
	public CatalogContract replace(@Nonnull CatalogSchemaContract updatedSchema, @Nonnull CatalogContract catalogToBeReplaced) {
		try {
			this.ioService.prepare();

			this.entityCollections.values().forEach(EntityCollection::terminate);
			final CatalogPersistenceService newIoService = ioService.replaceWith(
				updatedSchema.getName(),
				updatedSchema.getNameVariants(),
				CatalogSchema._internalBuild(updatedSchema),
				getCatalogState() == CatalogState.WARMING_UP ? 0L : getNextTransactionId()
			);
			final Map<String, EntityCollection> newCollections = entityCollections
				.values()
				.stream()
				.collect(
					Collectors.toMap(
						EntityCollection::getEntityType,
						it -> new EntityCollection(
							this, it.getEntityTypePrimaryKey(), it.getEntityType(),
							newIoService, cacheSupervisor, sequenceService, tracingContext
						)
					)
				);

			final Catalog catalogAfterRename = new Catalog(
				getCatalogState(),
				catalogIndex,
				newIoService,
				cacheSupervisor,
				readWriteSessionCount,
				txPkSequence,
				id,
				entityTypeSequence,
				newCollections,
				proxyFactory,
				tracingContext
			);
			newCollections.values().forEach(it -> it.updateReferenceToCatalog(catalogAfterRename));
			return catalogAfterRename;
		} finally {
			this.ioService.release();
		}
	}

	@Nonnull
	@Override
	public Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return entitySchemaIndex;
	}

	@Override
	@Nonnull
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		return ofNullable(entityCollections.get(entityType))
			.map(EntityCollection::getSchema);
	}

	@Override
	public boolean goLive() {
		try {
			Assert.isTrue(
				goingLive.compareAndSet(false, true),
				"Concurrent call of `goLive` method is not supported!"
			);
			Assert.isTrue(state.get() == CatalogState.WARMING_UP, "Catalog has already alive state!");
			flush();
			return state.compareAndSet(CatalogState.WARMING_UP, CatalogState.ALIVE);
		} finally {
			goingLive.set(false);
		}
	}

	@Override
	public void terminate() {
		try {
			ioService.executeWriteSafely(() -> {
				final List<EntityCollectionHeader> entityHeaders;
				boolean changeOccurred = false;
				final boolean warmingUpState = getCatalogState() == CatalogState.WARMING_UP;
				entityHeaders = new ArrayList<>(this.entityCollections.size());
				for (EntityCollection entityCollection : entityCollections.values()) {
					// in warmup state try to persist all changes in volatile memory
					if (warmingUpState) {
						final long lastSeenVersion = entityCollection.getVersion();
						entityHeaders.add(entityCollection.flush());
						changeOccurred |= entityCollection.getVersion() != lastSeenVersion;
					}
					// in all states terminate collection operations
					entityCollection.terminate();
				}

				// if any change occurred (this may happen only in warm up state)
				if (changeOccurred) {
					// store catalog header
					this.ioService.storeHeader(
						getCatalogState(),
						this.lastCommittedTransactionId,
						this.entityTypeSequence.get(),
						entityHeaders
					);
				}
				// close all resources here, here we just hand all objects to GC
				entityCollections.clear();
				return null;
			});
		} finally {
			ioService.executeWriteSafely(() -> {
				ioService.close();
				return null;
			});
		}
	}

	/**
	 * Returns reference to the main catalog index that allows fast lookups for entities across all types.
	 */
	@Nonnull
	public CatalogIndex getCatalogIndex() {
		return catalogIndex;
	}

	/**
	 * Returns {@link EntitySchema} for passed `entityType` or throws {@link IllegalArgumentException} if schema for
	 * this type is not yet known.
	 */
	@Nullable
	public EntityIndex getEntityIndexIfExists(@Nonnull String entityType, @Nonnull EntityIndexKey indexKey) {
		final EntityCollection targetCollection = ofNullable(entityCollections.get(entityType))
			.orElseThrow(() -> new IllegalArgumentException("Entity collection of type " + entityType + " doesn't exist!"));
		return targetCollection.getIndexByKeyIfExists(indexKey);
	}

	/**
	 * Returns internally held {@link CatalogSchema}.
	 */
	public CatalogSchema getInternalSchema() {
		return schema.get().getDelegate();
	}

	/**
	 * Updates schema in the map index on schema change.
	 *
	 * @param entitySchema updated entity schema
	 */
	public void entitySchemaUpdated(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchemaIndex.put(entitySchema.getName(), entitySchema);
	}

	/**
	 * Removes the entity schema from the map index.
	 *
	 * @param entityType the type of the entity schema to be removed
	 */
	public void entitySchemaRemoved(@Nonnull String entityType) {
		this.entitySchemaIndex.remove(entityType);
	}

	@Override
	public DataSourceChanges<CatalogIndexKey, CatalogIndex> createLayer() {
		return new DataSourceChanges<>();
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.schema.removeLayer(transactionalLayer);
		this.entityCollections.removeLayer(transactionalLayer);
		this.catalogIndex.removeLayer(transactionalLayer);
		this.entityCollectionsByPrimaryKey.removeLayer(transactionalLayer);
		this.entitySchemaIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public Catalog createCopyWithMergedTransactionalMemory(@Nullable DataSourceChanges<CatalogIndexKey, CatalogIndex> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		final CatalogSchemaDecorator newSchema = transactionalLayer.getStateCopyWithCommittedChanges(schema, transaction).orElseThrow();
		final DataSourceChanges<CatalogIndexKey, CatalogIndex> transactionalChanges = transactionalLayer.getTransactionalMemoryLayer(this);

		final MapChanges<String, EntityCollection> collectionChanges = transactionalLayer.getTransactionalMemoryLayerIfExists(entityCollections);
		if (transaction != null && collectionChanges != null) {
			final Map<String, EntityCollection> originalCollectionContents = collectionChanges.getMapDelegate();
			final Set<String> removedCollections = new HashSet<>(collectionChanges.getRemovedKeys());
			final ObjectObjectIdentityHashMap<EntityCollection, String> originalCollections = new ObjectObjectIdentityHashMap<>(removedCollections.size());
			for (String removedKey : removedCollections) {
				originalCollections.put(collectionChanges.getMapDelegate().get(removedKey), removedKey);
			}
			for (Entry<String, EntityCollection> updatedKey : collectionChanges.getModifiedKeys().entrySet()) {
				final EntityCollection updatedCollection = updatedKey.getValue();
				final String removedEntityType = originalCollections.get(updatedCollection);
				final String newEntityType = updatedKey.getKey();
				if (removedEntityType != null) {
					transaction.register(
						new RenameCollectionOperation(
							removedEntityType,
							newEntityType,
							updatedCollection.getEntityTypePrimaryKey()
						)
					);
					removedCollections.remove(removedEntityType);
					ofNullable(originalCollectionContents.get(newEntityType)).ifPresent(it -> it.removeLayer(transactionalLayer));
				}
			}
			for (String removedKey : removedCollections) {
				transaction.register(new RemoveCollectionOperation(removedKey));
				originalCollectionContents.get(removedKey).removeLayer(transactionalLayer);
			}
		}

		final Map<String, EntityCollection> possiblyUpdatedCollections = transactionalLayer.getStateCopyWithCommittedChanges(entityCollections, transaction);
		final CatalogIndex possiblyUpdatedCatalogIndex = transactionalLayer.getStateCopyWithCommittedChanges(catalogIndex, transaction);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.entityCollectionsByPrimaryKey);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.entitySchemaIndex);

		if (transactionalChanges != null) {

			final CatalogSchemaStoragePart storedSchema = CatalogSchemaStoragePart.deserializeWithCatalog(
				this, () -> this.ioService.getStoragePart(1, CatalogSchemaStoragePart.class)
			);

			if (transaction != null) {
				if (newSchema.getVersion() != storedSchema.catalogSchema().getVersion()) {
					this.ioService.putStoragePart(transaction.getId(), new CatalogSchemaStoragePart(newSchema.getDelegate()));
				}

				transactionalChanges.getModifiedStoragePartsToPersist()
					.forEach(it -> transaction.register(new PutStoragePartOperation(it)));

				transactionalChanges.getRemovedStoragePartsToPersist()
					.forEach(it -> transaction.register(new RemoveStoragePartOperation(it)));
			}

			// when we register all storage parts for persisting we can now release transactional memory
			transactionalLayer.removeTransactionalMemoryLayer(this);

			return new Catalog(
				getCatalogState(),
				possiblyUpdatedCatalogIndex,
				ioService,
				cacheSupervisor,
				readWriteSessionCount,
				txPkSequence,
				id,
				entityTypeSequence,
				possiblyUpdatedCollections,
				proxyFactory,
				tracingContext
			);
		} else {
			if (possiblyUpdatedCatalogIndex != catalogIndex ||
				possiblyUpdatedCollections
					.entrySet()
					.stream()
					.anyMatch(it -> this.entityCollections.get(it.getKey()) != it.getValue())
			) {
				return new Catalog(
					getCatalogState(),
					possiblyUpdatedCatalogIndex,
					ioService,
					cacheSupervisor,
					readWriteSessionCount,
					txPkSequence,
					id,
					entityTypeSequence,
					possiblyUpdatedCollections,
					proxyFactory,
					tracingContext
				);
			} else {
				// no changes present we can return self
				return this;
			}
		}
	}

	/**
	 * Returns next unique transaction id for the catalog.
	 */
	long getNextTransactionId() {
		return txPkSequence.updateAndGet(operand -> {
			try {
				return Math.addExact(operand, 1);
			} catch (ArithmeticException ex) {
				log.warn("Transactional id overflew! Starting from 1 again.");
				return 1L;
			}
		});
	}

	/*
		PROTECTED METHODS
	 */

	/**
	 * Increases number of read and write sessions that are currently talking with this catalog.
	 *
	 * TOBEDONE JNO - these methods should be removed
	 */
	void increaseReadWriteSessionCount() {
		if (readWriteSessionCount.getAndIncrement() == 0) {
			ioService.prepare();
		}
	}

	/**
	 * Decreases number of read and write sessions that are currently talking with this catalog.
	 * When session count reaches zero - opened output buffers are released to free memory.
	 *
	 * TOBEDONE JNO - these methods should be removed
	 */
	void decreaseReadWriteSessionCount() {
		if (readWriteSessionCount.decrementAndGet() == 0) {
			ioService.release();
		}
	}

	/**
	 * This method writes all changed storage parts into the file offset index of {@link EntityCollection} and then stores
	 * {@link CatalogBootstrap} marking transactionId as committed.
	 */
	void flushTransaction(long transactionId, @Nonnull CommitUpdateInstructionSet commitInstructionSet) {
		boolean changeOccurred = false;
		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(this.entityCollections.size());
		for (EntityCollection entityCollection : entityCollections.values()) {
			final String entityType = entityCollection.getSchema().getName();
			final long lastSeenVersion = entityCollection.getVersion();
			final List<DeferredStorageOperation<?>> collectionUpdates = commitInstructionSet.getEntityCollectionUpdates(entityType);
			entityHeaders.add(entityCollection.flush(transactionId, collectionUpdates));
			changeOccurred |= entityCollection.getVersion() != lastSeenVersion;
		}

		final List<DeferredStorageOperation<?>> catalogUpdates = commitInstructionSet.getCatalogUpdates();
		if (!catalogUpdates.isEmpty()) {
			this.ioService.applyUpdates("catalog", transactionId, catalogUpdates);
			changeOccurred = true;
		}

		if (changeOccurred) {
			this.ioService.storeHeader(getCatalogState(), transactionId, this.entityTypeSequence.get(), entityHeaders);
			this.lastCommittedTransactionId = transactionId;
		}
	}

	/**
	 * Method allows to immediately flush all information held in memory to the persistent storage.
	 * This method might do nothing particular in transaction ({@link CatalogState#ALIVE}) mode.
	 * Method stores {@link EntityCollectionHeader} in case there were any changes in the file offset index executed
	 * in BULK / non-transactional mode.
	 */
	void flush() {
		// if we're going live start with TRUE (force flush), otherwise start with false
		boolean changeOccurred = goingLive.get();
		Assert.isPremiseValid(
			getCatalogState() == CatalogState.WARMING_UP,
			"Cannot flush catalog that is in transactional mode. Any changes could occur only in transactions!"
		);
		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(this.entityCollections.size());
		for (EntityCollection entityCollection : entityCollections.values()) {
			final long lastSeenVersion = entityCollection.getVersion();
			entityHeaders.add(entityCollection.flush());
			changeOccurred |= entityCollection.getVersion() != lastSeenVersion;
		}

		if (changeOccurred) {
			this.ioService.flushTrappedUpdates(this.dataStoreBuffer.exchangeBuffer());
			this.ioService.storeHeader(
				this.goingLive.get() ? CatalogState.ALIVE : getCatalogState(),
				this.lastCommittedTransactionId,
				this.entityTypeSequence.get(),
				entityHeaders
			);
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method creates {@link QueryContext} that is used for read operations.
	 */
	@Nonnull
	private QueryContext createQueryContext(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		return new QueryContext(
			this,
			null,
			new CatalogReadOnlyEntityStorageContainerAccessor(this),
			session, evitaRequest,
			evitaRequest.isQueryTelemetryRequested() ? new QueryTelemetry(QueryPhase.OVERALL) : null,
			Collections.emptyMap(),
			cacheSupervisor
		);
	}

	/**
	 * Renames existing entity collection in catalog.
	 */
	private void renameEntityCollectionInternal(EvitaSessionContract session, @Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaNameMutation) {
		final String currentName = modifyEntitySchemaNameMutation.getName();
		final String newName = modifyEntitySchemaNameMutation.getNewName();
		this.ioService.verifyEntityType(
			this.entityCollections.values(),
			modifyEntitySchemaNameMutation.getNewName()
		);

		final EntityCollection entityCollectionToBeRenamed = getCollectionForEntityOrThrowException(currentName);
		doReplaceEntityCollectionInternal(
			modifyEntitySchemaNameMutation, newName, currentName,
			entityCollectionToBeRenamed,
			session
		);
	}

	/**
	 * Replaces existing entity collection in catalog.
	 */
	private void replaceEntityCollectionInternal(EvitaSessionContract session, @Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaNameMutation) {
		final String currentName = modifyEntitySchemaNameMutation.getName();
		final String newName = modifyEntitySchemaNameMutation.getNewName();
		getCollectionForEntityOrThrowException(currentName);
		final EntityCollection entityCollectionToBeReplacedWith = getCollectionForEntityOrThrowException(currentName);

		doReplaceEntityCollectionInternal(
			modifyEntitySchemaNameMutation, newName, currentName,
			entityCollectionToBeReplacedWith,
			session
		);
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	private void doReplaceEntityCollectionInternal(
		@Nonnull ModifyEntitySchemaNameMutation modifyEntitySchemaName,
		@Nonnull String entityCollectionNameToBeReplaced,
		@Nonnull String entityCollectionNameToBeReplacedWith,
		@Nonnull EntityCollection entityCollectionToBeReplacedWith,
		@Nonnull EvitaSessionContract session
	) {
		entityCollectionToBeReplacedWith.updateSchema(getSchema(), modifyEntitySchemaName);
		this.entityCollections.remove(entityCollectionNameToBeReplacedWith);
		this.entityCollections.put(entityCollectionNameToBeReplaced, entityCollectionToBeReplacedWith);
		if (!session.isTransactionOpen()) {
			new RenameCollectionOperation(
				entityCollectionNameToBeReplacedWith,
				entityCollectionNameToBeReplaced,
				entityCollectionToBeReplacedWith.getEntityTypePrimaryKey()
			)
				.execute("Catalog: " + getName(), -1L, ioService);
			// reinitialize persistence storage
			entityCollectionToBeReplacedWith.flush();
		}
	}

	/**
	 * This implementation just manipulates with the set of EntityIndex in entity collection.
	 */
	private class CatalogIndexMaintainerImpl implements IndexMaintainer<CatalogIndexKey, CatalogIndex> {

		/**
		 * Returns entity index by its key. If such index doesn't exist, it is automatically created.
		 */
		@Nonnull
		@Override
		public CatalogIndex getOrCreateIndex(@Nonnull CatalogIndexKey entityIndexKey) {
			return Catalog.this.dataStoreBuffer.getOrCreateIndexForModification(
				entityIndexKey,
				eik -> Catalog.this.catalogIndex
			);
		}

		/**
		 * Returns existing index for passed `entityIndexKey` or returns null.
		 */
		@Nullable
		@Override
		public CatalogIndex getIndexIfExists(@Nonnull CatalogIndexKey entityIndexKey) {
			return Catalog.this.catalogIndex;
		}

		/**
		 * Removes entity index by its key. If such index doesn't exist, exception is thrown.
		 *
		 * @throws IllegalArgumentException when entity index doesn't exist
		 */
		@Override
		public void removeIndex(@Nonnull CatalogIndexKey entityIndexKey) {
			throw new EvitaInternalError("Global catalog index is not expected to be removed!");
		}

	}

	private class CatalogEntitySchemaAccessor implements EntitySchemaProvider {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return Catalog.this.getEntitySchemaIndex().values();
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return Catalog.this.getEntitySchema(entityType).map(EntitySchemaContract.class::cast);
		}
	}
}
