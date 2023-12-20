/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies {@link ObservableInput} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ObservableInputTest {
	public static final int RECORD_SIZE = 24;
	public static final int PAYLOAD_SIZE = 12;
	public static final int OVERHEAD_SIZE = RECORD_SIZE - PAYLOAD_SIZE;
	private final Random random = new Random();
	private final CRC32C crc32C = new CRC32C();

	@DisplayName("Record written by standard Kryo output, should be read intact.")
	@Test
	void shouldReadRecord() {
		final int bufferSize = RECORD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final Output output = new Output(baos, bufferSize);

		writeRandomRecord(output, PAYLOAD_SIZE);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		readAndVerifyRecord(input, PAYLOAD_SIZE);
	}

	@DisplayName("Multiple random records of same size written by standard Kryo output, should be read intact.")
	@Test
	void shouldReadMultipleRandomRecords() {
		final int count = 512;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(RECORD_SIZE);
		final Output output = new Output(baos, RECORD_SIZE);

		for (int i = 0; i < count; i++) {
			writeRandomRecord(output, PAYLOAD_SIZE);
		}

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		for (int i = 0; i < count; i++) {
			readAndVerifyRecord(input, PAYLOAD_SIZE);
		}
	}

	@DisplayName("Multiple records of ransom size written by standard Kryo output, should be read intact.")
	@Test
	void shouldReadMultipleRandomRecordsOfDifferentSizes() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(8_192);
		final Output output = new Output(baos, 256);

		writeRandomRecord(output, 77);
		writeRandomRecord(output, 256);
		writeRandomRecord(output, 189);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		readAndVerifyRecord(input, 77);
		readAndVerifyRecord(input, 256);
		readAndVerifyRecord(input, 189);
	}

	@DisplayName("Records read by RandomAccessFile in random order should be intact.")
	@Test
	void shouldReadMultipleRandomRecordsInRandomFashionOfDifferentSizes() throws FileNotFoundException {
		final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "test.kryo");
		final File targetFileDescr = targetFile.toFile();
		targetFileDescr.delete();

		try {
			final FileOutputStream baos = new FileOutputStream(targetFileDescr);
			final Output output = new Output(baos, 256);

			final long s1 = writeRandomRecord(output, 77);
			final long s2 = writeRandomRecord(output, 256);
			final long s3 = writeRandomRecord(output, 189);

			final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(new RandomAccessFile(targetFileDescr, "r")), 24)
				.computeCRC32();

			seekReadAndVerifyRecord(input, s2, 256);
			seekReadAndVerifyRecord(input, s1, 77);
			seekReadAndVerifyRecord(input, s3, 189);
		} finally {
			targetFileDescr.delete();
		}
	}

	@DisplayName("Multiple records written by standard Kryo output, should be read intact.")
	@Test
	void shouldReadMultipleRandomRecordsOfRandomSizes() {
		final int count = 512;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(RECORD_SIZE);
		final Output output = new Output(baos, RECORD_SIZE);

		final List<Integer> sizes = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int rndSize = random.nextInt(1000);
			writeRandomRecord(output, rndSize);
			sizes.add(rndSize);
		}

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		for (Integer size : sizes) {
			readAndVerifyRecord(input, size);
		}
	}

	@DisplayName("Single large record written by standard Kryo output, should be read intact.")
	@Test
	void shouldReadRecordLargerThanBuffer() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(RECORD_SIZE * 4);
		final Output output = new Output(baos, RECORD_SIZE * 4);

		writeRandomRecord(output, RECORD_SIZE * 4);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(bais, 24).computeCRC32();

		readAndVerifyRecord(input, RECORD_SIZE * 4);
	}

	private int computeStartPositionFor(int[] parts, int index) {
		int loc = 0;
		for (int i = 0; i <= index; i++) {
			loc += parts[i];
		}
		return loc;
	}

	private void seekReadAndVerifyRecord(ObservableInput<RandomAccessFileInputStream> input, long startPosition, int payloadSize) {
		input.seek(new FileLocation(startPosition, payloadSize));
		readAndVerifyRecord(input, payloadSize);
	}

	private void readAndVerifyRecord(ObservableInput<?> input, int payloadSize) {
		input.markStart();
		final int length = input.readInt();
		input.markPayloadStart(length);
		final byte[] payload = input.readBytes(length - ObservableInputTest.OVERHEAD_SIZE);
		input.markEnd();

		assertEquals(payloadSize + ObservableInputTest.OVERHEAD_SIZE, length);
		assertEquals(payloadSize, payload.length);
	}

	private long writeRandomRecord(Output controlOutput, int length) {
		final byte[] bytes = generateBytes(length);

		crc32C.reset();
		crc32C.update(bytes);
		final long startPosition = controlOutput.total();
		controlOutput.writeInt(length + OVERHEAD_SIZE);
		controlOutput.writeBytes(bytes);
		controlOutput.writeLong(crc32C.getValue());
		controlOutput.flush();
		return startPosition;
	}

	private byte[] generateBytes(int count) {
		final byte[] result = new byte[count];
		random.nextBytes(result);
		return result;
	}

}