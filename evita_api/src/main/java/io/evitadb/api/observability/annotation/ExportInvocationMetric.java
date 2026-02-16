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
 * Annotation that automatically exports JFR event occurrences as a Prometheus COUNTER metric.
 *
 * This annotation is applied at the class level to JFR event types that extend
 * {@link jdk.jfr.Event} or {@link io.evitadb.core.metric.event.CustomMetricsExecutionEvent}.
 * When an annotated event is emitted (via `commit()`), the observability framework automatically
 * increments a Prometheus counter metric tracking the total number of event occurrences.
 *
 * The counter is monotonically increasing - it only ever goes up (or resets to zero on server
 * restart). This makes it suitable for tracking event frequencies, request counts, error totals,
 * and other cumulative metrics. Use Prometheus's `rate()` or `increase()` functions to calculate
 * the rate of occurrence over time.
 *
 * **Metric naming convention:**
 * The final Prometheus metric name follows the pattern:
 * `io_evitadb_{group}_{eventName}_{fieldName}`
 *
 * Where:
 * - `{group}` is derived from the event's package/EventGroup
 * - `{eventName}` is the event class name with "Event" suffix removed (converted to snake_case)
 * - `{fieldName}` is the value of `value()` attribute (default: "total")
 *
 * **Example usage:**
 * ```java
 * {@literal @}Name("io.evitadb.query.Finished")
 * {@literal @}ExportInvocationMetric(label = "Query finished")
 * {@literal @}ExportDurationMetric(label = "Query duration in milliseconds")
 * public class FinishedEvent extends AbstractQueryEvent {
 * // Each time this event is committed, the counter increments
 * }
 * ```
 *
 * This produces a counter metric named `io_evitadb_query_finished_total` that tracks how many
 * times queries have completed.
 *
 * **Combination with other annotations:**
 * - Commonly paired with {@link ExportDurationMetric @ExportDurationMetric} to track both event
 * count and duration in a single event class
 * - Requires {@link EventGroup @EventGroup} on the event class or its parent for proper naming
 * - Use {@link ExportMetricLabel @ExportMetricLabel} on event fields to add dimensions to the
 * counter (e.g., by catalog name, entity type, API endpoint)
 *
 * **Multi-dimensional metrics:**
 * When combined with {@literal @}ExportMetricLabel fields, this creates a multi-dimensional
 * counter allowing queries like:
 * - Total queries per catalog: `io_evitadb_query_finished_total{catalog_name="products"}`
 * - Total queries per entity type: `io_evitadb_query_finished_total{entity_type="Product"}`
 * - Query rate across all catalogs: `rate(io_evitadb_query_finished_total[5m])`
 *
 * **Thread-safety:**
 * The generated Prometheus counter metrics are thread-safe and can be incremented concurrently
 * from multiple event emissions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 * @see ExportDurationMetric
 * @see ExportMetricLabel
 * @see EventGroup
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportInvocationMetric {

	/**
	 * The metric field name to use in the final Prometheus metric identifier.
	 *
	 * This value becomes part of the metric name following the pattern
	 * `io_evitadb_{group}_{eventName}_{value}`. The default "total" is the Prometheus
	 * convention for counter metrics and should be used in most cases.
	 *
	 * Use a custom value when:
	 * - You need multiple invocation counters on the same event type (rare)
	 * - You want to match an existing metric naming convention
	 * - The metric tracks something other than total occurrences (e.g., "started", "failed")
	 *
	 * Example: For an event tracking failed operations, you might use `value = "failed"` to
	 * produce a metric like `io_evitadb_transaction_commit_failed`.
	 */
	String value() default "total";

	/**
	 * The human-readable label describing what event occurrences this counter tracks.
	 *
	 * This label appears in:
	 * - Prometheus metric metadata (HELP text)
	 * - Monitoring dashboards (Grafana, etc.)
	 * - Metric discovery tools
	 *
	 * The label should be a clear, concise description written as a noun phrase or short
	 * sentence. Examples:
	 * - "Query finished"
	 * - "Transaction committed"
	 * - "Session opened"
	 * - "Cache eviction triggered"
	 *
	 * Good labels help operators quickly understand what the counter measures when browsing
	 * available metrics or building dashboards. Since counters track totals, avoid redundant
	 * words like "total" or "count" in the label.
	 */
	String label();

}
