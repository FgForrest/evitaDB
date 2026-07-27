/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.ASSOCIATED_DATA_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutationTest.createExistingAssociatedDataSchema;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test for {@link SetAssociatedDataSchemaConflictResolutionOverrideMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SetAssociatedDataSchemaConflictResolutionOverrideMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
class SetAssociatedDataSchemaConflictResolutionOverrideMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should override previous mutation with the newer one when names match")
		void shouldOverridePreviousMutationWhenNamesMatch() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation existingMutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.ENTITY
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(SetAssociatedDataSchemaConflictResolutionOverrideMutation.class, result.current()[0]);
			assertEquals(
				ConflictResolutionOverride.GRANULAR,
				((SetAssociatedDataSchemaConflictResolutionOverrideMutation) result.current()[0])
					.getConflictResolutionOverride()
			);
		}

		@Test
		@DisplayName("should leave both mutations when names do not match")
		void shouldLeaveBothMutationsWhenNamesDoNotMatch() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation existingMutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					"differentName", ConflictResolutionOverride.ENTITY
				);

			assertNull(mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			));
		}

		@Test
		@DisplayName("should leave both mutations when the existing mutation is of an unrelated type")
		void shouldLeaveBothMutationsWhenExistingMutationIsUnrelatedType() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetAssociatedDataSchemaNullableMutation existingMutation =
				new SetAssociatedDataSchemaNullableMutation(ASSOCIATED_DATA_NAME, true);

			assertNull(mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation
			));
		}
	}

	@Nested
	@DisplayName("Mutate associated data schema")
	class MutateSchema {

		@Test
		@DisplayName("should carry the new override into the mutated associated data schema")
		void shouldMutateAssociatedDataSchema() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);

			final AssociatedDataSchemaContract mutatedSchema = mutation.mutate(createExistingAssociatedDataSchema());

			assertNotNull(mutatedSchema);
			assertEquals(ConflictResolutionOverride.GRANULAR, mutatedSchema.getConflictResolutionOverride());
		}

		@Test
		@DisplayName("should throw exception when associated data schema is null")
		void shouldThrowExceptionWhenAssociatedDataSchemaIsNull() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate((AssociatedDataSchemaContract) null)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update the override in entity schema")
		void shouldMutateEntitySchema() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
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
			assertEquals(ConflictResolutionOverride.GRANULAR, newSchema.getConflictResolutionOverride());
		}

		@Test
		@DisplayName("should return the same entity schema when the override is unchanged")
		void shouldReturnSameEntitySchemaWhenOverrideUnchanged() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.INHERITED
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			// the existing associated data already carries the INHERITED override
			Mockito.when(entitySchema.getAssociatedData(ASSOCIATED_DATA_NAME)).thenReturn(
				of(createExistingAssociatedDataSchema()));

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertSame(entitySchema, newEntitySchema);
		}

		@Test
		@DisplayName("should throw exception when associated data does not exist in entity schema")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAssociatedData() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
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
		@DisplayName("should not be equal to a mutation with a different override value")
		void shouldNotBeEqualToMutationWithDifferentOverride() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation1 =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation2 =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.ENTITY
				);

			assertNotEquals(mutation1, mutation2);
		}

		@Test
		@DisplayName("should not be equal to a mutation with a different associated data name")
		void shouldNotBeEqualToMutationWithDifferentName() {
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation1 =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					ASSOCIATED_DATA_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetAssociatedDataSchemaConflictResolutionOverrideMutation mutation2 =
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					"differentName", ConflictResolutionOverride.GRANULAR
				);

			assertNotEquals(mutation1, mutation2);
		}
	}
}
