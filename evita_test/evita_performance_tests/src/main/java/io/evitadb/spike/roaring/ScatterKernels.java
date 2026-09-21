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
 * Scatter kernels - setting the bits of a sorted `char[]` in a bitmap container's word array.
 *
 * This is `BitmapContainer.lazyIOR(ArrayContainer)`, and the census says it is not a corner: 92.7 % of every
 * container operation the engine performs during the query mix is exactly this shape, with a median of four
 * values scattered into an already-populated bitmap.
 *
 * Today's form touches the word array once per value. Because the input is sorted, the values that share a word
 * arrive consecutively, so the alternative accumulates them in a register and performs one read-modify-write per
 * **distinct word** instead - the shape `Util.intersectArrayIntoBitmap` already uses. At a median of four values
 * the two are nearly the same; the question is what the tail costs, where an array carries hundreds of values.
 *
 * Nothing here depends on evitaDB, which keeps the class compilable and verifiable on its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ScatterKernels {

	private ScatterKernels() {
	}

	/**
	 * Today's scatter - one read-modify-write per value.
	 *
	 * @param words  the destination bitmap
	 * @param values the values to set, ascending
	 * @param count  number of leading entries of `values` to set
	 */
	public static void scatterPerValue(
		@Nonnull final long[] words, @Nonnull final char[] values, final int count) {
		for (int i = 0; i < count; i++) {
			final int value = values[i];
			words[value >>> 6] |= 1L << value;
		}
	}

	/**
	 * Word-batched scatter - the run of values sharing a word is accumulated in a register, so the word array is
	 * written once per distinct word.
	 *
	 * @param words  the destination bitmap
	 * @param values the values to set, ascending
	 * @param count  number of leading entries of `values` to set
	 */
	public static void scatterPerWord(
		@Nonnull final long[] words, @Nonnull final char[] values, final int count) {
		int i = 0;
		while (i < count) {
			final int first = values[i];
			final int word = first >>> 6;
			long bits = 1L << first;
			int next = i + 1;
			while (next < count && values[next] >>> 6 == word) {
				bits |= 1L << values[next];
				next++;
			}
			words[word] |= bits;
			i = next;
		}
	}

}
