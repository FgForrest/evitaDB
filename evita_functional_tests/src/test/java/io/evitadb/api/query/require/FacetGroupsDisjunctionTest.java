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

import static io.evitadb.api.query.QueryConstraints.facetGroupsDisjunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetGroupsDisjunction} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetGroupsDisjunctionTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetGroupsDisjunction facetGroupsDisjunction = facetGroupsDisjunction("brand", 1, 5, 7);
		assertEquals("brand", facetGroupsDisjunction.getReferenceName());
		assertArrayEquals(new int[] {1, 5, 7}, facetGroupsDisjunction.getFacetGroups());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetGroupsDisjunction(null).isApplicable());
		assertFalse(new FacetGroupsDisjunction("brand").isApplicable());
		assertTrue(facetGroupsDisjunction("brand", 1).isApplicable());
		assertTrue(facetGroupsDisjunction("brand", 1, 5, 7).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetGroupsDisjunction facetGroupsDisjunction = facetGroupsDisjunction("brand", 1, 5, 7);
		assertEquals("facetGroupsDisjunction('brand',1,5,7)", facetGroupsDisjunction.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 1, 1, 5));
		assertEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 1, 1, 5));
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 1, 1, 6));
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 1, 1));
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 2, 1, 5));
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("category", 1, 1, 6));
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5), facetGroupsDisjunction("brand", 1, 1));
		assertEquals(facetGroupsDisjunction("brand", 1, 1, 5).hashCode(), facetGroupsDisjunction("brand", 1, 1, 5).hashCode());
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5).hashCode(), facetGroupsDisjunction("brand", 1, 1, 6).hashCode());
		assertNotEquals(facetGroupsDisjunction("brand", 1, 1, 5).hashCode(), facetGroupsDisjunction("brand", 1, 1).hashCode());
	}

}