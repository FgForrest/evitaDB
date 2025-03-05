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


import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.BitUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract superclass for tests in this package.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
abstract class AbstractObservableInputOutputTest {
	public static final int HEADER_SIZE = 1 + 4;
	/**
	 * Overhead is 13B: size (int), control (byte), crc (long).
	 */
	public static final int OVERHEAD_SIZE = HEADER_SIZE + 8;
	public static final int PAYLOAD_SIZE = 12;
	public static final int RECORD_SIZE = PAYLOAD_SIZE + OVERHEAD_SIZE;
	protected final CRC32C crc32C = new CRC32C();
	protected final Random random = new Random();

	/**
	 * Writes a random record to the provided {@link ObservableOutput}.
	 * The method generates random payload bytes, and updates the provided observable output to mark various record
	 * positions.
	 *
	 * @param output the {@link ObservableOutput} to which the record data will be written
	 * @param length the length of the random payload data to generate and write
	 * @return the start position of the record in the control output
	 */
	protected long writeRandomRecord(@Nonnull ObservableOutput<?> output, int length) {
		return writeRandomRecord(output, null, length);
	}

	/**
	 * Writes a random record to the provided {@link Output}.
	 * The method generates random payload bytes, calculates a CRC32C checksum, writes metadata and payload
	 * to the control output.
	 *
	 * @param output the {@link Output} to which the record data will be written
	 * @param length the length of the random payload data to generate and write
	 * @return the start position of the record in the control output
	 */
	protected long writeRandomRecord(@Nonnull Output output, int length) {
		return writeRandomRecord(null, output, length);
	}

	/**
	 * Writes a random record to the provided {@link ObservableOutput} and its associated control output.
	 * The method generates random payload bytes, calculates a CRC32C checksum, writes metadata and payload
	 * to the control output, and updates the provided observable output to mark various record positions.
	 *
	 * @param output        the {@link ObservableOutput} to which the record data will be written
	 * @param controlOutput the {@link Output} for writing the control metadata and payload
	 * @param length        the length of the random payload data to generate and write
	 * @return the start position of the record in the control output / output
	 */
	protected long writeRandomRecord(@Nullable ObservableOutput<?> output, @Nullable Output controlOutput, int length) {
		final byte[] bytes = generateBytes(length);
		return writeRecord(output, controlOutput, length, bytes);
	}

	/**
	 * Writes all record bytes to the provided {@link ObservableOutput} and its associated control output.
	 * The method generates random payload bytes, calculates a CRC32C checksum, writes metadata and payload
	 * to the control output, and updates the provided observable output to mark various record positions.
	 *
	 * @param output        the {@link ObservableOutput} to which the record data will be written
	 * @param controlOutput the {@link Output} for writing the control metadata and payload
	 * @param length        the length of the random payload data to generate and write
	 * @return the start position of the record in the control output / output
	 */
	public long writeRecord(@Nullable ObservableOutput<?> output, @Nullable Output controlOutput, int length, byte[] bytes) {
		long startPosition = -1;
		final byte controlByte = BitUtils.setBit((byte) 0, StorageRecord.CRC32_BIT, true);

		if (controlOutput != null) {
			startPosition = controlOutput.total();
			this.crc32C.reset();
			this.crc32C.update(bytes);
			this.crc32C.update(controlByte);
			controlOutput.writeInt(length + OVERHEAD_SIZE);
			controlOutput.writeByte(controlByte);
			controlOutput.writeBytes(bytes);
			controlOutput.writeLong(this.crc32C.getValue());
			controlOutput.flush();
		}

		if (output != null) {
			if (startPosition == -1) {
				startPosition = output.total();
			}
			output.markStart();
			output.markRecordLengthPosition();
			output.writeInt(0);
			output.writeByte(0);
			output.markPayloadStart();
			output.writeBytes(bytes);
			output.markEnd(controlByte);
		}

		return startPosition;
	}

	/**
	 * Seeks to a specified position in the given {@link ObservableInput}, reads a record with the specified payload size,
	 * and verifies the consistency of the record.
	 *
	 * @param input         the {@link ObservableInput} object to operate on
	 * @param startPosition the position in the input to seek to
	 * @param payloadSize   the size of the payload in the record to be verified
	 */
	protected void seekReadAndVerifyRecord(@Nonnull ObservableInput<RandomAccessFileInputStream> input, long startPosition, int payloadSize) {
		input.seek(new FileLocation(startPosition, payloadSize));
		readAndVerifyRecord(input, payloadSize);
	}

	/**
	 * Reads a record from the provided {@link ObservableInput}, verifies its structure, and validates its payload size.
	 * The method reads the length, control byte, and payload of the record and checks their consistency.
	 *
	 * @param input       the {@link ObservableInput} object to read the record from
	 * @param payloadSize the expected size of the payload in bytes
	 * @return the payload bytes of the record
	 */
	protected byte[] readAndVerifyRecord(@Nonnull ObservableInput<?> input, int payloadSize) {
		input.markStart();
		final int length = input.readInt();
		byte controlByte = input.readByte();
		input.markPayloadStart(length, controlByte);
		final byte[] payload = input.readBytes(payloadSize);
		input.markEnd(controlByte);

		if (BitUtils.isBitSet(controlByte, StorageRecord.COMPRESSION_BIT)) {
			assertTrue(length < payloadSize + OVERHEAD_SIZE);
		} else {
			assertEquals(payloadSize + OVERHEAD_SIZE, length);
		}

		// first byte of payload is control byte
		assertEquals(payloadSize, payload.length);

		return payload;
	}

	/**
	 * Generates an array of random bytes with the specified length.
	 *
	 * @param count the number of random bytes to generate
	 * @return a byte array filled with random values
	 */
	protected byte[] generateBytes(int count) {
		return generateBytes(count, this.random);
	}

	/**
	 * Generates an array of random bytes with the specified length.
	 *
	 * @param count the number of random bytes to generate
	 * @param theRandom random number generator
	 * @return a byte array filled with random values
	 */
	protected byte[] generateBytes(int count, @Nonnull Random theRandom) {
		final byte[] result = new byte[count];
		theRandom.nextBytes(result);
		return result;
	}

}
