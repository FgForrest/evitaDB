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
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link EqualizedHistogramDataCruncher} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EqualizedHistogramDataCruncher tests")
class EqualizedHistogramDataCruncherTest {

	@Test
	@DisplayName("Should throw exception for invalid bucket count")
	void shouldThrowExceptionForInvalidCountOfBuckets() {
		for (int i = 1; i > -2; i--) {
			final int bucketCount = i;
			assertThrows(
				InvalidHistogramBucketCountException.class,
				() -> createEqualizedIntCruncher(bucketCount, 100, 200)
			);
		}
	}

	@Test
	@DisplayName("Should compute histogram from single value")
	void equalizedHistogramFromSingleValue() {
		// Single value should return single bucket regardless of requested bucket count
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(5, 100);
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(100), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should compute histogram from two distinct values")
	void equalizedHistogramFromTwoDistinctValues() {
		// Two distinct values should produce at most 2 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(4, 100, 500);
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(100), 1),
				new CacheableBucket(new BigDecimal(500), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle heavily skewed data")
	void equalizedHistogramFromHeavilySkewedData() {
		// Data heavily skewed: 9 items at value 1, 1 item at value 100
		// Unlike standard histogram, equalized should only create 2 buckets
		// because there are only 2 distinct values
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 100
		);
		// With equalized behavior, we get 2 buckets at actual data boundaries
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(1), 9),
				new CacheableBucket(new BigDecimal(100), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should distribute evenly by weight")
	void equalizedHistogramDistributesEvenlyByWeight() {
		// Test that equalized histogram distributes records evenly across buckets
		// Data: 10 items at value 10, 10 items at value 20, 10 items at value 30, 10 items at value 40
		// With 4 buckets and 40 total items, each bucket should have ~10 items
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			20, 20, 20, 20, 20, 20, 20, 20, 20, 20,
			30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
			40, 40, 40, 40, 40, 40, 40, 40, 40, 40
		);
		// Each distinct value group has 10 items, target per bucket is 10
		// So each distinct value should map to one bucket
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(10), 10),
				new CacheableBucket(new BigDecimal(20), 10),
				new CacheableBucket(new BigDecimal(30), 10),
				new CacheableBucket(new BigDecimal(40), 10)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(40), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle uneven distribution")
	void equalizedHistogramWithUnevenDistribution() {
		// Data with uneven distribution: 30 at value 10, 5 at value 20, 5 at value 30
		// Total weight = 40, 4 buckets requested but only 3 distinct values
		// effectiveBucketCount = 3, target per bucket = 40/3 = 13.33
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			// 30 items at value 10
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			// 5 items at value 20
			20, 20, 20, 20, 20,
			// 5 items at value 30
			30, 30, 30, 30, 30
		);
		// After processing value 10 (cumulative=30 >= target=13.33), transition to bucket 1
		// After processing value 20 (cumulative=35 >= target=26.67), transition to bucket 2
		// Value 30 goes into bucket 2
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(10), 30),
				new CacheableBucket(new BigDecimal(20), 5),
				new CacheableBucket(new BigDecimal(30), 5)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(30), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should keep repeated values in same bucket")
	void equalizedHistogramWithRepeatedValuesInMiddle() {
		// Test that items with same value are never split across buckets
		// Data: 1, 2, 2, 2, 2, 2, 2, 2, 2, 3 (8 items at value 2)
		// Distinct values: 1 (w=1), 2 (w=8), 3 (w=1)
		// Total = 10, 4 buckets requested but only 3 distinct values
		// effectiveBucketCount = 3, target per bucket = 10/3 = 3.33
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			1, 2, 2, 2, 2, 2, 2, 2, 2, 3
		);
		// After processing value 1 (cumulative=1 < target=3.33), no transition
		// After processing value 2 (cumulative=9 >= target=3.33), transition - bucket 0 gets values 1+2
		// Value 3 goes into bucket 1
		// Result: 2 buckets (values 1+2 combined, then value 3)
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(1), 9),
				new CacheableBucket(new BigDecimal(3), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(3), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should limit buckets to distinct value count")
	void equalizedHistogramWithMoreBucketsThanDistinctValues() {
		// Requesting 10 buckets but only 3 distinct values
		// Should produce only 3 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10,
			100, 200, 300
		);
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(100), 1),
				new CacheableBucket(new BigDecimal(200), 1),
				new CacheableBucket(new BigDecimal(300), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(300), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle all same values")
	void equalizedHistogramWithAllSameValues() {
		// All items have the same value
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			100, 100, 100, 100, 100
		);
		// Should produce single bucket
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(100), 5)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should reduce buckets for sparse data with createOptimalHistogram")
	void equalizedOptimalHistogramReducesBucketsWhenSparse() {
		// Test createOptimalHistogram optimization for sparse data
		final EqualizedHistogramDataCruncher<Integer> cruncher = EqualizedHistogramDataCruncher.createOptimalHistogram(
			"test histogram",
			10,
			0,
			new Integer[]{100, 100, 100, 500, 500},
			value -> value,
			value -> 1,
			BigDecimal::new
		);
		// Only 2 distinct values, should reduce to 2 buckets
		assertArrayEquals(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal(100), 3),
				new CacheableBucket(new BigDecimal(500), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle weighted items correctly")
	void equalizedHistogramWithWeightedItems() {
		// Test with weighted items (using ValueToRecordBitmap)
		final EqualizedHistogramDataCruncher<ValueToRecordBitmap> cruncher = createEqualizedHistogramBucketCruncher(
			3,
			new ValueToRecordBitmap(100, 1, 11, 12, 13, 14, 15, 16, 17, 18, 19),  // weight 10
			new ValueToRecordBitmap(200, 2, 21),                                  // weight 2
			new ValueToRecordBitmap(300, 3, 31, 32, 33, 34),                      // weight 5
			new ValueToRecordBitmap(400, 4, 41, 42),                              // weight 3
			new ValueToRecordBitmap(500, 5, 51, 52, 53, 54, 55)                   // weight 6
		);
		// Total weight = 26, target per bucket (3 buckets) = 8.67
		// Bucket boundaries placed to equalize weight distribution
		final CacheableBucket[] histogram = cruncher.getHistogram();
		assertEquals(3, histogram.length);
		// First bucket should be at value 100
		assertEquals(new BigDecimal(100), histogram[0].threshold());
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle decimal places correctly")
	void equalizedHistogramWithDecimalPlaces() {
		final EqualizedHistogramDataCruncher<BigDecimal> cruncher = createEqualizedBigDecimalCruncher(
			3, 2, 2,
			new BigDecimal("10.00"),
			new BigDecimal("15.50"),
			new BigDecimal("20.00"),
			new BigDecimal("25.50"),
			new BigDecimal("30.00")
		);

		final CacheableBucket[] histogram = cruncher.getHistogram();
		assertEquals(3, histogram.length);

		// Verify thresholds have correct decimal places
		for (CacheableBucket bucket : histogram) {
			assertTrue(bucket.threshold().scale() <= 2,
				"Threshold scale should be <= 2, got: " + bucket.threshold().scale());
		}
		assertEquals(new BigDecimal("30.00"), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should preserve all records when computing histogram")
	void equalizedHistogramPreservesAllRecords() {
		final Integer[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 50, 100, 500, 1000};

		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(4, data);

		int totalOccurrences = 0;
		for (CacheableBucket bucket : cruncher.getHistogram()) {
			totalOccurrences += bucket.occurrences();
		}
		assertEquals(data.length, totalOccurrences, "All records should be accounted for");
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<Integer> createEqualizedIntCruncher(int stepCount, Integer... data) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			value -> value,
			value -> 1,
			BigDecimal::new
		);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<ValueToRecordBitmap> createEqualizedHistogramBucketCruncher(int stepCount, ValueToRecordBitmap... data) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			it -> (int) it.getValue(),
			bucket -> bucket.getRecordIds().size(),
			BigDecimal::new
		);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<BigDecimal> createEqualizedBigDecimalCruncher(
		int stepCount,
		int expectedDecimalPlaces,
		int allowedDecimalPlaces,
		BigDecimal... data
	) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, expectedDecimalPlaces,
			data,
			value -> value.scaleByPowerOfTen(allowedDecimalPlaces).intValueExact(),
			value -> 1,
			value -> allowedDecimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * allowedDecimalPlaces)
		);
	}

}
