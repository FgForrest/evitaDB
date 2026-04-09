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
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UserFilterFormula} verifying AND-like computation, memoization, cloning,
 * hashing, interface contracts and edge case behavior.
 *
 * @author evitaDB
 */
@DisplayName("UserFilterFormula functionality")
class UserFilterFormulaTest {

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should compute AND of multiple child formulas")
		void shouldComputeAndOfMultipleChildFormulas() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5)),
				new ConstantFormula(new ArrayBitmap(2, 3, 4, 6)),
				new ConstantFormula(new ArrayBitmap(3, 4, 7))
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{3, 4}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap for non-overlapping children")
		void shouldReturnEmptyBitmapForNonOverlappingChildren() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)),
				new ConstantFormula(new ArrayBitmap(3, 4))
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return single child contents when only one child provided")
		void shouldReturnSingleChildContentsWhenOnlyOneChildProvided() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20, 30))
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap when any child is empty")
		void shouldReturnEmptyBitmapWhenAnyChildIsEmpty() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return empty bitmap when all children are empty")
		void shouldReturnEmptyBitmapWhenAllChildrenAreEmpty() {
			final UserFilterFormula formula = new UserFilterFormula(
				EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should compute AND with two fully overlapping children")
		void shouldComputeAndWithTwoFullyOverlappingChildren() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20, 30)),
				new ConstantFormula(new ArrayBitmap(10, 20, 30))
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create clone with replacement inner formulas")
		void shouldCreateCloneWithReplacementInnerFormulas() {
			final UserFilterFormula original = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			final ConstantFormula newChild = new ConstantFormula(new ArrayBitmap(100, 200));
			final Formula clone = original.getCloneWithInnerFormulas(newChild);

			assertInstanceOf(UserFilterFormula.class, clone);
			assertNotSame(original, clone);
			assertArrayEquals(new int[]{100, 200}, clone.compute().getArray());
		}

		@Test
		@DisplayName("should return EmptyFormula when cloned with zero inner formulas")
		void shouldReturnEmptyFormulaWhenClonedWithZeroInnerFormulas() {
			final UserFilterFormula original = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should create clone with multiple inner formulas")
		void shouldCreateCloneWithMultipleInnerFormulas() {
			final UserFilterFormula original = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10))
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				new ConstantFormula(new ArrayBitmap(2, 3, 4))
			);

			assertInstanceOf(UserFilterFormula.class, clone);
			assertArrayEquals(new int[]{2, 3}, clone.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final UserFilterFormula formulaA = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(30, 40))
			);
			final UserFilterFormula formulaB = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20)),
				new ConstantFormula(new ArrayBitmap(30, 40))
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final UserFilterFormula formulaA = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20))
			);
			final UserFilterFormula formulaB = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(30, 40))
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce same hash regardless of child order")
		void shouldProduceSameHashRegardlessOfChildOrder() {
			final ConstantFormula childA = new ConstantFormula(new ArrayBitmap(10, 20));
			final ConstantFormula childB = new ConstantFormula(new ArrayBitmap(30, 40));

			final UserFilterFormula formulaAB = new UserFilterFormula(childA, childB);
			final UserFilterFormula formulaBA = new UserFilterFormula(childB, childA);

			// AbstractFormula sorts inner formula hashes by default (isFormulaOrderSignificant returns false)
			assertEquals(formulaAB.getHash(), formulaBA.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should estimate cardinality as minimum of inner formula cardinalities")
		void shouldEstimateCardinalityAsMinimumOfInnerFormulaCardinalities() {
			final UserFilterFormula formula = new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(10, 20, 30)),
				new ConstantFormula(new ArrayBitmap(40, 50))
			);

			assertEquals(2, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return zero cardinality for empty formula child")
		void shouldReturnZeroCardinalityForEmptyFormulaChild() {
			final UserFilterFormula formula = new UserFilterFormula(
				EmptyFormula.INSTANCE
			);

			assertEquals(0, formula.getEstimatedCardinality());
		}
	}

}
