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
import org.junit.jupiter.api.Nested;
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

	@Nested
	@DisplayName("Copy operations tests")
	class CopyOperationsTests {

		@Test
		@DisplayName("Should copy all bytes when streams are valid")
		void shouldCopyAllBytesWhenStreamsAreValid() {
			InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			byte[] buffer = new byte[4];

			long bytesCopied = IOUtils.copy(inputStream, outputStream, buffer);

			assertEquals(9, bytesCopied);
			assertEquals("test data", outputStream.toString());
		}

		@Test
		@DisplayName("Should copy specified bytes when streams are valid")
		void shouldCopySpecifiedBytesWhenStreamsAreValid() {
			InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			byte[] buffer = new byte[4];

			long bytesCopied = IOUtils.copy(inputStream, outputStream, 9, buffer);

			assertEquals(9, bytesCopied);
			assertEquals("test data", outputStream.toString());
		}

		@Test
		@DisplayName("Should copy partial bytes when streams are valid")
		void shouldCopyPartialBytesWhenStreamsAreValid() {
			InputStream inputStream = new ByteArrayInputStream("test data".getBytes());
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			byte[] buffer = new byte[4];

			long bytesCopied = IOUtils.copy(inputStream, outputStream, 3, buffer);

			assertEquals(3, bytesCopied);
			assertEquals("tes", outputStream.toString());
		}

		@Test
		@DisplayName("Should copy input stream to new file when file does not exist")
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
		@DisplayName("Should overwrite contents when target already exists")
		void shouldOverWriteContentsWhenTargetAlreadyExists() throws Exception {
			final Path tempDir = Files.createTempDirectory("ioutils-copy-exists-");
			final Path target = tempDir.resolve("existing.bin");
			try {
				Files.write(target, "original".getBytes());
				final InputStream inputStream = new ByteArrayInputStream("new content".getBytes());
				IOUtils.copy(inputStream, target);
				final String content = new String(Files.readAllBytes(target));
				assertEquals("new content", content);
			} finally {
				Files.deleteIfExists(target);
				Files.deleteIfExists(tempDir);
			}
		}

		@Test
		@DisplayName("Should not close input stream when copying to path")
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
	}

	@Nested
	@DisplayName("Execute safely tests")
	class ExecuteSafelyTests {

		@Test
		@DisplayName("Should execute lambda without exception")
		void shouldExecuteLambdaWithoutException() {
			IOUtils.executeSafely(
				IllegalStateException::new,
				() -> System.out.println("Lambda executed successfully.")
			);
		}

		@Test
		@DisplayName("Should wrap exception thrown by lambda")
		void shouldWrapExceptionThrownByLambda() {
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
		@DisplayName("Should execute consumer without exception")
		void shouldExecuteConsumerWithoutException() {
			IOUtils.executeSafely(
				1,
				IllegalStateException::new,
				i -> System.out.println("Lambda `" + i + "` executed successfully.")
			);
		}

		@Test
		@DisplayName("Should wrap exception thrown by consumer")
		void shouldWrapExceptionThrownByConsumer() {
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
	}

	@Nested
	@DisplayName("Close operations tests")
	class CloseOperationsTests {

		@Test
		@DisplayName("Should close resources without exception")
		void shouldCloseResourcesWithoutException() {
			IOUtils.close(
				IllegalStateException::new,
				() -> System.out.println("First resource closed successfully."),
				() -> System.out.println("Second resource closed successfully.")
			);
		}

		@Test
		@DisplayName("Should aggregate single exception on close")
		void shouldAggregateSingleExceptionOnClose() {
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
		@DisplayName("Should aggregate multiple exceptions on close")
		void shouldAggregateMultipleExceptionsOnClose() {
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
		@DisplayName("Should close runnable resources without exception")
		void shouldCloseRunnableResourcesWithoutException() {
			IOUtils.close(
				IllegalStateException::new,
				() -> System.out.println("First resource closed successfully."),
				() -> System.out.println("Second resource closed successfully.")
			);
		}

		@Test
		@DisplayName("Should aggregate single throwable on safe close")
		void shouldAggregateSingleThrowableOnSafeClose() {
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
		@DisplayName("Should aggregate multiple throwables on safe close")
		void shouldAggregateMultipleThrowablesOnSafeClose() {
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
		@DisplayName("Should log and suppress IOException on quiet close")
		void shouldLogAndSuppressIOExceptionOnQuietClose() {
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
		@DisplayName("Should log and suppress RuntimeException on safe close")
		void shouldLogAndSuppressRuntimeExceptionOnSafeClose() {
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
		@DisplayName("Should log and suppress Error on safe close")
		void shouldLogAndSuppressErrorOnSafeClose() {
			IOUtils.closeSafely(
				() -> System.out.println("First resource closed successfully."),
				() -> {
					throw new Error("This error should be caught and logged");
				},
				() -> System.out.println("Third resource closed successfully.")
			);
			// No assertion needed as the method should not throw any exception
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

		/**
		 * Returns whether this input stream has been closed.
		 *
		 * @return true if the stream has been closed, false otherwise
		 */
		private boolean isClosed() {
			return this.closed;
		}
	}
}
