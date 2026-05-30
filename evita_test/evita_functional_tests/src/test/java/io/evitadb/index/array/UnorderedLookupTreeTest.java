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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the contract of {@link UnorderedLookupTree} - the count-augmented, order-key-routed position
 * tree of the two-tree backing for {@link UnorderedLookup}. The tree is driven purely by order-key / position and
 * reports order-key assignments through an {@link OrderKeyConsumer}; the test pairs it with a stand-in value index
 * (an `int → long` map, exactly the role the real value index plays) so it can address records by id and assert the
 * order-key coordination (INV-COUPLE) stays coherent through splits and re-spacing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
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
	 * Asserts that the tree's flattened state matches the oracle and that addressing every record by id (via the
	 * stand-in value index) resolves to its true position.
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
	}

	@Test
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
	}

	@Test
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
	}

	@Test
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
	}

	@Test
	void shouldGrowBeyondSingleContainerAndStayConsistent() {
		// 5x the block size forces several container splits and at least one internal split
		final int count = UnorderedLookupTree.BLOCK_SIZE * 5;
		final TreeWithIndex tested = new TreeWithIndex();
		final List<Integer> oracle = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			tested.addAtPosition(i, 1000 + i);
			oracle.add(1000 + i);
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldBulkLoadLargeArrayAndStayConsistent() {
		final int count = UnorderedLookupTree.BLOCK_SIZE * UnorderedLookupTree.BLOCK_SIZE * 4;
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
	}

	@Test
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
	void shouldSurviveRandomizedInsertRemoveAgainstOracle() {
		final Random random = new Random(42);
		final TreeWithIndex tested = new TreeWithIndex();
		final List<Integer> oracle = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 20_000; op++) {
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
	void shouldSurviveRandomizedAddAfterAgainstOracle() {
		final Random random = new Random(7);
		final TreeWithIndex tested = new TreeWithIndex();
		final List<Integer> oracle = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 20_000; op++) {
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
	void shouldMatchArrayDelegateForIdenticalOperations() {
		final Random random = new Random(123);
		final TreeWithIndex tested = new TreeWithIndex();
		final UnorderedLookup array = new UnorderedLookup(new int[0]);
		final List<Integer> live = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 2_000; op++) {
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
