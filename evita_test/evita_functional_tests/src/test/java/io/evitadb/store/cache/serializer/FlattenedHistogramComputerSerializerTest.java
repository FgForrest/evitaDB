/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.cache.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.core.query.extraResult.translator.histogram.cache.FlattenedHistogramComputer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link FlattenedHistogramComputerSerializer} round-trips a {@link CacheableHistogram} whose explicit
 * `overallCount` differs from the sum of bucket occurrences — the range (overlap) histogram case. The distinct
 * count must survive write→read intact instead of being recomputed from the bucket sum.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FlattenedHistogramComputerSerializer — overallCount round-trip")
@Tag(STORAGE)
@Tag(HISTOGRAM)
@Tag(SERIALIZATION)
class FlattenedHistogramComputerSerializerTest {

	@Test
	@DisplayName("Distinct overallCount different from the bucket sum survives the round-trip")
	void shouldRetainExplicitOverallCountAcrossRoundTrip() {
		// buckets sum to 1+2+3+3+2 = 11 occurrences, but only 4 distinct records exist (overlap histogram)
		final CacheableBucket[] buckets = {
			new CacheableBucket(BigDecimal.valueOf(10), 1, new BigDecimal("33.33")),
			new CacheableBucket(BigDecimal.valueOf(15), 2, new BigDecimal("66.67")),
			new CacheableBucket(BigDecimal.valueOf(20), 3, new BigDecimal("100.00")),
			new CacheableBucket(BigDecimal.valueOf(25), 3, new BigDecimal("100.00")),
			new CacheableBucket(BigDecimal.valueOf(30), 2, new BigDecimal("66.67"))
		};
		final int distinctOverallCount = 4;
		final CacheableHistogram histogram = new CacheableHistogram(
			buckets, BigDecimal.valueOf(35), 10, 35, distinctOverallCount
		);
		// sanity: the value we want to protect is genuinely different from the bucket sum
		assertEquals(11, sumOccurrences(buckets), "fixture must have a bucket sum distinct from overallCount");
		assertEquals(distinctOverallCount, histogram.getOverallCount());

		final FlattenedHistogramComputer original = new FlattenedHistogramComputer(
			1L, 2L, new long[] {3L, 4L}, histogram
		);

		final Kryo kryo = KryoFactory.createKryo();
		final FlattenedHistogramComputerSerializer serializer = new FlattenedHistogramComputerSerializer();

		final ByteArrayOutputStream os = new ByteArrayOutputStream(512);
		try (final Output output = new Output(os, 512)) {
			serializer.write(kryo, output, original);
		}

		final FlattenedHistogramComputer deserialized;
		try (final Input input = new Input(os.toByteArray())) {
			deserialized = serializer.read(kryo, input, FlattenedHistogramComputer.class);
		}

		assertEquals(
			distinctOverallCount, deserialized.compute().getOverallCount(),
			"distinct overallCount must survive the round-trip, not be recomputed from the bucket sum"
		);
		assertEquals(
			0, deserialized.compute().getMax().compareTo(BigDecimal.valueOf(35)),
			"max must round-trip"
		);
		assertEquals(
			buckets.length, deserialized.compute().getBuckets().length,
			"bucket count must round-trip"
		);
	}

	@Test
	@DisplayName("Null raw bounds and bucket-sum overall count survive the round-trip for a price-style histogram")
	void shouldRoundTripNullRawBoundsForPriceStyleHistogram() {
		// price histograms use the two-argument constructor: null raw bounds, overallCount = bucket sum
		final CacheableBucket[] buckets = {
			new CacheableBucket(BigDecimal.valueOf(10), 4, new BigDecimal("80.00")),
			new CacheableBucket(BigDecimal.valueOf(20), 5, new BigDecimal("100.00")),
			new CacheableBucket(BigDecimal.valueOf(30), 1, new BigDecimal("20.00"))
		};
		final CacheableHistogram histogram = new CacheableHistogram(buckets, BigDecimal.valueOf(40));
		// sanity: the legacy constructor leaves raw bounds null and sums occurrences for overallCount
		assertNull(histogram.getRawMin(), "price-style histogram must carry a null raw min");
		assertNull(histogram.getRawMax(), "price-style histogram must carry a null raw max");
		assertEquals(10, histogram.getOverallCount(), "overall count must default to the bucket sum 4+5+1");

		final FlattenedHistogramComputer original = new FlattenedHistogramComputer(
			5L, 6L, new long[] {7L, 8L}, histogram
		);

		final Kryo kryo = KryoFactory.createKryo();
		final FlattenedHistogramComputerSerializer serializer = new FlattenedHistogramComputerSerializer();

		final ByteArrayOutputStream os = new ByteArrayOutputStream(512);
		try (final Output output = new Output(os, 512)) {
			serializer.write(kryo, output, original);
		}

		final FlattenedHistogramComputer deserialized;
		try (final Input input = new Input(os.toByteArray())) {
			deserialized = serializer.read(kryo, input, FlattenedHistogramComputer.class);
		}

		final CacheableHistogramContract result = deserialized.compute();
		assertNull(result.getRawMin(), "null raw min must survive the round-trip");
		assertNull(result.getRawMax(), "null raw max must survive the round-trip");
		assertEquals(10, result.getOverallCount(), "bucket-sum overall count must survive the round-trip");
		assertEquals(
			0, result.getMax().compareTo(BigDecimal.valueOf(40)),
			"max must round-trip"
		);
		assertEquals(buckets.length, result.getBuckets().length, "bucket count must round-trip");
	}

	/**
	 * Sums the occurrences across all buckets — the point/price-histogram definition of overall count.
	 */
	private static int sumOccurrences(CacheableBucket[] buckets) {
		int sum = 0;
		for (final CacheableBucket bucket : buckets) {
			sum += bucket.occurrences();
		}
		return sum;
	}
}
