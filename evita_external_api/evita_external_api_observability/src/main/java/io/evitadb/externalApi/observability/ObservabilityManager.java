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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.core.Evita;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.CorsFilter;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.observability.configuration.ObservabilityConfig;
import io.evitadb.externalApi.observability.exception.JfRException;
import io.evitadb.externalApi.observability.io.ObservabilityExceptionHandler;
import io.evitadb.externalApi.observability.logging.StartLoggingHandler;
import io.evitadb.externalApi.observability.logging.StopLoggingHandler;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.externalApi.observability.metric.provider.CustomEventProvider;
import io.evitadb.externalApi.observability.metric.provider.RegisteredCustomEventProvider;
import io.evitadb.utils.Assert;
import io.prometheus.metrics.exporter.servlet.jakarta.PrometheusMetricsServlet;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import jakarta.servlet.ServletException;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * This class is used as an orchestrator for all observability-related tasks. It is responsible for starting and stopping
 * JFR recording, registering Prometheus scraping servlet and registering resource handler for JFR recording file.
 * It also registers endpoints for controlling JFR recording. It iterates over all specified events and registers them
 * within {@link FlightRecorder} for JFR recordings and Prometheus metrics. It supports wildcards specifications, so
 * it is possible to register all custom events from a package at once. It only requires to be inheritor of {@link CustomMetricsExecutionEvent}
 * and to be registered in the {@link RegisteredCustomEventProvider}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ObservabilityManager {
	/**
	 * JFR recording instance.
	 */
	private final Recording recording;
	/**
	 * Directory where JFR recording file is stored.
	 */
	private static final Path RECORDING_FILE_DIRECTORY_PATH = Path.of(System.getProperty("java.io.tmpdir"), "evita-recording");
	/**
	 * Name of the JFR recording file.
	 */
	private static final String DUMP_FILE_NAME = "recording.jfr";
	/**
	 * Router for observability endpoints.
	 */
	private final PathHandler observabilityRouter = Handlers.path();
	/**
	 * Observability API config.
	 */
	private final ObservabilityConfig config;
	/**
	 * API options part of the config.
	 */
	private final ApiOptions apiOptions;
	/**
	 * Evita instance.
	 */
	private final Evita evita;
	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull @Getter private final ObjectMapper objectMapper = new ObjectMapper();

	public ObservabilityManager(ObservabilityConfig config, ApiOptions apiOptions, Evita evita) {
		this.recording = new Recording();
		this.config = config;
		this.apiOptions = apiOptions;
		this.evita = evita;
		createAndRegisterPrometheusServlet();
		registerJfrControlEndpoints();
		registerRecordingFileResourceHandler();
	}

	/**
	 * Starts JFR recording that logs all specified events.
	 */
	public void start(@Nonnull String[] allowedEvents) throws JfRException {
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
		recording.start();
	}

	/**
	 * Stops currently running JFR recording and stores recorded content to a file.
	 */
	@Nonnull
	public String stop() throws JfRException {
		if (recording.getState() != RecordingState.RUNNING) {
			throw new JfRException("Recording is not running.");
		}
		try {
			recording.dump(Path.of(String.valueOf(RECORDING_FILE_DIRECTORY_PATH), DUMP_FILE_NAME));
			recording.stop();
			final Optional<String> filePath = Arrays.stream(config.getBaseUrls(apiOptions.exposedOn()))
				.map(it -> it + DUMP_FILE_NAME)
				.findFirst();
			if (filePath.isEmpty()) {
				throw new JfRException("Unable to get URL for recording file.");
			} else {
				return filePath.get();
			}
		} catch (IOException e) {
			throw new JfRException("Unable to dump recording.", e);
		}
	}

	/**
	 * Registers handler for transforming JFR events to Prometheus metrics.
	 */
	public void registerPrometheusMetricHandler() {
		new MetricHandler(config).registerHandlers(evita.getExecutor());
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
		final ResourceHandler resourceHandler;
		try (FileResourceManager resourceManager = new FileResourceManager(RECORDING_FILE_DIRECTORY_PATH.toFile(), 100)) {
			resourceHandler = new ResourceHandler((exchange, path) -> {
				if (("/" + DUMP_FILE_NAME).equals(path)) {
					return resourceManager.getResource(DUMP_FILE_NAME);
				} else {
					return null;
				}
			});
			observabilityRouter.addPrefixPath("/", resourceHandler);
		} catch (IOException e) {
			throw new EvitaInternalError(e.getMessage(), e);
		}
	}

	/**
	 * Gets {@link HttpHandler} for observability endpoints.
	 */
	@Nonnull
	public HttpHandler getObservabilityRouter() {
		return new PathNormalizingHandler(observabilityRouter);
	}

	/**
	 * Creates and registers Prometheus scraping servlet for metrics publishing.
	 */
	private void createAndRegisterPrometheusServlet() {
		final DeploymentInfo servletBuilder = Servlets.deployment()
			.setClassLoader(Undertow.class.getClassLoader())
			.setDeploymentName("metrics-deployment")
			.setContextPath("/observability/metrics")
			.addServlets(
				Servlets.servlet("MetricsServlet", PrometheusMetricsServlet.class).addMapping("/*")
			);

		final DeploymentManager servletDeploymentManager = Servlets.defaultContainer().addDeployment(servletBuilder);
		servletDeploymentManager.deploy();

		try {
			observabilityRouter.addPrefixPath("/metrics", servletDeploymentManager.start());
		} catch (ServletException e) {
			throw new EvitaInternalError("Unable to add routing to Prometheus scraping servlet.");
		}
	}

	/**
	 * Registers endpoints for controlling JFR recording.
	 */
	private void registerJfrControlEndpoints() {
		observabilityRouter.addExactPath("/start",
			new BlockingHandler(
				new CorsFilter(
					new ObservabilityExceptionHandler(
						objectMapper,
						new StartLoggingHandler(this)
					),
					config.getAllowedOrigins()
				)
			)
		);
		observabilityRouter.addExactPath("/stop",
			new BlockingHandler(
				new CorsFilter(
					new ObservabilityExceptionHandler(
						objectMapper,
						new StopLoggingHandler(this)
					),
					config.getAllowedOrigins()
				)
			)
		);
	}

	/**
	 * Registers specified events within {@link FlightRecorder}.
	 */
	private static void registerJfrEvents(@Nonnull String[] allowedEvents) {
		for (String event : Arrays.stream(allowedEvents).filter(x -> !x.startsWith("jdk.")).toList()) {
			if (event.endsWith(".*")) {
				final Set<Class<? extends CustomMetricsExecutionEvent>> classes = CustomEventProvider.getEventClassesFromPackage(event);
				for (Class<? extends CustomMetricsExecutionEvent> clazz : classes) {
					if (!Modifier.isAbstract(clazz.getModifiers())) {
						FlightRecorder.register(clazz);
					}
				}
			} else {
				final Class<? extends CustomMetricsExecutionEvent> clazz = CustomEventProvider.getEventClass(event);
				if (!Modifier.isAbstract(clazz.getModifiers())) {
					FlightRecorder.register(clazz);
				}
			}
		}
	}
}
