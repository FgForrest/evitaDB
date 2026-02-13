/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api;


import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Interface for reading and querying traffic recordings captured by evitaDB. Traffic recordings provide a persistent
 * log of all queries, mutations, and session operations executed against a catalog, enabling debugging, performance
 * analysis, query replay, and audit trails.
 *
 * **Purpose and Usage**
 *
 * evitaDB can record all catalog operations to disk (configured via
 * {@link io.evitadb.api.configuration.TrafficRecordingOptions}), capturing:
 * - Session creation and closure
 * - Queries and their execution statistics
 * - Entity fetch operations
 * - Mutations (inserts, updates, deletes)
 * - Enrichment operations
 * - Associated labels (trace IDs, client IDs, entity types, custom labels)
 *
 * This interface provides filtered access to those recordings, supporting both chronological (oldest-first) and
 * reverse-chronological (newest-first) iteration.
 *
 * **Recording Storage**
 *
 * Traffic recordings are stored in a ring buffer on disk (see {@link io.evitadb.store.traffic.DiskRingBuffer}),
 * with configurable retention limits based on:
 * - Maximum disk space (MB)
 * - Maximum time window (duration)
 *
 * Once the buffer is full, the oldest recordings are overwritten, making this interface suitable for recent traffic
 * analysis rather than long-term archival.
 *
 * **Filtering and Querying**
 *
 * The {@link TrafficRecordingCaptureRequest} allows filtering by:
 * - **Time range**: Query recordings within a specific time window
 * - **Labels**: Filter by trace ID, client ID, IP address, URI, entity type, or custom labels
 * - **Recording type**: Include only queries, mutations, fetches, enrichments, or sessions
 *
 * Multiple criteria within a request are combined with **logical OR** — a recording matches if it satisfies any
 * of the specified conditions.
 *
 * **Ordering Guarantees**
 *
 * - Within a session: recordings are returned in execution order (the order operations were performed)
 * - Across sessions: sessions are ordered by their finalization timestamp
 * - `getRecordings()`: oldest sessions first, oldest operations within each session first
 * - `getRecordingsReversed()`: newest sessions first, newest operations within each session first
 *
 * **Resource Management**
 *
 * Both methods return {@link Stream} instances backed by file I/O. Callers **must** close the stream after use
 * (preferably via try-with-resources) to release file handles and memory.
 *
 * **Thread-Safety**
 *
 * Implementations are thread-safe for querying, but the returned streams are not. Each stream should be consumed
 * by a single thread.
 *
 * **Usage Context**
 *
 * This interface is implemented by:
 * - {@link io.evitadb.core.traffic.TrafficRecordingEngine} (primary engine for live catalog traffic)
 * - {@link io.evitadb.store.traffic.InputStreamTrafficRecordReader} (read-only access to exported traffic files)
 *
 * **Example Usage**
 *
 * ```
 * // Query all operations for a specific trace ID from the last hour
 * TrafficRecordingCaptureRequest request = TrafficRecordingCaptureRequest.builder()
 * .fromTime(OffsetDateTime.now().minusHours(1))
 * .labels(Map.of("trace-id", "abc123"))
 * .build();
 *
 * try (Stream<TrafficRecording> recordings = reader.getRecordingsReversed(request)) {
 * recordings.forEach(recording -> {
 * System.out.println("Operation: " + recording.type());
 * System.out.println("Timestamp: " + recording.timestamp());
 * System.out.println("Labels: " + recording.labels());
 * });
 * }
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 * @see io.evitadb.core.traffic.TrafficRecordingEngine
 * @see io.evitadb.store.traffic.InputStreamTrafficRecordReader
 * @see io.evitadb.api.requestResponse.trafficRecording.TrafficRecording
 * @see io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest
 */
public interface TrafficRecordingReader {

	/**
	 * Returns a stream of traffic recordings matching the specified criteria, ordered chronologically (oldest first).
	 * Within each session, operations are returned in the order they were executed. Sessions are ordered by their
	 * finalization (closure) timestamp.
	 *
	 * **Resource Management**: The returned stream must be closed after use to release file handles and memory.
	 * Use try-with-resources for automatic cleanup.
	 *
	 * **Filtering Logic**: Multiple criteria in the request are combined with logical OR — a recording is included
	 * if it matches any of the specified conditions (time range, labels, recording types).
	 *
	 * @param request criteria specifying which recordings to return (time range, labels, recording types); multiple
	 *                criteria are combined with logical OR
	 * @return stream of matching recordings in chronological order (oldest sessions and operations first); must be
	 * closed by the caller
	 * @throws TemporalDataNotAvailableException if the requested time range is no longer available (overwritten by
	 *                                           newer data in the ring buffer)
	 * @throws IndexNotReady                     if the traffic recording index is still being built and cannot be
	 *                                           queried yet
	 */
	@Nonnull
	Stream<TrafficRecording> getRecordings(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady;

	/**
	 * Returns a stream of traffic recordings matching the specified criteria, ordered reverse-chronologically
	 * (newest first). Within each session, operations are returned in reverse execution order. Sessions are ordered
	 * by their finalization (closure) timestamp in descending order.
	 *
	 * This method is useful for debugging recent issues or analyzing the most recent traffic patterns without
	 * iterating through older data.
	 *
	 * **Resource Management**: The returned stream must be closed after use to release file handles and memory.
	 * Use try-with-resources for automatic cleanup.
	 *
	 * **Filtering Logic**: Multiple criteria in the request are combined with logical OR — a recording is included
	 * if it matches any of the specified conditions (time range, labels, recording types).
	 *
	 * @param request criteria specifying which recordings to return (time range, labels, recording types); multiple
	 *                criteria are combined with logical OR
	 * @return stream of matching recordings in reverse chronological order (newest sessions and operations first);
	 * must be closed by the caller
	 * @throws TemporalDataNotAvailableException if the requested time range is no longer available (overwritten by
	 *                                           newer data in the ring buffer)
	 * @throws IndexNotReady                     if the traffic recording index is still being built and cannot be
	 *                                           queried yet
	 */
	@Nonnull
	Stream<TrafficRecording> getRecordingsReversed(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady;

}
