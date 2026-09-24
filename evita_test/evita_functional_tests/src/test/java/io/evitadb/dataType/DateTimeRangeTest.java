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

package io.evitadb.dataType;

import io.evitadb.dataType.exception.DataTypeParseException;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;

import static io.evitadb.dataType.DateTimeRange.between;
import static io.evitadb.dataType.DateTimeRange.since;
import static io.evitadb.dataType.DateTimeRange.until;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * Checks creation and behavior of the {@link DateTimeRange} data type.
 *
 * @author Jan Novotn\u00fd (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DateTimeRange")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class DateTimeRangeTest {

	@Nested
	@DisplayName("OffsetDateTime construction")
	class OffsetDateTimeConstructionTest {

		@Test
		@DisplayName("Should fail to construct range with both bounds null")
		void shouldFailToConstructUnreasonableRange() {
			assertThrows(EvitaInvalidUsageException.class, () -> between(null, null));
		}

		@Test
		@DisplayName("Should reject from after to")
		void shouldRejectFromAfterTo() {
			assertThrows(EvitaInvalidUsageException.class, () -> between(getOffsetDateTime(5), getOffsetDateTime(1)));
		}

		@Test
		@DisplayName("Should accept a zero-width range whose bounds name one moment at two offsets")
		void shouldAcceptZeroWidthRangeWhenBoundsShareTheMomentAtDifferentOffsets() {
			// the same moment written twice, once at +02:00 and once at +01:00: the bounds are neither `equals`
			// (the offsets differ) nor strictly ordered, but they name one instant, which is the order this type
			// compares by - so the range is legitimate and must build
			final OffsetDateTime fromAtPlusTwo = OffsetDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
			final OffsetDateTime toAtPlusOne = fromAtPlusTwo.withOffsetSameInstant(ZoneOffset.ofHours(1));

			final DateTimeRange range = between(fromAtPlusTwo, toAtPlusOne);

			assertEquals(fromAtPlusTwo, range.getPreciseFrom());
			assertEquals(toAtPlusOne, range.getPreciseTo());
			assertEquals(range, between(fromAtPlusTwo, fromAtPlusTwo));
		}

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenOffsetDateTime() {
			final DateTimeRange range = between(getOffsetDateTime(1), getOffsetDateTime(2));
			assertEquals(getOffsetDateTime(1), range.getPreciseFrom());
			assertEquals(getOffsetDateTime(2), range.getPreciseTo());
			assertEquals(1609514565000L, range.getFrom());
			assertEquals(1609600965000L, range.getTo());
			assertEquals("[2021-01-01T12:22:45-03:00,2021-01-02T12:22:45-03:00]", range.toString());
			assertEquals(range, between(getOffsetDateTime(1), getOffsetDateTime(2)));
			assertNotSame(range, between(getOffsetDateTime(1), getOffsetDateTime(2)));
			assertNotEquals(range, between(getOffsetDateTime(1), getOffsetDateTime(3)));
			assertNotEquals(range, between(getOffsetDateTime(2), getOffsetDateTime(2)));
			assertEquals(range.hashCode(), between(getOffsetDateTime(1), getOffsetDateTime(2)).hashCode());
			assertNotEquals(range.hashCode(), between(getOffsetDateTime(1), getOffsetDateTime(3)).hashCode());
		}

		@Test
		@DisplayName("Should construct since range")
		void shouldConstructFromOffsetDateTime() {
			final DateTimeRange range = since(getOffsetDateTime(1));
			assertEquals(getOffsetDateTime(1), range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1609514565000L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo(), "an absent upper bound is the offset-independent open sentinel");
			assertEquals("[2021-01-01T12:22:45-03:00,]", range.toString());
			assertEquals(range, since(getOffsetDateTime(1)));
			assertNotSame(range, since(getOffsetDateTime(1)));
			assertNotEquals(range, since(getOffsetDateTime(2)));
			assertEquals(range.hashCode(), since(getOffsetDateTime(1)).hashCode());
			assertNotEquals(range.hashCode(), since(getOffsetDateTime(2)).hashCode());
		}

		@Test
		@DisplayName("Should construct until range")
		void shouldConstructToOffsetDateTime() {
			final DateTimeRange range = until(getOffsetDateTime(1));
			assertEquals(getOffsetDateTime(1), range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1609514565000L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom(), "an absent lower bound is the offset-independent open sentinel");
			assertEquals("[,2021-01-01T12:22:45-03:00]", range.toString());
			assertEquals(range, until(getOffsetDateTime(1)));
			assertNotSame(range, until(getOffsetDateTime(1)));
			assertNotEquals(range, until(getOffsetDateTime(2)));
			assertEquals(range.hashCode(), until(getOffsetDateTime(1)).hashCode());
			assertNotEquals(range.hashCode(), until(getOffsetDateTime(2)).hashCode());
		}
	}

	@Nested
	@DisplayName("LocalDateTime construction")
	class LocalDateTimeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenLocalDateTime() {
			final DateTimeRange range = between(getLocalDateTime(1), getLocalDateTime(2), getZoneOffset());
			assertEquals(getLocalDateTime(1).atOffset(getZoneOffset()), range.getPreciseFrom());
			assertEquals(getLocalDateTime(2).atOffset(getZoneOffset()), range.getPreciseTo());
			assertEquals(1609514565000L, range.getFrom());
			assertEquals(1609600965000L, range.getTo());
			assertEquals("[2021-01-01T12:22:45-03:00,2021-01-02T12:22:45-03:00]", range.toString());
			assertEquals(range, between(getLocalDateTime(1), getLocalDateTime(2), getZoneOffset()));
			assertNotSame(range, between(getLocalDateTime(1), getLocalDateTime(2), getZoneOffset()));
			assertNotEquals(range, between(getLocalDateTime(1), getLocalDateTime(3), getZoneOffset()));
			assertNotEquals(range, between(getLocalDateTime(2), getLocalDateTime(2), getZoneOffset()));
			assertEquals(range.hashCode(), between(getLocalDateTime(1), getLocalDateTime(2), getZoneOffset()).hashCode());
			assertNotEquals(range.hashCode(), between(getLocalDateTime(1), getLocalDateTime(3), getZoneOffset()).hashCode());
		}

		@Test
		@DisplayName("Should construct since range")
		void shouldConstructFromLocalDateTime() {
			final DateTimeRange range = since(getLocalDateTime(1), getZoneOffset());
			assertEquals(getLocalDateTime(1).atOffset(getZoneOffset()), range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1609514565000L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo(), "an absent upper bound is the offset-independent open sentinel");
			assertEquals("[2021-01-01T12:22:45-03:00,]", range.toString());
			assertEquals(range, since(getLocalDateTime(1), getZoneOffset()));
			assertNotSame(range, since(getLocalDateTime(1), getZoneOffset()));
			assertNotEquals(range, since(getLocalDateTime(2), getZoneOffset()));
			assertEquals(range.hashCode(), since(getLocalDateTime(1), getZoneOffset()).hashCode());
			assertNotEquals(range.hashCode(), since(getLocalDateTime(2), getZoneOffset()).hashCode());
		}

		@Test
		@DisplayName("Should construct until range")
		void shouldConstructToLocalDateTime() {
			final DateTimeRange range = until(getLocalDateTime(1), getZoneOffset());
			assertEquals(getLocalDateTime(1).atOffset(getZoneOffset()), range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1609514565000L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom(), "an absent lower bound is the offset-independent open sentinel");
			assertEquals("[,2021-01-01T12:22:45-03:00]", range.toString());
			assertEquals(range, until(getLocalDateTime(1), getZoneOffset()));
			assertNotSame(range, until(getLocalDateTime(1), getZoneOffset()));
			assertNotEquals(range, until(getLocalDateTime(2), getZoneOffset()));
			assertEquals(range.hashCode(), until(getLocalDateTime(1), getZoneOffset()).hashCode());
			assertNotEquals(range.hashCode(), until(getLocalDateTime(2), getZoneOffset()).hashCode());
		}
	}

	@Nested
	@DisplayName("Comparison")
	class ComparisonTest {

		@Test
		@DisplayName("Should compare since ranges")
		void shouldCompareSinceRanges() {
			assertTrue(since(getOffsetDateTime(1)).compareTo(since(getOffsetDateTime(2))) < 0);
			assertEquals(0, since(getOffsetDateTime(1)).compareTo(since(getOffsetDateTime(1))));
			assertTrue(since(getOffsetDateTime(2)).compareTo(since(getOffsetDateTime(1))) > 0);
		}

		@Test
		@DisplayName("Should compare until ranges")
		void shouldCompareUntilRanges() {
			assertTrue(until(getOffsetDateTime(1)).compareTo(until(getOffsetDateTime(2))) < 0);
			assertEquals(0, until(getOffsetDateTime(1)).compareTo(until(getOffsetDateTime(1))));
			assertTrue(until(getOffsetDateTime(2)).compareTo(until(getOffsetDateTime(1))) > 0);
		}

		@Test
		@DisplayName("Should compare between ranges")
		void shouldCompareBetweenRanges() {
			assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(2), getOffsetDateTime(2))) < 0);
			assertEquals(0, between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))) > 0);
			assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(3))) < 0);
			assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(3)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))) > 0);
		}
	}

	@Nested
	@DisplayName("String parsing")
	class StringParsingTest {

		@Test
		@DisplayName("Should format and parse since range")
		void shouldFormatAndParseSinceRangeWithoutError() {
			final DateTimeRange sinceRange = since(getOffsetDateTime(1));
			assertEquals(sinceRange, DateTimeRange.fromString(sinceRange.toString()));
		}

		@Test
		@DisplayName("Should format and parse until range")
		void shouldFormatAndParseUntilRangeWithoutError() {
			final DateTimeRange untilRange = until(getOffsetDateTime(1));
			assertEquals(untilRange, DateTimeRange.fromString(untilRange.toString()));
		}

		@Test
		@DisplayName("Should format and parse between range")
		void shouldFormatAndParseBetweenRangeWithoutError() {
			final DateTimeRange betweenRange = between(getOffsetDateTime(1), getOffsetDateTime(5));
			assertEquals(betweenRange, DateTimeRange.fromString(betweenRange.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid formats")
		void shouldFailToParseInvalidFormats() {
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[a,b]"));
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[2021-01-01T12:22:45,2021-01-05T12:22:45]"));
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[2021-01-01T12:22:45-03:00]"));
			assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[2021-01-01T12:22:45-03:00,2021-01-02T12:22:45-03:00,2021-01-03T12:22:45-03:00]"));
		}

		@Test
		@DisplayName("Should parse valid format with offset")
		void shouldParseIncompleteFormat() {
			assertNotNull(DateTimeRange.fromString("[2021-01-01T12:22:45-03:00,2021-01-05T12:22:45-03:00]"));
		}

		@Test
		@DisplayName("Should correctly parse regex patterns")
		void shouldCorrectlyParseRegex() {
			assertArrayEquals(new String[]{"2021-01-01T12:22:45-03:00[America/Sao_Paulo]", "2021-01-02T12:22:45-03:00[America/Sao_Paulo]"}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00[America/Sao_Paulo],2021-01-02T12:22:45-03:00[America/Sao_Paulo]]"));
			assertArrayEquals(new String[]{"2021-01-01T12:22:45-03:00", "2021-01-05T12:22:45-03:00"}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00,2021-01-05T12:22:45-03:00]"));
			assertArrayEquals(new String[]{null, "2021-01-02T12:22:45-03:00[America/Sao_Paulo]"}, DateTimeRange.PARSE_FCT.apply("[,2021-01-02T12:22:45-03:00[America/Sao_Paulo]]"));
			assertArrayEquals(new String[]{"2021-01-01T12:22:45-03:00[America/Sao_Paulo]", null}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00[America/Sao_Paulo],]"));
		}
	}

	@Nested
	@DisplayName("isWithin")
	class IsWithinTest {

		@Test
		@DisplayName("Should check isWithin for between range")
		void shouldCheckIsWithinForBetweenRange() {
			final DateTimeRange range = between(getOffsetDateTime(2), getOffsetDateTime(6));
			assertTrue(range.isWithin(getOffsetDateTime(4)));
			assertTrue(range.isWithin(getOffsetDateTime(2)));
			assertTrue(range.isWithin(getOffsetDateTime(6)));
			assertFalse(range.isWithin(getOffsetDateTime(1)));
			assertFalse(range.isWithin(getOffsetDateTime(30)));
		}

		@Test
		@DisplayName("Should check isWithin for since range")
		void shouldCheckIsWithinForSinceRange() {
			final DateTimeRange range = since(getOffsetDateTime(5));
			assertTrue(range.isWithin(getOffsetDateTime(5)));
			assertTrue(range.isWithin(getOffsetDateTime(10)));
			assertFalse(range.isWithin(getOffsetDateTime(4)));
		}

		@Test
		@DisplayName("Should check isWithin for until range")
		void shouldCheckIsWithinForUntilRange() {
			final DateTimeRange range = until(getOffsetDateTime(5));
			assertTrue(range.isWithin(getOffsetDateTime(5)));
			assertTrue(range.isWithin(getOffsetDateTime(1)));
			assertFalse(range.isWithin(getOffsetDateTime(6)));
		}

		@Test
		@DisplayName("isValidFor should be equivalent to isWithin")
		void shouldBeEquivalentToIsValidFor() {
			// isValidFor is a legacy method that duplicates isWithin behavior
			final DateTimeRange range = between(getOffsetDateTime(2), getOffsetDateTime(6));
			final OffsetDateTime[] testValues = {
				getOffsetDateTime(1), getOffsetDateTime(2), getOffsetDateTime(4),
				getOffsetDateTime(6), getOffsetDateTime(30)
			};
			for (final OffsetDateTime value : testValues) {
				assertEquals(
					range.isWithin(value),
					range.isValidFor(value),
					"isWithin and isValidFor should return same result for " + value
				);
			}
		}

		@Test
		@DisplayName("isValidFor should work correctly for between range")
		void shouldBeValidIn() {
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(4)));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(2)));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(6)));
			assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(1)));
			assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(30)));
		}
	}

	@Nested
	@DisplayName("Overlaps")
	class OverlapsTest {

		@Test
		@DisplayName("Should compute overlaps correctly")
		void shouldComputeOverlapsCorrectly() {
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(3), getOffsetDateTime(4))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(2), getOffsetDateTime(6))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(1), getOffsetDateTime(2))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(1), getOffsetDateTime(3))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(6), getOffsetDateTime(8))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(5), getOffsetDateTime(8))));
			assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(1), getOffsetDateTime(10))));
			assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(1), getOffsetDateTime(1))));
			assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).overlaps(between(getOffsetDateTime(7), getOffsetDateTime(10))));
		}

		@Test
		@DisplayName("Should detect overlap of half-open ranges")
		void shouldDetectOverlapOfHalfOpenRanges() {
			assertTrue(since(getOffsetDateTime(3)).overlaps(until(getOffsetDateTime(5))));
			assertTrue(since(getOffsetDateTime(3)).overlaps(since(getOffsetDateTime(5))));
			assertTrue(until(getOffsetDateTime(5)).overlaps(until(getOffsetDateTime(3))));
		}
	}

	@Nested
	@DisplayName("Consolidation")
	class ConsolidationTest {

		@Test
		@DisplayName("Should consolidate overlapping ranges")
		void shouldConsolidateOverlappingRanges() {
			assertArrayEquals(
				new DateTimeRange[]{
					between(getOffsetDateTime(1), getOffsetDateTime(9)),
					between(getOffsetDateTime(25), getOffsetDateTime(31)),
				},
				Range.consolidateRange(
					new DateTimeRange[]{
						between(getOffsetDateTime(1), getOffsetDateTime(5)),
						between(getOffsetDateTime(25), getOffsetDateTime(31)),
						between(getOffsetDateTime(5), getOffsetDateTime(6)),
						between(getOffsetDateTime(3), getOffsetDateTime(9)),
					}
				)
			);
		}

		@Test
		@DisplayName("Should return empty array for empty input")
		void shouldReturnEmptyArrayForEmptyInput() {
			final DateTimeRange[] result = Range.consolidateRange(new DateTimeRange[0]);
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("Should return single range unchanged")
		void shouldReturnSingleRangeUnchanged() {
			final DateTimeRange[] input = new DateTimeRange[]{between(getOffsetDateTime(1), getOffsetDateTime(5))};
			final DateTimeRange[] result = Range.consolidateRange(input);
			assertEquals(1, result.length);
			assertEquals(between(getOffsetDateTime(1), getOffsetDateTime(5)), result[0]);
		}
	}

	@Nested
	@DisplayName("cloneWithDifferentBounds")
	class CloneWithDifferentBoundsTest {

		@Test
		@DisplayName("Should clone range with different bounds")
		void shouldCloneRangeWithDifferentBounds() {
			final DateTimeRange original = between(getOffsetDateTime(1), getOffsetDateTime(5));
			final Range<OffsetDateTime> cloned = original.cloneWithDifferentBounds(getOffsetDateTime(3), getOffsetDateTime(10));
			assertEquals(getOffsetDateTime(3), cloned.getPreciseFrom());
			assertEquals(getOffsetDateTime(10), cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone range with null lower bound")
		void shouldCloneRangeWithNullLowerBound() {
			final DateTimeRange original = between(getOffsetDateTime(1), getOffsetDateTime(5));
			final Range<OffsetDateTime> cloned = original.cloneWithDifferentBounds(null, getOffsetDateTime(10));
			assertNull(cloned.getPreciseFrom());
			assertEquals(getOffsetDateTime(10), cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone range with null upper bound")
		void shouldCloneRangeWithNullUpperBound() {
			final DateTimeRange original = between(getOffsetDateTime(1), getOffsetDateTime(5));
			final Range<OffsetDateTime> cloned = original.cloneWithDifferentBounds(getOffsetDateTime(3), null);
			assertEquals(getOffsetDateTime(3), cloned.getPreciseFrom());
			assertNull(cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should reject clone with both bounds null")
		void shouldRejectCloneWithBothBoundsNull() {
			final DateTimeRange original = between(getOffsetDateTime(1), getOffsetDateTime(5));
			assertThrows(IllegalArgumentException.class, () -> original.cloneWithDifferentBounds(null, null));
		}
	}

	/**
	 * The comparison scale itself: whole epoch milliseconds, with two offset-independent constants standing in for
	 * an absent bound. Ranges used to compare at whole seconds and to derive an open bound's value from the OTHER
	 * bound's zone offset, so these are the properties that changed.
	 */
	@Nested
	@DisplayName("Millisecond comparison scale")
	class MillisecondComparisonTest {

		@Test
		@DisplayName("Should compare at millisecond granularity")
		void shouldCompareAtMillisecondGranularity() {
			final OffsetDateTime base = OffsetDateTime.parse("2026-05-20T12:19:26.123Z");
			final DateTimeRange first = between(base, base.plusDays(1));
			// half a second apart: one range under the old whole-second scale, two under this one
			final DateTimeRange halfASecondLater = between(base.plusNanos(500_000_000L), base.plusDays(1));

			assertNotEquals(first, halfASecondLater, "ranges half a second apart are no longer the same range");
			assertNotEquals(first.hashCode(), halfASecondLater.hashCode());
			assertEquals(-1, Integer.signum(first.compareTo(halfASecondLater)), "and they order by the lower bound");
			assertEquals(
				first.getFrom() + 500L, halfASecondLater.getFrom(),
				"the two lower thresholds differ by exactly 500 milliseconds"
			);

			// ... but below the millisecond they still collapse, which is the guarantee every other temporal type
			// carries and the reason the index can key a range on two longs
			final DateTimeRange aNanosecondLater = between(base.plusNanos(1L), base.plusDays(1));
			assertEquals(first, aNanosecondLater, "ranges a nanosecond apart are one range");
			assertEquals(first.getFrom(), aNanosecondLater.getFrom());
		}

		@Test
		@DisplayName("Should give an absent bound an offset-independent constant")
		void shouldGiveAnAbsentBoundAnOffsetIndependentConstant() {
			final OffsetDateTime moment = OffsetDateTime.parse("2026-05-20T12:19:26.123Z");
			// the SAME instant expressed at three different offsets: an absent bound used to take a different
			// numeric value in each of these, so the three ranges were not equal to one another
			final DateTimeRange atUtc = until(moment);
			final DateTimeRange atPlusTwo = until(moment.withOffsetSameInstant(ZoneOffset.ofHours(2)));
			final DateTimeRange atMinusFive = until(moment.withOffsetSameInstant(ZoneOffset.ofHours(-5)));

			assertEquals(DateTimeRange.OPEN_FROM_THRESHOLD, atUtc.getFrom());
			assertEquals(DateTimeRange.OPEN_FROM_THRESHOLD, atPlusTwo.getFrom());
			assertEquals(DateTimeRange.OPEN_FROM_THRESHOLD, atMinusFive.getFrom());
			assertEquals(atUtc, atPlusTwo, "one instant written at two offsets is one open-ended range");
			assertEquals(atUtc, atMinusFive);
			assertEquals(atUtc.hashCode(), atPlusTwo.hashCode());

			final DateTimeRange sinceAtUtc = since(moment);
			final DateTimeRange sinceAtPlusTwo = since(moment.withOffsetSameInstant(ZoneOffset.ofHours(2)));
			assertEquals(DateTimeRange.OPEN_TO_THRESHOLD, sinceAtUtc.getTo());
			assertEquals(DateTimeRange.OPEN_TO_THRESHOLD, sinceAtPlusTwo.getTo());
			assertEquals(sinceAtUtc, sinceAtPlusTwo);

			// the sentinels are exactly the two the NumberRange family already spends on its own open bounds
			assertEquals(Long.MIN_VALUE, DateTimeRange.OPEN_FROM_THRESHOLD);
			assertEquals(Long.MAX_VALUE, DateTimeRange.OPEN_TO_THRESHOLD);
			assertEquals(Long.MIN_VALUE, IntegerNumberRange.to(10).getFrom());
			assertEquals(Long.MAX_VALUE, IntegerNumberRange.from(10).getTo());
		}

		@Test
		@DisplayName("Should keep an open-ended range answering isWithin on both sides")
		void shouldKeepAnOpenEndedRangeAnsweringIsWithin() {
			final OffsetDateTime moment = OffsetDateTime.parse("2026-05-20T12:19:26.123Z");
			final DateTimeRange openTo = since(moment);
			final DateTimeRange openFrom = until(moment);

			assertTrue(openTo.isWithin(moment), "the closed bound is inclusive");
			assertTrue(openTo.isWithin(moment.plusYears(1_000)));
			assertFalse(openTo.isWithin(moment.minusNanos(1_000_000L)));
			assertTrue(openFrom.isWithin(moment));
			assertTrue(openFrom.isWithin(moment.minusYears(1_000)));
			assertFalse(openFrom.isWithin(moment.plusNanos(1_000_000L)));
		}

		@Test
		@DisplayName("Should NOT saturate a bound sitting exactly on the representable window edge")
		void shouldNotSaturateABoundOnTheRepresentableWindowEdge() {
			// the sibling below proves the extremes saturate; on its own it would also pass with the window edges
			// placed anywhere at all. These four assertions sit ON the edge and one second past it, so a `<` / `<=`
			// slip or a mis-derived constant moves one of them
			final OffsetDateTime atUpperEdge = OffsetDateTime.ofInstant(
				Instant.ofEpochSecond(DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND), ZoneOffset.UTC
			);
			assertEquals(
				DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND * 1000L,
				DateTimeRange.toComparableLong(atUpperEdge).longValue(),
				"the highest representable second is a real moment and must be multiplied, not saturated"
			);
			assertEquals(
				DateTimeRange.SATURATED_TO_THRESHOLD,
				DateTimeRange.toComparableLong(atUpperEdge.plusSeconds(1L)).longValue(),
				"one second above it saturates"
			);

			final OffsetDateTime atLowerEdge = OffsetDateTime.ofInstant(
				Instant.ofEpochSecond(DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND), ZoneOffset.UTC
			);
			assertEquals(
				DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND * 1000L,
				DateTimeRange.toComparableLong(atLowerEdge).longValue(),
				"the lowest representable second is a real moment and must be multiplied, not saturated"
			);
			assertEquals(
				DateTimeRange.SATURATED_FROM_THRESHOLD,
				DateTimeRange.toComparableLong(atLowerEdge.minusSeconds(1L)).longValue(),
				"one second below it saturates"
			);

			// and a range built from the two edges keeps them apart from the saturation sentinels
			final DateTimeRange acrossTheWholeWindow = between(atLowerEdge, atUpperEdge);
			assertNotEquals(DateTimeRange.SATURATED_FROM_THRESHOLD, acrossTheWholeWindow.getFrom());
			assertNotEquals(DateTimeRange.SATURATED_TO_THRESHOLD, acrossTheWholeWindow.getTo());
		}

		@Test
		@DisplayName("Should compare a pre-epoch bound at millisecond granularity, epoch-straddling range included")
		void shouldComparePreEpochBoundsAtMillisecondGranularity() {
			// nothing else in this class uses a negative epoch, so the sign handling of the ×1000 expansion and of the
			// nanosecond remainder added on top of it is otherwise unexercised. A moment half a second before the
			// epoch has epoch SECOND -1 and nanosecond 500 000 000, so the two halves have opposite signs and only
			// their sum is right
			final OffsetDateTime halfASecondBeforeEpoch = Instant.ofEpochMilli(-500L).atOffset(ZoneOffset.UTC);
			assertEquals(-1L, halfASecondBeforeEpoch.toEpochSecond(), "the fixture must straddle a second boundary");
			assertEquals(500_000_000, halfASecondBeforeEpoch.getNano());
			assertEquals(
				-500L, DateTimeRange.toComparableLong(halfASecondBeforeEpoch).longValue(),
				"a pre-epoch moment reduces to a negative epoch millisecond"
			);

			// a range straddling the epoch orders and compares by those longs like any other
			final OffsetDateTime justAfterEpoch = Instant.ofEpochMilli(250L).atOffset(ZoneOffset.UTC);
			final DateTimeRange straddling = between(halfASecondBeforeEpoch, justAfterEpoch);
			assertEquals(-500L, straddling.getFrom());
			assertEquals(250L, straddling.getTo());
			assertTrue(straddling.isWithin(Instant.EPOCH.atOffset(ZoneOffset.UTC)), "the epoch itself is inside");
			assertTrue(straddling.isWithin(halfASecondBeforeEpoch), "the lower bound is inclusive");
			assertFalse(straddling.isWithin(Instant.ofEpochMilli(-501L).atOffset(ZoneOffset.UTC)));
			assertFalse(straddling.isWithin(Instant.ofEpochMilli(251L).atOffset(ZoneOffset.UTC)));

			// and the millisecond collapse holds on the negative side too, where a truncating division would round
			// the wrong way
			final DateTimeRange aNanosecondLater =
				between(halfASecondBeforeEpoch.plusNanos(1L), justAfterEpoch);
			assertEquals(straddling, aNanosecondLater, "bounds a nanosecond apart are one range before the epoch too");
			final DateTimeRange aMillisecondEarlier =
				between(halfASecondBeforeEpoch.minusNanos(1_000_000L), justAfterEpoch);
			assertEquals(-501L, aMillisecondEarlier.getFrom(), "and a whole millisecond earlier really is another one");
			assertEquals(1, Integer.signum(straddling.compareTo(aMillisecondEarlier)), "which orders after it");
		}

		@Test
		@DisplayName("Should saturate a bound beyond the representable millisecond window instead of overflowing")
		void shouldSaturateABoundBeyondTheRepresentableWindow() {
			// `OffsetDateTime` reaches year +-999999999, whose epoch second is ~3.16e16 - a thousand times that does
			// not fit a `long`. The extremes must therefore build rather than overflow, and must stay one step
			// inside the open-bound sentinels so a closed bound is never read back as an open one
			final DateTimeRange whole = between(OffsetDateTime.MIN, OffsetDateTime.MAX);
			assertEquals(Long.MIN_VALUE + 1, whole.getFrom(), "a bound below the window saturates one step inside");
			assertEquals(Long.MAX_VALUE - 1, whole.getTo(), "a bound above the window saturates one step inside");
			assertNotEquals(DateTimeRange.OPEN_FROM_THRESHOLD, whole.getFrom(), "and is still a CLOSED bound");
			assertNotEquals(DateTimeRange.OPEN_TO_THRESHOLD, whole.getTo());
			assertNotNull(whole.getPreciseFrom(), "the precise bounds are kept as given");
			assertNotNull(whole.getPreciseTo());

			// the saturated range still behaves as the everything-range it names
			assertTrue(whole.isWithin(OffsetDateTime.parse("2026-05-20T12:19:26.123Z")));

			final DateTimeRange upToTheEndOfTime = between(OffsetDateTime.parse("2026-01-01T00:00:00Z"), OffsetDateTime.MAX);
			assertEquals(Long.MAX_VALUE - 1, upToTheEndOfTime.getTo());
			assertEquals(
				DateTimeRange.toComparableLong(OffsetDateTime.parse("2026-01-01T00:00:00Z")),
				upToTheEndOfTime.getFrom(), "the ordinary bound beside it is unaffected"
			);
		}
	}

	private OffsetDateTime getOffsetDateTime(int day) {
		return OffsetDateTime.of(2021, 1, day, 12, 22, 45, 0, getZoneOffset());
	}

	private LocalDateTime getLocalDateTime(int day) {
		return LocalDateTime.of(2021, 1, day, 12, 22, 45, 0);
	}

	private ZoneOffset getZoneOffset() {
		return ZoneId.of("America/Sao_Paulo").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0));
	}

}
