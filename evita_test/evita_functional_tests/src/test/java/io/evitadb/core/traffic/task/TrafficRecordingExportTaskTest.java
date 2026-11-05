/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.traffic.task;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportProgressListener;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportSummary;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportedSessionConsumer;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.export.model.ExportFileSizeLimit;
import io.evitadb.test.EvitaTestSupport;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the on-demand {@link TrafficRecordingExportTask#doExport()} contract in isolation, driving the task's
 * callable directly against a stubbed {@link TrafficRecordingEngine} and a fake {@link ExportService}:
 *
 * - a successful export streams every session the engine yields into a zip archive - sessions are packed
 *   into chunked `traffic_recording_*.bin` entries (multiple sessions per entry until the configured chunk
 *   size rolls over) plus a trailing `metadata.txt` - and returns the finished {@link FileForFetch},
 * - a failing export (the engine raises {@link IOException}) is wrapped in an {@link UnexpectedIOException}
 *   and the partially written zip already registered in the export storage is deleted so it does not leak
 *   (mirrors {@code BackupTask}'s failure-cleanup model).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRAFFIC_ENGINE)
@Tag(EXPORT)
@DisplayName("TrafficRecordingExportTask")
// a task waits an hour for its file, so an export that never publishes must fail here instead of hanging the build
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TrafficRecordingExportTaskTest implements EvitaTestSupport {
	private static final String CATALOG_NAME = "testCatalog";
	private static final long CHUNK_SIZE = 1_048_576L;

	private Path zipFile;

	@BeforeEach
	void setUp() throws IOException {
		this.zipFile = Files.createTempFile("TrafficRecordingExportTaskTest", ".zip");
	}

	@AfterEach
	void tearDown() throws IOException {
		Files.deleteIfExists(this.zipFile);
	}

	@Test
	@DisplayName("A successful export writes chunked .bin entries plus metadata and returns the finished file")
	void shouldExportSessionsAndReturnFinishedFile() throws Exception {
		final UUID fileId = UUID.randomUUID();
		// the file is published only when the sink closes the handle, which only a finished body may do
		final FakeExportFileHandle handle = new FakeExportFileHandle(fileId, this.zipFile);
		final RecordingExportService exportService = new RecordingExportService(handle);

		final TrafficRecordingEngine engine = Mockito.mock(TrafficRecordingEngine.class);
		Mockito.when(engine.exportTrafficRecording(ArgumentMatchers.any(), ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				final ExportedSessionConsumer consumer = invocation.getArgument(0);
				final ExportProgressListener progress = invocation.getArgument(1);
				// exercise both CountingOutputStream write paths: a single byte and a byte range
				consumer.accept(1L, outputStream -> {
					outputStream.write('A');
					outputStream.write(new byte[]{1, 2, 3}, 0, 3);
				});
				// total > 0 exercises the non-degenerate progress branch
				progress.onProgress(1, 1);
				return new ExportSummary(1, 4L, 0, 1);
			});

		final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
			CATALOG_NAME, CHUNK_SIZE, engine, exportService
		);

		// move the task out of WAITING_FOR_PRECONDITION into QUEUED so execute() actually runs the callable
		task.transitionToIssued();
		final FileForFetch result = task.call();

		assertEquals(fileId, result.fileId(), "A successful export must return the finished file-for-fetch.");
		assertTrue(
			exportService.deletedFileIds.isEmpty(),
			"A successful export must not delete its own finished file."
		);
		final Set<String> entries = readZipEntryNames(this.zipFile);
		assertTrue(
			entries.contains("traffic_recording_1.bin"),
			"The single exported session must be packed into the first chunk .bin entry, got " + entries
		);
		assertTrue(
			entries.contains("metadata.txt"),
			"The export must always append a trailing metadata.txt entry, got " + entries
		);
	}

	@Test
	@DisplayName("A failing export is wrapped and the partial file is deleted so it does not leak")
	void shouldDeletePartialFileWhenExportFails() throws IOException {
		final UUID fileId = UUID.randomUUID();
		final FakeExportFileHandle handle = new FakeExportFileHandle(fileId, this.zipFile);
		final RecordingExportService exportService = new RecordingExportService(handle);

		final TrafficRecordingEngine engine = Mockito.mock(TrafficRecordingEngine.class);
		Mockito.when(engine.exportTrafficRecording(ArgumentMatchers.any(), ArgumentMatchers.any()))
			.thenThrow(new IOException("simulated export failure"));

		final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
			CATALOG_NAME, CHUNK_SIZE, engine, exportService
		);

		// move the task out of WAITING_FOR_PRECONDITION into QUEUED so execute() actually runs the callable
		task.transitionToIssued();
		final UnexpectedIOException failure = assertThrows(UnexpectedIOException.class, task::call);
		assertEquals("Failed to export traffic recording.", failure.getPublicMessage());
		assertFalse(Files.exists(this.zipFile), "the open handle must be discarded");
		assertTrue(exportService.deletedFileIds.isEmpty(), "an unpublished file cannot be deleted by descriptor");
	}

	@Test
	@DisplayName("A finalization failure leaves no file and surfaces its specific error")
	void shouldDeletePartialFileAndSurfaceErrorWhenFinalizationFails() throws Exception {
		final UUID fileId = UUID.randomUUID();
		final FakeExportFileHandle handle = new FakeExportFileHandle(fileId, this.zipFile);
		// A failed upload completes its future only after close and removes its own staging file.
		handle.finalizationFailure = new IOException("simulated finalization failure");
		final RecordingExportService exportService = new RecordingExportService(handle);

		final TrafficRecordingEngine engine = Mockito.mock(TrafficRecordingEngine.class);
		Mockito.when(engine.exportTrafficRecording(ArgumentMatchers.any(), ArgumentMatchers.any()))
			.thenAnswer(invocation -> {
				final ExportedSessionConsumer consumer = invocation.getArgument(0);
				consumer.accept(1L, outputStream -> outputStream.write(new byte[]{1, 2, 3, 4}, 0, 4));
				return new ExportSummary(1, 4L, 0, 1);
			});

		final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
			CATALOG_NAME, CHUNK_SIZE, engine, exportService
		);

		// move the task out of WAITING_FOR_PRECONDITION into QUEUED so execute() actually runs the callable
		task.transitionToIssued();
		final GenericEvitaInternalError failure = assertThrows(GenericEvitaInternalError.class, task::call);
		assertEquals("Failed to retrieve the traffic recording export file after successful creation!",
			failure.getPublicMessage());
		assertFalse(Files.exists(this.zipFile), "a failed upload must remove its staging file");
		assertTrue(exportService.deletedFileIds.isEmpty(), "a failed upload has no published descriptor to delete");
	}

	/** A refusal must remove only its own unpublished archive and sidecar. */
	@Test
	@DisplayName("should refuse an oversized export and preserve an older file")
	void shouldRefuseOversizedExportAndPreserveOlderFile() throws Exception {
		final TestPaths paths = createTestPaths("traffic-export-limit");
		final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
		executor.setThreadFactory(Thread.ofPlatform().daemon(true).factory());
		final Scheduler scheduler = new Scheduler(executor);
		try (
			final FileManagementService management = new FileManagementService(
				StorageOptions.builder().workDirectory(paths.work()).build());
			final ExportFileService service = new ExportFileService(
				FileSystemExportOptions.builder().directory(paths.export()).sizeLimitBytes(1024).build(), scheduler)
		) {
			final ExportFileHandle older = service.storeFile("older.zip", null, "application/zip", null);
			older.outputStream().write(42);
			older.close();
			final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
				CATALOG_NAME, CHUNK_SIZE, engine(management, scheduler, 16384), service);
			task.transitionToIssued();
			final ExportFileTooLargeException failure = assertThrows(ExportFileTooLargeException.class, task::call);
			assertEquals("The traffic recording export of catalog `testCatalog` was stopped because it exceeded " +
				"the export storage limit of 1 KB; the server would delete a larger file. No recording was " +
				"created. Increase `export.fileSystem.sizeLimitBytes` in the server configuration " +
				"(or `EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server.", failure.getPublicMessage());
			assertEquals(List.of(older.fileForFetchFuture().join()),
				service.listFilesToFetch(1, 10, Set.of()).getData());
			try (final Stream<Path> files = Files.list(paths.export())) {
				assertEquals(2, files.filter(Files::isRegularFile)
					.filter(it -> !it.getFileName().toString().equals(".lock")).count());
			}
		} finally {
			scheduler.shutdownNow();
			cleanupTestPaths(paths);
		}
	}

	/** A close-time write failure leaves an open real handle that deleteFile cannot find. */
	@Test
	@DisplayName("should discard the unpublished file when the zip close fails")
	void shouldDiscardFileWhenZipCloseFails() throws Exception {
		final TestPaths paths = createTestPaths("traffic-export-close");
		final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
		executor.setThreadFactory(Thread.ofPlatform().daemon(true).factory());
		final Scheduler scheduler = new Scheduler(executor);
		try (
			final FileManagementService management = new FileManagementService(
				StorageOptions.builder().workDirectory(paths.work()).build());
			final ExportFileService service = new ExportFileService(
				FileSystemExportOptions.builder().directory(paths.export()).build(), scheduler)
		) {
			final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
				CATALOG_NAME, 1, engine(management, scheduler, 1, 200), new FailingWriteService(service));
			task.transitionToIssued();
			assertThrows(UnexpectedIOException.class, task::call);
			try (final Stream<Path> files = Files.list(paths.export())) {
				assertEquals(0, files.filter(Files::isRegularFile)
					.filter(it -> !it.getFileName().toString().equals(".lock")).count());
			}
		} finally {
			scheduler.shutdownNow();
			cleanupTestPaths(paths);
		}
	}

	/** Supplies deterministic incompressible bytes through the real zip machinery. */
	@Nonnull
	private static TrafficRecordingEngine engine(
		@Nonnull FileManagementService management, @Nonnull Scheduler scheduler, int payloadSize
	) {
		return engine(management, scheduler, payloadSize, 1);
	}

	/** Produces enough independent zip entries to reach the backend while writing the central directory. */
	@Nonnull
	private static TrafficRecordingEngine engine(
		@Nonnull FileManagementService management, @Nonnull Scheduler scheduler, int payloadSize, int sessionCount
	) {
		final EvitaConfiguration configuration = EvitaConfiguration.builder()
			.server(ServerOptions.builder().trafficRecording(TrafficRecordingOptions.builder().enabled(false).build())
				.build()).build();
		return new TrafficRecordingEngine(CATALOG_NAME, CatalogState.ALIVE, TracingContextProvider.getContext(),
			configuration, management, scheduler) {
			/** {@inheritDoc} */
			@Override
			@Nonnull
			public ExportSummary exportTrafficRecording(
				@Nonnull ExportedSessionConsumer consumer, @Nonnull ExportProgressListener listener
			) throws IOException {
				final byte[] payload = new byte[payloadSize];
				new Random(42).nextBytes(payload);
				for (int i = 0; i < sessionCount; i++) {
					consumer.accept(i + 1L, output -> output.write(payload));
				}
				return new ExportSummary(sessionCount, (long) payloadSize * sessionCount, 0, sessionCount);
			}
		};
	}

	/** Retains the real export lifecycle while failing a central-directory write at close. */
	private static class FailingWriteService implements ExportService {
		@Delegate private final ExportService delegate;

		/** Wraps the storage service without replacing its cleanup. */
		private FailingWriteService(@Nonnull ExportService delegate) {
			this.delegate = delegate;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public ExportFileHandle storeFile(
			@Nonnull String name, @Nullable String description, @Nonnull String type, @Nullable String origin
		) {
			return new FailingWriteHandle(this.delegate.storeFile(name, description, type, origin));
		}
	}

	/** Fails writes without closing the handle, just as a failing zip finish does. */
	private static class FailingWriteHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;

		/** Keeps discard delegated to the real handle. */
		private FailingWriteHandle(@Nonnull ExportFileHandle delegate) {
			this.delegate = delegate;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public OutputStream outputStream() {
			return new FilterOutputStream(this.delegate.outputStream()) {
				/** {@inheritDoc} */
				@Override
				public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
					final boolean finishing = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
						frame.getClassName().equals(ZipOutputStream.class.getName()) &&
							frame.getMethodName().equals("finish")));
					if (finishing) {
						throw new IOException("zip close write failed");
					}
					this.out.write(bytes, offset, length);
				}
			};
		}
	}

	@Nonnull
	private static FileForFetch fileForFetch(@Nonnull UUID fileId) {
		return new FileForFetch(
			fileId, "traffic_recording.zip", null, "application/zip",
			1L, OffsetDateTime.now(), null
		);
	}

	/**
	 * Collects the names of every entry present in the given zip file.
	 *
	 * @param zip the zip file to inspect
	 * @return the set of entry names contained in the archive
	 */
	@Nonnull
	private static Set<String> readZipEntryNames(@Nonnull Path zip) throws IOException {
		final Set<String> names = new HashSet<>();
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				names.add(entry.getName());
			}
		}
		return names;
	}

	/**
	 * Minimal {@link ExportService} that hands out a single pre-built {@link ExportFileHandle} on
	 * {@code storeFile} and records every {@code deleteFile} call so the test can assert whether the
	 * partial-file cleanup was triggered. All other operations are irrelevant to this test.
	 */
	private static class RecordingExportService implements ExportService {
		private final ExportFileHandle handle;
		private final List<UUID> deletedFileIds = new ArrayList<>();

		private RecordingExportService(@Nonnull ExportFileHandle handle) {
			this.handle = handle;
		}

		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description,
			@Nonnull String contentType, @Nullable String origin
		) {
			return this.handle;
		}

		@Override
		public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
			this.deletedFileIds.add(fileId);
		}

		@Nonnull
		@Override
		public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
			throw new UnsupportedOperationException();
		}

		@Override
		public long purgeFiles() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			// no-op
		}
	}

	/**
	 * {@link ExportFileHandle} backed by a real temporary file so the task's zip machinery has a genuine
	 * sink to write into. Closing publishes or fails the future, while discard removes an open unpublished file.
	 */
	private static class FakeExportFileHandle implements ExportFileHandle {
		private final UUID fileId;
		private final Path targetFile;
		private final CompletableFuture<FileForFetch> future = new CompletableFuture<>();
		@Nullable private IOException finalizationFailure;
		@Nullable private OutputStream rawOutput;
		private boolean closed;

		private FakeExportFileHandle(@Nonnull UUID fileId, @Nonnull Path targetFile) {
			this.fileId = fileId;
			this.targetFile = targetFile;
		}

		@Nonnull
		@Override
		public UUID fileId() {
			return this.fileId;
		}

		@Nonnull
		@Override
		public CompletableFuture<FileForFetch> fileForFetchFuture() {
			return this.future;
		}

		@Override
		public long size() {
			try {
				return Files.size(this.targetFile);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to read the size of the test export file.",
					"Failed to read the size of the test export file.", e
				);
			}
		}

		@Nonnull
		@Override
		public OutputStream outputStream() {
			try {
				this.rawOutput = Files.newOutputStream(this.targetFile);
				return new FilterOutputStream(this.rawOutput) {
					@Override
					public void close() throws IOException {
						if (!FakeExportFileHandle.this.closed) {
							FakeExportFileHandle.this.closed = true;
							super.close();
							if (FakeExportFileHandle.this.finalizationFailure == null) {
								FakeExportFileHandle.this.future.complete(fileForFetch(FakeExportFileHandle.this.fileId));
							} else {
								Files.deleteIfExists(FakeExportFileHandle.this.targetFile);
								FakeExportFileHandle.this.future.completeExceptionally(
									FakeExportFileHandle.this.finalizationFailure);
							}
						}
					}
				};
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to open the test export file for writing.",
					"Failed to open the test export file for writing.", e
				);
			}
		}

		@Nonnull
		@Override
		public ExportFileSizeLimit sizeLimit() {
			return new ExportFileSizeLimit(Long.MAX_VALUE, Long.MAX_VALUE, "export.fileSystem.sizeLimitBytes");
		}

		@Override
		public boolean discard() {
			if (this.closed) {
				return false;
			}
			this.closed = true;
			try {
				if (this.rawOutput != null) {
					this.rawOutput.close();
				}
				Files.deleteIfExists(this.targetFile);
				this.future.cancel(false);
				return true;
			} catch (IOException exception) {
				throw new UnexpectedIOException(
					"Failed to discard test export.", "Failed to discard test export.", exception);
			}
		}

		@Override
		public void close() {
			// no-op
		}
	}
}
