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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Extracts individual attribute {@link HistogramContract}s for each attribute from {@link EvitaResponse}s extra results
 * requested by {@link io.evitadb.api.query.require.AttributeHistogram}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeHistogramsDataFetcher implements DataFetcher<List<Map<String, Object>>> {

	private final EntitySchemaContract entitySchema;

	@Nonnull
	@Override
	public List<Map<String, Object>> get(DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = Objects.requireNonNull(environment.getSource());
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		if (attributeHistogram == null) {
			return List.of();
		}
		//noinspection unchecked
		final List<String> attributes = (List<String>) environment.getArguments().get("attributes");
		return attributeHistogram.getHistograms()
			.entrySet()
			.stream()
			.filter(h -> {
				final String attributeName = this.entitySchema
					.getAttribute(h.getKey())
					.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing attribute `" + h.getKey() + "`."))
					.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION);
				return attributes.contains(attributeName);
			})
			.map(h -> Map.of(
				"attributeName", h.getKey(),
				"min", h.getValue().getMin(),
				"max", h.getValue().getMax(),
				"overallCount", h.getValue().getOverallCount(),
				"buckets", (Object) h.getValue().getBuckets()
			))
			.toList();
	}
}
