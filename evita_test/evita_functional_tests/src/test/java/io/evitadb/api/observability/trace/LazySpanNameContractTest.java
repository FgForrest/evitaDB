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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TracingContext - lazy span name")
@Tag(CONTRACT)
@Tag(OBSERVABILITY)
@Tag(REFERENCE)
class LazySpanNameContractTest {

	/**
	 * Stands in for a value whose `toString()` is too expensive to compute speculatively.
	 */
	private static final Object SUBJECT = new Object();

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
	@DisplayName("recording context composes the span name of a traced block and runs the work")
	void shouldComposeBlockSpanNameWhenContextRecordsSpans() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

		final String result = new ObservabilityTracingContext().executeWithinBlockIfParentContextAvailable(
			SUBJECT, namer, () -> "traced result", () -> SpanAttribute.EMPTY_ARRAY
		);

		assertEquals(
			1, invocations.get(),
			"a span-recording context must apply the namer exactly once per traced block"
		);
		assertEquals("traced result", result, "the traced work must run and its result be returned");
	}

	@Test
	@DisplayName("recording context does compose the span name")
	void shouldComposeSpanNameWhenContextRecordsSpans() {
		final AtomicInteger invocations = new AtomicInteger();
		final Function<Object, String> namer = subject -> {
			invocations.incrementAndGet();
			return "span - " + subject;
		};

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
