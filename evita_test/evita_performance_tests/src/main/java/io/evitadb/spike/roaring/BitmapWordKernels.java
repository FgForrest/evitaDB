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

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * Word-level kernels over a roaring `BitmapContainer`'s 1024-word (8 KiB) backing array, one scalar arm per
 * vector arm so a JMH run can price them against each other.
 *
 * The scalar arms are byte-for-byte what `BitmapContainer` runs today - a count pass followed by a store pass,
 * or `clone()` followed by an OR pass followed by a count pass. They are reproduced here rather than called
 * through the container so the benchmark measures the loop and nothing else, and so a `-XX:-UseSuperWord` run
 * can price how much of the "scalar" number C2's auto-vectoriser is already responsible for.
 *
 * Nothing here depends on evitaDB, which keeps the classes compilable and verifiable on their own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class BitmapWordKernels {

	/**
	 * Words in a roaring bitmap container - 65536 bits at 64 bits per word.
	 */
	public static final int WORDS = 1024;
	/**
	 * Widest long species the platform offers; 512-bit (8 lanes) on an AVX-512 machine. `WORDS` is divisible by
	 * every lane count this can take, so the kernels below need no tail loop - the tail is written anyway because
	 * a kernel that silently skips words is the kind of bug a benchmark never notices.
	 */
	private static final VectorSpecies<Long> LONGS = LongVector.SPECIES_PREFERRED;

	private BitmapWordKernels() {
	}

	/**
	 * Lane count of the long species the vector arms use, so a run can report the width it measured.
	 *
	 * @return number of longs per vector
	 */
	public static int laneCount() {
		return LONGS.length();
	}

	/* ------------------------------------------------------------------ cardinality ---------------------- */

	/**
	 * Population count of a word array - today's `BitmapContainer.computeCardinality()`.
	 *
	 * @param words the word array
	 * @return number of set bits
	 */
	public static int scalarCardinality(@Nonnull final long[] words) {
		int answer = 0;
		for (int k = 0; k < words.length; k++) {
			answer += Long.bitCount(words[k]);
		}
		return answer;
	}

	/**
	 * Population count accumulated lane-wise through `VPOPCNTQ`, reduced once at the end.
	 *
	 * @param words the word array
	 * @return number of set bits
	 */
	public static int vectorCardinality(@Nonnull final long[] words) {
		final int bound = LONGS.loopBound(words.length);
		LongVector accumulator = LongVector.zero(LONGS);
		int k = 0;
		for (; k < bound; k += LONGS.length()) {
			accumulator = accumulator.add(
				LongVector.fromArray(LONGS, words, k).lanewise(VectorOperators.BIT_COUNT)
			);
		}
		int answer = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < words.length; k++) {
			answer += Long.bitCount(words[k]);
		}
		return answer;
	}

	/* ------------------------------------------------------------------ AND cardinality ------------------ */

	/**
	 * Intersection cardinality without materialising it - today's `BitmapContainer.andCardinality(BitmapContainer)`.
	 *
	 * @param a first word array
	 * @param b second word array
	 * @return number of bits set in both
	 */
	public static int scalarAndCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		int answer = 0;
		for (int k = 0; k < a.length; k++) {
			answer += Long.bitCount(a[k] & b[k]);
		}
		return answer;
	}

	/**
	 * Intersection cardinality with the AND and the population count both done lane-wise.
	 *
	 * @param a first word array
	 * @param b second word array
	 * @return number of bits set in both
	 */
	public static int vectorAndCardinality(@Nonnull final long[] a, @Nonnull final long[] b) {
		final int bound = LONGS.loopBound(a.length);
		LongVector accumulator = LongVector.zero(LONGS);
		int k = 0;
		for (; k < bound; k += LONGS.length()) {
			accumulator = accumulator.add(
				LongVector.fromArray(LONGS, a, k)
					.and(LongVector.fromArray(LONGS, b, k))
					.lanewise(VectorOperators.BIT_COUNT)
			);
		}
		int answer = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			answer += Long.bitCount(a[k] & b[k]);
		}
		return answer;
	}

	/* ------------------------------------------------------------------ AND + store ---------------------- */

	/**
	 * Today's two-pass AND: one pass counts the result so the container type can be decided, a second pass
	 * writes it. Both passes read both inputs.
	 *
	 * @param a   first word array
	 * @param b   second word array
	 * @param out destination word array (may alias `a`, as `iand` does)
	 * @return cardinality of the result
	 */
	public static int scalarAndStoreCountTwoPass(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int answer = 0;
		for (int k = 0; k < a.length; k++) {
			answer += Long.bitCount(a[k] & b[k]);
		}
		for (int k = 0; k < a.length; k++) {
			out[k] = a[k] & b[k];
		}
		return answer;
	}

	/**
	 * One fused pass: load the pair once, AND once, store once, accumulate the population count of the value
	 * already in a register.
	 *
	 * @param a   first word array
	 * @param b   second word array
	 * @param out destination word array (may alias `a`)
	 * @return cardinality of the result
	 */
	public static int vectorAndStoreCountFused(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		final int bound = LONGS.loopBound(a.length);
		LongVector accumulator = LongVector.zero(LONGS);
		int k = 0;
		for (; k < bound; k += LONGS.length()) {
			final LongVector result = LongVector.fromArray(LONGS, a, k).and(LongVector.fromArray(LONGS, b, k));
			result.intoArray(out, k);
			accumulator = accumulator.add(result.lanewise(VectorOperators.BIT_COUNT));
		}
		int answer = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			final long word = a[k] & b[k];
			out[k] = word;
			answer += Long.bitCount(word);
		}
		return answer;
	}

	/**
	 * The scalar fused pass, kept as a third arm: it isolates how much of the fused win is the fusion itself
	 * rather than the vector width.
	 *
	 * @param a   first word array
	 * @param b   second word array
	 * @param out destination word array (may alias `a`)
	 * @return cardinality of the result
	 */
	public static int scalarAndStoreCountFused(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int answer = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] & b[k];
			out[k] = word;
			answer += Long.bitCount(word);
		}
		return answer;
	}

	/* ------------------------------------------------------------------ OR + clone + store --------------- */

	/**
	 * Today's `BitmapContainer.or(BitmapContainer)` shape: copy the receiver into a fresh array, OR the argument
	 * into it, then count it. Three passes over 8 KiB, the first of them a `System.arraycopy`.
	 *
	 * The destination is passed in rather than allocated here so both arms allocate at the same place in the
	 * benchmark method and the allocation shows up identically in `-prof gc`.
	 *
	 * @param a   first word array (the receiver being cloned)
	 * @param b   second word array
	 * @param out destination word array, contents ignored on entry
	 * @return cardinality of the result
	 */
	public static int scalarOrCloneStoreCountThreePass(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		System.arraycopy(a, 0, out, 0, a.length);
		for (int k = 0; k < out.length; k++) {
			out[k] |= b[k];
		}
		int answer = 0;
		for (int k = 0; k < out.length; k++) {
			answer += Long.bitCount(out[k]);
		}
		return answer;
	}

	/**
	 * One fused pass replacing the copy, the OR and the count.
	 *
	 * @param a   first word array
	 * @param b   second word array
	 * @param out destination word array, contents ignored on entry
	 * @return cardinality of the result
	 */
	public static int vectorOrStoreCountFused(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		final int bound = LONGS.loopBound(a.length);
		LongVector accumulator = LongVector.zero(LONGS);
		int k = 0;
		for (; k < bound; k += LONGS.length()) {
			final LongVector result = LongVector.fromArray(LONGS, a, k).or(LongVector.fromArray(LONGS, b, k));
			result.intoArray(out, k);
			accumulator = accumulator.add(result.lanewise(VectorOperators.BIT_COUNT));
		}
		int answer = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < a.length; k++) {
			final long word = a[k] | b[k];
			out[k] = word;
			answer += Long.bitCount(word);
		}
		return answer;
	}

	/**
	 * The scalar fused OR pass, isolating fusion from vector width the same way the AND family does.
	 *
	 * @param a   first word array
	 * @param b   second word array
	 * @param out destination word array, contents ignored on entry
	 * @return cardinality of the result
	 */
	public static int scalarOrStoreCountFused(
		@Nonnull final long[] a, @Nonnull final long[] b, @Nonnull final long[] out) {
		int answer = 0;
		for (int k = 0; k < a.length; k++) {
			final long word = a[k] | b[k];
			out[k] = word;
			answer += Long.bitCount(word);
		}
		return answer;
	}

	/* ------------------------------------------------------------------ range cardinality ---------------- */

	/**
	 * Population count over the bit range `[start, end)` - a copy of `Util.cardinalityInBitmapRange`, reproduced
	 * so the vector arm can be priced against the identical loop rather than across a module boundary.
	 *
	 * @param words the word array
	 * @param start first bit index, inclusive
	 * @param end   bit index one past the last, exclusive
	 * @return number of set bits inside the range
	 */
	public static int scalarCardinalityInRange(@Nonnull final long[] words, final int start, final int end) {
		if (start >= end) {
			return 0;
		}
		final int firstWord = start / 64;
		final int endWord = (end - 1) / 64;
		if (firstWord == endWord) {
			return Long.bitCount(words[firstWord] & ((~0L << start) & (~0L >>> -end)));
		}
		int answer = Long.bitCount(words[firstWord] & (~0L << start));
		for (int k = firstWord + 1; k < endWord; k++) {
			answer += Long.bitCount(words[k]);
		}
		answer += Long.bitCount(words[endWord] & (~0L >>> -end));
		return answer;
	}

	/**
	 * The same range count with the fully covered interior words counted lane-wise and only the two partial end
	 * words handled scalar.
	 *
	 * @param words the word array
	 * @param start first bit index, inclusive
	 * @param end   bit index one past the last, exclusive
	 * @return number of set bits inside the range
	 */
	public static int vectorCardinalityInRange(@Nonnull final long[] words, final int start, final int end) {
		if (start >= end) {
			return 0;
		}
		final int firstWord = start / 64;
		final int endWord = (end - 1) / 64;
		if (firstWord == endWord) {
			return Long.bitCount(words[firstWord] & ((~0L << start) & (~0L >>> -end)));
		}
		int answer = Long.bitCount(words[firstWord] & (~0L << start));
		final int from = firstWord + 1;
		final int bound = from + LONGS.loopBound(endWord - from);
		LongVector accumulator = LongVector.zero(LONGS);
		int k = from;
		for (; k < bound; k += LONGS.length()) {
			accumulator = accumulator.add(
				LongVector.fromArray(LONGS, words, k).lanewise(VectorOperators.BIT_COUNT)
			);
		}
		answer += (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < endWord; k++) {
			answer += Long.bitCount(words[k]);
		}
		answer += Long.bitCount(words[endWord] & (~0L >>> -end));
		return answer;
	}

}
