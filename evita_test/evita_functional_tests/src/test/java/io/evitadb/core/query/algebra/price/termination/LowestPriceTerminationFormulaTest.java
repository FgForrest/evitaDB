/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LowestPriceTerminationFormula} verifying construction, identity, cloning,
 * cardinality estimation and the selection of the lowest price among inner records.
 *
 * The selection is computed over real price indexes wrapped in
 * {@link io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula}, which provide the price records
 * per entity; the remaining contract tests need no price record resolution at all.
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

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);

			assertEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different evaluation context")
		void shouldProduceDifferentKeyForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("vip", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different selling price predicate")
		void shouldProduceDifferentKeyForDifferentSellingPricePredicate() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));
			final PriceEvaluationContext context = createContext("basic", CZK);

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final PriceRecordPredicate rangePredicate = new PricePredicate.PriceRecordPredicate(
				BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, rangePredicate, false
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different inner formulas")
		void shouldProduceDifferentKeyForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createContext("basic", CZK);

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(3, 4)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Verifies detached computation identity. */
		@Test
		@DisplayName("should produce different key for different currency")
		void shouldProduceDifferentKeyForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("basic", EUR),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);

			assertNotEquals(f1.getKey(), f2.getKey());
		}

		/** Price mode changes selling-price selection even with an unrestricted selling-price predicate. */
		@Test
		@DisplayName("Distinguishes query price modes with an unrestricted selling-price predicate")
		void shouldProduceDifferentIdentityWhenPriceModeSelectsDifferentSellingPrice() {
			final PriceIndexKey priceIndexKey = new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.LOWEST_PRICE);
			final PriceListAndCurrencyPriceSuperIndex priceIndex =
				new PriceListAndCurrencyPriceSuperIndex(priceIndexKey);
			final PriceRecordContract cheaperWithoutTax = new PriceRecordInnerRecordSpecific(1, 1, 1, 1, 12100, 10000);
			final PriceRecordContract cheaperWithTax = new PriceRecordInnerRecordSpecific(2, 2, 1, 2, 11000, 10500);
			priceIndex.addPrice(cheaperWithoutTax, null);
			priceIndex.addPrice(cheaperWithTax, null);
			final PriceIndexContainerFormula container = new PriceIndexContainerFormula(
				priceIndex, priceIndex, priceIndex.getIndexedPriceEntityIdsFormula()
			);
			final PriceEvaluationContext context = new PriceEvaluationContext(null, priceIndexKey);

			final LowestPriceTerminationFormula withTax = new LowestPriceTerminationFormula(
				container, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula withoutTax = new LowestPriceTerminationFormula(
				container, context, QueryPriceMode.WITHOUT_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			withTax.compute();
			withoutTax.compute();

			// the price records are resolved without an execution context, as `compute()` itself resolves them
			assertArrayEquals(
				new PriceRecordContract[]{cheaperWithTax},
				((ResolvedFilteredPriceRecords) withTax.getFilteredPriceRecords(null)).getPriceRecords()
			);
			assertArrayEquals(
				new PriceRecordContract[]{cheaperWithoutTax},
				((ResolvedFilteredPriceRecords) withoutTax.getFilteredPriceRecords(null)).getPriceRecords()
			);
			assertNotEquals(
				withTax.getKey(),
				withoutTax.getKey()
			);
			assertNotEquals(withTax.getKey().getHash64(), withoutTax.getKey().getHash64());
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
			final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITHOUT_TAX, predicate, false
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(10, 20));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertEquals(
				new LowestPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITHOUT_TAX, predicate, false
				).getKey(),
				typedClone.getKey()
			);
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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
			final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITH_TAX, predicate, false
			);

			final ConstantFormula newInner = new ConstantFormula(new ArrayBitmap(5, 6));
			final Formula clone = original.getCloneWithInnerFormulas(newInner);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertEquals(
				new LowestPriceTerminationFormula(
					newInner, context, QueryPriceMode.WITH_TAX, predicate, false
				).getKey(),
				typedClone.getKey()
			);
			assertEquals(QueryPriceMode.WITH_TAX, typedClone.getQueryPriceMode());
			assertSame(predicate, typedClone.getSellingPricePredicate());
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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
				QueryPriceMode.WITH_TAX, rangePredicate, false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
			);

			assertTrue(formula.compute().isEmpty());
		}

		@Test
		@DisplayName("should store WITH_TAX query price mode")
		void shouldStoreWithTaxQueryPriceMode() {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
			);

			assertEquals(QueryPriceMode.WITHOUT_TAX, formula.getQueryPriceMode());
		}
	}

	@Nested
	@DisplayName("Histogram side-output (per-inner-record price collection)")
	@Tag(HISTOGRAM)
	class HistogramSideOutputTest {

		@Test
		@DisplayName("should expose the per-inner-record collection flag set at construction")
		void shouldExposeConstructionTimeFlag() {
			final LowestPriceTerminationFormula withoutHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
			);
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			assertFalse(withoutHistogram.isCollectingPerInnerRecordPrices());
			assertTrue(withHistogram.isCollectingPerInnerRecordPrices());
		}

		/** Includes the histogram collection requirement in detached identity. */
		@Test
		@DisplayName("should produce different keys when per-inner-record collection differs")
		void shouldProduceDifferentKeyWhenPerInnerRecordCollectionDiffers() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;

			final LowestPriceTerminationFormula without = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);
			final LowestPriceTerminationFormula with = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, true
			);

			// Formula identity distinguishes the presence of the per-inner-record histogram side-output.
			assertNotEquals(without.getKey(), with.getKey());
		}

		@Test
		@DisplayName("should propagate the flag through cloning with new inner formulas")
		void shouldPropagateFlagThroughCloneWithInnerFormulas() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			final Formula clone = withHistogram.getCloneWithInnerFormulas(
				new ConstantFormula(new ArrayBitmap(10, 20))
			);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertTrue(typedClone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should propagate the flag through individual price predicate factory cloning")
		void shouldPropagateFlagThroughIndividualPricePredicateFactoryCloning() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			final LowestPriceTerminationFormula clone = withHistogram.withIndividualPricePredicateFactory(
				() -> price -> true
			);

			assertTrue(clone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should propagate the flag through inner-formula cloning")
		void shouldPropagateFlagThroughInnerFormulaCloning() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			final Formula clone = withHistogram.getCloneWithInnerFormulas(new ConstantFormula(new ArrayBitmap(5, 6)));

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertTrue(typedClone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should throw GenericEvitaInternalError when histogram records requested but flag is off")
		void shouldThrowWhenHistogramRecordsRequestedAndFlagOff() {
			final LowestPriceTerminationFormula withoutHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
			);

			// defensive design: asking for the histogram side-output on an LP that was constructed without
			// the flag is a programming error and must fail loudly
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> withoutHistogram.getFilteredPriceRecordsForHistogram(null)
			);
			assertNotNull(ex.getMessage());
		}

		@Test
		@DisplayName("should expose empty histogram records when flag is on and input is empty")
		void shouldExposeEmptyHistogramRecordsWhenInputIsEmpty() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			// trigger compute() — empty-input branch must still publish a non-null FilteredPriceRecords
			withHistogram.compute();

			assertNotNull(withHistogram.getFilteredPriceRecordsForHistogram(null));
		}

		/**
		 * Empty-input computation exposes an independently owned resolved set with no histogram data points.
		 */
		@Test
		@DisplayName("should return fresh empty records for empty input")
		void shouldReturnFreshEmptyRecordsForEmptyInput() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			withHistogram.compute();

			final FilteredPriceRecords histogramRecords = withHistogram.getFilteredPriceRecordsForHistogram(null);
			// The histogram side-output belongs to this formula instance.
			assertNotSame(ResolvedFilteredPriceRecords.EMPTY, histogramRecords);
			// the freshly allocated records must still be empty so downstream consumers see no data points
			assertInstanceOf(ResolvedFilteredPriceRecords.class, histogramRecords);
			assertEquals(0, ((ResolvedFilteredPriceRecords) histogramRecords).getPriceRecords().length);
		}

		/**
		 * The operation cost reported by the LP must be independent of the histogram side-output flag —
		 * the planner uses it to compare alternative plans, so any per-flag divergence would change plan
		 * choice when the user toggles `priceHistogram(...)` between sibling queries. The actual
		 * histogram collection cost is amortised through the side-output funnel and reflected in the
		 * estimated cost, not the per-element operation cost.
		 */
		@Test
		@DisplayName("should report identical operation cost regardless of histogram flag")
		void shouldReportIdenticalOperationCostRegardlessOfHistogramFlag() {
			final LowestPriceTerminationFormula withoutHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
			);
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			// cost is a planner input — flipping the histogram flag must NOT shift the relative cost of
			// alternative plans, otherwise the query planner would pick different plans for histogram vs
			// non-histogram queries with identical filters
			assertEquals(withoutHistogram.getOperationCost(), withHistogram.getOperationCost());
		}

		@Test
		@DisplayName("should report histogram capability matching the construction-time flag")
		void shouldReportHistogramCapabilityMatchingPerInnerRecordFlag() {
			final LowestPriceTerminationFormula withoutHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
			);
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			// the capability probe is the single source of truth read by `PriceHistogramComputer` —
			// it MUST follow the construction-time `collectPerInnerRecordPrices` flag verbatim
			assertFalse(withoutHistogram.exposesPerInnerRecordHistogramRecords());
			assertTrue(withHistogram.exposesPerInnerRecordHistogramRecords());
		}
	}

	@Nested
	@DisplayName("Selection of the lowest price among inner records")
	class LowestPriceSelectionTest {

		/**
		 * Number of independent computations, each over a freshly built index, container and formula.
		 */
		private static final int REPETITIONS = 200;

		/**
		 * The two inner records of the entity cost the same with tax, the price mode of the computation. Every
		 * computation of the very same input must select the record with the lower price id - the record selected here
		 * is what the discount sorting pairs the reference price with.
		 */
		@Test
		@DisplayName("should select the lower price id every time when inner records tie on the selling price")
		void shouldSelectLowerPriceIdEveryTimeWhenInnerRecordsTieOnSellingPrice() {
			final Map<Integer, Integer> selectionCounts = computeSelectedPriceIds(12100);

			assertEquals(
				Map.of(1, REPETITIONS), selectionCounts,
				"Computations of the same input selected different price records (price id -> times selected): " +
					selectionCounts
			);
		}

		/**
		 * Same fixture as the tied case with the selling prices one cent apart - the selection is decided by the
		 * amount alone and must be stable, which pins any instability of the tied case on the tie itself.
		 */
		@Test
		@DisplayName("should select the same price record every time when inner records differ in the selling price")
		void shouldSelectSamePriceRecordEveryTimeWhenInnerRecordsDifferInSellingPrice() {
			final Map<Integer, Integer> selectionCounts = computeSelectedPriceIds(12101);

			assertEquals(
				Map.of(1, REPETITIONS), selectionCounts,
				"Computations of the same input selected different price records (price id -> times selected): " +
					selectionCounts
			);
		}

		/**
		 * The two inner records tie on the price with tax and differ without tax. The record that is cheaper without
		 * tax carries the higher price id, so the tie-break decides only in the mode where the amounts tie: with tax
		 * the lower price id wins - although its record is added second and belongs to the higher inner record id -
		 * and without tax the cheaper record wins despite its higher price id.
		 */
		@Test
		@DisplayName("should select the lower price id among records tied with tax and the cheaper one without tax")
		void shouldSelectLowerPriceIdWhenTiedWithTaxAndCheaperRecordWithoutTax() {
			final PriceRecordContract cheaperWithoutTax = new PriceRecordInnerRecordSpecific(1, 2, 1, 1, 12100, 10000);
			final PriceRecordContract lowerPriceId = new PriceRecordInnerRecordSpecific(2, 1, 1, 2, 12100, 11000);

			assertEquals(
				Map.of(lowerPriceId.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITH_TAX, cheaperWithoutTax, lowerPriceId)
			);
			assertEquals(
				Map.of(cheaperWithoutTax.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITHOUT_TAX, cheaperWithoutTax, lowerPriceId)
			);
		}

		/**
		 * The mirror image of the previous case: the inner records tie on the price without tax and differ with tax,
		 * the record that is cheaper with tax carrying the higher price id.
		 */
		@Test
		@DisplayName("should select the lower price id among records tied without tax and the cheaper one with tax")
		void shouldSelectLowerPriceIdWhenTiedWithoutTaxAndCheaperRecordWithTax() {
			final PriceRecordContract cheaperWithTax = new PriceRecordInnerRecordSpecific(1, 2, 1, 1, 11000, 10000);
			final PriceRecordContract lowerPriceId = new PriceRecordInnerRecordSpecific(2, 1, 1, 2, 12100, 10000);

			assertEquals(
				Map.of(lowerPriceId.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITHOUT_TAX, cheaperWithTax, lowerPriceId)
			);
			assertEquals(
				Map.of(cheaperWithTax.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITH_TAX, cheaperWithTax, lowerPriceId)
			);
		}

		/**
		 * A price id is unique only within a price list, so the selling prices of two inner records found in two
		 * different price lists may share it. When they also tie on the amount, the lower inner record id wins - here
		 * the record of the less prioritized price list.
		 */
		@Test
		@DisplayName("should select the lower inner record id among tied records sharing the price id")
		void shouldSelectLowerInnerRecordIdWhenTiedRecordsSharePriceId() {
			final Map<Integer, Integer> selectionCounts = new TreeMap<>();
			for (int i = 0; i < REPETITIONS; i++) {
				final PriceIndexContainerFormula basic = createContainer(
					"basic", new PriceRecordInnerRecordSpecific(1, 5, 1, 2, 12100, 10000)
				);
				final PriceIndexContainerFormula secondary = createContainer(
					"secondary", new PriceRecordInnerRecordSpecific(2, 5, 1, 1, 12100, 10000)
				);
				final PriceRecordContract selected = computeSelectedPriceRecord(
					new OrFormula(basic, secondary), QueryPriceMode.WITH_TAX
				);
				selectionCounts.merge(selected.innerRecordId(), 1, Integer::sum);
			}

			assertEquals(
				Map.of(1, REPETITIONS), selectionCounts,
				"Computations of the same input selected different inner records " +
					"(inner record id -> times selected): " + selectionCounts
			);
		}

		/**
		 * Inner record 1 has two prices in the very same price list - possible when their validities do not overlap
		 * and the query names no moment - and every price of the entity sells for the same amount in both price modes.
		 * The price of inner record 1 indexed first holds the lower internal price id but the highest price id. The
		 * entity side represents inner record 1 by its lower price id and then lets it win the tie against inner record
		 * 2 by that very price id, so the lowest price id must survive the selection within inner record 1 as well -
		 * a selection by internal price id there would hand the entity to inner record 2.
		 */
		@Test
		@DisplayName("should select the lowest price id among tied prices of one inner record and of another one")
		void shouldSelectLowestPriceIdAmongTiedPricesOfOneInnerRecordAndOfAnotherOne() {
			final PriceRecordContract indexedFirst = new PriceRecordInnerRecordSpecific(1, 30, 1, 1, 10000, 10000);
			final PriceRecordContract lowestPriceId = new PriceRecordInnerRecordSpecific(2, 10, 1, 1, 10000, 10000);
			final PriceRecordContract otherInnerRecord = new PriceRecordInnerRecordSpecific(3, 20, 1, 2, 10000, 10000);

			assertEquals(
				Map.of(lowestPriceId.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITH_TAX, indexedFirst, lowestPriceId, otherInnerRecord)
			);
			assertEquals(
				Map.of(lowestPriceId.priceId(), REPETITIONS),
				computeSelectedPriceIds(QueryPriceMode.WITHOUT_TAX, indexedFirst, lowestPriceId, otherInnerRecord)
			);
		}

		/**
		 * Computes {@link #REPETITIONS} fresh lowest price terminations over an entity with two inner records priced
		 * in the `basic` price list with tax and counts which price record each of them selected for the entity.
		 *
		 * @param secondPriceWithTax price with tax of the second inner record, the first one costs `12100`
		 * @return price id of the selected price record mapped to the number of computations that selected it
		 */
		@Nonnull
		private static Map<Integer, Integer> computeSelectedPriceIds(int secondPriceWithTax) {
			return computeSelectedPriceIds(
				QueryPriceMode.WITH_TAX,
				new PriceRecordInnerRecordSpecific(1, 1, 1, 1, 12100, 10000),
				new PriceRecordInnerRecordSpecific(2, 2, 1, 2, secondPriceWithTax, 11000)
			);
		}

		/**
		 * Computes {@link #REPETITIONS} fresh lowest price terminations, each over a freshly built `basic` price index
		 * holding the passed records, and counts which price record each of them selected for the entity.
		 *
		 * @param queryPriceMode price mode the terminations compare the amounts in
		 * @param priceRecords   price records of a single entity, added to the index in the passed order
		 * @return price id of the selected price record mapped to the number of computations that selected it
		 */
		@Nonnull
		private static Map<Integer, Integer> computeSelectedPriceIds(
			@Nonnull QueryPriceMode queryPriceMode,
			@Nonnull PriceRecordContract... priceRecords
		) {
			final Map<Integer, Integer> selectionCounts = new TreeMap<>();
			for (int i = 0; i < REPETITIONS; i++) {
				final PriceRecordContract selected = computeSelectedPriceRecord(
					createContainer("basic", priceRecords), queryPriceMode
				);
				selectionCounts.merge(selected.priceId(), 1, Integer::sum);
			}
			return selectionCounts;
		}

		/**
		 * Creates a container formula over a fresh price index of the price list in CZK holding the passed records.
		 *
		 * @param priceList    price list of the index
		 * @param priceRecords price records added to the index in the passed order
		 * @return container formula providing the records of the index to the termination formula
		 */
		@Nonnull
		private static PriceIndexContainerFormula createContainer(
			@Nonnull String priceList,
			@Nonnull PriceRecordContract... priceRecords
		) {
			final PriceListAndCurrencyPriceSuperIndex priceIndex = new PriceListAndCurrencyPriceSuperIndex(
				new PriceIndexKey(priceList, CZK, PriceInnerRecordHandling.LOWEST_PRICE)
			);
			for (final PriceRecordContract priceRecord : priceRecords) {
				priceIndex.addPrice(priceRecord, null);
			}
			return new PriceIndexContainerFormula(priceIndex, priceIndex, priceIndex.getIndexedPriceEntityIdsFormula());
		}

		/**
		 * Computes a fresh lowest price termination over the passed container formula and returns the price record it
		 * selected for the single entity the container holds.
		 *
		 * @param containerFormula formula providing the price records of the entity
		 * @param queryPriceMode   price mode the termination compares the amounts in
		 * @return the selected price record
		 */
		@Nonnull
		private static PriceRecordContract computeSelectedPriceRecord(
			@Nonnull Formula containerFormula,
			@Nonnull QueryPriceMode queryPriceMode
		) {
			final LowestPriceTerminationFormula formula = new LowestPriceTerminationFormula(
				containerFormula,
				new PriceEvaluationContext(
					null, new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.LOWEST_PRICE)
				),
				queryPriceMode, PricePredicate.ALL_RECORD_FILTER, false
			);
			formula.compute();

			final PriceRecordContract[] selected =
				((ResolvedFilteredPriceRecords) formula.getFilteredPriceRecords(null)).getPriceRecords();
			assertEquals(1, selected.length);
			return selected[0];
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
