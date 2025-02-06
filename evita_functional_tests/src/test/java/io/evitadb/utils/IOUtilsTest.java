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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test verifies contract of {@link IOUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class IOUtilsTest {

	@Test
	void testCopyAllWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, buffer);

		assertEquals(9, bytesCopied);
		assertEquals("test data", outputStream.toString());
	}

	@Test
	void testCopyWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, 9, buffer);

		assertEquals(9, bytesCopied);
		assertEquals("test data", outputStream.toString());
	}

	@Test
	void testCopyPartialWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, 3, buffer);

		assertEquals(3, bytesCopied);
		assertEquals("tes", outputStream.toString());
	}

	@Test
	void testCloseWithoutException() {
		IOUtils.close(
			IllegalStateException::new,
			() -> System.out.println("First resource closed successfully."),
			() -> System.out.println("Second resource closed successfully.")
		);
	}

	@Test
	void testCloseWithSingleException() {
		IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
			IOUtils.close(IllegalStateException::new,
				() -> {
					throw new IOException("Single exception in close");
				}
			)
		);

		assertEquals("Single exception in close", exception.getSuppressed()[0].getMessage());
	}

	@Test
	void testCloseWithMultipleExceptions() {
		IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
			IOUtils.close(IllegalStateException::new,
				() -> {
					throw new IOException("Exception 1");
				},
				() -> {
					throw new IOException("Exception 2");
				}
			)
		);

		assertEquals(2, exception.getSuppressed().length);
		assertEquals("Exception 1", exception.getSuppressed()[0].getMessage());
		assertEquals("Exception 2", exception.getSuppressed()[1].getMessage());
	}
}