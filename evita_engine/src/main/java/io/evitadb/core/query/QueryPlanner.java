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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.exception.InconsistentResultsException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.debug.CacheableVariantsGeneratingVisitor;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.prefetch.PrefetchFormulaVisitor;
import io.evitadb.core.query.algebra.prefetch.PrefetchOrder;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FormulaOptimizer;
import io.evitadb.core.query.indexSelection.IndexSelectionResult;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.policy.BitmapFavouringNoCachePolicy;
import io.evitadb.core.query.policy.CacheEnforcingPolicy;
import io.evitadb.core.query.policy.PrefetchFavouringNoCachePolicy;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.primaryKey.sorter.TranslatedPrimaryKeySorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.chunk.ExpressionBasedSlicer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.RandomUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * {@link QueryPlanner} translates {@link EvitaRequest} to a {@link QueryPlan}. It has two main functions:
 *
 * - to choose the best index(es) to be used in query execution
 * - to construct the {@link QueryPlan} body that consists of a tree of formulas
 *
 * The planner doesn't really compute the result - only prepares the recipe for computing it. Result is computed
 * after {@link QueryPlan#execute()} is called. Preparation of the {@link QueryPlan} should be really fast and can be
 * called anytime without big performance penalty.
 *
 * The planner uses <a href="https://en.wikipedia.org/wiki/Visitor_pattern">Visitor</a> pattern to translate tree
 * of {@link FilterConstraint} to a tree of {@link AbstractFormula}.
 *
 * Because "cheap" is the whole point, the planner routinely plans **several** alternatives - one per candidate
 * index set - and keeps the one with the lowest {@link Formula#getEstimatedCost()}. The expensive follow-up work
 * (sorting, extra results) is then done only for the winner, unless a {@link DebugMode} asks for all of them to be
 * built and cross-checked against each other.
 *
 * The class is a static utility - it holds no state of its own, everything lives in the passed
 * {@link QueryPlanningContext}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryPlanner {

	/**
	 * Method evaluates the {@link QueryPlanningContext#getEvitaRequest()} and creates an "action plan" that allows
	 * to compute the appropriate response for it. This method is the heart of the query planner logic.
	 *
	 * Planning passes through these phases:
	 *
	 * 1. filtering formula construction for all possible indexes
	 * a) replacing formulas with cached results
	 * b) selecting the best formula / index combination that would produce result with minimal effort
	 * 2. creating sorter that will take care of ordering and slicing the result page
	 * 3. prefetching the entity bodies if the filtering requires it, or it would produce results faster
	 * 4. creating extra result computers, that will create and provide extra results for the request
	 *
	 * The expensive work will be executed when {@link QueryPlan#execute()} is called outside this method.
	 *
	 * Phases 2 - 4 are normally performed for the preferred plan only; the debug branch performs them for every
	 * alternative, because the consistency verification needs fully built plans to execute and compare - see
	 * {@link #verifyConsistentResultsInAllPlans}.
	 *
	 * @param context planning context of the query, also the collector of the planning telemetry
	 * @return plan ready to be executed, possibly an empty one when the filter cannot match anything
	 */
	@Nonnull
	public static QueryPlan planQuery(@Nonnull QueryPlanningContext context) {
		context.pushStep(QueryPhase.PLANNING);
		try {
			// determine the indexes that should be used for filtering
			final IndexSelectionResult<?> indexSelectionResult = selectIndexes(context);

			// if we found empty target index, we may quickly return empty result - one key condition is not fulfilled
			if (indexSelectionResult.isEmpty()) {
				return QueryPlanBuilder.empty(context);
			}

			// create filtering formula and pick the formula with the least estimated costs
			// this should be pretty fast - no computation is done yet
			final List<QueryPlanBuilder> queryPlanBuilders = createFilterFormula(
				context, indexSelectionResult.targetIndexes()
			);

			// verify there is at least one plan
			Assert.isPremiseValid(!queryPlanBuilders.isEmpty(), "Unexpectedly no query plan was created!");

			// select preferred plan
			final QueryPlanBuilder preferredPlan = queryPlanBuilders.get(0);

			// verify results in alternative indexes if the debug option is on
			final List<? extends TargetIndexes<?>> targetIndexes = indexSelectionResult.targetIndexes();
			if (context.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) || context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
				// create sorter and computers for all plans
				createSorter(context, targetIndexes, queryPlanBuilders);
				createExtraResultProducers(context, queryPlanBuilders);
				createSlicer(context, queryPlanBuilders);
				// and verify consistent results
				verifyConsistentResultsInAllPlans(context, targetIndexes, queryPlanBuilders, preferredPlan);
			} else {
				// create sorter and computers only for preferred plan
				final List<QueryPlanBuilder> preferredPlanBuilderCollection = Collections.singletonList(preferredPlan);
				createSorter(context, targetIndexes, preferredPlanBuilderCollection);
				createSlicer(context, queryPlanBuilders);
				createExtraResultProducers(context, preferredPlanBuilderCollection);
			}

			// return the preferred plan
			return preferredPlan.build();

		} finally {
			context.popStep();
		}
	}

	/**
	 * Method evaluates the {@link QueryPlanningContext#getEvitaRequest()} and creates an "action plan" that allows
	 * to compute the limited result of a nested query.
	 *
	 * Planning passes through these phases:
	 *
	 * 1. filtering formula construction for all possible indexes
	 * a) replacing formulas with cached results
	 * b) selecting the best formula / index combination that would produce result with minimal effort
	 * 2. creating sorter that will take care of ordering and slicing the result page
	 * 3. prefetching the entity bodies if the filtering requires it, or it would produce results faster
	 *
	 * What it deliberately does **not** do - and what distinguishes it from {@link #planQuery(QueryPlanningContext)} -
	 * is extra result fabrication and slicing: a nested query contributes primary keys (and sometimes a sorter) to
	 * the enclosing query, and nobody ever asks it for a facet summary or a page.
	 *
	 * The expensive work will be executed when {@link QueryPlan#execute()} is called outside this method.
	 *
	 * @param context                planning context of the nested query
	 * @param nestedQueryDescription description of the nested query for the telemetry step, resolved only when
	 *                               telemetry is actually collected
	 * @return plan ready to be executed, possibly an empty one when the filter cannot match anything
	 */
	@Nonnull
	public static QueryPlan planNestedQuery(
		@Nonnull QueryPlanningContext context,
		@Nonnull Supplier<String> nestedQueryDescription
	) {
		context.pushStep(QueryPhase.PLANNING_NESTED_QUERY, nestedQueryDescription);
		try {
			// determine the indexes that should be used for filtering
			final IndexSelectionResult<?> indexSelectionResult = selectIndexes(context);

			// if we found empty target index, we may quickly return empty result - one key condition is not fulfilled
			if (indexSelectionResult.isEmpty()) {
				return QueryPlanBuilder.empty(context);
			}

			// create filtering formula and pick the formula with the least estimated costs
			// this should be pretty fast - no computation is done yet
			final List<QueryPlanBuilder> queryPlanBuilders = createFilterFormula(
				context, indexSelectionResult.targetIndexes()
			);

			// verify there is at least one plan
			Assert.isPremiseValid(!queryPlanBuilders.isEmpty(), "Unexpectedly, no query plan was created!");

			// select preferred plan builder
			final QueryPlanBuilder preferredPlan = queryPlanBuilders.get(0);

			// verify results in alternative indexes
			final List<? extends TargetIndexes<?>> targetIndexes = indexSelectionResult.targetIndexes();
			if (context.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) || context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
				// create sorters for all possible plans
				createSorter(context, targetIndexes, queryPlanBuilders);
				verifyConsistentResultsInAllPlans(context, targetIndexes, queryPlanBuilders, preferredPlan);
			} else {
				// create sorter only for preferred plan
				createSorter(context, targetIndexes, Collections.singletonList(preferredPlan));
			}

			// return the preferred plan
			return preferredPlan.build();

		} finally {
			context.popStep();
		}
	}

	/**
	 * Method analyzes the input query and picks multiple {@link EntityIndex} sets that can be interchangeably used to
	 * construct response to the query. Currently, the logic is quite stupid - it searches the filter for all constraints
	 * within AND relation and when relation or hierarchy query is encountered, it adds specific
	 * {@link EntityIndexType#REFERENCED_ENTITY} that contains limited subset of the entities related to that
	 * placement/relation.
	 *
	 * @param queryContext planning context of the query whose `filterBy` is analyzed
	 * @return interchangeable index sets, each of which can answer the query on its own
	 */
	private static IndexSelectionResult<?> selectIndexes(@Nonnull QueryPlanningContext queryContext) {
		return selectIndexes(queryContext, queryContext.getFilterBy());
	}

	/**
	 * Variant of {@link #selectIndexes(QueryPlanningContext)} that runs index selection against a caller-supplied
	 * `FilterBy` instead of the outer query's. Used by {@link #planNestedFilteringFormula} when planning a rewritten
	 * filter for hierarchy statistics.
	 *
	 * A NULL `filterBy` is legal and yields whatever index the visitor offers by default (typically the global one),
	 * because a query without a filter still has to read entities from somewhere.
	 *
	 * @param queryContext planning context of the query
	 * @param filterBy     filter to analyze, NULL when the query has none
	 * @return interchangeable index sets, each of which can answer the filter on its own
	 */
	@Nonnull
	private static IndexSelectionResult<?> selectIndexes(
		@Nonnull QueryPlanningContext queryContext,
		@Nullable FilterBy filterBy
	) {
		queryContext.pushStep(QueryPhase.PLANNING_INDEX_USAGE);
		try {
			final IndexSelectionVisitor indexSelectionVisitor = new IndexSelectionVisitor(queryContext);
			ofNullable(filterBy).ifPresent(indexSelectionVisitor::visit);
			//noinspection rawtypes,unchecked
			return new IndexSelectionResult<>((List) indexSelectionVisitor.getTargetIndexes());
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Method creates multiple filter formulas for each of the {@link IndexSelectionResult#targetIndexes()} using
	 * specialized visitor that goes through input query. Creating formulas is relatively inexpensive - no computation
	 * really happens, only the execution tree is constructed. For each {@link IndexSelectionResult#targetIndexes()}
	 * one formula is created. From all of those formulas only single one is selected, the one with least estimated cost.
	 *
	 * @param queryContext  planning context of the query
	 * @param targetIndexes candidate index sets to build a formula for
	 * @return plan builders ordered cheapest first - a single element unless a debug mode asked for all of them
	 */
	@Nonnull
	private static <T extends Index<?>> List<QueryPlanBuilder> createFilterFormula(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes
	) {
		return createFilterFormula(queryContext, targetIndexes, queryContext.getFilterBy());
	}

	/**
	 * Variant of {@link #createFilterFormula(QueryPlanningContext, List)} that plans a caller-supplied `FilterBy`
	 * (typically the outer query's filter rewritten for an extra-result computation) instead of
	 * {@link QueryPlanningContext#getFilterBy()}. Used by {@link #planNestedFilteringFormula}.
	 *
	 * Two details of the result are relied upon by the callers:
	 *
	 * - the list is kept **sorted by estimated cost** as it is built (the cheapest candidate is always pushed to
	 *   the front), so `get(0)` is the preferred plan without a separate sorting pass
	 * - unless {@link DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} is on, only that first element is returned; the
	 *   remaining candidates are constructed but thrown away, since building them is cheap and comparing their
	 *   costs is the only way to know which one wins
	 *
	 * Candidates that are not eligible for a separate query plan produce no builder at all - they are only
	 * recorded in telemetry, so that a reader can see the index was considered and why it lost.
	 *
	 * @param queryContext  planning context of the query
	 * @param targetIndexes candidate index sets to build a formula for
	 * @param filterBy      filter to translate into a formula tree, NULL when the query has none
	 * @return plan builders ordered cheapest first - a single element unless a debug mode asked for all of them
	 */
	@Nonnull
	private static <T extends Index<?>> List<QueryPlanBuilder> createFilterFormula(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nullable FilterBy filterBy
	) {
		final LinkedList<QueryPlanBuilder> result = new LinkedList<>();
		queryContext.pushStep(QueryPhase.PLANNING_FILTER);
		try {
			for (TargetIndexes<T> targetIndex : targetIndexes) {
				queryContext.pushStep(QueryPhase.PLANNING_FILTER_ALTERNATIVE);
				if (targetIndex.isEligibleForSeparateQueryPlan()) {
					Formula adeptFormula = null;
					try {
						final FilterByVisitor filterByVisitor = new FilterByVisitor(
							queryContext, targetIndexes, targetIndex
						);

						final PrefetchFormulaVisitor prefetchFormulaVisitor = new PrefetchFormulaVisitor(queryContext, targetIndex);
						ofNullable(filterBy).ifPresent(filterByVisitor::visit);
						adeptFormula = queryContext.analyse(
							filterByVisitor.getFormula(
								new FormulaOptimizer(),
								prefetchFormulaVisitor
							)
						);

						final QueryPlanBuilder queryPlanBuilder = new QueryPlanBuilder(
							queryContext, adeptFormula, filterByVisitor, targetIndex, prefetchFormulaVisitor
						);
						if (result.isEmpty() || adeptFormula.getEstimatedCost() < result.get(0).getEstimatedCost()) {
							result.addFirst(queryPlanBuilder);
						} else {
							result.addLast(queryPlanBuilder);
						}
					} finally {
						// the supplier has to capture an effectively final reference; it is resolved only when
						// telemetry is on, and `toStringWithCosts` allocates two to three strings per candidate index
						final Formula builtFormula = adeptFormula;
						queryContext.popStep(
							builtFormula == null ?
								targetIndex::toString :
								() -> targetIndex.toStringWithCosts(builtFormula.getEstimatedCost())
						);
					}
				} else {
					queryContext.popStep(targetIndex::toString);
				}
			}

			return queryContext.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) ?
				result : result.subList(0, 1);
		} finally {
			if (result.isEmpty()) {
				queryContext.popStep(() -> "No index selected!");
			} else {
				queryContext.popStep(() -> "Selected index: " + result.get(0).getDescriptionWithCosts());
			}
		}
	}

	/**
	 * Plans a caller-supplied `FilterBy` against the same engine that plans the outer query, picks the cheapest
	 * candidate by estimated cost, and returns the chosen formula wrapped in {@link DeferredFormula} carrying the
	 * `EXECUTION_FILTER_NESTED_QUERY` telemetry step.
	 *
	 * Used by {@link ExtraResultPlanningVisitor#getFilteringFormulaForStatisticsBase} when the
	 * {@link FormulaCloner}-based shortcut over the already-planned filter is unsafe and the rewritten `FilterBy`
	 * must be re-translated. Mirrors the outer-query pipeline (`IndexSelectionVisitor` + per-candidate
	 * `FilterByVisitor` with `FormulaOptimizer` + `PrefetchFormulaVisitor`) — same primitives, same cost estimation,
	 * so prefetch remains a viable planning alternative when the rewritten filter narrows down to a small set of
	 * entities even if the outer filter alone wouldn't have justified it.
	 *
	 * The cheapest candidate's `PrefetchFormulaVisitor` populates a per-candidate {@link PrefetchOrder}, but only
	 * the OUTER query plan can fire prefetch (it's a one-shot pre-execution step). The chosen formula is therefore
	 * fed to `outerPrefetchFormulaVisitor` (when supplied) so the outer plan can fold the nested filter's prefetch
	 * demand into its single prefetch decision. The nested plan goes through a fresh {@link FilterByVisitor} pass,
	 * so any `SelectionFormula` instances it produces are new and contribute their full demand to the outer
	 * prefetcher without double-counting.
	 *
	 * Returns {@link EmptyFormula#INSTANCE} when `IndexSelectionVisitor` finds no eligible candidate (vanishingly
	 * rare — the GLOBAL fallback is always offered when the schema has a global index).
	 *
	 * @param queryContext                planning context inherited from the outer query
	 * @param filterBy                    rewritten `FilterBy` to plan
	 * @param outerPrefetchFormulaVisitor the outer query's `PrefetchFormulaVisitor` to fold nested prefetch
	 *                                    demand into; NULL skips the merge
	 * @param stepDescriptionSupplier     description used in `PLANNING_FILTER_NESTED_QUERY` /
	 *                                    `EXECUTION_FILTER_NESTED_QUERY` telemetry
	 * @return the cheapest planned formula wrapped with execution telemetry
	 */
	@Nonnull
	public static Formula planNestedFilteringFormula(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nullable PrefetchFormulaVisitor outerPrefetchFormulaVisitor,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		queryContext.pushStep(QueryPhase.PLANNING_FILTER_NESTED_QUERY, stepDescriptionSupplier);
		try {
			final IndexSelectionResult<?> indexSelectionResult = selectIndexes(queryContext, filterBy);
			if (indexSelectionResult.isEmpty()) {
				return EmptyFormula.INSTANCE;
			}
			//noinspection rawtypes,unchecked
			final List<QueryPlanBuilder> builders = createFilterFormula(
				queryContext, (List) indexSelectionResult.targetIndexes(), filterBy
			);
			if (builders.isEmpty()) {
				return EmptyFormula.INSTANCE;
			}
			final Formula nestedFormula = builders.get(0).getFilterFormula();

			// fold the nested filter's prefetch demand into the outer plan so prefetch can fire when the rewrite
			// narrows down to a small set of entities even if the outer filter alone wouldn't have triggered it
			if (outerPrefetchFormulaVisitor != null) {
				nestedFormula.accept(outerPrefetchFormulaVisitor);
			}

			// preserve telemetry continuity with the legacy nested-planning path
			return new DeferredFormula(
				new FormulaWrapper(
					nestedFormula,
					(executionContext, formula) -> {
						try {
							executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							return formula.compute();
						} finally {
							executionContext.popStep();
						}
					}
				)
			);
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Generates all possible variants of the original formula where cacheable parts are one by one transformed
	 * to the {@link CachePayloadHeader} counterparts and adds them to `result` list. The method is used for debugging
	 * purposes to verify that the {@link QueryPlan} for all of them produce exactly same results.
	 *
	 * Queries that do not request the entity type are skipped entirely: such a query is answered through prefetched
	 * entity bodies, and at this point the prefetch has not happened yet, so the variants would not be comparable.
	 *
	 * Every generated variant is fully equipped (sorter, extra result producers, and the source plan's slicer) -
	 * an incompletely built variant would produce a differently shaped response and report a false inconsistency.
	 *
	 * @param queryContext  planning context of the query
	 * @param targetIndexes candidate index sets, needed to build the sorters of the variants
	 * @param sourcePlan    plan whose filtering formula is varied
	 * @return one plan builder per cacheable variant, empty list when the query is not eligible for the check
	 */
	@Nonnull
	private static List<QueryPlanBuilder> generateCacheableVariantTrees(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull QueryPlanBuilder sourcePlan
	) {
		// when entity type is not known and the query hits global index, the query evaluation relies on prefetch
		// which is not yet done at this moment - so for these queries we need to skip the check
		if (queryContext.getEvitaRequest().isEntityTypeRequested()) {
			// and generate variants with various part of the filtering formula tree converted cacheable counterparts
			final CacheableVariantsGeneratingVisitor variantsGeneratingVisitor = new CacheableVariantsGeneratingVisitor();
			sourcePlan.getFilterFormula().accept(variantsGeneratingVisitor);
			// for each variant create separate query plan
			return variantsGeneratingVisitor.getFormulaVariants()
				.stream()
				.map(
					// create and add copy for the formula with cached variant result
					it -> {
						final QueryPlanBuilder alternativeBuilder = new QueryPlanBuilder(
							queryContext, it, sourcePlan.getFilterByVisitor(),
							sourcePlan.getTargetIndexes(),
							sourcePlan.getPrefetchFormulaVisitor()
						);
						// create sorter and computers for the plan
						final List<QueryPlanBuilder> alternativeBuilderInList = Collections.singletonList(alternativeBuilder);
						createSorter(queryContext, targetIndexes, alternativeBuilderInList);
						createExtraResultProducers(queryContext, alternativeBuilderInList);
						ofNullable(sourcePlan.getSlicer()).ifPresent(alternativeBuilder::setSlicer);
						return alternativeBuilder;
					}
				).toList();
		} else {
			return Collections.emptyList();
		}
	}

	/**
	 * Method creates instance of {@link Sorter} that sorts result of the filtering formula according to input query,
	 * and slices appropriate part of the result to respect limit/offset requirements from the query. No sorting/slicing
	 * is done in this method, only the instance of {@link Sorter} capable of doing it is created and set on
	 * the passed builders.
	 *
	 * Sorters are built per plan, not once for the query: a sorter may exploit the very index the plan filters on
	 * (a pre-sorted attribute index, for instance), so the same `orderBy` yields different sorters for different
	 * candidate plans. A `PLANNING_SORT_ALTERNATIVE` telemetry step is opened per builder only when there is more
	 * than one - with a single builder it would just duplicate its parent.
	 *
	 * @param queryContext  planning context of the query
	 * @param targetIndexes candidate index sets the sorters may take advantage of
	 * @param builders      plans to be equipped with sorters, mutated in place
	 */
	private static void createSorter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull List<QueryPlanBuilder> builders
	) {
		queryContext.pushStep(QueryPhase.PLANNING_SORT);
		try {
			final boolean multipleAlternatives = builders.size() > 1;
			for (QueryPlanBuilder builder : builders) {
				if (multipleAlternatives) {
					queryContext.pushStep(QueryPhase.PLANNING_SORT_ALTERNATIVE, builder::getDescription);
				}
				try {
					final OrderByVisitor orderByVisitor = new OrderByVisitor(
						queryContext, targetIndexes,
						builder.getFilterByVisitor(),
						builder.getFilterFormula()
					);
					ofNullable(queryContext.getOrderBy()).ifPresent(orderByVisitor::visit);
					builder.setSorters(replaceNoSorterIfNecessary(queryContext, orderByVisitor.getSorters()));
				} finally {
					if (multipleAlternatives) {
						queryContext.popStep();
					}
				}
			}
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Configures a slicer for each QueryPlanBuilder if the result form of the EvitaRequest is a paginated list
	 * and any conditional gaps are specified. Slicer is used to accurately calculate the offset of the record on
	 * particular page and its size based on the gap rules definition.
	 *
	 * When neither condition holds the builders are left without a slicer, which means plain pagination - the gap
	 * rules are the only reason a slicer is needed at all.
	 *
	 * **Must be called before the plans are verified against each other.**
	 * {@link #generateCacheableVariantTrees(QueryPlanningContext, List, QueryPlanBuilder)} copies the slicer from
	 * the plan it varies, so a variant generated before the slicer was set would paginate differently and be
	 * reported as an inconsistency.
	 *
	 * @param queryContext  The context of the current query, containing the EvitaRequest.
	 * @param builders      A list of QueryPlanBuilder instances to configure the slicer.
	 */
	private static void createSlicer(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<QueryPlanBuilder> builders
	) {
		final EvitaRequest evitaRequest = queryContext.getEvitaRequest();
		final ResultForm resultForm = evitaRequest.getResultForm();
		if (resultForm == ResultForm.PAGINATED_LIST) {
			final ConditionalGap[] conditionalGaps = evitaRequest.getConditionalGaps();
			if (!ArrayUtils.isEmpty(conditionalGaps)) {
				final ExpressionBasedSlicer slicer = new ExpressionBasedSlicer(conditionalGaps);
				for (QueryPlanBuilder builder : builders) {
					builder.setSlicer(slicer);
				}
			}
		}
	}

	/**
	 * This method replaces no sorters - which should always represent primary keys in ascending order - with the special
	 * implementation in case the entity is not known in the query. In such case the primary keys are translated
	 * different ids and those ids are translated back at the end of the query. Unfortunately the order of the translated
	 * keys might be different than the original order of the primary keys, so we need to sort them here according to
	 * their original primary keys order in ascending fashion.
	 *
	 * @param queryContext query context
	 * @param sorters       identified sorters
	 * @return sorters in input or new implementation that ensures proper sorting by primary keys in ascending order
	 */
	@Nonnull
	private static List<Sorter> replaceNoSorterIfNecessary(@Nonnull QueryPlanningContext queryContext, @Nonnull List<Sorter> sorters) {
		if (!queryContext.isEntityTypeKnown()) {
			int index = -1;
			for (int i = 0; i < sorters.size(); i++) {
				final Sorter sorter = sorters.get(i);
				if (sorter instanceof NoSorter) {
					index = i;
					break;
				}
			}
			if (index > -1) {
				final List<Sorter> result = new ArrayList<>(sorters);
				result.set(index, TranslatedPrimaryKeySorter.INSTANCE);
				return result;
			} else {
				return sorters;
			}
		} else {
			return sorters;
		}
	}

	/**
	 * Method creates list of {@link ExtraResultProducer} implementations that fabricate requested extra data structures
	 * that are somehow connected with the processed query taking existing formula and their memoized results into
	 * account (which is a great advantage comparing to computation in multiple requests as needed in other database
	 * solutions).
	 *
	 * The whole phase - telemetry step included - is skipped when the query carries no `require` container, since
	 * there is nothing to fabricate.
	 *
	 * @param queryContext planning context of the query
	 * @param builders     plans to be equipped with extra result producers, mutated in place
	 */
	private static void createExtraResultProducers(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<QueryPlanBuilder> builders
	) {
		if (queryContext.getRequire() != null) {
			queryContext.pushStep(QueryPhase.PLANNING_EXTRA_RESULT_FABRICATION);
			try {
				final boolean multipleAlternatives = builders.size() > 1;
				for (QueryPlanBuilder builder : builders) {
					if (multipleAlternatives) {
						queryContext.pushStep(
							QueryPhase.PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE,
							builder::getDescription
						);
					}
					try {
						final ExtraResultPlanningVisitor extraResultPlanner = new ExtraResultPlanningVisitor(
							queryContext,
							builder.getTargetIndexes(),
							builder.getFilterFormula(),
							builder.getFilterByVisitor(),
							builder.getSorters(),
							builder.getPrefetchFormulaVisitor()
						);
						extraResultPlanner.visit(queryContext.getRequire());
						builder.setExtraResultProducers(extraResultPlanner.getExtraResultProducers());
					} finally {
						if (multipleAlternatives) {
							queryContext.popStep();
						}
					}
				}
			} finally {
				queryContext.popStep();
			}
		}
	}

	/**
	 * Method verifies that all passed `queryPlanBuilders` produce the very same result as the `mainBuilder` in
	 * the computed response.
	 *
	 * This is a debug-only safety net: every alternative index, and optionally every cacheable variant of its
	 * formula tree, is fully executed and its response compared with the main one. It is therefore **expensive** -
	 * a query that would run one plan runs a plan per candidate instead - and is only reached when one of
	 * the verification {@link DebugMode}s is enabled.
	 *
	 * All executions share one frozen random seed, without which any query using randomized ordering would
	 * legitimately differ between runs and fail the comparison.
	 *
	 * @param context           planning context of the query
	 * @param targetIndexes     candidate index sets, needed when cacheable variants have to be built
	 * @param queryPlanBuilders all plans to be verified, the main one included
	 * @param mainBuilder       the preferred plan whose response is taken as the reference
	 * @throws InconsistentResultsException when any alternative produces a different response
	 */
	static void verifyConsistentResultsInAllPlans(
		@Nonnull QueryPlanningContext context,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull List<QueryPlanBuilder> queryPlanBuilders,
		@Nonnull QueryPlanBuilder mainBuilder
	) {
		// execute the main - bitmap preferring, no caching plan
		final byte[] frozenRandom = RandomUtils.getFrozenRandom();
		final QueryPlan mainPlan = mainBuilder.build();
		final EvitaResponse<EntityClassifier> mainResponse = mainPlan.execute(frozenRandom);

		queryPlanBuilders
			.stream()
			.flatMap(
				sourceBuilder -> Stream.concat(
					// if the builder is not the main one, add it to verified list
					sourceBuilder == mainBuilder ? Stream.empty() : Stream.of(sourceBuilder),
					// for each builder generate cacheable variants and add them to verified list if the debug option is on
					context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES) ?
						generateCacheableVariantTrees(context, targetIndexes, sourceBuilder).stream() : Stream.empty()
				)
			)
			.forEach(
				alternativeBuilder ->
					Stream.of(
							Stream.of(BitmapFavouringNoCachePolicy.INSTANCE),
							// if the debug for testing prefetch is on, add the prefetching policy
							context.isDebugModeEnabled(DebugMode.PREFER_PREFETCHING) ?
								Stream.of(PrefetchFavouringNoCachePolicy.INSTANCE) : Stream.empty(),
							// if the debug for testing caching trees is on, add the caching policy
							context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES) ?
								Stream.of(CacheEnforcingPolicy.INSTANCE) : Stream.empty()
						)
						.flatMap(Function.identity())
						.forEach(
							cachePolicy -> {
								final EvitaResponse<EntityClassifier> alternativeResponse = alternativeBuilder.build().execute(frozenRandom);
								Assert.isPremiseValid(
									mainResponse.equals(alternativeResponse),
									() -> new InconsistentResultsException(mainBuilder, mainResponse, alternativeBuilder, alternativeResponse)
								);
								if (log.isDebugEnabled()) {
									log.debug("Results consistent for: {} and {}", mainBuilder.getDescription(), alternativeBuilder.getDescription());
								}
							}
						)
			);
	}

	/*
		THIS CLASS IS ONLY CONTEMPORARY FAKE CLASS - IT SHOULD NEVER BE USED FOR REAL COMPUTATION!!!
	 */

	/**
	 * This special case of {@link AbstractFormula} is used for negative constraints. These query results need to be
	 * compared against certain superset which is the output of the computation on the same level or in the case
	 * of the root query the entire superset of the index.
	 *
	 * A negation cannot be resolved where it is encountered, because at that moment the set it subtracts from is
	 * not known yet - it is whatever its siblings on the same container level end up producing. The translator
	 * therefore emits this placeholder and {@link #postProcess(Formula[], EnclosingContainerRelation)} replaces it
	 * with a real {@link NotFormula} once the whole level has been collected.
	 *
	 * **The placeholder must never survive into an executable plan.** Everything that would be needed to run it
	 * either throws or answers with a neutral zero - see the individual methods.
	 */
	public static class FutureNotFormula extends AbstractFormula {
		/**
		 * Message of the exception thrown from every operation that would only make sense on a real formula -
		 * reaching any of them means the placeholder was not post-processed away.
		 */
		private static final String ERROR_TEMPORARY = "FutureNotFormula is only temporary placeholder!";
		/**
		 * Stable discriminator of this formula class within the computed formula hash - it is what keeps two
		 * structurally identical trees of different formula types from hashing alike. It must stay constant,
		 * otherwise previously cached results become unreachable.
		 */
		private static final long CLASS_ID = 497139306778809341L;
		/**
		 * This formula represents the real formula to compute the negated set.
		 */
		@Getter private final Formula innerFormula;

		/**
		 * Composes the final formula out of the formulas collected on the current container level, when there is
		 * **no** superset to subtract the negations from.
		 *
		 * Use this overload where the enclosing level is guaranteed to provide the superset later. If the level
		 * turns out to consist of negations only, the result is another {@link FutureNotFormula} that the enclosing
		 * container has to post-process in turn - see
		 * {@link #postProcess(Formula[], EnclosingContainerRelation, Supplier)} for the full transformation table.
		 *
		 * @param collectedFormulas formulas gathered on the current level, negations included
		 * @param relation          how the container joins its children (AND / OR)
		 * @return the composed formula, possibly still a {@link FutureNotFormula}
		 */
		public static Formula postProcess(@Nonnull Formula[] collectedFormulas, @Nonnull EnclosingContainerRelation relation) {
			return postProcess(collectedFormulas, relation, null);
		}

		/**
		 * This method is used to compose the final formula that takes collection of formulas on the current level
		 * of the query and wraps them to the final "not" formula. This is where every {@link FutureNotFormula}
		 * placeholder produced on the level is resolved into a real {@link NotFormula}.
		 *
		 * Method produces these results from these example formulas (in case aggregator function produces `and`):
		 *
		 * - `[ANY, ANY]` -> `and(ANY, ANY)`
		 * - `[ANY, FUTURE_NOT]` -> `not(FUTURE_NOT, ANY)`
		 * - `[ANY, ANY, FUTURE_NOT, FUTURE_NOT]` ->
		 *   `not(or(FUTURE_NOT, FUTURE_NOT), and(ANY, ANY))`
		 * - `[FUTURE_NOT]` -> `not(FUTURE_NOT, superSetFormula)`, or a new placeholder when no superset
		 *   was supplied
		 * - `[FUTURE_NOT, FUTURE_NOT]` -> `not(or(FUTURE_NOT, FUTURE_NOT), superSetFormula)`, or a new
		 *   placeholder when no superset was supplied
		 *
		 * Two aggregators are in play, and mixing them up is the easy mistake here: the positive formulas are
		 * joined by the container's own relation, while the negative ones are joined by its **opposite**, because
		 * `NOT A AND NOT B` is `NOT(A OR B)`. For a disjunctive container the result additionally has to stay
		 * a placeholder: `P OR NOT N` cannot be expressed as a subtraction from `P`, so it is rewritten as
		 * `NOT(N \ P)` and handed one level up for the superset to be applied.
		 *
		 * @param collectedFormulas       formulas gathered on the current level, negations included
		 * @param relation                how the container joins its children (AND / OR)
		 * @param superSetFormulaSupplier supplier of the set the negations subtract from - typically the whole
		 *                                index. NULL means "not known at this level", which forces the result to
		 *                                remain a {@link FutureNotFormula}. It is a supplier because obtaining
		 *                                the superset is not free and most levels never need it
		 * @return the composed formula; {@link EmptyFormula#INSTANCE} when there was nothing to compose
		 */
		public static Formula postProcess(
			@Nonnull Formula[] collectedFormulas,
			@Nonnull EnclosingContainerRelation relation,
			@Nullable Supplier<Formula> superSetFormulaSupplier
		) {
			if (collectedFormulas.length == 0 || (collectedFormulas.length == 1 && collectedFormulas[0] instanceof EmptyFormula)) {
				return EmptyFormula.INSTANCE;
			}

			/* define aggregators */
			final Function<Formula[], Formula> aggregator = switch (relation) {
				case DISJUNCTION -> FormulaFactory::or;
				case CONJUNCTION -> FormulaFactory::and;
			};
			final Function<Formula[], Formula> oppositeAggregator = switch (relation) {
				case DISJUNCTION -> FormulaFactory::and; // Use AND inside NOT for OR container
				case CONJUNCTION -> FormulaFactory::or;  // Use OR inside NOT for AND container
			};

			/* collect all negative formulas */
			final Formula[] notFormulas = Arrays.stream(collectedFormulas)
				.filter(FutureNotFormula.class::isInstance)
				.map(FutureNotFormula.class::cast)
				.map(FutureNotFormula::getInnerFormula)
				.toArray(Formula[]::new);

			/* CASE 1: Only Positive Formulas exist */
			if (notFormulas.length == 0) {
				return aggregator.apply(collectedFormulas);
			}

			/* Prepare the combined negative term (N) */
			/* we need to always use oppositeAggregator for inner negative terms */
			/* e.g., NOT A AND NOT B -> NOT(A OR B) */
			Formula combinedNegatives = notFormulas.length == 1
				? notFormulas[0]
				: oppositeAggregator.apply(notFormulas);

			/* collect all positive formulas */
			final Formula[] otherFormulas = Arrays.stream(collectedFormulas)
				.filter(it -> !(it instanceof FutureNotFormula))
				.toArray(Formula[]::new);

			/* CASE 2: Only Negative Formulas exist */
			if (ArrayUtils.isEmpty(otherFormulas)) {
				if (superSetFormulaSupplier != null) {
					/* If we have a superset (Universe), we subtract negatives from it */
					/* Logic: Universe \ N */
					return new NotFormula(combinedNegatives, superSetFormulaSupplier.get());
				} else {
					/* Just wrap in NOT */
					/* Logic: NOT(N) */
					return new FutureNotFormula(combinedNegatives);
				}
			}
			/* CASE 3: Mixed Positive (P) and Negative (N) Formulas */
			else {
				Formula combinedPositives = otherFormulas.length == 1
					? otherFormulas[0]
					: aggregator.apply(otherFormulas);

				return switch (relation) {
					/* Logic: P AND NOT N  =>  P \ N */ /* Standard Set Difference */
					case CONJUNCTION -> new NotFormula(
						combinedNegatives, // Excluded
						combinedPositives  // Included
					);
					/* Logic: P OR NOT N */
					/* Transformation: NOT( N \ P ) */
					/* We swap arguments and negate the result */
					case DISJUNCTION -> new FutureNotFormula(
						new NotFormula(
							combinedPositives, // Excluded (Subtract P from N)
							combinedNegatives  // Included (Start with N)
						)
					);
				};
			}
		}

		/**
		 * Wraps the formula computing the set that is to be **subtracted**, not the result itself.
		 *
		 * @param innerFormula formula computing the set to be negated
		 */
		public FutureNotFormula(@Nonnull Formula innerFormula) {
			this.innerFormula = innerFormula;
			this.initFields();
		}

		/**
		 * Not supported - cloning implies the placeholder is being treated as part of an executable tree, which is
		 * precisely the situation post-processing exists to prevent.
		 *
		 * @throws UnsupportedOperationException always
		 */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		/**
		 * Always zero - the placeholder never contributes to a cost estimate, because it is always replaced before
		 * candidate plans are compared. Reporting the inner formula's cardinality would be worse than useless: it
		 * describes the set being *removed*, not the one produced.
		 */
		@Override
		public int getEstimatedCardinality() {
			return 0;
		}

		/**
		 * Always zero - the placeholder performs no computation of its own, the {@link NotFormula} that replaces
		 * it carries the real cost.
		 */
		@Override
		public long getOperationCost() {
			return 0L;
		}

		/**
		 * Contributes nothing beyond the class id and the inner formula's own hash - the placeholder holds no
		 * state that could distinguish two instances.
		 */
		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			return 0L;
		}

		/**
		 * @return the constant discriminating this formula type inside computed hashes
		 */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Not supported - a placeholder reaching computation means post-processing failed to replace it, and
		 * a negation without its superset has no defined result. Failing loudly beats returning an arbitrary set.
		 *
		 * @throws UnsupportedOperationException always
		 */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}
	}

	/**
	 * Enumeration representing the logical relationship between enclosing containers used in query planning.
	 * This enum is used in {@link QueryPlanner} to specify how different query components are logically combined.
	 */
	public enum EnclosingContainerRelation {
		/**
		 * Logical OR relation.
		 */
		DISJUNCTION,
		/**
		 * Logical AND relation.
		 */
		CONJUNCTION
	}

}
