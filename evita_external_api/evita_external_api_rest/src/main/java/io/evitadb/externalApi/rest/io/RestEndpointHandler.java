/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataDeserializer;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.SystemRestHandlingContext;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestRequiredParameterMissingException;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent.OperationType;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.http.AdditionalHttpHeaderNames.INTERNAL_HEADER_PREFIX;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Generic HTTP request handler for processing REST API requests and responses.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public abstract class RestEndpointHandler<CTX extends RestHandlingContext>
	extends EndpointHandler<RestEndpointExecutionContext> {

	@Nonnull
	protected final DataDeserializer dataDeserializer;
	@Nonnull
	protected final CTX restHandlingContext;

	protected RestEndpointHandler(@Nonnull CTX restHandlingContext) {
		this.restHandlingContext = restHandlingContext;
		this.dataDeserializer = new DataDeserializer(
			this.restHandlingContext.getOpenApi(),
			this.restHandlingContext.getEnumMapping()
		);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext serviceRequestContext, @Nonnull HttpRequest httpRequest) {
		return instrumentRequest(serviceRequestContext, httpRequest);
	}

	@Nonnull
	@Override
	protected RestEndpointExecutionContext createExecutionContext(@Nonnull HttpRequest httpRequest) {
		final RestInstanceType instanceType;
		if (this.restHandlingContext instanceof SystemRestHandlingContext) {
			instanceType = RestInstanceType.SYSTEM;
		} else if (this.restHandlingContext instanceof CatalogRestHandlingContext) {
			instanceType = RestInstanceType.CATALOG;
		} else {
			// note: this is a bit of a hack to support lab rest API without rewriting it entirely. We will get rid of it
			// when lab uses gRPC API
			instanceType = RestInstanceType.LAB;
		}

		return new RestEndpointExecutionContext(
			httpRequest,
			this.restHandlingContext.getEvita(),
			new ExecutedEvent(
				instanceType,
				modifiesData() ? OperationType.MUTATION : OperationType.QUERY,
				this.restHandlingContext instanceof CatalogRestHandlingContext catalogRestHandlingContext ?
					catalogRestHandlingContext.getCatalogSchema().getName() :
					null,
				this.restHandlingContext instanceof CollectionRestHandlingContext collectionRestHandlingContext ?
					collectionRestHandlingContext.getEntityType() :
					null,
				httpRequest.method().name(),
				this.restHandlingContext.getEndpointOperation().getOperationId()
			)
		);
	}

	@Override
	protected void beforeRequestHandled(@Nonnull RestEndpointExecutionContext executionContext) {
		// tries to create evita session for this exchange
		createSession(executionContext).ifPresent(executionContext::provideSession);
	}

	@Override
	protected void afterRequestHandled(
		@Nonnull RestEndpointExecutionContext executionContext, @Nonnull EndpointResponse response) {
		// we need to close a current session and commit changes before we send the response to client
		executionContext.closeSessionIfOpen();
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RestInternalError(message);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createInternalError(
		@Nonnull String message, @Nonnull Throwable cause) {
		//noinspection unchecked
		return (T) new RestInternalError(message, cause);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message) {
		//noinspection unchecked
		return (T) new RestInvalidArgumentException(message);
	}

	/**
	 * Tries to create a {@link EvitaSessionContract} automatically from context.
	 */
	@Nonnull
	protected Optional<EvitaSessionContract> createSession(@Nonnull RestEndpointExecutionContext exchange) {
		if (!(this.restHandlingContext instanceof CatalogRestHandlingContext catalogRestHandlingContext)) {
			// we don't have any catalog to create session on
			return Optional.empty();
		}

		final Evita evita = this.restHandlingContext.getEvita();
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
	protected Map<String, Object> getParametersFromRequest(@Nonnull RestEndpointExecutionContext context) {
		final Operation operation = this.restHandlingContext.getEndpointOperation();
		//create builder representation of query params
		final Map<String, Deque<String>> queryParams = context
			.httpRequest()
			.headers()
			.stream()
			.filter(header -> header.getKey().startsWith(INTERNAL_HEADER_PREFIX))
			.collect(Collectors.toMap(
				key -> {
					final String queryParamNameCaseInsensitive = key
						.getKey()
						.toString()
						.replace(
							INTERNAL_HEADER_PREFIX,
							""
						);
					return operation.getParameters()
					                .stream()
					                .filter(
						                parameter -> parameter.getName()
						                                      .equalsIgnoreCase(
							                                      queryParamNameCaseInsensitive)
					                )
					                .findFirst()
					                .map(Parameter::getName)
					                .orElse(queryParamNameCaseInsensitive);
				},
				value -> {
					Deque<String> deque = new ArrayDeque<>(4);
					deque.add(value.getValue());
					return deque;
				},
				(existingDeque, newDeque) -> {
					existingDeque.addAll(newDeque);
					return existingDeque;
				}
			));

		final HashMap<String, Object> parameterData = createHashMap(operation.getParameters().size());

		if (operation.getParameters() != null) {
			for (Parameter parameter : operation.getParameters()) {
				getParameterFromRequest(queryParams, parameter).ifPresent(data -> {
					parameterData.put(parameter.getName(), data);
					queryParams.remove(parameter.getName());
				});
			}
		}

		if (!queryParams.isEmpty()) {
			throw new RestInvalidArgumentException(
				"Following parameters are not supported in this particular request, " +
					"please look into OpenAPI schema for more information. Parameters: " + String.join(
					", ", queryParams.keySet()));
		}
		return parameterData;
	}

	@Nonnull
	@SuppressWarnings("rawtypes")
	protected Schema getParameterSchema(@Nonnull Parameter parameter) {
		return SchemaUtils.getTargetSchemaFromRefOrOneOf(parameter.getSchema(), this.restHandlingContext.getOpenApi());
	}

	/**
	 * Process every request with tracing context, so we can classify it in evitaDB.
	 */
	@Nonnull
	private HttpResponse instrumentRequest(
		@Nonnull ServiceRequestContext serviceRequestContext, @Nonnull HttpRequest httpRequest) {
		return Objects.requireNonNull(
			this.restHandlingContext.getTracingContext().executeWithinBlock(
				"REST",
				httpRequest,
				() -> super.serve(serviceRequestContext, httpRequest)
			)
		);
	}

	@Nonnull
	private Optional<Object> getParameterFromRequest(
		@Nonnull Map<String, Deque<String>> queryParams,
		@Nonnull Parameter parameter
	) {
		final Deque<String> queryParam = queryParams.get(parameter.getName());
		if (queryParam != null && !queryParam.isEmpty()) {
			return Optional.ofNullable(this.dataDeserializer.deserializeValue(
				getParameterSchema(parameter),
				queryParam.toArray(String[]::new)
			));
		} else if (Boolean.TRUE.equals(parameter.getRequired())) {
			throw new RestRequiredParameterMissingException(
				"Required parameter " + parameter.getName() + " is missing in query data (" + parameter.getIn() + ")"
			);
		}
		return Optional.empty();
	}

}
