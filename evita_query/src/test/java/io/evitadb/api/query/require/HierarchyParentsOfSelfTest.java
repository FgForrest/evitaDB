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

import io.evitadb.api.query.QueryConstraints;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyParentsOfSelf;
import static io.evitadb.api.query.QueryConstraints.priceContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyParentsOfSelf} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyParentsOfSelfTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyParentsOfSelf hierarchyParentsOfSelf1 = QueryConstraints.hierarchyParentsOfSelf();
		assertNull(hierarchyParentsOfSelf1.getEntityRequirement());

		final HierarchyParentsOfSelf hierarchyParentsOfSelf2 = hierarchyParentsOfSelf(entityFetch());
		assertEquals(entityFetch(), hierarchyParentsOfSelf2.getEntityRequirement());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(QueryConstraints.hierarchyParentsOfSelf().isApplicable());
		assertTrue(hierarchyParentsOfSelf(entityFetch()).isApplicable());
		assertTrue(hierarchyParentsOfSelf(entityFetch(priceContent())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyParentsOfSelf hierarchyParentsOfSelf = hierarchyParentsOfSelf(entityFetch(priceContent()));
		assertEquals("hierarchyParentsOfSelf(entityFetch(priceContent(RESPECTING_FILTER)))", hierarchyParentsOfSelf.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(QueryConstraints.hierarchyParentsOfSelf(), QueryConstraints.hierarchyParentsOfSelf());
		assertEquals(QueryConstraints.hierarchyParentsOfSelf(), QueryConstraints.hierarchyParentsOfSelf());
		assertNotEquals(hierarchyParentsOfSelf(entityFetch()), hierarchyParentsOfSelf(entityFetch(priceContent())));
		assertEquals(hierarchyParentsOfSelf(entityFetch()).hashCode(), hierarchyParentsOfSelf(entityFetch()).hashCode());
		assertNotEquals(hierarchyParentsOfSelf(entityFetch()).hashCode(), hierarchyParentsOfSelf(entityFetch(priceContent())).hashCode());
		assertNotEquals(QueryConstraints.hierarchyParentsOfSelf().hashCode(), hierarchyParentsOfSelf(entityFetch()).hashCode());
	}

}
