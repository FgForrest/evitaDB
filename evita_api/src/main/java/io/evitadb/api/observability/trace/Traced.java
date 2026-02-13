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
 * Marker annotation indicating that a method is a candidate for distributed tracing. This
 * annotation serves as a hint to observability systems (e.g., OpenTelemetry) that the method is
 * significant enough to warrant span creation, but actual tracing behavior is controlled by
 * runtime configuration and sampling policies.
 *
 * **Design Purpose:**
 * This annotation identifies "traceable" operations without forcing tracing to occur. The actual
 * decision to create a span depends on:
 * - Whether tracing is enabled in the server configuration
 * - The current trace sampling rate
 * - Whether a parent trace context exists (for nested spans)
 * - The specific tracing implementation loaded via {@link TracingContextProvider}
 *
 * **Usage Context:**
 * Apply this annotation to methods that:
 * - Represent significant logical operations (e.g., entity enrichment, query planning)
 * - Are not direct API entry points (use {@link RepresentsQuery} or {@link RepresentsMutation}
 * for those)
 * - Benefit from detailed performance profiling during debugging or optimization
 *
 * **Example:**
 * ```java
 * @Traced
 * protected ServerEntityDecorator enrichEntity(
 * @Nonnull EntityContract entity,
 * @Nonnull EvitaRequest evitaRequest
 * ) {
 * // ... enrichment logic
 * }
 * ```
 *
 * **Relationship to Other Annotations:**
 * - Complementary to {@link RepresentsQuery} and {@link RepresentsMutation} (can be used together)
 * - More granular than {@link RepresentsQuery}/{@link RepresentsMutation} — used for internal
 * operations within an API call
 * - Optional marker — absence does not prevent manual tracing via {@link TracingContext}
 *
 * **Runtime Behavior:**
 * This annotation is retained at runtime and can be discovered via reflection by tracing
 * interceptors, AOP proxies, or annotation processors. If no tracing implementation is active,
 * the annotation has no effect.
 *
 * **When NOT to Use:**
 * - Do not annotate trivial getters/setters or utility methods — tracing overhead may exceed value
 * - Do not annotate methods called in tight loops — excessive span creation degrades performance
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Traced {
}
