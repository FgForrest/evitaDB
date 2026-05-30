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
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree.Entry;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
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
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link TransactionalLongBPlusTree} keyed by primitive `long` and holding
 * {@link TransactionalBitmap} producer values. Every generation rebuilds a fresh transactional tree from the
 * committed contents of the previous generation, runs a batch of insert / modify / delete operations inside a single
 * transaction (deliberately including the modify-then-delete sequence that stresses the leaf delete-cleanup sweep),
 * commits via {@link io.evitadb.utils.AssertionUtils#assertStateAfterCommit} - which runs the layer sweep
 * verification on every commit - and validates the committed tree against a `TreeMap<Long, int[]>` reference double
 * (RULE-T3). The chained commits exercise the STM sweep across thousands of cycles (INV-10 / INV-12). The seed is
 * printed on failure so a minimal repro can be reconstructed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Transactional long B+ tree (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalLongBPlusTreeTest implements TimeBoundedTestSupport {

	/**
	 * Builds a fresh transactional tree holding a producer-bitmap value per key from the given reference snapshot.
	 *
	 * @param reference the key to bitmap-contents snapshot
	 * @return a tree seeded with the snapshot
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<TransactionalBitmap> buildTree(
		@Nonnull TreeMap<Long, int[]> reference
	) {
		// the committed state of a TransactionalBitmap is a plain Bitmap, so the wrapper must reconstruct a
		// fresh TransactionalBitmap from it on every commit (mirroring how production indexes wrap producer values)
		final TransactionalLongBPlusTree<TransactionalBitmap> tree = new TransactionalLongBPlusTree<>(
			TransactionalBitmap.class,
			o -> o instanceof TransactionalBitmap transactionalBitmap
				? transactionalBitmap
				: new TransactionalBitmap((Bitmap) o)
		);
		for (final Map.Entry<Long, int[]> entry : reference.entrySet()) {
			tree.insert(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		return tree;
	}

	/**
	 * Verifies the committed tree matches the reference double exactly - both in key order and per-key bitmap
	 * contents - and reports a CONSISTENT internal state.
	 *
	 * @param tree      the committed tree
	 * @param reference the expected key to bitmap-contents snapshot
	 */
	private static void verifyTreeMatchesReference(
		@Nonnull TransactionalLongBPlusTree<TransactionalBitmap> tree,
		@Nonnull TreeMap<Long, int[]> reference
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		assertEquals(reference.size(), tree.size(), "Size mismatch between tree and reference!");

		final Iterator<Map.Entry<Long, int[]>> referenceIt = reference.entrySet().iterator();
		final Iterator<Entry<TransactionalBitmap>> treeIt = tree.entryIterator();
		while (referenceIt.hasNext()) {
			final Map.Entry<Long, int[]> referenceEntry = referenceIt.next();
			final Entry<TransactionalBitmap> treeEntry = treeIt.next();
			assertEquals(
				referenceEntry.getKey().longValue(), treeEntry.key(),
				"Key order mismatch between tree and reference!"
			);
			assertArrayEquals(
				referenceEntry.getValue(), treeEntry.value().getArray(),
				"Bitmap contents mismatch for key " + referenceEntry.getKey() + "!"
			);
		}
	}

	@ParameterizedTest(
		name = "TransactionalLongBPlusTree should survive generational randomized test applying modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized insert/modify/delete operations sweeping producer values cleanly")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int limitElements = 1000;
		final long seed = input.randomSeed();
		// print the seed so a failing run can be reproduced deterministically
		System.out.println("LongRunningTransactionalLongBPlusTreeTest seed: " + seed);

		final TreeMap<Long, int[]> initialReference = new TreeMap<>();
		final Random seedRandom = new Random(seed);
		do {
			final long key = seedRandom.nextInt(limitElements << 1);
			initialReference.put(key, new int[]{(int) key});
		} while (initialReference.size() < limitElements);

		runFor(
			input,
			1000,
			new TestState(new StringBuilder(512), initialReference, true),
			(random, testState) -> {
				final TreeMap<Long, int[]> reference = new TreeMap<>();
				for (final Map.Entry<Long, int[]> entry : testState.reference().entrySet()) {
					reference.put(entry.getKey(), entry.getValue().clone());
				}
				final TransactionalLongBPlusTree<TransactionalBitmap> tree = buildTree(reference);
				verifyTreeMatchesReference(tree, reference);

				final AtomicReference<TreeMap<Long, int[]>> committedReference = new AtomicReference<>();
				final StringBuilder code = testState.code();
				code.setLength(0);

				try {
					assertStateAfterCommit(
						tree,
						original -> {
							final int operations = 1 + random.nextInt(6);
							for (int op = 0; op < operations; op++) {
								final boolean delete =
									(reference.size() > 0 && random.nextInt(3) == 0)
										|| (testState.limitReached() && reference.size() > limitElements / 2);
								if (delete) {
									final Long key = pickRandomKey(reference, random);
									// modify-then-delete in the same transaction stresses the sweep: the
									// value's inner bitmap layer is opened, then the whole value is dropped
									original.search(key).orElseThrow().add((int) (key + 1));
									original.delete(key);
									reference.remove(key);
									code.append("D:").append(key).append(' ');
								} else {
									final long key = random.nextInt(limitElements << 1);
									final int[] existing = reference.get(key);
									if (existing == null) {
										original.insert(key, new TransactionalBitmap(new int[]{(int) key}));
										reference.put(key, new int[]{(int) key});
										code.append("I:").append(key).append(' ');
									} else {
										// mutate the existing producer value in place (mutate-and-keep-instance)
										final int extra = (int) (limitElements * 2 + key);
										original.search(key).orElseThrow().add(extra);
										reference.put(
											key,
											io.evitadb.utils.ArrayUtils.insertIntIntoOrderedArray(extra, existing)
										);
										code.append("M:").append(key).append(' ');
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

				final TreeMap<Long, int[]> nextReference = committedReference.get();
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

	/**
	 * Picks a random key present in the reference double.
	 *
	 * @param reference the reference double
	 * @param random    the randomizer
	 * @return a key that currently exists in the reference
	 */
	@Nonnull
	private static Long pickRandomKey(@Nonnull TreeMap<Long, int[]> reference, @Nonnull Random random) {
		final int index = random.nextInt(reference.size());
		final Iterator<Long> it = reference.keySet().iterator();
		Long key = null;
		for (int i = 0; i <= index; i++) {
			key = it.next();
		}
		return key;
	}

	/**
	 * Carries the chained generation state: the running operation log, the reference snapshot of the committed tree
	 * and whether the element-count growth limit has been reached.
	 *
	 * @param code          the running operation log used for failure reproduction
	 * @param reference     the committed key to bitmap-contents snapshot fed to the next generation
	 * @param limitReached  whether the growth limit has been reached (switches the churn to delete-biased)
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull TreeMap<Long, int[]> reference,
		boolean limitReached
	) {
	}
}
