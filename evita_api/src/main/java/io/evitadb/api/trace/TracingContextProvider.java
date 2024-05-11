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

package io.evitadb.api.trace;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Provider for fetching registered and used {@link TracingContext} implementation that are fetched via {@link ServiceLoader}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class TracingContextProvider {
	/**
	 * Singleton instance of the {@link TracingContext} implementation.
	 */
	private static final TracingContext TRACING_CONTEXT;

	static {
		final List<TracingContext> collectedContexts = ServiceLoader.load(TracingContext.class)
			.stream()
			.map(Provider::get)
			.toList();
		if (collectedContexts.size() > 1) {
			throw new GenericEvitaInternalError("There are multiple registered implementations of TracingContext.");
		}
		if (collectedContexts.size() == 1) {
			TRACING_CONTEXT = collectedContexts.stream().findFirst().get();
		} else {
			TRACING_CONTEXT = DefaultTracingContext.INSTANCE;
		}
	}

	/**
	 * Fetches and caches the {@link TracingContext} implementation.
	 */
	@Nonnull
	public static TracingContext getContext() {
		return loadContext();
	}

	/**
	 * Loads the {@link TracingContext} implementation using {@link ServiceLoader}.
	 * If there is only one implementation found, it returns that instance.
	 * If there are multiple implementations found, it throws an {@link EvitaInternalError}.
	 * If no implementations are found, it creates and returns a new instance of {@link DefaultTracingContext}.
	 *
	 * @return the loaded {@link TracingContext} implementation
	 * @throws EvitaInternalError if there are multiple registered implementations of {@link TracingContext}
	 */
	@Nonnull
	private static TracingContext loadContext() {
		return TRACING_CONTEXT;
	}
}
