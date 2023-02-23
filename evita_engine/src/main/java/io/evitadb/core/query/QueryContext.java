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

package io.evitadb.core.query;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetchRequirements;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.DataChunk;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.EntityStoragePartAccessor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Query context aggregates references to all the instances that are required to process the {@link EvitaRequest}.
 * The object serves as single "go to" object while preparing or executing {@link QueryPlan}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class QueryContext {
	private static final EntityIndexKey GLOBAL_INDEX_KEY = new EntityIndexKey(EntityIndexType.GLOBAL);

	/**
	 * Contains reference to the parent context of this one. The reference is not NULL only for sub-queries.
	 */
	@Nullable private final QueryContext parentContext;
	/**
	 * Contains reference to the catalog that is targeted by {@link #evitaRequest}.
	 */
	@Nonnull private final Catalog catalog;
	/**
	 * Contains reference to the entity collection that is targeted by {@link #evitaRequest}.
	 */
	@Nullable private final EntityCollection entityCollection;
	/**
	 * Contains cached value from {@link EntityCollection#getInternalSchema()} name.
	 */
	@Nullable private final String entityType;
	/**
	 * Allows accessing entity {@link StoragePart} directly.
	 */
	@Nonnull private final EntityStoragePartAccessor entityStorageContainerAccessor;
	/**
	 * Contains reference to the enveloping {@link EvitaSessionContract} within which the {@link #evitaRequest} is executed.
	 */
	@Getter
	@Nonnull private final EvitaSessionContract evitaSession;
	/**
	 * Contains input in {@link EvitaRequest}.
	 */
	@Getter
	@Nonnull private final EvitaRequest evitaRequest;
	/**
	 * Contains {@link QueryTelemetry} information that measures the costs of each {@link #evitaRequest} processing
	 * phases.
	 */
	@Nonnull private final Deque<QueryTelemetry> telemetryStack;
	/**
	 * Collection of search indexes prepared to handle queries.
	 */
	@Nonnull private final Map<IndexKey, Index<?>> indexes;
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified
	 * by its {@link Formula#computeHash(LongHashFunction)} method and when the supervisor identifies that certain
	 * formula is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	@Getter
	@Nonnull private final CacheSupervisor cacheSupervisor;
	/**
	 * Contains list of prefetched entities if they were considered worthwhile to prefetch -
	 * see {@link SelectionFormula} for more information.
	 */
	@Getter
	private List<EntityDecorator> prefetchedEntities;
	/**
	 * This flag signalizes that the entity prefetching is not possible within this context. I.e. it means that
	 * the {@link #prefetchedEntities} will always be NULL. Prefetching is not possible for nested queries since
	 * the prefetched entities wouldn't ever be used in the output and it would also force us to eagerly evaluate
	 * the created formula.
	 */
	@Getter
	private final boolean prefetchPossible;
	/**
	 * Contains sequence of already assigned virtual entity primary keys.
	 * If set to zero - no virtual entity primary key was assigned, if greater than zero it represents the last assigned
	 * virtual entity primary key.
	 */
	private int entityReferencePkSequence;
	/**
	 * Contains index of virtual entity primary keys to {@link EntityReference} that was used to generate them.
	 */
	private IntObjectHashMap<EntityReferenceContract<EntityReference>> entityReferencePkIndex;
	/**
	 * Contains index of {@link EntityReference} to their virtual primary keys. This index is exact opposite to
	 * {@link #entityReferencePkIndex}.
	 */
	private Map<EntityReferenceContract<EntityReference>, Integer> entityReferencePkReverseIndex;
	/**
	 * Contains index of primary keys to their respective prefetched {@link SealedEntity} objects.
	 */
	private IntObjectMap<EntityDecorator> entityPkIndex;
	/**
	 * Contains index of {@link EntityReference} identifiers to prefetched {@link SealedEntity} objects.
	 */
	private Map<EntityReferenceContract<EntityReference>, EntityDecorator> entityReferenceIndex;
	/**
	 * This field is used only for debugging purposes when we need to compute results for different variants of
	 * query plan. In case random function is used in the evaluation process, the variants would ultimately produce
	 * different results. Therefore, we "freeze" the {@link Random} using Java serialization process and restore it
	 * along with its internal state for each query plan so that the random row, stays the same for all evaluations.
	 */
	private byte[] frozenRandom;
	/**
	 * Internal cache currently server sor caching the computed formulas of nested queries.
	 * @see #computeOnlyOnce(FilterConstraint, Supplier) for more details
	 */
	private Map<Constraint<?>, Formula> internalCache;

	public <S extends IndexKey, T extends Index<S>> QueryContext(
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EntityStoragePartAccessor entityStorageContainerAccessor,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(
			null, catalog, entityCollection, entityStorageContainerAccessor,
			evitaSession, evitaRequest,
			telemetry, indexes, cacheSupervisor
		);
	}

	public <S extends IndexKey, T extends Index<S>> QueryContext(
		@Nullable QueryContext parentQueryContext,
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EntityStoragePartAccessor entityStorageContainerAccessor,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this.parentContext = parentQueryContext;
		this.prefetchPossible = parentQueryContext == null;
		this.catalog = catalog;
		this.entityCollection = entityCollection;
		this.entityType = ofNullable(entityCollection)
			.map(EntityCollection::getSchema)
			.map(EntitySchemaContract::getName)
			.orElse(null);
		this.entityStorageContainerAccessor = entityStorageContainerAccessor;
		this.evitaSession = evitaSession;
		this.evitaRequest = evitaRequest;
		this.telemetryStack = new LinkedList<>();
		ofNullable(telemetry).ifPresent(this.telemetryStack::push);
		//noinspection unchecked
		this.indexes = (Map<IndexKey, Index<?>>) indexes;
		this.cacheSupervisor = cacheSupervisor;
	}

	/**
	 * Returns true if the input {@link #evitaRequest} contains specification of the entity collection.
	 */
	public boolean isEntityTypeKnown() {
		return entityType != null;
	}

	/**
	 * Executes code in `lambda` function with a {@link Random} object that will generate the same sequences for each
	 * call of {@link #getRandom()} method.
	 *
	 * @see #frozenRandom for more information
	 */
	public void doWithFrozenRandom(@Nonnull Runnable lambda) {
		try {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream(200);
			try (var os = new ObjectOutputStream(bos)) {
				os.writeObject(new Random());
				this.frozenRandom = bos.toByteArray();
			} catch (IOException e) {
				throw new EvitaInternalError("Unexpected error during debug mode evaluation!", e);
			}
			lambda.run();
		} finally {
			this.frozenRandom = null;
		}
	}

	/**
	 * Returns random object to generate sequences from.
	 *
	 * @see #frozenRandom for more information
	 */
	@Nonnull
	public Random getRandom() {
		return ofNullable(frozenRandom)
			.map(it -> {
				try (var is = new ObjectInputStream(new ByteArrayInputStream(it))) {
					return (Random) is.readObject();
				} catch (IOException | ClassNotFoundException e) {
					throw new EvitaInternalError("Unexpected error during debug mode evaluation!", e);
				}
			})
			.orElseGet(ThreadLocalRandom::current);
	}

	/**
	 * Method will return full entity object for passed `entityPrimaryKey`. The input primary key may represent the
	 * real {@link EntityContract#getPrimaryKey()} or it may represent key masked by {@link #translateEntityReference(EntityReferenceContract[])}
	 * method.
	 */
	@Nullable
	public List<SealedEntity> fetchEntities(int... entityPrimaryKey) {
		if (ArrayUtils.isEmpty(entityPrimaryKey)) {
			return Collections.emptyList();
		}

		// are the reference bodies required?
		final Map<String, RequirementContext> requirementTuples = evitaRequest.getReferenceEntityFetch();

		// new predicates are richer that previous ones - we need to fetch additional data and create new entity
		final ReferenceFetcher entityFetcher = requirementTuples.isEmpty() ?
			ReferenceFetcher.NO_IMPLEMENTATION :
			new ReferencedEntityFetcher(
				requirementTuples,
				this
			);

		if (this.prefetchedEntities == null) {
			final EntityCollection entityCollection = getEntityCollectionOrThrowException(entityType, "fetch entity");
			return entityCollection.getEntities(entityPrimaryKey, evitaRequest, evitaSession, entityFetcher);
		} else {
			return takeAdvantageOfPrefetchedEntities(
				entityPrimaryKey,
				entityType,
				(entityCollection, entityPrimaryKeys, requestToUse) ->
					entityCollection.getEntities(entityPrimaryKeys, evitaRequest, evitaSession),
				(entityCollection, prefetchedEntities, requestToUse) ->
					entityFetcher.initReferenceIndex(prefetchedEntities, entityCollection)
						.stream()
						.map(it -> entityCollection.enrichEntity(it, requestToUse, entityFetcher))
						.map(it -> entityCollection.limitEntity(it, requestToUse, evitaSession))
						.toList()
			);
		}
	}

	/**
	 * Method will return full entity object for passed `entityPrimaryKey`. The input primary key may represent the
	 * real {@link EntityContract#getPrimaryKey()} or it may represent key masked by {@link #translateEntityReference(EntityReferenceContract[])}
	 * method.
	 */
	@Nullable
	public List<BinaryEntity> fetchBinaryEntities(int... entityPrimaryKey) {
		if (this.prefetchedEntities == null) {
			final EntityCollectionContract entityCollection = getEntityCollectionOrThrowException(entityType, "fetch entity");
			return entityCollection.getBinaryEntities(entityPrimaryKey, evitaRequest, evitaSession);
		} else {
			// we need to reread the contents of the prefetched entity in binary form
			return takeAdvantageOfPrefetchedEntities(
				entityPrimaryKey,
				entityType,
				(entityCollection, entityPrimaryKeys, requestToUse) ->
					entityCollection.getBinaryEntities(entityPrimaryKeys, evitaRequest, evitaSession),
				(entityCollection, prefetchedEntities, requestToUse) -> entityCollection.getBinaryEntities(
					prefetchedEntities.stream()
						.mapToInt(EntityDecorator::getPrimaryKey)
						.toArray(),
					evitaRequest, evitaSession
				)
			);
		}
	}

	/**
	 * Method loads entity contents by specifying its type and primary key. Fetching logic respect language from
	 * the original {@link EvitaRequest}
	 */
	@Nonnull
	public Optional<SealedEntity> fetchEntity(int entityPrimaryKey, @Nonnull EntityFetchRequirements requirements) {
		return fetchEntity(entityType, entityPrimaryKey, requirements);
	}

	/**
	 * Method loads requested entity contents by specifying its primary key.
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(int[] entityPrimaryKeys, @Nonnull EntityFetchRequirements requirements) {
		return fetchEntities(entityType, entityPrimaryKeys, requirements);
	}

	/**
	 * Method loads entity contents by specifying its type and primary key. Fetching logic respect language from
	 * the original {@link EvitaRequest}
	 */
	@Nonnull
	public Optional<SealedEntity> fetchEntity(@Nullable String entityType, int entityPrimaryKey, @Nonnull EntityFetchRequirements requirements) {
		final EntityCollection targetCollection = getEntityCollectionOrThrowException(entityType, "fetch entity");
		final EvitaRequest fetchRequest = fabricateFetchRequest(entityType, requirements);
		return targetCollection.getEntity(entityPrimaryKey, fetchRequest, evitaSession);
	}

	/**
	 * Method loads requested entity contents by specifying its primary key.
	 */
	@Nonnull
	public List<SealedEntity> fetchEntities(@Nullable String entityType, @Nonnull int[] entityPrimaryKeys, @Nonnull EntityFetchRequirements requirements) {
		final EntityCollection entityCollection = getEntityCollectionOrThrowException(entityType, "fetch entities");
		final EvitaRequest fetchRequest = fabricateFetchRequest(entityType, requirements);
		return entityCollection.getEntities(entityPrimaryKeys, fetchRequest, evitaSession);
	}

	/**
	 * Returns bitmap with newly generated virtual primary keys using masking function
	 * {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)}.
	 *
	 * @see #getOrRegisterEntityReferenceMaskId(EntityReferenceContract) for more information
	 */
	@Nonnull
	@SafeVarargs
	public final Bitmap translateEntityReference(@Nonnull EntityReferenceContract<EntityReference>... entityReferences) {
		if (this.entityReferencePkReverseIndex == null) {
			this.entityReferencePkReverseIndex = CollectionUtils.createHashMap(entityReferences.length);
			this.entityReferencePkIndex = new IntObjectHashMap<>(entityReferences.length);
		}
		return new BaseBitmap(
			Arrays.stream(entityReferences)
				.mapToInt(this::getOrRegisterEntityReferenceMaskId)
				.toArray()
		);
	}

	/**
	 * Returns virtual id assigned by {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)} or real primary key
	 * from {@link EntityContract#getPrimaryKey()}.
	 */
	public int translateEntity(@Nonnull EntityContract entity) {
		final int primaryKey = Objects.requireNonNull(entity.getPrimaryKey());
		if (this.entityReferencePkSequence > 0) {
			return Objects.requireNonNull(
				this.entityReferencePkReverseIndex.get(
					new EntityReference(entity.getType(), primaryKey)
				)
			);
		} else {
			return primaryKey;
		}
	}

	/**
	 * Method returns requested {@link EntityReference} by specifying its primary key (either virtual or real).
	 */
	@Nonnull
	public EntityReference translateToEntityReference(int primaryKey) {
		if (this.entityReferencePkSequence > 0) {
			return ofNullable(this.entityReferencePkIndex.get(primaryKey))
				.map(EntityReference::new)
				.orElseGet(() -> new EntityReference(getSchema().getName(), primaryKey));
		} else {
			return new EntityReference(getSchema().getName(), primaryKey);
		}
	}

	/**
	 * Method loads requested entity contents by specifying its primary key (either virtual or real).
	 */
	@Nullable
	public SealedEntity translateToEntity(int primaryKey) {
		if (this.entityReferencePkSequence > 0) {
			return getPrefetchedEntityByMaskedPrimaryKey(primaryKey);
		} else {
			return getPrefetchedEntityByPrimaryKey(primaryKey);
		}
	}

	/**
	 * Method will prefetch all entities mentioned in `entitiesToPrefetch` and loads them with the scope of `requirements`.
	 * The entities will reveal only the scope to the `requirements` - no less, no more data.
	 */
	public void prefetchEntities(@Nonnull Bitmap entitiesToPrefetch, @Nonnull EntityFetchRequirements requirements) {
		if (this.entityReferencePkSequence > 0) {
			prefetchEntities(
				Arrays.stream(entitiesToPrefetch.getArray())
					.mapToObj(it -> entityReferencePkIndex.get(it))
					.toArray(EntityReferenceContract[]::new),
				requirements
			);
		} else {
			final EntityCollection entityCollection = getEntityCollectionOrThrowException(entityType, "fetch entities");
			final EvitaRequest fetchRequest = fabricateFetchRequest(entityType, requirements);
			this.prefetchedEntities = Arrays.stream(entitiesToPrefetch.getArray())
				.mapToObj(it -> entityCollection.getEntityDecorator(it, fetchRequest, evitaSession))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
		}
	}

	/**
	 * Method will prefetch all entities mentioned in `entitiesToPrefetch` and loads them with the scope of `requirements`.
	 * The entities will reveal only the scope to the `requirements` - no less, no more data.
	 */
	public void prefetchEntities(@Nonnull EntityReferenceContract<?>[] entitiesToPrefetch, @Nonnull EntityFetchRequirements requirements) {
		if (entitiesToPrefetch.length != 0) {
			if (this.prefetchedEntities == null) {
				this.prefetchedEntities = new ArrayList<>(entitiesToPrefetch.length);
			}
			if (entitiesToPrefetch.length == 1) {
				final String entityType = entitiesToPrefetch[0].getType();
				final EntityCollection targetCollection = getEntityCollectionOrThrowException(entityType, "fetch entity");
				final EvitaRequest fetchRequest = fabricateFetchRequest(entityType, requirements);
				final int pk = entitiesToPrefetch[0].getPrimaryKey();
				targetCollection.getEntityDecorator(pk, fetchRequest, evitaSession)
					.ifPresent(it -> this.prefetchedEntities.add(it));
			} else {
				final Map<String, CompositeIntArray> entitiesByType = CollectionUtils.createHashMap(16);
				for (EntityReferenceContract<?> ref : entitiesToPrefetch) {
					final CompositeIntArray pks = entitiesByType.computeIfAbsent(ref.getType(), eType -> new CompositeIntArray());
					pks.add(ref.getPrimaryKey());
				}
				entitiesByType
					.entrySet()
					.stream()
					.flatMap(it -> {
						final String entityType = it.getKey();
						final EvitaRequest fetchRequest = fabricateFetchRequest(entityType, requirements);
						final EntityCollection targetCollection = getEntityCollectionOrThrowException(entityType, "fetch entity");
						return Arrays.stream(it.getValue().toArray())
								.mapToObj(pk -> targetCollection.getEntityDecorator(pk, fetchRequest, evitaSession))
								.filter(Optional::isPresent)
								.map(Optional::get);
					})
					.forEach(it -> this.prefetchedEntities.add(it));
			}
		}
	}

	/**
	 * Returns {@link EntityIndex} of external entity type by its key and entity type.
	 */
	@Nullable
	public EntityIndex getIndex(@Nonnull String entityType, @Nonnull EntityIndexKey entityIndexKey) {
		return getEntityCollectionOrThrowException(entityType, "access entity index")
			.getIndexByKeyIfExists(entityIndexKey);
	}

	/**
	 * Returns {@link EntityIndex} by its key.
	 */
	@Nullable
	public <S extends IndexKey, T extends Index<S>> T getIndex(@Nonnull S entityIndexKey) {
		if (entityIndexKey instanceof CatalogIndexKey) {
			//noinspection unchecked
			return (T) catalog.getCatalogIndex();
		} else {
			//noinspection unchecked
			return (T) indexes.get(entityIndexKey);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase) {
		if (!telemetryStack.isEmpty()) {
			telemetryStack.push(
				telemetryStack.peek().addStep(phase)
			);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull String message) {
		if (!telemetryStack.isEmpty()) {
			telemetryStack.push(
				telemetryStack.peek().addStep(phase, message)
			);
		}
	}

	/**
	 * Returns current step of the evaluation.
	 */
	@Nullable
	public QueryTelemetry getCurrentStep() {
		if (!telemetryStack.isEmpty()) {
			return telemetryStack.peek();
		}
		return null;
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep() {
		if (!telemetryStack.isEmpty()) {
			telemetryStack.pop().finish();
		}
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep(@Nonnull String message) {
		if (!telemetryStack.isEmpty()) {
			telemetryStack.pop().finish(message);
		}
	}

	/**
	 * Returns finalized {@link QueryTelemetry} or throws an exception.
	 */
	@Nonnull
	public QueryTelemetry finalizeAndGetTelemetry() {
		Assert.isPremiseValid(!telemetryStack.isEmpty(), "The telemetry has been already retrieved!");

		// there may be some steps still open at the time extra results is fabricated
		QueryTelemetry rootStep;
		do {
			rootStep = telemetryStack.pop();
			rootStep.finish();
		} while (!telemetryStack.isEmpty());

		return rootStep;
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getFilterBy()}.
	 */
	@Nullable
	public FilterConstraint getFilterBy() {
		return evitaRequest.getQuery().getFilterBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getOrderBy()} ()}.
	 */
	@Nullable
	public OrderConstraint getOrderBy() {
		return evitaRequest.getQuery().getOrderBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getRequire()} ()}.
	 */
	@Nullable
	public RequireConstraint getRequire() {
		return evitaRequest.getQuery().getRequire();
	}

	/**
	 * Returns language specified in {@link EvitaRequest}. Language is valid for entire query.
	 */
	@Nullable
	public Locale getLocale() {
		return evitaRequest.getLocale();
	}

	/**
	 * Returns query price mode specified in {@link EvitaRequest}. Query price mode is valid for entire query.
	 */
	@Nonnull
	public QueryPriceMode getQueryPriceMode() {
		return evitaRequest.getQueryPriceMode();
	}

	/**
	 * Returns schema of the catalog.
	 */
	@Nonnull
	public SealedCatalogSchema getCatalogSchema() {
		return catalog.getSchema();
	}

	/**
	 * Returns entity schema.
	 */
	@Nonnull
	public EntitySchemaContract getSchema() {
		return getEntityCollectionOrThrowException(entityType, "access entity schema").getSchema();
	}

	/**
	 * Returns entity schema by its type.
	 */
	@Nonnull
	public EntitySchemaContract getSchema(@Nonnull String entityType) {
		return getEntityCollectionOrThrowException(entityType, "access entity schema").getSchema();
	}

	/**
	 * Returns true if passed {@link DebugMode} is enabled in the query.
	 * Accessor method cache the found result so that consecutive calls of this method are pretty fast.
	 */
	public boolean isDebugModeEnabled(@Nonnull DebugMode debugMode) {
		return evitaRequest.isDebugModeEnabled(debugMode);
	}

	/**
	 * Returns global {@link GlobalEntityIndex} of the collection if the target entity collection is known.
	 */
	@Nullable
	public GlobalEntityIndex getGlobalEntityIndexIfExits() {
		return getIndex(GLOBAL_INDEX_KEY);
	}

	/**
	 * Returns global {@link GlobalEntityIndex} of the collection or throws an exception.
	 */
	@Nonnull
	public GlobalEntityIndex getGlobalEntityIndex() {
		return ofNullable(getGlobalEntityIndexIfExits())
			.map(GlobalEntityIndex.class::cast)
			.orElseThrow(() -> new EvitaInternalError("Global index of entity unexpectedly not found!"));
	}

	/**
	 * Returns {@link EntityIndex} by its key and entity type.
	 */
	@Nonnull
	public GlobalEntityIndex getGlobalEntityIndex(@Nonnull String entityType) {
		return ofNullable(getIndex(entityType, GLOBAL_INDEX_KEY))
			.map(GlobalEntityIndex.class::cast)
			.orElseThrow(() -> new EvitaInternalError("Global index of entity " + entityType + " unexpectedly not found!"));
	}

	/**
	 * Analyzes the input formula for cacheable / cached formulas and replaces them with appropriate counterparts (only
	 * if cache is enabled).
	 */
	@Nonnull
	public Formula analyse(@Nonnull Formula formula) {
		return ofNullable(evitaRequest.getEntityType())
			.map(it -> cacheSupervisor.analyse(evitaSession, evitaSession.getCatalogName(), it, formula))
			.orElse(formula);
	}

	/**
	 * Analyzes the input formula for cacheable / cached formulas and replaces them with appropriate counterparts (only
	 * if cache is enabled).
	 */
	@Nonnull
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull T computer) {
		return ofNullable(evitaRequest.getEntityType())
			.map(it -> cacheSupervisor.analyse(evitaSession, evitaSession.getCatalogName(), it, computer))
			.orElse(computer);
	}

	/**
	 * Analyzes the input formula for cacheable / cached formulas and replaces them with appropriate counterparts (only
	 * if cache is enabled).
	 */
	@Nonnull
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull String entityType, @Nonnull T computer) {
		return cacheSupervisor.analyse(evitaSession, evitaSession.getCatalogName(), entityType, computer);
	}

	/**
	 * Creates slice of entity primary keys that respect filtering query, specified sorting and is sliced according
	 * to requested offset and limit.
	 */
	@Nonnull
	public DataChunk<Integer> createDataChunk(int totalRecordCount, @Nonnull Formula filteringFormula, @Nonnull Sorter sorter) {
		final int firstRecordOffset = evitaRequest.getFirstRecordOffset(totalRecordCount);
		return evitaRequest.createDataChunk(
			totalRecordCount,
			Arrays.stream(
					sorter.sortAndSlice(
						this, filteringFormula,
						firstRecordOffset, firstRecordOffset + evitaRequest.getLimit()
					)
				)
				.boxed()
				.collect(Collectors.toList())
		);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getEntityStoragePart(String, int, EntityExistence)}.
	 */
	@Nonnull
	public EntityBodyStoragePart getEntityStorageContainer(@Nonnull String entityType, int entityPrimaryKey, EntityExistence expects) {
		return entityStorageContainerAccessor.getEntityStoragePart(entityType, entityPrimaryKey, expects);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getAttributeStoragePart(String, int)}.
	 */
	@Nonnull
	public AttributesStoragePart getAttributeStorageContainer(@Nonnull String entityType, int entityPrimaryKey) {
		return entityStorageContainerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getAttributeStoragePart(String, int, Locale)}.
	 */
	@Nonnull
	public AttributesStoragePart getAttributeStorageContainer(@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale) {
		return entityStorageContainerAccessor.getAttributeStoragePart(entityType, entityPrimaryKey, locale);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getAssociatedDataStoragePart(String, int, AssociatedDataKey)}.
	 */
	@Nonnull
	public AssociatedDataStoragePart getAssociatedDataStorageContainer(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		return entityStorageContainerAccessor.getAssociatedDataStoragePart(entityType, entityPrimaryKey, key);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getReferencesStoragePart(String, int)}.
	 */
	@Nonnull
	public ReferencesStoragePart getReferencesStorageContainer(@Nonnull String entityType, int entityPrimaryKey) {
		return entityStorageContainerAccessor.getReferencesStoragePart(entityType, entityPrimaryKey);
	}

	/**
	 * Shorthand for {@link EntityStoragePartAccessor#getPriceStoragePart(String, int)}.
	 */
	@Nonnull
	public PricesStoragePart getPriceStorageContainer(@Nonnull String entityType, int entityPrimaryKey) {
		return entityStorageContainerAccessor.getPriceStoragePart(entityType, entityPrimaryKey);
	}

	/**
	 * Returns true if session is switched to binary format output.
	 *
	 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
	 */
	public boolean isRequiresBinaryForm() {
		return evitaSession.isBinaryFormat();
	}

	/**
	 * Method allows to enrich / limit the referenced entity according to passed evita request.
	 */
	public SealedEntity enrichOrLimitReferencedEntity(
		@Nonnull SealedEntity sealedEntity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		final EntityCollection theCollection = getEntityCollectionOrThrowException(sealedEntity.getType(), "enriching reference");
		return theCollection.limitEntity(
			theCollection.enrichEntity(
				sealedEntity,
				evitaRequest,
				referenceFetcher
			),
			evitaRequest,
			evitaSession
		);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method retrieves already assigned masking id for the {@link EntityReference} or creates brand new. This virtual
	 * id is necessary because our filtering logic works with {@link Bitmap} objects that contains plain integers. In
	 * situation when no target entity collection is specified and filters targeting global attributes retrieves
	 * entities from various collections - their ids may overlap, and we need to keep them separated during computation.
	 * That's why we use such virtual ids during entire filtering and sorting process.
	 */
	private int getOrRegisterEntityReferenceMaskId(@Nonnull EntityReferenceContract<EntityReference> entityReference) {
		if (isEntityTypeKnown()) {
			// it the entity type is passed in the query, we don't need to mask anything - all entities will share
			// same primary key sequence
			this.entityReferencePkIndex.put(entityReference.getPrimaryKey(), entityReference);
			return entityReference.getPrimaryKey();
		} else {
			// otherwise we need to produce temporary id, that will mask entities from multiple collections that
			// may share same ids but represent different entities
			return this.entityReferencePkReverseIndex.computeIfAbsent(
				entityReference,
				ref -> {
					final int newEntityReferenceId = ++this.entityReferencePkSequence;
					this.entityReferencePkIndex.put(newEntityReferenceId, entityReference);
					return newEntityReferenceId;
				}
			);
		}
	}

	/**
	 * Returns appropriate prefetched {@link SealedEntity} by real primary key from {@link EntityContract#getPrimaryKey()}.
	 */
	@Nullable
	private EntityDecorator getPrefetchedEntityByPrimaryKey(int entityPrimaryKey) {
		this.entityPkIndex = ofNullable(this.entityPkIndex)
			.orElseGet(() -> {
				final IntObjectMap<EntityDecorator> result = new IntObjectHashMap<>(this.prefetchedEntities.size());
				for (EntityDecorator prefetchedEntity : this.prefetchedEntities) {
					result.put(Objects.requireNonNull(prefetchedEntity.getPrimaryKey()), prefetchedEntity);
				}
				return result;
			});
		return this.entityPkIndex.get(entityPrimaryKey);
	}

	/**
	 * Returns appropriate prefetched {@link SealedEntity} by virtual primary key assigned by
	 * {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)} method.
	 */
	@Nullable
	private EntityDecorator getPrefetchedEntityByMaskedPrimaryKey(int entityPrimaryKey) {
		this.entityReferenceIndex = ofNullable(this.entityReferenceIndex)
			.orElseGet(() ->
				this.prefetchedEntities
					.stream()
					.collect(
						Collectors.toMap(
							it -> new EntityReference(it.getType(), Objects.requireNonNull(it.getPrimaryKey())),
							Function.identity()
						)
					)
			);
		final EntityReferenceContract<EntityReference> entityReference = this.entityReferencePkIndex.get(entityPrimaryKey);
		return entityReference == null ? null : this.entityReferenceIndex.get(entityReference);
	}

	/**
	 * Method retrieves already prefetched entities and uses them for response output by enriching them of additional
	 * data that has been requested but not required for filtering or sorting operations.
	 */
	@Nonnull
	private <T extends EntityClassifier> List<T> takeAdvantageOfPrefetchedEntities(
		@Nonnull int[] inputPrimaryKeys,
		@Nullable String entityType,
		@Nonnull TriFunction<EntityCollection, int[], EvitaRequest, List<T>> fetcher,
		@Nonnull TriFunction<EntityCollection, List<EntityDecorator>, EvitaRequest, List<T>> collector
	) {
		// initialize variables that allow caching of last resolved objects
		// there is high probability that the locale will stay the same for entire result set
		Locale lastImplicitLocale = null;
		String lastEntityType = null;

		final AtomicReference<EntityCollection> entityCollection = new AtomicReference<>();
		final AtomicReference<EvitaRequest> requestToUse = new AtomicReference<>();
		final AtomicInteger primaryKeyPeek = new AtomicInteger();
		final int[] primaryKeysToFetch = new int[inputPrimaryKeys.length];
		final AtomicInteger prefetchedEntitiesPeek = new AtomicInteger();
		final EntityDecorator[] prefetchedEntities = new EntityDecorator[inputPrimaryKeys.length];
		final Map<Integer, T> index = CollectionUtils.createHashMap(inputPrimaryKeys.length);
		final AtomicReference<Map<EntityReference, Integer>> remappingIndex = new AtomicReference<>();

		final Runnable dataCollector = () -> {
			// convert collected data so far
			if (primaryKeyPeek.get() > 0) {
				fetcher.apply(entityCollection.get(), primaryKeyPeek.get() < inputPrimaryKeys.length ? Arrays.copyOfRange(primaryKeysToFetch, 0, primaryKeyPeek.get()) : primaryKeysToFetch, requestToUse.get())
					.forEach(it -> index.put(it.getPrimaryKey(), it));
				primaryKeyPeek.set(0);
			}
			if (prefetchedEntitiesPeek.get() > 0) {
				final List<EntityDecorator> collectedDecorators = prefetchedEntitiesPeek.get() < inputPrimaryKeys.length ?
					ArrayUtils.asList(prefetchedEntities, 0, prefetchedEntitiesPeek.get()) : Arrays.asList(prefetchedEntities);
				collector.apply(entityCollection.get(), collectedDecorators, requestToUse.get())
					.forEach(
						it -> index.put(
							ofNullable(remappingIndex.get())
								.map(ix -> ix.get(new EntityReference(it.getType(), it.getPrimaryKey())))
								.orElse(it.getPrimaryKey()),
							it
						)
					);
				prefetchedEntitiesPeek.set(0);
			}
		};

		for (final int epk : inputPrimaryKeys) {
			final EntityDecorator prefetchedEntity;
			final Locale implicitLocale;

			// if at least one masked primary key was assigned
			if (this.entityReferencePkSequence > 0) {
				// retrieve the prefetched entity by the masked key
				prefetchedEntity = getPrefetchedEntityByMaskedPrimaryKey(epk);
				// attempt to retrieve implicit locale from this prefetched entity
				// implicit locale = locale derived from the global unique attr that might have been resolved in filter
				implicitLocale = getPrefetchedEntityImplicitLocale(epk);
			} else {
				// retrieve the prefetched entity by its primary key
				prefetchedEntity = getPrefetchedEntityByPrimaryKey(epk);
				implicitLocale = getPrefetchedEntityImplicitLocale(epk);
			}

			// init collection
			final boolean collectionChanged;
			if (prefetchedEntity == null && !Objects.equals(lastEntityType, entityType)) {
				Assert.isTrue(entityType != null, () -> new EntityCollectionRequiredException("fetch entity"));
				entityCollection.set(getEntityCollectionOrThrowException(entityType, "fetch entity"));
				lastEntityType = entityType;
				collectionChanged = true;
			} else if (prefetchedEntity != null && !Objects.equals(lastEntityType, prefetchedEntity.getType())) {
				entityCollection.set(getEntityCollectionOrThrowException(prefetchedEntity.getType(), "fetch entity"));
				lastEntityType = prefetchedEntity.getType();
				collectionChanged = true;
			} else {
				collectionChanged = false;
			}

			// resolve the request that should be used for fetching
			if ((implicitLocale == null || evitaRequest.getLocale() != null) && evitaRequest != requestToUse.get()) {
				dataCollector.run();
				requestToUse.set(evitaRequest);
				lastImplicitLocale = null;
			} else if (!Objects.equals(lastImplicitLocale, implicitLocale)) {
				dataCollector.run();
				// when implicit locale is found we need to fabricate new request for that particular entity
				// that will use such implicit locale as if it would have been part of the original request
				lastImplicitLocale = implicitLocale;
				requestToUse.set(new EvitaRequest(evitaRequest, implicitLocale));
			} else if (collectionChanged) {
				dataCollector.run();
			}

			// now apply collector to fetch the entity in requested form using potentially enriched request
			if (prefetchedEntity == null) {
				primaryKeysToFetch[primaryKeyPeek.getAndIncrement()] = epk;
			} else {
				prefetchedEntities[prefetchedEntitiesPeek.getAndIncrement()] = prefetchedEntity;
				if (epk != prefetchedEntity.getPrimaryKey()) {
					if (remappingIndex.get() == null) {
						remappingIndex.set(CollectionUtils.createHashMap(inputPrimaryKeys.length));
					}
					remappingIndex.get().put(
						new EntityReference(prefetchedEntity.getType(), prefetchedEntity.getPrimaryKey()),
						epk
					);
				}
			}
		}

		dataCollector.run();

		return Arrays.stream(inputPrimaryKeys)
			.mapToObj(index::get)
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * Method extracts implicit locale that might be derived from the globally unique attribute if the entity is matched
	 * particularly by it.
	 */
	@Nullable
	private Locale getPrefetchedEntityImplicitLocale(int entityPrimaryKey) {
		final EntityReferenceContract<EntityReference> entityReference = ofNullable(this.entityReferencePkIndex)
			.map(it -> it.get(entityPrimaryKey))
			.orElse(null);
		return entityReference instanceof EntityReferenceWithLocale entityReferenceWithLocale ? entityReferenceWithLocale.locale() : null;
	}

	/**
	 * Method returns appropriate {@link EntityCollection} for the {@link #evitaRequest} or throws comprehensible
	 * exception. In order exception to be comprehensible you need to provide sensible `reason` for accessing
	 * the collection in the input parameter.
	 */
	@Nonnull
	public EntityCollection getEntityCollectionOrThrowException(@Nullable String entityType, @Nonnull String reason) {
		if (entityType == null) {
			throw new EntityCollectionRequiredException(reason);
		} else if (Objects.equals(entityType, this.entityType) && entityCollection != null) {
			return entityCollection;
		} else {
			return catalog.getCollectionForEntityOrThrowException(entityType);
		}
	}

	/**
	 * This method is used to avoid multiple creation of the exactly same outputs of the nested queries that involve
	 * creating separate optimized calculation formula tree. There are usually multiple formula calculation trees
	 * created when trying to find the most optimal one - only the least expensive is used at the end. Because
	 * the nested tree is evaluated separately we need to cache its result to avoid unnecessary multiple creations
	 * of the exactly same nested query formula tree.
	 *
	 * @param constraint caching key for which the lambda should be invoked only once
	 * @param formulaSupplier the lambda that creates the formula
	 * @return created formula
	 */
	@Nonnull
	public Formula computeOnlyOnce(
		@Nonnull FilterConstraint constraint,
		@Nonnull Supplier<Formula> formulaSupplier
	) {
		if (parentContext == null) {
			if (internalCache == null) {
				internalCache = new HashMap<>();
			}
			return internalCache.computeIfAbsent(
				constraint, cnt -> formulaSupplier.get()
			);
		} else {
			return parentContext.computeOnlyOnce(
				constraint, formulaSupplier
			);
		}
	}

	/**
	 * Method creates new {@link EvitaRequest} for particular `entityType` that takes all passed `requiredConstraints`
	 * into the account. Fabricated request is expected to be used only for passing the scope to
	 * {@link EntityCollection#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)} or
	 * {@link EntityCollection#enrichEntity(SealedEntity, EvitaRequest, EvitaSessionContract)} methods.
	 */
	@Nonnull
	private EvitaRequest fabricateFetchRequest(@Nonnull String entityType, @Nonnull EntityFetchRequirements requirements) {
		return evitaRequest.deriveCopyWith(entityType, requirements);
	}

}
