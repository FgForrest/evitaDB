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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.CatalogJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;
import io.evitadb.utils.Assert;
import io.undertow.util.Methods;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Returns all evitaDB catalogs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ListCatalogsHandler extends JsonRestHandler<SystemRestHandlingContext> {

	@Nonnull
	private final CatalogJsonSerializer catalogJsonSerializer;

	public ListCatalogsHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogJsonSerializer = new CatalogJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	protected EndpointResponse doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final Collection<CatalogContract> catalogs = restHandlingContext.getEvita().getCatalogs();
		return new SuccessEndpointResponse(convertResultIntoSerializableObject(exchange, catalogs));
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(Methods.GET_STRING);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExchange exchange, @Nonnull Object catalogs) {
		// Collection<CatalogContract>
		Assert.isPremiseValid(
			catalogs instanceof Collection,
			() -> new RestInternalError("Expected collection of catalogs, but got `" + catalogs.getClass().getName() + "`.")
		);
		//noinspection unchecked
		return catalogJsonSerializer.serialize((Collection<CatalogContract>) catalogs);
	}
}
