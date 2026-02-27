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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.ASSOCIATED_DATA_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.createExistingAssociatedDataSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link ModifyAssociatedDataSchemaDescriptionMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ModifyAssociatedDataSchemaDescriptionMutation")
class ModifyAssociatedDataSchemaDescriptionMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should override description of previous mutation when names match")
		void shouldOverrideDescriptionOfPreviousMutationIfNamesMatch() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			final ModifyAssociatedDataSchemaDescriptionMutation existingMutation =
				new ModifyAssociatedDataSchemaDescriptionMutation(ASSOCIATED_DATA_NAME, "oldDescription");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(
				of(createExistingAssociatedDataSchema()));
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation
			);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(ModifyAssociatedDataSchemaDescriptionMutation.class, result.current()[0]);
			assertEquals(
				"newDescription",
				((ModifyAssociatedDataSchemaDescriptionMutation) result.current()[0]).getDescription()
			);
		}

		@Test
		@DisplayName("should leave both mutations when names do not match")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			final ModifyAssociatedDataSchemaDescriptionMutation existingMutation =
				new ModifyAssociatedDataSchemaDescriptionMutation("differentName", "oldDescription");
			assertNull(mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			));
		}
	}

	@Nested
	@DisplayName("Mutate associated data schema")
	class MutateSchema {

		@Test
		@DisplayName("should set new description on associated data schema")
		void shouldMutateAssociatedDataSchema() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			final AssociatedDataSchemaContract mutatedSchema = mutation.mutate(createExistingAssociatedDataSchema());
			assertNotNull(mutatedSchema);
			assertEquals("newDescription", mutatedSchema.getDescription());
		}

		@Test
		@DisplayName("should return schema with same values when description is unchanged")
		void shouldReturnSchemaWithSameValuesWhenDescriptionIsUnchanged() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "oldDescription"
			);
			final AssociatedDataSchemaContract mutatedSchema = mutation.mutate(createExistingAssociatedDataSchema());
			assertNotNull(mutatedSchema);
			assertEquals("oldDescription", mutatedSchema.getDescription());
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update description in entity schema")
		void shouldMutateEntitySchema() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(
				of(createExistingAssociatedDataSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);
			assertEquals(2, newEntitySchema.version());
			final AssociatedDataSchemaContract newSchema = newEntitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)
				.orElseThrow();
			assertEquals("newDescription", newSchema.getDescription());
		}

		@Test
		@DisplayName("should throw exception when associated data does not exist in entity schema")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAssociatedData() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(
					Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class))
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnCorrectOperation() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return associated data name as container name")
		void shouldReturnAssociatedDataNameAsContainerName() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.containerName());
		}

		@Test
		@DisplayName("should return associated data name via getName")
		void shouldReturnAssociatedDataNameViaGetName() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key with entity type")
		void shouldReturnCollectionConflictKeyWithEntityType() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"product", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
			assertEquals("product", ((CollectionConflictKey) keys.get(0)).entityType());
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToStringOutput() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "newDescription"
			);
			final String result = mutation.toString();
			assertTrue(result.contains(ASSOCIATED_DATA_NAME));
			assertTrue(result.contains("newDescription"));
		}

		@Test
		@DisplayName("should be equal to mutation with same parameters")
		void shouldBeEqualToMutationWithSameParameters() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation1 = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			final ModifyAssociatedDataSchemaDescriptionMutation mutation2 = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description"
			);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different description")
		void shouldNotBeEqualToMutationWithDifferentParameters() {
			final ModifyAssociatedDataSchemaDescriptionMutation mutation1 = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description1"
			);
			final ModifyAssociatedDataSchemaDescriptionMutation mutation2 = new ModifyAssociatedDataSchemaDescriptionMutation(
				ASSOCIATED_DATA_NAME, "description2"
			);
			assertNotEquals(mutation1, mutation2);
		}
	}
}
