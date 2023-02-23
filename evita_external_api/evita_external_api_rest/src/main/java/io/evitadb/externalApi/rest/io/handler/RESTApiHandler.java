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

package io.evitadb.externalApi.rest.io.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.resolver.DataDeserializer;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.exception.RESTApiInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RESTApiRequiredParameterMissingException;
import io.evitadb.externalApi.rest.io.SchemaUtils;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generic HTTP request handler for processing REST API requests and responses.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class RESTApiHandler implements HttpHandler {
    private static final String CONTENT_TYPE_CHARSET = "; charset=UTF-8";
    @Nonnull
    protected final RESTApiContext restApiContext;

    protected RESTApiHandler(@Nonnull RESTApiContext restApiContext) {
        this.restApiContext = restApiContext;
        validateContext();
    }

    protected abstract void validateContext();

    /**
     * Validates HTTP request.
     * @param exchange
     */
    protected void validateRequest(@Nonnull HttpServerExchange exchange) {
        if (!acceptsSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.NOT_ACCEPTABLE,
                "Only supported result content types are those officially recommended by OpenApi Spec (`application/json`)."
            );
        }
        if (!bodyHasSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.UNSUPPORTED_MEDIA_TYPE,
                "Only supported request body content types are those officially recommended by OpenApi Spec (`application/json`)."
            );
        }
    }

    /**
     * Reads request body into string.
     *
     * @param exchange
     * @return
     * @throws IOException
     */
    @Nonnull
    protected String readRequestBody(@Nonnull HttpServerExchange exchange) throws IOException {
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

    /**
     * Serializes object with response data into JSON.
     *
     * @param responseData
     * @return
     */
    @Nonnull
    protected String serializeResult(@Nonnull Object responseData) {
        final String json;
        try {
            json = restApiContext.getObjectMapper().writeValueAsString(responseData);
        } catch (JsonProcessingException e) {
            throw new OpenApiInternalError(
                "Could not serialize Java object response to JSON: " + e.getMessage(),
                "Could not provide response data.", e
            );
        }
        return json;
    }

    protected void setSuccessResponse(@Nonnull HttpServerExchange exchange, @Nonnull String data) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, getContentType() + CONTENT_TYPE_CHARSET);
        exchange.getResponseSender().send(data);
    }

    @Nullable
    protected Stream<String> parseAcceptHeaders(@Nonnull HttpServerExchange exchange) {
        final HeaderValues acceptHeaders = exchange.getRequestHeaders().get(Headers.ACCEPT);
        if (acceptHeaders == null) {
            return null;
        }
        return acceptHeaders.stream()
                .flatMap(hv -> Arrays.stream(hv.split(",")))
                .map(String::strip);
    }

    protected boolean bodyHasSupportedContentType(HttpServerExchange exchange) {
        final String bodyContentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (bodyContentType == null) {
            return false;
        }
        return bodyContentType.startsWith(getContentType());
    }

    protected boolean acceptsSupportedContentType(@Nonnull HttpServerExchange exchange) {
        final Stream<String> acceptHeaders = parseAcceptHeaders(exchange);
        if (acceptHeaders == null) {
            return true;
        }
        return acceptHeaders.anyMatch(hv -> hv.equals(getContentType()));
    }

    @Nonnull
    protected String getContentType() {
        return MimeTypes.APPLICATION_JSON;
    }

    protected Map<String, Object> getParametersFromRequest(@Nonnull HttpServerExchange exchange, @Nonnull Operation operation) {
        //create copy of parameters
        final Map<String, Deque<String>> queryParameters = new HashMap<>(exchange.getQueryParameters());

        final HashMap<String, Object> parameterData = new HashMap<>();
        if(operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                getParameterFromRequest(queryParameters, parameter).ifPresent(data -> {
                        parameterData.put(parameter.getName(), data);
                        queryParameters.remove(parameter.getName());
                    }
                );
            }
        }

        if(!queryParameters.isEmpty()) {
            throw new RESTApiInvalidArgumentException("Following parameters are not supported in this particular request, " +
                "please look into OpenAPI schema for more information. Parameters: " + String.join(", ", queryParameters.keySet()));
        }
        return parameterData;
    }

    protected @Nonnull Optional<Object> getParameterFromRequest(final Map<String, Deque<String>> queryParameters, @Nonnull Parameter parameter) {
        final Deque<String> queryParam = queryParameters.get(parameter.getName());
        if(queryParam != null) {
            return Optional.ofNullable(DataDeserializer.deserialize(restApiContext.getOpenApi(), getParameterSchema(parameter), queryParam.toArray(new String[]{})));
        } else if(Boolean.TRUE.equals(parameter.getRequired())) {
            throw new RESTApiRequiredParameterMissingException("Required parameter " + parameter.getName() +
                " is missing in query data (" + parameter.getIn() + ")");
        }
        return Optional.empty();
    }

    @SuppressWarnings("rawtypes")
    protected Schema getParameterSchema(@Nonnull Parameter parameter) {
        return SchemaUtils.getTargetSchemaFromRefOrOneOf(parameter.getSchema(), restApiContext.getOpenApi());
    }
}
