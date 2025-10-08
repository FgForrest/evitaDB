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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.Serializable;
import java.util.Arrays;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingGlobalAttributeSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateGlobalAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class CreateGlobalAttributeSchemaMutationTest {
	static final String ATTRIBUTE_NAME = "name";

	@Test
	void shouldThrowExceptionWhenInvalidTypeIsProvided() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> new CreateGlobalAttributeSchemaMutation(
				ATTRIBUTE_NAME, "description", "deprecationNotice",
				AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
				Serializable.class, null, 2
			)
		);
	}

	@Test
	void shouldBeReplacedWithIndividualMutationsWhenGlobalAttributeWasRemovedAndCreatedWithDifferentSettings() {
		CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "newDescription", "newDeprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
			true, true, true, true, true,
			String.class, "abc", 0
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(
				of(createExistingGlobalAttributeSchema())
			);
		RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			catalogSchema,
			removeMutation
		);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(11, result.current().length);
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDescriptionMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDeprecationNoticeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaTypeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDefaultValueMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaLocalizedMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaNullableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaUniqueMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaGloballyUniqueMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaFilterableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaSortableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaRepresentativeMutation));
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, removeMutation));
	}

	@Test
	void shouldCreateGlobalAttribute() {
		CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, true, true, false,
			String.class, null, 0
		);
		final GlobalAttributeSchemaContract attributeSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null, GlobalAttributeSchemaContract.class);
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, attributeSchema.getGlobalUniquenessType());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldCreateAttributeInCatalog() {
		CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, true, true, false,
			String.class, null, 0
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, Mockito.mock(EntitySchemaProvider.class));
		assertNull(result.entitySchemaMutations());
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertNotNull(newCatalogSchema);
		assertEquals(2, newCatalogSchema.version());
		final GlobalAttributeSchemaContract attributeSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, attributeSchema.getGlobalUniquenessType());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldThrowExceptionWhenMutatingCatalogSchemaWithExistingAttribute() {
		CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
				Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
					.thenReturn(of(createExistingGlobalAttributeSchema()));
				mutation.mutate(catalogSchema, Mockito.mock(EntitySchemaProvider.class));
			}
		);
	}

}
