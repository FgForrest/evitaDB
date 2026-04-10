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
	 * Reflected CRC-32C (Castagnoli) polynomial used as the feedback polynomial in the GF(2) shift matrix.
	 *
	 * The value `0x82F63B78` is the bit-reversal of the standard CRC-32C polynomial `0x1EDC6F41`. The reflected
	 * form is used because the CRC shift register processes bits LSB-first: the least-significant bit of each
	 * byte enters the register first, so the polynomial feedback taps must be represented in reversed bit order.
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
	 * Combines two independently computed CRC32C values into the CRC32C of their concatenation, without
	 * requiring the original input bytes.
	 *
	 * **Fundamental invariant:** `combine(crc1, crc2, len2)` produces exactly the same result as computing
	 * CRC32C over the byte sequence `data1 || data2`, where `crc1 = CRC32C(data1)` and
	 * `crc2 = CRC32C(data2)`. This makes it possible to build cumulative WAL checksums by combining
	 * per-chunk checksums without re-reading earlier chunks.
	 *
	 * **Edge case:** when `len2 <= 0`, `crc1` is returned unchanged — the empty second segment contributes
	 * nothing to the combined checksum.
	 *
	 * **Algorithm:** appending `len2` zero bytes to `data1` has a deterministic effect on its CRC32C that
	 * can be expressed as a linear transformation over GF(2). That transformation is represented as a 32×32
	 * binary matrix, and the effect of `len2` zero bytes is computed via GF(2) matrix exponentiation using
	 * repeated squaring (binary exponentiation). The result is the CRC32C of `data1 || 0^len2`. XOR-ing with
	 * `crc2` then cancels the zeros and replaces them with the actual contribution of `data2`, yielding
	 * `CRC32C(data1 || data2)`.
	 *
	 * @param crc1 CRC32C checksum of the first data segment, as returned by {@link java.util.zip.CRC32C#getValue()}
	 * @param crc2 CRC32C checksum of the second data segment, as returned by {@link java.util.zip.CRC32C#getValue()}
	 * @param len2 number of bytes in the second data segment; values <= 0 cause `crc1` to be returned unchanged
	 * @return CRC32C of the concatenation `data1 || data2`, masked to 32 bits
	 */
	public static long combine(long crc1, long crc2, long len2) {
		crc1 &= 0xFFFFFFFFL;
		crc2 &= 0xFFFFFFFFL;

		if (len2 <= 0) {
			return crc1;
		}

		// odd[] holds the GF(2) transition matrix for a single zero-bit shift through the CRC register.
		// Row 0 is the feedback polynomial (models the XOR feedback on shift-out of a '1' bit);
		// rows 1..31 are the identity shift rows (each bit position advances by one).
		final long[] odd = new long[32];
		// even[] holds the two-bit shift matrix, computed as odd^2 via matrix squaring.
		final long[] even = new long[32];

		// Build the one-zero-bit shift matrix in odd[]
		odd[0] = CRC32C_POLY;
		long row = 1;
		for (int i = 1; i < 32; i++) {
			odd[i] = row;
			row <<= 1;
		}

		// Square once: odd -> even holds the two-bit (one-byte) shift matrix
		gf2MatrixSquare(even, odd);

		// Square again: even -> odd holds the four-bit (two-byte) shift matrix.
		// From here the loop performs binary exponentiation over the bit representation of len2,
		// doubling the exponent each iteration and applying the matrix whenever the corresponding
		// bit of len2 is set.
		gf2MatrixSquare(odd, even);

		// Apply the effect of len2 zero bytes to crc1 using binary (repeated-squaring) exponentiation.
		// Each loop iteration squares the current matrix (doubling the represented shift distance) and,
		// when the low bit of len2 is set, multiplies crc1 by that matrix to accumulate the shift.
		do {
			// Square odd -> even: even now represents twice as many zero bytes as before
			gf2MatrixSquare(even, odd);
			if ((len2 & 1L) != 0) {
				// This power-of-two bit is set — apply the corresponding shift to crc1
				crc1 = gf2MatrixTimes(even, crc1);
			}
			len2 >>= 1;
			if (len2 == 0) {
				break;
			}

			// Square even -> odd for the next bit position
			gf2MatrixSquare(odd, even);
			if ((len2 & 1L) != 0) {
				crc1 = gf2MatrixTimes(odd, crc1);
			}
			len2 >>= 1;
		} while (len2 != 0);

		// crc1 now equals CRC32C(data1 || 0^len2). XOR-ing with crc2 replaces the zero-padding
		// contribution with data2's contribution, yielding CRC32C(data1 || data2).
		return (crc1 ^ crc2) & 0xFFFFFFFFL;
	}

	/**
	 * Multiplies a 32-bit column vector by a 32×32 matrix over GF(2), the binary Galois field where
	 * addition is XOR and multiplication is AND.
	 *
	 * Each bit of `vec` selects whether the corresponding row of `mat` is XOR-ed into the result.
	 * This is the standard GF(2) matrix-vector product. Only the lower 32 bits of the result are
	 * significant; the upper bits are masked off before returning.
	 *
	 * @param mat 32-element array where each entry encodes one row of the 32×32 GF(2) matrix
	 * @param vec 32-bit column vector whose bits select which rows to XOR together
	 * @return the 32-bit GF(2) matrix-vector product `mat * vec`
	 */
	private static long gf2MatrixTimes(@Nonnull long[] mat, long vec) {
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
	 * Computes the square of a 32×32 matrix over GF(2) (the binary Galois field where addition is XOR
	 * and multiplication is AND), storing the result in `square`.
	 *
	 * Each row `i` of `square` is computed as `gf2MatrixTimes(mat, mat[i])`, i.e. `mat` applied to
	 * itself row by row. This is used by {@link #combine} to double the represented shift distance
	 * during binary exponentiation.
	 *
	 * @param square output array that will receive the squared matrix; must have length >= 32
	 * @param mat    input matrix to square; must have length >= 32 and must not alias `square`
	 */
	private static void gf2MatrixSquare(@Nonnull long[] square, @Nonnull long[] mat) {
		for (int i = 0; i < 32; i++) {
			square[i] = gf2MatrixTimes(mat, mat[i]);
		}
	}

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
	 * Updates the checksum with a slice of byte array.
	 *
	 * @param values the byte array
	 * @param offset the start offset
	 * @param length the number of bytes to use
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32Calculator withByteArray(@Nullable final byte[] values, int offset, int length) {
		if (values != null) {
			this.crc32C.update(values, offset, length);
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
