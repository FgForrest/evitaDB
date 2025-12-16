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

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.referenceProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceProperty}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ReferencePropertyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ReferenceProperty referenceProperty = referenceProperty("parameters", attributeNatural("code"));
		assertEquals("parameters", referenceProperty.getReferenceName());
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, referenceProperty.getChildren());
		assertNull(referenceProperty("parameter"));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(referenceProperty("parameter", attributeNatural("code")).isApplicable());
		assertFalse(new ReferenceProperty("parameter").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ReferenceProperty referenceProperty1 = referenceProperty("parameter", attributeNatural("code"));
		assertEquals("referenceProperty('parameter',attributeNatural('code',ASC))", referenceProperty1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(referenceProperty("parameter", attributeNatural("code")), referenceProperty("parameter", attributeNatural("code")));
		assertEquals(referenceProperty("parameter", attributeNatural("code")), referenceProperty("parameter", attributeNatural("code")));
		assertNotEquals(referenceProperty("parameter", attributeNatural("code")), referenceProperty("parameter", attributeNatural("order")));
		assertNotEquals(referenceProperty("parameter", attributeNatural("code")), referenceProperty("groups", attributeNatural("code")));
		assertNotEquals(referenceProperty("parameter", attributeNatural("code")), referenceProperty("parameter", null));
		assertEquals(referenceProperty("parameter", attributeNatural("code")).hashCode(), referenceProperty("parameter", attributeNatural("code")).hashCode());
		assertNotEquals(referenceProperty("parameter", attributeNatural("code")).hashCode(), referenceProperty("parameter", attributeNatural("order")).hashCode());
		assertNotEquals(referenceProperty("parameter", attributeNatural("code")).hashCode(), referenceProperty("groups", attributeNatural("code")).hashCode());
	}
}
