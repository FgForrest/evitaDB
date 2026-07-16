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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionLayer;
import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.PriceConflictKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link TransactionConflictEvent} reads the three bounded labels — coarse policy, resolution
 * layer and conflict scope — off a caught {@link ConflictingCatalogMutationException}, and that the older
 * diagnostics-less exception maps to the `UNKNOWN` sentinel instead of throwing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TransactionConflictEvent")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(OBSERVABILITY)
class TransactionConflictEventTest {

	@Test
	@DisplayName("should read policy, layer and scope from the enriched conflict exception")
	void shouldReadDiagnosticsFromEnrichedException() {
		final ConflictingCatalogMutationException conflict = new ConflictingCatalogMutationException(
			"testCatalog",
			new EntityConflictKey("Product", 42),
			128L,
			new ConflictResolution(ConflictPolicy.ENTITY),
			ConflictResolutionLayer.CATALOG_SCHEMA
		);

		final TransactionConflictEvent event = new TransactionConflictEvent("testCatalog", conflict);

		assertEquals("testCatalog", event.getCatalogName());
		assertEquals(ConflictPolicy.ENTITY.name(), event.getConflictPolicy());
		assertEquals(ConflictResolutionLayer.CATALOG_SCHEMA.name(), event.getResolutionLayer());
		assertEquals("ENTITY", event.getConflictScope());
	}

	@Test
	@DisplayName("should export the coarse policy even when a granular refinement is in force")
	void shouldExportCoarsePolicyForGranularResolution() {
		final ConflictingCatalogMutationException conflict = new ConflictingCatalogMutationException(
			"testCatalog",
			new AttributeConflictKey("Product", 42, "code"),
			5L,
			new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)),
			ConflictResolutionLayer.ENTITY_SCHEMA
		);

		final TransactionConflictEvent event = new TransactionConflictEvent("testCatalog", conflict);

		assertEquals(ConflictPolicy.ENTITY.name(), event.getConflictPolicy());
		assertEquals(ConflictResolutionLayer.ENTITY_SCHEMA.name(), event.getResolutionLayer());
		assertEquals("ATTRIBUTE", event.getConflictScope());
	}

	@Test
	@DisplayName("should reflect the conflicting key granularity in the scope label")
	void shouldReflectKeyGranularityInScope() {
		final ConflictingCatalogMutationException conflict = new ConflictingCatalogMutationException(
			"testCatalog",
			new PriceConflictKey("Product", 42, 7, Currency.getInstance("EUR"), "basic"),
			9L,
			new ConflictResolution(ConflictPolicy.ENTITY),
			ConflictResolutionLayer.ENGINE_DEFAULT
		);

		assertEquals("PRICE", new TransactionConflictEvent("testCatalog", conflict).getConflictScope());
	}

	@Test
	@DisplayName("should count a commutative delta conflict with scope populated and policy/layer unknown")
	void shouldCountCommutativeConflictWithUnknownPolicyAndLayer() {
		// the commutative subclass is raised via the diagnostics-less constructor, so the delta conflict is
		// still counted (scope survives from the delta key) but the finer policy/layer breakdown is unavailable
		final ConflictingCatalogCommutativeMutationException conflict =
			new ConflictingCatalogCommutativeMutationException(
				"testCatalog",
				new AttributeDeltaConflictKey("Product", 42, new AttributeKey("count"), 5, null),
				11L,
				"The accumulated value is outside the allowed range."
			);

		final TransactionConflictEvent event = new TransactionConflictEvent("testCatalog", conflict);

		assertEquals("UNKNOWN", event.getConflictPolicy());
		assertEquals("UNKNOWN", event.getResolutionLayer());
		assertEquals("ATTRIBUTE", event.getConflictScope());
	}

	@Test
	@DisplayName("should map absent diagnostics to the UNKNOWN sentinel")
	void shouldMapAbsentDiagnosticsToUnknown() {
		final ConflictingCatalogMutationException conflict = new ConflictingCatalogMutationException(
			"testCatalog",
			new EntityConflictKey("Product", 42),
			3L
		);

		final TransactionConflictEvent event = new TransactionConflictEvent("testCatalog", conflict);

		// the single-argument constructor leaves policy and layer null - they must degrade to the sentinel
		assertEquals("UNKNOWN", event.getConflictPolicy());
		assertEquals("UNKNOWN", event.getResolutionLayer());
		// the scope is still available from the conflict key itself
		assertEquals("ENTITY", event.getConflictScope());
	}

}
