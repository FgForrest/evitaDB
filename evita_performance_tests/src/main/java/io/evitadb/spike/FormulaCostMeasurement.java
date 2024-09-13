/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.JoinFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.termination.FirstVariantPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormulaWithPriceFilter;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier;
import io.evitadb.index.price.model.PriceIndexKey;
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
 * This spike test tries to test how fast are formulas.
 *
 * Results:
 * (COST = 1 = 1mil. ops/s)
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

	@Benchmark
	public void notFormulaInteger(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new NotFormula(
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

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

	@Benchmark
	public void disentangleFormula(IntegerBitmapState bitmapDataSet, Blackhole blackhole) {
		blackhole.consume(
			new DisentangleFormula(
				bitmapDataSet.getBitmapA(),
				bitmapDataSet.getBitmapB()
			).compute()
		);
	}

	@Benchmark
	public void priceIdContainer(PriceIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PriceIdContainerFormula testedFormula = new PriceIdContainerFormula(
			priceDataSet.getPriceIndex(),
			priceDataSet.getPriceIdsFormula()
		);
		blackhole.consume(testedFormula.compute());
		blackhole.consume(testedFormula.getFilteredPriceRecords(null));
	}

	@Benchmark
	public void priceIdToEntityIdTranslate(PriceIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PriceIdToEntityIdTranslateFormula testedFormula = new PriceIdToEntityIdTranslateFormula(priceDataSet.getPriceIdsFormula());
		blackhole.consume(testedFormula.compute());
		blackhole.consume(testedFormula.getFilteredPriceRecords(null));
	}

	@Benchmark
	public void plainPriceTermination(EntityIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PlainPriceTerminationFormula testedFormula = new PlainPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.NONE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			)
		);
		blackhole.consume(testedFormula.compute());
	}

	@Benchmark
	public void plainPriceTerminationWithPriceFilter(EntityIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final PlainPriceTerminationFormulaWithPriceFilter testedFormula = new PlainPriceTerminationFormulaWithPriceFilter(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.NONE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			PricePredicate.ALL_RECORD_FILTER
		);
		blackhole.consume(testedFormula.compute());
	}

	@Benchmark
	public void firstVariantPriceTermination(InnerRecordIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final FirstVariantPriceTerminationFormula testedFormula = new FirstVariantPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.LOWEST_PRICE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER
		);
		blackhole.consume(testedFormula.compute());
	}

	@Benchmark
	public void sumPriceTermination(InnerRecordIdsWithPriceRecordsRecordState priceDataSet, Blackhole blackhole) {
		final SumPriceTerminationFormula testedFormula = new SumPriceTerminationFormula(
			new PriceHandlingContainerFormula(
				PriceInnerRecordHandling.LOWEST_PRICE,
				priceDataSet.getFormula()
			),
			new PriceEvaluationContext(
				new PriceIndexKey("whatever", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE)
			),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER
		);
		blackhole.consume(testedFormula.compute());
	}

	@Benchmark
	public void histogramBitmapSupplier(BucketsRecordState bucketDataSet, Blackhole blackhole) {
		final HistogramBitmapSupplier<Integer> testedFormula = new HistogramBitmapSupplier<>(
			bucketDataSet.getBuckets()
		);
		blackhole.consume(testedFormula.get());
	}

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
