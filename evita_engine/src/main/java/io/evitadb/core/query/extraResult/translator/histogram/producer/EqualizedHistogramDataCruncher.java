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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Computes a histogram whose bucket boundaries sit at equal *quantiles* of the data rather than at equal
 * *value* intervals. Unlike {@link HistogramDataCruncher}, which uses equal-width buckets, every bucket
 * produced here covers approximately the same share of the records.
 *
 * This is what a filter slider wants: when 90% of the products cost between $10 and $50 and 10% cost between
 * $50 and $1000, an equal-width axis spends nine tenths of the track on a tenth of the catalogue. Equalizing
 * the axis gives every slider position roughly the same number of products to move past.
 *
 * The result has two independent parts, and they answer two different questions.
 *
 * ## Part 1 — where the bucket boundaries go
 *
 * The thresholds are the empirical inverse CDF (quantile function) sampled at ranks `k / bucketCount`,
 * de-duplicated:
 *
 * 1. accumulate the source items into distinct values with their weights,
 * 2. for each `k` in `[0, bucketCount)` walk to the first distinct value whose cumulative weight reaches
 *    the rank `k / bucketCount` - the value that *contains* that rank, compared in exact integer arithmetic
 *    (`C[j+1] * B > k * N`) so the boundary never depends on floating-point rounding,
 * 3. when several consecutive targets land on the *same* distinct value — which is what a price held by
 *    many products does — emit that value once and, if it absorbed two or more targets, additionally emit
 *    the *next* distinct value. That closes the heavy value's mass into a bucket of its own instead of
 *    letting it bleed into the following one.
 *
 * The result therefore contains **no empty buckets and no repeated thresholds**: every threshold is a real,
 * selectable value, so every slider position yields a different result set. It may legitimately contain
 * **fewer** than `bucketCount` buckets — when a value is held by many records, there is simply no distinct
 * value to split the interval at. Callers must not assume otherwise. The final bucket's threshold may also
 * equal {@link #getMaxValue()}, making it zero-width — that is what isolating a numerous largest value looks
 * like, and renderers are expected to floor the bar width rather than treat it as degenerate.
 *
 * The bucket count stays within budget: with `m_i` targets absorbed by start `i`,
 * `Σ m_i = bucketCount`, the isolation rule adds at most one threshold per start with `m_i >= 2`, and
 * `bucketCount = Σ m_i >= |starts| + Σ_{m_i >= 2} (m_i − 1) >= |starts| + |{i : m_i >= 2}|`, so the emitted
 * count never exceeds `bucketCount`.
 *
 * ## Part 2 — how tall the bar is
 *
 * `relativeFrequency` is a **rendering intensity in `(0, 100]`** — not a count, not a share. Because the
 * axis is equalized, occurrences per bucket are ~constant by construction and carry no information; the
 * quantity a reader actually perceives on an equal-pixel equalized axis is the density-quantile function
 * `f(F⁻¹(u))`. Deriving it from a single bucket's own width rests on the gap between two adjacent values -
 * a sample of one - and swings by orders of magnitude when a single record is repriced, so it is instead
 * read off one global kernel density estimate over the whole value axis:
 *
 * - **kernel**: triangular, `K(u) = max(0, 1 − |u|)`. Compact support keeps the evaluation `O(D + B)`.
 * - **bandwidth**: `h = √6 · 0.9 · min(σ_w, IQR_c / 1.34) · D^(−1/5)` — Silverman's rule, with the `√6`
 *   converting the normal-reference σ into the triangular kernel's support radius (the triangular kernel
 *   has variance `h²/6`).
 * - **normalisation**: against the maximum of the curve itself, so the tallest point of the *distribution*
 *   reads 100 regardless of how many buckets were requested.
 *
 * Two deliberate departures from textbook Silverman, both consequences of what this estimate is *for*:
 * it is a deterministic smoothing of a catalogue that is known in full, not an estimate of a latent
 * population from a sample.
 *
 * - The count term is `D`, the number of **distinct** values, not `N`. Cloning every product leaves the
 *   catalogue's shape identical, so it must leave the curve identical; `N^(−1/5)` would sharpen it by 13%
 *   per doubling.
 * - The robust spread term caps a heavy value at `w' = min(w, (N − w) / 2)`. A value holding more than half
 *   the weight otherwise spans the whole interquartile range and drives the IQR to zero from the inside,
 *   collapsing the bandwidth. See {@link #computeBandwidth} for why this is a `min` and not an `if`.
 *
 * The height belongs to the **whole bucket** and is evaluated at the bucket's weighted median observation,
 * so a bucket that is mostly one heavy value is measured where its records actually are. Clients render a
 * bar spanning `[threshold_k, threshold_{k+1})`.
 *
 * @param <T> the type of source data elements
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EqualizedHistogramDataCruncher<T> implements HistogramDataCruncherContract<T> {

	/**
	 * Constant for 100 used in relative frequency normalization.
	 */
	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
	/**
	 * Smallest relative frequency ever emitted for a non-empty bucket. The contract guarantees
	 * `0 &lt; relativeFrequency &lt;= 100`; a bucket whose density is more than five orders of magnitude below
	 * the peak would otherwise round to `0.00` at scale 2 and read as "empty", which it is not.
	 */
	private static final BigDecimal MINIMAL_RELATIVE_FREQUENCY = new BigDecimal("0.01");
	/**
	 * Converts a normal-reference standard deviation into the support radius of the triangular kernel.
	 * The triangular kernel `K(u) = max(0, 1 - |u|)` scaled to radius `h` has variance `h² / 6`, so a
	 * kernel of standard deviation `s` needs `h = √6 · s`.
	 */
	private static final double TRIANGULAR_SUPPORT_FACTOR = Math.sqrt(6.0);
	/**
	 * The `0.9` of Silverman's rule of thumb `0.9 · min(σ, IQR / 1.34) · n^(-1/5)`.
	 */
	private static final double SILVERMAN_FACTOR = 0.9;
	/**
	 * Exponent of the count term in Silverman's rule. Applied to the number of **distinct** values - see
	 * the class documentation for why the count is not `N`.
	 */
	private static final double SILVERMAN_COUNT_EXPONENT = -0.2;
	/**
	 * Normal-consistency constant relating the interquartile range to the standard deviation: for a normal
	 * distribution `IQR ≈ 1.34 σ`.
	 */
	private static final double NORMAL_IQR_CONSTANT = 1.34;
	/**
	 * Half-width, in rank units, of the band the quantile is averaged over - see
	 * {@link #bandAveragedInterQuartileSpread}. Must stay small enough that `quartile ± radius` remains inside
	 * `[0, 1]` for both quartiles.
	 */
	private static final double QUANTILE_BAND_RADIUS = 0.05;
	/**
	 * Lower quartile rank used for the robust spread estimate.
	 */
	private static final double LOWER_QUARTILE = 0.25;
	/**
	 * Upper quartile rank used for the robust spread estimate.
	 */
	private static final double UPPER_QUARTILE = 0.75;
	/**
	 * Floor applied to a capped weight so that a value holding the entire catalogue (where the cap
	 * `(N - w) / 2` evaluates to zero) still occupies a non-degenerate slice of the rank axis and the
	 * capped weights sum to something positive.
	 */
	private static final double MINIMAL_CAPPED_WEIGHT = 1e-12;
	/**
	 * Number of quantile ranks a single distinct value must absorb before its mass is closed into a bucket of its
	 * own. A value that absorbed just one rank is an ordinary bucket start; one that absorbed two or more was
	 * charged for intervals no other value could open, so the histogram re-opens at the next distinct value.
	 */
	private static final int MINIMUM_ABSORBED_RANKS_TO_ISOLATE = 2;

	/**
	 * Contains requested maximal bucket count.
	 */
	@Getter private final int bucketCount;
	/**
	 * Contains requested maximal count of decimal places allowed in computed threshold values of the histogram.
	 */
	@Getter private final int limitDecimalPlacesTo;
	/**
	 * Contains array of source data.
	 */
	@Getter private final T[] sourceData;
	/**
	 * Name of the histogram, used in diagnostic messages.
	 */
	private final String histogramType;
	/**
	 * Contains array of output data (buckets in histogram).
	 */
	private final CacheableBucket[] histogram;
	/**
	 * Internal variable containing first threshold int value.
	 */
	private final int firstThreshold;
	/**
	 * Internal variable containing last threshold int value.
	 */
	private final int lastThreshold;
	/**
	 * Function that converts int threshold/value to {@link BigDecimal}.
	 */
	private final IntFunction<BigDecimal> toBigDecimalConverter;

	/**
	 * Creates a new EqualizedHistogramDataCruncher that positions bucket boundaries on the empirical
	 * quantile function and derives bar heights from a kernel density estimate over the value axis.
	 *
	 * The number of returned buckets is at most `bucketCount` and may be lower - see the class
	 * documentation. Empty buckets are never produced, so there is nothing to pad and no separate
	 * "optimized" mode.
	 *
	 * @param histogramType         name of the histogram (for error messages)
	 * @param bucketCount           requested maximal number of buckets (must be &gt; 1)
	 * @param limitDecimalPlacesTo  maximum decimal places in threshold values
	 * @param sourceData            array of source data items (must be sorted by threshold value)
	 * @param thresholdRetriever    function to extract threshold value from source item
	 * @param weightRetriever       function to extract weight (record count) from source item
	 * @param toBigDecimalConverter function to convert int threshold to BigDecimal
	 */
	public EqualizedHistogramDataCruncher(
		@Nonnull String histogramType,
		int bucketCount,
		int limitDecimalPlacesTo,
		@Nonnull T[] sourceData,
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever,
		@Nonnull IntFunction<BigDecimal> toBigDecimalConverter
	) {
		Assert.isTrue(
			bucketCount > 1,
			() -> new InvalidHistogramBucketCountException(histogramType, bucketCount)
		);
		Assert.isTrue(
			!ArrayUtils.isEmpty(sourceData),
			"Source data must not be empty to compute " + histogramType + " histogram!"
		);
		this.histogramType = histogramType;
		this.bucketCount = bucketCount;
		this.limitDecimalPlacesTo = limitDecimalPlacesTo;
		this.sourceData = sourceData;
		this.toBigDecimalConverter = toBigDecimalConverter;
		this.firstThreshold = thresholdRetriever.applyAsInt(sourceData[0]);
		this.lastThreshold = thresholdRetriever.applyAsInt(sourceData[sourceData.length - 1]);

		this.histogram = computeEqualizedHistogram(thresholdRetriever, weightRetriever);
	}

	/**
	 * Returns the computed histogram as an array of buckets. The array is never empty, contains no empty
	 * bucket, and its length is at most {@link #getBucketCount()}.
	 */
	@Nonnull
	@Override
	public CacheableBucket[] getHistogram() {
		return this.histogram;
	}

	/**
	 * Returns the maximal value present in the histogram source data - the right bound of the last bucket.
	 * The equalized histogram never extends its range beyond the data, so this is always the largest value
	 * actually observed.
	 */
	@Nonnull
	@Override
	public BigDecimal getMaxValue() {
		return this.toBigDecimalConverter.apply(this.lastThreshold)
			.setScale(this.limitDecimalPlacesTo, RoundingMode.UP);
	}

	/**
	 * Computes the histogram. See the class documentation for the algorithm and its rationale.
	 *
	 * @param thresholdRetriever function to extract threshold value from source item
	 * @param weightRetriever    function to extract weight (record count) from source item
	 * @return computed buckets, in ascending threshold order
	 */
	@Nonnull
	private CacheableBucket[] computeEqualizedHistogram(
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever
	) {
		// Step 1: aggregate items into distinct values in a single pass. Pre-allocating to the source
		// length avoids a separate counting pass - a distinct value can never outnumber the source items.
		final int[] distinctThresholds = new int[this.sourceData.length];
		final int[] distinctWeights = new int[this.sourceData.length];

		int distinctCount = 0;
		long totalWeight = 0;
		int prevThreshold = Integer.MIN_VALUE;

		for (int i = 0; i < this.sourceData.length; i++) {
			final int currentThreshold = thresholdRetriever.applyAsInt(this.sourceData[i]);
			final int currentWeight = weightRetriever.applyAsInt(this.sourceData[i]);
			totalWeight += currentWeight;

			if (i == 0 || currentThreshold != prevThreshold) {
				distinctThresholds[distinctCount] = currentThreshold;
				distinctWeights[distinctCount] = currentWeight;
				distinctCount++;
				prevThreshold = currentThreshold;
			} else {
				distinctWeights[distinctCount - 1] += currentWeight;
			}
		}

		// callers hand over buckets that were already narrowed to the filtered record set, and empty ones
		// are dropped there - a zero-weight input would make the quantile function undefined
		Assert.isPremiseValid(
			totalWeight > 0,
			() -> "Source data of " + this.histogramType + " carries no weight - the quantile function is undefined!"
		);
		// the quantile walk compares the running cumulative weight times `bucketCount` against `rank * totalWeight` in
		// long arithmetic. Both factors are bounded by Integer.MAX_VALUE - `bucketCount` by its own type, and
		// `totalWeight` because it counts int-addressed records - so the widest product is
		// `(2^31 - 1)^2 = 4.61e18`, comfortably half of Long.MAX_VALUE. That bound comes from the callers
		// rather than from anything visible here, so it is asserted once instead of paid for with 128-bit
		// arithmetic on every one of the comparisons.
		Assert.isPremiseValid(
			totalWeight <= Integer.MAX_VALUE,
			() -> "Source data of " + this.histogramType + " carries a total weight above Integer.MAX_VALUE - " +
				"the quantile walk would overflow!"
		);

		// Step 2: a single distinct value is one indivisible plateau - there is nothing to equalize and
		// nothing to compare its height against, so it takes the whole scale
		if (distinctCount == 1) {
			return new CacheableBucket[]{
				new CacheableBucket(
					this.toBigDecimalConverter.apply(this.firstThreshold)
						.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
					Math.toIntExact(totalWeight),
					ONE_HUNDRED
				)
			};
		}

		// Step 3: place bucket boundaries on the empirical quantile function. The cumulative weight the walk
		// compares against is carried as a running scalar rather than materialized - the walk index only ever
		// moves forward, so a `long[distinctCount + 1]` prefix table would be read strictly left to right.
		final int[] bucketStarts = new int[Math.min(this.bucketCount, distinctCount)];
		final int actualBucketCount = computeBucketStarts(
			distinctWeights, distinctCount, totalWeight, bucketStarts
		);

		// Step 4: one walk over the distinct values folds every bucket's weight and both accumulators the
		// bandwidth needs - see accumulateTotals for why these three share a pass
		final int[] bucketCounts = new int[actualBucketCount];
		final double[] cappedWeights = new double[distinctCount];
		final DistinctValueTotals totals = accumulateTotals(
			distinctThresholds, distinctWeights, distinctCount, totalWeight,
			bucketStarts, actualBucketCount, bucketCounts, cappedWeights
		);

		// Step 5: locate the observation each bar height is measured at
		final int[] representativeIndexes = new int[actualBucketCount];
		locateRepresentatives(
			distinctWeights, distinctCount, bucketStarts, actualBucketCount,
			bucketCounts, representativeIndexes
		);

		// Step 6: one global density curve over the value axis, sampled only where the bars actually sit
		final double bandwidth = computeBandwidth(
			distinctThresholds, distinctWeights, distinctCount, totalWeight, totals, cappedWeights
		);
		final double[] representativeMasses = new double[actualBucketCount];
		final double peakMass = computeKernelMasses(
			distinctThresholds, distinctWeights, distinctCount, bandwidth,
			representativeIndexes, actualBucketCount, representativeMasses
		);

		// every distinct value contributes its own full weight to its own evaluation point, so the peak is
		// strictly positive for any positive total weight
		Assert.isPremiseValid(
			peakMass > 0.0,
			() -> "Kernel density of " + this.histogramType + " collapsed to zero everywhere!"
		);

		// Step 7: materialize the buckets
		final CacheableBucket[] result = new CacheableBucket[actualBucketCount];
		for (int i = 0; i < actualBucketCount; i++) {
			final BigDecimal threshold = this.toBigDecimalConverter.apply(distinctThresholds[bucketStarts[i]])
				.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP);

			// the 1 / (N * h) factor of the density cancels in the ratio, so the raw kernel masses are
			// normalized against each other directly. The clamp is floating-point hygiene: the kernel mass
			// is a difference of two running sums and can land a few ulps outside [0, peakMass] even though
			// the exact value never does.
			final double intensity = Math.min(
				100.0, Math.max(0.0, 100.0 * representativeMasses[i] / peakMass)
			);
			final BigDecimal rounded = BigDecimal.valueOf(intensity).setScale(2, RoundingMode.HALF_UP);

			result[i] = new CacheableBucket(
				threshold,
				bucketCounts[i],
				rounded.signum() == 0 ? MINIMAL_RELATIVE_FREQUENCY : rounded
			);
		}

		return result;
	}

	/**
	 * Walks the empirical quantile function and writes the index of the distinct value that opens each
	 * bucket into `bucketStarts`.
	 *
	 * For every rank `k / bucketCount` the walk advances to the first distinct value whose cumulative
	 * weight reaches that rank. The comparison `C[j+1] * bucketCount > k * totalWeight` is the
	 * multiplied-out form of `C[j+1] / totalWeight > k / bucketCount` and is exact in `long` arithmetic,
	 * so a boundary can never be decided by a rounding artefact. The strictness is what makes this the
	 * value *containing* the rank - see the comment on the walk itself, which realizes it as the negated
	 * `<=` loop condition.
	 *
	 * `C[j+1]` is carried as the running scalar `cumulativeThroughWalk` rather than read out of a prefix
	 * table: `walkIndex` never moves backwards, so the table would be consumed strictly left to right and
	 * exists only to be read once per entry. Accumulating it in the same left-to-right order gives
	 * bit-identical sums for one `long[distinctCount + 1]` less per histogram.
	 *
	 * Consecutive ranks landing on the same distinct value are the normal case for retail pricing, where a
	 * single price can hold a large share of the catalogue. Such a value is emitted once; if it absorbed
	 * two or more ranks it additionally emits the *following* distinct value, which closes its mass into a
	 * bucket of its own rather than letting it spill into the next bucket. The extra threshold is skipped
	 * when it would duplicate the next start or when no following value exists.
	 *
	 * @param distinctWeights weight of each distinct value
	 * @param distinctCount   number of distinct values, at least 2
	 * @param totalWeight     total weight of all observations
	 * @param bucketStarts    output array, sized `min(bucketCount, distinctCount)`; receives strictly
	 *                        increasing distinct-value indices
	 * @return number of bucket starts written into `bucketStarts`
	 */
	private int computeBucketStarts(
		@Nonnull int[] distinctWeights,
		int distinctCount,
		long totalWeight,
		@Nonnull int[] bucketStarts
	) {
		int startCount = 0;
		int walkIndex = 0;
		// the cumulative weight through `walkIndex` inclusive - i.e. `C[walkIndex + 1]` of the prefix table
		// this replaces. Kept in step with `walkIndex` by every advance below.
		long cumulativeThroughWalk = distinctWeights[0];
		// the start whose absorbed-rank count is still growing; it can only be emitted once the next
		// start is known, because the isolation rule must not duplicate it
		int pendingStart = 0;
		int pendingAbsorbed = 0;

		int rank = 0;
		while (rank < this.bucketCount) {
			// Strict `>` is what makes this the value that *contains* the rank rather than the one that
			// *ends* at it. The two agree whenever the cumulative weight steps past `rank * N / B` mid-value,
			// which is the usual case on irregular data - but on evenly weighted values every rank falls
			// exactly on a boundary, and taking the value that ends there puts every cut one value early:
			// six values of weight seven into three buckets yields 7/14/21 rather than 14/14/14, and twenty
			// equal plateaus into twenty buckets yields nineteen.
			// Bounded by distinctCount - 1: the last value's cumulative weight equals totalWeight, and
			// totalWeight * bucketCount <= rank * totalWeight is false for every rank < bucketCount.
			while (walkIndex < distinctCount - 1
				&& cumulativeThroughWalk * this.bucketCount <= (long) rank * totalWeight) {
				walkIndex++;
				cumulativeThroughWalk += distinctWeights[walkIndex];
			}

			// The walk lands on this value for every rank below `C[walkIndex + 1] * B / N`, so the whole run
			// is taken in one step instead of one iteration per rank. Without this the loop would cost
			// O(bucketCount) even when a handful of distinct values absorb everything - and the caller chooses
			// bucketCount. `(x - 1) / N` is the largest integer rank with `rank * N < x`, the exact inverse of
			// the strict comparison above; the step is therefore always at least one, so the walk index
			// strictly increases on every following iteration and the loop visits at most `distinctCount`
			// values.
			final long lastRankOnThisValue = walkIndex < distinctCount - 1
				? (cumulativeThroughWalk * this.bucketCount - 1) / totalWeight
				: this.bucketCount - 1L;
			final int nextRank = (int) Math.min(this.bucketCount, lastRankOnThisValue + 1);

			if (pendingAbsorbed > 0) {
				final int closedStart = pendingStart;
				// the run batching consumes every rank that resolves to `pendingStart` in one step, so the
				// walk must have moved on by now - a repeated start would mean a rank run was split across
				// two iterations and the absorbed count silently under-reported
				Assert.isPremiseValid(
					walkIndex > closedStart,
					() -> "Quantile walk of " + this.histogramType + " failed to advance past distinct value " +
						closedStart + " between two rank runs!"
				);
				startCount = emitStart(
					bucketStarts, startCount, pendingStart, pendingAbsorbed, walkIndex, distinctCount
				);
			}
			pendingStart = walkIndex;
			// saturate: only "absorbed at least two ranks" is ever asked, and a huge bucketCount over few
			// distinct values would otherwise overflow the counter
			pendingAbsorbed = Math.min(nextRank - rank, MINIMUM_ABSORBED_RANKS_TO_ISOLATE);
			rank = nextRank;
		}

		// close the final start - there is no following start to collide with, so pass an index that can
		// never equal `pendingStart + 1`
		return emitStart(
			bucketStarts, startCount, pendingStart, pendingAbsorbed, distinctCount, distinctCount
		);
	}

	/**
	 * Appends one bucket start to `bucketStarts` together with the isolation threshold it may owe.
	 *
	 * @param bucketStarts  output array of bucket start indices
	 * @param startCount    number of entries already written
	 * @param start         index of the distinct value opening the bucket
	 * @param absorbed      number of quantile ranks that landed on `start`, saturated at 2 - only "two or more"
	 *                      is ever asked
	 * @param nextStart     index of the distinct value opening the following bucket, or `distinctCount`
	 *                      when `start` is the last one
	 * @param distinctCount total number of distinct values
	 * @return the new number of entries in `bucketStarts`
	 */
	private static int emitStart(
		@Nonnull int[] bucketStarts,
		int startCount,
		int start,
		int absorbed,
		int nextStart,
		int distinctCount
	) {
		int count = startCount;
		bucketStarts[count++] = start;
		// a value that swallowed two or more ranks was charged for them - give the mass its own bucket by
		// re-opening at the next distinct value, unless that value already opens the following bucket
		if (absorbed >= MINIMUM_ABSORBED_RANKS_TO_ISOLATE && start + 1 < distinctCount && start + 1 != nextStart) {
			bucketStarts[count++] = start + 1;
		}
		return count;
	}

	/**
	 * The two whole-axis accumulators that {@link #computeBandwidth} needs, produced by the same walk that
	 * folds the bucket weights.
	 *
	 * @param weightedSum  `Σ w_i · v_i` over every distinct value, the numerator of the weighted mean
	 * @param cappedTotal  `Σ min(w_i, (N − w_i) / 2)` (floored), the denominator of the capped rank axis
	 */
	private record DistinctValueTotals(
		double weightedSum,
		double cappedTotal
	) {
	}

	/**
	 * Folds every bucket's weight and both whole-axis accumulators in a single walk over the distinct values.
	 *
	 * The three have nothing to do with each other mathematically; they share a pass because they share a
	 * traversal. At a hundred thousand distinct values the weight and threshold arrays are several hundred
	 * kilobytes each, so a pass is a memory-bandwidth cost rather than an arithmetic one, and the bucket
	 * boundary test costs one predictable branch per value against the read stream it saves.
	 *
	 * Bucket `i` spans `[bucketStarts[i], bucketStarts[i + 1])`, the last one running to `distinctCount`.
	 * `bucketStarts[0]` is the first distinct value the quantile walk reaches with a positive cumulative
	 * weight, so every value below it necessarily carries zero weight - no positive weight ever escapes a
	 * bucket, and skipping those values subtracts nothing from the totals. They are still walked for the
	 * accumulators, which describe the whole axis rather than the buckets.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param distinctWeights    weight of each distinct value
	 * @param distinctCount      number of distinct values
	 * @param totalWeight        total weight of all observations
	 * @param bucketStarts       distinct-value index opening each bucket
	 * @param actualBucketCount  number of buckets
	 * @param bucketCounts       output: total weight of each bucket
	 * @param cappedWeights      output: the robust-spread weight of each distinct value, see
	 *                           {@link #cappedWeight}
	 * @return the whole-axis accumulators
	 */
	@Nonnull
	private static DistinctValueTotals accumulateTotals(
		@Nonnull int[] distinctThresholds,
		@Nonnull int[] distinctWeights,
		int distinctCount,
		long totalWeight,
		@Nonnull int[] bucketStarts,
		int actualBucketCount,
		@Nonnull int[] bucketCounts,
		@Nonnull double[] cappedWeights
	) {
		final double observationCount = totalWeight;
		double weightedSum = 0.0;
		double cappedTotal = 0.0;

		int nextBucket = 0;
		int currentBucket = -1;
		long bucketWeight = 0;

		for (int i = 0; i < distinctCount; i++) {
			weightedSum += (double) distinctWeights[i] * distinctThresholds[i];
			cappedWeights[i] = cappedWeight(distinctWeights[i], observationCount);
			cappedTotal += cappedWeights[i];

			if (nextBucket < actualBucketCount && i == bucketStarts[nextBucket]) {
				if (currentBucket >= 0) {
					bucketCounts[currentBucket] = Math.toIntExact(bucketWeight);
				}
				currentBucket = nextBucket++;
				bucketWeight = 0;
			}
			if (currentBucket >= 0) {
				bucketWeight += distinctWeights[i];
			}
		}
		if (currentBucket >= 0) {
			bucketCounts[currentBucket] = Math.toIntExact(bucketWeight);
		}

		return new DistinctValueTotals(weightedSum, cappedTotal);
	}

	/**
	 * Locates the observation each bucket's bar height is measured at.
	 *
	 * The measurement point is the bucket's **weighted median** distinct value - the first value at which
	 * the bucket's own cumulative weight reaches half of the bucket's total. Measuring at the threshold
	 * instead would read the density at the bucket's left edge, which for a bucket dominated by one heavy
	 * value sitting away from that edge is not where its records are.
	 *
	 * The indices written out are strictly increasing, because the bucket ranges are disjoint and ascending
	 * and each representative lies inside its own range. {@link #computeKernelMasses} relies on that to
	 * collect the masses in one forward sweep.
	 *
	 * @param distinctWeights       weight of each distinct value
	 * @param distinctCount         number of distinct values
	 * @param bucketStarts          distinct-value index opening each bucket
	 * @param actualBucketCount     number of buckets
	 * @param bucketCounts          total weight of each bucket, as folded by {@link #accumulateTotals}
	 * @param representativeIndexes output: distinct-value index the height of each bucket is read at
	 */
	private static void locateRepresentatives(
		@Nonnull int[] distinctWeights,
		int distinctCount,
		@Nonnull int[] bucketStarts,
		int actualBucketCount,
		@Nonnull int[] bucketCounts,
		@Nonnull int[] representativeIndexes
	) {
		for (int i = 0; i < actualBucketCount; i++) {
			final int from = bucketStarts[i];
			final int to = i + 1 < actualBucketCount ? bucketStarts[i + 1] - 1 : distinctCount - 1;
			final long bucketWeight = bucketCounts[i];

			long cumulated = 0;
			int representative = from;
			for (int j = from; j <= to; j++) {
				cumulated += distinctWeights[j];
				if (cumulated * 2 >= bucketWeight) {
					representative = j;
					break;
				}
			}
			representativeIndexes[i] = representative;
		}
	}

	/**
	 * The robust spread estimate's weight for one distinct value: `max(min(w, (N − w) / 2), floor)`.
	 *
	 * Evaluated **once per distinct value** in {@link #accumulateTotals}, into an array the rank-axis walk in
	 * {@link #bandAveragedInterQuartileSpread} then reads. (Not the *quantile walk* - that name belongs to
	 * {@link #computeBucketStarts}, which reads the raw weights.)
	 *
	 * Recomputing it at both use sites instead removes that array, and was measured 12% slower over the
	 * whole computation: `Math.min` against a widened `int` plus a division is several times the cost of the
	 * sequential `double[]` load it replaces, and the saving is one array against a per-element price. The
	 * same trade was measured on the shifted-value axis and lost the same way - see {@link #computeKernelMasses}.
	 * See {@link #computeBandwidth} for why the cap is a `min` and not an `if`.
	 *
	 * @param weight           the distinct value's own weight
	 * @param observationCount total weight of all observations, as a double
	 * @return the capped weight, strictly positive
	 */
	private static double cappedWeight(int weight, double observationCount) {
		return Math.max(
			Math.min(weight, (observationCount - weight) / 2.0),
			MINIMAL_CAPPED_WEIGHT
		);
	}

	/**
	 * Computes the support radius `h` of the triangular kernel:
	 * `h = √6 · 0.9 · min(σ_w, IQR_c / 1.34) · D^(−1/5)`.
	 *
	 * `σ_w` is the ordinary weighted standard deviation over all `N` observations - that is what σ means,
	 * and it is not capped. `IQR_c` is the robust alternative, and it differs from the textbook one twice
	 * over, both times to remove a discontinuity that production data actually sits on:
	 *
	 * - **Weights are capped at `min(w, (N − w) / 2)` before the quantiles are taken.** A value holding
	 *   more than half the weight spans the whole interquartile range on its own and drives the IQR to
	 *   zero from the inside, collapsing the bandwidth to nothing. After capping no value exceeds a third
	 *   of the weight in use, which is below the half needed to span both quartiles. Written as a `min`
	 *   rather than an `if (w > N / 2)` on purpose: a hard switch is *discontinuous*, and a live attribute
	 *   histogram sits at 50.75% of its weight on one value - five records from that cliff, where the
	 *   bandwidth stepped by 2.06x. The `min` form starts binding at `w = N / 3` and is continuous
	 *   everywhere. The cap applies to the spread estimate only, never to the histogram itself.
	 * - **The quantiles are band-averaged** rather than point-valued - see
	 *   {@link #bandAveragedInterQuartileSpread}, which reads both off a single walk.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param distinctWeights    weight of each distinct value
	 * @param distinctCount      number of distinct values, at least 2
	 * @param totalWeight        total weight of all observations
	 * @param totals             the whole-axis accumulators folded by {@link #accumulateTotals}
	 * @param cappedWeights      the robust-spread weights filled by {@link #accumulateTotals}
	 * @return strictly positive support radius of the triangular kernel
	 */
	private double computeBandwidth(
		@Nonnull int[] distinctThresholds,
		@Nonnull int[] distinctWeights,
		int distinctCount,
		long totalWeight,
		@Nonnull DistinctValueTotals totals,
		@Nonnull double[] cappedWeights
	) {
		final double observationCount = totalWeight;

		// the weighted sum and the capped total were folded into the bucket walk; the variance cannot join
		// them, because it needs the mean this line produces
		final double mean = totals.weightedSum() / observationCount;

		double weightedSquares = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			final double deviation = distinctThresholds[i] - mean;
			weightedSquares += distinctWeights[i] * deviation * deviation;
		}
		final double standardDeviation = Math.sqrt(weightedSquares / observationCount);

		final double interQuartileRange = bandAveragedInterQuartileSpread(
			distinctThresholds, cappedWeights, distinctCount, totals.cappedTotal()
		) / NORMAL_IQR_CONSTANT;

		// Silverman takes the smaller of the two, but only among the ones that carry information: a tied
		// middle can legitimately produce IQR = 0 while σ is perfectly informative
		final double spread;
		if (standardDeviation > 0.0 && interQuartileRange > 0.0) {
			spread = Math.min(standardDeviation, interQuartileRange);
		} else if (standardDeviation > 0.0) {
			spread = standardDeviation;
		} else {
			spread = interQuartileRange;
		}

		final double bandwidth = TRIANGULAR_SUPPORT_FACTOR * SILVERMAN_FACTOR * spread
			* Math.pow(distinctCount, SILVERMAN_COUNT_EXPONENT);
		// two or more distinct values always have a positive weighted standard deviation
		Assert.isPremiseValid(
			bandwidth > 0.0,
			() -> "Bandwidth of " + this.histogramType + " is not positive although the data holds " +
				"more than one distinct value!"
		);
		return bandwidth;
	}

	/**
	 * Returns `Q̄(UPPER_QUARTILE) − Q̄(LOWER_QUARTILE)`, where `Q̄(p)` is the weighted quantile at rank `p`
	 * averaged over the rank band `[p − QUANTILE_BAND_RADIUS, p + QUANTILE_BAND_RADIUS]` - the L-estimator
	 * `Q̄(p) = 1 / (2r) · ∫ Q(u) du`.
	 *
	 * Both quartiles are read off **one** walk of the rank axis. They are independent integrals over the same
	 * cumulative weights, so evaluating them together costs one extra overlap test per distinct value and
	 * saves a whole pass. Each keeps its own band endpoints and divides by its own `(p + r) − (p − r)`: those
	 * two widths are equal in exact arithmetic but not necessarily bit-identical in floating point, and
	 * collapsing them onto a shared `2r` would silently move the result.
	 *
	 * The per-value weights are read from the array {@link #accumulateTotals} filled - see
	 * {@link #cappedWeight} for why they are materialized rather than recomputed here.
	 *
	 * A point-valued quantile is a step function of the weights, so a quartile crossing a value boundary
	 * moves by a whole gap at once. On values `0, 1, 2, G` with equal weights, moving a *single* record out
	 * of a hundred takes the nearest-rank IQR from `2` to `G` and the bandwidth with it - measured at
	 * 293 885x for `G = 10⁶`. Averaging over a band makes the estimate a continuous function of the weights
	 * (the band endpoints move continuously and the integral is continuous in them), which removes the
	 * cliff. When the whole band falls inside one value's rank interval this returns exactly that value,
	 * matching the nearest-rank quantile.
	 *
	 * It is deliberately **not** linear interpolation between neighbouring order statistics, which is also
	 * continuous but reaches *into* the gap and so invents a value the data does not contain: on a live
	 * five-product category whose most expensive item costs 24 691x the cheapest, interpolation placed the
	 * upper quartile inside the gap and returned a bandwidth of 368 374, flattening the whole profile.
	 *
	 * Requires `QUANTILE_BAND_RADIUS &lt;= p &lt;= 1 − QUANTILE_BAND_RADIUS`, which both quartiles satisfy, so
	 * the band never has to be clipped to `[0, 1]`.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param cappedWeights      the robust-spread weight of each distinct value, as filled by
	 *                           {@link #accumulateTotals}
	 * @param distinctCount      number of distinct values
	 * @param cappedTotal        sum of `cappedWeights`, strictly positive
	 * @return the band-averaged interquartile spread, before the normal-consistency division
	 */
	private static double bandAveragedInterQuartileSpread(
		@Nonnull int[] distinctThresholds,
		@Nonnull double[] cappedWeights,
		int distinctCount,
		double cappedTotal
	) {
		final double lowerBandFrom = LOWER_QUARTILE - QUANTILE_BAND_RADIUS;
		final double lowerBandTo = LOWER_QUARTILE + QUANTILE_BAND_RADIUS;
		final double upperBandFrom = UPPER_QUARTILE - QUANTILE_BAND_RADIUS;
		final double upperBandTo = UPPER_QUARTILE + QUANTILE_BAND_RADIUS;

		double lowerAccumulated = 0.0;
		double upperAccumulated = 0.0;
		double cumulated = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			final double rankFrom = cumulated / cappedTotal;
			cumulated += cappedWeights[i];
			final double rankTo = cumulated / cappedTotal;

			final double lowerOverlap = Math.min(lowerBandTo, rankTo) - Math.max(lowerBandFrom, rankFrom);
			if (lowerOverlap > 0.0) {
				lowerAccumulated += (double) distinctThresholds[i] * lowerOverlap;
			}
			final double upperOverlap = Math.min(upperBandTo, rankTo) - Math.max(upperBandFrom, rankFrom);
			if (upperOverlap > 0.0) {
				upperAccumulated += (double) distinctThresholds[i] * upperOverlap;
			}
		}
		return upperAccumulated / (upperBandTo - upperBandFrom)
			- lowerAccumulated / (lowerBandTo - lowerBandFrom);
	}

	/**
	 * Evaluates the un-normalized triangular kernel mass `Σ w_j · (1 − |x − v_j| / h)` at every distinct
	 * value. The `1 / (N · h)` factor that would turn this into a probability density is a constant and
	 * cancels when the curve is normalized against its own maximum, so it is not applied.
	 *
	 * Because the kernel has compact support and both the evaluation points and the observations are the
	 * same ascending sequence, the whole curve is produced in `O(D)` by sliding one window: the sum is
	 * rewritten as `Σw − (1 / h) · Σ w_j |x − v_j|`, and the absolute value is split at `x` into a left
	 * part `x · Σw_left − Σ(w·v)_left` and a right part `Σ(w·v)_right − x · Σw_right`, each maintained
	 * incrementally. A direct evaluation would cost `O(D²)`.
	 *
	 * Values are shifted to be relative to the first distinct value before the running sums are formed,
	 * which keeps the magnitudes of `Σ w·v` proportional to the data's own range rather than to its
	 * absolute position and preserves precision for catalogues clustered far from zero. The shifted axis is
	 * **materialized** into a `double[distinctCount]`: deriving it on demand instead saves the array but was
	 * measured 12% slower, because the three window pointers read each entry several times and each read then
	 * pays two int-to-double conversions and a subtract in place of one sequential load. See the note on
	 * {@link #cappedWeight} - the same trade was measured on the other scratch array and lost the same way.
	 *
	 * Only `actualBucketCount` of the `distinctCount` masses are ever read - the rest exist solely to find
	 * the peak the curve is normalized against. The sweep therefore keeps the peak in a scalar and copies
	 * out only the masses at `representativeIndexes`, which is why that array must be strictly increasing.
	 * The curve itself is still evaluated everywhere, so the peak is unchanged.
	 *
	 * @param distinctThresholds    distinct values in ascending order
	 * @param distinctWeights       weight of each distinct value
	 * @param distinctCount         number of distinct values
	 * @param bandwidth             support radius of the kernel, strictly positive
	 * @param representativeIndexes strictly increasing distinct-value indices the bars are measured at
	 * @param actualBucketCount     number of buckets, i.e. the length of `representativeIndexes`
	 * @param representativeMasses  output: kernel mass at each representative, in bucket order
	 * @return the maximum kernel mass over the whole curve
	 */
	private static double computeKernelMasses(
		@Nonnull int[] distinctThresholds,
		@Nonnull int[] distinctWeights,
		int distinctCount,
		double bandwidth,
		@Nonnull int[] representativeIndexes,
		int actualBucketCount,
		@Nonnull double[] representativeMasses
	) {
		final int origin = distinctThresholds[0];
		final double[] values = new double[distinctCount];
		for (int i = 0; i < distinctCount; i++) {
			values[i] = (double) distinctThresholds[i] - origin;
		}

		// window invariant: [lower, middle) holds the observations at or below x, [middle, upper) those
		// above it; everything outside [x - h, x + h] contributes exactly zero and is kept out
		int lower = 0;
		int middle = 0;
		int upper = 0;
		double leftWeight = 0.0;
		double leftWeightedValue = 0.0;
		double rightWeight = 0.0;
		double rightWeightedValue = 0.0;

		double peakMass = 0.0;
		int nextRepresentative = 0;

		for (int q = 0; q < distinctCount; q++) {
			final double x = values[q];

			// each loop derives its entry's shifted value exactly once and tests the derived value, rather
			// than deriving it again in the loop condition - the conditions are negated accordingly, which is
			// equivalent because no value here can be NaN (the thresholds are ints and the bandwidth is
			// asserted finite and positive)
			final double rightEdge = x + bandwidth;
			final double leftEdge = x - bandwidth;

			// admit everything that entered the right edge of the window
			while (upper < distinctCount) {
				final double value = values[upper];
				if (value >= rightEdge) {
					break;
				}
				rightWeight += distinctWeights[upper];
				rightWeightedValue += distinctWeights[upper] * value;
				upper++;
			}
			// move everything at or below x from the right half into the left half
			while (middle < upper) {
				final double value = values[middle];
				if (value > x) {
					break;
				}
				rightWeight -= distinctWeights[middle];
				rightWeightedValue -= distinctWeights[middle] * value;
				leftWeight += distinctWeights[middle];
				leftWeightedValue += distinctWeights[middle] * value;
				middle++;
			}
			// drop everything that fell out of the left edge of the window
			while (lower < middle) {
				final double value = values[lower];
				if (value > leftEdge) {
					break;
				}
				leftWeight -= distinctWeights[lower];
				leftWeightedValue -= distinctWeights[lower] * value;
				lower++;
			}

			final double absoluteDeviationSum =
				(x * leftWeight - leftWeightedValue) + (rightWeightedValue - x * rightWeight);
			final double kernelMass = (leftWeight + rightWeight) - absoluteDeviationSum / bandwidth;

			peakMass = Math.max(peakMass, kernelMass);
			if (nextRepresentative < actualBucketCount && q == representativeIndexes[nextRepresentative]) {
				representativeMasses[nextRepresentative++] = kernelMass;
			}
		}

		// the sweep visits every distinct value once in ascending order, so a representative it failed to
		// collect means `representativeIndexes` was not strictly increasing or pointed outside the curve -
		// either way the bars below would silently read a zero mass
		final int collectedRepresentatives = nextRepresentative;
		Assert.isPremiseValid(
			collectedRepresentatives == actualBucketCount,
			() -> "Kernel density sweep collected " + collectedRepresentatives + " of " + actualBucketCount +
				" bucket representatives - the representative indices are not strictly increasing!"
		);

		return peakMass;
	}

}
