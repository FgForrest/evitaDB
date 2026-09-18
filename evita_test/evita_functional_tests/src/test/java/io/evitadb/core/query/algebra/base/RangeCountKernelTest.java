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

package io.evitadb.core.query.algebra.base;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential test for BOTH implementations of the signed-multiplicity computation -
 * {@link RangeCountKernel} (per-chunk scatter) and {@link RangeBitSlicedKernel} (binary planes of roaring
 * bitmaps). Every case is checked against an independent map-based reference that shares no code with either
 * kernel.
 *
 * Holding two independent implementations to the same oracle is deliberate: they fail differently, so a case that
 * both pass is far stronger evidence than a case one passes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(QUERY)
@DisplayName("Both range counting kernels equal the Join/Disentangle pair they replace")
class RangeCountKernelTest {

	/**
	 * Independent reference: counts memberships in a `TreeMap` and keeps the strictly-positive ones.
	 *
	 * This deliberately shares no code with either kernel and no code with the formulas that used to compute this.
	 * It is a direct transcription of the specification - `{ v : count(plus, v) - count(minus, v) > 0 }` - so a
	 * bug would have to appear identically in a map-based counter, a chunked scatter and a bit-sliced plane
	 * network to go unnoticed.
	 *
	 * The `JoinFormula` -> `DisentangleFormula` pair was the oracle while it still existed, and both kernels were
	 * confirmed byte-identical to it across this whole suite before it was removed; this reference reproduces that
	 * contract without depending on deleted code.
	 *
	 * @param plus  bitmaps contributing `+1`
	 * @param minus bitmaps contributing `-1`
	 * @return record ids whose signed count is strictly positive, ascending
	 */
	@Nonnull
	private static int[] reference(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final TreeMap<Integer, Integer> counts = new TreeMap<>();
		for (final Bitmap bitmap : plus) {
			for (final int value : bitmap.getArray()) {
				counts.merge(value, 1, Integer::sum);
			}
		}
		for (final Bitmap bitmap : minus) {
			for (final int value : bitmap.getArray()) {
				counts.merge(value, -1, Integer::sum);
			}
		}
		return counts.entrySet().stream()
			.filter(it -> it.getValue() > 0)
			.mapToInt(Map.Entry::getKey)
			.toArray();
	}

	/**
	 * Asserts the kernel and the reference agree, reporting the operands when they do not.
	 *
	 * @param plus  bitmaps contributing `+1`
	 * @param minus bitmaps contributing `-1`
	 */
	private static void assertAgrees(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final int[] expected = reference(plus, minus);
		final int[] scatter = RangeCountKernel.compute(plus, minus).getArray();
		final int[] sliced = RangeBitSlicedKernel.compute(plus, minus).getArray();
		assertArrayEquals(
			expected, scatter,
			() -> "SCATTER kernel disagrees with the Join/Disentangle pair\n  plus  = " + describe(plus) +
				"\n  minus = " + describe(minus) +
				"\n  expected = " + Arrays.toString(expected) + "\n  actual   = " + Arrays.toString(scatter)
		);
		assertArrayEquals(
			expected, sliced,
			() -> "BIT-SLICED kernel disagrees with the Join/Disentangle pair\n  plus  = " + describe(plus) +
				"\n  minus = " + describe(minus) +
				"\n  expected = " + Arrays.toString(expected) + "\n  actual   = " + Arrays.toString(sliced)
		);
	}

	/**
	 * Renders a family compactly for a failure message.
	 *
	 * @param family the operands to render
	 * @return a readable rendering
	 */
	@Nonnull
	private static String describe(@Nonnull Bitmap[] family) {
		final StringBuilder sb = new StringBuilder(128);
		sb.append('[');
		for (int i = 0; i < family.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(Arrays.toString(family[i].getArray()));
		}
		return sb.append(']').toString();
	}

	/**
	 * Builds a bitmap from the given record ids.
	 *
	 * @param values the record ids
	 * @return a bitmap holding them, or the empty singleton
	 */
	@Nonnull
	private static Bitmap bitmap(int... values) {
		return values.length == 0 ? EmptyBitmap.INSTANCE : new BaseBitmap(values);
	}

	@Nested
	@DisplayName("Hand-written cases")
	class Explicit {

		@Test
		@DisplayName("The javadoc example of the formula being replaced")
		void shouldReproduceTheDisentangleJavadocExample() {
			// [3,3,6,9,12] against [2,3,4,6,8,10,12] -> [3,9]; as families that is {3,6,9,12} and {3} on the plus
			// side (3 appears twice) against {2,4,6,8,10,12} and {3} on the minus side
			assertAgrees(
				new Bitmap[]{bitmap(3, 6, 9, 12), bitmap(3)},
				new Bitmap[]{bitmap(2, 3, 4, 6, 8, 10, 12), bitmap(3)}
			);
		}

		@Test
		@DisplayName("An empty minus family degenerates to the union of the plus family")
		void shouldDegenerateToUnionWhenMinusIsEmpty() {
			assertAgrees(new Bitmap[]{bitmap(1, 5, 9), bitmap(5, 7)}, new Bitmap[0]);
		}

		@Test
		@DisplayName("An empty plus family yields nothing whatever the minus family holds")
		void shouldYieldNothingWhenPlusIsEmpty() {
			assertAgrees(new Bitmap[0], new Bitmap[]{bitmap(1, 2, 3)});
		}

		@Test
		@DisplayName("Equal multiplicity cancels, strictly greater survives")
		void shouldKeepOnlyStrictlyPositiveCounts() {
			// 7 appears twice on each side (cancels); 8 appears twice on the plus side and once on the minus (survives)
			assertAgrees(
				new Bitmap[]{bitmap(7, 8), bitmap(7, 8)},
				new Bitmap[]{bitmap(7, 8), bitmap(7)}
			);
		}

		@Test
		@DisplayName("Operands spanning several 65536-value chunks")
		void shouldSpanMultipleChunks() {
			assertAgrees(
				new Bitmap[]{bitmap(1, 70_000, 140_000), bitmap(70_000, 200_001)},
				new Bitmap[]{bitmap(1, 140_000)}
			);
		}

		@Test
		@DisplayName("Identical families cancel to nothing without needing a special guard")
		void shouldCancelWhenBothFamiliesAreIdentical() {
			// the pair being replaced needed an explicit `disentangle(X, X) = empty` guard because FormulaCloner /
			// FormulaDeduplicator could unify its two positional siblings. RangeCountFormula holds bitmaps rather
			// than child formulas, so there is nothing to unify - but the ARITHMETIC must still land on empty on
			// its own, which is what this asserts.
			final Bitmap[] family = {bitmap(1, 2, 3), bitmap(2, 5), bitmap(9)};
			assertAgrees(family, family);
		}

		@Test
		@DisplayName("A record present in every operand of both families")
		void shouldHandleTotalOverlap() {
			assertAgrees(
				new Bitmap[]{bitmap(5), bitmap(5), bitmap(5)},
				new Bitmap[]{bitmap(5), bitmap(5), bitmap(5)}
			);
		}
	}

	@Nested
	@DisplayName("Randomised families")
	@Tag(CACHE)
	class Randomised {

		@Test
		@DisplayName("Dense small-id families agree over many seeds")
		void shouldAgreeOnDenseSmallIdFamilies() {
			runSweep(1_000, 12, 512, 4);
		}

		@Test
		@DisplayName("Sparse wide-id families spanning many chunks agree over many seeds")
		void shouldAgreeOnSparseWideIdFamilies() {
			runSweep(400, 8, 64, 5_000_000);
		}

		@Test
		@DisplayName("Many operands - the high-cardinality shape a real range query builds")
		void shouldAgreeOnHighCardinalityFamilies() {
			runSweep(60, 250, 40, 20_000);
		}

		/**
		 * Compares kernel and reference over randomised families.
		 *
		 * @param iterations   how many families to try
		 * @param maxOperands  upper bound on operands per family
		 * @param maxPerBitmap upper bound on record ids per operand
		 * @param idSpace      exclusive upper bound on a record id
		 */
		private void runSweep(int iterations, int maxOperands, int maxPerBitmap, int idSpace) {
			for (int iteration = 0; iteration < iterations; iteration++) {
				final Random random = new Random(iteration * 7919L + 13L);
				final Bitmap[] plus = randomFamily(random, maxOperands, maxPerBitmap, idSpace);
				final Bitmap[] minus = randomFamily(random, maxOperands, maxPerBitmap, idSpace);
				assertAgrees(plus, minus);
			}
		}

		/**
		 * Builds one randomised family of operands.
		 *
		 * @param random       source of randomness
		 * @param maxOperands  upper bound on operands
		 * @param maxPerBitmap upper bound on record ids per operand
		 * @param idSpace      exclusive upper bound on a record id
		 * @return the family, possibly containing empty bitmaps
		 */
		@Nonnull
		private Bitmap[] randomFamily(@Nonnull Random random, int maxOperands, int maxPerBitmap, int idSpace) {
			final int operandCount = random.nextInt(maxOperands + 1);
			final Bitmap[] family = new Bitmap[operandCount];
			for (int i = 0; i < operandCount; i++) {
				final int size = random.nextInt(maxPerBitmap + 1);
				final TreeSet<Integer> values = new TreeSet<>();
				for (int j = 0; j < size; j++) {
					values.add(random.nextInt(idSpace) + 1);
				}
				final int[] array = new int[values.size()];
				int index = 0;
				for (final Integer value : values) {
					array[index++] = value;
				}
				family[i] = bitmap(array);
			}
			return family;
		}
	}

	@Nested
	@DisplayName("Counterfactual - the test must be able to fail")
	class Counterfactual {

		@Test
		@DisplayName("The reference itself distinguishes a strictly-positive count from a cancelled one")
		void shouldProveTheOracleIsSensitive() {
			// if this ever stops holding, every assertAgrees above is comparing two constants and proves nothing
			assertArrayEquals(
				new int[]{8},
				reference(new Bitmap[]{bitmap(7, 8), bitmap(7, 8)}, new Bitmap[]{bitmap(7, 8), bitmap(7)})
			);
			assertEquals(
				0,
				reference(new Bitmap[]{bitmap(7), bitmap(7)}, new Bitmap[]{bitmap(7), bitmap(7)}).length
			);
		}
	}
}
