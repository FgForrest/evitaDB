/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.EvitaBinaryEntityResponse;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.metric.event.query.FinishedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.PrefetchFactory;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.response.EntityFetchAwareDecorator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.utils.SortUtils;
import io.evitadb.dataType.DataChunk;
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
import java.util.stream.Collectors;

import static io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase.EXTRA_RESULT_ITEM_FABRICATION;
import static java.util.Optional.ofNullable;

/**
 * Query plan contains the full recipe on how the query result is going to be computed. Final result can be acquired
 * by calling {@link #execute()} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@Slf4j
public class QueryPlan {
	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate private final QueryPlanningContext queryContext;
	/**
	 * Source index description of this query plan.
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
	 * Optional visitor that collected information about target entities so that they can
	 * be fetched upfront and filtered/ordered by their properties.
	 */
	@Nonnull
	private final PrefetchFactory prefetchFactory;
	/**
	 * Contains prepared sorter implementation that takes output of the filtering process and sorts the entity
	 * primary keys according to {@link OrderConstraint} in {@link EvitaRequest}.
	 */
	@Getter
	@Nonnull
	private final Sorter sorter;
	/**
	 * Contains collections of computational objects that produce {@link EvitaResponseExtraResult} DTOs in reaction
	 * to {@link RequireConstraint} that are part of the input {@link EvitaRequest}.
	 */
	private final Collection<ExtraResultProducer> extraResultProducers;
	/**
	 * Contains the total count of entities found when the query plan was executed.
	 */
	@Getter
	private int totalRecordCount = -1;
	/**
	 * Contains the primary keys of the entities that were really returned when the query plan was executed.
	 */
	@Getter
	private int[] primaryKeys;
	/**
	 * Contains TRUE if the entities were prefetched when the query plan was executed.
	 */
	@Getter
	private boolean prefetched;

	/**
	 * Creates slice of entity primary keys that respect filtering query, specified sorting and is sliced according
	 * to requested offset and limit.
	 */
	@Nonnull
	private static int[] sortAndSliceResult(
		@Nonnull QueryExecutionContext queryContext,
		int totalRecordCount,
		@Nonnull Formula filteringFormula,
		@Nonnull Sorter sorter
	) {
		final EvitaRequest evitaRequest = queryContext.getEvitaRequest();
		final int firstRecordOffset = evitaRequest.getFirstRecordOffset(totalRecordCount);
		sorter = ConditionalSorter.getFirstApplicableSorter(queryContext, sorter);
		final int[] result = new int[Math.min(totalRecordCount, evitaRequest.getLimit())];
		final int peak = sorter.sortAndSlice(
			queryContext, filteringFormula,
			Math.max(0, firstRecordOffset),
			firstRecordOffset + evitaRequest.getLimit(),
			result,
			0
		);
		return SortUtils.asResult(result, peak);
	}

	/**
	 * This method will {@link Formula#compute()} the filtered result, applies ordering and cuts out the requested page.
	 * Method is expected to be called only once per request.
	 */
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T execute() {
		return execute(null);
	}

	/**
	 * This method will {@link Formula#compute()} the filtered result, applies ordering and cuts out the requested page.
	 * Method is expected to be called only once per request.
	 *
	 * @param frozenRandom the frozen random state to use (non-null for deterministic results, null for random results)
	 * @see io.evitadb.utils.RandomUtils#getFrozenRandom()
	 */
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T execute(@Nullable byte[] frozenRandom) {
		try (final QueryExecutionContext executionContext = this.queryContext.createExecutionContext(frozenRandom)) {
			executionContext.pushStep(QueryPhase.EXECUTION);

			try {
				// prefetch the entities to allow using them in filtering / sorting in next step
				this.prefetchFactory.createPrefetchLambdaIfNeededOrWorthwhile(executionContext)
					.ifPresent(prefetchLambda -> {
						executionContext.pushStep(QueryPhase.EXECUTION_PREFETCH);
						try {
							this.prefetched = true;
							prefetchLambda.run();
						} finally {
							executionContext.popStep();
						}
					});

				executionContext.pushStep(QueryPhase.EXECUTION_FILTER);
				try {
					// this call triggers the filtering computation and cause memoization of results
					this.filter.initialize(executionContext);
					this.totalRecordCount = this.filter.compute().size();
				} finally {
					executionContext.popStep();
				}

				executionContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE);
				try {
					this.initSorter(executionContext);
					this.primaryKeys = sortAndSliceResult(
						executionContext, this.totalRecordCount,
						this.filter, this.sorter
					);
				} finally {
					popStep();
				}

				final T result;
				final EvitaRequest evitaRequest = executionContext.getEvitaRequest();
				// if full entity bodies are requested
				if (evitaRequest.isRequiresEntity()) {
					executionContext.pushStep(QueryPhase.FETCHING);
					try {
						if (executionContext.isRequiresBinaryForm()) {
							// transform PKs to rich SealedEntities
							final DataChunk<BinaryEntity> dataChunk = evitaRequest.createDataChunk(
								this.totalRecordCount,
								executionContext.fetchBinaryEntities(this.primaryKeys)
							);

							// this may produce ClassCast exception if client assigns variable to different result than requests
							//noinspection unchecked
							result = (T) new EvitaBinaryEntityResponse(
								evitaRequest.getQuery(),
								dataChunk,
								this.primaryKeys,
								// fabricate extra results
								fabricateExtraResults(executionContext, dataChunk)
							);
						} else {
							// transform PKs to rich SealedEntities
							final DataChunk<SealedEntity> dataChunk = evitaRequest.createDataChunk(
								this.totalRecordCount,
								executionContext.fetchEntities(this.primaryKeys)
							);

							// this may produce ClassCast exception if client assigns variable to different result than requests
							//noinspection unchecked
							result = (T) new EvitaEntityResponse<>(
								evitaRequest.getQuery(),
								dataChunk,
								this.primaryKeys,
								// fabricate extra results
								fabricateExtraResults(executionContext, dataChunk)
							);
						}
					} finally {
						executionContext.popStep();
					}
				} else {
					// this may produce ClassCast exception if client assigns variable to different result than requests
					final DataChunk<EntityReference> dataChunk = evitaRequest.createDataChunk(
						this.totalRecordCount,
						Arrays.stream(this.primaryKeys)
							// returns simple reference to the entity (i.e. primary key and type of the entity)
							// TOBEDONE JNO - we should return a reference including the actual entity version information
							// so that the client might implement its local cache
							.mapToObj(executionContext::translateToEntityReference)
							.collect(Collectors.toList())
					);

					// this may produce ClassCast exception if client assigns variable to different result than requests
					//noinspection unchecked
					result = (T) new EvitaEntityReferenceResponse(
						evitaRequest.getQuery(),
						dataChunk,
						this.primaryKeys,
						// fabricate extra results
						fabricateExtraResults(executionContext, dataChunk)
					);
				}

				ofNullable(this.queryContext.getQueryFinishedEvent())
					.ifPresent(it -> {
						int ioFetchCount = 0;
						int ioFetchedSizeBytes = 0;
						for (S record : result.getRecordData()) {
							if (record instanceof EntityFetchAwareDecorator efad) {
								ioFetchCount += efad.getIoFetchCount();
								ioFetchedSizeBytes += efad.getIoFetchedBytes();
							}
						}
						it.finish(
							this.prefetched,
							this.filter.getEstimatedCardinality(),
							this.primaryKeys == null ? 0 : this.primaryKeys.length,
							this.totalRecordCount,
							ioFetchCount,
							ioFetchedSizeBytes,
							this.filter.getEstimatedCost(),
							this.filter.getCost()
						).commit();
					});

				return result;
			} finally {
				executionContext.popStep();
			}
		}
	}

	/**
	 * This method will process all {@link #extraResultProducers} and asks each an every of them to create an extra
	 * result that was requested in the query. Result array is not cached and execution cost is paid for each method
	 * call. This method is expected to be called only once, though.
	 */
	@Nonnull
	public EvitaResponseExtraResult[] fabricateExtraResults(@Nonnull QueryExecutionContext executionContext, @Nonnull DataChunk<? extends Serializable> dataChunk) {
		final LinkedList<EvitaResponseExtraResult> extraResults = new LinkedList<>();
		if (!extraResultProducers.isEmpty()) {
			executionContext.pushStep(QueryPhase.EXTRA_RESULTS_FABRICATION);
			try {
				for (ExtraResultProducer extraResultProducer : extraResultProducers) {
					// register sub-step for each fabricator so that we can track which were the costly ones
					executionContext.pushStep(
						EXTRA_RESULT_ITEM_FABRICATION,
						extraResultProducer.getClass().getSimpleName()
					);
					try {
						final EvitaResponseExtraResult extraResult = extraResultProducer.fabricate(executionContext, dataChunk.getData());
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

		if (executionContext.getEvitaRequest().isQueryTelemetryRequested()) {
			extraResults.add(executionContext.finalizeAndGetTelemetry());
		}

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
		final int offset = evitaRequest.getFirstRecordOffset();
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
		for (ExtraResultProducer extraResultProducer : extraResultProducers) {
			result.append(" + ").append(extraResultProducer.getDescription());
		}
		return result.toString();
	}

	/**
	 * Returns the list of SpanAttributes associated with the Span.
	 *
	 * @return an array of SpanAttribute objects representing the attributes of the Span
	 */
	@Nonnull
	public SpanAttribute[] getSpanAttributes() {
		final Query query = this.getEvitaRequest().getQuery();
		final FinishedEvent queryFinishedEvent = queryContext.getQueryFinishedEvent();
		return new SpanAttribute[] {
			new SpanAttribute("collection", query.getCollection() == null ? "<NONE>" : query.getCollection().toString()),
			new SpanAttribute("filter", query.getFilterBy() == null ? "<NONE>" : query.getFilterBy().toString()),
			new SpanAttribute("order", query.getOrderBy() == null ? "<NONE>" : query.getOrderBy().toString()),
			new SpanAttribute("require", query.getRequire() == null ? "<NONE>" : query.getRequire().toString()),
			new SpanAttribute("prefetch", queryFinishedEvent.getPrefetched()),
			new SpanAttribute("scannedRecords", queryFinishedEvent.getScanned()),
			new SpanAttribute("totalRecordCount", queryFinishedEvent.getFound()),
			new SpanAttribute("returnedRecordCount", queryFinishedEvent.getReturned()),
			new SpanAttribute("fetchedRecordCount", queryFinishedEvent.getFetched()),
			new SpanAttribute("fetchedRecordSizeBytes", queryFinishedEvent.getFetchedSizeBytes()),
			new SpanAttribute("estimatedComplexity", queryFinishedEvent.getEstimatedComplexity()),
			new SpanAttribute("complexity", queryFinishedEvent.getRealComplexity())
		};
	}

	/**
	 * Method initializes sorter and all its nested sorters.
	 * @param executionContext the execution context to use
	 */
	private void initSorter(@Nonnull QueryExecutionContext executionContext) {
		Sorter theSorter = this.sorter;
		while (theSorter != null) {
			if (theSorter instanceof TransactionalDataRelatedStructure tdrs) {
				tdrs.initialize(executionContext);
			}
			theSorter = theSorter.getNextSorter();
		}
	}
}
