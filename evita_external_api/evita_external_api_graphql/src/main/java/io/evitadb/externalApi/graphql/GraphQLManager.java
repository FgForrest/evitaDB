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

package io.evitadb.externalApi.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.CatalogSchemaApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLExceptionHandler;
import io.evitadb.externalApi.graphql.io.GraphQLHandler;
import io.evitadb.utils.Assert;
import io.undertow.Handlers;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

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
	private final ObjectMapper objectMapper = new ObjectMapper();
	/**
	 * Provides access to Evita private API
	 */
	private final EvitaSystemDataProvider evitaSystemDataProvider;
	/**
	 * GraphQL specific endpoint router.
	 */
	@Getter
	private final PathHandler graphQLRouter = Handlers.path();
	/**
	 * Already registered catalogs (corresponds to existing endpoints as well)
	 */
	private final Map<String, RegisteredCatalog> registeredCatalogs = createHashMap(20);

	public GraphQLManager(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider) {
		this.evitaSystemDataProvider = evitaSystemDataProvider;

		// register initial endpoints
		registerSystemApi();
		this.evitaSystemDataProvider.getCatalogs().forEach(catalog -> registerCatalog(catalog.getName()));
	}

	/**
	 * Registers new Evita catalog to API. It creates new endpoint and {@link GraphQL} instance for it.
	 */
	public void registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);
		Assert.isPremiseValid(
			!registeredCatalogs.containsKey(catalogName),
			() -> new GraphQLInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			final String catalogDataApiPath = buildCatalogDataApiPath(catalog.getSchema());
			final GraphQL catalogDataApiGraphQL = new CatalogGraphQLBuilder(
				evitaSystemDataProvider.getEvita(),
				catalog,
				new CatalogDataApiGraphQLSchemaBuilder(evitaSystemDataProvider.getEvita(), catalog).build()
			).build();
			final RegisteredGraphQLApi registeredDataApiGraphQL = new RegisteredGraphQLApi(
				catalogDataApiPath,
				new AtomicReference<>(catalogDataApiGraphQL)
			);
			registerGraphQLEndpoint(registeredDataApiGraphQL);

			final String catalogSchemaApiPath = buildCatalogSchemaApiPath(catalog.getSchema());
			final GraphQL catalogSchemaApiGraphQL = new CatalogGraphQLBuilder(
				evitaSystemDataProvider.getEvita(),
				catalog,
				new CatalogSchemaApiGraphQLSchemaBuilder(evitaSystemDataProvider.getEvita(), catalog).build()
			).build();
			final RegisteredGraphQLApi registeredSchemaApiGraphQL = new RegisteredGraphQLApi(
				catalogSchemaApiPath,
				new AtomicReference<>(catalogSchemaApiGraphQL)
			);
			registerGraphQLEndpoint(registeredSchemaApiGraphQL);

			final RegisteredCatalog registeredCatalog = new RegisteredCatalog(registeredDataApiGraphQL, registeredSchemaApiGraphQL);
			registeredCatalogs.put(catalogName, registeredCatalog);
		} catch (EvitaInternalError ex) {
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by GraphQL API.", ex);
		}
	}

	/**
	 * Refreshes already registered catalog endpoint and its {@link GraphQL} instance.
	 */
	public void refreshCatalog(@Nonnull String catalogName) {
		final RegisteredCatalog registeredCatalog = registeredCatalogs.get(catalogName);
		Assert.isPremiseValid(
			registeredCatalog != null,
			() -> new GraphQLInternalError("Cannot refresh catalog `" + catalogName + "`. Such catalog has not been registered yet.")
		);

		final CatalogContract catalog = evitaSystemDataProvider.getCatalog(catalogName);

		final GraphQL newCatalogDataApiGraphQL = new CatalogGraphQLBuilder(
			evitaSystemDataProvider.getEvita(),
			catalog,
			new CatalogDataApiGraphQLSchemaBuilder(evitaSystemDataProvider.getEvita(), catalog).build()
		).build();
		registeredCatalog.dataApi().graphQLReference().set(newCatalogDataApiGraphQL);

		final GraphQL newCatalogSchemaApiGraphQL = new CatalogGraphQLBuilder(
			evitaSystemDataProvider.getEvita(),
			catalog,
			new CatalogSchemaApiGraphQLSchemaBuilder(evitaSystemDataProvider.getEvita(), catalog).build()
		).build();
		registeredCatalog.schemaApi().graphQLReference().set(newCatalogSchemaApiGraphQL);
	}

	/**
	 * Deletes endpoint and its {@link GraphQL} instance for this already registered catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final RegisteredCatalog registeredCatalog = registeredCatalogs.remove(catalogName);
		if (registeredCatalog != null) {
			graphQLRouter.removeExactPath(registeredCatalog.dataApi().path());
			graphQLRouter.removeExactPath(registeredCatalog.schemaApi().path());
		}
	}

	/**
	 * Creates new GraphQL endpoint on specified path with specified {@link GraphQL} instance.
	 */
	private void registerGraphQLEndpoint(@Nonnull RegisteredGraphQLApi registeredGraphQLApi) {
		graphQLRouter.addExactPath(
			registeredGraphQLApi.path(),
			new BlockingHandler(
				new GraphQLExceptionHandler(
					objectMapper,
					new GraphQLHandler(objectMapper, registeredGraphQLApi.graphQLReference())
				)
			)
		);
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		registerGraphQLEndpoint(new RegisteredGraphQLApi(
			"/system",
			new AtomicReference<>(new SystemGraphQLBuilder(evitaSystemDataProvider.getEvita()).build())
		));
	}

	/**
	 * Unified way of building catalog's URL path from its name.
	 */
	@Nonnull
	private String buildCatalogDataApiPath(@Nonnull CatalogSchemaContract catalogSchema) {
		return Path.of(catalogSchema.getNameVariants().get(URL_NAME_NAMING_CONVENTION)).toString();
	}

	/**
	 * Unified way of building catalog's URL path from its name.
	 */
	@Nonnull
	private String buildCatalogSchemaApiPath(@Nonnull CatalogSchemaContract catalogSchema) {
		return Path.of(catalogSchema.getNameVariants().get(URL_NAME_NAMING_CONVENTION), "schema").toString();
	}

	private record RegisteredCatalog(@Nonnull RegisteredGraphQLApi dataApi,
	                                 @Nonnull RegisteredGraphQLApi schemaApi) {}

	private record RegisteredGraphQLApi(@Nonnull String path,
	                                    @Nonnull AtomicReference<GraphQL> graphQLReference) {}
}
