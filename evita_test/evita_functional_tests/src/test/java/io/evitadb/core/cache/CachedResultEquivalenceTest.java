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

package io.evitadb.core.cache;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.*;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.*;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.*;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.core.query.extraResult.translator.histogram.producer.*;
import io.evitadb.index.bitmap.*;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

import static io.evitadb.core.cache.CachedResultEquivalence.equivalent;
import static io.evitadb.core.cache.CachedResultTestSupport.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises semantic tuple comparison and independently computed payload determinism.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cached result equivalence")
@Tag(ENGINE)
@Tag(CACHE)
class CachedResultEquivalenceTest {
	/** Checks all representation pairs, including opposite signed and unsigned iteration orders. */
	@Test
	@DisplayName("Compares bitmap membership across representations with negative ids")
	void shouldCompareBitmapMembership() {
		final Bitmap[] bitmaps = {
			new BaseBitmap(-5, -1, 0, 7), new ArrayBitmap(-5, -1, 0, 7),
			new SortedArrayBitmap(0, 7, -5, -1), new TransactionalBitmap(new BaseBitmap(-5, -1, 0, 7))
		};
		for (final Bitmap left : bitmaps) {
			for (final Bitmap right : bitmaps) {
				assertTrue(equivalent(new CachedBitmapResult(left, 1), new CachedBitmapResult(right, 2)));
			}
			assertFalse(equivalent(new CachedBitmapResult(left, 1), new CachedBitmapResult(new BaseBitmap(-5), 1)));
			assertFalse(equivalent(new CachedBitmapResult(left, 1),
				new CachedBitmapResult(new BaseBitmap(-5, -1, 0, 8), 1)));
		}
		final Bitmap[] empty = {EmptyBitmap.INSTANCE, new BaseBitmap(), new ArrayBitmap(),
			new SortedArrayBitmap(), new TransactionalBitmap(new BaseBitmap())};
		for (final Bitmap left : empty) {
			for (final Bitmap right : empty) {
				assertTrue(equivalent(new CachedBitmapResult(left, 1), new CachedBitmapResult(right, 1)));
			}
		}
	}

	/** Checks both tuple shapes and argument orders when duplicate entries hide a missing identifier. */
	@Test
	@DisplayName("Rejects unequal bitmap membership even when duplicates preserve the reported size")
	void shouldRejectMissingMembershipWhenBitmapContainsDuplicates() {
		final Bitmap duplicates = new ArrayBitmap(1, 1);
		final Bitmap distinct = new BaseBitmap(1, 2);
		assertEquals(duplicates.size(), distinct.size());
		final CachedBitmapResult first = new CachedBitmapResult(duplicates, 1);
		final CachedBitmapResult second = new CachedBitmapResult(distinct, 1);
		final CachedPriceRecordsResult firstPrices = new CachedPriceRecordsResult(duplicates, records(), null, 1);
		final CachedPriceRecordsResult secondPrices = new CachedPriceRecordsResult(distinct, records(), null, 1);
		assertAll(
			() -> assertFalse(equivalent(first, second)),
			() -> assertFalse(equivalent(second, first)),
			() -> assertFalse(equivalent(firstPrices, secondPrices)),
			() -> assertFalse(equivalent(secondPrices, firstPrices)),
			() -> assertTrue(equivalent(first, new CachedBitmapResult(new ArrayBitmap(1, 1), 2)))
		);
	}

	/** Moves every plain and inner-record tuple component independently, keeping identity equality misleading. */
	@Test
	@DisplayName("Detects every physical price tuple component")
	void shouldCompareEveryPriceComponent() {
		final PriceRecord base = new PriceRecord(1, 2, 10, 120, 100);
		for (final PriceRecordContract changed : new PriceRecordContract[]{
			new PriceRecord(3, 2, 10, 120, 100), new PriceRecord(1, 3, 10, 120, 100),
			new PriceRecord(1, 2, 11, 120, 100), new PriceRecord(1, 2, 10, 121, 100),
			new PriceRecord(1, 2, 10, 120, 101), new PriceRecordInnerRecordSpecific(1, 2, 10, 0, 120, 100)
		}) {
			assertFalse(equivalent(prices(base), prices(changed)));
		}
		assertTrue(base.equals(new PriceRecord(1, 2, 10, 999, 999)));
		assertTrue(equivalent(prices(base), prices(new PriceRecord(1, 2, 10, 120, 100))));
		assertFalse(equivalent(prices(new PriceRecordInnerRecordSpecific(1, 2, 10, 3, 120, 100)),
			prices(new PriceRecordInnerRecordSpecific(1, 2, 10, 4, 120, 100))));
	}

	/** Deterministic selling-price order survives publication and remains part of the verified tuple. */
	@Test
	@Tag(PRICE)
	@DisplayName("Compares selling-price records in their within-entity order")
	void shouldCompareOrderedSellingPrices() {
		final PriceRecord first = new PriceRecord(1, 1, 10, 120, 100);
		final PriceRecord second = new PriceRecord(2, 2, 10, 240, 200);
		final PriceRecord third = new PriceRecord(3, 3, 20, 360, 300);
		assertFalse(equivalent(prices(first, second, third), prices(third, second, first)));
		assertTrue(equivalent(prices(first, second, third), prices(third, first, second)));
		assertFalse(equivalent(prices(first, first, second), prices(first, second, second)));
		assertFalse(equivalent(prices(first), prices(first, second)));
		assertFalse(equivalent(prices(first, second, third), prices(first, third, third)));
		assertTrue(equivalent(prices(), prices()));
	}

	/** Only the histogram side-output may ignore order; every field and duplicate multiplicity still matters. */
	@Test
	@Tag(PRICE)
	@DisplayName("Compares histogram-only records as full-tuple multisets")
	void shouldCompareHistogramPriceMultisets() {
		final PriceRecord first = new PriceRecord(1, 1, 10, 120, 100);
		final PriceRecord second = new PriceRecord(2, 2, 10, 240, 200);
		final PriceRecord third = new PriceRecord(3, 3, 20, 360, 300);
		assertTrue(equivalent(histogramPrices(first, second, third), histogramPrices(third, second, first)));
		assertFalse(equivalent(histogramPrices(first, first, second), histogramPrices(first, second, second)));
		assertFalse(equivalent(histogramPrices(first), histogramPrices(first, second)));
		assertFalse(equivalent(histogramPrices(first, second, third), histogramPrices(first, third, third)));
		assertFalse(equivalent(histogramPrices(first),
			histogramPrices(new PriceRecord(1, 1, 10, 121, 100))));
		assertTrue(equivalent(histogramPrices(), histogramPrices()));
	}

	/** Reordering the histogram-only side-output cannot change any histogram behavior's buckets. */
	@ParameterizedTest
	@EnumSource(HistogramBehavior.class)
	@Tag(PRICE)
	@DisplayName("Ignores inner-record order only in histogram inputs")
	void shouldIgnoreHistogramOnlyInnerRecordOrder(@Nonnull HistogramBehavior behavior) {
		final PriceListAndCurrencyPriceSuperIndex index = priceIndex();
		final PriceEvaluationContext context = new PriceEvaluationContext(null, index.getPriceIndexKey());
		final LowestPriceTerminationFormula first = new LowestPriceTerminationFormula(container(index), context,
			QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
		final LowestPriceTerminationFormula second = new LowestPriceTerminationFormula(container(index), context,
			QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
		final CachedPriceRecordsResult original = (CachedPriceRecordsResult) capture(first);
		final CachedPriceRecordsResult permuted = (CachedPriceRecordsResult) capture(second);
		final PriceRecordContract[] originalPrices = original.perInnerRecordPriceRecords().getPriceRecords();
		final PriceRecordContract[] permutedPrices = permuted.perInnerRecordPriceRecords().getPriceRecords();
		permutedPrices[0] = originalPrices[1];
		permutedPrices[1] = originalPrices[0];
		assertTrue(equivalent(original, permuted));
		final PriceHistogramComputer originalHistogram = new PriceHistogramComputer(
			2, behavior, 2, QueryPriceMode.WITH_TAX, first, first, null);
		final PriceHistogramComputer permutedHistogram = new PriceHistogramComputer(
			2, behavior, 2, QueryPriceMode.WITH_TAX, second, second, null);
		assertEquals(originalHistogram.getKey(), permutedHistogram.getKey());
		assertTrue(equivalent(new CachedHistogramResult(originalHistogram.compute(), 1),
			new CachedHistogramResult(permutedHistogram.compute(), 1)));
	}

	/** Independent tied selections must retain the same physical selling record, not only its amount. */
	@Test
	@Tag(PRICE)
	@DisplayName("Produces equivalent lowest-price tuples when inner-record prices tie")
	void shouldTreatRecomputedLowestPriceEquivalentWhenInnerPricesTie() {
		final PriceListAndCurrencyPriceSuperIndex index = tiedPriceIndex();
		final PriceEvaluationContext context = new PriceEvaluationContext(null, index.getPriceIndexKey());
		final Formula first = new LowestPriceTerminationFormula(container(index), context,
			QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
		final Formula second = new LowestPriceTerminationFormula(container(index), context,
			QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER, true);
		assertEquals(first.getKey(), second.getKey());
		assertTrue(equivalent(capture(first), capture(second)));
	}

	/** A translation's order decides which price a containing termination keeps for the same inner record. */
	@ParameterizedTest
	@EnumSource(value = PriceInnerRecordHandling.class, names = {"LOWEST_PRICE", "SUM"})
	@Tag(PRICE)
	@DisplayName("Rejects a price permutation that changes a first-wins consumer")
	void shouldRejectTranslatedPricePermutation(@Nonnull PriceInnerRecordHandling handling) {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), handling));
		index.addPrice(new PriceRecordInnerRecordSpecific(1, 1, 10, 1, 100, 100), null);
		index.addPrice(new PriceRecordInnerRecordSpecific(2, 2, 10, 1, 300, 300), null);
		final Formula first = new PriceIdToEntityIdTranslateFormula(new PriceIdContainerFormula(index,
			new ConstantFormula(new BaseBitmap(1, 2))));
		final Formula second = new PriceIdToEntityIdTranslateFormula(new PriceIdContainerFormula(index,
			new ConstantFormula(new BaseBitmap(1, 2))));
		assertEquals(first.getKey(), second.getKey());
		final CachedPriceRecordsResult original = (CachedPriceRecordsResult) capture(first);
		final CachedPriceRecordsResult permuted = (CachedPriceRecordsResult) capture(second);
		// Deliberately corrupt the detached translation output while preserving every physical tuple field.
		final PriceRecordContract[] prices = permuted.priceRecords().getPriceRecords();
		final PriceRecordContract swap = prices[0];
		prices[0] = prices[1];
		prices[1] = swap;
		final PriceEvaluationContext context = new PriceEvaluationContext(null, index.getPriceIndexKey());
		final Formula originalConsumer = handling == PriceInnerRecordHandling.SUM
			? new SumPriceTerminationFormula(first, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER)
			: new LowestPriceTerminationFormula(first, context, QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER, true);
		final Formula permutedConsumer = handling == PriceInnerRecordHandling.SUM
			? new SumPriceTerminationFormula(second, context, QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER)
			: new LowestPriceTerminationFormula(second, context, QueryPriceMode.WITH_TAX,
				PricePredicate.ALL_RECORD_FILTER, true);
		assertAll(
			() -> assertEquals(100, ((CachedPriceRecordsResult) capture(originalConsumer))
				.priceRecords().getPriceRecords()[0].priceWithTax()),
			() -> assertEquals(300, ((CachedPriceRecordsResult) capture(permutedConsumer))
				.priceRecords().getPriceRecords()[0].priceWithTax()),
			() -> assertFalse(equivalent(original, permuted)),
			() -> assertFalse(equivalent(permuted, original))
		);
	}

	/** Checks map key sets, values, totals, entity ids and price mode independently. */
	@Test
	@DisplayName("Compares complete SUM maps even when ordinary equals ignores them")
	void shouldCompareSumComponents() {
		final CumulatedVirtualPriceRecord first = sum(2, 2);
		final CumulatedVirtualPriceRecord second = sum(2, 200);
		assertTrue(equivalent(prices(first), prices(second)));
		second.innerRecordPrices().put(0, new PriceRecordInnerRecordSpecific(1, 1, 10, 0, 121, 100));
		second.innerRecordPrices().put(1, new PriceRecordInnerRecordSpecific(2, 2, 10, 1, 119, 100));
		assertEquals(first, second);
		assertFalse(equivalent(prices(first), prices(second)));
		final IntObjectHashMap<PriceRecordContract> changedKeys = new IntObjectHashMap<>(first.innerRecordPrices());
		changedKeys.put(100, changedKeys.remove(0));
		assertFalse(equivalent(prices(first), prices(new CumulatedVirtualPriceRecord(10, 240,
			QueryPriceMode.WITH_TAX, changedKeys))));
		assertFalse(equivalent(prices(first), prices(new CumulatedVirtualPriceRecord(10, 240,
			QueryPriceMode.WITH_TAX, new IntObjectHashMap<>()))));
		assertFalse(equivalent(prices(first), prices(new CumulatedVirtualPriceRecord(11, 240,
			QueryPriceMode.WITH_TAX, first.innerRecordPrices()))));
		assertFalse(equivalent(prices(first), prices(new CumulatedVirtualPriceRecord(10, 241,
			QueryPriceMode.WITH_TAX, first.innerRecordPrices()))));
		assertFalse(equivalent(prices(first), prices(new CumulatedVirtualPriceRecord(10, 240,
			QueryPriceMode.WITHOUT_TAX, first.innerRecordPrices()))));
	}

	/** Verifies that optional per-inner-record outputs are part of the semantic tuple. */
	@Test
	@DisplayName("Compares optional inner-record outputs and payload shapes")
	void shouldCompareOptionalOutputs() {
		final CachedPriceRecordsResult first = new CachedPriceRecordsResult(new BaseBitmap(10), records(),
			records(new PriceRecord(1, 1, 10, 120, 100)), 1);
		assertFalse(equivalent(first, prices()));
		assertFalse(equivalent(prices(), first));
		assertFalse(equivalent(first, new CachedPriceRecordsResult(new BaseBitmap(10), records(),
			records(new PriceRecord(1, 1, 10, 121, 100)), 1)));
		assertFalse(equivalent(first, new CachedPriceRecordsResult(new BaseBitmap(11), records(),
			first.perInnerRecordPriceRecords(), 1)));
		final CachedResult[] shapes = {prices(), new CachedBitmapResult(new BaseBitmap(), 1),
			new CachedHistogramResult(CacheableHistogramContract.EMPTY, 1)};
		for (int i = 0; i < shapes.length; i++) {
			for (int j = 0; j < shapes.length; j++) {
				assertEquals(i == j, equivalent(shapes[i], shapes[j]));
			}
		}
	}

	/** Checks strict decimal scale and every histogram field rather than response rendering. */
	@Test
	@DisplayName("Preserves histogram scale, frequencies, raw bounds and overall count")
	void shouldCompareHistogramContents() {
		final CacheableHistogram base = histogram();
		assertTrue(equivalent(new CachedHistogramResult(base, 1), new CachedHistogramResult(histogram(), 2)));
		final CacheableBucket[] changedThreshold = base.getBuckets().clone();
		changedThreshold[0] = new CacheableBucket(new BigDecimal("1000.0"), 2, new BigDecimal("33.333"));
		final CacheableBucket[] changedFrequency = base.getBuckets().clone();
		changedFrequency[0] = new CacheableBucket(new BigDecimal("1000.00"), 2, new BigDecimal("33.334"));
		final CacheableBucket[] changedCount = base.getBuckets().clone();
		changedCount[0] = new CacheableBucket(new BigDecimal("1000.00"), 3, new BigDecimal("33.333"));
		for (final CacheableHistogramContract changed : new CacheableHistogramContract[]{
			new CacheableHistogram(base.getBuckets(), new BigDecimal("3000.0"), base.getRawMin(), base.getRawMax()),
			new CacheableHistogram(changedThreshold, base.getMax(), base.getRawMin(), base.getRawMax()),
			new CacheableHistogram(changedFrequency, base.getMax(), base.getRawMin(), base.getRawMax()),
			new CacheableHistogram(changedCount, base.getMax(), base.getRawMin(), base.getRawMax()),
			new CacheableHistogram(base.getBuckets(), base.getMax(), 1000, base.getRawMax()),
			new CacheableHistogram(base.getBuckets(), base.getMax(), base.getRawMin(), 3001L),
			new CacheableHistogram(base.getBuckets(), base.getMax(), base.getRawMin(), base.getRawMax(), 7),
			CacheableHistogramContract.EMPTY
		}) {
			assertFalse(equivalent(new CachedHistogramResult(base, 1), new CachedHistogramResult(changed, 1)));
		}
		assertTrue(equivalent(new CachedHistogramResult(CacheableHistogramContract.EMPTY, 1),
			new CachedHistogramResult(CacheableHistogramContract.EMPTY, 2)));
	}

	/** Equal contents do not make another histogram implementation equivalent, in either argument order. */
	@Test
	@DisplayName("Rejects a histogram subclass with equal contents")
	void shouldRejectHistogramSubclassWithEqualContents() {
		final CacheableHistogram base = histogram();
		final CacheableHistogram subclass = new CacheableHistogram(
			base.getBuckets(), base.getMax(), base.getRawMin(), base.getRawMax()) {
			@Serial private static final long serialVersionUID = 1L;
		};
		assertEquals(base, subclass);
		assertFalse(equivalent(new CachedHistogramResult(base, 1), new CachedHistogramResult(subclass, 1)));
		assertFalse(equivalent(new CachedHistogramResult(subclass, 1), new CachedHistogramResult(base, 1)));
	}

	/** Compares independent computations from every eligible formula class using real bitmap and price inputs. */
	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("io.evitadb.core.cache.CachedResultTestSupport#formulaSuppliers")
	@DisplayName("Produces equivalent tuples from independently built formula instances")
	void shouldComputeDeterministicFormulaPayloads(@Nonnull Supplier<Formula> factory) {
		final Formula first = factory.get();
		final Formula second = factory.get();
		assertNotSame(first, second);
		assertEquals(first.getKey(), second.getKey());
		assertFalse(first.compute().isEmpty(), first.getClass().getSimpleName());
		final CachedResult firstResult = capture(first);
		final CachedResult secondResult = capture(second);
		assertTrue(equivalent(firstResult, secondResult), first.getClass().getSimpleName());
	}

	/** Checks both histogram computers independently, including a populated price histogram. */
	@Test
	@DisplayName("Produces equivalent tuples from both histogram computers")
	void shouldComputeDeterministicHistograms() {
		final PriceListAndCurrencyPriceSuperIndex index = priceIndex();
		final PriceHistogramComputer first = priceHistogram(index);
		final PriceHistogramComputer second = priceHistogram(index);
		assertEquals(first.getKey(), second.getKey());
		assertTrue(equivalent(new CachedHistogramResult(first.compute(), 1),
			new CachedHistogramResult(second.compute(), 1)));
		assertNotSame(CacheableHistogramContract.EMPTY, first.compute());
		final AttributeHistogramProducer.AttributeHistogramRequest request = attributeHistogramRequest();
		final AttributeHistogramComputer attributeFirst = attributeHistogram(request);
		final AttributeHistogramComputer attributeSecond = attributeHistogram(request);
		assertEquals(attributeFirst.getKey(), attributeSecond.getKey());
		assertTrue(equivalent(new CachedHistogramResult(attributeFirst.compute(), 1),
			new CachedHistogramResult(attributeSecond.compute(), 1)));
		assertNotSame(CacheableHistogramContract.EMPTY, attributeFirst.compute());
	}

	/** Builds a tuple whose supplied records belong exclusively to the histogram side-output. */
	@Nonnull
	private static CachedPriceRecordsResult histogramPrices(@Nonnull PriceRecordContract... prices) {
		return new CachedPriceRecordsResult(new BaseBitmap(10), records(), records(prices), 1);
	}

	/** Captures exactly the outputs assigned to the formula's payload shape. */
	@Nonnull
	private static CachedResult capture(@Nonnull Formula formula) {
		final Bitmap result = formula.compute();
		if (formula instanceof FilteredPriceRecordAccessor accessor
			&& !(formula instanceof PriceIndexContainerFormula)) {
			final ResolvedFilteredPriceRecords prices =
				(ResolvedFilteredPriceRecords) accessor.getFilteredPriceRecords(null);
			final ResolvedFilteredPriceRecords inner = formula instanceof LowestPriceTerminationFormula lowest
				? (ResolvedFilteredPriceRecords) lowest.getFilteredPriceRecordsForHistogram(null) : null;
			return new CachedPriceRecordsResult(result, prices, inner, 1);
		}
		return new CachedBitmapResult(result, 1);
	}
}
