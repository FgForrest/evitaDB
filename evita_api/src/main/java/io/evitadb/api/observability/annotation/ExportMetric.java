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

package io.evitadb.api.observability.annotation;

import io.evitadb.api.configuration.metric.MetricType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that exports a specific JFR event field as a Prometheus metric.
 *
 * This annotation is applied to fields within JFR event classes that extend
 * {@link io.evitadb.core.metric.event.CustomMetricsExecutionEvent}. Unlike
 * {@link ExportDurationMetric @ExportDurationMetric} and {@link ExportInvocationMetric @ExportInvocationMetric}
 * which operate at the class level, this annotation provides fine-grained control over individual
 * event fields, allowing you to export multiple metrics from a single event.
 *
 * When a JFR event is emitted, the observability framework reads the annotated field's value and
 * updates the corresponding Prometheus metric. The metric type (COUNTER, GAUGE, HISTOGRAM, or
 * SUMMARY) determines how the value is handled and aggregated over time.
 *
 * **Metric naming convention:**
 * The final Prometheus metric name follows the pattern:
 * `io_evitadb_{group}_{eventName}_{metricName}`
 *
 * Where:
 * - `{group}` is derived from the event's package/EventGroup
 * - `{eventName}` is the event class name with "Event" suffix removed (converted to snake_case)
 * - `{metricName}` is either the `metricName()` attribute value or the field name if not specified
 *
 * **Example usage:**
 * ```java
 * {@literal @}ExportInvocationMetric(label = "Query finished")
 * public class FinishedEvent extends AbstractQueryEvent {
 *
 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 * {@literal @}HistogramSettings(factor = 1.9)
 * private long planDurationMilliseconds;
 *
 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 * {@literal @}HistogramSettings(unit = "records", factor = 4)
 * private int scanned;
 *
 * {@literal @}ExportMetric(metricName = "estimated", metricType = MetricType.HISTOGRAM)
 * {@literal @}HistogramSettings(unit = "complexity", factor = 22)
 * private long estimatedComplexity;
 * }
 * ```
 *
 * This produces three metrics:
 * - `io_evitadb_query_finished_plan_duration_milliseconds` (histogram)
 * - `io_evitadb_query_finished_scanned` (histogram with custom buckets)
 * - `io_evitadb_query_finished_estimated` (histogram renamed from estimatedComplexity field)
 *
 * **Metric type selection guide:**
 * - **COUNTER**: Use for values that only increase (e.g., total errors, bytes transmitted)
 * - **GAUGE**: Use for values that go up and down (e.g., queue size, active sessions)
 * - **HISTOGRAM**: Use for distributions of values (e.g., latencies, sizes, counts)
 * - **SUMMARY**: Use for client-side quantile calculations (alternative to HISTOGRAM)
 *
 * **Combination with other annotations:**
 * - Use {@link HistogramSettings @HistogramSettings} to configure bucket boundaries for HISTOGRAM
 * metrics
 * - Use {@link ExportMetricLabel @ExportMetricLabel} on other fields to add dimensions to all
 * metrics in the event
 * - Typically combined with class-level {@link ExportInvocationMetric @ExportInvocationMetric}
 * or {@link ExportDurationMetric @ExportDurationMetric}
 *
 * **Thread-safety:**
 * All generated Prometheus metrics are thread-safe and can be updated concurrently from multiple
 * event emissions.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 * @see MetricType
 * @see ExportDurationMetric
 * @see ExportInvocationMetric
 * @see ExportMetricLabel
 * @see HistogramSettings
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportMetric {

	/**
	 * The metric field name to use in the final Prometheus metric identifier.
	 *
	 * This value becomes part of the metric name following the pattern
	 * `io_evitadb_{group}_{eventName}_{metricName}`. If left empty (default), the Java field
	 * name is used instead, converted to snake_case according to Prometheus naming conventions.
	 *
	 * Use a custom metric name when:
	 * - The field name doesn't make a good metric name (e.g., field is `estimatedComplexity`
	 * but you want metric `estimated`)
	 * - You need to match an existing metric naming convention
	 * - The field name is too verbose or unclear for external monitoring
	 *
	 * **Naming guidelines:**
	 * - Use lowercase with underscores (snake_case)
	 * - Be concise but descriptive
	 * - Avoid redundant prefixes (e.g., use "duration" not "query_duration" since the event
	 * name already includes "query")
	 * - Include units if not obvious from context (e.g., "size_bytes", "duration_millis")
	 */
	String metricName() default "";

	/**
	 * The type of Prometheus metric to create for this field.
	 *
	 * This determines how the field's value is interpreted and aggregated:
	 *
	 * - **{@link MetricType#COUNTER COUNTER}**: The field value is added to a monotonically
	 * increasing counter. Use for cumulative values that only increase. The field should
	 * contain the increment amount, not the total.
	 *
	 * - **{@link MetricType#GAUGE GAUGE}**: The field value directly sets the gauge's current
	 * value, replacing any previous value. Use for measurements that can go up or down (e.g.,
	 * current memory usage, active connections).
	 *
	 * - **{@link MetricType#HISTOGRAM HISTOGRAM}**: The field value is recorded as an
	 * observation in a histogram, which tracks the distribution across predefined buckets.
	 * Combine with {@link HistogramSettings @HistogramSettings} to configure bucket
	 * boundaries. Use for latencies, sizes, or counts where you need percentile calculations.
	 *
	 * - **{@link MetricType#SUMMARY SUMMARY}**: The field value is recorded as an observation
	 * in a summary, which calculates quantiles on the client side. Similar to HISTOGRAM but
	 * with different aggregation trade-offs.
	 *
	 * **Example:**
	 * ```java
	 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)  // For latency distribution
	 * private long executionMillis;
	 *
	 * {@literal @}ExportMetric(metricType = MetricType.GAUGE)      // For current queue size
	 * private int queueSize;
	 *
	 * {@literal @}ExportMetric(metricType = MetricType.COUNTER)    // For error count increment
	 * private int errorsInBatch;
	 * ```
	 *
	 * @see MetricType for detailed descriptions of each type
	 */
	MetricType metricType();

}
