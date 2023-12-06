/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.fileOffsetIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.exception.StorageException;
import io.evitadb.store.fileOffsetIndex.WriteOnlyFileHandle.ExclusiveWriteAccess;
import io.evitadb.store.fileOffsetIndex.exception.CorruptedKeyValueRecordException;
import io.evitadb.store.fileOffsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.fileOffsetIndex.exception.PoolExhaustedException;
import io.evitadb.store.fileOffsetIndex.exception.RecordNotYetWrittenException;
import io.evitadb.store.fileOffsetIndex.exception.SyncFailedException;
import io.evitadb.store.fileOffsetIndex.model.FileOffsetIndexRecordTypeRegistry;
import io.evitadb.store.fileOffsetIndex.model.NonFlushedValue;
import io.evitadb.store.fileOffsetIndex.model.RecordKey;
import io.evitadb.store.fileOffsetIndex.model.StorageRecord;
import io.evitadb.store.fileOffsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.zip.CRC32C;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * FileOffsetIndex represents simple key-value storage that is append-only. Ie. no data are ever overwritten in the file created
 * by FileOffsetIndex. We know that appending the file is very fast operation in all OSes and all types of hard drives - so this
 * implementation build on top of this idea.
 *
 * The key concept here is that the file might contain "dead" data that are not mapped by current FileOffsetIndex instance.
 * This dead content of the file needs to be cleaned (or vacuumed) periodically so that OS page cache is more efficient
 * and does not contain fragments of the dead data.
 *
 * Single {@link FileLocation} information needs to be kept outside FileOffsetIndex. This location points to the last part
 * of the FileOffsetIndex fragment written in the file. This fragment contains latest "updates" (ie. inserts / deletes)
 * to the FileOffsetIndex and refers to previous fragment that contains updates done before. This chain points to initial
 * fragment that has no ancestor and this fragment contains the initial load of the FileOffsetIndex records. FileOffsetIndex fragments
 * are limited to the {@link #writeHandle} buffer limit - this is by default {@link StorageOptions#outputBufferSize()} in Bytes.
 * So even the initial FileOffsetIndex state might be split into several FileOffsetIndex fragments.
 *
 * FileOffsetIndex contains only set of keys that points to file locations in the mapped file. This is how main operations are
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
 * - information about the remove is also tracked in MemoryFragment (when written to disk) so that when FileOffsetIndex is
 * reconstructed from fragments the record inserted in previous fragments will be ignored as well
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@ThreadSafe
public class FileOffsetIndex {
	/**
	 * Constant that contains currently the single node id. This is reserved for future use when those node ids should
	 * distinguish different origin nodes in clustered environments.
	 */
	public static final byte SINGLE_NODE_ID = 1;
	/**
	 * Initial size of the central {@link #keyToLocations} index.
	 */
	public static final int KEY_HASH_MAP_INITIAL_SIZE = 65_536;
	/**
	 * Initial size of the central {@link #histogram} index.
	 */
	public static final int HISTOGRAM_INITIAL_CAPACITY = 16;
	/**
	 * Contains path to the file mapped by the FileOffsetIndex. No other processes / threads should write to this file except
	 * this FileOffsetIndex instance.
	 */
	@Getter private final Path targetFile;
	/**
	 * Contains configuration options for the {@link FileOffsetIndex},
	 */
	@Getter private final StorageOptions options;
	/**
	 * Contains configuration of record types that could be stored into the mem-table.
	 */
	private final FileOffsetIndexRecordTypeRegistry recordTypeRegistry;
	/**
	 * Single {@link Kryo} instance used for writing - it's internal {@link KeyCompressor} may be modified.
	 */
	private final Kryo writeKryo;
	/**
	 * Single output stream to the mapped file.
	 */
	private final WriteOnlyFileHandle writeHandle;
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
	private final List<ReadOnlyFileHandle> readOnlyOpenedHandles;
	/**
	 * Contains flag that signalizes that shutdown procedure is active.
	 */
	private final AtomicBoolean shutdownDownProcedureActive = new AtomicBoolean(false);
	/**
	 * Map contains counts for each type of record stored in FileOffsetIndex.
	 */
	private final ConcurrentHashMap<Byte, Integer> histogram;
	/**
	 * Keeps track of maximum record size ever written to this FileOffsetIndex. The number doesn't respect record removals and
	 * should be used only for informational purposes.
	 */
	private final AtomicInteger maxRecordSize = new AtomicInteger(0);
	/**
	 * Keeps track of total size of records held in this FileOffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the FileOffsetIndex index. The removals and dead data are not reflected by this property.
	 */
	private final AtomicLong totalSize = new AtomicLong(0);
	/**
	 * Contains flag signalizing that FileOffsetIndex is open and can be used. Flag is set to false on {@link #close()} operation.
	 * No additional calls are allowed after that.
	 */
	@Getter private boolean operative = true;
	/**
	 * FileOffsetIndex descriptor used when creating FileOffsetIndex instance or created on last {@link #flush(long)} operation.
	 * Contains all information necessary to read/write data in FileOffsetIndex instance using {@link Kryo}.
	 */
	private FileOffsetIndexDescriptor fileOffsetDescriptor;
	/**
	 * Main index that keeps track of record keys file locations. Used for persisted record reading.
	 */
	private Map<RecordKey, FileLocation> keyToLocations;
	/**
	 * Non flushed values contains all values that has been modified in this FileOffsetIndex instance and their locations were
	 * not yet flushed to the disk. They might have been written to the disk, but their location is still only in memory
	 * and in case of crash they wouldn't be retrievable. Flush persists all file locations to disk and performs sync.
	 */
	private NonFlushedValues nonFlushedValues = new NonFlushedValues();
	/**
	 * This field contains the information about last known position that has been synced to the file on disk and can
	 * be safely read.
	 */
	private long lastSyncedPosition;

	public FileOffsetIndex(
		@Nonnull Path targetFile,
		@Nonnull FileOffsetIndexDescriptor fileOffsetDescriptor,
		@Nonnull StorageOptions options,
		@Nonnull FileOffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this.targetFile = targetFile;
		this.options = options;
		this.fileOffsetDescriptor = fileOffsetDescriptor;
		this.recordTypeRegistry = recordTypeRegistry;

		this.readOnlyOpenedHandles = new ArrayList<>(options.maxOpenedReadHandles());
		this.readKryoPool = new FileOffsetIndexKryoPool(
			options.maxOpenedReadHandles(),
			version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
		);
		this.writeKryo = fileOffsetDescriptor.getWriteKryo();
		try {
			final FileOffsetIndexBuilder fileOffsetIndexBuilder = initializeFileOffsetIndexFromFile(
				targetFile, fileOffsetDescriptor.getFileLocation(), options
			);
			this.keyToLocations = ofNullable(fileOffsetIndexBuilder)
				.map(FileOffsetIndexBuilder::getBuiltIndex)
				.orElseGet(() -> new ConcurrentHashMap<>(KEY_HASH_MAP_INITIAL_SIZE));
			this.histogram = ofNullable(fileOffsetIndexBuilder)
				.map(FileOffsetIndexBuilder::getHistogram)
				.orElseGet(() -> new ConcurrentHashMap<>(HISTOGRAM_INITIAL_CAPACITY));
			ofNullable(fileOffsetIndexBuilder)
				.ifPresent(it -> this.totalSize.set(it.getTotalSize()));
			ofNullable(fileOffsetIndexBuilder)
				.ifPresent(it -> this.maxRecordSize.set(it.getMaxSize()));
			this.writeHandle = new WriteOnlyFileHandle(
				targetFile, this::assertOperative, observableOutputKeeper
			);
			this.lastSyncedPosition = targetFile.toFile().length();
		} catch (IOException ex) {
			throw new StorageException("Target file " + targetFile + " cannot be opened!", ex);
		}
	}

	/**
	 * Returns version of the current FileOffsetIndexDescriptor instance. This version can be used to recognize, whether
	 * there was any real change made before and after {@link #flush(long)} or {@link #close()} operations.
	 */
	public long getVersion() {
		return fileOffsetDescriptor.getVersion();
	}

	/**
	 * Returns readable instance of key compressor.
	 */
	public KeyCompressor getReadOnlyKeyCompressor() {
		return fileOffsetDescriptor.getReadOnlyKeyCompressor();
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE entries in the FileOffsetIndex.
	 */
	public Collection<Entry<RecordKey, FileLocation>> getEntries() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.entrySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE keys in the FileOffsetIndex.
	 */
	public Collection<RecordKey> getKeys() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.keySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE file locations in the FileOffsetIndex.
	 */
	public Collection<FileLocation> getFileLocations() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.values());
	}

	/**
	 * Returns current count of ACTIVE entries in FileOffsetIndex.
	 */
	public int count() {
		assertOperative();
		return keyToLocations.size() + nonFlushedValues.count();
	}

	/**
	 * Returns current count of ACTIVE entries of certain type in FileOffsetIndex.
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
				.map(StorageRecord::getPayload)
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
	 * Returns value assigned to the particular location in FileOffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> T get(FileLocation location, @Nonnull Class<T> recordType) {
		return doGet(recordType, -1, location).getPayload();
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
				.map(StorageRecord::getPayload)
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
	 * Returns value assigned to the particular location in FileOffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> byte[] getBinary(FileLocation location, @Nonnull Class<T> recordType) {
		return doGetBinary(recordType, -1, location).getPayload();
	}

	/**
	 * Returns true if {@link FileOffsetIndex} contains record with this id and type.
	 */
	public <T extends StoragePart> boolean contains(long primaryKey, Class<T> recordType) {
		assertOperative();
		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		return ofNullable(keyToLocations.get(key)).isPresent();
	}

	/**
	 * Stores or overwrites record with passed primary key in FileOffsetIndex. Values of different types are distinguished by
	 * the FileOffsetIndex so that two different types of objects with same primary keys don't overwrite each other.
	 *
	 * @param transactionId will be propagated to {@link StorageRecord#getTransactionId()}
	 * @param value         value to be stored
	 */
	public <T extends StoragePart> long put(long transactionId, @Nonnull T value) {
		assertOperative();
		return writeHandle.execute(
			"Storing record",
			exclusiveWriteAccess -> {
				final long partId = ofNullable(value.getUniquePartId())
					.orElseGet(() -> value.computeUniquePartIdAndSet(fileOffsetDescriptor.getWriteKeyCompressor()));
				doPut(
					transactionId,
					partId, value,
					exclusiveWriteAccess
				);
				return partId;
			}
		);
	}

	/**
	 * Removes existing record with passed primary key in FileOffsetIndex. True is returned if particular record is found and
	 * removed.
	 *
	 * TOBEDONE JNO - shouldn't we also track transactionId as in put method?!
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
			writeHandle.execute(
				"Removing record",
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
	 * This method will check whether the related FileOffsetIndex file is consistent with internal rules - it checks:
	 *
	 * - whether there is non interrupted monotonic row of transactionIds
	 * - whether the final record has control bit that closes the transaction
	 * - whether all the records has CRC-32C checksum valid (when CRC32-C checksums are enabled)
	 */
	public FileOffsetIndexStatistics verifyContents() {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> this.readKryoPool.borrowAndExecute(
					kryo -> {
						final FileOffsetIndexStatistics result = new FileOffsetIndexStatistics(this.keyToLocations.size(), this.totalSize.get());
						@SuppressWarnings("resource") final ObservableInput<RandomAccessFileInputStream> stream = exclusiveReadAccess.readOnlyStream();
						final RandomAccessFileInputStream is = stream.getInputStream();
						is.seek(0);
						final CRC32C crc32C = options.computeCRC32C() ? new CRC32C() : null;
						byte[] buffer = new byte[stream.getBuffer().length];
						int recCount = 0;
						long startPosition = 0;
						long prevTransactionId = 0;
						boolean firstTransaction = true;
						boolean transactionCleared = true;
						final long fileLength;
						try {
							fileLength = is.getRandomAccessFile().length();
						} catch (IOException ex) {
							throw new CorruptedRecordException(
								"Cannot read file length of the file: " + stream.getInputStream().getRandomAccessFile().toString(), ex
							);
						}
						do {
							recCount++;
							final int finalRecCount = recCount;

							try {
								stream.resetToPosition(startPosition);
								// computed record length without CRC32 checksum
								int recordLength = stream.readInt();
								final byte control = stream.readByte();
								final byte nodeId = stream.readByte();
								final long transactionId = stream.readLong();
								final long finalStartPosition = startPosition;

								// verify that transactional id is monotonically increasing
								if (!firstTransaction && !(transactionCleared ? transactionId > prevTransactionId : transactionId >= prevTransactionId)) {
									throw new CorruptedRecordException(
										"Transaction id record monotonic row is violated in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + transactionId,
										prevTransactionId + 1, transactionId
									);
								}
								// verify that transaction id stays the same within transaction block
								if (!transactionCleared && transactionId != prevTransactionId) {
									throw new CorruptedRecordException(
										"Transaction id was not cleared with control bit record id in record no. " + finalRecCount + " file position: [" + finalStartPosition + ", length " + recordLength + "B], previous transaction id is " + prevTransactionId + ", current is " + transactionId,
										prevTransactionId, transactionId
									);
								}

								ofNullable(crc32C).ifPresent(CRC32C::reset);
								// first 4 bytes of length are not part of the CRC check
								int processedRecordLength = StorageRecord.CRC_NOT_COVERED_HEAD;
								is.seek(startPosition + processedRecordLength);
								// we have to avoid reading last 8 bytes of CRC check value
								while (processedRecordLength < recordLength - ObservableOutput.TAIL_MANDATORY_SPACE) {
									final int read = is.read(buffer, 0, Math.min(recordLength - processedRecordLength - ObservableOutput.TAIL_MANDATORY_SPACE, buffer.length));
									ofNullable(crc32C).ifPresent(it -> it.update(buffer, 0, read));
									processedRecordLength += read;
								}
								// verify CRC32-C checksum
								if (crc32C != null) {
									final long computedChecksum = crc32C.getValue();
									stream.resetToPosition(startPosition + recordLength - ObservableOutput.TAIL_MANDATORY_SPACE);
									final long storedChecksum = stream.readLong();
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
								prevTransactionId = transactionId;
								transactionCleared = StorageRecord.isBitSet(control, StorageRecord.TRANSACTION_CLOSING_BIT);
								if (transactionCleared && transactionId > 0L) {
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
					})
			)
		);
	}

	/**
	 * Flushes current state of the FileOffsetIndex to the disk. File contents are in sync when this method finalizes.
	 *
	 * @param transactionId will be propagated to {@link StorageRecord#getTransactionId()}
	 */
	@Nonnull
	public FileOffsetIndexDescriptor flush(long transactionId) {
		assertOperative();
		return writeHandle.execute(
			"Writing mem table to disk",
			it -> {
				this.fileOffsetDescriptor = doFlush(transactionId, fileOffsetDescriptor, it);
				return fileOffsetDescriptor;
			}
		);
	}

	/**
	 * Closes the FileOffsetIndex and writes all data to disk. File contents are in sync when this method finalizes.
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
				while (!readOnlyOpenedHandles.isEmpty() && System.currentTimeMillis() - start > options.waitOnCloseSeconds() * 1000) {
					if (readOnlyHandlePool.getFree() > 0) {
						final ReadOnlyFileHandle handleToClose = readOnlyHandlePool.obtain();
						try {
							handleToClose.executeIgnoringOperationalCheck(
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
				final Iterator<ReadOnlyFileHandle> readHandleIt = readOnlyOpenedHandles.iterator();
				while (readHandleIt.hasNext()) {
					final ReadOnlyFileHandle readOnlyFileHandle = readHandleIt.next();
					readOnlyFileHandle.forceClose();
					readHandleIt.remove();
				}
				// at last flush FileOffsetIndex and close write handle
				return writeHandle.executeIgnoringOperationalCheck(
					"Releasing file " + targetFile + " handle",
					exclusiveWriteAccess -> {
						this.fileOffsetDescriptor = doFlush(0L, fileOffsetDescriptor, exclusiveWriteAccess);
						exclusiveWriteAccess.close();
						return fileOffsetDescriptor.getFileLocation();
					}
				);
			} else {
				throw new EvitaInternalError("FileOffsetIndex is already being closed!");
			}
		} finally {
			shutdownDownProcedureActive.compareAndExchange(true, false);
		}
	}

	/**
	 * Returns position of last fragment of the current {@link FileOffsetIndex} in the tracked file.
	 */
	public FileLocation getFileOffsetIndexLocation() {
		return fileOffsetDescriptor.getFileLocation();
	}

	/**
	 * Returns collection of all non-flushed records to store.
	 */
	public Collection<NonFlushedValue> getNonFlushedEntries() {
		return this.nonFlushedValues.nonFlushedValueIndex.values();
	}

	/**
	 * Method allows to execute custom "(de)serialization" function in the context of current FileOffsetIndex Kryo read
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
	 * Just for testing purposes - verifies whether the FileOffsetIndex contents equals the other FileOffsetIndex contents.
	 */
	boolean fileOffsetIndexEquals(@Nonnull FileOffsetIndex o) {
		if (this == o) return true;
		return keyToLocations.equals(o.keyToLocations);
	}

	private FileOffsetIndexBuilder initializeFileOffsetIndexFromFile(@Nonnull Path targetFile, @Nullable FileLocation fileOffsetIndexLocation, @Nonnull StorageOptions options) throws IOException {
		final File targetFileRef = targetFile.toFile();
		if (fileOffsetIndexLocation == null || !targetFileRef.exists()) {
			final File directory = targetFileRef.getParentFile();
			// ensure directory exits
			if (!directory.exists()) {
				//noinspection ResultOfMethodCallIgnored
				directory.mkdirs();
			}
			isTrue(
				directory.isDirectory(),
				() -> new InvalidStoragePathException("Storage path doesn't represent a directory: " + directory)
			);
		} else {
			// read file from disk and initialize FileOffsetIndex from the file
			// this should initialize keyToLocations index unless error occurs
			return readFileOffsetIndex(fileOffsetIndexLocation);
		}

		// create empty file if no file exists
		if (!targetFileRef.exists()) {
			isTrue(
				targetFileRef.createNewFile(),
				"File " + targetFile + " doesn't exist and cannot be created!"
			);
		}

		// return null - nothing usable was read
		return null;
	}

	/**
	 * Checks whether the FileOffsetIndex is still opened and operative.
	 */
	private void assertOperative() {
		isPremiseValid(
			operative || Boolean.TRUE.equals(shutdownDownProcedureActive.get()),
			"FileOffsetIndex has been already closed!"
		);
	}

	/**
	 * Reads FileOffsetIndex from the disk using write handle.
	 */
	private FileOffsetIndexBuilder readFileOffsetIndex(@Nonnull FileLocation location) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					final FileOffsetIndexBuilder builder = new FileOffsetIndexBuilder();
					return readKryoPool.borrowAndExecute(kryo -> {
						FileOffsetIndexSerializationService.INSTANCE.deserialize(
							exclusiveReadAccess.readOnlyStream(),
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
	 * Flushes current FileOffsetIndex data (and it's changes) to the disk. File is synced within this method. Frequent flushes
	 * limit the I/O performance.
	 */
	private FileOffsetIndexDescriptor doFlush(long transactionId, @Nonnull FileOffsetIndexDescriptor fileOffsetIndexDescriptor, @Nonnull ExclusiveWriteAccess writeAccess) {
		if (!this.nonFlushedValues.isEmpty()) {
			final ObservableOutput<FileOutputStream> writeOnlyStream = writeAccess.getWriteOnlyStream();
			final FileLocation fileLocation = FileOffsetIndexSerializationService.INSTANCE.serialize(
				this,
				writeOnlyStream,
				transactionId
			);

			doSync(writeAccess);

			// now empty all NonFlushedValues and move them to current state
			promoteNonFlushedValuesToSharedState();

			final FileOffsetIndexDescriptor newFileOffsetIndexDescriptor = new FileOffsetIndexDescriptor(
				fileLocation,
				fileOffsetIndexDescriptor
			);

			if (fileOffsetIndexDescriptor.resetDirty()) {
				this.readKryoPool.expireAllPreviouslyCreated();
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
			writeHandle.execute(
				"Syncing changes to disk.",
				it -> {
					doSync(it);
					return null;
				}
			);
			// propagate changes in KeyCompressor to the read kryo pool
			if (fileOffsetDescriptor.resetDirty()) {
				this.fileOffsetDescriptor = new FileOffsetIndexDescriptor(
					fileOffsetDescriptor.getFileLocation(),
					fileOffsetDescriptor
				);
				this.readKryoPool.expireAllPreviouslyCreated();
			}
		}
	}

	/**
	 * Flushes output buffers to the disk and calls fsync so that we can be sure all data are safely persisted on disk.
	 */
	private void doSync(@Nonnull ExclusiveWriteAccess writeAccess) {
		// execute fsync so that data are really stored to the disk
		final ObservableOutput<FileOutputStream> os = writeAccess.getWriteOnlyStream();
		try {
			os.flush();
			os.getOutputStream().getFD().sync();
			this.lastSyncedPosition = writeAccess.getTargetFile().length();
		} catch (IOException e) {
			throw new SyncFailedException(e);
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
	 * Method removes existing record from the FileOffsetIndex. This method should be called only from {@link QueueWriter} and never
	 * directly from the code. All writes are serialized by exclusive write access.
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
	 * Method stores new record to the FileOffsetIndex. This method should be called only from {@link QueueWriter} and never
	 * directly from the code. All writes are serialized by exclusive write access.
	 */
	private void doPut(long transactionId, long primaryKey, @Nonnull StoragePart value, @Nonnull ExclusiveWriteAccess exclusiveWriteAccess) {
		final byte recordType = recordTypeRegistry.idFor(value.getClass());
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final boolean update = keyToLocations.containsKey(key);
		final FileLocation recordLocation = new StorageRecord<>(
			writeKryo,
			exclusiveWriteAccess.getWriteOnlyStream(),
			SINGLE_NODE_ID,
			transactionId,
			false,
			value
		).getFileLocation();

		// mark dirty read
		this.nonFlushedValues.put(
			key,
			new NonFlushedValue(primaryKey, recordType, recordLocation),
			!update
		);
	}

	/**
	 * Method retrieves existing record from the FileOffsetIndex.
	 */
	private <T extends Serializable> StorageRecord<T> doGet(@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> this.readKryoPool.borrowAndExecute(
					kryo -> {
						try {
							return new StorageRecord<>(
								exclusiveReadAccess.readOnlyStream(),
								it,
								(stream, length) -> kryo.readObject(stream, recordType)
							);
						} catch (CorruptedRecordException ex) {
							throw new CorruptedKeyValueRecordException(
								"Record " + primaryKey + " of type " + recordType.getName() + " is corrupted after reading!",
								recordType, primaryKey, ex
							);
						}
					})
			)
		);
	}

	/**
	 * Method retrieves existing record from the FileOffsetIndex without parsing its contents.
	 */
	private <T extends Serializable> StorageRecord<byte[]> doGetBinary(@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> this.readKryoPool.borrowAndExecute(
					kryo -> {
						try {
							return new StorageRecord<>(
								exclusiveReadAccess.readOnlyStream(),
								it,
								(stream, length) -> stream.readBytes(length - StorageRecord.OVERHEAD_SIZE)
							);
						} catch (CorruptedRecordException ex) {
							throw new CorruptedKeyValueRecordException(
								"Record " + primaryKey + " of type " + recordType.getName() + " is corrupted after reading!",
								recordType, primaryKey, ex
							);
						}
					})
			)
		);
	}

	/**
	 * Contains statistics about the FileOffsetIndex file.
	 */
	@RequiredArgsConstructor
	@ToString
	public static class FileOffsetIndexStatistics {
		@Getter private final long livingRecordCount;
		@Getter private final long livingRecordSize;
		@Getter private int recordCount;
		@Getter private long totalSize;
		@Getter private int maxRecordSize;

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
		public NonFlushedValue get(RecordKey key) {
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
		public Iterable<? extends Entry<RecordKey, NonFlushedValue>> entrySet() {
			return nonFlushedValueIndex.entrySet();
		}

		/**
		 * Returns count of all non-flushed records affecting {@link FileOffsetIndex#histogram} - may be positive or negative.
		 * Represents the diff against {@link FileOffsetIndex#histogram}.
		 */
		public int count() {
			return nonFlushedValuesHistogram.values().stream().mapToInt(it -> it).sum();
		}

		/**
		 * Returns count of all non-flushed records of specific type affecting {@link FileOffsetIndex#histogram} - may be
		 * positive or negative. Represents the diff against {@link FileOffsetIndex#histogram}.
		 */
		public int count(byte recordTypeId) {
			return ofNullable(nonFlushedValuesHistogram.get(recordTypeId)).orElse(0);
		}

	}

	/**
	 * This class is used to build initial FileOffsetIndex in {@link FileOffsetIndexSerializationService} and switch atomically
	 * the real (operative) FileOffsetIndex contents atomically once it's done.
	 */
	public static class FileOffsetIndexBuilder {
		@Getter private final ConcurrentHashMap<RecordKey, FileLocation> builtIndex = new ConcurrentHashMap<>(KEY_HASH_MAP_INITIAL_SIZE);
		@Getter private final ConcurrentHashMap<Byte, Integer> histogram = new ConcurrentHashMap<>(HISTOGRAM_INITIAL_CAPACITY);
		@Getter private long totalSize;
		@Getter private int maxSize;

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
	 * This class is used to monitor and limit {@link ReadOnlyFileHandle} pool. It creates new handles on demand in
	 * locked fashion and verifies that maximum opened handles limit is not exceeded.
	 */
	private class FileOffsetIndexObservableInputPool extends Pool<ReadOnlyFileHandle> {
		private final ReentrantLock readFilesLock = new ReentrantLock();

		private FileOffsetIndexObservableInputPool() {
			super(true, false);
		}

		public <T> T borrowAndExecute(Function<ReadOnlyFileHandle, T> logic) {
			final ReadOnlyFileHandle readOnlyFileHandle = this.obtain();
			try {
				return logic.apply(readOnlyFileHandle);
			} finally {
				this.free(readOnlyFileHandle);
			}
		}

		@Override
		protected ReadOnlyFileHandle create() {
			try {
				if (readFilesLock.tryLock(options.lockTimeoutSeconds(), TimeUnit.SECONDS)) {
					try {
						if (readOnlyOpenedHandles.size() >= options.maxOpenedReadHandles()) {
							throw new PoolExhaustedException(options.maxOpenedReadHandles(), targetFile);
						}
						final ReadOnlyFileHandle readOnlyFileHandle = new ReadOnlyFileHandle(
							targetFile, options.computeCRC32C(), FileOffsetIndex.this::assertOperative
						);
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
