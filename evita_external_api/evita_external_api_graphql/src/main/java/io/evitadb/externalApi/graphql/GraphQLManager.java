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
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLRefreshingObserver;
import io.evitadb.externalApi.graphql.api.catalog.SystemGraphQLRefreshingObserver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.CatalogSchemaApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLExceptionHandler;
import io.evitadb.externalApi.graphql.io.GraphQLWebHandler;
import io.evitadb.externalApi.graphql.io.GraphQLWebSocketHandler;
import io.evitadb.externalApi.http.CorsFilter;
import io.evitadb.externalApi.http.CorsPreflightHandler;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.utils.UriPath;
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
	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;

	@Nonnull private final GraphQLConfig graphQLConfig;

	/**
	 * GraphQL specific endpoint router.
	 */
	@Nonnull private final RoutingHandler graphQLRouter = Handlers.routing();
	/**
	 * Already registered catalogs (corresponds to existing endpoints as well)
	 */
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createHashMap(20);

	public GraphQLManager(@Nonnull Evita evita, @Nonnull GraphQLConfig graphQLConfig) {
		this.evita = evita;
		this.graphQLConfig = graphQLConfig;

		final long buildingStartTime = System.currentTimeMillis();

		// listen to any evita catalog changes
		evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(CaptureContent.HEADER))
			.subscribe(new SystemGraphQLRefreshingObserver(this));

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
		// todo jno: uncomment after the catalog captures are implemented
//		catalog.registerChangeCatalogCapture(
//			new ChangeCatalogCaptureRequest(
//				CaptureArea.SCHEMA, new SchemaSite(Operation.values()), CaptureContent.HEADER,
//				catalog.getLastCommittedTransactionId()
//			)
//		).subscribe(new CatalogGraphQLRefreshingObserver(this));

		try {
			final UriPath catalogDataApiPath = buildCatalogDataApiPath(catalog.getSchema());
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

			final UriPath catalogSchemaApiPath = buildCatalogSchemaApiPath(catalog.getSchema());
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
			graphQLRouter.remove(Methods.POST, registeredCatalog.dataApi().path().toString());
			graphQLRouter.remove(Methods.POST, registeredCatalog.schemaApi().path().toString());

			graphQLRouter.remove(Methods.OPTIONS, registeredCatalog.dataApi().path().toString());
			graphQLRouter.remove(Methods.OPTIONS, registeredCatalog.schemaApi().path().toString());
		}
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		final GraphQL graphql = new SystemGraphQLBuilder(evita).build(graphQLConfig);
		registerGraphQLEndpoint(new RegisteredGraphQLApi(
			UriPath.of("/", "system"),
			new AtomicReference<>(graphql)
		));
	}

	/**
	 * Creates new GraphQL endpoint on specified path with specified {@link GraphQL} instance.
	 */
	private void registerGraphQLEndpoint(@Nonnull RegisteredGraphQLApi registeredGraphQLApi) {
		// GraphQL handler for main HTTP web API
		graphQLRouter.add(
			Methods.POST,
			registeredGraphQLApi.path().toString(),
			new BlockingHandler(
				new CorsFilter(
					new GraphQLExceptionHandler(
						objectMapper,
						new GraphQLWebHandler(objectMapper, evita.getConfiguration(), registeredGraphQLApi.graphQLReference())
					),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
		// CORS pre-flight handler for the GraphQL handler
		graphQLRouter.add(
			Methods.OPTIONS,
			registeredGraphQLApi.path().toString(),
			new BlockingHandler(
				new CorsPreflightHandler(
					graphQLConfig.getAllowedOrigins(),
					Set.of(Methods.POST_STRING),
					Set.of(Headers.CONTENT_TYPE_STRING, Headers.ACCEPT_STRING)
				)
			)
		);


		// GraphQL handler for WebSocket web API
		// todo lho this is only prototype
		graphQLRouter.add(
			Methods.GET,
			registeredGraphQLApi.path() + "-ws",
			new GraphQLExceptionHandler(
				objectMapper,
				new CorsFilter(
					new GraphQLWebSocketHandler(objectMapper, evita, registeredGraphQLApi.graphQLReference()),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
		// CORS pre-flight handler for the GraphQL handler
		graphQLRouter.add(
			Methods.OPTIONS,
			registeredGraphQLApi.path() + "-ws",
			new BlockingHandler(
				new CorsPreflightHandler(
					graphQLConfig.getAllowedOrigins(),
					Set.of(Methods.POST_STRING),
					Set.of(Headers.CONTENT_TYPE_STRING, Headers.ACCEPT_STRING)
				)
			)
		);
	}

	/**
	 * Unified way of building catalog's URL path from its name.
	 */
	@Nonnull
	private UriPath buildCatalogDataApiPath(@Nonnull CatalogSchemaContract catalogSchema) {
		return UriPath.of(catalogSchema.getNameVariants().get(URL_NAME_NAMING_CONVENTION));
	}

	/**
	 * Unified way of building catalog's URL path from its name.
	 */
	@Nonnull
	private UriPath buildCatalogSchemaApiPath(@Nonnull CatalogSchemaContract catalogSchema) {
		return UriPath.of(buildCatalogDataApiPath(catalogSchema), "schema");
	}

	private record RegisteredCatalog(@Nonnull RegisteredGraphQLApi dataApi,
	                                 @Nonnull RegisteredGraphQLApi schemaApi) {}

	private record RegisteredGraphQLApi(@Nonnull UriPath path,
	                                    @Nonnull AtomicReference<GraphQL> graphQLReference) {}
}
