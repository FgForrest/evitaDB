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

package io.evitadb.spike.roaring;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * Set-bit extraction kernels - the `Util.fillArray` / `Util.fillArrayAND` shape that turns a dense bitmap
 * container back into a sorted `char[]`.
 *
 * The scalar arm is the classic `tzcnt`/`blsr` loop: one iteration per set bit, each of them a dependent chain of
 * three instructions plus a store, and a loop-exit branch whose misprediction rate rises with density.
 *
 * Two vector arms are measured:
 *
 * - **byte compress** - CRoaring's `bitset_extract_setbits_avx512_uint16`: compress a constant `0..63` byte table
 *   under the word used as a 64-bit mask (`VPCOMPRESSB`), widen the two halves to `short`, add the word base and
 *   store each half under a mask of the lanes that are actually populated. One compress per word.
 * - **short compress** - the same idea done twice per word over 32 `short` lanes (`VPCOMPRESSW`) against two
 *   constant tables (`0..31`, `32..63`), which needs no widening step at all. Two compresses per word, no
 *   `VPMOVZXBW`.
 *
 * Both skip an all-zero word entirely, which is what makes the kernels density-sensitive in the opposite
 * direction to the scalar loop: the scalar loop is cheap when the bitmap is sparse, the vector loops are cheap
 * when it is dense.
 *
 * Nothing here depends on evitaDB, which keeps the classes compilable and verifiable on their own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ExtractKernels {

	/**
	 * Sixty-four `byte` lanes - one whole word's worth of bit positions.
	 */
	private static final VectorSpecies<Byte> BYTE_512 = ByteVector.SPECIES_512;
	/**
	 * Thirty-two `short` lanes - half a word's worth of bit positions.
	 */
	private static final VectorSpecies<Short> SHORT_512 = ShortVector.SPECIES_512;
	/**
	 * Sixteen `int` lanes - a quarter of a word's worth, the width the 32-bit output form stores through.
	 */
	private static final VectorSpecies<Integer> INT_512 = IntVector.SPECIES_512;
	/**
	 * Widest long species the platform offers - the width the empty-block scan tests through.
	 */
	private static final VectorSpecies<Long> LONGS = LongVector.SPECIES_PREFERRED;
	/**
	 * The constant `0..63` byte table the byte-compress arm selects from.
	 */
	private static final ByteVector BIT_INDEX_BYTES = byteIndexTable();
	/**
	 * The constant `0..31` short table the short-compress arm selects from for the low half of a word.
	 */
	private static final ShortVector BIT_INDEX_SHORTS_LOW = shortIndexTable(0);
	/**
	 * The constant `32..63` short table the short-compress arm selects from for the high half of a word.
	 */
	private static final ShortVector BIT_INDEX_SHORTS_HIGH = shortIndexTable(32);

	private ExtractKernels() {
	}

	/**
	 * Builds the constant `0..63` byte table.
	 *
	 * @return the table as a 64-lane byte vector
	 */
	@Nonnull
	private static ByteVector byteIndexTable() {
		final byte[] table = new byte[BYTE_512.length()];
		for (int i = 0; i < table.length; i++) {
			table[i] = (byte) i;
		}
		return ByteVector.fromArray(BYTE_512, table, 0);
	}

	/**
	 * Builds a constant 32-lane short table starting at `base`.
	 *
	 * @param base value of lane zero
	 * @return the table as a 32-lane short vector
	 */
	@Nonnull
	private static ShortVector shortIndexTable(final int base) {
		final short[] table = new short[SHORT_512.length()];
		for (int i = 0; i < table.length; i++) {
			table[i] = (short) (base + i);
		}
		return ShortVector.fromArray(SHORT_512, table, 0);
	}

	/* ------------------------------------------------------------------ plain extraction ----------------- */

	/**
	 * Today's extraction - a copy of `Util.fillArray`, reproduced here so a `-XX:-UseSuperWord` arm prices the
	 * identical loop.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @return number of values written
	 */
	public static int scalarExtract(@Nonnull final long[] words, @Nonnull final char[] out) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < words.length; k++) {
			long bits = words[k];
			while (bits != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bits));
				bits &= bits - 1;
			}
			base += 64;
		}
		return pos;
	}

	/**
	 * Byte-compress extraction, CRoaring's kernel expressed in the Vector API.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @return number of values written
	 */
	public static int vectorExtractByteCompress(@Nonnull final long[] words, @Nonnull final char[] out) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < words.length; k++) {
			final long bits = words[k];
			if (bits != 0) {
				final int advance = Long.bitCount(bits);
				final ByteVector compressed = BIT_INDEX_BYTES.compress(VectorMask.fromLong(BYTE_512, bits));
				emit(compressed, 0, base, Math.min(advance, 32), out, pos);
				if (advance > 32) {
					emit(compressed, 1, base, advance - 32, out, pos + 32);
				}
				pos += advance;
			}
			base += 64;
		}
		return pos;
	}

	/**
	 * Widens one half of the compressed byte table to `short`, adds the word base and stores the populated
	 * lanes.
	 *
	 * @param compressed the compressed index table
	 * @param part       `0` for the low 32 lanes, `1` for the high 32
	 * @param base       bit index of lane zero of the source word
	 * @param lanes      how many lanes of this half are populated
	 * @param out        destination
	 * @param outPos     first free position in `out`
	 */
	private static void emit(
		@Nonnull final ByteVector compressed, final int part, final int base, final int lanes,
		@Nonnull final char[] out, final int outPos) {
		((ShortVector) compressed.convertShape(VectorOperators.B2S, SHORT_512, part))
			.add((short) base)
			.intoCharArray(out, outPos, VectorMask.fromLong(SHORT_512, (1L << lanes) - 1L));
	}

	/**
	 * Short-compress extraction: two `VPCOMPRESSW` per word against two constant tables, no widening.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @return number of values written
	 */
	public static int vectorExtractShortCompress(@Nonnull final long[] words, @Nonnull final char[] out) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < words.length; k++) {
			final long bits = words[k];
			if (bits != 0) {
				final long low = bits & 0xFFFFFFFFL;
				if (low != 0) {
					final int lanes = Long.bitCount(low);
					BIT_INDEX_SHORTS_LOW.compress(VectorMask.fromLong(SHORT_512, low))
						.add((short) base)
						.intoCharArray(out, pos, VectorMask.fromLong(SHORT_512, (1L << lanes) - 1L));
					pos += lanes;
				}
				final long high = bits >>> 32;
				if (high != 0) {
					final int lanes = Long.bitCount(high);
					BIT_INDEX_SHORTS_HIGH.compress(VectorMask.fromLong(SHORT_512, high))
						.add((short) base)
						.intoCharArray(out, pos, VectorMask.fromLong(SHORT_512, (1L << lanes) - 1L));
					pos += lanes;
				}
			}
			base += 64;
		}
		return pos;
	}

	/* ------------------------------------------------------------------ fused AND extraction ------------- */

	/**
	 * Today's fused extraction - a copy of `Util.fillArrayAND`.
	 *
	 * @param a   first bitmap
	 * @param b   second bitmap
	 * @param out destination, at least `popcount(a AND b)` entries
	 * @return number of values written
	 */
	public static int scalarExtractAnd(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final char[] out) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < a.length; k++) {
			long bits = a[k] & b[k];
			while (bits != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bits));
				bits &= bits - 1;
			}
			base += 64;
		}
		return pos;
	}

	/* ------------------------------------------------------------------ empty-block skipping ------------ */

	/**
	 * Extraction that tests a whole block of words for emptiness before touching any of them.
	 *
	 * The `tzcnt` loop already costs nothing per *bit* - its expense on a sparse container is the 1024-word
	 * walk it performs to find the few words that are set. One vector compare says whether eight words are all
	 * zero, and its mask says which of them are not, so a container holding four values reads 128 vectors and
	 * decodes one word instead of reading 1024 words.
	 *
	 * This sits between the two existing arms: it keeps the scalar decoder, which wins when a set word holds
	 * one or two bits, and removes the empty-word traversal, which is what dominates at low density. The VBMI2
	 * compress kernel wins the opposite regime.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @return number of values written
	 */
	public static int skippingExtract(@Nonnull final long[] words, @Nonnull final char[] out) {
		final int lanes = LONGS.length();
		final int bound = LONGS.loopBound(words.length);
		int pos = 0;
		int k = 0;
		for (; k < bound; k += lanes) {
			long live = LongVector.fromArray(LONGS, words, k)
				.compare(VectorOperators.NE, 0L)
				.toLong();
			while (live != 0) {
				final int lane = Long.numberOfTrailingZeros(live);
				live &= live - 1;
				final int index = k + lane;
				final int base = index << 6;
				long bits = words[index];
				while (bits != 0) {
					out[pos++] = (char) (base + Long.numberOfTrailingZeros(bits));
					bits &= bits - 1;
				}
			}
		}
		for (; k < words.length; k++) {
			long bits = words[k];
			final int base = k << 6;
			while (bits != 0) {
				out[pos++] = (char) (base + Long.numberOfTrailingZeros(bits));
				bits &= bits - 1;
			}
		}
		return pos;
	}

	/**
	 * The empty-block skip applied to the 32-bit output form.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @param base  the container's high 16 bits, added to every emitted position
	 * @return number of values written
	 */
	public static int skippingExtract32(
		@Nonnull final long[] words, @Nonnull final int[] out, final int base) {
		final int lanes = LONGS.length();
		final int bound = LONGS.loopBound(words.length);
		int pos = 0;
		int k = 0;
		for (; k < bound; k += lanes) {
			long live = LongVector.fromArray(LONGS, words, k)
				.compare(VectorOperators.NE, 0L)
				.toLong();
			while (live != 0) {
				final int lane = Long.numberOfTrailingZeros(live);
				live &= live - 1;
				final int index = k + lane;
				final int high = base + (index << 6);
				long bits = words[index];
				while (bits != 0) {
					out[pos++] = high + Long.numberOfTrailingZeros(bits);
					bits &= bits - 1;
				}
			}
		}
		for (; k < words.length; k++) {
			long bits = words[k];
			final int high = base + (k << 6);
			while (bits != 0) {
				out[pos++] = high + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
			}
		}
		return pos;
	}

	/* ------------------------------------------------------------------ 32-bit extraction ---------------- */

	/**
	 * Today's 32-bit extraction - a copy of `BitmapContainer.fillLeastSignificant16bits`, the shape behind
	 * `PersistentRoaringBitmap.toArray()`. Unlike the `char[]` forms this one has no cardinality cap, so it is
	 * the site where a dense container is extracted.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries
	 * @param base  the container's high 16 bits, added to every emitted position
	 * @return number of values written
	 */
	public static int scalarExtract32(
		@Nonnull final long[] words, @Nonnull final int[] out, final int base) {
		int pos = 0;
		int high = base;
		for (int k = 0; k < words.length; k++) {
			long bits = words[k];
			while (bits != 0) {
				out[pos++] = high + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
			}
			high += 64;
		}
		return pos;
	}

	/**
	 * The 32-bit extraction through `VPCOMPRESSB`: one compress per word, then up to four widened masked stores
	 * of 16 `int` lanes each.
	 *
	 * @param words source bitmap
	 * @param out   destination, at least `popcount(words)` entries plus 16 of slack
	 * @param base  the container's high 16 bits, added to every emitted position
	 * @return number of values written
	 */
	public static int vectorExtract32(
		@Nonnull final long[] words, @Nonnull final int[] out, final int base) {
		int pos = 0;
		int high = base;
		for (int k = 0; k < words.length; k++) {
			final long bits = words[k];
			if (bits != 0) {
				final int advance = Long.bitCount(bits);
				final ByteVector compressed = BIT_INDEX_BYTES.compress(VectorMask.fromLong(BYTE_512, bits));
				for (int part = 0; part * 16 < advance; part++) {
					final int remaining = advance - part * 16;
					((IntVector) compressed.castShape(INT_512, part))
						.add(high)
						.intoArray(out, pos + part * 16, VectorMask.fromLong(INT_512, maskBits(remaining, 16)));
				}
				pos += advance;
			}
			high += 64;
		}
		return pos;
	}

	/**
	 * Builds a low-`n` bit mask, saturating at `lanes` so a full vector is stored unmasked.
	 *
	 * @param n     how many lanes are populated
	 * @param lanes the species' lane count
	 * @return the mask bits
	 */
	private static long maskBits(final int n, final int lanes) {
		return n >= lanes ? -1L : (1L << n) - 1L;
	}

	/**
	 * The short-compress extraction fused with the AND, so the intersection never reaches memory.
	 *
	 * @param a   first bitmap
	 * @param b   second bitmap
	 * @param out destination, at least `popcount(a AND b)` entries
	 * @return number of values written
	 */
	public static int vectorExtractAndShortCompress(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final char[] out) {
		int pos = 0;
		int base = 0;
		for (int k = 0; k < a.length; k++) {
			final long bits = a[k] & b[k];
			if (bits != 0) {
				final long low = bits & 0xFFFFFFFFL;
				if (low != 0) {
					final int lanes = Long.bitCount(low);
					BIT_INDEX_SHORTS_LOW.compress(VectorMask.fromLong(SHORT_512, low))
						.add((short) base)
						.intoCharArray(out, pos, VectorMask.fromLong(SHORT_512, (1L << lanes) - 1L));
					pos += lanes;
				}
				final long high = bits >>> 32;
				if (high != 0) {
					final int lanes = Long.bitCount(high);
					BIT_INDEX_SHORTS_HIGH.compress(VectorMask.fromLong(SHORT_512, high))
						.add((short) base)
						.intoCharArray(out, pos, VectorMask.fromLong(SHORT_512, (1L << lanes) - 1L));
					pos += lanes;
				}
			}
			base += 64;
		}
		return pos;
	}

}
