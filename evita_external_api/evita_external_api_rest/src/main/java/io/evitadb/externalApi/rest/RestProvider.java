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
import io.evitadb.externalApi.http.ReadinessDiscoveryStallTracker;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.Consumer;

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

	/**
	 * Tracks how long the readiness discovery phase (see {@link #reachableUrl}) has been running, so a server that
	 * never becomes reachable on any candidate URL is reported once instead of on every single probe.
	 */
	private final ReadinessDiscoveryStallTracker stallTracker = new ReadinessDiscoveryStallTracker();

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
		if (this.reachableUrl == null) {
			// discovery phase: some candidate URLs (e.g. a publicly exposed hostname) are expected to fail until
			// the reachable one is found, so individual failures are only worth a DEBUG line here
			final String[] baseUrls = this.configuration.getBaseUrls();
			final Map<String, String> failures = CollectionUtils.createLinkedHashMap(baseUrls.length);
			for (String baseUrl : baseUrls) {
				final String url = baseUrl + OpenApiSystemEndpoint.URL_PREFIX + "/" + LivenessDescriptor.LIVENESS_SUFFIX;
				if (probe(url, message -> {
					failures.put(url, message);
					log.debug("Error while checking readiness of REST API: {}", message);
				})) {
					this.reachableUrl = url;
					return true;
				}
			}
			if (this.stallTracker.shouldWarnAboutStall()) {
				log.warn(
					"REST API has not become reachable on any of the {} configured URL(s) for over {}s " +
						"(this can be normal while the server is still starting up): {}",
					baseUrls.length, ReadinessDiscoveryStallTracker.GRACE_PERIOD.toSeconds(), failures
				);
			}
			return false;
		} else {
			// steady state: this URL was reachable before, so a failure now is a genuine regression
			return probe(
				this.reachableUrl,
				message -> log.error("Error while checking readiness of REST API: {}", message)
			);
		}
	}

	/**
	 * Performs a single readiness probe against the given URL, reporting any failure message via {@code failureLogger}.
	 */
	private boolean probe(@Nonnull String url, @Nonnull Consumer<String> failureLogger) {
		final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
		return NetworkUtils.fetchContent(
				url,
				"GET",
				"application/json",
				null,
				this.requestTimeout,
				error -> {
					failureLogger.accept(error);
					readinessEvent.finish(Result.ERROR);
				},
				timeouted -> {
					failureLogger.accept(timeouted);
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
	}

}
