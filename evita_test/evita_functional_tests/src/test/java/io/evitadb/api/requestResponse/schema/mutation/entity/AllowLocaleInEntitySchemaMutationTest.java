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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link AllowLocaleInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("AllowLocaleInEntitySchemaMutation")
class AllowLocaleInEntitySchemaMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should merge locales with same mutation type")
		void shouldMergeLocalesWithSameMutationType() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final AllowLocaleInEntitySchemaMutation existingMutation =
				new AllowLocaleInEntitySchemaMutation(Locale.FRENCH);
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
			assertInstanceOf(AllowLocaleInEntitySchemaMutation.class, result.current()[0]);
			final Locale[] locales =
				((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(3, locales.length);
			assertArrayEquals(
				new String[]{"de", "en", "fr"},
				Arrays.stream(locales).map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter locales with disallow mutation")
		void shouldFilterLocalesWithDisallowMutation() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final DisallowLocaleInEntitySchemaMutation existingMutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH);
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
				AllowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Locale[] locales =
				((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(2, locales.length);
			assertArrayEquals(
				new String[]{"de", "en"},
				Arrays.stream(locales).map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should filter locales with wider disallow mutation")
		void shouldFilterLocalesWithWiderDisallowMutation() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final DisallowLocaleInEntitySchemaMutation existingMutation =
				new DisallowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.FRENCH);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);
			assertNotNull(result);
			assertNotNull(result.origin());
			assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.origin());
			final Set<Locale> disallowedLocales =
				((DisallowLocaleInEntitySchemaMutation) result.origin()).getLocales();
			assertEquals(1, disallowedLocales.size());
			assertArrayEquals(
				new String[]{"fr"},
				disallowedLocales.stream()
					.map(Locale::toLanguageTag).sorted().toArray()
			);
			assertNotNull(result.current());
			assertInstanceOf(
				AllowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Locale[] allowedLocales =
				((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(2, allowedLocales.length);
			assertArrayEquals(
				new String[]{"de", "en"},
				Arrays.stream(allowedLocales)
					.map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should preserve both mutations with non-overlapping disallow")
		void shouldPreserveBothMutationsWithNonOverlappingDisallow() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
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
			assertNotNull(result.origin());
			assertInstanceOf(DisallowLocaleInEntitySchemaMutation.class, result.origin());
			final Set<Locale> disallowedLocales =
				((DisallowLocaleInEntitySchemaMutation) result.origin()).getLocales();
			assertEquals(1, disallowedLocales.size());
			assertArrayEquals(
				new String[]{"fr"},
				disallowedLocales.stream()
					.map(Locale::toLanguageTag).sorted().toArray()
			);
			assertNotNull(result.current());
			assertInstanceOf(
				AllowLocaleInEntitySchemaMutation.class, result.current()[0]
			);
			final Locale[] allowedLocales =
				((AllowLocaleInEntitySchemaMutation) result.current()[0]).getLocales();
			assertEquals(2, allowedLocales.length);
			assertArrayEquals(
				new String[]{"de", "en"},
				Arrays.stream(allowedLocales)
					.map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutationType() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH);
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
		@DisplayName("should add new locale to entity schema")
		void shouldAddNewLocaleToEntitySchema() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.GERMAN);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertEquals(2, newEntitySchema.version());
			final Set<Locale> locales = newEntitySchema.getLocales();
			assertEquals(2, locales.size());
			assertArrayEquals(
				new String[]{"de", "en"},
				locales.stream().map(Locale::toLanguageTag).sorted().toArray()
			);
		}

		@Test
		@DisplayName("should return unchanged schema when locale already present")
		void shouldReturnUnchangedSchemaWhenLocaleAlreadyPresent() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.supportsLocale(Locale.ENGLISH)).thenReturn(true);
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH);
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
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH);
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
			final AllowLocaleInEntitySchemaMutation mutation =
				new AllowLocaleInEntitySchemaMutation(Locale.ENGLISH, Locale.FRENCH);
			final String result = mutation.toString();
			assertTrue(result.contains("Allow"));
			assertTrue(result.contains("locales="));
		}
	}
}
