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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link UniqueIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UniqueIndexTest implements TimeBoundedTestSupport {
	private final UniqueIndex tested = new UniqueIndex(Entities.PRODUCT, new AttributeKey("whatever"), String.class, new HashMap<>());

	@Test
	void shouldRegisterUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertNull(this.tested.getRecordIdByUniqueValue("B"));
	}

	@Test
	void shouldFailToRegisterDuplicateValues() {
		this.tested.registerUniqueKey("A", 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", 2));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredValue() {
		this.tested.registerUniqueKey("A", 1);
		assertEquals(1, this.tested.unregisterUniqueKey("A", 1));
		assertNull(this.tested.getRecordIdByUniqueValue("A"));
	}

	@Test
	void shouldFailToUnregisterUnknownValue() {
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", 1));
	}

	@Test
	void shouldRegisterAndPartialUnregisterValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertEquals(1, this.tested.getRecordIdByUniqueValue("B"));
		assertEquals(1, this.tested.getRecordIdByUniqueValue("C"));
		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, 1);
		assertEquals(1, this.tested.getRecordIdByUniqueValue("A"));
		assertNull(this.tested.getRecordIdByUniqueValue("B"));
		assertNull(this.tested.getRecordIdByUniqueValue("C"));
	}

	@ParameterizedTest(name = "UniqueIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(),
				1,
				new UniqueIndex(Entities.PRODUCT, new AttributeKey("code"), String.class)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final UniqueIndex uniqueIndex = new UniqueIndex(\"code\", String.class);\n")
					.append(mapToCompare.entrySet().stream().map(it -> "uniqueIndex.registerUniqueKey(\"" + it.getKey() + "\"," + it.getValue() + ");").collect(Collectors.joining("\n")));
				codeBuffer.append("\nOps:\n");
				final UniqueIndex transactionalUniqueIndex = testState.initialState();
				final AtomicReference<UniqueIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalUniqueIndex,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalUniqueIndex.size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final String newValue = Character.toString(65 + random.nextInt(28)) + "_" + ((testState.iteration() * 100) + i);
									int newRecId;
									do {
										newRecId = random.nextInt(initialCount * 2);
									} while (currentRecordSet.contains(newRecId));
									mapToCompare.put(newValue, newRecId);
									currentRecordSet.add(newRecId);

									codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(newValue).append("\", product, ").append(newRecId).append(");\n");
									transactionalUniqueIndex.registerUniqueKey(newValue, newRecId);
								} else {
									// remove existing item
									final Iterator<Entry<String, Integer>> it = mapToCompare.entrySet().iterator();
									Entry<String, Integer> valueToRemove = null;
									for (int j = 0; j < random.nextInt(length) + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.getValue());

									codeBuffer.append("uniqueIndex.unregisterUniqueKey(\"").append(valueToRemove.getKey()).append("\", product,").append(valueToRemove.getValue()).append(");\n");
									transactionalUniqueIndex.unregisterUniqueKey(valueToRemove.getKey(), valueToRemove.getValue());
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getRecordIds().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getRecordIds().getArray()) + "\n\n" +
								codeBuffer
						);

						committedResult.set(
							new UniqueIndex(
								committed.getEntityType(),
								committed.getAttributeKey(),
								committed.getType(),
								new HashMap<>(committed.getUniqueValueToRecordId()),
								committed.getRecordIds()
							)
						);
					}
				);
				return new TestState(
					new StringBuilder(),
					testState.iteration() + 1,
					committedResult.get()
				);
			}
		);
	}

	private record TestState(
		StringBuilder code,
		int iteration,
		UniqueIndex initialState
	) {
	}

}
