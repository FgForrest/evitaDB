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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingEntityAttributeSchema;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingGlobalAttributeSchema;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link UseGlobalAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class UseGlobalAttributeSchemaMutationTest {
	static final String ATTRIBUTE_NAME = "name";

	@Test
	void shouldCreateEntityAttribute() {
		UseGlobalAttributeSchemaMutation mutation = new UseGlobalAttributeSchemaMutation(ATTRIBUTE_NAME);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchemaContract.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingGlobalAttributeSchema()));
		final GlobalAttributeSchemaContract attributeSchema = mutation.mutate(
			catalogSchemaContract, null, GlobalAttributeSchemaContract.class
		);
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(Integer.class, attributeSchema.getType());
		assertFalse(attributeSchema.isLocalized());
		assertFalse(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, attributeSchema.getGlobalUniquenessType());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(2, attributeSchema.getIndexedDecimalPlaces());

		assertInstanceOf(GlobalAttributeSchemaContract.class, attributeSchema);
	}

	@Test
	void shouldCreateAttributeInEntity() {
		UseGlobalAttributeSchemaMutation mutation = new UseGlobalAttributeSchemaMutation(ATTRIBUTE_NAME);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchemaContract.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingGlobalAttributeSchema()));
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(catalogSchemaContract, entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract attributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(Integer.class, attributeSchema.getType());
		assertFalse(attributeSchema.isLocalized());
		assertFalse(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(2, attributeSchema.getIndexedDecimalPlaces());

		assertInstanceOf(GlobalAttributeSchemaContract.class, attributeSchema);
	}

	@Test
	void shouldThrowExceptionWhenNoAttributeOfSuchNameIsPresentInCatalogSchema() {
		UseGlobalAttributeSchemaMutation mutation = new UseGlobalAttributeSchemaMutation(ATTRIBUTE_NAME);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchemaContract.getAttribute(ATTRIBUTE_NAME)).thenReturn(empty());
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			}
		);
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAttribute() {
		UseGlobalAttributeSchemaMutation mutation = new UseGlobalAttributeSchemaMutation(ATTRIBUTE_NAME);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchemaContract.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingGlobalAttributeSchema()));
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
					.thenReturn(of(createExistingEntityAttributeSchema()));
				mutation.mutate(catalogSchemaContract, entitySchema);
			}
		);
	}

}
