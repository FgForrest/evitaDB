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
 * This test verifies {@link Histogram} contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Histogram")
class HistogramTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidation {

		@Test
		@DisplayName("should reject empty buckets array")
		void shouldRejectEmptyBucketsArray() {
			assertThrows(
				Exception.class,
				() -> new Histogram(new Bucket[0], BigDecimal.TEN)
			);
		}

		@Test
		@DisplayName("should reject last bucket threshold greater than max")
		void shouldRejectLastBucketThresholdGreaterThanMax() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(new BigDecimal("20"), 5, false, new BigDecimal("100"))
			};
			assertThrows(
				Exception.class,
				() -> new Histogram(buckets, new BigDecimal("10"))
			);
		}

		@Test
		@DisplayName("should reject non-monotonic thresholds")
		void shouldRejectNonMonotonicThresholds() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(new BigDecimal("10"), 5, false, new BigDecimal("50")),
				new Bucket(new BigDecimal("5"), 3, false, new BigDecimal("30"))
			};
			assertThrows(
				Exception.class,
				() -> new Histogram(buckets, new BigDecimal("20"))
			);
		}

		@Test
		@DisplayName("should reject duplicate thresholds")
		void shouldRejectDuplicateThresholds() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(new BigDecimal("10"), 5, false, new BigDecimal("50")),
				new Bucket(new BigDecimal("10"), 3, false, new BigDecimal("30"))
			};
			assertThrows(
				Exception.class,
				() -> new Histogram(buckets, new BigDecimal("20"))
			);
		}

		@Test
		@DisplayName("should accept valid single bucket")
		void shouldAcceptValidSingleBucket() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(BigDecimal.ONE, 5, false, new BigDecimal("100"))
			};
			final Histogram histogram = new Histogram(buckets, BigDecimal.TEN);
			assertNotNull(histogram);
		}

		@Test
		@DisplayName("should accept valid multiple buckets")
		void shouldAcceptValidMultipleBuckets() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(BigDecimal.ONE, 5, false, new BigDecimal("50")),
				new Bucket(new BigDecimal("5"), 3, false, new BigDecimal("30")),
				new Bucket(BigDecimal.TEN, 2, false, new BigDecimal("20"))
			};
			final Histogram histogram = new Histogram(buckets, new BigDecimal("15"));
			assertNotNull(histogram);
		}

		@Test
		@DisplayName("should accept last bucket threshold equal to max")
		void shouldAcceptLastBucketThresholdEqualToMax() {
			final Bucket[] buckets = new Bucket[]{
				new Bucket(BigDecimal.TEN, 5, false, new BigDecimal("100"))
			};
			final Histogram histogram = new Histogram(buckets, BigDecimal.TEN);
			assertNotNull(histogram);
		}
	}

	@Nested
	@DisplayName("Getters")
	class Getters {

		@Test
		@DisplayName("should return min from first bucket threshold")
		void shouldReturnMinFromFirstBucketThreshold() {
			final Histogram histogram = createHistogram();
			assertEquals(BigDecimal.ONE, histogram.getMin());
		}

		@Test
		@DisplayName("should return max value")
		void shouldReturnMaxValue() {
			final Histogram histogram = createHistogram();
			assertEquals(new BigDecimal("20"), histogram.getMax());
		}

		@Test
		@DisplayName("should return overall count as sum of all bucket occurrences")
		void shouldReturnOverallCountAsSumOfAllBucketOccurrences() {
			final Histogram histogram = createHistogram();
			// 5 + 3 + 2 = 10
			assertEquals(10, histogram.getOverallCount());
		}

		@Test
		@DisplayName("should return buckets array")
		void shouldReturnBucketsArray() {
			final Histogram histogram = createHistogram();
			assertEquals(3, histogram.getBuckets().length);
		}
	}

	@Nested
	@DisplayName("estimateSize")
	class EstimateSize {

		@Test
		@DisplayName("should return positive size estimate")
		void shouldReturnPositiveSizeEstimate() {
			final Histogram histogram = createHistogram();
			assertTrue(histogram.estimateSize() > 0);
		}

		@Test
		@DisplayName("should increase with more buckets")
		void shouldIncreaseWithMoreBuckets() {
			final Histogram small = new Histogram(
				new Bucket[]{new Bucket(BigDecimal.ONE, 5, false, new BigDecimal("100"))},
				BigDecimal.TEN
			);
			final Histogram large = createHistogram();
			assertTrue(large.estimateSize() > small.estimateSize());
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final Histogram one = createHistogram();
			final Histogram two = createHistogram();
			assertNotSame(one, two);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different buckets")
		void shouldNotBeEqualWhenDifferentBuckets() {
			final Histogram one = createHistogram();
			final Histogram two = new Histogram(
				new Bucket[]{
					new Bucket(BigDecimal.ONE, 99, false, new BigDecimal("100"))
				},
				new BigDecimal("20")
			);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("should be equal to itself")
		void shouldBeEqualToItself() {
			final Histogram histogram = createHistogram();
			assertEquals(histogram, histogram);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final Histogram histogram = createHistogram();
			assertNotEquals(null, histogram);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should produce non-empty string")
		void shouldProduceNonEmptyString() {
			final Histogram histogram = createHistogram();
			final String result = histogram.toString();
			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName("should contain histogram header")
		void shouldContainHistogramHeader() {
			final Histogram histogram = createHistogram();
			final String result = histogram.toString();
			assertTrue(result.contains("Histogram["));
		}
	}

	private static Histogram createHistogram() {
		final Bucket[] buckets = new Bucket[]{
			new Bucket(BigDecimal.ONE, 5, false, new BigDecimal("50")),
			new Bucket(new BigDecimal("5"), 3, false, new BigDecimal("30")),
			new Bucket(BigDecimal.TEN, 2, true, new BigDecimal("20"))
		};
		return new Histogram(buckets, new BigDecimal("20"));
	}
}
