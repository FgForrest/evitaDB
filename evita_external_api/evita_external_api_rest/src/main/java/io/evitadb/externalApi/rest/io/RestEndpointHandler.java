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

package io.evitadb.externalApi.rest.io;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataDeserializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.undertow.server.HttpServerExchange;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Generic HTTP request handler for processing REST API requests and responses.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class RestEndpointHandler<CTX extends RestHandlingContext> extends EndpointHandler<RestEndpointExecutionContext> {

    @Nonnull
    protected final CTX restHandlingContext;
    @Nonnull
    protected final DataDeserializer dataDeserializer;

    protected RestEndpointHandler(@Nonnull CTX restHandlingContext) {
        this.restHandlingContext = restHandlingContext;
        this.dataDeserializer = new DataDeserializer(
            this.restHandlingContext.getOpenApi(),
            this.restHandlingContext.getEnumMapping()
        );
    }

    @Override
    public void handleRequest(HttpServerExchange serverExchange) {
        instrumentRequest(serverExchange);
    }

    /**
     * Process every request with tracing context, so we can classify it in evitaDB.
     */
    private void instrumentRequest(@Nonnull HttpServerExchange serverExchange) {
        restHandlingContext.getTracingContext().executeWithinBlock(
            "REST",
            serverExchange,
            () -> super.handleRequest(serverExchange)
        );
    }

    @Nonnull
    @Override
    protected RestEndpointExecutionContext createExecutionContext(@Nonnull HttpServerExchange serverExchange) {
        return new RestEndpointExecutionContext(serverExchange);
    }

    @Override
    protected void beforeRequestHandled(@Nonnull RestEndpointExecutionContext executionContext) {
        // tries to create evita session for this exchange
        createSession(executionContext).ifPresent(executionContext::provideSession);
    }

    @Override
    protected void afterRequestHandled(@Nonnull RestEndpointExecutionContext executionContext, @Nonnull EndpointResponse response) {
        // we need to close a current session and commit changes before we send the response to client
        executionContext.closeSessionIfOpen();
    }

    /**
     * Tries to create a {@link EvitaSessionContract} automatically from context.
     */
    @Nullable
    protected Optional<EvitaSessionContract> createSession(@Nonnull RestEndpointExecutionContext exchange) {
        if (!(restHandlingContext instanceof CatalogRestHandlingContext catalogRestHandlingContext)) {
            // we don't have any catalog to create session on
            return Optional.empty();
        }

        final Evita evita = restHandlingContext.getEvita();
        final String catalogName = catalogRestHandlingContext.getCatalogSchema().getName();
        if (modifiesData()) {
            return Optional.of(evita.createReadWriteSession(catalogName));
        } else {
            return Optional.of(evita.createReadOnlySession(catalogName));
        }
    }

    /**
     * Does this endpoint modify evitaDB's data?
     */
    protected boolean modifiesData() {
        return false;
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message) {
        //noinspection unchecked
        return (T) new RestInternalError(message);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause) {
        //noinspection unchecked
        return (T) new RestInternalError(message, cause);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message) {
        //noinspection unchecked
        return (T) new RestInvalidArgumentException(message);
    }

    @Nonnull
    protected Map<String, Object> getParametersFromRequest(@Nonnull RestEndpointExecutionContext exchange) {
        //create copy of parameters
        final Map<String, Deque<String>> parameters = new HashMap<>(exchange.serverExchange().getQueryParameters());

        final Operation operation = restHandlingContext.getEndpointOperation();
        final HashMap<String, Object> parameterData = createHashMap(operation.getParameters().size());
        if(operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                getParameterFromRequest(parameters, parameter).ifPresent(data -> {
                    parameterData.put(parameter.getName(), data);
                    parameters.remove(parameter.getName());
                });
            }
        }

        if(!parameters.isEmpty()) {
            throw new RestInvalidArgumentException("Following parameters are not supported in this particular request, " +
                "please look into OpenAPI schema for more information. Parameters: " + String.join(", ", parameters.keySet()));
        }
        return parameterData;
    }

    @Nonnull
    private Optional<Object> getParameterFromRequest(@Nonnull Map<String, Deque<String>> queryParameters,
                                                     @Nonnull Parameter parameter) {
        final Deque<String> queryParam = queryParameters.get(parameter.getName());
        if (queryParam != null) {
            return Optional.ofNullable(dataDeserializer.deserializeValue(
                getParameterSchema(parameter),
                queryParam.toArray(String[]::new)
            ));
        } else if(Boolean.TRUE.equals(parameter.getRequired())) {
            throw new RestRequiredParameterMissingException("Required parameter " + parameter.getName() +
                " is missing in query data (" + parameter.getIn() + ")");
        }
        return Optional.empty();
    }

    @Nonnull
    @SuppressWarnings("rawtypes")
    protected Schema getParameterSchema(@Nonnull Parameter parameter) {
        return SchemaUtils.getTargetSchemaFromRefOrOneOf(parameter.getSchema(), restHandlingContext.getOpenApi());
    }

}
