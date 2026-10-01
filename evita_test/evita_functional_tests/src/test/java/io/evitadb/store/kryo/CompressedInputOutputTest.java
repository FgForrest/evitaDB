/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.BitUtils;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * This test verifies compress behavior of the {@link ObservableOutput} and {@link ObservableInput}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
public class CompressedInputOutputTest extends AbstractObservableInputOutputTest {
	public static final int REPETITIONS = 50;
	private final static int BIG_PAYLOAD_SIZE = PAYLOAD_SIZE * REPETITIONS;
	/**
	 * Words compressible payloads are assembled from - a small vocabulary deflates well, so every record keeps its
	 * compression bit.
	 */
	private static final String[] VOCABULARY = {
		"product", "variant", "price", "stock", "category", "brand", "attribute", "reference", "locale", "currency"
	};

	@Test
	void shouldWriteAndReadCompressedData() {
		final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final ObservableOutput<?> output = new ObservableOutput<>(
			baos, bufferSize, bufferSize, 0,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
		);

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		final byte[] bytes = generateBytes(PAYLOAD_SIZE);
		final byte[] repeatedBytes = new byte[BIG_PAYLOAD_SIZE];
		for (int i = 0; i < REPETITIONS; i++) {
			System.arraycopy(bytes, 0, repeatedBytes, i * PAYLOAD_SIZE, PAYLOAD_SIZE);
		}
		writeRecord(output, controlOutput, BIG_PAYLOAD_SIZE, repeatedBytes);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(
			bais, 24,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
		);

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
		final ObservableOutput<?> output = new ObservableOutput<>(
			baos, bufferSize, bufferSize, 0,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
		);

		final ByteArrayOutputStream controlBaos = new ByteArrayOutputStream(bufferSize);
		final Output controlOutput = new Output(controlBaos, bufferSize);

		final byte[] bytes = new byte[PAYLOAD_SIZE];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}

		writeRecord(output, controlOutput, PAYLOAD_SIZE, bytes);

		final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		final ObservableInput<?> input = new ObservableInput<>(
			bais, 24,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
		);

		// read control byte
		input.markStart();
		input.skip(4);
		byte controlByte = input.readByte();
		// verify that compress bit is not set
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

	/**
	 * A compressed record whose raw bytes do not fit into the rest of the input buffer forces the inflater to refill
	 * the raw buffer from the underlying stream. `total()` after the record must still equal the stream offset of the
	 * record end - the WAL reader derives record sizes from its difference and refuses an intact file otherwise.
	 */
	@Test
	void shouldReportStreamOffsetAfterCompressedRecordsSpanningRawBufferRefills() {
		final int inputBufferSize = 64;
		final int recordCount = 40;
		final Random seededRandom = new Random(1687);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(65_536);
		final ObservableOutput<?> output = new ObservableOutput<>(
			baos, 16_384, 16_384, 0,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
		);

		final byte[][] payloads = new byte[recordCount][];
		final long[] recordEnds = new long[recordCount];
		for (int i = 0; i < recordCount; i++) {
			payloads[i] = generateCompressibleBytes(200 + seededRandom.nextInt(3_000), seededRandom);
			writeRecord(output, null, payloads[i].length, payloads[i]);
			// `total()` counts the uncompressed bytes, the stream offset is what actually reached the stream
			recordEnds[i] = output.getWrittenBytesSinceReset();
		}
		output.flush();

		final ObservableInput<?> input = new ObservableInput<>(
			new ByteArrayInputStream(baos.toByteArray()), inputBufferSize,
			Crc32CChecksumFactory.INSTANCE.createChecksum(),
			ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
		);

		int refillingRecords = 0;
		long recordStart = 0L;
		for (int i = 0; i < recordCount; i++) {
			// a compressed payload longer than the raw buffer cannot be inflated without at least one refill
			if (recordEnds[i] - recordStart - OVERHEAD_SIZE > inputBufferSize) {
				refillingRecords++;
			}
			assertArrayEquals(payloads[i], readAndVerifyRecord(input, payloads[i].length), "Payload of record " + i);
			assertEquals(recordEnds[i], input.total(), "Stream offset after record " + i);
			recordStart = recordEnds[i];
		}
		// the scenario is only proven when records actually refilled the raw buffer
		assertTrue(refillingRecords > recordCount / 2, "Only " + refillingRecords + " records refilled the raw buffer.");
	}

	/**
	 * Generates a payload of words from {@link #VOCABULARY} that deflates well.
	 *
	 * @param count     exact number of bytes to generate
	 * @param theRandom random number generator picking the words
	 * @return compressible payload of exactly `count` bytes
	 */
	@Nonnull
	private static byte[] generateCompressibleBytes(int count, @Nonnull Random theRandom) {
		final StringBuilder sb = new StringBuilder(count + 16);
		while (sb.length() < count) {
			sb.append(VOCABULARY[theRandom.nextInt(VOCABULARY.length)]).append(' ');
		}
		return Arrays.copyOf(sb.toString().getBytes(StandardCharsets.US_ASCII), count);
	}

}
