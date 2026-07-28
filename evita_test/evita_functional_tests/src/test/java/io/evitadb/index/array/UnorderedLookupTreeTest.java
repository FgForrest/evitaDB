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
import io.evitadb.index.bPlusTree.PagedLeafHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

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

	/**
	 * Verifies every head query (headRank / selectHead / findHeadCovering) against a trivially-correct brute-force
	 * oracle derived from the record order and the set of head record ids, so any divergence pins a real head-tracking
	 * bug in the tree.
	 */
	private static void assertHeadQueriesMatchOracle(
		@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle, @Nonnull Set<Integer> heads
	) {
		final int size = oracle.size();
		// brute-force head positions in ascending logical order
		final List<Integer> headPositions = new ArrayList<>();
		for (int p = 0; p < size; p++) {
			if (heads.contains(oracle.get(p))) {
				headPositions.add(p);
			}
		}
		// headRank(p): number of heads at positions [0, p]
		for (int p = 0; p < size; p++) {
			int expectedRank = 0;
			for (final int hp : headPositions) {
				if (hp <= p) {
					expectedRank++;
				} else {
					break;
				}
			}
			assertEquals(expectedRank, tested.tree.headRank(p), "headRank mismatch at position " + p);
		}
		// selectHead(k): position + record id of the k-th head (1-indexed)
		for (int k = 1; k <= headPositions.size(); k++) {
			final int expectedPos = headPositions.get(k - 1);
			final long packed = tested.tree.selectHead(k);
			assertEquals(expectedPos, (int) (packed >> 32), "selectHead position mismatch at rank " + k);
			assertEquals(
				oracle.get(expectedPos).intValue(), (int) packed, "selectHead record id mismatch at rank " + k);
		}
		// findHeadCovering(p): the head at the greatest head-position <= p (only defined where such a head exists)
		for (int p = 0; p < size; p++) {
			int coverPos = -1;
			for (final int hp : headPositions) {
				if (hp <= p) {
					coverPos = hp;
				} else {
					break;
				}
			}
			if (coverPos >= 0) {
				final long packed = tested.tree.findHeadCovering(p);
				assertEquals(coverPos, (int) (packed >> 32), "findHeadCovering position mismatch at position " + p);
				assertEquals(
					oracle.get(coverPos).intValue(), (int) packed,
					"findHeadCovering record id mismatch at position " + p
				);
			}
		}
	}

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

		TreeWithIndex(int blockSize, long orderKeyGap, boolean headAware) {
			this.tree = new UnorderedLookupTree(blockSize, orderKeyGap, headAware);
		}

		TreeWithIndex(@Nonnull UnorderedLookupTree tree) {
			this.tree = tree;
		}

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
		}

		void bulkLoad(@Nonnull int[] recordIds) {
			this.tree.bulkLoad(recordIds, this);
		}

		void bulkLoadWithHeads(@Nonnull int[] recordIds, @Nonnull int[] sortedHeadPositions) {
			this.tree.bulkLoadWithHeads(recordIds, sortedHeadPositions, this);
		}

		void markHead(int recordId) {
			this.tree.markHead(this.valueIndex.get(recordId), recordId);
		}

		void unmarkHead(int recordId) {
			this.tree.unmarkHead(this.valueIndex.get(recordId), recordId);
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
		private static TreeWithIndex grownTree(int blockSize, int count, @Nonnull List<Integer> oracle) {
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
		private static void drainFromHead(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
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
		private static void drainFromTail(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> oracle) {
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
		private static void runDeletionHeavyChurn(
			int blockSize, long orderKeyGap, int highWaterMark, int operations, long seed) {
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

	@Nested
	@DisplayName("Head tracking (chain-head bitmask augmentation)")
	class HeadTrackingTest {

		@Test
		@DisplayName("accepts a head-aware tree at the full block size and rejects an over-sized fan-out")
		void shouldAcceptFullBlockSizeHeadAwareTreeAndRejectOversizedFanOut() {
			// the head mask is now a long[] sized by leafCapacity, so head-awareness no longer caps the fan-out: a
			// head-aware tree at the full DEFAULT_BLOCK_SIZE fan-out is valid (would throw here if rejected)
			new UnorderedLookupTree(
				UnorderedLookupTree.DEFAULT_BLOCK_SIZE, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true);
			// a fan-out above DEFAULT_BLOCK_SIZE is still rejected (the routing spine stays small)
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new UnorderedLookupTree(
					UnorderedLookupTree.DEFAULT_BLOCK_SIZE + 1, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true
				)
			);
		}

		@Test
		@DisplayName("rejects head operations on a non-head-aware tree")
		void shouldRejectHeadOperationsOnNonHeadAwareTree() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{1, 2, 3});
			assertThrows(GenericEvitaInternalError.class, () -> tested.tree.findHeadCovering(0));
			assertThrows(GenericEvitaInternalError.class, () -> tested.tree.headRank(0));
			assertThrows(GenericEvitaInternalError.class, () -> tested.tree.selectHead(1));
			assertThrows(GenericEvitaInternalError.class, () -> tested.markHead(1));
			assertThrows(GenericEvitaInternalError.class, () -> tested.unmarkHead(1));
			// no head structures are maintained, so the consistency report stays clean
			assertConsistent(tested);
		}

		@Test
		@DisplayName("marks and unmarks idempotently, mirrored by the head queries")
		void shouldMarkAndUnmarkIdempotently() {
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true);
			for (int i = 0; i < 10; i++) {
				tested.addAtPosition(i, 100 + i);
			}
			tested.markHead(100);       // position 0
			tested.markHead(100);       // idempotent - must not double-count
			tested.markHead(105);       // position 5
			assertConsistent(tested);
			assertEquals(1, tested.tree.headRank(0));
			assertEquals(2, tested.tree.headRank(9));
			// unmarking twice is idempotent
			tested.unmarkHead(105);
			tested.unmarkHead(105);
			assertConsistent(tested);
			assertEquals(1, tested.tree.headRank(9));
		}

		@Test
		@DisplayName("preserves a top-slot head through a full-container split at the production block size")
		void shouldPreserveTopSlotHeadThroughFullContainerSplit() {
			// fill a container to capacity at the head-aware production block size (63), mark the record in its TOP slot,
			// then middle-insert to push it to 64 and force a split - the top-slot head must survive
			final int blockSize = UnorderedLookupTree.DEFAULT_BLOCK_SIZE - 1;
			final TreeWithIndex tested = new TreeWithIndex(blockSize, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true);
			for (int i = 0; i < blockSize; i++) {
				tested.addAtPosition(i, 1000 + i);
			}
			tested.markHead(1000);                    // position 0
			tested.markHead(1000 + blockSize - 1);    // position 62 (top slot -> mask bit 62)
			assertConsistent(tested);
			// a middle insert pushes the container to 64 records and forces a split; the top-slot head shifts to position 63
			tested.addAtPosition(30, 9999);
			assertConsistent(tested);
			final long covering = tested.tree.findHeadCovering(63);
			assertEquals(63, (int) (covering >> 32), "top-slot head lost its position through the split");
			assertEquals(1000 + blockSize - 1, (int) covering, "top-slot head lost its identity through the split");
			assertEquals(2, tested.tree.headRank(63), "a head bit was dropped by the split");
		}

		@Test
		@DisplayName("sets head marks during a head-aware bulk load")
		void shouldBulkLoadWithHeadMarks() {
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true);
			final int n = 50;
			final int[] recordIds = new int[n];
			final List<Integer> oracle = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				recordIds[i] = 100 + i;
				oracle.add(100 + i);
			}
			final int[] headPositions = {0, 5, 20, 33, 49};
			tested.bulkLoadWithHeads(recordIds, headPositions);
			assertConsistent(tested);
			final Set<Integer> heads = new HashSet<>();
			for (final int hp : headPositions) {
				heads.add(100 + hp);
			}
			assertHeadQueriesMatchOracle(tested, oracle, heads);
		}

		@Test
		@DisplayName("tracks heads against a brute-force oracle through randomized churn at a small block size")
		void shouldTrackHeadsThroughRandomizedChurn() {
			// a small block size forces frequent splits / steals / merges so the head mask + head counts are exercised
			// through every structural operation; the brute-force oracle pins any desync
			final Random random = new Random(20260702);
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true);
			final List<Integer> oracle = new ArrayList<>();
			final Set<Integer> heads = new HashSet<>();
			int nextRecordId = 1;
			for (int op = 0; op < 4_000; op++) {
				final int size = oracle.size();
				final int choice = random.nextInt(100);
				if (size == 0 || choice < 45) {
					// insert a fresh (non-head) record at a random position
					final int index = random.nextInt(size + 1);
					final int recordId = nextRecordId++;
					tested.addAtPosition(index, recordId);
					oracle.add(index, recordId);
				} else if (choice < 70) {
					// remove a random record (removal auto-clears its head mark)
					final int index = random.nextInt(size);
					final int removed = oracle.remove(index);
					tested.remove(removed);
					heads.remove(removed);
				} else if (choice < 85) {
					// mark a random record as a head
					final int recordId = oracle.get(random.nextInt(size));
					tested.markHead(recordId);
					heads.add(recordId);
				} else {
					// unmark a random record
					final int recordId = oracle.get(random.nextInt(size));
					tested.unmarkHead(recordId);
					heads.remove(recordId);
				}
				if (op % 25 == 0) {
					assertConsistent(tested);
					assertHeadQueriesMatchOracle(tested, oracle, heads);
				}
			}
			assertConsistent(tested);
			assertHeadQueriesMatchOracle(tested, oracle, heads);
		}
	}

	@Nested
	@DisplayName("Paging (page-sized leaves + granular page SPI)")
	class PagingTest {

		/**
		 * Builds a paged, head-aware tree with the given leaf capacity (physical leaf array = leafCapacity + 1). The
		 * internal fan-out is kept at `min(DEFAULT_BLOCK_SIZE, leafCapacity)` so tiny-leaf trees stay valid.
		 */
		@Nonnull
		private static TreeWithIndex pagedTree(int leafCapacity) {
			final int blockSize = Math.min(UnorderedLookupTree.DEFAULT_BLOCK_SIZE, leafCapacity);
			return new TreeWithIndex(new UnorderedLookupTree(
				blockSize, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true, leafCapacity, true));
		}

		@Test
		@DisplayName("preserves order and multi-word head marks across a page-sized-leaf split (leafCapacity=1024)")
		void shouldPreserveOrderAndMultiWordHeadMarksThroughPageSizedSplit() {
			// leafCapacity=1024 -> ceil(1025/64)=17 mask words. Fill exactly one full leaf, mark >64 heads spanning all
			// mask words (incl. the word boundaries 63/64, 127/128, 511/512 and the leaf-top 1023), then a single middle
			// insert overflows the leaf (1025 > 1024): the head at 1023 rides insertHeadSlot into the transient top bit
			// (word 15 -> 16) and then the multi-word split shift. Any bit-op bug shows up in the brute-force head oracle.
			final TreeWithIndex tested = pagedTree(UnorderedLookupTree.PAGE_RECORDS);
			final List<Integer> oracle = new ArrayList<>(UnorderedLookupTree.PAGE_RECORDS + 1);
			for (int i = 0; i < UnorderedLookupTree.PAGE_RECORDS; i++) {
				tested.addAtPosition(i, 1000 + i);
				oracle.add(1000 + i);
			}
			assertFalse(tested.tree.isRootInternal(), "exactly leafCapacity records must still fit a single leaf");
			// mark a dense, >64 head set spanning every mask word, plus the explicit word / leaf boundaries
			final Set<Integer> headPositions = new HashSet<>();
			for (int p = 0; p < UnorderedLookupTree.PAGE_RECORDS; p += 10) {
				headPositions.add(p);
			}
			for (final int p : new int[]{63, 64, 127, 128, 511, 512, 1023}) {
				headPositions.add(p);
			}
			assertTrue(headPositions.size() > 64, "the head set must exercise >= 2 mask words");
			final Set<Integer> heads = new HashSet<>();
			for (final int hp : headPositions) {
				tested.markHead(1000 + hp);
				heads.add(1000 + hp);
			}
			assertConsistent(tested);
			assertHeadQueriesMatchOracle(tested, oracle, heads);
			// one middle insert overflows the leaf and forces the multi-word split shift with real heads in many words
			tested.addAtPosition(100, 90_000);
			oracle.add(100, 90_000);
			assertTrue(tested.tree.isRootInternal(), "the overflow must have split the leaf into >= 2 leaves");
			assertConsistent(tested);
			assertHeadQueriesMatchOracle(tested, oracle, heads);
			// keep churning the middle so leaves split / grow repeatedly; the marks and order must ride every shift
			for (int i = 0; i < 2_000; i++) {
				final int index = (i * 131) % (oracle.size() + 1);
				final int recordId = 200_000 + i;
				tested.addAtPosition(index, recordId);
				oracle.add(index, recordId);
			}
			assertConsistent(tested);
			assertHeadQueriesMatchOracle(tested, oracle, heads);
			assertConsistentWithOracle(tested, oracle);
		}

		@Test
		@DisplayName("bulk-loads a paged head-aware tree past one page into multiple leaves with multi-word masks")
		void shouldBulkLoadPagedTreePastOnePage() {
			// this is the ChainIndex reload path (TransactionalUnorderedIntArray(delegate, headPositions)): pack > 1024
			// records into page-sized leaves, marking heads spanning several mask words, and verify order + head queries
			final TreeWithIndex tested = pagedTree(UnorderedLookupTree.PAGE_RECORDS);
			final int n = UnorderedLookupTree.PAGE_RECORDS * 3 + 137;   // > 1 page -> multiple leaves
			final int[] recordIds = new int[n];
			final List<Integer> oracle = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				recordIds[i] = 1_000 + i;
				oracle.add(1_000 + i);
			}
			// heads every 7th position (spans all mask words of every leaf) plus the exact leaf boundaries
			final List<Integer> headPositionList = new ArrayList<>();
			for (int p = 0; p < n; p += 7) {
				headPositionList.add(p);
			}
			// n is fixed at 3 pages + 137, so every boundary below is < n by construction; only guard against a
			// duplicate (63 is a multiple of 7, so the loop above already added it)
			for (final int p : new int[]{63, 64, 1023, 1024, 2047, 2048}) {
				if (!headPositionList.contains(p)) {
					headPositionList.add(p);
				}
			}
			headPositionList.sort(Integer::compareTo);
			final int[] headPositions = new int[headPositionList.size()];
			for (int i = 0; i < headPositions.length; i++) {
				headPositions[i] = headPositionList.get(i);
			}
			tested.bulkLoadWithHeads(recordIds, headPositions);
			assertTrue(tested.tree.isRootInternal(), "a > 1-page bulk load must produce multiple leaves");
			final Set<Integer> heads = new HashSet<>();
			for (final int hp : headPositions) {
				heads.add(1_000 + hp);
			}
			assertConsistent(tested);
			assertHeadQueriesMatchOracle(tested, oracle, heads);
		}

		@Test
		@DisplayName("enumerates dirty pages / live page sequences after mutations and resets on forgetPageStream")
		void shouldEnumerateAndResetPages() {
			final TreeWithIndex tested = pagedTree(4);   // tiny leaves -> many pages with few records
			for (int i = 0; i < 20; i++) {
				tested.addAtPosition(i, 100 + i);
			}
			assertTrue(tested.tree.isRootInternal(), "20 records at leafCapacity=4 must span multiple leaves");
			final List<UnorderedLookupTree.LeafPageHandle> handles = tested.tree.leafPageHandles();
			assertTrue(handles.size() >= 2, "expected multiple leaf pages");
			// every freshly built leaf is dirty and not yet paged
			assertEquals(handles.size(), tested.tree.collectChangedPages().size(), "all fresh leaves must be dirty");
			for (final UnorderedLookupTree.LeafPageHandle handle : handles) {
				assertEquals(PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE, handle.getPageSequence());
			}
			// simulate a flush: stamp a page sequence on every leaf and clear its dirty flag
			int seq = 0;
			for (final UnorderedLookupTree.LeafPageHandle handle : tested.tree.leafPageHandles()) {
				handle.setPageSequence(seq++);
				handle.clearDirty();
			}
			assertEquals(0, tested.tree.collectChangedPages().size(), "no leaf may be dirty after a simulated flush");
			assertEquals(seq, tested.tree.livePageSequences().length, "every leaf must now carry a live page");
			// a single insert touches only its holding leaf (and at most a split-born sibling) - never every page
			tested.addAfter(105, 9_999);
			final int dirtyAfterInsert = tested.tree.collectChangedPages().size();
			assertTrue(dirtyAfterInsert >= 1 && dirtyAfterInsert <= 2, "one insert must dirty 1 leaf (2 on a split)");
			assertTrue(dirtyAfterInsert < handles.size(), "one insert must NOT dirty every page (granular)");
			// forgetPageStream resets the whole bookkeeping: no live pages, no dirty leaves
			tested.tree.forgetPageStream();
			assertEquals(0, tested.tree.livePageSequences().length, "forgetPageStream must un-assign every page");
			assertEquals(0, tested.tree.collectChangedPages().size(), "forgetPageStream must clear every dirty flag");
		}

		@Test
		@DisplayName("dirty discipline: setOrderKey does NOT dirty a leaf; a content mutation does")
		void shouldNotDirtyOnOrderKeyButDirtyOnContentChange() {
			// respaceOrderKeys re-stamps EVERY container via setOrderKey - it must not re-dirty (and re-emit) every page,
			// so setOrderKey is proven not to set the flag while a content mutator (getRecordIdsForUpdate) does
			final UnorderedLookupTree.LeafNode leaf = new UnorderedLookupTree.LeafNode(false, 4, 1);
			leaf.getRecordIdsForUpdate()[0] = 1;
			leaf.setCount(1);
			assertTrue(leaf.isDirty(), "a content mutation must dirty the leaf");
			leaf.clearDirty();
			leaf.setOrderKey(123_456L);
			assertFalse(leaf.isDirty(), "setOrderKey (order-key re-space) must NOT dirty the leaf");
			leaf.getRecordIdsForUpdate();
			assertTrue(leaf.isDirty(), "a content mutation must dirty the leaf");
		}

		@Test
		@DisplayName("savepoint: pageSequence and dirty survive snapshot -> mutate -> restore")
		void shouldRestorePageSequenceAndDirtyFromMemento() {
			final UnorderedLookupTree.LeafNode leaf = new UnorderedLookupTree.LeafNode(false, 4, 1);
			leaf.getRecordIdsForUpdate()[0] = 7;
			leaf.setCount(1);          // dirty = true
			leaf.setPageSequence(42);
			final UnorderedLookupTree.LeafNode.LeafNodeMemento memento = leaf.snapshot();
			// mutate away from the snapshot: re-page and clear the dirty flag
			leaf.setPageSequence(99);
			leaf.clearDirty();
			assertFalse(leaf.isDirty());
			assertEquals(99, leaf.getPageSequence());
			// restore must revert BOTH page sequence and dirty flag
			leaf.restore(memento);
			assertTrue(leaf.isDirty(), "dirty must survive snapshot -> mutate -> restore");
			assertEquals(42, leaf.getPageSequence(), "pageSequence must survive snapshot -> mutate -> restore");
		}

		@Test
		@DisplayName("SortIndex-style tree stays zero-cost: 65-wide leaves, null head mask, non-paged")
		void shouldKeepNonPagedNonHeadAwareTreeZeroCost() {
			// the SortIndex family uses new LeafNode(true, DEFAULT_BLOCK_SIZE, 0): 65-slot record arrays, NO head-mask
			// array allocated at all (null), while the paged head-aware ChainIndex tree grows page-sized leaves + mask
			final UnorderedLookupTree.LeafNode sortLeaf =
				new UnorderedLookupTree.LeafNode(false, UnorderedLookupTree.DEFAULT_BLOCK_SIZE, 0);
			assertEquals(
				UnorderedLookupTree.DEFAULT_BLOCK_SIZE + 1, sortLeaf.getRecordIds().length,
				"SortIndex leaves stay DEFAULT_BLOCK_SIZE + 1 (65) slots wide"
			);
			assertNull(sortLeaf.getHeadMask(), "SortIndex leaves allocate no head-mask array (null - zero cost)");
			final int maskWords = (UnorderedLookupTree.PAGE_RECORDS + 1 + 63) / 64;
			final UnorderedLookupTree.LeafNode chainLeaf =
				new UnorderedLookupTree.LeafNode(false, UnorderedLookupTree.PAGE_RECORDS, maskWords);
			assertEquals(UnorderedLookupTree.PAGE_RECORDS + 1, chainLeaf.getRecordIds().length);
			assertEquals(maskWords, chainLeaf.getHeadMask().length, "paged head-aware leaves carry a multi-word mask");
			// the non-paged, non-head-aware tree rejects every page-SPI call (the SPI is gated behind `paged`)
			final UnorderedLookupTree sortIndexStyle = new UnorderedLookupTree();
			assertThrows(GenericEvitaInternalError.class, sortIndexStyle::leafPageHandles);
			assertThrows(GenericEvitaInternalError.class, sortIndexStyle::collectChangedPages);
			assertThrows(GenericEvitaInternalError.class, sortIndexStyle::livePageSequences);
			assertThrows(GenericEvitaInternalError.class, sortIndexStyle::forgetPageStream);
		}
	}

	@Nested
	@DisplayName("Position cursor (forward / reverse leaf-walking emit)")
	class PositionCursorTest {

		/**
		 * Builds a distinct 1..n record set in a fixed-seed random logical order (Fisher-Yates), the shape the cursor
		 * walks in production.
		 *
		 * @param n    number of distinct records
		 * @param seed random seed for reproducibility
		 * @return the record ids in their logical (unordered) sequence
		 */
		@Nonnull
		private static int[] distinctShuffle(int n, long seed) {
			final int[] records = new int[n];
			for (int i = 0; i < n; i++) {
				records[i] = i + 1;
			}
			final Random random = new Random(seed);
			for (int i = n - 1; i > 0; i--) {
				final int j = random.nextInt(i + 1);
				final int tmp = records[i];
				records[i] = records[j];
				records[j] = tmp;
			}
			return records;
		}

		@Test
		@DisplayName("forward cursor emits the full logical array in ascending order across many leaves")
		void shouldEmitFullArrayForward() {
			final int[] records = distinctShuffle(500, 20250706L);
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.forwardPositionCursor();
			for (int position = 0; position < records.length; position++) {
				assertEquals(records[position], cursor.recordAt(position), "forward mismatch at position " + position);
			}
		}

		@Test
		@DisplayName("reverse cursor emits the logical array in descending order across many leaves")
		void shouldEmitFullArrayReverse() {
			final int[] records = distinctShuffle(500, 987654321L);
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.reversePositionCursor();
			final int n = records.length;
			for (int emitIndex = 0; emitIndex < n; emitIndex++) {
				assertEquals(records[n - 1 - emitIndex], cursor.recordAt(emitIndex), "reverse mismatch at " + emitIndex);
			}
		}

		@Test
		@DisplayName("forward cursor resolves a sparse ascending subset exactly like getRecordAt")
		void shouldEmitSparseSubsetForward() {
			final int[] records = distinctShuffle(1000, 424242L);
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.forwardPositionCursor();
			for (int position = 3; position < records.length; position += 37) {
				assertEquals(
					tested.tree.getRecordAt(position), cursor.recordAt(position), "sparse forward mismatch at " + position);
			}
		}

		@Test
		@DisplayName("reverse cursor resolves a sparse ascending emit index like mirrored getRecordAt")
		void shouldEmitSparseSubsetReverse() {
			final int[] records = distinctShuffle(1000, 555L);
			final TreeWithIndex tested = new TreeWithIndex(4, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.reversePositionCursor();
			final int n = records.length;
			for (int emitIndex = 1; emitIndex < n; emitIndex += 29) {
				assertEquals(
					tested.tree.getRecordAt(n - 1 - emitIndex), cursor.recordAt(emitIndex),
					"sparse reverse mismatch at emit index " + emitIndex
				);
			}
		}

		@Test
		@DisplayName("both cursors serve a single-leaf (root-is-leaf) tree")
		void shouldHandleSingleLeaf() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{7, 3, 9});
			final UnorderedLookupTree.PositionCursor forward = tested.tree.forwardPositionCursor();
			assertEquals(7, forward.recordAt(0));
			assertEquals(3, forward.recordAt(1));
			assertEquals(9, forward.recordAt(2));
			final UnorderedLookupTree.PositionCursor reverse = tested.tree.reversePositionCursor();
			assertEquals(9, reverse.recordAt(0));
			assertEquals(3, reverse.recordAt(1));
			assertEquals(7, reverse.recordAt(2));
		}

		@Test
		@DisplayName("forward cursor rejects an out-of-bounds emit index")
		void shouldRejectOutOfBoundsForward() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{7, 3, 9});
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.forwardPositionCursor();
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(3));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(-1));
		}

		@Test
		@DisplayName("forward cursor rejects a non-monotonic (backward) emit index")
		void shouldRejectBackwardForward() {
			final int[] records = distinctShuffle(200, 13L);
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.forwardPositionCursor();
			cursor.recordAt(100);
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(50));
		}

		@Test
		@DisplayName("both cursors reject any emit on an empty tree")
		void shouldRejectAnyEmitOnEmptyForwardAndReverseCursor() {
			final TreeWithIndex tested = new TreeWithIndex();
			assertTrue(tested.tree.isEmpty());
			// an empty tree has no position 0 to serve in either direction
			final UnorderedLookupTree.PositionCursor forward = tested.tree.forwardPositionCursor();
			assertThrows(GenericEvitaInternalError.class, () -> forward.recordAt(0));
			final UnorderedLookupTree.PositionCursor reverse = tested.tree.reversePositionCursor();
			assertThrows(GenericEvitaInternalError.class, () -> reverse.recordAt(0));
		}

		@Test
		@DisplayName("both cursors serve a true single-element tree and reject the position past it")
		void shouldServeSingleElementTreeForwardAndReverse() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{42});
			assertEquals(1, tested.tree.size());

			final UnorderedLookupTree.PositionCursor forward = tested.tree.forwardPositionCursor();
			assertEquals(42, forward.recordAt(0), "the forward cursor serves the sole record at emit 0");
			assertThrows(GenericEvitaInternalError.class, () -> forward.recordAt(1), "emit index 1 is past the single element");

			final UnorderedLookupTree.PositionCursor reverse = tested.tree.reversePositionCursor();
			assertEquals(42, reverse.recordAt(0), "the reverse cursor serves the sole record at emit 0");
			assertThrows(GenericEvitaInternalError.class, () -> reverse.recordAt(1), "emit index 1 is past the single element");
		}

		@Test
		@DisplayName("re-querying the same emit index returns the same record without tripping the rewind guard")
		void shouldReturnSameRecordWhenReQueryingSameEmitIndex() {
			final int[] records = distinctShuffle(500, 20260706L);
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);

			// re-reading the identical emit index is allowed (the guard rejects only a strictly smaller one)
			final UnorderedLookupTree.PositionCursor forward = tested.tree.forwardPositionCursor();
			final int forwardFirst = forward.recordAt(250);
			assertEquals(records[250], forwardFirst, "the forward emit index is the ascending logical position");
			assertEquals(forwardFirst, forward.recordAt(250), "re-querying the same forward emit index must be stable");

			final UnorderedLookupTree.PositionCursor reverse = tested.tree.reversePositionCursor();
			final int reverseFirst = reverse.recordAt(250);
			assertEquals(records[records.length - 1 - 250], reverseFirst, "the reverse emit index mirrors the logical position");
			assertEquals(reverseFirst, reverse.recordAt(250), "re-querying the same reverse emit index must be stable");
		}

		@Test
		@DisplayName("reverse cursor rejects a non-monotonic (backward) emit index")
		void shouldRejectBackwardReverse() {
			final int[] records = distinctShuffle(200, 17L);
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(records);
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.reversePositionCursor();
			cursor.recordAt(100);
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(50));
		}

		@Test
		@DisplayName("reverse cursor rejects an out-of-bounds emit index")
		void shouldRejectOutOfBoundsReverse() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{7, 3, 9});
			final UnorderedLookupTree.PositionCursor cursor = tested.tree.reversePositionCursor();
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(3));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.recordAt(-1));
		}
	}

	@Nested
	@DisplayName("Ranged predecessor search (leaf window retained across probes)")
	class RangedPredecessorSearchTest {

		/**
		 * Builds the logical shape a {@link io.evitadb.index.attribute.SortIndex} produces: a sequence of value
		 * blocks that are ascending **inside** a block and unordered across blocks. Records are inserted one by one
		 * (rather than bulk-loaded) so the tree carries real split history and partially-filled leaves.
		 *
		 * @param tested        the tree to fill
		 * @param blockCount    number of value blocks to lay out
		 * @param maxBlockWidth largest number of records a single block may hold
		 * @param seed          random seed for reproducibility
		 * @return the logical record id sequence, mirroring the tree's array
		 */
		@Nonnull
		private List<Integer> fillWithValueBlocks(
			@Nonnull TreeWithIndex tested, int blockCount, int maxBlockWidth, long seed
		) {
			final Random random = new Random(seed);
			// each block draws its ids from a private 1000-wide id space, so ids never collide across blocks;
			// the spaces are handed out in shuffled order, so the array is NOT globally ascending
			final List<Integer> spaces = new ArrayList<>(blockCount);
			for (int i = 0; i < blockCount; i++) {
				spaces.add(i);
			}
			Collections.shuffle(spaces, random);
			final List<Integer> logical = new ArrayList<>();
			for (int block = 0; block < blockCount; block++) {
				final int width = 1 + random.nextInt(maxBlockWidth);
				final int base = spaces.get(block) * 1000;
				// ascending ids with gaps, so ids ABSENT from the block exist between present ones
				int id = base + 1 + random.nextInt(5);
				for (int i = 0; i < width; i++) {
					tested.addAtPosition(logical.size(), id);
					logical.add(id);
					id += 1 + random.nextInt(5);
				}
			}
			return logical;
		}

		/**
		 * Returns the boundaries `[from, to)` of every maximal ascending run of the logical array — exactly the
		 * ranges the ranged search is contracted to accept. Recomputing them keeps the assertions valid after
		 * removals have reshaped the blocks.
		 *
		 * @param logical the logical record id sequence
		 * @return the ascending runs, in logical order
		 */
		@Nonnull
		private List<int[]> ascendingRuns(@Nonnull List<Integer> logical) {
			final List<int[]> runs = new ArrayList<>();
			int start = 0;
			for (int i = 1; i <= logical.size(); i++) {
				if (i == logical.size() || logical.get(i) < logical.get(i - 1)) {
					runs.add(new int[]{start, i});
					start = i;
				}
			}
			return runs;
		}

		/**
		 * The naive oracle: finds the first position in `[from, to)` holding a record id greater than or equal to
		 * `recordId` — resolved through {@link UnorderedLookupTree#getRecordAt(int)} one position at a time — and
		 * returns the record id one position below it, or {@link Integer#MIN_VALUE} when that is position zero.
		 *
		 * @param tested   the tree under test
		 * @param from     first position of the range, inclusive
		 * @param to       last position of the range, exclusive
		 * @param recordId the record id whose predecessor is sought
		 * @return the expected preceding record id
		 */
		private int oracle(@Nonnull TreeWithIndex tested, int from, int to, int recordId) {
			int insertionPosition = to;
			for (int position = from; position < to; position++) {
				if (tested.tree.getRecordAt(position) >= recordId) {
					insertionPosition = position;
					break;
				}
			}
			return insertionPosition == 0 ? Integer.MIN_VALUE : tested.tree.getRecordAt(insertionPosition - 1);
		}

		/**
		 * Asserts the ranged predecessor search matches the oracle for every present id of every ascending run, for
		 * the gaps between them, and for ids falling before and after the whole run.
		 *
		 * @param tested  the tree under test
		 * @param logical the logical record id sequence mirroring the tree
		 */
		private void assertMatchesOracleOnEveryRun(@Nonnull TreeWithIndex tested, @Nonnull List<Integer> logical) {
			for (final int[] run : ascendingRuns(logical)) {
				final int from = run[0];
				final int to = run[1];
				for (int position = from; position < to; position++) {
					final int presentId = logical.get(position);
					// a present id, and the absent id immediately below it (the gap left by the generator)
					for (final int probe : new int[]{presentId, presentId - 1}) {
						assertEquals(
							oracle(tested, from, to, probe),
							tested.tree.findPredecessorInRange(from, to, probe),
							"mismatch for id " + probe + " in run [" + from + ", " + to + ")"
						);
					}
				}
				// below every id in the run (predecessor falls BELOW the range, or off the array entirely), and above
				// every id in the run (predecessor is the run's own last record)
				final int belowAll = logical.get(from) - 100;
				assertEquals(
					oracle(tested, from, to, belowAll), tested.tree.findPredecessorInRange(from, to, belowAll)
				);
				final int aboveAll = logical.get(to - 1) + 100;
				assertEquals(
					aboveAll > 0 ? logical.get(to - 1) : Integer.MIN_VALUE,
					tested.tree.findPredecessorInRange(from, to, aboveAll)
				);
				// the empty range at the run's start - the shape a brand-new value block produces
				assertEquals(
					from == 0 ? Integer.MIN_VALUE : logical.get(from - 1),
					tested.tree.findPredecessorInRange(from, from, belowAll)
				);
			}
		}

		@Test
		@DisplayName("matches a naive oracle when a searched range spans many leaves")
		void shouldMatchOracleWhenRangeSpansManyLeaves() {
			// block size 3 splits leaves every 3 records, so a 40-wide block spans well over a dozen leaves and
			// most probes MISS the retained window - this is the fall-through-and-refresh path
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final List<Integer> logical = fillWithValueBlocks(tested, 30, 40, 20260728L);
			assertMatchesOracleOnEveryRun(tested, logical);
			assertEquals(ConsistencyState.CONSISTENT, tested.tree.getConsistencyReport().state());
		}

		@Test
		@DisplayName("matches a naive oracle when a searched range fits inside a single leaf")
		void shouldMatchOracleWhenRangeFitsSingleLeaf() {
			// production fan-out: leaves hold up to 64 records, so a block narrower than that resolves every probe
			// after the first from the retained window - this is the window-HIT path
			final TreeWithIndex tested = new TreeWithIndex();
			final List<Integer> logical = fillWithValueBlocks(tested, 40, 30, 987654321L);
			assertMatchesOracleOnEveryRun(tested, logical);
			assertEquals(ConsistencyState.CONSISTENT, tested.tree.getConsistencyReport().state());
		}

		@Test
		@DisplayName("matches a naive oracle after interleaved removals have reshaped the leaves")
		void shouldMatchOracleAfterRemovals() {
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			final List<Integer> logical = fillWithValueBlocks(tested, 30, 40, 4242L);
			final Random random = new Random(4242L);
			// removals drive steals and merges, leaving leaves at varying occupancy and shifting every base position
			for (int i = 0; i < 200 && logical.size() > 1; i++) {
				final int position = random.nextInt(logical.size());
				tested.remove(logical.remove(position));
			}
			assertMatchesOracleOnEveryRun(tested, logical);
			assertEquals(ConsistencyState.CONSISTENT, tested.tree.getConsistencyReport().state());
		}

		@Test
		@DisplayName("resolves the predecessor for empty, single-element and whole-array ranges")
		void shouldHandleDegenerateRanges() {
			final TreeWithIndex tested = new TreeWithIndex(3, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP);
			tested.bulkLoad(new int[]{10, 20, 30, 40, 50});
			// empty range - no search runs, the answer is simply the record below the range
			assertEquals(20, tested.tree.findPredecessorInRange(2, 2, 25));
			// empty range at the very front - no predecessor exists
			assertEquals(Integer.MIN_VALUE, tested.tree.findPredecessorInRange(0, 0, 5));
			// single element; every answer here falls BELOW the searched range
			assertEquals(20, tested.tree.findPredecessorInRange(2, 3, 25));
			assertEquals(20, tested.tree.findPredecessorInRange(2, 3, 30));
			// ... except this one, where the single element itself precedes the insertion point
			assertEquals(30, tested.tree.findPredecessorInRange(2, 3, 35));
			// the whole array
			assertEquals(Integer.MIN_VALUE, tested.tree.findPredecessorInRange(0, 5, 5));
			assertEquals(50, tested.tree.findPredecessorInRange(0, 5, 55));
			assertEquals(30, tested.tree.findPredecessorInRange(0, 5, 40));
		}

		@Test
		@DisplayName("rejects a range that does not lie within the array")
		void shouldRejectRangeOutsideArray() {
			final TreeWithIndex tested = new TreeWithIndex();
			tested.bulkLoad(new int[]{10, 20, 30});
			assertThrows(
				GenericEvitaInternalError.class, () -> tested.tree.findPredecessorInRange(-1, 2, 15)
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> tested.tree.findPredecessorInRange(0, 4, 15)
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> tested.tree.findPredecessorInRange(2, 1, 15)
			);
		}
	}

}
