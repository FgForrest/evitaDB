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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.ATTRIBUTE_COMPOUND_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createExistingAttributeCompoundSchema;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createMockedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link SetSortableAttributeCompoundIndexedMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SetSortableAttributeCompoundIndexedMutationTest {

	@Test
	void shouldOverrideScopesOfPreviousMutationIfNamesMatch() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		SetSortableAttributeCompoundIndexedMutation existingMutation = new SetSortableAttributeCompoundIndexedMutation(ATTRIBUTE_COMPOUND_NAME, Scope.DEFAULT_SCOPES);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(SetSortableAttributeCompoundIndexedMutation.class, result.current()[0]);
		assertArrayEquals(Scope.values(), ((SetSortableAttributeCompoundIndexedMutation) result.current()[0]).getIndexedInScopes());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		SetSortableAttributeCompoundIndexedMutation existingMutation = new SetSortableAttributeCompoundIndexedMutation("differentName", Scope.DEFAULT_SCOPES);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateEntityAttributeSchema() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			createMockedReferenceSchema(),
			createExistingAttributeCompoundSchema()
		);
		assertNotNull(mutatedSchema);
		assertArrayEquals(Scope.values(), mutatedSchema.getIndexedInScopes().toArray(Scope[]::new));
	}

	@Test
	void shouldMutateEntitySchema() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final SortableAttributeCompoundSchemaContract newAttributeSchema = newEntitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
		assertArrayEquals(Scope.values(), newAttributeSchema.getIndexedInScopes().toArray(Scope[]::new));
	}

	@Test
	void shouldMutateReferenceSchema() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			referenceSchema
		);
		assertNotNull(mutatedSchema);
		final SortableAttributeCompoundSchemaContract newAttributeSchema = mutatedSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
		assertArrayEquals(Scope.values(), newAttributeSchema.getIndexedInScopes().toArray(Scope[]::new));
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

	@Test
	void shouldThrowExceptionWhenMutatingReferenceSchemaWithNonExistingAttribute() {
		SetSortableAttributeCompoundIndexedMutation mutation = new SetSortableAttributeCompoundIndexedMutation(
			ATTRIBUTE_COMPOUND_NAME, Scope.values()
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), Mockito.mock(ReferenceSchemaContract.class));
			}
		);
	}

}
