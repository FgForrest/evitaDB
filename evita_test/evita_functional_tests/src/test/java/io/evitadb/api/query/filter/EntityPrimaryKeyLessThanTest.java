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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyLessThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityPrimaryKeyLessThan} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityPrimaryKeyLessThan query")
class EntityPrimaryKeyLessThanTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyLessThan constraint = entityPrimaryKeyLessThan(5);
		assertEquals(5, constraint.getPrimaryKey());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
		final Integer nullInteger = null;
		final EntityPrimaryKeyLessThan constraint = entityPrimaryKeyLessThan(nullInteger);
		assertNull(constraint);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyLessThan(1).isApplicable());
		assertTrue(entityPrimaryKeyLessThan(100).isApplicable());
	}

	@Test
	void shouldRecognizeNotApplicability() {
		assertFalse(new EntityPrimaryKeyLessThan((Integer) null).isApplicable());
	}

	@Test
	void shouldToStringViaFactory() {
		final EntityPrimaryKeyLessThan constraint = entityPrimaryKeyLessThan(5);
		assertEquals("entityPrimaryKeyLessThan(5)", constraint.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyLessThan(5), entityPrimaryKeyLessThan(5));
		assertEquals(entityPrimaryKeyLessThan(5), entityPrimaryKeyLessThan(5));
		assertNotEquals(entityPrimaryKeyLessThan(5), entityPrimaryKeyLessThan(6));
		assertNotEquals(entityPrimaryKeyLessThan(5), entityPrimaryKeyLessThan(10));
		assertEquals(
			entityPrimaryKeyLessThan(5).hashCode(),
			entityPrimaryKeyLessThan(5).hashCode()
		);
		assertNotEquals(
			entityPrimaryKeyLessThan(5).hashCode(),
			entityPrimaryKeyLessThan(6).hashCode()
		);
	}

}
