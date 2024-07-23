/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.bool;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link TransactionalBoolean}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class TransactionalBooleanTest implements TimeBoundedTestSupport {

	@Test
	void shouldCorrectlySetValuesAndRollback() {
		final TransactionalBoolean theBoolean = new TransactionalBoolean();

		assertStateAfterRollback(
			theBoolean,
			original -> {
				original.setToTrue();
				assertTrue(theBoolean.isTrue());
			},
			(original, committed) -> {
				assertNull(committed);
				assertFalse(original.isTrue());
			}
		);
	}

	@Test
	void shouldCorrectlySetValuesAndCommit() {
		final TransactionalBoolean theBoolean = new TransactionalBoolean();

		assertStateAfterCommit(
			theBoolean,
			original -> {
				original.setToTrue();
				assertTrue(theBoolean.isTrue());
			},
			(original, committed) -> {
				assertTrue(committed);
				assertFalse(original.isTrue());
			}
		);
	}

	@ParameterizedTest(name = "TransactionalBoolean should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final AtomicBoolean nextBooleanToCompare = new AtomicBoolean();

		runFor(
			input,
			10_000,
			new TestState(false),
			(random, testState) -> {
				final TransactionalBoolean transactionalBoolean = new TransactionalBoolean(testState.initialValue());

				assertStateAfterCommit(
					transactionalBoolean,
					original -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							if (random.nextBoolean()) {
								transactionalBoolean.setToTrue();
								nextBooleanToCompare.set(true);
							} else {
								transactionalBoolean.setToFalse();
								nextBooleanToCompare.set(false);
							}
						}

						assertEquals(nextBooleanToCompare.get(), transactionalBoolean.isTrue());
					},
					(original, committed) -> {
						assertEquals(nextBooleanToCompare.get(), committed);
					}
				);

				return new TestState(
					nextBooleanToCompare.get()
				);
			}
		);
	}

	private record TestState(
		boolean initialValue
	) {}

}
