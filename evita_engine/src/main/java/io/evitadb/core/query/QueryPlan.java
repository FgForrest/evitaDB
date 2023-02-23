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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.EvitaBinaryEntityResponse;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula.PrefetchFormulaVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.DataChunk;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
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
public class QueryPlan {
	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate private final QueryContext queryContext;
	/**
	 * Source index description of this query plan.
	 */
	@Getter @Nonnull
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
	 * This method will {@link Formula#compute()} the filtered result, applies ordering and cuts out the requested page.
	 * Method is expected to be called only once per request.
	 */
	@Nonnull
	public <S extends EntityClassifier, T extends EvitaResponse<S>> T execute() {
		queryContext.pushStep(QueryPhase.EXECUTION);

		try {
			// prefetch the entities to allow using them in filtering / sorting in next step
			ofNullable(prefetchFormulaVisitor)
				.ifPresent(it -> {
					final Runnable prefetchLambda = it.createPrefetchLambdaIfNeededOrWorthwhile(queryContext);
					if (prefetchLambda != null) {
						queryContext.pushStep(QueryPhase.EXECUTION_PREFETCH);
						try {
							prefetchLambda.run();
						} finally {
							queryContext.popStep();
						}
					}
				});

			final int totalRecordCount;
			queryContext.pushStep(QueryPhase.EXECUTION_FILTER);
			try {
				// this call triggers the filtering computation and cause memoization of results
				totalRecordCount = filter.compute().size();
			} finally {
				queryContext.popStep();
			}

			final DataChunk<Integer> primaryKeys;
			queryContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE);
			try {
				primaryKeys = queryContext.createDataChunk(totalRecordCount, filter, sorter);
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
							primaryKeys.getTotalRecordCount(),
							queryContext.fetchBinaryEntities(
								primaryKeys.getData()
									.stream()
									.mapToInt(it -> it)
									.toArray()
							)
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
							primaryKeys.getTotalRecordCount(),
							queryContext.fetchEntities(
								primaryKeys.getData()
									.stream()
									.mapToInt(it -> it)
									.toArray()
							)
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
					primaryKeys.getTotalRecordCount(),
					primaryKeys.stream()
						// returns simple reference to the entity (i.e. primary key and type of the entity)
						.map(queryContext::translateToEntityReference)
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

}
