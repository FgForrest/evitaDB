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

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link EntityPrimaryKeyInFilter} ordering constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class EntityPrimaryKeyNaturalTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertNotNull(entityPrimaryKeyNatural(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyNatural(null).isApplicable());
		assertTrue(entityPrimaryKeyNatural(OrderDirection.DESC).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityPrimaryKeyNatural entityPrimaryKeyNaturalAsc = entityPrimaryKeyNatural(null);
		assertEquals("entityPrimaryKeyNatural(ASC)", entityPrimaryKeyNaturalAsc.toString());
		final EntityPrimaryKeyNatural entityPrimaryKeyNaturalDesc = entityPrimaryKeyNatural(OrderDirection.DESC);
		assertEquals("entityPrimaryKeyNatural(DESC)", entityPrimaryKeyNaturalDesc.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyNatural(null), entityPrimaryKeyNatural(null));
		assertEquals(entityPrimaryKeyNatural(null), entityPrimaryKeyNatural(null));
		assertEquals(entityPrimaryKeyNatural(null).hashCode(), entityPrimaryKeyNatural(null).hashCode());
		assertNotSame(entityPrimaryKeyNatural(OrderDirection.ASC), entityPrimaryKeyNatural(OrderDirection.DESC));
		assertNotEquals(entityPrimaryKeyNatural(OrderDirection.ASC), entityPrimaryKeyNatural(OrderDirection.DESC));
		assertNotEquals(entityPrimaryKeyNatural(OrderDirection.ASC).hashCode(), entityPrimaryKeyNatural(OrderDirection.DESC).hashCode());
	}

}
