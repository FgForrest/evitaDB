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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
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
 * final Crc32CWrapper calculator = new Crc32CWrapper();
 * calculator.withLong(12345L)
 *     .withString("example")
 *     .withInt(42);
 * final long checksum = calculator.getValue();
 * calculator.reset(); // Reuse calculator
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class Crc32CWrapper {
	/**
	 * Reflected CRC-32C (Castagnoli) polynomial.
	 */
	private static final long CRC32C_POLY = 0x82F63B78L;

	/**
	 * Internal CRC32C instance for computing checksums.
	 */
	private final CRC32C crc32C;

	/**
	 * Reusable 8-byte buffer for primitive type conversions to avoid allocations.
	 */
	private final byte[] buffer;

	/**
	 * Tracks the cumulative length of data added to the current CRC32C instance since the last
	 * {@link #getValue()} call. Used as the length parameter when combining the current CRC32C
	 * value with the stored cumulative checksum.
	 */
	private int pendingLength = 0;

	/**
	 * Stores the cumulative checksum value computed from all previous data. When {@link #getValue()}
	 * is called, this value is combined with the current CRC32C checksum to produce the final
	 * cumulative result. This allows the wrapper to maintain a running checksum across multiple
	 * getValue() calls without resetting the underlying state.
	 */
	private long checksum = 0L;

	/**
	 * Combine two CRC32C values into the CRC32C of (data1 || data2).
	 *
	 * @param crc1 CRC32C of data1 (as returned by java.util.zip.CRC32C#getValue()).
	 * @param crc2 CRC32C of data2 (as returned by java.util.zip.CRC32C#getValue()).
	 * @param len2 number of bytes in data2.
	 * @return CRC32C of concatenation data1||data2
	 */
	public static long combine(long crc1, long crc2, long len2) {
		crc1 &= 0xFFFFFFFFL;
		crc2 &= 0xFFFFFFFFL;

		if (len2 <= 0) {
			return crc1;
		}

		long[] odd = new long[32];   // operator for one zero bit
		long[] even = new long[32];  // operator for two zero bits

		// Put operator for one zero bit in odd[]
		odd[0] = CRC32C_POLY;
		long row = 1;
		for (int i = 1; i < 32; i++) {
			odd[i] = row;
			row <<= 1;
		}

		// Put operator for two zero bits in even[]
		gf2MatrixSquare(even, odd);

		// Put operator for four zero bits in odd[]
		gf2MatrixSquare(odd, even);

		// Apply len2 zeros to crc1 (i.e., shift crc1 by len2 bytes)
		do {
			gf2MatrixSquare(even, odd);
			if ((len2 & 1L) != 0) {
				crc1 = gf2MatrixTimes(even, crc1);
			}
			len2 >>= 1;
			if (len2 == 0) {
				break;
			}

			gf2MatrixSquare(odd, even);
			if ((len2 & 1L) != 0) {
				crc1 = gf2MatrixTimes(odd, crc1);
			}
			len2 >>= 1;
		} while (len2 != 0);

		// Combine
		return (crc1 ^ crc2) & 0xFFFFFFFFL;
	}

	/**
	 * Multiplies a vector by a matrix in the field GF(2) (Galois Field 2).
	 * This operation applies a bitwise matrix-vector multiplication where
	 * the matrix is represented as an array of longs, and the vector is a long value.
	 *
	 * @param mat the matrix represented as an array of long values, where each long
	 *            corresponds to a row of the matrix.
	 * @param vec the vector represented as a long value, where each bit corresponds
	 *            to an element in the vector.
	 * @return the result of multiplying the matrix by the vector, represented as a long value.
	 *         Only the lower 32 bits of the result are significant.
	 */
	private static long gf2MatrixTimes(long[] mat, long vec) {
		long sum = 0;
		int idx = 0;
		while (vec != 0) {
			if ((vec & 1L) != 0) {
				sum ^= mat[idx];
			}
			vec >>>= 1;
			idx++;
		}
		return sum & 0xFFFFFFFFL;
	}

	/**
	 * Squares a given matrix in the field GF(2) (Galois Field 2).
	 * This operation computes the square of a matrix represented as an array of long values,
	 * where each long corresponds to a row of the matrix.
	 *
	 * @param square the output matrix where the squared result will be stored.
	 *               Each element of this array corresponds to a row of the resulting matrix.
	 * @param mat    the input matrix to be squared, represented as an array of long values.
	 *               Each element of this array corresponds to a row of the matrix to be squared.
	 */
	private static void gf2MatrixSquare(long[] square, long[] mat) {
		for (int i = 0; i < 32; i++) {
			square[i] = gf2MatrixTimes(mat, mat[i]);
		}
	}

	/**
	 * Creates a new CRC32 calculator with initialized internal state.
	 */
	public Crc32CWrapper() {
		this.crc32C = new CRC32C();
		this.buffer = new byte[8];
	}

	/**
	 * Initializes a new instance of the Crc32CWrapper with a specified initial checksum.
	 *
	 * @param initialChecksum the initial checksum value to set for this calculator
	 */
	public Crc32CWrapper(long initialChecksum) {
		this();
		this.checksum = initialChecksum;
	}

	/**
	 * Updates the checksum with a primitive long value.
	 *
	 * @param value the long value to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withLong(long value) {
		// write in little-endian order to match Kryo Output serialization
		this.buffer[0] = (byte) value;
		this.buffer[1] = (byte) (value >>> 8);
		this.buffer[2] = (byte) (value >>> 16);
		this.buffer[3] = (byte) (value >>> 24);
		this.buffer[4] = (byte) (value >>> 32);
		this.buffer[5] = (byte) (value >>> 40);
		this.buffer[6] = (byte) (value >>> 48);
		this.buffer[7] = (byte) (value >>> 56);
		this.crc32C.update(this.buffer, 0, 8);
		this.pendingLength = Math.addExact(this.pendingLength, 8);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Long value. Null values are treated as 0.
	 *
	 * @param value the Long value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withLong(@Nullable final Long value) {
		return withLong(value == null ? 0L : value);
	}

	/**
	 * Updates the checksum with an array of primitive long values.
	 *
	 * @param values the long array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withLongArray(@Nullable final long[] values) {
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
	public Crc32CWrapper withLongArray(@Nullable final Long[] values) {
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
	public Crc32CWrapper withInt(int value) {
		// write in little-endian order to match Kryo Output serialization
		this.buffer[0] = (byte) value;
		this.buffer[1] = (byte) (value >> 8);
		this.buffer[2] = (byte) (value >> 16);
		this.buffer[3] = (byte) (value >> 24);
		this.crc32C.update(this.buffer, 0, 4);
		this.pendingLength = Math.addExact(this.pendingLength, 4);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Integer value. Null values are treated as 0.
	 *
	 * @param value the Integer value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withInt(@Nullable final Integer value) {
		return withInt(value == null ? 0 : value);
	}

	/**
	 * Updates the checksum with an array of primitive int values.
	 *
	 * @param values the int array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withIntArray(@Nullable final int[] values) {
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
	public Crc32CWrapper withIntArray(@Nullable final Integer[] values) {
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
	public Crc32CWrapper withByte(byte value) {
		this.buffer[0] = value;
		this.crc32C.update(this.buffer, 0, 1);
		this.pendingLength = Math.addExact(this.pendingLength, 1);
		return this;
	}

	/**
	 * Updates the checksum with a boxed Byte value. Null values are treated as 0.
	 *
	 * @param value the Byte value to include in the checksum (null is treated as 0)
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withByte(@Nullable final Byte value) {
		return withByte(value == null ? (byte) 0 : value);
	}

	/**
	 * Updates the checksum with an array of primitive byte values.
	 *
	 * @param values the byte array to include in the checksum
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withByteArray(@Nullable final byte[] values) {
		if (values != null) {
			this.crc32C.update(values, 0, values.length);
			this.pendingLength = Math.addExact(this.pendingLength, values.length);
		}
		return this;
	}

	/**
	 * Updates the checksum with a slice of byte array.
	 *
	 * @param values the byte array
	 * @param offset the start offset
	 * @param length the number of bytes to use
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper withByteArray(@Nullable final byte[] values, int offset, int length) {
		if (values != null) {
			this.crc32C.update(values, offset, length);
			this.pendingLength = Math.addExact(this.pendingLength, length);
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
	public Crc32CWrapper withByteArray(@Nullable final Byte[] values) {
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
	public Crc32CWrapper withString(@Nullable final String value) {
		if (value != null) {
			final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			this.crc32C.update(bytes, 0, bytes.length);
			this.pendingLength = Math.addExact(this.pendingLength, bytes.length);
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
	public Crc32CWrapper withStringArray(@Nullable final String[] values) {
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
	public Crc32CWrapper withOffsetDateTime(@Nullable final OffsetDateTime value) {
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
	public Crc32CWrapper withUuid(@Nullable final UUID value) {
		if (value != null) {
			withLong(value.getMostSignificantBits());
			withLong(value.getLeastSignificantBits());
		}
		return this;
	}

	/**
	 * Combines the current checksum with another precomputed checksum as if the data
	 * represented by the other checksum was appended to the current data.
	 *
	 * @param checksum      the other checksum to combine
	 * @param contentLength the length of the data represented by the other checksum
	 */
	@Nonnull
	public Crc32CWrapper withAnotherChecksum(long checksum, int contentLength) {
		final long currentChecksum = getValue();
		this.reset();
		this.checksum = Crc32CWrapper.combine(currentChecksum, checksum, contentLength);
		return this;
	}

	/**
	 * Resets the calculator to its initial state (with zero value), allowing it to be reused for new checksum
	 * computations.
	 *
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper reset() {
		this.crc32C.reset();
		this.checksum = 0L;
		this.pendingLength = 0;
		return this;
	}

	/**
	 * Resets the calculator to its initial state with specified initial value, allowing it to be reused for
	 * new checksum computations.
	 *
	 * @param initialValue the initial checksum value to set
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper reset(long initialValue) {
		this.crc32C.reset();
		this.checksum = initialValue;
		this.pendingLength = 0;
		return this;
	}

	/**
	 * Returns the current CRC32C checksum value as a long.
	 *
	 * @return the current checksum value
	 */
	public long getValue() {
		// when we have a stored cumulative checksum, combine it with the current value
		if (this.checksum != 0L || this.pendingLength > 0) {
			final long cumulatedChecksum = Crc32CWrapper.combine(
				this.checksum, this.crc32C.getValue(), this.pendingLength
			);
			this.reset();
			this.checksum = cumulatedChecksum;
			return this.checksum;
		} else {
			return this.crc32C.getValue();
		}
	}

}
