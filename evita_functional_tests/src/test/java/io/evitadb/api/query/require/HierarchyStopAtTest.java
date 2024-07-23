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

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyStopAt} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyStopAtTest {

	@Test
	void shouldCreateWithLevelViaFactoryClassWorkAsExpected() {
		final HierarchyStopAt hierarchyStopAt = stopAt(level(1));
		assertEquals(level(1), hierarchyStopAt.getLevel());
		assertNull(hierarchyStopAt.getDistance());
		assertNull(hierarchyStopAt.getNode());
	}

	@Test
	void shouldCreateWithDistanceViaFactoryClassWorkAsExpected() {
		final HierarchyStopAt hierarchyStopAt = stopAt(distance(1));
		assertEquals(distance(1), hierarchyStopAt.getDistance());
		assertNull(hierarchyStopAt.getLevel());
		assertNull(hierarchyStopAt.getNode());
	}

	@Test
	void shouldCreateWithNodeViaFactoryClassWorkAsExpected() {
		final HierarchyStopAt hierarchyStopAt = stopAt(node(filterBy(entityPrimaryKeyInSet(1))));
		assertEquals(node(filterBy(entityPrimaryKeyInSet(1))), hierarchyStopAt.getNode());
		assertNull(hierarchyStopAt.getLevel());
		assertNull(hierarchyStopAt.getDistance());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyStopAt(null).isApplicable());
		assertTrue(stopAt(level(1)).isApplicable());
		assertTrue(stopAt(distance(1)).isApplicable());
		assertTrue(stopAt(node(filterBy(entityPrimaryKeyInSet(1)))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyStopAt hierarchyStopAt = stopAt(level(1));
		assertEquals("stopAt(level(1))", hierarchyStopAt.toString());

		final HierarchyStopAt hierarchyStopAt2 = stopAt(distance(5));
		assertEquals("stopAt(distance(5))", hierarchyStopAt2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(stopAt(level(1)), stopAt(level(1)));
		assertEquals(stopAt(level(1)), stopAt(level(1)));
		assertNotEquals(stopAt(level(1)), stopAt(level(2)));
		assertNotEquals(stopAt(level(1)), stopAt(distance(1)));
		assertEquals(stopAt(level(1)).hashCode(), stopAt(level(1)).hashCode());
		assertNotEquals(stopAt(level(1)).hashCode(), stopAt(level(2)).hashCode());
		assertNotEquals(stopAt(level(1)).hashCode(), stopAt(distance(1)).hashCode());
	}

}
