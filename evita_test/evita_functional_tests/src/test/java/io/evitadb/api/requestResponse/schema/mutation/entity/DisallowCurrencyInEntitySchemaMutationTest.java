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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Currency;
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
 * This test verifies {@link DisallowCurrencyInEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("DisallowCurrencyInEntitySchemaMutation")
class DisallowCurrencyInEntitySchemaMutationTest {

	@Nested
	@DisplayName("CombineWith")
	class CombineWith {

		@Test
		@DisplayName("should merge currencies with same mutation type")
		void shouldMergeCurrenciesWithSameMutationType() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD"), Currency.getInstance("EUR")
				);
			final DisallowCurrencyInEntitySchemaMutation existingMutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("GBP")
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);

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
				DisallowCurrencyInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<Currency> currencies =
				((DisallowCurrencyInEntitySchemaMutation) result.current()[0])
					.getCurrencies();
			assertEquals(3, currencies.size());
			assertArrayEquals(
				new String[]{"EUR", "GBP", "USD"},
				currencies.stream()
					.map(Currency::getCurrencyCode)
					.sorted()
					.toArray()
			);
		}

		@Test
		@DisplayName("should filter currencies with allow mutation")
		void shouldFilterCurrenciesWithAllowMutation() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR"),
					Currency.getInstance("GBP")
				);
			final AllowCurrencyInEntitySchemaMutation existingMutation =
				new AllowCurrencyInEntitySchemaMutation(
					Currency.getInstance("GBP")
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getCurrencies()).thenReturn(
				Stream.of(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR")
				).collect(Collectors.toSet())
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
				DisallowCurrencyInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<Currency> currencies =
				((DisallowCurrencyInEntitySchemaMutation) result.current()[0])
					.getCurrencies();
			assertEquals(2, currencies.size());
			assertArrayEquals(
				new String[]{"EUR", "USD"},
				currencies.stream()
					.map(Currency::getCurrencyCode)
					.sorted()
					.toArray()
			);
		}

		@Test
		@DisplayName("should filter currencies with wider allow mutation")
		void shouldFilterCurrenciesWithWiderAllowMutation() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD"), Currency.getInstance("EUR")
				);
			final AllowCurrencyInEntitySchemaMutation existingMutation =
				new AllowCurrencyInEntitySchemaMutation(
					Currency.getInstance("GBP")
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getCurrencies()).thenReturn(
				Stream.of(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR")
				).collect(Collectors.toSet())
			);

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					entitySchema,
					existingMutation
				);

			assertNotNull(result);
			assertNotNull(result.origin());
			assertInstanceOf(
				AllowCurrencyInEntitySchemaMutation.class, result.origin()
			);
			final Currency[] allowedCurrencies =
				((AllowCurrencyInEntitySchemaMutation) result.origin())
					.getCurrencies();
			assertEquals(1, allowedCurrencies.length);
			assertArrayEquals(
				new String[]{"GBP"},
				Arrays.stream(allowedCurrencies)
					.map(Currency::getCurrencyCode)
					.sorted()
					.toArray()
			);
			assertNotNull(result.current());
			assertInstanceOf(
				DisallowCurrencyInEntitySchemaMutation.class,
				result.current()[0]
			);
			final Set<Currency> disallowedCurrencies =
				((DisallowCurrencyInEntitySchemaMutation) result.current()[0])
					.getCurrencies();
			assertEquals(2, disallowedCurrencies.size());
			assertArrayEquals(
				new String[]{"EUR", "USD"},
				disallowedCurrencies.stream()
					.map(Currency::getCurrencyCode)
					.sorted()
					.toArray()
			);
		}

		@Test
		@DisplayName("should return null for unrelated mutation type")
		void shouldReturnNullForUnrelatedMutationType() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD")
				);
			final ModifyEntitySchemaDescriptionMutation existingMutation =
				new ModifyEntitySchemaDescriptionMutation("desc");

			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(
					Mockito.mock(CatalogSchemaContract.class),
					Mockito.mock(EntitySchemaContract.class),
					existingMutation
				);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate")
	class Mutate {

		@Test
		@DisplayName("should remove currency from entity schema")
		void shouldRemoveCurrencyFromEntitySchema() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD")
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getCurrencies()).thenReturn(
				Stream.of(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR")
				).collect(Collectors.toSet())
			);
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract newEntitySchema = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);

			assertEquals(2, newEntitySchema.version());
			final Set<Currency> currencies =
				newEntitySchema.getCurrencies();
			assertEquals(1, currencies.size());
			assertArrayEquals(
				new String[]{"EUR"},
				currencies.stream()
					.map(Currency::getCurrencyCode)
					.sorted()
					.toArray()
			);
		}

		@Test
		@DisplayName(
			"should return unchanged schema when currency not present"
		)
		void shouldReturnUnchangedSchemaWhenCurrencyNotPresent() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("GBP")
				);
			final EntitySchemaContract entitySchema =
				Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getCurrencies()).thenReturn(
				Stream.of(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR")
				).collect(Collectors.toSet())
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
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD")
				);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate(
					Mockito.mock(CatalogSchemaContract.class), null
				)
			);
		}
	}

	@Nested
	@DisplayName("Metadata")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD")
				);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD")
				);
			final List<ConflictKey> keys =
				new ConflictGenerationContext().withEntityType(
					"product", null,
					ctx -> mutation.collectConflictKeys(
						ctx, Set.of()
					).toList()
				);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Currency.getInstance("USD"),
					Currency.getInstance("EUR")
				);
			final String result = mutation.toString();
			assertTrue(result.contains("Disallow"));
			assertTrue(result.contains("currencies="));
			assertTrue(result.contains("USD"));
			assertTrue(result.contains("EUR"));
		}

		@Test
		@DisplayName("should create from Set constructor")
		void shouldCreateFromSetConstructor() {
			final DisallowCurrencyInEntitySchemaMutation mutation =
				new DisallowCurrencyInEntitySchemaMutation(
					Set.of(
						Currency.getInstance("USD"),
						Currency.getInstance("EUR")
					)
				);
			final Set<Currency> currencies = mutation.getCurrencies();
			assertEquals(2, currencies.size());
			assertTrue(
				currencies.contains(Currency.getInstance("USD"))
			);
			assertTrue(
				currencies.contains(Currency.getInstance("EUR"))
			);
		}
	}
}
