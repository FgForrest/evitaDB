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
import io.evitadb.core.traffic.TrafficRecordingSettings;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;

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
	private final ExportFileService exportFileService;

	public TrafficRecorderTask(
		int samplingRate,
		@Nullable Duration recordingDuration,
		@Nullable Long recordingSizeLimitInBytes,
		int chunkFileSizeInBytes,
		@Nonnull ExportFileService exportFileService
	) {
		super(
			TrafficRecorderTask.class.getSimpleName(),
			"Traffic recording",
			new TrafficRecordingSettings(samplingRate, recordingDuration, recordingSizeLimitInBytes, chunkFileSizeInBytes),
			(task) -> ((TrafficRecorderTask) task).start(),
			recordingDuration == null && recordingSizeLimitInBytes == null ?
				new TaskTrait[] { TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED } :
				new TaskTrait[] { TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED }
		);
		/*this.recording = new Recording();*/
		this.targetFile = exportFileService.createTempFile(
			"traffic_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".jfr"
		);
		this.exportFileService = exportFileService;
	}

	@Override
	public boolean cancel() {
		stopInternal();
		return super.cancel();
	}

	/**
	 * Starts the JFR recording.
	 */
	@Nullable
	private FileForFetch start() {
		/*try {
			this.recording.setDestination(this.targetFile);
			final TrafficRecordingSettings settings = getStatus().settings();
			ofNullable(settings.maxSizeInBytes()).ifPresent(this.recording::setMaxSize);
			ofNullable(settings.maxAgeInSeconds()).map(Duration::ofSeconds).ifPresent(this.recording::setMaxAge);
			final Set<String> allowedEvents = new HashSet<>(Arrays.asList(settings.allowedEvents()));

			// start recording

			try {
				this.finalizationLatch.await();

				// stop recording

				this.updateTaskNameAndTraits("Traffic recording stopped (compressing output)");

				final String fileName = "traffic_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
				final ExportFileHandle exportFileHandle = this.exportFileService.storeFile(
					fileName + ".zip",
					"JFR recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " with events: " + Arrays.toString(settings.allowedEvents()) + ".",
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
				this.recording.stop();
				throw new GenericEvitaInternalError("JFR recording task finished abnormally (interrupt).", e);
			}

		} catch (IOException e) {
			throw new GenericEvitaInternalError("JFR recording task finished abnormally (I/O exception).", e);
		}*/
		return null;
	}

	@Override
	protected void stopInternal() {
		this.finalizationLatch.countDown();
	}

}
