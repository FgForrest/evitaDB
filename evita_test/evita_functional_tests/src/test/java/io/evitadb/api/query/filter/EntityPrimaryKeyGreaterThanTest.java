/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyGreaterThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityPrimaryKeyGreaterThan} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityPrimaryKeyGreaterThan query")
class EntityPrimaryKeyGreaterThanTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyGreaterThan constraint = entityPrimaryKeyGreaterThan(5);
		assertEquals(5, constraint.getPrimaryKey());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
		final Integer nullInteger = null;
		final EntityPrimaryKeyGreaterThan constraint = entityPrimaryKeyGreaterThan(nullInteger);
		assertNull(constraint);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyGreaterThan(1).isApplicable());
		assertTrue(entityPrimaryKeyGreaterThan(100).isApplicable());
	}

	@Test
	void shouldRecognizeNotApplicability() {
		assertFalse(new EntityPrimaryKeyGreaterThan((Integer) null).isApplicable());
	}

	@Test
	void shouldToStringViaFactory() {
		final EntityPrimaryKeyGreaterThan constraint = entityPrimaryKeyGreaterThan(5);
		assertEquals("entityPrimaryKeyGreaterThan(5)", constraint.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyGreaterThan(5), entityPrimaryKeyGreaterThan(5));
		assertEquals(entityPrimaryKeyGreaterThan(5), entityPrimaryKeyGreaterThan(5));
		assertNotEquals(entityPrimaryKeyGreaterThan(5), entityPrimaryKeyGreaterThan(6));
		assertNotEquals(entityPrimaryKeyGreaterThan(5), entityPrimaryKeyGreaterThan(10));
		assertEquals(
			entityPrimaryKeyGreaterThan(5).hashCode(),
			entityPrimaryKeyGreaterThan(5).hashCode()
		);
		assertNotEquals(
			entityPrimaryKeyGreaterThan(5).hashCode(),
			entityPrimaryKeyGreaterThan(6).hashCode()
		);
	}

}
