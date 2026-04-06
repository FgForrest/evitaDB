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
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JoinFormula} verifying duplicate-preserving merge, memoization,
 * cloning restrictions, hashing and cost estimation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("JoinFormula — duplicate-preserving merge")
class JoinFormulaTest {

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should merge three bitmaps preserving duplicates in sorted order")
		void shouldApplyBitmapJoin() {
			assertArrayEquals(
				new int[]{1, 1, 2, 2, 3, 4, 4, 5, 5},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 3, 4, 5),
					new BaseBitmap(2, 4),
					new BaseBitmap(1, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should merge three ArrayBitmap inputs preserving duplicates")
		void shouldApplyBitmapJoinWithFormula() {
			assertArrayEquals(
				new int[]{1, 1, 2, 2, 3, 4, 4, 5, 5},
				new JoinFormula(
					1L,
					new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)),
					new ArrayBitmap(new CompositeIntArray(2, 4)),
					new ArrayBitmap(new CompositeIntArray(1, 5))
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return empty for two empty bitmaps")
		void shouldReturnNothingForEmptyBitmaps() {
			assertArrayEquals(
				new int[0],
				new JoinFormula(
					1L,
					new BaseBitmap(),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return full bitmap when joined with empty")
		void shouldReturnFullBitmapForEmptyAndFullBitmap() {
			assertArrayEquals(
				new int[]{1, 3, 4, 5, 8},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 3, 4, 5, 8),
					new BaseBitmap()
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should duplicate common elements when one bitmap is subset of other")
		void shouldHandleOneBitmapBeingSubsetOfOther() {
			assertArrayEquals(
				new int[]{1, 1, 2, 2, 3, 3, 4, 5, 5},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 3, 4, 5),
					new BaseBitmap(1, 2, 3, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should merge overlapping bitmaps preserving duplicates")
		void shouldHandleOverlappingBitmaps() {
			assertArrayEquals(
				new int[]{1, 1, 2, 3, 4, 5, 5, 7, 8, 8},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 5, 7, 8),
					new BaseBitmap(1, 3, 4, 5, 8)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should handle first bitmap being considerably smaller")
		void shouldHandleFirstBitmapConsiderablySmaller() {
			assertArrayEquals(
				new int[]{1, 2, 2, 3, 4, 5},
				new JoinFormula(
					1L,
					new BaseBitmap(2),
					new BaseBitmap(1, 2, 3, 4, 5)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should handle second bitmap being considerably smaller")
		void shouldHandleSecondBitmapConsiderablySmaller() {
			assertArrayEquals(
				new int[]{1, 2, 2, 3, 4, 5},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 3, 4, 5),
					new BaseBitmap(2)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should concatenate bitmaps with no common elements")
		void shouldHandleBitmapsHavingNoCommonElements() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 3, 4, 5),
					new BaseBitmap(6, 7, 8, 9, 10)
				)
					.compute().getArray()
			);
		}

		@Test
		@DisplayName("should return single bitmap when other is empty")
		void shouldHandleOneBitmapBeingEmpty() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5},
				new JoinFormula(
					1L,
					new BaseBitmap(1, 2, 3, 4, 5),
					new BaseBitmap()
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
			final JoinFormula formula = new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(2, 3, 4)
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
			final JoinFormula formula = new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(2, 3, 4)
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
		@DisplayName("should throw UnsupportedOperationException on clone attempt")
		void shouldThrowOnCloneAttempt() {
			final JoinFormula formula = new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(4, 5, 6)
			);

			assertThrows(
				UnsupportedOperationException.class,
				formula::getCloneWithInnerFormulas
			);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically-constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final long hashA = new JoinFormula(1L, new BaseBitmap(1, 2, 3), new BaseBitmap(4, 5)).getHash();
			final long hashB = new JoinFormula(1L, new BaseBitmap(1, 2, 3), new BaseBitmap(4, 5)).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different bitmaps")
		void shouldProduceDifferentHashForDifferentBitmaps() {
			final long hashA = new JoinFormula(1L, new BaseBitmap(1, 2, 3), new BaseBitmap(4, 5)).getHash();
			final long hashB = new JoinFormula(1L, new BaseBitmap(7, 8, 9), new BaseBitmap(4, 5)).getHash();

			assertNotEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should return sum of cardinalities across bitmaps")
		void shouldReturnSumOfCardinalities() {
			final JoinFormula formula = new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(4, 5)
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
			final JoinFormula formula = new JoinFormula(
				1L,
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
		@DisplayName("should NOT implement CacheableFormula")
		void shouldNotImplementCacheableFormula() {
			final JoinFormula formula = new JoinFormula(
				1L,
				new BaseBitmap(1, 2),
				new BaseBitmap(3, 4)
			);

			assertFalse(formula instanceof CacheableFormula);
		}
	}
}
