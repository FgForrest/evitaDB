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

package io.evitadb.externalApi.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.file.FileService;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.exception.SingletonTaskAlreadyRunningException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.Evita;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.http.CorsEndpoint;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.observability.agent.ErrorMonitor;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.exception.JfRException;
import io.evitadb.externalApi.observability.io.ObservabilityExceptionHandler;
import io.evitadb.externalApi.observability.logging.CheckJfrRecordingHandler;
import io.evitadb.externalApi.observability.logging.GetJfrRecordingEventTypesHandler;
import io.evitadb.externalApi.observability.logging.StartJfrRecordingHandler;
import io.evitadb.externalApi.observability.logging.StopJfrRecordingHandler;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry.EvitaEventGroup;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry.JdkEventGroup;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.externalApi.observability.metric.PrometheusMetricsHttpService;
import io.evitadb.externalApi.observability.task.JfrRecorderTask;
import io.evitadb.externalApi.observability.task.JfrRecorderTask.RecordingSettings;
import io.evitadb.externalApi.serialization.OffsetDateTimeSerializer;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.utils.Assert;
import jdk.jfr.FlightRecorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * This class is used as an orchestrator for all observability-related tasks. It is responsible for starting and stopping
 * JFR recording, registering Prometheus scraping servlet and registering resource handler for JFR recording file.
 * It also registers endpoints for controlling JFR recording. It iterates over all specified events and registers them
 * within {@link FlightRecorder} for JFR recordings and Prometheus metrics. It supports wildcards specifications, so
 * it is possible to register all custom events from a package at once. It only requires to be inheritor of {@link CustomMetricsExecutionEvent}
 * and to be registered in the {@link EvitaJfrEventRegistry}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservabilityManager {
	public static final String METRICS_SUFFIX = "metrics";
	public static final String LIVENESS_SUFFIX = "liveness";
	/**
	 * Directory where JFR recording file is stored.
	 */
	private static final Path RECORDING_FILE_DIRECTORY_PATH = Path.of(System.getProperty("java.io.tmpdir"), "evita-recording");
	/**
	 * Name of the JFR recording file.
	 */
	private static final String DUMP_FILE_NAME = "recording.jfr";
	/**
	 * Counter for Java errors.
	 */
	private static final AtomicLong JAVA_ERRORS = new AtomicLong();
	/**
	 * Counter for Java OutOfMemory errors.
	 */
	private static final AtomicLong JAVA_OOM_ERRORS = new AtomicLong();
	/**
	 * Counter for evitaDB errors.
	 */
	private static final AtomicLong EVITA_ERRORS = new AtomicLong();
	/**
	 * Name of the OutOfMemoryError class to detect the problems of OOM kind.
	 */
	private static final String OOM_NAME = OutOfMemoryError.class.getSimpleName();

	static {
		ClassLoader classLoader = null;
		do {
			if (classLoader == null) {
				classLoader = MetricHandler.class.getClassLoader();
			} else {
				classLoader = classLoader.getParent();
			}
			try {
				final Class<?> errorMonitorClass = classLoader.loadClass(ErrorMonitor.class.getName());
				final Method setJavaErrorConsumer = errorMonitorClass.getDeclaredMethod("setJavaErrorConsumer", Consumer.class);
				setJavaErrorConsumer.invoke(null, (Consumer<String>) ObservabilityManager::javaErrorEvent);
				final Method setEvitaErrorConsumer = errorMonitorClass.getDeclaredMethod("setEvitaErrorConsumer", Consumer.class);
				setEvitaErrorConsumer.invoke(null, (Consumer<String>) ObservabilityManager::evitaErrorEvent);
			} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
			         InvocationTargetException e) {
				// do nothing, the errors won't be monitored
				log.warn("ErrorMonitor class not found, the Java & evitaDB errors won't be present in metrics.");
			}
		} while (classLoader.getParent() != null);
	}

	/**
	 * Router for observability endpoints.
	 */
	private final PathHandlingService observabilityRouter = new PathHandlingService();
	/**
	 * Observability API config.
	 */
	private final ObservabilityOptions config;
	/**
	 * Headers configuration.
	 */
	private final HeaderOptions headers;
	/**
	 * Evita instance.
	 */
	private final Evita evita;
	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull @Getter private final ObjectMapper objectMapper;

	/**
	 * Method increments the counter of Java errors in the Prometheus metrics.
	 */
	public static void javaErrorEvent(@Nonnull String simpleName) {
		MetricHandler.JAVA_ERRORS_TOTAL.labelValues(simpleName).inc();
		JAVA_ERRORS.incrementAndGet();
		if (simpleName.equals(OOM_NAME)) {
			JAVA_OOM_ERRORS.incrementAndGet();
		}
	}

	/**
	 * Method increments the counter of evitaDB errors in the Prometheus metrics.
	 */
	public static void evitaErrorEvent(@Nonnull String simpleName) {
		MetricHandler.EVITA_ERRORS_TOTAL.labelValues(simpleName).inc();
		EVITA_ERRORS.incrementAndGet();
	}

	public ObservabilityManager(
		@Nonnull HeaderOptions headers,
		@Nonnull ObservabilityOptions config,
		@Nonnull Evita evita
	) {
		this.headers = headers;
		this.config = config;
		this.evita = evita;
		this.objectMapper = new ObjectMapper();
		createAndRegisterPrometheusServlet();
		registerJfrControlEndpoints();
		registerRecordingFileResourceHandler();

		final SimpleModule module = new SimpleModule();
		module.addSerializer(OffsetDateTime.class, new OffsetDateTimeSerializer());
		this.objectMapper.registerModule(module);
	}

	/**
	 * Returns the number of Java errors.
	 *
	 * @return the number of Java errors
	 */
	public long getJavaErrorCount() {
		return JAVA_ERRORS.get();
	}

	/**
	 * Returns the number of Java OutOfMemory errors.
	 *
	 * @return the number of Java OutOfMemory errors
	 */
	public long getJavaOutOfMemoryErrorCount() {
		return JAVA_OOM_ERRORS.get();
	}

	/**
	 * Returns the number of evitaDB errors.
	 *
	 * @return the number of evitaDB errors
	 */
	public long getEvitaErrorCount() {
		return EVITA_ERRORS.get();
	}

	/**
	 * Records readiness of the API to the Prometheus metrics.
	 *
	 * @param apiCode the code of the API taken from {@link ExternalApiProviderRegistrar#getExternalApiCode()}
	 * @param ready   true if the API is ready, false otherwise
	 */
	public void recordReadiness(@Nonnull String apiCode, boolean ready) {
		MetricHandler.API_READINESS.labelValues(apiCode).set(ready ? 1 : 0);
	}

	/**
	 * Records health problem to the Prometheus metrics.
	 *
	 * @param healthProblem the health problem to be recorded
	 */
	public void recordHealthProblem(@Nonnull String healthProblem) {
		MetricHandler.HEALTH_PROBLEMS.labelValues(healthProblem).set(1);
	}

	/**
	 * Clears health problem from the Prometheus metrics.
	 *
	 * @param healthProblem the health problem to be cleared
	 */
	public void clearHealthProblem(@Nonnull String healthProblem) {
		MetricHandler.HEALTH_PROBLEMS.labelValues(healthProblem).set(0);
	}

	/**
	 * Returns list of all available JFR event types.
	 *
	 * @return list of all available JFR event types
	 */
	@Nonnull
	public List<RecordingGroup> getAvailableJfrEventTypes() {
		return Stream.concat(
				EvitaJfrEventRegistry.getEvitaEventGroups()
					.values()
					.stream()
					.sorted(Comparator.comparing(EvitaEventGroup::name))
					.map(it -> new RecordingGroup(it.id(), it.name(), it.description())),
				EvitaJfrEventRegistry.getJdkEventGroups()
					.values()
					.stream()
					.sorted(Comparator.comparing(JdkEventGroup::name))
					.map(it -> new RecordingGroup(it.id(), it.name(), it.description()))
			)
			.toList();
	}

	/**
	 * Starts JFR recording that logs all specified events.
	 */
	@Nonnull
	public ServerTask<RecordingSettings, FileForFetch> start(
		@Nonnull String[] allowedEvents,
		@Nullable Long maxSizeInBytes,
		@Nullable Long maxAgeInSeconds
	) throws JfRException, SingletonTaskAlreadyRunningException {
		Assert.isTrue(
			!this.evita.getConfiguration().server().readOnly(),
			ReadOnlyException::engineReadOnly
		);

		final Collection<JfrRecorderTask> existingTaskStatus = this.evita.management().getTaskStatuses(JfrRecorderTask.class);
		final JfrRecorderTask runningTask = existingTaskStatus.stream().filter(it -> !it.getFutureResult().isDone()).findFirst().orElse(null);
		if (runningTask != null) {
			throw new SingletonTaskAlreadyRunningException(runningTask.getStatus().taskName());
		} else {
			final ServerTask<RecordingSettings, FileForFetch> jfrRecorderTask = new JfrRecorderTask(
				allowedEvents, maxSizeInBytes, maxAgeInSeconds,
				this.evita.management().exportFileService()
			);
			this.evita.getServiceExecutor().submit(jfrRecorderTask);
			return jfrRecorderTask;
		}
	}

	/**
	 * Stops currently running JFR recording and stores recorded content to a file.
	 */
	@Nonnull
	public TaskStatus<?, ?> stop() throws JfRException {
		Assert.isTrue(
			!this.evita.getConfiguration().server().readOnly(),
			ReadOnlyException::engineReadOnly
		);

		final Collection<JfrRecorderTask> existingTaskStatus = this.evita.management().getTaskStatuses(JfrRecorderTask.class);
		final JfrRecorderTask runningTask = existingTaskStatus.stream().filter(it -> !it.getFutureResult().isDone()).findFirst().orElse(null);
		if (runningTask != null) {
			runningTask.stop();
			return runningTask.getStatus();
		} else {
			throw new EvitaInvalidUsageException(
				"JFR recording is not running.",
				"JFR recording is not running. You have to start it first."
			);
		}
	}

	/**
	 * Returns the status of the currently running JFR recording task or empty result if no task is running.
	 *
	 * @return the status of the currently running JFR recording task or empty result if no task is running
	 */
	@Nonnull
	public Optional<TaskStatus<RecordingSettings, FileForFetch>> jfrRecordingTaskStatus() {
		final Collection<JfrRecorderTask> existingTaskStatus = this.evita.management().getTaskStatuses(JfrRecorderTask.class);
		final JfrRecorderTask runningTask = existingTaskStatus.stream().filter(it -> !it.getFutureResult().isDone()).findFirst().orElse(null);
		if (runningTask != null) {
			return of(runningTask.getStatus());
		} else {
			return empty();
		}
	}

	/**
	 * Registers handler for transforming JFR events to Prometheus metrics.
	 */
	public void registerPrometheusMetricHandler() {
		new MetricHandler(this.config).registerHandlers(this.evita);
	}

	/**
	 * Gets {@link HttpService} for observability endpoints.
	 */
	@Nonnull
	public PathHandlingService getObservabilityRouter() {
		return this.observabilityRouter;
	}

	/**
	 * Registers resource handler for file containing JFR recording.
	 */
	private void registerRecordingFileResourceHandler() {
		final File directory = new File(String.valueOf(RECORDING_FILE_DIRECTORY_PATH));
		if (!directory.exists()) {
			Assert.isPremiseValid(
				directory.mkdir(),
				() -> new UnexpectedIOException(
					"Unable to create directory for recording file.",
					"Unable to create directory `" + directory + "` for recording file."
				)
			);
		}
		this.observabilityRouter.addExactPath("/" + DUMP_FILE_NAME, FileService.of(RECORDING_FILE_DIRECTORY_PATH));
	}

	/**
	 * Creates and registers Prometheus scraping servlet for metrics publishing.
	 */
	private void createAndRegisterPrometheusServlet() {
		this.observabilityRouter.addPrefixPath(
			"/" + METRICS_SUFFIX,
			new PrometheusMetricsHttpService(this.evita)
		);
	}

	/**
	 * Registers endpoints for controlling JFR recording.
	 */
	private void registerJfrControlEndpoints() {
		final StartJfrRecordingHandler startLoggingHandler = new StartJfrRecordingHandler(this.evita, this);
		final StopJfrRecordingHandler stopLoggingHandler = new StopJfrRecordingHandler(this.evita, this);
		final CheckJfrRecordingHandler checkJfrRecordingHandler = new CheckJfrRecordingHandler(this.evita, this);
		final GetJfrRecordingEventTypesHandler getJfrRecordingEventTypesHandler = new GetJfrRecordingEventTypesHandler(this.evita, this);

		final CorsEndpoint corsEndpoint = new CorsEndpoint(this.headers);
		corsEndpoint.addMetadataFromEndpoint(startLoggingHandler);
		corsEndpoint.addMetadataFromEndpoint(stopLoggingHandler);
		corsEndpoint.addMetadataFromEndpoint(checkJfrRecordingHandler);
		corsEndpoint.addMetadataFromEndpoint(getJfrRecordingEventTypesHandler);

		this.observabilityRouter.addExactPath(
			"/startRecording",
			corsEndpoint.toHandler(new ObservabilityExceptionHandler(this.objectMapper, startLoggingHandler))
		);
		this.observabilityRouter.addExactPath(
			"/stopRecording",
			corsEndpoint.toHandler(new ObservabilityExceptionHandler(this.objectMapper, stopLoggingHandler))
		);
		this.observabilityRouter.addExactPath(
			"/checkRecording",
			corsEndpoint.toHandler(new ObservabilityExceptionHandler(this.objectMapper, checkJfrRecordingHandler))
		);
		this.observabilityRouter.addExactPath(
			"/getRecordingEventTypes",
			corsEndpoint.toHandler(new ObservabilityExceptionHandler(this.objectMapper, getJfrRecordingEventTypesHandler))
		);
		this.observabilityRouter.addExactPath(
			"/" + LIVENESS_SUFFIX,
			corsEndpoint.toHandler(
				new ObservabilityExceptionHandler(this.objectMapper,
					(ctx, req) -> {
						new ReadinessEvent(ObservabilityProvider.CODE, Prospective.SERVER).finish(Result.READY);
						return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, this.evita.getConfiguration().name());
					})
			)
		);
	}

	/**
	 * Represents a group of JFR events that could be recorded.
	 *
	 * @param name        the name of the group
	 * @param description the description of the group
	 */
	public record RecordingGroup(
		@Nonnull String id,
		@Nonnull String name,
		@Nullable String description
	) {
	}

}
