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

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			return treeOf(blockSize).assembleFromSingleLeafTrees(pageTrees, persisted.sequences());
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
}
