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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test verifies contract of {@link IOUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("IOUtils contract tests")
class IOUtilsTest {

	@Test
	@DisplayName("copies all bytes with valid streams")
	void testCopyAllWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, buffer);

		assertEquals(9, bytesCopied);
		assertEquals("test data", outputStream.toString());
	}

	@Test
	@DisplayName("copies specified length with valid streams")
	void testCopyWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, 9, buffer);

		assertEquals(9, bytesCopied);
		assertEquals("test data", outputStream.toString());
	}

	@Test
	@DisplayName("copies partial number of bytes with valid streams")
	void testCopyPartialWithValidStreams() {
		InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[4];

		long bytesCopied = IOUtils.copy(inputStream, outputStream, 3, buffer);

		assertEquals(3, bytesCopied);
		assertEquals("tes", outputStream.toString());
	}

	@Test
	@DisplayName("executes lambda without exception")
	void testLambdaWithoutException() {
		IOUtils.executeSafely(
			IllegalStateException::new,
			() -> System.out.println("Lambda executed successfully.")
		);
	}

	@Test
	@DisplayName("wraps exception thrown by lambda")
	void testLambdaWithException() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class, () ->
				IOUtils.executeSafely(
					IllegalStateException::new,
					() -> {
						throw new IOException("Exception in lambda");
					}
				)
		);

		assertEquals("Exception in lambda", exception.getSuppressed()[0].getMessage());
	}

	@Test
	@DisplayName("executes consumer without exception")
	void testConsumerWithoutException() {
		IOUtils.executeSafely(
			1,
			IllegalStateException::new,
			i -> System.out.println("Lambda `" + i + "` executed successfully.")
		);
	}

	@Test
	@DisplayName("wraps exception thrown by consumer")
	void testConsumerWithException() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class, () ->
				IOUtils.executeSafely(
					1,
					IllegalStateException::new,
					i -> {
						throw new IOException("Exception in lambda");
					}
				)
		);

		assertEquals("Exception in lambda", exception.getSuppressed()[0].getMessage());
	}

	@Test
	@DisplayName("closes resources without exception")
	void testCloseWithoutException() {
		IOUtils.close(
			IllegalStateException::new,
			() -> System.out.println("First resource closed successfully."),
			() -> System.out.println("Second resource closed successfully.")
		);
	}

	@Test
	@DisplayName("aggregates single exception during close")
	void testCloseWithSingleException() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class, () ->
				IOUtils.close(
					IllegalStateException::new,
					() -> {
						throw new IOException("Single exception in close");
					}
				)
		);

		assertEquals("Single exception in close", exception.getSuppressed()[0].getMessage());
	}

	@Test
	@DisplayName("aggregates multiple exceptions during close")
	void testCloseWithMultipleExceptions() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class, () ->
				IOUtils.close(
					IllegalStateException::new,
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

	@Test
	@DisplayName("closes runnable resources without exception")
	void testCloseWithRunnableWithoutException() {
		IOUtils.close(
			IllegalStateException::new,
			() -> System.out.println("First resource closed successfully."),
			() -> System.out.println("Second resource closed successfully.")
		);
	}

	@Test
	@DisplayName("aggregates single throwable during safe close")
	void testCloseWithRunnableWithSingleException() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() ->
				IOUtils.closeSafely(
					() -> new IllegalStateException(),
					() -> {
						throw new RuntimeException("Single exception in close");
					}
				)
		);

		assertEquals("Single exception in close", exception.getSuppressed()[0].getMessage());
	}

	@Test
	@DisplayName("aggregates multiple throwables during safe close")
	void testCloseWithRunnableWithMultipleExceptions() {
		IllegalStateException exception = assertThrows(
			IllegalStateException.class, () ->
				IOUtils.closeSafely(
					() -> new IllegalStateException(),
					() -> {
						throw new RuntimeException("Exception 1");
					},
					() -> {
						throw new Error("Exception 2");
					}
				)
		);

		assertEquals(2, exception.getSuppressed().length);
		assertEquals("Exception 1", exception.getSuppressed()[0].getMessage());
		assertEquals("Exception 2", exception.getSuppressed()[1].getMessage());
	}

	@Test
	@DisplayName("logs and suppresses IOException during quiet close")
	void testCloseQuietlyWithIOExceptionThrowingRunnable() {
		IOUtils.closeQuietly(
			() -> System.out.println("First resource closed successfully."),
			() -> {
				throw new IOException("This exception should be caught and logged");
			},
			() -> System.out.println("Third resource closed successfully.")
		);
		// No assertion needed as the method should not throw any exception
	}

	@Test
	@DisplayName("logs and suppresses RuntimeException during safe close")
	void testCloseQuietlyWithRunnable() {
		IOUtils.closeSafely(
			() -> System.out.println("First resource closed successfully."),
			() -> {
				throw new RuntimeException("This exception should be caught and logged");
			},
			() -> System.out.println("Third resource closed successfully.")
		);
		// No assertion needed as the method should not throw any exception
	}

	@Test
	@DisplayName("logs and suppresses Error during safe close")
	void testCloseQuietlyWithRunnableThrowingError() {
		IOUtils.closeSafely(
			() -> System.out.println("First resource closed successfully."),
			() -> {
				throw new Error("This error should be caught and logged");
			},
			() -> System.out.println("Third resource closed successfully.")
		);
		// No assertion needed as the method should not throw any exception
	}

	@Test
	@DisplayName("copies input stream to a new file")
	void shouldCopyInputStreamToNewFileWhenFileDoesNotExist() throws Exception {
		final Path tempDir = Files.createTempDirectory("ioutils-copy-");
		final Path target = tempDir.resolve("target.bin");
		try {
			final InputStream inputStream = new ByteArrayInputStream("test data to file".getBytes());
			IOUtils.copy(inputStream, target);
			final String content = new String(Files.readAllBytes(target));
			assertEquals("test data to file", content);
		} finally {
			Files.deleteIfExists(target);
			Files.deleteIfExists(tempDir);
		}
	}

	@Test
	@DisplayName("fails overwrite contents target file already exists")
	void shouldOverWriteContentsWhenTargetAlreadyExists() throws Exception {
		final Path tempDir = Files.createTempDirectory("ioutils-copy-exists-");
		final Path target = tempDir.resolve("existing.bin");
		try {
			Files.write(target, "original".getBytes());
			final InputStream inputStream = new ByteArrayInputStream("new content".getBytes());
			IOUtils.copy(inputStream, target);
			// ensure the original file content was not modified/truncated
			final String content = new String(Files.readAllBytes(target));
			assertEquals("new content", content);
		} finally {
			Files.deleteIfExists(target);
			Files.deleteIfExists(tempDir);
		}
	}

	@Test
	@DisplayName("does not close the input stream")
	void shouldNotCloseInputStreamWhenCopyingToPath() throws Exception {
		final Path tempDir = Files.createTempDirectory("ioutils-copy-open-");
		final Path target = tempDir.resolve("open.bin");
		final TrackingInputStream inputStream = new TrackingInputStream("abc".getBytes());
		try {
			IOUtils.copy(inputStream, target);
			assertEquals(false, inputStream.isClosed());
		} finally {
			inputStream.close();
			Files.deleteIfExists(target);
			Files.deleteIfExists(tempDir);
		}
	}

	private static final class TrackingInputStream extends InputStream {
		private final InputStream delegate;
		private boolean closed;

		private TrackingInputStream(@Nonnull byte[] data) {
			this.delegate = new ByteArrayInputStream(data);
			this.closed = false;
		}

		@Override
		public int read() throws IOException {
			return this.delegate.read();
		}


		@Override
		public int read(@Nonnull byte[] b, int off, int len) throws IOException {
			return this.delegate.read(b, off, len);
		}

		@Override
		public void close() throws IOException {
			this.closed = true;
			this.delegate.close();
		}

		private boolean isClosed() {
			return this.closed;
		}
	}
}
