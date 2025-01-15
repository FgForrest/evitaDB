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

package io.evitadb.store.traffic;


import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.FileLocation;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;

/**
 * This class contains live, updated in-memory index of the content of the disk ring buffer file. It is used to quickly
 * locate the position of the session in the file and handle {@link #getSessionStream(TrafficRecordingCaptureRequest)}
 * that provides a stream of all the {@link SessionLocation} objects that match the client request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DiskRingBufferIndex implements
	TransactionalLayerProducer<Void, DiskRingBufferIndex>,
	Serializable
{
	@Serial private static final long serialVersionUID = 1416655137041708718L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Map of session positions in the disk buffer file.
	 */
	private final TransactionalMap<Long, FileLocation> sessionLocationIndex;
	private final TransactionalMap<UUID, Long> sessionIdIndex;
	private final TransactionalMap<Long, SessionDescriptor> sessionUuidIndex;
	private final TransactionalObjectBPlusTree<Long, Long> sessionSequenceOrderIndex;
	private final TransactionalObjectBPlusTree<OffsetDateTime, Long> sessionCreationIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionDurationIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionFetchCountIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionBytesFetchedIndex;
	private final TransactionalMap<TrafficRecordingType, ConcurrentSkipListSet<Long>> sessionRecordingTypeIndex;
	private final AtomicLong sessionBeingIndexed;
	private final Deque<Long> sessionsToRemove;

	public DiskRingBufferIndex() {
		this.sessionLocationIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionIdIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionUuidIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionSequenceOrderIndex = new TransactionalObjectBPlusTree<>(64, 31, 31, 15, Long.class, Long.class);
		this.sessionCreationIndex = new TransactionalObjectBPlusTree<>(64, 31, 31, 15, OffsetDateTime.class, Long.class);
		this.sessionDurationIndex = new TransactionalObjectBPlusTree<>(64, 31, 31, 15, Integer.class, Long.class);
		this.sessionFetchCountIndex = new TransactionalObjectBPlusTree<>(64, 31, 31, 15, Integer.class, Long.class);
		this.sessionBytesFetchedIndex = new TransactionalObjectBPlusTree<>(64, 31, 31, 15, Integer.class, Long.class);
		this.sessionRecordingTypeIndex = new TransactionalMap<>(CollectionUtils.createHashMap(TrafficRecordingType.values().length));
		this.sessionBeingIndexed = new AtomicLong(-1L);
		this.sessionsToRemove = new LinkedList<>();
	}

	public DiskRingBufferIndex(
		@Nonnull Map<Long, FileLocation> sessionLocationIndex,
		@Nonnull Map<UUID, Long> sessionIdIndex,
		@Nonnull Map<Long, SessionDescriptor> sessionUuidIndex,
		@Nonnull TransactionalObjectBPlusTree<Long, Long> sessionSequenceOrderIndex,
		@Nonnull TransactionalObjectBPlusTree<OffsetDateTime, Long> sessionCreationIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionDurationIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionFetchCountIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionBytesFetchedIndex,
		@Nonnull Map<TrafficRecordingType, ConcurrentSkipListSet<Long>> sessionRecordingTypeIndex,
		@Nonnull AtomicLong sessionBeingIndexed,
		@Nonnull Deque<Long> sessionsToRemove
	) {
		this.sessionLocationIndex = new TransactionalMap<>(sessionLocationIndex);
		this.sessionIdIndex = new TransactionalMap<>(sessionIdIndex);
		this.sessionUuidIndex = new TransactionalMap<>(sessionUuidIndex);
		this.sessionSequenceOrderIndex = sessionSequenceOrderIndex;
		this.sessionCreationIndex = sessionCreationIndex;
		this.sessionDurationIndex = sessionDurationIndex;
		this.sessionFetchCountIndex = sessionFetchCountIndex;
		this.sessionBytesFetchedIndex = sessionBytesFetchedIndex;
		this.sessionRecordingTypeIndex = new TransactionalMap<>(sessionRecordingTypeIndex);
		this.sessionBeingIndexed = sessionBeingIndexed;
		this.sessionsToRemove = sessionsToRemove;
	}

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
		int durationInMillis,
		int fetchCount,
		int bytesFetchedTotal,
		@Nonnull Set<TrafficRecordingType> recordingTypes
	) {
		final Long sessionSequenceOrder = sessionLocation.sequenceOrder();
		final FileLocation previousLocation = this.sessionLocationIndex.putIfAbsent(
			sessionSequenceOrder,
			sessionLocation.fileLocation()
		);
		// the file reader should not be ever faster, but this a safety check, for avoiding double indexing
		if (previousLocation == null) {
			// index data
			this.sessionIdIndex.put(sessionId, sessionSequenceOrder);
			this.sessionUuidIndex.put(
				sessionSequenceOrder,
				new SessionDescriptor(
					sessionSequenceOrder, sessionId, created,
					durationInMillis, fetchCount, bytesFetchedTotal, recordingTypes
				)
			);
			this.sessionSequenceOrderIndex.insert(sessionSequenceOrder, sessionSequenceOrder);
			this.sessionCreationIndex.insert(created, sessionSequenceOrder);
			this.sessionDurationIndex.insert(durationInMillis, sessionSequenceOrder);
			this.sessionFetchCountIndex.insert(fetchCount, sessionSequenceOrder);
			this.sessionBytesFetchedIndex.insert(bytesFetchedTotal, sessionSequenceOrder);
			for (TrafficRecordingType recordingType : recordingTypes) {
				this.sessionRecordingTypeIndex.computeIfAbsent(
					recordingType,
					__ -> new ConcurrentSkipListSet<>()
				).add(sessionSequenceOrder);
			}
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
		if (this.sessionBeingIndexed.get() == sessionSequenceOrder) {
			this.sessionsToRemove.add(sessionSequenceOrder);
		} else {
			removeSessionFromIndexes(sessionSequenceOrder);
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
			final Set<TrafficRecordingType> recordingTypes = EnumSet.noneOf(TrafficRecordingType.class);
			recordingTypes.add(TrafficRecordingType.SESSION_START);

			this.sessionUuidIndex.put(
				sessionSequenceOrder,
				new SessionDescriptor(
					sessionSequenceOrder,
					ssc.sessionId(),
					ssc.created(),
					ssc.durationInMilliseconds(),
					ssc.ioFetchCount(),
					ssc.ioFetchedSizeBytes(),
					recordingTypes
				)
			);
			this.sessionSequenceOrderIndex.insert(sessionSequenceOrder, sessionSequenceOrder);
			this.sessionCreationIndex.insert(ssc.created(), sessionSequenceOrder);
		} else {
			final SessionDescriptor sessionDescriptor = this.sessionUuidIndex.get(sessionSequenceOrder);
			indexRecording(recording, sessionDescriptor);
			emptyRemovalQueue();
		}
	}

	/**
	 * Updates various session-related indexes based on the given traffic recording and
	 * session descriptor. This method ensures that session statistics such as duration,
	 * fetch count, bytes fetched, and recording types are properly reflected in the
	 * respective internal indexes.
	 *
	 * @param trafficRecording the traffic recording containing session-specific data to be indexed, must not be null
	 * @param sessionDescriptor the descriptor of the session being indexed, must not be null
	 */
	private void indexRecording(
		@Nonnull TrafficRecording trafficRecording,
		@Nonnull SessionDescriptor sessionDescriptor
	) {
		sessionDescriptor.update(
			trafficRecording,
				(sso, previousValue, newValue) -> {
					this.sessionDurationIndex.delete(previousValue);
					this.sessionDurationIndex.insert(newValue, sso);
				},
				(sso, previousValue, newValue) -> {
					this.sessionFetchCountIndex.delete(previousValue);
					this.sessionFetchCountIndex.insert(newValue, sso);
				},
				(sso, previousValue, newValue) -> {
					this.sessionBytesFetchedIndex.delete(previousValue);
					this.sessionBytesFetchedIndex.insert(newValue, sso);
				},
				(sso, newValue) -> this.sessionRecordingTypeIndex.computeIfAbsent(newValue, trt -> new ConcurrentSkipListSet<>()).add(sso)
			);
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
		final List<Iterator<Long>> streams = Stream.concat(
				Stream.of(
					request.sinceSessionSequenceId() == null ? null : this.sessionSequenceOrderIndex.greaterOrEqualValueIterator(request.sinceSessionSequenceId()),
					request.sessionId() == null ? null : ofNullable(this.sessionIdIndex.get(request.sessionId())).map(sid -> List.of(sid).iterator()).orElse(null),
					request.since() == null ? null : this.sessionCreationIndex.greaterOrEqualValueIterator(request.since()),
					request.longerThan() == null ? null : this.sessionDurationIndex.greaterOrEqualValueIterator(Math.toIntExact(request.longerThan().toMillis())),
					request.fetchingMoreBytesThan() == null ? null : this.sessionBytesFetchedIndex.greaterOrEqualValueIterator(request.fetchingMoreBytesThan())
				),
				request.type() == null ?
					Stream.empty() :
					Arrays.stream(request.type()).map(this.sessionRecordingTypeIndex::get)
						.filter(Objects::nonNull)
						.map(ConcurrentSkipListSet::iterator)
			)
			.filter(Objects::nonNull)
			.toList();

		if (streams.isEmpty()) {
			return this.sessionLocationIndex
				.entrySet()
				.stream()
				.map(entry -> new SessionLocation(entry.getKey(), entry.getValue()));
		} else {
			return StreamSupport.stream(
					Spliterators.spliteratorUnknownSize(new CommonElementsIterator(streams), Spliterator.ORDERED | Spliterator.DISTINCT),
					false
				)
				.map(sessionSequenceOrder -> new SessionLocation(sessionSequenceOrder, this.sessionLocationIndex.get(sessionSequenceOrder)));
		}
	}

	@Override
	public Void createLayer() {
		return null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.sessionLocationIndex.removeLayer(transactionalLayer);
		this.sessionIdIndex.removeLayer(transactionalLayer);
		this.sessionUuidIndex.removeLayer(transactionalLayer);
		this.sessionCreationIndex.removeLayer(transactionalLayer);
		this.sessionDurationIndex.removeLayer(transactionalLayer);
		this.sessionFetchCountIndex.removeLayer(transactionalLayer);
		this.sessionBytesFetchedIndex.removeLayer(transactionalLayer);
		this.sessionRecordingTypeIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public DiskRingBufferIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new DiskRingBufferIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionLocationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionIdIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionUuidIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionSequenceOrderIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionCreationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionDurationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionFetchCountIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionBytesFetchedIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionRecordingTypeIndex),
			this.sessionBeingIndexed,
			this.sessionsToRemove
		);
	}

	/**
	 * Empties the removal queue by removing and processing each session ID stored in the
	 * queue. It repeatedly pops session IDs from the sessionsToRemove stack and removes
	 * the corresponding entries in the indexes until the queue is empty.
	 * This method is intended to be used for cleanup operations, ensuring all pending
	 * session removals are completed.
	 */
	private void emptyRemovalQueue() {
		Long sessionToRemove = this.sessionsToRemove.isEmpty() ? null : this.sessionsToRemove.pop();
		while (sessionToRemove != null) {
			this.sessionLocationIndex.remove(sessionToRemove);
			sessionToRemove = this.sessionsToRemove.pop();
			removeSessionFromIndexes(sessionToRemove);
		}
	}

	/**
	 * Removes a session from multiple internal indexes using the given session sequence order.
	 * This method ensures the specific session data is deleted from all relevant indexes,
	 * including session details and recording type mappings.
	 *
	 * @param sessionSequenceOrder the sequence order of the session to be removed from the indexes
	 */
	private void removeSessionFromIndexes(long sessionSequenceOrder) {
		final SessionDescriptor sessionDescriptor = this.sessionUuidIndex.remove(sessionSequenceOrder);
		this.sessionIdIndex.remove(sessionDescriptor.getSessionId());
		this.sessionCreationIndex.delete(sessionDescriptor.getCreated());
		this.sessionDurationIndex.delete(sessionDescriptor.getMaxDurationInMillis());
		this.sessionFetchCountIndex.delete(sessionDescriptor.getMaxFetchCount());
		this.sessionBytesFetchedIndex.delete(sessionDescriptor.getMaxBytesFetchedTotal());
		for (TrafficRecordingType recordingType : sessionDescriptor.getRecordingTypes()) {
			this.sessionRecordingTypeIndex.get(recordingType).remove(sessionSequenceOrder);
		}
	}

	/**
	 * Represents a descriptor of a session, tracking its metadata and statistics.
	 * This includes information such as session sequence order, session ID, creation time,
	 * traffic recording types observed, and session-specific metrics like maximum duration,
	 * fetch count, and total bytes fetched.
	 */
	@Getter
	private static class SessionDescriptor {
		private final long sessionSequenceOrder;
		private final UUID sessionId;
		private final OffsetDateTime created;
		private final Set<TrafficRecordingType> recordingTypes;
		private int maxDurationInMillis;
		private int maxFetchCount;
		private int maxBytesFetchedTotal;

		public SessionDescriptor(
			long sessionSequenceOrder,
			@Nonnull UUID sessionId,
			@Nonnull OffsetDateTime created,
			int maxDurationInMillis,
			int maxFetchCount,
			int maxBytesFetchedTotal,
			@Nonnull Set<TrafficRecordingType> recordingTypes
		) {
			this.sessionSequenceOrder = sessionSequenceOrder;
			this.sessionId = sessionId;
			this.created = created;
			this.maxDurationInMillis = maxDurationInMillis;
			this.maxFetchCount = maxFetchCount;
			this.maxBytesFetchedTotal = maxBytesFetchedTotal;
			this.recordingTypes = recordingTypes;
		}

		/**
		 * Updates the session descriptor's state by comparing values from the provided
		 * {@code trafficRecording} and invoking the appropriate callbacks when new maximums or
		 * new traffic recording types are encountered.
		 *
		 * @param trafficRecording the traffic recording containing values to be compared
		 *                         against the current state
		 * @param onMaxDuration callback invoked when a new maximum duration is found;
		 *                      provides the session sequence order, the previous maximum,
		 *                      and the new maximum duration
		 * @param onMaxFetchCount callback invoked when a new maximum fetch count is found;
		 *                        provides the session sequence order, the previous maximum,
		 *                        and the new maximum fetch count
		 * @param onMaxFetchBytes callback invoked when a new maximum fetch size in bytes is found;
		 *                        provides the session sequence order, the previous maximum,
		 *                        and the new maximum fetch size in bytes
		 * @param onNewRecordingType callback invoked when a new recording type is encountered;
		 *                           provides the session sequence order and the new recording type
		 */
		public void update(
			@Nonnull TrafficRecording trafficRecording,
			@Nonnull TriConsumer<Long, Integer, Integer> onMaxDuration,
			@Nonnull TriConsumer<Long, Integer, Integer> onMaxFetchCount,
			@Nonnull TriConsumer<Long, Integer, Integer> onMaxFetchBytes,
			@Nonnull BiConsumer<Long, TrafficRecordingType> onNewRecordingType
		) {
			if (trafficRecording.durationInMilliseconds() > this.maxDurationInMillis) {
				onMaxDuration.accept(this.sessionSequenceOrder, this.maxDurationInMillis, trafficRecording.durationInMilliseconds());
				this.maxDurationInMillis = trafficRecording.durationInMilliseconds();
			}
			if (trafficRecording.ioFetchCount() > this.maxFetchCount) {
				onMaxFetchCount.accept(this.sessionSequenceOrder, this.maxFetchCount, trafficRecording.ioFetchCount());
				this.maxFetchCount = trafficRecording.ioFetchCount();
			}
			if (trafficRecording.ioFetchedSizeBytes() > this.maxBytesFetchedTotal) {
				onMaxFetchBytes.accept(this.sessionSequenceOrder, this.maxBytesFetchedTotal, trafficRecording.ioFetchedSizeBytes());
				this.maxBytesFetchedTotal = trafficRecording.ioFetchedSizeBytes();
			}
			if (this.recordingTypes.add(trafficRecording.type())) {
				onNewRecordingType.accept(this.sessionSequenceOrder, trafficRecording.type());
			}
		}
	}

}
