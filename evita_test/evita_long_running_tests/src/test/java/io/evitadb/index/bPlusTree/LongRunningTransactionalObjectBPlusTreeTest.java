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
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link TransactionalObjectBPlusTree}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TransactionalObjectBPlusTree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalObjectBPlusTreeTest implements TimeBoundedTestSupport {
	/**
	 * Block sizes drawn per generation in the transactional axis; all odd (internal-node constraint) and small enough
	 * to force frequent splits and merges.
	 */
	private static final int[] BLOCK_SIZES = {3, 5, 7};

	@ParameterizedTest(
		name = "TransactionalObjectBPlusTreeTest should survive generational randomized test applying "
			+ "modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized insert/delete operations")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final TreeTuple testTree = prepareRandomTree(16, 7, 7, 3, 42, limitElements);
		final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
		final int[] initialArray = testTree.plainArray();
		verifyTreeConsistency(theTree, initialArray);

		runFor(
			input, 1000, new TestState(new StringBuilder(), initialArray, true),
			(random, testState) -> {
				final int[] startArray = testState.initialArray();
				final int[] endArray;
				int key = -1;
				final boolean delete =
					(startArray.length > 0 && random.nextInt(3) == 0)
						|| (testState.limitReached() && startArray.length > limitElements / 2);
				final boolean useUpsert = !delete && random.nextBoolean();

				try {
					if (delete) {
						final int index = random.nextInt(startArray.length);
						key = startArray[index];
						endArray = ArrayUtils.removeIntFromOrderedArray(key, startArray);
						theTree.delete(key);
					} else {
						key = random.nextInt(limitElements * 2);
						endArray = ArrayUtils.insertIntIntoOrderedArray(key, startArray);
						if (useUpsert) {
							// value-preserving upsert exercises the insert-or-update public path (may split the leaf)
							final int upsertKey = key;
							theTree.upsert(upsertKey, existing -> "Value" + upsertKey);
						} else {
							theTree.insert(key, "Value" + key);
						}
					}

					verifyTreeConsistency(theTree, endArray);

					return new TestState(
						testState.code().append(delete ? "D:" : useUpsert ? "U:" : "I:").append(key),
						endArray,
						testState.limitReached()
							? endArray.length > limitElements / 2
							: endArray.length >= limitElements
					);
				} catch (Exception ex) {
					fail(
						"Failed to " + (delete ? "delete" : useUpsert ? "upsert" : "insert") + " key " + key
							+ " with initial state: " + theTree,
						ex
					);
					throw ex;
				}
			}
		);
	}

	@ParameterizedTest(
		name = "TransactionalObjectBPlusTreeTest should survive generational randomized test under a reverse "
			+ "comparator"
	)
	@Tag(SLOW)
	@Tag(COMPARATOR)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized insert/delete operations under a reverse comparator")
	void generationalProofTestWithReverseComparator(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final Comparator<Integer> reverse = Comparator.reverseOrder();
		final TransactionalObjectBPlusTree<Integer, String> theTree = new TransactionalObjectBPlusTree<>(
			16, 7, 7, 3, Integer.class, String.class, reverse
		);
		// reference double ordered by the very same comparator (RULE-T3)
		final TreeMap<Integer, String> reference = new TreeMap<>(reverse);

		final Random seedRandom = new Random(42);
		do {
			final int i = seedRandom.nextInt(limitElements << 1);
			theTree.insert(i, "Value" + i);
			reference.put(i, "Value" + i);
		} while (reference.size() < limitElements);
		verifyComparatorTreeConsistency(theTree, reference);

		runFor(
			input, 1000, new ComparatorTestState(new StringBuilder(), true),
			(random, testState) -> {
				int key = -1;
				final boolean delete =
					(!reference.isEmpty() && random.nextInt(3) == 0)
						|| (testState.limitReached() && reference.size() > limitElements / 2);

				try {
					if (delete) {
						final Integer[] keys = reference.keySet().toArray(new Integer[0]);
						key = keys[random.nextInt(keys.length)];
						theTree.delete(key);
						reference.remove(key);
					} else {
						key = random.nextInt(limitElements * 2);
						theTree.insert(key, "Value" + key);
						reference.put(key, "Value" + key);
					}

					verifyComparatorTreeConsistency(theTree, reference);

					return new ComparatorTestState(
						testState.code().append(delete ? "D:" : "I:").append(key),
						testState.limitReached()
							? reference.size() > limitElements / 2
							: reference.size() >= limitElements
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

	@ParameterizedTest(
		name = "TransactionalObjectBPlusTreeTest should survive transactional generational insert/upsert/delete churn"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized transactional insert/upsert/delete churn swept cleanly on commit")
	void generationalTransactionalProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalObjectBPlusTreeTest (transactional) seed: " + seed);

		final Random seedRandom = new Random(seed);
		final TreeSet<Integer> initialReference = new TreeSet<>();
		do {
			initialReference.add(seedRandom.nextInt(limitElements << 1));
		} while (initialReference.size() < limitElements);

		runFor(
			input, 1000, new TestState(new StringBuilder(512), toArray(initialReference), true),
			(random, testState) -> {
				// draw a small block size per generation so every generation is a high-split-density tree
				final int blockSize = BLOCK_SIZES[random.nextInt(BLOCK_SIZES.length)];
				final int[] startKeys = testState.initialArray();
				final TransactionalObjectBPlusTree<Integer, String> tree = buildTree(startKeys, blockSize);
				verifyTreeConsistency(tree, startKeys);

				// oracle mutated in lockstep with the transaction; verified against the committed tree
				final TreeSet<Integer> oracle = new TreeSet<>();
				for (final int key : startKeys) {
					oracle.add(key);
				}
				final AtomicReference<int[]> committedKeys = new AtomicReference<>();
				final StringBuilder code = testState.code();
				code.setLength(0);

				try {
					assertStateAfterCommit(
						tree,
						original -> {
							final int operations = 1 + random.nextInt(6);
							for (int op = 0; op < operations; op++) {
								final boolean delete =
									(!oracle.isEmpty() && random.nextInt(3) == 0)
										|| (testState.limitReached() && oracle.size() > limitElements / 2);
								if (delete) {
									final int key = pickRandomKey(oracle, random);
									original.delete(key);
									oracle.remove(key);
									code.append("D:").append(key).append(' ');
								} else {
									final int key = random.nextInt(limitElements << 1);
									if (random.nextBoolean()) {
										original.insert(key, "Value" + key);
										code.append("I:").append(key).append(' ');
									} else {
										// value-preserving upsert keeps the oracle exact on both insert and update
										original.upsert(key, existing -> "Value" + key);
										code.append("U:").append(key).append(' ');
									}
									oracle.add(key);
								}
							}
						},
						(original, committed) -> {
							final int[] expectedKeys = toArray(oracle);
							verifyTreeConsistency(committed, expectedKeys);
							committedKeys.set(expectedKeys);
						}
					);
				} catch (Exception ex) {
					fail(
						"Generation failed for seed " + seed + " blockSize=" + blockSize
							+ " with operations [" + code + "]",
						ex
					);
					throw ex;
				}

				final int[] nextKeys = committedKeys.get();
				return new TestState(
					testState.code(),
					nextKeys,
					testState.limitReached()
						? nextKeys.length > limitElements / 2
						: nextKeys.length >= limitElements
				);
			}
		);
	}

	/**
	 * Builds a fresh object tree of the given block size holding one deterministic value per key from the given
	 * ascending key set.
	 *
	 * @param keys      the ascending distinct keys
	 * @param blockSize the leaf and internal node block size
	 * @return a tree seeded with `"Value" + key` per key
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, String> buildTree(@Nonnull int[] keys, int blockSize) {
		final TransactionalObjectBPlusTree<Integer, String> tree = new TransactionalObjectBPlusTree<>(
			blockSize, 1, blockSize, 1, Integer.class, String.class
		);
		for (final int key : keys) {
			tree.insert(key, "Value" + key);
		}
		return tree;
	}

	/**
	 * Picks a random key present in the reference double.
	 *
	 * @param reference the reference double
	 * @param random    the randomizer
	 * @return a key that currently exists in the reference
	 */
	private static int pickRandomKey(@Nonnull TreeSet<Integer> reference, @Nonnull Random random) {
		final int index = random.nextInt(reference.size());
		final Iterator<Integer> it = reference.iterator();
		int key = 0;
		for (int i = 0; i <= index; i++) {
			key = it.next();
		}
		return key;
	}

	/**
	 * Converts an ascending set of keys into a sorted primitive array.
	 *
	 * @param set the ascending key set
	 * @return the ascending keys
	 */
	@Nonnull
	private static int[] toArray(@Nonnull TreeSet<Integer> set) {
		final int[] array = new int[set.size()];
		int index = 0;
		for (final Integer value : set) {
			array[index++] = value;
		}
		return array;
	}

	/**
	 * Verifies that the comparator-ordered tree is internally consistent and that its forward key iteration matches
	 * the comparator-ordered reference map exactly.
	 *
	 * @param tree      the tree under test
	 * @param reference the reference {@link TreeMap} ordered by the same comparator
	 */
	private static void verifyComparatorTreeConsistency(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree,
		@Nonnull TreeMap<Integer, String> reference
	) {
		final ConsistencyReport consistencyReport = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		assertEquals(reference.size(), tree.size());
		final Iterator<Integer> treeKeys = tree.keyIterator();
		final Iterator<Integer> referenceKeys = reference.keySet().iterator();
		while (referenceKeys.hasNext()) {
			if (!treeKeys.hasNext()) {
				fail("Tree iterator exhausted before reference!");
			}
			assertEquals(referenceKeys.next(), treeKeys.next());
		}
		if (treeKeys.hasNext()) {
			fail("Tree iterator has more keys than reference!");
		}
	}

	private static void verifyTreeConsistency(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree, @Nonnull int... expectedArray
	) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	private static void verifyForwardValueIterator(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray
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
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray
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
		int valueBlockSize, int minValueBlockSize, int internalNodeSize, int minInternalNodeSize,
		long seed, int totalElements
	) {
		final Random random = new Random(seed);
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, Integer.class, String.class
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
		@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree,
		@Nonnull int[] plainArray
	) {
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] initialArray,
		boolean limitReached
	) {
	}

	private record ComparatorTestState(
		@Nonnull StringBuilder code,
		boolean limitReached
	) {
	}
}
