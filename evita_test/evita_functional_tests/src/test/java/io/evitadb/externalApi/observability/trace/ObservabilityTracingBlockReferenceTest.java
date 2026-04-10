/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ObservabilityTracingBlockReference} verifying
 * span lifecycle management using real OpenTelemetry SDK
 * components (no mocks). Each test creates real spans via
 * {@link SdkTracerProvider} and verifies exported
 * {@link SpanData} through {@link InMemorySpanExporter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName(
	"ObservabilityTracingBlockReference - OTel span lifecycle"
)
class ObservabilityTracingBlockReferenceTest {

	private InMemorySpanExporter spanExporter;
	private SdkTracerProvider tracerProvider;
	private Tracer tracer;

	@BeforeEach
	void setUp() {
		this.spanExporter = InMemorySpanExporter.create();
		this.tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(
				SimpleSpanProcessor.create(this.spanExporter)
			)
			.build();
		this.tracer = this.tracerProvider.get("test");
	}

	@AfterEach
	void tearDown() {
		this.tracerProvider.shutdown();
	}

	@Nested
	@DisplayName("Synchronous close")
	class SynchronousClose {

		@Test
		@DisplayName(
			"ends span with OK status on close()"
		)
		void shouldEndSpanWithStatusOkOnClose() {
			final Span span = tracer.spanBuilder("test-span")
				.startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			ref.close();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());
			assertEquals(
				StatusCode.OK,
				spans.get(0).getStatus().getStatusCode()
			);
		}

		@Test
		@DisplayName(
			"records error and ERROR status on close()"
		)
		void shouldRecordErrorOnClose() {
			final Span span = tracer.spanBuilder("test-span")
				.startSpan();
			final Scope scope = span.makeCurrent();
			final RuntimeException error =
				new RuntimeException("test error");

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			ref.setError(error);
			ref.close();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());

			final SpanData spanData = spans.get(0);
			assertEquals(
				StatusCode.ERROR,
				spanData.getStatus().getStatusCode()
			);
			// verify exception event was recorded
			final boolean hasExceptionEvent =
				spanData.getEvents().stream()
					.map(EventData::getName)
					.anyMatch("exception"::equals);
			assertTrue(
				hasExceptionEvent,
				"Span should have an exception event"
			);
		}
	}

	@Nested
	@DisplayName("Async pattern")
	class AsyncPattern {

		@Test
		@DisplayName(
			"span duration covers full async interval"
		)
		void shouldCoverFullAsyncDurationInSpanTiming()
			throws InterruptedException {

			final Span span = tracer.spanBuilder("async-span")
				.startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			// detach scope immediately (same thread)
			ref.detachScope();

			// simulate async work
			Thread.sleep(200);

			// end span after async work completes
			ref.end();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());

			final SpanData spanData = spans.get(0);
			final long durationNanos =
				spanData.getEndEpochNanos()
					- spanData.getStartEpochNanos();
			assertTrue(
				durationNanos >= 200_000_000L,
				"Span duration (" + durationNanos
					+ " ns) should be >= 200ms"
			);
		}

		@Test
		@DisplayName(
			"end() can be called from a different thread"
		)
		void shouldEndSpanFromDifferentThread()
			throws Exception {

			final Span span = tracer.spanBuilder("cross-thread")
				.startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			// detach scope on main thread
			ref.detachScope();

			// end span from another thread
			final ExecutorService executor =
				Executors.newSingleThreadExecutor();
			try {
				executor.submit(ref::end)
					.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdown();
			}

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());
			assertEquals(
				StatusCode.OK,
				spans.get(0).getStatus().getStatusCode()
			);
		}

		@Test
		@DisplayName(
			"records error when setError() before end()"
		)
		void shouldRecordErrorWhenSetErrorCalledBeforeEnd() {
			final Span span =
				tracer.spanBuilder("error-async").startSpan();
			final Scope scope = span.makeCurrent();
			final RuntimeException error =
				new RuntimeException("async failure");

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			ref.detachScope();
			ref.setError(error);
			ref.end();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());

			final SpanData spanData = spans.get(0);
			assertEquals(
				StatusCode.ERROR,
				spanData.getStatus().getStatusCode()
			);
			final boolean hasExceptionEvent =
				spanData.getEvents().stream()
					.map(EventData::getName)
					.anyMatch("exception"::equals);
			assertTrue(hasExceptionEvent);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCases {

		@Test
		@DisplayName(
			"handles null attributeSupplier gracefully"
		)
		void shouldHandleNullAttributeSupplier() {
			final Span span =
				tracer.spanBuilder("null-attrs").startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, null
				);

			// should not throw
			ref.close();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());
		}

		@Test
		@DisplayName(
			"handles null closeCallback gracefully"
		)
		void shouldHandleNullCloseCallback() {
			final Span span =
				tracer.spanBuilder("null-callback").startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, () -> new SpanAttribute[0], null
				);

			// should not throw
			ref.close();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());
		}

		@Test
		@DisplayName(
			"handles attributeSupplier returning null"
		)
		void shouldHandleAttributeSupplierReturningNull() {
			final Span span =
				tracer.spanBuilder("null-attrs-ret").startSpan();
			final Scope scope = span.makeCurrent();

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, () -> null, null
				);

			// should not throw -- null array is guarded
			ref.close();

			final List<SpanData> spans =
				spanExporter.getFinishedSpanItems();
			assertEquals(1, spans.size());
		}

		@Test
		@DisplayName(
			"invokes closeCallback on close()"
		)
		void shouldInvokeCloseCallback() {
			final Span span =
				tracer.spanBuilder("callback-test").startSpan();
			final Scope scope = span.makeCurrent();
			final AtomicBoolean callbackInvoked =
				new AtomicBoolean(false);

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span,
					scope,
					null,
					() -> callbackInvoked.set(true)
				);

			ref.close();

			assertTrue(
				callbackInvoked.get(),
				"Close callback should have been invoked"
			);
		}
	}

	@Nested
	@DisplayName("Idempotency")
	class Idempotency {

		@Test
		@DisplayName(
			"invokes close callback only once on multiple end() calls"
		)
		void shouldOnlyInvokeCloseCallbackOnceOnMultipleEndCalls() {
			final Span span =
				tracer.spanBuilder("idempotent-end").startSpan();
			final Scope scope = span.makeCurrent();
			final AtomicInteger counter = new AtomicInteger(0);

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, counter::incrementAndGet
				);

			// detach scope first, then call end() three times
			ref.detachScope();
			ref.end();
			ref.end();
			ref.end();

			assertEquals(
				1,
				counter.get(),
				"Close callback should be invoked exactly once"
			);
		}

		@Test
		@DisplayName(
			"invokes close callback only once on multiple close() calls"
		)
		void shouldOnlyInvokeCloseCallbackOnceOnMultipleCloseCalls() {
			final Span span =
				tracer.spanBuilder("idempotent-close").startSpan();
			final Scope scope = span.makeCurrent();
			final AtomicInteger counter = new AtomicInteger(0);

			final ObservabilityTracingBlockReference ref =
				new ObservabilityTracingBlockReference(
					span, scope, null, counter::incrementAndGet
				);

			ref.close();
			ref.close();
			ref.close();

			assertEquals(
				1,
				counter.get(),
				"Close callback should be invoked exactly once"
			);
		}
	}

	/**
	 * Creates a real span and scope from the test tracer.
	 *
	 * @param spanName the name for the span
	 * @return the created span (caller must manage scope)
	 */
	@Nonnull
	private Span createSpan(@Nonnull String spanName) {
		return this.tracer.spanBuilder(spanName).startSpan();
	}
}
