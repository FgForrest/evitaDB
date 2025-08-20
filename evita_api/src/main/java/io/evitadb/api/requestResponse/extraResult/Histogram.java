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

import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Default implementation of {@link HistogramContract}
 *
 * @see HistogramContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public class Histogram implements HistogramContract {
	@Serial private static final long serialVersionUID = 7702878167079284412L;
	private final BigDecimal max;
	@Getter private final Bucket[] buckets;

	public Histogram(@Nonnull Bucket[] buckets, @Nonnull BigDecimal max) {
		Assert.isTrue(!ArrayUtils.isEmpty(buckets), "Buckets may never be empty!");
		Assert.isTrue(buckets[buckets.length - 1].threshold().compareTo(max) <= 0, "Last bucket must have threshold lower than max!");
		Bucket lastBucket = null;
		for (Bucket bucket : buckets) {
			Assert.isTrue(
				lastBucket == null || lastBucket.threshold().compareTo(bucket.threshold()) < 0,
				"Buckets must have monotonic row of thresholds!"
			);
			lastBucket = bucket;
		}
		this.buckets = buckets;
		this.max = max;
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			MemoryMeasuringConstants.BIG_DECIMAL_SIZE +
			this.buckets.length * Bucket.BUCKET_MEMORY_SIZE;
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
		return Arrays.stream(this.buckets).mapToInt(Bucket::occurrences).sum();
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < this.buckets.length; i++) {
			final Bucket bucket = this.buckets[i];
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
