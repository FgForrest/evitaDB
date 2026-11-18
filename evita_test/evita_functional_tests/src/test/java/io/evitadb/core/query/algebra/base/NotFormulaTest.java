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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotFormula} verifying boolean negation (NOT) computation, memoization,
 * cloning, hashing, identity and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("NotFormula — boolean negation")
@Tag(ENGINE)
@Tag(QUERY)
class NotFormulaTest {

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should subtract elements from superset across various cases")
		void shouldApplyBooleanNot() {
			assertNegatedArray(new int[]{1, 3}, new int[0], new int[]{1, 3});
			assertNegatedArray(new int[]{6}, new int[]{2, 3, 4}, new int[]{3, 6});
			assertNegatedArray(new int[]{3, 4}, new int[]{1, 2}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{1, 2}, new int[]{3, 4}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{1, 4}, new int[]{2, 3}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{1, 3}, new int[]{2, 4}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[0], new int[]{1, 2, 3, 4}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[0], new int[]{1, 2, 3, 4, 5, 6}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{1, 3, 4}, new int[]{2, 7, 9}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{1, 2, 3, 4}, new int[]{7, 9}, new int[]{1, 2, 3, 4});
			assertNegatedArray(new int[]{3}, new int[]{1, 2, 4, 5, 6, 7}, new int[]{1, 2, 3, 4, 5, 6, 7});
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new NotFormula(
					new BaseBitmap(),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty when superset is empty")
		void shouldReturnNothingForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[0],
				new NotFormula(
					new BaseBitmap(1, 3, 4, 5, 8),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return all when subtracted is empty")
		void shouldReturnAllForFullAndEmptyBitmap() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 8},
				new NotFormula(
					new BaseBitmap(),
					new BaseBitmap(1, 3, 4, 5, 8)
				)
					.compute().getArray()
			);
		}
	}

	@Nested
	@DisplayName("Memoization")
	class MemoizationTest {

		@Test
		@DisplayName("should return same instance on repeated compute calls")
		void shouldReturnSameInstanceOnRepeatedCompute() {
			final NotFormula formula = new NotFormula(
				new BaseBitmap(2, 4),
				new BaseBitmap(1, 2, 3, 4, 5)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated compute calls with formula children")
		void shouldReturnSameInstanceOnRepeatedComputeWithFormulas() {
			final NotFormula formula = new NotFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 4))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("Clear memory")
	class ClearMemoryTest {

		@Test
		@DisplayName("should recompute equal result after clearMemory")
		void shouldRecomputeEqualResultAfterClearMemory() {
			final NotFormula formula = new NotFormula(
				new BaseBitmap(2, 4),
				new BaseBitmap(1, 2, 3, 4, 5)
			);

			final Bitmap first = formula.compute();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			assertNotSame(first, second);
			assertArrayEquals(first.getArray(), second.getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new NotFormula preserving computation when cloned")
		void shouldReturnNewNotFormulaWhenCloned() {
			final ConstantFormula subtracted = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 4)));
			final ConstantFormula superset = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)));
			final NotFormula original = new NotFormula(subtracted, superset);

			final Formula clone = original.getCloneWithInnerFormulas(subtracted, superset);

			assertInstanceOf(NotFormula.class, clone);
			assertArrayEquals(original.compute().getArray(), clone.compute().getArray());
		}
	}

	/** Verifies detached identity equality and operand sensitivity. */
	@Nested
	@DisplayName("Key determinism and sensitivity")
	class KeyTest {

		/** Equal computation inputs produce equal detached keys. */
		@Test
		@DisplayName("should produce identical keys for identically-constructed formulas")
		void shouldProduceIdenticalKeyForIdenticalFormulas() {
			final FormulaKey keyA = createNotFormula(new int[]{2, 4}, new int[]{1, 2, 3, 4, 5}).getKey();
			final FormulaKey keyB = createNotFormula(new int[]{2, 4}, new int[]{1, 2, 3, 4, 5}).getKey();

			assertEquals(keyA, keyB);
		}

		/** Different computation inputs produce unequal detached keys. */
		@Test
		@DisplayName("should produce different keys for different children")
		void shouldProduceDifferentKeyForDifferentChildren() {
			final FormulaKey keyA = createNotFormula(new int[]{2, 4}, new int[]{1, 2, 3, 4, 5}).getKey();
			final FormulaKey keyB = createNotFormula(new int[]{1, 3}, new int[]{1, 2, 3, 4, 5}).getKey();

			assertNotEquals(keyA, keyB);
		}

		/** Different computation inputs produce unequal detached keys. */
		@Test
		@DisplayName("should produce different keys when subtracted and superset are swapped (order-significant)")
		void shouldProduceDifferentKeyWhenArgumentsSwapped() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3, 4, 5)));

			// NOT(A, B) vs NOT(B, A) — order matters
			final FormulaKey keyAB = new NotFormula(childA, childB).getKey();
			final FormulaKey keyBA = new NotFormula(childB, childA).getKey();

			assertNotEquals(keyAB, keyBA);
		}

		/** Verifies both raw positions contribute independently to equality and the structural prefilter. */
		@Test
		@DisplayName("should include each ordered raw operand in the key and its structural hash")
		void shouldIncludeEachRawOperand() {
			final Bitmap subtracted = new BaseBitmap(1);
			final Bitmap superset = new BaseBitmap(2);

			final FormulaKey key = new NotFormula(subtracted, superset).getKey();
			final FormulaKey differentSubtracted = new NotFormula(new BaseBitmap(3), superset).getKey();
			final FormulaKey differentSuperset = new NotFormula(subtracted, new BaseBitmap(3)).getKey();
			final FormulaKey swapped = new NotFormula(superset, subtracted).getKey();

			assertNotEquals(key, differentSubtracted);
			assertNotEquals(key, differentSuperset);
			assertNotEquals(key, swapped);
			assertNotEquals(key.getHash64(), differentSubtracted.getHash64());
			assertNotEquals(key.getHash64(), differentSuperset.getHash64());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return superset cardinality for bitmap-based construction")
		void shouldReturnSupersetCardinalityForBitmaps() {
			final NotFormula formula = new NotFormula(
				new BaseBitmap(2, 4),
				new BaseBitmap(1, 2, 3, 4, 5)
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return superset cardinality for formula-based construction")
		void shouldReturnSupersetCardinalityForFormulas() {
			final NotFormula formula = new NotFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 4))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("should have non-negative estimated and actual cost with estimated being an upper bound")
		void shouldHaveNonNegativeCostsWithEstimatedAsUpperBound() {
			final NotFormula formula = new NotFormula(
				new BaseBitmap(2, 4),
				new BaseBitmap(1, 2, 3, 4, 5)
			);

			final long estimatedCost = formula.getEstimatedCost();
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(estimatedCost >= 0, "Estimated cost should be non-negative, was: " + estimatedCost);
			assertTrue(actualCost >= 0, "Actual cost should be non-negative, was: " + actualCost);
			assertTrue(
				estimatedCost >= actualCost,
				"Estimated cost " + estimatedCost + " should be >= actual cost " + actualCost
			);
		}
	}

	/**
	 * Asserts that NOT(negatedArray, mainArray) produces the expected result, testing both
	 * bitmap-based and formula-based constructors.
	 */
	private void assertNegatedArray(@Nonnull int[] expectedResult, @Nonnull int[] negatedArray, @Nonnull int[] mainArray) {
		assertArrayEquals(
			expectedResult,
			new NotFormula(
				new BaseBitmap(negatedArray),
				new BaseBitmap(mainArray)
			)
				.compute().getArray()
		);

		assertArrayEquals(
			expectedResult,
			new NotFormula(
				negatedArray.length == 0 ?
					EmptyFormula.INSTANCE :
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(negatedArray))),
				mainArray.length == 0 ?
					EmptyFormula.INSTANCE :
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(mainArray)))
			)
				.compute().getArray()
		);
	}

	/**
	 * Creates a {@link NotFormula} from formula-based children for hash and identity testing.
	 */
	@Nonnull
	private static NotFormula createNotFormula(@Nonnull int[] subtracted, @Nonnull int[] superset) {
		return new NotFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(subtracted))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(superset)))
		);
	}
}
