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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static io.evitadb.dataType.DateTimeRange.between;
import static io.evitadb.dataType.DateTimeRange.since;
import static io.evitadb.dataType.DateTimeRange.until;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks creation and behavior of the {@link DateTimeRange} data type.
 *
 * @author Jan Novotn\u00fd (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DateTimeRange")
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
		@DisplayName("Should construct between range")
		void shouldConstructBetweenOffsetDateTime() {
			final DateTimeRange range = between(getOffsetDateTime(1), getOffsetDateTime(2));
			assertEquals(getOffsetDateTime(1), range.getPreciseFrom());
			assertEquals(getOffsetDateTime(2), range.getPreciseTo());
			assertEquals(1609514565L, range.getFrom());
			assertEquals(1609600965L, range.getTo());
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
			assertEquals(1609514565L, range.getFrom());
			assertEquals(31556889832791599L, range.getTo());
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
			assertEquals(1609514565L, range.getTo());
			assertEquals(-31557014135586000L, range.getFrom());
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
			assertEquals(1609514565L, range.getFrom());
			assertEquals(1609600965L, range.getTo());
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
			assertEquals(1609514565L, range.getFrom());
			assertEquals(31556889832791599L, range.getTo());
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
			assertEquals(1609514565L, range.getTo());
			assertEquals(-31557014135586000L, range.getFrom());
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
