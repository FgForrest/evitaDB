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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link RemoveEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("RemoveEntitySchemaMutation")
class RemoveEntitySchemaMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should remove entity schema from accessor")
		void shouldRemoveEntitySchema() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);

			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
			Mockito.when(entitySchema.getName()).thenReturn("entityName");
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getEntitySchema("entityName")).thenReturn(of(entitySchema));

			final MutationEntitySchemaAccessor entitySchemaAccessor = new MutationEntitySchemaAccessor(catalogSchema);
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, entitySchemaAccessor);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertEquals(1, newCatalogSchema.version());

			assertFalse(entitySchemaAccessor.getEntitySchema("entityName").isPresent());
		}

		@Test
		@DisplayName("should throw when entity schema not found")
		void shouldThrowWhenEntitySchemaNotFound() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("nonExistent");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			final MutationEntitySchemaAccessor entitySchemaAccessor = new MutationEntitySchemaAccessor(catalogSchema);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> mutation.mutate(catalogSchema, entitySchemaAccessor)
			);
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should return null")
		void shouldReturnNull() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class),
				Mockito.mock(EntitySchemaContract.class)
			);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return REMOVE operation")
		void shouldReturnRemoveOperation() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			assertEquals(Operation.REMOVE, mutation.operation());
		}

		@Test
		@DisplayName("should return entity name as container name")
		void shouldReturnEntityNameAsContainerName() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			assertEquals("entityName", mutation.containerName());
		}

		@Test
		@DisplayName("should return entity name via getName")
		void shouldReturnEntityNameViaGetName() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			assertEquals("entityName", mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("product");
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"product", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final RemoveEntitySchemaMutation mutation = new RemoveEntitySchemaMutation("entityName");
			final String result = mutation.toString();
			assertTrue(result.contains("entityName"));
			assertTrue(result.contains("Remove"));
		}

		@Test
		@DisplayName("should be equal to mutation with same name")
		void shouldBeEqualToMutationWithSameName() {
			final RemoveEntitySchemaMutation mutation1 = new RemoveEntitySchemaMutation("entityName");
			final RemoveEntitySchemaMutation mutation2 = new RemoveEntitySchemaMutation("entityName");
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different name")
		void shouldNotBeEqualToMutationWithDifferentName() {
			final RemoveEntitySchemaMutation mutation1 = new RemoveEntitySchemaMutation("entity1");
			final RemoveEntitySchemaMutation mutation2 = new RemoveEntitySchemaMutation("entity2");
			assertNotEquals(mutation1, mutation2);
		}
	}
}
