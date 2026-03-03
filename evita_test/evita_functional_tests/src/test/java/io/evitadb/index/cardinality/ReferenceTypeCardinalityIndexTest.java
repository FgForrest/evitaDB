/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.cardinality;

import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReferenceTypeCardinalityIndex} focusing on bitmap cleanup and cache invalidation.
 *
 * @author Generated for evitaDB
 */
@DisplayName("ReferenceTypeCardinalityIndex")
class ReferenceTypeCardinalityIndexTest {

	private ReferenceTypeCardinalityIndex index;

	@BeforeEach
	void setUp() {
		this.index = new ReferenceTypeCardinalityIndex();
	}

	@Nested
	@DisplayName("BUG-6: empty bitmap cleanup on removeRecord")
	class EmptyBitmapCleanup {

		@Test
		@DisplayName("should remove empty bitmap from referencedPrimaryKeysIndex after last indexPK is removed")
		void shouldRemoveEmptyBitmapAfterLastRemoval() {
			// add a record: indexPK=1, referencedEntityPK=100
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(1, 100);

			// verify the bitmap exists
			assertFalse(
				ReferenceTypeCardinalityIndexTest.this.index.getReferencedPrimaryKeysIndex().isEmpty(),
				"referencedPrimaryKeysIndex should contain the referenced PK"
			);

			// remove the record
			ReferenceTypeCardinalityIndexTest.this.index.removeRecord(1, 100);

			// after removing the last entry, the bitmap should be removed from the map
			assertTrue(
				ReferenceTypeCardinalityIndexTest.this.index.getReferencedPrimaryKeysIndex().isEmpty(),
				"referencedPrimaryKeysIndex should be empty after removing the last record"
			);
		}

		@Test
		@DisplayName("should keep bitmap when other indexPKs still reference the same entity")
		void shouldKeepBitmapWhenOtherIndexPKsRemain() {
			// add two records referencing the same entity
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(1, 100);
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(2, 100);

			// remove one
			ReferenceTypeCardinalityIndexTest.this.index.removeRecord(1, 100);

			// bitmap should still be present with indexPK=2
			assertFalse(
				ReferenceTypeCardinalityIndexTest.this.index.getReferencedPrimaryKeysIndex().isEmpty(),
				"referencedPrimaryKeysIndex should still contain the referenced PK"
			);
			assertArrayEquals(
				new int[]{2},
				ReferenceTypeCardinalityIndexTest.this.index.getAllReferenceIndexes(100),
				"should still contain indexPK=2"
			);
		}
	}

	@Nested
	@DisplayName("BUG-12: memoizedAllReferencedPrimaryKeys cache invalidation")
	class MemoizedCacheInvalidation {

		@Test
		@DisplayName("should reflect new records in getIndexPrimaryKeys after addRecord")
		void shouldReflectNewRecordsAfterAdd() {
			// add first record and query to populate the memoized cache
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(1, 100);
			final RoaringBitmap query1 = RoaringBitmap.bitmapOf(100);
			final Bitmap result1 = ReferenceTypeCardinalityIndexTest.this.index.getIndexPrimaryKeys(query1);
			assertEquals(1, result1.size(), "should find indexPK=1 for referencedPK=100");

			// add another record with a new referenced entity PK
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(2, 200);

			// the memoized cache should be invalidated so that referencedPK=200 is found
			final RoaringBitmap query2 = RoaringBitmap.bitmapOf(200);
			final Bitmap result2 = ReferenceTypeCardinalityIndexTest.this.index.getIndexPrimaryKeys(query2);
			assertEquals(
				1, result2.size(),
				"should find indexPK=2 for referencedPK=200 after addRecord (cache must be invalidated)"
			);
		}

		@Test
		@DisplayName("should reflect removed records in getIndexPrimaryKeys after removeRecord")
		void shouldReflectRemovedRecordsAfterRemove() {
			// add records
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(1, 100);
			ReferenceTypeCardinalityIndexTest.this.index.addRecord(2, 200);

			// query to populate the memoized cache
			final RoaringBitmap queryAll = RoaringBitmap.bitmapOf(100, 200);
			final Bitmap resultBefore = ReferenceTypeCardinalityIndexTest.this.index.getIndexPrimaryKeys(queryAll);
			assertEquals(2, resultBefore.size(), "should find both indexPKs");

			// remove one record
			ReferenceTypeCardinalityIndexTest.this.index.removeRecord(2, 200);

			// the memoized cache should be invalidated so that referencedPK=200 is no longer found
			final RoaringBitmap query200 = RoaringBitmap.bitmapOf(200);
			final Bitmap resultAfter = ReferenceTypeCardinalityIndexTest.this.index.getIndexPrimaryKeys(query200);
			assertEquals(
				0, resultAfter.size(),
				"should not find referencedPK=200 after removeRecord (cache must be invalidated)"
			);
		}
	}
}
