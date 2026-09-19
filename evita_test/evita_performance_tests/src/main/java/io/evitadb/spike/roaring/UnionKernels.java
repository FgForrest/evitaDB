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

import javax.annotation.Nonnull;

/**
 * The array-first half of a lazy union policy: fold sorted `char[]` inputs into one sorted `char[]` for as long
 * as the running total stays inside a threshold, and report where it stopped.
 *
 * A JFR profile of the production facet workload makes this the largest single opportunity in the module. The
 * lazy-OR machinery is 26.4 % of query CPU, and **95 % of the 1.93 million bitmaps it lazily unions demote
 * straight back to an array container** - each of them having paid an 8 KiB allocation, a scatter, a population
 * count and a full 1024-word scan to get there. CRoaring avoids it by keeping an array-array lazy union as an
 * array while the total cardinality stays under `ARRAY_LAZY_LOWERBOUND` (1024, `mixed_union.c:247`); the Java
 * port never had that branch.
 *
 * This class deliberately stops at the array boundary. It does the merging and says where the threshold was
 * crossed; promoting the accumulator and finishing through the existing container path is the caller's job,
 * which keeps the kernel free of any dependency on the roaring module and lets the benchmark measure the
 * promotion with the real containers.
 *
 * Nothing here depends on evitaDB, which keeps the class compilable and verifiable on its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class UnionKernels {

	private UnionKernels() {
	}

	/**
	 * Merges inputs into `first` (ping-ponging with `second`) while the accumulated cardinality plus the next
	 * input's cardinality stays within `threshold`.
	 *
	 * The two buffers alternate because a two-way merge cannot write into one of its own inputs. Both are
	 * supplied by the caller so the allocation is counted once per union rather than once per input, which is
	 * what an implementation inside the container would do.
	 *
	 * @param inputs    the sorted inputs, each ascending and duplicate-free
	 * @param lengths   how many leading entries of each input to consider
	 * @param count     how many inputs to consider
	 * @param threshold the largest accumulated cardinality that stays on the array path
	 * @param first     scratch, at least `threshold + max(lengths)` entries
	 * @param second    scratch of the same size
	 * @return the accumulated length in the low 32 bits, the index of the first unconsumed input in the high
	 *         32 bits, and bit 62 set when the accumulator currently lives in `second` rather than `first`
	 */
	public static long foldWhileSmall(
		@Nonnull final char[][] inputs, @Nonnull final int[] lengths, final int count, final int threshold,
		@Nonnull final char[] first, @Nonnull final char[] second) {
		char[] accumulator = first;
		char[] spare = second;
		int accumulated = 0;
		int index = 0;
		boolean inSecond = false;
		while (index < count) {
			final int incoming = lengths[index];
			if (accumulated + incoming > threshold) {
				break;
			}
			final int merged = union(accumulator, accumulated, inputs[index], incoming, spare);
			final char[] swap = accumulator;
			accumulator = spare;
			spare = swap;
			accumulated = merged;
			inSecond = !inSecond;
			index++;
		}
		return ((long) index << 32) | (inSecond ? 1L << 62 : 0L) | (accumulated & 0xFFFFFFFFL);
	}

	/**
	 * Two-way merge of two ascending, duplicate-free inputs into `out`, keeping shared values once.
	 *
	 * @param a    first input
	 * @param la   number of leading entries of `a`
	 * @param b    second input
	 * @param lb   number of leading entries of `b`
	 * @param out  destination, at least `la + lb` entries
	 * @return number of values written
	 */
	public static int union(
		@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb, @Nonnull final char[] out) {
		if (la == 0) {
			System.arraycopy(b, 0, out, 0, lb);
			return lb;
		}
		if (lb == 0) {
			System.arraycopy(a, 0, out, 0, la);
			return la;
		}
		int ia = 0;
		int ib = 0;
		int pos = 0;
		while (true) {
			final int va = a[ia];
			final int vb = b[ib];
			if (va < vb) {
				out[pos++] = a[ia++];
				if (ia == la) {
					System.arraycopy(b, ib, out, pos, lb - ib);
					return pos + lb - ib;
				}
			} else if (va > vb) {
				out[pos++] = b[ib++];
				if (ib == lb) {
					System.arraycopy(a, ia, out, pos, la - ia);
					return pos + la - ia;
				}
			} else {
				out[pos++] = a[ia++];
				ib++;
				if (ia == la) {
					System.arraycopy(b, ib, out, pos, lb - ib);
					return pos + lb - ib;
				}
				if (ib == lb) {
					System.arraycopy(a, ia, out, pos, la - ia);
					return pos + la - ia;
				}
			}
		}
	}

	/**
	 * Extracts the index of the first unconsumed input from a {@link #foldWhileSmall} result.
	 *
	 * @param state the packed result
	 * @return the index
	 */
	public static int stoppedAt(final long state) {
		return (int) (state >>> 32) & 0x3FFFFFFF;
	}

	/**
	 * Extracts the accumulated length from a {@link #foldWhileSmall} result.
	 *
	 * @param state the packed result
	 * @return the length
	 */
	public static int accumulated(final long state) {
		return (int) state;
	}

	/**
	 * Says which of the two scratch buffers holds the accumulator.
	 *
	 * @param state the packed result
	 * @return `true` when it is the second one
	 */
	public static boolean inSecond(final long state) {
		return (state & (1L << 62)) != 0;
	}

}
