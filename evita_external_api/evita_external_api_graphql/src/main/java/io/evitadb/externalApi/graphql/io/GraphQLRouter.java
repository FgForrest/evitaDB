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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.web.GraphQLWebHandler;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils.Property;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Custom HTTP router for GraphQL APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class GraphQLRouter implements HttpService {

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

	private final PathHandlingService delegateRouter = new PathHandlingService();
	private boolean systemApiRegistered = false;
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createConcurrentHashMap(20);

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		return this.delegateRouter.serve(ctx, req);
	}

	/**
	 * Registers new system endpoints.
	 */
	public void registerSystemApi(@Nonnull GraphQL systemApi) {
		Assert.isPremiseValid(
			!this.systemApiRegistered,
			() -> new GraphQLInternalError("System API has been already registered.")
		);

		final RoutingHandlerService apiRouter = new RoutingHandlerService();
		registerApi(
			apiRouter,
			new RegisteredApi(
				GraphQLInstanceType.SYSTEM,
				API_PATH_MAPPING.get(GraphQLInstanceType.SYSTEM),
				new AtomicReference<>(systemApi)
			)
		);
		this.delegateRouter.addPrefixPath(SYSTEM_PATH.toString(), apiRouter);

		this.systemApiRegistered = true;
	}

	/**
	 * Registers new endpoints for defined catalog.
	 */
	public void registerCatalogApi(@Nonnull String catalogName, @Nonnull GraphQLInstanceType instanceType, @Nonnull GraphQL api) {
		final RegisteredCatalog registeredCatalog = this.registeredCatalogs.computeIfAbsent(catalogName, k -> {
			final RoutingHandlerService apiRouter = new RoutingHandlerService();
			this.delegateRouter.addPrefixPath(constructCatalogPath(catalogName).toString(), apiRouter);
			return new RegisteredCatalog(apiRouter);
		});

		final RegisteredApi registeredDataApi = new RegisteredApi(
			instanceType,
			API_PATH_MAPPING.get(instanceType),
			new AtomicReference<>(api)
		);
		registerApi(registeredCatalog.getApiRouter(), registeredDataApi);
		registeredCatalog.setApi(instanceType, registeredDataApi);
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
	private void registerApi(@Nonnull RoutingHandlerService apiRouter, @Nonnull RegisteredApi registeredApi) {
		final CorsEndpoint corsEndpoint = new CorsEndpoint(this.headers);
		corsEndpoint.addMetadata(Set.of(HttpMethod.GET, HttpMethod.POST), true, true);

		// actual GraphQL query handler
		apiRouter.add(
			HttpMethod.POST,
			registeredApi.path().toString(),
			CorsService.standaloneFilter(
				new GraphQLWebHandler(this.evita, this.headers, this.objectMapper, registeredApi.instanceType(), registeredApi.graphQLReference())
					.decorate(service -> new GraphQLExceptionHandler(this.objectMapper, service))
			)
		);
		// GraphQL schema handler
		apiRouter.add(
			HttpMethod.GET,
			registeredApi.path().toString(),
			CorsService.standaloneFilter(
				new GraphQLSchemaHandler(
					this.evita,
					registeredApi.graphQLReference()
				)
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

	@RequiredArgsConstructor
	private static class RegisteredCatalog {

		private static final Set<GraphQLInstanceType> ALLOWED_API_INSTANCES = Set.of(GraphQLInstanceType.DATA, GraphQLInstanceType.SCHEMA);

		@Getter
		private final RoutingHandlerService apiRouter;
		private final Map<GraphQLInstanceType, RegisteredApi> apis = createHashMap(2);

		public void setApi(@Nonnull GraphQLInstanceType instanceType, @Nonnull RegisteredApi api) {
			Assert.isPremiseValid(
				ALLOWED_API_INSTANCES.contains(instanceType),
				() -> new GraphQLInternalError("API `" + instanceType.name() + "` is not allowed for catalog.")
			);
			Assert.isPremiseValid(
				!this.apis.containsKey(instanceType),
				() -> new GraphQLInternalError("`" + instanceType.name() + "` API has been already registered.")
			);
			this.apis.put(instanceType, api);
		}

		public RegisteredApi getApi(@Nonnull GraphQLInstanceType instanceType) {
			final RegisteredApi api = this.apis.get(instanceType);
			Assert.isPremiseValid(
				api != null,
				() -> new GraphQLInternalError("API `" + instanceType.name() + "` has not been registered yet.")
			);
			return api;
		}
	}

	private record RegisteredApi(@Nonnull GraphQLInstanceType instanceType,
	                             @Nonnull UriPath path,
	                             @Nonnull AtomicReference<GraphQL> graphQLReference) {}
}
