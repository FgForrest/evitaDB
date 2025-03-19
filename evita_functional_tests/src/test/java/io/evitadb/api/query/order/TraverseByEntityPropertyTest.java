/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query.order;


import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.traverseByEntityProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraverseByEntityProperty}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TraverseByEntityPropertyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final TraverseByEntityProperty traverseByEntityProperty1 = traverseByEntityProperty(attributeNatural("code"));
		assertEquals(TraversalMode.DEPTH_FIRST, traverseByEntityProperty1.getTraversalMode());
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, traverseByEntityProperty1.getChildren());

		final TraverseByEntityProperty traverseByEntityProperty2 = traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code"));
		assertEquals(TraversalMode.BREADTH_FIRST, traverseByEntityProperty2.getTraversalMode());
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, traverseByEntityProperty2.getChildren());

		final TraverseByEntityProperty traverseByEntityProperty3 = traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code"));
		assertEquals(TraversalMode.DEPTH_FIRST, traverseByEntityProperty3.getTraversalMode());
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, traverseByEntityProperty3.getChildren());

		assertEquals(traverseByEntityProperty(TraversalMode.DEPTH_FIRST, entityPrimaryKeyNatural(OrderDirection.ASC)), traverseByEntityProperty());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(traverseByEntityProperty(attributeNatural("code")).isApplicable());
		assertTrue(traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")).isApplicable());
		assertFalse(new TraverseByEntityProperty(null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final TraverseByEntityProperty traverseByEntityProperty1 = traverseByEntityProperty(attributeNatural("code"));
		assertEquals("traverseByEntityProperty(attributeNatural('code',ASC))", traverseByEntityProperty1.toString());

		final TraverseByEntityProperty traverseByEntityProperty2 = traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code"));
		assertEquals("traverseByEntityProperty(BREADTH_FIRST,attributeNatural('code',ASC))", traverseByEntityProperty2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertNotSame(traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")), traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")));
		assertEquals(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertEquals(traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertNotEquals(traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertNotEquals(traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")), traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code")));
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("order")));
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")), new TraverseByEntityProperty(null));
		assertEquals(traverseByEntityProperty(attributeNatural("code")).hashCode(), traverseByEntityProperty(attributeNatural("code")).hashCode());
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")).hashCode(), traverseByEntityProperty(attributeNatural("order")).hashCode());
	}

}
