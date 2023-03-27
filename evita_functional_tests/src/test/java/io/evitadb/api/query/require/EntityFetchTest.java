/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.associatedDataContent;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityFetch} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EntityFetchTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityFetch entityFetch = entityFetch();
		assertNotNull(entityFetch);

		final EntityFetch entityFetch2 = entityFetch(attributeContent(), associatedDataContent());
		assertArrayEquals(new EntityContentRequire[] {attributeContent(), associatedDataContent()}, entityFetch2.getRequirements());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityFetch().isApplicable());
		assertTrue(entityFetch(attributeContent()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityFetch entityFetch = entityFetch();
		assertEquals("entityFetch()", entityFetch.toString());

		final EntityFetch entityFetch2 = entityFetch(attributeContent());
		assertEquals("entityFetch(attributeContent())", entityFetch2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityFetch(), entityFetch());
		assertEquals(entityFetch(), entityFetch());
		assertEquals(entityFetch().hashCode(), entityFetch().hashCode());
		assertEquals(entityFetch(attributeContent()), entityFetch(attributeContent()));
		assertEquals(entityFetch(attributeContent()).hashCode(), entityFetch(attributeContent()).hashCode());
		assertNotEquals(entityFetch(), entityFetch(attributeContent()));
		assertNotEquals(entityFetch().hashCode(), entityFetch(attributeContent()).hashCode());
	}

	@Test
	void shouldCorrectlyCombineWithAnotherRequirement() {
		assertEquals(
			entityFetch(),
			entityFetch().combineWith(entityFetch())
		);

		assertEquals(
			entityFetch(attributeContent("code", "name"), associatedDataContent()),
			entityFetch(attributeContent("code"), associatedDataContent()).combineWith(entityFetch(attributeContent("name")))
		);

		assertEquals(
			entityFetch(attributeContent("code", "name"), associatedDataContent()),
			entityFetch(attributeContent("code"), associatedDataContent()).combineWith(entityFetch(attributeContent("name")))
		);
	}
}