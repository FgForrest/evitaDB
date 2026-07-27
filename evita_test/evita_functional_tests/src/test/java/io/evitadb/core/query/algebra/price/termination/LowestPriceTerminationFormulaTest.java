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

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceRecordPredicate;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

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
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);

			assertEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different evaluation context")
		void shouldProduceDifferentHashForDifferentEvaluationContext() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("vip", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different selling price predicate")
		void shouldProduceDifferentHashForDifferentSellingPricePredicate() {
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

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different inner formulas")
		void shouldProduceDifferentHashForDifferentInnerFormulas() {
			final PriceEvaluationContext context = createContext("basic", CZK);

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(3, 4)), context,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);

			assertNotEquals(f1.getHash(), f2.getHash());
		}

		@Test
		@DisplayName("should produce different hash for different currency")
		void shouldProduceDifferentHashForDifferentCurrency() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2));

			final LowestPriceTerminationFormula f1 = new LowestPriceTerminationFormula(
				inner, createContext("basic", CZK),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
			);
			final LowestPriceTerminationFormula f2 = new LowestPriceTerminationFormula(
				inner, createContext("basic", EUR),
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, false
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
				QueryPriceMode.WITHOUT_TAX, predicate, false
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

		@Test
		@DisplayName("should create clone with computation callback preserving all config")
		void shouldCreateCloneWithComputationCallbackPreservingAllConfig() {
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;
			final LowestPriceTerminationFormula original = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)), context,
				QueryPriceMode.WITH_TAX, predicate, false
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
				PricePredicate.ALL_RECORD_FILTER,
				false
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

		@Test
		@DisplayName("should produce different hash when per-inner-record collection differs")
		void shouldProduceDifferentHashWhenPerInnerRecordCollectionDiffers() {
			final ConstantFormula inner = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final PriceEvaluationContext context = createContext("basic", CZK);
			final PriceRecordPredicate predicate = PricePredicate.ALL_RECORD_FILTER;

			final LowestPriceTerminationFormula without = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, false
			);
			final LowestPriceTerminationFormula with = new LowestPriceTerminationFormula(
				inner, context, QueryPriceMode.WITH_TAX, predicate, true
			);

			// cache isolation acceptance criterion — the histogram-enabled formula uses a different
			// flattened payload class on cache hit, so its hash MUST diverge to avoid feeding the wrong
			// cached entry back to the caller (a.k.a. "cache poisoning")
			assertNotEquals(without.getHash(), with.getHash());
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
		@DisplayName("should propagate the flag through individual price predicate cloning")
		void shouldPropagateFlagThroughIndividualPricePredicateCloning() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			final LowestPriceTerminationFormula clone = withHistogram.withIndividualPricePredicate(price -> true);

			assertTrue(clone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should propagate the flag through computation callback cloning")
		void shouldPropagateFlagThroughComputationCallbackCloning() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			final CacheableFormula clone = withHistogram.getCloneWithComputationCallback(
				f -> { /* no-op callback */ },
				new ConstantFormula(new ArrayBitmap(5, 6))
			);

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertTrue(typedClone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should propagate the flag through price predicate filtered out results cloning")
		void shouldPropagateFlagThroughFilteredOutResultsCloning() {
			final LowestPriceTerminationFormula withHistogram = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				true
			);

			// trigger computation on empty input — populates filtered out records bitmap so that
			// getCloneWithPricePredicateFilteredOutResults() can be invoked legitimately
			withHistogram.compute();

			final Formula clone = withHistogram.getCloneWithPricePredicateFilteredOutResults();

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			// this is the path most likely to drop the flag silently — every cloning factory must carry it
			assertTrue(typedClone.isCollectingPerInnerRecordPrices());
		}

		@Test
		@DisplayName("should propagate cleared flag through price predicate filtered out results cloning")
		void shouldPropagateClearedFlagThroughFilteredOutResultsCloning() {
			// regression sibling — when the flag is OFF on the source it must stay OFF on the clone too,
			// otherwise non-histogram queries would start paying the per-inner-record cost on cache miss
			final LowestPriceTerminationFormula withoutHistogram = new LowestPriceTerminationFormula(
				EmptyFormula.INSTANCE,
				createContext("basic", CZK),
				QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER,
				false
			);

			withoutHistogram.compute();

			final Formula clone = withoutHistogram.getCloneWithPricePredicateFilteredOutResults();

			assertInstanceOf(LowestPriceTerminationFormula.class, clone);
			final LowestPriceTerminationFormula typedClone = (LowestPriceTerminationFormula) clone;
			assertFalse(typedClone.isCollectingPerInnerRecordPrices());
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
		 * The empty-input branch in `LowestPriceTerminationFormula.computeInternal()` must allocate a
		 * fresh `ResolvedFilteredPriceRecords` rather than alias the shared `FilteredPriceRecords.EMPTY`
		 * singleton — otherwise concurrent histogram queries against empty inputs would race when the
		 * cache writer thread invokes `prepareForFlattening()` on the shared instance.
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
			// per-instance allocation prevents the singleton-leak race — the histogram side-output must
			// never alias the shared FilteredPriceRecords.EMPTY
			assertNotSame(FilteredPriceRecords.EMPTY, histogramRecords);
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
