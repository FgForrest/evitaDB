/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.BackupScheduleOptions;
import io.evitadb.api.configuration.BackupType;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ScheduleOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies behaviour of {@link io.evitadb.core.catalog.Catalog}.
 * It focuses on:
 * - catalog schema definition and its consistency
 * - automatic backup execution and catalog restoration from backups
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Catalog: General catalog behavior")
class CatalogTest implements EvitaTestSupport {

	public static final String DIR_CATALOG_TEST = "catalogTest";

	/**
	 * This test verifies that the catalog schema can be defined upfront and is correctly persisted and retrieved.
	 * It checks:
	 * - catalog schema definition with attributes
	 * - entity schema definition with global and local attributes
	 * - consistency of retrieved schemas and their versions
	 *
	 * @throws IOException if an error occurs during test directory cleaning or evita initialization
	 */
	@Test
	@DisplayName("should define catalog schema upfront and verify its consistency")
	void shouldDefineCatalogSchemaUpfront() throws IOException {
		cleanTestSubDirectory(DIR_CATALOG_TEST);
		try (final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_CATALOG_TEST))
						.build()
				).build()
		)) {
			evita.defineCatalog(TestConstants.TEST_CATALOG);
			evita.updateCatalog(
				TestConstants.TEST_CATALOG,
				evitaSession -> {
					evitaSession.getCatalogSchema().openForWrite()
						.withAttribute("code", String.class, whichIs -> whichIs.uniqueGlobally().sortable())
						.withAttribute("name", String.class, whichIs -> whichIs.localized().filterable().sortable())
						.updateVia(evitaSession);

					evitaSession.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute("code")
						.withGlobalAttribute("name")
						.withAttribute("catalogNumber", String.class, whichIs -> whichIs.filterable())
						.withPrice()
						.updateVia(evitaSession);
				}
			);

			evita.queryCatalog(
				TestConstants.TEST_CATALOG,
				evitaSession -> {
					final SealedCatalogSchema catalogSchema = evitaSession.getCatalogSchema();

					assertEquals(10, catalogSchema.version());
					final AttributeSchemaContract code = catalogSchema.getAttribute("code").orElseThrow();
					final AttributeSchemaContract name = catalogSchema.getAttribute("name").orElseThrow();

					final SealedEntitySchema entitySchema = evitaSession.getEntitySchema(Entities.PRODUCT)
						.orElseThrow();
					assertEquals(6, entitySchema.version());
					assertSame(code, entitySchema.getAttribute("code").orElse(null));
					assertSame(name, entitySchema.getAttribute("name").orElse(null));
					return null;
				}
			);
		}
	}

	/**
	 * This test verifies the automatic backup functionality of the catalog.
	 * It configures a backup schedule and waits for at least two backups to be created.
	 * Then it attempts to restore the catalog from each backup and verifies that:
	 * - all restored catalogs are correctly registered
	 * - restored catalogs are in the {@link io.evitadb.api.CatalogState#WARMING_UP} state
	 *
	 * @throws IOException if an error occurs during test directory cleaning or evita initialization
	 * @throws InterruptedException if the waiting for backups is interrupted
	 */
	@Tag(LONG_RUNNING_TEST)
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@Test
	@DisplayName("should execute automatic backups and allow restoration")
	void shouldExecuteAutomaticBackUps() throws IOException, InterruptedException {
		cleanTestSubDirectory(DIR_CATALOG_TEST);
		try (final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.schedule(
							ScheduleOptions.builder()
								.backup(
									List.of(
										BackupScheduleOptions.builder()
											.cron("*/3 * * * * *") // every 3 seconds
											.backupType(BackupType.SNAPSHOT)
											.retention(2)
											.build()
									)
								)
								.build()
						)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_CATALOG_TEST))
						.build()
				)
				.build()
		)) {
			evita.defineCatalog(TestConstants.TEST_CATALOG);
			evita.updateCatalog(
				TestConstants.TEST_CATALOG,
				evitaSession -> {
					evitaSession.getCatalogSchema().openForWrite()
						.withAttribute("code", String.class, whichIs -> whichIs.uniqueGlobally().sortable())
						.withAttribute("name", String.class, whichIs -> whichIs.localized().filterable().sortable())
						.updateVia(evitaSession);

					evitaSession.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute("code")
						.withGlobalAttribute("name")
						.withAttribute("catalogNumber", String.class, whichIs -> whichIs.filterable())
						.withPrice()
						.updateVia(evitaSession);
				}
			);

			do {
				Thread.sleep(500);
			} while (
				evita.management()
					.listFilesToFetch(
						1, 2,
						Set.of(TestConstants.TEST_CATALOG),
						Set.of(DefaultCatalogPersistenceService.ORIGIN_SYSTEM_BACKUP_TASK)
					)
					.getTotalRecordCount() < 2
			);

			// restore from all available backups
			final CompletableFuture[] restores = evita.management()
				.listFilesToFetch(
					1, 2,
					Set.of(TestConstants.TEST_CATALOG),
					Set.of(DefaultCatalogPersistenceService.ORIGIN_SYSTEM_BACKUP_TASK)
				).getData()
				.stream()
				.map(backup -> {
					     final String theCatalogName = "restored_" + backup.fileId();
					     return evita.management().restoreCatalog(
							     theCatalogName,
							     backup.fileId()
						     )
						     .getFutureResult()
						     .thenApply(__ -> evita.activateCatalogWithProgress(theCatalogName));
				     }
				).toArray(CompletableFuture[]::new);

			// wait until all restores are finished
			CompletableFuture.allOf(restores).join();

			// verify that all catalogs are in warming up state
			final Set<String> catalogNames = evita.getCatalogNames();
			assertEquals(3, catalogNames.size());
			for (String catalogName : catalogNames) {
				assertEquals(CatalogState.WARMING_UP, evita.getCatalogState(catalogName).orElse(null));
			}
		}
	}

}
