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
 * This test verifies {@link SetEntitySchemaWithHierarchyMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("SetEntitySchemaWithHierarchyMutation")
class SetEntitySchemaWithHierarchyMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous hierarchy mutation")
		void shouldReplacePreviousHierarchyMutation() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
			final SetEntitySchemaWithHierarchyMutation existingMutation =
				new SetEntitySchemaWithHierarchyMutation(false, Scope.NO_SCOPE);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(catalogSchema, entitySchema, existingMutation);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			assertInstanceOf(SetEntitySchemaWithHierarchyMutation.class, result.current()[0]);
			final SetEntitySchemaWithHierarchyMutation currentMutation =
				(SetEntitySchemaWithHierarchyMutation) result.current()[0];
			assertTrue(currentMutation.isWithHierarchy());
			assertArrayEquals(Scope.values(), currentMutation.getIndexedInScopes());
		}

		@Test
		@DisplayName("should return null when combined with unrelated mutation")
		void shouldReturnNullForUnrelatedMutation() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
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
		@DisplayName("should enable hierarchy with scopes")
		void shouldEnableHierarchyWithScopes() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertEquals(2, result.version());
			assertTrue(result.isWithHierarchy());
			assertEquals(
				Set.of(Scope.values()),
				result.getHierarchyIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should return unchanged schema when hierarchy and scopes are the same")
		void shouldReturnUnchangedSchemaWhenSame() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, new Scope[]{Scope.LIVE});
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.isWithHierarchy()).thenReturn(true);
			Mockito.when(entitySchema.getHierarchyIndexedInScopes())
				.thenReturn(Set.of(Scope.LIVE));
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should mutate when scopes differ even if hierarchy flag is the same")
		void shouldMutateWhenScopesOnlyChange() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, new Scope[]{Scope.LIVE, Scope.ARCHIVED});
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(entitySchema.isWithHierarchy()).thenReturn(true);
			Mockito.when(entitySchema.getHierarchyIndexedInScopes())
				.thenReturn(Set.of(Scope.LIVE));
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertNotSame(entitySchema, result);
			assertEquals(2, result.version());
			assertTrue(result.isWithHierarchy());
			assertEquals(
				Set.of(Scope.LIVE, Scope.ARCHIVED),
				result.getHierarchyIndexedInScopes()
			);
		}

		@Test
		@DisplayName("should default to empty scopes when null indexedInScopes is passed")
		void shouldDefaultToEmptyScopesWhenNull() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(false, null);
			assertArrayEquals(Scope.NO_SCOPE, mutation.getIndexedInScopes());
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
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
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
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
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(true, Scope.values());
			final String result = mutation.toString();
			assertTrue(result.contains("Set entity schema"));
			assertTrue(result.contains("withHierarchy=true"));
			assertTrue(result.contains("indexed in scopes"));
			assertTrue(result.contains(Arrays.toString(Scope.values())));
		}

		@Test
		@DisplayName("should produce toString without scopes when scopes are empty")
		void shouldProduceToStringWithoutScopes() {
			final SetEntitySchemaWithHierarchyMutation mutation =
				new SetEntitySchemaWithHierarchyMutation(false, Scope.NO_SCOPE);
			final String result = mutation.toString();
			assertTrue(result.contains("Set entity schema"));
			assertTrue(result.contains("withHierarchy=false"));
			assertTrue(result.contains("(not indexed)"));
		}
	}
}
