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
		final Random random = new Random(input.randomSeed());
		if (echoEachIterations > 0) {
			System.out.print("\nTest will run for " + input.intervalInMinutes() + " minutes and prints dot per " + StringUtils.formatCount(echoEachIterations) + " iterations.\nRandom seed used: " + input.randomSeed() + "\n");
		} else {
			System.out.print("\nTest will run for " + input.intervalInMinutes() + " minutes.\nRandom seed used: " + input.randomSeed() + "\n");
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
			} while ((System.currentTimeMillis() - start) / 60_000 < input.intervalInMinutes());

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
