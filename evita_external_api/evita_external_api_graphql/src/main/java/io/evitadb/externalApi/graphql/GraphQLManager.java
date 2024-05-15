/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.CatalogSchemaApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLRouter;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import io.undertow.server.HttpHandler;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Manages the whole GraphQL API (its endpoints, lifecycle, etc).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GraphQLManager {

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();
	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;

	@Nonnull private final GraphQLConfig graphQLConfig;

	/**
	 * GraphQL specific endpoint router.
	 */
	@Nonnull private final GraphQLRouter graphQLRouter;
	/**
	 * Already registered catalogs (corresponds to existing endpoints as well)
	 */
	@Nonnull private final Set<String> registeredCatalogs = createHashSet(20);

	public GraphQLManager(@Nonnull Evita evita, @Nonnull GraphQLConfig graphQLConfig) {
		this.evita = evita;
		this.graphQLConfig = graphQLConfig;
		this.graphQLRouter = new GraphQLRouter(objectMapper, evita, graphQLConfig);

		final long buildingStartTime = System.currentTimeMillis();

		// register initial endpoints
		registerSystemApi();
		this.evita.getCatalogs().forEach(catalog -> registerCatalog(catalog.getName()));

		log.info("Built GraphQL API in " + StringUtils.formatPreciseNano(System.currentTimeMillis() - buildingStartTime));
	}

	@Nonnull
	public HttpHandler getGraphQLRouter() {
		return new PathNormalizingHandler(graphQLRouter);
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		graphQLRouter.registerSystemApi(new SystemGraphQLBuilder(evita).build(graphQLConfig));
	}

	/**
	 * Registers new Evita catalog to API. It creates new endpoint and {@link GraphQL} instance for it.
	 */
	public void registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof CorruptedCatalog) {
			log.warn("Catalog `" + catalogName + "` is corrupted. Skipping...");
			return;
		}
		Assert.isPremiseValid(
			!registeredCatalogs.contains(catalogName),
			() -> new GraphQLInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			final GraphQL dataApi = new CatalogGraphQLBuilder(
				evita,
				catalog,
				new CatalogDataApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
			).build(graphQLConfig);

			final GraphQL schemaApi = new CatalogGraphQLBuilder(
				evita,
				catalog,
				new CatalogSchemaApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
			).build(graphQLConfig);

			registeredCatalogs.add(catalogName);
			graphQLRouter.registerCatalogApis(catalogName, dataApi, schemaApi);
		} catch (EvitaInternalError ex) {
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by GraphQL API.", ex);
		}
	}

	/**
	 * Refreshes already registered catalog endpoint and its {@link GraphQL} instance.
	 */
	public void refreshCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = registeredCatalogs.contains(catalogName);
		if (!catalogRegistered) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			registerCatalog(catalogName);
			return;
		}

		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);

		final GraphQL newDataApi = new CatalogGraphQLBuilder(
			evita,
			catalog,
			new CatalogDataApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
		).build(graphQLConfig);

		final GraphQL newSchemaApi = new CatalogGraphQLBuilder(
			evita,
			catalog,
			new CatalogSchemaApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
		).build(graphQLConfig);

		graphQLRouter.refreshCatalogApis(catalogName, newDataApi, newSchemaApi);
	}

	/**
	 * Deletes endpoint and its {@link GraphQL} instance for this already registered catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = registeredCatalogs.remove(catalogName);
		if (catalogRegistered) {
			graphQLRouter.unregisterCatalogApis(catalogName);
		}
	}
}
