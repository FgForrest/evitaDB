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

package io.evitadb.externalApi.lab.gui.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.lab.LabManager;
import io.evitadb.externalApi.lab.configuration.GuiConfig;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.evitadb.externalApi.lab.gui.dto.EvitaDBConnection;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.server.handlers.resource.ResourceSupplier;
import io.undertow.util.Headers;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Serves static files of lab GUI from fs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiHandler extends ResourceHandler {

	private static final String EVITALAB_SERVER_NAME_COOKIE = "evitalab_servername";
	private static final String EVITALAB_READONLY_COOKIE = "evitalab_readonly";
	private static final String EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE = "evitalab_pconnections";

	private static final Pattern ASSETS_PATTERN = Pattern.compile("/assets/[a-zA-Z0-9\\-]+\\.[a-z0-9]+");
	public static final Encoder BASE_64_ENCODER = Base64.getEncoder();

	@Nonnull private final LabConfig labConfig;
	@Nonnull private final ServerOptions serverOptions;
	@Nonnull private final ApiOptions apiOptions;
	@Nonnull private final ObjectMapper objectMapper;

	private GuiHandler(@Nonnull ResourceSupplier resourceSupplier,
					   @Nonnull LabConfig labConfig,
					   @Nonnull ServerOptions serverOptions,
					   @Nonnull ApiOptions apiOptions,
	                   @Nonnull ObjectMapper objectMapper) {
		super(resourceSupplier);
		this.labConfig = labConfig;
		this.serverOptions = serverOptions;
		this.apiOptions = apiOptions;
		this.objectMapper = objectMapper;
	}

	@Nonnull
	public static GuiHandler create(@Nonnull LabConfig labConfig,
	                                @Nonnull ServerOptions serverOptions,
	                                @Nonnull ApiOptions apiOptions,
	                                @Nonnull ObjectMapper objectMapper) {
		try (final ResourceManager rm = new ClassPathResourceManager(GuiHandler.class.getClassLoader(), "META-INF/lab/gui/dist")) {
			return new GuiHandler(new GuiResourceSupplier(rm), labConfig, serverOptions, apiOptions, objectMapper);
		} catch (IOException e) {
			throw new ExternalApiInternalError("Failed to load GUI resources.", e);
		}
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		passServerName(exchange);
		passReadOnlyFlag(exchange);
		passPreconfiguredEvitaDBConnections(exchange);
		super.handleRequest(exchange);
	}

	private void passServerName(@Nonnull HttpServerExchange exchange) {
		exchange.getResponseHeaders().add(
			Headers.SET_COOKIE,
			createCookie(EVITALAB_SERVER_NAME_COOKIE, serverOptions.name())
		);
	}

	/**
	 * Sends a {@link #EVITALAB_READONLY_COOKIE} cookie to the evitaLab with {@link GuiConfig#isReadOnly()} flag.
	 * If true, the evitaLab GUI will be in read-only mode.
	 */
	private void passReadOnlyFlag(@Nonnull HttpServerExchange exchange) {
		if (labConfig.getGui().isReadOnly()) {
			exchange.getResponseHeaders().add(
				Headers.SET_COOKIE,
				createCookie(EVITALAB_READONLY_COOKIE, "true")
			);
		}
	}

	/**
	 * Sends a {@link #EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE} cookie to the evitaLab with preconfigured
	 * evitaDB connections.
	 */
	private void passPreconfiguredEvitaDBConnections(@Nonnull HttpServerExchange exchange) throws IOException {
		final List<EvitaDBConnection> preconfiguredConnections = resolvePreconfiguredEvitaDBConnections();
		final String serializedSelfConnection = objectMapper.writeValueAsString(preconfiguredConnections);

		exchange.getResponseHeaders().add(
			Headers.SET_COOKIE,
			createCookie(EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE, serializedSelfConnection)
		);
	}

	@Nonnull
	private List<EvitaDBConnection> resolvePreconfiguredEvitaDBConnections() {
		final List<EvitaDBConnection> preconfiguredConnections = labConfig.getGui().getPreconfiguredConnections();
		if (preconfiguredConnections != null) {
			return preconfiguredConnections;
		}

		final RestConfig restConfig = apiOptions.getEndpointConfiguration(RestProvider.CODE);
		final GraphQLConfig graphQLConfig = apiOptions.getEndpointConfiguration(GraphQLProvider.CODE);
		final EvitaDBConnection selfConnection = new EvitaDBConnection(
			null,
			serverOptions.name(),
			labConfig.getBaseUrls()[0] + LabManager.LAB_API_URL_PREFIX,
			Optional.ofNullable(restConfig).map(it -> it.getBaseUrls()[0]).orElse(null),
			Optional.ofNullable(graphQLConfig).map(it -> it.getBaseUrls()[0]).orElse(null)
		);
		return List.of(selfConnection);
	}

	@Nonnull
	private String createCookie(@Nonnull String name, @Nonnull String value) {
		return name + "=" + BASE_64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + ";SameSite=Strict";
	}

	@RequiredArgsConstructor
	private static class GuiResourceSupplier implements ResourceSupplier {

		@Nonnull private final ResourceManager resourceManager;

		@Override
		public Resource getResource(HttpServerExchange exchange, String path) throws IOException {
			if (path == null) {
				return null;
			} else if (path.isEmpty() || path.equals("/index.html")) {
				return resourceManager.getResource("index.html");
			} else if (path.equals("/favicon.ico")) {
				return resourceManager.getResource("favicon.ico");
			} else if (ASSETS_PATTERN.matcher(path).matches()) {
				return resourceManager.getResource(path);
			} else {
				return null;
			}
		}
	}
}
