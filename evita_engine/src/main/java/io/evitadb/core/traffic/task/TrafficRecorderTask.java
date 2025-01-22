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
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.file.ExportFileService.ExportFileHandle;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.store.spi.SessionSink;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
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
	 * Target file where the JFR recording will be stored.
	 */
	private final Path targetFile;
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
		int samplingRate,
		boolean exportFile,
		@Nullable Duration recordingDuration,
		@Nullable Long recordingSizeLimitInBytes,
		long chunkFileSizeInBytes,
		@Nonnull TrafficRecordingEngine trafficRecordingEngine,
		@Nonnull ExportFileService exportFileService
	) {
		super(
			TrafficRecorderTask.class.getSimpleName(),
			"Traffic recording",
			new TrafficRecordingSettings(samplingRate, exportFile, recordingDuration, recordingSizeLimitInBytes, chunkFileSizeInBytes),
			(task) -> ((TrafficRecorderTask) task).start(),
			recordingDuration == null && recordingSizeLimitInBytes == null ?
				new TaskTrait[]{TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED} :
				new TaskTrait[]{TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED}
		);
		this.targetFile = exportFileService.createTempFile(
			"traffic_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".jfr"
		);
		this.exportFileService = exportFileService;
		this.trafficRecordingEngine = trafficRecordingEngine;
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
		try {
			final TrafficRecordingSettings settings = getStatus().settings();
			this.trafficRecordingEngine.startRecording(
				settings.samplingRate(),
				settings.exportFile() ?
					new ExportSessionSink(
						this.trafficRecordingEngine.getTrafficOptions().trafficDiskBufferSizeInBytes(),
						settings.chunkFileSizeInBytes()
					) : null
			);

			// start recording
			this.finalizationLatch.await();

			// stop recording
			this.updateTaskNameAndTraits("Traffic recording stopped (compressing output)");

			final String fileName = "traffic_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
			final ExportFileHandle exportFileHandle = this.exportFileService.storeFile(
				fileName + ".zip",
				"Traffic recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) +
					" with sampling rate " + settings.samplingRate() + "%" + getFinishCondition(settings) + ".",
				"application/zip",
				this.getClass().getSimpleName()
			);

			try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(exportFileHandle.outputStream()))) {
				zipOutputStream.putNextEntry(new ZipEntry(fileName + ".jfr"));
				// copy contents of the JFR recording to the zip file
				Files.copy(this.targetFile, zipOutputStream);

				log.info("JFR recording export completed.");
				return exportFileHandle.fileForFetchFuture().getNow(null);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to compress and store JFR recording: `" + this.targetFile + "`!",
					"Failed to compress and store JFR recording!",
					e
				);
			} finally {
				try {
					Files.deleteIfExists(this.targetFile);
				} catch (IOException e) {
					log.error(
						"Failed to delete temporary JFR recording file: `" + this.targetFile + "`!",
						e
					);
				}
			}
		} catch (InterruptedException e) {
			this.trafficRecordingEngine.stopRecording();
			throw new GenericEvitaInternalError("JFR recording task finished abnormally (interrupt).", e);
		}

	}

	private static class ExportSessionSink implements SessionSink {
		private final long nonExportedSizeLimit;
		private final long chunkFileSizeInBytes;
		private long nonExportedSize = 0;
		private FileChannel fileChannel;
		private SessionLocation lastExportedLocation;
		private SessionLocation lastSeenLocation;

		public ExportSessionSink(long diskBufferSizeInBytes, long chunkFileSizeInBytes) {
			this.nonExportedSizeLimit = diskBufferSizeInBytes / 2;
			this.chunkFileSizeInBytes = chunkFileSizeInBytes;
		}

		@Override
		public void initSourceFileChannel(@Nonnull FileChannel fileChannel) {
			this.fileChannel = fileChannel;
		}

		@Override
		public void onSessionLocationsUpdated(@Nonnull Deque<SessionLocation> sessionLocations) {
			// first update the non-exported size
			final Iterator<SessionLocation> it = sessionLocations.descendingIterator();
			while (it.hasNext()) {
				final SessionLocation previous = it.next();
				if (previous.equals(this.lastSeenLocation)) {
					break;
				}
				this.nonExportedSize += previous.fileLocation().recordLength();
			}
			// if the non-exported size grows too much, export it to a file
			if (this.nonExportedSize > this.nonExportedSizeLimit) {
				boolean export = false;
				final Iterator<SessionLocation> persistingIt = sessionLocations.iterator();
				while (persistingIt.hasNext()) {
					final SessionLocation next = persistingIt.next();
					if (export) {
						//this.fileChannel.transferTo();
						this.nonExportedSize -= next.fileLocation().recordLength();
					} else if (this.lastExportedLocation == null || this.lastExportedLocation.equals(next)) {
						export = true;
					}
				}
			}
		}

	}

}
