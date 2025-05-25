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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * This class can compute histogram stretched to certain steps count from any input array.
 * Slices entire range into regular pieces (even if there is no data in the particular slice) and computes occurrence
 * of the data in each of the range piece.
 *
 * For data: 100, 200, 300, 400, 500 and 4 steps it returns these histogram values:
 *
 * Index:   Threshold:   Occurrences:
 * 0,       200,         2
 * 1,       300,         1
 * 2,       400,         1
 * 3,       500,         1
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
public class HistogramDataCruncher<T> {
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
	@Getter private final CacheableBucket[] histogram;
	/**
	 * Internal variable containing optimal threshold step between buckets.
	 */
	private final BigDecimal optimalStep;
	/**
	 * Internal variable containing first threshold int value.
	 */
	private final int firstThreshold;
	/**
	 * Internal variable containing last threshold int value.
	 */
	private final int lastThreshold;
	/**
	 * Function that extracts int value from the {@link #sourceData} item. This value is compared against thresholds.
	 */
	private final ToIntFunction<T> thresholdRetriever;
	/**
	 * Function that extracts number of records that belong to the item in {@link #sourceData} - ie. data weight.
	 */
	private final ToIntFunction<T> weightRetriever;
	/**
	 * Function that converts int threshold/value to {@link BigDecimal}
	 */
	private final IntFunction<BigDecimal> toBigDecimalConverter;
	/**
	 * Function that converts {@link BigDecimal} to int threshold/value. This fct is inverse to {@link #toBigDecimalConverter}.
	 */
	private final ToIntFunction<BigDecimal> fromBigDecimalConverter;
	/**
	 * Internal variable containing current index in source array.
	 */
	private int sourceIndex;
	/**
	 * Internal variable containing TRUE if source data array examination is finished.
	 */
	private boolean finished;
	/**
	 * Internal variable containing current threshold.
	 */
	private BigDecimal currentStep;
	/**
	 * Internal variable containing reference to last bucket that contains at least single record (weight > 0)
	 */
	private CacheableBucket lastNonEmptyBucket;
	/**
	 * Internal variable containing the longest empty space (multiple empty columns in the row).
	 */
	@Getter private LongestSpaceRange longestSpace;
	/**
	 * Internal variable containing current count of empty thresholds in the row (this value is compared to {@link #longestSpace}.
	 */
	private int emptyBucketsInRow;

	public HistogramDataCruncher(
		@Nonnull String histogramType,
		int bucketCount,
		int limitDecimalPlacesTo,
		@Nonnull T[] sourceData,
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever,
		@Nonnull IntFunction<BigDecimal> toBigDecimalConverter,
		@Nonnull ToIntFunction<BigDecimal> fromBigDecimalConverter
	) {
		Assert.isTrue(
			bucketCount > 1,
			() -> new InvalidHistogramBucketCountException(histogramType, bucketCount)
		);
		this.bucketCount = bucketCount;
		this.limitDecimalPlacesTo = limitDecimalPlacesTo;
		this.firstThreshold = thresholdRetriever.applyAsInt(sourceData[0]);
		this.lastThreshold = thresholdRetriever.applyAsInt(sourceData[sourceData.length - 1]);

		this.currentStep = toBigDecimalConverter.apply(this.firstThreshold);
		this.optimalStep = toBigDecimalConverter.apply(this.lastThreshold)
			.subtract(toBigDecimalConverter.apply(this.firstThreshold))
			.divide(new BigDecimal(bucketCount), 10, RoundingMode.CEILING);

		this.sourceData = sourceData;
		this.thresholdRetriever = thresholdRetriever;
		this.weightRetriever = weightRetriever;
		this.toBigDecimalConverter = toBigDecimalConverter;
		this.fromBigDecimalConverter = fromBigDecimalConverter;

		// now compute the result
		final CompositeObjectArray<CacheableBucket> elasticHistogram = new CompositeObjectArray<>(CacheableBucket.class, false);
		// identify first bucket - this must never be empty column, because we start at the first known value
		final CacheableBucket firstColumn = consumeStep(elasticHistogram);
		this.lastNonEmptyBucket = firstColumn;
		elasticHistogram.add(firstColumn);
		// examine source data until all are exhausted
		while (!this.finished) {
			computeNext(elasticHistogram);
		}
		// finalize elastic array and retrieve output histogram
		this.histogram = elasticHistogram.toArray();
	}

	/**
	 * Helper method that tries to find optimal step count so that empty parts are minimized in the histogram.
	 */
	public static <T> HistogramDataCruncher<T> createOptimalHistogram(
		@Nonnull String histogramType,
		int stepCount,
		int allowedDecimalPlaces,
		@Nonnull T[] sourceData,
		@Nonnull ToIntFunction<T> thresholdRetriever,
		@Nonnull ToIntFunction<T> weightRetriever,
		@Nonnull IntFunction<BigDecimal> toBigDecimalConverter,
		@Nonnull ToIntFunction<BigDecimal> fromBigDecimalConverter
	) {
		// compute first histogram
		final HistogramDataCruncher<T> firstShotCruncher = new HistogramDataCruncher<>(
			histogramType,
			stepCount,
			allowedDecimalPlaces,
			sourceData,
			thresholdRetriever,
			weightRetriever,
			toBigDecimalConverter,
			fromBigDecimalConverter
		);
		// get the longest empty gap in it
		final LongestSpaceRange longestSpace = firstShotCruncher.getLongestSpace();
		final int emptyColumns = ofNullable(longestSpace).map(LongestSpaceRange::getColumns).orElse(0);
		// in case of only two non-empty columns recompute histogram only to two columns
		if (stepCount - emptyColumns <= 2) {
			return new HistogramDataCruncher<>(
				histogramType,
				2,
				allowedDecimalPlaces,
				sourceData,
				thresholdRetriever,
				weightRetriever,
				toBigDecimalConverter,
				fromBigDecimalConverter
			);
		} else if (emptyColumns >= 2) {
			// recompute with better column size - add half of the biggest empty space to the current optimal step size
			final BigDecimal longestSpaceSpan = longestSpace.getSpanWidth();
			final BigDecimal recomputedOptimalStep = firstShotCruncher.optimalStep.add(longestSpaceSpan.divide(new BigDecimal(2), 10, RoundingMode.HALF_UP));
			final BigDecimal lastThreshold = toBigDecimalConverter.apply(firstShotCruncher.lastThreshold);
			final BigDecimal firstThreshold = toBigDecimalConverter.apply(firstShotCruncher.firstThreshold);
			final int newOptimalStepCount = lastThreshold.subtract(firstThreshold).divide(recomputedOptimalStep, 0, RoundingMode.FLOOR).intValueExact() + 2;
			return new HistogramDataCruncher<>(
				histogramType,
				newOptimalStepCount,
				allowedDecimalPlaces,
				sourceData,
				thresholdRetriever,
				weightRetriever,
				toBigDecimalConverter,
				fromBigDecimalConverter
			);
		} else {
			return firstShotCruncher;
		}
	}

	/**
	 * Returns maximal value found in the input data.
	 */
	public BigDecimal getMaxValue() {
		return this.toBigDecimalConverter.apply(this.lastThreshold).setScale(this.limitDecimalPlacesTo, RoundingMode.UP);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Computes next bucket in the histogram.
	 */
	private void computeNext(@Nonnull CompositeObjectArray<CacheableBucket> histogram) {
		if (!this.finished) {
			final CacheableBucket nextBucket = consumeStep(histogram);
			if (nextBucket.occurrences() == 0) {
				// if the bucket is empty increase empty thresholds counter
				this.emptyBucketsInRow++;
			} else {
				// if not empty, check whether empty thresholds didn't overcome last biggest gap
				if (this.emptyBucketsInRow > 0 && (this.longestSpace == null || this.longestSpace.columns < this.emptyBucketsInRow)) {
					// if yes create information about next largest gap
					this.longestSpace = new LongestSpaceRange(
						this.emptyBucketsInRow,
						this.lastNonEmptyBucket.threshold(),
						nextBucket.threshold()
					);
				}
				// reset empty buckets counter
				this.emptyBucketsInRow = 0;
				// mark last non-empty bucket
				this.lastNonEmptyBucket = nextBucket;
			}
			histogram.add(nextBucket);
		}
	}

	/**
	 * Computes next threshold and consumes all items in input array with index . {@link #sourceIndex} which value is
	 * lower than the computed next threshold.
	 */
	@Nonnull
	private CacheableBucket consumeStep(@Nonnull CompositeObjectArray<CacheableBucket> histogram) {
		int distinctValues = 0;
		// compute next threshold
		final BigDecimal nextThresholdValue = this.currentStep.add(this.optimalStep).setScale(this.limitDecimalPlacesTo, RoundingMode.CEILING);
		// convert it to the int (if we are at the end of the source data add +1 to consume event he last item)
		final int nextThreshold = histogram.getSize() + 1 == this.bucketCount || this.currentStep.compareTo(nextThresholdValue) == 0 ?
			this.lastThreshold + 1 : this.fromBigDecimalConverter.applyAsInt(nextThresholdValue);

		do {
			distinctValues += consumeSourceDataUntil(nextThreshold);
			// repeat until we cross the next threshold
		} while (!this.finished && this.thresholdRetriever.applyAsInt(this.sourceData[this.sourceIndex]) < nextThreshold);

		// create bucket in the output histogram
		final CacheableBucket result = new CacheableBucket(
			this.currentStep.setScale(this.limitDecimalPlacesTo, RoundingMode.HALF_UP),
			distinctValues
		);

		// move next threshold as current threshold
		this.currentStep = nextThresholdValue;
		return result;
	}

	/**
	 * Method iterates over source items and consumes all which value is lesser than `nextStop`. Collected weight of
	 * all consumed items is returned as a result.
	 */
	private int consumeSourceDataUntil(int nextStop) {
		int collectedWeight = 0;
		int value = this.thresholdRetriever.applyAsInt(this.sourceData[this.sourceIndex]);
		while (value < nextStop) {
			collectedWeight += this.weightRetriever.applyAsInt(this.sourceData[this.sourceIndex]);
			if (this.sourceIndex + 1 >= this.sourceData.length) {
				this.finished = true;
				break;
			} else {
				this.sourceIndex++;
				value = this.thresholdRetriever.applyAsInt(this.sourceData[this.sourceIndex]);
			}
		}
		return collectedWeight;
	}

	/**
	 * DTO containing information about the biggest empty span in histogram.
	 */
	@RequiredArgsConstructor
	private static final class LongestSpaceRange {
		/**
		 * Contains number of buckets in histogram that are empty and are next to each other.
		 */
		@Getter private final int columns;
		/**
		 * Contains value of the first bucket that is empty in this row.
		 */
		private final BigDecimal start;
		/**
		 * Contains value of the last bucket that is empty in this row.
		 */
		private final BigDecimal end;

		/**
		 * Returns difference between end and start buckets that represents the empty columns.
		 */
		public BigDecimal getSpanWidth() {
			return this.end.subtract(this.start);
		}

	}

}
