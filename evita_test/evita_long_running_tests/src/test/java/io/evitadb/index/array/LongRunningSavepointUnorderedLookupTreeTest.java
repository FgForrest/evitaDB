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

package io.evitadb.index.array;

import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized proof that {@link UnorderedLookupTree}'s node layers ({@code LeafNode} container /
 * {@code InternalNode}) snapshot and restore correctly under a per-entity savepoint. Replicates the proven
 * {@link io.evitadb.index.bPlusTree.LongRunningSavepointBucketBPlusTreeTest} pattern for the order-key lookup tree.
 *
 * Each generation rebuilds a fresh transactional tree from a random reference, then within one transaction applies a
 * random baseline batch (must survive) and a random in-savepoint batch (must revert on rollback / be kept on commit),
 * driving the tree through positional insert and order-key removal with a small block size so that container splits,
 * steals and merges happen frequently — exactly the churn that creates transaction-local split offspring and removes
 * replaced node layers. A non-transactional `recordId → orderKey` index (the role the value index plays in production)
 * is kept coherent through an {@link OrderKeyConsumer} so removals can address records by their current order-key. The
 * framework asserts the tree's logical contents (read via {@link UnorderedLookupTree#getArray()}) against the oracle
 * captured at savepoint open, then commits the transaction so the layer-sweep verification proves the restore left no
 * dangling or stale layer. A marker record guarantees the in-savepoint batch is never a no-op. The run is time-bounded;
 * the random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Unordered lookup tree savepoint rollback/commit (generational fuzz)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningSavepointUnorderedLookupTreeTest extends AbstractSavepointFuzzTest<List<Integer>> {
	private static final int RECORD_SPACE = 48;
	private static final int BLOCK_SIZE = 4;
	private static final long ORDER_KEY_GAP = 1L << 40;

	@Nonnull
	@Override
	protected FuzzGeneration<List<Integer>> newGeneration(@Nonnull Random random) {
		return newSeededDriver(random);
	}

	/**
	 * Builds a fresh driver (tree + value index) seeded with a random number of records (outside any transaction).
	 */
	@Nonnull
	private static Driver newSeededDriver(@Nonnull Random random) {
		final Driver driver = new Driver();
		final int size = random.nextInt(RECORD_SPACE);
		for (int i = 0; i < size; i++) {
			driver.insertAt(driver.size() == 0 ? 0 : random.nextInt(driver.size() + 1), driver.nextRecordId());
		}
		return driver;
	}

	/**
	 * Bundles the lookup tree with a stand-in `recordId → orderKey` value index (the role the real value index plays
	 * in production) and mints unique record ids. The value index is plain (non-transactional) — it only needs to be
	 * coherent while ops are being driven; the framework reads the tree (not the index) after rollback / commit.
	 */
	private static final class Driver implements OrderKeyConsumer, FuzzGeneration<List<Integer>> {
		private final UnorderedLookupTree tree = new UnorderedLookupTree(BLOCK_SIZE, ORDER_KEY_GAP);
		private final Map<Integer, Long> valueIndex = new HashMap<>();
		private int recordIdSequence = 1;

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.tree;
		}

		@Nonnull
		@Override
		public List<Integer> contents() {
			return readContents();
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomOps(random, 1 + random.nextInt(10));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomOps(random, 1 + random.nextInt(10));
			// applied LAST: a marker record inserted first can be removed again by a later random operation
			insertAt(0, nextRecordId());
		}

		int nextRecordId() {
			return this.recordIdSequence++;
		}

		int size() {
			return this.tree.size();
		}

		void insertAt(int position, int recordId) {
			this.tree.insertAtPosition(position, recordId, this);
		}

		/**
		 * Applies `count` randomized operations: insert a new record at a random position, or remove a random existing
		 * record by its tracked order-key. The tree state is read fresh each op so removals always target a record that
		 * exists.
		 */
		void applyRandomOps(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				final int currentSize = this.tree.size();
				if (this.valueIndex.isEmpty() || random.nextInt(3) <= 1) {
					// insert a new record at a random logical position
					insertAt(currentSize == 0 ? 0 : random.nextInt(currentSize + 1), nextRecordId());
				} else {
					// remove an existing record addressed by its current order-key
					final int recordId = pickRecord(random);
					this.tree.removeByOrderKey(this.valueIndex.get(recordId), recordId, this);
					this.valueIndex.remove(recordId);
				}
			}
		}

		/**
		 * Reads the tree's logical record order into an `.equals`-comparable list.
		 */
		@Nonnull
		List<Integer> readContents() {
			final int[] array = this.tree.getArray();
			final List<Integer> contents = new ArrayList<>(array.length);
			for (final int recordId : array) {
				contents.add(recordId);
			}
			return contents;
		}

		/**
		 * Picks a random record id currently present in the value index.
		 */
		private int pickRecord(@Nonnull Random random) {
			final int index = random.nextInt(this.valueIndex.size());
			int i = 0;
			for (final Integer recordId : this.valueIndex.keySet()) {
				if (i++ == index) {
					return recordId;
				}
			}
			throw new IllegalStateException("unreachable");
		}
	}

}
