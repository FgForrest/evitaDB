/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test class for the {@link BigDecimalNumberRange}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class BigDecimalNumberRangeTest {

	@Test
	public void unionOfOverlappingFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("10.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("10.0"), result.getPreciseTo());
	}

	@Test
	public void unionOfFiniteAndInfiniteRange() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.INFINITE;
		BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
		assertEquals(BigDecimalNumberRange.INFINITE, result);
	}

	@Test
	public void unionOfNonOverlappingFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("4.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
		assertEquals(BigDecimalNumberRange.INFINITE, result);
	}

	@Test
	public void unionOfIdenticalFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void unionOfFiniteRangeWithNullBounds() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), null);
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(null, new BigDecimal("5.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.union(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void intersectionOfOverlappingFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("10.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("3.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void intersectionOfFiniteAndInfiniteRange() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.INFINITE;
		BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void intersectionOfNonOverlappingFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("3.0"), new BigDecimal("4.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
		assertEquals(BigDecimalNumberRange.INFINITE, result);
	}

	@Test
	public void intersectionOfIdenticalFiniteRanges() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void intersectionOfFiniteRangeWithNullBounds() {
		BigDecimalNumberRange rangeA = BigDecimalNumberRange.between(new BigDecimal("1.0"), null);
		BigDecimalNumberRange rangeB = BigDecimalNumberRange.between(null, new BigDecimal("5.0"));
		BigDecimalNumberRange result = BigDecimalNumberRange.intersect(rangeA, rangeB);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("1.0"), result.getPreciseFrom());
		assertEquals(new BigDecimal("5.0"), result.getPreciseTo());
	}

	@Test
	public void inverseOfFiniteRangeWithBothBounds() {
		BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("5.0"));
		BigDecimalNumberRange result = range.inverse(2);
		assertEquals(BigDecimalNumberRange.INFINITE, result);
	}

	@Test
	public void inverseOfFiniteRangeWithLowerBoundOnly() {
		BigDecimalNumberRange range = BigDecimalNumberRange.from(new BigDecimal("1.0"));
		BigDecimalNumberRange result = range.inverse(2);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("0.99"), result.getPreciseTo());
	}

	@Test
	public void inverseOfFiniteRangeWithUpperBoundOnly() {
		BigDecimalNumberRange range = BigDecimalNumberRange.to(new BigDecimal("5.0"));
		BigDecimalNumberRange result = range.inverse(2);
		assertNotEquals(BigDecimalNumberRange.INFINITE, result);
		assertEquals(new BigDecimal("5.01"), result.getPreciseFrom());
	}

	@Test
	public void inverseOfInfiniteRange() {
		BigDecimalNumberRange range = BigDecimalNumberRange.INFINITE;
		BigDecimalNumberRange result = range.inverse(2);
		assertEquals(BigDecimalNumberRange.INFINITE, result);
	}

}