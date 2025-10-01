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
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.validation.ValidationError;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

enum DefaultGraphQLErrorHandler implements GraphQLErrorHandler {
    INSTANCE;

    @Nonnull
    @Override
    public HttpResponse handle(
            ServiceRequestContext ctx,
            ExecutionInput input,
            @Nullable ExecutionResult result,
            @Nullable Throwable cause) {

        final MediaType produceType = GraphQLUtil.produceType(ctx.request().headers());
        assert produceType != null; // Checked in DefaultGraphqlService#executeGraphql

        if (cause != null) {
            // graphQL.executeAsync() returns an error in the executionResult with getErrors().
            // Use 500 Internal Server Error because this cause might be unexpected.
            final Map<String, Object> specification;
            if (cause instanceof GraphQLError) {
                specification = ((GraphQLError) cause).toSpecification();
            } else {
                specification = toSpecification(cause);
            }
            return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, produceType, specification);
        }

        if (result != null && result.getErrors().stream().anyMatch(ValidationError.class::isInstance)) {
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, produceType, result.toSpecification());
        }

        return HttpResponse.ofJson(produceType, result != null ? result.toSpecification() : Map.of());
    }

    private static Map<String, Object> toSpecification(Throwable cause) {
        requireNonNull(cause, "cause");
        final String message;
        if (cause.getMessage() != null) {
            message = cause.getMessage();
        } else {
            message = cause.toString();
        }

        // todo lho
//        return DefaultGraphqlService.toSpecification(message);
        final Map<String, Object> error = Map.of("message", message);
        return Map.of("errors", List.of(error));
    }
}
