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
 * Algorithm:
 * 1. Calculate total weight (sum of all record counts)
 * 2. Calculate cumulative frequency for each unique value
 * 3. Position bucket boundaries at points where cumulative frequency crosses threshold (i/bucketCount)
 * 4. Count actual occurrences in each resulting bucket
 *
 * @param <T> the type of source data elements
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EqualizedHistogramDataCruncher<T> implements HistogramDataCruncherContract<T> {
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
	 * Function that converts int threshold/value to {@link BigDecimal}.
	 */
	private final IntFunction<BigDecimal> toBigDecimalConverter;

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
		this.bucketCount = bucketCount;
		this.limitDecimalPlacesTo = limitDecimalPlacesTo;
		this.sourceData = sourceData;
		this.toBigDecimalConverter = toBigDecimalConverter;
		this.firstThreshold = thresholdRetriever.applyAsInt(sourceData[0]);
		this.lastThreshold = thresholdRetriever.applyAsInt(sourceData[sourceData.length - 1]);

		// compute histogram using cumulative frequency algorithm
		this.histogram = computeEqualizedHistogram(thresholdRetriever, weightRetriever);
	}

	/**
	 * Helper method that creates an optimized histogram, potentially with fewer buckets if data is sparse.
	 * This mirrors the optimization logic in {@link HistogramDataCruncher#createOptimalHistogram}.
	 *
	 * @param histogramType        name of the histogram (for error messages)
	 * @param stepCount            requested number of buckets
	 * @param allowedDecimalPlaces maximum decimal places in threshold values
	 * @param sourceData           array of source data items (must be sorted)
	 * @param thresholdRetriever   function to extract threshold value from source item
	 * @param weightRetriever      function to extract weight from source item
	 * @param toBigDecimalConverter function to convert int to BigDecimal
	 * @return optimized histogram cruncher
	 */
	public static <T> EqualizedHistogramDataCruncher<T> createOptimalHistogram(
		@Nonnull String histogramType,
		int stepCount,
		int allowedDecimalPlaces,
		@Nonnull T[] sourceData,
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever,
		@Nonnull IntFunction<BigDecimal> toBigDecimalConverter
	) {
		// first compute the histogram with requested bucket count
		final EqualizedHistogramDataCruncher<T> firstShot = new EqualizedHistogramDataCruncher<>(
			histogramType,
			stepCount,
			allowedDecimalPlaces,
			sourceData,
			thresholdRetriever,
			weightRetriever,
			toBigDecimalConverter
		);

		// count non-empty buckets
		int nonEmptyBuckets = 0;
		for (CacheableBucket bucket : firstShot.getHistogram()) {
			if (bucket.occurrences() > 0) {
				nonEmptyBuckets++;
			}
		}

		// if we have very few non-empty buckets, recompute with smaller bucket count
		if (nonEmptyBuckets <= 2 && stepCount > 2) {
			return new EqualizedHistogramDataCruncher<>(
				histogramType,
				2,
				allowedDecimalPlaces,
				sourceData,
				thresholdRetriever,
				weightRetriever,
				toBigDecimalConverter
			);
		}

		return firstShot;
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
	 * Returns maximal value found in the input data.
	 */
	@Nonnull
	@Override
	public BigDecimal getMaxValue() {
		return this.toBigDecimalConverter.apply(this.lastThreshold)
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
		if (distinctCount == 1) {
			long totalWeight = 0;
			for (int i = 0; i < this.sourceData.length; i++) {
				totalWeight += weightRetriever.applyAsInt(this.sourceData[i]);
			}
			return new CacheableBucket[]{
				new CacheableBucket(
					this.toBigDecimalConverter.apply(this.firstThreshold)
						.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
					(int) totalWeight
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
		final int actualBucketCount = currentBucket + 1;
		final CacheableBucket[] result = new CacheableBucket[actualBucketCount];
		for (int i = 0; i < actualBucketCount; i++) {
			result[i] = new CacheableBucket(
				this.toBigDecimalConverter.apply(bucketThresholds[i])
					.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
				bucketCounts[i]
			);
		}

		return result;
	}

}
