/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetGroupsDisjunction;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetGroupsDisjunction} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetGroupsDisjunctionTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetGroupsDisjunction facetGroupsDisjunction1 = facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsDisjunction1.getReferenceName());
		assertEquals(WITH_DIFFERENT_FACETS_IN_GROUP, facetGroupsDisjunction1.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsDisjunction1.getFacetGroups().orElseThrow());

		final FacetGroupsDisjunction facetGroupsDisjunction2 = facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsDisjunction2.getReferenceName());
		assertEquals(WITH_DIFFERENT_GROUPS, facetGroupsDisjunction2.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsDisjunction2.getFacetGroups().orElseThrow());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetGroupsDisjunction(null, null).isApplicable());
		assertFalse(new FacetGroupsDisjunction(null, WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(new FacetGroupsDisjunction("brand", null).isApplicable());
		assertTrue(new FacetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7))).isApplicable());
		assertTrue(facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetGroupsDisjunction facetGroupsDisjunction1 = facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsDisjunction('brand',filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsDisjunction1.toString());

		final FacetGroupsDisjunction facetGroupsDisjunction2 = facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsDisjunction('brand',WITH_DIFFERENT_GROUPS,filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsDisjunction2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotSame(facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(2, 1, 5))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("category", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode());
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))).hashCode());
		assertNotEquals(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))).hashCode());
	}

}
