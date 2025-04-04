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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Extract {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} DTO from response's extra results and
 * converts it to JSON.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryTelemetryDataFetcher implements DataFetcher<JsonNode> {

	// TOBEDONE LHO: use some shared object mapper
	private static final ObjectMapper QUERY_TELEMETRY_OBJECT_MAPPER = new ObjectMapper();

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
	public JsonNode get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = environment.getSource();
		final QueryTelemetry queryTelemetry = response.getExtraResult(QueryTelemetry.class);
		return Optional.ofNullable(queryTelemetry)
			.map(it -> (JsonNode) QUERY_TELEMETRY_OBJECT_MAPPER.valueToTree(QueryTelemetryDto.from(it)))
			.orElse(null);
	}
}
