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

import io.evitadb.store.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ObservableOutput} focusing on written-bytes accounting and compression savings.
 */
@DisplayName("ObservableOutput written bytes and compression accounting")
public class ObservableOutputTest {
	private static final int DEFAULT_BUFFER = ObservableOutput.DEFAULT_FLUSH_SIZE << 2;

	@Test
	@DisplayName("shouldReportExactWrittenBytesWithoutCompression")
	void shouldReportExactWrittenBytesWithoutCompression() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ObservableOutput<ByteArrayOutputStream> out =
			new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L).computeCRC32();

		// write multiple records without compression
		for (int i = 0; i < 50; i++) {
			final byte[] payload = ("record-" + i).repeat(10).getBytes(StandardCharsets.UTF_8);
			writeRecord(out, payload, false);
			assertEquals(baos.size(), out.getWrittenBytesSinceReset(),
				"Written bytes must equal stream size without compression");
		}
	}

	@Test
	@DisplayName("shouldReportExactWrittenBytesWithCompression")
	void shouldReportExactWrittenBytesWithCompression() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ObservableOutput<ByteArrayOutputStream> out =
			new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L).computeCRC32().compress();

		// write alternating compressible and incompressible payloads
		final Random rnd = new Random(42);
		for (int i = 0; i < 200; i++) {
			final boolean compressible = (i % 2 == 0);
			final byte[] payload;
			if (compressible) {
				payload = new byte[300 + (i % 50)];
				// highly compressible: filled with the same byte
				Arrays.fill(payload, (byte) 'A');
			} else {
				payload = new byte[300 + (i % 50)];
				rnd.nextBytes(payload);
			}
			writeRecord(out, payload, false);
			assertEquals(baos.size(), out.getWrittenBytesSinceReset(),
				"Written bytes must equal stream size with compression");
		}
	}

	@Test
	@DisplayName("shouldMaintainCorrectFileLocationOffsetsWithCompression")
	void shouldMaintainCorrectFileLocationOffsetsWithCompression() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ObservableOutput<ByteArrayOutputStream> out =
			new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L).computeCRC32().compress();

		for (int i = 0; i < 100; i++) {
			final byte[] payload = ("X").repeat(1024).getBytes(StandardCharsets.UTF_8);
			final long expectedStart = baos.size();
			final FileLocation fl = writeRecord(out, payload, false);
			assertEquals(expectedStart, fl.startingPosition(),
				"FileLocation.start must equal stream size before writing the record");
		}
	}

	@Test
	@DisplayName("shouldMatchWrittenBytesUnderRandomPayloadsAndCompression")
	void shouldMatchWrittenBytesUnderRandomPayloadsAndCompression() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ObservableOutput<ByteArrayOutputStream> out =
			new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L).computeCRC32().compress();

		final Random rnd = new Random(123);
		for (int i = 0; i < 1000; i++) {
			final int size = rnd.nextInt(1, 800);
			final boolean compressible = rnd.nextBoolean();
			final boolean suppressCompressionThisRecord = rnd.nextBoolean();
			final byte[] payload = new byte[size];
			if (compressible) {
				Arrays.fill(payload, (byte) 'Z');
			} else {
				rnd.nextBytes(payload);
			}
			writeRecord(out, payload, suppressCompressionThisRecord);
			assertEquals(baos.size(), out.getWrittenBytesSinceReset(),
				"Fuzzy: written bytes must equal stream size after each record");
		}
	}

	/**
	 * Writes one logical record with ObservableOutput protocol.
	 *
	 * - markStart()
	 * - write placeholders (record length INT + control byte)
	 * - markPayloadStart()
	 * - write payload
	 * - markEnd(controlByte) or markEndSuppressingCompression(controlByte)
	 */
	private static FileLocation writeRecord(
		final ObservableOutput<ByteArrayOutputStream> out,
		final byte[] payload,
		final boolean suppressCompression
	) {
		out.markStart();
		out.markRecordLengthPosition();
		out.writeInt(0);
		out.writeByte((byte) 0);
		out.markPayloadStart();
		out.writeBytes(payload, 0, payload.length);
		final byte control = 0;
		if (suppressCompression) {
			return out.markEndSuppressingCompression(control);
		} else {
			return out.markEnd(control);
		}
	}
}
