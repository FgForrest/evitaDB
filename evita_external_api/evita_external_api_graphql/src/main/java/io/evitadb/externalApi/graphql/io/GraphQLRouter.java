/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.http.AdditionalHeaders;
import io.evitadb.externalApi.http.CorsFilter;
import io.evitadb.externalApi.http.CorsPreflightHandler;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * Custom Undertow router for GraphQL APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class GraphQLRouter implements HttpHandler {

	public static final String SYSTEM_PREFIX = "system";
	private static final UriPath SYSTEM_PATH = UriPath.of("/", SYSTEM_PREFIX);
	private static final UriPath SYSTEM_API_PATH = UriPath.of("/");
	private static final UriPath DATA_API_PATH = UriPath.of("/");
	private static final UriPath SCHEMA_API_PATH = UriPath.of("/", "schema");

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper;
	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;
	@Nonnull private final GraphQLConfig graphQLConfig;

	private final PathHandler delegateRouter = Handlers.path();
	private boolean systemApiRegistered = false;
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createConcurrentHashMap(20);

	@Override
	public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
		delegateRouter.handleRequest(httpServerExchange);
	}

	/**
	 * Registers new system endpoints.
	 */
	public void registerSystemApi(@Nonnull GraphQL systemApi) {
		Assert.isPremiseValid(
			!systemApiRegistered,
			() -> new GraphQLInternalError("System API has been already registered.")
		);

		final RoutingHandler apiRouter = Handlers.routing();
		registerApi(
			apiRouter,
			new RegisteredApi(SYSTEM_API_PATH, new AtomicReference<>(systemApi))
		);
		delegateRouter.addPrefixPath(SYSTEM_PATH.toString(), apiRouter);

		systemApiRegistered = true;
	}

	/**
	 * Registers new endpoints for defined catalog.
	 */
	public void registerCatalogApis(@Nonnull String catalogName, @Nonnull GraphQL dataApi, @Nonnull GraphQL schemaApi) {
		Assert.isPremiseValid(
			!registeredCatalogs.containsKey(catalogName),
			() -> new GraphQLInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		final RoutingHandler apiRouter = Handlers.routing();

		final RegisteredApi registeredDataApi = new RegisteredApi(
			DATA_API_PATH,
			new AtomicReference<>(dataApi)
		);
		registerApi(apiRouter, registeredDataApi);

		final RegisteredApi registeredSchemaApi = new RegisteredApi(
			SCHEMA_API_PATH,
			new AtomicReference<>(schemaApi)
		);
		registerApi(apiRouter, registeredSchemaApi);

		delegateRouter.addPrefixPath(constructCatalogPath(catalogName).toString(), apiRouter);

		final RegisteredCatalog registeredCatalog = new RegisteredCatalog(registeredDataApi, registeredSchemaApi);
		registeredCatalogs.put(catalogName, registeredCatalog);
	}

	/**
	 * Swaps GraphQL instances for already registered APIs for defined catalog
	 */
	public void refreshCatalogApis(@Nonnull String catalogName, @Nonnull GraphQL newDataApi, @Nonnull GraphQL newSchemaApi) {
		final RegisteredCatalog registeredCatalog = registeredCatalogs.get(catalogName);
		if (registeredCatalog == null) {
			throw new GraphQLInternalError("No catalog APIs registered for `" + catalogName + "`. Cannot refresh.");
		}

		registeredCatalog.dataApi().graphQLReference().set(newDataApi);
		registeredCatalog.schemaApi().graphQLReference().set(newSchemaApi);
	}

	/**
	 * Unregisters all APIs associated with the defined catalog.
	 */
	public void unregisterCatalogApis(@Nonnull String catalogName) {
		final RegisteredCatalog registeredCatalog = registeredCatalogs.remove(catalogName);
		if (registeredCatalog != null) {
			delegateRouter.removePrefixPath(constructCatalogPath(catalogName).toString());
		}
	}

	/**
	 * Registers all needed endpoints for a single API into a passed router.
	 */
	private void registerApi(@Nonnull RoutingHandler apiRouter, @Nonnull RegisteredApi registeredApi) {
		// actual GraphQL query handler
		apiRouter.add(
			Methods.POST,
			registeredApi.path().toString(),
			new BlockingHandler(
				new CorsFilter(
					new GraphQLExceptionHandler(
						objectMapper,
						new GraphQLHandler(objectMapper, evita, registeredApi.graphQLReference())
					),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
		// GraphQL schema handler
		apiRouter.add(
			Methods.GET,
			registeredApi.path().toString(),
			new BlockingHandler(
				new CorsFilter(
					new GraphQLExceptionHandler(
						objectMapper,
						new GraphQLSchemaHandler(registeredApi.graphQLReference())
					),
					graphQLConfig.getAllowedOrigins()
				)
			)
		);
		// CORS pre-flight handler for the GraphQL handler
		apiRouter.add(
			Methods.OPTIONS,
			registeredApi.path().toString(),
			new BlockingHandler(
				new CorsFilter(
					new CorsPreflightHandler(
						graphQLConfig.getAllowedOrigins(),
						Set.of(Methods.GET_STRING, Methods.POST_STRING),
						Set.of(
							Headers.CONTENT_TYPE_STRING,
							Headers.ACCEPT_STRING,
							// default headers for tracing that are allowed on every endpoint by default
							AdditionalHeaders.OPENTELEMETRY_TRACEPARENT_STRING,
							AdditionalHeaders.EVITADB_CLIENTID_HEADER_STRING
						)
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
	private UriPath constructCatalogPath(@Nonnull String catalogName) {
		return UriPath.of("/", catalogName);
	}

	private record RegisteredCatalog(@Nonnull RegisteredApi dataApi,
	                                 @Nonnull RegisteredApi schemaApi) {}

	private record RegisteredApi(@Nonnull UriPath path,
	                             @Nonnull AtomicReference<GraphQL> graphQLReference) {}
}
