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

package io.evitadb.documentation;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This class generates referential documentation for Java Flight Recorder (JFR) events and metrics generated from them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class JfrDocumentation implements EvitaTestSupport {
	private static final String PATH_PREFIX = "evita_engine/src/main/java/";
	private static final String JFR_REFERENCE_DOCUMENTATION = "documentation/user/en/operate/reference/jfr-events.md";
	private static final String METRICS_DOCUMENTATION = "documentation/user/en/operate/reference/metrics.md";

	@Test
	void updateJfrReferenceDocumentation() throws IOException {
		generateJfrReferenceDocumentation();
		generateMetricsDocumentation();
	}

	/**
	 * Generates reference documentation for all custom JFR events.
	 *
	 * @throws IOException if an I/O error occurs
	 */
	@Disabled
	private void generateJfrReferenceDocumentation() throws IOException {
		final ReflectionLookup lookup = ReflectionLookup.NO_CACHE_INSTANCE;
		final Path jfrReferencePath = getRootDirectory().resolve(JFR_REFERENCE_DOCUMENTATION);
		try (Writer writer = new FileWriter(jfrReferencePath.toFile(), StandardCharsets.UTF_8, false)) {
			// write header
			writer.write("### Java Flight Recorder (JFR) Events\n\n");

			// group events by category
			final Map<String, List<Class<? extends CustomMetricsExecutionEvent>>> groupedEvents = EvitaJfrEventRegistry.getEventClasses()
				.stream()
				.collect(
					Collectors.groupingBy(
						it -> {
							final Category classAnnotation = lookup.getClassAnnotation(it, Category.class);
							Assert.isPremiseValid(classAnnotation != null, "Event class " + it.getName() + " is missing @Category annotation");
							final String[] groups = classAnnotation.value();
							return Arrays.stream(groups).skip(1).collect(Collectors.joining(" / "));
						}
					)
				);

			// write documentation for each category
			groupedEvents.entrySet()
				.stream()
				// sort categories by name
				.sorted(Entry.comparingByKey())
				.forEach(
					group -> {
						try {
							writer.write("#### " + group.getKey() + "\n\n");
							writer.write("<dl>\n");
							group.getValue()
								.stream()
								// sort events by class name
								.sorted(Comparator.comparing(Class::getSimpleName))
								.forEach(
									event -> {
										try {
											writer.write("  <dt><SourceClass>" + PATH_PREFIX + event.getName().replace('.', '/') + ".java</SourceClass> " + lookup.getClassAnnotation(event, Label.class).value() + "</dt>\n");
											writer.write("  <dd>" + lookup.getClassAnnotation(event, Description.class).value() + "</dd>\n");
										} catch (IOException e) {
											throw new RuntimeException(e);
										}
									});
							writer.write("</dl>\n\n");
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				);
		}
	}

	/**
	 * Generates reference documentation for all custom metrics.
	 * @throws IOException if an I/O error occurs
	 */
	private void generateMetricsDocumentation() throws IOException {
		final ReflectionLookup lookup = new ReflectionLookup(ReflectionCachingBehaviour.CACHE);
		final Path jfrReferencePath = getRootDirectory().resolve(METRICS_DOCUMENTATION);
		try (Writer writer = new FileWriter(jfrReferencePath.toFile(), StandardCharsets.UTF_8, false)) {
			// write header
			writer.write("### Metrics\n\n");

			// collect all labels used in metrics
			final List<MetricLabel> labels = EvitaJfrEventRegistry.getEventClasses()
				.stream()
				.flatMap(it -> {
					final Map<Field, List<ExportMetricLabel>> fields = lookup.getFields(it, ExportMetricLabel.class);
					return fields.entrySet()
						.stream()
						.map(
							field -> {
								final ExportMetricLabel annotation = field.getValue().get(0);
								final String label = ofNullable(field.getKey().getAnnotation(Label.class)).map(Label::value).orElse("N/A");
								final String description = ofNullable(field.getKey().getAnnotation(Description.class)).map(Description::value).orElse("N/A");
								return new MetricLabel(
									ofNullable(annotation.value()).filter(value -> !value.isBlank()).orElse(field.getKey().getName()),
									"<strong>" + label + "</strong>: " + description
								);
							})
						// we care only about distinct labels
						.distinct()
						// sort labels by name
						.sorted(Comparator.comparing(MetricLabel::name));
				})
				.toList();

			// write used terms section for each of it
			writer.write("<UsedTerms>\n");
			writer.write("  <h4>Labels used in metrics</h4>\n");
			writer.write("  <dl>\n");
			for (MetricLabel label : labels) {
				writer.write("    <dt>" + label.name() + "</dt>\n");
				writer.write("    <dd>" + label.description() + "</dd>\n");
			}
			writer.write("  </dl>\n");
			writer.write("</UsedTerms>\n\n");

			// group metrics by category
			final Map<String, List<Class<? extends CustomMetricsExecutionEvent>>> groupedEvents = EvitaJfrEventRegistry.getEventClasses()
				.stream()
				.collect(
					Collectors.groupingBy(
						it -> {
							final Category classAnnotation = lookup.getClassAnnotation(it, Category.class);
							Assert.isPremiseValid(classAnnotation != null, "Event class " + it.getName() + " is missing @Category annotation");
							final String[] groups = classAnnotation.value();
							return Arrays.stream(groups).skip(1).collect(Collectors.joining(" / "));
						}
					)
				);

			// write documentation for each category
			groupedEvents.entrySet()
				.stream()
				// sort categories by name
				.sorted(Entry.comparingByKey())
				.forEach(
					group -> {
						try {
							writer.write("#### " + group.getKey() + "\n\n");
							writer.write("<dl>\n");
							group.getValue()
								.stream()
								.flatMap(
									it -> Stream.of(
										// union invocation metric
										ofNullable(lookup.getClassAnnotation(it, ExportInvocationMetric.class)).stream().map(ann -> toInvocationMetric(it, ann, lookup)),
										// duration metric
										ofNullable(lookup.getClassAnnotation(it, ExportDurationMetric.class)).stream().map(ann -> toDurationMetric(it, ann, lookup)),
										// and custom metrics in the class
										lookup.getFields(it, ExportMetric.class).entrySet().stream().map(
											field -> {
												final ExportMetric annotation = field.getValue().get(0);
												final String label = ofNullable(field.getKey().getAnnotation(Label.class)).map(Label::value).orElse("N/A");
												final String description = ofNullable(field.getKey().getAnnotation(Description.class)).map(Description::value).orElse("N/A");
												return new Metric(
													MetricHandler.composeMetricName(it, annotation, of(annotation.metricName()).filter(metricName -> !metricName.isBlank()).orElse(field.getKey().getName())),
													annotation.metricType().name(),
													"<strong>" + label + "</strong>: " + description,
													getLabels(it, lookup)
												);
											}
										)
									).flatMap(Function.identity())
								)
								// sort them by name
								.sorted(Comparator.comparing(Metric::name))
								// and print them
								.forEach(
									metric -> {
										try {
											writer.write("  <dt><code>" + metric.name() + "</code> (" + metric.metricType() +  ")</dt>\n");
											writer.write("  <dd>");
											writer.write(metric.description());
											// if there are labels referred by the metrics list them including link to used terms
											if (metric.labels().length > 0) {
												writer.write("<br/><br/><strong>Labels:</strong> ");
												writer.write(Arrays.stream(metric.labels())
													.map(label -> "<Term>" + label + "</Term>").collect(Collectors.joining(", ")));
												writer.write("<br/>");
											}
											writer.write("</dd>\n");

										} catch (IOException e) {
											throw new RuntimeException(e);
										}
									});
							writer.write("</dl>\n\n");
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				);
		}
	}

	/**
	 * Transform {@link ExportInvocationMetric} to metric record.
	 *
	 * @param eventClass the event class
	 * @param invocationMetric annotation
	 * @param lookup reflection accessor
	 * @return translated metric
	 */
	@Nonnull
	private static Metric toInvocationMetric(
		@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass,
		@Nonnull ExportInvocationMetric invocationMetric,
		@Nonnull ReflectionLookup lookup
	) {
		return new Metric(
			MetricHandler.composeMetricName(eventClass, invocationMetric.value()),
			MetricType.COUNTER.name(),
			invocationMetric.label(),
			getLabels(eventClass, lookup)
		);
	}

	/**
	 * Transform {@link ExportDurationMetric} to metric record.
	 *
	 * @param eventClass the event class
	 * @param durationMetric annotation
	 * @param lookup reflection accessor
	 * @return translated metric
	 */
	@Nonnull
	private static Metric toDurationMetric(
		@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass,
		@Nonnull ExportDurationMetric durationMetric,
		@Nonnull ReflectionLookup lookup
	) {
		return new Metric(
			MetricHandler.composeMetricName(eventClass, durationMetric.value()),
			MetricType.HISTOGRAM.name(),
			durationMetric.label(),
			getLabels(eventClass, lookup)
		);
	}

	/**
	 * Extracts label names in {@link ExportMetricLabel} from the event class.
	 *
	 * @param eventClass the event class
	 * @param lookup reflection accessor
	 * @return array of label names
	 */
	@Nonnull
	private static String[] getLabels(
		@Nonnull Class<?> eventClass,
		@Nonnull ReflectionLookup lookup
	) {
		return lookup.getFields(eventClass, ExportMetricLabel.class)
			.entrySet()
			.stream()
			.map(it -> it.getValue().stream().map(ExportMetricLabel::value).filter(value -> !value.isBlank()).findFirst().orElse(it.getKey().getName()))
			.distinct()
			.sorted()
			.toArray(String[]::new);
	}

	/**
	 * Metrics record used for printing the documentation.
	 *
	 * @param name name of the metric
	 * @param metricType metric type (counter, gauge etc.)
	 * @param description description
	 * @param labels array of label names used in metrics
	 */
	private record Metric(
		@Nonnull String name,
		@Nonnull String metricType,
		@Nonnull String description,
		@Nonnull String[] labels
	) {}

	/**
	 * Metric label record.
	 *
	 * @param name name of the label
	 * @param description description of the label
	 */
	private record MetricLabel(
		@Nonnull String name,
		@Nonnull String description
	) {}

}
