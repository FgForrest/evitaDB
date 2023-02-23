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

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyStatisticsOfReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyStatisticsOfSelf} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyStatisticsOfReferenceTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyStatisticsOfReference hierarchyStatisticsOfReference = hierarchyStatisticsOfReference("category");
		assertArrayEquals(new String[] {"category"}, hierarchyStatisticsOfReference.getReferenceNames());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new HierarchyStatisticsOfReference("category").isApplicable());
		assertTrue(hierarchyStatisticsOfReference("category", entityFetch(attributeContent())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyStatisticsOfReference hierarchyStatisticsOfReference = hierarchyStatisticsOfReference("brand");
		assertEquals("hierarchyStatisticsOfReference('brand')", hierarchyStatisticsOfReference.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyStatisticsOfReference("brand"), hierarchyStatisticsOfReference("brand"));
		assertEquals(hierarchyStatisticsOfReference("brand"), hierarchyStatisticsOfReference("brand"));
		assertNotEquals(hierarchyStatisticsOfReference("brand"), hierarchyStatisticsOfReference("category"));
		assertEquals(hierarchyStatisticsOfReference("brand").hashCode(), hierarchyStatisticsOfReference("brand").hashCode());
		assertNotEquals(hierarchyStatisticsOfReference("brand").hashCode(), hierarchyStatisticsOfReference("category").hashCode());
	}

}
