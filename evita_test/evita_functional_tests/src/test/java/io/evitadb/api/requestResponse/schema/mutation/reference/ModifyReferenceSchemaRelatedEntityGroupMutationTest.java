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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.GROUP_TYPE;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyReferenceSchemaRelatedEntityGroupMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyReferenceSchemaRelatedEntityGroupMutation")
class ModifyReferenceSchemaRelatedEntityGroupMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous related entity group mutation when names match")
		void shouldOverrideRelatedEntityGroupOfPreviousMutationIfNamesMatch() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newRelatedEntityGroup", false
				);
			final ModifyReferenceSchemaRelatedEntityGroupMutation existingMutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "oldRelatedEntityGroup", false
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation
				);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(
				ModifyReferenceSchemaRelatedEntityGroupMutation.class, result.current()[0]
			);
			assertEquals(
				"newRelatedEntityGroup",
				((ModifyReferenceSchemaRelatedEntityGroupMutation) result.current()[0])
					.getReferencedGroupType()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newRelatedEntityGroup", false
				);
			final ModifyReferenceSchemaRelatedEntityGroupMutation existingMutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					"differentName", "oldRelatedEntityGroup", false
				);

			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				)
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newGroup", false
				);
			final LocalEntitySchemaMutation unrelatedMutation =
				new ModifyReferenceSchemaDescriptionMutation(REFERENCE_NAME, "desc");

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					unrelatedMutation
				);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("should set new related entity group type")
		void shouldMutateReferenceSchema() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newRelatedEntityGroup", false
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			assertEquals("newRelatedEntityGroup", mutatedSchema.getReferencedGroupType());
			assertFalse(mutatedSchema.isReferencedGroupTypeManaged());
		}

		@Test
		@DisplayName("should return same schema when group type and managed flag are unchanged")
		void shouldReturnSameSchemaWhenRelatedEntityGroupUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME,
					existingSchema.getReferencedGroupType(),
					existingSchema.isReferencedGroupTypeManaged()
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), existingSchema);

			assertSame(existingSchema, mutatedSchema);
		}

		@Test
		@DisplayName("should reject reflected reference schema")
		void shouldRejectReflectedReferenceSchema() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newGroup", false
				);
			final ReflectedReferenceSchema reflectedSchema =
				Mockito.mock(ReflectedReferenceSchema.class);

			assertThrows(
				EvitaInternalError.class,
				() -> mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update related entity group type in entity schema")
		void shouldMutateEntitySchema() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newRelatedEntityGroup", false
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertEquals("newRelatedEntityGroup", newReferenceSchema.getReferencedGroupType());
			assertFalse(newReferenceSchema.isReferencedGroupTypeManaged());
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "newRelatedEntityGroup", false
				);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class)
				)
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "groupType", false
				);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "groupType", false
				);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"testEntity", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);

			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final ModifyReferenceSchemaRelatedEntityGroupMutation mutation =
				new ModifyReferenceSchemaRelatedEntityGroupMutation(
					REFERENCE_NAME, "tagGroup", true
				);

			final String result = mutation.toString();

			assertTrue(result.contains("Modify entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("tagGroup"));
			assertTrue(result.contains("relatesToGroup"));
		}
	}
}
