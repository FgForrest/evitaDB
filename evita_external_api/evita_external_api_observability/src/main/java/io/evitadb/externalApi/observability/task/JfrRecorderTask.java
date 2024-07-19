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
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.observability.exception.JfRException;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.task.JfrRecorderTask.RecordingSettings;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

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

	/**
	 * Registers specified events within {@link FlightRecorder}.
	 */
	private static void registerJfrEvents(@Nonnull String[] allowedEvents) {
		for (String event : Arrays.stream(allowedEvents).filter(x -> !x.startsWith("jdk.")).toList()) {
			if (event.endsWith(".*")) {
				EvitaJfrEventRegistry.getEventClassesFromPackage(event)
					.ifPresent(classes -> {
						for (Class<? extends CustomMetricsExecutionEvent> clazz : classes) {
							if (!Modifier.isAbstract(clazz.getModifiers())) {
								FlightRecorder.register(clazz);
							}
						}
					});
			} else {
				final Class<? extends CustomMetricsExecutionEvent> clazz = EvitaJfrEventRegistry.getEventClass(event);
				if (!Modifier.isAbstract(clazz.getModifiers())) {
					FlightRecorder.register(clazz);
				}
			}
		}
	}

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
	 * Returns the name of the file that will be produced by this task.
	 * @return Name of the file.
	 */
	@Nonnull
	public String getFileName() {
		return this.targetFile.name();
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
			final String[] allowedEvents = settings.allowedEvents();
			registerJfrEvents(allowedEvents);
			final List<EventType> eventTypes = FlightRecorder.getFlightRecorder().getEventTypes();
			for (String event : allowedEvents) {
				if (event.endsWith(".*")) {
					for (EventType eventType : eventTypes) {
						String it = eventType.getName();
						if (it.startsWith(event.substring(0, event.length() - 2))) {
							recording.enable(it).withoutThreshold();
						}
					}
				} else {
					recording.enable(event).withoutThreshold();
				}
			}
			this.recording.start();
			return this.targetFile;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
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
