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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the restore sequence's export refusal on real storage, including the version pin's retention floor.
 * Lives beside the persistence service to read that floor without exposing it outside the package.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Restore to a version bounded by export storage")
@Tag(ENGINE)
@Tag(EXPORT)
@Tag(MANAGEMENT)
@Tag(TASK)
class CatalogRestoreToVersionExportLimitTest implements EvitaTestSupport {
	private static final int BRAND_COUNT = 5;
	private static final long SMALL_LIMIT = 512L;
	private static final long FITTING_LIMIT = 1024 * 1024L;
	private static final int OPERATION_TIMEOUT_SECONDS = 120;

	private TestPaths paths;
	private Evita evita;
	private long restoredVersion;

	/** Creates the same one-brand-per-version fixture as CatalogRestoreToVersionTest and measures its archive. */
	@BeforeEach
	void setUp() throws Exception {
		this.paths = createTestPaths(getClass().getSimpleName());
		this.evita = new Evita(configuration(FITTING_LIMIT));
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND);
		});
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		for (int i = 1; i <= BRAND_COUNT; i++) {
			final int brandId = i;
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity(Entities.BRAND, brandId));
			});
			if (i == 3) {
				this.restoredVersion = this.evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion);
			}
		}
		final FileForFetch measured = this.evita.management()
			.backupCatalog(TEST_CATALOG, null, this.restoredVersion, false)
			.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		final long archiveBytes = Files.size(measured.path(this.paths.export()));
		assertTrue(archiveBytes > SMALL_LIMIT, "the measured archive itself must exceed the small limit");
		assertTrue(archiveBytes < FITTING_LIMIT / 2, "the raised limit must have room for the archive and sidecar");
		this.evita.management().deleteFile(measured.fileId());
		reopen(SMALL_LIMIT);
	}

	/** Closes the engine before removing this test's directories. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** The returned sequence must preserve the actionable refusal and leave the target fully usable. */
	@Test
	@DisplayName("should fail cleanly when the intermediate backup does not fit")
	void shouldFailCleanlyWhenIntermediateBackupDoesNotFit() throws Exception {
		assertRestoreRefused();
	}

	/** Restarting on the same catalog storage with enough export space makes the identical request succeed. */
	@Test
	@DisplayName("should restore the requested state after raising the export limit")
	void shouldRestoreAfterRaisingExportLimit() throws Exception {
		assertRestoreRefused();
		reopen(FITTING_LIMIT);
		this.evita.management().restoreCatalogToVersion(TEST_CATALOG, null, this.restoredVersion, TEST_CATALOG)
			.getFutureResult().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertEquals(3, brandCount());
		assertEquals(Set.of(TEST_CATALOG), this.evita.getCatalogNames());
	}

	/** Checks the sequence's public error, storage cleanup, pin release and continued reads and writes. */
	private void assertRestoreRefused() throws Exception {
		final CatalogPersistenceService<?, ?, ?> service =
			((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG)).getPersistenceService();
		final DefaultCatalogPersistenceService persistence = (DefaultCatalogPersistenceService) service;
		assertEquals(-1L, persistence.getRetentionFloor(), "the fixture must start with no consumer pin");
		final long onDiskBytes = FileUtils.getDirectorySize(persistence.getCatalogStoragePath());
		final Task<?, Void> task = this.evita.management().restoreCatalogToVersion(
			TEST_CATALOG, null, this.restoredVersion, TEST_CATALOG
		);
		final ExecutionException failure = assertThrows(
			ExecutionException.class,
			() -> task.getFutureResult().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"an over-limit restore must fail through the returned task"
		);
		final String expected = "The backup of catalog `" + TEST_CATALOG + "` was stopped because it exceeded the " +
			"export storage limit of 512 B; the server would delete a larger file. No backup was created. " +
			"Increase `export.fileSystem.sizeLimitBytes` in the server configuration " +
			"(or `EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server. The catalog currently occupies " +
			StringUtils.formatByteSize(onDiskBytes) + " on disk.";
		assertEquals(expected, task.getStatus().publicExceptionMessage(),
			"the whole restore task must expose the exact actionable refusal");
		assertInstanceOf(ExportFileTooLargeException.class, failure.getCause());
		assertEquals(expected, failure.getCause().getMessage());
		assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		assertEquals(-1L, persistence.getRetentionFloor(), "the refused backup must release its version pin");
		assertEquals(BRAND_COUNT, brandCount(), "the target must keep serving all of its brands");
		assertEquals(Set.of(TEST_CATALOG), this.evita.getCatalogNames(), "no scratch catalog may remain");
		assertTrue(this.evita.management().listFilesToFetch(1, 100, Set.of()).getData().isEmpty());
		assertNoArchiveFiles(this.paths.export());
		assertNoArchiveFiles(this.paths.work());
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity(Entities.BRAND, 100));
		});
		assertEquals(BRAND_COUNT + 1, brandCount(), "the untouched target must still accept writes");
	}

	/** Checks both the export archive and metadata, including any nested local work copy. */
	private static void assertNoArchiveFiles(@Nonnull Path directory) throws IOException {
		try (final Stream<Path> files = Files.walk(directory)) {
			assertEquals(0L, files.filter(Files::isRegularFile).filter(path ->
				path.toString().endsWith(".zip") || path.toString().endsWith(FileForFetch.METADATA_EXTENSION)
			).count(), "no archive or sidecar may remain in " + directory);
		}
	}

	/** Reads the visible state through a session, rather than through storage metadata. */
	private int brandCount() {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getEntityCollectionSize(Entities.BRAND);
		});
	}

	/** Reopens the same directories, changing only the export limit. */
	private void reopen(long limit) {
		this.evita.close();
		this.evita = new Evita(configuration(limit));
		this.evita.waitUntilFullyInitialized();
	}

	/** Keeps historical versions restorable and gives every committed brand a published bootstrap record. */
	@Nonnull
	private EvitaConfiguration configuration(long limit) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(StorageOptions.builder()
				.storageDirectory(this.paths.storage()).workDirectory(this.paths.work())
				.timeTravelEnabled(true).build())
			.transaction(TransactionOptions.builder().checkpointIntervalInMillis(0).build())
			.export(FileSystemExportOptions.builder().directory(this.paths.export()).sizeLimitBytes(limit).build())
			.build();
	}
}
