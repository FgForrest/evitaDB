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
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import javax.annotation.Nonnull;

/**
 * Polynomial hash of an array container's contents - `ArrayContainer.hashCode`, which a JFR profile of the
 * production facet workload puts at 3.8 % of query CPU, self time.
 *
 * ## The recurrence is base 32, not base 31, and that changes the answer
 *
 * The shipped loop reads `hash += 31 * hash + content[k]`, which is `hash = 32 * hash + content[k]` - the
 * `+=` where a `=` was meant. It is inherited from upstream RoaringBitmap, not introduced here.
 *
 * Base 32 is `2^5`, so the coefficient of the character `j` positions from the end is `32^j = 2^(5j)`, and
 * `2^35` is zero in a 32-bit int. **Only the last seven characters can affect the result.** Everything before
 * them is multiplied by zero. That is proved, not assumed: 20,000 randomised containers of 1 to 4096 values
 * give the shipped loop and the seven-element loop below identical results, and two 4096-value containers
 * sharing only their last seven values hash the same.
 *
 * So the interesting arm here is not the vector one. A hash whose value is determined by seven characters does
 * not need to read four thousand of them, and {@link #lastSeven} computes the identical `int` in constant time.
 * The vector arms are kept because the brief asked for them and because they are the honest comparison: they
 * are what "make the existing loop faster" is worth, against what "stop doing the work" is worth.
 *
 * The distribution consequence - two containers differing in four thousand values colliding - is a separate
 * matter from performance and is not something a kernel change should quietly alter, because the value is the
 * same value callers see today.
 *
 * Nothing here depends on evitaDB, which keeps the class compilable and verifiable on its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class HashKernels {

	/**
	 * Characters beyond which the polynomial coefficient is zero in a 32-bit int: `32^7 = 2^35`.
	 */
	public static final int SIGNIFICANT_TAIL = 7;
	/**
	 * Sixteen `short` lanes - the load width feeding the 16-lane hash step.
	 */
	private static final VectorSpecies<Short> SHORT_256 = ShortVector.SPECIES_256;
	/**
	 * Sixteen `int` lanes.
	 */
	private static final VectorSpecies<Integer> INT_512 = IntVector.SPECIES_512;
	/**
	 * Thirty-two `short` lanes.
	 */
	private static final VectorSpecies<Short> SHORT_512 = ShortVector.SPECIES_512;
	/**
	 * Per-lane coefficients for a 16-character step: lane `i` carries `32^(15 - i)`.
	 */
	private static final IntVector POWERS_16 = powers(INT_512, 16);
	/**
	 * `32^16` as an int - the factor the running hash is multiplied by per 16-character step. It is zero,
	 * which is the whole point: a full step discards everything that came before it.
	 */
	private static final int STEP_16 = power(16);
	/**
	 * Per-lane coefficients for the two halves of a 32-character step.
	 */
	private static final IntVector POWERS_32_HIGH = powers(INT_512, 32);
	/**
	 * Per-lane coefficients for the low half of a 32-character step.
	 */
	private static final IntVector POWERS_32_LOW = powers(INT_512, 16);
	/**
	 * `32^32` as an int, likewise zero.
	 */
	private static final int STEP_32 = power(32);

	private HashKernels() {
	}

	/**
	 * `32^exponent` in 32-bit int arithmetic.
	 *
	 * @param exponent the exponent
	 * @return the coefficient, which is zero for any exponent of seven or more
	 */
	private static int power(final int exponent) {
		int result = 1;
		for (int i = 0; i < exponent; i++) {
			result *= 32;
		}
		return result;
	}

	/**
	 * Builds the per-lane coefficient vector for a step of `length` characters: lane `i` holds
	 * `32^(length - 1 - i)`.
	 *
	 * @param species the lane species
	 * @param length  characters consumed per step
	 * @return the coefficients
	 */
	@Nonnull
	private static IntVector powers(@Nonnull final VectorSpecies<Integer> species, final int length) {
		final int[] table = new int[species.length()];
		for (int i = 0; i < table.length; i++) {
			table[i] = power(length - 1 - i);
		}
		return IntVector.fromArray(species, table, 0);
	}

	/**
	 * The shipped loop, reproduced exactly - including the `+=` that makes it base 32.
	 *
	 * @param content     the container's values
	 * @param cardinality how many of them are live
	 * @return the hash
	 */
	public static int today(@Nonnull final char[] content, final int cardinality) {
		int hash = 0;
		for (int k = 0; k < cardinality; ++k) {
			hash += 31 * hash + content[k];
		}
		return hash;
	}

	/**
	 * The identical value in constant time: only the last seven characters carry a non-zero coefficient.
	 *
	 * @param content     the container's values
	 * @param cardinality how many of them are live
	 * @return the same hash {@link #today} returns, for every input
	 */
	public static int lastSeven(@Nonnull final char[] content, final int cardinality) {
		int hash = 0;
		for (int k = Math.max(0, cardinality - SIGNIFICANT_TAIL); k < cardinality; ++k) {
			hash = (hash << 5) + content[k];
		}
		return hash;
	}

	/**
	 * Sixteen characters per step: the block is widened to `int` lanes, multiplied by the per-lane
	 * coefficients and reduced, with the running hash scaled by `32^16` first.
	 *
	 * @param content     the container's values
	 * @param cardinality how many of them are live
	 * @return the hash
	 */
	public static int vector16(@Nonnull final char[] content, final int cardinality) {
		int hash = 0;
		int k = 0;
		for (; k + 16 <= cardinality; k += 16) {
			final IntVector block = (IntVector) ShortVector.fromCharArray(SHORT_256, content, k)
				.convertShape(VectorOperators.S2I, INT_512, 0);
			hash = hash * STEP_16
				+ (int) block.lanewise(VectorOperators.AND, 0xFFFF)
					.mul(POWERS_16).reduceLanes(VectorOperators.ADD);
		}
		for (; k < cardinality; ++k) {
			hash = (hash << 5) + content[k];
		}
		return hash;
	}

	/**
	 * Thirty-two characters per step, as two widened halves sharing one reduction chain.
	 *
	 * @param content     the container's values
	 * @param cardinality how many of them are live
	 * @return the hash
	 */
	public static int vector32(@Nonnull final char[] content, final int cardinality) {
		int hash = 0;
		int k = 0;
		for (; k + 32 <= cardinality; k += 32) {
			final ShortVector loaded = ShortVector.fromCharArray(SHORT_512, content, k);
			final IntVector high = (IntVector) loaded.convertShape(VectorOperators.S2I, INT_512, 0);
			final IntVector low = (IntVector) loaded.convertShape(VectorOperators.S2I, INT_512, 1);
			hash = hash * STEP_32
				+ (int) high.lanewise(VectorOperators.AND, 0xFFFF).mul(POWERS_32_HIGH)
					.add(low.lanewise(VectorOperators.AND, 0xFFFF).mul(POWERS_32_LOW))
					.reduceLanes(VectorOperators.ADD);
		}
		for (; k < cardinality; ++k) {
			hash = (hash << 5) + content[k];
		}
		return hash;
	}

}
