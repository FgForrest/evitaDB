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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import graphql.execution.InputMapDefinesTooManyFieldsException;
import graphql.execution.NonNullableValueCoercedAsNullException;
import graphql.execution.UnknownOperationException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.trace.TracingBlockReference;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLHandler.GraphQLEndpointExchange;
import io.evitadb.externalApi.http.EndpointExchange;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * HTTP request handler for processing {@link GraphQLRequest}s and returning {@link GraphQLResponse}s using passed
 * configured instance of {@link GraphQL}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
public class GraphQLHandler extends EndpointHandler<GraphQLEndpointExchange> {

    /**
     * Set of GraphQL exceptions that are caused by invalid user input and thus shouldn't return server error.
     */
    private static final Set<Class<? extends GraphQLException>> GRAPHQL_USER_ERRORS = Set.of(
        CoercingSerializeException.class,
        CoercingParseValueException.class,
        NonNullableValueCoercedAsNullException.class,
        InputMapDefinesTooManyFieldsException.class,
        UnknownOperationException.class
    );

    @Nonnull
    private final ObjectMapper objectMapper;
    @Nonnull
    private final ExternalApiTracingContext<Object> tracingContext;
    @Nonnull
    private final EvitaConfiguration evitaConfiguration;
    @Nonnull
    private final AtomicReference<GraphQL> graphQL;

    public GraphQLHandler(@Nonnull ObjectMapper objectMapper,
                          @Nonnull Evita evita,
                          @Nonnull AtomicReference<GraphQL> graphQL) {
        this.objectMapper = objectMapper;
        this.tracingContext = ExternalApiTracingContextProvider.getContext();
        this.evitaConfiguration = evita.getConfiguration();
        this.graphQL = graphQL;
    }

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
    protected EndpointResponse doHandleRequest(@Nonnull GraphQLEndpointExchange exchange) {
        final GraphQLRequest graphQLRequest = parseRequestBody(exchange, GraphQLRequest.class);
        final GraphQLResponse<?> graphQLResponse = tracingContext.executeWithinBlock(
            "GraphQL",
            exchange.serverExchange(),
            () -> executeRequest(graphQLRequest)
        );
        return new SuccessEndpointResponse(graphQLResponse);
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
        return Set.of(MimeTypes.APPLICATION_JSON);
    }

    @Nonnull
    @Override
    public LinkedHashSet<String> getSupportedResponseContentTypes() {
        final LinkedHashSet<String> mediaTypes = createLinkedHashSet(2);
        mediaTypes.add(GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON);
        mediaTypes.add(MimeTypes.APPLICATION_JSON);
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
            final ExecutionInput executionInput = graphQLRequest.toExecutionInput();
            final ExecutionResult result = graphQL.get()
                .executeAsync(executionInput)
                .orTimeout(evitaConfiguration.server().shortRunningThreadsTimeoutInSeconds(), TimeUnit.SECONDS)
                .join();

            // trying to close potential tracing block (created by OperationTracingInstrumentation) in the original thread
            final TracingBlockReference blockReference = executionInput.getGraphQLContext().get(GraphQLContextKey.OPERATION_TRACING_BLOCK);
            if (blockReference != null) {
                blockReference.close();
            }

            return GraphQLResponse.fromExecutionResult(result);
        } catch (CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new HttpExchangeException(StatusCodes.GATEWAY_TIME_OUT, "Could not complete GraphQL request. Process timed out.");
            } else if (GRAPHQL_USER_ERRORS.contains(cause.getClass())) {
                throw new GraphQLInvalidUsageException("Invalid GraphQL API request: " + cause.getMessage());
            } else if (cause instanceof GraphQLException graphQLException) {
                throw new GraphQLInternalError(
                    "Internal GraphQL API error: " + graphQLException.getMessage(),
                    "Internal GraphQL API error.",
                    graphQLException
                );
            } else if (cause instanceof RuntimeException) {
                // borrowed from graphql.GraphQL.execute(graphql.ExecutionInput)
                throw (RuntimeException) cause;
            } else {
                throw e;
            }
        } catch (RuntimeException e) {
            // if there is something weird going on, at least wrap it into our own exception
            throw new GraphQLInternalError(
                "Internal GraphQL API error: " + e.getMessage(),
                "Internal GraphQL API error.",
                e
            );
        }
    }

    @Override
    protected void writeResult(@Nonnull GraphQLEndpointExchange exchange, @Nonnull OutputStream outputStream, @Nonnull Object response) {
        try {
            objectMapper.writeValue(outputStream, response);
        } catch (IOException e) {
            throw new GraphQLInternalError(
                "Could not serialize GraphQL API response to JSON: " + e.getMessage(),
                "Could not provide GraphQL API response.",
                e
            );
        }
    }

    protected record GraphQLEndpointExchange(@Nonnull HttpServerExchange serverExchange,
                                             @Nonnull String httpMethod,
                                             @Nullable String requestBodyContentType,
                                             @Nullable String preferredResponseContentType) implements EndpointExchange {}
}
