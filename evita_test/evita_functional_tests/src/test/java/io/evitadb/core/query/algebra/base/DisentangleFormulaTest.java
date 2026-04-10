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

import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DisentangleFormula} verifying index-aware duplicate removal,
 * memoization, cloning, hashing and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DisentangleFormula — index-aware duplicate removal")
class DisentangleFormulaTest {

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should remove single matching element from main array")
		void shouldReturnAllNumbersExceptSingleInControlArray() {
			assertDistinctArray(
				new int[]{1, 2, 4},
				new int[]{1, 2, 3, 4},
				new int[]{3}
			);
		}

		@Test
		@DisplayName("should remove matching elements and collapse duplicates")
		void shouldReturnAllNumbersExceptThoseInControlArray() {
			assertDistinctArray(
				new int[]{1, 3, 4},
				new int[]{1, 2, 3, 3, 4},
				new int[]{2, 3}
			);
		}

		@Test
		@DisplayName("should not change main when control has only greater numbers")
		void shouldReturnMainListWithoutChangeWhenThereIsOnlyGreaterNumbersInControlList() {
			assertDistinctArray(
				new int[]{1, 2, 3, 4, 5},
				new int[]{1, 2, 3, 4, 5},
				new int[]{6, 7, 8}
			);
		}

		@Test
		@DisplayName("should not change main when control has only lesser numbers")
		void shouldReturnMainListWithoutChangeWhenThereIsOnlyLesserNumbersInControlList() {
			assertDistinctArray(
				new int[]{6, 7, 8},
				new int[]{6, 7, 8},
				new int[]{1, 2, 3, 4, 5}
			);
		}

		@Test
		@DisplayName("should return main unchanged when control is empty")
		void shouldReturnMainListWhenControlListIsEmpty() {
			assertDistinctArray(
				new int[]{6, 7, 8},
				new int[]{6, 7, 8},
				new int[0]
			);
		}

		@Test
		@DisplayName("should return empty when main is empty")
		void shouldReturnEmptyMainListWhenMainListIsEmpty() {
			assertDistinctArray(
				new int[0],
				new int[0],
				new int[]{6, 7, 8}
			);
		}

		@Test
		@DisplayName("should remove matching elements by index position")
		void shouldReturnMainListWithDistinctNumbersOnlyWhenThereIsMatchInControlList() {
			assertDistinctArray(
				new int[]{1, 7},
				new int[]{1, 4, 6, 7},
				new int[]{2, 4, 6, 8}
			);
		}

		@Test
		@DisplayName("should handle duplicates in both main and control arrays")
		void shouldReturnMainListWithDistinctNumbersAndDuplicatesOnlyWhenThereIsMatchInControlList() {
			assertDistinctArray(
				new int[]{1, 4, 7},
				new int[]{1, 4, 4, 6, 6, 7},
				new int[]{2, 4, 6, 6, 6, 8}
			);
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new DisentangleFormula(
					EmptyBitmap.INSTANCE,
					EmptyBitmap.INSTANCE
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty when main is empty and control is non-empty")
		void shouldReturnFullBitmapForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[0],
				new DisentangleFormula(
					EmptyBitmap.INSTANCE,
					new ArrayBitmap(new CompositeIntArray(3, 3, 6, 9, 12))
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return full main when control is empty")
		void shouldReturnEmptyBitmapForEmptyControlBitmapAndFullMainBitmap() {
			assertArrayEquals(
				new int[]{3, 6, 9, 12},
				new DisentangleFormula(
					new ArrayBitmap(new CompositeIntArray(3, 3, 6, 9, 12)),
					EmptyBitmap.INSTANCE
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
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4)),
				new ArrayBitmap(new CompositeIntArray(2, 3))
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated compute calls with formula children")
		void shouldReturnSameInstanceOnRepeatedComputeWithFormulas() {
			final DisentangleFormula formula = new DisentangleFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)))
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
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4)),
				new ArrayBitmap(new CompositeIntArray(2, 3))
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
		@DisplayName("should return new DisentangleFormula preserving computation when cloned")
		void shouldReturnNewFormulaWhenCloned() {
			final ConstantFormula main = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4)));
			final ConstantFormula control = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)));
			final DisentangleFormula original = new DisentangleFormula(main, control);

			final Formula clone = original.getCloneWithInnerFormulas(main, control);

			assertInstanceOf(DisentangleFormula.class, clone);
			assertArrayEquals(original.compute().getArray(), clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically-constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final long hashA = createDisentangleFormula(
				new int[]{1, 2, 3, 4}, new int[]{2, 3}
			).getHash();
			final long hashB = createDisentangleFormula(
				new int[]{1, 2, 3, 4}, new int[]{2, 3}
			).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different children")
		void shouldProduceDifferentHashForDifferentChildren() {
			final long hashA = createDisentangleFormula(
				new int[]{1, 2, 3, 4}, new int[]{2, 3}
			).getHash();
			final long hashB = createDisentangleFormula(
				new int[]{5, 6, 7, 8}, new int[]{6, 7}
			).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash when main and control are swapped (order-significant)")
		void shouldProduceDifferentHashWhenArgumentsSwapped() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3, 4, 5)));

			final long hashAB = new DisentangleFormula(childA, childB).getHash();
			final long hashBA = new DisentangleFormula(childB, childA).getHash();

			assertNotEquals(hashAB, hashBA);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return main bitmap cardinality for bitmap-based construction")
		void shouldReturnMainCardinalityForBitmaps() {
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)),
				new ArrayBitmap(new CompositeIntArray(2, 3))
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return main formula cardinality for formula-based construction")
		void shouldReturnMainCardinalityForFormulas() {
			final DisentangleFormula formula = new DisentangleFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)))
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
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)),
				new ArrayBitmap(new CompositeIntArray(2, 3, 4))
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

	@Nested
	@DisplayName("Cache behavior")
	class CacheBehaviorTest {

		@Test
		@DisplayName("should implement CacheableFormula")
		void shouldImplementCacheableFormula() {
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2)),
				new ArrayBitmap(new CompositeIntArray(2, 3))
			);

			assertInstanceOf(CacheableFormula.class, formula);
		}

		@Test
		@DisplayName("should implement CacheablePriceFormula")
		void shouldImplementCacheablePriceFormula() {
			final DisentangleFormula formula = new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2)),
				new ArrayBitmap(new CompositeIntArray(2, 3))
			);

			assertInstanceOf(CacheablePriceFormula.class, formula);
		}
	}

	/**
	 * Asserts that disentangling main against control produces the expected result, testing both
	 * bitmap-based and formula-based constructors.
	 */
	private void assertDistinctArray(@Nonnull int[] expectedResult, @Nonnull int[] mainArray, @Nonnull int[] controlArray) {
		assertArrayEquals(
			expectedResult,
			new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(mainArray)),
				new ArrayBitmap(new CompositeIntArray(controlArray))
			)
				.compute().getArray()
		);

		assertArrayEquals(
			expectedResult,
			new DisentangleFormula(
				mainArray.length == 0 ?
					EmptyFormula.INSTANCE :
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(mainArray))),
				controlArray.length == 0 ?
					EmptyFormula.INSTANCE :
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(controlArray)))
			)
				.compute().getArray()
		);
	}

	/**
	 * Creates a {@link DisentangleFormula} from formula-based children for hash testing.
	 */
	@Nonnull
	private static DisentangleFormula createDisentangleFormula(@Nonnull int[] main, @Nonnull int[] control) {
		return new DisentangleFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(main))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(control)))
		);
	}
}
