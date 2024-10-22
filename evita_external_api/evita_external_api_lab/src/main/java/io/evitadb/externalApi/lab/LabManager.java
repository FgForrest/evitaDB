/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.lab.api.LabApiBuilder;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.evitadb.externalApi.lab.gui.resolver.GuiHandler;
import io.evitadb.externalApi.lab.io.LabExceptionHandler;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * Manages lab API and GUI exposure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class LabManager {

	public static final String LAB_API_URL_PREFIX = "api";

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull private final Evita evita;
	@Nonnull private final LabConfig labConfig;

	/**
	 * evitaLab specific endpoint router.
	 */
	@Nonnull private final RoutingHandlerService labRouter = new RoutingHandlerService();
	@Nonnull private final Map<UriPath, CorsEndpoint> corsEndpoints = createConcurrentHashMap(20);

	public LabManager(@Nonnull Evita evita, @Nonnull LabConfig labConfig) {
		this.evita = evita;
		this.labConfig = labConfig;

		final long buildingStartTime = System.currentTimeMillis();

		registerLabApi();
		registerLabGui();
		corsEndpoints.forEach((path, endpoint) -> labRouter.add(HttpMethod.OPTIONS, path.toString(), endpoint.toHandler()));

		log.info("Built Lab in " + StringUtils.formatPreciseNano(System.currentTimeMillis() - buildingStartTime));
	}

	@Nonnull
	public HttpService getLabRouter() {
		return new PathNormalizingHandler(labRouter);
	}

	/**
	 * Builds REST API for evitaLab and registers it into router.
	 */
	private void registerLabApi() {
		final LabApiBuilder labApiBuilder = new LabApiBuilder(labConfig, evita);
		final Rest builtLabApi = labApiBuilder.build();
		builtLabApi.endpoints().forEach(this::registerLabApiEndpoint);
	}

	/**
	 * Creates new lab API endpoint on specified path with specified {@link Rest} instance.
	 */
	private void registerLabApiEndpoint(@Nonnull Rest.Endpoint endpoint) {
		final UriPath path = UriPath.of("/", LAB_API_URL_PREFIX, endpoint.path());

		final CorsEndpoint corsEndpoint = corsEndpoints.computeIfAbsent(path, p -> new CorsEndpoint());
		corsEndpoint.addMetadataFromEndpoint(endpoint.handler());

		labRouter.add(
			endpoint.method(),
			path.toString(),
			CorsService.standaloneFilter(
				endpoint.handler()
					.decorate(service -> new LabExceptionHandler(objectMapper, service))
			)
		);
	}

	/**
	 * Creates new endpoint for serving lab GUI static files from fs.
	 */
	private void registerLabGui() {
		final UriPath endpointPath = UriPath.of("/", "*");

		final CorsEndpoint corsEndpoint = corsEndpoints.computeIfAbsent(endpointPath, p -> new CorsEndpoint());
		corsEndpoint.addMetadata(Set.of(HttpMethod.GET), true, true);

		final EvitaConfiguration configuration = evita.getConfiguration();
		labRouter.add(
			HttpMethod.GET,
			endpointPath.toString(),
			CorsService.standaloneFilter(
				GuiHandler.create(labConfig, configuration.name(), objectMapper)
					.decorate(service -> new LabExceptionHandler(objectMapper, service))
			)
		);
	}
}
