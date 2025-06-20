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
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.file.ExportFileService.ExportFileHandle;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.store.spi.SessionSink;
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
	private final ExportFileService exportFileService;
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
		@Nonnull ExportFileService exportFileService,
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
		this.exportFileService = exportFileService;
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
		try {
			exportSessionSink = settings.exportFile() ?
				new ExportSessionSink(
					this.trafficRecordingEngine.getTrafficOptions().trafficDiskBufferSizeInBytes(),
					settings,
					this::stopInternal,
					this::updateProgress,
					this.exportFileService.storeFile(
						fileName + ".zip",
						"Traffic recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) +
							" with sampling rate " + settings.samplingRate() + "%" + getFinishCondition(settings) + ".",
						"application/zip",
						this.getClass().getSimpleName()
					)
				) :
				null;

			// start recording
			this.trafficRecordingEngine.startRecording(settings.samplingRate(), exportSessionSink);

			// wait for the recording to be stopped (either manually or by reaching the specified limits)
			this.finalizationLatch.await();

			// stop recording
			this.trafficRecordingEngine.stopRecording();

		} catch (InterruptedException e) {
			this.trafficRecordingEngine.stopRecording();
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
					(IOUtils.IOExceptionThrowingRunnable) exportSessionSink::close
				);
			}
		}

		return exportSessionSink == null ?
			null : exportSessionSink.getFileForFetch();
	}

	/**
	 * A private static class responsible for exporting session data to a compressed archive. This class implements
	 * {@link SessionSink} and {@link Closeable}, enabling it to act as a sink for session data and supporting proper
	 * resource management. The class ensures session data is exported in chunks, handles file compress using
	 * a zip archive, and maintains metadata about the export operation such as exported size and session count.
	 */
	private static class ExportSessionSink implements SessionSink, Closeable {
		private final IntConsumer updateProgress;
		private final ExportFileHandle exportFileHandle;
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
						this.outputStream.write("Traffic recording: \n".getBytes());
						this.outputStream.write(("\n   - started at " + this.startTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes());
						this.outputStream.write(("\n   - finished at " + finishTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).getBytes());
						this.outputStream.write(("\n   - requested sampling rate " + this.settings.samplingRate() + "%").getBytes());
						this.outputStream.write(("\n   - real sampling rate " + this.lastSamplingRate + "%").getBytes());
						this.outputStream.write(("\n   - duration " + StringUtils.formatDuration(Duration.between(this.startTime, finishTime))).getBytes());
						this.outputStream.write(("\n   - exported " + this.exportedSessionCount + " sessions").getBytes());
						this.outputStream.write(("\n   - exported " + StringUtils.formatByteSize(this.exportedSessionOriginalSize) + " of data").getBytes());
						this.outputStream.write(("\n   - task was" + getFinishCondition(this.settings)).getBytes());
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
					this.nonExportedSize -= next.fileLocation().recordLength();
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
				this.inputStream.seek(sessionLocation.fileLocation().startingPosition());
				final int bytesToWrite = sessionLocation.fileLocation().recordLength();
				IOUtils.copy(this.inputStream, this.outputStream, bytesToWrite, this.buffer);
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
				this.nonExportedSize += previous.fileLocation().recordLength();
			}
			this.lastSeenLocation = tailLocation;
		}

	}

}
