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
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.OfflineCatalogImages.StoredFile;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.evitadb.store.catalog.OfflineCatalogImages.catalogFolder;
import static io.evitadb.store.catalog.OfflineCatalogImages.filesHeldOpenIn;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.storedFiles;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a catalog written under storage protocol 7 - an intermediate format only development builds of
 * evitaDB 2026.3 wrote, never a release - is refused rather than migrated, and that a catalog header carrying a
 * protocol no migration starts from is refused rather than read again and again.
 *
 * A development catalog is told by its **bootstrap record**: a development build interrupted between two migration
 * steps published a header of an older protocol under a record of protocol 7, so the header alone does not tell.
 * Every migration step publishes a new bootstrap record of the current protocol, so the record is checked before the
 * first migration runs, and a refused catalog keeps every byte it had. A record of the current protocol over a header
 * of an older one is legitimate - a migration of this version that published one step and failed in the next - and is
 * migrated (see `Migration_2026_3_VirtualPartitionsTest`).
 *
 * **The images.** No catalog written by a development build is available, so each image is written by the engine -
 * a catalog holding no reference, so no migration can fail on anything but the protocol - and its catalog header and
 * bootstrap record are then stamped with the protocols of the state to reproduce (see
 * {@link OfflineCatalogImages#stamp}). The catalog is opened through the persistence service the engine loads it with,
 * with the engine stopped; one case restores an archive through the engine instead, the way an operator would bring
 * a backup of a development catalog back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Refusal of storage protocol 7, written only by development builds")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@SuppressWarnings("removal") // the protocol of development builds is named by the deprecated migration
class StorageProtocolRefusalTest implements EvitaTestSupport {
	/**
	 * The catalog of every image.
	 */
	private static final String CATALOG = "refusedCatalog";
	/**
	 * The name an archive of the image is restored under.
	 */
	private static final String RESTORED_CATALOG = "restoredCatalog";
	/**
	 * The only entity type of the image - it references nothing.
	 */
	private static final String ENTITY_BRAND = "brand";
	/**
	 * The protocol development builds of 2026.3 wrote.
	 */
	private static final int DEVELOPMENT_PROTOCOL = Migration_2026_3.DEVELOPMENT_STORAGE_PROTOCOL_VERSION;
	/**
	 * The protocol of the last release before it, 2026.2.
	 */
	private static final int RELEASED_PROTOCOL = 6;
	/**
	 * The protocol this version writes.
	 */
	private static final int CURRENT_PROTOCOL = PersistenceService.STORAGE_PROTOCOL_VERSION;
	/**
	 * A protocol no version of evitaDB has written yet.
	 */
	private static final int UNKNOWN_PROTOCOL = PersistenceService.STORAGE_PROTOCOL_VERSION + 1;
	/**
	 * How long a refusal may take - an open of a catalog of two entities is far quicker; a dispatch that loops never
	 * ends.
	 */
	private static final Duration PROMPTLY = Duration.ofSeconds(30);
	/**
	 * Generous bound on a restore of a catalog holding two entities.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;
	/**
	 * The diagnosis the refusal of a development catalog gives.
	 */
	private static final String DEVELOPMENT_DIAGNOSIS =
		"was written under storage protocol " + DEVELOPMENT_PROTOCOL + ", an intermediate format written only by " +
			"development builds";
	/**
	 * What the refusal of a development catalog tells the operator to do.
	 */
	private static final String DEVELOPMENT_REMEDY =
		"restore the catalog from a backup made by evitaDB 2026.2 or older";
	/**
	 * The diagnosis the refusal of an unknown protocol gives.
	 */
	private static final String UNKNOWN_DIAGNOSIS = "neither reads nor upgrades";
	/**
	 * Marker files the store writes into a catalog folder, which an archive of the folder never holds.
	 */
	private static final Set<String> MARKER_FILES = Set.of(
		CatalogPersistenceService.RESTORE_FLAG,
		CatalogPersistenceService.PROVISIONAL_FLAG,
		CatalogPersistenceService.CATALOG_NAME_FLAG
	);

	/**
	 * The directories the test allocated, removed after it.
	 */
	private final List<TestPaths> allocatedPaths = new ArrayList<>(2);

	@AfterEach
	void tearDown() {
		this.allocatedPaths.forEach(this::cleanupTestPaths);
	}

	/**
	 * Cases of a bootstrap record a development build wrote.
	 */
	@Nested
	@DisplayName("A bootstrap record of a development build is refused before any migration")
	class DevelopmentBootstrapRecord {

		/**
		 * The state a development build left when it was interrupted between two migration steps: the header was
		 * migrated to 6, the record says 7. Without the refusal before the first migration the catalog would be
		 * migrated and accepted.
		 */
		@Test
		@DisplayName("a record of protocol 7 over a header of the released protocol 6 is refused, nothing is written")
		void shouldRefuseRecordOfDevelopmentProtocolOverHeaderOfReleasedProtocol() {
			final EvitaConfiguration configuration = writeImage("record7header6");
			final CatalogBootstrap stamped = stamp(configuration, CATALOG, RELEASED_PROTOCOL, DEVELOPMENT_PROTOCOL);
			final SortedMap<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));

			assertRefused(configuration, DEVELOPMENT_DIAGNOSIS);

			assertEquals(
				before, storedFiles(catalogFolder(configuration, CATALOG)),
				"a refused catalog keeps every byte it had - the migration of protocol 6 must not have run"
			);
			assertEquals(
				stamped, lastBootstrap(configuration, CATALOG),
				"the last bootstrap record must still be the one of protocol 7 publishing the header of protocol 6"
			);
		}

		/**
		 * The state a development build of this branch left: a header of the current protocol under a record of 7.
		 */
		@Test
		@DisplayName("a record of protocol 7 over a header of the current protocol is refused and nothing is written")
		void shouldRefuseRecordOfDevelopmentProtocolOverHeaderOfCurrentProtocol() {
			final EvitaConfiguration configuration = writeImage("record7header8");
			final CatalogBootstrap stamped = stamp(
				configuration, CATALOG, PersistenceService.STORAGE_PROTOCOL_VERSION, DEVELOPMENT_PROTOCOL
			);
			final SortedMap<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));

			assertRefused(configuration, DEVELOPMENT_DIAGNOSIS);

			assertEquals(before, storedFiles(catalogFolder(configuration, CATALOG)), "every byte must be kept");
			assertEquals(stamped, lastBootstrap(configuration, CATALOG));
		}

		/**
		 * A restore copies the bytes of an archive and the catalog then loads through the same path, so it is refused
		 * there, keeping the restored bytes.
		 */
		@Test
		@Tag(TASK)
		@DisplayName("a restored archive of such a catalog is refused when the engine loads it and keeps its bytes")
		void shouldRefuseRestoredArchiveOfDevelopmentCatalog() throws Exception {
			final EvitaConfiguration source = writeImage("restoreSource");
			stamp(source, CATALOG, RELEASED_PROTOCOL, DEVELOPMENT_PROTOCOL);
			final Path archive = archive(catalogFolder(source, CATALOG), allocatePaths("archive").work());
			final List<String> archivedContents = archivedContents(archive);

			final EvitaConfiguration target = newTestEvitaConfigurationBuilder(allocatePaths("restoreTarget")).build();
			try (Evita evita = new Evita(target)) {
				evita.waitUntilFullyInitialized();
				try (InputStream inputStream = Files.newInputStream(archive)) {
					evita.management()
						.restoreCatalog(RESTORED_CATALOG, Files.size(archive), inputStream)
						.getFutureResult()
						.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
				Throwable activationFailure = null;
				try {
					evita.activateCatalog(RESTORED_CATALOG);
				} catch (RuntimeException ex) {
					activationFailure = ex;
				}
				assertEquals(
					CatalogState.CORRUPTED, evita.getCatalogState(RESTORED_CATALOG).orElseThrow(),
					"the restored catalog must not load: " + activationFailure
				);
				final Exception queryFailure = assertThrows(
					Exception.class,
					() -> evita.queryCatalog(
						RESTORED_CATALOG,
						session -> {
							return session.getAllEntityTypes();
						}
					)
				);
				final ObsoleteStorageProtocolException refusal = findCause(
					activationFailure == null ? queryFailure : activationFailure
				);
				assertTrue(
					refusal.getPrivateMessage().contains(DEVELOPMENT_DIAGNOSIS), refusal.getPrivateMessage()
				);
			}

			final List<String> restoredContents = new ArrayList<>(4);
			for (final Entry<String, StoredFile> file :
				storedFiles(EvitaTestSupport.catalogDirectory(target.storage().storageDirectory(), RESTORED_CATALOG))
					.entrySet()) {
				if (!MARKER_FILES.contains(file.getKey())) {
					restoredContents.add(file.getValue().content());
				}
			}
			restoredContents.sort(null);
			assertEquals(
				archivedContents, restoredContents,
				"the refused catalog must hold exactly the files of the archive, byte for byte"
			);
		}
	}

	/**
	 * Cases of a catalog header whose protocol no migration starts from.
	 */
	@Nested
	@DisplayName("A catalog header no migration starts from is refused when the storage format is verified")
	class UnmigratableHeader {

		/**
		 * A header of 7 under a record of another protocol reaches the verification of the storage format, which
		 * refuses it with the diagnosis of the development protocol rather than as unknown. No release writes either
		 * state; under a record of the current protocol it is a catalog a development build created with index objects
		 * on the request of a test, which no longer loads either.
		 *
		 * @param recordStorageProtocolVersion the storage protocol of the bootstrap record
		 */
		@ParameterizedTest(name = "record of protocol {0}")
		@ValueSource(ints = {RELEASED_PROTOCOL, CURRENT_PROTOCOL})
		@DisplayName("a header of protocol 7 under a record of another protocol is refused with its own diagnosis")
		void shouldRefuseHeaderOfDevelopmentProtocolUnderRecordOfAnotherProtocol(int recordStorageProtocolVersion) {
			final EvitaConfiguration configuration = writeImage("record" + recordStorageProtocolVersion + "header7");
			stamp(configuration, CATALOG, DEVELOPMENT_PROTOCOL, recordStorageProtocolVersion);
			final SortedMap<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));

			assertRefused(configuration, DEVELOPMENT_DIAGNOSIS);

			assertEquals(before, storedFiles(catalogFolder(configuration, CATALOG)), "every byte must be kept");
		}

		/**
		 * A header of a protocol newer than the current one is not below the current protocol, so the verification
		 * would not loop over it; it is refused as unknown right away, rather than by the generic check of the
		 * bootstrap record.
		 */
		@Test
		@DisplayName("a header of a protocol newer than the current one is refused as unknown, promptly")
		void shouldRefuseHeaderOfUnknownProtocolPromptly() {
			final EvitaConfiguration configuration = writeImage("record6header9");
			stamp(configuration, CATALOG, UNKNOWN_PROTOCOL, RELEASED_PROTOCOL);
			final SortedMap<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));

			final ObsoleteStorageProtocolException refusal = assertRefused(configuration, UNKNOWN_DIAGNOSIS);

			assertTrue(
				refusal.getPrivateMessage().contains("storage protocol " + UNKNOWN_PROTOCOL),
				refusal.getPrivateMessage()
			);
			assertEquals(before, storedFiles(catalogFolder(configuration, CATALOG)), "every byte must be kept");
		}
	}

	/**
	 * Cases of the refusal both constructors loading a catalog share.
	 */
	@Nested
	@DisplayName("The shared refusal of a bootstrap record")
	class RefusalOfBootstrapRecord {

		/**
		 * The helper both loading constructors call before any migration.
		 */
		@Test
		@DisplayName("a record of protocol 7 is refused, naming the catalog, the format and the remedy")
		void shouldRefuseRecordOfDevelopmentProtocol() {
			final ObsoleteStorageProtocolException refusal = assertThrows(
				ObsoleteStorageProtocolException.class,
				() -> DefaultCatalogPersistenceService.refuseDevelopmentStorageProtocol(
					CATALOG, bootstrapRecordOf(DEVELOPMENT_PROTOCOL)
				)
			);
			final String message = refusal.getPrivateMessage();
			assertTrue(message.contains("Catalog `" + CATALOG + "`"), message);
			assertTrue(message.contains(DEVELOPMENT_DIAGNOSIS), message);
			assertTrue(message.contains(DEVELOPMENT_REMEDY), message);
			assertTrue(refusal.getPublicMessage().contains("Catalog `" + CATALOG + "`"), refusal.getPublicMessage());
		}

		/**
		 * A record of any other protocol is legitimate here - one of the current protocol over an older header is what
		 * a migration of this version leaves when it published one step and failed in the next.
		 *
		 * @param storageProtocolVersion the storage protocol of the record
		 */
		@ParameterizedTest(name = "protocol {0}")
		@ValueSource(ints = {1, 4, 5, 6, 8, 9})
		@DisplayName("a record of any other protocol is left to the verification of the catalog header")
		void shouldLeaveRecordOfAnyOtherProtocolToTheHeaderVerification(int storageProtocolVersion) {
			assertDoesNotThrow(
				() -> DefaultCatalogPersistenceService.refuseDevelopmentStorageProtocol(
					CATALOG, bootstrapRecordOf(storageProtocolVersion)
				)
			);
		}
	}

	/**
	 * Opens the catalog with the engine stopped, through the persistence service the engine loads it with, and asserts
	 * that the open is refused promptly with the given diagnosis and leaves nothing behind: no task scheduled on the
	 * scheduler it was given and - where the platform lists the open file descriptors of the process - no file of the
	 * catalog folder held open. The bytes of the folder are compared by the callers.
	 *
	 * @param configuration the configuration of the engine
	 * @param diagnosis     a part of the message the refusal must carry
	 * @return the refusal
	 */
	@Nonnull
	private static ObsoleteStorageProtocolException assertRefused(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String diagnosis
	) {
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
			assertTrue(refusal.getPrivateMessage().contains(diagnosis), refusal.getPrivateMessage());
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
	 * Writes the image every case stamps: a live catalog of two brands, written by the engine, which is closed again.
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
	 * Packs a catalog folder into an archive the way a full backup does: the folder as the first entry, every file of
	 * it after.
	 *
	 * @param catalogFolder the catalog folder
	 * @param directory     the directory to write the archive into
	 * @return the archive
	 */
	@Nonnull
	private static Path archive(@Nonnull Path catalogFolder, @Nonnull Path directory) throws IOException {
		Files.createDirectories(directory);
		final Path archive = directory.resolve(CATALOG + ".zip");
		final String folderName = catalogFolder.getFileName().toString();
		try (
			OutputStream outputStream = Files.newOutputStream(archive);
			ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)
		) {
			// the restore requires the folder to be the first entry
			zipOutputStream.putNextEntry(new ZipEntry(folderName + "/"));
			zipOutputStream.closeEntry();
			for (final String fileName : storedFiles(catalogFolder).keySet()) {
				if (MARKER_FILES.contains(fileName)) {
					// a backup archives the files of the catalog, not the labels the store keeps on its folder
					continue;
				}
				zipOutputStream.putNextEntry(new ZipEntry(folderName + "/" + fileName));
				Files.copy(catalogFolder.resolve(fileName), zipOutputStream);
				zipOutputStream.closeEntry();
			}
		}
		return archive;
	}

	/**
	 * Describes every file of an archive by its length and the digest of its content, ignoring its name - a restore
	 * renames the files after the catalog it restores.
	 *
	 * @param archive the archive
	 * @return the descriptions, sorted
	 */
	@Nonnull
	private List<String> archivedContents(@Nonnull Path archive) throws IOException {
		final Path extracted = allocatePaths("extracted").work();
		Files.createDirectories(extracted);
		final List<String> contents = new ArrayList<>(4);
		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			int index = 0;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					final Path file = extracted.resolve("entry-" + index++);
					Files.copy(zipInputStream, file);
					contents.add(StoredFile.of(file).content());
				}
			}
		}
		assertTrue(contents.size() > 1, "the archive must hold the files of the catalog: " + contents);
		contents.sort(null);
		return contents;
	}

	/**
	 * Returns a bootstrap record of the given storage protocol.
	 *
	 * @param storageProtocolVersion the storage protocol
	 * @return the record
	 */
	@Nonnull
	private static CatalogBootstrap bootstrapRecordOf(int storageProtocolVersion) {
		return new CatalogBootstrap(storageProtocolVersion, 1L, 0, OffsetDateTime.now(), new FileLocation(0L, 1));
	}

	/**
	 * Returns the refusal in the cause chain of a failure, failing the test when there is none.
	 *
	 * @param failure the failure
	 * @return the refusal
	 */
	@Nonnull
	private static ObsoleteStorageProtocolException findCause(@Nullable Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 16; depth++) {
			if (current instanceof final ObsoleteStorageProtocolException refusal) {
				return refusal;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return fail("No refusal of the storage protocol in the cause chain of " + failure, failure);
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
