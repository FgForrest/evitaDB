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
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.GraphQL;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.http.AdditionalHeaders;
import io.evitadb.externalApi.http.CorsFilterServiceDecorator;
import io.evitadb.externalApi.http.CorsPreflightService;
import io.evitadb.externalApi.utils.PathHandlingService;
import io.evitadb.externalApi.utils.RoutingHandlerService;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
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
public class GraphQLRouter implements HttpService {

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

	private final PathHandlingService delegateRouter = new PathHandlingService();
	private boolean systemApiRegistered = false;
	@Nonnull private final Map<String, RegisteredCatalog> registeredCatalogs = createConcurrentHashMap(20);

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		return delegateRouter.serve(ctx, req);
	}

	/**
	 * Registers new system endpoints.
	 */
	public void registerSystemApi(@Nonnull GraphQL systemApi) {
		Assert.isPremiseValid(
			!systemApiRegistered,
			() -> new GraphQLInternalError("System API has been already registered.")
		);

		final RoutingHandlerService apiRouter = new RoutingHandlerService();
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

		final RoutingHandlerService apiRouter = new RoutingHandlerService();

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
	private void registerApi(@Nonnull RoutingHandlerService apiRouter, @Nonnull RegisteredApi registeredApi) {
		// actual GraphQL query handler
		apiRouter.add(
			HttpMethod.POST,
			registeredApi.path().toString(),
			new GraphQLExceptionHandler(
				objectMapper,
				new GraphQLHandler(objectMapper, evita, registeredApi.graphQLReference())
			).decorate(
				new CorsFilterServiceDecorator(
					graphQLConfig.getAllowedOrigins()
				).createDecorator()
			)
		);
		// GraphQL schema handler
		apiRouter.add(
			HttpMethod.GET,
			registeredApi.path().toString(),
			new GraphQLExceptionHandler(
				objectMapper,
				new GraphQLSchemaHandler(registeredApi.graphQLReference())
			).decorate(
				new CorsFilterServiceDecorator(
					graphQLConfig.getAllowedOrigins()
				).createDecorator()
			)
		);
		// CORS pre-flight handler for the GraphQL handler
		apiRouter.add(
			HttpMethod.OPTIONS,
			registeredApi.path().toString(),
			new CorsPreflightService(
				graphQLConfig.getAllowedOrigins(),
				Set.of(HttpMethod.GET.name(), HttpMethod.POST.name()),
				Set.of(
					HttpHeaderNames.CONTENT_TYPE.toString(),
					HttpHeaderNames.ACCEPT.toString(),
					// default headers for tracing that are allowed on every endpoint by default
					AdditionalHeaders.OPENTELEMETRY_TRACEPARENT_STRING,
					AdditionalHeaders.EVITADB_CLIENTID_HEADER_STRING
				)
			).decorate(
				new CorsFilterServiceDecorator(
					graphQLConfig.getAllowedOrigins()
				).createDecorator()
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
