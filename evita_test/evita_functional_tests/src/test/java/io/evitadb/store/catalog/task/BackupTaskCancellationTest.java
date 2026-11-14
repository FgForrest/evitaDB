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

import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.export.model.ExportFileSizeLimit;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what {@link BackupTask}'s failure cleanup does with the partially written export file when the backup is
 * aborted by an interrupt rather than by an ordinary runtime failure.
 *
 * The distinction matters because the interrupt checkpoints woven into the backup's helpers raise
 * {@link InterruptedException} — a **checked** exception, thrown from bytecode out of methods whose signatures never
 * mention it. It is invisible in the source, so the cleanup's exception type is the only thing that decides whether a
 * cancelled backup leaves its half-written archive behind. Both cases below drive the failure through the same
 * injection point and differ only in the type thrown, which is what makes the pair conclusive.
 *
 * The fixture is deliberately free of an embedded evitaDB instance: the persistence service is stubbed and fails on
 * the first call the backup makes against it, so nothing here depends on timing or on a catalog existing on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TASK)
@Tag(EXPORT)
@DisplayName("Backup task cancellation cleanup")
class BackupTaskCancellationTest {
	private static final String CATALOG_NAME = "testCatalog";
	/** Any non-zero version keeps the constructor off the warm-up path, which would need a real directory hold. */
	private static final long CATALOG_VERSION = 1L;

	private Path zipFile;
	/** Owns real sinks even when a tripped cap deliberately skips closing its delegate. */
	private final List<OutputStream> openedStreams = new ArrayList<>();

	/**
	 * Throws the given checked exception without declaring it — precisely what the woven interrupt advice does. Type
	 * erasure makes the cast to `E` unchecked at the throw site, so the compiler never sees the checked type.
	 *
	 * @param throwable the exception to raise
	 * @param <E>       the type the caller pretends is being thrown
	 * @throws E always
	 */
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void sneakyThrow(@Nonnull Throwable throwable) throws E {
		throw (E) throwable;
	}

	@Nonnull
	private static FileForFetch fileForFetch(@Nonnull UUID fileId) {
		return new FileForFetch(
			fileId, "backup_" + CATALOG_NAME + ".zip", null, "application/zip",
			1L, OffsetDateTime.now(), null
		);
	}

	@Nonnull
	private static CatalogBootstrap bootstrapRecord() {
		return new CatalogBootstrap(1, CATALOG_VERSION, 0, OffsetDateTime.now(), null);
	}

	/**
	 * Builds a backup task that reads its catalog path before the storage-part service lookup raises the given
	 * exception, placing the failure inside the block guarded by the export-file cleanup.
	 *
	 * @param exportService the export service the task registers its file with
	 * @param failure       the exception the persistence service raises
	 * @return the task, already moved into the QUEUED state so `call()` runs its body
	 */
	@Nonnull
	private BackupTask failingBackupTask(
		@Nonnull ExportService exportService,
		@Nonnull Throwable failure
	) {
		final DefaultCatalogPersistenceService persistenceService =
			Mockito.mock(DefaultCatalogPersistenceService.class);
		Mockito.when(persistenceService.getCatalogStoragePath()).thenReturn(this.zipFile.resolveSibling("catalog"));
		Mockito.when(persistenceService.getStoragePartPersistenceService(ArgumentMatchers.anyLong()))
			.thenAnswer(invocation -> {
				sneakyThrow(failure);
				return null;
			});

		final BackupTask task = new BackupTask(
			CATALOG_NAME, null, null, false, bootstrapRecord(), exportService, persistenceService, null
		);
		// execute() gates on QUEUED and a freshly built status is WAITING_FOR_PRECONDITION
		task.transitionToIssued();
		return task;
	}

	@BeforeEach
	void setUp() throws IOException {
		this.zipFile = Files.createTempFile("BackupTaskCancellationTest", ".zip");
	}

	@AfterEach
	void tearDown() throws IOException {
		for (final OutputStream stream : this.openedStreams) {
			IOUtils.closeQuietly(stream::close);
		}
		Files.deleteIfExists(this.zipFile);
	}

	@Nested
	@DisplayName("Aborted by an interrupt")
	class AbortedByInterrupt {

		@Test
		@DisplayName("deletes the registered export file when the backup is interrupted")
		void shouldDeleteRegisteredExportFileWhenBackupInterrupted() {
			// an interrupt must clean up exactly like any other failure: the archive written so far is structurally
			// valid but missing most of its entries, and leaving it registered lists it to users as a fetchable backup
			final UUID fileId = UUID.randomUUID();
			final FakeExportFileHandle handle = new FakeExportFileHandle(
				fileId, BackupTaskCancellationTest.this.zipFile
			);
			// Exercise cleanup of a handle that has already published its file.
			handle.fileForFetchFuture().complete(fileForFetch(fileId));
			final RecordingExportService exportService = new RecordingExportService(handle);

			final InterruptedException interrupt = new InterruptedException("simulated interrupt");
			final BackupTask task = failingBackupTask(exportService, interrupt);

			// the cleanup rewraps a non-runtime cause, so the checked interrupt surfaces as an UnexpectedIOException
			final UnexpectedIOException raised = assertThrows(
				UnexpectedIOException.class, task::call,
				"the interrupt must unwind the backup"
			);
			assertSame(interrupt, raised.getCause(), "the rewrap lost the interrupt that aborted the backup");
			assertEquals(
				List.of(fileId), exportService.deletedFileIds,
				"an interrupted backup left its partially written file registered as a fetchable backup"
			);
		}

		@Test
		@DisplayName("keeps the interrupt when deleting the published file fails")
		void shouldKeepInterruptWhenCleanupDeleteFails() {
			// a purge tick or a client may have removed the file first; that must not replace why the backup stopped
			final UUID fileId = UUID.randomUUID();
			final FakeExportFileHandle handle = new FakeExportFileHandle(
				fileId, BackupTaskCancellationTest.this.zipFile
			);
			handle.fileForFetchFuture().complete(fileForFetch(fileId));
			final RecordingExportService exportService = new RecordingExportService(handle) {
				@Override
				public void deleteFile(@Nonnull UUID id) throws FileForFetchNotFoundException {
					super.deleteFile(id);
					throw new FileForFetchNotFoundException(id);
				}
			};

			final InterruptedException interrupt = new InterruptedException("simulated interrupt");
			final BackupTask task = failingBackupTask(exportService, interrupt);

			final UnexpectedIOException raised = assertThrows(
				UnexpectedIOException.class, task::call,
				"the failed delete must not replace the interrupt that aborted the backup"
			);
			assertSame(interrupt, raised.getCause(), "the rewrap lost the interrupt that aborted the backup");
			assertEquals(List.of(fileId), exportService.deletedFileIds, "the cleanup must have attempted the delete");
			assertTrue(
				Arrays.stream(interrupt.getSuppressed()).anyMatch(FileForFetchNotFoundException.class::isInstance),
				"the failed delete must stay visible on the primary failure"
			);
		}
	}

	@Nested
	@DisplayName("Aborted by a runtime failure")
	class AbortedByRuntimeFailure {

		@Test
		@DisplayName("deletes the registered export file when the backup fails at runtime")
		void shouldDeleteRegisteredExportFileWhenBackupFailsAtRuntime() {
			// the counterfactual that makes the case above conclusive: the identical injection point, reached the
			// identical way, and the cleanup DOES run because the exception is a RuntimeException
			final UUID fileId = UUID.randomUUID();
			final FakeExportFileHandle handle = new FakeExportFileHandle(
				fileId, BackupTaskCancellationTest.this.zipFile
			);
			handle.fileForFetchFuture().complete(fileForFetch(fileId));
			final RecordingExportService exportService = new RecordingExportService(handle);

			final BackupTask task = failingBackupTask(exportService, new IllegalStateException("simulated failure"));

			assertThrows(IllegalStateException.class, task::call, "the runtime failure must unwind the backup");
			assertEquals(
				List.of(fileId), exportService.deletedFileIds,
				"a backup that failed at runtime must delete its partially written file"
			);
		}
	}

	/** An interrupt inside the zip can trip the cap while close unwinds, but must retain cancellation semantics. */
	@Nested
	@DisplayName("Cancellation while the cap trips in close")
	class CapDuringCancellation {
		@Test
		@DisplayName("should preserve a checked interrupt when finalization exceeds the cap")
		void shouldPreserveInterruptWhenCapTripsDuringClose() {
			final InterruptedException interrupt = new InterruptedException("inside zip");
			final BackupTask task = failingInsideZip(interrupt, false);
			final UnexpectedIOException raised = assertThrows(UnexpectedIOException.class, task::call);
			assertSame(interrupt, raised.getCause());
			assertTrue(Arrays.stream(interrupt.getSuppressed()).anyMatch(
				ExportFileTooLargeException.class::isInstance), "the cap must actually trip during close");
		}

		@Test
		@DisplayName("should keep a cancelled future cancelled when finalization exceeds the cap")
		void shouldKeepCancelledFutureWhenCapTripsDuringClose() {
			final RuntimeException failure = new RuntimeException(new InterruptedException("cancelled inside zip"));
			final BackupTask task = failingInsideZip(failure, true);
			task.execute();
			assertTrue(task.getFutureResult().isCancelled());
			assertFalse(task.getStatus().publicExceptionMessage() != null &&
				task.getStatus().publicExceptionMessage().contains("exceeded the export storage limit"));
			assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(
				ExportFileTooLargeException.class::isInstance), "the cap must actually trip during close");
		}
	}

	/** Places the failure in getCatalogHeader, after the buffered directory entry has been written. */
	@Nonnull
	private BackupTask failingInsideZip(@Nonnull Throwable failure, boolean cancel) {
		final FakeExportFileHandle handle = new FakeExportFileHandle(UUID.randomUUID(), this.zipFile);
		handle.allowance = 1;
		final DefaultCatalogPersistenceService persistenceService =
			Mockito.mock(DefaultCatalogPersistenceService.class);
		Mockito.when(persistenceService.getCatalogStoragePath()).thenReturn(this.zipFile.resolveSibling("catalog"));
		final CatalogOffsetIndexStoragePartPersistenceService offsetService =
			Mockito.mock(CatalogOffsetIndexStoragePartPersistenceService.class);
		Mockito.when(persistenceService.getStoragePartPersistenceService(ArgumentMatchers.anyLong()))
			.thenReturn(offsetService);
		final BackupTask task = new BackupTask(
			CATALOG_NAME, null, null, false, bootstrapRecord(), new RecordingExportService(handle),
			persistenceService, null
		);
		Mockito.when(offsetService.getCatalogHeader(ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
			if (cancel) {
				task.cancel();
			}
			sneakyThrow(failure);
			return null;
		});
		task.transitionToIssued();
		return task;
	}

	/**
	 * Minimal {@link ExportService} that hands out a single pre-built {@link ExportFileHandle} on `storeFile` and
	 * records every `deleteFile` call, so the test can assert whether the partial-file cleanup ran. Every other
	 * operation is irrelevant here.
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
	 * {@link ExportFileHandle} backed by a real temporary file, so the backup's zip machinery has a genuine sink, with
	 * a caller-controllable file-for-fetch future modelling a file that is already registered when the failure lands.
	 */
	private class FakeExportFileHandle implements ExportFileHandle {
		private final UUID fileId;
		private final Path targetFile;
		private final CompletableFuture<FileForFetch> future = new CompletableFuture<>();
		/** Payload allowance in bytes; effectively unlimited unless a cancellation test lowers it. */
		private long allowance = Long.MAX_VALUE;

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
				final OutputStream stream = Files.newOutputStream(this.targetFile);
				BackupTaskCancellationTest.this.openedStreams.add(stream);
				return stream;
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
			return new ExportFileSizeLimit(this.allowance, this.allowance, "export.fileSystem.sizeLimitBytes");
		}

		@Override
		public boolean discard() {
			// This fixture models a descriptor that is already published; retain the delete-file assertions.
			return false;
		}

		@Override
		public void close() {
			// no-op
		}
	}

}
