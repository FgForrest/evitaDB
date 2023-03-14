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

package io.evitadb.externalApi.graphql.api.catalog;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.externalApi.graphql.api.GraphQLBuilder;
import io.evitadb.externalApi.graphql.exception.EvitaDataFetcherExceptionHandler;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Creates pre-configured {@link GraphQL} instance for working with specific {@link CatalogContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class CatalogGraphQLBuilder implements GraphQLBuilder {

    @Nonnull
    private final EvitaContract evita;
    @Nonnull
    private final CatalogContract catalog;
    @Nonnull
    private final GraphQLSchema graphQLSchema;

    @Override
    public GraphQL build() {
        final EvitaSessionManagingInstrumentation instrumentation = new EvitaSessionManagingInstrumentation(evita, catalog.getName());
        final EvitaDataFetcherExceptionHandler dataFetcherExceptionHandler = new EvitaDataFetcherExceptionHandler();

        return GraphQL.newGraphQL(graphQLSchema)
            .instrumentation(instrumentation)
            .defaultDataFetcherExceptionHandler(dataFetcherExceptionHandler)
            .build();
    }
}
