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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTooBigException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.transaction.WalRotationEvent;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.store.wal.supplier.ReverseMutationSupplier;
import io.evitadb.store.wal.supplier.TransactionLocations;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.IOUtils.IOExceptionThrowingRunnable;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * AbstractMutationLog is a base class that provides shared functionality for implementing Write-Ahead Logging (WAL)
 * in the evitaDB system. It contains common logic used by both Catalog and Engine WAL implementations.
 *
 * A Write-Ahead Log is a durability mechanism used in database systems to ensure that transaction data is preserved
 * even in the event of system failures. The fundamental principle is that changes are written to the log before they
 * are applied to the database itself, hence the name "write-ahead".
 *
 * Databases use write-ahead logging as the first step of committed transactions for several key reasons:
 *
 * 1. Durability - By writing changes to a sequential log file before modifying the actual database structures,
 *    the system ensures that committed transactions can be recovered even if a crash occurs during the actual
 *    data modification process.
 *
 * 2. Atomicity - The WAL allows the database to ensure that transactions are atomic (either fully completed or
 *    not applied at all) by tracking the state of each transaction.
 *
 * 3. Performance - Sequential writes to a log file are typically faster than writes to multiple database structures,
 *    allowing for quicker transaction commits while maintaining durability guarantees.
 *
 * This abstract class provides functionality for:
 * - Creating and managing WAL files
 * - Appending transaction mutations to the WAL
 * - Rotating WAL files when they reach a certain size
 * - Reading mutations from the WAL for recovery purposes
 * - Cleaning up obsolete WAL files
 * - Handling WAL file corruption
 *
 * Concrete implementations of this class (CatalogWriteAheadLog and EngineMutationLog) provide specific
 * functionality for different parts of the evitaDB system.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public abstract class AbstractMutationLog<T extends Mutation> implements AutoCloseable {
	/**
	 * The size of a transaction mutation in the WAL file.
	 */
	public static final int TRANSACTION_MUTATION_SIZE = StorageRecord.OVERHEAD_SIZE + TransactionMutationSerializer.RECORD_SIZE;
	/**
	 * The size of a transaction mutation in the WAL file with a reserve for the class id written by Kryo automatically.
	 */
	public static final int TRANSACTION_MUTATION_SIZE_WITH_RESERVE = AbstractMutationLog.TRANSACTION_MUTATION_SIZE + 32;
	/**
	 * At the end of each WAL file there is 3 longs written with start and end catalog version in the WAL:
	 *
	 * - first catalog version mutation of the WAL file
	 * - last catalog version mutation of the WAL file
	 * - CRC32C checksum of the both versions
	 */
	public static final int WAL_TAIL_LENGTH = 8 + 8 + 8;
	/**
	 * The time after which the WAL cache is dropped after inactivity.
	 */
	protected static final long CUT_WAL_CACHE_AFTER_INACTIVITY_MS = 300_000L; // 5 minutes
	/**
	 * A flag indicating whether to compute CRC32 checksums for the written data in the WAL.
	 */
	protected final boolean computeCRC32C;
	/**
	 * Reference to the current WAL file information record.
	 */
	protected final AtomicReference<CurrentMutationLogFile> currentWalFile = new AtomicReference<>();
	/**
	 * Contains reference to the asynchronous task executor that clears the cached WAL pointers after some
	 * time of inactivity.
	 */
	protected final DelayedAsyncTask cutWalCacheTask;
	/**
	 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
	 */
	protected final Pool<Kryo> kryoPool;
	/**
	 * Size of the Write-Ahead Log (WAL) file in bytes before it is rotated.
	 */
	protected final long maxWalFileSizeBytes;
	/**
	 * Contains information about the last catalog version that was successfully stored (and its WAL contents processed).
	 */
	protected final AtomicLong processedVersion;
	/**
	 * Contains reference to the asynchronous task executor that removes the obsolete WAL files.
	 */
	protected final DelayedAsyncTask removeWalFileTask;
	/**
	 * Path to the catalog storage folder where the WAL file is stored.
	 */
	protected final Path storageFolder;
	/**
	 * The storage options for the catalog.
	 */
	protected final StorageOptions storageOptions;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	protected final ByteArrayOutputStream transactionMutationOutputStream = new ByteArrayOutputStream(
		AbstractMutationLog.TRANSACTION_MUTATION_SIZE);
	/**
	 * Number of WAL files to keep.
	 */
	protected final int walFileCountKept;
	/**
	 * The function that provides the name of the WAL file based on its index.
	 */
	protected final IntFunction<String> walFileNameProvider;
	/**
	 * Buffer used to serialize the overall content length and {@link TransactionMutation} at the front of the
	 * transaction log.
	 *
	 * @see ByteBuffer
	 */
	private final ByteBuffer contentLengthBuffer = ByteBuffer.allocate(4 + AbstractMutationLog.TRANSACTION_MUTATION_SIZE_WITH_RESERVE);
	/**
	 * Cache of already scanned WAL files. The locations might not be complete, but they will be always cover the start
	 * of the particular WAL file, but they may be later appended with new records that are not yet scanned or gradually
	 * added to the working WAL file. The index key in this map is the {@link CurrentMutationLogFile#getWalFileIndex()} of the WAL file.
	 *
	 * For each existing file there is at least single with the last read position of the file - so that the trunk
	 * incorporation doesn't need to scan the file from the beginning.
	 */
	private final ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache = CollectionUtils.createConcurrentHashMap(16);
	/**
	 * List of pending removals of WAL files that should be removed, but could not be removed yet because the WAL
	 * records in them were not yet processed.
	 */
	private final Queue<PendingRemoval> pendingRemovals = new ConcurrentLinkedDeque<>();

	/**
	 * Returns the index extracted from the given Write-Ahead-Log file name.
	 *
	 * @param walFileName the name of the WAL file
	 * @return the index extracted from the WAL file name
	 */
	public static int getIndexFromWalFileName(@Nonnull String walFileName) {
		int endIndex = walFileName.length() - WAL_FILE_SUFFIX.length();
		int startIndex = endIndex;
		while (startIndex > 0 && Character.isDigit(walFileName.charAt(startIndex - 1))) {
			startIndex--;
		}

		Assert.isPremiseValid(
			startIndex < endIndex,
			() -> new GenericEvitaInternalError(
				"Invalid WAL file name `" + walFileName + "`! Cannot extract index from it!"
			)
		);

		return Integer.parseInt(walFileName, startIndex, endIndex, 10);
	}

	/**
	 * Creates a Write-Ahead Log (WAL) file if it does not already exist.
	 *
	 * @param walFilePath The path of the WAL file.
	 * @throws IOException If an I/O error occurs.
	 */
	protected static void createWalFile(@Nonnull Path walFilePath, boolean mayExist) throws IOException {
		final File walFile = walFilePath.toFile();
		if (!walFile.exists()) {
			final File parentDirectory = walFilePath.getParent().toFile();
			if (!parentDirectory.exists()) {
				Assert.isPremiseValid(
					parentDirectory.mkdirs(),
					"Failed to create parent directory `" + parentDirectory + "` for WAL file `" + walFilePath + "`!"
				);
			}
			Assert.isPremiseValid(
				walFile.createNewFile(),
				"Failed to create WAL file `" + walFilePath + "`!"
			);
		} else if (!mayExist) {
			throw new WriteAheadLogCorruptedException(
				"WAL file `" + walFilePath + "` already exists, and it should not!",
				"WAL file already exists, and it should not!"
			);
		}
	}

	/**
	 * Method finds the last WAL file index in the catalog storage path.
	 *
	 * @param catalogStoragePath the path to the catalog storage folder
	 * @return the first and last WAL file index
	 */
	protected static int[] getFirstAndLastWalFileIndex(@Nonnull Path catalogStoragePath) {
		final File[] walFiles = ofNullable(
			catalogStoragePath.toFile().listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX))
		).orElse(new File[0]);

		if (walFiles.length == 0) {
			return new int[]{0, 0};
		} else {
			final List<Integer> indexes = Arrays.stream(walFiles)
			                                    .map(f -> AbstractMutationLog.getIndexFromWalFileName(f.getName()))
			                                    .sorted()
			                                    .toList();
			for (int i = 1; i < indexes.size(); i++) {
				Assert.isPremiseValid(
					indexes.get(i) == indexes.get(i - 1) + 1,
					"Missing WAL file with index `" + (indexes.get(i - 1) + 1) + "`!"
				);
			}
			return new int[]{indexes.get(0), indexes.get(indexes.size() - 1)};
		}
	}

	/**
	 * Returns the first and last catalog versions found in the given WAL file.
	 *
	 * @param walFile the WAL file to read from
	 * @return the first and last catalog versions found in the WAL file
	 */
	@Nonnull
	static FirstAndLastVersionsInWalFile getFirstAndLastVersionsFromWalFile(
		@Nonnull File walFile
	) throws FileNotFoundException {
		try (
			final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(walFile, "r");
			final RandomAccessFileInputStream inputStream = new RandomAccessFileInputStream(randomAccessOldWalFile, true);
			final Input input = new Input(inputStream)
		) {
			inputStream.seek(walFile.length() - AbstractMutationLog.WAL_TAIL_LENGTH);
			final long firstCatalogVersion = input.readLong();
			final long lastCatalogVersion = input.readLong();
			final long storedChecksum = input.readLong();

			// verify CRC32 checksum
			final CRC32C crc32c = new CRC32C();
			// Create a temporary buffer to hold the two longs for checksum calculation
			byte[] tempBuffer = new byte[16]; // 8 bytes for each long
			// Copy the first long
			for (int i = 0; i < 8; i++) {
				tempBuffer[i] = (byte) (firstCatalogVersion >>> (i << 3));
			}
			// Copy the second long
			for (int i = 0; i < 8; i++) {
				tempBuffer[i + 8] = (byte) (lastCatalogVersion >>> (i << 3));
			}
			// Update the checksum with the two longs
			crc32c.update(tempBuffer, 0, 16);
			// Get the calculated checksum value
			long calculatedChecksum = crc32c.getValue();

			// Verify the checksum
			if (calculatedChecksum != storedChecksum) {
				throw new WriteAheadLogCorruptedException(
					"CRC32 checksum mismatch in WAL file `" + walFile.getAbsolutePath() + "`!",
					"CRC32 checksum mismatch in WAL file!"
				);
			}

			return new FirstAndLastVersionsInWalFile(firstCatalogVersion, lastCatalogVersion);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to read `" + walFile.getAbsolutePath() + "`!",
				"Failed to read WAL file!", e
			);
		}
	}

	/**
	 * Checks if the Write-Ahead Log (WAL) file located at the specified path is either non-existing or empty.
	 *
	 * @param walFilePath the path to the WAL file, must not be null
	 * @return true if the file does not exist or its length is zero, false otherwise
	 */
	private static boolean isWalEmptyOrNonExisting(
		@Nonnull Path walFilePath
	) {
		final File file = walFilePath.toFile();
		return !file.exists() || file.length() == 0;
	}

	/**
	 * Creates an ObservableInput for reading from a WAL file with appropriate settings.
	 *
	 * @param walFile        The RandomAccessFile to read from
	 * @param storageOptions Options specifying storage behavior, such as compression and CRC32 computation
	 * @return A configured ObservableInput instance
	 */
	private static ObservableInput<RandomAccessFileInputStream> createObservableInput(
		@Nonnull RandomAccessFile walFile,
		@Nonnull StorageOptions storageOptions
	) {
		final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
			new RandomAccessFileInputStream(walFile, true)
		);

		if (storageOptions.computeCRC32C()) {
			input.computeCRC32();
		}
		if (storageOptions.compress()) {
			input.compress();
		}

		return input;
	}

	/**
	 * Scans a WAL file to find the last complete transaction.
	 *
	 * @param input       The input stream to read from
	 * @param fileLength  The length of the WAL file
	 * @param walFilePath The path to the WAL file (for logging purposes)
	 * @return A WalFileScanResult containing information about the last complete transaction
	 * @throws IOException If an I/O error occurs
	 */
	private static WalFileScanResult scanWalFileForLastCompleteTransaction(
		@Nonnull ObservableInput<RandomAccessFileInputStream> input,
		long fileLength,
		@Nonnull Path walFilePath
	) throws IOException {
		int transactionSize = 0;
		int previousTransactionSize = 0;
		long previousOffset = 0;
		long offset = 0;
		Exception readException = null;
		try {
			do {
				input.skip(transactionSize);
				previousTransactionSize = transactionSize;
				previousOffset = offset;
				transactionSize = input.simpleIntRead();
				final int debugTxSize = transactionSize;
				Assert.isPremiseValid(
					transactionSize > 0,
					() -> new WriteAheadLogCorruptedException(
						"Transaction size must be greater than zero, but is " + debugTxSize + "!",
						"Transaction size must be greater than zero!"
					)
				);
				offset += 4 + transactionSize;
			} while (offset < fileLength);
		} catch (Exception ex) {
			readException = ex;
		}

		final long lastTxStartPosition;
		final int lastTxLength;
		final long consistentLength;

		if (offset > fileLength || readException != null) {
			final Path backupFilePath = walFilePath.getParent().resolve("_damaged_wal.bck");
			if (readException == null) {
				AbstractMutationLog.log.warn(
					"WAL file `{}` was not written completely! Truncating to the last complete transaction (offset `{}`) and backing up the original file to: `{}`!",
					walFilePath, previousOffset, backupFilePath
				);
			} else {
				AbstractMutationLog.log.warn(
					"WAL file `{}` is damaged (cause: `{}`)! Truncating to the last complete transaction (offset `{}`) and backing up the original file to: `{}`!",
					walFilePath, readException.getMessage(), previousOffset, backupFilePath,
					readException
				);
			}
			Files.copy(walFilePath, backupFilePath);
			lastTxStartPosition = Math.max(0, previousOffset - previousTransactionSize - 4);
			lastTxLength = previousTransactionSize;
			consistentLength = previousOffset;
		} else {
			lastTxStartPosition = previousOffset;
			lastTxLength = transactionSize;
			consistentLength = offset;
		}

		return new WalFileScanResult(lastTxStartPosition, lastTxLength, consistentLength);
	}

	/**
	 * Reads and validates the first and last transactions from a WAL file.
	 *
	 * @param input               The input stream to read from
	 * @param kryo                The Kryo instance to use for deserialization
	 * @param lastTxStartPosition The start position of the last transaction
	 * @param lastTxLength        The length of the last transaction
	 * @return A TransactionPair containing the first and last transaction mutations
	 * @throws IOException If an I/O error occurs
	 */
	private static TransactionPair readAndValidateTransactions(
		@Nonnull ObservableInput<RandomAccessFileInputStream> input,
		@Nonnull Kryo kryo,
		long lastTxStartPosition,
		int lastTxLength
	) throws IOException {
		input.seekWithUnknownLength(4);
		final TransactionMutation firstTransactionMutation = Objects.requireNonNull(
			(TransactionMutation) StorageRecord.read(
				input, (stream, length) -> kryo.readClassAndObject(stream)
			).payload()
		);

		input.seekWithUnknownLength(lastTxStartPosition + 4);
		final TransactionMutation lastTransactionMutation = Objects.requireNonNull(
			(TransactionMutation) StorageRecord.read(
				input, (stream, length) -> kryo.readClassAndObject(stream)
			).payload()
		);

		final long calculatedTransactionSize = input.total() + lastTransactionMutation.getWalSizeInBytes();
		Assert.isPremiseValid(
			lastTxLength == calculatedTransactionSize,
			() -> new WriteAheadLogCorruptedException(
				"The transaction size `" + lastTxLength + "` does not match the actual size `" +
					calculatedTransactionSize + "`!",
				"The transaction size does not match the actual size!"
			)
		);

		return new TransactionPair(firstTransactionMutation, lastTransactionMutation);
	}

	/**
	 * Handles exceptions that occur when reading transactions from a WAL file.
	 *
	 * @param walFilePath The path to the WAL file
	 * @param ex          The exception that occurred
	 * @throws WriteAheadLogCorruptedException Always throws this exception with appropriate message
	 */
	private static void handleTransactionReadException(
		@Nonnull Path walFilePath,
		@Nonnull Exception ex
	) {
		AbstractMutationLog.log.error("Failed to read the last transaction from WAL file `{}`! The file is probably corrupted! The catalog will be marked as corrupted!", walFilePath, ex);
		if (ex instanceof WriteAheadLogCorruptedException) {
			// just rethrow
			throw (WriteAheadLogCorruptedException) ex;
		} else {
			// wrap original exception
			throw new WriteAheadLogCorruptedException(
				"Failed to read the last transaction from WAL file `" + walFilePath + "`! The file is probably" +
					" corrupted! The catalog will be marked as corrupted!",
				"Failed to read the last transaction from WAL file!",
				ex
			);
		}
	}

	/**
	 * Writes the WAL (Write Ahead Log) tail information to the specified WAL file.
	 * The method writes the first and last change versions specified to the tail of the WAL file.
	 * This is a static version of the writeWalTail method that creates its own ByteBuffer.
	 *
	 * @param firstCvInFile  the first change version present in the WAL file
	 * @param lastCvInFile   the last change version present in the WAL file
	 * @param walFileChannel the file channel of the WAL file to write to
	 * @throws IOException if an I/O error occurs while writing to the file channel
	 */
	private static void writeWalTailStatic(
		long firstCvInFile,
		long lastCvInFile,
		@Nonnull FileChannel walFileChannel
	) throws IOException {
		// Create a buffer for the WAL tail (3 longs: firstCvInFile, lastCvInFile, and CRC32C checksum)
		ByteBuffer contentLengthBuffer = ByteBuffer.allocate(AbstractMutationLog.WAL_TAIL_LENGTH);

		AbstractMutationLog.writeLongToByteBuffer(contentLengthBuffer, firstCvInFile);
		AbstractMutationLog.writeLongToByteBuffer(contentLengthBuffer, lastCvInFile);

		// Calculate CRC32 checksum from the two longs already in contentLengthBuffer
		CRC32C crc32c = new CRC32C();
		// Get position after writing both longs
		int position = contentLengthBuffer.position();
		// Flip buffer temporarily to read the data
		contentLengthBuffer.flip();
		crc32c.update(contentLengthBuffer.limit(position));
		// Reset position to continue writing
		contentLengthBuffer.limit(contentLengthBuffer.capacity());
		contentLengthBuffer.position(position);
		// Write the checksum as the third long
		AbstractMutationLog.writeLongToByteBuffer(contentLengthBuffer, crc32c.getValue());

		int written = 0;
		contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
		while (contentLengthBuffer.hasRemaining()) {
			written += walFileChannel.write(contentLengthBuffer);
		}
		Assert.isPremiseValid(
			written == AbstractMutationLog.WAL_TAIL_LENGTH,
			"Failed to write tail to WAL file!"
		);
	}

	/**
	 * Writes integer to the byte buffer, matching the procedure in {@link Output#writeInt(int)}.
	 *
	 * @param byteBuffer The byte buffer to write to.
	 * @param integer    The integer to write.
	 */
	private static void writeIntToByteBuffer(@Nonnull ByteBuffer byteBuffer, int integer) {
		byteBuffer.put((byte) integer);
		byteBuffer.put((byte) (integer >> 8));
		byteBuffer.put((byte) (integer >> 16));
		byteBuffer.put((byte) (integer >> 24));
	}

	/**
	 * Writes integer to the byte buffer, matching the procedure in {@link Output#writeLong(long)}.
	 *
	 * @param byteBuffer The byte buffer to write to.
	 * @param longValue  The long to write.
	 */
	private static void writeLongToByteBuffer(@Nonnull ByteBuffer byteBuffer, long longValue) {
		byteBuffer.put((byte) longValue);
		byteBuffer.put((byte) (longValue >>> 8));
		byteBuffer.put((byte) (longValue >>> 16));
		byteBuffer.put((byte) (longValue >>> 24));
		byteBuffer.put((byte) (longValue >>> 32));
		byteBuffer.put((byte) (longValue >>> 40));
		byteBuffer.put((byte) (longValue >>> 48));
		byteBuffer.put((byte) (longValue >>> 56));
	}

	public AbstractMutationLog(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull Pool<Kryo> kryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		this.walFileNameProvider = walFileNameProvider;
		this.kryoPool = kryoPool;
		this.processedVersion = new AtomicLong(version);
		this.storageOptions = storageOptions;
		this.cutWalCacheTask = this.createDelayedAsyncTask(
			"WAL cache cutter",
			scheduler,
			this::cutWalCache,
			CUT_WAL_CACHE_AFTER_INACTIVITY_MS
		);
		this.removeWalFileTask = this.createDelayedAsyncTask(
			"WAL file remover",
			scheduler,
			this::removeWalFiles,
			0
		);
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.storageFolder = storageFolder;
		this.computeCRC32C = storageOptions.computeCRC32C();
		final AtomicInteger currentWalFileIndex = new AtomicInteger(-1);
		try {
			final int[] firstAndLastWalFileIndex = getFirstAndLastWalFileIndex(storageFolder);
			currentWalFileIndex.set(firstAndLastWalFileIndex[1]);

			final AtomicReference<FirstAndLastVersionsInWalFile> lastVersion = new AtomicReference<>();
			for (int i = firstAndLastWalFileIndex[0]; i < firstAndLastWalFileIndex[1]; i++) {
				final Path walFilePath = storageFolder.resolve(walFileNameProvider.apply(i));
				final FirstAndLastVersionsInWalFile firstAndLastCatalogVersions = checkFinalizedWalFile(
					walFilePath, kryoPool, storageOptions
				);
				if (lastVersion.get() != null) {
					// the first transaction and the last transaction must follow up
					int walIndex = i;
					Assert.isPremiseValid(
						lastVersion.get().lastVersion() + 1 == firstAndLastCatalogVersions.firstVersion(),
						() -> new WriteAheadLogCorruptedException(
							walIndex,
							lastVersion.get().lastVersion(),
							firstAndLastCatalogVersions.firstVersion(),
							walFileNameProvider
						)
					);
				}
				lastVersion.set(firstAndLastCatalogVersions);
			}

			final Path walFilePath = storageFolder.resolve(walFileNameProvider.apply(currentWalFileIndex.get()));
			final FirstAndLastVersionsInWalFile versions = checkAndTruncate(walFilePath, kryoPool, storageOptions);
			if (lastVersion.get() != null) {
				Assert.isPremiseValid(
					lastVersion.get().lastVersion() + 1 == versions.firstVersion(),
					() -> new WriteAheadLogCorruptedException(
						currentWalFileIndex.get(),
						lastVersion.get().lastVersion(),
						versions.firstVersion(),
						walFileNameProvider
					)
				);
			}

			// create the WAL file if it does not exist
			createWalFile(walFilePath, true);

			final FileChannel walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);

			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				this.transactionMutationOutputStream,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);

			this.currentWalFile.set(
				new CurrentMutationLogFile(
					currentWalFileIndex.get(),
					versions.firstVersion(),
					versions.lastVersion(),
					walFilePath,
					walFileChannel,
					this.computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + walFileNameProvider.apply(currentWalFileIndex.get()) + "`!",
				"Failed to open the WAL file!",
				e
			);
		}
	}

	/**
	 * Constructor for internal use only. It is used to create a new WAL file with the given parameters.
	 */
	public AbstractMutationLog(
		long version,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path storageFolder,
		@Nonnull Pool<Kryo> kryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		int walFileIndex
	) {
		this.walFileNameProvider = walFileNameProvider;
		this.storageOptions = storageOptions;
		this.storageFolder = storageFolder;
		this.kryoPool = kryoPool;
		this.processedVersion = new AtomicLong(version);
		this.cutWalCacheTask = this.createDelayedAsyncTask(
			"WAL cache cutter",
			scheduler,
			this::cutWalCache,
			CUT_WAL_CACHE_AFTER_INACTIVITY_MS
		);
		this.removeWalFileTask = this.createDelayedAsyncTask(
			"WAL file remover",
			scheduler,
			this::removeWalFiles,
			0
		);
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.computeCRC32C = storageOptions.computeCRC32C();

		try {
			final Path walFilePath = storageFolder.resolve(walFileNameProvider.apply(walFileIndex));
			final FileChannel walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);

			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				this.transactionMutationOutputStream,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);

			this.currentWalFile.set(
				new CurrentMutationLogFile(
					walFileIndex,
					-1,
					-1,
					walFilePath,
					walFileChannel,
					this.computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + walFileNameProvider.apply(walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
	}

	/**
	 * Returns index of the current WAL file.
	 *
	 * @return index of the current WAL file
	 */
	public int getWalFileIndex() {
		return this.currentWalFile.get().getWalFileIndex();
	}

	/**
	 * Returns the path of the current WAL file.
	 *
	 * @return the path of the current WAL file
	 */
	@Nonnull
	public Path getWalFilePath() {
		return this.currentWalFile.get().getWalFilePath();
	}

	/**
	 * Method for internal use - allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents() {
		// emit the event with information about the first available transaction in the WAL
		final File firstWalFile = this.storageFolder.resolve(this.walFileNameProvider.apply(getWalFileIndex()))
		                                            .toFile();
		if (firstWalFile.length() > 0) {
			final TransactionMutation firstAvailableTransaction = getFirstTransactionMutationFromWalFile(
				firstWalFile
			);
			emitWalStatisticsEvent(firstAvailableTransaction.getCommitTimestamp());
		}
	}

	/**
	 * Retrieves the first catalog version of the current WAL file.
	 *
	 * @return the first catalog version of the current WAL file
	 */
	public long getFirstVersionOfCurrentWalFile() {
		return this.currentWalFile.get().getFirstVersionOfCurrentWalFile();
	}

	/**
	 * Retrieves the last written catalog version.
	 *
	 * @return the last written catalog version
	 */
	public long getLastWrittenVersion() {
		return this.currentWalFile.get().getLastWrittenVersion();
	}

	/**
	 * Appends a transaction mutation to the Write-Ahead Log (WAL) file.
	 *
	 * @param transactionMutation The transaction mutation to append.
	 * @param walReference        The reference to the WAL file.
	 * @return the number of Bytes written
	 */
	@Nonnull
	@SuppressWarnings("StringConcatenationMissingWhitespace")
	public LogFileRecordReference append(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		Assert.isTrue(
			transactionMutation.getWalSizeInBytes() <= this.maxWalFileSizeBytes,
			() -> new TransactionTooBigException(
				"Transaction size (`" + transactionMutation.getWalSizeInBytes() + "B`) exceeds the maximum WAL file size (`" + this.maxWalFileSizeBytes + "B`)! " +
					"Transactions cannot be split into multiple WAL files, so you need to extend the limit " +
					"for maximum WAL file size in evitaDB settings.",
				"Transaction size exceeds the maximum WAL file size! " +
					"Transactions cannot be split into multiple WAL files, so you need to extend the limit " +
					"for maximum WAL file size in evitaDB settings."
			)
		);
		Assert.isPremiseValid(
			walReference.getContentLength() == transactionMutation.getWalSizeInBytes(),
			() -> new TransactionException(
				"Transaction size (`" + transactionMutation.getWalSizeInBytes() + "B`) does not match the WAL reference size (`" + walReference.getContentLength() + "B`)!",
				"Transaction size does not match the WAL reference size!"
			)
		);

		final long currentWalFileSize = this.currentWalFile.get().getCurrentWalFileSize();
		final long newWalFileSize = currentWalFileSize + transactionMutation.getWalSizeInBytes() + 4;
		if (newWalFileSize > this.maxWalFileSizeBytes) {
			// rotate the WAL file
			rotateWalFile();
		}

		final Kryo kryo = this.kryoPool.obtain();
		try {
			// write transaction mutation to memory buffer
			this.transactionMutationOutputStream.reset();
			final CurrentMutationLogFile theCurrentWalFile = this.currentWalFile.get();
			theCurrentWalFile.checkNextVersionMatch(transactionMutation.getVersion());

			final ObservableOutput<ByteArrayOutputStream> output = theCurrentWalFile.getOutput();
			output.reset();

			// first write the transaction mutation to the memory byte array
			final StorageRecord<TransactionMutation> record = new StorageRecord<>(
				output,
				transactionMutation.getVersion(),
				false,
				theOutput -> {
					kryo.writeClassAndObject(theOutput, transactionMutation);
					return transactionMutation;
				}
			);
			output.flush();

			// write content length first
			final int contentLength = walReference.getContentLength();
			final int contentLengthWithTxMutation = contentLength + record.fileLocation().recordLength();
			this.contentLengthBuffer.clear();
			AbstractMutationLog.writeIntToByteBuffer(this.contentLengthBuffer, contentLengthWithTxMutation);

			// then write the transaction mutation to the memory buffer as the first record of the transaction
			this.contentLengthBuffer.put(this.transactionMutationOutputStream.toByteArray(), 0, record.fileLocation()
			                                                                                          .recordLength());

			// first write the contents of the byte buffer as the leading information in the shared WAL
			int writtenHead = 0;
			this.contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			final FileChannel walFileChannel = theCurrentWalFile.getWalFileChannel();
			while (this.contentLengthBuffer.hasRemaining()) {
				writtenHead += walFileChannel.write(this.contentLengthBuffer);
			}
			Assert.isPremiseValid(
				writtenHead == record.fileLocation().recordLength() + 4,
				"Failed to write content length to WAL file!"
			);

			// then copy the contents of the isolated WAL into the shared WAL and discard the isolated WAL
			int writtenContent = 0;
			try (walReference) {
				if (walReference.getBuffer().isPresent()) {
					// write the buffer contents from the buffer in case of off heap byte buffer
					final ByteBuffer byteBuffer = walReference.getBuffer().get();
					while (byteBuffer.hasRemaining()) {
						writtenContent += walFileChannel.write(byteBuffer);// Write buffer to file
					}
				} else if (walReference.getFilePath().isPresent()) {
					// write the file contents from the file in case of file reference
					try (
						final FileChannel readChannel = FileChannel.open(
							walReference.getFilePath().get(),
							StandardOpenOption.READ
						)
					) {
						while (writtenContent < contentLength) {
							writtenContent += Math.toIntExact(readChannel.transferTo(writtenContent, contentLength, walFileChannel));
						}
					}
				}
			}

			// verify the expected length of the transaction was written
			Assert.isPremiseValid(
				writtenContent == contentLength,
				"Failed to write all bytes (" + writtenContent + " of " + contentLength + " Bytes) to WAL file!"
			);

			theCurrentWalFile.initFirstVersionOfCurrentWalFileIfNecessary(
				transactionMutation.getVersion(),
				() -> {
					// emit the event with information about the first transaction in the WAL
					emitWalStatisticsEvent(transactionMutation.getCommitTimestamp());
				}
			);
			final int writtenLength = writtenHead + writtenContent;
			theCurrentWalFile.updateLastWrittenVersion(transactionMutation.getVersion(), writtenLength);

			// clean up the folder if empty
			walReference.getFilePath()
			            .map(Path::getParent)
			            .ifPresent(FileUtils::deleteFolderIfEmpty);

			return new LogFileRecordReference(
				this.walFileNameProvider,
				theCurrentWalFile.getWalFileIndex(),
				new FileLocation(currentWalFileSize, writtenLength)
			);

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to append WAL to `" + this.currentWalFile.get() + "`!",
				"Failed to append WAL!",
				e
			);
		} finally {
			this.kryoPool.free(kryo);
		}
	}

	/**
	 * Gets the reference to a WAL (Write-Ahead Log) file with the last processed WAL record position.
	 *
	 * @param lastProcessedTransaction The last processed WAL record position.
	 * @return The WAL file reference with last processed WAL record position.
	 */
	@Nonnull
	public LogFileRecordReference getWalFileReference(@Nullable TransactionMutation lastProcessedTransaction) {
		Assert.isPremiseValid(
			lastProcessedTransaction instanceof TransactionMutationWithLocation,
			"Invalid last processed transaction!"
		);
		final TransactionMutationWithLocation lastProcessedTransactionWithLocation = (TransactionMutationWithLocation) lastProcessedTransaction;
		return new LogFileRecordReference(
			this.walFileNameProvider,
			lastProcessedTransactionWithLocation.getWalFileIndex(),
			lastProcessedTransactionWithLocation.getTransactionSpan()
		);
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param startCatalogVersion the catalog version to start reading from
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<T> getCommittedMutationStream(long startCatalogVersion) {
		return getCommittedMutationStream(startCatalogVersion, null);
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * This method should be used for safe-reading of the WAL that is being appended with new records and is also
	 * read simultaneously. There is probably some bug in our observable input implementation that is revealed by
	 * filling the buffer incompletely with the data from the stream - example: the buffer is 16k long, but the next
	 * transaction takes only 2k and then file ends the observable input reads the transaction mutation, but in the
	 * meantime another transaction with 4k size has been written the 4k transaction is then failed to be read from the
	 * observable input, because the internal pointers are probably somehow misaligned.
	 *
	 * @param startCatalogVersion     the catalog version to start reading from
	 * @param requestedCatalogVersion the minimal catalog version to finish reading
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<T> getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(
		long startCatalogVersion, long requestedCatalogVersion) {
		return getCommittedMutationStream(startCatalogVersion, requestedCatalogVersion);
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version and going backwards towards
	 * the start of the history. Each transaction mutation will have catalog version lesser than the previous one.
	 * Mutations in the transaction are also returned in reverse order.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param version the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<T> getCommittedReversedMutationStream(long version) {
		final CurrentMutationLogFile theCurrentWalFile = this.currentWalFile.get();
		final File walFile = theCurrentWalFile.getWalFilePath().toFile();
		if (!walFile.exists() || walFile.length() < 4) {
			// WAL file does not exist or is empty, nothing to read
			return Stream.empty();
		} else {
			final ReverseMutationSupplier<T> supplier;
			if (this.getFirstVersionOfCurrentWalFile() <= version) {
				// we could start reading the current WAL file
				supplier = new ReverseMutationSupplier<>(
					version, this.walFileNameProvider, this.storageFolder, this.storageOptions,
					theCurrentWalFile.getWalFileIndex(), this.kryoPool, this.transactionLocationsCache,
					() -> emitCacheSizeEvent(this.transactionLocationsCache.size())
				);
			} else {
				// we need to find older WAL file
				final int foundWalIndex = findWalIndexFor(version);
				supplier = new ReverseMutationSupplier<>(
					version, this.walFileNameProvider, this.storageFolder, this.storageOptions,
					foundWalIndex, this.kryoPool, this.transactionLocationsCache,
					() -> emitCacheSizeEvent(this.transactionLocationsCache.size())
				);
			}
			this.cutWalCacheTask.schedule();
			return Stream.generate(supplier)
			             .takeWhile(Objects::nonNull)
			             .onClose(supplier::close);
		}
	}

	/**
	 * Returns UUID of the first transaction that is present in the WAL file and which transitions the catalog to
	 * the version that is greater by one than the current catalog version.
	 *
	 * @param walReference the current catalog version
	 * @return UUID of the first transaction that is present in the WAL file and which transitions the catalog to
	 */
	@Nonnull
	public Optional<TransactionMutationWithWalFileReference> getFirstNonProcessedTransaction(
		@Nullable LogFileRecordReference walReference
	) {
		final Path walFilePath;
		final int walFileIndex;
		final long startPosition;
		if (walReference == null) {
			walFileIndex = getWalFileIndex();
			walFilePath = this.storageFolder.resolve(this.walFileNameProvider.apply(walFileIndex));
			startPosition = 0L;
		} else {
			walFilePath = walReference.toFilePath(this.storageFolder);
			walFileIndex = walReference.fileIndex();
			final FileLocation fileLocation = Objects.requireNonNull(walReference.fileLocation());
			startPosition = fileLocation.endPosition();
		}
		final File walFile = walFilePath.toFile();
		if (walFile.exists() && walFile.length() > startPosition + 4) {
			try (
				final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(walFile, "r");
				final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
					new RandomAccessFileInputStream(randomAccessOldWalFile, true)
				)
			) {
				input.seekWithUnknownLength(startPosition + 4);
				final StorageRecord<Object> storageRecord = StorageRecord.read(
					input, (stream, length) -> this.kryoPool.obtain().readClassAndObject(stream)
				);
				final TransactionMutation transactionMutation = Objects.requireNonNull((TransactionMutation) storageRecord.payload());
				return of(
					new TransactionMutationWithWalFileReference(
						new LogFileRecordReference(
							this.walFileNameProvider, walFileIndex,
							new FileLocation(
								storageRecord.fileLocation().startingPosition(),
								Math.toIntExact((long)storageRecord.fileLocation().recordLength() + transactionMutation.getWalSizeInBytes())
							)
						),
						transactionMutation
					)
				);
			} catch (Exception e) {
				throw new WriteAheadLogCorruptedException(
					"Failed to read `" + walFilePath + "`!",
					"Failed to read WAL file!", e
				);
			}
		}
		return empty();
	}

	/**
	 * Calculates descriptor for particular version in history.
	 *
	 * @param version              the catalog version to describe
	 * @param previousKnownVersion the previous known catalog version (delimits transactions incorporated in
	 *                             previous version of the catalog), -1 if there is no known previous version
	 * @param introducedAt         the time when the version was introduced
	 * @return the descriptor for the version in history or NULL if the version is not present in the WAL
	 */
	@Nullable
	public abstract WriteAheadLogVersionDescriptor getWriteAheadLogVersionDescriptor(
		long version,
		long previousKnownVersion,
		@Nonnull OffsetDateTime introducedAt
	);

	/**
	 * Notify method that allows to track which versions of the catalog have been already processed and safe to be
	 * removed in the future.
	 *
	 * @param version the catalog version that has been processed
	 */
	public void walProcessedUntil(long version) {
		this.processedVersion.set(version);
		if (!this.pendingRemovals.isEmpty()) {
			this.removeWalFileTask.schedule();
		}
	}

	@Override
	public void close() throws IOException {
		this.currentWalFile.get().close();
		this.transactionMutationOutputStream.close();
		removeWalFiles();
	}

	/**
	 * Emits an event containing Write-Ahead Logging (WAL) statistics.
	 *
	 * @param commitTimestamp The timestamp representing the commit time of the transaction,
	 *                        must not be null.
	 */
	protected abstract void emitWalStatisticsEvent(@Nonnull OffsetDateTime commitTimestamp);

	/**
	 * Creates and returns a new instance of a WalRotationEvent. This method is
	 * abstract and is intended to be implemented by subclasses to provide the
	 * specific behavior for creating a write-ahead log (WAL) rotation event.
	 *
	 * @return a non-null instance of WalRotationEvent
	 */
	@Nonnull
	protected abstract WalRotationEvent createWalRotationEvent();

	/**
	 * Method fires event that actuates the cache size information in metrics.
	 * @param cacheSize the size of the cache to emit
	 */
	protected abstract void emitCacheSizeEvent(int cacheSize);

	/**
	 * Creates a delayed asynchronous task with the specified parameters.
	 *
	 * @param name             the name of the task, must not be null
	 * @param scheduler        the scheduler to use for managing the task, must not be null
	 * @param lambda           a supplier providing the delay or intervalInMillis value dynamically, must not be null
	 * @param intervalInMillis the fixed intervalInMillis for the task execution if applicable
	 * @return a configured instance of {@code DelayedAsyncTask}
	 */
	@Nonnull
	protected abstract DelayedAsyncTask createDelayedAsyncTask(
		@Nonnull String name,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier lambda,
		long intervalInMillis
	);

	/**
	 * Method checks if the WAL file is complete and if not, it truncates it. The non-complete WAL file record is
	 * recognized by scanning the file from the beginning and jumping from one read offset to another until the end
	 * is reached. If the last jump is ok, the last transaction consistency is verified and if it's ok, the file is
	 * assumed to be correct.
	 *
	 * If the last jump is not ok, we check the previous jump (last correctly finished transaction) and verify it
	 * instead. If it's ok, the file is backed up and truncated to the last correctly finished transaction.
	 *
	 * If the consistency of the "last" transaction is not ok, the WAL is considered as damaged and exception is thrown,
	 * which effectively leads to making the catalog as corrupted.
	 *
	 * @param walFilePath     the path to the WAL file to check and truncate
	 * @param catalogKryoPool the Kryo object pool to use for deserialization
	 * @param storageOptions  a flag indicating whether to compute CRC32C checksums for the input
	 * @return the last fully written catalog version found in the WAL file
	 */
	@Nonnull
	protected FirstAndLastVersionsInWalFile checkAndTruncate(
		@Nonnull Path walFilePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions
	) {
		if (AbstractMutationLog.isWalEmptyOrNonExisting(walFilePath)) {
			return new FirstAndLastVersionsInWalFile(-1L, -1L);
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try (
			final RandomAccessFile walFile = new RandomAccessFile(walFilePath.toFile(), "rw")
		) {
			// The previous process might have crashed before fsync.
			// If the DBMS doesn't fsync after opening, it may recover from data in the page cache (not yet durable),
			// and then externalize this as committed (e.g. to reads), violating Durability.
			walFile.getFD().sync();

			try (
				final ObservableInput<RandomAccessFileInputStream> input = AbstractMutationLog.createObservableInput(walFile, storageOptions)
			) {
				final WalFileScanResult scanResult = AbstractMutationLog.scanWalFileForLastCompleteTransaction(input, walFile.length(), walFilePath);

				try {
					final TransactionPair transactions = AbstractMutationLog.readAndValidateTransactions(
						input, kryo, scanResult.lastTxStartPosition, scanResult.lastTxLength
					);

					if (scanResult.consistentLength < walFile.length()) {
						truncateWalFile(walFilePath, walFile, scanResult.consistentLength);
					}

					return new FirstAndLastVersionsInWalFile(
						transactions.firstTransaction.getVersion(),
						transactions.lastTransaction.getVersion()
					);

				} catch (Exception ex) {
					AbstractMutationLog.handleTransactionReadException(walFilePath, ex);
					// This line is never reached as handleTransactionReadException always throws an exception
					throw new GenericEvitaInternalError("This code should never be reached");
				}
			}

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open WAL file `" + walFilePath + "`!",
				"Failed to open WAL file!",
				e
			);
		} finally {
			catalogKryoPool.free(kryo);
		}
	}

	/**
	 * Checks the finalized Write-Ahead Log (WAL) file to retrieve the latest catalog version.
	 * If the WAL file is corrupted, it truncates and recalculates the tail to determine the latest version.
	 *
	 * @param walFilePath     The file path to the WAL file. Must not be null.
	 * @param catalogKryoPool A pool of Kryo serializers for processing the catalog. Must not be null.
	 * @param storageOptions  Options for configuring storage behavior. Must not be null.
	 * @return The first and last catalog version determined after checking or truncating the WAL file.
	 */
	@Nonnull
	protected FirstAndLastVersionsInWalFile checkFinalizedWalFile(
		@Nonnull Path walFilePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions
	) {
		// first read and verify only a tail of the file
		try {
			final File walFile = walFilePath.toFile();
			return AbstractMutationLog.getFirstAndLastVersionsFromWalFile(walFile);
		} catch (WriteAheadLogCorruptedException e) {
			// WAL file is corrupted, we need to truncate it and calculate new tail
			return truncateWalFileAndCalculateTail(walFilePath, catalogKryoPool, storageOptions);
		} catch (FileNotFoundException e) {
			throw new WriteAheadLogCorruptedException(
				"WAL file `" + walFilePath + "` not found!",
				"WAL file not found!",
				e
			);
		}
	}

	/**
	 * Creates and returns a new instance of DataFileCompactEvent.
	 *
	 * @return a non-null instance of DataFileCompactEvent
	 */
	@Nonnull
	protected abstract DataFileCompactEvent createDataFileCompactEvent();

	/**
	 * Trims the Write-Ahead Log (WAL) file by ensuring only complete and consistent transactions are preserved
	 * and calculates the catalog versions of the first and last transaction within the WAL.
	 * This method verifies the integrity of the WAL file, handles corrupted or incomplete transactions,
	 * and emits a data file compact event if corrections are made.
	 *
	 * @param walFilePath     The path to the WAL file to be inspected and trimmed. Must not be null.
	 * @param catalogKryoPool A pool of Kryo instances used for reading serialized objects. Must not be null.
	 * @param storageOptions  Options specifying storage behavior, such as compression and CRC32 computation. Must not be null.
	 * @return An object containing the catalog versions of the first and last transactions after trimming.
	 * @throws UnexpectedIOException           If the WAL file cannot be opened or manipulated due to I/O errors.
	 * @throws WriteAheadLogCorruptedException If the integrity of the WAL file is violated or it contains corrupted transactions.
	 */
	@Nonnull
	protected FirstAndLastVersionsInWalFile truncateWalFileAndCalculateTail(
		@Nonnull Path walFilePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions
	) {
		if (AbstractMutationLog.isWalEmptyOrNonExisting(walFilePath)) {
			return new FirstAndLastVersionsInWalFile(-1L, -1L);
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try (
			final RandomAccessFile walFile = new RandomAccessFile(walFilePath.toFile(), "rw")
		) {
			// The previous process might have crashed before fsync.
			// If the DBMS doesn't fsync after opening, it may recover from data in the page cache (not yet durable),
			// and then externalize this as committed (e.g. to reads), violating Durability.
			walFile.getFD().sync();

			try (
				final ObservableInput<RandomAccessFileInputStream> input = AbstractMutationLog.createObservableInput(walFile, storageOptions)
			) {
				final WalFileScanResult scanResult = AbstractMutationLog.scanWalFileForLastCompleteTransaction(input, walFile.length(), walFilePath);

				try {
					final TransactionPair transactions = AbstractMutationLog.readAndValidateTransactions(
						input, kryo, scanResult.lastTxStartPosition(), scanResult.lastTxLength()
					);

					if (scanResult.consistentLength() < walFile.length()) {
						truncateWalFile(walFilePath, walFile, scanResult.consistentLength());
					}

					AbstractMutationLog.log.info("Writing missing tail statistics to file `{}`.", walFilePath);

					// write tail to the file using the static version
					AbstractMutationLog.writeWalTailStatic(
						transactions.firstTransaction().getVersion(),
						transactions.lastTransaction().getVersion(),
						walFile.getChannel()
					);

					return new FirstAndLastVersionsInWalFile(
						transactions.firstTransaction().getVersion(),
						transactions.lastTransaction().getVersion()
					);

				} catch (Exception ex) {
					AbstractMutationLog.handleTransactionReadException(walFilePath, ex);
					// This line is never reached as handleTransactionReadException always throws an exception
					throw new GenericEvitaInternalError("This code should never be reached");
				}
			}

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open WAL file `" + walFilePath + "`!",
				"Failed to open the WAL file!",
				e
			);
		} finally {
			catalogKryoPool.free(kryo);
		}
	}

	/**
	 * Check the cached WAL entries whether they should be cut because of long inactivity or left intact. This method
	 * also re-plans the next cache cut if the cache is not empty.
	 */
	protected long cutWalCache() {
		final long threshold = System.currentTimeMillis() - AbstractMutationLog.CUT_WAL_CACHE_AFTER_INACTIVITY_MS;
		long oldestNotCutEntryTouchTime = -1L;
		for (TransactionLocations locations : this.transactionLocationsCache.values()) {
			// if the entry was not cut already (contains more than single / last read record)
			final int size = locations.size();
			final boolean oldestRecord = oldestNotCutEntryTouchTime == -1L || locations.getLastReadTime() < oldestNotCutEntryTouchTime;
			if (size > 1) {
				if (locations.getLastReadTime() < threshold) {
					if (!locations.cut() && oldestRecord) {
						oldestNotCutEntryTouchTime = locations.getLastReadTime();
					}
				} else if (oldestRecord) {
					oldestNotCutEntryTouchTime = locations.getLastReadTime();
				}
			} else if (size == -1 && oldestRecord) {
				// we couldn't acquire lock, try again later for this entry
				oldestNotCutEntryTouchTime = locations.getLastReadTime();
			}
		}
		emitCacheSizeEvent(this.transactionLocationsCache.size());
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		return oldestNotCutEntryTouchTime > -1L ? (oldestNotCutEntryTouchTime - threshold) + 1 : -1L;
	}

	/**
	 * Creates a MutationSupplier object with the specified parameters.
	 *
	 * @param startVersion     the catalog version to start reading from
	 * @param requestedVersion the minimal catalog version to finish reading
	 * @return a new MutationSupplier object
	 */
	@Nonnull
	MutationSupplier<T> createSupplier(long startVersion, @Nullable Long requestedVersion) {
		final long theFirstVersionOfCurrentWalFile = this.getFirstVersionOfCurrentWalFile();

		// when requested catalog version is provided it means we may be reading the WAL file that is being appended
		// but we may rely on that the transaction with requested catalog version is already written and readable
		final Optional<Long> requestedCatalogVersionOptional = ofNullable(requestedVersion);
		final boolean avoidPartiallyFilledBuffer = requestedCatalogVersionOptional.isPresent();
		final long theRequestedCatalogVersion = requestedCatalogVersionOptional.orElse(Long.MAX_VALUE);

		if (theFirstVersionOfCurrentWalFile != -1 && theFirstVersionOfCurrentWalFile <= startVersion) {
			// we could start reading the current WAL file
			return new MutationSupplier<>(
				startVersion, theRequestedCatalogVersion,
				this.walFileNameProvider, this.storageFolder, this.storageOptions,
				getWalFileIndex(), this.kryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer,
				() -> emitCacheSizeEvent(this.transactionLocationsCache.size())
			);
		} else {
			// we need to find older WAL file
			final int foundWalIndex = findWalIndexFor(startVersion);
			return new MutationSupplier<>(
				startVersion, theRequestedCatalogVersion,
				this.walFileNameProvider, this.storageFolder, this.storageOptions,
				foundWalIndex, this.kryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer,
				() -> emitCacheSizeEvent(this.transactionLocationsCache.size())
			);
		}
	}

	/**
	 * Removes the obsolete WAL files from the catalog storage path.
	 */
	long removeWalFiles() {
		synchronized (this.pendingRemovals) {
			final long catalogVersion = this.processedVersion.get();
			final Set<PendingRemoval> toRemove = new HashSet<>(64);

			long firstVersionToBeKept = -1;
			for (PendingRemoval pendingRemoval : this.pendingRemovals) {
				if (pendingRemoval.version() <= catalogVersion) {
					try {
						pendingRemoval.removeLambda().run();
						if (pendingRemoval.version() > firstVersionToBeKept) {
							firstVersionToBeKept = pendingRemoval.version();
						}
						toRemove.add(pendingRemoval);
					} catch (IOException ex) {
						// failed to remove the file, we will try again later and continue with the next one
						AbstractMutationLog.log.error(ex.getMessage(), ex);
					}
				} else {
					break;
				}
			}

			if (!toRemove.isEmpty()) {
				this.pendingRemovals.removeAll(toRemove);
				updateFirstVersionKept(firstVersionToBeKept);
			}

			return -1;
		}
	}

	/**
	 * Updates the first version that is retained in the WAL files.
	 *
	 * @param firstVersionToBeKept The version number representing the first version available in the WAL files
	 */
	protected abstract void updateFirstVersionKept(long firstVersionToBeKept);

	/**
	 * Truncates a WAL file to a consistent length and emits a data file compact event.
	 *
	 * @param walFilePath      The path to the WAL file
	 * @param walFile          The RandomAccessFile to truncate
	 * @param consistentLength The length to truncate the file to
	 * @throws IOException If an I/O error occurs
	 */
	private void truncateWalFile(
		@Nonnull Path walFilePath,
		@Nonnull RandomAccessFile walFile,
		long consistentLength
	) throws IOException {
		final DataFileCompactEvent event = createDataFileCompactEvent();

		log.info(
			"Unknown content at the end of WAL file `{}`! Truncating to `{}` bytes!", walFilePath, consistentLength
		);

		// truncate the WAL file to the last consistent transaction
		walFile.setLength(consistentLength);

		// emit the event
		event.finish().commit();
	}

	/**
	 * Finds the index of a write-ahead log (WAL) file associated with a given catalog version.
	 *
	 * @param version The catalog version to search for.
	 * @return The index of the WAL file containing the specified catalog version, or -1 if no such file is found.
	 * @throws WriteAheadLogCorruptedException If an error occurs while reading the WAL files or if the catalog version
	 *                                         is found to be invalid or inconsistent.
	 */
	private int findWalIndexFor(long version) {
		final CurrentMutationLogFile theCurrentWalFile = this.currentWalFile.get();
		// if the particular version is within current file, return it
		final long firstCatalogVersionOfCurrentWalFile = theCurrentWalFile.getFirstVersionOfCurrentWalFile();
		final int currentWalFileIndex = theCurrentWalFile.getWalFileIndex();
		if ((version >= firstCatalogVersionOfCurrentWalFile && version <= theCurrentWalFile.getLastWrittenVersion()) ||
			// of if the version is lower than the start version of current WAL and the WAL is the first one
			(version < firstCatalogVersionOfCurrentWalFile && currentWalFileIndex == 0)) {
			return currentWalFileIndex;
		}

		final int[] walIndexesToSearch =
			Arrays.stream(
				      Objects.requireNonNull(
					      this.storageFolder.toFile().listFiles(
						      (dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
					      )
				      )
			      )
			      .mapToInt(it -> AbstractMutationLog.getIndexFromWalFileName(it.getName()))
			      .filter(it -> it != currentWalFileIndex)
			      .sorted()
			      .toArray();

		long previousLastVersion = -1;
		int foundWalIndex = -1;
		for (int indexToSearch : walIndexesToSearch) {
			final File oldWalFile = this.storageFolder.resolve(this.walFileNameProvider.apply(indexToSearch)).toFile();
			if (oldWalFile.length() > AbstractMutationLog.WAL_TAIL_LENGTH) {
				try (
					final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(oldWalFile, "r");
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(randomAccessOldWalFile, true)
					)
				) {
					input.seekWithUnknownLength(oldWalFile.length() - AbstractMutationLog.WAL_TAIL_LENGTH);
					final long firstVersion = input.simpleLongRead();
					final long lastVersion = input.simpleLongRead();

					Assert.isPremiseValid(
						firstVersion >= 1,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (`" + firstVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);
					Assert.isPremiseValid(
						firstVersion <= lastVersion,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (first: `" + firstVersion + "`, last: `" + lastVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);
					long finalPreviousLastCatalogVersion = previousLastVersion;
					Assert.isPremiseValid(
						previousLastVersion == -1 || previousLastVersion < firstVersion,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (first: `" + firstVersion + "`, last version of the previous file: `" + finalPreviousLastCatalogVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);

					// if the version looks for is lower than the first version ever, return the first file
					if (version < firstVersion && indexToSearch == 0) {
						foundWalIndex = indexToSearch;
						break;
					} else if (version >= firstVersion && version <= lastVersion) {
						foundWalIndex = indexToSearch;
						break;
					}

					previousLastVersion = lastVersion;
				} catch (Exception e) {
					// the file was deleted in the meantime or is being currently written to
				}
			}
		}
		return foundWalIndex;
	}

	/**
	 * Rotates the Write-Ahead Log (WAL) file.
	 * Increments the WAL file index and performs the necessary operations to close the current WAL file,
	 * create a new one, and delete any excess WAL files beyond the configured limit.
	 *
	 * @throws WriteAheadLogCorruptedException if an error occurs during the rotation process.
	 */
	private void rotateWalFile() {
		final WalRotationEvent event = createWalRotationEvent();

		final CurrentMutationLogFile theCurrentWalFile = this.currentWalFile.get();
		try {
			// write information about last and first catalog version in this WAL file
			final long firstCvInFile = this.getFirstVersionOfCurrentWalFile();
			final long lastCvInFile = this.getLastWrittenVersion();
			Assert.isPremiseValid(
				firstCvInFile >= 1,
				() -> new WriteAheadLogCorruptedException(
					"Invalid first catalog version in the WAL file (`" + firstCvInFile + "`)!",
					"Invalid first catalog version in the WAL file!"
				)
			);
			Assert.isPremiseValid(
				lastCvInFile >= firstCvInFile,
				() -> new WriteAheadLogCorruptedException(
					"Invalid last catalog version in the WAL file (first: " + firstCvInFile + ", last: " + lastCvInFile + ")!",
					"Invalid last catalog version in the WAL file!"
				)
			);

			// write tail to the file
			writeWalTail(firstCvInFile, lastCvInFile, theCurrentWalFile.getWalFileChannel());
			theCurrentWalFile.close();
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to close the WAL file channel for WAL file `" + theCurrentWalFile.getWalFilePath() + "`!",
				"Failed to close the WAL file channel!",
				e
			);
		}

		final int newWalFileIndex = theCurrentWalFile.getWalFileIndex() + 1;
		OffsetDateTime firstCommitTimestamp = null;
		try {
			final Path walFilePath = this.storageFolder.resolve(this.walFileNameProvider.apply(newWalFileIndex));

			// create the WAL file if it does not exist
			AbstractMutationLog.createWalFile(walFilePath, false);
			final FileChannel walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);
			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				this.transactionMutationOutputStream,
				AbstractMutationLog.TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				AbstractMutationLog.TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				AbstractMutationLog.TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);

			this.currentWalFile.set(
				new CurrentMutationLogFile(
					newWalFileIndex,
					-1L, -1L,
					walFilePath,
					walFileChannel,
					this.computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);

			// list all existing WAL files and remove the oldest ones when their count exceeds the limit
			final File[] walFiles = this.storageFolder.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			if (walFiles != null && walFiles.length > this.walFileCountKept) {
				// first sort the files from oldest to newest according to their index in the file name
				Arrays.sort(
					walFiles,
					Comparator.comparingInt(f -> AbstractMutationLog.getIndexFromWalFileName(f.getName()))
				);

				// assert that at least one file remains
				Assert.isPremiseValid(
					walFiles.length > this.walFileCountKept,
					"Failed to rotate WAL file! At least one WAL file should remain!"
				);

				// then delete all files except the last `walFileCountKept` files
				for (int i = 0; i < walFiles.length - this.walFileCountKept; i++) {
					final File walFile = walFiles[i];
					try {
						final FirstAndLastVersionsInWalFile versionsFromWalFile = AbstractMutationLog.getFirstAndLastVersionsFromWalFile(walFile);
						final PendingRemoval pendingRemoval = new PendingRemoval(
							versionsFromWalFile.lastVersion() + 1,
							() -> {
								try {
									if (walFile.delete()) {
										AbstractMutationLog.log.debug("Deleted WAL file `{}`!", walFile);
									} else {
										throw new IOException("Failed to delete WAL file `" + walFile.getAbsolutePath() + "`!");
									}
								} catch (Exception ex) {
									throw new IOException("Failed to delete WAL file `" + walFile.getAbsolutePath() + "`!", ex);
								}
							}
						);
						if (!this.pendingRemovals.contains(pendingRemoval)) {
							this.pendingRemovals.add(pendingRemoval);
						}
						// schedule the task to remove the file
						this.removeWalFileTask.schedule();
					} catch (FileNotFoundException ex) {
						// the file was deleted in the meantime
					}
				}
			}

		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + this.walFileNameProvider.apply(newWalFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		} finally {
			// emit the event
			event.finish(firstCommitTimestamp).commit();
		}
	}

	/**
	 * Writes the WAL (Write Ahead Log) tail information to the specified WAL file.
	 * The method writes the first and last change versions specified to the tail of the WAL file.
	 *
	 * @param firstCvInFile  the first change version present in the WAL file
	 * @param lastCvInFile   the last change version present in the WAL file
	 * @param walFileChannel the file channel of the WAL file to write to
	 * @throws IOException if an I/O error occurs while writing to the file channel
	 */
	private void writeWalTail(
		long firstCvInFile,
		long lastCvInFile,
		@Nonnull FileChannel walFileChannel
	) throws IOException {
		this.contentLengthBuffer.clear();
		AbstractMutationLog.writeLongToByteBuffer(this.contentLengthBuffer, firstCvInFile);
		AbstractMutationLog.writeLongToByteBuffer(this.contentLengthBuffer, lastCvInFile);

		// Calculate CRC32 checksum from the two longs already in contentLengthBuffer
		CRC32C crc32c = new CRC32C();
		// Get position after writing both longs
		int position = this.contentLengthBuffer.position();
		// Flip buffer temporarily to read the data
		this.contentLengthBuffer.flip();
		crc32c.update(this.contentLengthBuffer.limit(position));
		// Reset position to continue writing
		this.contentLengthBuffer.limit(this.contentLengthBuffer.capacity());
		this.contentLengthBuffer.position(position);
		// Write the checksum as the third long
		AbstractMutationLog.writeLongToByteBuffer(this.contentLengthBuffer, crc32c.getValue());

		int written = 0;
		this.contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
		while (this.contentLengthBuffer.hasRemaining()) {
			written += walFileChannel.write(this.contentLengthBuffer);
		}
		Assert.isPremiseValid(
			written == AbstractMutationLog.WAL_TAIL_LENGTH,
			"Failed to write tail to WAL file!"
		);
	}

	/**
	 * Returns the first transaction mutation from the given WAL file.
	 *
	 * @param walFile the WAL file to read from
	 * @return the first transaction mutation found in the WAL file
	 * @throws IllegalArgumentException        if the given WAL file is invalid
	 * @throws WriteAheadLogCorruptedException if failed to read the first transaction from the WAL file
	 */
	@Nonnull
	private TransactionMutation getFirstTransactionMutationFromWalFile(@Nonnull File walFile) {
		Assert.isPremiseValid(
			walFile.exists() && walFile.length() > 4,
			"Invalid WAL file `" + walFile + "`!"
		);
		try (
			final MutationSupplier<T> mutationSupplier = new MutationSupplier<>(
				0L, 0L, this.walFileNameProvider, this.storageFolder, this.storageOptions,
				AbstractMutationLog.getIndexFromWalFileName(walFile.getName()),
				this.kryoPool, this.transactionLocationsCache, false,
				() -> emitCacheSizeEvent(this.transactionLocationsCache.size())
			)
		) {
			final Mutation mutation = mutationSupplier.get();
			if (mutation instanceof TransactionMutation transactionMutation) {
				return transactionMutation;
			} else {
				throw new WriteAheadLogCorruptedException(
					"Failed to read the first transaction from WAL file `" + walFile + "`!",
					"Failed to read the first transaction from WAL file!"
				);
			}
		}
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param startVersion     the version to start reading from
	 * @param requestedVersion the minimal version to finish reading
	 * @return a stream of committed mutations
	 */
	@Nonnull
	private Stream<T> getCommittedMutationStream(
		long startVersion,
		@Nullable Long requestedVersion
	) {
		final MutationSupplier<T> supplier = createSupplier(startVersion, requestedVersion);
		this.cutWalCacheTask.schedule();
		return Stream.generate(supplier)
		             .takeWhile(Objects::nonNull)
		             .onClose(supplier::close);
	}

	/**
	 * Interface that allows to look up for the active files for the given catalog version and to remove the files up to
	 * the given active files.
	 */
	public interface WalPurgeCallback {

		WalPurgeCallback NO_OP = activeFiles -> {
			// do nothing
		};

		/**
		 * Purges the files up to the given active files.
		 *
		 * @param firstActiveCatalogVersion the first catalog version that needs to be kept
		 */
		void purgeFilesUpTo(long firstActiveCatalogVersion);

	}

	/**
	 * Represents the result of scanning a WAL file for the last complete transaction.
	 *
	 * @param lastTxStartPosition The start position of the last transaction
	 * @param lastTxLength        The length of the last transaction
	 * @param consistentLength    The length of the file upto the last complete transaction
	 */
	private record WalFileScanResult(
		long lastTxStartPosition,
		int lastTxLength,
		long consistentLength
	) {
	}

	/**
	 * Represents a pair of first and last transaction mutations from a WAL file.
	 */
	private record TransactionPair(
		@Nonnull TransactionMutation firstTransaction,
		@Nonnull TransactionMutation lastTransaction
	) {
	}

	/**
	 * Contains first and last versions found in current WAL file.
	 *
	 * @param firstVersion first version present in the file
	 * @param lastVersion  last version present in the file
	 */
	record FirstAndLastVersionsInWalFile(
		long firstVersion,
		long lastVersion
	) {
	}

	/**
	 * Record that holds information about pending removal of the WAL file.
	 *
	 * @param version      the version that needs to be processed before the removal
	 * @param removeLambda the removeLambda that performs the file removal
	 *                     and returns first transaction mutation in the removed file
	 */
	protected record PendingRemoval(
		long version,
		@Nonnull IOExceptionThrowingRunnable removeLambda
	) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			AbstractMutationLog.PendingRemoval that = (AbstractMutationLog.PendingRemoval) o;
			return this.version == that.version;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(this.version);
		}

		@Nonnull
		@Override
		public String toString() {
			return "PendingRemoval: version=" + this.version;
		}
	}
}
