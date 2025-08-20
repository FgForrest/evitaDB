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
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.debug.CacheableVariantsGeneratingVisitor;
import io.evitadb.core.query.algebra.prefetch.PrefetchFormulaVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.IndexSelectionResult;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.policy.BitmapFavouringNoCachePolicy;
import io.evitadb.core.query.policy.CacheEnforcingPolicy;
import io.evitadb.core.query.policy.PrefetchFavouringNoCachePolicy;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.primaryKey.TranslatedPrimaryKeySorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.spi.chunk.ExpressionBasedSlicer;
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
 * {@link QueryPlanner} translates {@link EvitaRequest} to a {@link QueryPlan}. It has to main functions:
 *
 * - to choose the best index(es) to be used in query execution
 * - to construct the {@link QueryPlan} body that consists of a tree of formulas
 *
 * Query executor doesn't really compute the result - only prepares the recipe for computing it. Result is computed
 * after {@link QueryPlan#execute()} is called. Preparation of the {@link QueryPlan} should be really fast and can be
 * called anytime without big performance penalty.
 *
 * Query executor uses <a href="https://en.wikipedia.org/wiki/Visitor_pattern">Visitor</a> pattern to translate tree
 * of {@link FilterConstraint} to a tree of {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryPlanner {

	/**
	 * Method evaluates the {@link QueryPlanningContext#getEvitaRequest()} and creates an "action plan" that allows to compute
	 * the appropriate response for it. This method is the hearth of the query planner logic.
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
	 * Method evaluates the {@link QueryPlanningContext#getEvitaRequest()} and creates an "action plan" that allows to compute
	 * the limited result that involves only filtering phase.
	 *
	 * Planning passes through these phases:
	 *
	 * 1. filtering formula construction for all possible indexes
	 * a) replacing formulas with cached results
	 * b) selecting the best formula / index combination that would produce result with minimal effort
	 * 2. prefetching the entity bodies if the filtering requires it, or it would produce results faster
	 *
	 * The expensive work will be executed when {@link QueryPlan#execute()} is called outside this method.
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
	 */
	private static IndexSelectionResult<?> selectIndexes(@Nonnull QueryPlanningContext queryContext) {
		queryContext.pushStep(QueryPhase.PLANNING_INDEX_USAGE);
		try {
			final IndexSelectionVisitor indexSelectionVisitor = new IndexSelectionVisitor(queryContext);
			ofNullable(queryContext.getFilterBy()).ifPresent(indexSelectionVisitor::visit);
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
	 */
	@Nonnull
	private static <T extends Index<?>> List<QueryPlanBuilder> createFilterFormula(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes
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
						ofNullable(queryContext.getFilterBy()).ifPresent(filterByVisitor::visit);
						adeptFormula = queryContext.analyse(filterByVisitor.getFormula(prefetchFormulaVisitor));

						final QueryPlanBuilder queryPlanBuilder = new QueryPlanBuilder(
							queryContext, adeptFormula, filterByVisitor, targetIndex, prefetchFormulaVisitor
						);
						if (result.isEmpty() || adeptFormula.getEstimatedCost() < result.get(0).getEstimatedCost()) {
							result.addFirst(queryPlanBuilder);
						} else {
							result.addLast(queryPlanBuilder);
						}
					} finally {
						if (adeptFormula == null) {
							queryContext.popStep(targetIndex.toString());
						} else {
							queryContext.popStep(targetIndex.toStringWithCosts(adeptFormula.getEstimatedCost()));
						}
					}
				} else {
					queryContext.popStep(targetIndex.toString());
				}
			}

			return queryContext.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) ?
				result : result.subList(0, 1);
		} finally {
			if (result.isEmpty()) {
				queryContext.popStep("No index selected!");
			} else {
				queryContext.popStep("Selected index: " + result.get(0).getDescriptionWithCosts());
			}
		}
	}

	/**
	 * Generates all possible variants of the original formula where cacheable parts are one by one transformed
	 * to the {@link CachePayloadHeader} counterparts and adds them to `result` list. The method is used for debugging
	 * purposes to verify that the {@link QueryPlan} for all of them produce exactly same results.
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
	 * is done in this method, only the instance of {@link Sorter} capable of doing it is created and returned.
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
					queryContext.pushStep(QueryPhase.PLANNING_SORT_ALTERNATIVE, builder.getDescription());
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
						queryContext.pushStep(QueryPhase.PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE, builder.getDescription());
					}
					try {
						final ExtraResultPlanningVisitor extraResultPlanner = new ExtraResultPlanningVisitor(
							queryContext,
							builder.getTargetIndexes(),
							builder.getFilterFormula(),
							builder.getFilterByVisitor(),
							builder.getSorters()
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
	 */
	public static class FutureNotFormula extends AbstractFormula {
		private static final String ERROR_TEMPORARY = "FutureNotFormula is only temporary placeholder!";
		private static final long CLASS_ID = 497139306778809341L;
		/**
		 * This formula represents the real formula to compute the negated set.
		 */
		@Getter private final Formula innerFormula;

		/**
		 * This method is used to compose the final formula that takes collection of formulas on the current level
		 * of the query and wraps them to the final "not" formula.
		 *
		 * Method produces these results from these example formulas (in case aggregator function produces `and`):
		 *
		 * - [ANY_FORMULA, ANY_FORMULA] -> [ANY_FORMULA, ANY_FORMULA]
		 * - [ANY_FORMULA, FUTURE_NOT_FORMULA] -> not(FUTURE_NOT_FORMULA, ANY_FORMULA)
		 * - [ANY_FORMULA, ANY_FORMULA, FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA] -> not(and(FUTURE_NOT_FORMULA,FUTURE_NOT_FORMULA), and(ANY_FORMULA, ANY_FORMULA))
		 * - [FUTURE_NOT_FORMULA] -> not(FUTURE_NOT_FORMULA, superSetFormula) ... or exception when not on first level of query
		 * - [FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA] -> not(and(FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA), superSetFormula) ... or exception when not on first level of query
		 */
		public static Formula postProcess(@Nonnull Formula[] collectedFormulas, @Nonnull Function<Formula[], Formula> aggregator) {
			return postProcess(collectedFormulas, aggregator, null);
		}

		/**
		 * This method is used to compose the final formula that takes collection of formulas on the current level
		 * of the query and wraps them to the final "not" formula.
		 *
		 * Method produces these results from these example formulas (in case aggregator function produces `and`):
		 *
		 * - [ANY_FORMULA, ANY_FORMULA] -> [ANY_FORMULA, ANY_FORMULA]
		 * - [ANY_FORMULA, FUTURE_NOT_FORMULA] -> not(FUTURE_NOT_FORMULA, ANY_FORMULA)
		 * - [ANY_FORMULA, ANY_FORMULA, FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA] -> not(and(FUTURE_NOT_FORMULA,FUTURE_NOT_FORMULA), and(ANY_FORMULA, ANY_FORMULA))
		 * - [FUTURE_NOT_FORMULA] -> not(FUTURE_NOT_FORMULA, superSetFormula) ... or exception when not on first level of query
		 * - [FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA] -> not(and(FUTURE_NOT_FORMULA, FUTURE_NOT_FORMULA), superSetFormula) ... or exception when not on first level of query
		 */
		public static Formula postProcess(@Nonnull Formula[] collectedFormulas, @Nonnull Function<Formula[], Formula> aggregator, @Nullable Supplier<Formula> superSetFormulaSupplier) {
			/* collect all negative formulas */
			final Formula[] notFormulas = Arrays.stream(collectedFormulas)
				.filter(FutureNotFormula.class::isInstance)
				.map(FutureNotFormula.class::cast)
				.map(FutureNotFormula::getInnerFormula)
				.toArray(Formula[]::new);
			/* if there are none - just wrap positive formulas with aggregator function */
			if (notFormulas.length == 0) {
				return aggregator.apply(collectedFormulas);
			} else {
				/* collect all positive formulas */
				final Formula[] otherFormulas = Arrays.stream(collectedFormulas)
					.filter(it -> !(it instanceof FutureNotFormula))
					.toArray(Formula[]::new);
				/* if there are none - i.e. we have only negative formulas */
				if (ArrayUtils.isEmpty(otherFormulas)) {
					/* access superset formula  */
					if (superSetFormulaSupplier != null) {
						final Formula superSetFormula = superSetFormulaSupplier.get();
						/* construct not formula using aggregator function if there are multiple negative formulas */
						return new NotFormula(
							notFormulas.length == 1 ? notFormulas[0] : aggregator.apply(notFormulas),
							superSetFormula
						);
						/* delegate FutureNotFormula to upper level */
					} else {
						return new FutureNotFormula(
							notFormulas.length == 1 ? notFormulas[0] : aggregator.apply(notFormulas)
						);
					}
				} else {
					/* construct not formula using aggregator function if there are multiple negative / positive formulas */
					return new NotFormula(
						notFormulas.length == 1 ? notFormulas[0] : aggregator.apply(notFormulas),
						otherFormulas.length == 1 ? otherFormulas[0] : aggregator.apply(otherFormulas)
					);
				}
			}
		}

		public FutureNotFormula(@Nonnull Formula innerFormula) {
			this.innerFormula = innerFormula;
			this.initFields();
		}

		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Override
		public int getEstimatedCardinality() {
			return 0;
		}

		@Override
		public long getOperationCost() {
			return 0L;
		}

		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			return 0L;
		}

		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}
	}

}
