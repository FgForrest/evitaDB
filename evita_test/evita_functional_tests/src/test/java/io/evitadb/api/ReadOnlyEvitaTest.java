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

package io.evitadb.api;

import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test contains integration tests for read-only {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
class ReadOnlyEvitaTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	/**
	 * Deliberately different from the built-in default conflict resolution, so that the engine
	 * settings assertion proves the value is read from the configuration and not hard-coded.
	 */
	private static final ConflictPolicy CONFLICT_POLICY = ConflictPolicy.COLLECTION;
	/**
	 * Likewise different from the built-in default, so the reported capability cannot pass by
	 * accident.
	 */
	private static final boolean TIME_TRAVEL_ENABLED = true;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("ReadOnlyEvitaTest");
		this.evita = new Evita(
			getEvitaConfiguration(false)
		);
		this.evita.defineCatalog(TEST_CATALOG);
		/* first update the catalog the standard way */
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.withDescription("Test")
					.updateVia(session);
				session.createNewEntity(Entities.PRODUCT)
					.setAttribute(ATTRIBUTE_NAME, "someProduct")
					.upsertVia(session);
				session.goLiveAndClose();
			}
		);
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration(true)
		);
		this.evita.waitUntilFullyInitialized();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	void shouldFailToCreateCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.defineCatalog("differentCatalog"));
	}

	@Test
	void shouldFailToDropExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.deleteCatalogIfExists(TEST_CATALOG));
	}

	@Test
	void shouldFailToRenameExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.renameCatalog(TEST_CATALOG, "differentCatalog"));
	}

	@Test
	void shouldFailToUpdateExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema));
	}

	@Test
	void shouldFailToCreateReadWriteSessionToExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.createReadWriteSession(TEST_CATALOG));
	}

	@Test
	void shouldFailToCreateReadWriteSessionViaTraitsToExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> this.evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE)));
	}

	@Test
	void shouldAllowToQueryExistingCatalog() {
		assertNotNull(this.evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema));
	}

	@Test
	@Tag(MANAGEMENT)
	void shouldFailToProvideFullConfigurationInReadOnlyMode() {
		assertThrows(ReadOnlyException.class, () -> this.evita.management().getConfiguration());
	}

	@Test
	@Tag(MANAGEMENT)
	void shouldProvideEngineSettingsInReadOnlyMode() {
		// contrary to the full configuration, the curated engine settings must stay readable in
		// read-only mode - clients rely on them to interpret the conflict resolution behaviour
		// of the server they talk to
		final EngineSettings engineSettings = this.evita.management().getEngineSettings();
		assertEquals(CONFLICT_POLICY, engineSettings.conflictResolution().policy());
		// capability flags must reflect the configuration this instance was booted with
		assertEquals(TIME_TRAVEL_ENABLED, engineSettings.timeTravelEnabled());
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(boolean readOnly) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.readOnly(readOnly)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.timeTravelEnabled(TIME_TRAVEL_ENABLED)
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.conflictResolution(CONFLICT_POLICY)
					.build()
			)
			.build();
	}

}
