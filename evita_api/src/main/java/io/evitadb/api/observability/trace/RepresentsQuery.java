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

package io.evitadb.api.observability.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation indicating that a method represents a database read operation (query). This
 * annotation serves as metadata for observability systems, enabling differentiation between read
 * operations and write operations ({@link RepresentsMutation}) in tracing, metrics, and traffic
 * recording.
 *
 * **Design Purpose:**
 * evitaDB's observability layer uses this annotation to:
 * - Tag trace spans as query operations in OpenTelemetry
 * - Record read traffic separately from write traffic in {@link io.evitadb.core.traffic.TrafficRecordingEngine}
 * - Generate query-specific metrics (e.g., query duration, result count, cache hit rate)
 * - Enable filtering and analysis of read patterns
 *
 * **Usage Context:**
 * Apply this annotation to methods in {@link io.evitadb.api.EvitaSessionContract} that retrieve
 * data without modifying database state, such as:
 * - `query()`, `queryList()`, `queryOne()`
 * - `getEntity()`, `getEntityOrThrow()`
 * - Schema retrieval methods like `getEntitySchema()`, `getCatalogSchema()`
 * - CDC methods like `getAllEntityTypes()`, `subscribeChangeCatalogEvents()`
 *
 * **Example:**
 * ```java
 * @RepresentsQuery
 * public <S extends Serializable, T extends EvitaResponse<S>> T query(
 * @Nonnull Query query,
 * @Nonnull Class<S> expectedType
 * ) {
 * // ... query logic
 * }
 * ```
 *
 * **Relationship to Other Annotations:**
 * - Mutually exclusive with {@link RepresentsMutation} (a method cannot be both read and write)
 * - Often combined with {@link Traced} to enable detailed span creation
 *
 * **Runtime Behavior:**
 * This annotation is retained at runtime and can be discovered via reflection by tracing
 * interceptors, AOP proxies, or annotation processors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface RepresentsQuery {
}
