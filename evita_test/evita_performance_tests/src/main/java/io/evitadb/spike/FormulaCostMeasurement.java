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

package io.evitadb.spike;

import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.entity.EntityPrimaryKeyRangeFormula;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.JoinFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormulaWithPriceFilter;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spike.mock.BucketsRecordState;
import io.evitadb.spike.mock.EntityIdsWithPriceRecordsRecordState;
import io.evitadb.spike.mock.InnerRecordIdsWithPriceRecordsRecordState;
import io.evitadb.spike.mock.IntegerBitmapState;
import io.evitadb.spike.mock.PriceBucketRecordState;
import io.evitadb.spike.mock.PriceIdsWithPriceRecordsRecordState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Currency;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark suite that measures throughput of individual formula implementations used in
 * the evitaDB query engine. The results are used to derive **operation cost coefficients** for the
 * formula caching layer — a cost of 1 corresponds to 1 million ops/s.
 *
 * All benchmarks operate on datasets of comparable cardinality (~100K records) so that throughput
 * numbers are directly comparable across formula types. Each benchmark creates a fresh formula instance
 * per invocation (no caching between iterations) and consumes results via {@link Blackhole} to prevent
 * dead-code elimination.
 *
 * **Note on `plainPriceTermination`:** {@link PlainPriceTerminationFormula} is a pure delegation wrapper
 * — its `computeInternal()` only forwards to the inner formula without price filtering. The extremely
 * high throughput (~45M ops/s) reflects this delegation overhead and is intentional — the formula
 * genuinely has near-zero cost in the cost model. Compare with
 * {@link PlainPriceTerminationFormulaWithPriceFilter} which performs per-entity price predicate
 * evaluation and shows realistic filtering cost (~312 ops/s).
 *
 * ## Results
 *
 * (COST = 1 = 1 mil. ops/s)
 *
 * Benchmark                                                       Mode  Cnt        Score   Error  Units
 * FormulaCostMeasurement.andFormulaInteger                       thrpt    2   116094.151          ops/s
 * FormulaCostMeasurement.attributeHistogramComputer              thrpt    2      301.262          ops/s
 * FormulaCostMeasurement.disentangleFormula                      thrpt    2      446.449          ops/s
 * FormulaCostMeasurement.firstVariantPriceTermination            thrpt    2       54.935          ops/s
 * FormulaCostMeasurement.histogramBitmapSupplier                 thrpt    2     3959.800          ops/s
 * FormulaCostMeasurement.joinFormula                             thrpt    2      390.631          ops/s
 * FormulaCostMeasurement.notFormulaInteger                       thrpt    2   148431.438          ops/s
 * FormulaCostMeasurement.orFormulaInteger                        thrpt    2    80640.199          ops/s
 * FormulaCostMeasurement.plainPriceTermination                   thrpt    2 45566587.048          ops/s
 * FormulaCostMeasurement.plainPriceTerminationWithPriceFilter    thrpt    2      312.185          ops/s
 * FormulaCostMeasurement.priceHistogramComputer                  thrpt    2       88.759          ops/s
 * FormulaCostMeasurement.priceIdContainer                        thrpt    2      483.873          ops/s
 * FormulaCostMeasurement.priceIdToEntityIdTranslate              thrpt    2      283.536          ops/s
 * FormulaCostMeasurement.sumPriceTermination                     thrpt    2       55.321          ops/s
 * FormulaCostMeasurement.mergedSortedRecordsSupplier             thrpt    2       50.794          ops/s
 *
 * Benchmark                                                       Mode  Cnt        Score   Error  Units
 * FormulaCostMeasurement.roaringBitmapWithRandomFar              thrpt    2      374.028          ops/s
 * FormulaCostMeasurement.roaringBitmapWithRandomClose            thrpt    2      903.770          ops/s
 * FormulaCostMeasurement.roaringBitmapWithRandomIntClose         thrpt    2     1580.381          ops/s
 * FormulaCostMeasurement.roaringBitmapWithRandomIntCloseBatch    thrpt    2     3332.862          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@BenchmarkMode(Mode.Throughput)
@Threads(1)
@Warmup(iterations = 2)
@Fork(1)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.MINUTES)
public class FormulaCostMeasurement {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Measures throughput of {@link AndFormula} — set intersection of two 100K-element
	 * RoaringBitmap-backed bitmaps.
	 */
	@Benchmark
	public void andFormulaInteger(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new AndFormula(
				new long[]{1L},
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link OrFormula} — set union of two 100K-element
	 * RoaringBitmap-backed bitmaps.
	 */
	@Benchmark
	public void orFormulaInteger(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new OrFormula(
				new long[]{1L},
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link NotFormula} — set difference (A minus B) of two 100K-element
	 * RoaringBitmap-backed bitmaps.
	 */
	@Benchmark
	public void notFormulaInteger(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new NotFormula(
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link JoinFormula} — concatenation (multiset union) of two 100K-element
	 * RoaringBitmap-backed bitmaps.
	 */
	@Benchmark
	public void joinFormula(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new JoinFormula(
				1L,
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link DisentangleFormula} — separates records unique to A from records
	 * shared with B, operating on two 100K-element RoaringBitmap-backed bitmaps.
	 */
	@Benchmark
	public void disentangleFormula(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new DisentangleFormula(
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link EntityPrimaryKeyRangeFormula} — iterates a 100K-element
	 * RoaringBitmap-backed superset bitmap and retains only primary keys within a range covering
	 * approximately the middle 50% of values (25th to 75th percentile).
	 */
	@Benchmark
	public void entityPrimaryKeyRangeFormula(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new EntityPrimaryKeyRangeFormula(
				50_000,
				150_000,
				new ConstantFormula(bitmapDataSet.getBitmapA())
			).compute()
		);
	}

	/**
	 * Measures throughput of {@link PriceIdContainerFormula} — resolves price IDs from a price index
	 * and materializes corresponding {@link PriceRecordContract} array via
	 * {@link PriceIdContainerFormula#getFilteredPriceRecords}. Operates on ~100K price records.
	 */
	@Benchmark
	public void priceIdContainer(PriceIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PriceIdContainerFormula testedFormula = new PriceIdContainerFormula(
			priceDataSet.getPriceIndex(),
			priceDataSet.getPriceIdsFormula()
		);
		blackhole.consume(testedFormula.compute());
		blackhole.consume(testedFormula.getFilteredPriceRecords(priceDataSet.getQueryExecutionContext()));
	}

	/**
	 * Measures throughput of {@link PriceIdToEntityIdTranslateFormula} — translates ~100K price IDs
	 * to entity IDs by looking up each price record in the underlying price index, and collects
	 * the corresponding filtered price records.
	 */
	@Benchmark
	public void priceIdToEntityIdTranslate(PriceIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PriceIdToEntityIdTranslateFormula testedFormula = new PriceIdToEntityIdTranslateFormula(priceDataSet.getPriceIdsFormula());
		blackhole.consume(testedFormula.compute());
		blackhole.consume(testedFormula.getFilteredPriceRecords(priceDataSet.getQueryExecutionContext()));
	}

	/**
	 * Measures throughput of {@link PlainPriceTerminationFormula} — a pure delegation wrapper that
	 * returns entity IDs from the inner formula without applying any price predicate filtering.
	 * The extremely high throughput (~45M ops/s) reflects the near-zero cost of delegation only.
	 * Operates on an OR-combined formula tree of 3 × 100K price records over ~10K entities.
	 */
	@Benchmark
	public void plainPriceTermination(EntityIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PlainPriceTerminationFormula testedFormula = new PlainPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.NONE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				null, new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			)
		);
		blackhole.consume(testedFormula.compute());
	}

	/**
	 * Measures throughput of {@link PlainPriceTerminationFormulaWithPriceFilter} — iterates through
	 * all entity IDs, looks up their associated price records, and evaluates each price against
	 * a {@link PricePredicate}. Operates on the same dataset as {@link #plainPriceTermination} but
	 * performs actual per-entity price filtering work (~312 ops/s vs ~45M ops/s for pure delegation).
	 */
	@Benchmark
	public void plainPriceTerminationWithPriceFilter(EntityIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PlainPriceTerminationFormulaWithPriceFilter testedFormula = new PlainPriceTerminationFormulaWithPriceFilter(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.NONE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				null, new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			PricePredicate.ALL_RECORD_FILTER
		);
		blackhole.consume(testedFormula.compute());
	}

	/**
	 * Measures throughput of {@link LowestPriceTerminationFormula} — selects the lowest-priced
	 * variant for each entity from inner-record-specific price records, applying a price predicate.
	 * Operates on an OR-combined formula tree of 3 × 100K price records with inner record grouping.
	 */
	@Benchmark
	public void firstVariantPriceTermination(InnerRecordIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final LowestPriceTerminationFormula testedFormula = new LowestPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.LOWEST_PRICE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				null, new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER,
			false
		);
		blackhole.consume(testedFormula.compute());
	}

	/**
	 * Measures throughput of {@link SumPriceTerminationFormula} — sums prices across all inner
	 * records for each entity and applies a price predicate to the aggregate. Operates on the same
	 * dataset as {@link #firstVariantPriceTermination} (3 × 100K inner-record-specific price records).
	 */
	@Benchmark
	public void sumPriceTermination(InnerRecordIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final SumPriceTerminationFormula testedFormula = new SumPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.LOWEST_PRICE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				null, new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER
		);
		blackhole.consume(testedFormula.compute());
	}

	/**
	 * Measures throughput of {@link HistogramBitmapSupplier} — merges bitmaps from 2000 histogram
	 * buckets (~50 records each, ~100K total) into a single bitmap.
	 */
	@Benchmark
	public void histogramBitmapSupplier(BucketsRecordState bucketDataSet, Blackhole blackhole) {
		final HistogramBitmapSupplier testedFormula = new HistogramBitmapSupplier(
			bucketDataSet.getBuckets()
		);
		blackhole.consume(testedFormula.get());
	}

	/**
	 * Measures throughput of {@link AttributeHistogramComputer} — computes a 40-bucket attribute
	 * histogram by intersecting 5 filter indices (each with 2000 value buckets) against ~100K
	 * entity IDs.
	 */
	@Benchmark
	public void attributeHistogramComputer(BucketsRecordState bucketDataSet, Blackhole blackhole) {
		final AttributeHistogramComputer testedFormula = new AttributeHistogramComputer(
			"test histogram",
			bucketDataSet.getFormula(),
			40, HistogramBehavior.STANDARD,
			bucketDataSet.getRequest()
		);
		blackhole.consume(testedFormula.compute());
	}

	/**
	 * Measures throughput of {@link PriceHistogramComputer} — computes a 40-bucket price histogram
	 * from two price record accessors (70K + 30K = 100K prices) over ~10K entities, using
	 * WITH_TAX price mode with 2 decimal places.
	 */
	@Benchmark
	public void priceHistogramComputer(PriceBucketRecordState bucketDataSet, Blackhole blackhole) {
		final PriceHistogramComputer testedFormula = new PriceHistogramComputer(
			40, HistogramBehavior.STANDARD,
			2, QueryPriceMode.WITH_TAX,
			bucketDataSet.getFormulaA(),
			bucketDataSet.getFormulaB(),
			bucketDataSet.getFilteredPriceRecordAccessors(),
			null
		);
		blackhole.consume(testedFormula.compute());
	}

}
