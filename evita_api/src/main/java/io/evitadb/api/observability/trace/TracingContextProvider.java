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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.observability.trace;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Service provider for discovering and loading {@link TracingContext} implementations using Java
 * {@link ServiceLoader}. This class enables pluggable tracing backends (e.g., OpenTelemetry,
 * no-op) without compile-time dependencies.
 *
 * **Design Purpose:**
 * evitaDB's tracing layer is designed to be optional and pluggable. The actual implementation
 * (OpenTelemetry-backed tracing, no-op fallback) is discovered at startup via ServiceLoader. This
 * allows:
 * - Running with zero tracing overhead when observability module is not present
 * - Swapping tracing implementations without code changes (e.g., Jaeger → Zipkin)
 * - Embedding evitaDB without forcing observability dependencies on users
 *
 * **ServiceLoader Discovery:**
 * At class initialization, this provider scans for implementations of {@link TracingContext} via
 * ServiceLoader. If exactly one implementation is found, it is used. If multiple implementations
 * are found, an error is thrown. If no implementations are found, {@link DefaultTracingContext}
 * (no-op) is used.
 *
 * **Implementations:**
 * - {@link DefaultTracingContext}: Built-in no-op fallback (always available)
 * - `ObservabilityTracingContext`: OpenTelemetry-backed implementation in
 * `evita_external_api_observability` module (discovered via ServiceLoader)
 *
 * **Usage:**
 * Call {@link #getContext()} to retrieve the active tracing context. The same instance is
 * returned for all calls (singleton pattern).
 *
 * **ServiceLoader Configuration:**
 * To register a custom implementation, create a file:
 * ```
 * META-INF/services/io.evitadb.api.observability.trace.TracingContext
 * ```
 * containing the fully-qualified class name of the implementation.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class TracingContextProvider {
	/**
	 * Cached singleton instance of the discovered {@link TracingContext} implementation. This
	 * field is initialized once during class loading and never changes.
	 */
	private static final TracingContext TRACING_CONTEXT;

	static {
		// Discover all TracingContext implementations via ServiceLoader
		final List<TracingContext> collectedContexts = ServiceLoader.load(TracingContext.class)
			.stream()
			.map(Provider::get)
			.toList();

		// Validate that at most one implementation is registered
		if (collectedContexts.size() > 1) {
			throw new GenericEvitaInternalError("There are multiple registered implementations of TracingContext.");
		}

		// Use discovered implementation, or fall back to no-op default
		if (collectedContexts.size() == 1) {
			TRACING_CONTEXT = collectedContexts.stream().findFirst().get();
		} else {
			TRACING_CONTEXT = DefaultTracingContext.INSTANCE;
		}
	}

	/**
	 * Returns the singleton {@link TracingContext} implementation discovered via ServiceLoader.
	 * The same instance is returned for all calls.
	 *
	 * **Behavior:**
	 * - If an implementation was found via ServiceLoader → returns that implementation
	 * - If no implementation was found → returns {@link DefaultTracingContext#INSTANCE} (no-op)
	 * - If multiple implementations were found → throws {@link GenericEvitaInternalError} at
	 * class initialization
	 *
	 * @return the active tracing context (never null)
	 */
	@Nonnull
	public static TracingContext getContext() {
		return loadContext();
	}

	/**
	 * Internal method to access the cached {@link TracingContext} instance. Exists for clarity
	 * but simply returns the static field.
	 *
	 * @return the loaded {@link TracingContext} implementation
	 */
	@Nonnull
	private static TracingContext loadContext() {
		return TRACING_CONTEXT;
	}
}
