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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionLayer;
import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the conflict exception surfaces the resolved conflict-resolution diagnostics — the policy in
 * force and the schema layer it came from — both through its getters and folded into the message so the
 * detail survives across the client boundary.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictingCatalogMutationException")
@Tag(CONTRACT)
@Tag(TRANSACTION)
class ConflictingCatalogMutationExceptionTest {

	private static final EntityConflictKey CONFLICT_KEY = new EntityConflictKey("Product", 42);

	@Test
	@DisplayName("should expose the resolved policy and layer through getters")
	void shouldExposeResolvedPolicyAndLayerThroughGetters() {
		final ConflictResolution resolution = new ConflictResolution(
			ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
		);
		final ConflictingCatalogMutationException ex = new ConflictingCatalogMutationException(
			"testCatalog", CONFLICT_KEY, 128L, resolution, ConflictResolutionLayer.CATALOG_SCHEMA
		);
		assertEquals(resolution, ex.getResolvedConflictResolution());
		assertEquals(ConflictResolutionLayer.CATALOG_SCHEMA, ex.getResolutionLayer());
	}

	@Test
	@DisplayName("should fold the resolved policy, granularity and layer into the message")
	void shouldFoldDiagnosticsIntoMessage() {
		final ConflictResolution resolution = new ConflictResolution(
			ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
		);
		final ConflictingCatalogMutationException ex = new ConflictingCatalogMutationException(
			"testCatalog", CONFLICT_KEY, 128L, resolution, ConflictResolutionLayer.ENTITY_SCHEMA
		);
		final String message = ex.getMessage();
		// the base conflict preamble is preserved
		assertTrue(message.contains("testCatalog"), message);
		assertTrue(message.contains("128"), message);
		// the diagnostics are appended
		assertTrue(message.contains(ConflictPolicy.ENTITY.name()), message);
		assertTrue(message.contains(GranularConflictPolicy.ENTITY_ATTRIBUTE.name()), message);
		assertTrue(message.contains(ConflictResolutionLayer.ENTITY_SCHEMA.name()), message);
	}

	@Test
	@DisplayName("should omit the granular refinement clause when the resolution is not granular")
	void shouldOmitGranularClauseWhenNotGranular() {
		final ConflictResolution resolution = new ConflictResolution(ConflictPolicy.ENTITY);
		final ConflictingCatalogMutationException ex = new ConflictingCatalogMutationException(
			"testCatalog", CONFLICT_KEY, 7L, resolution, ConflictResolutionLayer.ENGINE_DEFAULT
		);
		assertTrue(ex.getMessage().contains(ConflictResolutionLayer.ENGINE_DEFAULT.name()), ex.getMessage());
		assertTrue(!ex.getMessage().contains("granular refinement"), ex.getMessage());
	}

	@Test
	@DisplayName("should leave the diagnostics null when built without resolution context")
	void shouldLeaveDiagnosticsNullWhenBuiltWithoutContext() {
		final ConflictingCatalogMutationException ex = new ConflictingCatalogMutationException(
			"testCatalog", CONFLICT_KEY, 7L
		);
		assertNull(ex.getResolvedConflictResolution());
		assertNull(ex.getResolutionLayer());
	}

}
