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
import java.util.Map;
import java.util.Map.Entry;
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
	 * Executes a provided runnable within a trace block. The trace block is created using the passed task name and attributes.
	 * The execution of the lambda is traced and properly executed within the trace block.
	 *
	 * @param taskName    The name of the task to be traced.
	 * @param attributes  Optional attributes to be added to the trace.
	 * @param runnable    The runnable to be executed within the trace block.
	 */
	@Override
	public void executeWithinBlock(
		@Nonnull String taskName,
		@Nullable Map<String, Object> attributes,
		@Nonnull Runnable runnable
	) {
		executeWithinBlock(taskName, attributes, () -> {
			runnable.run();
			return null;
		});
	}

	/**
	 * Executes a provided supplier within a trace block. The trace block is created using the passed task name and attributes.
	 * The execution of the lambda is traced and properly executed within the trace block.
	 *
	 * @param <T>         The type of the return value
	 * @param taskName    The name of the task to be traced.
	 * @param attributes  Optional attributes to be added to the trace.
	 * @param lambda      The supplier to be executed within the trace block.
	 * @return The result of the lambda execution.
	 */
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String taskName,
		@Nullable Map<String, Object> attributes,
		@Nonnull Supplier<T> lambda
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
			for (Entry<String, Object> attribute : attributes.entrySet()) {
				span.setAttribute(attribute.getKey(), attribute.getValue().toString());
			}
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
		}
		finally {
			span.end();
			clearMdc();
		}
	}

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
}
