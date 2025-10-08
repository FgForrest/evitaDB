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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyExact;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link EntityPrimaryKeyExact} ordering constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class EntityPrimaryKeyExactTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityPrimaryKeyExact entityPrimaryKeyExact = entityPrimaryKeyExact(18, 45, 13);
		assertArrayEquals(new int[] { 18, 45, 13 }, entityPrimaryKeyExact.getPrimaryKeys());
		assertNull(entityPrimaryKeyExact());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyExact(18, 45, 13).isApplicable());
		assertFalse(new EntityPrimaryKeyExact().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityPrimaryKeyExact entityPrimaryKeyExact = entityPrimaryKeyExact(18, 45, 13);
		assertEquals("entityPrimaryKeyExact(18,45,13)", entityPrimaryKeyExact.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45, 13));
		assertEquals(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45, 13));
		assertEquals(entityPrimaryKeyExact(18, 45, 13).hashCode(), entityPrimaryKeyExact(18, 45, 13).hashCode());
		assertNotEquals(entityPrimaryKeyExact(18, 45, 13).hashCode(), entityPrimaryKeyExact(18, 45).hashCode());
	}

}
