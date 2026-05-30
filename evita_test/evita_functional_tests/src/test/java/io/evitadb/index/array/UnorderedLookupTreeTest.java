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
 * This test verifies the contract of {@link UnorderedLookupTree} - the count-augmented B+ tree replacement for the
 * dual-`int[]` {@link UnorderedLookup} delegate. It locks the order-statistic semantics (select / rank / insert-after /
 * insert-at / delete / flatten) against an {@link ArrayList} oracle, including the leaf/internal split and collapse
 * paths exercised once the element count exceeds the block size.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
class UnorderedLookupTreeTest {

	/**
	 * Asserts that the tree's flattened state matches the oracle and that the three snapshot arrays stay mutually
	 * consistent (`getArray()[positions[i]] == recordIds[i]` and record ids ascending).
	 */
	private static void assertConsistentWithOracle(@Nonnull UnorderedLookupTree tree, @Nonnull List<Integer> oracle) {
		final int[] expected = new int[oracle.size()];
		for (int i = 0; i < oracle.size(); i++) {
			expected[i] = oracle.get(i);
		}
		assertArrayEquals(expected, tree.getArray(), "Flattened array mismatch");
		assertEquals(oracle.size(), tree.size());

		final int[] array = tree.getArray();
		final int[] recordIds = tree.getRecordIds();
		final int[] positions = tree.getPositions();
		assertEquals(array.length, recordIds.length);
		assertEquals(array.length, positions.length);
		// record ids must be ascending and aligned with positions through the permutation
		for (int i = 0; i < recordIds.length; i++) {
			if (i > 0) {
				assertTrue(recordIds[i - 1] < recordIds[i], "Record ids must be strictly ascending");
			}
			assertEquals(recordIds[i], array[positions[i]], "positions[i] must point at recordIds[i]");
			assertEquals(positions[i], tree.findPosition(recordIds[i]), "findPosition mismatch");
		}
		// every position must resolve back to the permutation entry
		for (int position = 0; position < array.length; position++) {
			assertEquals(array[position], tree.getRecordAt(position), "getRecordAt mismatch at " + position);
		}
	}

	@Test
	void shouldCreateLookupAndFindPositionsProperly() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{4, 2, 3, 1});
		assertEquals(0, tested.findPosition(4));
		assertEquals(1, tested.findPosition(2));
		assertEquals(2, tested.findPosition(3));
		assertEquals(3, tested.findPosition(1));
		assertEquals(Integer.MIN_VALUE, tested.findPosition(99));

		assertEquals(4, tested.getRecordAt(0));
		assertEquals(2, tested.getRecordAt(1));
		assertEquals(3, tested.getRecordAt(2));
		assertEquals(1, tested.getRecordAt(3));
	}

	@Test
	void shouldCreateLookupAndReturnArrayUntouched() {
		final int[] inputArray = {4, 2, 3, 1};
		final UnorderedLookupTree tested = new UnorderedLookupTree(inputArray);
		assertArrayEquals(inputArray, tested.getArray());
	}

	@Test
	void shouldRemoveRecordAndStillMaintainCorrectPositions() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{4, 2, 3, 1, 6, 5});
		tested.removeRecord(1);
		assertArrayEquals(new int[]{4, 2, 3, 6, 5}, tested.getArray());
		tested.removeRecord(4);
		assertArrayEquals(new int[]{2, 3, 6, 5}, tested.getArray());
		tested.removeRecord(5);
		assertArrayEquals(new int[]{2, 3, 6}, tested.getArray());
		tested.removeRecord(3);
		assertArrayEquals(new int[]{2, 6}, tested.getArray());
		tested.removeRecord(2);
		assertArrayEquals(new int[]{6}, tested.getArray());
		tested.removeRecord(6);
		assertArrayEquals(new int[0], tested.getArray());
		assertEquals(0, tested.size());
	}

	@Test
	void shouldAddRecordAndStillMaintainCorrectPositions() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[0]);
		tested.addRecord(Integer.MIN_VALUE, 3);
		assertArrayEquals(new int[]{3}, tested.getArray());
		tested.addRecord(Integer.MIN_VALUE, 5);
		assertArrayEquals(new int[]{5, 3}, tested.getArray());
		tested.addRecord(5, 1);
		assertArrayEquals(new int[]{5, 1, 3}, tested.getArray());
		tested.addRecord(3, 2);
		assertArrayEquals(new int[]{5, 1, 3, 2}, tested.getArray());
		tested.addRecord(Integer.MIN_VALUE, 0);
		assertArrayEquals(new int[]{0, 5, 1, 3, 2}, tested.getArray());
		tested.addRecord(2, 10);
		assertArrayEquals(new int[]{0, 5, 1, 3, 2, 10}, tested.getArray());
	}

	@Test
	void shouldAppendRecordsAtTheEnd() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5});
		tested.appendRecords(new int[]{4, 3, 8});
		assertArrayEquals(new int[]{9, 1, 5, 4, 3, 8}, tested.getArray());
		tested.appendRecords(new int[]{2, 10});
		assertArrayEquals(new int[]{9, 1, 5, 4, 3, 8, 2, 10}, tested.getArray());
	}

	@Test
	void shouldReturnLastRecordId() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5});
		assertEquals(5, tested.getLastRecordId());
		tested.addRecord(5, 7);
		assertEquals(7, tested.getLastRecordId());
		tested.removeRecord(7);
		assertEquals(5, tested.getLastRecordId());
	}

	@Test
	void shouldReportContainment() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5});
		assertTrue(tested.contains(9));
		assertTrue(tested.contains(1));
		assertFalse(tested.contains(2));
		tested.removeRecord(1);
		assertFalse(tested.contains(1));
	}

	@Test
	void shouldRemoveRange() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5, 4, 3, 8});
		final int[] removed = tested.removeRange(1, 4);
		assertArrayEquals(new int[]{1, 5, 4}, removed);
		assertArrayEquals(new int[]{9, 3, 8}, tested.getArray());
	}

	@Test
	void shouldThrowWhenAddingAfterMissingRecord() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5});
		assertThrows(Exception.class, () -> tested.addRecord(42, 7));
	}

	@Test
	void shouldThrowWhenAddingDuplicate() {
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[]{9, 1, 5});
		assertThrows(Exception.class, () -> tested.addRecord(9, 1));
	}

	@Test
	void shouldGrowBeyondSingleLeafAndStayConsistent() {
		// 5x the block size forces several leaf splits and at least one internal split
		final int count = UnorderedLookupTree.BLOCK_SIZE * 5;
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[0]);
		final List<Integer> oracle = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			tested.addRecordOnIndex(i, 1000 + i);
			oracle.add(1000 + i);
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldSurviveRandomizedInsertRemoveAgainstOracle() {
		final Random random = new Random(42);
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[0]);
		final List<Integer> oracle = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 20_000; op++) {
			final int size = oracle.size();
			final boolean insert = size == 0 || random.nextInt(100) < 60;
			if (insert) {
				final int index = random.nextInt(size + 1);
				final int recordId = nextRecordId++;
				tested.addRecordOnIndex(index, recordId);
				oracle.add(index, recordId);
			} else {
				final int index = random.nextInt(size);
				final int recordId = oracle.remove(index);
				tested.removeRecord(recordId);
			}
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldSurviveRandomizedAddAfterAgainstOracle() {
		final Random random = new Random(7);
		final UnorderedLookupTree tested = new UnorderedLookupTree(new int[0]);
		final List<Integer> oracle = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 20_000; op++) {
			final int size = oracle.size();
			final boolean insert = size == 0 || random.nextInt(100) < 60;
			if (insert) {
				final int recordId = nextRecordId++;
				if (size == 0 || random.nextInt(10) == 0) {
					tested.addRecord(Integer.MIN_VALUE, recordId);
					oracle.add(0, recordId);
				} else {
					final int prevIndex = random.nextInt(size);
					final int previousRecordId = oracle.get(prevIndex);
					tested.addRecord(previousRecordId, recordId);
					oracle.add(prevIndex + 1, recordId);
				}
			} else {
				final int index = random.nextInt(size);
				final int recordId = oracle.remove(index);
				tested.removeRecord(recordId);
			}
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldBulkLoadLargeArrayAndStayConsistent() {
		// builds several internal levels bottom-up; then mutate to prove the bulk-built tree behaves like any other
		final int count = UnorderedLookupTree.BLOCK_SIZE * UnorderedLookupTree.BLOCK_SIZE * 4;
		final int[] input = new int[count];
		final List<Integer> oracle = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			input[i] = 1_000_000 - i;
			oracle.add(input[i]);
		}
		final UnorderedLookupTree tested = new UnorderedLookupTree(input);
		assertConsistentWithOracle(tested, oracle);
		// remove a scattered third and reinsert at head to exercise post-bulk-load splits/collapses
		final Random random = new Random(99);
		for (int i = 0; i < count / 3; i++) {
			final int idx = random.nextInt(oracle.size());
			tested.removeRecord(oracle.remove(idx));
		}
		for (int i = 0; i < 500; i++) {
			tested.addRecord(Integer.MIN_VALUE, 2_000_000 + i);
			oracle.add(0, 2_000_000 + i);
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldStayConsistentWhenOrderKeyGapExhausts() {
		// a tiny order-key gap forces repeated re-spacing as the leftmost region keeps splitting under head inserts
		final UnorderedLookupTree tested = new UnorderedLookupTree(4L);
		final List<Integer> oracle = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			tested.addRecord(Integer.MIN_VALUE, 1000 + i);
			oracle.add(0, 1000 + i);
		}
		assertConsistentWithOracle(tested, oracle);
	}

	@Test
	void shouldMatchArrayDelegateForIdenticalOperations() {
		final Random random = new Random(123);
		final UnorderedLookupTree tree = new UnorderedLookupTree(new int[0]);
		final UnorderedLookup array = new UnorderedLookup(new int[0]);
		final List<Integer> live = new ArrayList<>();
		int nextRecordId = 1;
		for (int op = 0; op < 2_000; op++) {
			final int size = live.size();
			final boolean insert = size == 0 || random.nextInt(100) < 60;
			if (insert) {
				final int recordId = nextRecordId++;
				final int prev = size == 0 ? Integer.MIN_VALUE : live.get(random.nextInt(size));
				tree.addRecord(prev, recordId);
				array.addRecord(prev, recordId);
				final int prevIndex = prev == Integer.MIN_VALUE ? -1 : live.indexOf(prev);
				live.add(prevIndex + 1, recordId);
			} else {
				final int recordId = live.remove(random.nextInt(size));
				tree.removeRecord(recordId);
				array.removeRecord(recordId);
			}
			// both delegates must agree on every observable projection
			assertArrayEquals(array.getArray(), tree.getArray(), "permutation mismatch at op " + op);
			assertArrayEquals(array.getRecordIds(), tree.getRecordIds(), "record ids mismatch at op " + op);
			assertArrayEquals(array.getPositions(), tree.getPositions(), "positions mismatch at op " + op);
		}
	}

}
