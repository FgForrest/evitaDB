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

package io.evitadb.api.query.require;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.distance;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyDistance} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyDistanceTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyDistance hierarchyDistance = distance(1);
		assertEquals(hierarchyDistance.getDistance(), 1);
	}

	@Test
	void shouldFailToCreateDistanceWithNegativeNumber() {
		assertThrows(EvitaInvalidUsageException.class, () -> distance(-1));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new HierarchyDistance(1).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyDistance hierarchyDistance = distance(1);
		assertEquals("distance(1)", hierarchyDistance.toString());

		final HierarchyDistance hierarchyDistance2 = distance(12);
		assertEquals("distance(12)", hierarchyDistance2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(distance(1), distance(1));
		assertEquals(distance(1), distance(1));
		assertNotEquals(distance(2), distance(1));
		assertEquals(distance(1).hashCode(), distance(1).hashCode());
		assertNotEquals(distance(2).hashCode(), distance(1).hashCode());
	}

}
