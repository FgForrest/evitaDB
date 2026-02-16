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

import com.carrotsearch.hppc.LongHashSet;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.wal.model.EngineTransactionChanges;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
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
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EngineMutationLog} functionality, organized into nested classes by feature area.
 *
 * This test suite verifies behavior specific to the Engine-level Write-Ahead Log that tracks
 * engine-level mutations such as catalog creations, deletions, and renames.
 *
 * The tests are organized into the following categories:
 * - WAL Integrity Tests: verify checkAndTruncate and corruption handling
 * - WAL File Name Parsing Tests: verify static filename parsing methods
 * - First Version Retrieval Tests: verify getFirstVersionOf functionality
 * - WAL Descriptor Tests: verify getWriteAheadLogVersionDescriptor functionality
 * - Mutation Stream Tests: verify reading mutations via streams
 * - Cumulative CRC32 Tests: verify checksum computation and storage
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings({"ResultOfMethodCallIgnored", "SameParameterValue"})
@DisplayName("Engine Mutation Log functionality tests")
class EngineMutationLogTest implements EvitaTestSupport {
	private static final StorageSettings LARGE_FILE_STORAGE_SETTINGS = new StorageSettings(
		StorageOptions.builder()
			.compress(false)
			.build(),
		TransactionOptions.builder()
			.walFileSizeBytes(Long.MAX_VALUE)
			.build()
	);
	private final Path walDirectory = getTestDirectory().resolve(getClass().getSimpleName());
	private final Pool<Kryo> kryoPool = new Pool<>(false, false, 1) {
		@Nonnull
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final Path isolatedWalFilePath = this.walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(
		EnginePersistenceService.ENGINE_NAME, 10_000_000, 128, Crc32CChecksumFactory.INSTANCE
	);
	private EngineMutationLog wal;

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		this.walDirectory.toFile().mkdirs();
		this.wal = createEngineWalWithLargeSize();
	}

	@AfterEach
	void tearDown() throws Exception {
		this.observableOutputKeeper.close();
		this.wal.close();
		FileUtils.deleteDirectory(this.walDirectory);
	}

	/**
	 * Creates an EngineMutationLog with large file size to avoid rotation during tests.
	 */
	@Nonnull
	private EngineMutationLog createEngineWalWithLargeSize() {
		return new EngineMutationLog(
			0L,
			new LogFileRecordReference(EnginePersistenceService::getWalFileName),
			this.walDirectory,
			this.kryoPool,
			LARGE_FILE_STORAGE_SETTINGS,
			Mockito.mock(Scheduler.class)
		);
	}

	/**
	 * Creates an EngineMutationLog with a custom WAL file size limit for rotation tests.
	 *
	 * @param walFileSizeBytes the maximum size of each WAL file in bytes
	 * @return a configured EngineMutationLog instance
	 */
	@Nonnull
	private EngineMutationLog createEngineWalWithCustomSize(long walFileSizeBytes) {
		return new EngineMutationLog(
			0L,
			new LogFileRecordReference(EnginePersistenceService::getWalFileName),
			this.walDirectory,
			this.kryoPool,
			new StorageSettings(
				StorageOptions.builder()
					.compress(false)
					.build(),
				TransactionOptions.builder()
					.walFileSizeBytes(walFileSizeBytes)
					.walFileCountKept(10)
					.build()
			),
			Mockito.mock(Scheduler.class)
		);
	}

	/**
	 * Writes engine WAL entries composed of SetCatalogMutabilityMutation and appends TransactionMutations to WAL.
	 *
	 * @param transactionSizes   array specifying the number of mutations in each transaction
	 * @param initialTimestamp   optional starting timestamp
	 * @return list of TxData for each written transaction to verify content later
	 */
	@Nonnull
	private List<TxData> writeEngineWal(@Nonnull int[] transactionSizes, @Nullable OffsetDateTime initialTimestamp) {
		final DefaultIsolatedWalService walPersistenceService = new DefaultIsolatedWalService(
			TEST_CATALOG,
			UUID.randomUUID(),
			EnumSet.noneOf(ConflictPolicy.class),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.isolatedWalFilePath,
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				false,
				this.observableOutputKeeper,
				this.offHeapMemoryManager,
				LARGE_FILE_STORAGE_SETTINGS,
				LARGE_FILE_STORAGE_SETTINGS
			)
		);

		final List<TxData> result = new ArrayList<>(transactionSizes.length);
		OffsetDateTime timestamp = initialTimestamp == null ? OffsetDateTime.now() : initialTimestamp;
		for (int i = 0; i < transactionSizes.length; i++) {
			final int txSize = transactionSizes[i];
			final long version = i + 1L;
			final StringBuilder expectedConcat = new StringBuilder(txSize << 5);

			for (int j = 0; j < txSize; j++) {
				final boolean mutable = ((i + j) & 1) == 0;
				final SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("engine-cat", mutable);
				walPersistenceService.write(version, mutation);
				expectedConcat.append(mutation.toString());
			}

			final OffHeapWithFileBackupReference walReference = walPersistenceService.getWalReference();
			final TransactionMutation txMutation = new TransactionMutation(
				UUIDUtil.randomUUID(),
				version,
				txSize,
				walReference.getContentLength(),
				timestamp
			);

			this.wal.append(txMutation, walReference);

			result.add(new TxData(version, timestamp, txSize, walReference.getContentLength(), expectedConcat.toString()));
			timestamp = timestamp.plusMinutes(1);
		}

		return result;
	}

	/**
	 * Creates a raw transaction with test data of specified size for integrity tests.
	 *
	 * @param transactionIndex index of the transaction (used for transaction version)
	 * @param dataSize         size of the test data in bytes
	 * @return a pair containing the transaction mutation and its data buffer
	 */
	@Nonnull
	private static TransactionWithData createRawTestTransaction(int transactionIndex, int dataSize) {
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
	 * Helper method to modify WAL file for testing purposes.
	 *
	 * @param modificationFunction function that performs the actual modification on the RandomAccessFile
	 * @return the original length of the WAL file before modification
	 * @throws IOException if an I/O error occurs
	 */
	private long modifyWalFile(@Nonnull IOFunction<RandomAccessFile, Void> modificationFunction) throws IOException {
		final Path walFilePath = this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));
		final File walFile = walFilePath.toFile();
		final long originalWalFileLength = walFile.length();

		try (final RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
			modificationFunction.apply(raf);
		}

		return originalWalFileLength;
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

	/**
	 * Nested tests for WAL integrity checking functionality.
	 */
	@Nested
	@DisplayName("WAL Integrity Tests")
	class WalIntegrityTests {

		@Test
		@DisplayName("should verify WAL is OK for valid file")
		void shouldVerifyWalIsOk() {
			final int[] txSizes = new int[]{55, 152, 199, 46};
			for (int i = 0; i < txSizes.length; i++) {
				final TransactionWithData transaction = createRawTestTransaction(i, txSizes[i]);
				EngineMutationLogTest.this.wal.append(transaction.mutation(), transaction.data());
			}

			final Path walFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));
			// Should not throw any exception when WAL file is valid
			EngineMutationLogTest.this.wal.checkAndTruncate(
				walFilePath, EngineMutationLogTest.this.kryoPool, new LogFileRecordReference(EnginePersistenceService::getWalFileName)
			);
		}

		@Test
		@DisplayName("should truncate partially written WAL file")
		void shouldTruncatePartiallyWrittenWal() throws IOException {
			final int[] txSizes = new int[]{55, 152, 199, 46};
			for (int i = 0; i < txSizes.length; i++) {
				final TransactionWithData transaction = createRawTestTransaction(i, txSizes[i]);
				EngineMutationLogTest.this.wal.append(transaction.mutation(), transaction.data());
			}

			// Truncate the WAL to simulate a partially written file
			final int lastTxSize = txSizes[txSizes.length - 1];
			final long originalWalFileLength = modifyWalFile(raf -> {
				raf.setLength(raf.length() - (lastTxSize / 2));
				return null;
			});

			final Path walFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));
			final LogFileRecordReference walFileReference = new LogFileRecordReference(EnginePersistenceService::getWalFileName);

			// Should not throw any exception, but truncate the file properly
			EngineMutationLogTest.this.wal.checkAndTruncate(walFilePath, EngineMutationLogTest.this.kryoPool, walFileReference);

			// Verify the file was truncated
			final File walFile = walFilePath.toFile();
			assertTrue(walFile.length() < originalWalFileLength);
		}

		@Test
		@DisplayName("should detect and handle corrupted WAL file")
		void shouldDetectCorruptedWalFile() throws IOException {
			final int[] txSizes = new int[]{55, 152, 199, 46};
			for (int i = 0; i < txSizes.length; i++) {
				final TransactionWithData transaction = createRawTestTransaction(i, txSizes[i]);
				EngineMutationLogTest.this.wal.append(transaction.mutation(), transaction.data());
			}

			// Damage the WAL file by overwriting part of a transaction record
			modifyWalFile(raf -> {
				final int checksumBytes = AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
				final long leadPosition = raf.length() - (txSizes[txSizes.length - 1] + checksumBytes + 10);
				raf.seek(leadPosition);
				raf.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
				return null;
			});

			final Path walFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));
			final LogFileRecordReference walFileReference = new LogFileRecordReference(EnginePersistenceService::getWalFileName);

			// Should return a new log file reference indicating corruption was detected
			assertNotNull(
				EngineMutationLogTest.this.wal.checkAndTruncate(walFilePath, EngineMutationLogTest.this.kryoPool, walFileReference).logFileRecordReference()
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
			assertEquals(
				0, AbstractMutationLog.getIndexFromWalFileName("evitaDB_0.wal"),
				"Should extract index 0 from engine WAL filename"
			);
		}

		@Test
		@DisplayName("should throw exception for invalid WAL file names")
		void shouldThrowExceptionForInvalidWalFileNames() {
			GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName("invalid_name.wal")
			);
			assertTrue(exception.getMessage().contains("Invalid WAL file name"));

			exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName(".wal")
			);
			assertTrue(exception.getMessage().contains("Invalid WAL file name"));

			exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> AbstractMutationLog.getIndexFromWalFileName("testCatalog_.wal")
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
			final int[] txSizes = new int[]{1, 2, 1};
			writeEngineWal(txSizes, null);

			final long firstVersion = EngineMutationLogTest.this.wal.getFirstVersionOf(0);
			assertEquals(1L, firstVersion, "Should return version 1 as the first version in the WAL file");
		}

		@Test
		@DisplayName("should return -1 for non-existing WAL file")
		void shouldReturnMinusOneForNonExistingWalFile() {
			final long firstVersion = EngineMutationLogTest.this.wal.getFirstVersionOf(999);
			assertEquals(-1L, firstVersion, "Should return -1 when WAL file does not exist");
		}

		@Test
		@DisplayName("should return -1 for empty WAL file")
		void shouldReturnMinusOneForEmptyWalFile() throws IOException {
			final int emptyWalIndex = 1;
			final Path emptyWalPath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(emptyWalIndex));
			assertTrue(emptyWalPath.toFile().createNewFile());

			try {
				final long firstVersion = EngineMutationLogTest.this.wal.getFirstVersionOf(emptyWalIndex);
				assertEquals(-1L, firstVersion, "Should return -1 when WAL file is empty");
			} finally {
				assertTrue(emptyWalPath.toFile().delete());
			}
		}
	}

	/**
	 * Nested tests for WAL descriptor functionality.
	 */
	@Nested
	@DisplayName("WAL Descriptor Tests")
	class WalDescriptorTests {

		@Test
		@DisplayName("should return valid engine WAL description")
		void shouldReturnEngineWallDescription() {
			final int[] txSizes = new int[]{1, 2, 1};
			final List<TxData> txData = writeEngineWal(txSizes, null);
			assertEquals(3, txData.size());

			final LongHashSet versions = new LongHashSet(8);
			versions.add(3L);
			final List<WriteAheadLogVersionDescriptor> descriptors = EngineMutationLogTest.this.wal.getWriteAheadLogVersionDescriptor(
				versions, new MaterializedVersionBlock(3, 4, OffsetDateTime.now())
			);
			assertNotNull(descriptors);
			assertEquals(1, descriptors.size());

			final WriteAheadLogVersionDescriptor descriptor = descriptors.get(0);
			final TxData expectedTxData = txData.get(2);

			assertEquals(expectedTxData.version(), descriptor.version());

			final EngineTransactionChanges transactionChanges = (EngineTransactionChanges) descriptor.transactionChanges();
			assertEquals(expectedTxData.commitTimestamp(), transactionChanges.commitTimestamp());
			assertEquals(expectedTxData.mutationCount(), transactionChanges.mutationCount());
			assertEquals(expectedTxData.mutationSizeInBytes(), transactionChanges.walSizeInBytes());
			assertEquals(
				expectedTxData.expectedConcatenatedChanges(),
				String.join(", ", transactionChanges.changes())
			);
		}

		@Test
		@DisplayName("should return empty list for empty version set")
		void shouldReturnEmptyListForEmptyVersionSet() {
			final int[] txSizes = new int[]{1, 2, 1};
			writeEngineWal(txSizes, null);

			final LongHashSet emptyVersions = new LongHashSet(0);
			final List<WriteAheadLogVersionDescriptor> descriptors = EngineMutationLogTest.this.wal.getWriteAheadLogVersionDescriptor(
				emptyVersions, new MaterializedVersionBlock(1, 3, OffsetDateTime.now())
			);
			assertNotNull(descriptors);
			assertTrue(descriptors.isEmpty(), "Should return empty list for empty version set");
		}

		@Test
		@DisplayName("should return multiple descriptors for multiple versions in block")
		void shouldReturnMultipleDescriptorsForVersionsInBlock() {
			final int[] txSizes = new int[]{1, 2, 3};
			writeEngineWal(txSizes, null);

			final LongHashSet versions = new LongHashSet(8);
			versions.add(1L);
			versions.add(2L);
			versions.add(3L);
			final List<WriteAheadLogVersionDescriptor> descriptors = EngineMutationLogTest.this.wal.getWriteAheadLogVersionDescriptor(
				versions, new MaterializedVersionBlock(1, 3, OffsetDateTime.now())
			);
			assertNotNull(descriptors);
			assertEquals(3, descriptors.size(), "Should return 3 descriptors for 3 versions");
		}
	}

	/**
	 * Nested tests for mutation stream functionality.
	 */
	@Nested
	@DisplayName("Mutation Stream Tests")
	class MutationStreamTests {

		@Test
		@DisplayName("should read engine mutations via committed stream")
		void shouldReadEngineMutationsViaCommittedStream() {
			final int[] txSizes = new int[]{1, 2, 3};
			writeEngineWal(txSizes, null);

			final Iterator<EngineMutation<?>> iterator = EngineMutationLogTest.this.wal.getCommittedMutationStream(1).iterator();

			int transactionCount = 0;
			while (iterator.hasNext()) {
				final Mutation mutation = iterator.next();
				assertInstanceOf(TransactionMutation.class, mutation);
				final TransactionMutation txMutation = (TransactionMutation) mutation;
				transactionCount++;

				assertEquals(transactionCount, txMutation.getVersion());
				assertEquals(txSizes[transactionCount - 1], txMutation.getMutationCount());

				// Consume all mutations in this transaction
				for (int i = 0; i < txMutation.getMutationCount(); i++) {
					final Mutation innerMutation = iterator.next();
					assertInstanceOf(SetCatalogMutabilityMutation.class, innerMutation);
				}
			}

			assertEquals(txSizes.length, transactionCount);
		}

		@Test
		@DisplayName("should read engine mutations via reversed stream")
		void shouldReadEngineMutationsViaReversedStream() {
			final int[] txSizes = new int[]{1, 2, 3};
			writeEngineWal(txSizes, null);

			final Iterator<EngineMutation<?>> iterator = EngineMutationLogTest.this.wal.getCommittedReversedMutationStream(3).iterator();

			int expectedVersion = 3;
			int transactionCount = 0;
			int totalInnerMutations = 0;
			while (iterator.hasNext()) {
				final Mutation mutation = iterator.next();
				if (mutation instanceof TransactionMutation txMutation) {
					assertEquals(expectedVersion, txMutation.getVersion());
					expectedVersion--;
					transactionCount++;
				} else {
					assertInstanceOf(SetCatalogMutabilityMutation.class, mutation);
					totalInnerMutations++;
				}
			}

			assertEquals(3, transactionCount, "Should have read 3 transactions");
			assertEquals(6, totalInnerMutations, "Should have read 1+2+3=6 inner mutations");
		}
	}

	/**
	 * Nested tests for cumulative CRC32 checksum functionality.
	 */
	@Nested
	@DisplayName("Cumulative CRC32 Tests")
	class CumulativeCrc32Tests {

		@Test
		@DisplayName("should compute and store checksum for each transaction")
		void shouldComputeAndStoreChecksumForEachTransaction() {
			long previousChecksum = 0;
			for (int i = 1; i <= 3; i++) {
				final TransactionWithData txData = createRawTestTransaction(i - 1, 100 + i * 10);
				final LogFileRecordReference reference = EngineMutationLogTest.this.wal.append(txData.mutation(), txData.data());

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

		@Test
		@DisplayName("should include checksum in LogFileRecordReference")
		void shouldIncludeChecksumInReference() {
			final TransactionWithData txData = createRawTestTransaction(0, 100);
			final LogFileRecordReference reference = EngineMutationLogTest.this.wal.append(txData.mutation(), txData.data());

			assertEquals(0, reference.fileIndex(), "File index should be 0");
			assertNotEquals(0L, reference.cumulativeChecksum(), "Checksum should not be zero");
			assertNotNull(reference.fileLocation(), "File location should not be null");
			assertTrue(reference.fileLocation().recordLength() > 0, "Record length should be positive");
		}

		@Test
		@DisplayName("should handle checksum with multiple transactions")
		void shouldHandleChecksumWithMultipleTransactions() {
			final long[] checksums = new long[5];
			for (int i = 0; i < 5; i++) {
				final TransactionWithData txData = createRawTestTransaction(i, 50 + i * 25);
				final LogFileRecordReference reference = EngineMutationLogTest.this.wal.append(txData.mutation(), txData.data());
				checksums[i] = reference.cumulativeChecksum();
			}

			for (int i = 1; i < checksums.length; i++) {
				assertNotEquals(
					checksums[i - 1], checksums[i],
					"Each transaction should have a different cumulative checksum"
				);
			}
		}

		@Test
		@DisplayName("should write correct cumulative CRC32C checksum to WAL file")
		void shouldWriteCorrectCumulativeCrc32ChecksumToWalFile() throws IOException {
			final Path walFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));

			final TransactionWithData txData = createRawTestTransaction(0, 100);
			final LogFileRecordReference reference = EngineMutationLogTest.this.wal.append(txData.mutation(), txData.data());
			final long reportedChecksum = reference.cumulativeChecksum();

			EngineMutationLogTest.this.wal.close();

			// Verify the checksum is correctly stored at the end of the file
			try (final RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "r")) {
				final long fileSize = raf.length();

				raf.seek(fileSize - 8);
				final byte[] checksumBytes = new byte[8];
				raf.readFully(checksumBytes);
				final long storedChecksum = readLittleEndianLong(checksumBytes);

				assertEquals(reportedChecksum, storedChecksum, "Stored checksum should match reported");

				// Independently verify by computing CRC32C of all preceding bytes
				final int dataLength = (int) (fileSize - 8);
				raf.seek(0);
				final byte[] allDataBytes = new byte[dataLength];
				raf.readFully(allDataBytes);

				final Crc32CWrapper checksumCalculator = new Crc32CWrapper();
				checksumCalculator.withByteArray(allDataBytes);
				final long computedChecksum = checksumCalculator.getValue();

				assertEquals(reportedChecksum, computedChecksum, "Computed CRC32C should match reported checksum");
			}

			// Clean directory and recreate WAL for tearDown
			cleanTestSubDirectory(EngineMutationLogTest.class.getSimpleName());
			EngineMutationLogTest.this.walDirectory.toFile().mkdirs();
			EngineMutationLogTest.this.wal = createEngineWalWithLargeSize();
		}

		@Test
		@DisplayName("should write correct WAL tail with cumulative checksum on rotation")
		void shouldWriteCorrectWalTailWithCumulativeChecksumOnRotation() throws Exception {
			EngineMutationLogTest.this.wal.close();

			final long walFileSizeLimit = 4096L;
			final Path firstWalFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(0));
			final Path secondWalFilePath = EngineMutationLogTest.this.walDirectory.resolve(EnginePersistenceService.getWalFileName(1));

			long firstCatalogVersionInFile0 = -1;
			long lastCatalogVersionBeforeRotation = -1;

			try (EngineMutationLog smallWal = createEngineWalWithCustomSize(walFileSizeLimit)) {
				int transactionVersion = 1;

				while (!Files.exists(secondWalFilePath)) {
					final TransactionWithData txData = createRawTestTransaction(transactionVersion - 1, 200);
					smallWal.append(txData.mutation(), txData.data());

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

				assertEquals(firstCatalogVersionInFile0, storedFirstCv, "Stored firstCv should match");
				assertEquals(lastCatalogVersionBeforeRotation, storedLastCv, "Stored lastCv should match");
			}

			// Clean directory and recreate WAL for tearDown
			cleanTestSubDirectory(EngineMutationLogTest.class.getSimpleName());
			EngineMutationLogTest.this.walDirectory.toFile().mkdirs();
			EngineMutationLogTest.this.wal = createEngineWalWithLargeSize();
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

	/**
	 * Immutable holder for expected data of a single transaction used for assertions.
	 */
	private record TxData(
		long version,
		@Nonnull OffsetDateTime commitTimestamp,
		int mutationCount,
		long mutationSizeInBytes,
		@Nonnull String expectedConcatenatedChanges
	) {
	}
}
