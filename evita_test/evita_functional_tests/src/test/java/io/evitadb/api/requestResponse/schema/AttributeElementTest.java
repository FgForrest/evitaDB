/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link AttributeElement} record verifying factory methods,
 * record accessors, equality/hashCode, and toString formatting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeElement record")
class AttributeElementTest {

	@Nested
	@DisplayName("Factory methods")
	class FactoryMethodsTest {

		@Test
		@DisplayName("creates element with defaults (ASC, NULLS_LAST)")
		void shouldCreateElementWithNameOnly() {
			final AttributeElement element = attributeElement("price");

			assertEquals("price", element.attributeName());
			assertEquals(OrderDirection.ASC, element.direction());
			assertEquals(OrderBehaviour.NULLS_LAST, element.behaviour());
		}

		@Test
		@DisplayName("creates element with custom direction")
		void shouldCreateElementWithNameAndDirection() {
			final AttributeElement element = attributeElement("price", OrderDirection.DESC);

			assertEquals("price", element.attributeName());
			assertEquals(OrderDirection.DESC, element.direction());
			assertEquals(OrderBehaviour.NULLS_LAST, element.behaviour());
		}

		@Test
		@DisplayName("creates element with custom behaviour")
		void shouldCreateElementWithNameAndBehaviour() {
			final AttributeElement element = attributeElement("price", OrderBehaviour.NULLS_FIRST);

			assertEquals("price", element.attributeName());
			assertEquals(OrderDirection.ASC, element.direction());
			assertEquals(OrderBehaviour.NULLS_FIRST, element.behaviour());
		}

		@Test
		@DisplayName("creates element with custom direction and behaviour")
		void shouldCreateElementWithAllParameters() {
			final AttributeElement element = attributeElement(
				"price", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST
			);

			assertEquals("price", element.attributeName());
			assertEquals(OrderDirection.DESC, element.direction());
			assertEquals(OrderBehaviour.NULLS_FIRST, element.behaviour());
		}
	}

	@Nested
	@DisplayName("Record accessors")
	class RecordAccessorsTest {

		@Test
		@DisplayName("attributeName returns the configured name")
		void shouldReturnCorrectAttributeName() {
			final AttributeElement element = new AttributeElement(
				"myAttribute", OrderDirection.ASC, OrderBehaviour.NULLS_LAST
			);

			assertEquals("myAttribute", element.attributeName());
		}

		@Test
		@DisplayName("direction returns the configured direction")
		void shouldReturnCorrectDirection() {
			final AttributeElement element = new AttributeElement(
				"attr", OrderDirection.DESC, OrderBehaviour.NULLS_LAST
			);

			assertEquals(OrderDirection.DESC, element.direction());
		}

		@Test
		@DisplayName("behaviour returns the configured behaviour")
		void shouldReturnCorrectBehaviour() {
			final AttributeElement element = new AttributeElement(
				"attr", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST
			);

			assertEquals(OrderBehaviour.NULLS_FIRST, element.behaviour());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCodeTest {

		@Test
		@DisplayName("equal elements have same hashCode")
		void shouldHaveSameHashCodeForEqualElements() {
			final AttributeElement element1 = attributeElement(
				"price", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST
			);
			final AttributeElement element2 = attributeElement(
				"price", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST
			);

			assertEquals(element1, element2);
			assertEquals(element1.hashCode(), element2.hashCode());
		}

		@Test
		@DisplayName("elements differ when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final AttributeElement element1 = attributeElement("price");
			final AttributeElement element2 = attributeElement("name");

			assertNotEquals(element1, element2);
		}

		@Test
		@DisplayName("elements differ when directions differ")
		void shouldNotBeEqualWhenDirectionsDiffer() {
			final AttributeElement element1 = attributeElement("price", OrderDirection.ASC);
			final AttributeElement element2 = attributeElement("price", OrderDirection.DESC);

			assertNotEquals(element1, element2);
		}

		@Test
		@DisplayName("elements differ when behaviours differ")
		void shouldNotBeEqualWhenBehavioursDiffer() {
			final AttributeElement element1 = attributeElement(
				"price", OrderBehaviour.NULLS_FIRST
			);
			final AttributeElement element2 = attributeElement(
				"price", OrderBehaviour.NULLS_LAST
			);

			assertNotEquals(element1, element2);
		}

		@Test
		@DisplayName("factory methods produce equal elements to direct constructor")
		void shouldBeEqualWhenCreatedByFactoryOrConstructor() {
			final AttributeElement viaFactory = attributeElement("price");
			final AttributeElement viaConstructor = new AttributeElement(
				"price", OrderDirection.ASC, OrderBehaviour.NULLS_LAST
			);

			assertEquals(viaFactory, viaConstructor);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTest {

		@Test
		@DisplayName("formats as 'name' DIRECTION BEHAVIOUR")
		void shouldFormatCorrectly() {
			final AttributeElement element = attributeElement(
				"price", OrderDirection.ASC, OrderBehaviour.NULLS_LAST
			);

			assertEquals("'price' ASC NULLS_LAST", element.toString());
		}

		@Test
		@DisplayName("formats DESC NULLS_FIRST correctly")
		void shouldFormatDescNullsFirstCorrectly() {
			final AttributeElement element = attributeElement(
				"name", OrderDirection.DESC, OrderBehaviour.NULLS_FIRST
			);

			assertEquals("'name' DESC NULLS_FIRST", element.toString());
		}
	}
}
