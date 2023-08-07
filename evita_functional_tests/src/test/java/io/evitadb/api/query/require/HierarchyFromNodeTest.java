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

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyFromNode} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyFromNodeTest {
	private static final HierarchyNode NODE_REF = node(filterBy(entityPrimaryKeyInSet(1)));
	private static final HierarchyNode NODE2_REF = node(filterBy(entityPrimaryKeyInSet(2)));

	@Test
	void shouldCreateWithStopAtViaFactoryClassWorkAsExpected() {
		final HierarchyFromNode hierarchyFromNode = fromNode("megaMenu", NODE_REF, stopAt(level(1)));
		assertEquals("megaMenu", hierarchyFromNode.getOutputName());
		assertEquals(NODE_REF, hierarchyFromNode.getFromNode());
		assertFalse(hierarchyFromNode.getEntityFetch().isPresent());
		assertFalse(hierarchyFromNode.getStatistics().isPresent());
		assertEquals(stopAt(level(1)), hierarchyFromNode.getStopAt().orElse(null));
	}

	@Test
	void shouldCreateWithEntityFetchViaFactoryClassWorkAsExpected() {
		final HierarchyFromNode hierarchyFromNode = fromNode("megaMenu", NODE_REF, entityFetchAll());
		assertEquals("megaMenu", hierarchyFromNode.getOutputName());
		assertEquals(NODE_REF, hierarchyFromNode.getFromNode());
		assertFalse(hierarchyFromNode.getStopAt().isPresent());
		assertFalse(hierarchyFromNode.getStatistics().isPresent());
		assertEquals(entityFetchAll(), hierarchyFromNode.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCreateWithStatisticsViaFactoryClassWorkAsExpected() {
		final HierarchyFromNode hierarchyFromNode = fromNode("megaMenu", NODE_REF, statistics());
		assertEquals("megaMenu", hierarchyFromNode.getOutputName());
		assertEquals(NODE_REF, hierarchyFromNode.getFromNode());
		assertFalse(hierarchyFromNode.getStopAt().isPresent());
		assertFalse(hierarchyFromNode.getEntityFetch().isPresent());
		assertEquals(statistics(), hierarchyFromNode.getStatistics().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyFromNode(null, null).isApplicable());
		assertFalse(new HierarchyFromNode("megaMenu", null).isApplicable());
		assertFalse(new HierarchyFromNode(null, NODE_REF).isApplicable());
		assertTrue(fromNode("megaMenu", NODE_REF, stopAt(level(1))).isApplicable());
		assertTrue(fromNode("megaMenu", NODE_REF, entityFetchAll()).isApplicable());
		assertTrue(fromNode("megaMenu", NODE_REF).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyFromNode hierarchyFromNode = fromNode("megaMenu", NODE_REF, stopAt(level(1)));
		assertEquals("fromNode('megaMenu',node(filterBy(entityPrimaryKeyInSet(1))),stopAt(level(1)))", hierarchyFromNode.toString());

		final HierarchyFromNode hierarchyFromNode2 = fromNode("megaMenu", NODE_REF, entityFetchAll());
		assertEquals("fromNode('megaMenu',node(filterBy(entityPrimaryKeyInSet(1))),entityFetch(attributeContentAll(),hierarchyContent(),associatedDataContentAll(),priceContentAll(),referenceContentAllWithAttributes(),dataInLocalesAll()))", hierarchyFromNode2.toString());

		final HierarchyFromNode hierarchyFromNode3 = fromNode("megaMenu", NODE_REF, statistics());
		assertEquals("fromNode('megaMenu',node(filterBy(entityPrimaryKeyInSet(1))),statistics(WITHOUT_USER_FILTER))", hierarchyFromNode3.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(fromNode("megaMenu", NODE_REF, stopAt(level(1))), fromNode("megaMenu", NODE_REF, stopAt(level(1))));
		assertEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))), fromNode("megaMenu", NODE_REF, stopAt(level(1))));
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))), fromNode("megaMenu", NODE_REF, entityFetchAll()));
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))), fromNode("megaMenu", NODE_REF, stopAt(distance(1))));
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))), fromNode("megaMenu", NODE2_REF, stopAt(distance(1))));
		assertEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))).hashCode(), fromNode("megaMenu", NODE_REF, stopAt(level(1))).hashCode());
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))).hashCode(), fromNode("megaMenu", NODE2_REF, stopAt(level(1))).hashCode());
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))).hashCode(), fromNode("megaMenu", NODE_REF, stopAt(level(2))).hashCode());
		assertNotEquals(fromNode("megaMenu", NODE_REF, stopAt(level(1))).hashCode(), fromNode("megaMenu", NODE_REF, entityFetchAll()).hashCode());
	}

}
