/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.externalApi.observability.trace.ObservabilityTracingContext;
import io.evitadb.externalApi.observability.trace.OpenTelemetryTracerSetup;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

/**
 * Pins the laziness contract of
 * {@link TracingContext#createAndActivateBlock(Object, Function, SpanAttribute...)}.
 *
 * The whole point of that overload is that a span name which is expensive to compose — such as
 * `"mutation - " + mutation`, where `Mutation.toString()` walks the entire mutation — is never
 * composed while no tracing backend is listening. That property is invisible in ordinary
 * behavioural tests: forgetting it costs no correctness, only throughput on a hot path. These
 * tests make a violation fail loudly instead, by handing the API a namer that records whether it
 * was called.
 *
 * Both implementations are pinned: the no-op {@link DefaultTracingContext} and the OpenTelemetry-backed
 * {@link ObservabilityTracingContext} that a standard server build wires through the `ServiceLoader` — the latter
 * must skip the namer while tracing is switched off, and apply it exactly once when spans are really recorded.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TracingContext - lazy span name")
@Tag(CONTRACT)
@Tag(OBSERVABILITY)
class LazySpanNameContractTest {

	/**
	 * Stands in for a value whose `toString()` is too expensive to compute speculatively.
	 */
	private static final Object SUBJECT = new Object();

	private SdkTracerProvider tracerProvider;
	private Tracer tracer;

	@BeforeEach
	void setUp() {
		this.tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
			.build();
		this.tracer = this.tracerProvider.get("test");
	}

	@AfterEach
	void tearDown() {
		this.tracerProvider.shutdown();
	}

	@Test
	@DisplayName("no-op context never composes the span name")
	void shouldNotComposeSpanNameWhenTracingIsDisabled() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		final TracingBlockReference blockReference = DefaultTracingContext.INSTANCE
			.createAndActivateBlock(SUBJECT, namer, SpanAttribute.EMPTY_ARRAY);
		blockReference.close();

		assertEquals(
			0, invocations.get(),
			"the null-object tracing context discards span names unread, so composing one is " +
				"pure waste — the namer must not be invoked"
		);
	}

	@Test
	@DisplayName("no-op context reuses the shared block reference")
	void shouldReuseSharedBlockReferenceWhenTracingIsDisabled() {
		final Function<Object, String> namer = subject -> "span - " + subject;

		final TracingBlockReference first = DefaultTracingContext.INSTANCE
			.createAndActivateBlock(SUBJECT, namer, SpanAttribute.EMPTY_ARRAY);
		final TracingBlockReference second = DefaultTracingContext.INSTANCE
			.createAndActivateBlock(SUBJECT, namer, SpanAttribute.EMPTY_ARRAY);

		assertSame(
			DefaultTracingBlockReference.INSTANCE, first,
			"the no-op block reference is stateless, so it must be shared rather than allocated"
		);
		assertSame(first, second, "every disabled-path call must yield the very same instance");
	}

	@Test
	@DisplayName("no-op context never composes the span name of a traced block")
	void shouldNotComposeBlockSpanNameWhenTracingIsDisabled() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		final String result = DefaultTracingContext.INSTANCE.executeWithinBlockIfParentContextAvailable(
			SUBJECT, namer, () -> "traced result", () -> SpanAttribute.EMPTY_ARRAY
		);

		assertEquals(
			0, invocations.get(),
			"the null-object tracing context discards span names unread, so composing one is " +
				"pure waste — the namer must not be invoked"
		);
		assertEquals(
			"traced result", result,
			"the traced work must still run and its result be returned — laziness applies to the " +
				"span name, never to the code being traced"
		);
	}

	@Test
	@DisplayName("observability context never composes the span name of a traced block while tracing is off")
	void shouldNotComposeBlockSpanNameWhenObservabilityTracingIsDisabled() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		// this is the implementation a standard server build actually wires through the ServiceLoader, so the
		// laziness has to hold here as well — otherwise the hot call sites keep paying for names nobody reads
		final String result = new ObservabilityTracingContext().executeWithinBlockIfParentContextAvailable(
			SUBJECT, namer, () -> "traced result", () -> SpanAttribute.EMPTY_ARRAY
		);

		assertEquals(
			0, invocations.get(),
			"no span is recorded while tracing is disabled, so the namer must not be invoked"
		);
		assertEquals(
			"traced result", result,
			"the traced work must still run and its result be returned — laziness applies to the " +
				"span name, never to the code being traced"
		);
	}

	@Test
	@DisplayName("observability context never composes the span name while tracing is off")
	void shouldNotComposeSpanNameWhenObservabilityTracingIsDisabled() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		final TracingBlockReference blockReference = new ObservabilityTracingContext()
			.createAndActivateBlock(SUBJECT, namer, SpanAttribute.EMPTY_ARRAY);
		blockReference.close();

		assertSame(
			DefaultTracingBlockReference.INSTANCE, blockReference,
			"a disabled tracer records nothing, so it must hand back the shared no-op block reference"
		);
		assertEquals(
			0, invocations.get(),
			"no span is recorded while tracing is disabled, so the namer must not be invoked"
		);
	}

	@Test
	@DisplayName("recording context composes the span name of a traced block and runs the work")
	void shouldComposeBlockSpanNameWhenContextRecordsSpans() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		try (MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class)) {
			otel.when(OpenTelemetryTracerSetup::isTracingEnabled).thenReturn(true);
			otel.when(OpenTelemetryTracerSetup::getTracer).thenReturn(this.tracer);

			// this flavour records a span only within an already opened parent context, so the outer block is what
			// makes the inner call reach the recording path at all
			final ObservabilityTracingContext tracingContext = new ObservabilityTracingContext();
			final String result = tracingContext.executeWithinBlock(
				"outer",
				() -> tracingContext.executeWithinBlockIfParentContextAvailable(
					SUBJECT, namer, () -> "traced result", () -> SpanAttribute.EMPTY_ARRAY
				)
			);

			assertEquals(
				1, invocations.get(),
				"a span-recording context must apply the namer exactly once per traced block"
			);
			assertEquals("traced result", result, "the traced work must run and its result be returned");
		}
	}

	@Test
	@DisplayName("recording context does compose the span name")
	void shouldComposeSpanNameWhenContextRecordsSpans() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		try (MockedStatic<OpenTelemetryTracerSetup> otel = mockStatic(OpenTelemetryTracerSetup.class)) {
			otel.when(OpenTelemetryTracerSetup::isTracingEnabled).thenReturn(true);
			otel.when(OpenTelemetryTracerSetup::getTracer).thenReturn(this.tracer);

			// this implementation is backed by a real tracer, so it must consume the name it was
			// promised — laziness must not silently degrade into "the span is never named"
			final TracingBlockReference blockReference = new ObservabilityTracingContext()
				.createAndActivateBlock(SUBJECT, namer, SpanAttribute.EMPTY_ARRAY);
			assertNotNull(blockReference);
			blockReference.close();

			assertEquals(
				1, invocations.get(),
				"a span-recording context must apply the namer exactly once per created block"
			);
		}
	}
}
