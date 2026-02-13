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
import io.evitadb.core.query.extraResult.translator.histogram.producer.EqualizedHistogramDataCruncher.BucketCountMode;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link EqualizedHistogramDataCruncher} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
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
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should compute histogram from two distinct values with EXACT mode padding")
	void equalizedHistogramFromTwoDistinctValues() {
		// Two distinct values with 4 buckets requested in EXACT mode
		// Should produce exactly 4 buckets: 2 data + 2 empty in the gap
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(4, 100, 500);
		// Gap between 100 and 500 is 400, distributed into 3 segments (2 empty buckets)
		// Empty thresholds at 100 + 400/3 = 233, 100 + 2*400/3 = 367
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 1),
				bucket(new BigDecimal(233), 0),
				bucket(new BigDecimal(367), 0),
				bucket(new BigDecimal(500), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should compute histogram from two distinct values with ADAPTIVE mode (no padding)")
	void equalizedHistogramFromTwoDistinctValuesAdaptive() {
		// Two distinct values with ADAPTIVE mode should produce only 2 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4, BucketCountMode.ADAPTIVE, 100, 500
		);
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 1),
				bucket(new BigDecimal(500), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle heavily skewed data with EXACT mode padding")
	void equalizedHistogramFromHeavilySkewedData() {
		// Data heavily skewed: 9 items at value 1, 1 item at value 100
		// With EXACT mode, 4 buckets requested = 2 data + 2 empty in gap
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 100
		);
		// Gap between 1 and 100 is 99, distributed into 3 segments (2 empty buckets)
		// Empty thresholds at 1 + 99/3 = 34, 1 + 2*99/3 = 67
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(1), 9),
				bucket(new BigDecimal(34), 0),
				bucket(new BigDecimal(67), 0),
				bucket(new BigDecimal(100), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle heavily skewed data with ADAPTIVE mode (no padding)")
	void equalizedHistogramFromHeavilySkewedDataAdaptive() {
		// Data heavily skewed: 9 items at value 1, 1 item at value 100
		// With ADAPTIVE mode, only 2 buckets because only 2 distinct values
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4, BucketCountMode.ADAPTIVE,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 100
		);
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(1), 9),
				bucket(new BigDecimal(100), 1)
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
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(10), 10),
				bucket(new BigDecimal(20), 10),
				bucket(new BigDecimal(30), 10),
				bucket(new BigDecimal(40), 10)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(40), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle uneven distribution with EXACT mode padding")
	void equalizedHistogramWithUnevenDistribution() {
		// Data with uneven distribution: 30 at value 10, 5 at value 20, 5 at value 30
		// Total weight = 40, 4 buckets requested, 3 distinct values
		// With EXACT mode: need 1 empty bucket, distributed between 2 equal gaps (10-20 and 20-30)
		// With floor + largest-remainder method, equal gaps get floor(0.5)=0 each,
		// then the 1 remaining bucket goes to the first gap
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
		// Empty bucket placed at midpoint of first gap: 10 + 10/2 = 15
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(10), 30),
				bucket(new BigDecimal(15), 0),
				bucket(new BigDecimal(20), 5),
				bucket(new BigDecimal(30), 5)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(30), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should handle uneven distribution with ADAPTIVE mode (no padding)")
	void equalizedHistogramWithUnevenDistributionAdaptive() {
		// Same data but with ADAPTIVE mode - should produce 3 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4, BucketCountMode.ADAPTIVE,
			// 30 items at value 10
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
			// 5 items at value 20
			20, 20, 20, 20, 20,
			// 5 items at value 30
			30, 30, 30, 30, 30
		);
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(10), 30),
				bucket(new BigDecimal(20), 5),
				bucket(new BigDecimal(30), 5)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(30), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should keep repeated values in same bucket with EXACT mode padding")
	void equalizedHistogramWithRepeatedValuesInMiddle() {
		// Test that items with same value are never split across buckets
		// Data: 1, 2, 2, 2, 2, 2, 2, 2, 2, 3 (8 items at value 2)
		// Algorithm produces 2 data buckets (1 with 9 items, 3 with 1 item)
		// With EXACT mode and 4 buckets requested: need 2 empty buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4,
			1, 2, 2, 2, 2, 2, 2, 2, 2, 3
		);
		// Gap is 2 (from 1 to 3), divided into 3 segments for 2 empty buckets
		// Empty thresholds at 1 + 2/3 ≈ 1.67 → 2, 1 + 4/3 ≈ 2.33 → 2
		// With integer precision, only 1 bucket fits in the gap (at 2).
		// The second empty bucket extends the range beyond 3.
		// EXACT mode guarantees exactly 4 buckets.
		final CacheableBucket[] histogram = cruncher.getHistogram();
		assertEquals(4, histogram.length, "EXACT mode must return exactly 4 buckets");

		// Bucket 0: data bucket at threshold 1 (contains 9 items: 1 at value 1, 8 at value 2)
		assertEquals(new BigDecimal(1), histogram[0].threshold());
		assertEquals(9, histogram[0].occurrences());

		// Bucket 1: empty bucket in gap at threshold 2
		assertEquals(new BigDecimal(2), histogram[1].threshold());
		assertEquals(0, histogram[1].occurrences());

		// Bucket 2: data bucket at threshold 3
		assertEquals(new BigDecimal(3), histogram[2].threshold());
		assertEquals(1, histogram[2].occurrences());

		// Bucket 3: extended empty bucket at threshold 4
		assertEquals(new BigDecimal(4), histogram[3].threshold());
		assertEquals(0, histogram[3].occurrences());

		// Verify strict monotonicity
		for (int i = 1; i < histogram.length; i++) {
			assertTrue(histogram[i].threshold().compareTo(histogram[i - 1].threshold()) > 0,
				"Thresholds must be strictly increasing");
		}

		// Max value should be the extended threshold (4)
		assertTrue(cruncher.getMaxValue().compareTo(new BigDecimal(3)) > 0,
			"Max value should be extended beyond original data max");
	}

	@Test
	@DisplayName("Should keep repeated values in same bucket with ADAPTIVE mode (no padding)")
	void equalizedHistogramWithRepeatedValuesInMiddleAdaptive() {
		// Same data with ADAPTIVE mode - should produce 2 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4, BucketCountMode.ADAPTIVE,
			1, 2, 2, 2, 2, 2, 2, 2, 2, 3
		);
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(1), 9),
				bucket(new BigDecimal(3), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(3), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should pad to exact bucket count with EXACT mode")
	void equalizedHistogramWithMoreBucketsThanDistinctValues() {
		// Requesting 10 buckets with only 3 distinct values in EXACT mode
		// Should produce exactly 10 buckets: 3 data + 7 empty distributed in gaps
		// Two equal gaps (100-200, 200-300), each size 100
		// With floor + largest-remainder: floor(3.5)=3 each, 1 remaining goes to first
		// Result: 4 in first gap, 3 in second gap
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10,
			100, 200, 300
		);
		// First gap [100, 200]: 4 empty buckets at 100 + 100/5*i = 120, 140, 160, 180
		// Second gap [200, 300]: 3 empty buckets at 200 + 100/4*i = 225, 250, 275
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 1),
				bucket(new BigDecimal(120), 0),
				bucket(new BigDecimal(140), 0),
				bucket(new BigDecimal(160), 0),
				bucket(new BigDecimal(180), 0),
				bucket(new BigDecimal(200), 1),
				bucket(new BigDecimal(225), 0),
				bucket(new BigDecimal(250), 0),
				bucket(new BigDecimal(275), 0),
				bucket(new BigDecimal(300), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(300), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should limit buckets to distinct value count with ADAPTIVE mode")
	void equalizedHistogramWithMoreBucketsThanDistinctValuesAdaptive() {
		// Requesting 10 buckets with only 3 distinct values in ADAPTIVE mode
		// Should produce only 3 buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.ADAPTIVE,
			100, 200, 300
		);
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 1),
				bucket(new BigDecimal(200), 1),
				bucket(new BigDecimal(300), 1)
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
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 5)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("ADAPTIVE mode reduces buckets for sparse data")
	void adaptiveModeReducesBucketsWhenSparse() {
		// ADAPTIVE mode naturally reduces bucket count for sparse data because
		// the equalized algorithm caps bucket count to number of distinct values.
		// This is the behavior that makes createOptimalHistogram redundant.
		final EqualizedHistogramDataCruncher<Integer> cruncher = new EqualizedHistogramDataCruncher<>(
			"test histogram",
			10,
			0,
			new Integer[]{100, 100, 100, 500, 500},
			value -> value,
			value -> 1,
			BigDecimal::new,
			BucketCountMode.ADAPTIVE
		);
		// Only 2 distinct values, ADAPTIVE mode caps to 2 buckets
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 3),
				bucket(new BigDecimal(500), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(new BigDecimal(500), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Equalized algorithm naturally avoids empty buckets - ADAPTIVE provides full optimization")
	void equalizedAlgorithmNaturallyAvoidsEmptyBuckets() {
		// The equalized histogram algorithm places bucket boundaries based on cumulative frequency,
		// which means it naturally creates non-empty buckets. This test demonstrates that
		// ADAPTIVE mode provides full optimization for equalized histograms because the algorithm
		// inherently minimizes empty buckets by placing boundaries at data value transitions.
		//
		// Historical note: A createOptimalHistogram method was previously added to mirror
		// HistogramDataCruncher, but testing proved it was redundant for equalized histograms.

		// Test with bimodal data: heavy at extremes, light in middle
		final ValueToRecordBitmap[] bimodalData = new ValueToRecordBitmap[]{
			createWeightedValue(1, 1000),   // 1000 records at value 1
			createWeightedValue(2, 1),
			createWeightedValue(3, 1),
			createWeightedValue(4, 1),
			createWeightedValue(5, 1),
			createWeightedValue(6, 1),
			createWeightedValue(7, 1),
			createWeightedValue(8, 1),
			createWeightedValue(9, 1),
			createWeightedValue(10, 1000)   // 1000 records at value 10
		};

		// ADAPTIVE mode
		final EqualizedHistogramDataCruncher<ValueToRecordBitmap> adaptiveCruncher =
			new EqualizedHistogramDataCruncher<>(
				"test histogram",
				10, 0,
				bimodalData,
				it -> (int) it.getValue(),
				bucket -> bucket.getRecordIds().size(),
				BigDecimal::new,
				BucketCountMode.ADAPTIVE
			);

		final CacheableBucket[] adaptiveHistogram = adaptiveCruncher.getHistogram();

		// Count non-empty buckets in ADAPTIVE result
		int nonEmptyAdaptive = 0;
		for (CacheableBucket bucket : adaptiveHistogram) {
			if (bucket.occurrences() > 0) {
				nonEmptyAdaptive++;
			}
		}

		// For equalized histograms, all buckets are non-empty because boundaries
		// are placed at data value transitions, not arbitrary intervals
		assertEquals(adaptiveHistogram.length, nonEmptyAdaptive,
			"Equalized algorithm should produce all non-empty buckets");

		// Verify data integrity
		int totalAdaptive = 0;
		for (CacheableBucket bucket : adaptiveHistogram) {
			totalAdaptive += bucket.occurrences();
		}
		assertEquals(2008, totalAdaptive, "Should have 2008 total records");
	}

	@Test
	@DisplayName("ADAPTIVE mode caps to distinct value count - 2 values produce 2 buckets")
	void adaptiveModeCapsToDistinctValueCount() {
		// When there are only 2 distinct values, ADAPTIVE mode correctly caps to 2 buckets
		// regardless of how many buckets were requested. This is the core mechanism that
		// makes separate optimization logic redundant for equalized histograms.
		//
		// Historical note: A createOptimalHistogram method was previously considered,
		// but it would produce identical results to ADAPTIVE for this case.

		final ValueToRecordBitmap[] twoValueData = new ValueToRecordBitmap[]{
			createWeightedValue(100, 500),
			createWeightedValue(500, 500)
		};

		// ADAPTIVE mode with 10 buckets requested
		final EqualizedHistogramDataCruncher<ValueToRecordBitmap> adaptiveCruncher =
			new EqualizedHistogramDataCruncher<>(
				"test histogram",
				10, 0,
				twoValueData,
				it -> (int) it.getValue(),
				bucket -> bucket.getRecordIds().size(),
				BigDecimal::new,
				BucketCountMode.ADAPTIVE
			);

		// Should produce exactly 2 buckets (capped to distinct value count)
		assertEquals(2, adaptiveCruncher.getHistogram().length,
			"ADAPTIVE should cap to 2 buckets for 2 distinct values");

		// Verify bucket contents
		assertBucketsEqual(
			new CacheableBucket[]{
				bucket(new BigDecimal(100), 500),
				bucket(new BigDecimal(500), 500)
			},
			adaptiveCruncher.getHistogram()
		);
	}

	@Test
	@DisplayName("ADAPTIVE handles extreme weight distribution - all buckets non-empty")
	void adaptiveHandlesExtremeWeightDistribution() {
		// This test demonstrates that ADAPTIVE mode handles extreme weight distributions
		// correctly. Even with very uneven weights, the equalized algorithm places
		// boundaries at data value transitions, resulting in non-empty buckets.
		//
		// This is the key insight that makes separate optimization logic redundant:
		// equalized histograms inherently avoid empty buckets because they use
		// cumulative frequency, not fixed-width intervals.

		// Extreme case: 3 distinct values with 1000:1:1000 weights
		final ValueToRecordBitmap[] threeValueData = new ValueToRecordBitmap[]{
			createWeightedValue(10, 1000),   // very heavy
			createWeightedValue(50, 1),      // very light
			createWeightedValue(100, 1000)   // very heavy
		};

		final EqualizedHistogramDataCruncher<ValueToRecordBitmap> adaptiveCruncher =
			new EqualizedHistogramDataCruncher<>(
				"test histogram",
				3, 0,
				threeValueData,
				it -> (int) it.getValue(),
				bucket -> bucket.getRecordIds().size(),
				BigDecimal::new,
				BucketCountMode.ADAPTIVE
			);

		final CacheableBucket[] adaptiveResult = adaptiveCruncher.getHistogram();

		// Count non-empty
		int nonEmpty = 0;
		for (CacheableBucket bucket : adaptiveResult) {
			if (bucket.occurrences() > 0) {
				nonEmpty++;
			}
		}

		// The equalized algorithm produces all non-empty buckets
		// because it places boundaries at value transitions, not arbitrary intervals
		assertEquals(nonEmpty, adaptiveResult.length,
			"Equalized histogram should produce all non-empty buckets");
		assertTrue(nonEmpty >= 2,
			"Equalized histogram should have at least 2 non-empty buckets");

		// Verify total weight is preserved
		int totalWeight = 0;
		for (CacheableBucket bucket : adaptiveResult) {
			totalWeight += bucket.occurrences();
		}
		assertEquals(2001, totalWeight, "Total weight should be 2001 (1000 + 1 + 1000)");
	}

	@Test
	@DisplayName("ADAPTIVE handles extreme weight distribution - all buckets non-empty")
	void adaptiveHandlesExtremeWeightDistributionAndJitter() {
		// This test demonstrates that ADAPTIVE mode handles extreme weight distributions
		// correctly. Even with very uneven weights, the equalized algorithm places
		// boundaries at data value transitions, resulting in non-empty buckets.
		//
		// This is the key insight that makes separate optimization logic redundant:
		// equalized histograms inherently avoid empty buckets because they use
		// cumulative frequency, not fixed-width intervals.

		// Extreme case: 3 distinct values with 1000:1:1000 weights
		final ValueToRecordBitmap[] threeValueData = Stream.of(
			createWeightedValue(10, 1000, 10),   // very heavy
			createWeightedValue(50, 1, 1),       // very light
			createWeightedValue(100, 1000, 50)   // very heavy
		)
			.flatMap(Stream::of)
			.toArray(ValueToRecordBitmap[]::new);

		final EqualizedHistogramDataCruncher<ValueToRecordBitmap> adaptiveCruncher =
			new EqualizedHistogramDataCruncher<>(
				"test histogram",
				5, 0,
				threeValueData,
				it -> (int) it.getValue(),
				bucket -> bucket.getRecordIds().size(),
				BigDecimal::new,
				BucketCountMode.ADAPTIVE
			);

		final CacheableBucket[] adaptiveResult = adaptiveCruncher.getHistogram();

		// Count non-empty
		int nonEmpty = 0;
		for (CacheableBucket bucket : adaptiveResult) {
			if (bucket.occurrences() > 0) {
				nonEmpty++;
			}
		}

		// The equalized algorithm produces all non-empty buckets
		// because it places boundaries at value transitions, not arbitrary intervals
		assertEquals(nonEmpty, adaptiveResult.length,
		             "Equalized histogram should produce all non-empty buckets");
		assertTrue(nonEmpty >= 2,
		           "Equalized histogram should have at least 2 non-empty buckets");

		// Verify total weight is preserved
		int totalWeight = 0;
		for (CacheableBucket bucket : adaptiveResult) {
			totalWeight += bucket.occurrences();
		}
		assertEquals(2001, totalWeight, "Total weight should be 2001 (1000 + 1 + 1000)");
	}

	/**
	 * Helper to create a ValueToRecordBitmap with specified value and weight (number of records).
	 */
	@Nonnull
	private static ValueToRecordBitmap createWeightedValue(int value, int weight) {
		final int[] recordIds = new int[weight];
		for (int i = 0; i < weight; i++) {
			recordIds[i] = value * 10000 + i;  // unique record IDs
		}
		return new ValueToRecordBitmap(value, recordIds);
	}

	/**
	 * Helper to create a ValueToRecordBitmap with specified value and weight (number of records).
	 */
	@Nonnull
	private static ValueToRecordBitmap[] createWeightedValue(int value, int weight, int valueJitter) {
		final long seed = System.nanoTime();
		log.info("createWeightedValue using seed: {} for value={}, weight={}, jitter={}", seed, value, weight, valueJitter);
		final Random rnd = new Random(seed);
		final Map<Integer, CompositeIntArray> recordIds = CollectionUtils.createHashMap(valueJitter);
		for (int i = 0; i < weight; i++) {
			recordIds.computeIfAbsent(
				value + rnd.nextInt(valueJitter),
				k -> new CompositeIntArray()
			).add(value * 10000 + i);  // unique record IDs
		}
		return recordIds.entrySet().stream().map(it -> new ValueToRecordBitmap(it.getKey(), it.getValue().toArray()))
			.sorted(Comparator.comparingInt(o -> (Integer) o.getValue()))
			.toArray(ValueToRecordBitmap[]::new);
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

	@Test
	@DisplayName("Should throw exception for empty source data")
	void shouldThrowExceptionForEmptySourceData() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new EqualizedHistogramDataCruncher<>(
				"test histogram",
				4, 0,
				new Integer[]{},
				value -> value,
				value -> 1,
				BigDecimal::new,
				BucketCountMode.EXACT
			)
		);
	}

	@Test
	@DisplayName("Should maintain monotonicity when padding very close thresholds")
	void shouldMaintainMonotonicityWhenPaddingCloseThresholds() {
		// Create data with two very close values - when padded with many
		// empty buckets, the rounding logic must ensure strict monotonicity
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.EXACT, 100, 101
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// Verify strict monotonicity: each threshold must be > previous
		for (int i = 1; i < histogram.length; i++) {
			assertTrue(
				histogram[i].threshold().compareTo(histogram[i - 1].threshold()) > 0,
				"Thresholds must be strictly increasing, but bucket " + i +
					" (" + histogram[i].threshold() + ") is not greater than bucket " +
					(i - 1) + " (" + histogram[i - 1].threshold() + ")"
			);
		}
	}

	@Test
	@DisplayName("EXACT mode guarantees requested bucket count by extending range")
	void exactModeGuaranteesBucketCountByExtendingRange() {
		// Data with very small gap that can't fit requested empty buckets
		// Values 100, 101 with bucketCount=10 and limitDecimalPlacesTo=0
		// Gap of 1 can only fit 0 empty buckets in the gap, so 8 must go to extension
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.EXACT, 100, 101
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// EXACT mode must return exactly the requested bucket count
		assertEquals(10, histogram.length,
			"EXACT mode must return exactly 10 buckets");

		// First bucket should be at 100, second at 101
		assertEquals(new BigDecimal(100), histogram[0].threshold());
		assertEquals(new BigDecimal(101), histogram[1].threshold());

		// All data is in first two buckets
		int totalOccurrences = 0;
		for (CacheableBucket bucket : histogram) {
			totalOccurrences += bucket.occurrences();
		}
		assertEquals(2, totalOccurrences, "Total occurrences should be 2");
	}

	@Test
	@DisplayName("Extended buckets maintain strict monotonicity")
	void extendedBucketsMaintainStrictMonotonicity() {
		// Small gap scenario forcing range extension
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			15, BucketCountMode.EXACT, 100, 102
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// Verify exact count
		assertEquals(15, histogram.length,
			"EXACT mode must return exactly 15 buckets");

		// Verify all thresholds are strictly increasing including extended ones
		for (int i = 1; i < histogram.length; i++) {
			assertTrue(
				histogram[i].threshold().compareTo(histogram[i - 1].threshold()) > 0,
				"All thresholds must be strictly increasing, but bucket " + i +
					" (" + histogram[i].threshold() + ") is not greater than bucket " +
					(i - 1) + " (" + histogram[i - 1].threshold() + ")"
			);
		}
	}

	@Test
	@DisplayName("getMaxValue returns extended max when range is extended")
	void getMaxValueReturnsExtendedMax() {
		// Small gap forcing extension - max should be > lastThreshold
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.EXACT, 100, 101
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();
		final BigDecimal maxValue = cruncher.getMaxValue();

		// Max value should be >= last bucket threshold
		assertTrue(maxValue.compareTo(histogram[histogram.length - 1].threshold()) >= 0,
			"Max value should be >= last bucket threshold");

		// Max value should be > original data max (101) since range was extended
		assertTrue(maxValue.compareTo(new BigDecimal(101)) > 0,
			"Max value should be > 101 since range was extended");
	}

	@Test
	@DisplayName("Extended buckets have zero relativeFrequency (0 occurrences)")
	void extendedBucketsHaveZeroRelativeFrequency() {
		// Scenario requiring range extension
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.EXACT, 100, 101
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// Data buckets (first two) should have non-zero relativeFrequency
		assertTrue(
			histogram[0].relativeFrequency().compareTo(BigDecimal.ZERO) > 0,
			"First data bucket should have relativeFrequency > 0"
		);
		assertTrue(
			histogram[1].relativeFrequency().compareTo(BigDecimal.ZERO) > 0,
			"Second data bucket should have relativeFrequency > 0"
		);

		// Extended buckets (index 2+) should have relativeFrequency = 0 (no occurrences)
		for (int i = 2; i < histogram.length; i++) {
			assertEquals(
				0,
				histogram[i].relativeFrequency().compareTo(BigDecimal.ZERO),
				"Extended bucket " + i + " should have relativeFrequency = 0 (no occurrences), got " +
					histogram[i].relativeFrequency()
			);
		}
	}

	@Test
	@DisplayName("EXACT mode with decimal places extends correctly")
	void exactModeWithDecimalPlacesExtendsCorrectly() {
		// Test with decimal places to ensure extension respects precision
		final EqualizedHistogramDataCruncher<BigDecimal> cruncher = createEqualizedBigDecimalCruncher(
			10, 2, 2, BucketCountMode.EXACT,
			new BigDecimal("10.00"),
			new BigDecimal("10.05")
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// Must return exactly 10 buckets
		assertEquals(10, histogram.length,
			"EXACT mode must return exactly 10 buckets");

		// All thresholds must be strictly increasing
		for (int i = 1; i < histogram.length; i++) {
			assertTrue(
				histogram[i].threshold().compareTo(histogram[i - 1].threshold()) > 0,
				"Thresholds must be strictly increasing"
			);
		}

		// All thresholds must have correct decimal scale
		for (CacheableBucket bucket : histogram) {
			assertTrue(bucket.threshold().scale() <= 2,
				"Threshold scale should be <= 2, got: " + bucket.threshold().scale());
		}
	}

	@Test
	@DisplayName("Relative frequencies should sum to approximately 100")
	void relativeFrequenciesShouldSumTo100() {
		// Test with various data distributions
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			5, BucketCountMode.ADAPTIVE,
			10, 10, 10, 20, 20, 30, 40, 50, 60, 70, 80, 90, 100
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// Sum all relative frequencies
		BigDecimal sum = BigDecimal.ZERO;
		for (CacheableBucket bucket : histogram) {
			sum = sum.add(bucket.relativeFrequency());
		}

		// Should sum to approximately 100 (allow small rounding error)
		assertTrue(
			sum.compareTo(new BigDecimal("99")) >= 0 && sum.compareTo(new BigDecimal("101")) <= 0,
			"Relative frequencies should sum to ~100, but got " + sum
		);
	}

	@Test
	@DisplayName("Empty buckets should have zero relativeFrequency")
	void emptyBucketsShouldHaveZeroRelativeFrequency() {
		// EXACT mode creates empty buckets in gaps
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			10, BucketCountMode.EXACT, 100, 200, 300
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		for (int i = 0; i < histogram.length; i++) {
			if (histogram[i].occurrences() == 0) {
				assertEquals(
					0,
					histogram[i].relativeFrequency().compareTo(BigDecimal.ZERO),
					"Empty bucket " + i + " should have relativeFrequency = 0, got " +
						histogram[i].relativeFrequency()
				);
			}
		}
	}

	@Test
	@DisplayName("Relative frequency reflects both occurrences and bucket width")
	void relativeFrequencyReflectsOccurrencesAndWidth() {
		// Two buckets with same width but different occurrences
		// The bucket with more occurrences should have higher relative frequency
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			2, BucketCountMode.ADAPTIVE,
			// 5 items at value 10
			10, 10, 10, 10, 10,
			// 1 item at value 20
			20
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		assertEquals(2, histogram.length);
		// First bucket has 5 occurrences, second has 1
		// First bucket should have higher relative frequency since it has more data
		// packed into same width range
		assertTrue(
			histogram[0].relativeFrequency().compareTo(histogram[1].relativeFrequency()) > 0,
			"Bucket with more occurrences should have higher relative frequency. " +
				"First: " + histogram[0].relativeFrequency() + ", Second: " + histogram[1].relativeFrequency()
		);
	}

	@Test
	@DisplayName("Single bucket should have 100 relativeFrequency")
	void singleBucketShouldHave100RelativeFrequency() {
		// All items have same value - single bucket
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			5, BucketCountMode.ADAPTIVE,
			100, 100, 100, 100, 100
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		assertEquals(1, histogram.length, "Should produce single bucket");
		assertEquals(
			new BigDecimal("100"),
			histogram[0].relativeFrequency(),
			"Single bucket should have relativeFrequency = 100"
		);
	}

	@Test
	@DisplayName("Non-empty buckets in ADAPTIVE mode all have positive relative frequency summing to 100")
	void nonEmptyBucketsHavePositiveRelativeFrequencySummingTo100() {
		// ADAPTIVE mode produces only non-empty buckets
		final EqualizedHistogramDataCruncher<Integer> cruncher = createEqualizedIntCruncher(
			4, BucketCountMode.ADAPTIVE,
			// Varied distribution
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1,  // 10 items at value 1
			50, 50,                          // 2 items at value 50
			100, 100, 100, 100               // 4 items at value 100
		);
		final CacheableBucket[] histogram = cruncher.getHistogram();

		// All buckets should have positive relative frequency
		for (int i = 0; i < histogram.length; i++) {
			assertTrue(
				histogram[i].relativeFrequency().compareTo(BigDecimal.ZERO) > 0,
				"Non-empty bucket " + i + " should have relativeFrequency > 0"
			);
		}

		// Sum should be approximately 100
		BigDecimal sum = BigDecimal.ZERO;
		for (CacheableBucket bucket : histogram) {
			sum = sum.add(bucket.relativeFrequency());
		}
		assertTrue(
			sum.compareTo(new BigDecimal("99")) >= 0 && sum.compareTo(new BigDecimal("101")) <= 0,
			"Relative frequencies should sum to ~100, but got " + sum
		);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<Integer> createEqualizedIntCruncher(int stepCount, Integer... data) {
		return createEqualizedIntCruncher(stepCount, BucketCountMode.EXACT, data);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<Integer> createEqualizedIntCruncher(
		int stepCount,
		@Nonnull BucketCountMode bucketCountMode,
		Integer... data
	) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			value -> value,
			value -> 1,
			BigDecimal::new,
			bucketCountMode
		);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<ValueToRecordBitmap> createEqualizedHistogramBucketCruncher(int stepCount, ValueToRecordBitmap... data) {
		return createEqualizedHistogramBucketCruncher(stepCount, BucketCountMode.EXACT, data);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<ValueToRecordBitmap> createEqualizedHistogramBucketCruncher(
		int stepCount,
		@Nonnull BucketCountMode bucketCountMode,
		ValueToRecordBitmap... data
	) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			it -> (int) it.getValue(),
			bucket -> bucket.getRecordIds().size(),
			BigDecimal::new,
			bucketCountMode
		);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<BigDecimal> createEqualizedBigDecimalCruncher(
		int stepCount,
		int expectedDecimalPlaces,
		int allowedDecimalPlaces,
		BigDecimal... data
	) {
		return createEqualizedBigDecimalCruncher(stepCount, expectedDecimalPlaces, allowedDecimalPlaces, BucketCountMode.EXACT, data);
	}

	@Nonnull
	private static EqualizedHistogramDataCruncher<BigDecimal> createEqualizedBigDecimalCruncher(
		int stepCount,
		int expectedDecimalPlaces,
		int allowedDecimalPlaces,
		@Nonnull BucketCountMode bucketCountMode,
		BigDecimal... data
	) {
		return new EqualizedHistogramDataCruncher<>(
			"test histogram",
			stepCount, expectedDecimalPlaces,
			data,
			value -> value.scaleByPowerOfTen(allowedDecimalPlaces).intValueExact(),
			value -> 1,
			value -> allowedDecimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * allowedDecimalPlaces),
			bucketCountMode
		);
	}

	/**
	 * Helper method to create a CacheableBucket for comparison purposes.
	 * Uses a placeholder relativeFrequency since the exact value is not the focus of most tests.
	 */
	@Nonnull
	private static CacheableBucket bucket(@Nonnull BigDecimal threshold, int occurrences) {
		// Placeholder relativeFrequency - the actual value will be calculated by the cruncher
		return new CacheableBucket(threshold, occurrences, BigDecimal.ZERO);
	}

	/**
	 * Custom assertion that compares only threshold and occurrences, ignoring relativeFrequency.
	 * This is useful for tests that focus on bucket placement rather than relative frequency calculation.
	 */
	private static void assertBucketsEqual(@Nonnull CacheableBucket[] expected, @Nonnull CacheableBucket[] actual) {
		assertEquals(expected.length, actual.length, "Bucket count mismatch");
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].threshold(), actual[i].threshold(),
				"Bucket " + i + " threshold mismatch");
			assertEquals(expected[i].occurrences(), actual[i].occurrences(),
				"Bucket " + i + " occurrences mismatch");
		}
	}

}
