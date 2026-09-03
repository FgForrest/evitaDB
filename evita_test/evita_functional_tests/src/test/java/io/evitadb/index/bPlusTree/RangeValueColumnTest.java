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

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertTreeMatchesOracle;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.verifyConsistent;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the range value column: that every one of the six concrete {@link Range} subtypes round-trips through the
 * two comparison longs (plus, for {@link DateTimeRange}, the packed zone offsets), that the declared subtype is
 * reproduced exactly, that {@link ValueColumnFactory} selects the column only where it may, and — the binding
 * requirement — that {@code Range.consolidateRange} lands on the same thresholds over reconstructed ranges as it does
 * over the originals.
 *
 * **The consolidation invariant is the point of the class, not equality.** The write path reads a record's existing
 * ranges back out of the tree, consolidates them, and asks the range index to drop exactly the thresholds an earlier
 * consolidation of the ORIGINAL objects inserted. A reconstruction that is merely {@code equals} to the original can
 * still consolidate to a different threshold — which is silent range-index corruption with no exception anywhere —
 * so the invariant is pinned here directly, including the concrete counterexample a single-offset encoding fails.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range value column")
@Tag(INDEXING)
@Tag(DATA_TYPE)
class RangeValueColumnTest {

	private static final int BLOCK_SIZE = 8;

	/**
	 * Builds an empty range column of the given kind at {@link #BLOCK_SIZE}.
	 *
	 * @param kind                 the range subtype the column stores
	 * @param indexedDecimalPlaces the scale `BigDecimalNumberRange` bounds are encoded at
	 * @return the fresh column
	 */
	@Nonnull
	private static <M extends Comparable<M>> ValueColumn<M> columnOf(
		@Nonnull RangeKind kind, int indexedDecimalPlaces
	) {
		return new RangeValueColumn<>(kind, indexedDecimalPlaces, BLOCK_SIZE);
	}

	/**
	 * Builds an empty boxed reference column for the given key class at {@link #BLOCK_SIZE}. The cast is unavoidable:
	 * {@code BoxedObjectColumn} takes a {@code Class<M>}, and the numeric range hierarchy declares
	 * {@code Comparable<NumberRange<T>>} on its abstract class — so the only type argument satisfying the column's
	 * {@code M extends Comparable<M>} bound is the parameterized supertype, which has no class literal.
	 *
	 * @param keyType the concrete key class the column materializes its boxed array from
	 * @return the fresh boxed column
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <M extends Comparable<M>> ValueColumn<M> boxedColumnOf(@Nonnull Class<?> keyType) {
		return (ValueColumn<M>) new BoxedObjectColumn(keyType, BLOCK_SIZE);
	}

	/**
	 * Loads the supplied ranges into a fresh column of the given kind, in the order given, and asserts each one reads
	 * back with the same comparison bounds and the same concrete class.
	 *
	 * @param kind                 the range subtype the column stores
	 * @param indexedDecimalPlaces the scale `BigDecimalNumberRange` bounds are encoded at
	 * @param ranges               the ranges to store, in ascending order
	 * @return the populated column, for further assertions
	 */
	@Nonnull
	private static <M extends Comparable<M>> ValueColumn<M> assertRoundTrip(
		@Nonnull RangeKind kind, int indexedDecimalPlaces, @Nonnull List<? extends Range<?>> ranges
	) {
		final ValueColumn<M> column = columnOf(kind, indexedDecimalPlaces);
		for (int i = 0; i < ranges.size(); i++) {
			//noinspection unchecked
			column.insertKeyAt(i, (M) ranges.get(i));
		}
		for (int i = 0; i < ranges.size(); i++) {
			final Range<?> original = ranges.get(i);
			final Range<?> decoded = (Range<?>) column.keyAt(i);
			assertEquals(original.getFrom(), decoded.getFrom(), "lower bound mismatch at slot " + i);
			assertEquals(original.getTo(), decoded.getTo(), "upper bound mismatch at slot " + i);
			// `ReevaluateExpressionExecutor` re-indexes raw bucket values and enforces `plainType.isInstance(value)`,
			// so a supertype here would fail there rather than at the column
			assertSame(
				original.getClass(), decoded.getClass(),
				"the declared concrete subtype must be reproduced at slot " + i
			);
			// equality across both hierarchies is generated from the two comparison longs alone
			assertEquals(original, decoded, "the reconstruction must be equal to the original at slot " + i);
		}
		return column;
	}

	/**
	 * Builds a deterministic ascending {@link DateTimeRange} whose two bounds carry **different** zone offsets, both
	 * varying with the ordinal.
	 *
	 * The two offsets differ from slot to slot and from each other, so the two comparison longs a slot holds are not
	 * derivable from one another and a lockstep failure that carried one array's slot without the other's reads back
	 * as a different range.
	 *
	 * @param ordinal the ordinal to derive the range from
	 * @return an ascending, deterministic date-time range
	 */
	@Nonnull
	private static DateTimeRange offsetBearingRange(int ordinal) {
		final ZoneOffset fromOffset = ZoneOffset.ofTotalSeconds(ordinal * 1800 - 7200);
		final ZoneOffset toOffset = ZoneOffset.ofTotalSeconds(ordinal * 1800 - 6300);
		final LocalDateTime moment = LocalDateTime.of(2024, 1, 1, 0, 0).plusDays(ordinal);
		return DateTimeRange.between(moment.atOffset(fromOffset), moment.plusDays(1).atOffset(toOffset));
	}

	/**
	 * Re-renders a range with every closed bound moved to UTC, naming the very same instants. This is the form the
	 * column rebuilds a key in — its two comparison longs identify instants and carry no offset — so it is the
	 * oracle a reconstruction's rendering is checked against.
	 *
	 * @param range the range to re-render
	 * @return the same range with each closed bound expressed at UTC
	 */
	@Nonnull
	private static DateTimeRange atUtc(@Nonnull DateTimeRange range) {
		final OffsetDateTime preciseFrom = range.getPreciseFrom();
		final OffsetDateTime preciseTo = range.getPreciseTo();
		if (preciseFrom == null) {
			return DateTimeRange.until(preciseTo.withOffsetSameInstant(ZoneOffset.UTC));
		} else if (preciseTo == null) {
			return DateTimeRange.since(preciseFrom.withOffsetSameInstant(ZoneOffset.UTC));
		} else {
			return DateTimeRange.between(
				preciseFrom.withOffsetSameInstant(ZoneOffset.UTC),
				preciseTo.withOffsetSameInstant(ZoneOffset.UTC)
			);
		}
	}

	/**
	 * Asserts a slot decodes to the expected range: the same two instants — read off the rebuilt bound objects,
	 * which equality never looks at — both rendered at UTC, which is the reconstruction contract.
	 *
	 * @param column   the column to read
	 * @param index    the slot to read
	 * @param expected the range the slot must hold
	 * @param where    what had just been done to the column, for the failure message
	 */
	private static void assertRebuiltBoundsAt(
		@Nonnull ValueColumn<DateTimeRange> column, int index, @Nonnull DateTimeRange expected, @Nonnull String where
	) {
		final DateTimeRange decoded = column.keyAt(index);
		assertEquals(expected, decoded, "slot " + index + " must hold the expected range (" + where + ")");
		assertEquals(
			expected.getPreciseFrom().toInstant(), decoded.getPreciseFrom().toInstant(),
			"from-bound instant moved at slot " + index + " (" + where + ")"
		);
		assertEquals(
			expected.getPreciseTo().toInstant(), decoded.getPreciseTo().toInstant(),
			"to-bound instant moved at slot " + index + " (" + where + ")"
		);
		assertEquals(
			ZoneOffset.UTC, decoded.getPreciseFrom().getOffset(),
			"a rebuilt from-bound is always at UTC at slot " + index + " (" + where + ")"
		);
		assertEquals(
			ZoneOffset.UTC, decoded.getPreciseTo().getOffset(),
			"a rebuilt to-bound is always at UTC at slot " + index + " (" + where + ")"
		);
	}

	/**
	 * Round-trips every subtype through every bound shape, and the zone-offset spread the date-time kind needs.
	 */
	@Nested
	@DisplayName("round-trip of all six subtypes")
	class RoundTripTest {

		@Test
		@DisplayName("byte ranges round-trip closed, open-from and open-to")
		void shouldRoundTripByteRanges() {
			assertRoundTrip(
				RangeKind.BYTE_NUMBER, 0,
				List.of(
					ByteNumberRange.to((byte) -5),
					ByteNumberRange.to(Byte.MAX_VALUE),
					ByteNumberRange.from(Byte.MIN_VALUE),
					ByteNumberRange.between((byte) -3, (byte) 7),
					ByteNumberRange.between((byte) 0, (byte) 0),
					ByteNumberRange.from((byte) 4)
				)
			);
		}

		@Test
		@DisplayName("short ranges round-trip closed, open-from and open-to")
		void shouldRoundTripShortRanges() {
			assertRoundTrip(
				RangeKind.SHORT_NUMBER, 0,
				List.of(
					ShortNumberRange.to((short) -1_000),
					ShortNumberRange.between((short) -1, (short) 1),
					ShortNumberRange.from((short) 12_345),
					ShortNumberRange.from(Short.MAX_VALUE)
				)
			);
		}

		@Test
		@DisplayName("integer ranges round-trip closed, open-from and open-to")
		void shouldRoundTripIntegerRanges() {
			assertRoundTrip(
				RangeKind.INTEGER_NUMBER, 0,
				List.of(
					IntegerNumberRange.to(Integer.MIN_VALUE),
					IntegerNumberRange.to(-17),
					IntegerNumberRange.between(-4, 4),
					IntegerNumberRange.between(0, Integer.MAX_VALUE),
					IntegerNumberRange.from(11),
					IntegerNumberRange.from(Integer.MAX_VALUE)
				)
			);
		}

		@Test
		@DisplayName("long ranges round-trip closed, open-from and open-to")
		void shouldRoundTripLongRanges() {
			assertRoundTrip(
				RangeKind.LONG_NUMBER, 0,
				List.of(
					LongNumberRange.to(-9_000_000_000L),
					LongNumberRange.between(-1L, 1L),
					LongNumberRange.from(9_000_000_000L)
				)
			);
		}

		@Test
		@DisplayName("an explicit Long.MIN_VALUE bound is indistinguishable from an open one, and behaves the same")
		void shouldTreatAnExplicitLongMinimumAsAnOpenBound() {
			// the one documented ambiguity of the family: both forms encode to the same long. The substitution is
			// invisible to the engine, which decides everything on getFrom()/getTo() and re-derives them from the
			// precise bounds through cloneWithDifferentBounds - so the BEHAVIOUR is what has to match, not the object
			final LongNumberRange explicit = LongNumberRange.between(Long.MIN_VALUE, 42L);
			final ValueColumn<NumberRange<Long>> column = columnOf(RangeKind.LONG_NUMBER, 0);
			column.insertKeyAt(0, explicit);
			final NumberRange<Long> decoded = column.keyAt(0);

			assertEquals(explicit, decoded, "the two forms are equal by their comparison longs");
			assertEquals(Long.MIN_VALUE, decoded.getFrom());
			assertEquals(42L, decoded.getTo());
			// the precise bound really did change form - the assertion below is what makes that a documented
			// substitution rather than an undetected one
			assertEquals(Long.valueOf(Long.MIN_VALUE), explicit.getPreciseFrom());
			assertNull(decoded.getPreciseFrom(), "the explicit minimum reads back as an open bound");

			// and re-encoding either form through cloneWithDifferentBounds lands on the same long pair
			final Range<Long> reclonedOriginal = explicit.cloneWithDifferentBounds(
				explicit.getPreciseFrom(), explicit.getPreciseTo());
			final Range<Long> reclonedDecoded = decoded.cloneWithDifferentBounds(
				decoded.getPreciseFrom(), decoded.getPreciseTo());
			assertEquals(reclonedOriginal.getFrom(), reclonedDecoded.getFrom());
			assertEquals(reclonedOriginal.getTo(), reclonedDecoded.getTo());
		}

		@Test
		@DisplayName("an explicit Long.MAX_VALUE bound is indistinguishable from an open one, and behaves the same")
		void shouldTreatAnExplicitLongMaximumAsAnOpenBound() {
			// the symmetric half of the ambiguity above, on the upper bound and against the other sentinel - the two
			// bounds read their sentinel from opposite ends of the long range and neither substitution implies the
			// other
			final LongNumberRange explicit = LongNumberRange.between(-42L, Long.MAX_VALUE);
			final ValueColumn<NumberRange<Long>> column = columnOf(RangeKind.LONG_NUMBER, 0);
			column.insertKeyAt(0, explicit);
			final NumberRange<Long> decoded = column.keyAt(0);

			assertEquals(explicit, decoded, "the two forms are equal by their comparison longs");
			assertEquals(-42L, decoded.getFrom());
			assertEquals(Long.MAX_VALUE, decoded.getTo());
			assertEquals(Long.valueOf(Long.MAX_VALUE), explicit.getPreciseTo());
			assertNull(decoded.getPreciseTo(), "the explicit maximum reads back as an open bound");

			// and re-encoding either form through cloneWithDifferentBounds lands on the same long pair
			final Range<Long> reclonedOriginal = explicit.cloneWithDifferentBounds(
				explicit.getPreciseFrom(), explicit.getPreciseTo());
			final Range<Long> reclonedDecoded = decoded.cloneWithDifferentBounds(
				decoded.getPreciseFrom(), decoded.getPreciseTo());
			assertEquals(reclonedOriginal.getFrom(), reclonedDecoded.getFrom());
			assertEquals(reclonedOriginal.getTo(), reclonedDecoded.getTo());
		}

		@Test
		@DisplayName("a long range saturating both sentinels rebuilds with both precise bounds materialized")
		void shouldRebuildASaturatedLongRange() {
			// three ordinary constructions store the saturated pair, and the two open-ended ones are the plausible
			// shapes - "open from the smallest representable value" is a legal thing for a caller to write
			final LongNumberRange[] saturated = {
				LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE),
				LongNumberRange.from(Long.MIN_VALUE),
				LongNumberRange.to(Long.MAX_VALUE)
			};
			for (final LongNumberRange original : saturated) {
				assertEquals(Long.MIN_VALUE, original.getFrom(), "the fixture must store the saturated pair");
				assertEquals(Long.MAX_VALUE, original.getTo(), "the fixture must store the saturated pair");

				final ValueColumn<NumberRange<Long>> column = columnOf(RangeKind.LONG_NUMBER, 0);
				column.insertKeyAt(0, original);
				final NumberRange<Long> decoded = column.keyAt(0);
				assertEquals(original, decoded, "equality is generated from the two comparison longs alone");
				assertEquals(Long.MIN_VALUE, decoded.getFrom());
				assertEquals(Long.MAX_VALUE, decoded.getTo());

				// reading each sentinel independently would mint a range with BOTH precise bounds null - a shape
				// `LongNumberRange` itself refuses - so the saturated pair is the one decoded with both bounds
				// materialized. They re-encode to the very same two longs, so nothing observable moves
				assertEquals(Long.valueOf(Long.MIN_VALUE), decoded.getPreciseFrom());
				assertEquals(Long.valueOf(Long.MAX_VALUE), decoded.getPreciseTo());
			}
		}

		@Test
		@DisplayName("big decimal ranges round-trip at the index scale, including a scale-mismatched input")
		void shouldRoundTripBigDecimalRangesAtTheIndexScale() {
			// the normalizer rewrites every key to the schema scale before it reaches the tree, so a range built at a
			// DIFFERENT intrinsic scale still has to round-trip to the same longs once encoded at the index scale
			final int indexedDecimalPlaces = 2;
			final BigDecimalNumberRange mismatched = BigDecimalNumberRange.between(
				new BigDecimal("1.005"), new BigDecimal("9.5"), indexedDecimalPlaces
			);
			assertRoundTrip(
				RangeKind.BIG_DECIMAL_NUMBER, indexedDecimalPlaces,
				List.of(
					BigDecimalNumberRange.to(new BigDecimal("-3.25"), indexedDecimalPlaces),
					mismatched,
					BigDecimalNumberRange.between(
						new BigDecimal("10.00"), new BigDecimal("20.00"), indexedDecimalPlaces),
					BigDecimalNumberRange.from(new BigDecimal("99.99"), indexedDecimalPlaces)
				)
			);
		}

		@Test
		@DisplayName("the INFINITE big decimal range comes back as the very same instance")
		void shouldReturnTheSharedInfiniteInstance() {
			// `union` / `intersect` compare against INFINITE by reference, and the tree really does hold the constant
			// itself today - `FilterIndex`'s rescaling passes a fully-open range through unchanged - so rebuilding an
			// equal-but-distinct object here would be the only thing that broke that identity
			final ValueColumn<NumberRange<BigDecimal>> column = columnOf(RangeKind.BIG_DECIMAL_NUMBER, 2);
			column.insertKeyAt(0, BigDecimalNumberRange.INFINITE);
			assertSame(BigDecimalNumberRange.INFINITE, column.keyAt(0));
		}

		@Test
		@DisplayName("the rebuilt big decimal bounds carry the column's scale, which nothing comparing them sees")
		void shouldRebuildBigDecimalBoundsAtTheColumnScale() {
			// the column's scale is threaded into `BigDecimal.valueOf(long, scale)` AND into the rebuilt range's own
			// `retainedDecimalPlaces`, which makes the reconstruction self-consistent at ANY scale: the comparison
			// longs, equality, ordering and consolidation all agree whatever it is. Only the precise bounds move -
			// so they are the only thing that can pin the scale, and the guard below spells that out
			final BigDecimalNumberRange original =
				BigDecimalNumberRange.between(new BigDecimal("1.25"), new BigDecimal("9.50"), 2);
			final ValueColumn<NumberRange<BigDecimal>> atTwo = columnOf(RangeKind.BIG_DECIMAL_NUMBER, 2);
			atTwo.insertKeyAt(0, original);
			final NumberRange<BigDecimal> decodedAtTwo = atTwo.keyAt(0);
			// BigDecimal equality is scale-sensitive, so each assertion pins the value AND the scale it carries
			assertEquals(new BigDecimal("1.25"), decodedAtTwo.getPreciseFrom());
			assertEquals(new BigDecimal("9.50"), decodedAtTwo.getPreciseTo());

			// the non-vacuity guard: the SAME range stored in a column built at scale 0 rebuilds to bounds a hundred
			// times larger, and everything the engine compares still reads the two reconstructions as identical
			final ValueColumn<NumberRange<BigDecimal>> atZero = columnOf(RangeKind.BIG_DECIMAL_NUMBER, 0);
			atZero.insertKeyAt(0, original);
			final NumberRange<BigDecimal> decodedAtZero = atZero.keyAt(0);
			assertEquals(decodedAtTwo, decodedAtZero, "equality is generated from the two comparison longs alone");
			assertEquals(decodedAtTwo.getFrom(), decodedAtZero.getFrom());
			assertEquals(decodedAtTwo.getTo(), decodedAtZero.getTo());
			final Range<BigDecimal> reclonedAtTwo = decodedAtTwo.cloneWithDifferentBounds(
				decodedAtTwo.getPreciseFrom(), decodedAtTwo.getPreciseTo());
			final Range<BigDecimal> reclonedAtZero = decodedAtZero.cloneWithDifferentBounds(
				decodedAtZero.getPreciseFrom(), decodedAtZero.getPreciseTo());
			assertEquals(reclonedAtTwo.getFrom(), reclonedAtZero.getFrom(), "consolidation re-derives one long pair");
			assertEquals(reclonedAtTwo.getTo(), reclonedAtZero.getTo(), "consolidation re-derives one long pair");
			assertNotEquals(
				decodedAtTwo.getPreciseFrom(), decodedAtZero.getPreciseFrom(),
				"the precise bounds are all the scale moves - unasserted, a wrong scale passes the whole suite"
			);
		}

		@Test
		@DisplayName("date-time ranges round-trip across the whole zone-offset span, both bounds and both open shapes")
		void shouldRoundTripDateTimeRangesAcrossEveryOffset() {
			// ZoneOffset runs to +-18:00, not +14:00 / -12:00 - the extremes are what a bound reconstruction that
			// went through a fixed offset instead of the stored instant would land in the wrong place
			final ZoneOffset maxOffset = ZoneOffset.ofTotalSeconds(18 * 3600);
			final ZoneOffset minOffset = ZoneOffset.ofTotalSeconds(-18 * 3600);
			final ZoneOffset negativeHalfHour = ZoneOffset.ofTotalSeconds(-1800);
			final LocalDateTime moment = LocalDateTime.of(2024, 1, 10, 12, 30, 45);

			final List<DateTimeRange> ranges = new ArrayList<>(8);
			ranges.add(DateTimeRange.until(moment.atOffset(maxOffset)));
			ranges.add(DateTimeRange.until(moment.atOffset(minOffset)));
			ranges.add(DateTimeRange.since(moment.atOffset(negativeHalfHour)));
			ranges.add(DateTimeRange.between(moment.atOffset(maxOffset), moment.plusDays(5).atOffset(maxOffset)));
			ranges.add(DateTimeRange.between(moment.atOffset(minOffset), moment.plusDays(5).atOffset(minOffset)));
			// the two bounds carry DIFFERENT offsets - a scheme keeping only one of them cannot rebuild this
			ranges.add(
				DateTimeRange.between(moment.atOffset(negativeHalfHour), moment.plusDays(5).atOffset(maxOffset)));
			ranges.add(DateTimeRange.since(moment.atOffset(maxOffset)));
			ranges.add(DateTimeRange.since(moment.atOffset(minOffset)));
			ranges.sort(Comparator.naturalOrder());

			final ValueColumn<DateTimeRange> column = assertRoundTrip(RangeKind.DATE_TIME, 0, ranges);

			// which bound is open survives, and every closed bound names the very instant it was written with — the
			// offsets it was written AT are not stored, so a rebuilt bound is always at UTC
			for (int i = 0; i < ranges.size(); i++) {
				final DateTimeRange original = ranges.get(i);
				final DateTimeRange decoded = column.keyAt(i);
				assertEquals(
					original.getPreciseFrom() == null, decoded.getPreciseFrom() == null,
					"open-from shape mismatch at slot " + i
				);
				assertEquals(
					original.getPreciseTo() == null, decoded.getPreciseTo() == null,
					"open-to shape mismatch at slot " + i
				);
				if (original.getPreciseFrom() != null) {
					assertEquals(
						original.getPreciseFrom().toInstant(), decoded.getPreciseFrom().toInstant(),
						"from-bound instant mismatch at slot " + i
					);
					assertEquals(
						ZoneOffset.UTC, decoded.getPreciseFrom().getOffset(), "rebuilt at UTC at slot " + i
					);
				}
				if (original.getPreciseTo() != null) {
					assertEquals(
						original.getPreciseTo().toInstant(), decoded.getPreciseTo().toInstant(),
						"to-bound instant mismatch at slot " + i
					);
					assertEquals(
						ZoneOffset.UTC, decoded.getPreciseTo().getOffset(), "rebuilt at UTC at slot " + i
					);
				}
			}
		}

		@Test
		@DisplayName("a sub-millisecond bound is truncated and re-anchored at UTC, invisibly to every comparison")
		void shouldTruncateSubSecondBoundsInvisibly() {
			// `DateTimeRange` keeps its bounds as OffsetDateTime but derives both comparison longs as whole epoch
			// MILLISECONDS, and evitaDB does not truncate range bounds on input - so a rebuilt range carries a
			// truncated nanosecond field, and a UTC offset, where the original may have carried others. Every
			// comparison in the engine is decided by the long pair (equals / hashCode are generated from it,
			// compareTo is it, the range index stores it), so this is a change the write path cannot observe.
			// Pinned here so it stays that way
			final ZoneOffset offset = ZoneOffset.ofHours(3);
			final OffsetDateTime nanoBearingFrom =
				LocalDateTime.of(2024, 5, 6, 7, 8, 9, 123_456_789).atOffset(offset);
			final OffsetDateTime nanoBearingTo =
				LocalDateTime.of(2024, 5, 6, 7, 8, 10, 987_654_321).atOffset(offset);
			final DateTimeRange original = DateTimeRange.between(nanoBearingFrom, nanoBearingTo);

			final ValueColumn<DateTimeRange> column = columnOf(RangeKind.DATE_TIME, 0);
			column.insertKeyAt(0, original);
			final DateTimeRange decoded = column.keyAt(0);

			// the sub-millisecond digits really are gone from the precise bounds, and the offset with them ...
			assertEquals(123_456_789, original.getPreciseFrom().getNano());
			assertEquals(
				123_000_000, decoded.getPreciseFrom().getNano(), "the rebuilt bound carries whole milliseconds"
			);
			assertEquals(
				987_000_000, decoded.getPreciseTo().getNano(), "the rebuilt bound carries whole milliseconds"
			);
			assertNotEquals(offset, ZoneOffset.UTC, "the fixture must not write at UTC or the next line is vacuous");
			assertEquals(ZoneOffset.UTC, decoded.getPreciseFrom().getOffset(), "and is re-anchored at UTC");
			assertEquals(
				original.getPreciseFrom().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
				decoded.getPreciseFrom().toInstant(), "naming the same instant, to the millisecond"
			);

			// ... and invisible to everything that compares ranges
			assertEquals(original.getFrom(), decoded.getFrom());
			assertEquals(original.getTo(), decoded.getTo());
			assertEquals(original, decoded, "equality is generated from the comparison longs alone");
			assertEquals(original.hashCode(), decoded.hashCode());
			assertEquals(0, original.compareTo(decoded));

			// and it survives a re-clone, which is what consolidation actually performs
			final Range<OffsetDateTime> reclonedOriginal =
				original.cloneWithDifferentBounds(original.getPreciseFrom(), original.getPreciseTo());
			final Range<OffsetDateTime> reclonedDecoded =
				decoded.cloneWithDifferentBounds(decoded.getPreciseFrom(), decoded.getPreciseTo());
			assertEquals(reclonedOriginal.getFrom(), reclonedDecoded.getFrom());
			assertEquals(reclonedOriginal.getTo(), reclonedDecoded.getTo());
		}

		@Test
		@DisplayName("a sub-second range whose two bounds carry DIFFERENT offsets rebuilds rather than throwing")
		void shouldRebuildASubSecondRangeWhoseBoundsCarryDifferentOffsets() {
			// the sibling above uses ONE offset on both bounds and therefore cannot reach this case. Here the two
			// bounds name the same epoch MILLISECOND at two different offsets, so truncation collapses them to one
			// long - a zero-width range whose two bounds were written at two offsets. `DateTimeRange` used to refuse
			// exactly that on the way back in (`assertFromLesserThanTo` compared the local date-time and the offset,
			// and the sub-millisecond tail was what made the ORIGINAL legal), so `keyAt` threw for a value the write
			// path had happily indexed - and `keyAt` is reached by every dirtied leaf inside a transaction, by the
			// array-delta read and by the reload check. The assertion now compares instants, which is the order this
			// type sorts and compares by
			final OffsetDateTime from = LocalDateTime.of(2024, 1, 1, 12, 0, 0).atOffset(ZoneOffset.ofHours(2));
			final OffsetDateTime to =
				LocalDateTime.of(2024, 1, 1, 11, 0, 0, 999_999).atOffset(ZoneOffset.ofHours(1));
			final DateTimeRange original = DateTimeRange.between(from, to);
			assertNotEquals(
				from.toInstant(), to.toInstant(), "the two bounds must be distinct instants below the millisecond"
			);
			assertEquals(original.getFrom(), original.getTo(), "the two bounds must land on one epoch millisecond");

			final ValueColumn<DateTimeRange> column = columnOf(RangeKind.DATE_TIME, 0);
			column.insertKeyAt(0, original);
			final DateTimeRange decoded = column.keyAt(0);

			assertEquals(original, decoded, "the rebuilt key must equal the original");
			assertEquals(original.getFrom(), decoded.getFrom());
			assertEquals(original.getTo(), decoded.getTo());
			assertEquals(
				ZoneOffset.UTC, decoded.getPreciseFrom().getOffset(), "a rebuilt from-bound is always at UTC"
			);
			assertEquals(
				ZoneOffset.UTC, decoded.getPreciseTo().getOffset(), "a rebuilt to-bound is always at UTC"
			);
			assertEquals(
				decoded.getPreciseFrom(), decoded.getPreciseTo(),
				"a zero-width range rebuilds as one moment written twice, which its own bound assertion admits"
			);
			// and the rebuilt range is still a usable key - the tree finds the stored slot with it
			assertTrue(
				column.findKeyPosition(decoded, 0, 1, null).alreadyPresent(),
				"the reconstruction must search back to its own slot"
			);
		}

		@Test
		@DisplayName("asBoxedArray materializes the declared concrete subtype and appendKey renders the rebuilt key")
		void shouldMaterializeTheDeclaredSubtypeArrayAndRenderKeys() {
			final List<DateTimeRange> dateTimes =
				List.of(offsetBearingRange(0), offsetBearingRange(1), offsetBearingRange(2));
			final ValueColumn<DateTimeRange> dateTimeColumn = assertRoundTrip(RangeKind.DATE_TIME, 0, dateTimes);
			assertSubtypeArrayAndRendering(dateTimeColumn, DateTimeRange.class, dateTimes);

			final List<IntegerNumberRange> integers = List.of(
				IntegerNumberRange.to(-17), IntegerNumberRange.between(-4, 4), IntegerNumberRange.from(11)
			);
			final ValueColumn<NumberRange<Integer>> integerColumn =
				assertRoundTrip(RangeKind.INTEGER_NUMBER, 0, integers);
			assertSubtypeArrayAndRendering(integerColumn, IntegerNumberRange.class, integers);
		}

		/**
		 * Asserts a populated column materializes its boxed array as the **declared** concrete subtype — the
		 * component type `ReevaluateExpressionExecutor` re-indexes against, and the one thing that separates this
		 * implementation's `asBoxedArray` from every sibling's — and that `appendKey` renders the rebuilt key.
		 *
		 * @param column   the populated column
		 * @param subtype  the concrete range subtype its boxed array must be typed by
		 * @param expected the ranges the column holds, in slot order
		 */
		private static <M extends Comparable<M>> void assertSubtypeArrayAndRendering(
			@Nonnull ValueColumn<M> column, @Nonnull Class<?> subtype, @Nonnull List<? extends Range<?>> expected
		) {
			final M[] boxed = column.asBoxedArray();
			assertSame(
				subtype, boxed.getClass().getComponentType(),
				"the array must be typed by the declared subtype, not by the abstract range class"
			);
			assertEquals(expected.size(), boxed.length, "the boxed array must be exactly the live run");
			for (int i = 0; i < expected.size(); i++) {
				assertEquals(expected.get(i), boxed[i], "boxed slot " + i);
				final StringBuilder sb = new StringBuilder(64);
				column.appendKey(sb, i);
				assertEquals(
					column.keyAt(i).toString(), sb.toString(), "appendKey must render the rebuilt key at slot " + i
				);
			}
		}
	}

	/**
	 * The two bound arrays have to travel **in lockstep** through every array operation, and each of these tests
	 * drives one of the mutators the round-trip nest above does not reach.
	 *
	 * A slot's lower and upper bound are independent values here — the fixture gives every range its own pair — so a
	 * mutator that shifted, copied or trimmed one array without the other assembles a key out of two different
	 * slots, which {@link DateTimeRange} equality does see: it is generated from exactly that pair. Each assertion
	 * also reads the rebuilt bound objects back, which equality never looks at, and pins the UTC reconstruction
	 * contract.
	 */
	@Nested
	@DisplayName("the two bound arrays travel in lockstep")
	class MetaLockstepTest {

		@Test
		@DisplayName("bulkLoad carries every key's packed offsets into the arrays it allocates")
		void shouldPreserveBoundOffsetsWhenBulkLoaded() {
			// the reload path: `InvertedIndex.fromPersistedPages` fills every leaf of a persisted index this way, so
			// a bound array missed here is a whole catalog reloaded under the wrong keys
			final DateTimeRange[] loaded = new DateTimeRange[6];
			for (int i = 0; i < loaded.length; i++) {
				loaded[i] = offsetBearingRange(i);
			}
			final ValueColumn<DateTimeRange> column = columnOf(RangeKind.DATE_TIME, 0);
			column.bulkLoad(loaded, loaded.length);

			assertEquals(loaded.length, column.size());
			for (int i = 0; i < loaded.length; i++) {
				assertRebuiltBoundsAt(column, i, loaded[i], "bulkLoad");
			}
		}

		@Test
		@DisplayName("an empty bulk load leaves a date-time column on the shared empty arrays and still round-trips")
		void shouldBulkLoadAnEmptyDateTimeColumn() {
			// `bulkLoad` is the one site in the column that infers the kind rather than reading it - it branches on
			// the length of the second array it has just allocated, and a zero-length allocation parks that array on
			// the shared empty constant, so an empty date-time load takes the NUMERIC arm. Harmless only because
			// that arm's loop body never runs; this pins the observable shape a kind-based branch must preserve
			final ValueColumn<DateTimeRange> column = columnOf(RangeKind.DATE_TIME, 0);
			column.bulkLoad(new Object[0], 0);
			assertEquals(0, column.size());
			assertEquals(
				columnOf(RangeKind.DATE_TIME, 0).getHeapSizeInBytes(), column.getHeapSizeInBytes(),
				"an empty load must leave both arrays on the shared empty constant"
			);

			// and the column is still a date-time column afterwards, offsets included
			final DateTimeRange range = offsetBearingRange(3);
			column.insertKeyAt(0, range);
			assertRebuiltBoundsAt(column, 0, range, "insert after an empty bulkLoad");
		}

		@Test
		@DisplayName("copyRangeTo carries the packed offsets across columns and through an in-place right shift")
		void shouldPreserveBoundOffsetsWhenCopiedAndShifted() {
			final ValueColumn<DateTimeRange> source = columnOf(RangeKind.DATE_TIME, 0);
			for (int i = 0; i < 4; i++) {
				source.insertKeyAt(i, offsetBearingRange(i));
			}

			// across two columns - what a split, a merge and a steal each move
			final ValueColumn<DateTimeRange> target = columnOf(RangeKind.DATE_TIME, 0);
			source.copyRangeTo(1, target, 0, 3);
			assertEquals(3, target.size());
			for (int i = 0; i < 3; i++) {
				assertRebuiltBoundsAt(target, i, offsetBearingRange(i + 1), "cross-column copyRangeTo");
			}

			// and in place and overlapping - the leaf's own right shift ahead of an insert
			source.copyRangeTo(0, source, 2, 4);
			assertEquals(6, source.size());
			assertRebuiltBoundsAt(source, 0, offsetBearingRange(0), "in-place right shift");
			assertRebuiltBoundsAt(source, 1, offsetBearingRange(1), "in-place right shift");
			for (int i = 0; i < 4; i++) {
				assertRebuiltBoundsAt(source, i + 2, offsetBearingRange(i), "in-place right shift");
			}
		}

		@Test
		@DisplayName("the packed offsets survive a removal, a duplicate, a truncation and a trim")
		void shouldPreserveBoundOffsetsWhenSlotsAreDroppedDuplicatedAndTrimmed() {
			final ValueColumn<DateTimeRange> column = columnOf(RangeKind.DATE_TIME, 0);
			for (int i = 0; i < 8; i++) {
				column.insertKeyAt(i, offsetBearingRange(i));
			}

			// `removeKeyAt` left-shifts the whole tail, so every surviving key changes slot and its upper bound has
			// to change slot with it - a shift applied to two arrays out of three leaves the offsets one slot out of
			// step, which every equality assertion in the suite reads as correct
			column.removeKeyAt(1);
			assertEquals(7, column.size());
			assertRebuiltBoundsAt(column, 0, offsetBearingRange(0), "removeKeyAt");
			for (int i = 1; i < 7; i++) {
				assertRebuiltBoundsAt(column, i, offsetBearingRange(i + 1), "removeKeyAt");
			}

			// the MVCC decouple copies the backing arrays; the copy has to carry the third one too
			final ValueColumn<DateTimeRange> copy = column.duplicate();
			assertEquals(7, copy.size());
			assertRebuiltBoundsAt(copy, 0, offsetBearingRange(0), "duplicate");
			for (int i = 1; i < 7; i++) {
				assertRebuiltBoundsAt(copy, i, offsetBearingRange(i + 1), "duplicate");
			}

			// `clearAt` truncates the live run, after which `trimmed` reallocates the survivors into a shorter backing
			copy.clearAt(2);
			assertEquals(2, copy.size());
			final ValueColumn<DateTimeRange> trimmed = copy.trimmed();
			assertNotSame(copy, trimmed, "two live keys in eight slots must be worth a trim");
			assertEquals(2, trimmed.size());
			assertRebuiltBoundsAt(trimmed, 0, offsetBearingRange(0), "clearAt + trimmed");
			assertRebuiltBoundsAt(trimmed, 1, offsetBearingRange(2), "clearAt + trimmed");
		}
	}

	/**
	 * The binding invariant: {@code Range.consolidateRange} over reconstructed ranges must land on the same
	 * thresholds as over the originals, because that is what {@code FilterIndex.addRecordDelta} /
	 * {@code removeRecordDelta} do with the values they read back out of the tree.
	 */
	@Nested
	@DisplayName("the consolidation invariant")
	class ConsolidationTest {

		/**
		 * Stores the ranges in a column, reads them all back and asserts that consolidating the reconstructions
		 * yields exactly the thresholds consolidating the originals does.
		 *
		 * @param kind                 the range subtype the column stores
		 * @param indexedDecimalPlaces the scale `BigDecimalNumberRange` bounds are encoded at
		 * @param originals            the ranges to store, in ascending order
		 */
		@SuppressWarnings({"unchecked", "rawtypes"})
		private static void assertConsolidationAgrees(
			@Nonnull RangeKind kind, int indexedDecimalPlaces, @Nonnull Range[] originals
		) {
			final ValueColumn column = columnOf(kind, indexedDecimalPlaces);
			for (int i = 0; i < originals.length; i++) {
				column.insertKeyAt(i, (Comparable) originals[i]);
			}
			// the reconstruction array keeps the ORIGINAL component type, because `consolidateRange` allocates its
			// result from it — a `Range[]` there would make the two sides differ in a way the assertions cannot see
			final Range[] reconstructed = (Range[]) Array.newInstance(
				originals.getClass().getComponentType(), originals.length);
			for (int i = 0; i < originals.length; i++) {
				reconstructed[i] = (Range) column.keyAt(i);
			}

			final Range[] fromOriginals = Range.consolidateRange(originals);
			final Range[] fromReconstructed = Range.consolidateRange(reconstructed);

			assertEquals(
				fromOriginals.length, fromReconstructed.length,
				"consolidation collapsed a different number of ranges"
			);
			for (int i = 0; i < fromOriginals.length; i++) {
				assertEquals(
					fromOriginals[i].getFrom(), fromReconstructed[i].getFrom(),
					"consolidated lower bound mismatch at slot " + i
				);
				assertEquals(
					fromOriginals[i].getTo(), fromReconstructed[i].getTo(),
					"consolidated upper bound mismatch at slot " + i
				);
			}
		}

		@Test
		@DisplayName("an open range meeting a closed one at another offset — the case that used to need the offsets")
		void shouldConsolidateAnOpenRangeAgainstClosedRangesAtOtherOffsets() {
			// This was THE counterexample for a column that stored no offsets. `consolidateRange` calls
			// cloneWithDifferentBounds(null, B.getPreciseTo()) when the open range A wins the lower bound and the
			// closed range B wins the upper one, and `DateTimeRange` used to recompute the open side's sentinel from
			// **B's** offset — so a B rebuilt at the wrong offset moved the consolidated lower threshold, after
			// which `FilterIndex.removeRecordDelta` asked the range index to drop a threshold no `addRange` ever
			// inserted. It is settled by construction now: the open-bound sentinel is a CONSTANT, so no offset can
			// reach it. This test is what keeps that true
			final ZoneOffset twoHours = ZoneOffset.ofHours(2);
			final ZoneOffset fiveHours = ZoneOffset.ofHours(5);
			final DateTimeRange openFrom =
				DateTimeRange.until(LocalDateTime.of(2024, 1, 10, 0, 0).atOffset(twoHours));
			final DateTimeRange closed = DateTimeRange.between(
				LocalDateTime.of(2024, 1, 5, 0, 0).atOffset(fiveHours),
				LocalDateTime.of(2024, 1, 20, 0, 0).atOffset(fiveHours)
			);
			assertTrue(openFrom.overlaps(closed), "the counterexample needs the two ranges to overlap");

			assertConsolidationAgrees(
				RangeKind.DATE_TIME, 0, new DateTimeRange[]{openFrom, closed}
			);

			// the merged survivor really is one-sided (so the branch above is the one that ran), and its lower
			// threshold is the offset-independent constant rather than anything derived from either range's offset
			final DateTimeRange[] consolidated = Range.consolidateRange(new DateTimeRange[]{openFrom, closed});
			assertEquals(1, consolidated.length, "the two ranges must merge into one");
			assertNull(consolidated[0].getPreciseFrom(), "the survivor keeps the open lower bound");
			assertEquals(
				DateTimeRange.OPEN_FROM_THRESHOLD, consolidated[0].getFrom(),
				"the open lower threshold is a constant, not a function of the surviving bound's offset"
			);
			// the same merge with the CLOSED range moved to UTC lands on the same two thresholds — which is exactly
			// what makes storing its offset unnecessary, and what the assertion above would miss on its own
			final DateTimeRange closedAtUtc = DateTimeRange.between(
				closed.getPreciseFrom().withOffsetSameInstant(ZoneOffset.UTC),
				closed.getPreciseTo().withOffsetSameInstant(ZoneOffset.UTC)
			);
			final DateTimeRange[] consolidatedAtUtc =
				Range.consolidateRange(new DateTimeRange[]{openFrom, closedAtUtc});
			assertEquals(1, consolidatedAtUtc.length);
			assertEquals(consolidated[0].getFrom(), consolidatedAtUtc[0].getFrom());
			assertEquals(consolidated[0].getTo(), consolidatedAtUtc[0].getTo());
		}

		@Test
		@DisplayName("a mixed array of open and closed date-time ranges at several offsets consolidates identically")
		void shouldConsolidateAMixedDateTimeArray() {
			final ZoneOffset plusEighteen = ZoneOffset.ofTotalSeconds(18 * 3600);
			final ZoneOffset minusEighteen = ZoneOffset.ofTotalSeconds(-18 * 3600);
			final ZoneOffset halfHour = ZoneOffset.ofTotalSeconds(-1800);
			assertConsolidationAgrees(
				RangeKind.DATE_TIME, 0,
				new DateTimeRange[]{
					DateTimeRange.until(LocalDateTime.of(2024, 3, 1, 0, 0).atOffset(plusEighteen)),
					DateTimeRange.between(
						LocalDateTime.of(2024, 2, 1, 0, 0).atOffset(minusEighteen),
						LocalDateTime.of(2024, 4, 1, 0, 0).atOffset(halfHour)
					),
					DateTimeRange.between(
						LocalDateTime.of(2024, 3, 15, 0, 0).atOffset(halfHour),
						LocalDateTime.of(2024, 6, 1, 0, 0).atOffset(plusEighteen)
					),
					DateTimeRange.since(LocalDateTime.of(2025, 1, 1, 0, 0).atOffset(minusEighteen))
				}
			);
		}

		@Test
		@DisplayName("mixed open and closed numeric ranges consolidate identically for every numeric subtype")
		void shouldConsolidateMixedNumericArrays() {
			assertConsolidationAgrees(
				RangeKind.INTEGER_NUMBER, 0,
				new IntegerNumberRange[]{
					IntegerNumberRange.to(10), IntegerNumberRange.between(5, 40),
					IntegerNumberRange.between(80, 90), IntegerNumberRange.from(85)
				}
			);
			assertConsolidationAgrees(
				RangeKind.LONG_NUMBER, 0,
				new LongNumberRange[]{
					LongNumberRange.to(-5L), LongNumberRange.between(-10L, 100L), LongNumberRange.from(1_000L)
				}
			);
			assertConsolidationAgrees(
				RangeKind.SHORT_NUMBER, 0,
				new ShortNumberRange[]{
					ShortNumberRange.to((short) 3), ShortNumberRange.between((short) 1, (short) 9)
				}
			);
			// the open-to range deliberately does NOT overlap the consolidation of the first two. An open-from range
			// merging with an open-to one makes `Range.consolidateRange` call `cloneWithDifferentBounds(null, null)`,
			// which every `Range` implementation refuses — a pre-existing property of the data type, reproduced
			// identically by the originals and by the reconstructions, and nothing this column can affect either way
			assertConsolidationAgrees(
				RangeKind.BYTE_NUMBER, 0,
				new ByteNumberRange[]{
					ByteNumberRange.to((byte) 3), ByteNumberRange.between((byte) 1, (byte) 9),
					ByteNumberRange.from((byte) 20)
				}
			);
			// the scale is what makes this one work: `cloneWithDifferentBounds` re-derives the longs from the precise
			// bounds, and it uses the range's own `retainedDecimalPlaces` to do it
			assertConsolidationAgrees(
				RangeKind.BIG_DECIMAL_NUMBER, 2,
				new BigDecimalNumberRange[]{
					BigDecimalNumberRange.to(new BigDecimal("10.50"), 2),
					BigDecimalNumberRange.between(new BigDecimal("5.25"), new BigDecimal("40.75"), 2),
					BigDecimalNumberRange.from(new BigDecimal("100.00"), 2)
				}
			);
		}

		@Test
		@DisplayName("a rebuilt saturated long range consolidates with an overlapping sibling")
		void shouldConsolidateARebuiltSaturatedLongRangeWithAnOverlappingSibling() {
			// `consolidateRange` merges an overlapping pair by cloning the winner with the two precise bounds that
			// won, and the saturated range wins both - so it is cloned with the pair its reconstruction carries
			final ValueColumn<NumberRange<Long>> column = columnOf(RangeKind.LONG_NUMBER, 0);
			column.insertKeyAt(0, LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE));
			final LongNumberRange rebuilt = (LongNumberRange) column.keyAt(0);
			final LongNumberRange sibling = LongNumberRange.between(1L, 5L);
			assertTrue(rebuilt.overlaps(sibling), "the saturated range overlaps everything");
			final LongNumberRange[] pair = {rebuilt, sibling};

			// the merge clones the winner with the two precise bounds that won, so the reconstruction has to carry a
			// pair `cloneWithDifferentBounds` accepts - and the result must be the boxed column's answer verbatim
			final LongNumberRange[] consolidated = Range.consolidateRange(pair);
			assertEquals(1, consolidated.length, "the saturated range swallows every sibling it overlaps");
			assertEquals(Long.MIN_VALUE, consolidated[0].getFrom());
			assertEquals(Long.MAX_VALUE, consolidated[0].getTo());
			assertArrayEquals(
				Range.consolidateRange(new LongNumberRange[]{LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE),
					sibling}),
				consolidated,
				"the rebuilt range must consolidate exactly as the original object does"
			);
		}
	}

	/**
	 * Verifies the column's ordered search agrees with the boxed column driven by {@code Comparator.naturalOrder()},
	 * which is the comparator a range-typed filter index actually gives its tree.
	 */
	@Nested
	@DisplayName("ordered search")
	class OrderTest {

		@Test
		@DisplayName("findKeyPosition matches the boxed column under natural order, tiebreak included")
		void shouldMatchTheBoxedColumnUnderNaturalOrder() {
			// same-`from` different-`to` neighbours are the tiebreak: natural order for both range hierarchies is
			// getFrom() then getTo(), and a search that stopped at the lower bound would confuse these two
			final IntegerNumberRange[] dataset = {
				IntegerNumberRange.to(-100),
				IntegerNumberRange.between(-10, -5),
				IntegerNumberRange.between(0, 5),
				IntegerNumberRange.between(0, 50),
				IntegerNumberRange.between(0, Integer.MAX_VALUE),
				IntegerNumberRange.between(7, 9),
				IntegerNumberRange.from(20)
			};
			Arrays.sort(dataset);
			final ValueColumn<NumberRange<Integer>> primitive = columnOf(RangeKind.INTEGER_NUMBER, 0);
			final ValueColumn<NumberRange<Integer>> boxed = boxedColumnOf(IntegerNumberRange.class);
			for (int i = 0; i < dataset.length; i++) {
				primitive.insertKeyAt(i, dataset[i]);
				boxed.insertKeyAt(i, dataset[i]);
			}

			final IntegerNumberRange[] probes = {
				IntegerNumberRange.to(-1_000),
				dataset[0], dataset[2], dataset[3], dataset[dataset.length - 1],
				IntegerNumberRange.between(0, 6),
				IntegerNumberRange.between(0, 49),
				IntegerNumberRange.between(1, 2),
				IntegerNumberRange.from(1_000)
			};
			for (final IntegerNumberRange probe : probes) {
				final InsertionPosition primitivePosition =
					primitive.findKeyPosition(probe, 0, dataset.length, Comparator.naturalOrder());
				final InsertionPosition boxedPosition =
					boxed.findKeyPosition(probe, 0, dataset.length, Comparator.naturalOrder());
				assertEquals(
					boxedPosition.position(), primitivePosition.position(), "position mismatch for probe " + probe);
				assertEquals(
					boxedPosition.alreadyPresent(), primitivePosition.alreadyPresent(),
					"alreadyPresent mismatch for probe " + probe
				);
			}

			// the documented empty-range encoding (position 0, not present), same as every sibling column
			final InsertionPosition empty = primitive.findKeyPosition(dataset[0], 2, 2, null);
			assertEquals(0, empty.position());
			assertFalse(empty.alreadyPresent());
		}

		@Test
		@DisplayName("date-time search agrees with natural order across mixed offsets and open bounds")
		void shouldSearchDateTimeRangesUnderNaturalOrder() {
			final List<DateTimeRange> dataset = new ArrayList<>(6);
			final LocalDateTime base = LocalDateTime.of(2024, 7, 1, 0, 0);
			dataset.add(DateTimeRange.until(base.atOffset(ZoneOffset.ofHours(2))));
			dataset.add(
				DateTimeRange.between(base.atOffset(ZoneOffset.UTC), base.plusDays(1).atOffset(ZoneOffset.UTC)));
			dataset.add(
				DateTimeRange.between(base.atOffset(ZoneOffset.UTC), base.plusDays(9).atOffset(ZoneOffset.UTC)));
			dataset.add(DateTimeRange.since(base.plusDays(3).atOffset(ZoneOffset.ofHours(-7))));
			dataset.sort(Comparator.naturalOrder());

			final ValueColumn<DateTimeRange> primitive = columnOf(RangeKind.DATE_TIME, 0);
			final ValueColumn<DateTimeRange> boxed = new BoxedObjectColumn<>(DateTimeRange.class, BLOCK_SIZE);
			for (int i = 0; i < dataset.size(); i++) {
				primitive.insertKeyAt(i, dataset.get(i));
				boxed.insertKeyAt(i, dataset.get(i));
			}
			for (final DateTimeRange probe : dataset) {
				final InsertionPosition primitivePosition =
					primitive.findKeyPosition(probe, 0, dataset.size(), Comparator.naturalOrder());
				assertTrue(primitivePosition.alreadyPresent(), "a stored key must be found: " + probe);
				assertEquals(
					boxed.findKeyPosition(probe, 0, dataset.size(), Comparator.naturalOrder()).position(),
					primitivePosition.position()
				);
			}
			// an absent probe sharing a lower bound with a stored key still lands by the upper bound
			final DateTimeRange absent =
				DateTimeRange.between(base.atOffset(ZoneOffset.UTC), base.plusDays(5).atOffset(ZoneOffset.UTC));
			final InsertionPosition position =
				primitive.findKeyPosition(absent, 0, dataset.size(), Comparator.naturalOrder());
			assertFalse(position.alreadyPresent());
			assertEquals(
				boxed.findKeyPosition(absent, 0, dataset.size(), Comparator.naturalOrder()).position(),
				position.position()
			);
		}
	}

	/**
	 * Two columns may exchange keys only when their encodings agree, and this is the only column in the family whose
	 * compatibility has **two** dimensions: one class covers six kinds, so `instanceof` alone does not establish that
	 * a source slot means the same thing in the destination.
	 */
	@Nested
	@DisplayName("cross-column copy compatibility")
	class CopyCompatibilityTest {

		@Test
		@DisplayName("a copy between two incompatible key encodings is refused rather than absorbed")
		void shouldRefuseToCopyBetweenTwoDifferentColumnKinds() {
			final ValueColumn<DateTimeRange> source = columnOf(RangeKind.DATE_TIME, 0);
			for (int i = 0; i < 3; i++) {
				source.insertKeyAt(i, offsetBearingRange(i));
			}

			// the key type is a compile-time argument only, so a numeric column can be named at this type - and it
			// is exactly the column whose `keyAt` would rebuild these three keys as the wrong subtype
			final ValueColumn<DateTimeRange> otherKind = columnOf(RangeKind.INTEGER_NUMBER, 0);
			assertThrows(IllegalArgumentException.class, () -> source.copyRangeTo(0, otherKind, 0, 3));
			assertEquals(0, otherKind.size(), "a refused copy must leave the destination untouched");

			// the other arm of the same check: a different implementation altogether
			final ValueColumn<DateTimeRange> boxed = new BoxedObjectColumn<>(DateTimeRange.class, BLOCK_SIZE);
			assertThrows(IllegalArgumentException.class, () -> source.copyRangeTo(0, boxed, 0, 3));
			assertEquals(0, boxed.size(), "a refused copy must leave the destination untouched");
		}

		@Test
		@DisplayName("a copy between two big decimal columns of different scale is refused rather than absorbed")
		void shouldRefuseACopyBetweenTwoBigDecimalColumnsOfDifferentScale() {
			final ValueColumn<NumberRange<BigDecimal>> atTwo = columnOf(RangeKind.BIG_DECIMAL_NUMBER, 2);
			atTwo.insertKeyAt(0, BigDecimalNumberRange.between(new BigDecimal("1.25"), new BigDecimal("9.50"), 2));
			final ValueColumn<NumberRange<BigDecimal>> atZero = columnOf(RangeKind.BIG_DECIMAL_NUMBER, 0);

			// the longs move across intact and the destination rebuilds every one of them at ITS scale, so an
			// unguarded copy silently turns 1.25 into 125 - two incompatible encodings, exactly what this check
			// claims to catch, and the only pair of them that shares a kind
			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class, () -> atTwo.copyRangeTo(0, atZero, 0, 1));
			assertTrue(ex.getMessage().contains("2"), () -> "the source scale must be named: " + ex.getMessage());
			assertTrue(ex.getMessage().contains("0"), () -> "the target scale must be named: " + ex.getMessage());
			assertEquals(0, atZero.size(), "a refused copy must leave the destination untouched");
		}
	}

	/**
	 * Verifies which entry point may select the range column, and for which declared types.
	 */
	@Nested
	@DisplayName("ValueColumnFactory selection")
	class FactorySelectionTest {

		@Test
		@DisplayName("all six concrete range subtypes select the range column through forFilterKey")
		void shouldSelectRangeColumnForEveryConcreteSubtype() {
			final Class<?>[] concreteSubtypes = {
				DateTimeRange.class, BigDecimalNumberRange.class, LongNumberRange.class,
				IntegerNumberRange.class, ShortNumberRange.class, ByteNumberRange.class
			};
			for (final Class<?> subtype : concreteSubtypes) {
				assertInstanceOf(
					RangeValueColumn.class,
					ValueColumnFactory.forFilterKey(subtype, Comparator.naturalOrder(), 0).create(BLOCK_SIZE),
					"expected a range column for " + subtype.getSimpleName()
				);
				assertInstanceOf(
					RangeValueColumn.class,
					ValueColumnFactory.forFilterKey(subtype, null, 0).create(BLOCK_SIZE),
					"expected a range column for " + subtype.getSimpleName() + " under the null comparator"
				);
			}
		}

		@Test
		@DisplayName("an abstract range type falls through to the boxed column")
		void shouldFallThroughForAbstractRangeTypes() {
			// a filter index really is built over `NumberRange.class` in this repository's own tests, and neither it
			// nor `Range.class` is a supported schema attribute type - there is no subtype to rebuild for either, so
			// the selection has to be exact class equality rather than `isAssignableFrom`
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forFilterKey(NumberRange.class, Comparator.naturalOrder(), 0).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forFilterKey(Range.class, Comparator.naturalOrder(), 0).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("a non-natural comparator falls through to the boxed column")
		void shouldFallThroughForANonNaturalComparator() {
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory
					.forFilterKey(IntegerNumberRange.class, Comparator.<IntegerNumberRange>reverseOrder(), 0)
					.create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("forKey can never select the range column, whatever the type")
		void shouldNeverSelectTheRangeColumnThroughForKey() {
			// `UniqueIndexBPlusTreeSupport.buildTree` and `ReferenceTypeCardinalityIndex` call `forKey` and have no
			// `indexedDecimalPlaces` to give, so a `Range`-typed unique attribute reaching the range column would
			// rebuild every `BigDecimalNumberRange` at the wrong scale, silently. The gate is what is callable
			final Class<?>[] concreteSubtypes = {
				DateTimeRange.class, BigDecimalNumberRange.class, LongNumberRange.class,
				IntegerNumberRange.class, ShortNumberRange.class, ByteNumberRange.class
			};
			for (final Class<?> subtype : concreteSubtypes) {
				assertInstanceOf(
					BoxedObjectColumn.class,
					ValueColumnFactory.forKey(subtype, Comparator.naturalOrder()).create(BLOCK_SIZE),
					"forKey must not select the range column for " + subtype.getSimpleName()
				);
			}
		}

		@Test
		@DisplayName("a type the range column does not claim gets exactly the column forKey would have chosen")
		void shouldDelegateEveryTypeItDoesNotClaimToForKey() {
			// every filter index in the engine now goes through `forFilterKey`, so a regression in its fall-through
			// would move every String and integral one back to the boxed column - a large, silent memory regression
			// that only two budget assertions elsewhere would catch, and only indirectly
			final Class<?>[] unclaimed = {
				String.class, Long.class, Integer.class, UUID.class
			};
			for (final Class<?> type : unclaimed) {
				assertDelegatesToForKey(type, Comparator.naturalOrder(), 0);
				assertDelegatesToForKey(type, null, 0);
			}
			// and the scale it would have used is simply ignored on the delegating path
			assertDelegatesToForKey(BigDecimal.class, Comparator.naturalOrder(), 2);
		}

		@Test
		@DisplayName("a declared temporal type is delegated under its NORMALIZED key class, not verbatim")
		void shouldDelegateTemporalTypesUnderTheirNormalizedKeyClass() {
			// the one place where the two entry points deliberately diverge: a filter index converts every temporal
			// value to an `Instant` before it becomes a key, so `forFilterKey` must select the column `forKey` would
			// pick for `Instant` - while `forKey` itself, whose callers store values verbatim, must NOT.
			for (final Class<?> declared : new Class<?>[]{OffsetDateTime.class, LocalDateTime.class}) {
				for (final Comparator<?> comparator : new Comparator<?>[]{Comparator.naturalOrder(), null}) {
					final Object filterColumn =
						ValueColumnFactory.forFilterKey(declared, comparator, 0).create(BLOCK_SIZE);
					assertSame(
						ValueColumnFactory.forKey(Instant.class, comparator).create(BLOCK_SIZE).getClass(),
						filterColumn.getClass(), "forFilterKey must select the Instant column for " + declared.getSimpleName()
					);
					assertNotEquals(
						ValueColumnFactory.forKey(declared, comparator).create(BLOCK_SIZE).getClass(),
						filterColumn.getClass(),
						"forKey must NOT select the Instant column for " + declared.getSimpleName()
					);
				}
			}
		}

		/**
		 * Asserts `forFilterKey` hands back the very column class `forKey` would have chosen for the same type and
		 * comparator. The two are compared against **each other** rather than against a named column, which keeps
		 * this about the delegation instead of duplicating `forKey`'s own selection tests.
		 *
		 * @param type                 the declared plain attribute type
		 * @param comparator           the tree comparator, or `null` for natural order
		 * @param indexedDecimalPlaces the scale the filter-index entry point would carry
		 */
		private static void assertDelegatesToForKey(
			@Nonnull Class<?> type, @Nullable Comparator<?> comparator, int indexedDecimalPlaces
		) {
			final Object delegated =
				ValueColumnFactory.forFilterKey(type, comparator, indexedDecimalPlaces).create(BLOCK_SIZE);
			final Object direct = ValueColumnFactory.forKey(type, comparator).create(BLOCK_SIZE);
			assertSame(
				direct.getClass(), delegated.getClass(),
				"forFilterKey must delegate " + type.getSimpleName() + " to forKey verbatim"
			);
		}
	}

	/**
	 * Drives a real {@link TransactionalBucketBPlusTree} whose leaves use the range column, so the two-array
	 * lockstep runs through split, merge, steal and an MVCC commit rather than only through direct column calls.
	 */
	@Nested
	@DisplayName("range-keyed tree workload")
	class TreeWorkloadTest {

		/**
		 * Builds an ascending domain of date-time ranges spanning several offsets and both open shapes.
		 *
		 * @return the domain, ascending
		 */
		@Nonnull
		private static List<DateTimeRange> dateTimeDomain() {
			final ZoneOffset[] offsets = {
				ZoneOffset.UTC, ZoneOffset.ofHours(2), ZoneOffset.ofTotalSeconds(-1800),
				ZoneOffset.ofTotalSeconds(18 * 3600), ZoneOffset.ofTotalSeconds(-18 * 3600)
			};
			final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
			final List<DateTimeRange> domain = new ArrayList<>(40);
			for (int i = 0; i < offsets.length; i++) {
				final ZoneOffset offset = offsets[i];
				domain.add(DateTimeRange.until(base.plusDays(i).atOffset(offset)));
				domain.add(DateTimeRange.since(base.plusDays(i).atOffset(offset)));
				for (int span = 1; span <= 4; span++) {
					domain.add(DateTimeRange.between(
						base.plusDays(i).atOffset(offset), base.plusDays(i + span).atOffset(offset)
					));
				}
			}
			domain.sort(Comparator.naturalOrder());
			return domain;
		}

		@Test
		@DisplayName("a randomized add/remove workload on a date-time-range tree matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleOnDateTimeRangeKeyedTree() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forFilterKey(DateTimeRange.class, Comparator.naturalOrder(), 0);
			assertInstanceOf(RangeValueColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<DateTimeRange> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, DateTimeRange.class, null, factory
			);

			final List<DateTimeRange> domain = dateTimeDomain();
			final TreeMap<DateTimeRange, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(424242L);
			for (int op = 0; op < 6_000; op++) {
				final DateTimeRange key = domain.get(random.nextInt(domain.size()));
				final int recordId = random.nextInt(1_000);
				if (random.nextInt(100) < 65) {
					tree.addRecord(key, recordId);
					oracle.computeIfAbsent(key, k -> new TreeSet<>()).add(recordId);
				} else {
					final TreeSet<Integer> set = oracle.get(key);
					if (set != null && set.contains(recordId)) {
						tree.removeRecord(key, recordId);
						set.remove(recordId);
						if (set.isEmpty()) {
							oracle.remove(key);
						}
					}
				}
				if (op % 250 == 0) {
					assertTreeMatchesOracle(tree, oracle);
					verifyConsistent(tree);
				}
			}
			assertTreeMatchesOracle(tree, oracle);
			verifyConsistent(tree);

			// the oracle compares keys with `equals`, which `DateTimeRange` generates from its two comparison longs
			// alone - blind to WHICH bound objects a split, a merge or a steal rebuilt. `toString` renders both
			// bounds as ISO_OFFSET_DATE_TIME, so comparing it reads the reconstruction back over the whole tree at
			// once, against the oracle key re-rendered at UTC
			final BucketCursor<DateTimeRange> cursor = tree.cursor();
			for (final DateTimeRange expected : oracle.keySet()) {
				assertTrue(cursor.next(), "the tree ran out of buckets before the oracle did");
				assertEquals(
					atUtc(expected).toString(), cursor.value().toString(),
					"a bucket value must render as the same two instants the oracle key names"
				);
			}
			assertFalse(cursor.next(), "the tree holds more buckets than the oracle");
		}

		@Test
		@DisplayName("a numeric range tree survives an MVCC commit that splits and merges leaves")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveNumericRangeTreeAcrossCommit() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forFilterKey(IntegerNumberRange.class, Comparator.naturalOrder(), 0);
			final TransactionalBucketBPlusTree<NumberRange<Integer>> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, NumberRange.class, null, factory
			);

			final List<NumberRange<Integer>> domain = new ArrayList<>(40);
			domain.add(IntegerNumberRange.to(-1));
			for (int i = 0; i < 18; i++) {
				domain.add(IntegerNumberRange.between(i, i + 3));
				domain.add(IntegerNumberRange.between(i, i + 9));
			}
			domain.add(IntegerNumberRange.from(100));
			domain.sort(Comparator.naturalOrder());

			final TreeMap<NumberRange<Integer>, TreeSet<Integer>> baseOracle = new TreeMap<>();
			final int half = domain.size() / 2;
			for (int i = 0; i < half; i++) {
				tree.addRecord(domain.get(i), 1_000 + i);
				baseOracle.computeIfAbsent(domain.get(i), k -> new TreeSet<>()).add(1_000 + i);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = half; i < domain.size(); i++) {
						tested.addRecord(domain.get(i), 1_000 + i);
						tested.addRecord(domain.get(i), 2_000 + i);
					}
					for (int i = 0; i < half / 2; i++) {
						tested.removeRecord(domain.get(i), 1_000 + i);
					}
				},
				(original, committed) -> {
					final TreeMap<NumberRange<Integer>, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int i = half; i < domain.size(); i++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(1_000 + i);
						set.add(2_000 + i);
						oracle.put(domain.get(i), set);
					}
					for (int i = 0; i < half / 2; i++) {
						oracle.remove(domain.get(i));
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);
					// the pre-commit base tree is unchanged — proves the layer decoupled
					assertTreeMatchesOracle(original, baseOracle);
				}
			);
		}
	}
}
