/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.TrafficRecordingReader;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.store.spi.TrafficRecorder.StreamDirection;
import io.evitadb.store.traffic.serializer.CurrentSessionRecordContext;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.store.spi.TrafficRecorder.createRequestPredicate;
import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;
import static java.util.Optional.ofNullable;

/**
 * InputStreamTrafficRecordReader reads traffic records from an input stream and allows querying of these records
 * based on specific criteria. This class is not thread-safe and requires external synchronization if accessed from
 * multiple threads.
 *
 * It makes use of a Kryo instance pool for deserialization purposes and maintains an index for the records to support
 * efficient querying. The reader also handles session records and can stream recordings from storage based on a file
 * location and session details.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@NotThreadSafe
public class InputStreamTrafficRecordReader implements TrafficRecordingReader, Closeable {
	/**
	 * Pool of Kryo instances used for serialization of traffic data.
	 */
	private final Kryo trafficRecorderKryo = KryoFactory.createKryo(
		WalKryoConfigurer.INSTANCE
			.andThen(QuerySerializationKryoConfigurer.INSTANCE)
			.andThen(TrafficRecordingSerializationKryoConfigurer.INSTANCE)
	);
	private final ObservableInput<AbstractRandomAccessInputStream> input;
	/**
	 * Index used for querying the traffic data.
	 */
	private final TrafficRecordingIndex sessionIndex;
	/**
	 * Input stream used for reading the traffic data.
	 */
	private final AbstractRandomAccessInputStream inputStream;

	public InputStreamTrafficRecordReader(@Nonnull AbstractRandomAccessInputStream inputStream) throws IOException {
		this.inputStream = inputStream;
		this.sessionIndex = new TrafficRecordingIndex();
		long position = 0L;

		inputStream.seek(position);
		this.input = new ObservableInput<>(inputStream);

		final byte[] descriptorByteBufferArray = new byte[LEAD_DESCRIPTOR_BYTE_SIZE];
		final ByteBuffer descriptorByteBuffer = ByteBuffer.allocate(LEAD_DESCRIPTOR_BYTE_SIZE);
		int read = inputStream.read(descriptorByteBufferArray, 0, descriptorByteBufferArray.length);
		position += read;

		long lastSessionSequenceOrder = -1;
		while (read == descriptorByteBufferArray.length) {
			descriptorByteBuffer.clear();
			descriptorByteBuffer.put(descriptorByteBufferArray);
			descriptorByteBuffer.flip();
			long sessionSequenceOrder = descriptorByteBuffer.getLong();
			final int sessionRecordsCount = descriptorByteBuffer.getInt();
			final int totalSize = descriptorByteBuffer.getInt();
			this.sessionIndex.setupSession(new SessionLocation(sessionSequenceOrder, sessionRecordsCount, new FileLocation(position, totalSize)));

			if (lastSessionSequenceOrder != -1 && lastSessionSequenceOrder + 1 != sessionSequenceOrder) {
				log.warn(
					"Session sequence order mismatch detected: expected {}, got {}",
					lastSessionSequenceOrder + 1, sessionSequenceOrder
				);
			}

			this.input.seekWithUnknownLength(position);
			position += totalSize;
			StorageRecord<TrafficRecording> theRecord;
			for (int i = 0; i < sessionRecordsCount; i++) {
				theRecord = StorageRecord.read(
					this.input,
					(theInput, recordLength) -> CurrentSessionRecordContext.fetch(
						sessionSequenceOrder,
						sessionRecordsCount,
						() -> (TrafficRecording) this.trafficRecorderKryo.readClassAndObject(this.input)
					)
				);
				ofNullable(theRecord.payload()).ifPresent(
					recording -> this.sessionIndex.indexRecording(sessionSequenceOrder, recording)
				);
			}

			this.inputStream.seek(position);
			read = inputStream.read(descriptorByteBufferArray);
			position += read;
			lastSessionSequenceOrder = sessionSequenceOrder;
		}
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request) {
		final Predicate<TrafficRecording> requestPredicate = createRequestPredicate(request, StreamDirection.FORWARD);
		return this.sessionIndex.getSessionStream(request)
			.flatMap(
				it -> this.readSessionRecords(
					it.sequenceOrder(), it.sessionRecordsCount(), it.fileLocation()
				)
			)
			.filter(requestPredicate);
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordingsReversed(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException, IndexNotReady {
		final Predicate<TrafficRecording> requestPredicate = createRequestPredicate(request, StreamDirection.REVERSE);
		return this.sessionIndex.getSessionReversedStream(request)
			.flatMap(
				it -> {
					// this is inefficient, but we need to reverse the order of the records and there is no other simple way around
					// if it happens to be slow in real world scenarios, we'd have to add a support to the index
					final List<TrafficRecording> recordings = this.readSessionRecords(
						it.sequenceOrder(), it.sessionRecordsCount(), it.fileLocation()
					).collect(Collectors.toCollection(ArrayList::new));
					Collections.reverse(recordings);
					return recordings.stream();
				}
			)
			.filter(requestPredicate);
	}

	@Override
	public void close() throws IOException {
		IOUtils.close(
			() -> new UnexpectedIOException("Failed to close observable input-"),
			this.input::close
		);
	}

	/**
	 * Reads session records from a specified file location and provides a stream of TrafficRecording objects.
	 * The method ensures that the records are read only if the session exists and the file location is updated
	 * accordingly to prevent redundant reads.
	 *
	 * @param sessionSequenceId the unique identifier for the session sequence to read records for
	 * @param fileLocation      the file location specifying where to read the session records from
	 * @return a stream of TrafficRecording objects read from the specified file location
	 */
	@Nonnull
	private Stream<TrafficRecording> readSessionRecords(
		long sessionSequenceId,
		int sessionRecordsCount,
		@Nonnull FileLocation fileLocation
	) {
		final AtomicLong lastLocationRead = new AtomicLong(-1);
		return Stream.generate(
				() -> {
					final long lastFileLocation = lastLocationRead.get();
					// finalize stream when the expected session end position is reached
					if (lastFileLocation != -1L && lastFileLocation == fileLocation.endPosition()) {
						return null;
					} else {
						// read the next record from the file
						final long startPosition = lastLocationRead.get() == -1 ?
							fileLocation.startingPosition() : lastFileLocation;
						try {
							this.input.seekWithUnknownLength(startPosition);
							final StorageRecord<TrafficRecording> tr = StorageRecord.read(
								this.input,
								(theInput, recordLength) -> CurrentSessionRecordContext.fetch(
									sessionSequenceId,
									sessionRecordsCount,
									() -> (TrafficRecording) this.trafficRecorderKryo.readClassAndObject(this.input)
								)
							);
							lastLocationRead.set(startPosition + tr.fileLocation().recordLength());
							// return the payload of the record
							return tr.payload();
						} catch (Exception ex) {
							log.error(
								"Error reading session #{} traffic record from disk buffer at position {}: {}",
								sessionSequenceId, startPosition, ex.getMessage()
							);
							return null;
						}
					}
				}
			)
			.takeWhile(Objects::nonNull);
	}

}
