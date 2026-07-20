/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the correctness of the {@link TransactionalElementBPlusTree} — the element-keyed B+ tree backing the price
 * super index's `priceRecords` collection. It covers insert / split, delete / borrow / merge /
 * collapse, point lookup and ordered iteration, the transactional commit / rollback machinery, a cross-store correctness
 * port of the decision spike (the element tree must agree with a reference map on every read), and the granular per-leaf
 * persistence round-trip (boundary-stable reload with the change-detection baseline restored).
 *
 * The element type is the real {@link PriceRecordContract}, keyed on {@link PriceRecordContract#internalPriceId()} — the
 * exact production shape — so a content-deriving factory ({@link #rec(int)}) lets every assertion check both the
 * routing key and the stored element identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Transactional element-keyed B+ tree")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalElementBPlusTreeTest {

	/**
	 * The key extractor used by every tree under test: the element's internal price id.
	 */
	private static final ToIntFunction<PriceRecordContract> KEY = PriceRecordContract::internalPriceId;

	/**
	 * Builds a price record whose every field is derived from the given key, so a round-tripped element can be checked
	 * for identity (`internalPriceId`) and full content equality against a freshly produced reference.
	 *
	 * @param key the internal price id (the tree's routing key)
	 * @return a content-stable price record for the key
	 */
	@Nonnull
	private static PriceRecordContract rec(int key) {
		return new PriceRecord(key, key + 1, key / 3, key * 100 + 21, key * 100);
	}

	/**
	 * Creates an empty element tree with a valid configuration derived from the given leaf block size (any size &gt;= 3,
	 * odd or even) and the standard key extractor. The minimum leaf occupancy is {@code ceil(bs/2) - 1}, the internal node
	 * size is the largest odd value not exceeding the leaf size, and its minimum mirrors the leaf rule — the same
	 * derivation the family's default constructor uses, so merges never immediately overflow.
	 *
	 * @param blockSize the leaf block size
	 * @return a fresh empty tree
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<PriceRecordContract> treeOf(int blockSize) {
		final int minValue = (int) Math.ceil(blockSize / 2.0) - 1;
		final int internal = blockSize % 2 == 1 ? blockSize : blockSize - 1;
		final int minInternal = (int) Math.ceil(internal / 2.0) - 1;
		return new TransactionalElementBPlusTree<>(
			blockSize, minValue, internal, minInternal, PriceRecordContract.class, KEY
		);
	}

	/**
	 * Verifies the tree's internal consistency report and that the forward / reverse element iterators, the forward key
	 * iterator and the {@link TransactionalElementBPlusTree#toArray()} projection all agree with the expected ascending
	 * key array (checking both the routing key and the full element content).
	 *
	 * @param tree         the tree to verify
	 * @param expectedKeys the expected ascending key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree, @Nonnull int... expectedKeys
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		assertEquals(expectedKeys.length, tree.size());

		// forward element iteration: ascending keys + content
		int index = 0;
		final Iterator<PriceRecordContract> forward = tree.valueIterator();
		while (forward.hasNext()) {
			final PriceRecordContract record = forward.next();
			assertEquals(expectedKeys[index], record.internalPriceId(), "Forward iterator key mismatch at " + index);
			assertEquals(rec(expectedKeys[index]), record, "Forward iterator content mismatch at " + index);
			index++;
		}
		assertEquals(expectedKeys.length, index, "Forward iterator produced a different element count");
		assertThrows(NoSuchElementException.class, forward::next, "Forward iterator should be exhausted");

		// reverse element iteration: descending keys
		index = expectedKeys.length;
		final Iterator<PriceRecordContract> reverse = tree.valueReverseIterator();
		while (reverse.hasNext()) {
			final PriceRecordContract record = reverse.next();
			assertEquals(expectedKeys[--index], record.internalPriceId(), "Reverse iterator key mismatch at " + index);
		}
		assertEquals(0, index, "Reverse iterator produced a different element count");
		assertThrows(NoSuchElementException.class, reverse::next, "Reverse iterator should be exhausted");

		// forward key iteration
		final int[] reconstructedKeys = new int[expectedKeys.length];
		index = 0;
		final OfInt keys = tree.keyIterator();
		while (keys.hasNext()) {
			reconstructedKeys[index++] = keys.nextInt();
		}
		assertArrayEquals(expectedKeys, reconstructedKeys, "Key iterator mismatch");

		// toArray projection
		final PriceRecordContract[] array = tree.toArray();
		assertEquals(expectedKeys.length, array.length);
		for (int i = 0; i < expectedKeys.length; i++) {
			assertEquals(expectedKeys[i], array[i].internalPriceId(), "toArray key mismatch at " + i);
			assertEquals(rec(expectedKeys[i]), array[i], "toArray content mismatch at " + i);
		}

		// point lookups
		for (final int expectedKey : expectedKeys) {
			final PriceRecordContract found = tree.search(expectedKey);
			assertEquals(rec(expectedKey), found, "search content mismatch for key " + expectedKey);
		}
	}

	/**
	 * Builds a tree of `totalElements` distinct random keys (warm-up / non-transactional insert path) and returns it
	 * together with its sorted key array.
	 *
	 * @param blockSize     the block size
	 * @param seed          the random seed for reproducibility
	 * @param totalElements the number of distinct elements to insert
	 * @return the tree and its sorted key array
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(int blockSize, long seed, int totalElements) {
		final Random random = new Random(seed);
		final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(blockSize);
		int[] keys = new int[0];
		do {
			final int key = random.nextInt(totalElements << 1);
			tree.insert(rec(key));
			keys = ArrayUtils.insertIntIntoOrderedArray(key, keys);
		} while (keys.length < totalElements);
		return new TreeTuple(tree, keys);
	}

	/**
	 * Performs the ordered filtered merge lookup used by the price index ({@code getPriceRecordsByPriceIds} shape): given
	 * an ascending id filter, returns the matching elements in ascending order using a single forward iterator pass.
	 *
	 * @param tree      the tree to look up in
	 * @param sortedIds ascending distinct keys to resolve
	 * @return the matching elements in ascending key order
	 */
	@Nonnull
	private static List<PriceRecordContract> filteredLookup(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree, @Nonnull int[] sortedIds
	) {
		final List<PriceRecordContract> result = new ArrayList<>(sortedIds.length);
		if (sortedIds.length == 0) {
			return result;
		}
		final Iterator<PriceRecordContract> it = tree.greaterOrEqualValueIterator(sortedIds[0]);
		int idCursor = 0;
		PriceRecordContract current = it.hasNext() ? it.next() : null;
		while (current != null && idCursor < sortedIds.length) {
			final int recordKey = current.internalPriceId();
			final int wantedKey = sortedIds[idCursor];
			if (recordKey == wantedKey) {
				result.add(current);
				idCursor++;
				current = it.hasNext() ? it.next() : null;
			} else if (recordKey < wantedKey) {
				current = it.hasNext() ? it.next() : null;
			} else {
				idCursor++;
			}
		}
		return result;
	}

	/**
	 * Holds a tree under test together with its expected sorted key array.
	 *
	 * @param tree the tree under test
	 * @param keys the sorted array of inserted keys
	 */
	private record TreeTuple(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree,
		@Nonnull int[] keys
	) {
	}

	@Nested
	@DisplayName("Insert operations")
	class InsertOperationsTest {

		@Test
		@DisplayName("replaces the element when inserting a duplicate key")
		void shouldOverwriteDuplicateKeys() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			tree.insert(rec(5));
			final PriceRecordContract replacement = new PriceRecord(5, 999, 0, 1, 0);
			tree.insert(replacement);
			assertEquals(1, tree.size());
			assertEquals(replacement, tree.search(5));
			assertEquals(999, tree.search(5).priceId());
		}

		@Test
		@DisplayName("splits the leaf node when capacity is exceeded")
		void shouldSplitNodeWhenFull() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			tree.insert(rec(1));
			tree.insert(rec(2));
			tree.insert(rec(3));
			tree.insert(rec(4));
			assertEquals(4, tree.size());
			assertTrue(tree.isRootInternal(), "Tree should have split into multiple leaves");
			verifyTreeConsistency(tree, 1, 2, 3, 4);
		}

		@Test
		@DisplayName("stays balanced after sequential forward insertions")
		void shouldMaintainBalancedForward() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			final int[] expected = new int[100];
			for (int i = 0; i < 100; i++) {
				tree.insert(rec(i));
				expected[i] = i;
			}
			verifyTreeConsistency(tree, expected);
		}

		@Test
		@DisplayName("stays balanced after sequential backward insertions")
		void shouldMaintainBalancedBackward() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			final int[] expected = new int[100];
			for (int i = 99; i >= 0; i--) {
				tree.insert(rec(i));
				expected[i] = i;
			}
			verifyTreeConsistency(tree, expected);
		}

		@Test
		@DisplayName("stays balanced after random insertions")
		void shouldMaintainBalancedRandom() {
			final TreeTuple tuple = prepareRandomTree(5, 2024L, 500);
			verifyTreeConsistency(tuple.tree(), tuple.keys());
		}
	}

	@Nested
	@DisplayName("Delete operations")
	class DeleteOperationsTest {

		@Test
		@DisplayName("ignores deletion of an absent key")
		void shouldIgnoreAbsentKey() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			tree.insert(rec(1));
			tree.insert(rec(2));
			tree.delete(99);
			verifyTreeConsistency(tree, 1, 2);
		}

		@Test
		@DisplayName("rebalances on deletion via borrow and merge")
		void shouldRebalanceOnDelete() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			for (int i = 0; i < 12; i++) {
				tree.insert(rec(i));
			}
			verifyTreeConsistency(tree, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
			// delete the head of the leftmost leaf (forces a parent separator update) and an interior key
			tree.delete(0);
			verifyTreeConsistency(tree, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
			tree.delete(6);
			verifyTreeConsistency(tree, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11);
		}

		@Test
		@DisplayName("returns to an empty single-leaf root after deleting everything")
		void shouldReturnToEmpty() {
			final TreeTuple tuple = prepareRandomTree(3, 7L, 200);
			final TransactionalElementBPlusTree<PriceRecordContract> tree = tuple.tree();
			int[] keys = tuple.keys();
			final Random random = new Random(99L);
			while (keys.length > 0) {
				final int index = random.nextInt(keys.length);
				final int key = keys[index];
				tree.delete(key);
				keys = ArrayUtils.removeIntFromOrderedArray(key, keys);
				verifyTreeConsistency(tree, keys);
			}
			assertEquals(0, tree.size());
			assertFalse(tree.isRootInternal(), "Drained tree must collapse to a single empty leaf");
			assertNull(tree.search(0));
		}
	}

	@Nested
	@DisplayName("Search and iteration")
	class SearchAndIterationTest {

		@Test
		@DisplayName("returns null for an absent key")
		void shouldReturnNullForAbsentKey() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(4);
			for (int i = 0; i < 20; i += 2) {
				tree.insert(rec(i));
			}
			assertNull(tree.search(1));
			assertNull(tree.search(-5));
			assertNull(tree.search(1000));
		}

		@Test
		@DisplayName("iterates an empty tree without producing elements")
		void shouldIterateEmptyTree() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(4);
			assertFalse(tree.valueIterator().hasNext());
			assertFalse(tree.valueReverseIterator().hasNext());
			assertFalse(tree.keyIterator().hasNext());
			assertEquals(0, tree.toArray().length);
		}

		@Test
		@DisplayName("greater-or-equal element iterator starts at the first matching key")
		void shouldIterateGreaterOrEqual() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			for (int i = 0; i < 20; i++) {
				tree.insert(rec(i << 1)); // even keys 0..38
			}
			// start on an absent key -> first element with a greater key
			final Iterator<PriceRecordContract> it = tree.greaterOrEqualValueIterator(15);
			final List<Integer> seen = new ArrayList<>();
			while (it.hasNext()) {
				seen.add(it.next().internalPriceId());
			}
			assertEquals(16, seen.get(0));
			assertEquals(38, seen.get(seen.size() - 1));
			assertEquals(12, seen.size());
		}

		@Test
		@DisplayName("lesser-or-equal element iterator starts at the last matching key")
		void shouldIterateLesserOrEqual() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			for (int i = 0; i < 20; i++) {
				tree.insert(rec(i << 1)); // even keys 0..38
			}
			// start on an absent key -> first element with a lesser key
			final Iterator<PriceRecordContract> it = tree.lesserOrEqualValueIterator(15);
			final List<Integer> seen = new ArrayList<>();
			while (it.hasNext()) {
				seen.add(it.next().internalPriceId());
			}
			assertEquals(14, seen.get(0));
			assertEquals(0, seen.get(seen.size() - 1));
			assertEquals(8, seen.size());
		}
	}

	@Nested
	@DisplayName("Transactional behaviour")
	class TransactionalTest {

		@Test
		@DisplayName("applies insertions on commit")
		void shouldCommitInsertions() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = 0; i < 10; i++) {
						tested.insert(rec(i));
					}
					// read-your-writes inside the transaction
					assertEquals(rec(7), tested.search(7));
					assertEquals(10, tested.size());
				},
				(original, committed) -> {
					assertEquals(0, original.size(), "The original tree must be untouched before commit-merge");
					verifyTreeConsistency(committed, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
				}
			);
		}

		@Test
		@DisplayName("discards insertions on rollback")
		void shouldRollbackInsertions() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			tree.insert(rec(100));
			assertStateAfterRollback(
				tree,
				tested -> {
					for (int i = 0; i < 10; i++) {
						tested.insert(rec(i));
					}
					assertEquals(11, tested.size());
				},
				(original, committed) -> verifyTreeConsistency(original, 100)
			);
		}

		@Test
		@DisplayName("applies deletions on commit")
		void shouldCommitDeletions() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			for (int i = 0; i < 12; i++) {
				tree.insert(rec(i));
			}
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.delete(0);
					tested.delete(11);
					tested.delete(6);
				},
				(original, committed) -> {
					verifyTreeConsistency(original, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
					verifyTreeConsistency(committed, 1, 2, 3, 4, 5, 7, 8, 9, 10);
				}
			);
		}

		@Test
		@DisplayName("survives large randomized insert/delete churn committed as one transaction")
		void shouldSurviveRandomizedTransactionalChurn() {
			// The dirty-scope validation runs only at commit, so a fuzzer that never commits (like the bare-tree
			// consistency-report churn) is blind to it. This churns hundreds of random insert / delete operations at
			// small block sizes inside a single committed transaction, so leaves are repeatedly split and merged
			// within the transaction; the commit-time pre-WAL and post-replay dirty-scope passes must re-derive every
			// dirtied leaf's boundaries cleanly, and the committed content must match the oracle.
			for (final int blockSize : new int[]{3, 4, 6}) {
				for (long seed = 0; seed < 25; seed++) {
					exerciseTransactionalChurn(blockSize, seed);
				}
			}
		}

		/**
		 * Runs one big transaction of randomized insert / delete churn against a fresh tree of the given block size,
		 * mirroring every operation into an oracle map, then commits and asserts the committed tree matches the oracle
		 * exactly. The commit triggers both dirty-scope validation passes.
		 *
		 * @param blockSize the leaf block size (small values force frequent splits and merges)
		 * @param seed      the RNG seed for reproducibility
		 */
		private void exerciseTransactionalChurn(int blockSize, long seed) {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(blockSize);
			final TreeMap<Integer, PriceRecordContract> oracle = new TreeMap<>();
			final Random random = new Random(seed);
			final int range = 120;
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int op = 0; op < 400; op++) {
						final int key = random.nextInt(range);
						// bias towards delete once the tree has grown so merges (donor blanks) run heavily
						final boolean delete = oracle.size() > 40 ? random.nextInt(3) > 0 : random.nextBoolean();
						if (delete) {
							tested.delete(key);
							oracle.remove(key);
						} else {
							final PriceRecordContract record = rec(key);
							tested.insert(record);
							oracle.put(key, record);
						}
					}
					// read-your-writes: the in-transaction view must already match the oracle size
					assertEquals(
						oracle.size(), tested.size(),
						"In-transaction size mismatch at blockSize=" + blockSize + " seed=" + seed
					);
				},
				(original, committed) -> {
					assertEquals(0, original.size(), "The original tree must be untouched before commit-merge");
					final int[] expectedKeys = new int[oracle.size()];
					int index = 0;
					for (final Integer key : oracle.keySet()) {
						expectedKeys[index++] = key;
					}
					verifyTreeConsistency(committed, expectedKeys);
				}
			);
		}
	}

	@Nested
	@DisplayName("Cross-store correctness")
	class CrossStoreCorrectnessTest {

		@Test
		@DisplayName("agrees with a reference map on toArray, point lookup and filtered merge")
		void shouldAgreeWithReferenceMap() {
			final long seed = 0x5DEECE66DL;
			final Random random = new Random(seed);
			final int recordCount = 5_000;

			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(64);
			final TreeMap<Integer, PriceRecordContract> reference = new TreeMap<>();
			while (reference.size() < recordCount) {
				final int key = random.nextInt(recordCount << 2);
				final PriceRecordContract record = rec(key);
				tree.insert(record);
				reference.put(key, record);
			}

			// 1) full ordered scan must match the reference order and content exactly
			final PriceRecordContract[] all = tree.toArray();
			assertEquals(reference.size(), all.length);
			int index = 0;
			for (final PriceRecordContract referenceRecord : reference.values()) {
				assertEquals(referenceRecord, all[index++], "toArray disagreement at " + (index - 1));
			}

			// 2) point lookups (present and absent) must match the reference
			for (int i = 0; i < 10_000; i++) {
				final int key = random.nextInt(recordCount << 2);
				assertEquals(reference.get(key), tree.search(key), "search disagreement for key " + key);
			}

			// 3) filtered ordered merge must match the reference filtered set
			final int[] filter = sortedDistinctSubset(reference, random, recordCount / 10);
			final List<PriceRecordContract> hit = filteredLookup(tree, filter);
			assertEquals(filter.length, hit.size(), "filtered lookup size disagreement");
			for (int i = 0; i < filter.length; i++) {
				assertEquals(reference.get(filter[i]), hit.get(i), "filtered lookup disagreement at " + i);
			}
		}

		/**
		 * Draws an ascending, distinct subset of existing keys from the reference map.
		 *
		 * @param reference the reference key/value map
		 * @param random    the RNG
		 * @param size      the desired subset size
		 * @return the ascending distinct subset of existing keys
		 */
		@Nonnull
		private static int[] sortedDistinctSubset(
			@Nonnull TreeMap<Integer, PriceRecordContract> reference, @Nonnull Random random, int size
		) {
			final Integer[] keys = reference.keySet().toArray(new Integer[0]);
			final TreeMap<Integer, Boolean> picked = new TreeMap<>();
			while (picked.size() < size) {
				picked.put(keys[random.nextInt(keys.length)], Boolean.TRUE);
			}
			final int[] result = new int[picked.size()];
			int index = 0;
			for (final Integer key : picked.keySet()) {
				result[index++] = key;
			}
			return result;
		}
	}

	@Nested
	@DisplayName("Warm-up randomized churn")
	class WarmUpChurnTest {

		@Test
		@DisplayName("keeps separators and occupancy consistent through non-transactional insert/delete churn")
		void shouldKeepConsistentThroughRandomizedChurn() {
			// The non-transactional (warm-up / bulk-load) path: churn random insert and delete directly on the tree
			// (no transaction), asserting the consistency report and the exact content after every structural change.
			// Delete-biased once the tree has grown so borrows and merges are exercised as heavily as splits.
			for (long seed = 0; seed < 50; seed++) {
				final Random random = new Random(seed);
				final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
				final TreeMap<Integer, PriceRecordContract> oracle = new TreeMap<>();
				for (int op = 0; op < 400; op++) {
					final int key = random.nextInt(60);
					final boolean delete = oracle.size() > 20 ? random.nextInt(3) > 0 : random.nextBoolean();
					if (delete) {
						tree.delete(key);
						oracle.remove(key);
					} else {
						final PriceRecordContract record = rec(key);
						tree.insert(record);
						oracle.put(key, record);
					}
					final ConsistencyReport report = tree.getConsistencyReport();
					assertEquals(
						ConsistencyState.CONSISTENT, report.state(),
						"Inconsistent at seed " + seed + " op " + op + ": " + report.report()
					);
					assertEquals(oracle.size(), tree.size(), "Size mismatch at seed " + seed + " op " + op);
				}
				// final content must match the oracle exactly, in ascending order
				final int[] expectedKeys = new int[oracle.size()];
				int index = 0;
				for (final Integer key : oracle.keySet()) {
					expectedKeys[index++] = key;
				}
				verifyTreeConsistency(tree, expectedKeys);
			}
		}
	}

	@Nested
	@DisplayName("Granular per-leaf persistence")
	class GranularPersistenceTest {

		@Test
		@DisplayName("round-trips through leaf pages, preserving boundaries and clearing the dirty baseline")
		void shouldRoundTripViaLeafPages() {
			final int blockSize = 4;
			final TreeTuple tuple = prepareRandomTree(blockSize, 11L, 120);
			final TransactionalElementBPlusTree<PriceRecordContract> original = tuple.tree();
			assertTrue(original.isRootInternal(), "Test needs a multi-leaf tree to exercise paging");

			// emit pages from the freshly built tree (every leaf is unassigned -> gets a fresh page, then clean)
			final AtomicInteger sequenceAllocator = new AtomicInteger(0);
			final PersistedState persisted = emitPages(original, sequenceAllocator);
			assertTrue(persisted.pages().size() >= 2, "A multi-leaf tree must emit at least two pages");

			// reassemble from the persisted pages (boundary-stable) and seed the change-detection baseline
			final TransactionalElementBPlusTree<PriceRecordContract> restored = reassemble(blockSize, persisted);
			seedAfterReload(restored);

			// the restored tree holds exactly the same content
			verifyTreeConsistency(restored, tuple.keys());

			// boundary-stable: the restored leaves carry the same page sequences, in the same order
			final int[] restoredSequences = pageSequences(restored);
			assertArrayEquals(persisted.sequences(), restoredSequences, "Leaf page boundaries were not preserved");

			// the change-detection baseline is restored: a second flush is a true no-op (nothing dirty, nothing new)
			for (final LeafPageHandle<PriceRecordContract> handle : restored.<PriceRecordContract>leafPageHandles()) {
				assertFalse(handle.isDirty(), "A reloaded, unmutated leaf must not be dirty");
				assertTrue(
					handle.getPageSequence() != AbstractTransactionalBPlusTree.UNASSIGNED_PAGE_SEQUENCE,
					"A reloaded leaf must keep its persisted page sequence"
				);
			}
		}

		@Test
		@DisplayName("flags only the mutated leaves dirty after a change")
		void shouldFlagOnlyMutatedLeavesDirty() {
			final int blockSize = 4;
			final TreeTuple tuple = prepareRandomTree(blockSize, 13L, 80);
			final TransactionalElementBPlusTree<PriceRecordContract> tree = tuple.tree();

			// first flush assigns + cleans every leaf
			emitPages(tree, new AtomicInteger(0));
			for (final LeafPageHandle<PriceRecordContract> handle : tree.<PriceRecordContract>leafPageHandles()) {
				assertFalse(handle.isDirty(), "All leaves must be clean right after a flush");
			}

			// mutate a single key, then count the leaves the write path would re-emit
			final int mutatedKey = tuple.keys()[tuple.keys().length / 2];
			tree.delete(mutatedKey);

			int dirtyOrNew = 0;
			for (final LeafPageHandle<PriceRecordContract> handle : tree.<PriceRecordContract>leafPageHandles()) {
				if (handle.isDirty()
					|| handle.getPageSequence() == AbstractTransactionalBPlusTree.UNASSIGNED_PAGE_SEQUENCE) {
					dirtyOrNew++;
				}
			}
			assertTrue(dirtyOrNew >= 1, "The mutated leaf must be flagged for re-emission");
			assertTrue(dirtyOrNew <= 3, "A single deletion must not dirty the whole tree (" + dirtyOrNew + ")");
		}

		@Test
		@DisplayName("rejects reassembly when single-leaf pages overlap across a leaf boundary")
		void shouldThrowWhenSingleLeafTreesOverlapAcrossBoundary() {
			// a block size large enough that three records never split, so each source tree stays a single leaf page
			final int blockSize = 64;

			// leaf page A holds keys 1, 2, 3
			final TransactionalElementBPlusTree<PriceRecordContract> treeA = treeOf(blockSize);
			treeA.insert(rec(1));
			treeA.insert(rec(2));
			treeA.insert(rec(3));

			// leaf page B holds keys 2, 3, 4 — a stale-twin shape overlapping A at 2 and 3, so A's last key (3) does
			// not sort strictly before B's first key (2)
			final TransactionalElementBPlusTree<PriceRecordContract> treeB = treeOf(blockSize);
			treeB.insert(rec(2));
			treeB.insert(rec(3));
			treeB.insert(rec(4));

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> treeOf(blockSize).assembleFromSingleLeafTrees(
					List.of(treeA, treeB), new int[]{0, 1}, "element B+ tree validation test"
				),
				"Overlapping single-leaf trees must fail the cross-leaf-order validation."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("rejects a reassembled leaf page whose interior keys are out of order")
		void shouldThrowWhenLeafHasOutOfOrderInteriorKeys() {
			// build a sound single-leaf source tree, then corrupt its interior key order in place to simulate a
			// serializer bug / truncated write / bit rot; the intra-leaf order check must reject it. This tree keeps no
			// key array, so the corruption is expressed by swapping two values whose extracted keys then sit out of order
			final int blockSize = 64;
			final TransactionalElementBPlusTree<PriceRecordContract> corrupt = treeOf(blockSize);
			corrupt.insert(rec(1));
			corrupt.insert(rec(2));
			corrupt.insert(rec(3));
			// mutate the live leaf value array directly so the extracted keys become [2, 1, 3]
			final PriceRecordContract[] values =
				((TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract>) corrupt.getRoot()).getValues();
			final PriceRecordContract swap = values[0];
			values[0] = values[1];
			values[1] = swap;

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> treeOf(blockSize).assembleFromSingleLeafTrees(
					List.of(corrupt), new int[]{0}, "element B+ tree intra-leaf test"),
				"A leaf with out-of-order interior keys must fail the intra-leaf-order validation."
			);
			assertTrue(
				ex.getMessage().contains("out-of-order keys"),
				"Expected the intra-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

		/**
		 * Models the granular write path: walks the leaf handles in order, allocates a fresh page for every unassigned or
		 * dirty leaf, clears the dirty flag and captures the page contents.
		 *
		 * @param tree              the tree to emit pages from
		 * @param sequenceAllocator the monotonic page-sequence allocator
		 * @return the captured page contents and their ordered page sequences
		 */
		@Nonnull
		private static PersistedState emitPages(
			@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree,
			@Nonnull AtomicInteger sequenceAllocator
		) {
			final List<LeafPageHandle<PriceRecordContract>> handles = tree.leafPageHandles();
			final List<List<PriceRecordContract>> pages = new ArrayList<>(handles.size());
			final int[] sequences = new int[handles.size()];
			for (int h = 0; h < handles.size(); h++) {
				final LeafPageHandle<PriceRecordContract> handle = handles.get(h);
				if (handle.getPageSequence() == AbstractTransactionalBPlusTree.UNASSIGNED_PAGE_SEQUENCE
					|| handle.isDirty()) {
					if (handle.getPageSequence() == AbstractTransactionalBPlusTree.UNASSIGNED_PAGE_SEQUENCE) {
						handle.setPageSequence(sequenceAllocator.getAndIncrement());
					}
					handle.clearDirty();
				}
				sequences[h] = handle.getPageSequence();
				final List<PriceRecordContract> page = new ArrayList<>(handle.size());
				for (int i = 0; i < handle.size(); i++) {
					page.add(handle.valueAt(i));
				}
				pages.add(page);
			}
			return new PersistedState(pages, sequences);
		}

		/**
		 * Rebuilds a boundary-stable tree from persisted pages: one single-leaf tree per page (filled via the public
		 * insert surface), reassembled with their page sequences.
		 *
		 * @param blockSize the block size to rebuild at
		 * @param persisted the persisted page contents and sequences
		 * @return the reassembled tree
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> reassemble(
			int blockSize, @Nonnull PersistedState persisted
		) {
			final List<TransactionalElementBPlusTree<PriceRecordContract>> pageTrees =
				new ArrayList<>(persisted.pages().size());
			for (final List<PriceRecordContract> page : persisted.pages()) {
				final TransactionalElementBPlusTree<PriceRecordContract> pageTree = treeOf(blockSize);
				for (final PriceRecordContract record : page) {
					pageTree.insert(record);
				}
				pageTrees.add(pageTree);
			}
			return treeOf(blockSize).assembleFromSingleLeafTrees(
				pageTrees, persisted.sequences(), "element B+ tree reassembly test"
			);
		}

		/**
		 * Seeds the change-detection baseline after a reload: the leaves rebuilt via insert are dirty, so the consumer
		 * clears them once (the restored page is known to be on disk already).
		 *
		 * @param tree the freshly reassembled tree
		 */
		private static void seedAfterReload(@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree) {
			for (final LeafPageHandle<PriceRecordContract> handle : tree.<PriceRecordContract>leafPageHandles()) {
				handle.clearDirty();
			}
		}

		/**
		 * Returns the ordered page sequences of a tree's leaves.
		 *
		 * @param tree the tree
		 * @return the ascending-order leaf page sequences
		 */
		@Nonnull
		private static int[] pageSequences(@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree) {
			final List<LeafPageHandle<PriceRecordContract>> handles = tree.leafPageHandles();
			final int[] sequences = new int[handles.size()];
			for (int i = 0; i < handles.size(); i++) {
				sequences[i] = handles.get(i).getPageSequence();
			}
			return sequences;
		}
	}

	/**
	 * Captured granular-storage state: the ordered leaf-page contents and their persisted page sequences.
	 *
	 * @param pages     the per-page element lists, in ascending leaf order
	 * @param sequences the persisted page sequence of each page, positionally aligned with {@link #pages}
	 */
	private record PersistedState(
		@Nonnull List<List<PriceRecordContract>> pages,
		@Nonnull int[] sequences
	) {
	}

	@Nested
	@DisplayName("op-time boundary-mutation asserts")
	class OpTimeBoundaryMutationTest {

		/**
		 * Builds a single-leaf source tree holding the supplied keys. Block size 10 keeps every supplied key in one leaf
		 * (no split), so the tree's root stays a leaf that can be reassembled into a controlled spine. The internal block
		 * size is fixed at 3 (cap of four children per parent) so the assembled spine is deterministic — the family's
		 * derived block sizes are deliberately not used here.
		 *
		 * @param keys the keys to place in the leaf, in any order
		 * @return a single-leaf tree
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> singleLeaf(@Nonnull int... keys) {
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				new TransactionalElementBPlusTree<>(10, 1, 3, 1, PriceRecordContract.class, KEY);
			for (final int key : keys) {
				tree.insert(rec(key));
			}
			return tree;
		}

		/**
		 * Reassembles the supplied sound single-leaf trees into one tree with a deterministic spine: internal block size 3
		 * caps a parent at four children, so five leaves split into two parents. The leaves are non-overlapping, so the
		 * cross-leaf validation inside the assembler passes; the assembled tree is then used to exercise the op-time
	 * boundary checks
		 * against hypothetical boundary keys.
		 *
		 * @param leaves the ordered, non-overlapping single-leaf trees
		 * @return the assembled tree
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> assembleSound(
			@Nonnull List<TransactionalElementBPlusTree<PriceRecordContract>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalElementBPlusTree<PriceRecordContract>(10, 1, 3, 1, PriceRecordContract.class, KEY)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "element B+ tree op-time boundary test");
		}

		@Test
		@DisplayName("tail insert overlapping the successor leaf under a different parent throws (Check T)")
		void shouldThrowOnMisroutedTailInsertAcrossParentBoundary() {
			// spine (internal block 3 => max 4 children => 5 leaves split 3 + 2): P1 = [L0,L1,L2], P2 = [L3,L4].
			// L2 is the RIGHTMOST child of P1 and its successor L3 is the LEFTMOST child of P2, so the fence
			// (8 = L3's first key) lives at the ROOT, not L2's immediate parent — this proves the cross-parent walk.
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(5);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertTailBoundary(cursor, 8),
				"A new last key equal to the successor leaf's first key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("successor leaf boundary"),
				"Expected the tail cross-leaf diagnostic, got: " + ex.getMessage()
			);
			// a last key strictly below the fence is legal
			assertDoesNotThrow(() -> tree.assertTailBoundary(cursor, 7));
		}

		@Test
		@DisplayName("head insert undercutting the same-parent predecessor throws (Check H)")
		void shouldThrowOnMisroutedHeadInsertWithinParent() {
			// three sound leaves under one parent: L0 = [1,5], L1 = [8,12], L2 = [15,16]. L1's predecessor is its
			// same-parent left sibling L0 (last key 5). A head key of 3 undercuts it.
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 5), singleLeaf(8, 12), singleLeaf(15, 16)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(8);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertHeadBoundary(cursor, 3),
				"A new first key at or below the predecessor leaf's last key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
			// a first key strictly above the predecessor's last key is legal
			assertDoesNotThrow(() -> tree.assertHeadBoundary(cursor, 6));
			// the separator-order belt is INSUFFICIENT for this shape: propagating the mis-routed first key (3) up
			// as L1's separator yields separators (3, 15) which are still strictly increasing, so the belt would
			// NOT fire — which is exactly why Check H is required
			assertDoesNotThrow(
				() -> TransactionalElementBPlusTree.assertSeparatorOrder(new int[]{3, 15}, 2, 0));
		}

		@Test
		@DisplayName("head insert undercutting a predecessor under a different parent throws (Check H right-spine)")
		void shouldThrowOnMisroutedHeadInsertAcrossParentBoundary() {
			// L3 is the LEFTMOST child of P2; its predecessor L2 is the RIGHTMOST child of P1 (cross-parent). Check
			// H must walk up to the clamp ancestor (root) and descend P1's right spine to L2 (last key 6).
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(8);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertHeadBoundary(cursor, 5),
				"A new first key at or below the cross-parent predecessor's last key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
			assertDoesNotThrow(() -> tree.assertHeadBoundary(cursor, 7));
		}

		@Test
		@DisplayName("both boundary checks apply to a single-key (0 to 1) leaf")
		void shouldRunBothChecksForSingleKeyLeaf() {
			// sound tree with a single-key middle leaf L_mid = [6] between L_pred = [1,4] and L_succ = [8,9]
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 4), singleLeaf(6), singleLeaf(8, 9)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(6);
			// the actual 0->1 key (6) is sound: both checks run and pass
			assertDoesNotThrow(() -> tree.assertInsertBoundaries(cursor, 6));
			// the SAME single-key leaf is subject to the head check (a smaller key undercuts L_pred's last key 4)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertHeadBoundary(cursor, 3));
			// ...and to the tail check (a larger key reaches L_succ's first key 8)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertTailBoundary(cursor, 8));
		}

		@Test
		@DisplayName("sequential bulk append and prepend never trip the op-time boundary asserts (happy-path pin)")
		void shouldNotThrowOnSequentialInsertions() {
			// ascending inserts are all tail inserts (Check T runs on the rightmost leaf and finds no fence);
			// descending inserts are all head inserts on the leftmost leaf (Check H finds no predecessor)
			final TransactionalElementBPlusTree<PriceRecordContract> ascending = treeOf(3);
			assertDoesNotThrow(() -> {
				for (int i = 1; i <= 256; i++) {
					ascending.insert(rec(i));
				}
			});
			final ConsistencyReport ascendingReport = ascending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, ascendingReport.state(), ascendingReport.report());

			final TransactionalElementBPlusTree<PriceRecordContract> descending = treeOf(3);
			assertDoesNotThrow(() -> {
				for (int i = 256; i >= 1; i--) {
					descending.insert(rec(i));
				}
			});
			final ConsistencyReport descendingReport = descending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, descendingReport.state(), descendingReport.report());
		}

	}

	@Nested
	@DisplayName("commit-time structural integrity validation")
	class DirtyLeafScopeValidationTest {

		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> singleLeaf(@Nonnull int... keys) {
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				new TransactionalElementBPlusTree<>(10, 1, 3, 1, PriceRecordContract.class, KEY);
			for (final int key : keys) {
				tree.insert(rec(key));
			}
			return tree;
		}

		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> assembleSound(
			@Nonnull List<TransactionalElementBPlusTree<PriceRecordContract>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalElementBPlusTree<PriceRecordContract>(10, 1, 3, 1, PriceRecordContract.class, KEY)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "element B+ tree scope test");
		}

		@Nonnull
		private static TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> leafAt(
			@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree, int key) {
			return tree.createCursor(key).leafNode();
		}

		@Test
		@DisplayName("a sound dirty scope relocates and validates without throwing")
		void shouldNotThrowWhenDirtyLeavesAreSound() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// the registry keeps probe KEYS, not node objects; each key relocates to the leaf that owns it
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(1, 5, 10)));
		}

		@Test
		@DisplayName("a leaf whose last key was widened past its successor is caught (tail)")
		void shouldDetectTailOverlapOnRelocateAndValidate() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> middle = leafAt(tree, 5);
			// simulate a reverted / lifecycle-corrupted layer widening the middle leaf's derived range from [5,6] to
			// [5,10]: the key is derived from the value, so replacing the value at the peek slot with rec(10) makes its
			// last key reach the successor's first key; the separator is untouched, so relocating by the leaf's own
			// first key (5) still lands on it and the tail half-invariant fires
			middle.getValues()[middle.getPeek()] = rec(10);
			// relocate by the leaf's own (unchanged) first key 5 — the descent lands on it and the tail half-invariant fires
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(5))
			);
			assertTrue(
				ex.getMessage().contains("successor leaf boundary"),
				"Expected the tail cross-leaf diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("a leaf undercut by a widened predecessor is caught (head)")
		void shouldDetectHeadOverlapOnRelocateAndValidate() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// widen the PREDECESSOR leaf's last key from 2 to 5 so it reaches the middle leaf's first key; relocating
			// the middle leaf by its unchanged first key (5) lands on it and the head half-invariant compares against
			// the predecessor's corrupted last key
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> predecessor = leafAt(tree, 1);
			predecessor.getValues()[predecessor.getPeek()] = rec(5);
			// relocate the middle leaf by its unchanged first key 5; the head half-invariant compares against the
			// predecessor's corrupted last key
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(5))
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("a probe key landing on an empty leaf is skipped, not dereferenced")
		void shouldSkipWhenDescentLandsOnEmptyLeaf() {
			// an empty tree routes any probe key to its empty root leaf (peek < 0) — the scope validation must skip
			// it rather than dereference the peek slot
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				new TransactionalElementBPlusTree<>(10, 1, 3, 1, PriceRecordContract.class, KEY);
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(42)));
		}

		/**
		 * Widens (in place) the effective last key of the leaf that currently routes {@code leafKey}, simulating a
		 * lifecycle bug that reverted / corrupted the leaf's write layer so its derived range overlaps the successor.
		 * Mutates the write layer when one exists (the transactional case), otherwise the leaf itself; the key is
		 * derived from the value, so the peek slot's value is replaced with one deriving {@code newLastKey}.
		 */
		private static void corruptLastKey(
			@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree, int leafKey, int newLastKey) {
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> leaf =
				tree.createCursor(leafKey).leafNode();
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> layer =
				Transaction.getTransactionalMemoryLayerIfExists(leaf);
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<PriceRecordContract> writable =
				layer != null ? layer : leaf;
			writable.getValues()[writable.getPeek()] = rec(newLastKey);
		}

		@Test
		@DisplayName("pre-commit pipeline: a transactional insert registers its leaf and a healthy scope is accepted")
		void shouldRegisterDirtiedLeafAndAcceptHealthyScope() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					t.insert(rec(7));
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					// registration fired AND the registry is keyed by this exact tree instance — the identity the
					// pre-commit pass and the post-replay merge both look the scope up by
					assertFalse(
						maintainer.getDirtyScopeTokens(t).isEmpty(),
						"the transactional insert must register its dirtied leaf under this tree"
					);
					// the healthy scope must be accepted by the pre-commit pass (no false positive)
					assertDoesNotThrow(maintainer::validateDirtyScopesBeforeCommit);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("pre-commit pipeline: a corrupted registered leaf is rejected by the pre-commit pass")
		void shouldRejectCorruptedScopeInPreCommitPass() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						t.insert(rec(7));
						// widen the dirtied leaf (now [5,6,7]) so its last key reaches the successor's first key (10)
						corruptLastKey(t, 5, 10);
						Transaction.getTransactionalLayerMaintainer().validateDirtyScopesBeforeCommit();
					},
					(t, committed) -> fail("the pre-commit pass must reject before commit")
				)
			);
		}

		@Test
		@DisplayName("post-replay pipeline: a corrupted registered leaf is rejected by the commit merge")
		void shouldRejectCorruptedScopeInCommitMerge() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						t.insert(rec(7));
						// no pre-commit pass here — the commit merge (post-replay, inside
						// createCopyWithMergedTransactionalMemory) must relocate the registered
						// leaf in the merged tree and catch the overlap
						corruptLastKey(t, 5, 10);
					},
					(t, committed) -> fail("the commit merge must reject the corrupted scope")
				)
			);
		}

		@Test
		@DisplayName("Registry hygiene: a remove-only transaction still registers its dirtied leaf")
		void shouldRegisterLeafOnRemoveOnlyTransaction() {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					// a clean remove (min block size 1 → [5,6] becomes [5], no underflow/rebalance) exercises the
					// delete seam only; a reverted removal layer is exactly what the pre-commit (pre-WAL) and post-replay
					// (merge-time) dirty-scope validation must be able to relocate
					t.delete(6);
					assertFalse(
						Transaction.getTransactionalLayerMaintainer().getDirtyScopeTokens(t).isEmpty(),
						"a remove-only transaction must register the leaf it dirtied"
					);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("Registry hygiene: a leaf split registers both halves")
		void shouldRegisterBothHalvesOnLeafSplit() {
			// block size 3 splits the root leaf as these four keys are inserted
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(3);
			assertStateAfterCommit(
				tree,
				t -> {
					t.insert(rec(1));
					t.insert(rec(2));
					t.insert(rec(3));
					t.insert(rec(4));
					// the split must register BOTH leaf halves; the identity-set size collapses to 1 if only one half
					// was registered (or one object was registered twice), which fails this assertion
					assertTrue(
						Transaction.getTransactionalLayerMaintainer().getDirtyScopeTokens(t).size() >= 2,
						"a leaf split must register both halves"
					);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

	}

	@Nested
	@DisplayName("internal-node split correctness")
	class InternalNodeSplitTest {

		/**
		 * Creates an empty tree with an explicit block-size configuration (bypassing the family's derived defaults), so
		 * the resulting spine shape is fully deterministic for the scenarios below.
		 *
		 * @param valueBlockSize           the leaf block size
		 * @param minValueBlockSize        the minimum leaf occupancy
		 * @param internalNodeBlockSize    the internal (routing) node block size
		 * @param minInternalNodeBlockSize the minimum internal node occupancy
		 * @return a fresh empty tree
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> treeOf(
			int valueBlockSize, int minValueBlockSize, int internalNodeBlockSize, int minInternalNodeBlockSize
		) {
			return new TransactionalElementBPlusTree<>(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize,
				PriceRecordContract.class, KEY
			);
		}

		/**
		 * Builds a single-leaf source tree holding the supplied keys, at the given explicit block-size configuration. A
		 * source tree stays a single leaf as long as the supplied keys never exceed {@code valueBlockSize}, which is the
		 * shape {@link TransactionalElementBPlusTree#assembleFromSingleLeafTrees} requires of every source tree.
		 *
		 * @param valueBlockSize           the leaf block size
		 * @param minValueBlockSize        the minimum leaf occupancy
		 * @param internalNodeBlockSize    the internal (routing) node block size
		 * @param minInternalNodeBlockSize the minimum internal node occupancy
		 * @param keys                     the keys to place in the leaf, in any order
		 * @return a single-leaf tree
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<PriceRecordContract> singleLeaf(
			int valueBlockSize, int minValueBlockSize, int internalNodeBlockSize, int minInternalNodeBlockSize,
			@Nonnull int... keys
		) {
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize
			);
			for (final int key : keys) {
				tree.insert(rec(key));
			}
			return tree;
		}

		/**
		 * Recursively verifies that every internal node reachable from {@code node} holds at most
		 * {@code internalNodeBlockSize} keys — the fan-out the tree's constructor was configured with. Leaf nodes impose
		 * no constraint here and end the recursion.
		 *
		 * @param node                  the subtree root to verify
		 * @param internalNodeBlockSize the configured internal node fan-out
		 */
		private static void assertInternalFanOutWithinBlockSize(
			@Nonnull BPlusTreeNode<?> node, int internalNodeBlockSize) {
			if (node instanceof AbstractIntKeyedInternalNode<?> internal) {
				assertTrue(
					internal.keyCount() <= internalNodeBlockSize,
					"Internal node holds " + internal.keyCount() + " keys, exceeding its configured fan-out of " +
						internalNodeBlockSize + "."
				);
				final BPlusTreeNode<?>[] children = internal.getChildren();
				for (int i = 0; i <= internal.getPeek(); i++) {
					assertInternalFanOutWithinBlockSize(children[i], internalNodeBlockSize);
				}
			}
		}

		/**
		 * Counts the internal (routing) nodes reachable from {@code node}, inclusive. A test uses the growth of this
		 * count across a batch of inserts to prove an internal-node split actually fired — a consistency check alone
		 * would pass even if the inserts never reached the split path.
		 *
		 * @param node the subtree root to count from
		 * @return the number of internal nodes in the subtree (0 for a leaf)
		 */
		private static int countInternalNodes(@Nonnull BPlusTreeNode<?> node) {
			if (node instanceof AbstractIntKeyedInternalNode<?> internal) {
				int count = 1;
				final BPlusTreeNode<?>[] children = internal.getChildren();
				for (int i = 0; i <= internal.getPeek(); i++) {
					count += countInternalNodes(children[i]);
				}
				return count;
			}
			return 0;
		}

		@Test
		@DisplayName("an assembled spine absorbs a leaf split that fills its parent internal node to capacity")
		void shouldSurviveInternalNodeSplitAfterAssembly() {
			final int valueBlockSize = 8;
			final int minValueBlockSize = 3;
			final int internalNodeBlockSize = 3;
			final int minInternalNodeBlockSize = 1;

			// six single-leaf source pages, four elements each, disjoint ascending ranges
			final List<TransactionalElementBPlusTree<PriceRecordContract>> leaves = new ArrayList<>(6);
			final int[] pageSequences = new int[6];
			for (int i = 0; i < 6; i++) {
				leaves.add(
					singleLeaf(
						valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize,
						i * 100, i * 100 + 1, i * 100 + 2, i * 100 + 3
					)
				);
				pageSequences[i] = i;
			}
			// assembly splits the six leaves into two internal nodes of three children each (two keys, short of the
			// four-child capacity) under a root
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				treeOf(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize)
					.assembleFromSingleLeafTrees(leaves, pageSequences, "internal-node split regression test");

			// filling the first leaf to its own capacity forces it to split, handing its parent a fourth child and
			// pushing that parent itself to capacity, which must in turn split without corrupting the spine
			assertDoesNotThrow(() -> {
				for (int key = 4; key < valueBlockSize; key++) {
					tree.insert(rec(key));
				}
			});

			final int[] expectedKeys = new int[valueBlockSize + 5 * 4];
			int index = 0;
			for (int key = 0; key < valueBlockSize; key++) {
				expectedKeys[index++] = key;
			}
			for (int leafIndex = 1; leafIndex < 6; leafIndex++) {
				final int base = leafIndex * 100;
				for (int k = 0; k < 4; k++) {
					expectedKeys[index++] = base + k;
				}
			}
			verifyTreeConsistency(tree, expectedKeys);
		}

		@Test
		@DisplayName("an assembled spine survives chained leaf splits at production-scale capacity")
		void shouldSurviveInternalNodeSplitAtProductionBlockSizes() {
			final int valueBlockSize = 64;
			final int minValueBlockSize = 31;
			final int internalNodeBlockSize = 31;
			final int minInternalNodeBlockSize = 15;
			final int leafCount = 33;
			// each source page must hold at least minValueBlockSize elements, otherwise the assembled (non-root) leaf is
			// structurally under-occupied and the final consistency report fails regardless of the split fix
			final int elementsPerLeaf = minValueBlockSize;
			final int leafSpacing = 1000;

			// thirty-three single-leaf source pages, minValueBlockSize elements each, disjoint ascending ranges
			final List<TransactionalElementBPlusTree<PriceRecordContract>> leaves = new ArrayList<>(leafCount);
			final int[] pageSequences = new int[leafCount];
			for (int i = 0; i < leafCount; i++) {
				final int[] keys = new int[elementsPerLeaf];
				for (int k = 0; k < elementsPerLeaf; k++) {
					keys[k] = i * leafSpacing + k;
				}
				leaves.add(
					singleLeaf(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, keys)
				);
				pageSequences[i] = i;
			}
			// assembly splits the thirty-three leaves into two internal nodes (seventeen and sixteen children) under a
			// root
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				treeOf(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize)
					.assembleFromSingleLeafTrees(leaves, pageSequences, "internal-node split production geometry test");
			final int internalNodesAfterAssembly = countInternalNodes(tree.getRoot());

			// ascending inserts stay below the second leaf's first key (1000), so they keep landing in the growing tail
			// of the first internal node's subtree; enough of them chain leaf splits until that internal node itself
			// reaches its capacity and must split
			assertDoesNotThrow(() -> {
				for (int key = elementsPerLeaf; key < 651; key++) {
					tree.insert(rec(key));
				}
			});

			// teeth: the run must actually exercise splitInternalNode — the internal-node count has to grow beyond what
			// assembly produced, otherwise a green consistency check would be a false positive (the split never fired)
			assertTrue(
				countInternalNodes(tree.getRoot()) > internalNodesAfterAssembly,
				"Expected the chained leaf splits to split at least one internal node, but the internal-node count did " +
					"not grow beyond the " + internalNodesAfterAssembly + " produced by assembly."
			);

			final int[] expectedKeys = new int[651 + (leafCount - 1) * elementsPerLeaf];
			int index = 0;
			for (int key = 0; key < 651; key++) {
				expectedKeys[index++] = key;
			}
			for (int i = 1; i < leafCount; i++) {
				for (int k = 0; k < elementsPerLeaf; k++) {
					expectedKeys[index++] = i * leafSpacing + k;
				}
			}
			verifyTreeConsistency(tree, expectedKeys);
		}

		@Test
		@DisplayName("incrementally grown internal nodes never exceed their configured fan-out")
		void shouldRespectInternalNodeBlockSizeOnIncrementalGrowth() {
			final int internalNodeBlockSize = 3;
			final TransactionalElementBPlusTree<PriceRecordContract> tree = treeOf(8, 3, internalNodeBlockSize, 1);
			for (int key = 0; key <= 500; key++) {
				tree.insert(rec(key));
			}
			assertTrue(tree.isRootInternal(), "Test needs a multi-level tree to exercise the spine");
			assertInternalFanOutWithinBlockSize(tree.getRoot(), internalNodeBlockSize);
		}

		@Test
		@DisplayName("a leaf split under a fully packed assembled root does not violate the no-full-node invariant")
		void shouldSplitChildUnderFullyPackedAssembledInternalNode() {
			final int valueBlockSize = 8;
			final int minValueBlockSize = 3;
			final int internalNodeBlockSize = 3;
			final int minInternalNodeBlockSize = 1;

			// four single-leaf source pages, four elements each, disjoint ascending ranges
			final List<TransactionalElementBPlusTree<PriceRecordContract>> leaves = new ArrayList<>(4);
			final int[] pageSequences = new int[4];
			for (int i = 0; i < 4; i++) {
				leaves.add(
					singleLeaf(
						valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize,
						i * 100, i * 100 + 1, i * 100 + 2, i * 100 + 3
					)
				);
				pageSequences[i] = i;
			}
			// with a four-child capacity, all four leaves assemble under a single root that is already at capacity
			final TransactionalElementBPlusTree<PriceRecordContract> tree =
				treeOf(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize)
					.assembleFromSingleLeafTrees(leaves, pageSequences, "fully packed root regression test");

			// the first leaf splitting under the already-full root must still be absorbed, without the root ever being
			// asked to accommodate a child it has no room for
			assertDoesNotThrow(() -> {
				for (int key = 4; key < valueBlockSize; key++) {
					tree.insert(rec(key));
				}
			});

			final int[] expectedKeys = new int[valueBlockSize + 3 * 4];
			int index = 0;
			for (int key = 0; key < valueBlockSize; key++) {
				expectedKeys[index++] = key;
			}
			for (int leafIndex = 1; leafIndex < 4; leafIndex++) {
				final int base = leafIndex * 100;
				for (int k = 0; k < 4; k++) {
					expectedKeys[index++] = base + k;
				}
			}
			verifyTreeConsistency(tree, expectedKeys);
		}

	}
}
