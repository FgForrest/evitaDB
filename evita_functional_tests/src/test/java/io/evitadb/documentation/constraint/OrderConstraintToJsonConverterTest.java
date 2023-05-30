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

package io.evitadb.documentation.constraint;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link OrderConstraintToJsonConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class OrderConstraintToJsonConverterTest extends ConstraintToJsonConverterTest {

	private OrderConstraintToJsonConverter converter;

	@BeforeEach
	void init() {
		super.init();
		this.converter = new OrderConstraintToJsonConverter(catalogSchema);
	}

	@Test
	void shouldResolveValueOrderConstraint() {
		assertEquals(
			new JsonConstraint("attributeCodeNatural", jsonNodeFactory.textNode("ASC")),
			converter.convert(
				new GenericDataLocator(Entities.PRODUCT),
				attributeNatural("CODE", OrderDirection.ASC)
			)
		);
	}

	@Test
	void shouldResolveChildOrderConstraint() {
		final ObjectNode referenceCategoryProperty = jsonNodeFactory.objectNode();
		referenceCategoryProperty.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("ASC"));
		referenceCategoryProperty.putIfAbsent("random", jsonNodeFactory.booleanNode(true));

		assertEquals(
			new JsonConstraint("referenceCategoryProperty", referenceCategoryProperty),
			converter.convert(
				new GenericDataLocator(Entities.PRODUCT),
				referenceProperty(
					"CATEGORY",
					attributeNatural("CODE", OrderDirection.ASC),
					random()
				)
			)
		);
	}

	@Test
	void shouldResolveComplexOrderConstraintTree() {
		final ObjectNode orderBy = jsonNodeFactory.objectNode();
		orderBy.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("ASC"));
		orderBy.putIfAbsent("priceNatural", jsonNodeFactory.textNode("DESC"));

		final ObjectNode referenceCategoryProperty = jsonNodeFactory.objectNode();
		referenceCategoryProperty.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("DESC"));
		referenceCategoryProperty.putIfAbsent("random", jsonNodeFactory.booleanNode(true));
		orderBy.putIfAbsent("referenceCategoryProperty", referenceCategoryProperty);

		assertEquals(
			new JsonConstraint("orderBy", orderBy),
			converter.convert(
				new GenericDataLocator(Entities.PRODUCT),
				orderBy(
					attributeNatural("CODE", OrderDirection.ASC),
					priceNatural(OrderDirection.DESC),
					referenceProperty(
						"CATEGORY",
						attributeNatural("CODE", OrderDirection.DESC),
						random()
					)
				)
			)
		);
	}
}
