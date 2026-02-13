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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAttributeSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ModifyAttributeSchemaNameMutation")
class ModifyAttributeSchemaNameMutationTest {

	@Test
	@DisplayName("Should override name of previous global attribute mutation when names match")
	void shouldOverrideNamePreviousGlobalAttributeMutationIfNamesMatch() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final ModifyAttributeSchemaNameMutation existingMutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "differentName");
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaNameMutation.class, result.current()[0]);
		assertEquals("newName", ((ModifyAttributeSchemaNameMutation) result.current()[0]).getNewName());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new global attribute mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newDescription"
		);
		final ModifyAttributeSchemaNameMutation existingMutation = new ModifyAttributeSchemaNameMutation(
			"differentName", "oldDeprecationNotice");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override name of previous mutation when names match")
	void shouldOverrideNameOfPreviousMutationIfNamesMatch() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final ModifyAttributeSchemaNameMutation existingMutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "differentName");
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaNameMutation.class, result.current()[0]);
		assertEquals("name", ((ModifyAttributeSchemaNameMutation) result.current()[0]).getName());
		assertEquals("newName", ((ModifyAttributeSchemaNameMutation) result.current()[0]).getNewName());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final ModifyAttributeSchemaNameMutation existingMutation = new ModifyAttributeSchemaNameMutation(
			"differentName", "oldName");
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
				existingMutation
			));
	}

	@Test
	@DisplayName("Should mutate global attribute schema")
	void shouldMutateGlobalAttributeSchema() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(),
			GlobalAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertEquals("newName", mutatedSchema.getName());
	}

	@Test
	@DisplayName("Should mutate entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertEquals("newName", mutatedSchema.getName());
	}

	@Test
	@DisplayName("Should mutate catalog schema")
	void shouldMutateCatalogSchema() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas mutationResult = mutation.mutate(
			catalogSchema
		);
		assertEquals(0, mutationResult.entitySchemaMutations().length);
		final CatalogSchemaContract newCatalogSchema = mutationResult.updatedCatalogSchema();
		assertEquals(2, newCatalogSchema.version());
		assertTrue(newCatalogSchema.getAttribute("newName").isPresent());
	}

	@Test
	@DisplayName("Should mutate entity schema")
	void shouldMutateEntitySchema() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		assertTrue(newEntitySchema.getAttribute("newName").isPresent());
	}

	@Test
	@DisplayName("Should mutate reference schema")
	void shouldMutateReferenceSchema() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			referenceSchema
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.getAttribute("newName").isPresent());
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with non-existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		final ModifyAttributeSchemaNameMutation mutation = new ModifyAttributeSchemaNameMutation(
			ATTRIBUTE_NAME, "newName"
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
