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

package io.evitadb.externalApi.rest.api.system.builder;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.swagger.v3.oas.models.servers.Server;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * This context contains objects which are used (and shared) during building REST API for evitaDB management.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SystemRestBuildingContext extends RestBuildingContext {

	private static final String OPEN_API_TITLE = "Web services for managing evitaDB.";

	public SystemRestBuildingContext(
		@Nonnull RestOptions restConfig,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull Evita evita
	) {
		super(restConfig, headerOptions, evita);
	}

	@Nonnull
	@Override
	protected List<Server> buildOpenApiServers() {
		return Arrays.stream(restConfig.getBaseUrls())
			.map(baseUrl -> new Server()
				.url(baseUrl + OpenApiSystemEndpoint.URL_PREFIX))
			.toList();
	}

	@Nonnull
	@Override
	protected String getOpenApiTitle() {
		return OPEN_API_TITLE;
	}
}
