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
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link TransactionalBucketBPlusTree} - the columnar bucket store backing the
 * inverted index. Each generation rebuilds a fresh transactional tree from the committed contents of the previous
 * generation, runs a batch of insert / grow / partial-remove / drain operations inside a single transaction and
 * commits via {@link io.evitadb.utils.AssertionUtils#assertStateAfterCommit} - which runs the transactional-layer
 * sweep verification on every commit - then validates the committed tree against a `TreeMap<Integer, TreeSet<Integer>>`
 * reference double (value to record-set).
 *
 * The churn deliberately includes the **promote-then-drain** sequence in a single transaction: a bucket is grown so
 * its lazy overflow {@link io.evitadb.index.bitmap.TransactionalBitmap} layer is opened, then every record is removed so
 * the whole bucket is deleted - exercising the `discardRemovedValueLayer` release that prevents the dropped bitmap
 * layer from being reported as stale at commit. Chaining the committed output of each generation into the next
 * accumulates any layer-sweep, split/merge or column-alignment error over thousands of commit cycles. The seed is
 * printed on failure so a minimal reproduction can be reconstructed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Transactional bucket B+ tree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalBucketBPlusTreeTest implements TimeBoundedTestSupport {

	/**
	 * Builds a fresh transactional bucket tree from the given reference snapshot, one bucket per key holding that
	 * key's record set (a single-element set lands as a compact single bucket, a larger set as an overflow bucket).
	 *
	 * @param reference the value to record-set snapshot
	 * @return a tree seeded with the snapshot
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> buildTree(@Nonnull TreeMap<Integer, TreeSet<Integer>> reference) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			16, 7, 7, 3, Integer.class, null
		);
		for (final Map.Entry<Integer, TreeSet<Integer>> entry : reference.entrySet()) {
			tree.addRecord(entry.getKey(), toArray(entry.getValue()));
		}
		return tree;
	}

	/**
	 * Verifies the committed tree matches the reference double exactly - bucket count, key order, per-bucket record set
	 * and total record count - and reports a CONSISTENT internal state.
	 *
	 * @param tree      the committed tree
	 * @param reference the expected value to record-set snapshot
	 */
	private static void verifyTreeMatchesReference(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree,
		@Nonnull TreeMap<Integer, TreeSet<Integer>> reference
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		assertEquals(reference.size(), tree.bucketCount(), "Bucket count mismatch between tree and reference!");

		int totalRecords = 0;
		final Iterator<Map.Entry<Integer, TreeSet<Integer>>> referenceIt = reference.entrySet().iterator();
		final BucketCursor<Integer> cursor = tree.cursor();
		while (referenceIt.hasNext()) {
			final Map.Entry<Integer, TreeSet<Integer>> referenceEntry = referenceIt.next();
			assertTrue(cursor.next(), "Tree exposes fewer buckets than the reference!");
			assertEquals(
				referenceEntry.getKey().intValue(), cursor.value().intValue(),
				"Key order mismatch between tree and reference!"
			);
			assertArrayEquals(
				toArray(referenceEntry.getValue()), cursor.records().getArray(),
				"Record set mismatch for value " + referenceEntry.getKey() + "!"
			);
			totalRecords += referenceEntry.getValue().size();
		}
		assertFalse(cursor.next(), "Tree exposes more buckets than the reference!");
		assertEquals(totalRecords, tree.recordCount(), "Total record count mismatch between tree and reference!");
	}

	@ParameterizedTest(
		name = "TransactionalBucketBPlusTree should survive generational randomized test applying modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized insert/grow/remove/drain operations sweeping overflow bitmap layers cleanly")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalBucketBPlusTreeTest seed: " + seed);

		final TreeMap<Integer, TreeSet<Integer>> initialReference = new TreeMap<>();
		final Random seedRandom = new Random(seed);
		do {
			final int key = seedRandom.nextInt(limitElements << 1);
			final TreeSet<Integer> records = new TreeSet<>();
			records.add(key);
			initialReference.put(key, records);
		} while (initialReference.size() < limitElements);

		runFor(
			input,
			1000,
			new TestState(new StringBuilder(512), initialReference, true),
			(random, testState) -> {
				// deep-clone the previous generation's committed snapshot so tree and reference move in lockstep
				final TreeMap<Integer, TreeSet<Integer>> reference = new TreeMap<>();
				for (final Map.Entry<Integer, TreeSet<Integer>> entry : testState.reference().entrySet()) {
					reference.put(entry.getKey(), new TreeSet<>(entry.getValue()));
				}
				final TransactionalBucketBPlusTree<Integer> tree = buildTree(reference);
				verifyTreeMatchesReference(tree, reference);

				final AtomicReference<TreeMap<Integer, TreeSet<Integer>>> committedReference = new AtomicReference<>();
				final StringBuilder code = testState.code();
				code.setLength(0);

				try {
					assertStateAfterCommit(
						tree,
						original -> {
							final int operations = 1 + random.nextInt(6);
							for (int op = 0; op < operations; op++) {
								final boolean drain =
									(!reference.isEmpty() && random.nextInt(3) == 0)
										|| (testState.limitReached() && reference.size() > limitElements / 2);
								if (drain) {
									// promote-then-drain in the same transaction: open the overflow bitmap layer by
									// growing the bucket, then remove every record so the bucket is deleted - stressing
									// the discardRemovedValueLayer release
									final Integer key = pickRandomKey(reference, random);
									final int probe = (limitElements << 1) + key;
									original.addRecord(key, probe);
									final TreeSet<Integer> drainSet = new TreeSet<>(reference.get(key));
									drainSet.add(probe);
									original.removeRecord(key, toArray(drainSet));
									reference.remove(key);
									code.append("D:").append(key).append(' ');
								} else {
									final int key = random.nextInt(limitElements << 1);
									final TreeSet<Integer> existing = reference.get(key);
									if (existing == null) {
										// insert a new bucket - single or multi from the start
										if (random.nextBoolean()) {
											original.addRecord(key, key);
											final TreeSet<Integer> records = new TreeSet<>();
											records.add(key);
											reference.put(key, records);
											code.append("I:").append(key).append(' ');
										} else {
											final int second = (limitElements << 1) + key;
											original.addRecord(key, key, second);
											final TreeSet<Integer> records = new TreeSet<>();
											records.add(key);
											records.add(second);
											reference.put(key, records);
											code.append("I2:").append(key).append(' ');
										}
									} else {
										final int choice = random.nextInt(3);
										if (choice == 0) {
											// grow: add a distinct record (single->multi promote or multi grow);
											// a duplicate is deduped on both sides and stays consistent
											final int extra = (limitElements << 1) + random.nextInt(limitElements << 2);
											original.addRecord(key, extra);
											existing.add(extra);
											code.append("M:").append(key).append(':').append(extra).append(' ');
										} else if (existing.size() > 1) {
											// partial remove: drop one record, the bucket survives
											final int victim = pickFromSet(existing, random);
											original.removeRecord(key, victim);
											existing.remove(victim);
											code.append("R:").append(key).append(':').append(victim).append(' ');
										} else {
											// remove the sole record - the bucket is deleted
											final int sole = existing.first();
											original.removeRecord(key, sole);
											reference.remove(key);
											code.append("R0:").append(key).append(' ');
										}
									}
								}
							}
						},
						(original, committed) -> {
							verifyTreeMatchesReference(committed, reference);
							committedReference.set(reference);
						}
					);
				} catch (Exception ex) {
					fail(
						"Generation failed for seed " + seed + " with operations [" + code + "]",
						ex
					);
					throw ex;
				}

				final TreeMap<Integer, TreeSet<Integer>> nextReference = committedReference.get();
				return new TestState(
					testState.code(),
					nextReference,
					testState.limitReached()
						? nextReference.size() > limitElements / 2
						: nextReference.size() >= limitElements
				);
			}
		);
	}

	@ParameterizedTest(
		name = "TransactionalBucketBPlusTree should survive non-transactional generational churn on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized non-transactional (warm-up) insert/grow/remove/drain churn")
	void generationalWarmUpProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalBucketBPlusTreeTest (warm-up) seed: " + seed);

		// one long-lived bare tree churned directly (no transaction) at a small block size so splits / merges are dense
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		final TreeMap<Integer, TreeSet<Integer>> reference = new TreeMap<>();
		final Random seedRandom = new Random(seed);
		do {
			final int key = seedRandom.nextInt(limitElements << 1);
			if (!reference.containsKey(key)) {
				final TreeSet<Integer> records = new TreeSet<>();
				records.add(key);
				reference.put(key, records);
				tree.addRecord(key, key);
			}
		} while (reference.size() < limitElements);
		verifyTreeMatchesReference(tree, reference);

		runFor(
			input, 1000, new WarmUpState(new StringBuilder(512), true),
			(random, state) -> {
				final StringBuilder code = state.code();
				code.setLength(0);
				try {
					final boolean drain =
						(!reference.isEmpty() && random.nextInt(3) == 0)
							|| (state.limitReached() && reference.size() > limitElements / 2);
					if (drain) {
						// promote-then-drain in one shot: grow the bucket so its overflow bitmap layer is opened, then
						// remove every record so the bucket is deleted - stressing the discardRemovedValueLayer release
						final Integer key = pickRandomKey(reference, random);
						final int probe = (limitElements << 1) + key;
						tree.addRecord(key, probe);
						final TreeSet<Integer> drainSet = new TreeSet<>(reference.get(key));
						drainSet.add(probe);
						tree.removeRecord(key, toArray(drainSet));
						reference.remove(key);
						code.append("D:").append(key).append(' ');
					} else {
						final int key = random.nextInt(limitElements << 1);
						final TreeSet<Integer> existing = reference.get(key);
						if (existing == null) {
							// insert a new bucket - single or multi from the start
							if (random.nextBoolean()) {
								tree.addRecord(key, key);
								final TreeSet<Integer> records = new TreeSet<>();
								records.add(key);
								reference.put(key, records);
								code.append("I:").append(key).append(' ');
							} else {
								final int second = (limitElements << 1) + key;
								tree.addRecord(key, key, second);
								final TreeSet<Integer> records = new TreeSet<>();
								records.add(key);
								records.add(second);
								reference.put(key, records);
								code.append("I2:").append(key).append(' ');
							}
						} else {
							final int choice = random.nextInt(3);
							if (choice == 0) {
								// grow: add a distinct record (single->multi promote or multi grow); a duplicate is
								// deduped on both sides and stays consistent
								final int extra = (limitElements << 1) + random.nextInt(limitElements << 2);
								tree.addRecord(key, extra);
								existing.add(extra);
								code.append("M:").append(key).append(':').append(extra).append(' ');
							} else if (existing.size() > 1) {
								// partial remove: drop one record, the bucket survives
								final int victim = pickFromSet(existing, random);
								tree.removeRecord(key, victim);
								existing.remove(victim);
								code.append("R:").append(key).append(':').append(victim).append(' ');
							} else {
								// remove the sole record - the bucket is deleted
								final int sole = existing.first();
								tree.removeRecord(key, sole);
								reference.remove(key);
								code.append("R0:").append(key).append(' ');
							}
						}
					}

					verifyTreeMatchesReference(tree, reference);

					return new WarmUpState(
						state.code(),
						state.limitReached()
							? reference.size() > limitElements / 2
							: reference.size() >= limitElements
					);
				} catch (Exception ex) {
					fail("Generation failed for seed " + seed + " with operation [" + code + "]", ex);
					throw ex;
				}
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
	@Nonnull
	private static Integer pickRandomKey(@Nonnull TreeMap<Integer, TreeSet<Integer>> reference, @Nonnull Random random) {
		final int index = random.nextInt(reference.size());
		final Iterator<Integer> it = reference.keySet().iterator();
		Integer key = null;
		for (int i = 0; i <= index; i++) {
			key = it.next();
		}
		return key;
	}

	/**
	 * Picks a random record id present in the given set.
	 *
	 * @param set    the record set
	 * @param random the randomizer
	 * @return a record id that currently exists in the set
	 */
	private static int pickFromSet(@Nonnull TreeSet<Integer> set, @Nonnull Random random) {
		final int index = random.nextInt(set.size());
		final Iterator<Integer> it = set.iterator();
		int value = 0;
		for (int i = 0; i <= index; i++) {
			value = it.next();
		}
		return value;
	}

	/**
	 * Converts an ordered set of record ids into a sorted primitive array.
	 *
	 * @param set the record set
	 * @return the ascending record ids
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
	 * Carries the chained generation state: the running operation log, the reference snapshot of the committed tree
	 * and whether the bucket-count growth limit has been reached.
	 *
	 * @param code         the running operation log used for failure reproduction
	 * @param reference    the committed value to record-set snapshot fed to the next generation
	 * @param limitReached whether the growth limit has been reached (switches the churn to delete-biased)
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull TreeMap<Integer, TreeSet<Integer>> reference,
		boolean limitReached
	) {
	}

	/**
	 * Carries the chained warm-up generation state for the non-transactional axis: the running operation log (reset to
	 * the single current op each generation) and whether the bucket-count growth limit has been reached. The tree and
	 * its reference double are captured directly and mutated in place across generations.
	 *
	 * @param code         the current operation log used for failure reproduction
	 * @param limitReached whether the growth limit has been reached (switches the churn to drain-biased)
	 */
	private record WarmUpState(
		@Nonnull StringBuilder code,
		boolean limitReached
	) {
	}
}
