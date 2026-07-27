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

import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
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
class TrafficRecorderTaskExportCleanupTest {

	@Test
	@DisplayName("A corrupted export (completed run but null result) deletes the leaked file")
	void shouldDeleteFileWhenExportCorrupted() {
		final RecordingExportService exportService = new RecordingExportService();
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID());

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
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID());

		// recording aborted before it completed - regardless of whether a (partial) result exists, the file leaks
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, null, false);

		assertEquals(
			List.of(handle.fileId()), exportService.deletedFileIds,
			"An aborted recording must delete the partial file."
		);
	}

	@Test
	@DisplayName("A healthy completed export keeps its file")
	void shouldKeepFileWhenExportHealthy() {
		final RecordingExportService exportService = new RecordingExportService();
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID());
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
			@Override
			public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
				// mimic a file that was never fully registered / already gone
				throw new FileForFetchNotFoundException(fileId);
			}
		};
		final ExportFileHandle handle = new FixedExportFileHandle(UUID.randomUUID());

		// must not propagate - cleanup happens on a failure path and must not mask the original error
		TrafficRecorderTask.cleanupLeakedExportFile(exportService, handle, null, false);
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
	 * Minimal {@link ExportFileHandle} exposing a fixed {@code fileId}; the remaining lifecycle hooks are
	 * never exercised by the cleanup decision under test.
	 */
	private static class FixedExportFileHandle implements ExportFileHandle {
		private final UUID fileId;

		private FixedExportFileHandle(@Nonnull UUID fileId) {
			this.fileId = fileId;
		}

		@Nonnull
		@Override
		public UUID fileId() {
			return this.fileId;
		}

		@Nonnull
		@Override
		public CompletableFuture<FileForFetch> fileForFetchFuture() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long size() {
			throw new UnsupportedOperationException();
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
