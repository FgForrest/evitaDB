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
 * Test for {@link SetAttributeSchemaRepresentativeMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class SetAttributeSchemaRepresentativeMutationTest {

	@Test
	void shouldOverrideRepresentativeOfPreviousGlobalAttributeMutationIfNamesMatch() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		SetAttributeSchemaRepresentativeMutation existingMutation = new SetAttributeSchemaRepresentativeMutation(ATTRIBUTE_NAME, false);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaRepresentativeMutation.class, result.current()[0]);
		assertTrue(((SetAttributeSchemaRepresentativeMutation) result.current()[0]).isRepresentative());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		SetAttributeSchemaRepresentativeMutation existingMutation = new SetAttributeSchemaRepresentativeMutation("differentName", false);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	void shouldOverrideRepresentativeOfPreviousMutationIfNamesMatch() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		SetAttributeSchemaRepresentativeMutation existingMutation = new SetAttributeSchemaRepresentativeMutation(ATTRIBUTE_NAME, false);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaRepresentativeMutation.class, result.current()[0]);
		assertTrue(((SetAttributeSchemaRepresentativeMutation) result.current()[0]).isRepresentative());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		SetAttributeSchemaRepresentativeMutation existingMutation = new SetAttributeSchemaRepresentativeMutation("differentName", false);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateGlobalAttributeSchema() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isRepresentative());
	}

	@Test
	void shouldMutateEntityAttributeSchema() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(), EntityAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isRepresentative());
	}

	@Test
	void shouldMutateCatalogSchema() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
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
		final GlobalAttributeSchemaContract newSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newSchema.isRepresentative());
	}

	@Test
	void shouldMutateEntitySchema() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final EntityAttributeSchemaContract newAttributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newAttributeSchema.isRepresentative());
	}

	@Test
	void shouldMakeReferenceSchemaAttributeRepresentative() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.isIndexed()).thenReturn(true);
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract newReferenceSchema = mutation.mutate(
			entitySchema,
			referenceSchema
		);
		final AttributeSchemaContract newAttributeSchema = newReferenceSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newAttributeSchema.isRepresentative());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		SetAttributeSchemaRepresentativeMutation mutation = new SetAttributeSchemaRepresentativeMutation(
			ATTRIBUTE_NAME, true
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
