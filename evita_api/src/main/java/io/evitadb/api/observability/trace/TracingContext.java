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

package io.evitadb.api.observability.trace;


import io.evitadb.api.query.head.Label;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Unified abstraction for distributed tracing in evitaDB. This interface provides methods for
 * creating trace spans, propagating context across threads, and recording operation metadata
 * (trace IDs, client IDs, IP addresses, etc.) for observability and debugging purposes.
 *
 * **Design Purpose:**
 * evitaDB supports pluggable tracing backends via Java ServiceLoader. The actual implementation
 * (e.g., OpenTelemetry-based tracing, no-op tracing) is discovered at runtime via
 * {@link TracingContextProvider}. This design allows evitaDB to:
 * - Integrate with OpenTelemetry, Jaeger, Zipkin, or other tracing systems
 * - Run with zero tracing overhead when observability is disabled (via {@link DefaultTracingContext})
 * - Maintain context across asynchronous operations and thread boundaries
 *
 * **Key Concepts:**
 *
 * 1. **Trace Spans:** Logical units of work recorded in a distributed trace. Each span has a name,
 * start/end time, and optional attributes (key-value pairs).
 *
 * 2. **Context Propagation:** The trace context (trace ID, span ID) must be propagated across
 * threads, API boundaries, and asynchronous operations. This interface provides methods to
 * capture and restore context.
 *
 * 3. **MDC Integration:** Client metadata (trace ID, client ID, IP address, URI) is stored in
 * SLF4J's MDC (Mapped Diagnostic Context) to enable structured logging with trace correlation.
 *
 * **Usage Patterns:**
 *
 * **Automatic Span Management:**
 * ```java
 * tracingContext.executeWithinBlock("Operation Name", () -> {
 * // ... traced code
 * return result;
 * }, new SpanAttribute("key", "value"));
 * ```
 *
 * **Manual Span Management:**
 * ```java
 * try (TracingBlockReference span = tracingContext.createAndActivateBlock("Operation Name")) {
 * // ... traced code
 * if (error) span.setError(exception);
 * }
 * ```
 *
 * **Cross-Thread Context Propagation:**
 * ```java
 * TracingContextReference<?> ctx = tracingContext.getCurrentContext();
 * executor.submit(() ->
 * tracingContext.executeWithinBlockWithParentContext(ctx, "Async Task", () -> {
 * // ... runs in child span under parent context
 * })
 * );
 * ```
 *
 * **Implementations:**
 * - {@link DefaultTracingContext}: No-op implementation when tracing is disabled
 * - `ObservabilityTracingContext`: OpenTelemetry-backed implementation in
 * `evita_external_api_observability` module
 *
 * **Thread-Safety:**
 * Implementations must be thread-safe. Context is typically stored in ThreadLocal or
 * OpenTelemetry's Context API.
 *
 * **Related Annotations:**
 * - {@link Traced}: Marks methods as candidates for tracing
 * - {@link RepresentsQuery}: Marks read operations
 * - {@link RepresentsMutation}: Marks write operations
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public interface TracingContext {
	/**
	 * MDC key for the client identifier. Used in structured logging to correlate log entries with
	 * the client session that initiated the request.
	 */
	String MDC_CLIENT_ID_PROPERTY = "clientId";

	/**
	 * MDC key for the trace identifier. Populated from the active trace span's trace ID to enable
	 * log correlation with distributed traces.
	 */
	String MDC_TRACE_ID_PROPERTY = "traceId";

	/**
	 * MDC key for the client IP address. Stored for security auditing and traffic analysis.
	 */
	String MDC_CLIENT_IP_ADDRESS = "clientIp";

	/**
	 * MDC key for the client request URI. Records the API endpoint or GraphQL query path to
	 * correlate logs with specific operations.
	 */
	String MDC_CLIENT_URI = "clientUri";

	/**
	 * Thread-local storage for client-provided labels (custom metadata). Labels are set via
	 * {@link #executeWithClientContext} and remain available for the duration of the request.
	 * Used for custom tagging and filtering in observability systems.
	 */
	ThreadLocal<Label[]> CLIENT_LABELS = new ThreadLocal<>();

	/**
	 * Executes an operation with client metadata temporarily stored in MDC. This method sets the
	 * client IP address, request URI, and custom labels in the MDC for the duration of the
	 * operation, enabling structured logging and request correlation.
	 *
	 * **Use Case:**
	 * Called at API entry points (REST, gRPC, GraphQL handlers) to propagate client metadata
	 * through the request lifecycle. The metadata is automatically cleared after execution,
	 * preventing cross-request contamination.
	 *
	 * **MDC Keys Set:**
	 * - {@link #MDC_CLIENT_IP_ADDRESS}
	 * - {@link #MDC_CLIENT_URI}
	 * - {@link #CLIENT_LABELS} (ThreadLocal, not MDC)
	 *
	 * @param clientIpAddress the client's IP address (null allowed)
	 * @param clientUri       the request URI or endpoint path (null allowed)
	 * @param labels          custom client-provided labels for filtering/tagging (null allowed)
	 * @param runnable        the operation to execute with client context
	 * @param <T>             return type
	 * @return the result of invoking the supplier
	 */
	static <T> T executeWithClientContext(
		@Nullable String clientIpAddress,
		@Nullable String clientUri,
		@Nullable Label[] labels,
		@Nonnull Supplier<T> runnable
	) {
		MDC.put(MDC_CLIENT_IP_ADDRESS, clientIpAddress);
		MDC.put(MDC_CLIENT_URI, clientUri);
		CLIENT_LABELS.set(labels);
		try {
			return runnable.get();
		} finally {
			MDC.remove(MDC_CLIENT_IP_ADDRESS);
			MDC.remove(MDC_CLIENT_URI);
			CLIENT_LABELS.remove();
		}
	}

	/**
	 * Executes an operation with previously captured context restored in MDC. This method is used
	 * for cross-thread or asynchronous operations where the original request context must be
	 * propagated to a different execution context.
	 *
	 * **Use Case:**
	 * When spawning background tasks or processing work in thread pools, capture the context via
	 * {@link #captureContext()} in the originating thread, then restore it in the worker thread
	 * using this method.
	 *
	 * **MDC Keys Set:**
	 * - {@link #MDC_TRACE_ID_PROPERTY}
	 * - {@link #MDC_CLIENT_ID_PROPERTY}
	 * - {@link #MDC_CLIENT_IP_ADDRESS}
	 * - {@link #MDC_CLIENT_URI}
	 * - {@link #CLIENT_LABELS} (ThreadLocal)
	 *
	 * @param context  the captured context from {@link #captureContext()}
	 * @param runnable the operation to execute with restored context
	 * @param <T>      return type
	 * @return the result of invoking the supplier
	 */
	static <T> T executeWithClientContext(
		@Nonnull CapturedContext context,
		@Nonnull Supplier<T> runnable
	) {
		setContext(context);
		try {
			return runnable.get();
		} finally {
			clearContext();
		}
	}

	/**
	 * Captures the current request context (trace ID, client metadata, labels) for later restoration
	 * in a different thread or execution context. This is essential for maintaining observability
	 * across asynchronous boundaries.
	 *
	 * **Use Case:**
	 * Before submitting work to an executor or passing operations to background threads, capture
	 * the context and pass it along with the work. The worker thread can then restore the context
	 * via {@link #executeWithClientContext(CapturedContext, Supplier)}.
	 *
	 * **Captured Values:**
	 * - Trace ID from {@link #MDC_TRACE_ID_PROPERTY}
	 * - Client ID from {@link #MDC_CLIENT_ID_PROPERTY}
	 * - Client IP from {@link #MDC_CLIENT_IP_ADDRESS}
	 * - Client URI from {@link #MDC_CLIENT_URI}
	 * - Client labels from {@link #CLIENT_LABELS}
	 *
	 * @return a snapshot of the current context (contains null values if no context is set)
	 */
	@Nonnull
	static CapturedContext captureContext() {
		final String traceId = MDC.get(MDC_TRACE_ID_PROPERTY);
		final String clientId = MDC.get(MDC_CLIENT_ID_PROPERTY);
		final String clientIpAddress = MDC.get(MDC_CLIENT_IP_ADDRESS);
		final String clientUri = MDC.get(MDC_CLIENT_URI);
		final Label[] clientLabels = CLIENT_LABELS.get();
		if (traceId == null && clientId == null && clientIpAddress == null && clientUri == null && clientLabels == null) {
			return CapturedContext.EMPTY;
		}
		return new CapturedContext(traceId, clientId, clientIpAddress, clientUri, clientLabels);
	}

	/**
	 * Sets the captured tracing context into MDC and thread-local storage on the current thread.
	 * This is the inverse of {@link #clearContext()}.
	 *
	 * @param ctx the captured context to restore
	 */
	static void setContext(@Nonnull CapturedContext ctx) {
		MDC.put(MDC_TRACE_ID_PROPERTY, ctx.traceId());
		MDC.put(MDC_CLIENT_ID_PROPERTY, ctx.clientId());
		MDC.put(MDC_CLIENT_IP_ADDRESS, ctx.clientIpAddress());
		MDC.put(MDC_CLIENT_URI, ctx.clientUri());
		CLIENT_LABELS.set(ctx.clientLabels());
	}

	/**
	 * Clears all tracing context from MDC and thread-local storage on the current thread.
	 * This is the inverse of {@link #setContext(CapturedContext)}.
	 */
	static void clearContext() {
		MDC.remove(MDC_TRACE_ID_PROPERTY);
		MDC.remove(MDC_CLIENT_ID_PROPERTY);
		MDC.remove(MDC_CLIENT_IP_ADDRESS);
		MDC.remove(MDC_CLIENT_URI);
		CLIENT_LABELS.remove();
	}

	/**
	 * Returns the current trace ID from the active span. In OpenTelemetry implementations, this
	 * extracts the trace ID from the current span context.
	 *
	 * @return the trace ID if a span is active, empty otherwise
	 */
	@Nonnull
	Optional<String> getTraceId();

	/**
	 * Returns the current client ID associated with the active session or request.
	 *
	 * @return the client ID if available, empty otherwise
	 */
	@Nonnull
	Optional<String> getClientId();

	/**
	 * Returns the client IP address from MDC. Available when set via
	 * {@link #executeWithClientContext}.
	 *
	 * @return the client IP address if set in MDC, empty otherwise
	 */
	@Nonnull
	default Optional<String> getClientIpAddress() {
		return Optional.ofNullable(MDC.get(MDC_CLIENT_IP_ADDRESS));
	}

	/**
	 * Returns the client request URI from MDC. Available when set via
	 * {@link #executeWithClientContext}.
	 *
	 * @return the client URI if set in MDC, empty otherwise
	 */
	@Nonnull
	default Optional<String> getClientUri() {
		return Optional.ofNullable(MDC.get(MDC_CLIENT_URI));
	}

	/**
	 * Returns the client labels from ThreadLocal storage. Available when set via
	 * {@link #executeWithClientContext}.
	 *
	 * @return the client labels, or an empty array if not set
	 */
	@Nonnull
	default Label[] getClientLabels() {
		final Label[] labels = CLIENT_LABELS.get();
		return labels == null ? Label.EMPTY_ARRAY : labels;
	}

	/**
	 * Returns a reference to the currently active tracing context implementation. This reference
	 * can be passed to another thread or execution context to propagate the parent trace span.
	 *
	 * **Use Case:**
	 * Before executing work asynchronously, capture the current context reference and pass it to
	 * {@link #executeWithinBlockWithParentContext} methods to create child spans under the same
	 * parent trace.
	 *
	 * @return a reference to the underlying tracing context (never null, but may be a no-op
	 * instance)
	 */
	TracingContextReference<?> getCurrentContext();

	/**
	 * Creates and activates a new trace span with the specified name and attributes. The caller is
	 * responsible for closing the returned reference (typically via try-with-resources).
	 *
	 * **Use Case:**
	 * Use this method when the traced code spans multiple methods or requires manual span
	 * lifecycle control. For single-method tracing, prefer {@link #executeWithinBlock} variants.
	 *
	 * **Attributes Timing:**
	 * Attributes are set immediately when the span is created. Use this variant when attribute
	 * values are known upfront.
	 *
	 * @param taskName   the span name (e.g., "Entity Enrichment", "Query Planning")
	 * @param attributes optional key-value pairs to attach to the span
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(
		@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Creates and activates a new trace span with the specified name. Attributes are computed and
	 * set lazily when the span is closed, allowing attributes to depend on computed results.
	 *
	 * **Use Case:**
	 * Use this method when attribute values depend on the result of the traced operation (e.g.,
	 * result count, processing time, error conditions).
	 *
	 * **Attributes Timing:**
	 * The supplier is invoked when `close()` is called on the returned reference, allowing
	 * attributes to capture final state or computed metrics.
	 *
	 * @param taskName   the span name
	 * @param attributes supplier that produces attributes when the span closes (may return null)
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(
		@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Creates and activates a new trace span with the specified name and no attributes.
	 *
	 * @param taskName the span name
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(@Nonnull String taskName) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Executes a runnable within a new trace span, with attributes set immediately upon span
	 * creation. The span is automatically closed when the runnable completes (or throws).
	 *
	 * **Use Case:**
	 * Preferred method for tracing code blocks when attribute values are known upfront and the
	 * traced operation does not return a value.
	 *
	 * @param taskName   the span name
	 * @param runnable   the code to trace
	 * @param attributes optional key-value pairs to attach to the span
	 */
	void executeWithinBlock(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Executes a supplier within a new trace span, with attributes set immediately upon span
	 * creation. The span is automatically closed when the supplier completes (or throws).
	 *
	 * **Use Case:**
	 * Preferred method for tracing code blocks when attribute values are known upfront and the
	 * traced operation returns a value.
	 *
	 * @param taskName   the span name
	 * @param lambda     the code to trace
	 * @param attributes optional key-value pairs to attach to the span
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlock(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Executes a runnable within a new trace span, with attributes computed and set lazily when
	 * the span closes. This allows attributes to capture final state or computed metrics.
	 *
	 * **Use Case:**
	 * Use when attribute values depend on the result of the traced operation (e.g., result count,
	 * error conditions).
	 *
	 * @param taskName   the span name
	 * @param runnable   the code to trace
	 * @param attributes supplier invoked after runnable completes to produce attributes (may
	 *                   return null)
	 */
	void executeWithinBlock(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Executes a supplier within a new trace span, with attributes computed and set lazily when
	 * the span closes. This allows attributes to capture final state or computed metrics.
	 *
	 * **Use Case:**
	 * Use when attribute values depend on the result of the traced operation (e.g., result count,
	 * processing time).
	 *
	 * @param taskName   the span name
	 * @param lambda     the code to trace
	 * @param attributes supplier invoked after lambda completes to produce attributes (may return
	 *                   null)
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlock(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Executes a runnable within a new trace span with no attributes.
	 *
	 * @param taskName the span name
	 * @param runnable the code to trace
	 */
	void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Executes a supplier within a new trace span with no attributes.
	 *
	 * @param taskName the span name
	 * @param lambda   the code to trace
	 * @param <T>      return type
	 * @return the result of invoking the supplier
	 */
	default <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Creates and activates a new child trace span under the specified parent context. This method
	 * is used for cross-thread tracing where the parent span was created in a different thread.
	 *
	 * **Use Case:**
	 * When executing asynchronous work, capture the parent context via {@link #getCurrentContext()}
	 * in the originating thread, then use this method in the worker thread to create a child span
	 * under the same trace.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param attributes         optional key-value pairs to attach to the span
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName,
		@Nullable SpanAttribute... attributes
	) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Creates and activates a new child trace span under the specified parent context, with
	 * attributes computed lazily when the span closes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param attributes         supplier invoked when span closes to produce attributes
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Creates and activates a new child trace span under the specified parent context with no
	 * attributes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @return a reference to the active span (must be closed by caller)
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Executes a runnable within a new child trace span under the specified parent context. This
	 * method is used for cross-thread tracing where the parent span was created in a different
	 * thread.
	 *
	 * **Use Case:**
	 * When submitting work to an executor, capture the parent context via
	 * {@link #getCurrentContext()} and pass it to this method in the worker thread to maintain
	 * trace continuity.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param runnable           the code to trace
	 * @param attributes         optional key-value pairs to attach to the span
	 */
	void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	);

	/**
	 * Executes a supplier within a new child trace span under the specified parent context.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param lambda             the code to trace
	 * @param attributes         optional key-value pairs to attach to the span
	 * @param <T>                return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	);

	/**
	 * Executes a runnable within a new child trace span under the specified parent context, with
	 * attributes computed lazily when the span closes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param runnable           the code to trace
	 * @param attributes         supplier invoked after runnable completes to produce attributes
	 */
	void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable,
		@Nullable Supplier<SpanAttribute[]> attributes
	);

	/**
	 * Executes a supplier within a new child trace span under the specified parent context, with
	 * attributes computed lazily when the span closes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param lambda             the code to trace
	 * @param attributes         supplier invoked after lambda completes to produce attributes
	 * @param <T>                return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda,
		@Nullable Supplier<SpanAttribute[]> attributes
	);

	/**
	 * Executes a runnable within a new child trace span under the specified parent context with no
	 * attributes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param runnable           the code to trace
	 */
	void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Executes a supplier within a new child trace span under the specified parent context with no
	 * attributes.
	 *
	 * @param contextHolderToUse the parent context reference from {@link #getCurrentContext()}
	 * @param taskName           the child span name
	 * @param lambda             the code to trace
	 * @param <T>                return type
	 * @return the result of invoking the supplier
	 */
	default <T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Creates and activates a new child trace span only if a parent span is already active. If no
	 * parent span exists, returns `null` instead of creating a new root span.
	 *
	 * **Use Case:**
	 * Use this method for "optional" tracing — code that should be traced only if it's already
	 * part of a larger traced operation. This avoids creating orphaned root spans for internal
	 * operations.
	 *
	 * **Null Handling:**
	 * The returned reference may be `null`, so use with null-safe patterns or manual null checks
	 * instead of try-with-resources.
	 *
	 * @param taskName   the child span name
	 * @param attributes optional key-value pairs to attach to the span
	 * @return a reference to the active span, or `null` if no parent span exists
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return null;
	}

	/**
	 * Creates and activates a new child trace span only if a parent span is already active, with
	 * attributes computed lazily when the span closes.
	 *
	 * @param taskName   the child span name
	 * @param attributes supplier invoked when span closes to produce attributes
	 * @return a reference to the active span, or `null` if no parent span exists
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return null;
	}

	/**
	 * Creates and activates a new child trace span only if a parent span is already active, with
	 * no attributes.
	 *
	 * @param taskName the child span name
	 * @return a reference to the active span, or `null` if no parent span exists
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName) {
		return null;
	}

	/**
	 * Executes a runnable within a new child trace span only if a parent span is already active.
	 * If no parent span exists, the runnable executes without tracing.
	 *
	 * **Use Case:**
	 * Use this method for "optional" tracing — code that should be traced only if it's already
	 * part of a larger traced operation. This avoids creating orphaned root spans for internal
	 * operations.
	 *
	 * @param taskName   the child span name
	 * @param runnable   the code to trace (or execute untraced)
	 * @param attributes optional key-value pairs to attach to the span
	 */
	void executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Executes a supplier within a new child trace span only if a parent span is already active.
	 * If no parent span exists, the supplier executes without tracing.
	 *
	 * @param taskName   the child span name
	 * @param lambda     the code to trace (or execute untraced)
	 * @param attributes optional key-value pairs to attach to the span
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Executes a runnable within a new child trace span only if a parent span is already active,
	 * with attributes computed lazily when the span closes.
	 *
	 * @param taskName   the child span name
	 * @param runnable   the code to trace (or execute untraced)
	 * @param attributes supplier invoked after runnable completes to produce attributes (not
	 *                   invoked if no parent span exists)
	 */
	void executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Executes a supplier within a new child trace span only if a parent span is already active,
	 * with attributes computed lazily when the span closes.
	 *
	 * @param taskName   the child span name
	 * @param lambda     the code to trace (or execute untraced)
	 * @param attributes supplier invoked after lambda completes to produce attributes (not invoked
	 *                   if no parent span exists)
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	<T> T executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Executes a runnable within a new child trace span only if a parent span is already active,
	 * with no attributes.
	 *
	 * @param taskName the child span name
	 * @param runnable the code to trace (or execute untraced)
	 */
	void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Executes a supplier within a new child trace span only if a parent span is already active,
	 * with no attributes.
	 *
	 * @param taskName the child span name
	 * @param lambda   the code to trace (or execute untraced)
	 * @param <T>      return type
	 * @return the result of invoking the supplier
	 */
	default <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Represents a key-value attribute to attach to a trace span. Attributes provide additional
	 * metadata for filtering, analysis, and debugging in observability backends (e.g.,
	 * OpenTelemetry, Jaeger).
	 *
	 * **Supported Value Types:**
	 * - Primitives: `String`, `Integer`, `Long`, `Double`, `Boolean`
	 * - Other types are converted to strings via `toString()`
	 * - `null` values are stored as-is (not converted to empty string)
	 *
	 * **Naming Conventions:**
	 * Use dot-separated namespaces for keys (e.g., `db.operation`, `entity.type`, `query.result_count`)
	 * to maintain consistency with OpenTelemetry semantic conventions.
	 *
	 * @param key   the attribute key (e.g., "entity.type", "result_count")
	 * @param value the attribute value (primitives, strings, or objects convertible to string)
	 */
	record SpanAttribute(
		@Nonnull String key,
		@Nullable Object value
	) {
		/**
		 * Empty array constant to avoid allocations when no attributes are needed.
		 */
		public static final SpanAttribute[] EMPTY_ARRAY = new SpanAttribute[0];
	}

	/**
	 * Snapshot of the current request context (trace ID, client metadata, labels) for cross-thread
	 * propagation. This record is created by {@link #captureContext()} and can be restored via
	 * {@link #executeWithClientContext(CapturedContext, Supplier)}.
	 *
	 * **Use Case:**
	 * When spawning background tasks or processing work in thread pools, capture the context in
	 * the originating thread and restore it in the worker thread to maintain log correlation and
	 * observability.
	 *
	 * **Thread-Safety:**
	 * This record is immutable and safe to pass across threads.
	 *
	 * @param traceId         the trace identifier from active span (may be null)
	 * @param clientId        the client identifier from session (may be null)
	 * @param clientIpAddress the client IP address (may be null)
	 * @param clientUri       the request URI or endpoint path (may be null)
	 * @param clientLabels    custom client-provided labels (may be null)
	 */
	record CapturedContext(
		@Nullable String traceId,
		@Nullable String clientId,
		@Nullable String clientIpAddress,
		@Nullable String clientUri,
		@Nullable Label[] clientLabels
	) {
		/**
		 * Sentinel instance representing an empty context (all fields null).
		 * Used to avoid allocating a new record when no tracing context is active.
		 */
		public static final CapturedContext EMPTY = new CapturedContext(
			null, null, null, null, null
		);

		/**
		 * Returns true if all context fields are null — i.e., no tracing context was active when captured.
		 */
		public boolean isEmpty() {
			return this.traceId == null &&
				this.clientId == null &&
				this.clientIpAddress == null &&
				this.clientUri == null &&
				this.clientLabels == null;
		}
	}

}
