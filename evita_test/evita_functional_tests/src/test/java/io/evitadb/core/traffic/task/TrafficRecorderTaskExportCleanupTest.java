/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.export.model.ExportFileSizeLimit;
import io.evitadb.test.EvitaTestSupport;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the export-file cleanup decision of the live traffic-recording task
 * ({@link TrafficRecorderTask#cleanupLeakedExportFile}). When a recording aborts (an exception is thrown)
 * or the export is marked corrupted mid-flight, the partial zip already registered in the export storage
 * must be deleted so it does not leak; a healthy completed export must be kept. Before the fix the old
 * live-streaming task never deleted the leftover file, unlike the newer on-demand
 * {@link TrafficRecordingExportTask}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRAFFIC_ENGINE)
@Tag(EXPORT)
@DisplayName("Traffic recorder export cleanup")
class TrafficRecorderTaskExportCleanupTest implements EvitaTestSupport {

	@Test
	@DisplayName("A corrupted export (completed run but null result) deletes the leaked file")
	void shouldDeleteFileWhenExportCorrupted() {
		final RecordingExportService exportService = new RecordingExportService();
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID(), true);

		// recording finished (no exception) but the sink got corrupted -> getFileForFetch() returned null
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, null, true);

		assertEquals(
			List.of(handle.fileId()), exportService.deletedFileIds,
			"A corrupted export must delete the leaked file so it does not leak in the export storage."
		);
	}

	@Test
	@DisplayName("An aborted recording (exception before completion) deletes the leaked file")
	void shouldDeleteFileWhenRecordingAborted() {
		final RecordingExportService exportService = new RecordingExportService();
		final FixedExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID(), false);

		// recording aborted before it completed - regardless of whether a (partial) result exists, the file leaks
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, null, false);

		assertEquals(
			List.of(), exportService.deletedFileIds,
			"An open handle must be discarded without a delete attempt."
		);
		assertTrue(handle.discarded);
	}

	@Test
	@DisplayName("A healthy completed export keeps its file")
	void shouldKeepFileWhenExportHealthy() {
		final RecordingExportService exportService = new RecordingExportService();
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID(), true);
		final FileForFetch result = fileForFetch(handle.fileId());

		// recording completed cleanly and produced a valid result -> the finished file must be kept
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, result, true);

		assertTrue(
			exportService.deletedFileIds.isEmpty(),
			"A healthy completed export must not delete its own finished file."
		);
	}

	@Test
	@DisplayName("No export requested (null handle) is a no-op")
	void shouldDoNothingWhenNoExportFile() {
		final RecordingExportService exportService = new RecordingExportService();

		TrafficRecorderTask.cleanupLeakedExportFile(exportService, null, null, false);

		assertTrue(
			exportService.deletedFileIds.isEmpty(),
			"When no export file was created there is nothing to clean up."
		);
	}

	@Test
	@DisplayName("A deletion failure is swallowed so it never masks the primary failure")
	void shouldSwallowDeletionFailure() {
		final ExportService exportService = new RecordingExportService() {
			/** {@inheritDoc} */
			@Override
			public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
				// mimic a file that was never fully registered / already gone
				throw new FileForFetchNotFoundException(fileId);
			}
		};
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID(), true);

		// must not propagate - cleanup happens on a failure path and must not mask the original error
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, null, false);
	}

	/** Pending publication is healthy: a descriptor arriving after close must be returned and retained. */
	@Test
	@DisplayName("should await a recording descriptor that arrives after close")
	void shouldAwaitDescriptorAfterClose() throws Exception {
		assertDelayedPublication(false);
	}

	/** A failed future must report the public upload error instead of a silent null or an unknown failure. */
	@Test
	@DisplayName("should report the public message of a failed recording upload")
	void shouldReportFailedUpload() throws Exception {
		assertDelayedPublication(true);
	}

	/** Uses a real recording engine and storage, delaying only delivery of the closed handle's descriptor. */
	private void assertDelayedPublication(boolean failUpload) throws Exception {
		final TestPaths paths = createTestPaths("traffic-delayed-publication");
		final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
		executor.setThreadFactory(Thread.ofPlatform().daemon(true).factory());
		final Scheduler scheduler = new Scheduler(executor);
		try (
			final FileManagementService management = new FileManagementService(
				StorageOptions.builder().workDirectory(paths.work()).build());
			final ExportFileService storage = new ExportFileService(
				FileSystemExportOptions.builder().directory(paths.export()).build(), scheduler)
		) {
			final DelayedExportService service = new DelayedExportService(storage);
			final TrafficRecordingEngine engine = new TrafficRecordingEngine("X", CatalogState.ALIVE,
				TracingContextProvider.getContext(), EvitaConfiguration.builder()
					.storage(StorageOptions.builder().workDirectory(paths.work()).build())
					.server(ServerOptions.builder().trafficRecording(TrafficRecordingOptions.builder()
						.enabled(false).build()).build()).build(), management, scheduler);
			final TrafficRecorderTask task = new TrafficRecorderTask(
				"X", 100, true, null, null, 32000, engine, service, scheduler);
			task.transitionToIssued();
			final Thread worker = Thread.ofPlatform().daemon(true).start(() -> {
				try {
					task.call();
				} catch (Exception ignored) {
					// Assertions read the task's future, which retains the original failure.
				}
			});
			task.stop();
			try {
				assertTrue(service.closed.await(1, TimeUnit.MINUTES));
				// The completing thread is independent of the task; no sleeps govern upload timing.
				final Thread publisher = Thread.ofPlatform().daemon(true).start(() -> {
					try {
						if (!service.handle.queried.await(1, TimeUnit.MINUTES)) {
							throw new IllegalStateException("The task never read the publication future.");
						}
					} catch (InterruptedException exception) {
						try {
							Thread.currentThread().interrupt();
							throw new IllegalStateException(exception);
						} finally {
							Thread.interrupted();
						}
					}
					if (failUpload) {
						service.handle.future.completeExceptionally(new UnexpectedIOException(
							"storage rejected upload", "Failed to upload file to S3."));
					} else {
						service.handle.future.complete(service.handle.delegate.fileForFetchFuture().join());
					}
				});
				publisher.join(60000);
				if (failUpload) {
					assertThrows(ExecutionException.class,
						() -> task.getFutureResult().get(1, TimeUnit.MINUTES));
					assertEquals("Failed to upload file to S3.", task.getStatus().publicExceptionMessage());
				} else {
					final FileForFetch result = task.getFutureResult().get(1, TimeUnit.MINUTES);
					assertNotNull(result, "a pending upload must be awaited, not treated as leaked");
					assertEquals(0, service.deleteAttempts);
					assertTrue(Files.exists(result.path(paths.export())));
				}
			} finally {
				worker.join(60000);
				assertFalse(worker.isAlive());
			}
		} finally {
			scheduler.shutdownNow();
			cleanupTestPaths(paths);
		}
	}

	/** Delays only the publication future; data and discard use the real file service. */
	private static class DelayedExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private final CountDownLatch closed = new CountDownLatch(1);
		private DelayedHandle handle;
		private int deleteAttempts;

		/** Keeps the backend real while exposing completion to the test publisher. */
		private DelayedExportService(@Nonnull ExportService delegate) {
			this.delegate = delegate;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public ExportFileHandle storeFile(
			@Nonnull String name, @Nullable String description, @Nonnull String type, @Nullable String origin
		) {
			this.handle = new DelayedHandle(this.delegate.storeFile(name, description, type, origin), this.closed);
			return this.handle;
		}

		/** {@inheritDoc} */
		@Override
		public void deleteFile(@Nonnull UUID fileId) {
			this.deleteAttempts++;
			this.delegate.deleteFile(fileId);
		}
	}

	/** Signals actual stream closure before making the descriptor available to the task. */
	private static class DelayedHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;
		private final CountDownLatch queried = new CountDownLatch(1);
		private final CompletableFuture<FileForFetch> future = new CompletableFuture<>() {
			/** {@inheritDoc} */
			@Override
			public FileForFetch getNow(FileForFetch absent) {
				final FileForFetch result = super.getNow(absent);
				DelayedHandle.this.queried.countDown();
				return result;
			}

			/** {@inheritDoc} */
			@Override
			public FileForFetch get(long timeout, @Nonnull TimeUnit unit)
				throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
				DelayedHandle.this.queried.countDown();
				return super.get(timeout, unit);
			}
		};
		private final CountDownLatch closed;

		/** Wraps one real handle. */
		private DelayedHandle(@Nonnull ExportFileHandle delegate, @Nonnull CountDownLatch closed) {
			this.delegate = delegate;
			this.closed = closed;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public CompletableFuture<FileForFetch> fileForFetchFuture() {
			return this.future;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public OutputStream outputStream() {
			return new FilterOutputStream(this.delegate.outputStream()) {
				/** {@inheritDoc} */
				@Override
				public void close() throws IOException {
					super.close();
					DelayedHandle.this.closed.countDown();
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
	 * Minimal {@link ExportService} that records every {@code deleteFile} call so the test can assert
	 * whether cleanup was triggered. All other operations are irrelevant to this test.
	 */
	private static class RecordingExportService implements ExportService {
		private final List<UUID> deletedFileIds = new ArrayList<>();

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
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description,
			@Nonnull String contentType, @Nullable String origin
		) {
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
	 * Models an open unpublished handle or a closed handle with a successfully published descriptor.
	 */
	private static class FixedExportFileHandle implements ExportFileHandle {
		private final UUID fileId;
		private final boolean published;
		private final CompletableFuture<FileForFetch> future;
		private boolean discarded;

		private FixedExportFileHandle(@Nonnull UUID fileId, boolean published) {
			this.fileId = fileId;
			this.published = published;
			this.future = published ? CompletableFuture.completedFuture(fileForFetch(fileId)) :
				new CompletableFuture<>();
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
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public ExportFileSizeLimit sizeLimit() {
			return new ExportFileSizeLimit(Long.MAX_VALUE, Long.MAX_VALUE, "export.fileSystem.sizeLimitBytes");
		}

		@Override
		public boolean discard() {
			if (this.published || this.discarded) {
				return false;
			}
			this.discarded = true;
			this.future.cancel(false);
			return true;
		}

		@Override
		public void close() {
			// no-op
		}

		@Nonnull
		@Override
		public OutputStream outputStream() {
			throw new UnsupportedOperationException();
		}
	}
}
