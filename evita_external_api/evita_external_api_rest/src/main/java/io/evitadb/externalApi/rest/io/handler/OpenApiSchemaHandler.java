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

package io.evitadb.externalApi.rest.io.handler;

import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.OpenApiWriter;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Returns OpenAPI schema for whole collection.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OpenApiSchemaHandler extends RESTApiHandler {

	public OpenApiSchemaHandler(@Nonnull RESTApiContext restApiContext) {
		super(restApiContext);
	}

	@Override
	protected void validateContext() {}

	@Override
	public void handleRequest(@Nonnull HttpServerExchange exchange) throws Exception {
		setSuccessResponse(exchange, OpenApiWriter.toYaml(restApiContext.getOpenApi().get()));
	}

	@Nonnull
	@Override
	protected String getContentType() {
		return MimeTypes.APPLICATION_YAML;
	}
}
