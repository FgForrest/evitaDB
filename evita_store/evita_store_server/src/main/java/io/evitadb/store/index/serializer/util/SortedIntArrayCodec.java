/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.index.serializer.util;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Delta-varint codec for **non-decreasing** (ascending) `int[]` arrays. Many index storage parts persist primitive
 * arrays that are globally sorted in ascending order (e.g. facet referencing entity ids, hierarchy children / roots /
 * orphans, price internal ids). Storing such an array as raw fixed 4-byte ints wastes space; storing the gaps between
 * neighbouring elements as unsigned varints typically collapses each element to one or two bytes because the gaps are
 * small and always non-negative.
 *
 * Wire layout:
 *
 * - `count` — number of elements, unsigned varint.
 * - if `count > 0`: `first` — the first element written as a zig-zag varint (so a negative smallest id is still
 *   compact), followed by `count - 1` gaps, each written as `curr - prev` via an unsigned varint (the gaps of an
 *   ascending array are always `>= 0`).
 *
 * The decoder is the exact inverse and always returns a non-null array (`new int[0]` for an empty input), because
 * callers (notably the hierarchy storage part) treat these arrays as never-null.
 *
 * This codec deliberately does not subtract one from the gaps: keeping the gap equal to the literal difference makes
 * the encoding obviously correct and tolerant of duplicate neighbouring values (gap `0`), at the cost of one extra
 * byte only when an array contains a duplicate — which the asserting write path forbids only for *decreasing* input,
 * not for equal neighbours.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SortedIntArrayCodec {

	private SortedIntArrayCodec() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated!");
	}

	/**
	 * Writes a non-decreasing `int[]` as a count, the zig-zag first element and unsigned gap varints. The input must be
	 * sorted in ascending order (equal neighbours are allowed); a debug premise check fails loud if it is not, so a
	 * future caller that hands in unsorted data is caught immediately instead of silently corrupting the stream.
	 *
	 * @param output    the Kryo output to write to
	 * @param ascending the non-decreasing array to encode (must not be null)
	 */
	public static void writeAscendingInts(@Nonnull Output output, @Nonnull int[] ascending) {
		final int count = ascending.length;
		output.writeVarInt(count, true);
		if (count > 0) {
			// the first element may be negative, so use zig-zag to keep small magnitudes compact regardless of sign
			output.writeVarInt(ascending[0], false);
			int previous = ascending[0];
			for (int i = 1; i < count; i++) {
				final int current = ascending[i];
				// fail loud on a decreasing input instead of writing a negative gap that the reader would misinterpret
				Assert.isPremiseValid(
					current >= previous,
					"SortedIntArrayCodec requires a non-decreasing array, but element at index " + i +
						" (" + current + ") is smaller than its predecessor (" + previous + ")!"
				);
				// the gap of an ascending array is always >= 0, so a plain (unsigned) varint is optimal here
				output.writeVarInt(current - previous, true);
				previous = current;
			}
		}
	}

	/**
	 * Reads an array previously written by {@link #writeAscendingInts(Output, int[])}. Returns a non-null array; an
	 * empty input decodes to `new int[0]` (never null) because callers treat these arrays as never-null.
	 *
	 * @param input the Kryo input to read from
	 * @return the decoded ascending array (never null)
	 */
	@Nonnull
	public static int[] readAscendingInts(@Nonnull Input input) {
		final int count = input.readVarInt(true);
		if (count == 0) {
			return new int[0];
		}
		final int[] result = new int[count];
		// the first element was zig-zag encoded
		int previous = input.readVarInt(false);
		result[0] = previous;
		for (int i = 1; i < count; i++) {
			// each subsequent element is the running sum of the unsigned gaps
			previous += input.readVarInt(true);
			result[i] = previous;
		}
		return result;
	}

}
