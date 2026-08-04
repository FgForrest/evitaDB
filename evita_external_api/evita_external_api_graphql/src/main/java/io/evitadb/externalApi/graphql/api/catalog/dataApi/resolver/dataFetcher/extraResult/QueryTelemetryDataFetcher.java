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
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.QueryTelemetryNodeDto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Extracts the {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} tree from the response's extra
 * results and publishes it as the flat, pre-order list of nodes this API declares - see
 * {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.QueryTelemetryNodeDescriptor}.
 *
 * The conversion goes through {@link QueryTelemetryDto} rather than reading the engine object directly, so that the
 * `start` normalization, the derived self time and the reshaped metrics are computed in exactly one place and REST
 * and GraphQL cannot disagree about them. Only the nesting is undone here.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryTelemetryDataFetcher implements DataFetcher<List<QueryTelemetryNodeDto>> {

	@Nullable
	private static QueryTelemetryDataFetcher INSTANCE;

	@Nonnull
	public static QueryTelemetryDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new QueryTelemetryDataFetcher();
		}
		return INSTANCE;
	}

	@Nullable
	@Override
	public List<QueryTelemetryNodeDto> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = Objects.requireNonNull(environment.getSource());
		final QueryTelemetry queryTelemetry = response.getExtraResult(QueryTelemetry.class);
		if (queryTelemetry == null) {
			return null;
		}
		return QueryTelemetryNodeDto.flatten(QueryTelemetryDto.from(queryTelemetry));
	}
}
