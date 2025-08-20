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
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link FilterConstraintToJsonConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class FilterConstraintToJsonConverterTest extends ConstraintToJsonConverterTest {

	private FilterConstraintToJsonConverter converter;

	@BeforeEach
	void init() {
		super.init();
		this.converter = new FilterConstraintToJsonConverter(this.catalogSchema);
	}

	@Test
	void shouldResolveValueFilterConstraint() {
		assertEquals(
			new JsonConstraint(
				"attributeCodeEquals",
				jsonNodeFactory.numberNode(123)
			),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				attributeEquals("CODE", 123)
			).get()
		);
	}

	@Test
	void shouldResolveChildFilterConstraint() {
		final ArrayNode and1 = jsonNodeFactory.arrayNode();
		final ObjectNode wrapperObject1 = jsonNodeFactory.objectNode();
		wrapperObject1.putIfAbsent("attributeCodeEquals", jsonNodeFactory.textNode("123"));
		wrapperObject1.putIfAbsent("attributeAgeIs", jsonNodeFactory.textNode("NULL"));
		and1.add(wrapperObject1);

		assertEquals(
			new JsonConstraint("and", and1),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				and(
					attributeEquals("CODE", "123"),
					attributeIs("AGE", AttributeSpecialValue.NULL)
				)
			).get()
		);

		final ArrayNode and2 = jsonNodeFactory.arrayNode();
		final ObjectNode wrapperObject21 = jsonNodeFactory.objectNode();
		wrapperObject21.putIfAbsent("attributeCodeEquals", jsonNodeFactory.textNode("123"));
		and2.add(wrapperObject21);
		final ObjectNode wrapperObject22 = jsonNodeFactory.objectNode();
		wrapperObject22.putIfAbsent("attributeCodeEquals", jsonNodeFactory.numberNode(321));
		and2.add(wrapperObject22);

		assertEquals(
			new JsonConstraint("and", and2),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				and(
					attributeEquals("CODE", "123"),
					attributeEquals("CODE", 321)
				)
			).get()
		);
	}


	@Test
	void shouldResolveFilterConstraintWithMultipleArguments() {
		final ObjectNode within = jsonNodeFactory.objectNode();

		final ObjectNode ofParent = jsonNodeFactory.objectNode();
		final ArrayNode ofParentPKs = jsonNodeFactory.arrayNode();
		ofParentPKs.add(1);
		ofParent.putIfAbsent("entityPrimaryKeyInSet", ofParentPKs);
		within.putIfAbsent("ofParent", ofParent);

		final ObjectNode with = jsonNodeFactory.objectNode();
		with.putIfAbsent("directRelation", jsonNodeFactory.booleanNode(true));
		within.putIfAbsent("with", with);

		assertEquals(
			new JsonConstraint("hierarchyCategoryWithin", within),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				hierarchyWithin(
					"CATEGORY",
					entityPrimaryKeyInSet(1),
					directRelation()
				)
			).get()
		);
	}

	@Test
	void shouldResolveFilterConstraintWithArgumentsResultingInRange() {
		final ArrayNode between1 = jsonNodeFactory.arrayNode();
		between1.add(jsonNodeFactory.numberNode(1));
		between1.add(jsonNodeFactory.numberNode(2));

		assertEquals(
			new JsonConstraint("attributeAgeBetween", between1),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				attributeBetween(
					"AGE",
					1,
					2
				)
			).get()
		);

		final ArrayNode between2 = jsonNodeFactory.arrayNode();
		between2.add(jsonNodeFactory.nullNode());
		between2.add(jsonNodeFactory.numberNode(2));

		assertEquals(
			new JsonConstraint("attributeAgeBetween", between2),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				attributeBetween(
					"AGE",
					null,
					2
				)
			).get()
		);

		final ArrayNode between3 = jsonNodeFactory.arrayNode();
		between3.add(jsonNodeFactory.numberNode(1));
		between3.add(jsonNodeFactory.nullNode());

		assertEquals(
			new JsonConstraint("attributeAgeBetween", between3),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				attributeBetween(
					"AGE",
					1,
					null
				)
			).get()
		);
	}

	@Test
	void shouldResolveComplexFilterConstraintTree() {
		final ObjectNode filterBy = jsonNodeFactory.objectNode();

		filterBy.putIfAbsent("attributeCodeEquals", jsonNodeFactory.textNode("123"));

		final ArrayNode or = jsonNodeFactory.arrayNode();

		final ObjectNode orWrapperContainer1 = jsonNodeFactory.objectNode();
		orWrapperContainer1.putIfAbsent("attributeAgeIs", jsonNodeFactory.textNode("NULL"));
		or.add(orWrapperContainer1);

		final ArrayNode and = jsonNodeFactory.arrayNode();
		final ObjectNode andWrapperContainer = jsonNodeFactory.objectNode();

		final ObjectNode orWrapperContainer2 = jsonNodeFactory.objectNode();

		final ArrayNode priceBetween = jsonNodeFactory.arrayNode();
		priceBetween.add(jsonNodeFactory.textNode("10"));
		priceBetween.add(jsonNodeFactory.textNode("20"));
		andWrapperContainer.putIfAbsent("priceBetween", priceBetween);

		final ObjectNode facetHavingWrapperContainer = jsonNodeFactory.objectNode();
		final ArrayNode entityPrimaryKeyInSet = jsonNodeFactory.arrayNode();
		entityPrimaryKeyInSet.add(10);
		entityPrimaryKeyInSet.add(20);
		entityPrimaryKeyInSet.add(30);
		facetHavingWrapperContainer.putIfAbsent("entityPrimaryKeyInSet", entityPrimaryKeyInSet);
		andWrapperContainer.putIfAbsent("facetBrandHaving", facetHavingWrapperContainer);

		and.add(andWrapperContainer);
		orWrapperContainer2.putIfAbsent("and", and);

		or.add(orWrapperContainer2);

		filterBy.putIfAbsent("or", or);

		final ArrayNode referenceHaving = jsonNodeFactory.arrayNode();
		final ObjectNode referenceHavingWrapperContainer = jsonNodeFactory.objectNode();

		referenceHavingWrapperContainer.putIfAbsent("attributeCodeStartsWith", jsonNodeFactory.textNode("ab"));

		final ArrayNode entityPrimaryKeyInSet2 = jsonNodeFactory.arrayNode();
		entityPrimaryKeyInSet2.add(2);
		referenceHavingWrapperContainer.put("entityPrimaryKeyInSet", entityPrimaryKeyInSet2);

		final ObjectNode entityHaving = jsonNodeFactory.objectNode();

		final ArrayNode and2 = jsonNodeFactory.arrayNode();
		final ObjectNode and2WrapperContainer = jsonNodeFactory.objectNode();
		and2WrapperContainer.putIfAbsent("attributeNameEquals", jsonNodeFactory.textNode("cd"));

		final ArrayNode referenceRelatedProductsHaving = jsonNodeFactory.arrayNode();
		final ObjectNode referenceRelatedProductsHavingWrapperContainer = jsonNodeFactory.objectNode();
		referenceRelatedProductsHavingWrapperContainer.putIfAbsent("attributeOrderEquals", jsonNodeFactory.numberNode(1));
		referenceRelatedProductsHaving.add(referenceRelatedProductsHavingWrapperContainer);
		and2WrapperContainer.putIfAbsent("referenceRelatedProductsHaving", referenceRelatedProductsHaving);

		and2.add(and2WrapperContainer);
		entityHaving.putIfAbsent("and", and2);

		referenceHavingWrapperContainer.putIfAbsent("entityHaving", entityHaving);

		referenceHaving.add(referenceHavingWrapperContainer);

		filterBy.putIfAbsent("referenceCategoryHaving", referenceHaving);

		assertEquals(
			new JsonConstraint("filterBy", filterBy),
			this.converter.convert(
				new EntityDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				filterBy(
					attributeEquals("CODE", "123"),
					or(
						attributeIs("AGE", AttributeSpecialValue.NULL),
						and(
							priceBetween(BigDecimal.valueOf(10L), BigDecimal.valueOf(20L)),
							facetHaving("BRAND", entityPrimaryKeyInSet(10, 20, 30))
						)
					),
					referenceHaving(
						"CATEGORY",
						attributeStartsWith("CODE", "ab"),
						entityPrimaryKeyInSet(2),
						entityHaving(
							and(
								attributeEquals("NAME", "cd"),
								referenceHaving(
									"RELATED_PRODUCTS",
									attributeEquals("ORDER", 1)
								)
							)
						)
					)
				)
			).get()
		);
	}
}
