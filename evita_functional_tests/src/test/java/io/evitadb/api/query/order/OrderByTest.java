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

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.And;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link And} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class OrderByTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<OrderConstraint> orderBy =
				orderBy(
					QueryConstraints.attributeNatural("abc"),
					attributeNatural("def", DESC)
				);
		assertNotNull(orderBy);
		assertEquals(2, orderBy.getChildrenCount());
		assertEquals("abc", ((AttributeNatural)orderBy.getChildren()[0]).getAttributeName());
		assertEquals(ASC, ((AttributeNatural) orderBy.getChildren()[0]).getOrderDirection());
		assertEquals("def", ((AttributeNatural)orderBy.getChildren()[1]).getAttributeName());
		assertEquals(DESC, ((AttributeNatural) orderBy.getChildren()[1]).getOrderDirection());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new OrderBy(QueryConstraints.attributeNatural("abc")).isApplicable());
		assertFalse(new OrderBy().isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new OrderBy(QueryConstraints.attributeNatural("abc"), attributeNatural("xyz", DESC)).isNecessary());
		assertTrue(new OrderBy(QueryConstraints.attributeNatural("abc")).isNecessary());
		assertFalse(new OrderBy().isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<OrderConstraint> orderBy =
				orderBy(
					QueryConstraints.attributeNatural("ab'c"),
					attributeNatural("abc", DESC)
				);
		assertNotNull(orderBy);
		assertEquals("orderBy(attributeNatural('ab\\'c',ASC),attributeNatural('abc',DESC))", orderBy.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(createOrderByConstraint("abc", "def"), createOrderByConstraint("abc", "def"));
		assertEquals(createOrderByConstraint("abc", "def"), createOrderByConstraint("abc", "def"));
		assertNotEquals(createOrderByConstraint("abc", "def"), createOrderByConstraint("abc", "defe"));
		assertNotEquals(createOrderByConstraint("abc", "def"), createOrderByConstraint("abc", null));
		assertNotEquals(createOrderByConstraint("abc", "def"), createOrderByConstraint(null, "abc"));
		assertEquals(createOrderByConstraint("abc", "def").hashCode(), createOrderByConstraint("abc", "def").hashCode());
		assertNotEquals(createOrderByConstraint("abc", "def").hashCode(), createOrderByConstraint("abc", "defe").hashCode());
		assertNotEquals(createOrderByConstraint("abc", "def").hashCode(), createOrderByConstraint("abc", null).hashCode());
		assertNotEquals(createOrderByConstraint("abc", "def").hashCode(), createOrderByConstraint(null, "abc").hashCode());
	}

	private static OrderBy createOrderByConstraint(String... values) {
		return orderBy(
				Arrays.stream(values)
						.map(QueryConstraints::attributeNatural)
						.toArray(OrderConstraint[]::new)
		);
	}

}
