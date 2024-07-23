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

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks creation of the {@link NumberRange} data type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class NumberRangeTest {

	@Test
	void shouldFailToConstructUnreasonableRange() {
		assertThrows(IllegalArgumentException.class, () -> BigDecimalNumberRange.between(null, null));
		assertThrows(IllegalArgumentException.class, () -> LongNumberRange.between(null, null));
		assertThrows(IllegalArgumentException.class, () -> IntegerNumberRange.between(null, null));
		assertThrows(IllegalArgumentException.class, () -> ShortNumberRange.between(null, null));
		assertThrows(IllegalArgumentException.class, () -> ByteNumberRange.between(null, null));
	}

	@Test
	void shouldConstructBetweenByte() {
		final ByteNumberRange range = ByteNumberRange.between((byte)1, (byte)2);
		assertEquals((byte)1, range.getPreciseFrom());
		assertEquals((byte)2, range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(2L, range.getTo());
		assertEquals("[1,2]", range.toString());
		assertEquals(range, ByteNumberRange.between((byte)1, (byte)2));
		assertNotSame(range, ByteNumberRange.between((byte)1, (byte)2));
		assertNotEquals(range, ByteNumberRange.between((byte)1, (byte)3));
		assertNotEquals(range, ByteNumberRange.between((byte)2, (byte)2));
		assertEquals(range.hashCode(), ByteNumberRange.between((byte)1, (byte)2).hashCode());
		assertNotEquals(range.hashCode(), ByteNumberRange.between((byte)1, (byte)3).hashCode());
	}

	@Test
	void shouldConstructFromByte() {
		final ByteNumberRange range = ByteNumberRange.from((byte)1);
		assertEquals((byte)1, range.getPreciseFrom());
		assertNull(range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(Long.MAX_VALUE, range.getTo());
		assertEquals("[1,]", range.toString());
		assertEquals(range, ByteNumberRange.from((byte)1));
		assertNotSame(range, ByteNumberRange.from((byte)1));
		assertNotEquals(range, ByteNumberRange.from((byte)2));
		assertEquals(range.hashCode(), ByteNumberRange.from((byte)1).hashCode());
		assertNotEquals(range.hashCode(), ByteNumberRange.from((byte)2).hashCode());
	}

	@Test
	void shouldConstructToByte() {
		final ByteNumberRange range = ByteNumberRange.to((byte)1);
		assertEquals((byte)1, range.getPreciseTo());
		assertNull(range.getPreciseFrom());
		assertEquals(1L, range.getTo());
		assertEquals(Long.MIN_VALUE, range.getFrom());
		assertEquals("[,1]", range.toString());
		assertEquals(range, ByteNumberRange.to((byte)1));
		assertNotSame(range, ByteNumberRange.to((byte)1));
		assertNotEquals(range, ByteNumberRange.to((byte)2));
		assertEquals(range.hashCode(), ByteNumberRange.to((byte)1).hashCode());
		assertNotEquals(range.hashCode(), ByteNumberRange.to((byte)2).hashCode());
	}

	@Test
	void shouldConstructBetweenShort() {
		final ShortNumberRange range = ShortNumberRange.between((short)1, (short)2);
		assertEquals((short)1, range.getPreciseFrom());
		assertEquals((short)2, range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(2L, range.getTo());
		assertEquals("[1,2]", range.toString());
		assertEquals(range, ShortNumberRange.between((short)1, (short)2));
		assertNotSame(range, ShortNumberRange.between((short)1, (short)2));
		assertNotEquals(range, ShortNumberRange.between((short)1, (short)3));
		assertNotEquals(range, ShortNumberRange.between((short)2, (short)2));
		assertEquals(range.hashCode(), ShortNumberRange.between((short)1, (short)2).hashCode());
		assertNotEquals(range.hashCode(), ShortNumberRange.between((short)1, (short)3).hashCode());
	}

	@Test
	void shouldConstructFromShort() {
		final ShortNumberRange range = ShortNumberRange.from((short)1);
		assertEquals((short)1, range.getPreciseFrom());
		assertNull(range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(Long.MAX_VALUE, range.getTo());
		assertEquals("[1,]", range.toString());
		assertEquals(range, ShortNumberRange.from((short)1));
		assertNotSame(range, ShortNumberRange.from((short)1));
		assertNotEquals(range, ShortNumberRange.from((short)2));
		assertEquals(range.hashCode(), ShortNumberRange.from((short)1).hashCode());
		assertNotEquals(range.hashCode(), ShortNumberRange.from((short)2).hashCode());
	}

	@Test
	void shouldConstructToShort() {
		final ShortNumberRange range = ShortNumberRange.to((short)1);
		assertEquals((short)1, range.getPreciseTo());
		assertNull(range.getPreciseFrom());
		assertEquals(1L, range.getTo());
		assertEquals(Long.MIN_VALUE, range.getFrom());
		assertEquals("[,1]", range.toString());
		assertEquals(range, ShortNumberRange.to((short)1));
		assertNotSame(range, ShortNumberRange.to((short)1));
		assertNotEquals(range, ShortNumberRange.to((short)2));
		assertEquals(range.hashCode(), ShortNumberRange.to((short)1).hashCode());
		assertNotEquals(range.hashCode(), ShortNumberRange.to((short)2).hashCode());
	}

	@Test
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
	void shouldConstructBetweenLong() {
		final LongNumberRange range = LongNumberRange.between((long)1, (long)2);
		assertEquals((long)1, range.getPreciseFrom());
		assertEquals((long)2, range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(2L, range.getTo());
		assertEquals("[1,2]", range.toString());
		assertEquals(range, LongNumberRange.between((long)1, (long)2));
		assertNotSame(range, LongNumberRange.between((long)1, (long)2));
		assertNotEquals(range, LongNumberRange.between((long)1, (long)3));
		assertNotEquals(range, LongNumberRange.between((long)2, (long)2));
		assertEquals(range.hashCode(), LongNumberRange.between((long)1, (long)2).hashCode());
		assertNotEquals(range.hashCode(), LongNumberRange.between((long)1, (long)3).hashCode());
	}

	@Test
	void shouldConstructFromLong() {
		final LongNumberRange range = LongNumberRange.from((long)1);
		assertEquals((long)1, range.getPreciseFrom());
		assertNull(range.getPreciseTo());
		assertEquals(1L, range.getFrom());
		assertEquals(Long.MAX_VALUE, range.getTo());
		assertEquals("[1,]", range.toString());
		assertEquals(range, LongNumberRange.from((long)1));
		assertNotSame(range, LongNumberRange.from((long)1));
		assertNotEquals(range, LongNumberRange.from((long)2));
		assertEquals(range.hashCode(), LongNumberRange.from((long)1).hashCode());
		assertNotEquals(range.hashCode(), LongNumberRange.from((long)2).hashCode());
	}

	@Test
	void shouldConstructToLong() {
		final LongNumberRange range = LongNumberRange.to((long)1);
		assertEquals((long)1, range.getPreciseTo());
		assertNull(range.getPreciseFrom());
		assertEquals(1L, range.getTo());
		assertEquals(Long.MIN_VALUE, range.getFrom());
		assertEquals("[,1]", range.toString());
		assertEquals(range, LongNumberRange.to((long)1));
		assertNotSame(range, LongNumberRange.to((long)1));
		assertNotEquals(range, LongNumberRange.to((long)2));
		assertEquals(range.hashCode(), LongNumberRange.to((long)1).hashCode());
		assertNotEquals(range.hashCode(), LongNumberRange.to((long)2).hashCode());
	}

	@Test
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
	void shouldCompareRanges() {
		assertTrue(IntegerNumberRange.from(1).compareTo(IntegerNumberRange.from(2)) < 0);
		assertTrue(IntegerNumberRange.from(1).compareTo(IntegerNumberRange.from(1)) == 0);
		assertTrue(IntegerNumberRange.from(2).compareTo(IntegerNumberRange.from(1)) > 0);

		assertTrue(IntegerNumberRange.to(1).compareTo(IntegerNumberRange.to(2)) < 0);
		assertTrue(IntegerNumberRange.to(1).compareTo(IntegerNumberRange.to(1)) == 0);
		assertTrue(IntegerNumberRange.to(2).compareTo(IntegerNumberRange.to(1)) > 0);

		assertTrue(IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(2, 2)) < 0);
		assertTrue(IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(1, 2)) == 0);
		assertTrue(IntegerNumberRange.between(2, 2).compareTo(IntegerNumberRange.between(1, 2)) > 0);
		assertTrue(IntegerNumberRange.between(1, 2).compareTo(IntegerNumberRange.between(1, 3)) < 0);
		assertTrue(IntegerNumberRange.between(1, 3).compareTo(IntegerNumberRange.between(1, 2)) > 0);
	}

	@Test
	void shouldFormatAndParsefromRangeWithoutError() {
		final BigDecimalNumberRange from = BigDecimalNumberRange.from(toBD("1.123"), 3);
		assertEquals(from, BigDecimalNumberRange.fromString(from.toString()));
	}

	@Test
	void shouldFormatAndParsetoRangeWithoutError() {
		final BigDecimalNumberRange to = BigDecimalNumberRange.to(toBD("1.123"), 3);
		assertEquals(to, BigDecimalNumberRange.fromString(to.toString()));
	}

	@Test
	void shouldFormatAndParseBetweenRangeWithoutError() {
		final BigDecimalNumberRange between = BigDecimalNumberRange.between(toBD("1.123"), toBD("5"), 3);
		assertEquals(between, BigDecimalNumberRange.fromString(between.toString()));
	}

	@Test
	void shouldFailToParseInvalidFormats() {
		assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString(""));
		assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString("[,]"));
		assertThrows(DataTypeParseException.class, () -> BigDecimalNumberRange.fromString("[a,b]"));
	}

	@Test
	void shouldResolveWithinCorrectly() {
		assertTrue(IntegerNumberRange.between(1,5).isWithin(3));
		assertTrue(IntegerNumberRange.between(1,5).isWithin(1));
		assertTrue(IntegerNumberRange.between(1,5).isWithin(5));
		assertFalse(IntegerNumberRange.between(1,5).isWithin(6));
		assertFalse(IntegerNumberRange.between(1,5).isWithin(0));
		assertFalse(IntegerNumberRange.between(1,5).isWithin(-1));
		assertFalse(IntegerNumberRange.between(1,5).isWithin(Integer.MAX_VALUE));
		assertFalse(IntegerNumberRange.between(1,5).isWithin(Integer.MIN_VALUE));
	}

	@Test
	void shouldResolveWithinCorrectlyWithBigDecimal() {
		assertTrue(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(3)));
		assertTrue(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(1)));
		assertTrue(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(5)));
		assertFalse(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(6)));
		assertFalse(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(0)));
		assertFalse(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(-1)));
		assertFalse(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(Integer.MAX_VALUE)));
		assertFalse(BigDecimalNumberRange.between(toBD(1),toBD(5)).isWithin(toBD(Integer.MIN_VALUE)));
	}

	@Test
	void shouldResolveWithinCorrectlyWithBigDecimalAndPrecision() {
		assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("3.123")));
		assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("1.123")));
		assertTrue(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("5.123")));
		assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("6.123")));
		assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("0.123")));
		assertFalse(BigDecimalNumberRange.between(toBD("1.123"), toBD("5.123"), 1).isWithin(toBD("-1.123")));
		//this can be true due to rounding and inclusivity
		assertTrue(BigDecimalNumberRange.between(toBD("1.1"), toBD("5.0"), 1).isWithin(toBD("5.00000001")));
	}

	@Test
	void shouldComputeOverlapsCorrectly() {
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(6, 7)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(1, 10)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(5, 8)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(4, 5)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(4, 6)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(8, 9)));
		assertTrue(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(7, 9)));
		assertFalse(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(1, 4)));
		assertFalse(IntegerNumberRange.between(5,8).overlaps(IntegerNumberRange.between(9, 15)));
	}

	@Test
	void shouldComputeOverlapsCorrectlyWithBigDecimal() {
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(6), toBD(7))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(1), toBD(10))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(5), toBD(8))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(4), toBD(5))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(4), toBD(6))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(8), toBD(9))));
		assertTrue(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(7), toBD(9))));
		assertFalse(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(1), toBD(4))));
		assertFalse(BigDecimalNumberRange.between(toBD(5),toBD(8)).overlaps(BigDecimalNumberRange.between(toBD(9), toBD(15))));
	}

	@Test
	void shouldCorrectlyParseRegex() {
		assertArrayEquals(new String[] {"15.1", "78.9"}, NumberRange.PARSE_FCT.apply("[15.1,78.9]"));
		assertArrayEquals(new String[] {"15.1", null}, NumberRange.PARSE_FCT.apply("[15.1,]"));
		assertArrayEquals(new String[] {null, "78.9"}, NumberRange.PARSE_FCT.apply("[,78.9]"));
		assertArrayEquals(new String[] {"15.1", "78.9"}, NumberRange.PARSE_FCT.apply("[15.1 ,    78.9]"));
		assertArrayEquals(new String[] {"15.1", null}, NumberRange.PARSE_FCT.apply("[15.1 ,  ]"));
		assertArrayEquals(new String[] {null, "78.9"}, NumberRange.PARSE_FCT.apply("[ ,  78.9]"));
		assertArrayEquals(new String[] {"7", "7"}, NumberRange.PARSE_FCT.apply("7"));
		assertArrayEquals(new String[] {"78.9", "78.9"}, NumberRange.PARSE_FCT.apply("78.9"));
	}

	@Test
	void shouldConsolidateRanges() {
		assertArrayEquals(
			new NumberRange[] {
				IntegerNumberRange.between(2, 12),
				IntegerNumberRange.between(50, 55),
				IntegerNumberRange.between(80, 90),
			},
			Range.consolidateRange(
				new NumberRange[] {
					IntegerNumberRange.between(80, 90),
					IntegerNumberRange.between(51, 55),
					IntegerNumberRange.between(5, 12),
					IntegerNumberRange.between(2, 6),
					IntegerNumberRange.between(50, 51),
				}
			)
		);
	}

	private BigDecimal toBD(int integer) {
		return toBD(String.valueOf(integer));
	}

	@Nonnull
	private BigDecimal toBD(String s) {
		return new BigDecimal(s);
	}

}
