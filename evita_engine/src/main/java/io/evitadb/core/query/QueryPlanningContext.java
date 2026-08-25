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

package io.evitadb.core.query;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.index.EntityIndexType;
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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.reference.producer.FilteringFormulaPredicate;
import io.evitadb.core.query.policy.BitmapFavouringNoCachePolicy;
import io.evitadb.core.query.policy.DefaultPolicy;
import io.evitadb.core.query.policy.PlanningPolicy;
import io.evitadb.core.query.policy.PlanningPolicy.PrefetchPolicy;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.*;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Query context aggregates references to all the instances that are required to process the {@link EvitaRequest}.
 * The object serves as single "go to" object while preparing or executing {@link QueryPlan}.
 *
 * The context covers the **planning** phase - index lookup, formula tree construction, cost estimation and
 * telemetry of all of that. The **execution** phase gets its own short-lived {@link QueryExecutionContext}
 * fabricated by {@link #createExecutionContext(boolean, byte[])}; one planning context may spawn several of
 * them (the plan is executed repeatedly when a debug mode verifies alternative plans against each other).
 *
 * Nested (sub-)queries receive their own child context linked through {@link #parentContext}. A child inherits
 * the parent's {@link PlanningPolicy}, delegates {@link #computeOnlyOnce(List, FilterConstraint, Supplier, long...)}
 * to the root context, and can never prefetch - see {@link #isPrefetchPossible()}.
 *
 * The instance is **not** thread safe and is bound to a single query evaluation - most of its state is lazily
 * initialized on first use and mutated in place.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class QueryPlanningContext implements LocaleProvider, PrefetchStrategyResolver {
	/**
	 * Pre-computed {@link EntityIndexKey} of the global entity index for each {@link Scope}. The keys are immutable
	 * and looked up on every query, so they are shared instead of being allocated over and over again.
	 */
	private static final EnumMap<Scope, EntityIndexKey> GLOBAL_INDEX_KEY = new EnumMap<>(
		Map.of(
			Scope.LIVE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
			Scope.ARCHIVED, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED)
		)
	);

	/**
	 * Contains reference to the parent context of this one. The reference is not NULL only for sub-queries.
	 *
	 * Its presence is what distinguishes a nested query from the outer one: a context with a parent inherits
	 * the parent {@link #planningPolicy}, has {@link #prefetchPossible} forced to false and forwards
	 * {@link #computeOnlyOnce(List, FilterConstraint, Supplier, long...)} up the chain so that the memoization
	 * of nested formulas is shared by the entire query.
	 */
	@Nullable private final QueryPlanningContext parentContext;
	/**
	 * Reference to the collector of requirements for entity prefetch phase.
	 *
	 * Translators register here the {@link EntityContentRequire} they will need on a prefetched entity body, so that
	 * a single prefetch can satisfy all of them at once instead of each translator fetching on its own.
	 */
	@Nonnull @Getter
	private final FetchRequirementCollector fetchRequirementCollector = new DefaultPrefetchRequirementCollector();
	/**
	 * Contains reference to the policy that controls the interaction with cache and drives the query planning strategy.
	 * It is picked once for the outer query (debug modes may force a non-caching variant) and inherited unchanged by
	 * every nested context, so a single query never mixes two policies.
	 */
	@Nonnull private final PlanningPolicy planningPolicy;
	/**
	 * Internal event to be fired when the query was finished. It is created only for the top level context created
	 * by the public "entry" constructor - nested contexts leave it NULL, because a sub-query is not a query from
	 * the metrics point of view and must not be reported as one.
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
	 * Contains reference to the enveloping {@link EvitaSessionContract} within which the {@link #evitaRequest}
	 * is executed.
	 *
	 * The reference is NULL when the query is evaluated outside any session (WAL replay re-evaluating a facet
	 * expression, for example) or when the passed session is not the internal {@link EvitaSession} implementation.
	 * Everything that genuinely needs a session degrades gracefully in that case: {@link #analyse(Formula)} skips
	 * the cache, {@link #isRequiresBinaryForm()} answers false and entity proxy creation throws.
	 */
	@Getter
	@Nullable private final EvitaSession evitaSession;
	/**
	 * Contains input in {@link EvitaRequest}.
	 */
	@Getter
	@Nonnull private final EvitaRequest evitaRequest;
	/**
	 * Contains {@link QueryTelemetry} information that measures the costs of each {@link #evitaRequest} processing
	 * phases.
	 *
	 * **An empty stack means telemetry was not requested.** The stack is seeded in the constructor only when a root
	 * {@link QueryTelemetry} node is passed in, and every telemetry method here starts by testing the stack for
	 * emptiness - so when the query did not ask for telemetry, the whole mechanism collapses into a series of no-ops
	 * and costs a single reference check per call. The bottom of the stack is the root node, the head is the
	 * innermost step that is still open.
	 */
	@Nonnull private final Deque<QueryTelemetry> telemetryStack;
	/**
	 * Collection of search indexes prepared to handle queries, keyed by their {@link IndexKey}. This is the map
	 * the query planner consults when it translates a constraint into an index lookup.
	 */
	@Nonnull private final Map<IndexKey, Index<?>> indexes;
	/**
	 * The very same indexes as in {@link #indexes}, keyed by the index primary key (the id the index is stored
	 * under in the persistent data store) instead. This is the lookup direction needed when
	 * {@link ReferencedTypeEntityIndex} hands out the primary keys of all reduced indexes belonging to
	 * a particular referenced entity - see {@link #getEntityIndexByPrimaryKey(int, Class)}.
	 */
	@Nonnull private final Map<Integer, Index<?>> indexesByPk;
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
	 * the entities will be never prefetched. Prefetching is not possible for nested queries since
	 * the prefetched entities wouldn't ever be used in the output and it would also force us to eagerly evaluate
	 * the created formula.
	 *
	 * The flag alone does not make prefetching happen - {@link #isPrefetchPossible()} also consults
	 * the {@link PlanningPolicy}, which may forbid prefetch even on the outer query.
	 */
	private final boolean prefetchPossible;
	/**
	 * Internal execution context used for execution of formulas evaluated in planning phase.
	 *
	 * Planning is supposed to be cheap, but a few decisions have to compute a formula eagerly to be made at all -
	 * hierarchy and facet group resolution, and everything memoized by
	 * {@link #computeOnlyOnce(List, FilterConstraint, Supplier, long...)}. They all initialize their formulas with
	 * this one context, so the memoized results stay valid for the whole planning phase instead of being
	 * recomputed per candidate plan.
	 */
	@Getter @Nonnull
	private final QueryExecutionContext internalExecutionContext;
	/**
	 * Contains sequence of already assigned virtual entity primary keys.
	 * If set to zero - no virtual entity primary key was assigned, if greater than zero it represents the last assigned
	 * virtual entity primary key.
	 *
	 * A non-zero value is therefore also the signal that primary keys flowing through the formula tree are masked
	 * and must be translated back before they leave the query - see {@link #isAtLeastOneMaskedPrimaryAssigned()}.
	 */
	private int entityReferencePkSequence;
	/**
	 * Contains index of virtual entity primary keys to {@link EntityReference} that was used to generate them.
	 *
	 * Lazily allocated together with {@link #entityReferencePkReverseIndex} on the first
	 * {@link #translateEntityReference(EntityReferenceContract...)} call and left NULL for the vast majority of
	 * queries that never mask anything - readers must tolerate the NULL.
	 */
	private IntObjectHashMap<EntityReferenceContract> entityReferencePkIndex;
	/**
	 * Contains index of {@link EntityReference} to their virtual primary keys. This index is exact opposite to
	 * {@link #entityReferencePkIndex} and shares its lazy initialization.
	 */
	private Map<EntityReferenceContract, Integer> entityReferencePkReverseIndex;
	/**
	 * Cached version of {@link EntitySchema} for {@link #entityType}. Resolved on the first {@link #getSchema()}
	 * call, because a context created for a catalog-wide query may never need it at all.
	 */
	private EntitySchema entitySchema;
	/**
	 * Contains reference to the {@link HierarchyFilteringPredicate} that keeps information about all hierarchy nodes
	 * that should be included/excluded from traversal.
	 *
	 * It is resolved by the filtering phase and handed over to the requirement phase, so that hierarchy statistics
	 * observe exactly the same node visibility as the filter did. It can be set only once per context - see
	 * {@link #setHierarchyHavingPredicate(HierarchyFilteringPredicate)}.
	 */
	@Getter
	private HierarchyFilteringPredicate hierarchyHavingPredicate;
	/**
	 * Contains reference to the {@link Formula} that calculates the root hierarchy node ids used for filtering
	 * the query result to be reused in other query evaluation phases (require). Shares the write-once contract of
	 * {@link #hierarchyHavingPredicate} and is read through {@link #getRootHierarchyNodes()}.
	 */
	private Formula rootHierarchyNodesFormula;
	/**
	 * The index contains rules for facet summary computation regarding the inter facet relation. The key in the index
	 * is a tuple consisting of `referenceName` and `typeOfRule`, the value in the index is prepared predicate allowing
	 * to mark the group id involved in special relation handling.
	 *
	 * The predicates are expensive - each of them plans and evaluates the group filter - and are asked about many
	 * group ids in a row, hence the memoization. Lazily allocated by {@link #getFacetRelationTuples()}.
	 */
	private Map<FacetRelationTuple, FilteringFormulaPredicate> facetRelationTuples;
	/**
	 * Internal cache that serves for caching the computed formulas of nested queries.
	 *
	 * Only the root context ever allocates it - nested contexts delegate to their parent - so a formula computed
	 * for one candidate plan is reused by all the others.
	 *
	 * @see #computeOnlyOnce(List, FilterConstraint, Supplier, long...) for more details
	 */
	private Map<InternalCacheKey, Formula> internalCache;
	/**
	 * The schema capabilities this query has asked for so far, as the **holders** counting them rather than the keys
	 * naming them - see {@link #registerRequestedCapability(SchemaCapabilityUsage)} for why, and
	 * {@link #drainRequestedCapabilities()} for what eventually happens to them.
	 *
	 * Left NULL until the first capability is registered, so a query that asks for none - by primary key, by facet, by
	 * hierarchy placement - allocates nothing at all. It is nulled again by the drain rather than cleared, which is
	 * what makes a second drain of the same context a no-op.
	 */
	@Nullable private List<SchemaCapabilityUsage> requestedCapabilities;


	/**
	 * Creates the context of a **top level** query - the one that produces the response handed back to the client.
	 * This is the only constructor that fabricates the {@link FinishedEvent}, so exactly one metric event is
	 * emitted per client query regardless of how many nested queries it spawns.
	 *
	 * @param catalog          the catalog the query is executed against
	 * @param entityCollection collection targeted by the query, NULL for catalog-wide queries
	 * @param evitaSession     the enveloping session, must be an {@link EvitaSession} instance
	 * @param evitaRequest     the request describing the query
	 * @param telemetry        pre-created root telemetry node, NULL when the query did not request telemetry -
	 *                         passing NULL turns the whole telemetry collection into a no-op
	 * @param indexes          indexes available for the query, keyed by their {@link IndexKey}
	 * @param indexesByPk      the very same indexes keyed by their primary key
	 * @param cacheSupervisor  supervisor deciding which formulas get their results memoized in the shared cache
	 */
	public <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull Map<Integer, T> indexesByPk,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(
			null, catalog, entityCollection,
			evitaSession, evitaRequest,
			telemetry, indexes, indexesByPk,
			cacheSupervisor,
			new FinishedEvent(
				catalog.getName(),
				entityCollection == null ? null : entityCollection.getEntityType(),
				evitaRequest.getLabels()
			)
		);
		Assert.isPremiseValid(evitaSession instanceof EvitaSession, "The session must be an instance of EvitaSession!");
	}

	/**
	 * Creates the context of a **nested** query - a sub-query planned while the outer query is being planned
	 * (a filter targeting another collection, a hierarchy statistics base, ...). No {@link FinishedEvent} is
	 * created here, and the child inherits the parent's planning policy while losing the ability to prefetch.
	 *
	 * Passing NULL as `parentQueryContext` produces a top level context **without** the metric event, which is
	 * what internal, non-client-facing evaluations want.
	 *
	 * @param parentQueryContext context of the enclosing query, NULL for a standalone internal evaluation
	 * @param catalog            the catalog the query is executed against
	 * @param entityCollection   collection targeted by the query, NULL for catalog-wide queries
	 * @param evitaSession       the enveloping session, must be an {@link EvitaSession} instance whenever
	 *                           the parent has one
	 * @param evitaRequest       the request describing the query
	 * @param telemetry          root telemetry node of this nested tree, NULL when telemetry is not collected
	 * @param indexes            indexes available for the query, keyed by their {@link IndexKey}
	 * @param indexesByPk        the very same indexes keyed by their primary key
	 * @param cacheSupervisor    supervisor deciding which formulas get their results memoized
	 */
	public <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nullable QueryPlanningContext parentQueryContext,
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nonnull EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull Map<Integer, T> indexesByPk,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(
			parentQueryContext, catalog, entityCollection,
			evitaSession, evitaRequest, telemetry, indexes, indexesByPk,
			cacheSupervisor, null
		);
		// guard only when session is expected — nested contexts during session-less evaluation
		// (WAL replay) inherit the null session from the parent and should not assert
		if (parentQueryContext == null || parentQueryContext.evitaSession != null) {
			Assert.isPremiseValid(evitaSession instanceof EvitaSession, "The session must be an instance of EvitaSession!");
		}
	}

	/**
	 * Creates a session-optional context for internal index-only evaluation (e.g., facet expression re-evaluation
	 * during WAL replay where no session is available). The session is nullable — cache analysis and binary format
	 * checks gracefully degrade when absent. All other query planning features (indexes, schema, formula
	 * construction) work normally.
	 *
	 * No telemetry is collected by such a context - there is no client to report it to - and no {@link FinishedEvent}
	 * is emitted, because this evaluation is not a client query.
	 *
	 * The raw `Map` parameters are deliberate: the caller of this path holds the indexes in a differently
	 * parameterized map and the generic signature of the sibling constructors would force an unchecked cast on
	 * the call site instead of here.
	 *
	 * **Do not use for normal query processing** — use the public constructors that enforce a non-null session.
	 *
	 * @param catalog           the catalog instance
	 * @param entityCollection  the entity collection being queried (nullable for catalog-level queries)
	 * @param evitaSession      the session, or null when no session context is available (WAL replay)
	 * @param evitaRequest      the request describing the query
	 * @param indexes           the index map for formula resolution, keyed by {@link IndexKey}
	 * @param indexesByPk       the index map keyed by primary key
	 * @param cacheSupervisor   the cache supervisor (tolerates null session)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public QueryPlanningContext(
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nullable EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Map indexes,
		@Nonnull Map indexesByPk,
		@Nonnull CacheSupervisor cacheSupervisor
	) {
		this(null, catalog, entityCollection, evitaSession, evitaRequest, null, indexes, indexesByPk, cacheSupervisor, null);
	}

	/**
	 * The single constructor every public one funnels into - the only place where the context state is actually
	 * assembled. Two things happening here are relied upon everywhere else in this class:
	 *
	 * - **telemetry is opt-in.** The stack is seeded only when a root `telemetry` node is passed in; when it is
	 *   NULL the stack stays empty forever and every `pushStep`/`popStep` call degenerates into a no-op. Telemetry
	 *   thus imposes no cost on queries that did not ask for it.
	 * - **a session that is not an {@link EvitaSession} is downgraded to no session at all.** The public
	 *   constructors assert against that, but the assertion can only run *after* this constructor completed
	 *   (Java requires the `this(...)` call to come first), so this method must cope with the value on its own.
	 *
	 * @param parentQueryContext context of the enclosing query, NULL for a top level one
	 * @param catalog            the catalog the query is executed against
	 * @param entityCollection   collection targeted by the query, NULL for catalog-wide queries
	 * @param evitaSession       the enveloping session, NULL when the query runs outside any session
	 * @param evitaRequest       the request describing the query
	 * @param telemetry          root telemetry node, NULL disables telemetry collection entirely
	 * @param indexes            indexes available for the query, keyed by their {@link IndexKey}
	 * @param indexesByPk        the very same indexes keyed by their primary key
	 * @param cacheSupervisor    supervisor deciding which formulas get their results memoized
	 * @param event              metric event to be completed when the query finishes, NULL for nested and
	 *                           internal evaluations that must not be reported as client queries
	 */
	private <S extends IndexKey, T extends Index<S>> QueryPlanningContext(
		@Nullable QueryPlanningContext parentQueryContext,
		@Nonnull Catalog catalog,
		@Nullable EntityCollection entityCollection,
		@Nullable EvitaSessionContract evitaSession,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable QueryTelemetry telemetry,
		@Nonnull Map<S, T> indexes,
		@Nonnull Map<Integer, T> indexesByPk,
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
		this.evitaSession = evitaSession instanceof EvitaSession es ? es : null;
		this.evitaRequest = evitaRequest;
		if (parentQueryContext == null) {
			// when debug mode is enabled we need to enforce the main plan to be non-cached
			// the cached variants needs to be derived from it
			// the same policy serves `PREFER_INDEX_SCAN`, which needs the prefetch denied so the query resolves
			// the indexes - and gets the cache bypass along with it, which keeps that resolution observable
			this.planningPolicy = isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
				|| isDebugModeEnabled(DebugMode.PREFER_INDEX_SCAN) ?
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
		//noinspection unchecked
		this.indexesByPk = (Map<Integer, Index<?>>) indexesByPk;
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
	 *
	 * When it does not, the query may match entities of several collections whose primary keys overlap, and
	 * the whole primary key masking machinery around
	 * {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)} kicks in.
	 */
	public boolean isEntityTypeKnown() {
		return this.entityType != null;
	}

	/**
	 * Records that this query asked for one capability of one schema element - `filterable()` on `ean`, `sortable()` on
	 * a compound, and so on - at the moment a translator was handed the schema it needs to build its part of the plan.
	 *
	 * # Requested, not chosen
	 *
	 * The claim recorded here is *"the query named this element and needed this flag"*, which stays true no matter
	 * which candidate index set the planner ends up costing cheapest. That is deliberately a different question from
	 * the one {@link io.evitadb.index.IndexActivity} answers, and {@link SchemaCapabilityUsage} states the difference
	 * in full.
	 *
	 * # Attribution, and the case this deliberately drops
	 *
	 * Only elements of **this context's own collection** are recorded, which is what `owner` is checked for. A lookup
	 * that resolved against the catalog schema alone belongs elsewhere and has its own method -
	 * {@link #recordRequestedGlobalCapability(String, Capability, Scope)} - so one call site is left passing through
	 * without recording anything, and it is a known gap rather than an oversight: **a filter or an ordering evaluated
	 * against another collection's structures** (a nested query behind `referenceHaving`, an ordering by a referenced
	 * entity's property) would have to count against *that* collection's registry, and the context that owns it is not
	 * the one whose plan gets built.
	 *
	 * Counting it here would attribute the request to the wrong schema, which is worse than not counting it: the
	 * number exists to decide whether a flag can be dropped, and a request attributed to the wrong element protects
	 * the wrong flag while leaving the right one looking dead.
	 *
	 * @param owner         the entity schema declaring the element, as the caller resolved it - a schema of another
	 *                      collection is silently ignored
	 * @param containerName name of the reference declaring the element, or NULL when the entity declares it directly
	 * @param elementKind   whether the element is an attribute or a sortable attribute compound
	 * @param elementName   name of the element, canonical as the schema spells it
	 * @param capability    the flag the query needed
	 * @param scope         the scope whose indexes maintain it
	 */
	public void recordRequestedCapability(
		@Nonnull EntitySchemaContract owner,
		@Nullable String containerName,
		@Nonnull ElementKind elementKind,
		@Nonnull String elementName,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		final EntityCollection collection = this.entityCollection;
		if (collection == null || !owner.getName().equals(this.entityType)) {
			return;
		}
		// bail before the key is minted: the planner translates the filter once per candidate index set, so this runs
		// N times per logical query and each run would otherwise allocate a `SchemaCapabilityKey` and hash it into the
		// registry. That is the per-query cost `server.usageStatisticsTracking: false` exists to remove
		if (!this.catalog.isUsageStatisticsTracked()) {
			return;
		}
		registerRequestedCapability(
			collection.getUsageRegistry().resolve(
				new SchemaCapabilityKey(elementKind, containerName, elementName, capability, scope)
			)
		);
	}

	/**
	 * Records that this query asked for one flag the **entity itself** declares - its `withHierarchy()` or its
	 * `withPrice()` - across every scope the lookup covered.
	 *
	 * A convenience over {@link #recordRequestedCapability}, and the reason it exists is that the sites calling it are
	 * scattered: hierarchy and price are verified in a dozen translators rather than behind one accessor the way
	 * attributes are. Keeping the element plumbing here rather than at each of them is what stops the twelfth site
	 * from spelling the key slightly differently to the first.
	 *
	 * Like every request-side path this only *accumulates*: the count is raised once per logical query when the
	 * winning plan is built, never once per candidate plan the planner weighed. See
	 * {@link #registerRequestedCapability}.
	 *
	 * @param owner      the entity schema the flag was verified against - a lookup that resolved against another
	 *                   collection records nothing, exactly as a filter evaluated against another collection does
	 * @param capability the flag the query needed - `HIERARCHY_INDEXED` or `PRICE_INDEXED`
	 * @param scopes     the scopes the query asked for
	 */
	public void recordRequestedEntityCapability(
		@Nonnull EntitySchemaContract owner,
		@Nonnull Capability capability,
		@Nonnull Set<Scope> scopes
	) {
		final String entityType = owner.getName();
		for (final Scope scope : scopes) {
			recordRequestedCapability(owner, null, ElementKind.ENTITY, entityType, capability, scope);
		}
	}

	/**
	 * Records that this query asked for one flag a **reference itself** declares - its `indexed()`, `faceted()` or
	 * `bucketed()` - across every scope the lookup covered.
	 *
	 * The reference is the element here, so it is named by `elementName` and the container stays null. That is the
	 * opposite arrangement from a request about an attribute *of* the reference, which names it as the container;
	 * see {@link io.evitadb.index.usage.SchemaCapabilityKey#reference}.
	 *
	 * @param owner         the entity schema declaring the reference - see {@link #recordRequestedEntityCapability}
	 *                      for why a foreign owner records nothing
	 * @param referenceName name of the reference the query named
	 * @param capability    the flag the query needed - `INDEXED`, `FACETED` or `BUCKETED`
	 * @param scopes        the scopes the query asked for
	 */
	public void recordRequestedReferenceCapability(
		@Nonnull EntitySchemaContract owner,
		@Nonnull String referenceName,
		@Nonnull Capability capability,
		@Nonnull Set<Scope> scopes
	) {
		for (final Scope scope : scopes) {
			recordRequestedCapability(owner, null, ElementKind.REFERENCE, referenceName, capability, scope);
		}
	}

	/**
	 * Records that this query asked for one capability of an attribute the **catalog schema** declares - the
	 * counterpart of {@link #recordRequestedCapability} for the lookups that never reach an entity schema at all.
	 *
	 * A query naming no collection resolves its attributes against the catalog schema and is answered from the
	 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} the catalog keeps, so the request cannot be attributed to
	 * any one collection: it is the catalog's schema that declares the flag and a catalog schema mutation that would
	 * drop it. The holder therefore comes from the catalog's own registry, and everything after that - the
	 * deduplication, the accumulator, the flush - is shared with the collection-level path, because where the holder
	 * was resolved is the only difference between the two.
	 *
	 * # Callers must pass only a capability the catalog itself maintains
	 *
	 * In practice `FILTERABLE` and `UNIQUE`, both of which the catalog's global unique index costs. Passing
	 * `SORTABLE` would mint a row whose update count can never leave zero - no write files `SORTABLE` into this
	 * registry, because a global attribute's sort index belongs to each collection declaring it - and a
	 * permanently-zero maintenance count reads as *"drop this flag"* about a flag that is actively maintained. The
	 * filter that enforces this lives at the sole caller,
	 * {@link io.evitadb.core.query.AttributeSchemaAccessor#recordRequestedTraits}, which is where the
	 * `(trait, owner)` pair being translated makes the reason legible; a second caller has to honour the same rule.
	 *
	 * @param attributeName name of the global attribute, canonical as the catalog schema spells it
	 * @param capability    the flag the query needed - `FILTERABLE` or `UNIQUE`, never `SORTABLE`
	 * @param scope         the scope whose indexes maintain it
	 */
	public void recordRequestedGlobalCapability(
		@Nonnull String attributeName,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		// same bail as the collection-level path, and for the same per-candidate-plan reason
		if (!this.catalog.isUsageStatisticsTracked()) {
			return;
		}
		registerRequestedCapability(
			this.catalog.getUsageRegistry().resolve(
				new SchemaCapabilityKey(ElementKind.ATTRIBUTE, null, attributeName, capability, scope)
			)
		);
	}

	/**
	 * Adds one already-resolved holder to this query's accumulator, unless the same holder is in it already.
	 *
	 * **The holder is stored, never the key.** Everything after this point - the flush the winning plan performs - is
	 * then an iterate-and-increment with no hashing, no map lookup and no allocation, which is the whole reason the
	 * resolve happens once, here, at the moment the schema is looked up anyway.
	 *
	 * # Why the duplicate check is a linear scan
	 *
	 * The planner translates the filter **once per candidate index set**, so the same holder arrives here as many
	 * times as there are candidate plans - and the count has to come out as one per logical query regardless. Rejecting
	 * the duplicate on the way in rather than at the flush keeps the list bounded by the number of *distinct*
	 * capabilities the query names, which is single digits for anything a person writes: a scan over a handful of
	 * references beats both a hash set (which allocates a node per entry and hashes each arrival) and a deferred
	 * deduplication (which would let a repeatedly-translated filter grow the list without bound).
	 *
	 * @param holder the holder counting the capability that was just requested
	 */
	public void registerRequestedCapability(@Nonnull SchemaCapabilityUsage holder) {
		List<SchemaCapabilityUsage> accumulator = this.requestedCapabilities;
		if (accumulator == null) {
			// sized for a hand-written query - a filter and an ordering over a few attributes each
			accumulator = new ArrayList<>(8);
			this.requestedCapabilities = accumulator;
		} else {
			// identity, not equality: two holders are the same capability exactly when the registry handed back the
			// same instance, and SchemaCapabilityUsage has no value semantics to compare by
			for (SchemaCapabilityUsage schemaCapabilityUsage : accumulator) {
				if (schemaCapabilityUsage == holder) {
					return;
				}
			}
		}
		accumulator.add(holder);
	}

	/**
	 * Hands over everything this query has requested and leaves the context holding nothing.
	 *
	 * **The emptying is the deduplication of last resort.** A logical query may build its preferred plan more than
	 * once - the two verification debug modes build it again after executing every alternative - and a drain that left
	 * the list behind would count that query twice. Because the accumulator is handed over rather than copied, the
	 * second drain finds nothing and the second build counts nothing.
	 *
	 * # What that leaves standing, and why it is the honest reading
	 *
	 * The emptying makes the count *once per drain of what had been accumulated by then*, not *once per context*: a
	 * capability registered **after** a build has already drained would be counted again by the next build on the same
	 * context. Nothing a production session does can reach that, because everything that consults the schema runs
	 * before the single build that ends {@link QueryPlanner#planQuery}. One debug-only path could:
	 * {@link io.evitadb.api.query.require.DebugMode#VERIFY_POSSIBLE_CACHING_TREES} equips each cacheable variant of
	 * the formula with a sorter of its own *after* the preferred plan was built, and planning an ordering re-registers
	 * what it names.
	 *
	 * Suppressing that with a one-way "already flushed" latch was deliberately not done, and the asymmetry is the
	 * reason: this count exists to answer *"would dropping this flag break a query?"*, where an over-count merely
	 * protects a flag a little too eagerly, while an under-count makes a used flag look dead and invites somebody to
	 * drop it. A latch buys exactness under a debug mode that already multiplies every per-index reading, at the price
	 * of silently discarding the requests of any future caller that legitimately plans further work on a context whose
	 * plan is already built - trading a debug-only over-count for an under-count nobody would notice. The caveat
	 * therefore reads exactly like {@link io.evitadb.index.IndexActivity}'s: exact arithmetic on these readings
	 * requires a session with no verification debug mode enabled.
	 *
	 * @return the distinct holders this query requested, in registration order; empty when it requested none
	 */
	@Nonnull
	public List<SchemaCapabilityUsage> drainRequestedCapabilities() {
		final List<SchemaCapabilityUsage> accumulated = this.requestedCapabilities;
		if (accumulated == null) {
			return List.of();
		}
		this.requestedCapabilities = null;
		return accumulated;
	}

	/**
	 * Prefetching is possible only when **both** conditions hold: this context is not a nested one (a sub-query
	 * result never reaches the output, so prefetching for it would be wasted work forcing eager evaluation), and
	 * the {@link PlanningPolicy} in effect allows it - some debug policies deliberately forbid prefetch to force
	 * the query through the index resolution path.
	 */
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
	 *
	 * Unlike {@link #getIndexIfExists(IndexKey, Class)}, which is limited to the indexes this query was set up
	 * with, this method resolves the owning collection by name and can therefore reach into a **different**
	 * collection than the query targets. A missing index is a legal outcome (nothing was indexed for that key
	 * yet) and yields an empty result; an index of unexpected type is a programming error and fails the premise
	 * check.
	 *
	 * @param entityType     entity type owning the requested index
	 * @param entityIndexKey key of the requested index
	 * @param indexType      expected index implementation, verified at runtime
	 * @return the index or empty result when the collection holds no index of that key
	 * @throws EntityCollectionRequiredException when there is no collection of the passed entity type
	 */
	@Nonnull
	public <T extends EntityIndex> Optional<T> getEntityIndex(@Nonnull String entityType, @Nonnull EntityIndexKey entityIndexKey, @Nonnull Class<T> indexType) {
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
	 * Returns {@link EntityIndex} of external entity type by its primary key.
	 *
	 * The primary key is expected to come from an index that already knows the index exists (typically
	 * {@link ReferencedTypeEntityIndex} listing its reduced indexes), therefore a missing index is treated as
	 * a programming error rather than an ordinary "not found" outcome.
	 *
	 * @param indexPrimaryKey primary key of the requested index
	 * @param indexType       expected index implementation, verified at runtime
	 * @return the requested index, never NULL
	 */
	@Nonnull
	public <T extends EntityIndex> T getEntityIndexByPrimaryKey(int indexPrimaryKey, @Nonnull Class<T> indexType) {
		final Index<?> index = this.indexesByPk.get(indexPrimaryKey);
		Assert.isPremiseValid(
			indexType.isInstance(index),
			() -> "Expected index of type " + indexType + " but got " + (index == null ? "NULL" : index.getClass()) + "!"
		);
		//noinspection unchecked
		return (T) index;
	}

	/**
	 * Returns {@link EntityIndex} by its key, looked up among the indexes this query was set up with.
	 *
	 * A {@link CatalogIndexKey} is special-cased: catalog indexes are not part of the per-query index map (they
	 * are not owned by any collection), so they are fetched straight from the catalog by scope. Note that
	 * the `indexType` is **not** verified on that branch.
	 *
	 * @param indexKey  key of the requested index
	 * @param indexType expected index implementation, verified at runtime for non-catalog indexes
	 * @return the index or empty result when no index of that key is available to this query
	 */
	@Nonnull
	public <S extends IndexKey, T extends Index<S>> Optional<T> getIndexIfExists(@Nonnull S indexKey, @Nonnull Class<T> indexType) {
		if (indexKey instanceof CatalogIndexKey cik) {
			//noinspection unchecked
			return ofNullable((T) this.catalog.getCatalogIndex(cik.scope()));
		} else {
			final Index<?> index = this.indexes.get(indexKey);
			Assert.isPremiseValid(
				index == null || indexType.isInstance(index),
				() -> "Expected index of type " + indexType + " but got " + (index == null ? "NULL" : index.getClass()) + "!"
			);
			//noinspection unchecked
			return ofNullable((T) index);
		}
	}

	/**
	 * Retrieves a stream of {@link ReducedEntityIndex} objects based on the provided scope, referenced
	 * entity ID, entity schema, reference schema, and a supplier for handling missing indexes.
	 *
	 * The lookup path depends on the reference cardinality. When duplicates are allowed, a single referenced
	 * entity may be covered by several reduced indexes, so they have to be enumerated through
	 * the `REFERENCED_ENTITY_TYPE` index; otherwise there is at most one and it is addressed directly by
	 * the `REFERENCED_ENTITY` key built from the reference key.
	 *
	 * @param scope the scope within which the entity indexes are retrieved
	 * @param referencedEntityId the ID of the referenced entity
	 * @param entitySchema the schema of the entity used for configuration
	 * @param referenceSchema the schema of the reference defining the relationship to the referenced entity
	 * @param missingIndexSupplier a supplier function to provide a fallback index when a requested index is missing;
	 *                             it may return NULL, in which case an empty stream is produced
	 * @return a stream of {@link ReducedEntityIndex} corresponding to the specified query criteria
	 */
	@Nonnull
	public Stream<ReducedEntityIndex> getReducedEntityIndexes(
		@Nonnull Scope scope,
		int referencedEntityId,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull BiFunction<EntitySchemaContract, EntityIndexKey, ReducedEntityIndex> missingIndexSupplier
	) {
		final String referenceName = referenceSchema.getName();
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final EntityIndexKey entityIndexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
			);
			return getEntityIndex(entitySchema.getName(), entityIndexKey, ReferencedTypeEntityIndex.class)
				.map(referencedTypeEntityIndex -> {
					final int[] allReducedEntityIndexPks = referencedTypeEntityIndex.getAllReferenceIndexes(
						referencedEntityId
					);
					return Arrays.stream(allReducedEntityIndexPks)
						.mapToObj(pk -> getEntityIndexByPrimaryKey(pk, ReducedEntityIndex.class));
				})
				.orElseGet(() -> {
					final ReducedEntityIndex missingIndex = missingIndexSupplier.apply(entitySchema, entityIndexKey);
					return missingIndex == null ? Stream.empty() : Stream.of(missingIndex);
				});
		} else {
			final EntityIndexKey entityIndexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY,
				scope,
				new RepresentativeReferenceKey(
					new ReferenceKey(referenceName, referencedEntityId)
				)
			);
			return getEntityIndex(entitySchema.getName(), entityIndexKey, ReducedEntityIndex.class)
				.or(() -> Optional.ofNullable(missingIndexSupplier.apply(entitySchema, entityIndexKey)))
				.stream();
		}
	}

	/**
	 * Retrieves a stream of {@link ReducedGroupEntityIndex} instances corresponding to the group entity
	 * identified by `groupEntityId` within the given scope. This is the group-level counterpart of
	 * {@link #getReducedEntityIndexes(Scope, int, EntitySchemaContract, ReferenceSchemaContract, BiFunction)}.
	 *
	 * It differs from that sibling in one important aspect: **there is no cardinality branch here.** A single
	 * group is routinely shared by many references, so the group indexes are always looked up through
	 * the `REFERENCED_GROUP_ENTITY_TYPE` index, which resolves one group primary key to all reduced group
	 * indexes built for it - the direct `REFERENCED_GROUP_ENTITY` key would only ever find one of them.
	 *
	 * @param scope the scope within which the group entity indexes are retrieved
	 * @param groupEntityId the ID of the group entity
	 * @param entitySchema the schema of the entity used for configuration
	 * @param referenceSchema the schema of the reference defining the relationship to the group entity
	 * @param missingIndexSupplier a supplier function to provide a fallback index when a requested index is missing;
	 *                             it may return NULL, in which case an empty stream is produced
	 * @return a stream of {@link ReducedGroupEntityIndex} corresponding to the specified query criteria
	 */
	@Nonnull
	public Stream<ReducedGroupEntityIndex> getReducedGroupEntityIndexes(
		@Nonnull Scope scope,
		int groupEntityId,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull BiFunction<EntitySchemaContract, EntityIndexKey, ReducedGroupEntityIndex> missingIndexSupplier
	) {
		final String referenceName = referenceSchema.getName();
		// always use the type index path to locate all reduced group indexes for a given group PK
		final EntityIndexKey entityIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, scope, referenceName
		);
		return getEntityIndex(entitySchema.getName(), entityIndexKey, ReferencedTypeEntityIndex.class)
			.map(referencedTypeEntityIndex -> {
				final int[] allReducedEntityIndexPks = referencedTypeEntityIndex.getAllReferenceIndexes(
					groupEntityId
				);
				return Arrays.stream(allReducedEntityIndexPks)
					.mapToObj(pk -> getEntityIndexByPrimaryKey(pk, ReducedGroupEntityIndex.class));
			})
			.orElseGet(() -> {
				final ReducedGroupEntityIndex missingIndex =
					missingIndexSupplier.apply(entitySchema, entityIndexKey);
				return missingIndex == null ? Stream.empty() : Stream.of(missingIndex);
			});
	}

	/**
	 * Adds new step of query evaluation.
	 *
	 * The new step becomes a child of the currently open one and the new innermost step, so the sequence of
	 * `pushStep` / `popStep` calls made while planning builds the telemetry tree. **When telemetry was not
	 * requested the stack is empty and this method does nothing** - which also means the paired
	 * {@link #popStep()} does nothing, so the two stay balanced without the caller ever testing for it.
	 * Callers are still expected to pop in a `finally` block: a step that is never popped is closed only by
	 * {@link #finalizeTelemetry()}, and its reported duration then stretches all the way to the end of the query.
	 *
	 * @param phase phase of the query evaluation the new step measures
	 */
	public void pushStep(@Nonnull QueryPhase phase) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.push(
				this.telemetryStack.peek().addStep(phase)
			);
		}
	}

	/**
	 * Adds new step of query evaluation, described by a message that tells the reader *which* concrete piece of
	 * work the step covers (which index, which nested filter, ...).
	 *
	 * The message is resolved only when telemetry is actually being collected. There is deliberately no overload
	 * taking a plain `String` - it would let the caller build the message before this guard is reached, which is
	 * exactly the cost telemetry must not impose on a query that did not ask for it.
	 *
	 * @param phase           phase of the query evaluation the new step measures
	 * @param messageSupplier description of the step, invoked only when telemetry is being collected
	 */
	public void pushStep(@Nonnull QueryPhase phase, @Nonnull Supplier<String> messageSupplier) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.push(
				this.telemetryStack.peek().addStep(phase, messageSupplier.get())
			);
		}
	}

	/**
	 * Returns the innermost step that is currently open, i.e. the node any measurement taken right now belongs to.
	 * Used by code that wants to annotate the running step with a value it just computed rather than open
	 * a step of its own.
	 *
	 * @return the open step, or NULL when telemetry is not being collected - callers must handle the NULL
	 *         instead of assuming telemetry is on
	 */
	@Nullable
	public QueryTelemetry getCurrentStep() {
		if (!this.telemetryStack.isEmpty()) {
			return this.telemetryStack.peek();
		}
		return null;
	}

	/**
	 * Returns true when telemetry is being collected **and** the query asked for the formula plan - the guard a
	 * caller must consult before walking a formula tree to describe it.
	 *
	 * Both halves are load-bearing. The stack test is what makes it false for a query without telemetry (and for
	 * a dry run, whose planning context is never seeded with a root); the request test is what keeps plain
	 * `queryTelemetry()` from paying for a structure it did not ask for.
	 *
	 * @return true when a plan recorded on the current step ends up in the response
	 */
	public boolean isTelemetryPlanCollected() {
		return !this.telemetryStack.isEmpty() && this.evitaRequest.isQueryTelemetryPlanRequested();
	}

	/**
	 * Finishes current query evaluation step, recording the time spent in it and making its parent the current
	 * step again. Does nothing when telemetry is not being collected, which is what allows call sites to pop
	 * unconditionally in a `finally` block.
	 */
	public void popStep() {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.pop().finish();
		}
	}

	/**
	 * Finishes current query evaluation step, describing the outcome it arrived at. This is what distinguishes
	 * it from {@link #popStep()}: the description is only known *after* the work is done (the index that won,
	 * the estimated cost that decided it), so it cannot be supplied at push time.
	 *
	 * The message is resolved only when telemetry is actually being collected - see {@link #pushStep(QueryPhase,
	 * Supplier)} for why no plain `String` overload exists.
	 *
	 * @param messageSupplier description of the outcome, invoked only when telemetry is being collected
	 */
	public void popStep(@Nonnull Supplier<String> messageSupplier) {
		if (!this.telemetryStack.isEmpty()) {
			this.telemetryStack.pop().finish(messageSupplier.get());
		}
	}

	/**
	 * Returns {@link QueryTelemetry} root or throws an exception if no telemetry is initialized.
	 *
	 * The root is the *bottom* of the stack - the node seeded when this context was created. `push()` on
	 * an {@link ArrayDeque} is `addFirst()`, so the head of the deque is the innermost still-open step
	 * (that is what {@link #getCurrentStep()} returns); reading the head here would hand out whichever step
	 * happens to be open at the time of the call rather than the root of the tree.
	 *
	 * @return the root of the telemetry tree, with all steps collected so far hanging beneath it
	 * @throws GenericEvitaInternalError when telemetry is not being collected, or has already been drained by
	 *                                   {@link #finalizeTelemetry()}
	 */
	@Nonnull
	public QueryTelemetry getTelemetryRoot() {
		Assert.isPremiseValid(!this.telemetryStack.isEmpty(), "The telemetry is not initialized!");
		return this.telemetryStack.getLast();
	}

	/**
	 * Finalizes {@link QueryTelemetry} or throws an exception. This method can be called only once, because it
	 * empties the internal telemetry stack.
	 *
	 * Steps are still open at this point - extra results are fabricated from within the execution phase, so
	 * the phases enclosing that fabrication cannot have been popped yet. All of them are closed here, innermost
	 * first, which is why their measured durations include the extra result computation itself.
	 *
	 * Retrieve the tree with {@link #getTelemetryRoot()} **before** calling this method: afterwards the stack is
	 * empty and both methods fail their premise check.
	 *
	 * @throws GenericEvitaInternalError when telemetry is not being collected, or was already finalized
	 */
	public void finalizeTelemetry() {
		Assert.isPremiseValid(!this.telemetryStack.isEmpty(), "The telemetry has been already retrieved!");

		// there may be some steps still open at the time extra results is fabricated
		QueryTelemetry rootStep;
		do {
			rootStep = this.telemetryStack.pop();
			rootStep.finish();
		} while (!this.telemetryStack.isEmpty());
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getFilterBy()}.
	 */
	@Nullable
	public FilterBy getFilterBy() {
		return this.evitaRequest.getQuery().getFilterBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getOrderBy()}.
	 */
	@Nullable
	public OrderConstraint getOrderBy() {
		return this.evitaRequest.getQuery().getOrderBy();
	}

	/**
	 * Shorthand for {@link EvitaRequest#getQuery()} and {@link Query#getRequire()}.
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
	 * Returns the entity-type name ↔ compact primary key resolver backed by the catalog targeted by this query.
	 * Used by the globally-unique attribute filter translators to decode the entity type stored inside the
	 * global unique index's packed tuples.
	 */
	@Nonnull
	public EntityTypeClassifierResolver getEntityTypeClassifierResolver() {
		return this.catalog;
	}

	/**
	 * Returns the internal schema of the collection this query targets, resolving and caching it on first use.
	 *
	 * @return internal schema of the target collection
	 * @throws EntityCollectionRequiredException when the query does not target a single known collection - check
	 *                                           {@link #isEntityTypeKnown()} first if that is a possibility
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
		return getIndexIfExists(GLOBAL_INDEX_KEY.get(scope), GlobalEntityIndex.class);
	}

	/**
	 * Returns global {@link GlobalEntityIndex} of the collection or throws an exception.
	 */
	@Nonnull
	public GlobalEntityIndex getGlobalEntityIndex(@Nonnull Scope scope) {
		return getIndexIfExists(GLOBAL_INDEX_KEY.get(scope), GlobalEntityIndex.class)
			.orElseThrow(() -> new GenericEvitaInternalError("Global index of entity unexpectedly not found!"));
	}

	/**
	 * Returns the global {@link GlobalEntityIndex} of **another** collection in the given scope - the sibling of
	 * {@link #getGlobalEntityIndexIfExists(Scope)} for entity types this query does not target itself.
	 *
	 * @param entityType entity type whose global index is requested
	 * @param scope      scope the index belongs to
	 * @return the global index or empty result when the collection has none in that scope
	 */
	@Nonnull
	public Optional<GlobalEntityIndex> getGlobalEntityIndexIfExists(@Nonnull String entityType, @Nonnull Scope scope) {
		return getEntityIndex(entityType, GLOBAL_INDEX_KEY.get(scope), GlobalEntityIndex.class);
	}

	/**
	 * Analyzes the input formula for cacheable / cached formulas and replaces them with appropriate counterparts (only
	 * if cache is enabled).
	 *
	 * The formula is returned untouched when there is no session or the query targets no particular entity type -
	 * the cache is keyed per entity type and accounted per session, so neither can be skipped. Callers therefore
	 * must not assume the returned tree differs from the input one.
	 *
	 * @param formula formula tree to be analysed
	 * @return the same tree, or an equivalent one with cacheable / cached counterparts substituted in
	 */
	@Nonnull
	public Formula analyse(@Nonnull Formula formula) {
		final String theEntityType = this.evitaRequest.getEntityType();
		if (this.evitaSession != null && theEntityType != null) {
			return this.cacheSupervisor.analyse(this.evitaSession, theEntityType, formula);
		} else {
			return formula;
		}
	}

	/**
	 * Analyzes the input extra result computer for cacheable / cached extra result computers and replaces them with
	 * appropriate counterparts (only if cache is enabled).
	 *
	 * Unlike {@link #analyse(Formula)}, the substitution is routed through the {@link PlanningPolicy}, which is what
	 * lets a debug run force caching on or off for extra results. The computer is returned untouched when there is
	 * no session or no target entity type.
	 *
	 * @param computer extra result computer to be analysed
	 * @return the same computer, or its cacheable / cached counterpart
	 */
	@Nonnull
	public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(@Nonnull T computer) {
		final String theEntityType = this.evitaRequest.getEntityType();
		if (this.evitaSession != null && theEntityType != null) {
			return this.planningPolicy.analyse(this.cacheSupervisor, this.evitaSession, theEntityType, computer);
		} else {
			return computer;
		}
	}

	/**
	 * Returns true if session is switched to binary format output.
	 *
	 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
	 */
	public boolean isRequiresBinaryForm() {
		return this.evitaSession != null && this.evitaSession.isBinaryFormat();
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
	 * The cache always lives on the **root** context - a nested context forwards the call to its parent - so
	 * a formula computed while planning one candidate index is reused by every other candidate and by every
	 * sub-query of the same client query.
	 *
	 * @param entityIndexes       indexes the formula is going to be evaluated against; their ids form part of
	 *                            the cache key, because the very same constraint yields a different result on
	 *                            a different index set
	 * @param constraint          caching key for which the lambda should be invoked only once
	 * @param formulaSupplier     the lambda that creates the formula
	 * @param additionalCacheKeys extra discriminators for callers whose result depends on something beyond
	 *                            the index set and the constraint; they are negated before being merged with
	 *                            the index ids, so that they land in the negative half of the key space and
	 *                            do not collide with the (positive) index ids
	 * @return created formula, already initialized with {@link #internalExecutionContext}
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
	 * This is the single entry point into the masking machinery and the place that lazily allocates both
	 * reference indexes - which is why the masking function itself is not exposed.
	 *
	 * @param entityReferences references to be translated into (possibly virtual) primary keys
	 * @return bitmap of primary keys usable in the formula tree
	 * @see #getOrRegisterEntityReferenceMaskId(EntityReferenceContract) for more information
	 */
	@Nonnull
	public final Bitmap translateEntityReference(@Nonnull EntityReferenceContract... entityReferences) {
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
	 *
	 * This is the inverse of the masking done during filtering and the last step before primary keys leave
	 * the query. A key that was never masked (or a query that masked nothing at all) is paired with the target
	 * collection's entity type, which is the only sensible interpretation in that case.
	 *
	 * @param primaryKey virtual or real primary key
	 * @return entity reference the key stands for
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
	 * Returns true if at least one primary key was masked by
	 * {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)}, i.e. whether the primary keys travelling
	 * through the formula tree are virtual and have to be translated back before they leave the query.
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
	public Optional<EntityReferenceContract> getEntityReferenceIfExist(int primaryKey) {
		return ofNullable(this.entityReferencePkIndex)
			.map(it -> it.get(primaryKey));
	}

	/**
	 * Returns virtual id assigned by {@link #getOrRegisterEntityReferenceMaskId(EntityReferenceContract)} or real
	 * primary key from {@link EntityContract#getPrimaryKey()}.
	 *
	 * Used when a prefetched entity body has to be matched against the bitmaps the formula tree works with. While
	 * masking is active the entity **must** already be registered - an unknown entity means the formula tree and
	 * the prefetched set disagree, and it fails loudly rather than silently producing a wrong key.
	 *
	 * @param entity entity to be translated
	 * @return primary key under which the entity is known inside this query
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
	 *
	 * Unlike {@link #translateToEntityReference(int)} it drops the entity type, so it is only usable where
	 * the collection is already known from the context. An unrecognized key is passed through unchanged.
	 *
	 * @param primaryKey virtual or real primary key
	 * @return real primary key of the entity
	 */
	public int translateToEntityPrimaryKey(int primaryKey) {
		if (this.entityReferencePkSequence > 0) {
			final EntityReferenceContract referencedEntity = this.entityReferencePkIndex.get(primaryKey);
			return referencedEntity == null ? primaryKey : referencedEntity.getPrimaryKey();
		} else {
			return primaryKey;
		}
	}


	/**
	 * Sets resolved hierarchy root nodes formula to be shared among filter and requirement phase. Can be called
	 * only once per context - two different root sets within one query would mean the filter and the hierarchy
	 * statistics disagree about what the hierarchy is.
	 *
	 * @param rootHierarchyNodesFormula formula computing primary keys of the hierarchy roots
	 */
	public void setRootHierarchyNodesFormula(@Nonnull Formula rootHierarchyNodesFormula) {
		Assert.isPremiseValid(this.rootHierarchyNodesFormula == null, "The hierarchy filtering formula can be set only once!");
		this.rootHierarchyNodesFormula = rootHierarchyNodesFormula;
	}

	/**
	 * Sets resolved hierarchy having/exclusion predicate to be shared among filter and requirement phase. Setting
	 * it repeatedly is tolerated as long as the predicate is equal to the one already stored - the same constraint
	 * may legitimately be resolved by more than one translator - but a *different* predicate is rejected.
	 *
	 * @param hierarchyHavingPredicate predicate deciding which hierarchy nodes are traversable
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
	 * Determines whether the specified relation type matches the given facet group relation criteria. Shared
	 * implementation of the four `isFacetGroup*` methods, which differ only in the relation type they ask about
	 * and the request accessor that carries the settings for it.
	 *
	 * The decision has three outcomes worth knowing about:
	 *
	 * - the query says nothing about this relation for this reference - the request-wide default for the given
	 *   `level` decides
	 * - the query requests the relation **without** a filter - it applies to every group, hence `true`
	 * - the query requests the relation **with** a filter - the filter is planned into a predicate (memoized in
	 *   {@link #facetRelationTuples}, since it is asked about many groups in a row) and the group is tested
	 *   against it; a facet with no group at all cannot match such a filter and gets `false`
	 *
	 * @param relationType the type of the facet relation to be checked
	 * @param referenceSchema the schema of the reference to which the facet group belongs
	 * @param groupId the identifier of the group being considered; can be null if no group is specified
	 * @param level the level of facet group relation that should be considered in the evaluation
	 * @param facetSettingsRetriever accessor pulling the settings of `relationType` for a reference name out of
	 *                               the request - this is what binds the shared implementation to one relation
	 * @return `true` if the relation type matches the facet group relation criteria, `false` otherwise
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
	 * Freezing the randomness is what makes the debug modes able to compare two plans for equality - a query
	 * ordering entities randomly would otherwise legitimately produce a different result on every run.
	 *
	 * When this context has no session, the created execution context is given an entity proxy factory that
	 * throws: a session-less evaluation may compute over indexes, but it can never materialize client-facing
	 * proxy instances.
	 *
	 * @param prefetchExecution flag that signalizes if the prefetching was executed and filtering should occur on
	 *                          prefetched entities
	 * @param frozenRandom      frozen random bytes to be used for the query execution
	 * @return new query execution context
	 */
	@Nonnull
	public QueryExecutionContext createExecutionContext(boolean prefetchExecution, @Nullable byte[] frozenRandom) {
		return new QueryExecutionContext(
			this, prefetchExecution, frozenRandom,
			this.evitaSession != null ? this.evitaSession::createEntityProxy : (type, entity) -> {
				throw new GenericEvitaInternalError("Entity proxy creation is not available without a session.");
			}
		);
	}

	/**
	 * Method retrieves already assigned masking id for the {@link EntityReference} or creates brand new. This virtual
	 * id is necessary because our filtering logic works with {@link Bitmap} objects that contains plain integers. In
	 * situation when no target entity collection is specified and filters targeting global attributes retrieves
	 * entities from various collections - their ids may overlap, and we need to keep them separated during computation.
	 * That's why we use such virtual ids during entire filtering and sorting process.
	 *
	 * When the entity type **is** known there is nothing to disambiguate, so the real primary key is returned
	 * unchanged and {@link #entityReferencePkSequence} stays at zero - which is exactly how the rest of the class
	 * recognizes that no translation back is needed.
	 *
	 * **Reachable only through {@link #translateEntityReference(EntityReferenceContract...)}**, which is what
	 * allocates the two reference indexes; calling it on a context where that never happened would hit a NULL
	 * index. Keep it package private for that reason.
	 *
	 * @param entityReference reference to be masked
	 * @return virtual primary key representing the reference within this query, or the real primary key when
	 *         the query targets a single known collection
	 */
	int getOrRegisterEntityReferenceMaskId(@Nonnull EntityReferenceContract entityReference) {
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
	 * A non-managed group type has no collection and therefore no real index of its own, yet the facet group
	 * filter still has to be planned against *something*. The stubs returned here know the set of existing group
	 * ids and nothing else - anything beyond that throws `EntityNotManagedException`, which is the intended way to
	 * tell the client that such a filter cannot be satisfied for a non-managed group.
	 *
	 * @param referenceName       name of the reference to retrieve groups from
	 * @param referencedGroupType type of the referenced group
	 * @return list of fake global entity indexes, one per requested scope that actually facets this reference
	 */
	@Nonnull
	private List<GlobalEntityIndex> getThrowingGlobalIndexesForNonManagedEntityTypeGroup(
		@Nonnull String referenceName,
		@Nonnull String referencedGroupType
	) {
		return getScopes().stream()
			.map(scope -> {
				final Optional<GlobalEntityIndex> refGlobalIndex = getGlobalEntityIndexIfExists(scope);
				return refGlobalIndex
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
	 *
	 * @param referenceName name of the reference the facet group belongs to
	 * @param relation      relation type the memoized predicate decides about
	 */
	private record FacetRelationTuple(
		@Nonnull String referenceName,
		@Nonnull FacetRelationType relation
	) {

	}

	/**
	 * The internal caching key.
	 *
	 * `equals` and `hashCode` are overridden on purpose: the record's generated implementations compare array
	 * components by identity, which would make every key unique and turn the cache into a memory leak that never
	 * hits.
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
