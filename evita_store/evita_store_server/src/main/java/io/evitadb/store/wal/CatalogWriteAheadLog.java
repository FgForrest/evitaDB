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
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
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
	 * The name of the catalog.
	 */
	private final String catalogName;
	/**
	 * The path to the WAL file.
	 */
	private final Path walFilePath;
	/**
	 * The file channel for writing to the WAL file.
	 */
	private final FileChannel walFileChannel;
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ByteArrayOutputStream transactionMutationOutputStream = new ByteArrayOutputStream(TRANSACTION_MUTATION_SIZE);
	/**
	 * The output stream for writing {@link TransactionMutation} to the WAL file.
	 */
	private final ObservableOutput<ByteArrayOutputStream> output;
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
	 * The index of the WAL file incremented each time the WAL file is rotated.
	 */
	private final int walFileIndex;
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
	 * The next planned cache cut time - if there is scheduled action planned in the current scheduled executor service,
	 * the time is stored here to avoid scheduling the same action multiple times.
	 */
	private volatile long nextPlannedCacheCut = -1L;

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
	static long checkAndTruncate(@Nonnull Path walFilePath, @Nonnull Pool<Kryo> catalogKryoPool, boolean computeCRC32C) {
		if (!walFilePath.toFile().exists()) {
			// WAL file does not exist, nothing to check
			return 0;
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
				return 0;
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

				return transactionMutation.getCatalogVersion();

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
	private static void createWalFileNotExists(@Nonnull Path walFilePath) throws IOException {
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
		}
	}

	public CatalogWriteAheadLog(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull WalFileReference walFileReference,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ScheduledExecutorService scheduledExecutorService
	) {
		this.walFileIndex = walFileReference.fileIndex();
		this.scheduledExecutorService = scheduledExecutorService;
		try {
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, this.walFileIndex));
			this.lastWrittenCatalogVersion.set(checkAndTruncate(walFilePath, catalogKryoPool, storageOptions.computeCRC32C()));

			this.catalogName = catalogName;
			this.catalogKryoPool = catalogKryoPool;

			// create the WAL file if it does not exist
			createWalFileNotExists(walFilePath);
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
			this.output = storageOptions.computeCRC32C() ?
				theOutput.computeCRC32() : theOutput;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to open WAL file `" + getWalFileName(catalogName, this.walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
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
		final Kryo kryo = catalogKryoPool.obtain();
		try {
			// write transaction mutation to memory buffer
			transactionMutationOutputStream.reset();
			output.reset();

			// first write the transaction mutation to the memory byte array
			final StorageRecord<TransactionMutation> record = new StorageRecord<>(
				output,
				transactionMutation.getCatalogVersion(),
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
			contentLengthBuffer.clear();
			contentLengthBuffer.put((byte) contentLengthWithTxMutation);
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 8));
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 16));
			contentLengthBuffer.put((byte) (contentLengthWithTxMutation >> 24));

			// then write the transaction mutation to the memory buffer as the first record of the transaction
			contentLengthBuffer.put(transactionMutationOutputStream.toByteArray(), 0, record.fileLocation().recordLength());

			// first write the contents of the byte buffer as the leading information in the shared WAL
			int written = 0;
			contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			while (contentLengthBuffer.hasRemaining()) {
				written += this.walFileChannel.write(contentLengthBuffer);
			}
			Assert.isPremiseValid(
				written == record.fileLocation().recordLength() + 4,
				"Failed to write content length to WAL file!"
			);

			// then copy the contents of the isolated WAL into the shared WAL and discard the isolated WAL
			written = 0;
			try (walReference) {
				if (walReference.getBuffer().isPresent()) {
					// write the buffer contents from the buffer in case of off heap byte buffer
					final ByteBuffer byteBuffer = walReference.getBuffer().get();
					while (byteBuffer.hasRemaining()) {
						written += this.walFileChannel.write(byteBuffer);// Write buffer to file
					}
				} else if (walReference.getFilePath().isPresent()) {
					// write the file contents from the file in case of file reference
					try (
						final FileChannel readChannel = FileChannel.open(
							walReference.getFilePath().get(),
							StandardOpenOption.READ
						)
					) {
						while (written < contentLength) {
							written += Math.toIntExact(readChannel.transferTo(written, contentLength, this.walFileChannel));
						}
					}
				}
			}

			// verify the expected length of the transaction was written
			Assert.isPremiseValid(
				written == contentLength,
				"Failed to write all bytes (" + written + " of " + contentLength + " Bytes) to WAL file!"
			);

			this.walFileChannel.force(true);
			this.lastWrittenCatalogVersion.set(transactionMutation.getCatalogVersion());

		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to append WAL to catalog `" + this.catalogName + "`!",
				"Failed to append WAL to catalog!",
				e
			);
		} finally {
			catalogKryoPool.free(kryo);
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

	@Override
	public void close() throws IOException {
		this.output.close();
		this.walFileChannel.close();
		this.transactionMutationOutputStream.close();
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
			final MutationSupplier supplier = createSupplier(catalogVersion, avoidPartiallyFilledBuffer, walFile);
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
	 * Creates a MutationSupplier object with the specified parameters.
	 *
	 * @param catalogVersion             the catalog version
	 * @param avoidPartiallyFilledBuffer whether to avoid partially filled buffer or not
	 * @param walFile                    the WAL file
	 * @return a new MutationSupplier object
	 */
	@Nonnull
	MutationSupplier createSupplier(long catalogVersion, boolean avoidPartiallyFilledBuffer, File walFile) {
		return new MutationSupplier(
			catalogVersion, walFile, this.walFileIndex, this.catalogKryoPool, this.transactionLocationsCache,
			avoidPartiallyFilledBuffer
		);
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
			// re-plan the scheduled cut to the moment when the next entry should be cut down
			this.scheduledExecutorService.schedule(
				this::cutWalCache,
				CUT_WAL_CACHE_AFTER_INACTIVITY_MS - (now - oldestNotCutEntryTouchTime) + 1,
				MILLISECONDS
			);
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
		 * The Write-Ahead Log (WAL) file reference
		 */
		private final File walFile;
		/**
		 * The index of the WAL file incremented each time the WAL file is rotated.
		 */
		private final int walFileIndex;
		/**
		 * The ObservableInput for reading {@link TransactionMutation} from the WAL file.
		 */
		private final ObservableInput<RandomAccessFileInputStream> observableInput;
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
		 * The current {@link TransactionMutation} being read from the WAL file.
		 */
		private TransactionMutationWithLocation transactionMutation;
		/**
		 * The expected total length of current transaction (leading mutation plus all other mutations).
		 */
		private int contentLength;
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
			@Nonnull File walFile,
			int walFileIndex,
			@Nonnull Pool<Kryo> catalogKryoPool,
			@Nonnull ConcurrentHashMap<Integer, TransactionLocations> transactionLocationsCache,
			boolean avoidPartiallyFilledBuffer
		) {
			this.walFile = walFile;
			this.walFileIndex = walFileIndex;
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
				ObservableInput<RandomAccessFileInputStream> theObservableInput;
				try {
					final RandomAccessFile randomWalFile = new RandomAccessFile(walFile, "r");
					theObservableInput = new ObservableInput<>(
						new RandomAccessFileInputStream(
							randomWalFile
						)
					);
					// fast path if the record is found in cache
					this.filePosition = 0L;
					this.filePosition = ofNullable(this.transactionLocationsCache.get(this.walFileIndex))
						.map(it -> it.findNearestLocation(catalogVersion))
						.orElse(0L);

					theObservableInput.seekWithUnknownLength(this.filePosition);

					TransactionMutationWithLocation transactionMutation = readAndRecordTransactionMutation(theObservableInput);
					// move cursor to the end of the lead mutation
					while (transactionMutation.getCatalogVersion() < catalogVersion) {
						// move cursor to the next transaction mutation
						this.filePosition += transactionMutation.getTransactionSpan().recordLength();
						theObservableInput.seekWithUnknownLength(this.filePosition);
						// read content length and leading transaction mutation
						transactionMutation = readAndRecordTransactionMutation(theObservableInput);
						// if the file is shorter than the expected size of the transaction mutation, we've reached EOF
						if (this.walFile.length() < this.filePosition + transactionMutation.getTransactionSpan().recordLength()) {
							transactionMutation = null;
							break;
						}
					}
					// we've reached the first transaction mutation with catalog version >= requested catalog version
					this.transactionMutation = transactionMutation;
				} catch (BufferUnderflowException e) {
					// we've reached EOF or the tx mutation hasn't been yet completely written
					this.transactionMutation = null;
					theObservableInput = null;
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to read WAL file `" + walFile.getName() + "`!",
						"Failed to read WAL file!",
						e
					);
				}
				this.observableInput = theObservableInput;
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
						if (currentFileLength <= this.filePosition) {
							// we've reached EOF
							return null;
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
			this.contentLength = theObservableInput.simpleIntRead();
			TransactionMutation transactionMutation = (TransactionMutation) StorageRecord.read(
				theObservableInput, (stream, length) -> kryo.readClassAndObject(stream)
			).payload();
			// measure the lead mutation size + verify the content length
			final int leadTransactionMutationSize = Math.toIntExact(theObservableInput.total() - totalBefore);
			Assert.isPremiseValid(
				this.contentLength + 4 == leadTransactionMutationSize + transactionMutation.getWalSizeInBytes(),
				"Invalid WAL file on position `" + this.filePosition + "`!"
			);
			this.transactionLocationsCache.computeIfAbsent(
				this.walFileIndex, it -> new TransactionLocations()
			).register(this.filePosition, transactionMutation);

			this.transactionsRead++;
			return new TransactionMutationWithLocation(
				transactionMutation,
				new FileLocation(this.filePosition, this.contentLength + 4),
				this.walFileIndex
			);
		}
	}

	/**
	 * Represents a TransactionMutation with additional location information.
	 */
	private static class TransactionMutationWithLocation extends TransactionMutation {
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
	private static class TransactionLocations {
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

}
