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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link CatalogSchemaBuilder} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogSchemaBuilderTest {
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG,
		NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	private static void assertAttribute(
		GlobalAttributeSchemaContract attributeSchema,
		boolean unique,
		boolean uniqueGlobally,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		int indexedDecimalPlaces,
		Class<? extends Serializable> ofType
	) {
		assertNotNull(attributeSchema);
		assertEquals(unique, attributeSchema.isUnique());
		assertEquals(uniqueGlobally, attributeSchema.isUniqueGlobally());
		assertEquals(filterable, attributeSchema.isFilterable());
		assertEquals(sortable, attributeSchema.isSortable());
		assertEquals(localized, attributeSchema.isLocalized());
		assertEquals(nullable, attributeSchema.isNullable());
		assertEquals(representative, attributeSchema.isRepresentative());
		assertEquals(ofType, attributeSchema.getType());
		assertEquals(indexedDecimalPlaces, attributeSchema.getIndexedDecimalPlaces());
	}

	@SuppressWarnings("Convert2MethodRef")
	@Test
	void shouldDefineCategorySchema() {
		final CatalogSchemaContract updatedSchema = new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.uniqueGlobally().representative())
			.withAttribute("url", String.class, whichIs -> whichIs.uniqueGlobally().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable().nullable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			/* finally apply schema changes */
			.toInstance();

		assertEquals(5, updatedSchema.getAttributes().size());
		assertAttribute(updatedSchema.getAttribute("code").orElseThrow(), true, true, false, false, false, false, true, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("name").orElseThrow(), false, false, true, true, false, true, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("url").orElseThrow(), true, true, false, false, true, false, false, 0, String.class);
		assertAttribute(updatedSchema.getAttribute("oldEntityUrls").orElseThrow(), false, false, true, false, true, false, false, 0, String[].class);
		assertAttribute(updatedSchema.getAttribute("priority").orElseThrow(), false, false, false, true, false, false, false, 0, Long.class);
	}

	@Test
	void shouldFailToDefineTwoAttributesSharingNameInSpecificNamingConvention() {
		assertThrows(
			AttributeAlreadyPresentInEntitySchemaException.class,
			() -> new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
				.withAttribute("abc", String.class)
				.withAttribute("Abc", String.class)
				.toMutation()
		);
	}

	@Test
	void shouldWorkWithAttributesInNamingConventionsWorkProperly() {
		final CatalogSchemaBuilder schemaBuilder = new CatalogSchemaDecorator(CATALOG_SCHEMA).openForWrite()
			.withAttribute("some-attribute-1", String.class)
			.withAttribute("attribute", String.class)
			.withAttribute("code", String.class);

		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		schemaBuilder.withoutAttribute("attribute");

		assertNotNull(schemaBuilder.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(schemaBuilder.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(schemaBuilder.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));

		final CatalogSchemaContract updatedSchema = schemaBuilder.toInstance();

		assertNotNull(updatedSchema.getAttributeByName("someAttribute1", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.CAMEL_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.KEBAB_CASE).orElse(null));
		assertNull(updatedSchema.getAttributeByName("attribute", NamingConvention.SNAKE_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.CAMEL_CASE).orElse(null));
		assertNotNull(updatedSchema.getAttributeByName("code", NamingConvention.SNAKE_CASE).orElse(null));
	}

}
