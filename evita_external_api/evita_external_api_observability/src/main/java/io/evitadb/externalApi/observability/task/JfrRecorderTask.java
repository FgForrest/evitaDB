/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.core.async.ClientCallableTask;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.externalApi.observability.exception.JfRException;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry.EvitaEventGroup;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry.JdkEventGroup;
import io.evitadb.externalApi.observability.task.JfrRecorderTask.RecordingSettings;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Task is responsible for recording JFR events.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class JfrRecorderTask extends ClientCallableTask<RecordingSettings, FileForFetch> {
	/**
	 * JFR recording instance.
	 */
	private final Recording recording;
	/**
	 * Target file where the JFR recording will be stored.
	 */
	private final FileForFetch targetFile;
	/**
	 * Absolute path to the target file.
	 */
	private final Path targetFilePath;

	public JfrRecorderTask(
		@Nonnull String[] allowedEvents,
		@Nullable Long maxSizeInBytes,
		@Nullable Long maxAgeInSeconds,
		@Nonnull ExportFileService exportFileService
	) {
		super(
			"JFR recording",
			new RecordingSettings(allowedEvents, maxSizeInBytes, maxAgeInSeconds),
			(task) -> ((JfrRecorderTask) task).start()
		);
		this.recording = new Recording();
		this.targetFile = exportFileService.createFile(
			"jfr_recording_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".jfr",
			"JFR recording started at " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " with events: " + Arrays.toString(allowedEvents) + ".",
			"application/octet-stream",
			this.getClass().getSimpleName()
		);
		this.targetFilePath = exportFileService.getFilePath(targetFile);
	}

	@Override
	public boolean cancel() {
		stop();
		return super.cancel();
	}

	/**
	 * Starts the JFR recording.
	 * @return File where the recording will be stored.
	 */
	@Nonnull
	private FileForFetch start() {
		try {
			this.recording.setToDisk(true);
			this.recording.setDestination(this.targetFilePath);
			final RecordingSettings settings = getStatus().settings();
			ofNullable(settings.maxSizeInBytes()).ifPresent(this.recording::setMaxSize);
			ofNullable(settings.maxAgeInSeconds()).map(Duration::ofSeconds).ifPresent(this.recording::setMaxAge);
			final Set<String> allowedEvents = new HashSet<>(Arrays.asList(settings.allowedEvents()));

			// first disable all JDK events, that are not wanted
			disableUnwantedJdkEvents(allowedEvents);

			// then register all custom event groups
			enableEvitaEvents(allowedEvents);

			this.recording.start();
			return this.targetFile;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Disables all JDK events that are not in the allowed set.
	 * @param allowedEvents set of allowed events
	 */
	private void disableUnwantedJdkEvents(@Nonnull Set<String> allowedEvents) {
		final Set<String> disabledJdkEvents = EvitaJfrEventRegistry.getJdkEventGroups()
			.entrySet()
			.stream()
			.filter(entry -> !allowedEvents.contains(entry.getKey()))
			.map(Entry::getValue)
			.map(JdkEventGroup::events)
			.flatMap(Arrays::stream)
			.collect(Collectors.toSet());
		for (EventType eventType : FlightRecorder.getFlightRecorder().getEventTypes()) {
			if (disabledJdkEvents.contains(eventType.getName())) {
				this.recording.disable(eventType.getName());
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
			.forEach(eventType -> recording.enable(eventType).withoutThreshold());
	}

	/**
	 * Stops the JFR recording.
	 * @return File where the recording was stored.
	 */
	@Nonnull
	private FileForFetch stop() {
		if (this.recording.getState() != RecordingState.RUNNING) {
			throw new JfRException("Recording is not running.");
		}
		this.recording.stop();
		return this.targetFile;
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
	}

}
