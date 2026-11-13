/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.web.GraphQLWebHandler;
import io.evitadb.externalApi.graphql.io.webSocket.GraphQLWebSocketHandler;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.http.routing.EndpointRoutingService;
import io.evitadb.externalApi.http.routing.PrefixRoutingService;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils.Property;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Custom HTTP router for GraphQL APIs. The system API and every catalog own one prefix bound to an immutable
 * {@link EndpointRoutingService}; a catalog's DATA and SCHEMA APIs share its prefix and are published together.
 * A catalog refresh swaps the `GraphQL` instance behind each API's reference without republishing any route.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class GraphQLRouter implements HttpService, WebSocketHandler {

	public static final String SYSTEM_PREFIX = "system";
	private static final UriPath SYSTEM_PATH = UriPath.of("/", SYSTEM_PREFIX);
	private static final Map<GraphQLInstanceType, UriPath> API_PATH_MAPPING = createHashMap(
		new Property<>(GraphQLInstanceType.SYSTEM, UriPath.of("/")),
		new Property<>(GraphQLInstanceType.DATA, UriPath.of("/")),
		new Property<>(GraphQLInstanceType.SCHEMA, UriPath.of("/", "schema"))
	);

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper;
	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;
	/**
	 * Provides access to headers configuration.
	 */
	@Nonnull private final HeaderOptions headers;

	/** One prefix for the system API and one per catalog, each bound to an immutable endpoint router. */
	private final PrefixRoutingService delegateRouter = new PrefixRoutingService();
	private boolean systemApiRegistered = false;
	/** API references of every published catalog, used by instance refreshes and to remove the catalog's prefix. */
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createConcurrentHashMap(20);

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		return this.delegateRouter.serve(ctx, req);
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		return this.delegateRouter.handle(ctx, in);
	}

	/**
	 * Registers new system endpoints.
	 */
	public void registerSystemApi(@Nonnull GraphQL systemApi) {
		Assert.isPremiseValid(
			!this.systemApiRegistered,
			() -> new GraphQLInternalError("System API has been already registered.")
		);

		final EndpointRoutingService.Builder apiRouter = new EndpointRoutingService.Builder();
		registerApi(
			apiRouter,
			new RegisteredApi(
				GraphQLInstanceType.SYSTEM,
				API_PATH_MAPPING.get(GraphQLInstanceType.SYSTEM),
				new AtomicReference<>(systemApi)
			)
		);
		this.delegateRouter.addPrefixPath(SYSTEM_PATH.toString(), apiRouter.build());

		this.systemApiRegistered = true;
	}

	/**
	 * Builds DATA and SCHEMA endpoints together and publishes the complete catalog router once, so no request can
	 * reach one of the two APIs while the other is missing.
	 *
	 * @param catalogName name of the catalog, which is also its path prefix
	 * @param dataApi     GraphQL instance serving the catalog's data API
	 * @param schemaApi   GraphQL instance serving the catalog's schema API
	 * @throws GraphQLInternalError when APIs of the catalog are already registered
	 */
	public void registerCatalogApi(
		@Nonnull String catalogName, @Nonnull GraphQL dataApi, @Nonnull GraphQL schemaApi
	) {
		Assert.isPremiseValid(
			!this.registeredCatalogs.containsKey(catalogName),
			() -> new GraphQLInternalError("Catalog APIs have been already registered for `" + catalogName + "`.")
		);
		final EndpointRoutingService.Builder apiRouter = new EndpointRoutingService.Builder();
		final RegisteredApi registeredDataApi = new RegisteredApi(
			GraphQLInstanceType.DATA, API_PATH_MAPPING.get(GraphQLInstanceType.DATA), new AtomicReference<>(dataApi)
		);
		final RegisteredApi registeredSchemaApi = new RegisteredApi(
			GraphQLInstanceType.SCHEMA, API_PATH_MAPPING.get(GraphQLInstanceType.SCHEMA),
			new AtomicReference<>(schemaApi)
		);
		registerApi(apiRouter, registeredDataApi);
		registerApi(apiRouter, registeredSchemaApi);
		final EndpointRoutingService catalogRouter = apiRouter.build();
		this.delegateRouter.addPrefixPath(constructCatalogPath(catalogName).toString(), catalogRouter);
		this.registeredCatalogs.put(catalogName, new RegisteredCatalog(registeredDataApi, registeredSchemaApi));
	}

	/**
	 * Swaps GraphQL instance for already registered API for defined catalog
	 */
	public void refreshCatalogApi(@Nonnull String catalogName, @Nonnull GraphQLInstanceType instanceType, @Nonnull GraphQL newApi) {
		final RegisteredCatalog registeredCatalog = this.registeredCatalogs.get(catalogName);
		if (registeredCatalog == null) {
			throw new GraphQLInternalError("No catalog APIs registered for `" + catalogName + "`. Cannot refresh.");
		}

		registeredCatalog.getApi(instanceType).graphQLReference().set(newApi);
	}

	/**
	 * Unregisters all APIs associated with the defined catalog.
	 */
	public void unregisterCatalogApis(@Nonnull String catalogName) {
		final RegisteredCatalog registeredCatalog = this.registeredCatalogs.remove(catalogName);
		if (registeredCatalog != null) {
			this.delegateRouter.removePrefixPath(constructCatalogPath(catalogName).toString());
		}
	}

	/**
	 * Registers all needed endpoints for a single API into a passed router.
	 */
	private void registerApi(
		@Nonnull EndpointRoutingService.Builder apiRouter, @Nonnull RegisteredApi registeredApi
	) {
		final CorsEndpoint corsEndpoint = new CorsEndpoint(this.headers);
		corsEndpoint.addMetadata(Set.of(HttpMethod.GET, HttpMethod.POST), true, true);

		// GET method supports both subscriptions via WebSockets (GET method is used to upgrade the connection to WebSocket connection)
		// or fetching GQL schema via standard HTTP
		apiRouter.add(
			HttpMethod.GET,
			registeredApi.path().toString(),
			CorsService.standaloneFilter(
				new GraphQLWebSocketHandler(
					registeredApi.graphQLReference(),
					registeredApi.instanceType(),
					this.objectMapper,
					new GraphQLSchemaHandler(
						this.evita,
						registeredApi.graphQLReference()
					)
						.decorate(service -> new GraphQLExceptionHandler(this.objectMapper, service))
				)
			)
		);
		// POST method supports only querying
		apiRouter.add(
			HttpMethod.POST,
			registeredApi.path().toString(),
			CorsService.standaloneFilter(
				new GraphQLWebHandler(this.evita, this.headers, this.objectMapper, registeredApi.instanceType(), registeredApi.graphQLReference())
					.decorate(service -> new GraphQLExceptionHandler(this.objectMapper, service))
			)
		);

		// CORS pre-flight handler for the GraphQL handler
		apiRouter.add(
			HttpMethod.OPTIONS,
			registeredApi.path().toString(),
			corsEndpoint.toHandler()
		);
	}

	/**
	 * Unified way of building catalog's URL path from its name.
	 */
	@Nonnull
	private static UriPath constructCatalogPath(@Nonnull String catalogName) {
		return UriPath.of("/", catalogName);
	}

	/**
	 * Tracks the references refreshed independently of the immutable catalog router.
	 *
	 * @param data   DATA API whose GraphQL instance a refresh swaps
	 * @param schema SCHEMA API whose GraphQL instance a refresh swaps
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record RegisteredCatalog(@Nonnull RegisteredApi data, @Nonnull RegisteredApi schema) {

		/** Returns the registered reference for an instance refresh; a catalog has no SYSTEM API. */
		@Nonnull
		public RegisteredApi getApi(@Nonnull GraphQLInstanceType instanceType) {
			return switch (instanceType) {
				case DATA -> this.data;
				case SCHEMA -> this.schema;
				case SYSTEM -> throw new GraphQLInternalError(
					"API `" + instanceType.name() + "` has not been registered yet."
				);
			};
		}
	}

	private record RegisteredApi(@Nonnull GraphQLInstanceType instanceType,
	                             @Nonnull UriPath path,
	                             @Nonnull AtomicReference<GraphQL> graphQLReference) {}
}
