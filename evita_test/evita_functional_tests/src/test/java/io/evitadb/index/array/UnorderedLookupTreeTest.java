/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.IntLongMap;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the contract of {@link UnorderedLookupTree} - the count-augmented, order-key-routed position
 * tree of the two-tree backing for {@link UnorderedLookup}. The tree is driven purely by order-key / position and
 * reports order-key assignments through an {@link OrderKeyConsumer}; the test pairs it with a stand-in value index
 * (an `int → long` map, exactly the role the real value index plays) so it can address records by id and assert the
 * order-key coordination stays coherent through splits and re-spacing.
 *
 * The exhaustive, time-bounded randomized soak coverage lives in `LongRunningUnorderedLookupTreeTest`; the
 * randomized methods retained here are deliberately small, fixed-seed smoke checks that keep the fast loop honest.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("UnorderedLookupTree")
class UnorderedLookupTreeTest {

	/**
	 * Bundles the position tree with a stand-in value index (`recordId → orderKey`), mirroring the composite that
	 * will drive the tree in production. Implements {@link OrderKeyConsumer} to keep the index coherent.
	 */
	private static final class TreeWithIndex implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree tree;
		@Nonnull final IntLongMap valueIndex = new IntLongHashMap();

		TreeWithIndex() {
			this.tree = new UnorderedLookupTree();
		}

		TreeWithIndex(long orderKeyGap) {
			this.tree = new UnorderedLookupTree(orderKeyGap);
		}

		TreeWithIndex(int blockSize, long orderKeyGap) {
			this.tree = new UnorderedLookupTree(blockSize, orderKeyGap);
		}

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
		}

		void bulkLoad(@Nonnull int[] recordIds) {
			this.tree.bulkLoad(recordIds, this);
		}

		void addAtPosition(int index, int recordId) {
			this.tree.insertAtPosition(index, recordId, this);
		}

		void addAfter(int previousRecordId, int recordId) {
			this.tree.insertAfter(this.valueIndex.get(previousRecordId), previousRecordId, recordId, this);
		}

		void addAtHead(int recordId) {
			this.tree.insertAtPosition(0, recordId, this);
		}

		void remove(int recordId) {
			this.tree.removeByOrderKey(this.valueIndex.get(recordId), recordId, this);
			this.valueIndex.remove(recordId);
		}

		int findPosition(int recordId) {
			return this.tree.findPositionByOrderKey(this.valueIndex.get(recordId), recordId);
		}

		boolean contains(int recordId) {
			return this.valueIndex.containsKey(recordId);
		}
	}

	/**
	 * Asserts that the tree's flattened state matches the oracle, that addressing every record by id (via the
	 * stand-in value index) resolves to its true position, AND that the structural consistency report is CONSISTENT
	 * (equal leaf depth / balance, correct subtree-count augmentation, exact order-key separators, strictly increasing
	 * container keys, internal-node minimum occupancy and tracked-size accuracy). The oracle stays a trivially-correct
	 * `List<Integer>`, so any divergence pins a real bug in the tree; the consistency report independently catches
	 * structural corruption that the flattened-array oracle would not surface.
	 */
	private static void assertConsistentWithOracle(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
		final int[] expected = new int[oracle.size()];
		for (int i = 0; i < oracle.size(); i++) {
			expected[i] = oracle.get(i);
		}
		assertArrayEquals(expected, tested.tree.getArray(), "Flattened array mismatch");
		assertEquals(oracle.size(), tested.tree.size());
		for (int position = 0; position < expected.length; position++) {
			final int recordId = expected[position];
			assertEquals(recordId, tested.tree.getRecordAt(position), "getRecordAt mismatch at " + position);
			assertEquals(position, tested.findPosition(recordId), "findPosition mismatch for " + recordId);
		}
		assertConsistent(tested);
	}

	/**
	 * Asserts the structural consistency report of the tree is CONSISTENT, surfacing the report message on failure so a
	 * production steal/merge or augmentation bug is pinpointed exactly. Used both inside the oracle assertion and on its
	 * own after each mutation in the rebalancing scenarios.
	 */
	private static void assertConsistent(@Nonnull TreeWithIndex tested) {
		final ConsistencyReport report = tested.tree.getConsistencyReport();
		assertEquals(
			ConsistencyState.CONSISTENT, report.state(),
			"Tree reported structural inconsistency:\n" + report.report()
		);
	}

	@Nested
	@DisplayName("Construction and bulk load")
	class BulkLoadTest {

		@Test
		@DisplayName("bulk-loads an empty array and stays pristine and usable")
		void shouldBulkLoadEmptyArrayAndStayEmpty() {
			final TreeWithIndex tested = new TreeWithIndex();
			// a fresh tree reports empty
			assertTrue(tested.tree.isEmpty());
			assertEquals(0, tested.tree.size());

			tested.bulkLoad(new int[0]);

			// an empty bulk-load is a no-op and must NOT trip the "non-empty tree" guard
			assertTrue(tested.tree.isEmpty());
			assertEquals(0, tested.tree.size());
			assertArrayEquals(new int[0], tested.tree.getArray());

			// a subsequent real insert still works (the tree was left pristine)
			tested.addAtHead(7);
			assertArrayEquals(new int[]{7}, tested.tree.getArray());
			assertFalse(tested.tree.isEmpty());
			assertEquals(1, tested.tree.size());
			assertConsistent(tested);
		}

		@Test
		@DisplayName("renders an empty array and string for a fresh tree")
		void shouldRenderEmptyTreeArrayAndString() {
			final TreeWithIndex tested = new TreeWithIndex();
			assertArrayEquals(new int[0], tested.tree.getArray());
			assertEquals("UnorderedLookupTree[]", tested.tree.toString());
		}

		@Test
		@DisplayName("bulk-loads a small array and addresses every record by position")
		void shouldBulkLoadAndAddressByPosition() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{4, 2, 3, 1});
			assertArrayEquals(new int[]{4, 2, 3, 1}, tested.tree.getArray());
			assertEquals(0, tested.findPosition(4));
			assertEquals(1, tested.findPosition(2));
			assertEquals(2, tested.findPosition(3));
			assertEquals(3, tested.findPosition(1));
			assertEquals(4, tested.tree.getRecordAt(0));
			assertEquals(1, tested.tree.getRecordAt(3));
			assertEquals(1, tested.tree.getLastRecordId());
			assertConsistent(tested);
		}

		@Test
		@DisplayName("bulk-loads a large array and stays consistent through later splits and collapses")
		void shouldBulkLoadLargeArrayAndStayConsistent() {
			final int count = UnorderedLookupTree.DEFAULT_BLOCK_SIZE * UnorderedLookupTree.DEFAULT_BLOCK_SIZE * 4;
			final int[] input = new int[count];
			final List<Integer> oracle = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				input[i] = 1_000_000 - i;
				oracle.add(input[i]);
			}
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(input);
			assertConsistentWithOracle(tested, oracle);
			// remove a scattered third then prepend, to exercise post-bulk-load splits/collapses
			final Random random = new Random(99);
			for (int i = 0; i < count / 3; i++) {
				tested.remove(oracle.remove(random.nextInt(oracle.size())));
			}
			for (int i = 0; i < 500; i++) {
				tested.addAtHead(2_000_000 + i);
				oracle.add(0, 2_000_000 + i);
			}
			assertConsistentWithOracle(tested, oracle);

			// after deep multi-level growth the rightmost descent must land on the true last record
			assertEquals(oracle.get(oracle.size() - 1).intValue(), tested.tree.getLastRecordId());
			// removing the current last record re-targets getLastRecordId to the new tail
			final int lastRecordId = oracle.remove(oracle.size() - 1);
			tested.remove(lastRecordId);
			assertEquals(oracle.get(oracle.size() - 1).intValue(), tested.tree.getLastRecordId());
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("rejects a bulk-load issued on a non-empty tree")
		void shouldRejectBulkLoadOnNonEmptyTree() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{1, 2, 3});
			// bulk-load is only legal on a pristine, empty tree
			assertThrows(GenericEvitaInternalError.class, () -> tested.bulkLoad(new int[]{4, 5}));
		}
	}

	@Nested
	@DisplayName("Core operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("returns a fresh array after a mutation invalidates the memo cache")
		void shouldReturnFreshArrayAfterMutationInvalidatesCache() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{4, 2, 3, 1});

			// populate the outside-transaction memo cache and confirm repeated reads are content-equal
			final int[] first = tested.tree.getArray();
			final int[] second = tested.tree.getArray();
			assertArrayEquals(new int[]{4, 2, 3, 1}, first);
			assertArrayEquals(first, second);

			// a mutation must invalidate the cache so the next read reflects the new contents
			tested.addAtHead(9);
			final int[] afterMutation = tested.tree.getArray();
			assertArrayEquals(new int[]{9, 4, 2, 3, 1}, afterMutation);
			assertConsistent(tested);
		}

		@Test
		@DisplayName("adds records after a predecessor and at the head while keeping positions coherent")
		void shouldAddRecordsAfterAndAtHead() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.addAtHead(3);
			assertArrayEquals(new int[]{3}, tested.tree.getArray());
			tested.addAtHead(5);
			assertArrayEquals(new int[]{5, 3}, tested.tree.getArray());
			tested.addAfter(5, 1);
			assertArrayEquals(new int[]{5, 1, 3}, tested.tree.getArray());
			tested.addAfter(3, 2);
			assertArrayEquals(new int[]{5, 1, 3, 2}, tested.tree.getArray());
			tested.addAtHead(0);
			assertArrayEquals(new int[]{0, 5, 1, 3, 2}, tested.tree.getArray());
			tested.addAfter(2, 10);
			assertArrayEquals(new int[]{0, 5, 1, 3, 2, 10}, tested.tree.getArray());
			assertEquals(10, tested.tree.getLastRecordId());
			assertConsistent(tested);
		}

		@Test
		@DisplayName("removes records and keeps the remaining positions coherent")
		void shouldRemoveRecordsAndKeepPositions() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{4, 2, 3, 1, 6, 5});
			tested.remove(1);
			assertArrayEquals(new int[]{4, 2, 3, 6, 5}, tested.tree.getArray());
			tested.remove(4);
			assertArrayEquals(new int[]{2, 3, 6, 5}, tested.tree.getArray());
			tested.remove(5);
			assertArrayEquals(new int[]{2, 3, 6}, tested.tree.getArray());
			assertFalse(tested.contains(5));
			tested.remove(3);
			tested.remove(2);
			tested.remove(6);
			assertArrayEquals(new int[0], tested.tree.getArray());
			assertTrue(tested.tree.isEmpty());
			assertConsistent(tested);
		}

		@Test
		@DisplayName("grows beyond a single container and stays consistent across splits")
		void shouldGrowBeyondSingleContainerAndStayConsistent() {
			// 5x the block size forces several container splits and at least one internal split
			final int count = UnorderedLookupTree.DEFAULT_BLOCK_SIZE * 5;
			final TreeWithIndex tested = new TreeWithIndex();
			final List<Integer> oracle = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				tested.addAtPosition(i, 1000 + i);
				oracle.add(1000 + i);
			}
			assertConsistentWithOracle(tested, oracle);
		}
	}

	@Nested
	@DisplayName("Order-key spacing and re-spacing")
	class OrderKeySpacingTest {

		@Test
		@DisplayName("stays consistent when a tiny order-key gap forces repeated re-spacing")
		void shouldStayConsistentWhenOrderKeyGapExhausts() {
			// a tiny order-key gap forces repeated re-spacing as the leftmost region keeps splitting under head inserts
			final TreeWithIndex tested = new TreeWithIndex(4L);
			final List<Integer> oracle = new ArrayList<>();
			for (int i = 0; i < 2_000; i++) {
				tested.addAtHead(1000 + i);
				oracle.add(0, 1000 + i);
			}
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("surfaces a misconfigured order-key gap that cannot subdivide on split")
		void shouldThrowWhenOrderKeyGapCannotSubdivideOnSplit() {
			// with a gap of 1 the two bulk-loaded containers sit exactly one order-key apart (keys 0 and 1), so even a
			// full re-spacing cannot mint a key between them - overflowing the left container must surface that
			// misconfiguration instead of minting a colliding order-key
			final TreeWithIndex tested = new TreeWithIndex(1L);
			final int[] recordIds = new int[UnorderedLookupTree.DEFAULT_BLOCK_SIZE + 1];
			for (int i = 0; i < recordIds.length; i++) {
				recordIds[i] = i + 1;
			}
			tested.bulkLoad(recordIds);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.addAtHead(UnorderedLookupTree.DEFAULT_BLOCK_SIZE + 2)
			);
		}
	}

	@Nested
	@DisplayName("Randomized oracle smoke (fixed seed)")
	class RandomizedSmokeTest {

		@Test
		@DisplayName("survives a small randomized insert/remove run against an in-memory oracle")
		void shouldSurviveRandomizedInsertRemoveAgainstOracle() {
			final Random random = new Random(42);
			final TreeWithIndex tested = new TreeWithIndex();
			final List<Integer> oracle = new ArrayList<>();
			int nextRecordId = 1;
			for (int op = 0; op < 2_000; op++) {
				final int size = oracle.size();
				final boolean insert = size == 0 || random.nextInt(100) < 60;
				if (insert) {
					final int index = random.nextInt(size + 1);
					final int recordId = nextRecordId++;
					tested.addAtPosition(index, recordId);
					oracle.add(index, recordId);
				} else {
					final int index = random.nextInt(size);
					tested.remove(oracle.remove(index));
				}
			}
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("survives a small randomized add-after run against an in-memory oracle")
		void shouldSurviveRandomizedAddAfterAgainstOracle() {
			final Random random = new Random(7);
			final TreeWithIndex tested = new TreeWithIndex();
			final List<Integer> oracle = new ArrayList<>();
			int nextRecordId = 1;
			for (int op = 0; op < 2_000; op++) {
				final int size = oracle.size();
				final boolean insert = size == 0 || random.nextInt(100) < 60;
				if (insert) {
					final int recordId = nextRecordId++;
					if (size == 0 || random.nextInt(10) == 0) {
						tested.addAtHead(recordId);
						oracle.add(0, recordId);
					} else {
						final int prevIndex = random.nextInt(size);
						tested.addAfter(oracle.get(prevIndex), recordId);
						oracle.add(prevIndex + 1, recordId);
					}
				} else {
					tested.remove(oracle.remove(random.nextInt(size)));
				}
			}
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("matches the array delegate operation-for-operation on a small randomized run")
		void shouldMatchArrayDelegateForIdenticalOperations() {
			final Random random = new Random(123);
			final TreeWithIndex tested = new TreeWithIndex();
			final UnorderedLookup array = new UnorderedLookup(new int[0]);
			final List<Integer> live = new ArrayList<>();
			int nextRecordId = 1;
			for (int op = 0; op < 500; op++) {
				final int size = live.size();
				final boolean insert = size == 0 || random.nextInt(100) < 60;
				if (insert) {
					final int recordId = nextRecordId++;
					if (size == 0) {
						tested.addAtHead(recordId);
						array.addRecord(Integer.MIN_VALUE, recordId);
						live.add(0, recordId);
					} else {
						final int prev = live.get(random.nextInt(size));
						tested.addAfter(prev, recordId);
						array.addRecord(prev, recordId);
						live.add(live.indexOf(prev) + 1, recordId);
					}
				} else {
					final int recordId = live.remove(random.nextInt(size));
					tested.remove(recordId);
					array.removeRecord(recordId);
				}
				// the tree's permutation must match the array delegate operation for operation
				assertArrayEquals(array.getArray(), tested.tree.getArray(), "permutation mismatch at op " + op);
			}
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("throws when routing by order-key into an empty tree")
		void shouldThrowWhenLocatingByOrderKeyInEmptyTree() {
			final TreeWithIndex empty = new TreeWithIndex();
			// routing by order-key into an empty tree is an inconsistent lookup state
			assertThrows(
				GenericEvitaInternalError.class,
				() -> empty.tree.findPositionByOrderKey(0L, 1)
			);
		}

		@Test
		@DisplayName("throws when addressing a position outside the valid bounds")
		void shouldThrowWhenAddressingPositionOutOfBounds() {
			final TreeWithIndex empty = new TreeWithIndex();
			// any position on an empty tree is out of bounds
			assertThrows(GenericEvitaInternalError.class, () -> empty.tree.getRecordAt(0));

			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{4, 2, 3, 1});
			assertThrows(GenericEvitaInternalError.class, () -> tested.tree.getRecordAt(-1));
			// position == size is just past the last valid index
			assertThrows(GenericEvitaInternalError.class, () -> tested.tree.getRecordAt(4));
		}

		@Test
		@DisplayName("throws when asking for the last record id of an empty tree")
		void shouldThrowWhenGettingLastRecordIdOfEmptyTree() {
			final TreeWithIndex empty = new TreeWithIndex();
			assertThrows(ArrayIndexOutOfBoundsException.class, empty.tree::getLastRecordId);
		}

		@Test
		@DisplayName("throws when locating a record missing from the routed container")
		void shouldThrowWhenLocatingRecordMissingFromRoutedContainer() {
			final TreeWithIndex tested = new TreeWithIndex();
			// a handful of records all live in the single root container with order-key 0
			tested.bulkLoad(new int[]{1, 2, 3});
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.tree.findPositionByOrderKey(0L, 999)
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("inserts a single element and removes it back to empty, then reuses the tree")
		void shouldInsertSingleElementRemoveToEmptyAndReuse() {
			final TreeWithIndex tested = new TreeWithIndex();

			tested.addAtHead(42);
			assertArrayEquals(new int[]{42}, tested.tree.getArray());
			assertEquals(1, tested.tree.size());
			assertEquals(42, tested.tree.getLastRecordId());
			assertConsistent(tested);

			tested.remove(42);
			assertTrue(tested.tree.isEmpty());
			assertEquals(0, tested.tree.size());
			assertArrayEquals(new int[0], tested.tree.getArray());
			// the empty-tree report must stay CONSISTENT (null root with size 0)
			assertConsistent(tested);

			// the tree is reusable after being drained to empty (root was reset to null, not left dangling)
			tested.addAtHead(7);
			tested.addAfter(7, 8);
			assertArrayEquals(new int[]{7, 8}, tested.tree.getArray());
			assertConsistent(tested);
		}

		@Test
		@DisplayName("drains a multi-level tree one record at a time, staying consistent and collapsing to empty")
		void shouldDrainMultiLevelTreeToEmpty() {
			// small block size builds a multi-level tree from only a few dozen records
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final List<Integer> oracle = new ArrayList<>();
			for (int i = 0; i < 60; i++) {
				tested.addAtPosition(i, 1000 + i);
				oracle.add(1000 + i);
			}
			assertConsistentWithOracle(tested, oracle);

			// remove from the middle every time, forcing container empties scattered across the tree and repeated
			// internal steal/merge consolidation, asserting CONSISTENT after EVERY single delete
			final Random random = new Random(31);
			while (!oracle.isEmpty()) {
				final int index = random.nextInt(oracle.size());
				tested.remove(oracle.remove(index));
				assertConsistent(tested);
			}
			assertTrue(tested.tree.isEmpty());
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("bulk-loads an exactly-block-size array into a single full container")
		void shouldBulkLoadExactlyOneFullContainer() {
			final int blockSize = 6;
			final TreeWithIndex tested = new TreeWithIndex(blockSize, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final int[] input = new int[blockSize];
			final List<Integer> oracle = new ArrayList<>(blockSize);
			for (int i = 0; i < blockSize; i++) {
				input[i] = 500 - i;
				oracle.add(input[i]);
			}
			tested.bulkLoad(input);
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("bulk-loads an array spanning many containers and several internal levels")
		void shouldBulkLoadManyContainersSpanningSeveralLevels() {
			final int blockSize = 4;
			// blockSize^3 records force a three-level tree straight out of bulk-load
			final int count = blockSize * blockSize * blockSize + 5;
			final TreeWithIndex tested = new TreeWithIndex(blockSize, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final int[] input = new int[count];
			final List<Integer> oracle = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				input[i] = 1 + i;
				oracle.add(input[i]);
			}
			tested.bulkLoad(input);
			assertConsistentWithOracle(tested, oracle);
		}
	}

	@Nested
	@DisplayName("Rebalancing at a small block size")
	class RebalancingTest {

		/**
		 * Builds a tree at the given small block size holding records `1..count` in logical order (so the record id at
		 * logical position `p` is `p + 1`), asserting it is consistent before the deletion scenario starts.
		 */
		@Nonnull
		private TreeWithIndex grownTree(int blockSize, int count, @Nonnull List<Integer> oracle) {
			final TreeWithIndex tested = new TreeWithIndex(blockSize, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			for (int i = 0; i < count; i++) {
				tested.addAtPosition(i, 1 + i);
				oracle.add(1 + i);
			}
			assertConsistentWithOracle(tested, oracle);
			return tested;
		}

		/**
		 * Removes from the head until the tree is empty, asserting structural consistency after each delete. Forcing a
		 * long run of head removals drives the leftmost containers empty one after another, which makes the left edge of
		 * the tree the perpetual underflow site — exercising steal-from-right and merge-with-right consolidation as well
		 * as repeated root collapses as the height shrinks.
		 */
		private void drainFromHead(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
			while (!oracle.isEmpty()) {
				tested.remove(oracle.remove(0));
				assertConsistent(tested);
			}
			assertTrue(tested.tree.isEmpty());
		}

		/**
		 * Removes from the tail until the tree is empty, asserting structural consistency after each delete. Tail
		 * removals empty the rightmost containers, making the right edge the perpetual underflow site — exercising
		 * steal-from-left and merge-with-left consolidation and root collapses.
		 */
		private void drainFromTail(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
			while (!oracle.isEmpty()) {
				tested.remove(oracle.remove(oracle.size() - 1));
				assertConsistent(tested);
			}
			assertTrue(tested.tree.isEmpty());
		}

		@Test
		@DisplayName("repairs underflow by stealing from the left sibling while draining from the tail")
		void shouldStealFromLeftSiblingWhileDrainingTail() {
			// minimum block size (3) gives minChildren = 2, so the first internal underflow after a single child loss
			// must be repaired by a steal when a sibling has > 2 children to spare
			final List<Integer> oracle = new ArrayList<>();
			final TreeWithIndex tested = grownTree(3, 40, oracle);
			drainFromTail(tested, oracle);
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("repairs underflow by stealing from the right sibling while draining from the head")
		void shouldStealFromRightSiblingWhileDrainingHead() {
			final List<Integer> oracle = new ArrayList<>();
			final TreeWithIndex tested = grownTree(3, 40, oracle);
			drainFromHead(tested, oracle);
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("repairs underflow by merging siblings and collapsing the root on a larger block size")
		void shouldMergeSiblingsAndCollapseRootWhileDraining() {
			// a wider fan-out (8 -> minChildren = 4) plus enough records to build three levels makes merges the common
			// repair once siblings shrink to the minimum, cascading up and collapsing the root as the height drops
			final List<Integer> oracle = new ArrayList<>();
			final TreeWithIndex tested = grownTree(8, 300, oracle);
			drainFromTail(tested, oracle);
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("stays balanced through interleaved middle deletes and head re-inserts at a small block size")
		void shouldStayBalancedThroughInterleavedDeletesAndInserts() {
			final List<Integer> oracle = new ArrayList<>();
			final TreeWithIndex tested = grownTree(5, 120, oracle);
			final Random random = new Random(2027);
			int nextRecordId = 10_000;
			// alternate a burst of deletes (which trigger steal/merge) with a burst of head inserts (which trigger
			// splits and root growth) so both the grow and shrink paths are exercised against the same instance
			for (int round = 0; round < 60 && !oracle.isEmpty(); round++) {
				final int deletes = 1 + random.nextInt(3);
				for (int d = 0; d < deletes && !oracle.isEmpty(); d++) {
					tested.remove(oracle.remove(random.nextInt(oracle.size())));
					assertConsistent(tested);
				}
				final int inserts = 1 + random.nextInt(3);
				for (int ins = 0; ins < inserts; ins++) {
					final int recordId = nextRecordId++;
					tested.addAtHead(recordId);
					oracle.add(0, recordId);
					assertConsistent(tested);
				}
			}
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("cascades merges all the way up and collapses a three-level tree back to a single container")
		void shouldCascadeMergesAndCollapseToSingleContainer() {
			// build a genuine three-level tree at the minimum fan-out, then drain it completely; the final deletes must
			// cascade merges up through every internal level and collapse the root down to one leaf and then to empty,
			// keeping every leaf at equal depth throughout
			final List<Integer> oracle = new ArrayList<>();
			final TreeWithIndex tested = grownTree(3, 100, oracle);
			final Random random = new Random(909);
			while (oracle.size() > 1) {
				tested.remove(oracle.remove(random.nextInt(oracle.size())));
				assertConsistent(tested);
			}
			// one record left - the tree must now be a single root container (height fully collapsed)
			assertConsistentWithOracle(tested, oracle);
			tested.remove(oracle.remove(0));
			assertTrue(tested.tree.isEmpty());
			assertConsistent(tested);
		}
	}

	@Nested
	@DisplayName("Deletion-heavy churn")
	class DeletionHeavyChurnTest {

		/**
		 * Runs a churn that biases toward inserts until the tree reaches `highWaterMark`, then biases toward deletes,
		 * asserting structural consistency after EVERY operation. This repeatedly grows the tree past several internal
		 * levels and then shrinks it back down, hammering the split / steal / merge / root-collapse machinery.
		 */
		private void runDeletionHeavyChurn(int blockSize, long orderKeyGap, int highWaterMark, int operations, long seed) {
			final TreeWithIndex tested = new TreeWithIndex(blockSize, orderKeyGap);
			final List<Integer> oracle = new ArrayList<>();
			final Random random = new Random(seed);
			int nextRecordId = 1;
			for (int op = 0; op < operations; op++) {
				final int size = oracle.size();
				// below the high-water mark inserts dominate (grow); above it deletes dominate (shrink)
				final int insertChance = size < highWaterMark ? 70 : 25;
				final boolean insert = size == 0 || random.nextInt(100) < insertChance;
				if (insert) {
					final int index = random.nextInt(size + 1);
					final int recordId = nextRecordId++;
					tested.addAtPosition(index, recordId);
					oracle.add(index, recordId);
				} else {
					tested.remove(oracle.remove(random.nextInt(size)));
				}
				assertConsistent(tested);
			}
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("stays consistent under deletion-biased churn at the minimum block size")
		void shouldStayConsistentUnderDeletionBiasedChurnAtMinBlockSize() {
			runDeletionHeavyChurn(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, 200, 4_000, 11);
		}

		@Test
		@DisplayName("stays consistent under deletion-biased churn at a medium block size")
		void shouldStayConsistentUnderDeletionBiasedChurnAtMediumBlockSize() {
			runDeletionHeavyChurn(8, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, 400, 4_000, 17);
		}

		@Test
		@DisplayName("stays consistent under deletion-biased churn at the production block size")
		void shouldStayConsistentUnderDeletionBiasedChurnAtDefaultBlockSize() {
			runDeletionHeavyChurn(
				UnorderedLookupTree.DEFAULT_BLOCK_SIZE, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP,
				2_000, 8_000, 23
			);
		}

		@Test
		@DisplayName("remains balanced (equal leaf depth) after a full grow-then-drain cycle at a small block size")
		void shouldRemainBalancedAfterGrowThenDrainCycle() {
			// grow well past several internal levels, then drain almost everything; the consistency report enforces
			// equal leaf depth on every check, so surviving the whole cycle CONSISTENT is the height/balance proof
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final List<Integer> oracle = new ArrayList<>();
			for (int i = 0; i < 1_000; i++) {
				tested.addAtPosition(i, 1 + i);
				oracle.add(1 + i);
			}
			assertConsistentWithOracle(tested, oracle);

			final Random random = new Random(4242);
			while (oracle.size() > 5) {
				tested.remove(oracle.remove(random.nextInt(oracle.size())));
				assertConsistent(tested);
			}
			assertConsistentWithOracle(tested, oracle);
		}
	}

}
