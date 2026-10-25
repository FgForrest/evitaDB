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
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Crc32CWrapper;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * This test verifies {@link WriteOnlyOffHeapWithFileBackupHandle} behaviour, including data writing
 * to off-heap memory, automatic offloading to file backup when memory is exhausted, and CRC32
 * checksum calculation consistency across different storage scenarios.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("WriteOnlyOffHeapWithFileBackupHandle functionality")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class WriteOnlyOffHeapWithFileBackupHandleTest implements EvitaTestSupport {
	private final Path targetDirectory = getPathInTargetDirectory("WriteOnlyOffHeapWithFileBackupHandle");
	private final ObservableOutputKeeper outputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);

	@AfterEach
	void tearDown() {
		this.outputKeeper.close();
	}

	@Test
	@DisplayName("Should write small data to off-heap memory chunk")
	void shouldWriteDataToOffHeapChunk() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			writeHandle.checkAndExecuteAndSync(
				"write some data",
				() -> {
				},
				output -> output.writeString("Small data content")
			);

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(18, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						assertEquals("Small data content", input.readString());
						return null;
					}
				);
			}
		}
	}

	@Test
	@DisplayName("Should automatically switch from off-heap to file when memory exhausted (with sync)")
	void shouldWriteLargeDataFirstToOffHeapChunkThatAutomaticallySwitchesToTemporaryFileWithSync() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			for (int i = 0; i < 5; i++) {
				final int index = i;
				writeHandle.checkAndExecuteAndSync(
					"write some data",
					() -> {
					},
					output -> output.writeString("Data " + index + ".")
				);
			}

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(35, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						for (int i = 0; i < 5; i++) {
							assertEquals("Data " + i + ".", input.readString());
						}
						return null;
					}
				);
			}
		}
	}

	@Test
	@DisplayName("Should automatically switch from off-heap to file when memory exhausted (without sync)")
	void shouldWriteLargeDataFirstToOffHeapChunkThatAutomaticallySwitchesToTemporaryFileWithoutSync() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			for (int i = 0; i < 5; i++) {
				final int index = i;
				writeHandle.checkAndExecute(
					"write some data",
					() -> {
					},
					output -> {
						output.writeString("Data " + index + ".");
						return null;
					}
				);
			}

			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(35, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						for (int i = 0; i < 5; i++) {
							assertEquals("Data " + i + ".", input.readString());
						}
						return null;
					}
				);
			}
		}
	}

	@Test
	@DisplayName("Should start directly with file backup when no free memory region is available")
	void shouldStartDirectlyWithFileBackupIfThereIsNoFreeMemoryRegionAvailable() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle realMemoryHandle = createWriteHandle(memoryManager)
		) {
			// write at least one byte to force the memory manager to allocate the memory region
			realMemoryHandle.checkAndExecuteAndSync(
				"write some data",
				() -> {
				},
				output -> output.writeByte((byte) 0)
			);
			// because there is only one region available, this forces the handle to use file backup immediately
			try (
				final WriteOnlyOffHeapWithFileBackupHandle forcedFileHandle = createWriteHandle(memoryManager)
			) {
				for (int i = 0; i < 5; i++) {
					final int index = i;
					forcedFileHandle.checkAndExecuteAndSync(
						"write some data",
						() -> {
						},
						output -> output.writeString("Data " + index + ".")
					);
				}

				try (final ReadOnlyHandle readOnlyHandle = forcedFileHandle.toReadOnlyHandle()) {
					assertEquals(35, readOnlyHandle.getLastWrittenPosition());
					readOnlyHandle.execute(
						input -> {
							for (int i = 0; i < 5; i++) {
								assertEquals("Data " + i + ".", input.readString());
							}
							return null;
						}
					);
				}
			}
		}
	}

	@Test
	@DisplayName("Should calculate correct CRC32 checksum when writing directly to file")
	void shouldCalculateCorrectChecksumWhenWritingDirectlyToFile() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, Crc32CChecksumFactory.INSTANCE);
			// Exhaust the only available region
			final WriteOnlyOffHeapWithFileBackupHandle dummyHandle = createWriteHandle(memoryManager)
		) {
			// Write to dummy handle to exhaust the region
			dummyHandle.checkAndExecuteAndSync("exhaust", () -> {}, output -> output.writeByte((byte) 0));

			// This handle will be forced to use file directly
			try (final WriteOnlyOffHeapWithFileBackupHandle fileHandle = createWriteHandle(memoryManager)) {
				// Write known data
				final byte[] testData1 = "Hello, World!".getBytes(StandardCharsets.UTF_8);
				final byte[] testData2 = "Test data for CRC32".getBytes(StandardCharsets.UTF_8);

				fileHandle.checkAndExecuteAndSync("write1", () -> {}, output -> output.writeBytes(testData1));
				fileHandle.checkAndExecuteAndSync("write2", () -> {}, output -> output.writeBytes(testData2));

				// Get checksum from reference
				final OffHeapWithFileBackupReference reference = fileHandle.toReadOffHeapWithFileBackupReference();
				final long actualChecksum = reference.getChecksum();

				// Compute expected checksum manually
				final Crc32CWrapper wrapper = new Crc32CWrapper();
				wrapper.withByteArray(testData1).withByteArray(testData2);
				final long expectedChecksum = wrapper.getValue();

				assertEquals(expectedChecksum, actualChecksum,
					"Checksum for file-only writes should match manual calculation");
			}
		}
	}

	@Test
	@DisplayName("Should calculate consistent CRC32 checksum when offloading from off-heap to file")
	void shouldCalculateConsistentChecksumWhenOffloadingFromOffHeapToFile() throws IOException {
		try (
			// Small region (32 bytes) to force offload
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, Crc32CChecksumFactory.INSTANCE);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			// Write data chunks that will trigger offload
			for (int i = 0; i < 5; i++) {
				final byte[] chunk = ("Data chunk " + i + ".").getBytes(StandardCharsets.UTF_8);
				writeHandle.checkAndExecuteAndSync(
					"write " + i, () -> {}, output -> output.writeBytes(chunk)
				);
			}

			// Get reference with checksum
			final OffHeapWithFileBackupReference reference = writeHandle.toReadOffHeapWithFileBackupReference();
			final long actualChecksum = reference.getChecksum();

			// Read actual file contents and calculate expected checksum
			final Path filePath = reference.getFilePath().orElseThrow();
			final byte[] fileContents = Files.readAllBytes(filePath);

			final Crc32CWrapper wrapper = new Crc32CWrapper();
			wrapper.withByteArray(fileContents);
			final long expectedChecksum = wrapper.getValue();

			assertEquals(expectedChecksum, actualChecksum,
				"Checksum should equal CRC32C of actual file contents");
		}
	}

	@Test
	@DisplayName("Should calculate correct CRC32 checksum when writing only to off-heap memory")
	void shouldCalculateCorrectChecksumWhenWritingOnlyToOffHeap() {
		try (
			// Large region (1KB) to ensure data fits
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1024, 1, Crc32CChecksumFactory.INSTANCE);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			// Write small data that fits in off-heap
			final byte[] testData = "Small data".getBytes(StandardCharsets.UTF_8);

			writeHandle.checkAndExecuteAndSync(
				"write", () -> {}, output -> output.writeBytes(testData)
			);

			// Get checksum from reference
			final OffHeapWithFileBackupReference reference = writeHandle.toReadOffHeapWithFileBackupReference();
			final long actualChecksum = reference.getChecksum();

			// Compute expected checksum manually
			final Crc32CWrapper wrapper = new Crc32CWrapper();
			wrapper.withByteArray(testData);
			final long expectedChecksum = wrapper.getValue();

			assertEquals(expectedChecksum, actualChecksum,
				"Checksum for off-heap-only writes should match manual calculation");
		}
	}

	@Test
	@DisplayName("Should create independent detached outputs for consecutive transactions")
	void shouldCreateDetachedOutputsAcrossTransactions() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1024, 2, ChecksumFactory.NO_OP)
		) {

			final ObservableOutput<?> instanceFromA;
			try (final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager)) {
				handleA.checkAndExecuteAndSync(
					"write", () -> {}, output -> output.writeString("Transaction A data")
				);
				instanceFromA = getOffHeapMemoryOutput(handleA);
				assertNotNull(instanceFromA, "Handle A should have created an off-heap output");
				assertEquals(0, instanceFromA.getBuffer().length);

				handleA.toReadOffHeapWithFileBackupReference().close();
			}

			try (final WriteOnlyOffHeapWithFileBackupHandle handleB = createWriteHandle(memoryManager)) {
				handleB.checkAndExecuteAndSync(
					"write", () -> {}, output -> output.writeString("Transaction B data")
				);
				assertNotSame(
					instanceFromA, getOffHeapMemoryOutput(handleB),
					"Each transaction creates its own cheap output"
				);

				try (final ReadOnlyHandle readOnlyHandle = handleB.toReadOnlyHandle()) {
					readOnlyHandle.execute(
						input -> {
							assertEquals("Transaction B data", input.readString());
							return null;
						}
					);
				}
			}
		}
	}

	@Test
	@DisplayName("Should isolate cumulative checksums between consecutive transactions")
	void shouldIsolateChecksumsAcrossTransactions() throws NoSuchFieldException, IllegalAccessException {
		try (

			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1024, 2, Crc32CChecksumFactory.INSTANCE)
		) {
			final byte[] bytesA = "Transaction A payload".getBytes(StandardCharsets.UTF_8);
			final byte[] bytesB = "Different B payload!!".getBytes(StandardCharsets.UTF_8);

			final ObservableOutput<?> instanceFromA;
			try (final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager)) {
				handleA.checkAndExecuteAndSync("write A", () -> {}, output -> output.writeBytes(bytesA));
				instanceFromA = getOffHeapMemoryOutput(handleA);

				handleA.toReadOffHeapWithFileBackupReference().close();
			}

			try (final WriteOnlyOffHeapWithFileBackupHandle handleB = createWriteHandle(memoryManager)) {
				handleB.checkAndExecuteAndSync("write B", () -> {}, output -> output.writeBytes(bytesB));
				assertNotSame(
					instanceFromA, getOffHeapMemoryOutput(handleB),
					"Each transaction must have independent checksum state"
				);

				final OffHeapWithFileBackupReference reference = handleB.toReadOffHeapWithFileBackupReference();
				final long expectedChecksum = new Crc32CWrapper().withByteArray(bytesB).getValue();
				assertEquals(
					expectedChecksum, reference.getChecksum(),
					"B's checksum must cover only B's bytes - A's cumulative checksum must not leak into B"
				);
			}
		}
	}

	@Test
	@DisplayName("Should preserve an open reference while another transaction reuses frame buffers")
	void shouldNotCorruptOpenReferenceWhenAnotherTransactionWrites() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1023, 3, ChecksumFactory.NO_OP)
		) {

			try (final WriteOnlyOffHeapWithFileBackupHandle warmUpHandle = createWriteHandle(memoryManager)) {
				warmUpHandle.checkAndExecuteAndSync("write", () -> {}, output -> output.writeString("Warm-up data"));
				warmUpHandle.toReadOffHeapWithFileBackupReference().close();
			}

			final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager);
			handleA.checkAndExecuteAndSync("write", () -> {}, output -> output.writeString("Transaction A data"));
			final OffHeapWithFileBackupReference referenceA = handleA.toReadOffHeapWithFileBackupReference();

			final ByteBuffer bufferA = referenceA.getBuffer().orElseThrow();
			final byte[] snapshotBeforeB = snapshot(bufferA, referenceA.getContentLength());

			// run a second, independent transaction to completion while A's reference is still open
			try (final WriteOnlyOffHeapWithFileBackupHandle handleB = createWriteHandle(memoryManager)) {
				handleB.checkAndExecuteAndSync("write", () -> {}, output -> output.writeString("Transaction B data"));
				try (final ReadOnlyHandle readOnlyHandle = handleB.toReadOnlyHandle()) {
					readOnlyHandle.execute(
						input -> {
							assertEquals("Transaction B data", input.readString());
							return null;
						}
					);
				}
			}

			final byte[] snapshotAfterB = snapshot(bufferA, referenceA.getContentLength());
			assertArrayEquals(
				snapshotBeforeB, snapshotAfterB,
				"A's off-heap region bytes must be unaffected by B's independent frame buffer reuse"
			);

			assertDoesNotThrow(referenceA::close);
			assertDoesNotThrow(handleA::close);
		}
	}

	@Test
	@DisplayName("Should detach the off-heap output when overflow switches to file backup")
	void shouldDetachOffHeapOutputOnOverflow() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			final ObservableOutput<?> original = writeHandle.checkAndExecute(
				"initialize", () -> {}, output -> output
			);
			for (int i = 0; i < 5; i++) {
				final int index = i;
				writeHandle.checkAndExecuteAndSync(
					"write some data", () -> {}, output -> output.writeString("Data " + index + ".")
				);
			}

			// The small region overflowed; retrying against the file must release the abandoned frame buffer.
			assertEquals(0, original.getBuffer().length, "The abandoned off-heap output must return its array");
			assertNull(getOffHeapMemoryOutput(writeHandle));
			try (final ReadOnlyHandle readOnlyHandle = writeHandle.toReadOnlyHandle()) {
				assertEquals(35, readOnlyHandle.getLastWrittenPosition());
				readOnlyHandle.execute(
					input -> {
						for (int i = 0; i < 5; i++) {
							assertEquals("Data " + i + ".", input.readString());
						}
						return null;
					}
				);
			}
		}
	}

	/**
	 * Copies {@code length} bytes starting at position 0 of {@code buffer} without disturbing its position/limit.
	 */
	@Nonnull
	private static byte[] snapshot(@Nonnull ByteBuffer buffer, int length) {
		final byte[] result = new byte[length];
		final ByteBuffer duplicate = buffer.duplicate();
		duplicate.position(0);
		duplicate.get(result, 0, length);
		return result;
	}

	/**
	 * Reflects into the handle's private off-heap output field for pool-identity assertions.
	 */
	@Nonnull
	private static ObservableOutput<?> getOffHeapMemoryOutput(
		@Nonnull WriteOnlyOffHeapWithFileBackupHandle handle
	) throws NoSuchFieldException, IllegalAccessException {
		final Field field = WriteOnlyOffHeapWithFileBackupHandle.class.getDeclaredField("offHeapMemoryOutput");
		field.setAccessible(true);
		return (ObservableOutput<?>) field.get(handle);
	}

	/**
	 * Creates a new {@link WriteOnlyOffHeapWithFileBackupHandle} instance for testing with default settings.
	 * The handle writes to a randomly named temporary file in the target directory with CRC32C checksum
	 * enabled and no compression.
	 *
	 * @param memoryManager the off-heap memory manager to use for allocating memory regions
	 * @return a new write handle instance configured for testing
	 */
	@Nonnull
	private WriteOnlyOffHeapWithFileBackupHandle createWriteHandle(@Nonnull CatalogOffHeapMemoryManager memoryManager) {
		return new WriteOnlyOffHeapWithFileBackupHandle(
			this.targetDirectory.resolve(UUIDUtil.randomUUID() + ".tmp"),
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
			false,
			this.outputKeeper,
			memoryManager,
			Crc32CChecksumFactory.INSTANCE,
			CompressionFactory.NO_COMPRESSION
		);
	}

}
