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

package io.evitadb.externalApi.graphql.api.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.GraphQLBuilder;
import io.evitadb.externalApi.graphql.api.tracing.OperationTracingInstrumentation;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.EvitaDataFetcherExceptionHandler;
import io.evitadb.externalApi.graphql.metric.event.request.RequestMetricInstrumentation;
import io.evitadb.externalApi.graphql.traffic.SourceQueryRecordingInstrumentation;
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
    private final Evita evita;
    @Nonnull
    private final CatalogContract catalog;
    @Nonnull
    private final GraphQLSchema graphQLSchema;
    @Nonnull
    private final ObjectMapper objectMapper;

    @Override
    public GraphQL build(@Nonnull GraphQLOptions config) {
        final Instrumentation instrumentation = new ChainedInstrumentation(
            new OperationTracingInstrumentation(),
            new RequestMetricInstrumentation(this.catalog.getName()),
            new EvitaSessionManagingInstrumentation(this.evita, this.catalog.getName()),
            new SourceQueryRecordingInstrumentation(this.objectMapper, this.evita.getConfiguration().server().trafficRecording())
        );
        final EvitaDataFetcherExceptionHandler dataFetcherExceptionHandler = new EvitaDataFetcherExceptionHandler();

        return GraphQL.newGraphQL(this.graphQLSchema)
            .instrumentation(instrumentation)
            .defaultDataFetcherExceptionHandler(dataFetcherExceptionHandler)
            .build();
    }
}
