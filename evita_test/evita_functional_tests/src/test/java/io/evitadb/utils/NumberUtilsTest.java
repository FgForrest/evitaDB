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

package io.evitadb.utils;

import io.evitadb.dataType.BigDecimalNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static io.evitadb.utils.NumberUtils.pack;
import static io.evitadb.utils.NumberUtils.unpack;
import static io.evitadb.utils.NumberUtils.unpackHigh;
import static io.evitadb.utils.NumberUtils.unpackHigh16;
import static io.evitadb.utils.NumberUtils.unpackLow;
import static io.evitadb.utils.NumberUtils.unpackLow32;
import static io.evitadb.utils.NumberUtils.unpackMid16;
import java.io.Serializable;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * Test verifies contract of {@link NumberUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("NumberUtils contract tests")
@Tag(ENGINE)
@Tag(DATA_TYPE)
class NumberUtilsTest {

	@Nested
	@DisplayName("Pack/Unpack tests")
	class PackUnpackTests {

		@Test
		@DisplayName("Should pack and increment separately")
		void shouldPackAndIncrementSeparately() {
			assertEquals(4398046511105L, pack(1024, 1));
			assertEquals(4398046511105L + 1L, pack(1024, 2));
		}

		@Test
		@DisplayName("Should pack and decompose ints to long")
		void shouldPackAndDecomposeIntsToLong() {
			assertArrayEquals(new int[]{1, 45}, unpack(pack(1, 45)));
			assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, unpack(pack(Integer.MAX_VALUE, Integer.MIN_VALUE)));
			assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE}, unpack(pack(Integer.MIN_VALUE, Integer.MIN_VALUE)));
			assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}, unpack(pack(Integer.MIN_VALUE, Integer.MAX_VALUE)));
			assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, unpack(pack(Integer.MAX_VALUE, Integer.MIN_VALUE)));
			assertArrayEquals(new int[]{-10, -564}, unpack(pack(-10, -564)));
		}

		@Test
		@DisplayName("Should decompose a packed long half-by-half without allocation")
		void shouldUnpackHighAndLowWithoutAllocation() {
			final int[][] pairs = {
				{1, 45},
				{Integer.MAX_VALUE, Integer.MIN_VALUE},
				{Integer.MIN_VALUE, Integer.MIN_VALUE},
				{Integer.MIN_VALUE, Integer.MAX_VALUE},
				{Integer.MAX_VALUE, Integer.MIN_VALUE},
				{-10, -564},
				{0, 0}
			};
			for (final int[] pair : pairs) {
				final long packed = pack(pair[0], pair[1]);
				// the half-accessors must agree with the index-0 / index-1 components of unpack(...)
				assertEquals(pair[0], unpackHigh(packed));
				assertEquals(pair[1], unpackLow(packed));
				assertEquals(unpack(packed)[0], unpackHigh(packed));
				assertEquals(unpack(packed)[1], unpackLow(packed));
			}
		}
	}

	@Nested
	@DisplayName("Triple pack/unpack tests (16|16|32)")
	class TriplePackUnpackTests {

		@Test
		@DisplayName("Should round-trip the three fields of a (16|16|32) packed long")
		void shouldRoundTripThreeFieldPack() {
			final int[][] triples = {
				{0, 0, 0},
				{1, 2, 3},
				{0xFFFF, 0xFFFF, -1},
				{0xFFFF, 0, Integer.MAX_VALUE},
				{0, 0xFFFF, Integer.MIN_VALUE},
				{12345, 54321 & 0xFFFF, -987654},
				{0xFFFF, 0x1234, 0}
			};
			for (final int[] triple : triples) {
				final long packed = pack(triple[0], triple[1], triple[2]);
				assertEquals(triple[0], unpackHigh16(packed));
				assertEquals(triple[1], unpackMid16(packed));
				assertEquals(triple[2], unpackLow32(packed));
			}
		}

		@Test
		@DisplayName("Should keep the three fields independent and non-overlapping")
		void shouldKeepThreeFieldsIndependent() {
			// every field set to its maximum unsigned width must not bleed into a neighbour
			assertEquals(0xFFFF, unpackHigh16(pack(0xFFFF, 0, 0)));
			assertEquals(0, unpackMid16(pack(0xFFFF, 0, 0)));
			assertEquals(0, unpackLow32(pack(0xFFFF, 0, 0)));

			assertEquals(0, unpackHigh16(pack(0, 0xFFFF, 0)));
			assertEquals(0xFFFF, unpackMid16(pack(0, 0xFFFF, 0)));
			assertEquals(0, unpackLow32(pack(0, 0xFFFF, 0)));

			assertEquals(0, unpackHigh16(pack(0, 0, -1)));
			assertEquals(0, unpackMid16(pack(0, 0, -1)));
			assertEquals(-1, unpackLow32(pack(0, 0, -1)));
		}

		@Test
		@DisplayName("Should reject a high field that overflows 16 unsigned bits")
		void shouldThrowWhenHighFieldOverflows() {
			assertThrows(GenericEvitaInternalError.class, () -> pack(0x10000, 0, 0));
			assertThrows(GenericEvitaInternalError.class, () -> pack(-1, 0, 0));
		}

		@Test
		@DisplayName("Should reject a mid field that overflows 16 unsigned bits")
		void shouldThrowWhenMidFieldOverflows() {
			assertThrows(GenericEvitaInternalError.class, () -> pack(0, 0x10000, 0));
			assertThrows(GenericEvitaInternalError.class, () -> pack(0, -1, 0));
		}
	}

	@Nested
	@DisplayName("Summation tests")
	class SummationTests {

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should sum byte and anything")
		void shouldSumByteAndAnything() {
			assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((byte) 4)));
			assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((short) 4)));
			assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((int) 4)));
			assertEquals((byte) 8, NumberUtils.sum(((byte) 4), ((long) 4)));
			assertEquals((byte) 8, NumberUtils.sum(((byte) 4), new BigDecimal("4")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should fail on byte overflow")
		void shouldFailOnByteOverflow() {
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((byte) 127)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((short) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((int) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), ((long) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((byte) 4), new BigDecimal("512")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should sum short and anything")
		void shouldSumShortAndAnything() {
			assertEquals((short) 8, NumberUtils.sum(((short) 4), ((byte) 4)));
			assertEquals((short) 8, NumberUtils.sum(((short) 4), ((short) 4)));
			assertEquals((short) 8, NumberUtils.sum(((short) 4), ((int) 4)));
			assertEquals((short) 8, NumberUtils.sum(((short) 4), ((long) 4)));
			assertEquals((short) 8, NumberUtils.sum(((short) 4), new BigDecimal("4")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should fail on short overflow")
		void shouldFailOnShortOverflow() {
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32766), ((byte) 127)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((short) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((int) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), ((long) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(((short) 32767), new BigDecimal("512")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should sum int and anything")
		void shouldSumIntAndAnything() {
			assertEquals((int) 8, NumberUtils.sum(((int) 4), ((byte) 4)));
			assertEquals((int) 8, NumberUtils.sum(((int) 4), ((short) 4)));
			assertEquals((int) 8, NumberUtils.sum(((int) 4), ((int) 4)));
			assertEquals((int) 8, NumberUtils.sum(((int) 4), ((long) 4)));
			assertEquals((int) 8, NumberUtils.sum(((int) 4), new BigDecimal("4")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should fail on int overflow")
		void shouldFailOnIntOverflow() {
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((byte) 127)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((short) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((int) 512)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, ((long) Integer.MAX_VALUE + 200)));
			assertThrows(ArithmeticException.class, () -> NumberUtils.sum(Integer.MAX_VALUE, new BigDecimal("512")));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should sum long and anything")
		void shouldSumLongAndAnything() {
			assertEquals((long) 8, NumberUtils.sum(((long) 4), ((byte) 4)));
			assertEquals((long) 8, NumberUtils.sum(((long) 4), ((short) 4)));
			assertEquals((long) 8, NumberUtils.sum(((long) 4), ((int) 4)));
			assertEquals((long) 8, NumberUtils.sum(((long) 4), ((long) 4)));
			assertEquals((long) 8, NumberUtils.sum(((long) 4), new BigDecimal("4")));
			assertEquals((long) 0, NumberUtils.sum(((long) 4), ((long) -4)));
		}

		@SuppressWarnings("RedundantCast")
		@Test
		@DisplayName("Should sum BigDecimal and anything")
		void shouldSumBigDecimalAndAnything() {
			assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((byte) 4)));
			assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((short) 4)));
			assertEquals(new BigDecimal("8"), NumberUtils.sum(new BigDecimal("4"), ((int) 4)));
			assertEquals(new BigDecimal("8.2"), NumberUtils.sum(new BigDecimal("4.2"), ((long) 4)));
			assertEquals(new BigDecimal("9.0"), NumberUtils.sum(new BigDecimal("4.2"), new BigDecimal("4.8")));
			assertEquals(new BigDecimal("-0.6"), NumberUtils.sum(new BigDecimal("4.2"), new BigDecimal("-4.8")));
		}
	}

	@Nested
	@DisplayName("ConvertToNumericType tests")
	class ConvertToNumericTypeTests {

		@Test
		@DisplayName("Should convert to each supported target type")
		void shouldConvertToEachSupportedTargetType() {
			assertEquals((byte) 42, NumberUtils.convertToNumericType(42, Byte.class));
			assertEquals((short) 1000, NumberUtils.convertToNumericType(1000, Short.class));
			assertEquals(100_000, NumberUtils.convertToNumericType(100_000L, Integer.class));
			assertEquals(5_000_000_000L, NumberUtils.convertToNumericType(new BigDecimal("5000000000"), Long.class));
			assertEquals(new BigDecimal("42"), NumberUtils.convertToNumericType(42, BigDecimal.class));
		}

		@Test
		@DisplayName("Should strip trailing zeros for BigDecimal target")
		void shouldStripTrailingZerosForBigDecimalTarget() {
			assertEquals(new BigDecimal("10.5"), NumberUtils.convertToNumericType(new BigDecimal("10.500"), BigDecimal.class));
		}

		@Test
		@DisplayName("Should throw on unsupported target type")
		void shouldThrowOnUnsupportedTargetType() {
			assertThrows(IllegalArgumentException.class, () -> NumberUtils.convertToNumericType(1, Float.class));
			assertThrows(IllegalArgumentException.class, () -> NumberUtils.convertToNumericType(1, Double.class));
			assertThrows(IllegalArgumentException.class, () -> NumberUtils.convertToNumericType(1, String.class));
		}
	}

	@Nested
	@DisplayName("Conversion tests")
	class ConversionTests {

		@Test
		@DisplayName("Should convert numbers to int")
		void shouldConvertNumbersToInt() {
			assertEquals(2, NumberUtils.convertToInt((byte) 2));
			assertEquals(2, NumberUtils.convertToInt((short) 2));
			assertEquals(2, NumberUtils.convertToInt(2));
			assertEquals(2, NumberUtils.convertToInt((long) 2));
			assertEquals(22314, NumberUtils.convertToInt(new BigDecimal("223.1405"), 2));
			assertEquals(2231405, NumberUtils.convertToInt(new BigDecimal("223.1405"), 4));
			assertEquals(223, NumberUtils.convertToInt(new BigDecimal("223.1405"), 0));
		}

		@Test
		@DisplayName("Should fail to convert big numbers to int")
		void shouldFailToConvertBigNumbersToInt() {
			assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(1.1f));
			assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt((double) 1.1f));
			assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(Long.MAX_VALUE));
		}

		@Test
		@DisplayName("Should convert BigDecimal to int")
		void shouldConvertBigDecimalToInt() {
			assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.2"), 2));
			assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.20"), 2));
			assertEquals(11020, NumberUtils.convertToInt(new BigDecimal("110.2000"), 2));
			assertThrows(ArithmeticException.class, () -> NumberUtils.convertToInt(new BigDecimal("21474836471"), 2));
		}

		@Test
		@DisplayName("Should correctly normalize BigDecimal for map key")
		void shouldCorrectlyNormalizeBigDecimalForMapKey() {
			final Map<BigDecimal, Integer> map = Map.of(
				NumberUtils.normalize(new BigDecimal("110.2")), 1
			);

			assertEquals(1, map.get(NumberUtils.normalize(new BigDecimal("110.2"))));
			assertEquals(1, map.get(NumberUtils.normalize(new BigDecimal("110.200"))));
			assertEquals(1, map.get(NumberUtils.normalize(new BigDecimal("+110.2"))));
			assertEquals(1, map.get(NumberUtils.normalize(new BigDecimal("+0110.2"))));
			assertEquals(1, map.get(NumberUtils.normalize(new BigDecimal("+0110.200"))));
			assertNull(map.get(NumberUtils.normalize(new BigDecimal("-0110.200"))));
			assertNull(map.get(NumberUtils.normalize(new BigDecimal("-00110.200"))));
			assertNull(map.get(NumberUtils.normalize(new BigDecimal("110.0200"))));
			assertNull(map.get(NumberUtils.normalize(new BigDecimal("1010.0200"))));
		}
	}

	@Nested
	@DisplayName("normalizeIfBigDecimal tests")
	class NormalizeIfBigDecimalTests {

		@Test
		@DisplayName("Should normalize scalar BigDecimal")
		void shouldNormalizeScalarBigDecimal() {
			final Serializable result = NumberUtils.normalizeIfBigDecimal(new BigDecimal("50.00"));
			assertEquals(new BigDecimal("5E+1"), result);
		}

		@Test
		@DisplayName("Should return non-BigDecimal value unchanged")
		void shouldReturnNonBigDecimalValueUnchanged() {
			final Serializable intValue = 42;
			assertSame(intValue, NumberUtils.normalizeIfBigDecimal(intValue));

			final Serializable strValue = "hello";
			assertSame(strValue, NumberUtils.normalizeIfBigDecimal(strValue));
		}

		@Test
		@DisplayName("Should normalize each element in BigDecimal array")
		void shouldNormalizeEachElementInBigDecimalArray() {
			final BigDecimal[] input = new BigDecimal[]{
				new BigDecimal("10.00"), new BigDecimal("20"), new BigDecimal("30.50")
			};
			final Serializable result = NumberUtils.normalizeIfBigDecimal(input);
			final BigDecimal[] normalized = (BigDecimal[]) result;

			assertEquals(new BigDecimal("1E+1"), normalized[0]);
			assertEquals(new BigDecimal("2E+1"), normalized[1]);
			assertEquals(new BigDecimal("30.5"), normalized[2]);
		}

		@Test
		@DisplayName("Should normalize pre-normalized BigDecimal array elements to equivalent values")
		void shouldNormalizePreNormalizedBigDecimalArrayToEquivalentValues() {
			final BigDecimal[] input = new BigDecimal[]{
				NumberUtils.normalize(new BigDecimal("10")),
				NumberUtils.normalize(new BigDecimal("20"))
			};
			final BigDecimal[] result = (BigDecimal[]) NumberUtils.normalizeIfBigDecimal(input);
			assertEquals(input[0], result[0]);
			assertEquals(input[1], result[1]);
		}

		@Test
		@DisplayName("Should handle empty BigDecimal array")
		void shouldHandleEmptyBigDecimalArray() {
			final BigDecimal[] result = (BigDecimal[]) NumberUtils.normalizeIfBigDecimal(new BigDecimal[0]);
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("Should produce HashMap-consistent values for array elements")
		void shouldProduceHashMapConsistentValuesForArrayElements() {
			final BigDecimal[] input = new BigDecimal[]{
				new BigDecimal("50.00"), new BigDecimal("50"), new BigDecimal("5E+1")
			};
			final BigDecimal[] normalized = (BigDecimal[]) NumberUtils.normalizeIfBigDecimal(input);

			// all three forms of 50 must produce equal and hashCode-consistent values
			assertEquals(normalized[0], normalized[1]);
			assertEquals(normalized[1], normalized[2]);
			assertEquals(normalized[0].hashCode(), normalized[1].hashCode());
			assertEquals(normalized[1].hashCode(), normalized[2].hashCode());

			// they must work as HashMap keys
			final Map<BigDecimal, Integer> map = Map.of(normalized[0], 1);
			assertEquals(1, map.get(normalized[1]));
			assertEquals(1, map.get(normalized[2]));
		}
	}

	@Nested
	@DisplayName("normalizeForIndexing tests")
	class NormalizeForIndexingTests {

		@Test
		@DisplayName("Should strip trailing zeros for scalar BigDecimal regardless of indexed decimal places")
		void shouldNormalizeScalarBigDecimal() {
			assertEquals(new BigDecimal("5E+1"), NumberUtils.normalizeForIndexing(new BigDecimal("50.00"), 4));
			assertEquals(new BigDecimal("88.1"), NumberUtils.normalizeForIndexing(new BigDecimal("88.10000"), 4));
		}

		@Test
		@DisplayName("Should return non-decimal values unchanged")
		void shouldReturnNonDecimalValuesUnchanged() {
			final Serializable intValue = 42;
			assertSame(intValue, NumberUtils.normalizeForIndexing(intValue, 4));
			final Serializable strValue = "hello";
			assertSame(strValue, NumberUtils.normalizeForIndexing(strValue, 4));
		}

		@Test
		@DisplayName("Should normalize each element in BigDecimal array, preserving nulls")
		void shouldNormalizeBigDecimalArrayPreservingNulls() {
			final BigDecimal[] input = new BigDecimal[]{new BigDecimal("10.00"), null, new BigDecimal("30.50")};
			final BigDecimal[] result = (BigDecimal[]) NumberUtils.normalizeForIndexing(input, 4);
			assertEquals(new BigDecimal("1E+1"), result[0]);
			assertNull(result[1]);
			assertEquals(new BigDecimal("30.5"), result[2]);
		}

		@Test
		@DisplayName("Should re-encode a typed range built at a different intrinsic scale to indexedDecimalPlaces")
		void shouldReEncodeRangeToIndexedDecimalPlaces() {
			// 2-arg factory derives retainedDecimalPlaces from the inputs' own scale (5) and leaves the
			// schema-authoritative scale unknown (null) - this is the buggy case that must be re-encoded
			final BigDecimalNumberRange input = BigDecimalNumberRange.between(
				new BigDecimal("88.10000"), new BigDecimal("118.10000")
			);
			final BigDecimalNumberRange result =
				(BigDecimalNumberRange) NumberUtils.normalizeForIndexing(input, 4);

			final BigDecimalNumberRange expected = BigDecimalNumberRange.between(
				new BigDecimal("88.10000"), new BigDecimal("118.10000"), 4
			);
			assertEquals(4, result.getRetainedDecimalPlaces());
			assertEquals(expected.getFrom(), result.getFrom());
			assertEquals(expected.getTo(), result.getTo());
			// the comparable longs must equal those the query side produces at the same scale
			assertEquals(881000L, result.getFrom());
			assertEquals(1181000L, result.getTo());
		}

		@Test
		@DisplayName("Should re-encode a range whose explicit scale differs from indexedDecimalPlaces")
		void shouldReEncodeRangeWhenExplicitScaleDiffersFromIndexedDecimalPlaces() {
			// the range carries an explicit retainedDecimalPlaces (2) that differs from the requested
			// indexedDecimalPlaces (4), so it must be re-encoded to the schema-authoritative scale
			final BigDecimalNumberRange input = BigDecimalNumberRange.between(
				new BigDecimal("88.1"), new BigDecimal("118.1"), 2
			);
			final BigDecimalNumberRange result =
				(BigDecimalNumberRange) NumberUtils.normalizeForIndexing(input, 4);

			assertNotSame(input, result);
			assertEquals(4, result.getRetainedDecimalPlaces());
			assertEquals(881000L, result.getFrom());
			assertEquals(1181000L, result.getTo());
			// the precise bounds must be preserved through the re-encoding
			assertEquals(0, new BigDecimal("88.1").compareTo(result.getPreciseFrom()));
			assertEquals(0, new BigDecimal("118.1").compareTo(result.getPreciseTo()));
		}

		@Test
		@DisplayName("Should return the same range instance when it already carries the requested scale")
		void shouldReturnSameInstanceWhenScaleAlreadyMatches() {
			final BigDecimalNumberRange input = BigDecimalNumberRange.between(
				new BigDecimal("88.1"), new BigDecimal("118.1"), 4
			);
			assertSame(input, NumberUtils.normalizeForIndexing(input, 4));
		}

		@Test
		@DisplayName("Should return the same range instance when null-retained natural scale already matches")
		void shouldReturnSameInstanceWhenNullRetainedNaturalScaleAlreadyMatches() {
			// 2-arg factory leaves retainedDecimalPlaces null but the bounds' natural scale (2) already
			// equals indexedDecimalPlaces - the common index path must not allocate a fresh equal range
			final BigDecimalNumberRange input = BigDecimalNumberRange.between(
				new BigDecimal("88.10"), new BigDecimal("118.10")
			);
			assertSame(input, NumberUtils.normalizeForIndexing(input, 2));
		}

		@Test
		@DisplayName("Should re-encode open-ended ranges (from-only and to-only)")
		void shouldReEncodeOpenEndedRanges() {
			final BigDecimalNumberRange fromOnly =
				(BigDecimalNumberRange) NumberUtils.normalizeForIndexing(
					BigDecimalNumberRange.from(new BigDecimal("88.10000")), 4
				);
			assertEquals(4, fromOnly.getRetainedDecimalPlaces());
			assertEquals(881000L, fromOnly.getFrom());

			final BigDecimalNumberRange toOnly =
				(BigDecimalNumberRange) NumberUtils.normalizeForIndexing(
					BigDecimalNumberRange.to(new BigDecimal("118.10000")), 4
				);
			assertEquals(4, toOnly.getRetainedDecimalPlaces());
			assertEquals(1181000L, toOnly.getTo());
		}

		@Test
		@DisplayName("Should round to indexedDecimalPlaces = 0")
		void shouldRoundToZeroIndexedDecimalPlaces() {
			final BigDecimalNumberRange result =
				(BigDecimalNumberRange) NumberUtils.normalizeForIndexing(
					BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.90000")), 0
				);
			assertEquals(0, result.getRetainedDecimalPlaces());
			assertEquals(88L, result.getFrom());
			assertEquals(119L, result.getTo());
		}

		@Test
		@DisplayName("Should leave the infinite range untouched")
		void shouldLeaveInfiniteRangeUntouched() {
			assertSame(
				BigDecimalNumberRange.INFINITE,
				NumberUtils.normalizeForIndexing(BigDecimalNumberRange.INFINITE, 4)
			);
		}

		@Test
		@DisplayName("Should re-encode each element of a range array, preserving nulls")
		void shouldReEncodeRangeArrayPreservingNulls() {
			final BigDecimalNumberRange[] input = new BigDecimalNumberRange[]{
				BigDecimalNumberRange.between(new BigDecimal("88.10000"), new BigDecimal("118.10000")),
				null
			};
			final BigDecimalNumberRange[] result =
				(BigDecimalNumberRange[]) NumberUtils.normalizeForIndexing(input, 4);
			assertEquals(4, result[0].getRetainedDecimalPlaces());
			assertEquals(881000L, result[0].getFrom());
			assertNull(result[1]);
		}
	}
}
