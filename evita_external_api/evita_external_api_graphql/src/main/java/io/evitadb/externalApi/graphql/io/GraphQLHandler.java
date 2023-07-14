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
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.http.MimeTypes;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generic HTTP request handler for processing {@link GraphQLRequest}s and returning {@link GraphQLResponse}s using passed
 * configured instance of {@link GraphQL}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
@RequiredArgsConstructor
public class GraphQLHandler implements HttpHandler {

    private static final String CONTENT_TYPE_CHARSET = "; charset=UTF-8";

    @Nonnull
    private final ObjectMapper objectMapper;
    @Nonnull
    private final EvitaConfiguration evitaConfiguration;
    @Nonnull
    private final AtomicReference<GraphQL> graphQL;

    @Override
    public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
        validateRequest(exchange);

        final String body = readRequestBody(exchange);
        final GraphQLRequest graphQLRequest = parseRequest(body);
        final ExecutionResult graphQLResponse = executeRequest(graphQLRequest);
        final String serializedResult = serializeResult(GraphQLResponse.fromExecutionResult(graphQLResponse));

        setSuccessResponse(exchange, serializedResult);
    }

    private static void validateRequest(@Nonnull HttpServerExchange exchange) {
        if (!hasSupportedMethod(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.METHOD_NOT_ALLOWED,
                "Only POST method is currently supported."
            );
        }
        if (!acceptsSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.NOT_ACCEPTABLE,
                "Only supported result content types are those officially recommended by GraphQL Spec (`application/graphql+json`, `application/json`)."
            );
        }
        if (!bodyHasSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.UNSUPPORTED_MEDIA_TYPE,
                "Only supported request body content types are those officially recommended by GraphQL Spec (`application/graphql+json`, `application/json`)."
            );
        }
    }

    @Nonnull
    private static String readRequestBody(@Nonnull HttpServerExchange exchange) throws IOException {
        final String bodyContentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        final Charset bodyCharset = Arrays.stream(bodyContentType.split(";"))
            .map(String::trim)
            .filter(part -> part.startsWith("charset"))
            .findFirst()
            .map(charsetPart -> {
                final String[] charsetParts = charsetPart.split("=");
                if (charsetParts.length != 2) {
                    throw new HttpExchangeException(StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Charset has invalid format");
                }
                return charsetParts[1].trim();
            })
            .map(charsetName -> {
                try {
                    return Charset.forName(charsetName);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
                    throw new HttpExchangeException(StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Unsupported charset.");
                }
            })
            .orElse(StandardCharsets.UTF_8);

        try (final InputStream is = exchange.getInputStream();
             final InputStreamReader isr = new InputStreamReader(is, bodyCharset);
             final BufferedReader bf = new BufferedReader(isr)) {
            return bf.lines().collect(Collectors.joining("\n"));
        }
    }

    @Nonnull
    private GraphQLRequest parseRequest(@Nonnull String body) {
        try {
            return objectMapper.readValue(body, GraphQLRequest.class);
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
    private ExecutionResult executeRequest(@Nonnull GraphQLRequest graphQLRequest) {
        try {
            return graphQL.get()
                .executeAsync(graphQLRequest.toExecutionInput())
                .orTimeout(evitaConfiguration.server().shortRunningThreadsTimeoutInSeconds(), TimeUnit.SECONDS)
                .join();
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
    private String serializeResult(@Nonnull GraphQLResponse<?> graphQLResponse) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(graphQLResponse);
        } catch (JsonProcessingException e) {
            throw new GraphQLInternalError(
                "Could not serialize GraphQL API response to JSON: " + e.getMessage(),
                "Could not provide GraphQL API response.",
                e
            );
        }
        return json;
    }

    private static void setSuccessResponse(@Nonnull HttpServerExchange exchange, @Nonnull String data) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, getPreferredResponseContentType(exchange) + CONTENT_TYPE_CHARSET);
        exchange.getResponseSender().send(data);
    }

    @Nullable
    private static Stream<String> parseAcceptHeaders(@Nonnull HttpServerExchange exchange) {
        final HeaderValues acceptHeaders = exchange.getRequestHeaders().get(Headers.ACCEPT);
        if (acceptHeaders == null) {
            return null;
        }
        return acceptHeaders.stream()
                .flatMap(hv -> Arrays.stream(hv.split(",")))
                .map(String::strip);
    }

    private static boolean bodyHasSupportedContentType(HttpServerExchange exchange) {
        final String bodyContentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (bodyContentType == null) {
            return false;
        }
        return bodyContentType.startsWith(MimeTypes.APPLICATION_JSON);
    }

    private static boolean acceptsSupportedContentType(@Nonnull HttpServerExchange exchange) {
        final Stream<String> acceptHeaders = parseAcceptHeaders(exchange);
        if (acceptHeaders == null) {
            return true;
        }
        return acceptHeaders.anyMatch(hv -> hv.equals(MimeTypes.ALL) ||
                        hv.equals(GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON) ||
                        hv.equals(MimeTypes.APPLICATION_JSON));
    }

    private static boolean hasSupportedMethod(@Nonnull HttpServerExchange exchange) {
        return exchange.getRequestMethod().equals(Methods.POST);
    }

    @Nonnull
    private static String getPreferredResponseContentType(@Nonnull HttpServerExchange exchange) {
        final Stream<String> acceptHeaders = parseAcceptHeaders(exchange);
        if (acceptHeaders == null) {
            return GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON;
        }

        if (acceptHeaders.anyMatch(ah -> ah.equals(MimeTypes.ALL) ||
                ah.equals(GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON))) {
            return GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON;
        } else {
            return MimeTypes.APPLICATION_JSON;
        }
    }
}
