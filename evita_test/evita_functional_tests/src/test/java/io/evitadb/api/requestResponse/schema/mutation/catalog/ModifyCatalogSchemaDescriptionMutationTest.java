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
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ModifyCatalogSchemaDescriptionMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ModifyCatalogSchemaDescriptionMutation")
class ModifyCatalogSchemaDescriptionMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should set description on catalog schema")
		void shouldSetDescription() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("newDescription");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertNull(result.entitySchemaMutations());
			assertEquals(2, newCatalogSchema.version());
			assertEquals("newDescription", newCatalogSchema.getDescription());
		}

		@Test
		@DisplayName("should set null description")
		void shouldSetNullDescription() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation(null);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getDescription()).thenReturn("existingDescription");
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertEquals(2, newCatalogSchema.version());
			assertNull(newCatalogSchema.getDescription());
		}

		@Test
		@DisplayName("should return unchanged schema when description is the same")
		void shouldReturnUnchangedSchemaWhenDescriptionIsSame() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("sameDescription");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getDescription()).thenReturn("sameDescription");
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			assertSame(catalogSchema, result.updatedCatalogSchema());
		}
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous description mutation")
		void shouldReplacePreviousDescriptionMutation() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("newDescription");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
				catalogSchema, new ModifyCatalogSchemaDescriptionMutation("oldDescription")
			);
			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			assertSame(mutation, result.current()[0]);
		}

		@Test
		@DisplayName("should not combine with different mutation type")
		void shouldNotCombineWithDifferentMutationType() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("newDescription");
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
				catalogSchema, new CreateEntitySchemaMutation("product")
			);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("desc");
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return catalog conflict key")
		void shouldReturnCatalogConflictKey() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("desc");
			final List<ConflictKey> keys = new ConflictGenerationContext().withCatalogName(
				"testCatalog",
				ctx -> mutation.collectConflictKeys(ctx, Set.of()).toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(CatalogConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("should produce readable toString output")
		void shouldProduceReadableToString() {
			final ModifyCatalogSchemaDescriptionMutation mutation =
				new ModifyCatalogSchemaDescriptionMutation("test description");
			final String result = mutation.toString();
			assertTrue(result.contains("test description"));
		}

		@Test
		@DisplayName("should be equal to mutation with same description")
		void shouldBeEqualToMutationWithSameDescription() {
			final ModifyCatalogSchemaDescriptionMutation mutation1 =
				new ModifyCatalogSchemaDescriptionMutation("desc");
			final ModifyCatalogSchemaDescriptionMutation mutation2 =
				new ModifyCatalogSchemaDescriptionMutation("desc");
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different description")
		void shouldNotBeEqualToMutationWithDifferentDescription() {
			final ModifyCatalogSchemaDescriptionMutation mutation1 =
				new ModifyCatalogSchemaDescriptionMutation("desc1");
			final ModifyCatalogSchemaDescriptionMutation mutation2 =
				new ModifyCatalogSchemaDescriptionMutation("desc2");
			assertNotEquals(mutation1, mutation2);
		}
	}
}
