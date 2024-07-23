/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.api.query.QueryConstraints.priceValidIn;
import static io.evitadb.api.query.QueryConstraints.priceValidInNow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link PriceValidIn} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceValidInTest {

	@Test
	void shouldCreateMomentViaFactoryClassWorkAsExpected() {
		final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		final PriceValidIn priceValidIn = priceValidIn(now);
		assertEquals(now, priceValidIn.getTheMoment(() -> null));

		final PriceValidIn priceValidInNow = priceValidInNow();
		assertNull(priceValidInNow.getTheMoment(() -> null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new PriceValidIn(null).isApplicable());
		assertTrue(new PriceValidIn().isApplicable());
		assertTrue(priceValidIn(OffsetDateTime.now(ZoneOffset.UTC)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final PriceValidIn inDateRange = priceValidIn(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
		assertEquals("priceValidIn(2021-01-01T00:00:00Z)", inDateRange.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		assertNotSame(priceValidIn(now), priceValidIn(now));
		assertEquals(priceValidIn(now), priceValidIn(now));
		assertNotEquals(priceValidIn(now), priceValidIn(now.plusHours(1)));
		assertEquals(priceValidIn(now).hashCode(), priceValidIn(now).hashCode());
		assertNotEquals(priceValidIn(now).hashCode(), priceValidIn(now.plusHours(1)).hashCode());
	}

}
