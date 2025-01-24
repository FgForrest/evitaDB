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


import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import com.carrotsearch.hppc.cursors.ObjectIntCursor;
import io.evitadb.api.requestResponse.trafficRecording.Label;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.Entry;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
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
public class TrafficRecordingIndex implements
	TransactionalLayerProducer<Void, TrafficRecordingIndex>,
	Serializable
{
	@Serial private static final long serialVersionUID = 1416655137041708718L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Map of session positions in the disk buffer file.
	 */
	private final TransactionalMap<Long, SessionLocation> sessionLocationIndex;
	private final TransactionalMap<UUID, Long> sessionIdIndex;
	private final TransactionalMap<Long, SessionDescriptor> sessionUuidIndex;
	private final TransactionalObjectBPlusTree<Long, Long> sessionSequenceOrderIndex;
	private final TransactionalObjectBPlusTree<OffsetDateTime, Long> sessionCreationIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionDurationIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionFetchCountIndex;
	private final TransactionalObjectBPlusTree<Integer, Long> sessionBytesFetchedIndex;
	private final TransactionalObjectBPlusTree<Label, TransactionalObjectBPlusTree<Long, Long>> labelIndex;
	private final TransactionalMap<TrafficRecordingType, TransactionalObjectBPlusTree<Long, Long>> sessionRecordingTypeIndex;
	private final AtomicLong sessionBeingIndexed;
	private final Deque<Long> sessionsToRemove;

	public TrafficRecordingIndex() {
		this.sessionLocationIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionIdIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionUuidIndex = new TransactionalMap<>(CollectionUtils.createHashMap(1_024));
		this.sessionSequenceOrderIndex = new TransactionalObjectBPlusTree<>(Long.class, Long.class);
		this.sessionCreationIndex = new TransactionalObjectBPlusTree<>(OffsetDateTime.class, Long.class);
		this.sessionDurationIndex = new TransactionalObjectBPlusTree<>(Integer.class, Long.class);
		this.sessionFetchCountIndex = new TransactionalObjectBPlusTree<>(Integer.class, Long.class);
		this.sessionBytesFetchedIndex = new TransactionalObjectBPlusTree<>(Integer.class, Long.class);
		//noinspection unchecked,rawtypes
		this.labelIndex = new TransactionalObjectBPlusTree<>(
			Label.class,
			TransactionalObjectBPlusTree.genericClass(),
			TransactionalObjectBPlusTree.class::cast
		);
		//noinspection unchecked,rawtypes
		this.sessionRecordingTypeIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(TrafficRecordingType.values().length),
			TransactionalObjectBPlusTree.class::cast
		);
		this.sessionBeingIndexed = new AtomicLong(-1L);
		this.sessionsToRemove = new LinkedList<>();
	}

	private TrafficRecordingIndex(
		@Nonnull Map<Long, SessionLocation> sessionLocationIndex,
		@Nonnull Map<UUID, Long> sessionIdIndex,
		@Nonnull Map<Long, SessionDescriptor> sessionUuidIndex,
		@Nonnull TransactionalObjectBPlusTree<Long, Long> sessionSequenceOrderIndex,
		@Nonnull TransactionalObjectBPlusTree<OffsetDateTime, Long> sessionCreationIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionDurationIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionFetchCountIndex,
		@Nonnull TransactionalObjectBPlusTree<Integer, Long> sessionBytesFetchedIndex,
		@Nonnull TransactionalObjectBPlusTree<Label, TransactionalObjectBPlusTree<Long, Long>> labelIndex,
		@Nonnull Map<TrafficRecordingType, TransactionalObjectBPlusTree<Long, Long>> sessionRecordingTypeIndex,
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
		this.labelIndex = labelIndex;
		//noinspection unchecked,rawtypes
		this.sessionRecordingTypeIndex = new TransactionalMap<>(
			sessionRecordingTypeIndex,
			TransactionalObjectBPlusTree.class::cast
		);
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
			sessionLocation
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
		@Nonnull Set<TrafficRecordingType> recordingTypes,
		@Nonnull Set<Label> labels
	) {
		final Long sessionSequenceOrder = sessionLocation.sequenceOrder();
		final SessionLocation previousLocation = this.sessionLocationIndex.putIfAbsent(
			sessionSequenceOrder,
			sessionLocation
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
					__ -> new TransactionalObjectBPlusTree<>(Long.class, Long.class)
				).insert(sessionSequenceOrder, sessionSequenceOrder);
			}
			for (Label label : labels) {
				this.labelIndex.upsert(
					label,
					values -> {
						if (values == null) {
							values = new TransactionalObjectBPlusTree<>(Long.class, Long.class);
						}
						final Long seqOrder = Objects.requireNonNull(sessionSequenceOrder);
						values.insert(seqOrder, seqOrder);
						return values;
					}
				);
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
		final List<Iterator<Long>> streams = Stream.<Stream<Iterator<Long>>>of(
				Stream.of(
					request.sinceSessionSequenceId() == null ? null : this.sessionSequenceOrderIndex.greaterOrEqualValueIterator(request.sinceSessionSequenceId()),
					request.sessionId() == null ? null : ofNullable(this.sessionIdIndex.get(request.sessionId())).map(sid -> List.of(sid).iterator()).orElse(null),
					request.since() == null ? null : this.sessionCreationIndex.greaterOrEqualValueIterator(request.since()),
					request.longerThan() == null ? null : this.sessionDurationIndex.greaterOrEqualValueIterator(Math.toIntExact(request.longerThan().toMillis())),
					request.fetchingMoreBytesThan() == null ? null : this.sessionBytesFetchedIndex.greaterOrEqualValueIterator(request.fetchingMoreBytesThan())
				),
				request.types() == null ?
					Stream.empty() :
					Arrays.stream(request.types()).map(this.sessionRecordingTypeIndex::get)
						.filter(Objects::nonNull)
						.map(TransactionalObjectBPlusTree::valueIterator),
				request.labels() == null ?
					Stream.empty() :
					Arrays.stream(request.labels())
						.map(this.labelIndex::search)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.map(TransactionalObjectBPlusTree::valueIterator)
			)
			.flatMap(UnaryOperator.identity())
			.filter(Objects::nonNull)
			.toList();

		if (streams.isEmpty()) {
			return this.sessionLocationIndex
				.values()
				.stream();
		} else {
			return StreamSupport.stream(
					Spliterators.spliteratorUnknownSize(new CommonElementsIterator(streams), Spliterator.ORDERED | Spliterator.DISTINCT),
					false
				)
				.map(this.sessionLocationIndex::get)
				.filter(Objects::nonNull);
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
	public TrafficRecordingIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new TrafficRecordingIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionLocationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionIdIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionUuidIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionSequenceOrderIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionCreationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionDurationIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionFetchCountIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionBytesFetchedIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.labelIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sessionRecordingTypeIndex),
			this.sessionBeingIndexed,
			this.sessionsToRemove
		);
	}

	/**
	 * Retrieves a stream of label names that start with the given prefix, ordered by their cardinality.
	 *
	 * @param nameStartingWith the prefix that the label names should start with, must not be null
	 * @return a stream of label names that match the prefix, ordered by cardinality, never null
	 */
	@Nonnull
	public Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit) {
		final Iterator<Entry<Label, TransactionalObjectBPlusTree<Long, Long>>> it = nameStartingWith == null ?
			this.labelIndex.entryIterator() :
			this.labelIndex.greaterOrEqualEntryIterator(new Label(nameStartingWith, null));
		final ObjectIntMap<String> cardinalities = new ObjectIntHashMap<>(256);
		while (it.hasNext()) {
			final Entry<Label, TransactionalObjectBPlusTree<Long, Long>> entry = it.next();
			final Label nextLabel = entry.key();
			if (nameStartingWith == null || nextLabel.name().startsWith(nameStartingWith)) {
				final int labelCardinality = entry.value().size();
				cardinalities.putOrAdd(nextLabel.name(), labelCardinality, labelCardinality);
			} else {
				break;
			}
		}
		final List<LabelWithCardinality> labels = new ArrayList<>(cardinalities.size());
		for (ObjectIntCursor<String> cardinality : cardinalities) {
			labels.add(new LabelWithCardinality(cardinality.key, cardinality.value));
		}

		labels.sort(Comparator.comparingInt(LabelWithCardinality::cardinality).reversed().thenComparing(LabelWithCardinality::label));
		return labels.stream()
			.map(LabelWithCardinality::label)
			.limit(limit)
			.toList();
	}

	/**
	 * Retrieves a stream of label values that match the given name and start with the specified prefix,
	 * ordered by their cardinality.
	 *
	 * @param nameEquals        the exact name of the label to filter by, must not be null
	 * @param valueStartingWith the prefix that the label values should start with, must not be null
	 * @return a stream of label values that match the criteria, ordered by their cardinality, never null
	 */
	@Nonnull
	public Collection<String> getLabelValuesOrderedByCardinality(@Nonnull String nameEquals, @Nullable String valueStartingWith, int limit) {
		final Iterator<Entry<Label, TransactionalObjectBPlusTree<Long, Long>>> it = this.labelIndex.greaterOrEqualEntryIterator(new Label(nameEquals, valueStartingWith));
		final ObjectIntMap<String> cardinalities = new ObjectIntHashMap<>(256);
		while (it.hasNext()) {
			final Entry<Label, TransactionalObjectBPlusTree<Long, Long>> entry = it.next();
			final Label nextLabel = entry.key();
			final String valueAsString = ofNullable(nextLabel.value()).map(EvitaDataTypes::formatValue).orElse("");
			if (nextLabel.name().equals(nameEquals) && (valueStartingWith == null || valueAsString.startsWith(valueStartingWith))) {
				final int labelCardinality = entry.value().size();
				cardinalities.putOrAdd(valueAsString, labelCardinality, labelCardinality);
			} else {
				break;
			}
		}
		final List<LabelWithCardinality> labels = new ArrayList<>(cardinalities.size());
		for (ObjectIntCursor<String> cardinality : cardinalities) {
			labels.add(new LabelWithCardinality(cardinality.key, cardinality.value));
		}

		labels.sort(Comparator.comparingInt(LabelWithCardinality::cardinality).reversed().thenComparing(LabelWithCardinality::label));
		return labels.stream()
			.map(LabelWithCardinality::label)
			.limit(limit)
			.map(Object::toString)
			.toList();
	}

	/**
	 * Updates various session-related indexes based on the given traffic recording and
	 * session descriptor. This method ensures that session statistics such as duration,
	 * fetch count, bytes fetched, and recording types are properly reflected in the
	 * respective internal indexes.
	 *
	 * @param trafficRecording  the traffic recording containing session-specific data to be indexed, must not be null
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
			(sso, newValue) ->
				this.sessionRecordingTypeIndex.computeIfAbsent(
						newValue,
						trt ->
							new TransactionalObjectBPlusTree<>(Long.class, Long.class))
					.insert(sso, sso)
		);

		if (trafficRecording instanceof QueryContainer queryContainer) {
			for (Label label : queryContainer.labels()) {
				this.labelIndex.upsert(
					label,
					values -> {
						if (values == null) {
							values = new TransactionalObjectBPlusTree<>(Long.class, Long.class);
						}
						final Long seqOrder = Objects.requireNonNull(queryContainer.sessionSequenceOrder());
						values.insert(seqOrder, seqOrder);
						return values;
					}
				);
			}
		}
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
			this.sessionRecordingTypeIndex.get(recordingType).delete(sessionSequenceOrder);
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
		 * @param trafficRecording   the traffic recording containing values to be compared
		 *                           against the current state
		 * @param onMaxDuration      callback invoked when a new maximum duration is found;
		 *                           provides the session sequence order, the previous maximum,
		 *                           and the new maximum duration
		 * @param onMaxFetchCount    callback invoked when a new maximum fetch count is found;
		 *                           provides the session sequence order, the previous maximum,
		 *                           and the new maximum fetch count
		 * @param onMaxFetchBytes    callback invoked when a new maximum fetch size in bytes is found;
		 *                           provides the session sequence order, the previous maximum,
		 *                           and the new maximum fetch size in bytes
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

	/**
	 * Represents a label and its associated cardinality. The label is a combination
	 * of a name and an optional value, which is used to describe and categorize
	 * session-related data. The cardinality indicates the count of objects or items
	 * associated with this particular label.
	 *
	 * This class is utilized within the context of indexing and querying operations
	 * to provide metadata about session recordings and their attributes along with
	 * their frequency or occurrence count.
	 *
	 * The label component must not be null, while the cardinality is an integer
	 * value representing its occurrence.
	 *
	 * @param label       the label object containing name and value, must not be null
	 * @param cardinality the count of items associated with the label
	 */
	private record LabelWithCardinality(
		@Nonnull String label,
		int cardinality
	) {
	}
}
