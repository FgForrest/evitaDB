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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.configuration.metric.LoggedMetric;
import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ClientInfiniteCallableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.function.ChainableConsumer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.core.metrics.Histogram.Builder;
import io.prometheus.metrics.core.metrics.Metric;
import io.prometheus.metrics.core.metrics.Summary;
import io.prometheus.metrics.instrumentation.jvm.*;
import io.prometheus.metrics.model.snapshots.Unit;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This class orchestrates listening for JFR events and transforming them into Prometheus metrics which are in the
 * periodically published to Prometheus scraping endpoint.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class MetricHandler {
	// define a Prometheus counter for errors
	public static final Counter JAVA_ERRORS_TOTAL = Counter.builder()
		.name("jvm_errors_total")
		.labelNames("error_type")
		.help("Total number of internal Java errors")
		.register();
	public static final Counter EVITA_ERRORS_TOTAL = Counter.builder()
		.name("io_evitadb_errors_total")
		.labelNames("error_type")
		.help("Total number of internal evitaDB errors")
		.register();
	public static final Gauge HEALTH_PROBLEMS = Gauge.builder()
		.name("io_evitadb_probe_health_problem")
		.labelNames("problem_type")
		.help("Health problems detected in the system")
		.register();
	public static final Gauge API_READINESS = Gauge.builder()
		.name("io_evitadb_probe_api_readiness")
		.labelNames("api_type")
		.help("Status of the API readiness (internal HTTP call check)")
		.register();
	private static final Map<String, Metric> REGISTERED_METRICS = new HashMap<>(64);
	private static final Pattern EVENT = Pattern.compile("Event");
	private static final Map<String, Runnable> DEFAULT_JVM_METRICS;
	private static final String DEFAULT_JVM_METRICS_NAME = "AllMetrics";
	private static final String NOT_APPLICABLE = "N/A";

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

	private final ObservabilityOptions observabilityConfig;

	/**
	 * Composes the name of the metric from the event class, export metric and field name.
	 *
	 * @param eventClass   event class
	 * @param exportMetric export metric annotation
	 * @param fieldName    name of the field in the JFR event
	 * @return composed name of the metric
	 */
	@Nonnull
	public static String composeMetricName(
		@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass,
		@Nonnull ExportMetric exportMetric,
		@Nonnull String fieldName
	) {
		final String metricName = of(exportMetric.metricName())
			.filter(it -> !it.isBlank())
			.orElse(fieldName);

		// if the metric contains dots, it means it's already fully composed
		return metricName.contains(".") ?
			metricName : composeMetricName(eventClass, metricName);
	}

	/**
	 * Creates name of the metric from the event class and metric name.
	 *
	 * @param eventClass event class
	 * @param metricName name of the metric itself
	 * @return composed name of the metric
	 */
	@Nonnull
	public static String composeMetricName(
		@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass,
		@Nonnull String metricName
	) {
		return StringUtils.toSnakeCase(
			EvitaJfrEventRegistry.getMetricsGroup(eventClass) +
				"." + EVENT.matcher(eventClass.getSimpleName()).replaceFirst("") + "." +
				metricName
		);
	}

	/**
	 * Converts the getter method into the exporter of the metric label.
	 *
	 * @param getter getter method
	 * @return exporter of the metric label
	 */
	@Nonnull
	private static MetricLabelExporter convertGetterToMetricLabelExporter(@Nonnull Method getter) {
		final String propertyName = ReflectionLookup.getPropertyNameFromMethodName(getter.getName());
		final ExportMetricLabel exportMetricLabel = getter.getAnnotation(ExportMetricLabel.class);
		return new MetricLabelExporter(
			of(exportMetricLabel.value()).filter(it -> !it.isBlank()).orElse(propertyName),
			recordedEvent -> recordedEvent.getString(propertyName)
		);
	}

	/**
	 * Converts the field into the exporter of the metric label.
	 *
	 * @param field the field to export data from
	 * @return exporter of the metric label
	 */
	@Nonnull
	private static MetricLabelExporter convertFieldToMetricLabelExporter(@Nonnull Field field) {
		final ExportMetricLabel exportMetricLabel = field.getAnnotation(ExportMetricLabel.class);
		final String fieldName = field.getName();
		return new MetricLabelExporter(
			of(exportMetricLabel.value()).filter(it -> !it.isBlank()).orElse(fieldName),
			recordedEvent -> recordedEvent.getString(fieldName)
		);
	}

	/**
	 * Stores the lambda into the reference and chains it with the previous lambda if there is one.
	 *
	 * @param reference reference with the composition lambda
	 * @param lambda    lambda to be stored
	 */
	private static void chainLambda(
		@Nonnull AtomicReference<ChainableConsumer<RecordedEvent>> reference,
		@Nonnull ChainableConsumer<RecordedEvent> lambda
	) {
		if (reference.get() == null) {
			reference.set(lambda);
		} else {
			reference.set(reference.get().andThen(lambda));
		}
	}

	/**
	 * Checks if the provided string is a custom event class name.
	 *
	 * @param string name of the event class
	 * @return true if the string is a custom event class name, false otherwise
	 */
	private static boolean isCustomEventClassName(@Nonnull String string) {
		return string.contains(".") && !(string.charAt(string.length() - 1) == '.');
	}

	/**
	 * Creates a lambda that updates the value of the metric based on the type of the metric.
	 *
	 * @param metricType         type of the metric
	 * @param metric             metric to be updated
	 * @param fieldName          name of the field in the JFR event
	 * @param labelValueExporter exporters of the metric labels
	 * @return lambda that updates the value of the metric
	 */
	@SafeVarargs
	@Nonnull
	private static ChainableConsumer<RecordedEvent> updateMetricValue(
		@Nonnull MetricType metricType,
		@Nonnull Metric metric,
		@Nonnull String fieldName,
		@Nonnull Function<RecordedEvent, String>... labelValueExporter
	) {
		if (ArrayUtils.isEmpty(labelValueExporter)) {
			return switch (metricType) {
				case COUNTER -> (recordedEvent) -> ((Counter) metric).inc(recordedEvent.getDouble(fieldName));
				case GAUGE -> (recordedEvent) -> ((Gauge) metric).set(recordedEvent.getDouble(fieldName));
				case HISTOGRAM -> (recordedEvent) -> ((Histogram) metric).observe(recordedEvent.getDouble(fieldName));
				case SUMMARY -> (recordedEvent) -> ((Summary) metric).observe(recordedEvent.getDouble(fieldName));
			};
		} else {
			return switch (metricType) {
				case COUNTER -> (recordedEvent) -> ((Counter) metric)
					.labelValues(Arrays.stream(labelValueExporter).map(it -> ofNullable(it.apply(recordedEvent)).orElse(NOT_APPLICABLE)).toArray(String[]::new))
					.inc(recordedEvent.getDouble(fieldName));
				case GAUGE -> (recordedEvent) -> ((Gauge) metric)
					.labelValues(Arrays.stream(labelValueExporter).map(it -> ofNullable(it.apply(recordedEvent)).orElse(NOT_APPLICABLE)).toArray(String[]::new))
					.set(recordedEvent.getDouble(fieldName));
				case HISTOGRAM -> (recordedEvent) -> ((Histogram) metric)
					.labelValues(Arrays.stream(labelValueExporter).map(it -> ofNullable(it.apply(recordedEvent)).orElse(NOT_APPLICABLE)).toArray(String[]::new))
					.observe(recordedEvent.getDouble(fieldName));
				case SUMMARY -> (recordedEvent) -> ((Summary) metric)
					.labelValues(Arrays.stream(labelValueExporter).map(it -> ofNullable(it.apply(recordedEvent)).orElse(NOT_APPLICABLE)).toArray(String[]::new))
					.observe(recordedEvent.getDouble(fieldName));
			};
		}
	}

	/**
	 * Builds and registers a metric based on the provided logged metric.
	 *
	 * @param metric            logged metric
	 * @param registeredMetrics set of registered metrics
	 * @return built and registered metric
	 */
	@Nonnull
	private static Metric buildAndRegisterMetric(@Nonnull LoggedMetric metric, @Nonnull Map<String, Metric> registeredMetrics) {
		final String name = StringUtils.toSnakeCase(metric.name());
		if (registeredMetrics.containsKey(name)) {
			return registeredMetrics.get(name);
		} else {
			final Metric newMetric = switch (metric.type()) {
				case GAUGE -> Gauge.builder()
					.name(name)
					.labelNames(metric.labels())
					.help(metric.helpMessage())
					.register();
				case COUNTER -> Counter.builder()
					.name(name)
					.labelNames(metric.labels())
					.help(metric.helpMessage())
					.register();
				case HISTOGRAM -> {
					final Builder builder = Histogram.builder()
						.name(name);
					ofNullable(metric.histogramSettings())
						.ifPresentOrElse(
							settings -> {
								builder.classicExponentialUpperBounds(settings.start(), settings.factor(), settings.count());
								if (!settings.unit().isBlank()) {
									builder.unit(new Unit(settings.unit()));
								}
							},
							() -> builder.classicExponentialUpperBounds(1, 2.0, 14)
								.unit(new Unit("milliseconds")));
					yield builder
						.labelNames(metric.labels())
						.help(metric.helpMessage())
						.register();
				}
				case SUMMARY -> Summary.builder()
					.name(name)
					.labelNames(metric.labels())
					.help(metric.helpMessage())
					.register();
			};
			registeredMetrics.put(name, newMetric);
			return newMetric;
		}
	}

	public MetricHandler(@Nonnull ObservabilityOptions observabilityConfig) {
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
	 * @param evita evita instance
	 */
	public void registerHandlers(@Nonnull Evita evita) {
		Scheduler scheduler = evita.getServiceExecutor();

		registerJvmMetrics();
		final Set<Class<? extends CustomMetricsExecutionEvent>> allowedMetrics = getAllowedEventSet();

		final MetricTask metricTask = new MetricTask(allowedMetrics);
		scheduler.submit((ServerTask<?,?>) metricTask);

		try {
			metricTask.getInitialized()
				.thenAccept(unused -> {
					// emit the start event
					evita.emitStartObservabilityEvents();
				}).get(1, TimeUnit.MINUTES);
		} catch (Exception e) {
			log.error("Failed to initialize metric handler in time. Metrics might not work. Start events are not emitted.");
		}
	}

	/**
	 * Registers JVM metrics based on the configuration.
	 */
	private void registerJvmMetrics() {
		final List<String> allowedEventsFromConfig = this.observabilityConfig.getAllowedEvents();

		if (allowedEventsFromConfig != null && allowedEventsFromConfig.stream().noneMatch(DEFAULT_JVM_METRICS_NAME::equals)) {
			allowedEventsFromConfig
				.stream()
				.filter(it -> !MetricHandler.isCustomEventClassName(it))
				.map(DEFAULT_JVM_METRICS::get)
				.filter(Objects::nonNull)
				.forEach(Runnable::run);
		} else {
			DEFAULT_JVM_METRICS.get(DEFAULT_JVM_METRICS_NAME).run();
		}
	}

	/**
	 * Method calculates the index of allowed events that should be logged and metrics that should be created.
	 *
	 * @return index of allowed events and metrics
	 */
	@Nonnull
	private Set<Class<? extends CustomMetricsExecutionEvent>> getAllowedEventSet() {
		final List<String> allowedEventsFromConfig = this.observabilityConfig.getAllowedEvents();
		final Set<Class<? extends CustomMetricsExecutionEvent>> knownEvents = EvitaJfrEventRegistry.getEventClasses();

		final Set<String> configuredEvents = new HashSet<>(16);
		configuredEvents.addAll(
			Objects.requireNonNullElseGet(
				allowedEventsFromConfig,
				() -> knownEvents.stream().map(Class::getName).toList()
			)
		);

		final Set<Class<? extends CustomMetricsExecutionEvent>> allowedEventSet = new HashSet<>(16);
		for (String loggingEvent : configuredEvents) {
			if (loggingEvent.endsWith(".*")) {
				final String packageName = loggingEvent.substring(0, loggingEvent.length() - 2);
				EvitaJfrEventRegistry.getEventClassesFromPackage(packageName)
					.ifPresent(it -> allowedEventSet.addAll(Arrays.asList(it)));
			} else {
				ofNullable(EvitaJfrEventRegistry.getEventClass(loggingEvent))
					.ifPresent(allowedEventSet::add);
			}
		}
		return allowedEventSet;
	}

	/**
	 * Task that listens for JFR events and transforms them into Prometheus metrics.
	 */
	private static class MetricTask extends ClientInfiniteCallableTask<Void, Void> {
		private final AtomicReference<RecordingStream> recordingStream = new AtomicReference<>();
		@Getter private final CompletableFuture<Boolean> initialized = new CompletableFuture<>();

		public MetricTask(
			@Nonnull Set<Class<? extends CustomMetricsExecutionEvent>> allowedMetrics
		) {
			super(
				MetricHandler.class.getSimpleName(),
				"Metric handler",
				null,
				theTask -> {
					final ReflectionLookup lookup = ReflectionLookup.NO_CACHE_INSTANCE;
					try (final RecordingStream recordingStream = new RecordingStream()) {
						((MetricTask) theTask).recordingStream.set(recordingStream);
						for (Class<? extends CustomMetricsExecutionEvent> eventClass : allowedMetrics) {
							FlightRecorder.register(eventClass);
							recordingStream.enable(eventClass);

							final Optional<Name> name = Optional.ofNullable(lookup.getClassAnnotation(eventClass, Name.class));
							final String eventName = name.map(Name::value).orElse(eventClass.getName());
							AtomicReference<ChainableConsumer<RecordedEvent>> lambdaRef = new AtomicReference<>();

							final Map<Field, List<Annotation>> fieldsAnnotations = lookup.getFields(eventClass);
							final List<MetricLabelExporter> labelExporters = Stream.concat(
								lookup.findAllGettersHavingAnnotationDeeply(eventClass, ExportMetricLabel.class)
									.stream()
									.map(MetricHandler::convertGetterToMetricLabelExporter),
								fieldsAnnotations.entrySet()
									.stream()
									.filter(it -> it.getValue().stream().anyMatch(ExportMetricLabel.class::isInstance))
									.map(Entry::getKey)
									.map(MetricHandler::convertFieldToMetricLabelExporter)
							).toList();

							final String[] labelNames = labelExporters
								.stream()
								.map(MetricLabelExporter::labelName)
								.toArray(String[]::new);
							//noinspection unchecked
							final Function<RecordedEvent, String>[] labelValueExporters = labelExporters.stream()
								.map(MetricLabelExporter::labelValueAccessor)
								.toArray(Function[]::new);

							Optional.ofNullable(lookup.getClassAnnotation(eventClass, ExportDurationMetric.class))
								.ifPresent(it -> {
									final String metricName = composeMetricName(eventClass, it.value());
									final HistogramSettings histogramSettings = lookup.getClassAnnotation(eventClass, HistogramSettings.class);
									final Metric durationMetric = buildAndRegisterMetric(
										new LoggedMetric(metricName, it.label(), MetricType.HISTOGRAM, histogramSettings, labelNames),
										REGISTERED_METRICS
									);
									if (ArrayUtils.isEmpty(labelValueExporters)) {
										chainLambda(
											lambdaRef,
											recordedEvent -> ((Histogram) durationMetric)
												.observe(recordedEvent.getDuration().toMillis())
										);
									} else {
										chainLambda(
											lambdaRef,
											recordedEvent -> ((Histogram) durationMetric)
												.labelValues(
													Arrays.stream(labelValueExporters)
														.map(exporter -> ofNullable(exporter.apply(recordedEvent)).orElse(NOT_APPLICABLE))
														.toArray(String[]::new))
												.observe(recordedEvent.getDuration().toMillis())
										);
									}
								});
							Optional.ofNullable(lookup.getClassAnnotation(eventClass, ExportInvocationMetric.class))
								.ifPresent(it -> {
									final String metricName = composeMetricName(eventClass, it.value());
									final Metric invocationMetric = buildAndRegisterMetric(
										new LoggedMetric(metricName, it.label(), MetricType.COUNTER, null, labelNames),
										REGISTERED_METRICS
									);
									if (ArrayUtils.isEmpty(labelValueExporters)) {
										chainLambda(lambdaRef, recordedEvent -> ((Counter) invocationMetric).inc());
									} else {
										chainLambda(
											lambdaRef,
											recordedEvent -> ((Counter) invocationMetric)
												.labelValues(
													Arrays.stream(labelValueExporters)
														.map(exporter -> ofNullable(exporter.apply(recordedEvent))
															.orElse(NOT_APPLICABLE)).toArray(String[]::new)
												)
												.inc()
										);
									}
								});

							for (Entry<Field, List<Annotation>> fieldAnnotationsEntry : fieldsAnnotations.entrySet()) {
								final List<Annotation> annotations = fieldAnnotationsEntry.getValue();
								final Optional<ExportMetric> exportMetric = annotations.stream()
									.filter(ExportMetric.class::isInstance)
									.map(ExportMetric.class::cast)
									.findFirst();
								final Optional<HistogramSettings> histogramSettings = annotations.stream()
									.filter(HistogramSettings.class::isInstance)
									.map(HistogramSettings.class::cast)
									.findFirst();
								final Optional<Label> label = annotations.stream()
									.filter(Label.class::isInstance)
									.map(Label.class::cast)
									.findFirst();
								final Optional<Name> nameAnnotation = annotations.stream()
									.filter(Name.class::isInstance)
									.map(Name.class::cast)
									.findFirst();
								if (exportMetric.isEmpty() || label.isEmpty()) {
									continue;
								}

								final ExportMetric exportMetricAnnotation = exportMetric.get();
								final Label labelAnnotation = label.get();
								final String fieldName = nameAnnotation.map(Name::value)
									.orElseGet(() -> fieldAnnotationsEntry.getKey().getName());

								final String metricName = composeMetricName(eventClass, exportMetricAnnotation, fieldName);
								final Metric metric = buildAndRegisterMetric(
									new LoggedMetric(
										metricName,
										labelAnnotation.value(),
										exportMetricAnnotation.metricType(),
										histogramSettings.orElse(null),
										labelNames
									),
									REGISTERED_METRICS
								);
								chainLambda(
									lambdaRef,
									updateMetricValue(
										exportMetricAnnotation.metricType(),
										metric,
										fieldName,
										labelValueExporters
									)
								);
							}

							if (lambdaRef.get() != null) {
								recordingStream.onEvent(eventName, lambdaRef.get());
							}
						}

						((MetricTask) theTask).initialized.complete(true);
						recordingStream.start();
					}

					return null;
				}
			);
		}

		@Override
		protected void stopInternal() {
			this.recordingStream.get().close();
		}

		@Override
		public boolean cancel() {
			stopInternal();
			return super.cancel();
		}

	}

	/**
	 * Represents the exporter of the metric label identified in the event class by annotation {@link ExportMetricLabel}.
	 *
	 * @param labelName          name of the label
	 * @param labelValueAccessor accessor of the label value
	 */
	private record MetricLabelExporter(
		@Nonnull String labelName,
		@Nonnull Function<RecordedEvent, String> labelValueAccessor
	) {
	}

}
