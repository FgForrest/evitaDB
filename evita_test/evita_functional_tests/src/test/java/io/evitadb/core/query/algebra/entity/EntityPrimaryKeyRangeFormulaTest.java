/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.algebra.entity;

import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrimaryKeyRangeFormula} verifying inclusive range filtering
 * over a superset bitmap, unbounded edge cases, cloning, hashing, and cost estimation.
 *
 * @author evitaDB
 */
@DisplayName("EntityPrimaryKeyRangeFormula - inclusive PK range filtering")
class EntityPrimaryKeyRangeFormulaTest {

	@Nested
	@DisplayName("Computation correctness")
	class ComputationTest {

		@Test
		@DisplayName("should return empty bitmap when superset is empty")
		void shouldReturnEmptyWhenSupersetIsEmpty() {
			final EntityPrimaryKeyRangeFormula formula =
				new EntityPrimaryKeyRangeFormula(1, 10, EmptyFormula.INSTANCE);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[0], result.getArray());
		}

		@Test
		@DisplayName("should return all elements when entire superset falls within range")
		void shouldReturnAllElementsWhenAllInRange() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				2, 4, 6, 8, 10
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{2, 4, 6, 8, 10}, result.getArray());
		}

		@Test
		@DisplayName("should return empty when no elements fall within range")
		void shouldReturnEmptyWhenNoElementsInRange() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				50, 100,
				1, 5, 10, 20, 30
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[0], result.getArray());
		}

		@Test
		@DisplayName("should return subset when range selects part of superset")
		void shouldReturnSubsetWhenRangeSelectsPart() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				5, 15,
				1, 3, 5, 7, 10, 15, 20, 25
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{5, 7, 10, 15}, result.getArray());
		}

		@Test
		@DisplayName("should return single element when range matches exactly one PK")
		void shouldReturnSingleElementWhenRangeMatchesOne() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				4, 6,
				1, 5, 10, 15
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{5}, result.getArray());
		}

		@Test
		@DisplayName("should include boundary values when they exist in superset")
		void shouldIncludeBoundaryValues() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				3, 7,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{3, 5, 7}, result.getArray());
		}

		@Test
		@DisplayName("should return single PK when from equals to and element exists")
		void shouldReturnSinglePkWhenFromEqualsToAndElementExists() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				5, 5,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{5}, result.getArray());
		}

		@Test
		@DisplayName("should return empty when from equals to and element is absent")
		void shouldReturnEmptyWhenFromEqualsToAndElementAbsent() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				4, 4,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[0], result.getArray());
		}

		@Test
		@DisplayName("should correctly span across gaps in superset")
		void shouldCorrectlySpanAcrossGaps() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				10, 50,
				1, 5, 10, 30, 50, 80, 100
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 30, 50}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Unbounded range")
	class UnboundedRangeTest {

		@Test
		@DisplayName("should apply only upper bound when lower bound is MIN_VALUE")
		void shouldApplyOnlyUpperBoundWhenLowerIsMinValue() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, 5,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5}, result.getArray());
		}

		@Test
		@DisplayName("should apply only lower bound when upper bound is MAX_VALUE")
		void shouldApplyOnlyLowerBoundWhenUpperIsMaxValue() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				7, Integer.MAX_VALUE,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{7, 9}, result.getArray());
		}

		@Test
		@DisplayName("should return all elements when both bounds are unbounded")
		void shouldReturnAllWhenFullyUnbounded() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, Integer.MAX_VALUE,
				2, 4, 6, 8
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{2, 4, 6, 8}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Large PK values near Integer.MAX_VALUE")
	class LargePkTest {

		@Test
		@DisplayName("should filter correctly with PKs near Integer.MAX_VALUE")
		void shouldFilterCorrectlyWithLargePks() {
			final int nearMax = Integer.MAX_VALUE - 1;
			final int max = Integer.MAX_VALUE;
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				nearMax, max,
				1, 100, nearMax, max
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{nearMax, max}, result.getArray());
		}

		@Test
		@DisplayName(
			"should handle unbounded upper with large PKs in superset"
		)
		void shouldHandleUnboundedUpperWithLargePks() {
			final int nearMax = Integer.MAX_VALUE - 1;
			final int max = Integer.MAX_VALUE;
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				100, Integer.MAX_VALUE,
				1, 50, 100, nearMax, max
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{100, nearMax, max}, result.getArray());
		}

		@Test
		@DisplayName(
			"should return only MAX_VALUE when from equals MAX_VALUE"
		)
		void shouldReturnMaxValueWhenFromEqualsMaxValue() {
			final int max = Integer.MAX_VALUE;
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				max, max,
				1, 100, max
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{max}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Minimum valid PK boundary")
	class MinimumPkTest {

		@Test
		@DisplayName("should include PK 1 when from is 1")
		void shouldIncludePkOneWhenFromIsOne() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 3,
				1, 2, 3, 4, 5
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 2, 3}, result.getArray());
		}

		@Test
		@DisplayName(
			"should return only PK 1 when range is [1,1]"
		)
		void shouldReturnOnlyPkOneWhenRangeIsSingleOne() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 1,
				1, 2, 3
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Negative primary keys")
	class NegativePkTest {

		@Test
		@DisplayName("should filter purely negative PK range")
		void shouldFilterPurelyNegativeRange() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-10, -5,
				-15, -10, -7, -5, -1, 1, 10
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{-10, -7, -5}, result.getArray());
		}

		@Test
		@DisplayName("should filter range spanning negative to positive")
		void shouldFilterRangeSpanningNegativeToPositive() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-3, 3,
				-5, -3, -1, 0, 1, 3, 5
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{-3, -1, 0, 1, 3}, result.getArray());
		}

		@Test
		@DisplayName("should return single negative PK when from equals to")
		void shouldReturnSingleNegativePk() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-5, -5,
				-10, -5, 0, 5, 10
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{-5}, result.getArray());
		}

		@Test
		@DisplayName("should return empty when from > to")
		void shouldReturnEmptyWhenFromGreaterThanTo() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				5, -5,
				-10, -5, 0, 5, 10
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[0], result.getArray());
		}

		@Test
		@DisplayName("should handle zero in range")
		void shouldHandleZeroInRange() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-1, 1,
				-2, -1, 0, 1, 2
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{-1, 0, 1}, result.getArray());
		}

		@Test
		@DisplayName("should select only zero")
		void shouldSelectOnlyZero() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				0, 0,
				-1, 0, 1
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{0}, result.getArray());
		}

		@Test
		@DisplayName("should handle MIN_VALUE as actual PK, not just sentinel")
		void shouldHandleMinValueAsActualPk() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, Integer.MIN_VALUE + 2,
				Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2, 0, 1
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2},
				result.getArray()
			);
		}

		@Test
		@DisplayName("should handle negative lower bound with positive-only superset")
		void shouldHandleNegativeLowerBoundWithPositiveSuperset() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-100, 5,
				1, 3, 5, 7, 9
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Signed/unsigned boundary edge cases")
	class SignedUnsignedBoundaryTest {

		@Test
		@DisplayName("should handle range from MIN_VALUE to MAX_VALUE with mixed PKs")
		void shouldHandleFullRangeWithMixedPks() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, Integer.MAX_VALUE,
				Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE},
				result.getArray()
			);
		}

		@Test
		@DisplayName("should correctly filter near MAX_VALUE/MIN_VALUE boundary")
		void shouldFilterNearMaxMinBoundary() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
				Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{Integer.MAX_VALUE - 1, Integer.MAX_VALUE},
				result.getArray()
			);
		}

		@Test
		@DisplayName("should handle range [-1, 0] crossing the unsigned wrap-around")
		void shouldHandleRangeCrossingUnsignedWrapAround() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				-1, 0,
				-2, -1, 0, 1
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{-1, 0}, result.getArray());
		}

		@Test
		@DisplayName("should return all negative PKs with [MIN_VALUE, -1] range")
		void shouldReturnAllNegativePks() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, -1,
				Integer.MIN_VALUE, -100, -1, 0, 1, 100
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, -100, -1},
				result.getArray()
			);
		}

		@Test
		@DisplayName("should return all positive PKs with [0, MAX_VALUE] range")
		void shouldReturnAllPositivePks() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				0, Integer.MAX_VALUE,
				Integer.MIN_VALUE, -1, 0, 1, 100, Integer.MAX_VALUE
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{0, 1, 100, Integer.MAX_VALUE},
				result.getArray()
			);
		}

		@Test
		@DisplayName("should return all positive PKs with [1, MAX_VALUE] range")
		void shouldReturnStrictlyPositivePks() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, Integer.MAX_VALUE,
				-1, 0, 1, 100, Integer.MAX_VALUE
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(
				new int[]{1, 100, Integer.MAX_VALUE},
				result.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should delegate to inner formula cardinality")
		void shouldDelegateToInnerFormulaCardinality() {
			final ConstantFormula inner = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5))
			);
			final EntityPrimaryKeyRangeFormula formula =
				new EntityPrimaryKeyRangeFormula(2, 4, inner);

			assertEquals(5, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Operation cost")
	class OperationCostTest {

		@Test
		@DisplayName("should return 154")
		void shouldReturn154() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				1, 5, 10
			);

			assertEquals(154L, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce PK_RANGE[from,to] format")
		void shouldProducePkRangeFormat() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				3, 42,
				5, 10
			);

			assertEquals("PK_RANGE[3,42]", formula.toString());
		}

		@Test
		@DisplayName("should show MIN_VALUE and MAX_VALUE for unbounded range")
		void shouldShowMinMaxForUnboundedRange() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				Integer.MIN_VALUE, Integer.MAX_VALUE,
				1, 2
			);

			assertEquals(
				"PK_RANGE[" + Integer.MIN_VALUE + "," + Integer.MAX_VALUE + "]",
				formula.toString()
			);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create correct clone preserving range bounds")
		void shouldCreateCorrectClonePreservingRange() {
			final ConstantFormula inner = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 5, 10, 15, 20))
			);
			final EntityPrimaryKeyRangeFormula original =
				new EntityPrimaryKeyRangeFormula(5, 15, inner);

			final Formula clone = original.getCloneWithInnerFormulas(inner);

			assertInstanceOf(EntityPrimaryKeyRangeFormula.class, clone);
			assertArrayEquals(
				original.compute().getArray(),
				clone.compute().getArray()
			);
		}

		@Test
		@DisplayName(
			"should create cacheable clone with computation callback"
		)
		void shouldCreateCacheableCloneWithComputationCallback() {
			final ConstantFormula inner = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 5, 10))
			);
			final EntityPrimaryKeyRangeFormula original =
				new EntityPrimaryKeyRangeFormula(1, 10, inner);

			final boolean[] callbackInvoked = {false};
			final CacheableFormula clone =
				original.getCloneWithComputationCallback(
					formula -> callbackInvoked[0] = true,
					inner
				);

			assertInstanceOf(EntityPrimaryKeyRangeFormula.class, clone);

			// trigger computation to invoke callback
			clone.compute();
			assertTrue(callbackInvoked[0]);
		}

		@Test
		@DisplayName(
			"should throw when cloned with wrong number of inner formulas"
		)
		void shouldThrowWhenClonedWithWrongInnerFormulaCount() {
			final ConstantFormula innerA = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2))
			);
			final ConstantFormula innerB = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(3, 4))
			);
			final EntityPrimaryKeyRangeFormula original =
				new EntityPrimaryKeyRangeFormula(1, 10, innerA);

			assertThrows(
				Exception.class,
				() -> original.getCloneWithInnerFormulas(innerA, innerB)
			);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName(
			"should produce identical hash for same range and superset"
		)
		void shouldProduceIdenticalHashForSameParameters() {
			final long hashA = createFormula(5, 15, 1, 2, 3).getHash();
			final long hashB = createFormula(5, 15, 1, 2, 3).getHash();

			assertEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash for different range")
		void shouldProduceDifferentHashForDifferentRange() {
			final long hashA = createFormula(1, 10, 1, 2, 3).getHash();
			final long hashB = createFormula(5, 20, 1, 2, 3).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName(
			"should produce different hash for different superset"
		)
		void shouldProduceDifferentHashForDifferentSuperset() {
			final long hashA = createFormula(1, 10, 1, 2, 3).getHash();
			final long hashB = createFormula(1, 10, 4, 5, 6).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName(
			"should produce different hash when only from differs"
		)
		void shouldProduceDifferentHashWhenOnlyFromDiffers() {
			final long hashA = createFormula(1, 10, 1, 5, 10).getHash();
			final long hashB = createFormula(2, 10, 1, 5, 10).getHash();

			assertNotEquals(hashA, hashB);
		}

		@Test
		@DisplayName("should produce different hash when only to differs")
		void shouldProduceDifferentHashWhenOnlyToDiffers() {
			final long hashA = createFormula(1, 10, 1, 5, 10).getHash();
			final long hashB = createFormula(1, 9, 1, 5, 10).getHash();

			assertNotEquals(hashA, hashB);
		}
	}

	@Nested
	@DisplayName("Memoization")
	class MemoizationTest {

		@Test
		@DisplayName("should return same instance on repeated compute calls")
		void shouldReturnSameInstanceOnRepeatedCompute() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				2, 5, 8
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
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				2, 5, 8
			);

			final Bitmap first = formula.compute();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			assertNotSame(first, second);
			assertArrayEquals(first.getArray(), second.getArray());
		}

		@Test
		@DisplayName("should preserve hash after clearMemory")
		void shouldPreserveHashAfterClearMemory() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				5, 15,
				1, 5, 10, 15, 20
			);

			final long hashBefore = formula.getHash();
			formula.compute();
			formula.clearMemory();
			final long hashAfter = formula.getHash();

			assertEquals(hashBefore, hashAfter);
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("should have non-negative estimated and actual cost with estimated being an upper bound")
		void shouldHaveNonNegativeCostsWithEstimatedAsUpperBound() {
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				1, 3, 5, 7, 9
			);

			final long estimatedCost = formula.getEstimatedCost();
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(
				estimatedCost >= 0,
				"Estimated cost should be non-negative, was: " + estimatedCost
			);
			assertTrue(
				actualCost >= 0,
				"Actual cost should be non-negative, was: " + actualCost
			);
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
			final EntityPrimaryKeyRangeFormula formula = createFormula(
				1, 10,
				1, 5, 10
			);

			assertInstanceOf(CacheableFormula.class, formula);
		}
	}

	/**
	 * Creates an {@link EntityPrimaryKeyRangeFormula} with the given range
	 * bounds wrapping a {@link ConstantFormula} built from the supplied PKs.
	 *
	 * @param from     inclusive lower bound of the range
	 * @param to       inclusive upper bound of the range
	 * @param superSet primary keys forming the superset bitmap
	 * @return configured formula ready to compute
	 */
	@Nonnull
	private static EntityPrimaryKeyRangeFormula createFormula(
		int from,
		int to,
		int... superSet
	) {
		final ConstantFormula inner = new ConstantFormula(
			new ArrayBitmap(new CompositeIntArray(superSet))
		);
		return new EntityPrimaryKeyRangeFormula(from, to, inner);
	}
}
