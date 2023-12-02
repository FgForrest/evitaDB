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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.payload.CachePayloadHeader;
import io.evitadb.core.exception.InconsistentResultsException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.debug.CacheableVariantsGeneratingVisitor;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula.PrefetchFormulaVisitor;
import io.evitadb.core.query.extraResult.CacheDisabledExtraResultAccessor;
import io.evitadb.core.query.extraResult.CacheTranslatingExtraResultAccessor;
import io.evitadb.core.query.extraResult.CacheableExtraResultProducer;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.IndexSelectionResult;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
	 * Method evaluates the {@link QueryContext#getEvitaRequest()} and creates an "action plan" that allows to compute
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
	public static QueryPlan planQuery(@Nonnull QueryContext context) {
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
			List<QueryPlanBuilder> queryPlanBuilders = createFilterFormula(
				context, indexSelectionResult
			);

			// create sorter
			queryPlanBuilders = createSorter(context, indexSelectionResult.targetIndexes(), queryPlanBuilders);

			// create EvitaResponseExtraResult producers
			queryPlanBuilders = createExtraResultProducers(context, queryPlanBuilders);

			// verify there is at least one plan
			Assert.isPremiseValid(!queryPlanBuilders.isEmpty(), "Unexpectedly no query plan was created!");

			// verify results in alternative indexes
			if (context.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) || context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
				final QueryPlanBuilder mainBuilder = queryPlanBuilders.get(0);
				final QueryPlan mainPlan = mainBuilder.build();
				verifyConsistentResultsInAllPlans(context, queryPlanBuilders, mainBuilder, mainPlan);
			}

			// return the preferred plan
			return queryPlanBuilders.get(0).build();

		} finally {
			context.popStep();
		}
	}

	/**
	 * Method evaluates the {@link QueryContext#getEvitaRequest()} and creates an "action plan" that allows to compute
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
		@Nonnull QueryContext context
	) {
		context.pushStep(QueryPhase.PLANNING_NESTED_QUERY);
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
				context, indexSelectionResult
			);

			// verify there is at least one plan
			Assert.isPremiseValid(!queryPlanBuilders.isEmpty(), "Unexpectedly no query plan was created!");

			// select preferred plan builder
			final QueryPlanBuilder preferredPlan = queryPlanBuilders.get(0);

			// verify results in alternative indexes
			if (context.isDebugModeEnabled(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS) || context.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
				final QueryPlan mainPlan = preferredPlan.build();
				verifyConsistentResultsInAllPlans(context, queryPlanBuilders, preferredPlan, mainPlan);
			}

			// create sorter
			createSorter(context, indexSelectionResult.targetIndexes(), queryPlanBuilders);

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
	 * {@link EntityIndexType#REFERENCED_ENTITY} or {@link EntityIndexType#REFERENCED_HIERARCHY_NODE} that contains
	 * limited subset of the entities related to that placement/relation.
	 */
	private static IndexSelectionResult<?> selectIndexes(@Nonnull QueryContext queryContext) {
		queryContext.pushStep(QueryPhase.PLANNING_INDEX_USAGE);
		try {
			final IndexSelectionVisitor indexSelectionVisitor = new IndexSelectionVisitor(queryContext);
			ofNullable(queryContext.getFilterBy()).ifPresent(indexSelectionVisitor::visit);
			//noinspection rawtypes,unchecked
			return new IndexSelectionResult<>(
				(List)indexSelectionVisitor.getTargetIndexes(),
				indexSelectionVisitor.isTargetIndexQueriedByOtherConstraints()
			);
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
		@Nonnull QueryContext queryContext,
		@Nonnull IndexSelectionResult<T> indexSelectionResult
	) {
		final LinkedList<QueryPlanBuilder> result = new LinkedList<>();
		queryContext.pushStep(QueryPhase.PLANNING_FILTER);
		try {
			final boolean debugCachedVariantTrees = queryContext.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES);
			for (TargetIndexes<T> targetIndex : indexSelectionResult.targetIndexes()) {
				queryContext.pushStep(QueryPhase.PLANNING_FILTER_ALTERNATIVE);
				Formula adeptFormula = null;
				try {
					final FilterByVisitor filterByVisitor = new FilterByVisitor(
						queryContext,
						indexSelectionResult.targetIndexes(),
						targetIndex,
						indexSelectionResult.targetIndexQueriedByOtherConstraints()
					);

					final PrefetchFormulaVisitor prefetchFormulaVisitor = createPrefetchFormulaVisitor(targetIndex);
					ofNullable(prefetchFormulaVisitor).ifPresent(filterByVisitor::registerFormulaPostProcessorIfNotPresent);
					ofNullable(queryContext.getFilterBy()).ifPresent(filterByVisitor::visit);
					// we need the original trees to contain only non-cached forms of formula if debug mode is enabled
					if (debugCachedVariantTrees) {
						adeptFormula = filterByVisitor.getFormula();
					} else {
						adeptFormula = queryContext.analyse(filterByVisitor.getFormula());
					}
					final QueryPlanBuilder queryPlanBuilder = new QueryPlanBuilder(
						queryContext, adeptFormula, targetIndex, prefetchFormulaVisitor
					);
					if (result.isEmpty() || adeptFormula.getEstimatedCost() < result.get(0).getEstimatedCost()) {
						result.addFirst(queryPlanBuilder);
					} else {
						result.addLast(queryPlanBuilder);
					}
				} finally {
					if (adeptFormula == null) {
						queryContext.popStep();
					} else {
						queryContext.popStep(targetIndex.toStringWithCosts(adeptFormula.getEstimatedCost()));
					}
				}
			}

			// if there is debug request for generating all variants of possible filter formula trees with cached results
			if (debugCachedVariantTrees) {
				generateCacheableVariantTrees(queryContext, result);
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
	private static void generateCacheableVariantTrees(@Nonnull QueryContext queryContext, @Nonnull LinkedList<QueryPlanBuilder> result) {
		// when entity type is not known and the query hits global index, the query evaluation relies on prefetch
		// which is not yet don at this moment - so for these queries we need to skip the check
		if (queryContext.getEvitaRequest().isEntityTypeRequested()) {
			final int currentResultSize = result.size();
			// go through all main query plans
			for (int i = 0; i < currentResultSize; i++) {
				final QueryPlanBuilder queryPlanBuilder = result.get(i);
				// and generate variants with various part of the filtering formula tree converted cacheable counterparts
				final CacheableVariantsGeneratingVisitor variantsGeneratingVisitor = new CacheableVariantsGeneratingVisitor();
				queryPlanBuilder.getFilterFormula().accept(variantsGeneratingVisitor);
				// for each variant  create separate query plan
				for (Formula formulaVariant : variantsGeneratingVisitor.getFormulaVariants()) {
					// create and add copy for the formula with cached variant result
					result.add(
						new QueryPlanBuilder(
							queryContext, formulaVariant,
							queryPlanBuilder.getTargetIndexes(),
							queryPlanBuilder.getPrefetchFormulaVisitor()
						)
					);
				}
			}
		}
	}

	/**
	 * Method creates a copy of the original sorter with all the cacheable sorter part implementations replaces with
	 * their cacheable variant.
	 */
	@Nullable
	private static Sorter replaceSorterWithCachedVariant(
		@Nullable Sorter sorter,
		@Nonnull QueryContext queryContext
	) {
		if (sorter == null) {
			return null;
		} else {
			final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();
			final LinkedList<Sorter> sorters = new LinkedList<>();
			boolean cacheableVariantFound = false;
			Sorter nextSorter = sorter;
			do {
				if (nextSorter instanceof final CacheableSorter cacheableSorter) {
					if (cacheableSorter instanceof ConditionalSorter conditionalSorter) {
						if (conditionalSorter.shouldApply(queryContext)) {
							sorters.add(
								cacheableSorter.toSerializableResult(
									cacheableSorter.computeHash(hashFunction),
									hashFunction
								)
							);
							cacheableVariantFound = true;
						} else {
							sorters.add(cacheableSorter.cloneInstance());
						}
					} else {
						sorters.add(
							cacheableSorter.toSerializableResult(
								cacheableSorter.computeHash(hashFunction),
								hashFunction
							)
						);
						cacheableVariantFound = true;
					}
				} else {
					sorters.add(nextSorter.cloneInstance());
				}
				nextSorter = nextSorter.getNextSorter();
			} while (nextSorter != null);

			if (cacheableVariantFound) {
				Sorter replacedSorter = null;
				final Iterator<Sorter> it = sorters.descendingIterator();
				while (it.hasNext()) {
					final Sorter theSorter = it.next();
					replacedSorter = replacedSorter == null ?
						theSorter : theSorter.andThen(replacedSorter);
				}
				return replacedSorter;
			} else {
				return sorter;
			}
		}
	}

	/**
	 * The method returns {@link PrefetchFormulaVisitor} in case the passed `targetIndex` represents
	 * {@link GlobalEntityIndex} o {@link CatalogIndex}. In case of narrowed indexes some query may be omitted -
	 * due the implicit lack of certain entities in such index - this would then must be taken into an account in
	 * {@link SelectionFormula} which would also limit its performance boost to a large extent.
	 */
	@Nullable
	private static PrefetchFormulaVisitor createPrefetchFormulaVisitor(@Nonnull TargetIndexes<?> targetIndex) {
		if (targetIndex.isGlobalIndex() || targetIndex.isCatalogIndex()) {
			return new PrefetchFormulaVisitor();
		} else {
			return null;
		}
	}

	/**
	 * Method creates instance of {@link Sorter} that sorts result of the filtering formula according to input query,
	 * and slices appropriate part of the result to respect limit/offset requirements from the query. No sorting/slicing
	 * is done in this method, only the instance of {@link Sorter} capable of doing it is created and returned.
	 */
	private static List<QueryPlanBuilder> createSorter(
		@Nonnull QueryContext queryContext,
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
						queryContext, targetIndexes, builder, builder.getFilterFormula()
					);
					ofNullable(queryContext.getOrderBy()).ifPresent(orderByVisitor::visit);
					// in case of debug cached variant tree or the entity is not known, we cannot use cache here
					// and we need to retain original non-cached sorter
					final Sorter sorter = orderByVisitor.getSorter();
					builder.appendSorter(sorter);
				} finally {
					if (multipleAlternatives) {
						queryContext.popStep();
					}
				}
			}
			if (queryContext.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
				// create copy of each builder with cacheable variants of the sorter
				return builders.stream()
					.flatMap(builder -> {
						final Sorter sorter = builder.getSorter();
						final Sorter replacedSorter = replaceSorterWithCachedVariant(
							sorter,
							queryContext
						);
						if (sorter != null && replacedSorter != sorter) {
							return Stream.of(
								builder,
								new QueryPlanBuilder(
									queryContext, builder.getFilterFormula(),
									builder.getTargetIndexes(),
									builder.getPrefetchFormulaVisitor(),
									replacedSorter
								)
							);
						} else {
							return Stream.of(builder);
						}
					})
					.collect(Collectors.toList());
			} else {
				return builders;
			}
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Method creates list of {@link ExtraResultProducer} implementations that fabricate requested extra data structures
	 * that are somehow connected with the processed query taking existing formula and their memoized results into
	 * account (which is a great advantage comparing to computation in multiple requests as needed in other database
	 * solutions).
	 */
	private static List<QueryPlanBuilder> createExtraResultProducers(
		@Nonnull QueryContext queryContext,
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
							builder,
							builder.getFilterFormula(),
							builder.getSorter()
						);
						extraResultPlanner.visit(queryContext.getRequire());
						builder.appendExtraResultProducers(extraResultPlanner.getExtraResultProducers());
					} finally {
						if (multipleAlternatives) {
							queryContext.popStep();
						}
					}
				}

				if (queryContext.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)) {
					// create copy of each builder with cacheable variants of the sorter
					return builders.stream()
						.flatMap(
							builder -> {
								if (builder.getExtraResultProducers().stream().noneMatch(CacheableExtraResultProducer.class::isInstance)) {
									return Stream.of(builder);
								} else {
									return Stream.of(
										builder,
										new QueryPlanBuilder(
											queryContext,
											builder.getFilterFormula(),
											builder.getTargetIndexes(),
											builder.getPrefetchFormulaVisitor(),
											builder.getSorter(),
											builder.getExtraResultProducers()
												.stream()
												.map(
													it -> it instanceof CacheableExtraResultProducer cacheableExtraResultProducer ?
														cacheableExtraResultProducer.cloneInstance(CacheDisabledExtraResultAccessor.INSTANCE)
														: it
												)
												.toList()
										),
										new QueryPlanBuilder(
											queryContext,
											builder.getFilterFormula(),
											builder.getTargetIndexes(),
											builder.getPrefetchFormulaVisitor(),
											builder.getSorter(),
											builder.getExtraResultProducers()
												.stream()
												.map(
													it -> it instanceof CacheableExtraResultProducer cacheableExtraResultProducer ?
														cacheableExtraResultProducer.cloneInstance(CacheTranslatingExtraResultAccessor.INSTANCE)
														: it
												)
												.toList()
										)
									);
								}
							})
						.collect(Collectors.toList());
				}
			} finally {
				queryContext.popStep();
			}
		}
		return builders;
	}

	/**
	 * Method verifies that all passed `queryPlanBuilders` produce the very same result as the `mainBuilder` in
	 * the computed response.
	 */
	private static void verifyConsistentResultsInAllPlans(
		@Nonnull QueryContext queryContext,
		@Nonnull List<QueryPlanBuilder> queryPlanBuilders,
		@Nonnull QueryPlanBuilder mainBuilder,
		@Nonnull QueryPlan mainPlan
	) {
		queryContext.executeInDryRun(() -> {
			final EvitaResponse<EntityClassifier> mainResponse = mainPlan.execute();
			for (int i = 1; i < queryPlanBuilders.size(); i++) {
				final QueryPlanBuilder alternativeBuilder = queryPlanBuilders.get(i);
				final EvitaResponse<EntityClassifier> alternativeResponse = alternativeBuilder.build().execute();
				Assert.isPremiseValid(
					mainResponse.equals(alternativeResponse),
					() -> new InconsistentResultsException(mainBuilder, mainResponse, alternativeBuilder, alternativeResponse)
				);
				log.debug("Results consistent for: " + mainBuilder.getDescription() + " and " + alternativeBuilder.getDescription());
			}
		});
	}

	/*
		THIS CLASS IS ONLY CONTEMPORARY FAKE CLASS - IT SHOULD NEVER BE USED FOR REAL COMPUTATION!!!
	 */

	/**
	 * This special case of {@link AbstractFormula} is used for negative constraints. These query results need to be
	 * compared against certain superset which is the output of the computation on the same level or in the case
	 * of the root query the entire superset of the index.
	 */
	@RequiredArgsConstructor
	public static class FutureNotFormula extends AbstractFormula {
		private static final String ERROR_TEMPORARY = "FutureNotFormula is only temporary placeholder!";
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

		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Override
		public int getEstimatedCardinality() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Override
		public long getOperationCost() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Override
		protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Override
		protected long getClassId() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}

		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			throw new UnsupportedOperationException(ERROR_TEMPORARY);
		}
	}

}
