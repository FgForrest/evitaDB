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
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;

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
@Tag(STORAGE)
@Tag(WAL)
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
			AbstractMutationLog.WalPurgeCallback.NO_OP
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
		return createTestWal(
			0L,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			walFileSizeBytes,
			10,
			AbstractMutationLog.WalPurgeCallback.NO_OP
		);
	}

	/**
	 * Creates a test instance of CatalogWriteAheadLog opened against an explicit log record reference. This is the
	 * shape a restart takes: the reference is whatever the catalog header found through the last published bootstrap
	 * record, and the WAL directory holds whatever rotation left behind since.
	 *
	 * @param catalogVersion         the catalog version the log resumes from
	 * @param logFileRecordReference the reference the log is opened against
	 * @param walFileSizeBytes       the maximum size of each WAL file in bytes
	 * @param walFileCountKept       how many WAL files rotation leaves unqueued for removal
	 * @param onWalPurgeCallback     invoked when a purge actually removes files; `removeWalFiles` dereferences it
	 *                               unconditionally, so a test that never purges still passes
	 *                               {@link AbstractMutationLog.WalPurgeCallback#NO_OP} rather than `null`
	 * @return a configured CatalogWriteAheadLog instance
	 */
	@Nonnull
	private CatalogWriteAheadLog createTestWal(
		long catalogVersion,
		@Nonnull LogFileRecordReference logFileRecordReference,
		long walFileSizeBytes,
		int walFileCountKept,
		@Nonnull AbstractMutationLog.WalPurgeCallback onWalPurgeCallback
	) {
		return new CatalogWriteAheadLog(
			catalogVersion,
			TEST_CATALOG,
			logFileRecordReference,
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder()
					.compress(false)
					.build(),
				TransactionOptions.builder()
					.walFileSizeBytes(walFileSizeBytes)
					.walFileCountKept(walFileCountKept)
					.build()
			),
			Mockito.mock(Scheduler.class),
			offsetDateTime -> {},
			onWalPurgeCallback
		);
	}

	/**
	 * Appends 200-byte transactions, one catalog version each starting at 1, until the given WAL file appears -
	 * i.e. until rotation has happened as many times as that file's index. Deterministic: the file size limit and
	 * the transaction size decide the count, nothing timing-dependent.
	 *
	 * @param wal            the log to append to
	 * @param awaitedWalFile the WAL file whose appearance ends the loop
	 * @param referenceAt    catalog version whose append reference is captured and returned
	 * @return the reference returned by the append of `referenceAt`, plus the last version written
	 */
	@Nonnull
	private RotatedWal appendUntilWalFileAppears(
		@Nonnull CatalogWriteAheadLog wal,
		@Nonnull Path awaitedWalFile,
		long referenceAt
	) {
		LogFileRecordReference capturedReference = null;
		long transactionVersion = 1;
		while (!Files.exists(awaitedWalFile)) {
			final TransactionWithData txData = createTestTransaction((int) (transactionVersion - 1), 200);
			final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
			if (transactionVersion == referenceAt) {
				capturedReference = reference;
			}
			transactionVersion++;
			if (transactionVersion > 500) {
				throw new AssertionError(
					"WAL did not rotate up to `" + awaitedWalFile + "` within the expected number of transactions!"
				);
			}
		}
		return new RotatedWal(
			Objects.requireNonNull(capturedReference, "No reference was captured for version " + referenceAt + "!"),
			transactionVersion - 1
		);
	}

	/**
	 * Outcome of {@link #appendUntilWalFileAppears(CatalogWriteAheadLog, Path, long)}.
	 *
	 * @param capturedReference reference of the append that was singled out as "the last checkpointed one"
	 * @param lastVersion       the last catalog version actually written
	 */
	private record RotatedWal(
		@Nonnull LogFileRecordReference capturedReference,
		long lastVersion
	) {
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

		/**
		 * The WAL channel is not opened with `DSYNC`; durability comes from one explicit `force` per
		 * append instead. That trades away write *ordering*: previously each `write` was synced on its
		 * own, so a crash could only ever leave a clean prefix, whereas now the writes of the append
		 * that was in flight may reach the device in any order and leave a **hole** — bytes missing in
		 * the middle of the last transaction while later bytes made it.
		 *
		 * Such a hole is confined to the last transaction (every force covers everything written before
		 * it), and that transaction was never acknowledged to a client, so losing it is correct. What
		 * must not happen is recovery reporting it as corruption: `WriteAheadLogCorruptedException` here
		 * is precisely the signature of the lost ordering guarantee and would turn a routine crash into
		 * an unopenable catalog. Recovery has to truncate instead.
		 *
		 * The hole is punched without changing the file length — a shortened file is the old, already
		 * covered torn-tail shape, whereas out-of-order writeback preserves the length.
		 *
		 * @param bytesBackFromEnd where the 8-byte hole starts, counted back from the end of the file
		 */
		@ParameterizedTest(name = "hole {0} bytes back from the end of the WAL")
		@ValueSource(ints = {9, 12, 16, 20, 24, 30, 36, 42, 48, 54, 60})
		@DisplayName("should truncate rather than report corruption when the last transaction has a hole")
		void shouldTruncateWhenLastTransactionHasHole(int bytesBackFromEnd) throws IOException {
			final long originalLength = modifyWalFile(raf -> {
				raf.seek(raf.length() - bytesBackFromEnd);
				raf.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
				return null;
			});

			assertEquals(
				originalLength, CatalogWriteAheadLogTest.this.walFilePath.toFile().length(),
				"Precondition: the hole must preserve the file length, otherwise this degenerates into " +
					"the torn-tail case already covered by shouldTruncatePartiallyWrittenWal"
			);

			assertDoesNotThrow(
				() -> CatalogWriteAheadLogTest.this.tested.checkAndTruncate(
					CatalogWriteAheadLogTest.this.walFilePath,
					CatalogWriteAheadLogTest.this.catalogKryoPool,
					CatalogWriteAheadLogTest.this.walFileReference
				),
				"A hole in the last (unacknowledged) transaction must be truncated, not surfaced as " +
					"corruption — throwing here means a crash mid-append leaves an unopenable catalog"
			);

			// without this the test would pass even if recovery never looked at the file: the damaged
			// transaction has to actually be gone, otherwise a corrupt record stays readable
			assertTrue(
				CatalogWriteAheadLogTest.this.walFilePath.toFile().length() < originalLength,
				"The holed transaction must actually be truncated away — file length stayed at " +
					originalLength + ", so the damage went undetected"
			);
		}

		/**
		 * Group commit widened the window the test above covers. With one force per append the unforced
		 * region was a single transaction, so a hole could only ever land in the last one. Now a force
		 * covers a whole *batch*, and every transaction appended since the previous force is unforced at
		 * once — so out-of-order writeback can leave a hole several transactions back from the end while
		 * the transactions after it landed intact.
		 *
		 * That is still safe, but for a reason worth stating: nothing in the unforced batch was ever
		 * acknowledged (clients are notified only after the force that covers them), so discarding the
		 * hole *and everything behind it* loses nothing a client was promised. What recovery must not do
		 * is throw, and it must not keep the transactions that follow the damage — those sit behind a gap
		 * in the cumulative checksum chain and would be read as valid history that never happened.
		 *
		 * @param bytesBackFromEnd where the 8-byte hole starts, counted back from the end of the file —
		 *                         far enough back to land in a transaction that is not the last one
		 */
		@ParameterizedTest(name = "hole {0} bytes back from the end, several transactions deep")
		@ValueSource(ints = {150, 250, 350, 450})
		@DisplayName("should truncate the whole unforced batch when a hole lands before its last transaction")
		void shouldTruncateWhenAnEarlierTransactionOfTheBatchHasHole(int bytesBackFromEnd) throws IOException {
			final long originalLength = modifyWalFile(raf -> {
				raf.seek(raf.length() - bytesBackFromEnd);
				raf.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
				return null;
			});
			final long holeOffset = originalLength - bytesBackFromEnd;

			assertEquals(
				originalLength, CatalogWriteAheadLogTest.this.walFilePath.toFile().length(),
				"Precondition: the hole must preserve the file length, otherwise this degenerates into " +
					"the torn-tail case already covered by shouldTruncatePartiallyWrittenWal"
			);
			assertTrue(
				holeOffset > 0,
				"Precondition: the fixture WAL must be long enough for the hole to land inside it"
			);

			assertDoesNotThrow(
				() -> CatalogWriteAheadLogTest.this.tested.checkAndTruncate(
					CatalogWriteAheadLogTest.this.walFilePath,
					CatalogWriteAheadLogTest.this.catalogKryoPool,
					CatalogWriteAheadLogTest.this.walFileReference
				),
				"A hole anywhere in the unforced batch must be truncated, not surfaced as corruption — " +
					"throwing here means a crash mid-batch leaves an unopenable catalog"
			);

			final long recoveredLength = CatalogWriteAheadLogTest.this.walFilePath.toFile().length();
			assertTrue(
				recoveredLength <= holeOffset,
				"Recovery must drop the damaged transaction AND every transaction appended after it — " +
					"the file was truncated to " + recoveredLength + " but the damage starts at " +
					holeOffset + ", so records sitting behind a gap in the checksum chain survived"
			);
		}

		/**
		 * Hole tolerance rests entirely on the cumulative CRC32C chain — it is the only thing that can tell
		 * a hole apart from valid data, because a hole preserves the file length and so leaves the
		 * transaction framing intact.
		 *
		 * That chain is optional. With {@link StorageOptions#computeCRC32C()} off, the storage layer swaps
		 * in `Checksum.NO_OP`, whose `equalsTo` unconditionally returns TRUE — so the scan waves every
		 * transaction through, and the damage surfaces only when the last transaction is deserialized,
		 * where `handleTransactionReadException` turns it into a hard failure instead of a truncation.
		 *
		 * This was harmless while the WAL was opened with `DSYNC`, because every write was synced in order
		 * and a crash could only ever leave a clean prefix — holes were not physically possible, so nothing
		 * depended on detecting them. Dropping DSYNC made hole tolerance a correctness requirement, and
		 * batching the force across several transactions widened the window it has to cover. The checksum
		 * therefore stopped being an integrity nicety and became load-bearing, and it must not be possible
		 * to switch it off.
		 */
		@Test
		@DisplayName("should demand write ordering from the device when CRC32C computation is switched off")
		void shouldRequireWriteOrderingWhenChecksumsAreDisabled() {
			assertTrue(
				AbstractMutationLog.requiresWriteOrdering(
					new StorageSettings(
						StorageOptions.builder().computeCRC32(false).build(),
						TransactionOptions.builder().build()
					)
				),
				"With no checksum chain a hole cannot be detected — verified by experiment: recovery does " +
					"not throw and does not truncate, it accepts the damaged transaction and replays it as " +
					"real history. The damage must therefore be made impossible instead, by syncing every " +
					"individual write so a crash can only leave a clean prefix"
			);
			assertFalse(
				AbstractMutationLog.requiresWriteOrdering(
					new StorageSettings(
						StorageOptions.builder().computeCRC32(true).build(),
						TransactionOptions.builder().build()
					)
				),
				"With the checksum chain present holes are detected and truncated, so the WAL may take the " +
					"fast path of one force per batch instead of one device sync per write"
			);
		}

		/**
		 * Regression test for the `Negative seek offset` crash observed in
		 * `LongRunningEvitaTransactionalFunctionalTest.shouldCorrectlyRotateAllFiles` on
		 * GitHub Actions run 25644343839: rotation cleanup invoked
		 * `getFirstAndLastVersionsFromWalFile` on a WAL file shorter than `WAL_TAIL_LENGTH`
		 * (e.g. a freshly created file that only received the 8-byte cumulative-checksum
		 * header but no transaction record yet), causing `walFile.length() - WAL_TAIL_LENGTH`
		 * to go negative and aborting the entire `append` call. The fix treats such files
		 * as corrupt by surfacing `WriteAheadLogCorruptedException` instead of letting the
		 * negative seek through.
		 */
		@Test
		@DisplayName("should reject too-short WAL file with corruption exception instead of negative seek")
		void shouldRejectTooShortWalFileWithCorruptionException() throws IOException {
			final Path orphanWalPath = CatalogWriteAheadLogTest.this.walDirectory.resolve(
				getWalFileName(TEST_CATALOG, 999)
			);
			// only the 8-byte cumulative-checksum header — no transactions, no tail
			Files.write(orphanWalPath, new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
			assertTrue(
				orphanWalPath.toFile().length() < AbstractMutationLog.WAL_TAIL_LENGTH,
				"Precondition: file must be shorter than the tail length to exercise the bug"
			);

			final WriteAheadLogCorruptedException exception = assertThrows(
				WriteAheadLogCorruptedException.class,
				() -> AbstractMutationLog.getFirstAndLastVersionsFromWalFile(
					orphanWalPath.toFile(), WriteAheadLogCorruptedException.WalKind.CATALOG
				),
				"Too-short WAL file should surface as corruption, not a negative-seek IOException"
			);
			assertTrue(
				exception.getMessage().contains(orphanWalPath.toFile().getName()),
				"Exception message should identify the offending file: " + exception.getMessage()
			);
		}
	}

	/**
	 * Reproduces a same-JVM visibility race between a WAL writer and a reader that already believes a
	 * given catalog version was durably written.
	 *
	 * A transaction-processing caller that treats {@code lastWrittenCatalogVersion == N} as a durable
	 * promise that transaction {@code N} is fully readable will ask the mutation stream for exactly that
	 * version. If that call returns a dry stream, such a caller can misread "nothing visible yet" as
	 * "already processed everything" and stop without retrying — which in turn leaves anything waiting for
	 * transaction {@code N} to be finalized spinning forever, since nothing will ever finalize it.
	 *
	 * These tests freeze the exact window deterministically: a transaction is appended through the
	 * real {@link CatalogWriteAheadLog#append} API (so it is genuinely, legitimately written — no
	 * mock WAL layer), then its trailing cumulative checksum alone is stripped off — the very last thing
	 * {@link AbstractMutationLog#append} writes, strictly after the header and content. This reproduces
	 * the on-disk state a torn/partially-visible flush would leave behind without depending on real
	 * thread timing.
	 *
	 * Unlike the sibling nested classes, these tests **consume the transaction content as mutations**.
	 * The shared {@link #setUp()} fixture fills transaction content with opaque test bytes — fine for
	 * header/positioning tests, but unreadable by Kryo — so the fixture below replaces the WAL with
	 * one whose content consists of genuinely serialized entity mutations written through the real
	 * isolated-WAL pipeline (see {@link CatalogWriteAheadLogIntegrationTest#writeWal}).
	 */
	@Nested
	@DisplayName("Dry-read visibility race: requested version durable but trailing checksum not yet visible")
	class DryReadVisibilityRaceTests {
		/**
		 * Off-heap memory manager backing the isolated-WAL writes of the real-mutation fixture.
		 */
		private final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			TEST_CATALOG, 10_000_000, 4, Crc32CChecksumFactory.INSTANCE
		);
		/**
		 * Keeper of observable outputs used by the isolated-WAL write handle.
		 */
		private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
			Mockito.mock(Scheduler.class)
		);

		/**
		 * Replaces the WAL created by the outer {@link #setUp()} with one holding the same number of
		 * transactions (catalog versions `1..txSizes.length`) whose content is real serialized entity
		 * mutations, so the mutation stream under test can deserialize what it reads.
		 */
		@BeforeEach
		void rebuildWalWithRealMutationContent() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
			CatalogWriteAheadLogTest.this.tested = createTestWal();
			CatalogWriteAheadLogIntegrationTest.writeWal(
				this.offHeapMemoryManager,
				CatalogWriteAheadLogTest.this.txSizes,
				null,
				CatalogWriteAheadLogTest.this.walDirectory.resolve("isolatedWal.tmp"),
				this.observableOutputKeeper,
				CatalogWriteAheadLogTest.this.tested
			);
		}

		@AfterEach
		void releaseWriteInfrastructure() {
			this.observableOutputKeeper.close();
		}

		@Test
		@DisplayName("getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(N,N) must not go dry for a " +
			"version whose content is on disk but whose trailing checksum has not landed yet")
		void shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum() throws IOException {
			// version = 1 + transactionIndex in setUp(), so the last transaction appended is at this version
			final long lastAppendedVersion = CatalogWriteAheadLogTest.this.txSizes.length;

			// strip only the trailing 8-byte cumulative checksum of the last transaction - its header
			// and content are fully durable on disk, exactly mirroring the moment append() has written
			// everything except the final checksum write
			modifyWalFile(raf -> {
				raf.setLength(raf.length() - AbstractMutationLog.CUMULATIVE_CRC32_SIZE);
				return null;
			});

			final List<CatalogBoundMutation> mutations;
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogTest.this.tested
					.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(lastAppendedVersion, lastAppendedVersion)
			) {
				mutations = stream.toList();
			}

			assertFalse(
				mutations.isEmpty(),
				"getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(" + lastAppendedVersion + ", " +
					lastAppendedVersion + ") returned a DRY stream even though transaction " +
					lastAppendedVersion + "'s header and content are durably on disk (only its trailing " +
					"checksum is momentarily missing). A caller that already believes this version is " +
					"written (as a production catalog-version tracker would) has no way to distinguish " +
					"this from \"nothing left to process\" and will silently drop the transaction instead " +
					"of retrying."
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
	 *
	 * Before reading anything here as an integrity guarantee, see the `checksum` field of
	 * {@link AbstractMutationLog} - it is the authoritative account of what these values detect, which is
	 * narrower than "cumulative" suggests. In short: damage inside a transaction, and nothing about ordering.
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

		/**
		 * Proves the stored checksum equals an independently computed CRC32C over every byte preceding it, which
		 * is exactly what the format specifies.
		 *
		 * It does **not** prove that the file is tamper-evident or that its transactions are pinned in place, and
		 * it must not be cited for either - `shouldNotDetectReorderedTransactions` below shows why. The two tests
		 * are deliberately adjacent: this one is the reason the misreading is tempting, that one is the correction.
		 */
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

		/**
		 * Pins what the cumulative checksum actually detects, which is less than the chain suggests.
		 *
		 * Each stored checksum genuinely is the CRC32C of every byte preceding it - `shouldWriteCorrectCumulativeCrc32
		 * ChecksumToWalFile` verifies exactly that against an independently computed CRC. The catch is that this
		 * coverage includes the previous stored checksums, and `CRC(M ‖ CRC(M))` is the CRC residue: a constant,
		 * whatever `M` was. The running register therefore returns to that same constant at every stored checksum, so
		 * each value ends up determined by the constant plus its own transaction's bytes and nothing earlier.
		 *
		 * What that catches: damage **inside** a transaction whose stored checksum was not also rewritten. What it
		 * cannot catch is below - whole transactions exchanged, duplicated, or spliced in from another file, since
		 * each carries a checksum that verifies from the same constant wherever it is placed.
		 *
		 * **That gap is closed elsewhere, not here.** Every transaction carries the catalog version it produces, and
		 * trunk incorporation asserts each one is exactly the next expected version
		 * ({@link io.evitadb.core.transaction.TransactionManager}, "Unexpected catalog version!"). Exchanged,
		 * duplicated, dropped and cross-file-spliced transactions all break that sequence and are rejected loudly.
		 * So this test pins where the *checksum's* reach ends, not a hole in the engine - and the reason it asserts
		 * acceptance rather than rejection is that the WAL layer is not the layer that says no.
		 */
		@Test
		@DisplayName("should not detect two whole transactions exchanged - the chain resets at every checksum")
		void shouldNotDetectReorderedTransactions() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path walPath = CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 0));

			// three transactions with identical payload sizes, so each occupies exactly the same number of bytes and
			// two of them can be exchanged without disturbing a single length prefix
			try (CatalogWriteAheadLog wal = createTestWal()) {
				for (int i = 0; i < 3; i++) {
					final TransactionWithData txData = createTestTransaction(i, 200);
					wal.append(txData.mutation(), txData.data());
				}
			}

			final byte[] original = Files.readAllBytes(walPath);
			final int header = AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
			final int blockLength = (original.length - header) / 3;
			assertEquals(
				header + blockLength * 3, original.length,
				"Precondition: the three transactions must be equally long for the exchange to be byte-neutral."
			);

			// exchange the first two transactions, each carried over whole - its length prefix, its records and its
			// own trailing cumulative checksum
			final byte[] tampered = original.clone();
			System.arraycopy(original, header + blockLength, tampered, header, blockLength);
			System.arraycopy(original, header, tampered, header + blockLength, blockLength);
			Files.write(walPath, tampered);

			// reopening runs `checkAndTruncate`, which verifies every transaction's cumulative checksum in turn
			try (CatalogWriteAheadLog ignored = createTestWal(
				0L, new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)), 1_048_576L, 10,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				// nothing to do - construction is what scans the file
			}

			assertFalse(
				Files.exists(CatalogWriteAheadLogTest.this.walDirectory.resolve("_damaged_wal.bck")),
				"The swap is currently accepted, so no damaged-file backup may be produced - a backup here means " +
					"the chain started detecting reordering and this test is now the one that is out of date."
			);
			assertEquals(
				original.length, walPath.toFile().length(),
				"The swap is currently accepted, so nothing may be truncated off the file."
			);

			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
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
	 * Restart behaviour when the log has moved past the position a restart resumes from - either by rotating into
	 * later files, or by appending transactions the trunk never incorporated.
	 *
	 * A catalog resumes from the last **published bootstrap record**, not from the newest state it had in memory:
	 * `DefaultCatalogPersistenceService#close` deliberately leaves that pointer at the last checkpoint, "which
	 * simply means restart resumes from there and replays the rest from the WAL". Everything appended since is the
	 * tail replay consumes, and it may span several files.
	 *
	 * These tests pin the parts of that contract independently: rotation must never drop the file the reference
	 * needs (which would leave an unbridgeable gap), reopening against such a reference must work, and a
	 * transaction appended after the restart must survive the next restart intact.
	 *
	 * Note what is deliberately **not** claimed here: that appends continue a checksum chain. Each transaction's
	 * trailing checksum covers only its own bytes - the running value returns to a fixed constant after every
	 * record, so no test in this class can distinguish one append-chain seed from another. The authoritative
	 * account of what that checksum does and does not cover is on `AbstractMutationLog#checksum`.
	 */
	@Nested
	@DisplayName("Restart from a position the log has moved past")
	class RestartAfterRotation {

		@Test
		@DisplayName("should resume replay when the reference is the last transaction of a rotated file")
		void shouldResumeReplayWhenReferenceIsLastTransactionOfRotatedFile() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			// small enough that a handful of 200-byte transactions rotate the file more than once
			final long walFileSizeLimit = 1_000L;

			LogFileRecordReference lastInFirstFile = null;
			long lastVersionInFirstFile = -1L;
			try (CatalogWriteAheadLog wal = createTestWal(
				0L, new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)), walFileSizeLimit, 10,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				for (int version = 1; version <= 8; version++) {
					final TransactionWithData txData = createTestTransaction(version - 1, 200);
					final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
					if (reference.fileIndex() == 0) {
						lastInFirstFile = reference;
						lastVersionInFirstFile = version;
					}
				}
			}
			final LogFileRecordReference processedReference = Objects.requireNonNull(lastInFirstFile);
			assertTrue(
				CatalogWriteAheadLogTest.this.walDirectory.resolve(getWalFileName(TEST_CATALOG, 1)).toFile().exists(),
				"Precondition: rotation must have produced a second WAL file."
			);

			// the checkpoint landed exactly on the rotation boundary: everything in file 0 is processed and the
			// remainder lives in file 1. Replay must cross that boundary rather than read into the finalized tail
			try (CatalogWriteAheadLog reopened = createTestWal(
				lastVersionInFirstFile, processedReference, walFileSizeLimit, 10,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				assertEquals(
					lastVersionInFirstFile + 1,
					reopened.getFirstNonProcessedTransaction(processedReference)
						.orElseThrow(() -> new AssertionError("Replay found nothing after the reference!"))
						.transactionMutation()
						.getVersion(),
					"replay must continue into the next WAL file when the reference ends the previous one"
				);
			}
		}

		@Test
		@DisplayName("should resume replay and keep appending when the reference lags inside the active file")
		void shouldResumeReplayAndAppendWhenReferenceLagsInsideActiveFile() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path walFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 0));
			final long walFileSizeLimit = 1_048_576L;

			// four transactions land in one file, but only the second was ever incorporated - which is what a
			// crash leaves behind whenever appends outran the trunk rounds processing them
			LogFileRecordReference afterSecond = null;
			LogFileRecordReference afterFourth = null;
			try (CatalogWriteAheadLog wal = createTestWal(
				0L, new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)), walFileSizeLimit, 10,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				for (int version = 1; version <= 4; version++) {
					final TransactionWithData txData = createTestTransaction(version - 1, 200);
					final LogFileRecordReference reference = wal.append(txData.mutation(), txData.data());
					if (version == 2) {
						afterSecond = reference;
					} else if (version == 4) {
						afterFourth = reference;
					}
				}
			}
			final LogFileRecordReference processedReference = Objects.requireNonNull(afterSecond);
			final LogFileRecordReference lastAppendedReference = Objects.requireNonNull(afterFourth);

			// the restart resumes from transaction 2; transactions 3 and 4 are the tail replay consumes
			final LogFileRecordReference postRestartReference;
			try (CatalogWriteAheadLog reopened = createTestWal(
				2L, processedReference, walFileSizeLimit, 10, AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				assertEquals(
					3L,
					reopened.getFirstNonProcessedTransaction(processedReference)
						.orElseThrow(() -> new AssertionError("Replay found nothing after the reference!"))
						.transactionMutation()
						.getVersion(),
					"replay must resume at the first transaction the trunk never incorporated"
				);

				final TransactionWithData txData = createTestTransaction(4, 200);
				postRestartReference = reopened.append(txData.mutation(), txData.data());
			}
			final long walFileLengthAfterRestart = walFile.toFile().length();

			// A third open is what would discover a transaction 5 the reopen wrote wrongly - a scan rejects the
			// file outright, or quietly truncates the offending transaction back off it. Both assertions below
			// are therefore needed: either outcome alone would let the other pass unnoticed. This covers the
			// reopen path where `checkAndTruncate` mints a corrected reference and the constructor adopts it,
			// which no other test in this class exercises.
			try (CatalogWriteAheadLog reopenedAgain = createTestWal(
				5L, postRestartReference, walFileSizeLimit, 10, AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				assertEquals(
					5L,
					reopenedAgain.getFirstNonProcessedTransaction(lastAppendedReference)
						.orElseThrow(() -> new AssertionError("The transaction appended after the restart is gone!"))
						.transactionMutation()
						.getVersion(),
					"the transaction appended after the restart must still be readable"
				);
			}
			assertEquals(
				walFileLengthAfterRestart, walFile.toFile().length(),
				"reopening must not truncate the transaction appended after the restart - a shorter file means " +
					"the scan rejected it and silently repaired the file"
			);

			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should keep the WAL file holding the first non-processed transaction")
		void shouldKeepWalFileHoldingFirstNonProcessedTransaction() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path firstWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 0));
			final Path fourthWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 3));

			// only two files are kept, so rotation queues files 0 and 1 for removal - but the queue is
			// version-gated, and version 4 (still unprocessed) lives in file 0
			final CatalogWriteAheadLog wal = createTestWal(
				0L, new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)), 4_096L, 2,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			);
			try {
				appendUntilWalFileAppears(wal, fourthWalFile, 3L);
				assertTrue(
					wal.getFirstVersionOf(1) > 4L,
					"test setup: version 4 must still live in file 0 for this assertion to mean anything"
				);

				wal.walProcessedUntil(3L);
			} finally {
				// close() runs the purge (AbstractMutationLog#close), so the assertions come after it
				wal.close();
			}

			assertTrue(
				Files.exists(firstWalFile),
				"file 0 holds version 4, the first non-processed transaction - purging it would leave a gap " +
					"that replay after a restart could never bridge"
			);

			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should purge rotated WAL files once every transaction in them is processed")
		void shouldPurgeWalFilesOnceEveryTransactionInThemIsProcessed() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path firstWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 0));
			final Path secondWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 1));
			final Path fourthWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 3));

			// the calibration of the test above: with the same rotation and the same kept-count, the two queued
			// files DO disappear once nothing unprocessed is left in them. Without this, that test would pass
			// just as happily if the purge never ran at all.
			final CatalogWriteAheadLog wal = createTestWal(
				0L, new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)), 4_096L, 2,
				AbstractMutationLog.WalPurgeCallback.NO_OP
			);
			try {
				appendUntilWalFileAppears(wal, fourthWalFile, 3L);
				// every version below the first one in file 2 is contained in files 0 and 1
				wal.walProcessedUntil(wal.getFirstVersionOf(2));
			} finally {
				wal.close();
			}

			assertFalse(Files.exists(firstWalFile), "file 0 is fully processed and must be purged");
			assertFalse(Files.exists(secondWalFile), "file 1 is fully processed and must be purged");

			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();
			CatalogWriteAheadLogTest.this.tested = createTestWal();
		}

		@Test
		@DisplayName("should reopen against a reference that sits several rotations back")
		void shouldReopenAgainstReferenceSeveralRotationsBack() throws IOException {
			CatalogWriteAheadLogTest.this.tested.close();
			cleanTestSubDirectory(CatalogWriteAheadLogTest.class.getSimpleName());
			CatalogWriteAheadLogTest.this.walDirectory.toFile().mkdirs();

			final Path firstWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 0));
			final Path fourthWalFile = CatalogWriteAheadLogTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, 3));

			final LogFileRecordReference checkpointedReference;
			final long lastWrittenVersion;
			final long firstWalFileLengthBeforeRestart;

			// nothing is purged here - the point is the reference's distance from the newest file, not its absence
			try (CatalogWriteAheadLog wal = createTestWalWithCustomSize(4_096L)) {
				final RotatedWal rotated = appendUntilWalFileAppears(wal, fourthWalFile, 3L);
				checkpointedReference = rotated.capturedReference();
				lastWrittenVersion = rotated.lastVersion();
			}
			firstWalFileLengthBeforeRestart = firstWalFile.toFile().length();

			// this is what a restart does: open against the reference the last checkpoint left behind
			final LogFileRecordReference postRestartReference;
			try (CatalogWriteAheadLog reopened = createTestWal(
				3L, checkpointedReference, 4_096L, 10, AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				assertEquals(
					4L,
					reopened.getFirstNonProcessedTransaction(checkpointedReference)
						.orElseThrow(() -> new AssertionError("Replay found nothing after the reference!"))
						.transactionMutation()
						.getVersion(),
					"replay must resume at the transaction right after the checkpointed one"
				);

				final TransactionWithData txData = createTestTransaction((int) lastWrittenVersion, 200);
				postRestartReference = reopened.append(txData.mutation(), txData.data());
			}

			assertEquals(
				firstWalFileLengthBeforeRestart, firstWalFile.toFile().length(),
				"a restart must not truncate or append to an already rotated-away WAL file - appends belong in " +
					"the newest file, and file 0's tail record is not a partially written transaction"
			);
			assertEquals(
				3, postRestartReference.fileIndex(),
				"the post-restart append belongs in the newest WAL file, not in the one the reference points at"
			);

			// Opening a third time is what proves the APPEND chain was seeded correctly: the scan recomputes each
			// transaction's cumulative checksum from the file's stored seed and rejects the file when one does not
			// match. A restart that continued the reference's chain instead of the active file's would write a
			// transaction whose checksum is wrong, and this is the open that finds it.
			try (CatalogWriteAheadLog reopenedAgain = createTestWal(
				lastWrittenVersion + 1, postRestartReference, 4_096L, 10, AbstractMutationLog.WalPurgeCallback.NO_OP
			)) {
				assertTrue(
					reopenedAgain.getFirstNonProcessedTransaction(postRestartReference).isEmpty(),
					"nothing follows the transaction appended after the restart"
				);
			}

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
