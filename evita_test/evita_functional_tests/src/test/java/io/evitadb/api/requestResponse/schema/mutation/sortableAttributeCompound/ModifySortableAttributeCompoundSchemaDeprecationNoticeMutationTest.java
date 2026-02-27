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
 * Test verifies {@link ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation")
class ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("Should override deprecation notice of previous mutation when names match")
		void shouldOverrideDeprecationNoticeOfPreviousMutationIfNamesMatch() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation existingMutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(ATTRIBUTE_COMPOUND_NAME, "oldDeprecationNotice");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, result.current()[0]);
			assertEquals("newDeprecationNotice", ((ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation) result.current()[0]).getDeprecationNotice());
		}

		@Test
		@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation existingMutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation("differentName", "oldDeprecationNotice");
			assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
		}
	}

	@Nested
	@DisplayName("Mutate compound schema")
	class MutateSchema {

		@Test
		@DisplayName("Should modify deprecation notice on entity-level compound schema")
		void shouldMutateEntityAttributeSchema() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				null,
				createExistingAttributeCompoundSchema()
			);
			assertNotNull(mutatedSchema);
			assertEquals("newDeprecationNotice", mutatedSchema.getDeprecationNotice());
		}

		@Test
		@DisplayName("Should modify deprecation notice on reference-level compound schema")
		void shouldMutateReferenceAttributeSchema() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final SortableAttributeCompoundSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				createMockedReferenceSchema(),
				createExistingAttributeCompoundSchema()
			);
			assertNotNull(mutatedSchema);
			assertInstanceOf(SortableAttributeCompoundSchema.class, mutatedSchema);
			assertEquals("newDeprecationNotice", mutatedSchema.getDeprecationNotice());
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("Should modify deprecation notice in entity schema")
		void shouldMutateEntitySchema() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
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
			assertEquals("newDeprecationNotice", newAttributeSchema.getDeprecationNotice());
		}

		@Test
		@DisplayName("Should throw exception when compound doesn't exist in entity schema")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
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
		@DisplayName("Should modify deprecation notice in reference schema")
		void shouldMutateReferenceSchema() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
			final ReferenceSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				referenceSchema
			);
			assertNotNull(mutatedSchema);
			final SortableAttributeCompoundSchemaContract newAttributeSchema = mutatedSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
			assertEquals("newDeprecationNotice", newAttributeSchema.getDeprecationNotice());
		}

		@Test
		@DisplayName("Should produce SortableAttributeCompoundSchema type for reference-level mutation")
		void shouldProduceCorrectSchemaTypeForReferenceLevel() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
			);
			final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
			Mockito.when(referenceSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME)).thenReturn(of(createExistingAttributeCompoundSchema()));
			final ReferenceSchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class),
				referenceSchema
			);
			assertNotNull(mutatedSchema);
			final SortableAttributeCompoundSchemaContract newCompoundSchema = mutatedSchema.getSortableAttributeCompound(ATTRIBUTE_COMPOUND_NAME).orElseThrow();
			assertInstanceOf(SortableAttributeCompoundSchema.class, newCompoundSchema);
		}

		@Test
		@DisplayName("Should throw exception when compound doesn't exist in reference schema")
		void shouldThrowExceptionWhenMutatingReferenceSchemaWithNonExistingAttribute() {
			final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
				ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
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
		final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
			ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
		);
		assertEquals(Operation.UPSERT, mutation.operation());
	}

	@Test
	@DisplayName("Should provide human-readable toString")
	void shouldHaveToString() {
		final ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation mutation = new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(
			ATTRIBUTE_COMPOUND_NAME, "newDeprecationNotice"
		);
		final String result = mutation.toString();
		assertTrue(result.contains(ATTRIBUTE_COMPOUND_NAME));
		assertTrue(result.contains("newDeprecationNotice"));
	}

}
