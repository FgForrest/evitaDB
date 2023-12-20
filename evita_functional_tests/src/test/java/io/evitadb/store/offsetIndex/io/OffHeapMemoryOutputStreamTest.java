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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream.OffHeapMemoryInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test verifies {@link OffHeapMemoryOutputStream} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class OffHeapMemoryOutputStreamTest {
	private final OffHeapMemoryOutputStream outputStream = new OffHeapMemoryOutputStream();

	@BeforeEach
	void setUp() {
		outputStream.init(1, ByteBuffer.allocateDirect(32), (regionIndex, os) -> {});
	}

	@AfterEach
	void tearDown() {
		outputStream.close();
	}

	@Test
	void shouldWriteAndReadSingleByte() throws IOException {
		outputStream.write(1);
		outputStream.write(5);
		outputStream.write(10);

		outputStream.flush();

		final byte[] bytes = outputStream.getInputStream().readAllBytes();
		assertArrayEquals(new byte[] {1, 5, 10}, bytes);
		assertEquals(3, outputStream.getPeakDataWrittenLength());
	}

	@Test
	void shouldWriteAndReadAllBytesAtOnce() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});

		outputStream.flush();

		final byte[] bytes = outputStream.getInputStream().readAllBytes();
		assertArrayEquals(new byte[] {1, 5, 10}, bytes);
		assertEquals(3, outputStream.getPeakDataWrittenLength());
	}

	@Test
	void shouldWriteAndReadBytesChunkUsingOffsetAndLength() throws IOException {
		outputStream.write(new byte[] {1, 5, 10, 15, 20, 25, 30, 35, 40, 45}, 2, 5);

		outputStream.flush();

		final byte[] bytes = outputStream.getInputStream().readAllBytes();
		assertArrayEquals(new byte[] {10, 15, 20, 25, 30}, bytes);
		assertEquals(5, outputStream.getPeakDataWrittenLength());
	}

	@Test
	void shouldReadByteUsingProvidedInputStream() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		assertEquals(3, inputStream.available());
		assertEquals((byte) 1, inputStream.read());
		assertEquals(2, inputStream.available());
		assertEquals((byte) 5, inputStream.read());
		assertEquals(1, inputStream.available());
		assertEquals((byte) 10, inputStream.read());
		assertEquals(0, inputStream.available());
		assertEquals((byte) -1, inputStream.read());
	}

	@Test
	void shouldReadBytesUsingProvidedInputStreamToLargerByteArray() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		final byte[] array = new byte[5];
		assertEquals(3, inputStream.read(array));
		assertArrayEquals(new byte[] {1, 5, 10, 0, 0}, array);
	}

	@Test
	void shouldReadAllBytesUsingProvidedInputStream() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		assertArrayEquals(new byte[] {1, 5, 10}, inputStream.readAllBytes());
	}

	@Test
	void shouldReadNBytesUsingProvidedInputStream() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		assertArrayEquals(new byte[] {1, 5}, inputStream.readNBytes(2));
	}

	@Test
	void shouldReadNBytesUsingOffsetAndLengthProvidedInputStream() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		final byte[] array = new byte[5];
		assertEquals(2, inputStream.readNBytes(array, 1, 2));
		assertArrayEquals(new byte[] {0, 1, 5, 0, 0}, array);
	}

	@Test
	void shouldReadSomeBytesUsingProvidedInputStreamAndSkippingAByte() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		assertEquals(1, inputStream.skip(1));
		assertEquals(5, inputStream.read());
		assertEquals(10, inputStream.read());
		assertEquals(-1, inputStream.read());
	}

	@Test
	void shouldReadSomeBytesUsingProvidedInputStreamAndSkippingNBytes() throws IOException {
		outputStream.write(new byte[] {1, 5, 10});
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

		inputStream.skipNBytes(2);
		assertEquals(10, inputStream.read());
		assertEquals(-1, inputStream.read());
	}
}