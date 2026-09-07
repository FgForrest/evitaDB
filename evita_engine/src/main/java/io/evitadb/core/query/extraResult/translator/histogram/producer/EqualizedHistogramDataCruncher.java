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
 *    the rank `k / bucketCount`, compared in exact integer arithmetic (`C[j+1] * B >= k * N`) so the
 *    boundary never depends on floating-point rounding,
 * 3. when several consecutive targets land on the *same* distinct value — which is what a price held by
 *    many products does — emit that value once and, if it absorbed two or more targets, additionally emit
 *    the *next* distinct value. That closes the heavy value's mass into a bucket of its own instead of
 *    letting it bleed into the following one.
 *
 * The result therefore contains **no empty buckets and no repeated thresholds**: every threshold is a real,
 * selectable value, so every slider position yields a different result set. It may legitimately contain
 * **fewer** than `bucketCount` buckets — when a value is held by many records, there is simply no distinct
 * value to split the interval at. Callers must not assume otherwise.
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
 * `f(F⁻¹(u))`. Estimating it from the single gap between two adjacent values (which is what this class did
 * before) makes it swing by orders of magnitude when one record is repriced, so it is instead read off one
 * global kernel density estimate over the whole value axis:
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
	 * {@link #bandAveragedQuantile}. Must stay small enough that `quartile ± radius` remains inside
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

		// Step 3: place bucket boundaries on the empirical quantile function
		final long[] cumulativeWeights = new long[distinctCount + 1];
		for (int i = 0; i < distinctCount; i++) {
			cumulativeWeights[i + 1] = cumulativeWeights[i] + distinctWeights[i];
		}
		final int[] bucketStarts = new int[Math.min(this.bucketCount, distinctCount)];
		final int actualBucketCount = computeBucketStarts(
			cumulativeWeights, distinctCount, totalWeight, bucketStarts
		);

		// Step 4: fold each bucket's weight and locate the observation the bar height is measured at
		final int[] bucketCounts = new int[actualBucketCount];
		final int[] representativeIndexes = new int[actualBucketCount];
		collectBucketWeights(
			distinctWeights, distinctCount, bucketStarts, actualBucketCount,
			bucketCounts, representativeIndexes
		);

		// Step 5: one global density curve over the value axis, read at each bucket's representative
		final double bandwidth = computeBandwidth(
			distinctThresholds, distinctWeights, distinctCount, totalWeight
		);
		final double[] kernelMasses = computeKernelMasses(
			distinctThresholds, distinctWeights, distinctCount, bandwidth
		);

		double peakMass = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			peakMass = Math.max(peakMass, kernelMasses[i]);
		}
		// every distinct value contributes its own full weight to its own evaluation point, so the peak is
		// strictly positive for any positive total weight
		Assert.isPremiseValid(
			peakMass > 0.0,
			() -> "Kernel density of " + this.histogramType + " collapsed to zero everywhere!"
		);

		// Step 6: materialize the buckets
		final CacheableBucket[] result = new CacheableBucket[actualBucketCount];
		for (int i = 0; i < actualBucketCount; i++) {
			final BigDecimal threshold = this.toBigDecimalConverter.apply(distinctThresholds[bucketStarts[i]])
				.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP);

			// the 1 / (N * h) factor of the density cancels in the ratio, so the raw kernel masses are
			// normalized against each other directly. The clamp is floating-point hygiene: the kernel mass
			// is a difference of two running sums and can land a few ulps outside [0, peakMass] even though
			// the exact value never does.
			final double intensity = Math.min(
				100.0, Math.max(0.0, 100.0 * kernelMasses[representativeIndexes[i]] / peakMass)
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
	 * weight reaches that rank. The comparison `C[j+1] * bucketCount >= k * totalWeight` is the
	 * multiplied-out form of `C[j+1] / totalWeight >= k / bucketCount` and is exact in `long` arithmetic,
	 * so a boundary can never be decided by a rounding artefact.
	 *
	 * Consecutive ranks landing on the same distinct value are the normal case for retail pricing, where a
	 * single price can hold a large share of the catalogue. Such a value is emitted once; if it absorbed
	 * two or more ranks it additionally emits the *following* distinct value, which closes its mass into a
	 * bucket of its own rather than letting it spill into the next bucket. The extra threshold is skipped
	 * when it would duplicate the next start or when no following value exists.
	 *
	 * @param cumulativeWeights running weight totals, `cumulativeWeights[i]` being the weight below
	 *                          distinct index `i`; length `distinctCount + 1`
	 * @param distinctCount     number of distinct values, at least 2
	 * @param totalWeight       total weight of all observations
	 * @param bucketStarts      output array, sized `min(bucketCount, distinctCount)`; receives strictly
	 *                          increasing distinct-value indices
	 * @return number of bucket starts written into `bucketStarts`
	 */
	private int computeBucketStarts(
		@Nonnull long[] cumulativeWeights,
		int distinctCount,
		long totalWeight,
		@Nonnull int[] bucketStarts
	) {
		int startCount = 0;
		int walkIndex = 0;
		// the start whose absorbed-rank count is still growing; it can only be emitted once the next
		// start is known, because the isolation rule must not duplicate it
		int pendingStart = 0;
		int pendingAbsorbed = 0;

		int rank = 0;
		while (rank < this.bucketCount) {
			// bounded by distinctCount - 1: the last value's cumulative weight equals totalWeight, and
			// totalWeight * bucketCount < k * totalWeight is false for every k < bucketCount
			while (walkIndex < distinctCount - 1
				&& cumulativeWeights[walkIndex + 1] * this.bucketCount < (long) rank * totalWeight) {
				walkIndex++;
			}

			// The walk lands on this value for every rank up to `C[walkIndex + 1] * B / N`, so the whole run
			// is taken in one step instead of one iteration per rank. Without this the loop would cost
			// O(bucketCount) even when a handful of distinct values absorb everything - and the caller chooses
			// bucketCount. The step is always at least one, because the rank just resolved lands here by
			// construction, so the walk index strictly increases on every following iteration and the loop
			// visits at most `distinctCount` values.
			final long lastRankOnThisValue = walkIndex < distinctCount - 1
				? cumulativeWeights[walkIndex + 1] * this.bucketCount / totalWeight
				: this.bucketCount - 1L;
			final int nextRank = (int) Math.min(this.bucketCount, lastRankOnThisValue + 1);

			if (pendingAbsorbed > 0 && walkIndex != pendingStart) {
				startCount = emitStart(
					bucketStarts, startCount, pendingStart, pendingAbsorbed, walkIndex, distinctCount
				);
				pendingAbsorbed = 0;
			}
			pendingStart = walkIndex;
			// saturate: only "absorbed at least two ranks" is ever asked, and a huge bucketCount over few
			// distinct values would otherwise overflow the counter
			pendingAbsorbed = Math.min(pendingAbsorbed + (nextRank - rank), 2);
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
		if (absorbed >= 2 && start + 1 < distinctCount && start + 1 != nextStart) {
			bucketStarts[count++] = start + 1;
		}
		return count;
	}

	/**
	 * Folds the weight of every bucket and locates the observation its bar height is measured at.
	 *
	 * The measurement point is the bucket's **weighted median** distinct value - the first value at which
	 * the bucket's own cumulative weight reaches half of the bucket's total. Measuring at the threshold
	 * instead would read the density at the bucket's left edge, which for a bucket dominated by one heavy
	 * value sitting away from that edge is not where its records are.
	 *
	 * @param distinctWeights       weight of each distinct value
	 * @param distinctCount         number of distinct values
	 * @param bucketStarts          distinct-value index opening each bucket
	 * @param actualBucketCount     number of buckets
	 * @param bucketCounts          output: total weight of each bucket
	 * @param representativeIndexes output: distinct-value index the height of each bucket is read at
	 */
	private static void collectBucketWeights(
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

			long bucketWeight = 0;
			for (int j = from; j <= to; j++) {
				bucketWeight += distinctWeights[j];
			}
			bucketCounts[i] = Math.toIntExact(bucketWeight);

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
	 * - **The quantiles are band-averaged** rather than point-valued - see {@link #bandAveragedQuantile}.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param distinctWeights    weight of each distinct value
	 * @param distinctCount      number of distinct values, at least 2
	 * @param totalWeight        total weight of all observations
	 * @return strictly positive support radius of the triangular kernel
	 */
	private double computeBandwidth(
		@Nonnull int[] distinctThresholds,
		@Nonnull int[] distinctWeights,
		int distinctCount,
		long totalWeight
	) {
		final double observationCount = totalWeight;

		double weightedSum = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			weightedSum += (double) distinctWeights[i] * distinctThresholds[i];
		}
		final double mean = weightedSum / observationCount;

		double weightedSquares = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			final double deviation = distinctThresholds[i] - mean;
			weightedSquares += distinctWeights[i] * deviation * deviation;
		}
		final double standardDeviation = Math.sqrt(weightedSquares / observationCount);

		final double[] cappedWeights = new double[distinctCount];
		double cappedTotal = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			cappedWeights[i] = Math.max(
				Math.min(distinctWeights[i], (observationCount - distinctWeights[i]) / 2.0),
				MINIMAL_CAPPED_WEIGHT
			);
			cappedTotal += cappedWeights[i];
		}
		final double interQuartileRange = (
			bandAveragedQuantile(distinctThresholds, cappedWeights, distinctCount, cappedTotal, UPPER_QUARTILE) -
				bandAveragedQuantile(distinctThresholds, cappedWeights, distinctCount, cappedTotal, LOWER_QUARTILE)
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
	 * Returns the weighted quantile at rank `p`, averaged over the rank band
	 * `[p − QUANTILE_BAND_RADIUS, p + QUANTILE_BAND_RADIUS]` - the L-estimator
	 * `Q̄(p) = 1 / (2r) · ∫ Q(u) du`.
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
	 * Requires `QUANTILE_BAND_RADIUS <= p <= 1 − QUANTILE_BAND_RADIUS`, which both quartiles satisfy, so
	 * the band never has to be clipped to `[0, 1]`.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param cappedWeights      per-value weights the rank axis is built from
	 * @param distinctCount      number of distinct values
	 * @param cappedTotal        sum of `cappedWeights`, strictly positive
	 * @param p                  rank of the requested quantile
	 * @return the band-averaged quantile value
	 */
	private static double bandAveragedQuantile(
		@Nonnull int[] distinctThresholds,
		@Nonnull double[] cappedWeights,
		int distinctCount,
		double cappedTotal,
		double p
	) {
		final double lowerRank = p - QUANTILE_BAND_RADIUS;
		final double upperRank = p + QUANTILE_BAND_RADIUS;

		double accumulated = 0.0;
		double cumulated = 0.0;
		for (int i = 0; i < distinctCount; i++) {
			final double rankFrom = cumulated / cappedTotal;
			cumulated += cappedWeights[i];
			final double rankTo = cumulated / cappedTotal;

			final double overlap = Math.min(upperRank, rankTo) - Math.max(lowerRank, rankFrom);
			if (overlap > 0.0) {
				accumulated += (double) distinctThresholds[i] * overlap;
			}
		}
		return accumulated / (upperRank - lowerRank);
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
	 * absolute position and preserves precision for catalogues clustered far from zero.
	 *
	 * @param distinctThresholds distinct values in ascending order
	 * @param distinctWeights    weight of each distinct value
	 * @param distinctCount      number of distinct values
	 * @param bandwidth          support radius of the kernel, strictly positive
	 * @return kernel mass at each distinct value, in the same order
	 */
	@Nonnull
	private static double[] computeKernelMasses(
		@Nonnull int[] distinctThresholds,
		@Nonnull int[] distinctWeights,
		int distinctCount,
		double bandwidth
	) {
		final double[] values = new double[distinctCount];
		final int origin = distinctThresholds[0];
		for (int i = 0; i < distinctCount; i++) {
			values[i] = (double) distinctThresholds[i] - origin;
		}

		final double[] kernelMasses = new double[distinctCount];

		// window invariant: [lower, middle) holds the observations at or below x, [middle, upper) those
		// above it; everything outside [x - h, x + h] contributes exactly zero and is kept out
		int lower = 0;
		int middle = 0;
		int upper = 0;
		double leftWeight = 0.0;
		double leftWeightedValue = 0.0;
		double rightWeight = 0.0;
		double rightWeightedValue = 0.0;

		for (int q = 0; q < distinctCount; q++) {
			final double x = values[q];

			// admit everything that entered the right edge of the window
			while (upper < distinctCount && values[upper] < x + bandwidth) {
				rightWeight += distinctWeights[upper];
				rightWeightedValue += distinctWeights[upper] * values[upper];
				upper++;
			}
			// move everything at or below x from the right half into the left half
			while (middle < upper && values[middle] <= x) {
				rightWeight -= distinctWeights[middle];
				rightWeightedValue -= distinctWeights[middle] * values[middle];
				leftWeight += distinctWeights[middle];
				leftWeightedValue += distinctWeights[middle] * values[middle];
				middle++;
			}
			// drop everything that fell out of the left edge of the window
			while (lower < middle && values[lower] <= x - bandwidth) {
				leftWeight -= distinctWeights[lower];
				leftWeightedValue -= distinctWeights[lower] * values[lower];
				lower++;
			}

			final double absoluteDeviationSum =
				(x * leftWeight - leftWeightedValue) + (rightWeightedValue - x * rightWeight);
			kernelMasses[q] = (leftWeight + rightWeight) - absoluteDeviationSum / bandwidth;
		}

		return kernelMasses;
	}

}
