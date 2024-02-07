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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.trace.TracingContext;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	 * Initializes MDC with the given client ID and trace ID. It is used for logging purposes.
	 */
	private static void initMdc(@Nonnull String clientId, @Nonnull String traceId) {
		MDC.remove(MDC_CLIENT_ID_PROPERTY);
		MDC.put(MDC_CLIENT_ID_PROPERTY, clientId);

		MDC.remove(MDC_TRACE_ID_PROPERTY);
		MDC.put(MDC_TRACE_ID_PROPERTY, traceId);
	}

	private static void clearMdc() {
		MDC.remove(MDC_CLIENT_ID_PROPERTY);
		MDC.remove(MDC_TRACE_ID_PROPERTY);
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String taskName,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		executeWithinBlock(
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
		return executeWithinBlock(taskName, lambda, attributes, null);
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		executeWithinBlock(
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
		return executeWithinBlock(taskName, lambda, null, attributes);
	}

	/**
	 * Executes the given lambda function within a block.
	 * If tracing is enabled, it creates and manages a span for the execution of the lambda.
	 *
	 * @param taskName the name of the task or operation
	 * @param lambda the lambda function to execute
	 * @param attributes an array of SpanAttribute objects representing the attributes to set in the span (optional)
	 * @param attributeSupplier a supplier function that provides an array of SpanAttribute objects representing additional attributes to set in the span (optional)
	 * @param <T> the return type of the lambda function
	 * @return the result of the lambda function
	 */
	private static <T> T executeWithinBlock(
		@Nonnull String taskName,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute[] attributes,
		@Nullable Supplier<SpanAttribute[]> attributeSupplier
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return lambda.get();
		}

		// the context will contain `traceId` provided by the client, if the propagation has been orchestrated on his side
		final Context context = Context.current();
		// the additional scope is needed to make sure that the span is not closed before the lambda is executed
		// docs: https://opentelemetry.io/docs/languages/java/instrumentation/#context-propagation

		final Span span = OpenTelemetryTracerSetup.getTracer()
			.spanBuilder(taskName)
			.setSpanKind(SpanKind.SERVER)
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
	 * Sets the specified attributes in the given span.
	 *
	 * @param span       the span to set the attributes in
	 * @param attributes an array of SpanAttribute objects representing the attributes to set in the span
	 */
	private static void setAttributes(
		@Nonnull Span span,
		@Nonnull SpanAttribute[] attributes
	) {
		for (SpanAttribute attribute : attributes) {
			if (attribute.value() instanceof String string) {
				span.setAttribute(attribute.key(), string);
			} else if (attribute.value() instanceof Integer integer) {
				span.setAttribute(attribute.key(), integer);
			} else if (attribute.value() instanceof Long longValue) {
				span.setAttribute(attribute.key(), longValue);
			} else if (attribute.value() instanceof Double doubleValue) {
				span.setAttribute(attribute.key(), doubleValue);
			} else if (attribute.value() instanceof Boolean booleanValue) {
				span.setAttribute(attribute.key(), booleanValue);
			}
		}
	}
}
