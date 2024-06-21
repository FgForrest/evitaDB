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

import com.linecorp.armeria.server.HttpService;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.undertow.server.HttpHandler;

import javax.annotation.Nonnull;

/**
 * Registers lab provider to provide lab API and GUI. It serves mainly as {@link LabManager}
 * initializer.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class LabProviderRegistrar implements ExternalApiProviderRegistrar<LabConfig> {

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return LabProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<LabConfig> getConfigurationClass() {
		return LabConfig.class;
	}

	@Nonnull
	@Override
	public ExternalApiProvider<LabConfig> register(@Nonnull Evita evita, @Nonnull ExternalApiServer externalApiServer, @Nonnull ApiOptions apiOptions, @Nonnull LabConfig labConfig) {
		final LabManager labManager = new LabManager(evita, apiOptions, labConfig);
		final HttpService apiHandler = labManager.getLabRouter();
		return new LabProvider(labConfig, apiHandler);
	}
}
