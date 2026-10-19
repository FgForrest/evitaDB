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

package io.evitadb.store.catalog.task;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.BootstrapWriteOnlyFileHandle;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.serializeBootstrapRecord;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a point-in-time backup carries the storage protocol of the catalog version it backs up, not the
 * protocol of the engine that made it.
 *
 * A backup copies the storage parts of the backed-up version verbatim, so a version written before a
 * storage-protocol migration is archived in its older format. The protocol in the archived catalog header is what a
 * restore reads to decide which migrations still have to run, and the archived bootstrap record must agree with it -
 * a restore whose header claims the current protocol skips every migration in between.
 *
 * **The fixture.** No catalog written by an older engine is available offline, so the fixture reproduces the shape an
 * upgrade leaves on disk instead: an ALIVE catalog whose second version is published once more under the previous
 * protocol - its catalog header rewritten with protocol {@link #PREVIOUS_PROTOCOL} and a bootstrap record pointing at
 * it - and which then moves on under the current protocol. A backup taken at the moment of that record is exactly
 * the backup of a pre-upgrade version. The parts underneath are in the current format, so the migration's transform
 * finds nothing to rewrite (`Migration_2026_3_Test` covers the transform itself); what these tests pin is the
 * protocol decision the transform depends on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Storage protocol stamped on a point-in-time backup")
@Tag(STORAGE)
@Tag(TASK)
class BackupTaskStorageProtocolTest implements EvitaTestSupport {
	/**
	 * The protocol the fixture's pre-upgrade version is published under - the protocol of the previous release,
	 * 2026.2, whose migration a restore must still run. Named rather than derived from the current protocol: the
	 * protocol right below the current one was written by development builds only and is refused, not migrated.
	 */
	private static final int PREVIOUS_PROTOCOL = 6;
	/**
	 * Name the backup is restored under.
	 */
	private static final String RESTORED_CATALOG = TEST_CATALOG + "Restored";
	/**
	 * Generous bound on a backup or restore of a catalog holding a handful of entities.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;

	private TestPaths paths;
	private Evita evita;
	/**
	 * Timestamp of the bootstrap record publishing the pre-upgrade version, truncated to the millisecond precision
	 * the bootstrap file stores; a backup at this moment resolves to exactly that record.
	 */
	private OffsetDateTime preUpgradeMoment;

	/**
	 * Builds the fixture described in the class JavaDoc: brands 1 and 2 committed, the version holding them
	 * re-published under {@link #PREVIOUS_PROTOCOL}, then brand 3 committed under the current protocol.
	 */
	@BeforeEach
	void setUp() throws InterruptedException {
		this.paths = createTestPaths(BackupTaskStorageProtocolTest.class.getSimpleName());
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND);
		});
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		commitBrand(1);
		commitBrand(2);

		this.preUpgradeMoment = republishLastVersionUnderPreviousProtocol();

		// the next record must be strictly newer than the pre-upgrade one, or the moment would not single it out
		Thread.sleep(2);
		commitBrand(3);
		persistenceService(TEST_CATALOG).checkpoint();
	}

	@AfterEach
	void tearDown() {
		if (this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("the archived bootstrap record carries the protocol of the backed-up version")
	void shouldStampBackupWithProtocolOfBackedUpVersionWhenItPredatesMigration() throws Exception {
		final Path archive = backupAtPreUpgradeMoment();

		final Path extracted = extract(archive, this.paths.work().resolve("extracted"));
		final List<CatalogBootstrap> archivedRecords = bootstrapRecords(extracted.resolve(TEST_CATALOG));
		assertEquals(1, archivedRecords.size(), "a backup archives exactly one bootstrap record");
		assertEquals(
			PREVIOUS_PROTOCOL, archivedRecords.getFirst().storageProtocolVersion(),
			"the backed-up version was written under the previous protocol, and so is the data the archive holds"
		);
	}

	@Test
	@DisplayName("restoring the backup runs the remaining migration and yields the backed-up version's data")
	void shouldRunRemainingMigrationWhenRestoringBackupOfPreUpgradeVersion() throws Exception {
		final Path archive = backupAtPreUpgradeMoment();

		assertEquals(
			2, restoreAndCountBrands(archive), "the restored catalog must hold the two brands of the backed-up version"
		);

		final DefaultCatalogPersistenceService restored = persistenceService(RESTORED_CATALOG);
		final long restoredVersion = restored.getLastCatalogVersion();
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION,
			restored.getCatalogHeader(restoredVersion).storageProtocolVersion(),
			"once loaded, the restored catalog is on the current protocol"
		);
		// the trace of the migration: the archived record under the previous protocol, followed by the record the
		// migration published for the same version once it had advanced the header - a header that had claimed the
		// current protocol from the start would have been loaded without either
		final List<CatalogBootstrap> restoredRecords = bootstrapRecords(restored.getCatalogStoragePath());
		assertEquals(PREVIOUS_PROTOCOL, restoredRecords.getFirst().storageProtocolVersion());
		assertTrue(
			restoredRecords.stream().skip(1).anyMatch(
				it -> it.catalogVersion() == restoredRecords.getFirst().catalogVersion() &&
					it.storageProtocolVersion() == PersistenceService.STORAGE_PROTOCOL_VERSION
			),
			"the migration must have re-published the backed-up version under the current protocol: " + restoredRecords
		);
	}

	@Test
	@DisplayName("a backup of a version written under the current protocol is stamped with the current protocol")
	void shouldStampBackupWithCurrentProtocolWhenBackedUpVersionIsCurrent() throws Exception {
		// the setup has already published the version holding brand 3 under the current protocol
		final Path archive = backupAt(OffsetDateTime.now());

		final Path extracted = extract(archive, this.paths.work().resolve("extracted"));
		final List<CatalogBootstrap> archivedRecords = bootstrapRecords(extracted.resolve(TEST_CATALOG));
		assertEquals(1, archivedRecords.size(), "a backup archives exactly one bootstrap record");
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION, archivedRecords.getFirst().storageProtocolVersion(),
			"the backed-up version was written under the current protocol, and so is the data the archive holds"
		);

		assertEquals(
			3, restoreAndCountBrands(archive),
			"the restored catalog must hold the three brands of the backed-up version"
		);
		final DefaultCatalogPersistenceService restored = persistenceService(RESTORED_CATALOG);
		assertEquals(
			PersistenceService.STORAGE_PROTOCOL_VERSION,
			restored.getCatalogHeader(restored.getLastCatalogVersion()).storageProtocolVersion()
		);
		// no migration ran, so no record of the restored catalog may carry an older protocol
		final List<CatalogBootstrap> restoredRecords = bootstrapRecords(restored.getCatalogStoragePath());
		assertTrue(
			restoredRecords.stream().allMatch(
				it -> it.storageProtocolVersion() == PersistenceService.STORAGE_PROTOCOL_VERSION
			),
			"every bootstrap record of the restored catalog must carry the current protocol: " + restoredRecords
		);
	}

	/**
	 * Re-publishes the catalog's last version under {@link #PREVIOUS_PROTOCOL}: writes its catalog header once more
	 * with that protocol, flushes it, and appends a bootstrap record pointing at the flushed header - the same two
	 * writes a protocol migration makes, in the opposite direction. Everything goes through the store's own public
	 * types; the bootstrap record is appended through a handle of its own, which the append-only bootstrap file
	 * accepts next to the engine's.
	 *
	 * @return the timestamp of the appended bootstrap record
	 */
	@Nonnull
	private OffsetDateTime republishLastVersionUnderPreviousProtocol() {
		final DefaultCatalogPersistenceService persistenceService = persistenceService(TEST_CATALOG);
		persistenceService.checkpoint();
		final long version = persistenceService.getLastCatalogVersion();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService =
			persistenceService.getStoragePartPersistenceService(version);
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
			storagePartPersistenceService.getCatalogHeader(version);
		assertEquals(PersistenceService.STORAGE_PROTOCOL_VERSION, header.storageProtocolVersion());

		storagePartPersistenceService.writeCatalogHeader(
			PREVIOUS_PROTOCOL,
			version,
			persistenceService.getCatalogStoragePath(),
			header.walFileReference(),
			header.collectionFileIndex(),
			header.catalogId(),
			header.catalogName(),
			header.catalogState(),
			header.lastEntityCollectionPrimaryKey()
		);
		final OffsetIndexDescriptor flushedDescriptor = storagePartPersistenceService.flush(version);

		final Path catalogStoragePath = persistenceService.getCatalogStoragePath();
		final CatalogBootstrap lastRecord = lastOf(bootstrapRecords(catalogStoragePath));
		final OffsetDateTime timestamp = OffsetDateTime.ofInstant(
			Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault()
		);
		final StorageSettings bootstrapSettings = bootstrapStorageSettings();
		try (
			BootstrapWriteOnlyFileHandle handle = new BootstrapWriteOnlyFileHandle(
				bootstrapFile(catalogStoragePath),
				bootstrapSettings.outputBufferSize(),
				true,
				bootstrapSettings.lockTimeoutSeconds(),
				bootstrapSettings
			)
		) {
			handle.checkAndExecuteAndSync(
				"append the pre-upgrade bootstrap record",
				() -> {
				},
				output -> serializeBootstrapRecord(
					output,
					new CatalogBootstrap(
						PREVIOUS_PROTOCOL,
						version,
						lastRecord.catalogFileIndex(),
						timestamp,
						flushedDescriptor.fileLocation()
					)
				).payload(),
				(output, record) -> record
			);
		}
		return timestamp;
	}

	/**
	 * Takes a point-in-time backup of the test catalog at {@link #preUpgradeMoment}, without the WAL.
	 *
	 * @return the path of the backup archive
	 */
	@Nonnull
	private Path backupAtPreUpgradeMoment() throws Exception {
		return backupAt(this.preUpgradeMoment);
	}

	/**
	 * Takes a point-in-time backup of the test catalog at the given moment, without the WAL.
	 *
	 * @param moment the moment whose catalog version is backed up
	 * @return the path of the backup archive
	 */
	@Nonnull
	private Path backupAt(@Nonnull OffsetDateTime moment) throws Exception {
		final FileForFetch backup = this.evita.management()
			.backupCatalog(TEST_CATALOG, moment, null, false)
			.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		final Path archive = backup.path(
			((FileSystemExportOptions) this.evita.getConfiguration().export()).getDirectory()
		);
		assertTrue(archive.toFile().isFile(), "the backup archive must have been written");
		return archive;
	}

	/**
	 * Restores the backup archive as {@link #RESTORED_CATALOG}, activates it - which is where its protocol is verified
	 * and the migrations are run - and counts its brands.
	 *
	 * @param archive the backup archive to restore
	 * @return the number of brands the restored catalog holds
	 */
	private int restoreAndCountBrands(@Nonnull Path archive) throws Exception {
		try (InputStream inputStream = Files.newInputStream(archive)) {
			this.evita.management()
				.restoreCatalog(RESTORED_CATALOG, Files.size(archive), inputStream)
				.getFutureResult()
				.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		this.evita.activateCatalog(RESTORED_CATALOG);
		return this.evita.queryCatalog(
			RESTORED_CATALOG,
			session -> {
				return session.getEntityCollectionSize(Entities.BRAND);
			}
		);
	}

	/**
	 * Commits a single brand and waits for it to become visible.
	 *
	 * @param brandId primary key of the brand
	 */
	private void commitBrand(int brandId) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.BRAND, brandId));
			}
		);
	}

	/**
	 * Returns the live persistence service of the named catalog.
	 *
	 * @param catalogName the catalog
	 * @return its persistence service
	 */
	@Nonnull
	private DefaultCatalogPersistenceService persistenceService(@Nonnull String catalogName) {
		// through the wildcard: `Catalog` holds the service under the interface's parameterisation while the
		// implementation declares concrete reference types, so the direct cast is rejected at compile time
		final CatalogPersistenceService<?, ?, ?> catalogPersistenceService =
			((Catalog) this.evita.getCatalogInstanceOrThrowException(catalogName)).getPersistenceService();
		return (DefaultCatalogPersistenceService) catalogPersistenceService;
	}

	/**
	 * Reads every bootstrap record of the catalog stored in the given folder, oldest first.
	 *
	 * @param catalogStoragePath the catalog folder
	 * @return the bootstrap records
	 */
	@Nonnull
	private List<CatalogBootstrap> bootstrapRecords(@Nonnull Path catalogStoragePath) {
		try (
			Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				bootstrapFile(catalogStoragePath).getFileName().toString()
					.replace(CatalogPersistenceService.BOOT_FILE_SUFFIX, ""),
				catalogStoragePath,
				bootstrapStorageSettings()
			)
		) {
			return records.toList();
		}
	}

	/**
	 * Returns the storage settings the engine reads and writes bootstrap files with.
	 *
	 * @return the bootstrap storage settings
	 */
	@Nonnull
	private StorageSettings bootstrapStorageSettings() {
		final EvitaConfiguration configuration = this.evita.getConfiguration();
		return new StorageSettings(configuration.storage(), configuration.transaction()).modifyForBootstrapFile();
	}

	/**
	 * Returns the single bootstrap file of the catalog stored in the given folder.
	 *
	 * @param catalogStoragePath the catalog folder
	 * @return the path of its bootstrap file
	 */
	@Nonnull
	private static Path bootstrapFile(@Nonnull Path catalogStoragePath) {
		final File[] bootstrapFiles = catalogStoragePath.toFile().listFiles(
			(dir, name) -> name.endsWith(CatalogPersistenceService.BOOT_FILE_SUFFIX)
		);
		assertNotNull(bootstrapFiles);
		assertEquals(1, bootstrapFiles.length, "a catalog folder holds exactly one bootstrap file");
		return bootstrapFiles[0].toPath();
	}

	/**
	 * Returns the last element of a non-empty list.
	 *
	 * @param list the list
	 * @param <T>  the element type
	 * @return its last element
	 */
	@Nonnull
	private static <T> T lastOf(@Nonnull List<T> list) {
		return list.getLast();
	}

	/**
	 * Extracts the backup archive into the target folder.
	 *
	 * @param archive the archive to extract
	 * @param target  the folder to extract into
	 * @return the target folder
	 */
	@Nonnull
	private static Path extract(@Nonnull Path archive, @Nonnull Path target) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				final Path entryPath = target.resolve(entry.getName()).normalize();
				if (entry.isDirectory()) {
					Files.createDirectories(entryPath);
				} else {
					Files.createDirectories(entryPath.getParent());
					Files.copy(zipInputStream, entryPath);
				}
			}
		}
		return target;
	}

	/**
	 * Builds the engine configuration: time travel on, so the pre-upgrade version stays retained, and a checkpoint
	 * every round, so every commit is published by a bootstrap record of its own.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.timeTravelEnabled(true)
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.checkpointIntervalInMillis(0)
					.build()
			)
			.build();
	}

}
