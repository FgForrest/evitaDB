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

package io.evitadb.externalApi.http;

import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.undertow.server.HttpHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Descriptor of single external API provider. External API provider is system that is responsible for serving
 * the API (e.g. GraphQL).
 *
 * It have to registered by {@link ExternalApiProviderRegistrar}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ExternalApiProvider<T extends AbstractApiConfiguration> {

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
	@Nullable
	default HttpHandler getApiHandler() {
		return null;
	}

	/**
	 * Method should return true if the API is managed by the Undertow server.
	 */
	default boolean isManagedByUndertow() {
		return true;
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

}
