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

package io.evitadb.index.list;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-running randomised operation sequences verifying consistency between {@link TransactionalList}
 * and a plain ArrayList reference implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2018
 */
@DisplayName("TransactionalList (generational proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalListTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "TransactionalList should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 20;
		final List<Integer> initialState =
			generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(
				new StringBuilder(),
				initialState
			),
			(random, testState) -> {
				final List<Integer> referenceList = new ArrayList<>(testState.initialState);
				final TransactionalList<Integer> transactionalList =
					new TransactionalList<>(testState.initialState());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("\nSTART: ")
					.append(transactionalList.stream().map(Object::toString).collect(Collectors.joining(",")))
					.append("\n");

				assertStateAfterCommit(
					transactionalList,
					original -> {

						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalList.size();
							assertEquals(referenceList.size(), length);
							final int operation = random.nextInt(3);
							if ((operation == 0 || length < 10) && length < 120) {
								if (random.nextBoolean()) {
									// insert new item at the end
									final Integer newRecId = random.nextInt(initialCount * 2);
									transactionalList.add(newRecId);
									referenceList.add(newRecId);
									codeBuffer.append("+").append(newRecId);
								} else if (length > 0) {
									// insert new item in the middle
									final int addIndex = random.nextInt(length - 1);
									final Integer newRecId = random.nextInt(initialCount * 2);
									transactionalList.add(addIndex, newRecId);
									referenceList.add(addIndex, newRecId);
									codeBuffer.append("++(").append(addIndex).append(")").append(newRecId);
								}
							} else if (operation == 1) {
								if (random.nextBoolean()) {
									// remove existing item by index
									final int removeIndex = random.nextInt(length);
									codeBuffer.append("-").append(removeIndex);
									transactionalList.remove(removeIndex);
									referenceList.remove(removeIndex);
								} else {
									// remove existing item by value
									final Integer removedRecId = transactionalList.get(random.nextInt(length));
									transactionalList.remove(removedRecId);
									referenceList.remove(removedRecId);
									codeBuffer.append("--").append(removedRecId);
								}
							} else {
								// update existing item by index
								final int updateIndex = random.nextInt(length);
								final Integer updatedValue = random.nextInt(initialCount * 2);
								codeBuffer.append("!").append(updateIndex);
								transactionalList.set(updateIndex, updatedValue);
								referenceList.set(updateIndex, updatedValue);
							}
						}
						codeBuffer.append("\n");
					},
					(original, committed) ->
						assertListContains(committed, referenceList.stream().mapToInt(it -> it).toArray())
				);

				return new TestState(
					new StringBuilder(),
					referenceList
				);
			}
		);
	}

	@Nonnull
	private List<Integer> generateRandomInitialArray(@Nonnull Random rnd, int count) {
		final List<Integer> initialArray = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int recId = rnd.nextInt(count * 2);
			initialArray.add(recId);
		}
		return initialArray;
	}

	private void assertListContains(@Nonnull List<Integer> list, int... recordIds) {
		final String errorMessage = "\nExpected: " + Arrays.toString(recordIds) +
			"\nActual:   [" + list.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]";

		assertEquals(recordIds.length, list.size(), errorMessage);
		assertArrayEquals(
			Arrays.stream(recordIds).boxed().toArray(Integer[]::new),
			list.toArray(new Integer[0]),
			errorMessage
		);

		if (recordIds.length == 0) {
			assertTrue(list.isEmpty(), errorMessage);
		} else {
			assertFalse(list.isEmpty(), errorMessage);
		}

		for (int i = 0; i < recordIds.length; i++) {
			final int recordId = recordIds[i];
			assertEquals(Integer.valueOf(recordId), list.get(i), errorMessage);
		}

		for (int recordId : recordIds) {
			assertTrue(list.contains(recordId), errorMessage);
		}

		int index = 0;
		final ListIterator<Integer> it = list.listIterator();
		while (it.hasNext()) {
			final Integer nextRecord = it.next();
			assertEquals(recordIds[index++], nextRecord, errorMessage);
		}
		assertEquals(recordIds.length, index, errorMessage);

		while (it.hasPrevious()) {
			final Integer prevRecord = it.previous();
			assertEquals(recordIds[--index], prevRecord, errorMessage);
		}
		assertEquals(0, index, errorMessage);
	}

	private record TestState(
		StringBuilder code,
		List<Integer> initialState
	) {}
}
