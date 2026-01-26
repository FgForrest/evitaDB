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
 * This class computes histogram using cumulative frequency distribution to position bucket boundaries.
 * Unlike {@link HistogramDataCruncher} which uses equal-width buckets, this class positions bucket
 * boundaries so that each bucket covers approximately equal portion of total records.
 *
 * This approach is beneficial when data is heavily skewed - for example, if 90% of products cost
 * between $10-$50 and 10% cost $50-$1000, equal-width buckets would place most records in the first
 * bucket. Equalized buckets distribute records more evenly across buckets.
 *
 * ## Algorithm
 *
 * 1. Calculate total weight (sum of all record counts)
 * 2. Calculate cumulative frequency for each unique value
 * 3. Position bucket boundaries at points where cumulative frequency crosses threshold (i/bucketCount)
 * 4. Count actual occurrences in each resulting bucket
 *
 * ## Optimization Note
 *
 * Unlike {@link HistogramDataCruncher} which benefits from a separate `createOptimalHistogram` factory
 * method to reduce empty buckets, the equalized histogram algorithm **inherently avoids empty buckets**
 * because bucket boundaries are placed at actual data value transitions based on cumulative frequency.
 * Therefore, using {@link BucketCountMode#ADAPTIVE} provides full optimization for equalized histograms
 * without requiring any additional recomputation logic.
 *
 * @param <T> the type of source data elements
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EqualizedHistogramDataCruncher<T> implements HistogramDataCruncherContract<T> {

	/**
	 * Defines how the histogram handles the case when fewer distinct values exist
	 * than the requested bucket count.
	 */
	public enum BucketCountMode {
		/**
		 * Returns exactly the requested number of buckets.
		 * If data has fewer distinct values than requested buckets, empty buckets are added:
		 * first distributed proportionally across gaps between data buckets, then any
		 * remaining buckets extend the histogram range beyond the last data value.
		 */
		EXACT,
		/**
		 * Return fewer buckets when data is sparse (fewer distinct values than
		 * requested buckets). This avoids unnecessary empty buckets.
		 *
		 * For equalized histograms, ADAPTIVE mode provides full optimization because
		 * the cumulative frequency algorithm inherently places bucket boundaries at
		 * data value transitions, resulting in non-empty buckets. No additional
		 * recomputation is needed beyond what ADAPTIVE already provides.
		 */
		ADAPTIVE
	}

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
	 * Internal variable containing the extended max threshold for EXACT mode.
	 * May be greater than lastThreshold when range is extended to fit requested bucket count.
	 */
	private int extendedMaxThreshold;
	/**
	 * Function that converts int threshold/value to {@link BigDecimal}.
	 */
	private final IntFunction<BigDecimal> toBigDecimalConverter;
	/**
	 * Defines how the histogram handles sparse data (fewer distinct values than requested buckets).
	 */
	private final BucketCountMode bucketCountMode;

	/**
	 * Creates a new EqualizedHistogramDataCruncher that computes histogram using cumulative frequency distribution.
	 *
	 * @param histogramType        name of the histogram (for error messages)
	 * @param bucketCount          requested number of buckets (must be > 1)
	 * @param limitDecimalPlacesTo maximum decimal places in threshold values
	 * @param sourceData           array of source data items (must be sorted by threshold value)
	 * @param thresholdRetriever   function to extract threshold value from source item
	 * @param weightRetriever      function to extract weight (record count) from source item
	 * @param toBigDecimalConverter function to convert int threshold to BigDecimal
	 * @param bucketCountMode      defines how to handle sparse data (EXACT pads with empty buckets,
	 *                             ADAPTIVE allows fewer buckets)
	 */
	public EqualizedHistogramDataCruncher(
		@Nonnull String histogramType,
		int bucketCount,
		int limitDecimalPlacesTo,
		@Nonnull T[] sourceData,
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever,
		@Nonnull IntFunction<BigDecimal> toBigDecimalConverter,
		@Nonnull BucketCountMode bucketCountMode
	) {
		Assert.isTrue(
			bucketCount > 1,
			() -> new InvalidHistogramBucketCountException(histogramType, bucketCount)
		);
		Assert.isTrue(
			!ArrayUtils.isEmpty(sourceData),
			"Source data must not be empty to compute " + histogramType + " histogram!"
		);
		this.bucketCount = bucketCount;
		this.limitDecimalPlacesTo = limitDecimalPlacesTo;
		this.sourceData = sourceData;
		this.toBigDecimalConverter = toBigDecimalConverter;
		this.bucketCountMode = bucketCountMode;
		this.firstThreshold = thresholdRetriever.applyAsInt(sourceData[0]);
		this.lastThreshold = thresholdRetriever.applyAsInt(sourceData[sourceData.length - 1]);
		this.extendedMaxThreshold = this.lastThreshold;

		// compute histogram using cumulative frequency algorithm
		this.histogram = computeEqualizedHistogram(thresholdRetriever, weightRetriever);
	}

	/**
	 * Returns the computed histogram as an array of buckets.
	 */
	@Nonnull
	@Override
	public CacheableBucket[] getHistogram() {
		return this.histogram;
	}

	/**
	 * Returns maximal value in the histogram. For EXACT mode, this may be greater than the
	 * maximum data value if the range was extended to accommodate the requested bucket count.
	 */
	@Nonnull
	@Override
	public BigDecimal getMaxValue() {
		return this.toBigDecimalConverter.apply(this.extendedMaxThreshold)
			.setScale(this.limitDecimalPlacesTo, RoundingMode.UP);
	}

	/**
	 * Computes histogram using cumulative frequency distribution.
	 * The algorithm positions bucket boundaries so each bucket covers approximately equal
	 * portion of total records (by weight).
	 *
	 * The algorithm works in three phases:
	 * 1. Aggregate items by distinct threshold values (items with same value must be in same bucket)
	 * 2. Calculate cumulative distribution and find optimal bucket boundaries at value changes
	 * 3. Create the final bucket array
	 */
	@Nonnull
	private CacheableBucket[] computeEqualizedHistogram(
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever
	) {
		// Step 1: Aggregate items by distinct threshold values
		// Since source data is sorted, we can do this in a single pass
		// Count distinct values first to size arrays appropriately
		int distinctCount = 1;
		int prevThreshold = thresholdRetriever.applyAsInt(this.sourceData[0]);
		for (int i = 1; i < this.sourceData.length; i++) {
			final int currentThreshold = thresholdRetriever.applyAsInt(this.sourceData[i]);
			if (currentThreshold != prevThreshold) {
				distinctCount++;
				prevThreshold = currentThreshold;
			}
		}

		// Edge case: if all items have same threshold, return single bucket
		// For single bucket, relativeFrequency is 100% (contains all data) or represents single point (no range)
		if (distinctCount == 1) {
			long totalWeight = 0;
			for (int i = 0; i < this.sourceData.length; i++) {
				totalWeight += weightRetriever.applyAsInt(this.sourceData[i]);
			}
			return new CacheableBucket[]{
				new CacheableBucket(
					this.toBigDecimalConverter.apply(this.firstThreshold)
						.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
					(int) totalWeight,
					BigDecimal.ONE // Single bucket covers entire range, normalized to 1
				)
			};
		}

		// Build arrays of distinct values and their aggregated weights
		final int[] distinctThresholds = new int[distinctCount];
		final int[] distinctWeights = new int[distinctCount];

		int distinctIndex = 0;
		distinctThresholds[0] = thresholdRetriever.applyAsInt(this.sourceData[0]);
		distinctWeights[0] = weightRetriever.applyAsInt(this.sourceData[0]);

		for (int i = 1; i < this.sourceData.length; i++) {
			final int currentThreshold = thresholdRetriever.applyAsInt(this.sourceData[i]);
			final int currentWeight = weightRetriever.applyAsInt(this.sourceData[i]);

			if (currentThreshold == distinctThresholds[distinctIndex]) {
				// same value, accumulate weight
				distinctWeights[distinctIndex] += currentWeight;
			} else {
				// new distinct value
				distinctIndex++;
				distinctThresholds[distinctIndex] = currentThreshold;
				distinctWeights[distinctIndex] = currentWeight;
			}
		}

		// Step 2: Calculate cumulative distribution and find bucket boundaries
		// Calculate total weight
		long totalWeight = 0;
		for (int i = 0; i < distinctCount; i++) {
			totalWeight += distinctWeights[i];
		}

		// Limit bucket count to number of distinct values (can't have more buckets than values)
		final int effectiveBucketCount = Math.min(this.bucketCount, distinctCount);
		final double targetWeightPerBucket = (double) totalWeight / effectiveBucketCount;

		// Arrays to store bucket boundaries and counts
		final int[] bucketThresholds = new int[effectiveBucketCount];
		final int[] bucketCounts = new int[effectiveBucketCount];

		int currentBucket = 0;
		long cumulativeWeight = 0;
		int bucketWeight = 0;

		// Set first bucket threshold to first distinct value
		bucketThresholds[0] = distinctThresholds[0];

		// Iterate over distinct values (not individual items)
		for (int i = 0; i < distinctCount; i++) {
			final int valueWeight = distinctWeights[i];

			cumulativeWeight += valueWeight;
			bucketWeight += valueWeight;

			// Check if we should move to next bucket AFTER processing this distinct value
			// Only transition if:
			// 1. We've exceeded the target cumulative weight
			// 2. There are more buckets to fill
			// 3. There is a next distinct value to use as the new bucket's threshold
			final double targetCumulativeWeight = (currentBucket + 1) * targetWeightPerBucket;

			// Only transition once per distinct value - if cumulative weight exceeds target,
			// start a new bucket with the NEXT distinct value (if available)
			if (currentBucket < effectiveBucketCount - 1
				&& cumulativeWeight >= targetCumulativeWeight
				&& i + 1 < distinctCount) {

				// Record count for current bucket
				bucketCounts[currentBucket] = bucketWeight;
				bucketWeight = 0;

				// Move to next bucket
				currentBucket++;

				// Set threshold to the NEXT distinct value (bucket boundary at value change)
				bucketThresholds[currentBucket] = distinctThresholds[i + 1];
			}
		}

		// Record count for last bucket
		bucketCounts[currentBucket] = bucketWeight;

		// Step 3: Create CacheableBucket array, trimming any unused buckets
		// Calculate relativeFrequency as totalRange / bucketWidth (value density)
		final int actualBucketCount = currentBucket + 1;
		final BigDecimal firstThresholdBD = this.toBigDecimalConverter.apply(this.firstThreshold);
		final BigDecimal lastThresholdBD = this.toBigDecimalConverter.apply(this.lastThreshold);
		final BigDecimal totalRange = lastThresholdBD.subtract(firstThresholdBD);

		final CacheableBucket[] result = new CacheableBucket[actualBucketCount];
		for (int i = 0; i < actualBucketCount; i++) {
			final BigDecimal bucketStart = this.toBigDecimalConverter.apply(bucketThresholds[i]);
			final BigDecimal bucketEnd = (i + 1 < actualBucketCount)
				? this.toBigDecimalConverter.apply(bucketThresholds[i + 1])
				: lastThresholdBD;
			final BigDecimal bucketWidth = bucketEnd.subtract(bucketStart);

			// relativeFrequency = totalRange / bucketWidth
			// Higher values indicate denser data concentration (narrow bucket = high density)
			final BigDecimal relativeFrequency;
			if (bucketWidth.compareTo(BigDecimal.ZERO) > 0 && totalRange.compareTo(BigDecimal.ZERO) > 0) {
				relativeFrequency = totalRange.divide(bucketWidth, 2, RoundingMode.HALF_UP);
			} else {
				// Edge case: zero width or zero range - use 1 as neutral value
				relativeFrequency = BigDecimal.ONE;
			}

			result[i] = new CacheableBucket(
				bucketStart.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
				bucketCounts[i],
				relativeFrequency
			);
		}

		// Step 4: Pad with empty buckets if EXACT mode and we have fewer buckets than requested
		if (this.bucketCountMode == BucketCountMode.EXACT && result.length < this.bucketCount) {
			return padWithEmptyBuckets(result);
		}

		return result;
	}

	/**
	 * Pads the histogram with empty buckets to reach the requested bucket count.
	 * Empty buckets are distributed proportionally across gaps between data buckets.
	 *
	 * @param dataBuckets the data-containing buckets computed from cumulative frequency distribution
	 * @return array with exactly {@link #bucketCount} buckets, including empty ones in gaps
	 */
	@Nonnull
	private CacheableBucket[] padWithEmptyBuckets(@Nonnull CacheableBucket[] dataBuckets) {
		// Cannot pad single bucket (no gaps to distribute empty buckets into)
		if (dataBuckets.length <= 1) {
			return dataBuckets;
		}

		final int emptyBucketsNeeded = this.bucketCount - dataBuckets.length;

		// Calculate gaps between consecutive data buckets
		final int gapCount = dataBuckets.length - 1;
		final BigDecimal[] gapSizes = new BigDecimal[gapCount];
		BigDecimal totalGapSize = BigDecimal.ZERO;

		for (int i = 0; i < gapCount; i++) {
			gapSizes[i] = dataBuckets[i + 1].threshold().subtract(dataBuckets[i].threshold());
			totalGapSize = totalGapSize.add(gapSizes[i]);
		}

		// Calculate total range for relativeFrequency computation
		final BigDecimal firstThresholdBD = this.toBigDecimalConverter.apply(this.firstThreshold);
		final BigDecimal lastThresholdBD = this.toBigDecimalConverter.apply(this.lastThreshold);
		final BigDecimal totalRange = lastThresholdBD.subtract(firstThresholdBD);

		// Distribute empty buckets proportionally using floor + largest-remainder method
		// This approach guarantees: no negative allocations, exact total, fair distribution
		final int[] emptyPerGap = new int[gapCount];
		final double[] remainders = new double[gapCount];
		int assigned = 0;

		for (int i = 0; i < gapCount; i++) {
			final double exactAllocation = emptyBucketsNeeded * gapSizes[i].doubleValue() / totalGapSize.doubleValue();
			emptyPerGap[i] = (int) Math.floor(exactAllocation);
			remainders[i] = exactAllocation - emptyPerGap[i];
			assigned += emptyPerGap[i];
		}

		// Distribute remaining buckets to gaps with largest remainders
		int remaining = emptyBucketsNeeded - assigned;
		while (remaining > 0) {
			int maxRemainderIndex = 0;
			for (int i = 1; i < gapCount; i++) {
				if (remainders[i] > remainders[maxRemainderIndex]) {
					maxRemainderIndex = i;
				}
			}
			emptyPerGap[maxRemainderIndex]++;
			remainders[maxRemainderIndex] = -1.0; // Mark as used
			remaining--;
		}

		// Build result array with data and empty buckets interleaved
		// Track last threshold to ensure strict monotonicity after rounding
		final CacheableBucket[] result = new CacheableBucket[this.bucketCount];
		int resultIndex = 0;
		final BigDecimal minIncrement = BigDecimal.ONE.scaleByPowerOfTen(-this.limitDecimalPlacesTo);

		for (int i = 0; i < dataBuckets.length; i++) {
			result[resultIndex++] = dataBuckets[i];

			// Add empty buckets in the gap after this data bucket (except after the last one)
			if (i < dataBuckets.length - 1 && emptyPerGap[i] > 0) {
				final BigDecimal gapStart = dataBuckets[i].threshold();
				final BigDecimal gapEnd = dataBuckets[i + 1].threshold();
				final BigDecimal step = gapEnd.subtract(gapStart)
					.divide(new BigDecimal(emptyPerGap[i] + 1), 10, RoundingMode.HALF_UP);

				BigDecimal lastPlacedThreshold = gapStart;
				for (int j = 1; j <= emptyPerGap[i]; j++) {
					BigDecimal emptyThreshold = gapStart.add(step.multiply(new BigDecimal(j)))
						.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP);

					// Ensure strict monotonicity: each threshold must be > previous
					if (emptyThreshold.compareTo(lastPlacedThreshold) <= 0) {
						emptyThreshold = lastPlacedThreshold.add(minIncrement);
					}

					// Skip if would violate monotonicity with next data bucket
					if (emptyThreshold.compareTo(gapEnd) >= 0) {
						continue;
					}

					// Empty bucket width is the step size; compute relativeFrequency
					final BigDecimal emptyBucketRelativeFrequency = step.compareTo(BigDecimal.ZERO) > 0
						? totalRange.divide(step, 2, RoundingMode.HALF_UP)
						: BigDecimal.ZERO;

					result[resultIndex++] = new CacheableBucket(emptyThreshold, 0, emptyBucketRelativeFrequency);
					lastPlacedThreshold = emptyThreshold;
				}
			}
		}

		// Extend range if some buckets couldn't fit in gaps due to monotonicity constraints
		final int missingBuckets = this.bucketCount - resultIndex;
		if (missingBuckets > 0) {
			resultIndex = extendRangeForMissingBuckets(result, resultIndex, missingBuckets, totalRange);
		}

		return result;
	}

	/**
	 * Extends histogram range beyond last data value to accommodate missing buckets.
	 * Updates {@link #extendedMaxThreshold} to reflect the new range.
	 *
	 * @param result         the bucket array being built
	 * @param currentIndex   current write position in result array
	 * @param missingBuckets number of empty buckets to add
	 * @param dataRange      the original data range (last - first threshold)
	 * @return new currentIndex after adding buckets
	 */
	private int extendRangeForMissingBuckets(
		@Nonnull CacheableBucket[] result,
		int currentIndex,
		int missingBuckets,
		@Nonnull BigDecimal dataRange
	) {
		final BigDecimal minIncrement = BigDecimal.ONE.scaleByPowerOfTen(-this.limitDecimalPlacesTo);
		final BigDecimal lastThresholdBD = this.toBigDecimalConverter.apply(this.lastThreshold);

		// Calculate extension step: use average bucket width or minimum increment
		final BigDecimal extensionStep;
		if (currentIndex > 1 && dataRange.compareTo(BigDecimal.ZERO) > 0) {
			// Average width of existing buckets
			extensionStep = dataRange.divide(new BigDecimal(currentIndex - 1), 10, RoundingMode.HALF_UP)
				.max(minIncrement);
		} else {
			extensionStep = minIncrement;
		}

		// Add missing buckets after last data value
		BigDecimal currentThreshold = lastThresholdBD;
		for (int i = 0; i < missingBuckets; i++) {
			currentThreshold = currentThreshold.add(extensionStep)
				.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP);

			// relativeFrequency for extended buckets: use the step width relative to total range
			final BigDecimal extendedBucketRelativeFrequency = extensionStep.compareTo(BigDecimal.ZERO) > 0
				? dataRange.add(extensionStep.multiply(new BigDecimal(i + 1)))
					.divide(extensionStep, 2, RoundingMode.HALF_UP)
				: BigDecimal.ONE;

			result[currentIndex++] = new CacheableBucket(currentThreshold, 0, extendedBucketRelativeFrequency);
		}

		// Update extended max threshold for getMaxValue()
		this.extendedMaxThreshold = currentThreshold
			.scaleByPowerOfTen(this.limitDecimalPlacesTo)
			.setScale(0, RoundingMode.UP)
			.intValue();

		return currentIndex;
	}

}
