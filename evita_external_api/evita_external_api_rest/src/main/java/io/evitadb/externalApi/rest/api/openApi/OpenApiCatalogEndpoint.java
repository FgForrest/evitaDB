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
import com.linecorp.armeria.server.HttpService;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.EndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.ParameterLocation;
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
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.newPathParameter;
import static io.swagger.v3.oas.models.PathItem.HttpMethod.*;

/**
 * Single REST endpoint with schema description and handler builder for building catalog-specific endpoints
 * (for specific catalog and entity type). It combines {@link io.swagger.v3.oas.models.PathItem},
 * {@link Operation} and {@link HttpService} into one place with useful defaults.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OpenApiCatalogEndpoint extends OpenApiEndpoint<CatalogRestHandlingContext> {

	@Nonnull protected final CatalogSchemaContract catalogSchema;

	/**
	 * Creates builder for new catalog endpoint.
	 */
	@Nonnull
	public static Builder newCatalogEndpoint(@Nonnull CatalogSchemaContract catalogSchema) {
		return new Builder(catalogSchema);
	}

	private OpenApiCatalogEndpoint(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull PathItem.HttpMethod method,
		@Nonnull UriPath path,
		boolean localized,
		@Nonnull String operationId,
		@Nonnull String description,
		@Nullable String deprecationNotice,
		@Nonnull List<OpenApiEndpointParameter> parameters,
		@Nullable OpenApiSimpleType requestBody,
		@Nonnull OpenApiSimpleType successResponse,
		@Nonnull Function<CatalogRestHandlingContext, RestEndpointHandler<CatalogRestHandlingContext>> handlerBuilder
	) {
		super(method, path, localized, operationId, description, deprecationNotice, parameters, requestBody, successResponse, handlerBuilder);
		this.catalogSchema = catalogSchema;
	}

	@Nonnull
	@Override
	public RestEndpointHandler<CatalogRestHandlingContext> toHandler(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headers,
		@Nonnull OpenAPI openApi,
		@Nonnull Map<String, Class<? extends Enum<?>>> enumMapping
	) {
		final CatalogRestHandlingContext context = new CatalogRestHandlingContext(
			objectMapper,
			evita,
			headers,
			this.catalogSchema,
			openApi,
			enumMapping,
			toOperation(),
			this.localized
		);
		return this.handlerBuilder.apply(context);
	}

	public static class Builder {
		@Nonnull private final CatalogSchemaContract catalogSchema;
		@Nonnull private final List<OpenApiEndpointParameter> parameters;
		@Nullable private PathItem.HttpMethod method;
		@Nullable private UriPath path;
		private boolean localized;
		@Nullable private String operationId;
		@Nullable private String description;
		@Nullable private String deprecationNotice;
		@Nullable private OpenApiSimpleType requestBody;
		@Nullable private OpenApiSimpleType successResponse;

		@Nullable private Function<CatalogRestHandlingContext, RestEndpointHandler<CatalogRestHandlingContext>> handlerBuilder;

		private Builder(@Nonnull CatalogSchemaContract catalogSchema) {
			this.catalogSchema = catalogSchema;
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
		 * Set non-localized path of this endpoint. Provided builder is already configured with catalog-specific prefix
		 * `/rest/catalog`.
		 */
		@Nonnull
		public Builder path(@Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			return path(false, pathBuilderFunction);
		}

		/**
		 * Set path of this endpoint. Provided builder is already configured with catalog-specific prefix
		 * `/rest/catalog` or `/rest/catalog/{locale}`.
		 */
		@Nonnull
		public Builder path(boolean localized, @Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			// prepare new catalog path
			PathBuilder pathBuilder = newPath();
			if (localized) {
				pathBuilder.paramItem(newPathParameter()
					.name(EndpointHeaderDescriptor.LOCALIZED.name())
					.description(EndpointHeaderDescriptor.LOCALIZED.description())
					.type(DataTypesConverter.getOpenApiScalar(EndpointHeaderDescriptor.LOCALIZED.primitiveType().javaType()))
					.build());
			}

			pathBuilder = pathBuilderFunction.apply(pathBuilder);

			this.localized = localized;
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
		public Builder handler(@Nonnull Function<CatalogRestHandlingContext, RestEndpointHandler<CatalogRestHandlingContext>> handlerBuilder) {
			this.handlerBuilder = handlerBuilder;
			return this;
		}

		@Nonnull
		public OpenApiCatalogEndpoint build() {
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

			return new OpenApiCatalogEndpoint(
				this.catalogSchema,
				this.method,
				this.path,
				this.localized,
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
