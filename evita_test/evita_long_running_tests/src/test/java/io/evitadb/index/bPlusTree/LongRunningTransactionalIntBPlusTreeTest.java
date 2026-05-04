/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index.bPlusTree;

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

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link TransactionalIntBPlusTree}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Transactional int B+ tree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalIntBPlusTreeTest implements TimeBoundedTestSupport {

	@ParameterizedTest(
		name = "TransactionalIntBPlusTreeTest should survive generational randomized test applying modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized insert/delete operations")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final TreeTuple testTree = prepareRandomTree(16, 7, 7, 3, 42, limitElements);
		final TransactionalIntBPlusTree<String> theTree = testTree.bPlusTree();
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
				final int[] startArray = testState.initialArray();
				final int[] endArray;
				int key = -1;
				final boolean delete =
					(startArray.length > 0 && random.nextInt(3) == 0)
						|| (testState.limitReached() && startArray.length > limitElements / 2);

				try {
					if (delete) {
						final int index = random.nextInt(startArray.length);
						key = startArray[index];
						endArray = ArrayUtils.removeIntFromOrderedArray(key, startArray);
						theTree.delete(key);
					} else {
						key = random.nextInt(limitElements << 1);
						endArray = ArrayUtils.insertIntIntoOrderedArray(key, startArray);
						theTree.insert(key, "Value" + key);
					}

					verifyTreeConsistency(theTree, endArray);

					return new TestState(
						testState.code().append(delete ? "D:" : "I:").append(key),
						endArray,
						testState.limitReached()
							? endArray.length > limitElements / 2
							: endArray.length >= limitElements
					);
				} catch (Exception ex) {
					fail(
						"Failed to " + (delete ? "delete" : "insert") + " key " + key
							+ " with initial state: " + theTree,
						ex
					);
					throw ex;
				}
			}
		);
	}

	private static void verifyTreeConsistency(
		@Nonnull TransactionalIntBPlusTree<String> bPlusTree, @Nonnull int... expectedArray
	) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	private static void verifyForwardValueIterator(
		@Nonnull TransactionalIntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = 0;
		final Iterator<String> it = tree.valueIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
			assertEquals(expectedArray[index - 1], reconstructedArray[index - 1]);
		}

		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	private static void verifyReverseValueIterator(
		@Nonnull TransactionalIntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = expectedArray.length;
		final Iterator<String> it = tree.valueReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.next();
			assertEquals(expectedArray[index], reconstructedArray[index]);
		}

		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	@Nonnull
	private static TreeTuple prepareRandomTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeSize,
		int minInternalNodeSize,
		long seed,
		int totalElements
	) {
		final Random random = new Random(seed);
		final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, String.class
		);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements << 1);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertIntIntoOrderedArray(i, plainArray);
		} while (plainArray.length < totalElements);

		return new TreeTuple(bPlusTree, plainArray);
	}

	private record TreeTuple(
		@Nonnull TransactionalIntBPlusTree<String> bPlusTree,
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
