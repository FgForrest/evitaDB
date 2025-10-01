/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.io.webSocket;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;

import static java.util.Objects.requireNonNull;

/**
 * A handler that maps {@link ExecutionResult#getErrors()} or the specified {@link Throwable}
 * to an {@link HttpResponse}.
 */
@UnstableApi
@FunctionalInterface
public interface GraphQLErrorHandler {

    /**
     * Return the default {@link GraphQLErrorHandler}.
     */
    static GraphQLErrorHandler of() {
        return DefaultGraphQLErrorHandler.INSTANCE;
    }

    /**
     * Maps {@link ExecutionResult#getErrors()} or the specified {@link Throwable} to an {@link HttpResponse}.
     */
    @Nullable
    HttpResponse handle(
            ServiceRequestContext ctx, ExecutionInput input, @Nullable ExecutionResult result,
            @Nullable Throwable cause);

    /**
     * Returns a composed {@link GraphQLErrorHandler} that applies this first and the specified
     * other later if this returns {@code null}.
     */
    default GraphQLErrorHandler orElse(GraphQLErrorHandler other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return (ctx, input, executionResult, cause) -> {
            final HttpResponse response = handle(ctx, input, executionResult, cause);
            if (response != null) {
                return response;
            }
            return other.handle(ctx, input, executionResult, cause);
        };
    }
}
