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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAttributeSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class RemoveAttributeSchemaMutationTest {

	@Test
	void shouldRemovePreviousGlobalAttributeCreateMutationWithSameName() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		CreateGlobalAttributeSchemaMutation previousMutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, GlobalAttributeUniquenessType.NOT_UNIQUE,
			false, false, false, false, false,
			String.class, null, 2
		);
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), previousMutation
		);
		assertNotNull(result);
		assertTrue(result.discarded());
		assertNull(result.origin());
		assertNotNull(result.current());
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentGlobalAttribute() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		CreateGlobalAttributeSchemaMutation previousMutation = new CreateGlobalAttributeSchemaMutation(
			"differentName", "description", "deprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, GlobalAttributeUniquenessType.NOT_UNIQUE,
			false, false, false, false, false,
			String.class, null, 2
		);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				previousMutation
			)
		);
	}

	@Test
	void shouldRemovePreviousCreateMutationWithSameName() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		CreateAttributeSchemaMutation previousMutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 2
		);
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
		);
		assertNotNull(result);
		assertTrue(result.discarded());
		assertNull(result.origin());
		assertNotNull(result.current());
	}

	@Test
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		CreateAttributeSchemaMutation previousMutation = new CreateAttributeSchemaMutation(
			"differentName", "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class),
				previousMutation
			)
		);
	}

	@Test
	void shouldRemoveGlobalAttribute() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final GlobalAttributeSchemaContract attributeSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			createExistingGlobalAttributeSchema(),
			GlobalAttributeSchemaContract.class
		);
		assertNull(attributeSchema);
	}

	@Test
	void shouldRemoveEntityAttribute() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final EntityAttributeSchemaContract attributeSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		assertNull(attributeSchema);
	}

	@Test
	void shouldRemoveAttributeInCatalog() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
		assertEquals(0, result.entitySchemaMutations().length);
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertNotNull(newCatalogSchema);
		assertEquals(2, newCatalogSchema.version());
		assertFalse(newCatalogSchema.getAttribute(ATTRIBUTE_NAME).isPresent());
	}

	@Test
	void shouldRemoveAttributeInEntity() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		assertFalse(newEntitySchema.getAttribute(ATTRIBUTE_NAME).isPresent());
	}

	@Test
	void shouldRemoveAttributeInReference() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract newReferenceSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema);
		assertNotNull(newReferenceSchema);
		assertFalse(newReferenceSchema.getAttribute(ATTRIBUTE_NAME).isPresent());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		RemoveAttributeSchemaMutation mutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(empty());
		final EntitySchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertEquals(1, mutatedSchema.version());
	}

}
