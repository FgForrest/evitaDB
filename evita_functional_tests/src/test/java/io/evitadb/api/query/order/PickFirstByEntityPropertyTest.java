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
import static io.evitadb.api.query.QueryConstraints.pickFirstByEntityProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PickFirstByEntityProperty}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class PickFirstByEntityPropertyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final PickFirstByEntityProperty pickFirstByEntityProperty = pickFirstByEntityProperty(attributeNatural("code"));
		assertArrayEquals(new OrderConstraint[] { attributeNatural("code") }, pickFirstByEntityProperty.getChildren());
		assertNull(pickFirstByEntityProperty());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(pickFirstByEntityProperty(attributeNatural("code")).isApplicable());
		assertFalse(new PickFirstByEntityProperty().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final PickFirstByEntityProperty pickFirstByEntityProperty1 = pickFirstByEntityProperty(attributeNatural("code"));
		assertEquals("pickFirstByEntityProperty(attributeNatural('code',ASC))", pickFirstByEntityProperty1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(pickFirstByEntityProperty(attributeNatural("code")), pickFirstByEntityProperty(attributeNatural("code")));
		assertEquals(pickFirstByEntityProperty(attributeNatural("code")), pickFirstByEntityProperty(attributeNatural("code")));
		assertNotEquals(pickFirstByEntityProperty(attributeNatural("code")), pickFirstByEntityProperty(attributeNatural("order")));
		assertNotEquals(pickFirstByEntityProperty(attributeNatural("code")), new PickFirstByEntityProperty());
		assertEquals(pickFirstByEntityProperty(attributeNatural("code")).hashCode(), pickFirstByEntityProperty(attributeNatural("code")).hashCode());
		assertNotEquals(pickFirstByEntityProperty(attributeNatural("code")).hashCode(), pickFirstByEntityProperty(attributeNatural("order")).hashCode());
	}

}
