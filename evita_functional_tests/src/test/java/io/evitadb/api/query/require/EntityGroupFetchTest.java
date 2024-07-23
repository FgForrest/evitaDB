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

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.entityGroupFetch;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityFetch} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityGroupFetchTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityGroupFetch entityGroupFetch = entityGroupFetch();
		assertNotNull(entityGroupFetch);

		final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContentAll(), associatedDataContentAll());
		assertArrayEquals(new EntityContentRequire[] {attributeContentAll(), associatedDataContentAll()}, entityGroupFetch2.getRequirements());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityGroupFetch().isApplicable());
		assertTrue(entityGroupFetch(attributeContentAll()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityGroupFetch entityGroupFetch = entityGroupFetch();
		assertEquals("entityGroupFetch()", entityGroupFetch.toString());

		final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContentAll());
		assertEquals("entityGroupFetch(attributeContentAll())", entityGroupFetch2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityGroupFetch(), entityGroupFetch());
		assertEquals(entityGroupFetch(), entityGroupFetch());
		assertEquals(entityGroupFetch().hashCode(), entityGroupFetch().hashCode());
		assertEquals(entityGroupFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()));
		assertEquals(entityGroupFetch(attributeContentAll()).hashCode(), entityGroupFetch(attributeContent()).hashCode());
		assertNotEquals(entityGroupFetch(), entityGroupFetch(attributeContent()));
		assertNotEquals(entityGroupFetch().hashCode(), entityGroupFetch(attributeContent()).hashCode());
	}

	@Test
	void shouldCorrectlyCombineWithAnotherRequirement() {
		assertEquals(
			entityGroupFetch(),
			entityGroupFetch().combineWith(entityGroupFetch())
		);

		assertEquals(
			entityGroupFetch(attributeContent("code", "name"), associatedDataContentAll()),
			entityGroupFetch(attributeContent("code"), associatedDataContentAll()).combineWith(entityGroupFetch(attributeContent("name")))
		);

		assertEquals(
			entityGroupFetch(attributeContent("code", "name"), associatedDataContentAll()),
			entityGroupFetch(attributeContent("code"), associatedDataContentAll()).combineWith(entityGroupFetch(attributeContent("name")))
		);
	}
}
