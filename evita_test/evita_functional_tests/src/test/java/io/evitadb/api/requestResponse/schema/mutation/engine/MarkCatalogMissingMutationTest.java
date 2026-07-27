/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * This test verifies {@link MarkCatalogMissingMutation} class.
 *
 * Coverage targets:
 *
 * - the `verifyApplicability` matrix: unknown / already-MISSING (both rejected with descriptive messages) and
 *   ALIVE / INACTIVE (both accepted, since the mutation applies to any non-MISSING bucket),
 * - the `mutate` carry-through: when the schema is still in memory at the moment the mutation runs, it must be
 *   forwarded unchanged inside a `CatalogSchemaWithImpactOnEntitySchemas`; when the schema is already gone (`null`),
 *   the mutation returns `null`,
 * - operational metadata: `Operation.UPSERT`, `getProgressResultType` and `toString`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("MarkCatalogMissingMutation tests")
@Tag(CONTRACT)
@Tag(SCHEMA)
public class MarkCatalogMissingMutationTest {

	private static final String CATALOG_NAME = "doomedCatalog";

	@Nested
	@DisplayName("verifyApplicability matrix")
	class VerifyApplicability {

		@Test
		@DisplayName("should reject when the catalog name is unknown to the engine")
		void shouldRejectWhenCatalogIsUnknown() {
			// Unknown name means there is no bucket entry to move into `missingCatalogs`. The check is
			// done first via `getCatalogNames().contains(...)` so the failure message names the catalog.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of("otherCatalog"));

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> new MarkCatalogMissingMutation(CATALOG_NAME).verifyApplicability(evita)
			);
			assertEquals("Catalog `" + CATALOG_NAME + "` doesn't exist!", exception.getMessage());
		}

		@Test
		@DisplayName("should reject when the catalog is already in MISSING state")
		void shouldRejectWhenCatalogAlreadyMissing() {
			// Idempotency lives in the operator (replay parity, no-op re-apply); at the API level a
			// double-mark is treated as an operator error so callers can spot accidental double dispatch.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));
			when(evita.getCatalogState(CATALOG_NAME)).thenReturn(Optional.of(CatalogState.MISSING));

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> new MarkCatalogMissingMutation(CATALOG_NAME).verifyApplicability(evita)
			);
			assertEquals(
				"Catalog `" + CATALOG_NAME + "` is already marked as MISSING!",
				exception.getMessage()
			);
		}

		@Test
		@DisplayName("should accept catalog currently in ALIVE state")
		void shouldAcceptCatalogInAliveState() {
			// The whole purpose of the mutation is to reclassify a live (or otherwise registered)
			// catalog whose folder has gone missing — ALIVE is the most common starting bucket.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));
			when(evita.getCatalogState(CATALOG_NAME)).thenReturn(Optional.of(CatalogState.ALIVE));

			assertDoesNotThrow(
				() -> new MarkCatalogMissingMutation(CATALOG_NAME).verifyApplicability(evita)
			);
		}

		@Test
		@DisplayName("should accept catalog currently in INACTIVE state")
		void shouldAcceptCatalogInInactiveState() {
			// INACTIVE is the second supported source bucket; the operator-level test exercises the
			// transition itself, this test only documents that the API-level check does not reject it.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));
			when(evita.getCatalogState(CATALOG_NAME)).thenReturn(Optional.of(CatalogState.INACTIVE));

			assertDoesNotThrow(
				() -> new MarkCatalogMissingMutation(CATALOG_NAME).verifyApplicability(evita)
			);
		}

		@Test
		@DisplayName("should accept catalog currently in WARMING_UP state")
		void shouldAcceptCatalogInWarmingUpState() {
			// WARMING_UP is just an alternative live-bucket state. The mutation's docstring says any
			// non-MISSING state is acceptable — the only states it rejects are unknown + already-MISSING.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));
			when(evita.getCatalogState(CATALOG_NAME)).thenReturn(Optional.of(CatalogState.WARMING_UP));

			assertDoesNotThrow(
				() -> new MarkCatalogMissingMutation(CATALOG_NAME).verifyApplicability(evita)
			);
		}

	}

	@Nested
	@DisplayName("mutate carry-through")
	class MutateCarryThrough {

		@Test
		@DisplayName("should return null when the input schema is null (post-drop transient window)")
		void shouldReturnNullWhenSchemaIsNull() {
			// Engine-level mutation: when the catalog instance has already been dropped (the in-memory
			// reference is gone), the schema mutate is invoked with `null` and the mutation simply
			// returns `null` rather than fabricating a synthetic schema.
			final CatalogSchemaWithImpactOnEntitySchemas result =
				new MarkCatalogMissingMutation(CATALOG_NAME).mutate(null);
			assertNull(result);
		}

		@Test
		@DisplayName("should carry the schema through unchanged when input is non-null")
		void shouldCarrySchemaThroughUnchangedWhenInputIsNonNull() {
			// In the transient window between WAL append and the engine dropping the in-memory catalog
			// reference, the schema may still be present. The mutation must not modify it — it is a
			// pure engine-state transition, not a schema mutation.
			final CatalogSchemaContract inputSchema = mock(CatalogSchemaContract.class);

			final CatalogSchemaWithImpactOnEntitySchemas result =
				new MarkCatalogMissingMutation(CATALOG_NAME).mutate(inputSchema);

			assertNotNull(result);
			// Reference equality — the wrapped schema must be the exact same instance, not a copy.
			assertSame(inputSchema, result.updatedCatalogSchema());
			// MarkCatalogMissingMutation has no entity-schema impact — the field must be null
			// (the convenience ctor used by the mutation passes `null` for entity schema mutations).
			assertNull(result.entitySchemaMutations());
		}

	}

	@Nested
	@DisplayName("Operational metadata")
	class OperationalMetadata {

		@Test
		@DisplayName("should expose UPSERT operation for CDC consumers")
		void shouldExposeUpsertOperation() {
			// CDC consumers branch on `Operation` to decide how to materialise the change downstream;
			// MISSING is reported as UPSERT because the row-level effect is "the catalog now exists in
			// MISSING bucket" — a state replacement, not a delete.
			assertEquals(Operation.UPSERT, new MarkCatalogMissingMutation(CATALOG_NAME).operation());
		}

		@Test
		@DisplayName("should report Void as progress result type")
		void shouldReportVoidProgressResultType() {
			// The mutation has no commit-time return value — surfacing `Void.class` lets the
			// progress-tracking infrastructure short-circuit serialization of an absent payload.
			assertSame(Void.class, new MarkCatalogMissingMutation(CATALOG_NAME).getProgressResultType());
		}

		@Test
		@DisplayName("should produce a descriptive toString embedding the catalog name")
		void shouldProduceDescriptiveToString() {
			// `toString` is surfaced in CDC stream entries and operator logs — the catalog name must
			// be present so the entry is actionable without cross-referencing a transaction id.
			assertEquals(
				"Mark catalog `" + CATALOG_NAME + "` missing",
				new MarkCatalogMissingMutation(CATALOG_NAME).toString()
			);
		}

		@Test
		@DisplayName("should expose the catalog name via getCatalogName")
		void shouldExposeCatalogName() {
			// Consumers (operators, CDC, conflict-key generation) read the name through the public
			// getter — guard the exact value so a lombok rename cannot silently reshape the API.
			assertEquals(CATALOG_NAME, new MarkCatalogMissingMutation(CATALOG_NAME).getCatalogName());
		}

	}

}
