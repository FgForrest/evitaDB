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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

/**
 * Deletes single evitaDB catalog by its name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DeleteCatalogHandler extends RestHandler<SystemRestHandlingContext> {

	public DeleteCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Nonnull
	@Override
	public String getSupportedHttpMethod() {
		return Methods.DELETE_STRING;
	}

	@Nonnull
	@Override
	protected Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final Map<String, Object> parameters = getParametersFromRequest(exchange);

		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		final Optional<CatalogContract> catalog = restApiHandlingContext.getCatalog(catalogName, ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION);
		if (catalog.isEmpty()) {
			return Optional.empty();
		}

		final boolean deleted = restApiHandlingContext.getEvita().deleteCatalogIfExists(catalog.get().getName());
		Assert.isPremiseValid(
			deleted,
			() -> new RestInternalError("Could not delete catalog `" + catalog.get().getName() + "`, even though it should exist.")
		);
		// returns 204
		return null;
	}
}
