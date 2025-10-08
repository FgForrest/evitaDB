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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Function;

/**
 * Returns DTO of {@link ReferenceSchema#getEntityTypeNameVariants(Function)} .
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceSchemaEntityTypeNameVariantsDataFetcher implements DataFetcher<Map<NamingConvention, String>>, ReadDataFetcher {

	@Nullable
	private static ReferenceSchemaEntityTypeNameVariantsDataFetcher INSTANCE;

	@Nonnull
	public static ReferenceSchemaEntityTypeNameVariantsDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReferenceSchemaEntityTypeNameVariantsDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> get(DataFetchingEnvironment environment) {
		final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

		final ReferenceSchemaContract referenceSchema = environment.getSource();
		return referenceSchema.getEntityTypeNameVariants(entityType -> {
			final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
			return requestExecutedEvent.measureInternalEvitaDBExecution(() ->
				evitaSession.getEntitySchemaOrThrow(entityType));
		});
	}
}
