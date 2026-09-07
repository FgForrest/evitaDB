/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.core.query.extraResult.translator.histogram.producer.EqualizedHistogramDataCruncher;
import io.evitadb.core.query.extraResult.translator.histogram.producer.HistogramDataCruncher;
import io.evitadb.core.query.extraResult.translator.histogram.producer.HistogramDataCruncherContract;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spike.LegacyEqualizedHistogramDataCruncher.BucketCountMode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Answers one question: **did rewriting {@link EqualizedHistogramDataCruncher} cost anything at runtime?**
 *
 * `HistogramBehavior.EQUALIZED` was reimplemented in `e84668e82` - bucket boundaries moved from a single
 * cumulative-weight sweep to the empirical quantile function, and `relativeFrequency` moved from
 * `occurrences / bucketWidth` to a triangular-kernel density estimate over the whole value axis. Both changes
 * were made for correctness, and both add passes over the distinct values. This benchmark measures what those
 * passes cost, against the algorithm they replaced, in one JVM.
 *
 * ## What is compared
 *
 * Three generations of the same computation, so one run separates two questions that are otherwise
 * conflated - what the correctness rewrite cost, and how much of that the later optimizations gave back:
 *
 * - {@link #equalizedCurrent} - the shipped implementation, after the scratch-array and pass-fusion work.
 * - {@link #equalizedPreOptimization} - {@link PreOptimizationEqualizedHistogramDataCruncher}, the rewrite
 *   before those optimizations. Its output is bit-identical to {@link #equalizedCurrent} (verified by
 *   differential comparison over ~200 000 random fixtures, including zero-weight and leading-zero-weight
 *   axes), so the gap between the two is pure overhead removed: two of the four `D`-sized scratch arrays
 *   and three of the walks over the distinct values.
 * - {@link #equalizedLegacyExact} / {@link #equalizedLegacyAdaptive} -
 *   {@link LegacyEqualizedHistogramDataCruncher}, a frozen copy of the pre-rewrite class, in the two modes the
 *   two old behaviours used ({@link BucketCountMode#EXACT} for `EQUALIZED`, {@link BucketCountMode#ADAPTIVE}
 *   for `EQUALIZED_OPTIMIZED`). `EXACT` pads up to the requested bucket count using `BigDecimal` division per
 *   placed bucket, so the two legacy modes are not interchangeable as a baseline.
 * - {@link #standardEqualWidth} - {@link HistogramDataCruncher}, untouched by the rewrite. Not a competitor;
 *   it is the scale reference that says what "a histogram" costs on this fixture, so an absolute number for the
 *   equalized family can be read as cheap or expensive rather than merely as a ratio.
 *
 * ## Where the cost is expected, from reading the algorithms
 *
 * All three generations share an identical `O(N)` first pass that folds the source items into `D` distinct
 * values. Everything after that is `O(D)` or `O(B)`, and that is where they diverge. The pre-rewrite version
 * walked the distinct values once and then made two `O(B)` passes. The rewrite walked them roughly eight
 * times - cumulative weights, the quantile walk, the bucket fold, mean, variance, the capped weights, two
 * band-averaged quantiles, the kernel window, the peak scan - and allocated a `long[D]` plus three `double[D]`
 * on the way; that is what {@link #equalizedPreOptimization} still does. The current version streams the
 * cumulative weight, fuses the bucket fold with two of the bandwidth accumulators, reads both quartiles off
 * one walk and keeps only the `B` kernel masses the bars actually need - removing the `long[D]` and one
 * `double[D]`.
 *
 * The other two `double[D]` (the shifted value axis and the capped weights) were also removed at one point,
 * by deriving both on demand. That version was measured and **reverted**: it is bit-identical and 12-15%
 * slower, because the kernel sweep reads each entry through several pointers and each read then pays
 * conversions and a division in place of one sequential load. Do not re-propose it - the arrays pay for
 * themselves, and the reasoning sits on `EqualizedHistogramDataCruncher#cappedWeight`.
 *
 * The penalty of the rewrite, and therefore the benefit of removing it, scales with `D / N`: invisible when a
 * few distinct prices back a large record set, largest when every source item is its own distinct value.
 *
 * That is exactly the split the {@link SourceShape} parameter draws, and it is not synthetic - it is the
 * difference between the two production call sites:
 *
 * - {@link SourceShape#PRICE} - `PriceHistogramComputer` hands over one `PriceRecordContract` per matching
 *   record, each of weight 1, so `N` is the record count and `D` the number of distinct prices. Here the
 *   shared `O(N)` pass dominates and dilutes any `O(D)` regression.
 * - {@link SourceShape#ATTRIBUTE} - `AttributeHistogramComputer` hands over one `ValueToRecordBitmap` per
 *   distinct value, weighted by its bitmap size, so `N == D`. Here the shared pass is at its smallest and the
 *   regression at its most visible. This is the worst case, and it is a real one.
 *
 * Both shapes are generated to carry the **same total weight** (`AVERAGE_WEIGHT * distinctCount`), so the two
 * columns describe the same catalogue seen through two call sites rather than two different catalogues.
 *
 * ## Value distributions
 *
 * - {@link Distribution#UNIFORM} - evenly spaced values of equal weight. The benign case, and the one where
 *   the quantile walk's run-batching has nothing to batch.
 * - {@link Distribution#LOGNORMAL} - lognormal gaps on the value axis with heavy-tailed weights: a retail
 *   price axis, clustered low with a long expensive tail.
 * - {@link Distribution#PLATEAU} - one value in the middle holding ~50% of the total weight. This is the shape
 *   the rewrite was made for (a price held by a large share of the catalogue) and the one that exercises the
 *   weight cap in `computeBandwidth` and the rank-run batching in the quantile walk. Worth measuring
 *   separately because it is the case where the two algorithms take genuinely different paths, not merely the
 *   same path at different speeds.
 *
 * `@Setup` prints the bucket count each variant actually produced for the current parameter combination, so
 * the run log carries evidence that no variant was measured while short-circuiting on a degenerate fixture.
 *
 * Run with `-prof gc` - the scratch arrays are as much the point as the wall-clock, and a query that
 * allocates more per call costs more than the average time alone shows.
 *
 * **Five forks is not negotiable.** JMH forks per benchmark method, so with one fork every method-vs-method
 * comparison here is really a fork-vs-fork comparison. Measured: at `-f 1` the two legacy arms - which differ
 * only by a padding pass - came out up to 70% apart and several rows inverted outright. At `-f 5` their
 * disagreement drops to 2.7% median, which is the noise floor this benchmark can resolve.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 5, jvmArgsAppend = {"-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class EqualizedHistogramCruncherBenchmark {

	/**
	 * Requested bucket count - the size a faceted price slider actually asks for.
	 */
	private static final int BUCKET_COUNT = 40;
	/**
	 * Decimal places of the indexed value space; 2 is what a price in cents uses.
	 */
	private static final int DECIMAL_PLACES = 2;
	/**
	 * Mean number of records per distinct value. Fixes the total weight at `AVERAGE_WEIGHT * distinctCount`
	 * for every distribution, so the two source shapes describe one catalogue rather than two.
	 */
	private static final int AVERAGE_WEIGHT = 4;
	private static final long RANDOM_SEED = 42L;

	/**
	 * Shape of the value axis and of the weights placed on it.
	 */
	public enum Distribution {
		UNIFORM, LOGNORMAL, PLATEAU
	}

	/**
	 * Which production call site the source array imitates - see the class javadoc.
	 */
	public enum SourceShape {
		PRICE, ATTRIBUTE
	}

	/**
	 * Number of distinct values. 100 is a filtered category, 100 000 a whole catalogue's price axis.
	 */
	@Param({"100", "1000", "10000", "100000"})
	private int distinctCount;

	@Param({"UNIFORM", "LOGNORMAL", "PLATEAU"})
	private Distribution distribution;

	@Param({"PRICE", "ATTRIBUTE"})
	private SourceShape shape;

	/**
	 * Builds one cruncher over the fixture. Four instances are bound in `@Setup`, one per measured variant,
	 * each already closed over the source array its {@link #shape} requires - so the timed methods contain
	 * neither a branch on the shape nor a generic cast, and each call site stays monomorphic within its fork.
	 */
	@FunctionalInterface
	private interface CruncherFactory {

		@Nonnull
		HistogramDataCruncherContract<?> create();

	}

	private CruncherFactory currentFactory;
	private CruncherFactory preOptimizationFactory;
	private CruncherFactory legacyExactFactory;
	private CruncherFactory legacyAdaptiveFactory;
	private CruncherFactory standardFactory;

	@Setup(Level.Trial)
	public void setUp() {
		final Random random = new Random(RANDOM_SEED);
		final int[] values = buildValues(this.distribution, this.distinctCount, random);
		final int[] weights = buildWeights(this.distribution, this.distinctCount, random);

		final IntFunction<BigDecimal> toBigDecimal = value -> new BigDecimal(value).scaleByPowerOfTen(-DECIMAL_PLACES);
		final ToIntFunction<BigDecimal> fromBigDecimal =
			value -> value.scaleByPowerOfTen(DECIMAL_PLACES).intValueExact();

		if (this.shape == SourceShape.PRICE) {
			final PriceRecordContract[] priceRecords = buildPriceRecords(values, weights);
			final ToIntFunction<PriceRecordContract> thresholdRetriever = PriceRecordContract::priceWithTax;
			final ToIntFunction<PriceRecordContract> weightRetriever = record -> 1;
			this.currentFactory = () -> new EqualizedHistogramDataCruncher<>(
				"price histogram", BUCKET_COUNT, DECIMAL_PLACES, priceRecords,
				thresholdRetriever, weightRetriever, toBigDecimal
			);
			this.preOptimizationFactory = () -> new PreOptimizationEqualizedHistogramDataCruncher<>(
				"price histogram", BUCKET_COUNT, DECIMAL_PLACES, priceRecords,
				thresholdRetriever, weightRetriever, toBigDecimal
			);
			this.legacyExactFactory = () -> new LegacyEqualizedHistogramDataCruncher<>(
				"price histogram", BUCKET_COUNT, DECIMAL_PLACES, priceRecords,
				thresholdRetriever, weightRetriever, toBigDecimal, BucketCountMode.EXACT
			);
			this.legacyAdaptiveFactory = () -> new LegacyEqualizedHistogramDataCruncher<>(
				"price histogram", BUCKET_COUNT, DECIMAL_PLACES, priceRecords,
				thresholdRetriever, weightRetriever, toBigDecimal, BucketCountMode.ADAPTIVE
			);
			this.standardFactory = () -> new HistogramDataCruncher<>(
				"price histogram", BUCKET_COUNT, DECIMAL_PLACES, priceRecords,
				thresholdRetriever, weightRetriever, toBigDecimal, fromBigDecimal
			);
		} else {
			final ValueToRecordBitmap[] buckets = buildValueToRecordBitmaps(values, weights);
			final ToIntFunction<ValueToRecordBitmap> thresholdRetriever = bucket -> (Integer) bucket.getValue();
			final ToIntFunction<ValueToRecordBitmap> weightRetriever = bucket -> bucket.getRecordIds().size();
			this.currentFactory = () -> new EqualizedHistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal
			);
			this.preOptimizationFactory = () -> new PreOptimizationEqualizedHistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal
			);
			this.legacyExactFactory = () -> new LegacyEqualizedHistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal, BucketCountMode.EXACT
			);
			this.legacyAdaptiveFactory = () -> new LegacyEqualizedHistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal, BucketCountMode.ADAPTIVE
			);
			this.standardFactory = () -> new HistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal, fromBigDecimal
			);
		}

		// evidence in the run log that every variant did real work on this fixture - a variant that
		// short-circuits to a single bucket would otherwise post a fast, meaningless score
		long totalWeight = 0;
		for (final int weight : weights) {
			totalWeight += weight;
		}
		System.err.printf(
			"[fixture] shape=%s dist=%s D=%d N=%d totalWeight=%d -> buckets: current=%d preOpt=%d " +
				"legacyExact=%d legacyAdaptive=%d standard=%d%n",
			this.shape, this.distribution, this.distinctCount,
			this.shape == SourceShape.PRICE ? totalWeight : this.distinctCount, totalWeight,
			this.currentFactory.create().getHistogram().length,
			this.preOptimizationFactory.create().getHistogram().length,
			this.legacyExactFactory.create().getHistogram().length,
			this.legacyAdaptiveFactory.create().getHistogram().length,
			this.standardFactory.create().getHistogram().length
		);
	}

	/**
	 * The shipped implementation - quantile boundaries plus a kernel-density intensity.
	 */
	@Benchmark
	public Object equalizedCurrent() {
		return this.currentFactory.create().getHistogram();
	}

	/**
	 * The same algorithm as {@link #equalizedCurrent}, before the scratch arrays were removed and the passes
	 * fused. Bit-identical output, so the difference between the two is pure overhead.
	 */
	@Benchmark
	public Object equalizedPreOptimization() {
		return this.preOptimizationFactory.create().getHistogram();
	}

	/**
	 * The pre-rewrite implementation in the mode `HistogramBehavior.EQUALIZED` used, which pads the result up
	 * to the requested bucket count with `BigDecimal`-placed empty buckets.
	 */
	@Benchmark
	public Object equalizedLegacyExact() {
		return this.legacyExactFactory.create().getHistogram();
	}

	/**
	 * The pre-rewrite implementation in the mode `HistogramBehavior.EQUALIZED_OPTIMIZED` used - the same
	 * boundary sweep without the padding pass, and therefore the cheaper of the two legacy baselines.
	 */
	@Benchmark
	public Object equalizedLegacyAdaptive() {
		return this.legacyAdaptiveFactory.create().getHistogram();
	}

	/**
	 * Scale reference: the equal-width cruncher the rewrite did not touch.
	 */
	@Benchmark
	public Object standardEqualWidth() {
		return this.standardFactory.create().getHistogram();
	}

	/**
	 * Builds the ascending, strictly increasing distinct value axis in the indexed integer space.
	 */
	@Nonnull
	private static int[] buildValues(@Nonnull Distribution distribution, int distinctCount, @Nonnull Random random) {
		final int[] values = new int[distinctCount];
		switch (distribution) {
			case UNIFORM, PLATEAU -> {
				// evenly spaced, 97 cents apart - a prime step so no bucket boundary lands on a round multiple
				for (int i = 0; i < distinctCount; i++) {
					values[i] = 1000 + i * 97;
				}
			}
			case LOGNORMAL -> {
				// lognormal gaps: dense at the cheap end, sparse in the expensive tail
				int current = 1000;
				for (int i = 0; i < distinctCount; i++) {
					values[i] = current;
					current += Math.max(1, (int) Math.round(Math.exp(random.nextGaussian()) * 8.0));
				}
			}
			default -> throw new IllegalStateException("Unhandled distribution: " + distribution);
		}
		return values;
	}

	/**
	 * Builds the per-value weights. Every distribution targets a total of `AVERAGE_WEIGHT * distinctCount`.
	 */
	@Nonnull
	private static int[] buildWeights(@Nonnull Distribution distribution, int distinctCount, @Nonnull Random random) {
		final int[] weights = new int[distinctCount];
		switch (distribution) {
			case UNIFORM -> {
				for (int i = 0; i < distinctCount; i++) {
					weights[i] = AVERAGE_WEIGHT;
				}
			}
			case LOGNORMAL -> {
				// heavy-tailed weights: most values held by a handful of records, a few by many
				for (int i = 0; i < distinctCount; i++) {
					weights[i] = Math.max(1, (int) Math.round(Math.exp(random.nextGaussian() * 0.8) * 3.0));
				}
			}
			case PLATEAU -> {
				// one value in the middle carries half the catalogue; the rest share the other half evenly
				for (int i = 0; i < distinctCount; i++) {
					weights[i] = AVERAGE_WEIGHT / 2;
				}
				weights[distinctCount / 2] = (AVERAGE_WEIGHT / 2) * distinctCount;
			}
			default -> throw new IllegalStateException("Unhandled distribution: " + distribution);
		}
		return weights;
	}

	/**
	 * Expands the weighted value axis into the flat, ascending, weight-1 record array
	 * `PriceHistogramComputer` produces.
	 */
	@Nonnull
	private static PriceRecordContract[] buildPriceRecords(@Nonnull int[] values, @Nonnull int[] weights) {
		int total = 0;
		for (final int weight : weights) {
			total += weight;
		}
		final PriceRecordContract[] priceRecords = new PriceRecordContract[total];
		int index = 0;
		for (int i = 0; i < values.length; i++) {
			for (int j = 0; j < weights[i]; j++) {
				priceRecords[index] = new PriceRecord(index + 1, index + 1, index + 1, values[i], values[i]);
				index++;
			}
		}
		return priceRecords;
	}

	/**
	 * Wraps the weighted value axis into the one-entry-per-distinct-value array
	 * `AttributeHistogramComputer` produces, each entry weighted by the size of its record bitmap.
	 */
	@Nonnull
	private static ValueToRecordBitmap[] buildValueToRecordBitmaps(@Nonnull int[] values, @Nonnull int[] weights) {
		final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[values.length];
		int nextRecordId = 1;
		for (int i = 0; i < values.length; i++) {
			final int[] recordIds = new int[weights[i]];
			for (int j = 0; j < recordIds.length; j++) {
				recordIds[j] = nextRecordId++;
			}
			buckets[i] = new ValueToRecordBitmap(values[i], new BaseBitmap(recordIds));
		}
		return buckets;
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

}
