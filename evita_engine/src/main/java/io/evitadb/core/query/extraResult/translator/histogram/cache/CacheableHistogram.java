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

package io.evitadb.core.query.extraResult.translator.histogram.cache;

import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Default implementation of {@link HistogramContract}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see HistogramContract
 */
@EqualsAndHashCode
public class CacheableHistogram implements CacheableHistogramContract {
	@Serial private static final long serialVersionUID = 6790616758491107665L;
	private final BigDecimal max;
	@Getter private final CacheableBucket[] buckets;

	public CacheableHistogram(@Nonnull CacheableBucket[] buckets, @Nonnull BigDecimal max) {
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
		return Arrays.stream(this.buckets).mapToInt(CacheableBucket::occurrences).sum();
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.BIG_DECIMAL_SIZE +
			this.buckets.length * CacheableBucket.BUCKET_MEMORY_SIZE;
	}

	@Nonnull
	@Override
	public HistogramContract convertToHistogram(@Nonnull Predicate<BigDecimal> requestedPredicate) {
		return new Histogram(
			Arrays.stream(this.buckets)
				.map(
					bucket -> new Bucket(
						bucket.threshold(),
						bucket.occurrences(),
						requestedPredicate.test(bucket.threshold())
					)
				)
				.toArray(Bucket[]::new),
			this.max
		);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < this.buckets.length; i++) {
			final CacheableBucket bucket = this.buckets[i];
			final boolean hasNext = i + 1 < this.buckets.length;
			sb.append("[")
				.append(bucket.threshold())
				.append(" - ")
				.append(hasNext ? this.buckets[i + 1].threshold() : this.max)
				.append("]: ")
				.append(bucket.occurrences());
			if (hasNext) {
				sb.append(", ");
			}
		}
		return sb.toString();
	}

}
