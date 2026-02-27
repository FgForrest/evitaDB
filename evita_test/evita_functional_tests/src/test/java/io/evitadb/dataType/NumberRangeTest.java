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

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks creation and behavior of the {@link NumberRange} data type hierarchy.
 *
 * @author Jan Novotn\u00fd (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("NumberRange hierarchy")
class NumberRangeTest {

	@Nested
	@DisplayName("Construction validation")
	class ConstructionValidationTest {

		@Test
		@DisplayName("Should fail to construct range with both bounds null")
		void shouldFailToConstructUnreasonableRange() {
			assertThrows(EvitaInvalidUsageException.class, () -> BigDecimalNumberRange.between(null, null));
			assertThrows(EvitaInvalidUsageException.class, () -> LongNumberRange.between(null, null));
			assertThrows(EvitaInvalidUsageException.class, () -> IntegerNumberRange.between(null, null));
			assertThrows(EvitaInvalidUsageException.class, () -> ShortNumberRange.between(null, null));
			assertThrows(EvitaInvalidUsageException.class, () -> ByteNumberRange.between(null, null));
		}

		@Test
		@DisplayName("Should reject from greater than to for Integer")
		void shouldRejectFromGreaterThanToForInteger() {
			assertThrows(EvitaInvalidUsageException.class, () -> IntegerNumberRange.between(10, 5));
		}

		@Test
		@DisplayName("Should reject from greater than to for Long")
		void shouldRejectFromGreaterThanToForLong() {
			assertThrows(EvitaInvalidUsageException.class, () -> LongNumberRange.between(10L, 5L));
		}

		@Test
		@DisplayName("Should reject from greater than to for Short")
		void shouldRejectFromGreaterThanToForShort() {
			assertThrows(EvitaInvalidUsageException.class, () -> ShortNumberRange.between((short) 10, (short) 5));
		}

		@Test
		@DisplayName("Should reject from greater than to for Byte")
		void shouldRejectFromGreaterThanToForByte() {
			assertThrows(EvitaInvalidUsageException.class, () -> ByteNumberRange.between((byte) 10, (byte) 5));
		}

		@Test
		@DisplayName("Should reject from greater than to for BigDecimal")
		void shouldRejectFromGreaterThanToForBigDecimal() {
			assertThrows(EvitaInvalidUsageException.class, () -> BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("5")));
		}
	}

	@Nested
	@DisplayName("ByteNumberRange construction")
	class ByteNumberRangeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenByte() {
			final ByteNumberRange range = ByteNumberRange.between((byte) 1, (byte) 2);
			assertEquals((byte) 1, range.getPreciseFrom());
			assertEquals((byte) 2, range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(2L, range.getTo());
			assertEquals("[1,2]", range.toString());
			assertEquals(range, ByteNumberRange.between((byte) 1, (byte) 2));
			assertNotSame(range, ByteNumberRange.between((byte) 1, (byte) 2));
			assertNotEquals(range, ByteNumberRange.between((byte) 1, (byte) 3));
			assertNotEquals(range, ByteNumberRange.between((byte) 2, (byte) 2));
			assertEquals(range.hashCode(), ByteNumberRange.between((byte) 1, (byte) 2).hashCode());
			assertNotEquals(range.hashCode(), ByteNumberRange.between((byte) 1, (byte) 3).hashCode());
		}

		@Test
		@DisplayName("Should construct from range")
		void shouldConstructFromByte() {
			final ByteNumberRange range = ByteNumberRange.from((byte) 1);
			assertEquals((byte) 1, range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1,]", range.toString());
			assertEquals(range, ByteNumberRange.from((byte) 1));
			assertNotSame(range, ByteNumberRange.from((byte) 1));
			assertNotEquals(range, ByteNumberRange.from((byte) 2));
			assertEquals(range.hashCode(), ByteNumberRange.from((byte) 1).hashCode());
			assertNotEquals(range.hashCode(), ByteNumberRange.from((byte) 2).hashCode());
		}

		@Test
		@DisplayName("Should construct to range")
		void shouldConstructToByte() {
			final ByteNumberRange range = ByteNumberRange.to((byte) 1);
			assertEquals((byte) 1, range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1]", range.toString());
			assertEquals(range, ByteNumberRange.to((byte) 1));
			assertNotSame(range, ByteNumberRange.to((byte) 1));
			assertNotEquals(range, ByteNumberRange.to((byte) 2));
			assertEquals(range.hashCode(), ByteNumberRange.to((byte) 1).hashCode());
			assertNotEquals(range.hashCode(), ByteNumberRange.to((byte) 2).hashCode());
		}

		@Test
		@DisplayName("Should construct single-point range")
		void shouldConstructSinglePointByte() {
			final ByteNumberRange range = ByteNumberRange.between((byte) 5, (byte) 5);
			assertEquals((byte) 5, range.getPreciseFrom());
			assertEquals((byte) 5, range.getPreciseTo());
			assertEquals(5L, range.getFrom());
			assertEquals(5L, range.getTo());
			assertEquals("[5,5]", range.toString());
		}
	}

	@Nested
	@DisplayName("ShortNumberRange construction")
	class ShortNumberRangeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenShort() {
			final ShortNumberRange range = ShortNumberRange.between((short) 1, (short) 2);
			assertEquals((short) 1, range.getPreciseFrom());
			assertEquals((short) 2, range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(2L, range.getTo());
			assertEquals("[1,2]", range.toString());
			assertEquals(range, ShortNumberRange.between((short) 1, (short) 2));
			assertNotSame(range, ShortNumberRange.between((short) 1, (short) 2));
			assertNotEquals(range, ShortNumberRange.between((short) 1, (short) 3));
			assertNotEquals(range, ShortNumberRange.between((short) 2, (short) 2));
			assertEquals(range.hashCode(), ShortNumberRange.between((short) 1, (short) 2).hashCode());
			assertNotEquals(range.hashCode(), ShortNumberRange.between((short) 1, (short) 3).hashCode());
		}

		@Test
		@DisplayName("Should construct from range")
		void shouldConstructFromShort() {
			final ShortNumberRange range = ShortNumberRange.from((short) 1);
			assertEquals((short) 1, range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1,]", range.toString());
			assertEquals(range, ShortNumberRange.from((short) 1));
			assertNotSame(range, ShortNumberRange.from((short) 1));
			assertNotEquals(range, ShortNumberRange.from((short) 2));
			assertEquals(range.hashCode(), ShortNumberRange.from((short) 1).hashCode());
			assertNotEquals(range.hashCode(), ShortNumberRange.from((short) 2).hashCode());
		}

		@Test
		@DisplayName("Should construct to range")
		void shouldConstructToShort() {
			final ShortNumberRange range = ShortNumberRange.to((short) 1);
			assertEquals((short) 1, range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1]", range.toString());
			assertEquals(range, ShortNumberRange.to((short) 1));
			assertNotSame(range, ShortNumberRange.to((short) 1));
			assertNotEquals(range, ShortNumberRange.to((short) 2));
			assertEquals(range.hashCode(), ShortNumberRange.to((short) 1).hashCode());
			assertNotEquals(range.hashCode(), ShortNumberRange.to((short) 2).hashCode());
		}

		@Test
		@DisplayName("Should construct single-point range")
		void shouldConstructSinglePointShort() {
			final ShortNumberRange range = ShortNumberRange.between((short) 5, (short) 5);
			assertEquals((short) 5, range.getPreciseFrom());
			assertEquals((short) 5, range.getPreciseTo());
			assertEquals(5L, range.getFrom());
			assertEquals(5L, range.getTo());
		}
	}

	@Nested
	@DisplayName("IntegerNumberRange construction")
	class IntegerNumberRangeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenInt() {
			final IntegerNumberRange range = IntegerNumberRange.between(1, 2);
			assertEquals(1, range.getPreciseFrom());
			assertEquals(2, range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(2L, range.getTo());
			assertEquals("[1,2]", range.toString());
			assertEquals(range, IntegerNumberRange.between(1, 2));
			assertNotSame(range, IntegerNumberRange.between(1, 2));
			assertNotEquals(range, IntegerNumberRange.between(1, 3));
			assertNotEquals(range, IntegerNumberRange.between(2, 2));
			assertEquals(range.hashCode(), IntegerNumberRange.between(1, 2).hashCode());
			assertNotEquals(range.hashCode(), IntegerNumberRange.between(1, 3).hashCode());
		}

		@Test
		@DisplayName("Should construct from range")
		void shouldConstructFromInt() {
			final IntegerNumberRange range = IntegerNumberRange.from(1);
			assertEquals(1, range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1,]", range.toString());
			assertEquals(range, IntegerNumberRange.from(1));
			assertNotSame(range, IntegerNumberRange.from(1));
			assertNotEquals(range, IntegerNumberRange.from(2));
			assertEquals(range.hashCode(), IntegerNumberRange.from(1).hashCode());
			assertNotEquals(range.hashCode(), IntegerNumberRange.from(2).hashCode());
		}

		@Test
		@DisplayName("Should construct to range")
		void shouldConstructToInt() {
			final IntegerNumberRange range = IntegerNumberRange.to(1);
			assertEquals(1, range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1]", range.toString());
			assertEquals(range, IntegerNumberRange.to(1));
			assertNotSame(range, IntegerNumberRange.to(1));
			assertNotEquals(range, IntegerNumberRange.to(2));
			assertEquals(range.hashCode(), IntegerNumberRange.to(1).hashCode());
			assertNotEquals(range.hashCode(), IntegerNumberRange.to(2).hashCode());
		}

		@Test
		@DisplayName("Should construct single-point range")
		void shouldConstructSinglePointInt() {
			final IntegerNumberRange range = IntegerNumberRange.between(5, 5);
			assertEquals(5, range.getPreciseFrom());
			assertEquals(5, range.getPreciseTo());
			assertEquals(5L, range.getFrom());
			assertEquals(5L, range.getTo());
		}
	}

	@Nested
	@DisplayName("LongNumberRange construction")
	class LongNumberRangeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenLong() {
			final LongNumberRange range = LongNumberRange.between(1L, 2L);
			assertEquals(1L, range.getPreciseFrom());
			assertEquals(2L, range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(2L, range.getTo());
			assertEquals("[1,2]", range.toString());
			assertEquals(range, LongNumberRange.between(1L, 2L));
			assertNotSame(range, LongNumberRange.between(1L, 2L));
			assertNotEquals(range, LongNumberRange.between(1L, 3L));
			assertNotEquals(range, LongNumberRange.between(2L, 2L));
			assertEquals(range.hashCode(), LongNumberRange.between(1L, 2L).hashCode());
			assertNotEquals(range.hashCode(), LongNumberRange.between(1L, 3L).hashCode());
		}

		@Test
		@DisplayName("Should construct from range")
		void shouldConstructFromLong() {
			final LongNumberRange range = LongNumberRange.from(1L);
			assertEquals(1L, range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1,]", range.toString());
			assertEquals(range, LongNumberRange.from(1L));
			assertNotSame(range, LongNumberRange.from(1L));
			assertNotEquals(range, LongNumberRange.from(2L));
			assertEquals(range.hashCode(), LongNumberRange.from(1L).hashCode());
			assertNotEquals(range.hashCode(), LongNumberRange.from(2L).hashCode());
		}

		@Test
		@DisplayName("Should construct to range")
		void shouldConstructToLong() {
			final LongNumberRange range = LongNumberRange.to(1L);
			assertEquals(1L, range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1]", range.toString());
			assertEquals(range, LongNumberRange.to(1L));
			assertNotSame(range, LongNumberRange.to(1L));
			assertNotEquals(range, LongNumberRange.to(2L));
			assertEquals(range.hashCode(), LongNumberRange.to(1L).hashCode());
			assertNotEquals(range.hashCode(), LongNumberRange.to(2L).hashCode());
		}

		@Test
		@DisplayName("Should construct single-point range")
		void shouldConstructSinglePointLong() {
			final LongNumberRange range = LongNumberRange.between(5L, 5L);
			assertEquals(5L, range.getPreciseFrom());
			assertEquals(5L, range.getPreciseTo());
			assertEquals(5L, range.getFrom());
			assertEquals(5L, range.getTo());
		}
	}

	@Nested
	@DisplayName("BigDecimalNumberRange construction")
	class BigDecimalNumberRangeConstructionTest {

		@Test
		@DisplayName("Should construct between range")
		void shouldConstructBetweenBigDecimal() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(2));
			assertEquals(new BigDecimal(1), range.getPreciseFrom());
			assertEquals(new BigDecimal(2), range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(2L, range.getTo());
			assertEquals("[1,2]", range.toString());
			assertEquals(range, BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(2)));
			assertNotSame(range, BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(2)));
			assertNotEquals(range, BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(3)));
			assertNotEquals(range, BigDecimalNumberRange.between(new BigDecimal(2), new BigDecimal(2)));
			assertEquals(range.hashCode(), BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(2)).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.between(new BigDecimal(1), new BigDecimal(3)).hashCode());
		}

		@Test
		@DisplayName("Should construct from range")
		void shouldConstructFromBigDecimal() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.from(new BigDecimal(1));
			assertEquals(new BigDecimal(1), range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(1L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1,]", range.toString());
			assertEquals(range, BigDecimalNumberRange.from(new BigDecimal(1)));
			assertNotSame(range, BigDecimalNumberRange.from(new BigDecimal(1)));
			assertNotEquals(range, BigDecimalNumberRange.from(new BigDecimal(2)));
			assertEquals(range.hashCode(), BigDecimalNumberRange.from(new BigDecimal(1)).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.from(new BigDecimal(2)).hashCode());
		}

		@Test
		@DisplayName("Should construct to range")
		void shouldConstructToBigDecimal() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.to(new BigDecimal(1));
			assertEquals(new BigDecimal(1), range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(1L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1]", range.toString());
			assertEquals(range, BigDecimalNumberRange.to(new BigDecimal(1)));
			assertNotSame(range, BigDecimalNumberRange.to(new BigDecimal(1)));
			assertNotEquals(range, BigDecimalNumberRange.to(new BigDecimal(2)));
			assertEquals(range.hashCode(), BigDecimalNumberRange.to(new BigDecimal(1)).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.to(new BigDecimal(2)).hashCode());
		}

		@Test
		@DisplayName("Should construct between range with fractional part rounding")
		void shouldConstructBetweenBigDecimalWithFractionalPartRounding() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.between(toBD("1.125"), toBD("2.125"), 2);
			assertEquals(toBD("1.125"), range.getPreciseFrom());
			assertEquals(toBD("2.125"), range.getPreciseTo());
			assertEquals(113L, range.getFrom());
			assertEquals(213L, range.getTo());
			assertEquals("[1.125,2.125]", range.toString());
			assertEquals(range, BigDecimalNumberRange.between(toBD("1.125"), toBD("2.125"), 2));
			assertNotSame(range, BigDecimalNumberRange.between(toBD("1.125"), toBD("2.125"), 2));
			assertNotEquals(range, BigDecimalNumberRange.between(toBD("1.125"), toBD("3.125"), 2));
			assertNotEquals(range, BigDecimalNumberRange.between(toBD("2.125"), toBD("2.125"), 2));
			assertEquals(range.hashCode(), BigDecimalNumberRange.between(toBD("1.125"), toBD("2.125"), 2).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.between(toBD("1.125"), toBD("3.125"), 2).hashCode());
		}

		@Test
		@DisplayName("Should construct from range with fractional part rounding")
		void shouldConstructFromBigDecimalFractionalPartRounding() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.from(toBD("1.125"), 2);
			assertEquals(toBD("1.125"), range.getPreciseFrom());
			assertNull(range.getPreciseTo());
			assertEquals(113L, range.getFrom());
			assertEquals(Long.MAX_VALUE, range.getTo());
			assertEquals("[1.125,]", range.toString());
			assertEquals(range, BigDecimalNumberRange.from(toBD("1.125"), 2));
			assertNotSame(range, BigDecimalNumberRange.from(toBD("1.125"), 2));
			assertNotEquals(range, BigDecimalNumberRange.from(toBD("2.125"), 2));
			assertEquals(range.hashCode(), BigDecimalNumberRange.from(toBD("1.125"), 2).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.from(toBD("2.125"), 2).hashCode());
		}

		@Test
		@DisplayName("Should construct to range with fractional part rounding")
		void shouldConstructToBigDecimalFractionalPartRounding() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.to(toBD("1.125"), 2);
			assertEquals(toBD("1.125"), range.getPreciseTo());
			assertNull(range.getPreciseFrom());
			assertEquals(113L, range.getTo());
			assertEquals(Long.MIN_VALUE, range.getFrom());
			assertEquals("[,1.125]", range.toString());
			assertEquals(range, BigDecimalNumberRange.to(toBD("1.125"), 2));
			assertNotSame(range, BigDecimalNumberRange.to(toBD("1.125"), 2));
			assertNotEquals(range, BigDecimalNumberRange.to(toBD("2.125"), 2));
			assertEquals(range.hashCode(), BigDecimalNumberRange.to(toBD("1.125"), 2).hashCode());
			assertNotEquals(range.hashCode(), BigDecimalNumberRange.to(toBD("2.125"), 2).hashCode());
		}

		@Test
		@DisplayName("Should resolve default retained decimal places")
		void shouldResolveDefaultRetainedDecimalPlacesForBigDecimals() {
			final BigDecimalNumberRange between = BigDecimalNumberRange.between(BigDecimal.valueOf(10.23452), BigDecimal.valueOf(20.23));
			assertEquals(1023452, between.getFrom());
			assertEquals(2023000, between.getTo());

			final BigDecimalNumberRange from = BigDecimalNumberRange.from(BigDecimal.valueOf(10.23452));
			assertEquals(1023452, from.getFrom());

			final BigDecimalNumberRange to = BigDecimalNumberRange.to(BigDecimal.valueOf(20.23));
			assertEquals(2023, to.getTo());
		}

		@Test
		@DisplayName("Should resolve default retained decimal places for integer BigDecimals")
		void shouldResolveDefaultRetainedDecimalPlacesForIntegerBigDecimals() {
			final BigDecimalNumberRange between = BigDecimalNumberRange.between(BigDecimal.valueOf(10), BigDecimal.valueOf(20));
			assertEquals(10, between.getFrom());
			assertEquals(20, between.getTo());

			final BigDecimalNumberRange from = BigDecimalNumberRange.from(BigDecimal.valueOf(10));
			assertEquals(10, from.getFrom());

			final BigDecimalNumberRange to = BigDecimalNumberRange.to(BigDecimal.valueOf(20));
			assertEquals(20, to.getTo());
		}

		@Test
		@DisplayName("Should construct single-point range")
		void shouldConstructSinglePointBigDecimal() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal("5.0"), new BigDecimal("5.0"));
			assertEquals(new BigDecimal("5.0"), range.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), range.getPreciseTo());
		}
	}

	@Nested
	@DisplayName("Comparison")
	class ComparisonTest {

		@Test
		@DisplayName("Should compare from ranges")
		void shouldCompareFromRanges() {
			assertTrue(IntegerNumberRange.from(1).compareTo(IntegerNumberRange.from(2)) < 0);
			assertEquals(0, IntegerNumberRange.from(1).compareTo(IntegerNumberRange.from(1)));
			assertTrue(IntegerNumberRange.from(2).compareTo(IntegerNumberRange.from(1)) > 0);
		}

		@Test
		@DisplayName("Should compare to ranges")
		void shouldCompareToRanges() {
			assertTrue(IntegerNumberRange.to(1).compareTo(IntegerNumberRange.to(2)) < 0);
			assertEquals(0, IntegerNumberRange.to(1).compareTo(IntegerNumberRange.to(1)));
			assertTrue(IntegerNumberRange.to(2).compareTo(IntegerNumberRange.to(1)) > 0);
		}

		@Test
		@DisplayName("Should compare between ranges")
		void shouldCompareBetweenRanges() {
			assertTrue(IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(2, 2)) < 0);
			assertEquals(0, IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(1, 2)));
			assertTrue(IntegerNumberRange.between(2, 2).compareTo(IntegerNumberRange.between(1, 2)) > 0);
			assertTrue(IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(1, 3)) < 0);
			assertTrue(IntegerNumberRange.between(1, 3).compareTo(IntegerNumberRange.between(1, 2)) > 0);
		}
	}

	@Nested
	@DisplayName("String parsing")
	class StringParsingTest {

		@Test
		@DisplayName("Should format and parse BigDecimal from range")
		void shouldFormatAndParseFromRangeWithoutError() {
			final BigDecimalNumberRange from = BigDecimalNumberRange.from(toBD("1.123"), 3);
			assertEquals(from, BigDecimalNumberRange.fromString(from.toString()));
		}

		@Test
		@DisplayName("Should format and parse BigDecimal to range")
		void shouldFormatAndParseToRangeWithoutError() {
			final BigDecimalNumberRange to = BigDecimalNumberRange.to(toBD("1.123"), 3);
			assertEquals(to, BigDecimalNumberRange.fromString(to.toString()));
		}

		@Test
		@DisplayName("Should format and parse BigDecimal between range")
		void shouldFormatAndParseBetweenRangeWithoutError() {
			final BigDecimalNumberRange between = BigDecimalNumberRange.between(toBD("1.123"), toBD("5"), 3);
			assertEquals(between, BigDecimalNumberRange.fromString(between.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid BigDecimal formats")
		void shouldFailToParseInvalidFormats() {
			assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString("[a,b]"));
		}

		@Test
		@DisplayName("Should format and parse Long between range")
		void shouldFormatAndParseLongBetweenRange() {
			final LongNumberRange range = LongNumberRange.between(10L, 20L);
			assertEquals(range, LongNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Long from range")
		void shouldFormatAndParseLongFromRange() {
			final LongNumberRange range = LongNumberRange.from(10L);
			assertEquals(range, LongNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Long to range")
		void shouldFormatAndParseLongToRange() {
			final LongNumberRange range = LongNumberRange.to(20L);
			assertEquals(range, LongNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid Long formats")
		void shouldFailToParseInvalidLongFormats() {
			assertThrows(DataTypeParseException.class, () -> LongNumberRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> LongNumberRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> LongNumberRange.fromString("[a,b]"));
		}

		@Test
		@DisplayName("Should format and parse Integer between range")
		void shouldFormatAndParseIntegerBetweenRange() {
			final IntegerNumberRange range = IntegerNumberRange.between(10, 20);
			assertEquals(range, IntegerNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Integer from range")
		void shouldFormatAndParseIntegerFromRange() {
			final IntegerNumberRange range = IntegerNumberRange.from(10);
			assertEquals(range, IntegerNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Integer to range")
		void shouldFormatAndParseIntegerToRange() {
			final IntegerNumberRange range = IntegerNumberRange.to(20);
			assertEquals(range, IntegerNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid Integer formats")
		void shouldFailToParseInvalidIntegerFormats() {
			assertThrows(DataTypeParseException.class, () -> IntegerNumberRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> IntegerNumberRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> IntegerNumberRange.fromString("[a,b]"));
		}

		@Test
		@DisplayName("Should format and parse Short between range")
		void shouldFormatAndParseShortBetweenRange() {
			final ShortNumberRange range = ShortNumberRange.between((short) 10, (short) 20);
			assertEquals(range, ShortNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Short from range")
		void shouldFormatAndParseShortFromRange() {
			final ShortNumberRange range = ShortNumberRange.from((short) 10);
			assertEquals(range, ShortNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Short to range")
		void shouldFormatAndParseShortToRange() {
			final ShortNumberRange range = ShortNumberRange.to((short) 20);
			assertEquals(range, ShortNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid Short formats")
		void shouldFailToParseInvalidShortFormats() {
			assertThrows(DataTypeParseException.class, () -> ShortNumberRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> ShortNumberRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> ShortNumberRange.fromString("[a,b]"));
		}

		@Test
		@DisplayName("Should format and parse Byte between range")
		void shouldFormatAndParseByteBetweenRange() {
			final ByteNumberRange range = ByteNumberRange.between((byte) 10, (byte) 20);
			assertEquals(range, ByteNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Byte from range")
		void shouldFormatAndParseByteFromRange() {
			final ByteNumberRange range = ByteNumberRange.from((byte) 10);
			assertEquals(range, ByteNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse Byte to range")
		void shouldFormatAndParseByteToRange() {
			final ByteNumberRange range = ByteNumberRange.to((byte) 20);
			assertEquals(range, ByteNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should fail to parse invalid Byte formats")
		void shouldFailToParseInvalidByteFormats() {
			assertThrows(DataTypeParseException.class, () -> ByteNumberRange.fromString(""));
			assertThrows(DataTypeParseException.class, () -> ByteNumberRange.fromString("[,]"));
			assertThrows(DataTypeParseException.class, () -> ByteNumberRange.fromString("[a,b]"));
		}

		@Test
		@DisplayName("Should correctly parse regex patterns")
		void shouldCorrectlyParseRegex() {
			assertArrayEquals(new String[]{"15.1", "78.9"}, NumberRange.PARSE_FCT.apply("[15.1,78.9]"));
			assertArrayEquals(new String[]{"15.1", null}, NumberRange.PARSE_FCT.apply("[15.1,]"));
			assertArrayEquals(new String[]{null, "78.9"}, NumberRange.PARSE_FCT.apply("[,78.9]"));
			assertArrayEquals(new String[]{"15.1", "78.9"}, NumberRange.PARSE_FCT.apply("[15.1 ,    78.9]"));
			assertArrayEquals(new String[]{"15.1", null}, NumberRange.PARSE_FCT.apply("[15.1 ,  ]"));
			assertArrayEquals(new String[]{null, "78.9"}, NumberRange.PARSE_FCT.apply("[ ,  78.9]"));
			assertArrayEquals(new String[]{"7", "7"}, NumberRange.PARSE_FCT.apply("7"));
			assertArrayEquals(new String[]{"78.9", "78.9"}, NumberRange.PARSE_FCT.apply("78.9"));
		}

		@Test
		@DisplayName("Should parse negative numbers in range format")
		void shouldParseNegativeNumbersInRangeFormat() {
			assertArrayEquals(new String[]{"-5", "10"}, NumberRange.PARSE_FCT.apply("[-5,10]"));
			assertArrayEquals(new String[]{"-10", "-5"}, NumberRange.PARSE_FCT.apply("[-10,-5]"));
			assertArrayEquals(new String[]{"-5", null}, NumberRange.PARSE_FCT.apply("[-5,]"));
			assertArrayEquals(new String[]{null, "-5"}, NumberRange.PARSE_FCT.apply("[,-5]"));
		}

		@Test
		@DisplayName("Should parse negative numbers as simple number")
		void shouldParseNegativeNumbersAsSimpleNumber() {
			assertArrayEquals(new String[]{"-7", "-7"}, NumberRange.PARSE_FCT.apply("-7"));
			assertArrayEquals(new String[]{"-78.9", "-78.9"}, NumberRange.PARSE_FCT.apply("-78.9"));
		}

		@Test
		@DisplayName("Should format and parse negative Long range")
		void shouldFormatAndParseNegativeLongRange() {
			final LongNumberRange range = LongNumberRange.between(-10L, -5L);
			assertEquals(range, LongNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse negative Integer range")
		void shouldFormatAndParseNegativeIntegerRange() {
			final IntegerNumberRange range = IntegerNumberRange.between(-10, -5);
			assertEquals(range, IntegerNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse negative Short range")
		void shouldFormatAndParseNegativeShortRange() {
			final ShortNumberRange range = ShortNumberRange.between((short) -10, (short) -5);
			assertEquals(range, ShortNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse negative Byte range")
		void shouldFormatAndParseNegativeByteRange() {
			final ByteNumberRange range = ByteNumberRange.between((byte) -10, (byte) -5);
			assertEquals(range, ByteNumberRange.fromString(range.toString()));
		}

		@Test
		@DisplayName("Should format and parse negative BigDecimal range")
		void shouldFormatAndParseNegativeBigDecimalRange() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal("-10.5"), new BigDecimal("-5.5"));
			assertEquals(range, BigDecimalNumberRange.fromString(range.toString()));
		}
	}

	@Nested
	@DisplayName("isWithin")
	class IsWithinTest {

		@Test
		@DisplayName("Should resolve within correctly for Integer")
		void shouldResolveWithinCorrectlyForInteger() {
			assertTrue(IntegerNumberRange.between(1, 5).isWithin(3));
			assertTrue(IntegerNumberRange.between(1, 5).isWithin(1));
			assertTrue(IntegerNumberRange.between(1, 5).isWithin(5));
			assertFalse(IntegerNumberRange.between(1, 5).isWithin(6));
			assertFalse(IntegerNumberRange.between(1, 5).isWithin(0));
			assertFalse(IntegerNumberRange.between(1, 5).isWithin(-1));
			assertFalse(IntegerNumberRange.between(1, 5).isWithin(Integer.MAX_VALUE));
			assertFalse(IntegerNumberRange.between(1, 5).isWithin(Integer.MIN_VALUE));
		}

		@Test
		@DisplayName("Should resolve within correctly for BigDecimal")
		void shouldResolveWithinCorrectlyForBigDecimal() {
			assertTrue(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(3)));
			assertTrue(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(1)));
			assertTrue(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(5)));
			assertFalse(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(6)));
			assertFalse(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(0)));
			assertFalse(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(-1)));
			assertFalse(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(Integer.MAX_VALUE)));
			assertFalse(BigDecimalNumberRange.between(toBD(1), toBD(5)).isWithin(toBD(Integer.MIN_VALUE)));
		}

		@Test
		@DisplayName("Should resolve within correctly for BigDecimal with precision")
		void shouldResolveWithinCorrectlyForBigDecimalWithPrecision() {
			assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("3.123")));
			assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("1.123")));
			assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("5.123")));
			assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("6.123")));
			assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("0.123")));
			assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("-1.123")));
			// this can be true due to rounding and inclusivity
			assertTrue(BigDecimalNumberRange.between(toBD("1.1"), toBD("5.0"), 1).isWithin(toBD("5.00000001")));
		}

		@Test
		@DisplayName("Should resolve within for half-open from range")
		void shouldResolveWithinForHalfOpenFromRange() {
			final IntegerNumberRange range = IntegerNumberRange.from(5);
			assertTrue(range.isWithin(5));
			assertTrue(range.isWithin(100));
			assertTrue(range.isWithin(Integer.MAX_VALUE));
			assertFalse(range.isWithin(4));
			assertFalse(range.isWithin(0));
		}

		@Test
		@DisplayName("Should resolve within for half-open to range")
		void shouldResolveWithinForHalfOpenToRange() {
			final IntegerNumberRange range = IntegerNumberRange.to(5);
			assertTrue(range.isWithin(5));
			assertTrue(range.isWithin(0));
			assertTrue(range.isWithin(-100));
			assertTrue(range.isWithin(Integer.MIN_VALUE));
			assertFalse(range.isWithin(6));
			assertFalse(range.isWithin(100));
		}

		@Test
		@DisplayName("Should resolve within for single-point range")
		void shouldResolveWithinForSinglePointRange() {
			final IntegerNumberRange range = IntegerNumberRange.between(5, 5);
			assertTrue(range.isWithin(5));
			assertFalse(range.isWithin(4));
			assertFalse(range.isWithin(6));
		}

		@Test
		@DisplayName("Should resolve within correctly for Long")
		void shouldResolveWithinCorrectlyForLong() {
			assertTrue(LongNumberRange.between(1L, 5L).isWithin(3L));
			assertTrue(LongNumberRange.between(1L, 5L).isWithin(1L));
			assertTrue(LongNumberRange.between(1L, 5L).isWithin(5L));
			assertFalse(LongNumberRange.between(1L, 5L).isWithin(6L));
			assertFalse(LongNumberRange.between(1L, 5L).isWithin(0L));
		}

		@Test
		@DisplayName("Should resolve within correctly for Short")
		void shouldResolveWithinCorrectlyForShort() {
			assertTrue(ShortNumberRange.between((short) 1, (short) 5).isWithin((short) 3));
			assertTrue(ShortNumberRange.between((short) 1, (short) 5).isWithin((short) 1));
			assertTrue(ShortNumberRange.between((short) 1, (short) 5).isWithin((short) 5));
			assertFalse(ShortNumberRange.between((short) 1, (short) 5).isWithin((short) 6));
			assertFalse(ShortNumberRange.between((short) 1, (short) 5).isWithin((short) 0));
		}

		@Test
		@DisplayName("Should resolve within correctly for Byte")
		void shouldResolveWithinCorrectlyForByte() {
			assertTrue(ByteNumberRange.between((byte) 1, (byte) 5).isWithin((byte) 3));
			assertTrue(ByteNumberRange.between((byte) 1, (byte) 5).isWithin((byte) 1));
			assertTrue(ByteNumberRange.between((byte) 1, (byte) 5).isWithin((byte) 5));
			assertFalse(ByteNumberRange.between((byte) 1, (byte) 5).isWithin((byte) 6));
			assertFalse(ByteNumberRange.between((byte) 1, (byte) 5).isWithin((byte) 0));
		}
	}

	@Nested
	@DisplayName("Overlaps")
	class OverlapsTest {

		@Test
		@DisplayName("Should compute overlaps correctly for Integer")
		void shouldComputeOverlapsCorrectlyForInteger() {
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(6, 7)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(1, 10)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(5, 8)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(4, 5)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(4, 6)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(8, 9)));
			assertTrue(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(7, 9)));
			assertFalse(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(1, 4)));
			assertFalse(IntegerNumberRange.between(5, 8).overlaps(IntegerNumberRange.between(9, 15)));
		}

		@Test
		@DisplayName("Should compute overlaps correctly for BigDecimal")
		void shouldComputeOverlapsCorrectlyForBigDecimal() {
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(6), toBD(7))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(1), toBD(10))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(5), toBD(8))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(4), toBD(5))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(4), toBD(6))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(8), toBD(9))));
			assertTrue(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(7), toBD(9))));
			assertFalse(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(1), toBD(4))));
			assertFalse(BigDecimalNumberRange.between(toBD(5), toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(9), toBD(15))));
		}

		@Test
		@DisplayName("Should detect overlap at adjacent boundary")
		void shouldDetectOverlapAtAdjacentBoundary() {
			// [1,5] and [5,10] share boundary 5 - should overlap
			assertTrue(IntegerNumberRange.between(1, 5).overlaps(IntegerNumberRange.between(5, 10)));
			assertTrue(IntegerNumberRange.between(5, 10).overlaps(IntegerNumberRange.between(1, 5)));
		}

		@Test
		@DisplayName("Should reject cross-type overlap comparison")
		@SuppressWarnings({"rawtypes", "unchecked"})
		void shouldRejectCrossTypeOverlapComparison() {
			final Range intRange = IntegerNumberRange.between(1, 5);
			final Range longRange = LongNumberRange.between(1L, 5L);
			assertThrows(IllegalArgumentException.class, () -> intRange.overlaps(longRange));
		}
	}

	@Nested
	@DisplayName("Consolidation")
	class ConsolidationTest {

		@Test
		@DisplayName("Should consolidate overlapping ranges")
		void shouldConsolidateOverlappingRanges() {
			assertArrayEquals(
				new NumberRange[]{
					IntegerNumberRange.between(2, 12),
					IntegerNumberRange.between(50, 55),
					IntegerNumberRange.between(80, 90),
				},
				Range.consolidateRange(
					new NumberRange[]{
						IntegerNumberRange.between(80, 90),
						IntegerNumberRange.between(51, 55),
						IntegerNumberRange.between(5, 12),
						IntegerNumberRange.between(2, 6),
						IntegerNumberRange.between(50, 51),
					}
				)
			);
		}

		@Test
		@DisplayName("Should return empty array when consolidating empty array")
		void shouldReturnEmptyArrayWhenConsolidatingEmpty() {
			final IntegerNumberRange[] result = Range.consolidateRange(new IntegerNumberRange[0]);
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("Should return single range unchanged")
		void shouldReturnSingleRangeUnchanged() {
			final IntegerNumberRange[] input = new IntegerNumberRange[]{IntegerNumberRange.between(1, 5)};
			final IntegerNumberRange[] result = Range.consolidateRange(input);
			assertEquals(1, result.length);
			assertEquals(IntegerNumberRange.between(1, 5), result[0]);
		}
	}

	@Nested
	@DisplayName("cloneWithDifferentBounds")
	class CloneWithDifferentBoundsTest {

		@Test
		@DisplayName("Should clone Integer range with different bounds")
		void shouldCloneIntegerRangeWithDifferentBounds() {
			final IntegerNumberRange original = IntegerNumberRange.between(1, 10);
			final Range<Integer> cloned = original.cloneWithDifferentBounds(5, 15);
			assertEquals(5, cloned.getPreciseFrom());
			assertEquals(15, cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone Long range with different bounds")
		void shouldCloneLongRangeWithDifferentBounds() {
			final LongNumberRange original = LongNumberRange.between(1L, 10L);
			final Range<Long> cloned = original.cloneWithDifferentBounds(5L, 15L);
			assertEquals(5L, cloned.getPreciseFrom());
			assertEquals(15L, cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone Short range with different bounds")
		void shouldCloneShortRangeWithDifferentBounds() {
			final ShortNumberRange original = ShortNumberRange.between((short) 1, (short) 10);
			final Range<Short> cloned = original.cloneWithDifferentBounds((short) 5, (short) 15);
			assertEquals((short) 5, cloned.getPreciseFrom());
			assertEquals((short) 15, cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone Byte range with different bounds")
		void shouldCloneByteRangeWithDifferentBounds() {
			final ByteNumberRange original = ByteNumberRange.between((byte) 1, (byte) 10);
			final Range<Byte> cloned = original.cloneWithDifferentBounds((byte) 5, (byte) 15);
			assertEquals((byte) 5, cloned.getPreciseFrom());
			assertEquals((byte) 15, cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone BigDecimal range with different bounds")
		void shouldCloneBigDecimalRangeWithDifferentBounds() {
			final BigDecimalNumberRange original = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("10.0"));
			final Range<BigDecimal> cloned = original.cloneWithDifferentBounds(new BigDecimal("5.0"), new BigDecimal("15.0"));
			assertEquals(new BigDecimal("5.0"), cloned.getPreciseFrom());
			assertEquals(new BigDecimal("15.0"), cloned.getPreciseTo());
		}

		@Test
		@DisplayName("Should clone BigDecimal range preserving retained decimal places")
		void shouldCloneBigDecimalRangePreservingRetainedDecimalPlaces() {
			final BigDecimalNumberRange original = BigDecimalNumberRange.between(new BigDecimal("1.00"), new BigDecimal("10.00"), 2);
			final Range<BigDecimal> cloned = original.cloneWithDifferentBounds(new BigDecimal("5.00"), new BigDecimal("15.00"));
			assertEquals(new BigDecimal("5.00"), cloned.getPreciseFrom());
			assertEquals(new BigDecimal("15.00"), cloned.getPreciseTo());
			assertEquals(500L, cloned.getFrom());
			assertEquals(1500L, cloned.getTo());
		}
	}

	private static BigDecimal toBD(int integer) {
		return toBD(String.valueOf(integer));
	}

	@Nonnull
	private static BigDecimal toBD(String s) {
		return new BigDecimal(s);
	}

}
