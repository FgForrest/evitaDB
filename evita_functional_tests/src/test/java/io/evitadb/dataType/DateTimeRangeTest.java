/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
 * Checks creation of the {@link DateTimeRange} data type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DateTimeRangeTest {

	@Test
	void shouldFailToConstructUnreasonableRange() {
		assertThrows(IllegalArgumentException.class, () -> between(null, null));
	}

	@Test
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

	@Test
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

	@Test
	void shouldCompareRanges() {
		assertTrue(since(getOffsetDateTime(1)).compareTo(since(getOffsetDateTime(2))) < 0);
		assertEquals(0, since(getOffsetDateTime(1)).compareTo(since(getOffsetDateTime(1))));
		assertTrue(since(getOffsetDateTime(2)).compareTo(since(getOffsetDateTime(1))) > 0);

		assertTrue(until(getOffsetDateTime(1)).compareTo(until(getOffsetDateTime(2))) < 0);
		assertEquals(0, until(getOffsetDateTime(1)).compareTo(until(getOffsetDateTime(1))));
		assertTrue(until(getOffsetDateTime(2)).compareTo(until(getOffsetDateTime(1))) > 0);

		assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(2), getOffsetDateTime(2))) < 0);
		assertEquals(0, between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))));
		assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))) > 0);
		assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(2)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(3))) < 0);
		assertTrue(between(getOffsetDateTime(1), getOffsetDateTime(3)).compareTo(between(getOffsetDateTime(1), getOffsetDateTime(2))) > 0);
	}

	@Test
	void shouldFormatAndParseSinceRangeWithoutError() {
		final DateTimeRange since = since(getOffsetDateTime(1));
		assertEquals(since, DateTimeRange.fromString(since.toString()));
	}

	@Test
	void shouldFormatAndParseUntilRangeWithoutError() {
		final DateTimeRange until = until(getOffsetDateTime(1));
		assertEquals(until, DateTimeRange.fromString(until.toString()));
	}

	@Test
	void shouldFormatAndParseBetweenRangeWithoutError() {
		final DateTimeRange between = between(getOffsetDateTime(1), getOffsetDateTime(5));
		assertEquals(between, DateTimeRange.fromString(between.toString()));
	}

	@Test
	void shouldFailToParseInvalidFormats() {
		assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString(""));
		assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[,]"));
		assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[a,b]"));
		assertThrows(DataTypeParseException.class, () -> DateTimeRange.fromString("[2021-01-01T12:22:45,2021-01-05T12:22:45]"));
	}

	@Test
	void shouldParseIncompleteFormat() {
		assertNotNull(DateTimeRange.fromString("[2021-01-01T12:22:45-03:00,2021-01-05T12:22:45-03:00]"));
	}

	@Test
	void shouldBeValidIn() {
		assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(4)));
		assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(2)));
		assertTrue(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(6)));
		assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(1)));
		assertFalse(between(getOffsetDateTime(2), getOffsetDateTime(6)).isValidFor(getOffsetDateTime(30)));
	}

	@Test
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
	void shouldCorrectlyParseRegex() {
		assertArrayEquals(new String[] {"2021-01-01T12:22:45-03:00[America/Sao_Paulo]", "2021-01-02T12:22:45-03:00[America/Sao_Paulo]"}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00[America/Sao_Paulo],2021-01-02T12:22:45-03:00[America/Sao_Paulo]]"));
		assertArrayEquals(new String[] {"2021-01-01T12:22:45-03:00", "2021-01-05T12:22:45-03:00"}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00,2021-01-05T12:22:45-03:00]"));
		assertArrayEquals(new String[] {null, "2021-01-02T12:22:45-03:00[America/Sao_Paulo]"}, DateTimeRange.PARSE_FCT.apply("[,2021-01-02T12:22:45-03:00[America/Sao_Paulo]]"));
		assertArrayEquals(new String[] {"2021-01-01T12:22:45-03:00[America/Sao_Paulo]", null}, DateTimeRange.PARSE_FCT.apply("[2021-01-01T12:22:45-03:00[America/Sao_Paulo],]"));
	}

	@Test
	void shouldConsolidateOverlappingRanges() {
		assertArrayEquals(
			new DateTimeRange[] {
				between(getOffsetDateTime(1), getOffsetDateTime(9)),
				between(getOffsetDateTime(25), getOffsetDateTime(31)),
			},
			Range.consolidateRange(
				new DateTimeRange[] {
					between(getOffsetDateTime(1), getOffsetDateTime(5)),
					between(getOffsetDateTime(25), getOffsetDateTime(31)),
					between(getOffsetDateTime(5), getOffsetDateTime(6)),
					between(getOffsetDateTime(3), getOffsetDateTime(9)),
				}
			)
		);
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
