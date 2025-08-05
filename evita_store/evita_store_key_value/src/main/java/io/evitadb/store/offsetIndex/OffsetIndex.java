/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.GenericEvitaInternalError;
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
import io.evitadb.store.offsetIndex.model.StorageRecord.RawRecord;
import io.evitadb.store.offsetIndex.model.VersionedValue;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.*;
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
	 * Default size of the pools for decompression.
	 */
	public static final int DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY = 5;
	/**
	 * Pool that is usually empty, but may contain large byte arrays that are used as temporary containers during
	 * decompression of binary records.
	 */
	private final Pool<byte[]> decompressionPool;
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
	private final AtomicLong maxRecordSizeBytes = new AtomicLong(0);
	/**
	 * Keeps track of total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 */
	private final AtomicLong totalSizeBytes = new AtomicLong(0);
	/**
	 * Volatile values contains history of previous writes and removals so that offset index can provide access to
	 * the correct contents based on the catalog version. Volatile values keep track only of the changes that have
	 * chance to be read by the client and try to be purged immediately when there is no chance to read them anymore.
	 * Otherwise their size would grow too large.
	 */
	private final VolatileValues volatileValues;
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

	/**
	 * Reads particular storage part from the target file path. This method will take `fileLocation` as leading pointer
	 * to the offset index mapping and iterates over all records in this file looking for the last occurrence of
	 * the particular storage part and returns it.
	 *
	 * @param filePath          The path to the file.
	 * @param fileLocation      The location of the leading pointer to the offset index mapping.
	 * @param recordKey         The looked up record key
	 * @param storagePartReader implementation that will take care of deserialization of the storage record from
	 *                          particular position in the file.
	 * @param <T>               The type of the storage part.
	 * @return deserialized storage part or null if the record was not found
	 */
	@Nonnull
	public static <T extends StoragePart> T readSingleRecord(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path filePath,
		@Nonnull FileLocation fileLocation,
		@Nonnull RecordKey recordKey,
		@Nonnull BiFunction<OffsetIndexBuilder, ObservableInput<?>, T> storagePartReader
	) {
		try (
			final ObservableInput<InputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(
					new RandomAccessFile(filePath.toFile(), "r"),
					true
				)
			)
		) {
			if (storageOptions.computeCRC32C()) {
				input.computeCRC32();
			}
			if (storageOptions.compress()) {
				input.compress();
			}
			final FilteringOffsetIndexBuilder filteringOffsetIndexBuilder = new FilteringOffsetIndexBuilder(recordKey);
			deserialize(
				input,
				fileLocation,
				filteringOffsetIndexBuilder
			);
			return Objects.requireNonNull(
				storagePartReader.apply(filteringOffsetIndexBuilder, input)
			);
		} catch (FileNotFoundException e) {
			throw new UnexpectedIOException(
				"Cannot create read offset file index from file `" + filePath + "`!",
				"OffsetIndex file not found! Critical error.",
				e
			);
		}
	}

	public OffsetIndex(
		long catalogVersion,
		@Nonnull OffsetIndexDescriptor fileOffsetDescriptor,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver
	) {
		this.storageOptions = storageOptions;
		this.fileOffsetDescriptor = fileOffsetDescriptor;
		this.recordTypeRegistry = recordTypeRegistry;
		this.volatileValues = new VolatileValues(
			nonFlushedBlockObserver == null ? nonFlushedBlock -> {
			} : nonFlushedBlockObserver,
			historicalRecordObserver == null ? historicalRecord -> {
			} : historicalRecordObserver
		);

		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
		this.readKryoPool = new FileOffsetIndexKryoPool(
			storageOptions.maxOpenedReadHandles(),
			version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
		);
		this.writeKryo = fileOffsetDescriptor.getWriteKryo();
		this.writeHandle = writeHandle;
		this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
		try {
			final Optional<CollectingOffsetIndexBuilder> fileOffsetIndexBuilder;
			if (this.lastSyncedPosition == 0) {
				fileOffsetIndexBuilder = Optional.empty();
			} else {
				fileOffsetIndexBuilder = of(
					readFileOffsetIndex(fileOffsetDescriptor.fileLocation())
				);
			}
			this.keyCatalogVersion = catalogVersion;
			this.keyToLocations = fileOffsetIndexBuilder
				.map(CollectingOffsetIndexBuilder::getBuiltIndex)
				.orElseGet(() -> CollectionUtils.createConcurrentHashMap(KEY_HASH_MAP_INITIAL_SIZE));
			this.histogram = fileOffsetIndexBuilder
				.map(CollectingOffsetIndexBuilder::getHistogram)
				.orElseGet(() -> CollectionUtils.createConcurrentHashMap(HISTOGRAM_INITIAL_CAPACITY));
			fileOffsetIndexBuilder
				.ifPresent(it -> {
					this.totalSizeBytes.set(it.getTotalSizeBytes());
					this.maxRecordSizeBytes.set(it.getMaxSizeBytes());
				});
			this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
				@Override
				protected byte[] create() {
					return new byte[storageOptions.outputBufferSize()];
				}
			};
		} catch (RuntimeException ex) {
			clearReadOnlyOpenedHandles();
			// clean resources before rethrowing the exception
			throw ex;
		}
	}

	public OffsetIndex(
		long catalogVersion,
		@Nonnull Path filePath,
		@Nonnull FileLocation fileLocation,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver,
		@Nonnull BiFunction<OffsetIndexBuilder, ObservableInput<?>, ? extends OffsetIndexDescriptor> offsetIndexDescriptorFactory
	) {
		this.storageOptions = storageOptions;
		this.writeHandle = writeHandle;
		this.volatileValues = new VolatileValues(
			nonFlushedBlockObserver == null ? nonFlushedBlock -> {
			} : nonFlushedBlockObserver,
			historicalRecordObserver == null ? historicalRecord -> {
			} : historicalRecordObserver
		);

		this.recordTypeRegistry = recordTypeRegistry;
		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
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
			if (storageOptions.computeCRC32C()) {
				input.computeCRC32();
			}
			if (storageOptions.compress()) {
				input.compress();
			}
			final CollectingOffsetIndexBuilder fileOffsetIndexBuilder = new CollectingOffsetIndexBuilder();
			deserialize(
				input,
				fileLocation,
				fileOffsetIndexBuilder
			);
			this.keyToLocations = fileOffsetIndexBuilder.getBuiltIndex();
			this.histogram = fileOffsetIndexBuilder.getHistogram();
			this.totalSizeBytes.set(fileOffsetIndexBuilder.getTotalSizeBytes());
			this.maxRecordSizeBytes.set(fileOffsetIndexBuilder.getMaxSizeBytes());
			this.fileOffsetDescriptor = offsetIndexDescriptorFactory.apply(fileOffsetIndexBuilder, input);
			this.keyCatalogVersion = catalogVersion;
			this.readKryoPool = new FileOffsetIndexKryoPool(
				storageOptions.maxOpenedReadHandles(),
				version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
			);
			this.writeKryo = this.fileOffsetDescriptor.getWriteKryo();
			this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
				@Override
				protected byte[] create() {
					return new byte[storageOptions.outputBufferSize()];
				}
			};
		} catch (FileNotFoundException e) {
			throw new UnexpectedIOException(
				"Cannot create read offset file index from file `" + filePath + "`!",
				"OffsetIndex file not found! Critical error.",
				e
			);
		}
	}

	public OffsetIndex(
		long catalogVersion,
		@Nonnull Path filePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver,
		@Nonnull OffsetIndex previousOffsetIndex,
		@Nonnull OffsetIndexDescriptor fileOffsetIndexDescriptor
	) {
		this.storageOptions = storageOptions;
		this.recordTypeRegistry = recordTypeRegistry;
		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
		this.writeHandle = writeHandle;
		this.volatileValues = new VolatileValues(
			nonFlushedBlockObserver == null ? nonFlushedBlock -> {
			} : nonFlushedBlockObserver,
			historicalRecordObserver == null ? historicalRecord -> {
			} : historicalRecordObserver
		);

		this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
		if (this.lastSyncedPosition == 0) {
			throw new UnexpectedIOException(
				"Cannot create OffsetIndex from empty file: `" + filePath + "`!",
				"Cannot create OffsetIndex from empty file!"
			);
		}

		this.keyToLocations = previousOffsetIndex.keyToLocations;
		this.histogram = previousOffsetIndex.histogram;
		this.totalSizeBytes.set(previousOffsetIndex.totalSizeBytes.get());
		this.maxRecordSizeBytes.set(previousOffsetIndex.getMaxRecordSizeBytes());
		this.fileOffsetDescriptor = fileOffsetIndexDescriptor;
		this.keyCatalogVersion = catalogVersion;
		this.readKryoPool = new FileOffsetIndexKryoPool(
			storageOptions.maxOpenedReadHandles(),
			version -> this.fileOffsetDescriptor.getReadKryoFactory().apply(version)
		);
		this.writeKryo = this.fileOffsetDescriptor.getWriteKryo();
		this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
			@Override
			protected byte[] create() {
				return new byte[storageOptions.outputBufferSize()];
			}
		};
	}

	/**
	 * Returns version of the current OffsetIndexDescriptor instance. This version can be used to recognize, whether
	 * there was any real change made before and after {@link #flush(long)} or {@link #close()} operations.
	 */
	public long getVersion() {
		return this.fileOffsetDescriptor.version();
	}

	/**
	 * Returns readable instance of key compressor.
	 */
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.fileOffsetDescriptor.getReadOnlyKeyCompressor();
	}

	/**
	 * Returns unmodifiable map of current index of compressed keys.
	 *
	 * @return unmodifiable map of current index of compressed keys
	 */
	@Nonnull
	public Map<Integer, Object> getCompressedKeys() {
		return Collections.unmodifiableMap(
			this.fileOffsetDescriptor.getWriteKeyCompressor().getKeys()
		);
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE entries in the OffsetIndex.
	 */
	public Collection<Entry<RecordKey, FileLocation>> getEntries() {
		assertOperative();
		return Collections.unmodifiableCollection(this.keyToLocations.entrySet());
	}

	/**
	 * Returns current count of ACTIVE entries in OffsetIndex.
	 */
	public int count(long catalogVersion) {
		assertOperative();
		return this.keyToLocations.size() + this.volatileValues.countDifference(catalogVersion);
	}

	/**
	 * Returns current count of ACTIVE entries of certain type in OffsetIndex.
	 */
	public int count(long catalogVersion, @Nonnull Class<? extends StoragePart> recordType) {
		assertOperative();
		final byte recordTypeId = this.recordTypeRegistry.idFor(recordType);
		return ofNullable(this.histogram.get(recordTypeId)).orElse(0) + this.volatileValues.countDifference(catalogVersion, recordTypeId);
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
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.removed()) {
				return null;
			} else {
				try {
					// if the record was not yet flushed to the disk we need to enforce sync so that we can read it
					if (this.lastSyncedPosition < nonFlushedValue.fileLocation().endPosition()) {
						doSoftFlush();
					}
					//noinspection unchecked
					return (T) get(nonFlushedValue.fileLocation(), this.recordTypeRegistry.typeFor(nonFlushedValue.recordType()));
				} catch (KryoException exception) {
					throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
				}
			}
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VolatileValueInformation> volatileValueRef = this.volatileValues.getVolatileValueInformation(catalogVersion, key);
			if (volatileValueRef.isPresent()) {
				final VolatileValueInformation volatileValue = volatileValueRef.get();
				if (volatileValue.removed() || volatileValue.addedInFuture()) {
					return null;
				} else {
					final VersionedValue rewrittenValue = volatileValue.versionedValue();
					if (rewrittenValue == null) {
						return null;
					} else {
						//noinspection unchecked
						return (T) get(rewrittenValue.fileLocation(), this.recordTypeRegistry.typeFor(rewrittenValue.recordType()));
					}
				}
			}
		}

		return ofNullable(this.keyToLocations.get(key))
			.map(it -> doGet(recordType, primaryKey, it))
			.filter(it -> it.generationId() <= catalogVersion)
			.map(StorageRecord::payload)
			.orElse(null);
	}

	/**
	 * Returns value assigned to the particular location in OffsetIndex. This method is optimized for sequential access
	 * by {@link #getEntries()} or {@link #getFileLocations()} avoiding unnecessary index lookup.
	 */
	@Nullable
	public <T extends Serializable> T get(@Nonnull FileLocation location, @Nonnull Class<T> recordType) {
		// if the record was not yet flushed to the disk we need to enforce sync so that we can read it
		if (this.lastSyncedPosition < location.endPosition()) {
			doSoftFlush();
		}

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
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.removed()) {
				return null;
			} else {
				try {
					// if the record was not yet flushed to the disk we need to enforce sync so that we can read it
					if (this.lastSyncedPosition < nonFlushedValue.fileLocation().endPosition()) {
						doSoftFlush();
					}
					return getBinary(nonFlushedValue.fileLocation(), this.recordTypeRegistry.typeFor(nonFlushedValue.recordType()));
				} catch (KryoException exception) {
					throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
				}
			}
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VolatileValueInformation> volatileValueRef = this.volatileValues.getVolatileValueInformation(catalogVersion, key);
			if (volatileValueRef.isPresent()) {
				final VolatileValueInformation volatileValue = volatileValueRef.get();
				if (volatileValue.removed() || volatileValue.addedInFuture()) {
					return null;
				} else {
					final VersionedValue rewrittenValue = volatileValue.versionedValue();
					if (rewrittenValue == null) {
						return null;
					} else {
						return getBinary(rewrittenValue.fileLocation(), this.recordTypeRegistry.typeFor(rewrittenValue.recordType()));
					}
				}
			}
		}

		return ofNullable(this.keyToLocations.get(key))
			.map(it -> doGetBinary(recordType, primaryKey, it))
			.filter(it -> it.generationId() <= catalogVersion)
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
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			return !nonFlushedValue.removed();
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VolatileValueInformation> volatileValueRef = this.volatileValues.getVolatileValueInformation(catalogVersion, key);
			if (volatileValueRef.isPresent()) {
				final VolatileValueInformation volatileValue = volatileValueRef.get();
				return !(volatileValue.removed() || volatileValue.addedInFuture());
			}
		}

		return this.keyToLocations.containsKey(key);
	}

	/**
	 * Stores or overwrites record with passed primary key in OffsetIndex. Values of different types are distinguished by
	 * the OffsetIndex so that two different types of objects with same primary keys don't overwrite each other.
	 *
	 * @param catalogVersion will be propagated to {@link StorageRecord#generationId()}
	 * @param value          value to be stored
	 */
	public <T extends StoragePart> long put(long catalogVersion, @Nonnull T value) {
		return this.writeHandle.checkAndExecute(
			"Storing record",
			this::assertOperative,
			exclusiveWriteAccess -> {
				final long partId = ofNullable(value.getStoragePartPK())
					.orElseGet(() -> value.computeUniquePartIdAndSet(this.fileOffsetDescriptor.getWriteKeyCompressor()));
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
		return this.writeHandle.checkAndExecute(
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
		return this.readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				inputStream -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> verify(
							inputStream,
							readOnlyFileHandle.getLastWrittenPosition(),
							new FileOffsetIndexStatistics(
								// use the latest possible version - we need actual count of records
								this.count(Long.MAX_VALUE),
								this.getTotalSizeBytes()
							), this.getStorageOptions()
						)
					);
				}
			)
		);
	}

	/**
	 * Flushes current state of the OffsetIndex to the disk. File contents are in sync when this method finalizes.
	 *
	 * @param catalogVersion will be propagated to {@link StorageRecord#generationId()}
	 */
	@Nonnull
	public OffsetIndexDescriptor flush(long catalogVersion) {
		assertOperative();
		this.keyCatalogVersion = catalogVersion;
		this.fileOffsetDescriptor = doFlush(catalogVersion, this.fileOffsetDescriptor, false);
		return this.fileOffsetDescriptor;
	}

	/**
	 * Purges the catalog for the given catalog version. This method should be called when there is no client using
	 * a particular version of the catalog.
	 *
	 * @param catalogVersion the version of the catalog that can be purged
	 * @throws IllegalStateException if the catalog is not in an operative state
	 */
	public void purge(long catalogVersion) {
		assertOperative();
		this.volatileValues.purge(catalogVersion);
	}

	/**
	 * Copies entire living data set to the target output stream. The output stream is not closed in the method,
	 * the caller is responsible for closing the stream.
	 *
	 * @param outputStream   target output stream to write the copy to
	 * @param progressConsumer consumer that will be called with the progress of the copy
	 * @param catalogVersion will be propagated to {@link StorageRecord#generationId()}
	 * @return result containing the file location and the file descriptor actual when the copy was made
	 */
	@Nonnull
	public OffsetIndexDescriptor copySnapshotTo(
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressConsumer,
		long catalogVersion,
		@Nullable StoragePart... updatedStorageParts
	) {
		// flush all non-flushed values to the disk
		this.doSoftFlush();
		// copy the active parts to a new file
		return this.readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				// by requesting write-handle we enforce no other thread can write to the source file while we are copying
				inputStream -> this.writeHandle.checkAndExecute(
					"Writing mem table",
					this::assertOperative,
					output -> this.readKryoPool.borrowAndExecute(
						kryo -> {
							Assert.isTrue(inputStream.getInputStream() instanceof RandomAccessFileInputStream, "Input stream must be RandomAccessFileInputStream!");
							@SuppressWarnings("unchecked") final ObservableInput<RandomAccessFileInputStream> randomAccessFileInputStream =
								(ObservableInput<RandomAccessFileInputStream>) inputStream;
							final Map<RecordKey, byte[]> overriddenEntries;
							if (updatedStorageParts != null && updatedStorageParts.length > 0) {
								overriddenEntries = CollectionUtils.createHashMap(updatedStorageParts.length);
								final ByteArrayOutputStream baos = new ByteArrayOutputStream(this.storageOptions.outputBufferSize());
								final ObservableOutput<ByteArrayOutputStream> observableOutput = new ObservableOutput<>(
									baos, this.storageOptions.outputBufferSize(), 0
								);
								for (StoragePart value : updatedStorageParts) {
									final RecordKey recordKey = new RecordKey(
										this.recordTypeRegistry.idFor(value.getClass()),
										ofNullable(value.getStoragePartPK())
											.orElseGet(() -> value.computeUniquePartIdAndSet(this.fileOffsetDescriptor.getWriteKeyCompressor()))
									);
									baos.reset();
									observableOutput.reset();
									serializeValue(value, observableOutput);
									observableOutput.flush();
									overriddenEntries.put(recordKey, baos.toByteArray());
								}
							} else {
								overriddenEntries = Collections.emptyMap();
							}
							final FileLocationAndWrittenBytes locationAndWrittenBytes = OffsetIndexSerializationService.copySnapshotTo(
								this,
								randomAccessFileInputStream,
								outputStream,
								catalogVersion,
								overriddenEntries,
								this.volatileValues,
								progressConsumer
							);
							return new OffsetIndexDescriptor(
								this.fileOffsetDescriptor.version() + 1,
								locationAndWrittenBytes.fileLocation(),
								this.getCompressedKeys(),
								this.fileOffsetDescriptor.getKryoFactory(),
								1,
								locationAndWrittenBytes.writtenBytes()
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
		this.operative = false;
		try {
			if (!this.shutdownDownProcedureActive.compareAndExchange(false, true)) {
				// spinning lock to close all opened handles once they occur free in pool
				clearReadOnlyOpenedHandles();
				// at last flush OffsetIndex and close write handle
				this.fileOffsetDescriptor = doFlush(
					// if there are any non-flushed values, use their version as the last version
					this.volatileValues.getLastNonFlushedCatalogVersionIfExists()
						.orElse(this.keyCatalogVersion),
					this.fileOffsetDescriptor,
					true
				);
				return this.fileOffsetDescriptor.fileLocation();
			} else {
				throw new GenericEvitaInternalError("OffsetIndex is already being closed!");
			}
		} finally {
			this.shutdownDownProcedureActive.compareAndExchange(true, false);
		}
	}

	/**
	 * Clears read-only file handles that have been opened but not properly released.
	 *
	 * This method attempts to close all handles in the `readOnlyOpenedHandles` collection that were unable to be
	 * released within a specific timeout defined by `storageOptions.waitOnCloseSeconds()`. It performs a cleanup
	 * of file handles to ensure that resources are released and avoid resource leakage.
	 */
	private void clearReadOnlyOpenedHandles() {
		long start = System.currentTimeMillis();
		while (!this.readOnlyOpenedHandles.isEmpty() && System.currentTimeMillis() - start > this.storageOptions.waitOnCloseSeconds() * 1000) {
			if (this.readOnlyHandlePool.getFree() > 0) {
				final ReadOnlyHandle handleToClose = this.readOnlyHandlePool.obtain();
				try {
					handleToClose.execute(
						exclusiveReadAccess -> {
							IOUtils.closeQuietly(exclusiveReadAccess::close);
							return null;
						});
					this.readOnlyOpenedHandles.remove(handleToClose);
				} catch (Exception ex) {
					log.error("Read handle cannot be closed!", ex);
					// ignore this - we need to close other files
				}
			}
		}
		// these handles were not released by the clients within the timeout
		for (ReadOnlyHandle readOnlyOpenedHandle : this.readOnlyOpenedHandles) {
			readOnlyOpenedHandle.close();
		}
		this.readOnlyOpenedHandles.clear();
	}

	/**
	 * Returns position of last fragment of the current {@link OffsetIndex} in the tracked file.
	 */
	@Nonnull
	public FileLocation getFileOffsetIndexLocation() {
		return this.fileOffsetDescriptor.fileLocation();
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
		return this.recordTypeRegistry.idFor(storagePartClass);
	}

	/**
	 * Returns maximal observed record size in this index.
	 *
	 * @return maximal observed record size in this index
	 */
	public long getMaxRecordSizeBytes() {
		return this.maxRecordSizeBytes.get();
	}

	/**
	 * Returns the oldest record kept timestamp.
	 *
	 * @return the oldest record kept timestamp
	 */
	@Nonnull
	public Optional<OffsetDateTime> getOldestRecordKeptTimestamp() {
		return this.volatileValues.getOldestRecordKeptTimestamp();
	}

	/**
	 * Returns histogram (counts) of particular record types in this index.
	 *
	 * @return histogram of particular record types in this index
	 */
	@Nonnull
	public Map<String, Integer> getHistogram() {
		return this.histogram.entrySet().stream()
			.collect(
				Collectors.toMap(
					it -> this.recordTypeRegistry.typeFor(it.getKey()).getSimpleName(),
					Entry::getValue
				)
			);
	}

	/**
	 * Returns the total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 *
	 * @return the total size
	 */
	public long getTotalSizeBytes() {
		return this.totalSizeBytes.get() + (long) this.keyToLocations.size() * (long) MEM_TABLE_RECORD_SIZE;
	}

	/**
	 * Returns the total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 *
	 * @return the total size
	 */
	public long getTotalSizeIncludingVolatileData() {
		return getTotalSizeBytes() + this.volatileValues.getTotalSize();
	}

	/**
	 * Calculates the living object share.
	 * The living object share is calculated as the ratio of the total size of the object and the size of the file
	 * that is being written to.
	 *
	 * @return the living object share as a double value
	 */
	public double getActiveRecordShare(long fileSize) {
		final double activeRecordShare = fileSize == 0 ? 1.0d : (double) getTotalActiveSize() / (double) fileSize;
		Assert.isPremiseValid(activeRecordShare >= 0, "Active record share must be non-negative!");
		return activeRecordShare;
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
		try (final FileOutputStream fos = new FileOutputStream(newFilePath.toFile())) {
			return copySnapshotTo(fos, null, this.keyCatalogVersion);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error occurred while compacting the snapshot to the new file: " + e.getMessage(),
				"Error occurred while compacting the snapshot to the new file.",
				e
			);
		}
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE keys in the OffsetIndex.
	 */
	Collection<RecordKey> getKeys() {
		assertOperative();
		return Collections.unmodifiableCollection(this.keyToLocations.keySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE file locations in the OffsetIndex.
	 */
	Collection<FileLocation> getFileLocations() {
		assertOperative();
		return Collections.unmodifiableCollection(this.keyToLocations.values());
	}

	/**
	 * Just for testing purposes - verifies whether the OffsetIndex contents equals the other OffsetIndex contents.
	 */
	boolean fileOffsetIndexEquals(@Nonnull OffsetIndex o) {
		if (this == o) return true;
		return this.keyToLocations.equals(o.keyToLocations);
	}

	/**
	 * Method serializes single {@link StoragePart} to an observable output stream. The value is not wrapped into
	 * a {@link StorageRecord} and is written in a bare form, so that it could be wrapped in {@link StorageRecord}
	 * later on.
	 *
	 * @param value            value to be serialized
	 * @param observableOutput target output stream
	 */
	private void serializeValue(
		@Nonnull StoragePart value,
		@Nonnull ObservableOutput<? extends OutputStream> observableOutput
	) {
		// we cant write new values into the kryo here, because we write to snapshot file
		this.readKryoPool.borrowAndExecute(
			kryo -> {
				kryo.writeObject(observableOutput, value);
				return null;
			}
		);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Calculates estimated total active size. In case of compression enabled this size might exceed the actual size
	 * of the file on the disk, since it calculates potential size of the all the records in the index (compressed)
	 * and the index itself (uncompressed - since it hasn't been compressed yet).
	 *
	 * Note: we could make this more precise if we'd store the size of the index in the {@link OffsetIndexDescriptor}
	 * and estimate the uncompressed size only for the volatile values. But we don't necessarily need that precision now.
	 *
	 * @return The total active size.
	 */
	private long getTotalActiveSize() {
		return this.totalSizeBytes.get() + countFileOffsetTableSize(this.keyToLocations.size(), this.storageOptions);
	}

	/**
	 * Checks whether the OffsetIndex is still opened and operative.
	 */
	private void assertOperative() {
		isPremiseValid(
			this.operative || this.shutdownDownProcedureActive.get(),
			"OffsetIndex has been already closed!"
		);
	}

	/**
	 * Reads OffsetIndex from the disk using write handle.
	 */
	private CollectingOffsetIndexBuilder readFileOffsetIndex(@Nonnull FileLocation location) {
		return this.readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					final CollectingOffsetIndexBuilder builder = new CollectingOffsetIndexBuilder();
					return this.readKryoPool.borrowAndExecute(kryo -> {
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
		// if there are any non-flushed values, we need to flush them to the disk (of if the offset index was not yet created)
		if (this.volatileValues.hasValuesToFlush() || fileOffsetIndexDescriptor.fileLocation() == FileLocation.EMPTY) {
			final OffsetIndexDescriptor newFileOffsetIndexDescriptor = this.writeHandle.checkAndExecuteAndSync(
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
							this.getStorageOptions(),
							"File: " + this.writeHandle + ", last written position: " + this.writeHandle.getLastWrittenPosition()
						)
					);
				},
				(outputStream, nonFlushedValuesWithFileLocation) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = this.writeHandle.getLastWrittenPosition();
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
					final long fileSize = this.writeHandle.getLastWrittenPosition();
					return new OffsetIndexDescriptor(
						nonFlushedValuesWithFileLocation.fileLocation(),
						fileOffsetIndexDescriptor,
						getActiveRecordShare(this.writeHandle.getLastWrittenPosition()),
						fileSize
					);
				}
			);

			if (close) {
				IOUtils.closeQuietly(this.writeHandle::close);
			}
			return newFileOffsetIndexDescriptor;
		} else {
			if (close) {
				IOUtils.closeQuietly(this.writeHandle::close);
			}
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
			this.writeHandle.checkAndExecuteAndSync(
				"Syncing changes to disk.",
				this::assertOperative,
				it -> null,
				(output, result) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = this.writeHandle.getLastWrittenPosition();
					// propagate changes in KeyCompressor to the read kryo pool
					if (this.fileOffsetDescriptor.resetDirty()) {
						this.fileOffsetDescriptor = new OffsetIndexDescriptor(
							new FileLocationAndWrittenBytes(
								this.fileOffsetDescriptor.fileLocation(),
								0,
								"Soft flush of non-flushed values"
							),
							this.fileOffsetDescriptor,
							getActiveRecordShare(this.lastSyncedPosition),
							this.lastSyncedPosition
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

		long workingMaxRecordSize = this.maxRecordSizeBytes.get();
		long recordLengthDelta = 0;

		final Map<Byte, Integer> histogramDiff = CollectionUtils.createHashMap(this.histogram.size());
		for (NonFlushedValueSet volatileValues : nonFlushedValueSets) {
			for (Entry<RecordKey, VersionedValue> entry : volatileValues.entrySet()) {
				final RecordKey recordKey = entry.getKey();
				final VersionedValue nonFlushedValue = entry.getValue();

				final int count;
				if (nonFlushedValue.removed()) {
					final FileLocation removedLocation = newKeyToLocations.remove(recordKey);
					// location might not exist when value was created and immediately removed
					if (removedLocation != null) {
						count = -1;
						recordLengthDelta -= removedLocation.recordLength();
					} else {
						count = 0;
					}
				} else if (volatileValues.wasAdded(recordKey)) {
					final FileLocation recordLocation = nonFlushedValue.fileLocation();
					final int currentRecordLength = recordLocation.recordLength();
					recordLengthDelta += currentRecordLength;
					if (currentRecordLength > workingMaxRecordSize) {
						workingMaxRecordSize = currentRecordLength;
					}
					final FileLocation previousValue = newKeyToLocations.put(recordKey, recordLocation);
					Assert.isPremiseValid(
						previousValue == null,
						"Record was already present!"
					);
					count = 1;
				} else {
					final FileLocation newRecordLocation = nonFlushedValue.fileLocation();
					final FileLocation existingRecordLocation = newKeyToLocations.put(recordKey, newRecordLocation);
					Assert.isPremiseValid(existingRecordLocation != null, "Record was not present!");
					recordLengthDelta += newRecordLocation.recordLength() - existingRecordLocation.recordLength();
					if (newRecordLocation.recordLength() > workingMaxRecordSize) {
						workingMaxRecordSize = newRecordLocation.recordLength();
					}
					count = 0;
				}

				histogramDiff.merge(
					recordKey.recordType(), count, Integer::sum
				);
			}
		}

		// update statistics
		this.totalSizeBytes.addAndGet(recordLengthDelta);
		this.maxRecordSizeBytes.set(workingMaxRecordSize);
		for (Entry<Byte, Integer> entry : histogramDiff.entrySet()) {
			this.histogram.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}
		// and the locations finally
		this.keyToLocations = newKeyToLocations;
	}

	/**
	 * Method stores new record to the OffsetIndex. This method should be called only from singleton writer and never
	 * directly from the code. All writes are serialized by exclusive write access.
	 */
	private void doPut(long catalogVersion, long primaryKey, @Nonnull StoragePart value, @Nonnull ObservableOutput<?> exclusiveWriteAccess) {
		final byte recordType = this.recordTypeRegistry.idFor(value.getClass());
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final boolean update = this.keyToLocations.containsKey(key);
		final FileLocation recordLocation = new StorageRecord<>(
			this.writeKryo,
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
		final byte recordType = this.recordTypeRegistry.idFor(valueType);
		final RecordKey key = new RecordKey(recordType, primaryKey);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.removed()) {
				return false;
			} else {
				this.volatileValues.removeValue(catalogVersion, key, nonFlushedValue.fileLocation());
				return true;
			}
		}

		if (catalogVersion < this.keyCatalogVersion) {
			final Optional<VolatileValueInformation> volatileValueRef = this.volatileValues.getVolatileValueInformation(catalogVersion, key);
			if (volatileValueRef.isPresent()) {
				final VolatileValueInformation volatileValue = volatileValueRef.get();
				if (volatileValue.removed() || volatileValue.addedInFuture()) {
					return false;
				}
			}
		}

		final FileLocation currentLocation = this.keyToLocations.get(key);
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
		return this.readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> {
							try {
								return StorageRecord.read(
									exclusiveReadAccess,
									it,
									(stream, length, control) -> kryo.readObject(stream, recordType)
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
		return this.readOnlyHandlePool.borrowAndExecute(
			readOnlyFileHandle -> readOnlyFileHandle.execute(
				exclusiveReadAccess -> {
					assertOperative();
					return this.readKryoPool.borrowAndExecute(
						kryo -> {
							try {
								exclusiveReadAccess.seek(it);
								final RawRecord rawRecord = StorageRecord.readRaw(exclusiveReadAccess);
								/* TOBEDONE 13 - THIS LOGIC SHOULD BE EXTRACTED TO HIGHER LEVELS,
								     DECOMPRESSION SHOULD OCCUR ON THE CLIENT TO SAVE NETWORK BANDWITH */
								final byte[] decompressed;
								if (BitUtils.isBitSet(rawRecord.control(), StorageRecord.COMPRESSION_BIT)) {
									// decompress the record first
									byte[] utility = null;
									try {
										utility = this.decompressionPool.obtain();
										final int decompressedBytes = exclusiveReadAccess.decompress(rawRecord.rawData(), utility);
										decompressed = Arrays.copyOf(utility, decompressedBytes);
									} finally {
										if (utility != null) {
											this.decompressionPool.free(utility);
										}
									}
								} else {
									decompressed = rawRecord.rawData();
								}
								// we need to manually read generation id, hence it may have been compressed
								return new StorageRecord<>(
									rawRecord.generationId(),
									BitUtils.isBitSet(rawRecord.control(), StorageRecord.GENERATION_CLOSING_BIT),
									decompressed,
									rawRecord.location()
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
			return (double) this.livingRecordSize / (double) this.totalSize;
		}

		/**
		 * Registers a record with the specified length in the statistics of the OffsetIndex file.
		 *
		 * @param length The length of the record to be registered.
		 */
		void registerRecord(int length) {
			this.recordCount++;
			this.totalSize += length;
			if (length > this.maxRecordSize) {
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
		/**
		 * Observer that is notified when a non-flushed block size increases.
		 */
		private final LongConsumer nonFlushedBlockSizeChangedCallback;

		public NonFlushedValueSet(long catalogVersion, @Nonnull LongConsumer nonFlushedBlockSizeChangedCallback) {
			this.catalogVersion = catalogVersion;
			this.nonFlushedBlockSizeChangedCallback = nonFlushedBlockSizeChangedCallback;
		}

		/**
		 * Returns instance of the record by its key if present in non-flushed index.
		 */
		@Nullable
		public VersionedValue get(@Nonnull RecordKey key) {
			return this.nonFlushedValueIndex.get(key);
		}

		/**
		 * Checks if a record with the specified key was added to the non-flushed index.
		 *
		 * @param key The key of the record to check.
		 * @return {@code true} if the record was added, {@code false} otherwise.
		 */
		public boolean wasAdded(@Nonnull RecordKey key) {
			return this.addedKeys.contains(key);
		}

		/**
		 * Returns a collection of all VersionedValue objects stored in the nonFlushedValueIndex.
		 *
		 * @return a collection of all VersionedValue objects
		 */
		@Nonnull
		public Collection<VersionedValue> getAllValues() {
			return this.nonFlushedValueIndex.values();
		}

		/**
		 * Stores instance of the record to the non-flushed index.
		 *
		 * @param create - when true it affects {@link #histogram} results
		 */
		public void put(@Nonnull RecordKey key, @Nonnull VersionedValue value, boolean create) {
			if (create) {
				this.nonFlushedValuesHistogram.merge(key.recordType(), 1, Integer::sum);
				this.addedKeys.add(key);
				this.removedKeys.remove(key);
			}
			this.nonFlushedValueIndex.put(key, value);
			this.nonFlushedBlockSizeChangedCallback.accept(value.fileLocation().recordLength());
		}

		/**
		 * Stores information about record removal to the non-flushed index.
		 * This will prevent loading record from the persistent storage even if its present there.
		 */
		public void remove(@Nonnull RecordKey key, @Nonnull FileLocation fileLocation) {
			this.nonFlushedValuesHistogram.merge(key.recordType(), -1, Integer::sum);
			this.nonFlushedValueIndex.put(key, new VersionedValue(key.primaryKey(), (byte) (key.recordType() * -1), fileLocation));
			this.addedKeys.remove(key);
			this.removedKeys.add(key);
			this.nonFlushedBlockSizeChangedCallback.accept(fileLocation.recordLength());
		}

		/**
		 * Returns iterator over all non-flushed records.
		 */
		@Nonnull
		public Iterable<? extends Entry<RecordKey, VersionedValue>> entrySet() {
			return this.nonFlushedValueIndex.entrySet();
		}

		/**
		 * Creates map of original values for keys that were rewritten by this non-flushed value set.
		 *
		 * @param currentLocations current locations of the records
		 * @return map of original values for keys that were rewritten by this non-flushed value set
		 */
		@Nonnull
		public PastMemory createFrom(@Nonnull Map<RecordKey, FileLocation> currentLocations) {
			final Map<RecordKey, VersionedValue> result = CollectionUtils.createHashMap(this.nonFlushedValueIndex.size());
			for (RecordKey replacedKey : this.nonFlushedValueIndex.keySet()) {
				ofNullable(currentLocations.get(replacedKey))
					.map(it -> new VersionedValue(replacedKey.primaryKey(), replacedKey.recordType(), it))
					.ifPresent(it -> result.put(replacedKey, it));
			}
			return new PastMemory(
				Collections.unmodifiableMap(result),
				this.addedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(this.addedKeys),
				this.removedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(this.removedKeys),
				this.nonFlushedValuesHistogram
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
			final Map<Byte, Integer> histogram = new HashMap<>(existingHistory.getHistogram());
			for (Entry<Byte, Integer> entry : this.nonFlushedValuesHistogram.entrySet()) {
				histogram.compute(entry.getKey(), (k, v) -> v == null ? entry.getValue() : v + entry.getValue());
			}
			final Map<RecordKey, VersionedValue> replacedValues = new HashMap<>(existingHistory.getReplacedValues());
			for (RecordKey replacedKey : this.nonFlushedValueIndex.keySet()) {
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
				removedKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(removedKeys),
				histogram
			);
		}

		/**
		 * Returns the estimated memory size occupied by this instance in Bytes.
		 *
		 * @return the estimated memory size occupied by this instance in Bytes
		 */
		public long getTotalSize() {
			return MemoryMeasuringConstants.LONG_SIZE +
				MemoryMeasuringConstants.OBJECT_HEADER_SIZE * 7 +
				this.nonFlushedValueIndex.size() * (RecordKey.MEMORY_SIZE + VersionedValue.MEMORY_SIZE) +
				this.nonFlushedValuesHistogram.size() * (2 * MemoryMeasuringConstants.OBJECT_HEADER_SIZE) +
				this.addedKeys.size() * RecordKey.MEMORY_SIZE +
				this.removedKeys.size() * RecordKey.MEMORY_SIZE;
		}

		/**
		 * Returns the count of non-flushed records of particular type.
		 * @param recordTypeId the record type id
		 * @return the count of non-flushed records of particular type
		 */
		public int getCountFor(byte recordTypeId) {
			return this.nonFlushedValuesHistogram.getOrDefault(recordTypeId, 0);
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
	static class VolatileValues {
		/**
		 * Contains catalog version which can be purged from {@link #historicalVersions} and {@link #volatileValues}
		 * on the next promotion.
		 */
		private final AtomicLong purgeOlderThan = new AtomicLong(-1);
		/**
		 * Observer that is notified when a non-flushed block size changes in any way.
		 */
		@Nonnull
		private final Consumer<NonFlushedBlock> nonFlushedBlockObserver;
		/**
		 * Observer that is notified when a historical versions data is purged.
		 */
		@Nonnull
		private final Consumer<Optional<OffsetDateTime>> historicalVersionsObserver;
		/**
		 * Lock guarding the access to the {@link #historicalVersions}.
		 */
		private final ReentrantLock lock = new ReentrantLock();
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
		 * Contains the last count of non-flushed records.
		 */
		private int nonFlushedRecordCount;
		/**
		 * Contains the last size of non-flushed records in Bytes.
		 */
		private long nonFlushedRecordSizeInBytes;

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
			if (nv != null && nvValues != null) {
				int index = Arrays.binarySearch(nv, catalogVersion);
				if (index != -1) {
					final int startIndex = index >= 0 ? index : -index - 1;
					for (int ix = nv.length - 1; ix >= startIndex && ix >= 0; ix--) {
						final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
						diff += nonFlushedValueSet.getAddedKeys().size() - nonFlushedValueSet.getRemovedKeys().size();
					}
				}
			}

			// scan also all previous versions we still keep in memory
			if (this.volatileValues != null) {
				final ConcurrentHashMap<Long, PastMemory> hvValues;
				final long[] hv;
				try {
					this.lock.lock();
					hvValues = this.volatileValues;
					hv = this.historicalVersions;
				} finally {
					this.lock.unlock();
				}
				if (hv != null) {
					int index = Arrays.binarySearch(hv, catalogVersion);
					if (index != -1 && hvValues != null) {
						final int startIndex = index >= 0 ? index : -index - 1;
						for (int ix = hv.length - 1; ix > startIndex && ix >= 0; ix--) {
							final PastMemory differenceSet = hvValues.get(hv[ix]);
							diff -= differenceSet.getAddedKeys().size() - differenceSet.getRemovedKeys().size();
						}
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
				int index = Arrays.binarySearch(nv, catalogVersion);
				if (index != -1 && nvValues != null) {
					final int startIndex = index >= 0 ? index : -index - 1;
					for (int ix = nv.length - 1; ix >= startIndex && ix >= 0; ix--) {
						final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
						diff += nonFlushedValueSet == null ? 0 : nonFlushedValueSet.getCountFor(recordTypeId);
					}
				}
			}

			// scan also all previous versions we still keep in memory
			if (this.volatileValues != null) {
				final ConcurrentHashMap<Long, PastMemory> hvValues;
				final long[] hv;
				try {
					this.lock.lock();
					hvValues = this.volatileValues;
					hv = this.historicalVersions;
				} finally {
					this.lock.unlock();
				}
				if (hv != null && hvValues != null) {
					int index = Arrays.binarySearch(hv, catalogVersion);
					if (index != -1) {
						final int startIndex = index >= 0 ? index : -index - 1;
						for (int ix = hv.length - 1; ix > startIndex && ix >= 0; ix--) {
							final PastMemory differenceSet = hvValues.get(hv[ix]);
							diff -= differenceSet == null ? 0 : differenceSet.getCountFor(recordTypeId);
						}
					}
				}
			}

			return diff;
		}

		/**
		 * Retrieves the non-flushed versioned value associated with the given catalog version (or lesser) and key.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @param key            the record key
		 * @return an Optional containing the non-flushed VersionedValue if it exists, empty Optional otherwise
		 */
		@Nonnull
		public Optional<VersionedValue> getNonFlushedValueIfVersionMatches(long catalogVersion, @Nonnull RecordKey key) {
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvSet = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null && nvSet != null) {
				int index = Arrays.binarySearch(nv, catalogVersion);
				final int startIndex = index >= 0 ? index : -index - 2;
				if (startIndex >= 0) {
					for (int ix = startIndex; ix >= 0; ix--) {
						final NonFlushedValueSet nfvs = nvSet.get(nv[ix]);
						if (nfvs != null) {
							final Optional<VersionedValue> versionedValue = ofNullable(nfvs.get(key));
							if (versionedValue.isPresent()) {
								return versionedValue;
							}
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
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvSet = this.nonFlushedValues;
			return nvSet != null && !nvSet.isEmpty();
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
		public Optional<VolatileValueInformation> getVolatileValueInformation(long catalogVersion, @Nonnull RecordKey key) {
			// scan also all previous versions we still keep in memory
			final long[] hv;
			final ConcurrentHashMap<Long, PastMemory> hvValues;
			try {
				this.lock.lock();
				hvValues = this.volatileValues;
				hv = this.historicalVersions;
			} finally {
				this.lock.unlock();
			}
			if (hv != null && hvValues != null) {
				int index = Arrays.binarySearch(hv, catalogVersion);
				final int startIndex = index >= 0 ? index : -index - 1;
				boolean addedInFuture = false;
				for (int ix = startIndex; ix < hv.length; ix++) {
					final long examinedVersion = hv[ix];
					final PastMemory pastMemory = hvValues.get(examinedVersion);
					if (pastMemory.getRemovedKeys().contains(key)) {
						//noinspection DataFlowIssue
						addedInFuture = false;
					}
					if (pastMemory.getAddedKeys().contains(key) && examinedVersion != catalogVersion) {
						addedInFuture = true;
					}
					// we must skip the current version, because it contains previous value that was overwritten
					// not the currently valid one
					if (examinedVersion != catalogVersion) {
						final VersionedValue previousValue = pastMemory.getPreviousValue(key);
						if (previousValue != null) {
							return of(new VolatileValueInformation(previousValue, previousValue.removed(), addedInFuture));
						} else if (addedInFuture) {
							return of(new VolatileValueInformation(null, false, addedInFuture));
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
			getNonFlushedValues(catalogVersion).put(key, nonFlushedValue, create && !contains(key));
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
			if (nv != null && nvSet != null) {
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
				this.nonFlushedRecordCount = 0;
				this.nonFlushedRecordSizeInBytes = 0L;
				// notify the observer
				this.nonFlushedBlockObserver.accept(new NonFlushedBlock(0, 0L));
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
			final long versionToPurge = this.purgeOlderThan.getAndSet(-1);
			// remove all versions that are lower than the given catalog version in a safe - single threaded scope
			if (versionToPurge > -1) {
				try {
					this.lock.lock();
					final long[] versionsToPurge = this.historicalVersions;
					final ConcurrentHashMap<Long, PastMemory> theVolatileValues = this.volatileValues;
					if (versionsToPurge != null && theVolatileValues != null) {
						int index = Arrays.binarySearch(versionsToPurge, versionToPurge);
						final int startIndex = index >= 0 ? index : -index - 2;
						if (index != -1) {
							for (int ix = startIndex; ix >= 0; ix--) {
								theVolatileValues.remove(versionsToPurge[ix]);
							}
						}
						this.historicalVersions = Arrays.copyOfRange(versionsToPurge, startIndex + 1, versionsToPurge.length);
						// notify the observer
						this.historicalVersionsObserver.accept(getOldestRecordKeptTimestamp());
					}
				} finally {
					this.lock.unlock();
				}
			}
			for (NonFlushedValueSet valuesToPromote : nonFlushedValueSetsToPromote) {
				final long catalogVersion = valuesToPromote.getCatalogVersion();
				try {
					this.lock.lock();
					final long[] hv = this.historicalVersions;
					final ConcurrentHashMap<Long, PastMemory> theVolatileValues = this.volatileValues;
					if (hv == null || theVolatileValues == null) {
						final ConcurrentHashMap<Long, PastMemory> newVolatileValues = CollectionUtils.createConcurrentHashMap(16);
						newVolatileValues.put(catalogVersion, valuesToPromote.createFrom(keyToLocations));
						this.historicalVersions = new long[]{catalogVersion};
						this.volatileValues = newVolatileValues;
					} else {
						theVolatileValues.compute(
							catalogVersion,
							(key, value) -> {
								if (value == null) {
									this.historicalVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, hv);
									return valuesToPromote.createFrom(keyToLocations);
								} else {
									return valuesToPromote.mergeWith(value, keyToLocations);
								}
							}
						);
					}
				} finally {
					this.lock.unlock();
				}
			}
		}

		/**
		 * Removes all versions of volatile record backup that are lower than the given catalog version.
		 * There will never be another client asking for those values.
		 *
		 * @param catalogVersion the catalog version to compare against, all values related to this or
		 *                       lesser version will be removed
		 */
		public void purge(long catalogVersion) {
			final long[] hv;
			try {
				this.lock.lock();
				hv = this.historicalVersions;
			} finally {
				this.lock.unlock();
			}
			if (hv != null && hv.length > 0) {
				this.purgeOlderThan.accumulateAndGet(
					catalogVersion,
					(prev, next) -> prev > -1 ? Math.min(prev, next) : next
				);
			}
		}

		/**
		 * Clears all non-flushed values.
		 */
		public void forgetNonFlushedValues() {
			this.nonFlushedVersions = null;
			this.nonFlushedValues = null;
			this.nonFlushedRecordCount = 0;
			this.nonFlushedRecordSizeInBytes = 0L;
			// notify the observer
			this.nonFlushedBlockObserver.accept(new NonFlushedBlock(0, 0L));
		}

		/**
		 * Estimates memory usage of the non-flushed values.
		 *
		 * @return the estimated memory usage of the non-flushed values in Bytes
		 */
		public long getTotalSize() {
			return Optional.ofNullable(this.nonFlushedVersions)
				.map(nv -> (long) nv.length * MemoryMeasuringConstants.LONG_SIZE)
				.orElse(0L) +
				Optional.ofNullable(this.historicalVersions)
					.map(hv -> (long) hv.length * MemoryMeasuringConstants.LONG_SIZE)
					.orElse(0L) +
				Optional.ofNullable(this.nonFlushedValues)
					.map(nv -> nv.values().stream().mapToLong(it -> MemoryMeasuringConstants.LONG_SIZE + it.getTotalSize()).sum())
					.orElse(0L) +
				Optional.ofNullable(this.volatileValues)
					.map(vv -> vv.values().stream().mapToLong(it -> MemoryMeasuringConstants.LONG_SIZE + it.getTotalSize()).sum())
					.orElse(0L)
				+ MemoryMeasuringConstants.OBJECT_HEADER_SIZE * 4
				+ MemoryMeasuringConstants.INT_SIZE + MemoryMeasuringConstants.LONG_SIZE;
		}

		/**
		 * Returns the timestamp of the oldest record kept in the volatile values.
		 *
		 * @return the timestamp of the oldest record kept in the volatile values
		 */
		@Nonnull
		public Optional<OffsetDateTime> getOldestRecordKeptTimestamp() {
			return ofNullable(this.volatileValues)
				.map(it -> {
					final long index = ofNullable(this.historicalVersions)
						.filter(hv -> !ArrayUtils.isEmpty(hv))
						.map(hv -> hv[0])
						.orElse(-1L);
					return index == -1 ? null :
						OffsetDateTime.ofInstant(
							Instant.ofEpochMilli(it.get(index).getTimestamp()),
							ZoneId.systemDefault()
						);
				});
		}

		/**
		 * Returns true if the non-flushed values contain the non-removed specified key.
		 * @param key the record key
		 * @return true if the non-flushed values contain the non-removed specified key, false otherwise
		 */
		public boolean contains(@Nonnull RecordKey key) {
			final long[] nv = this.nonFlushedVersions;
			final ConcurrentHashMap<Long, NonFlushedValueSet> theNonVlushedValues = this.nonFlushedValues;
			if (nv != null && theNonVlushedValues != null) {
				for (int i = nv.length - 1; i >= 0; i--) {
					long nonFlushedVersion = nv[i];
					final NonFlushedValueSet nfSet = theNonVlushedValues.get(nonFlushedVersion);
					if (nfSet != null) {
						if (nfSet.removedKeys.contains(key)) {
							return false;
						} else if (nfSet.addedKeys.contains(key)) {
							return true;
						}
					}
				}
			}
			return false;
		}

		/**
		 * Retrieves the NonFlushedValueSet associated with the given catalog version or creates new set.
		 *
		 * @param catalogVersion the catalog version to check against
		 * @return the NonFlushedValueSet if it exists, otherwise it creates a new one and returns it
		 */
		@Nonnull
		private NonFlushedValueSet getNonFlushedValues(long catalogVersion) {
			final long[] nv = this.nonFlushedVersions;
			final ConcurrentHashMap<Long, NonFlushedValueSet> theNonFlushedValues = this.nonFlushedValues;
			if (nv == null || theNonFlushedValues == null) {
				final ConcurrentHashMap<Long, NonFlushedValueSet> newNonFlushedValues = CollectionUtils.createConcurrentHashMap(16);
				final NonFlushedValueSet nvSet = new NonFlushedValueSet(catalogVersion, this::notifySizeIncrease);
				newNonFlushedValues.put(catalogVersion, nvSet);
				this.nonFlushedValues = newNonFlushedValues;
				this.nonFlushedVersions = new long[]{catalogVersion};
				return nvSet;
			} else {
				return theNonFlushedValues.computeIfAbsent(
					catalogVersion,
					cv -> {
						final long lastCatalogVersion = nv[nv.length - 1];
						Assert.isPremiseValid(
							lastCatalogVersion == -1 || lastCatalogVersion <= catalogVersion,
							() -> new GenericEvitaInternalError(
								"You're trying to write to an already completed version `" + catalogVersion + "`, current is `" + lastCatalogVersion + "`!",
								"You're trying to write to an already completed version!"
							)
						);
						this.nonFlushedVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, nv);
						return new NonFlushedValueSet(catalogVersion, this::notifySizeIncrease);
					}
				);
			}
		}

		/**
		 * Notifies the observer about the size increase of the non-flushed block.
		 *
		 * @param sizeInBytes the size increase in Bytes.
		 */
		private void notifySizeIncrease(long sizeInBytes) {
			this.nonFlushedRecordCount++;
			this.nonFlushedRecordSizeInBytes += sizeInBytes;
			this.nonFlushedBlockObserver.accept(new NonFlushedBlock(this.nonFlushedRecordCount, this.nonFlushedRecordSizeInBytes));
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
			return this.supplier.apply(this.version);
		}

		/**
		 * Returns borrowed instance back to the pool.
		 */
		@Override
		public void free(VersionedKryo object) {
			// if object version is the same as actual version, accept it,
			// otherwise it would be discarded and garbage collected
			if (object.getVersion() == this.version) {
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
		@Getter private final long timestamp = System.currentTimeMillis();
		@Nonnull private final Map<RecordKey, VersionedValue> replacedValues;
		@Nonnull private final Set<RecordKey> addedKeys;
		@Nonnull private final Set<RecordKey> removedKeys;
		/**
		 * Map of non-flushed values. We can use "non-concurrent" map because this instance is secured by the write
		 * handle for concurrent access.
		 */
		private final Map<Byte, Integer> histogram;

		/**
		 * Retrieves the previous value associated with the specified record key.
		 *
		 * @param key The record key for which to retrieve the previous value.
		 * @return The previous value associated with the record key, or null if no previous value exists.
		 */
		@Nullable
		public VersionedValue getPreviousValue(@Nonnull RecordKey key) {
			return this.replacedValues.get(key);
		}

		/**
		 * Checks if the specified record key exists in the map of replaced values.
		 *
		 * @param replacedKey The record key to check for existence.
		 * @return true if the record key exists in the map of replaced values, false otherwise.
		 */
		public boolean containsKey(@Nonnull RecordKey replacedKey) {
			return this.replacedValues.containsKey(replacedKey);
		}

		/**
		 * Returns the estimated memory size occupied by this instance in Bytes.
		 *
		 * @return the estimated memory size occupied by this instance in Bytes
		 */
		public long getTotalSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				MemoryMeasuringConstants.LONG_SIZE +
				this.replacedValues.size() * (RecordKey.MEMORY_SIZE + VersionedValue.MEMORY_SIZE) +
				this.addedKeys.size() * RecordKey.MEMORY_SIZE +
				this.removedKeys.size() * RecordKey.MEMORY_SIZE;
		}

		/**
		 * Returns the count of past memory records of particular type.
		 * @param recordTypeId the record type id
		 * @return the count of past memory records of particular type
		 */
		public int getCountFor(byte recordTypeId) {
			return this.histogram.getOrDefault(recordTypeId, 0);
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
		@Nonnull FileLocationAndWrittenBytes fileLocation
	) {
	}

	/**
	 * Contains information about volatile value and its location in the file. Contains information if the value was
	 * removed in the past or added in the future versions.
	 *
	 * @param versionedValue the versioned value pointer
	 * @param removed        true if the value was removed in the past
	 * @param addedInFuture  true if the value was added in future versions
	 */
	protected record VolatileValueInformation(
		@Nullable VersionedValue versionedValue,
		boolean removed,
		boolean addedInFuture
	) {
	}

	/**
	 * This record allows to propagate information about the current size of non-flushed block to outside world.
	 *
	 * @param recordCount                number of records in the non-flushed block
	 * @param estimatedMemorySizeInBytes estimated memory size of the non-flushed block in Bytes
	 */
	public record NonFlushedBlock(
		int recordCount,
		long estimatedMemorySizeInBytes
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
				if (this.getFree() < OffsetIndex.this.storageOptions.maxOpenedReadHandles()) {
					this.free(readOnlyFileHandle);
				} else {
					readOnlyFileHandle.close();
				}
			}
		}

		@Override
		protected ReadOnlyHandle create() {
			try {
				if (this.readFilesLock.tryLock(OffsetIndex.this.storageOptions.lockTimeoutSeconds(), TimeUnit.SECONDS)) {
					try {
						final ReadOnlyHandle readOnlyFileHandle = OffsetIndex.this.writeHandle.toReadOnlyHandle();
						if (OffsetIndex.this.readOnlyOpenedHandles.size() >= OffsetIndex.this.storageOptions.maxOpenedReadHandles()) {
							throw new PoolExhaustedException(OffsetIndex.this.storageOptions.maxOpenedReadHandles(), readOnlyFileHandle.toString());
						}
						OffsetIndex.this.readOnlyOpenedHandles.add(readOnlyFileHandle);
						return readOnlyFileHandle;
					} finally {
						this.readFilesLock.unlock();
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
