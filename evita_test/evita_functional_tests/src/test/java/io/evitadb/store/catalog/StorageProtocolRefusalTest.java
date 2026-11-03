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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.OfflineCatalogImages.StoredFile;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.evitadb.store.catalog.OfflineCatalogImages.catalogFolder;
import static io.evitadb.store.catalog.OfflineCatalogImages.filesHeldOpenIn;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.storedFiles;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a catalog header carrying a storage protocol no migration starts from - one newer than the protocol
 * this version writes - is refused when the storage format is verified, rather than read again and again, and that the
 * refused open keeps every byte of the catalog and leaves nothing open or scheduled.
 *
 * **The image.** Each image is written by the engine - a catalog holding no reference, so no migration can fail on
 * anything but the protocol - and its catalog header and bootstrap record are then stamped with the protocols of the
 * state to reproduce (see {@link OfflineCatalogImages#stamp}). The catalog is opened through the persistence service the
 * engine loads it with, with the engine stopped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Refusal of a storage protocol no migration starts from")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class StorageProtocolRefusalTest implements EvitaTestSupport {
	/**
	 * The catalog of every image.
	 */
	private static final String CATALOG = "refusedCatalog";
	/**
	 * The only entity type of the image - it references nothing.
	 */
	private static final String ENTITY_BRAND = "brand";
	/**
	 * The protocol of the last release, 2026.2.
	 */
	private static final int RELEASED_PROTOCOL = 6;
	/**
	 * A protocol this version of evitaDB does not know.
	 */
	private static final int UNKNOWN_PROTOCOL = PersistenceService.STORAGE_PROTOCOL_VERSION + 1;
	/**
	 * How long a refusal may take - an open of a catalog of two entities is far quicker; a dispatch that loops never
	 * ends.
	 */
	private static final Duration PROMPTLY = Duration.ofSeconds(30);
	/**
	 * The diagnosis the refusal of an unknown protocol gives.
	 */
	private static final String UNKNOWN_DIAGNOSIS = "neither reads nor upgrades";

	/**
	 * The directories the test allocated, removed after it.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(1);

	@AfterEach
	void tearDown() {
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * A header of a protocol newer than the current one is not below the current protocol, so the verification would
	 * not loop over it; it is refused as unknown right away, under a bootstrap record of any protocol.
	 */
	@Test
	@DisplayName("a header of a protocol newer than the current one is refused as unknown, promptly")
	void shouldRefuseHeaderOfUnknownProtocolPromptly() {
		final EvitaConfiguration configuration = writeImage("record6headerUnknown");
		stamp(configuration, CATALOG, UNKNOWN_PROTOCOL, RELEASED_PROTOCOL);
		final SortedMap<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));

		final ObsoleteStorageProtocolException refusal = assertRefused(configuration);

		assertTrue(
			refusal.getPrivateMessage().contains("storage protocol " + UNKNOWN_PROTOCOL),
			refusal.getPrivateMessage()
		);
		assertEquals(before, storedFiles(catalogFolder(configuration, CATALOG)), "every byte must be kept");
	}

	/**
	 * Opens the catalog with the engine stopped, through the persistence service the engine loads it with, and asserts
	 * that the open is refused promptly as an unknown protocol and leaves nothing behind: no task scheduled on the
	 * scheduler it was given and - where the platform lists the open file descriptors of the process - no file of the
	 * catalog folder held open. The bytes of the folder are compared by the caller.
	 *
	 * @param configuration the configuration of the engine
	 * @return the refusal
	 */
	@Nonnull
	private static ObsoleteStorageProtocolException assertRefused(@Nonnull EvitaConfiguration configuration) {
		final Path folder = catalogFolder(configuration, CATALOG);
		final CatalogFolderId folderId = new CatalogFolderId(folder.getFileName().toString());
		final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		final Scheduler scheduler = new Scheduler(executor);
		try (ExportFileService exportService = new ExportFileService(configuration.export(), scheduler)) {
			// taken after the export service scheduled its own purge, so everything that differs is the open's doing
			final Set<String> heldBefore = filesHeldOpenIn(folder);
			// the scheduler counts what it was asked to schedule; the executor's own task count is approximate - it
			// counts a worker thread that has not started yet as a running task, and drops it once the thread runs
			final long tasksBefore = scheduler.getSubmittedTaskCount();
			final ObsoleteStorageProtocolException refusal = assertTimeoutPreemptively(
				PROMPTLY,
				() -> assertThrows(
					ObsoleteStorageProtocolException.class,
					() -> {
						try (
							DefaultCatalogPersistenceService service = new DefaultCatalogPersistenceService(
								new UnusableCatalog(
									CATALOG, CatalogState.ALIVE, folderId, configuration.storage().storageDirectory(),
									CatalogFolderOperations.unsupported("The test only opens the catalog."),
									(name, folderToken, root) -> new IllegalStateException("The test never queries it.")
								),
								CATALOG, folderId, configuration.storage(), configuration.transaction(), scheduler,
								exportService
							)
						) {
							fail("The catalog must be refused, but it was opened: " + service.getLastCatalogVersion());
						}
					}
				),
				"the open of the catalog must end - a verification that reads one header again and again never does"
			);
			assertTrue(refusal.getPrivateMessage().contains(UNKNOWN_DIAGNOSIS), refusal.getPrivateMessage());
			assertEquals(
				tasksBefore, scheduler.getSubmittedTaskCount(), "the refused open must leave no task scheduled"
			);
			final Set<String> heldAfter = filesHeldOpenIn(folder);
			if (heldBefore != null && heldAfter != null) {
				heldAfter.removeAll(heldBefore);
				assertTrue(
					heldAfter.isEmpty(), "the refused open must leave no file of the catalog open: " + heldAfter
				);
			}
			return refusal;
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "the offline scheduler did not stop");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Writes the image the case stamps: a live catalog of two brands, written by the engine, which is closed again.
	 *
	 * @param label names the directories of the image
	 * @return the configuration of an engine over the image
	 */
	@Nonnull
	private EvitaConfiguration writeImage(@Nonnull String label) {
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(allocatePaths(label)).build();
		try (Evita evita = new Evita(configuration)) {
			evita.defineCatalog(CATALOG);
			evita.updateCatalog(
				CATALOG,
				session -> {
					session.defineEntitySchema(ENTITY_BRAND);
					session.upsertEntity(session.createNewEntity(ENTITY_BRAND, 1));
					session.upsertEntity(session.createNewEntity(ENTITY_BRAND, 2));
				}
			);
			evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		return configuration;
	}

	/**
	 * Allocates fresh test paths, removed after the test.
	 *
	 * @param label names the directories
	 * @return the paths
	 */
	@Nonnull
	private TestPaths allocatePaths(@Nonnull String label) {
		final TestPaths paths = createTestPaths("StorageProtocolRefusal_" + label);
		this.allocatedPaths.add(paths);
		return paths;
	}

}
