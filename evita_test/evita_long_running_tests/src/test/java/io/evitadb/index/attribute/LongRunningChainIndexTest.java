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
import io.evitadb.test.TestTags;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
	 * Generational proof that a **rolled-back** transaction discards every in-transaction move and leaves the base
	 * {@link ChainIndex} byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation seeds a
	 * fresh single chain `1..N`, random-walks the base with a batch of **coherent local moves** applied outside the
	 * transaction (the realistic chain workload, mirroring {@link #shouldChurnViaCoherentMoves} and the sibling
	 * {@code LongRunningSavepointChainIndexTest}), captures a value oracle of that base, then inside a transaction that
	 * is rolled back applies a further coherent-move batch (with a guaranteed reordering marker so the batch is
	 * non-vacuous) and asserts the base order is unchanged and no committed value was published.
	 *
	 * @param input input for the test
	 */
	@ParameterizedTest(name = "ChainIndex rollback discards every in-transaction move and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(@Nonnull GenerationalTestInput input) {
		final int chainSize = 24;
		final int maxOps = 10;
		runFor(
			input,
			1_000,
			0L,
			(random, iteration) -> {
				final ChainState state = new ChainState(chainSize);
				// random-walk the base OUTSIDE the transaction so each generation proves rollback from a fresh order
				state.applyRandomMoves(random, random.nextInt(maxOps * 2));
				// value oracle of the base state that the rollback must return to
				final ChainSnapshot beforeRollback = snapshot(state.index);

				assertStateAfterRollback(
					state.index,
					original -> {
						// a guaranteed reordering move makes the in-transaction batch non-vacuous
						state.forceReorder();
						state.applyRandomMoves(random, 1 + random.nextInt(maxOps));
					},
					(original, committed) -> {
						assertNull(committed, "A rolled-back transaction must not publish a committed value!");
						assertEquals(
							beforeRollback, snapshot(original),
							"ChainIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);
				return iteration + 1;
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

	/**
	 * Workload-validation harness for the {@link ChainIndex} used behind a `Predecessor`-ordered attribute.
	 *
	 * `ChainIndex` models a single-linked chain of elements ordered by their predecessor pointers; it is built to keep
	 * an "unordered" ordering in a form where **moving an individual element perturbs only a constant number of
	 * neighbours** (the moved element plus its old and new successors), never renumbering the whole tail. It is
	 * therefore the wrong structure for purely-random insert/delete churn - random permanent deletes shatter the chain
	 * into unbounded split subchains and stress the unrelated chain-collapse bookkeeping rather than the move path.
	 *
	 * This harness drives the index directly (no engine) with the **realistic** workload: phase 1 builds a single
	 * chain `1..N`, phase 2 performs coherent local **moves** over a maintained doubly-linked order, keeping the chain
	 * consistent. It asserts the live subchain count ({@link ChainIndex#chains}) stays bounded (units/tens), and
	 * reports per-block timing so the move path can be observed to scale (no `O(ops*chains)` cliff).
	 *
	 * It is a fixed-size scaling assertion rather than a generational/time-bounded test, so it runs as a plain
	 * {@code @Test} tagged {@link TestTags#SLOW} - run explicitly, not part of the fast functional loop.
	 */
	@DisplayName("Coherent local moves keep the chain bounded and scale with no collapse cliff")
	@Test
	@Tag(SLOW)
	void shouldChurnViaCoherentMoves() {
		final int initialRecordCount = 1_000_000;
		final int churnOperations = 200_000;
		final int block = 10_000;
		// the chain may fragment transiently while a move is applied, but must stay bounded - never thousands
		final int maxReasonableChains = 100;

		final ChainIndex chainIndex = new ChainIndex(new AttributeIndexKey(null, "order", null));

		// phase 1 - build a single chain 1..N (record i chained right after record i-1)
		final long buildStart = System.nanoTime();
		for (int i = 0; i < initialRecordCount; i++) {
			final int primaryKey = i + 1;
			chainIndex.upsertPredecessor(primaryKey == 1 ? Predecessor.HEAD : new Predecessor(primaryKey - 1), primaryKey);
		}
		System.out.printf(
			"Phase 1: built %d-element chain in %d ms; chain count = %d%n",
			initialRecordCount, (System.nanoTime() - buildStart) / 1_000_000L, chainIndex.chains.size()
		);

		// maintained doubly-linked order of the (all-live) records: pred[pk]/succ[pk], 0 == HEAD / none
		final int[] pred = new int[initialRecordCount + 1];
		final int[] succ = new int[initialRecordCount + 1];
		for (int pk = 1; pk <= initialRecordCount; pk++) {
			pred[pk] = pk - 1;
			succ[pk] = pk == initialRecordCount ? 0 : pk + 1;
		}

		// phase 2 - coherent local moves: relocate a random element after a random other element (or to HEAD),
		// updating exactly the three affected predecessors (moved element, old successor, new successor)
		final Random random = new Random(42);
		long blockStart = System.nanoTime();
		int maxChainsObserved = chainIndex.chains.size();
		int head = 1;
		for (int op = 0; op < churnOperations; op++) {
			final int x = 1 + random.nextInt(initialRecordCount);
			// 10 % of moves promote the element to the chain head, otherwise relocate after a random anchor
			int anchor = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(initialRecordCount);
			if (anchor == x) {
				anchor = pred[x]; // avoid self-anchor; collapses to a no-op which we skip below
			}
			if (anchor == pred[x]) {
				continue; // element already sits right after the anchor - nothing to do
			}

			final int pOld = pred[x];
			final int sOld = succ[x];
			// detach x from its current position
			if (pOld == 0) {
				head = sOld; // x was the head; its successor becomes the new head
			} else {
				succ[pOld] = sOld;
			}
			if (sOld != 0) {
				pred[sOld] = pOld;
			}
			// insert x right after the anchor (anchor == 0 means promote to head)
			final int sNew = anchor == 0 ? head : succ[anchor];
			if (anchor == 0) {
				head = x;
			} else {
				succ[anchor] = x;
			}
			pred[x] = anchor;
			succ[x] = sNew;
			if (sNew != 0) {
				pred[sNew] = x;
			}

			// apply the move to the index as the three affected predecessor updates, in the natural "detach-first"
			// order: first reconnect x's old successor to x's old predecessor (so x stops dragging a suffix), then
			// relocate x, then attach x's new successor. This keeps every single mutation a true local move.
			if (sOld != 0 && sOld != x) {
				chainIndex.upsertPredecessor(pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld), sOld);
			}
			chainIndex.upsertPredecessor(anchor == 0 ? Predecessor.HEAD : new Predecessor(anchor), x);
			if (sNew != 0 && sNew != x) {
				chainIndex.upsertPredecessor(new Predecessor(x), sNew);
			}

			maxChainsObserved = Math.max(maxChainsObserved, chainIndex.chains.size());
			if ((op + 1) % block == 0) {
				final long blockMs = (System.nanoTime() - blockStart) / 1_000_000L;
				System.out.printf(
					"Moves %d..%d: %d ms (%.3f ms/op); chains now = %d (max seen = %d)%n",
					op + 1 - block, op, blockMs, blockMs / (double) block, chainIndex.chains.size(), maxChainsObserved
				);
				blockStart = System.nanoTime();
			}
		}

		assertTrue(chainIndex.isConsistent(), "Index must stay consistent after coherent moves.");
		assertTrue(
			maxChainsObserved <= maxReasonableChains,
			"Coherent moves must keep the chain bounded, but observed up to " + maxChainsObserved + " subchains."
		);
	}

	/**
	 * Reads the full logical content of the chain — its element order as produced by {@link ChainIndex#getUnorderedLookup()}
	 * — into a value-comparable snapshot, so two snapshots taken before and after a rollback can be compared with
	 * `.equals` to prove exact restoration. The chain order is a sequence (not a set), so it is preserved verbatim and
	 * deliberately NOT sorted — the order itself is the logical state under test.
	 *
	 * @param index the chain index to snapshot
	 * @return a value-comparable snapshot of the chain order
	 */
	@Nonnull
	static ChainSnapshot snapshot(@Nonnull ChainIndex index) {
		final int[] array = index.getUnorderedLookup().getArray();
		final List<Integer> order = new ArrayList<>(array.length);
		for (final int primaryKey : array) {
			order.add(primaryKey);
		}
		return new ChainSnapshot(order);
	}

	/**
	 * Value-comparable snapshot of a {@link ChainIndex}: the element order (primary keys in chain order). Record equality
	 * gives deep structural comparison of the ordered content.
	 *
	 * @param order the chain's element order (primary keys)
	 */
	record ChainSnapshot(@Nonnull List<Integer> order) {}

	/**
	 * A consistent {@link ChainIndex} over the fixed primary-key set `1..N`, paired with an in-test doubly-linked order
	 * model ({@code pred}/{@code succ}/{@code head}, `0` = HEAD / none) so randomized **coherent local moves** can be
	 * generated that keep the chain consistent. The initial single chain `1..N` is built outside any transaction; moves
	 * are applied to the index (and mirrored in the model) as the three affected predecessor updates in detach-first
	 * order — each a true local move, mirroring {@link #shouldChurnViaCoherentMoves}.
	 */
	private static final class ChainState {
		private final int size;
		private final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));
		private final int[] pred;
		private final int[] succ;
		private int head;

		ChainState(int size) {
			this.size = size;
			this.pred = new int[size + 1];
			this.succ = new int[size + 1];
			this.head = 1;
			for (int pk = 1; pk <= size; pk++) {
				this.pred[pk] = pk - 1;
				this.succ[pk] = pk == size ? 0 : pk + 1;
				this.index.upsertPredecessor(pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk);
			}
		}

		/**
		 * Applies `count` random coherent moves (relocate a random element after a random anchor, or to HEAD).
		 *
		 * @param random the randomness source
		 * @param count  number of moves to attempt
		 */
		void applyRandomMoves(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				final int x = 1 + random.nextInt(this.size);
				int anchor = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(this.size);
				if (anchor == x) {
					anchor = this.pred[x];
				}
				if (anchor == this.pred[x]) {
					continue; // already sits right after the anchor — nothing to do
				}
				move(x, anchor);
			}
		}

		/**
		 * Performs one guaranteed-reordering move: relocate the tail element to the chain head.
		 */
		void forceReorder() {
			int tail = this.head;
			while (tail != 0 && this.succ[tail] != 0) {
				tail = this.succ[tail];
			}
			if (tail != 0 && this.pred[tail] != 0) {
				move(tail, 0);
			}
		}

		/**
		 * Relocates element `x` to sit right after `anchor` (`anchor == 0` promotes it to HEAD), updating the model and
		 * applying the three affected predecessor updates to the index in detach-first order — each a true local move.
		 *
		 * @param x      the element to relocate
		 * @param anchor the anchor after which `x` is inserted (`0` promotes `x` to HEAD)
		 */
		private void move(int x, int anchor) {
			final int pOld = this.pred[x];
			final int sOld = this.succ[x];
			// detach x from its current position
			if (pOld == 0) {
				this.head = sOld;
			} else {
				this.succ[pOld] = sOld;
			}
			if (sOld != 0) {
				this.pred[sOld] = pOld;
			}
			// insert x right after the anchor
			final int sNew = anchor == 0 ? this.head : this.succ[anchor];
			if (anchor == 0) {
				this.head = x;
			} else {
				this.succ[anchor] = x;
			}
			this.pred[x] = anchor;
			this.succ[x] = sNew;
			if (sNew != 0) {
				this.pred[sNew] = x;
			}
			// apply to the index as the three affected predecessor updates
			if (sOld != 0 && sOld != x) {
				this.index.upsertPredecessor(pOld == 0 ? Predecessor.HEAD : new Predecessor(pOld), sOld);
			}
			this.index.upsertPredecessor(anchor == 0 ? Predecessor.HEAD : new Predecessor(anchor), x);
			if (sNew != 0 && sNew != x) {
				this.index.upsertPredecessor(new Predecessor(x), sNew);
			}
		}
	}

}
