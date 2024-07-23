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

import static io.evitadb.api.query.QueryConstraints.level;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyLevel} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyLevelTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyLevel hierarchyLevel = level(1);
		assertEquals(hierarchyLevel.getLevel(), 1);
	}

	@Test
	void shouldFailToCreateLevelWithNegativeNumber() {
		assertThrows(EvitaInvalidUsageException.class, () -> level(-1));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new HierarchyLevel(1).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyLevel hierarchyLevel = level(1);
		assertEquals("level(1)", hierarchyLevel.toString());

		final HierarchyLevel hierarchyLevel2 = level(12);
		assertEquals("level(12)", hierarchyLevel2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(level(1), level(1));
		assertEquals(level(1), level(1));
		assertNotEquals(level(2), level(1));
		assertEquals(level(1).hashCode(), level(1).hashCode());
		assertNotEquals(level(2).hashCode(), level(1).hashCode());
	}

}
