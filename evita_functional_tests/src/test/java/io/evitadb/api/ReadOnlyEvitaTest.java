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
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test contains integration tests for read-only {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ReadOnlyEvitaTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String DIR_READ_ONLY_EVITA_TEST = "readOnlyEvitaTest";
	public static final String DIR_READ_ONLY_EVITA_TEST_EXPORT = "readOnlyEvitaTest_export";
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_READ_ONLY_EVITA_TEST);
		cleanTestSubDirectory(DIR_READ_ONLY_EVITA_TEST_EXPORT);
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
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIR_READ_ONLY_EVITA_TEST);
		cleanTestSubDirectory(DIR_READ_ONLY_EVITA_TEST_EXPORT);
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

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(boolean readOnly) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.readOnly(readOnly)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_READ_ONLY_EVITA_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_READ_ONLY_EVITA_TEST_EXPORT))
					.build()
			)
			.build();
	}

}
