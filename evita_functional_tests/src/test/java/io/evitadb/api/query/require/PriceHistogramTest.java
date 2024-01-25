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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link PriceHistogram} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceHistogramTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final PriceHistogram priceHistogram = priceHistogram(20);
		assertEquals(20, priceHistogram.getRequestedBucketCount());
		assertEquals(HistogramBehavior.STANDARD, priceHistogram.getBehavior());
		assertNotNull(priceHistogram);

		final PriceHistogram priceHistogram2 = priceHistogram(20, HistogramBehavior.OPTIMIZED);
		assertEquals(20, priceHistogram2.getRequestedBucketCount());
		assertEquals(HistogramBehavior.OPTIMIZED, priceHistogram2.getBehavior());
		assertNotNull(priceHistogram2);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(priceHistogram(20).isApplicable());
		assertTrue(priceHistogram(20, HistogramBehavior.OPTIMIZED).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final PriceHistogram priceHistogram = priceHistogram(20);
		assertEquals("priceHistogram(20,STANDARD)", priceHistogram.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceHistogram(20), priceHistogram(20));
		assertEquals(priceHistogram(20), priceHistogram(20));
		assertEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED), priceHistogram(20, HistogramBehavior.OPTIMIZED));
		assertNotEquals(priceHistogram(20), priceHistogram(25));
		assertNotEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED), priceHistogram(20, HistogramBehavior.STANDARD));
		assertEquals(priceHistogram(20).hashCode(), priceHistogram(20).hashCode());
		assertNotEquals(priceHistogram(20).hashCode(), priceHistogram(22).hashCode());
		assertNotEquals(priceHistogram(20, HistogramBehavior.OPTIMIZED).hashCode(), priceHistogram(20, HistogramBehavior.STANDARD).hashCode());
	}
	
}