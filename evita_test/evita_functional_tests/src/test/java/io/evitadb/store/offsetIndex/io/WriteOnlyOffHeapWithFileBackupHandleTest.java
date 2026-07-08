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
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
	@DisplayName("Should recycle the off-heap ObservableOutput instance for reuse by the next transaction")
	void shouldRecycleObservableOutputInstanceAcrossTransactions() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1024, 2, ChecksumFactory.NO_OP)
		) {
			assertEquals(0, getFreeOffHeapOutputCount());

			final ObservableOutput<?> instanceFromA;
			try (final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager)) {
				handleA.checkAndExecuteAndSync(
					"write", () -> {}, output -> output.writeString("Transaction A data")
				);
				instanceFromA = getOffHeapMemoryOutput(handleA);
				assertNotNull(instanceFromA, "Handle A should have borrowed an off-heap output");

				// closing the reference (not the handle) is what triggers the recycle
				handleA.toReadOffHeapWithFileBackupReference().close();
			}

			assertEquals(1, getFreeOffHeapOutputCount(), "A's released instance must sit in the free-list");

			try (final WriteOnlyOffHeapWithFileBackupHandle handleB = createWriteHandle(memoryManager)) {
				handleB.checkAndExecuteAndSync(
					"write", () -> {}, output -> output.writeString("Transaction B data")
				);
				assertSame(
					instanceFromA, getOffHeapMemoryOutput(handleB),
					"B must have reused the instance recycled by A instead of allocating a new one"
				);
				assertEquals(0, getFreeOffHeapOutputCount(), "B's borrow must have drained the free-list");

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
	@DisplayName("Should re-arm the cumulative checksum when a recycled output is reused by the next transaction")
	void shouldReArmChecksumWhenReusingRecycledOutputForNextTransaction() throws NoSuchFieldException, IllegalAccessException {
		try (
			// large region so each payload fits off-heap; two regions so B can reuse A's recycled instance
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1024, 2, Crc32CChecksumFactory.INSTANCE)
		) {
			final byte[] bytesA = "Transaction A payload".getBytes(StandardCharsets.UTF_8);
			final byte[] bytesB = "Different B payload!!".getBytes(StandardCharsets.UTF_8);

			// transaction A writes known bytes so its cumulative checksum becomes non-zero, then recycles
			final ObservableOutput<?> instanceFromA;
			try (final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager)) {
				handleA.checkAndExecuteAndSync("write A", () -> {}, output -> output.writeBytes(bytesA));
				instanceFromA = getOffHeapMemoryOutput(handleA);
				// closing the reference (not the handle) recycles A's instance back to the free-list
				handleA.toReadOffHeapWithFileBackupReference().close();
			}
			assertEquals(1, getFreeOffHeapOutputCount(), "A's instance must have been recycled");

			// transaction B reuses A's recycled instance and writes different bytes
			try (final WriteOnlyOffHeapWithFileBackupHandle handleB = createWriteHandle(memoryManager)) {
				handleB.checkAndExecuteAndSync("write B", () -> {}, output -> output.writeBytes(bytesB));
				assertSame(
					instanceFromA, getOffHeapMemoryOutput(handleB),
					"B must have reused A's recycled instance for this scenario to be meaningful"
				);

				final OffHeapWithFileBackupReference reference = handleB.toReadOffHeapWithFileBackupReference();
				final long expectedChecksum = new Crc32CWrapper().withByteArray(bytesB).getValue();
				assertEquals(
					expectedChecksum, reference.getChecksum(),
					"B's checksum must cover only B's bytes - A's cumulative checksum must not leak through the recycled instance"
				);
			}
		}
	}

	@Test
	@DisplayName("Should not corrupt an open reference's off-heap region while another transaction recycles/reuses an ObservableOutput")
	void shouldNotCorruptOpenReferenceWhenAnotherTransactionRecyclesAndReuses() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 1023, 3, ChecksumFactory.NO_OP)
		) {
			// prime the free-list so that handle A below is likely to borrow a recycled instance
			try (final WriteOnlyOffHeapWithFileBackupHandle warmUpHandle = createWriteHandle(memoryManager)) {
				warmUpHandle.checkAndExecuteAndSync("write", () -> {}, output -> output.writeString("Warm-up data"));
				warmUpHandle.toReadOffHeapWithFileBackupReference().close();
			}
			assertEquals(1, getFreeOffHeapOutputCount());

			final WriteOnlyOffHeapWithFileBackupHandle handleA = createWriteHandle(memoryManager);
			handleA.checkAndExecuteAndSync("write", () -> {}, output -> output.writeString("Transaction A data"));
			final OffHeapWithFileBackupReference referenceA = handleA.toReadOffHeapWithFileBackupReference();
			assertEquals(0, getFreeOffHeapOutputCount(), "A must have taken the only recycled instance, leaving none behind");

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
			assertEquals(1, getFreeOffHeapOutputCount(), "B's own instance must have been recycled on handle close");

			final byte[] snapshotAfterB = snapshot(bufferA, referenceA.getContentLength());
			assertArrayEquals(
				snapshotBeforeB, snapshotAfterB,
				"A's off-heap region bytes must be unaffected by B's independent borrow/recycle activity"
			);

			// closing the still-open reference now recycles A's instance; closing the handle afterward must no-op
			assertDoesNotThrow(referenceA::close);
			assertDoesNotThrow(handleA::close);
			assertEquals(2, getFreeOffHeapOutputCount(), "both A's and B's instances must be recycled exactly once");
		}
	}

	@Test
	@DisplayName("Should drop (not recycle) the off-heap ObservableOutput when overflow switches to file backup")
	void shouldNotRecycleOffHeapOutputOnOverflow() throws NoSuchFieldException, IllegalAccessException {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TEST_CATALOG, 32, 1, ChecksumFactory.NO_OP);
			final WriteOnlyOffHeapWithFileBackupHandle writeHandle = createWriteHandle(memoryManager)
		) {
			assertEquals(0, getFreeOffHeapOutputCount());

			for (int i = 0; i < 5; i++) {
				final int index = i;
				writeHandle.checkAndExecuteAndSync(
					"write some data", () -> {}, output -> output.writeString("Data " + index + ".")
				);
			}

			// the small 32-byte region overflowed and the write switched to file backup - the half-written
			// off-heap ObservableOutput instance must be dropped, not handed back to the free-list
			assertEquals(0, getFreeOffHeapOutputCount(), "overflowed instance must not be recycled");

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
	 * Reflects into the shared keeper's free-list for size assertions.
	 */
	private int getFreeOffHeapOutputCount() throws NoSuchFieldException, IllegalAccessException {
		final Field field = ObservableOutputKeeper.class.getDeclaredField("freeOffHeapOutputs");
		field.setAccessible(true);
		final ConcurrentLinkedDeque<?> deque = (ConcurrentLinkedDeque<?>) field.get(this.outputKeeper);
		return deque.size();
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
