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
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
 * The impact proofs drive the tree in its impact-carrying mode, the one the fulltext term dictionary runs in, keyed by
 * strings as the dictionary is. Every record carries an impact byte, and the impact column stores it in the shape of
 * the bucket: one byte beside a single record, an array beside a small bucket, a chunk per bitmap container beside a
 * bitmap bucket. The n-th impact must stay with the n-th record through every insert, removal, split, merge, bucket
 * promotion and demotion, and commit. A few hot buckets random-walk across both bucket thresholds
 * ({@link OverflowRecords#SMALL_BUCKET_THRESHOLD} and its demotion half), and their records span three bitmap
 * containers, so the chunked form is exercised with more than one chunk. The impacts are checked twice per bucket,
 * through the cursor's view and through a descent.
 *
 * Calibrated on 2026-10-07: leaving the chunk offset unreset when `ImpactRecords#align` steps into the next bitmap
 * container failed the transactional impact proof in 0.04 s, while the two record-only proofs stayed green. Whoever
 * next changes how the impact column is aligned, chunked or merged owes this test that check again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Transactional bucket B+ tree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalBucketBPlusTreeTest implements TimeBoundedTestSupport {
	/**
	 * The keys of the impact proofs, zero-padded so their string order is their numeric order.
	 */
	private static final String[] IMPACT_KEYS = impactKeys(48);
	/**
	 * How many of the first {@link #IMPACT_KEYS} are hot - they get one operation in four between them, and take
	 * many records per operation, so their buckets cross the bucket thresholds.
	 */
	private static final int HOT_IMPACT_KEYS = 4;
	/**
	 * How many bitmap containers the record ids of the impact proofs span.
	 */
	private static final int IMPACT_CONTAINERS = 3;
	/**
	 * How many record ids of each container the impact proofs draw from.
	 */
	private static final int IMPACT_RECORDS_PER_CONTAINER = 120;

	/**
	 * Builds the keys of the impact proofs.
	 *
	 * @param count how many keys to build
	 * @return the keys, in their natural order
	 */
	@Nonnull
	private static String[] impactKeys(int count) {
		final String[] keys = new String[count];
		for (int i = 0; i < count; i++) {
			keys[i] = (i < 10 ? "term0" : "term") + i;
		}
		return keys;
	}

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

	@ParameterizedTest(
		name = "TransactionalBucketBPlusTree should keep every impact with its record across chained commits"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("keeps impacts aligned with records through randomized churn across chained commits")
	void generationalImpactProofTest(@Nonnull GenerationalTestInput input) {
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalBucketBPlusTreeTest (impacts) seed: " + seed);

		runFor(
			input,
			1000,
			new ImpactState(new StringBuilder(512), new TreeMap<>(), newImpactTree()),
			(random, state) -> {
				final TreeMap<String, TreeMap<Integer, Integer>> before = state.reference();
				final TreeMap<String, TreeMap<Integer, Integer>> reference = deepCopy(before);
				final StringBuilder code = state.code();
				code.setLength(0);
				final AtomicReference<TransactionalBucketBPlusTree<String>> published = new AtomicReference<>();

				try {
					assertStateAfterCommit(
						state.tree(),
						original -> {
							final int operations = 1 + random.nextInt(8);
							for (int op = 0; op < operations; op++) {
								applyRandomImpactOperation(random, original, reference, code);
							}
						},
						(original, committed) -> {
							final TransactionalBucketBPlusTree<String> result =
								committed == null ? original : committed;
							if (result == original) {
								// carried forward as the same instance, legal only when nothing changed
								assertEquals(
									before, reference, "No new version was published, yet the content changed"
								);
							}
							verifyImpactTreeMatchesReference(result, reference);
							// the previous version shares leaves and buckets with the published one, so an impact
							// written in place shows up here
							verifyImpactTreeMatchesReference(original, before);
							published.set(result);
						}
					);
				} catch (Exception ex) {
					fail("Generation failed for seed " + seed + " with operations [" + code + "]", ex);
					throw ex;
				}

				return new ImpactState(code, reference, published.get());
			}
		);
	}

	@ParameterizedTest(
		name = "TransactionalBucketBPlusTree should keep every impact with its record through non-transactional churn"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("keeps impacts aligned with records through randomized non-transactional (warm-up) churn")
	void generationalImpactWarmUpProofTest(@Nonnull GenerationalTestInput input) {
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalBucketBPlusTreeTest (impacts, warm-up) seed: " + seed);

		final TransactionalBucketBPlusTree<String> tree = newImpactTree();
		final TreeMap<String, TreeMap<Integer, Integer>> reference = new TreeMap<>();
		runFor(
			input, 1000, new StringBuilder(512),
			(random, code) -> {
				code.setLength(0);
				try {
					final int operations = 1 + random.nextInt(8);
					for (int op = 0; op < operations; op++) {
						applyRandomImpactOperation(random, tree, reference, code);
					}
					verifyImpactTreeMatchesReference(tree, reference);
				} catch (Exception ex) {
					fail("Generation failed for seed " + seed + " with operations [" + code + "]", ex);
					throw ex;
				}
				return code;
			}
		);
	}

	/**
	 * Creates an empty impact-carrying tree with string keys, at a small block size so splits and merges are dense.
	 *
	 * @return the tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<String> newImpactTree() {
		final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
			16, 7, 7, 3, String.class, null
		);
		tree.enableImpacts();
		return tree;
	}

	/**
	 * Applies one random operation to the impact-carrying tree and to the reference alike: adding records with their
	 * impacts (a record the bucket already holds gets the new impact), re-impacting an existing record, removing some
	 * records, or draining the whole bucket. A hot bucket takes up to forty records per operation and leans towards
	 * growing while small and towards shrinking while large, so it keeps crossing both bucket thresholds.
	 *
	 * @param random    the source of randomness
	 * @param tree      the tree under test
	 * @param reference key to record to impact; updated alongside the tree
	 * @param code      receives a short description of the operation
	 */
	private static void applyRandomImpactOperation(
		@Nonnull Random random,
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		@Nonnull TreeMap<String, TreeMap<Integer, Integer>> reference,
		@Nonnull StringBuilder code
	) {
		final boolean hot = random.nextInt(4) == 0;
		final String key = IMPACT_KEYS[random.nextInt(hot ? HOT_IMPACT_KEYS : IMPACT_KEYS.length)];
		final TreeMap<Integer, Integer> bucket = reference.get(key);
		final int size = bucket == null ? 0 : bucket.size();
		final int growPercent = growPercentOf(hot, size);
		final int dice = random.nextInt(100);
		if (bucket == null || dice < growPercent) {
			final TreeMap<Integer, Integer> target = bucket == null ? new TreeMap<>() : bucket;
			final int count = hot ? 1 + random.nextInt(40) : 1;
			for (int i = 0; i < count; i++) {
				final int record = randomImpactRecord(random);
				final int impact = 1 + random.nextInt(255);
				tree.addRecord(key, record, (byte) impact);
				target.put(record, impact);
			}
			reference.put(key, target);
			code.append("A:").append(key).append('x').append(count).append(' ');
		} else if (dice < growPercent + 10) {
			// a new impact for a record the bucket holds replaces the old one in place of the record
			final int record = pickFromSet(bucket.keySet(), random);
			final int impact = 1 + random.nextInt(255);
			tree.addRecord(key, record, (byte) impact);
			bucket.put(record, impact);
			code.append("U:").append(key).append(':').append(record).append(' ');
		} else if (dice >= 95) {
			tree.removeRecord(key, toArray(bucket.keySet()));
			reference.remove(key);
			code.append("D:").append(key).append(' ');
		} else {
			final int count = Math.min(bucket.size(), hot ? 1 + random.nextInt(40) : 1);
			final TreeSet<Integer> victims = new TreeSet<>();
			while (victims.size() < count) {
				victims.add(pickFromSet(bucket.keySet(), random));
			}
			tree.removeRecord(key, toArray(victims));
			bucket.keySet().removeAll(victims);
			if (bucket.isEmpty()) {
				reference.remove(key);
			}
			code.append("R:").append(key).append('x').append(count).append(' ');
		}
	}

	/**
	 * Draws a record id from one of three bitmap containers.
	 *
	 * @param random the source of randomness
	 * @return the record id
	 */
	private static int randomImpactRecord(@Nonnull Random random) {
		return random.nextInt(IMPACT_CONTAINERS) * 65_536 + random.nextInt(IMPACT_RECORDS_PER_CONTAINER);
	}

	/**
	 * Verifies the tree holds exactly the reference: bucket count, key order, every bucket's records, and every
	 * bucket's impacts in record order, read both through the cursor and through a descent. The tree must also report
	 * a CONSISTENT internal state.
	 *
	 * @param tree      the tree to check
	 * @param reference key to record to impact
	 */
	private static void verifyImpactTreeMatchesReference(
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		@Nonnull TreeMap<String, TreeMap<Integer, Integer>> reference
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		assertEquals(reference.size(), tree.bucketCount(), "Bucket count mismatch between tree and reference!");

		int totalRecords = 0;
		final BucketCursor<String> cursor = tree.cursor();
		for (final Map.Entry<String, TreeMap<Integer, Integer>> entry : reference.entrySet()) {
			final String key = entry.getKey();
			assertTrue(cursor.next(), "Tree exposes fewer buckets than the reference!");
			assertEquals(key, cursor.value(), "Key order mismatch between tree and reference!");
			assertArrayEquals(
				toArray(entry.getValue().keySet()), cursor.records().getArray(), "Record set mismatch for " + key + "!"
			);
			final byte[] impacts = new byte[entry.getValue().size()];
			int index = 0;
			for (final Integer impact : entry.getValue().values()) {
				impacts[index++] = (byte) impact.intValue();
			}
			assertArrayEquals(impacts, cursor.impacts().toArray(), "Impacts read by the cursor for " + key + "!");
			assertArrayEquals(impacts, tree.impactsOf(key), "Impacts read by a descent for " + key + "!");
			totalRecords += impacts.length;
		}
		assertFalse(cursor.next(), "Tree exposes more buckets than the reference!");
		assertEquals(totalRecords, tree.recordCount(), "Total record count mismatch between tree and reference!");
	}

	/**
	 * Copies the reference deeply, so the copy can be changed while the original keeps describing the version a
	 * transaction started from.
	 *
	 * @param reference key to record to impact
	 * @return an independent copy
	 */
	@Nonnull
	private static TreeMap<String, TreeMap<Integer, Integer>> deepCopy(
		@Nonnull TreeMap<String, TreeMap<Integer, Integer>> reference
	) {
		final TreeMap<String, TreeMap<Integer, Integer>> copy = new TreeMap<>();
		for (final Map.Entry<String, TreeMap<Integer, Integer>> entry : reference.entrySet()) {
			copy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * Returns the percentage of impact operations that add records: an even split for a cold bucket, while a hot one
	 * leans towards growing while small and towards shrinking while large.
	 *
	 * @param hot  whether the operation targets a hot bucket
	 * @param size the number of records the bucket holds
	 * @return the chance of an add, in percent
	 */
	private static int growPercentOf(boolean hot, int size) {
		if (!hot) {
			return 50;
		}
		if (size < 40) {
			return 70;
		}
		if (size > 200) {
			return 30;
		}
		return 50;
	}

	/**
	 * Converts an ascending collection of record ids into a primitive array.
	 *
	 * @param records the record ids, ascending
	 * @return the record ids as an array
	 */
	@Nonnull
	private static int[] toArray(@Nonnull Collection<Integer> records) {
		final int[] array = new int[records.size()];
		int index = 0;
		for (final Integer value : records) {
			array[index++] = value;
		}
		return array;
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
	 * Picks a random record id present in the given set. The pick depends on the set's iteration order, so callers pass
	 * sorted sets - a `TreeSet` or a `TreeMap` key set - to keep seeded runs reproducible.
	 *
	 * @param set    the record set, sorted
	 * @param random the randomizer
	 * @return a record id that currently exists in the set
	 */
	private static int pickFromSet(@Nonnull Set<Integer> set, @Nonnull Random random) {
		final int index = random.nextInt(set.size());
		final Iterator<Integer> it = set.iterator();
		int value = 0;
		for (int i = 0; i <= index; i++) {
			value = it.next();
		}
		return value;
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

	/**
	 * Carries the chained state of the transactional impact proof.
	 *
	 * @param code      the operation log of the current generation, for the failure report
	 * @param reference key to record to impact, as last published
	 * @param tree      the last published tree
	 */
	private record ImpactState(
		@Nonnull StringBuilder code,
		@Nonnull TreeMap<String, TreeMap<Integer, Integer>> reference,
		@Nonnull TransactionalBucketBPlusTree<String> tree
	) {
	}
}
