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
 * Tests for {@link SetReferenceSchemaFacetedMutation} verifying faceted flag mutations,
 * combination with same-type mutations, and entity schema mutation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetReferenceSchemaFacetedMutation")
class SetReferenceSchemaFacetedMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous faceted mutation when names match")
		void shouldOverrideFacetedFlagOfPreviousMutationIfNamesMatch() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaFacetedMutation existingMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
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
			assertInstanceOf(SetReferenceSchemaFacetedMutation.class, result.current()[0]);
			assertArrayEquals(
				Scope.NO_SCOPE,
				((SetReferenceSchemaFacetedMutation) result.current()[0]).getFacetedInScopes()
			);
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final SetReferenceSchemaFacetedMutation existingMutation =
				new SetReferenceSchemaFacetedMutation("differentName", Scope.DEFAULT_SCOPES);

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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final LocalEntitySchemaMutation unrelatedMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);

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
		@DisplayName("should set faceted scopes on reference")
		void shouldMutateReferenceSchema() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

			final ReferenceSchemaContract mutatedSchema =
				mutation.mutate(
					Mockito.mock(EntitySchemaContract.class), createExistingReferenceSchema()
				);

			assertNotNull(mutatedSchema);
			assertArrayEquals(
				Scope.NO_SCOPE,
				mutatedSchema.getFacetedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should return same schema when faceted scopes are unchanged")
		void shouldReturnSameSchemaWhenFacetedScopesUnchanged() {
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();
			// existing schema is faceted in Scope.LIVE
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(
					REFERENCE_NAME, new Scope[]{Scope.LIVE}
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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

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
		@DisplayName("should update faceted in entity schema")
		void shouldMutateEntitySchema() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReference(REFERENCE_NAME))
				.thenReturn(of(createExistingReferenceSchema()));
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final ReferenceSchemaContract newReferenceSchema =
				newEntitySchema.getReference(REFERENCE_NAME).orElseThrow();
			// assert on FACETED scopes, not indexed scopes (BUG FIX from original test)
			assertArrayEquals(
				Scope.NO_SCOPE,
				newReferenceSchema.getFacetedInScopes().toArray(Scope[]::new)
			);
		}

		@Test
		@DisplayName("should throw when reference does not exist")
		void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingReference() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.NO_SCOPE);

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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);
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
			final SetReferenceSchemaFacetedMutation mutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, Scope.DEFAULT_SCOPES);

			final String result = mutation.toString();

			assertTrue(result.contains("Set entity reference"));
			assertTrue(result.contains(REFERENCE_NAME));
			assertTrue(result.contains("faceted"));
		}

		@Test
		@DisplayName("should return correct getFaceted for Boolean constructor variants")
		void shouldReturnCorrectGetFacetedForBooleanConstructors() {
			final SetReferenceSchemaFacetedMutation trueMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, true);
			final SetReferenceSchemaFacetedMutation falseMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, false);
			final SetReferenceSchemaFacetedMutation nullMutation =
				new SetReferenceSchemaFacetedMutation(REFERENCE_NAME, (Boolean) null);

			assertEquals(true, trueMutation.getFaceted());
			assertEquals(false, falseMutation.getFaceted());
			assertNull(nullMutation.getFaceted());
		}
	}
}
