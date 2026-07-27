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

package io.evitadb.index.reference;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generational randomized proof test for {@link TransactionalReference}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Transactional reference (generational randomized proof)")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class LongRunningTransactionalReferenceTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications")
	@ParameterizedTest(name = "TransactionalReference should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final AtomicReference<Boolean> nextBooleanToCompare = new AtomicReference<>();

		runFor(
			input,
			50_000,
			new TestState(false),
			(random, testState) -> {
				final TransactionalReference<Boolean> transactionalBoolean =
					new TransactionalReference<>(testState.initialState());
				final AtomicReference<Boolean> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalBoolean,
					original -> {
						// seed the expected value with the reference's initial state, so that a
						// transaction performing zero operations still compares against the correct
						// current value instead of a stale (or null) value from a previous iteration
						nextBooleanToCompare.set(testState.initialState());

						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							if (random.nextBoolean()) {
								transactionalBoolean.set(true);
								nextBooleanToCompare.set(true);
							} else {
								transactionalBoolean.set(false);
								nextBooleanToCompare.set(false);
							}
						}

						assertEquals(
							nextBooleanToCompare.get(),
							transactionalBoolean.get()
						);
					},
					(original, committed) -> {
						assertEquals(
							nextBooleanToCompare.get(),
							committed.orElse(null)
						);
						committedResult.set(committed.orElse(null));
					}
				);

				return new TestState(
					committedResult.get()
				);
			}
		);
	}

	/**
	 * Holds the state carried between generational test iterations.
	 */
	private record TestState(
		boolean initialState
	) {}

}
