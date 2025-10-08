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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class EvitaWarmUpInsertionTest implements EvitaTestSupport {
	public static final String DIR_EVITA_TEST = "evitaWarmUpInsertionTest";
	public static final String DIR_EVITA_TEST_EXPORT = "evitaWarmUpInsertionTest_export";
	public static final String THE_ENTITY = "theEntity";
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldGenerateLoadOfDataInWarmUpPhase() {
		this.evita.defineCatalog(TEST_CATALOG);

		final long start = System.nanoTime();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(THE_ENTITY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				for (int i = 0; i < 10_000_000; i++) {
					session.createNewEntity(THE_ENTITY, i + 1)
						.upsertVia(session);
					if (i % 200_000 == 0) {
						System.out.println("Inserted: " + i);
					}
				}

				session.goLiveAndClose();
			}
		);

		log.info("Set-up completed in: " + StringUtils.formatNano(System.nanoTime() - start));
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.serviceThreadPool(
						ThreadPoolOptions.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_TEST_EXPORT))
					.timeTravelEnabled(false)
					.fileSizeCompactionThresholdBytes(100_000_000)
					.minimalActiveRecordShare(0.8)
					.build()
			)
			.build();
	}

}
