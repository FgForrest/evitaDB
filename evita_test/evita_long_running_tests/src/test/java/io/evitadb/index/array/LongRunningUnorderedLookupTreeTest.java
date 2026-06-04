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

package io.evitadb.index.array;

import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.IntLongMap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generational randomized proof tests for {@link UnorderedLookupTree} - the count-augmented, order-key-routed
 * position tree of the two-tree backing for {@link UnorderedLookup}. Each generation applies a fresh batch of random
 * insert/remove operations and verifies the tree's flattened permutation and per-record positions still match an
 * in-memory oracle.
 *
 * Two flavours are exercised:
 *
 * - a plain non-transactional soak that drives the tree directly and checks consistency after every generation, and
 * - a transactional soak that applies each generation's mutations inside a real {@link io.evitadb.core.transaction.Transaction}
 *   via {@link io.evitadb.utils.AssertionUtils#assertStateAfterCommit} and verifies the committed copy.
 *
 * The {@link Random} is seeded from {@code input.randomSeed()} so the run differs between executions; the small,
 * fixed-seed smoke variants live in `UnorderedLookupTreeTest` in the functional module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("UnorderedLookupTree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningUnorderedLookupTreeTest implements TimeBoundedTestSupport {

	/**
	 * Bundles the position tree with a stand-in value index (`recordId → orderKey`), mirroring the composite that
	 * drives the tree in production. Implements {@link OrderKeyConsumer} to keep the index coherent across the
	 * order-key re-stamps that splits and re-spacing perform. The index map is intentionally non-transactional - only
	 * the tree's behaviour (and, in the transactional flavour, its STM) is under test.
	 */
	private static final class TreeWithIndex implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree tree;
		@Nonnull final IntLongMap valueIndex = new IntLongHashMap();

		TreeWithIndex(@Nonnull UnorderedLookupTree tree) {
			this.tree = tree;
		}

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
		}

		void addAtPosition(int index, int recordId) {
			this.tree.insertAtPosition(index, recordId, this);
		}

		void remove(int recordId) {
			this.tree.removeByOrderKey(this.valueIndex.get(recordId), recordId, this);
			this.valueIndex.remove(recordId);
		}

		int findPosition(int recordId) {
			return this.tree.findPositionByOrderKey(this.valueIndex.get(recordId), recordId);
		}
	}

	@DisplayName("survives generational randomized insert/remove applied directly")
	@ParameterizedTest(name = "UnorderedLookupTree should survive generational randomized modifications applied directly")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final TreeWithIndex tested = new TreeWithIndex(new UnorderedLookupTree());
		final List<Integer> oracle = new ArrayList<>();
		final int[] nextRecordId = {1};

		runFor(
			input,
			10_000,
			new TestState(oracle, nextRecordId),
			(random, testState) -> {
				final List<Integer> currentOracle = testState.oracle();
				final int operationsInGeneration = random.nextInt(1_000);
				for (int i = 0; i < operationsInGeneration; i++) {
					final int size = currentOracle.size();
					final boolean insert = size == 0 || random.nextInt(100) < 60;
					if (insert) {
						final int index = random.nextInt(size + 1);
						final int recordId = testState.nextRecordId()[0]++;
						tested.addAtPosition(index, recordId);
						currentOracle.add(index, recordId);
					} else {
						final int index = random.nextInt(size);
						tested.remove(currentOracle.remove(index));
					}
				}

				// after each generation the whole permutation and every per-record position must match the oracle
				assertConsistentWithOracle(tested, currentOracle);

				return testState;
			}
		);
	}

	@DisplayName("survives generational randomized insert/remove committed transactionally")
	@ParameterizedTest(name = "UnorderedLookupTree should survive generational randomized modifications committed transactionally")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalTransactionalProofTest(@Nonnull GenerationalTestInput input) {
		final int[] nextRecordId = {1};

		runFor(
			input,
			1_000,
			new TestState(new ArrayList<>(), nextRecordId),
			(random, testState) -> {
				// rebuild a freshly committed tree holding the previous generation's logical order, capturing the
				// committed order-keys into a coherent value index before the transaction opens
				final List<Integer> committedOracle = testState.oracle();
				final TreeWithIndex driver = new TreeWithIndex(new UnorderedLookupTree());
				for (int i = 0; i < committedOracle.size(); i++) {
					driver.addAtPosition(i, committedOracle.get(i));
				}

				// the running oracle this generation mutates; it starts as a copy of the committed state
				final List<Integer> generationOracle = new ArrayList<>(committedOracle);

				assertStateAfterCommit(
					driver.tree,
					tested -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int size = generationOracle.size();
							final boolean insert = size == 0 || random.nextInt(100) < 60;
							if (insert) {
								final int index = random.nextInt(size + 1);
								final int recordId = testState.nextRecordId()[0]++;
								driver.addAtPosition(index, recordId);
								generationOracle.add(index, recordId);
							} else {
								final int index = random.nextInt(size);
								driver.remove(generationOracle.remove(index));
							}
						}
					},
					(original, committed) -> {
						// the still-committed view (the "other thread") only ever saw the previous generation
						assertArrayEquals(toArray(committedOracle), original.getArray());
						assertEquals(committedOracle.size(), original.size());

						// the merged copy carries this generation's mutations in logical order
						assertArrayEquals(toArray(generationOracle), committed.getArray());
						assertEquals(generationOracle.size(), committed.size());
						for (int position = 0; position < generationOracle.size(); position++) {
							assertEquals(
								generationOracle.get(position).intValue(),
								committed.getRecordAt(position),
								"getRecordAt mismatch at " + position
							);
						}
					}
				);

				// carry this generation's committed state forward to seed the next generation
				return new TestState(generationOracle, testState.nextRecordId());
			}
		);
	}

	/**
	 * Asserts that the tree's flattened state matches the oracle and that addressing every record by id (via the
	 * stand-in value index) resolves to its true position.
	 */
	private static void assertConsistentWithOracle(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
		assertArrayEquals(toArray(oracle), tested.tree.getArray(), "Flattened array mismatch");
		assertEquals(oracle.size(), tested.tree.size());
		for (int position = 0; position < oracle.size(); position++) {
			final int recordId = oracle.get(position);
			assertEquals(recordId, tested.tree.getRecordAt(position), "getRecordAt mismatch at " + position);
			assertEquals(position, tested.findPosition(recordId), "findPosition mismatch for " + recordId);
		}
	}

	/**
	 * Flattens the oracle list into a primitive `int` array for comparison against the tree's flattened view.
	 */
	@Nonnull
	private static int[] toArray(@Nonnull List<Integer> oracle) {
		final int[] expected = new int[oracle.size()];
		for (int i = 0; i < oracle.size(); i++) {
			expected[i] = oracle.get(i);
		}
		return expected;
	}

	/**
	 * Holds the state carried between generational test iterations: the current logical oracle and the monotonically
	 * increasing record-id generator (a single-element array so it survives the value-semantics of the record).
	 */
	private record TestState(
		@Nonnull List<Integer> oracle,
		@Nonnull int[] nextRecordId
	) {
	}

}
