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

import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.executor.ClientInfiniteCallableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.store.catalog.trafficRecorder.RandomAccessFileSessionSink;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionLocation;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Task is responsible for recording traffic in the catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class TrafficRecorderTask extends ClientInfiniteCallableTask<TrafficRecordingSettings, FileForFetch> {
	/**
	 * Flag indicating that the recording has finished.
	 */
	private final CountDownLatch finalizationLatch = new CountDownLatch(1);
	/**
	 * Export file service that manages the target file.
	 */
	private final TrafficRecordingEngine trafficRecordingEngine;
	/**
	 * Export file service that manages the target file.
	 */
	private final ExportService exportService;
	/**
	 * Scheduler used for scheduling the task stop and progress update.
	 */
	private final Scheduler scheduler;

	/**
	 * Determines the finish condition for the traffic recording based on the given settings.
	 *
	 * @param settings the traffic recording settings containing duration and/or size limit constraints
	 * @return a string representing the condition upon which the recording will be stopped
	 */
	@Nonnull
	private static String getFinishCondition(@Nonnull TrafficRecordingSettings settings) {
		if (settings.recordingDuration() != null && settings.recordingSizeLimitInBytes() != null) {
			return " running for " + StringUtils.formatDuration(settings.recordingDuration()) + " or until file size of " + StringUtils.formatByteSize(settings.recordingSizeLimitInBytes()) + " reached";
		} else if (settings.recordingDuration() != null) {
			return " running for " + StringUtils.formatDuration(settings.recordingDuration());
		} else if (settings.recordingSizeLimitInBytes() != null) {
			return " running until file size of " + StringUtils.formatByteSize(settings.recordingSizeLimitInBytes()) + " reached";
		} else {
			return " running until stopped";
		}
	}

	public TrafficRecorderTask(
		@Nonnull String catalogName,
		int samplingRate,
		boolean exportFile,
		@Nullable Duration recordingDuration,
		@Nullable Long recordingSizeLimitInBytes,
		long chunkFileSizeInBytes,
		@Nonnull TrafficRecordingEngine trafficRecordingEngine,
		@Nonnull ExportService exportService,
		@Nonnull Scheduler scheduler
	) {
		super(
			catalogName,
			TrafficRecorderTask.class.getSimpleName(),
			"Traffic recording",
			new TrafficRecordingSettings(
				catalogName, samplingRate, exportFile,
				recordingDuration, recordingSizeLimitInBytes, chunkFileSizeInBytes
			),
			(task) -> ((TrafficRecorderTask) task).start(),
			recordingDuration == null && recordingSizeLimitInBytes == null ?
				new TaskTrait[]{TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED} :
				new TaskTrait[]{TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED}
		);
		this.exportService = exportService;
		this.scheduler = scheduler;
		this.trafficRecordingEngine = trafficRecordingEngine;
		if (recordingDuration != null) {
			scheduler.schedule(this::stopInternal, recordingDuration.toMillis(), TimeUnit.MILLISECONDS);
			scheduler.schedule(this::updateTaskProgress, 5, TimeUnit.SECONDS);
		}
	}

	@Override
	public boolean cancel() {
		stopInternal();
		return super.cancel();
	}

	@Override
	protected void stopInternal() {
		this.finalizationLatch.countDown();
		this.updateTaskNameAndTraits("Stopping traffic recording and finalizing the file.");
	}

	/**
	 * Updates the task progress based on the remaining time until the recording finishes.
	 */
	private void updateTaskProgress() {
		final TaskStatus<TrafficRecordingSettings, FileForFetch> theStatus = getStatus();
		final Duration duration = theStatus.settings().recordingDuration();
		if (theStatus.started() != null && duration != null) {
			final OffsetDateTime finishTime = theStatus.started().plusSeconds(duration.getSeconds());
			final long remaining = finishTime.toEpochSecond() - OffsetDateTime.now().toEpochSecond();
			this.updateProgress((int) ((1.0 - ((float) remaining / (float) duration.getSeconds())) * 100.0));
			if (remaining > 5) {
				this.scheduler.schedule(this::updateTaskProgress, 5, TimeUnit.SECONDS);
			}
		} else {
			this.scheduler.schedule(this::updateTaskProgress, 5, TimeUnit.SECONDS);
		}
	}

	/**
	 * Starts the traffic recording.
	 */
	@Nullable
	private FileForFetch start() {
		final TrafficRecordingSettings settings = getStatus().settings();
		final String fileName = "traffic_recording_" + settings.catalogName() + "_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		ExportSessionSink exportSessionSink = null;
		// captured separately from the sink so the handle survives even if the sink constructor fails after
		// the export file has already been created - otherwise that partial file would leak
		ExportFileHandle exportFileHandle = null;
		boolean recordingCompleted = false;
		try {
			if (settings.exportFile()) {
				exportFileHandle = this.exportService.storeFile(
					fileName + ".zip",
					"Traffic recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) +
						" with sampling rate " + settings.samplingRate() + "%" + getFinishCondition(settings) + ".",
					"application/zip",
					this.getClass().getSimpleName()
				);
				exportSessionSink = new ExportSessionSink(
					this.trafficRecordingEngine.getTrafficOptions().trafficDiskBufferSizeInBytes(),
					settings,
					this::stopInternal,
					this::updateProgress,
					exportFileHandle
				);
			}

			// start recording
			this.trafficRecordingEngine.startRecording(settings.samplingRate(), exportSessionSink);

			// wait for the recording to be stopped (either manually or by reaching the specified limits)
			this.finalizationLatch.await();

			// stop recording
			this.trafficRecordingEngine.stopRecording();
			recordingCompleted = true;

		} catch (InterruptedException e) {
			this.trafficRecordingEngine.stopRecording();
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError("Traffic recording task finished abnormally (interrupt).", e);
		} catch (FileNotFoundException e) {
			throw new GenericEvitaInternalError(
				"Traffic recording task finished abnormally: " + e.getMessage(),
				"Traffic recording task finished abnormally.",
				e
			);
		} finally {
			if (exportSessionSink != null) {
				IOUtils.close(
					() -> new UnexpectedIOException("Failed to close export session sink."),
					exportSessionSink::close
				);
			}
			// close the sink first (it writes the metadata entry and completes the file-for-fetch future), then
			// delete the export file if the recording aborted or the export got corrupted - so a partial /
			// corrupted zip does not leak in the export storage. The file-for-fetch read is done via the
			// throw-safe helper because it runs inside this finally: a future that completed exceptionally
			// (e.g. an S3 upload failure surfaced without close() throwing) would otherwise rethrow here, mask
			// any in-flight exception and skip the very cleanup we are performing.
			cleanupLeakedExportFile(
				this.exportService,
				exportFileHandle,
				exportSessionSink == null ? null : safeGetFileForFetch(exportSessionSink),
				recordingCompleted
			);
		}

		return exportSessionSink == null ?
			null : exportSessionSink.getFileForFetch();
	}

	/**
	 * Reads the produced file for fetch of the given sink without ever throwing, so it is safe to call from a
	 * `finally` block. {@link ExportSessionSink#getFileForFetch()} delegates to
	 * {@link java.util.concurrent.CompletableFuture#getNow(Object)}, which rethrows when the file-for-fetch
	 * future completed exceptionally (e.g. an asynchronous S3 upload failure). Such a failure means there is no
	 * healthy exported file, so it is reported as a null result and the partial file is then cleaned up.
	 *
	 * @param exportSessionSink the sink to read the produced file from
	 * @return the produced file for fetch, or null when it is corrupted, not yet available, or finalization failed
	 */
	@Nullable
	private static FileForFetch safeGetFileForFetch(@Nonnull ExportSessionSink exportSessionSink) {
		try {
			return exportSessionSink.getFileForFetch();
		} catch (RuntimeException e) {
			// the file-for-fetch future completed exceptionally (finalization / upload failed) - no healthy file
			return null;
		}
	}

	/**
	 * Deletes a partially written / corrupted traffic recording export file when the recording did not finish
	 * cleanly, so the leftover zip does not leak in the export storage. Mirrors the cleanup performed by
	 * {@link TrafficRecordingExportTask#doExport()}. A healthy, fully completed export (a non-null result
	 * produced by a run that finished without throwing) is kept untouched.
	 *
	 * @param exportService      service managing the exported files
	 * @param exportFileHandle   handle of the export file created for this run, or null when no export was requested
	 * @param validResult        the file for fetch when the export finished uncorrupted, null when corrupted
	 * @param recordingCompleted true when recording finished without an exception being thrown
	 */
	static void cleanupLeakedExportFile(
		@Nonnull ExportService exportService,
		@Nullable ExportFileHandle exportFileHandle,
		@Nullable FileForFetch validResult,
		boolean recordingCompleted
	) {
		if (exportFileHandle == null) {
			// no export file was created for this run - nothing to clean up
			return;
		}
		if (recordingCompleted && validResult != null) {
			// healthy, completed export - keep the finished file
			return;
		}
		deleteLeakedExportFile(exportService, exportFileHandle);
	}

	/**
	 * Deletes the export file backed by the given handle, swallowing (and logging) any deletion failure so the
	 * cleanup never masks the primary failure that triggered it.
	 *
	 * @param exportService    service managing the exported files
	 * @param exportFileHandle handle of the export file to delete
	 */
	private static void deleteLeakedExportFile(
		@Nonnull ExportService exportService,
		@Nonnull ExportFileHandle exportFileHandle
	) {
		try {
			// FileForFetchNotFoundException (an unchecked exception) is folded into this catch - a missing file
			// simply means there is nothing to clean up
			exportService.deleteFile(exportFileHandle.fileId());
		} catch (RuntimeException e) {
			log.warn(
				"Failed to delete leaked partial traffic recording export file `{}`.",
				exportFileHandle.fileId(), e
			);
		}
	}

	/**
	 * Copies `length` bytes of a session starting at `startingPosition` from the ring-buffer file
	 * `inputStream` into `outputStream`. If the session physically wraps the end of the ring buffer file
	 * (i.e. `startingPosition + length > diskBufferSizeInBytes`) it is copied in two segments - first the
	 * tail of the file from `startingPosition`, then the remainder from offset 0 - so the wrapped portion
	 * is not silently dropped when a plain `seek + copy of length bytes` runs past EOF (the copy helper
	 * stops at EOF, truncating the exported record). Package-private and static so the wrap handling can be
	 * unit-tested directly against a crafted file.
	 *
	 * @param inputStream           the ring-buffer file input stream to read from
	 * @param outputStream          the destination stream to write the session bytes to
	 * @param startingPosition      the physical position of the session start within the ring buffer file
	 * @param length                the total number of bytes to copy (descriptor + records)
	 * @param diskBufferSizeInBytes the physical size of the ring buffer file
	 * @param buffer                a reusable copy buffer
	 */
	static void copyPossiblyWrappingSession(
		@Nonnull RandomAccessFileInputStream inputStream,
		@Nonnull OutputStream outputStream,
		long startingPosition,
		int length,
		long diskBufferSizeInBytes,
		@Nonnull byte[] buffer
	) {
		if (startingPosition + length > diskBufferSizeInBytes) {
			// wrapping session: copy the tail-of-file segment, then the head-of-file remainder
			final int headLength = Math.toIntExact(diskBufferSizeInBytes - startingPosition);
			inputStream.seek(startingPosition);
			IOUtils.copy(inputStream, outputStream, headLength, buffer);
			inputStream.seek(0);
			IOUtils.copy(inputStream, outputStream, length - headLength, buffer);
		} else {
			inputStream.seek(startingPosition);
			IOUtils.copy(inputStream, outputStream, length, buffer);
		}
	}

	private static class ExportSessionSink implements RandomAccessFileSessionSink, Closeable {
		private final IntConsumer updateProgress;
		private final ExportFileHandle exportFileHandle;
		private final long diskBufferSizeInBytes;
		private final long chunkFileSizeInBytes;
		private final long nonExportedSizeLimit;
		private final long exportedSizeLimit;
		private final ZipOutputStream outputStream;
		private final byte[] buffer;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final AtomicBoolean corrupted = new AtomicBoolean();
		private final Runnable finalizer;
		private final TrafficRecordingSettings settings;
		private final OffsetDateTime startTime = OffsetDateTime.now();

		private RandomAccessFileInputStream inputStream;
		private int lastSamplingRate = 0;
		private long exportedSessionCount = 0L;
		private long exportedSessionOriginalSize = 0L;
		private long nonExportedSize = 0L;
		private long currentChunkSize = -1L;
		@Nullable private SessionLocation lastExportedLocation;
		@Nullable private SessionLocation lastSeenLocation;

		public ExportSessionSink(
			long diskBufferSizeInBytes,
			@Nonnull TrafficRecordingSettings settings,
			@Nonnull Runnable finalizer,
			@Nonnull IntConsumer updateProgress,
			@Nonnull ExportFileHandle exportFileHandle
		) throws FileNotFoundException {
			this.diskBufferSizeInBytes = diskBufferSizeInBytes;
			// export by 64kB or half of the disk buffer size if it's lower
			this.nonExportedSizeLimit = Math.min(65_536L, diskBufferSizeInBytes / 2);
			this.chunkFileSizeInBytes = settings.chunkFileSizeInBytes();
			this.exportedSizeLimit = settings.recordingSizeLimitInBytes() == null ? Long.MAX_VALUE : settings.recordingSizeLimitInBytes();
			this.finalizer = finalizer;
			this.settings = settings;
			//noinspection CheckForOutOfMemoryOnLargeArrayAllocation
			this.buffer = new byte[8192];
			this.updateProgress = updateProgress;
			this.exportFileHandle = exportFileHandle;
			this.outputStream = new ZipOutputStream(new BufferedOutputStream(this.exportFileHandle.outputStream()));
		}

		@Override
		public void initSourceInputStream(@Nonnull RandomAccessFileInputStream inputStream) {
			Assert.isPremiseValid(!this.closed.get(), "Session sink is already closed.");
			this.inputStream = inputStream;
		}

		@Override
		public void onSessionLocationsUpdated(@Nonnull Deque<SessionLocation> sessionLocations, int realSamplingRate) {
			Assert.isPremiseValid(!this.closed.get(), "Session sink is already closed.");
			this.lastSamplingRate = realSamplingRate;
			// first update the non-exported size
			updateNonExportedSize(sessionLocations);
			// if the non-exported size grows too much, export it to a file
			if (this.nonExportedSize > this.nonExportedSizeLimit) {
				exportAllSessionLocations(sessionLocations);
			}
		}

		@Override
		public void onClose(@Nonnull Deque<SessionLocation> sessionLocations, int realSamplingRate) {
			this.lastSamplingRate = realSamplingRate;
			exportAllSessionLocations(sessionLocations);
		}

		/**
		 * Returns the file for fetch associated with the export file handle.
		 *
		 * @return the file for fetch associated with the export file handle
		 */
		@Nullable
		public FileForFetch getFileForFetch() {
			return this.corrupted.get() ?
				null :
				this.exportFileHandle
					.fileForFetchFuture()
					.getNow(null);
		}

		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true)) {
				IOUtils.close(
					() -> new UnexpectedIOException("Failed to close ZIP output stream."),
					() -> {
						if (this.currentChunkSize > -1) {
							this.outputStream.closeEntry();
						}
						final OffsetDateTime finishTime = OffsetDateTime.now();
						this.outputStream.putNextEntry(new ZipEntry("metadata.txt"));
						// write with an explicit charset so the archive is portable regardless of the platform default
						this.outputStream.write("Traffic recording: \n".getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - started at " + this.startTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - finished at " + finishTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - requested sampling rate " + this.settings.samplingRate() + "%").getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - real sampling rate " + this.lastSamplingRate + "%").getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - duration " + StringUtils.formatDuration(Duration.between(this.startTime, finishTime))).getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - exported " + this.exportedSessionCount + " sessions").getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - exported " + StringUtils.formatByteSize(this.exportedSessionOriginalSize) + " of data").getBytes(StandardCharsets.UTF_8));
						this.outputStream.write(("\n   - task was" + getFinishCondition(this.settings)).getBytes(StandardCharsets.UTF_8));
					},
					this.outputStream::close
				);
			}
		}

		/**
		 * Exports all session locations provided in the deque to the final destination. The method increments through
		 * the deque of session locations and compresses the session data starting from the last exported location or
		 * the beginning if it hasn't been defined. Each session's data is processed and marked as exported by adjusting
		 * the non-exported size and updating the last exported location reference.
		 *
		 * @param sessionLocations a deque containing session locations to be processed for export. Each location provides
		 *                         details about the sequence order and file position of the session data.
		 */
		private void exportAllSessionLocations(@Nonnull Deque<SessionLocation> sessionLocations) {
			boolean export = this.lastExportedLocation == null;
			for (SessionLocation next : sessionLocations) {
				if (export) {
					compressToFinalDestination(next);
					this.nonExportedSize -= next.location().recordLength();
					this.lastExportedLocation = next;
					final long currentFileSize = this.exportFileHandle.size();
					this.updateProgress.accept((int) (((float) currentFileSize / (float) this.exportedSizeLimit) * 100.0));
					if (currentFileSize > this.exportedSizeLimit) {
						this.finalizer.run();
						break;
					}
				} else if (this.lastExportedLocation.equals(next)) {
					export = true;
				}
			}
		}

		/**
		 * Compresses the session data, specified by the given session location, into a final destination
		 * such as a zip archive. This method handles writing the session's binary data to an output stream,
		 * ensuring that the data is partitioned into chunks of a defined size. If the current chunk size
		 * exceeds the predetermined limit, the method closes the current chunk and prepares for the next one.
		 *
		 * @param sessionLocation the location of the session data to be compressed, providing details
		 *                        about its sequence order and file position within the underlying file
		 */
		private void compressToFinalDestination(@Nonnull SessionLocation sessionLocation) {
			try {
				if (this.currentChunkSize == -1) {
					this.outputStream.putNextEntry(new ZipEntry("traffic_recording_" + sessionLocation.sequenceOrder() + ".bin"));
					this.currentChunkSize = 0;
				}
				final int bytesToWrite = sessionLocation.location().recordLength();
				// a session may physically wrap the end of the ring buffer file - copy it wrap-aware so the
				// tail segment (which lives back at offset 0) is not silently truncated at EOF
				copyPossiblyWrappingSession(
					this.inputStream, this.outputStream,
					sessionLocation.location().startingPosition(), bytesToWrite,
					this.diskBufferSizeInBytes, this.buffer
				);
				this.outputStream.flush();
				this.currentChunkSize += bytesToWrite;
				this.exportedSessionCount++;
				this.exportedSessionOriginalSize += bytesToWrite;

				if (this.currentChunkSize >= this.chunkFileSizeInBytes) {
					this.outputStream.closeEntry();
					this.currentChunkSize = -1;
				}
			} catch (Exception e) {
				this.corrupted.set(true);
				this.finalizer.run();
			} finally {
				if (this.exportFileHandle.size() > this.exportedSizeLimit) {
					this.finalizer.run();
				}
			}
		}

		/**
		 * Updates the non-exported size field by iterating over the provided deque of session locations
		 * in reverse order, calculating the size of the unprocessed session records, and stopping
		 * when the last seen location is encountered. The method also updates the reference to the
		 * last seen location in the deque.
		 *
		 * @param sessionLocations a deque containing session locations to be inspected for updating
		 *                         the non-exported size
		 */
		private void updateNonExportedSize(@Nonnull Deque<SessionLocation> sessionLocations) {
			final Iterator<SessionLocation> it = sessionLocations.descendingIterator();
			SessionLocation tailLocation = null;
			while (it.hasNext()) {
				final SessionLocation previous = it.next();
				if (tailLocation == null) {
					tailLocation = previous;
				}
				if (previous.equals(this.lastSeenLocation)) {
					break;
				}
				this.nonExportedSize += previous.location().recordLength();
			}
			this.lastSeenLocation = tailLocation;
		}

	}

}
