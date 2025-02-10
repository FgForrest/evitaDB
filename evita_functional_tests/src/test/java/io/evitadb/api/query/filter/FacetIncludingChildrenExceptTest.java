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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.includingChildrenExcept;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link FacetIncludingChildrenExcept} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetIncludingChildrenExceptTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetIncludingChildrenExcept facetIncludingChildrenExcept = includingChildrenExcept(entityPrimaryKeyInSet(1, 5, 7));
		assertEquals(1, facetIncludingChildrenExcept.getChildren().length);
		assertEquals(entityPrimaryKeyInSet(1, 5, 7), facetIncludingChildrenExcept.getChildren()[0]);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new FacetIncludingChildrenExcept().isApplicable());
		assertTrue(includingChildrenExcept(entityPrimaryKeyInSet(1)).isApplicable());
		assertTrue(includingChildrenExcept(entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("includingChildrenExcept()", new FacetIncludingChildrenExcept().toString());
		assertEquals("includingChildrenExcept(entityPrimaryKeyInSet(1,5,7))", includingChildrenExcept(entityPrimaryKeyInSet(1, 5, 7)).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(new FacetIncludingChildrenExcept(), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotEquals(new FacetIncludingChildrenExcept(), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotSame(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)));
		assertEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1)));
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(2, 1, 5)));
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenExcept(entityPrimaryKeyInSet(1, 1)));
		assertEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode());
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 6)).hashCode());
		assertNotEquals(includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenExcept(entityPrimaryKeyInSet(1, 1)).hashCode());
	}

}
