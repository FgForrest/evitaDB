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
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiType;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.undertow.util.StatusCodes;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiObject.newObject;

/**
 * Contains utility methods to created particular objects required when composing {@link io.swagger.v3.oas.models.PathItem}
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PathItemsCreator {

	public static final String STATUS_CODE_OK = String.valueOf(StatusCodes.OK);
	public static final String STATUS_CODE_INTERNAL_SERVER_ERROR = String.valueOf(StatusCodes.INTERNAL_SERVER_ERROR);
	public static final String STATUS_CODE_METHOD_NOT_ALLOWED = String.valueOf(StatusCodes.METHOD_NOT_ALLOWED);
	public static final String STATUS_CODE_NOT_FOUND = String.valueOf(StatusCodes.NOT_FOUND);
	public static final String STATUS_CODE_NOT_ACCEPTABLE = String.valueOf(StatusCodes.NOT_ACCEPTABLE);
	public static final String STATUS_CODE_UNSUPPORTED_MEDIA_TYPE = String.valueOf(StatusCodes.UNSUPPORTED_MEDIA_TYPE);
	public static final String STATUS_CODE_BAD_REQUEST = String.valueOf(StatusCodes.BAD_REQUEST);

	public static MediaType createMediaType(@Nonnull OpenApiType type) {
		return new MediaType().schema(type.toSchema());
	}

	public static Content createApplicationJsonContent(@Nonnull MediaType mediaType) {
		return new Content()
			.addMediaType(MimeTypes.APPLICATION_JSON, mediaType);
	}

	/**
	 * Creates OK response for provided schema and adds it into {@link ApiResponses}
	 * @param apiResponses
	 * @param type this schema will be used as response body
	 */
	public static void createAndAddOkResponse(@Nonnull ApiResponses apiResponses, @Nonnull OpenApiType type) {
		apiResponses.addApiResponse(STATUS_CODE_OK, createSchemaResponse(type));
	}

	/**
	 * Creates OK response for provided schema and adds it into {@link ApiResponses}. Response will contain array of schema.
	 *
	 * @param dataType this type will be used as array in response body
	 */
	public static void createSchemaArrayAndAddOkResponse(@Nonnull ApiResponses apiResponses, @Nonnull OpenApiSimpleType dataType) {
		apiResponses.addApiResponse(STATUS_CODE_OK, createSchemaArrayResponse(dataType));
	}

	/**
	 * Creates all possible error responses and adds them into {@link ApiResponses}.
	 * @param errorType this type will be used as response body
	 */
	public static void createAndAddAllErrorResponses(@Nonnull ApiResponses apiResponses, @Nonnull OpenApiSimpleType errorType) {
		final var internalErrorResponse = createSchemaResponse(errorType);
		internalErrorResponse.setDescription("Unexpected internal error.");
		apiResponses.addApiResponse(STATUS_CODE_INTERNAL_SERVER_ERROR, internalErrorResponse);

		final var methodNotAllowedResponse = createSchemaResponse(errorType);
		methodNotAllowedResponse.setDescription("Used method is not allowed at this endpoint.");
		apiResponses.addApiResponse(STATUS_CODE_METHOD_NOT_ALLOWED, methodNotAllowedResponse);

		final var notAcceptableResponse = createSchemaResponse(errorType);
		notAcceptableResponse.setDescription("Cannot produce a response matching the list of acceptable values.");
		apiResponses.addApiResponse(STATUS_CODE_NOT_ACCEPTABLE, notAcceptableResponse);

		final var unsupportedMediaTypeResponse = createSchemaResponse(errorType);
		unsupportedMediaTypeResponse.setDescription("The media format of the requested data is not supported by the server.");
		apiResponses.addApiResponse(STATUS_CODE_UNSUPPORTED_MEDIA_TYPE, unsupportedMediaTypeResponse);

		final var badRequestResponse = createSchemaResponse(errorType);
		badRequestResponse.setDescription("The server cannot or will not process the request due to malformed request syntax.");
		apiResponses.addApiResponse(STATUS_CODE_BAD_REQUEST, badRequestResponse);
	}

	public static ApiResponse createSchemaResponse(@Nonnull OpenApiType type) {
		return new ApiResponse()
			.description("Request was successful.")
			.content(createApplicationJsonContent(createMediaType(type)));
	}

	public static ApiResponse createSchemaArrayResponse(@Nonnull OpenApiSimpleType type) {
		return new ApiResponse()
			.description("Request was successful.")
			.content(createApplicationJsonContent(createMediaType(arrayOf(type))));
	}
}
