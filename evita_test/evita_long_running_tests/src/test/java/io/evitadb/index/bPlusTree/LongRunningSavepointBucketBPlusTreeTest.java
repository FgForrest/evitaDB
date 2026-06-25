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

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
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
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized proof that {@link TransactionalBucketBPlusTree}'s node layers
 * ({@code BPlusLeafTreeNode} / {@code BPlusInternalTreeNode}) snapshot and restore correctly under a per-entity
 * savepoint. This is a delicate (data-holding, copy-on-write) layer family proven against the savepoint fuzz/oracle
 * framework.
 *
 * Each generation rebuilds a fresh transactional tree from a random reference, then within one transaction applies a
 * random baseline batch (must survive) and a random in-savepoint batch (must revert on rollback / be kept on commit),
 * driving the tree through bucket promotion, growth, partial removal and full-drain (bucket delete) operations —
 * exactly the churn that opens/discards overflow {@link io.evitadb.index.bitmap.TransactionalBitmap} layers and splits
 * / merges nodes. The framework asserts the tree's logical contents (read via {@link BucketCursor}) against the oracle
 * captured at savepoint open, then commits the transaction so the layer-sweep verification proves the restore left no
 * dangling or stale layer. A marker bucket outside the random key range guarantees the in-savepoint batch is never
 * a no-op. The run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Bucket B+ tree savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointBucketBPlusTreeTest implements TimeBoundedTestSupport {
	private static final int KEY_SPACE = 48;
	private static final int PK_SPACE = 500;
	private static final int MARKER_KEY = KEY_SPACE + 1;

	@ParameterizedTest(name = "Bucket B+ tree should survive generational savepoint rollback")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint bucket contents")
	void shouldRollBackBucketTreeToSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			1000,
			0L,
			(random, iteration) -> {
				final TransactionalBucketBPlusTree<Integer> tree = newSeededTree(random);
				assertSavepointRollbackRestores(
					tree,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10)),
					LongRunningSavepointBucketBPlusTreeTest::readContents,
					tested -> {
						// a marker bucket outside the random key range guarantees a non-vacuous in-savepoint batch
						tested.addRecord(MARKER_KEY, Integer.MAX_VALUE);
						applyRandomOps(tested, random, 1 + random.nextInt(10));
					}
				);
				return iteration + 1;
			}
		);
	}

	@ParameterizedTest(name = "Bucket B+ tree should survive generational savepoint commit")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint bucket contents")
	void shouldCommitBucketTreeSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			1000,
			0L,
			(random, iteration) -> {
				final TransactionalBucketBPlusTree<Integer> tree = newSeededTree(random);
				assertSavepointCommitKeeps(
					tree,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10)),
					LongRunningSavepointBucketBPlusTreeTest::readContents,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10))
				);
				return iteration + 1;
			}
		);
	}

	/**
	 * Builds a fresh transactional bucket tree seeded with a random set of single-record buckets.
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> newSeededTree(@Nonnull Random random) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			16, 7, 7, 3, Integer.class, null
		);
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			tree.addRecord(random.nextInt(KEY_SPACE), 1 + random.nextInt(PK_SPACE));
		}
		return tree;
	}

	/**
	 * Applies `count` randomized bucket operations to the tree within the current transaction: insert a new bucket,
	 * grow an existing bucket (single → multi or multi grow), partially remove a record, or fully drain a bucket. The
	 * current contents are read fresh each op so removals always target a record that exists.
	 */
	private static void applyRandomOps(@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final TreeMap<Integer, List<Integer>> contents = readContents(tree);
			final int choice = random.nextInt(4);
			if (contents.isEmpty() || choice == 0) {
				// insert a new single bucket (or grow if the key happens to exist)
				tree.addRecord(random.nextInt(KEY_SPACE), 1 + random.nextInt(PK_SPACE));
			} else if (choice == 1) {
				// grow an existing bucket with a distinct record (promote single → multi, or extend a multi)
				tree.addRecord(pickKey(contents, random), PK_SPACE + 1 + random.nextInt(PK_SPACE));
			} else {
				// remove one existing record — drops the whole bucket when it was the last record
				final int key = pickKey(contents, random);
				final List<Integer> records = contents.get(key);
				tree.removeRecord(key, records.get(random.nextInt(records.size())));
			}
		}
	}

	/**
	 * Reads the tree's logical contents (bucket value → ascending record list) into an `.equals`-comparable map via a
	 * {@link BucketCursor}.
	 */
	@Nonnull
	private static TreeMap<Integer, List<Integer>> readContents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		final TreeMap<Integer, List<Integer>> contents = new TreeMap<>();
		final BucketCursor<Integer> cursor = tree.cursor();
		while (cursor.next()) {
			final int[] array = cursor.records().getArray();
			final List<Integer> records = new ArrayList<>(array.length);
			for (final int value : array) {
				records.add(value);
			}
			contents.put(cursor.value(), records);
		}
		return contents;
	}

	/**
	 * Picks a random bucket value present in the given contents.
	 */
	private static int pickKey(@Nonnull TreeMap<Integer, List<Integer>> contents, @Nonnull Random random) {
		final int index = random.nextInt(contents.size());
		int i = 0;
		for (final Integer key : contents.keySet()) {
			if (i++ == index) {
				return key;
			}
		}
		throw new IllegalStateException("unreachable");
	}

}
