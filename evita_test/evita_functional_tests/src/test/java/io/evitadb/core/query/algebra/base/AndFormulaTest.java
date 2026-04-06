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
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AndFormula} verifying boolean conjunction (AND) computation, memoization,
 * cloning, hashing and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AndFormula — boolean conjunction")
class AndFormulaTest {

	private static final long[] INDEX_TRANSACTION_ID = {1L};

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should intersect three overlapping bitmaps")
		void shouldApplyBooleanAnd() {
			assertArrayEquals(
				new int[]{1, 4},
				new AndFormula(
					INDEX_TRANSACTION_ID,
					new ArrayBitmap(1, 3, 4, 5, 8),
					new ArrayBitmap(1, 2, 4, 8),
					new ArrayBitmap(1, 2, 3, 4, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should intersect three overlapping formulas")
		void shouldApplyBooleanAndWithFormula() {
			final AndFormula andFormula = new AndFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
			);
			assertArrayEquals(
				new int[]{1, 4},
				andFormula.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty when no elements are common")
		void shouldApplyBooleanAndOnNonOverlappingCollections() {
			assertArrayEquals(
				new int[0],
				new AndFormula(
					INDEX_TRANSACTION_ID,
					new ArrayBitmap(1, 3, 5, 7, 9, 11),
					new ArrayBitmap(3, 4, 5, 6, 7),
					new ArrayBitmap(10, 11, 1)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new AndFormula(
					INDEX_TRANSACTION_ID,
					new ArrayBitmap(),
					new ArrayBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty when one bitmap is empty")
		void shouldReturnNothingForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[0],
				new AndFormula(
					INDEX_TRANSACTION_ID,
					new ArrayBitmap(1, 3, 4, 5, 8),
					new ArrayBitmap(),
					new ArrayBitmap(1, 2, 3, 4, 5)
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
			final AndFormula formula = new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 2, 3),
				new ArrayBitmap(2, 3, 4)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated compute calls with formula children")
		void shouldReturnSameInstanceOnRepeatedComputeWithFormulas() {
			final AndFormula formula = new AndFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3, 4)))
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
			final AndFormula formula = new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 2, 3),
				new ArrayBitmap(2, 3, 4)
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
			final AndFormula original = new AndFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)))
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should return the single child directly when cloned with one child")
		void shouldReturnSingleChildWhenClonedWithOneChild() {
			final ConstantFormula child = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final AndFormula original = new AndFormula(
				child,
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)))
			);

			final Formula clone = original.getCloneWithInnerFormulas(child);

			assertSame(child, clone);
		}

		@Test
		@DisplayName("should return new AndFormula preserving computation when cloned with two+ children")
		void shouldReturnNewAndFormulaWhenClonedWithMultipleChildren() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3, 4)));
			final AndFormula original = new AndFormula(childA, childB);

			final Formula clone = original.getCloneWithInnerFormulas(childA, childB);

			assertInstanceOf(AndFormula.class, clone);
			assertArrayEquals(original.compute().getArray(), clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically-constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final long hashA = createAndFormula(1, 2, 3, 4).getHash();
			final long hashB = createAndFormula(1, 2, 3, 4).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different children")
		void shouldProduceDifferentHashForDifferentChildren() {
			final long hashA = createAndFormula(1, 2, 3).getHash();
			final long hashB = createAndFormula(4, 5, 6).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce same hash when children are reordered (order-insignificant)")
		void shouldProduceSameHashWhenChildrenReordered() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5, 6)));

			final long hashAB = new AndFormula(childA, childB).getHash();
			final long hashBA = new AndFormula(childB, childA).getHash();

			assertEquals(hashAB, hashBA);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return minimum cardinality across bitmaps")
		void shouldReturnMinCardinalityForBitmaps() {
			final AndFormula formula = new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 2, 3, 4, 5),
				new ArrayBitmap(2, 3),
				new ArrayBitmap(1, 2, 3, 4)
			);

			assertEquals(2, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return minimum cardinality across formula children")
		void shouldReturnMinCardinalityForFormulas() {
			final AndFormula formula = new AndFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2, 3)))
			);

			assertEquals(2, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("should have non-negative estimated and actual cost with estimated being an upper bound")
		void shouldHaveNonNegativeCostsWithEstimatedAsUpperBound() {
			final AndFormula formula = new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 2, 3, 4, 5),
				new ArrayBitmap(2, 3, 4)
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
			final AndFormula formula = new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 2),
				new ArrayBitmap(2, 3)
			);

			assertInstanceOf(CacheableFormula.class, formula);
		}
	}

	/**
	 * Creates an {@link AndFormula} from two bitmap-based children for hash testing: one bitmap
	 * holds the supplied values, the other holds a fixed reference set.
	 */
	@Nonnull
	private static AndFormula createAndFormula(int... values) {
		return new AndFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5, 6)))
		);
	}
}
