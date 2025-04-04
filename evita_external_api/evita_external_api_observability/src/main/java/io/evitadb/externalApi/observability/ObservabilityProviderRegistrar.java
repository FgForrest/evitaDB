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

package io.evitadb.externalApi.observability;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.configuration.TracingConfig;
import io.evitadb.externalApi.observability.trace.OpenTelemetryTracerSetup;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Registers Metrics API provider to provide metrics API to maintainers.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ObservabilityProviderRegistrar implements ExternalApiProviderRegistrar<ObservabilityOptions> {
	@Nonnull
	@Override
	public String getExternalApiCode() {
		return ObservabilityProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<ObservabilityOptions> getConfigurationClass() {
		return ObservabilityOptions.class;
	}

	/**
	 * Needs to be registered before gRPC.
	 */
	@Override
	public int getOrder() {
		return -10;
	}

	@Nonnull
	@Override
	public ExternalApiProvider<ObservabilityOptions> register(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull ObservabilityOptions observabilityConfig
	) {
		final ObservabilityManager observabilityManager = new ObservabilityManager(
			apiOptions.headers(), observabilityConfig, evita
		);
		final TracingConfig tracingConfig = observabilityConfig.getTracing();
		if (tracingConfig != null && tracingConfig.endpoint() != null) {
			OpenTelemetryTracerSetup.setTracingConfig(observabilityConfig.getTracing());
		}
		observabilityManager.registerPrometheusMetricHandler();
		return new ObservabilityProvider(
			observabilityConfig,
			observabilityManager,
			Arrays.stream(observabilityConfig.getBaseUrls())
				.toArray(String[]::new),
			apiOptions.requestTimeoutInMillis()
		);
	}
}
