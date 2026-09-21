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
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * Array-against-bitmap probe kernels - issue #1544. The scalar arm is exactly
 * `BitmapContainer.andCardinality(ArrayContainer)`: one branch-free bit test per array value.
 *
 * The vector arm turns eight probes into one gather. Eight `char` values widen to eight `int` lanes, their word
 * indices go through an `int[8]` scratch (the Vector API's gather takes an index array, not an index vector), the
 * gather loads eight words in one `VPGATHERQQ`, and the per-lane variable shift plus `AND 1` reduces to a single
 * lane-wise accumulate.
 *
 * The index round-trip through memory is the part to watch: it is a store and a load per eight probes that the
 * scalar arm does not pay, and it is what the measurement is really deciding.
 *
 * Nothing here depends on evitaDB, which keeps the classes compilable and verifiable on their own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ProbeKernels {

	/**
	 * Eight `short` lanes - one gather's worth of probes.
	 */
	private static final VectorSpecies<Short> SHORT_128 = ShortVector.SPECIES_128;
	/**
	 * Eight `int` lanes, the widened values.
	 */
	private static final VectorSpecies<Integer> INT_256 = IntVector.SPECIES_256;
	/**
	 * Eight `long` lanes, the gathered words and the accumulator.
	 */
	private static final VectorSpecies<Long> LONG_512 = LongVector.SPECIES_512;
	/**
	 * Probes handled per vector step.
	 */
	public static final int PROBE_BLOCK = 8;

	private ProbeKernels() {
	}

	/**
	 * Today's probe: one branch-free bit test per value.
	 *
	 * @param words  the bitmap being probed
	 * @param values ascending unsigned 16-bit values to look up
	 * @param count  number of leading entries of `values` to consider
	 * @return how many of them are set in the bitmap
	 */
	public static int scalarProbeCardinality(
		@Nonnull final long[] words, @Nonnull final char[] values, final int count) {
		int answer = 0;
		for (int k = 0; k < count; k++) {
			final int value = values[k];
			answer += (int) ((words[value >>> 6] >>> value) & 1L);
		}
		return answer;
	}

	/**
	 * The gather probe: eight values per step, one gather, one variable shift, one lane-wise accumulate.
	 *
	 * @param words   the bitmap being probed
	 * @param values  ascending unsigned 16-bit values to look up
	 * @param count   number of leading entries of `values` to consider
	 * @param indices caller-owned `int[PROBE_BLOCK]` scratch carrying the word indices into the gather
	 * @return how many of them are set in the bitmap
	 */
	public static int gatherProbeCardinality(
		@Nonnull final long[] words, @Nonnull final char[] values, final int count, @Nonnull final int[] indices) {
		final int bound = count & -PROBE_BLOCK;
		LongVector accumulator = LongVector.zero(LONG_512);
		int k = 0;
		for (; k < bound; k += PROBE_BLOCK) {
			// S2I sign-extends, so the top bit of a char has to be masked back off before it becomes an index
			final IntVector widened = (IntVector) ShortVector.fromCharArray(SHORT_128, values, k)
				.convertShape(VectorOperators.S2I, INT_256, 0)
				.lanewise(VectorOperators.AND, 0xFFFF);
			widened.lanewise(VectorOperators.LSHR, 6).intoArray(indices, 0);
			final LongVector gathered = LongVector.fromArray(LONG_512, words, 0, indices, 0);
			final LongVector shifts = (LongVector) widened.lanewise(VectorOperators.AND, 63)
				.convertShape(VectorOperators.I2L, LONG_512, 0);
			accumulator = accumulator.add(gathered.lanewise(VectorOperators.LSHR, shifts).and(1L));
		}
		int answer = (int) accumulator.reduceLanes(VectorOperators.ADD);
		for (; k < count; k++) {
			final int value = values[k];
			answer += (int) ((words[value >>> 6] >>> value) & 1L);
		}
		return answer;
	}

}
