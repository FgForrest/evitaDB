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

package io.evitadb.api.query.order;

import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.random;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link Random} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RandomTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final Random random = random();
		assertNotNull(random);

		final Random random1 = QueryConstraints.randomWithSeed(42);
		assertNotNull(random1);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(random().isApplicable());
		assertTrue(QueryConstraints.randomWithSeed(42).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final Random random = random();
		assertEquals("random()", random.toString());

		final Random random1 = QueryConstraints.randomWithSeed(42);
		assertEquals("randomWithSeed(42)", random1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertSame(random(), random());
		assertNotSame(QueryConstraints.randomWithSeed(42), QueryConstraints.randomWithSeed(42));
		assertNotSame(QueryConstraints.randomWithSeed(42), QueryConstraints.randomWithSeed(32));
		assertEquals(random(), random());
		assertEquals(QueryConstraints.randomWithSeed(42), QueryConstraints.randomWithSeed(42));
		assertNotEquals(QueryConstraints.randomWithSeed(42), QueryConstraints.randomWithSeed(32));
		assertEquals(random().hashCode(), random().hashCode());
		assertEquals(QueryConstraints.randomWithSeed(42).hashCode(), QueryConstraints.randomWithSeed(42).hashCode());
		assertNotEquals(QueryConstraints.randomWithSeed(42).hashCode(), QueryConstraints.randomWithSeed(32).hashCode());
	}

}
