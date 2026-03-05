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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the contract of {@link ChainIndex} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class ChainIndexTest implements TimeBoundedTestSupport {
	private static final int[] EXPECTED_CHAIN = {1, 2, 3, 4, 5};
	private static final Map<Integer, Predecessor> PREDECESSOR_MAP = Map.of(
		1, new Predecessor(),
		2, new Predecessor(1),
		3, new Predecessor(2),
		4, new Predecessor(3),
		5, new Predecessor(4)
	);

	private final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));

	@DisplayName("Create consistent chain when new items are added in different orders")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryAddingInDifferentOrders(int[] order) {
		for (int pk : order) {
			final Predecessor predecessor = PREDECESSOR_MAP.get(pk);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent());
		assertArrayEquals(EXPECTED_CHAIN, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("Create consistent chain when randomly reordered")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryReordering(int[] order) {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		for (int i = 0; i < order.length; i++) {
			int pk = order[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(order[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(order, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("Create consistent chain when randomly removing elements and returning back")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryRemovingSingleElementsAndReturnItBack(int[] order) {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		for (int count = 1; count <= order.length; count++) {
			// remove the first count elements
			for (int i = 0; i < count; i++) {
				this.index.removePredecessor(order[i]);
			}
			// now return them back in
			for (int i = 0; i < count; i++) {
				this.index.upsertPredecessor(PREDECESSOR_MAP.get(order[i]), order[i]);
			}

			assertTrue(this.index.isConsistent(), "Index is inconsistent.");
			assertArrayEquals(EXPECTED_CHAIN, this.index.getUnorderedLookup().getArray());
		}
	}

	@DisplayName("Create consistent chain when circular dependency is introduced and broken")
	@Test
	void shouldBreakCircularDependency() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 1);
		this.index.upsertPredecessor(new Predecessor(4), 2);
		this.index.upsertPredecessor(new Predecessor(2), 5);
		this.index.upsertPredecessor(new Predecessor(1), 4);

		assertFalse(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {5, 2, 3, 1, 4}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [2, 3, 1, 4]
			      - [5]
			   - elementStates:
			      - 1: SUCCESSOR of 3 🔗 2
			      - 2: CIRCULAR of 4 🔗 2
			      - 3: SUCCESSOR of 2 🔗 2
			      - 4: SUCCESSOR of 1 🔗 2
			      - 5: SUCCESSOR of 2 🔗 5""",
			this.index.toString()
		);

		this.index.upsertPredecessor(new Predecessor(), 3);

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {3, 1, 4, 2, 5}, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("When adding a new element to the middle of the chain and then correcting it, the index should be consistent")
	@Test
	void shouldIntroduceSplitChainDuringIndexingAndThenCorrectIt() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 6);
		this.index.upsertPredecessor(new Predecessor(6), 4);

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 6, 4, 5}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [1, 2, 3, 6, 4, 5]
			   - elementStates:
			      - 1: HEAD 🔗 1
			      - 2: SUCCESSOR of 1 🔗 1
			      - 3: SUCCESSOR of 2 🔗 1
			      - 4: SUCCESSOR of 6 🔗 1
			      - 5: SUCCESSOR of 4 🔗 1
			      - 6: SUCCESSOR of 3 🔗 1""",
			this.index.toString()
		);
	}

	@DisplayName("When introducing a split chain, the longer chains should be favoured")
	@Test
	void shouldIntroduceReconnectSplitChainsFavouringLongerOne() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}

		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 6);
		this.index.upsertPredecessor(new Predecessor(3), 7);
		this.index.upsertPredecessor(new Predecessor(7), 8);

		assertFalse(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 4, 5, 7, 8, 6}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [1, 2, 3, 4, 5]
			      - [6]
			      - [7, 8]
			   - elementStates:
			      - 1: HEAD 🔗 1
			      - 2: SUCCESSOR of 1 🔗 1
			      - 3: SUCCESSOR of 2 🔗 1
			      - 4: SUCCESSOR of 3 🔗 1
			      - 5: SUCCESSOR of 4 🔗 1
			      - 6: SUCCESSOR of 3 🔗 6
			      - 7: SUCCESSOR of 3 🔗 7
			      - 8: SUCCESSOR of 7 🔗 7""",
			this.index.toString()
		);
	}

	@DisplayName("When elements are removed the chains are properly collapsed")
	@Test
	void shouldCollapseChainsOnElementRemoval() {
		int[] initialState = {12, 7, 6, 2, 13, 5, 17, 1, 9};
		for (int i = 0; i < initialState.length; i++) {
			int pk = initialState[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent());

		this.index.upsertPredecessor(new Predecessor(12), 6);
		this.index.upsertPredecessor(new Predecessor(6), 5);
		this.index.removePredecessor(1);
		this.index.upsertPredecessor(new Predecessor(5), 13);
		this.index.upsertPredecessor(new Predecessor(13), 7);
		this.index.removePredecessor(17);
		this.index.upsertPredecessor(new Predecessor(7), 9);
		this.index.upsertPredecessor(new Predecessor(9), 19);
		this.index.upsertPredecessor(new Predecessor(19), 3);
		this.index.upsertPredecessor(new Predecessor(3), 21);
		this.index.removePredecessor(2);

		assertTrue(this.index.isConsistent());
	}

	@Test
	void shouldExecuteOperationsInTransactionAndStayConsistent() {
		int[] initialState = {23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11};
		for (int i = 0; i < initialState.length; i++) {
			int pk = initialState[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertStateAfterCommit(
			this.index,
			original -> {
				original.upsertPredecessor(new Predecessor(-1), 8);
				original.upsertPredecessor(new Predecessor(8), 2);
				original.upsertPredecessor(new Predecessor(2), 23);
				original.removePredecessor(11);
				original.upsertPredecessor(new Predecessor(23), 4);
				original.upsertPredecessor(new Predecessor(4), 26);
				original.removePredecessor(9);
				original.upsertPredecessor(new Predecessor(26), 3);
				original.upsertPredecessor(new Predecessor(3), 7);
				original.upsertPredecessor(new Predecessor(7), 10);
				original.removePredecessor(6);
				original.upsertPredecessor(new Predecessor(10), 5);
				original.upsertPredecessor(new Predecessor(5), 24);
				original.upsertPredecessor(new Predecessor(24), 19);

				assertTrue(original.isConsistent());
			},
			(chainIndex, chainIndex2) -> {
				assertTrue(chainIndex.isConsistent());
				assertTrue(chainIndex2.isConsistent());
			}
		);
	}

	@Test
	void shouldGenerateConsistencyReport() {
		shouldExecuteOperationsInTransactionAndStayConsistent();

		assertEquals(
			"""
			## Chains

				- 23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11
			
			## No errors detected.""",
			this.index.getConsistencyReport().report()
		);
	}

	@ParameterizedTest(name = "ChainIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Random theRandom = new Random(input.randomSeed());
		final int[] initialState = generateInitialChain(theRandom, initialCount);
		final AtomicReference<int[]> originalOrder = new AtomicReference<>(new int[0]);
		final AtomicReference<int[]> desiredOrder = new AtomicReference<>(initialState);
		final AtomicReference<ChainIndex> transactionalIndex = new AtomicReference<>(this.index);

		runFor(
			input,
			100,
			new StringBuilder(256),
			(random, codeBuffer) -> {
				final int[] originalState = originalOrder.get();

				final ChainIndex index = transactionalIndex.get();
				codeBuffer.append("\nSTART: ")
					.append(
						"int[] initialState = {" + Arrays.stream(index.getUnorderedLookup().getArray()).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "};\n" +
						"\t\tfor (int i = 0; i < initialState.length; i++) {\n" +
						"\t\t\tint pk = initialState[i];\n" +
						"\t\t\tfinal Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);\n" +
						"\t\t\tindex.upsertPredecessor(predecessor, pk);\n" +
						"\t\t}"
					)
					.append("\n");

				assertStateAfterCommit(
					index,
					original -> {
						final int[] targetState = desiredOrder.get();

						final Deque<Integer> removedPrimaryKeys = new LinkedList<>();
						for (int pk : originalState) {
							if (ArrayUtils.indexOf(pk, targetState) < 0) {
								removedPrimaryKeys.push(pk);
							}
						}

						try {
							for (int i = 0; i < targetState.length; i++) {
								final int pk = targetState[i];
								final Predecessor predecessor = i <= 0 ? Predecessor.HEAD : new Predecessor(targetState[i - 1]);

								final int originalStatePkIndex = ArrayUtils.indexOf(pk, originalState);
								final Predecessor originalPredecessor;
								if (originalStatePkIndex >= 0) {
									originalPredecessor = originalStatePkIndex == 0 ? Predecessor.HEAD : new Predecessor(originalState[originalStatePkIndex - 1]);
								} else {
									originalPredecessor = null;
								}

								if (predecessor != originalPredecessor) {
									// change order
									codeBuffer.append("index.upsertPredecessor(")
										.append("new Predecessor(").append(predecessor.predecessorPk()).append("), ")
										.append(pk).append(");\n");
									original.upsertPredecessor(predecessor, pk);
								}

								// remove the element randomly
								if (!removedPrimaryKeys.isEmpty() && random.nextInt(5) == 0) {
									final Integer pkToRemove = removedPrimaryKeys.pop();
									codeBuffer.append("index.removePredecessor(")
										.append(pkToRemove).append(");\n");
									original.removePredecessor(pkToRemove);
								}
							}

							while (!removedPrimaryKeys.isEmpty()) {
								final Integer pkToRemove = removedPrimaryKeys.pop();
								codeBuffer.append("index.removePredecessor(")
									.append(pkToRemove).append(");\n");
								original.removePredecessor(pkToRemove);
							}

							codeBuffer.append("\n");

						} catch (Exception ex) {
							System.out.println(codeBuffer);
							throw ex;
						}

						final int[] finalArray = original.getUnorderedLookup().getArray();
						try {
							if (!Arrays.equals(targetState, finalArray)) {
								final int[] finalArrayAgain = original.getUnorderedLookup().getArray();
							}
							assertArrayEquals(targetState, finalArray);
						} catch (Throwable ex) {
							System.out.println(codeBuffer);
							throw ex;
						}
					},
					(original, committed) -> {
						try {
							final int[] originalArray = original.getUnorderedLookup().getArray();
							assertArrayEquals(originalOrder.get(), originalArray);
							final int[] finalArray = committed.getUnorderedLookup().getArray();
							assertArrayEquals(desiredOrder.get(), finalArray);
							assertTrue(original.isConsistent());
							assertTrue(committed.isConsistent());
							assertEquals(ConsistencyState.CONSISTENT, original.getConsistencyReport().state());
							assertEquals(ConsistencyState.CONSISTENT, committed.getConsistencyReport().state());

							originalOrder.set(finalArray);
							transactionalIndex.set(committed);

							defineTargetState(random, finalArray, initialCount, desiredOrder);
						} catch (Throwable ex) {
							System.out.println(codeBuffer);
							throw ex;
						}
					}
				);

				return new StringBuilder(256);
			}
		);
	}

	/**
	 * This test will insert to a and remove from the data chaotically. In the final stage it reorder them in
	 * a consistent way and checks if the final state is consistent.
	 *
	 * @param input input for the test
	 */
	@ParameterizedTest(name = "ChainIndex should survive generational randomized test with garbage")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalAllTimeBrokenProofTest(GenerationalTestInput input) {
		final int initialCount = 30;
		final Random theRandom = new Random(input.randomSeed());
		final int[] initialState = generateInitialChain(theRandom, initialCount);
		final AtomicReference<int[]> originalOrder = new AtomicReference<>(new int[0]);
		final AtomicReference<ChainIndex> transactionalIndex = new AtomicReference<>(this.index);

		runFor(
			input,
			100,
			new StringBuilder(256),
			(random, codeBuffer) -> {
				final int[] originalState = originalOrder.get();

				final ChainIndex index = transactionalIndex.get();
				codeBuffer.append("\nSTART: ")
					.append(
						"int[] initialState = {" + Arrays.stream(index.getUnorderedLookup().getArray()).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "};\n" +
							"\t\tfor (int i = 0; i < initialState.length; i++) {\n" +
							"\t\t\tint pk = initialState[i];\n" +
							"\t\t\tfinal Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);\n" +
							"\t\t\tindex.upsertPredecessor(predecessor, pk);\n" +
							"\t\t}"
					)
					.append("\n");

				assertStateAfterCommit(
					index,
					original -> {
						final Deque<Integer> removedPrimaryKeys = new LinkedList<>();
						for (int pk : originalState) {
							if (originalState.length - removedPrimaryKeys.size() > initialCount * 0.8 && random.nextInt(5) == 0) {
								removedPrimaryKeys.push(pk);
							}
						}

						try {

							final Set<Integer> processedPks = new HashSet<>(removedPrimaryKeys);
							for (int i = 0; i < initialCount * 0.5; i++) {
								final int randomPreviousIndex = random.nextInt(initialState.length);
								final int previousPk = initialState[randomPreviousIndex];

								int randomPk;
								do {
									randomPk = initialState[random.nextInt(initialState.length)];
								} while (processedPks.contains(randomPk) || randomPk == previousPk);

								processedPks.add(randomPk);
								final Predecessor predecessor = randomPreviousIndex == 0 ? Predecessor.HEAD : new Predecessor(previousPk);

								// change order
								codeBuffer.append("index.upsertPredecessor(")
									.append("new Predecessor(").append(predecessor.predecessorPk()).append("), ")
									.append(randomPk).append(");\n");
								original.upsertPredecessor(predecessor, randomPk);

								// remove the element randomly
								if (!removedPrimaryKeys.isEmpty() && random.nextInt(5) == 0) {
									final Integer pkToRemove = removedPrimaryKeys.pop();
									codeBuffer.append("index.removePredecessor(")
										.append(pkToRemove).append(");\n");
									original.removePredecessor(pkToRemove);
								}
							}

							while (!removedPrimaryKeys.isEmpty()) {
								final Integer pkToRemove = removedPrimaryKeys.pop();
								codeBuffer.append("index.removePredecessor(")
									.append(pkToRemove).append(");\n");
								original.removePredecessor(pkToRemove);
							}

							codeBuffer.append("\n");

						} catch (Exception ex) {
							System.out.println(codeBuffer);
							throw ex;
						}
					},
					(original, committed) -> {
						try {
							final int[] originalArray = original.getUnorderedLookup().getArray();
							assertArrayEquals(originalOrder.get(), originalArray);
							final int[] finalArray = committed.getUnorderedLookup().getArray();
							assertNotEquals(ConsistencyState.BROKEN, committed.getConsistencyReport().state());

							originalOrder.set(finalArray);
							transactionalIndex.set(committed);
						} catch (Throwable ex) {
							System.out.println(codeBuffer);
							throw ex;
						}
					}
				);

				return new StringBuilder(256);
			}
		);

		final StringBuilder codeBuffer = new StringBuilder(256);
		final int[] originalState = originalOrder.get();
		final AtomicReference<int[]> desiredOrder = new AtomicReference<>(initialState);
		defineTargetState(theRandom, originalState, initialCount, desiredOrder);
		assertStateAfterCommit(
			this.index,
			original -> {
				final int[] targetState = desiredOrder.get();
				try {
					for (int i = 0; i < targetState.length; i++) {
						final int pk = targetState[i];
						final Predecessor predecessor = i <= 0 ? Predecessor.HEAD : new Predecessor(targetState[i - 1]);

						// change order
						codeBuffer.append("index.upsertPredecessor(")
							.append("new Predecessor(").append(predecessor.predecessorPk()).append("), ")
							.append(pk).append(");\n");
						original.upsertPredecessor(predecessor, pk);
					}

					codeBuffer.append("\n");

				} catch (Exception ex) {
					System.out.println(codeBuffer);
					throw ex;
				}
			},
			(original, committed) -> {
				try {
					final int[] finalArray = committed.getUnorderedLookup().getArray();
					assertArrayEquals(desiredOrder.get(), finalArray);
					assertTrue(committed.isConsistent());
					assertEquals(ConsistencyState.CONSISTENT, committed.getConsistencyReport().state());

					originalOrder.set(finalArray);
					transactionalIndex.set(committed);
				} catch (Throwable ex) {
					System.out.println(codeBuffer);
					throw ex;
				}
			}
		);
	}

	private static void defineTargetState(@Nonnull Random random, @Nonnull int[] originalState, int initialCount, @Nonnull AtomicReference<int[]> desiredOrder) {
		// collect the pks to next generation - leave out some of existing and add some new
		final int[] targetState = IntStream.concat(
			Arrays.stream(originalState).filter(it -> random.nextInt(10) != 0),
			// add a few new primary keys
			IntStream.generate(() -> random.nextInt(initialCount * 3)).limit((long)(initialCount * 0.3))
		)
			.distinct()
			.limit((long)(initialCount * 1.2))
			.toArray();

		// randomize one third of the elements
		ArrayUtils.shuffleArray(random, targetState, initialCount / 3);
		desiredOrder.set(targetState);
	}

	/**
	 * Generates initial chain of the given length with primary keys from 1 to initialCount in random order.
	 * @param random random generator to use
	 * @param initialCount number of elements in the chain
	 * @return array of primary keys
	 */
	private static int[] generateInitialChain(@Nonnull Random random, int initialCount) {
		final int[] initialState = new int[initialCount];
		for (int i = 0; i < initialCount; i++) {
			initialState[i] = i + 1;
		}
		ArrayUtils.shuffleArray(random, initialState, initialCount);
		return initialState;
	}

	/**
	 * Creates all permutations of the expected chain.
	 * @return stream of arguments for the test method
	 */
	@Nonnull
	static Stream<Arguments> createPermutationsForPredecessors() {
		final List<int[]> result = new LinkedList<>();
		permute(Arrays.copyOf(EXPECTED_CHAIN, EXPECTED_CHAIN.length), 0, result);

		return result.stream()
				.map(Arguments::of);
	}

	/**
	 * Creates all permutations of the given array. The permutations are added to the result list.
	 * @param nums array to permute
	 * @param start index of the first element to permute
	 * @param result list to add the permutations to
	 */
	private static void permute(@Nonnull int[] nums, int start, @Nonnull List<int[]> result) {
		if (start == nums.length - 1) {
			result.add(Arrays.copyOf(nums, nums.length));
			return;
		}

		for (int i = start; i < nums.length; i++) {
			swap(nums, start, i);
			permute(nums, start + 1, result);
			swap(nums, start, i);  // backtrack
		}
	}

	/**
	 * Swaps two elements in the given array.
	 * @param nums array to swap elements in
	 * @param i index of the first element
	 * @param j index of the second element
	 */
	private static void swap(@Nonnull int[] nums, int i, int j) {
		int temp = nums[i];
		nums[i] = nums[j];
		nums[j] = temp;
	}

	/**
	 * Populates the shared `index` field with the standard 1-5 chain.
	 */
	private void populateStandardChain() {
		for (int pk : EXPECTED_CHAIN) {
			ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
	}

	/**
	 * Tests verifying construction and getter correctness for different ChainIndex constructors.
	 */
	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("two-arg constructor with RepresentativeReferenceKey sets getters correctly")
		void shouldConstructWithReferenceKeyAndVerifyGetters() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 42)
			);
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "order", null);
			final ChainIndex idx = new ChainIndex(refKey, attrKey);

			assertSame(refKey, idx.getReferenceKey());
			assertSame(attrKey, idx.getAttributeIndexKey());
			assertTrue(idx.isEmpty());
			assertTrue(idx.isConsistent());
		}

		@Test
		@DisplayName("four-arg deserialization constructor provides correct unordered lookup")
		void shouldConstructFromDeserializedDataAndVerifyLookup() {
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "order", null);
			final int[][] chains = new int[][]{
				{10, 20, 30}
			};
			final Map<Integer, ChainElementState> elementStates = new HashMap<>(4);
			elementStates.put(
				10,
				new ChainElementState(10, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD)
			);
			elementStates.put(
				20,
				new ChainElementState(10, 10, ChainIndex.ElementState.SUCCESSOR)
			);
			elementStates.put(
				30,
				new ChainElementState(10, 20, ChainIndex.ElementState.SUCCESSOR)
			);

			final ChainIndex idx = new ChainIndex(attrKey, chains, elementStates);

			assertFalse(idx.isEmpty());
			assertTrue(idx.isConsistent());
			assertArrayEquals(new int[]{10, 20, 30}, idx.getUnorderedLookup().getArray());
			assertNull(idx.getReferenceKey());
		}

		@Test
		@DisplayName("four-arg deserialization constructor with reference key")
		void shouldConstructFromDeserializedDataWithReferenceKey() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("category", 7)
			);
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "pos", null);
			final int[][] chains = new int[][]{
				{1, 2}
			};
			final Map<Integer, ChainElementState> elementStates = new HashMap<>(4);
			elementStates.put(
				1,
				new ChainElementState(1, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD)
			);
			elementStates.put(
				2,
				new ChainElementState(1, 1, ChainIndex.ElementState.SUCCESSOR)
			);

			final ChainIndex idx = new ChainIndex(refKey, attrKey, chains, elementStates);

			assertSame(refKey, idx.getReferenceKey());
			assertArrayEquals(new int[]{1, 2}, idx.getUnorderedLookup().getArray());
		}
	}

	/**
	 * Tests for STM invariants: id uniqueness, removeLayer cleanup, commit behavior,
	 * and createCopyWithMergedTransactionalMemory with null layer.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("getId() returns stable unique id across different instances")
		void shouldReturnStableUniqueId() {
			final ChainIndex idx1 = new ChainIndex(
				new AttributeIndexKey(null, "a", null)
			);
			final ChainIndex idx2 = new ChainIndex(
				new AttributeIndexKey(null, "b", null)
			);

			assertNotEquals(idx1.getId(), idx2.getId());
			// id is stable across multiple calls
			assertEquals(idx1.getId(), idx1.getId());
		}

		@Test
		@DisplayName("after commit, original and committed are not the same instance")
		void shouldReturnNewInstanceAfterCommit() {
			populateStandardChain();

			assertStateAfterCommit(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					// original still has the old 5-element chain
					assertArrayEquals(EXPECTED_CHAIN, original.getUnorderedLookup().getArray());
					// committed has the new 6-element chain
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 5, 6},
						committed.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("original order array is not mutated after commit")
		void shouldNotMutateOriginalAfterCommit() {
			populateStandardChain();
			final int[] originalArrayBefore = ChainIndexTest.this.index
				.getUnorderedLookup().getArray().clone();

			assertStateAfterCommit(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
					original.removePredecessor(1);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertArrayEquals(
						originalArrayBefore,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests verifying rollback semantics -- transactional changes are discarded and
	 * the original index remains unchanged.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("rollback after upsert preserves original state")
		void shouldRollbackUpsert() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 5, 6},
						original.getUnorderedLookup().getArray()
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback after remove preserves original state")
		void shouldRollbackRemove() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					original.removePredecessor(3);
				},
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback of circular dependency introduction preserves original state")
		void shouldRollbackCircularDependency() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					// introduce circular: make element 1 depend on element 3
					original.upsertPredecessor(new Predecessor(3), 1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isConsistent());
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests verifying non-transactional mode: operations applied directly without
	 * a transaction context.
	 */
	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("upsert and remove outside transaction update state correctly")
		void shouldUpsertAndRemoveOutsideTransaction() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "x", null)
			);

			idx.upsertPredecessor(new Predecessor(), 10);
			idx.upsertPredecessor(new Predecessor(10), 20);
			idx.upsertPredecessor(new Predecessor(20), 30);

			assertTrue(idx.isConsistent());
			assertArrayEquals(
				new int[]{10, 20, 30},
				idx.getUnorderedLookup().getArray()
			);

			idx.removePredecessor(20);

			assertArrayEquals(
				new int[]{10, 30},
				idx.getUnorderedLookup().getArray()
			);
		}
	}

	/**
	 * Tests verifying ReferencedEntityPredecessor support in the ChainIndex.
	 */
	@Nested
	@DisplayName("ReferencedEntityPredecessor support")
	class ReferencedEntityPredecessorTest {

		@Test
		@DisplayName("upsertPredecessor with ReferencedEntityPredecessor works correctly")
		void shouldUpsertWithReferencedEntityPredecessor() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "refOrder", null)
			);

			idx.upsertPredecessor(new ReferencedEntityPredecessor(), 1);
			idx.upsertPredecessor(new ReferencedEntityPredecessor(1), 2);
			idx.upsertPredecessor(new ReferencedEntityPredecessor(2), 3);

			assertTrue(idx.isConsistent());
			assertArrayEquals(
				new int[]{1, 2, 3},
				idx.getUnorderedLookup().getArray()
			);
		}

		@Test
		@DisplayName("self-reference via ReferencedEntityPredecessor does NOT throw")
		void shouldAllowSelfReferenceWithReferencedEntityPredecessor() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "refOrder", null)
			);

			idx.upsertPredecessor(new ReferencedEntityPredecessor(), 1);

			// self-reference is allowed for ReferencedEntityPredecessor
			assertDoesNotThrow(
				() -> idx.upsertPredecessor(new ReferencedEntityPredecessor(1), 1)
			);
		}
	}

	/**
	 * Tests verifying error paths: removing non-existent elements, self-referential Predecessor.
	 */
	@Nested
	@DisplayName("Error paths")
	class ErrorPathsTest {

		@Test
		@DisplayName("removePredecessor on non-existent element throws EvitaInvalidUsageException")
		void shouldThrowOnRemoveNonExistent() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> ChainIndexTest.this.index.removePredecessor(999)
			);
			assertTrue(
				ex.getMessage().contains("999"),
				"Exception message should reference the missing pk"
			);
		}

		@Test
		@DisplayName("self-referential Predecessor throws EvitaInvalidUsageException")
		void shouldThrowOnSelfReferentialPredecessor() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> ChainIndexTest.this.index.upsertPredecessor(
					new Predecessor(5), 5
				)
			);
		}
	}

	/**
	 * Tests verifying SortedRecordsSupplier behavior for ascending and descending order.
	 */
	@Nested
	@DisplayName("SortedRecordsSupplier")
	class SortedRecordsSupplierTest {

		@Test
		@DisplayName("ascending order supplier returns correct order")
		void shouldReturnAscendingOrder() {
			populateStandardChain();

			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();

			assertArrayEquals(
				EXPECTED_CHAIN,
				asc.getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("descending order supplier returns reverse order")
		void shouldReturnDescendingOrder() {
			populateStandardChain();

			final SortedRecordsSupplier desc =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getDescendingOrderRecordsSupplier();

			assertArrayEquals(
				new int[]{5, 4, 3, 2, 1},
				desc.getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("ascending supplier with referenceKey returns ReferenceSortedRecordsProvider")
		void shouldReturnReferenceSortedRecordsProviderWhenReferenceKeyPresent() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 1)
			);
			final ChainIndex idx = new ChainIndex(
				refKey,
				new AttributeIndexKey(null, "order", null)
			);
			idx.upsertPredecessor(new Predecessor(), 10);
			idx.upsertPredecessor(new Predecessor(10), 20);

			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) idx.getAscendingOrderRecordsSupplier();

			assertInstanceOf(ReferenceSortedRecordsProvider.class, asc);
			assertArrayEquals(new int[]{10, 20}, asc.getSortedRecordIds());
		}

		@Test
		@DisplayName("cached supplier on repeated call without modification returns same instance")
		void shouldReturnCachedSupplier() {
			populateStandardChain();

			final SortedRecordsSupplier first =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();
			final SortedRecordsSupplier second =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();

			assertSame(first, second);
		}
	}

	/**
	 * Tests verifying dirty flag and storage part creation behavior.
	 */
	@Nested
	@DisplayName("Dirty flag and storage part")
	class DirtyFlagStoragePartTest {

		@Test
		@DisplayName("createStoragePart returns non-null after upsert")
		void shouldReturnStoragePartAfterUpsert() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);

			final StoragePart part = ChainIndexTest.this.index.createStoragePart(1);
			assertNotNull(part);
			assertInstanceOf(ChainIndexStoragePart.class, part);
		}

		@Test
		@DisplayName("createStoragePart returns null on fresh (non-dirty) index")
		void shouldReturnNullStoragePartOnFreshIndex() {
			final StoragePart part = ChainIndexTest.this.index.createStoragePart(1);
			assertNull(part);
		}

		@Test
		@DisplayName("resetDirty makes createStoragePart return null")
		void shouldReturnNullAfterResetDirty() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			// consume the dirty state
			final StoragePart firstPart = ChainIndexTest.this.index.createStoragePart(1);
			assertNotNull(firstPart);

			ChainIndexTest.this.index.resetDirty();

			final StoragePart secondPart = ChainIndexTest.this.index.createStoragePart(1);
			assertNull(secondPart);
		}
	}

	/**
	 * Tests verifying isEmpty behavior across lifecycle states.
	 */
	@Nested
	@DisplayName("isEmpty behavior")
	class IsEmptyTest {

		@Test
		@DisplayName("fresh index is empty")
		void shouldBeEmptyOnFreshIndex() {
			assertTrue(ChainIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("index is not empty after upsert")
		void shouldNotBeEmptyAfterUpsert() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			assertFalse(ChainIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("index is empty after all elements removed")
		void shouldBeEmptyAfterAllRemoved() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(1), 2);

			ChainIndexTest.this.index.removePredecessor(2);
			ChainIndexTest.this.index.removePredecessor(1);

			assertTrue(ChainIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests verifying toString output for various index states.
	 */
	@Nested
	@DisplayName("toString representation")
	class ToStringTest {

		@Test
		@DisplayName("toString with referenceKey includes refKey label")
		void shouldIncludeRefKeyInToString() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 5)
			);
			final ChainIndex idx = new ChainIndex(
				refKey,
				new AttributeIndexKey(null, "order", null)
			);
			idx.upsertPredecessor(new Predecessor(), 1);

			final String result = idx.toString();
			assertTrue(
				result.contains("(refKey:"),
				"toString should contain '(refKey:' prefix"
			);
		}

		@Test
		@DisplayName("toString for empty index produces valid output")
		void shouldProduceValidToStringForEmptyIndex() {
			final String result = ChainIndexTest.this.index.toString();
			assertNotNull(result);
			assertTrue(result.contains("ChainIndex"));
		}
	}

	/**
	 * Tests verifying consistency report states.
	 */
	@Nested
	@DisplayName("Consistency report")
	class ConsistencyReportTest {

		@Test
		@DisplayName("INCONSISTENT state when multiple chains exist")
		void shouldReportInconsistentWhenMultipleChainsExist() {
			populateStandardChain();

			// introduce a second chain that cannot be merged
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 6);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 7);

			assertFalse(ChainIndexTest.this.index.isConsistent());

			final ConsistencyState state =
				ChainIndexTest.this.index.getConsistencyReport().state();
			assertEquals(ConsistencyState.INCONSISTENT, state);
		}
	}

}
