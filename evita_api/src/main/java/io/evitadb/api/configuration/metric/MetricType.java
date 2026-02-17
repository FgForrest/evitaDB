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

/**
 * Enumeration of Prometheus metric types supported by evitaDB's observability framework.
 *
 * This enum represents the four fundamental metric types defined by the Prometheus monitoring
 * system. Each type serves a specific purpose in tracking different kinds of measurements and
 * statistics. The metric type determines how Prometheus will aggregate, query, and visualize
 * the collected data.
 *
 * These types are used in conjunction with
 * {@link io.evitadb.api.observability.annotation.ExportMetric} annotations to specify how JFR
 * (Java Flight Recorder) event fields should be exported as Prometheus metrics. The metric
 * handler in the observability module automatically creates and registers the appropriate
 * Prometheus metric builders based on these type declarations.
 *
 * Example usage in a JFR event class:
 *
 * ```java
 * public class QueryFinishedEvent extends AbstractQueryEvent {
 *     {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 *     {@literal @}Label("Query execution duration in milliseconds")
 *     private long executionDurationMilliseconds;
 *
 *     {@literal @}ExportMetric(metricType = MetricType.COUNTER)
 *     {@literal @}Label("Query count")
 *     private long queryCount;
 * }
 * ```
 *
 * @see io.evitadb.api.observability.annotation.ExportMetric
 * @see io.evitadb.api.configuration.metric.LoggedMetric
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public enum MetricType {
	/**
	 * A cumulative metric that represents a monotonically increasing counter.
	 *
	 * Counters are ideal for tracking values that only increase over time, such as:
	 * - Total number of requests processed
	 * - Total number of errors encountered
	 * - Total bytes transferred
	 *
	 * The counter value can only increase or be reset to zero on restart. In Prometheus,
	 * counters are typically queried using the `rate()` or `increase()` functions to calculate
	 * the rate of change over time.
	 *
	 * **Thread-safety**: Counter operations are atomic and thread-safe.
	 *
	 * **Prometheus implementation**: Maps to `io.prometheus.metrics.core.metrics.Counter`.
	 */
	COUNTER,

	/**
	 * A metric that represents a single numerical value that can arbitrarily go up and down.
	 *
	 * Gauges are suitable for tracking values that can increase or decrease, such as:
	 * - Current memory usage
	 * - Number of active sessions
	 * - Queue size
	 * - Temperature readings
	 *
	 * Unlike counters, gauges can be set to any value at any time. They represent the current
	 * state of a measured value at the time of scraping.
	 *
	 * **Thread-safety**: Gauge operations are atomic and thread-safe.
	 *
	 * **Prometheus implementation**: Maps to `io.prometheus.metrics.core.metrics.Gauge`.
	 */
	GAUGE,

	/**
	 * A metric that samples observations and counts them in configurable buckets.
	 *
	 * Histograms are used to track the distribution of values over time, particularly useful for:
	 * - Request/response latencies
	 * - Query execution times
	 * - Size distributions (e.g., payload sizes)
	 *
	 * A histogram provides:
	 * - A count of all observations
	 * - A sum of all observed values
	 * - Counts in predefined buckets (allowing calculation of quantiles)
	 *
	 * The bucket boundaries can be configured using
	 * {@link io.evitadb.api.observability.annotation.HistogramSettings}. If no settings are
	 * provided, the default configuration uses exponential buckets starting at 1ms with a
	 * factor of 2.0 and 14 buckets (1, 2, 4, 8, ..., 8192ms).
	 *
	 * **Thread-safety**: Histogram operations are atomic and thread-safe.
	 *
	 * **Prometheus implementation**: Maps to `io.prometheus.metrics.core.metrics.Histogram`.
	 *
	 * @see io.evitadb.api.observability.annotation.HistogramSettings
	 */
	HISTOGRAM,

	/**
	 * A metric that samples observations and provides configurable quantiles over a sliding
	 * time window.
	 *
	 * Summaries are similar to histograms but calculate quantiles on the client side. They are
	 * useful for tracking:
	 * - Percentiles of request durations (e.g., 50th, 95th, 99th percentile)
	 * - Distribution statistics that don't require server-side aggregation
	 *
	 * A summary provides:
	 * - A count of all observations
	 * - A sum of all observed values
	 * - Precalculated quantiles (e.g., median, 95th percentile)
	 *
	 * **Trade-offs vs. HISTOGRAM**:
	 * - Summaries calculate quantiles on the client (evitaDB server), which reduces Prometheus
	 *   server load but prevents aggregation across multiple instances
	 * - Histograms calculate quantiles on the Prometheus server, allowing aggregation but with
	 *   higher query-time cost
	 *
	 * **Thread-safety**: Summary operations are atomic and thread-safe.
	 *
	 * **Prometheus implementation**: Maps to `io.prometheus.metrics.core.metrics.Summary`.
	 */
	SUMMARY
}
