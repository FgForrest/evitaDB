/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_TYPE;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RemoveReferenceSchemaMutation} verifying removal of reference schemas,
 * combination with create mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("RemoveReferenceSchemaMutation")
class RemoveReferenceSchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should discard create mutation with matching name")
		void shouldRemovePreviousCreateMutationWithSameName() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
			final CreateReferenceSchemaMutation previousMutation =
				new CreateReferenceSchemaMutation(
					REFERENCE_NAME,
					"description", "deprecationNotice",
					Cardinality.EXACTLY_ONE, "brand", false,
					null, false,
					false, false
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					previousMutation
				);

			assertNotNull(result);
			assertTrue(result.discarded());
			assertNull(result.origin());
			assertNotNull(result.current());
		}

		@Test
		@DisplayName("should not combine when create targets different reference")
		void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentReference() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
			final CreateReferenceSchemaMutation previousMutation =
				new CreateReferenceSchemaMutation(
					"differentName",
					"differentDescription", "deprecationNotice",
					Cardinality.EXACTLY_ONE, "brand", false,
					null, false,
					false, false
				);

			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					previousMutation
				)
			);
		}

		@Test
		@DisplayName("should return null when mutation targets different reference name")
		void shouldReturnNullForMutationWithDifferentName() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
			final LocalEntitySchemaMutation differentNameMutation =
				new ModifyReferenceSchemaDescriptionMutation("differentName", "notice");

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					differentNameMutation
				);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate reference schema")
	class MutateReferenceSchema {

		@Test
		@DisplayName("should return null (removing the reference)")
		void shouldRemoveReference() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final ReferenceSchemaContract result =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNull(result);
		}

		@Test
		@DisplayName("should throw when reference schema is null")
		void shouldThrowWhenReferenceSchemaIsNull() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			assertThrows(
				EvitaInternalError.class,
				() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), null)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should remove reference from entity schema")
		void shouldRemoveReferenceInEntity() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema =
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);

			assertNotNull(newEntitySchema);
			assertEquals(2, newEntitySchema.version());
			assertFalse(newEntitySchema.getReference(REFERENCE_NAME).isPresent());
		}

		@Test
		@DisplayName("should return unchanged schema when reference does not exist")
		void shouldReturnUnchangedSchemaWhenReferenceDoesNotExist() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME)).thenReturn(empty());

			final EntitySchemaContract mutatedSchema =
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);

			assertEquals(1, mutatedSchema.version());
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return REMOVE operation")
		void shouldReturnRemoveOperation() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			assertEquals(Operation.REMOVE, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);
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
			final RemoveReferenceSchemaMutation mutation =
				new RemoveReferenceSchemaMutation(REFERENCE_NAME);

			final String result = mutation.toString();

			assertTrue(result.contains("Remove entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
		}
	}
}
