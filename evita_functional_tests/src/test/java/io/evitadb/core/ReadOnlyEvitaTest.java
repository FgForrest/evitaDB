/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
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
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		SequenceService.reset();
		cleanTestSubDirectory(DIR_READ_ONLY_EVITA_TEST);
		evita = new Evita(
			getEvitaConfiguration(false)
		);
		evita.defineCatalog(TEST_CATALOG);
		/* first update the catalog the standard way */
		evita.updateCatalog(
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
		evita.close();

		evita = new Evita(
			getEvitaConfiguration(true)
		);
	}

	@Test
	void shouldFailToCreateCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.defineCatalog("differentCatalog"));
	}

	@Test
	void shouldFailToDropExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.deleteCatalogIfExists(TEST_CATALOG));
	}

	@Test
	void shouldFailToRenameExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.renameCatalog(TEST_CATALOG, "differentCatalog"));
	}

	@Test
	void shouldFailToUpdateExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema));
	}

	@Test
	void shouldFailToCreateReadWriteSessionToExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.createReadWriteSession(TEST_CATALOG));
	}

	@Test
	void shouldFailToCreateReadWriteSessionViaTraitsToExistingCatalog() {
		assertThrows(ReadOnlyException.class, () -> evita.createSession(new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE)));
	}

	@Test
	void shouldAllowToQueryExistingCatalog() {
		assertNotNull(evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema));
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
					.build()
			)
			.build();
	}

}