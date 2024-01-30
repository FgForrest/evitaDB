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

package io.evitadb.driver.observability.trace;

import io.evitadb.api.trace.TracingContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

/**
 * Client tracing context implementation for observability. It uses OpenTelemetry to create spans and propagate the context.
 * It also adds the client ID to the span attributes. The client ID is extracted from the context.
 * Executed call is wrapped in a span that is properly sent to the OpenTelemetry collector via tracing exporter.
 * All exceptions are caught and recorded in the span as well. It also exposes gRPC client interceptor instance for
 * the client to use to ensure tracing via OpenTelemetry instance.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class OpenTelemetryClientTracingContext implements TracingContext {
	private static final String CLIENT_ID = "client_id";
	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nullable Map<String, Object> attributes, @Nonnull Runnable runnable) {
		// the context will contain `traceId` provided by the client, if the propagation has been orchestrated on his side
		final Context context = Context.current();
		// the additional scope is needed to make sure that the span is not closed before the lambda is executed
		// docs: https://opentelemetry.io/docs/languages/java/instrumentation/#context-propagation
		try (Scope scope = context.makeCurrent()) {
			final Span span = OpenTelemetryClientTracerSetup.getTracer()
				.spanBuilder(taskName)
				.setSpanKind(SpanKind.CLIENT)
				.startSpan();

			final String clientId = context.get(ContextKey.named(CLIENT_ID));

			if (attributes != null) {
				for (Entry<String, Object> attribute : attributes.entrySet()) {
					span.setAttribute(attribute.getKey(), attribute.getValue().toString());
				}
			}
			span.setAttribute(CLIENT_ID, clientId);

			try (Scope ignored = span.makeCurrent()) {
				runnable.run();
				span.setStatus(StatusCode.OK);
			} catch (Exception e) {
				span.setStatus(StatusCode.ERROR);
				span.recordException(e);
				throw e;
			}
			finally {
				span.end();
			}
		}
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nullable Map<String, Object> attributes, @Nonnull Supplier<T> lambda) {
		// the context will contain `traceId` provided by the client, if the propagation has been orchestrated on his side
		final Context context = Context.current();
		// the additional scope is needed to make sure that the span is not closed before the lambda is executed
		// docs: https://opentelemetry.io/docs/languages/java/instrumentation/#context-propagation
		try (Scope scope = context.makeCurrent()) {
			final Span span = OpenTelemetryClientTracerSetup.getTracer()
				.spanBuilder(taskName)
				.setSpanKind(SpanKind.CLIENT)
				.startSpan();

			final String clientId = context.get(ContextKey.named(CLIENT_ID));

			if (attributes != null) {
				for (Entry<String, Object> attribute : attributes.entrySet()) {
					span.setAttribute(attribute.getKey(), attribute.getValue().toString());
				}
			}

			span.setAttribute(CLIENT_ID, clientId);

			try (Scope ignored = span.makeCurrent()) {
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
			}
		}
	}
}
