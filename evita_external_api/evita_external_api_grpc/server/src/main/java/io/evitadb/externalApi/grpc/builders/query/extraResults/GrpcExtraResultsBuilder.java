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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * This class is used for building {@link GrpcExtraResults} containing all of requested results in gRPC message types.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcExtraResultsBuilder {

	/**
	 * This method builds all partial additional results from {@link EvitaResponse#getExtraResult(Class)}
	 * and after each result is built, it is added to the {@link GrpcExtraResults}. When all results are built, the final
	 * {@link GrpcExtraResults} is returned.
	 *
	 * @param evitaResponse returned by evita
	 * @param <T>           type of {@link EvitaResponse} entities
	 * @return new {@link GrpcExtraResults} with all partial additional results
	 */
	@Nonnull
	public static <T extends EntityClassifier> GrpcExtraResults buildExtraResults(@Nonnull EvitaResponse<T> evitaResponse) {
		final GrpcExtraResults.Builder extraResults = GrpcExtraResults.newBuilder();
		evitaResponse.getExtraResultTypes().forEach(extraResultType -> {
			final EvitaResponseExtraResult extraResult = evitaResponse.getExtraResult(extraResultType);
			if (extraResult instanceof AttributeHistogram erHistogram) {
				final Map<String, GrpcHistogram> attributeHistograms = GrpcHistogramBuilder.buildAttributeHistogram(erHistogram);
				extraResults.putAllAttributeHistogram(attributeHistograms);
			} else if (extraResult instanceof PriceHistogram erHistogram) {
				extraResults.setPriceHistogram(GrpcHistogramBuilder.buildPriceHistogram(erHistogram));
			} else if (extraResult instanceof FacetSummary erFacetSummary) {
				GrpcFacetSummaryBuilder.buildFacetSummary(extraResults, erFacetSummary);
			} else if (extraResult instanceof Hierarchy erHierarchy) {
				GrpcHierarchyStatisticsBuilder.buildHierarchy(extraResults, erHierarchy);
			} else if (extraResult instanceof QueryTelemetry erQueryTelemetry) {
				extraResults.setQueryTelemetry(GrpcQueryTelemetryBuilder.buildQueryTelemetry(erQueryTelemetry));
			}
		});
		return extraResults.build();
	}

}
