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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
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
		this.converter = new OrderConstraintToJsonConverter(this.catalogSchema);
	}

	@Test
	void shouldResolveValueOrderConstraint() {
		assertEquals(
			new JsonConstraint("attributeCodeNatural", jsonNodeFactory.textNode("ASC")),
			this.converter.convert(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				attributeNatural("CODE", OrderDirection.ASC)
			).get()
		);
	}

	@Test
	void shouldResolveChildOrderConstraint() {
		final ArrayNode referenceCategoryProperty = jsonNodeFactory.arrayNode();

		final ObjectNode attributeCodeNaturalWrapper = jsonNodeFactory.objectNode();
		attributeCodeNaturalWrapper.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("ASC"));
		referenceCategoryProperty.add(attributeCodeNaturalWrapper);

		final ObjectNode randomWrapper = jsonNodeFactory.objectNode();
		randomWrapper.putIfAbsent("random", jsonNodeFactory.booleanNode(true));
		referenceCategoryProperty.add(randomWrapper);

		assertEquals(
			new JsonConstraint("referenceCategoryProperty", referenceCategoryProperty),
			this.converter.convert(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				referenceProperty(
					"CATEGORY",
					attributeNatural("CODE", OrderDirection.ASC),
					random()
				)
			).get()
		);
	}

	@Test
	void shouldResolveComplexOrderConstraintTree() {
		final ArrayNode orderBy = jsonNodeFactory.arrayNode();

		final ObjectNode attributeCodeNaturalWrapper = jsonNodeFactory.objectNode();
		attributeCodeNaturalWrapper.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("ASC"));
		orderBy.add(attributeCodeNaturalWrapper);
		final ObjectNode priceNaturalWrapper = jsonNodeFactory.objectNode();
		priceNaturalWrapper.putIfAbsent("priceNatural", jsonNodeFactory.textNode("DESC"));
		orderBy.add(priceNaturalWrapper);

		final ArrayNode referenceCategoryProperty = jsonNodeFactory.arrayNode();
		final ObjectNode referenceAttributeCodeNaturalWrapper = jsonNodeFactory.objectNode();
		referenceAttributeCodeNaturalWrapper.putIfAbsent("attributeCodeNatural", jsonNodeFactory.textNode("DESC"));
		referenceCategoryProperty.add(referenceAttributeCodeNaturalWrapper);
		final ObjectNode referenceRandomWrapper = jsonNodeFactory.objectNode();
		referenceRandomWrapper.putIfAbsent("random", jsonNodeFactory.booleanNode(true));
		referenceCategoryProperty.add(referenceRandomWrapper);
		final ObjectNode referenceCategoryPropertyWrapper = jsonNodeFactory.objectNode();
		referenceCategoryPropertyWrapper.putIfAbsent("referenceCategoryProperty", referenceCategoryProperty);
		orderBy.add(referenceCategoryPropertyWrapper);

		assertEquals(
			new JsonConstraint("orderBy", orderBy),
			this.converter.convert(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				orderBy(
					attributeNatural("CODE", OrderDirection.ASC),
					priceNatural(OrderDirection.DESC),
					referenceProperty(
						"CATEGORY",
						attributeNatural("CODE", OrderDirection.DESC),
						random()
					)
				)
			).get()
		);
	}
}
