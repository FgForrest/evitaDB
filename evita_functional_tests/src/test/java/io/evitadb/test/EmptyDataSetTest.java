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

package io.evitadb.test;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Example of the test with empty database.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EmptyDataSetTest implements EvitaTestSupport {
	private static final String DIR_EMPTY_DATA_SET_TEST = "emptyDataSetTest";
	private static final String DIR_EMPTY_DATA_SET_TEST_EXPORT = "emptyDataSetTest_export";
	private Evita evita;

	@BeforeEach
	void setUp() {
		// clean test directory to start from scratch
		cleanTestSubDirectoryWithRethrow(DIR_EMPTY_DATA_SET_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EMPTY_DATA_SET_TEST_EXPORT);
		// initialize the evitaDB server
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					// disable automatic session termination
					// to avoid closing sessions when you stop at breakpoint
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(-1)
						.build()
				)
				.storage(
					// point evitaDB to a test directory (temp directory)
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_EMPTY_DATA_SET_TEST))
						.exportDirectory(getTestDirectory().resolve(DIR_EMPTY_DATA_SET_TEST_EXPORT))
						.build()
				)
				.cache(
					// disable cache for tests
					CacheOptions.builder()
						.enabled(false)
						.build()
				)
				.build()
		);
		// create new empty catalog for evitaDB
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EMPTY_DATA_SET_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EMPTY_DATA_SET_TEST_EXPORT);
	}

	@Test
	void shouldWriteTest() {
		// here comes your test logic
		assertNotNull(this.evita);
	}
}
