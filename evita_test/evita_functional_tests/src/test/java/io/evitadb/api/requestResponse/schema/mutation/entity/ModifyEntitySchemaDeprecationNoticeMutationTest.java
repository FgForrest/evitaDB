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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyEntitySchemaDeprecationNoticeMutation} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyEntitySchemaDeprecationNoticeMutation")
class ModifyEntitySchemaDeprecationNoticeMutationTest {

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous deprecation notice mutation")
		void shouldReplacePreviousDeprecationNoticeMutation() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("newNotice");
			final ModifyEntitySchemaDeprecationNoticeMutation existingMutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("oldNotice");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final MutationCombinationResult<LocalEntitySchemaMutation> result =
				mutation.combineWith(catalogSchema, entitySchema, existingMutation);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			assertInstanceOf(ModifyEntitySchemaDeprecationNoticeMutation.class, result.current()[0]);
			assertEquals(
				"newNotice",
				((ModifyEntitySchemaDeprecationNoticeMutation) result.current()[0]).getDeprecationNotice()
			);
		}

		@Test
		@DisplayName("should return null when combined with unrelated mutation")
		void shouldReturnNullForUnrelatedMutation() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("notice");
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
		@DisplayName("should set deprecation notice on entity schema")
		void shouldSetDeprecationNotice() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("deprecated");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.version()).thenReturn(1);
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertEquals(2, result.version());
			assertEquals("deprecated", result.getDeprecationNotice());
		}

		@Test
		@DisplayName("should return unchanged schema when deprecation notice is the same")
		void shouldReturnUnchangedSchemaWhenNoticeIsSame() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("sameNotice");
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getDeprecationNotice()).thenReturn("sameNotice");
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				entitySchema
			);
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("notice");
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
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("notice");
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("notice");
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
			final ModifyEntitySchemaDeprecationNoticeMutation mutation =
				new ModifyEntitySchemaDeprecationNoticeMutation("this is deprecated");
			final String result = mutation.toString();
			assertTrue(result.contains("Modify entity schema"));
			assertTrue(result.contains("deprecationNotice"));
			assertTrue(result.contains("this is deprecated"));
		}
	}
}
