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
 * Configuration for histogram bucket boundaries in Prometheus HISTOGRAM metrics.
 *
 * This annotation provides fine-grained control over how histogram observations are bucketed,
 * which directly affects the accuracy of percentile calculations and query performance. It can
 * be applied either at the class level (affecting all histogram metrics in the event) or at the
 * field level (affecting only that specific metric).
 *
 * Histograms in Prometheus use predefined buckets to count observations. The bucket boundaries
 * defined here determine the resolution of percentile calculations. Choosing appropriate buckets
 * is crucial:
 * - Too few buckets → poor percentile accuracy
 * - Too many buckets → excessive memory usage and scraping overhead
 * - Wrong bucket range → most observations fall in overflow bucket ("+Inf")
 *
 * This annotation configures **exponential buckets**, where each bucket boundary is `factor`
 * times the previous one, starting from `start`. For example, with `start=1`, `factor=2.0`,
 * `count=14`, the buckets are: 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192,
 * +Inf milliseconds.
 *
 * **Example usage:**
 *
 * Class-level (applies to all histograms in the event):
 * ```java
 * {@literal @}HistogramSettings(factor = 1.65)  // Finer granularity for all histograms
 * {@literal @}ExportDurationMetric(label = "Query duration in milliseconds")
 * public class FinishedEvent extends AbstractQueryEvent {
 * // All histogram fields inherit these settings unless overridden
 * }
 * ```
 *
 * Field-level (overrides class-level settings):
 * ```java
 * public class FinishedEvent extends AbstractQueryEvent {
 *
 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 * {@literal @}HistogramSettings(unit = "records", factor = 4)  // Coarser buckets for counts
 * private int scanned;
 *
 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 * {@literal @}HistogramSettings(unit = "bytes", factor = 3, start = 100)  // Larger starting point
 * private int fetchedSizeBytes;
 * }
 * ```
 *
 * **Choosing appropriate settings:**
 *
 * For **duration measurements** (milliseconds):
 * - Fast operations (< 100ms): `start=1, factor=1.5, count=15` → 1ms to 437ms
 * - Normal operations (< 1s): `start=1, factor=2.0, count=14` → 1ms to 8.2s (default)
 * - Slow operations (> 1s): `start=10, factor=2.0, count=14` → 10ms to 82s
 *
 * For **size measurements** (bytes, records):
 * - Small counts (< 1000): `start=1, factor=2.0, count=14` → 1 to 8192
 * - Large counts: `start=10, factor=4, count=12` → 10 to 167 million
 *
 * For **complexity scores** (unitless):
 * - Highly variable: `start=1, factor=22, count=10` → spans many orders of magnitude
 *
 * **Trade-offs:**
 * - Lower `factor` (e.g., 1.5) → more buckets, better accuracy, higher memory usage
 * - Higher `factor` (e.g., 4.0) → fewer buckets, lower accuracy, less memory usage
 * - Higher `count` → wider range coverage but more buckets
 *
 * **Thread-safety:**
 * The annotation itself is immutable metadata. The resulting Prometheus histogram metrics are
 * thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 * @see ExportMetric
 * @see ExportDurationMetric
 * @see io.evitadb.api.configuration.metric.MetricType#HISTOGRAM
 */
@Target({ElementType.TYPE, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HistogramSettings {

	/**
	 * The unit of measurement for the histogram observations.
	 *
	 * This value appears in Prometheus metric metadata and helps monitoring tools interpret the
	 * values correctly. It's purely informational and doesn't affect the metric calculation, but
	 * it's crucial for dashboard building and metric discovery.
	 *
	 * **Common units:**
	 * - **Time durations**: "milliseconds", "seconds", "microseconds"
	 * - **Sizes**: "bytes", "kilobytes", "megabytes"
	 * - **Counts**: "records", "entities", "requests", "operations"
	 * - **Complexity**: "complexity" (unitless score), "score"
	 * - **Percentages**: "percent" (0-100 scale)
	 *
	 * Use singular form and lowercase. Avoid abbreviations unless they're standard (e.g., "ms"
	 * is less clear than "milliseconds").
	 *
	 * **Example:**
	 * ```java
	 * {@literal @}HistogramSettings(unit = "milliseconds")  // Duration
	 * private long executionTime;
	 *
	 * {@literal @}HistogramSettings(unit = "records")       // Count
	 * private int rowsScanned;
	 *
	 * {@literal @}HistogramSettings(unit = "bytes")         // Size
	 * private int payloadSize;
	 * ```
	 */
	String unit() default "milliseconds";

	/**
	 * The value of the lowest (first) histogram bucket boundary.
	 *
	 * This should be set to the smallest value you expect to observe frequently. Observations
	 * below this value will fall into the implicit "underflow" bucket (not explicitly created,
	 * but tracked in the cumulative count).
	 *
	 * **Guidelines:**
	 * - For durations in milliseconds, `1` is a good default for sub-millisecond precision
	 * - For large counts or sizes, use a higher start (e.g., `10`, `100`) to avoid excessive
	 * buckets for small values
	 * - If most observations are > 10ms, use `start=10` to avoid wasting buckets on rare values
	 *
	 * **Examples:**
	 * - Fast queries (mostly < 10ms): `start=1`
	 * - Typical queries (mostly 10-100ms): `start=10`
	 * - File sizes (mostly > 1KB): `start=1024` (with unit="bytes")
	 * - Record counts (mostly > 100): `start=100` (with unit="records")
	 *
	 * The default value of `1` is appropriate for millisecond-scale duration measurements.
	 */
	int start() default 1;

	/**
	 * The multiplication factor between consecutive bucket boundaries.
	 *
	 * Each bucket boundary is calculated as: `boundary[i] = start * (factor ^ i)`
	 *
	 * This creates **exponential buckets** where the bucket width grows exponentially. This is
	 * ideal for measurements that span multiple orders of magnitude (which is common for
	 * durations and sizes).
	 *
	 * **Factor selection guide:**
	 * - **`factor = 1.5`**: Fine-grained buckets, ~40% growth per bucket
	 * - Use when: High precision needed, values vary within 1-2 orders of magnitude
	 * - Example: Fast operations (1-100ms) where you need accurate percentiles
	 * - **`factor = 2.0`**: Standard buckets (default), 100% growth per bucket
	 * - Use when: Balanced precision and memory, values span 2-3 orders of magnitude
	 * - Example: General purpose queries (1ms to 10s)
	 * - **`factor = 4.0`**: Coarse buckets, 300% growth per bucket
	 * - Use when: Wide range coverage, less precision needed
	 * - Example: Record counts, file sizes that vary widely
	 * - **`factor = 10.0` or higher**: Very coarse buckets
	 * - Use when: Extremely wide range, rough percentiles acceptable
	 *
	 * **Trade-offs:**
	 * Lower factor → more buckets → better percentile accuracy → more memory per time series
	 * Higher factor → fewer buckets → coarser percentiles → less memory per time series
	 *
	 * The default `2.0` provides a good balance for most use cases.
	 *
	 * **Example:**
	 * With `start=1, factor=2.0, count=4`:
	 * Buckets are: 1, 2, 4, 8, +Inf
	 *
	 * With `start=1, factor=10, count=4`:
	 * Buckets are: 1, 10, 100, 1000, +Inf
	 */
	double factor() default 2.0;

	/**
	 * The number of exponential bucket boundaries to create.
	 *
	 * This determines how many explicit buckets are created, which directly affects:
	 * - The range covered: `max_value = start * (factor ^ count)`
	 * - Memory usage per time series: proportional to bucket count
	 * - Percentile accuracy: more buckets → better resolution
	 *
	 * The final bucket is always "+Inf" (implicitly added) to catch any observations above the
	 * highest explicit boundary.
	 *
	 * **Count selection guide:**
	 * - **12-15 buckets**: Good balance for most use cases
	 * - **< 10 buckets**: Use when memory is constrained or values have narrow range
	 * - **> 20 buckets**: Use when extremely high precision needed (rare)
	 *
	 * **Calculating range coverage:**
	 * Max covered value = `start * (factor ^ count)`
	 *
	 * Examples with `start=1`:
	 * - `factor=2.0, count=14` → covers up to 8,192 (good for milliseconds)
	 * - `factor=2.0, count=20` → covers up to 524,288 (~8.7 minutes in ms)
	 * - `factor=4.0, count=10` → covers up to 1,048,576 (~17 minutes in ms)
	 *
	 * **Memory consideration:**
	 * Each unique label combination creates a separate time series with `count` buckets. With
	 * high cardinality labels, this multiplies quickly:
	 * - 10 catalogs × 50 entity types × 14 buckets = 7,000 bucket counters per metric
	 *
	 * The default `14` buckets with `factor=2.0` covers 1ms to 8.2s, suitable for most evitaDB
	 * operations.
	 */
	int count() default 14;

}
