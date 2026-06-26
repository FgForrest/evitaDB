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

import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.Entry;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized proof that {@link TransactionalObjectBPlusTree}'s node layers
 * ({@code BPlusLeafTreeNode} / {@code BPlusInternalTreeNode}) snapshot and restore correctly under a per-entity
 * savepoint. Replicates the proven {@link LongRunningSavepointBucketBPlusTreeTest} pattern for the generic
 * object-key, object-value B+ tree.
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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Object B+ tree savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointObjectBPlusTreeTest implements TimeBoundedTestSupport {
	private static final int KEY_SPACE = 48;
	private static final int VALUE_SPACE = 1000;
	private static final int MARKER_KEY = KEY_SPACE + 1;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint tree contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint tree contents")
	void shouldRollBackObjectTreeToSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			1000,
			0L,
			(random, iteration) -> {
				final TransactionalObjectBPlusTree<Integer, Integer> tree = newSeededTree(random);
				assertSavepointRollbackRestores(
					tree,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10)),
					LongRunningSavepointObjectBPlusTreeTest::readContents,
					tested -> {
						// a marker key outside the random key range guarantees a non-vacuous in-savepoint batch
						tested.insert(MARKER_KEY, Integer.MAX_VALUE);
						applyRandomOps(tested, random, 1 + random.nextInt(10));
					}
				);
				return iteration + 1;
			}
		);
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint tree contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint tree contents")
	void shouldCommitObjectTreeSavepoint(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			1000,
			0L,
			(random, iteration) -> {
				final TransactionalObjectBPlusTree<Integer, Integer> tree = newSeededTree(random);
				assertSavepointCommitKeeps(
					tree,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10)),
					LongRunningSavepointObjectBPlusTreeTest::readContents,
					tested -> applyRandomOps(tested, random, 1 + random.nextInt(10))
				);
				return iteration + 1;
			}
		);
	}

	/**
	 * Builds a fresh transactional object B+ tree (small block sizes to force splits) seeded with random keys.
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, Integer> newSeededTree(@Nonnull Random random) {
		final TransactionalObjectBPlusTree<Integer, Integer> tree = new TransactionalObjectBPlusTree<>(
			8, 3, 7, 3, Integer.class, Integer.class
		);
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			tree.insert(random.nextInt(KEY_SPACE), random.nextInt(VALUE_SPACE));
		}
		return tree;
	}

	/**
	 * Applies `count` randomized operations to the tree within the current transaction: insert a new key, overwrite an
	 * existing key with a new value, or remove an existing key. The current contents are read fresh each op so removals
	 * / overwrites always target a key that exists.
	 */
	private static void applyRandomOps(@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			final TreeMap<Integer, Integer> contents = readContents(tree);
			final int choice = random.nextInt(3);
			if (contents.isEmpty() || choice == 0) {
				// insert a new key (or overwrite if the key happens to exist)
				tree.insert(random.nextInt(KEY_SPACE), random.nextInt(VALUE_SPACE));
			} else if (choice == 1) {
				// overwrite an existing key with a distinct value
				tree.insert(pickKey(contents, random), VALUE_SPACE + random.nextInt(VALUE_SPACE));
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
	private static TreeMap<Integer, Integer> readContents(@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree) {
		final TreeMap<Integer, Integer> contents = new TreeMap<>();
		final Iterator<Entry<Integer, Integer>> it = tree.entryIterator();
		while (it.hasNext()) {
			final Entry<Integer, Integer> entry = it.next();
			contents.put(entry.key(), entry.value());
		}
		return contents;
	}

	/**
	 * Picks a random key present in the given contents.
	 */
	private static int pickKey(@Nonnull TreeMap<Integer, Integer> contents, @Nonnull Random random) {
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
