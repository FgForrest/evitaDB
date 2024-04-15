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
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAttributeSchemaTypeMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class ModifyAttributeSchemaTypeMutationTest {

	@Test
	void shouldOverrideTypeOfPreviousGlobalAttributeMutationIfNamesMatch() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, DateTimeRange.class, 2);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaTypeMutation.class, result.current()[0]);
		assertEquals(String.class, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getType());
		assertEquals(0, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getIndexedDecimalPlaces());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation("differentName", DateTimeRange.class, 2);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}
	
	@Test
	void shouldOverrideTypeOfPreviousMutationIfNamesMatch() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, DateTimeRange.class, 2);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaTypeMutation.class, result.current()[0]);
		assertEquals(String.class, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getType());
		assertEquals(0, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getIndexedDecimalPlaces());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation("differentName", String.class, 0);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateAttributeSchema() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		final AttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingAttributeSchema(), AttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(Integer.class, mutatedSchema.getType());
	}

	@Test
	void shouldMutateEntityAttributeSchema() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(), EntityAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(String.class, mutatedSchema.getType());
		assertEquals(0, mutatedSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldMutateCatalogSchema() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}