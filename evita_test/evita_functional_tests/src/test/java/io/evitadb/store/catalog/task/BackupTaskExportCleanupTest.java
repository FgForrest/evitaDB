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
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises failed zip writes through real backup tasks, catalog persistence and local export storage.
 * The failing stream keeps throwing during zip finalization, leaving the handle open for discard.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Backup cleanup after a failed export write")
@Tag(STORAGE)
@Tag(TASK)
@Tag(EXPORT)
class BackupTaskExportCleanupTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;
	private Scheduler scheduler;
	private ExportFileService exports;
	private DefaultCatalogPersistenceService persistence;
	private FailingExportService failingExports;

	/**
	 * Creates a live catalog with incompressible content large enough to overflow the zip's output buffer.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(BackupTaskExportCleanupTest.class.getSimpleName());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		final byte[] payload = new byte[64 * 1024];
		new Random(42).nextBytes(payload);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND).withAttribute("payload", String.class).updateVia(session);
			session.upsertEntity(
				session.createNewEntity(Entities.BRAND, 1)
					.setAttribute("payload", Base64.getEncoder().encodeToString(payload))
			);
		});
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		final CatalogPersistenceService<?, ?, ?> service =
			((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG)).getPersistenceService();
		this.persistence = (DefaultCatalogPersistenceService) service;
		this.persistence.checkpoint();
		final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
		executor.setThreadFactory(Thread.ofPlatform().daemon(true).factory());
		this.scheduler = new Scheduler(executor);
		this.exports = new ExportFileService(
			FileSystemExportOptions.builder().directory(this.paths.work().resolve("failed-exports")).build(),
			this.scheduler
		);
		this.failingExports = new FailingExportService(this.exports);
	}

	/**
	 * Releases the real services and deletes only this test's directories.
	 */
	@AfterEach
	void tearDown() {
		this.exports.close();
		this.scheduler.shutdownNow();
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Both tasks must discard an unpublished archive when zip finalization also fails.
	 */
	@Nested
	@DisplayName("Failed writes")
	class FailedWrites {
		@Test
		@DisplayName("should remove the unfinished point-in-time archive")
		void shouldDiscardUnfinishedBackupWhenWriteFails() throws IOException {
			final BackupTask task = new BackupTask(
				TEST_CATALOG, null, null, false, bootstrap(), failingExports, persistence, null
			);
			assertFailedAndClean(task);
		}

		@Test
		@DisplayName("should remove the unfinished full archive")
		void shouldDiscardUnfinishedFullBackupWhenWriteFails() throws IOException {
			final FullBackupTask task = new FullBackupTask(TEST_CATALOG, failingExports, persistence, null);
			assertFailedAndClean(task);
		}
	}

	/**
	 * Runs the task synchronously and checks physical cleanup before checking the published-file registry.
	 *
	 * @param task the real backup task to execute
	 */
	private void assertFailedAndClean(@Nonnull ServerTask<?, FileForFetch> task) throws IOException {
		task.transitionToIssued();
		assertThrows(RuntimeException.class, task::execute);
		assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		assertThrows(CompletionException.class, () -> task.getFutureResult().join());
		assertTrue(this.failingExports.failingHandle.stream.failed, "the injected write must actually fail");
		final Path directory = this.paths.work().resolve("failed-exports");
		try (final Stream<Path> files = Files.list(directory)) {
			assertEquals(0L, files.filter(it -> it.toString().endsWith(".zip")).count(),
				"failed backup must leave no zip");
		}
		try (final Stream<Path> files = Files.list(directory)) {
			assertEquals(0L, files.filter(it -> it.toString().endsWith(FileForFetch.METADATA_EXTENSION)).count(),
				"failed backup must leave no sidecar");
		}
		assertEquals(0, this.exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
		assertTrue(this.exports.getFile(this.failingExports.handle.fileId()).isEmpty());
		assertTrue(this.failingExports.handle.fileForFetchFuture().isCancelled());
	}

	/**
	 * Reads the last published bootstrap through the storage API, without private-field access.
	 *
	 * @return the catalog version to archive
	 */
	@Nonnull
	private CatalogBootstrap bootstrap() throws IOException {
		final Path directory = this.persistence.getCatalogStoragePath();
		final String prefix;
		try (final Stream<Path> files = Files.list(directory)) {
			prefix = files.map(it -> it.getFileName().toString())
				.filter(it -> it.endsWith(PersistenceService.BOOT_FILE_SUFFIX))
				.findFirst().orElseThrow().replace(PersistenceService.BOOT_FILE_SUFFIX, "");
		}
		try (final Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
			prefix, directory,
			new StorageSettings(this.evita.getConfiguration().storage(), this.evita.getConfiguration().transaction())
				.modifyForBootstrapFile()
		)) {
			return records.toList().getLast();
		}
	}

	/**
	 * Delegates storage to the real service, replacing only the handle's write stream.
	 */
	private static class FailingExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private ExportFileHandle handle;
		private FailingHandle failingHandle;

		/**
		 * @param delegate real local export storage
		 */
		private FailingExportService(@Nonnull ExportService delegate) {
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description, @Nonnull String contentType, @Nullable String origin
		) {
			this.handle = this.delegate.storeFile(fileName, description, contentType, origin);
			this.failingHandle = new FailingHandle(this.handle);
			return this.failingHandle;
		}
	}

	/**
	 * Delegates the entire handle lifecycle, including discard, to the actual local handle.
	 */
	private static class FailingHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;
		private final FailingOutputStream stream;

		/**
		 * @param delegate real export handle
		 */
		private FailingHandle(@Nonnull ExportFileHandle delegate) {
			this.delegate = delegate;
			this.stream = new FailingOutputStream(delegate.outputStream());
		}

		@Nonnull
		@Override
		public OutputStream outputStream() {
			return this.stream;
		}
	}

	/**
	 * Writes the first 100 bytes, then fails every subsequent write, including zip finalization.
	 * Each failure has its own exception instance so try-with-resources can suppress close failures.
	 */
	private static class FailingOutputStream extends OutputStream {
		@Delegate private final OutputStream delegate;
		private int remaining = 100;
		private boolean failed;

		/**
		 * @param delegate real handle stream
		 */
		private FailingOutputStream(@Nonnull OutputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public void write(int value) throws IOException {
			write(new byte[]{(byte) value}, 0, 1);
		}

		@Override
		public void write(@Nonnull byte[] bytes) throws IOException {
			write(bytes, 0, bytes.length);
		}

		@Override
		public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
			final int accepted = Math.min(this.remaining, length);
			this.delegate.write(bytes, offset, accepted);
			this.remaining -= accepted;
			if (accepted < length) {
				this.failed = true;
				throw new IOException("Injected export write failure");
			}
		}
	}
}
