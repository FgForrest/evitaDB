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

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyBetween;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityPrimaryKeyBetween} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityPrimaryKeyBetween query")
class EntityPrimaryKeyBetweenTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyBetween constraint = entityPrimaryKeyBetween(5, 10);
		assertEquals(Integer.valueOf(5), constraint.getFrom());
		assertEquals(Integer.valueOf(10), constraint.getTo());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullBounds() {
		assertNull(entityPrimaryKeyBetween(null, null));

		final EntityPrimaryKeyBetween withNullFrom =
			entityPrimaryKeyBetween(null, 10);
		assertNotNull(withNullFrom);
		assertNull(withNullFrom.getFrom());
		assertEquals(Integer.valueOf(10), withNullFrom.getTo());

		final EntityPrimaryKeyBetween withNullTo =
			entityPrimaryKeyBetween(5, null);
		assertNotNull(withNullTo);
		assertEquals(Integer.valueOf(5), withNullTo.getFrom());
		assertNull(withNullTo.getTo());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyBetween(5, 10).isApplicable());
		assertTrue(entityPrimaryKeyBetween(null, 10).isApplicable());
		assertTrue(entityPrimaryKeyBetween(5, null).isApplicable());
	}

	@Test
	void shouldRejectBothBoundsNull() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new EntityPrimaryKeyBetween(null, null)
		);
	}

	@Test
	void shouldToStringViaFactory() {
		assertEquals(
			"entityPrimaryKeyBetween(5,10)",
			entityPrimaryKeyBetween(5, 10).toString()
		);
		assertEquals(
			"entityPrimaryKeyBetween(<NULL>,10)",
			entityPrimaryKeyBetween(null, 10).toString()
		);
		assertEquals(
			"entityPrimaryKeyBetween(5,<NULL>)",
			entityPrimaryKeyBetween(5, null).toString()
		);
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(
			entityPrimaryKeyBetween(5, 10),
			entityPrimaryKeyBetween(5, 10)
		);
		assertEquals(
			entityPrimaryKeyBetween(5, 10),
			entityPrimaryKeyBetween(5, 10)
		);
		assertNotEquals(
			entityPrimaryKeyBetween(5, 10),
			entityPrimaryKeyBetween(5, 11)
		);
		assertNotEquals(
			entityPrimaryKeyBetween(5, 10),
			entityPrimaryKeyBetween(6, 10)
		);
		assertNotEquals(
			entityPrimaryKeyBetween(5, 10),
			entityPrimaryKeyBetween(null, null)
		);
		assertEquals(
			entityPrimaryKeyBetween(null, 10),
			entityPrimaryKeyBetween(null, 10)
		);
		assertEquals(
			entityPrimaryKeyBetween(5, 10).hashCode(),
			entityPrimaryKeyBetween(5, 10).hashCode()
		);
		assertNotEquals(
			entityPrimaryKeyBetween(5, 10).hashCode(),
			entityPrimaryKeyBetween(5, 11).hashCode()
		);
		assertNotEquals(
			entityPrimaryKeyBetween(5, 10).hashCode(),
			entityPrimaryKeyBetween(6, 10).hashCode()
		);
		assertEquals(
			entityPrimaryKeyBetween(null, 10).hashCode(),
			entityPrimaryKeyBetween(null, 10).hashCode()
		);
	}

}
