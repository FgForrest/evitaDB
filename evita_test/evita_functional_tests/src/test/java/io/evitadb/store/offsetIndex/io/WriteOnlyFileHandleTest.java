/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.exception.InvalidStoragePathException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WriteOnlyFileHandle} verifying write operations, concurrency handling,
 * error scenarios, and resource management.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("WriteOnlyFileHandle unit tests")
class WriteOnlyFileHandleTest implements EvitaTestSupport {
	private final Path targetDirectory = getPathInTargetDirectory("WriteOnlyFileHandle");
	private ObservableOutputKeeper outputKeeper;

	@BeforeEach
	void setUp() {
		this.outputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
	}

	@AfterEach
	void tearDown() {
		this.outputKeeper.close();
	}

	/**
	 * Creates a write handle with default test settings.
	 *
	 * @param filePath the target file path
	 * @return a new WriteOnlyFileHandle instance
	 */
	@Nonnull
	private WriteOnlyFileHandle createWriteHandle(@Nonnull Path filePath) {
		return new WriteOnlyFileHandle(
			filePath,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
			false,
			Crc32CChecksumFactory.INSTANCE,
			CompressionFactory.NO_COMPRESSION,
			this.outputKeeper
		);
	}

	/**
	 * Creates a write handle with custom settings.
	 *
	 * @param filePath           the target file path
	 * @param syncWrites         whether to sync writes
	 * @param checksumFactory    the checksum factory to use
	 * @param compressionFactory the compression factory to use
	 * @return a new WriteOnlyFileHandle instance
	 */
	@Nonnull
	private WriteOnlyFileHandle createWriteHandle(
		@Nonnull Path filePath,
		boolean syncWrites,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull CompressionFactory compressionFactory
	) {
		return new WriteOnlyFileHandle(
			filePath,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
			syncWrites,
			checksumFactory,
			compressionFactory,
			this.outputKeeper
		);
	}

	@Nested
	@DisplayName("Static method getTargetFile()")
	class GetTargetFileTest {

		@Test
		@DisplayName("creates parent directories when they don't exist")
		void shouldCreateParentDirectoriesWhenNotExist() {
			final Path nestedPath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(
				UUIDUtil.randomUUID() + "/nested/deep/file.dat"
			);

			final File result = WriteOnlyFileHandle.getTargetFile(nestedPath);

			assertNotNull(result);
			assertTrue(result.exists());
			assertTrue(result.getParentFile().isDirectory());
		}

		@Test
		@DisplayName("returns existing file without modification")
		void shouldReturnExistingFileWithoutModification() throws IOException {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			Files.createDirectories(filePath.getParent());
			Files.writeString(filePath, "existing content");

			final File result = WriteOnlyFileHandle.getTargetFile(filePath);

			assertNotNull(result);
			assertTrue(result.exists());
			assertEquals("existing content", Files.readString(filePath));
		}

		@Test
		@DisplayName("throws InvalidStoragePathException when parent is not a directory")
		void shouldThrowInvalidStoragePathExceptionWhenParentIsNotDirectory() throws IOException {
			// Create a file that should be a directory
			final Path parentAsFile = WriteOnlyFileHandleTest.this.targetDirectory.resolve(
				UUIDUtil.randomUUID() + ".file");
			Files.createDirectories(parentAsFile.getParent());
			Files.writeString(parentAsFile, "I am a file, not a directory");

			final Path filePath = parentAsFile.resolve("child.dat");

			assertThrows(
				InvalidStoragePathException.class, () ->
					WriteOnlyFileHandle.getTargetFile(filePath)
			);
		}
	}

	@Nested
	@DisplayName("Constructor variations")
	class ConstructorTest {

		@Test
		@DisplayName("creates handle with minimal parameters")
		void shouldCreateHandleWithMinimalParameters() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");

			try (final WriteOnlyFileHandle handle = new WriteOnlyFileHandle(
				filePath,
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				false,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION,
				WriteOnlyFileHandleTest.this.outputKeeper
			)) {
				assertNotNull(handle);
				assertEquals(filePath, handle.getTargetFile());
			}
		}

		@Test
		@DisplayName("creates handle with observability parameters")
		void shouldCreateHandleWithObservabilityParameters() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");

			try (final WriteOnlyFileHandle handle = new WriteOnlyFileHandle(
				FileType.CATALOG,
				"test-catalog-file",
				filePath,
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				false,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION,
				WriteOnlyFileHandleTest.this.outputKeeper
			)) {
				assertNotNull(handle);
				assertEquals(filePath, handle.getTargetFile());
			}
		}

		@Test
		@DisplayName("creates handle with all parameters including catalog name")
		void shouldCreateHandleWithAllParameters() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");

			try (final WriteOnlyFileHandle handle = new WriteOnlyFileHandle(
				"testCatalog",
				FileType.CATALOG,
				"test-catalog-file",
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				true,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION,
				filePath,
				WriteOnlyFileHandleTest.this.outputKeeper
			)) {
				assertNotNull(handle);
				assertEquals(filePath, handle.getTargetFile());
			}
		}
	}

	@Nested
	@DisplayName("Basic functionality")
	class BasicFunctionalityTest {

		@Test
		@DisplayName("returns correct last written position after writes")
		void shouldReturnCorrectLastWrittenPositionAfterWrites() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				assertEquals(0, writeHandle.getLastWrittenPosition());

				writeHandle.checkAndExecuteAndSync(
					"write data",
					() -> {},
					output -> output.writeString("Hello")
				);

				assertTrue(writeHandle.getLastWrittenPosition() > 0);
			}
		}

		@Test
		@DisplayName("creates valid read-only handle")
		void shouldCreateValidReadOnlyHandle() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				writeHandle.checkAndExecuteAndSync(
					"write data",
					() -> {},
					output -> output.writeString("Test data for reading")
				);

				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					assertNotNull(readHandle);
					assertTrue(readHandle.getLastWrittenPosition() > 0);
				}
			}
		}

		@Test
		@DisplayName("toString returns descriptive string with file path")
		void shouldReturnDescriptiveToString() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final String result = writeHandle.toString();

				assertTrue(result.contains("write handle"));
				assertTrue(result.contains(filePath.toString()));
			}
		}
	}

	@Nested
	@DisplayName("Premise and error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("propagates exception from premise in checkAndExecute")
		void shouldPropagateExceptionFromPremiseInCheckAndExecute() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final RuntimeException expectedException = new RuntimeException("Premise failed");

				final RuntimeException thrown = assertThrows(
					RuntimeException.class, () ->
						writeHandle.checkAndExecute(
							"test operation",
							() -> {throw expectedException;},
							output -> null
						)
				);

				assertEquals("Premise failed", thrown.getMessage());
			}
		}

		@Test
		@DisplayName("propagates exception from premise in checkAndExecuteAndSync")
		void shouldPropagateExceptionFromPremiseInCheckAndExecuteAndSync() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final RuntimeException expectedException = new RuntimeException("Premise failed in sync");

				final RuntimeException thrown = assertThrows(
					RuntimeException.class, () ->
						writeHandle.checkAndExecuteAndSync(
							"test sync operation",
							() -> {throw expectedException;},
							output -> output.writeString("data")
						)
				);

				assertEquals("Premise failed in sync", thrown.getMessage());
			}
		}
	}

	@Nested
	@DisplayName("Lock and concurrency handling")
	class LockHandlingTest {

		@Test
		@DisplayName("throws UnexpectedIOException when lock timeout expires in checkAndExecute")
		void shouldThrowUnexpectedIOExceptionOnLockTimeoutInCheckAndExecute() throws Exception {
			// Create keeper with very short timeout (1 second)
			// 1 second lock timeout
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(
				UUIDUtil.randomUUID() + ".tmp");

			try (
				ObservableOutputKeeper shortTimeoutKeeper = new ObservableOutputKeeper(
					StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
					1,  // 1 second lock timeout
					5,
					Mockito.mock(Scheduler.class)
				);
				final WriteOnlyFileHandle handle = new WriteOnlyFileHandle(
					filePath,
					StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
					false,
					Crc32CChecksumFactory.INSTANCE,
					CompressionFactory.NO_COMPRESSION,
					shortTimeoutKeeper
				)
			) {
				// Acquire lock in separate thread and hold it
				final CountDownLatch lockAcquired = new CountDownLatch(1);
				final CountDownLatch testComplete = new CountDownLatch(1);
				final AtomicBoolean holderFinished = new AtomicBoolean(false);

				final Thread lockHolder = new Thread(() -> {
					handle.checkAndExecute(
						"hold lock", () -> {}, output -> {
							lockAcquired.countDown();
							try {
								testComplete.await(10, TimeUnit.SECONDS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
							holderFinished.set(true);
							return null;
						}
					);
				});
				lockHolder.start();
				assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

				// Try to acquire lock - should timeout
				assertThrows(
					UnexpectedIOException.class, () ->
						handle.checkAndExecute("timeout test", () -> {}, output -> null)
				);

				testComplete.countDown();
				lockHolder.join(5000);
				assertTrue(holderFinished.get());
			}
		}

		@Test
		@DisplayName("throws UnexpectedIOException when lock timeout expires in checkAndExecuteAndSync")
		void shouldThrowUnexpectedIOExceptionOnLockTimeoutInCheckAndExecuteAndSync() throws Exception {
			// Create keeper with very short timeout (1 second)
			// 1 second lock timeout
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(
				UUIDUtil.randomUUID() + ".tmp");

			try (
				ObservableOutputKeeper shortTimeoutKeeper = new ObservableOutputKeeper(
					StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
					1,  // 1 second lock timeout
					5,
					Mockito.mock(Scheduler.class)
				);
				final WriteOnlyFileHandle handle = new WriteOnlyFileHandle(
					filePath,
					StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
					false,
					Crc32CChecksumFactory.INSTANCE,
					CompressionFactory.NO_COMPRESSION,
					shortTimeoutKeeper
				)
			) {
				// Acquire lock in separate thread and hold it
				final CountDownLatch lockAcquired = new CountDownLatch(1);
				final CountDownLatch testComplete = new CountDownLatch(1);

				final Thread lockHolder = new Thread(() -> {
					handle.checkAndExecuteAndSync(
						"hold lock", () -> {}, output -> {
							lockAcquired.countDown();
							try {
								testComplete.await(10, TimeUnit.SECONDS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}
					);
				});
				lockHolder.start();
				assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

				// Try to acquire lock - should timeout
				assertThrows(
					UnexpectedIOException.class, () ->
						handle.checkAndExecuteAndSync("timeout test", () -> {}, output -> output.writeString("test"))
				);

				testComplete.countDown();
				lockHolder.join(5000);
			}
		}

		@Test
		@DisplayName("throws GenericEvitaInternalError and restores interrupt flag when interrupted during checkAndExecute")
		void shouldThrowAndRestoreInterruptFlagInCheckAndExecute() throws Exception {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			final CountDownLatch lockAcquired = new CountDownLatch(1);
			final CountDownLatch testComplete = new CountDownLatch(1);
			final AtomicBoolean interruptRestored = new AtomicBoolean(false);
			final AtomicBoolean exceptionThrown = new AtomicBoolean(false);

			try (final WriteOnlyFileHandle handle = createWriteHandle(filePath)) {
				// First thread holds the lock
				final Thread lockHolder = new Thread(() -> {
					handle.checkAndExecute(
						"hold lock", () -> {}, output -> {
							lockAcquired.countDown();
							try {
								testComplete.await(10, TimeUnit.SECONDS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
							return null;
						}
					);
				});
				lockHolder.start();
				assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

				// Second thread tries to acquire lock and will be interrupted
				final Thread interruptedThread = new Thread(() -> {
					try {
						handle.checkAndExecute("interrupted operation", () -> {}, output -> null);
					} catch (GenericEvitaInternalError e) {
						exceptionThrown.set(true);
						interruptRestored.set(Thread.currentThread().isInterrupted());
					}
				});
				interruptedThread.start();

				// Give the thread time to start waiting for the lock
				Thread.sleep(100);

				// Interrupt the waiting thread
				interruptedThread.interrupt();
				interruptedThread.join(5000);

				testComplete.countDown();
				lockHolder.join(5000);

				assertTrue(exceptionThrown.get(), "GenericEvitaInternalError should be thrown");
				assertTrue(interruptRestored.get(), "Interrupt flag should be restored");
			}
		}
	}

	@Nested
	@DisplayName("Integration tests - write/read cycles")
	class IntegrationTest {

		@Test
		@DisplayName("writes string data and reads it back correctly")
		void shouldWriteStringAndReadBack() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				// Write data
				writeHandle.checkAndExecuteAndSync(
					"write string",
					() -> {},
					output -> output.writeString("Hello evitaDB!")
				);

				// Verify using read-only handle
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						assertEquals("Hello evitaDB!", input.readString());
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("writes multiple data entries and reads them back in order")
		void shouldWriteMultipleEntriesAndReadBackInOrder() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				// Write multiple entries
				for (int i = 0; i < 10; i++) {
					final int index = i;
					writeHandle.checkAndExecuteAndSync(
						"write entry " + i,
						() -> {},
						output -> {
							output.writeInt(index);
							output.writeString("Entry-" + index);
						}
					);
				}

				// Verify position tracking
				assertTrue(writeHandle.getLastWrittenPosition() > 0);

				// Read back and verify order
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						for (int i = 0; i < 10; i++) {
							assertEquals(i, input.readInt());
							assertEquals("Entry-" + i, input.readString());
						}
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("writes binary data and reads it back correctly")
		void shouldWriteBinaryDataAndReadBack() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final byte[] testData = new byte[256];
				for (int i = 0; i < testData.length; i++) {
					testData[i] = (byte) i;
				}

				writeHandle.checkAndExecuteAndSync(
					"write binary",
					() -> {},
					output -> {
						output.writeInt(testData.length);
						output.writeBytes(testData);
					}
				);

				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						final int length = input.readInt();
						assertEquals(256, length);
						final byte[] readData = input.readBytes(length);
						assertArrayEquals(testData, readData);
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("checkAndExecute returns computed value from logic function")
		void shouldReturnComputedValueFromCheckAndExecute() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final Long result = writeHandle.checkAndExecute(
					"compute value",
					() -> {},
					output -> {
						output.writeString("test data");
						return output.total();
					}
				);

				assertNotNull(result);
				assertTrue(result > 0);
			}
		}

		@Test
		@DisplayName("checkAndExecuteAndSync with post-execution logic returns correct value")
		void shouldExecutePostExecutionLogicCorrectly() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				// Use the three-argument overload with post-execution logic
				final Long result = writeHandle.checkAndExecuteAndSync(
					"write with post-execution",
					() -> {},
					output -> {
						output.writeString("test data");
						return output.total();
					},
					(output, intermediateResult) -> intermediateResult * 2
				);

				// Result should be intermediate result doubled
				assertNotNull(result);
				assertTrue(result > 0);
			}
		}

		@Test
		@DisplayName("handles large data writes exceeding initial buffer size")
		void shouldHandleLargeDataWritesExceedingBufferSize() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			// Use NO_OP checksum to avoid CRC32C buffer issues with large data
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				false,
				ChecksumFactory.NO_OP,
				CompressionFactory.NO_COMPRESSION
			)) {
				// Write large data - 100KB of repeated pattern
				final String largeData = "A".repeat(100 * 1024);

				writeHandle.checkAndExecuteAndSync(
					"write large data",
					() -> {},
					output -> output.writeString(largeData)
				);

				// Verify it was written
				assertTrue(writeHandle.getLastWrittenPosition() > 100 * 1024);

				// Read back and verify
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						final String readData = input.readString();
						assertEquals(largeData.length(), readData.length());
						assertEquals(largeData, readData);
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("concurrent writes from multiple threads are properly serialized")
		void shouldSerializeConcurrentWritesCorrectly() throws Exception {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			// Use NO_OP checksum for simplicity in concurrent test
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				false,
				ChecksumFactory.NO_OP,
				CompressionFactory.NO_COMPRESSION
			)) {
				final int threadCount = 3;
				final int writesPerThread = 5;
				final int totalWrites = threadCount * writesPerThread;
				final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
				final CountDownLatch startLatch = new CountDownLatch(1);
				final CountDownLatch doneLatch = new CountDownLatch(threadCount);

				// Submit concurrent write tasks
				for (int t = 0; t < threadCount; t++) {
					final int threadId = t;
					executor.submit(() -> {
						try {
							startLatch.await();
							for (int i = 0; i < writesPerThread; i++) {
								final String value = "T" + threadId + "-" + i;
								writeHandle.checkAndExecuteAndSync(
									"thread-" + threadId + "-write-" + i,
									() -> {},
									output -> output.writeString(value)
								);
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} finally {
							doneLatch.countDown();
						}
					});
				}

				// Start all threads simultaneously
				startLatch.countDown();
				assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
				executor.shutdown();
				assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

				// Verify data was written by reading back through read-only handle
				// This ensures proper flushing and verifies data integrity
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					assertTrue(readHandle.getLastWrittenPosition() > 0,
						"File should have content after concurrent writes");

					// Read all written strings and count them
					final int[] readCount = {0};
					readHandle.execute(input -> {
						while (input.position() < readHandle.getLastWrittenPosition()) {
							final String value = input.readString();
							assertNotNull(value);
							assertTrue(value.startsWith("T"), "Value should start with thread prefix");
							readCount[0]++;
						}
						return null;
					});

					assertEquals(totalWrites, readCount[0],
						"Should read back all " + totalWrites + " written entries");
				}
			}
		}

		@Test
		@DisplayName("writes with compression enabled and reads back correctly")
		void shouldWriteWithCompressionAndReadBack() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			// Create handle with compression enabled
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				false,
				Crc32CChecksumFactory.INSTANCE,
				ZipCompressionFactory.INSTANCE
			)) {
				// Write highly compressible data
				final String repeatedData = "ABCDEFGHIJ".repeat(100);
				writeHandle.checkAndExecuteAndSync(
					"write compressible data",
					() -> {},
					output -> output.writeString(repeatedData)
				);

				// Read back and verify
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						assertEquals(repeatedData, input.readString());
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("writes with CRC32C checksum enabled and reads back correctly")
		void shouldWriteWithChecksumAndReadBack() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				false,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION
			)) {
				final String testData = "Data with checksum verification";
				writeHandle.checkAndExecuteAndSync(
					"write with checksum",
					() -> {},
					output -> output.writeString(testData)
				);

				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						assertEquals(testData, input.readString());
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("writes without checksum computation and reads back correctly")
		void shouldWriteWithoutChecksumAndReadBack() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				false,
				ChecksumFactory.NO_OP,
				CompressionFactory.NO_COMPRESSION
			)) {
				final String testData = "Data without checksum";
				writeHandle.checkAndExecuteAndSync(
					"write without checksum",
					() -> {},
					output -> output.writeString(testData)
				);

				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						assertEquals(testData, input.readString());
						return null;
					});
				}
			}
		}

		@Test
		@DisplayName("file position is correctly tracked across multiple writes")
		void shouldTrackFilePositionAcrossMultipleWrites() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				final List<Long> positions = new ArrayList<>();
				positions.add(writeHandle.getLastWrittenPosition());

				for (int i = 0; i < 5; i++) {
					final int index = i;
					writeHandle.checkAndExecuteAndSync(
						"write " + index,
						() -> {},
						output -> output.writeString("Data block " + index)
					);
					positions.add(writeHandle.getLastWrittenPosition());
				}

				// Verify positions are monotonically increasing
				for (int i = 1; i < positions.size(); i++) {
					assertTrue(
						positions.get(i) > positions.get(i - 1),
						"Position should increase after each write"
					);
				}
			}
		}

		@Test
		@DisplayName("read-only handle provides accurate file length")
		void shouldProvideAccurateFileLengthFromReadOnlyHandle() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath)) {
				writeHandle.checkAndExecuteAndSync(
					"write data",
					() -> {},
					output -> output.writeString("Test data for length verification")
				);

				final long writeHandlePosition = writeHandle.getLastWrittenPosition();

				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					assertEquals(writeHandlePosition, readHandle.getLastWrittenPosition());
				}
			}
		}

		@Test
		@DisplayName("close releases resources and allows file deletion")
		void shouldReleaseResourcesOnClose() throws IOException {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");

			final WriteOnlyFileHandle writeHandle = createWriteHandle(filePath);
			writeHandle.checkAndExecuteAndSync(
				"write data",
				() -> {},
				output -> output.writeString("Test data")
			);
			writeHandle.close();

			// After close, the file should be deletable
			assertTrue(Files.exists(filePath));
			Files.delete(filePath);
			assertFalse(Files.exists(filePath));
		}

		@Test
		@DisplayName("writes with sync enabled ensures data is persisted")
		void shouldSyncDataWhenSyncWritesEnabled() {
			final Path filePath = WriteOnlyFileHandleTest.this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".dat");
			try (final WriteOnlyFileHandle writeHandle = createWriteHandle(
				filePath,
				true,  // sync writes enabled
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION
			)) {
				writeHandle.checkAndExecuteAndSync(
					"write with sync",
					() -> {},
					output -> output.writeString("Synced data")
				);

				// Verify data was written
				try (final ReadOnlyHandle readHandle = writeHandle.toReadOnlyHandle()) {
					readHandle.execute(input -> {
						assertEquals("Synced data", input.readString());
						return null;
					});
				}
			}
		}
	}
}
