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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReflectedReferenceSchema;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SetReferenceSchemaIndexedMutation} verifying indexed flag mutations,
 * combination with same-type mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetReferenceSchemaIndexedMutation")
class SetReferenceSchemaIndexedMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should merge indexed scopes when names match")
		void shouldOverrideIndexedFlagOfPreviousMutationIfNamesMatch() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.NONE
						),
						new ScopedReferenceIndexType(
							Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
						)
					}
				);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.FOR_FILTERING
						)
					}
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
			assertInstanceOf(SetReferenceSchemaIndexedMutation.class, result.current()[0]);
			assertArrayEquals(
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.NONE),
					new ScopedReferenceIndexType(
						Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING
					)
				},
				((SetReferenceSchemaIndexedMutation) result.current()[0]).getIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should not throw NPE when this.indexedInScopes is null but existing is non-null")
		void shouldNotThrowNpeWhenThisIndexedInScopesIsNullButExistingIsNonNull() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME, (ScopedReferenceIndexType[]) null
				);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					new ScopedReferenceIndexType[]{
						new ScopedReferenceIndexType(
							Scope.LIVE, ReferenceIndexType.FOR_FILTERING
						)
					}
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(SetReferenceSchemaIndexedMutation.class, result.current()[0]);
			// null (inherited) takes precedence as the latest mutation
			assertNull(
				((SetReferenceSchemaIndexedMutation) result.current()[0]).getIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaIndexedMutation existingMutation =
				new SetReferenceSchemaIndexedMutation("differentName", true);

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
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
			final LocalEntitySchemaMutation unrelatedMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

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
		@DisplayName("should set indexed scopes on reference")
		void shouldMutateReferenceSchema() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class),
					createExistingReferenceSchema(false)
				);

			assertNotNull(mutatedSchema);
			assertArrayEquals(
				Scope.DEFAULT_SCOPES,
				mutatedSchema.getIndexedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should return same schema when indexed scopes are unchanged")
		void shouldReturnSameSchemaWhenIndexedScopesUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema(true);
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(
					REFERENCE_NAME,
					existingSchema.getReferenceIndexTypeInScopes()
						.entrySet()
						.stream()
						.map(
							e -> new ScopedReferenceIndexType(e.getKey(), e.getValue())
						)
						.toArray(ScopedReferenceIndexType[]::new)
				);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(Mockito.mock(EntitySchemaContract.class), existingSchema);

			assertSame(existingSchema, mutatedSchema);
		}

		@Test
		@DisplayName("should handle reflected reference schema")
		void shouldHandleReflectedReferenceSchema() {
			final ReflectedReferenceSchema reflectedSchema =
				createExistingReflectedReferenceSchema();
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), reflectedSchema
				);

			assertNotNull(mutatedSchema);
			assertInstanceOf(ReflectedReferenceSchemaContract.class, mutatedSchema);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should update indexed in entity schema")
		void shouldMutateEntitySchema() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema(false)));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			assertArrayEquals(
				Scope.DEFAULT_SCOPES,
				newReferenceSchema.getIndexedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

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
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
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
			final SetReferenceSchemaIndexedMutation mutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final String result = mutation.toString();

			assertTrue(result.contains("Set entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("indexed"));
		}

		@Test
		@DisplayName("should return correct getIndexed for Boolean constructor variants")
		void shouldReturnCorrectGetIndexedForBooleanConstructors() {
			final SetReferenceSchemaIndexedMutation trueMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);
			final SetReferenceSchemaIndexedMutation falseMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, false);
			final SetReferenceSchemaIndexedMutation nullMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, (Boolean) null);

			assertEquals(true, trueMutation.getIndexed());
			assertEquals(false, falseMutation.getIndexed());
			assertNull(nullMutation.getIndexed());
		}
	}
}
