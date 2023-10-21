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
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.evitadb.utils.Assert;
import io.undertow.util.Methods;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deletes single evitaDB catalog by its name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DeleteCatalogHandler extends JsonRestHandler<Void, SystemRestHandlingContext>  {

	public DeleteCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Nonnull
	@Override
	protected EndpointResponse<Void> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Map<String, Object> parameters = getParametersFromRequest(exchange);

		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		final Optional<CatalogContract> catalog = restApiHandlingContext.getEvita().getCatalogInstance(catalogName);
		if (catalog.isEmpty()) {
			return new NotFoundEndpointResponse<>();
		}

		final boolean deleted = restApiHandlingContext.getEvita().deleteCatalogIfExists(catalog.get().getName());
		Assert.isPremiseValid(
			deleted,
			() -> new RestInternalError("Could not delete catalog `" + catalog.get().getName() + "`, even though it should exist.")
		);
		return new SuccessEndpointResponse<>();
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.DELETE_STRING);
	}
}
