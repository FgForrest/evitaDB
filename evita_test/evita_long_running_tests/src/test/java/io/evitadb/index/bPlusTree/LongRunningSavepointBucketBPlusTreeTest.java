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

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

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
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Bucket B+ tree savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointBucketBPlusTreeTest extends AbstractSavepointFuzzTest<TreeMap<Integer, List<Integer>>> {
	private static final int KEY_SPACE = 48;
	private static final int PK_SPACE = 500;
	private static final int MARKER_KEY = KEY_SPACE + 1;

	@Nonnull
	@Override
	protected FuzzGeneration<TreeMap<Integer, List<Integer>>> newGeneration(@Nonnull Random random) {
		return new TreeState(random);
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

	/**
	 * One generation's fixture: a freshly seeded bucket tree, read and mutated through its own public
	 * surface so the harness's mid-savepoint read goes through the same iterator a query would.
	 */
	private static final class TreeState implements FuzzGeneration<TreeMap<Integer, List<Integer>>> {
		private final TransactionalBucketBPlusTree<Integer> tree;

		TreeState(@Nonnull Random random) {
			this.tree = newSeededTree(random);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.tree;
		}

		@Nonnull
		@Override
		public TreeMap<Integer, List<Integer>> contents() {
			return readContents(this.tree);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomOps(this.tree, random, 1 + random.nextInt(10));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomOps(this.tree, random, 1 + random.nextInt(10));
			// applied LAST: a marker inserted first is a key like any other and a later random op can delete it
			this.tree.addRecord(MARKER_KEY, Integer.MAX_VALUE);
		}
	}

}
