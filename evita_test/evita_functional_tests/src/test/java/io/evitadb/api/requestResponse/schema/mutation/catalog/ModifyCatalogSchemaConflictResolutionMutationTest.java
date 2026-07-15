/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ModifyCatalogSchemaConflictResolutionMutation} class - the mutation that sets the
 * catalog-level conflict resolution override.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ModifyCatalogSchemaConflictResolutionMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(TRANSACTION)
class ModifyCatalogSchemaConflictResolutionMutationTest {

	@Nested
	@DisplayName("Mutate catalog schema")
	class Mutate {

		@Test
		@DisplayName("should set conflict resolution and bump version when value changes")
		void shouldSetConflictResolution() {
			final ConflictResolution resolution = new ConflictResolution(ConflictPolicy.ENTITY);
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(resolution);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			// the mock returns Optional.empty() for getConflictResolution() by default (currently inherited)

			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);

			assertNull(result.entitySchemaMutations());
			final CatalogSchemaContract updated = result.updatedCatalogSchema();
			assertEquals(2, updated.version());
			assertEquals(resolution, updated.getConflictResolution().orElseThrow());
		}

		@Test
		@DisplayName("should clear conflict resolution to inherited when a value was set")
		void shouldClearConflictResolutionToInherited() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(null);
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.version()).thenReturn(1);
			Mockito.when(catalogSchema.getConflictResolution())
				.thenReturn(Optional.of(new ConflictResolution(ConflictPolicy.ENTITY)));

			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);

			final CatalogSchemaContract updated = result.updatedCatalogSchema();
			assertEquals(2, updated.version());
			assertTrue(updated.getConflictResolution().isEmpty());
		}

		@Test
		@DisplayName("should wrap the same schema when the requested value is unchanged")
		void shouldReturnSameSchemaWhenValueUnchanged() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.CATALOG));
			final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
			Mockito.when(catalogSchema.getConflictResolution())
				.thenReturn(Optional.of(new ConflictResolution(ConflictPolicy.CATALOG)));

			final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema);

			assertSame(catalogSchema, result.updatedCatalogSchema());
		}

		@Test
		@DisplayName("should throw schema mutation exception when the catalog schema is null")
		void shouldThrowWhenCatalogSchemaIsNull() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			// the two-parameter overload is exercised directly: the one-parameter default wraps the argument in
			// Objects.requireNonNull, which would surface a NullPointerException instead of the domain exception
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(null, Mockito.mock(EntitySchemaProvider.class))
			);
		}
	}

	@Nested
	@DisplayName("Combine with other mutations")
	class CombineWith {

		@Test
		@DisplayName("should replace previous conflict resolution mutation letting the newer value win")
		void shouldReplacePreviousMutationWithNewerValue() {
			final ConflictResolution newerResolution = new ConflictResolution(ConflictPolicy.ENTITY);
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(newerResolution);
			final ModifyCatalogSchemaConflictResolutionMutation existingMutation =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.CATALOG));

			final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), existingMutation
			);

			assertNotNull(result);
			assertNull(result.origin());
			assertNotNull(result.current());
			assertEquals(1, result.current().length);
			final ModifyCatalogSchemaConflictResolutionMutation winner =
				assertInstanceOf(ModifyCatalogSchemaConflictResolutionMutation.class, result.current()[0]);
			assertEquals(newerResolution, winner.getConflictResolution());
		}

		@Test
		@DisplayName("should return null when combined with an unrelated mutation type")
		void shouldReturnNullForUnrelatedMutation() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));

			final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), new CreateEntitySchemaMutation("product")
			);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Conflict key generation")
	class ConflictKeys {

		@Test
		@DisplayName("should generate a single catalog conflict key for the mutated catalog")
		void shouldReturnCatalogConflictKey() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final List<ConflictKey> keys = new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.NONE))
				.withCatalogName(
					"testCatalog",
					ctx -> mutation.collectConflictKeys(ctx).toList()
				);
			assertEquals(1, keys.size());
			assertInstanceOf(CatalogConflictKey.class, keys.get(0));
		}
	}

	@Nested
	@DisplayName("Equality")
	class Equality {

		@Test
		@DisplayName("should be equal to a mutation carrying the same conflict resolution")
		void shouldBeEqualForSameResolution() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation1 =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final ModifyCatalogSchemaConflictResolutionMutation mutation2 =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			assertEquals(mutation1, mutation2);
			assertEquals(mutation1.hashCode(), mutation2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to a mutation carrying a different conflict resolution")
		void shouldNotBeEqualForDifferentResolution() {
			final ModifyCatalogSchemaConflictResolutionMutation mutation1 =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.ENTITY));
			final ModifyCatalogSchemaConflictResolutionMutation mutation2 =
				new ModifyCatalogSchemaConflictResolutionMutation(new ConflictResolution(ConflictPolicy.CATALOG));
			assertNotEquals(mutation1, mutation2);
		}
	}
}
