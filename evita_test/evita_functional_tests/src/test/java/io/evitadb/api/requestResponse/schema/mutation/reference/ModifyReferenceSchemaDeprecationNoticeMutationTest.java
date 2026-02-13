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
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyReferenceSchemaDeprecationNoticeMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyReferenceSchemaDeprecationNoticeMutation")
class ModifyReferenceSchemaDeprecationNoticeMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous deprecation notice mutation when names match")
		void shouldOverrideDeprecationNoticeOfPreviousMutationIfNamesMatch() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "newDeprecationNotice"
				);
			final ModifyReferenceSchemaDeprecationNoticeMutation existingMutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "oldDeprecationNotice"
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
				ModifyReferenceSchemaDeprecationNoticeMutation.class, result.current()[0]
			);
			assertEquals(
				"newDeprecationNotice",
				((ModifyReferenceSchemaDeprecationNoticeMutation) result.current()[0])
					.getDeprecationNotice()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "newDeprecationNotice"
				);
			final ModifyReferenceSchemaDeprecationNoticeMutation existingMutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					"differentName", "oldDeprecationNotice"
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
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(REFERENCE_NAME, "notice");
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
		@DisplayName("should set new deprecation notice")
		void shouldMutateReferenceSchema() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "newDeprecationNotice"
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			assertEquals("newDeprecationNotice", mutatedSchema.getDeprecationNotice());
		}

		@Test
		@DisplayName("should return same schema when deprecation notice is unchanged")
		void shouldReturnSameSchemaWhenDeprecationNoticeUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, existingSchema.getDeprecationNotice()
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), existingSchema);

			assertSame(existingSchema, mutatedSchema);
		}

		@Test
		@DisplayName("should set deprecation notice to null")
		void shouldSetDeprecationNoticeToNull() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(REFERENCE_NAME, null);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			assertNull(mutatedSchema.getDeprecationNotice());
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update deprecation notice in entity schema")
		void shouldMutateEntitySchema() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "newDeprecationNotice"
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
			assertEquals("newDeprecationNotice", newReferenceSchema.getDeprecationNotice());
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "newDeprecationNotice"
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
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(REFERENCE_NAME, "notice");

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(REFERENCE_NAME, "notice");
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"testEntity", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);

			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should have unique serialVersionUID distinct from ModifyReferenceSchemaDescriptionMutation")
		void shouldHaveUniqueSerialVersionUID() throws NoSuchFieldException, IllegalAccessException {
			final Field deprecationField =
				ModifyReferenceSchemaDeprecationNoticeMutation.class.getDeclaredField("serialVersionUID");
			deprecationField.setAccessible(true);
			final long deprecationUID = (long) deprecationField.get(null);

			final Field descriptionField =
				ModifyReferenceSchemaDescriptionMutation.class.getDeclaredField("serialVersionUID");
			descriptionField.setAccessible(true);
			final long descriptionUID = (long) descriptionField.get(null);

			assertNotEquals(
				descriptionUID, deprecationUID,
				"ModifyReferenceSchemaDeprecationNoticeMutation and ModifyReferenceSchemaDescriptionMutation " +
					"must have different serialVersionUID values"
			);
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final ModifyReferenceSchemaDeprecationNoticeMutation mutation =
				new ModifyReferenceSchemaDeprecationNoticeMutation(
					REFERENCE_NAME, "test deprecation"
				);

			final String result = mutation.toString();

			assertTrue(result.contains("Modify entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("deprecationNotice"));
			assertTrue(result.contains("test deprecation"));
		}
	}
}
