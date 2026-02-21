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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
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
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mockStatic;

/**
 * Tests for the async tracing path in {@link JsonApiTracingContext#executeWithinBlockAsync}. Uses real OTel SDK
 * for span verification and {@link MockedStatic} for {@link OpenTelemetryTracerSetup} to inject the test tracer
 * provider.
 *
 * @author evitaDB
 */
@DisplayName("JsonApiTracingContext - async span lifecycle")
class JsonApiTracingContextAsyncTest {

	private InMemorySpanExporter spanExporter;
	private SdkTracerProvider tracerProvider;
	private OpenTelemetry openTelemetry;
	private Tracer tracer;

	@BeforeEach
	void setUp() {
		this.spanExporter = InMemorySpanExporter.create();
		this.tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(this.spanExporter))
			.build();
		this.openTelemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder()
			.setTracerProvider(this.tracerProvider)
			.build();
		this.tracer = this.tracerProvider.get("test");
	}

	@AfterEach
	void tearDown() {
		this.tracerProvider.shutdown();
	}

	@Test
	@DisplayName("returns HttpRequest.class as context type")
	void shouldReturnHttpRequestClassAsContextType() {
		final JsonApiTracingContext ctx = new JsonApiTracingContext(DefaultTracingContext.INSTANCE);
		assertEquals(HttpRequest.class, ctx.contextType());
	}

	@Nested
	@DisplayName("Async span lifecycle")
	class AsyncSpanLifecycle {

		@Test
		@DisplayName("span duration covers full async interval")
		void shouldCoverFullAsyncDurationInSpanTiming() throws Exception {
			try (
				MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class);
				MockedStatic<TracingContextProvider> tcp = mockStatic(TracingContextProvider.class)
			) {
				configureTracingMocks(otel, tcp);

				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/graphql"));
				final TracingContext tracingContext = TracingContextProvider.getContext();
				final JsonApiTracingContext ctx = new JsonApiTracingContext(tracingContext);

				// async lambda that completes after 200ms
				final CompletableFuture<String> delayed = new CompletableFuture<>();
				final CompletableFuture<String> result = ctx.executeWithinBlockAsync(
					"GraphQL", httpRequest, () -> delayed
				);

				Thread.sleep(200);
				delayed.complete("done");

				final String value = result.get();
				assertEquals("done", value);

				final List<SpanData> spans = spanExporter.getFinishedSpanItems();
				assertTrue(!spans.isEmpty(), "At least one span should be exported");

				final SpanData spanData = spans.get(spans.size() - 1);
				final long durationNanos = spanData.getEndEpochNanos() - spanData.getStartEpochNanos();
				assertTrue(
					durationNanos >= 200_000_000L,
					"Span duration (" + durationNanos + " ns) should be >= 200ms"
				);
			}
		}

		@Test
		@DisplayName("records error when future completes exceptionally")
		void shouldRecordErrorAndEndSpanWhenFutureFails() {
			try (
				MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class);
				MockedStatic<TracingContextProvider> tcp = mockStatic(TracingContextProvider.class)
			) {
				configureTracingMocks(otel, tcp);

				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/graphql"));
				final TracingContext tracingContext = TracingContextProvider.getContext();
				final JsonApiTracingContext ctx = new JsonApiTracingContext(tracingContext);

				final CompletableFuture<String> failing = new CompletableFuture<>();
				final RuntimeException error = new RuntimeException("async error");

				final CompletableFuture<String> result = ctx.executeWithinBlockAsync(
					"GraphQL", httpRequest, () -> failing
				);

				failing.completeExceptionally(error);

				assertTrue(result.isCompletedExceptionally());

				final List<SpanData> spans = spanExporter.getFinishedSpanItems();
				assertTrue(!spans.isEmpty());

				final SpanData spanData = spans.get(spans.size() - 1);
				assertEquals(StatusCode.ERROR, spanData.getStatus().getStatusCode());
				final boolean hasExceptionEvent = spanData.getEvents().stream()
					.map(EventData::getName)
					.anyMatch("exception"::equals);
				assertTrue(hasExceptionEvent);
			}
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandling {

		@Test
		@DisplayName("closes block when async lambda throws sync")
		void shouldCloseBlockWhenAsyncLambdaThrowsSync() {
			try (
				MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class);
				MockedStatic<TracingContextProvider> tcp = mockStatic(TracingContextProvider.class)
			) {
				configureTracingMocks(otel, tcp);

				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/graphql"));
				final TracingContext tracingContext = TracingContextProvider.getContext();
				final JsonApiTracingContext ctx = new JsonApiTracingContext(tracingContext);

				final RuntimeException syncError = new RuntimeException("sync setup error");

				try {
					ctx.executeWithinBlockAsync("GraphQL", httpRequest, () -> { throw syncError; });
					fail("Should have thrown RuntimeException");
				} catch (RuntimeException e) {
					assertEquals("sync setup error", e.getMessage());
				}

				// span should be ended with ERROR status
				final List<SpanData> spans = spanExporter.getFinishedSpanItems();
				assertTrue(!spans.isEmpty());

				final SpanData spanData = spans.get(spans.size() - 1);
				assertEquals(StatusCode.ERROR, spanData.getStatus().getStatusCode());
			}
		}
	}

	@Nested
	@DisplayName("Null guards")
	class NullGuards {

		@Test
		@DisplayName("throws with descriptive message when async lambda returns null")
		void shouldThrowWhenAsyncLambdaReturnsNull() {
			try (
				MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class);
				MockedStatic<TracingContextProvider> tcp = mockStatic(TracingContextProvider.class)
			) {
				configureTracingMocks(otel, tcp);

				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/graphql"));
				final TracingContext tracingContext = TracingContextProvider.getContext();
				final JsonApiTracingContext ctx = new JsonApiTracingContext(tracingContext);

				try {
					ctx.executeWithinBlockAsync("GraphQL", httpRequest, () -> null);
					fail("Should have thrown NullPointerException");
				} catch (NullPointerException e) {
					// The message must be descriptive (from Objects.requireNonNull),
					// not the empty message from calling .handle() on null
					assertTrue(
						e.getMessage() != null && e.getMessage().contains("non-null"),
						"NPE message should be descriptive but was: " + e.getMessage()
					);
				}

				// span should still be ended properly
				final List<SpanData> spans = spanExporter.getFinishedSpanItems();
				assertTrue(!spans.isEmpty(), "Span should be ended even when lambda returns null");
			}
		}
	}

	@Nested
	@DisplayName("Tracing disabled")
	class TracingDisabled {

		@Test
		@DisplayName("passes through result when tracing disabled")
		void shouldPassthroughWhenTracingDisabled() throws Exception {
			try (
				MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class)
			) {
				otel.when(OpenTelemetryTracerSetup::isTracingEnabled).thenReturn(false);

				// use default no-op TracingContext (singleton)
				final JsonApiTracingContext ctx = new JsonApiTracingContext(DefaultTracingContext.INSTANCE);

				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/graphql"));

				final CompletableFuture<String> result = ctx.executeWithinBlockAsync(
					"GraphQL", httpRequest, () -> CompletableFuture.completedFuture("result")
				);

				assertEquals("result", result.get());

				// no spans should be exported
				final List<SpanData> spans = spanExporter.getFinishedSpanItems();
				assertTrue(spans.isEmpty());
			}
		}
	}

	/**
	 * Configures both {@link OpenTelemetryTracerSetup} and {@link TracingContextProvider} mocked statics to use
	 * the real OTel SDK from this test's tracer provider.
	 *
	 * @param otelMock the mocked static for OTel setup
	 * @param tcpMock  the mocked static for tracing context
	 */
	private void configureTracingMocks(
		MockedStatic<OpenTelemetryTracerSetup> otelMock,
		MockedStatic<TracingContextProvider> tcpMock
	) {
		otelMock.when(OpenTelemetryTracerSetup::isTracingEnabled).thenReturn(true);
		otelMock.when(OpenTelemetryTracerSetup::getOpenTelemetry).thenReturn(this.openTelemetry);
		otelMock.when(OpenTelemetryTracerSetup::getTracer).thenReturn(this.tracer);

		// provide real ObservabilityTracingContext so that createAndActivateBlock creates real spans
		final ObservabilityTracingContext realTracingCtx = new ObservabilityTracingContext();
		tcpMock.when(TracingContextProvider::getContext).thenReturn(realTracingCtx);
	}
}
