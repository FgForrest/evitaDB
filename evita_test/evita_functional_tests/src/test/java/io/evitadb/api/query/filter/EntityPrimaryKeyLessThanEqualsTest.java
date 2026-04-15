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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyLessThanEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityPrimaryKeyLessThanEquals} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityPrimaryKeyLessThanEquals query")
class EntityPrimaryKeyLessThanEqualsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyLessThanEquals constraint =
			entityPrimaryKeyLessThanEquals(5);
		assertEquals(5, constraint.getPrimaryKey());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
		final Integer nullInteger = null;
		final EntityPrimaryKeyLessThanEquals constraint =
			entityPrimaryKeyLessThanEquals(nullInteger);
		assertNull(constraint);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyLessThanEquals(1).isApplicable());
		assertTrue(entityPrimaryKeyLessThanEquals(100).isApplicable());
	}

	@Test
	void shouldRecognizeNotApplicability() {
		assertFalse(
			new EntityPrimaryKeyLessThanEquals((Integer) null).isApplicable()
		);
	}

	@Test
	void shouldToStringViaFactory() {
		final EntityPrimaryKeyLessThanEquals constraint =
			entityPrimaryKeyLessThanEquals(5);
		assertEquals(
			"entityPrimaryKeyLessThanEquals(5)",
			constraint.toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(
			entityPrimaryKeyLessThanEquals(5),
			entityPrimaryKeyLessThanEquals(5)
		);
		assertEquals(
			entityPrimaryKeyLessThanEquals(5),
			entityPrimaryKeyLessThanEquals(5)
		);
		assertNotEquals(
			entityPrimaryKeyLessThanEquals(5),
			entityPrimaryKeyLessThanEquals(6)
		);
		assertNotEquals(
			entityPrimaryKeyLessThanEquals(5),
			entityPrimaryKeyLessThanEquals(10)
		);
		assertEquals(
			entityPrimaryKeyLessThanEquals(5).hashCode(),
			entityPrimaryKeyLessThanEquals(5).hashCode()
		);
		assertNotEquals(
			entityPrimaryKeyLessThanEquals(5).hashCode(),
			entityPrimaryKeyLessThanEquals(6).hashCode()
		);
	}

}
