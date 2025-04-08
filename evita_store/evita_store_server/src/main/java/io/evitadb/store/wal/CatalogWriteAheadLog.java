/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTooBigException;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor.EntityCollectionChanges;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor.TransactionChanges;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.async.DelayedAsyncTask;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.transaction.WalCacheSizeChangedEvent;
import io.evitadb.core.metric.event.transaction.WalRotationEvent;
import io.evitadb.core.metric.event.transaction.WalStatisticsEvent;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.exception.CatalogWriteAheadLastTransactionMismatchException;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.store.wal.supplier.ReverseMutationSupplier;
import io.evitadb.store.wal.supplier.TransactionLocations;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.store.wal.transaction.TransactionMutationSerializer;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
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
public class CatalogWriteAheadLog implements AutoCloseable {
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
	 * The storage options for the catalog.
	 */
	private final StorageOptions storageOptions;
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
	 * Reference to the current WAL file information record.
	 */
	private final AtomicReference<CurrentWalFile> currentWalFile = new AtomicReference<>();
	/**
	 * Contains information about the last catalog version that was successfully stored (and its WAL contents processed).
	 */
	private final AtomicLong processedCatalogVersion;
	/**
	 * Contains reference to the asynchronous task executor that clears the cached WAL pointers after some
	 * time of inactivity.
	 */
	private final DelayedAsyncTask cutWalCacheTask;
	/**
	 * Contains reference to the asynchronous task executor that removes the obsolete WAL files.
	 */
	private final DelayedAsyncTask removeWalFileTask;
	/**
	 * Cache of already scanned WAL files. The locations might not be complete, but they will be always cover the start
	 * of the particular WAL file, but they may be later appended with new records that are not yet scanned or gradually
	 * added to the working WAL file. The index key in this map is the {@link CurrentWalFile#walFileIndex} of the WAL file.
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
	private final LongConsumer bootstrapFileTrimmer;
	/**
	 * List of pending removals of WAL files that should be removed, but could not be removed yet because the WAL
	 * records in them were not yet processed.
	 */
	private final List<PendingRemoval> pendingRemovals = new CopyOnWriteArrayList<>();
	/**
	 * Callback to be called when the WAL file is purged.
	 */
	private final WalPurgeCallback onWalPurgeCallback;

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
	 * @param storageOptions   a flag indicating whether to compute CRC32C checksums for the input
	 * @return the last fully written catalog version found in the WAL file
	 */
	@Nonnull
	static FirstAndLastCatalogVersions checkAndTruncate(
		@Nonnull String catalogName,
		@Nonnull Path walFilePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions
	) {
		if (!walFilePath.toFile().exists()) {
			// WAL file does not exist, nothing to check
			return new FirstAndLastCatalogVersions(-1L, -1L);
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
				final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
					new RandomAccessFileInputStream(walFile, true)
				)
			) {
				if (storageOptions.computeCRC32C()) {
					input.computeCRC32();
				}
				if (storageOptions.compress()) {
					input.compress();
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
					final TransactionMutation firstTransactionMutation = Objects.requireNonNull(
						(TransactionMutation) StorageRecord.read(
							input, (stream, length) -> kryo.readClassAndObject(stream)
						).payload()
					);

					input.seekWithUnknownLength(lastTxStartPosition + 4);
					final TransactionMutation transactionMutation = Objects.requireNonNull(
						(TransactionMutation) StorageRecord.read(
							input, (stream, length) -> kryo.readClassAndObject(stream)
						).payload()
					);

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
	 * Returns the first and last catalog versions found in the given WAL file.
	 *
	 * @param walFile the WAL file to read from
	 * @return the first and last catalog versions found in the WAL file
	 */
	@Nonnull
	static FirstAndLastCatalogVersions getFirstAndLastCatalogVersionsFromWalFile(@Nonnull File walFile) throws FileNotFoundException {
		try (
			final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(walFile, "r");
			final RandomAccessFileInputStream inputStream = new RandomAccessFileInputStream(randomAccessOldWalFile, true);
			final Input input = new Input(inputStream)
		) {
			inputStream.seek(walFile.length() - WAL_TAIL_LENGTH);
			final long firstCatalogVersion = input.readLong();
			final long lastCatalogVersion = input.readLong();
			return new FirstAndLastCatalogVersions(firstCatalogVersion, lastCatalogVersion);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to read `" + walFile.getAbsolutePath() + "`!",
				"Failed to read WAL file!", e
			);
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
	 * @return the first and last WAL file index
	 */
	private static int[] getFirstAndLastWalFileIndex(@Nonnull Path catalogStoragePath, @Nonnull String catalogName) {
		final File[] walFiles = ofNullable(
			catalogStoragePath.toFile().listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX))
		).orElse(new File[0]);

		if (walFiles.length == 0) {
			return new int[]{0, 0};
		} else {
			final List<Integer> indexes = Arrays.stream(walFiles)
				.map(f -> getIndexFromWalFileName(catalogName, f.getName()))
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
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer bootstrapFileTrimmer,
		@Nonnull WalPurgeCallback onWalPurgeCallback
	) {
		this.catalogName = catalogName;
		this.catalogKryoPool = catalogKryoPool;
		this.processedCatalogVersion = new AtomicLong(catalogVersion);
		this.storageOptions = storageOptions;
		this.cutWalCacheTask = new DelayedAsyncTask(
			catalogName, "WAL cache cutter",
			scheduler,
			this::cutWalCache,
			CUT_WAL_CACHE_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
		this.removeWalFileTask = new DelayedAsyncTask(
			catalogName, "WAL file remover",
			scheduler,
			this::removeWalFiles,
			0, TimeUnit.MILLISECONDS
		);
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.catalogStoragePath = catalogStoragePath;
		this.computeCRC32C = storageOptions.computeCRC32C();
		this.bootstrapFileTrimmer = bootstrapFileTrimmer;
		this.onWalPurgeCallback = onWalPurgeCallback;
		int walFileIndex = -1;
		try {
			final int[] firstAndLastWalFileIndex = getFirstAndLastWalFileIndex(catalogStoragePath, catalogName);
			walFileIndex = firstAndLastWalFileIndex[1];
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, walFileIndex));
			final FirstAndLastCatalogVersions versions = checkAndTruncate(
				catalogName, walFilePath, catalogKryoPool, storageOptions
			);

			// create the WAL file if it does not exist
			createWalFile(walFilePath, true);

			final FileChannel walFileChannel = FileChannel.open(
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

			this.currentWalFile.set(
				new CurrentWalFile(
					walFileIndex,
					versions.firstCatalogVersion(),
					versions.lastCatalogVersion(),
					walFilePath,
					walFileChannel,
					this.computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + getWalFileName(catalogName, walFileIndex) + "`!",
				"Failed to open WAL file!",
				e
			);
		}
	}

	/**
	 * Constructor for internal use only. It is used to create a new WAL file with the given parameters.
	 */
	public CatalogWriteAheadLog(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		int walFileIndex
	) {
		this.catalogName = catalogName;
		this.storageOptions = storageOptions;
		this.catalogStoragePath = catalogStoragePath;
		this.catalogKryoPool = catalogKryoPool;
		this.processedCatalogVersion = new AtomicLong(catalogVersion);
		this.cutWalCacheTask = new DelayedAsyncTask(
			catalogName, "WAL cache cutter",
			scheduler,
			this::cutWalCache,
			CUT_WAL_CACHE_AFTER_INACTIVITY_MS, TimeUnit.MILLISECONDS
		);
		this.removeWalFileTask = new DelayedAsyncTask(
			catalogName, "WAL file remover",
			scheduler,
			this::removeWalFiles,
			0, TimeUnit.MILLISECONDS
		);
		this.maxWalFileSizeBytes = transactionOptions.walFileSizeBytes();
		this.walFileCountKept = transactionOptions.walFileCountKept();
		this.computeCRC32C = storageOptions.computeCRC32C();
		this.bootstrapFileTrimmer = null;
		this.onWalPurgeCallback = null;

		try {
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, walFileIndex));
			final FileChannel walFileChannel = FileChannel.open(
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

			this.currentWalFile.set(
				new CurrentWalFile(
					walFileIndex,
					-1,
					-1,
					walFilePath,
					walFileChannel,
					computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);
		} catch (IOException e) {
			throw new WriteAheadLogCorruptedException(
				"Failed to open WAL file `" + getWalFileName(catalogName, walFileIndex) + "`!",
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
		// emit the event with information about first available transaction in the WAL
		final File firstWalFile = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, getWalFileIndex())).toFile();
		if (firstWalFile.length() > 0) {
			final TransactionMutation firstAvailableTransaction = getFirstTransactionMutationFromWalFile(
				firstWalFile
			);
			new WalStatisticsEvent(
				this.catalogName,
				firstAvailableTransaction.getCommitTimestamp()
			).commit();
		}
	}

	/**
	 * Retrieves the first catalog version of the current WAL file.
	 *
	 * @return the first catalog version of the current WAL file
	 */
	public long getFirstCatalogVersionOfCurrentWalFile() {
		return this.currentWalFile.get().getFirstCatalogVersionOfCurrentWalFile();
	}

	/**
	 * Retrieves the last written catalog version.
	 *
	 * @return the last written catalog version
	 */
	public long getLastWrittenCatalogVersion() {
		return this.currentWalFile.get().getLastWrittenCatalogVersion();
	}

	/**
	 * Appends a transaction mutation to the Write-Ahead Log (WAL) file.
	 *
	 * @param transactionMutation The transaction mutation to append.
	 * @param walReference        The reference to the WAL file.
	 * @return the number of Bytes written
	 */
	@SuppressWarnings("StringConcatenationMissingWhitespace")
	public long append(
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

		if (this.currentWalFile.get().getCurrentWalFileSize() + transactionMutation.getWalSizeInBytes() + 4 > this.maxWalFileSizeBytes) {
			// rotate the WAL file
			rotateWalFile();
		}

		final Kryo kryo = catalogKryoPool.obtain();
		try {
			// write transaction mutation to memory buffer
			this.transactionMutationOutputStream.reset();
			final CurrentWalFile theCurrentWalFile = this.currentWalFile.get();
			theCurrentWalFile.checkNextCatalogVersionMatch(transactionMutation.getCatalogVersion());

			final ObservableOutput<ByteArrayOutputStream> output = theCurrentWalFile.getOutput();
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
			this.contentLengthBuffer.clear();
			writeIntToByteBuffer(this.contentLengthBuffer, contentLengthWithTxMutation);

			// then write the transaction mutation to the memory buffer as the first record of the transaction
			this.contentLengthBuffer.put(this.transactionMutationOutputStream.toByteArray(), 0, record.fileLocation().recordLength());

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

			theCurrentWalFile.initFirstCatalogVersionOfCurrentWalFileIfNecessary(
				transactionMutation.getCatalogVersion(),
				() -> {
					// emit the event with information about the first transaction in the WAL
					new WalStatisticsEvent(
						this.catalogName,
						transactionMutation.getCommitTimestamp()
					).commit();
				}
			);
			final int writtenLength = 4 + writtenHead + writtenContent;
			theCurrentWalFile.updateLastWrittenCatalogVersion(transactionMutation.getCatalogVersion(), writtenLength);

			// clean up the folder if empty
			walReference.getFilePath()
				.map(Path::getParent)
				.ifPresent(FileUtils::deleteFolderIfEmpty);

			return writtenLength;

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
	 * @param startCatalogVersion the catalog version to start reading from
	 * @return a stream of committed mutations
	 */
	@Nonnull
	public Stream<Mutation> getCommittedMutationStream(long startCatalogVersion) {
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
	public Stream<Mutation> getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(long startCatalogVersion, long requestedCatalogVersion) {
		return getCommittedMutationStream(startCatalogVersion, requestedCatalogVersion);
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
		final CurrentWalFile theCurrentWalFile = this.currentWalFile.get();
		final File walFile = theCurrentWalFile.getWalFilePath().toFile();
		if (!walFile.exists() || walFile.length() < 4) {
			// WAL file does not exist or is empty, nothing to read
			return Stream.empty();
		} else {
			final ReverseMutationSupplier supplier;
			if (this.getFirstCatalogVersionOfCurrentWalFile() <= catalogVersion) {
				// we could start reading the current WAL file
				supplier = new ReverseMutationSupplier(
					catalogVersion, this.catalogName, this.catalogStoragePath, this.storageOptions,
					theCurrentWalFile.getWalFileIndex(), this.catalogKryoPool, this.transactionLocationsCache,
					this::updateCacheSize
				);
			} else {
				// we need to find older WAL file
				final int foundWalIndex = findWalIndexFor(catalogVersion);
				supplier = new ReverseMutationSupplier(
					catalogVersion, this.catalogName, this.catalogStoragePath, this.storageOptions,
					foundWalIndex, this.catalogKryoPool, this.transactionLocationsCache,
					this::updateCacheSize
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
			walFilePath = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, getWalFileIndex()));
			startPosition = 0L;
		} else {
			walFilePath = currentCatalogVersion.toFilePath(this.catalogStoragePath);
			final FileLocation fileLocation = Objects.requireNonNull(currentCatalogVersion.fileLocation());
			startPosition = fileLocation.startingPosition() + fileLocation.recordLength();
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
				return of(
					Objects.requireNonNull(
						(TransactionMutation) StorageRecord.read(
							input, (stream, length) -> this.catalogKryoPool.obtain().readClassAndObject(stream)
						).payload()
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
	 * @param catalogVersion              the catalog version to describe
	 * @param previousKnownCatalogVersion the previous known catalog version (delimits transactions incorporated in
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
				previousKnownCatalogVersion + 1, null
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

	/**
	 * Notify method that allows to track which versions of the catalog have been already processed and safe to be
	 * removed in the future.
	 *
	 * @param catalogVersion the catalog version that has been processed
	 */
	public void walProcessedUntil(long catalogVersion) {
		this.processedCatalogVersion.set(catalogVersion);
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
	 * Creates a MutationSupplier object with the specified parameters.
	 *
	 * @param startCatalogVersion     the catalog version to start reading from
	 * @param requestedCatalogVersion the minimal catalog version to finish reading
	 * @return a new MutationSupplier object
	 */
	@Nonnull
	MutationSupplier createSupplier(long startCatalogVersion, @Nullable Long requestedCatalogVersion) {
		final long theFirstCatalogVersionOfCurrentWalFile = this.getFirstCatalogVersionOfCurrentWalFile();

		// when requested catalog version is provided it means we may be reading the WAL file that is being appended
		// but we may rely on that the transaction with requested catalog version is already written and readable
		final Optional<Long> requestedCatalogVersionOptional = ofNullable(requestedCatalogVersion);
		final boolean avoidPartiallyFilledBuffer = requestedCatalogVersionOptional.isPresent();
		final long theRequestedCatalogVersion = requestedCatalogVersionOptional.orElse(Long.MAX_VALUE);

		if (theFirstCatalogVersionOfCurrentWalFile != -1 && theFirstCatalogVersionOfCurrentWalFile <= startCatalogVersion) {
			// we could start reading the current WAL file
			return new MutationSupplier(
				startCatalogVersion, theRequestedCatalogVersion,
				this.catalogName, this.catalogStoragePath, this.storageOptions,
				getWalFileIndex(), this.catalogKryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer,
				this::updateCacheSize
			);
		} else {
			// we need to find older WAL file
			final int foundWalIndex = findWalIndexFor(startCatalogVersion);
			return new MutationSupplier(
				startCatalogVersion, theRequestedCatalogVersion,
				this.catalogName, this.catalogStoragePath, this.storageOptions,
				foundWalIndex, this.catalogKryoPool, this.transactionLocationsCache,
				avoidPartiallyFilledBuffer,
				this::updateCacheSize
			);
		}
	}

	/**
	 * Removes the obsolete WAL files from the catalog storage path.
	 */
	long removeWalFiles() {
		synchronized (this.pendingRemovals) {
			final long catalogVersion = this.processedCatalogVersion.get();
			final Set<PendingRemoval> toRemove = new HashSet<>(64);

			long firstCatalogVersionToBeKept = -1;
			for (PendingRemoval pendingRemoval : this.pendingRemovals) {
				if (pendingRemoval.catalogVersion() <= catalogVersion) {
					toRemove.add(pendingRemoval);
					pendingRemoval.removeLambda().run();
					if (pendingRemoval.catalogVersion() > firstCatalogVersionToBeKept) {
						firstCatalogVersionToBeKept = pendingRemoval.catalogVersion();
					}
				} else {
					break;
				}
			}

			if (!toRemove.isEmpty()) {
				this.pendingRemovals.removeAll(toRemove);
				// first trim the bootstrap record file
				this.bootstrapFileTrimmer.accept(firstCatalogVersionToBeKept);
				// call the listener to remove the obsolete files
				if (firstCatalogVersionToBeKept > -1) {
					this.onWalPurgeCallback.purgeFilesUpTo(firstCatalogVersionToBeKept);
				}
			}

			return -1;
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
		final CurrentWalFile theCurrentWalFile = this.currentWalFile.get();
		// if the particular version is within current file, return it
		final long firstCatalogVersionOfCurrentWalFile = theCurrentWalFile.getFirstCatalogVersionOfCurrentWalFile();
		final int currentWalFileIndex = theCurrentWalFile.getWalFileIndex();
		if ((catalogVersion >= firstCatalogVersionOfCurrentWalFile && catalogVersion <= theCurrentWalFile.getLastWrittenCatalogVersion()) ||
			// of if the version is lower than the start version of current WAL and the WAL is the first one
			(catalogVersion < firstCatalogVersionOfCurrentWalFile && currentWalFileIndex == 0)) {
			return currentWalFileIndex;
		}

		final int[] walIndexesToSearch = Arrays.stream(
				Objects.requireNonNull(
					this.catalogStoragePath.toFile().listFiles(
						(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
					)
				)
			)
			.mapToInt(it -> getIndexFromWalFileName(this.catalogName, it.getName()))
			.filter(it -> it != currentWalFileIndex)
			.sorted()
			.toArray();

		long previousLastCatalogVersion = -1;
		int foundWalIndex = -1;
		for (int indexToSearch : walIndexesToSearch) {
			final File oldWalFile = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, indexToSearch)).toFile();
			if (oldWalFile.length() > WAL_TAIL_LENGTH) {
				try (
					final RandomAccessFile randomAccessOldWalFile = new RandomAccessFile(oldWalFile, "r");
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(randomAccessOldWalFile, true)
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

					// if the version looks for is lower than the first version ever, return the first file
					if (catalogVersion < firstCatalogVersion && indexToSearch == 0) {
						foundWalIndex = indexToSearch;
						break;
					} else if (catalogVersion >= firstCatalogVersion && catalogVersion <= lastCatalogVersion) {
						foundWalIndex = indexToSearch;
						break;
					}

					previousLastCatalogVersion = lastCatalogVersion;
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
		final WalRotationEvent event = new WalRotationEvent(catalogName);
		final CurrentWalFile theCurrentWalFile = this.currentWalFile.get();
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
			this.contentLengthBuffer.flip(); // Switch the buffer from writing mode to reading mode
			while (this.contentLengthBuffer.hasRemaining()) {
				written += theCurrentWalFile.getWalFileChannel().write(this.contentLengthBuffer);
			}
			Assert.isPremiseValid(
				written == WAL_TAIL_LENGTH,
				"Failed to write tail to WAL file!"
			);
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
			final Path walFilePath = this.catalogStoragePath.resolve(getWalFileName(this.catalogName, newWalFileIndex));

			// create the WAL file if it does not exist
			createWalFile(walFilePath, false);
			final FileChannel walFileChannel = FileChannel.open(
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

			this.currentWalFile.set(
				new CurrentWalFile(
					newWalFileIndex,
					-1L, -1L,
					walFilePath,
					walFileChannel,
					this.computeCRC32C ? theOutput.computeCRC32() : theOutput,
					walFileChannel.size()
				)
			);

			// list all existing WAL files and remove the oldest ones when their count exceeds the limit
			final File[] walFiles = this.catalogStoragePath.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			if (walFiles != null && walFiles.length > this.walFileCountKept) {
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
					try {
						final FirstAndLastCatalogVersions versionsFromWalFile = getFirstAndLastCatalogVersionsFromWalFile(walFile);
						final PendingRemoval pendingRemoval = new PendingRemoval(
							versionsFromWalFile.lastCatalogVersion() + 1,
							() -> {
								try {
									if (walFile.delete()) {
										log.debug("Deleted WAL file `" + walFile + "`!");
									} else {
										// don't throw exception - this is not so critical so that we should stop accepting new mutations
										log.error("Failed to delete WAL file `" + walFile + "`!");
									}
								} catch (Exception ex) {
									log.error("Failed to delete WAL file `" + walFile + "`!", ex);
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
				"Failed to open WAL file `" + getWalFileName(this.catalogName, newWalFileIndex) + "`!",
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
				0L, 0L, this.catalogName, this.catalogStoragePath, this.storageOptions,
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
	 * @param startCatalogVersion     the catalog version to start reading from
	 * @param requestedCatalogVersion the minimal catalog version to finish reading
	 * @return a stream of committed mutations
	 */
	@Nonnull
	private Stream<Mutation> getCommittedMutationStream(long startCatalogVersion, @Nullable Long requestedCatalogVersion) {
		final MutationSupplier supplier = createSupplier(startCatalogVersion, requestedCatalogVersion);
		this.cutWalCacheTask.schedule();
		return Stream.generate(supplier)
			.takeWhile(Objects::nonNull)
			.onClose(supplier::close);
	}

	/**
	 * Check the cached WAL entries whether they should be cut because of long inactivity or left intact. This method
	 * also re-plans the next cache cut if the cache is not empty.
	 */
	private long cutWalCache() {
		final long threshold = System.currentTimeMillis() - CUT_WAL_CACHE_AFTER_INACTIVITY_MS;
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
	 * Record contains information about currently use WAL file.
	 */
	static class CurrentWalFile implements Closeable {
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
		 * The index of the WAL file incremented each time the WAL file is rotated.
		 */
		@Getter private final int walFileIndex;
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
		private final ObservableOutput<ByteArrayOutputStream> output;
		/**
		 * Field contains current size of the WAL file the records are appended to in Bytes.
		 */
		private long currentWalFileSize;
		/**
		 * Field indicates whether the WAL file is closed.
		 */
		private boolean closed = false;

		public CurrentWalFile(
			int walFileIndex,
			long firstCatalogVersion,
			long lastCatalogVersion,
			@Nonnull Path walFilePath,
			@Nonnull FileChannel walFileChannel,
			@Nonnull ObservableOutput<ByteArrayOutputStream> output,
			long size
		) {
			this.walFileIndex = walFileIndex;
			this.firstCatalogVersionOfCurrentWalFile.set(firstCatalogVersion);
			this.lastWrittenCatalogVersion.set(lastCatalogVersion);
			this.walFilePath = walFilePath;
			this.walFileChannel = walFileChannel;
			this.output = output;
			this.currentWalFileSize = size;
		}

		@Nonnull
		public Path getWalFilePath() {
			assertOpen();
			return walFilePath;
		}

		@Nonnull
		public FileChannel getWalFileChannel() {
			assertOpen();
			return walFileChannel;
		}

		@Nonnull
		public ObservableOutput<ByteArrayOutputStream> getOutput() {
			assertOpen();
			return output;
		}

		public long getCurrentWalFileSize() {
			return currentWalFileSize;
		}

		public long getFirstCatalogVersionOfCurrentWalFile() {
			return firstCatalogVersionOfCurrentWalFile.get();
		}

		public long getLastWrittenCatalogVersion() {
			return lastWrittenCatalogVersion.get();
		}

		/**
		 * Initializes the first catalog version of the current WAL file if it is not set yet and runs the given action.
		 *
		 * @param catalogVersion the catalog version to set
		 * @param andThen        the action to run after the catalog version is set
		 */
		public void initFirstCatalogVersionOfCurrentWalFileIfNecessary(long catalogVersion, @Nonnull Runnable andThen) {
			if (this.firstCatalogVersionOfCurrentWalFile.get() == -1) {
				this.firstCatalogVersionOfCurrentWalFile.set(catalogVersion);
				andThen.run();
			}
		}

		/**
		 * Updates the last written catalog version and the current WAL file size by the given written length and updated
		 * catalog version.
		 *
		 * @param catalogVersion the updated catalog version
		 * @param writtenLength  the length of the written record
		 */
		public void updateLastWrittenCatalogVersion(long catalogVersion, int writtenLength) {
			checkNextCatalogVersionMatch(catalogVersion);
			this.lastWrittenCatalogVersion.set(catalogVersion);
			this.currentWalFileSize += writtenLength;
		}

		/**
		 * Closes the current WAL file.
		 *
		 * @throws IOException if an I/O error occurs
		 */
		public void close() throws IOException {
			this.closed = true;
			this.output.close();
			this.walFileChannel.close();
		}

		/**
		 * Checks if the next catalog version matches the expected order.
		 *
		 * The method validates that the provided catalog version is either the start of a new sequence
		 * (when the current last catalog version is -1) or the subsequent version of the last written one.
		 * It throws a {@link GenericEvitaInternalError} if this condition is not met.
		 *
		 * @param catalogVersion the catalog version to verify against the expected sequence
		 */
		private void checkNextCatalogVersionMatch(long catalogVersion) {
			final long currentLastCatalogVersion = this.lastWrittenCatalogVersion.get();
			Assert.isPremiseValid(
				currentLastCatalogVersion == -1 || currentLastCatalogVersion + 1 == catalogVersion,
				() -> new CatalogWriteAheadLastTransactionMismatchException(
					currentLastCatalogVersion,
					"Invalid catalog version to write to the WAL file!",
					"Invalid catalog version `" + catalogVersion + "`! Expected: `" + (currentLastCatalogVersion + 1) + "`, but got `" + catalogVersion + "`!"
				)
			);
		}

		/**
		 * Asserts that the current WAL file is open.
		 */
		private void assertOpen() {
			Assert.isPremiseValid(
				!closed,
				"The current WAL file is already closed!"
			);
		}
	}

	/**
	 * Contains first and last catalog versions found in current WAL file.
	 *
	 * @param firstCatalogVersion first catalog version
	 * @param lastCatalogVersion  last catalog version
	 */
	record FirstAndLastCatalogVersions(
		long firstCatalogVersion,
		long lastCatalogVersion
	) {
	}

	/**
	 * Record that holds information about pending removal of the WAL file.
	 *
	 * @param catalogVersion the catalog version that needs to be processed before the removal
	 * @param removeLambda   the removeLambda that performs the file removal
	 *                       and returns first transaction mutation in the removed file
	 */
	private record PendingRemoval(
		long catalogVersion,
		@Nonnull Runnable removeLambda
	) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			PendingRemoval that = (PendingRemoval) o;
			return catalogVersion == that.catalogVersion;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(catalogVersion);
		}

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
