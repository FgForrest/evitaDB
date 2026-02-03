/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test verifies contract of {@link Crc32CWrapper} class.
 *
 * This test suite ensures that the CRC32C calculator correctly computes checksums for various data types
 * and handles edge cases properly. It verifies that the calculated checksums match the values produced
 * by the standard Java {@link CRC32C} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Test verifies contract of Crc32CWrapper class")
class Crc32CWrapperTest {

	/**
	 * Computes the CRC32C checksum of the given byte array.
	 *
	 * @param bytes the byte array for which the CRC32C checksum is to be calculated;
	 *              must not be null or empty to avoid unexpected behavior.
	 * @return the computed CRC32C value as a long.
	 */
	private static long crc32c(byte[] bytes) {
		CRC32C crc = new CRC32C();
		crc.update(bytes, 0, bytes.length);
		return crc.getValue();
	}

	@Test
	@DisplayName("Should compute CRC32C for single long value")
	void shouldComputeCrc32ForSingleLongValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(12345L);
		final long result = calculator.getValue();

		// Verify against standard CRC32C
		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(12345L);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Long as zero")
	void shouldHandleNullBoxedLongAsZero() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong((Long) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(0L);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for long array")
	void shouldComputeCrc32ForLongArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final long[] values = {1L, 2L, 3L, 4L, 5L};
		calculator.withLongArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		for (final long value : values) {
			buffer.clear();
			buffer.putLong(value);
			expected.update(buffer.array());
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null long array")
	void shouldHandleNullLongArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLongArray((long[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Long array with null elements")
	void shouldComputeCrc32ForBoxedLongArrayWithNullElements() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final Long[] values = {1L, null, 3L};
		calculator.withLongArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		for (final Long value : values) {
			buffer.clear();
			buffer.putLong(value == null ? 0L : value);
			expected.update(buffer.array());
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for single int value")
	void shouldComputeCrc32ForSingleIntValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withInt(42);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(42);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Integer as zero")
	void shouldHandleNullBoxedIntegerAsZero() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withInt((Integer) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(0);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for int array")
	void shouldComputeCrc32ForIntArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final int[] values = {10, 20, 30, 40, 50};
		calculator.withIntArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		for (final int value : values) {
			buffer.clear();
			buffer.putInt(value);
			expected.update(buffer.array());
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null int array")
	void shouldHandleNullIntArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withIntArray((int[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Integer array with null elements")
	void shouldComputeCrc32ForBoxedIntegerArrayWithNullElements() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final Integer[] values = {10, null, 30};
		calculator.withIntArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		for (final Integer value : values) {
			buffer.clear();
			buffer.putInt(value == null ? 0 : value);
			expected.update(buffer.array());
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for single byte value")
	void shouldComputeCrc32ForSingleByteValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withByte((byte) 123);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[]{123});

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Byte as zero")
	void shouldHandleNullBoxedByteAsZero() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withByte((Byte) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[]{0});

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for byte array")
	void shouldComputeCrc32ForByteArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final byte[] values = {1, 2, 3, 4, 5};
		calculator.withByteArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(values);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null byte array")
	void shouldHandleNullByteArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withByteArray((byte[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle empty byte array")
	void shouldHandleEmptyByteArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withByteArray(new byte[0]);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[0]);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Byte array with null elements")
	void shouldComputeCrc32ForBoxedByteArrayWithNullElements() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final Byte[] values = {1, null, 3};
		calculator.withByteArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		for (final Byte value : values) {
			expected.update(new byte[]{value == null ? 0 : value});
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for String value")
	void shouldComputeCrc32ForStringValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final String testString = "Hello, World!";
		calculator.withString(testString);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(testString.getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null String")
	void shouldHandleNullString() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withString(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle empty String")
	void shouldHandleEmptyString() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withString("");
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[0]);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for String array")
	void shouldComputeCrc32ForStringArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final String[] values = {"foo", "bar", "baz"};
		calculator.withStringArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		for (final String value : values) {
			expected.update(value.getBytes(StandardCharsets.UTF_8));
		}

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null String array")
	void shouldHandleNullStringArray() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withStringArray(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle String array with null elements")
	void shouldHandleStringArrayWithNullElements() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final String[] values = {"foo", null, "baz"};
		calculator.withStringArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update("foo".getBytes(StandardCharsets.UTF_8));
		expected.update("baz".getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for OffsetDateTime value")
	void shouldComputeCrc32ForOffsetDateTimeValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 15, 10, 30, 45, 123456789, ZoneOffset.UTC);
		calculator.withOffsetDateTime(dateTime);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(dateTime.toEpochSecond());
		buffer.putInt(dateTime.getNano());
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null OffsetDateTime")
	void shouldHandleNullOffsetDateTime() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withOffsetDateTime(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for UUID value")
	void shouldComputeCrc32ForUuidValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
		calculator.withUuid(uuid);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(uuid.getMostSignificantBits());
		buffer.putLong(uuid.getLeastSignificantBits());
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null UUID")
	void shouldHandleNullUuid() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withUuid(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should support method chaining")
	void shouldSupportMethodChaining() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final long result = calculator
			.withLong(100L)
			.withInt(200)
			.withString("test")
			.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer longBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuffer.putLong(100L);
		expected.update(longBuffer.array());

		final ByteBuffer intBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		intBuffer.putInt(200);
		expected.update(intBuffer.array());

		expected.update("test".getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should reset calculator properly")
	void shouldResetCalculatorProperly() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(12345L);
		final long firstResult = calculator.getValue();

		calculator.reset();
		final long afterResetResult = calculator.getValue();

		assertEquals(0, afterResetResult);
		assertNotEquals(firstResult, afterResetResult);
	}

	@Test
	@DisplayName("Should allow reuse after reset")
	void shouldAllowReuseAfterReset() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(12345L);
		final long firstResult = calculator.getValue();

		calculator.reset().withLong(12345L);
		final long secondResult = calculator.getValue();

		assertEquals(firstResult, secondResult);
	}

	@Test
	@DisplayName("Should handle edge case with max long value")
	void shouldHandleEdgeCaseWithMaxLongValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(Long.MAX_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(Long.MAX_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with min long value")
	void shouldHandleEdgeCaseWithMinLongValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(Long.MIN_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(Long.MIN_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with max int value")
	void shouldHandleEdgeCaseWithMaxIntValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withInt(Integer.MAX_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(Integer.MAX_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with min int value")
	void shouldHandleEdgeCaseWithMinIntValue() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withInt(Integer.MIN_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(Integer.MIN_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle empty arrays consistently")
	void shouldHandleEmptyArraysConsistently() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLongArray(new long[0])
			.withIntArray(new int[0])
			.withByteArray(new byte[0])
			.withStringArray(new String[0]);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute consistent checksum for complex data")
	void shouldComputeConsistentChecksumForComplexData() {
		final Crc32CWrapper calculator1 = new Crc32CWrapper();
		calculator1.withLong(100L)
			.withInt(200)
			.withByte((byte) 5)
			.withString("test")
			.withUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
		final long result1 = calculator1.getValue();

		final Crc32CWrapper calculator2 = new Crc32CWrapper();
		calculator2.withLong(100L)
			.withInt(200)
			.withByte((byte) 5)
			.withString("test")
			.withUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
		final long result2 = calculator2.getValue();

		assertEquals(result1, result2);
	}

	@Test
	@DisplayName("Should produce different checksum when data order changes")
	void shouldProduceDifferentChecksumWhenDataOrderChanges() {
		final Crc32CWrapper calculator1 = new Crc32CWrapper();
		calculator1.withLong(100L).withInt(200);
		final long result1 = calculator1.getValue();

		final Crc32CWrapper calculator2 = new Crc32CWrapper();
		calculator2.withInt(200).withLong(100L);
		final long result2 = calculator2.getValue();

		assertNotEquals(result1, result2);
	}

	@Test
	@DisplayName("Should handle unicode strings correctly")
	void shouldHandleUnicodeStringsCorrectly() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final String unicodeString = "Hello 世界 🌍";
		calculator.withString(unicodeString);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(unicodeString.getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should combine CRC values when two chunks are concatenated")
	void shouldCombineCrcValuesWhenTwoChunksAreConcatenated() {
		byte[] chunk1 = "Hello ".getBytes();
		byte[] chunk2 = "world!".getBytes();

		long crc1 = crc32c(chunk1);     // persisted after writing chunk1
		long crc2 = crc32c(chunk2);     // computed later for chunk2

		long combined = Crc32CWrapper.combine(crc1, crc2, chunk2.length);

		byte[] all = new byte[chunk1.length + chunk2.length];
		System.arraycopy(chunk1, 0, all, 0, chunk1.length);
		System.arraycopy(chunk2, 0, all, chunk1.length, chunk2.length);

		long direct = crc32c(all);

		assertEquals(direct, combined);
	}

	@Test
	@DisplayName("Should chain multiple CRC values when three chunks are combined")
	void shouldChainMultipleCrcValuesWhenThreeChunksAreCombined() {
		byte[] c1 = "chunk-1|".getBytes();
		byte[] c2 = "chunk-2|".getBytes();
		byte[] c3 = "chunk-3".getBytes();

		long crc1 = crc32c(c1);
		long crc2 = crc32c(c2);
		long crc3 = crc32c(c3);

		// simulate resuming: ((c1||c2)||c3)
		long crc12 = Crc32CWrapper.combine(crc1, crc2, c2.length);
		long crc123 = Crc32CWrapper.combine(crc12, crc3, c3.length);

		byte[] all = new byte[c1.length + c2.length + c3.length];
		System.arraycopy(c1, 0, all, 0, c1.length);
		System.arraycopy(c2, 0, all, c1.length, c2.length);
		System.arraycopy(c3, 0, all, c1.length + c2.length, c3.length);

		long direct = crc32c(all);

		// [c1][crc(c1)][c2][crc(c1||c2)][c3][crc(c1||c2||c3)]
		assertEquals(direct, crc123);
	}

	@Test
	@DisplayName("Should return first CRC when second chunk is empty")
	void shouldReturnFirstCrcWhenSecondChunkIsEmpty() {
		final byte[] chunk1 = "Hello world!".getBytes();
		final byte[] chunk2 = new byte[0];

		final long crc1 = crc32c(chunk1);
		final long crc2 = crc32c(chunk2);

		final long combined = Crc32CWrapper.combine(crc1, crc2, 0);

		assertEquals(crc1, combined);
	}

	@Test
	@DisplayName("Should return first CRC when second chunk length is negative")
	void shouldReturnFirstCrcWhenSecondChunkLengthIsNegative() {
		final byte[] chunk1 = "Hello world!".getBytes();

		final long crc1 = crc32c(chunk1);
		final long crc2 = 12345L; // arbitrary value

		final long combined = Crc32CWrapper.combine(crc1, crc2, -10);

		assertEquals(crc1, combined);
	}

	@Test
	@DisplayName("Should combine correctly when first chunk is empty")
	void shouldCombineCorrectlyWhenFirstChunkIsEmpty() {
		final byte[] chunk1 = new byte[0];
		final byte[] chunk2 = "Hello!".getBytes();

		final long crc1 = crc32c(chunk1);
		final long crc2 = crc32c(chunk2);

		final long combined = Crc32CWrapper.combine(crc1, crc2, chunk2.length);

		final byte[] all = new byte[chunk2.length];
		System.arraycopy(chunk2, 0, all, 0, chunk2.length);
		final long direct = crc32c(all);

		assertEquals(direct, combined);
	}

	@Test
	@DisplayName("Should combine correctly when single bytes are combined")
	void shouldCombineCorrectlyWhenSingleBytesAreCombined() {
		final byte[] chunk1 = new byte[]{42};
		final byte[] chunk2 = new byte[]{73};

		final long crc1 = crc32c(chunk1);
		final long crc2 = crc32c(chunk2);

		final long combined = Crc32CWrapper.combine(crc1, crc2, chunk2.length);

		final byte[] all = new byte[]{42, 73};
		final long direct = crc32c(all);

		assertEquals(direct, combined);
	}

	@Test
	@DisplayName("Should combine correctly when large chunks are combined")
	void shouldCombineCorrectlyWhenLargeChunksAreCombined() {
		// Create 10KB chunks
		final byte[] chunk1 = new byte[10240];
		final byte[] chunk2 = new byte[10240];

		// Fill with deterministic data
		for (int i = 0; i < chunk1.length; i++) {
			chunk1[i] = (byte) (i % 256);
			chunk2[i] = (byte) ((i << 1) % 256);
		}

		final long crc1 = crc32c(chunk1);
		final long crc2 = crc32c(chunk2);

		final long combined = Crc32CWrapper.combine(crc1, crc2, chunk2.length);

		final byte[] all = new byte[chunk1.length + chunk2.length];
		System.arraycopy(chunk1, 0, all, 0, chunk1.length);
		System.arraycopy(chunk2, 0, all, chunk1.length, chunk2.length);
		final long direct = crc32c(all);

		assertEquals(direct, combined);
	}

	@Test
	@DisplayName("Should maintain associativity when chaining multiple combinations")
	void shouldMaintainAssociativityWhenChainingMultipleCombinations() {
		final byte[] a = "part-A|".getBytes();
		final byte[] b = "part-B|".getBytes();
		final byte[] c = "part-C".getBytes();

		final long crcA = crc32c(a);
		final long crcB = crc32c(b);
		final long crcC = crc32c(c);

		// Left-associative: ((A + B) + C)
		final long crcAB = Crc32CWrapper.combine(crcA, crcB, b.length);
		final long leftResult = Crc32CWrapper.combine(crcAB, crcC, c.length);

		// Right-associative: (A + (B + C))
		// First combine B and C
		final byte[] bc = new byte[b.length + c.length];
		System.arraycopy(b, 0, bc, 0, b.length);
		System.arraycopy(c, 0, bc, b.length, c.length);
		final long crcBC = crc32c(bc);
		final long rightResult = Crc32CWrapper.combine(crcA, crcBC, bc.length);

		// Direct computation: A + B + C
		final byte[] all = new byte[a.length + b.length + c.length];
		System.arraycopy(a, 0, all, 0, a.length);
		System.arraycopy(b, 0, all, a.length, b.length);
		System.arraycopy(c, 0, all, a.length + b.length, c.length);
		final long directResult = crc32c(all);

		// Both associative forms should produce the same result as direct computation
		assertEquals(directResult, leftResult);
		assertEquals(directResult, rightResult);
	}

	// =========================================================================
	// Tests for cumulative checksum functionality
	// =========================================================================

	@Test
	@DisplayName("Should accumulate checksum correctly when getValue is called between data additions")
	void shouldAccumulateChecksumCorrectlyWhenGetValueCalledBetweenDataAdditions() {
		// Test that getValue() preserves state for cumulative computation
		final Crc32CWrapper calculator = new Crc32CWrapper();

		// Add first chunk and get intermediate value
		calculator.withLong(100L);
		calculator.getValue();  // Finalize first chunk

		// Add second chunk
		calculator.withInt(200);
		final long cumulativeResult = calculator.getValue();

		// Compare against direct computation
		final CRC32C expected = new CRC32C();
		final ByteBuffer longBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuffer.putLong(100L);
		expected.update(longBuffer.array());

		final ByteBuffer intBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		intBuffer.putInt(200);
		expected.update(intBuffer.array());

		assertEquals(expected.getValue(), cumulativeResult);
	}

	@Test
	@DisplayName("Should return same value on consecutive getValue calls without new data")
	void shouldReturnSameValueOnConsecutiveGetValueCallsWithoutNewData() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withLong(12345L).withString("test");

		final long firstResult = calculator.getValue();
		final long secondResult = calculator.getValue();
		final long thirdResult = calculator.getValue();

		assertEquals(firstResult, secondResult);
		assertEquals(secondResult, thirdResult);
	}

	@Test
	@DisplayName("Should clear accumulated checksum after reset")
	void shouldClearAccumulatedChecksumAfterReset() {
		final Crc32CWrapper calculator = new Crc32CWrapper();

		// Build up cumulative checksum
		calculator.withLong(100L);
		calculator.getValue();
		calculator.withInt(200);
		final long beforeReset = calculator.getValue();

		// Reset should clear everything
		calculator.reset();
		final long afterReset = calculator.getValue();

		assertNotEquals(beforeReset, afterReset);
		assertEquals(0L, afterReset);
	}

	@Test
	@DisplayName("Should correctly combine local data with external checksum using withAnotherChecksum")
	void shouldCorrectlyCombineLocalDataWithExternalChecksumUsingWithAnotherChecksum() {
		// Compute external checksum separately
		final byte[] externalData = "external chunk".getBytes(StandardCharsets.UTF_8);
		final long externalCrc = crc32c(externalData);

		// Calculator with local data
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withString("local data");
		calculator.withAnotherChecksum(externalCrc, externalData.length);
		final long combinedResult = calculator.getValue();

		// Compute expected: local data followed by external data
		final CRC32C expected = new CRC32C();
		expected.update("local data".getBytes(StandardCharsets.UTF_8));
		expected.update(externalData);

		assertEquals(expected.getValue(), combinedResult);
	}

	@Test
	@DisplayName("Should handle withAnotherChecksum when no local data is pending")
	void shouldHandleWithAnotherChecksumWhenNoLocalDataIsPending() {
		final byte[] externalData = "external only".getBytes(StandardCharsets.UTF_8);
		final long externalCrc = crc32c(externalData);

		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withAnotherChecksum(externalCrc, externalData.length);
		final long result = calculator.getValue();

		assertEquals(externalCrc, result);
	}

	@Test
	@DisplayName("Should chain multiple withAnotherChecksum calls correctly")
	void shouldChainMultipleWithAnotherChecksumCallsCorrectly() {
		final byte[] chunk1 = "chunk1|".getBytes(StandardCharsets.UTF_8);
		final byte[] chunk2 = "chunk2|".getBytes(StandardCharsets.UTF_8);
		final byte[] chunk3 = "chunk3".getBytes(StandardCharsets.UTF_8);

		final long crc1 = crc32c(chunk1);
		final long crc2 = crc32c(chunk2);
		final long crc3 = crc32c(chunk3);

		// Build cumulative checksum using withAnotherChecksum
		final Crc32CWrapper calculator = new Crc32CWrapper();
		calculator.withAnotherChecksum(crc1, chunk1.length);
		calculator.withAnotherChecksum(crc2, chunk2.length);
		calculator.withAnotherChecksum(crc3, chunk3.length);
		final long result = calculator.getValue();

		// Compute direct checksum
		final byte[] all = new byte[chunk1.length + chunk2.length + chunk3.length];
		System.arraycopy(chunk1, 0, all, 0, chunk1.length);
		System.arraycopy(chunk2, 0, all, chunk1.length, chunk2.length);
		System.arraycopy(chunk3, 0, all, chunk1.length + chunk2.length, chunk3.length);
		final long direct = crc32c(all);

		assertEquals(direct, result);
	}

	@Test
	@DisplayName("Should correctly interleave local data additions with external checksum combinations")
	void shouldCorrectlyInterleaveLocalDataWithExternalChecksums() {
		final byte[] external1 = "ext1".getBytes(StandardCharsets.UTF_8);
		final byte[] external2 = "ext2".getBytes(StandardCharsets.UTF_8);

		final Crc32CWrapper calculator = new Crc32CWrapper();

		// Local -> External -> Local -> External
		calculator.withLong(100L);
		calculator.withAnotherChecksum(crc32c(external1), external1.length);
		calculator.withInt(200);
		calculator.withAnotherChecksum(crc32c(external2), external2.length);
		final long result = calculator.getValue();

		// Direct computation
		final CRC32C expected = new CRC32C();
		final ByteBuffer longBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuf.putLong(100L);
		expected.update(longBuf.array());
		expected.update(external1);
		final ByteBuffer intBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		intBuf.putInt(200);
		expected.update(intBuf.array());
		expected.update(external2);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should maintain cumulative checksum correctly with various data types")
	void shouldMaintainCumulativeChecksumWithVariousDataTypes() {
		final Crc32CWrapper calculator = new Crc32CWrapper();
		final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
		final OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 15, 10, 30, 45, 0, ZoneOffset.UTC);

		// Add data in batches with getValue() calls between
		calculator.withLong(100L);
		calculator.getValue();

		calculator.withString("test");
		calculator.getValue();

		calculator.withUuid(uuid);
		calculator.getValue();

		calculator.withOffsetDateTime(dateTime);
		final long cumulativeResult = calculator.getValue();

		// Direct computation
		final CRC32C expected = new CRC32C();
		final ByteBuffer longBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuf.putLong(100L);
		expected.update(longBuf.array());
		expected.update("test".getBytes(StandardCharsets.UTF_8));

		final ByteBuffer uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		uuidBuf.putLong(uuid.getMostSignificantBits());
		uuidBuf.putLong(uuid.getLeastSignificantBits());
		expected.update(uuidBuf.array());

		final ByteBuffer dtBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		dtBuf.putLong(dateTime.toEpochSecond());
		dtBuf.putInt(dateTime.getNano());
		expected.update(dtBuf.array());

		assertEquals(expected.getValue(), cumulativeResult);
	}

	@Test
	@DisplayName("Should initialize calculator with initial checksum value")
	void shouldInitializeCalculatorWithInitialChecksumValue() {
		// Compute checksum for first chunk
		final byte[] firstChunk = "initial data chunk".getBytes(StandardCharsets.UTF_8);
		final long firstChunkCrc = crc32c(firstChunk);

		// Create calculator with initial checksum (simulating resumption from persisted state)
		final Crc32CWrapper calculator = new Crc32CWrapper(firstChunkCrc);

		// Add second chunk
		final byte[] secondChunk = "appended data".getBytes(StandardCharsets.UTF_8);
		calculator.withByteArray(secondChunk);
		final long result = calculator.getValue();

		// Compute expected: direct CRC of both chunks concatenated
		final byte[] combined = new byte[firstChunk.length + secondChunk.length];
		System.arraycopy(firstChunk, 0, combined, 0, firstChunk.length);
		System.arraycopy(secondChunk, 0, combined, firstChunk.length, secondChunk.length);
		final long expected = crc32c(combined);

		assertEquals(expected, result);
	}

	@Test
	@DisplayName("Should return initial checksum when no data is added")
	void shouldReturnInitialChecksumWhenNoDataIsAdded() {
		final byte[] data = "some existing data".getBytes(StandardCharsets.UTF_8);
		final long initialCrc = crc32c(data);

		final Crc32CWrapper calculator = new Crc32CWrapper(initialCrc);
		final long result = calculator.getValue();

		assertEquals(initialCrc, result);
	}

	@Test
	@DisplayName("Should handle zero initial checksum same as default constructor")
	void shouldHandleZeroInitialChecksumSameAsDefaultConstructor() {
		final Crc32CWrapper calculatorWithZero = new Crc32CWrapper(0L);
		final Crc32CWrapper calculatorDefault = new Crc32CWrapper();

		calculatorWithZero.withLong(12345L).withString("test");
		calculatorDefault.withLong(12345L).withString("test");

		assertEquals(calculatorDefault.getValue(), calculatorWithZero.getValue());
	}

	@Test
	@DisplayName("Should correctly chain multiple data additions after initial checksum")
	void shouldCorrectlyChainMultipleDataAdditionsAfterInitialChecksum() {
		// First chunk - computed externally
		final byte[] chunk1 = "chunk-one|".getBytes(StandardCharsets.UTF_8);
		final long chunk1Crc = crc32c(chunk1);

		// Initialize calculator with first chunk's checksum
		final Crc32CWrapper calculator = new Crc32CWrapper(chunk1Crc);

		// Add multiple data items
		calculator.withLong(42L);
		calculator.withInt(123);
		calculator.withString("suffix");
		final long result = calculator.getValue();

		// Direct computation: chunk1 + long(42) + int(123) + "suffix"
		final CRC32C expected = new CRC32C();
		expected.update(chunk1);

		final ByteBuffer longBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuf.putLong(42L);
		expected.update(longBuf.array());

		final ByteBuffer intBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		intBuf.putInt(123);
		expected.update(intBuf.array());

		expected.update("suffix".getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should reset initial checksum when reset is called")
	void shouldResetInitialChecksumWhenResetIsCalled() {
		final byte[] data = "initial data".getBytes(StandardCharsets.UTF_8);
		final long initialCrc = crc32c(data);

		final Crc32CWrapper calculator = new Crc32CWrapper(initialCrc);
		calculator.reset();
		final long result = calculator.getValue();

		assertEquals(0L, result);
	}

	@Test
	@DisplayName("Should reset with initial value and preserve it when no new data is added")
	void shouldResetWithInitialValueAndPreserveIt() {
		final byte[] data = "existing data".getBytes(StandardCharsets.UTF_8);
		final long initialCrc = crc32c(data);

		final Crc32CWrapper calculator = new Crc32CWrapper();
		// Add some data first
		calculator.withLong(12345L);
		calculator.getValue();

		// Reset with initial value
		calculator.reset(initialCrc);
		final long result = calculator.getValue();

		assertEquals(initialCrc, result);
	}

	@Test
	@DisplayName("Should reset with initial value and allow cumulative computation")
	void shouldResetWithInitialValueAndAllowCumulativeComputation() {
		// Compute checksum for first chunk
		final byte[] firstChunk = "first chunk".getBytes(StandardCharsets.UTF_8);
		final long firstChunkCrc = crc32c(firstChunk);

		final Crc32CWrapper calculator = new Crc32CWrapper();
		// Add some data, then reset with initial value
		calculator.withInt(999);
		calculator.reset(firstChunkCrc);

		// Add second chunk
		final byte[] secondChunk = "second chunk".getBytes(StandardCharsets.UTF_8);
		calculator.withByteArray(secondChunk);
		final long result = calculator.getValue();

		// Compute expected: direct CRC of both chunks concatenated
		final byte[] combined = new byte[firstChunk.length + secondChunk.length];
		System.arraycopy(firstChunk, 0, combined, 0, firstChunk.length);
		System.arraycopy(secondChunk, 0, combined, firstChunk.length, secondChunk.length);
		final long expected = crc32c(combined);

		assertEquals(expected, result);
	}

	@Test
	@DisplayName("Should reset with initial value and clear pending data")
	void shouldResetWithInitialValueAndClearPendingData() {
		final byte[] data = "target data".getBytes(StandardCharsets.UTF_8);
		final long initialCrc = crc32c(data);

		final Crc32CWrapper calculator = new Crc32CWrapper();
		// Add data but don't call getValue (pending data)
		calculator.withLong(12345L);
		calculator.withString("pending string");

		// Reset with initial value should discard pending data
		calculator.reset(initialCrc);
		final long result = calculator.getValue();

		// Should only have the initial checksum value
		assertEquals(initialCrc, result);
	}

	@Test
	@DisplayName("Should support method chaining with reset(long)")
	void shouldSupportMethodChainingWithResetLong() {
		final byte[] data = "base data".getBytes(StandardCharsets.UTF_8);
		final long initialCrc = crc32c(data);

		final Crc32CWrapper calculator = new Crc32CWrapper();
		final long result = calculator
			.withInt(999)
			.reset(initialCrc)
			.withLong(42L)
			.getValue();

		// Direct computation: base data + long(42)
		final CRC32C expected = new CRC32C();
		expected.update(data);
		final ByteBuffer longBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		longBuf.putLong(42L);
		expected.update(longBuf.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should reset with zero initial value same as no-arg reset")
	void shouldResetWithZeroInitialValueSameAsNoArgReset() {
		final Crc32CWrapper calculator1 = new Crc32CWrapper();
		final Crc32CWrapper calculator2 = new Crc32CWrapper();

		// Add same data to both
		calculator1.withLong(12345L).withString("test");
		calculator2.withLong(12345L).withString("test");

		// Reset with different methods
		calculator1.reset();
		calculator2.reset(0L);

		// Add same new data
		calculator1.withInt(42);
		calculator2.withInt(42);

		assertEquals(calculator1.getValue(), calculator2.getValue());
	}

	@Test
	@DisplayName("Should start fresh cumulative computation after reset")
	void shouldStartFreshCumulativeComputationAfterReset() {
		final Crc32CWrapper calculator = new Crc32CWrapper();

		// First cumulative computation
		calculator.withLong(100L);
		calculator.getValue();
		calculator.withInt(200);
		calculator.getValue();

		// Reset and start new computation
		calculator.reset();
		calculator.withLong(300L);
		final long afterResetResult = calculator.getValue();

		// Should equal fresh computation of just 300L
		final Crc32CWrapper fresh = new Crc32CWrapper();
		fresh.withLong(300L);
		final long freshResult = fresh.getValue();

		assertEquals(freshResult, afterResetResult);
	}

	@Test
	@DisplayName("Should correctly track pending length for combine operation")
	void shouldCorrectlyTrackPendingLengthForCombineOperation() {
		// This test verifies the internal tracking works correctly by comparing
		// cumulative vs direct computation with known byte lengths

		final Crc32CWrapper calculator = new Crc32CWrapper();

		// 8 bytes (long) + getValue
		calculator.withLong(1L);
		calculator.getValue();

		// 4 bytes (int) + getValue
		calculator.withInt(2);
		calculator.getValue();

		// 1 byte + getValue
		calculator.withByte((byte) 3);
		final long result = calculator.getValue();

		// Direct: 8 + 4 + 1 = 13 bytes total
		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putLong(1L);
		buffer.putInt(2);
		buffer.put((byte) 3);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

}
