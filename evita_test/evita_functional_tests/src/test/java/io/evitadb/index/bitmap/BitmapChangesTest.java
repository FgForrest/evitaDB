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

package io.evitadb.index.bitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link BitmapChanges}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BitmapChanges")
class BitmapChangesTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create from empty original")
		void shouldCreateFromEmptyOriginal() {
			final BitmapChanges changes = new BitmapChanges(new RoaringBitmap());
			assertTrue(changes.isEmpty());
			assertEquals(0, changes.getMergedLength());
		}

		@Test
		@DisplayName("should create from non-empty original")
		void shouldCreateFromNonEmptyOriginal() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertFalse(changes.isEmpty());
			assertEquals(3, changes.getMergedLength());
		}
	}

	@Nested
	@DisplayName("Add operations")
	class AddOperationsTest {

		@Test
		@DisplayName("should add new record to empty original")
		void shouldAddNewRecordToEmptyOriginal() {
			final BitmapChanges changes = new BitmapChanges(new RoaringBitmap());
			final boolean result = changes.addRecordId(5);
			assertTrue(result);
			assertTrue(changes.contains(5));
		}

		@Test
		@DisplayName("should return true when adding new record")
		void shouldReturnTrueWhenAddingNewRecord() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertTrue(changes.addRecordId(5));
		}

		@Test
		@DisplayName("should return false when adding record already in original")
		void shouldReturnFalseWhenAddingRecordAlreadyInOriginal() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertFalse(changes.addRecordId(2));
		}

		@Test
		@DisplayName("should add multiple records")
		void shouldAddMultipleRecords() {
			final BitmapChanges changes = new BitmapChanges(new RoaringBitmap());
			changes.addRecordId(5);
			changes.addRecordId(10);
			changes.addRecordId(15);
			assertEquals(3, changes.getMergedLength());
			assertTrue(changes.contains(5));
			assertTrue(changes.contains(10));
			assertTrue(changes.contains(15));
		}

		@Test
		@DisplayName("should cancel previous removal on add")
		void shouldCancelPreviousRemovalOnAdd() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			assertFalse(changes.contains(2));
			// re-add the same record
			assertTrue(changes.addRecordId(2));
			assertTrue(changes.contains(2));
			assertEquals(3, changes.getMergedLength());
		}
	}

	@Nested
	@DisplayName("Remove operations")
	class RemoveOperationsTest {

		@Test
		@DisplayName("should remove record from original")
		void shouldRemoveRecordFromOriginal() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			assertFalse(changes.contains(2));
			assertEquals(2, changes.getMergedLength());
		}

		@Test
		@DisplayName("should return true when removing existing record")
		void shouldReturnTrueWhenRemovingExistingRecord() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertTrue(changes.removeRecordId(2));
		}

		@Test
		@DisplayName("should return false when removing non-existing record")
		void shouldReturnFalseWhenRemovingNonExistingRecord() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertFalse(changes.removeRecordId(99));
		}

		@Test
		@DisplayName("should cancel previous insertion on remove")
		void shouldCancelPreviousInsertionOnRemove() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			assertTrue(changes.contains(5));
			// remove the just-added record
			assertTrue(changes.removeRecordId(5));
			assertFalse(changes.contains(5));
			assertEquals(3, changes.getMergedLength());
		}

		@Test
		@DisplayName("should remove multiple records")
		void shouldRemoveMultipleRecords() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3, 4, 5);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			changes.removeRecordId(4);
			assertEquals(3, changes.getMergedLength());
			assertFalse(changes.contains(2));
			assertFalse(changes.contains(4));
		}
	}

	@Nested
	@DisplayName("Contains")
	class ContainsTest {

		@Test
		@DisplayName("should contain record from original")
		void shouldContainRecordFromOriginal() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertTrue(changes.contains(1));
			assertTrue(changes.contains(2));
			assertTrue(changes.contains(3));
		}

		@Test
		@DisplayName("should not contain removed record")
		void shouldNotContainRemovedRecord() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			assertFalse(changes.contains(2));
		}

		@Test
		@DisplayName("should contain inserted record")
		void shouldContainInsertedRecord() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(10);
			assertTrue(changes.contains(10));
		}

		@Test
		@DisplayName("should not contain record neither in original nor inserted")
		void shouldNotContainRecordNeitherInOriginalNorInserted() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertFalse(changes.contains(99));
		}
	}

	@Nested
	@DisplayName("Merged bitmap")
	class MergedBitmapTest {

		@Test
		@DisplayName("should return original when no changes")
		void shouldReturnOriginalWhenNoChanges() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			// when there are no changes, getMergedBitmap returns the original identity
			assertSame(original, changes.getMergedBitmap());
		}

		@Test
		@DisplayName("should return merged with insertions")
		void shouldReturnMergedWithInsertions() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			changes.addRecordId(7);
			final RoaringBitmap merged = changes.getMergedBitmap();
			assertArrayEquals(new int[]{1, 2, 3, 5, 7}, merged.toArray());
		}

		@Test
		@DisplayName("should return merged with removals")
		void shouldReturnMergedWithRemovals() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3, 4, 5);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			changes.removeRecordId(4);
			final RoaringBitmap merged = changes.getMergedBitmap();
			assertArrayEquals(new int[]{1, 3, 5}, merged.toArray());
		}

		@Test
		@DisplayName("should return merged with both insertions and removals")
		void shouldReturnMergedWithBothInsertionsAndRemovals() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			changes.addRecordId(10);
			final RoaringBitmap merged = changes.getMergedBitmap();
			assertArrayEquals(new int[]{1, 3, 10}, merged.toArray());
		}

		@Test
		@DisplayName("should memoize merged result")
		void shouldMemoizeMergedResult() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			final RoaringBitmap firstCall = changes.getMergedBitmap();
			final RoaringBitmap secondCall = changes.getMergedBitmap();
			assertSame(firstCall, secondCall);
		}

		@Test
		@DisplayName("should invalidate memoized result on add")
		void shouldInvalidateMemoizedResultOnAdd() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			final RoaringBitmap firstCall = changes.getMergedBitmap();
			changes.addRecordId(7);
			final RoaringBitmap afterAdd = changes.getMergedBitmap();
			assertNotSame(firstCall, afterAdd);
			assertArrayEquals(new int[]{1, 2, 3, 5, 7}, afterAdd.toArray());
		}

		@Test
		@DisplayName("should invalidate memoized result on remove")
		void shouldInvalidateMemoizedResultOnRemove() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			final RoaringBitmap firstCall = changes.getMergedBitmap();
			changes.removeRecordId(2);
			final RoaringBitmap afterRemove = changes.getMergedBitmap();
			assertNotSame(firstCall, afterRemove);
			assertArrayEquals(new int[]{1, 3, 5}, afterRemove.toArray());
		}
	}

	@Nested
	@DisplayName("Merged length")
	class MergedLengthTest {

		@Test
		@DisplayName("should return original length when no changes")
		void shouldReturnOriginalLengthWhenNoChanges() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertEquals(3, changes.getMergedLength());
		}

		@Test
		@DisplayName("should return length with insertions")
		void shouldReturnLengthWithInsertions() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.addRecordId(5);
			changes.addRecordId(7);
			assertEquals(5, changes.getMergedLength());
		}

		@Test
		@DisplayName("should return length with removals")
		void shouldReturnLengthWithRemovals() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3, 4, 5);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			changes.removeRecordId(4);
			assertEquals(3, changes.getMergedLength());
		}

		@Test
		@DisplayName("should return length with both insertions and removals")
		void shouldReturnLengthWithBothInsertionsAndRemovals() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(2);
			changes.addRecordId(10);
			changes.addRecordId(20);
			assertEquals(4, changes.getMergedLength());
		}
	}

	@Nested
	@DisplayName("isEmpty")
	class IsEmptyTest {

		@Test
		@DisplayName("should be empty when original is empty and no insertions")
		void shouldBeEmptyWhenOriginalIsEmptyAndNoInsertions() {
			final BitmapChanges changes = new BitmapChanges(new RoaringBitmap());
			assertTrue(changes.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when original has records")
		void shouldNotBeEmptyWhenOriginalHasRecords() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			assertFalse(changes.isEmpty());
		}

		@Test
		@DisplayName("should be empty when all original records removed")
		void shouldBeEmptyWhenAllOriginalRecordsRemoved() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(1);
			changes.removeRecordId(2);
			changes.removeRecordId(3);
			assertTrue(changes.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when record inserted")
		void shouldNotBeEmptyWhenRecordInserted() {
			final BitmapChanges changes = new BitmapChanges(new RoaringBitmap());
			changes.addRecordId(5);
			assertFalse(changes.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when original has records and some removed")
		void shouldNotBeEmptyWhenOriginalHasRecordsAndSomeRemoved() {
			final RoaringBitmap original = RoaringBitmap.bitmapOf(1, 2, 3);
			final BitmapChanges changes = new BitmapChanges(original);
			changes.removeRecordId(1);
			assertFalse(changes.isEmpty());
		}
	}
}
