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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the schema precedence walk performed by {@link EffectiveConflictResolutionResolver} — the most
 * specific non-null {@link ConflictResolution} wins entirely (whole-record override, no field merging), in
 * the order entity schema → catalog schema → engine default.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EffectiveConflictResolutionResolver")
@Tag(CONTRACT)
@Tag(TRANSACTION)
class EffectiveConflictResolutionResolverTest {

	private static final ConflictResolution ENGINE_DEFAULT = new ConflictResolution(ConflictPolicy.ENTITY);
	private static final ConflictResolution CATALOG_LEVEL = new ConflictResolution(ConflictPolicy.CATALOG);
	private static final ConflictResolution ENTITY_LEVEL = new ConflictResolution(
		ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
	);

	/**
	 * Builds a catalog schema mock whose {@link CatalogSchemaContract#getConflictResolution()} returns the
	 * given (possibly empty) resolution.
	 */
	private static CatalogSchemaContract catalogWith(@javax.annotation.Nullable ConflictResolution resolution) {
		final CatalogSchemaContract catalog = mock(CatalogSchemaContract.class);
		when(catalog.getConflictResolution()).thenReturn(Optional.ofNullable(resolution));
		return catalog;
	}

	/**
	 * Builds an entity schema mock whose {@link EntitySchemaContract#getConflictResolution()} returns the
	 * given (possibly empty) resolution.
	 */
	private static EntitySchemaContract entityWith(@javax.annotation.Nullable ConflictResolution resolution) {
		final EntitySchemaContract entity = mock(EntitySchemaContract.class);
		when(entity.getConflictResolution()).thenReturn(Optional.ofNullable(resolution));
		return entity;
	}

	@Test
	@DisplayName("should return entity resolution when the entity schema declares one")
	void shouldReturnEntityResolutionWhenEntityDeclaresOne() {
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(CATALOG_LEVEL), entityWith(ENTITY_LEVEL), ENGINE_DEFAULT
		);
		assertSame(ENTITY_LEVEL, resolved);
	}

	@Test
	@DisplayName("should fall back to catalog resolution when the entity schema declares none")
	void shouldFallBackToCatalogWhenEntityDeclaresNone() {
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(CATALOG_LEVEL), entityWith(null), ENGINE_DEFAULT
		);
		assertSame(CATALOG_LEVEL, resolved);
	}

	@Test
	@DisplayName("should fall back to the engine default when neither schema declares a resolution")
	void shouldFallBackToEngineDefaultWhenNeitherDeclaresOne() {
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(null), entityWith(null), ENGINE_DEFAULT
		);
		// identity guard: the resolver must return the exact engine-default value it was given — this is
		// what keeps schema-aware Site A byte-identical to the old global-backed path under default config
		assertSame(ENGINE_DEFAULT, resolved);
	}

	@Test
	@DisplayName("should use catalog resolution when there is no entity schema at all")
	void shouldUseCatalogResolutionWhenNoEntitySchema() {
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(CATALOG_LEVEL), null, ENGINE_DEFAULT
		);
		assertSame(CATALOG_LEVEL, resolved);
	}

	@Test
	@DisplayName("should use the engine default when there is no entity schema and the catalog declares none")
	void shouldUseEngineDefaultWhenNoEntitySchemaAndCatalogDeclaresNone() {
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(null), null, ENGINE_DEFAULT
		);
		assertSame(ENGINE_DEFAULT, resolved);
	}

	@Test
	@DisplayName("should let the entity resolution win over the catalog resolution")
	void shouldLetEntityWinOverCatalog() {
		// whole-record override: the entity's coarse policy replaces the catalog's entirely, no merging
		final ConflictResolution resolved = EffectiveConflictResolutionResolver.resolve(
			catalogWith(CATALOG_LEVEL), entityWith(ENTITY_LEVEL), ENGINE_DEFAULT
		);
		assertEquals(ConflictPolicy.ENTITY, resolved.policy());
		assertEquals(EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE), resolved.granularity());
	}

}
