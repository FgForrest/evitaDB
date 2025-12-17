/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test verifies contract of {@link Crc32Calculator} class.
 *
 * This test suite ensures that the CRC32C calculator correctly computes checksums for various data types
 * and handles edge cases properly. It verifies that the calculated checksums match the values produced
 * by the standard Java {@link CRC32C} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Test verifies contract of Crc32Calculator class")
class Crc32CalculatorTest {

	@Test
	@DisplayName("Should compute CRC32C for single long value")
	void shouldComputeCrc32ForSingleLongValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLong(12345L);
		final long result = calculator.getValue();

		// Verify against standard CRC32C
		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(12345L);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Long as zero")
	void shouldHandleNullBoxedLongAsZero() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLong((Long) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(0L);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for long array")
	void shouldComputeCrc32ForLongArray() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final long[] values = {1L, 2L, 3L, 4L, 5L};
		calculator.withLongArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLongArray((long[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Long array with null elements")
	void shouldComputeCrc32ForBoxedLongArrayWithNullElements() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final Long[] values = {1L, null, 3L};
		calculator.withLongArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withInt(42);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(42);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Integer as zero")
	void shouldHandleNullBoxedIntegerAsZero() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withInt((Integer) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(0);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for int array")
	void shouldComputeCrc32ForIntArray() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final int[] values = {10, 20, 30, 40, 50};
		calculator.withIntArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withIntArray((int[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Integer array with null elements")
	void shouldComputeCrc32ForBoxedIntegerArrayWithNullElements() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final Integer[] values = {10, null, 30};
		calculator.withIntArray(values);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withByte((byte) 123);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[]{123});

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null boxed Byte as zero")
	void shouldHandleNullBoxedByteAsZero() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withByte((Byte) null);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[]{0});

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for byte array")
	void shouldComputeCrc32ForByteArray() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withByteArray((byte[]) null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle empty byte array")
	void shouldHandleEmptyByteArray() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withByteArray(new byte[0]);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[0]);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for boxed Byte array with null elements")
	void shouldComputeCrc32ForBoxedByteArrayWithNullElements() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withString(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle empty String")
	void shouldHandleEmptyString() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withString("");
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(new byte[0]);

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should compute CRC32C for String array")
	void shouldComputeCrc32ForStringArray() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withStringArray(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should handle String array with null elements")
	void shouldHandleStringArrayWithNullElements() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
		final OffsetDateTime dateTime = OffsetDateTime.of(2025, 1, 15, 10, 30, 45, 123456789, ZoneOffset.UTC);
		calculator.withOffsetDateTime(dateTime);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(12);
		buffer.putLong(dateTime.toEpochSecond());
		buffer.putInt(dateTime.getNano());
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null OffsetDateTime")
	void shouldHandleNullOffsetDateTime() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withOffsetDateTime(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should compute CRC32C for UUID value")
	void shouldComputeCrc32ForUuidValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
		calculator.withUuid(uuid);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(uuid.getMostSignificantBits());
		buffer.putLong(uuid.getLeastSignificantBits());
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle null UUID")
	void shouldHandleNullUuid() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withUuid(null);
		final long result = calculator.getValue();

		assertEquals(0, result);
	}

	@Test
	@DisplayName("Should support method chaining")
	void shouldSupportMethodChaining() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final long result = calculator
			.withLong(100L)
			.withInt(200)
			.withString("test")
			.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer longBuffer = ByteBuffer.allocate(8);
		longBuffer.putLong(100L);
		expected.update(longBuffer.array());

		final ByteBuffer intBuffer = ByteBuffer.allocate(4);
		intBuffer.putInt(200);
		expected.update(intBuffer.array());

		expected.update("test".getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should reset calculator properly")
	void shouldResetCalculatorProperly() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLong(12345L);
		final long firstResult = calculator.getValue();

		calculator.reset().withLong(12345L);
		final long secondResult = calculator.getValue();

		assertEquals(firstResult, secondResult);
	}

	@Test
	@DisplayName("Should handle edge case with max long value")
	void shouldHandleEdgeCaseWithMaxLongValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLong(Long.MAX_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(Long.MAX_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with min long value")
	void shouldHandleEdgeCaseWithMinLongValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withLong(Long.MIN_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(Long.MIN_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with max int value")
	void shouldHandleEdgeCaseWithMaxIntValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withInt(Integer.MAX_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(Integer.MAX_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle edge case with min int value")
	void shouldHandleEdgeCaseWithMinIntValue() {
		final Crc32Calculator calculator = new Crc32Calculator();
		calculator.withInt(Integer.MIN_VALUE);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		final ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putInt(Integer.MIN_VALUE);
		expected.update(buffer.array());

		assertEquals(expected.getValue(), result);
	}

	@Test
	@DisplayName("Should handle empty arrays consistently")
	void shouldHandleEmptyArraysConsistently() {
		final Crc32Calculator calculator = new Crc32Calculator();
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
		final Crc32Calculator calculator1 = new Crc32Calculator();
		calculator1.withLong(100L)
			.withInt(200)
			.withByte((byte) 5)
			.withString("test")
			.withUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
		final long result1 = calculator1.getValue();

		final Crc32Calculator calculator2 = new Crc32Calculator();
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
		final Crc32Calculator calculator1 = new Crc32Calculator();
		calculator1.withLong(100L).withInt(200);
		final long result1 = calculator1.getValue();

		final Crc32Calculator calculator2 = new Crc32Calculator();
		calculator2.withInt(200).withLong(100L);
		final long result2 = calculator2.getValue();

		assertNotEquals(result1, result2);
	}

	@Test
	@DisplayName("Should handle unicode strings correctly")
	void shouldHandleUnicodeStringsCorrectly() {
		final Crc32Calculator calculator = new Crc32Calculator();
		final String unicodeString = "Hello 世界 🌍";
		calculator.withString(unicodeString);
		final long result = calculator.getValue();

		final CRC32C expected = new CRC32C();
		expected.update(unicodeString.getBytes(StandardCharsets.UTF_8));

		assertEquals(expected.getValue(), result);
	}

}
