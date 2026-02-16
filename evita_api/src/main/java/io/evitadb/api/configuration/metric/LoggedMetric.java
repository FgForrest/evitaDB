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

package io.evitadb.api.configuration.metric;

import io.evitadb.api.observability.annotation.HistogramSettings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable data transfer object that encapsulates all metadata required to create and register
 * a Prometheus metric in evitaDB's observability framework.
 *
 * This record serves as a bridge between JFR (Java Flight Recorder) events and Prometheus metrics.
 * When the {@link io.evitadb.externalApi.observability.metric.MetricHandler} processes JFR event
 * classes annotated with {@link io.evitadb.api.observability.annotation.ExportMetric}, it creates
 * `LoggedMetric` instances containing all the information needed to build and register the
 * corresponding Prometheus metric objects (Counter, Gauge, Histogram, or Summary).
 *
 * The metric handler uses this record to:
 * 1. Determine which Prometheus metric type to instantiate based on the {@link #type} field
 * 2. Configure the metric with a unique {@link #name} and human-readable {@link #helpMessage}
 * 3. Attach dimensional {@link #labels} for filtering and aggregation in Prometheus queries
 * 4. Apply histogram-specific configuration via {@link #histogramSettings} (for HISTOGRAM
 *    types only)
 *
 * **Metric Naming Convention**: Metric names are automatically converted to snake_case and follow
 * the pattern `io_evitadb_{category}_{event}_{field}`, where:
 * - `category`: The metrics group (e.g., `system`, `query`, `transaction`)
 * - `event`: The JFR event name without the "Event" suffix
 * - `field`: The field name from the JFR event class
 *
 * **Label Dimensions**: Labels enable multi-dimensional data in Prometheus. For example, a query
 * execution metric might include labels like `entity_type` and `prefetched` to allow analysis
 * of performance broken down by collection type and query optimization strategy.
 *
 * **Histogram Configuration**: When {@link #type} is {@link MetricType#HISTOGRAM}, the optional
 * {@link #histogramSettings} parameter controls bucket boundaries. If null, defaults are used
 * (exponential buckets: start=1, factor=2.0, count=14, unit=milliseconds). Custom settings allow
 * tuning the histogram to the expected value distribution for better quantile accuracy.
 *
 * **Thread-Safety**: This record is immutable and thread-safe. Metric registration happens once
 * during server startup, and the same `LoggedMetric` instances are reused across metric updates.
 *
 * **Example Usage**:
 * ```java
 * // In MetricHandler, a LoggedMetric is created from JFR event annotations:
 * final LoggedMetric metric = new LoggedMetric(
 *     "io_evitadb_query_finished_execution_duration_milliseconds",
 *     "The time it took to execute the query",
 *     MetricType.HISTOGRAM,
 *     new HistogramSettings(1, 1.9, 14, "milliseconds"),
 *     "entity_type", "prefetched"
 * );
 *
 * // The metric is then used to build and register a Prometheus Histogram:
 * final Histogram prometheusMetric = Histogram.builder()
 *     .name(metric.name())
 *     .help(metric.helpMessage())
 *     .labelNames(metric.labels())
 *     .classicExponentialUpperBounds(settings.start(), settings.factor(), settings.count())
 *     .register();
 * ```
 *
 * @param name Unique identifier for the metric in Prometheus, typically in snake_case format
 *             (e.g., `io_evitadb_query_finished_execution_duration_milliseconds`). Must conform
 *             to Prometheus naming conventions (alphanumeric and underscores only).
 * @param helpMessage Human-readable description of what the metric measures. This appears in
 *                    Prometheus query interfaces and documentation. Should be concise but
 *                    informative (e.g., "The time it took to execute the selected query plan").
 * @param type The Prometheus metric type that determines aggregation and query semantics.
 * @param histogramSettings Optional configuration for histogram bucket boundaries and units.
 *                          Only applicable when {@link #type} is {@link MetricType#HISTOGRAM}.
 *                          May be null for non-histogram metrics or to use default histogram
 *                          settings (exponential buckets starting at 1ms).
 * @param labels Zero or more label names that define the dimensions of this metric. Labels enable
 *               filtering and grouping in Prometheus queries (e.g., `entity_type`, `catalog_name`).
 *               Must conform to Prometheus label naming conventions. An empty array means the
 *               metric has no dimensions.
 * @see MetricType
 * @see io.evitadb.api.observability.annotation.ExportMetric
 * @see io.evitadb.api.observability.annotation.ExportMetricLabel
 * @see io.evitadb.api.observability.annotation.HistogramSettings
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public record LoggedMetric(
	@Nonnull String name,
	@Nonnull String helpMessage,
	@Nonnull MetricType type,
	@Nullable HistogramSettings histogramSettings,
	@Nonnull String... labels
) {

}
