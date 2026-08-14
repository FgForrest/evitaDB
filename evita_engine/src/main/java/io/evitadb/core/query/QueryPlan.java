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

import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.EvitaBinaryEntityResponse;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.chunk.Slicer;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.PrefetchOrder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaPlanVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.RandomUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase.EXTRA_RESULT_ITEM_FABRICATION;
import static java.util.Optional.ofNullable;

/**
 * Query plan contains the full recipe on how the query result is going to be computed. Final result can be acquired
 * by calling {@link #execute()} method.
 *
 * The plan is assembled by {@link QueryPlanBuilder} during the planning phase and is **single-use** - see
 * {@link #execute(byte[])} for the reasons why it must not be executed twice. The planner may build several
 * alternative plans for the same request and execute all of them in a "dry run" to verify they agree on the result;
 * each such execution gets its own isolated {@link QueryExecutionContext}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@Slf4j
public class QueryPlan {
	/**
	 * Converter placeholder used for result forms that cannot convert a {@link SealedEntity} into a client-requested
	 * custom type - i.e. entity references and binary entities, where no {@link SealedEntity} can ever reach the
	 * converter. Reaching it therefore signals a planning error rather than an unsupported client request.
	 */
	public static final Function<SealedEntity, ?> CONVERSION_NOT_SUPPORTED = (sealedEntity) -> {
		throw new UnsupportedOperationException();
	};

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate private final QueryPlanningContext queryContext;
	/**
	 * Source index description of this query plan - the human-readable name of the index set the filtering formula
	 * was built against. It distinguishes the alternative plans the planner considered and is echoed in
	 * {@link #getDescription()}, so it must never contain client data.
	 */
	@Nonnull
	private final String description;
	/**
	 * Filtering formula tree.
	 */
	@Getter
	@Nonnull
	private final Formula filter;
	/**
	 * Optional prefetcher that can be used to load entities in advance to speed up the filtering process or to
	 * retrieve data, that cannot be located in the entity indexes.
	 */
	@Nullable
	private final PrefetchOrder prefetcher;
	/**
	 * Contains prepared sorter implementation that takes output of the filtering process and sorts the entity
	 * primary keys according to {@link OrderConstraint} in {@link EvitaRequest}.
	 *
	 * The sorters form a chain: each one fills as much of the requested slice as it can and hands the remainder to
	 * the next - see {@link #sortAndSliceResult(QueryExecutionContext, int, Formula, Collection, OffsetAndLimit)}.
	 * Never empty; a plan with no ordering carries a single {@link NoSorter#INSTANCE}.
	 */
	@Getter
	@Nonnull
	private final Collection<Sorter> sorters;
	/**
	 * Contains slicer implementation that calculates offset and limit for paginating the result. The slicer runs only
	 * once the total record count is known, because the requested page may lie beyond the end of the result set and
	 * the slicer is what clamps it.
	 */
	private final Slicer slicer;
	/**
	 * Contains collections of computational objects that produce {@link EvitaResponseExtraResult} DTOs in reaction
	 * to {@link RequireConstraint} that are part of the input {@link EvitaRequest}.
	 */
	private final Collection<ExtraResultProducer> extraResultProducers;
	/**
	 * Contains the total count of entities found when the query plan was executed - i.e. the size of the filtering
	 * formula output before paging is applied. Stays `-1` until {@link #execute(byte[])} completes the filtering
	 * phase.
	 */
	@Getter
	private int totalRecordCount = -1;
	/**
	 * Contains the primary keys of the entities that were really returned when the query plan was executed - that is
	 * the requested page only, already sorted. Stays `null` until {@link #execute(byte[])} completes the sorting
	 * phase.
	 */
	@Getter
	private int[] primaryKeys;

	/**
	 * Creates slice of entity primary keys that respect filtering query, specified sorting and is sliced according
	 * to requested offset and limit.
	 *
	 * The `sorters` are consulted in order, each one appending to `result` and reporting through
	 * {@link SortingContext#peak()} how far the buffer is filled; the loop stops as soon as the page is complete.
	 * Sorters are allowed to leave records they cannot order (for instance entities missing the sorted attribute),
	 * so whatever remains unfilled is topped up by {@link NoSorter#INSTANCE} in the natural primary key order - this
	 * is why a partially applicable ordering still yields a full page rather than a short one.
	 *
	 * @param queryContext     the execution context handed to the sorters
	 * @param totalRecordCount total number of records matched by `filteringFormula`
	 * @param filteringFormula the already-computed (memoized) filtering formula whose output is being sorted
	 * @param sorters          the sorter chain to apply, in order
	 * @param offsetAndLimit   the resolved slice of the sorted result to materialize
	 * @return primary keys of the requested slice in sorted order, empty when the offset lies past the last record
	 */
	@Nonnull
	private static int[] sortAndSliceResult(
		@Nonnull QueryExecutionContext queryContext,
		int totalRecordCount,
		@Nonnull Formula filteringFormula,
		@Nonnull Collection<Sorter> sorters,
		@Nonnull OffsetAndLimit offsetAndLimit
	) {
		if (offsetAndLimit.offset() >= totalRecordCount) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		} else {
			final int[] result = new int[Math.min(totalRecordCount - offsetAndLimit.offset(), offsetAndLimit.limit())];
			SortingContext sortingContext = new SortingContext(
				queryContext,
				filteringFormula.compute(),
				offsetAndLimit.offset(),
				offsetAndLimit.offset() + offsetAndLimit.limit(),
				0,
				0
			);
			for (Sorter sorter : sorters) {
				sortingContext = sorter.sortAndSlice(sortingContext, result, null);
				if (sortingContext.peak() == result.length) {
					break;
				}
			}
			// append the rest of the records if not all are sorted
			if (sortingContext.peak() < result.length) {
				NoSorter.INSTANCE.sortAndSlice(sortingContext, result, null);
			}
			return result;
		}
	}

	/**
	 * Retrieves the source query associated with the current query context.
	 *
	 * @return the source {@link Query} used for the current Evita request
	 */
	@Nonnull
	public Query getSourceQuery() {
		return this.queryContext.getEvitaRequest().getQuery();
	}

	/**
	 * This method will {@link Formula#compute()} the filtered result, applies ordering and cuts out the requested page.
	 * Method is expected to be called only once per request.
	 *
	 * @return the response of the form the {@link EvitaRequest} asked for
	 * @see #execute(byte[]) for the full contract, including why a second call is not allowed
	 */
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T execute() {
		return execute(null);
	}

	/**
	 * This method will {@link Formula#compute()} the filtered result, applies ordering and cuts out the requested page.
	 * Method is expected to be called only once per request.
	 *
	 * The single-use rule is not merely a convention: the method publishes its outcome into the mutable
	 * {@link #totalRecordCount} / {@link #primaryKeys} fields, so a second call silently overwrites the first one's
	 * results. When query telemetry was requested it does not even get that far - the final
	 * {@link QueryExecutionContext#finalizeTelemetry()} drains the telemetry stack owned by the *planning* context
	 * and asserts that stack is not already empty, so the second call fails outright.
	 *
	 * Passing a non-null `frozenRandom` puts the execution context into "dry run" mode: results are computed for
	 * plan-comparison purposes only, and telemetry collection is suppressed - see
	 * {@link QueryExecutionContext#isDryRun()}.
	 *
	 * @param frozenRandom the frozen random state to use (non-null for deterministic results, null for random results)
	 * @return the response of the form the {@link EvitaRequest} asked for - entity references, full entity bodies or
	 *         binary entities - carrying the requested page, the total record count and all requested extra results
	 * @see RandomUtils#getFrozenRandom()
	 */
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T execute(@Nullable byte[] frozenRandom) {
		final boolean prefetchedDataSuitableForFiltering = this.prefetcher != null && this.prefetcher.isPrefetchedEntitiesSuitableForFiltering();
		try (
			final QueryExecutionContext executionContext = this.queryContext.createExecutionContext(
				prefetchedDataSuitableForFiltering,
				frozenRandom
			)
		) {
			this.queryContext.pushStep(QueryPhase.EXECUTION);
			try {
				// prefetch the entities to allow using them in filtering / sorting in next step
				if (this.prefetcher != null) {
					try {
						executionContext.pushStep(QueryPhase.EXECUTION_PREFETCH);
						executionContext.prefetchEntities(this.prefetcher);
					} finally {
						executionContext.popStep();
					}
				}

				executionContext.pushStep(QueryPhase.EXECUTION_FILTER);
				try {
					// this call triggers the filtering computation and cause memoization of results
					this.filter.initialize(executionContext);
					this.totalRecordCount = this.filter.compute().size();
				} finally {
					executionContext.popStep();
				}

				// sort and slice results
				executionContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE);
				final EvitaRequest evitaRequest = this.queryContext.getEvitaRequest();
				final OffsetAndLimit offsetAndLimit;
				try {
					this.initSorter(executionContext);
					offsetAndLimit = this.slicer.calculateOffsetAndLimit(
						evitaRequest.getResultForm(), evitaRequest.getStart(), evitaRequest.getLimit(), this.totalRecordCount
					);
					this.primaryKeys = sortAndSliceResult(
						executionContext, this.totalRecordCount,
						this.filter, this.sorters,
						offsetAndLimit
					);
				} finally {
					popStep();
				}

				// finally, fabricate extra results
				final EvitaResponseExtraResult[] extraResults = fabricateExtraResults(executionContext);

				// wrap data and return the result
				final T result;
				//noinspection rawtypes
				final Class expectedType = evitaRequest.getExpectedType();
				// if full entity bodies are requested
				if (evitaRequest.isRequiresEntity()) {
					executionContext.pushStep(QueryPhase.FETCHING);
					try {
						if (executionContext.isRequiresBinaryForm()) {
							// transform PKs to rich SealedEntities
							//noinspection unchecked
							final DataChunk<BinaryEntity> dataChunk = createDataChunk(
								expectedType,
								evitaRequest.getResultForm(),
								offsetAndLimit,
								this.totalRecordCount,
								executionContext.fetchBinaryEntities(this.primaryKeys),
								CONVERSION_NOT_SUPPORTED
							);

							// this may produce ClassCast exception if client assigns variable to different result than requests
							//noinspection unchecked
							result = (T) new EvitaBinaryEntityResponse(
								evitaRequest.getQuery(),
								dataChunk,
								this.primaryKeys,
								extraResults
							);
						} else {
							// transform PKs to rich SealedEntities
							//noinspection unchecked
							final DataChunk<SealedEntity> dataChunk = createDataChunk(
								expectedType,
								evitaRequest.getResultForm(),
								offsetAndLimit,
								this.totalRecordCount,
								executionContext.fetchEntities(this.primaryKeys),
								sealedEntity -> executionContext.convertToRequestedType(expectedType, sealedEntity)
							);

							// this may produce ClassCast exception if client assigns variable to different result than requests
							//noinspection unchecked
							result = (T) new EvitaEntityResponse<>(
								evitaRequest.getQuery(),
								dataChunk,
								this.primaryKeys,
								extraResults
							);
						}
					} finally {
						executionContext.popStep();
					}
				} else {
					// this may produce ClassCast exception if client assigns variable to different result than requests
					//noinspection unchecked
					final DataChunk<EntityReference> dataChunk = createDataChunk(
						expectedType,
						evitaRequest.getResultForm(),
						offsetAndLimit,
						this.totalRecordCount,
						Arrays.stream(this.primaryKeys)
							// returns simple reference to the entity (i.e. primary key and type of the entity)
							// TOBEDONE JNO - we should return a reference including the actual entity version information
							// so that the client might implement its local cache
							.mapToObj(executionContext::translateToEntityReference)
							.collect(Collectors.toList()),
						CONVERSION_NOT_SUPPORTED
					);

					// this may produce ClassCast exception if client assigns variable to different result than requests
					//noinspection unchecked
					result = (T) new EvitaEntityReferenceResponse(
						evitaRequest.getQuery(),
						dataChunk,
						this.primaryKeys,
						extraResults
					);
				}

				recordQueryMetrics(executionContext, result, prefetchedDataSuitableForFiltering);

				executionContext.finalizeTelemetry();

				ofNullable(this.queryContext.getQueryFinishedEvent())
					.ifPresent(
						it -> it.finish(
							prefetchedDataSuitableForFiltering,
							this.filter.getEstimatedCardinality(),
							this.primaryKeys == null ? 0 : this.primaryKeys.length,
							this.totalRecordCount,
							result.getIoFetchCount(),
							result.getIoFetchedSizeBytes(),
							this.filter.getEstimatedCost(),
							this.filter.getCost()
						).commit()
					);
				return result;
			} finally {
				executionContext.popStep();
			}
		}
	}

	/**
	 * Attaches the query level numbers the engine has just finished computing to the {@link QueryPhase#OVERALL} root
	 * of the telemetry tree.
	 *
	 * These are the very same values the `FinishedEvent` reports to JFR and Prometheus, and until now none of them
	 * reached the client debugging the one slow query it actually cares about. Attaching them costs nothing extra:
	 * every value here is either already computed or memoized by the time the query is answered.
	 *
	 * **This works only because the telemetry root travels into the response as a live reference.**
	 * {@link #fabricateExtraResults(QueryExecutionContext)} appends the still-open root to the extra results well
	 * before this point, so writing into it here is visible to the client - exactly the coupling the root's own
	 * `spentTime` already depends on. A well-meaning refactor that hands the response a defensive copy of the tree
	 * would silently drop both.
	 *
	 * The guard is telemetry's own rather than the `FinishedEvent`'s. The two are switched independently, so folding
	 * this into the event's presence check would make the metrics vanish whenever JFR recording happens to be off,
	 * for a client that explicitly asked for a profile.
	 *
	 * **`getTelemetryRoot()` really is the `OVERALL` root here, but only for a non-local reason.** It returns the
	 * *bottom* of its context's telemetry stack, and a nested query's context is seeded with the step that spawned
	 * it rather than with the tree root - see `EntityCollection#createQueryContext(QueryPlanningContext, ...)`. What
	 * keeps query level metrics on the real root is that nested queries are planned through
	 * {@link QueryPlanner#planNestedQuery} and never reach this method: the only contexts that get here come from
	 * the root-seeding `createQueryContext(EvitaRequest, EvitaSessionContract)`. The two debug-mode
	 * {@link QueryPlanner#verifyConsistentResultsInAllPlans} executions do reach it, but they are dry runs and the
	 * guard above rejects them. `QueryTelemetryRootFunctionalTest` pins all of this against a query that really does
	 * run a nested one.
	 *
	 * @param executionContext                  context that knows whether a telemetry tree is being built at all
	 * @param result                            the assembled response, which is where the I/O counters come from
	 * @param prefetchedDataSuitableForFiltering whether the planner filtered over prefetched bodies instead of indexes
	 */
	private void recordQueryMetrics(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull EvitaResponse<?> result,
		boolean prefetchedDataSuitableForFiltering
	) {
		if (executionContext.isTelemetryCollected()) {
			final QueryTelemetry telemetryRoot = this.queryContext.getTelemetryRoot();
			telemetryRoot
				.recordMetric(StepMetric.ESTIMATED_CARDINALITY, this.filter.getEstimatedCardinality())
				.recordMetric(StepMetric.ACTUAL_CARDINALITY, this.totalRecordCount)
				.recordMetric(StepMetric.RECORDS_RETURNED, this.primaryKeys == null ? 0 : this.primaryKeys.length)
				.recordMetric(StepMetric.IO_FETCH_COUNT, result.getIoFetchCount())
				.recordMetric(StepMetric.IO_FETCHED_SIZE_BYTES, result.getIoFetchedSizeBytes())
				.recordMetric(StepMetric.PREFETCHED, prefetchedDataSuitableForFiltering);
			// both costs report Long.MAX_VALUE for "not known" rather than failing - the estimate when the
			// arithmetic overflowed, the real one when the formula was never computed - and that has to surface as
			// an unrecorded metric, not as a nine-quintillion cost on somebody's dashboard
			recordCostIfKnown(telemetryRoot, StepMetric.ESTIMATED_COST, this.filter.getEstimatedCost());
			recordCostIfKnown(telemetryRoot, StepMetric.ACTUAL_COST, this.filter.getCost());

			// the plan that actually ran, recorded here rather than during planning on purpose: by now the winning
			// formula has been computed, so its nodes can report the result counts and real costs the alternatives
			// recorded at planning time necessarily could not. Rendering computes nothing - a branch the formula
			// short-circuited past is legitimately unmemoized and is reported as such.
			//
			// Keeping this below the ACTUAL_COST line is no longer a *correctness* requirement - the renderer
			// reads Formula#getMemoizedCost(), which cannot fall through to a computing cost path - but it is
			// still what makes the plan's costs worth reading: the cost pass above is what prices the nodes, and
			// a node nobody has priced reports no cost at all. Render first and the plan is structurally identical
			// but numerically emptier
			if (executionContext.isTelemetryPlanCollected()) {
				telemetryRoot.recordPlan(FormulaPlanVisitor.toPlan(this.filter));
			}
		}
	}

	/**
	 * Records a formula cost on the telemetry root, unless the formula reported it as unknown.
	 *
	 * @param telemetryRoot telemetry root to record data to
	 * @param metric the cost metric being recorded
	 * @param cost   the value the formula reported, possibly the `Long.MAX_VALUE` "not known" sentinel
	 */
	private static void recordCostIfKnown(
		@Nonnull QueryTelemetry telemetryRoot,
		@Nonnull StepMetric metric,
		long cost
	) {
		if (cost != Long.MAX_VALUE) {
			telemetryRoot.recordMetric(metric, cost);
		}
	}

	/**
	 * This method will process all {@link #extraResultProducers} and asks each an every of them to create an extra
	 * result that was requested in the query. Result array is not cached and execution cost is paid for each method
	 * call. This method is expected to be called only once, though.
	 *
	 * When query telemetry was requested, its root node is appended to the returned array as just another extra
	 * result. Note that the node is appended here while it is still *open* - the telemetry tree is closed later, by
	 * the {@link QueryExecutionContext#finalizeTelemetry()} call in {@link #execute(byte[])}. This is safe only
	 * because what travels into the response is a reference to the very node that call finishes; do not defensively
	 * copy the telemetry tree at this point or the response would carry unfinished timings.
	 *
	 * @param executionContext the execution context to fabricate the extra results with
	 * @return the fabricated extra results, plus the telemetry root when telemetry was requested
	 */
	@Nonnull
	public EvitaResponseExtraResult[] fabricateExtraResults(@Nonnull QueryExecutionContext executionContext) {
		final LinkedList<EvitaResponseExtraResult> extraResults = new LinkedList<>();
		if (!this.extraResultProducers.isEmpty()) {
			executionContext.pushStep(QueryPhase.EXTRA_RESULTS_FABRICATION);
			try {
				for (ExtraResultProducer extraResultProducer : this.extraResultProducers) {
					// register sub-step for each fabricator so that we can track which were the costly ones
					executionContext.pushStep(
						EXTRA_RESULT_ITEM_FABRICATION,
						() -> extraResultProducer.getClass().getSimpleName()
					);
					try {
						final EvitaResponseExtraResult extraResult = extraResultProducer.fabricate(executionContext);
						if (extraResult != null) {
							extraResults.add(extraResult);
						}
					} finally {
						executionContext.popStep();
					}
				}
			} finally {
				executionContext.popStep();
			}
		}

		executionContext.getTelemetryRoot()
			.ifPresent(extraResults::add);

		return extraResults.toArray(EvitaResponseExtraResult[]::new);
	}

	/**
	 * Returns human-readable description of the plan which doesn't reveal any sensitive data. The description may be
	 * logged or inserted into traces.
	 *
	 * @return human-readable description of the plan
	 */
	@Nonnull
	public String getDescription() {
		final StringBuilder result = new StringBuilder(512);
		final EvitaRequest evitaRequest = this.queryContext.getEvitaRequest();
		final int offset = evitaRequest.getStart();
		final int limit = evitaRequest.getLimit();
		final String entityType = ofNullable(evitaRequest.getEntityType()).orElse("<ANY TYPE>");
		result.append("offset ")
			.append(offset)
			.append(" limit ")
			.append(limit)
			.append(" `")
			.append(entityType)
			.append("` entities using ")
			.append(this.description);
		if (this.queryContext.isRequiresBinaryForm()) {
			result.append(" (in binary form)");
		}
		for (ExtraResultProducer extraResultProducer : this.extraResultProducers) {
			result.append(" + ").append(extraResultProducer.getDescription());
		}
		return result.toString();
	}

	/**
	 * Returns the attributes describing this query, to be attached to the tracing span covering its execution.
	 *
	 * The attributes are read off the {@link FinishedEvent} the planning context collected, so this method is only
	 * meaningful **after** {@link #execute(byte[])} has finished - before that the counters would still be zero.
	 * When no {@link FinishedEvent} is being collected at all an empty array is returned rather than a set of blank
	 * attributes, which keeps spans free of misleading zeroes.
	 *
	 * Unlike {@link #getDescription()}, the returned attributes **do** carry the query's constraints verbatim and any
	 * client-supplied labels, so they are not suitable for a context where client data must not appear.
	 *
	 * @return attributes of the span covering this query, or an empty array when no metrics were collected
	 */
	@Nonnull
	public SpanAttribute[] getSpanAttributes() {
		final EvitaRequest evitaRequest = this.getEvitaRequest();
		final Query query = evitaRequest.getQuery();
		final FinishedEvent queryFinishedEvent = this.queryContext.getQueryFinishedEvent();
		if (queryFinishedEvent == null) {
			return SpanAttribute.EMPTY_ARRAY;
		} else {
			final SpanAttribute[] systemAttributes = {
				new SpanAttribute("collection", query.getCollection() == null ? "<NONE>" : query.getCollection().toString()),
				new SpanAttribute("filter", query.getFilterBy() == null ? "<NONE>" : query.getFilterBy().toString()),
				new SpanAttribute("order", query.getOrderBy() == null ? "<NONE>" : query.getOrderBy().toString()),
				new SpanAttribute("require", query.getRequire() == null ? "<NONE>" : query.getRequire().toString()),
				new SpanAttribute("prefetch", queryFinishedEvent.getPrefetched() == null ? "<NONE>" : queryFinishedEvent.getPrefetched()),
				new SpanAttribute("scannedRecords", queryFinishedEvent.getScanned()),
				new SpanAttribute("totalRecordCount", queryFinishedEvent.getFound()),
				new SpanAttribute("returnedRecordCount", queryFinishedEvent.getReturned()),
				new SpanAttribute("fetchedRecordCount", queryFinishedEvent.getFetched()),
				new SpanAttribute("fetchedRecordSizeBytes", queryFinishedEvent.getFetchedSizeBytes()),
				new SpanAttribute("estimatedComplexity", queryFinishedEvent.getEstimatedComplexity()),
				new SpanAttribute("complexity", queryFinishedEvent.getRealComplexity())
			};
			if (evitaRequest.getLabels().length > 0) {
				return ArrayUtils.mergeArrays(
					systemAttributes,
					Arrays.stream(evitaRequest.getLabels())
						.map(label -> new SpanAttribute(label.getLabelName(), label.getLabelValue()))
						.toArray(SpanAttribute[]::new)
				);
			} else {
				return systemAttributes;
			}
		}
	}

	/**
	 * Method creates requested implementation of {@link DataChunk} with results.
	 *
	 * When `data` does not already hold instances of `expectedType`, the elements are run through `converter` - which
	 * is how a client that asked for its own interface or POJO gets it. Only {@link SealedEntity} elements can be
	 * converted this way; anything else is a mismatch between what was planned and what was requested and raises
	 * {@link UnexpectedResultException}. The homogeneity of the list is assumed, so only its first element is probed.
	 *
	 * @param expectedType     the element type the client asked for
	 * @param resultForm       whether a page-based or an offset-based chunk is to be created
	 * @param offsetAndLimit   the resolved slice `data` was taken from
	 * @param totalRecordCount total number of records matched by the query, before paging
	 * @param data             the already-sliced results
	 * @param converter        converts a {@link SealedEntity} into `expectedType`; pass
	 *                         {@link #CONVERSION_NOT_SUPPORTED} for result forms where no conversion can occur
	 * @return the data chunk of the form dictated by `resultForm`
	 * @throws UnexpectedResultException when `data` holds elements that are neither `expectedType` nor convertible
	 */
	@Nonnull
	public <T extends Serializable> DataChunk<T> createDataChunk(
		@Nonnull Class<T> expectedType,
		@Nonnull ResultForm resultForm,
		@Nonnull OffsetAndLimit offsetAndLimit,
		int totalRecordCount,
		@Nonnull List<T> data,
		@Nonnull Function<SealedEntity, ?> converter
	) {
		if (!data.isEmpty()) {
			if (!expectedType.isInstance(data.get(0))) {
				if (data.get(0) instanceof SealedEntity) {
					//noinspection unchecked
					data = (List<T>) data.stream()
						.map(SealedEntity.class::cast)
						.map(converter)
						.toList();
				} else {
					throw new UnexpectedResultException(expectedType, data.get(0).getClass());
				}
			}
		}

		final int limit = offsetAndLimit.limit();
		return switch (resultForm) {
			case PAGINATED_LIST -> new PaginatedList<>(
				limit == 0 ? 1 : offsetAndLimit.length() / limit,
				offsetAndLimit.lastPageNumber(),
				limit,
				totalRecordCount,
				data
			);
			case STRIP_LIST -> new StripList<>(
				offsetAndLimit.offset(),
				limit,
				totalRecordCount,
				data
			);
		};
	}

	/**
	 * Binds every sorter in the chain to the execution context before any of them is asked to sort. Sorters that do
	 * not participate in transactional data (they do not implement {@link TransactionalDataRelatedStructure}) need no
	 * binding and are skipped. Must run before
	 * {@link #sortAndSliceResult(QueryExecutionContext, int, Formula, Collection, OffsetAndLimit)}, since an
	 * uninitialized sorter has no access to the indexes it sorts by.
	 *
	 * @param executionContext the execution context to use
	 */
	private void initSorter(@Nonnull QueryExecutionContext executionContext) {
		for (Sorter theSorter : this.sorters) {
			if (theSorter instanceof TransactionalDataRelatedStructure tdrs) {
				tdrs.initialize(executionContext);
			}
		}
	}

}
