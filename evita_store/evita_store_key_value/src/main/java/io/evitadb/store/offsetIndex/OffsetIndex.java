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
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.VersionedValue;
import io.evitadb.store.offsetIndex.stream.RandomAccessFileInputStream;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.MEM_TABLE_RECORD_SIZE;
import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.deserialize;
import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.serialize;
import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.verify;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
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
	private final OffsetIndexObservableInputPool readOnlyHandlePool = new OffsetIndexObservableInputPool();
	/**
	 * List of all currently opened handles.
	 */
	private final CopyOnWriteArrayList<ReadOnlyHandle> readOnlyOpenedHandles;
	/**
	 * Contains flag that signalizes that shutdown procedure is active.
	 */
	private final AtomicBoolean shutdownDownProcedureActive = new AtomicBoolean(false);
	/**
	 * Keeps track of maximum record size ever written to this OffsetIndex. The number doesn't respect record removals and
	 * should be used only for informational purposes.
	 */
	private final AtomicLong maxRecordSize = new AtomicLong(0);
	/**
	 * Keeps track of total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 */
	private final AtomicLong totalSize = new AtomicLong(0);
	/**
	 * Volatile values contains history of previous writes and removals so that offset index can provide access to
	 * the correct contents based on the catalog version. Volatile values keep track only of the changes that have
	 * chance to be read by the client and try to be purged immediately when there is no chance to read them anymore.
	 * Otherwise their size would grow too large.
	 */
	private final VolatileValues volatileValues = new VolatileValues();
	/**
	 * Map contains counts for each type of record stored in OffsetIndex.
	 */
	private final ConcurrentHashMap<Byte, Integer> histogram;
	/**
	 * Contains flag signalizing that OffsetIndex is open and can be used. Flag is set to false on {@link #close()} operation.
	 * No additional calls are allowed after that.
	 */
	@Getter private boolean operative = true;
	/**
	 * Contains the catalog version that conforms to the values in {@link #keyToLocations} state.
	 */
	private long keyCatalogVersion;
	/**
	 * OffsetIndex descriptor used when creating OffsetIndex instance or created on last {@link #flush(long)} operation.
	 * Contains all information necessary to read/write data in OffsetIndex instance using {@link Kryo}.
	 */
	private OffsetIndexDescriptor fileOffsetDescriptor;
	/**
	 * Main index that keeps track of record keys file locations. Used for persisted record reading.
	 * This map is completely replaced on each flush, so it could be "non-concurrent" map.
	 */
	private Map<RecordKey, FileLocation> keyToLocations;
	/**
	 * This field contains the information about last known position that has been synced to the file on disk and can
	 * be safely read.
	 */
	private long lastSyncedPosition;

	public OffsetIndex(
		long catalogVersion,
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
			fileOffsetIndexBuilder = of(
				readFileOffsetIndex(fileOffsetDescriptor.fileLocation())
			);
		}
		this.keyCatalogVersion = catalogVersion;
		this.keyToLocations = fileOffsetIndexBuilder
			.map(FileOffsetIndexBuilder::getBuiltIndex)
			.orElseGet(() -> CollectionUtils.createConcurrentHashMap(KEY_HASH_MAP_INITIAL_SIZE));
		this.histogram = fileOffsetIndexBuilder
			.map(FileOffsetIndexBuilder::getHistogram)
			.orElseGet(() -> CollectionUtils.createConcurrentHashMap(HISTOGRAM_INITIAL_CAPACITY));
		fileOffsetIndexBuilder
			.ifPresent(it -> {
				this.totalSize.set(it.getTotalSize());
				this.maxRecordSize.set(it.getMaxSize());
			});
	}

	public OffsetIndex(
		long catalogVersion,
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
			deserialize(
				input,
				fileLocation,
				fileOffsetIndexBuilder
			);
			this.keyToLocations = fileOffsetIndexBuilder.getBuiltIndex();
			this.histogram = fileOffsetIndexBuilder.getHistogram();
			this.totalSize.set(fileOffsetIndexBuilder.getTotalSize());
			this.maxRecordSize.set(fileOffsetIndexBuilder.getMaxSize());
			this.fileOffsetDescriptor = offsetIndexDescriptorFactory.apply(fileOffsetIndexBuilder, input);
			this.keyCatalogVersion = catalogVersion;
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
	 *
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
	 * Returns current count of ACTIVE entries in OffsetIndex.
	 */
	public int count(long catalogVersion) {
		assertOperative();
		return keyToLocations.size() + volatileValues.countDifference(catalogVersion);
	}

	/**
	 * Returns current count of ACTIVE entries of certain type in OffsetIndex.
	 */
	public int count(long catalogVersion, @Nonnull Class<? extends StoragePart> recordType) {
		assertOperative();
		final byte recordTypeId = recordTypeRegistry.idFor(recordType);
		return ofNullable(histogram.get(recordTypeId)).orElse(0) + volatileValues.countDifference(catalogVersion, recordTypeId);
	}

	/**
	 * Returns value assigned to the primary key.
	 *
	 * Beware method may not return previously written record via {@link #put(long, StoragePart)} until method
	 * {@link #flush(long)} is called. In this situation {@link RecordNotYetWrittenException} is thrown.
	 */
	@Nullable
	public <T extends StoragePart> T get(long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();
		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.isRemoval()) {
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

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VersionedValue> rewrittenValueRef = this.volatileValues.getPreviousValue(catalogVersion, key);
			if (rewrittenValueRef.isPresent()) {
				final VersionedValue rewrittenValue = rewrittenValueRef.get();
				if (rewrittenValue.isRemoval()) {
					return null;
				} else {
					//noinspection unchecked
					return (T) get(rewrittenValue.fileLocation(), recordTypeRegistry.typeFor(rewrittenValue.recordType()));
				}
			}
		}

		return ofNullable(keyToLocations.get(key))
			.map(it -> doGet(recordType, primaryKey, it))
			.filter(it -> it.transactionId() <= catalogVersion)
			.map(StorageRecord::payload)
			.orElse(null);
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
	public <T extends StoragePart> byte[] getBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();

		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.isRemoval()) {
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

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VersionedValue> rewrittenValueRef = this.volatileValues.getPreviousValue(catalogVersion, key);
			if (rewrittenValueRef.isPresent()) {
				final VersionedValue rewrittenValue = rewrittenValueRef.get();
				if (rewrittenValue.isRemoval()) {
					return null;
				} else {
					return getBinary(rewrittenValue.fileLocation(), recordTypeRegistry.typeFor(rewrittenValue.recordType()));
				}
			}
		}

		return ofNullable(keyToLocations.get(key))
			.map(it -> doGetBinary(recordType, primaryKey, it))
			.filter(it -> it.transactionId() <= catalogVersion)
			.map(StorageRecord::payload)
			.orElse(null);
	}

	/**
	 * Returns value assigned to the particular location in OffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> byte[] getBinary(@Nonnull FileLocation location, @Nonnull Class<T> recordType) {
		return doGetBinary(recordType, -1, location).payload();
	}

	/**
	 * Returns true if {@link OffsetIndex} contains record with this id and type.
	 *
	 * @param catalogVersion The catalog version.
	 * @param primaryKey     The primary key of the record.
	 * @param recordType     The class object representing the record type.
	 * @param <T>            The type of the record.
	 * @return {@code true} if the record exists in the storage, {@code false} otherwise.
	 */
	public <T extends StoragePart> boolean contains(long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) {
		assertOperative();
		final RecordKey key = new RecordKey(
			recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			return !nonFlushedValue.isRemoval();
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VersionedValue> rewrittenValueRef = this.volatileValues.getPreviousValue(catalogVersion, key);
			if (rewrittenValueRef.isPresent()) {
				final VersionedValue rewrittenValue = rewrittenValueRef.get();
				return !rewrittenValue.isRemoval();
			}
		}

		return keyToLocations.containsKey(key);
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
	 * @return true if the record was found and removed
	 */
	public <T extends StoragePart> boolean remove(long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) {
		return writeHandle.checkAndExecute(
			"Removing record",
			this::assertOperative,
			exclusiveWriteAccess -> doRemove(
				catalogVersion,
				primaryKey,
				recordType
			)
		);
	}

	/**
	 * This method will check whether the related OffsetIndex file is consistent with internal rules - it checks:
	 *
	 * - whether there is non interrupted monotonic row of transactionIds
	 * - whether the final record has control bit that closes the transaction
	 * - whether all the records has CRC-32C checksum valid (when CRC32-C checksums are enabled)
	 */
	@Nonnull
	public FileOffsetIndexStatistics verifyContents() {
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				inputStream -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> verify(this, inputStream, readOnlyFileHandle.getLastWrittenPosition())
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
		this.keyCatalogVersion = catalogVersion;
		this.fileOffsetDescriptor = doFlush(catalogVersion, fileOffsetDescriptor, false);
		return fileOffsetDescriptor;
	}

	/**
	 * Purges the catalog for the given catalog version. This method should be called when there is no client using
	 * a particular version of the catalog.
	 *
	 * @param catalogVersion the version of the catalog to be purged
	 * @throws IllegalStateException if the catalog is not in an operative state
	 */
	public void purge(long catalogVersion) {
		assertOperative();
		this.volatileValues.purge(catalogVersion);
	}

	/**
	 * Copies entire living data set to the target file. The file must exist and must be prepared for re-writing.
	 * File must not be used by any other process.
	 *
	 * @param newFilePath    target file
	 * @param catalogVersion will be propagated to {@link StorageRecord#transactionId()}
	 * @return length of the copied data
	 */
	@Nonnull
	public OffsetIndexDescriptor copySnapshotTo(@Nonnull Path newFilePath, long catalogVersion) {
		// flush all non-flushed values to the disk
		this.doSoftFlush();
		// copy the active parts to a new file
		return readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				// by requesting write-handle we enforce no other thread can write to the source file while we are copying
				inputStream -> writeHandle.checkAndExecute(
					"Writing mem table",
					this::assertOperative,
					output -> this.readKryoPool.borrowAndExecute(
						kryo -> {
							Assert.isTrue(inputStream.getInputStream() instanceof RandomAccessFileInputStream, "Input stream must be RandomAccessFileInputStream!");
							@SuppressWarnings("unchecked") final ObservableInput<RandomAccessFileInputStream> randomAccessFileInputStream =
								(ObservableInput<RandomAccessFileInputStream>) inputStream;
							final FileLocation fileLocation = OffsetIndexSerializationService.copySnapshotTo(
								this,
								randomAccessFileInputStream,
								catalogVersion,
								newFilePath
							);
							return new OffsetIndexDescriptor(
								this.fileOffsetDescriptor.version() + 1,
								fileLocation,
								this.getCompressedKeys(),
								this.fileOffsetDescriptor.getKryoFactory(),
								getActiveRecordShare()
							);
						}
					)
				)
			)
		);
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
				this.fileOffsetDescriptor = doFlush(
					// if there are any non-flushed values, use their version as the last version
					this.volatileValues.getLastNonFlushedCatalogVersionIfExists()
						.orElse(this.keyCatalogVersion),
					this.fileOffsetDescriptor,
					true
				);
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
	@Nullable
	public FileLocation getFileOffsetIndexLocation() {
		return fileOffsetDescriptor.fileLocation();
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

	/**
	 * Returns the total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 *
	 * @return the total size
	 */
	public long getTotalSize() {
		return this.totalSize.get() + (long) this.keyToLocations.size() * (long) MEM_TABLE_RECORD_SIZE;
	}

	/**
	 * Forgets all non-flushed values. This method is used when it's known those data will never be promoted to
	 * the shared state.
	 */
	public void forgetVolatileData() {
		this.volatileValues.forgetNonFlushedValues();
	}

	/**
	 * Creates new file that contains only records directly reachable from {@link #keyToLocations} index. While
	 * compacting, the original offset index is locked for writing (but reading is still possible).
	 *
	 * Original file remains unchanged and must be removed later manually when the history is no longer needed.
	 *
	 * @param newFilePath new file location
	 * @return new file location
	 */
	@Nonnull
	public OffsetIndexDescriptor compact(@Nonnull Path newFilePath) {
		return copySnapshotTo(newFilePath, this.keyCatalogVersion);
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE keys in the OffsetIndex.
	 */
	Collection<RecordKey> getKeys() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.keySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE file locations in the OffsetIndex.
	 */
	Collection<FileLocation> getFileLocations() {
		assertOperative();
		return Collections.unmodifiableCollection(keyToLocations.values());
	}

	/**
	 * Just for testing purposes - verifies whether the OffsetIndex contents equals the other OffsetIndex contents.
	 */
	boolean fileOffsetIndexEquals(@Nonnull OffsetIndex o) {
		if (this == o) return true;
		return keyToLocations.equals(o.keyToLocations);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Calculates the living object share.
	 * The living object share is calculated as the ratio of the total size of the object and the size of the file
	 * that is being written to.
	 *
	 * @return the living object share as a double value
	 */
	private double getActiveRecordShare() {
		return (double) this.totalSize.get() / (double) writeHandle.getLastWrittenPosition();
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
						deserialize(
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
		if (this.volatileValues.hasValuesToFlush()) {
			final OffsetIndexDescriptor newFileOffsetIndexDescriptor = writeHandle.checkAndExecuteAndSync(
				"Writing mem table",
				this::assertOperative,
				outputStream -> {
					// serialize all non-flushed values to the output stream
					final Collection<NonFlushedValueSet> nonFlushedEntries = this.volatileValues.getNonFlushedEntriesToPromote(catalogVersion);
					final List<VersionedValue> valuesToPromote = nonFlushedEntries
						.stream()
						.flatMap(it -> it.getAllValues().stream())
						.toList();
					return new NonFlushedValuesWithFileLocation(
						nonFlushedEntries,
						valuesToPromote.size(),
						serialize(
							outputStream,
							catalogVersion,
							valuesToPromote,
							this.getFileOffsetIndexLocation(),
							this.getStorageOptions()
						)
					);
				},
				(outputStream, nonFlushedValuesWithFileLocation) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
					// now empty all NonFlushedValueSet and move them to current state
					promoteNonFlushedValuesToSharedState(
						nonFlushedValuesWithFileLocation.valueCount(),
						nonFlushedValuesWithFileLocation.nonFlushedValueSets()
					);
					// propagate changes in KeyCompressor to the read kryo pool
					if (fileOffsetIndexDescriptor.resetDirty()) {
						this.readKryoPool.expireAllPreviouslyCreated();
					}
					// create new OffsetIndexDescriptor with updated version
					return new OffsetIndexDescriptor(
						nonFlushedValuesWithFileLocation.fileLocation(),
						fileOffsetIndexDescriptor,
						getActiveRecordShare()
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
		if (this.volatileValues.hasValuesToFlush()) {
			writeHandle.checkAndExecuteAndSync(
				"Syncing changes to disk.",
				this::assertOperative,
				it -> null,
				(output, result) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
					// propagate changes in KeyCompressor to the read kryo pool
					if (fileOffsetDescriptor.resetDirty()) {
						this.fileOffsetDescriptor = new OffsetIndexDescriptor(
							fileOffsetDescriptor.fileLocation(),
							fileOffsetDescriptor,
							getActiveRecordShare()
						);
						this.readKryoPool.expireAllPreviouslyCreated();
					}
					return null;
				}
			);

		}
	}

	/**
	 * Method moves all `nonFlushedValues` to a `keyToLocations` entries and purges them from the main memory.
	 */
	private void promoteNonFlushedValuesToSharedState(int valueCount, @Nonnull Collection<NonFlushedValueSet> nonFlushedValueSets) {
		this.volatileValues.recordHistoricalVersions(nonFlushedValueSets, this.keyToLocations);
		// promote changes to shared state
		final Map<RecordKey, FileLocation> newKeyToLocations = createHashMap(
			this.keyToLocations.size() + valueCount
		);
		// we need to start with the original set - this is expensive operation - it would be much better to
		// have something like persistent map that would allow us to create new instance with reusing the old segments
		newKeyToLocations.putAll(this.keyToLocations);

		long workingMaxRecordSize = this.maxRecordSize.get();
		long recordLength = this.totalSize.get();

		final Map<Byte, Integer> histogramDiff = CollectionUtils.createHashMap(this.histogram.size());
		for (NonFlushedValueSet volatileValues : nonFlushedValueSets) {
			for (Entry<RecordKey, VersionedValue> entry : volatileValues.entrySet()) {
				final RecordKey recordKey = entry.getKey();
				final VersionedValue nonFlushedValue = entry.getValue();

				final int count;
				if (nonFlushedValue.isRemoval()) {
					final FileLocation removedLocation = newKeyToLocations.remove(recordKey);
					// location might not exist when value was created and immediately removed
					if (removedLocation != null) {
						count = -1;
						recordLength -= removedLocation.recordLength();
					} else {
						count = 0;
						recordLength = 0;
					}
				} else if (volatileValues.wasAdded(recordKey)) {
					final FileLocation recordLocation = nonFlushedValue.fileLocation();
					final int currentRecordLength = recordLocation.recordLength();
					recordLength += currentRecordLength;
					if (currentRecordLength > workingMaxRecordSize) {
						workingMaxRecordSize = currentRecordLength;
					}
					newKeyToLocations.put(recordKey, recordLocation);
					count = 1;
				} else {
					final FileLocation existingRecordLocation = newKeyToLocations.get(recordKey);
					final FileLocation newRecordLocation = nonFlushedValue.fileLocation();
					recordLength += newRecordLocation.recordLength() - existingRecordLocation.recordLength();
					if (newRecordLocation.recordLength() > workingMaxRecordSize) {
						workingMaxRecordSize = newRecordLocation.recordLength();
					}
					newKeyToLocations.put(recordKey, newRecordLocation);
					count = 0;
				}

				histogramDiff.merge(
					recordKey.recordType(), count, Integer::sum
				);
			}

			// update statistics
			this.totalSize.addAndGet(recordLength);
			this.maxRecordSize.set(workingMaxRecordSize);
			for (Entry<Byte, Integer> entry : histogramDiff.entrySet()) {
				this.histogram.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
			// and the locations finally
			this.keyToLocations = newKeyToLocations;
		}
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
		this.volatileValues.putValue(
			catalogVersion, key,
			new VersionedValue(primaryKey, recordType, recordLocation),
			!update
		);
	}

	/**
	 * Method removes existing record from the OffsetIndex. This method should be called only from singleton writer and
	 * never directly from the code. All writes are serialized by exclusive write access.
	 */
	private boolean doRemove(long catalogVersion, long primaryKey, @Nonnull Class<? extends StoragePart> valueType) {
		final byte recordType = recordTypeRegistry.idFor(valueType);
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.isRemoval()) {
				return false;
			} else {
				this.volatileValues.removeValue(catalogVersion, key, nonFlushedValue.fileLocation());
				return true;
			}
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VersionedValue> rewrittenValueRef = this.volatileValues.getPreviousValue(catalogVersion, key);
			if (rewrittenValueRef.isPresent()) {
				final VersionedValue rewrittenValue = rewrittenValueRef.get();
				if (rewrittenValue.isRemoval()) {
					return false;
				}
			}
		}

		final FileLocation currentLocation = keyToLocations.get(key);
		if (currentLocation == null) {
			return false;
		} else {
			this.volatileValues.removeValue(catalogVersion, key, currentLocation);
			return true;
		}
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

		public double getActiveRecordShare() {
			return (double) livingRecordSize / (double) totalSize;
		}

		/**
		 * Registers a record with the specified length in the statistics of the OffsetIndex file.
		 *
		 * @param length The length of the record to be registered.
		 */
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
	private static class NonFlushedValueSet {
		/**
		 * Catalog version associated with this instance.
		 */
		@Getter private final long catalogVersion;
		/**
		 * Map of non-flushed values. We can use "non-concurrent" map because this instance is secured by the write
		 * handle for concurrent access.
		 */
		private final Map<RecordKey, VersionedValue> nonFlushedValueIndex = CollectionUtils.createHashMap(64);
		/**
		 * Map of non-flushed values. We can use "non-concurrent" map because this instance is secured by the write
		 * handle for concurrent access.
		 */
		private final Map<Byte, Integer> nonFlushedValuesHistogram = CollectionUtils.createHashMap(64);
		/**
		 * Set of added records.
		 */
		@Getter private final Set<RecordKey> addedKeys = CollectionUtils.createHashSet(64);
		/**
		 * Set of removed records.
		 */
		@Getter private final Set<RecordKey> removedKeys = CollectionUtils.createHashSet(64);

		public NonFlushedValueSet(long catalogVersion) {
			this.catalogVersion = catalogVersion;
		}

		/**
		 * Returns instance of the record by its key if present in non-flushed index.
		 */
		@Nullable
		public VersionedValue get(@Nonnull RecordKey key) {
			return nonFlushedValueIndex.get(key);
		}

		/**
		 * Checks if a record with the specified key was added to the non-flushed index.
		 *
		 * @param key The key of the record to check.
		 * @return {@code true} if the record was added, {@code false} otherwise.
		 */
		public boolean wasAdded(@Nonnull RecordKey key) {
			return addedKeys.contains(key);
		}

		/**
		 * Returns a collection of all VersionedValue objects stored in the nonFlushedValueIndex.
		 *
		 * @return a collection of all VersionedValue objects
		 */
		@Nonnull
		public Collection<VersionedValue> getAllValues() {
			return nonFlushedValueIndex.values();
		}

		/**
		 * Stores instance of the record to the non-flushed index.
		 *
		 * @param create - when true it affects {@link #histogram} results
		 */
		public void put(@Nonnull RecordKey key, @Nonnull VersionedValue value, boolean create) {
			if (create) {
				nonFlushedValuesHistogram.merge(key.recordType(), 1, Integer::sum);
				addedKeys.add(key);
				removedKeys.remove(key);
			}
			nonFlushedValueIndex.put(key, value);
		}

		/**
		 * Stores information about record removal to the non-flushed index.
		 * This will prevent loading record from the persistent storage even if its present there.
		 */
		public void remove(@Nonnull RecordKey key, @Nonnull FileLocation fileLocation) {
			nonFlushedValuesHistogram.merge(key.recordType(), -1, Integer::sum);
			nonFlushedValueIndex.put(key, new VersionedValue(key.primaryKey(), (byte) (key.recordType() * -1), fileLocation));
			addedKeys.remove(key);
			removedKeys.add(key);
		}

		/**
		 * Returns iterator over all non-flushed records.
		 */
		@Nonnull
		public Iterable<? extends Entry<RecordKey, VersionedValue>> entrySet() {
			return nonFlushedValueIndex.entrySet();
		}

		/**
		 * Creates map of original values for keys that were rewritten by this non-flushed value set.
		 *
		 * @param currentLocations current locations of the records
		 * @return map of original values for keys that were rewritten by this non-flushed value set
		 */
		@Nonnull
		public PastMemory createFrom(@Nonnull Map<RecordKey, FileLocation> currentLocations) {
			final Map<RecordKey, VersionedValue> result = CollectionUtils.createHashMap(nonFlushedValueIndex.size());
			for (RecordKey replacedKey : nonFlushedValueIndex.keySet()) {
				ofNullable(currentLocations.get(replacedKey))
					.map(it -> new VersionedValue(replacedKey.primaryKey(), replacedKey.recordType(), it))
					.ifPresent(it -> result.put(replacedKey, it));
			}
			return new PastMemory(
				Collections.unmodifiableMap(result),
				addedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(addedKeys),
				removedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(removedKeys)
			);
		}

		/**
		 * Merges the existingHistory map with the currentLocations map.
		 * If a key in the existingHistory map is not present in the currentLocations map, a new VersionedValue
		 * is created using the currentLocations map and added to the existingHistory map.
		 *
		 * @param existingHistory  The existing history map.
		 * @param currentLocations The current locations map.
		 * @return The merged map.
		 */
		@Nonnull
		public PastMemory mergeWith(
			@Nonnull PastMemory existingHistory,
			@Nonnull Map<RecordKey, FileLocation> currentLocations
		) {
			final Set<RecordKey> addedKeys = new HashSet<>(existingHistory.getAddedKeys());
			addedKeys.addAll(this.addedKeys);
			final Set<RecordKey> removedKeys = new HashSet<>(existingHistory.getRemovedKeys());
			removedKeys.addAll(this.removedKeys);
			final Map<RecordKey, VersionedValue> replacedValues = new HashMap<>(existingHistory.getReplacedValues());
			for (RecordKey replacedKey : nonFlushedValueIndex.keySet()) {
				// if the existing history already contains the key, we must not overwrite it, the currentLocations
				// already contains value written in this catalog version and we would store invalid value (and lose
				// the proper historical one) - the single record may have been written multiple times in single tx
				if (!existingHistory.containsKey(replacedKey)) {
					ofNullable(currentLocations.get(replacedKey))
						.map(it -> new VersionedValue(replacedKey.primaryKey(), replacedKey.recordType(), it))
						.ifPresent(it -> replacedValues.put(replacedKey, it));
				}
			}
			return new PastMemory(
				Collections.unmodifiableMap(replacedValues),
				addedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(addedKeys),
				removedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(removedKeys)
			);
		}
	}

	/**
	 * The VolatileValues class represents a collection of non-flushed values and their versions and also list
	 * of previously written values in previous versions of the OffsetIndex in case there is still client referencing
	 * to such version. The owner of the OffsetIndex is responsible to report when there are no more clients referencing
	 * to the previous versions so that the memory can be released.
	 *
	 * Because the OffsetIndex points only to the latest version of the record by the primary key, we must keep pointers
	 * to all previous versions that were overwritten by the new version in order to be able to retrieve the correct
	 * versions to the clients referencing to older versions of the catalog (so that we keep the SNAPSHOT consistency
	 * level).
	 */
	@RequiredArgsConstructor
	private static class VolatileValues {
		/**
		 * Contains catalog version which can be purged from {@link #historicalVersions} and {@link #volatileValues}
		 * on the next promotion.
		 */
		private final AtomicLong purgeOlderThan = new AtomicLong(-1);
		/**
		 * Non flushed values contains all values that has been modified in this OffsetIndex instance and their locations were
		 * not yet flushed to the disk. They might have been written to the disk, but their location is still only in memory
		 * and in case of crash they wouldn't be retrievable. Flush persists all file locations to disk and performs sync.
		 */
		@Nullable
		private volatile ConcurrentHashMap<Long, NonFlushedValueSet> nonFlushedValues;
		/**
		 * Contains the information about all non-flushed versions.
		 */
		@Nullable
		private volatile long[] nonFlushedVersions;
		/**
		 * Pointers to the records that have been overwritten by the new versions of the same record.
		 */
		@Nullable
		private volatile ConcurrentHashMap<Long, PastMemory> volatileValues;
		/**
		 * Represents a sorted set of catalog versions used as keys in {@link #volatileValues} living in the memory.
		 * This variable can be modified only in critical sections that are covered by write-handle lock to single
		 * threaded access.
		 */
		@Nullable
		private volatile long[] historicalVersions;

		/**
		 * Counts all non-flushed records not yet promoted to the shared state and the historical versions we still
		 * keep memory.
		 *
		 * @param catalogVersion the catalog version that limits the visibility of changes
		 * @return the count of non-flushed records
		 */
		public int countDifference(long catalogVersion) {
			int diff = 0;

			// scan non-flushed values
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvValues = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null) {
				int index = Arrays.binarySearch(nv, catalogVersion + 1);
				if (index != -1) {
					final int startIndex = index >= 0 ? index - 1 : -index - 2;
					for (int ix = nv.length - 1; ix >= startIndex && ix >= 0; ix--) {
						final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
						diff += nonFlushedValueSet.getAddedKeys().size() - nonFlushedValueSet.getRemovedKeys().size();
					}
				}
			}

			// scan also all previous versions we still keep in memory
			final ConcurrentHashMap<Long, PastMemory> hvValues = this.volatileValues;
			final long[] hv = this.historicalVersions;
			if (hv != null) {
				int index = Arrays.binarySearch(hv, catalogVersion + 1);
				if (index != -1) {
					final int startIndex = index >= 0 ? index - 1 : -index - 2;
					for (int ix = hv.length - 1; ix > startIndex && ix >= 0; ix--) {
						final PastMemory differenceSet = hvValues.get(hv[ix]);
						diff -= differenceSet.getAddedKeys().size() - differenceSet.getRemovedKeys().size();
					}
				}
			}

			return diff;
		}

		/**
		 * Counts all non-flushed records of specific type not yet promoted to the shared state.
		 *
		 * @param recordTypeId   the record type id
		 * @param catalogVersion the catalog version that limits the visibility of changes
		 * @return the count of non-flushed records of particular type
		 */
		public int countDifference(long catalogVersion, byte recordTypeId) {
			int diff = 0;

			// scan non-flushed values
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvValues = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null) {
				int index = Arrays.binarySearch(nv, catalogVersion + 1);
				if (index != -1) {
					final int startIndex = index >= 0 ? index - 1 : -index - 2;
					for (int ix = nv.length - 1; ix >= startIndex && ix >= 0; ix--) {
						final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
						for (RecordKey addedKey : nonFlushedValueSet.getAddedKeys()) {
							if (addedKey.recordType() == recordTypeId) {
								diff++;
							}
						}
						for (RecordKey removedKey : nonFlushedValueSet.getRemovedKeys()) {
							if (removedKey.recordType() == recordTypeId) {
								diff--;
							}
						}
					}
				}
			}

			// scan also all previous versions we still keep in memory
			final ConcurrentHashMap<Long, PastMemory> hvValues = this.volatileValues;
			final long[] hv = this.historicalVersions;
			if (hv != null) {
				int index = Arrays.binarySearch(hv, catalogVersion + 1);
				if (index != -1) {
					final int startIndex = index >= 0 ? index - 1 : -index - 2;
					for (int ix = hv.length - 1; ix > startIndex && ix >= 0; ix--) {
						final PastMemory differenceSet = hvValues.get(hv[ix]);
						for (RecordKey addedKey : differenceSet.getAddedKeys()) {
							if (addedKey.recordType() == recordTypeId) {
								diff--;
							}
						}
						for (RecordKey removedKey : differenceSet.getRemovedKeys()) {
							if (removedKey.recordType() == recordTypeId) {
								diff++;
							}
						}
					}
				}
			}

			return diff;
		}

		/**
		 * Retrieves the non-flushed versioned value associated with the given catalog version and key.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @param key            the record key
		 * @return an Optional containing the non-flushed VersionedValue if it exists, empty Optional otherwise
		 */
		@Nonnull
		public Optional<VersionedValue> getNonFlushedValueIfVersionMatches(long catalogVersion, @Nonnull RecordKey key) {
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvSet = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null) {
				int index = Arrays.binarySearch(nv, catalogVersion);
				if (index != -1) {
					final int startIndex = index >= 0 ? index - 1 : -index - 2;
					for (int ix = nv.length - 1; ix > startIndex && ix >= 0; ix--) {
						final Optional<VersionedValue> versionedValue = ofNullable(nvSet.get(nv[ix]))
							.map(it -> it.get(key));
						if (versionedValue.isPresent()) {
							return versionedValue;
						}
					}
				}
			}
			return empty();
		}

		/**
		 * Retrieves the non-flushed entries if they exist.
		 *
		 * @return an Optional containing the NonFlushedValueSet if it exists, empty Optional otherwise
		 */
		@Nonnull
		public OptionalLong getLastNonFlushedCatalogVersionIfExists() {
			final long[] nv = this.nonFlushedVersions;
			return nv == null || nv.length == 0 ?
				OptionalLong.empty() :
				OptionalLong.of(nv[nv.length - 1]);
		}

		/**
		 * Returns true if there are non-flushed values waiting to be flushed.
		 *
		 * @return true if there are non-flushed values, false otherwise
		 */
		public boolean hasValuesToFlush() {
			return !(nonFlushedValues == null || nonFlushedValues.isEmpty());
		}

		/**
		 * Retrieves the {@link VersionedValue} location associated with the passed key and valid for particular catalog
		 * version. The method first looks at the non-flushed values and then at the previously rewritten ones.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @param key            the record key
		 * @return the VersionedValue location if it exists, empty otherwise
		 */
		@Nonnull
		public Optional<VersionedValue> getPreviousValue(long catalogVersion, @Nonnull RecordKey key) {
			// scan also all previous versions we still keep in memory
			if (this.historicalVersions != null) {
				int index = Arrays.binarySearch(this.historicalVersions, catalogVersion);
				if (index != -1) {
					final int startIndex = index >= 0 ? index : -index - 1;
					for (int ix = startIndex; ix < this.volatileValues.size(); ix++) {
						final PastMemory pastMemory = this.volatileValues.get(this.historicalVersions[ix]);
						final VersionedValue previousValue = pastMemory.getPreviousValue(key);
						if (previousValue != null) {
							return of(previousValue);
						}
					}
				}
			}
			// ok - we didn't find anything
			return empty();
		}

		/**
		 * Stores new value to non-flushed storage. The value will be propagated to the shared state once the
		 * {@link #flush(long)} method is called.
		 *
		 * @param catalogVersion  the catalog version the value is written for
		 * @param key             the record key
		 * @param nonFlushedValue the non-flushed value to store
		 * @param create          whether the record was created or not (affects the histogram)
		 */
		public void putValue(long catalogVersion, @Nonnull RecordKey key, @Nonnull VersionedValue nonFlushedValue, boolean create) {
			getNonFlushedValues(catalogVersion).put(key, nonFlushedValue, create);
		}

		/**
		 * Stores information about removal of the existing value to non-flushed storage. The removal will be propagated
		 * to the shared state once the {@link #flush(long)} method is called.
		 *
		 * @param key            the record key
		 * @param catalogVersion the catalog version the value is written for
		 * @param fileLocation   the existing file location of the removed value
		 */
		public void removeValue(long catalogVersion, @Nonnull RecordKey key, @Nonnull FileLocation fileLocation) {
			getNonFlushedValues(catalogVersion).remove(key, fileLocation);
		}

		/**
		 * Retrieves the non-flushed entries associated with the given catalog version.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @return a collection of non-flushed entries if they exist, an empty collection otherwise
		 */
		@Nonnull
		public Collection<NonFlushedValueSet> getNonFlushedEntriesToPromote(long catalogVersion) {
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvSet = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null) {
				Assert.isPremiseValid(
					catalogVersion >= nv[nv.length - 1],
					"Catalog version is expected to be at least " + nv[nv.length - 1] + "!"
				);
				final List<NonFlushedValueSet> result = new ArrayList<>(nv.length);
				for (long cv : nv) {
					result.add(
						Objects.requireNonNull(nvSet.get(cv), "Non-flushed value set for catalog version " + cv + " is unexpectedly missing!")
					);
				}
				// clear all the data that has been promoted
				this.nonFlushedVersions = null;
				this.nonFlushedValues = null;
				return result;
			} else {
				return Collections.emptyList();
			}
		}

		/**
		 * Returns the set of non-flushed values to promote to the shared state and clears the container so that it
		 * could be initialized lazily on first next write. Method also stores information about locations of previous
		 * versions of the records that were overwritten by the new versions.
		 *
		 * @param nonFlushedValueSetsToPromote the set of non-flushed values to promote to the shared state
		 * @param keyToLocations               the map of current shared state of the record keys to their file locations
		 */
		public void recordHistoricalVersions(
			@Nonnull Collection<NonFlushedValueSet> nonFlushedValueSetsToPromote,
			@Nonnull Map<RecordKey, FileLocation> keyToLocations
		) {
			final long versionToPurge = purgeOlderThan.getAndSet(-1);
			// remove all versions that are lower than the given catalog version in a safe - single threaded scope
			if (versionToPurge > -1) {
				if (this.historicalVersions != null) {
					final long[] versionsToPurge = this.historicalVersions;
					int index = Arrays.binarySearch(versionsToPurge, versionToPurge);
					final int startIndex = index >= 0 ? index : -index - 2;
					if (index != -1) {
						for (int ix = startIndex; ix >= 0; ix--) {
							this.volatileValues.remove(versionsToPurge[ix]);
						}
					}
					this.historicalVersions = Arrays.copyOfRange(versionsToPurge, startIndex + 1, versionsToPurge.length);
				}
			}
			for (NonFlushedValueSet valuesToPromote : nonFlushedValueSetsToPromote) {
				final long catalogVersion = valuesToPromote.getCatalogVersion();
				if (this.historicalVersions == null) {
					this.historicalVersions = new long[]{catalogVersion};
					this.volatileValues = CollectionUtils.createConcurrentHashMap(16);
					this.volatileValues.put(catalogVersion, valuesToPromote.createFrom(keyToLocations));
				} else {
					this.volatileValues.compute(
						catalogVersion,
						(key, value) -> {
							if (value == null) {
								final long[] hv = this.historicalVersions;
								this.historicalVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, hv);
								return valuesToPromote.createFrom(keyToLocations);
							} else {
								return valuesToPromote.mergeWith(value, keyToLocations);
							}
						}
					);
				}
			}
		}

		/**
		 * Removes all versions of volatile record backup that are lower than the given catalog version.
		 * There will never be another client asking for those values.
		 *
		 * @param catalogVersion the catalog version to compare against
		 */
		public void purge(long catalogVersion) {
			purgeOlderThan.accumulateAndGet(
				catalogVersion,
				(prev, next) -> prev > -1 ? Math.min(prev, next) : next
			);
		}

		/**
		 * Clears all non-flushed values.
		 */
		public void forgetNonFlushedValues() {
			this.nonFlushedVersions = null;
			this.nonFlushedValues = null;
		}

		/**
		 * Retrieves the NonFlushedValueSet associated with the given catalog version or creates new set.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @return the NonFlushedValueSet if it exists, otherwise it creates a new one and returns it
		 */
		@Nonnull
		private NonFlushedValueSet getNonFlushedValues(long catalogVersion) {
			if (this.nonFlushedVersions == null) {
				this.nonFlushedValues = CollectionUtils.createConcurrentHashMap(16);
				this.nonFlushedVersions = new long[]{catalogVersion};
				final NonFlushedValueSet nv = new NonFlushedValueSet(catalogVersion);
				this.nonFlushedValues.put(catalogVersion, nv);
				return nv;
			} else {
				return this.nonFlushedValues.computeIfAbsent(
					catalogVersion,
					cv -> {
						final long[] nv = this.nonFlushedVersions;
						final long lastCatalogVersion = nv[nv.length - 1];
						Assert.isPremiseValid(
							lastCatalogVersion == -1 || lastCatalogVersion <= catalogVersion,
							() -> new EvitaInternalError(
								"You're trying to write to an already completed version `" + catalogVersion + "`, current is `" + lastCatalogVersion + "`!",
								"You're trying to write to an already completed version!"
							)
						);
						this.nonFlushedVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, nv);
						return new NonFlushedValueSet(catalogVersion);
					}
				);
			}
		}

	}

	/**
	 * This class is used to build initial OffsetIndex in {@link OffsetIndexSerializationService} and switch atomically
	 * the real (operative) OffsetIndex contents atomically once it's done.
	 */
	@Getter
	public static class FileOffsetIndexBuilder {
		private final ConcurrentHashMap<RecordKey, FileLocation> builtIndex = CollectionUtils.createConcurrentHashMap(KEY_HASH_MAP_INITIAL_SIZE);
		private final ConcurrentHashMap<Byte, Integer> histogram = CollectionUtils.createConcurrentHashMap(HISTOGRAM_INITIAL_CAPACITY);
		private long totalSize;
		private int maxSize;

		/**
		 * Registers a record key with its corresponding file location in the built index and updates the histogram
		 * and total size.
		 *
		 * @param recordKey    The record key to register.
		 * @param fileLocation The file location associated with the record key.
		 */
		public void register(@Nonnull RecordKey recordKey, @Nonnull FileLocation fileLocation) {
			this.builtIndex.put(recordKey, fileLocation);
			this.histogram.merge(recordKey.recordType(), 1, Integer::sum);
			this.totalSize += fileLocation.recordLength();
			if (this.maxSize < fileLocation.recordLength()) {
				this.maxSize = fileLocation.recordLength();
			}
		}

		/**
		 * Checks if the specified record key is contained in the built index.
		 *
		 * @param recordKey The record key to check.
		 * @return `true` if the built index contains the record key, `false` otherwise.
		 */
		public boolean contains(@Nonnull RecordKey recordKey) {
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
	 * The {@code PastMemory} class represents the past updates of a records in specific catalog version.
	 * It contains information about the replaced values, added keys, and removed keys.
	 */
	@RequiredArgsConstructor
	@Getter
	private static class PastMemory {
		@Nonnull private final Map<RecordKey, VersionedValue> replacedValues;
		@Nonnull private final Set<RecordKey> addedKeys;
		@Nonnull private final Set<RecordKey> removedKeys;

		/**
		 * Retrieves the previous value associated with the specified record key.
		 *
		 * @param key The record key for which to retrieve the previous value.
		 * @return The previous value associated with the record key, or null if no previous value exists.
		 */
		@Nullable
		public VersionedValue getPreviousValue(@Nonnull RecordKey key) {
			return replacedValues.get(key);
		}

		/**
		 * Checks if the specified record key exists in the map of replaced values.
		 *
		 * @param replacedKey The record key to check for existence.
		 * @return true if the record key exists in the map of replaced values, false otherwise.
		 */
		public boolean containsKey(@Nonnull RecordKey replacedKey) {
			return replacedValues.containsKey(replacedKey);
		}
	}

	/**
	 * This record is used to propagate multiple values in the {@link #doFlush(long, OffsetIndexDescriptor, boolean)}
	 * method.
	 *
	 * @param nonFlushedValueSets set of non-flushed value sets that have been flushed
	 * @param valueCount          count of non-flushed values that have been flushed (allows to properly initialize collection sizes)
	 * @param fileLocation        the file location of the offset-index descriptor in the file that covers the newly flushed values
	 */
	private record NonFlushedValuesWithFileLocation(
		@Nonnull Collection<NonFlushedValueSet> nonFlushedValueSets,
		int valueCount,
		@Nonnull FileLocation fileLocation
	) {
	}

	/**
	 * This class is used to monitor and limit {@link ReadOnlyHandle} pool. It creates new handles on demand in
	 * locked fashion and verifies that maximum opened handles limit is not exceeded.
	 */
	private class OffsetIndexObservableInputPool extends Pool<ReadOnlyHandle> {
		private final ReentrantLock readFilesLock = new ReentrantLock();

		private OffsetIndexObservableInputPool() {
			super(true, false);
		}

		/**
		 * Executes the provided logic on the borrowed ReadOnlyHandle and returns the result.
		 *
		 * @param logic the function that takes a ReadOnlyHandle and returns a result
		 * @param <T>   the type of the result
		 * @return the result of executing the provided logic
		 */
		public <T> T borrowAndExecute(@Nonnull Function<ReadOnlyHandle, T> logic) {
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
