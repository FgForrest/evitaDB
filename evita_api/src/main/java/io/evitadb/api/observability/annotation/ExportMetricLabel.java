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
 * Annotation that adds a dimension (label) to all Prometheus metrics exported from a JFR event.
 *
 * This annotation is applied to fields or methods within JFR event classes. The annotated field's
 * value is captured when the event is emitted and attached as a label to all Prometheus metrics
 * derived from that event. This enables multi-dimensional metrics that can be filtered and
 * aggregated by the label values.
 *
 * Labels are fundamental to Prometheus's data model - they allow you to slice metrics by different
 * dimensions (e.g., catalog name, entity type, API endpoint, error type) enabling powerful queries
 * like:
 * - Queries per catalog: `io_evitadb_query_finished_total{catalog_name="products"}`
 * - Query duration by entity type: `io_evitadb_query_finished_duration_milliseconds{entity_type="Product"}`
 * - Cross-catalog aggregation: `sum by (catalog_name) (rate(io_evitadb_query_finished_total[5m]))`
 *
 * **Important label design considerations:**
 * - **Cardinality**: Avoid high-cardinality labels (values that have many unique instances) as
 * they multiply the number of time series and can overwhelm Prometheus. Good labels have a
 * bounded set of values (e.g., catalog names, entity types, API names). Bad labels include
 * user IDs, session IDs, timestamps, or arbitrary strings.
 * - **Consistency**: Use consistent label names across related metrics to enable cross-metric
 * queries and dashboard building.
 * - **Naming**: Label names should be lowercase with underscores (snake_case) and descriptive.
 *
 * **Example usage:**
 * ```java
 * {@literal @}ExportInvocationMetric(label = "Query finished")
 * {@literal @}ExportDurationMetric(label = "Query duration")
 * public class FinishedEvent extends AbstractQueryEvent {
 *
 * {@literal @}ExportMetricLabel
 * private final String catalogName;  // Label: "catalog_name"
 *
 * {@literal @}ExportMetricLabel
 * private final String entityType;   // Label: "entity_type"
 *
 * {@literal @}ExportMetricLabel
 * private String prefetched;         // Label: "prefetched" (values: "yes"/"no")
 *
 * {@literal @}ExportMetric(metricType = MetricType.HISTOGRAM)
 * private long planDurationMilliseconds;
 * }
 * ```
 *
 * This produces metrics with multiple dimensions:
 * ```
 * io_evitadb_query_finished_total{catalog_name="products",entity_type="Product",prefetched="no"} 1543
 * io_evitadb_query_finished_total{catalog_name="products",entity_type="Category",prefetched="yes"} 892
 * io_evitadb_query_finished_duration_milliseconds{catalog_name="products",entity_type="Product",prefetched="no"} 45.2
 * ```
 *
 * **Application to methods:**
 * The annotation can also be applied to getter methods, which is useful when the label value
 * needs to be computed rather than stored directly:
 * ```java
 * {@literal @}ExportMetricLabel("operation_type")
 * public String getOperationType() {
 * return this.mutationType.getGroup().name();
 * }
 * ```
 *
 * **Label value conversion:**
 * - Field/method return values are converted to strings via `toString()`
 * - Null values are typically rendered as empty strings or "N/A" depending on the metric handler
 * configuration
 * - For enums, the enum constant name is used (not ordinal)
 *
 * **Combination with other annotations:**
 * - Labels apply to ALL metrics in the event class (both field-level {@link ExportMetric @ExportMetric}
 * and class-level {@link ExportInvocationMetric @ExportInvocationMetric} /
 * {@link ExportDurationMetric @ExportDurationMetric})
 * - Common pattern: use labels inherited from parent event classes (e.g., `catalogName` in
 * `AbstractQueryEvent`) to ensure consistency across all query-related metrics
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 * @see ExportMetric
 * @see ExportInvocationMetric
 * @see ExportDurationMetric
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ExportMetricLabel {

	/**
	 * The label name to use in Prometheus metrics.
	 *
	 * If not specified (empty string), the Java field or method name is used, converted to
	 * snake_case according to Prometheus naming conventions. For methods, the "get" prefix is
	 * typically stripped (e.g., `getOperationType()` becomes label `operation_type`).
	 *
	 * Use a custom label name when:
	 * - The field/method name doesn't follow Prometheus naming conventions
	 * - You need to match existing label names from other systems
	 * - The field name is unclear or too verbose for external monitoring
	 *
	 * **Naming guidelines:**
	 * - Use lowercase with underscores (snake_case)
	 * - Be concise but descriptive (e.g., "entity_type" not "the_type_of_entity")
	 * - Use consistent names across related metrics (e.g., always use "catalog_name", not
	 * "catalogName" or "catalog" in different events)
	 * - Avoid special characters, use only alphanumeric and underscores
	 *
	 * **Common label names in evitaDB:**
	 * - `catalog_name` - The catalog the operation belongs to
	 * - `entity_type` - The entity collection name
	 * - `api_type` - The API being used (graphql, rest, grpc)
	 * - `instance_id` - The evitaDB server instance identifier
	 * - `operation_type` - The type of operation being performed
	 *
	 * **Example:**
	 * ```java
	 * {@literal @}ExportMetricLabel("entity_type")  // Explicit label name
	 * private String entityType;
	 *
	 * {@literal @}ExportMetricLabel  // Inferred label name: "catalog_name"
	 * private String catalogName;
	 *
	 * {@literal @}ExportMetricLabel("operation")  // Rename from getOperationType() method
	 * public String getOperationType() { ... }
	 * ```
	 */
	String value() default "";

}
