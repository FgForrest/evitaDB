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
 * Measures what {@link EqualizedHistogramDataCruncher} costs per computation, on the two source shapes the
 * production call sites actually produce.
 *
 * `HistogramBehavior.EQUALIZED` was reimplemented in `e84668e82` - bucket boundaries moved from a single
 * cumulative-weight sweep to the empirical quantile function, and `relativeFrequency` from
 * `occurrences / bucketWidth` to a triangular-kernel density estimate over the whole value axis - and then
 * optimized in `8cda8175e`. Both rounds were measured with this harness, against frozen copies of the two
 * earlier generations that no longer live here: the fork they answered is settled and shipped, and what they
 * measured is recorded in
 * `documentation/adr/2026-09-07-equalized-histogram-density-and-quantile-bucketing.md`.
 *
 * ## Getting a historical baseline back
 *
 * Both removed copies were verbatim copies of the production class at a revision, differing from it only in
 * the class name, so either comparison is reconstructible without carrying dead code in the tree:
 *
 * ```
 * P=evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram/producer
 * git show 373dfc9b2:$P/EqualizedHistogramDataCruncher.java   # pre-rewrite (EQUALIZED / EQUALIZED_OPTIMIZED)
 * git show 84e599586:$P/EqualizedHistogramDataCruncher.java   # rewritten, before the optimization pass
 * ```
 *
 * Rename the class, drop it in this package, bind a factory and add an arm. Read the ADR first - it records
 * what each generation cost and why the current shape won, so most questions do not need the code back.
 *
 * ## What is measured
 *
 * - {@link #equalizedCurrent} - the shipped equalized cruncher. This is the regression signal: compare its
 *   ns/op against the figures the ADR records for the same fixtures rather than against a second arm.
 * - {@link #standardEqualWidth} - {@link HistogramDataCruncher}, the equal-width cruncher that also ships and
 *   that the rewrite never touched. Not a competitor; it is the scale reference that says what "a histogram"
 *   costs on this fixture, so the equalized number reads as cheap or expensive rather than merely as a ratio.
 *
 * ## Where the cost sits, from reading the algorithm
 *
 * An `O(N)` first pass folds the source items into `D` distinct values; everything after it is `O(D)` or
 * `O(B)`. The current version streams the quantile walk's cumulative weight, fuses the bucket fold with two
 * of the bandwidth accumulators, reads both quartiles off one walk, and keeps only the `B` kernel masses the
 * bars are measured at - leaving two `double[D]` of scratch.
 *
 * Those two (the shifted value axis and the capped weights) were also removed at one point, by deriving both
 * on demand. That version was measured and **reverted**: it is bit-identical and 12-15% slower, because the
 * kernel sweep reads each entry through several window pointers and each read then pays two conversions and a
 * division in place of one sequential load. Do not re-propose it - the arrays pay for themselves, and the
 * reasoning sits on `EqualizedHistogramDataCruncher#cappedWeight`.
 *
 * The equalized family's cost, relative to the shared first pass, scales with `D / N`: invisible when a few
 * distinct prices back a large record set, largest when every source item is its own distinct value. That is
 * exactly the split the {@link SourceShape} parameter draws, and it is not synthetic - it is the difference
 * between the two production call sites:
 *
 * - {@link SourceShape#PRICE} - `PriceHistogramComputer` hands over one `PriceRecordContract` per matching
 *   record, each of weight 1, so `N` is the record count and `D` the number of distinct prices. Here the
 *   shared `O(N)` pass dominates and dilutes any `O(D)` regression.
 * - {@link SourceShape#ATTRIBUTE} - `AttributeHistogramComputer` hands over one `ValueToRecordBitmap` per
 *   distinct value, weighted by its bitmap size, so `N == D`. Here the shared pass is at its smallest and a
 *   regression at its most visible. This is the worst case, and it is a real one.
 *
 * Both shapes are generated to carry the same total weight, so the two columns describe the same catalogue
 * seen through two call sites rather than two different catalogues - see {@link #AVERAGE_WEIGHT} for the one
 * distribution that only approaches that target.
 *
 * ## Value distributions
 *
 * - {@link Distribution#UNIFORM} - evenly spaced values of equal weight. The benign case, and the one where
 *   the quantile walk's run-batching has nothing to batch.
 * - {@link Distribution#LOGNORMAL} - lognormal gaps on the value axis with heavy-tailed weights: a retail
 *   price axis, clustered low with a long expensive tail.
 * - {@link Distribution#PLATEAU} - one value in the middle holding ~50% of the total weight. This is the shape
 *   the rewrite was made for (a price held by a large share of the catalogue) and the one that exercises the
 *   weight cap in `computeBandwidth` and the rank-run batching in the quantile walk.
 *
 * `@Setup` prints the bucket count each arm actually produced for the current parameter combination, so the
 * run log carries evidence that nothing was measured while short-circuiting on a degenerate fixture.
 *
 * Run with `-prof gc` - the scratch arrays are as much the point as the wall-clock, and a query that
 * allocates more per call costs more than the average time alone shows.
 *
 * **Five forks is not negotiable.** JMH forks per benchmark method, so with one fork every method-vs-method
 * comparison here is really a fork-vs-fork comparison. Measured during the attribution study this harness was
 * built for: at `-f 1` two arms differing only by a padding pass came out up to 70% apart and several rows
 * inverted outright, while at `-f 5` their disagreement dropped to 2.7% median - the noise floor this
 * benchmark can resolve. The same applies to reading one arm across two runs.
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
	 * Mean number of records per distinct value - the total weight of `AVERAGE_WEIGHT * distinctCount` the
	 * distributions aim at, so that they describe one catalogue rather than three. UNIFORM and PLATEAU hit it
	 * exactly; LOGNORMAL's floored draw averages nearer 4.14 and so lands 3-6% over it. That is left alone
	 * deliberately: rescaling would flatten the very weight shape the distribution exists to supply, and
	 * every arm of one `@Param` combination is measured against the identical fixture, so no comparison this
	 * benchmark makes ever crosses a distribution boundary.
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
	 * Builds one cruncher over the fixture. One instance is bound in `@Setup` per measured arm, each already
	 * closed over the source array its {@link #shape} requires - so the timed methods contain neither a branch
	 * on the shape nor a generic cast, and each call site stays monomorphic within its fork.
	 */
	@FunctionalInterface
	private interface CruncherFactory {

		@Nonnull
		HistogramDataCruncherContract<?> create();

	}

	private CruncherFactory currentFactory;
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
			this.standardFactory = () -> new HistogramDataCruncher<>(
				"attribute histogram", BUCKET_COUNT, DECIMAL_PLACES, buckets,
				thresholdRetriever, weightRetriever, toBigDecimal, fromBigDecimal
			);
		}

		// evidence in the run log that both arms did real work on this fixture - an arm that short-circuits
		// to a single bucket would otherwise post a fast, meaningless score
		long totalWeight = 0;
		for (final int weight : weights) {
			totalWeight += weight;
		}
		System.err.printf(
			"[fixture] shape=%s dist=%s D=%d N=%d totalWeight=%d -> buckets: current=%d standard=%d%n",
			this.shape, this.distribution, this.distinctCount,
			this.shape == SourceShape.PRICE ? totalWeight : this.distinctCount, totalWeight,
			this.currentFactory.create().getHistogram().length,
			this.standardFactory.create().getHistogram().length
		);
	}

	/**
	 * The shipped implementation - quantile boundaries plus a kernel-density intensity. The regression signal.
	 */
	@Benchmark
	public Object equalizedCurrent() {
		return this.currentFactory.create().getHistogram();
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
	 * Builds the per-value weights. Every distribution targets a total of `AVERAGE_WEIGHT * distinctCount` -
	 * see {@link #AVERAGE_WEIGHT} for the one that only approaches it.
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
