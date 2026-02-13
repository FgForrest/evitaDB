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

import javax.annotation.Nonnull;

/**
 * Type-safe reference to the underlying tracing implementation's context object. This interface
 * allows evitaDB's tracing layer to remain agnostic to the specific tracing backend (e.g.,
 * OpenTelemetry's `Context`, Armeria's `RequestContext`) while still enabling context propagation
 * across threads.
 *
 * **Design Purpose:**
 * Different tracing implementations use different context representations:
 * - OpenTelemetry: `io.opentelemetry.context.Context`
 * - No-op: `Void` (no context exists)
 * - Custom backends: implementation-specific context types
 *
 * This interface provides a type-safe wrapper for these backend-specific context objects, allowing
 * them to be passed to {@link TracingContext#executeWithinBlockWithParentContext} methods for
 * cross-thread trace propagation.
 *
 * **Usage Pattern:**
 * 1. Capture context in originating thread: `TracingContextReference<?> ctx = tracingContext.getCurrentContext()`
 * 2. Pass context to worker thread (e.g., via executor submission)
 * 3. Restore context in worker thread: `tracingContext.executeWithinBlockWithParentContext(ctx, "Task", () -> ...)`
 *
 * **Implementations:**
 * - {@link DefaultTracingContextReference}: No-op reference with `Void` type (no context)
 * - `ObservabilityTracingContextReference`: OpenTelemetry `Context` wrapper in
 * `evita_external_api_observability` module
 *
 * **Thread-Safety:**
 * Implementations should be immutable and safe to pass across threads. The underlying context
 * object should also be thread-safe (OpenTelemetry's `Context` is immutable).
 *
 * @param <C> the type of the underlying context object (e.g., `io.opentelemetry.context.Context`,
 *            `Void`)
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface TracingContextReference<C> {

	/**
	 * Returns the runtime type of the underlying context object. Useful for type checking or
	 * reflection-based processing.
	 *
	 * @return the class of the context object (e.g., `Context.class`, `Void.class`)
	 */
	@Nonnull
	Class<C> getType();

	/**
	 * Returns the actual underlying context object from the tracing backend. For no-op
	 * implementations, this returns `null` (with type `Void`).
	 *
	 * @return the context object (e.g., OpenTelemetry's `Context`, or `null` for no-op)
	 */
	@Nonnull
	C getContext();

}
