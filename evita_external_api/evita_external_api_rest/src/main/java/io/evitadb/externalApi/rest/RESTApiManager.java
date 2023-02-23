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
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogOpenApiBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.UrlPathCreator;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.io.handler.RESTApiExceptionHandler;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.Handlers;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * Creates REST endpoints for particular Evita catalogs according generated OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class RESTApiManager {

	/**
	 * Common object mapper for endpoints
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final EvitaSystemDataProvider evitaSystemDataProvider;

	/**
	 * REST specific endpoint router.
	 */
	@Getter
	private final PathHandler restRouter = Handlers.path();

	@Getter private final Map<String, String> registeredCatalogBasePath = createConcurrentHashMap(20);

	public RESTApiManager(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider) {
		this.evitaSystemDataProvider = evitaSystemDataProvider;

		// register initial endpoints
		evitaSystemDataProvider.getCatalogs()
			.forEach(catalog -> {
				try {
					registerNewCatalog(catalog.getName());
				} catch (EvitaInternalError ex) {
					// log and skip the catalog entirely
					log.error("Catalog `" + catalog.getName() + "` is corrupted and will not accessible by REST API.");
				}
			});
	}

	/**
	 * Register REST endpoints for new catalog.
	 *
	 * @param catalogName
	 */
	public void registerNewCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);
		Assert.isPremiseValid(
			!registeredCatalogBasePath.containsKey(catalogName),
			() -> new OpenApiInternalError("Catalog `" + catalogName + "` has been already registered.")
		);
		registerCatalog(catalog);
		log.debug("Registering catalog: " + catalogName + " into REST handler.");
	}

	/**
	 * Unregister all REST endpoints of catalog.
	 *
	 * @param catalogName
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final String basePath = registeredCatalogBasePath.get(catalogName);
		if(basePath != null) {
			restRouter.removePrefixPath(basePath);
			registeredCatalogBasePath.remove(catalogName);
			log.debug("Unregistering catalog: " + catalogName + " from REST handler.");
		} else {
			log.warn("Can't unregister catalog: " + catalogName + " because it wasn't found in a list of registered catalogs.");
		}
	}

	/**
	 * Update REST endpoints and OpenAPI schema of catalog.
	 *
	 * @param catalogName
	 */
	public void updateExistingCatalog(@Nonnull String catalogName) {
		if(!registeredCatalogBasePath.containsKey(catalogName)) {
			log.warn("Can't update catalog: " + catalogName + " because it wasn't found in a list of registered catalogs. Adding as new one.");
		} else {
			log.debug("Updating existing catalog: " + catalogName);
		}
		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);
		registerCatalog(catalog);
	}

	private void registerCatalog(@Nonnull CatalogContract catalog) {
		final CatalogOpenApiBuilder catalogOpenApiBuilder = new CatalogOpenApiBuilder(evitaSystemDataProvider.getEvita(), catalog);
		/*final OpenAPI openAPI = */catalogOpenApiBuilder.build();

		// todo mve when removing this remove also IO utils from deps
		/*try {
			Files.writeString(
				Path.of("/www/edee/evita/docs/api/" + catalog.getName() + ".yaml"),
				OpenApiWriter.toYaml(openAPI)
			);

		} catch (Exception e) {
			e.printStackTrace();
		}*/

		final String basePath = UrlPathCreator.getCatalogUrlPathName(catalog);
		registeredCatalogBasePath.put(catalog.getName(), basePath);

		registerRestEndpoint(basePath, catalogOpenApiBuilder.getRoutingHandler());
	}

	/**
	 * Creates new REST endpoint on specified path with specified {@link OpenAPI} instance.
	 */
	private void registerRestEndpoint(@Nonnull String basePath, @Nonnull RoutingHandler routingHandler) {
		restRouter.addPrefixPath(
			basePath,
			new BlockingHandler(
				new RESTApiExceptionHandler(
					objectMapper,
					routingHandler
				)
			)
		);
	}
}
