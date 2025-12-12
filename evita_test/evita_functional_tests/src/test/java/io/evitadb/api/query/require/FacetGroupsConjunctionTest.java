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
import static io.evitadb.api.query.QueryConstraints.facetGroupsConjunction;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetGroupsConjunction} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetGroupsConjunctionTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetGroupsConjunction facetGroupsConjunction1 = facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsConjunction1.getReferenceName());
		assertEquals(WITH_DIFFERENT_FACETS_IN_GROUP, facetGroupsConjunction1.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsConjunction1.getFacetGroups().orElseThrow());

		final FacetGroupsConjunction facetGroupsConjunction2 = facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsConjunction2.getReferenceName());
		assertEquals(WITH_DIFFERENT_GROUPS, facetGroupsConjunction2.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsConjunction2.getFacetGroups().orElseThrow());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetGroupsConjunction(null, null).isApplicable());
		assertFalse(new FacetGroupsConjunction(null, WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(new FacetGroupsConjunction("brand", null).isApplicable());
		assertTrue(new FacetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7))).isApplicable());
		assertTrue(facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetGroupsConjunction facetGroupsConjunction1 = facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsConjunction('brand',filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsConjunction1.toString());

		final FacetGroupsConjunction facetGroupsConjunction2 = facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsConjunction('brand',WITH_DIFFERENT_GROUPS,filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsConjunction2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotSame(facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(2, 1, 5))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("category", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode());
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))).hashCode());
		assertNotEquals(facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsConjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1))).hashCode());
	}

}
