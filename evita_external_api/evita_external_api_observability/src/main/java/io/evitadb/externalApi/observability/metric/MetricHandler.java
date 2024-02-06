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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.configuration.metric.LoggedMetric;
import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.core.metric.annotation.UsedMetric;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.observability.configuration.ObservabilityConfig;
import io.evitadb.externalApi.observability.metric.provider.CustomEventProvider;
import io.evitadb.function.ChainableConsumer;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.core.metrics.Metric;
import io.prometheus.metrics.core.metrics.Summary;
import io.prometheus.metrics.instrumentation.jvm.*;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import lombok.extern.slf4j.Slf4j;
import org.jboss.threads.EnhancedQueueExecutor;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

/**
 * This class orchestrates listening for JFR events and transforming them into Prometheus metrics which are in the
 * periodically published to Prometheus scraping endpoint.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class MetricHandler {
	private final ObservabilityConfig observabilityConfig;

	private static final Map<String, Runnable> DEFAULT_JVM_METRICS;
	private static final String DEFAULT_JVM_METRICS_NAME = "AllMetrics";

	static {
		DEFAULT_JVM_METRICS = Map.of(
			"AllMetrics", () -> JvmMetrics.builder().register(),
			"JvmThreadsMetrics", () -> JvmThreadsMetrics.builder().register(),
			"JvmBufferPoolMetrics", () -> JvmBufferPoolMetrics.builder().register(),
			"JvmClassLoadingMetrics", () -> JvmClassLoadingMetrics.builder().register(),
			"JvmCompilationMetrics", () -> JvmCompilationMetrics.builder().register(),
			"JvmGarbageCollectorMetrics", () -> JvmGarbageCollectorMetrics.builder().register(),
			"JvmMemoryPoolAllocationMetrics", () -> JvmMemoryPoolAllocationMetrics.builder().register(),
			"JvmMemoryMetrics", () -> JvmMemoryMetrics.builder().register(),
			"JvmRuntimeInfoMetric", () -> JvmRuntimeInfoMetric.builder().register(),
			"ProcessMetrics", () -> ProcessMetrics.builder().register()
		);
	}

	public MetricHandler(@Nonnull ObservabilityConfig observabilityConfig) {
		this.observabilityConfig = observabilityConfig;
	}

	/**
	 * Based on configuration, this method enables collecting and publishing metrics into Prometheus scraping endpoint.
	 * If no configuration of such event has been provided, all JVM and custom events are logged. For limiting the logged
	 * events, you can create a configuration file specified in `evita-configuration.yml` name all of allowed packages of custom
	 * events, that should be logged. Reducing JVM logs can be done by specifying allowed categories.
	 *
	 * All JVM categories are:
	 * [AllMetrics, JvmThreadsMetrics, JvmBufferPoolMetrics, JvmClassLoadingMetrics, JvmCompilationMetrics,
	 * JvmGarbageCollectorMetrics, JvmMemoryPoolAllocationMetrics, JvmMemoryMetrics, JvmRuntimeInfoMetric, ProcessMetrics].
	 *
	 * @param executor thread pool that will be used for acquiring a thread for Prometheus events handling
	 */
	public void registerHandlers(EnhancedQueueExecutor executor) {
		final List<String> allowedEventsFromConfig = observabilityConfig.getAllowedEvents();
		final boolean publishAllCustomEvents = allowedEventsFromConfig == null;
		final Set<String> allowedEvents = new HashSet<>(16);
		if (!publishAllCustomEvents) {
			allowedEvents.addAll(allowedEventsFromConfig);
		} else {
			allowedEvents.addAll(CustomEventProvider.getEventClasses().stream().map(Class::getName).toList());
		}

		final Map<String, Set<String>> eventMetricClasses = new HashMap<>(4);

		if (allowedEventsFromConfig != null && allowedEventsFromConfig.stream().anyMatch(MetricHandler::isCustomEventClassName) ) {
			Optional.of(allowedEventsFromConfig).stream()
				.flatMap(Collection::stream)
				.filter(DEFAULT_JVM_METRICS::containsKey)
				.forEach(e -> DEFAULT_JVM_METRICS.get(e).run());
		} else {
			DEFAULT_JVM_METRICS.get(DEFAULT_JVM_METRICS_NAME).run();
		}

		for (String loggingEvent : allowedEvents) {
			final Set<String> classNames = new HashSet<>(4);
			if (loggingEvent.endsWith(".*")) {
				final String packageName = loggingEvent.substring(0, loggingEvent.length() - 2);
				final Set<Class<? extends CustomMetricsExecutionEvent>> classes = CustomEventProvider.getEventClassesFromPackage(packageName);
				for (Class<?> aClass : classes) {
					classNames.add(aClass.getName());
				}
			} else {
				if (isCustomEventClassName(loggingEvent)) {
					final Class<?> existingClass = CustomEventProvider.getEventClass(loggingEvent);
					classNames.add(existingClass.getName());
				}
				classNames.add(loggingEvent);
			}
			eventMetricClasses.put(loggingEvent, classNames);
		}

		executor.execute(() -> {
			try (var recordingStream = new RecordingStream()) {
				for (Entry<String, Set<String>> eventClasses : eventMetricClasses.entrySet()) {
					final Set<String> classNames = eventClasses.getValue();
					final Set<Class<? extends CustomMetricsExecutionEvent>> existingEventClasses = CustomEventProvider.getEventClasses();

					for (Class<? extends CustomMetricsExecutionEvent> eventClass : existingEventClasses) {
						// if event is enabled
						if (classNames.contains(eventClass.getName())) {
							FlightRecorder.register(eventClass);
							recordingStream.enable(eventClass);
							final Map<Field, List<Annotation>> fieldsAnnotations = ReflectionLookup.NO_CACHE_INSTANCE.getFields(eventClass);

							ChainableConsumer<RecordedEvent> oldValue = null;
							for (Entry<Field, List<Annotation>> fieldAnnotationsEntry : fieldsAnnotations.entrySet()) {
								final List<Annotation> annotations = fieldAnnotationsEntry.getValue();
								final Optional<UsedMetric> usedMetric = annotations.stream()
									.filter(a -> a instanceof UsedMetric)
									.map(a -> (UsedMetric) a)
									.findFirst();
								final Optional<Label> label = annotations.stream()
									.filter(a -> a instanceof Label)
									.map(a -> (Label) a)
									.findFirst();
								if (usedMetric.isEmpty() || label.isEmpty()) {
									continue;
								}
								final UsedMetric usedMetricAnnotation = usedMetric.get();
								final Label labelAnnotation = label.get();

								final String metricName = StringUtils.toSnakeCase(eventClass.getName() + fieldAnnotationsEntry.getKey().getName().toUpperCase() + usedMetricAnnotation.metricType());
								final Metric metric = buildAndRegisterMetric(new LoggedMetric(metricName, labelAnnotation.value(), usedMetricAnnotation.metricType()));
								if (oldValue == null) {
									oldValue = updateMetricValue(usedMetricAnnotation.metricType(), metric, fieldAnnotationsEntry.getKey().getName());
								} else {
									oldValue = oldValue.andThen(
										updateMetricValue(usedMetricAnnotation.metricType(), metric, fieldAnnotationsEntry.getKey().getName())
									);
								}
							}

							if (oldValue != null) {
								recordingStream.onEvent(eventClass.getName(), oldValue);
							}
						}
					}
				}
				recordingStream.start();
			}
		});
	}

	private static boolean isCustomEventClassName(@Nonnull String string) {
		return string.contains(".") && !(string.charAt(string.length() - 1) == '.');
	}

	private static ChainableConsumer<RecordedEvent> updateMetricValue(MetricType metricType, Metric metric, String fieldName) {
		return switch (metricType) {
			case COUNTER -> (recordedEvent) -> ((Counter) metric).inc(recordedEvent.getDouble(fieldName));
			case GAUGE -> (recordedEvent) -> ((Gauge) metric).set(recordedEvent.getDouble(fieldName));
			case HISTOGRAM -> (recordedEvent) -> ((Histogram) metric).observe(recordedEvent.getDouble(fieldName));
			case SUMMARY -> (recordedEvent) -> ((Summary) metric).observe(recordedEvent.getDouble(fieldName));
		};
	}

	private static Metric buildAndRegisterMetric(LoggedMetric metric) {
		final String name = StringUtils.toSnakeCase(metric.name());
		return switch (metric.type()) {
			case GAUGE -> Gauge.builder()
				.name(name)
				.help(metric.helpMessage())
				.register();
			case COUNTER -> Counter.builder()
				.name(name)
				.help(metric.helpMessage())
				.register();
			case HISTOGRAM -> Histogram.builder()
				.name(name)
				.help(metric.helpMessage())
				.register();
			case SUMMARY -> Summary.builder()
				.name(name)
				.help(metric.helpMessage())
				.register();
		};
	}
}
