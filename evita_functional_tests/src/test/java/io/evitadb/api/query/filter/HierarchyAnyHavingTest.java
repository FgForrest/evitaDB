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

import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.anyHaving;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyAnyHaving} query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class HierarchyAnyHavingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyAnyHaving anyHaving = anyHaving(attributeEquals("code", "a"));
		assertArrayEquals(new FilterConstraint[] {attributeEquals("code", "a")}, anyHaving.getFiltering());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyAnyHaving().isApplicable());
		assertTrue(anyHaving(entityPrimaryKeyInSet(1)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyAnyHaving anyHaving = anyHaving(attributeEquals("code", "a"));
		assertEquals("anyHaving(attributeEquals('code','a'))", anyHaving.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(anyHaving(attributeEquals("code", "a")), anyHaving(attributeEquals("code", "a")));
		assertEquals(anyHaving(attributeEquals("code", "a")), anyHaving(attributeEquals("code", "a")));
		assertNotEquals(anyHaving(attributeEquals("code", "a")), anyHaving(entityPrimaryKeyInSet(1)));
		assertEquals(anyHaving(attributeEquals("code", "a")).hashCode(), anyHaving(attributeEquals("code", "a")).hashCode());
		assertNotEquals(anyHaving(attributeEquals("code", "a")).hashCode(), anyHaving(entityPrimaryKeyInSet(1)).hashCode());
	}

}