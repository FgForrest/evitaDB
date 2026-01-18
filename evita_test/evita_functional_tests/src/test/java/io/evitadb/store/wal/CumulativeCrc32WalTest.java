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
import java.util.zip.CRC32C;

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

		for (int j = 0; j < dataSize; j++) {
			dataBuffer.put((byte) j);
		}
		dataBuffer.flip();

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
				dataBuffer, dataSize, 0L, dataBuffer::clear
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
		try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
			// The checksum is the last 8 bytes of the transaction record
			// recordLength includes: 4 (content length) + content + 8 (checksum)
			final long checksumPosition = recordLength - 8;
			raf.seek(checksumPosition);

			// Read the 8-byte checksum (little-endian long)
			final long storedChecksum = Long.reverseBytes(raf.readLong());

			// The stored checksum should match what we computed
			assertEquals(
				finalChecksum, storedChecksum,
				"Stored checksum should match computed checksum"
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
		long recordLength;
		try {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
			reportedChecksum = reference.cumulativeChecksum();
			recordLength = reference.fileLocation().recordLength();
		} finally {
			wal.close();
		}

		// Verify the checksum by computing it ourselves
		try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
			// Read all bytes up to (but not including) the checksum
			final long dataLength = recordLength - 8;
			final byte[] data = new byte[(int) dataLength];
			raf.readFully(data);

			// Compute CRC32C
			final CRC32C crc32c = new CRC32C();
			crc32c.update(data);
			final long computedChecksum = crc32c.getValue();

			assertEquals(
				reportedChecksum, computedChecksum,
				"Reported checksum should match independently computed checksum"
			);
		}
	}

	private record TransactionWithData(
		TransactionMutation mutation,
		OffHeapWithFileBackupReference data
	) {
	}
}
