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

package io.evitadb.externalApi.rest;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.configuration.RestOptions;

import javax.annotation.Nonnull;

/**
 * Registers REST API provider to provide REST API to clients.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestProviderRegistrar implements ExternalApiProviderRegistrar<RestOptions> {

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return RestProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<RestOptions> getConfigurationClass() {
		return RestOptions.class;
	}

	@Nonnull
	@Override
	public ExternalApiProvider<RestOptions> register(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull RestOptions restConfiguration
	) {
		final RestManager restManager = new RestManager(evita, apiOptions.headers(), restConfiguration);
		return new RestProvider(restConfiguration, restManager, apiOptions.requestTimeoutInMillis());
	}
}
