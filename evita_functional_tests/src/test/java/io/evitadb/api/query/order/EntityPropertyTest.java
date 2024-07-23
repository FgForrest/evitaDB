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
import static io.evitadb.api.query.QueryConstraints.entityProperty;
import static io.evitadb.api.query.QueryConstraints.priceNatural;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityProperty}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class EntityPropertyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final EntityProperty entityProperty = entityProperty(priceNatural());
		assertArrayEquals(new OrderConstraint[] { priceNatural() }, entityProperty.getChildren());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(entityProperty(priceNatural()).isApplicable());
		assertFalse(entityProperty().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final EntityProperty entityProperty1 = entityProperty(priceNatural());
		assertEquals("entityProperty(priceNatural(ASC))", entityProperty1.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(entityProperty(priceNatural()), entityProperty(priceNatural()));
		assertEquals(entityProperty(priceNatural()), entityProperty(priceNatural()));
		assertNotEquals(entityProperty(priceNatural()), entityProperty(attributeNatural("code")));
		assertNotEquals(entityProperty(priceNatural()), entityProperty(null));
		assertEquals(entityProperty(priceNatural()).hashCode(), entityProperty(priceNatural()).hashCode());
		assertNotEquals(entityProperty(priceNatural()).hashCode(), entityProperty(attributeNatural("code")).hashCode());
	}
}
