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

package io.evitadb.core.query;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.query.require.FetchRequirementCollector;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.FacetFilterBy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.facet.producer.FilteringFormulaPredicate;
import io.evitadb.core.query.policy.BitmapFavouringNoCachePolicy;
import io.evitadb.core.query.policy.DefaultPolicy;
import io.evitadb.core.query.policy.PlanningPolicy;
import io.evitadb.core.query.policy.PlanningPolicy.PrefetchPolicy;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static java.util.Optional.ofNullable;

/**
 * Query context aggregates references to all the instances that are required to process the {@link EvitaRequest}.
 * The object serves as single "go to" object while preparing or executing {@link QueryPlan}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class QueryPlanningContext implements LocaleProvider, PrefetchStrategyResolver {
	private static final EnumMap<Scope, EntityIndexKey> GLOBAL_INDEX_KEY = new EnumMap<>(
		Map.of(
			Scope.LIVE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
			Scope.ARCHIVED, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED)
		)
	);

	/**
	 * Contains reference to the parent context of this one. The reference is not NULL only for sub-queries.
	 */
	@Nullable private final QueryPlanningContext parentContext;
	/**
	 * Reference to the collector of requirements for entity prefetch phase.
	 */
	@Nonnull @Getter
	private final FetchRequirementCollector fetchRequirementCollector = new DefaultPrefetchRequirementCollector();
	/**
	 * Contains reference to the policy that controls the interaction with cache and drives the query planning strategy.
	 */
	@Nonnull private final PlanningPolicy planningPolicy;
	/**
	 * Internal event to be fired when the query was finished.
	 */
	@Nullable @Getter private final FinishedEvent queryFinishedEvent;
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
	@Getter
	@Nullable private final String entityType;
	/**
	 * Contains reference to the enveloping {@link EvitaSessionContract} within which the {@link #evitaRequest} is executed.
	 */
	@Getter
	@Nonnull private final EvitaSession evitaSession;
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
	 * by its {@link Formula#getHash()} method and when the supervisor identifies that certain
	 * formula is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	@Getter
	@Nonnull private final CacheSupervisor cacheSupervisor;
	/**
	 * This flag signalizes that the entity prefetching is not possible within this context. I.e. it means that
	 * the entities wil be never prefetched. Prefetching is not possible for nested queries since
	 * the prefetched entities wouldn't ever be used in the output and it would also force us to eagerly evaluate
	 * the created formula.
	 */
	private final boolean prefetchPossible;
	/**
	 * Internal execution context used for execution of formulas evaluated in planning phase.
	 */
	@Getter @Nonnull
	private final QueryExecutionContext internalExecutionContext;
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
	 * Cached version of {@link EntitySchema} for {@link #entityType}.
	 */
	private EntitySchema entitySchema;
	/**
	 * Contains reference to the {@link HierarchyFilteringPredicate} that keeps information about all hierarchy nodes
	 * that should be included/excluded from traversal.
	 */
	@Getter
	private HierarchyFilteringPredicate hierarchyHavingPredicate;
	/**
	 * Contains reference to the {@link Formula} that calculates the root hierarchy node ids used for filtering
	 * the query result to be reused in other query evaluation phases (require).
	 */
	private Formula rootHierarchyNodesFormula;
	/**
	 * The index contains rules for facet summary computation regarding the inter facet relation. The key in the index
	 * is a tuple consisting of `referenceName` and `typeOfRule`, the value in the index is prepared predicate allowing
	 * to mark the group id involved in special relation handling.
	 */
	private Map<FacetRelationTuple, FilteringFormulaPredicate> facetRelationTuples;
	/**
	 * Internal cache currently server sor caching the computed formulas of nested queries.
	 *
	 * @see #computeOnlyOnce(List, FilterConstraint, Supplier, long...) for more details
	 */
	private Map<InternalCacheKey, Formula> internalCache;


	public <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(
			null, catalog, entityCollection,
			evitaSession, evitaRequest,
			telemetry, indexes, cacheSupervisor,
			new FinishedEvent(
				catalog.getName(),
				entityCollection == null ? null : entityCollection.getEntityType(),
				evitaRequest.getLabels()
			)
		);
	}

	public <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nullable QueryPlanningContext parentQueryContext,
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(
			parentQueryContext, catalog, entityCollection,
			evitaSession, evitaRequest, telemetry, indexes, cacheSupervisor, null
		);
	}

	private <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nullable QueryPlanningContext parentQueryContext,
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull CacheSupervisor cacheSupervisor,
		@Nullable FinishedEvent event
	) {
		this.parentContext = parentQueryContext;
		this.catalog = catalog;
		this.entityCollection = entityCollection;
		this.entityType = ofNullable(entityCollection)
			.map(EntityCollection::getSchema)
			.map(EntitySchemaContract::getName)
			.orElse(null);
		Assert.isPremiseValid(evitaSession instanceof EvitaSession, "The session must be an instance of EvitaSession!");
		this.evitaSession = (EvitaSession) evitaSession;
		this.evitaRequest = evitaRequest;
		if (parentQueryContext == null) {
			// when debug mode is enabled we need to enforce the main plan to be non-cached
			// the cached variants needs to be derived from it
			this.planningPolicy = isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES) ?
				BitmapFavouringNoCachePolicy.INSTANCE : DefaultPolicy.INSTANCE;
			this.prefetchPossible = true;
		} else {
			this.planningPolicy = parentQueryContext.planningPolicy;
			this.prefetchPossible = false;
		}
		this.telemetryStack = new ArrayDeque<>(16);
		ofNullable(telemetry).ifPresent(this.telemetryStack::push);
		//noinspection unchecked
		this.indexes = (Map<IndexKey, Index<?>>) indexes;
		this.cacheSupervisor = cacheSupervisor;
		this.queryFinishedEvent = event;
		this.internalExecutionContext = createExecutionContext();
	}

	/**
	 * Shortcut for {@link EvitaRequest#getScopes()}.
	 *
	 * @return set of requested scopes in the query
	 */
	@Nonnull
	public Set<Scope> getScopes() {
		return this.evitaRequest.getScopes();
	}

	/**
	 * Delegates method to {@link FetchRequirementCollector#addRequirementsToPrefetch(EntityContentRequire...)}.
	 *
	 * @param require the requirement to prefetch
	 */
	public void addRequirementToPrefetch(@Nonnull EntityContentRequire... require) {
		this.fetchRequirementCollector.addRequirementsToPrefetch(require);
	}

	/**
	 * Delegates method to {@link FetchRequirementCollector#getRequirementsToPrefetch()}.
	 *
	 * @return an array of {@link EntityContentRequire} representing the requirements to prefetch
	 */
	@Nonnull
	public EntityContentRequire[] getRequirementsToPrefetch() {
		return this.fetchRequirementCollector.getRequirementsToPrefetch();
	}

	/**
	 * Returns true if the input {@link #evitaRequest} contains specification of the entity collection.
	 */
	public boolean isEntityTypeKnown() {
		return this.entityType != null;
	}

	@Override
	public boolean isPrefetchPossible() {
		return this.prefetchPossible && this.planningPolicy.getPrefetchPolicy() == PrefetchPolicy.ALLOW;
	}

	/**
	 * Returns estimated prefetch cost for the passed entity count and requirements.
	 *
	 * @param prefetchEntityCount count of entities to prefetch
	 * @param requirements        requirements for the prefetch
	 * @return estimated prefetch cost
	 */
	public long estimatePrefetchCost(int prefetchEntityCount, @Nonnull EntityFetchRequire requirements) {
		return this.planningPolicy.estimatePrefetchCost(
			prefetchEntityCount, requirements, isDebugModeEnabled(DebugMode.PREFER_PREFETCHING)
		);
	}

	/**
	 * Checks if any of the keys in the indexes map are instances of EntityIndexKey.
	 *
	 * @return true if at least one key in the indexes map is an instance of EntityIndexKey;
	 * false otherwise.
	 */
	public boolean hasEntityGlobalIndex() {
		return this.indexes.keySet().stream().anyMatch(EntityIndexKey.class::isInstance);
	}

	/**
	 * Returns {@link EntityIndex} of external entity type by its key and entity type.
	 */
	@Nonnull
	public <T extends EntityIndex> Optional<T> getIndex(@Nonnull String entityType, @Nonnull EntityIndexKey entityIndexKey, @Nonnull Class<T> indexType) {
		final EntityIndex entityIndex = getEntityCollectionOrThrowException(entityType, "access entity index")
			.getIndexByKeyIfExists(entityIndexKey);
		Assert.isPremiseValid(
			entityIndex == null || indexType.isInstance(entityIndex),
			() -> "Expected index of type " + indexType + " but got " + (entityIndex == null ? "NULL" : entityIndex.getClass()) + "!"
		);
		//noinspection unchecked
		return ofNullable((T) entityIndex);
	}

	/**
	 * Returns {@link EntityIndex} by its key.
	 */
	@Nonnull
	public <S extends IndexKey, T extends Index<S>> Optional<T> getIndex(@Nonnull S indexKey) {
		if (indexKey instanceof CatalogIndexKey cik) {
			//noinspection unchecked
			return ofNullable((T) this.catalog.getCatalogIndex(cik.scope()));
		} else {
			//noinspection unchecked
			return ofNullable((T) this.indexes.get(indexKey));
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.push(
				this.telemetryStack.peek().addStep(phase)
			);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull String message) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.push(
				this.telemetryStack.peek().addStep(phase, message)
			);
		}
	}

	/**
	 * Adds new step of query evaluation.
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull Supplier<String> messageSupplier) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.push(
				this.telemetryStack.peek().addStep(phase, messageSupplier.get())
			);
		}
	}

	/**
	 * Returns current step of the evaluation.
	 */
	@Nullable
	public QueryTelemetry getCurrentStep() {
		if (!this.telemetryStack.isEmpty()) {
			return this.telemetryStack.peek();
		}
		return null;
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep() {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.pop().finish();
		}
	}

	/**
	 * Finishes current query evaluation step.
	 */
	public void popStep(@Nonnull String message) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.pop().finish(message);
		}
	}

	/**
	 * Returns finalized {@link QueryTelemetry} or throws an exception.
	 */
	@Nonnull
	public QueryTelemetry finalizeAndGetTelemetry() {
		Assert.isPremiseValid(!this.telemetryStack.isEmpty(), "The telemetry has been already retrieved!");

		// there may be some steps still open at the time extra results is fabricated
		QueryTelemetry rootStep;
		do {
			rootStep = this.telemetryStack.pop();
			rootStep.finish();
		} while (!this.telemetryStack.isEmpty());

		return rootStep;
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getFilterBy()}.
	 */
	@Nullable
	public FilterBy getFilterBy() {
		return this.evitaRequest.getQuery().getFilterBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getOrderBy()} ()}.
	 */
	@Nullable
	public OrderConstraint getOrderBy() {
		return this.evitaRequest.getQuery().getOrderBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getRequire()} ()}.
	 */
	@Nullable
	public RequireConstraint getRequire() {
		return this.evitaRequest.getQuery().getRequire();
	}

	/**
	 * Returns language specified in {@link EvitaRequest}. Language is valid for entire query.
	 */
	@Override
	@Nullable
	public Locale getLocale() {
		return this.evitaRequest.getLocale();
	}

	/**
	 * Returns query price mode specified in {@link EvitaRequest}. Query price mode is valid for entire query.
	 */
	@Nonnull
	public QueryPriceMode getQueryPriceMode() {
		return this.evitaRequest.getQueryPriceMode();
	}

	/**
	 * Returns schema of the catalog.
	 */
	@Nonnull
	public SealedCatalogSchema getCatalogSchema() {
		return this.catalog.getSchema();
	}

	/**
	 * Returns entity schema.
	 */
	@Nonnull
	public EntitySchema getSchema() {
		if (this.entitySchema == null) {
			this.entitySchema = getEntityCollectionOrThrowException(this.entityType, "access entity schema").getInternalSchema();
		}
		return this.entitySchema;
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
		return this.evitaRequest.isDebugModeEnabled(debugMode);
	}

	/**
	 * Returns global {@link GlobalEntityIndex} of the collection if the target entity collection is known.
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalEntityIndexIfExists(@Nonnull Scope scope) {
		return getIndex(GLOBAL_INDEX_KEY.get(scope));
	}

	/**
	 * Returns global {@link GlobalEntityIndex} of the collection or throws an exception.
	 */
	@Nonnull
	public GlobalEntityIndex getGlobalEntityIndex(@Nonnull Scope scope) {
		return getGlobalEntityIndexIfExists(scope)
			.orElseThrow(() -> new GenericEvitaInternalError("Global index of entity unexpectedly not found!"));
	}

	/**
	 * Returns {@link EntityIndex} by its key and entity type.
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalEntityIndexIfExists(@Nonnull String entityType, @Nonnull Scope scope) {
		return getIndex(entityType, GLOBAL_INDEX_KEY.get(scope), GlobalEntityIndex.class);
	}

	/**
	 * Analyzes the input formula for cacheable / cached formulas and replaces them with appropriate counterparts (only
	 * if cache is enabled).
	 */
	@Nonnull
	public Formula analyse(@Nonnull Formula formula) {
		return ofNullable(this.evitaRequest.getEntityType())
			.map(it -> this.cacheSupervisor.analyse(this.evitaSession, it, formula))
			.orElse(formula);
	}

	/**
	 * Analyzes the input extra result computer for cacheable / cached extra result computers and replaces them with
	 * appropriate counterparts (only if cache is enabled).
	 */
	@Nonnull
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull T computer) {
		return ofNullable(this.evitaRequest.getEntityType())
			.map(it -> this.planningPolicy.analyse(this.cacheSupervisor, this.evitaSession, it, computer))
			.orElse(computer);
	}

	/**
	 * Returns true if session is switched to binary format output.
	 *
	 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
	 */
	public boolean isRequiresBinaryForm() {
		return this.evitaSession.isBinaryFormat();
	}

	/**
	 * Method returns appropriate {@link EntityCollection} for the {@link #evitaRequest} or empty value.
	 */
	@Nonnull
	public Optional<EntityCollection> getEntityCollection(@Nullable String entityType) {
		if (entityType == null) {
			return Optional.empty();
		} else if (Objects.equals(entityType, this.entityType) && this.entityCollection != null) {
			return Optional.of(this.entityCollection);
		} else {
			return Optional.ofNullable(
				(EntityCollection) this.catalog.getCollectionForEntity(entityType).orElse(null)
			);
		}
	}

	/**
	 * Method returns appropriate {@link EntityCollection} for the {@link #evitaRequest} or throws comprehensible
	 * exception. In order exception to be comprehensible you need to provide sensible `reason` for accessing
	 * the collection in the input parameter.
	 */
	@Nonnull
	public EntityCollection getEntityCollectionOrThrowException(@Nullable String entityType, @Nonnull String reason) {
		return getEntityCollection(entityType)
			.orElseThrow(() -> new EntityCollectionRequiredException(reason));
	}

	/**
	 * Method returns appropriate {@link EntityCollection} for the {@link #evitaRequest} or throws comprehensible
	 * exception. In order exception to be comprehensible you need to provide sensible `reason` for accessing
	 * the collection in the input parameter.
	 */
	@Nonnull
	public EntityCollection getEntityCollectionOrThrowException(@Nullable String entityType, @Nonnull Supplier<String> reasonSupplier) {
		return getEntityCollection(entityType)
			.orElseThrow(() -> new EntityCollectionRequiredException(reasonSupplier.get()));
	}

	/**
	 * Method creates new {@link EvitaRequest} for particular `entityType` that takes all passed `requiredConstraints`
	 * into the account. Fabricated request is expected to be used only for passing the scope to
	 * {@link EntityCollection#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}  or
	 * {@link EntityCollection#enrichEntity(EntityContract, EvitaRequest, EvitaSessionContract)}  methods.
	 */
	@Nonnull
	public EvitaRequest fabricateFetchRequest(@Nullable String entityType, @Nonnull EntityFetchRequire requirements) {
		return this.evitaRequest.deriveCopyWith(entityType, requirements);
	}

	/**
	 * This method is used to avoid multiple creation of the exactly same outputs of the nested queries that involve
	 * creating separate optimized calculation formula tree. There are usually multiple formula calculation trees
	 * created when trying to find the most optimal one - only the least expensive is used at the end. Because
	 * the nested tree is evaluated separately we need to cache its result to avoid unnecessary multiple creations
	 * of the exactly same nested query formula tree.
	 *
	 * Formulas are expected to be invoked in planning phase and share the same {@link #internalExecutionContext}.
	 *
	 * @param constraint      caching key for which the lambda should be invoked only once
	 * @param formulaSupplier the lambda that creates the formula
	 * @return created formula
	 */
	@Nonnull
	public Formula computeOnlyOnce(
		@Nonnull List<EntityIndex> entityIndexes,
		@Nonnull FilterConstraint constraint,
		@Nonnull Supplier<Formula> formulaSupplier,
		long... additionalCacheKeys
	) {
		if (this.parentContext == null) {
			if (this.internalCache == null) {
				this.internalCache = new HashMap<>();
			}
			final InternalCacheKey cacheKey = new InternalCacheKey(
				LongStream.concat(
					entityIndexes.stream().mapToLong(EntityIndex::getId),
					Arrays.stream(additionalCacheKeys).map(Math::negateExact)
				).toArray(),
				constraint
			);
			final Formula cachedResult = this.internalCache.get(cacheKey);
			if (cachedResult == null) {
				final Formula computedResult = formulaSupplier.get();
				computedResult.initialize(this.internalExecutionContext);
				this.internalCache.put(cacheKey, computedResult);
				return computedResult;
			} else {
				return cachedResult;
			}
		} else {
			return this.parentContext.computeOnlyOnce(
				entityIndexes, constraint, formulaSupplier, additionalCacheKeys
			);
		}
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
	 * Method returns requested {@link EntityReference} by specifying its primary key (either virtual or real).
	 */
	@Nonnull
	public EntityReference translateToEntityReference(int primaryKey) {
		if (this.entityReferencePkSequence > 0) {
			return ofNullable(this.entityReferencePkIndex.get(primaryKey))
				.map(EntityReference::new)
				.orElseGet(() -> new EntityReference(this.getSchema().getName(), primaryKey));
		} else {
			return new EntityReference(this.getSchema().getName(), primaryKey);
		}
	}

	/**
	 * Returns true if at least one primary key was masked by {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)}.
	 *
	 * @return true if at least one primary key was masked
	 */
	public boolean isAtLeastOneMaskedPrimaryAssigned() {
		return this.entityReferencePkSequence > 0;
	}

	/**
	 * Returns {@link EntityReferenceContract} for passed primary key if it was previously registered by
	 * {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)}.
	 *
	 * @param primaryKey primary key of the entity
	 * @return entity reference contract or empty if not found
	 */
	@Nonnull
	public Optional<EntityReferenceContract<EntityReference>> getEntityReferenceIfExist(int primaryKey) {
		return ofNullable(this.entityReferencePkIndex)
			.map(it -> it.get(primaryKey));
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
	 * Method returns requested entity primary key by specifying its primary key (either virtual or real).
	 */
	public int translateToEntityPrimaryKey(int primaryKey) {
		if (this.entityReferencePkSequence > 0) {
			final EntityReferenceContract<EntityReference> referencedEntity = this.entityReferencePkIndex.get(primaryKey);
			return referencedEntity == null ? primaryKey : referencedEntity.getPrimaryKey();
		} else {
			return primaryKey;
		}
	}


	/**
	 * Sets resolved hierarchy root nodes formula to be shared among filter and requirement phase.
	 */
	public void setRootHierarchyNodesFormula(@Nonnull Formula rootHierarchyNodesFormula) {
		Assert.isPremiseValid(this.rootHierarchyNodesFormula == null, "The hierarchy filtering formula can be set only once!");
		this.rootHierarchyNodesFormula = rootHierarchyNodesFormula;
	}

	/**
	 * Sets resolved hierarchy having/exclusion predicate to be shared among filter and requirement phase.
	 */
	public void setHierarchyHavingPredicate(@Nonnull HierarchyFilteringPredicate hierarchyHavingPredicate) {
		Assert.isPremiseValid(
			this.hierarchyHavingPredicate == null || this.hierarchyHavingPredicate.equals(hierarchyHavingPredicate),
			"The hierarchy exclusion predicate can be set only once!"
		);
		this.hierarchyHavingPredicate = hierarchyHavingPredicate;
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by conjunction (AND) on
	 * particular level.
	 *
	 * @param referenceSchema reference schema of the facet group
	 * @param groupId         group id to be tested
	 * @param level           level of the facet group relation (within group, between groups)
	 */
	public boolean isFacetGroupConjunction(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return isFacetGroupRelationType(
			FacetRelationType.CONJUNCTION,
			referenceSchema, groupId, level,
			EvitaRequest::getFacetGroupConjunction
		);
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by disjunction (OR) on
	 * particular level.
	 *
	 * @param referenceSchema reference schema of the facet group
	 * @param groupId         group id to be tested
	 * @param level           level of the facet group relation (within group, between groups)
	 */
	public boolean isFacetGroupDisjunction(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return isFacetGroupRelationType(
			FacetRelationType.DISJUNCTION,
			referenceSchema, groupId, level,
			EvitaRequest::getFacetGroupDisjunction
		);
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by negation (AND NOT) on
	 * particular level.
	 *
	 * @param referenceSchema reference schema of the facet group
	 * @param groupId         group id to be tested
	 * @param level           level of the facet group relation (within group, between groups)
	 */
	public boolean isFacetGroupNegation(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return isFacetGroupRelationType(
			FacetRelationType.NEGATION,
			referenceSchema, groupId, level,
			EvitaRequest::getFacetGroupNegation
		);
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by exclusivity on
	 * particular level.
	 *
	 * @param referenceSchema reference schema of the facet group
	 * @param groupId         group id to be tested
	 * @param level           level of the facet group relation (within group, between groups)
	 */
	public boolean isFacetGroupExclusivity(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level
	) {
		return isFacetGroupRelationType(
			FacetRelationType.EXCLUSIVITY,
			referenceSchema, groupId, level,
			EvitaRequest::getFacetGroupExclusivity
		);
	}

	/**
	 * Determines whether the specified relation type matches the given facet group relation criteria.
	 *
	 * @param relationType the type of the facet relation to be checked
	 * @param referenceSchema the schema of the reference to which the facet group belongs
	 * @param groupId the identifier of the group being considered; can be null if no group is specified
	 * @param level the level of facet group relation that should be considered in the evaluation
	 * @return {@code true} if the relation type matches the facet group relation criteria, {@code false} otherwise
	 */
	private boolean isFacetGroupRelationType(
		@Nonnull FacetRelationType relationType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		@Nonnull FacetGroupRelationLevel level,
		@Nonnull BiFunction<EvitaRequest, String, Optional<FacetFilterBy>> facetSettingsRetriever
		) {
		final String referenceName = referenceSchema.getName();
		final FacetRelationType theDefault = level == FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP ?
			this.evitaRequest.getDefaultFacetRelationType() : this.evitaRequest.getDefaultGroupRelationType();
		final Optional<FacetFilterBy> facetSettings = facetSettingsRetriever.apply(this.evitaRequest, referenceName);
		if (facetSettings.isEmpty()) {
			return theDefault == relationType;
		} else {
			final FacetFilterBy facetFilterBy = facetSettings.get();
			final FilterBy filterBy = facetFilterBy.filterBy();
			if (filterBy != null) {
				if (groupId == null) {
					return false;
				} else {
					final boolean requestedExplicitly = getFacetRelationTuples()
						.computeIfAbsent(
							new FacetRelationTuple(referenceName, relationType),
							refName -> {
								final String referencedGroupType = referenceSchema.getReferencedGroupType();
								Assert.isTrue(
									referencedGroupType != null,
									() -> "Referenced group type must be defined for facet group " + relationType.name().toLowerCase() + " of `" + referenceName + "`!"
								);
								if (referenceSchema.isReferencedGroupTypeManaged()) {
									return new FilteringFormulaPredicate(
										this,
										getScopes(),
										filterBy,
										referencedGroupType,
										() -> "Facet group " + relationType.name().toLowerCase() + " of `" + referenceSchema.getName() + "` filter: " + facetFilterBy
									);
								} else {
									return new FilteringFormulaPredicate(
										this,
										getThrowingGlobalIndexesForNonManagedEntityTypeGroup(referenceName, referencedGroupType),
										filterBy,
										() -> "Facet group "  + relationType.name().toLowerCase() + " of `" + referenceSchema.getName() + "` filter: " + facetFilterBy
									);
								}
							}
						)
						.test(groupId);
					return requestedExplicitly || theDefault == relationType;
				}
			} else {
				return true;
			}
		}
	}

	/**
	 * Returns primary key of all root hierarchy nodes that cover the requested hierarchy.
	 *
	 * @return bitmap of root hierarchy nodes
	 */
	@Nonnull
	public Bitmap getRootHierarchyNodes() {
		return ofNullable(this.rootHierarchyNodesFormula)
			.map(Formula::compute)
			.orElse(EmptyBitmap.INSTANCE);
	}

	/**
	 * Creates new {@link QueryExecutionContext} that can be used to execute the query plan.
	 *
	 * @return new query execution context
	 */
	@Nonnull
	public QueryExecutionContext createExecutionContext() {
		return this.createExecutionContext(false, null);
	}

	/**
	 * Creates new {@link QueryExecutionContext} that can be used to execute the query plan.
	 * This overload allows to pass frozen random bytes that will be used for the query execution.
	 *
	 * @param prefetchExecution flag that signalizes if the prefetching was executed and filtering should occur on
	 *                          prefetched entities
	 * @param frozenRandom      frozen random bytes to be used for the query execution
	 * @return new query execution context
	 */
	@Nonnull
	public QueryExecutionContext createExecutionContext(boolean prefetchExecution, @Nullable byte[] frozenRandom) {
		return new QueryExecutionContext(
			this, prefetchExecution, frozenRandom, this.evitaSession::createEntityProxy
		);
	}

	/**
	 * Method retrieves already assigned masking id for the {@link EntityReference} or creates brand new. This virtual
	 * id is necessary because our filtering logic works with {@link Bitmap} objects that contains plain integers. In
	 * situation when no target entity collection is specified and filters targeting global attributes retrieves
	 * entities from various collections - their ids may overlap, and we need to keep them separated during computation.
	 * That's why we use such virtual ids during entire filtering and sorting process.
	 */
	int getOrRegisterEntityReferenceMaskId(@Nonnull EntityReferenceContract<EntityReference> entityReference) {
		if (this.isEntityTypeKnown()) {
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

	/*
		PRIVATE METHODS
	 */

	/**
	 * Creates a list of global entity indexes for the given non-managed entity type. Global indexes contains only
	 * primary keys of groups retrieved from {@link FacetIndex} of the given reference.
	 *
	 * @param referenceName       name of the reference to retrieve groups from
	 * @param referencedGroupType type of the referenced group
	 * @return list of fake global entity indexes
	 */
	@Nonnull
	private List<GlobalEntityIndex> getThrowingGlobalIndexesForNonManagedEntityTypeGroup(
		@Nonnull String referenceName,
		@Nonnull String referencedGroupType
	) {
		return getScopes().stream()
			.map(scope -> {
				final Optional<Index<EntityIndexKey>> refTypeIndex = getIndex(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
				return refTypeIndex
					.map(GlobalEntityIndex.class::cast)
					.map(index -> index.getFacetingEntities().get(referenceName))
					.map(facetIndex -> GlobalEntityIndex.createThrowingStub(
							referencedGroupType,
							new EntityIndexKey(EntityIndexType.GLOBAL, scope),
							facetIndex.getGroupsAsMap().keySet()
						)
					)
					.orElse(null);
			})
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * Lazy initialization of the facet relation tuples.
	 *
	 * @return facet relation tuples
	 */
	@Nonnull
	private Map<FacetRelationTuple, FilteringFormulaPredicate> getFacetRelationTuples() {
		if (this.facetRelationTuples == null) {
			this.facetRelationTuples = new HashMap<>();
		}
		return this.facetRelationTuples;
	}

	/**
	 * Tuple that wraps {@link ReferenceSchemaContract#getName()} and {@link FacetRelationType} into one object used as
	 * the {@link #facetRelationTuples} key.
	 */
	private record FacetRelationTuple(
		@Nonnull String referenceName,
		@Nonnull FacetRelationType relation
	) {

	}

	/**
	 * The internal caching key.
	 *
	 * @param indexKeys  array of {@link EntityIndex#getId()} that were used for result calculation
	 * @param constraint the constraint that has been evaluated on those indexes
	 */
	private record InternalCacheKey(
		@Nonnull long[] indexKeys,
		@Nonnull Constraint<?> constraint
	) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			InternalCacheKey that = (InternalCacheKey) o;
			return Arrays.equals(this.indexKeys, that.indexKeys) && Objects.equals(this.constraint, that.constraint);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(this.constraint);
			result = 31 * result + Arrays.hashCode(this.indexKeys);
			return result;
		}

		@Nonnull
		@Override
		public String toString() {
			return "InternalCacheKey{" +
				"indexKeys=" + Arrays.toString(this.indexKeys) +
				", constraint=" + this.constraint +
				'}';
		}
	}

}
