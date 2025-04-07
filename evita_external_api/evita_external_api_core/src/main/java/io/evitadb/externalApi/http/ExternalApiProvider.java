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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.utils.path.PathHandlingService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;

/**
 * Descriptor of single external API provider. External API provider is system that is responsible for serving
 * the API (e.g. GraphQL).
 *
 * It have to registered by {@link ExternalApiProviderRegistrar}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ExternalApiProvider<T extends AbstractApiOptions> {

	/**
	 * @return system-wide unique camelCase code of API to be able to select which APIs to register
	 */
	@Nonnull
	String getCode();

	/**
	 * @return configuration the provider was set up with
	 */
	@Nonnull
	T getConfiguration();

	/**
	 * @return HTTP handler that is responsible for processing all requests addressed to this API
	 */
	@Nonnull
	HttpServiceDefinition[] getHttpServiceDefinitions();

	/**
	 * Returns map of key endpoints and their absolute URLs that could be published to the user via.
	 * console or administration GUI
	 * @return index of symbolic name of the endpoint and the absolute URL as a value
	 */
	@Nonnull
	default Map<String, String[]> getKeyEndPoints() {
		return Collections.emptyMap();
	}

	/**
	 * Called automatically when entire server is done initializing but not started yet.
	 */
	default void afterAllInitialized() {
		// do nothing
	}

	/**
	 * Called automatically when root server has been started. Can be used e.g. initialize API provider.
	 */
	default void afterStart() {
		// do nothing
	}

	/**
	 * Called automatically when root server is about to be stopped. Can be used e.g. to clean up the API provider.
	 */
	default void beforeStop() {
		// do nothing
	}

	/**
	 * Returns TRUE if the API is ready to accept requests. This method should physically test an API call to determine
	 * the API responds to the requests.
	 *
	 * @return TRUE if the API is ready to accept requests
	 */
	boolean isReady();

	/**
	 * Represents HTTP service that is responsible for processing all requests addressed to this API on given sub-path.
	 *
	 * @param path sub-path of the API
	 * @param service HTTP service that is responsible for processing all requests addressed to path
	 * @param pathHandlingMode mode of path handling (see {@link PathHandlingMode})
	 * @param defaultService true, if the root path should be redirected to this service
	 */
	record HttpServiceDefinition(
		@Nullable String path,
		@Nonnull HttpService service,
		@Nonnull PathHandlingMode pathHandlingMode,
		boolean defaultService
	) {

		public HttpServiceDefinition(@Nonnull HttpService service, @Nonnull PathHandlingMode routing) {
			this("", service, routing, false);
		}

		public HttpServiceDefinition(@Nonnull HttpService service, @Nonnull PathHandlingMode routing, boolean defaultService) {
			this("", service, routing, defaultService);
		}

		public HttpServiceDefinition(@Nullable String path, @Nonnull HttpService service, @Nonnull PathHandlingMode pathHandlingMode) {
			this(path, service, pathHandlingMode, false);
		}

	}

	enum PathHandlingMode {
		/**
		 * Needs to be used for services on root path that execute their own path handling.
		 */
		FIXED_PATH_HANDLING,
		/**
		 * Might be used for services on sub-paths that can be handled by {@link PathHandlingService}, that can
		 * be dynamically updated during runtime.
		 */
		DYNAMIC_PATH_HANDLING
	}

}
