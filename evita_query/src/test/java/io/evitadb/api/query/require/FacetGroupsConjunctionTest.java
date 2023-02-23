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

import static io.evitadb.api.query.QueryConstraints.facetGroupsConjunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetGroupsConjunction} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetGroupsConjunctionTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetGroupsConjunction facetGroupsConjunction = facetGroupsConjunction("brand", 1, 5, 7);
		assertEquals("brand", facetGroupsConjunction.getReferenceName());
		assertArrayEquals(new int[] {1, 5, 7}, facetGroupsConjunction.getFacetGroups());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetGroupsConjunction(null).isApplicable());
		assertFalse(new FacetGroupsConjunction("brand").isApplicable());
		assertTrue(facetGroupsConjunction("brand", 1).isApplicable());
		assertTrue(facetGroupsConjunction("brand", 1, 5, 7).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetGroupsConjunction facetGroupsConjunction = facetGroupsConjunction("brand", 1, 5, 7);
		assertEquals("facetGroupsConjunction('brand',1,5,7)", facetGroupsConjunction.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 1, 1, 5));
		assertEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 1, 1, 5));
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 1, 1, 6));
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 1, 1));
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 2, 1, 5));
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("category", 1, 1, 6));
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5), facetGroupsConjunction("brand", 1, 1));
		assertEquals(facetGroupsConjunction("brand", 1, 1, 5).hashCode(), facetGroupsConjunction("brand", 1, 1, 5).hashCode());
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5).hashCode(), facetGroupsConjunction("brand", 1, 1, 6).hashCode());
		assertNotEquals(facetGroupsConjunction("brand", 1, 1, 5).hashCode(), facetGroupsConjunction("brand", 1, 1).hashCode());
	}

}