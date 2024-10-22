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
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.http.ProxyingEndpointProvider;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Descriptor of provider of lab API and GUI.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class LabProvider implements ProxyingEndpointProvider<LabConfig> {

	public static final String CODE = "lab";

	@Nonnull
	@Getter
	private final LabConfig configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		return new HttpServiceDefinition[]{
			new HttpServiceDefinition(apiHandler, PathHandlingMode.DYNAMIC_PATH_HANDLING, true)
		};
	}

	@Override
	public boolean isReady() {
		final Predicate<String> isReady = url -> {
			final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
			return NetworkUtils.fetchContent(
					url,
					null,
					"text/html",
					Optional.ofNullable(configuration.getAllowedOrigins()).map(it -> it[0]).orElse(null),
					null,
					error -> {
						log.error("Error while checking readiness of Lab API: {}", error);
						readinessEvent.finish(Result.ERROR);
					},
					timeouted -> {
						log.error("{}", timeouted);
						readinessEvent.finish(Result.TIMEOUT);
					}
				)
				.map(content -> {
					final boolean result = content.contains("evitaLab app");
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
				if (isReady.test(baseUrl)) {
					this.reachableUrl = baseUrl;
					return true;
				}
			}
			return false;
		} else {
			return isReady.test(this.reachableUrl);
		}
	}

}
