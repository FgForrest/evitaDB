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

package io.evitadb.api.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link DisallowEvolutionModeInEntitySchemaMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("DisallowEvolutionModeInEntitySchemaMutation")
class DisallowEvolutionModeInEntitySchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should merge evolution modes with same mutation type")
		void shouldMergeEvolutionModesWithSameMutationType() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES
				);
			final DisallowEvolutionModeInEntitySchemaMutation existingMutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_ASSOCIATED_DATA
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(
				DisallowEvolutionModeInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<EvolutionMode> modes =
				((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0])
					.getEvolutionModes();
			assertEquals(3, modes.size());
			assertArrayEquals(
				new EvolutionMode[]{
					EvolutionMode.ADDING_ASSOCIATED_DATA,
					EvolutionMode.ADDING_LOCALES,
					EvolutionMode.ADDING_CURRENCIES
				},
				modes.stream().sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter evolution modes with allow mutation")
		void shouldFilterEvolutionModesWithAllowMutation() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES,
					EvolutionMode.ADDING_CURRENCIES,
					EvolutionMode.ADDING_ASSOCIATED_DATA
				);
			final AllowEvolutionModeInEntitySchemaMutation existingMutation =
				new AllowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_ASSOCIATED_DATA
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getEvolutionMode())
				.thenReturn(
					Stream.of(
						EvolutionMode.ADDING_LOCALES,
						EvolutionMode.ADDING_CURRENCIES
					).collect(Collectors.toSet())
				);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);

			assertNotNull(result);
			// allow mutation is fully consumed (all its modes are in the disallow set)
			assertNull(result.origin());
			assertNotNull(result.current());
			assertInstanceOf(
				DisallowEvolutionModeInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<EvolutionMode> modes =
				((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0])
					.getEvolutionModes();
			assertEquals(2, modes.size());
			assertArrayEquals(
				new EvolutionMode[]{
					EvolutionMode.ADDING_LOCALES,
					EvolutionMode.ADDING_CURRENCIES
				},
				modes.stream().sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter evolution modes with wider allow mutation")
		void shouldFilterEvolutionModesWithWiderAllowMutation() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES
				);
			final AllowEvolutionModeInEntitySchemaMutation existingMutation =
				new AllowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_ASSOCIATED_DATA
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getEvolutionMode())
				.thenReturn(
					Stream.of(
						EvolutionMode.ADDING_LOCALES,
						EvolutionMode.ADDING_CURRENCIES
					).collect(Collectors.toSet())
				);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);

			assertNotNull(result);
			// allow mutation preserved since it has no overlap with disallow
			assertNotNull(result.origin());
			assertInstanceOf(
				AllowEvolutionModeInEntitySchemaMutation.class,
				result.origin()
			);
			final EvolutionMode[] allowedModes =
				((AllowEvolutionModeInEntitySchemaMutation) result.origin())
					.getEvolutionModes();
			assertEquals(1, allowedModes.length);
			assertArrayEquals(
				new EvolutionMode[]{EvolutionMode.ADDING_ASSOCIATED_DATA},
				Arrays.stream(allowedModes).sorted().toArray()
			);

			assertNotNull(result.current());
			assertInstanceOf(
				DisallowEvolutionModeInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<EvolutionMode> disallowedModes =
				((DisallowEvolutionModeInEntitySchemaMutation) result.current()[0])
					.getEvolutionModes();
			assertEquals(2, disallowedModes.size());
			assertArrayEquals(
				new EvolutionMode[]{
					EvolutionMode.ADDING_LOCALES,
					EvolutionMode.ADDING_CURRENCIES
				},
				disallowedModes.stream().sorted().toArray()
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutationType() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES
				);
			final ModifyEntitySchemaDescriptionMutation existingMutation =
				new ModifyEntitySchemaDescriptionMutation("some description");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class Mutate {

		@Test
		@DisplayName("should remove evolution mode from entity schema")
		void shouldRemoveEvolutionModeFromEntitySchema() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES, EvolutionMode.ADDING_CURRENCIES
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getEvolutionMode())
				.thenReturn(
					Stream.of(
						EvolutionMode.ADDING_LOCALES,
						EvolutionMode.ADDING_CURRENCIES
					).collect(Collectors.toSet())
				);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final Set<EvolutionMode> modes = newEntitySchema.getEvolutionMode();
			assertTrue(modes.isEmpty());
		}

		@Test
		@DisplayName("should return unchanged schema when mode not present")
		void shouldReturnUnchangedSchemaWhenModeNotPresent() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES
				);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getEvolutionMode()).thenReturn(Set.of());
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);

			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES
				);
			assertThrows(
				Exception.class,
				() -> mutation.mutate(
					Mockito.mock(CatalogSchemaContract.class), null
				)
			);
		}
	}

	@Nested
	@DisplayName("Metadata and contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES
				);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES
				);
			final List<ConflictKey> keys = new ConflictGenerationContext()
				.withEntityType(
					"testEntity", null,
					ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
				);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					EvolutionMode.ADDING_LOCALES,
					EvolutionMode.ADDING_CURRENCIES
				);
			final String result = mutation.toString();
			assertTrue(result.contains("Disallow"));
			assertTrue(result.contains("evolutionModes="));
			assertTrue(result.contains("ADDING_LOCALES"));
			assertTrue(result.contains("ADDING_CURRENCIES"));
		}

		@Test
		@DisplayName("should create from Set constructor")
		void shouldCreateFromSetConstructor() {
			final DisallowEvolutionModeInEntitySchemaMutation mutation =
				new DisallowEvolutionModeInEntitySchemaMutation(
					Set.of(
						EvolutionMode.ADDING_LOCALES,
						EvolutionMode.ADDING_CURRENCIES
					)
				);
			final Set<EvolutionMode> modes = mutation.getEvolutionModes();
			assertEquals(2, modes.size());
			assertTrue(modes.contains(EvolutionMode.ADDING_LOCALES));
			assertTrue(modes.contains(EvolutionMode.ADDING_CURRENCIES));
		}
	}
}
