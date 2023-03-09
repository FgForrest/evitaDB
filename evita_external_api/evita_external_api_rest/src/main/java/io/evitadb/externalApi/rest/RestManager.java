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

package io.evitadb.externalApi.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.CatalogContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuilder;
import io.evitadb.externalApi.rest.api.dto.Rest;
import io.evitadb.externalApi.rest.api.dto.Rest.Endpoint;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.io.handler.RestExceptionHandler;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.HttpString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Creates REST endpoints for particular Evita catalogs according generated OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class RestManager {

	/**
	 * Common object mapper for endpoints
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final EvitaSystemDataProvider evitaSystemDataProvider;

	/**
	 * All registered endpoint paths for each catalog
	 */
	private final Map<String, Set<Endpoint>> registeredEndpoints = createConcurrentHashMap(20);
	/**
	 * REST specific endpoint router.
	 */
	@Getter
	private final RoutingHandler restRouter = Handlers.routing();

	public RestManager(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider) {
		this.evitaSystemDataProvider = evitaSystemDataProvider;

		// register initial endpoints
		evitaSystemDataProvider.getCatalogs()
			.forEach(catalog -> {
				try {
					registerCatalog(catalog.getName());
				} catch (EvitaInternalError ex) {
					// log and skip the catalog entirely
					log.error("Catalog `" + catalog.getName() + "` is corrupted and will not accessible by REST API.", ex);
				}
			});
	}

	/**
	 * Register REST endpoints for new catalog.
	 */
	public void registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);
		Assert.isPremiseValid(
			!registeredEndpoints.containsKey(catalogName),
			() -> new OpenApiInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(evitaSystemDataProvider.getEvita(), catalog);
		final Rest builtRest = catalogRestBuilder.build();

		builtRest.endpoints().forEach(endpoint -> registerRestEndpoint(catalogName, endpoint));
	}

	/**
	 * Unregister all REST endpoints of catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final Set<Endpoint> endpointsForCatalog = registeredEndpoints.remove(catalogName);
		if (endpointsForCatalog != null) {
			endpointsForCatalog.forEach(endpoint ->
				restRouter.remove(new HttpString(endpoint.method().toString()), endpoint.path().toString()));
		}
	}

	/**
	 * Update REST endpoints and OpenAPI schema of catalog.
	 */
	public void refreshCatalog(@Nonnull String catalogName) {
		Assert.isPremiseValid(
			registeredEndpoints.containsKey(catalogName),
			() -> new OpenApiInternalError("Cannot refresh catalog `" + catalogName + "`. Such catalog has not been registered yet.")
		);

		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);
		final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(evitaSystemDataProvider.getEvita(), catalog);
		final Rest builtRest = catalogRestBuilder.build();

		unregisterCatalog(catalogName);
		builtRest.endpoints().forEach(endpoint -> registerRestEndpoint(catalogName, endpoint));
	}

	/**
	 * Creates new REST endpoint on specified path with specified {@link OpenAPI} instance.
	 */
	private void registerRestEndpoint(@Nonnull String catalogName, @Nonnull Rest.Endpoint endpoint) {
		final Set<Endpoint> endpointsForCatalog = registeredEndpoints.computeIfAbsent(catalogName, key -> createHashSet(100));
		endpointsForCatalog.add(endpoint);

		restRouter.add(
			endpoint.method().toString(),
			endpoint.path().toString(),
			new BlockingHandler(
				new RestExceptionHandler(
					objectMapper,
					endpoint.handler()
				)
			)
		);
	}
}
