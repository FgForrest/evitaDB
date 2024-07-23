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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.ATTRIBUTE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingEntityAttributeSchema;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.createExistingGlobalAttributeSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link SetAttributeSchemaGloballyUniqueMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class SetAttributeSchemaGloballyUniqueMutationTest {

	@Test
	void shouldOverrideGloballyUniqueOfPreviousGlobalAttributeMutationIfNamesMatch() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		SetAttributeSchemaGloballyUniqueMutation existingMutation = new SetAttributeSchemaGloballyUniqueMutation(ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaGloballyUniqueMutation.class, result.current()[0]);
		assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, ((SetAttributeSchemaGloballyUniqueMutation) result.current()[0]).getUniqueGlobally());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		SetAttributeSchemaGloballyUniqueMutation existingMutation = new SetAttributeSchemaGloballyUniqueMutation("differentName", GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	void shouldOverrideGloballyUniqueOfPreviousMutationIfNamesMatch() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		SetAttributeSchemaGloballyUniqueMutation existingMutation = new SetAttributeSchemaGloballyUniqueMutation(ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaGloballyUniqueMutation.class, result.current()[0]);
		assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, ((SetAttributeSchemaGloballyUniqueMutation) result.current()[0]).getUniqueGlobally());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		SetAttributeSchemaGloballyUniqueMutation existingMutation = new SetAttributeSchemaGloballyUniqueMutation("differentName", GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateGlobalAttributeSchema() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isUniqueGlobally());
		assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, mutatedSchema.getGlobalUniquenessType());
	}

	@Test
	void shouldMutateCatalogSchema() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
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
		final GlobalAttributeSchemaContract mutatedSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(mutatedSchema.isUniqueGlobally());
		assertEquals(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG, mutatedSchema.getGlobalUniquenessType());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		SetAttributeSchemaGloballyUniqueMutation mutation = new SetAttributeSchemaGloballyUniqueMutation(
			ATTRIBUTE_NAME, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaProvider.class));
			}
		);
	}

}
