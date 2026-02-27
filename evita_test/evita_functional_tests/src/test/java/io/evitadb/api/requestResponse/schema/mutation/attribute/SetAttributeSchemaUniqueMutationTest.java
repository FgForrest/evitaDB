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
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link SetAttributeSchemaUniqueMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("SetAttributeSchemaUniqueMutation")
class SetAttributeSchemaUniqueMutationTest {

	@Test
	@DisplayName("Should override unique of previous global attribute mutation when names match")
	void shouldOverrideUniqueOfPreviousGlobalAttributeMutationIfNamesMatch() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final SetAttributeSchemaUniqueMutation existingMutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE)
			}
		);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaUniqueMutation.class, result.current()[0]);
		assertEquals(
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
			((SetAttributeSchemaUniqueMutation) result.current()[0]).getUnique()
		);
		assertArrayEquals(
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			((SetAttributeSchemaUniqueMutation) result.current()[0]).getUniqueInScopes()
		);
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new global attribute mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final SetAttributeSchemaUniqueMutation existingMutation = new SetAttributeSchemaUniqueMutation(
			"differentName", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override unique of previous mutation when names match")
	void shouldOverrideUniqueOfPreviousMutationIfNamesMatch() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final SetAttributeSchemaUniqueMutation existingMutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE)
			}
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetAttributeSchemaUniqueMutation.class, result.current()[0]);
		assertEquals(
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION,
			((SetAttributeSchemaUniqueMutation) result.current()[0]).getUnique()
		);
		assertArrayEquals(
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			((SetAttributeSchemaUniqueMutation) result.current()[0]).getUniqueInScopes()
		);
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final SetAttributeSchemaUniqueMutation existingMutation = new SetAttributeSchemaUniqueMutation(
			"differentName", AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
				existingMutation
			));
	}

	@Test
	@DisplayName("Should mutate global attribute schema")
	void shouldMutateGlobalAttributeSchema() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(),
			GlobalAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isUnique());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, mutatedSchema.getUniquenessType());
		assertEquals(
			Map.of(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			mutatedSchema.getUniquenessTypeInScopes()
		);
	}

	@Test
	@DisplayName("Should mutate entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.isUnique());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, mutatedSchema.getUniquenessType());
		assertEquals(
			Map.of(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			mutatedSchema.getUniquenessTypeInScopes()
		);
	}

	@Test
	@DisplayName("Should mutate catalog schema")
	void shouldMutateCatalogSchema() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
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
		assertTrue(mutatedSchema.isUnique());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, mutatedSchema.getUniquenessType());
		assertEquals(
			Map.of(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			mutatedSchema.getUniquenessTypeInScopes()
		);
	}

	@Test
	@DisplayName("Should mutate entity schema")
	void shouldMutateEntitySchema() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract mutatedSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(mutatedSchema.isUnique());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, mutatedSchema.getUniquenessType());
		assertEquals(
			Map.of(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			mutatedSchema.getUniquenessTypeInScopes()
		);
	}

	@Test
	@DisplayName("Should mutate reference schema")
	void shouldMutateReferenceSchema() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final EntitySchemaContract mockedEntitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(mockedEntitySchema.getName()).thenReturn("mockedEntitySchema");
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.isIndexed()).thenReturn(true);
		Mockito.when(referenceSchema.isIndexedInScope(Scope.LIVE)).thenReturn(true);
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			mockedEntitySchema,
			referenceSchema
		);
		assertNotNull(mutatedSchema);
		final AttributeSchemaContract newAttributeSchema = mutatedSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertTrue(newAttributeSchema.isUnique());
		assertEquals(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, newAttributeSchema.getUniquenessType());
		assertEquals(
			Map.of(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			newAttributeSchema.getUniquenessTypeInScopes()
		);
	}

	@Test
	@DisplayName("Should fail to mutate reference schema when not indexed")
	void shouldFailMutateReferenceSchemaIfNotIndexed() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					referenceSchema
				);
			}
		);
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with non-existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		final SetAttributeSchemaUniqueMutation mutation = new SetAttributeSchemaUniqueMutation(
			ATTRIBUTE_NAME,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

}
