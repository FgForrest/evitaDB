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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies compression behavior of the {@link ObservableOutput} and {@link ObservableInput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CompressedInputOutputTest extends AbstractObservableInputOutputTest {
	private final static int BIG_PAYLOAD_SIZE = PAYLOAD_SIZE * 50;

	@Test
	void shouldWriteAndReadCompressedData() {
		final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(
			baos, bufferSize, bufferSize, 0
		).computeCRC32();

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		writeRandomRecord(output, controlOutput, BIG_PAYLOAD_SIZE);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		final byte[] payload = readAndVerifyRecord(input, BIG_PAYLOAD_SIZE);

		final Input controlInput = new Input(new ByteArrayInputStream(controlBaos.toByteArray()), 24);
		final byte[] controlPayload = new byte[bufferSize];
		controlInput.readBytes(controlPayload);

		assertArrayEquals(
			Arrays.copyOfRange(controlPayload, HEADER_SIZE, HEADER_SIZE + BIG_PAYLOAD_SIZE),
			payload
		);
	}
}
