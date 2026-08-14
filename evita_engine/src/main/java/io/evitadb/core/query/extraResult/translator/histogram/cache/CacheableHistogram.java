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

package io.evitadb.core.query.extraResult.translator.histogram.cache;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.function.Predicate;

/**
 * Default (non-empty) implementation of {@link CacheableHistogramContract}. Holds the full set of
 * pre-computed {@link CacheableBucket}s together with the inclusive right bound of the last bucket.
 * Instances are produced by the histogram computer pipeline and stored in the extra-result cache; they are
 * later converted to the query-time {@link io.evitadb.api.requestResponse.extraResult.HistogramContract} DTO
 * via {@link #convertToHistogram(java.util.function.Predicate)} or the overload that accepts boundary entities.
 *
 * The constructor enforces three structural invariants at creation time:
 *
 * - the bucket array must not be empty;
 * - the last bucket's threshold must be `<= max`;
 * - bucket thresholds must form a strictly monotonic (ascending) sequence.
 *
 * These invariants guarantee that downstream code can always rely on `buckets[0].threshold()` as the minimum
 * and `max` as the inclusive right bound without additional null/range checks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see CacheableHistogramContract
 */
@EqualsAndHashCode
public class CacheableHistogram implements CacheableHistogramContract {
	@Serial private static final long serialVersionUID = 6790616758491107665L;
	/**
	 * Inclusive upper bound of the last bucket. Stored separately because each bucket record only stores its own
	 * lower-bound threshold; without this field the rightmost bucket would have no upper bound.
	 */
	private final BigDecimal max;
	/**
	 * Pre-computed histogram buckets ordered by ascending threshold. Never empty — enforced by the constructor.
	 */
	@Getter private final CacheableBucket[] buckets;
	/**
	 * Raw (native-typed) minimum attribute value observed across all matching entities — preserves the original
	 * attribute type so downstream lookups against {@link io.evitadb.index.attribute.FilterIndex} can be done without
	 * coercion. {@code null} when the producer does not provide it (e.g. the price histogram or legacy cache entries).
	 */
	@Nullable private final Serializable rawMin;
	/**
	 * Raw (native-typed) maximum attribute value observed across all matching entities. See {@link #rawMin} for
	 * contract.
	 */
	@Nullable private final Serializable rawMax;
	/**
	 * Number of entities represented by the histogram. For point and price histograms, and for range histograms
	 * under the frequency-equalised behaviors (`EQUALIZED` / `EQUALIZED_OPTIMIZED`), this equals the sum of
	 * occurrences across all buckets; for range histograms under the overlap behaviors (`STANDARD` / `OPTIMIZED`) a
	 * single record may overlap multiple buckets, so this is the distinct count — smaller than the bucket-occurrence
	 * sum — and must be stored explicitly.
	 */
	private final int overallCount;

	/**
	 * Legacy constructor — delegates to the four-argument form with {@code null} raw bounds. Retained for callers
	 * (price histograms, backward-compat cache deserializers) that cannot or need not carry raw native-typed bounds.
	 *
	 * @param buckets non-empty array of buckets with strictly monotonic thresholds
	 * @param max     inclusive right bound of the last bucket; must be `>= buckets[last].threshold()`
	 */
	public CacheableHistogram(@Nonnull CacheableBucket[] buckets, @Nonnull BigDecimal max) {
		this(buckets, max, null, null);
	}

	/**
	 * @param buckets non-empty array of buckets with strictly monotonic thresholds
	 * @param max     inclusive right bound of the last bucket; must be `>= buckets[last].threshold()`
	 * @param rawMin  native-typed smallest attribute value observed, or {@code null} when unavailable
	 * @param rawMax  native-typed largest attribute value observed, or {@code null} when unavailable
	 */
	public CacheableHistogram(
		@Nonnull CacheableBucket[] buckets,
		@Nonnull BigDecimal max,
		@Nullable Serializable rawMin,
		@Nullable Serializable rawMax
	) {
		this(buckets, max, rawMin, rawMax, sumOccurrences(buckets));
	}

	/**
	 * Constructor variant accepting an explicit `overallCount`. Used by range (overlap) histograms where a single
	 * record may overlap multiple buckets, so the distinct-record count differs from the bucket-occurrence sum.
	 *
	 * @param buckets      non-empty array of buckets with strictly monotonic thresholds
	 * @param max          inclusive right bound of the last bucket; must be `>= buckets[last].threshold()`
	 * @param rawMin       native-typed smallest attribute value observed, or {@code null} when unavailable
	 * @param rawMax       native-typed largest attribute value observed, or {@code null} when unavailable
	 * @param overallCount number of distinct entities covered by the histogram
	 */
	public CacheableHistogram(
		@Nonnull CacheableBucket[] buckets,
		@Nonnull BigDecimal max,
		@Nullable Serializable rawMin,
		@Nullable Serializable rawMax,
		int overallCount
	) {
		Assert.isTrue(!ArrayUtils.isEmpty(buckets), "Buckets may never be empty!");
		Assert.isTrue(buckets[buckets.length - 1].threshold().compareTo(max) <= 0, "Last bucket must have threshold lower than max!");
		CacheableBucket lastBucket = null;
		for (CacheableBucket bucket : buckets) {
			Assert.isTrue(
				lastBucket == null || lastBucket.threshold().compareTo(bucket.threshold()) < 0,
				"Buckets must have monotonic row of thresholds!"
			);
			lastBucket = bucket;
		}
		this.buckets = buckets;
		this.max = max;
		this.rawMin = rawMin;
		this.rawMax = rawMax;
		this.overallCount = overallCount;
	}

	/**
	 * Sums the occurrences across all buckets — the point/price-histogram definition of overall count.
	 */
	private static int sumOccurrences(@Nonnull CacheableBucket[] buckets) {
		int sum = 0;
		for (final CacheableBucket bucket : buckets) {
			sum += bucket.occurrences();
		}
		return sum;
	}

	@Nonnull
	@Override
	public BigDecimal getMin() {
		return this.buckets[0].threshold();
	}

	@Nonnull
	@Override
	public BigDecimal getMax() {
		return this.max;
	}

	@Nullable
	@Override
	public Serializable getRawMin() {
		return this.rawMin;
	}

	@Nullable
	@Override
	public Serializable getRawMax() {
		return this.rawMax;
	}

	/**
	 * Returns the number of entities represented by this histogram. For point and price histograms, and for range
	 * histograms under the frequency-equalised behaviors (`EQUALIZED` / `EQUALIZED_OPTIMIZED`), this equals the sum of
	 * bucket occurrences; for range histograms under the overlap behaviors (`STANDARD` / `OPTIMIZED`) it is the
	 * distinct-record count carried over from the producer. This is the raw cached value, not filtered by any
	 * query-time predicate.
	 */
	@Override
	public int getOverallCount() {
		return this.overallCount;
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.BIG_DECIMAL_WHOLE_SIZE +
			this.buckets.length * CacheableBucket.BUCKET_MEMORY_SIZE;
	}

	@Nonnull
	@Override
	public HistogramContract convertToHistogram(@Nonnull Predicate<BigDecimal> requestedPredicate) {
		return convertToHistogram(requestedPredicate, null, null);
	}

	@Nonnull
	@Override
	public HistogramContract convertToHistogram(
		@Nonnull Predicate<BigDecimal> requestedPredicate,
		@Nullable SealedEntity minReferencedEntity,
		@Nullable SealedEntity maxReferencedEntity
	) {
		return new Histogram(
			buildBuckets(requestedPredicate), this.max, this.overallCount,
			minReferencedEntity, maxReferencedEntity
		);
	}

	/**
	 * Converts each {@link CacheableBucket} into a query-time {@link Bucket}. The `requested` flag on each
	 * bucket is set by testing the bucket threshold against `requestedPredicate` — this wires the user's
	 * `attributeBetween` range (or always-false predicate when no range was requested) into the produced DTO.
	 */
	@Nonnull
	private Bucket[] buildBuckets(@Nonnull Predicate<BigDecimal> requestedPredicate) {
		final Bucket[] result = new Bucket[this.buckets.length];
		for (int i = 0; i < this.buckets.length; i++) {
			final CacheableBucket source = this.buckets[i];
			result[i] = new Bucket(
				source.threshold(),
				source.occurrences(),
				// test the bucket's lower-bound threshold — a bucket is "requested" when the user's
				// range filter sits inside the [threshold, nextThreshold) interval
				requestedPredicate.test(source.threshold()),
				source.relativeFrequency()
			);
		}
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return asString();
	}
}
