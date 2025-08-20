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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link EntityPrimaryKeyInFilter} ordering constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class EntityPrimaryKeyInFilterTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertNotNull(entityPrimaryKeyInFilter());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityPrimaryKeyInFilter().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityPrimaryKeyInFilter entityPrimaryKeyInFilter = entityPrimaryKeyInFilter();
		assertEquals("entityPrimaryKeyInFilter()", entityPrimaryKeyInFilter.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityPrimaryKeyInFilter(), entityPrimaryKeyInFilter());
		assertEquals(entityPrimaryKeyInFilter(), entityPrimaryKeyInFilter());
		assertEquals(entityPrimaryKeyInFilter().hashCode(), entityPrimaryKeyInFilter().hashCode());
	}

}
