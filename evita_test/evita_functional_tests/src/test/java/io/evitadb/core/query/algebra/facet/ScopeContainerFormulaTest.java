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

import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link ScopeContainerFormula} verifying AND-like computation, scope metadata,
 * memoization, cloning, caching, hashing and edge case behavior.
 *
 * @author evitaDB
 */
@DisplayName("ScopeContainerFormula functionality")
@Tag(ENGINE)
@Tag(QUERY)
class ScopeContainerFormulaTest {

	@Nested
	@DisplayName("Computation")
	class ComputationTest {

		@Test
		@DisplayName("should compute AND of multiple child formulas")
		void shouldComputeAndOfMultipleChildFormulas() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
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
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(1, 2)),
				new ConstantFormula(new ArrayBitmap(3, 4))
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return single child contents when only one child provided")
		void shouldReturnSingleChildContentsWhenOnlyOneChildProvided() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20, 30))
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{10, 20, 30}, result.getArray());
		}

		@Test
		@DisplayName("should return empty bitmap when any child is empty")
		void shouldReturnEmptyBitmapWhenAnyChildIsEmpty() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20)),
				EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return empty bitmap when all children are empty")
		void shouldReturnEmptyBitmapWhenAllChildrenAreEmpty() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				EmptyFormula.INSTANCE
			);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should work with ARCHIVED scope")
		void shouldWorkWithArchivedScope() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.ARCHIVED,
				new ConstantFormula(new ArrayBitmap(10, 20, 30)),
				new ConstantFormula(new ArrayBitmap(20, 30, 40))
			);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{20, 30}, result.getArray());
		}
	}

	@Nested
	@DisplayName("Scope metadata")
	class ScopeMetadataTest {

		@Test
		@DisplayName("should return LIVE scope")
		void shouldReturnLiveScope() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10))
			);

			assertEquals(Scope.LIVE, formula.getScope());
		}

		@Test
		@DisplayName("should return ARCHIVED scope")
		void shouldReturnArchivedScope() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.ARCHIVED,
				new ConstantFormula(new ArrayBitmap(10))
			);

			assertEquals(Scope.ARCHIVED, formula.getScope());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create clone preserving scope")
		void shouldCreateClonePreservingScope() {
			final ScopeContainerFormula original = new ScopeContainerFormula(
				Scope.ARCHIVED,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			final ConstantFormula newChild = new ConstantFormula(new ArrayBitmap(100, 200));
			final Formula clone = original.getCloneWithInnerFormulas(newChild);

			assertInstanceOf(ScopeContainerFormula.class, clone);
			final ScopeContainerFormula cloned = (ScopeContainerFormula) clone;
			assertEquals(Scope.ARCHIVED, cloned.getScope());
			assertArrayEquals(new int[]{100, 200}, cloned.compute().getArray());
		}

		@Test
		@DisplayName("should return EmptyFormula when cloned with zero inner formulas")
		void shouldReturnEmptyFormulaWhenClonedWithZeroInnerFormulas() {
			final ScopeContainerFormula original = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			final Formula clone = original.getCloneWithInnerFormulas();

			assertSame(EmptyFormula.INSTANCE, clone);
		}

		@Test
		@DisplayName("should not be same instance as original")
		void shouldNotBeSameInstanceAsOriginal() {
			final ScopeContainerFormula original = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10))
			);

			final Formula clone = original.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(10))
			);

			assertNotSame(original, clone);
		}

		@Test
		@DisplayName("should create cacheable clone with computation callback")
		void shouldCreateCacheableCloneWithComputationCallback() {
			final ScopeContainerFormula original = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			final boolean[] callbackInvoked = {false};
			final CacheableFormula clone = original.getCloneWithComputationCallback(
				formula -> callbackInvoked[0] = true,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			assertInstanceOf(ScopeContainerFormula.class, clone);
			final ScopeContainerFormula cloned = (ScopeContainerFormula) clone;
			assertEquals(Scope.LIVE, cloned.getScope());

			// trigger computation to invoke callback
			cloned.compute();
			assertTrue(callbackInvoked[0]);
		}
	}

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticallyConstructedFormulas() {
			final ScopeContainerFormula formulaA = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);
			final ScopeContainerFormula formulaB = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			assertEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final ScopeContainerFormula formulaA = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);
			final ScopeContainerFormula formulaB = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(30, 40))
			);

			assertNotEquals(formulaA.getHash(), formulaB.getHash());
		}

		@Test
		@DisplayName("should produce same hash for different scopes with same children")
		void shouldProduceSameHashForDifferentScopesWithSameChildren() {
			// scope is not part of includeAdditionalHash (returns 0L)
			final ScopeContainerFormula formulaLive = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);
			final ScopeContainerFormula formulaArchived = new ScopeContainerFormula(
				Scope.ARCHIVED,
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			assertEquals(formulaLive.getHash(), formulaArchived.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality estimate")
	class CardinalityEstimateTest {

		@Test
		@DisplayName("should estimate cardinality as minimum of inner formula cardinalities")
		void shouldEstimateCardinalityAsMinimumOfInnerFormulaCardinalities() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(10, 20, 30)),
				new ConstantFormula(new ArrayBitmap(40, 50))
			);

			assertEquals(2, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return zero cardinality for empty formula child")
		void shouldReturnZeroCardinalityForEmptyFormulaChild() {
			final ScopeContainerFormula formula = new ScopeContainerFormula(
				Scope.LIVE,
				EmptyFormula.INSTANCE
			);

			assertEquals(0, formula.getEstimatedCardinality());
		}
	}

}
