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
import static io.evitadb.api.query.QueryConstraints.includingChildren;
import static io.evitadb.api.query.QueryConstraints.includingChildrenHaving;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link FacetIncludingChildren} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetIncludingChildrenTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final FacetIncludingChildren referenceIncludingChildren = includingChildrenHaving(entityPrimaryKeyInSet(1, 5, 7));
		assertEquals(1, referenceIncludingChildren.getChildren().length);
		assertEquals(entityPrimaryKeyInSet(1, 5, 7), referenceIncludingChildren.getChildren()[0]);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new FacetIncludingChildren().isApplicable());
		assertTrue(includingChildrenHaving(entityPrimaryKeyInSet(1)).isApplicable());
		assertTrue(includingChildrenHaving(entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("includingChildren()", includingChildren().toString());
		assertEquals("includingChildrenHaving(entityPrimaryKeyInSet(1,5,7))", includingChildrenHaving(entityPrimaryKeyInSet(1, 5, 7)).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(includingChildren(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotEquals(includingChildren(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotSame(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
		assertEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1)));
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(2, 1, 5)));
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 6)));
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)), includingChildrenHaving(entityPrimaryKeyInSet(1, 1)));
		assertEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode());
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 6)).hashCode());
		assertNotEquals(includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1)).hashCode());
	}

}
