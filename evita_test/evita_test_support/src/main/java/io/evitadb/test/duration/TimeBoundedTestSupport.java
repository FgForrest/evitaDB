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
import io.evitadb.utils.StringUtils;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * This interface provides support for tests that should be bounded by time query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface TimeBoundedTestSupport {

	/**
	 * Method allows running the test logic for specified amount of time.
	 */
	default <T> T runFor(@Nonnull GenerationalTestInput input, @Nonnull T initialState, @Nonnull BiFunction<Random, T, T> testLogic) {
		return runFor(input, 0, initialState, testLogic, null);
	}

	/**
	 * Method allows running the test logic for specified amount of time.
	 */
	default <T> T runFor(@Nonnull GenerationalTestInput input, int echoEachIterations, @Nonnull T initialState, @Nonnull BiFunction<Random, T, T> testLogic) {
		return runFor(input, echoEachIterations, initialState, testLogic, null);
	}

	/**
	 * Method allows running the test logic for specified amount of time.
	 */
	default <T> T runFor(@Nonnull GenerationalTestInput input, int echoEachIterations, @Nonnull T initialState, @Nonnull BiFunction<Random, T, T> testLogic, @Nullable BiConsumer<T, Throwable> onException) {
		return runBounded(
			input,
			input.intervalInMinutes() * 60_000L,
			input.intervalInMinutes() + " minutes",
			echoEachIterations, initialState, testLogic, onException
		);
	}

	/**
	 * Runs the test logic for a budget expressed in SECONDS rather than whole minutes, which is the smallest budget
	 * {@link GenerationalTestInput#intervalInMinutes()} can express.
	 *
	 * It exists for generative cases that must not occupy a full minute each — the warm-up half of the savepoint fuzz
	 * matrix being the one that motivated it, because that matrix doubles the method count of every scenario it covers
	 * and a full minute apiece would price a full sweep out of reach. Everything else is
	 * identical to {@link #runFor(GenerationalTestInput, int, Object, BiFunction, BiConsumer)}: the same seeded
	 * {@link Random}, the same progress echo, and the same seed enrichment of a failure so the run can be reproduced
	 * with `-Dtest.seed=...`.
	 *
	 * The seed still comes from `input`, so a failing warm-up generation reproduces exactly like a minute-bounded one.
	 *
	 * @param input              the generational input carrying the random seed
	 * @param durationInSeconds  how long to keep generating; at least one iteration always runs
	 * @param echoEachIterations print a dot every N iterations, or `0` to stay silent
	 * @param initialState       the state threaded through the generations
	 * @param testLogic          one generation, returning the next state
	 * @return the state left by the last generation
	 */
	default <T> T runForSeconds(
		@Nonnull GenerationalTestInput input,
		int durationInSeconds,
		int echoEachIterations,
		@Nonnull T initialState,
		@Nonnull BiFunction<Random, T, T> testLogic
	) {
		return runBounded(
			input, durationInSeconds * 1000L, durationInSeconds + " seconds",
			echoEachIterations, initialState, testLogic, null
		);
	}

	/**
	 * The shared generation loop behind {@link #runFor} and {@link #runForSeconds} — it differs only in how the budget
	 * was expressed. The loop is a `do/while`, so at least one generation always runs even with a zero budget.
	 *
	 * @param input             the generational input carrying the random seed
	 * @param budgetInMillis    the wall-clock budget for the whole run
	 * @param budgetDescription how the budget is phrased in the progress echo (e.g. `2 minutes`)
	 */
	private static <T> T runBounded(
		@Nonnull GenerationalTestInput input,
		long budgetInMillis,
		@Nonnull String budgetDescription,
		int echoEachIterations,
		@Nonnull T initialState,
		@Nonnull BiFunction<Random, T, T> testLogic,
		@Nullable BiConsumer<T, Throwable> onException
	) {
		final Random random = new Random(input.randomSeed());
		if (echoEachIterations > 0) {
			System.out.print(
				"\nTest will run for " + budgetDescription + " and prints dot per "
					+ StringUtils.formatCount(echoEachIterations) + " iterations.\nRandom seed used: "
					+ input.randomSeed() + "\n"
			);
		} else {
			System.out.print(
				"\nTest will run for " + budgetDescription + ".\nRandom seed used: " + input.randomSeed() + "\n"
			);
		}
		T state = initialState;
		int iteration = 0;
		try {
			int printed = 0;
			final long start = System.currentTimeMillis();
			do {
				if (echoEachIterations > 0 && iteration % echoEachIterations == 0 && printed % 80 == 0) {
					System.out.print("\n");
				}
				state = testLogic.apply(random, state);
				iteration++;
				if (echoEachIterations > 0 && iteration % echoEachIterations == 0) {
					System.out.print(".");
					System.out.flush();
					printed++;
				}
			} while (System.currentTimeMillis() - start < budgetInMillis);

			System.out.println(
				"\nFinished correctly after " + ((System.currentTimeMillis() - start) / 1000) +
					" seconds and executed " + StringUtils.formatCount(iteration) + " iterations."
			);

			return state;
		} catch (Throwable ex) {
			System.out.println("\nFailed after " + StringUtils.formatCount(iteration) + " iterations.");
			T finalState = state;
			Optional.ofNullable(onException)
				.ifPresent(it -> it.accept(finalState, ex));
			throw enrichWithSeed(ex, input.randomSeed());
		}
	}

	/**
	 * Wraps the thrown exception so its top-level message starts with the random seed used by the failing run.
	 * The seed is also embedded as a ready-to-paste `-Dtest.seed=...` hint, so the failure can be reproduced
	 * deterministically without having to dig through the captured stdout of the test process.
	 *
	 * The original exception is kept as the cause (so its full stack trace and details remain available), and
	 * `AssertionFailedError` is reconstructed via its 4-arg constructor so the expected/actual values stay
	 * intact for IDE diff views.
	 */
	@Nonnull
	private static RuntimeException enrichWithSeed(@Nonnull Throwable original, int seed) {
		final String prefix = "Generational test failed with seed " + seed
			+ " (reproduce with -Dtest.seed=" + seed + ")\n";
		final String originalMessage = original.getMessage() == null ? "" : original.getMessage();
		final Throwable enriched;
		if (original instanceof AssertionFailedError afe) {
			final AssertionFailedError wrapped = new AssertionFailedError(
				prefix + originalMessage,
				afe.isExpectedDefined() ? afe.getExpected().getValue() : null,
				afe.isActualDefined() ? afe.getActual().getValue() : null,
				afe
			);
			wrapped.setStackTrace(afe.getStackTrace());
			enriched = wrapped;
		} else if (original instanceof AssertionError ae) {
			final AssertionError wrapped = new AssertionError(prefix + originalMessage, ae);
			wrapped.setStackTrace(ae.getStackTrace());
			enriched = wrapped;
		} else {
			final RuntimeException wrapped = new RuntimeException(prefix + originalMessage, original);
			wrapped.setStackTrace(original.getStackTrace());
			enriched = wrapped;
		}
		return sneakyThrow(enriched);
	}

	/**
	 * Throws the given Throwable bypassing the compile-time checked-exception check. Returning
	 * `RuntimeException` makes the call site usable with `throw sneakyThrow(...)` to satisfy
	 * Java's reachability analysis, even though this method never actually returns.
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static <E extends Throwable> RuntimeException sneakyThrow(@Nonnull Throwable t) throws E {
		throw (E) t;
	}

}
