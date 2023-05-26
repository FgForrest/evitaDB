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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.QueryTelemetryFieldHeaderDescriptor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extract {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} DTO from response's extra results and
 * converts it to JSON.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class QueryTelemetryDataFetcher implements DataFetcher<JsonNode> {

	@Nonnull
	private final ObjectMapper objectMapper;

	@Nullable
	@Override
	public JsonNode get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = environment.getSource();
		final QueryTelemetry queryTelemetry = response.getExtraResult(QueryTelemetry.class);
		if (queryTelemetry == null) {
			return null;
		}

		final boolean formatted = environment.getArgumentOrDefault(QueryTelemetryFieldHeaderDescriptor.FORMATTED.name(), false);
		return objectMapper.valueToTree(QueryTelemetryDto.from(queryTelemetry, formatted));
	}

}
