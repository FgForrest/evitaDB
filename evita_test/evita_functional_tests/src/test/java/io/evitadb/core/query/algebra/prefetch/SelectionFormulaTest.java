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
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.filter.translator.entity.alternative.LocaleEntityToBitmapFilter;
import io.evitadb.core.query.filter.translator.entity.alternative.ScopeEntityToBitmapFilter;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Locale;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
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
@Tag(ENGINE)
@Tag(QUERY)
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

	/**
	 * Equal detached keys for equal semantic inputs.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Key determinism")
	class KeyDeterminismTest {

		/** Verifies the corresponding detached key contract. */
		@Test
		@DisplayName("should produce identical key for identically constructed instances")
		void shouldProduceIdenticalKeyForIdenticallyConstructedInstances() {
			final ConstantFormula delegate = new ConstantFormula(
				new ArrayBitmap(new CompositeIntArray(1, 2, 3))
			);
			final SelectionFormula first = createFormula(delegate);
			final SelectionFormula second = createFormula(delegate);

			assertEquals(first.getKey(), second.getKey());
			assertEquals(first.getKey().getHash64(), second.getKey().getHash64());
		}
	}

	/**
	 * Sensitivity to every semantic input outside the formula children.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Key sensitivity")
	class KeySensitivityTest {

		/** Verifies the corresponding detached key contract. */
		@Test
		@DisplayName("should produce different key for different delegates")
		void shouldProduceDifferentKeyForDifferentDelegates() {
			final SelectionFormula first = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3)))
			);
			final SelectionFormula second = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(4, 5, 6)))
			);

			assertNotEquals(first.getKey(), second.getKey());
		}

		/** Verifies the corresponding detached key contract. */
		@Test
		@DisplayName("should produce different key between SelectionFormula and EmptyFormula delegate")
		void shouldProduceDifferentKeyBetweenDifferentDelegateTypes() {
			final SelectionFormula withConstant = createFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1)))
			);
			final SelectionFormula withEmpty = createFormula(EmptyFormula.INSTANCE);

			assertNotEquals(withConstant.getKey(), withEmpty.getKey());
		}
	}

	/**
	 * Identity of the alternative is independent of the delegate.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Alternative identity")
	class AlternativeIdentityTest {
		/** Distinguishes locales over an identical delegate. */
		@Test
		@DisplayName("Different locale alternatives produce different keys")
		void shouldDistinguishLocaleAlternatives() {
			final Formula delegate = new ConstantFormula(new BaseBitmap(1));
			final SelectionFormula first = new SelectionFormula(delegate,
				new LocaleEntityToBitmapFilter(Locale.ENGLISH));
			final SelectionFormula second = new SelectionFormula(delegate,
				new LocaleEntityToBitmapFilter(Locale.FRENCH));
			assertNotEquals(first.getKey(), second.getKey());
			assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		}

		/** Distinguishes scope while equal independently built alternatives remain equal. */
		@Test
		@DisplayName("Scope alternatives determine selection identity and survive cloning")
		void shouldDistinguishScopeAlternativesAndPreserveEqualClones() {
			final Formula delegate = new ConstantFormula(new BaseBitmap(1));
			final SelectionFormula first = new SelectionFormula(delegate, new ScopeEntityToBitmapFilter(Scope.LIVE));
			final SelectionFormula equal = new SelectionFormula(delegate, new ScopeEntityToBitmapFilter(Scope.LIVE));
			final SelectionFormula other = new SelectionFormula(delegate,
				new ScopeEntityToBitmapFilter(Scope.ARCHIVED));
			assertEquals(first.getKey(), equal.getKey());
			assertNotEquals(first.getKey(), other.getKey());
			assertEquals(first.getKey(), first.getCloneWithInnerFormulas(delegate).getKey());
		}

		/** A shared opaque input still binds each selection to its own instance. */
		@Test
		@DisplayName("An opaque alternative makes a newly allocated selection clone unequal")
		void shouldKeepOpaqueAlternativeSelectionsInstanceUnique() {
			final Formula delegate = new ConstantFormula(new BaseBitmap(1));
			final EntityToBitmapFilter alternative = new SelectionFormulaIdentityFixture.OpaqueBitmapFilter();
			final SelectionFormula first = new SelectionFormula(delegate, alternative);
			assertTrue(first.getKey().isOpaque());
			assertTrue(first.getKey().isQueryLocal());
			assertNotEquals(first.getKey(), new SelectionFormula(delegate, alternative).getKey());
			assertNotEquals(first.getKey(), first.getCloneWithInnerFormulas(delegate).getKey());
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
	static class NoOpEntityToBitmapFilter implements EntityToBitmapFilter {
		/** Unique identifier of the test alternative. */
		private static final long CLASS_ID = 373611245603847280L;

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

		/** Returns the declared identity of the constant result and requirements. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity();
		}

		/** Returns the test alternative's class identifier. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Identity of the constant empty alternative.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity() implements IdentityRecord {
			/** Returns the declaring alternative's identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** The result and requirement are constants without additional components. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
			}
		}
	}

}
