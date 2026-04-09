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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CombinedFacetFormula} verifying computation, memoization, cloning,
 * hashing, accessor methods and edge case behavior.
 *
 * @author evitaDB
 */
@DisplayName("CombinedFacetFormula functionality")
class CombinedFacetFormulaTest {

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should compute OR of and-formula and or-formula results")
		void shouldComputeOrOfAndFormulaAndOrFormulaResults() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10, 20, 30));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(30, 40, 50));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30, 40, 50}, result.getArray());
		}

		@Test
		@DisplayName("should compute OR with non-overlapping inputs")
		void shouldComputeOrWithNonOverlappingInputs() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(4, 5, 6));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.getArray());
		}

		@Test
		@DisplayName("should compute OR with fully overlapping inputs")
		void shouldComputeOrWithFullyOverlappingInputs() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10, 20, 30));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(10, 20, 30));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should handle empty formula as one of the inputs")
		void shouldHandleEmptyFormulaAsOneOfTheInputs() {
			final ConstantFormula nonEmpty = new ConstantFormula(new ArrayBitmap(10, 20));
			final CombinedFacetFormula formula = new CombinedFacetFormula(
				EmptyFormula.INSTANCE, nonEmpty
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20}, result.getArray());
		}

		@Test
		@DisplayName("should return empty result when both inputs are empty")
		void shouldReturnEmptyResultWhenBothInputsAreEmpty() {
			final CombinedFacetFormula formula = new CombinedFacetFormula(
				EmptyFormula.INSTANCE, EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertEquals(0, result.size());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return and-formula via getAndFormula")
		void shouldReturnAndFormulaViaGetAndFormula() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(20));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			assertSame(andPart, formula.getAndFormula());
		}

		@Test
		@DisplayName("should return or-formula via getOrFormula")
		void shouldReturnOrFormulaViaGetOrFormula() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(20));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			assertSame(orPart, formula.getOrFormula());
		}

		@Test
		@DisplayName("should have exactly two inner formulas")
		void shouldHaveExactlyTwoInnerFormulas() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(20));
			final CombinedFacetFormula formula = new CombinedFacetFormula(andPart, orPart);

			assertEquals(2, formula.getInnerFormulas().length);
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create clone with different inner formulas")
		void shouldCreateCloneWithDifferentInnerFormulas() {
			final ConstantFormula andPart = new ConstantFormula(new ArrayBitmap(10));
			final ConstantFormula orPart = new ConstantFormula(new ArrayBitmap(20));
			final CombinedFacetFormula original = new CombinedFacetFormula(andPart, orPart);

			final ConstantFormula newAndPart = new ConstantFormula(new ArrayBitmap(100));
			final ConstantFormula newOrPart = new ConstantFormula(new ArrayBitmap(200));
			final Formula clone = original.getCloneWithInnerFormulas(newAndPart, newOrPart);

			assertInstanceOf(CombinedFacetFormula.class, clone);
			final CombinedFacetFormula clonedFormula = (CombinedFacetFormula) clone;
			assertSame(newAndPart, clonedFormula.getAndFormula());
			assertSame(newOrPart, clonedFormula.getOrFormula());
		}

		@Test
		@DisplayName("should not be same instance as original")
		void shouldNotBeSameInstanceAsOriginal() {
			final CombinedFacetFormula original = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10)),
				new ConstantFormula(new ArrayBitmap(20))
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(10)),
				new ConstantFormula(new ArrayBitmap(20))
			);

			assertNotSame(original, clone);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final CombinedFacetFormula formulaA = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(30, 40))
			);
			final CombinedFacetFormula formulaB = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(30, 40))
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final CombinedFacetFormula formulaA = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(30, 40))
			);
			final CombinedFacetFormula formulaB = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(50, 60))
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should estimate cardinality as sum of inner formula cardinalities")
		void shouldEstimateCardinalityAsSumOfInnerFormulaCardinalities() {
			final CombinedFacetFormula formula = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10, 20, 30)),
				new ConstantFormula(new ArrayBitmap(40, 50))
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("should throw when cloned with fewer than two inner formulas")
		void shouldThrowWhenClonedWithFewerThanTwoInnerFormulas() {
			final CombinedFacetFormula original = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10)),
				new ConstantFormula(new ArrayBitmap(20))
			);

			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCloneWithInnerFormulas(
					new ConstantFormula(new ArrayBitmap(100))
				)
			);

			assertTrue(
				exception.getMessage().contains("exactly 2"),
				"Exception message should mention exactly 2 required formulas, was: " + exception.getMessage()
			);
		}

		@Test
		@DisplayName("should throw when cloned with more than two inner formulas")
		void shouldThrowWhenClonedWithMoreThanTwoInnerFormulas() {
			final CombinedFacetFormula original = new CombinedFacetFormula(
				new ConstantFormula(new ArrayBitmap(10)),
				new ConstantFormula(new ArrayBitmap(20))
			);

			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCloneWithInnerFormulas(
					new ConstantFormula(new ArrayBitmap(100)),
					new ConstantFormula(new ArrayBitmap(200)),
					new ConstantFormula(new ArrayBitmap(300))
				)
			);

			assertTrue(
				exception.getMessage().contains("exactly 2"),
				"Exception message should mention exactly 2 required formulas, was: " + exception.getMessage()
			);
		}
	}
}
