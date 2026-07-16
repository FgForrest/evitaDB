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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
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

import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyEntitySchemaConflictResolutionMutation} class - the mutation that sets the
 * entity-collection-level conflict resolution override.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ModifyEntitySchemaConflictResolutionMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(TRANSACTION)
class ModifyEntitySchemaConflictResolutionMutationTest {

	@Nested
	@DisplayName("Mutate entity schema")
	class Mutate {

		@Test
		@DisplayName("should set conflict resolution and bump version when value changes")
		void shouldSetConflictResolution() {
			final ConflictResolution resolution = new ConflictResolution(ConflictPolicy.ENTITY);
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(resolution);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			// the mock returns Optional.empty() for getConflictResolution() by default (currently inherited)

			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, result.version());
			assertEquals(resolution, result.getConflictResolution().orElseThrow());
		}

		@Test
		@DisplayName("should clear conflict resolution to inherited when a value was set")
		void shouldClearConflictResolutionToInherited() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(null);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.getConflictResolution())
				.thenReturn(Optional.of(new ConflictResolution(ConflictPolicy.ENTITY)));

			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertEquals(2, result.version());
			assertTrue(result.getConflictResolution().isEmpty());
		}

		@Test
		@DisplayName("should return same schema when both current and requested value are inherited")
		void shouldReturnSameSchemaWhenBothInherited() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(null);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			// default mock returns Optional.empty() - already inherited

			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should return same schema when requested value equals the current one")
		void shouldReturnSameSchemaWhenValueUnchanged() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.COLLECTION));
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getConflictResolution())
				.thenReturn(Optional.of(new ConflictResolution(ConflictPolicy.COLLECTION)));

			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), entitySchema
			);

			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw internal error when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null)
			);
		}
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous conflict resolution mutation letting the newer value win")
		void shouldReplacePreviousMutationWithNewerValue() {
			final ConflictResolution newerResolution = new ConflictResolution(ConflictPolicy.ENTITY);
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(newerResolution);
			final ModifyEntitySchemaConflictResolutionMutation existingMutation =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.COLLECTION));

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class),
				existingMutation
			);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			final ModifyEntitySchemaConflictResolutionMutation winner =
				assertInstanceOf(ModifyEntitySchemaConflictResolutionMutation.class, result.current()[0]);
			assertEquals(newerResolution, winner.getConflictResolution());
		}

		@Test
		@DisplayName("should return null when combined with an unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final LocalEntitySchemaMutation unrelatedMutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("notice");

			final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class),
				unrelatedMutation
			);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Conflict key generation")
	class ConflictKeys {

		@Test
		@DisplayName("should generate a single collection conflict key for the mutated entity type")
		void shouldReturnCollectionConflictKey() {
			final ModifyEntitySchemaConflictResolutionMutation mutation =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final List<ConflictKey> keys = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.NONE))
				.withEntityType(
					"product", null,
					ctx -> mutation.collectConflictKeys(ctx).toList()
				);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}
	}

	@Nested
	@DisplayName("Equality")
	class Equality {

		@Test
		@DisplayName("should be equal to a mutation carrying the same conflict resolution")
		void shouldBeEqualForSameResolution() {
			final ModifyEntitySchemaConflictResolutionMutation mutation1 =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final ModifyEntitySchemaConflictResolutionMutation mutation2 =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to a mutation carrying a different conflict resolution")
		void shouldNotBeEqualForDifferentResolution() {
			final ModifyEntitySchemaConflictResolutionMutation mutation1 =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final ModifyEntitySchemaConflictResolutionMutation mutation2 =
				new ModifyEntitySchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.COLLECTION));
			assertNotEquals(mutation1, mutation2);
		}
	}
}
