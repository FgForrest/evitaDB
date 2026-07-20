/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

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
 * Generational randomized proof test for {@link TransactionalIntToLongBPlusTree} - the primitive-`int` key,
 * primitive-`long` value B+ tree. The value is deterministically derived from the key ({@code key * 10}), so a plain
 * ascending key set is a sufficient reference double and a value-preserving `upsert` keeps that reference exact whether
 * the key was absent (insert + possible split) or already present.
 *
 * The class exposes both coverage axes the family needs:
 * - the **WARM_UP** (non-transactional / bulk-load) axis churns random insert / upsert / delete operations directly on
 *   one long-lived bare tree, asserting `getConsistencyReport()` == CONSISTENT and the exact ascending content after
 *   every structural change;
 * - the **ALIVE** (transactional) axis rebuilds a fresh tree from the previous generation's committed contents, applies
 *   a random batch of insert / upsert / delete inside a single transaction and commits via
 *   {@link io.evitadb.utils.AssertionUtils#assertStateAfterCommit} - which runs the transactional-layer sweep on every
 *   commit - then validates the committed tree against a `TreeSet<Integer>` reference double. Each generation draws a
 *   small block size from `{3, 5, 7}` so leaves split and merge densely.
 *
 * All three public mutation paths that drive split / merge (insert, upsert, delete) are exercised. The run is
 * time-bounded; the seed is printed on failure and the per-generation operation log is included in the failure message.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Transactional int-to-long B+ tree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalIntToLongBPlusTreeTest implements TimeBoundedTestSupport {
	private static final int LIMIT_ELEMENTS = 1000;
	/**
	 * Block sizes drawn per generation in the transactional axis; all odd (internal-node constraint) and small enough
	 * to force frequent splits and merges.
	 */
	private static final int[] BLOCK_SIZES = {3, 5, 7};

	/**
	 * Derives the deterministic value stored for a key.
	 *
	 * @param key the key
	 * @return the value associated with the key (`key * 10`)
	 */
	private static long valueOf(int key) {
		return key * 10L;
	}

	/**
	 * Builds a fresh int-to-long tree of the given block size holding one value per key from the given ascending key set.
	 *
	 * @param keys      the ascending distinct keys
	 * @param blockSize the leaf and internal node block size
	 * @return a tree seeded with a deterministic value per key
	 */
	@Nonnull
	private static TransactionalIntToLongBPlusTree buildTree(@Nonnull int[] keys, int blockSize) {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(blockSize, 1, blockSize, 1);
		for (final int key : keys) {
			tree.insert(key, valueOf(key));
		}
		return tree;
	}

	/**
	 * Verifies the tree's internal consistency report and that both the forward and reverse value iterators agree with
	 * the deterministic values of the expected ascending key array.
	 *
	 * @param tree         the tree to verify
	 * @param expectedKeys the expected ascending key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalIntToLongBPlusTree tree, @Nonnull int... expectedKeys
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		assertEquals(expectedKeys.length, tree.size(), "Size mismatch between tree and reference!");

		final long[] expectedValues = new long[expectedKeys.length];
		for (int i = 0; i < expectedKeys.length; i++) {
			expectedValues[i] = valueOf(expectedKeys[i]);
		}

		// forward value iteration
		final long[] forwardValues = new long[expectedValues.length];
		int index = 0;
		final PrimitiveIterator.OfLong forward = tree.valueIterator();
		while (forward.hasNext()) {
			forwardValues[index++] = forward.nextLong();
		}
		assertEquals(expectedValues.length, index, "Forward iterator produced a different element count");
		assertArrayEquals(expectedValues, forwardValues, "Forward value iterator mismatch");
		assertThrows(NoSuchElementException.class, forward::next, "Forward iterator should be exhausted");

		// reverse value iteration
		final long[] reverseValues = new long[expectedValues.length];
		index = expectedValues.length;
		final PrimitiveIterator.OfLong reverse = tree.valueReverseIterator();
		while (reverse.hasNext()) {
			reverseValues[--index] = reverse.nextLong();
		}
		assertEquals(0, index, "Reverse iterator produced a different element count");
		assertArrayEquals(expectedValues, reverseValues, "Reverse value iterator mismatch");
		assertThrows(NoSuchElementException.class, reverse::next, "Reverse iterator should be exhausted");
	}

	@ParameterizedTest(
		name = "TransactionalIntToLongBPlusTree should survive non-transactional generational insert/upsert/delete churn"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized non-transactional (warm-up) insert/upsert/delete churn")
	void generationalWarmUpProofTest(@Nonnull GenerationalTestInput input) {
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalIntToLongBPlusTreeTest (warm-up) seed: " + seed);

		// one long-lived bare tree churned directly (no transaction) at a small block size so splits / merges are dense
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3, 1, 3, 1);
		final Random seedRandom = new Random(seed);
		int[] initialKeys = new int[0];
		do {
			final int key = seedRandom.nextInt(LIMIT_ELEMENTS << 1);
			tree.insert(key, valueOf(key));
			initialKeys = ArrayUtils.insertIntIntoOrderedArray(key, initialKeys);
		} while (initialKeys.length < LIMIT_ELEMENTS);
		verifyTreeConsistency(tree, initialKeys);

		runFor(
			input, 1000, new GenState(new StringBuilder(512), initialKeys, true),
			(random, state) -> {
				final int[] startArray = state.keys();
				int key = -1;
				final boolean delete =
					(startArray.length > 0 && random.nextInt(3) == 0)
						|| (state.limitReached() && startArray.length > LIMIT_ELEMENTS / 2);
				final boolean useUpsert = !delete && random.nextBoolean();
				try {
					final int[] endArray;
					if (delete) {
						key = startArray[random.nextInt(startArray.length)];
						endArray = ArrayUtils.removeIntFromOrderedArray(key, startArray);
						tree.delete(key);
					} else {
						key = random.nextInt(LIMIT_ELEMENTS << 1);
						endArray = ArrayUtils.insertIntIntoOrderedArray(key, startArray);
						if (useUpsert) {
							// value-preserving upsert exercises the insert-or-update public path while keeping the
							// reference exact whether the key was absent (insert + possible split) or already present
							final int upsertKey = key;
							tree.upsert(upsertKey, existing -> valueOf(upsertKey));
						} else {
							tree.insert(key, valueOf(key));
						}
					}

					verifyTreeConsistency(tree, endArray);

					return new GenState(
						state.code().append(delete ? "D:" : useUpsert ? "U:" : "I:").append(key).append(' '),
						endArray,
						state.limitReached()
							? endArray.length > LIMIT_ELEMENTS / 2
							: endArray.length >= LIMIT_ELEMENTS
					);
				} catch (Exception ex) {
					fail(
						"Failed to " + (delete ? "delete" : useUpsert ? "upsert" : "insert") + " key " + key
							+ " for seed " + seed,
						ex
					);
					throw ex;
				}
			}
		);
	}

	@ParameterizedTest(
		name = "TransactionalIntToLongBPlusTree should survive transactional generational insert/upsert/delete churn"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized transactional insert/upsert/delete churn swept cleanly on commit")
	void generationalTransactionalProofTest(@Nonnull GenerationalTestInput input) {
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalIntToLongBPlusTreeTest (transactional) seed: " + seed);

		final Random seedRandom = new Random(seed);
		final TreeSet<Integer> initialReference = new TreeSet<>();
		do {
			initialReference.add(seedRandom.nextInt(LIMIT_ELEMENTS << 1));
		} while (initialReference.size() < LIMIT_ELEMENTS);

		runFor(
			input, 1000, new GenState(new StringBuilder(512), toArray(initialReference), true),
			(random, state) -> {
				// draw a small block size per generation so every generation is a high-split-density tree
				final int blockSize = BLOCK_SIZES[random.nextInt(BLOCK_SIZES.length)];
				final int[] startKeys = state.keys();
				final TransactionalIntToLongBPlusTree tree = buildTree(startKeys, blockSize);
				verifyTreeConsistency(tree, startKeys);

				// oracle mutated in lockstep with the transaction; verified against the committed tree
				final TreeSet<Integer> oracle = new TreeSet<>();
				for (final int key : startKeys) {
					oracle.add(key);
				}
				final AtomicReference<int[]> committedKeys = new AtomicReference<>();
				final StringBuilder code = state.code();
				code.setLength(0);

				try {
					assertStateAfterCommit(
						tree,
						original -> {
							final int operations = 1 + random.nextInt(6);
							for (int op = 0; op < operations; op++) {
								final boolean delete =
									(!oracle.isEmpty() && random.nextInt(3) == 0)
										|| (state.limitReached() && oracle.size() > LIMIT_ELEMENTS / 2);
								if (delete) {
									final int key = pickRandomKey(oracle, random);
									original.delete(key);
									oracle.remove(key);
									code.append("D:").append(key).append(' ');
								} else {
									final int key = random.nextInt(LIMIT_ELEMENTS << 1);
									if (random.nextBoolean()) {
										original.insert(key, valueOf(key));
										code.append("I:").append(key).append(' ');
									} else {
										// value-preserving upsert keeps the oracle exact on both insert and update
										original.upsert(key, existing -> valueOf(key));
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
				return new GenState(
					state.code(),
					nextKeys,
					state.limitReached()
						? nextKeys.length > LIMIT_ELEMENTS / 2
						: nextKeys.length >= LIMIT_ELEMENTS
				);
			}
		);
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
	 * Carries the chained generation state: the running operation log, the committed ascending key snapshot fed to the
	 * next generation and whether the element-count growth limit has been reached.
	 *
	 * @param code         the running operation log used for failure reproduction
	 * @param keys         the committed ascending key snapshot
	 * @param limitReached whether the growth limit has been reached (switches the churn to delete-biased)
	 */
	private record GenState(
		@Nonnull StringBuilder code,
		@Nonnull int[] keys,
		boolean limitReached
	) {
	}
}
