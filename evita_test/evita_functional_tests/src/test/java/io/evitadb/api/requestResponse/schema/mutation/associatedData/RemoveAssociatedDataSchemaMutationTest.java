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
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link RemoveAssociatedDataSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("RemoveAssociatedDataSchemaMutation")
class RemoveAssociatedDataSchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should remove previous create mutation with same name")
		void shouldRemovePreviousCreateMutationWithSameName() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final CreateAssociatedDataSchemaMutation previousMutation = new CreateAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME, "description", "deprecationNotice", String.class, true, true
			);
			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
			);
			assertNotNull(result);
			assertTrue(result.discarded());
			assertNull(result.origin());
			assertNotNull(result.current());
		}

		@Test
		@DisplayName("should leave mutation intact when removal targets different associated data")
		void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAssociateData() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final CreateAssociatedDataSchemaMutation previousMutation = new CreateAssociatedDataSchemaMutation(
				"differentName", "differentDescription", "deprecationNotice", String.class, true, true
			);
			assertNull(mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), previousMutation
			));
		}
	}

	@Nested
	@DisplayName("Mutate associated data schema")
	class MutateSchema {

		@Test
		@DisplayName("should return null when removing associated data schema")
		void shouldRemoveAssociatedData() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final AssociatedDataSchemaContract associatedSchema = mutation.mutate(createExistingAssociatedDataSchema());
			assertNull(associatedSchema);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should remove associated data from entity schema")
		void shouldRemoveAssociatedDataInEntity() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
				.thenReturn(of(createExistingAssociatedDataSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);
			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			assertFalse(newEntitySchema.getAssociatedData(ASSOCIATED_DATA_NAME).isPresent());
		}

		@Test
		@DisplayName("should return unchanged schema when associated data does not exist")
		void shouldReturnUnchangedSchemaWhenAssociatedDataDoesNotExist() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME))
				.thenReturn(empty());
			final EntitySchemaContract mutatedSchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);
			assertEquals(1, mutatedSchema.version());
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return REMOVE operation")
		void shouldReturnCorrectOperation() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			assertEquals(Operation.REMOVE, mutation.operation());
		}

		@Test
		@DisplayName("should return associated data name as container name")
		void shouldReturnAssociatedDataNameAsContainerName() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.containerName());
		}

		@Test
		@DisplayName("should return associated data name via getName")
		void shouldReturnAssociatedDataNameViaGetName() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			assertEquals(ASSOCIATED_DATA_NAME, mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key with entity type")
		void shouldReturnCollectionConflictKeyWithEntityType() {
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
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
			final RemoveAssociatedDataSchemaMutation mutation = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final String result = mutation.toString();
			assertTrue(result.contains("Remove"));
			assertTrue(result.contains(ASSOCIATED_DATA_NAME));
		}

		@Test
		@DisplayName("should be equal to mutation with same name")
		void shouldBeEqualToMutationWithSameName() {
			final RemoveAssociatedDataSchemaMutation mutation1 = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final RemoveAssociatedDataSchemaMutation mutation2 = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different name")
		void shouldNotBeEqualToMutationWithDifferentName() {
			final RemoveAssociatedDataSchemaMutation mutation1 = new RemoveAssociatedDataSchemaMutation(
				ASSOCIATED_DATA_NAME);
			final RemoveAssociatedDataSchemaMutation mutation2 = new RemoveAssociatedDataSchemaMutation(
				"differentName");
			assertNotEquals(mutation1, mutation2);
		}
	}
}
