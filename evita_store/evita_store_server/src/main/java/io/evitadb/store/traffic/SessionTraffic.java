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

import io.evitadb.api.requestResponse.trafficRecording.EntityFetchContainer;
import io.evitadb.api.requestResponse.trafficRecording.Label;
import io.evitadb.api.requestResponse.trafficRecording.MutationContainer;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.SourceQueryStatisticsContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.NumberedByteBuffer;
import io.evitadb.store.traffic.stream.RecoverableOutputStream;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Session traffic class is used to store information about the session and the memory blocks where the queries and
 * mutations involved in this session are stored. This object is stored in Java heap memory because it's updated
 * with newly allocated memory blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SessionTraffic implements Closeable {
	/**
	 * Id of the session.
	 */
	@Getter private final UUID sessionId;
	/**
	 * Catalog version this session targets.
	 */
	@Getter private final long catalogVersion;
	/**
	 * Date and time when the session was created.
	 */
	@Getter private final OffsetDateTime created;
	/**
	 * Types of traffic recording that were observed in this session.
	 */
	@Getter private final Set<TrafficRecordingType> recordingTypes = EnumSet.noneOf(TrafficRecordingType.class);
	/**
	 * Indexes of memory blocks where the queries and mutations involved in this session are stored.
	 */
	private final CompositeIntArray blockIds;
	/**
	 * Contains current byte buffer where the queries and mutations are stored.
	 */
	private final ObservableOutput<RecoverableOutputStream> observableOutput;
	/**
	 * Index of source query counters indexed by `sourceQueryId`.
	 */
	private Map<UUID, SourceQueryCounter> sourceQueryCounterIndex;
	/**
	 * Counter of records in this session.
	 */
	private final AtomicInteger recordCounter = new AtomicInteger();
	/**
	 * Counter of queries in this session.
	 */
	private final AtomicInteger queryCounter = new AtomicInteger();
	/**
	 * Counter of separate entity fetches in this session.
	 */
	private final AtomicInteger entityFetchCounter = new AtomicInteger();
	/**
	 * Counter of mutations in this session.
	 */
	private final AtomicInteger mutationCounter = new AtomicInteger();
	/**
	 * Duration of the session in milliseconds.
	 */
	@Getter private int durationInMillis;
	/**
	 * Total number of fetch operations in this session.
	 */
	@Getter private int fetchCount;
	/**
	 * Total number of bytes fetched in this session.
	 */
	@Getter private int bytesFetchedTotal;
	/**
	 * Number of records missed out in this session (due to sampling or memory shortage).
	 */
	@Getter private int recordsMissedOut;
	/**
	 * Flag indicating whether the session is finished.
	 */
	@Getter private FinishReason finished;

	/**
	 * Extracts the source query UUID from the specified query container's labels, if present.
	 *
	 * The method iterates through all labels associated with the provided query container
	 * and checks for a label with the name `LABEL_SOURCE_QUERY`. If such a label is found,
	 * its value is returned as a UUID. If no such label is found, the method returns null.
	 *
	 * @param queryContainer the container holding the query and its associated labels
	 * @return the UUID value of the source query label, or null if not found
	 */
	@Nullable
	private static UUID getSourceQueryId(QueryContainer queryContainer) {
		final Label[] labels = queryContainer.labels();
		for (Label label : labels) {
			if (io.evitadb.api.query.head.Label.LABEL_SOURCE_QUERY.equals(label.name())) {
				return (UUID) label.value();
			}
		}
		return null;
	}

	public SessionTraffic(
		@Nonnull UUID sessionId,
		long catalogVersion,
		@Nonnull OffsetDateTime created,
		@Nonnull byte[] writeBuffer,
		@Nonnull Supplier<NumberedByteBuffer> bufferSupplier
	) {
		this.sessionId = sessionId;
		this.catalogVersion = catalogVersion;
		this.created = created;
		this.blockIds = new CompositeIntArray();
		this.observableOutput = new ObservableOutput<>(
			new RecoverableOutputStream(
				() -> {
					try {
						final NumberedByteBuffer numberedByteBuffer = bufferSupplier.get();
						this.blockIds.add(numberedByteBuffer.number());
						return numberedByteBuffer.buffer();
					} catch (MemoryNotAvailableException ex) {
						throw new MemoryNotAvailableException(
							finishDueToMemoryShortage(), ex
						);
					}
				}
			),
			writeBuffer
		);
	}

	/**
	 * Provides the observable output associated with this session's traffic.
	 *
	 * @return an instance of ObservableOutput containing a RecoverableOutputStream
	 */
	@Nonnull
	public ObservableOutput<RecoverableOutputStream> getObservableOutput() {
		return observableOutput;
	}

	/**
	 * Reserves and returns next recording id.
	 * @return Next recording id.
	 */
	public int nextRecordingId() {
		return this.recordCounter.getAndIncrement();
	}

	/**
	 * Returns count of records in this session.
	 * @return Count of records in this session.
	 */
	public int getRecordCount() {
		return this.recordCounter.get();
	}

	/**
	 * Returns count of queries in this session.
	 * @return Count of queries in this session.
	 */
	public int getQueryCount() {
		return this.queryCounter.get();
	}

	/**
	 * Returns count of separate entity fetches in this session.
	 * @return Count of separate entity fetches in this session.
	 */
	public int getEntityFetchCount() {
		return this.entityFetchCounter.get();
	}

	/**
	 * Returns count of mutations in this session.
	 * @return Count of mutations in this session.
	 */
	public int getMutationCount() {
		return this.mutationCounter.get();
	}

	/**
	 * Registers a new traffic recording in this session.
	 *
	 * @param container Traffic recording container to be registered.
	 */
	public <T extends TrafficRecording> void registerRecording(T container) {
		this.recordingTypes.add(container.type());
		this.fetchCount += container.ioFetchCount();
		this.bytesFetchedTotal += container.ioFetchedSizeBytes();
		if (container instanceof QueryContainer queryContainer && this.sourceQueryCounterIndex != null) {
			this.queryCounter.incrementAndGet();
			final UUID sourceQueryId = getSourceQueryId(queryContainer);
			if (sourceQueryId != null) {
				final SourceQueryCounter sourceQueryCounter = this.sourceQueryCounterIndex.get(sourceQueryId);
				if (sourceQueryCounter != null) {
					sourceQueryCounter.append(
						queryContainer.primaryKeys().length,
						queryContainer.totalRecordCount(),
						queryContainer.ioFetchCount(),
						queryContainer.ioFetchedSizeBytes(),
						queryContainer.durationInMilliseconds()
					);
				}
			}
		} else if (container instanceof MutationContainer) {
			this.mutationCounter.incrementAndGet();
		} else if (container instanceof EntityFetchContainer) {
			this.entityFetchCounter.incrementAndGet();
		}
	}

	/**
	 * Registers a new record missed out in this session.
	 */
	public void registerRecordMissedOut() {
		this.recordsMissedOut++;
	}

	/**
	 * Returns iterator over all registered memory block ids containing queries and mutations of this session in correct
	 * order.
	 *
	 * @return Iterator over memory block ids.
	 */
	@Nonnull
	public OfInt getMemoryBlockIds() {
		return this.blockIds.iterator();
	}

	/**
	 * Returns the current position in the byte buffer where the queries and mutations are stored.
	 *
	 * @return current position in the byte buffer, or -1 if the buffer is not initialized.
	 */
	public int getCurrentByteBufferPosition() {
		return this.observableOutput.getOutputStream().getBufferPosition();
	}

	/**
	 * Marks the session as finished.
	 *
	 * @return buffer used for writing (so it could be reused for another session)
	 */
	@Nonnull
	public byte[] finish() {
		this.durationInMillis = (int) (System.currentTimeMillis() - this.created.toInstant().toEpochMilli());
		this.finished = FinishReason.REGULAR_FINISH;
		return this.observableOutput.getBuffer();
	}

	/**
	 * Marks the session as finished due to memory shortage.
	 *
	 * @return buffer used for writing (so it could be reused for another session)
	 */
	@Nonnull
	public byte[] finishDueToMemoryShortage() {
		this.durationInMillis = (int) (System.currentTimeMillis() - this.created.toInstant().toEpochMilli());
		this.finished = FinishReason.MEMORY_SHORTAGE;
		return this.observableOutput.getBuffer();
	}

	/**
	 * Returns whether the session is finished - no matter the reason.
	 *
	 * @return true if the session is finished, false otherwise
	 */
	public boolean isFinished() {
		return this.finished != null;
	}

	/**
	 * Creates a counter container for particular source query with the given id.
	 *
	 * @param sourceQueryId Id of the source query.
	 */
	public void setupSourceQuery(@Nonnull UUID sourceQueryId, @Nonnull OffsetDateTime created) {
		if (this.sourceQueryCounterIndex == null) {
			this.sourceQueryCounterIndex = CollectionUtils.createConcurrentHashMap(32);
		}
		this.sourceQueryCounterIndex.put(sourceQueryId, new SourceQueryCounter(created.toInstant().toEpochMilli()));
	}

	/**
	 * Finalizes the counter container for particular source query with the given id and returns the traffic recording
	 * container with aggregated data.
	 *
	 * @param sourceQueryId Id of the source query.
	 * @return Traffic recording container with aggregated data.
	 */
	public TrafficRecording closeSourceQuery(@Nonnull UUID sourceQueryId) {
		final Optional<SourceQueryCounter> sourceQueryCounter = Optional.ofNullable(this.sourceQueryCounterIndex)
			.map(it -> it.remove(sourceQueryId));
		return new SourceQueryStatisticsContainer(
			this.sessionId,
			nextRecordingId(),
			sourceQueryId,
			OffsetDateTime.now(),
			sourceQueryCounter.map(SourceQueryCounter::getComputeTime).orElse(0),
			sourceQueryCounter.map(SourceQueryCounter::getRecordsReturned).orElse(0),
			sourceQueryCounter.map(SourceQueryCounter::getTotalRecordCount).orElse(0),
			sourceQueryCounter.map(SourceQueryCounter::getIoFetchCount).orElse(0),
			sourceQueryCounter.map(SourceQueryCounter::getIoFetchedSizeBytes).orElse(0)
		);
	}

	@Override
	public void close() {
		this.observableOutput.close();
	}

	/**
	 * Represents various reasons why the session was finished.
	 */
	public enum FinishReason {

		/**
		 * The session finished regularly.
		 */
		REGULAR_FINISH,
		/**
		 * The session was prematurely abandoned due to memory shortage.
		 */
		MEMORY_SHORTAGE

	}

	/**
	 * Represents a counter for a single source query.
	 */
	@Getter
	@RequiredArgsConstructor
	private static class SourceQueryCounter {
		private final long started;
		private int recordsReturned;
		private int totalRecordCount;
		private int ioFetchCount;
		private int ioFetchedSizeBytes;
		private int computeTime;

		/**
		 * Updates the current counter values by adding the provided values to the existing ones.
		 *
		 * @param recordsReturned    The number of records returned to be added to the current total.
		 * @param totalRecordCount   The total record count to be added to the current total.
		 * @param ioFetchCount       The number of fetch operations performed to be added to the current total.
		 * @param ioFetchedSizeBytes The size in bytes of fetched data to be added to the current total.
		 * @param computeTime        The time spent computing the query to be added to the current total.
		 */
		void append(int recordsReturned, int totalRecordCount, int ioFetchCount, int ioFetchedSizeBytes, int computeTime) {
			this.recordsReturned += recordsReturned;
			this.totalRecordCount += totalRecordCount;
			this.ioFetchCount += ioFetchCount;
			this.ioFetchedSizeBytes += ioFetchedSizeBytes;
			this.computeTime += computeTime;
		}

	}

}
