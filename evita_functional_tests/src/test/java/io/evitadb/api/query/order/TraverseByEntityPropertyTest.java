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
		final TraverseByEntityProperty traverseByEntityProperty = traverseByEntityProperty(attributeNatural("code"));
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, traverseByEntityProperty.getChildren());
		assertNull(traverseByEntityProperty());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(traverseByEntityProperty(attributeNatural("code")).isApplicable());
		assertFalse(new TraverseByEntityProperty().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final TraverseByEntityProperty traverseByEntityProperty1 = traverseByEntityProperty(attributeNatural("code"));
		assertEquals("traverseByEntityProperty(attributeNatural('code',ASC))", traverseByEntityProperty1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertEquals(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("code")));
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")), traverseByEntityProperty(attributeNatural("order")));
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")), new TraverseByEntityProperty());
		assertEquals(traverseByEntityProperty(attributeNatural("code")).hashCode(), traverseByEntityProperty(attributeNatural("code")).hashCode());
		assertNotEquals(traverseByEntityProperty(attributeNatural("code")).hashCode(), traverseByEntityProperty(attributeNatural("order")).hashCode());
	}

}
