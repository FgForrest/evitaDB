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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.ATTRIBUTE_COMPOUND_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createExistingAttributeCompoundSchema;
import static io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationTest.createMockedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifySortableAttributeCompoundSchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifySortableAttributeCompoundSchemaNameMutation")
class ModifySortableAttributeCompoundSchemaNameMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("Should override name of previous mutation when names match")
		void shouldOverrideNameOfPreviousMutationIfNamesMatch() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			final ModifySortableAttributeCompoundSchemaNameMutation existingMutation = new ModifySortableAttributeCompoundSchemaNameMutation(ATTRIBUTE_COMPOUND_NAME, "oldName");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(ModifySortableAttributeCompoundSchemaNameMutation.class, result.current()[0]);
			assertEquals("name", ((ModifySortableAttributeCompoundSchemaNameMutation) result.current()[0]).getName());
			assertEquals("newName", ((ModifySortableAttributeCompoundSchemaNameMutation) result.current()[0]).getNewName());
		}

		@Test
		@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			final ModifySortableAttributeCompoundSchemaNameMutation existingMutation = new ModifySortableAttributeCompoundSchemaNameMutation("differentName", "oldName");
			assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
		}
	}

	@Nested
	@DisplayName("Mutate compound schema")
	class MutateSchema {

		@Test
		@DisplayName("Should rename entity-level compound schema")
		void shouldMutateEntityAttributeSchema() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				null,
				createExistingAttributeCompoundSchema()
			);
			assertNotNull(mutatedSchema);
			assertEquals("newName", mutatedSchema.getName());
		}

		@Test
		@DisplayName("Should rename reference-level compound schema")
		void shouldMutateReferenceAttributeSchema() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				createMockedReferenceSchema(),
				createExistingAttributeCompoundSchema()
			);
			assertNotNull(mutatedSchema);
			assertInstanceOf(SortableAttributeCompoundSchema.class, mutatedSchema);
			assertEquals("newName", mutatedSchema.getName());
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("Should rename compound in entity schema")
		void shouldMutateEntitySchema() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
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
		@DisplayName("Should throw exception when compound doesn't exist in entity schema")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class))
			);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("Should rename compound in reference schema")
		void shouldMutateReferenceSchema() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
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
		@DisplayName("Should produce SortableAttributeCompoundSchema type for reference-level mutation")
		void shouldProduceCorrectSchemaTypeForReferenceLevel() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
			final ReferenceSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				referenceSchema
			);
			assertNotNull(mutatedSchema);
			final SortableAttributeCompoundSchemaContract newCompoundSchema = mutatedSchema.getSortableAttributeCompound("newName").orElseThrow();
			assertInstanceOf(SortableAttributeCompoundSchema.class, newCompoundSchema);
		}

		@Test
		@DisplayName("Should throw exception when compound doesn't exist in reference schema")
		void shouldThrowExceptionWhenMutatingReferenceSchemaWithNonExistingAttribute() {
			final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
				ATTRIBUTE_COMPOUND_NAME, "newName"
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), Mockito.mock(ReferenceSchemaContract.class))
			);
		}
	}

	@Test
	@DisplayName("Should return UPSERT operation")
	void shouldReturnUpsertOperation() {
		final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		assertEquals(Operation.UPSERT, mutation.operation());
	}

	@Test
	@DisplayName("Should provide human-readable toString")
	void shouldHaveToString() {
		final ModifySortableAttributeCompoundSchemaNameMutation mutation = new ModifySortableAttributeCompoundSchemaNameMutation(
			ATTRIBUTE_COMPOUND_NAME, "newName"
		);
		final String result = mutation.toString();
		assertTrue(result.contains(ATTRIBUTE_COMPOUND_NAME));
		assertTrue(result.contains("newName"));
	}

}
