/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.traffic;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Interface for introspecting labels attached to traffic recordings. Labels are key-value pairs that provide
 * contextual metadata about recorded queries and mutations (e.g., `trace-id`, `client-id`, `ip-address`, `uri`,
 * `entity-type`, custom application labels).
 *
 * **Purpose and Usage**
 *
 * Traffic recordings in evitaDB capture executed queries, mutations, and session operations along with associated
 * labels. This interface provides read-only access to those labels, enabling:
 * - Discovery of available label names and their most common values
 * - Autocomplete functionality in UIs (e.g., evitaLab) for filtering traffic by labels
 * - Understanding the distribution of traffic across different dimensions (entity types, clients, endpoints)
 *
 * **Label Cardinality**
 *
 * Results are ordered by cardinality (frequency of occurrence) in descending order — the most frequently occurring
 * label names or values appear first. This ordering helps users identify the most significant dimensions in their
 * traffic data.
 *
 * **Common Label Names**
 *
 * - `trace-id`: Distributed tracing identifier
 * - `client-id`: Client application identifier
 * - `ip-address`: Source IP address of the request
 * - `uri`: Request URI path
 * - `entity-type`: Entity collection being queried or mutated
 * - Custom labels defined via {@link io.evitadb.api.query.head.Label} query constraint
 *
 * **Usage Context**
 *
 * This interface is implemented by:
 * - {@link io.evitadb.core.traffic.TrafficRecordingEngine} (via delegation to
 * {@link io.evitadb.store.traffic.OffHeapTrafficRecorder})
 * - {@link io.evitadb.core.session.EvitaSession} (exposes label introspection to session clients)
 * - {@link io.evitadb.store.traffic.DiskRingBuffer} (provides the underlying index for label queries)
 *
 * **Thread-Safety**
 *
 * Implementations may be accessed concurrently from multiple threads. The returned collections are snapshots
 * taken at query time and are safe to iterate without additional synchronization.
 *
 * **Example Usage**
 *
 * ```
 * // Discover the 10 most common label names (e.g., "entity-type", "trace-id", "client-id")
 * Collection<String> topLabels = labelIntrospector.getLabelsNamesOrderedByCardinality(null, 10);
 *
 * // Find entity types matching prefix "prod", ordered by how often they appear in traffic
 * Collection<String> entityTypes = labelIntrospector.getLabelValuesOrderedByCardinality(
 * "entity-type", "prod", 20
 * );
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see io.evitadb.core.traffic.TrafficRecordingEngine
 * @see io.evitadb.store.traffic.TrafficRecordingIndex
 * @see io.evitadb.api.query.head.Label
 */
public interface LabelIntrospector {

	/**
	 * Returns a collection of unique label names present in the traffic recording, ordered by the cardinality
	 * (frequency) of their associated values in descending order. Labels with more distinct values or higher occurrence
	 * counts appear first.
	 *
	 * This method is useful for discovering which labels are actively used in traffic recordings, prioritizing those
	 * that vary most across requests (indicating high informational value).
	 *
	 * @param nameStartingWith optional prefix filter — only label names starting with this string are included
	 *                         (case-sensitive); if `null`, all label names are considered
	 * @param limit            maximum number of label names to return (must be positive)
	 * @return collection of unique label names ordered by cardinality (highest cardinality first), limited to the
	 * specified count
	 */
	@Nonnull
	Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit);

	/**
	 * Returns a collection of unique values for a specific label, ordered by cardinality (frequency of occurrence)
	 * in descending order. Values that appear most frequently in the traffic recording are returned first.
	 *
	 * This method is useful for:
	 * - Autocomplete functionality (e.g., suggesting entity types or client IDs)
	 * - Understanding the distribution of traffic across label dimensions
	 * - Identifying most active clients, entity types, or endpoints
	 *
	 * @param labelName         exact name of the label to query (case-sensitive, e.g., "entity-type", "client-id")
	 * @param valueStartingWith optional prefix filter — only values starting with this string are included
	 *                          (case-sensitive); if `null`, all values for the label are considered
	 * @param limit             maximum number of values to return (must be positive)
	 * @return collection of unique label values ordered by cardinality (highest cardinality first), limited to the
	 * specified count
	 */
	@Nonnull
	Collection<String> getLabelValuesOrderedByCardinality(
		@Nonnull String labelName, @Nullable String valueStartingWith, int limit);

}
