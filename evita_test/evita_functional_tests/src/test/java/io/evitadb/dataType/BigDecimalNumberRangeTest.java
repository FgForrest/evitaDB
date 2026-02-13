/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test class for the {@link BigDecimalNumberRange} set operations (union, intersection, inverse).
 *
 * @author Jan Novotn\u00fd (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("BigDecimalNumberRange set operations")
class BigDecimalNumberRangeTest {

	@Nested
	@DisplayName("Union")
	class UnionTest {

		@Test
		@DisplayName("Should compute union of overlapping finite ranges")
		void shouldComputeUnionOfOverlappingFiniteRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("10.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("10.0"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should return infinite for union with infinite range")
		void shouldReturnInfiniteForUnionWithInfiniteRange() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.INFINITE;
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}

		@Test
		@DisplayName("Should compute union of non-overlapping finite ranges as convex hull")
		void shouldComputeUnionOfNonOverlappingFiniteRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("4.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertEquals(BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("4.0")), result);
		}

		@Test
		@DisplayName("Should compute union of identical ranges")
		void shouldComputeUnionOfIdenticalFiniteRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should return infinite for union of ranges with complementary null bounds")
		void shouldReturnInfiniteForUnionOfRangesWithComplementaryNullBounds() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.from(new BigDecimal("1.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.to(new BigDecimal("5.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}

		@Test
		@DisplayName("Should retain maximum decimal places in union")
		void shouldRetainMaximumDecimalPlacesInUnion() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.00"), new BigDecimal("5.00"), 2);
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.000"), new BigDecimal("10.000"), 3);
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
			assertEquals(new BigDecimal("1.00"), result.getPreciseFrom());
			assertEquals(new BigDecimal("10.000"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should return infinite for union of two infinite ranges")
		void shouldReturnInfiniteForUnionOfTwoInfiniteRanges() {
			final BigDecimalNumberRange result = BigDecimalNumberRange.union(
				BigDecimalNumberRange.INFINITE,
				BigDecimalNumberRange.INFINITE
			);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}
	}

	@Nested
	@DisplayName("Intersection")
	class IntersectionTest {

		@Test
		@DisplayName("Should compute intersection of overlapping ranges")
		void shouldComputeIntersectionOfOverlappingFiniteRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("10.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("3.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should intersect finite with infinite range")
		void shouldIntersectFiniteWithInfiniteRange() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.INFINITE;
			final BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should return infinite for non-overlapping ranges")
		void shouldReturnInfiniteForNonOverlappingRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("4.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}

		@Test
		@DisplayName("Should compute intersection of identical ranges")
		void shouldComputeIntersectionOfIdenticalRanges() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should compute intersection of ranges with complementary null bounds")
		void shouldComputeIntersectionOfRangesWithComplementaryNullBounds() {
			final BigDecimalNumberRange rangeA = BigDecimalNumberRange.from(new BigDecimal("1.0"));
			final BigDecimalNumberRange rangeB = BigDecimalNumberRange.to(new BigDecimal("5.0"));
			final BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
			assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
		}
	}

	@Nested
	@DisplayName("Inverse")
	class InverseTest {

		@Test
		@DisplayName("Should return infinite for range with both bounds")
		void shouldReturnInfiniteForRangeWithBothBounds() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
			final BigDecimalNumberRange result = range.inverse(2);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}

		@Test
		@DisplayName("Should compute inverse of from-only range")
		void shouldComputeInverseOfFromOnlyRange() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.from(new BigDecimal("1.0"));
			final BigDecimalNumberRange result = range.inverse(2);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("0.99"), result.getPreciseTo());
		}

		@Test
		@DisplayName("Should compute inverse of to-only range")
		void shouldComputeInverseOfToOnlyRange() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.to(new BigDecimal("5.0"));
			final BigDecimalNumberRange result = range.inverse(2);
			assertNotEquals(BigDecimalNumberRange.INFINITE, result);
			assertEquals(new BigDecimal("5.01"), result.getPreciseFrom());
		}

		@Test
		@DisplayName("Should return infinite for infinite range")
		void shouldReturnInfiniteForInfiniteRange() {
			final BigDecimalNumberRange range = BigDecimalNumberRange.INFINITE;
			final BigDecimalNumberRange result = range.inverse(2);
			assertEquals(BigDecimalNumberRange.INFINITE, result);
		}
	}

}
