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

package io.evitadb.core.query.extraResult;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.hierarchy.HierarchyFormula;
import io.evitadb.core.query.algebra.prefetch.PrefetchFormulaVisitor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.RequireInScopeTranslator;
import io.evitadb.core.query.extraResult.translator.RequireTranslator;
import io.evitadb.core.query.extraResult.translator.facet.FacetSummaryOfReferenceTranslator;
import io.evitadb.core.query.extraResult.translator.facet.FacetSummaryTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceHistogramStatisticsTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryOfReferenceTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceSummaryTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyChildrenTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyFromNodeTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyFromRootTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyOfReferenceTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyOfSelfTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyParentsTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchySiblingsTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.AttributeHistogramTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.PriceHistogramTranslator;
import io.evitadb.core.query.extraResult.translator.reference.AssociatedDataContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.AttributeContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.EntityFetchTranslator;
import io.evitadb.core.query.extraResult.translator.reference.EntityGroupFetchTranslator;
import io.evitadb.core.query.extraResult.translator.reference.HierarchyContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.PriceContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceContentTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * Entry-point of the "planning" phase for `require` clause evaluation. Walks the {@link RequireConstraint} tree
 * produced by the query parser and dispatches each constraint to the matching {@link RequireConstraintTranslator}
 * registered in the {@link #TRANSLATORS} map. Translators emit {@link ExtraResultProducer} instances that are
 * collected and later executed against actual entity data during the fabrication phase.
 *
 * **Dispatch table** — the static `TRANSLATORS` map is populated once at class-load time and maps each require
 * constraint class to a single shared translator instance. Adding support for a new require constraint means
 * registering a new entry here.
 *
 * **Self-traversing translators** — translators that implement {@link SelfTraversingTranslator} are responsible
 * for dispatching their own children. The visitor skips automatic child traversal for these containers and
 * instead invokes `createProducer` directly, letting the translator decide when and how children are visited.
 * This is used by `ReferenceSummary` and `ReferenceSummaryOfReference` so they can pre-register their producer
 * before dispatching nested `histogramStatistics` children.
 *
 * **Processing scope stack** — the `scope` deque tracks contextual metadata (current requirement, active
 * {@link io.evitadb.dataType.Scope}s, reference schema, entity schema) that changes as the visitor descends into
 * nested containers. Translators push a new {@link ProcessingScope} via
 * {@link #executeInContext(RequireConstraint, java.util.function.Supplier, java.util.function.Supplier, java.util.function.Supplier)}
 * and pop it automatically on return. The root scope is initialised with the query's own active scopes and
 * null schemas (top-level, no reference context).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("deprecation")
public class ExtraResultPlanningVisitor implements ConstraintVisitor {
	private static final Map<Class<? extends RequireConstraint>, RequireConstraintTranslator<? extends RequireConstraint>>
		TRANSLATORS;

	/* initialize list of all RequireConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = createHashMap(10);
		TRANSLATORS.put(Require.class, new RequireTranslator());
		TRANSLATORS.put(FacetSummary.class, new FacetSummaryTranslator());
		TRANSLATORS.put(FacetSummaryOfReference.class, new FacetSummaryOfReferenceTranslator());
		TRANSLATORS.put(ReferenceSummary.class, new ReferenceSummaryTranslator());
		TRANSLATORS.put(ReferenceSummaryOfReference.class, new ReferenceSummaryOfReferenceTranslator());
		TRANSLATORS.put(ReferenceHistogramStatistics.class, new ReferenceHistogramStatisticsTranslator());
		TRANSLATORS.put(AttributeHistogram.class, new AttributeHistogramTranslator());
		TRANSLATORS.put(PriceHistogram.class, new PriceHistogramTranslator());
		TRANSLATORS.put(HierarchyOfSelf.class, new HierarchyOfSelfTranslator());
		TRANSLATORS.put(HierarchyOfReference.class, new HierarchyOfReferenceTranslator());
		TRANSLATORS.put(HierarchyFromRoot.class, new HierarchyFromRootTranslator());
		TRANSLATORS.put(HierarchyFromNode.class, new HierarchyFromNodeTranslator());
		TRANSLATORS.put(HierarchyParents.class, new HierarchyParentsTranslator());
		TRANSLATORS.put(HierarchyChildren.class, new HierarchyChildrenTranslator());
		TRANSLATORS.put(HierarchySiblings.class, new HierarchySiblingsTranslator());
		TRANSLATORS.put(EntityFetch.class, new EntityFetchTranslator());
		TRANSLATORS.put(EntityGroupFetch.class, new EntityGroupFetchTranslator());
		TRANSLATORS.put(HierarchyContent.class, new HierarchyContentTranslator());
		TRANSLATORS.put(ReferenceContent.class, new ReferenceContentTranslator());
		TRANSLATORS.put(PriceContent.class, new PriceContentTranslator());
		TRANSLATORS.put(AttributeContent.class, new AttributeContentTranslator());
		TRANSLATORS.put(AssociatedDataContent.class, new AssociatedDataContentTranslator());
		TRANSLATORS.put(RequireInScope.class, new RequireInScopeTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate @Getter private final QueryPlanningContext queryContext;
	/**
	 * This instance contains the {@link io.evitadb.index.Index} set that is used to resolve passed query filter.
	 */
	@Getter private final TargetIndexes<?> indexSetToUse;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Getter private final Formula filteringFormula;
	/**
	 * Reference to {@link FilterByVisitor} used for creating filterFormula.
	 */
	@Getter private final FilterByVisitor filterByVisitor;
	/**
	 * Contains prepared sorter implementation that takes output of the {@link #filteringFormula} and sorts the entity
	 * primary keys according to {@link OrderConstraint} in {@link EvitaRequest}.
	 */
	@Getter private final Collection<Sorter> sorters;
	/**
	 * The outer query plan's {@link PrefetchFormulaVisitor}. Nested filter plans produced by
	 * {@link #getFilteringFormulaForStatisticsBase} merge their prefetch demand into this visitor so the outer
	 * query plan can fold them into its single one-shot prefetch decision. May be {@code null} in test contexts
	 * where prefetch coordination is not exercised.
	 */
	@Nullable private final PrefetchFormulaVisitor outerPrefetchFormulaVisitor;
	/**
	 * Contains the list of producers that react to passed requirements.
	 */
	@Getter private final LinkedHashSet<ExtraResultProducer> extraResultProducers = new LinkedHashSet<>(16);
	/**
	 * Contains an accessor providing access to the attribute schemas.
	 */
	@Getter private final AttributeSchemaAccessor attributeSchemaAccessor;
	/**
	 * Contemporary stack for auxiliary data resolved for each level of the query.
	 */
	private final Deque<ProcessingScope> scope = new ArrayDeque<>(32);
	/**
	 * Performance shortcut: caches the last producer returned by {@link #findExistingProducer(Class, java.util.function.Predicate)}.
	 * Avoids a full linear scan of {@link #extraResultProducers} when multiple translators ask for the same producer
	 * during a single planning pass (the common case: reference-summary translator registers the producer, then histogram
	 * child translators look it up immediately after).
	 */
	private ExtraResultProducer lastReturnedProducer;
	/**
	 * Lazily computed variant of {@link #filteringFormula} with all {@link UserFilterFormula} sub-trees removed.
	 * Used by facet/reference-summary producers to compute statistics against the "base" filter (without the
	 * user's own dynamic filter parts). Initialized on first access via
	 * {@link #getFilteringFormulaWithoutUserFilter()}.
	 */
	private Formula filteringFormulaWithoutUserFilter;
	/**
	 * Cache of formula trees derived from {@link #filteringFormula} for the three {@link StatisticsBase} modes,
	 * keyed by `(referenceName, statisticsBase)`. Each entry is the result of a single {@link FormulaCloner} pass
	 * over {@link #filteringFormula} that strips the hierarchy and (optionally) user-filter parts irrelevant for
	 * the statistics computation. The map deliberately stores `null` values (via {@link Map#put} with a `null`
	 * second argument) for variants that resolve to "no filter applies"; callers must use
	 * {@link Map#containsKey(Object)} to distinguish a cached `null` from a cache miss. Cached non-`null` values
	 * are wrapped in {@link DeferredFormula} so that telemetry phases stay aligned with the
	 * {@link QueryPlanner#planNestedFilteringFormula} path used by the fallback.
	 */
	private Map<FormulaVariant, Formula> formulaCache;
	/**
	 * Contains set (usually of size == 1 or 0) that contains references to the {@link UserFilterFormula} inside
	 * {@link #filteringFormula}. This is a helper field that allows to reuse result of the formula search multiple
	 * times.
	 */
	private Set<Formula> userFilterFormula;

	public ExtraResultPlanningVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull TargetIndexes<?> indexSetToUse,
		@Nonnull Formula filteringFormula,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nullable Collection<Sorter> sorters,
		@Nullable PrefetchFormulaVisitor outerPrefetchFormulaVisitor
	) {
		this.queryContext = queryContext;
		this.indexSetToUse = indexSetToUse;
		this.filteringFormula = filteringFormula;
		this.filterByVisitor = filterByVisitor;
		this.sorters = sorters;
		this.outerPrefetchFormulaVisitor = outerPrefetchFormulaVisitor;
		this.attributeSchemaAccessor = new AttributeSchemaAccessor(queryContext);
		final LinkedList<Set<Scope>> requestedScopes = new LinkedList<>();
		requestedScopes.add(queryContext.getScopes());
		this.scope.push(
			new ProcessingScope(
				null,
				requestedScopes,
				() -> null,
				() -> null
			)
		);
	}

	/**
	 * Returns superset of all possible results without any filtering.
	 *
	 * @return formula that represents the superset of all possible results.
	 */
	@Nonnull
	public Formula getSuperSetFormula() {
		return this.filterByVisitor.getSuperSetFormula();
	}

	/**
	 * Method finds existing {@link ExtraResultProducer} implementation of particular `producerClass` allowing multiple
	 * translators to reuse (enrich) it. This overload uses the {@link #lastReturnedProducer} cache as a
	 * performance shortcut when the same producer type is requested repeatedly during a planning pass.
	 */
	@Nullable
	public <T extends ExtraResultProducer> T findExistingProducer(@Nonnull Class<T> producerClass) {
		// class-only lookup: safe to consult and update the cache because any producer of the
		// matching class is acceptable — no per-call filtering narrows the set
		if (producerClass.isInstance(this.lastReturnedProducer)) {
			@SuppressWarnings("unchecked") final T cached = (T) this.lastReturnedProducer;
			return cached;
		}
		for (final ExtraResultProducer extraResultProducer : this.extraResultProducers) {
			if (producerClass.isInstance(extraResultProducer)) {
				@SuppressWarnings("unchecked") final T candidate = (T) extraResultProducer;
				this.lastReturnedProducer = extraResultProducer;
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Method finds an existing {@link ExtraResultProducer} implementation of `producerClass` that additionally
	 * satisfies the passed `predicate`. Needed when multiple producers of the same class may coexist in the
	 * planner and the caller wants the one matching a specific internal state — e.g. when the same producer type
	 * is parameterized with different adapters to emit the deprecated {@link FacetSummary} DTO alongside the canonical
	 * {@link ReferenceSummary} DTO.
	 *
	 * This overload deliberately does NOT update the {@link #lastReturnedProducer} cache: doing so would poison
	 * subsequent class-only lookups by making them skip earlier producers in favour of the predicate-selected one.
	 *
	 * @param producerClass the expected producer class (also matches subclasses via {@link Class#isInstance})
	 * @param predicate     additional filter applied to every candidate; the first producer matching both wins
	 * @return the first matching producer or {@code null} if none is present
	 */
	@Nullable
	public <T extends ExtraResultProducer> T findExistingProducer(
		@Nonnull Class<T> producerClass,
		@Nonnull Predicate<T> predicate
	) {
		if (producerClass.isInstance(this.lastReturnedProducer)) {
			@SuppressWarnings("unchecked") final T candidate = (T) this.lastReturnedProducer;
			if (predicate.test(candidate)) {
				return candidate;
			}
		}
		for (final ExtraResultProducer extraResultProducer : this.extraResultProducers) {
			if (producerClass.isInstance(extraResultProducer)) {
				@SuppressWarnings("unchecked") final T candidate = (T) extraResultProducer;
				if (predicate.test(candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	/**
	 * Returns the {@link #getFilteringFormula()} that is stripped of all {@link UserFilterFormula} parts.
	 * Result of this method is cached so that additional calls introduce no performance penalty and also the formula
	 * memoized sub-results are shared once the {@link Formula#compute()} method is called for the first time.
	 */
	@Nonnull
	public Formula getFilteringFormulaWithoutUserFilter() {
		if (this.filteringFormulaWithoutUserFilter == null) {
			this.filteringFormulaWithoutUserFilter = ofNullable(
				FormulaCloner.clone(
					this.filteringFormula,
					formula -> formula instanceof UserFilterFormula ? null : formula
				)).orElseGet(this::getSuperSetFormula);
		}
		return this.filteringFormulaWithoutUserFilter;
	}

	/**
	 * Returns a {@link Formula} derived from {@link #getFilteringFormula()} that represents the filter applicable
	 * to {@link Hierarchy} statistics for the given `statisticsBase` and `referenceSchema`. The result is produced by
	 * a single {@link FormulaCloner} pass over the already-planned {@link #filteringFormula} and therefore reuses
	 * the memoised sub-results of every leaf the cloner does not touch — the dominant performance win over re-planning
	 * the filter constraint tree per hierarchy node.
	 *
	 * The three {@link StatisticsBase} modes apply the following stripping rules (compared against the
	 * `referenceSchema.getName()`; `null` reference name represents the queried entity's own hierarchy):
	 *
	 * - `WITHOUT_USER_FILTER` — drops every {@link UserFilterFormula} sub-tree **and** every
	 *   {@link HierarchyFormula} whose reference name matches the stats reference (or is `null` when the stats
	 *   reference is `null`).
	 * - `COMPLETE_FILTER` — drops only {@link HierarchyFormula} whose reference name matches the stats reference;
	 *   the user-filter remains intact.
	 * - `COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER` — for self-stats (`null` stats reference) drops every
	 *   self-{@link HierarchyFormula} and every empty-named {@link FacetHavingFormula} regardless of position; for
	 *   reference-stats drops {@link HierarchyFormula} and {@link FacetHavingFormula} matching the stats reference
	 *   only when they sit inside a {@link UserFilterFormula}. Other-reference hierarchies and facet selections are
	 *   always preserved.
	 *
	 * The non-`null` result is wrapped in {@link DeferredFormula} carrying the
	 * {@link QueryPhase#EXECUTION_FILTER_NESTED_QUERY} telemetry step so query reports still attribute the work to
	 * the nested-query phase even after the planning pass became free.
	 *
	 * Returns `null` when the cloner stripped the formula entirely — callers fast-path this as "no filter applies"
	 * and skip the AND with the per-node primary-key formula.
	 *
	 * Subsequent calls with the same `(referenceName, statisticsBase)` pair return the same {@link Formula}
	 * instance so multiple per-node lambdas share both planning work and downstream memoised compute results.
	 *
	 * @param statisticsBase  the statistics base mode; {@code null} is treated as
	 *                        {@link StatisticsBase#WITHOUT_USER_FILTER}
	 * @param referenceSchema the reference schema for which statistics are computed, or {@code null} for self-
	 *                        hierarchy statistics
	 * @return the cloned, optionally stripped filter formula wrapped for telemetry, or {@code null} when no filter
	 * needs to be applied
	 */
	@Nullable
	public Formula getFilteringFormulaForStatisticsBase(
		@Nullable StatisticsBase statisticsBase,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final StatisticsBase effectiveBase = statisticsBase == null ? StatisticsBase.WITHOUT_USER_FILTER : statisticsBase;
		final String referenceName = referenceSchema == null ? null : referenceSchema.getName();
		final FormulaVariant cacheKey = new FormulaVariant(referenceName, effectiveBase);
		if (this.formulaCache != null && this.formulaCache.containsKey(cacheKey)) {
			return this.formulaCache.get(cacheKey);
		}
		if (this.formulaCache == null) {
			this.formulaCache = CollectionUtils.createHashMap(4);
		}
		final Formula result = canUseShortcut(referenceSchema)
			? shortcutFormula(effectiveBase, referenceName)
			: fallbackFormula(effectiveBase, referenceSchema);
		this.formulaCache.put(cacheKey, result);
		return result;
	}

	/**
	 * Decides whether the formula-tree shortcut (`FormulaCloner` over the already-planned
	 * {@link #filteringFormula}) is safe for the given stats reference.
	 *
	 * The shortcut reuses leaf bitmaps from whatever target index the primary plan picked, and erases the entire
	 * {@link HierarchyFormula} matching the stats reference. Two independent prerequisites must hold for it to be
	 * correct:
	 *
	 * 1. **Leaf scope** — every stats node the lambda will ask about must lie inside the PK universe those leaves
	 *    can express:
	 *    - Self-hierarchy (`refSchema == null`) — `IndexSelectionVisitor` never offers REDUCED_ENTITY for self, so
	 *      the primary plan is always rooted in `GlobalEntityIndex`.
	 *    - Reference-hierarchy with a non-partitioned reference schema — REDUCED_ENTITY of the same reference is
	 *      tagged with `EligibilityObstacle.NOT_PARTITIONED_INDEX` and is not picked as the primary plan.
	 *    - Reference-hierarchy whose schema is `FOR_FILTERING_AND_PARTITIONING` for every active scope — the
	 *      planner may have selected REDUCED_ENTITY of the same reference, breaking this prerequisite.
	 *
	 * 2. **No having/excluding filter to preserve** — when the user's `hierarchyWithin` / `hierarchyWithinRoot`
	 *    carries a `having(...)` or `excluding(...)` clause, those predicates are baked into the
	 *    {@link HierarchyFormula}'s leaf bitmap together with the subtree positioning. Stripping the wrapper would
	 *    lose the having/excluding semantics — the fallback path must extract them via
	 *    {@link #rewriteHierarchyFilter} and re-apply them at constraint level.
	 *
	 * Each gate conservatively over-approximates: a false positive (gate says "fallback" when the shortcut would
	 * have been correct) costs one extra nested planning pass per `(StatisticsBase, refName)`, never a wrong result.
	 *
	 * Note: `COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER` used to require a third gate because stripping every
	 * child of a {@link UserFilterFormula} would leave the cloner with an empty container that
	 * `UserFilterFormula.getCloneWithInnerFormulas([])` collapses into {@link EmptyFormula} (the "empty result"
	 * sentinel). {@link FormulaCloner} now generalises this: any wrapper whose `getCloneWithInnerFormulas([])`
	 * returns {@link EmptyFormula} is dropped as the identity element of its parent — covering not only
	 * `UserFilterFormula` but also nested `AndFormula`/`OrFormula`/`ScopeContainerFormula` chains that the
	 * strip mutators can empty out, so the shortcut handles all three modes safely.
	 */
	private boolean canUseShortcut(@Nullable ReferenceSchemaContract referenceSchema) {
		final String referenceName = referenceSchema == null ? null : referenceSchema.getName();
		final HierarchyFilterConstraint hierarchyFilter = getEvitaRequest().getHierarchyWithin(referenceName);
		if (hierarchyFilter != null
			&& (!ArrayUtils.isEmpty(hierarchyFilter.getHavingChildrenFilter())
				|| !ArrayUtils.isEmpty(hierarchyFilter.getExcludedChildrenFilter()))) {
			return false;
		}
		if (referenceSchema != null) {
			final Set<Scope> scopes = getProcessingScope().getScopes();
			return !scopes.stream().allMatch(
				scope -> referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
			);
		} else {
			return true;
		}
	}

	/**
	 * Shortcut path — clones the already-planned {@link #filteringFormula} via {@link FormulaCloner} with a mutator
	 * that strips the parts irrelevant for the given {@link StatisticsBase}, then wraps in a
	 * {@link DeferredFormula} carrying execution telemetry. Reuses every leaf the cloner did not touch, so leaf-level
	 * memoised compute results are shared with the primary filter. Returns `null` when the strip removed everything.
	 */
	@Nullable
	private Formula shortcutFormula(@Nonnull StatisticsBase base, @Nullable String referenceName) {
		final Formula cloned = switch (base) {
			case COMPLETE_FILTER -> FormulaCloner.clone(
				this.filteringFormula,
				stripHierarchyMatching(referenceName)
			);
			case WITHOUT_USER_FILTER -> FormulaCloner.clone(
				this.filteringFormula,
				stripHierarchyMatchingAndUserFilter(referenceName)
			);
			case COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER -> FormulaCloner.clone(
				this.filteringFormula,
				stripHierarchyAndFacetForSelfInUserFilter(referenceName)
			);
		};
		return cloned == null ? null : wrapWithStatisticsTelemetry(cloned, base, referenceName);
	}

	/**
	 * Fallback path — used when the primary plan may be rooted in REDUCED_ENTITY of the same hierarchy reference.
	 * Re-translates the rewritten `FilterBy` once via {@link QueryPlanner#planNestedFilteringFormula}, which mirrors
	 * the outer-query planning pipeline (`IndexSelectionVisitor` + per-candidate `FilterByVisitor` with
	 * `FormulaOptimizer` + `PrefetchFormulaVisitor`) and picks the cheapest candidate by estimated cost. The chosen
	 * formula is wrapped with execution telemetry and its prefetch demand is folded into the outer query's
	 * {@link PrefetchFormulaVisitor} so the outer plan can decide prefetch on the combined signal.
	 *
	 * Returns `null` only when the rewrite leaves no applicable filter constraint (`rewritten == null`); in that
	 * case the caller skips the AND with the per-node primary-key formula. An {@link EmptyFormula#INSTANCE}
	 * coming back from {@link QueryPlanner#planNestedFilteringFormula} (no eligible index — practically
	 * unreachable when the queried collection has a global index) is propagated as-is so callers AND it normally
	 * and produce an empty bitmap, preserving "no plan possible" semantics rather than degrading into "unfiltered"
	 * which would over-count.
	 */
	@Nullable
	private Formula fallbackFormula(@Nonnull StatisticsBase base, @Nullable ReferenceSchemaContract referenceSchema) {
		final FilterBy rewritten = rewriteFilterByForStatisticsBase(base, referenceSchema);
		if (rewritten == null) {
			return null;
		}
		final String referenceName = referenceSchema == null ? null : referenceSchema.getName();
		return QueryPlanner.planNestedFilteringFormula(
			getQueryContext(),
			rewritten,
			this.outerPrefetchFormulaVisitor,
			() -> "Hierarchy statistics filter (" + base + (referenceName == null ? "" : ", reference=" + referenceName)
				+ ")"
		);
	}

	/**
	 * Constraint-tree analogue of the formula-tree mutators used by {@link #shortcutFormula}. Walks a deep clone of
	 * the original {@link FilterBy} and drops the constraint nodes that match the strip rules for the given
	 * {@link StatisticsBase}, returning the resulting filter — or `null` when the rewrite leaves nothing applicable.
	 *
	 * Used exclusively by {@link #fallbackFormula}; the resulting `FilterBy` is fed to a nested
	 * {@link FilterByVisitor} pass against the queried collection's `GlobalEntityIndex`.
	 */
	@Nullable
	private FilterBy rewriteFilterByForStatisticsBase(
		@Nonnull StatisticsBase base,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final FilterBy original = getFilterBy();
		if (original == null) {
			return null;
		}
		final String referenceName = referenceSchema == null ? null : referenceSchema.getName();
		final FilterBy result = (FilterBy) ConstraintCloneVisitor.clone(
			original,
			(visitor, constraint) -> switch (base) {
				case COMPLETE_FILTER -> {
					if (constraint instanceof HierarchyFilterConstraint hfc && hierarchyMatches(hfc, referenceName)) {
						yield rewriteHierarchyFilter(hfc, referenceName);
					}
					yield constraint;
				}
				case WITHOUT_USER_FILTER -> {
					if (constraint instanceof HierarchyFilterConstraint hfc && hierarchyMatches(hfc, referenceName)) {
						yield rewriteHierarchyFilter(hfc, referenceName);
					}
					if (constraint instanceof UserFilter) {
						yield null;
					}
					yield constraint;
				}
				case COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER -> {
					if (constraint instanceof HierarchyFilterConstraint hfc) {
						final boolean matchesUnnamedVariant =
							referenceName == null && hfc.getReferenceName().isEmpty();
						final boolean matchesNamedVariantInsideUserFilter =
							referenceName != null
								&& hfc.getReferenceName()
									.map(refName -> refName.equals(referenceName))
									.orElse(false)
								&& visitor.isWithin(UserFilter.class);
						if (matchesUnnamedVariant || matchesNamedVariantInsideUserFilter) {
							yield null;
						}
					} else if (
						// facets are always reference-bound (`FacetHaving.getReferenceName()` is `@Nonnull`
						// and `@Classifier`-validated non-empty), so they can only ever match named-reference
						// stats — never self-stats
						referenceName != null
							&& constraint instanceof FacetHaving fh
							&& fh.getReferenceName().equals(referenceName)
							&& visitor.isWithin(UserFilter.class)
					) {
						yield null;
					}
					yield constraint;
				}
			}
		);
		return result != null && result.isApplicable() ? result : null;
	}

	/**
	 * Returns `true` when the given {@link HierarchyFilterConstraint} targets the same hierarchy reference for
	 * which statistics are being computed — `null` `referenceName` matches an empty-reference `hierarchyWithin`
	 * (self-hierarchy), a non-`null` `referenceName` matches an `hfc` carrying the same reference name. Mirrors
	 * the formula-tree match condition used by {@link #stripHierarchyMatching} so the fallback path strips
	 * exactly the same set of hierarchy constraints as the shortcut.
	 */
	private static boolean hierarchyMatches(
		@Nonnull HierarchyFilterConstraint hfc,
		@Nullable String referenceName
	) {
		final Optional<String> hfcRef = hfc.getReferenceName();
		return referenceName == null ? hfcRef.isEmpty() : hfcRef.map(referenceName::equals).orElse(false);
	}

	/**
	 * Replaces a {@link HierarchyFilterConstraint} with the equivalent constraint expressed against the queried
	 * entity's `GlobalEntityIndex` — for self-hierarchy this is the inner having/excluded predicate as-is, for
	 * referenced hierarchy it wraps the predicate in `referenceHaving(referenceName, entityHaving(...))` so the
	 * nested visitor can reach the referenced entity. Returns `null` when the original constraint had no predicates,
	 * letting the {@link ConstraintCloneVisitor} drop the node entirely.
	 */
	@Nullable
	private static FilterConstraint rewriteHierarchyFilter(
		@Nonnull HierarchyFilterConstraint hfc,
		@Nullable String referenceName
	) {
		final Function<FilterConstraint, FilterConstraint> wrapper = referenceName == null
			? Function.identity()
			: filter -> new FilterBy(new ReferenceHaving(referenceName, entityHaving(filter)));
		final FilterConstraint[] excludedChildrenFilter = hfc.getExcludedChildrenFilter();
		final FilterConstraint[] havingChildrenFilter = hfc.getHavingChildrenFilter();
		if (ArrayUtils.isEmpty(excludedChildrenFilter)) {
			if (ArrayUtils.isEmpty(havingChildrenFilter)) {
				return null;
			} else if (havingChildrenFilter.length == 1) {
				return wrapper.apply(havingChildrenFilter[0]);
			} else {
				return wrapper.apply(and(havingChildrenFilter));
			}
		} else if (excludedChildrenFilter.length == 1) {
			return wrapper.apply(not(excludedChildrenFilter[0]));
		} else {
			return wrapper.apply(not(and(excludedChildrenFilter)));
		}
	}

	/**
	 * Mutator for `COMPLETE_FILTER`: strips {@link HierarchyFormula} whose `referenceName` matches `referenceName`
	 * (or both are `null`). All other formulas are preserved so that the cloner reuses their memoised sub-results.
	 */
	@Nonnull
	private static UnaryOperator<Formula> stripHierarchyMatching(@Nullable String referenceName) {
		return formula -> {
			if (formula instanceof HierarchyFormula hf && Objects.equals(hf.getReferenceName(), referenceName)) {
				return null;
			}
			return formula;
		};
	}

	/**
	 * Mutator for `WITHOUT_USER_FILTER`: strips every {@link UserFilterFormula} and every
	 * {@link HierarchyFormula} whose `referenceName` matches `referenceName`.
	 */
	@Nonnull
	private static UnaryOperator<Formula> stripHierarchyMatchingAndUserFilter(@Nullable String referenceName) {
		return formula -> {
			if (formula instanceof UserFilterFormula) {
				return null;
			}
			if (formula instanceof HierarchyFormula hf && Objects.equals(hf.getReferenceName(), referenceName)) {
				return null;
			}
			return formula;
		};
	}

	/**
	 * Mutator for `COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER`. For a `null` stats reference (self-hierarchy
	 * statistics) every self-{@link HierarchyFormula} is stripped regardless of its position; for a non-`null`
	 * stats reference, {@link HierarchyFormula} and {@link FacetHavingFormula} matching the stats reference are
	 * stripped only when reached through a {@link UserFilterFormula} parent — anything outside the user filter
	 * (including the mandatory hierarchy anchor) is preserved.
	 *
	 * {@link FacetHavingFormula} is never stripped for self-stats: facets are always reference-bound
	 * (`FacetHaving.getReferenceName()` is `@Nonnull` and `@Classifier`-validated non-empty), so they can only
	 * ever match named-reference stats.
	 */
	@Nonnull
	private static BiFunction<FormulaCloner, Formula, Formula> stripHierarchyAndFacetForSelfInUserFilter(
		@Nullable String referenceName
	) {
		return (cloner, formula) -> {
			if (formula instanceof HierarchyFormula hf) {
				final String hierarchyRef = hf.getReferenceName();
				if (referenceName == null && hierarchyRef == null) {
					return null;
				}
				if (referenceName != null && referenceName.equals(hierarchyRef)
					&& cloner.isWithin(UserFilterFormula.class)) {
					return null;
				}
			} else if (
				referenceName != null
					&& formula instanceof FacetHavingFormula fh
					&& referenceName.equals(fh.getReferenceName())
					&& cloner.isWithin(UserFilterFormula.class)
			) {
				return null;
			}
			return formula;
		};
	}

	/**
	 * Wraps the cloned filter formula in {@link DeferredFormula} so that its evaluation is reported under
	 * {@link QueryPhase#EXECUTION_FILTER_NESTED_QUERY} — preserving telemetry continuity with the fallback path
	 * (which goes through {@link QueryPlanner#planNestedFilteringFormula} and runs a nested
	 * {@link QueryPhase#PLANNING_FILTER_NESTED_QUERY}). The shortcut path skips the planning step entirely
	 * (re-using the already-planned tree) so only the execution step is exposed to telemetry consumers.
	 */
	@Nonnull
	private static Formula wrapWithStatisticsTelemetry(
		@Nonnull Formula formula,
		@Nonnull StatisticsBase statisticsBase,
		@Nullable String referenceName
	) {
		final Supplier<String> stepDescriptionSupplier = () ->
			"Hierarchy statistics filter (" + statisticsBase
				+ (referenceName == null ? "" : ", reference=" + referenceName)
				+ ")";
		return new DeferredFormula(
			new FormulaWrapper(
				formula,
				(executionContext, inner) -> {
					try {
						executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
						return inner.compute();
					} finally {
						executionContext.popStep();
					}
				}
			)
		);
	}

	/**
	 * Returns set (usually of size == 1 or 0) that contains references to the {@link UserFilterFormula} inside
	 * {@link #filteringFormula}. Result of this method is cached so that additional calls introduce no performance
	 * penalty.
	 */
	@Nonnull
	public Set<Formula> getUserFilteringFormula() {
		if (this.userFilterFormula == null) {
			this.userFilterFormula = new HashSet<>(
				FormulaFinder.find(
					getFilteringFormula(), UserFilterFormula.class, LookUp.SHALLOW
				)
			);
		}
		return this.userFilterFormula;
	}

	/**
	 * Method creates the {@link Sorter} implementation that should be used for sorting {@link LevelInfo} inside
	 * the {@link Hierarchy} result object.
	 */
	@Nonnull
	public NestedContextSorter createSorter(
		@Nonnull ConstraintContainer<OrderConstraint> orderBy,
		@Nullable Locale locale,
		@Nonnull EntityCollection entityCollection,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		return OrderByVisitor.createSorter(
			orderBy, locale, entityCollection, stepDescriptionSupplier,
			this.queryContext, getProcessingScope().getScopes()
		);
	}

	/**
	 * Method finds sorter of specified type in the current {@link #sorters} or sorters that are chained in it as secondary
	 * or tertiary sorters.
	 */
	@Nullable
	public <T extends Sorter> T findSorter(@Nonnull Class<T> sorterType) {
		for (Sorter sorter : this.sorters) {
			if (sorterType.isInstance(sorter)) {
				//noinspection unchecked
				return (T) sorter;
			}
		}
		return null;
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final RequireConstraint requireConstraint = (RequireConstraint) constraint;

		if (requireConstraint instanceof ExtraResultRequireConstraint) {
			@SuppressWarnings("unchecked") final RequireConstraintTranslator<RequireConstraint> translator =
				(RequireConstraintTranslator<RequireConstraint>) TRANSLATORS.get(requireConstraint.getClass());
			isPremiseValid(
				translator != null,
				"No translator found for constraint `" + requireConstraint.getClass() + "`!"
			);

			// if query is a container query
			if (requireConstraint instanceof ConstraintContainer) {
				@SuppressWarnings("unchecked") final ConstraintContainer<RequireConstraint> container =
					(ConstraintContainer<RequireConstraint>) requireConstraint;
				// process children constraints
				if (!(translator instanceof SelfTraversingTranslator)) {
					for (RequireConstraint child : container) {
						child.accept(this);
					}
				} else {
					registerProducer(translator.createProducer(requireConstraint, this));
				}
			} else if (requireConstraint instanceof ConstraintLeaf) {
				// process the leaf query
				registerProducer(translator.createProducer(requireConstraint, this));
			} else {
				// sanity check only
				throw new GenericEvitaInternalError("Should never happen");
			}
		} else {
			@SuppressWarnings("unchecked") final RequireConstraintTranslator<RequireConstraint> translator =
				(RequireConstraintTranslator<RequireConstraint>) TRANSLATORS.get(requireConstraint.getClass());

			if (translator != null) {
				translator.createProducer(requireConstraint, this);
			}

			if (requireConstraint instanceof ConstraintContainer && !(translator instanceof SelfTraversingTranslator)) {
				@SuppressWarnings("unchecked") final ConstraintContainer<RequireConstraint> container =
					(ConstraintContainer<RequireConstraint>) requireConstraint;
				for (RequireConstraint child : container) {
					child.accept(this);
				}
			}
		}
	}

	/**
	 * Method registers the {@link ExtraResultProducer} instance. Idempotent — a subsequent call with
	 * an already-registered producer is a no-op. This lets self-traversing translators publish their
	 * producer before dispatching children (so the children can discover it via
	 * {@link #findExistingProducer}) without risking a duplicate registration when the planner
	 * eventually re-registers the same producer after {@code createProducer} returns.
	 */
	public void registerProducer(@Nullable ExtraResultProducer extraResultProducer) {
		if (extraResultProducer != null) {
			this.extraResultProducers.add(extraResultProducer);
		}
	}

	/**
	 * Pushes a new {@link ProcessingScope} onto the scope stack for the duration of `lambda`, then pops it
	 * in a `finally` block. The new scope inherits the active {@link io.evitadb.dataType.Scope} set from the
	 * enclosing scope and overrides the reference and entity schema suppliers so that translators running inside
	 * the lambda see the correct schema context.
	 *
	 * Used by self-traversing translators (e.g. `ReferenceSummaryOfReferenceTranslator`) before manually dispatching
	 * child constraints, so that the child translators (e.g. `ReferenceHistogramStatisticsTranslator`) can retrieve
	 * the active reference schema via {@link ProcessingScope#getReferenceSchema()}.
	 *
	 * @param requirement             the require constraint being processed — recorded in the scope and surfaced via
	 *                                {@link #getEntityContentRequireChain(EntityContentRequire)}
	 * @param referenceSchemaSupplier supplier that returns the reference schema for the current context, or `null`
	 *                                when the context is not reference-scoped
	 * @param entitySchemaSupplier    supplier that returns the entity schema for the current context, or `null`
	 *                                when the context targets the queried entity
	 * @param lambda                  code to execute within the new scope; its return value is forwarded to the caller
	 * @return the value returned by `lambda`
	 */
	public final <T> T executeInContext(
		@Nonnull RequireConstraint requirement,
		@Nonnull Supplier<ReferenceSchemaContract> referenceSchemaSupplier,
		@Nonnull Supplier<EntitySchemaContract> entitySchemaSupplier,
		@Nonnull Supplier<T> lambda
	) {
		try {
			final LinkedList<Set<Scope>> scopes = new LinkedList<>();
			scopes.add(getProcessingScope().getScopes());
			this.scope.push(
				new ProcessingScope(
					requirement,
					scopes,
					referenceSchemaSupplier,
					entitySchemaSupplier
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Returns current processing scope.
	 */
	@Nonnull
	public ProcessingScope getProcessingScope() {
		if (this.scope.isEmpty()) {
			throw new GenericEvitaInternalError("Scope should never be empty");
		} else {
			return Objects.requireNonNull(this.scope.peek());
		}
	}

	/**
	 * Return the {@link ReferenceSchemaContract} valid for current context or null.
	 */
	@Nonnull
	public Stream<RequireConstraint> getEntityContentRequireChain(@Nonnull EntityContentRequire current) {
		return Stream.concat(
			StreamSupport
				.stream(
					Spliterators.spliteratorUnknownSize(this.scope.descendingIterator(), Spliterator.ORDERED),
					false
				)
				.map(ProcessingScope::requirement)
				.filter(Objects::nonNull),
			Stream.of(current)
		);
	}

	/**
	 * Return the {@link ReferenceSchemaContract} valid for current context or null.
	 */
	@Nonnull
	public Optional<ReferenceSchemaContract> getCurrentReferenceSchema() {
		return getProcessingScope().getReferenceSchema();
	}

	/**
	 * Return the {@link EntitySchemaContract} valid for current context.
	 */
	@Nonnull
	public Optional<EntitySchemaContract> getCurrentEntitySchema() {
		return getProcessingScope().getEntitySchema();
	}

	/**
	 * Returns true if the scope relates to top entity.
	 */
	public boolean isScopeOfQueriedEntity() {
		return this.scope.size() <= 1;
	}

	/**
	 * Returns true if the scope relates to top entity.
	 *
	 * @return true if the scope relates to top entity
	 */
	public boolean isRootScope() {
		return this.scope.size() == 1;
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link RequireConstraintTranslator}
	 * implementations to exchange schema that is being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 *
	 * A new scope is pushed onto the visitor's stack by
	 * {@link ExtraResultPlanningVisitor#executeInContext(RequireConstraint, Supplier, Supplier, Supplier)}
	 * and popped on exit. The root scope (pushed in the visitor's constructor) has a null requirement and provides
	 * the query's own entity/reference schemas.
	 *
	 * @param requirement             the require constraint being processed in this scope; `null` in the root scope
	 *                                (no enclosing constraint). Surfaced via
	 *                                {@link ExtraResultPlanningVisitor#getEntityContentRequireChain(EntityContentRequire)}
	 *                                so translators can reconstruct the chain of enclosing requirements.
	 * @param requiredScopes          stack of active {@link Scope} sets. The top element (peeked by
	 *                                {@link #getScopes()}) is the effective scope set for the current context; the
	 *                                stack grows when a translator calls {@link #doWithScope(Scope, Supplier)} to
	 *                                temporarily narrow the active scope and shrinks automatically on return.
	 * @param referenceSchemaAccessor supplier returning the {@link ReferenceSchemaContract} that is in effect for
	 *                                the current scope, or `null` at top level where no reference is being processed.
	 *                                Invoked lazily so translators can capture whichever reference they push without
	 *                                eagerly resolving it at construction time.
	 * @param entitySchemaAccessor    supplier returning the {@link EntitySchemaContract} that is in effect for the
	 *                                current scope. Translators use this to resolve attribute and reference schemas
	 *                                relative to whichever entity collection the nested require is being evaluated
	 *                                against (which may differ from the query's target collection when resolving
	 *                                referenced entities).
	 */
	public record ProcessingScope(
		@Nullable RequireConstraint requirement,
		@Nonnull Deque<Set<Scope>> requiredScopes,
		@Nonnull Supplier<ReferenceSchemaContract> referenceSchemaAccessor,
		@Nonnull Supplier<EntitySchemaContract> entitySchemaAccessor
	) {

		/**
		 * Returns reference schema if any.
		 */
		@Nonnull
		public Optional<ReferenceSchemaContract> getReferenceSchema() {
			return ofNullable(this.referenceSchemaAccessor.get());
		}

		/**
		 * Returns entity schema.
		 */
		@Nonnull
		public Optional<EntitySchemaContract> getEntitySchema() {
			return ofNullable(this.entitySchemaAccessor.get());
		}

		/**
		 * Retrieves the set of requested scopes from the processing context.
		 *
		 * @return A non-null set of {@link Scope} that are required for the current processing context.
		 */
		@Nonnull
		public Set<Scope> getScopes() {
			return Objects.requireNonNull(this.requiredScopes.peek());
		}

		/**
		 * Executes the given supplier within the context of the specified scope. This method ensures that
		 * the specified scope is applied for the duration of the supplier's execution and then restores
		 * the previous scope afterwards.
		 *
		 * @param scopeToUse the scope to be applied during the execution of the supplier
		 * @param lambda     the supplier function to be executed within the specified scope
		 * @return the result produced by the supplier
		 */
		public <S> S doWithScope(@Nonnull Scope scopeToUse, @Nonnull Supplier<S> lambda) {
			try {
				this.requiredScopes.push(EnumSet.of(scopeToUse));
				return lambda.get();
			} finally {
				this.requiredScopes.pop();
			}
		}

	}

	/**
	 * Represents a specific formula variant which encapsulates a reference name and the base type of
	 * statistics calculation. This record is used as a cache key to {@link ExtraResultPlanningVisitor#formulaCache}.
	 */
	private record FormulaVariant(
		@Nullable String referenceName,
		@Nonnull StatisticsBase statisticsBase
	) {
	}

}
