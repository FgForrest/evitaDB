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
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAttributeSchemaDefaultValueMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class ModifyAttributeSchemaDefaultValueMutationTest {

	@Test
	void shouldOverrideDefaultValueOfPreviousGlobalAttributeMutationIfNamesMatch() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "oldDefaultValue");
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaDefaultValueMutation.class, result.current()[0]);
		assertEquals(5, ((ModifyAttributeSchemaDefaultValueMutation) result.current()[0]).getDefaultValue());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation("differentName", "oldDeprecationNotice");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	void shouldOverrideDefaultValueOfPreviousMutationIfNamesMatch() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "oldDefaultValue");
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaDefaultValueMutation.class, result.current()[0]);
		assertEquals(5, ((ModifyAttributeSchemaDefaultValueMutation) result.current()[0]).getDefaultValue());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation("differentName", "oldDefaultValue");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateGlobalAttributeSchema() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(5, mutatedSchema.getDefaultValue());
	}

	@Test
	void shouldMutateEntityAttributeSchema() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(), EntityAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(5, mutatedSchema.getDefaultValue());
	}

	@Test
	void shouldMutateCatalogSchema() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
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
		final GlobalAttributeSchemaContract newAttributeSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract newAttributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ReferenceSchemaContract mockedReferenceSchema = createMockedReferenceSchema();
		Mockito.when(mockedReferenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			mockedReferenceSchema
		);
		assertNotNull(mutatedSchema);
		final AttributeSchemaContract newAttributeSchema = mutatedSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
