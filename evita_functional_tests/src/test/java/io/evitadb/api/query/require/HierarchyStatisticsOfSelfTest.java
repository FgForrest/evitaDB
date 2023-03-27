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

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyStatisticsOfSelf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link HierarchyStatisticsOfSelf} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyStatisticsOfSelfTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyStatisticsOfSelf hierarchyStatisticsOfSelf = hierarchyStatisticsOfSelf(entityFetch(attributeContent()));
		assertEquals(entityFetch(attributeContent()), hierarchyStatisticsOfSelf.getEntityRequirement());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new HierarchyStatisticsOfSelf(null).isApplicable());
		assertTrue(hierarchyStatisticsOfSelf(entityFetch(attributeContent())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyStatisticsOfSelf hierarchyStatisticsOfSelf = QueryConstraints.hierarchyStatisticsOfSelf();
		assertEquals("hierarchyStatisticsOfSelf()", hierarchyStatisticsOfSelf.toString());

		final HierarchyStatisticsOfSelf hierarchyStatisticsOfSelf2 = hierarchyStatisticsOfSelf(entityFetch(attributeContent()));
		assertEquals("hierarchyStatisticsOfSelf(entityFetch(attributeContent()))", hierarchyStatisticsOfSelf2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(QueryConstraints.hierarchyStatisticsOfSelf(), QueryConstraints.hierarchyStatisticsOfSelf());
		assertEquals(QueryConstraints.hierarchyStatisticsOfSelf(), QueryConstraints.hierarchyStatisticsOfSelf());
		assertNotEquals(hierarchyStatisticsOfSelf(entityFetch(attributeContent())), QueryConstraints.hierarchyStatisticsOfSelf());
		assertEquals(QueryConstraints.hierarchyStatisticsOfSelf().hashCode(), QueryConstraints.hierarchyStatisticsOfSelf().hashCode());
		assertNotEquals(hierarchyStatisticsOfSelf(entityFetch(attributeContent())).hashCode(), QueryConstraints.hierarchyStatisticsOfSelf().hashCode());
	}

}
