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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link LowestPriceTerminationFormula} verifying construction, hashing, cloning,
 * cardinality estimation, and cache behavior.
 *
 * Note: full computation tests for `compute()` require a subtree containing
 * {@link io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor} implementations
 * providing price records per entity, which cannot be constructed without mocking.
 * The contract tests below cover all aspects that don't require actual price record resolution.
 *
 * @author evitaDB
 */
@DisplayName("LowestPriceTerminationFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class LowestPriceTerminationFormulaTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");

	@Nested
	@DisplayName("Hash determinism and sensitivity")
	class HashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);

			assertEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different evaluation context")
		void shouldProduceDifferentHashForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("vip", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different selling price predicate")
		void shouldProduceDifferentHashForDifferentSellingPricePredicate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PriceEvaluationContext context = createContext("basic", CZK);

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final PriceRecordPredicate rangePredicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, rangePredicate
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createContext("basic", CZK);

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(3, 4)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different currency")
		void shouldProduceDifferentHashForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("basic", EUR),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should preserve context, price mode, and predicate when cloning")
		void shouldPreserveAllConfigWhenCloning() {
			final PriceEvaluationContext context = createContext("vip", EUR);
			final PriceRecordPredicate predicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2
			);
			final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITHOUT_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertSame(context, typedClone.getPriceEvaluationContext());
			assertEquals(QueryPriceMode.WITHOUT_TAX, typedClone.getQueryPriceMode());
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}

		@Test
		@DisplayName("should throw when cloning with wrong number of inner formulas")
		void shouldThrowWhenCloningWithWrongNumberOfInnerFormulas() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertThrows(
				Exception.class,
				() -> formula.getCloneWithInnerFormulas(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				)
			);
		}

		@Test
		@DisplayName("should create clone with computation callback preserving all config")
		void shouldCreateCloneWithComputationCallbackPreservingAllConfig() {
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;
			final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITH_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(5, 6));
			final CacheableFormula clone = original.getCloneWithComputationCallback(
				f -> { /* no-op callback */ }, newInner
			);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertSame(context, typedClone.getPriceEvaluationContext());
			assertEquals(QueryPriceMode.WITH_TAX, typedClone.getQueryPriceMode());
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}

		@Test
		@DisplayName("should create clone with price predicate filtered out results")
		void shouldCreateCloneWithPricePredicateFilteredOutResults() {
			// first compute to populate recordsFilteredOutByPredicate
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			// trigger computation on empty input - populates filtered out records
			formula.compute();

			final Formula clone = formula.getCloneWithPricePredicateFilteredOutResults();

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			// the clone should use ALL_RECORD_FILTER predicate
			assertSame(PricePredicate.ALL_RECORD_FILTER, typedClone.getSellingPricePredicate());
		}
	}

	@Nested
	@DisplayName("Cardinality and cost")
	class CardinalityAndCostTest {

		@Test
		@DisplayName("should delegate estimated cardinality to inner formula")
		void shouldDelegateEstimatedCardinalityToInnerFormula() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should report operation cost of 18203")
		void shouldReportOperationCostOf18203() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(18203, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("Contract details")
	class ContractTest {

		@Test
		@DisplayName("should return delegate formula via getDelegate")
		void shouldReturnDelegateFormulaViaGetDelegate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertSame(inner, formula.getDelegate());
		}

		@Test
		@DisplayName("should return exactly one inner formula")
		void shouldReturnExactlyOneInnerFormula() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(1, formula.getInnerFormulas().length);
		}

		@Test
		@DisplayName("should return predicate's requested predicate")
		void shouldReturnPredicatesRequestedPredicate() {
			final PriceRecordPredicate rangePredicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2
			);
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, rangePredicate
			);

			assertNotNull(formula.getRequestedPredicate());
		}

		@Test
		@DisplayName("should produce non-empty toString from predicate")
		void shouldProduceNonEmptyToString() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertNotNull(formula.toString());
			assertFalse(formula.toString().isEmpty());
		}

		@Test
		@DisplayName("should return empty result when inner formula produces empty bitmap")
		void shouldReturnEmptyResultWhenInnerFormulaProducesEmptyBitmap() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertTrue(formula.compute().isEmpty());
			assertNotNull(formula.getRecordsFilteredOutByPredicate());
			assertTrue(formula.getRecordsFilteredOutByPredicate().isEmpty());
		}

		@Test
		@DisplayName("should store WITH_TAX query price mode")
		void shouldStoreWithTaxQueryPriceMode() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(QueryPriceMode.WITH_TAX, formula.getQueryPriceMode());
		}

		@Test
		@DisplayName("should store WITHOUT_TAX query price mode")
		void shouldStoreWithoutTaxQueryPriceMode() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITHOUT_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(QueryPriceMode.WITHOUT_TAX, formula.getQueryPriceMode());
		}
	}

	/**
	 * Creates a {@link PriceEvaluationContext} with a single price index key.
	 *
	 * @param priceList the price list name
	 * @param currency  the currency
	 * @return configured price evaluation context
	 */
	@Nonnull
	private static PriceEvaluationContext createContext(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) {
		return new PriceEvaluationContext(
			null,
			new PriceIndexKey(priceList, currency, PriceInnerRecordHandling.NONE)
		);
	}
}
