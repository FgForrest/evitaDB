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

package io.evitadb.externalApi.rest;

import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.rest.configuration.RESTConfig;

import javax.annotation.Nonnull;

/**
 * Registers REST API provider to provide REST API to clients.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RESTProviderRegistrar implements ExternalApiProviderRegistrar<RESTConfig> {

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return RESTProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<RESTConfig> getConfigurationClass() {
		return RESTConfig.class;
	}

	/**
	 * Register REST API
	 * @param evitaSystemDataProvider ready-to-use Evita with access to internal data structures
	 * @param restConfiguration configuration parameters for this provider (structure is defined by provider itself)
	 * @return
	 */
	@Nonnull
	@Override
	public ExternalApiProvider register(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider, @Nonnull ApiOptions apiOptions, @Nonnull RESTConfig restConfiguration) {
		final RESTApiManager restApiManager = new RESTApiManager(evitaSystemDataProvider);

		evitaSystemDataProvider.getEvita().registerStructuralChangeObserver(new CatalogRestRefreshingObserver(restApiManager));

		return new RESTProvider(restApiManager.getRestRouter());
	}
}
