/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.driver.trace;

import io.evitadb.exception.EvitaInternalError;

import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Provider for fetching registered and used {@link ClientTracingContext} implementation that are fetched via {@link ServiceLoader}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ClientTracingContextProvider {

	/**
	 * Fetches and caches the {@link ClientTracingContext} implementation.
	 */
	public static ClientTracingContext getContext() {
		final List<ClientTracingContext> collectedContexts = ServiceLoader.load(ClientTracingContext.class)
			.stream()
			.map(Provider::get)
			.toList();
		if (collectedContexts.size() > 1) {
			throw new EvitaInternalError("There are multiple registered implementations of ExternalApiTracingContext.");
		}
		if (collectedContexts.size() == 1) {
			return collectedContexts.stream().findFirst().get();
		}
		return new DefaultClientTracingContext();
	}
}
