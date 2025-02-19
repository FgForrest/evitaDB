/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link ObservableOutput} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ObservableOutputTest extends AbstractObservableInputOutputTest {

	@DisplayName("Exception should be thrown when single record is larger than the buffer size.")
	@Test
	void shouldFailToWriteRecordWhenThereIsNoRoom() {
		final int bufferSize = RECORD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, bufferSize, bufferSize, 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		assertThrows(KryoException.class, () -> writeRandomRecord(output, controlOutput, 32));
	}

	@DisplayName("Exception should be thrown when first record is ok but second is larger than the buffer size.")
	@Test
	void shouldFailToWriteSecondLargerRecordWhenThereIsNoRoom() {
		final int bufferSize = RECORD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, bufferSize, bufferSize, 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		// this should be ok
		writeRandomRecord(output, controlOutput, PAYLOAD_SIZE);
		assertThrows(KryoException.class, () -> writeRandomRecord(output, controlOutput, PAYLOAD_SIZE + 1));
	}

	@DisplayName("Write single record with all buffer sizes aligned to record size.")
	@Test
	void shouldWriteSingleRecord() {
		final int bufferSize = RECORD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, bufferSize, bufferSize, 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		writeRandomRecord(output, controlOutput, PAYLOAD_SIZE);

		assertArrayEquals(controlOutput.toBytes(), output.toBytes());
		assertArrayEquals(controlBaos.toByteArray(), baos.toByteArray());
		assertEquals(bufferSize, output.total());
		assertEquals(0, output.position());
	}

	@DisplayName("Write multiple records - all aligned to exact correct buffer size.")
	@Test
	void shouldWriteMultipleRecords() {
		final int count = 16;
		final int flushSize = RECORD_SIZE;
		final int bufferSize = flushSize * count;

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, flushSize, bufferSize, 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		for(int i = 0; i < count; i++) {
			writeRandomRecord(output, controlOutput, PAYLOAD_SIZE);
		}

		// our output stream was regularly flushed
		assertArrayEquals(new byte[0], output.toBytes());
		assertArrayEquals(controlBaos.toByteArray(), baos.toByteArray());
		assertEquals(bufferSize, output.total());
		assertEquals(0, output.position());
	}

	@DisplayName("Write multiple records - with buffer for 1.3 size of record.")
	@Test
	void shouldWriteMultipleRecordsWithDifferentFlushBufferSize() {
		final int count = 2;
		final int flushSize = 32;
		final int bufferSize = RECORD_SIZE * count;

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, flushSize, (int) (flushSize * 1.3), 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		for(int i = 0; i < count; i++) {
			writeRandomRecord(output, controlOutput, PAYLOAD_SIZE);
		}

		output.flush();
		// our output stream was regularly flushed
		assertArrayEquals(new byte[0], output.toBytes());
		assertArrayEquals(controlBaos.toByteArray(), baos.toByteArray());
		assertEquals(bufferSize, output.total());
		assertEquals(0, output.position());
	}

	@DisplayName("Write multiple records - with buffer not big enough for two records having second record fit to entire buffer size.")
	@Test
	void shouldWriteMultipleRecordsWithDifferentFlushBufferSizeTriggeringByteArrayAllocation() {
		final int flushSize = RECORD_SIZE - 2;
		final int bufferSize = RECORD_SIZE + 128;

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(baos, flushSize, 128, 0).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		writeRandomRecord(output, controlOutput, PAYLOAD_SIZE);
		writeRandomRecord(output, controlOutput, 128 - OVERHEAD_SIZE);

		// our output stream was regularly flushed
		assertArrayEquals(new byte[0], output.toBytes());
		assertArrayEquals(controlBaos.toByteArray(), baos.toByteArray());
		assertEquals(bufferSize, output.total());
		assertEquals(0, output.position());
	}

}
