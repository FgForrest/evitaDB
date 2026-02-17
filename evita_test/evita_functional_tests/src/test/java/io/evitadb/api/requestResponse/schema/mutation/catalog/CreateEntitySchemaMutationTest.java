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
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateEntitySchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CreateEntitySchemaMutation")
class CreateEntitySchemaMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should create entity schema in accessor")
		void shouldCreateEntitySchemaInAccessor() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("newEntityCollection");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			final MutationEntitySchemaAccessor schemaProvider = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, schemaProvider);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertNull(result.entitySchemaMutations());
			assertNotNull(newCatalogSchema);
			assertEquals(1, newCatalogSchema.version());

			final Collection<EntitySchemaContract> entitySchemas = schemaProvider.getEntitySchemas();
			assertEquals(1, entitySchemas.size());
			assertNotNull(schemaProvider.getEntitySchema("newEntityCollection"));
		}

		@Test
		@DisplayName("should not modify catalog schema version")
		void shouldNotModifyCatalogSchemaVersion() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("testEntity");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(5);
			final MutationEntitySchemaAccessor schemaProvider = new MutationEntitySchemaAccessor(
				Mockito.mock(EntitySchemaProvider.class)
			);
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, schemaProvider);
			assertSame(catalogSchema, result.updatedCatalogSchema());
		}
	}

	@Nested
	@DisplayName("Mutate entity schema")
	class MutateEntitySchema {

		@Test
		@DisplayName("should create entity schema with given name")
		void shouldCreateEntitySchemaWithGivenName() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
			final EntitySchemaContract result = mutation.mutate(
				Mockito.mock(CatalogSchemaContract.class), (EntitySchemaContract) null
			);
			assertNotNull(result);
			assertEquals("product", result.getName());
		}
	}

	@Nested
	@DisplayName("Validation")
	class Validation {

		@SuppressWarnings("ResultOfObjectAllocationIgnored")
		@Test
		@DisplayName("should reject invalid entity name")
		void shouldRejectInvalidEntityName() {
			assertThrows(
				InvalidClassifierFormatException.class,
				() -> new CreateEntitySchemaMutation("")
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return entity name as container name")
		void shouldReturnEntityNameAsContainerName() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
			assertEquals("product", mutation.containerName());
		}

		@Test
		@DisplayName("should return entity name via getName")
		void shouldReturnEntityNameViaGetName() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
			assertEquals("product", mutation.getName());
		}

		@Test
		@DisplayName("should return collection conflict key")
		void shouldReturnCollectionConflictKey() {
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
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
			final CreateEntitySchemaMutation mutation = new CreateEntitySchemaMutation("product");
			final String result = mutation.toString();
			assertTrue(result.contains("product"));
			assertTrue(result.contains("Create"));
		}

		@Test
		@DisplayName("should be equal to mutation with same name")
		void shouldBeEqualToMutationWithSameName() {
			final CreateEntitySchemaMutation mutation1 = new CreateEntitySchemaMutation("product");
			final CreateEntitySchemaMutation mutation2 = new CreateEntitySchemaMutation("product");
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different name")
		void shouldNotBeEqualToMutationWithDifferentName() {
			final CreateEntitySchemaMutation mutation1 = new CreateEntitySchemaMutation("product");
			final CreateEntitySchemaMutation mutation2 = new CreateEntitySchemaMutation("category");
			assertNotEquals(mutation1, mutation2);
		}
	}
}
