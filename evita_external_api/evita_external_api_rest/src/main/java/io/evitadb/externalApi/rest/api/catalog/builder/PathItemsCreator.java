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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.undertow.util.StatusCodes;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;

/**
 * Contains utility methods to created particular objects required when composing {@link io.swagger.v3.oas.models.PathItem}
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PathItemsCreator {
	public static final String FILTER_BY = "filterBy";
	public static final String ORDER_BY = "orderBy";
	public static final String REQUIRE = "require";

	public static final String STATUS_CODE_OK = String.valueOf(StatusCodes.OK);
	public static final String STATUS_CODE_INTERNAL_SERVER_ERROR = String.valueOf(StatusCodes.INTERNAL_SERVER_ERROR);
	public static final String STATUS_CODE_METHOD_NOT_ALLOWED = String.valueOf(StatusCodes.METHOD_NOT_ALLOWED);
	public static final String STATUS_CODE_NOT_FOUND = String.valueOf(StatusCodes.NOT_FOUND);
	public static final String STATUS_CODE_NOT_ACCEPTABLE = String.valueOf(StatusCodes.NOT_ACCEPTABLE);
	public static final String STATUS_CODE_UNSUPPORTED_MEDIA_TYPE = String.valueOf(StatusCodes.UNSUPPORTED_MEDIA_TYPE);
	public static final String STATUS_CODE_BAD_REQUEST = String.valueOf(StatusCodes.BAD_REQUEST);

	public static MediaType createMediaType(@Nonnull Schema<Object> schema) {
		return new MediaType().schema(schema);
	}

	public static Content createApplicationJsonContent(@Nonnull MediaType mediaType) {
		return new Content()
			.addMediaType(MimeTypes.APPLICATION_JSON, mediaType);
	}

	/**
	 * Creates OK response for provided schema and adds it into {@link ApiResponses}
	 * @param apiResponses
	 * @param dataSchema this schema will be used as response body
	 */
	public static void createAndAddOkResponse(@Nonnull ApiResponses apiResponses, @Nonnull Schema<Object> dataSchema) {
		apiResponses.addApiResponse(STATUS_CODE_OK, createSchemaResponse(dataSchema));
	}

	/**
	 * Creates OK response for provided schema and adds it into {@link ApiResponses}. Response will contain array of schema.
	 *
	 * @param apiResponses
	 * @param dataSchema this schema will be used as array in response body
	 */
	public static void createSchemaArrayAndAddOkResponse(@Nonnull ApiResponses apiResponses, @Nonnull Schema<Object> dataSchema) {
		apiResponses.addApiResponse(STATUS_CODE_OK, createSchemaArrayResponse(dataSchema));
	}

	/**
	 * Creates all possible error responses and adds them into {@link ApiResponses}.
	 * @param apiResponses
	 * @param errorSchema this schema will be used as response body
	 */
	public static void createAndAddAllErrorResponses(@Nonnull ApiResponses apiResponses, @Nonnull Schema<Object> errorSchema) {
		final var internalErrorResponse = createSchemaResponse(errorSchema);
		internalErrorResponse.setDescription("Unexpected internal error.");
		apiResponses.addApiResponse(STATUS_CODE_INTERNAL_SERVER_ERROR, internalErrorResponse);

		final var methodNotAllowedResponse = createSchemaResponse(errorSchema);
		methodNotAllowedResponse.setDescription("Used method is not allowed at this endpoint.");
		apiResponses.addApiResponse(STATUS_CODE_METHOD_NOT_ALLOWED, methodNotAllowedResponse);

		final var notAcceptableResponse = createSchemaResponse(errorSchema);
		notAcceptableResponse.setDescription("Cannot produce a response matching the list of acceptable values.");
		apiResponses.addApiResponse(STATUS_CODE_NOT_ACCEPTABLE, notAcceptableResponse);

		final var unsupportedMediaTypeResponse = createSchemaResponse(errorSchema);
		unsupportedMediaTypeResponse.setDescription("The media format of the requested data is not supported by the server.");
		apiResponses.addApiResponse(STATUS_CODE_UNSUPPORTED_MEDIA_TYPE, unsupportedMediaTypeResponse);

		final var badRequestResponse = createSchemaResponse(errorSchema);
		badRequestResponse.setDescription("The server cannot or will not process the request due to malformed request syntax.");
		apiResponses.addApiResponse(STATUS_CODE_BAD_REQUEST, badRequestResponse);
	}

	public static ApiResponse createSchemaResponse(@Nonnull Schema<Object> schema) {
		return new ApiResponse()
			.description(schema.getDescription())
			.content(createApplicationJsonContent(createMediaType(schema)));
	}

	public static ApiResponse createSchemaArrayResponse(@Nonnull Schema<Object> schema) {
		return new ApiResponse()
			.description(schema.getDescription())
			.content(createApplicationJsonContent(createMediaType(createArraySchemaOf(schema))));
	}

	public static Parameter createPathParameter(@Nonnull Schema<Object> schema, boolean required) {
		return new PathParameter()
			.name(schema.getName())
			.required(required)
			.description(schema.getDescription())
			.schema(schema);
	}

	public static Parameter createPathParameter(@Nonnull Property property) {
		return createPathParameter(property.schema(), property.required());
	}

	public static Parameter createQueryParameter(@Nonnull Schema<Object> schema, boolean required) {
		return new QueryParameter()
			.name(schema.getName())
			.required(required)
			.description(schema.getDescription())
			.schema(schema);
	}

	public static Parameter createQueryParameter(@Nonnull Schema<Object> schema) {
		return createQueryParameter(schema, false);
	}

	public static Parameter createQueryParameter(@Nonnull Property property) {
		return createQueryParameter(property.schema(), property.required());
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	@Nonnull
	public static Schema<Object> createRequestListSchema(@Nonnull Schema<Object> filterContainer, @Nonnull Schema<Object> orderContainer, @Nullable Schema<Object> requireContainer) {
		final Schema schema = createObjectSchema()
			.addProperty(FILTER_BY, filterContainer)
			.addProperty(ORDER_BY, orderContainer);

		if(requireContainer != null) {
			schema.addProperty(REQUIRE, requireContainer);
		}
		return schema;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	public static Schema<Object> createRequestQuerySchema(@Nonnull Schema<Object> filterContainer, @Nonnull Schema<Object> orderContainer, @Nullable Schema<Object> requireContainer) {
		final Schema schema = createObjectSchema()
			.addProperty(FILTER_BY, filterContainer)
			.addProperty(ORDER_BY, orderContainer);

		if(requireContainer != null) {
			schema.addProperty(REQUIRE, requireContainer);
		}

		return schema;
	}
}
