/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Provider for fetching registered and used {@link ExternalApiTracingContext} implementation that are fetched via {@link ServiceLoader}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ExternalApiTracingContextProvider {
	/**
	 * Fetches the {@link TracingContext} implementation.
	 */
	@Nonnull
	public static <T> ExternalApiTracingContext<T> getContext(@Nonnull HeaderOptions headerOptions) {
		//noinspection unchecked
		return (ExternalApiTracingContext<T>) loadContext(headerOptions);
	}

	/**
	 * Loads the implementation of the ExternalApiTracingContext interface using the ServiceLoader mechanism.
	 * If there is only one implementation found, it returns that implementation. If there are multiple implementations
	 * found, it throws an ExternalApiInternalError. If no implementation is found, it returns an instance of
	 * DefaultExternalApiTracingContext.
	 *
	 * @return the loaded ExternalApiTracingContext implementation
	 * @throws ExternalApiInternalError if multiple implementations of ExternalApiTracingContext are found
	 */
	@Nonnull
	private static ExternalApiTracingContext<?> loadContext(@Nonnull HeaderOptions headerOptions) {
		//noinspection rawtypes
		final List<ExternalApiTracingContext> collectedContexts = ServiceLoader.load(ExternalApiTracingContext.class)
			.stream()
			.map(Provider::get)
			.peek(it -> it.configureHeaders(headerOptions))
			.toList();
		if (collectedContexts.size() > 1) {
			throw new ExternalApiInternalError("There are multiple registered implementations of ExternalApiTracingContext.");
		}
		if (collectedContexts.size() == 1) {
			return collectedContexts.stream().findFirst().get();
		}
		return DefaultExternalApiTracingContext.INSTANCE;
	}
}
