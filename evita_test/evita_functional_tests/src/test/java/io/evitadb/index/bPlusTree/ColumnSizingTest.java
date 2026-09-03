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

package io.evitadb.index.bPlusTree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.TransactionalBitmap;

import javax.annotation.Nonnull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.SIZING_CAPACITY;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertRecordColumnSizing;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertValueColumnSizing;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the sizing contract every {@link ValueColumn} and {@link RecordColumn} implementation shares: a
 * **logical** {@code capacity()} that is fixed at construction, over a **physical** backing array that follows the
 * live content — empty until the first write, doubling on growth, and shrunk by {@code trimmed()} only once enough
 * slack has accumulated to pay for the copy.
 *
 * # Why one class for all seven implementations
 *
 * The contract is the reason a leaf holding four values stops paying for a whole 256-slot block, and the reason a
 * leaf with a short backing array still refuses to split. Both halves of it are properties of the *family*: a single
 * implementation answering `capacity()` with its array length would silently turn a five-value tree into a paged one,
 * and a single implementation forgetting to grow its destination would corrupt a rebalance. Driving all seven through
 * one battery ({@code ValueColumnTestSupport}) is what makes a divergence a failure rather than a surprise.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("B+ tree column logical capacity over content-sized backing")
class ColumnSizingTest {

	/**
	 * Builds a deterministic ascending {@link UUID} for the given ordinal, used as the boxed column's key. The high
	 * half carries the ordinal so natural {@link UUID} order matches ordinal order.
	 *
	 * @param ordinal the ordinal to derive the key from
	 * @return an ascending, deterministic UUID
	 */
	private static UUID uuid(int ordinal) {
		return new UUID(ordinal, 0L);
	}

	/**
	 * Builds a deterministic ascending {@link DateTimeRange} for the given ordinal, used as the range column's key.
	 * The offset varies with the ordinal so the column's `meta` array is populated rather than uniform — but **this
	 * battery cannot see the offsets at all**: it asserts `keyAt` equality, and `DateTimeRange`'s `equals` /
	 * `compareTo` are generated from the two comparison longs alone, so a range rebuilt at the wrong offset still
	 * compares equal. The offsets themselves are pinned by the offset-level assertions in
	 * {@code RangeValueColumnTest}.
	 *
	 * @param ordinal the ordinal to derive the key from
	 * @return an ascending, deterministic date-time range
	 */
	@Nonnull
	private static DateTimeRange dateTimeRange(int ordinal) {
		final ZoneOffset offset = ZoneOffset.ofTotalSeconds((ordinal % 5 - 2) * 1800);
		final LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0).plusDays(ordinal);
		return DateTimeRange.between(from.atOffset(offset), from.plusDays(1).atOffset(offset));
	}

	/**
	 * The leaf block size the arithmetic assertions use — the production value, so the numbers below read as the
	 * ones a real inverted index actually takes.
	 */
	private static final int BLOCK = 256;

	@Nested
	@DisplayName("every value column obeys the shared grow / trim / size contract")
	class ValueColumns {

		@Test
		void shouldObeyTheSizingContractWhenBackedByAnIntArray() {
			assertValueColumnSizing(capacity -> new IntValueColumn<Integer>(capacity), ordinal -> ordinal, true);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByALongArray() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			assertValueColumnSizing(
				capacity -> new LongValueColumn<Integer>(codec, capacity), ordinal -> ordinal, true
			);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByTwoParallelArrays() {
			assertValueColumnSizing(
				capacity -> new InstantValueColumn<Instant>(capacity),
				ordinal -> Instant.ofEpochSecond(ordinal, ordinal),
				true
			);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByABoxedArray() {
			assertValueColumnSizing(
				capacity -> new BoxedObjectColumn<UUID>(UUID.class, capacity), ColumnSizingTest::uuid, true
			);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByRangeBoundArrays() {
			// the range column has TWO shapes and both have to obey the contract: a `DateTimeRange` column, which
			// materializes the third `meta` array and therefore grows / trims / duplicates three arrays in lockstep,
			// and a numeric one, whose `meta` stays parked on the shared empty constant for the column's whole life
			assertValueColumnSizing(
				capacity -> new RangeValueColumn<DateTimeRange>(RangeKind.DATE_TIME, 0, capacity),
				ColumnSizingTest::dateTimeRange,
				true
			);
			// `NumberRange<Integer>` rather than `IntegerNumberRange`: the numeric hierarchy declares
			// `Comparable<NumberRange<T>>` on the abstract class, so only the parameterized supertype satisfies the
			// column's `M extends Comparable<M>` bound. `DateTimeRange` is `Comparable` of itself and needs no care
			assertValueColumnSizing(
				capacity -> new RangeValueColumn<NumberRange<Integer>>(RangeKind.INTEGER_NUMBER, 0, capacity),
				ordinal -> IntegerNumberRange.between(ordinal, ordinal + 1),
				true
			);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByAFrontCodedBlob() {
			// the front-coded column has no per-slot storage, so `trimmed()` has nothing to reclaim - the battery
			// asserts the identity return instead of a shrink
			assertValueColumnSizing(
				capacity -> new FrontCodedStringColumn<String>(capacity, true),
				ordinal -> String.format("key-%04d", ordinal),
				false
			);
		}
	}

	@Nested
	@DisplayName("every record column obeys the shared grow / trim / size contract")
	class RecordColumns {

		@Test
		void shouldObeyTheSizingContractWhenBackedByAnIntArray() {
			assertRecordColumnSizing(IntRecordColumn::new);
		}

		@Test
		void shouldObeyTheSizingContractWhenBackedByALongArray() {
			assertRecordColumnSizing(LongRecordColumn::new);
		}
	}

	@Nested
	@DisplayName("trimming waits for enough slack and does not oscillate")
	class TrimHysteresis {

		@Test
		@DisplayName("a column filling three quarters of its backing keeps it")
		void shouldNotTrimWhenSlackIsSmall() {
			// eight inserts land the backing on exactly eight slots; five live keys are far too many to pay for a copy
			final ValueColumn<Integer> column = new IntValueColumn<>(SIZING_CAPACITY);
			for (int i = 0; i < 8; i++) {
				column.insertKeyAt(i, i);
			}
			column.fillEmpty(5, SIZING_CAPACITY);
			assertEquals(5, column.size());
			assertSame(column, column.trimmed(), "five keys in eight slots is not worth a copy");
		}

		@Test
		@DisplayName("a column down to a quarter of its backing shrinks, and the shrunk column stays put")
		void shouldTrimOnceAndThenHoldStill() {
			final ValueColumn<Integer> column = new IntValueColumn<>(SIZING_CAPACITY);
			for (int i = 0; i < 8; i++) {
				column.insertKeyAt(i, i);
			}
			column.fillEmpty(2, SIZING_CAPACITY);

			final ValueColumn<Integer> trimmed = column.trimmed();
			assertNotSame(column, trimmed, "two keys in eight slots must shrink");
			assertEquals(2, trimmed.size());
			assertEquals(SIZING_CAPACITY, trimmed.capacity());
			assertEquals(Integer.valueOf(0), trimmed.keyAt(0));
			assertEquals(Integer.valueOf(1), trimmed.keyAt(1));
			assertTrue(trimmed.getHeapSizeInBytes() < column.getHeapSizeInBytes());

			// the 4:1 gap is the hysteresis: the shrunk column sits at the floor of four slots and must not trim
			// again, or a leaf hovering around a power of two would alternate grow and trim on every commit
			assertSame(trimmed, trimmed.trimmed(), "a column already at the floor must not trim again");

			// and re-growing it back through the floor does not immediately want another trim either
			trimmed.insertKeyAt(2, 2);
			trimmed.insertKeyAt(3, 3);
			trimmed.insertKeyAt(4, 4);
			assertEquals(5, trimmed.size());
			assertSame(trimmed, trimmed.trimmed(), "a column that just grew has no slack to give back");
		}

		@Test
		@DisplayName("a trim never lowers the logical capacity a leaf splits on")
		void shouldKeepTheLogicalCapacityAcrossATrim() {
			final RecordColumn column = new IntRecordColumn(SIZING_CAPACITY);
			for (int i = 0; i < SIZING_CAPACITY; i++) {
				column.insertAt(i, i + 1L);
			}
			column.fillEmpty(1, SIZING_CAPACITY);

			final RecordColumn trimmed = column.trimmed();
			assertEquals(SIZING_CAPACITY, trimmed.capacity(), "the split threshold must survive the shrink");
			assertEquals(1, trimmed.size());
		}
	}

	@Nested
	@DisplayName("the shared sizing arithmetic, at its edges")
	class Arithmetic {

		@Test
		@DisplayName("rounding up to a power of two")
		void shouldRoundUpToThePowerOfTwo() {
			assertEquals(1, ColumnSizing.nextPowerOfTwo(0), "zero rounds up to one, not to zero");
			assertEquals(1, ColumnSizing.nextPowerOfTwo(1));
			assertEquals(2, ColumnSizing.nextPowerOfTwo(2), "an exact power of two is left alone");
			assertEquals(4, ColumnSizing.nextPowerOfTwo(3));
			assertEquals(4, ColumnSizing.nextPowerOfTwo(4));
			assertEquals(8, ColumnSizing.nextPowerOfTwo(5));
			assertEquals(256, ColumnSizing.nextPowerOfTwo(255));
			assertEquals(ColumnSizing.MAX_POWER_OF_TWO, ColumnSizing.nextPowerOfTwo(ColumnSizing.MAX_POWER_OF_TWO));
		}

		@Test
		@DisplayName("rounding refuses an argument whose answer would not fit an int")
		void shouldRefuseToRoundUpBeyondTheIntRange() {
			// the two call sites are bounded far below this by the leaf block size, but the method is visible to the
			// package and silently returning a negative length is the worst possible answer
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ColumnSizing.nextPowerOfTwo(ColumnSizing.MAX_POWER_OF_TWO + 1)
			);
			assertThrows(GenericEvitaInternalError.class, () -> ColumnSizing.nextPowerOfTwo(-1));
		}

		@Test
		@DisplayName("growth starts at the floor of four and then doubles")
		void shouldGrowFromNothingToTheFloorAndThenDouble() {
			assertEquals(4, ColumnSizing.grownLength(0, 1, BLOCK), "the first write allocates the floor");
			assertEquals(4, ColumnSizing.grownLength(0, 4, BLOCK));
			assertEquals(8, ColumnSizing.grownLength(4, 5, BLOCK));
			assertEquals(16, ColumnSizing.grownLength(8, 9, BLOCK));
			assertEquals(128, ColumnSizing.grownLength(64, 65, BLOCK));
		}

		@Test
		@DisplayName("growth past half the block goes straight to the block")
		void shouldGrowStraightToTheCapacityPastHalfTheBlock() {
			assertEquals(BLOCK, ColumnSizing.grownLength(128, 129, BLOCK), "doubling would overshoot the cap anyway");
			assertEquals(BLOCK, ColumnSizing.grownLength(0, BLOCK, BLOCK));
			assertEquals(128, ColumnSizing.grownLength(64, 128, BLOCK), "exactly half still doubles");
		}

		@Test
		@DisplayName("growth respects a block smaller than the floor of four")
		void shouldNeverGrowBeyondATinyCapacity() {
			// the tree validates only `blockSize >= 3`, and its own tests run at three and four
			assertEquals(3, ColumnSizing.grownLength(0, 1, 3));
			assertEquals(3, ColumnSizing.grownLength(0, 2, 3));
			assertEquals(3, ColumnSizing.grownLength(0, 3, 3));
			assertEquals(4, ColumnSizing.grownLength(0, 1, 4));
			assertEquals(4, ColumnSizing.grownLength(0, 4, 4));
		}

		@Test
		@DisplayName("growth refuses to exceed the logical capacity")
		void shouldRefuseToGrowBeyondTheLogicalCapacity() {
			// a column asked for more slots than its block holds means the leaf failed to split; answering with a
			// bigger array would hide that rather than report it
			assertThrows(GenericEvitaInternalError.class, () -> ColumnSizing.grownLength(BLOCK, BLOCK + 1, BLOCK));
		}

		@Test
		@DisplayName("trimming holds until the slack reaches four to one")
		void shouldTrimOnlyOnceSlackReachesFourToOne() {
			assertEquals(BLOCK, ColumnSizing.trimmedLength(65, BLOCK, BLOCK), "one over a quarter is not enough");
			assertEquals(64, ColumnSizing.trimmedLength(64, BLOCK, BLOCK), "exactly a quarter trims");
			assertEquals(4, ColumnSizing.trimmedLength(1, BLOCK, BLOCK));
			assertEquals(8, ColumnSizing.trimmedLength(5, 64, BLOCK), "the target rounds up to a power of two");
		}

		@Test
		@DisplayName("trimming never moves a column that is already at or below the floor")
		void shouldLeaveAColumnAtTheFloorAlone() {
			assertEquals(0, ColumnSizing.trimmedLength(0, 0, BLOCK), "an empty column owns nothing to reclaim");
			assertEquals(4, ColumnSizing.trimmedLength(0, 4, BLOCK), "the floor is the smallest non-empty backing");
			assertEquals(4, ColumnSizing.trimmedLength(1, 4, BLOCK));
			assertEquals(3, ColumnSizing.trimmedLength(0, 3, 3), "a tiny block caps the floor at the block itself");
			assertEquals(
				3, ColumnSizing.trimmedLength(1, 3, 3),
				"a live entry in a block below the floor must not be 'trimmed' UP to the floor"
			);
		}

		@Test
		@DisplayName("a bulk load larger than the logical capacity is refused by every column family")
		void shouldRefuseABulkLoadLargerThanTheLogicalCapacity() {
			// the incremental path carries this premise inside `grownLength`; a bulk load sizes its array straight
			// to the count and never asks, so without the same premise it is the one way into the family that can
			// build a column whose live run runs past the block it belongs to
			final int tiny = 4;

			assertThrows(
				GenericEvitaInternalError.class,
				() -> new IntValueColumn<Integer>(tiny).bulkLoad(new Object[]{0, 1, 2, 3, 4, 5}, 6),
				"the int key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new LongValueColumn<Integer>(LongKeyCodec.forType(Integer.class), tiny)
					.bulkLoad(new Object[]{0, 1, 2, 3, 4, 5}, 6),
				"the long key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new BoxedObjectColumn<>(Integer.class, tiny).bulkLoad(new Object[]{0, 1, 2, 3, 4, 5}, 6),
				"the boxed key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new InstantValueColumn<Instant>(tiny).bulkLoad(instants(6), 6),
				"the temporal key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FrontCodedStringColumn<String>(tiny, true)
					.bulkLoad(new Object[]{"a", "b", "c", "d", "e", "f"}, 6),
				"the front-coded key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new RangeValueColumn<DateTimeRange>(RangeKind.DATE_TIME, 0, tiny).bulkLoad(dateTimeRanges(6), 6),
				"the date-time range key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new RangeValueColumn<NumberRange<Integer>>(RangeKind.INTEGER_NUMBER, 0, tiny)
					.bulkLoad(integerRanges(6), 6),
				"the numeric range key column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new IntRecordColumn(tiny).bulkLoad(new long[]{1, 2, 3, 4, 5, 6}, 6),
				"the int record column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new LongRecordColumn(tiny).bulkLoad(new long[]{1, 2, 3, 4, 5, 6}, 6),
				"the long record column absorbed a load larger than its block"
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new OverflowColumn(tiny).bulkLoad(new TransactionalBitmap[6], 6),
				"the overflow column absorbed a load larger than its block"
			);

			// a load that exactly fills the block is still served
			final RecordColumn records = new IntRecordColumn(tiny);
			records.bulkLoad(new long[]{1, 2, 3, 4}, tiny);
			assertEquals(tiny, records.size());
			assertEquals(tiny, records.capacity());
		}

		/**
		 * Builds an ascending run of instants for the temporal column's bulk load.
		 *
		 * @param count the number of instants to build
		 * @return the instants, ascending
		 */
		@Nonnull
		private Object[] instants(int count) {
			final Object[] result = new Object[count];
			for (int i = 0; i < count; i++) {
				result[i] = Instant.ofEpochSecond(i);
			}
			return result;
		}

		/**
		 * Builds an ascending run of date-time ranges for the range column's bulk load.
		 *
		 * @param count the number of ranges to build
		 * @return the ranges, ascending
		 */
		@Nonnull
		private Object[] dateTimeRanges(int count) {
			final Object[] result = new Object[count];
			for (int i = 0; i < count; i++) {
				result[i] = dateTimeRange(i);
			}
			return result;
		}

		/**
		 * Builds an ascending run of integer ranges for the range column's bulk load.
		 *
		 * @param count the number of ranges to build
		 * @return the ranges, ascending
		 */
		@Nonnull
		private Object[] integerRanges(int count) {
			final Object[] result = new Object[count];
			for (int i = 0; i < count; i++) {
				result[i] = IntegerNumberRange.between(i, i + 1);
			}
			return result;
		}
	}
}
