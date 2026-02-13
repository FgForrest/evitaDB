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
 * This test verifies {@link DisallowEvolutionModeInCatalogSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("DisallowEvolutionModeInCatalogSchemaMutation")
class DisallowEvolutionModeInCatalogSchemaMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class MutateCatalogSchema {

		@Test
		@DisplayName("should remove evolution mode")
		void shouldRemoveEvolutionMode() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getCatalogEvolutionMode())
				.thenReturn(Set.of(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
			assertNull(result.entitySchemaMutations());
			assertEquals(2, newCatalogSchema.version());
			final Set<CatalogEvolutionMode> modes = newCatalogSchema.getCatalogEvolutionMode();
			assertTrue(modes.isEmpty());
		}

		@Test
		@DisplayName("should return unchanged schema when mode not present")
		void shouldReturnUnchangedSchemaWhenModeNotPresent() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getCatalogEvolutionMode()).thenReturn(Set.of());
			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);
			assertSame(catalogSchema, result.updatedCatalogSchema());
		}

		@Test
		@DisplayName("should be constructible from Set")
		void shouldBeConstructibleFromSet() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(
					Set.of(CatalogEvolutionMode.ADDING_ENTITY_TYPES)
				);
			assertEquals(1, mutation.getEvolutionModes().size());
			assertTrue(mutation.getEvolutionModes().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES));
		}
	}

	@Nested
	@DisplayName("Contract methods")
	class ContractMethods {

		@Test
		@DisplayName("should return UPSERT operation")
		void shouldReturnUpsertOperation() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			assertEquals(Operation.UPSERT, mutation.operation());
		}

		@Test
		@DisplayName("should return catalog conflict key")
		void shouldReturnCatalogConflictKey() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
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
			final DisallowEvolutionModeInCatalogSchemaMutation mutation =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final String result = mutation.toString();
			assertTrue(result.contains("Disallow"));
			assertTrue(result.contains("ADDING_ENTITY_TYPES"));
		}

		@Test
		@DisplayName("should be equal to mutation with same modes")
		void shouldBeEqualToMutationWithSameModes() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation1 =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final DisallowEvolutionModeInCatalogSchemaMutation mutation2 =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to mutation with different modes")
		void shouldNotBeEqualToMutationWithDifferentModes() {
			final DisallowEvolutionModeInCatalogSchemaMutation mutation1 =
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES);
			final DisallowEvolutionModeInCatalogSchemaMutation mutation2 =
				new DisallowEvolutionModeInCatalogSchemaMutation();
			assertNotEquals(mutation1, mutation2);
		}
	}
}
