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
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link SetEntitySchemaWithPriceMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetEntitySchemaWithPriceMutation")
class SetEntitySchemaWithPriceMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous price mutation")
		void shouldReplacePreviousPriceMutation() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			final SetEntitySchemaWithPriceMutation existingMutation =
				new SetEntitySchemaWithPriceMutation(false, Scope.NO_SCOPE, 0);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(catalogSchema, entitySchema, existingMutation);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			assertInstanceOf(SetEntitySchemaWithPriceMutation.class, result.current()[0]);
			final SetEntitySchemaWithPriceMutation currentMutation =
				(SetEntitySchemaWithPriceMutation) result.current()[0];
			assertTrue(currentMutation.isWithPrice());
			assertEquals(2, currentMutation.getIndexedPricePlaces());
			assertArrayEquals(Scope.values(), currentMutation.getIndexedInScopes());
		}

		@Test
		@DisplayName("should return null when combined with unrelated mutation")
		void shouldReturnNullForUnrelatedMutation() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final LocalEntitySchemaMutation unrelatedMutation =
				new ModifyEntitySchemaDescriptionMutation("desc");
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(catalogSchema, entitySchema, unrelatedMutation);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class Mutate {

		@Test
		@DisplayName("should enable price with scopes and price places")
		void shouldEnablePriceWithScopesAndPricePlaces() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertEquals(2, result.version());
			assertTrue(result.isWithPrice());
			assertEquals(2, result.getIndexedPricePlaces());
			assertEquals(
				Set.of(Scope.values()),
				result.getPriceIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should return unchanged schema when price settings are the same")
		void shouldReturnUnchangedSchemaWhenSame() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, new Scope[]{Scope.LIVE}, 2);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.isWithPrice()).thenReturn(true);
			Mockito.when(entitySchema.getIndexedPricePlaces()).thenReturn(2);
			Mockito.when(entitySchema.getPriceIndexedInScopes())
				.thenReturn(Set.of(Scope.LIVE));
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should mutate when scopes differ even if price flag and places are the same")
		void shouldMutateWhenScopesOnlyChange() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, 2);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.isWithPrice()).thenReturn(true);
			Mockito.when(entitySchema.getIndexedPricePlaces()).thenReturn(2);
			Mockito.when(entitySchema.getPriceIndexedInScopes())
				.thenReturn(Set.of(Scope.LIVE));
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertNotSame(entitySchema, result);
			assertEquals(2, result.version());
			assertTrue(result.isWithPrice());
			assertEquals(2, result.getIndexedPricePlaces());
			assertEquals(
				Set.of(Scope.LIVE, Scope.ARCHIVED),
				result.getPriceIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should default to empty scopes when null indexedInScopes is passed")
		void shouldDefaultToEmptyScopesWhenNull() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(false, null, 2);
			assertArrayEquals(Scope.NO_SCOPE, mutation.getIndexedInScopes());
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			assertThrows(
				Exception.class,
				() -> mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null)
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class Metadata {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"testEntity", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce toString with scopes when scopes are present")
		void shouldProduceToStringWithScopes() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(true, Scope.values(), 2);
			final String result = mutation.toString();
			assertTrue(result.contains("Set entity schema"));
			assertTrue(result.contains("withPrice=true"));
			assertTrue(result.contains("indexedPricePlaces=2"));
			assertTrue(result.contains("indexed in scopes"));
			assertTrue(result.contains(Arrays.toString(Scope.values())));
		}

		@Test
		@DisplayName("should produce toString without scopes when scopes are empty")
		void shouldProduceToStringWithoutScopes() {
			final SetEntitySchemaWithPriceMutation mutation =
				new SetEntitySchemaWithPriceMutation(false, Scope.NO_SCOPE, 0);
			final String result = mutation.toString();
			assertTrue(result.contains("Set entity schema"));
			assertTrue(result.contains("withPrice=false"));
			assertTrue(result.contains("(not indexed)"));
		}
	}
}
