/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.externalApi.trace;

import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Provider for fetching registered {@link ExternalApiTracingContext} implementations via {@link ServiceLoader}.
 * Uses a class token to select the correct implementation for the caller's protocol type, enabling
 * compile-time type safety instead of runtime `instanceof` dispatch.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ExternalApiTracingContextProvider {

	/**
	 * All discovered implementations, loaded once and cached.
	 */
	@SuppressWarnings("rawtypes")
	private static final List<ExternalApiTracingContext> IMPLEMENTATIONS;

	static {
		IMPLEMENTATIONS = ServiceLoader.load(ExternalApiTracingContext.class)
			.stream()
			.map(Provider::get)
			.toList();
	}

	/**
	 * Returns a type-safe {@link ExternalApiTracingContext} for the given context class.
	 * Selects the matching implementation by comparing the requested class token against
	 * each implementation's {@link ExternalApiTracingContext#contextType()}.
	 * Falls back to {@link DefaultExternalApiTracingContext} when no match is found.
	 *
	 * @param contextType the class of the context object (e.g. `HttpRequest.class`, `Metadata.class`)
	 * @param headerOptions header options to configure on the returned context
	 * @param <C> the context type
	 * @return a properly typed tracing context
	 */
	@Nonnull
	public static <C> ExternalApiTracingContext<C> getContext(
		@Nonnull Class<C> contextType,
		@Nonnull HeaderOptions headerOptions
	) {
		for (ExternalApiTracingContext<?> implementation : IMPLEMENTATIONS) {
			if (contextType.equals(implementation.contextType())) {
				implementation.configureHeaders(headerOptions);
				@SuppressWarnings("unchecked")
				final ExternalApiTracingContext<C> typed = (ExternalApiTracingContext<C>) implementation;
				return typed;
			}
		}
		// no matching implementation found — return the NOOP default
		@SuppressWarnings("unchecked")
		final ExternalApiTracingContext<C> fallback = (ExternalApiTracingContext<C>) DefaultExternalApiTracingContext.INSTANCE;
		return fallback;
	}
}
