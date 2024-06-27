/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.lab.tools.schemaDiff.openApi.OpenApiSchemaDiffer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.evitadb.utils.Assert;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormData.FormValue;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public class OpenApiSchemaDiffHandler extends JsonRestHandler<LabApiHandlingContext> {

	private static final FormParserFactory FORM_PARSER_FACTORY = FormParserFactory.builder().build();

	public OpenApiSchemaDiffHandler(@Nonnull LabApiHandlingContext labApiHandlingContext) {
		super(labApiHandlingContext);
	}

	@Nonnull
	@Override
	protected EndpointResponse doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		// evitaDB schemas may be quite large
		exchange.serverExchange().setMaxEntitySize(20 * 1024 * 1024L); // 20MB

		final String oldSchema;
		final String newSchema;
		try (final FormDataParser parser = FORM_PARSER_FACTORY.createParser(exchange.serverExchange())) {
			FormData data = parser.parseBlocking();
			exchange.serverExchange().setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);

			final FormValue oldSchemaFormValue = data.getFirst("oldSchema");
			Assert.isTrue(
				oldSchemaFormValue.isFileItem(),
				() -> new RestInvalidArgumentException("`oldSchema` form value is not a file item.")
			);
			final FormValue newSchemaFormValue = data.getFirst("newSchema");
			Assert.isTrue(
				newSchemaFormValue.isFileItem(),
				() -> new RestInvalidArgumentException("`newSchema` form value is not a file item.")
			);

			// todo lho should this respect charset of request somehow?
			oldSchema = String.join(
				"\n",
				Files.readAllLines(oldSchemaFormValue.getFileItem().getFile(), StandardCharsets.UTF_8)
			);
			newSchema = String.join(
				"\n",
				Files.readAllLines(newSchemaFormValue.getFileItem().getFile(), StandardCharsets.UTF_8)
			);

		} catch (Throwable e) {
			throw new RestInternalError(
				"Could not read input files: " + e.getMessage(),
				"Could not read input files.",
				e
			);
		}
		return new SuccessEndpointResponse(OpenApiSchemaDiffer.analyze(oldSchema, newSchema));
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.POST_STRING);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return Set.of(MimeTypes.MULTIPART_FORM_DATA);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
