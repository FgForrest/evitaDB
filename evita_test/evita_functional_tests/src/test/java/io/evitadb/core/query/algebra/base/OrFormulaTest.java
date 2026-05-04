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
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link OrFormula} verifying boolean disjunction (OR) computation, memoization,
 * cloning, hashing and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("OrFormula — boolean disjunction")
@Tag(ENGINE)
@Tag(QUERY)
class OrFormulaTest {

	private static final long[] INDEX_TRANSACTION_ID = {1L};

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should union three overlapping bitmaps")
		void shouldApplyBooleanOr() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 8},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 4, 5, 8),
					new BaseBitmap(1, 2, 4, 8),
					new BaseBitmap(1, 2, 3, 4, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should union three overlapping formulas")
		void shouldApplyBooleanOrWithFormula() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 8},
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8))),
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should deduplicate across bitmaps with shared elements")
		void shouldConjugateCollectionsOfDuplicateInt() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 7, 8},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5),
					new BaseBitmap(3, 5, 8),
					new BaseBitmap(3, 4, 5, 7)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return union when one bitmap is empty")
		void shouldReturnJoinForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(3, 4),
					new BaseBitmap(),
					new BaseBitmap(1, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should union non-overlapping collections")
		void shouldConjugateNonOverlappingCollections() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 6, 7, 9, 10, 11, 12},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(3, 4, 5, 6, 7),
					new BaseBitmap(10, 11, 12)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should deduplicate completely overlapping collections")
		void shouldConjugateOverlappingCollections() {
			assertArrayEquals(
				new int[]{1, 3, 5, 7, 9, 11},
				new OrFormula(
					INDEX_TRANSACTION_ID,
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(1, 3, 5, 7, 9, 11),
					new BaseBitmap(1, 3, 5, 7, 9, 11)
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
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(3, 4, 5)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated compute calls with formula children")
		void shouldReturnSameInstanceOnRepeatedComputeWithFormulas() {
			final OrFormula formula = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4, 5)))
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
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(3, 4, 5)
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
		@DisplayName("should return EmptyFormula when cloned with zero children")
		void shouldReturnEmptyFormulaWhenClonedWithZeroChildren() {
			final OrFormula original = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4)))
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should return the single child directly when cloned with one child")
		void shouldReturnSingleChildWhenClonedWithOneChild() {
			final ConstantFormula child = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final OrFormula original = new OrFormula(
				child,
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5)))
			);

			final Formula clone = original.getCloneWithInnerFormulas(child);

			assertSame(child, clone);
		}

		@Test
		@DisplayName("should return new OrFormula preserving computation when cloned with two+ children")
		void shouldReturnNewOrFormulaWhenClonedWithMultipleChildren() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(3, 4, 5)));
			final OrFormula original = new OrFormula(childA, childB);

			final Formula clone = original.getCloneWithInnerFormulas(childA, childB);

			assertInstanceOf(OrFormula.class, clone);
			assertArrayEquals(original.compute().getArray(), clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically-constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final long hashA = createOrFormula(1, 2, 3).getHash();
			final long hashB = createOrFormula(1, 2, 3).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different children")
		void shouldProduceDifferentHashForDifferentChildren() {
			final long hashA = createOrFormula(1, 2, 3).getHash();
			final long hashB = createOrFormula(7, 8, 9).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce same hash when children are reordered (order-insignificant)")
		void shouldProduceSameHashWhenChildrenReordered() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5, 6)));

			final long hashAB = new OrFormula(childA, childB).getHash();
			final long hashBA = new OrFormula(childB, childA).getHash();

			assertEquals(hashAB, hashBA);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return sum of cardinalities across bitmaps")
		void shouldReturnSumCardinalityForBitmaps() {
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(4, 5)
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return sum of cardinalities across formula children")
		void shouldReturnSumCardinalityForFormulas() {
			final OrFormula formula = new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5)))
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
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(3, 4, 5, 6, 7)
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
			final OrFormula formula = new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 2),
				new BaseBitmap(3, 4)
			);

			assertInstanceOf(CacheableFormula.class, formula);
		}
	}

	/**
	 * Creates an {@link OrFormula} wrapping two constant formulas for hash testing.
	 */
	@Nonnull
	private static OrFormula createOrFormula(int... values) {
		return new OrFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(10, 20, 30)))
		);
	}
}
