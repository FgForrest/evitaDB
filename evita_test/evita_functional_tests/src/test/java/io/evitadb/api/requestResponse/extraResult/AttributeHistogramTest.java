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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AttributeHistogram} contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributeHistogram")
class AttributeHistogramTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Getters")
	class Getters {

		@Test
		@DisplayName("should return histogram by attribute name")
		void shouldReturnHistogramByAttributeName() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			final HistogramContract histogram = attributeHistogram.getHistogram("price");
			assertNotNull(histogram);
			assertEquals(3, histogram.getBuckets().length);
		}

		@Test
		@DisplayName("should return null for unknown attribute name")
		void shouldReturnNullForUnknownAttributeName() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			assertNull(attributeHistogram.getHistogram("nonExistent"));
		}

		@Test
		@DisplayName("should return all histograms as unmodifiable map")
		void shouldReturnAllHistogramsAsUnmodifiableMap() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			final Map<String, HistogramContract> histograms = attributeHistogram.getHistograms();
			assertEquals(2, histograms.size());
			assertThrows(UnsupportedOperationException.class, () -> histograms.put("test", null));
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final AttributeHistogram one = createAttributeHistogram();
			final AttributeHistogram two = createAttributeHistogram();
			assertNotSame(one, two);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different attributes")
		void shouldNotBeEqualWhenDifferentAttributes() {
			final AttributeHistogram one = createAttributeHistogram();
			final AttributeHistogram two = new AttributeHistogram(
				Map.of("other", createSimpleHistogram())
			);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("should be equal to itself")
		void shouldBeEqualToItself() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			assertEquals(attributeHistogram, attributeHistogram);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should produce non-empty string")
		void shouldProduceNonEmptyString() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			final String result = attributeHistogram.toString();
			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName("should contain attribute names")
		void shouldContainAttributeNames() {
			final AttributeHistogram attributeHistogram = createAttributeHistogram();
			final String result = attributeHistogram.toString();
			assertTrue(result.contains("price"));
			assertTrue(result.contains("quantity"));
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

	private static AttributeHistogram createAttributeHistogram() {
		return new AttributeHistogram(
			Map.of(
				"price", createSimpleHistogram(),
				"quantity", createSimpleHistogram()
			)
		);
	}
}
