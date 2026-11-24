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

package io.evitadb.core.engine;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.ExpandedEngineState.CatalogSwapOutcome;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the outcome [ExpandedEngineState#replaceCatalogReference(Catalog)] reports for finalized snapshots of the
 * catalog incarnation the state serves. The engine publishes a round only when the swap installed it or the state
 * already serves it, and emits a schema change only when the installation advanced the schema, so the outcome is a
 * contract the commit pipeline relies on.
 *
 * Every case swaps into a state derived by [ExpandedEngineState#withUpdatedCatalogInstance], which holds its own
 * catalog binding, so the live state of the engine is never touched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExpandedEngineState - installation of finalized catalog snapshots")
@Tag(ENGINE)
@Tag(TRANSACTION)
class ExpandedEngineStateCatalogSwapTest implements EvitaTestSupport {
	private static final String CATALOG_NAME = "catalogSwapCatalog";
	private static final String ATTRIBUTE_NAME = "name";

	private TestPaths paths;
	private Evita evita;

	/**
	 * Starts an engine with an alive catalog holding a product collection.
	 *
	 * @throws IOException if the test storage directory cannot be created
	 */
	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(ExpandedEngineStateCatalogSwapTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		this.evita.defineCatalog(CATALOG_NAME);
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Closes the engine and removes its test files.
	 */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should install a newer snapshot whose schema did not change")
	void shouldInstallANewerSnapshotOfTheSameSchema() {
		final Catalog older = liveCatalog();
		commitProduct(1);
		final Catalog newer = liveCatalog();
		final ExpandedEngineState state = this.evita.getEngineState().withUpdatedCatalogInstance(older);

		assertEquals(CatalogSwapOutcome.INSTALLED, state.replaceCatalogReference(newer));
		assertSame(newer, state.getCatalog(CATALOG_NAME).orElseThrow());
	}

	@Test
	@DisplayName("should report the schema advance of a newer snapshot that changed the schema")
	void shouldReportTheSchemaAdvanceOfANewerSnapshot() {
		final Catalog older = liveCatalog();
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(Entities.BRAND).updateVia(session);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		final Catalog newer = liveCatalog();
		assertTrue(
			newer.getSchema().version() > older.getSchema().version(),
			"precondition: the committed round must have advanced the catalog schema"
		);
		final ExpandedEngineState state = this.evita.getEngineState().withUpdatedCatalogInstance(older);

		assertEquals(CatalogSwapOutcome.INSTALLED_SCHEMA_ADVANCED, state.replaceCatalogReference(newer));
		assertSame(newer, state.getCatalog(CATALOG_NAME).orElseThrow());
	}

	@Test
	@DisplayName("should keep serving the same or a newer snapshot of the incarnation")
	void shouldKeepServingTheSameOrANewerSnapshot() {
		final Catalog older = liveCatalog();
		commitProduct(1);
		final Catalog newer = liveCatalog();
		final ExpandedEngineState state = this.evita.getEngineState().withUpdatedCatalogInstance(newer);

		assertEquals(CatalogSwapOutcome.ALREADY_SERVING, state.replaceCatalogReference(older));
		assertEquals(CatalogSwapOutcome.ALREADY_SERVING, state.replaceCatalogReference(newer));
		assertSame(newer, state.getCatalog(CATALOG_NAME).orElseThrow(), "an older snapshot must never be installed");
	}

	/**
	 * Commits a product and waits until it is visible.
	 *
	 * @param primaryKey the primary key of the product
	 */
	private void commitProduct(int primaryKey) {
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, primaryKey).setAttribute(ATTRIBUTE_NAME, "product")
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Returns the catalog instance currently in the live view.
	 *
	 * @return the live catalog
	 */
	@Nonnull
	private Catalog liveCatalog() {
		return assertInstanceOf(Catalog.class, this.evita.getCatalogInstance(CATALOG_NAME).orElseThrow());
	}
}
