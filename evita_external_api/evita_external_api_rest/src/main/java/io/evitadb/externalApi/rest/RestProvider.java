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

import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.function.Predicate;

/**
 * Descriptor of external API provider that provides REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class RestProvider implements ExternalApiProvider<RestOptions> {

	public static final String CODE = "rest";

	@Nonnull
	@Getter
	private final RestOptions configuration;
	@Nonnull
	private final RestManager restManager;

	/**
	 * Timeout taken from {@link ApiOptions#requestTimeoutInMillis()} that will be used in {@link #isReady()}
	 * method.
	 */
	private final long requestTimeout;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	public RestProvider(@Nonnull RestOptions configuration, @Nonnull RestManager restManager, long requestTimeout) {
		this.configuration = configuration;
		this.restManager = restManager;
		this.requestTimeout = requestTimeout;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		return new HttpServiceDefinition[] {
			new HttpServiceDefinition(this.restManager.getRestRouter(), PathHandlingMode.DYNAMIC_PATH_HANDLING)
		};
	}

	@Override
	public void afterAllInitialized() {
		this.restManager.emitObservabilityEvents();
	}

	@Override
	public boolean isReady() {
		final Predicate<String> isReady = url -> {
			final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
			return NetworkUtils.fetchContent(
					url,
					"GET",
					"application/json",
					null,
					this.requestTimeout,
					error -> {
						log.error("Error while checking readiness of REST API: {}", error);
						readinessEvent.finish(Result.ERROR);
					},
					timeouted -> {
						log.error("{}", timeouted);
						readinessEvent.finish(Result.TIMEOUT);
					}
				)
				.map(content -> {
					final boolean result = content.contains("true");
					if (result) {
						readinessEvent.finish(Result.READY);
					}
					return result;
				})
				.orElse(false);
		};
		final String[] baseUrls = this.configuration.getBaseUrls();
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
