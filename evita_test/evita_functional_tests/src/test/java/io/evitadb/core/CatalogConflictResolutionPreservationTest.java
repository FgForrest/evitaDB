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

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the conflict resolution a catalog schema declares survives the engine paths that build the catalog
 * schema anew rather than mutating it: creating a catalog from a creation mutation that carries one, and restoring a
 * backup under a different catalog name.
 */
@DisplayName("Conflict resolution of a catalog schema survives creation and restore")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SCHEMA)
@Tag(MANAGEMENT)
class CatalogConflictResolutionPreservationTest implements EvitaTestSupport {
	/** The catalog-level override the tests declare - any policy other than the engine default does. */
	private static final ConflictResolution RESOLUTION = new ConflictResolution(ConflictPolicy.CATALOG);
	/** Name of the catalog a backup is restored under. */
	private static final String RESTORED_CATALOG = TEST_CATALOG + "_restored";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogConflictResolutionPreservation");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should keep the conflict resolution a catalog is created with")
	void shouldKeepTheConflictResolutionACatalogIsCreatedWith() {
		this.evita.applyMutation(new CreateCatalogSchemaMutation(TEST_CATALOG, RESOLUTION))
			.onCompletion()
			.toCompletableFuture()
			.join();

		assertEquals(Optional.of(RESOLUTION), readConflictResolution(TEST_CATALOG));
	}

	@Test
	@DisplayName("should keep the conflict resolution of a catalog restored under a different name")
	void shouldKeepTheConflictResolutionOfACatalogRestoredUnderADifferentName() throws IOException {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(EvitaSessionContract session) -> {
				session.getCatalogSchema()
					.openForWrite()
					.withConflictResolution(RESOLUTION)
					.updateVia(session);
			}
		);
		// the control: the source catalog holds the override the restored one is compared against
		assertEquals(Optional.of(RESOLUTION), readConflictResolution(TEST_CATALOG));

		final Path backupPath = this.evita.management()
			.backupCatalog(TEST_CATALOG, null, null, true)
			.join()
			.path(((FileSystemExportOptions) this.evita.getConfiguration().export()).getDirectory());
		try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(backupPath))) {
			this.evita.management()
				.restoreCatalog(RESTORED_CATALOG, Files.size(backupPath), inputStream)
				.getFutureResult()
				.join();
		}
		this.evita.activateCatalog(RESTORED_CATALOG);

		assertEquals(Optional.of(RESOLUTION), readConflictResolution(RESTORED_CATALOG));
	}

	/**
	 * Reads the conflict resolution the catalog schema declares.
	 *
	 * @param catalogName the name of the catalog
	 * @return the declared conflict resolution, empty when the catalog inherits the engine default
	 */
	@Nonnull
	private Optional<ConflictResolution> readConflictResolution(@Nonnull String catalogName) {
		return this.evita.queryCatalog(
			catalogName,
			(EvitaSessionContract session) -> {
				final CatalogSchemaContract catalogSchema = session.getCatalogSchema();
				return catalogSchema.getConflictResolution();
			}
		);
	}

	/**
	 * Builds the throw-away embedded configuration this test runs against.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

}
