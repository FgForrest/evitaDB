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
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor.EntityCollectionChanges;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor.TransactionChanges;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.transaction.WalCacheSizeChangedEvent;
import io.evitadb.core.metric.event.transaction.WalRotationEvent;
import io.evitadb.core.scheduling.DelayedAsyncTask;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.store.wal.supplier.ReverseMutationSupplier;
import io.evitadb.store.wal.supplier.TransactionLocations;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import lombok.AccessLevel;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getIndexFromWalFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

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
	 * At the end of each WAL file there is two longs written with start and end catalog version in the WAL.
	 */
	public static final int WAL_TAIL_LENGTH = 16;
	/**
	 * The time after which the WAL cache is dropped after inactivity.
	 */
	private static final long CUT_WAL_CACHE_AFTER_INACTIVITY_MS = 300_000L; // 5 minutes
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
	private final DelayedAsyncTask cutWalCacheTask;
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
	 * This lambda allows trimming the bootstrap file to the given date.
	 */
	private final Consumer<OffsetDateTime> bootstrapFileTrimmer;
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
	 * @param catalogName     the name of the catalog
	 * @param walFilePath     the path to the WAL file to check and truncate
	 * @param catalogKryoPool the Kryo object pool to use for deserialization
	 * @param computeCRC32C   a flag indicating whether to compute CRC32C checksums for the input
	 * @return the last fully written catalog version found in the WAL file
	 */
	@Nonnull
	static FirstAndLastCatalogVersions checkAndTruncate(@Nonnull String catalogName, @Nonnull Path walFilePath, @Nonnull Pool<Kryo> catalogKryoPool, boolean computeCRC32C) {
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
					final DataFileCompactEvent event = new DataFileCompactEvent(
						catalogName,
						FileType.WAL,
						FileType.WAL.name()
					);

					// truncate the WAL file to the last consistent transaction
					walFile.setLength(consistentLength);

					// emit the event
					event.finish().commit();
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

	/**
	 * Creates a new instance of TransactionChanges based on the given parameters.
	 *
	 * @param txMutation           the transaction mutation.
	 * @param catalogSchemaChanges the number of catalog schema changes.
	 * @param aggregations         the map of entity collection changes.
	 * @return a new instance of TransactionChanges.
	 */
	@Nonnull
	private static TransactionChanges createTransactionChanges(
		@Nonnull TransactionMutation txMutation,
		int catalogSchemaChanges,
		@Nonnull Map<String, EntityCollectionChangesTriple> aggregations
	) {
		return new TransactionChanges(
			txMutation.getCatalogVersion(),
			txMutation.getCommitTimestamp(),
			catalogSchemaChanges,
			txMutation.getMutationCount(),
			txMutation.getWalSizeInBytes(),
			aggregations.entrySet().stream()
				.map(
					it -> new EntityCollectionChanges(
						it.getKey(),
						it.getValue().getSchemaChanges(),
						it.getValue().getUpserted(),
						it.getValue().getRemoved()
					)
				)
				.sorted(Comparator.comparing(EntityCollectionChanges::entityName))
				.toArray(EntityCollectionChanges[]::new)
		);
	}

	public CatalogWriteAheadLog(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Consumer<OffsetDateTime> bootstrapFileTrimmer
	) {
		this.walFileIndex = getLastWalFileIndex(catalogStoragePath, catalogName);
		this.cutWalCacheTask = new DelayedAsyncTask(
			catalogName, "WAL cache cutter",
			scheduler,
			this::cutWalCache,
			CUT_WAL_CACHE_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.catalogStoragePath = catalogStoragePath;
		this.computeCRC32C = storageOptions.computeCRC32C();
		this.bootstrapFileTrimmer = bootstrapFileTrimmer;
		try {
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, this.walFileIndex));
			final FirstAndLastCatalogVersions versions = checkAndTruncate(
				catalogName, walFilePath, catalogKryoPool, storageOptions.computeCRC32C()
			);
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

			// clean up the folder if empty
			walReference.getFilePath()
				.map(Path::getParent)
				.ifPresent(FileUtils::deleteFolderIfEmpty);

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
	 * Retrieves a stream of committed mutations starting from the given catalog version and going backwards towards
	 * the start of the history. Each transaction mutation will have catalog version lesser than the previous one.
	 * Mutations in the transaction are also returned in reverse order.
	 * The stream is generated by reading mutations from the Write-Ahead Log (WAL) file.
	 *
	 * @param catalogVersion the catalog version from which to start retrieving mutations
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<Mutation> getCommittedReversedMutationStream(long catalogVersion) {
		final File walFile = this.walFilePath.toFile();
		if (!walFile.exists() || walFile.length() < 4) {
			// WAL file does not exist or is empty, nothing to read
			return Stream.empty();
		} else {
			final ReverseMutationSupplier supplier;
			if (this.getFirstCatalogVersionOfCurrentWalFile() <= catalogVersion) {
				// we could start reading the current WAL file
				supplier = new ReverseMutationSupplier(
					catalogVersion, this.catalogName, this.catalogStoragePath, this.walFileIndex,
					this.catalogKryoPool, this.transactionLocationsCache, this::updateCacheSize
				);
			} else {
				// we need to find older WAL file
				final int foundWalIndex = findWalIndexFor(catalogVersion);
				supplier = new ReverseMutationSupplier(
					catalogVersion, this.catalogName, this.catalogStoragePath, foundWalIndex,
					this.catalogKryoPool, this.transactionLocationsCache, this::updateCacheSize
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

	/**
	 * Calculates descriptor for particular version in history.
	 *
	 * @param catalogVersion              the catalog version to describe
	 * @param previousKnownCatalogVersion the previous known catalog version (delimites transactions incorporated in
	 *                                    previous version of the catalog), -1 if there is no known previous version
	 * @param introducedAt                the time when the version was introduced
	 * @return the descriptor for the version in history or NULL if the version is not present in the WAL
	 */
	@Nullable
	public CatalogVersionDescriptor getCatalogVersionDescriptor(
		long catalogVersion,
		long previousKnownCatalogVersion,
		@Nonnull OffsetDateTime introducedAt
	) {
		try (
			final MutationSupplier supplier = createSupplier(
				previousKnownCatalogVersion + 1, false
			)
		) {
			TransactionMutation txMutation = (TransactionMutation) supplier.get();
			if (txMutation == null) {
				return null;
			} else {
				final List<TransactionChanges> txChanges = new ArrayList<>(Math.toIntExact(catalogVersion - previousKnownCatalogVersion));
				final Map<String, EntityCollectionChangesTriple> aggregations = CollectionUtils.createHashMap(16);
				int catalogSchemaChanges = 0;
				while (txMutation != null) {
					final Mutation nextMutation = supplier.get();
					if (nextMutation instanceof TransactionMutation anotherTxMutation) {
						txChanges.add(
							createTransactionChanges(txMutation, catalogSchemaChanges, aggregations)
						);
						if (anotherTxMutation.getCatalogVersion() == catalogVersion + 1) {
							txMutation = null;
						} else {
							txMutation = anotherTxMutation;
							aggregations.clear();
							catalogSchemaChanges = 0;
						}
					} else if (nextMutation == null) {
						txChanges.add(
							createTransactionChanges(txMutation, catalogSchemaChanges, aggregations)
						);
						txMutation = null;
					} else {
						if (nextMutation instanceof ModifyEntitySchemaMutation schemaMutation) {
							aggregations
								.computeIfAbsent(schemaMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordSchemaChange();
						} else if (nextMutation instanceof LocalCatalogSchemaMutation) {
							catalogSchemaChanges++;
						} else if (nextMutation instanceof EntityUpsertMutation entityMutation) {
							aggregations
								.computeIfAbsent(entityMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordUpsert();
						} else if (nextMutation instanceof EntityRemoveMutation entityMutation) {
							aggregations
								.computeIfAbsent(entityMutation.getEntityType(), s -> new EntityCollectionChangesTriple())
								.recordRemoval();
						} else {
							throw new InvalidMutationException(
								"Unexpected mutation type: " + nextMutation.getClass().getName(),
								"Unexpected mutation type."
							);
						}
					}
				}
				return new CatalogVersionDescriptor(
					catalogVersion,
					introducedAt,
					txChanges.toArray(TransactionChanges[]::new)
				);
			}
		}
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
				avoidPartiallyFilledBuffer,
				this::updateCacheSize
			);
		} else {
			// we need to find older WAL file
			final int foundWalIndex = findWalIndexFor(catalogVersion);
			return new MutationSupplier(
				catalogVersion, this.catalogName, this.catalogStoragePath, foundWalIndex,
				this.catalogKryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer,
				this::updateCacheSize
			);
		}
	}

	/**
	 * Finds the index of a write-ahead log (WAL) file associated with a given catalog version.
	 *
	 * @param catalogVersion The catalog version to search for.
	 * @return The index of the WAL file containing the specified catalog version, or -1 if no such file is found.
	 * @throws WriteAheadLogCorruptedException If an error occurs while reading the WAL files or if the catalog version
	 *                                         is found to be invalid or inconsistent.
	 */
	private int findWalIndexFor(long catalogVersion) {
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
		final WalRotationEvent event = new WalRotationEvent(catalogName);
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

		OffsetDateTime firstCommitTimestamp = null;
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

				// assert that at least one file remains
				Assert.isPremiseValid(
					walFiles.length > this.walFileCountKept,
					"Failed to rotate WAL file! At least one WAL file should remain!"
				);

				// then delete all files except the last `walFileCountKept` files
				for (int i = 0; i < walFiles.length - this.walFileCountKept; i++) {
					final File walFile = walFiles[i];
					if (!walFile.delete()) {
						// don't throw exception - this is not so critical so that we should stop accepting new mutations
						log.error("Failed to delete WAL file `" + walFile + "`!");
					}
				}

				// now check the date and time of the leading transaction of the oldest WAL file
				final TransactionMutation firstMutation = getFirstTransactionMutationFromWalFile(
					walFiles[walFiles.length - this.walFileCountKept]
				);
				firstCommitTimestamp = firstMutation.getCommitTimestamp();
				this.bootstrapFileTrimmer.accept(firstCommitTimestamp);
			}

		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + getWalFileName(catalogName, this.walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		} finally {
			// emit the event
			event.finish(firstCommitTimestamp).commit();
		}
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
			final MutationSupplier mutationSupplier = new MutationSupplier(
				0L, this.catalogName, this.catalogStoragePath,
				getIndexFromWalFileName(this.catalogName, walFile.getName()),
				this.catalogKryoPool, this.transactionLocationsCache, false,
				this::updateCacheSize
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
			this.cutWalCacheTask.schedule();
			return Stream.generate(supplier)
				.takeWhile(Objects::nonNull)
				.onClose(supplier::close);
		}
	}

	/**
	 * Check the cached WAL entries whether they should be cut because of long inactivity or left intact. This method
	 * also re-plans the next cache cut if the cache is not empty.
	 */
	private long cutWalCache() {
		final long threshold = System.currentTimeMillis() - CUT_WAL_CACHE_AFTER_INACTIVITY_MS;
		long oldestNotCutEntryTouchTime = -1L;
		for (TransactionLocations locations : transactionLocationsCache.values()) {
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
		updateCacheSize();
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		return oldestNotCutEntryTouchTime > -1L ? (oldestNotCutEntryTouchTime - threshold) + 1 : -1L;
	}

	/**
	 * Method fires event that actuates the cache size information in metrics.
	 */
	private void updateCacheSize() {
		// emit the event
		new WalCacheSizeChangedEvent(
			this.catalogName,
			this.transactionLocationsCache.size()
		).commit();
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

	/**
	 * Represents a triplet of recorded changes in an entity collection.
	 */
	@Getter
	private static class EntityCollectionChangesTriple {
		/**
		 * The number of schema mutations.
		 */
		private int schemaChanges;
		/**
		 * The number of upserted entities.
		 */
		private int upserted;
		/**
		 * The number of removed entities.
		 */
		private int removed;

		/**
		 * Increments the count of upserted entities in the entity collection changes.
		 */
		public void recordUpsert() {
			this.upserted++;
		}

		/**
		 * Records the removal of an entity in the entity collection changes.
		 */
		public void recordRemoval() {
			this.removed++;
		}

		/**
		 * Increments the count of schema changes in the entity collection changes.
		 */
		public void recordSchemaChange() {
			this.schemaChanges++;
		}

	}

}
