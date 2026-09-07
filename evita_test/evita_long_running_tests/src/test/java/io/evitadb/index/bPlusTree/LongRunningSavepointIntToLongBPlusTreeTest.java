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
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized proof that {@link TransactionalIntToLongBPlusTree}'s node layers
 * ({@code BPlusLeafTreeNode} / {@code BPlusInternalTreeNode}) snapshot and restore correctly under a per-entity
 * savepoint. Replicates the proven {@link LongRunningSavepointBucketBPlusTreeTest} pattern for the primitive-`int`
 * key, primitive-`long` value B+ tree.
 *
 * Each generation rebuilds a fresh transactional tree from a random reference, then within one transaction applies a
 * random baseline batch (must survive) and a random in-savepoint batch (must revert on rollback / be kept on commit),
 * driving the tree through insert, overwrite, partial removal and full-drain operations with small block sizes so that
 * node splits and merges happen frequently — exactly the churn that creates transaction-local split/merge nodes and
 * removes replaced node layers. The framework asserts the tree's logical contents (read via the entry iterator)
 * against the oracle captured at savepoint open, then commits the transaction so the layer-sweep verification proves
 * the restore left no dangling or stale layer. A marker key outside the random key range guarantees the in-savepoint
 * batch is never a no-op. The run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("IntToLong B+ tree savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointIntToLongBPlusTreeTest extends AbstractSavepointFuzzTest<TreeMap<Integer, Long>> {
	private static final int KEY_SPACE = 48;
	private static final long VALUE_SPACE = 1_000_000L;
	private static final int MARKER_KEY = KEY_SPACE + 1;

	@Nonnull
	@Override
	protected FuzzGeneration<TreeMap<Integer, Long>> newGeneration(@Nonnull Random random) {
		return new TreeState(random);
	}

	/**
	 * Builds a fresh transactional int-to-long B+ tree (small block sizes to force splits) seeded with random keys.
	 */
	@Nonnull
	private static TransactionalIntToLongBPlusTree newSeededTree(@Nonnull Random random) {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(
			8, 3, 7, 3
		);
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			tree.insert(random.nextInt(KEY_SPACE), nextValue(random));
		}
		return tree;
	}

	/**
	 * Applies `count` randomized operations to the tree within the current transaction: insert a new key, overwrite an
	 * existing key with a new value, or remove an existing key. The current contents are read fresh each op so removals
	 * / overwrites always target a key that exists.
	 */
	private static void applyRandomOps(@Nonnull TransactionalIntToLongBPlusTree tree, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final TreeMap<Integer, Long> contents = readContents(tree);
			final int choice = random.nextInt(3);
			if (contents.isEmpty() || choice == 0) {
				// insert a new key (or overwrite if the key happens to exist)
				tree.insert(random.nextInt(KEY_SPACE), nextValue(random));
			} else if (choice == 1) {
				// overwrite an existing key with a distinct value
				tree.insert(pickKey(contents, random), VALUE_SPACE + nextValue(random));
			} else {
				// remove an existing key
				tree.delete(pickKey(contents, random));
			}
		}
	}

	/**
	 * Reads the tree's logical contents (key → value) into an `.equals`-comparable map via the entry iterator.
	 */
	@Nonnull
	private static TreeMap<Integer, Long> readContents(@Nonnull TransactionalIntToLongBPlusTree tree) {
		final TreeMap<Integer, Long> contents = new TreeMap<>();
		final Iterator<Entry> it = tree.entryIterator();
		while (it.hasNext()) {
			final Entry entry = it.next();
			contents.put(entry.key(), entry.value());
		}
		return contents;
	}

	/**
	 * Produces a random non-negative long value within the value space.
	 */
	private static long nextValue(@Nonnull Random random) {
		return Math.floorMod(random.nextLong(), VALUE_SPACE);
	}

	/**
	 * Picks a random key present in the given contents.
	 */
	private static int pickKey(@Nonnull TreeMap<Integer, Long> contents, @Nonnull Random random) {
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
	 * One generation's fixture: a freshly seeded int-to-long tree, read and mutated through its own public
	 * surface so the harness's mid-savepoint read goes through the same iterator a query would.
	 */
	private static final class TreeState implements FuzzGeneration<TreeMap<Integer, Long>> {
		private final TransactionalIntToLongBPlusTree tree;

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
		public TreeMap<Integer, Long> contents() {
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
			this.tree.insert(MARKER_KEY, Long.MAX_VALUE);
		}
	}

}
