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

package io.evitadb.api;


import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.util.ExceptionUtils;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behavior of evitaDB when catalog is replaced while there are clients trying to query it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Evita catalog replacement under load")
@Tag(LONG_RUNNING_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EvitaReplacementFunctionalTest implements EvitaTestSupport {
	private static final String DIR_EVITA_REPLACEMENT_TEST = "evitaReplacementTest";
	private static final String DIR_EVITA_REPLACEMENT_TEST_EXPORT = "evitaReplacementTest_export";
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_REPLACEMENT_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_REPLACEMENT_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_REPLACEMENT_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_REPLACEMENT_TEST_EXPORT);
	}

	@DisplayName("Replace catalog under heavy load")
	@Test
	void shouldReplaceCatalogUnderHeavyLoad() throws InterruptedException {
		final String catalogWithSingleEntity = "catalogWithSingleEntity";
		final String catalogWithHundredEntities = "catalogWithHundredEntities";

		// create a catalog with a single entity
		this.evita.defineCatalog(catalogWithSingleEntity)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			catalogWithSingleEntity,
			session -> {
				session.createNewEntity("Brand", 1)
					.setAttribute("name", Locale.ENGLISH, "Lenovo")
					.upsertVia(session);
				session.goLiveAndClose();
			}
		);

		// create a catalog with a hundred entities
		this.evita.defineCatalog(catalogWithHundredEntities)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			catalogWithHundredEntities,
			session -> {
				for (int i = 1; i <= 100; i++) {
					session.createNewEntity("Brand", i)
						.setAttribute("name", Locale.ENGLISH, "Lenovo_" + i)
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);

		// now create multiple clients heavily querying the catalog with single entity
		final AtomicLong errors = new AtomicLong(0);
		final AtomicLong terminations = new AtomicLong(0);
		final AtomicLong queries = new AtomicLong(0);

		final int iterations = 1000;
		final int clientCount = 100;
		final CountDownLatch latch = new CountDownLatch(clientCount);
		for (int i = 0; i < clientCount; i++) {
			final int clientId = i;
			new Thread(
				() -> {
					try {
						for (int j = 0; j < iterations; j++) {
							try {
								this.evita.queryCatalog(
									catalogWithSingleEntity,
									session -> {
										final String brand = session.getEntity("Brand", 1, attributeContentAll(), dataInLocales(Locale.ENGLISH))
											.orElseThrow()
											.getAttribute("name", Locale.ENGLISH);
										assertTrue(
											"Lenovo".equals(brand) || "Lenovo_1".equals(brand),
											"Client " + clientId + " expected Lenovo or Lenovo_1 but got " + brand
										);
									}
								);
								queries.incrementAndGet();
							} catch (Exception e) {
								final List<Throwable> nestedThrowables = ExceptionUtils.findNestedThrowables(e);
								if (nestedThrowables.stream().anyMatch(InstanceTerminatedException.class::isInstance)) {
									terminations.incrementAndGet();
								} else if (nestedThrowables.stream().anyMatch(SessionBusyException.class::isInstance)) {
									terminations.incrementAndGet();
								} else {
									errors.incrementAndGet();
								}
							}
						}
					} catch (Exception e) {
						log.error("Client " + clientId + " encountered an error: " + e.getMessage(), e);
					} finally {
						latch.countDown();
					}
				}
			).start();
		}

		while (queries.get() < (iterations * clientCount) / 2) {
			Thread.sleep(10);
		}

		// now replace the catalog with a hundred entities
		this.evita.replaceCatalog(
			catalogWithHundredEntities,
			catalogWithSingleEntity
		);

		log.info("Awaiting for all clients to finish...");

		// and wait for all clients to finish
		latch.await();

		// we must not have any errors
		assertEquals(0, errors.get());

		System.out.println("Successful queries: " + queries.get());
		System.out.println("Terminations: " + terminations.get());
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
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_REPLACEMENT_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_REPLACEMENT_TEST_EXPORT))
					.maxOpenedReadHandles(100)
					.build()
			)
			.build();
	}

}
