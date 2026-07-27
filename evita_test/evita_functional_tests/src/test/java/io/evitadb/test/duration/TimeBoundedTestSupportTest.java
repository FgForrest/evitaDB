/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test.duration;

import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the driving contract of {@link TimeBoundedTestSupport#runFor}: a zero-minute interval
 * must execute the test body exactly once (so tests can be exercised without waiting a whole
 * minute), the accumulated state must be threaded back to the caller, the supplied {@link Random}
 * must be seeded deterministically from the input seed, and any failure must be re-thrown
 * enriched with a reproduce-with-seed hint while preserving the original cause and the
 * expected/actual diff values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(TASK)
@DisplayName("TimeBoundedTestSupport generational driver contract")
class TimeBoundedTestSupportTest {

	/**
	 * Anonymous implementation exercising the interface's default `runFor` methods. The interface
	 * declares only default methods, so an empty body is a complete, behaviour-carrying double.
	 */
	private final TimeBoundedTestSupport support = new TimeBoundedTestSupport() {
	};

	/**
	 * Builds an input that drives the do-while body exactly once: a zero-minute interval makes the
	 * loop condition false immediately after the first iteration, avoiding any real wall-clock wait.
	 *
	 * @param seed random seed forwarded to the {@link Random} handed to the test body
	 * @return single-iteration generational input carrying the given seed
	 */
	private static GenerationalTestInput singleIteration(int seed) {
		return new GenerationalTestInput(0, seed);
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(TASK)
	@DisplayName("Single-iteration execution")
	class SingleIterationExecution {

		@Test
		@DisplayName("runs the test body exactly once when the interval is zero minutes")
		void shouldRunSingleIterationWhenIntervalIsZero() {
			final AtomicInteger iterations = new AtomicInteger();

			support.runFor(singleIteration(1), "state", (random, state) -> {
				iterations.incrementAndGet();
				return state;
			});

			assertEquals(1, iterations.get());
		}

		@Test
		@DisplayName("returns the state accumulated by the single test-body invocation")
		void shouldReturnAccumulatedState() {
			final int result = support.runFor(
				singleIteration(7), 10, (random, state) -> state + 5
			);

			assertEquals(15, result);
		}

		@Test
		@DisplayName("seeds the random generator deterministically from the input seed")
		void shouldSeedRandomDeterministicallyFromInputSeed() {
			final int seed = 42;
			final List<Integer> drawn = new ArrayList<>(5);

			support.runFor(singleIteration(seed), "state", (random, state) -> {
				for (int i = 0; i < 5; i++) {
					drawn.add(random.nextInt());
				}
				return state;
			});

			final Random oracle = new Random(seed);
			final List<Integer> expected = new ArrayList<>(5);
			for (int i = 0; i < 5; i++) {
				expected.add(oracle.nextInt());
			}
			assertEquals(expected, drawn);
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(TASK)
	@DisplayName("Failure enrichment")
	class FailureEnrichment {

		@Test
		@DisplayName("enriches AssertionFailedError with the seed and keeps expected/actual values")
		void shouldEnrichAssertionFailedErrorWithSeedAndPreserveExpectedActual() {
			final int seed = 12345;
			final AssertionFailedError original = new AssertionFailedError("boom", "exp", "act");

			final AssertionFailedError thrown = assertThrows(
				AssertionFailedError.class,
				() -> support.runFor(singleIteration(seed), "state", (random, state) -> {
					throw original;
				})
			);

			assertTrue(thrown.getMessage().contains("seed " + seed));
			assertTrue(thrown.getMessage().contains("-Dtest.seed=" + seed));
			assertTrue(thrown.getMessage().contains("boom"));
			assertEquals("exp", thrown.getExpected().getValue());
			assertEquals("act", thrown.getActual().getValue());
			assertSame(original, thrown.getCause());
		}

		@Test
		@DisplayName("wraps a generic throwable with a seed hint while preserving the cause")
		void shouldWrapGenericThrowableWithSeedHint() {
			final int seed = 6789;
			final IllegalStateException original = new IllegalStateException("kaboom");

			final RuntimeException thrown = assertThrows(
				RuntimeException.class,
				() -> support.runFor(singleIteration(seed), "state", (random, state) -> {
					throw original;
				})
			);

			assertEquals(RuntimeException.class, thrown.getClass());
			assertTrue(thrown.getMessage().contains("seed " + seed));
			assertTrue(thrown.getMessage().contains("-Dtest.seed=" + seed));
			assertTrue(thrown.getMessage().contains("kaboom"));
			assertSame(original, thrown.getCause());
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(TASK)
	@DisplayName("On-exception callback")
	class OnExceptionCallback {

		@Test
		@DisplayName("invokes the on-exception callback with the final state and the original cause")
		void shouldInvokeOnExceptionCallbackWithFinalStateAndCause() {
			final AtomicReference<String> capturedState = new AtomicReference<>();
			final AtomicReference<Throwable> capturedCause = new AtomicReference<>();
			final IllegalStateException original = new IllegalStateException("fail");

			assertThrows(
				RuntimeException.class,
				() -> support.<String>runFor(
					singleIteration(99),
					0,
					"initial",
					(random, state) -> {
						throw original;
					},
					(finalState, cause) -> {
						capturedState.set(finalState);
						capturedCause.set(cause);
					}
				)
			);

			assertEquals("initial", capturedState.get());
			assertSame(original, capturedCause.get());
		}

		@Test
		@DisplayName("does not invoke the on-exception callback on a successful run")
		void shouldNotInvokeCallbackOnSuccess() {
			final AtomicBoolean invoked = new AtomicBoolean(false);

			final String result = support.<String>runFor(
				singleIteration(3),
				0,
				"state",
				(random, state) -> state,
				(finalState, cause) -> invoked.set(true)
			);

			assertEquals("state", result);
			assertFalse(invoked.get());
		}
	}
}
