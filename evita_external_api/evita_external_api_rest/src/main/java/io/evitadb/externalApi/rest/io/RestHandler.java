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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataDeserializer;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.utils.Assert;
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

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Generic HTTP request handler for processing REST API requests and responses.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class RestHandler<CTX extends RestHandlingContext> implements HttpHandler {

    private static final String CONTENT_TYPE_CHARSET = "; charset=UTF-8";
    @Nonnull
    protected final CTX restApiHandlingContext;

    protected RestHandler(@Nonnull CTX restApiHandlingContext) {
        this.restApiHandlingContext = restApiHandlingContext;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        validateRequest(exchange);

        final Optional<Object> result = doHandleRequest(exchange);
        if (result == null) {
            sendSuccessResponse(exchange);
        }

        final Object resultObject = result
            .orElseThrow(() -> new HttpExchangeException(StatusCodes.NOT_FOUND, "Requested resource wasn't found."));

        final String resultJson;
        if (resultObject instanceof String) {
            resultJson = (String) resultObject;
        } else {
            resultJson = serializeResult(resultObject);
        }
        sendSuccessResponse(exchange, resultJson);
    }

    @Nonnull
    protected String getContentType() {
        return MimeTypes.APPLICATION_JSON;
    }

    /**
     * Handle request and return response. If null returned, no response body is returned. If empty optional
     * supplied, 404 is returned otherwise passed object is returned.
     */
    @Nullable
    protected abstract Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange);

    /**
     * Validates HTTP request.
     */
    protected void validateRequest(@Nonnull HttpServerExchange exchange) {
        // todo lho this should vary on handler and method
        if (!acceptsSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.NOT_ACCEPTABLE,
                "Only supported result content types are those officially recommended by OpenApi Spec (`" + getContentType() + "`)."
            );
        }
        if (!bodyHasSupportedContentType(exchange)) {
            throw new HttpExchangeException(
                StatusCodes.UNSUPPORTED_MEDIA_TYPE,
                "Only supported request body content types are those officially recommended by OpenApi Spec (`" + getContentType() + "`)."
            );
        }
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

    /**
     * Reads request body into string.
     */
    @Nonnull
    private String readRequestBody(@Nonnull HttpServerExchange exchange) {
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
        } catch (IOException e) {
            throw new RestInternalError("Could not read request body: ", e);
        }
    }

    /**
     * Tries to parse input request body JSON into data class.
     */
    @Nonnull
    protected <T> T parseRequestBody(@Nonnull HttpServerExchange exchange, @Nonnull Class<T> dataClass) {
        final String content = readRequestBody(exchange);
        Assert.isTrue(
            !content.trim().isEmpty(),
            () -> new RestInvalidArgumentException("Request's body contains no data.")
        );

        try {
            return restApiHandlingContext.getObjectMapper().readValue(content, dataClass);
        } catch (JsonProcessingException e) {
            throw new RestInternalError("Could not parse request body: ", e);
        }
    }

    @Nonnull
    protected Map<String, Object> getParametersFromRequest(@Nonnull HttpServerExchange exchange, @Nonnull Operation operation) {
        //create copy of parameters
        final Map<String, Deque<String>> parameters = new HashMap<>(exchange.getQueryParameters());

        final HashMap<String, Object> parameterData = createHashMap(operation.getParameters().size());
        if(operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                getParameterFromRequest(parameters, parameter).ifPresent(data -> {
                        parameterData.put(parameter.getName(), data);
                        parameters.remove(parameter.getName());
                    }
                );
            }
        }

        if(!parameters.isEmpty()) {
            throw new RestInvalidArgumentException("Following parameters are not supported in this particular request, " +
                "please look into OpenAPI schema for more information. Parameters: " + String.join(", ", parameters.keySet()));
        }
        return parameterData;
    }

    @Nonnull
    protected Optional<Object> getParameterFromRequest(@Nonnull Map<String, Deque<String>> queryParameters, @Nonnull Parameter parameter) {
        final Deque<String> queryParam = queryParameters.get(parameter.getName());
        if(queryParam != null) {
            return Optional.ofNullable(DataDeserializer.deserializeValue(restApiHandlingContext.getOpenApi(), getParameterSchema(parameter), queryParam.toArray(new String[]{})));
        } else if(Boolean.TRUE.equals(parameter.getRequired())) {
            throw new RestRequiredParameterMissingException("Required parameter " + parameter.getName() +
                " is missing in query data (" + parameter.getIn() + ")");
        }
        return Optional.empty();
    }

    @Nonnull
    @SuppressWarnings("rawtypes")
    protected Schema getParameterSchema(@Nonnull Parameter parameter) {
        return SchemaUtils.getTargetSchemaFromRefOrOneOf(parameter.getSchema(), restApiHandlingContext.getOpenApi());
    }

    /**
     * Serializes object with response data into JSON.
     */
    @Nonnull
    protected String serializeResult(@Nonnull Object responseData) {
        final String json;
        try {
            json = restApiHandlingContext.getObjectMapper().writeValueAsString(responseData);
        } catch (JsonProcessingException e) {
            throw new OpenApiInternalError(
                "Could not serialize Java object response to JSON: " + e.getMessage(),
                "Could not provide response data.", e
            );
        }
        return json;
    }

    private void sendSuccessResponse(@Nonnull HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.endExchange();
    }

    private void sendSuccessResponse(@Nonnull HttpServerExchange exchange, @Nonnull String data) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, getContentType() + CONTENT_TYPE_CHARSET);
        exchange.getResponseSender().send(data);
    }
}
