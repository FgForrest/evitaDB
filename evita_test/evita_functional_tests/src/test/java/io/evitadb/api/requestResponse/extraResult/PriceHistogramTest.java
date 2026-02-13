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
 * This test verifies {@link PriceHistogram} contract including delegation via @Delegate.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PriceHistogram")
class PriceHistogramTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Delegation")
	class Delegation {

		@Test
		@DisplayName("should delegate getMin to underlying histogram")
		void shouldDelegateGetMin() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertEquals(BigDecimal.ONE, priceHistogram.getMin());
		}

		@Test
		@DisplayName("should delegate getMax to underlying histogram")
		void shouldDelegateGetMax() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertEquals(new BigDecimal("20"), priceHistogram.getMax());
		}

		@Test
		@DisplayName("should delegate getOverallCount to underlying histogram")
		void shouldDelegateGetOverallCount() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertEquals(10, priceHistogram.getOverallCount());
		}

		@Test
		@DisplayName("should delegate getBuckets to underlying histogram")
		void shouldDelegateGetBuckets() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertEquals(3, priceHistogram.getBuckets().length);
		}

		@Test
		@DisplayName("should delegate estimateSize to underlying histogram")
		void shouldDelegateEstimateSize() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertTrue(priceHistogram.estimateSize() > 0);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should delegate toString to underlying histogram")
		void shouldDelegateToString() {
			final Histogram underlying = createSimpleHistogram();
			final PriceHistogram priceHistogram = new PriceHistogram(underlying);
			assertEquals(underlying.toString(), priceHistogram.toString());
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when wrapping equal histograms")
		void shouldBeEqualWhenWrappingEqualHistograms() {
			final PriceHistogram one = createPriceHistogram();
			final PriceHistogram two = createPriceHistogram();
			assertNotSame(one, two);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when wrapping different histograms")
		void shouldNotBeEqualWhenWrappingDifferentHistograms() {
			final PriceHistogram one = createPriceHistogram();
			final PriceHistogram two = new PriceHistogram(
				new Histogram(
					new Bucket[]{new Bucket(new BigDecimal("100"), 99, false, new BigDecimal("100"))},
					new BigDecimal("200")
				)
			);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("should be equal to itself")
		void shouldBeEqualToItself() {
			final PriceHistogram priceHistogram = createPriceHistogram();
			assertEquals(priceHistogram, priceHistogram);
		}
	}

	@Nested
	@DisplayName("EMPTY histogram wrapping")
	class EmptyWrapping {

		@Test
		@DisplayName("should wrap empty histogram correctly")
		void shouldWrapEmptyHistogramCorrectly() {
			final PriceHistogram priceHistogram = new PriceHistogram(HistogramContract.EMPTY);
			assertEquals(BigDecimal.ZERO, priceHistogram.getMin());
			assertEquals(BigDecimal.ZERO, priceHistogram.getMax());
			assertEquals(0, priceHistogram.getOverallCount());
			assertEquals(0, priceHistogram.getBuckets().length);
		}
	}

	private static Histogram createSimpleHistogram() {
		final Bucket[] buckets = new Bucket[]{
			new Bucket(BigDecimal.ONE, 5, false, new BigDecimal("50")),
			new Bucket(new BigDecimal("5"), 3, false, new BigDecimal("30")),
			new Bucket(BigDecimal.TEN, 2, false, new BigDecimal("20"))
		};
		return new Histogram(buckets, new BigDecimal("20"));
	}

	private static PriceHistogram createPriceHistogram() {
		return new PriceHistogram(createSimpleHistogram());
	}
}
