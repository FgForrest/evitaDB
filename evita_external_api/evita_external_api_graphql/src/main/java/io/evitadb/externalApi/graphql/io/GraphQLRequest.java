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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import graphql.ExecutionInput;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * Client request for GraphQL.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record GraphQLRequest(@Nonnull String query,
                             @Nullable String operationName,
                             @Nullable Map<String, Object> variables,
                             @Nullable Map<String, Object> extensions) {

    public GraphQLRequest {
        Assert.notNull(
            query,
            "Query cannot be empty."
        );
    }

    @JsonCreator
    private static GraphQLRequest fromJson(@Nonnull @JsonProperty("query") String query,
                                           @Nullable @JsonProperty("operationName") String operationName,
                                           @Nullable @JsonProperty("variables") Map<String, Object> variables,
                                           @Nullable @JsonProperty("extensions") Map<String, Object> extensions) {
        return new GraphQLRequest(query, operationName, variables, extensions);
    }

    /**
     * Copies request data to execution input as well as settings base execution context.
     * Currently, only query execution start is filled.
     *
     * @return execution input
     */
    public ExecutionInput toExecutionInput(@Nonnull GraphQLEndpointExecutionContext executionContext) {
        final ExecutionInput.Builder executionInputBuilder = new ExecutionInput.Builder()
            .query(query());

        if (operationName() != null) {
            executionInputBuilder.operationName(operationName());
        }
        if (variables() != null) {
            executionInputBuilder.variables(variables());
        }
        if (extensions() != null) {
            executionInputBuilder.extensions(extensions());
        }

        executionInputBuilder.graphQLContext(builder ->
            builder.of(GraphQLContextKey.METRIC_EXECUTED_EVENT, executionContext.requestExecutedEvent()));

        return executionInputBuilder.build();
    }
}
