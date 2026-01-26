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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * A histogram is an approximate representation of the distribution of numerical data. For detailed description please
 * see <a href="https://en.wikipedia.org/wiki/Histogram">Wikipedia</a>.
 *
 * Histogram can be computed only for numeric based properties. It visualises which property values are more common
 * in the returned data set and which are rare. Bucket count will never exceed requested bucket count specified in
 * {@link PriceHistogram#getRequestedBucketCount()} or {@link AttributeHistogram#getRequestedBucketCount()} but there
 * may be less of them if there is no enough data for computation. Bucket thresholds are specified heuristically so that
 * there are as few "empty buckets" as possible.
 *
 * - buckets are defined by their lower bounds (inclusive)
 * - the upper bound is the lower bound of the next bucket
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HistogramContract extends Serializable {

	/**
	 * Constant for empty histogram.
	 */
	HistogramContract EMPTY = new HistogramContract() {
		@Serial private static final long serialVersionUID = 1796245817858964020L;

		@Nonnull
		@Override
		public BigDecimal getMin() {
			return BigDecimal.ZERO;
		}

		@Nonnull
		@Override
		public BigDecimal getMax() {
			return BigDecimal.ZERO;
		}

		@Override
		public int getOverallCount() {
			return 0;
		}

		@Nonnull
		@Override
		public Bucket[] getBuckets() {
			return new Bucket[0];
		}

		@Override
		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE;
		}

		@Override
		public String toString() {
			return "EMPTY HISTOGRAM";
		}
	};

	/**
	 * Returns left bound of the first bucket. It represents the smallest value encountered in the returned set.
	 */
	@Nonnull
	BigDecimal getMin();

	/**
	 * Returns right bound of the last bucket of the histogram. Each bucket contains only left bound threshold, so this
	 * value is necessary so that first histogram buckets makes any sense. This value is exceptional in the sense that
	 * it represents the biggest value encountered in the returned set and represents inclusive right bound for the
	 * last bucket.
	 */
	@Nonnull
	BigDecimal getMax();

	/**
	 * Returns count of all entities that are covered by this histogram. It's plain sum of occurrences of all buckets
	 * in the histogram.
	 */
	int getOverallCount();

	/**
	 * Returns histogram buckets that represents a tuple of occurrence count and the minimal threshold of the bucket
	 * values.
	 */
	@Nonnull
	Bucket[] getBuckets();

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	int estimateSize();

	/**
	 * Converts the histogram data into a formatted string representation.
	 * The resulting string includes information about the range, occurrences, and percentages
	 * for each bucket within the histogram, providing a detailed textual summary of the histogram's distribution.
	 *
	 * @return A non-null formatted string representation of the histogram.
	 */
	@Nonnull
	default String asString() {
		final Bucket[] buckets = getBuckets();
		return formatHistogram(
			buckets.length,
			index -> buckets[index].threshold(),
			index -> buckets[index].occurrences(),
			index -> buckets[index].relativeFrequency(),
			index -> buckets[index].requested(),
			getMax(),
			getOverallCount()
		);
	}

	/**
	 * Formats a histogram into a human-readable string representation.
	 * The resulting string includes details about the range, occurrences,
	 * bar visualization, and percentages for each bucket.
	 *
	 * @param bucketCount               The number of buckets in the histogram. Must be non-negative.
	 * @param thresholdProvider         A function that provides the threshold value for a given bucket index.
	 *                                  The thresholds define the ranges of the buckets. Cannot be null.
	 * @param occurrenceProvider        A function that provides the occurrence count for a given bucket index.
	 *                                  The occurrences represent the frequency of data points within a bucket.
	 *                                  Cannot be null.
	 * @param relativeFrequencyProvider A function that provides the relative frequency value for a given bucket index.
	 *                                  This value is used to calculate the visual bar width, allowing proper
	 *                                  visualization for both standard and equalized histograms. Cannot be null.
	 * @param requestedProvider         A predicate that determines whether a specific bucket is requested for
	 *                                  special marking in the visualization. Cannot be null.
	 * @param max                       The maximum value for the histogram's range. Used for defining the right bound
	 *                                  of the last bucket. Cannot be null.
	 * @param overallCount              The total number of occurrences across all buckets.
	 *                                  Used for calculating percentages. Must be non-negative.
	 * @return A non-null string representation of the formatted histogram, where each bucket's range,
	 *         occurrences, bar visualization, and percentage are displayed in a human-readable format.
	 *         If bucketCount is 0, returns "EMPTY HISTOGRAM".
	 */
	@Nonnull
	static String formatHistogram(
		int bucketCount,
		@Nonnull IntFunction<BigDecimal> thresholdProvider,
		@Nonnull IntFunction<Integer> occurrenceProvider,
		@Nonnull IntFunction<BigDecimal> relativeFrequencyProvider,
		@Nonnull IntPredicate requestedProvider,
		@Nonnull BigDecimal max,
		int overallCount
	) {
		if (bucketCount == 0) {
			return "EMPTY HISTOGRAM";
		}

		int maxOccurrences = 0;
		BigDecimal maxRelativeFrequency = BigDecimal.ZERO;
		for (int i = 0; i < bucketCount; i++) {
			maxOccurrences = Math.max(maxOccurrences, occurrenceProvider.apply(i));
			final BigDecimal rf = relativeFrequencyProvider.apply(i);
			if (rf.compareTo(maxRelativeFrequency) > 0) {
				maxRelativeFrequency = rf;
			}
		}
		final int countWidth = Math.max(1, String.valueOf(maxOccurrences).length());
		final int maxBarWidth = 40;
		final String[] ranges = new String[bucketCount];
		int rangeWidth = 0;

		for (int i = 0; i < bucketCount; i++) {
			final boolean hasNext = i + 1 < bucketCount;
			final String range = thresholdProvider.apply(i).toPlainString() +
				" - " +
				(hasNext ? thresholdProvider.apply(i + 1).toPlainString() : max.toPlainString());
			ranges[i] = range;
			rangeWidth = Math.max(rangeWidth, range.length());
		}

		final String lineSeparator = System.lineSeparator();
		final StringBuilder sb = new StringBuilder()
			.append("Histogram[min=")
			.append(thresholdProvider.apply(0).toPlainString())
			.append(", max=")
			.append(max.toPlainString())
			.append(", overall=")
			.append(overallCount)
			.append(']')
			.append(lineSeparator);

		for (int i = 0; i < bucketCount; i++) {
			final int occurrences = occurrenceProvider.apply(i);
			final BigDecimal relativeFrequency = relativeFrequencyProvider.apply(i);
			int barSize = maxRelativeFrequency.compareTo(BigDecimal.ZERO) == 0
				? 0
				: relativeFrequency.multiply(BigDecimal.valueOf(maxBarWidth))
					.divide(maxRelativeFrequency, 0, RoundingMode.HALF_UP)
					.intValue();
			if (relativeFrequency.compareTo(BigDecimal.ZERO) > 0 && barSize == 0) {
				barSize = 1;
			}
			sb.append(String.format(Locale.ROOT, "%-" + rangeWidth + "s | %" + countWidth + "d ", ranges[i], occurrences));
			if (requestedProvider.test(i)) {
				sb.append('^');
			}
			if (barSize > 0) {
				for (int j = 0; j < barSize; j++) {
					sb.append('#');
				}
			}
			if (overallCount > 0) {
				final double percentage = occurrences * 100.0d / overallCount;
				sb.append(String.format(Locale.ROOT, " (%.1f%%)", percentage));
			}
			if (i + 1 < bucketCount) {
				sb.append(lineSeparator);
			}
		}
		return sb.toString();
	}

	/**
	 * Data object that carries out threshold in histogram (or bucket if you will) along with number of occurrences in it.
	 *
	 * @param threshold         Contains threshold (left bound - inclusive) of the bucket.
	 * @param occurrences       Contains number of entity occurrences in this bucket - e.g. number of entities that
	 *                          has monitored property value between previous bucket threshold (exclusive) and this
	 *                          bucket threshold (inclusive)
	 * @param requested         contains true if the query contained {@link AttributeBetween} or {@link PriceBetween}
	 *                          constraint for particular attribute / price and the bucket threshold lies within the
	 *                          range (inclusive) of the constraint. False otherwise.
	 * @param relativeFrequency Relative frequency value used for visualization purposes.
	 *                          For standard histograms: percentage of total occurrences (0-100), calculated as
	 *                          `(occurrences / overallCount) * 100`.
	 *                          For equalized histograms: value density calculated as `totalRange / bucketWidth`,
	 *                          where higher values indicate denser data concentration (values packed into narrower
	 *                          bucket range).
	 */
	record Bucket(
		@Nonnull BigDecimal threshold,
		int occurrences,
		boolean requested,
		@Nonnull BigDecimal relativeFrequency
	) implements Serializable {
		public static final int BUCKET_MEMORY_SIZE = MemoryMeasuringConstants.INT_SIZE * 2 + MemoryMeasuringConstants.BIG_DECIMAL_SIZE * 2;
		@Serial private static final long serialVersionUID = 4216355542992506074L;

		@Nonnull
		@Override
		public String toString() {
			return '[' +
				(this.requested ? "^" : "") + this.threshold +
				": " + this.occurrences +
				" (" + this.relativeFrequency + ")" +
				']';
		}
	}

}
