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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import graphql.ExecutionResult;
import graphql.language.SourceLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Response from GraphQL passed as output to client.
 *
 * @param <T> result data
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record GraphQLResponse<T>(@Nullable T data,
                                 @Nullable @JsonInclude(Include.NON_NULL) List<GraphQLResponseError> errors) {

    private GraphQLResponse(@Nullable T data) {
        this(data, null);
    }

    public static <T> GraphQLResponse<T> fromExecutionResult(@Nonnull ExecutionResult executionResult) {
        if (executionResult.getErrors().isEmpty()) {
            return new GraphQLResponse<>(executionResult.getData());
        }

        return new GraphQLResponse<>(
                executionResult.getData(),
                executionResult.getErrors()
                        .stream()
                        .map(e -> new GraphQLResponseError(e.getMessage(), e.getLocations(), e.getPath(), e.getExtensions()))
                        .toList()
        );
    }

    private record GraphQLResponseError(@Nonnull String message,
                                        @Nonnull List<SourceLocation> locations,
                                        @Nonnull List<Object> path,
                                        @Nonnull Map<String, Object> extensions) {}
}
