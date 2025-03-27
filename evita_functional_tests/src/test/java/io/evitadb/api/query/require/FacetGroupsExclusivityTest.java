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
import static io.evitadb.api.query.QueryConstraints.facetGroupsExclusivity;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetGroupsExclusivity} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetGroupsExclusivityTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetGroupsExclusivity facetGroupsExclusivity1 = facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsExclusivity1.getReferenceName());
		assertEquals(WITH_DIFFERENT_FACETS_IN_GROUP, facetGroupsExclusivity1.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsExclusivity1.getFacetGroups().orElseThrow());

		final FacetGroupsExclusivity facetGroupsExclusivity2 = facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("brand", facetGroupsExclusivity2.getReferenceName());
		assertEquals(WITH_DIFFERENT_GROUPS, facetGroupsExclusivity2.getFacetGroupRelationLevel());
		assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), facetGroupsExclusivity2.getFacetGroups().orElseThrow());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetGroupsExclusivity(null, null).isApplicable());
		assertFalse(new FacetGroupsExclusivity(null, WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(new FacetGroupsExclusivity("brand", null).isApplicable());
		assertTrue(new FacetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, null).isApplicable());
		assertTrue(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1))).isApplicable());
		assertTrue(facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetGroupsExclusivity facetGroupsExclusivity1 = facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsExclusivity('brand',filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsExclusivity1.toString());

		final FacetGroupsExclusivity facetGroupsExclusivity2 = facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7)));
		assertEquals("facetGroupsExclusivity('brand',WITH_DIFFERENT_GROUPS,filterBy(entityPrimaryKeyInSet(1,5,7)))", facetGroupsExclusivity2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotSame(facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertEquals(facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(2, 1, 5))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("category", filterBy(entityPrimaryKeyInSet(1, 1, 6))));
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1))));
		assertEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode());
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))).hashCode());
		assertNotEquals(facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(), facetGroupsExclusivity("brand", filterBy(entityPrimaryKeyInSet(1, 1))).hashCode());
	}

}
