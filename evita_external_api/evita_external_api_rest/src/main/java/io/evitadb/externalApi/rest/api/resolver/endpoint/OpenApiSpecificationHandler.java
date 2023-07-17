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

package io.evitadb.externalApi.rest.api.resolver.endpoint;

import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.openApi.OpenApiWriter;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.util.Methods;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Returns OpenAPI schema for whole collection.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OpenApiSpecificationHandler<C extends RestHandlingContext> extends RestEndpointHandler<OpenAPI, C> {

	public OpenApiSpecificationHandler(@Nonnull C restHandlingContext) {
		super(restHandlingContext);
	}

	@Nonnull
	@Override
	protected EndpointResponse<OpenAPI> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		return new SuccessEndpointResponse<>(restApiHandlingContext.getOpenApi());
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.GET_STRING);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		final LinkedHashSet<String> mediaTypes = createLinkedHashSet(2);
		mediaTypes.add(MimeTypes.APPLICATION_JSON);
		mediaTypes.add(MimeTypes.APPLICATION_YAML);
		return mediaTypes;
	}

	@Nonnull
	@Override
	protected String serializeResult(@Nonnull RestEndpointExchange exchange, @Nonnull OpenAPI openApiSpecification) {
		final String preferredResponseMediaType = exchange.preferredResponseContentType();
		if (preferredResponseMediaType.equals(MimeTypes.APPLICATION_YAML)) {
			return OpenApiWriter.toYaml(openApiSpecification);
		} else if (preferredResponseMediaType.equals(MimeTypes.APPLICATION_JSON)) {
			return OpenApiWriter.toJson(openApiSpecification);
		} else {
			throw new RestInternalError("Should never happen!");
		}
	}
}
