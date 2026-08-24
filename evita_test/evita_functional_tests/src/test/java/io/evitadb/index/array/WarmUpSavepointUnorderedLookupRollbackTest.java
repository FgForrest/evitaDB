/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the position tree behind {@link TransactionalUnorderedIntArray} — the count-augmented
 * {@link UnorderedLookupTree} plus the {@code TransactionalIntToLongBPlusTree} order-key index it is paired with —
 * rewinds its non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back.
 *
 * The façade holds no state of its own: it delegates entirely to those two trees, whose nodes journal at node
 * granularity. What the tree itself contributes beyond its nodes is the memoized flattening returned by
 * {@link UnorderedLookupTree#getArray()}, and that memo is memoized ONLY on the no-transaction branch — precisely the
 * warm-up path a savepoint brackets. So every rollback test below deliberately READS the array while the savepoint is
 * open: that read repopulates the memo from the half-mutated tree, and an implementation relying only on the forward
 * mutators' invalidation passes without the read and fails with it.
 *
 * The final nested class closes the loop end to end through {@link SortIndex}, whose {@code sortedRecords} IS one of
 * these façades — the last piece of that index's recovery, its memoized caches having been covered separately.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see io.evitadb.index.bPlusTree.WarmUpSavepointBPlusTreeRollbackTest for the node-level journaling this rests on
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of the unordered lookup tree")
class WarmUpSavepointUnorderedLookupRollbackTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	/**
	 * Builds an unordered array holding `count` record ids in ascending append order.
	 */
	@Nonnull
	private static TransactionalUnorderedIntArray newArray(int count) {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray();
		final int[] recordIds = new int[count];
		for (int i = 0; i < count; i++) {
			recordIds[i] = i + 1;
		}
		array.appendAll(recordIds);
		return array;
	}

	@Nested
	@DisplayName("TransactionalUnorderedIntArray")
	class UnorderedArray {

		@Test
		@DisplayName("Rollback undoes an append burst that split the position tree")
		void shouldRestoreArrayAfterSplittingAppendBurst() {
			final TransactionalUnorderedIntArray array = newArray(8);
			final int[] expected = array.getArray();
			assertFalse(array.isRootInternal(), "self-check: the seeded array must still fit one container");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				array.add(array.getLastRecordId(), 1000 + i);
			}
			assertTrue(array.isRootInternal(), "self-check: the in-savepoint burst must have split the position tree");
			// read INSIDE the savepoint: this repopulates the memoized flattening from the half-mutated tree
			assertEquals(3008, array.getArray().length, "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertArrayEquals(expected, array.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(8, array.getLength(), "Rollback must restore the array length.");
			assertFalse(array.isRootInternal(), "Rollback must restore the pre-split single-container shape.");

			// the array must remain usable afterwards
			array.add(8, 9);
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, array.getArray(),
				"A write after the rollback must apply normally on top of the restored order."
			);
		}

		@Test
		@DisplayName("Rollback undoes an insert / remove churn in the middle of the order")
		void shouldRestoreArrayAfterMidOrderChurn() {
			final TransactionalUnorderedIntArray array = newArray(2000);
			final int[] expected = array.getArray();
			assertTrue(array.isRootInternal(), "self-check: the seeded array must span several containers");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// insert after and remove around the middle so containers both split and drain below their minimum
			for (int i = 0; i < 400; i++) {
				array.add(1000, 5000 + i);
			}
			for (int i = 100; i < 900; i++) {
				array.remove(i);
			}
			assertEquals(1600, array.getArray().length, "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertArrayEquals(expected, array.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(2000, array.getLength(), "Rollback must restore the array length.");
			assertEquals(500, array.indexOf(501), "Rollback must restore the position index of every record.");
		}

		@Test
		@DisplayName("Commit keeps an append burst that split the position tree")
		void shouldKeepAppendBurstOnCommit() {
			final TransactionalUnorderedIntArray array = newArray(8);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				array.add(array.getLastRecordId(), 1000 + i);
			}
			final int[] expected = array.getArray();
			savepoint.commit();

			assertArrayEquals(
				expected, array.getArray(), "Commit must keep every change made while the savepoint was open."
			);
			assertTrue(array.isRootInternal(), "Commit must keep the split shape.");
		}

		@Test
		@DisplayName("Rollback leaves an array that was never touched alone")
		void shouldNotTouchUnrelatedArray() {
			final TransactionalUnorderedIntArray touched = newArray(8);
			final TransactionalUnorderedIntArray untouched = newArray(2000);
			final int[] untouchedContents = untouched.getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				touched.add(touched.getLastRecordId(), 1000 + i);
			}
			savepoint.rollback();

			assertArrayEquals(
				untouchedContents, untouched.getArray(),
				"An array never write-touched inside the savepoint must be left exactly as it was."
			);
			assertEquals(8, touched.getLength(), "self-check: the touched array was rewound");
		}
	}

	@Nested
	@DisplayName("SortIndex end to end")
	class SortIndexRecovery {

		/**
		 * Builds a sort index over single-character string values, one record per value, in ascending value order.
		 */
		@Nonnull
		private SortIndex newIndex(int count) {
			final SortIndex index = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
			for (int i = 0; i < count; i++) {
				index.addRecord(String.format("v%05d", i), i + 1);
			}
			return index;
		}

		@Test
		@DisplayName("Rollback restores the sorted record order after a burst that split the position tree")
		void shouldRestoreSortedRecordsAfterInsertBurst() {
			final SortIndex index = newIndex(600);
			final int[] expected = index.getSortedRecords();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 2000; i++) {
				index.addRecord(String.format("w%05d", i), 10_000 + i);
			}
			// read INSIDE the savepoint so the sort-index caches and the position tree's memoized flattening are both
			// repopulated from the half-mutated state
			assertEquals(2600, index.getSortedRecords().length, "self-check on the in-savepoint order");
			savepoint.rollback();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Rollback must restore the exact pre-savepoint sorted-record order."
			);
			assertEquals(600, index.size(), "Rollback must restore the index size.");

			// a subsequent write must apply on top of the restored order rather than on a stale view of it
			index.addRecord("v99999", 99_999);
			final int[] afterRollbackWrite = index.getSortedRecords();
			assertEquals(601, afterRollbackWrite.length, "A write after the rollback must apply normally.");
			assertEquals(
				99_999, afterRollbackWrite[afterRollbackWrite.length - 1],
				"The record written after the rollback must sort into the restored order."
			);
		}

		@Test
		@DisplayName("Rollback restores the sorted record order after a removal burst")
		void shouldRestoreSortedRecordsAfterRemovalBurst() {
			final SortIndex index = newIndex(2000);
			final int[] expected = index.getSortedRecords();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 1500; i++) {
				index.removeRecord(String.format("v%05d", i), i + 1);
			}
			assertEquals(500, index.getSortedRecords().length, "self-check on the in-savepoint order");
			savepoint.rollback();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Rollback must restore the exact pre-savepoint sorted-record order."
			);
			assertEquals(2000, index.size(), "Rollback must restore the index size.");
		}

		@Test
		@DisplayName("Commit keeps the sorted record order written while the savepoint was open")
		void shouldKeepSortedRecordsOnCommit() {
			final SortIndex index = newIndex(600);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 2000; i++) {
				index.addRecord(String.format("w%05d", i), 10_000 + i);
			}
			final int[] expected = index.getSortedRecords();
			savepoint.commit();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Commit must keep every change made while the savepoint was open."
			);
		}
	}

}
