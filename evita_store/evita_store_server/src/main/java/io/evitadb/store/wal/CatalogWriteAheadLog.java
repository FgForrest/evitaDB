/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serial;
import java.io.Serializable;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getIndexFromWalFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * CatalogWriteAheadLog is a class for managing a Write-Ahead Log (WAL) file for a catalog.
 * It allows appending transaction mutations to the WAL file. The class also provides a method to check and truncate
 * incomplete WAL files at the time it's created.
 *
 * The WAL file is used for durability, ensuring that changes made to the catalog are durable
 * and can be recovered in the case of crashes or failures.
 *
 * The class is not thread-safe and should be used from a single thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public class CatalogWriteAheadLog implements Closeable {
	/**
	 * The size of a transaction mutation in the WAL file.
	 */
	public static final int TRANSACTION_MUTATION_SIZE = StorageRecord.OVERHEAD_SIZE + TransactionMutationSerializer.RECORD_SIZE;
	/**
	 * The size of a transaction mutation in the WAL file with a reserve for the class id written by Kryo automatically.
	 */
	public static final int TRANSACTION_MUTATION_SIZE_WITH_RESERVE = TRANSACTION_MUTATION_SIZE + 32;
	/**
	 * The time after which the WAL cache is dropped after inactivity.
	 */
	private static final long CUT_WAL_CACHE_AFTER_INACTIVITY_MS = 300_000L; // 5 minutes
	/**
	 * At the end of each WAL file there is two longs written with start and end catalog version in the WAL.
	 */
	private static final int WAL_TAIL_LENGTH = 16;
	/**
	 * The name of the catalog.
	 */
	private final String catalogName;
	/**
	 * Path to the catalog storage folder where the WAL file is stored.
	 */
	private final Path catalogStoragePath;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ByteArrayOutputStream transactionMutationOutputStream = new ByteArrayOutputStream(TRANSACTION_MUTATION_SIZE);
	/**
	 * Buffer used to serialize the overall content length and {@link TransactionMutation} at the front of the
	 * transaction log.
	 *
	 * @see ByteBuffer
	 */
	private final ByteBuffer contentLengthBuffer = ByteBuffer.allocate(4 + TRANSACTION_MUTATION_SIZE_WITH_RESERVE);
	/**
	 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
	 */
	private final Pool<Kryo> catalogKryoPool;
	/**
	 * This field contains the version of the first transaction in the current {@link #walFilePath}.
	 */
	private final AtomicLong firstCatalogVersionOfCurrentWalFile = new AtomicLong(-1L);
	/**
	 * This field contains the version of the last fully written transaction in the WAL file.
	 * The value `0` means there are no valid transactions in the WAL file.
	 */
	private final AtomicLong lastWrittenCatalogVersion = new AtomicLong();
	/**
	 * Contains reference to the asynchronous task executor that clears the cached WAL pointers after some
	 * time of inactivity.
	 */
	private final ScheduledExecutorService scheduledExecutorService;
	/**
	 * Cache of already scanned WAL files. The locations might not be complete, but they will be always cover the start
	 * of the particular WAL file, but they may be later appended with new records that are not yet scanned or gradually
	 * added to the working WAL file. The index key in this map is the {@link #walFileIndex} of the WAL file.
	 *
	 * For each existing file there is at least single with the last read position of the file - so that the trunk
	 * incorporation doesn't need to scan the file from the beginning.
	 */
	private final ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache = CollectionUtils.createConcurrentHashMap(16);
	/**
	 * Size of the Write-Ahead Log (WAL) file in bytes before it is rotated.
	 */
	private final long maxWalFileSizeBytes;
	/**
	 * Number of WAL files to keep.
	 */
	private final int walFileCountKept;
	/**
	 * A flag indicating whether to compute CRC32 checksums for the written data in the WAL.
	 */
	private final boolean computeCRC32C;
	/**
	 * The next planned cache cut time - if there is scheduled action planned in the current scheduled executor service,
	 * the time is stored here to avoid scheduling the same action multiple times.
	 */
	private volatile long nextPlannedCacheCut = -1L;
	/**
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	@Getter(AccessLevel.PACKAGE)
	private int walFileIndex;
	/**
	 * The path to the WAL file.
	 */
	@Getter(AccessLevel.PACKAGE)
	private Path walFilePath;
	/**
	 * The file channel for writing to the WAL file.
	 */
	private FileChannel walFileChannel;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private ObservableOutput<ByteArrayOutputStream> output;
	/**
	 * Field contains current size of the WAL file the records are appended to in Bytes.
	 */
	private long currentWalFileSize;

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
	 * @param computeCRC32C   a flag indicating whether to compute CRC32C checksums for the input
	 * @return the last fully written catalog version found in the WAL file
	 */
	@Nonnull
	static FirstAndLastCatalogVersions checkAndTruncate(@Nonnull Path walFilePath, @Nonnull Pool<Kryo> catalogKryoPool, boolean computeCRC32C) {
		if (!walFilePath.toFile().exists()) {
			// WAL file does not exist, nothing to check
			return new FirstAndLastCatalogVersions(-1L, -1L);
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try (
			final RandomAccessFile walFile = new RandomAccessFile(walFilePath.toFile(), "rw");
			final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(
					walFile
				)
			)
		) {
			if (computeCRC32C) {
				input.computeCRC32();
			}
			final long fileLength = walFile.length();
			if (fileLength == 0) {
				// WAL file is empty, nothing to check
				return new FirstAndLastCatalogVersions(-1L, -1L);
			}

			int transactionSize = 0;
			int previousTransactionSize;
			long previousOffset;
			long offset = 0;
			do {
				input.skip(transactionSize);
				previousTransactionSize = transactionSize;
				previousOffset = offset;
				transactionSize = input.simpleIntRead();
				offset += 4 + transactionSize;
			} while (offset < fileLength);

			final long lastTxStartPosition;
			final int lastTxLength;
			final long consistentLength;
			if (offset > fileLength) {
				final Path backupFilePath = walFilePath.getParent().resolve("_damaged_wal.bck");
				log.warn(
					"WAL file `" + walFilePath + "` was not written completely! Truncating to the last complete" +
						" transaction (offset `" + previousOffset + "`) and backing up the original file to: `" +
						backupFilePath + "`!"
				);
				Files.copy(walFilePath, backupFilePath);
				lastTxStartPosition = previousOffset - previousTransactionSize - 4;
				lastTxLength = previousTransactionSize;
				consistentLength = previousOffset;
			} else {
				lastTxStartPosition = previousOffset;
				lastTxLength = transactionSize;
				consistentLength = offset;
			}

			try {
				input.seekWithUnknownLength(4);
				final TransactionMutation firstTransactionMutation = (TransactionMutation) StorageRecord.read(
					input, (stream, length) -> kryo.readClassAndObject(stream)
				).payload();

				input.seekWithUnknownLength(lastTxStartPosition + 4);
				final TransactionMutation transactionMutation = (TransactionMutation) StorageRecord.read(
					input, (stream, length) -> kryo.readClassAndObject(stream)
				).payload();

				final long calculatedTransactionSize = input.total() + transactionMutation.getWalSizeInBytes();
				Assert.isPremiseValid(
					lastTxLength == calculatedTransactionSize,
					() -> new WriteAheadLogCorruptedException(
						"The transaction size `" + lastTxLength + "` does not match the actual size `" +
							calculatedTransactionSize + "`!",
						"The transaction size does not match the actual size!"
					)
				);

				if (consistentLength < fileLength) {
					// truncate the WAL file to the last consistent transaction
					walFile.setLength(consistentLength);
				}

				return new FirstAndLastCatalogVersions(
					firstTransactionMutation.getCatalogVersion(),
					transactionMutation.getCatalogVersion()
				);

			} catch (Exception ex) {
				log.error(
					"Failed to read the last transaction from WAL file `" + walFilePath + "`! The file is probably" +
						" corrupted! The catalog will be marked as corrupted!",
					ex
				);
				if (ex instanceof WriteAheadLogCorruptedException) {
					// just rethrow
					throw ex;
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
	 * Creates a Write-Ahead Log (WAL) file if it does not already exist.
	 *
	 * @param walFilePath The path of the WAL file.
	 * @throws IOException If an I/O error occurs.
	 */
	private static void createWalFile(@Nonnull Path walFilePath, boolean mayExist) throws IOException {
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
	 * @param catalogName        the name of the catalog
	 * @return the last WAL file index
	 */
	private static int getLastWalFileIndex(@Nonnull Path catalogStoragePath, @Nonnull String catalogName) {
		final File[] walFiles = ofNullable(
			catalogStoragePath.toFile().listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX))
		).orElse(new File[0]);

		if (walFiles.length == 0) {
			return 0;
		} else {
			return Arrays.stream(walFiles)
				.map(f -> getIndexFromWalFileName(catalogName, f.getName()))
				.max(Integer::compareTo)
				.orElse(0);
		}
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

	public CatalogWriteAheadLog(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull ScheduledExecutorService scheduledExecutorService
	) {
		this.walFileIndex = getLastWalFileIndex(catalogStoragePath, catalogName);
		this.scheduledExecutorService = scheduledExecutorService;
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.catalogStoragePath = catalogStoragePath;
		this.computeCRC32C = storageOptions.computeCRC32C();
		try {
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, this.walFileIndex));
			final FirstAndLastCatalogVersions versions = checkAndTruncate(walFilePath, catalogKryoPool, storageOptions.computeCRC32C());
			this.firstCatalogVersionOfCurrentWalFile.set(versions.firstCatalogVersion());
			this.lastWrittenCatalogVersion.set(versions.lastCatalogVersion());

			this.catalogName = catalogName;
			this.catalogKryoPool = catalogKryoPool;

			// create the WAL file if it does not exist
			createWalFile(walFilePath, true);
			this.walFilePath = walFilePath;
			this.walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);
			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				transactionMutationOutputStream,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);
			this.output = this.computeCRC32C ?
				theOutput.computeCRC32() : theOutput;

			this.currentWalFileSize = this.walFileChannel.size();
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + getWalFileName(catalogName, this.walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
	}

	/**
	 * Retrieves the first catalog version of the current WAL file.
	 *
	 * @return the first catalog version of the current WAL file
	 */
	public long getFirstCatalogVersionOfCurrentWalFile() {
		return firstCatalogVersionOfCurrentWalFile.get();
	}

	/**
	 * Retrieves the last written catalog version.
	 *
	 * @return the last written catalog version
	 */
	public long getLastWrittenCatalogVersion() {
		return lastWrittenCatalogVersion.get();
	}

	/**
	 * Appends a transaction mutation to the Write-Ahead Log (WAL) file.
	 *
	 * @param transactionMutation The transaction mutation to append.
	 * @param walReference        The reference to the WAL file.
	 */
	public void append(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		if (this.currentWalFileSize + transactionMutation.getWalSizeInBytes() + 4 > this.maxWalFileSizeBytes) {
			// rotate the WAL file
			rotateWalFile();
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try {
			// write transaction mutation to memory buffer
			this.transactionMutationOutputStream.reset();
			this.output.reset();

			// first write the transaction mutation to the memory byte array
			final StorageRecord<TransactionMutation> record = new StorageRecord<>(
				this.output,
				transactionMutation.getCatalogVersion(),
				false,
				theOutput -> {
					kryo.writeClassAndObject(theOutput, transactionMutation);
					return transactionMutation;
				}
			);
			this.output.flush();

			// write content length first
			final int contentLength = walReference.getContentLength();
			final int contentLengthWithTxMutation = contentLength + record.fileLocation().recordLength();
			contentLengthBuffer.clear();
			writeIntToByteBuffer(contentLengthBuffer, contentLengthWithTxMutation);

			// then write the transaction mutation to the memory buffer as the first record of the transaction
			this.contentLengthBuffer.put(transactionMutationOutputStream.toByteArray(), 0, record.fileLocation().recordLength());

			// first write the contents of the byte buffer as the leading information in the shared WAL
			int writtenHead = 0;
			contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			while (contentLengthBuffer.hasRemaining()) {
				writtenHead += this.walFileChannel.write(contentLengthBuffer);
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
						writtenContent += this.walFileChannel.write(byteBuffer);// Write buffer to file
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
							writtenContent += Math.toIntExact(readChannel.transferTo(writtenContent, contentLength, this.walFileChannel));
						}
					}
				}
			}

			// verify the expected length of the transaction was written
			Assert.isPremiseValid(
				writtenContent == contentLength,
				"Failed to write all bytes (" + writtenContent + " of " + contentLength + " Bytes) to WAL file!"
			);

			if (this.firstCatalogVersionOfCurrentWalFile.get() == -1) {
				this.firstCatalogVersionOfCurrentWalFile.set(transactionMutation.getCatalogVersion());
			}
			this.lastWrittenCatalogVersion.set(transactionMutation.getCatalogVersion());
			this.currentWalFileSize += 4 + writtenHead + writtenContent;

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to append WAL to catalog `" + this.catalogName + "`!",
				"Failed to append WAL to catalog!",
				e
			);
		} finally {
			this.catalogKryoPool.free(kryo);
		}
	}

	/**
	 * Gets the reference to a WAL (Write-Ahead Log) file with the last processed WAL record position.
	 *
	 * @param lastProcessedTransaction The last processed WAL record position.
	 * @return The WAL file reference with last processed WAL record position.
	 */
	@Nonnull
	public WalFileReference getWalFileReference(@Nullable TransactionMutation lastProcessedTransaction) {
		Assert.isPremiseValid(
			lastProcessedTransaction instanceof TransactionMutationWithLocation,
			"Invalid last processed transaction!"
		);
		final TransactionMutationWithLocation lastProcessedTransactionWithLocation = (TransactionMutationWithLocation) lastProcessedTransaction;
		return new WalFileReference(
			this.catalogName,
			lastProcessedTransactionWithLocation.getWalFileIndex(),
			lastProcessedTransactionWithLocation.getTransactionSpan()
		);
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param catalogVersion the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<Mutation> getCommittedMutationStream(long catalogVersion) {
		return getCommittedMutationStream(catalogVersion, false);
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
	 * @param catalogVersion the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<Mutation> getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(long catalogVersion) {
		return getCommittedMutationStream(catalogVersion, true);
	}

	/**
	 * Returns UUID of the first transaction that is present in the WAL file and which transitions the catalog to
	 * the version that is greater by one than the current catalog version.
	 *
	 * @param currentCatalogVersion the current catalog version
	 * @return UUID of the first transaction that is present in the WAL file and which transitions the catalog to
	 */
	@Nonnull
	public Optional<TransactionMutation> getFirstNonProcessedTransaction(@Nullable WalFileReference currentCatalogVersion) {
		final Path walFilePath;
		final long startPosition;
		if (currentCatalogVersion == null) {
			walFilePath = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, this.walFileIndex));
			startPosition = 0L;
		} else {
			walFilePath = currentCatalogVersion.toFilePath(this.catalogStoragePath);
			startPosition = currentCatalogVersion.fileLocation().startingPosition() + currentCatalogVersion.fileLocation().recordLength();
		}
		final File walFile = walFilePath.toFile();
		if (walFile.exists() && walFile.length() > startPosition + 4) {
			try (
				final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(walFile, "r");
				final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
					new RandomAccessFileInputStream(
						randomAccessOldWalFile
					)
				)
			) {
				input.seekWithUnknownLength(startPosition + 4);
				final TransactionMutation firstTransactionMutation = (TransactionMutation) StorageRecord.read(
					input, (stream, length) -> catalogKryoPool.obtain().readClassAndObject(stream)
				).payload();
				return of(firstTransactionMutation);
			} catch (Exception e) {
				throw new WriteAheadLogCorruptedException(
					"Failed to read `" + walFilePath + "`!",
					"Failed to read WAL file!", e
				);
			}
		}
		return empty();
	}

	@Override
	public void close() throws IOException {
		this.output.close();
		this.walFileChannel.close();
		this.transactionMutationOutputStream.close();
	}

	/**
	 * Creates a MutationSupplier object with the specified parameters.
	 *
	 * @param catalogVersion             the catalog version
	 * @param avoidPartiallyFilledBuffer whether to avoid partially filled buffer or not
	 * @return a new MutationSupplier object
	 */
	@Nonnull
	MutationSupplier createSupplier(long catalogVersion, boolean avoidPartiallyFilledBuffer) {
		if (this.getFirstCatalogVersionOfCurrentWalFile() <= catalogVersion) {
			// we could start reading the current WAL file
			return new MutationSupplier(
				catalogVersion, this.catalogName, this.catalogStoragePath, this.walFileIndex,
				this.catalogKryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer
			);
		} else {
			// we need to find older WAL file
			final int[] walIndexesToSearch = Arrays.stream(
					this.catalogStoragePath.toFile().listFiles(
						(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
					)
				)
				.mapToInt(it -> getIndexFromWalFileName(catalogName, it.getName()))
				.filter(it -> it != this.walFileIndex)
				.sorted()
				.toArray();

			long previousLastCatalogVersion = -1;
			int foundWalIndex = -1;
			for (int indexToSearch : walIndexesToSearch) {
				final File oldWalFile = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, indexToSearch)).toFile();
				try (
					final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(oldWalFile, "r");
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(
							randomAccessOldWalFile
						)
					)
				) {
					input.seekWithUnknownLength(oldWalFile.length() - WAL_TAIL_LENGTH);
					final long firstCatalogVersion = input.simpleLongRead();
					final long lastCatalogVersion = input.simpleLongRead();

					Assert.isPremiseValid(
						firstCatalogVersion >= 1,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (`" + firstCatalogVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);
					Assert.isPremiseValid(
						firstCatalogVersion <= lastCatalogVersion,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (first: `" + firstCatalogVersion + "`, last: `" + lastCatalogVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);
					long finalPreviousLastCatalogVersion = previousLastCatalogVersion;
					Assert.isPremiseValid(
						previousLastCatalogVersion == -1 || previousLastCatalogVersion < firstCatalogVersion,
						() -> new WriteAheadLogCorruptedException(
							"Invalid first catalog version in the WAL file `" + oldWalFile.getAbsolutePath() + "` (first: `" + firstCatalogVersion + "`, last version of the previous file: `" + finalPreviousLastCatalogVersion + "`)!",
							"Invalid first catalog version in the WAL file!"
						)
					);

					if (catalogVersion >= firstCatalogVersion && catalogVersion <= lastCatalogVersion) {
						foundWalIndex = indexToSearch;
						break;
					}

					previousLastCatalogVersion = lastCatalogVersion;
				} catch (IOException e) {
					throw new WriteAheadLogCorruptedException(
						"Failed to read `" + oldWalFile.getAbsolutePath() + "`!",
						"Failed to read WAL file!", e
					);
				}
			}

			return new MutationSupplier(
				catalogVersion, this.catalogName, this.catalogStoragePath, foundWalIndex,
				this.catalogKryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer
			);
		}
	}

	/**
	 * Rotates the Write-Ahead Log (WAL) file.
	 * Increments the WAL file index and performs the necessary operations to close the current WAL file,
	 * create a new one, and delete any excess WAL files beyond the configured limit.
	 *
	 * @throws WriteAheadLogCorruptedException if an error occurs during the rotation process.
	 */
	private void rotateWalFile() {
		this.walFileIndex++;
		try {
			// write information about last and first catalog version in this WAL file
			final long firstCvInFile = this.getFirstCatalogVersionOfCurrentWalFile();
			final long lastCvInFile = this.getLastWrittenCatalogVersion();
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
			this.contentLengthBuffer.clear();
			writeLongToByteBuffer(this.contentLengthBuffer, firstCvInFile);
			writeLongToByteBuffer(this.contentLengthBuffer, lastCvInFile);
			int written = 0;
			contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			while (contentLengthBuffer.hasRemaining()) {
				written += this.walFileChannel.write(contentLengthBuffer);
			}
			Assert.isPremiseValid(
				written == WAL_TAIL_LENGTH,
				"Failed to write tail to WAL file!"
			);
			this.firstCatalogVersionOfCurrentWalFile.set(-1L);

			this.output.close();
			this.walFileChannel.close();
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to close the WAL file channel for WAL file `" + this.walFilePath + "`!",
				"Failed to close the WAL file channel!",
				e
			);
		}
		try {
			final Path walFilePath = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, this.walFileIndex));

			// create the WAL file if it does not exist
			createWalFile(walFilePath, false);
			this.walFilePath = walFilePath;
			this.walFileChannel = FileChannel.open(
				walFilePath,
				StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC
			);
			//noinspection IOResourceOpenedButNotSafelyClosed
			final ObservableOutput<ByteArrayOutputStream> theOutput = new ObservableOutput<>(
				transactionMutationOutputStream,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE,
				TRANSACTION_MUTATION_SIZE_WITH_RESERVE
			);
			this.output = computeCRC32C ?
				theOutput.computeCRC32() : theOutput;

			this.currentWalFileSize = this.walFileChannel.size();

			// list all existing WAL files and remove the oldest ones when their count exceeds the limit
			final File[] walFiles = this.catalogStoragePath.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			if (walFiles.length > this.walFileCountKept) {
				// first sort the files from oldest to newest according to their index in the file name
				Arrays.sort(
					walFiles,
					Comparator.comparingInt(f -> getIndexFromWalFileName(catalogName, f.getName()))
				);
				// then delete all files except the last `walFileCountKept` files
				for (int i = 0; i < walFiles.length - this.walFileCountKept; i++) {
					final File walFile = walFiles[i];
					if (!walFile.delete()) {
						// don't throw exception - this is not so critical so that we should stop accepting new mutations
						log.error("Failed to delete WAL file `" + walFile + "`!");
					}
				}
			}

		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + getWalFileName(catalogName, this.walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
	}

	/**
	 * Retrieves a stream of committed mutations starting from the given catalog version.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param catalogVersion the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	private Stream<Mutation> getCommittedMutationStream(long catalogVersion, boolean avoidPartiallyFilledBuffer) {
		final File walFile = this.walFilePath.toFile();
		if (!walFile.exists() || walFile.length() < 4) {
			// WAL file does not exist or is empty, nothing to read
			return Stream.empty();
		} else {
			final MutationSupplier supplier = createSupplier(catalogVersion, avoidPartiallyFilledBuffer);
			if (this.nextPlannedCacheCut == -1L) {
				this.scheduledExecutorService.schedule(
					this::cutWalCache, CUT_WAL_CACHE_AFTER_INACTIVITY_MS, MILLISECONDS
				);
			}
			return Stream.generate(supplier)
				.takeWhile(Objects::nonNull)
				.onClose(supplier::close);
		}
	}

	/**
	 * Check the cached WAL entries whether they should be cut because of long inactivity or left intact. This method
	 * also re-plans the next cache cut if the cache is not empty.
	 */
	private void cutWalCache() {
		final long now = System.currentTimeMillis();
		long oldestNotCutEntryTouchTime = -1L;
		for (TransactionLocations locations : transactionLocationsCache.values()) {
			// if the entry was not cut already (contains more than single / last read record)
			final int size = locations.size();
			final boolean oldestRecord = oldestNotCutEntryTouchTime == -1L || locations.getLastReadTime() < oldestNotCutEntryTouchTime;
			if (size > 1) {
				if (locations.getLastReadTime() - CUT_WAL_CACHE_AFTER_INACTIVITY_MS > now) {
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
		if (oldestNotCutEntryTouchTime > -1L) {
			this.nextPlannedCacheCut = CUT_WAL_CACHE_AFTER_INACTIVITY_MS - (now - oldestNotCutEntryTouchTime) + 1;
			// re-plan the scheduled cut to the moment when the next entry should be cut down
			this.scheduledExecutorService.schedule(
				this::cutWalCache,
				this.nextPlannedCacheCut,
				MILLISECONDS
			);
		} else {
			this.nextPlannedCacheCut = -1L;
		}
	}

	/**
	 * This private static class represents a Supplier of Mutation objects and implements the Supplier and AutoCloseable
	 * interfaces.
	 * It is used to supply Mutation objects from a Write-Ahead Log (WAL) file.
	 */
	static class MutationSupplier implements Supplier<Mutation>, AutoCloseable {
		/**
		 * The Kryo pool for serializing {@link TransactionMutation} (given by outside).
		 */
		private final Pool<Kryo> catalogKryoPool;
		/**
		 * The Kryo object for serializing {@link TransactionMutation} obtained in constructor from the Kryo pool.
		 */
		private final Kryo kryo;
		/**
		 * The name of the catalog.
		 */
		private final String catalogName;
		/**
		 * Path to the catalog storage folder where the WAL file is stored.
		 */
		private final Path catalogStoragePath;
		/**
		 * The cache of already scanned WAL files. The locations might not be complete, but they will be always cover
		 * the start of the particular WAL file, but they may be later appended with new records that are not yet scanned
		 * or gradually added to the working WAL file. The index key in this map is the {@link #walFileIndex} of the WAL file.
		 */
		private final ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache;
		/**
		 * This flag is used to prevent the observable input from reading the next transaction mutation if there is not
		 * enough data already written to fully fill the buffer of the observable input.
		 */
		private final boolean avoidPartiallyFilledBuffer;
		/**
		 * The Write-Ahead Log (WAL) file reference
		 */
		private File walFile;
		/**
		 * The index of the WAL file incremented each time the WAL file is rotated.
		 */
		private int walFileIndex;
		/**
		 * The ObservableInput for reading {@link TransactionMutation} from the WAL file.
		 */
		private ObservableInput<RandomAccessFileInputStream> observableInput;
		/**
		 * The current {@link TransactionMutation} being read from the WAL file.
		 */
		private TransactionMutationWithLocation transactionMutation;
		/**
		 * The current position in the WAL file.
		 */
		private long filePosition;
		/**
		 * The number of mutations read from the current transaction.
		 */
		private int transactionMutationRead;
		/**
		 * The number of transactions read from the WAL file.
		 */
		@Getter private int transactionsRead;

		public MutationSupplier(
			long catalogVersion,
			@Nonnull String catalogName,
			@Nonnull Path catalogStoragePath,
			int walFileIndex,
			@Nonnull Pool<Kryo> catalogKryoPool,
			@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
			boolean avoidPartiallyFilledBuffer
		) {
			this.walFile = catalogStoragePath.resolve(getWalFileName(catalogName, walFileIndex)).toFile();
			this.walFileIndex = walFileIndex;
			this.catalogName = catalogName;
			this.catalogStoragePath = catalogStoragePath;
			this.transactionLocationsCache = transactionLocationsCache;
			this.avoidPartiallyFilledBuffer = avoidPartiallyFilledBuffer;
			if (walFile.length() == 0) {
				this.catalogKryoPool = catalogKryoPool;
				this.kryo = null;
				this.observableInput = null;
				this.transactionMutation = null;
			} else {
				this.catalogKryoPool = catalogKryoPool;
				this.kryo = catalogKryoPool.obtain();
				try {
					final RandomAccessFile randomWalFile = new RandomAccessFile(walFile, "r");
					this.observableInput = new ObservableInput<>(
						new RandomAccessFileInputStream(
							randomWalFile
						)
					);
					// fast path if the record is found in cache
					TransactionMutationWithLocation initialTransactionMutation;
					do {
						this.filePosition = ofNullable(this.transactionLocationsCache.get(this.walFileIndex))
							.map(it -> it.findNearestLocation(catalogVersion))
							.orElse(0L);

						this.observableInput.seekWithUnknownLength(this.filePosition);

						initialTransactionMutation = readAndRecordTransactionMutation(this.observableInput);
						// move cursor to the end of the lead mutation
						while (initialTransactionMutation.getCatalogVersion() < catalogVersion) {
							// move cursor to the next transaction mutation
							this.filePosition += initialTransactionMutation.getTransactionSpan().recordLength();
							this.observableInput.seekWithUnknownLength(this.filePosition);
							// read content length and leading transaction mutation
							initialTransactionMutation = readAndRecordTransactionMutation(this.observableInput);
							// if the file is shorter than the expected size of the transaction mutation, we've reached EOF
							if (this.walFile.length() < this.filePosition + initialTransactionMutation.getTransactionSpan().recordLength()) {
								initialTransactionMutation = null;
								break;
							}
						}
					} while (
						initialTransactionMutation == null &&
							// if we've reached EOF, check whether there is file with next WAL index
							moveToNextWalFile()
					);
					// we've reached the first transaction mutation with catalog version >= requested catalog version
					this.transactionMutation = initialTransactionMutation;
				} catch (BufferUnderflowException e) {
					// we've reached EOF or the tx mutation hasn't been yet completely written
					this.transactionMutation = null;
					this.observableInput = null;
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to read WAL file `" + walFile.getName() + "`!",
						"Failed to read WAL file!",
						e
					);
				}
			}
		}

		@Override
		public Mutation get() {
			if (this.transactionMutationRead == 0) {
				this.transactionMutationRead++;
				return this.transactionMutation;
			} else {
				if (this.transactionMutationRead <= this.transactionMutation.getMutationCount()) {
					this.transactionMutationRead++;
					return (Mutation) StorageRecord.read(
						this.observableInput, (stream, length) -> kryo.readClassAndObject(stream)
					).payload();
				} else {
					// advance position to the end of the last transaction
					this.filePosition += this.transactionMutation.getTransactionSpan().recordLength();
					try {
						// check the entire transaction was written
						final long currentFileLength = this.walFile.length();
						if (currentFileLength <= this.filePosition + WAL_TAIL_LENGTH) {
							// we've reached EOF
							if (!moveToNextWalFile()) {
								// we've reached EOF and there is no next WAL file
								return null;
							}
						}
						// read content length and next leading transaction mutation
						this.transactionMutation = readAndRecordTransactionMutation(this.observableInput);

						// check the entire transaction was written and there are another data that would fully fill the buffer of the observable input
						// Note: there must be some bug in our observable input implementation that is revealed by filling the buffer incompletely with
						// the data from the stream - example: the buffer is 16k long, but the next transaction takes only 2k and then file ends
						// the observable input reads the transaction mutation, but in the meantime another transaction with 4k size has been written
						// the 4k transaction is then failed to be read from the observable input, because the internal pointers are probably somehow misaligned

						// nevertheless, the condition is here to prevent the observable input from reading the next transaction mutation
						// if there is not enough data already written is actually ok for real-world scenarios - we want to fast play transactions
						// in the WAL only when there is a lot of them to be read and processed
						final long requiredLength = this.filePosition + this.transactionMutation.getTransactionSpan().recordLength() +
							(avoidPartiallyFilledBuffer ? this.observableInput.getBuffer().length : 0);
						if (currentFileLength >= requiredLength) {
							this.transactionMutationRead = 1;
							// return the transaction mutation
							return this.transactionMutation;
						} else {
							// we've reached EOF or the tx mutation hasn't been yet completely written
							return null;
						}
					} catch (CorruptedRecordException | BufferUnderflowException | KryoException ex) {
						// we've reached EOF or the tx mutation hasn't been yet completely written
						return null;
					}
				}
			}
		}

		@Override
		public void close() {
			if (this.kryo != null) {
				this.catalogKryoPool.free(this.kryo);
			}
			if (observableInput != null) {
				this.observableInput.close();
			}
		}

		/**
		 * Moves pointers to the next Write-Ahead-Log (WAL) file.
		 */
		private boolean moveToNextWalFile() {
			this.observableInput.close();

			final File nextWalFile = this.catalogStoragePath.resolve(
				getWalFileName(this.catalogName, this.walFileIndex + 1)
			).toFile();

			if (nextWalFile.exists()) {
				try {
					this.walFile = nextWalFile;
					this.walFileIndex++;
					this.filePosition = 0L;
					this.observableInput = new ObservableInput<>(
						new RandomAccessFileInputStream(
							new RandomAccessFile(nextWalFile, "r")
						)
					);
					return true;
				} catch (FileNotFoundException ignored) {
					// this should not happen when we checked before that the file exists
					// but let's pretend it hasn't existed
					return false;
				}
			} else {
				return false;
			}
		}

		/**
		 * Reads and records a transaction mutation from the given observable input.
		 *
		 * @param theObservableInput the input stream to read from
		 * @return the transaction mutation read from the input stream
		 */
		@Nonnull
		private TransactionMutationWithLocation readAndRecordTransactionMutation(
			@Nonnull ObservableInput<RandomAccessFileInputStream> theObservableInput) {
			// read content length and leading transaction mutation
			final long totalBefore = theObservableInput.total();
			// the expected total length of current transaction (leading mutation plus all other mutations)
			int contentLength = theObservableInput.simpleIntRead();
			TransactionMutation transactionMutation = (TransactionMutation) StorageRecord.read(
				theObservableInput, (stream, length) -> kryo.readClassAndObject(stream)
			).payload();
			// measure the lead mutation size + verify the content length
			final int leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBefore);
			Assert.isPremiseValid(
				contentLength + 4 == leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
				"Invalid WAL file on position `" + this.filePosition + "`!"
			);
			this.transactionLocationsCache.computeIfAbsent(
				this.walFileIndex, it -> new TransactionLocations()
			).register(this.filePosition, transactionMutation);

			this.transactionsRead++;
			return new TransactionMutationWithLocation(
				transactionMutation,
				new FileLocation(this.filePosition, contentLength + 4),
				this.walFileIndex
			);
		}
	}

	/**
	 * Represents a TransactionMutation with additional location information.
	 */
	static class TransactionMutationWithLocation extends TransactionMutation {
		@Serial private static final long serialVersionUID = -5873907941292188132L;
		@Nonnull @Getter
		private final FileLocation transactionSpan;
		@Getter
		private final int walFileIndex;

		public TransactionMutationWithLocation(@Nonnull TransactionMutation delegate, @Nonnull FileLocation transactionSpan, int walFileIndex) {
			super(delegate.getTransactionId(), delegate.getCatalogVersion(), delegate.getMutationCount(), delegate.getWalSizeInBytes());
			this.transactionSpan = transactionSpan;
			this.walFileIndex = walFileIndex;
		}

	}

	/**
	 * This class represents a collection of transaction locations. It maintains a full known
	 * transaction location history and provides methods to interact with the locations.
	 */
	static class TransactionLocations {
		/**
		 * Lock is used for synchronization of tha access to non-thread-safe {@link #locations}.
		 */
		private final ReentrantLock lock = new ReentrantLock();
		/**
		 * The time of the last read of the transaction locations.
		 */
		@Getter private long lastReadTime;
		/**
		 * The full known transaction location history maintained in this object.
		 */
		private CompositeObjectArray<TransactionLocation> locations = new CompositeObjectArray<>(TransactionLocation.class, true);
		/**
		 * The last known transaction location maintained in this object.
		 */
		private TransactionLocation lastLocation;

		/**
		 * Returns the number of transaction locations maintained in this object.
		 *
		 * @return the number of transaction locations, or -1 if the lock could not be acquired
		 */
		public int size() {
			if (lock.tryLock()) {
				try {
					return this.locations == null ? 1 : this.locations.getSize();
				} finally {
					lock.unlock();
				}
			} else {
				return -1;
			}
		}

		/**
		 * Releases all transaction locations maintained in this object and leaves only the last one.
		 * Throws an exception if the locations are already cut.
		 *
		 * @return true if the locations were successfully cut, false if the lock could not be acquired
		 */
		public boolean cut() {
			Assert.isPremiseValid(this.locations != null, "The locations are already cut!");
			try {
				if (lock.tryLock(100, MILLISECONDS)) {
					try {
						this.lastLocation = this.locations.getLast();
						this.locations = null;
						return true;
					} finally {
						lock.unlock();
					}
				}
			} catch (InterruptedException ignored) {
				// do nothing
				Thread.currentThread().interrupt();
			}
			return false;
		}

		/**
		 * Registers a transaction mutation with the given total before value.
		 *
		 * @param startPosition       the total value before the transaction mutation
		 * @param transactionMutation the transaction mutation to register
		 */
		public void register(long startPosition, @Nonnull TransactionMutation transactionMutation) {
			notifyAboutUsage();
			if (this.locations == null) {
				this.locations = new CompositeObjectArray<>(TransactionLocation.class);
			}
			if (lock.tryLock()) {
				try {
					final boolean canBeAppended = ofNullable(this.locations.getLast())
						.map(it -> it.catalogVersion() + 1 == transactionMutation.getCatalogVersion())
						.orElse(true);
					if (canBeAppended) {
						this.locations.add(
							new TransactionLocation(
								transactionMutation.getCatalogVersion(),
								startPosition,
								transactionMutation.getMutationCount(),
								transactionMutation.getWalSizeInBytes()
							)
						);
					}
				} finally {
					lock.unlock();
				}
			}
		}

		/**
		 * Finds the nearest location in the catalog based on the given catalog version.
		 *
		 * @param catalogVersion the catalog version to find the nearest location for
		 * @return the start position of the nearest location, or 0 if no location is found
		 */
		public long findNearestLocation(long catalogVersion) {
			notifyAboutUsage();
			final CompositeObjectArray<TransactionLocation> locs = this.locations;
			if (locs != null) {
				if (lock.tryLock()) {
					try {
						final int index = locs.indexOf(
							catalogVersion,
							(transactionLocation, cv) -> Long.compare(transactionLocation.catalogVersion(), cv)
						);
						return index >= 0 ? locs.get(index).startPosition() : 0L;
					} finally {
						lock.unlock();
					}
				}
			} else {
				final TransactionLocation ll = this.lastLocation;
				if (ll != null && ll.catalogVersion() <= catalogVersion) {
					return ll.startPosition();
				}
			}
			return 0L;
		}

		/**
		 * Notifies about the usage of a transaction location object by updating the last read time.
		 */
		private void notifyAboutUsage() {
			this.lastReadTime = System.currentTimeMillis();
		}

	}

	/**
	 * Represents the location of a transaction in the WAL file and its span.
	 *
	 * @param catalogVersion the catalog version
	 * @param startPosition  the start position in the WAL file, the first 4B after this position contains overal length of the transaction
	 * @param mutationCount  the number of mutations in the transaction
	 * @param contentLength  the length of the transaction as specified in transaction mutation
	 */
	private record TransactionLocation(
		long catalogVersion,
		long startPosition,
		int mutationCount,
		long contentLength
	) implements Comparable<TransactionLocation>, Serializable {

		@Override
		public int compareTo(TransactionLocation o) {
			return Long.compare(this.catalogVersion, o.catalogVersion);
		}

	}

	/**
	 * Contains first and last catalog versions found in current WAL file.
	 *
	 * @param firstCatalogVersion first catalog version
	 * @param lastCatalogVersion  last catalog version
	 */
	private record FirstAndLastCatalogVersions(
		long firstCatalogVersion,
		long lastCatalogVersion
	) {
	}

}
