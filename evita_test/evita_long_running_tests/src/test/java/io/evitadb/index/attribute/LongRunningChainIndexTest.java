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

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized stress tests for {@link ChainIndex}. Verifies the contract under random
 * upsert/remove sequences with both clean reordering and chaotic broken intermediate states.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningChainIndexTest implements TimeBoundedTestSupport {

	private final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));

	@ParameterizedTest(name = "ChainIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
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
	@Tag(SLOW)
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

}
