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
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.utils.IOUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class InputStreamTrafficRecordReader implements TrafficRecordingReader, Closeable {
	/**
	 * Byte buffer used for writing the descriptor to the disk buffer file.
	 */
	private final byte[] descriptorByteBufferArray = new byte[LEAD_DESCRIPTOR_BYTE_SIZE];
	private final ByteBuffer descriptorByteBuffer = ByteBuffer.allocate(LEAD_DESCRIPTOR_BYTE_SIZE);
	/**
	 * Pool of Kryo instances used for serialization of traffic data.
	 */
	private final Kryo trafficRecorderKryo = KryoFactory.createKryo(
		WalKryoConfigurer.INSTANCE
			.andThen(QuerySerializationKryoConfigurer.INSTANCE)
			.andThen(TrafficRecordingSerializationKryoConfigurer.INSTANCE)
	);

	/**
	 * Index used for querying the traffic data.
	 */
	private final TrafficRecordingIndex index;
	/**
	 * Input stream used for reading the traffic data.
	 */
	private final AbstractRandomAccessInputStream inputStream;
	/**
	 * Observable input stream used for reading the traffic data.
	 */
	private final ObservableInput<AbstractRandomAccessInputStream> input;

	public InputStreamTrafficRecordReader(@Nonnull AbstractRandomAccessInputStream inputStream) throws IOException {
		this.inputStream = inputStream;
		this.index = new TrafficRecordingIndex();
		inputStream.seek(0);
		this.input = new ObservableInput<>(inputStream);

		final int read = inputStream.read(this.descriptorByteBufferArray, 0, this.descriptorByteBufferArray.length);
		while (read == this.descriptorByteBufferArray.length) {
			this.descriptorByteBuffer.put(this.descriptorByteBufferArray);
			this.descriptorByteBuffer.flip();
			final long sessionSequenceOrder = this.descriptorByteBuffer.getLong();
			final int totalSize = this.descriptorByteBuffer.getInt();

		}
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request)
		throws TemporalDataNotAvailableException, IndexNotReady {
		return Stream.empty();
	}

	@Override
	public void close() throws IOException {
		IOUtils.close(
			() -> new UnexpectedIOException("Failed to close observable input-"),
			this.input::close
		);
	}
}
