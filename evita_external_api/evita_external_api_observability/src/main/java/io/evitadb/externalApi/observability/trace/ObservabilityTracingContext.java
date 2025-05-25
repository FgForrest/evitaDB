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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.observability.trace.DefaultTracingBlockReference;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextReference;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tracing context implementation for observability. It uses OpenTelemetry to create spans and propagate the context.
 * It also adds the client ID to the span attributes. The client ID is extracted from the context.
 * Executed call is wrapped in a span that is properly sent to the OpenTelemetry collector via tracing exporter.
 * All exceptions are caught and recorded in the span as well. In this place, client and trace IDs are set within
 * the MDC.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ObservabilityTracingContext implements TracingContext {
	/**
	 * Contains TRUE only if the context was already opened by `executeWithinBlock` like methods.
	 */
	private final ThreadLocal<Boolean> parentContextAvailable = new ThreadLocal<>();

	/**
	 * Initializes MDC with the given client ID and trace ID. It is used for logging purposes.
	 */
	private static void initMdc(@Nonnull String clientId, @Nonnull String traceId) {
		MDC.put(MDC_CLIENT_ID_PROPERTY, clientId);
		MDC.put(MDC_TRACE_ID_PROPERTY, traceId);
	}

	/**
	 * Clears MDC from the client ID and trace ID.
	 */
	protected static void clearMdc() {
		MDC.remove(MDC_CLIENT_ID_PROPERTY);
		MDC.remove(MDC_TRACE_ID_PROPERTY);
	}

	/**
	 * Sets the specified attributes in the given span.
	 *
	 * @param span       the span to set the attributes in
	 * @param attributes an array of SpanAttribute objects representing the attributes to set in the span
	 */
	protected static void setAttributes(
		@Nonnull Span span,
		@Nonnull SpanAttribute[] attributes
	) {
		for (SpanAttribute attribute : attributes) {
			final String key = attribute.key();
			final Object value = attribute.value();
			if (value instanceof String string) {
				span.setAttribute(key, string);
			} else if (value instanceof Integer integer) {
				span.setAttribute(key, integer);
			} else if (value instanceof Long longValue) {
				span.setAttribute(key, longValue);
			} else if (value instanceof Double doubleValue) {
				span.setAttribute(key, doubleValue);
			} else if (value instanceof Boolean booleanValue) {
				span.setAttribute(key, booleanValue);
			} else if (value != null) {
				span.setAttribute(key, value.toString());
			}
		}
	}

	@Override
	public TracingContextReference<?> getCurrentContext() {
		return new ObservabilityTracingContextReference(Context.current());
	}

	@Nonnull
	@Override
	public Optional<String> getClientId() {
		return Optional.ofNullable(Context.current().get(OpenTelemetryTracerSetup.CONTEXT_KEY));
	}

	@Nonnull
	public Optional<String> getTraceId() {
		final SpanContext spanContext = Span.current().getSpanContext();
		return spanContext != null && spanContext.isValid() ? Optional.of(spanContext.getTraceId()) : Optional.empty();
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlock(@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return createAndActivateBlockOpeningParentContext(taskName, attributes, null);
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlock(@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return createAndActivateBlockOpeningParentContext(taskName, null, attributes);
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlock(@Nonnull String taskName) {
		return createAndActivateBlockOpeningParentContext(taskName, null, null);
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String taskName,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		executeWithinBlockOpeningParentContext(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			attributes,
			null
		);
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		return executeWithinBlockOpeningParentContext(taskName, lambda, attributes, null);
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		executeWithinBlockOpeningParentContext(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			attributes
		);
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return executeWithinBlockOpeningParentContext(taskName, lambda, null, attributes);
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable) {
		executeWithinBlockOpeningParentContext(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			null
		);
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return executeWithinBlockOpeningParentContext(
			taskName,
			lambda,
			null,
			null
		);
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return createAndActivateBlockUsingCustomParentContext(contextReference, taskName, attributes, null);
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return createAndActivateBlockUsingCustomParentContext(contextReference, taskName, null, attributes);
	}

	@Nonnull
	@Override
	public TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName) {
		return createAndActivateBlockUsingCustomParentContext(contextReference, taskName, null, null);
	}

	@Override
	public void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference,
		@Nonnull String taskName,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			attributes,
			null
		);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference,
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		return executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			lambda,
			attributes,
			null
		);
	}

	@Override
	public void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			attributes
		);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			lambda,
			null,
			attributes
		);
	}

	@Override
	public void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable) {
		executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			null
		);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return executeWithinBlockUsingCustomParentContext(
			contextReference,
			taskName,
			lambda,
			null,
			null
		);
	}

	@Nullable
	@Override
	public TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return createAndActivateBlockInternal(taskName, attributes, null, null);
	}

	@Nullable
	@Override
	public TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return createAndActivateBlockInternal(taskName, null, attributes, null);
	}

	@Nullable
	@Override
	public TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName) {
		return createAndActivateBlockInternal(taskName, null, null, null);
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		executeWithinBlockInternal(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			attributes,
			null
		);
	}

	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return executeWithinBlockInternal(taskName, lambda, attributes, null);
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		executeWithinBlockInternal(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			attributes
		);
	}

	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return executeWithinBlockInternal(taskName, lambda, null, attributes);
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable) {
		executeWithinBlockInternal(
			taskName,
			() -> {
				runnable.run();
				return null;
			},
			null,
			null
		);
	}

	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return executeWithinBlockInternal(
			taskName,
			lambda,
			null,
			null
		);
	}

	/**
	 * Executes the given lambda function within a block and opens parent context block.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @param taskName          the name of the task or operation
	 * @param lambda            the lambda function to execute
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing
	 *                          additional attributes to set in the span (optional)
	 * @param <T>               the return type of the lambda function
	 * @return the result of the lambda function
	 */
	private <T> T executeWithinBlockOpeningParentContext(
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		boolean clear = false;
		try {
			final Boolean originalValue = this.parentContextAvailable.get();
			if (!Boolean.TRUE.equals(originalValue)) {
				this.parentContextAvailable.set(true);
				clear = true;
			}
			return executeWithinBlockInternal(
				taskName,
				lambda,
				attributes,
				attributeSupplier
			);
		} finally {
			if (clear) {
				this.parentContextAvailable.set(false);
			}
		}
	}

	/**
	 * Executes the given lambda function within a block within passed parent context. It records a span only when somebody already called
	 * the {@link #executeWithinBlockOpeningParentContext(String, Supplier, SpanAttribute[], Supplier)} or
	 * {@link #createAndActivateBlockOpeningParentContext(String, SpanAttribute[], Supplier)} method.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @parem contextReference  the parent context reference
	 * @param taskName          the name of the task or operation
	 * @param lambda            the lambda function to execute
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing additional attributes to set in the span (optional)
	 * @param <T>               the return type of the lambda function
	 * @return the result of the lambda function
	 */
	private <T> T executeWithinBlockUsingCustomParentContext(
		@Nonnull TracingContextReference<?> contextReference,
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		if (!(contextReference instanceof ObservabilityTracingContextReference)) {
			throw new EvitaInvalidUsageException("Unsupported context type `" + contextReference.getType().getName() + "`");
		}

		boolean clear = false;
		try {
			final Boolean originalValue = this.parentContextAvailable.get();
			if (!Boolean.TRUE.equals(originalValue)) {
				this.parentContextAvailable.set(true);
				clear = true;
			}
			try (Scope ignored = ((ObservabilityTracingContextReference) contextReference).getContext().makeCurrent()) {
				return executeWithinBlockInternal(
					taskName,
					lambda,
					attributes,
					attributeSupplier
				);
			}
		} finally {
			if (clear) {
				this.parentContextAvailable.set(false);
			}
		}
	}

	/**
	 * Executes the given lambda function within a block. It records a span only when somebody already called
	 * the {@link #executeWithinBlockOpeningParentContext(String, Supplier, SpanAttribute[], Supplier)} or
	 * {@link #createAndActivateBlockOpeningParentContext(String, SpanAttribute[], Supplier)} method.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @param taskName          the name of the task or operation
	 * @param lambda            the lambda function to execute
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing additional attributes to set in the span (optional)
	 * @param <T>               the return type of the lambda function
	 * @return the result of the lambda function
	 */
	private <T> T executeWithinBlockInternal(
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled() || !Boolean.TRUE.equals(this.parentContextAvailable.get())) {
			return lambda.get();
		}

		// the context will contain `traceId` provided by the client, if the propagation has been orchestrated on his side
		final Context context = Context.current();
		// the additional scope is needed to make sure that the span is not closed before the lambda is executed
		// docs: https://opentelemetry.io/docs/languages/java/instrumentation/#context-propagation

		final Span span = OpenTelemetryTracerSetup.getTracer()
			.spanBuilder(taskName)
			.setSpanKind(SpanKind.SERVER)
			.setParent(context)
			.startSpan();

		final String clientId = context.get(OpenTelemetryTracerSetup.CONTEXT_KEY);

		if (attributes != null) {
			setAttributes(span, attributes);
		}

		span.setAttribute(ExternalApiTracingContext.CLIENT_ID_CONTEXT_KEY_NAME, clientId);

		try (Scope ignored = span.makeCurrent()) {
			initMdc(clientId, span.getSpanContext().getTraceId());
			T result = lambda.get();
			span.setStatus(StatusCode.OK);
			return result;
		} catch (Exception e) {
			span.setStatus(StatusCode.ERROR);
			span.recordException(e);
			throw e;
		} finally {
			if (attributeSupplier != null) {
				final SpanAttribute[] finalAttributes = attributeSupplier.get();
				if (finalAttributes != null) {
					setAttributes(span, finalAttributes);
				}
			}

			span.end();
			clearMdc();
		}
	}

	/**
	 * Creates and activates a block and opens parent context block.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @param taskName          the name of the task or operation
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing
	 *                          additional attributes to set in the span (optional)
	 * @return the new block, must be manually closed after all client is executed
	 */
	@Nonnull
	private TracingBlockReference createAndActivateBlockOpeningParentContext(
		@Nonnull String taskName,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		boolean clear = false;
		final Boolean originalValue = this.parentContextAvailable.get();
		if (!Boolean.TRUE.equals(originalValue)) {
			this.parentContextAvailable.set(true);
			clear = true;
		}

		final boolean finalClear = clear;
		return createAndActivateBlockInternal(
			taskName,
			attributes,
			attributeSupplier,
			() -> {
				if (finalClear) {
					this.parentContextAvailable.set(false);
				}
			}
		);
	}

	/**
	 * Creates and activates block within passed parent context. It records a span only when somebody already called
	 * the {@link #executeWithinBlockOpeningParentContext(String, Supplier, SpanAttribute[], Supplier)} or
	 * {@link #createAndActivateBlockOpeningParentContext(String, SpanAttribute[], Supplier)} method.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @parem contextReference  the parent context reference
	 * @param taskName          the name of the task or operation
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing additional attributes to set in the span (optional)
	 * @return the new block, must be manually closed after all client is executed
	 */
	@Nonnull
	private TracingBlockReference createAndActivateBlockUsingCustomParentContext(
		@Nonnull TracingContextReference<?> contextReference,
		@Nonnull String taskName,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		if (!(contextReference instanceof ObservabilityTracingContextReference observabilityContextReference)) {
			throw new EvitaInvalidUsageException("Unsupported context type `" + contextReference.getType().getName() + "`");
		}

		boolean clear = false;
		final Boolean originalValue = this.parentContextAvailable.get();
		if (!Boolean.TRUE.equals(originalValue)) {
			this.parentContextAvailable.set(true);
			clear = true;
		}

		final boolean finalClear = clear;
		final Scope parentScope = observabilityContextReference.getContext().makeCurrent();
		return createAndActivateBlockInternal(
			taskName,
			attributes,
			attributeSupplier,
			() -> {
				parentScope.close();
				if (finalClear) {
					this.parentContextAvailable.set(false);
				}
			}
		);
	}

	/**
	 * Creates and activates block. It records a span only when somebody already called
	 * the {@link #executeWithinBlockOpeningParentContext(String, Supplier, SpanAttribute[], Supplier)} or
	 * {@link #createAndActivateBlockOpeningParentContext(String, SpanAttribute[], Supplier)} method.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @param taskName          the name of the task or operation
	 * @param attributes        an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing additional attributes to set in the span (optional)
	 * @return the new block, must be manually closed after all client is executed
	 */
	@Nullable
	private TracingBlockReference createAndActivateBlockInternal(@Nonnull String taskName,
	                                                             @Nullable SpanAttribute[] attributes,
	                                                             @Nullable Supplier<SpanAttribute[]> attributeSupplier,
	                                                             @Nullable Runnable closeCallback) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled() || !Boolean.TRUE.equals(this.parentContextAvailable.get())) {
			return new DefaultTracingBlockReference();
		}

		// the context will contain `traceId` provided by the client, if the propagation has been orchestrated on his side
		final Context context = Context.current();
		// the additional scope is needed to make sure that the span is not closed before the lambda is executed
		// docs: https://opentelemetry.io/docs/languages/java/instrumentation/#context-propagation

		final Span span = OpenTelemetryTracerSetup.getTracer()
			.spanBuilder(taskName)
			.setSpanKind(SpanKind.SERVER)
			.setParent(context)
			.startSpan();

		final String clientId = context.get(OpenTelemetryTracerSetup.CONTEXT_KEY);

		if (attributes != null) {
			setAttributes(span, attributes);
		}

		span.setAttribute(ExternalApiTracingContext.CLIENT_ID_CONTEXT_KEY_NAME, clientId);

		final Scope scope = span.makeCurrent();
		initMdc(clientId, span.getSpanContext().getTraceId());

		return new ObservabilityTracingBlockReference(span, scope, attributeSupplier, closeCallback);
	}
}
