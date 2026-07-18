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

import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportProgressListener;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportSummary;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportedSessionConsumer;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
class TrafficRecordingExportTaskTest {
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
		final FileForFetch finishedFile = fileForFetch(fileId);
		final FakeExportFileHandle handle = new FakeExportFileHandle(fileId, this.zipFile);
		handle.fileForFetchFuture().complete(finishedFile);
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

		assertSame(finishedFile, result, "A successful export must return the finished file-for-fetch.");
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
		// the file was already registered (future completed) before the engine failed mid-export
		handle.fileForFetchFuture().complete(fileForFetch(fileId));
		final RecordingExportService exportService = new RecordingExportService(handle);

		final TrafficRecordingEngine engine = Mockito.mock(TrafficRecordingEngine.class);
		Mockito.when(engine.exportTrafficRecording(ArgumentMatchers.any(), ArgumentMatchers.any()))
			.thenThrow(new IOException("simulated export failure"));

		final TrafficRecordingExportTask task = new TrafficRecordingExportTask(
			CATALOG_NAME, CHUNK_SIZE, engine, exportService
		);

		// move the task out of WAITING_FOR_PRECONDITION into QUEUED so execute() actually runs the callable
		task.transitionToIssued();
		assertThrows(
			UnexpectedIOException.class, task::call,
			"An engine I/O failure during export must surface as an UnexpectedIOException."
		);
		assertEquals(
			List.of(fileId), exportService.deletedFileIds,
			"A failed export must delete the partially written file so it does not leak in export storage."
		);
	}

	@Test
	@DisplayName("A finalization failure deletes the partial file and surfaces the specific error, not a masked CompletionException")
	void shouldDeletePartialFileAndSurfaceErrorWhenFinalizationFails() throws Exception {
		final UUID fileId = UUID.randomUUID();
		final FakeExportFileHandle handle = new FakeExportFileHandle(fileId, this.zipFile);
		// the export streams fine, but the file-for-fetch future completes EXCEPTIONALLY (e.g. an S3 upload
		// failure surfaced asynchronously). getNow(null) on such a future rethrows a CompletionException; the
		// cleanup must not use it (that would mask the real error and skip the delete).
		handle.fileForFetchFuture().completeExceptionally(new IOException("simulated finalization failure"));
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
		// the specific GenericEvitaInternalError (from fileForFetchFuture().get()) must surface - NOT a
		// CompletionException masking it from the cleanup's former getNow(null)
		assertThrows(
			GenericEvitaInternalError.class, task::call,
			"A finalization failure must surface as the specific GenericEvitaInternalError, not a " +
				"CompletionException from the cleanup reading an exceptionally-completed future."
		);
		assertEquals(
			List.of(fileId), exportService.deletedFileIds,
			"Cleanup must still delete the partial file when the file-for-fetch future completed exceptionally."
		);
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
	 * sink to write into, with a caller-controllable file-for-fetch future used to model both the finished
	 * and the already-registered-but-failed states.
	 */
	private static class FakeExportFileHandle implements ExportFileHandle {
		private final UUID fileId;
		private final Path targetFile;
		private final CompletableFuture<FileForFetch> future = new CompletableFuture<>();

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
				return Files.newOutputStream(this.targetFile);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to open the test export file for writing.",
					"Failed to open the test export file for writing.", e
				);
			}
		}

		@Override
		public void close() {
			// no-op
		}
	}
}
