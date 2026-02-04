/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test class verifies the behavior of the {@link CatalogWriteAheadLog} component, which is responsible
 * for managing the Write-Ahead Log (WAL) for catalog data.
 *
 * The Write-Ahead Log is a critical component for ensuring data durability and recovery in case of system failures.
 * It records all transaction mutations before they are applied to the main data store, allowing for recovery
 * in case of unexpected shutdowns or crashes.
 *
 * The tests are organized into nested classes by feature area:
 * - WAL Integrity Tests: verify checkAndTruncate, corruption handling, and truncation
 * - WAL File Name Parsing Tests: verify static filename parsing methods
 * - First Version Retrieval Tests: verify getFirstVersionOf functionality
 * - Cumulative CRC32 Tests: verify checksum computation and storage
 *
 * Each test sets up a temporary WAL file with predefined transaction data and then tests different
 * aspects of the WAL functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings({"ResultOfMethodCallIgnored", "SameParameterValue"})
@DisplayName("Catalog Write-Ahead Log functionality tests")
class CatalogWriteAheadLogTest implements EvitaTestSupport {
	private final Path walDirectory = getTestDirectory().resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final LogFileRecordReference walFileReference = new LogFileRecordReference(
		index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index)
	);
	private final int[] txSizes = new int[]{55, 152, 199, 46};
	private CatalogWriteAheadLog tested;
	private Path walFilePath;

	/**
	 * Creates a transaction mutation with test data of specified size.
	 *
	 * @param transactionIndex index of the transaction (used for transaction ID)
	 * @param dataSize         size of the test data in bytes
	 * @return a pair containing the transaction mutation and its data buffer
	 */
	@Nonnull
	private static TransactionWithData createTestTransaction(int transactionIndex, int dataSize) {
		final ByteBuffer dataBuffer = ByteBuffer.allocate(200);
		final Checksum checksum = Crc32CChecksumFactory.INSTANCE.createChecksum();
		dataBuffer.clear();

		for (int j = 0; j < dataSize; j++) {
			final byte theByte = (byte) j;
			dataBuffer.put(theByte);
			checksum.update(theByte);
		}
		dataBuffer.flip();

		final TransactionMutation transactionMutation = new TransactionMutation(
			UUIDUtil.randomUUID(),
			1L + transactionIndex,
			2,
			dataSize,
			OffsetDateTime.MIN
		);

		return new TransactionWithData(
			transactionMutation,
			OffHeapWithFileBackupReference.withByteBuffer(
				dataBuffer, dataSize, checksum.getValue(), dataBuffer::clear
			)
		);
	}

	/**
	 * Reads an 8-byte little-endian long from the given byte array.
	 */
	private static long readLittleEndianLong(@Nonnull byte[] bytes) {
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
		this.tested = createTestWal();
		this.walFilePath = this.walDirectory.resolve(
			getWalFileName(TEST_CATALOG, this.walFileReference.fileIndex())
		);

		// Initialize the WAL file with multiple test transactions
		for (int i = 0; i < this.txSizes.length; i++) {
			final int txSize = this.txSizes[i];
			final TransactionWithData transaction = createTestTransaction(i, txSize);
			this.tested.append(transaction.mutation(), transaction.data());
		}
	}

	@AfterEach
	void tearDown() throws IOException {
		this.tested.close();
		FileUtils.deleteDirectory(this.walDirectory);
	}

	/**
	 * Creates a test instance of CatalogWriteAheadLog with default settings.
	 */
	@Nonnull
	private CatalogWriteAheadLog createTestWal() {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			this.walFileReference,
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder().build(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {},
			null
		);
	}

	/**
	 * Creates a test instance of CatalogWriteAheadLog with a custom WAL file size limit.
	 *
	 * @param walFileSizeBytes the maximum size of each WAL file in bytes
	 * @return a configured CatalogWriteAheadLog instance
	 */
	@Nonnull
	private CatalogWriteAheadLog createTestWalWithCustomSize(long walFileSizeBytes) {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder()
					.compress(false)
					.build(),
				TransactionOptions.builder()
					.walFileSizeBytes(walFileSizeBytes)
					.walFileCountKept(10)
					.build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {},
			null
		);
	}

	/**
	 * Helper method to modify WAL file for testing purposes.
	 *
	 * @param modificationFunction function that performs the actual modification on the RandomAccessFile
	 * @return the original length of the WAL file before modification
	 * @throws IOException if an I/O error occurs
	 */
	private long modifyWalFile(@Nonnull IOFunction<RandomAccessFile, Void> modificationFunction) throws IOException {
		final File walFile = this.walFilePath.toFile();
		final long originalWalFileLength = walFile.length();

		try (final RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
			modificationFunction.apply(raf);
		}

		return originalWalFileLength;
	}

	/**
	 * Nested tests for WAL integrity checking functionality.
	 */
	@Nested
	@DisplayName("WAL Integrity Tests")
	class WalIntegrityTests {

		@Test
		@DisplayName("should verify WAL is OK for valid file")
		void shouldVerifyWalIsOk() {
			// Should not throw any exception when WAL file is valid
			CatalogWriteAheadLogTest.this.tested.checkAndTruncate(CatalogWriteAheadLogTest.this.walFilePath, CatalogWriteAheadLogTest.this.catalogKryoPool, CatalogWriteAheadLogTest.this.walFileReference);
		}

		@Test
		@DisplayName("should truncate partially written WAL file")
		void shouldTruncatePartiallyWrittenWal() throws IOException {
			// Truncate the WAL to simulate a partially written WAL file
			final int lastTxSize = CatalogWriteAheadLogTest.this.txSizes[CatalogWriteAheadLogTest.this.txSizes.length - 1];
			final long originalWalFileLength = modifyWalFile(raf -> {
				raf.setLength(raf.length() - (lastTxSize / 2));
				return null;
			});

			// Should not throw any exception, but truncate the file properly
			CatalogWriteAheadLogTest.this.tested.checkAndTruncate(CatalogWriteAheadLogTest.this.walFilePath, CatalogWriteAheadLogTest.this.catalogKryoPool, CatalogWriteAheadLogTest.this.walFileReference);

			// Verify the file was truncated to the correct size
			final File walFile = CatalogWriteAheadLogTest.this.walFilePath.toFile();
			final int txLengthBytes = 4;
			final int classIdBytes = 2;
			final int offsetDateTimeBytesDelta = 11;
			final int checksumBytes = AbstractMutationLog.CUMULATIVE_CRC32_SIZE;

			final long expectedSize = originalWalFileLength - lastTxSize
				- (AbstractMutationLog.TRANSACTION_MUTATION_SIZE - offsetDateTimeBytesDelta)
				- txLengthBytes - classIdBytes - checksumBytes;

			assertEquals(expectedSize, walFile.length(), "WAL file should be truncated to remove incomplete transaction");
		}

		@Test
		@DisplayName("should return new log file reference when WAL file is damaged")
		void shouldReturnNewLogFileReferenceWhenWalFileIsDamaged() throws IOException {
			// Damage the WAL file by overwriting part of a transaction record
			modifyWalFile(raf -> {
				final int checksumBytes = AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
				final long leadPosition = raf.length() - (CatalogWriteAheadLogTest.this.txSizes[CatalogWriteAheadLogTest.this.txSizes.length - 1] + checksumBytes + 10);
				raf.seek(leadPosition);
				raf.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
				return null;
			});

			// Should return a new log file reference indicating corruption was detected
			assertNotNull(
				CatalogWriteAheadLogTest.this.tested.checkAndTruncate(CatalogWriteAheadLogTest.this.walFilePath, CatalogWriteAheadLogTest.this.catalogKryoPool, CatalogWriteAheadLogTest.this.walFileReference).logFileRecordReference(),
				"Corrupted WAL file should be detected and return new reference"
			);
		}
	}

	/**
	 * Nested tests for WAL file name parsing functionality.
	 */
	@Nested
	@DisplayName("WAL File Name Parsing Tests")
	class WalFileNameParsingTests {

		@Test
		@DisplayName("should extract index from valid WAL file names")
		void shouldExtractIndexFromValidWalFileNames() {
			assertEquals(
				123, AbstractMutationLog.getIndexFromWalFileName("testCatalog_123.wal"),
				"Should extract index 123 from WAL filename"
			);
			assertEquals(
				1, AbstractMutationLog.getIndexFromWalFileName("testCatalog_1.wal"),
				"Should extract index 1 from WAL filename"
			);
			assertEquals(
				456789, AbstractMutationLog.getIndexFromWalFileName("testCatalog_456789.wal"),
				"Should extract index 456789 from WAL filename"
			);
		}

		@Test
		@DisplayName("should throw exception for invalid WAL file names")
		void shouldThrowExceptionForInvalidWalFileNames() {
			GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName("invalid_name.wal"),
				"Should throw exception for invalid WAL filename format"
			);
			assertTrue(exception.getMessage().contains("Invalid WAL file name"));

			exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName(".wal"),
				"Should throw exception for WAL filename without catalog and index"
			);
			assertTrue(exception.getMessage().contains("Invalid WAL file name"));

			exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName("testCatalog_.wal"),
				"Should throw exception for WAL filename without index"
			);
			assertTrue(exception.getMessage().contains("Invalid WAL file name"));
		}
	}

	/**
	 * Nested tests for first version retrieval functionality.
	 */
	@Nested
	@DisplayName("First Version Retrieval Tests")
	class FirstVersionRetrievalTests {

		@Test
		@DisplayName("should get first version from existing WAL file")
		void shouldGetFirstVersionOfExistingWalFile() {
			final long firstVersion = CatalogWriteAheadLogTest.this.tested.getFirstVersionOf(CatalogWriteAheadLogTest.this.walFileReference.fileIndex());
			assertEquals(1L, firstVersion, "Should return version 1 as the first version in the WAL file");
		}

		@Test
		@DisplayName("should return -1 for non-existing WAL file")
		void shouldReturnMinusOneForNonExistingWalFile() {
			final long firstVersion = CatalogWriteAheadLogTest.this.tested.getFirstVersionOf(999);
			assertEquals(-1L, firstVersion, "Should return -1 when WAL file does not exist");
		}

		@Test
		@DisplayName("should return -1 for empty WAL file")
		void shouldReturnMinusOneForEmptyWalFile() throws IOException {
			final int emptyWalIndex = 1;
			final Path emptyWalPath = CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, emptyWalIndex));
			assertTrue(emptyWalPath.toFile().createNewFile());

			try {
				final long firstVersion = CatalogWriteAheadLogTest.this.tested.getFirstVersionOf(emptyWalIndex);
				assertEquals(-1L, firstVersion, "Should return -1 when WAL file is empty");
			} finally {
				assertTrue(emptyWalPath.toFile().delete());
			}
		}
	}

	/**
	 * Nested tests for cumulative CRC32 checksum functionality.
	 * These tests verify the checksum computation and storage in the WAL file.
	 */
	@Nested
	@DisplayName("Cumulative CRC32 Tests")
	class CumulativeCrc32Tests {

		@Test
		@DisplayName("should compute and store checksum for each transaction")
		void shouldComputeAndStoreChecksumForEachTransaction() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			try (CatalogWriteAheadLog wal = createTestWal()) {
				long previousChecksum = 0;
				for (int i = 1; i <= 3; i++) {
					final TransactionWithData txData = createTestTransaction(i - 1, 100 + i * 10);
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

			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should include checksum in LogFileRecordReference")
		void shouldIncludeChecksumInReference() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			try (CatalogWriteAheadLog wal = createTestWal()) {
				final TransactionWithData txData = createTestTransaction(0, 100);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());

				assertEquals(0, reference.fileIndex(), "File index should be 0");
				assertNotEquals(0L, reference.cumulativeChecksum(), "Checksum should not be zero");
				assertNotNull(reference.fileLocation(), "File location should not be null");
				assertTrue(reference.fileLocation().recordLength() > 0, "Record length should be positive");
			}

			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should handle checksum with multiple transactions")
		void shouldHandleChecksumWithMultipleTransactions() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			try (CatalogWriteAheadLog wal = createTestWal()) {
				final long[] checksums = new long[5];
				for (int i = 0; i < 5; i++) {
					final TransactionWithData txData = createTestTransaction(i, 50 + i * 25);
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

			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should write correct cumulative CRC32C checksum to WAL file")
		void shouldWriteCorrectCumulativeCrc32ChecksumToWalFile() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path walPath = CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));

			final long reportedChecksum;
			final long recordLength;
			try (CatalogWriteAheadLog wal = createTestWal()) {
				final TransactionWithData txData = createTestTransaction(0, 100);
				final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
				reportedChecksum = reference.cumulativeChecksum();
				recordLength = reference.fileLocation().recordLength();
			}

			try (final RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "r")) {
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

			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should write correct WAL tail with cumulative checksum on rotation")
		void shouldWriteCorrectWalTailWithCumulativeChecksumOnRotation() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final long walFileSizeLimit = 4096L;
			final Path firstWalFilePath = CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));
			final Path secondWalFilePath = CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 1));

			long firstCatalogVersionInFile0 = -1;
			long lastCatalogVersionBeforeRotation = -1;

			try (CatalogWriteAheadLog wal = createTestWalWithCustomSize(walFileSizeLimit)) {
				int transactionVersion = 1;

				while (!Files.exists(secondWalFilePath)) {
					final TransactionWithData txData = createTestTransaction(transactionVersion - 1, 200);
					wal.append(txData.mutation(), txData.data());

					if (firstCatalogVersionInFile0 == -1) {
						firstCatalogVersionInFile0 = transactionVersion;
					}
					lastCatalogVersionBeforeRotation = transactionVersion;
					transactionVersion++;

					if (transactionVersion > 100) {
						throw new AssertionError("WAL rotation did not occur within expected number of transactions");
					}
				}

				lastCatalogVersionBeforeRotation = transactionVersion - 2;
			}

			assertTrue(Files.exists(firstWalFilePath), "First WAL file should exist after rotation");

			try (RandomAccessFile raf = new RandomAccessFile(firstWalFilePath.toFile(), "r")) {
				final long fileSize = raf.length();

				final int walTailLength = 24;
				assertTrue(fileSize > walTailLength, "WAL file should be larger than tail length");

				raf.seek(fileSize - walTailLength);
				final byte[] tailBytes = new byte[walTailLength];
				raf.readFully(tailBytes);

				final byte[] firstCvBytes = new byte[8];
				System.arraycopy(tailBytes, 0, firstCvBytes, 0, 8);
				final long storedFirstCv = readLittleEndianLong(firstCvBytes);

				final byte[] lastCvBytes = new byte[8];
				System.arraycopy(tailBytes, 8, lastCvBytes, 0, 8);
				final long storedLastCv = readLittleEndianLong(lastCvBytes);

				final byte[] checksumBytesFromTail = new byte[8];
				System.arraycopy(tailBytes, 16, checksumBytesFromTail, 0, 8);
				final long storedChecksum = readLittleEndianLong(checksumBytesFromTail);

				assertEquals(
					firstCatalogVersionInFile0, storedFirstCv,
					"Stored firstCv should match first transaction version written to file 0"
				);
				assertEquals(
					lastCatalogVersionBeforeRotation, storedLastCv,
					"Stored lastCv should match last transaction version before rotation"
				);

				// Independently verify checksum
				raf.seek(0);
				final byte[] initialChecksumBytes = new byte[8];
				raf.readFully(initialChecksumBytes);
				final long initialChecksum = readLittleEndianLong(initialChecksumBytes);

				final int dataLengthForChecksum = (int) (fileSize - 8);
				final byte[] transactionDataBytes = new byte[dataLengthForChecksum];
				raf.seek(0);
				raf.readFully(transactionDataBytes);

				final Crc32CWrapper checksumCalculator = new Crc32CWrapper(initialChecksum);
				checksumCalculator.withByteArray(transactionDataBytes);
				final long computedChecksum = checksumCalculator.getValue();

				assertEquals(
					computedChecksum, storedChecksum,
					"Stored checksum should match independently computed CRC32C"
				);
			}

			// Clean directory and recreate WAL for tearDown
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}
	}

	/**
	 * Functional interface for operations that can throw IOException.
	 */
	@FunctionalInterface
	private interface IOFunction<T, R> {
		R apply(T t) throws IOException;
	}

	/**
	 * Simple container for a transaction mutation and its associated data.
	 */
	private record TransactionWithData(
		@Nonnull TransactionMutation mutation,
		@Nonnull OffHeapWithFileBackupReference data
	) {
	}
}
