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
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link AllowEvolutionModeInCatalogSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("AllowEvolutionModeInCatalogSchemaMutation")
class AllowEvolutionModeInCatalogSchemaMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should add single evolution mode")
		void shouldAddSingleEvolutionMode() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertNull(result.entitySchemaMutations());
			assertEquals(2, newCatalogSchema.version());
			final Set<CatalogEvolutionMode> modes = newCatalogSchema.getCatalogEvolutionMode();
			assertEquals(1, modes.size());
			assertTrue(modes.contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
		}

		@Test
		@DisplayName("should preserve existing modes and add new one")
		void shouldPreserveExistingModesAndAddNewOne() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getCatalogEvolutionMode())
				.thenReturn(Set.of(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			// mode already present - schema should be unchanged
			assertEquals(1, newCatalogSchema.version());
		}

		@Test
		@DisplayName("should return unchanged schema when all modes already present")
		void shouldReturnUnchangedSchemaWhenModesAlreadyPresent() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getCatalogEvolutionMode())
				.thenReturn(Set.of(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			assertSame(catalogSchema, result.updatedCatalogSchema());
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return catalog conflict key")
		void shouldReturnCatalogConflictKey() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
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
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final String result = mutation.toString();
			assertTrue(result.contains("Allow"));
			assertTrue(result.contains("ADDING_ENTITY_TYPES"));
		}

		@Test
		@DisplayName("should be equal to mutation with same modes")
		void shouldBeEqualToMutationWithSameModes() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation1 =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final AllowEvolutionModeInCatalogSchemaMutation mutation2 =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different modes")
		void shouldNotBeEqualToMutationWithDifferentModes() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation1 =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final AllowEvolutionModeInCatalogSchemaMutation mutation2 =
				new AllowEvolutionModeInCatalogSchemaMutation();
			assertNotEquals(mutation1, mutation2);
		}

		@Test
		@DisplayName("should return evolution modes array")
		void shouldReturnEvolutionModesArray() {
			final AllowEvolutionModeInCatalogSchemaMutation mutation =
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogEvolutionMode[] modes = mutation.getEvolutionModes();
			assertEquals(1, modes.length);
			assertEquals(CatalogEvolutionMode.ADDING_ENTITY_TYPES, modes[0]);
		}
	}
}
