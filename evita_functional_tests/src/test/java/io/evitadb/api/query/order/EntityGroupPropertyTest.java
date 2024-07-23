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

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.entityGroupProperty;
import static io.evitadb.api.query.QueryConstraints.priceNatural;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityGroupProperty}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class EntityGroupPropertyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityGroupProperty entityGroupProperty = entityGroupProperty(priceNatural());
		assertArrayEquals(new OrderConstraint[] { priceNatural() }, entityGroupProperty.getChildren());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityGroupProperty(priceNatural()).isApplicable());
		assertFalse(entityGroupProperty().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityGroupProperty entityGroupProperty1 = entityGroupProperty(priceNatural());
		assertEquals("entityGroupProperty(priceNatural(ASC))", entityGroupProperty1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityGroupProperty(priceNatural()), entityGroupProperty(priceNatural()));
		assertEquals(entityGroupProperty(priceNatural()), entityGroupProperty(priceNatural()));
		assertNotEquals(entityGroupProperty(priceNatural()), entityGroupProperty(attributeNatural("code")));
		assertNotEquals(entityGroupProperty(priceNatural()), entityGroupProperty(null));
		assertEquals(entityGroupProperty(priceNatural()).hashCode(), entityGroupProperty(priceNatural()).hashCode());
		assertNotEquals(entityGroupProperty(priceNatural()).hashCode(), entityGroupProperty(attributeNatural("code")).hashCode());
	}
}
