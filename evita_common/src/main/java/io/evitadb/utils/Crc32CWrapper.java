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
 * - Mathematical CRC state injection via {@link #forceValue(long)} to avoid expensive combine operations
 *   on every {@link #getValue()} call
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
	private static final int CRC32C_POLY = 0x82F63B78;

	/**
	 * CRC32C value of 4 zero bytes, used by {@link #forceValue(long)} to compensate for the initial
	 * `0xFFFFFFFF` internal state drift when injecting a target CRC value.
	 */
	private static final int CRC_OF_4_ZEROS = 0x48674BC7;

	/**
	 * Internal CRC32C instance for computing checksums.
	 */
	private final CRC32C crc32C;

	/**
	 * Reusable 8-byte buffer for primitive type conversions to avoid allocations.
	 */
	private final byte[] buffer;

	/**
	 * Precomputed GF(2) "doubling ladder" of CRC32C shift operators: `COMBINE_LADDER[j]` is the
	 * matrix `M^(8·2^j)`, where `M` is the one-zero-bit reflected-CRC32C shift operator (a 32×32 GF(2)
	 * matrix stored as an `int[32]`, one bit-column per int). Because these operators depend only on
	 * {@link #CRC32C_POLY} — never on the checksum values or the length being combined — they are built
	 * once at class load and shared immutably across all threads. `COMBINE_LADDER[0] = M^8` (one byte
	 * of zero-shift); each subsequent rung is the square of the previous. {@link Long#SIZE} rungs cover
	 * every {@code long} `len2`, so {@link #combine(long, long, long)} can fold `M^(8·len2)` into `crc1`
	 * one rung per set bit of `len2` — no per-call matrix rebuild and no allocation.
	 *
	 * At {@code int[32]} (128 B) per rung this is a fixed 8 KB for the whole JVM.
	 */
	private static final int[][] COMBINE_LADDER = buildCombineLadder();

	/**
	 * Builds the {@link #COMBINE_LADDER} doubling ladder once at class load. `M` is the operator for a
	 * single zero bit; three squarings give `M^8` (the first rung), and each further rung is the square
	 * of the previous (`M^(8·2^j)`).
	 *
	 * @return the immutable ladder of {@link Long#SIZE} GF(2) shift operators
	 */
	@Nonnull
	private static int[][] buildCombineLadder() {
		// M = operator for one zero bit
		final int[] m = new int[32];
		m[0] = CRC32C_POLY;
		int row = 1;
		for (int i = 1; i < 32; i++) {
			m[i] = row;
			row <<= 1;
		}
		// first rung = M^8 (three squarings: M -> M^2 -> M^4 -> M^8)
		final int[] m2 = new int[32];
		final int[] m4 = new int[32];
		final int[] m8 = new int[32];
		gf2MatrixSquare(m2, m);
		gf2MatrixSquare(m4, m2);
		gf2MatrixSquare(m8, m4);
		final int[][] ladder = new int[Long.SIZE][];
		ladder[0] = m8;
		// each subsequent rung is the square of the previous: M^(8·2^j)
		for (int j = 1; j < ladder.length; j++) {
			final int[] next = new int[32];
			gf2MatrixSquare(next, ladder[j - 1]);
			ladder[j] = next;
		}
		return ladder;
	}

	/**
	 * Combines two CRC32C values into the CRC32C of (data1 || data2), i.e. computes the checksum of the
	 * concatenation from the two part checksums and the length of the second part.
	 *
	 * Applies the precomputed GF(2) {@link #COMBINE_LADDER} to `crc1` once per set bit of `len2` — there
	 * is no per-call matrix rebuild and no allocation. The result is bit-identical to a direct CRC32C of
	 * the concatenated bytes (the ladder is a mathematical identity: `M^(8·len2)` factored over the set
	 * bits of `len2`, and powers of `M` commute so the fold order is irrelevant).
	 *
	 * @param crc1 CRC32C of data1 (as returned by java.util.zip.CRC32C#getValue()).
	 * @param crc2 CRC32C of data2 (as returned by java.util.zip.CRC32C#getValue()).
	 * @param len2 number of bytes in data2.
	 * @return CRC32C of concatenation data1||data2, as an unsigned 32-bit value in a long
	 */
	public static long combine(long crc1, long crc2, long len2) {
		if (len2 <= 0) {
			return crc1 & 0xFFFFFFFFL;
		}
		int shifted = (int) crc1;
		long remaining = len2;
		int rung = 0;
		// fold M^(8·2^rung) into crc1 for each set bit of len2
		while (remaining != 0) {
			if ((remaining & 1L) != 0) {
				shifted = gf2MatrixTimes(COMBINE_LADDER[rung], shifted);
			}
			remaining >>>= 1;
			rung++;
		}
		return (shifted ^ (int) crc2) & 0xFFFFFFFFL;
	}

	/**
	 * Multiplies a vector by a matrix in GF(2) using Kernighan's bit-clearing trick.
	 * Uses {@link Integer#numberOfTrailingZeros(int)} (a JVM intrinsic mapping to a single
	 * TZCNT/BSF CPU instruction) to skip zero bits in O(1), reducing iterations from 32
	 * to the number of set bits (~16 on average for random CRC values).
	 *
	 * @param mat the 32x32 matrix represented as an int array, where each int
	 *            corresponds to a row of the matrix
	 * @param vec the vector represented as an int, where each bit corresponds
	 *            to an element in the vector
	 * @return the result of multiplying the matrix by the vector
	 */
	private static int gf2MatrixTimes(@Nonnull int[] mat, int vec) {
		int sum = 0;
		while (vec != 0) {
			sum ^= mat[Integer.numberOfTrailingZeros(vec)];
			vec &= vec - 1; // clear lowest set bit (Kernighan's trick)
		}
		return sum;
	}

	/**
	 * Squares a given 32x32 matrix in GF(2).
	 *
	 * @param square the output matrix where the squared result will be stored
	 * @param mat    the input matrix to be squared
	 */
	private static void gf2MatrixSquare(@Nonnull int[] square, @Nonnull int[] mat) {
		for (int i = 0; i < 32; i++) {
			square[i] = gf2MatrixTimes(mat, mat[i]);
		}
	}

	/**
	 * Reverses 32 steps of the reflected CRC32C shift register to find the 4-byte input
	 * that produces a given internal state when processed from state zero.
	 *
	 * The reflected CRC32C forward step is: `s = (s >>> 1) ^ ((s & 1) != 0 ? POLY : 0)`.
	 * The MSB of the current state reveals whether the previous step XORed with POLY
	 * (since POLY has MSB set), allowing deterministic reversal.
	 *
	 * @param value the target internal state to reverse
	 * @return the 32-bit value whose 4 little-endian bytes produce the target state
	 */
	private static int reverseCrc32c(int value) {
		int r = value;
		for (int i = 0; i < 32; i++) {
			if ((r & 0x80000000) != 0) {
				// MSB set → previous step included XOR with POLY → bit 0 was 1
				r = ((r ^ CRC32C_POLY) << 1) | 1;
			} else {
				// MSB clear → previous step was a clean shift → bit 0 was 0
				r = r << 1;
			}
		}
		return r;
	}

	/**
	 * Forces the internal {@link CRC32C} state so that {@link #getValue()} returns the specified
	 * target value. This is achieved by computing a 4-byte "injection sequence" via inverse CRC
	 * mathematics and feeding it into a freshly reset CRC32C instance.
	 *
	 * The CRC state machine is Markovian — its next state depends only on the current register
	 * value and the input, not on the history of how the register reached that value. Therefore,
	 * subsequent {@link CRC32C#update} calls will produce results identical to those that would
	 * be obtained if the original data (whose checksum equals `targetValue`) had been fed instead.
	 *
	 * @param targetValue the desired CRC32C checksum value that {@link #getValue()} should return
	 */
	private void forceValue(long targetValue) {
		this.crc32C.reset();
		// compensate for the 0xFFFFFFFF initial state drift over the 4 injection bytes
		final int delta = (int) targetValue ^ CRC_OF_4_ZEROS;
		final int injection = reverseCrc32c(delta);
		this.buffer[0] = (byte) injection;
		this.buffer[1] = (byte) (injection >>> 8);
		this.buffer[2] = (byte) (injection >>> 16);
		this.buffer[3] = (byte) (injection >>> 24);
		this.crc32C.update(this.buffer, 0, 4);
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
	 * The internal CRC32C state is set so that {@link #getValue()} immediately returns
	 * the initial checksum, and subsequent data additions build on that state.
	 *
	 * @param initialChecksum the initial checksum value to set for this calculator
	 */
	public Crc32CWrapper(long initialChecksum) {
		this();
		forceValue(initialChecksum);
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
		final long combined = combine(getValue(), checksum, contentLength);
		forceValue(combined);
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
		return this;
	}

	/**
	 * Resets the calculator to its initial state with specified initial value, allowing it to be reused for
	 * new checksum computations. The internal CRC32C state is set so that {@link #getValue()} immediately
	 * returns the specified value, and subsequent data additions build on that state.
	 *
	 * @param initialValue the initial checksum value to set
	 * @return this calculator for method chaining
	 */
	@Nonnull
	public Crc32CWrapper reset(long initialValue) {
		forceValue(initialValue);
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

}
