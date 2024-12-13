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

package io.evitadb.store.traffic;


import io.evitadb.api.requestResponse.trafficRecording.EntityEnrichmentContainer;
import io.evitadb.api.requestResponse.trafficRecording.EntityFetchContainer;
import io.evitadb.api.requestResponse.trafficRecording.MutationContainer;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionCloseContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.model.FileLocation;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * This class contains live, updated in-memory index of the content of the disk ring buffer file. It is used to quickly
 * locate the position of the session in the file and handle {@link #getSessionStream(TrafficRecordingCaptureRequest)}
 * that provides a stream of all the {@link SessionLocation} objects that match the client request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DiskRingBufferIndex {
	/**
	 * Map of session positions in the disk buffer file.
	 */
	private final Map<Long, FileLocation> sessionLocationIndex = new ConcurrentHashMap<>(1_024);
	/**
	 * TODO JNO - B+Tree implementation
	 */
	private final Map<UUID, Long> sessionIdIndex = new ConcurrentHashMap<>(1_024);
	private final AtomicLong sessionsIndexed = new AtomicLong(-1);
	private final Deque<Long> sessionsToRemove = new LinkedList<>();

	/**
	 * Sets up a session by storing the given session location in an internal index.
	 *
	 * @param sessionLocation the session location to be added to the index, must not be null
	 */
	public void setupSession(@Nonnull SessionLocation sessionLocation) {
		this.sessionLocationIndex.put(
			sessionLocation.sequenceOrder(),
			sessionLocation.fileLocation()
		);
	}

	/**
	 * Sets up a session by storing the given session location in an internal index and already collected data in
	 * the memory.
	 *
	 * @param sessionLocation   the session location to be added to the index, must not be null
	 * @param sessionId         the session id
	 * @param created           the time when the session was created
	 * @param durationInMillis  the duration of the session in milliseconds
	 * @param fetchCount        the number of fetch operations in the session
	 * @param bytesFetchedTotal the total number of bytes fetched in the session
	 * @param recordingTypes    the types of traffic recordings that were observed in the session
	 */
	public void setupSession(
		@Nonnull SessionLocation sessionLocation,
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime created,
		long durationInMillis,
		int fetchCount,
		int bytesFetchedTotal,
		@Nonnull Set<TrafficRecordingType> recordingTypes
	) {
		/* TODO JNO - IMPLEMENT ME */
		final FileLocation previousLocation = this.sessionLocationIndex.putIfAbsent(
			sessionLocation.sequenceOrder(),
			sessionLocation.fileLocation()
		);
		// the file reader should not be ever faster, but this a safety check, for avoiding double indexing
		if (previousLocation == null) {
			// index
		}
	}

	/**
	 * Removes a session from the session location index based on the provided session sequence order.
	 * If the session is currently being indexed, it will be marked for removal later.
	 *
	 * @param sessionSequenceOrder the sequence order of the session to be removed
	 */
	public void removeSession(long sessionSequenceOrder) {
		this.sessionLocationIndex.remove(sessionSequenceOrder);
		// the session is currently being indexed, we need to remove its data later
		if (this.sessionsIndexed.get() == sessionSequenceOrder) {
			this.sessionsToRemove.add(sessionSequenceOrder);
		}
	}

	/**
	 * Checks if a session exists in the index based on the given session sequence order.
	 *
	 * @param sessionSequenceOrder the sequence order of the session to check for existence
	 * @return true if the session exists in the index, false otherwise
	 */
	public boolean sessionExists(long sessionSequenceOrder) {
		return this.sessionLocationIndex.containsKey(sessionSequenceOrder);
	}

	/**
	 * Indexes a given traffic recording based on its type. The method updates internal indexes
	 * to keep track of session-related data. The session sequence order is used as a key for these indexes.
	 *
	 * @param sessionSequenceOrder the sequence order of the session to be used in indexing
	 * @param recording            the traffic recording to be indexed
	 */
	public void indexRecording(long sessionSequenceOrder, @Nonnull TrafficRecording recording) {
		if (recording instanceof SessionStartContainer ssc) {
			this.sessionIdIndex.put(ssc.sessionId(), sessionSequenceOrder);
		} else if (recording instanceof SessionCloseContainer scc) {
			emptyRemovalQueue();
		} else if (recording instanceof QueryContainer qc) {

		} else if (recording instanceof EntityFetchContainer rfc) {

		} else if (recording instanceof EntityEnrichmentContainer rec) {

		} else if (recording instanceof MutationContainer mc) {

		} else {
			throw new GenericEvitaInternalError("Unknown recording type: " + recording.getClass().getName());
		}
	}

	/**
	 * Retrieves a stream of session locations filtered by the criteria specified in the given
	 * TrafficRecordingCaptureRequest. The stream provides access to session data that can be
	 * processed lazily.
	 *
	 * @param request the TrafficRecordingCaptureRequest containing criteria for filtering the sessions,
	 *                must not be null
	 * @return a stream of SessionLocation objects that match the specified criteria
	 */
	@Nonnull
	public Stream<SessionLocation> getSessionStream(@Nonnull TrafficRecordingCaptureRequest request) {
		return null;
	}

	/**
	 * Empties the removal queue by removing and processing each session ID stored in the
	 * queue. It repeatedly pops session IDs from the sessionsToRemove stack and removes
	 * the corresponding entries in the indexes until the queue is empty.
	 * This method is intended to be used for cleanup operations, ensuring all pending
	 * session removals are completed.
	 */
	private void emptyRemovalQueue() {
		Long sessionToRemove = this.sessionsToRemove.pop();
		while (sessionToRemove != null) {
			this.sessionLocationIndex.remove(sessionToRemove);
			sessionToRemove = this.sessionsToRemove.pop();
		}
	}
}
