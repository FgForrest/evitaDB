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

package io.evitadb.api.query.require;

import io.evitadb.api.query.filter.FilterBy;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.node;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyNode} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyNodeTest {
	private static final FilterBy NODE_REF = filterBy(entityPrimaryKeyInSet(1));
	private static final FilterBy NODE2_REF = filterBy(entityPrimaryKeyInSet(2));

	@Test
	void shouldCreateWithNodeViaFactoryClassWorkAsExpected() {
		final HierarchyNode hierarchyNode = node(NODE_REF);
		assertEquals(NODE_REF, hierarchyNode.getFilterBy());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyNode(null).isApplicable());
		assertTrue(node(NODE_REF).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyNode hierarchyNode = node(NODE_REF);
		assertEquals("node(filterBy(entityPrimaryKeyInSet(1)))", hierarchyNode.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(node(NODE_REF), node(NODE_REF));
		assertEquals(node(NODE_REF), node(NODE_REF));
		assertNotEquals(node(NODE_REF), node(NODE2_REF));
		assertEquals(node(NODE_REF).hashCode(), node(NODE_REF).hashCode());
		assertNotEquals(node(NODE_REF).hashCode(), node(NODE2_REF).hashCode());
	}

}
