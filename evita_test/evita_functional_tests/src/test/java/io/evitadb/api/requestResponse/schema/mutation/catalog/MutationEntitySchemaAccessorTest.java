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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link MutationEntitySchemaAccessor} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("MutationEntitySchemaAccessor")
class MutationEntitySchemaAccessorTest {

	@Nested
	@DisplayName("Singleton instance")
	class SingletonInstance {

		@Test
		@DisplayName("should return empty schemas collection")
		void shouldReturnEmptySchemas() {
			final Collection<EntitySchemaContract> schemas = MutationEntitySchemaAccessor.INSTANCE.getEntitySchemas();
			assertTrue(schemas.isEmpty());
		}

		@Test
		@DisplayName("should return empty optional for any entity type")
		void shouldReturnEmptyForAnyEntityType() {
			final Optional<EntitySchemaContract> result = MutationEntitySchemaAccessor.INSTANCE.getEntitySchema(
				"anyType");
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should ignore addUpsertedEntitySchema call")
		void shouldIgnoreAddUpsertedEntitySchema() {
			final EntitySchemaContract schema = EntitySchema._internalBuild("test");
			MutationEntitySchemaAccessor.INSTANCE.addUpsertedEntitySchema(schema);
			assertTrue(MutationEntitySchemaAccessor.INSTANCE.getEntitySchema("test").isEmpty());
		}

		@Test
		@DisplayName("should ignore removeEntitySchema call")
		void shouldIgnoreRemoveEntitySchema() {
			MutationEntitySchemaAccessor.INSTANCE.removeEntitySchema("anyType");
			assertTrue(MutationEntitySchemaAccessor.INSTANCE.getEntitySchemas().isEmpty());
		}

		@Test
		@DisplayName("should ignore replaceEntitySchema call")
		void shouldIgnoreReplaceEntitySchema() {
			final EntitySchemaContract schema = EntitySchema._internalBuild("test");
			MutationEntitySchemaAccessor.INSTANCE.replaceEntitySchema("old", schema);
			assertTrue(MutationEntitySchemaAccessor.INSTANCE.getEntitySchema("test").isEmpty());
		}

		@Test
		@DisplayName("should be same instance across accesses")
		void shouldBeSameInstance() {
			assertSame(MutationEntitySchemaAccessor.INSTANCE, MutationEntitySchemaAccessor.INSTANCE);
		}
	}

	@Nested
	@DisplayName("Add upserted entity schema")
	class AddUpsertedEntitySchema {

		@Test
		@DisplayName("should add new entity schema")
		void shouldAddNewEntitySchema() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final EntitySchemaContract schema = EntitySchema._internalBuild("product");

			accessor.addUpsertedEntitySchema(schema);

			assertTrue(accessor.getEntitySchema("product").isPresent());
			assertEquals("product", accessor.getEntitySchema("product").orElseThrow().getName());
		}

		@Test
		@DisplayName("should overwrite entity schema with same name")
		void shouldOverwriteEntitySchemaWithSameName() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final EntitySchemaContract schema1 = EntitySchema._internalBuild("product");
			final EntitySchemaContract schema2 = EntitySchema._internalBuild("product");

			accessor.addUpsertedEntitySchema(schema1);
			accessor.addUpsertedEntitySchema(schema2);

			assertTrue(accessor.getEntitySchema("product").isPresent());
			assertSame(schema2, accessor.getEntitySchema("product").orElseThrow());
		}

		@Test
		@DisplayName("should include added schema in getEntitySchemas")
		void shouldIncludeInGetEntitySchemas() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final EntitySchemaContract schema = EntitySchema._internalBuild("product");

			accessor.addUpsertedEntitySchema(schema);

			final Collection<EntitySchemaContract> schemas = accessor.getEntitySchemas();
			assertEquals(1, schemas.size());
			assertEquals("product", schemas.iterator().next().getName());
		}
	}

	@Nested
	@DisplayName("Remove entity schema")
	class RemoveEntitySchema {

		@Test
		@DisplayName("should remove schema from base accessor")
		void shouldRemoveSchemaFromBaseAccessor() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);

			assertTrue(accessor.getEntitySchema("product").isPresent());
			accessor.removeEntitySchema("product");
			assertTrue(accessor.getEntitySchema("product").isEmpty());
		}

		@Test
		@DisplayName("should remove previously added schema")
		void shouldRemovePreviouslyAddedSchema() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final EntitySchemaContract schema = EntitySchema._internalBuild("product");

			accessor.addUpsertedEntitySchema(schema);
			assertTrue(accessor.getEntitySchema("product").isPresent());

			accessor.removeEntitySchema("product");
			assertTrue(accessor.getEntitySchema("product").isEmpty());
		}

		@Test
		@DisplayName("should exclude removed schema from getEntitySchemas")
		void shouldExcludeFromGetEntitySchemas() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));
			Mockito.when(baseProvider.getEntitySchemas()).thenReturn(java.util.List.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			assertEquals(1, accessor.getEntitySchemas().size());

			accessor.removeEntitySchema("product");
			assertTrue(accessor.getEntitySchemas().isEmpty());
		}
	}

	@Nested
	@DisplayName("Replace entity schema")
	class ReplaceEntitySchema {

		@Test
		@DisplayName("should replace schema with different name")
		void shouldReplaceSchemaWithDifferentName() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("oldProduct");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("oldProduct")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final EntitySchemaContract newSchema = EntitySchema._internalBuild("newProduct");

			accessor.replaceEntitySchema("oldProduct", newSchema);

			assertTrue(accessor.getEntitySchema("newProduct").isPresent());
			assertTrue(accessor.getEntitySchema("oldProduct").isEmpty());
		}

		@Test
		@DisplayName("should replace schema keeping same name consistently")
		void shouldReplaceSchemaKeepingSameName() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));
			Mockito.when(baseProvider.getEntitySchemas()).thenReturn(java.util.List.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final EntitySchemaContract newSchema = EntitySchema._internalBuild("product");

			accessor.replaceEntitySchema("product", newSchema);

			// both getEntitySchema and getEntitySchemas should agree
			assertTrue(accessor.getEntitySchema("product").isPresent());
			final Collection<EntitySchemaContract> allSchemas = accessor.getEntitySchemas();
			assertEquals(1, allSchemas.size());
			assertEquals("product", allSchemas.iterator().next().getName());
		}
	}

	@Nested
	@DisplayName("Get entity schemas")
	class GetEntitySchemas {

		@Test
		@DisplayName("should return empty collection when no schemas exist")
		void shouldReturnEmptyWhenNoSchemas() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			assertTrue(accessor.getEntitySchemas().isEmpty());
		}

		@Test
		@DisplayName("should merge base and added schemas")
		void shouldMergeBaseAndAddedSchemas() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchemas()).thenReturn(java.util.List.of(baseSchema));
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final EntitySchemaContract addedSchema = EntitySchema._internalBuild("category");
			accessor.addUpsertedEntitySchema(addedSchema);

			final Collection<EntitySchemaContract> schemas = accessor.getEntitySchemas();
			assertEquals(2, schemas.size());
		}

		@Test
		@DisplayName("should prefer added schema over base schema with same name")
		void shouldPreferAddedOverBase() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchemas()).thenReturn(java.util.List.of(baseSchema));
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final EntitySchemaContract updatedSchema = EntitySchema._internalBuild("product");
			accessor.addUpsertedEntitySchema(updatedSchema);

			final Collection<EntitySchemaContract> schemas = accessor.getEntitySchemas();
			assertEquals(1, schemas.size());
			assertSame(updatedSchema, schemas.iterator().next());
		}

		@Test
		@DisplayName("should not include removed schemas from base")
		void shouldNotIncludeRemovedSchemas() {
			final EntitySchemaContract productSchema = EntitySchema._internalBuild("product");
			final EntitySchemaContract categorySchema = EntitySchema._internalBuild("category");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchemas()).thenReturn(
				java.util.List.of(productSchema, categorySchema)
			);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(productSchema));
			Mockito.when(baseProvider.getEntitySchema("category")).thenReturn(Optional.of(categorySchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			accessor.removeEntitySchema("product");

			final Collection<EntitySchemaContract> schemas = accessor.getEntitySchemas();
			assertEquals(1, schemas.size());
			assertEquals("category", schemas.iterator().next().getName());
		}
	}

	@Nested
	@DisplayName("Get entity schema by name")
	class GetEntitySchemaByName {

		@Test
		@DisplayName("should return empty for unknown schema")
		void shouldReturnEmptyForUnknownSchema() {
			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			assertTrue(accessor.getEntitySchema("nonExistent").isEmpty());
		}

		@Test
		@DisplayName("should delegate to base accessor when no local changes")
		void shouldDelegateToBaseAccessor() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final Optional<EntitySchemaContract> result = accessor.getEntitySchema("product");

			assertTrue(result.isPresent());
			assertSame(baseSchema, result.orElseThrow());
		}

		@Test
		@DisplayName("should prefer local schema over base schema")
		void shouldPreferLocalOverBase() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			final EntitySchemaContract localSchema = EntitySchema._internalBuild("product");
			accessor.addUpsertedEntitySchema(localSchema);

			final Optional<EntitySchemaContract> result = accessor.getEntitySchema("product");
			assertTrue(result.isPresent());
			assertSame(localSchema, result.orElseThrow());
		}

		@Test
		@DisplayName("should return empty for removed schema")
		void shouldReturnEmptyForRemovedSchema() {
			final EntitySchemaContract baseSchema = EntitySchema._internalBuild("product");
			final EntitySchemaProvider baseProvider = Mockito.mock(EntitySchemaProvider.class);
			Mockito.when(baseProvider.getEntitySchema("product")).thenReturn(Optional.of(baseSchema));

			final MutationEntitySchemaAccessor accessor = new MutationEntitySchemaAccessor(baseProvider);
			accessor.removeEntitySchema("product");

			assertTrue(accessor.getEntitySchema("product").isEmpty());
		}
	}
}
