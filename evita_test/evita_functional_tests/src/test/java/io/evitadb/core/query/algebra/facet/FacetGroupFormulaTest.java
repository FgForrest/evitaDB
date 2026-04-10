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

package io.evitadb.core.query.algebra.facet;

import com.esotericsoftware.kryo.util.IntMap;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetGroupAndFormula} and {@link FacetGroupOrFormula} verifying
 * computation, memoization, cloning, hashing, merging and edge case behavior.
 *
 * @author evitaDB
 */
@DisplayName("FacetGroupFormula (And + Or) functionality")
class FacetGroupFormulaTest {

	@Nested
	@DisplayName("FacetGroupAndFormula computation")
	class AndComputationTest {

		@Test
		@DisplayName("should compute AND across overlapping bitmaps")
		void shouldComputeAndAcrossOverlappingBitmaps() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(20, 30, 40)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap for non-overlapping bitmaps")
		void shouldReturnEmptyBitmapForNonOverlappingBitmaps() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}

		@Test
		@DisplayName("should return single bitmap contents when only one bitmap provided")
		void shouldReturnSingleBitmapContentsWhenOnlyOneBitmapProvided() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20, 30)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap when any input bitmap is empty")
		void shouldReturnEmptyBitmapWhenAnyInputBitmapIsEmpty() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap()
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}
	}

	@Nested
	@DisplayName("FacetGroupOrFormula computation")
	class OrComputationTest {

		@Test
		@DisplayName("should compute OR across overlapping bitmaps")
		void shouldComputeOrAcrossOverlappingBitmaps() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(20, 30, 40)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30, 40}, result.getArray());
		}

		@Test
		@DisplayName("should compute OR across non-overlapping bitmaps")
		void shouldComputeOrAcrossNonOverlappingBitmaps() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30, 40}, result.getArray());
		}

		@Test
		@DisplayName("should return single bitmap contents when only one bitmap provided")
		void shouldReturnSingleBitmapContentsWhenOnlyOneBitmapProvided() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20, 30)
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap when no bitmaps provided")
		void shouldReturnEmptyBitmapWhenNoBitmapsProvided() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				EmptyBitmap.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should handle one empty and one non-empty bitmap")
		void shouldHandleOneEmptyAndOneNonEmptyBitmap() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap()
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Memoization")
	class MemoizationTest {

		@Test
		@DisplayName("should return same instance on repeated AND compute calls")
		void shouldReturnSameInstanceOnRepeatedAndComputeCalls() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should return same instance on repeated OR compute calls")
		void shouldReturnSameInstanceOnRepeatedOrComputeCalls() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("Clear memory and recomputation")
	class ClearMemoryTest {

		@Test
		@DisplayName("should recompute AND after clearMemory and produce equal result")
		void shouldRecomputeAndAfterClearMemoryAndProduceEqualResult() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(20, 30, 40)
			);

			final Bitmap first = formula.compute();
			final int[] firstArray = first.getArray();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			assertNotSame(first, second);
			assertArrayEquals(firstArray, second.getArray());
		}

		@Test
		@DisplayName("should recompute OR after clearMemory and produce equal result")
		void shouldRecomputeOrAfterClearMemoryAndProduceEqualResult() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			final Bitmap first = formula.compute();
			final int[] firstArray = first.getArray();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			assertNotSame(first, second);
			assertArrayEquals(firstArray, second.getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return same AND formula instance since it has no inner formulas")
		void shouldReturnSameAndFormulaInstanceSinceItHasNoInnerFormulas() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			final Formula clone = formula.getCloneWithInnerFormulas();

			assertSame(formula, clone);
		}

		@Test
		@DisplayName("should reject inner formulas for AND formula clone")
		void shouldRejectInnerFormulasForAndFormulaClone() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> formula.getCloneWithInnerFormulas(
					new FacetGroupAndFormula("other", 2, new ArrayBitmap(5), new BaseBitmap(50))
				)
			);
		}

		@Test
		@DisplayName("should return same OR formula instance since it has no inner formulas")
		void shouldReturnSameOrFormulaInstanceSinceItHasNoInnerFormulas() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			final Formula clone = formula.getCloneWithInnerFormulas();

			assertSame(formula, clone);
		}

		@Test
		@DisplayName("should reject inner formulas for OR formula clone")
		void shouldRejectInnerFormulasForOrFormulaClone() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> formula.getCloneWithInnerFormulas(
					new FacetGroupOrFormula("other", 2, new ArrayBitmap(5), new BaseBitmap(50))
				)
			);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed AND formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedAndFormulas() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different reference names")
		void shouldProduceDifferentHashForDifferentReferenceNames() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"category", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different facet group ids")
		void shouldProduceDifferentHashForDifferentFacetGroupIds() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"product", 2,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different facet ids")
		void shouldProduceDifferentHashForDifferentFacetIds() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(2),
				new BaseBitmap(10)
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hashes for AND vs OR formulas")
		void shouldProduceDifferentHashesForAndVsOrFormulas() {
			final FacetGroupAndFormula andFormula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);
			final FacetGroupOrFormula orFormula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);

			assertNotEquals(andFormula.getHash(), orFormula.getHash());
		}

		@Test
		@DisplayName("should produce identical hash for identically constructed OR formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedOrFormulas() {
			final FacetGroupOrFormula formulaA = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);
			final FacetGroupOrFormula formulaB = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20),
				new BaseBitmap(30, 40)
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should handle null facet group id in hash")
		void shouldHandleNullFacetGroupIdInHash() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for null vs non-null facet group id")
		void shouldProduceDifferentHashForNullVsNonNullFacetGroupId() {
			final FacetGroupAndFormula formulaA = new FacetGroupAndFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);
			final FacetGroupAndFormula formulaB = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("AND should estimate cardinality as minimum of bitmap sizes")
		void andShouldEstimateCardinalityAsMinimumOfBitmapSizes() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2, 3),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(40, 50),
				new BaseBitmap(60, 70, 80, 90)
			);

			assertEquals(2, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("OR should estimate cardinality as sum of bitmap sizes")
		void orShouldEstimateCardinalityAsSumOfBitmapSizes() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2, 3),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(40, 50),
				new BaseBitmap(60, 70, 80, 90)
			);

			assertEquals(9, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("AND estimated cost should be a non-negative upper bound of actual cost")
		void andEstimatedCostShouldBeUpperBoundOfActualCost() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(20, 30, 40)
			);

			final long estimatedCost = formula.getEstimatedCost();
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(estimatedCost >= 0, "Estimated cost should be non-negative, was: " + estimatedCost);
			assertTrue(actualCost >= 0, "Actual cost should be non-negative, was: " + actualCost);
			assertTrue(
				estimatedCost >= actualCost,
				"Estimated cost (" + estimatedCost + ") should be >= actual cost (" + actualCost + ")"
			);
		}

		@Test
		@DisplayName("OR estimated cost should be a non-negative upper bound of actual cost")
		void orEstimatedCostShouldBeUpperBoundOfActualCost() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10, 20, 30),
				new BaseBitmap(20, 30, 40)
			);

			final long estimatedCost = formula.getEstimatedCost();
			formula.compute();
			final long actualCost = formula.getCost();

			assertTrue(estimatedCost >= 0, "Estimated cost should be non-negative, was: " + estimatedCost);
			assertTrue(actualCost >= 0, "Actual cost should be non-negative, was: " + actualCost);
			assertTrue(
				estimatedCost >= actualCost,
				"Estimated cost (" + estimatedCost + ") should be >= actual cost (" + actualCost + ")"
			);
		}
	}

	@Nested
	@DisplayName("Merge with")
	class MergeWithTest {

		@Test
		@DisplayName("should merge two AND formulas with overlapping facet ids")
		void shouldMergeTwoAndFormulasWithOverlappingFacetIds() {
			final FacetGroupAndFormula a = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2, 3),
				new BaseBitmap(10),
				new BaseBitmap(11),
				new BaseBitmap(12)
			);
			final FacetGroupAndFormula b = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(3, 4),
				new BaseBitmap(20),
				new BaseBitmap(21)
			);

			final FacetGroupFormula result = a.mergeWith(b);

			assertInstanceOf(FacetGroupAndFormula.class, result);
			assertFacetGroupFormulaIs(
				new int[]{1, 2, 3, 4},
				new Bitmap[]{
					new BaseBitmap(10),
					new BaseBitmap(11),
					new BaseBitmap(12, 20),
					new BaseBitmap(21),
				},
				(FacetGroupAndFormula) result
			);
		}

		@Test
		@DisplayName("should merge two AND formulas with disjoint facet ids")
		void shouldMergeTwoAndFormulasWithDisjointFacetIds() {
			final FacetGroupAndFormula a = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10),
				new BaseBitmap(20)
			);
			final FacetGroupAndFormula b = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(3, 4),
				new BaseBitmap(30),
				new BaseBitmap(40)
			);

			final FacetGroupFormula result = a.mergeWith(b);

			assertInstanceOf(FacetGroupAndFormula.class, result);
			assertFacetGroupFormulaIs(
				new int[]{1, 2, 3, 4},
				new Bitmap[]{
					new BaseBitmap(10),
					new BaseBitmap(20),
					new BaseBitmap(30),
					new BaseBitmap(40),
				},
				(FacetGroupAndFormula) result
			);
		}

		@Test
		@DisplayName("should merge two OR formulas together")
		void shouldMergeTwoOrFormulasTogether() {
			final FacetGroupOrFormula a = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10),
				new BaseBitmap(20)
			);
			final FacetGroupOrFormula b = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(3),
				new BaseBitmap(30)
			);

			final FacetGroupFormula result = a.mergeWith(b);

			assertInstanceOf(FacetGroupOrFormula.class, result);
			final FacetGroupOrFormula orResult = (FacetGroupOrFormula) result;
			assertArrayEquals(new int[]{1, 2, 3}, orResult.getFacetIds().getArray());
			assertEquals(3, orResult.getBitmaps().length);
		}

		@Test
		@DisplayName("should merge two AND formulas via static mergeWith")
		void shouldMergeTwoFacetGroupFormulasTogetherViaStaticMergeWith() {
			final FacetGroupAndFormula a = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2, 3),
				new BaseBitmap(10),
				new BaseBitmap(11),
				new BaseBitmap(12)
			);
			final FacetGroupAndFormula b = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(3, 4),
				new BaseBitmap(20),
				new BaseBitmap(21)
			);

			final FacetGroupAndFormula result = FacetGroupFormula.mergeWith(
				a, b, (facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
			);

			assertNotNull(result);
			assertFacetGroupFormulaIs(
				new int[]{1, 2, 3, 4},
				new Bitmap[]{
					new BaseBitmap(10),
					new BaseBitmap(11),
					new BaseBitmap(12, 20),
					new BaseBitmap(21),
				},
				result
			);
		}

		@Test
		@DisplayName("should fail to merge when reference names differ")
		void shouldFailToMergeWhenReferenceNamesDiffer() {
			assertThrows(
				EvitaInternalError.class,
				() -> FacetGroupFormula.mergeWith(
					new FacetGroupAndFormula("productA", 1, EmptyBitmap.INSTANCE),
					new FacetGroupAndFormula("productB", 1, EmptyBitmap.INSTANCE),
					(facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
				)
			);
		}

		@Test
		@DisplayName("should fail to merge when group ids differ")
		void shouldFailToMergeWhenGroupIdsDiffer() {
			assertThrows(
				EvitaInternalError.class,
				() -> FacetGroupFormula.mergeWith(
					new FacetGroupAndFormula("product", 1, EmptyBitmap.INSTANCE),
					new FacetGroupAndFormula("product", 2, EmptyBitmap.INSTANCE),
					(facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
				)
			);
		}

		@Test
		@DisplayName("should fail to merge when one group id is null and the other is not")
		void shouldFailToMergeWhenOneGroupIdIsNullAndTheOtherIsNot() {
			assertThrows(
				EvitaInternalError.class,
				() -> FacetGroupFormula.mergeWith(
					new FacetGroupAndFormula("product", null, EmptyBitmap.INSTANCE),
					new FacetGroupAndFormula("product", 1, EmptyBitmap.INSTANCE),
					(facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
				)
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("AND should fail when facetIds count does not match bitmaps count")
		void andShouldFailWhenFacetIdsCountDoesNotMatchBitmapsCount() {
			assertThrows(
				EvitaInternalError.class,
				() -> new FacetGroupAndFormula(
					"product", 1,
					new ArrayBitmap(1, 2, 3),
					new BaseBitmap(10),
					new BaseBitmap(20)
				)
			);
		}

		@Test
		@DisplayName("AND should support null facet group id")
		void andShouldSupportNullFacetGroupId() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);

			assertNull(formula.getFacetGroupId());
			assertArrayEquals(new int[]{10, 20}, formula.compute().getArray());
		}

		@Test
		@DisplayName("OR should support null facet group id")
		void orShouldSupportNullFacetGroupId() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10, 20)
			);

			assertNull(formula.getFacetGroupId());
			assertArrayEquals(new int[]{10, 20}, formula.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Operation cost")
	class OperationCostTest {

		@Test
		@DisplayName("AND operation cost should be 15")
		void andOperationCostShouldBe15() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertEquals(15, formula.getOperationCost());
		}

		@Test
		@DisplayName("OR operation cost should be 11")
		void orOperationCostShouldBe11() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertEquals(11, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("AND toString should contain reference name and AND keyword")
		void andToStringShouldContainReferenceNameAndAndKeyword() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10),
				new BaseBitmap(20)
			);

			final String result = formula.toString();

			assertTrue(result.contains("product"));
			assertTrue(result.contains("AND"));
		}

		@Test
		@DisplayName("OR toString should contain reference name and OR keyword")
		void orToStringShouldContainReferenceNameAndOrKeyword() {
			final FacetGroupOrFormula formula = new FacetGroupOrFormula(
				"product", 1,
				new ArrayBitmap(1, 2),
				new BaseBitmap(10),
				new BaseBitmap(20)
			);

			final String result = formula.toString();

			assertTrue(result.contains("product"));
			assertTrue(result.contains("OR"));
		}

		@Test
		@DisplayName("AND toString should show dash for null group id")
		void andToStringShouldShowDashForNullGroupId() {
			final FacetGroupAndFormula formula = new FacetGroupAndFormula(
				"product", null,
				new ArrayBitmap(1),
				new BaseBitmap(10)
			);

			assertTrue(formula.toString().contains("-"));
		}
	}

	/**
	 * Asserts that the given {@link FacetGroupAndFormula} contains exactly the expected facet ids and bitmaps.
	 *
	 * @param facetIds       expected facet ids
	 * @param bitmaps        expected bitmaps for each facet id
	 * @param actualFormula  the formula to check
	 */
	private static void assertFacetGroupFormulaIs(
		@Nonnull int[] facetIds,
		@Nonnull Bitmap[] bitmaps,
		@Nonnull FacetGroupAndFormula actualFormula
	) {
		final Bitmap actualFacetIds = actualFormula.getFacetIds();
		final Bitmap[] actualBitmaps = actualFormula.getBitmaps();

		assertEquals(facetIds.length, actualFacetIds.size());
		assertEquals(bitmaps.length, actualBitmaps.length);

		final IntMap<Bitmap> expected = new IntMap<>(facetIds.length);
		for (int i = 0; i < facetIds.length; i++) {
			expected.put(facetIds[i], bitmaps[i]);
		}

		final IntMap<Bitmap> actual = new IntMap<>(actualFacetIds.size());
		int index = 0;
		for (Integer facetId : actualFacetIds) {
			actual.put(facetId, actualBitmaps[index++]);
		}

		assertEquals(expected, actual);
	}
}
