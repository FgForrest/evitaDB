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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Returns collection of {@link EntitySchemaContract}s belonging to {@link io.evitadb.api.requestResponse.schema.CatalogSchemaContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class AllEntitySchemasDataFetcher implements DataFetcher<DataFetcherResult<List<EntitySchemaContract>>> {

	@Nonnull
	@Override
	public DataFetcherResult<List<EntitySchemaContract>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		final List<EntitySchemaContract> entitySchemas = evitaSession.getAllEntityTypes()
			.stream()
			.map(entityType -> evitaSession.getEntitySchema(entityType)
				.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing entity schema for type `" + entityType + "`.")))
			.map(it -> (EntitySchemaContract)it)
			.toList();

		return DataFetcherResult.<List<EntitySchemaContract>>newResult()
			.data(entitySchemas)
			.build();
	}
}
