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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import graphql.ExecutionInput;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

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
     * Returns client context extensions from sent extensions. If no client context extension is sent, the
     * {@link ClientContextExtension#unknown()} is returned, so we can at least somehow classify the request.
     */
    @Nonnull
    public ClientContextExtension clientContextExtension() {
        return Optional.ofNullable(extensions())
            .map(it -> it.get(ClientContextExtension.CLIENT_CONTEXT_EXTENSION))
            .map(it -> {
                Assert.isTrue(
                    it instanceof Map<?, ?>,
                    () -> new GraphQLInvalidArgumentException("Client context extension is invalid.")
                );
                //noinspection unchecked
                return (Map<String, Object>) it;
            })
            .map(it -> new ClientContextExtension(
                (String) it.get(ClientContextExtension.CLIENT_ID),
                (String) it.get(ClientContextExtension.REQUEST_ID)
            ))
            .orElse(ClientContextExtension.unknown());
    }

    /**
     * Copies request data to execution input as well as settings base execution context.
     * Currently, only query execution start is filled.
     *
     * @return execution input
     */
    public ExecutionInput toExecutionInput() {
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

        return executionInputBuilder.build();
    }
}