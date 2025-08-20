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

package io.evitadb.externalApi.observability.task;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.executor.ClientInfiniteCallableTask;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.file.ExportFileService.ExportFileHandle;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry.EvitaEventGroup;
import io.evitadb.externalApi.observability.task.JfrRecorderTask.RecordingSettings;
import io.evitadb.utils.StringUtils;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.Optional.ofNullable;

/**
 * Task is responsible for recording JFR events.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class JfrRecorderTask extends ClientInfiniteCallableTask<RecordingSettings, FileForFetch> {
	/**
	 * JFR recording instance.
	 */
	private final Recording recording;
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

	public JfrRecorderTask(
		@Nonnull String[] allowedEvents,
		@Nullable Long maxSizeInBytes,
		@Nullable Long maxAgeInSeconds,
		@Nonnull ExportFileService exportFileService
	) {
		super(
			JfrRecorderTask.class.getSimpleName(),
			"JFR recording",
			new RecordingSettings(allowedEvents, maxSizeInBytes, maxAgeInSeconds),
			(task) -> ((JfrRecorderTask) task).start(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED
		);
		this.recording = new Recording();
		this.targetFile = exportFileService.createTempFile(
			"jfr_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".jfr"
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
		try {
			this.recording.setToDisk(true);
			this.recording.setDestination(this.targetFile);
			final RecordingSettings settings = getStatus().settings();
			ofNullable(settings.maxSizeInBytes()).ifPresent(this.recording::setMaxSize);
			ofNullable(settings.maxAgeInSeconds()).map(Duration::ofSeconds).ifPresent(this.recording::setMaxAge);
			final Set<String> allowedEvents = new HashSet<>(Arrays.asList(settings.allowedEvents()));

			// first disable all JDK events, that are not wanted
			enableJdkEvents(allowedEvents);

			// then register all custom event groups
			enableEvitaEvents(allowedEvents);

			this.recording.start();

			try {
				this.finalizationLatch.await();

				this.recording.stop();
				this.updateTaskNameAndTraits("JFR recording stopped (compressing output)");

				final String fileName = "jfr_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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
		}
	}

	@Override
	protected void stopInternal() {
		this.finalizationLatch.countDown();
	}

	/**
	 * Disables all JDK events that are not in the allowed set.
	 * @param allowedEvents set of allowed events
	 */
	private void enableJdkEvents(@Nonnull Set<String> allowedEvents) {
		final Set<String> unrolledAllowedEvents = EvitaJfrEventRegistry.getJdkEventGroups()
			.entrySet()
			.stream()
			.filter(entry -> allowedEvents.contains(entry.getKey()))
			.flatMap(entry -> Arrays.stream(entry.getValue().events()))
			.collect(Collectors.toSet());
		for (EventType eventType : FlightRecorder.getFlightRecorder().getEventTypes()) {
			if (unrolledAllowedEvents.contains(eventType.getName())) {
				this.recording.enable(eventType.getName());
			}
		}
	}

	/**
	 * Enables all Evita events that are in the allowed set.
	 * @param allowedEvents set of allowed events
	 */
	private void enableEvitaEvents(@Nonnull Set<String> allowedEvents) {
		final Map<String, EvitaEventGroup> evitaEventGroups = EvitaJfrEventRegistry.getEvitaEventGroups();
		allowedEvents.stream()
			.map(evitaEventGroups::get)
			.filter(Objects::nonNull)
			.map(EvitaEventGroup::events)
			.flatMap(Arrays::stream)
			.forEach(eventType -> this.recording.enable(eventType).withoutThreshold());
	}

	/**
	 * Contains configuration of event types that will be recorded.
	 *
	 * @param allowedEvents list of allowed events
	 * @param maxSizeInBytes maximum size of the recording in bytes
	 * @param maxAgeInSeconds maximum age of the recording in seconds
	 */
	public record RecordingSettings(
		@Nonnull String[] allowedEvents,
		@Nullable Long maxSizeInBytes,
		@Nullable Long maxAgeInSeconds
	) {

		@Nonnull
		@Override
		public String toString() {
			return "AllowedEvents: " + Arrays.toString(this.allowedEvents) +
				(this.maxSizeInBytes == null ? "" : ", maxSize: " + StringUtils.formatByteSize(this.maxSizeInBytes)) +
				(this.maxAgeInSeconds == null ? "" : ", maxAge: " + StringUtils.formatDuration(Duration.ofSeconds(this.maxAgeInSeconds)));
		}

	}

}
