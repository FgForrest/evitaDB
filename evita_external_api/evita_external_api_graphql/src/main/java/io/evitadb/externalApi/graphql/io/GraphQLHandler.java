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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLHandler.GraphQLEndpointExchange;
import io.evitadb.externalApi.http.EndpointExchange;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Generic HTTP request handler for processing {@link GraphQLRequest}s and returning {@link GraphQLResponse}s using passed
 * configured instance of {@link GraphQL}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
@RequiredArgsConstructor
public class GraphQLHandler extends EndpointHandler<GraphQLEndpointExchange, GraphQLResponse<?>> {

    @Nonnull
    private final ObjectMapper objectMapper;
    @Nonnull
    private final EvitaConfiguration evitaConfiguration;
    @Nonnull
    private final AtomicReference<GraphQL> graphQL;

    @Nonnull
    @Override
    protected GraphQLEndpointExchange createEndpointExchange(@Nonnull HttpServerExchange serverExchange,
                                                             @Nonnull String httpMethod,
                                                             @Nullable String requestBodyMediaType,
                                                             @Nullable String preferredResponseMediaType) {
        return new GraphQLEndpointExchange(serverExchange, httpMethod, requestBodyMediaType, preferredResponseMediaType);
    }

    @Override
    @Nonnull
    protected EndpointResponse<GraphQLResponse<?>> doHandleRequest(@Nonnull GraphQLEndpointExchange exchange) {
        final GraphQLRequest graphQLRequest = parseRequestBody(exchange, GraphQLRequest.class);
        final GraphQLResponse<?> graphQLResponse = executeRequest(graphQLRequest);
        return new SuccessEndpointResponse<>(graphQLResponse);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message) {
        //noinspection unchecked
        return (T) new GraphQLInternalError(message);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause) {
        //noinspection unchecked
        return (T) new GraphQLInternalError(message, cause);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message) {
        //noinspection unchecked
        return (T) new GraphQLInvalidUsageException(message);
    }

    @Nonnull
    @Override
    public Set<String> getSupportedHttpMethods() {
        return Set.of(Methods.POST_STRING);
    }

    @Nonnull
    @Override
    public Set<String> getSupportedRequestContentTypes() {
        return Set.of(GraphQLMimeTypes.APPLICATION_JSON);
    }

    @Nonnull
    @Override
    public LinkedHashSet<String> getSupportedResponseContentTypes() {
        final LinkedHashSet<String> mediaTypes = createLinkedHashSet(2);
        mediaTypes.add(GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON);
        mediaTypes.add(GraphQLMimeTypes.APPLICATION_JSON);
        return mediaTypes;
    }

    @Nonnull
    @Override
    protected <T> T parseRequestBody(@Nonnull GraphQLEndpointExchange exchange, @Nonnull Class<T> dataClass) {
        final String rawBody = readRawRequestBody(exchange);
        try {
            return objectMapper.readValue(rawBody, dataClass);
        } catch (IOException e) {
            if (e.getCause() instanceof EvitaInternalError internalError) {
                throw internalError;
            } else if (e.getCause() instanceof EvitaInvalidUsageException invalidUsageException) {
                throw invalidUsageException;
            }
            throw new HttpExchangeException(StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Invalid request body format. Expected JSON object.");
        }
    }

    @Nonnull
    private GraphQLResponse<?> executeRequest(@Nonnull GraphQLRequest graphQLRequest) {
        try {
            final ExecutionResult result = graphQL.get()
                .executeAsync(graphQLRequest.toExecutionInput())
                .orTimeout(evitaConfiguration.server().shortRunningThreadsTimeoutInSeconds(), TimeUnit.SECONDS)
                .join();

            return GraphQLResponse.fromExecutionResult(result);
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                throw new HttpExchangeException(StatusCodes.GATEWAY_TIME_OUT, "Could not complete GraphQL request. Process timed out.");
            }
            // borrowed from graphql.GraphQL.execute(graphql.ExecutionInput)
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw e;
            }
        } catch (GraphQLException e) {
            throw new GraphQLInternalError(
                "Internal GraphQL API error: " + e.getMessage(),
                "Internal GraphQL API error.",
                e
            );
        }
    }

    @Nonnull
    @Override
    protected String serializeResult(@Nonnull GraphQLEndpointExchange exchange, @Nonnull GraphQLResponse<?> response) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new GraphQLInternalError(
                "Could not serialize GraphQL API response to JSON: " + e.getMessage(),
                "Could not provide GraphQL API response.",
                e
            );
        }
        return json;
    }

    protected record GraphQLEndpointExchange(@Nonnull HttpServerExchange serverExchange,
                                             @Nonnull String httpMethod,
                                             @Nullable String requestBodyContentType,
                                             @Nullable String preferredResponseContentType) implements EndpointExchange {}
}
