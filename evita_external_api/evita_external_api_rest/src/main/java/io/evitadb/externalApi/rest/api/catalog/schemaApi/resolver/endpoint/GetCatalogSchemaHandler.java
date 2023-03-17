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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint;

import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer.CatalogSchemaJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Handles request for fetching entity schema
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class GetCatalogSchemaHandler extends RestHandler<CatalogRestHandlingContext> {

	@Nonnull
	private final CatalogSchemaJsonSerializer catalogSchemaJsonSerializer;

	public GetCatalogSchemaHandler(@Nonnull CatalogRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		catalogSchemaJsonSerializer = new CatalogSchemaJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	public String getSupportedHttpMethod() {
		return Methods.GET_STRING;
	}

	@Override
	public boolean returnsResponseBodies() {
		return true;
	}

	@Override
	@Nonnull
	public Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		return restApiHandlingContext.queryCatalog(session ->
			Optional.of(session.getCatalogSchema())
				.map(it -> catalogSchemaJsonSerializer.serialize(it, session::getEntitySchemaOrThrow, session.getAllEntityTypes()))
		);
	}
}
