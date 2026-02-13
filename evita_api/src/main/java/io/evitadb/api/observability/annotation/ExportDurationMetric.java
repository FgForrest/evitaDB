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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that automatically exports a JFR event's duration as a Prometheus HISTOGRAM metric.
 *
 * This annotation is applied at the class level to JFR event types that extend
 * {@link jdk.jfr.Event} or {@link io.evitadb.core.metric.event.CustomMetricsExecutionEvent}.
 * When an annotated event is emitted, the observability framework automatically creates and
 * updates a Prometheus histogram metric tracking the event's execution duration.
 *
 * The duration is measured between the event's `begin()` and `end()` (or `commit()`) calls,
 * which are typically inherited from JFR's Event class. The resulting metric follows Prometheus
 * histogram conventions, with configurable bucket boundaries (see
 * {@link HistogramSettings @HistogramSettings}).
 *
 * **Metric naming convention:**
 * The final Prometheus metric name follows the pattern:
 * `io_evitadb_{group}_{eventName}_{fieldName}`
 *
 * Where:
 * - `{group}` is derived from the event's package/EventGroup
 * - `{eventName}` is the event class name with "Event" suffix removed
 * - `{fieldName}` is the value of `value()` attribute (default: "durationMilliseconds")
 *
 * **Example usage:**
 * ```java
 * {@literal @}Name("io.evitadb.query.Finished")
 * {@literal @}ExportDurationMetric(label = "Query duration in milliseconds")
 * {@literal @}HistogramSettings(factor = 1.65)
 * public class FinishedEvent extends AbstractQueryEvent {
 * // Event implementation that calls begin() and end()
 * }
 * ```
 *
 * This produces a metric named `io_evitadb_query_finished_duration_milliseconds` with histogram
 * buckets configured by the HistogramSettings annotation.
 *
 * **Combination with other annotations:**
 * - Often combined with {@link ExportInvocationMetric @ExportInvocationMetric} to track both
 * duration and occurrence count
 * - Requires {@link EventGroup @EventGroup} on the event class or its parent for proper naming
 * - Use {@link HistogramSettings @HistogramSettings} to customize bucket boundaries
 *
 * **Thread-safety:**
 * The generated Prometheus histogram metrics are thread-safe and can be updated concurrently
 * from multiple event emissions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 * @see ExportInvocationMetric
 * @see HistogramSettings
 * @see EventGroup
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportDurationMetric {

	/**
	 * The metric field name to use in the final Prometheus metric identifier.
	 *
	 * This value becomes part of the metric name following the pattern
	 * `io_evitadb_{group}_{eventName}_{value}`. The default "durationMilliseconds" is
	 * appropriate for most duration-tracking use cases.
	 *
	 * Use a custom value when:
	 * - You need multiple duration metrics on the same event (rare)
	 * - You want to match an existing metric naming convention
	 * - The default name doesn't clearly describe the measured duration
	 *
	 * Example: For an event tracking compilation time, you might use
	 * `value = "compilationTimeMillis"` to produce a metric like
	 * `io_evitadb_query_plan_compilation_time_millis`.
	 */
	String value() default "durationMilliseconds";

	/**
	 * The human-readable label describing what this duration metric measures.
	 *
	 * This label appears in:
	 * - Prometheus metric metadata (HELP text)
	 * - Monitoring dashboards (Grafana, etc.)
	 * - Metric discovery tools
	 *
	 * The label should be a clear, concise description written as a phrase, typically including
	 * the unit of measurement. Examples:
	 * - "Query duration in milliseconds"
	 * - "Transaction commit time in milliseconds"
	 * - "File compaction duration in milliseconds"
	 *
	 * Good labels help operators quickly understand what the metric measures when browsing
	 * available metrics or building dashboards.
	 */
	String label();

}
