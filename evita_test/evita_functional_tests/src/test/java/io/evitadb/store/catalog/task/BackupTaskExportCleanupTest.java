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

import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
class BackupTaskExportCleanupTest extends BackupTaskExportFixture {
	private FailingExportService failingExports;

	/** Adds the failing stream to the shared real export service. */
	@BeforeEach
	void setUpFailure() {
		this.failingExports = new FailingExportService(this.exports);
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

	/** A cancellation arriving right after allocation must leave nothing behind, however the backup then unwinds. */
	@Nested
	@DisplayName("Cancellation during export allocation")
	class CancellationDuringAllocation {
		@Test
		@DisplayName("should leave nothing behind when the full backup is cancelled right after allocation")
		void shouldLeaveNothingBehindWhenFullBackupCancelledDuringAllocation() throws IOException {
			final AtomicReference<FullBackupTask> taskReference = new AtomicReference<>();
			final CancellingExportService cancellingExports = new CancellingExportService(
				exports, () -> {
					assertTrue(taskReference.get().cancel());
					// in production the cancel interrupts the worker through its execution handle
					Thread.currentThread().interrupt();
				}
			);
			final FullBackupTask task = new FullBackupTask(TEST_CATALOG, cancellingExports, persistence, null);
			taskReference.set(task);
			task.transitionToIssued();
			try {
				task.execute();

				assertTrue(task.getFutureResult().isCancelled());
				assertNotNull(cancellingExports.handle, "cancellation must follow real handle allocation");
				try (final Stream<Path> files = Files.list(paths.work().resolve("failed-exports"))) {
					assertEquals(0L, files.filter(it -> it.toString().endsWith(".zip") ||
						it.toString().endsWith(FileForFetch.METADATA_EXTENSION)).count(),
						"cancelled backup must leave no archive or sidecar");
				}
				assertEquals(0, exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
				assertTrue(exports.getFile(cancellingExports.handle.fileId()).isEmpty());
			} finally {
				// the interrupt must not leak into the next test, and neither may the handle if an assertion failed
				Thread.interrupted();
				if (cancellingExports.handle != null) {
					cancellingExports.handle.discard();
				}
			}
		}

		@Test
		@DisplayName("should stay cancelled when deleting the archive the unwinding zip published fails")
		void shouldStayCancelledWhenCleanupDeleteFails() {
			final AtomicReference<FullBackupTask> taskReference = new AtomicReference<>();
			final AtomicInteger deleteAttempts = new AtomicInteger();
			final CancellingExportService undeletableExports = new CancellingExportService(
				exports, () -> {
					assertTrue(taskReference.get().cancel());
					// in production the cancel interrupts the worker through its execution handle
					Thread.currentThread().interrupt();
				}
			) {
				@Override
				public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
					deleteAttempts.incrementAndGet();
					throw new FileForFetchNotFoundException(fileId);
				}
			};
			final FullBackupTask task = new FullBackupTask(TEST_CATALOG, undeletableExports, persistence, null);
			taskReference.set(task);
			task.transitionToIssued();
			try {
				task.execute();

				assertTrue(task.getFutureResult().isCancelled());
				assertEquals(
					"Task was cancelled.", task.getStatus().publicExceptionMessage(),
					"the failed delete must not turn the cancellation into a failure"
				);
				assertEquals(1, deleteAttempts.get(), "the zip's close must publish, leaving the delete to clean up");
			} finally {
				// the interrupt must not leak into the next test, and neither may the handle if an assertion failed
				Thread.interrupted();
				if (undeletableExports.handle != null) {
					undeletableExports.handle.discard();
				}
			}
		}
	}

	/** Allocates a real local handle and cancels the task before returning it. */
	private static class CancellingExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private final Runnable cancellation;
		private ExportFileHandle handle;

		/**
		 * @param delegate real local storage
		 * @param cancellation deterministic post-allocation cancellation
		 */
		private CancellingExportService(@Nonnull ExportService delegate, @Nonnull Runnable cancellation) {
			this.delegate = delegate;
			this.cancellation = cancellation;
		}

		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description, @Nonnull String contentType, @Nullable String origin
		) {
			this.handle = this.delegate.storeFile(fileName, description, contentType, origin);
			this.cancellation.run();
			return this.handle;
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
