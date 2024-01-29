/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.exception.CorruptedKeyValueRecordException;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.PoolExhaustedException;
import io.evitadb.store.offsetIndex.exception.RecordNotYetWrittenException;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import io.evitadb.store.offsetIndex.model.NonFlushedValue;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.BitUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.CRC32C;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * OffsetIndex represents simple key-value storage that is append-only. Ie. no data are ever overwritten in the file created
 * by OffsetIndex. We know that appending the file is very fast operation in all OSes and all types of hard drives - so this
 * implementation build on top of this idea.
 *
 * The key concept here is that the file might contain "dead" data that are not mapped by current OffsetIndex instance.
 * This dead content of the file needs to be cleaned (or vacuumed) periodically so that OS page cache is more efficient
 * and does not contain fragments of the dead data.
 *
 * Single {@link FileLocation} information needs to be kept outside OffsetIndex. This location points to the last part
 * of the OffsetIndex fragment written in the file. This fragment contains latest "updates" (ie. inserts / deletes)
 * to the OffsetIndex and refers to previous fragment that contains updates done before. This chain points to initial
 * fragment that has no ancestor and this fragment contains the initial load of the OffsetIndex records. OffsetIndex fragments
 * are limited to the {@link #writeHandle} buffer limit - this is by default {@link StorageOptions#outputBufferSize()} in Bytes.
 * So even the initial OffsetIndex state might be split into several OffsetIndex fragments.
 *
 * OffsetIndex contains only set of keys that points to file locations in the mapped file. This is how main operations are
 * handled:
 *
 * WRITE:
 * - writes record to the end of the mapped file
 * - stores returned {@link FileLocation} along with key to {@link #keyToLocations}
 *
 * READ:
 * - looks up {@link FileLocation} by the passed key (this is expected to be fast)
 * - uses {@link RandomAccessFile} to seek the position in the file and reads its contents
 * - performance of this operation depends on the OS page cache - so the OS should have enough RAM left for this sake
 *
 * DELETE:
 * - removes record in the {@link #keyToLocations}
 * - information about the remove is also tracked in MemoryFragment (when written to disk) so that when OffsetIndex is
 * reconstructed from fragments the record inserted in previous fragments will be ignored as well
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@ThreadSafe
public class OffsetIndex {
	/**
	 * Initial size of the central {@link #keyToLocations} index.
	 */
	public static final int KEY_HASH_MAP_INITIAL_SIZE = 65_536;
	/**
	 * Initial size of the central {@link #histogram} index.
	 */
	public static final int HISTOGRAM_INITIAL_CAPACITY = 16;
	/**
	 * Contains configuration options for the {@link OffsetIndex},
	 */
	@Getter private final StorageOptions storageOptions;
	/**
	 * Contains configuration of record types that could be stored into the mem-table.
	 */
	@Getter private final OffsetIndexRecordTypeRegistry recordTypeRegistry;
	/**
	 * Single {@link Kryo} instance used for writing - it's internal {@link KeyCompressor} may be modified.
	 */
	private final Kryo writeKryo;
	/**
	 * Single output stream to the mapped file.
	 */
	private final WriteOnlyHandle writeHandle;
	/**
	 * Pool of {@link Kryo} instances which are not thread safe and are used for reading.
	 */
	private final FileOffsetIndexKryoPool readKryoPool;
	/**
	 * Pool of {@link ObservableInput} instances which are not thread safe.
	 */
	private final FileOffsetIndexObservableInputPool readOnlyHandlePool = new FileOffsetIndexObservableInputPool();
	/**
	 * List of all currently opened handles.
	 */
	private final CopyOnWriteArrayList<ReadOnlyHandle> readOnlyOpenedHandles;
	/**
	 * Contains flag that signalizes that shutdown procedure is active.
	 */
	private final AtomicBoolean shutdownDownProcedureActive = new AtomicBoolean(false);
	/**
	 * Map contains counts for each type of record stored in OffsetIndex.
	 */
	private final ConcurrentHashMap<Byte, Integer> histogram;
	/**
	 * Keeps track of maximum record size ever written to this OffsetIndex. The number doesn't respect record removals and
	 * should be used only for informational purposes.
	 */
	private final AtomicInteger maxRecordSize = new AtomicInteger(0);
	/**
	 * Keeps track of total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 */
	private final AtomicLong totalSize = new AtomicLong(0);
	/**
	 * Contains flag signalizing that OffsetIndex is open and can be used. Flag is set to false on {@link #close()} operation.
	 * No additional calls are allowed after that.
	 */
	@Getter private boolean operative = true;
	/**
	 * OffsetIndex descriptor used when creating OffsetIndex instance or created on last {@link #flush(long)} operation.
	 * Contains all information necessary to read/write data in OffsetIndex instance using {@link Kryo}.
	 */
	private OffsetIndexDescriptor fileOffsetDescriptor;
	/**
	 * Main index that keeps track of record keys file locations. Used for persisted record reading.
	 */
	private Map<RecordKey, FileLocation> keyToLocations;
	/**
	 * Non flushed values contains all values that has been modified in this OffsetIndex instance and their locations were
	 * not yet flushed to the disk. They might have been written to the disk, but their location is still only in memory
	 * and in case of crash they wouldn't be retrievable. Flush persists all file locations to disk and performs sync.
	 */
	private NonFlushedValues nonFlushedValues = new NonFlushedValues();
	/**
	 * This field contains the information about last known position that has been synced to the file on disk and can
	 * be safely read.
	 */
	private long lastSyncedPosition;

	public OffsetIndex(
		@Nonnull OffsetIndexDescriptor fileOffsetDescriptor,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle
	) {
		this.storageOptions = storageOptions;
		this.fileOffsetDescriptor = fileOffsetDescriptor;
		this.recordTypeRegistry = recordTypeRegistry;

		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
		this.readKryoPool = new FileOffsetIndexKryoPool(
			storageOptions.maxOpenedReadHandles(),
			version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
		);
		this.writeKryo = fileOffsetDescriptor.getWriteKryo();
		this.writeHandle = writeHandle;
		this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
		final Optional<FileOffsetIndexBuilder> fileOffsetIndexBuilder;
		if (this.lastSyncedPosition == 0) {
			fileOffsetIndexBuilder = Optional.empty();
		} else {
			fileOffsetIndexBuilder = Optional.of(
				readFileOffsetIndex(fileOffsetDescriptor.fileLocation())
			);
		}
		this.keyToLocations = fileOffsetIndexBuilder
			.map(FileOffsetIndexBuilder::getBuiltIndex)
			.orElseGet(() -> new ConcurrentHashMap<>(KEY_HASH_MAP_INITIAL_SIZE));
		this.histogram = fileOffsetIndexBuilder
			.map(FileOffsetIndexBuilder::getHistogram)
			.orElseGet(() -> new ConcurrentHashMap<>(HISTOGRAM_INITIAL_CAPACITY));
		fileOffsetIndexBuilder
			.ifPresent(it -> {
				this.totalSize.set(it.getTotalSize());
				this.maxRecordSize.set(it.getMaxSize());
			});
	}

	public OffsetIndex(
		@Nonnull Path filePath,
		@Nonnull FileLocation fileLocation,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nonnull BiFunction<FileOffsetIndexBuilder, ObservableInput<?>, ? extends OffsetIndexDescriptor> offsetIndexDescriptorFactory
	) {
		this.storageOptions = storageOptions;
		this.recordTypeRegistry = recordTypeRegistry;

		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
		this.writeHandle = writeHandle;
		this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
		if (this.lastSyncedPosition == 0) {
			throw new UnexpectedIOException(
				"Cannot create OffsetIndex from empty file: `" + filePath + "`!",
				"Cannot create OffsetIndex from empty file!"
			);
		}

		try (
			final ObservableInput<InputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(
					new RandomAccessFile(filePath.toFile(), "r"),
					true
				)
			)
		) {
			final FileOffsetIndexBuilder fileOffsetIndexBuilder = new FileOffsetIndexBuilder();
			OffsetIndexSerializationService.INSTANCE.deserialize(
				input,
				fileLocation,
				fileOffsetIndexBuilder
			);
			this.keyToLocations = fileOffsetIndexBuilder.getBuiltIndex();
			this.histogram = fileOffsetIndexBuilder.getHistogram();
			this.totalSize.set(fileOffsetIndexBuilder.getTotalSize());
			this.maxRecordSize.set(fileOffsetIndexBuilder.getMaxSize());
			this.fileOffsetDescriptor = offsetIndexDescriptorFactory.apply(fileOffsetIndexBuilder, input);
			this.readKryoPool = new FileOffsetIndexKryoPool(
				storageOptions.maxOpenedReadHandles(),
				version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
			);
			this.writeKryo = fileOffsetDescriptor.getWriteKryo();
		} catch (FileNotFoundException e) {
			throw new UnexpectedIOException(
				"Cannot create read offset file index from file `" + filePath + "`!",
				"OffsetIndex file not found! Critical error.",
				e
			);
		}
	}

	/**
	 * Returns version of the current OffsetIndexDescriptor instance. This version can be used to recognize, whether
	 * there was any real change made before and after {@link #flush(long)} or {@link #close()} operations.
	 */
	public long getVersion() {
		return fileOffsetDescriptor.version();
	}

	/**
	 * Returns readable instance of key compressor.
	 */
	public KeyCompressor getReadOnlyKeyCompressor() {
		return fileOffsetDescriptor.getReadOnlyKeyCompressor();
	}

	/**
	 * Returns unmodifiable map of current index of compressed keys.
	 * @return unmodifiable map of current index of compressed keys
	 */
	@Nonnull
	public Map<Integer, Object> getCompressedKeys() {
		return Collections.unmodifiableMap(
			fileOffsetDescriptor.getWriteKeyCompressor().getKeys()
		);
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE entries in the OffsetIndex.
	 */
	public Collection<Entry<RecordKey, FileLocation>> getEntries() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.entrySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE keys in the OffsetIndex.
	 */
	public Collection<RecordKey> getKeys() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.keySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE file locations in the OffsetIndex.
	 */
	public Collection<FileLocation> getFileLocations() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.values());
	}

	/**
	 * Returns current count of ACTIVE entries in OffsetIndex.
	 */
	public int count() {
		assertOperative();
		return keyToLocations.size() + nonFlushedValues.count();
	}

	/**
	 * Returns current count of ACTIVE entries of certain type in OffsetIndex.
	 */
	public int count(Class<? extends StoragePart> recordType) {
		assertOperative();
		final byte recordTypeId = recordTypeRegistry.idFor(recordType);
		return ofNullable(histogram.get(recordTypeId)).orElse(0) + nonFlushedValues.count(recordTypeId);
	}

	/**
	 * Returns value assigned to the primary key.
	 *
	 * Beware method may not return previously written record via {@link #put(long, StoragePart)} until method
	 * {@link #flush(long)} is called. In this situation {@link RecordNotYetWrittenException} is thrown.
	 */
	@Nullable
	public <T extends StoragePart> T get(long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();
		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final NonFlushedValue nonFlushedValue = this.nonFlushedValues.get(key);
		if (nonFlushedValue == null) {
			return ofNullable(keyToLocations.get(key))
				.map(it -> doGet(recordType, primaryKey, it))
				.map(StorageRecord::payload)
				.orElse(null);
		} else if (nonFlushedValue.isRemoval()) {
			return null;
		} else {
			try {
				// if the record was not yet flushed to the disk we need to enforce sync so that we can read it
				if (lastSyncedPosition < nonFlushedValue.fileLocation().getEndPosition()) {
					doSoftFlush();
				}
				//noinspection unchecked
				return (T) get(nonFlushedValue.fileLocation(), recordTypeRegistry.typeFor(nonFlushedValue.recordType()));
			} catch (KryoException exception) {
				throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
			}
		}
	}

	/**
	 * Returns value assigned to the particular location in OffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> T get(FileLocation location, @Nonnull Class<T> recordType) {
		return doGet(recordType, -1, location).payload();
	}

	/**
	 * Returns unparsed value assigned to the primary key.
	 *
	 * Beware method may not return previously written record via {@link #put(long, StoragePart)} until method
	 * {@link #flush(long)} is called. In this situation {@link RecordNotYetWrittenException} is thrown.
	 */
	@Nullable
	public <T extends StoragePart> byte[] getBinary(long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();

		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final NonFlushedValue nonFlushedValue = this.nonFlushedValues.get(key);
		if (nonFlushedValue == null) {
			return ofNullable(keyToLocations.get(key))
				.map(it -> doGetBinary(recordType, primaryKey, it))
				.map(StorageRecord::payload)
				.orElse(null);
		} else if (nonFlushedValue.isRemoval()) {
			return null;
		} else {
			try {
				// if the record was not yet flushed to the disk we need to enforce sync so that we can read it
				if (lastSyncedPosition < nonFlushedValue.fileLocation().getEndPosition()) {
					doSoftFlush();
				}
				return getBinary(nonFlushedValue.fileLocation(), recordTypeRegistry.typeFor(nonFlushedValue.recordType()));
			} catch (KryoException exception) {
				throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
			}
		}
	}

	/**
	 * Returns value assigned to the particular location in OffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> byte[] getBinary(FileLocation location, @Nonnull Class<T> recordType) {
		return doGetBinary(recordType, -1, location).payload();
	}

	/**
	 * Returns true if {@link OffsetIndex} contains record with this id and type.
	 */
	public <T extends StoragePart> boolean contains(long primaryKey, Class<T> recordType) {
		assertOperative();
		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final NonFlushedValue nonFlushedValue = nonFlushedValues.nonFlushedValueIndex.get(key);
		if (nonFlushedValue == null) {
			return ofNullable(keyToLocations.get(key)).isPresent();
		} else if (nonFlushedValue.isRemoval()) {
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Stores or overwrites record with passed primary key in OffsetIndex. Values of different types are distinguished by
	 * the OffsetIndex so that two different types of objects with same primary keys don't overwrite each other.
	 *
	 * @param catalogVersion will be propagated to {@link StorageRecord#transactionId()}
	 * @param value          value to be stored
	 */
	public <T extends StoragePart> long put(long catalogVersion, @Nonnull T value) {
		return writeHandle.checkAndExecute(
			"Storing record",
			this::assertOperative,
			exclusiveWriteAccess -> {
				final long partId = ofNullable(value.getStoragePartPK())
					.orElseGet(() -> value.computeUniquePartIdAndSet(fileOffsetDescriptor.getWriteKeyCompressor()));
				doPut(
					catalogVersion,
					partId, value,
					exclusiveWriteAccess
				);
				return partId;
			}
		);
	}

	/**
	 * Removes existing record with passed primary key in OffsetIndex. True is returned if particular record is found and
	 * removed.
	 *
	 * @param primaryKey primary key of the record that is removed
	 * @param recordType type of the container that is connected with the passed id
	 */
	public <T extends StoragePart> boolean remove(long primaryKey, @Nonnull Class<T> recordType) {
		// mark dirty read
		final byte recordTypeId = recordTypeRegistry.idFor(recordType);
		final RecordKey key = new RecordKey(recordTypeId, primaryKey);
		// the record may have not yet propagated to the `keyToLocations`
		final NonFlushedValue nonFlushedValue = nonFlushedValues.get(key);

		if (keyToLocations.containsKey(key) || nonFlushedValue != null) {
			writeHandle.checkAndExecute(
				"Removing record",
				this::assertOperative,
				exclusiveWriteAccess -> {
					doRemove(
						primaryKey,
						recordType
					);
					return null;
				}
			);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * This method will check whether the related OffsetIndex file is consistent with internal rules - it checks:
	 *
	 * - whether there is non interrupted monotonic row of transactionIds
	 * - whether the final record has control bit that closes the transaction
	 * - whether all the records has CRC-32C checksum valid (when CRC32-C checksums are enabled)
	 */
	public FileOffsetIndexStatistics verifyContents() {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				inputStream -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> {
							final FileOffsetIndexStatistics result = new FileOffsetIndexStatistics(this.keyToLocations.size(), this.totalSize.get());
							inputStream.resetToPosition(0);
							final CRC32C crc32C = storageOptions.computeCRC32C() ? new CRC32C() : null;
							byte[] buffer = new byte[inputStream.getBuffer().length];
							int recCount = 0;
							long startPosition = 0;
							long prevTransactionId = 0;
							boolean firstTransaction = true;
							boolean transactionCleared = true;
							final long fileLength = readOnlyFileHandle.getLastWrittenPosition();
							do {
								recCount++;
								final int finalRecCount = recCount;

								try {
									inputStream.resetToPosition(startPosition);
									// computed record length without CRC32 checksum
									int recordLength = inputStream.readInt();
									final byte control = inputStream.readByte();
									final long catalogVersion = inputStream.readLong();
									final long finalStartPosition = startPosition;

									// verify that transactional id is monotonically increasing
									if (!firstTransaction && !(transactionCleared ? catalogVersion > prevTransactionId : catalogVersion >= prevTransactionId)) {
										throw new CorruptedRecordException(
											"Transaction id record monotonic row is violated in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + catalogVersion,
											prevTransactionId + 1, catalogVersion
										);
									}
									// verify that transaction id stays the same within transaction block
									if (!transactionCleared && catalogVersion != prevTransactionId) {
										throw new CorruptedRecordException(
											"Transaction id was not cleared with control bit record id in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + catalogVersion,
											prevTransactionId, catalogVersion
										);
									}

									ofNullable(crc32C).ifPresent(CRC32C::reset);
									// first 4 bytes of length are not part of the CRC check
									int processedRecordLength = StorageRecord.CRC_NOT_COVERED_HEAD;
									inputStream.resetToPosition(startPosition + processedRecordLength);
									// we have to avoid reading last 8 bytes of CRC check value
									while (processedRecordLength < recordLength - ObservableOutput.TAIL_MANDATORY_SPACE) {
										final int read = inputStream.read(buffer, 0, Math.min(recordLength - processedRecordLength - ObservableOutput.TAIL_MANDATORY_SPACE, buffer.length));
										ofNullable(crc32C).ifPresent(it -> it.update(buffer, 0, read));
										processedRecordLength += read;
									}
									// verify CRC32-C checksum
									if (crc32C != null) {
										crc32C.update(control);
										final long computedChecksum = crc32C.getValue();
										inputStream.resetToPosition(startPosition + recordLength - ObservableOutput.TAIL_MANDATORY_SPACE);
										final long storedChecksum = inputStream.readLong();
										processedRecordLength += ObservableOutput.TAIL_MANDATORY_SPACE;
										if (computedChecksum != storedChecksum) {
											throw new CorruptedRecordException(
												"Invalid checksum for record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B]", computedChecksum, storedChecksum
											);
										}
									}

									if (processedRecordLength != recordLength) {
										throw new CorruptedRecordException(
											"Record no. " + finalRecCount + " prematurely ended -  file position: [" + finalStartPosition + ", length " + recordLength + "B]", processedRecordLength, recordLength
										);
									}

									startPosition += recordLength;
									prevTransactionId = catalogVersion;
									transactionCleared = BitUtils.isBitSet(control, StorageRecord.TRANSACTION_CLOSING_BIT);
									if (transactionCleared && catalogVersion > 0L) {
										firstTransaction = false;
									}
									result.registerRecord(recordLength);

								} catch (KryoException ex) {
									throw new CorruptedRecordException(
										"Record no. " + finalRecCount + " cannot be read!", ex
									);
								}
							} while (fileLength > result.getTotalSize());

							return result;
						}
					);
				}
			)
		);
	}

	/**
	 * Flushes current state of the OffsetIndex to the disk. File contents are in sync when this method finalizes.
	 *
	 * @param catalogVersion will be propagated to {@link StorageRecord#transactionId()}
	 */
	@Nonnull
	public OffsetIndexDescriptor flush(long catalogVersion) {
		assertOperative();
		this.fileOffsetDescriptor = doFlush(catalogVersion, fileOffsetDescriptor, false);
		return fileOffsetDescriptor;
	}

	/**
	 * Closes the OffsetIndex and writes all data to disk. File contents are in sync when this method finalizes.
	 * No additional operations with this instance will be possible after calling this method. All file handles are
	 * released.
	 */
	@Nonnull
	public FileLocation close() {
		assertOperative();
		// this will forbid new read handles to be created
		operative = false;
		try {
			if (!shutdownDownProcedureActive.compareAndExchange(false, true)) {
				// spinning lock to close all opened handles once they occur free in pool
				long start = System.currentTimeMillis();
				while (!readOnlyOpenedHandles.isEmpty() && System.currentTimeMillis() - start > storageOptions.waitOnCloseSeconds() * 1000) {
					if (readOnlyHandlePool.getFree() > 0) {
						final ReadOnlyHandle handleToClose = readOnlyHandlePool.obtain();
						try {
							handleToClose.execute(
								exclusiveReadAccess -> {
									exclusiveReadAccess.close();
									return null;
								});
							readOnlyOpenedHandles.remove(handleToClose);
						} catch (Exception ex) {
							log.error("Read handle cannot be closed!", ex);
							// ignore this - we need to close other files
						}
					}
				}
				// these handles were not released by the clients within the timeout
				for (ReadOnlyHandle readOnlyOpenedHandle : readOnlyOpenedHandles) {
					readOnlyOpenedHandle.forceClose();
				}
				this.readOnlyOpenedHandles.clear();
				// at last flush OffsetIndex and close write handle
				this.fileOffsetDescriptor = doFlush(0L, fileOffsetDescriptor, true);
				return fileOffsetDescriptor.fileLocation();
			} else {
				throw new EvitaInternalError("OffsetIndex is already being closed!");
			}
		} finally {
			shutdownDownProcedureActive.compareAndExchange(true, false);
		}
	}

	/**
	 * Returns position of last fragment of the current {@link OffsetIndex} in the tracked file.
	 */
	public FileLocation getFileOffsetIndexLocation() {
		return fileOffsetDescriptor.fileLocation();
	}

	/**
	 * Returns collection of all non-flushed records to store.
	 */
	public Collection<NonFlushedValue> getNonFlushedEntries() {
		return this.nonFlushedValues.nonFlushedValueIndex.values();
	}

	/**
	 * Method allows to execute custom "(de)serialization" function in the context of current OffsetIndex Kryo read
	 * instance. The serialization MUST NOT attempt to produce new keys via. {@link KeyCompressor} otherwise the
	 * exception will be thrown.
	 */
	public <T> T executeWithKryo(@Nonnull Function<Kryo, T> logic) {
		return this.readKryoPool.borrowAndExecute(
			logic::apply
		);
	}

	/**
	 * Returns record id for passed type of {@link StoragePart}.
	 */
	public byte getIdForRecordType(Class<? extends StoragePart> storagePartClass) {
		return recordTypeRegistry.idFor(storagePartClass);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Just for testing purposes - verifies whether the OffsetIndex contents equals the other OffsetIndex contents.
	 */
	boolean fileOffsetIndexEquals(@Nonnull OffsetIndex o) {
		if (this == o) return true;
		return keyToLocations.equals(o.keyToLocations);
	}

	/**
	 * Checks whether the OffsetIndex is still opened and operative.
	 */
	private void assertOperative() {
		isPremiseValid(
			operative || Boolean.TRUE.equals(shutdownDownProcedureActive.get()),
			"OffsetIndex has been already closed!"
		);
	}

	/**
	 * Reads OffsetIndex from the disk using write handle.
	 */
	private FileOffsetIndexBuilder readFileOffsetIndex(@Nonnull FileLocation location) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					final FileOffsetIndexBuilder builder = new FileOffsetIndexBuilder();
					return readKryoPool.borrowAndExecute(kryo -> {
						OffsetIndexSerializationService.INSTANCE.deserialize(
							exclusiveReadAccess,
							location,
							builder
						);
						return builder;
					});
				}
			)
		);
	}

	/**
	 * Flushes current OffsetIndex data (and it's changes) to the disk. File is synced within this method. Frequent flushes
	 * limit the I/O performance.
	 */
	@Nonnull
	private OffsetIndexDescriptor doFlush(
		long catalogVersion,
		@Nonnull OffsetIndexDescriptor fileOffsetIndexDescriptor,
		boolean close
	) {
		if (!this.nonFlushedValues.isEmpty()) {
			final OffsetIndexDescriptor newFileOffsetIndexDescriptor = writeHandle.checkAndExecuteAndSync(
				"Writing mem table",
				this::assertOperative,
				outputStream -> {
					// serialize all non-flushed values to the output stream
					return OffsetIndexSerializationService.INSTANCE.serialize(
						this,
						outputStream,
						catalogVersion
					);
				},
				(outputStream, fileLocation) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
					// now empty all NonFlushedValues and move them to current state
					promoteNonFlushedValuesToSharedState();
					// propagate changes in KeyCompressor to the read kryo pool
					if (fileOffsetIndexDescriptor.resetDirty()) {
						this.readKryoPool.expireAllPreviouslyCreated();
					}
					// create new OffsetIndexDescriptor with updated version
					return new OffsetIndexDescriptor(
						fileLocation,
						fileOffsetIndexDescriptor
					);
				}
			);

			if (close) {
				writeHandle.close();
			}

			return newFileOffsetIndexDescriptor;
		} else {
			return fileOffsetIndexDescriptor;
		}
	}

	/**
	 * Method executes soft flush meaning, that all records currently held in the buffer are fSynced on disk so that
	 * they can be read. This soft flush happens outside regular flushes that we want to do not so frequently, but
	 * when there is request to read the record, that has been just written, and it still sits in non-flushed memory,
	 * we have to enforce the flush.
	 */
	private void doSoftFlush() {
		if (!this.nonFlushedValues.isEmpty()) {
			writeHandle.checkAndExecuteAndSync(
				"Syncing changes to disk.",
				this::assertOperative,
				it -> {
				}
			);
			// propagate changes in KeyCompressor to the read kryo pool
			if (fileOffsetDescriptor.resetDirty()) {
				this.fileOffsetDescriptor = new OffsetIndexDescriptor(
					fileOffsetDescriptor.fileLocation(),
					fileOffsetDescriptor
				);
				this.readKryoPool.expireAllPreviouslyCreated();
			}
		}
	}

	/**
	 * Method moves all `nonFlushedValues` to a `keyToLocations` entries and purges them from the main memory.
	 */
	private void promoteNonFlushedValuesToSharedState() {
		// promote changes to shared state
		final Map<RecordKey, FileLocation> newKeyToLocations = createHashMap(this.keyToLocations.size() + this.nonFlushedValues.count());
		newKeyToLocations.putAll(this.keyToLocations);

		final NonFlushedValues locationsToProcess = this.nonFlushedValues;
		this.nonFlushedValues = new NonFlushedValues();
		for (Entry<RecordKey, NonFlushedValue> entry : locationsToProcess.entrySet()) {
			final RecordKey recordKey = entry.getKey();
			final NonFlushedValue nonFlushedValue = entry.getValue();

			final int count;
			final int recordLength;

			if (nonFlushedValue.isRemoval()) {
				final FileLocation removedLocation = newKeyToLocations.remove(recordKey);
				// location might not exist when value was created and immediately removed
				if (removedLocation != null) {
					count = -1;
					recordLength = -removedLocation.recordLength();
				} else {
					count = 0;
					recordLength = 0;
				}
			} else {
				final FileLocation recordLocation = nonFlushedValue.fileLocation();
				recordLength = recordLocation.recordLength();
				if (recordLength > maxRecordSize.get()) {
					this.maxRecordSize.set(recordLength);
				}
				newKeyToLocations.put(recordKey, recordLocation);
				count = 1;
			}
			// update statistics
			this.totalSize.addAndGet(recordLength);
			this.histogram.merge(
				recordKey.recordType(), count, Integer::sum
			);
		}

		this.keyToLocations = newKeyToLocations;
	}

	/**
	 * Method removes existing record from the OffsetIndex. This method should be called only from singleton writer and
	 * never directly from the code. All writes are serialized by exclusive write access.
	 */
	private void doRemove(long primaryKey, @Nonnull Class<? extends StoragePart> valueType) {
		final byte recordType = recordTypeRegistry.idFor(valueType);
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final FileLocation fileLocation = ofNullable(this.nonFlushedValues.get(key))
			.filter(it -> !it.isRemoval())
			.map(NonFlushedValue::fileLocation)
			.orElseGet(() -> this.keyToLocations.get(key));

		isTrue(
			fileLocation != null,
			"There is no record `" + primaryKey + "` of type `" + valueType + "`!"
		);
		this.nonFlushedValues.remove(key, fileLocation);
	}

	/**
	 * Method stores new record to the OffsetIndex. This method should be called only from singleton writer and never
	 * directly from the code. All writes are serialized by exclusive write access.
	 */
	private void doPut(long catalogVersion, long primaryKey, @Nonnull StoragePart value, @Nonnull ObservableOutput<?> exclusiveWriteAccess) {
		final byte recordType = recordTypeRegistry.idFor(value.getClass());
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final boolean update = keyToLocations.containsKey(key);
		final FileLocation recordLocation = new StorageRecord<>(
			writeKryo,
			exclusiveWriteAccess,
			catalogVersion,
			false,
			value
		).fileLocation();

		// mark dirty read
		this.nonFlushedValues.put(
			key,
			new NonFlushedValue(primaryKey, recordType, recordLocation),
			!update
		);
	}

	/**
	 * Method retrieves existing record from the OffsetIndex.
	 */
	private <T extends Serializable> StorageRecord<T> doGet(@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> {
							try {
								return StorageRecord.read(
									exclusiveReadAccess,
									it,
									(stream, length) -> kryo.readObject(stream, recordType)
								);
							} catch (CorruptedRecordException ex) {
								throw new CorruptedKeyValueRecordException(
									"Record " + primaryKey + " of type " + recordType.getName() + " is corrupted after reading!",
									recordType, primaryKey, ex
								);
							}
						});
				}
			)
		);
	}

	/**
	 * Method retrieves existing record from the OffsetIndex without parsing its contents.
	 */
	private <T extends Serializable> StorageRecord<byte[]> doGetBinary(@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> {
							try {
								return StorageRecord.read(
									exclusiveReadAccess,
									it,
									(stream, length) -> stream.readBytes(length - StorageRecord.OVERHEAD_SIZE)
								);
							} catch (CorruptedRecordException ex) {
								throw new CorruptedKeyValueRecordException(
									"Record " + primaryKey + " of type " + recordType.getName() + " is corrupted after reading!",
									recordType, primaryKey, ex
								);
							}
						});
				}
			)
		);
	}

	/**
	 * Contains statistics about the OffsetIndex file.
	 */
	@RequiredArgsConstructor
	@Getter
	public static class FileOffsetIndexStatistics {
		private final long livingRecordCount;
		private final long livingRecordSize;
		private int recordCount;
		private long totalSize;
		private int maxRecordSize;

		public double getLivingObjectShare() {
			return (double) livingRecordSize / (double) totalSize;
		}

		void registerRecord(int length) {
			this.recordCount++;
			this.totalSize += length;
			if (length > maxRecordSize) {
				this.maxRecordSize = length;
			}
		}

	}

	/**
	 * This class / instance collects all information connected with the data that may or may be not present
	 * in the persistent storage. In the meanwhile the data still needs to be accessible by the readers so this
	 * implementation needs to use concurrent implementations of the data structures.
	 *
	 * Instance is discarded with each {@link #flush(long)} invocation because after the flush is finished all data must
	 * be correctly and safely retrievable from the disk.
	 */
	@ThreadSafe
	private static class NonFlushedValues {
		private final ConcurrentHashMap<RecordKey, NonFlushedValue> nonFlushedValueIndex = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<Byte, Integer> nonFlushedValuesHistogram = new ConcurrentHashMap<>();

		/**
		 * Returns instance of the record by its key if present in non-flushed index.
		 */
		@Nullable
		public NonFlushedValue get(@Nonnull RecordKey key) {
			return nonFlushedValueIndex.get(key);
		}

		/**
		 * Stores instance of the record to the non-flushed index.
		 *
		 * @param create - when true it affects {@link #histogram} results
		 */
		public void put(@Nonnull RecordKey key, @Nonnull NonFlushedValue value, boolean create) {
			if (create) {
				nonFlushedValuesHistogram.merge(key.recordType(), 1, Integer::sum);
			}
			nonFlushedValueIndex.put(key, value);
		}

		/**
		 * Stores information about record removal to the non-flushed index.
		 * This will prevent loading record from the persistent storage even if its present there.
		 */
		public void remove(@Nonnull RecordKey key, @Nonnull FileLocation fileLocation) {
			nonFlushedValuesHistogram.merge(key.recordType(), -1, Integer::sum);
			nonFlushedValueIndex.put(key, new NonFlushedValue(key.primaryKey(), (byte) (key.recordType() * -1), fileLocation));
		}

		/**
		 * Returns true if the non-flushed index is empty.
		 */
		public boolean isEmpty() {
			return nonFlushedValueIndex.isEmpty();
		}

		/**
		 * Returns iterator over all non-flushed records.
		 */
		@Nonnull
		public Iterable<? extends Entry<RecordKey, NonFlushedValue>> entrySet() {
			return nonFlushedValueIndex.entrySet();
		}

		/**
		 * Returns count of all non-flushed records affecting {@link OffsetIndex#histogram} - may be positive or negative.
		 * Represents the diff against {@link OffsetIndex#histogram}.
		 */
		public int count() {
			return nonFlushedValuesHistogram.values().stream().mapToInt(it -> it).sum();
		}

		/**
		 * Returns count of all non-flushed records of specific type affecting {@link OffsetIndex#histogram} - may be
		 * positive or negative. Represents the diff against {@link OffsetIndex#histogram}.
		 */
		public int count(byte recordTypeId) {
			return ofNullable(nonFlushedValuesHistogram.get(recordTypeId)).orElse(0);
		}

	}

	/**
	 * This class is used to build initial OffsetIndex in {@link OffsetIndexSerializationService} and switch atomically
	 * the real (operative) OffsetIndex contents atomically once it's done.
	 */
	@Getter
	public static class FileOffsetIndexBuilder {
		private final ConcurrentHashMap<RecordKey, FileLocation> builtIndex = new ConcurrentHashMap<>(KEY_HASH_MAP_INITIAL_SIZE);
		private final ConcurrentHashMap<Byte, Integer> histogram = new ConcurrentHashMap<>(HISTOGRAM_INITIAL_CAPACITY);
		private long totalSize;
		private int maxSize;

		public void register(RecordKey recordKey, FileLocation fileLocation) {
			this.builtIndex.put(recordKey, fileLocation);
			this.histogram.merge(recordKey.recordType(), 1, Integer::sum);
			this.totalSize += fileLocation.recordLength();
			if (this.maxSize < fileLocation.recordLength()) {
				this.maxSize = fileLocation.recordLength();
			}
		}

		public boolean contains(RecordKey recordKey) {
			return builtIndex.containsKey(recordKey);
		}
	}

	/**
	 * This class is used to safely borrow and return Kryo instances to the pool.
	 */
	@ThreadSafe
	public static class FileOffsetIndexKryoPool extends Pool<VersionedKryo> {
		/**
		 * Function allows creating new instance of {@link VersionedKryo} with current Pool version.
		 */
		private final Function<Long, VersionedKryo> supplier;
		/**
		 * Version increases only by calling {@link #expireAllPreviouslyCreated()} method and allows to discard all
		 * obsolete {@link VersionedKryo} instances when they are about to be returned back to pool.
		 */
		private long version = 1L;

		public FileOffsetIndexKryoPool(int maxInstancesKept, @Nonnull Function<Long, VersionedKryo> supplier) {
			super(true, false, maxInstancesKept);
			this.supplier = supplier;
		}

		/**
		 * Method allowing safe way for obtaining {@link Kryo} instance and returning it back to the pool.
		 */
		public <T> T borrowAndExecute(@Nonnull Function<VersionedKryo, T> logic) {
			final VersionedKryo kryo = this.obtain();
			try {
				return logic.apply(kryo);
			} finally {
				this.free(kryo);
			}
		}

		/**
		 * This method will increase version of this pool which makes all previously created {@link VersionedKryo}
		 * instances obsolete. Borrowed instances will still work but when they are returned back by {@link #free(VersionedKryo)}
		 * method they are not accepted back to pool and they are going to be garbage collected. New {@link VersionedKryo}
		 * instances will be created on their place and these new versions will possibly have new configuration of key
		 * internal inputs ({@link VersionedKryoKeyInputs}).
		 */
		public void expireAllPreviouslyCreated() {
			this.version++;
			this.clear();
		}

		/**
		 * Creates new instance of {@link VersionedKryo} with current configuration of {@link VersionedKryoKeyInputs}.
		 */
		@Override
		protected VersionedKryo create() {
			return supplier.apply(version);
		}

		/**
		 * Returns borrowed instance back to the pool.
		 */
		@Override
		public void free(VersionedKryo object) {
			// if object version is the same as actual version, accept it,
			// otherwise it would be discarded and garbage collected
			if (object.getVersion() == version) {
				super.free(object);
			}
		}

	}

	/**
	 * This class is used to monitor and limit {@link ReadOnlyHandle} pool. It creates new handles on demand in
	 * locked fashion and verifies that maximum opened handles limit is not exceeded.
	 */
	private class FileOffsetIndexObservableInputPool extends Pool<ReadOnlyHandle> {
		private final ReentrantLock readFilesLock = new ReentrantLock();

		private FileOffsetIndexObservableInputPool() {
			super(true, false);
		}

		public <T> T borrowAndExecute(Function<ReadOnlyHandle, T> logic) {
			final ReadOnlyHandle readOnlyFileHandle = this.obtain();
			try {
				return logic.apply(readOnlyFileHandle);
			} finally {
				this.free(readOnlyFileHandle);
			}
		}

		@Override
		protected ReadOnlyHandle create() {
			try {
				if (readFilesLock.tryLock(storageOptions.lockTimeoutSeconds(), TimeUnit.SECONDS)) {
					try {
						final ReadOnlyHandle readOnlyFileHandle = writeHandle.toReadOnlyHandle();
						if (readOnlyOpenedHandles.size() >= storageOptions.maxOpenedReadHandles()) {
							throw new PoolExhaustedException(storageOptions.maxOpenedReadHandles(), readOnlyFileHandle.toString());
						}
						readOnlyOpenedHandles.add(readOnlyFileHandle);
						return readOnlyFileHandle;
					} finally {
						readFilesLock.unlock();
					}
				}
				throw new StorageException("New handle to the file couldn't have been created within timeout!");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new StorageException("New handle to the file couldn't have been created due to interrupt!");
			}

		}

	}

}
