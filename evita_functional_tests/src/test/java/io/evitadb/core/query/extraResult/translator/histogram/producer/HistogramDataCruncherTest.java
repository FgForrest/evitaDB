/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.index.histogram.ValueToRecordBitmap;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link HistogramDataCruncher} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class HistogramDataCruncherTest {

	@Test
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
	void computeHistogramFromSingleValue() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(5, 100);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(100),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromDistinctValues() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 200, 300, 400, 500);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 1),
				new Bucket(1, new BigDecimal(200), 1),
				new Bucket(2, new BigDecimal(300), 1),
				new Bucket(3, new BigDecimal(400), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromLessThanRequiredValues() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 200);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 1),
				new Bucket(1, new BigDecimal(125), 0),
				new Bucket(2, new BigDecimal(150), 0),
				new Bucket(3, new BigDecimal(175), 1)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(200),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedLessThanRequiredValues() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(4, 100, 100, 100, 200, 200);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 3),
				new Bucket(1, new BigDecimal(125), 0),
				new Bucket(2, new BigDecimal(150), 0),
				new Bucket(3, new BigDecimal(175), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(200),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedTwoValuesOnBoundaries() {
		final HistogramDataCruncher<Integer> cruncher = createOptimalCruncher(4, 100, 100, 100, 500, 500);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 3),
				new Bucket(1, new BigDecimal(300), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedLessThanRequiredValuesOnBoundaries() {
		final HistogramDataCruncher<Integer> cruncher = createOptimalCruncher(
			10,
			100, 100, 100,
			500,
			600,
			900, 900,
			1000
		);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 3),
				new Bucket(1, new BigDecimal(280), 0),
				new Bucket(2, new BigDecimal(460), 2),
				new Bucket(3, new BigDecimal(640), 0),
				new Bucket(4, new BigDecimal(820), 3)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(1000),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromDistinctValuesWithLessSteps() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(3, 100, 200, 300, 400, 500);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 2),
				new Bucket(1, new BigDecimal(234), 1),
				new Bucket(2, new BigDecimal(368), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromDistinctValuesWithLessStepsAndSpecificWeight() {
		final HistogramDataCruncher<ValueToRecordBitmap<Integer>> cruncher = createHistogramBucketCruncher(
			3,
			new ValueToRecordBitmap<>(100, 1, 11, 12, 13, 14, 15, 16, 17, 18, 19),
			new ValueToRecordBitmap<>(200, 2, 21),
			new ValueToRecordBitmap<>(300, 3, 31, 32, 33, 34),
			new ValueToRecordBitmap<>(400, 4, 41, 42),
			new ValueToRecordBitmap<>(500, 5, 51, 52, 53, 54, 55)
		);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 12),
				new Bucket(1, new BigDecimal(234), 5),
				new Bucket(2, new BigDecimal(368), 9)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedValues() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(
			4,
			100, 100, 100,
			200, 200,
			300,
			400, 400, 400,
			500, 500, 500, 500
		);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 3),
				new Bucket(1, new BigDecimal(200), 2),
				new Bucket(2, new BigDecimal(300), 1),
				new Bucket(3, new BigDecimal(400), 7)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedValuesWithLessSteps() {
		final HistogramDataCruncher<Integer> cruncher = createIntCruncher(
			3,
			100, 100, 100,
			200, 200,
			300,
			400, 400, 400,
			500, 500, 500, 500
		);
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 5),
				new Bucket(1, new BigDecimal(234), 1),
				new Bucket(2, new BigDecimal(368), 7)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromMultipliedValuesWithEmptyPlaces() {
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
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(100), 3),
				new Bucket(1, new BigDecimal(234), 0),
				new Bucket(2, new BigDecimal(368), 4)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(500),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromValuesWithDecimalPlaces() {
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
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal("10.00"), 3),
				new Bucket(1, new BigDecimal("16.39"), 1),
				new Bucket(2, new BigDecimal("22.78"), 3)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal("29.16"),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromValuesWithDecimalPlacesCelingToOne() {
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
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal("10.0"), 3),
				new Bucket(1, new BigDecimal("16.4"), 1),
				new Bucket(2, new BigDecimal("22.8"), 3)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal("29.2"),
			cruncher.getMaxValue()
		);
	}

	@Test
	void computeHistogramFromValuesWithDecimalPlacesCeiling() {
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
		assertArrayEquals(
			new Bucket[]{
				new Bucket(0, new BigDecimal(10), 3),
				new Bucket(1, new BigDecimal(17), 2),
				new Bucket(2, new BigDecimal(24), 2)
			},
			cruncher.getHistogram()
		);
		assertEquals(
			new BigDecimal(30),
			cruncher.getMaxValue()
		);
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
	private static HistogramDataCruncher<ValueToRecordBitmap<Integer>> createHistogramBucketCruncher(int stepCount, ValueToRecordBitmap<Integer>... data) {
		return new HistogramDataCruncher<>(
			"test histogram",
			stepCount, 0,
			data,
			ValueToRecordBitmap::getValue,
			bucket -> bucket.getRecordIds().size(),
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