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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.utils.Crc32CWrapper;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test suite for verifying the cumulative CRC32C checksum functionality in the Write-Ahead Log (WAL).
 *
 * The cumulative checksum is calculated incrementally as bytes are written to the WAL file, covering:
 * - The 4-byte content length prefix
 * - The TransactionMutation record
 * - All mutation payload bytes
 *
 * Each transaction record in the WAL is followed by an 8-byte cumulative CRC32C checksum that represents
 * the checksum of all bytes from the beginning of the file up to (but not including) the checksum itself.
 *
 * These tests verify:
 * - Checksum is correctly computed and stored during writes
 * - Checksums are cumulative (each transaction has a different checksum)
 * - Checksum is stored in the LogFileRecordReference
 *
 * TODO JNO- tohle přepsat ručně
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Cumulative CRC32 WAL verification tests")
class CumulativeCrc32WalTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir"))
		.resolve("evita")
		.resolve(getClass().getSimpleName());

	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};

	/**
	 * Creates a transaction mutation with test data of specified size.
	 */
	private static TransactionWithData createTestTransaction(int transactionVersion, int dataSize) {
		final ByteBuffer dataBuffer = ByteBuffer.allocate(200);
		dataBuffer.clear();

		// Create data bytes and compute their checksum
		final byte[] dataBytes = new byte[dataSize];
		for (int j = 0; j < dataSize; j++) {
			dataBytes[j] = (byte) j;
			dataBuffer.put((byte) j);
		}
		dataBuffer.flip();

		// Compute CRC32C checksum of the data bytes
		final Crc32CWrapper checksumCalculator = new Crc32CWrapper();
		checksumCalculator.withByteArray(dataBytes);
		final long dataChecksum = checksumCalculator.getValue();

		final TransactionMutation transactionMutation = new TransactionMutation(
			UUIDUtil.randomUUID(),
			transactionVersion,
			0,  // No mutations - we're testing checksum, not mutation serialization
			dataSize,
			OffsetDateTime.MIN
		);

		return new TransactionWithData(
			transactionMutation,
			OffHeapWithFileBackupReference.withByteBuffer(
				dataBuffer, dataSize, dataChecksum, dataBuffer::clear
			)
		);
	}

	@BeforeEach
	void setUp() {
		// Clean up any existing test directory
		FileUtils.deleteDirectory(this.walDirectory);
		this.walDirectory.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() {
		FileUtils.deleteDirectory(this.walDirectory);
	}

	@Test
	@DisplayName("Should compute and store cumulative checksum for each transaction")
	void shouldComputeAndStoreChecksumForEachTransaction() throws IOException {
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.temporary(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {
			},
			null
		);

		try {
			long previousChecksum = 0;
			for (int i = 1; i <= 3; i++) {
				final TransactionWithData txData = createTestTransaction(i, 100 + i * 10);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());

				// Each transaction should have a different checksum (cumulative)
				assertNotEquals(
					previousChecksum, reference.cumulativeChecksum(),
					"Checksum should change after each transaction"
				);
				assertNotEquals(
					0L, reference.cumulativeChecksum(),
					"Checksum should not be zero after writing data"
				);

				previousChecksum = reference.cumulativeChecksum();
			}
		} finally {
			wal.close();
		}
	}

	@Test
	@DisplayName("Should include checksum in LogFileRecordReference")
	void shouldIncludeChecksumInReference() throws IOException {
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.temporary(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {
			},
			null
		);

		try {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());

			// Verify the reference contains all expected fields
			assertEquals(0, reference.fileIndex(), "File index should be 0");
			assertNotEquals(0L, reference.cumulativeChecksum(), "Checksum should not be zero");
			assertTrue(reference.fileLocation() != null, "File location should not be null");
			assertTrue(reference.fileLocation().recordLength() > 0, "Record length should be positive");
		} finally {
			wal.close();
		}
	}

	@Test
	@DisplayName("Should handle checksum with multiple transactions")
	void shouldHandleChecksumWithMultipleTransactions() throws IOException {
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.temporary(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {
			},
			null
		);

		try {
			// Write multiple transactions
			final long[] checksums = new long[5];
			for (int i = 0; i < 5; i++) {
				final TransactionWithData txData = createTestTransaction(i + 1, 50 + i * 25);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
				checksums[i] = reference.cumulativeChecksum();
			}

			// Verify checksums are all different (cumulative property)
			for (int i = 1; i < checksums.length; i++) {
				assertNotEquals(
					checksums[i - 1], checksums[i],
					"Each transaction should have a different cumulative checksum"
				);
			}
		} finally {
			wal.close();
		}
	}

	@Test
	@DisplayName("Should verify checksum is written to WAL file")
	void shouldVerifyChecksumIsWrittenToWalFile() throws IOException {
		final Path walFilePath = this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));

		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.temporary(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {
			},
			null
		);

		long finalChecksum;
		long recordLength;
		try {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
			finalChecksum = reference.cumulativeChecksum();
			recordLength = reference.fileLocation().recordLength();
		} finally {
			wal.close();
		}

		// Verify the checksum was actually written to the file
		// The cumulative checksum is written at the end of each transaction record
		// After close(), an additional 8 bytes may be written (just the cumulative checksum again)
		try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
			final long fileSize = raf.length();

			// The checksum appears to be written at the very end of the file
			// Try reading from fileSize - 8 (the last 8 bytes)
			raf.seek(fileSize - 8);

			// Read the 8-byte checksum in little-endian format (matching writeLongToByteBuffer)
			final byte[] checksumBytes = new byte[8];
			raf.readFully(checksumBytes);
			final long storedChecksum = readLittleEndianLong(checksumBytes);

			// The stored checksum should match what we computed
			assertEquals(
				finalChecksum, storedChecksum,
				"Stored checksum should match computed checksum (fileSize=" + fileSize +
					", recordLength=" + recordLength + ")"
			);
		}
	}

	@Test
	@DisplayName("Should compute correct cumulative checksum")
	void shouldComputeCorrectCumulativeChecksum() throws IOException {
		final Path walFilePath = this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));

		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.temporary(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {
			},
			null
		);

		long reportedChecksum;
		try {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
			reportedChecksum = reference.cumulativeChecksum();
		} finally {
			wal.close();
		}

		// WAL file structure (not finalized, just closed):
		// - 8 bytes: initial cumulative checksum
		// - 4 bytes: content length
		// - N bytes: transaction mutation
		// - M bytes: data payload
		// - 8 bytes: trailing cumulative checksum
		//
		// The trailing checksum is at (fileSize - 8) and represents the CRC32C
		// of all bytes from position 0 to (fileSize - 8).
		// Note: WAL tail (24 bytes with firstCv + lastCv + checksum) is only written
		// when WAL is finalized via rotateWalFile, not on close().
		try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
			final long fileSize = raf.length();

			// Read the trailing checksum (last 8 bytes)
			raf.seek(fileSize - 8);
			final byte[] checksumBytes = new byte[8];
			raf.readFully(checksumBytes);
			final long storedChecksum = readLittleEndianLong(checksumBytes);

			// Verify stored checksum matches reported checksum
			assertEquals(
				reportedChecksum, storedChecksum,
				"Stored checksum should match reported checksum"
			);

			// Read all bytes before the checksum
			final int dataLength = (int) (fileSize - 8);
			raf.seek(0);
			final byte[] allDataBytes = new byte[dataLength];
			raf.readFully(allDataBytes);

			// Compute CRC32C of all data bytes
			final Crc32CWrapper checksumCalculator = new Crc32CWrapper();
			checksumCalculator.withByteArray(allDataBytes);
			final long computedChecksum = checksumCalculator.getValue();

			assertEquals(
				reportedChecksum, computedChecksum,
				"Computed CRC32C of all file bytes should match reported checksum"
			);
		}
	}

	/**
	 * Reads an 8-byte little-endian long from the given byte array.
	 */
	private static long readLittleEndianLong(byte[] bytes) {
		return (bytes[0] & 0xFFL)
			| ((bytes[1] & 0xFFL) << 8)
			| ((bytes[2] & 0xFFL) << 16)
			| ((bytes[3] & 0xFFL) << 24)
			| ((bytes[4] & 0xFFL) << 32)
			| ((bytes[5] & 0xFFL) << 40)
			| ((bytes[6] & 0xFFL) << 48)
			| ((bytes[7] & 0xFFL) << 56);
	}

	/**
	 * Reads a 4-byte little-endian int from the given byte array.
	 */
	private static int readLittleEndianInt(byte[] bytes) {
		return (bytes[0] & 0xFF)
			| ((bytes[1] & 0xFF) << 8)
			| ((bytes[2] & 0xFF) << 16)
			| ((bytes[3] & 0xFF) << 24);
	}

	private record TransactionWithData(
		TransactionMutation mutation,
		OffHeapWithFileBackupReference data
	) {
	}
}
