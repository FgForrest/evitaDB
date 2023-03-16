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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.CatalogJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandler;
import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

/**
 * Returns all evitaDB catalogs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ListCatalogsHandler extends RestHandler<SystemRestHandlingContext> {

	@Nonnull
	private final CatalogJsonSerializer catalogJsonSerializer;

	public ListCatalogsHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogJsonSerializer = new CatalogJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	protected Optional<Object> doHandleRequest(@Nonnull HttpServerExchange exchange) {
		final List<ObjectNode> catalogs = restApiHandlingContext.getEvita().getCatalogs()
			.stream()
			.map(catalogJsonSerializer::serialize)
			.toList();
		return Optional.of(catalogs);
	}
}
