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
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.CatalogSchemaApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLExceptionHandler;
import io.evitadb.externalApi.graphql.io.GraphQLHandler;
import io.evitadb.externalApi.http.CorsFilter;
import io.evitadb.externalApi.http.CorsPreflightHandler;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
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
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull private final GraphQLConfig graphQLConfig;
	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;

	/**
	 * GraphQL specific endpoint router.
	 */
	@Nonnull private final RoutingHandler graphQLRouter = Handlers.routing();
	/**
	 * Already registered catalogs (corresponds to existing endpoints as well)
	 */
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createHashMap(20);

	public GraphQLManager(@Nonnull GraphQLConfig graphQLConfig, @Nonnull Evita evita) {
		this.graphQLConfig = graphQLConfig;
		this.evita = evita;

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
	 * Registers new Evita catalog to API. It creates new endpoint and {@link GraphQL} instance for it.
	 */
	public void registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof CorruptedCatalog) {
			log.warn("Catalog `" + catalogName + "` is corrupted. Skipping...");
			return;
		}
		Assert.isPremiseValid(
			!registeredCatalogs.containsKey(catalogName),
			() -> new GraphQLInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			final String catalogDataApiPath = buildCatalogDataApiPath(catalog.getSchema());
			final GraphQL catalogDataApiGraphQL = new CatalogGraphQLBuilder(
				evita,
				catalog,
				new CatalogDataApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
			).build(graphQLConfig);
			final RegisteredGraphQLApi registeredDataApiGraphQL = new RegisteredGraphQLApi(
				catalogDataApiPath,
				new AtomicReference<>(catalogDataApiGraphQL)
			);
			registerGraphQLEndpoint(registeredDataApiGraphQL);

			final String catalogSchemaApiPath = buildCatalogSchemaApiPath(catalog.getSchema());
			final GraphQL catalogSchemaApiGraphQL = new CatalogGraphQLBuilder(
				evita,
				catalog,
				new CatalogSchemaApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
			).build(graphQLConfig);
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

		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);

		final GraphQL newCatalogDataApiGraphQL = new CatalogGraphQLBuilder(
			evita,
			catalog,
			new CatalogDataApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
		).build(graphQLConfig);
		registeredCatalog.dataApi().graphQLReference().set(newCatalogDataApiGraphQL);

		final GraphQL newCatalogSchemaApiGraphQL = new CatalogGraphQLBuilder(
			evita,
			catalog,
			new CatalogSchemaApiGraphQLSchemaBuilder(graphQLConfig, evita, catalog).build()
		).build(graphQLConfig);
		registeredCatalog.schemaApi().graphQLReference().set(newCatalogSchemaApiGraphQL);
	}

	/**
	 * Deletes endpoint and its {@link GraphQL} instance for this already registered catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final RegisteredCatalog registeredCatalog = registeredCatalogs.remove(catalogName);
		if (registeredCatalog != null) {
			graphQLRouter.remove(Methods.POST, registeredCatalog.dataApi().path());
			graphQLRouter.remove(Methods.POST, registeredCatalog.schemaApi().path());

			graphQLRouter.remove(Methods.OPTIONS, registeredCatalog.dataApi().path());
			graphQLRouter.remove(Methods.OPTIONS, registeredCatalog.schemaApi().path());
		}
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		registerGraphQLEndpoint(new RegisteredGraphQLApi(
			"/system",
			new AtomicReference<>(new SystemGraphQLBuilder(evita).build(graphQLConfig))
		));
	}

	/**
	 * Creates new GraphQL endpoint on specified path with specified {@link GraphQL} instance.
	 */
	private void registerGraphQLEndpoint(@Nonnull RegisteredGraphQLApi registeredGraphQLApi) {
		// actual GraphQL handler
		graphQLRouter.add(
			Methods.POST,
			registeredGraphQLApi.path(),
			new BlockingHandler(
				new CorsFilter(
					new GraphQLExceptionHandler(
						objectMapper,
						new GraphQLHandler(objectMapper, registeredGraphQLApi.graphQLReference())
					),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
		// CORS pre-flight handler for the GraphQL handler
		graphQLRouter.add(
			Methods.OPTIONS,
			registeredGraphQLApi.path(),
			new BlockingHandler(
				new CorsFilter(
					new CorsPreflightHandler(
						graphQLConfig.getAllowedOrigins(),
						Set.of(Methods.POST_STRING),
						Set.of(Headers.CONTENT_TYPE_STRING, Headers.ACCEPT_STRING)
					),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
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
