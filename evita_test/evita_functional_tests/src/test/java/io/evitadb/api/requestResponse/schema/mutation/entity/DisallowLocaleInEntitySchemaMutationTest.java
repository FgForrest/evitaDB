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
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * This test verifies {@link DisallowLocaleInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("DisallowLocaleInEntitySchemaMutation")
class DisallowLocaleInEntitySchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should merge locales with same mutation type")
		void shouldMergeLocalesWithSameMutationType() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final DisallowLocaleInEntitySchemaMutation existingMutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.FRENCH);
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
				DisallowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Set<Locale> locales =
				((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(3, locales.size());
			assertArrayEquals(
				new String[]{"de", "en", "fr"},
				locales.stream().map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter locales with allow mutation")
		void shouldFilterLocalesWithAllowMutation() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(
					Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH
				);
			final AllowLocaleInEntitySchemaMutation existingMutation =
				new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getLocales()).thenReturn(
				Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet())
			);
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
				DisallowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Set<Locale> locales =
				((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(2, locales.size());
			assertArrayEquals(
				new String[]{"de", "en"},
				locales.stream().map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter locales with wider allow mutation")
		void shouldFilterLocalesWithWiderAllowMutation() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final AllowLocaleInEntitySchemaMutation existingMutation =
				new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getLocales()).thenReturn(
				Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet())
			);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);
			assertNotNull(result);
			assertNotNull(result.origin());
			assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.origin());
			final Locale[] allowedLocales =
				((AllowLocaleInEntitySchemaMutation) result.origin()).getLocales();
			assertEquals(1, allowedLocales.length);
			assertArrayEquals(
				new String[]{"fr"},
				Arrays.stream(allowedLocales)
					.map(Locale::toLanguageTag).sorted().toArray()
			);
			assertNotNull(result.current());
			assertInstanceOf(
				DisallowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Set<Locale> disallowedLocales =
				((DisallowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(2, disallowedLocales.size());
			assertArrayEquals(
				new String[]{"de", "en"},
				disallowedLocales.stream()
					.map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutationType() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
			final LocalEntitySchemaMutation unrelatedMutation =
				Mockito.mock(LocalEntitySchemaMutation.class);
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
	@DisplayName("Mutate entity schema")
	class Mutate {

		@Test
		@DisplayName("should remove locale from entity schema")
		void shouldRemoveLocaleFromEntitySchema() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getLocales()).thenReturn(
				Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet())
			);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertEquals(2, newEntitySchema.version());
			final Set<Locale> locales = newEntitySchema.getLocales();
			assertTrue(locales.isEmpty());
		}

		@Test
		@DisplayName("should return unchanged schema when locale not present")
		void shouldReturnUnchangedSchemaWhenLocaleNotPresent() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.FRENCH);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getLocales()).thenReturn(
				Stream.of(Locale.ENGLISH, Locale.GERMAN).collect(Collectors.toSet())
			);
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
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
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
			final List<ConflictKey> keys =
				new ConflictGenerationContext().withEntityType(
					"product", null,
					ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
				);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.FRENCH);
			final String result = mutation.toString();
			assertTrue(result.contains("Disallow"));
			assertTrue(result.contains("locales="));
		}

		@Test
		@DisplayName("should create from Set constructor")
		void shouldCreateFromSetConstructor() {
			final DisallowLocaleInEntitySchemaMutation mutation =
				new DisallowLocaleInEntitySchemaMutation(
					Set.of(Locale.ENGLISH, Locale.GERMAN)
				);
			final Set<Locale> locales = mutation.getLocales();
			assertEquals(2, locales.size());
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
		}
	}
}
