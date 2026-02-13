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

import io.evitadb.api.exception.InvalidSchemaMutationException;
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
 * This test verifies {@link ModifyEntitySchemaNameMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyEntitySchemaNameMutation")
class ModifyEntitySchemaNameMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should rename entity schema in accessor")
		void shouldMutateCatalogSchema() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", true
			);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);

			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
			Mockito.when(entitySchema.getName()).thenReturn("entityName");
			Mockito.when(entitySchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getEntitySchema("entityName")).thenReturn(of(entitySchema));

			final MutationEntitySchemaAccessor entitySchemaAccessor =
				new MutationEntitySchemaAccessor(catalogSchema);
			final CatalogSchemaWithImpactOnEntitySchemas result =
				mutation.mutate(catalogSchema, entitySchemaAccessor);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertEquals(1, newCatalogSchema.version());

			final EntitySchemaContract updatedSchema =
				entitySchemaAccessor.getEntitySchema("newEntityName").orElseThrow();
			assertEquals(2, updatedSchema.version());
		}

		@Test
		@DisplayName("should throw when entity schema not found")
		void shouldThrowWhenEntitySchemaNotFound() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"nonExistent", "newName", false
			);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchema.class);
			final MutationEntitySchemaAccessor entitySchemaAccessor =
				new MutationEntitySchemaAccessor(catalogSchema);

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
		@DisplayName("should rename entity schema")
		void shouldMutateEntitySchema() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", true
			);

			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
			Mockito.when(entitySchema.getName()).thenReturn("entityName");
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract updatedSchema =
				mutation.mutate(Mockito.mock(CatalogSchema.class), entitySchema);
			assertEquals(2, updatedSchema.version());
			assertEquals("newEntityName", updatedSchema.getName());
		}

		@Test
		@DisplayName("should return unchanged schema when new name equals current entity name")
		void shouldReturnUnchangedSchemaWhenNewNameEqualsCurrentEntityName() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "entityName", false
			);

			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchema.class);
			Mockito.when(entitySchema.getName()).thenReturn("entityName");
			Mockito.when(entitySchema.version()).thenReturn(1);

			final EntitySchemaContract result =
				mutation.mutate(Mockito.mock(CatalogSchema.class), entitySchema);
			// should return the same instance since nothing changed
			assertSame(entitySchema, result);
		}

		@Test
		@DisplayName("should throw when entity schema is null")
		void shouldThrowWhenEntitySchemaIsNull() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", false
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(Mockito.mock(CatalogSchema.class), (EntitySchemaContract) null)
			);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", false
			);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return original name as container name")
		void shouldReturnOriginalNameAsContainerName() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", false
			);
			assertEquals("entityName", mutation.containerName());
		}

		@Test
		@DisplayName("should return original name via getName")
		void shouldReturnOriginalNameViaGetName() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", false
			);
			assertEquals("entityName", mutation.getName());
		}

		@Test
		@DisplayName("should return conflict keys for both old and new names")
		void shouldReturnConflictKeysForBothNames() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"oldName", "newName", false
			);
			final List<ConflictKey> keys = new ConflictGenerationContext().withEntityType(
				"oldName", null,
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(2, keys.size());
			assertInstanceOf(CollectionConflictKey.class, keys.get(0));
			assertInstanceOf(CollectionConflictKey.class, keys.get(1));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final ModifyEntitySchemaNameMutation mutation = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", true
			);
			final String result = mutation.toString();
			assertTrue(result.contains("entityName"));
			assertTrue(result.contains("newEntityName"));
			assertTrue(result.contains("overwriteTarget=true"));
		}

		@Test
		@DisplayName("should be equal to mutation with same parameters")
		void shouldBeEqualToMutationWithSameParameters() {
			final ModifyEntitySchemaNameMutation mutation1 = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", true
			);
			final ModifyEntitySchemaNameMutation mutation2 = new ModifyEntitySchemaNameMutation(
				"entityName", "newEntityName", true
			);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different new name")
		void shouldNotBeEqualToMutationWithDifferentNewName() {
			final ModifyEntitySchemaNameMutation mutation1 = new ModifyEntitySchemaNameMutation(
				"entityName", "newName1", false
			);
			final ModifyEntitySchemaNameMutation mutation2 = new ModifyEntitySchemaNameMutation(
				"entityName", "newName2", false
			);
			assertNotEquals(mutation1, mutation2);
		}
	}
}
