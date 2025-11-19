/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class verifies the behavior of the {@link CatalogWriteAheadLog} component, which is responsible
 * for managing the Write-Ahead Log (WAL) for catalog data.
 *
 * The Write-Ahead Log is a critical component for ensuring data durability and recovery in case of system failures.
 * It records all transaction mutations before they are applied to the main data store, allowing for recovery
 * in case of unexpected shutdowns or crashes.
 *
 * The tests in this class verify:
 * - Basic WAL integrity checking
 * - Handling of partially written WAL files (truncation)
 * - Detection of corrupted WAL files
 * - Parsing of WAL file names and index extraction
 *
 * Each test sets up a temporary WAL file with predefined transaction data and then tests different
 * aspects of the WAL functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Catalog Write-Ahead Log functionality tests")
class CatalogWriteAheadLogTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final LogFileRecordReference walFileReference = new LogFileRecordReference(
		index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index),
		0, null
	);
	private final CatalogWriteAheadLog tested = new CatalogWriteAheadLog(
		0L,
		TEST_CATALOG,
		index -> getWalFileName(TEST_CATALOG, index),
		this.walDirectory,
		this.catalogKryoPool,
		StorageOptions.builder().build(),
		TransactionOptions.builder().build(),
		Mockito.mock(Scheduler.class),
		offsetDateTime -> {},
		null
	);
	private final Path walFilePath = this.walDirectory.resolve(getWalFileName(TEST_CATALOG, this.walFileReference.fileIndex()));
	private final int[] txSizes = new int[] {55, 152, 199, 46};

	/**
	 * Creates a transaction mutation with test data of specified size.
	 *
	 * @param transactionIndex Index of the transaction (used for transaction ID)
	 * @param dataSize Size of the test data in bytes
	 * @return A pair containing the transaction mutation and its data buffer
	 */
	private static TransactionWithData createTestTransaction(int transactionIndex, int dataSize) {
		// Create a buffer with test data
		final ByteBuffer dataBuffer = ByteBuffer.allocate(200);
		dataBuffer.clear();

		// Fill buffer with sequential bytes as test data
		for (int j = 0; j < dataSize; j++) {
			dataBuffer.put((byte) j);
		}
		dataBuffer.flip();

		// Create transaction mutation with metadata
		final TransactionMutation transactionMutation = new TransactionMutation(
			UUIDUtil.randomUUID(),  // Random UUID for the transaction
			1L + transactionIndex,  // Transaction ID based on index
			2,                      // Fixed number of operations
			dataSize,               // Size of the transaction data
			OffsetDateTime.MIN      // Timestamp (using MIN for deterministic testing)
		);

		return new TransactionWithData(
			transactionMutation,
			OffHeapWithFileBackupReference.withByteBuffer(
				dataBuffer, dataSize, dataBuffer::clear
			)
		);
	}

	/**
	 * Simple container for a transaction mutation and its associated data.
	 */
	private record TransactionWithData(
		TransactionMutation mutation,
		OffHeapWithFileBackupReference data
	) {}

	@BeforeEach
	void setUp() {
		// Initialize the WAL file with multiple test transactions of different sizes
		// This creates a predictable WAL file that can be used for testing various scenarios
		for (int i = 0; i < this.txSizes.length; i++) {
			final int txSize = this.txSizes[i];

			// Create and append a test transaction to the WAL
			final TransactionWithData transaction = createTestTransaction(i, txSize);
			this.tested.append(transaction.mutation(), transaction.data());
		}
	}

	/**
	 * Cleans up resources after each test.
	 *
	 * This method ensures that:
	 * 1. The WAL file is properly closed to release any file handles
	 * 2. The temporary WAL file is deleted from the filesystem
	 *
	 * @throws IOException If an error occurs during cleanup
	 */
	@AfterEach
	void tearDown() throws IOException {
		// Close the WAL file to release resources
		this.tested.close();

		// Delete the temporary WAL file
		this.walFilePath.toFile().delete();
	}

	/**
	 * Helper method to modify WAL file for testing purposes.
	 *
	 * @param modificationFunction Function that performs the actual modification on the RandomAccessFile
	 * @return The original length of the WAL file before modification
	 * @throws IOException If an I/O error occurs
	 */
	private long modifyWalFile(IOFunction<java.io.RandomAccessFile, Void> modificationFunction) throws IOException {
		final File walFile = this.walFilePath.toFile();
		final long originalWalFileLength = walFile.length();

		try (final var raf = new java.io.RandomAccessFile(walFile, "rw")) {
			modificationFunction.apply(raf);
		}

		return originalWalFileLength;
	}

	/**
	 * Functional interface for operations that can throw IOException.
	 */
	@FunctionalInterface
	private interface IOFunction<T, R> {
		R apply(T t) throws IOException;
	}

	@Test
	@DisplayName("WAL integrity check should pass for valid WAL file")
	void shouldVerifyWalIsOk() {
		// Should not throw any exception when WAL file is valid
		this.tested.checkAndTruncate(this.walFilePath, this.catalogKryoPool, StorageOptions.temporary());
	}

	@Test
	@DisplayName("Partially written WAL file should be truncated correctly")
	void shouldTruncatePartiallyWrittenWal() throws IOException {
		// Truncate the WAL to simulate a partially written WAL file
		final int lastTxSize = this.txSizes[this.txSizes.length - 1];
		final long originalWalFileLength = modifyWalFile(raf -> {
			// Cut off half of the last transaction to simulate incomplete write
			raf.setLength(raf.length() - (lastTxSize / 2));
			return null;
		});

		// Should not throw any exception, but truncate the file properly
		this.tested.checkAndTruncate(this.walFilePath, this.catalogKryoPool, StorageOptions.temporary());

		// Verify the file was truncated to the correct size
		final File walFile = this.walFilePath.toFile();
		final int txLengthBytes = 4;  // Size of transaction length field
		final int classIdBytes = 2;   // Size of class ID field
		final int offsetDateTimeBytesDelta = 11;  // Difference in OffsetDateTime serialization

		// Calculate expected file size after truncation
		final long expectedSize = originalWalFileLength - lastTxSize
			- (AbstractMutationLog.TRANSACTION_MUTATION_SIZE - offsetDateTimeBytesDelta)
			- txLengthBytes - classIdBytes;

		assertEquals(expectedSize, walFile.length(), "WAL file should be truncated to remove incomplete transaction");
	}

	@Test
	@DisplayName("Corrupted WAL file should throw appropriate exception")
	void shouldThrowExceptionWhenLeadingTxMutationIsDamaged() throws IOException {
		// Damage the WAL file by overwriting part of a transaction record
		modifyWalFile(raf -> {
			// Calculate position to damage the leading transaction mutation
			final long leadPosition = raf.length() - (this.txSizes[this.txSizes.length - 1] + 10);
			raf.seek(leadPosition);
			// Write zeros to corrupt the data
			raf.write(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
			return null;
		});

		// Should throw an exception when WAL file is corrupted
		assertThrows(
			WriteAheadLogCorruptedException.class,
			() -> this.tested.checkAndTruncate(this.walFilePath, this.catalogKryoPool, StorageOptions.temporary()),
			"Corrupted WAL file should be detected and cause an exception"
		);
	}

	@Test
	@DisplayName("Valid WAL file names should have their index correctly extracted")
	void shouldExtractIndexFromValidWalFileNames() {
		// Test various valid WAL file name formats
		assertEquals(123, AbstractMutationLog.getIndexFromWalFileName("testCatalog_123.wal"),
		             "Should extract index 123 from WAL filename");
		assertEquals(1, AbstractMutationLog.getIndexFromWalFileName("testCatalog_1.wal"),
		             "Should extract index 1 from WAL filename");
		assertEquals(456789, AbstractMutationLog.getIndexFromWalFileName("testCatalog_456789.wal"),
		             "Should extract index 456789 from WAL filename");
	}

	@Test
	@DisplayName("Invalid WAL file names should cause appropriate exceptions")
	void shouldThrowExceptionForInvalidWalFileNames() {
		// Test case: completely invalid format
		GenericEvitaInternalError exception = assertThrows(
			GenericEvitaInternalError.class,
			() -> AbstractMutationLog.getIndexFromWalFileName("invalid_name.wal"),
			"Should throw exception for invalid WAL filename format"
		);
		assertTrue(exception.getMessage().contains("Invalid WAL file name"),
			"Exception message should indicate invalid WAL file name");

		// Test case: missing catalog name and index
		exception = assertThrows(
			GenericEvitaInternalError.class,
			() -> AbstractMutationLog.getIndexFromWalFileName(".wal"),
			"Should throw exception for WAL filename without catalog and index"
		);
		assertTrue(exception.getMessage().contains("Invalid WAL file name"),
			"Exception message should indicate invalid WAL file name");

		// Test case: missing index
		exception = assertThrows(
			GenericEvitaInternalError.class,
			() -> AbstractMutationLog.getIndexFromWalFileName("testCatalog_.wal"),
			"Should throw exception for WAL filename without index"
		);
		assertTrue(exception.getMessage().contains("Invalid WAL file name"),
			"Exception message should indicate invalid WAL file name");
    }

	@Test
	@DisplayName("Should retrieve first version from existing WAL file")
	void shouldGetFirstVersionOfExistingWalFile() {
		// The first transaction has version 1 (based on createTestTransaction logic: 1L + 0)
		final long firstVersion = this.tested.getFirstVersionOf(this.walFileReference.fileIndex());

		assertEquals(1L, firstVersion,
			"Should return version 1 as the first version in the WAL file");
	}

	@Test
	@DisplayName("Should return -1 for non-existing WAL file")
	void shouldReturnMinusOneForNonExistingWalFile() {
		// Query a WAL file index that doesn't exist
		final long firstVersion = this.tested.getFirstVersionOf(999);

		assertEquals(-1L, firstVersion,
			"Should return -1 when WAL file does not exist");
	}

	@Test
	@DisplayName("Should return -1 for empty WAL file")
	void shouldReturnMinusOneForEmptyWalFile() throws IOException {
		// Create an empty WAL file with a different index
		final int emptyWalIndex = 1;
		final Path emptyWalPath = this.walDirectory.resolve(getWalFileName(TEST_CATALOG, emptyWalIndex));
		assertTrue(emptyWalPath.toFile().createNewFile());

		try {
			final long firstVersion = this.tested.getFirstVersionOf(emptyWalIndex);

			assertEquals(-1L, firstVersion,
				"Should return -1 when WAL file is empty (less than 4 bytes)");
		} finally {
			// Cleanup
			assertTrue(emptyWalPath.toFile().delete());
		}
	}

}
