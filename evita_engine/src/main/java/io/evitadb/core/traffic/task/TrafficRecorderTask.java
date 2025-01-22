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
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.async.ClientInfiniteCallableTask;
import io.evitadb.core.async.Scheduler;
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
		this.trafficRecordingEngine = trafficRecordingEngine;
		if (recordingDuration != null) {
			scheduler.schedule(this::stopInternal, recordingDuration.toMillis(), TimeUnit.MILLISECONDS);
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
	}

	/**
	 * Starts the traffic recording.
	 */
	@Nullable
	private FileForFetch start() {
		final TrafficRecordingSettings settings = getStatus().settings();
		ExportSessionSink exportSessionSink = null;
		try {
			exportSessionSink = settings.exportFile() ? new ExportSessionSink(
				settings.catalogName(),
				this.exportFileService,
				this.trafficRecordingEngine.getTrafficOptions().trafficDiskBufferSizeInBytes(),
				settings,
				this::stopInternal
			) : null;


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
					exportSessionSink::close
				);
			}
		}

		return exportSessionSink == null ?
			null : exportSessionSink.getFileForFetch();
	}

	private static class ExportSessionSink implements SessionSink, Closeable {
		private final ExportFileHandle exportFileHandle;
		private final long chunkFileSizeInBytes;
		private final long nonExportedSizeLimit;
		private final long exportedSizeLimit;
		private final ZipOutputStream outputStream;
		private final byte[] buffer;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final AtomicBoolean corrupted = new AtomicBoolean();
		private final Runnable finalizer;
		private RandomAccessFileInputStream inputStream;
		private long nonExportedSize = 0;
		private long currentChunkSize = -1;
		@Nullable private SessionLocation lastExportedLocation;
		@Nullable private SessionLocation lastSeenLocation;

		public ExportSessionSink(
			@Nonnull String catalogName,
			@Nonnull ExportFileService exportFileService,
			long diskBufferSizeInBytes,
			@Nonnull TrafficRecordingSettings settings,
			@Nonnull Runnable finalizer
		) throws FileNotFoundException {
			this.nonExportedSizeLimit = diskBufferSizeInBytes / 2;
			this.chunkFileSizeInBytes = settings.chunkFileSizeInBytes();
			this.exportedSizeLimit = settings.recordingSizeLimitInBytes() == null ? Long.MAX_VALUE : settings.recordingSizeLimitInBytes();
			this.finalizer = finalizer;
			//noinspection CheckForOutOfMemoryOnLargeArrayAllocation
			this.buffer = new byte[8192];

			final String fileName = "traffic_recording_" + catalogName + "_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
			this.exportFileHandle = exportFileService.storeFile(
				fileName + ".zip",
				"Traffic recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) +
					" with sampling rate " + settings.samplingRate() + "%" + getFinishCondition(settings) + ".",
				"application/zip",
				this.getClass().getSimpleName()
			);

			this.outputStream = new ZipOutputStream(new BufferedOutputStream(this.exportFileHandle.outputStream()));
		}

		@Override
		public void initSourceInputStream(@Nonnull RandomAccessFileInputStream inputStream) {
			Assert.isPremiseValid(!this.closed.get(), "Session sink is already closed.");
			this.inputStream = inputStream;
		}

		@Override
		public void onSessionLocationsUpdated(@Nonnull Deque<SessionLocation> sessionLocations) {
			Assert.isPremiseValid(!this.closed.get(), "Session sink is already closed.");
			// first update the non-exported size
			updateNonExportedSize(sessionLocations);
			// if the non-exported size grows too much, export it to a file
			if (this.nonExportedSize > this.nonExportedSizeLimit) {
				exportAllSessionLocations(sessionLocations);
			}
		}

		@Override
		public void onClose(@Nonnull Deque<SessionLocation> sessionLocations) {
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
					if (this.exportFileHandle.size() > this.exportedSizeLimit) {
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
