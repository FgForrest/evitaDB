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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.ClientContext;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Executor;

/**
 * Finds out size of particular collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CollectionSizeDataFetcher extends ReadDataFetcher<Integer> {

	@Nonnull private final EntitySchemaContract entitySchema;

	public CollectionSizeDataFetcher(@Nonnull ClientContext clientContext,
	                                 @Nullable Executor executor,
	                                 @Nonnull EntitySchemaContract entitySchema) {
		super(clientContext, executor);
		this.entitySchema = entitySchema;
	}

	@Nonnull
	@Override
	public Integer doGet(@Nonnull DataFetchingEnvironment environment) {
		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		return evitaSession.getEntityCollectionSize(entitySchema.getName());
	}
}
