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

package io.evitadb.index.range;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.RangeCountFormula;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link RangeIndex} answers when ONE record id holds several ranges that share a border — the shape the
 * contribution-keyed indexes produce, where several owners of one referenced entity add their ranges to that entity's
 * single record id.
 *
 * Every query counts, per record, the ranges that started minus the ranges that ended before the examined point, so a
 * shared border has to be kept once per range: a start kept once makes the record vanish while the longer range still
 * runs, an end kept once keeps it after both ranges are over, and removing one of two ranges must not strip the border
 * from the other. Each scenario has a control with the borders shifted apart. The nests below also cover borders
 * shared more than twice, the suffix query, transactions and savepoints, which repeated levels a query turns into
 * operands, the paged persistence of the levels, and the range-histogram sweep, which has to count a record's open
 * ranges for the same reason.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("RangeIndex: ranges of one record sharing a border")
class RangeIndexSharedBorderTest {
	private static final int RECORD = 1;
	private static final int[] JUST_RECORD = {RECORD};
	private static final int[] NOTHING = {};

	/**
	 * Computes the records whose range envelopes `threshold` - the query behind `attributeInRange`.
	 *
	 * @param index     the index to ask
	 * @param threshold the examined point
	 * @return the matching record ids
	 */
	@Nonnull
	private static int[] enveloping(@Nonnull RangeIndex index, long threshold) {
		return index.getRecordsEnvelopingInclusive(threshold).compute().getArray();
	}

	/**
	 * Describes every point of the index with all of its occurrence levels, so two indexes - or one index before and
	 * after a rollback - can be compared by content.
	 *
	 * @param index the index to describe
	 * @return one line per point: threshold, start levels and end levels, a repeated level printed as `+[ids]` and the
	 * empty spare levels left out
	 */
	@Nonnull
	private static List<String> levelsOf(@Nonnull RangeIndex index) {
		final List<String> result = new ArrayList<>();
		for (final RangePoint<?> rangePoint : index.getRanges()) {
			final TransactionalRangePoint point = (TransactionalRangePoint) rangePoint;
			final StringBuilder sb = new StringBuilder(64);
			sb.append(point.getThreshold()).append(" S").append(Arrays.toString(point.getStarts().getArray()));
			for (final Bitmap level : point.getRepeatedStarts()) {
				if (level.isEmpty()) {
					break;
				}
				sb.append('+').append(Arrays.toString(level.getArray()));
			}
			sb.append(" E").append(Arrays.toString(point.getEnds().getArray()));
			for (final Bitmap level : point.getRepeatedEnds()) {
				if (level.isEmpty()) {
					break;
				}
				sb.append('+').append(Arrays.toString(level.getArray()));
			}
			result.add(sb.toString());
		}
		return result;
	}

	/**
	 * Counts the non-empty levels of a repeated-level array, leaving its spare levels out.
	 *
	 * @param levels the repeated levels of one side of a point
	 * @return the number of levels holding a record
	 */
	private static int nonEmptyLevels(@Nonnull Bitmap[] levels) {
		int count = 0;
		while (count < levels.length && !levels[count].isEmpty()) {
			count++;
		}
		return count;
	}

	/**
	 * Returns the point of the index at `threshold`.
	 *
	 * @param index     the index to read
	 * @param threshold the threshold of the point
	 * @return the point
	 */
	@Nonnull
	private static TransactionalRangePoint pointAt(@Nonnull RangeIndex index, long threshold) {
		for (final RangePoint<?> point : index.getRanges()) {
			if (point.getThreshold() == threshold) {
				return (TransactionalRangePoint) point;
			}
		}
		throw new AssertionError("No point at threshold " + threshold);
	}

	@Nested
	@DisplayName("Shared start")
	class SharedStart {

		@Test
		@DisplayName("keeps the record valid through the longer of two ranges starting at the same threshold")
		void shouldKeepRecordValidThroughLongerRangeWhenTwoRangesShareStart() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 20L, RECORD);
			index.addRecord(0L, 30L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 25L), "[0,30] covers 25");
			assertArrayEquals(
				JUST_RECORD, index.getRecordsValidNowFormula(25L).compute().getArray(), "[0,30] covers 25"
			);
			assertArrayEquals(
				JUST_RECORD, index.getRecordsWithRangesOverlapping(25L, 28L).compute().getArray(),
				"[0,30] overlaps [25,28]"
			);
		}

		@Test
		@DisplayName("keeps the surviving range valid after the other range starting at the same threshold is removed")
		void shouldKeepSurvivingRangeWhenOneOfTwoRangesSharingStartIsRemoved() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 30L, RECORD);
			index.addRecord(0L, 20L, RECORD);
			assertArrayEquals(JUST_RECORD, enveloping(index, 10L), "precondition: both ranges cover 10");

			index.removeRecord(0L, 20L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 10L), "[0,30] still covers 10");
		}

		@Test
		@DisplayName("control: the same ranges with distinct starts are answered correctly")
		void shouldKeepRecordValidThroughLongerRangeWhenStartsDiffer() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 20L, RECORD);
			index.addRecord(1L, 30L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 25L));

			index.removeRecord(0L, 20L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 10L));
			assertArrayEquals(NOTHING, enveloping(index, 35L));
		}
	}

	@Nested
	@DisplayName("Shared open start (two `until` values)")
	class SharedOpenStart {

		@Test
		@DisplayName("keeps the record valid until the later of two open-start ranges")
		void shouldKeepRecordValidUntilLaterEndWhenTwoOpenStartRangesShareSentinel() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(Long.MIN_VALUE, 20L, RECORD);
			index.addRecord(Long.MIN_VALUE, 30L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 25L), "until(30) covers 25");
		}

		@Test
		@DisplayName("keeps the surviving open-start range valid after the other one is removed")
		void shouldKeepSurvivingOpenStartRangeWhenTheOtherIsRemoved() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(Long.MIN_VALUE, 30L, RECORD);
			index.addRecord(Long.MIN_VALUE, 20L, RECORD);

			index.removeRecord(Long.MIN_VALUE, 20L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 10L), "until(30) still covers 10");
		}
	}

	@Nested
	@DisplayName("Shared end")
	class SharedEnd {

		@Test
		@DisplayName("drops the record once both ranges ending at the same threshold are over")
		void shouldDropRecordAfterBothRangesSharingEndAreOver() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 30L, RECORD);
			index.addRecord(10L, 30L, RECORD);

			assertArrayEquals(NOTHING, enveloping(index, 35L), "neither [0,30] nor [10,30] covers 35");
		}

		@Test
		@DisplayName("drops the record after the surviving range is over once the other one sharing its end is removed")
		void shouldDropRecordAfterSurvivingRangeIsOverWhenOneOfTwoRangesSharingEndIsRemoved() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 30L, RECORD);
			index.addRecord(10L, 30L, RECORD);
			assertArrayEquals(JUST_RECORD, enveloping(index, 25L), "precondition: both ranges cover 25");

			index.removeRecord(0L, 30L, RECORD);

			assertArrayEquals(NOTHING, enveloping(index, 35L), "[10,30] does not cover 35");
		}

		@Test
		@DisplayName("control: the same ranges with distinct ends are answered correctly")
		void shouldDropRecordAfterBothRangesAreOverWhenEndsDiffer() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 30L, RECORD);
			index.addRecord(10L, 31L, RECORD);

			assertArrayEquals(NOTHING, enveloping(index, 35L));

			index.removeRecord(0L, 30L, RECORD);

			assertArrayEquals(JUST_RECORD, enveloping(index, 25L));
			assertArrayEquals(NOTHING, enveloping(index, 35L));
		}

		@Test
		@DisplayName("the suffix query counts both ends of the record")
		void shouldCountBothSharedEndsInSuffixQuery() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(10L, 30L, RECORD);
			index.addRecord(20L, 30L, RECORD);

			// getRecordsFrom(t) holds the records whose range started before t and has not ended by t
			assertArrayEquals(JUST_RECORD, index.getRecordsFrom(15L).compute().getArray(), "[10,30] is running at 15");
			assertArrayEquals(JUST_RECORD, index.getRecordsFrom(25L).compute().getArray(), "both are running at 25");
			assertArrayEquals(NOTHING, index.getRecordsFrom(5L).compute().getArray(), "nothing started before 5");
		}
	}

	@Nested
	@DisplayName("A border shared more than twice")
	class SharedMoreThanTwice {

		@Test
		@DisplayName("three ranges sharing a start are removed one by one in any order, leaving the others intact")
		void shouldKeepEveryRemainingRangeWhenThreeRangesShareStart() {
			for (final long[] removalOrder : new long[][]{{10L, 20L, 30L}, {30L, 20L, 10L}, {20L, 30L, 10L}}) {
				final RangeIndex index = new RangeIndex();
				index.addRecord(0L, 10L, RECORD);
				index.addRecord(0L, 20L, RECORD);
				index.addRecord(0L, 30L, RECORD);
				assertEquals(2, pointAt(index, 0L).getRepeatedStarts().length, "levels 2 and 3 hold the record");

				final List<Long> remaining = new ArrayList<>(List.of(10L, 20L, 30L));
				for (final long end : removalOrder) {
					index.removeRecord(0L, end, RECORD);
					remaining.remove(end);
					final long longest = remaining.isEmpty() ? -1L : remaining.getLast();
					for (final long probe : new long[]{5L, 15L, 25L, 35L}) {
						assertArrayEquals(
							probe <= longest ? JUST_RECORD : NOTHING, enveloping(index, probe),
							"after removing [0," + end + "] in order " + Arrays.toString(removalOrder) + " at " + probe
						);
					}
				}
				assertEquals(List.of(), remaining);
				assertEquals(List.of("-9223372036854775808 S[] E[]", "9223372036854775807 S[] E[]"), levelsOf(index));
			}
		}

		@Test
		@DisplayName("the same range added twice needs two removals to disappear")
		void shouldNeedTwoRemovalsForTheSameRangeAddedTwice() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(5L, 10L, RECORD);
			index.addRecord(5L, 10L, RECORD);
			index.addRecord(5L, 10L, 2);

			assertEquals(List.of("5 S[1, 2]+[1] E[]", "10 S[] E[1, 2]+[1]"), levelsOf(index).subList(1, 3));

			index.removeRecord(5L, 10L, RECORD);
			assertArrayEquals(new int[]{1, 2}, enveloping(index, 7L), "one [5,10] of record 1 is left");
			assertEquals(List.of("5 S[1, 2] E[]", "10 S[] E[1, 2]"), levelsOf(index).subList(1, 3));

			index.removeRecord(5L, 10L, RECORD);
			assertArrayEquals(new int[]{2}, enveloping(index, 7L));
		}

		@Test
		@DisplayName("a range ending where another of the same record starts keeps the record valid across the border")
		void shouldKeepRecordValidAcrossTouchingRanges() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 10L, RECORD);
			index.addRecord(10L, 20L, RECORD);

			for (final long probe : new long[]{0L, 5L, 10L, 15L, 20L}) {
				assertArrayEquals(JUST_RECORD, enveloping(index, probe), "covered at " + probe);
			}
			index.removeRecord(0L, 10L, RECORD);
			assertArrayEquals(NOTHING, enveloping(index, 5L));
			assertArrayEquals(JUST_RECORD, enveloping(index, 10L));
			assertArrayEquals(JUST_RECORD, enveloping(index, 15L));
		}
	}

	@Nested
	@DisplayName("Transactions and savepoints")
	@Tag(TRANSACTION)
	class Transactions {

		/**
		 * Builds an index holding one range of {@link #RECORD} starting at 0.
		 *
		 * @return the index
		 */
		@Nonnull
		private static RangeIndex withOneRange() {
			final RangeIndex index = new RangeIndex();
			index.addRecord(0L, 20L, RECORD);
			return index;
		}

		@Test
		@DisplayName("a level grown inside a transaction is visible there, committed, and absent from the original")
		void shouldCommitLevelGrownInTransaction() {
			assertStateAfterCommit(
				withOneRange(),
				original -> {
					original.addRecord(0L, 30L, RECORD);
					assertArrayEquals(JUST_RECORD, enveloping(original, 25L));
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertArrayEquals(NOTHING, enveloping(original, 25L));
					assertEquals(0, pointAt(original, 0L).getRepeatedStarts().length);
					assertArrayEquals(JUST_RECORD, enveloping(committed, 25L));
					assertEquals(1, pointAt(committed, 0L).getRepeatedStarts().length);
				}
			);
		}

		@Test
		@DisplayName("a level grown inside a transaction disappears on rollback")
		void shouldDiscardLevelGrownInRolledBackTransaction() {
			assertStateAfterRollback(
				withOneRange(),
				original -> original.addRecord(0L, 30L, RECORD),
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(NOTHING, enveloping(original, 25L));
					assertEquals(List.of("0 S[1] E[]", "20 S[] E[1]"), levelsOf(original).subList(1, 3));
				}
			);
		}

		@Test
		@DisplayName("a level dropped inside a transaction is committed while the original keeps it")
		void shouldCommitLevelDroppedInTransaction() {
			final RangeIndex index = withOneRange();
			index.addRecord(0L, 30L, RECORD);
			assertStateAfterCommit(
				index,
				original -> {
					original.removeRecord(0L, 20L, RECORD);
					// refill the spare level, then outgrow the level array - which replaces the point inside the
					// transaction - and drop everything but [0,30] again; no layer may be left behind
					original.addRecord(0L, 40L, RECORD);
					original.addRecord(0L, 50L, RECORD);
					original.removeRecord(0L, 40L, RECORD);
					original.removeRecord(0L, 50L, RECORD);
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertEquals(1, pointAt(original, 0L).getRepeatedStarts().length);
					assertEquals(0, pointAt(committed, 0L).getRepeatedStarts().length);
					assertArrayEquals(JUST_RECORD, enveloping(committed, 25L));
					assertArrayEquals(NOTHING, enveloping(committed, 35L));
				}
			);
		}

		@Test
		@DisplayName("a transactional savepoint rollback restores the levels it changed")
		void shouldRestoreLevelsOnTransactionalSavepointRollback() {
			assertSavepointRollbackRestores(
				withOneRange(),
				index -> index.addRecord(0L, 30L, RECORD),
				RangeIndexSharedBorderTest::levelsOf,
				index -> {
					index.addRecord(0L, 40L, RECORD);
					index.removeRecord(0L, 20L, RECORD);
					index.addRecord(5L, 40L, RECORD);
				}
			);
		}

		@Test
		@DisplayName("a warm-up savepoint rollback restores the levels it changed")
		void shouldRestoreLevelsOnWarmUpSavepointRollback() {
			assertWarmUpSavepointRollbackRestores(
				withOneRange(),
				index -> index.addRecord(0L, 30L, RECORD),
				RangeIndexSharedBorderTest::levelsOf,
				index -> {
					index.addRecord(0L, 40L, RECORD);
					index.removeRecord(0L, 20L, RECORD);
					index.removeRecord(0L, 30L, RECORD);
					index.addRecord(5L, 40L, RECORD);
				}
			);
		}

		@Test
		@DisplayName("a record alternately starting and ending at one threshold keeps every level as both sides grow")
		void shouldKeepBothSidesWhenStartsAndEndsAlternateInTransaction() {
			final long border = 1_000L;
			final int pairs = 20;
			final RangeIndex index = withOneRange();
			final List<String> before = levelsOf(index);
			final AtomicReference<List<String>> inTransaction = new AtomicReference<>();
			assertStateAfterCommit(
				index,
				original -> {
					// each pair grows the start levels and the end levels at the border once more, so the point is
					// replaced inside the transaction while the other side holds spare levels
					for (int i = 1; i <= pairs; i++) {
						original.addRecord(border, border + i, RECORD);
						original.addRecord(border - i, border, RECORD);
					}
					inTransaction.set(levelsOf(original));
					assertArrayEquals(JUST_RECORD, enveloping(original, border - pairs));
					assertArrayEquals(JUST_RECORD, enveloping(original, border + pairs));
					assertArrayEquals(NOTHING, enveloping(original, border + pairs + 1));
					assertArrayEquals(NOTHING, enveloping(original, border - pairs - 1));
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertEquals(before, levelsOf(original), "the original is untouched");
					assertEquals(inTransaction.get(), levelsOf(committed));
					final TransactionalRangePoint point = pointAt(committed, border);
					assertEquals(pairs - 1, nonEmptyLevels(point.getRepeatedStarts()), "every repeated start is kept");
					assertEquals(pairs - 1, nonEmptyLevels(point.getRepeatedEnds()), "every repeated end is kept");
					assertArrayEquals(JUST_RECORD, enveloping(committed, border + pairs));
					assertArrayEquals(NOTHING, enveloping(committed, border + pairs + 1));
				}
			);
		}
	}

	@Nested
	@DisplayName("Query operands")
	class QueryOperands {
		/**
		 * The number of distinct open-bound ranges the record holds - each one more occurrence at the shared sentinel.
		 */
		private static final int RANGES = 64;

		/**
		 * Builds an index where {@link #RECORD} holds {@link #RANGES} `until` ranges, all sharing the `MIN` sentinel
		 * and ending at `100`, `101`, … — the shape a popular referenced entity gets from many owners.
		 *
		 * @return the index
		 */
		@Nonnull
		private static RangeIndex withOpenStartRanges() {
			final RangeIndex index = new RangeIndex();
			for (int i = 0; i < RANGES; i++) {
				index.addRecord(Long.MIN_VALUE, 100L + i, RECORD);
			}
			return index;
		}

		/**
		 * Builds an index where {@link #RECORD} holds {@link #RANGES} `since` ranges starting at `1`, `2`, … and all
		 * sharing the `MAX` sentinel.
		 *
		 * @return the index
		 */
		@Nonnull
		private static RangeIndex withOpenEndRanges() {
			final RangeIndex index = new RangeIndex();
			for (int i = 1; i <= RANGES; i++) {
				index.addRecord(i, Long.MAX_VALUE, RECORD);
			}
			return index;
		}

		@Test
		@DisplayName("a prefix query reaching no end answers from level 1 alone")
		void shouldAnswerPrefixQueryWithoutEndsFromLevelOne() {
			final RangeIndex index = withOpenStartRanges();
			assertEquals(
				RANGES - 1, nonEmptyLevels(pointAt(index, Long.MIN_VALUE).getRepeatedStarts()),
				"precondition: every range but the first adds a level at the MIN sentinel"
			);

			for (final Formula formula : List.of(
				index.getRecordsTo(50L),
				index.getRecordsEnvelopingInclusive(50L),
				index.getRecordsWithRangesOverlapping(10L, 50L)
			)) {
				assertInstanceOf(
					ConstantFormula.class, formula,
					"nothing ends in the prefix, so the union of the level-1 starts is the answer: " + formula
				);
				assertArrayEquals(JUST_RECORD, formula.compute().getArray());
			}
		}

		@Test
		@DisplayName("a suffix query reaching no start answers from level 1 alone")
		void shouldAnswerSuffixQueryWithoutStartsFromLevelOne() {
			final RangeIndex index = withOpenEndRanges();
			assertEquals(
				RANGES - 1, nonEmptyLevels(pointAt(index, Long.MAX_VALUE).getRepeatedEnds()),
				"precondition: every range but the first adds a level at the MAX sentinel"
			);

			final Formula formula = index.getRecordsFrom(RANGES + 10L);
			assertInstanceOf(
				ConstantFormula.class, formula,
				"nothing starts in the suffix, so the union of the level-1 ends is the answer: " + formula
			);
			assertArrayEquals(JUST_RECORD, formula.compute().getArray());
		}

		@Test
		@DisplayName("a prefix query that cancels still counts every repeated level")
		void shouldCountEveryRepeatedLevelWhenPrefixQueryCancels() {
			final RangeIndex index = withOpenStartRanges();

			final Formula running = index.getRecordsTo(100L + RANGES / 2);
			assertInstanceOf(RangeCountFormula.class, running, "ends in the prefix need the counted levels");
			assertArrayEquals(JUST_RECORD, running.compute().getArray(), "the later half of the ranges still runs");
			assertArrayEquals(NOTHING, index.getRecordsTo(100L + RANGES).compute().getArray(), "every range is over");
		}

		@Test
		@DisplayName("a suffix query that cancels still counts every repeated level")
		void shouldCountEveryRepeatedLevelWhenSuffixQueryCancels() {
			final RangeIndex index = withOpenEndRanges();

			final Formula running = index.getRecordsFrom(RANGES / 2);
			assertInstanceOf(RangeCountFormula.class, running, "starts in the suffix need the counted levels");
			assertArrayEquals(JUST_RECORD, running.compute().getArray(), "the earlier half of the ranges runs");
			assertArrayEquals(NOTHING, index.getRecordsFrom(1L).compute().getArray(), "nothing started before 1");
		}
	}

	@Nested
	@DisplayName("Paged persistence")
	class Paging {
		/**
		 * Spacing of the thresholds of the background records, wide enough to insert hundreds of thresholds between
		 * two of them.
		 */
		private static final long SPACING = 10_000L;
		/**
		 * The threshold whose start the record holds twice - the start of background record 200.
		 */
		private static final long SHARED = 200 * SPACING;

		/**
		 * Builds an index of `count` background records `[i * SPACING, i * SPACING + SPACING / 2]` - enough points to
		 * span several leaves.
		 *
		 * @param count the number of background records
		 * @return the index
		 */
		@Nonnull
		private static RangeIndex background(int count) {
			final RangeIndex index = new RangeIndex();
			for (int i = 0; i < count; i++) {
				index.addRecord(i * SPACING, i * SPACING + SPACING / 2, 100_000 + i);
			}
			return index;
		}

		/**
		 * Flushes the index the way the granular write path does: copies of the points of every page the flush
		 * re-emits replace what `disk` held for that page, and the pages a merge freed are removed. The copies keep the
		 * persisted state apart from the live points, which later writes keep mutating.
		 *
		 * @param index the index to flush
		 * @param disk  the persisted pages by page sequence, updated in place
		 * @return the emission of the flush
		 */
		@Nonnull
		private static PageEmission<RangeIndex.RangePage> flush(
			@Nonnull RangeIndex index, @Nonnull Map<Integer, TransactionalRangePoint[]> disk
		) {
			final PageEmission<RangeIndex.RangePage> emission = index.collectChangedPages();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				final TransactionalRangePoint[] copies = new TransactionalRangePoint[page.points().length];
				for (int i = 0; i < copies.length; i++) {
					copies[i] = page.points()[i].makeClone();
				}
				disk.put(page.pageSequence(), copies);
			}
			for (final int freed : emission.freedPageSequences()) {
				disk.remove(freed);
			}
			index.getPageStreamRegistry().publishStaged();
			return emission;
		}

		/**
		 * Reassembles an index from the persisted pages, the way the load path does.
		 *
		 * @param emission the emission of the last flush, naming the live pages in order
		 * @param disk     the persisted pages by page sequence
		 * @return the reloaded index
		 */
		@Nonnull
		private static RangeIndex reload(
			@Nonnull PageEmission<RangeIndex.RangePage> emission, @Nonnull Map<Integer, TransactionalRangePoint[]> disk
		) {
			final int[] ordered = emission.orderedPageSequences();
			final TransactionalRangePoint[][] perPagePoints = new TransactionalRangePoint[ordered.length][];
			for (int i = 0; i < ordered.length; i++) {
				perPagePoints[i] = disk.get(ordered[i]);
				assertNotNull(perPagePoints[i], "live page " + ordered[i] + " was never written");
			}
			return RangeIndex.fromPersistedPages(
				"attribute `span`", ordered, perPagePoints, emission.highWaterPageSequence()
			);
		}

		@Test
		@DisplayName("a point with a repeated level survives leaf splits, leaf merges and a reload from its pages")
		void shouldKeepLevelsThroughSplitMergeAndReload() {
			final Map<Integer, TransactionalRangePoint[]> disk = new HashMap<>();
			final RangeIndex index = background(800);
			index.addRecord(SHARED, SHARED + 2, RECORD);
			index.addRecord(SHARED, SHARED + 3, RECORD);
			assertTrue(index.isPaged(), "precondition: the index spans several leaves");
			final int pagesBefore = flush(index, disk).orderedPageSequences().length;

			// split the leaf holding the shared point by crowding hundreds of thresholds right after it
			for (int i = 0; i < 700; i++) {
				index.addRecord(SHARED + 10 + i, SHARED + 10 + i, 200_000 + i);
			}
			final PageEmission<RangeIndex.RangePage> afterSplit = flush(index, disk);
			assertTrue(afterSplit.orderedPageSequences().length > pagesBefore, "precondition: a leaf split");
			assertEquals(1, pointAt(index, SHARED).getRepeatedStarts().length);
			assertArrayEquals(new int[]{RECORD, 100_200}, enveloping(index, SHARED + 3));
			assertEquals(levelsOf(index), levelsOf(reload(afterSplit, disk)));

			// merge them away again, growing one more level on the shared point on the way
			for (int i = 0; i < 700; i++) {
				index.removeRecord(SHARED + 10 + i, SHARED + 10 + i, 200_000 + i);
			}
			for (int i = 0; i < 300; i++) {
				index.removeRecord(i * SPACING, i * SPACING + SPACING / 2, 100_000 + i);
			}
			index.addRecord(SHARED, SHARED + 4, RECORD);
			final PageEmission<RangeIndex.RangePage> afterMerge = flush(index, disk);
			assertTrue(afterMerge.freedPageSequences().length > 0, "precondition: leaves merged away");
			assertEquals(2, pointAt(index, SHARED).getRepeatedStarts().length);

			final RangeIndex reloaded = reload(afterMerge, disk);
			assertEquals(index, reloaded, "the reloaded index holds every occurrence level");
			assertEquals(levelsOf(index), levelsOf(reloaded));
			assertArrayEquals(JUST_RECORD, enveloping(reloaded, SHARED + 4));
			assertArrayEquals(NOTHING, enveloping(reloaded, SHARED + 5));

			// the reloaded index keeps counting: dropping the longest range leaves the other two
			reloaded.removeRecord(SHARED, SHARED + 4, RECORD);
			assertArrayEquals(JUST_RECORD, enveloping(reloaded, SHARED + 3));
			assertArrayEquals(NOTHING, enveloping(reloaded, SHARED + 4));
		}
	}

	@Nested
	@DisplayName("Range-histogram sweep")
	@Tag(HISTOGRAM)
	class HistogramSweep {

		/**
		 * Builds an owner filter index over an integer range attribute - the filter index of an expression histogram,
		 * whose record is the owner and whose ranges come one per contribution.
		 *
		 * @return the index
		 */
		@Nonnull
		private static OwnerFilterIndex histogramFilter() {
			return new OwnerFilterIndex(new AttributeIndexKey(null, "span", null), IntegerNumberRange.class);
		}

		/**
		 * Renders the sweep's buckets as `value=[records]`.
		 *
		 * @param index the index to sweep
		 * @return one entry per bucket
		 */
		@Nonnull
		private static List<String> buckets(@Nonnull OwnerFilterIndex index) {
			final List<String> result = new ArrayList<>();
			for (final ValueToRecord bucket : index.getRangeHistogramOfAllRecords(Integer.class, 0).getBuckets()) {
				result.add(bucket.getValue() + "=" + Arrays.toString(bucket.getRecordIds().getArray()));
			}
			return result;
		}

		@Test
		@DisplayName("a record holding two overlapping ranges stays in every bucket until its last range ends")
		void shouldKeepRecordUntilItsLastOverlappingRangeEnds() {
			final OwnerFilterIndex index = histogramFilter();
			index.addRecord(RECORD, IntegerNumberRange.between(0, 20));
			index.addRecord(RECORD, IntegerNumberRange.between(10, 30));
			index.addRecord(2, IntegerNumberRange.between(5, 15));

			assertEquals(
				List.of("0=[1]", "5=[1, 2]", "10=[1, 2]", "15=[1, 2]", "20=[1]", "30=[1]"),
				buckets(index)
			);
		}

		@Test
		@DisplayName("a record holding two ranges with a shared start or end is counted once per range")
		void shouldCountRangesSharingBordersInSweep() {
			final OwnerFilterIndex sharedStart = histogramFilter();
			sharedStart.addRecord(RECORD, IntegerNumberRange.between(0, 20));
			sharedStart.addRecord(RECORD, IntegerNumberRange.between(0, 30));
			assertEquals(List.of("0=[1]", "20=[1]", "30=[1]"), buckets(sharedStart));

			final OwnerFilterIndex sharedEnd = histogramFilter();
			sharedEnd.addRecord(RECORD, IntegerNumberRange.between(0, 30));
			sharedEnd.addRecord(RECORD, IntegerNumberRange.between(10, 30));
			sharedEnd.addRecord(2, IntegerNumberRange.between(40, 50));
			assertEquals(List.of("0=[1]", "10=[1]", "30=[1]", "40=[2]", "50=[2]"), buckets(sharedEnd));

			sharedEnd.removeRecord(RECORD, IntegerNumberRange.between(0, 30));
			assertEquals(List.of("10=[1]", "30=[1]", "40=[2]", "50=[2]"), buckets(sharedEnd));
		}
	}

}
