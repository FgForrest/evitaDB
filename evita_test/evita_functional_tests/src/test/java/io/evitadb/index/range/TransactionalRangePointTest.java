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

package io.evitadb.index.range;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionalRangePoint} covering construction,
 * non-transactional operations, dirty tracking, STM commit/rollback,
 * deepEquals, makeClone, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionalRangePoint")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalRangePointTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("single-arg constructor: threshold set, starts/ends empty")
		void shouldInitializeWithThresholdOnly() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(100L);
			assertEquals(100L, point.getThreshold());
			assertTrue(point.getStarts().isEmpty());
			assertTrue(point.getEnds().isEmpty());
		}

		@Test
		@DisplayName("int-array constructor: all three fields set")
		void shouldInitializeWithIntArrays() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					50L, new int[]{1, 2, 3}, new int[]{4, 5}
				);
			assertEquals(50L, point.getThreshold());
			assertEquals(3, point.getStarts().size());
			assertTrue(point.getStarts().contains(1));
			assertTrue(point.getStarts().contains(2));
			assertTrue(point.getStarts().contains(3));
			assertEquals(2, point.getEnds().size());
			assertTrue(point.getEnds().contains(4));
			assertTrue(point.getEnds().contains(5));
		}

		@Test
		@DisplayName("bitmap constructor: starts/ends mirror input bitmaps")
		void shouldInitializeWithBitmaps() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L,
					new BaseBitmap(10, 20),
					new BaseBitmap(30, 40)
				);
			assertEquals(10L, point.getThreshold());
			assertEquals(2, point.getStarts().size());
			assertTrue(point.getStarts().contains(10));
			assertTrue(point.getStarts().contains(20));
			assertEquals(2, point.getEnds().size());
			assertTrue(point.getEnds().contains(30));
			assertTrue(point.getEnds().contains(40));
		}
	}

	@Nested
	@DisplayName("Dirty-tracking behavior")
	class DirtyTrackingTest {

		@Test
		@DisplayName("addStart marks dirty → new instance on commit")
		void shouldMarkDirtyOnAddStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addStart(10),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.getStarts().contains(10));
				}
			);
		}

		@Test
		@DisplayName("addEnd marks dirty → new instance on commit")
		void shouldMarkDirtyOnAddEnd() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addEnd(20),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.getEnds().contains(20));
				}
			);
		}

		@Test
		@DisplayName("addStart of several records marks dirty")
		void shouldMarkDirtyOnAddStartOfSeveralRecords() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> {
					assertSame(original, original.addStart(1));
					assertSame(original, original.addStart(2));
					assertSame(original, original.addStart(3));
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(3, committed.getStarts().size());
				}
			);
		}

		@Test
		@DisplayName("addEnd of several records marks dirty")
		void shouldMarkDirtyOnAddEndOfSeveralRecords() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> {
					assertSame(original, original.addEnd(4));
					assertSame(original, original.addEnd(5));
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(2, committed.getEnds().size());
				}
			);
		}

		@Test
		@DisplayName("removeStart marks dirty")
		void shouldMarkDirtyOnRemoveStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1, 2, 3}, new int[]{}
				);

			assertStateAfterCommit(
				point,
				original -> original.removeStart(2),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(2, committed.getStarts().size());
					assertFalse(committed.getStarts().contains(2));
				}
			);
		}

		@Test
		@DisplayName("removeEnd marks dirty")
		void shouldMarkDirtyOnRemoveEnd() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{}, new int[]{10, 20}
				);

			assertStateAfterCommit(
				point,
				original -> original.removeEnd(10),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(1, committed.getEnds().size());
					assertFalse(committed.getEnds().contains(10));
				}
			);
		}

		@Test
		@DisplayName(
			"no mutations → same instance returned (INV-8)"
		)
		void shouldReturnSameInstanceWhenNotDirty() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);

			assertStateAfterCommit(
				point,
				original -> {
					// no mutations
				},
				Assertions::assertSame
			);
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName(
			"INV-6/INV-7: commit with mutations returns new instance"
		)
		void shouldReturnNewInstanceWithCorrectData() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					5L, new int[]{1, 2}, new int[]{3}
				);

			assertStateAfterCommit(
				point,
				original -> {
					original.addStart(10);
					original.addEnd(20);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(5L, committed.getThreshold());
					assertEquals(3, committed.getStarts().size());
					assertTrue(committed.getStarts().contains(10));
					assertEquals(2, committed.getEnds().size());
					assertTrue(committed.getEnds().contains(20));
				}
			);
		}

		@Test
		@DisplayName(
			"getMaintainedTransactionalCreators returns 3 elements"
		)
		void shouldReturnThreeMaintainedCreators() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			assertEquals(
				3,
				point.getMaintainedTransactionalCreators().size()
			);
		}

		@Test
		@DisplayName(
			"getMaintainedTransactionalCreators adds every repeated occurrence level"
		)
		void shouldMaintainEveryRepeatedLevel() {
			final TransactionalRangePoint point = new TransactionalRangePoint(
				1L, new BaseBitmap(1), new BaseBitmap(1),
				new Bitmap[]{new BaseBitmap(1), new BaseBitmap(1)}, new Bitmap[]{new BaseBitmap(1)}
			);
			assertEquals(
				6,
				point.getMaintainedTransactionalCreators().size()
			);
		}
	}

	@Nested
	@DisplayName("Commit and rollback")
	class CommitAndRollbackTest {

		@Test
		@DisplayName(
			"commit: original unchanged, committed reflects mutations (T1, T2)"
		)
		void shouldPreserveOriginalOnCommit() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1}, new int[]{2}
				);

			assertStateAfterCommit(
				point,
				original -> {
					original.addStart(5);
					original.addEnd(6);
					original.removeStart(1);
				},
				(original, committed) -> {
					// original unchanged (T2)
					assertEquals(1, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					assertEquals(1, original.getEnds().size());
					assertTrue(original.getEnds().contains(2));
					// committed has all mutations
					assertEquals(1, committed.getStarts().size());
					assertTrue(committed.getStarts().contains(5));
					assertFalse(committed.getStarts().contains(1));
					assertEquals(2, committed.getEnds().size());
					assertTrue(committed.getEnds().contains(2));
					assertTrue(committed.getEnds().contains(6));
				}
			);
		}

		@Test
		@DisplayName(
			"rollback: mutations to starts/ends fully undone (T7)"
		)
		void shouldRollbackMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1, 2}, new int[]{3}
				);

			assertStateAfterRollback(
				point,
				original -> {
					original.addStart(99);
					original.addEnd(88);
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(2, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					assertTrue(original.getStarts().contains(2));
					assertFalse(original.getStarts().contains(99));
					assertEquals(1, original.getEnds().size());
					assertTrue(original.getEnds().contains(3));
					assertFalse(original.getEnds().contains(88));
				}
			);
		}

		@Test
		@DisplayName(
			"rollback after mixed mutations (addStart + removeEnd)"
		)
		void shouldRollbackMixedMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1}, new int[]{2, 3}
				);

			assertStateAfterRollback(
				point,
				original -> {
					original.addStart(50);
					original.removeEnd(2);
				},
				(original, committed) -> {
					assertNull(committed);
					// starts unchanged
					assertEquals(1, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					// ends unchanged
					assertEquals(2, original.getEnds().size());
					assertTrue(original.getEnds().contains(2));
					assertTrue(original.getEnds().contains(3));
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mode (T8)")
	class NonTransactionalTest {

		@Test
		@DisplayName("all mutation methods work outside transaction")
		void shouldSupportNonTransactionalMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			point.addStart(1);
			assertTrue(point.getStarts().contains(1));

			point.addEnd(2);
			assertTrue(point.getEnds().contains(2));

			point.addStart(3);
			point.addStart(4);
			assertTrue(point.getStarts().contains(3));
			assertTrue(point.getStarts().contains(4));

			point.addEnd(5);
			point.addEnd(6);
			assertTrue(point.getEnds().contains(5));
			assertTrue(point.getEnds().contains(6));

			point.removeStart(3);
			assertFalse(point.getStarts().contains(3));

			point.removeEnd(5);
			assertFalse(point.getEnds().contains(5));
		}
	}

	@Nested
	@DisplayName("deepEquals / equals / hashCode")
	class EqualityTest {

		@Test
		@DisplayName("deepEquals(this) returns true")
		void shouldBeDeepEqualToSelf() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			assertTrue(point.deepEquals(point));
		}

		@Test
		@DisplayName("deepEquals(null) returns false")
		void shouldNotBeDeepEqualToNull() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			assertFalse(point.deepEquals(null));
		}

		@Test
		@DisplayName(
			"same threshold, different starts → deepEquals false"
		)
		void shouldNotBeDeepEqualWithDifferentStarts() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{99}, new int[]{2}
				);
			assertFalse(p1.deepEquals(p2));
		}

		@Test
		@DisplayName(
			"same threshold, different ends → deepEquals false"
		)
		void shouldNotBeDeepEqualWithDifferentEnds() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{99}
				);
			assertFalse(p1.deepEquals(p2));
		}

		@Test
		@DisplayName(
			"equals uses only threshold (Lombok @EqualsAndHashCode)"
		)
		void shouldBeEqualByThresholdOnly() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{99}, new int[]{88}
				);
			assertEquals(p1, p2);
		}

		@Test
		@DisplayName("hashCode consistent for same threshold")
		void shouldHaveConsistentHashCode() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(42L);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(42L);
			assertEquals(p1.hashCode(), p2.hashCode());
		}
	}

	@Nested
	@DisplayName("makeClone")
	class MakeCloneTest {

		@Test
		@DisplayName("clone is new instance with same data")
		void shouldCloneWithSameData() {
			final TransactionalRangePoint original =
				new TransactionalRangePoint(
					5L, new int[]{1, 2}, new int[]{3, 4}
				);
			final TransactionalRangePoint clone = original.makeClone();
			assertNotSame(original, clone);
			assertEquals(5L, clone.getThreshold());
			assertTrue(clone.deepEquals(original));
		}

		@Test
		@DisplayName("mutating clone does not affect original")
		void shouldNotAffectOriginalOnCloneMutation() {
			final TransactionalRangePoint original =
				new TransactionalRangePoint(
					5L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint clone = original.makeClone();
			clone.addStart(99);
			clone.addEnd(88);
			assertFalse(original.getStarts().contains(99));
			assertFalse(original.getEnds().contains(88));
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("addStart same ID twice counts it twice on a new point, leaving this one untouched")
		void shouldCountSecondStartOfSameRecordOnNewPoint() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			assertSame(point, point.addStart(10));
			final TransactionalRangePoint grown = point.addStart(10);

			assertNotSame(point, grown);
			assertArrayEquals(new int[]{10}, grown.getStarts().getArray());
			assertEquals(1, grown.getRepeatedStarts().length);
			assertArrayEquals(new int[]{10}, grown.getRepeatedStarts()[0].getArray());
			assertEquals(0, grown.getRepeatedEnds().length);
			// the replaced point still describes the state before the second start
			assertArrayEquals(new int[]{10}, point.getStarts().getArray());
			assertEquals(0, point.getRepeatedStarts().length);
		}

		@Test
		@DisplayName("removeStart on never-added ID (graceful no-op)")
		void shouldHandleRemoveOfAbsentStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			// should not throw
			point.removeStart(999);
			assertTrue(point.getStarts().isEmpty());
		}

		@Test
		@DisplayName("removeEnd on empty bitmap (graceful no-op)")
		void shouldHandleRemoveOnEmptyEnds() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			// should not throw
			for (final int recordId : new int[]{1, 2, 3}) {
				point.removeEnd(recordId);
			}
			assertTrue(point.getEnds().isEmpty());
		}

		@Test
		@DisplayName("extreme threshold values")
		void shouldHandleExtremeThresholds() {
			final TransactionalRangePoint minPoint =
				new TransactionalRangePoint(Long.MIN_VALUE);
			assertEquals(Long.MIN_VALUE, minPoint.getThreshold());

			final TransactionalRangePoint maxPoint =
				new TransactionalRangePoint(Long.MAX_VALUE);
			assertEquals(Long.MAX_VALUE, maxPoint.getThreshold());
		}
	}

	@Nested
	@DisplayName("Occurrence levels of one record starting or ending here several times")
	class OccurrenceLevelsTest {

		/**
		 * Builds a point at threshold 1 holding the passed start levels and no ends.
		 *
		 * @param levels the start levels, level 1 first
		 * @return the point
		 */
		@Nonnull
		private static TransactionalRangePoint withStartLevels(@Nonnull int[]... levels) {
			final Bitmap[] repeated = new Bitmap[levels.length - 1];
			for (int i = 1; i < levels.length; i++) {
				repeated[i - 1] = new BaseBitmap(levels[i]);
			}
			return new TransactionalRangePoint(1L, new BaseBitmap(levels[0]), new BaseBitmap(), repeated, null);
		}

		/**
		 * Returns the record ids of every non-empty start level of the point, level 1 first — spare levels left out.
		 *
		 * @param point the point to read
		 * @return the start levels as arrays
		 */
		@Nonnull
		private static int[][] startLevels(@Nonnull TransactionalRangePoint point) {
			final List<int[]> result = new ArrayList<>();
			result.add(point.getStarts().getArray());
			for (final Bitmap level : point.getRepeatedStarts()) {
				if (level.isEmpty()) {
					break;
				}
				result.add(level.getArray());
			}
			return result.toArray(int[][]::new);
		}

		@Test
		@DisplayName("an addition goes to the lowest level not holding the record, a new array only when all hold it")
		void shouldAddToLowestLevelNotHoldingRecord() {
			final TransactionalRangePoint point = withStartLevels(new int[]{1, 2}, new int[]{1});

			assertSame(point, point.addStart(2), "level 2 does not hold record 2 yet");
			assertArrayEquals(new int[][]{{1, 2}, {1, 2}}, startLevels(point));

			final TransactionalRangePoint grown = point.addStart(1);
			assertNotSame(point, grown, "both levels hold record 1 - a third one is needed");
			assertArrayEquals(new int[][]{{1, 2}, {1, 2}, {1}}, startLevels(grown));
			assertArrayEquals(new int[][]{{1, 2}, {1, 2}}, startLevels(point), "the replaced point is not touched");
		}

		@Test
		@DisplayName("the level array doubles, so a record starting here many times rarely needs a new point")
		void shouldDoubleLevelArrayWhenItRunsOut() {
			TransactionalRangePoint point = new TransactionalRangePoint(1L);
			final List<Integer> lengthsAtGrowth = new ArrayList<>();
			for (int occurrence = 1; occurrence <= 17; occurrence++) {
				final TransactionalRangePoint next = point.addStart(5);
				if (next != point) {
					lengthsAtGrowth.add(next.getRepeatedStarts().length);
				}
				point = next;
			}
			assertEquals(List.of(1, 2, 4, 8, 16), lengthsAtGrowth);
			assertEquals(17, startLevels(point).length);
		}

		@Test
		@DisplayName("a record alternately starting and ending here doubles each side on its own, not every addition")
		void shouldDoubleEachSideIndependentlyWhenStartsAndEndsAlternate() {
			final int pairs = 1_000;
			TransactionalRangePoint point = new TransactionalRangePoint(1L);
			final List<Integer> startLengthsAtGrowth = new ArrayList<>();
			final List<Integer> endLengthsAtGrowth = new ArrayList<>();
			for (int occurrence = 1; occurrence <= pairs; occurrence++) {
				final TransactionalRangePoint afterStart = point.addStart(5);
				if (afterStart != point) {
					startLengthsAtGrowth.add(afterStart.getRepeatedStarts().length);
				}
				final TransactionalRangePoint afterEnd = afterStart.addEnd(5);
				if (afterEnd != afterStart) {
					endLengthsAtGrowth.add(afterEnd.getRepeatedEnds().length);
				}
				point = afterEnd;
			}
			// the 999 repeated occurrences of each side need the lengths 1, 2, 4, ..., 1024 - eleven new points per
			// side; a growth that dropped the other side's spare levels would make that side grow again on its next
			// addition, which is a new point on almost every one of the 2,000 additions
			final List<Integer> doubling = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024);
			assertEquals(doubling, startLengthsAtGrowth, "start-level arrays allocated while the sides alternate");
			assertEquals(doubling, endLengthsAtGrowth, "end-level arrays allocated while the sides alternate");
			assertEquals(pairs, startLevels(point).length, "every start occurrence is kept");
			final TransactionalBitmap[] repeatedEnds = point.getRepeatedEnds();
			for (int level = 0; level < pairs - 1; level++) {
				assertArrayEquals(new int[]{5}, repeatedEnds[level].getArray(), "end level " + (level + 2));
			}
			assertTrue(repeatedEnds[pairs - 1].isEmpty(), "the end levels past the last occurrence are spare");
		}

		@Test
		@DisplayName("a removal empties the highest level holding the record in place, and the level is refilled later")
		void shouldRemoveFromHighestLevelHoldingRecord() {
			final TransactionalRangePoint point = withStartLevels(new int[]{1, 2}, new int[]{1, 2}, new int[]{1});

			point.removeStart(2);
			assertArrayEquals(new int[][]{{1, 2}, {1}, {1}}, startLevels(point), "record 2 leaves level 2");

			point.removeStart(1);
			assertArrayEquals(new int[][]{{1, 2}, {1}}, startLevels(point), "record 1 leaves level 3");
			point.removeStart(1);
			assertArrayEquals(new int[][]{{1, 2}}, startLevels(point), "record 1 leaves level 2");
			assertEquals(2, point.getRepeatedStarts().length, "the emptied levels stay as spares");

			assertSame(point, point.addStart(2), "a spare level takes the next repeated occurrence");
			assertArrayEquals(new int[][]{{1, 2}, {2}}, startLevels(point));

			point.removeStart(1);
			point.removeStart(2);
			assertArrayEquals(new int[][]{{2}}, startLevels(point));
		}

		@Test
		@DisplayName("end levels are counted independently of start levels")
		void shouldCountEndLevelsIndependentlyOfStartLevels() {
			final TransactionalRangePoint point = new TransactionalRangePoint(1L)
				.addStart(7).addStart(7)
				.addEnd(7).addEnd(7).addEnd(7);

			assertArrayEquals(new int[]{7}, point.getRepeatedStarts()[0].getArray());
			assertArrayEquals(new int[]{7}, point.getRepeatedEnds()[1].getArray());

			point.removeEnd(7);
			assertArrayEquals(new int[]{7}, point.getRepeatedStarts()[0].getArray(), "start levels stay as they were");
			assertTrue(point.getRepeatedEnds()[1].isEmpty());
			assertArrayEquals(new int[]{7}, point.getRepeatedEnds()[0].getArray());
		}

		@Test
		@DisplayName("deepEquals and deepHashCode see the repeated levels but not the spare ones")
		void shouldCompareRepeatedLevels() {
			final TransactionalRangePoint twice = withStartLevels(new int[]{1}, new int[]{1});
			final TransactionalRangePoint once = withStartLevels(new int[]{1});
			final TransactionalRangePoint twiceAgain = withStartLevels(new int[]{1}, new int[]{1});

			assertFalse(twice.deepEquals(once));
			assertFalse(once.deepEquals(twice));
			assertTrue(twice.deepEquals(twiceAgain));
			assertEquals(twice.deepHashCode(), twiceAgain.deepHashCode());
			assertNotEquals(twice.deepHashCode(), once.deepHashCode());

			final TransactionalRangePoint endedTwice = new TransactionalRangePoint(
				1L, new BaseBitmap(1), new BaseBitmap(), null, new Bitmap[]{new BaseBitmap(1)}
			);
			assertFalse(twice.deepEquals(endedTwice), "a repeated start is not a repeated end");

			twice.removeStart(1);
			assertEquals(1, twice.getRepeatedStarts().length, "precondition: an empty spare level is left behind");
			assertTrue(twice.deepEquals(once));
			assertEquals(twice.deepHashCode(), once.deepHashCode());
		}

		@Test
		@DisplayName("makeClone copies the non-empty levels into bitmaps of its own and leaves the spares out")
		void shouldCloneRepeatedLevels() {
			final TransactionalRangePoint original = withStartLevels(new int[]{1, 2}, new int[]{1}, new int[]{1});
			original.removeStart(1);
			final TransactionalRangePoint clone = original.makeClone();

			assertTrue(clone.deepEquals(original));
			assertEquals(1, clone.getRepeatedStarts().length);
			assertSame(clone, clone.addStart(2));
			assertArrayEquals(new int[]{1}, original.getRepeatedStarts()[0].getArray());
		}

		@Test
		@DisplayName("a level mutated in a transaction is merged on commit and the original keeps its levels")
		void shouldMergeRepeatedLevelOnCommit() {
			assertStateAfterCommit(
				withStartLevels(new int[]{1, 2}, new int[]{1}, new int[]{1}),
				original -> {
					assertSame(original, original.addStart(2));
					original.removeStart(1);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertArrayEquals(new int[][]{{1, 2}, {1, 2}}, startLevels(committed));
					assertEquals(1, committed.getRepeatedStarts().length, "the committed copy drops the spare level");
					assertArrayEquals(new int[][]{{1, 2}, {1}, {1}}, startLevels(original));
				}
			);
		}

		@Test
		@DisplayName("a level mutated in a transaction is restored by a rollback")
		void shouldRestoreRepeatedLevelOnRollback() {
			assertStateAfterRollback(
				withStartLevels(new int[]{1, 2}, new int[]{1, 2}),
				original -> original.removeStart(2),
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(new int[][]{{1, 2}, {1, 2}}, startLevels(original));
				}
			);
		}

		@Test
		@DisplayName("the heap size includes the repeated levels")
		void shouldChargeRepeatedLevelsToHeapSize() {
			final TransactionalRangePoint once = withStartLevels(new int[]{1});
			final TransactionalRangePoint twice = withStartLevels(new int[]{1}, new int[]{1});

			assertTrue(
				twice.getHeapSizeInBytes() > once.getHeapSizeInBytes(),
				"a point with a repeated level must weigh more than the same point without it"
			);
		}
	}

	@Nested
	@DisplayName("Other")
	class OtherTest {

		@Test
		@DisplayName("toString output format verification")
		void shouldFormatToString() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					42L, new int[]{1}, new int[]{2}
				);
			final String result = point.toString();
			assertTrue(
				result.contains("42"),
				"toString should contain threshold"
			);
			assertTrue(
				result.contains("TransactionalRangePoint"),
				"toString should contain class name"
			);
		}
	}

}
