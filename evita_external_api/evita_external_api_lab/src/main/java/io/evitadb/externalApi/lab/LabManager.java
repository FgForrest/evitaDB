/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.lab.configuration.LabOptions;
import io.evitadb.externalApi.lab.gui.resolver.GuiHandler;
import io.evitadb.externalApi.lab.io.LabExceptionHandler;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
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

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull private final Evita evita;
	@Nonnull private final HeaderOptions headerOptions;
	@Nonnull private final LabOptions labConfig;

	/**
	 * evitaLab specific endpoint router.
	 */
	@Nonnull private final RoutingHandlerService labRouter = new RoutingHandlerService();
	@Nonnull private final Map<UriPath, CorsEndpoint> corsEndpoints = createConcurrentHashMap(20);

	public LabManager(@Nonnull Evita evita, @Nonnull HeaderOptions headerOptions, @Nonnull LabOptions labConfig) {
		this.evita = evita;
		this.headerOptions = headerOptions;
		this.labConfig = labConfig;

		registerLabGui();
		this.corsEndpoints.forEach((path, endpoint) -> this.labRouter.add(HttpMethod.OPTIONS, path.toString(), endpoint.toHandler()));
	}

	@Nonnull
	public HttpService getLabRouter() {
		return this.labRouter.decorate(PathNormalizingHandler::new);
	}

	/**
	 * Creates new endpoint for serving lab GUI static files from fs.
	 */
	private void registerLabGui() {
		final UriPath endpointPath = UriPath.of("/", "*");

		final CorsEndpoint corsEndpoint = this.corsEndpoints.computeIfAbsent(endpointPath, p -> new CorsEndpoint(this.headerOptions));
		corsEndpoint.addMetadata(Set.of(HttpMethod.GET), true, true);

		final EvitaConfiguration configuration = this.evita.getConfiguration();
		this.labRouter.add(
			HttpMethod.GET,
			endpointPath.toString(),
			CorsService.standaloneFilter(
				GuiHandler.create(this.labConfig, configuration.name(), this.objectMapper)
					.decorate(service -> new LabExceptionHandler(this.objectMapper, service))
			)
		);
	}
}
