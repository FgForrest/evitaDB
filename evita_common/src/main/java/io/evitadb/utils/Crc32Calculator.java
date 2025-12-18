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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * A wrapper around Java's standard {@link CRC32C} class that provides allocation-free methods for computing
 * CRC32C checksums from various data types. This calculator maintains internal state and allows incremental
 * updates through various `with*` methods.
 *
 * The implementation is optimized for performance with:
 * - Reusable internal buffer to avoid allocations
 * - Direct byte array access where possible
 * - No boxing/unboxing in critical paths
 * - Manual loops instead of streams
 *
 * Example usage:
 * ```java
 * final Crc32Calculator calculator = new Crc32Calculator();
 * calculator.withLong(12345L)
 *     .withString("example")
 *     .withInt(42);
 * final long checksum = calculator.getValue();
 * calculator.reset(); // Reuse calculator
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class Crc32Calculator {
	/**
	 * Internal CRC32C instance for computing checksums.
	 */
	private final CRC32C crc32C;

	/**
	 * Reusable 8-byte buffer for primitive type conversions to avoid allocations.
	 */
	private final byte[] buffer;

	/**
	 * Creates a new CRC32 calculator with initialized internal state.
	 */
	public Crc32Calculator() {
		this.crc32C = new CRC32C();
		this.buffer = new byte[8];
	}

	/**
	 * Updates the checksum with a primitive long value.
	 *
	 * @param value the long value to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withLong(long value) {
		// write in big-endian order to match ByteBuffer default used in tests
		this.buffer[0] = (byte) (value >> 56);
		this.buffer[1] = (byte) (value >> 48);
		this.buffer[2] = (byte) (value >> 40);
		this.buffer[3] = (byte) (value >> 32);
		this.buffer[4] = (byte) (value >> 24);
		this.buffer[5] = (byte) (value >> 16);
		this.buffer[6] = (byte) (value >> 8);
		this.buffer[7] = (byte) value;
		this.crc32C.update(this.buffer, 0, 8);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Long value. Null values are treated as 0.
	 *
	 * @param value the Long value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withLong(@Nullable final Long value) {
		return withLong(value == null ? 0L : value);
	}

	/**
	 * Updates the checksum with an array of primitive long values.
	 *
	 * @param values the long array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withLongArray(@Nullable final long[] values) {
		if (values != null) {
			for (final long value : values) {
				withLong(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with an array of boxed Long values. Null array elements are treated as 0.
	 *
	 * @param values the Long array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withLongArray(@Nullable final Long[] values) {
		if (values != null) {
			for (final Long value : values) {
				withLong(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with a primitive int value.
	 *
	 * @param value the int value to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withInt(int value) {
		// write in big-endian order to match ByteBuffer default used in tests
		this.buffer[0] = (byte) (value >> 24);
		this.buffer[1] = (byte) (value >> 16);
		this.buffer[2] = (byte) (value >> 8);
		this.buffer[3] = (byte) value;
		this.crc32C.update(this.buffer, 0, 4);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Integer value. Null values are treated as 0.
	 *
	 * @param value the Integer value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withInt(@Nullable final Integer value) {
		return withInt(value == null ? 0 : value);
	}

	/**
	 * Updates the checksum with an array of primitive int values.
	 *
	 * @param values the int array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withIntArray(@Nullable final int[] values) {
		if (values != null) {
			for (final int value : values) {
				withInt(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with an array of boxed Integer values. Null array elements are treated as 0.
	 *
	 * @param values the Integer array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withIntArray(@Nullable final Integer[] values) {
		if (values != null) {
			for (final Integer value : values) {
				withInt(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with a primitive byte value.
	 *
	 * @param value the byte value to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withByte(byte value) {
		this.buffer[0] = value;
		this.crc32C.update(this.buffer, 0, 1);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Byte value. Null values are treated as 0.
	 *
	 * @param value the Byte value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withByte(@Nullable final Byte value) {
		return withByte(value == null ? (byte) 0 : value);
	}

	/**
	 * Updates the checksum with an array of primitive byte values.
	 *
	 * @param values the byte array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withByteArray(@Nullable final byte[] values) {
		if (values != null) {
			this.crc32C.update(values, 0, values.length);
		}
		return this;
	}

	/**
	 * Updates the checksum with an array of boxed Byte values. Null array elements are treated as 0.
	 *
	 * @param values the Byte array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withByteArray(@Nullable final Byte[] values) {
		if (values != null) {
			for (final Byte value : values) {
				withByte(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with a String value encoded as UTF-8 bytes.
	 *
	 * @param value the String to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withString(@Nullable final String value) {
		if (value != null) {
			final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			this.crc32C.update(bytes, 0, bytes.length);
		}
		return this;
	}

	/**
	 * Updates the checksum with an array of String values.
	 *
	 * @param values the String array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withStringArray(@Nullable final String[] values) {
		if (values != null) {
			for (final String value : values) {
				withString(value);
			}
		}
		return this;
	}

	/**
	 * Updates the checksum with an OffsetDateTime value. The datetime is converted to epoch seconds (long)
	 * and nanosecond adjustment (int) for stable binary representation.
	 *
	 * @param value the OffsetDateTime to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withOffsetDateTime(@Nullable final OffsetDateTime value) {
		if (value != null) {
			// Store epoch second and nano for stable representation
			withLong(value.toEpochSecond());
			withInt(value.getNano());
		}
		return this;
	}

	/**
	 * Updates the checksum with a UUID value. The UUID is converted to its most significant and least
	 * significant bits (two long values).
	 *
	 * @param value the UUID to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withUuid(@Nullable final UUID value) {
		if (value != null) {
			withLong(value.getMostSignificantBits());
			withLong(value.getLeastSignificantBits());
		}
		return this;
	}

	/**
	 * Returns the current CRC32C checksum value as a long.
	 *
	 * @return the current checksum value
	 */
	public long getValue() {
		return this.crc32C.getValue();
	}

	/**
	 * Resets the calculator to its initial state, allowing it to be reused for new checksum computations.
	 *
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator reset() {
		this.crc32C.reset();
		return this;
	}

}
