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

import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link HistogramContract} contract, including the EMPTY constant,
 * the {@link Bucket} record, and the {@link HistogramContract#formatHistogram} static method.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramContract")
class HistogramContractTest implements EvitaTestSupport {

	@Nested
	@DisplayName("EMPTY constant")
	class EmptyConstant {

		@Test
		@DisplayName("should return zero for min")
		void shouldReturnZeroForMin() {
			assertEquals(BigDecimal.ZERO, HistogramContract.EMPTY.getMin());
		}

		@Test
		@DisplayName("should return zero for max")
		void shouldReturnZeroForMax() {
			assertEquals(BigDecimal.ZERO, HistogramContract.EMPTY.getMax());
		}

		@Test
		@DisplayName("should return zero for overall count")
		void shouldReturnZeroForOverallCount() {
			assertEquals(0, HistogramContract.EMPTY.getOverallCount());
		}

		@Test
		@DisplayName("should return empty buckets array")
		void shouldReturnEmptyBucketsArray() {
			assertEquals(0, HistogramContract.EMPTY.getBuckets().length);
		}

		@Test
		@DisplayName("should return positive size estimate")
		void shouldReturnPositiveSizeEstimate() {
			assertTrue(HistogramContract.EMPTY.estimateSize() > 0);
		}

		@Test
		@DisplayName("should return EMPTY HISTOGRAM string")
		void shouldReturnEmptyHistogramString() {
			assertEquals("EMPTY HISTOGRAM", HistogramContract.EMPTY.toString());
		}
	}

	@Nested
	@DisplayName("Bucket record")
	class BucketTests {

		@Test
		@DisplayName("should store all fields correctly")
		void shouldStoreAllFieldsCorrectly() {
			final Bucket bucket = new Bucket(BigDecimal.TEN, 5, true, new BigDecimal("42.5"));
			assertEquals(BigDecimal.TEN, bucket.threshold());
			assertEquals(5, bucket.occurrences());
			assertTrue(bucket.requested());
			assertEquals(new BigDecimal("42.5"), bucket.relativeFrequency());
		}

		@Test
		@DisplayName("should have correct toString for requested bucket")
		void shouldHaveCorrectToStringForRequestedBucket() {
			final Bucket bucket = new Bucket(BigDecimal.TEN, 5, true, new BigDecimal("42.5"));
			final String result = bucket.toString();
			assertTrue(result.contains("^"));
			assertTrue(result.contains("10"));
			assertTrue(result.contains("5"));
		}

		@Test
		@DisplayName("should have correct toString for non-requested bucket")
		void shouldHaveCorrectToStringForNonRequestedBucket() {
			final Bucket bucket = new Bucket(BigDecimal.TEN, 5, false, new BigDecimal("42.5"));
			final String result = bucket.toString();
			assertFalse(result.contains("^"));
		}

		@Test
		@DisplayName("should have positive memory size constant")
		void shouldHavePositiveMemorySizeConstant() {
			assertTrue(Bucket.BUCKET_MEMORY_SIZE > 0);
		}

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final Bucket one = new Bucket(BigDecimal.TEN, 5, true, new BigDecimal("42.5"));
			final Bucket two = new Bucket(BigDecimal.TEN, 5, true, new BigDecimal("42.5"));
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different data")
		void shouldNotBeEqualWhenDifferentData() {
			final Bucket one = new Bucket(BigDecimal.TEN, 5, true, new BigDecimal("42.5"));
			final Bucket two = new Bucket(BigDecimal.ONE, 3, false, new BigDecimal("10.0"));
			assertNotEquals(one, two);
		}
	}

	@Nested
	@DisplayName("formatHistogram")
	class FormatHistogram {

		@Test
		@DisplayName("should return EMPTY HISTOGRAM for zero buckets")
		void shouldReturnEmptyHistogramForZeroBuckets() {
			final String result = HistogramContract.formatHistogram(
				0,
				index -> BigDecimal.ZERO,
				index -> 0,
				index -> BigDecimal.ZERO,
				index -> false,
				BigDecimal.ZERO,
				0
			);
			assertEquals("EMPTY HISTOGRAM", result);
		}

		@Test
		@DisplayName("should format single bucket histogram")
		void shouldFormatSingleBucketHistogram() {
			final String result = HistogramContract.formatHistogram(
				1,
				index -> BigDecimal.ONE,
				index -> 10,
				index -> new BigDecimal("100"),
				index -> false,
				BigDecimal.TEN,
				10
			);
			assertNotNull(result);
			assertTrue(result.contains("Histogram["));
			assertTrue(result.contains("min=1"));
			assertTrue(result.contains("max=10"));
			assertTrue(result.contains("overall=10"));
		}

		@Test
		@DisplayName("should format multi-bucket histogram")
		void shouldFormatMultiBucketHistogram() {
			final BigDecimal[] thresholds = {BigDecimal.ONE, new BigDecimal("5"), BigDecimal.TEN};
			final int[] occurrences = {5, 3, 2};
			final BigDecimal[] frequencies = {new BigDecimal("50"), new BigDecimal("30"), new BigDecimal("20")};

			final String result = HistogramContract.formatHistogram(
				3,
				index -> thresholds[index],
				index -> occurrences[index],
				index -> frequencies[index],
				index -> index == 2,
				new BigDecimal("15"),
				10
			);
			assertNotNull(result);
			assertTrue(result.contains("Histogram["));
			// requested bucket should have ^ marker
			assertTrue(result.contains("^"));
		}

		@Test
		@DisplayName("should show percentage for each bucket")
		void shouldShowPercentageForEachBucket() {
			final String result = HistogramContract.formatHistogram(
				2,
				index -> index == 0 ? BigDecimal.ONE : BigDecimal.TEN,
				index -> index == 0 ? 7 : 3,
				index -> index == 0 ? new BigDecimal("70") : new BigDecimal("30"),
				index -> false,
				new BigDecimal("20"),
				10
			);
			assertTrue(result.contains("70.0%"));
			assertTrue(result.contains("30.0%"));
		}
	}
}
