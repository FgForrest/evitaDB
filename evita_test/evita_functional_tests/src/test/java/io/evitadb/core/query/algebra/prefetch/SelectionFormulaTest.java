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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SelectionFormula} verifying computation delegation, memoization,
 * cloning, hash behavior, and cost estimation.
 *
 * The non-prefetch path (delegate-based computation) is exercised directly. The
 * prefetch path (alternative filter on prefetched entities) requires a fully wired
 * `QueryExecutionContext` with real prefetched entities and is documented as a
 * limitation rather than tested here.
 *
 * @author evitaDB
 */
@DisplayName("SelectionFormula")
class SelectionFormulaTest {

	@Nested
	@DisplayName("Non-prefetch computation")
	class NonPrefetchComputationTest {

		@Test
		@DisplayName("should delegate computation to inner formula")
		void shouldDelegateComputationToInnerFormula() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 3, 5, 7))
			);
			final SelectionFormula formula = createFormula(delegate);
			initializeForNonPrefetch(formula);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{1, 3, 5, 7}, result.getArray());
		}

		@Test
		@DisplayName("should delegate to empty formula and return empty bitmap")
		void shouldDelegateToEmptyFormulaAndReturnEmptyBitmap() {
			final SelectionFormula formula = createFormula(EmptyFormula.INSTANCE);
			initializeForNonPrefetch(formula);

			final Bitmap result = formula.compute();

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should delegate to single-element formula")
		void shouldDelegateToSingleElementFormula() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(42))
			);
			final SelectionFormula formula = createFormula(delegate);
			initializeForNonPrefetch(formula);

			final Bitmap result = formula.compute();

			assertArrayEquals(new int[]{42}, result.getArray());
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should delegate to large bitmap formula")
		void shouldDelegateToLargeBitmapFormula() {
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 1; i <= 1000; i++) {
				array.add(i);
			}
			final ConstantFormula delegate = new ConstantFormula(new ArrayBitmap(array));
			final SelectionFormula formula = createFormula(delegate);
			initializeForNonPrefetch(formula);

			final Bitmap result = formula.compute();

			assertEquals(1000, result.size());
			assertEquals(1, result.getArray()[0]);
			assertEquals(1000, result.getArray()[999]);
		}
	}

	@Nested
	@DisplayName("Memoization")
	class MemoizationTest {

		@Test
		@DisplayName("should return same instance on repeated compute calls")
		void shouldReturnSameInstanceOnRepeatedComputeCalls() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3))
			);
			final SelectionFormula formula = createFormula(delegate);
			initializeForNonPrefetch(formula);

			final Bitmap first = formula.compute();
			final Bitmap second = formula.compute();

			assertSame(first, second);
		}

		@Test
		@DisplayName("should recompute after clearMemory")
		void shouldRecomputeAfterClearMemory() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(10, 20, 30))
			);
			final SelectionFormula formula = createFormula(delegate);
			initializeForNonPrefetch(formula);

			final Bitmap first = formula.compute();
			formula.clearMemory();
			final Bitmap second = formula.compute();

			// result content is identical but memoization was cleared
			assertArrayEquals(first.getArray(), second.getArray());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should create clone with single inner formula")
		void shouldCreateCloneWithSingleInnerFormula() {
			final ConstantFormula originalDelegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2))
			);
			final SelectionFormula original = createFormula(originalDelegate);

			final ConstantFormula newDelegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(3, 4))
			);
			final Formula clone = original.getCloneWithInnerFormulas(newDelegate);

			assertNotNull(clone);
			final SelectionFormula clonedSelection = assertInstanceOf(SelectionFormula.class, clone);
			initializeForNonPrefetch(clonedSelection);

			assertArrayEquals(new int[]{3, 4}, clonedSelection.compute().getArray());
		}

		@Test
		@DisplayName("should throw when zero inner formulas provided")
		void shouldThrowWhenZeroInnerFormulasProvided() {
			final SelectionFormula formula = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);

			assertThrows(
				Exception.class,
				() -> formula.getCloneWithInnerFormulas()
			);
		}

		@Test
		@DisplayName("should throw when more than one inner formula provided")
		void shouldThrowWhenMoreThanOneInnerFormulaProvided() {
			final SelectionFormula formula = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);

			assertThrows(
				Exception.class,
				() -> formula.getCloneWithInnerFormulas(
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1))),
					new ConstantFormula(new ArrayBitmap(new CompositeIntArray(2)))
				)
			);
		}
	}

	@Nested
	@DisplayName("Hash determinism")
	class HashDeterminismTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed instances")
		void shouldProduceIdenticalHashForIdenticallyConstructedInstances() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3))
			);
			final SelectionFormula first = createFormula(delegate);
			final SelectionFormula second = createFormula(delegate);

			assertEquals(first.getHash(), second.getHash());
		}

		@Test
		@DisplayName("should produce identical transactional id hash for same delegate")
		void shouldProduceIdenticalTransactionalIdHashForSameDelegate() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(5, 10, 15))
			);
			final SelectionFormula first = createFormula(delegate);
			final SelectionFormula second = createFormula(delegate);

			assertEquals(
				first.getTransactionalIdHash(),
				second.getTransactionalIdHash()
			);
		}
	}

	@Nested
	@DisplayName("Hash sensitivity")
	class HashSensitivityTest {

		@Test
		@DisplayName("should produce different hash for different delegates")
		void shouldProduceDifferentHashForDifferentDelegates() {
			final SelectionFormula first = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)))
			);
			final SelectionFormula second = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5, 6)))
			);

			assertNotEquals(first.getHash(), second.getHash());
		}

		@Test
		@DisplayName("should produce different hash between SelectionFormula and EmptyFormula delegate")
		void shouldProduceDifferentHashBetweenDifferentDelegateTypes() {
			final SelectionFormula withConstant = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);
			final SelectionFormula withEmpty = createFormula(EmptyFormula.INSTANCE);

			assertNotEquals(withConstant.getHash(), withEmpty.getHash());
		}
	}

	@Nested
	@DisplayName("Cardinality and cost estimates")
	class CardinalityAndCostEstimatesTest {

		@Test
		@DisplayName("should delegate estimated cardinality to child formula")
		void shouldDelegateEstimatedCardinalityToChildFormula() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5))
			);
			final SelectionFormula formula = createFormula(delegate);

			// without initialization, prefetchEstimatedCardinality is null
			// → delegates to child's estimated cardinality
			assertEquals(delegate.getEstimatedCardinality(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should return zero estimated cardinality when delegate is empty")
		void shouldReturnZeroEstimatedCardinalityWhenDelegateIsEmpty() {
			final SelectionFormula formula = createFormula(EmptyFormula.INSTANCE);

			assertEquals(0, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should delegate estimated cost to child formula when not initialized")
		void shouldDelegateEstimatedCostToChildFormulaWhenNotInitialized() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3))
			);
			final SelectionFormula formula = createFormula(delegate);

			// prefetchEstimatedCost is null → delegates to child
			assertEquals(delegate.getEstimatedCost(), formula.getEstimatedCost());
		}

		@Test
		@DisplayName("should return 1 for operation cost")
		void shouldReturnOneForOperationCost() {
			final SelectionFormula formula = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);

			assertEquals(1L, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("Structure")
	class StructureTest {

		@Test
		@DisplayName("should expose delegate as first inner formula")
		void shouldExposeDelegateAsFirstInnerFormula() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2))
			);
			final SelectionFormula formula = createFormula(delegate);

			assertEquals(1, formula.getInnerFormulas().length);
			assertSame(delegate, formula.getDelegate());
			assertSame(delegate, formula.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should return null entity require when alternative has none")
		void shouldReturnNullEntityRequireWhenAlternativeHasNone() {
			final SelectionFormula formula = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);

			assertNull(formula.getEntityRequire());
		}

		@Test
		@DisplayName("should return descriptive toString")
		void shouldReturnDescriptiveToString() {
			final SelectionFormula formula = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);

			assertEquals(
				"APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE",
				formula.toString()
			);
		}
	}

	@Nested
	@DisplayName("Cost ordering")
	class CostOrderingTest {

		@Test
		@DisplayName("should satisfy estimatedCost >= delegate estimated cost")
		void shouldSatisfyEstimatedCostGreaterOrEqualToDelegateEstimatedCost() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3))
			);
			final SelectionFormula formula = createFormula(delegate);

			// SelectionFormula delegates to child's estimatedCost when no prefetch
			assertTrue(formula.getEstimatedCost() >= delegate.getEstimatedCost());
		}
	}

	@Nested
	@DisplayName("Construction validation")
	class ConstructionValidationTest {

		@Test
		@DisplayName("should reject SkipFormula as delegate")
		void shouldRejectSkipFormulaAsDelegate() {
			assertThrows(
				Exception.class,
				() -> new SelectionFormula(SkipFormula.INSTANCE, new NoOpEntityToBitmapFilter())
			);
		}
	}

	/**
	 * Creates a {@link SelectionFormula} with the given delegate and a no-op alternative filter.
	 *
	 * @param delegate the delegate formula for the non-prefetch computation path
	 * @return new selection formula instance
	 */
	@Nonnull
	private static SelectionFormula createFormula(@Nonnull Formula delegate) {
		return new SelectionFormula(delegate, new NoOpEntityToBitmapFilter());
	}

	/**
	 * Initializes the formula with a non-prefetch execution context so that
	 * `computeInternal()` follows the delegate path. Uses a mock
	 * `QueryPlanningContext` because constructing a real one requires full
	 * engine infrastructure.
	 *
	 * @param formula the formula to initialize
	 */
	private static void initializeForNonPrefetch(@Nonnull SelectionFormula formula) {
		final QueryExecutionContext context = new QueryExecutionContext(
			Mockito.mock(QueryPlanningContext.class),
			false,
			null,
			(aClass, sealedEntity) -> {
				throw new UnsupportedOperationException();
			}
		);
		formula.initialize(context);
	}

	/**
	 * Minimal {@link EntityToBitmapFilter} that returns a fixed empty bitmap and
	 * declares no entity requirements. Used for tests that exercise structural
	 * behavior rather than actual entity filtering.
	 */
	private static class NoOpEntityToBitmapFilter implements EntityToBitmapFilter {

		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			return new ArrayBitmap();
		}

		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}
	}
}
