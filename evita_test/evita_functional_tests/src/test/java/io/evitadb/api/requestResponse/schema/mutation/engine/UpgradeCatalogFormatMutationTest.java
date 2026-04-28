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

import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test verifies {@link UpgradeCatalogFormatMutation} class.
 *
 * Coverage targets:
 *
 * - the `verifyApplicability` accept/reject decision (the only contract is "the catalog must be known to the engine"
 *   — the operator handles state-machine transitions),
 * - the `mutate` carry-through during the `OUT_OF_DATE → BEING_UPGRADED` mid-load window: the schema is forwarded
 *   unchanged when the catalog is still in memory, and `null` is returned when the schema reference is gone,
 * - operational metadata: protocol-version getters, `Operation.UPSERT`, `getProgressResultType` and `toString`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UpgradeCatalogFormatMutation tests")
public class UpgradeCatalogFormatMutationTest {

	private static final String CATALOG_NAME = "upgradableCatalog";
	private static final int FROM_PROTOCOL_VERSION = 4;
	private static final int TO_PROTOCOL_VERSION = 5;

	@Nested
	@DisplayName("verifyApplicability")
	class VerifyApplicability {

		@Test
		@DisplayName("should accept catalog known to the engine regardless of its current state")
		void shouldAcceptKnownCatalog() {
			// The mutation does not branch on the current state — the operator handles the
			// `OUT_OF_DATE → BEING_UPGRADED → <prior state>` transition. The API-level check
			// only guards against unknown catalog names.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));

			assertDoesNotThrow(
				() -> new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.verifyApplicability(evita)
			);
		}

		@Test
		@DisplayName("should reject when the catalog name is unknown to the engine")
		void shouldRejectWhenCatalogIsUnknown() {
			// An upgrade for a non-existent catalog is a programming error — surface it loudly so the
			// caller can spot the dispatch typo rather than silently appending a no-op WAL entry.
			final EvitaContract evita = mock(EvitaContract.class);
			when(evita.getCatalogNames()).thenReturn(Set.of("otherCatalog"));

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.verifyApplicability(evita)
			);
			assertEquals("Catalog `" + CATALOG_NAME + "` doesn't exist!", exception.getMessage());
		}

	}

	@Nested
	@DisplayName("mutate carry-through")
	class MutateCarryThrough {

		@Test
		@DisplayName("should return null when the input schema is null")
		void shouldReturnNullWhenSchemaIsNull() {
			// Engine-level mutation: the catalog schema is unmodified. When the in-memory reference
			// is gone (e.g. the catalog has been swapped for an UnusableCatalog placeholder during
			// the OUT_OF_DATE → BEING_UPGRADED transition) the mutation receives `null` and returns
			// `null` — no synthetic schema is fabricated.
			final CatalogSchemaWithImpactOnEntitySchemas result =
				new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.mutate(null);
			assertNull(result);
		}

		@Test
		@DisplayName("should carry the schema through unchanged when input is non-null (mid-load window)")
		void shouldCarrySchemaThroughUnchangedWhenInputIsNonNull() {
			// In the transient `OUT_OF_DATE → BEING_UPGRADED` window the catalog schema may still be
			// loaded in memory; the mutation must forward it unchanged because a protocol upgrade
			// cannot reshape the schema (only the on-disk encoding changes).
			final CatalogSchemaContract inputSchema = mock(CatalogSchemaContract.class);

			final CatalogSchemaWithImpactOnEntitySchemas result =
				new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.mutate(inputSchema);

			assertNotNull(result);
			// Reference equality — the wrapped schema must be the exact same instance, not a copy.
			assertSame(inputSchema, result.updatedCatalogSchema());
			// No entity-schema impact: protocol-format upgrades do not derive entity-schema mutations.
			assertNull(result.entitySchemaMutations());
		}

	}

	@Nested
	@DisplayName("Operational metadata")
	class OperationalMetadata {

		@Test
		@DisplayName("should expose UPSERT operation for CDC consumers")
		void shouldExposeUpsertOperation() {
			// The protocol bump is reported as UPSERT — CDC consumers see it as a state replacement
			// of the catalog header rather than a delete-and-recreate.
			assertEquals(
				Operation.UPSERT,
				new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.operation()
			);
		}

		@Test
		@DisplayName("should report Void as progress result type")
		void shouldReportVoidProgressResultType() {
			// The mutation's commit-time result is empty — surfacing `Void.class` lets the
			// progress-tracking infrastructure short-circuit serialization of an absent payload.
			assertEquals(
				Void.class,
				new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION)
					.getProgressResultType()
			);
		}

		@Test
		@DisplayName("should expose protocol versions and catalog name via accessors")
		void shouldExposeProtocolVersionsAndCatalogName() {
			// The protocol version fields are captured purely for observability (CDC + progress
			// reporting). Asserting both ends of the upgrade range guards the public API surface
			// against accidental lombok rename / field-order shuffling.
			final UpgradeCatalogFormatMutation mutation =
				new UpgradeCatalogFormatMutation(CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION);

			assertEquals(CATALOG_NAME, mutation.getCatalogName());
			assertEquals(FROM_PROTOCOL_VERSION, mutation.getFromProtocolVersion());
			assertEquals(TO_PROTOCOL_VERSION, mutation.getToProtocolVersion());
		}

		@Test
		@DisplayName("should produce a descriptive toString embedding catalog name and both protocol versions")
		void shouldProduceDescriptiveToString() {
			// `toString` surfaces in CDC stream entries and operator logs — the catalog name and
			// both protocol versions must appear so the entry pinpoints the migration without
			// cross-referencing a transaction id.
			final String text = new UpgradeCatalogFormatMutation(
				CATALOG_NAME, FROM_PROTOCOL_VERSION, TO_PROTOCOL_VERSION
			).toString();
			assertEquals(
				"Upgrade catalog `" + CATALOG_NAME + "` format from protocol v"
					+ FROM_PROTOCOL_VERSION + " to v" + TO_PROTOCOL_VERSION,
				text
			);
		}

	}

}
