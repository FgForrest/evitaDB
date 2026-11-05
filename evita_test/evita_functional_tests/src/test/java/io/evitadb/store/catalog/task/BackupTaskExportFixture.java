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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Shared real catalog and local export storage for backup cleanup and size limit contracts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
abstract class BackupTaskExportFixture implements EvitaTestSupport {
	/** Per-test catalog and work directories, removed after the services close. */
	protected TestPaths paths;
	/** Owns the live catalog and its persistence service until teardown. */
	protected Evita evita;
	/** Runs local export maintenance on daemon threads and is shut down at teardown. */
	protected Scheduler scheduler;
	/** Real local export storage, replaceable by subclasses and closed at teardown. */
	protected ExportFileService exports;
	/** Borrowed from the live catalog; its lifecycle belongs to the evita instance. */
	protected DefaultCatalogPersistenceService persistence;

	/**
	 * Creates a live catalog with incompressible content large enough to overflow the zip's output buffer.
	 */
	@BeforeEach
	void setUpCatalog() {
		this.paths = createTestPaths(getClass().getSimpleName());
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
	}

	/**
	 * Releases the real services and deletes only this test's directories.
	 */
	@AfterEach
	void tearDownCatalog() {
		this.exports.close();
		this.scheduler.shutdownNow();
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Reads the last published bootstrap through the storage API, without private-field access.
	 *
	 * @return the last published catalog bootstrap record
	 */
	@Nonnull
	protected CatalogBootstrap bootstrap() throws IOException {
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

	/** Allocates a real export handle and wraps only its write stream. */
	protected static class CancellingExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private final Runnable cancellation;
		@Nullable private CancellingHandle lastHandle;

		/** Creates a service with a deterministic cancellation hook. */
		protected CancellingExportService(@Nonnull ExportService delegate, @Nonnull Runnable cancellation) {
			this.delegate = delegate;
			this.cancellation = cancellation;
		}

		/** {@inheritDoc} */
		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description, @Nonnull String contentType, @Nullable String origin
		) {
			this.lastHandle = new CancellingHandle(
				this.delegate.storeFile(fileName, description, contentType, origin), this.cancellation
			);
			return this.lastHandle;
		}

		/** Returns the last real handle wrapped by this service, after an export has started. */
		@Nonnull
		protected CancellingHandle lastHandle() {
			return Objects.requireNonNull(this.lastHandle);
		}
	}

	/** Preserves the real handle lifecycle and cancels on its first stream write. */
	protected static class CancellingHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;
		private final Runnable cancellation;

		/** Wraps the real handle with a cancellation hook. */
		private CancellingHandle(@Nonnull ExportFileHandle delegate, @Nonnull Runnable cancellation) {
			this.delegate = delegate;
			this.cancellation = cancellation;
		}

		/** {@inheritDoc} */
		@Nonnull
		@Override
		public OutputStream outputStream() {
			return new FilterOutputStream(this.delegate.outputStream()) {
				private boolean cancelled;

				/** {@inheritDoc} */
				@Override
				public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
					if (!this.cancelled) {
						this.cancelled = true;
						CancellingHandle.this.cancellation.run();
					}
					this.out.write(bytes, offset, length);
				}
			};
		}
	}
}
