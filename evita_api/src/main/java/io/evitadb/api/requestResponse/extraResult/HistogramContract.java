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
	 * Data object that carries out threshold in histogram (or bucket if you will) along with number of occurrences in it.
	 *
	 * @param threshold   Contains threshold (left bound - inclusive) of the bucket.
	 * @param occurrences Contains number of entity occurrences in this bucket - e.g. number of entities that has monitored property value
	 *                    between previous bucket threshold (exclusive) and this bucket threshold (inclusive)
	 * @param requested   contains true if the query contained {@link AttributeBetween} or {@link PriceBetween}
	 *                    constraint for particular attribute / price and the bucket threshold lies within the range
	 *                    (inclusive) of the constraint. False otherwise.
	 */
	record Bucket(
		@Nonnull BigDecimal threshold,
		int occurrences,
		boolean requested
	) implements Serializable {
		public static final int BUCKET_MEMORY_SIZE = MemoryMeasuringConstants.INT_SIZE * 2 + MemoryMeasuringConstants.BIG_DECIMAL_SIZE;
		@Serial private static final long serialVersionUID = 4216355542992506073L;

		@Nonnull
		@Override
		public String toString() {
			return '[' +
				(this.requested ? "^" : "") + this.threshold +
				": " + this.occurrences +
				')';
		}
	}
}
