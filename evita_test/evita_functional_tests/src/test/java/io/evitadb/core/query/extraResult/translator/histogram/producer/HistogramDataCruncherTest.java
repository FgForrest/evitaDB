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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link HistogramDataCruncher} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("HistogramDataCruncher tests")
class HistogramDataCruncherTest {

	@Test
	@DisplayName("Should throw exception for invalid bucket count")
	void shouldThrowExceptionForInvalidCountOfBuckets() {
		for (int i = 1; i > -2; i--) {
			int bucketCount = i;
			assertThrows(
				InvalidHistogramBucketCountException.class,
				() -> createIntCruncher(bucketCount, 100)
			);
		}
	}

	@Test
	@DisplayName("Should compute histogram from single value")
	void computeHistogramFromSingleValue() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(5, 100);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 1, "100.00")
		);
		assertEquals(
			new BigDecimal(100),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from distinct values")
	void computeHistogramFromDistinctValues() {
		// Total occurrences: 5 (1+1+1+2)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 200, 300, 400, 500);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 1, "20.00"),  // 1/5 * 100
			bucket(new BigDecimal(200), 1, "20.00"),  // 1/5 * 100
			bucket(new BigDecimal(300), 1, "20.00"),  // 1/5 * 100
			bucket(new BigDecimal(400), 2, "40.00")   // 2/5 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from less than required values")
	void computeHistogramFromLessThanRequiredValues() {
		// Total occurrences: 2 (1+0+0+1)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 200);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 1, "50.00"),  // 1/2 * 100
			bucket(new BigDecimal(125), 0, "0.00"),   // 0/2 * 100
			bucket(new BigDecimal(150), 0, "0.00"),   // 0/2 * 100
			bucket(new BigDecimal(175), 1, "50.00")   // 1/2 * 100
		);
		assertEquals(
			new BigDecimal(200),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied less than required values")
	void computeHistogramFromMultipliedLessThanRequiredValues() {
		// Total occurrences: 5 (3+0+0+2)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 100, 100, 200, 200);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 3, "60.00"),  // 3/5 * 100
			bucket(new BigDecimal(125), 0, "0.00"),   // 0/5 * 100
			bucket(new BigDecimal(150), 0, "0.00"),   // 0/5 * 100
			bucket(new BigDecimal(175), 2, "40.00")   // 2/5 * 100
		);
		assertEquals(
			new BigDecimal(200),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied two values on boundaries")
	void computeHistogramFromMultipliedTwoValuesOnBoundaries() {
		// Total occurrences: 5 (3+2)
		final HistogramDataCruncher<Integer> cruncher = createOptimalCruncher(4, 100, 100, 100, 500, 500);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 3, "60.00"),  // 3/5 * 100
			bucket(new BigDecimal(300), 2, "40.00")   // 2/5 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied less than required values on boundaries")
	void computeHistogramFromMultipliedLessThanRequiredValuesOnBoundaries() {
		// Total occurrences: 8 (3+0+2+0+3)
		final HistogramDataCruncher<Integer> cruncher = createOptimalCruncher(
			10,
			100, 100, 100,
			500,
			600,
			900, 900,
			1000
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 3, "37.50"),   // 3/8 * 100
			bucket(new BigDecimal(280), 0, "0.00"),    // 0/8 * 100
			bucket(new BigDecimal(460), 2, "25.00"),   // 2/8 * 100
			bucket(new BigDecimal(640), 0, "0.00"),    // 0/8 * 100
			bucket(new BigDecimal(820), 3, "37.50")    // 3/8 * 100
		);
		assertEquals(
			new BigDecimal(1000),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from distinct values with less steps")
	void computeHistogramFromDistinctValuesWithLessSteps() {
		// Total occurrences: 5 (2+1+2)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(3, 100, 200, 300, 400, 500);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 2, "40.00"),  // 2/5 * 100
			bucket(new BigDecimal(234), 1, "20.00"),  // 1/5 * 100
			bucket(new BigDecimal(368), 2, "40.00")   // 2/5 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from distinct values with less steps and specific weight")
	void computeHistogramFromDistinctValuesWithLessStepsAndSpecificWeight() {
		// Total weight: 26 (10+2+5+3+6) = 26
		final HistogramDataCruncher<ValueToRecordBitmap> cruncher = createHistogramBucketCruncher(
			3,
			new ValueToRecordBitmap(100, 1, 11, 12, 13, 14, 15, 16, 17, 18, 19),  // weight 10
			new ValueToRecordBitmap(200, 2, 21),                                   // weight 2
			new ValueToRecordBitmap(300, 3, 31, 32, 33, 34),                       // weight 5
			new ValueToRecordBitmap(400, 4, 41, 42),                               // weight 3
			new ValueToRecordBitmap(500, 5, 51, 52, 53, 54, 55)                    // weight 6
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 12, "46.15"),  // 12/26 * 100
			bucket(new BigDecimal(234), 5, "19.23"),   // 5/26 * 100
			bucket(new BigDecimal(368), 9, "34.62")    // 9/26 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied values")
	void computeHistogramFromMultipliedValues() {
		// Total occurrences: 13 (3+2+1+7)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(
			4,
			100, 100, 100,
			200, 200,
			300,
			400, 400, 400,
			500, 500, 500, 500
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 3, "23.08"),   // 3/13 * 100
			bucket(new BigDecimal(200), 2, "15.38"),   // 2/13 * 100
			bucket(new BigDecimal(300), 1, "7.69"),    // 1/13 * 100
			bucket(new BigDecimal(400), 7, "53.85")    // 7/13 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied values with less steps")
	void computeHistogramFromMultipliedValuesWithLessSteps() {
		// Total occurrences: 13 (5+1+7)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(
			3,
			100, 100, 100,
			200, 200,
			300,
			400, 400, 400,
			500, 500, 500, 500
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 5, "38.46"),  // 5/13 * 100
			bucket(new BigDecimal(234), 1, "7.69"),   // 1/13 * 100
			bucket(new BigDecimal(368), 7, "53.85")   // 7/13 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from multiplied values with empty places")
	void computeHistogramFromMultipliedValuesWithEmptyPlaces() {
		// Total occurrences: 7 (3+0+4)
		final HistogramDataCruncher<BigDecimal> cruncher = createBigDecimalCruncher(
			3, 0, 0,
			new BigDecimal(100),
			new BigDecimal(100),
			new BigDecimal(100),
			new BigDecimal(500),
			new BigDecimal(500),
			new BigDecimal(500),
			new BigDecimal(500)
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(100), 3, "42.86"),  // 3/7 * 100
			bucket(new BigDecimal(234), 0, "0.00"),   // 0/7 * 100
			bucket(new BigDecimal(368), 4, "57.14")   // 4/7 * 100
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from values with decimal places")
	void computeHistogramFromValuesWithDecimalPlaces() {
		// Total occurrences: 7 (3+1+3)
		final HistogramDataCruncher<BigDecimal> cruncher = createBigDecimalCruncher(
			3, 2, 2,
			new BigDecimal("10"),
			new BigDecimal("11.5"),
			new BigDecimal("15.75"),
			new BigDecimal("21.01"),
			new BigDecimal("23.72"),
			new BigDecimal("24.01"),
			new BigDecimal("29.16")
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal("10.00"), 3, "42.86"),  // 3/7 * 100
			bucket(new BigDecimal("16.39"), 1, "14.29"),  // 1/7 * 100
			bucket(new BigDecimal("22.78"), 3, "42.86")   // 3/7 * 100
		);
		assertEquals(
			new BigDecimal("29.16"),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from values with decimal places ceiling to one")
	void computeHistogramFromValuesWithDecimalPlacesCelingToOne() {
		// Total occurrences: 7 (3+1+3)
		final HistogramDataCruncher<BigDecimal> cruncher = createBigDecimalCruncher(
			3, 1, 2,
			new BigDecimal("10"),
			new BigDecimal("11.5"),
			new BigDecimal("15.75"),
			new BigDecimal("21.01"),
			new BigDecimal("23.72"),
			new BigDecimal("24.01"),
			new BigDecimal("29.16")
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal("10.0"), 3, "42.86"),  // 3/7 * 100
			bucket(new BigDecimal("16.4"), 1, "14.29"),  // 1/7 * 100
			bucket(new BigDecimal("22.8"), 3, "42.86")   // 3/7 * 100
		);
		assertEquals(
			new BigDecimal("29.2"),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from values with decimal places ceiling")
	void computeHistogramFromValuesWithDecimalPlacesCeiling() {
		// Total occurrences: 7 (3+2+2)
		final HistogramDataCruncher<BigDecimal> cruncher = createBigDecimalCruncher(
			3, 0, 2,
			new BigDecimal("10"),
			new BigDecimal("11.5"),
			new BigDecimal("15.75"),
			new BigDecimal("21.01"),
			new BigDecimal("23.72"),
			new BigDecimal("24.01"),
			new BigDecimal("29.16")
		);
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(10), 3, "42.86"),  // 3/7 * 100
			bucket(new BigDecimal(17), 2, "28.57"),  // 2/7 * 100
			bucket(new BigDecimal(24), 2, "28.57")   // 2/7 * 100
		);
		assertEquals(
			new BigDecimal(30),
			cruncher.getMaxValue()
		);
	}

	@Test
	@DisplayName("Should compute histogram from heavily skewed data")
	void computeHistogramFromHeavilySkewedData() {
		// Data heavily skewed to low values - demonstrates STANDARD behavior limitation
		// Total occurrences: 10 (9+0+0+1)
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(
			4,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 100
		);
		// With STANDARD behavior, most records end up in first bucket
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(1), 9, "90.00"),   // 9/10 * 100
			bucket(new BigDecimal(26), 0, "0.00"),   // 0/10 * 100
			bucket(new BigDecimal(51), 0, "0.00"),   // 0/10 * 100
			bucket(new BigDecimal(76), 1, "10.00")   // 1/10 * 100
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	@Test
	@DisplayName("Should compute optimal histogram from heavily skewed data")
	void computeOptimalHistogramFromHeavilySkewedData() {
		// Total occurrences: 10 (9+1)
		final HistogramDataCruncher<Integer> cruncher = createOptimalCruncher(
			4,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 100
		);
		// OPTIMIZED reduces buckets when there are large gaps
		assertHistogram(
			cruncher.getHistogram(),
			bucket(new BigDecimal(1), 9, "90.00"),   // 9/10 * 100
			bucket(new BigDecimal(51), 1, "10.00")   // 1/10 * 100
		);
		assertEquals(new BigDecimal(100), cruncher.getMaxValue());
	}

	/**
	 * Helper method to create a bucket with relativeFrequency.
	 */
	@Nonnull
	private static CacheableBucket bucket(@Nonnull BigDecimal threshold, int occurrences, @Nonnull String relativeFrequency) {
		return new CacheableBucket(threshold, occurrences, new BigDecimal(relativeFrequency));
	}

	/**
	 * Helper method to assert histogram contents, comparing threshold, occurrences, and relativeFrequency.
	 */
	private static void assertHistogram(@Nonnull CacheableBucket[] actual, @Nonnull CacheableBucket... expected) {
		assertEquals(expected.length, actual.length, "Histogram bucket count mismatch");
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].threshold(), actual[i].threshold(),
				"Bucket " + i + " threshold mismatch");
			assertEquals(expected[i].occurrences(), actual[i].occurrences(),
				"Bucket " + i + " occurrences mismatch");
			assertEquals(expected[i].relativeFrequency(), actual[i].relativeFrequency(),
				"Bucket " + i + " relativeFrequency mismatch");
		}
	}

	@Nonnull
	private static HistogramDataCruncher<Integer> createIntCruncher(int stepCount, Integer... data) {
		return new HistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			value -> value,
			value -> 1,
			BigDecimal::new,
			BigDecimal::intValueExact
		);
	}

	@Nonnull
	private static HistogramDataCruncher<ValueToRecordBitmap> createHistogramBucketCruncher(int stepCount, ValueToRecordBitmap... data) {
		return new HistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			it -> (int) it.getValue(),
			CacheableBucket -> CacheableBucket.getRecordIds().size(),
			BigDecimal::new,
			BigDecimal::intValueExact
		);
	}

	@Nonnull
	private static HistogramDataCruncher<Integer> createOptimalCruncher(int stepCount, Integer... data) {
		return HistogramDataCruncher.createOptimalHistogram(
			"test histogram",
			stepCount, 0,
			data,
			value -> value,
			value -> 1,
			BigDecimal::new,
			BigDecimal::intValueExact
		);
	}

	@Nonnull
	private static HistogramDataCruncher<BigDecimal> createBigDecimalCruncher(int stepCount, int expectedDecimalPlaces, int allowedDecimalPlaces, BigDecimal... histogramBuckets) {
		return new HistogramDataCruncher<>(
			"test histogram",
			stepCount, expectedDecimalPlaces,
			histogramBuckets,
			value -> value.scaleByPowerOfTen(allowedDecimalPlaces).intValueExact(),
			value -> 1,
			value -> allowedDecimalPlaces == 0 ? new BigDecimal(value) : new BigDecimal(value).scaleByPowerOfTen(-1 * allowedDecimalPlaces),
			value -> allowedDecimalPlaces == 0 ? value.intValueExact() : value.scaleByPowerOfTen(allowedDecimalPlaces).intValueExact()
		);
	}

}
