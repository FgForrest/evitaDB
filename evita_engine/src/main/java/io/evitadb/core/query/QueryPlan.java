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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query;

import io.evitadb.api.query.OrderConstraint;
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
import io.evitadb.api.trace.TracingContext.SpanAttribute;
import io.evitadb.core.metric.event.QueryPlanStepExecutedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.PrefetchFormulaVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
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
	@Delegate private final QueryContext queryContext;
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
	@Nullable
	private final PrefetchFormulaVisitor prefetchFormulaVisitor;
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
		@Nonnull QueryContext queryContext,
		int totalRecordCount,
		@Nonnull Formula filteringFormula,
		@Nonnull Sorter sorter
	) {
		final EvitaRequest evitaRequest = queryContext.getEvitaRequest();
		final int firstRecordOffset = evitaRequest.getFirstRecordOffset(totalRecordCount);
		sorter = ConditionalSorter.getFirstApplicableSorter(sorter, queryContext);
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
		queryContext.pushStep(QueryPhase.EXECUTION);
		new QueryPlanStepExecutedEvent(
			QueryPhase.EXECUTION.name(),
			this.filter.getEstimatedCost()
		).commit();

		try {
			// prefetch the entities to allow using them in filtering / sorting in next step
			ofNullable(prefetchFormulaVisitor)
				.ifPresent(it -> {
					final Runnable prefetchLambda = it.createPrefetchLambdaIfNeededOrWorthwhile(queryContext);
					if (prefetchLambda != null) {
						queryContext.pushStep(QueryPhase.EXECUTION_PREFETCH);
						try {
							prefetched = true;
							prefetchLambda.run();
						} finally {
							queryContext.popStep();
						}
					}
				});

			queryContext.pushStep(QueryPhase.EXECUTION_FILTER);
			try {
				// this call triggers the filtering computation and cause memoization of results
				totalRecordCount = filter.compute().size();
			} finally {
				queryContext.popStep();
			}

			queryContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE);
			try {
				primaryKeys = sortAndSliceResult(queryContext, totalRecordCount, filter, sorter);
			} finally {
				popStep();
			}

			final T result;
			final EvitaRequest evitaRequest = queryContext.getEvitaRequest();
			// if full entity bodies are requested
			if (evitaRequest.isRequiresEntity()) {
				queryContext.pushStep(QueryPhase.FETCHING);
				try {
					if (queryContext.isRequiresBinaryForm()) {
						// transform PKs to rich SealedEntities
						final DataChunk<BinaryEntity> dataChunk = evitaRequest.createDataChunk(
							totalRecordCount,
							queryContext.fetchBinaryEntities(primaryKeys)
						);

						// this may produce ClassCast exception if client assigns variable to different result than requests
						//noinspection unchecked
						result = (T) new EvitaBinaryEntityResponse(
							evitaRequest.getQuery(),
							dataChunk,
							// fabricate extra results
							fabricateExtraResults(dataChunk)
						);
					} else {
						// transform PKs to rich SealedEntities
						final DataChunk<SealedEntity> dataChunk = evitaRequest.createDataChunk(
							totalRecordCount,
							queryContext.fetchEntities(primaryKeys)
						);

						// this may produce ClassCast exception if client assigns variable to different result than requests
						//noinspection unchecked
						result = (T) new EvitaEntityResponse(
							evitaRequest.getQuery(),
							dataChunk,
							// fabricate extra results
							fabricateExtraResults(dataChunk)
						);
					}
				} finally {
					queryContext.popStep();
				}
			} else {
				// this may produce ClassCast exception if client assigns variable to different result than requests
				final DataChunk<EntityReference> dataChunk = evitaRequest.createDataChunk(
					totalRecordCount,
					Arrays.stream(primaryKeys)
						// returns simple reference to the entity (i.e. primary key and type of the entity)
						// TOBEDONE JNO - we should return a reference including the actual entity version information
						// so that the client might implement its local cache
						.mapToObj(queryContext::translateToEntityReference)
						.collect(Collectors.toList())
				);

				// this may produce ClassCast exception if client assigns variable to different result than requests
				//noinspection unchecked
				result = (T) new EvitaEntityReferenceResponse(
					evitaRequest.getQuery(),
					dataChunk,
					// fabricate extra results
					fabricateExtraResults(dataChunk)
				);
			}

			return result;
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * This method will process all {@link #extraResultProducers} and asks each an every of them to create an extra
	 * result that was requested in the query. Result array is not cached and execution cost is paid for each method
	 * call. This method is expected to be called only once, though.
	 */
	@Nonnull
	public EvitaResponseExtraResult[] fabricateExtraResults(@Nonnull DataChunk<? extends Serializable> dataChunk) {
		final LinkedList<EvitaResponseExtraResult> extraResults = new LinkedList<>();
		if (!extraResultProducers.isEmpty()) {
			queryContext.pushStep(QueryPhase.EXTRA_RESULTS_FABRICATION);
			try {
				for (ExtraResultProducer extraResultProducer : extraResultProducers) {
					// register sub-step for each fabricator so that we can track which were the costly ones
					queryContext.pushStep(
						EXTRA_RESULT_ITEM_FABRICATION,
						extraResultProducer.getClass().getSimpleName()
					);
					try {
						final EvitaResponseExtraResult extraResult = extraResultProducer.fabricate(dataChunk.getData());
						if (extraResult != null) {
							extraResults.add(extraResult);
						}
					} finally {
						queryContext.popStep();
					}
				}
			} finally {
				queryContext.popStep();
			}
		}

		if (queryContext.getEvitaRequest().isQueryTelemetryRequested()) {
			extraResults.add(queryContext.finalizeAndGetTelemetry());
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
			.append(description);
		if (queryContext.isRequiresBinaryForm()) {
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
		return new SpanAttribute[] {
			new SpanAttribute("prefetch", this.prefetched),
			new SpanAttribute("scannedRecords", this.filter.getEstimatedCardinality()),
			new SpanAttribute("totalRecordCount", this.totalRecordCount),
			new SpanAttribute("returnedRecordCount", this.primaryKeys.length)
		};
	}
}
