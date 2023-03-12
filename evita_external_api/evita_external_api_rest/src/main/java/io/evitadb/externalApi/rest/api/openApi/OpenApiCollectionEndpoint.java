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

package io.evitadb.externalApi.rest.api.openApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.ParameterLocation;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint.PathBuilder.newPath;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.newPathParameter;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.swagger.v3.oas.models.PathItem.HttpMethod.*;

/**
 * Single REST endpoint with schema description and handler builder for building collection-specific endpoints
 * (for specific catalog and entity type). It combines {@link io.swagger.v3.oas.models.PathItem},
 * {@link Operation} and {@link io.undertow.server.HttpHandler} into one place with useful defaults.
 *
 * @see OpenApiCatalogEndpoint
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OpenApiCollectionEndpoint extends OpenApiEndpoint<CollectionRestHandlingContext> {

	@Nonnull private final EntitySchemaContract entitySchema;

	private OpenApiCollectionEndpoint(@Nonnull CatalogSchemaContract catalogSchema,
									  @Nonnull EntitySchemaContract entitySchema,
	                                  @Nonnull PathItem.HttpMethod method,
	                                  @Nonnull Path path,
									  boolean localized,
	                                  @Nonnull String description,
	                                  @Nullable String deprecationNotice,
	                                  @Nonnull List<OpenApiEndpointParameter> parameters,
	                                  @Nullable OpenApiSimpleType requestBody,
	                                  @Nonnull OpenApiSimpleType successResponse,
	                                  @Nonnull Function<CollectionRestHandlingContext, RestHandler<CollectionRestHandlingContext>> handlerBuilder) {
		super(catalogSchema, method, path, localized, description, deprecationNotice, parameters, requestBody, successResponse, handlerBuilder);
		this.entitySchema = entitySchema;
	}

	/**
	 * Creates builder for new collection endpoint.
	 */
	@Nonnull
	public static Builder newCollectionEndpoint(@Nonnull CatalogSchemaContract catalogSchema,
	                                            @Nonnull EntitySchemaContract entitySchema) {
		return new Builder(catalogSchema, entitySchema);
	}

	@Nonnull
	@Override
	public RestHandler<CollectionRestHandlingContext> toHandler(@Nonnull ObjectMapper objectMapper,
	                                                            @Nonnull Evita evita,
	                                                            @Nonnull OpenAPI openApi) {
		final CollectionRestHandlingContext context = new CollectionRestHandlingContext(
			objectMapper,
			evita,
			catalogSchema,
			entitySchema,
			openApi,
			toOperation(),
			localized
		);
		return handlerBuilder.apply(context);
	}


	public static class Builder {

		@Nonnull private final CatalogSchemaContract catalogSchema;
		@Nonnull private final EntitySchemaContract entitySchema;

		@Nullable private PathItem.HttpMethod method;
		@Nullable private Path path;
		private boolean localized;

		@Nullable private String description;
		@Nullable private String deprecationNotice;
		@Nonnull private final List<OpenApiEndpointParameter> parameters;

		@Nullable private OpenApiSimpleType requestBody;
		@Nullable private OpenApiSimpleType successResponse;

		@Nullable private Function<CollectionRestHandlingContext, RestHandler<CollectionRestHandlingContext>> handlerBuilder;

		private Builder(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
			this.catalogSchema = catalogSchema;
			this.entitySchema = entitySchema;
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
		 * Set non-localized path of this endpoint. Provided builder is already configured with collection-specific prefix
		 * `/rest/catalog/entityType`.
		 */
		@Nonnull
		public Builder path(@Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			return path(false, pathBuilderFunction);
		}

		/**
		 * Set path of this endpoint. Provided builder is already configured with collection-specific prefix
		 * `/rest/catalog/entityType` or `/rest/catalog/{locale}/entityType`.
		 */
		@Nonnull
		public Builder path(boolean localized, @Nonnull UnaryOperator<PathBuilder> pathBuilderFunction) {
			// prepare new catalog path
			PathBuilder pathBuilder = newPath()
				.staticItem(catalogSchema.getNameVariant(URL_NAME_NAMING_CONVENTION));
			if (localized) {
				pathBuilder.paramItem(newPathParameter()
					.name(ParamDescriptor.REQUIRED_LOCALE.name())
					.description(ParamDescriptor.REQUIRED_LOCALE.description())
					.type(typeRefTo(ENTITY_LOCALE_ENUM.name(entitySchema)))
					.build());
			}
			pathBuilder.staticItem(entitySchema.getNameVariant(URL_NAME_NAMING_CONVENTION));

			pathBuilder = pathBuilderFunction.apply(pathBuilder);

			this.localized = localized;
			this.path = pathBuilder.getPath();
			this.parameters.addAll(pathBuilder.getPathParameters());
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
		public Builder handler(@Nonnull Function<CollectionRestHandlingContext, RestHandler<CollectionRestHandlingContext>> handlerBuilder) {
			this.handlerBuilder = handlerBuilder;
			return this;
		}

		@Nonnull
		public OpenApiCollectionEndpoint build() {
			Assert.isPremiseValid(
				path != null,
				() -> new OpenApiBuildingError("Missing endpoint path.")
			);
			Assert.isPremiseValid(
				method != null,
				() -> new OpenApiBuildingError("Endpoint `" + path + "` is missing method.")
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

			return new OpenApiCollectionEndpoint(
				catalogSchema,
				entitySchema,
				method,
				path,
				localized,
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
