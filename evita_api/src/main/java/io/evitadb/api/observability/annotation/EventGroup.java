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
 * Annotation used for organizing JFR events into logical groups for metrics and observability.
 *
 * This annotation provides a way to aggregate related JFR (Java Flight Recorder) events into named
 * groups, similar to how Java packages organize classes. Event groups are used by the observability
 * framework to structure metrics and events in a hierarchical manner, making them easier to
 * discover, query, and visualize in monitoring systems like Prometheus.
 *
 * The annotation is typically applied to abstract base classes that represent a category of events
 * (e.g., query events, transaction events, storage events). All concrete event subclasses then
 * inherit the group classification.
 *
 * **Example usage:**
 * ```java
 * {@literal @}EventGroup(
 * value = "io.evitadb.query",
 * name = "evitaDB - Query",
 * description = "evitaDB events related to query processing."
 * )
 * {@literal @}Category({"evitaDB", "Query"})
 * abstract class AbstractQueryEvent extends CustomMetricsExecutionEvent {
 * // All query event subclasses inherit this group classification
 * }
 * ```
 *
 * **Event group hierarchy in evitaDB:**
 * - `io.evitadb.query` - Query processing events (entity fetching, filtering, sorting)
 * - `io.evitadb.transaction` - Transaction lifecycle events (commit, rollback, WAL operations)
 * - `io.evitadb.storage` - Storage I/O events (file operations, compaction, indexing)
 * - `io.evitadb.session` - Session lifecycle events (open, close, timeout)
 * - `io.evitadb.cache` - Cache-related events (hits, misses, evictions)
 * - `io.evitadb.system` - System-wide events (startup, configuration, background tasks)
 * - `io.evitadb.graphql`, `io.evitadb.rest`, `io.evitadb.grpc` - External API events
 *
 * The `value()` attribute is used internally as a namespace identifier, while `name()` and
 * `description()` provide human-readable metadata for documentation and monitoring UIs.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 * @see io.evitadb.core.metric.event.CustomMetricsExecutionEvent
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventGroup {

	/**
	 * The namespace identifier for the event group, typically in reverse-domain notation.
	 *
	 * This value acts as a unique identifier for the group and is used internally to organize
	 * events. It should follow Java package naming conventions (e.g., "io.evitadb.query").
	 *
	 * This identifier is also used as the base for JFR event names when combined with the
	 * event class name.
	 */
	String value();

	/**
	 * The human-readable display name for the event group.
	 *
	 * This name is used in monitoring UIs, documentation, and logging to identify the group.
	 * It should be concise and descriptive (e.g., "evitaDB - Query", "evitaDB - Storage").
	 *
	 * If not specified, the group will not have a display name in external systems, though
	 * the `value()` identifier will still be used internally.
	 */
	String name() default "";

	/**
	 * A detailed description explaining the purpose and scope of the event group.
	 *
	 * This description provides context about what types of events belong to this group and
	 * what aspects of system behavior they monitor. It is used in documentation, monitoring
	 * dashboards, and observability tools to help users understand the event category.
	 *
	 * Example: "evitaDB events related to query processing, including entity fetching,
	 * filtering, enrichment, and result pagination."
	 */
	String description() default "";

}
