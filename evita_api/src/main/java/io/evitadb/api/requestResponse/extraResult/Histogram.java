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

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Default implementation of {@link HistogramContract}
 *
 * @see HistogramContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public class Histogram implements HistogramContract {
	@Serial private static final long serialVersionUID = -4037073601860571920L;
	/**
	 * Inclusive upper bound of the last bucket; the rightmost bucket stores only its lower threshold.
	 */
	private final BigDecimal max;
	/**
	 * Pre-computed buckets ordered by ascending threshold; never empty (enforced by the constructor).
	 */
	@Getter private final Bucket[] buckets;
	/**
	 * Number of entities represented by the histogram. For point and price histograms, and for range histograms
	 * under the frequency-equalised behaviors (`EQUALIZED` / `EQUALIZED_OPTIMIZED`), this equals the sum of
	 * occurrences across all buckets; for range histograms under the overlap behaviors (`STANDARD` / `OPTIMIZED`) a
	 * single record may overlap multiple buckets, so this is the distinct count — smaller than the bucket-occurrence
	 * sum — and must be stored explicitly.
	 */
	private final int overallCount;
	/**
	 * Referenced entity whose value anchors the minimum bucket of the histogram. Populated only for
	 * histograms computed over references when the query requests an entity fetch for the reference.
	 * Excluded from equals/hashCode because two histograms with identical buckets/max must compare
	 * equal even when the in-memory entity instances differ (e.g. different attribute scopes).
	 */
	@EqualsAndHashCode.Exclude
	@Nullable private final SealedEntity minReferencedEntity;
	/**
	 * Referenced entity whose value anchors the maximum bucket of the histogram. See
	 * {@link #minReferencedEntity} for populating semantics and equality rationale.
	 */
	@EqualsAndHashCode.Exclude
	@Nullable private final SealedEntity maxReferencedEntity;

	public Histogram(@Nonnull Bucket[] buckets, @Nonnull BigDecimal max) {
		this(buckets, max, null, null);
	}

	public Histogram(
		@Nonnull Bucket[] buckets,
		@Nonnull BigDecimal max,
		@Nullable SealedEntity minReferencedEntity,
		@Nullable SealedEntity maxReferencedEntity
	) {
		this(buckets, max, sumOccurrences(buckets), minReferencedEntity, maxReferencedEntity);
	}

	/**
	 * Constructor variant accepting an explicit `overallCount`. Used by range (overlap) histograms where a single
	 * record may overlap multiple buckets, so the distinct-record count differs from the bucket-occurrence sum.
	 *
	 * @param buckets             non-empty array of buckets with strictly monotonic thresholds
	 * @param max                 inclusive right bound of the last bucket; must be `>= buckets[last].threshold()`
	 * @param overallCount        number of distinct entities covered by the histogram
	 * @param minReferencedEntity entity anchoring the first bucket, or `null` if unresolved
	 * @param maxReferencedEntity entity anchoring the last bucket, or `null` if unresolved
	 */
	public Histogram(
		@Nonnull Bucket[] buckets,
		@Nonnull BigDecimal max,
		int overallCount,
		@Nullable SealedEntity minReferencedEntity,
		@Nullable SealedEntity maxReferencedEntity
	) {
		Assert.isTrue(!ArrayUtils.isEmpty(buckets), "Buckets may never be empty!");
		Assert.isTrue(
			buckets[buckets.length - 1].threshold().compareTo(max) <= 0,
			"Last bucket must have threshold lower than max!"
		);
		Bucket lastBucket = null;
		for (final Bucket bucket : buckets) {
			Assert.isTrue(
				lastBucket == null || lastBucket.threshold().compareTo(bucket.threshold()) < 0,
				"Buckets must have a monotonic row of thresholds!"
			);
			lastBucket = bucket;
		}
		Assert.isPremiseValid(
			(minReferencedEntity == null) == (maxReferencedEntity == null),
			"Minimum and maximum referenced entities must be either both present or both absent!"
		);
		this.buckets = buckets;
		this.max = max;
		this.overallCount = overallCount;
		this.minReferencedEntity = minReferencedEntity;
		this.maxReferencedEntity = maxReferencedEntity;
	}

	/**
	 * Sums the occurrences across all buckets — the point/price-histogram definition of overall count.
	 */
	private static int sumOccurrences(@Nonnull Bucket[] buckets) {
		int sum = 0;
		for (final Bucket bucket : buckets) {
			sum += bucket.occurrences();
		}
		return sum;
	}

	@Override
	public int estimateSize() {
		int size = MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.BIG_DECIMAL_WHOLE_SIZE +
			this.buckets.length * Bucket.BUCKET_MEMORY_SIZE;
		if (this.minReferencedEntity != null) {
			size += this.minReferencedEntity.estimateSize();
		}
		if (this.maxReferencedEntity != null) {
			size += this.maxReferencedEntity.estimateSize();
		}
		return size;
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

	@Override
	public int getOverallCount() {
		return this.overallCount;
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getMinReferencedEntity() {
		return Optional.ofNullable(this.minReferencedEntity);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getMaxReferencedEntity() {
		return Optional.ofNullable(this.maxReferencedEntity);
	}

	@Override
	public String toString() {
		return asString();
	}
}
