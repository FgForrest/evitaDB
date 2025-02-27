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

package io.evitadb.store.kryo;


import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.BitUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test verifies compression behavior of the {@link ObservableOutput} and {@link ObservableInput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CompressedInputOutputTest extends AbstractObservableInputOutputTest {
	public static final int REPETITIONS = 50;
	private final static int BIG_PAYLOAD_SIZE = PAYLOAD_SIZE * REPETITIONS;

	@Test
	void shouldWriteAndReadCompressedData() {
		final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, bufferSize, bufferSize, 0)
			.computeCRC32()
			.deflate();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		final byte[] bytes = generateBytes(PAYLOAD_SIZE);
		final byte[] repeatedBytes = new byte[BIG_PAYLOAD_SIZE];
		for (int i = 0; i < REPETITIONS; i++) {
			System.arraycopy(bytes, 0, repeatedBytes, i * PAYLOAD_SIZE, PAYLOAD_SIZE);
		}
		writeRecord(output, controlOutput, BIG_PAYLOAD_SIZE, repeatedBytes);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24)
			.computeCRC32()
			.inflate();

		final byte[] payload = readAndVerifyRecord(input, BIG_PAYLOAD_SIZE);

		final Input controlInput = new Input(new ByteArrayInputStream(controlBaos.toByteArray()), 24);
		final byte[] controlPayload = new byte[bufferSize];
		controlInput.readBytes(controlPayload);

		assertArrayEquals(
			Arrays.copyOfRange(controlPayload, HEADER_SIZE, HEADER_SIZE + BIG_PAYLOAD_SIZE),
			payload
		);
	}

	@Test
	void shouldNotCompressIncompressibleData() {
		final int bufferSize = PAYLOAD_SIZE + OVERHEAD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, bufferSize, bufferSize, 0)
			.computeCRC32()
			.deflate();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		final byte[] bytes = new byte[PAYLOAD_SIZE];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}

		writeRecord(output, controlOutput, PAYLOAD_SIZE, bytes);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24)
			.computeCRC32()
			.inflate();

		// read control byte
		input.markStart();
		input.skip(4);
		byte controlByte = input.readByte();
		// verify that compression bit is not set
		assertFalse(BitUtils.isBitSet(controlByte, StorageRecord.COMPRESSION_BIT));

		// try to deserialize the record as normal
		input.reset();
		final byte[] payload = readAndVerifyRecord(input, PAYLOAD_SIZE);

		final Input controlInput = new Input(new ByteArrayInputStream(controlBaos.toByteArray()), 24);
		final byte[] controlPayload = new byte[bufferSize];
		controlInput.readBytes(controlPayload);

		assertArrayEquals(
			Arrays.copyOfRange(controlPayload, HEADER_SIZE, HEADER_SIZE + PAYLOAD_SIZE),
			payload
		);
	}

}
