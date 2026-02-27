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
 * Reference to an active trace span or block representing a logical unit of work. This interface
 * enables manual span lifecycle control in situations where automatic span management via
 * {@link TracingContext#executeWithinBlock} is not feasible (e.g., when the traced code spans
 * multiple methods or runs asynchronously).
 *
 * **Design Purpose:**
 * In many tracing scenarios, the code to be traced exists within a single lambda/closure, allowing
 * use of {@link TracingContext#executeWithinBlock}. However, for complex scenarios — such as:
 * - Asynchronous operations where span start and end occur in different threads
 * - Long-lived operations that span multiple method calls
 * - Error handling logic separated from the main execution path
 *
 * This interface provides explicit control over span lifecycle via `close()` and error recording
 * via `setError()`.
 *
 * **Usage Pattern:**
 * Typical usage follows try-with-resources pattern:
 * ```java
 * try (TracingBlockReference span = tracingContext.createAndActivateBlock("Operation Name")) {
 * // ... perform operation
 * if (error) {
 * span.setError(exception);
 * }
 * } // span automatically closed
 * ```
 *
 * **Implementations:**
 * - {@link DefaultTracingBlockReference}: No-op implementation used when tracing is disabled
 * - `ObservabilityTracingBlockReference`: OpenTelemetry-backed implementation that creates real
 * spans
 *
 * **Thread-Safety:**
 * Implementations should support calling `setError()` and `close()` from different threads, though
 * the caller is responsible for ensuring logical consistency (e.g., closing a span only once).
 *
 * **Error Handling:**
 * Calling `setError()` records the exception in the span but does NOT close the span — `close()`
 * must still be called (typically via try-with-resources).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface TracingBlockReference extends AutoCloseable {

	/**
	 * Records an exception in the span, marking it as failed. In OpenTelemetry implementations,
	 * this sets the span status to ERROR and records the exception as a span event.
	 *
	 * This method does NOT close the span — `close()` must still be called separately.
	 *
	 * @param error the exception that occurred during the traced operation
	 */
	void setError(@Nonnull Throwable error);

	/**
	 * Detaches the thread-local scope (and clears MDC) without ending the span. Must be called
	 * on the **same thread** that created this block. After this call, the span remains open for
	 * asynchronous completion via {@link #end()}.
	 *
	 * **Async Pattern:**
	 * ```java
	 * TracingBlockReference block = tracingContext.createAndActivateBlock("op");
	 * CompletableFuture<?> future = startAsyncWork();
	 * block.detachScope();  // same thread as createAndActivateBlock
	 * future.whenComplete((r, e) -> {
	 *     if (e != null) block.setError(e);
	 *     block.end();  // any thread
	 * });
	 * ```
	 */
	void detachScope();

	/**
	 * Ends the span, records final status and attributes, and invokes any close callbacks.
	 * Thread-safe — can be called from any thread. In async flows, call this after
	 * {@link #detachScope()} when the asynchronous operation completes.
	 *
	 * For synchronous use, prefer {@link #close()} which combines both operations.
	 */
	void end();

	/**
	 * Ends the trace span and releases any associated resources. In OpenTelemetry implementations,
	 * this marks the span end time and exports it to the configured trace backend.
	 *
	 * Equivalent to calling {@link #detachScope()} followed by {@link #end()}.
	 *
	 * Safe to call multiple times (subsequent calls are no-ops). Calling `close()` does NOT throw
	 * exceptions, making it safe for use in try-with-resources blocks.
	 */
	@Override
	void close();
}
