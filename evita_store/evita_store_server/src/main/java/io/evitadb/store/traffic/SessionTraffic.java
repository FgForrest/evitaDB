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

import io.evitadb.core.traffic.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.NumberedByteBuffer;
import io.evitadb.store.traffic.stream.RecoverableOutputStream;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Session traffic class is used to store information about the session and the memory blocks where the queries and
 * mutations involved in this session are stored. This object is stored in Java heap memory because it's updated
 * with newly allocated memory blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SessionTraffic {
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
	@Getter private final ObservableOutput<RecoverableOutputStream> observableOutput;
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
	 * Registers a new traffic recording in this session.
	 *
	 * @param type         Type of the traffic recording.
	 * @param fetchCount   Number of fetch operations in this recording.
	 * @param bytesFetched Number of bytes fetched in this recording.
	 */
	public void registerRecording(@Nonnull TrafficRecordingType type, int fetchCount, int bytesFetched) {
		this.recordingTypes.add(type);
		this.fetchCount += fetchCount;
		this.bytesFetchedTotal += bytesFetched;
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

}
