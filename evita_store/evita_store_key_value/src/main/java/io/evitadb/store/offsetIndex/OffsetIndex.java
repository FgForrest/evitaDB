/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadOnlyKeyCompressorView;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.offsetIndex.exception.CorruptedKeyValueRecordException;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.PoolExhaustedException;
import io.evitadb.store.offsetIndex.exception.RecordNotYetWrittenException;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import io.evitadb.store.offsetIndex.map.OffsetLocationChampMap;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.RecordTypeUsage;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.RawRecord;
import io.evitadb.store.offsetIndex.model.VersionedValue;
import io.evitadb.store.offsetIndex.model.WasteAccumulation;
import io.evitadb.store.shared.model.FileLocation;
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
import java.lang.ref.SoftReference;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.*;
import static io.evitadb.utils.Assert.isPremiseValid;
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
 * - stores returned {@link FileLocation} along with key to {@link Roots#latestRoot()}
 *
 * READ:
 * - looks up {@link FileLocation} by the passed key (this is expected to be fast)
 * - uses {@link RandomAccessFile} to seek the position in the file and reads its contents
 * - performance of this operation depends on the OS page cache - so the OS should have enough RAM left for this sake
 *
 * DELETE:
 * - removes record in the {@link Roots#latestRoot()}
 * - information about the remove is also tracked in MemoryFragment (when written to disk) so that when OffsetIndex is
 * reconstructed from fragments the record inserted in previous fragments will be ignored as well
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@ThreadSafe
public class OffsetIndex {
	/**
	 * Initial size of the central {@link Roots#latestRoot()} index.
	 */
	public static final int KEY_HASH_MAP_INITIAL_SIZE = 65_536;
	/**
	 * Initial size of the central {@link Roots#latestHistogram()} index.
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
	 * Size of the memory buffer used for write operations, in bytes.
	 * This buffer size limits the maximum size of individual records that can be written to the offset index.
	 * Sourced from {@link StorageOptions#outputBufferSize()}, typically defaults to 2MB.
	 */
	private final int outputBufferSize;
	/**
	 * Reusable scratch buffer for the {@link #copySnapshotTo} record-copy loop, retained across compactions so each
	 * compaction does not allocate a fresh {@link #outputBufferSize}-byte array. Held through a {@link SoftReference}
	 * so idle collections do not pin the (default 2MB) buffer under memory pressure, and allocated lazily so a
	 * never-compacted collection pays nothing. A single slot is sufficient — and no pool is needed — because every
	 * writer of this instance is serialized by the {@link #writeHandle} lock, so at most one compaction reads it at a
	 * time. See {@link #getCompactionScratchBuffer()}.
	 */
	@Nullable private SoftReference<byte[]> compactionScratchBuffer;
	/**
	 * Maximum number of read handles that can be simultaneously opened to the file.
	 * Read handles are pooled to limit resource usage and prevent file descriptor exhaustion.
	 * Sourced from {@link StorageOptions#maxOpenedReadHandles()}, typically defaults to 20 × CPU cores.
	 */
	private final int maxOpenedReadHandlesOrDefault;
	/**
	 * Timeout in seconds for acquiring locks on file handles.
	 * If a lock cannot be acquired within this timeout, an exception is thrown.
	 * Sourced from {@link StorageOptions#lockTimeoutSeconds()}, typically defaults to 5 seconds.
	 */
	private final int lockTimeoutSeconds;
	/**
	 * Timeout in seconds for waiting on processes to release read handles during cleanup operations.
	 * Used when closing file handles to ensure graceful shutdown.
	 * Sourced from {@link StorageOptions#waitOnCloseSeconds()}, typically defaults to 5 seconds.
	 */
	private final int waitOnCloseSeconds;
	/**
	 * Factory for creating checksums for data integrity verification during read and write operations.
	 * Sourced from {@link StorageOptions#computeCRC32C()}.
	 */
	private final ChecksumFactory checksumFactory;
	/**
	 * Factory for creating compressor and decompressor instances for optional data compression.
	 * Compression is applied only when it reduces data size below the original.
	 * Sourced from {@link StorageOptions#compress()}.
	 */
	private final CompressionFactory compressionFactory;
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
	 * Pool of {@link ReadOnlyHandle} instances which are not thread safe.
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
	 * How many bytes rewrites and removals have stranded in the current data file, and how fast that is growing. Only
	 * ever written by the flush thread inside {@link #promoteNonFlushedValuesToSharedState(long, Collection)}, and
	 * published as one immutable value so a statistics reader cannot pair a counter with a rate from another flush.
	 */
	@Nonnull
	private volatile WasteAccumulation wasteAccumulation = WasteAccumulation.NONE;
	/**
	 * Volatile values contains history of previous writes and removals so that offset index can provide access to
	 * the correct contents based on the catalog version. Volatile values keep track only of the changes that have
	 * chance to be read by the client and try to be purged immediately when there is no chance to read them anymore.
	 * Otherwise their size would grow too large.
	 */
	private final VolatileValues volatileValues;
	/**
	 * Contains flag signalizing that OffsetIndex is open and can be used. Flag is set to false on {@link #close()} operation.
	 * No additional calls are allowed after that.
	 */
	@Getter private boolean operative = true;
	/**
	 * Versioned registry of the record-location index. A single volatile reference publishes the whole
	 * `(currentVersion, versions, locationRoots, histograms)` triple atomically, so lock-free readers in
	 * {@link #get(long, long, Class)} (and its sibling lookups) always observe a coherent snapshot - they can never
	 * pair a freshly appended root with a stale version array. Each retained version owns an immutable, structurally
	 * shared {@link OffsetLocationChampMap} of locations, so keeping many versions is cheap and historical reads resolve to the exact
	 * per-version state via {@link Roots#floorRoot(long)} rather than reconstructing diffs. See {@link Roots}.
	 */
	private volatile Roots roots;
	/**
	 * OffsetIndex descriptor used when creating OffsetIndex instance or created on last {@link #flush(long)} operation.
	 * Contains all information necessary to read/write data in OffsetIndex instance using {@link Kryo}.
	 *
	 * Reassigned only from inside the {@link #writeHandle} critical section, but read without holding anything.
	 * `volatile` here buys visibility of the descriptor's own scalars: {@link #getVersion()} and
	 * {@link #getFileOffsetIndexLocation()} are read from threads other than the flushing one, and an indefinitely
	 * stale version or file location is not acceptable.
	 *
	 * It deliberately does **not** carry the read-Kryo binding any more. That binding used to be resolved through
	 * this field at Kryo-creation time, which is precisely what let a freshly-expired pool build instances against
	 * a superseded compressor; it now lives in the pool's own volatile generation, where the version and the factory
	 * are published together.
	 *
	 * The compressor-facing accessors ({@link #getReadOnlyKeyCompressor()}, {@link #getCompressedKeys()},
	 * {@link #getKeyCompressorSnapshot()}) do not rely on this guarantee either: every post-flush descriptor carries
	 * the *same* `ReadWriteKeyCompressor` instance forward, so even a stale reference resolves to the live compressor.
	 *
	 * `volatile` gives visibility, not atomicity - never read-modify-write this field. Each path publishes it in
	 * exactly one place ({@link #doFlush} and {@link #doSoftFlush()}), both inside the critical section.
	 */
	private volatile OffsetIndexDescriptor fileOffsetDescriptor;
	/**
	 * Lazily cached read-only view over the current
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor} held by
	 * {@link #fileOffsetDescriptor}. The view delegates all read-only methods directly to the live write compressor
	 * while preventing mutation via {@link KeyCompressor#getId(Comparable)}. This field is reset to {@code null}
	 * whenever {@link #fileOffsetDescriptor} is reassigned. That reset is belt-and-braces rather than a correctness
	 * requirement: the write compressor instance is carried forward across every post-flush rebuild, so a view built
	 * against an earlier descriptor still delegates to the live compressor. `volatile` likewise only removes any
	 * doubt about a benign racy cache - the wrapped delegate is final, so an instance published through the race is
	 * always fully constructed.
	 */
	@Nullable private volatile ReadOnlyKeyCompressorView readOnlyKeyCompressorView;
	/**
	 * This field contains the information about last known position that has been synced to the file on disk and can
	 * be safely read.
	 */
	private long lastSyncedPosition;

	/**
	 * Reads particular storage part from the target file path. This method will take `location` as leading pointer
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
		@Nonnull ChecksumFactory checksumCalculatorFactory,
		@Nonnull CompressionFactory compressionFactory,
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
				),
				checksumCalculatorFactory.createChecksum(),
				compressionFactory.createDecompressor().orElse(null)
			)
		) {
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
		int outputBufferSize,
		int maxOpenedReadHandlesOrDefault,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver
	) {
		this.outputBufferSize = outputBufferSize;
		this.maxOpenedReadHandlesOrDefault = maxOpenedReadHandlesOrDefault;
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.checksumFactory = checksumFactory;
		this.compressionFactory = compressionFactory;
		this.fileOffsetDescriptor = fileOffsetDescriptor;
		this.readOnlyKeyCompressorView = null;
		this.recordTypeRegistry = recordTypeRegistry;
		this.volatileValues = new VolatileValues(
			nonFlushedBlockObserver == null ? nonFlushedBlock -> {
			} : nonFlushedBlockObserver,
			historicalRecordObserver == null ? historicalRecord -> {
			} : historicalRecordObserver
		);

		this.readOnlyOpenedHandles = new CopyOnWriteArrayList<>();
		this.readKryoPool = new FileOffsetIndexKryoPool(
			maxOpenedReadHandlesOrDefault,
			this.fileOffsetDescriptor.getReadKryoFactory()
		);
		this.writeKryo = fileOffsetDescriptor.getWriteKryo();
		this.writeHandle = writeHandle;
		this.lastSyncedPosition = writeHandle.getLastWrittenPosition();
		try {
			final Optional<CollectingOffsetIndexBuilder> fileOffsetIndexBuilder;
			if (this.lastSyncedPosition == 0 || fileOffsetDescriptor.fileLocation().equals(FileLocation.EMPTY)) {
				fileOffsetIndexBuilder = Optional.empty();
			} else {
				fileOffsetIndexBuilder = of(
					readFileOffsetIndex(fileOffsetDescriptor.fileLocation())
				);
			}
			this.roots = Roots.initial(
				catalogVersion,
				fileOffsetIndexBuilder
					.map(CollectingOffsetIndexBuilder::getBuiltIndex)
					.map(OffsetLocationChampMap::from)
					.orElseGet(OffsetLocationChampMap::empty),
				fileOffsetIndexBuilder
					.map(CollectingOffsetIndexBuilder::getHistogram)
					.map(Map::copyOf)
					.orElseGet(Map::of),
				System.currentTimeMillis()
			);
			fileOffsetIndexBuilder
				.ifPresent(it -> {
					this.totalSizeBytes.set(it.getTotalSizeBytes());
					this.maxRecordSizeBytes.set(it.getMaxSizeBytes());
				});
			this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
				@Override
				protected byte[] create() {
					return new byte[outputBufferSize];
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
		int outputBufferSize,
		int maxOpenedReadHandlesOrDefault,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver,
		@Nonnull BiFunction<OffsetIndexBuilder, ObservableInput<?>, ? extends OffsetIndexDescriptor> offsetIndexDescriptorFactory
	) {
		this.outputBufferSize = outputBufferSize;
		this.maxOpenedReadHandlesOrDefault = maxOpenedReadHandlesOrDefault;
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.checksumFactory = checksumFactory;
		this.compressionFactory = compressionFactory;
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
				),
				checksumFactory.createChecksum(),
				compressionFactory.createDecompressor().orElse(null)
			)
		) {
			final CollectingOffsetIndexBuilder fileOffsetIndexBuilder = new CollectingOffsetIndexBuilder();
			deserialize(
				input,
				fileLocation,
				fileOffsetIndexBuilder
			);
			this.roots = Roots.initial(
				catalogVersion,
				OffsetLocationChampMap.from(fileOffsetIndexBuilder.getBuiltIndex()),
				Map.copyOf(fileOffsetIndexBuilder.getHistogram()),
				System.currentTimeMillis()
			);
			this.totalSizeBytes.set(fileOffsetIndexBuilder.getTotalSizeBytes());
			this.maxRecordSizeBytes.set(fileOffsetIndexBuilder.getMaxSizeBytes());
			this.fileOffsetDescriptor = offsetIndexDescriptorFactory.apply(fileOffsetIndexBuilder, input);
			this.readOnlyKeyCompressorView = null;
			this.readKryoPool = new FileOffsetIndexKryoPool(
				maxOpenedReadHandlesOrDefault,
				this.fileOffsetDescriptor.getReadKryoFactory()
			);
			this.writeKryo = this.fileOffsetDescriptor.getWriteKryo();
			this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
				@Override
				protected byte[] create() {
					return new byte[outputBufferSize];
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
		int outputBufferSize,
		int maxOpenedReadHandlesOrDefault,
		int lockTimeoutSeconds,
		int waitOnCloseSeconds,
		@Nonnull ChecksumFactory checksumCalculatorFactory,
		@Nonnull CompressionFactory compressionFactory,
		@Nonnull OffsetIndexRecordTypeRegistry recordTypeRegistry,
		@Nonnull WriteOnlyHandle writeHandle,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historicalRecordObserver,
		@Nonnull OffsetIndex previousOffsetIndex,
		@Nonnull OffsetIndexDescriptor fileOffsetIndexDescriptor
	) {
		this.outputBufferSize = outputBufferSize;
		this.maxOpenedReadHandlesOrDefault = maxOpenedReadHandlesOrDefault;
		this.lockTimeoutSeconds = lockTimeoutSeconds;
		this.waitOnCloseSeconds = waitOnCloseSeconds;
		this.checksumFactory = checksumCalculatorFactory;
		this.compressionFactory = compressionFactory;
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

		this.roots = Roots.initial(
			catalogVersion,
			previousOffsetIndex.roots.latestRoot(),
			previousOffsetIndex.roots.latestHistogram(),
			System.currentTimeMillis()
		);
		this.totalSizeBytes.set(previousOffsetIndex.totalSizeBytes.get());
		this.maxRecordSizeBytes.set(previousOffsetIndex.getMaxRecordSizeBytes());
		// this constructor builds the compacted successor of `previousOffsetIndex` - the stranded bytes did not
		// survive the rewrite, but the write load that produced them did
		this.wasteAccumulation = previousOffsetIndex.wasteAccumulation.carriedOverToCompactedFile();
		this.fileOffsetDescriptor = fileOffsetIndexDescriptor;
		this.readOnlyKeyCompressorView = null;
		this.readKryoPool = new FileOffsetIndexKryoPool(
			maxOpenedReadHandlesOrDefault,
			this.fileOffsetDescriptor.getReadKryoFactory()
		);
		this.writeKryo = this.fileOffsetDescriptor.getWriteKryo();
		this.decompressionPool = new Pool<>(true, false, DECOMPRESSION_ARRAY_POOL_MAXIMUM_CAPACITY) {
			@Override
			protected byte[] create() {
				return new byte[outputBufferSize];
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
	 * Returns a read-only view over the live write key compressor. The view is lazily cached and invalidated
	 * whenever {@link #fileOffsetDescriptor} is reassigned.
	 */
	@Nonnull
	public KeyCompressor getReadOnlyKeyCompressor() {
		ReadOnlyKeyCompressorView result = this.readOnlyKeyCompressorView;
		if (result == null) {
			result = new ReadOnlyKeyCompressorView(this.fileOffsetDescriptor.getWriteKeyCompressor());
			this.readOnlyKeyCompressorView = result;
		}
		return result;
	}

	/**
	 * Returns the **live** index of compressed keys.
	 *
	 * The compressor does **not** synchronize this access - see the class JavaDoc of
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor} for which half
	 * of its API is self-synchronized and which half is the caller's responsibility. Every mint originates inside an
	 * `executeWithWriteAccess` session opened by this class, so a live view is coherent exactly as long as the caller
	 * consumes it outside such a session or from within one. Callers that retain the map past their own consumption
	 * window (i.e. iterate it later or hand it to a long-lived data structure that may iterate after concurrent
	 * writers have advanced) must use {@link #getKeyCompressorSnapshot()} instead, which returns the compressor's
	 * memoized immutable copy under its read lock.
	 *
	 * Safe live-view consumers in this codebase:
	 *
	 * - `.size()` / peek-only reads;
	 * - `storeHeader` building a `CatalogHeader` whose subsequent Kryo serialization runs inside the
	 * compressor's write session and whose serializer (including value-key sub-serializers for `AttributeKey`,
	 * `AssociatedDataKey`, `CompressiblePriceKey`, `ReferenceNameKey`) never calls back into
	 * {@link KeyCompressor#getId(Comparable)} mid-iteration.
	 *
	 * @return live id → key index of the current compressed keys
	 */
	@Nonnull
	public Map<Integer, Object> getCompressedKeys() {
		return this.fileOffsetDescriptor.getWriteKeyCompressor().getKeys();
	}

	/**
	 * Returns an atomic snapshot of the trunk's key compressor — both the id → key map and the highest assigned id,
	 * coordinated inside the compressor so the two fields are coherent. Callers seeding a new
	 * {@link ReadWriteKeyCompressor} from the snapshot rely on the invariant `max(keys.keySet()) <= peakId`; reading
	 * the fields without coordination could let a concurrent writer advance the peak between the two reads, leaving
	 * the new compressor with a sequence that collides with already-seeded ids.
	 *
	 * The id → key map returned here is the compressor's memoized **immutable** snapshot. This is the only call
	 * path whose downstream iteration runs outside the writer's session (e.g.
	 * `TransactionalStoragePartPersistenceService` seeding a new compressor while trunk-side writers keep mutating).
	 * Quiescent callers pay only a volatile read; a fresh `Map.copyOf` happens at most once per inter-snapshot
	 * mutation burst.
	 */
	@Nonnull
	public KeyCompressorSnapshot getKeyCompressorSnapshot() {
		return this.fileOffsetDescriptor.getWriteKeyCompressor().getAtomicSnapshot();
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE entries in the OffsetIndex. The entries are wrapped via
	 * {@link Collections#unmodifiableMap(Map)} so that callers cannot mutate the published - and by the
	 * {@link Roots} contract immutable - locations map through {@link Entry#setValue(Object)}.
	 */
	public Collection<Entry<RecordKey, FileLocation>> getEntries() {
		assertOperative();
		return Collections.unmodifiableMap(this.roots.latestRoot()).entrySet();
	}

	/**
	 * Returns an unmodifiable collection of all ACTIVE entries as of `catalogVersion` - the exact per-version
	 * snapshot resolved through the versioned-root registry. Used by the snapshot/compaction path to copy the living
	 * data set of a specific version without per-key historical reconstruction.
	 *
	 * @param catalogVersion the catalog version whose snapshot to return
	 * @return the active entries as of `catalogVersion`
	 */
	public Collection<Entry<RecordKey, FileLocation>> getEntries(long catalogVersion) {
		assertOperative();
		return Collections.unmodifiableMap(this.roots.floorRoot(catalogVersion)).entrySet();
	}

	/**
	 * Returns the count of ACTIVE entries as of `catalogVersion`.
	 *
	 * The result is the live count for that version: the size of the per-version root plus the net delta of any
	 * in-flight (not-yet-flushed) versions visible at `catalogVersion`.
	 *
	 * @param catalogVersion the catalog version to resolve the count against
	 * @return the number of active entries visible at `catalogVersion`
	 */
	public int count(long catalogVersion) {
		assertOperative();
		// the per-version root holds the exact live count as of catalogVersion; add only the net delta of any
		// not-yet-flushed (in-flight) versions visible at catalogVersion
		return this.roots.floorRoot(catalogVersion).size() + this.volatileValues.countDifference(catalogVersion);
	}

	/**
	 * Returns the count of ACTIVE entries of the given type as of `catalogVersion`.
	 *
	 * The per-type count is resolved against the per-version histogram plus the net delta of that type across any
	 * in-flight (not-yet-flushed) versions visible at `catalogVersion`.
	 *
	 * @param catalogVersion the catalog version to resolve the count against
	 * @param recordType     the {@link StoragePart} subtype whose entries to count
	 * @return the number of active entries of `recordType` visible at `catalogVersion`
	 */
	public int count(long catalogVersion, @Nonnull Class<? extends StoragePart> recordType) {
		assertOperative();
		final byte recordTypeId = this.recordTypeRegistry.idFor(recordType);
		// the per-version histogram holds the exact per-type count as of catalogVersion; add only the net delta of
		// any not-yet-flushed (in-flight) versions visible at catalogVersion
		return this.roots.floorHistogram(catalogVersion)
			.getOrDefault(recordTypeId, RecordTypeUsage.EMPTY)
			.count() + this.volatileValues.countDifference(catalogVersion, recordTypeId);
	}

	/**
	 * Returns the deserialized value assigned to `primaryKey` as of `catalogVersion`, or {@code null} when no
	 * active record exists for that key at that version.
	 *
	 * The value is resolved against the per-version root, so the returned entry already conforms to
	 * `catalogVersion` — there is no per-key historical reconstruction and no separate generation filter.
	 *
	 * Beware method may not return previously written record via {@link #put(long, StoragePart)} until method
	 * {@link #flush(long)} is called. In this situation {@link RecordNotYetWrittenException} is thrown.
	 *
	 * @param catalogVersion the catalog version to resolve the value as of
	 * @param primaryKey     the primary key of the record to read
	 * @param recordType     the {@link StoragePart} subtype to deserialize the record as
	 * @param <T>            the record type
	 * @return the active value for `primaryKey` at `catalogVersion`, or {@code null} if none exists
	 * @throws RecordNotYetWrittenException if the record was written but not yet flushed and cannot be read back
	 */
	@Nullable
	public <T extends StoragePart> T get(
		long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();
		final RecordKey key = new RecordKey(
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef =
			this.volatileValues.getNonFlushedValueIfVersionMatches(catalogVersion, key);
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
					return (T) get(
						nonFlushedValue.fileLocation(),
						this.recordTypeRegistry.typeFor(nonFlushedValue.recordType())
					);
				} catch (KryoException exception) {
					throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
				}
			}
		}

		// resolve against the per-version root: floorRoot returns the location map as of the greatest retained
		// version not exceeding catalogVersion, so its entries already conform to that version (generationId <=
		// catalogVersion is implied) - no separate historical reconstruction or generation filter is needed
		return ofNullable(this.roots.floorRoot(catalogVersion).get(key))
			.map(it -> doGet(recordType, primaryKey, it))
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
	 * Returns the raw (unparsed) payload assigned to `primaryKey` as of `catalogVersion`, or {@code null} when no
	 * active record exists for that key at that version.
	 *
	 * Like {@link #get(long, long, Class)}, the value is resolved against the per-version root, so the returned
	 * entry already conforms to `catalogVersion` — no per-key historical reconstruction and no generation filter.
	 *
	 * Beware method may not return previously written record via {@link #put(long, StoragePart)} until method
	 * {@link #flush(long)} is called. In this situation {@link RecordNotYetWrittenException} is thrown.
	 *
	 * @param catalogVersion the catalog version to resolve the value as of
	 * @param primaryKey     the primary key of the record to read
	 * @param recordType     the {@link StoragePart} subtype the record belongs to
	 * @param <T>            the record type
	 * @return the raw payload bytes for `primaryKey` at `catalogVersion`, or {@code null} if none exists
	 * @throws RecordNotYetWrittenException if the record was written but not yet flushed and cannot be read back
	 */
	@Nullable
	public <T extends StoragePart> byte[] getBinary(
		long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) throws RecordNotYetWrittenException {
		assertOperative();

		final RecordKey key = new RecordKey(
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(
			catalogVersion, key);
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
					return getBinary(
						nonFlushedValue.fileLocation(), this.recordTypeRegistry.typeFor(nonFlushedValue.recordType()));
				} catch (KryoException exception) {
					throw new RecordNotYetWrittenException(primaryKey, recordType, exception);
				}
			}
		}

		// resolve against the per-version root (see get): floorRoot already yields the location map conforming to
		// catalogVersion, so no historical reconstruction or generation filter is required
		return ofNullable(this.roots.floorRoot(catalogVersion).get(key))
			.map(it -> doGetBinary(recordType, primaryKey, it))
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
	public <T extends StoragePart> boolean contains(
		long catalogVersion, long primaryKey, @Nonnull Class<T> recordType) {
		assertOperative();
		final RecordKey key = new RecordKey(
			this.recordTypeRegistry.idFor(recordType),
			primaryKey
		);

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(
			catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			return !nonFlushedValue.removed();
		}

		// resolve presence against the per-version root: floorRoot yields the location map as of catalogVersion, so
		// containsKey is consistent with get/getBinary at every version (a key absent at the requested version is
		// simply absent from that version's root)
		return this.roots.floorRoot(catalogVersion).containsKey(key);
	}

	/**
	 * Stores or overwrites record with passed primary key in OffsetIndex. Values of different types are distinguished by
	 * the OffsetIndex so that two different types of objects with same primary keys don't overwrite each other.
	 *
	 * @param catalogVersion will be propagated to {@link StorageRecord#generationId()}
	 * @param value          value to be stored
	 */
	public <T extends StoragePart> long put(long catalogVersion, @Nonnull T value) {
		// the compressor's write session covers both the explicit `computeUniquePartIdAndSet` call (which may
		// invoke `keyCompressor.getId(...)`) and the Kryo serialization inside `doPut(...)` (which also drives
		// key allocation through the write compressor); without it, a concurrent snapshot reader would see the
		// underlying HashMap mid-mutation and throw ConcurrentModificationException. `getId(...)` calls inside
		// the session are reentrant on the write lock (~5 ns per call) instead of paying a full acquire/release
		final ReadWriteKeyCompressor compressor = this.fileOffsetDescriptor.getWriteKeyCompressor();
		return compressor.executeWithWriteAccess(
			() ->
				this.writeHandle.checkAndExecute(
					"Storing record",
					this::assertOperative,
					exclusiveWriteAccess -> {
						final long partId = ofNullable(value.getStoragePartPK())
							.orElseGet(() -> value.computeUniquePartIdAndSet(compressor));
						doPut(
							catalogVersion,
							partId,
							value,
							exclusiveWriteAccess
						);
						return partId;
					}
				)
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
							),
							this.checksumFactory.createChecksum()
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
		// `doFlush` publishes the descriptor to `fileOffsetDescriptor` itself, from inside the write-handle critical
		// section, because the read Kryo pool has to be expired against a binding that is already live
		doFlush(catalogVersion, false);
		// flush runs under the single-writer model: all writes (including the promotion inside doFlush) are
		// serialized by the writeHandle ReentrantLock, so no other thread mutates the roots registry concurrently here.
		// When there were non-flushed values, doFlush already republished the shared state (version + locations)
		// atomically; when there was nothing to promote, advance the conforming version while keeping the current
		// locations. The guard is strictly monotonic (`<`, not `!=`) so the key catalog version can only move
		// forward - it never regresses even if this assumption were ever violated.
		final Roots currentState = this.roots;
		if (currentState.currentVersion() < catalogVersion) {
			this.roots = currentState.withCurrentVersion(catalogVersion);
		}
		this.readOnlyKeyCompressorView = null;
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
	 * Returns the scratch buffer used by the {@link #copySnapshotTo} record-copy loop, lazily (re)allocating it if it
	 * has never been created or was reclaimed by the GC. The buffer is sized to {@link #outputBufferSize} (the bound on
	 * a single record fragment) and reused across compactions. Must be called only from within the {@link #writeHandle}
	 * critical section, which serializes every writer of this instance — so the single-slot, no-synchronization design
	 * is safe.
	 *
	 * @return a reusable {@link #outputBufferSize}-byte scratch buffer for the raw record copy
	 */
	@Nonnull
	byte[] getCompactionScratchBuffer() {
		final SoftReference<byte[]> ref = this.compactionScratchBuffer;
		byte[] buffer = ref == null ? null : ref.get();
		if (buffer == null) {
			buffer = new byte[this.outputBufferSize];
			this.compactionScratchBuffer = new SoftReference<>(buffer);
		}
		return buffer;
	}

	/**
	 * Copies entire living data set to the target output stream. The output stream is not closed in
	 * the method, the caller is responsible for closing the stream.
	 *
	 * The living data set is resolved as-of `catalogVersion`: the per-version snapshot exposed by
	 * {@link #getEntries(long)} is copied verbatim, with no per-key historical reconstruction.
	 *
	 * @param outputStream        target output stream to write the copy to
	 * @param progressConsumer    consumer that will be called with the progress of the copy
	 * @param catalogVersion      version resolving which entries are copied; also propagated to
	 *                            {@link StorageRecord#generationId()} on every copied record
	 * @param updatedStorageParts records written verbatim into the copy instead of being copied from
	 *                            the source file, keyed by record key; they override the as-of-version
	 *                            snapshot for the keys they carry
	 * @return result containing the file location and the file descriptor actual when the copy was
	 * made
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
		// the compressor's write session covers the explicit `computeUniquePartIdAndSet` calls below and the
		// Kryo serialization inside `serializeValue(...)` — both can mutate the write compressor; without it
		// a concurrent snapshot reader would see the underlying HashMap mid-mutation and throw CME
		final ReadWriteKeyCompressor compressor = this.fileOffsetDescriptor.getWriteKeyCompressor();
		return compressor.executeWithWriteAccess(
			() ->
				// copy the active parts to a new file
				this.readOnlyHandlePool.borrowAndExecute(
					readOnlyFileHandle -> readOnlyFileHandle.execute(
						// by requesting write-handle we enforce no other thread can write to the source file while we are copying
						inputStream -> this.writeHandle.checkAndExecute(
							"Writing mem table",
							this::assertOperative,
							output -> this.readKryoPool.borrowAndExecute(
								kryo -> {
									Assert.isTrue(
										inputStream.getInputStream() instanceof RandomAccessFileInputStream,
										"Input stream must be RandomAccessFileInputStream!"
									);
									@SuppressWarnings("unchecked") final ObservableInput<RandomAccessFileInputStream> randomAccessFileInputStream =
										(ObservableInput<RandomAccessFileInputStream>) inputStream;
									final Map<RecordKey, byte[]> overriddenEntries;
									if (updatedStorageParts != null && updatedStorageParts.length > 0) {
										overriddenEntries = CollectionUtils.createHashMap(updatedStorageParts.length);
										final ByteArrayOutputStream baos = new ByteArrayOutputStream(this.outputBufferSize);
										final ObservableOutput<ByteArrayOutputStream> observableOutput = new ObservableOutput<>(
											baos, this.outputBufferSize, 0,
											this.checksumFactory.createChecksum(),
											this.compressionFactory.createCompressor().orElse(null)
										);
										for (StoragePart value : updatedStorageParts) {
											final RecordKey recordKey = new RecordKey(
												this.recordTypeRegistry.idFor(value.getClass()),
												ofNullable(value.getStoragePartPK())
													.orElseGet(() -> value.computeUniquePartIdAndSet(compressor))
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
										progressConsumer,
										this.checksumFactory,
										this.compressionFactory,
										this.outputBufferSize
									);
									return new OffsetIndexDescriptor(
										this.fileOffsetDescriptor.version() + 1,
										locationAndWrittenBytes.fileLocation(),
										compressor.getKeys(),
										this.fileOffsetDescriptor.getKryoFactory(),
										1,
										locationAndWrittenBytes.writtenBytes()
									);
								}
							)
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
				// at last flush OffsetIndex and close write handle - doFlush publishes the resulting descriptor to
				// `fileOffsetDescriptor` itself (see flush), so there is nothing to assign back here
				doFlush(
					// if there are any non-flushed values, use their version as the last version
					this.volatileValues.getLastNonFlushedCatalogVersionIfExists()
						.orElse(this.roots.currentVersion()),
					true
				);
				this.readOnlyKeyCompressorView = null;
				return this.fileOffsetDescriptor.fileLocation();
			} else {
				throw new GenericEvitaInternalError("OffsetIndex is already being closed!");
			}
		} finally {
			this.shutdownDownProcedureActive.compareAndExchange(true, false);
		}
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
	 * Promotion time of the oldest retained *historical* version — a side-channel observability metric for how far
	 * back point-in-time restore can currently reach.
	 *
	 * Returns empty when only the current version is retained (no history is being kept for past readers). This
	 * value is telemetry only: it does NOT participate in version resolution — reads and counts are resolved
	 * purely by catalog version, never by timestamp.
	 *
	 * @return the oldest retained historical version's promotion timestamp, or empty when no history is retained
	 */
	@Nonnull
	public Optional<OffsetDateTime> getOldestRecordKeptTimestamp() {
		final OptionalLong oldest = this.roots.oldestHistoricalTimestamp();
		return oldest.isPresent() ?
			of(OffsetDateTime.ofInstant(Instant.ofEpochMilli(oldest.getAsLong()), ZoneId.systemDefault())) :
			empty();
	}

	/**
	 * Returns the histogram of particular record types in this index - how many records of each type are held and how
	 * many bytes they occupy, keyed by the record type's simple class name.
	 *
	 * The histogram describes the **flushed** state only: it is the snapshot published by the last promotion, so
	 * records written but not yet flushed are not part of it. That is deliberate - the question this breakdown answers
	 * is where the bytes on disk went, and unflushed records have not reached the disk. It is also why the per-type
	 * count here can lag {@link #count(long, Class)}, which does add the in-flight delta.
	 *
	 * The summed {@link RecordTypeUsage#totalBytes()} of every entry is the **record payload** the index holds - the
	 * same accumulator {@link #getTotalSizeBytes()} builds on, before that method adds the offset-index entry
	 * overhead of `MEM_TABLE_RECORD_SIZE` per live record. So the exact relation is
	 * `Σ totalBytes + Σ count * MEM_TABLE_RECORD_SIZE == getTotalSizeBytes()`; the per-type numbers deliberately
	 * carry no share of the index's own bookkeeping, which belongs to no record type.
	 *
	 * @return histogram of particular record types in this index
	 */
	@Nonnull
	public Map<String, RecordTypeUsage> getHistogram() {
		final Map<Byte, RecordTypeUsage> latestHistogram = this.roots.latestHistogram();
		final Map<String, RecordTypeUsage> result = CollectionUtils.createHashMap(latestHistogram.size());
		for (final Entry<Byte, RecordTypeUsage> entry : latestHistogram.entrySet()) {
			result.put(this.recordTypeRegistry.typeFor(entry.getKey()).getSimpleName(), entry.getValue());
		}
		return result;
	}

	/**
	 * Returns the total size of records held in this OffsetIndex. This number reflect the gross size of all ACTIVE
	 * records except the OffsetIndex index. The removals and dead data are not reflected by this property.
	 *
	 * @return the total size
	 */
	public long getTotalSizeBytes() {
		return this.totalSizeBytes.get() + (long) this.roots.latestRoot().size() * (long) MEM_TABLE_RECORD_SIZE;
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
	 * Returns how many records have been written to this index but not yet flushed to its file, and how many bytes
	 * they occupy. This is the same pair that is pushed to the non-flushed block observer on every write; reading it
	 * here is what lets a statistics call ask for it instead of having to have been subscribed all along.
	 *
	 * The pair is a snapshot of a value that a concurrent write may already have moved on from - which is inherent to
	 * asking "what is in flight right now" - but it is never internally inconsistent: the count and the size are
	 * published together through a single volatile write.
	 *
	 * @return the records written but not yet flushed
	 */
	@Nonnull
	public NonFlushedBlock getNonFlushedBlock() {
		return this.volatileValues.getNonFlushedBlock();
	}

	/**
	 * Returns how many bytes rewrites and removals have stranded in the current data file and how fast that is
	 * growing - the input a compaction forecast extrapolates from.
	 *
	 * Scoped to the file this index currently writes to, not to the index's whole history: a compaction leaves the
	 * stranded bytes behind in the file it replaced. See {@link WasteAccumulation} for what the rate does and does
	 * not say.
	 *
	 * @return the waste accumulated by the current data file
	 */
	@Nonnull
	public WasteAccumulation getWasteAccumulation() {
		return this.wasteAccumulation;
	}

	/**
	 * Returns the current length of the data file this index writes to.
	 *
	 * This is the very number the compaction trigger compares against `fileSizeCompactionThresholdBytes` when it runs
	 * at flush time, which is why the forecast reads it from here rather than measuring the file separately - a
	 * prediction made from a different notion of "file size" than the trigger uses would drift from it.
	 *
	 * @return length of the data file in bytes
	 */
	public long getFileSize() {
		return this.writeHandle.getLastWrittenPosition();
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
	 * Creates new file that contains only records directly reachable from {@link Roots#latestRoot()} index. While
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
			return copySnapshotTo(fos, null, this.roots.currentVersion());
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
		return Collections.unmodifiableCollection(this.roots.latestRoot().keySet());
	}

	/**
	 * Returns unmodifiable collection of all ACTIVE file locations in the OffsetIndex.
	 */
	Collection<FileLocation> getFileLocations() {
		assertOperative();
		return Collections.unmodifiableCollection(this.roots.latestRoot().values());
	}

	/**
	 * Just for testing purposes - verifies whether the OffsetIndex contents equals the other OffsetIndex contents.
	 */
	boolean fileOffsetIndexEquals(@Nonnull OffsetIndex o) {
		if (this == o) return true;
		return this.roots.latestRoot().equals(o.roots.latestRoot());
	}

	/**
	 * Clears read-only file handles that have been opened but not properly released.
	 *
	 * This method attempts to close all handles in the `readOnlyOpenedHandles` collection that were unable to be
	 * released within a specific timeout defined by `storageOptions.waitOnCloseSeconds()`. It performs a cleanup
	 * of file handles to ensure that resources are released and avoid resource leakage.
	 */
	private void clearReadOnlyOpenedHandles() {
		final long start = System.currentTimeMillis();
		// Grace-drain loop: wait up to `waitOnCloseSeconds` for clients (most importantly an in-flight snapshot
		// copy / compaction that has borrowed a read handle) to return their handles, closing each one the moment
		// it frees up in the pool. The comparison MUST be `<` (still inside the grace window): with the former `>`
		// it was false on entry (elapsed is ~0), so this loop never ran and close() fell straight through to the
		// force-close below while the handle was still being read - surfacing as `Stream Closed` mid-compaction on
		// the reading thread.
		while (!this.readOnlyOpenedHandles.isEmpty() && System.currentTimeMillis() - start < this.waitOnCloseSeconds * 1000L) {
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
			} else {
				// every handle is currently borrowed - yield rather than burn a core until one is returned to
				// the pool
				Thread.onSpinWait();
			}
		}
		// these handles were not released by the clients within the timeout
		for (ReadOnlyHandle readOnlyOpenedHandle : this.readOnlyOpenedHandles) {
			readOnlyOpenedHandle.close();
		}
		this.readOnlyOpenedHandles.clear();
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

	/**
	 * Calculates estimated total active size. In case of compression enabled this size might exceed the actual size
	 * of the file on the disk, since it calculates potential size of the all the records in the index (compressed)
	 * and the index itself (uncompressed - since it hasn't been compressed yet).
	 *
	 * Note: we could make this more precise if we'd store the size of the index in the {@link OffsetIndexDescriptor}
	 * and estimate the uncompressed size only for the volatile values. But we don't necessarily need that precision now.
	 *
	 * Because the number is an estimate that can exceed the file it describes, any caller that reports it as a share
	 * of - or a subset of - the file's bytes must clamp it to the file length. {@link #getActiveRecordShare(long)}
	 * is the built-in way to ask the same question as a ratio.
	 *
	 * @return The total active size.
	 */
	public long getTotalActiveSize() {
		return this.totalSizeBytes.get() +
			countFileOffsetTableSize(this.roots.latestRoot().size(), this.outputBufferSize);
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
	 *
	 * The outcome is **published to {@link #fileOffsetDescriptor}, not returned**. It has to be: the read Kryo pool is
	 * expired in the same breath and must be expired against a binding that is already live (see the comment at the
	 * publication site), which is only possible from inside the critical section. {@link #doSoftFlush()} publishes
	 * from inside its own for the same reason. When there is nothing to promote the field is left alone, because the
	 * descriptor it already holds remains the current one.
	 *
	 * @param catalogVersion version stamped onto the promoted records
	 * @param close          whether the write handle should be closed once the data is on disk
	 */
	private void doFlush(
		long catalogVersion,
		boolean close
	) {
		final OffsetIndexDescriptor currentDescriptor = this.fileOffsetDescriptor;
		// if there are any non-flushed values, we need to flush them to the disk (of if the offset index was not yet created)
		if (this.volatileValues.hasValuesToFlush() || currentDescriptor.fileLocation() == FileLocation.EMPTY) {
			this.writeHandle.checkAndExecuteAndSync(
				"Writing mem table",
				this::assertOperative,
				outputStream -> {
					// serialize all non-flushed values to the output stream
					final Collection<NonFlushedValueSet> nonFlushedEntries = this.volatileValues.getNonFlushedEntriesToPromote(
						catalogVersion);
					final List<VersionedValue> valuesToPromote = nonFlushedEntries
						.stream()
						.flatMap(it -> it.getAllValues().stream())
						.toList();
					return new NonFlushedValuesWithFileLocation(
						nonFlushedEntries,
						serialize(
							outputStream,
							catalogVersion,
							valuesToPromote,
							this.getFileOffsetIndexLocation(),
							this.outputBufferSize
						)
					);
				},
				(outputStream, nonFlushedValuesWithFileLocation) -> {
					// update last synced position, since in post action we are already after sync
					this.lastSyncedPosition = this.writeHandle.getLastWrittenPosition();
					// now empty all NonFlushedValueSet and move them to current state
					promoteNonFlushedValuesToSharedState(
						catalogVersion,
						nonFlushedValuesWithFileLocation.nonFlushedValueSets()
					);
					// create new OffsetIndexDescriptor with updated version
					final long fileSize = this.writeHandle.getLastWrittenPosition();
					final OffsetIndexDescriptor newDescriptor = new OffsetIndexDescriptor(
						nonFlushedValuesWithFileLocation.fileLocation(),
						currentDescriptor,
						getActiveRecordShare(this.writeHandle.getLastWrittenPosition()),
						fileSize
					);
					// publish the new descriptor BEFORE expiring the read kryo pool, exactly like doSoftFlush does.
					// Expiring first empties the pool while this field still points at the superseded descriptor, so
					// a reader borrowing in that window builds a VersionedKryo bound to the OLD read compressor yet
					// tagged with the FRESH pool version - which `free` then accepts back into the pool, where it
					// survives every subsequent flush and fails every read of a record written with a newly minted
					// compressed key.
					// Note this closes the *permanent* form of the problem, not every last instant of it: promotion
					// above already made the new records reachable through `roots`, so a reader that borrows an
					// already-pooled instance between that promotion and this publication can still fail once. That
					// residual window is transient - the instance it used is rejected by `free` and the next borrow
					// builds a current one - whereas the poisoned-pool form was permanent. Closing it entirely would
					// mean publishing ahead of the promotion, which is not possible while the descriptor's
					// activeRecordShare is derived from post-promotion totals.
					this.fileOffsetDescriptor = newDescriptor;
					this.readOnlyKeyCompressorView = null;
					// propagate changes in KeyCompressor to the read kryo pool
					if (currentDescriptor.resetDirty()) {
						this.readKryoPool.expireAllPreviouslyCreated(newDescriptor.getReadKryoFactory());
					}
					// the post-execution hook has to yield something - the descriptor is already published above
					return newDescriptor;
				}
			);
		}

		if (close) {
			IOUtils.closeQuietly(this.writeHandle::close);
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
					final OffsetIndexDescriptor currentDescriptor = this.fileOffsetDescriptor;
					if (currentDescriptor.resetDirty()) {
						final OffsetIndexDescriptor newDescriptor = new OffsetIndexDescriptor(
							new FileLocationAndWrittenBytes(
								currentDescriptor.fileLocation(),
								0
							),
							currentDescriptor,
							getActiveRecordShare(this.lastSyncedPosition),
							this.lastSyncedPosition
						);
						// publish first, expire second - see the identically-shaped comment in doFlush for what
						// happens when the read Kryo pool is emptied while this field still names the old descriptor
						this.fileOffsetDescriptor = newDescriptor;
						this.readOnlyKeyCompressorView = null;
						this.readKryoPool.expireAllPreviouslyCreated(newDescriptor.getReadKryoFactory());
					}
					return null;
				}
			);

		}
	}

	/**
	 * Method moves all `nonFlushedValues` into the published per-version location map and purges them from the main
	 * memory. The new location map and the `catalogVersion` it conforms to are published together through a single
	 * {@link #roots} volatile write, so lock-free readers never observe a torn `(version, locations)` pair.
	 *
	 * @param catalogVersion      the catalog version stamped onto the freshly published {@link Roots#latestRoot()}
	 *                            location map; this is the conforming version that readers compare against in
	 *                            {@link #get(long, long, Class)}
	 * @param nonFlushedValueSets the non-flushed value sets to promote to the shared state
	 */
	private void promoteNonFlushedValuesToSharedState(
		long catalogVersion, @Nonnull Collection<NonFlushedValueSet> nonFlushedValueSets) {
		final Roots currentRoots = this.roots;
		// promote changes by path-copying the persistent map per committed version: each version derives a new root
		// that structurally shares every untouched sub-tree with its predecessor, so the whole promotion costs
		// O(M·log32 N) and allocates only the touched path (no O(N) full-map copy, no humongous backing array). One
		// root + histogram snapshot is retained per promoted version, so a reader pinned to any of them resolves the
		// exact per-version state through Roots.floorRoot (replacing the former overwritten-value reconstruction).
		OffsetLocationChampMap root = currentRoots.latestRoot();
		Map<Byte, RecordTypeUsage> histogram = currentRoots.latestHistogram();

		final int batchSize = nonFlushedValueSets.size();
		final long[] addVersions = new long[batchSize];
		final OffsetLocationChampMap[] addRoots = new OffsetLocationChampMap[batchSize];
		@SuppressWarnings("unchecked") final Map<Byte, RecordTypeUsage>[] addHistograms = new Map[batchSize];
		final long[] addTimestamps = new long[batchSize];
		// all versions in one flush become durable together, so they share a single promotion timestamp
		final long promotedAt = System.currentTimeMillis();

		long workingMaxRecordSize = this.maxRecordSizeBytes.get();
		long recordLengthDelta = 0;
		// bytes this flush strands in the data file. Deliberately *not* `recordLengthDelta`: a rewrite that shrinks
		// a record has a negative length delta while still leaving the whole superseded record behind as waste
		long wasteBytesGenerated = 0;
		int batchIndex = 0;

		// the sets arrive in ascending catalog-version order (see getNonFlushedEntriesToPromote)
		for (NonFlushedValueSet nonFlushedValueSet : nonFlushedValueSets) {
			final Map<Byte, RecordTypeUsage> histogramDiff = CollectionUtils.createHashMap(histogram.size());
			for (Entry<RecordKey, VersionedValue> entry : nonFlushedValueSet.entrySet()) {
				final RecordKey recordKey = entry.getKey();
				final VersionedValue nonFlushedValue = entry.getValue();

				final int count;
				// every branch feeds the per-type byte delta with exactly what it feeds `recordLengthDelta`,
				// the count-neutral update included - that is what keeps the per-type breakdown reconciling with
				// `totalSizeBytes` by construction instead of by coincidence
				final long byteDelta;
				if (nonFlushedValue.removed()) {
					// read the dropped record's length before path-copying it away (primitive fast path,
					// no FileLocation materialization)
					final int removedLength = root.findRecordLength(recordKey);
					// location might not exist when value was created and immediately removed
					if (removedLength != OffsetLocationChampMap.RECORD_LENGTH_ABSENT) {
						root = root.removed(recordKey);
						count = -1;
						byteDelta = -removedLength;
						recordLengthDelta -= removedLength;
						// the record leaves the index but not the file - only compaction reclaims it
						wasteBytesGenerated += removedLength;
					} else {
						count = 0;
						byteDelta = 0L;
					}
				} else if (nonFlushedValueSet.wasAdded(recordKey)) {
					final FileLocation recordLocation = nonFlushedValue.fileLocation();
					final int currentRecordLength = recordLocation.recordLength();
					recordLengthDelta += currentRecordLength;
					if (currentRecordLength > workingMaxRecordSize) {
						workingMaxRecordSize = currentRecordLength;
					}
					Assert.isPremiseValid(
						!root.containsKey(recordKey),
						"Record was already present!"
					);
					root = root.updated(recordKey, recordLocation);
					count = 1;
					byteDelta = currentRecordLength;
				} else {
					final FileLocation newRecordLocation = nonFlushedValue.fileLocation();
					// read the replaced record's length before path-copying the new one in (primitive
					// fast path, no FileLocation materialization)
					final int existingLength = root.findRecordLength(recordKey);
					Assert.isPremiseValid(
						existingLength != OffsetLocationChampMap.RECORD_LENGTH_ABSENT, "Record was not present!");
					root = root.updated(recordKey, newRecordLocation);
					recordLengthDelta += newRecordLocation.recordLength() - existingLength;
					// the superseded version stays in the file behind the new one
					wasteBytesGenerated += existingLength;
					if (newRecordLocation.recordLength() > workingMaxRecordSize) {
						workingMaxRecordSize = newRecordLocation.recordLength();
					}
					count = 0;
					byteDelta = newRecordLocation.recordLength() - existingLength;
				}

				histogramDiff.merge(
					recordKey.recordType(), new RecordTypeUsage(count, byteDelta), RecordTypeUsage::plus
				);
			}

			// snapshot this version's histogram (reuse the prior immutable map when no record type changed)
			if (!histogramDiff.isEmpty()) {
				final Map<Byte, RecordTypeUsage> updatedHistogram = new HashMap<>(histogram);
				for (Entry<Byte, RecordTypeUsage> entry : histogramDiff.entrySet()) {
					updatedHistogram.merge(entry.getKey(), entry.getValue(), RecordTypeUsage::plus);
				}
				histogram = Map.copyOf(updatedHistogram);
			}
			addVersions[batchIndex] = nonFlushedValueSet.getCatalogVersion();
			addRoots[batchIndex] = root;
			addHistograms[batchIndex] = histogram;
			addTimestamps[batchIndex] = promotedAt;
			batchIndex++;
		}

		// update global statistics
		this.totalSizeBytes.addAndGet(recordLengthDelta);
		this.maxRecordSizeBytes.set(workingMaxRecordSize);
		// sample the waste rate here rather than per write: a flush is where the stranded bytes actually become part
		// of the file, and it is the same moment the compaction trigger itself evaluates
		this.wasteAccumulation = this.wasteAccumulation.sampled(wasteBytesGenerated, promotedAt);
		// append the new per-version snapshots, then drop any history a catalog has released (the watermark set by
		// purge is applied here, in the serialized writer, so no reader-side lock is needed)
		Roots published = currentRoots.append(catalogVersion, addVersions, addRoots, addHistograms, addTimestamps);
		final long releasedUptoInclusive = this.volatileValues.consumePurgeWatermark();
		if (releasedUptoInclusive > -1) {
			published = published.purgedThrough(releasedUptoInclusive);
		}
		// publish the registry together with the conforming catalog version through a single volatile write, so
		// lock-free readers never observe a torn (versions, roots) snapshot
		this.roots = published;
		// report the oldest retained historical version to observers after a release may have advanced it
		if (releasedUptoInclusive > -1) {
			this.volatileValues.notifyOldestKept(getOldestRecordKeptTimestamp().orElse(null));
		}
	}

	/**
	 * Method stores new record to the OffsetIndex. This method should be called only from singleton writer and never
	 * directly from the code. All writes are serialized by exclusive write access.
	 */
	private void doPut(
		long catalogVersion, long primaryKey, @Nonnull StoragePart value,
		@Nonnull ObservableOutput<?> exclusiveWriteAccess
	) {
		final byte recordType = this.recordTypeRegistry.idFor(value.getClass());
		final RecordKey key = new RecordKey(recordType, primaryKey);

		// a not-yet-flushed write to this key from an EARLIER, different version always overrides the published
		// root, which may not yet reflect it - same precedence as doRemove's lookup below, so a batch that both
		// removes and re-adds the same key across several not-yet-flushed versions resolves consistently at
		// promote time. The lookup is deliberately bounded to versions strictly before this one: a remove and a
		// re-add within the SAME version fold into one entry regardless (see NonFlushedValueSet#put/#remove), so
		// consulting this version's own not-yet-registered activity here would misjudge a same-transaction
		// delete-then-recreate of a key that already exists in the published root as a brand new key.
		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(
			catalogVersion - 1, key);
		final boolean update = nonFlushedValueRef.isPresent() ?
			!nonFlushedValueRef.get().removed() : this.roots.latestRoot().containsKey(key);
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

		final Optional<VersionedValue> nonFlushedValueRef = this.volatileValues.getNonFlushedValueIfVersionMatches(
			catalogVersion, key);
		if (nonFlushedValueRef.isPresent()) {
			final VersionedValue nonFlushedValue = nonFlushedValueRef.get();
			if (nonFlushedValue.removed()) {
				return false;
			} else {
				this.volatileValues.removeValue(catalogVersion, key, nonFlushedValue.fileLocation());
				return true;
			}
		}

		// a write always targets the current version, so resolve the location to drop against the latest root
		final FileLocation currentLocation = this.roots.floorRoot(catalogVersion).get(key);
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
	private <T extends Serializable> StorageRecord<T> doGet(
		@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
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
	private <T extends Serializable> StorageRecord<byte[]> doGetBinary(
		@Nonnull Class<T> recordType, long primaryKey, @Nonnull FileLocation it) {
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
										final int decompressedBytes = exclusiveReadAccess.decompress(
											rawRecord.rawData(), utility);
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
	 * Immutable, structurally-shared registry of the record-location index across catalog
	 * versions. It pairs a sorted (ascending) `versions` array with parallel `locationRoots`,
	 * `histograms` and `timestamps` arrays, where index `i` holds the complete state as it stood at
	 * catalog version `versions[i]` - the {@link OffsetLocationChampMap} of record locations, the record-type
	 * histogram, and the wall-clock promotion timestamp (a telemetry-only side channel, never
	 * consulted for version resolution). Only versions that actually changed the index get an entry;
	 * reads for any catalog version resolve through {@link #floorIndex(long)} (the greatest retained
	 * version not exceeding the requested one), so the gaps between entries are interpolated for free
	 * by the persistent map's structural sharing.
	 *
	 * The whole registry is published through a single volatile {@link #roots} reference, so
	 * lock-free readers always observe a coherent `(versions, locationRoots, histograms, timestamps)`
	 * tuple - they can never see a freshly appended root paired with a stale version array.
	 * {@link #currentVersion} is the conforming catalog version of the latest entry; it may run ahead
	 * of `versions[length - 1]` after an empty flush (a version bump that changed nothing), which
	 * costs only a new holder - not a new root.
	 *
	 * @param currentVersion the conforming catalog version of the most recent state
	 *                       (>= `versions[length - 1]`)
	 * @param versions       retained catalog versions in ascending order; never empty
	 * @param locationRoots  parallel to `versions`; `locationRoots[i]` is the location map as of
	 *                       `versions[i]`
	 * @param histograms     parallel to `versions`; `histograms[i]` is the record-type histogram as
	 *                       of `versions[i]`
	 * @param timestamps     parallel to `versions`; `timestamps[i]` is the epoch-millis promotion
	 *                       time of `versions[i]` - a telemetry-only side channel, not used for
	 *                       version resolution
	 */
	private record Roots(
		long currentVersion,
		@Nonnull long[] versions,
		@Nonnull OffsetLocationChampMap[] locationRoots,
		@Nonnull Map<Byte, RecordTypeUsage>[] histograms,
		@Nonnull long[] timestamps
	) {

		/**
		 * Builds a single-entry registry holding the initial state at `version`.
		 *
		 * @param version   the catalog version the state conforms to
		 * @param root      the location map as of `version`
		 * @param histogram the record-type histogram as of `version`
		 * @param timestamp the wall-clock epoch millis at which `version` became the retained state
		 * @return a one-entry registry
		 */
		@Nonnull
		static Roots initial(
			long version,
			@Nonnull OffsetLocationChampMap root,
			@Nonnull Map<Byte, RecordTypeUsage> histogram,
			long timestamp
		) {
			return new Roots(
				version, new long[]{version}, asRootArray(root), asHistogramArray(histogram), new long[]{timestamp}
			);
		}

		@Nonnull
		private static OffsetLocationChampMap[] asRootArray(
			@Nonnull OffsetLocationChampMap root
		) {
			return new OffsetLocationChampMap[]{root};
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		private static Map<Byte, RecordTypeUsage>[] asHistogramArray(@Nonnull Map<Byte, RecordTypeUsage> histogram) {
			return (Map<Byte, RecordTypeUsage>[]) new Map[]{histogram};
		}

		/**
		 * Returns a copy with the conforming version advanced to `version`, keeping every retained root, histogram and
		 * timestamp untouched - an empty flush bumps only the version pointer, not the data.
		 *
		 * @param version the new conforming catalog version (must not be lower than {@link #currentVersion})
		 * @return a registry with the advanced conforming version
		 */
		@Nonnull
		Roots withCurrentVersion(long version) {
			return new Roots(version, this.versions, this.locationRoots, this.histograms, this.timestamps);
		}

		/**
		 * Returns a copy of this registry with the supplied per-version entries appended. The added versions must be
		 * ascending and must not precede the current tail. The first added version may *equal* the tail version - this
		 * happens when changes are written at the genesis/reload version (the writer keeps building the version it
		 * started from) - in which case it supersedes the tail entry instead of duplicating it; every later added
		 * version is strictly greater. Because each appended root structurally shares the bulk of its predecessor,
		 * retaining the whole per-version history is cheap.
		 *
		 * @param newCurrentVersion the conforming catalog version after the append (>= the highest added version)
		 * @param addVersions       the catalog versions to append, ascending, the first being >= the current tail
		 * @param addRoots          parallel to `addVersions`; the location map snapshot per appended version
		 * @param addHistograms     parallel to `addVersions`; the histogram snapshot per appended version
		 * @param addTimestamps     parallel to `addVersions`; the epoch millis at which each version was promoted
		 * @return a registry holding the retained entries followed by the appended ones
		 */
		@Nonnull
		Roots append(
			long newCurrentVersion,
			@Nonnull long[] addVersions,
			@Nonnull OffsetLocationChampMap[] addRoots,
			@Nonnull Map<Byte, RecordTypeUsage>[] addHistograms,
			@Nonnull long[] addTimestamps
		) {
			if (addVersions.length == 0) {
				return withCurrentVersion(newCurrentVersion);
			}
			final long tail = this.versions[this.versions.length - 1];
			Assert.isPremiseValid(
				addVersions[0] >= tail,
				"Appended versions must not precede the retained ones!"
			);
			// re-promoting the current tail version (changes written at the genesis/reload version) supersedes that
			// entry rather than adding a duplicate; every other added version extends the registry
			final int keep = addVersions[0] == tail ? this.versions.length - 1 : this.versions.length;
			final int addLen = addVersions.length;
			final int total = keep + addLen;
			final long[] nv = new long[total];
			System.arraycopy(this.versions, 0, nv, 0, keep);
			System.arraycopy(addVersions, 0, nv, keep, addLen);
			final OffsetLocationChampMap[] nr = Arrays.copyOf(this.locationRoots, total);
			System.arraycopy(addRoots, 0, nr, keep, addLen);
			final Map<Byte, RecordTypeUsage>[] nh = Arrays.copyOf(this.histograms, total);
			System.arraycopy(addHistograms, 0, nh, keep, addLen);
			final long[] nt = new long[total];
			System.arraycopy(this.timestamps, 0, nt, 0, keep);
			System.arraycopy(addTimestamps, 0, nt, keep, addLen);
			return new Roots(newCurrentVersion, nv, nr, nh, nt);
		}

		/**
		 * Returns a copy of this registry that has dropped every version no later than `releasedUptoInclusive` while
		 * still retaining the floor entry needed to resolve the smallest version a client may still reference
		 * (`releasedUptoInclusive + 1`). The current (last) entry is always retained. If nothing can be dropped the
		 * same instance is returned. Dropped roots become unreachable and their now-exclusive CHAMP nodes are
		 * reclaimed by the GC through structural sharing.
		 *
		 * @param releasedUptoInclusive the highest catalog version no client references any more
		 * @return a registry without the released history, or `this` when nothing is dropped
		 */
		@Nonnull
		Roots purgedThrough(long releasedUptoInclusive) {
			final int keepFrom = floorIndex(releasedUptoInclusive + 1);
			if (keepFrom <= 0) {
				return this;
			}
			return new Roots(
				this.currentVersion,
				Arrays.copyOfRange(this.versions, keepFrom, this.versions.length),
				Arrays.copyOfRange(this.locationRoots, keepFrom, this.locationRoots.length),
				Arrays.copyOfRange(this.histograms, keepFrom, this.histograms.length),
				Arrays.copyOfRange(this.timestamps, keepFrom, this.timestamps.length)
			);
		}

		/**
		 * Epoch millis at which the oldest retained *historical* version was promoted, or empty when only the current
		 * version is retained (no history is being kept for past readers). Used purely for telemetry.
		 *
		 * @return the oldest retained historical version's promotion timestamp, or empty when no history is retained
		 */
		@Nonnull
		OptionalLong oldestHistoricalTimestamp() {
			return this.versions.length > 1 ? OptionalLong.of(this.timestamps[0]) : OptionalLong.empty();
		}

		/**
		 * Index of the greatest retained version not exceeding `catalogVersion`; clamps to `0` when `catalogVersion`
		 * precedes every retained version (the oldest retained state is then the best available answer).
		 *
		 * @param catalogVersion the catalog version to resolve
		 * @return the parallel-array index of the resolved version
		 */
		int floorIndex(long catalogVersion) {
			final int idx = Arrays.binarySearch(this.versions, catalogVersion);
			if (idx >= 0) {
				return idx;
			}
			final int insertion = -idx - 1;
			return insertion == 0 ? 0 : insertion - 1;
		}

		/**
		 * Returns the location map as of the greatest retained version not exceeding `catalogVersion`.
		 *
		 * @param catalogVersion the catalog version to resolve against
		 * @return the resolved location map
		 */
		@Nonnull
		OffsetLocationChampMap floorRoot(long catalogVersion) {
			return this.locationRoots[floorIndex(catalogVersion)];
		}

		/**
		 * Returns the record-type histogram as of the greatest retained version not exceeding `catalogVersion`.
		 *
		 * @param catalogVersion the catalog version to resolve against
		 * @return the resolved histogram
		 */
		@Nonnull
		Map<Byte, RecordTypeUsage> floorHistogram(long catalogVersion) {
			return this.histograms[floorIndex(catalogVersion)];
		}

		/**
		 * Returns the most recent (current) location map.
		 *
		 * @return the latest location map
		 */
		@Nonnull
		OffsetLocationChampMap latestRoot() {
			return this.locationRoots[this.locationRoots.length - 1];
		}

		/**
		 * Returns the most recent (current) record-type histogram.
		 *
		 * @return the latest histogram
		 */
		@Nonnull
		Map<Byte, RecordTypeUsage> latestHistogram() {
			return this.histograms[this.histograms.length - 1];
		}
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
		 * @param create - when true it affects {@link #nonFlushedValuesHistogram} results; when false it still
		 *               affects them if this version had already removed the same key, because the removal and
		 *               this write fold into a plain overwrite whose cardinality effect cancels out
		 */
		public void put(@Nonnull RecordKey key, @Nonnull VersionedValue value, boolean create) {
			if (create) {
				this.nonFlushedValuesHistogram.merge(key.recordType(), 1, Integer::sum);
				this.addedKeys.add(key);
				this.removedKeys.remove(key);
			} else if (this.removedKeys.remove(key)) {
				// this version already dropped a record that existed before it and now writes it back: the two
				// fold into a plain overwrite, so the removal's cardinality effect has to be undone. Without
				// this the in-flight count reports one record fewer than the very next flush publishes.
				this.nonFlushedValuesHistogram.merge(key.recordType(), 1, Integer::sum);
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
			this.nonFlushedValueIndex.put(
				key, new VersionedValue(key.primaryKey(), (byte) (key.recordType() * -1), fileLocation));
			// a record created in this very version and dropped again folds into a no-op - undo the creation
			// rather than record the removal of something that was never published, which would otherwise
			// make the in-flight count of an untouched index go negative
			if (!this.addedKeys.remove(key)) {
				this.removedKeys.add(key);
			}
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
		 *
		 * @param recordTypeId the record type id
		 * @return the count of non-flushed records of particular type
		 */
		public int getCountFor(byte recordTypeId) {
			return this.nonFlushedValuesHistogram.getOrDefault(recordTypeId, 0);
		}
	}

	/**
	 * The VolatileValues class holds the not-yet-flushed (in-flight) changes of the OffsetIndex - the writes whose
	 * file locations have been computed but not yet persisted and promoted into the versioned-root registry. Once a
	 * flush promotes them (see {@link #promoteNonFlushedValuesToSharedState}) this container is emptied; historical
	 * versions are no longer kept here - they live as retained roots in {@link Roots}, resolved by catalog version.
	 *
	 * This container also carries the deferred purge watermark: {@link #purge(long)} records the highest released
	 * catalog version, and the next promotion consumes it (under the serialized writer) to drop the corresponding
	 * retained roots from the registry.
	 */
	@RequiredArgsConstructor
	static class VolatileValues {
		/**
		 * Highest catalog version a client has released; the next promotion consumes this watermark and drops the
		 * corresponding retained roots from the versioned-root registry. `-1` means nothing is pending.
		 */
		private final AtomicLong purgeOlderThan = new AtomicLong(-1);
		/**
		 * Observer that is notified when a non-flushed block size changes in any way.
		 */
		@Nonnull
		private final Consumer<NonFlushedBlock> nonFlushedBlockObserver;
		/**
		 * Observer that is notified, after a release advances the oldest retained version, with its promotion time.
		 */
		@Nonnull
		private final Consumer<Optional<OffsetDateTime>> historicalVersionsObserver;
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
		 * Contains the last count of non-flushed records.
		 */
		private int nonFlushedRecordCount;
		/**
		 * Contains the last size of non-flushed records in Bytes.
		 */
		private long nonFlushedRecordSizeInBytes;
		/**
		 * The two counters above published as one immutable pair, so that a reader outside the write path never sees
		 * a count from one moment paired with a byte size from another. The instance stored here is always the very
		 * one handed to {@link #nonFlushedBlockObserver}, so publishing it costs a volatile write and no allocation.
		 */
		@Nonnull
		private volatile NonFlushedBlock nonFlushedBlock = new NonFlushedBlock(0, 0L);

		/**
		 * Net cardinality delta of the not-yet-flushed (in-flight) versions visible at `catalogVersion`. Flushed
		 * versions are already reflected by the per-version root resolved in {@link #count(long)}, so this adds only
		 * the contribution of in-flight versions whose catalog version does not exceed the requested one.
		 *
		 * @param catalogVersion the catalog version that limits the visibility of in-flight changes
		 * @return the net (added minus removed) count of in-flight records visible at `catalogVersion`
		 */
		public int countDifference(long catalogVersion) {
			int diff = 0;
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvValues = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null && nvValues != null) {
				// nv is ascending; accumulate every in-flight version up to (and including) catalogVersion
				for (int ix = 0; ix < nv.length && nv[ix] <= catalogVersion; ix++) {
					final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
					if (nonFlushedValueSet != null) {
						diff += nonFlushedValueSet.getAddedKeys().size() - nonFlushedValueSet.getRemovedKeys().size();
					}
				}
			}
			return diff;
		}

		/**
		 * Net per-type cardinality delta of the not-yet-flushed (in-flight) versions visible at `catalogVersion`. As
		 * with {@link #countDifference(long)}, flushed versions are reflected by the per-version histogram resolved in
		 * {@link #count(long, Class)}, so this adds only the in-flight contribution.
		 *
		 * @param catalogVersion the catalog version that limits the visibility of in-flight changes
		 * @param recordTypeId   the record type id
		 * @return the net count of in-flight records of the given type visible at `catalogVersion`
		 */
		public int countDifference(long catalogVersion, byte recordTypeId) {
			int diff = 0;
			final ConcurrentHashMap<Long, NonFlushedValueSet> nvValues = this.nonFlushedValues;
			final long[] nv = this.nonFlushedVersions;
			if (nv != null && nvValues != null) {
				for (int ix = 0; ix < nv.length && nv[ix] <= catalogVersion; ix++) {
					final NonFlushedValueSet nonFlushedValueSet = nvValues.get(nv[ix]);
					if (nonFlushedValueSet != null) {
						diff += nonFlushedValueSet.getCountFor(recordTypeId);
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
		public Optional<VersionedValue> getNonFlushedValueIfVersionMatches(
			long catalogVersion, @Nonnull RecordKey key) {
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
		 * Stores new value to non-flushed storage. The value will be propagated to the shared state once the
		 * {@link #flush(long)} method is called.
		 *
		 * @param catalogVersion  the catalog version the value is written for
		 * @param key             the record key
		 * @param nonFlushedValue the non-flushed value to store
		 * @param create          whether the record was created or not (affects the histogram)
		 */
		public void putValue(
			long catalogVersion, @Nonnull RecordKey key, @Nonnull VersionedValue nonFlushedValue, boolean create) {
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
						Objects.requireNonNull(
							nvSet.get(cv),
							"Non-flushed value set for catalog version " + cv + " is unexpectedly missing!"
						)
					);
				}
				// clear all the data that has been promoted
				this.nonFlushedVersions = null;
				this.nonFlushedValues = null;
				this.nonFlushedRecordCount = 0;
				this.nonFlushedRecordSizeInBytes = 0L;
				// notify the observer
				this.nonFlushedBlock = new NonFlushedBlock(0, 0L);
				this.nonFlushedBlockObserver.accept(this.nonFlushedBlock);
				return result;
			} else {
				return Collections.emptyList();
			}
		}

		/**
		 * Records the highest released catalog version as a deferred purge watermark. The actual drop of the matching
		 * retained roots happens on the next promotion (under the serialized writer) via {@link #consumePurgeWatermark()}.
		 * Accumulates the maximum, so several releases between flushes collapse into the highest released version - the
		 * driver supplies a monotonically rising boundary (everything no later than it has been released by every
		 * client), so keeping the maximum drops all releasable history rather than under-purging to the lowest. The
		 * `-1` sentinel is naturally subsumed because every real released catalog version is `>= 0`.
		 *
		 * @param catalogVersion the highest catalog version no client references any more
		 */
		public void purge(long catalogVersion) {
			this.purgeOlderThan.accumulateAndGet(catalogVersion, Math::max);
		}

		/**
		 * Atomically reads and clears the deferred purge watermark set by {@link #purge(long)}.
		 *
		 * @return the highest released catalog version to drop, or `-1` when no release is pending
		 */
		public long consumePurgeWatermark() {
			return this.purgeOlderThan.getAndSet(-1);
		}

		/**
		 * Notifies the historical-versions observer with the promotion timestamp of the oldest retained version after
		 * a release may have advanced it. Purely telemetry (it surfaces how far back point-in-time restore can reach).
		 *
		 * @param oldestRecordKeptTimestamp the promotion time of the oldest retained historical version, or `null`
		 *                                  when no history is retained
		 */
		public void notifyOldestKept(@Nullable OffsetDateTime oldestRecordKeptTimestamp) {
			this.historicalVersionsObserver.accept(Optional.ofNullable(oldestRecordKeptTimestamp));
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
			this.nonFlushedBlock = new NonFlushedBlock(0, 0L);
			this.nonFlushedBlockObserver.accept(this.nonFlushedBlock);
		}

		/**
		 * Returns the count and the byte size of the records written but not yet flushed, as one coherent pair.
		 *
		 * @return the current non-flushed block
		 */
		@Nonnull
		public NonFlushedBlock getNonFlushedBlock() {
			return this.nonFlushedBlock;
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
				Optional.ofNullable(this.nonFlushedValues)
					.map(nv -> nv.values()
						.stream()
						.mapToLong(it -> MemoryMeasuringConstants.LONG_SIZE + it.getTotalSize())
						.sum())
					.orElse(0L)
				+ (MemoryMeasuringConstants.OBJECT_HEADER_SIZE << 2)
				+ MemoryMeasuringConstants.INT_SIZE + MemoryMeasuringConstants.LONG_SIZE;
		}

		/**
		 * Returns true if the non-flushed values contain the non-removed specified key.
		 *
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
				final ConcurrentHashMap<Long, NonFlushedValueSet> newNonFlushedValues = CollectionUtils.createConcurrentHashMap(
					16);
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
			this.nonFlushedBlock = new NonFlushedBlock(
				this.nonFlushedRecordCount, this.nonFlushedRecordSizeInBytes);
			this.nonFlushedBlockObserver.accept(this.nonFlushedBlock);
		}

	}

	/**
	 * This class is used to safely borrow and return Kryo instances to the pool.
	 */
	@ThreadSafe
	public static class FileOffsetIndexKryoPool extends Pool<VersionedKryo> {
		/**
		 * Current pool generation - the version counter paired with the factory of the {@link OffsetIndexDescriptor}
		 * that version belongs to. Held in a single reference so that {@link #create()} can never combine one
		 * generation's version with another generation's factory, and {@link #free(VersionedKryo)} always compares
		 * against a version that actually matches the binding instances were built with.
		 */
		private final AtomicReference<Generation> generation;

		public FileOffsetIndexKryoPool(int maxInstancesKept, @Nonnull Function<Long, VersionedKryo> kryoFactory) {
			super(true, false, maxInstancesKept);
			this.generation = new AtomicReference<>(new Generation(1L, kryoFactory));
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
		 *
		 * The factory of the descriptor that supersedes the expired one has to be passed in, because the version bump
		 * and the binding it stands for must become visible together. Taking the factory as an argument also makes the
		 * ordering mistake unrepresentable: there is no way to expire the pool without naming the generation that
		 * replaces it.
		 *
		 * @param kryoFactory read-Kryo factory of the descriptor the pool serves from now on
		 */
		public void expireAllPreviouslyCreated(@Nonnull Function<Long, VersionedKryo> kryoFactory) {
			// today's only callers hold the writeHandle lock, so no two expiries can interleave anyway - but the
			// whole point of this field is to be self-consistent without leaning on an external lock, so the bump
			// is done atomically rather than as a read-then-write. The update function is pure, hence safe to retry.
			this.generation.updateAndGet(current -> new Generation(current.version() + 1, kryoFactory));
			this.clear();
		}

		/**
		 * Creates new instance of {@link VersionedKryo} with current configuration of {@link VersionedKryoKeyInputs}.
		 */
		@Override
		protected VersionedKryo create() {
			// read the generation exactly once - version and factory must come from the same one
			final Generation currentGeneration = this.generation.get();
			return currentGeneration.kryoFactory().apply(currentGeneration.version());
		}

		/**
		 * Returns borrowed instance back to the pool.
		 */
		@Override
		public void free(VersionedKryo object) {
			// if object version is the same as actual version, accept it,
			// otherwise it would be discarded and garbage collected
			if (object.getVersion() == this.generation.get().version()) {
				super.free(object);
			}
		}

		/**
		 * Immutable pairing of a pool version with the {@link VersionedKryo} factory of the
		 * {@link OffsetIndexDescriptor} that version stands for.
		 *
		 * @param version     monotonically increasing pool version stamped onto every instance built by `kryoFactory`
		 * @param kryoFactory factory bound to the read {@link KeyCompressor} of the descriptor this generation
		 *                    belongs to
		 */
		private record Generation(
			long version,
			@Nonnull Function<Long, VersionedKryo> kryoFactory
		) {
		}

	}

	/**
	 * This record is used to propagate multiple values in the {@link #doFlush(long, boolean)}
	 * method.
	 *
	 * @param nonFlushedValueSets set of non-flushed value sets that have been flushed
	 * @param fileLocation        the file location of the offset-index descriptor in the file that covers the newly flushed values
	 */
	private record NonFlushedValuesWithFileLocation(
		@Nonnull Collection<NonFlushedValueSet> nonFlushedValueSets,
		@Nonnull FileLocationAndWrittenBytes fileLocation
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
				if (this.getFree() < OffsetIndex.this.maxOpenedReadHandlesOrDefault) {
					this.free(readOnlyFileHandle);
				} else {
					readOnlyFileHandle.close();
				}
			}
		}

		@Override
		protected ReadOnlyHandle create() {
			try {
				if (this.readFilesLock.tryLock(OffsetIndex.this.lockTimeoutSeconds, TimeUnit.SECONDS)) {
					try {
						final ReadOnlyHandle readOnlyFileHandle = OffsetIndex.this.writeHandle.toReadOnlyHandle();
						if (OffsetIndex.this.readOnlyOpenedHandles.size() >= OffsetIndex.this.maxOpenedReadHandlesOrDefault) {
							throw new PoolExhaustedException(
								OffsetIndex.this.maxOpenedReadHandlesOrDefault, readOnlyFileHandle.toString());
						}
						OffsetIndex.this.readOnlyOpenedHandles.add(readOnlyFileHandle);
						return readOnlyFileHandle;
					} finally {
						this.readFilesLock.unlock();
					}
				}
				throw new UnexpectedIOException("New handle to the file couldn't have been created within timeout!");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new UnexpectedIOException("New handle to the file couldn't have been created due to interrupt!");
			}

		}

	}

}
