/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.schema;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises a catalog schema batch whose `ModifyEntitySchemaMutation` names an entity type that does not exist yet,
 * so that the catalog creates the collection implicitly (the catalog evolution mode allows adding entity types).
 *
 * The implicit creation must behave like an explicit `CreateEntitySchemaMutation` placed before the modification:
 * the catalog schema version advances by one for the creation and one for the modification, and a catalog-level
 * mutation later in the same batch is applied on top of it. Each scenario runs against a catalog in warm-up state
 * (no transaction) and in alive state (transactional).
 */
@DisplayName("Implicit entity collection creation in a catalog schema batch")
@Tag(ENGINE)
@Tag(SCHEMA)
class ImplicitEntityCollectionCreationTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "Product";
	private static final String ATTRIBUTE_NAME = "code";
	private static final String CATALOG_DESCRIPTION = "Catalog description set in the same batch.";

	private TestPaths paths;
	private Evita evita;

	/** Creates isolated storage for this test's catalog. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ImplicitEntityCollectionCreationTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/** Closes the embedded engine and removes its isolated fixture. */
	@AfterEach
	void tearDown() {
		try {
			this.evita.close();
		} finally {
			cleanupTestPaths(this.paths);
		}
	}

	/** Catalog in warm-up state - the batch is applied without a transaction. */
	@Nested
	@DisplayName("Warm-up catalog")
	class WarmUp {

		@Test
		@DisplayName("should apply a catalog mutation following an implicit collection creation")
		void shouldApplyCatalogMutationFollowingImplicitCollectionCreation() {
			assertCatalogMutationAppliedAfterImplicitCreation();
		}

		@Test
		@DisplayName("should advance the catalog schema version as an explicit creation does")
		void shouldAdvanceCatalogSchemaVersionWhenCollectionIsCreatedImplicitly() {
			assertCatalogSchemaVersionAdvancedLikeExplicitCreation();
		}
	}

	/** Catalog in alive state - the batch is applied inside the session's transaction. */
	@Nested
	@DisplayName("Alive catalog")
	@Tag(TRANSACTION)
	class Alive {

		/** Switches the catalog to the transactional state before each scenario. */
		@BeforeEach
		void goLive() {
			ImplicitEntityCollectionCreationTest.this.evita.updateCatalog(
				TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}

		@Test
		@DisplayName("should apply a catalog mutation following an implicit collection creation")
		void shouldApplyCatalogMutationFollowingImplicitCollectionCreation() {
			assertCatalogMutationAppliedAfterImplicitCreation();
		}

		@Test
		@DisplayName("should advance the catalog schema version as an explicit creation does")
		void shouldAdvanceCatalogSchemaVersionWhenCollectionIsCreatedImplicitly() {
			assertCatalogSchemaVersionAdvancedLikeExplicitCreation();
		}
	}

	/**
	 * Creates a modification of the not-yet-existing entity type that adds a single attribute to it.
	 *
	 * @return the modification that makes the catalog create the collection implicitly
	 */
	@Nonnull
	private static ModifyEntitySchemaMutation modifyNewEntityType() {
		return new ModifyEntitySchemaMutation(
			ENTITY_TYPE,
			new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null, null,
				false, false, false, true, false,
				String.class, null, 0
			)
		);
	}

	/**
	 * Sends the implicitly creating modification followed by a catalog description change as one batch and verifies
	 * that both the entity schema change and the catalog schema change are visible afterwards.
	 */
	private void assertCatalogMutationAppliedAfterImplicitCreation() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertDoesNotThrow(
					() -> session.updateCatalogSchema(
						modifyNewEntityType(),
						new ModifyCatalogSchemaDescriptionMutation(CATALOG_DESCRIPTION)
					)
				);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(CATALOG_DESCRIPTION, session.getCatalogSchema().getDescription());
				assertTrue(
					session.getEntitySchemaOrThrow(ENTITY_TYPE).getAttribute(ATTRIBUTE_NAME).isPresent(),
					"attribute `" + ATTRIBUTE_NAME + "` must be present on `" + ENTITY_TYPE + "`"
				);
			}
		);
	}

	/**
	 * Sends the implicitly creating modification alone and verifies that the collection exists afterwards and that
	 * the catalog schema version advanced by two - one for the creation and one for the modification, as an explicit
	 * `CreateEntitySchemaMutation` followed by the same modification advances it. The engine is then reopened over
	 * the same storage, so the version is also verified as the one the catalog stored.
	 */
	private void assertCatalogSchemaVersionAdvancedLikeExplicitCreation() {
		final int versionBefore = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema().version();
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(modifyNewEntityType());
			}
		);
		assertCollectionCreatedAtVersion(versionBefore + 2);

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		assertCollectionCreatedAtVersion(versionBefore + 2);
	}

	/**
	 * Verifies that the implicitly created collection carries its attribute and that the catalog schema has the
	 * expected version.
	 *
	 * @param expectedVersion the catalog schema version the batch must have produced
	 */
	private void assertCollectionCreatedAtVersion(int expectedVersion) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(
					session.getEntitySchemaOrThrow(ENTITY_TYPE).getAttribute(ATTRIBUTE_NAME).isPresent(),
					"attribute `" + ATTRIBUTE_NAME + "` must be present on `" + ENTITY_TYPE + "`"
				);
				assertEquals(expectedVersion, session.getCatalogSchema().version());
			}
		);
	}
}
