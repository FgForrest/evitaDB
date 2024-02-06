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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.observability;

import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.observability.configuration.ObservabilityConfig;
import io.undertow.server.HttpHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Descriptor of external API provider that provides Metrics API.
 *
 * @see ObservabilityProviderRegistrar
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class ObservabilityProvider implements ExternalApiProvider<ObservabilityConfig> {
	public static final String CODE = "observability";

	@Nonnull
	@Getter
	private final ObservabilityConfig configuration;

	@Nonnull
	@Getter
	private final HttpHandler apiHandler;

	@Nonnull
	@Getter
	private final String[] serverNameUrls;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}
}
