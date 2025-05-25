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
import com.linecorp.armeria.common.HttpStatus;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.ParameterLocation;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Single REST endpoint with schema description and handler builder. It combines {@link io.swagger.v3.oas.models.PathItem},
 * {@link Operation} and {@link com.linecorp.armeria.server.HttpService} into one place with useful defaults. To further simplify
 * building endpoints, the {@link OpenApiCollectionEndpoint} and {@link OpenApiCatalogEndpoint} have been created with
 * built-in specific path builders and custom properties for each type of endpoint.
 *
 * @see OpenApiCollectionEndpoint
 * @see OpenApiCatalogEndpoint
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
@ToString
public abstract class OpenApiEndpoint<HC extends RestHandlingContext> {

	private static final String STATUS_CODE_OK = String.valueOf(HttpStatus.OK);
	private static final String STATUS_CODE_NO_CONTENT = String.valueOf(HttpStatus.NO_CONTENT);
	private static final String STATUS_CODE_INTERNAL_SERVER_ERROR = String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR);
	private static final String STATUS_CODE_METHOD_NOT_ALLOWED = String.valueOf(HttpStatus.METHOD_NOT_ALLOWED);
	private static final String STATUS_CODE_NOT_FOUND = String.valueOf(HttpStatus.NOT_FOUND);
	private static final String STATUS_CODE_NOT_ACCEPTABLE = String.valueOf(HttpStatus.NOT_ACCEPTABLE);
	private static final String STATUS_CODE_UNSUPPORTED_MEDIA_TYPE = String.valueOf(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	private static final String STATUS_CODE_BAD_REQUEST = String.valueOf(HttpStatus.BAD_REQUEST);

	@Nonnull @Getter protected final HttpMethod method;
	@Nonnull @Getter protected final UriPath path;
	/**
	 * Whether the endpoint contains locale parameter in path, and thus defines default locale for entire endpoint.
	 */
	protected final boolean localized;

	/**
	 * ID/name of operation this endpoint represents. Usually used by client generators for naming methods.
	 */
	@Nonnull protected final String operationId;
	@Nonnull protected final String description;
	@Nullable protected final String deprecationNotice;
	/**
	 * List of all parameters, both path and query parameters.
	 */
	@Nonnull protected final List<OpenApiEndpointParameter> parameters;

	@Nullable protected final OpenApiSimpleType requestBody;
	@Nullable protected final OpenApiSimpleType successResponse;

	@Nonnull protected final Function<HC, RestEndpointHandler<HC>> handlerBuilder;

	/**
	 * Instantiate a new handler for this particular endpoint with passed data.
	 *
	 * @param objectMapper for parsing request bodies and serializing responses
	 * @param evita to query and update data
	 * @param openApi final OpenAPI specs for parsing and validation
	 * @param enumMapping custom enum mapping, because enums are not fixedly defined like scalars
	 * @return ready-to-handle endpoint handler
	 */
	@Nonnull
	public abstract RestEndpointHandler<HC> toHandler(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headers,
		@Nonnull OpenAPI openApi,
		@Nonnull Map<String, Class<? extends Enum<?>>> enumMapping
	);

	/**
	 * Build {@link Operation} describing this endpoint in OpenAPI.
	 */
	@Nonnull
	public Operation toOperation() {
		final Operation operation = new Operation();
		operation.operationId(this.operationId);
		operation.description(this.description);
		if (this.deprecationNotice != null) {
			operation.deprecated(true);
		}

		operation.parameters(this.parameters.stream().map(OpenApiEndpointParameter::toParameter).toList());

		if (this.requestBody != null) {
			operation.requestBody(createRequestBody(this.requestBody));
		}

		final ApiResponses responses = new ApiResponses();
		if (this.successResponse != null) {
			responses.addApiResponse(
				STATUS_CODE_OK,
				createResponse(
					"Request was successful.",
					this.successResponse
				)
			);
		} else {
			responses.addApiResponse(
				STATUS_CODE_NO_CONTENT,
				createResponse(
					"Request was successful.",
					null
				)
			);
		}
		if (!(this.successResponse instanceof OpenApiNonNull)) {
			responses.addApiResponse(
				STATUS_CODE_NOT_FOUND,
				createErrorResponse("Resource not found.")
			);
		}
		responses.addApiResponse(
			STATUS_CODE_INTERNAL_SERVER_ERROR,
			createErrorResponse("Unexpected internal error.")
		);
		responses.addApiResponse(
			STATUS_CODE_METHOD_NOT_ALLOWED,
			createErrorResponse("Used method is not allowed at this endpoint.")
		);
		responses.addApiResponse(
			STATUS_CODE_NOT_ACCEPTABLE,
			createErrorResponse("Cannot produce a response matching the list of acceptable values.")
		);
		responses.addApiResponse(
			STATUS_CODE_UNSUPPORTED_MEDIA_TYPE,
			createErrorResponse("The media format of the requested data is not supported by the server.")
		);
		responses.addApiResponse(
			STATUS_CODE_BAD_REQUEST,
			createErrorResponse("The server cannot or will not process the request due to malformed request syntax.")
		);
		operation.responses(responses);

		return operation;
	}

	@Nonnull
	protected RequestBody createRequestBody(@Nonnull OpenApiSimpleType type) {
		return new RequestBody()
			.content(
				new Content()
					.addMediaType(
						MimeTypes.APPLICATION_JSON,
						new MediaType()
							.schema(type.toSchema())
					)
			);
	}

	@Nonnull
	protected ApiResponse createResponse(@Nonnull String description, @Nullable OpenApiSimpleType type) {
		final ApiResponse response = new ApiResponse()
			.description(description);
		if (type != null) {
			response
				.content(
					new Content()
						.addMediaType(
							MimeTypes.APPLICATION_JSON,
							new MediaType()
								.schema(type.toSchema())
						)
				);
		}

		return response;
	}

	@Nonnull
	protected ApiResponse createErrorResponse(@Nonnull String description) {
		return createResponse(description, typeRefTo(ErrorDescriptor.THIS.name()));
	}

	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class PathBuilder {

		@Nonnull
		private final UriPath.Builder pathBuilder = UriPath.builder();
		@Getter
		private final List<OpenApiEndpointParameter> pathParameters = new LinkedList<>();

		@Nonnull
		public UriPath getPath() {
			return this.pathBuilder.build();
		}

		@Nonnull
		public static PathBuilder newPath() {
			return new PathBuilder();
		}

		@Nonnull
		public PathBuilder staticItem(@Nullable String staticItem) {
			if (staticItem != null && !staticItem.isEmpty()) {
				this.pathBuilder.part(staticItem);
			}
			return this;
		}

		@Nonnull
		public PathBuilder paramItem(@Nullable OpenApiEndpointParameter paramItem) {
			if (paramItem != null) {
				Assert.isPremiseValid(
					paramItem.getLocation().equals(ParameterLocation.PATH),
					() -> new OpenApiBuildingError("Path only supports path parameters.")
				);
				this.pathBuilder.part("{" + paramItem.getName() + "}");
				this.pathParameters.add(paramItem);
			}
			return this;
		}

		@Nonnull
		public PathBuilder paramItem(@Nullable OpenApiEndpointParameter.Builder paramItemBuilder) {
			if (paramItemBuilder != null) {
				return paramItem(paramItemBuilder.build());
			}
			return this;
		}
	}
}
