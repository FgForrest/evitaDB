/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.api.traffic.TrafficRecordingExporter;
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportSummary;
import io.evitadb.api.traffic.TrafficRecordingExporter.SessionByteSource;
import io.evitadb.core.executor.ClientCallableTask;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingExportSettings;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


/**
 * Task that exports a consistent, on-demand snapshot of the currently buffered traffic recording window into a
 * downloadable zip archive - a one-shot, plain callable task (unlike {@link TrafficRecorderTask}, this is never
 * infinite and carries no {@link TaskTrait#NEEDS_TO_BE_STOPPED} trait).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class TrafficRecordingExportTask extends ClientCallableTask<TrafficRecordingExportSettings, FileForFetch> {
	private final TrafficRecordingEngine trafficRecordingEngine;
	private final ExportService exportService;

	public TrafficRecordingExportTask(
		@Nonnull String catalogName,
		long chunkFileSizeInBytes,
		@Nonnull TrafficRecordingEngine trafficRecordingEngine,
		@Nonnull ExportService exportService
	) {
		super(
			catalogName,
			TrafficRecordingExportTask.class.getSimpleName(),
			"Traffic recording export for catalog `" + catalogName + "`",
			new TrafficRecordingExportSettings(catalogName, chunkFileSizeInBytes),
			(task) -> ((TrafficRecordingExportTask) task).doExport(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED
		);
		this.trafficRecordingEngine = trafficRecordingEngine;
		this.exportService = exportService;
	}

	/**
	 * Executes the export and returns the resulting zip file. On any failure, the partially written export file
	 * is deleted (mirrors {@code BackupTask}'s failure-cleanup model) before the exception is rethrown.
	 */
	@Nonnull
	private FileForFetch doExport() {
		final TrafficRecordingExportSettings settings = getStatus().settings();
		final String fileName = "traffic_recording_export_" + settings.catalogName() + "_" +
			OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

		final ExportFileHandle exportFileHandle = this.exportService.storeFile(
			fileName + ".zip",
			"On-demand traffic recording export for catalog `" + settings.catalogName() + "` created at " +
				OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".",
			"application/zip",
			this.getClass().getSimpleName()
		);

		try {
			try (ExportSink sink = new ExportSink(exportFileHandle, settings.chunkFileSizeInBytes())) {
				final ExportSummary summary = this.trafficRecordingEngine.exportTrafficRecording(
					sink::exportSession,
					(processed, total) -> this.updateProgress(total == 0 ? 100 : (processed * 100) / total)
				);
				sink.finish(summary);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to export traffic recording for catalog `" + settings.catalogName() + "`: " + e.getMessage(),
					"Failed to export traffic recording.",
					e
				);
			}

			try {
				return exportFileHandle.fileForFetchFuture().get();
			} catch (Exception e) {
				throw new GenericEvitaInternalError(
					"Unexpected error when retrieving the traffic recording export file for catalog `" +
						settings.catalogName() + "`: " + e.getMessage(),
					"Failed to retrieve the traffic recording export file after successful creation!",
					e
				);
			}
		} catch (RuntimeException exception) {
			// delete the partial export file so it does not leak in the export storage. Use fileId() directly
			// (always available) rather than getNow(null) on the file-for-fetch future: if finalization
			// completed exceptionally, getNow rethrows a CompletionException that would both mask `exception`
			// and skip this cleanup. Deletion failures are swallowed so they never mask the primary failure.
			try {
				this.exportService.deleteFile(exportFileHandle.fileId());
			} catch (RuntimeException cleanupFailure) {
				log.warn(
					"Failed to delete partial traffic recording export file `{}`.",
					exportFileHandle.fileId(), cleanupFailure
				);
			}
			throw exception;
		}
	}

	/**
	 * Manages the zip/chunk mechanics and the final {@code metadata.txt} entry for one export run - mirrors
	 * {@link TrafficRecorderTask.ExportSessionSink}'s chunk-rollover shape. Chunk rollover is checked strictly
	 * *after* a whole session has been written, never mid-copy, so a session is never split across two entries.
	 */
	private static class ExportSink implements Closeable {
		private final ZipOutputStream outputStream;
		private final long chunkFileSizeInBytes;
		private final OffsetDateTime startTime = OffsetDateTime.now();
		private long currentChunkSize = -1L;

		ExportSink(@Nonnull ExportFileHandle exportFileHandle, long chunkFileSizeInBytes) {
			this.chunkFileSizeInBytes = chunkFileSizeInBytes;
			this.outputStream = new ZipOutputStream(new BufferedOutputStream(exportFileHandle.outputStream()));
		}

		/**
		 * {@link TrafficRecordingExporter.ExportedSessionConsumer} implementation, invoked once per exported
		 * session by the engine.
		 */
		void exportSession(long sequenceOrder, @Nonnull SessionByteSource byteSource) throws IOException {
			if (this.currentChunkSize == -1L) {
				this.outputStream.putNextEntry(new ZipEntry("traffic_recording_" + sequenceOrder + ".bin"));
				this.currentChunkSize = 0L;
			}
			final CountingOutputStream countingOutputStream = new CountingOutputStream(this.outputStream);
			byteSource.copyTo(countingOutputStream);
			this.outputStream.flush();
			this.currentChunkSize += countingOutputStream.count();

			if (this.currentChunkSize >= this.chunkFileSizeInBytes) {
				this.outputStream.closeEntry();
				this.currentChunkSize = -1L;
			}
		}

		/**
		 * Writes the final {@code metadata.txt} entry using the export summary - mirrors
		 * {@link TrafficRecorderTask.ExportSessionSink#close()}'s field set, plus the export-specific counters
		 * (nothing parses this file, so the added lines are safe).
		 */
		void finish(@Nonnull ExportSummary summary) throws IOException {
			if (this.currentChunkSize > -1L) {
				this.outputStream.closeEntry();
				this.currentChunkSize = -1L;
			}
			final OffsetDateTime finishTime = OffsetDateTime.now();
			this.outputStream.putNextEntry(new ZipEntry("metadata.txt"));
			this.outputStream.write("Traffic recording export: \n".getBytes());
			this.outputStream.write(("\n   - started at " + this.startTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes());
			this.outputStream.write(("\n   - finished at " + finishTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes());
			this.outputStream.write(("\n   - duration " + StringUtils.formatDuration(Duration.between(this.startTime, finishTime))).getBytes());
			this.outputStream.write(("\n   - exported " + summary.exportedSessionCount() + " sessions").getBytes());
			this.outputStream.write(("\n   - exported " + StringUtils.formatByteSize(summary.exportedByteCount()) + " of data").getBytes());
			this.outputStream.write(("\n   - skipped " + summary.skippedSessionCount() + " sessions (evicted or write-locked during export)").getBytes());
			this.outputStream.write(("\n   - snapshot contained " + summary.totalSessionCount() + " sessions").getBytes());
			this.outputStream.closeEntry();
		}

		@Override
		public void close() throws IOException {
			this.outputStream.close();
		}
	}

	/**
	 * Minimal counting decorator so the chunk-rollover accounting knows exactly how many bytes a single
	 * session's raw copy contributed, without {@link SessionByteSource} needing to report its own size.
	 */
	private static final class CountingOutputStream extends FilterOutputStream {
		private long count = 0L;

		CountingOutputStream(@Nonnull OutputStream out) {
			super(out);
		}

		@Override
		public void write(int b) throws IOException {
			this.out.write(b);
			this.count++;
		}

		@Override
		public void write(@Nonnull byte[] b, int off, int len) throws IOException {
			this.out.write(b, off, len);
			this.count += len;
		}

		long count() {
			return this.count;
		}
	}

}
