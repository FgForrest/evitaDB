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

import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
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
 * Tests for {@link SumPriceTerminationFormula} verifying construction, hashing, cloning
 * and cardinality estimation.
 *
 * @author evitaDB
 */
@DisplayName("SumPriceTerminationFormula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class SumPriceTerminationFormulaTest {

	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");

	/**
	 * Verifies detached computation identity.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Key determinism and sensitivity")
	class KeyTest {

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce identical key for identically constructed formulas")
		void shouldProduceIdenticalKeyForIdenticalFormulas() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);

			assertEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different evaluation context")
		void shouldProduceDifferentKeyForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, createContext("vip", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different selling price predicate")
		void shouldProduceDifferentKeyForDifferentSellingPricePredicate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PriceEvaluationContext context = createContext("basic", CZK);

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final PriceRecordPredicate rangePredicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, rangePredicate
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different inner formulas")
		void shouldProduceDifferentKeyForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createContext("basic", CZK);

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(3, 4)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different currency")
		void shouldProduceDifferentKeyForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, createContext("basic", EUR),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/**
		 * The query price mode decides which total the cumulated selling price carries - the sum of prices with tax
		 * or the sum of prices without it - even with an unrestricted selling-price predicate, so it is a key input.
		 */
		@Test
		@DisplayName("Distinguishes query price modes whose cumulated prices carry different totals")
		void shouldProduceDifferentIdentityWhenPriceModeSumsDifferentTotal() {
			final PriceIndexKey priceIndexKey = new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.SUM);
			final PriceListAndCurrencyPriceSuperIndex priceIndex =
				new PriceListAndCurrencyPriceSuperIndex(priceIndexKey);
			priceIndex.addPrice(new PriceRecordInnerRecordSpecific(1, 1, 1, 1, 12100, 10000), null);
			priceIndex.addPrice(new PriceRecordInnerRecordSpecific(2, 2, 1, 2, 11000, 10500), null);
			final PriceIndexContainerFormula container = new PriceIndexContainerFormula(
				priceIndex, priceIndex, priceIndex.getIndexedPriceEntityIdsFormula()
			);
			final PriceEvaluationContext context = new PriceEvaluationContext(null, priceIndexKey);

			final SumPriceTerminationFormula withTax = new SumPriceTerminationFormula(
				container, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula withoutTax = new SumPriceTerminationFormula(
				container, context, QueryPriceMode.WITHOUT_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			withTax.compute();
			withoutTax.compute();

			// the price records are resolved without an execution context, as `compute()` itself resolves them
			final PriceRecordContract[] withTaxPrices =
				((ResolvedFilteredPriceRecords) withTax.getFilteredPriceRecords(null)).getPriceRecords();
			final PriceRecordContract[] withoutTaxPrices =
				((ResolvedFilteredPriceRecords) withoutTax.getFilteredPriceRecords(null)).getPriceRecords();
			assertEquals(1, withTaxPrices.length);
			assertEquals(1, withoutTaxPrices.length);
			// a cumulated record carries only the total of its own mode, the other one reads as zero
			assertEquals(12100 + 11000, withTaxPrices[0].priceWithTax());
			assertEquals(0, withTaxPrices[0].priceWithoutTax());
			assertEquals(0, withoutTaxPrices[0].priceWithTax());
			assertEquals(10000 + 10500, withoutTaxPrices[0].priceWithoutTax());
			assertNotEquals(
				withTax.getKey(),
				withoutTax.getKey()
			);
			assertNotEquals(withTax.getKey().getHash64(), withoutTax.getKey().getHash64());
		}
	}

	/**
	 * Preserves the hash contract used by the formula deduplicator.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Legacy hash determinism and sensitivity")
	class LegacyHashTest {

		@Test
		@DisplayName("should produce identical hash for identically constructed formulas")
		void shouldProduceIdenticalHashForIdenticalFormulas() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate
			);

			assertEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different evaluation context")
		void shouldProduceDifferentHashForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
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

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final PriceRecordPredicate rangePredicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, rangePredicate
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createContext("basic", CZK);

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(3, 4)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different currency")
		void shouldProduceDifferentHashForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final SumPriceTerminationFormula f1 = new SumPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);
			final SumPriceTerminationFormula f2 = new SumPriceTerminationFormula(
				inner, createContext("basic", EUR),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		/** Verifies that cloning retains the declared computation inputs. */
		@Test
		@DisplayName("should preserve context, price mode, and predicate when cloning")
		void shouldPreserveAllConfigWhenCloning() {
			final PriceEvaluationContext context = createContext("vip", EUR);
			final PriceRecordPredicate predicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2
			);
			final SumPriceTerminationFormula original = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITHOUT_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(SumPriceTerminationFormula.class, clone);
			final SumPriceTerminationFormula typedClone = (SumPriceTerminationFormula) clone;
			assertEquals(
				new SumPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITHOUT_TAX, predicate
				).getKey(),
				typedClone.getKey()
			);
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}

		@Test
		@DisplayName("should throw when cloning with wrong number of inner formulas")
		void shouldThrowWhenCloningWithWrongNumberOfInnerFormulas() {
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
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

		/** Verifies that cloning retains the declared computation inputs. */
		@Test
		@DisplayName("should create clone with inner formulas preserving all config")
		void shouldCreateCloneWithInnerFormulasPreservingAllConfig() {
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;
			final SumPriceTerminationFormula original = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITH_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(5, 6));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(SumPriceTerminationFormula.class, clone);
			final SumPriceTerminationFormula typedClone = (SumPriceTerminationFormula) clone;
			assertEquals(
				new SumPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITH_TAX, predicate
				).getKey(),
				typedClone.getKey()
			);
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}
	}

	@Nested
	@DisplayName("Cardinality and cost")
	class CardinalityAndCostTest {

		@Test
		@DisplayName("should delegate estimated cardinality to inner formula")
		void shouldDelegateEstimatedCardinalityToInnerFormula() {
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(5, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should report operation cost of 18077")
		void shouldReportOperationCostOf18077() {
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertEquals(18077, formula.getOperationCost());
		}
	}

	@Nested
	@DisplayName("Contract details")
	class ContractTest {

		@Test
		@DisplayName("should return delegate formula via getDelegate")
		void shouldReturnDelegateFormulaViaGetDelegate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER
			);

			assertSame(inner, formula.getDelegate());
		}

		@Test
		@DisplayName("should return exactly one inner formula")
		void shouldReturnExactlyOneInnerFormula() {
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
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
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, rangePredicate
			);

			assertNotNull(formula.getRequestedPredicate());
		}

		@Test
		@DisplayName("should produce non-empty toString from predicate")
		void shouldProduceNonEmptyToString() {
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
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
			final SumPriceTerminationFormula formula = new SumPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER
			);

			assertTrue(formula.compute().isEmpty());
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

	/**
	 * Preserves legacy hash assertions for cloning and side-output configuration.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Legacy hash cloning and configuration")
	class LegacyHashCloneTest {
		@Test
		@DisplayName("should preserve context, price mode, and predicate when cloning")
		void shouldPreserveAllConfigWhenCloning() {
			final PriceEvaluationContext context = createContext("vip", EUR);
			final PriceRecordPredicate predicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2
			);
			final SumPriceTerminationFormula original = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITHOUT_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(SumPriceTerminationFormula.class, clone);
			final SumPriceTerminationFormula typedClone = (SumPriceTerminationFormula) clone;
			assertEquals(
				new SumPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITHOUT_TAX, predicate
				).getHash(),
				typedClone.getHash()
			);
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}

		@Test
		@DisplayName("should create clone with inner formulas preserving all config")
		void shouldCreateCloneWithInnerFormulasPreservingAllConfig() {
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;
			final SumPriceTerminationFormula original = new SumPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITH_TAX, predicate
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(5, 6));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(SumPriceTerminationFormula.class, clone);
			final SumPriceTerminationFormula typedClone = (SumPriceTerminationFormula) clone;
			assertEquals(
				new SumPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITH_TAX, predicate
				).getHash(),
				typedClone.getHash()
			);
			assertSame(predicate, typedClone.getSellingPricePredicate());
		}
	}

}
