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

package io.evitadb.externalApi.graphql.api.system;

import graphql.GraphQL;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.EvitaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.GraphQLBuilder;
import io.evitadb.externalApi.graphql.api.tracing.OperationTracingInstrumentation;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.EvitaDataFetcherExceptionHandler;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Creates pre-configured {@link GraphQL} instance for managing whole {@link EvitaContract} and its catalogs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class SystemGraphQLBuilder implements GraphQLBuilder {

    @Nonnull
    private final Evita evita;
    @Nonnull
    private final GraphQLSchema graphQLSchema;

    @Override
    public GraphQL build(@Nonnull GraphQLOptions config) {
        final Instrumentation instrumentation = new OperationTracingInstrumentation();

        final EvitaDataFetcherExceptionHandler dataFetcherExceptionHandler = new EvitaDataFetcherExceptionHandler();

        return GraphQL.newGraphQL(this.graphQLSchema)
            .instrumentation(instrumentation)
            .defaultDataFetcherExceptionHandler(dataFetcherExceptionHandler)
            .build();
    }
}
