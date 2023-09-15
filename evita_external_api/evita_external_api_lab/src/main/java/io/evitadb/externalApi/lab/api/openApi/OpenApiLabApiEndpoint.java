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

package io.evitadb.externalApi.lab.api.openApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.lab.api.resolver.endpoint.LabApiHandlingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.ParameterLocation;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint.PathBuilder.newPath;
import static io.swagger.v3.oas.models.PathItem.HttpMethod.*;

/**
 * Single REST endpoint with schema description and handler builder for building data endpoints.
 * It combines {@link PathItem}, {@link Operation} and {@link io.undertow.server.HttpHandler} into one place with useful defaults.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OpenApiLabApiEndpoint extends OpenApiEndpoint<LabApiHandlingContext> {

	public static final String SYSTEM_API_URL_PREFIX = "system";
	public static final String SCHEMA_API_URL_PREFIX = "schema";
	public static final String DATA_API_URL_PREFIX = "data";

	private OpenApiLabApiEndpoint(@Nonnull PathItem.HttpMethod method,
	                              @Nonnull UriPath path,
	                              @Nonnull String operationId,
	                              @Nonnull String description,
	                              @Nullable String deprecationNotice,
	                              @Nonnull List<OpenApiEndpointParameter> parameters,
	                              @Nullable OpenApiSimpleType requestBody,
	                              @Nonnull OpenApiSimpleType successResponse,
	                              @Nonnull Function<LabApiHandlingContext, RestEndpointHandler<?, LabApiHandlingContext>> handlerBuilder) {
		super(method, path, false, operationId, description, deprecationNotice, parameters, requestBody, successResponse, handlerBuilder);
	}

	/**
	 * Creates builder for new data endpoint.
	 */
	@Nonnull
	public static Builder newLabApiEndpoint() {
		return new Builder();
	}

	@Nonnull
	@Override
	public RestEndpointHandler<?, LabApiHandlingContext> toHandler(@Nonnull ObjectMapper objectMapper,
	                                                               @Nonnull Evita evita,
	                                                               @Nonnull OpenAPI openApi,
	                                                               @Nonnull Map<String, Class<? extends Enum<?>>> enumMapping) {
		final LabApiHandlingContext context = new LabApiHandlingContext(
			objectMapper,
			evita,
			openApi,
			enumMapping,
			toOperation()
		);
		return handlerBuilder.apply(context);
	}

	public static class Builder {

		@Nullable private PathItem.HttpMethod method;
		@Nullable private UriPath path = newPath().getPath();

		@Nullable private String operationId;
		@Nullable private String description;
		@Nullable private String deprecationNotice;
		@Nonnull private final List<OpenApiEndpointParameter> parameters;

		@Nullable private OpenApiSimpleType requestBody;
		@Nullable private OpenApiSimpleType successResponse;

		@Nullable private Function<LabApiHandlingContext, RestEndpointHandler<?, LabApiHandlingContext>> handlerBuilder;

		private Builder() {
			this.parameters = new LinkedList<>();
		}

		/**
		 * Set HTTP method of this endpoint.
		 */
		@Nonnull
		public Builder method(@Nonnull PathItem.HttpMethod method) {
			this.method = method;
			return this;
		}

		/**
		 * Set path of this endpoint. Provided builder is already configured with data prefix
		 * `/lab/data`.
		 */
		@Nonnull
		public Builder path(@Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			// prepare new data path
			final PathBuilder pathBuilder = pathBuilderFunction.apply(newPath());

			this.path = pathBuilder.getPath();
			this.parameters.addAll(pathBuilder.getPathParameters());
			return this;
		}

		/**
		 * Sets endpoint operation ID.
		 */
		@Nonnull
		public Builder operationId(@Nonnull String operationId) {
			this.operationId = operationId;
			return this;
		}

		/**
		 * Sets endpoint description.
		 */
		@Nonnull
		public Builder description(@Nonnull String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets endpoint deprecation notice to indicate that the endpoint is deprecated. If null, endpoint is not set
		 * as deprecated.
		 */
		@Nonnull
		public Builder deprecationNotice(@Nullable String deprecationNotice) {
			this.deprecationNotice = deprecationNotice;
			return this;
		}

		/**
		 * Adds single query parameter.
		 */
		@Nonnull
		public Builder queryParameter(@Nonnull OpenApiEndpointParameter queryParameter) {
			Assert.isPremiseValid(
				queryParameter.getLocation().equals(ParameterLocation.QUERY),
				() -> new OpenApiBuildingError("Only query parameters are supported here.")
			);
			this.parameters.add(queryParameter);
			return this;
		}

		/**
		 * Adds list of query parameters to existing query parameters.
		 */
		@Nonnull
		public Builder queryParameters(@Nonnull List<OpenApiEndpointParameter> queryParameters) {
			queryParameters.forEach(queryParameter ->
				Assert.isPremiseValid(
					queryParameter.getLocation().equals(ParameterLocation.QUERY),
					() -> new OpenApiBuildingError("Only query parameters are supported here.")
				)
			);
			this.parameters.addAll(queryParameters);
			return this;
		}

		/**
		 * Sets type of request body if any.
		 */
		@Nonnull
		public Builder requestBody(@Nonnull OpenApiSimpleType requestBodyType) {
			this.requestBody = requestBodyType;
			return this;
		}

		/**
		 * Sets type of response body for successful response.
		 */
		@Nonnull
		public Builder successResponse(@Nonnull OpenApiSimpleType successResponseType) {
			this.successResponse = successResponseType;
			return this;
		}

		/**
		 * Sets handler builder.
		 */
		@Nonnull
		public Builder handler(@Nonnull Function<LabApiHandlingContext, RestEndpointHandler<?, LabApiHandlingContext>> handlerBuilder) {
			this.handlerBuilder = handlerBuilder;
			return this;
		}

		@Nonnull
		public OpenApiLabApiEndpoint build() {
			Assert.isPremiseValid(
				path != null,
				() -> new OpenApiBuildingError("Missing endpoint path.")
			);
			Assert.isPremiseValid(
				method != null,
				() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing method.")
			);
			Assert.isPremiseValid(
				operationId != null && !operationId.isEmpty(),
				() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing operationId.")
			);
			Assert.isPremiseValid(
				description != null && !description.isEmpty(),
				() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing description.")
			);

			if (Set.of(POST, PUT, PATCH).contains(method)) {
				Assert.isPremiseValid(
					requestBody != null,
					() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing request body.")
				);
			} else if (Set.of(GET, HEAD, TRACE, OPTIONS).contains(method)) {
				Assert.isPremiseValid(
					requestBody == null,
					() -> new OpenApiBuildingError("Endpoint `" + path + "` doesn't support request body.")
				);
			}

			if (Set.of(GET, POST, PUT, PATCH).contains(method)) {
				Assert.isPremiseValid(
					successResponse != null,
					() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing success response.")
				);
			}

			Assert.isPremiseValid(
				handlerBuilder != null,
				() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing handler.")
			);

			return new OpenApiLabApiEndpoint(
				method,
				path,
				operationId,
				description,
				deprecationNotice,
				parameters,
				requestBody,
				successResponse,
				handlerBuilder
			);
		}
	}
}
