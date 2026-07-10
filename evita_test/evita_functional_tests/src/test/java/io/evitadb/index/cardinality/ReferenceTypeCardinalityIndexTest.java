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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Collections;
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
		@DisplayName("returns BOUNDARY_CROSSED on first add for given indexPk")
		void shouldReportBoundaryCrossedOnFirstAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertEquals(CardinalityChange.BOUNDARY_CROSSED, index.addRecord(1, 100));
		}

		@Test
		@DisplayName(
			"returns NO_BOUNDARY_CROSSING on second add for same indexPk"
		)
		void shouldReportNoBoundaryCrossingOnSecondAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, index.addRecord(1, 200));
		}

		@Test
		@DisplayName("returns BOUNDARY_CROSSED when new indexPk is added")
		void shouldReportBoundaryCrossedForNewIndexPk() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertEquals(CardinalityChange.BOUNDARY_CROSSED, index.addRecord(2, 100));
		}

		@Test
		@DisplayName(
			"multiple referencedPks under same indexPk"
		)
		void shouldTrackMultipleReferencedPks() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertEquals(CardinalityChange.BOUNDARY_CROSSED, index.addRecord(1, 100));
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, index.addRecord(1, 200));
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, index.addRecord(1, 300));
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
		@DisplayName("returns BOUNDARY_CROSSED when indexPk fully evicted")
		void shouldReportBoundaryCrossedWhenFullyEvicted() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertEquals(CardinalityChange.BOUNDARY_CROSSED, index.removeRecord(1, 100));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"returns NO_BOUNDARY_CROSSING when indexPk still has other referencedPks"
		)
		void shouldReportNoBoundaryCrossingWhenStillPresent() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.addRecord(1, 200);
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, index.removeRecord(1, 100));
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
				PersistentRoaringBitmap.bitmapOf()
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
				PersistentRoaringBitmap.bitmapOf(100)
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
				PersistentRoaringBitmap.bitmapOf(100, 300)
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
				PersistentRoaringBitmap.bitmapOf(999)
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

			final PersistentRoaringBitmap query =
				PersistentRoaringBitmap.bitmapOf(100);
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
				PersistentRoaringBitmap.bitmapOf(100)
			);
			assertEquals(1, first.size());

			// add new referenced PK
			index.addRecord(2, 200);

			// cache should be invalidated
			final Bitmap result = index.getIndexPrimaryKeys(
				PersistentRoaringBitmap.bitmapOf(200)
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
				PersistentRoaringBitmap.bitmapOf(100, 200)
			);

			// remove
			index.removeRecord(2, 200);

			// cache should be invalidated
			final Bitmap result = index.getIndexPrimaryKeys(
				PersistentRoaringBitmap.bitmapOf(200)
			);
			assertTrue(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Dirty flag / Storage part")
	class DirtyFlagTest {

		@Test
		@DisplayName("appendStorageParts emits nothing when not dirty")
		void shouldEmitNothingWhenNotDirty() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			assertEquals(0, emittedPartCount(index));
		}

		@Test
		@DisplayName(
			"appendStorageParts emits a part after addRecord"
		)
		void shouldEmitPartAfterAdd() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			assertTrue(emittedPartCount(index) > 0);
		}

		@Test
		@DisplayName(
			"appendStorageParts emits a part after removeRecord"
		)
		void shouldEmitPartAfterRemove() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.resetDirty();
			index.removeRecord(1, 100);
			assertTrue(emittedPartCount(index) > 0);
		}

		@Test
		@DisplayName(
			"resetDirty → appendStorageParts emits nothing"
		)
		void shouldEmitNothingAfterReset() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			index.addRecord(1, 100);
			index.resetDirty();
			assertEquals(0, emittedPartCount(index));
		}
	}

	/**
	 * Counts the storage parts the index appends to a fresh {@link TrappedChanges} sink — a dirty index emits at least
	 * one (the inline SINGLE root or the PAGED leaf pages + root), a clean index emits none.
	 *
	 * @param index the cardinality index to flush
	 * @return the number of emitted storage parts
	 */
	private static int emittedPartCount(@Nonnull ReferenceTypeCardinalityIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(1, "ref", trappedChanges);
		return trappedChanges.getTrappedChangesCount();
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit add → new instance with record"
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
			"commit with no mutations → same instance"
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
					assertEquals(0, emittedPartCount(index));
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
	@DisplayName("Empty bitmap cleanup on removeRecord")
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

	@Nested
	@DisplayName("Granular paging shape")
	class GranularPagingShape {

		@Test
		@DisplayName(
			"stays inline while small and pages once the leaf splits"
		)
		void shouldStayInlineForSmallIndexAndPageWhenItGrows() {
			final ReferenceTypeCardinalityIndex index =
				new ReferenceTypeCardinalityIndex();
			// a handful of tuples stays well within the 256-entry leaf (the SINGLE shape)
			for (int i = 1; i <= 5; i++) {
				index.addRecord(i, 1_000 + i);
			}
			assertFalse(index.isPaged(), "a small index must stay inline (SINGLE)");

			// each addRecord writes two composed-key tree entries, so enough distinct tuples split the leaf into more
			// than one — flipping the index to the PAGED shape on the same instance
			for (int i = 6; i <= 300; i++) {
				index.addRecord(i, 1_000 + i);
			}
			assertTrue(index.isPaged(), "a grown index must page once the leaf splits");
		}

		@Test
		@DisplayName(
			"fromPersistedPages rejects empty and misaligned page arrays"
		)
		void shouldRejectEmptyPageArraysInFromPersistedPages() {
			// the length>0 premise: a paged index must have at least one leaf page
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceTypeCardinalityIndex.fromPersistedPages(
					new int[0], new long[0][], new long[0][], 0, Collections.emptyMap()
				)
			);
			// the alignment premise: the page-sequence count must match the leaf-page array counts
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceTypeCardinalityIndex.fromPersistedPages(
					new int[]{0, 1}, new long[][]{{10L}}, new long[][]{{1L}}, 1, Collections.emptyMap()
				)
			);
		}

		@Test
		@DisplayName(
			"fromPersistedPages with a single page reassembles as the inline (SINGLE) shape"
		)
		void shouldReassembleSinglePageAsInlineShape() {
			final long[] seedKeys = {10L, 20L, 30L};
			final long[] seedPayloads = {1L, 2L, 3L};
			final ReferenceTypeCardinalityIndex index =
				ReferenceTypeCardinalityIndex.fromPersistedPages(
					new int[]{0}, new long[][]{seedKeys}, new long[][]{seedPayloads}, 0, Collections.emptyMap()
				);

			assertFalse(index.isPaged(), "a single leaf page must reassemble as SINGLE");
			final Map<Long, Integer> expected = CollectionUtils.createHashMap(4);
			for (int i = 0; i < seedKeys.length; i++) {
				expected.put(seedKeys[i], (int) seedPayloads[i]);
			}
			assertEquals(expected, index.getCardinalities(), "the reassembled cardinalities must equal the seed");
		}
	}

}
