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
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.ATTRIBUTE_COMPOUND_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createExistingAttributeCompoundSchema;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createMockedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifySortableAttributeCompoundSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ModifySortableAttributeCompoundSchemaNameMutationTest {

	@Test
	void shouldOverrideNameOfPreviousMutationIfNamesMatch() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		ModifySortableAttributeCompoundSchemaNameMutation existingMutation = new ModifySortableAttributeCompoundSchemaNameMutation(ATTRIBUTE_COMPOUND_NAME, "oldName");
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		final MutationCombinationResult<EntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifySortableAttributeCompoundSchemaNameMutation.class, result.current()[0]);
		assertEquals("name", ((ModifySortableAttributeCompoundSchemaNameMutation) result.current()[0]).getName());
		assertEquals("newName", ((ModifySortableAttributeCompoundSchemaNameMutation) result.current()[0]).getNewName());
	}

	@Test
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		ModifySortableAttributeCompoundSchemaNameMutation existingMutation = new ModifySortableAttributeCompoundSchemaNameMutation("differentName", "oldName");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	void shouldMutateEntityAttributeSchema() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			createMockedReferenceSchema(),
			createExistingAttributeCompoundSchema()
		);
		assertNotNull(mutatedSchema);
		assertEquals("newName", mutatedSchema.getName());
	}

	@Test
	void shouldMutateEntitySchema() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		assertTrue(newEntitySchema.getSortableAttributeCompound("newName").isPresent());
	}

	@Test
	void shouldMutateReferenceSchema() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			referenceSchema
		);
		assertNotNull(mutatedSchema);
		assertTrue(mutatedSchema.getSortableAttributeCompound("newName").isPresent());
	}

	@Test
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
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
		ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), Mockito.mock(ReferenceSchemaContract.class));
			}
		);
	}

}
