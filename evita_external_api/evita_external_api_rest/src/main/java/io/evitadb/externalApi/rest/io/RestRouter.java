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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.http.CorsFilter;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Methods;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Custom Undertow router for REST APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class RestRouter implements HttpHandler {

	private static final String SYSTEM_API_NAME = "system";

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper;
	@Nonnull private final RestConfig restConfig;

	private final PathHandler delegateRouter = Handlers.path();
	@Nonnull private final Set<String> registeredApis = createHashSet(20);

	@Override
	public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
		delegateRouter.handleRequest(httpServerExchange);
	}

	/**
	 * Registers new system API.
	 */
	public void registerSystemApi(@Nonnull Rest api) {
		registerApi(SYSTEM_API_NAME, api);
	}

	/**
	 * Registers new API for specified catalog.
	 */
	public void registerCatalogApi(@Nonnull String catalogName, @Nonnull Rest api) {
		registerApi(catalogName, api);
	}

	/**
	 * Registers new API with endpoints under specified API name.
	 */
	private void registerApi(@Nonnull String apiName, @Nonnull Rest api) {
		Assert.isPremiseValid(
			!registeredApis.contains(apiName),
			() -> new RestInternalError("API `" + apiName + "` has been already registered.")
		);

		final RoutingHandler apiRouter = Handlers.routing();
		final Map<UriPath, CorsEndpoint> corsEndpoints = createConcurrentHashMap(20);

		api.endpoints().forEach(endpoint -> {
			final CorsEndpoint corsEndpoint = corsEndpoints.computeIfAbsent(endpoint.path(), p -> new CorsEndpoint(restConfig));
			registerEndpoint(apiRouter, corsEndpoint, endpoint);
		});
		corsEndpoints.forEach((path, endpoint) -> apiRouter.add(Methods.OPTIONS, path.toString(), endpoint.toHandler()));

		delegateRouter.addPrefixPath(constructApiPath(apiName).toString(), apiRouter);

		registeredApis.add(apiName);
	}

	/**
	 * Unregisters all APIs associated with the defined catalog.
	 */
	public void unregisterCatalogApi(@Nonnull String catalogName) {
		final boolean catalogRegistered = registeredApis.remove(catalogName);
		if (catalogRegistered) {
			delegateRouter.removePrefixPath(constructApiPath(catalogName).toString());
		}
	}

	/**
	 * Creates new REST endpoint on specified path with specified {@link Rest} instance.
	 */
	private void registerEndpoint(@Nonnull RoutingHandler apiRouter, @Nonnull CorsEndpoint corsEndpoint, @Nonnull Rest.Endpoint endpoint) {
		corsEndpoint.addMetadataFromHandler(endpoint.handler());

		apiRouter.add(
			endpoint.method(),
			endpoint.path().toString(),
			new BlockingHandler(
				new CorsFilter(
					new RestExceptionHandler(
						objectMapper,
						endpoint.handler()
					),
					restConfig.getAllowedOrigins()
				)
			)
		);
	}

	@Nonnull
	private UriPath constructApiPath(@Nonnull String apiName) {
		return UriPath.of("/", apiName);
	}
}
