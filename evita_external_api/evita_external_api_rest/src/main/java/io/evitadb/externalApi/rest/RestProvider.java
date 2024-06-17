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

package io.evitadb.externalApi.rest;

import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.utils.NetworkUtils;
import io.undertow.server.HttpHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Descriptor of external API provider that provides REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class RestProvider implements ExternalApiProvider<RestConfig> {

	public static final String CODE = "rest";

	@Nonnull
	@Getter
	private final RestConfig configuration;
	@Nonnull
	private final RestManager restManager;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nullable
	@Override
	public HttpHandler getApiHandler() {
		return restManager.getRestRouter();
	}

	@Override
	public void afterAllInitialized() {
		restManager.emitObservabilityEvents();
	}

	@Override
	public boolean isReady() {
		final Predicate<String> isReady = url -> NetworkUtils.fetchContent(url, "GET", "application/json", null)
			.map(content -> content.contains("true"))
			.orElse(false);
		final String[] baseUrls = this.configuration.getBaseUrls(configuration.getExposedHost());
		if (this.reachableUrl == null) {
			for (String baseUrl : baseUrls) {
				final String url = baseUrl + OpenApiSystemEndpoint.URL_PREFIX + "/" + LivenessDescriptor.LIVENESS_SUFFIX;
				if (isReady.test(url)) {
					this.reachableUrl = url;
					return true;
				}
			}
			return false;
		} else {
			return isReady.test(this.reachableUrl);
		}
	}

}
