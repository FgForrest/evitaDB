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

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * Candidate kernels for `ArrayContainer` intersection - the seven variants issue #1543 puts on the table,
 * counting and materialising, so the JMH harness can price them against the branchy merge that runs today.
 *
 * Variant letters follow the design note:
 *
 * - **A** branchy two-pointer merge (today; lives in `Util`, not here)
 * - **B** branch-free two-pointer merge, two formulations
 * - **C** scratch-bitmap probe: paint the shorter side, probe the longer
 * - **D** all-pairs over 8 `short` lanes with 8 precomputed rotations, in four formulations: `D` rolls the
 *   rotation loop, `Du` unrolls it onto constant shuffles, `Dt` reassociates the mask ORs into a balanced tree,
 *   and `Ds` drops the mask ORs entirely and sums eight independent lane counts
 * - **E** the same over 32 `short` lanes
 * - **F** the same over 16 `int` lanes after widening
 * - **G** D with a block range-skip
 * - **H** broadcast probe: one element of A against a whole block of B at a time
 *
 * `Ds` is only correct because both inputs are strictly ascending and therefore duplicate-free: a value of A can
 * equal at most one value of the B block, so exactly one of the eight rotations reports it and summing the eight
 * counts cannot double-count. With duplicates in either input the OR-ed mask would be required.
 *
 * Variant H is a different shape entirely: instead of comparing a block of A against a block of B, it keeps one
 * block of B resident and broadcasts a single A value across it, which costs one compare plus a mask test per A
 * element and one block advance per 32 B elements. That makes it `O(la + lb / lanes)` rather than
 * `O((la + lb) / lanes * lanes)`, so it should win wherever the two inputs are far apart in size - and, unlike
 * the compress-emitting all-pairs variant, its materialising form writes `out[pos]` with `pos <= ia`, which is
 * what `ArrayContainer.iand` needs when it passes its own `content` array as both input and output.
 *
 * The all-pairs family is CRoaring's `intersect_vector16` without `pcmpestrm`: compare an 8-lane block of A
 * against all 8 rotations of an 8-lane block of B, OR the masks, and the mask's true lanes are exactly the A
 * values present in the B block. Blocks advance on the standard `a_max <= b_max` / `b_max <= a_max` rule, which
 * is what keeps the output sorted and duplicate-free: a value of A is emitted only while its own block is loaded,
 * and it can match in at most one B block because B is strictly ascending.
 *
 * Nothing here depends on evitaDB, which keeps the classes compilable and verifiable on their own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ArrayIntersectKernels {

	/**
	 * Eight `short` lanes - the width issue #1543 names.
	 */
	private static final VectorSpecies<Short> SHORT_128 = ShortVector.SPECIES_128;
	/**
	 * Thirty-two `short` lanes, kept to confirm that width alone does not change the all-pairs ratio.
	 */
	private static final VectorSpecies<Short> SHORT_512 = ShortVector.SPECIES_512;
	/**
	 * Sixteen `int` lanes, the widened formulation.
	 */
	private static final VectorSpecies<Integer> INT_512 = IntVector.SPECIES_512;
	/**
	 * Sixteen `short` lanes, only ever used as the load species feeding the widening into `INT_512`.
	 */
	private static final VectorSpecies<Short> SHORT_256 = ShortVector.SPECIES_256;
	/**
	 * Rotations of an 8-lane block: `ROTATIONS_128[r]` maps lane `i` to lane `(i + r) % 8`.
	 */
	private static final VectorShuffle<Short>[] ROTATIONS_128 = rotations(SHORT_128);
	/**
	 * Rotations of a 32-lane block.
	 */
	private static final VectorShuffle<Short>[] ROTATIONS_512 = rotations(SHORT_512);
	/**
	 * Rotations of a 16-lane `int` block.
	 */
	private static final VectorShuffle<Integer>[] ROTATIONS_INT_512 = rotations(INT_512);
	/**
	 * Words in the scratch bitmap variant C paints into - a full container's worth.
	 */
	public static final int SCRATCH_WORDS = 1024;
	/**
	 * Shorter-side size below which the hybrid picks the broadcast probe over the all-pairs kernel.
	 */
	public static final int HYBRID_THRESHOLD = 64;
	/**
	 * The eight 8-lane rotations again, this time as individual constants. A shuffle read out of an array is an
	 * array load C2 cannot fold, so the rolled variant pays a load per compare; naming them makes the unrolled
	 * variants' shuffles compile-time constants.
	 */
	private static final VectorShuffle<Short> ROT_1 = ROTATIONS_128[1];
	private static final VectorShuffle<Short> ROT_2 = ROTATIONS_128[2];
	private static final VectorShuffle<Short> ROT_3 = ROTATIONS_128[3];
	private static final VectorShuffle<Short> ROT_4 = ROTATIONS_128[4];
	private static final VectorShuffle<Short> ROT_5 = ROTATIONS_128[5];
	private static final VectorShuffle<Short> ROT_6 = ROTATIONS_128[6];
	private static final VectorShuffle<Short> ROT_7 = ROTATIONS_128[7];

	private ArrayIntersectKernels() {
	}

	/**
	 * Builds every rotation shuffle of a species: entry `r` rotates a vector left by `r` lanes.
	 *
	 * @param species the species to build shuffles for
	 * @param <E>     the species element type
	 * @return one shuffle per lane offset
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <E> VectorShuffle<E>[] rotations(@Nonnull final VectorSpecies<E> species) {
		final VectorShuffle<E>[] result = new VectorShuffle[species.length()];
		for (int r = 0; r < result.length; r++) {
			result[r] = VectorShuffle.iota(species, r, 1, true);
		}
		return result;
	}

	/* ------------------------------------------------------------------ B: branch-free merge ------------- */

	/**
	 * Branch-free merge, conditional-expression formulation: every step consumes at least one element and the
	 * three updates are data-dependent only through `cmov`-shaped selects.
	 *
	 * @param a  first array, ascending, unsigned 16-bit values
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int branchFreeSelectCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < la && ib < lb) {
			final int va = a[ia];
			final int vb = b[ib];
			answer += va == vb ? 1 : 0;
			ia += va <= vb ? 1 : 0;
			ib += vb <= va ? 1 : 0;
		}
		return answer;
	}

	/**
	 * Branch-free merge, arithmetic formulation: the same three updates derived from the sign bit of the
	 * difference, so no select is needed at all.
	 *
	 * `va` and `vb` are both in `[0, 65535]`, so `va - vb` never overflows and `(d - 1) >>> 31` is `1` exactly
	 * when `d <= 0`; the mirrored expression gives `d >= 0`, and their AND gives equality.
	 *
	 * @param a  first array, ascending, unsigned 16-bit values
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int branchFreeArithmeticCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < la && ib < lb) {
			final int difference = a[ia] - b[ib];
			final int lessOrEqual = (difference - 1) >>> 31;
			final int greaterOrEqual = (-difference - 1) >>> 31;
			answer += lessOrEqual & greaterOrEqual;
			ia += lessOrEqual;
			ib += greaterOrEqual;
		}
		return answer;
	}

	/**
	 * Branch-free merge writing the intersection: the candidate is stored unconditionally at the current output
	 * position and the position only advances on a match, so the store needs no branch either.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb)` entries
	 * @return number of values written
	 */
	public static int branchFreeArithmeticIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < la && ib < lb) {
			final int difference = a[ia] - b[ib];
			final int lessOrEqual = (difference - 1) >>> 31;
			final int greaterOrEqual = (-difference - 1) >>> 31;
			out[answer] = a[ia];
			answer += lessOrEqual & greaterOrEqual;
			ia += lessOrEqual;
			ib += greaterOrEqual;
		}
		return answer;
	}

	/* ------------------------------------------------------------------ C: scratch-bitmap probe ---------- */

	/**
	 * Paints the shorter input into a caller-owned scratch bitmap and probes the longer one against it, then
	 * clears only the words it touched by walking the painted values again.
	 *
	 * This is the shape `BitmapContainer.andCardinality(ArrayContainer)` already runs, borrowed for two array
	 * containers. The scratch array stands in for a pooled buffer: it is passed in and left zeroed on exit, so
	 * successive invocations see the same clean state a pool would hand them.
	 *
	 * @param a       first array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       second array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @param scratch zeroed scratch of `SCRATCH_WORDS` words, returned zeroed
	 * @return cardinality of the intersection
	 */
	public static int scratchProbeCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final long[] scratch) {
		final char[] shorter = la <= lb ? a : b;
		final int shorterLength = Math.min(la, lb);
		final char[] longer = la <= lb ? b : a;
		final int longerLength = Math.max(la, lb);
		for (int i = 0; i < shorterLength; i++) {
			final int value = shorter[i];
			scratch[value >>> 6] |= 1L << value;
		}
		int answer = 0;
		for (int i = 0; i < longerLength; i++) {
			final int value = longer[i];
			answer += (int) ((scratch[value >>> 6] >>> value) & 1L);
		}
		for (int i = 0; i < shorterLength; i++) {
			scratch[shorter[i] >>> 6] = 0L;
		}
		return answer;
	}

	/**
	 * The materialising form of the scratch probe. The output is emitted in the order of the **longer** input,
	 * which is ascending, so the result is sorted.
	 *
	 * @param a       first array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       second array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @param scratch zeroed scratch of `SCRATCH_WORDS` words, returned zeroed
	 * @param out     destination, at least `min(la, lb)` entries
	 * @return number of values written
	 */
	public static int scratchProbeIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb,
		@Nonnull final long[] scratch, @Nonnull final char[] out) {
		final char[] shorter = la <= lb ? a : b;
		final int shorterLength = Math.min(la, lb);
		final char[] longer = la <= lb ? b : a;
		final int longerLength = Math.max(la, lb);
		for (int i = 0; i < shorterLength; i++) {
			final int value = shorter[i];
			scratch[value >>> 6] |= 1L << value;
		}
		int answer = 0;
		for (int i = 0; i < longerLength; i++) {
			final char value = longer[i];
			out[answer] = value;
			answer += (int) ((scratch[value >>> 6] >>> value) & 1L);
		}
		for (int i = 0; i < shorterLength; i++) {
			scratch[shorter[i] >>> 6] = 0L;
		}
		return answer;
	}

	/* ------------------------------------------------------------------ scalar tail --------------------- */

	/**
	 * The branchy merge over index windows, used as the tail of every all-pairs kernel once one side has fewer
	 * than a full block left.
	 *
	 * @param a  first array, ascending
	 * @param ia index of the first entry of `a` to consider
	 * @param la index one past the last entry of `a` to consider
	 * @param b  second array, ascending
	 * @param ib index of the first entry of `b` to consider
	 * @param lb index one past the last entry of `b` to consider
	 * @return cardinality of the intersection of the two windows
	 */
	private static int tailCardinality(
		@Nonnull final char[] a, int ia, final int la, @Nonnull final char[] b, int ib, final int lb) {
		int answer = 0;
		while (ia < la && ib < lb) {
			final int va = a[ia];
			final int vb = b[ib];
			if (va == vb) {
				answer++;
				ia++;
				ib++;
			} else if (va < vb) {
				ia++;
			} else {
				ib++;
			}
		}
		return answer;
	}

	/**
	 * The branchy merge over index windows, writing the intersection - the materialising tail.
	 *
	 * @param a      first array, ascending
	 * @param ia     index of the first entry of `a` to consider
	 * @param la     index one past the last entry of `a` to consider
	 * @param b      second array, ascending
	 * @param ib     index of the first entry of `b` to consider
	 * @param lb     index one past the last entry of `b` to consider
	 * @param out    destination
	 * @param outPos first free position in `out`
	 * @return the new first free position in `out`
	 */
	private static int tailIntersect(
		@Nonnull final char[] a, int ia, final int la, @Nonnull final char[] b, int ib, final int lb,
		@Nonnull final char[] out, int outPos) {
		while (ia < la && ib < lb) {
			final int va = a[ia];
			final int vb = b[ib];
			if (va == vb) {
				out[outPos++] = a[ia];
				ia++;
				ib++;
			} else if (va < vb) {
				ia++;
			} else {
				ib++;
			}
		}
		return outPos;
	}

	/* ------------------------------------------------------------------ D: all-pairs, 8 short lanes ----- */

	/**
	 * Variant D - the mechanism issue #1543 specifies: 8 `short` lanes, 8 compares and 7 rotations per block
	 * pair, mask population count for the cardinality.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsShort128Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				VectorMask<Short> found = va.compare(VectorOperators.EQ, vb);
				for (int r = 1; r < lanes; r++) {
					found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_128[r])));
				}
				answer += found.trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Variant D materialising through `VPCOMPRESSW`: the OR-ed mask marks exactly the lanes of the A block that
	 * occur in the B block, so compressing the A block under it and storing its first `trueCount` lanes emits
	 * them in order.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb)` entries
	 * @return number of values written
	 */
	public static int allPairsShort128Intersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int outPos = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				VectorMask<Short> found = va.compare(VectorOperators.EQ, vb);
				for (int r = 1; r < lanes; r++) {
					found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_128[r])));
				}
				final int matches = found.trueCount();
				if (matches > 0) {
					va.compress(found)
						.intoCharArray(out, outPos, VectorMask.fromLong(SHORT_128, (1L << matches) - 1L));
					outPos += matches;
				}
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
	}

	/* ------------------------------------------------------------------ Du/Dt/Ds: D, reformulated ------- */

	/**
	 * Variant Du - variant D with the rotation loop unrolled onto constant shuffles, the mask ORs still a serial
	 * dependence chain. Isolates the cost of the shuffle array load from the cost of the chain.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsUnrolledCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				VectorMask<Short> found = va.compare(VectorOperators.EQ, vb);
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_1)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_2)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_3)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_4)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_5)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_6)));
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROT_7)));
				answer += found.trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Variant Dt - Du with the eight masks reassociated into a balanced OR tree, so the chain is three deep
	 * instead of seven.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsTreeCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				answer += treeMask(va, vb).trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * The eight rotation compares of one block pair, OR-ed as a balanced tree. Lane `i` of the result is set
	 * exactly when `va` lane `i` occurs anywhere in `vb`.
	 *
	 * @param va the A block
	 * @param vb the B block
	 * @return the membership mask over `va`'s lanes
	 */
	@Nonnull
	private static VectorMask<Short> treeMask(@Nonnull final ShortVector va, @Nonnull final ShortVector vb) {
		final VectorMask<Short> m0 = va.compare(VectorOperators.EQ, vb);
		final VectorMask<Short> m1 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_1));
		final VectorMask<Short> m2 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_2));
		final VectorMask<Short> m3 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_3));
		final VectorMask<Short> m4 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_4));
		final VectorMask<Short> m5 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_5));
		final VectorMask<Short> m6 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_6));
		final VectorMask<Short> m7 = va.compare(VectorOperators.EQ, vb.rearrange(ROT_7));
		return m0.or(m1).or(m2.or(m3)).or(m4.or(m5).or(m6.or(m7)));
	}

	/**
	 * Variant Ds - the eight compares with no mask combination at all, their lane counts summed instead. Valid
	 * only because both inputs are duplicate-free, which makes the eight per-rotation counts disjoint.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsSummedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				answer += va.compare(VectorOperators.EQ, vb).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_1)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_2)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_3)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_4)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_5)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_6)).trueCount()
					+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_7)).trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Variant Dt materialising - the tree-OR mask feeding `VPCOMPRESSW`, which is the cheapest formulation that
	 * still produces the mask the compress needs.
	 *
	 * Not alias-safe: the compressed store writes a whole vector at the output cursor, which can run past the
	 * read cursor. `ArrayContainer.iand` would need a buffer for it.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 8` entries
	 * @return number of values written
	 */
	public static int allPairsTreeIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int outPos = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			while (true) {
				final VectorMask<Short> found = treeMask(va, vb);
				final int matches = found.trueCount();
				if (matches > 0) {
					va.compress(found)
						.intoCharArray(out, outPos, VectorMask.fromLong(SHORT_128, (1L << matches) - 1L));
					outPos += matches;
				}
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_128, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_128, b, ib);
				}
			}
		}
		return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
	}

	/**
	 * Variant Es - the 32-lane all-pairs with the same summed-count formulation as `Ds`, which is the only way
	 * a 32-rotation block pair avoids a 31-deep mask chain.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsShort512SummedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_512.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_512, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_512, b, ib);
			while (true) {
				int block = va.compare(VectorOperators.EQ, vb).trueCount();
				for (int r = 1; r < lanes; r++) {
					block += va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_512[r])).trueCount();
				}
				answer += block;
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_512, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_512, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Variant Gs - the block range-skip in front of the summed-count formulation, which is the cheapest
	 * all-pairs arm combined with the cheapest way to avoid running it.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsSkippingSummedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < blocksA && ib < blocksB) {
			final int maxA = a[ia + lanes - 1];
			final int maxB = b[ib + lanes - 1];
			// the two blocks cannot overlap - advance the one that ends first without comparing anything
			if (maxA < b[ib]) {
				ia += lanes;
				continue;
			}
			if (maxB < a[ia]) {
				ib += lanes;
				continue;
			}
			final ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			final ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			answer += va.compare(VectorOperators.EQ, vb).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_1)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_2)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_3)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_4)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_5)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_6)).trueCount()
				+ va.compare(VectorOperators.EQ, vb.rearrange(ROT_7)).trueCount();
			if (maxA <= maxB) {
				ia += lanes;
			}
			if (maxB <= maxA) {
				ib += lanes;
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/* ------------------------------------------------------------------ E: all-pairs, 32 short lanes ---- */

	/**
	 * Variant E - the same all-pairs shape at 32 `short` lanes, which costs 32 compares and 31 rotations per
	 * block pair while consuming four times as many elements.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsShort512Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_512.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			ShortVector va = ShortVector.fromCharArray(SHORT_512, a, ia);
			ShortVector vb = ShortVector.fromCharArray(SHORT_512, b, ib);
			while (true) {
				VectorMask<Short> found = va.compare(VectorOperators.EQ, vb);
				for (int r = 1; r < lanes; r++) {
					found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_512[r])));
				}
				answer += found.trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = ShortVector.fromCharArray(SHORT_512, a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = ShortVector.fromCharArray(SHORT_512, b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/* ------------------------------------------------------------------ F: all-pairs, 16 int lanes ------ */

	/**
	 * Variant F - all-pairs after widening to 16 `int` lanes. `S2I` sign-extends, so the values are masked back
	 * to their unsigned 16-bit range; equality is unaffected by that, but the mask keeps the vectors readable
	 * when debugging and costs one instruction per load.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsInt512Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = INT_512.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (ia < blocksA && ib < blocksB) {
			IntVector va = widen(a, ia);
			IntVector vb = widen(b, ib);
			while (true) {
				VectorMask<Integer> found = va.compare(VectorOperators.EQ, vb);
				for (int r = 1; r < lanes; r++) {
					found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_INT_512[r])));
				}
				answer += found.trueCount();
				final int maxA = a[ia + lanes - 1];
				final int maxB = b[ib + lanes - 1];
				if (maxA <= maxB) {
					ia += lanes;
					if (ia == blocksA) {
						break;
					}
					va = widen(a, ia);
				}
				if (maxB <= maxA) {
					ib += lanes;
					if (ib == blocksB) {
						break;
					}
					vb = widen(b, ib);
				}
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Loads 16 `char` values and widens them into 16 unsigned `int` lanes.
	 *
	 * @param values source array
	 * @param offset index of the first value to load
	 * @return the widened block
	 */
	@Nonnull
	private static IntVector widen(@Nonnull final char[] values, final int offset) {
		return (IntVector) ShortVector.fromCharArray(SHORT_256, values, offset)
			.convertShape(VectorOperators.S2I, INT_512, 0)
			.lanewise(VectorOperators.AND, 0xFFFF);
	}

	/* ------------------------------------------------------------------ G: D with a block range-skip ---- */

	/**
	 * Variant G - variant D with a range test in front of the block comparison: when one block's largest value
	 * is below the other block's smallest, the two share nothing and the block is skipped without a single
	 * compare.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int allPairsShort128SkippingCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		final int lanes = SHORT_128.length();
		final int blocksA = la & -lanes;
		final int blocksB = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < blocksA && ib < blocksB) {
			final int maxA = a[ia + lanes - 1];
			final int maxB = b[ib + lanes - 1];
			// the two blocks cannot overlap - advance the one that ends first without comparing anything
			if (maxA < b[ib]) {
				ia += lanes;
				continue;
			}
			if (maxB < a[ia]) {
				ib += lanes;
				continue;
			}
			final ShortVector va = ShortVector.fromCharArray(SHORT_128, a, ia);
			final ShortVector vb = ShortVector.fromCharArray(SHORT_128, b, ib);
			VectorMask<Short> found = va.compare(VectorOperators.EQ, vb);
			for (int r = 1; r < lanes; r++) {
				found = found.or(va.compare(VectorOperators.EQ, vb.rearrange(ROTATIONS_128[r])));
			}
			answer += found.trueCount();
			if (maxA <= maxB) {
				ia += lanes;
			}
			if (maxB <= maxA) {
				ib += lanes;
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/* ------------------------------------------------------------------ H: broadcast probe -------------- */

	/**
	 * Variant H at 32 `short` lanes - counting form.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeShort512Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeCardinality(SHORT_512, a, la, b, lb);
	}

	/**
	 * Variant H at 16 `short` lanes - counting form, kept to price the block width against the advance rate.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeShort256Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeCardinality(SHORT_256, a, la, b, lb);
	}

	/**
	 * Variant H at 32 `short` lanes - materialising form.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	public static int broadcastProbeShort512Intersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return broadcastProbeIntersect(SHORT_512, a, la, b, lb, out);
	}

	/**
	 * Variant H at 16 `short` lanes - materialising form.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	public static int broadcastProbeShort256Intersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return broadcastProbeIntersect(SHORT_256, a, la, b, lb, out);
	}

	/**
	 * Variant H with the sides put the right way round: the probe walks every element of the first argument and
	 * only advances a block of the second, so the **shorter** side has to be the one being walked.
	 *
	 * The synthetic grid hands the sides over in fixture order; a replay of real operands does not get to
	 * choose, so this is where the orientation is fixed. The intersection is symmetric and both inputs are
	 * ascending, so swapping changes neither the count nor the order of the values emitted.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeOrientedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return la <= lb
			? broadcastProbeCardinality(SHORT_512, a, la, b, lb)
			: broadcastProbeCardinality(SHORT_512, b, lb, a, la);
	}

	/**
	 * The oriented broadcast probe, materialising.
	 *
	 *
	 * **Not alias-safe.** The unoriented form writes `out[pos]` with `pos <= ia`, so it may share an array with
	 * the side it walks - which is what `ArrayContainer.iand` needs. Orienting breaks that: when the shorter
	 * side turns out to be the *other* operand, the walked array and the destination are different arrays, and
	 * writing into the destination clobbers blocks the probe has not read yet. A caller that wants both the
	 * orientation and an in-place result has to buffer. Speed and in-place are a genuine fork here, not an
	 * oversight.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	public static int broadcastProbeOrientedIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return la <= lb
			? broadcastProbeIntersect(SHORT_512, a, la, b, lb, out)
			: broadcastProbeIntersect(SHORT_512, b, lb, a, la, out);
	}

	/**
	 * The hybrid the census shape argues for: the broadcast probe while the shorter side is small enough that
	 * walking it costs less than streaming both sides, the summed all-pairs kernel otherwise.
	 *
	 * The threshold is a straight guess at this point and the JMH grid is what settles it; it is named here so
	 * the replay measures a policy rather than a kernel.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int hybridCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return Math.min(la, lb) <= HYBRID_THRESHOLD
			? broadcastProbeCachedOrientedCardinality(a, la, b, lb)
			: allPairsSummedCardinality(a, la, b, lb);
	}

	/**
	 * The broadcast probe itself: one block of B stays resident while single values of A are broadcast across
	 * it, the block advancing only once its largest element falls below the probe.
	 *
	 * The comparison of `a[ia]` against the block's last element is done on widened `int` values, which is the
	 * unsigned order the sorted arrays are in; the lane-wise equality is done on the raw `short` bit patterns,
	 * where equality is signedness-agnostic.
	 *
	 * @param species the block width
	 * @param a       first array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       second array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	private static int broadcastProbeCardinality(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		if (blocks > 0) {
			ShortVector block = ShortVector.fromCharArray(species, b, ib);
			probing:
			while (ia < la) {
				final int value = a[ia];
				while (value > b[ib + lanes - 1]) {
					ib += lanes;
					if (ib == blocks) {
						break probing;
					}
					block = ShortVector.fromCharArray(species, b, ib);
				}
				answer += block.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
				ia++;
			}
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * Variant H512b at 32 `short` lanes - the block reloaded on every probe instead of being carried across the
	 * loop.
	 *
	 * H keeps the resident block in a local across both the outer walk and the inner advance, which makes it a
	 * loop-carried vector value inside a nested loop - the shape C2's box elimination is least reliable on. This
	 * formulation never lets a vector cross a back-edge: the block is loaded, compared and dead inside one
	 * iteration. It trades block loads for that (one per probed element rather than one per advance), which is
	 * fewer loads exactly where the census says the work is - a short side against a long one.
	 *
	 * @param a  first array, ascending, the side that is walked
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending, the side that is blocked
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeReloadingShort512Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeReloadingCardinality(SHORT_512, a, la, b, lb);
	}

	/**
	 * Variant H256b - the reloading formulation at 16 `short` lanes.
	 *
	 * @param a  first array, ascending, the side that is walked
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending, the side that is blocked
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeReloadingShort256Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeReloadingCardinality(SHORT_256, a, la, b, lb);
	}

	/**
	 * The reloading broadcast probe with the shorter side put on the walked side.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeReloadingOrientedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return la <= lb
			? broadcastProbeReloadingCardinality(SHORT_512, a, la, b, lb)
			: broadcastProbeReloadingCardinality(SHORT_512, b, lb, a, la);
	}

	/**
	 * The reloading broadcast probe, materialising, with the shorter side walked.
	 *
	 *
	 * **Not alias-safe.** The unoriented form writes `out[pos]` with `pos <= ia`, so it may share an array with
	 * the side it walks - which is what `ArrayContainer.iand` needs. Orienting breaks that: when the shorter
	 * side turns out to be the *other* operand, the walked array and the destination are different arrays, and
	 * writing into the destination clobbers blocks the probe has not read yet. A caller that wants both the
	 * orientation and an in-place result has to buffer. Speed and in-place are a genuine fork here, not an
	 * oversight.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	public static int broadcastProbeReloadingOrientedIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return la <= lb
			? broadcastProbeReloadingIntersect(SHORT_512, a, la, b, lb, out)
			: broadcastProbeReloadingIntersect(SHORT_512, b, lb, a, la, out);
	}

	/**
	 * The reloading broadcast probe itself. No vector value crosses a loop back-edge.
	 *
	 * @param species the block width
	 * @param a       the walked array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       the blocked array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	private static int broadcastProbeReloadingCardinality(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int answer = 0;
		while (ia < la && ib < blocks) {
			final int value = a[ia];
			while (value > b[ib + lanes - 1]) {
				ib += lanes;
				if (ib == blocks) {
					return answer + tailCardinality(a, ia, la, b, ib, lb);
				}
			}
			answer += ShortVector.fromCharArray(species, b, ib)
				.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
			ia++;
		}
		return answer + tailCardinality(a, ia, la, b, ib, lb);
	}

	/**
	 * The reloading broadcast probe, materialising. Writes `out[pos]` with `pos <= ia`, so it may run in place.
	 *
	 * @param species the block width
	 * @param a       the walked array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       the blocked array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @param out     destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	private static int broadcastProbeReloadingIntersect(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int outPos = 0;
		while (ia < la && ib < blocks) {
			final char value = a[ia];
			while (value > b[ib + lanes - 1]) {
				ib += lanes;
				if (ib == blocks) {
					return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
				}
			}
			out[outPos] = value;
			outPos += ShortVector.fromCharArray(species, b, ib)
				.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
			ia++;
		}
		return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
	}

	/**
	 * Variant H512c at 32 `short` lanes - the reloading formulation with the current block's largest value kept
	 * in a local instead of re-read from the array on every comparison.
	 *
	 * The disassembly of H and H512b says the vector work is not the cost. Both compile to exactly
	 * `vmovdqu32` / `vpbroadcastw` / `vpcmpeqw k` / `kortestd` / `setne`, with the mask never leaving a
	 * k-register and no allocation or call anywhere in the loop. What surrounds those five instructions is the
	 * expense: a bounds check on `b[ib + lanes - 1]` that C2 cannot eliminate, the array read itself, a
	 * safepoint poll, and enough register pressure that array references are parked in XMM registers and pulled
	 * back through `vmovq` every iteration.
	 *
	 * Caching the block maximum removes one array read and one bounds check from the path taken when the block
	 * does **not** advance - which, on the shape the census actually produces (a short side against a long one,
	 * median four values against a median of 247), is almost every iteration.
	 *
	 * @param a  first array, ascending, the side that is walked
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending, the side that is blocked
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeCachedShort512Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeCachedCardinality(SHORT_512, a, la, b, lb);
	}

	/**
	 * Variant H256c - the cached formulation at 16 `short` lanes.
	 *
	 * @param a  first array, ascending, the side that is walked
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending, the side that is blocked
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeCachedShort256Cardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return broadcastProbeCachedCardinality(SHORT_256, a, la, b, lb);
	}

	/**
	 * The cached broadcast probe with the shorter side put on the walked side.
	 *
	 * @param a  first array, ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second array, ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	public static int broadcastProbeCachedOrientedCardinality(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return la <= lb
			? broadcastProbeCachedCardinality(SHORT_512, a, la, b, lb)
			: broadcastProbeCachedCardinality(SHORT_512, b, lb, a, la);
	}

	/**
	 * The cached broadcast probe, materialising, with the shorter side walked.
	 *
	 *
	 * **Not alias-safe.** The unoriented form writes `out[pos]` with `pos <= ia`, so it may share an array with
	 * the side it walks - which is what `ArrayContainer.iand` needs. Orienting breaks that: when the shorter
	 * side turns out to be the *other* operand, the walked array and the destination are different arrays, and
	 * writing into the destination clobbers blocks the probe has not read yet. A caller that wants both the
	 * orientation and an in-place result has to buffer. Speed and in-place are a genuine fork here, not an
	 * oversight.
	 *
	 * @param a   first array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	public static int broadcastProbeCachedOrientedIntersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return la <= lb
			? broadcastProbeCachedIntersect(SHORT_512, a, la, b, lb, out)
			: broadcastProbeCachedIntersect(SHORT_512, b, lb, a, la, out);
	}

	/**
	 * Variant H512c materialising, unoriented - the form that may run in place, because the array it walks and
	 * the array it writes are the same one.
	 *
	 * @param a   the walked array, ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   the blocked array, ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb) + 1` entries; may be `a`
	 * @return number of values written
	 */
	public static int broadcastProbeCachedShort512Intersect(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		return broadcastProbeCachedIntersect(SHORT_512, a, la, b, lb, out);
	}

	/**
	 * The cached broadcast probe itself.
	 *
	 * @param species the block width
	 * @param a       the walked array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       the blocked array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	private static int broadcastProbeCachedCardinality(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		if (blocks == 0 || la == 0) {
			return tailCardinality(a, 0, la, b, 0, lb);
		}
		int ia = 0;
		int ib = 0;
		int answer = 0;
		int blockMax = b[lanes - 1];
		while (ia < la) {
			final int value = a[ia];
			while (value > blockMax) {
				ib += lanes;
				if (ib == blocks) {
					return answer + tailCardinality(a, ia, la, b, ib, lb);
				}
				blockMax = b[ib + lanes - 1];
			}
			answer += ShortVector.fromCharArray(species, b, ib)
				.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
			ia++;
		}
		return answer;
	}

	/**
	 * The cached broadcast probe, materialising. Writes `out[pos]` with `pos <= ia`, so it may run in place.
	 *
	 * @param species the block width
	 * @param a       the walked array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       the blocked array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @param out     destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	private static int broadcastProbeCachedIntersect(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		if (blocks == 0 || la == 0) {
			return tailIntersect(a, 0, la, b, 0, lb, out, 0);
		}
		int ia = 0;
		int ib = 0;
		int outPos = 0;
		int blockMax = b[lanes - 1];
		while (ia < la) {
			final char value = a[ia];
			while (value > blockMax) {
				ib += lanes;
				if (ib == blocks) {
					return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
				}
				blockMax = b[ib + lanes - 1];
			}
			out[outPos] = value;
			outPos += ShortVector.fromCharArray(species, b, ib)
				.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
			ia++;
		}
		return outPos;
	}

	/**
	 * The materialising broadcast probe. The candidate is stored speculatively at `out[pos]`, and because `pos`
	 * never exceeds `ia` the destination may be the same array as `a` - which is exactly what
	 * `ArrayContainer.iand` does.
	 *
	 * @param species the block width
	 * @param a       first array, ascending
	 * @param la      number of leading entries of `a` to consider
	 * @param b       second array, ascending
	 * @param lb      number of leading entries of `b` to consider
	 * @param out     destination, at least `min(la, lb) + 1` entries
	 * @return number of values written
	 */
	private static int broadcastProbeIntersect(
		@Nonnull final VectorSpecies<Short> species, @Nonnull final char[] a, final int la,
		@Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		final int lanes = species.length();
		final int blocks = lb & -lanes;
		int ia = 0;
		int ib = 0;
		int outPos = 0;
		if (blocks > 0) {
			ShortVector block = ShortVector.fromCharArray(species, b, ib);
			probing:
			while (ia < la) {
				final char value = a[ia];
				while (value > b[ib + lanes - 1]) {
					ib += lanes;
					if (ib == blocks) {
						break probing;
					}
					block = ShortVector.fromCharArray(species, b, ib);
				}
				out[outPos] = value;
				outPos += block.compare(VectorOperators.EQ, (short) value).anyTrue() ? 1 : 0;
				ia++;
			}
		}
		return tailIntersect(a, ia, la, b, ib, lb, out, outPos);
	}

}
