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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityPrimaryKeyInSet} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityPrimaryKeyInSetTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyInSet entityPrimaryKeyInSet = entityPrimaryKeyInSet(1, 5, 7);
		assertArrayEquals(new int[] {1, 5, 7}, entityPrimaryKeyInSet.getPrimaryKeys());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
		final EntityPrimaryKeyInSet entityPrimaryKeyInSet = entityPrimaryKeyInSet(1, null, 7);
		assertArrayEquals(new int[] {1, 7}, entityPrimaryKeyInSet.getPrimaryKeys());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
		final Integer nullInteger = null;
		final EntityPrimaryKeyInSet entityPrimaryKeyInSet = entityPrimaryKeyInSet(nullInteger);
		assertNull(entityPrimaryKeyInSet);
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
		final EntityPrimaryKeyInSet entityPrimaryKeyInSet = entityPrimaryKeyInSet(new Integer[0]);
		assertArrayEquals(new int[0], entityPrimaryKeyInSet.getPrimaryKeys());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new EntityPrimaryKeyInSet().isApplicable());
		assertTrue(entityPrimaryKeyInSet(1).isApplicable());
		assertTrue(entityPrimaryKeyInSet(1, 5, 7).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityPrimaryKeyInSet entityPrimaryKeyInSet = entityPrimaryKeyInSet(1, 5, 7);
		assertEquals("entityPrimaryKeyInSet(1,5,7)", entityPrimaryKeyInSet.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(1,1, 5));
		assertEquals(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(1,1, 5));
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(1,1, 6));
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(1,1));
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(2,1, 5));
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5), entityPrimaryKeyInSet(1,1));
		assertEquals(entityPrimaryKeyInSet(1,1, 5).hashCode(), entityPrimaryKeyInSet(1,1, 5).hashCode());
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5).hashCode(), entityPrimaryKeyInSet(1,1, 6).hashCode());
		assertNotEquals(entityPrimaryKeyInSet(1,1, 5).hashCode(), entityPrimaryKeyInSet(1,1).hashCode());
	}

}
