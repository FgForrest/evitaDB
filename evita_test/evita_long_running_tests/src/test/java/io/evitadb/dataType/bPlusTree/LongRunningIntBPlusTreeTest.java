/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link IntBPlusTree}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Int B+ tree (generational proof)")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class LongRunningIntBPlusTreeTest implements TimeBoundedTestSupport {

	@ParameterizedTest(
		name = "IntBPlusTreeTest should survive "
			+ "generational randomized test applying "
			+ "modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName(
		"survives randomized insert/delete operations"
	)
	void generationalProofTest(
		GenerationalTestInput input
	) {
		final int limitElements = 1000;
		final TreeTuple testTree = prepareRandomTree(
			16, 7, 7, 3, 42, limitElements
		);
		final IntBPlusTree<String> theTree =
			testTree.bPlusTree();
		final int[] initialArray = testTree.plainArray();
		verifyTreeConsistency(theTree, initialArray);

		runFor(
			input,
			1000,
			new TestState(
				new StringBuilder(),
				initialArray,
				true
			),
			(random, testState) -> {
				final int[] startArray =
					testState.initialArray();
				final int[] endArray;
				int key = -1;
				final boolean delete =
					(startArray.length > 0
						&& random.nextInt(3) == 0)
					|| (testState.limitReached()
						&& startArray.length
							> limitElements / 2);

				try {
					if (delete) {
						final int index = random.nextInt(
							startArray.length
						);
						key = startArray[index];
						endArray =
							ArrayUtils
								.removeIntFromOrderedArray(
									key, startArray
								);
						theTree.delete(key);
					} else {
						key = random.nextInt(
							limitElements * 2
						);
						endArray =
							ArrayUtils
								.insertIntIntoOrderedArray(
									key, startArray
								);
						theTree.insert(
							key, "Value" + key
						);
					}

					verifyTreeConsistency(
						theTree, endArray
					);

					return new TestState(
						testState.code()
							.append(
								delete ? "D:" : "I:"
							)
							.append(key),
						endArray,
						testState.limitReached()
							? endArray.length
								> limitElements / 2
							: endArray.length
								>= limitElements
					);
				} catch (Exception ex) {
					fail(
						"Failed to "
							+ (delete
								? "delete"
								: "insert")
							+ " key " + key
							+ " with initial state: "
							+ theTree,
						ex
					);
					throw ex;
				}
			}
		);
	}

	private static void verifyTreeConsistency(
		@Nonnull IntBPlusTree<String> bPlusTree, @Nonnull int... expectedArray
	) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	private static void verifyForwardValueIterator(
		@Nonnull IntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = 0;
		final Iterator<String> it = tree.valueIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
		}
		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	private static void verifyReverseValueIterator(
		@Nonnull IntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = expectedArray.length;
		final Iterator<String> it = tree.valueReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.next();
		}
		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	@Nonnull
	private static TreeTuple prepareRandomTree(
		int valueBlockSize, int minValueBlockSize, int internalNodeSize, int minInternalNodeSize,
		long seed, int totalElements
	) {
		final Random random = new Random(seed);
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, String.class
		);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements * 2);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertIntIntoOrderedArray(i, plainArray);
		} while (plainArray.length < totalElements);
		return new TreeTuple(bPlusTree, plainArray);
	}

	private record TreeTuple(
		@Nonnull IntBPlusTree<String> bPlusTree,
		@Nonnull int[] plainArray
	) {
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] initialArray,
		boolean limitReached
	) {
	}
}
