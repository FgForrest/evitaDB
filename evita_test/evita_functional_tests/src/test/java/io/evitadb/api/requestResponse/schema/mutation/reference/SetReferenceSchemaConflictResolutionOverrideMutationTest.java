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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.REFERENCE_NAME;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReferenceSchema;
import static io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutationTest.createExistingReflectedReferenceSchema;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetReferenceSchemaConflictResolutionOverrideMutation} verifying the per-reference conflict
 * resolution override is applied, combined and validated correctly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SetReferenceSchemaConflictResolutionOverrideMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(REFERENCE)
class SetReferenceSchemaConflictResolutionOverrideMutationTest {

	@Nested
	@DisplayName("Mutate reference schema")
	class Mutate {

		@Test
		@DisplayName("should carry the new override into the rebuilt plain reference schema")
		void shouldSetOverrideOnPlainReferenceSchema() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			// the fixture carries the default INHERITED override
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();

			final ReferenceSchemaContract result = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class), existingSchema
			);

			assertNotNull(result);
			assertEquals(ConflictResolutionOverride.GRANULAR, result.getConflictResolutionOverride());
		}

		@Test
		@DisplayName("should return the same schema instance when the override is unchanged")
		void shouldReturnSameSchemaWhenOverrideUnchanged() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.INHERITED
				);
			// the fixture already carries the INHERITED override
			final ReferenceSchemaContract existingSchema = createExistingReferenceSchema();

			final ReferenceSchemaContract result = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class), existingSchema
			);

			assertSame(existingSchema, result);
		}

		@Test
		@DisplayName("should throw when a non-inherited override is set on a reflected reference")
		void shouldThrowWhenSettingOverrideOnReflectedReference() {
			// the reflected reference always inherits (INHERITED) - a differing value must reach the throw
			// instead of being short-circuited by the unchanged-value early return
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final ReflectedReferenceSchema reflectedSchema = createExistingReflectedReferenceSchema();

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), reflectedSchema)
			);
		}

		@Test
		@DisplayName("should throw internal error when the reference schema is null")
		void shouldThrowWhenReferenceSchemaIsNull() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), null)
			);
		}
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous override mutation letting the newer value win when names match")
		void shouldReplacePreviousMutationWhenNamesMatch() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetReferenceSchemaConflictResolutionOverrideMutation existingMutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.ENTITY
				);

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class),
				existingMutation
			);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			final SetReferenceSchemaConflictResolutionOverrideMutation winner =
				assertInstanceOf(SetReferenceSchemaConflictResolutionOverrideMutation.class, result.current()[0]);
			assertEquals(ConflictResolutionOverride.GRANULAR, winner.getConflictResolutionOverride());
		}

		@Test
		@DisplayName("should not combine when reference names differ")
		void shouldNotCombineWhenNamesDiffer() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetReferenceSchemaConflictResolutionOverrideMutation existingMutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					"differentName", ConflictResolutionOverride.ENTITY
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
		@DisplayName("should return null for an unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final LocalEntitySchemaMutation unrelatedMutation =
				new SetReferenceSchemaIndexedMutation(REFERENCE_NAME, true);

			assertNull(
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					unrelatedMutation
				)
			);
		}
	}

	@Nested
	@DisplayName("Equality")
	class Equality {

		@Test
		@DisplayName("should not be equal to a mutation with a different override value")
		void shouldNotBeEqualForDifferentOverride() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation1 =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation2 =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.ENTITY
				);

			assertNotEquals(mutation1, mutation2);
		}

		@Test
		@DisplayName("should not be equal to a mutation with a different reference name")
		void shouldNotBeEqualForDifferentName() {
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation1 =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					REFERENCE_NAME, ConflictResolutionOverride.GRANULAR
				);
			final SetReferenceSchemaConflictResolutionOverrideMutation mutation2 =
				new SetReferenceSchemaConflictResolutionOverrideMutation(
					"differentName", ConflictResolutionOverride.GRANULAR
				);

			assertNotEquals(mutation1, mutation2);
		}
	}
}
