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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link FacetHaving} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetHavingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetHaving facetHaving = facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7));
		assertEquals("brand", facetHaving.getReferenceName());
		assertEquals(1, facetHaving.getChildren().length);
		assertEquals(entityPrimaryKeyInSet(1, 5, 7), facetHaving.getChildren()[0]);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new FacetHaving("brand").isApplicable());
		assertTrue(facetHaving("brand", entityPrimaryKeyInSet(1)).isApplicable());
		assertTrue(facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final FacetHaving facetHaving = facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7));
		assertEquals("facetHaving('brand',entityPrimaryKeyInSet(1,5,7))", facetHaving.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)));
		assertEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)));
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(1, 1)));
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(2, 1, 5)));
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("category", entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)), facetHaving("brand", entityPrimaryKeyInSet(1, 1)));
		assertEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(), facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode());
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(), facetHaving("brand", entityPrimaryKeyInSet(1, 1, 6)).hashCode());
		assertNotEquals(facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(), facetHaving("brand", entityPrimaryKeyInSet(1, 1)).hashCode());
	}

}
