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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.Map;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link ReferenceTypeCardinalityIndex} covering construction,
 * add/remove operations, query methods, memoization cache, dirty flag,
 * STM commit/rollback, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceTypeCardinalityIndex")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
class ReferenceTypeCardinalityIndexTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"default constructor: empty cardinalities and index"
		)
		void shouldInitializeEmpty() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertTrue(index.isEmpty());
			assertTrue(index.getCardinalities().isEmpty());
			assertTrue(
				index.getReferencedPrimaryKeysIndex().isEmpty()
			);
		}

		@Test
		@DisplayName("map constructor: both maps reflected")
		void shouldInitializeWithMaps() {
			final Map<Long, Integer> cardinalities =
				CollectionUtils.createHashMap(4);
			cardinalities.put(1L, 1);
			cardinalities.put(-1L, 1);

			final Map<Integer, TransactionalBitmap> refIndex =
				CollectionUtils.createHashMap(4);
			refIndex.put(
				100, new TransactionalBitmap(new BaseBitmap(1))
			);

			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex(
					cardinalities, refIndex
				);
			assertFalse(index.isEmpty());
			assertEquals(2, index.getCardinalities().size());
			assertEquals(
				1,
				index.getReferencedPrimaryKeysIndex().size()
			);
		}

		@Test
		@DisplayName(
			"isEmpty false after add, true after full removal"
		)
		void shouldTrackEmptiness() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertTrue(index.isEmpty());
			index.addRecord(1, 100);
			assertFalse(index.isEmpty());
			index.removeRecord(1, 100);
			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("addRecord — Return value and cardinality")
	class AddRecordTest {

		@Test
		@DisplayName("returns true on first add for given indexPk")
		void shouldReturnTrueOnFirstAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertTrue(index.addRecord(1, 100));
		}

		@Test
		@DisplayName(
			"returns false on second add for same indexPk"
		)
		void shouldReturnFalseOnSecondAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertFalse(index.addRecord(1, 200));
		}

		@Test
		@DisplayName("returns true when new indexPk is added")
		void shouldReturnTrueForNewIndexPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertTrue(index.addRecord(2, 100));
		}

		@Test
		@DisplayName(
			"multiple referencedPks under same indexPk"
		)
		void shouldTrackMultipleReferencedPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertTrue(index.addRecord(1, 100));
			assertFalse(index.addRecord(1, 200));
			assertFalse(index.addRecord(1, 300));
			// all three referenced PKs should be tracked
			assertArrayEquals(
				new int[]{1},
				index.getAllReferenceIndexes(100)
			);
			assertArrayEquals(
				new int[]{1},
				index.getAllReferenceIndexes(200)
			);
			assertArrayEquals(
				new int[]{1},
				index.getAllReferenceIndexes(300)
			);
		}
	}

	@Nested
	@DisplayName("addRecord — Validation")
	class AddRecordValidationTest {

		@Test
		@DisplayName("addRecord(0, 100) → assertion error")
		void shouldRejectZeroIndexPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertThrows(
				Exception.class,
				() -> index.addRecord(0, 100)
			);
		}
	}

	@Nested
	@DisplayName("removeRecord — Return value and cardinality")
	class RemoveRecordTest {

		@Test
		@DisplayName("returns true when indexPk fully evicted")
		void shouldReturnTrueWhenFullyEvicted() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertTrue(index.removeRecord(1, 100));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"returns false when indexPk still has other referencedPks"
		)
		void shouldReturnFalseWhenStillPresent() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(1, 200);
			assertFalse(index.removeRecord(1, 100));
			assertFalse(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("removeRecord — Error paths")
	class RemoveRecordErrorTest {

		@Test
		@DisplayName("removeRecord(0, 100) → assertion error")
		void shouldRejectZeroIndexPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertThrows(
				Exception.class,
				() -> index.removeRecord(0, 100)
			);
		}

		@Test
		@DisplayName(
			"remove absent indexPk → GenericEvitaInternalError"
		)
		void shouldThrowOnAbsentIndexPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.removeRecord(1, 100)
			);
		}
	}

	@Nested
	@DisplayName("Query methods")
	class QueryMethodsTest {

		@Test
		@DisplayName(
			"getAllReferenceIndexes for absent → EMPTY_INT_ARRAY"
		)
		void shouldReturnEmptyForAbsentRefPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertArrayEquals(
				new int[0],
				index.getAllReferenceIndexes(999)
			);
		}

		@Test
		@DisplayName(
			"getAllReferenceIndexes returns all indexPks"
		)
		void shouldReturnAllIndexPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 100);
			index.addRecord(3, 100);
			final int[] result =
				index.getAllReferenceIndexes(100);
			assertArrayEquals(new int[]{1, 2, 3}, result);
		}

		@Test
		@DisplayName(
			"getReferencedPrimaryKeysForIndexPks: empty input"
		)
		void shouldReturnEmptyForEmptyInput() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			final Bitmap result =
				index.getReferencedPrimaryKeysForIndexPks(
					new BaseBitmap()
				);
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName(
			"getReferencedPrimaryKeysForIndexPks: matching"
		)
		void shouldReturnMatchingRefPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 200);
			final Bitmap result =
				index.getReferencedPrimaryKeysForIndexPks(
					new BaseBitmap(1)
				);
			assertFalse(result.isEmpty());
			assertTrue(result.contains(100));
		}

		@Test
		@DisplayName(
			"getReferencedPrimaryKeysForIndexPks: no match"
		)
		void shouldReturnEmptyForNoMatch() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			final Bitmap result =
				index.getReferencedPrimaryKeysForIndexPks(
					new BaseBitmap(999)
				);
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName(
			"getIndexPrimaryKeys: empty input → EmptyBitmap"
		)
		void shouldReturnEmptyForEmptyRefPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf()
			);
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName(
			"getIndexPrimaryKeys: matching referencedPks"
		)
		void shouldReturnMatchingIndexPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 100);
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(100)
			);
			assertEquals(2, result.size());
			assertTrue(result.contains(1));
			assertTrue(result.contains(2));
		}

		@Test
		@DisplayName(
			"getIndexPrimaryKeys: multiple matching referencedPks"
		)
		void shouldReturnForMultipleRefPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 200);
			index.addRecord(3, 300);
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(100, 300)
			);
			assertEquals(2, result.size());
			assertTrue(result.contains(1));
			assertTrue(result.contains(3));
		}

		@Test
		@DisplayName(
			"getIndexPrimaryKeys: no matching → empty"
		)
		void shouldReturnEmptyForNoMatchingRefPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(999)
			);
			assertTrue(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Memoization cache")
	class MemoizationCacheTest {

		@Test
		@DisplayName(
			"non-tx: getIndexPrimaryKeys same result on second call"
		)
		void shouldReturnSameResultFromCache() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);

			final RoaringBitmap query =
				RoaringBitmap.bitmapOf(100);
			final Bitmap first = index.getIndexPrimaryKeys(query);
			final Bitmap second = index.getIndexPrimaryKeys(query);
			assertEquals(first.size(), second.size());
			assertTrue(first.contains(1));
			assertTrue(second.contains(1));
		}

		@Test
		@DisplayName(
			"cache invalidated on addRecord"
		)
		void shouldInvalidateCacheOnAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);

			// populate cache
			final Bitmap first = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(100)
			);
			assertEquals(1, first.size());

			// add new referenced PK
			index.addRecord(2, 200);

			// cache should be invalidated
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(200)
			);
			assertEquals(1, result.size());
			assertTrue(result.contains(2));
		}

		@Test
		@DisplayName("cache invalidated on removeRecord")
		void shouldInvalidateCacheOnRemove() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 200);

			// populate cache
			index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(100, 200)
			);

			// remove
			index.removeRecord(2, 200);

			// cache should be invalidated
			final Bitmap result = index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(200)
			);
			assertTrue(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Dirty flag / Storage part")
	class DirtyFlagTest {

		@Test
		@DisplayName("createStoragePart returns null when not dirty")
		void shouldReturnNullWhenNotDirty() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertNull(index.createStoragePart(1, "ref"));
		}

		@Test
		@DisplayName(
			"createStoragePart returns non-null after addRecord"
		)
		void shouldReturnNonNullAfterAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertNotNull(index.createStoragePart(1, "ref"));
		}

		@Test
		@DisplayName(
			"createStoragePart returns non-null after removeRecord"
		)
		void shouldReturnNonNullAfterRemove() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.resetDirty();
			index.removeRecord(1, 100);
			assertNotNull(index.createStoragePart(1, "ref"));
		}

		@Test
		@DisplayName(
			"resetDirty → createStoragePart returns null"
		)
		void shouldReturnNullAfterReset() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.resetDirty();
			assertNull(index.createStoragePart(1, "ref"));
		}
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit add → new instance with record (INV-7)"
		)
		void shouldCommitAddAndReturnNewInstance() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterCommit(
				index,
				original -> original.addRecord(1, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertFalse(committed.isEmpty());
					assertArrayEquals(
						new int[]{1},
						committed.getAllReferenceIndexes(100)
					);
				}
			);
		}

		@Test
		@DisplayName(
			"commit with no mutations → same instance (INV-8)"
		)
		void shouldReturnSameInstanceWhenNotDirty() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterCommit(
				index,
				original -> {
					// no mutations
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("T2: Original unchanged after commit")
		void shouldPreserveOriginalAfterCommit() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord(1, 100);
					original.addRecord(2, 200);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName(
			"T5: Multiple records across referencedPks — all flushed"
		)
		void shouldCommitMultipleRecordsAtomically() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord(1, 100);
					original.addRecord(2, 200);
					original.addRecord(3, 300);
					original.addRecord(4, 100);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
					assertArrayEquals(
						new int[]{1, 4},
						committed.getAllReferenceIndexes(100)
					);
					assertArrayEquals(
						new int[]{2},
						committed.getAllReferenceIndexes(200)
					);
					assertArrayEquals(
						new int[]{3},
						committed.getAllReferenceIndexes(300)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — Rollback")
	class RollbackTest {

		@Test
		@DisplayName(
			"rollback after add → original empty (T7)"
		)
		void shouldRollbackAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterRollback(
				index,
				original -> original.addRecord(1, 100),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}

		@Test
		@DisplayName(
			"rollback after remove → original record still present"
		)
		void shouldRollbackRemove() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.resetDirty();

			assertStateAfterRollback(
				index,
				original -> original.removeRecord(1, 100),
				(original, committed) -> {
					assertNull(committed);
					assertFalse(original.isEmpty());
					assertArrayEquals(
						new int[]{1},
						original.getAllReferenceIndexes(100)
					);
				}
			);
		}

		@Test
		@DisplayName("index not marked dirty after rollback")
		void shouldNotBeDirtyAfterRollback() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterRollback(
				index,
				original -> original.addRecord(1, 100),
				(original, committed) -> {
					assertNull(committed);
					assertNull(
						index.createStoragePart(1, "ref")
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName(
			"multiple indexPks for same refPk, remove one by one"
		)
		void shouldShrinkCorrectly() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 100);
			index.addRecord(3, 100);

			index.removeRecord(2, 100);
			assertArrayEquals(
				new int[]{1, 3},
				index.getAllReferenceIndexes(100)
			);

			index.removeRecord(1, 100);
			assertArrayEquals(
				new int[]{3},
				index.getAllReferenceIndexes(100)
			);

			index.removeRecord(3, 100);
			assertArrayEquals(
				new int[0],
				index.getAllReferenceIndexes(100)
			);
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"add then remove in same transaction → committed empty"
		)
		void shouldCommitEmptyAfterAddAndRemove() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord(1, 100);
					original.removeRecord(1, 100);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertTrue(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName(
			"1000 distinct referencedPks → all retrievable"
		)
		void shouldHandleManyReferencedPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			for (int i = 1; i <= 1000; i++) {
				index.addRecord(i, i * 10);
			}
			for (int i = 1; i <= 1000; i++) {
				assertArrayEquals(
					new int[]{i},
					index.getAllReferenceIndexes(i * 10)
				);
			}
		}
	}

	@Nested
	@DisplayName("BUG-6: empty bitmap cleanup on removeRecord")
	class EmptyBitmapCleanup {

		@Test
		@DisplayName(
			"should remove empty bitmap after last removal"
		)
		void shouldRemoveEmptyBitmapAfterLastRemoval() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertFalse(
				index.getReferencedPrimaryKeysIndex().isEmpty()
			);
			index.removeRecord(1, 100);
			assertTrue(
				index.getReferencedPrimaryKeysIndex().isEmpty()
			);
		}

		@Test
		@DisplayName(
			"should keep bitmap when other indexPKs remain"
		)
		void shouldKeepBitmapWhenOtherIndexPKsRemain() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(2, 100);
			index.removeRecord(1, 100);
			assertFalse(
				index.getReferencedPrimaryKeysIndex().isEmpty()
			);
			assertArrayEquals(
				new int[]{2},
				index.getAllReferenceIndexes(100)
			);
		}
	}

}
