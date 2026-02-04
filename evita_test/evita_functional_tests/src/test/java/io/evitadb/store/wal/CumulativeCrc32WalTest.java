/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Crc32CWrapper;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class CumulativeCrc32WalTest implements EvitaTestSupport {
	private final Path walDirectory = getTestDirectory().resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};

	/**
	 * Creates a transaction mutation with test data of specified size.
	 */
	@Nonnull
	private static TransactionWithData createTestTransaction(int transactionVersion, int dataSize) {
		final ByteBuffer dataBuffer = ByteBuffer.allocate(200);
		dataBuffer.clear();

		final byte[] dataBytes = new byte[dataSize];
		for (int j = 0; j < dataSize; j++) {
			dataBytes[j] = (byte) j;
			dataBuffer.put((byte) j);
		}
		dataBuffer.flip();

		final Crc32CWrapper checksumCalculator = new Crc32CWrapper();
		checksumCalculator.withByteArray(dataBytes);
		final long dataChecksum = checksumCalculator.getValue();

		final TransactionMutation transactionMutation = new TransactionMutation(
			UUIDUtil.randomUUID(),
			transactionVersion,
			0,
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

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		this.walDirectory.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() {
		FileUtils.deleteDirectory(this.walDirectory);
	}

	@Test
	@DisplayName("Should compute and store cumulative checksum for each transaction")
	void shouldComputeAndStoreChecksumForEachTransaction() throws IOException {
		try (CatalogWriteAheadLog wal = createTestWal()) {
			long previousChecksum = 0;
			for (int i = 1; i <= 3; i++) {
				final TransactionWithData txData = createTestTransaction(i, 100 + i * 10);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());

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
		}
	}

	@Test
	@DisplayName("Should include checksum in LogFileRecordReference")
	void shouldIncludeChecksumInReference() throws IOException {
		try (CatalogWriteAheadLog wal = createTestWal()) {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());

			assertEquals(0, reference.fileIndex(), "File index should be 0");
			assertNotEquals(0L, reference.cumulativeChecksum(), "Checksum should not be zero");
			assertNotNull(reference.fileLocation(), "File location should not be null");
			assertTrue(reference.fileLocation().recordLength() > 0, "Record length should be positive");
		}
	}

	@Test
	@DisplayName("Should handle checksum with multiple transactions")
	void shouldHandleChecksumWithMultipleTransactions() throws IOException {
		try (CatalogWriteAheadLog wal = createTestWal()) {
			final long[] checksums = new long[5];
			for (int i = 0; i < 5; i++) {
				final TransactionWithData txData = createTestTransaction(i + 1, 50 + i * 25);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
				checksums[i] = reference.cumulativeChecksum();
			}

			for (int i = 1; i < checksums.length; i++) {
				assertNotEquals(
					checksums[i - 1], checksums[i],
					"Each transaction should have a different cumulative checksum"
				);
			}
		}
	}

	/**
	 * Verifies that the cumulative CRC32C checksum is correctly written to the WAL file
	 * and that it can be independently verified by computing CRC32C over all preceding bytes.
	 */
	@Test
	@DisplayName("Should write correct cumulative CRC32C checksum to WAL file")
	void shouldWriteCorrectCumulativeCrc32ChecksumToWalFile() throws IOException {
		final Path walFilePath = this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));
		final CatalogWriteAheadLog wal = createTestWal();

		final long reportedChecksum;
		final long recordLength;
		try {
			final TransactionWithData txData = createTestTransaction(1, 100);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
			reportedChecksum = reference.cumulativeChecksum();
			recordLength = reference.fileLocation().recordLength();
		} finally {
			wal.close();
		}

		// The trailing checksum (last 8 bytes) represents CRC32C of all preceding bytes
		try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
			final long fileSize = raf.length();

			raf.seek(fileSize - 8);
			final byte[] checksumBytes = new byte[8];
			raf.readFully(checksumBytes);
			final long storedChecksum = readLittleEndianLong(checksumBytes);

			assertEquals(
				reportedChecksum, storedChecksum,
				"Stored checksum should match reported (fileSize=" + fileSize + ", recordLength=" + recordLength + ")"
			);

			// Independently verify by computing CRC32C of all preceding bytes
			final int dataLength = (int) (fileSize - 8);
			raf.seek(0);
			final byte[] allDataBytes = new byte[dataLength];
			raf.readFully(allDataBytes);

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
	 * Creates a test instance of CatalogWriteAheadLog.
	 */
	@Nonnull
	private CatalogWriteAheadLog createTestWal() {
		return new CatalogWriteAheadLog(
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
			offsetDateTime -> {},
			null
		);
	}

	private record TransactionWithData(
		@Nonnull TransactionMutation mutation,
		@Nonnull OffHeapWithFileBackupReference data
	) {
	}
}
