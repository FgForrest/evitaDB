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

package io.evitadb.externalApi.rest.api.openApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.SystemRestHandlingContext;
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
 * Single REST endpoint with schema description and handler builder for building system-specific endpoints.
 * It combines {@link PathItem}, {@link Operation} and {@link com.linecorp.armeria.server.HttpService} into one place with useful defaults.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OpenApiSystemEndpoint extends OpenApiEndpoint<SystemRestHandlingContext> {

	public static final String URL_PREFIX = "system";

	/**
	 * Creates builder for new system endpoint.
	 */
	@Nonnull
	public static Builder newSystemEndpoint() {
		return new Builder();
	}

	private OpenApiSystemEndpoint(
		@Nonnull PathItem.HttpMethod method,
		@Nonnull UriPath path,
		@Nonnull String operationId,
		@Nonnull String description,
		@Nullable String deprecationNotice,
		@Nonnull List<OpenApiEndpointParameter> parameters,
		@Nullable OpenApiSimpleType requestBody,
		@Nonnull OpenApiSimpleType successResponse,
		@Nonnull Function<SystemRestHandlingContext, RestEndpointHandler<SystemRestHandlingContext>> handlerBuilder
	) {
		super(method, path, false, operationId, description, deprecationNotice, parameters, requestBody, successResponse, handlerBuilder);
	}

	@Nonnull
	@Override
	public RestEndpointHandler<SystemRestHandlingContext> toHandler(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull OpenAPI openApi,
		@Nonnull Map<String, Class<? extends Enum<?>>> enumMapping
	) {
		final SystemRestHandlingContext context = new SystemRestHandlingContext(
			objectMapper,
			evita,
			headerOptions,
			openApi,
			enumMapping,
			toOperation(),
			this.localized
		);
		return this.handlerBuilder.apply(context);
	}

	public static class Builder {

		@Nonnull private final List<OpenApiEndpointParameter> parameters;
		@Nullable private PathItem.HttpMethod method;
		@Nullable private UriPath path;
		@Nullable private String operationId;
		@Nullable private String description;
		@Nullable private String deprecationNotice;
		@Nullable private OpenApiSimpleType requestBody;
		@Nullable private OpenApiSimpleType successResponse;

		@Nullable private Function<SystemRestHandlingContext, RestEndpointHandler<SystemRestHandlingContext>> handlerBuilder;

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
		 * Set path of this endpoint. Provided builder is already configured with system-specific prefix
		 * `/rest/system`.
		 */
		@Nonnull
		public Builder path(@Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			// prepare new system path
			PathBuilder pathBuilder = newPath();
			pathBuilder = pathBuilderFunction.apply(pathBuilder);

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
		public Builder handler(@Nonnull Function<SystemRestHandlingContext, RestEndpointHandler<SystemRestHandlingContext>> handlerBuilder) {
			this.handlerBuilder = handlerBuilder;
			return this;
		}

		@Nonnull
		public OpenApiSystemEndpoint build() {
			Assert.isPremiseValid(
				this.path != null,
				() -> new OpenApiBuildingError("Missing endpoint path.")
			);
			Assert.isPremiseValid(
				this.method != null,
				() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing method.")
			);
			Assert.isPremiseValid(
				this.operationId != null && !this.operationId.isEmpty(),
				() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing operationId.")
			);
			Assert.isPremiseValid(
				this.description != null && !this.description.isEmpty(),
				() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing description.")
			);

			if (Set.of(POST, PUT, PATCH).contains(this.method)) {
				Assert.isPremiseValid(
					this.requestBody != null,
					() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing request body.")
				);
			} else if (Set.of(GET, HEAD, TRACE, OPTIONS).contains(this.method)) {
				Assert.isPremiseValid(
					this.requestBody == null,
					() -> new OpenApiBuildingError("Endpoint `" + this.path + "` doesn't support request body.")
				);
			}

			if (Set.of(GET, POST, PUT, PATCH).contains(this.method)) {
				Assert.isPremiseValid(
					this.successResponse != null,
					() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing success response.")
				);
			}

			Assert.isPremiseValid(
				this.handlerBuilder != null,
				() -> new OpenApiBuildingError("Endpoint `" + this.path + "` is missing handler.")
			);

			return new OpenApiSystemEndpoint(
				this.method,
				this.path,
				this.operationId,
				this.description,
				this.deprecationNotice,
				this.parameters,
				this.requestBody,
				this.successResponse,
				this.handlerBuilder
			);
		}
	}
}
