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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Custom HTTP router for REST APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class RestRouter implements HttpService, WebSocketHandler {

	private static final String SYSTEM_API_NAME = "system";

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper;
	@Nonnull private final HeaderOptions headers;
	@Nonnull private final RestOptions restConfig;
	private final PathHandlingService delegateRouter = new PathHandlingService();

	@Nonnull private final Set<String> registeredApis = createHashSet(20);

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
	 * Registers new API with endpoints under specified API name.
	 */
	private void registerApi(@Nonnull String apiName, @Nonnull Rest api) {
		Assert.isPremiseValid(
			!this.registeredApis.contains(apiName),
			() -> new RestInternalError("API `" + apiName + "` has been already registered.")
		);

		final RoutingHandlerService apiRouter = new RoutingHandlerService();
		final Map<UriPath, CorsEndpoint> corsEndpoints = createConcurrentHashMap(20);

		api.endpoints().forEach(endpoint -> {
			final CorsEndpoint corsEndpoint = corsEndpoints.computeIfAbsent(endpoint.path(), p -> new CorsEndpoint(this.headers));
			registerEndpoint(apiRouter, corsEndpoint, endpoint);
		});
		corsEndpoints.forEach((path, endpoint) -> apiRouter.add(HttpMethod.OPTIONS, path.toString(), endpoint.toHandler()));

		//fix router hierarchical inputs
		this.delegateRouter.addPrefixPath(constructApiPath(apiName).toString(), apiRouter);

		this.registeredApis.add(apiName);
	}

	/**
	 * Unregisters all APIs associated with the defined catalog.
	 */
	public void unregisterCatalogApi(@Nonnull String catalogName) {
		final boolean catalogRegistered = this.registeredApis.remove(catalogName);
		if (catalogRegistered) {
			this.delegateRouter.removePrefixPath(constructApiPath(catalogName).toString());
		}
	}

	/**
	 * Creates new REST endpoint on specified path with specified {@link Rest} instance.
	 */
	private void registerEndpoint(@Nonnull RoutingHandlerService apiRouter, @Nonnull CorsEndpoint corsEndpoint, @Nonnull Rest.Endpoint endpoint) {
		corsEndpoint.addMetadataFromEndpoint(endpoint.handler());

		apiRouter.add(
			endpoint.method(),
			endpoint.path().toString(),
			CorsService.standaloneFilter(
				endpoint.handler()
					.decorate(service -> new RestExceptionHandler(this.objectMapper, service))
			)
		);
	}

	@Nonnull
	private UriPath constructApiPath(@Nonnull String apiName) {
		return UriPath.of("/", apiName);
	}
}
