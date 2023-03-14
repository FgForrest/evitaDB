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

package io.evitadb.externalApi.rest.api.system.builder;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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

	public SystemRestBuildingContext(@Nonnull RestConfig restConfig, @Nonnull Evita evita) {
		super(restConfig, evita);
	}

	@Nonnull
	@Override
	protected List<Server> buildOpenApiServers() {
		return Arrays.stream(restConfig.getHost())
			.map(host -> new Server()
				.url("https://" + host.hostName() + ":" + host.port() + "/" + restConfig.getPrefix() + "/" + OpenApiSystemEndpoint.URL_PREFIX)
				.description(""))
			.toList();
	}

	@Nonnull
	@Override
	protected Info buildOpenApiInfo() {
		final Info info = new Info();
		info.setTitle("Web services for managing evitaDB.");
		info.setContact(new Contact().email("novotny@fg.cz").url("https://www.fg.cz"));
		info.setVersion("1.0.0-oas3");
		info.setLicense(new License().name("Business Source License 1.1").url("https://github.com/FgForrest/evitaDB/blob/dev/LICENSE"));
		return info;
	}
}
