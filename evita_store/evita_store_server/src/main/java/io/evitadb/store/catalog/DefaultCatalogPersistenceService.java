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

package io.evitadb.store.catalog;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongSet;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.CatalogRequiresUpgradeException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogConsumersListener;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.storage.OffsetIndexHistoryKeptEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexNonFlushedEvent;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.BiIntConsumer;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.store.catalog.header.HeaderInfoSupplier;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.CatalogStoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import io.evitadb.store.catalog.ObsoleteFileMaintainer.DataFilesBulkInfo;
import io.evitadb.store.catalog.TimeTravelRetention.CatalogDataFile;
import io.evitadb.store.catalog.TimeTravelRetention.DataFileInventory;
import io.evitadb.store.catalog.TimeTravelRetention.EntityCollectionDataFile;
import io.evitadb.store.catalog.TimeTravelRetention.GenerationPin;
import io.evitadb.store.catalog.TimeTravelRetention.HorizonDecision;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.catalog.task.BackupTask;
import io.evitadb.store.catalog.task.FullBackupTask;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.exception.BootstrapFileNotFound;
import io.evitadb.store.exception.DirectoryNotEmptyException;
import io.evitadb.store.exception.InvalidFileNameException;
import io.evitadb.store.exception.StoredProtocolVersionNotSupportedException;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.InvalidStoragePathException;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.BootstrapWriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.shared.model.PersistentStorageDescriptor;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.store.wal.AbstractMutationLog.WalPurgeCallback;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.IOUtils.ExceptionThrowingRunnable;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getEntityCollectionDataStoreFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getEntityCollectionDataStoreFileNamePattern;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getIndexFromCatalogFileName;
import static io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService.readCatalogHeader;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * DefaultEntityCollectionPersistenceService class encapsulates main logic of {@link Catalog}
 * serialization to persistent storage and also deserializing the catalog contents back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class DefaultCatalogPersistenceService
	implements
	CatalogPersistenceService<LogFileRecordReference, CollectionFileReference, EntityCollectionFileHeader>,
	CatalogConsumersListener
{

	/**
	 * Buffer size for the {@link BufferedOutputStream} that wraps a raw compaction / snapshot output file. The
	 * snapshot copy emits three tiny writes per record (header, payload, tail); without buffering a
	 * multi-million-record collection turns the copy into millions of write syscalls. Batching them through this
	 * buffer collapses it into far fewer, larger writes.
	 */
	private static final int COMPACTION_OUTPUT_BUFFER_SIZE = 65_536;

	/**
	 * Factory function that configures new instance of the versioned kryo factory.
	 */
	static final Function<VersionedKryoKeyInputs, VersionedKryo> VERSIONED_KRYO_FACTORY = kryoKeyInputs -> VersionedKryoFactory.createKryo(
		kryoKeyInputs.version(),
		SchemaKryoConfigurer.INSTANCE
			.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
			.andThen(SharedClassesConfigurer.INSTANCE)
			.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
			.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
	);
	/**
	 * This constant contains suffixes of all supported extensions for the catalog files and their priority for
	 * the copying.
	 */
	static final Map<String, Integer> ALLOWED_SUFFIXES_WITH_PRIORITY = Map.of(
		BOOT_FILE_SUFFIX, 0,
		CATALOG_FILE_SUFFIX, 1,
		ENTITY_COLLECTION_FILE_SUFFIX, 2,
		WAL_FILE_SUFFIX, 3
	);
	/**
	 * The real clock, and the value {@link #CURRENT_TIME_MILLIS} hands out on any thread that has not overridden it.
	 * Hoisted into a constant so the thread-local initial value costs no allocation per thread.
	 */
	private static final LongSupplier SYSTEM_TIME_MILLIS = System::currentTimeMillis;
	/**
	 * This supplier is overridden in tests to provide deterministic time. Do not use elsewhere.
	 *
	 * Scoped to the overriding thread on purpose. The functional test suite runs as a **single reused surefire fork**
	 * (`forkCount=1`, `reuseForks=true`) with `parallel=all`, so a process-wide override is visible to every test
	 * running concurrently with the one that installed it - and to every test that runs after it, if the override is
	 * ever left in place. Both showed up as real failures: a test pinning this clock to a past instant stamps the
	 * bootstrap records written by *unrelated* catalogs with that past instant, which silently breaks anything that
	 * relates a bootstrap timestamp to the wall clock. Thread scoping removes that coupling by construction rather
	 * than relying on every overriding test to restore the value.
	 *
	 * The override therefore only applies to writes performed on the overriding thread, which is what the tests that
	 * use it do - they drive the persistence service directly, with a mocked scheduler.
	 */
	static final ThreadLocal<LongSupplier> CURRENT_TIME_MILLIS = ThreadLocal.withInitial(() -> SYSTEM_TIME_MILLIS);
	/**
	 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
	 * {@link ObservableOutput#getBuffer()} to use them for serialization. There buffers are not necessary when there are
	 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
	 */
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * The off-heap memory manager instance that is used for allocating off-heap memory regions for storing data.
	 */
	private final CatalogOffHeapMemoryManager offHeapMemoryManager;
	/**
	 * The export file service instance that is used for backing-up data from the catalog.
	 */
	private final ExportService exportService;
	/**
	 * The name of the catalog that maps to {@link EntitySchema#getName()}.
	 */
	@Nonnull
	private final String catalogName;
	/**
	 * Contains lambda that provides name of the WAL file for given WAL file index.
	 */
	private final IntFunction<String> walFileNameProvider;
	/**
	 * Contains path to the directory that contains all files for the catalog this instance of persistence service
	 * takes care of.
	 */
	@Nonnull @Getter
	private final Path catalogStoragePath;
	/**
	 * Contains configuration of record types that could be stored into the mem-table.
	 */
	private final OffsetIndexRecordTypeRegistry recordTypeRegistry;
	/**
	 * The storage part persistence service implementation indexed by catalog version since which it can be used.
	 * Caller should always use the latest version of the storage part persistence service whose key is less or equal to
	 * the catalog version.
	 */
	@Nonnull
	private final ConcurrentHashMap<Long, CatalogOffsetIndexStoragePartPersistenceService> catalogStoragePartPersistenceService;
	/**
	 * Contains information about storage configuration options.
	 */
	@Nonnull
	private final StorageSettings storageSettings;
	/**
	 * Contains information about storage configuration options modified to match requirements of the bootstrap file.
	 * The bootstrap file requires fixed record size and thus must not be compressed.
	 */
	@Nonnull
	private final StorageSettings bootstrapStorageSettings;
	/**
	 * Wall-clock time (epoch millis, {@link #CURRENT_TIME_MILLIS}) at which the catalog data file was last compacted.
	 * Backs the {@code minCompactionIntervalMilliseconds} cadence gate for the catalog-file compaction trigger in
	 * {@link #prepareBootstrap(long, String, int, long, DataStoreMemoryBuffer)}.
	 */
	@Getter(AccessLevel.PACKAGE)
	private long lastCatalogCompactionAtMillis = getNowEpochMillis();
	/**
	 * The map contains index of already created {@link EntityCollectionPersistenceService entity collection services}.
	 * Instances of these services are costly and also contain references to the state, so that they must be kept as
	 * singletons.
	 */
	@Nonnull
	private final ConcurrentHashMap<CollectionFileReference, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServices;
	/**
	 * This variable is used to handle write operations in the Bootstrap class and synchronize the access to it.
	 */
	@Nonnull
	private final AtomicReference<BootstrapWriteOnlyFileHandle> bootstrapWriteHandle;
	/**
	 * This lock synchronizes the access to the bootstrap file.
	 */
	private final ReentrantLock bootstrapWriteLock = new ReentrantLock();
	/**
	 * This lock synchronizes the access to the write ahead log file.
	 */
	private final ReentrantLock walWriteLock = new ReentrantLock();
	/**
	 * Serialises a trunk round's end-of-round processing against a checkpoint driven from any other thread - the
	 * ticker, a backup or an integrity check. Both advance the catalog offset index and may write a bootstrap record,
	 * and a record naming version `V` must not be built from an index that has already absorbed `V+1`.
	 *
	 * Lives here rather than on {@link CheckpointCoordinator} because the state it guards is this service's -
	 * {@link #deferredCheckpointBootstrap}, {@link #bootstrapUsed} and the catalog offset index. The coordinator is
	 * handed the same instance so both paths take one lock, and it exists even when there is no coordinator, which
	 * is what lets {@link #storeHeader} lock unconditionally instead of branching.
	 *
	 * This is a leaf lock: {@link #bootstrapWriteLock}, {@link #cpsvLock} and the write-handle locks are all taken
	 * beneath it, in that same order on every path, and nothing beneath it reaches back here.
	 */
	private final ReentrantLock checkpointLock = new ReentrantLock();
	/**
	 * Takes the device flush of the catalog and entity collection data files off the end of every trunk round and
	 * performs it on an interval instead, together with the bootstrap record that points at those files.
	 *
	 * Null when checkpointing happens at the end of every round - either because
	 * {@link io.evitadb.api.configuration.TransactionOptions#checkpointIntervalInMillis()} is zero, or because
	 * {@link io.evitadb.api.configuration.StorageOptions#syncWrites()} is off and there is consequently no device
	 * flush to defer in the first place.
	 */
	@Getter(AccessLevel.PACKAGE)
	@Nullable private final CheckpointCoordinator checkpointCoordinator;
	/**
	 * The catalog version most recently handed to {@link #storeHeader}, whether or not it has been checkpointed yet.
	 *
	 * {@link #getLastCatalogVersion()} deliberately keeps reporting the last **checkpointed** version, because its
	 * callers use it to tell a failure that struck before durability from one that struck after. This field answers
	 * the different question of what has already been written, which is what a caller deciding whether anything
	 * changed since the previous round needs.
	 */
	private volatile long lastAppliedCatalogVersion;
	/**
	 * The bootstrap record a deferred checkpoint still owes: already built by the round that deferred - which is what
	 * makes it safe for another thread to publish - but not yet written to the bootstrap file.
	 *
	 * Written by the round while holding the coordinator's lock and read by whoever settles the debt under that same
	 * lock. Null when nothing is owed.
	 *
	 * It doubles as the baseline the next round builds on: catalog-file compaction bumps the file index inside the
	 * record, and until the record is written {@link #bootstrapUsed} still names the file index from before the
	 * compaction.
	 */
	@Nullable private volatile CatalogBootstrap deferredCheckpointBootstrap;
	/**
	 * Scheduled executor service is used for planning maintenance tasks on the data level.
	 */
	@Nonnull private final Scheduler scheduler;
	/**
	 * Obsolete file maintainer takes care of deleting files that are no longer referenced by any of the sessions.
	 */
	private final ObsoleteFileMaintainer obsoleteFileMaintainer;
	/**
	 * The single callback through which the history horizon is applied to the files on disk. It is a no-op unless
	 * {@link StorageOptions#timeTravelEnabled()} is set - without time travel the files are deleted as soon as their
	 * last reader leaves and no history accumulates to reclaim in the first place.
	 */
	private final WalPurgeCallback walPurgeCallback;
	/**
	 * The catalog version below which no history is retained any more. Guards {@link #advanceHistoryHorizon(long)}
	 * against walking backwards: WAL retention and the {@link StorageOptions#timeTravelSizeLimitBytes()} guard drive
	 * the same seam independently, so a request derived from a stale floor must not undo the other driver's work.
	 */
	private final AtomicLong historyHorizon = new AtomicLong(0L);
	/**
	 * Serializes the two independent drivers of {@link #advanceHistoryHorizon(long)}. The steps behind it - trimming
	 * the bootstrap file and reclaiming the data files below the new horizon - only make sense as one atomic unit:
	 * the purge derives its threshold by re-reading the oldest record of the freshly trimmed bootstrap file.
	 */
	private final ReentrantLock historyHorizonLock = new ReentrantLock();
	/**
	 * The highest history-horizon request a retention floor clamped away, or `-1` when none is outstanding.
	 *
	 * Only the write-ahead log driver needs this. It deletes its files *before* reporting the floor they imply and
	 * then forgets them, so a request refused because a consumer pinned an older version is gone for good - nothing
	 * will ever report it again, and the bootstrap records pointing at those deleted log files would be retained for
	 * the rest of the catalog's life. Remembering it lets the release of the pin retry it. The budget driver needs no
	 * such thing: it re-derives its horizon from scratch on every run.
	 */
	private final AtomicLong pendingHistoryHorizonRequest = new AtomicLong(-1L);
	/**
	 * Enforces {@link StorageOptions#timeTravelSizeLimitBytes()} off the commit thread. `null` when the limit cannot
	 * bind - either time travel is off, or the operator asked for no limit at all.
	 */
	@Nullable private final DelayedAsyncTask timeTravelSizeGuardTask;
	/**
	 * Pool contains instances of {@link Kryo} that are used for serializing mutations in WAL.
	 */
	private final Pool<Kryo> walKryoPool = new Pool<>(true, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	/**
	 * Lock used for synchronization to {@link #catalogPersistenceServiceVersions} array.
	 */
	private final ReentrantLock cpsvLock = new ReentrantLock();
	/**
	 * Contains sorted array of {@link #catalogStoragePartPersistenceService} keys. It is used for fast lookup of the
	 * storage part persistence service for a given catalog version.
	 */
	private long[] catalogPersistenceServiceVersions;
	/**
	 * Contains information about cardinality of the warm-up version of the catalog - i.e. zero. This version is special
	 * in the sense, it may be used repeatedly (version doesn't increment with catalog flushes) and because the array
	 * {@link #catalogPersistenceServiceVersions} cannot contain multiple zeros, this counter is used to keep track of
	 * the number of times the zero version was used.
	 */
	private int warmUpVersionCardinality;
	/**
	 * Contains the instance of {@link CatalogBootstrap} that contains the last bootstrap record that is currently used.
	 */
	private CatalogBootstrap bootstrapUsed;
	/**
	 * Contains the instance of {@link CatalogWriteAheadLog} that is used for writing mutations into shared WAL.
	 *
	 * `volatile` because {@link #syncWal()} reads it from the thread that forces the WAL, which is not the
	 * thread that appends. Every other assignment happens in a constructor, and the only runtime one is the
	 * lazy initialisation inside {@link #doAppendWalAndDiscard}; the appender's publication of that value
	 * does happen-before the syncer's read through the pending-transaction queue's lock, but relying on a
	 * chain that long to keep a field safe is exactly how it stops being safe when somebody reorders a call.
	 */
	@Nullable private volatile CatalogWriteAheadLog catalogWal;
	/**
	 * Contains the millis from the time the non-flushed block was reported.
	 */
	private long lastReportTimestamp;
	/**
	 * Contains the millis from the time when catalog statistics was reported.
	 */
	private long lastCatalogStatisticsTimestamp;
	/**
	 * Flag indicating whether the catalog is closed. This flag is set to true when the catalog is closed and
	 * should not be used anymore.
	 */
	private boolean closed;

	/**
	 * Method returns continuous stream of catalog bootstrap records from the catalog bootstrap file.
	 *
	 * @param catalogName     the name of the catalog
	 * @param storageSettings the storage options for reading the bootstrap file
	 * @return the stream of catalog bootstrap records
	 */
	@Nonnull
	public static Stream<CatalogBootstrap> getCatalogBootstrapRecordStream(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final CatalogBootstrapSupplier supplier = new CatalogBootstrapSupplier(bootstrapFilePath, storageSettings);
			return Stream.generate(supplier)
				.takeWhile(Objects::nonNull)
				.onClose(supplier::close);
		} else {
			throw new BootstrapFileNotFound(catalogStoragePath, bootstrapFile);
		}
	}

	/**
	 * Check whether target directory exists and whether it is really directory.
	 *
	 * @return name of the directory (e.g. catalog name)
	 */
	@Nonnull
	public static String verifyDirectory(@Nonnull Path storageDirectory, boolean requireEmpty) {
		final File storageDirectoryFile = storageDirectory.toFile();
		if (!storageDirectoryFile.exists()) {
			Assert.isPremiseValid(
				storageDirectoryFile.mkdirs(),
				() -> new UnexpectedIOException(
					"Unable to create directory " + storageDirectory + " for catalog restoration!",
					"Unable to create directory for catalog restoration!"
				)
			);
		}
		Assert.isTrue(
			storageDirectoryFile.exists(),
			() -> new InvalidStoragePathException("Storage path doesn't exist: " + storageDirectory)
		);
		Assert.isTrue(
			storageDirectoryFile.isDirectory(), () -> new InvalidStoragePathException(
				"Storage path doesn't represent a directory: " + storageDirectory)
		);

		if (requireEmpty) {
			Assert.isTrue(
				ofNullable(storageDirectoryFile.listFiles()).map(it -> it.length).orElse(0) == 0,
				() -> new DirectoryNotEmptyException(storageDirectory.toString())
			);
		}

		return storageDirectoryFile.getName();
	}

	/**
	 * Verifies the name of the catalog and its uniqueness among other existing catalogs.
	 */
	@Nonnull
	public static Path pathForCatalog(@Nonnull String catalogName, @Nonnull Path storageDirectory) {
		try {
			return storageDirectory.resolve(catalogName);
		} catch (InvalidPathException ex) {
			throw new InvalidFileNameException(
				"Name `" + catalogName + "` cannot be converted a valid file name: " + ex.getMessage() + "! Please rename the catalog.");
		}
	}

	/**
	 * Serializes the bootstrap record to the output and returns the {@link StorageRecord}.
	 *
	 * @param output          the output to write the record to
	 * @param bootstrapRecord the bootstrap record to serialize
	 * @return the {@link StorageRecord} with the serialized bootstrap record
	 */
	@Nonnull
	public static StorageRecord<CatalogBootstrap> serializeBootstrapRecord(
		@Nonnull ObservableOutput<?> output,
		@Nonnull CatalogBootstrap bootstrapRecord
	) {
		Assert.isPremiseValid(
			!output.isCompressionEnabled(),
			"Bootstrap record must not be compressed!"
		);
		return new StorageRecord<>(
			output, bootstrapRecord.catalogVersion(), true,
			theOutput -> {
				theOutput.writeInt(bootstrapRecord.storageProtocolVersion());
				theOutput.writeLong(bootstrapRecord.catalogVersion());
				theOutput.writeInt(bootstrapRecord.catalogFileIndex());
				theOutput.writeLong(bootstrapRecord.timestamp().toInstant().toEpochMilli());

				final FileLocation fileLocation = bootstrapRecord.fileLocation();
				Assert.isPremiseValid(
					fileLocation != null,
					"File location in the catalog bootstrap record is not expected to be NULL!"
				);
				theOutput.writeLong(fileLocation.startingPosition());
				theOutput.writeInt(fileLocation.recordLength());

				return bootstrapRecord;
			}
		);
	}

	/**
	 * Retrieves the first catalog bootstrap for a given catalog or NULL if the bootstrap file is empty.
	 *
	 * @param catalogName     The name of the catalog.
	 * @param storageSettings The storage options for reading the bootstrap file.
	 * @return The first catalog bootstrap or NULL if the catalog bootstrap file is empty.
	 * @throws UnexpectedIOException If there is an error opening the catalog bootstrap file.
	 */
	@Nonnull
	static Optional<CatalogBootstrap> getFirstCatalogBootstrap(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			return of(
				deserializeCatalogBootstrapRecord(storageSettings, bootstrapFilePath, 0));
		} else {
			return empty();
		}
	}

	/**
	 * Retrieves the catalog bootstrap that is valid for passed date and time for a given catalog.
	 *
	 * @param catalogName    The name of the catalog.
	 * @param storageSettings The storage options for reading the bootstrap file.
	 * @param pastMoment     The moment in time to search for the first catalog bootstrap.
	 * @return particular catalog bootstrap record or exception
	 * @throws UnexpectedIOException             If there is an error opening the catalog bootstrap file.
	 * @throws TemporalDataNotAvailableException If the catalog bootstrap starts with later record than the specified
	 *                                           moment or is in the future
	 */
	@Nonnull
	static CatalogBootstrap getCatalogBootstrapForSpecificMoment(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull OffsetDateTime pastMoment
	) {
		Assert.isTrue(
			pastMoment.isBefore(OffsetDateTime.now()),
			() -> new TemporalDataNotAvailableException()
		);

		return localizeLastCatalogBootstrapNotAfter(catalogName, storageSettings, pastMoment);
	}

	/**
	 * Retrieves the catalog bootstrap that is valid for passed date and time for a given catalog.
	 *
	 * @param catalogName     The name of the catalog.
	 * @param storageSettings The storage options for reading the bootstrap file.
	 * @param catalogVersion  The version to search for the catalog bootstrap record.
	 * @return The first catalog bootstrap or NULL if the catalog bootstrap file is empty.
	 * @throws UnexpectedIOException             If there is an error opening the catalog bootstrap file.
	 * @throws TemporalDataNotAvailableException If the catalog bootstrap starts with later record than the specified
	 *                                           moment or is in the future
	 */
	@Nonnull
	static CatalogBootstrap getCatalogBootstrapForSpecificVersion(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		long catalogVersion
	) {
		final Optional<CatalogBootstrap> firstBootstrap = getFirstCatalogBootstrap(catalogName, storageSettings);
		final long firstCatalogVersion = firstBootstrap.map(CatalogBootstrap::catalogVersion).orElse(0L);
		Assert.isTrue(
			firstBootstrap
				.map(it -> it.catalogVersion() <= catalogVersion)
				.orElse(false),
			() -> new TemporalDataNotAvailableException(firstCatalogVersion)
		);

		try (
			final Stream<CatalogBootstrap> catalogBootstrapRecordStream = getCatalogBootstrapRecordStream(
				catalogName, storageSettings
			)
		) {
			final CatalogBootstrap bootstrapRecord = catalogBootstrapRecordStream
				.takeWhile(current -> current.catalogVersion() <= catalogVersion)
				.reduce((previous, current) -> current)
				.orElseThrow(() -> new TemporalDataNotAvailableException(firstCatalogVersion));
			Assert.isTrue(
				bootstrapRecord.catalogVersion() == catalogVersion,
				() -> new TemporalDataNotAvailableException(firstCatalogVersion)
			);
			return bootstrapRecord;
		}
	}

	/**
	 * Creates a CatalogWriteAheadLog if there are any WAL files present in the catalog file path.
	 *
	 * @param catalogVersion     the version of the catalog
	 * @param catalogName        the name of the catalog
	 * @param storageSettings    the storage options
	 * @param scheduler          the executor service
	 * @param historyHorizonAdvancer callback advancing the catalog history horizon once WAL files are purged
	 * @param catalogFilePath    the path to the catalog file
	 * @param kryoPool           the Kryo pool
	 * @return a CatalogWriteAheadLog object if WAL files are present, otherwise null
	 */
	@Nullable
	static CatalogWriteAheadLog createWalIfAnyWalFilePresent(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull LogFileRecordReference logFileRecordReference,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer historyHorizonAdvancer,
		@Nonnull Path catalogFilePath,
		@Nonnull Pool<Kryo> kryoPool
	) {
		final File[] walFiles = catalogFilePath
			.toFile()
			.listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX));
		if (walFiles == null || walFiles.length == 0) {
			return null;
		} else {
			return new CatalogWriteAheadLog(
				catalogVersion, catalogName, logFileRecordReference, catalogFilePath, kryoPool,
				storageSettings, scheduler, historyHorizonAdvancer
			);
		}
	}

	/**
	 * Creates a write-only file handle for the bootstrap catalog file.
	 *
	 * @param catalogName        the name of the catalog for which the bootstrap handle is to be created
	 * @param storageSettings     the storage options to configure the file handle
	 * @param catalogStoragePath the path to the catalog storage directory
	 * @return a new instance of {@code WriteOnlyFileHandle} configured for the catalog bootstrap file
	 */
	@Nonnull
	static BootstrapWriteOnlyFileHandle createBootstrapWriteOnlyHandle(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Path catalogStoragePath
	) {
		return new BootstrapWriteOnlyFileHandle(
			catalogName,
			FileType.CATALOG,
			catalogName,
			storageSettings.outputBufferSize(),
			storageSettings.syncWrites(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings,
			catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName))
		);
	}

	/**
	 * Localizes the **newest** catalog bootstrap record whose timestamp is not after the requested moment - i.e. the
	 * record that describes the state of the catalog *as of* that moment.
	 *
	 * The search deliberately never reports an exact hit: the comparator classifies every record as either
	 * "not after" (lesser) or "after" (greater), so the binary search always returns the insertion point of the first
	 * record strictly newer than the moment. That is the **upper bound**, and the record right below it is the answer.
	 * Resolving it this way rather than by an equality hit matters because bootstrap timestamps are only
	 * millisecond-precise and are far from unique - a warm-up flush burst writes many records inside a single
	 * millisecond - and an equality hit would land on an arbitrary one of them instead of the last.
	 *
	 * @param catalogName     The name of the catalog. Must not be null.
	 * @param storageSettings The storage options containing the storage directory and configuration. Must not be null.
	 * @param moment          The moment the returned record must not be newer than. Must not be null.
	 * @return The localized catalog bootstrap record. Will never be null.
	 * @throws TemporalDataNotAvailableException If the catalog bootstrap file does not exist, is empty, or its oldest
	 *                                           retained record is already newer than the requested moment.
	 * @throws UnexpectedIOException             If there is an issue reading or accessing the catalog bootstrap file.
	 */
	@Nonnull
	private static CatalogBootstrap localizeLastCatalogBootstrapNotAfter(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull OffsetDateTime moment
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		if (!bootstrapFilePath.toFile().exists()) {
			throw new TemporalDataNotAvailableException();
		}
		final CatalogBootstrap oldestRetainedRecord;
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath, storageSettings, storageSettings
			)
		) {
			final int recordCount = CatalogBootstrap.getRecordCount(readHandle.getLastWrittenPosition());
			if (recordCount == 0) {
				throw new TemporalDataNotAvailableException();
			}
			final int localizedIndex = ArrayUtils.binarySearch(
				index -> deserializeCatalogBootstrapRecord(
					CatalogBootstrap.getPositionForRecord(index),
					readHandle
				),
				moment,
				0,
				recordCount,
				recordCount,
				(catalogBootstrap, lookedUpMoment) ->
					catalogBootstrap.timestamp().compareTo(lookedUpMoment) <= 0 ? -1 : 1
			);
			// the comparator above never reports a hit, so the result is always the negated insertion point
			final int firstRecordAfterMoment = -1 * localizedIndex - 1;
			if (firstRecordAfterMoment > 0) {
				return deserializeCatalogBootstrapRecord(
					CatalogBootstrap.getPositionForRecord(firstRecordAfterMoment - 1),
					readHandle
				);
			}
			// every retained record is newer than the requested moment - the history that covered it is gone
			oldestRetainedRecord = deserializeCatalogBootstrapRecord(
				CatalogBootstrap.getPositionForRecord(0),
				readHandle
			);
		} catch (TemporalDataNotAvailableException e) {
			throw e;
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + bootstrapFilePath.toAbsolutePath() + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
		throw new TemporalDataNotAvailableException(oldestRetainedRecord.timestamp());
	}

	/**
	 * Localizes and retrieves a pair of catalog bootstrap records based on the specified catalog name, storage options,
	 * and a looked-up value. The method uses a binary search to locate the desired record and its adjacent record within
	 * the catalog bootstrap file.
	 *
	 * @param catalogName    the name of the catalog for which the bootstrap file is located (must not be null)
	 * @param storageSettings the storage options configuration, including the directory where the catalog files are stored (must not be null)
	 * @param lookedUpValue  the value to search for within the catalog bootstrap records, must be a type that extends {@code Comparable} (must not be null)
	 * @param comparator     a function that compares a catalog bootstrap record with the looked-up value to assist in locating the desired record (must not be null)
	 * @param delta          an integer value indicating the offset to apply when retrieving the located record (e.g., 0for the located record, -1 for the previous record)
	 * @return an array containing two {@code CatalogBootstrap} objects: the first is the record prior to or equal to the located record, and the second is the located record itself
	 * @throws TemporalDataNotAvailableException if the catalog bootstrap file does not exist
	 * @throws UnexpectedIOException             if an error occurs while accessing or reading the catalog bootstrap file
	 */
	@Nonnull
	private static <T extends Comparable<T>> CatalogBootstrap[] localizeCatalogBootstrapPair(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull T lookedUpValue,
		@Nonnull ToIntBiFunction<CatalogBootstrap, T> comparator,
		int delta
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		if (!bootstrapFilePath.toFile().exists()) {
			throw new TemporalDataNotAvailableException();
		}
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath, storageSettings, storageSettings
			)
		) {
			final int recordCount = CatalogBootstrap.getRecordCount(readHandle.getLastWrittenPosition());
			final int localizedIndex = ArrayUtils.binarySearch(
				index -> deserializeCatalogBootstrapRecord(
					CatalogBootstrap.getPositionForRecord(index),
					readHandle
				),
				lookedUpValue,
				0,
				recordCount,
				recordCount,
				comparator
			);
			final int startIndex = localizedIndex < 0 ? -1 * (localizedIndex) - 1 : localizedIndex;
			final int alteredStartIndex = startIndex + delta;
			final CatalogBootstrap locatedBootstrap = deserializeCatalogBootstrapRecord(
				CatalogBootstrap.getPositionForRecord(Math.min(recordCount - 1, alteredStartIndex)),
				readHandle
			);
			final CatalogBootstrap previousBootstrap = alteredStartIndex > 0 ?
				deserializeCatalogBootstrapRecord(
					CatalogBootstrap.getPositionForRecord(alteredStartIndex - 1),
					readHandle
				) : null;
			return new CatalogBootstrap[]{
				previousBootstrap, locatedBootstrap
			};
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + bootstrapFilePath.toAbsolutePath() + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
	}

	/**
	 * Deserializes the catalog bootstrap record from the file on specified position.
	 *
	 * @param storageSettings   the storage options
	 * @param bootstrapFilePath the path to the catalog bootstrap file
	 * @param fromPosition      the position in the file to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	static CatalogBootstrap deserializeCatalogBootstrapRecord(
		@Nonnull StorageSettings storageSettings,
		@Nonnull Path bootstrapFilePath,
		long fromPosition
	) {
		return deserializeCatalogBootstrapRecord(
			storageSettings, bootstrapFilePath, fromPosition,
			DefaultCatalogPersistenceService::deserializeCatalogBootstrapRecord
		);
	}

	/**
	 * Deserializes the catalog bootstrap record from the file on specified position.
	 *
	 * @param storageSettings   the storage options
	 * @param bootstrapFilePath the path to the catalog bootstrap file
	 * @param fromPosition      the position in the file to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap deserializeCatalogBootstrapRecord(
		@Nonnull StorageSettings storageSettings,
		@Nonnull Path bootstrapFilePath,
		long fromPosition,
		@Nonnull BiFunction<Long, ReadOnlyFileHandle, CatalogBootstrap> reader
	) {
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath, storageSettings, storageSettings
			)
		) {
			return reader.apply(fromPosition, readHandle);
		} catch (CorruptedRecordException e) {
			throw e;
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + bootstrapFilePath + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
	}

	/**
	 * Internal method for reading the catalog bootstrap record from the file handle.
	 *
	 * @param fromPosition from which position to read the record
	 * @param readHandle   the file handle to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap deserializeCatalogBootstrapRecord(
		long fromPosition,
		@Nonnull ReadOnlyFileHandle readHandle
	) {
		final StorageRecord<CatalogBootstrap> storageRecord = readHandle.execute(
			input -> {
				Assert.isPremiseValid(
					input.isCompressionDisabled(),
					"Bootstrap record must not be compressed!"
				);
				return StorageRecord.read(
					input,
					new FileLocation(fromPosition, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
					(theInput, recordLength, control) -> new CatalogBootstrap(
						theInput.readInt(),
						theInput.readLong(),
						theInput.readInt(),
						Instant.ofEpochMilli(theInput.readLong()).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
						new FileLocation(
							theInput.readLong(),
							theInput.readInt()
						)
					)
				);
			}
		);
		Assert.isPremiseValid(
			storageRecord != null && storageRecord.payload() != null,
			"Bootstrap record is not expected to be NULL!"
		);
		return storageRecord.payload();
	}

	/**
	 * Retrieves the instance of shared WAL service for a given catalog if the catalog is in transactional mode.
	 *
	 * BEWARE: work with {@link CatalogWriteAheadLog} is not thread safe and must be synchronized!
	 *
	 * @param catalogVersion     The version of the catalog.
	 * @param catalogName        The name of the catalog.
	 * @param catalogStoragePath The path to the storage location of the catalog.
	 * @param catalogHeader      The catalog header object.
	 * @param catalogKryoPool    The Kryo pool for serializing objects.
	 * @param storageSettings     The storage options for the catalog.
	 * @return The CatalogWriteAheadLog object for the given catalog, and creates new if it doesn't exists and catalog
	 * is in transactional mode, it returns NULL if catalog is in warm-up mode
	 */
	@Nullable
	private static CatalogWriteAheadLog getCatalogWriteAheadLog(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer historyHorizonAdvancer
	) {
		final LogFileRecordReference currentWalFileRef;
		if (catalogHeader.catalogState() == CatalogState.ALIVE && catalogHeader.walFileReference() == null) {
			// set up new empty WAL file
			currentWalFileRef = new LogFileRecordReference(walFileNameProvider);
			final Path walFilePath = catalogStoragePath.resolve(
				walFileNameProvider.apply(currentWalFileRef.fileIndex())
			);
			Assert.isPremiseValid(
				!walFilePath.toFile().exists(),
				() -> new UnexpectedIOException(
					"WAL file `" + walFilePath + "` is not expected to exist at this point, but it does!",
					"WAL file is not expected to exist at this point, but it does!"
				)
			);
		} else {
			currentWalFileRef = catalogHeader.walFileReference();
		}
		return ofNullable(currentWalFileRef)
			.map(
				walFileReference -> new CatalogWriteAheadLog(
					catalogVersion, catalogName,
					currentWalFileRef,
					catalogStoragePath, catalogKryoPool,
					storageSettings, scheduler,
					historyHorizonAdvancer
				)
			)
			.orElse(null);
	}

	/**
	 * Reports changes in historical records kept.
	 *
	 * @param catalogName            name of the catalog
	 * @param oldestHistoricalRecord oldest historical record
	 */
	private static void reportOldestHistoricalRecord(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime oldestHistoricalRecord
	) {
		new OffsetIndexHistoryKeptEvent(
			catalogName,
			FileType.CATALOG,
			catalogName,
			oldestHistoricalRecord
		).commit();
	}

	/**
	 * Returns current date & time in epoch milliseconds. Package-visible so that
	 * {@link DefaultEntityCollectionPersistenceService} can share the same test-overridable clock ({@link #CURRENT_TIME_MILLIS})
	 * for its own compaction-interval gate.
	 *
	 * @return current date & time in epoch milliseconds
	 */
	static long getNowEpochMillis() {
		return CURRENT_TIME_MILLIS.get().getAsLong();
	}

	/**
	 * Tells whether at least {@code minCompactionIntervalMillis} have elapsed since {@code lastCompactionAtMillis}.
	 * A {@code minCompactionIntervalMillis} of `0` (the backward-compatible default) always returns `true`, i.e.
	 * the gate is disabled and imposes no minimum interval.
	 *
	 * @param nowMillis                   current wall-clock time in epoch milliseconds
	 * @param lastCompactionAtMillis      wall-clock time of the file's last compaction, in epoch milliseconds
	 * @param minCompactionIntervalMillis configured minimal interval between compactions, in milliseconds
	 * @return `true` if the minimal interval has elapsed (or is disabled)
	 */
	static boolean isCompactionIntervalElapsed(
		long nowMillis,
		long lastCompactionAtMillis,
		long minCompactionIntervalMillis
	) {
		return (nowMillis - lastCompactionAtMillis) >= minCompactionIntervalMillis;
	}

	/**
	 * Counts how many of the registered catalog persistence services became unreachable once the retained history
	 * starts at the version whose insertion position is passed in.
	 *
	 * The service that **serves** the floor must survive: {@link #getStoragePartPersistenceService(long)} resolves
	 * a version to the closest registered version at or below it, so a service registered at `v <= floor` still
	 * serves everything from the floor upwards. That makes the count differ between the two branches:
	 *
	 * - **exact hit** - the insertion position is that version's own index, so all the services below it are
	 *   obsolete and the one at the position itself keeps serving;
	 * - **no hit** - the insertion position counts the versions strictly lower than the floor, and the last of them
	 *   (index `position - 1`) is the one still serving the floor, so one fewer is obsolete.
	 *
	 * Gating the cleanup on an exact hit - which is what this did before it was extracted - made it practically
	 * unreachable: the floor arrives from WAL retention as `lastVersionInFile + 1` and coincides with a version at
	 * which a compaction registered a service only by accident. Every service left behind keeps read handles open
	 * on a data file that the following purge is about to delete.
	 *
	 * @param position insertion position of the history floor within the registered service versions
	 * @return number of obsolete services, counted from the oldest
	 */
	static int countObsoletePersistenceServices(@Nonnull InsertionPosition position) {
		return position.alreadyPresent() ?
			position.position() : Math.max(0, position.position() - 1);
	}

	/**
	 * Decides whether a data file should be compacted now. This is the single decision function shared by both
	 * compaction trigger sites (entity-collection flush and catalog-file bootstrap) - see
	 * `docs/plans/optimizations/compaction-waste-threshold-auto-tuning.md` §3.1.
	 *
	 * The file is compacted when it exceeds `fileSizeCompactionThresholdBytes` (`fileBigEnough`) AND either:
	 * (a) its active record share has fallen below `maxWasteActiveShare` - a hard override that forces compaction
	 *     immediately, regardless of the minimal interval, or
	 * (b) its active record share is below `minimalActiveRecordShare` (worthwhile to compact) AND the minimal
	 *     compaction interval has elapsed.
	 *
	 * With the backward-compatible defaults (`minCompactionIntervalMilliseconds = 0`, `maxWasteActiveShare =
	 * minimalActiveRecordShare`), `intervalElapsed` is always `true` and branch (a) subsumes branch (b), so this
	 * collapses to the original `fileBigEnough && activeRecordShare < minimalActiveRecordShare` condition exactly.
	 *
	 * @param fileBigEnough             `true` when the file size exceeds `fileSizeCompactionThresholdBytes`
	 * @param activeRecordShare         the file's current active (non-wasted) record share
	 * @param minimalActiveRecordShare  the "worthwhile waste" threshold (`A`)
	 * @param maxWasteActiveShare       the hard override threshold - compaction is forced below this share
	 * @param minCompactionIntervalElapsed `true` when at least `minCompactionIntervalMilliseconds` have elapsed since
	 *                                      the file's last compaction (see {@link #isCompactionIntervalElapsed})
	 * @return `true` if the file should be compacted now
	 */
	static boolean shouldCompact(
		boolean fileBigEnough,
		double activeRecordShare,
		double minimalActiveRecordShare,
		double maxWasteActiveShare,
		boolean minCompactionIntervalElapsed
	) {
		return fileBigEnough && (
			activeRecordShare < maxWasteActiveShare ||
				(activeRecordShare < minimalActiveRecordShare && minCompactionIntervalElapsed)
		);
	}

	/**
	 * Retrieves last catalog bootstrap from the bootstrap file and returns it if the file is not empty.
	 * When the bootstrap file is in old format, it performs automatic upgrade on it and all catalog files.
	 *
	 * @param catalogName    name of the catalog
	 * @param bootstrapStorageSettings storage settings with correct configuration for bootstrap file
	 * @param storageSettings storage settings with correct configuration for catalog files
	 * @param exportService  the export service
	 * @return the last catalog bootstrap after the upgrade has been performed, otherwise exception is thrown
	 */
	@Nonnull
	private static CatalogBootstrap getLastCatalogBootstrapWithAutomaticUpgrade(
		@Nonnull String catalogName,
		@Nonnull StorageSettings bootstrapStorageSettings,
		@Nonnull StorageSettings storageSettings,
		@Nonnull ExportService exportService
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = bootstrapStorageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final long lastMeaningfulPosition = CatalogBootstrap.getLastMeaningfulPosition(length);
			try {
				return deserializeCatalogBootstrapRecord(
					bootstrapStorageSettings, bootstrapFilePath, lastMeaningfulPosition
				);
			} catch (CorruptedRecordException ex) {
				// corruption may signalize old format
				final long lastMeaningfulOldPosition = Migration_2025_1.getOldLastMeaningfulPosition(length);
				// this will either read old bootstrap and verify CRC32C checksum or throw exception
				final CatalogBootstrap oldBootstrap = deserializeCatalogBootstrapRecord(
					bootstrapStorageSettings, bootstrapFilePath, lastMeaningfulOldPosition,
					Migration_2025_1::deserializeOldCatalogBootstrapRecord
				);
				// upgrade the bootstrap file and all catalog files
				Migration_2025_1.upgradeCatalogFiles(
					catalogName, bootstrapStorageSettings, storageSettings,
					catalogStoragePath, bootstrapFilePath,
					exportService
				);
				// return the last old bootstrap which is now in new format
				return oldBootstrap;
			}
		} else {
			if (FileUtils.isDirectoryEmpty(catalogStoragePath)) {
				return new CatalogBootstrap(
					0,
					0,
					Instant.now().atZone(ZoneId.systemDefault()).toOffsetDateTime(),
					null
				);
			} else {
				throw new BootstrapFileNotFound(catalogStoragePath, bootstrapFile);
			}
		}
	}

	/**
	 * Returns the file name with renaming the files that contain original catalog name.
	 * This method is based on the logic from RestoreTask.
	 *
	 * @param fileName    the original file name
	 * @param catalogName the new catalog name
	 * @return the file name with renaming
	 */
	@Nonnull
	private static String getFileNameWithCatalogRename(
		@Nonnull String fileName,
		@Nonnull String catalogName
	) {
		if (fileName.endsWith(BOOT_FILE_SUFFIX)) {
			return CatalogPersistenceService.getCatalogBootstrapFileName(catalogName);
		} else if (fileName.endsWith(CATALOG_FILE_SUFFIX)) {
			final int catalogIndex = CatalogPersistenceService.getIndexFromCatalogFileName(fileName);
			return CatalogPersistenceService.getCatalogDataStoreFileName(catalogName, catalogIndex);
		} else if (fileName.endsWith(WAL_FILE_SUFFIX)) {
			final int walIndex = AbstractMutationLog.getIndexFromWalFileName(fileName);
			return CatalogPersistenceService.getWalFileName(catalogName, walIndex);
		} else {
			return fileName;
		}
	}

	/**
	 * Creates a new DefaultCatalogPersistenceService for an existing catalog by reading from storage.
	 * Initializes storage settings (including checksum and compression factories), reads the last bootstrap,
	 * and sets up the WAL and data storage infrastructure.
	 *
	 * @param catalogName        the name of the catalog to load
	 * @param storageOptions     storage configuration including checksum and compression settings
	 * @param transactionOptions transaction configuration for memory buffers and WAL settings
	 * @param scheduler          scheduler for background tasks
	 * @param exportService      service for exporting catalog data
	 */
	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService
	) {
		this.storageSettings = new StorageSettings(storageOptions, transactionOptions);
		this.bootstrapStorageSettings = this.storageSettings.modifyForBootstrapFile();
		this.scheduler = scheduler;
		this.checkpointCoordinator = createCheckpointCoordinator(catalogName, this.storageSettings, scheduler);
		this.exportService = exportService;
		this.offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			catalogName,
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount(),
			this.storageSettings
		);
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = pathForCatalog(catalogName, this.storageSettings.storageDirectory());
		verifyDirectory(this.catalogStoragePath, true);
		this.observableOutputKeeper = new ObservableOutputKeeper(
			catalogName,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			scheduler
		);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			this.storageSettings.timeTravelEnabled(),
			this::fetchOldestRetainedDataFilesInfo
		);
		this.walPurgeCallback = this.obsoleteFileMaintainer.createWalPurgeCallback();
		this.timeTravelSizeGuardTask = createTimeTravelSizeGuardTask(catalogName, this.storageSettings, scheduler);
		final CatalogBootstrap initialCatalogBootstrap = new CatalogBootstrap(
			0, 0, Instant.now().atZone(ZoneId.systemDefault()).toOffsetDateTime(), null
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			createBootstrapWriteOnlyHandle(catalogName, this.bootstrapStorageSettings, this.catalogStoragePath)
		);

		final long catalogVersion = 0L;
		this.catalogWal = null;

		final Path catalogFilePath = this.catalogStoragePath.resolve(
			getCatalogDataStoreFileName(
				catalogName, initialCatalogBootstrap.catalogFileIndex()
			)
		);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFilePath,
				this.storageSettings,
				initialCatalogBootstrap,
				this.recordTypeRegistry,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				VERSIONED_KRYO_FACTORY,
				nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null)),
				this.checkpointCoordinator
			)
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.warmUpVersionCardinality = 1;

		if (initialCatalogBootstrap.fileLocation() == null) {
			this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, 0, null);
		} else {
			this.bootstrapUsed = initialCatalogBootstrap;
		}

		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(16);
	}

	/**
	 * Opens the catalog on disk strictly long enough to run any outstanding storage-protocol upgrade, then closes all
	 * handles. This is the work-phase entry point used by the engine-level `UpgradeCatalogFormatMutationOperator`.
	 * Normal callers must never use it.
	 *
	 * The method constructs a {@link UnusableCatalog} stub as the nominal {@link CatalogContract}
	 * passed to the load ctor — the ctor's only use of that instance is to satisfy the
	 * `verifyCatalogNameMatches` check, which never dereferences anything beyond `getName` on the
	 * non-restore path. The `allowInlineV4ToV5Upgrade = true` flag unlocks the inline v4→v5 migration
	 * inside `verifyAndUpgradeStorageFormat` that would otherwise throw
	 * {@link CatalogRequiresUpgradeException}.
	 *
	 * @param catalogName        the catalog to upgrade
	 * @param storageOptions     storage configuration options
	 * @param transactionOptions transaction configuration options
	 * @param scheduler          scheduler for background tasks
	 * @param exportService      service for creating the pre-migration backup archive
	 */
	public static void runStorageProtocolUpgrade(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService
	) {
		final Path catalogFolder = pathForCatalog(catalogName, storageOptions.storageDirectory());
		final CatalogContract upgradeStub = new UnusableCatalog(
			catalogName,
			CatalogState.OUT_OF_DATE,
			catalogFolder,
			(cn, path) -> new IllegalStateException(
				"Upgrade stub for catalog `" + cn + "` should not be queried — " +
					"only used internally by runStorageProtocolUpgrade."
			)
		);
		try (DefaultCatalogPersistenceService svc = new DefaultCatalogPersistenceService(
			upgradeStub, catalogName, storageOptions, transactionOptions, scheduler, exportService, true
		)) {
			// ctor already ran the v4→v5 migration inline — try-with-resources releases handles.
			// The body deliberately has no content; the work is a side effect of construction.
			Assert.isPremiseValid(
				svc.bootstrapUsed.storageProtocolVersion() == STORAGE_PROTOCOL_VERSION,
				"Upgrade for catalog `" + catalogName + "` completed but bootstrap still reports " +
					"storage protocol version " + svc.bootstrapUsed.storageProtocolVersion() +
					" (expected " + STORAGE_PROTOCOL_VERSION + ")."
			);
		}
	}

	/**
	 * Creates a new DefaultCatalogPersistenceService for a new or existing catalog with an in-memory instance.
	 * Initializes storage settings (including checksum and compression factories), persists the catalog schema,
	 * and sets up the WAL and data storage infrastructure.
	 *
	 * Delegates to the seven-arg overload with {@code allowInlineV4ToV5Upgrade = false} — the normal load path,
	 * which throws {@link CatalogRequiresUpgradeException} on a v4 catalog so the engine can drive the upgrade
	 * through the WAL-backed mutation flow.
	 *
	 * @param catalogInstance    the catalog instance to persist
	 * @param catalogName        the name of the catalog
	 * @param storageOptions     storage configuration including checksum and compression settings
	 * @param transactionOptions transaction configuration for memory buffers and WAL settings
	 * @param scheduler          scheduler for background tasks
	 * @param exportService      service for exporting catalog data
	 */
	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService
	) {
		this(catalogInstance, catalogName, storageOptions, transactionOptions, scheduler, exportService, false);
	}

	/**
	 * Full-control ctor that additionally lets the caller opt into an inline v4→v5 storage-protocol
	 * upgrade. Used exclusively by {@link #runStorageProtocolUpgrade} (invoked by the engine-level
	 * upgrade mutation operator). All other loads must use the shorter overload and get the
	 * "throw {@link CatalogRequiresUpgradeException}" behavior.
	 *
	 * The flag name is deliberately verbose to distinguish this **inline** (same-thread, inside the
	 * load ctor) upgrade path from the **out-of-band** upgrade driven by
	 * {@link io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation}
	 * through the engine WAL — both are "v4→v5 upgrades" but they execute in very different contexts.
	 *
	 * @param catalogInstance          the catalog instance to persist
	 * @param catalogName              the name of the catalog
	 * @param storageOptions           storage configuration including checksum and compression settings
	 * @param transactionOptions       transaction configuration for memory buffers and WAL settings
	 * @param scheduler                scheduler for background tasks
	 * @param exportService            service for exporting catalog data
	 * @param allowInlineV4ToV5Upgrade when {@code true} the v4→v5 WAL rewrite runs inline during
	 *                                 load; when {@code false} a v4 catalog triggers
	 *                                 {@link CatalogRequiresUpgradeException}
	 */
	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService,
		boolean allowInlineV4ToV5Upgrade
	) {
		this.storageSettings = new StorageSettings(storageOptions, transactionOptions);
		this.bootstrapStorageSettings = this.storageSettings.modifyForBootstrapFile();
		this.scheduler = scheduler;
		this.checkpointCoordinator = createCheckpointCoordinator(catalogName, this.storageSettings, scheduler);
		this.exportService = exportService;
		this.offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			catalogName,
			this.storageSettings.transactionMemoryBufferLimitSizeBytes(),
			this.storageSettings.transactionMemoryRegionCount(),
			this.storageSettings
		);
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = pathForCatalog(catalogName, this.storageSettings.storageDirectory());
		this.observableOutputKeeper = new ObservableOutputKeeper(
			catalogName,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			scheduler
		);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			this.storageSettings.timeTravelEnabled(),
			this::fetchOldestRetainedDataFilesInfo
		);
		this.walPurgeCallback = this.obsoleteFileMaintainer.createWalPurgeCallback();
		this.timeTravelSizeGuardTask = createTimeTravelSizeGuardTask(catalogName, this.storageSettings, scheduler);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		// TOBEDONE #538 - introduced with #650 and could be removed later when no version prior to 2025.2 is used
		// TOBEDONE #538 - original contents: getLastCatalogBootstrap(verifiedCatalogName, this.bootstrapStorageOptions);
		this.bootstrapUsed = getLastCatalogBootstrapWithAutomaticUpgrade(
			verifiedCatalogName, this.bootstrapStorageSettings, this.storageSettings,
			exportService
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			createBootstrapWriteOnlyHandle(catalogName, this.bootstrapStorageSettings,this.catalogStoragePath)
		);

		final long catalogVersion = this.bootstrapUsed.catalogVersion();
		final Path catalogFilePath = this.catalogStoragePath.resolve(
			getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex())
		);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		// verifyAndUpgradeStorageFormat can throw CatalogRequiresUpgradeException (for v4 catalogs on
		// the normal load path) or any I/O exception from storage part service creation. If that
		// happens after we've already opened observableOutputKeeper, bootstrapWriteHandle, etc.,
		// those resources must be released — otherwise every failed retry cycle leaks file handles.
		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService;
		try {
			catalogStoragePartPersistenceService = verifyAndUpgradeStorageFormat(
				() -> CatalogOffsetIndexStoragePartPersistenceService.create(
					this.catalogName,
					catalogFilePath,
					this.storageSettings,
					this.bootstrapUsed,
					this.recordTypeRegistry,
					this.offHeapMemoryManager,
					this.observableOutputKeeper,
					VERSIONED_KRYO_FACTORY,
					nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
					oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null)),
					this.checkpointCoordinator
				),
				this.bootstrapUsed.catalogVersion(),
				allowInlineV4ToV5Upgrade
			);
		} catch (Throwable t) {
			closeOnConstructorFailure();
			throw t;
		}

		if (this.bootstrapUsed.storageProtocolVersion() != STORAGE_PROTOCOL_VERSION) {
			closeOnConstructorFailure();
			IOUtils.closeQuietly(catalogStoragePartPersistenceService::close);
			throw new StoredProtocolVersionNotSupportedException(
				this.bootstrapUsed.storageProtocolVersion(), STORAGE_PROTOCOL_VERSION
			);
		}

		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.warmUpVersionCardinality = catalogVersion == 0 ? 1 : 0;
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader =
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogHeader.getEntityTypeFileIndexes().size()
		);

		final LogFileRecordReference logFileRecordReference = catalogHeader.walFileReference() == null ?
			new LogFileRecordReference(this.walFileNameProvider) : catalogHeader.walFileReference();

		final boolean restoreFlagExists;
		try {
			final File restoreFlagFile = this.catalogStoragePath.resolve(CatalogPersistenceService.RESTORE_FLAG)
				.toFile();
			verifyCatalogNameMatches(
				catalogInstance, catalogVersion, this.catalogStoragePath,
				catalogStoragePartPersistenceService, restoreFlagFile.exists() ?
					OnDifferentCatalogName.ADAPT : OnDifferentCatalogName.THROW_EXCEPTION,
				this.bootstrapUsed
			);
			restoreFlagExists = restoreFlagFile.exists();
			if (restoreFlagExists) {
				Assert.isPremiseValid(
					restoreFlagFile.delete(),
					() -> new UnexpectedIOException(
						"Unable to delete restore flag file `" + restoreFlagFile.getAbsolutePath() + "`!",
						"Unable to delete restore flag file!"
					)
				);
			}
		} catch (UnexpectedCatalogContentsException ex) {
			this.close();
			throw ex;
		}

		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName,
			restoreFlagExists ?
				// the catalog name has changed, so we need to reinitialize the WAL file name provider
				new LogFileRecordReference(this.walFileNameProvider, logFileRecordReference) :
				logFileRecordReference,
			this.storageSettings, scheduler,
			this::advanceHistoryHorizon,
			this.catalogStoragePath, this.walKryoPool
		);

		// the limit may have been lowered while this catalog was down, and nothing else would notice until the next
		// compaction - which on an idle catalog may never come
		scheduleTimeTravelSizeGuard();
	}

	private DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull DefaultCatalogPersistenceService formerService
	) {
		this.storageSettings = formerService.storageSettings;
		this.bootstrapStorageSettings = formerService.bootstrapStorageSettings;
		this.scheduler = formerService.scheduler;
		// a fresh coordinator rather than the former service's: this service writes different files, and the former
		// one still owns - and on close forces - whatever it had pending against the paths it was writing
		this.checkpointCoordinator = createCheckpointCoordinator(catalogName, this.storageSettings, this.scheduler);
		this.exportService = formerService.exportService;
		this.offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			catalogName,
			this.storageSettings.transactionMemoryBufferLimitSizeBytes(),
			this.storageSettings.transactionMemoryRegionCount(),
			this.storageSettings
		);
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = pathForCatalog(catalogName, this.storageSettings.storageDirectory());
		this.observableOutputKeeper = new ObservableOutputKeeper(
			catalogName,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.scheduler
		);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			this.scheduler,
			this.catalogStoragePath,
			this.storageSettings.timeTravelEnabled(),
			this::fetchOldestRetainedDataFilesInfo
		);
		this.walPurgeCallback = this.obsoleteFileMaintainer.createWalPurgeCallback();
		this.timeTravelSizeGuardTask = createTimeTravelSizeGuardTask(
			catalogName, this.storageSettings, this.scheduler);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		// TOBEDONE #538 - introduced with #650 and could be removed later when no version prior to 2025.2 is used
		// TOBEDONE #538 - original contents: getLastCatalogBootstrap(verifiedCatalogName, this.bootstrapStorageOptions);
		this.bootstrapUsed = getLastCatalogBootstrapWithAutomaticUpgrade(
			verifiedCatalogName, this.bootstrapStorageSettings, this.storageSettings,
			this.exportService
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			createBootstrapWriteOnlyHandle(catalogName, this.bootstrapStorageSettings, this.catalogStoragePath)
		);

		final long catalogVersion = this.bootstrapUsed.catalogVersion();

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService = verifyAndUpgradeStorageFormat(
			() -> CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFilePath,
				this.storageSettings,
				this.bootstrapUsed,
				this.recordTypeRegistry,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				VERSIONED_KRYO_FACTORY,
				nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null)),
				this.checkpointCoordinator
			),
			this.bootstrapUsed.catalogVersion(),
			false
		);

		if (this.bootstrapUsed.storageProtocolVersion() != STORAGE_PROTOCOL_VERSION) {
			throw new StoredProtocolVersionNotSupportedException(
				this.bootstrapUsed.storageProtocolVersion(), STORAGE_PROTOCOL_VERSION
			);
		}

		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.warmUpVersionCardinality = catalogVersion == 0 ? 1 : 0;
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader =
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion);

		final LogFileRecordReference logFileRecordReference = catalogHeader.walFileReference() == null ?
			new LogFileRecordReference(this.walFileNameProvider) : catalogHeader.walFileReference();

		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName, logFileRecordReference,
			this.storageSettings, this.scheduler,
			this::advanceHistoryHorizon,
			this.catalogStoragePath, this.walKryoPool
		);

		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogHeader.getEntityTypeFileIndexes().size()
		);

		try {
			verifyCatalogNameMatches(catalogVersion, this.catalogStoragePath, catalogStoragePartPersistenceService);
		} catch (UnexpectedCatalogContentsException ex) {
			this.close();
			throw ex;
		}

		// the limit may have been lowered while this catalog was down, and nothing else would notice until the next
		// compaction - which on an idle catalog may never come
		scheduleTimeTravelSizeGuard();
	}

	@Override
	public void emitObservabilityEvents() {
		// emit statistics event
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = getCatalogHeader(
			this.bootstrapUsed.catalogVersion());
		new CatalogStatisticsEvent(
			this.catalogName,
			catalogHeader.getEntityTypeFileIndexes().size(),
			FileUtils.getDirectorySize(this.catalogStoragePath),
			getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageSettings)
				.map(CatalogBootstrap::timestamp)
				.orElse(null),
			computeRetainedHistoryBytes()
		).commit();
		// emit WAL events if it exists
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal != null) {
			theCatalogWal.emitObservabilityEvents();
		}
	}

	@Nonnull
	@Override
	public CatalogOffsetIndexStoragePartPersistenceService getStoragePartPersistenceService(long catalogVersion) {
		try {
			this.cpsvLock.lockInterruptibly();
			final int index = Arrays.binarySearch(this.catalogPersistenceServiceVersions, catalogVersion);
			final int lookupIndex = index >= 0 ? index : (-index - 2);
			Assert.isPremiseValid(
				lookupIndex >= 0 && lookupIndex < this.catalogPersistenceServiceVersions.length,
				() -> new GenericEvitaInternalError(
					"Catalog version " + catalogVersion + " not found in the catalog persistence service versions!")
			);
			final CatalogOffsetIndexStoragePartPersistenceService persistenceService = this.catalogStoragePartPersistenceService.get(
				this.catalogPersistenceServiceVersions[lookupIndex]
			);
			Assert.isPremiseValid(
				persistenceService != null,
				() -> new GenericEvitaInternalError(
					"Catalog persistence service not found for version " + catalogVersion + "!")
			);
			return persistenceService;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Interrupted while trying to lock the catalog persistence service versions!",
				"Interrupted while trying to lock the catalog persistence service versions!",
				e
			);
		} finally {
			if (this.cpsvLock.isHeldByCurrentThread()) {
				this.cpsvLock.unlock();
			}
		}
	}

	@Override
	public long getLastCatalogVersion() {
		return this.bootstrapUsed.catalogVersion();
	}

	@Override
	public long getLastAppliedCatalogVersion() {
		// `bootstrapUsed` moves only at a checkpoint, so without a coordinator the two answers coincide; the field
		// is seeded lazily by the first storeHeader, hence the fallback for a service that has not stored one yet
		final long applied = this.lastAppliedCatalogVersion;
		return applied == 0L ? this.bootstrapUsed.catalogVersion() : applied;
	}

	@Nonnull
	@Override
	public CatalogHeader<LogFileRecordReference, CollectionFileReference> getCatalogHeader(long catalogVersion) {
		return getStoragePartPersistenceService(catalogVersion).getCatalogHeader(catalogVersion);
	}

	@Override
	public void verifyEntityType(
		@Nonnull Collection<EntityCollection> existingEntityCollections, @Nonnull String entityType)
		throws EntityTypeAlreadyPresentInCatalogSchemaException, InvalidClassifierFormatException {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		final Map<NamingConvention, String> nameVariants = NamingConvention.generate(entityType);
		// check the names in all naming conventions are unique in the entity schema
		existingEntityCollections
			.stream()
			.map(EntityCollection::getSchema)
			.flatMap(it -> it.getNameVariants()
				.entrySet()
				.stream()
				.filter(
					nameVariant -> nameVariant.getValue().equals(nameVariants.get(nameVariant.getKey())))
				.map(nameVariant -> new EntityNamingConventionConflict(
					it, nameVariant.getKey(),
					nameVariant.getValue()
				))
			)
			.forEach(conflict -> {
				throw new EntityTypeAlreadyPresentInCatalogSchemaException(
					this.catalogName, conflict.conflictingSchema(), entityType,
					conflict.convention(), conflict.conflictingName()
				);
			});
	}

	@Nonnull
	@Override
	public Optional<CatalogIndex> readCatalogIndex(@Nonnull Catalog catalog, @Nonnull Scope scope) {
		final long catalogVersion = catalog.getVersion();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogIndexStoragePart catalogIndexStoragePart = storagePartPersistenceService.getStoragePart(
			catalogVersion,
			CatalogIndexStoragePart.getStoragePartPKForScope(scope),
			CatalogIndexStoragePart.class
		);
		if (catalogIndexStoragePart == null) {
			return Optional.empty();
		} else {
			Assert.isPremiseValid(
				catalogIndexStoragePart.getCatalogIndexKey().scope() == scope,
				() -> new GenericEvitaInternalError(
					"Catalog index key scope `" + catalogIndexStoragePart.getCatalogIndexKey()
						.scope() + "` does not match the requested scope (`" + scope + "`)!")
			);
			final Set<AttributeKey> sharedAttributeUniqueIndexes = catalogIndexStoragePart.getSharedAttributeUniqueIndexes();
			final Map<AttributeKey, GlobalUniqueIndex> sharedUniqueIndexes = CollectionUtils.createHashMap(
				sharedAttributeUniqueIndexes.size());
			for (AttributeKey attributeKey : sharedAttributeUniqueIndexes) {
				final long partId = GlobalUniqueIndexStoragePart.computeUniquePartId(
					scope,
					attributeKey,
					storagePartPersistenceService.getReadOnlyKeyCompressor()
				);
				final GlobalUniqueIndexStoragePart sharedUniqueIndexStoragePart = storagePartPersistenceService.getStoragePart(
					catalogVersion, partId, GlobalUniqueIndexStoragePart.class);
				Assert.isPremiseValid(
					sharedUniqueIndexStoragePart != null,
					"Shared unique index not found for attribute `" + attributeKey + "`!"
				);
				final GlobalAttributeSchemaContract attributeSchema = catalog.getSchema().getAttribute(
						attributeKey.attributeName())
					.orElseThrow(
						() -> new EvitaInvalidUsageException(
							"Catalog index references attribute `" + attributeKey.attributeName() + "` but such attribute is not found in catalog schema!"));
				final GlobalUniqueIndex globalUniqueIndex;
				if (sharedUniqueIndexStoragePart.isPaged()) {
					// PAGED: the value→tuple tree was persisted as individual leaf pages. Resolve the stream id from the
					// (scope, attributeKey) identity (registered at the first PAGED write) and read every listed leaf page
					// in ascending key order, then reassemble boundary-stable so the first post-restart commit rewrites
					// only genuinely-changed leaves.
					final int streamId = storagePartPersistenceService.getReadOnlyKeyCompressor().getId(
						new GlobalUniqueLeafStreamKey(scope, attributeKey)
					);
					final int[] orderedPageSequences = sharedUniqueIndexStoragePart.getLeafPageSequences();
					final java.io.Serializable[][] perPageValues = new java.io.Serializable[orderedPageSequences.length][];
					final long[][] perPagePayloads = new long[orderedPageSequences.length][];
					for (int i = 0; i < orderedPageSequences.length; i++) {
						final int pageSequence = orderedPageSequences[i];
						final GlobalUniqueIndexLeafPagePart leafPage = storagePartPersistenceService.getStoragePart(
							catalogVersion,
							GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
							GlobalUniqueIndexLeafPagePart.class
						);
						Assert.isPremiseValid(
							leafPage != null,
							"Global unique index leaf page " + pageSequence + " (stream " + streamId + ") for attribute `" +
								attributeKey + "` was not found in persistent storage!"
						);
						perPageValues[i] = leafPage.getValues();
						perPagePayloads[i] = leafPage.getPayloads();
					}
					globalUniqueIndex = GlobalUniqueIndex.fromPersistedPages(
						scope,
						attributeKey,
						attributeSchema.getPlainType(),
						orderedPageSequences,
						perPageValues,
						perPagePayloads,
						sharedUniqueIndexStoragePart.getHighWaterPageSequence(),
						sharedUniqueIndexStoragePart.getLocaleIndex()
					);
				} else {
					globalUniqueIndex = new GlobalUniqueIndex(
						scope,
						attributeKey,
						attributeSchema.getPlainType(),
						java.util.Objects.requireNonNull(
							sharedUniqueIndexStoragePart.getValues(),
							"A SINGLE global unique part must carry the inline value column!"
						),
						java.util.Objects.requireNonNull(
							sharedUniqueIndexStoragePart.getPayloads(),
							"A SINGLE global unique part must carry the inline payload column!"
						),
						sharedUniqueIndexStoragePart.getLocaleIndex()
					);
				}
				sharedUniqueIndexes.put(attributeKey, globalUniqueIndex);
			}
			return Optional.of(
				new CatalogIndex(
					catalogIndexStoragePart.getVersion(),
					catalogIndexStoragePart.getCatalogIndexKey(),
					sharedUniqueIndexes
				)
			);
		}
	}

	@Override
	public void storeHeader(
		@Nonnull UUID catalogId,
		@Nonnull CatalogState catalogState,
		long catalogVersion,
		int lastEntityCollectionPrimaryKey,
		@Nullable TransactionMutation lastProcessedTransaction,
		@Nonnull List<EntityCollectionFileHeader> entityHeaders,
		@Nonnull DataStoreMemoryBuffer dataStoreMemoryBuffer
	) {
		// Serialises the round's end-of-round processing against a ticker-driven or backup-driven checkpoint: both
		// advance the catalog offset index and may write a bootstrap record, and a record naming version `V` must not
		// be built from an index that has already absorbed `V+1`.
		//
		// Taken unconditionally. Without a coordinator there is no other thread that can take it, so the acquisition
		// is uncontended and costs nothing against the round's own device flushes - and making it conditional is what
		// previously forced this method to be split in two around the branch.
		this.checkpointLock.lock();
		try {
			// a checkpoint that failed can never be retried into success - the rounds it covered are long acknowledged
			// and more have been written behind it. Refuse rather than keep acknowledging commits that cannot be made
			// durable.
			if (this.checkpointCoordinator != null) {
				final Throwable checkpointFailure = this.checkpointCoordinator.getFailure();
				if (checkpointFailure != null) {
					throw new UnexpectedIOException(
						"Catalog `" + this.catalogName + "` can no longer checkpoint its data files!",
						"Catalog can no longer checkpoint its data files.",
						checkpointFailure
					);
				}
			}
			// first we need to execute transition to alive state
			if (catalogState == CatalogState.ALIVE && catalogVersion == 0L) {
				this.bootstrapUsed = recordBootstrap(
					catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex(), dataStoreMemoryBuffer);
				catalogVersion = 1L;
			}
			// first store all entity collection headers if they differ
			final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService =
				getStoragePartPersistenceService(catalogVersion);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> currentCatalogHeader = storagePartPersistenceService.getCatalogHeader(
				catalogVersion);
			for (EntityCollectionFileHeader entityHeader : entityHeaders) {
				final FileLocation currentLocation = entityHeader.fileLocation();
				final Optional<FileLocation> previousLocation = currentCatalogHeader
					.getEntityTypeFileIndexIfExists(entityHeader.entityType())
					.map(CollectionFileReference::fileLocation);
				// if the location is different, store the header
				if (!previousLocation.map(it -> it.equals(currentLocation)).orElse(false)) {
					storagePartPersistenceService.putStoragePart(catalogVersion, entityHeader);
				}
			}

			storagePartPersistenceService.writeCatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				catalogVersion,
				this.catalogStoragePath,
				ofNullable(this.catalogWal)
					.map(cwal -> cwal.getWalFileReference(lastProcessedTransaction))
					.orElse(null),
				entityHeaders
					.stream()
					.map(
						it -> new CollectionFileReference(
							it.entityType(),
							it.entityTypePrimaryKey(),
							it.entityTypeFileIndex(),
							it.fileLocation()
						)
					)
					.collect(
						Collectors.toMap(
							CollectionFileReference::entityType,
							Function.identity()
						)
					),
				catalogId,
				this.catalogName,
				catalogState,
				lastEntityCollectionPrimaryKey
			);
			this.lastAppliedCatalogVersion = catalogVersion;

			// Build the bootstrap record HERE, on the round's own thread, whether or not it will be published now.
			// This is what advances the catalog offset index to this version, and only the writer of those entries
			// may do it - a checkpoint arriving from the ticker or a backup would otherwise promote the entries of
			// a round already in flight. Publishing the finished record later is safe; building it later is not.
			final CatalogBootstrap deferredBootstrap = this.deferredCheckpointBootstrap;
			final CatalogBootstrap preparedBootstrap = prepareBootstrap(
				catalogVersion,
				this.catalogName,
				// build on the newest record there is, written or not - a compaction inside a deferred round bumped
				// the file index in that record while `bootstrapUsed` still names the file from before it. Using
				// `bootstrapUsed` here reuses an index a previous round already took, and the compaction copy then
				// overwrites a live catalog file - which surfaces as a Kryo buffer underflow, not a clean failure.
				(deferredBootstrap == null ? this.bootstrapUsed : deferredBootstrap).catalogFileIndex(),
				getNowEpochMillis(),
				dataStoreMemoryBuffer
			);

			// everything above has written its bytes; from here on the only question is when they reach the device

			// warm-up is excluded deliberately: it has no trunk rounds to amortise a checkpoint across, and go-live
			// depends on everything written during it being addressable from disk before the first transaction runs
			if (this.checkpointCoordinator != null && catalogState == CatalogState.ALIVE &&
				!this.checkpointCoordinator.isCheckpointDue()) {
				this.deferredCheckpointBootstrap = preparedBootstrap;
				this.checkpointCoordinator.noteCheckpointDeferred();
				return;
			}

			this.bootstrapUsed = writeCatalogBootstrap(catalogVersion, this.catalogName, preparedBootstrap);
			this.deferredCheckpointBootstrap = null;

			// notify WAL that the new version was successfully stored
			final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
			if (theCatalogWal != null) {
				theCatalogWal.walProcessedUntil(catalogVersion);
			}

			// only in ALIVE state: warm-up writes a bootstrap record on every flush, and reporting each of those as
			// a checkpoint would fill the cadence gauge with the bulk-load round rate. An operator reading a median
			// cadence of a few milliseconds during a bulk load would conclude checkpointing is healthy while looking
			// at a phase where the number means nothing at all. Warm-up flushes still force their pending files -
			// that happens at the fence inside `writeCatalogBootstrap`, independently of this bookkeeping.
			if (this.checkpointCoordinator != null && catalogState == CatalogState.ALIVE) {
				this.checkpointCoordinator.noteCheckpointCompleted();
			}

			// emit event if the number of collections has changed
			if (getNowEpochMillis() - this.lastCatalogStatisticsTimestamp > 1000) {
				new CatalogStatisticsEvent(
					this.catalogName,
					entityHeaders.size(),
					FileUtils.getDirectorySize(this.catalogStoragePath),
					getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageSettings)
						.map(CatalogBootstrap::timestamp)
						.orElse(null),
					computeRetainedHistoryBytes()
				).commit();
				this.lastCatalogStatisticsTimestamp = getNowEpochMillis();
			}
		} finally {
			this.checkpointLock.unlock();
		}
	}

	@Nonnull
	@Override
	public DefaultEntityCollectionPersistenceService getOrCreateEntityCollectionPersistenceService(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey
	) {
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final EntityCollectionFileHeader entityCollectionHeader = ofNullable(
			storagePartPersistenceService.getStoragePart(
				catalogVersion, entityTypePrimaryKey, EntityCollectionFileHeader.class
			)
		)
			.orElseGet(
				() -> new EntityCollectionFileHeader(
					entityType,
					entityTypePrimaryKey,
					findFirstAvailableFileIndex(entityType, entityTypePrimaryKey)
				)
			);
		return this.entityCollectionPersistenceServices.computeIfAbsent(
			new CollectionFileReference(
				entityType,
				entityTypePrimaryKey,
				entityCollectionHeader.entityTypeFileIndex(),
				entityCollectionHeader.fileLocation()
			),
			eType -> createEntityCollectionPersistenceService(entityCollectionHeader)
		);
	}

	@Nonnull
	@Override
	public Optional<EntityCollectionPersistenceService> flush(
		long catalogVersion,
		@Nonnull HeaderInfoSupplier headerInfoSupplier,
		@Nonnull EntityCollectionFileHeader entityCollectionHeader,
		@Nonnull DataStoreMemoryBuffer dataStoreBuffer
	) {
		final CollectionFileReference collectionFileReference =
			new CollectionFileReference(
				entityCollectionHeader.entityType(),
				entityCollectionHeader.entityTypePrimaryKey(),
				entityCollectionHeader.entityTypeFileIndex(),
				entityCollectionHeader.fileLocation()
			);
		final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = this.entityCollectionPersistenceServices.get(
			collectionFileReference);
		if (entityCollectionPersistenceService == null) {
			return empty();
		} else {
			final long previousVersion = entityCollectionPersistenceService.getEntityCollectionHeader().version();
			final OffsetIndexDescriptor newDescriptor = entityCollectionPersistenceService.flush(
				catalogVersion, headerInfoSupplier);
			final boolean intervalElapsed = isCompactionIntervalElapsed(
				getNowEpochMillis(), entityCollectionPersistenceService.getLastCompactionAtMillis(),
				this.storageSettings.minCompactionIntervalMilliseconds()
			);
			final boolean shouldCompact = shouldCompact(
				newDescriptor.getFileSize() > this.storageSettings.fileSizeCompactionThresholdBytes(),
				newDescriptor.getActiveRecordShare(),
				this.storageSettings.minimalActiveRecordShare(),
				this.storageSettings.maxWasteActiveShare(),
				intervalElapsed
			);
			if (newDescriptor.version() > previousVersion && shouldCompact) {
				log.info(
					"Compacting catalog `{}` entity collection `{}`, size exceeds threshold `{}` and active record share is `{}`%, " +
						"entity collection files on disk consume `{}` bytes.",
					this.catalogName,
					entityCollectionHeader.entityType(),
					newDescriptor.getFileSize(),
					newDescriptor.getActiveRecordShare(),
					entityCollectionPersistenceService.getSizeOnDiskInBytes()
				);

				final EntityCollectionFileHeader compactedHeader = entityCollectionPersistenceService.compact(
					this.catalogName, catalogVersion, headerInfoSupplier
				);
				final DefaultEntityCollectionPersistenceService newPersistenceService = this.entityCollectionPersistenceServices.computeIfAbsent(
					new CollectionFileReference(
						entityCollectionHeader.entityType(),
						entityCollectionHeader.entityTypePrimaryKey(),
						compactedHeader.entityTypeFileIndex(),
						compactedHeader.fileLocation()
					),
					eType -> new DefaultEntityCollectionPersistenceService(
						catalogVersion,
						this.catalogName,
						this.catalogStoragePath,
						compactedHeader,
						this.storageSettings,
						this.offHeapMemoryManager,
						this.observableOutputKeeper,
						this.recordTypeRegistry,
						this.checkpointCoordinator
					)
				);
				if (dataStoreBuffer instanceof WarmUpDataStoreMemoryBuffer warmUpDataStoreMemoryBuffer) {
					warmUpDataStoreMemoryBuffer.setPersistenceService(
						newPersistenceService.getStoragePartPersistenceService());
				}
				retireDataFile(
					catalogVersion,
					this.catalogStoragePath.resolve(
						getEntityCollectionDataStoreFileName(
							entityCollectionHeader.entityType(),
							entityCollectionHeader.entityTypePrimaryKey(),
							entityCollectionHeader.entityTypeFileIndex()
						)
					),
					() -> removeEntityCollectionPersistenceServiceAndClose(collectionFileReference)
				);
				Assert.isPremiseValid(
					newPersistenceService.getEntityCollectionHeader().equals(compactedHeader),
					() -> new GenericEvitaInternalError("Unexpected header mismatch!")
				);
				return of(newPersistenceService);
			} else {
				return of(entityCollectionPersistenceService);
			}
		}
	}

	@Nonnull
	@Override
	public EntityCollectionFileHeader getEntityCollectionHeader(
		long catalogVersion,
		int entityTypePrimaryKey
	) {
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		return Objects.requireNonNull(
			storagePartPersistenceService.getStoragePart(
				catalogVersion,
				entityTypePrimaryKey,
				EntityCollectionFileHeader.class
			)
		);
	}

	@Override
	public void goLive(long catalogVersion) {
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> currentCatalogHeader = storagePartPersistenceService.getCatalogHeader(
			catalogVersion);
		Assert.isPremiseValid(
			currentCatalogHeader.catalogState() == CatalogState.WARMING_UP,
			() -> "Catalog `" + this.catalogName + "` is not in WARMING_UP state, cannot go live!"
		);

		storagePartPersistenceService.writeCatalogHeader(
			STORAGE_PROTOCOL_VERSION,
			catalogVersion,
			this.catalogStoragePath,
			currentCatalogHeader.walFileReference(),
			currentCatalogHeader.collectionFileIndex(),
			currentCatalogHeader.catalogId(),
			currentCatalogHeader.catalogName(),
			CatalogState.ALIVE,
			currentCatalogHeader.lastEntityCollectionPrimaryKey()
		);

		this.bootstrapUsed = recordBootstrap(
			catalogVersion,
			this.catalogName,
			this.bootstrapUsed.catalogFileIndex(),
			null
		);
	}

	@Override
	public void updateEntityCollectionHeaders(
		long catalogVersion,
		@Nonnull EntityCollectionHeader[] entityCollectionHeaders
	) {
		// first store all entity collection headers if they differ
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService =
			getStoragePartPersistenceService(catalogVersion);
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> currentCatalogHeader = storagePartPersistenceService
			.getCatalogHeader(catalogVersion);
		boolean hasChanges = false;
		for (EntityCollectionHeader entityHeader : entityCollectionHeaders) {
			if (entityHeader instanceof EntityCollectionFileHeader entityFileHeader) {
				final FileLocation currentLocation = entityFileHeader.fileLocation();
				final Optional<FileLocation> previousLocation = currentCatalogHeader
					.getEntityTypeFileIndexIfExists(entityFileHeader.entityType())
					.map(CollectionFileReference::fileLocation);
				// if the location is different, store the header
				if (!previousLocation.map(it -> it.equals(currentLocation)).orElse(false)) {
					storagePartPersistenceService.putStoragePart(catalogVersion, entityFileHeader);
					hasChanges = true;
				}
			} else {
				throw new GenericEvitaInternalError(
					"Unsupported entity collection header type: " + entityHeader.getClass() + "!",
					"Unsupported entity collection header type!"
				);
			}
		}

		if (hasChanges) {
			final Map<String, CollectionFileReference> newEntityHeaders = Arrays.stream(entityCollectionHeaders)
				// this is safe - we checked it above
				.map(EntityCollectionFileHeader.class::cast)
				.map(
					it -> new CollectionFileReference(
						it.entityType(),
						it.entityTypePrimaryKey(),
						it.entityTypeFileIndex(),
						it.fileLocation()
					)
				)
				.collect(
					Collectors.toMap(
						CollectionFileReference::entityType,
						Function.identity()
					)
				);
			storagePartPersistenceService.writeCatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				currentCatalogHeader.version(),
				this.catalogStoragePath,
				currentCatalogHeader.walFileReference(),
				newEntityHeaders,
				currentCatalogHeader.catalogId(),
				currentCatalogHeader.catalogName(),
				currentCatalogHeader.catalogState(),
				currentCatalogHeader.lastEntityCollectionPrimaryKey()
			);
		}
	}

	@Nonnull
	@Override
	public IsolatedWalPersistenceService createIsolatedWalPersistenceService(
		@Nonnull UUID transactionId,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
		return new DefaultIsolatedWalService(
			this.catalogName,
			transactionId,
			this.storageSettings.conflictPolicy(),
			catalogSchema,
			entitySchemaAccessor,
			this.walKryoPool.obtain(),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.storageSettings.transactionWorkDirectory()
					.resolve(transactionId.toString())
					.resolve(transactionId + ".wal"),
				this.storageSettings.outputBufferSize(),
				this.storageSettings.syncWrites(),
				this.observableOutputKeeper,
				this.offHeapMemoryManager,
				this.storageSettings,
				this.storageSettings
			)
		);
	}

	@Override
	public void closeAndDelete() {
		// close factory first and then delete The directory
		this.close();
		FileUtils.deleteDirectory(this.catalogStoragePath);
	}

	@Override
	public long appendWalAndDiscardDeferringSync(
		long catalogVersion,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull LogRecordReference walReference
	) {
		return doAppendWalAndDiscard(catalogVersion, transactionMutation, walReference);
	}

	@Override
	public void syncWal() {
		// deliberately NOT taken under `walWriteLock`: the force is the expensive part of a commit and
		// holding that lock across it would re-serialize appends behind syncs, which is exactly what the
		// deferred-sync path exists to avoid. Exclusion against rotation and channel close lives inside
		// the WAL itself.
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		// a missing WAL must NOT be treated as "nothing to sync": the only caller is the durability
		// handshake, which asks for a force precisely because it has transactions it is about to declare
		// durable. Returning quietly here would acknowledge them without anything reaching the device -
		// the one failure mode of this design that corrupts silently instead of throwing
		Assert.isPremiseValid(
			theCatalogWal != null,
			"Cannot sync the WAL of catalog `" + this.catalogName + "` - it does not exist! " +
				"A sync was requested for transactions that were supposedly appended to it."
		);
		theCatalogWal.syncWal();
	}

	/**
	 * Appends the transaction to the shared WAL and discards the isolated WAL contents.
	 *
	 * The append is left written but not durable - see {@link #appendWalAndDiscardDeferringSync} for who owes the
	 * matching {@link #syncWal()}.
	 *
	 * @param catalogVersion       the catalog version the transaction is bound to
	 * @param transactionMutation  the leading transaction mutation
	 * @param walReference         the reference to the isolated WAL contents
	 * @return the length of the written WAL contents
	 */
	private long doAppendWalAndDiscard(
		long catalogVersion,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull LogRecordReference walReference
	) {
		if (walReference instanceof OffHeapWithFileBackupReference offHeapReference) {
			this.walWriteLock.lock();
			try {
				try (offHeapReference) {
					if (this.catalogWal == null) {
						final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = getCatalogHeader(
							catalogVersion);
						this.catalogWal = getCatalogWriteAheadLog(
							this.bootstrapUsed.catalogVersion(), this.catalogName, this.walFileNameProvider,
							this.catalogStoragePath, catalogHeader, this.walKryoPool,
							this.storageSettings,
							this.scheduler,
							this::advanceHistoryHorizon
						);
					}
					Assert.isPremiseValid(
						offHeapReference.getBuffer().isPresent() || offHeapReference.getFilePath().isPresent(),
						"Unexpected WAL reference - neither off-heap buffer nor file reference present!"
					);
					final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
					Assert.isPremiseValid(
						theCatalogWal != null,
						"Catalog WAL is unexpectedly not present!"
					);

					final LogFileRecordReference reference = theCatalogWal.appendDeferringSync(
						transactionMutation, offHeapReference
					);
					return Objects.requireNonNull(reference.fileLocation()).recordLength();
				}
			} finally {
				this.walWriteLock.unlock();
			}
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported WAL reference type: " + walReference.getClass() + "!",
				"Unsupported WAL reference type!"
			);
		}
	}

	@Nonnull
	@Override
	public Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(
		long catalogVersion
	) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return Optional.empty();
		} else {
			return theCatalogWal.getFirstNonProcessedTransaction(getCatalogHeader(catalogVersion).walFileReference())
				.map(TransactionMutationWithWalFileReference::transactionMutation);
		}
	}

	@Nonnull
	@Override
	public CatalogPersistenceService<LogFileRecordReference, CollectionFileReference, EntityCollectionFileHeader> replaceWith(
		long catalogVersion,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull DataStoreMemoryBuffer dataStoreMemoryBuffer,
		@Nonnull BiIntConsumer progressObserver
	) {
		final Path newPath = pathForCatalog(catalogNameToBeReplaced, this.storageSettings.storageDirectory());
		final boolean targetPathExists = newPath.toFile().exists();
		if (targetPathExists) {
			Assert.isPremiseValid(
				newPath.toFile().isDirectory(), () -> "Path `" + newPath.toAbsolutePath() + "` is not a directory!");
		}

		// store the catalog that replaces the original header
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = getCatalogHeader(
			catalogVersion);
		final long newCatalogVersion = catalogHeader.catalogState() == CatalogState.WARMING_UP ?
			0L : catalogHeader.version() + 1;

		// first changes and replace name of the catalog in the catalog schema in catalog that replaces the original
		CatalogSchemaStoragePart.serializeWithCatalogName(
			catalogNameToBeReplaced,
			catalogNameVariationsToBeReplaced,
			() -> {
				final CatalogSchemaStoragePart storagePart = new CatalogSchemaStoragePart(catalogSchema);
				storagePartPersistenceService.putStoragePart(newCatalogVersion, storagePart);
				return null;
			}
		);

		storagePartPersistenceService.writeCatalogHeader(
			STORAGE_PROTOCOL_VERSION,
			newCatalogVersion,
			newPath,
			catalogHeader.walFileReference(),
			catalogHeader.collectionFileIndex(),
			catalogHeader.catalogId(),
			catalogNameToBeReplaced,
			catalogHeader.catalogState(),
			catalogHeader.lastEntityCollectionPrimaryKey()
		);

		final int catalogIndex = this.bootstrapUsed.catalogFileIndex();
		recordBootstrap(
			newCatalogVersion,
			catalogNameToBeReplaced,
			catalogIndex,
			dataStoreMemoryBuffer
		);

		// close the catalog
		this.close();

		// name files in the directory that replaces the original first
		final File[] filesToRename = this.catalogStoragePath
			.toFile()
			.listFiles((dir, name) -> name.startsWith(this.catalogName));
		if (filesToRename != null) {
			for (int i = 0; i < filesToRename.length; i++) {
				File it = filesToRename[i];
				final Path filePath = it.toPath();
				final String fileNameToRename;
				if (it.getName().equals(getCatalogBootstrapFileName(this.catalogName))) {
					fileNameToRename = getCatalogBootstrapFileName(catalogNameToBeReplaced);
				} else if (it.getName().equals(getCatalogDataStoreFileName(this.catalogName, catalogIndex))) {
					fileNameToRename = getCatalogDataStoreFileName(catalogNameToBeReplaced, catalogIndex);
				} else {
					continue;
				}
				final Path filePathForRename = filePath.getParent().resolve(fileNameToRename);
				Assert.isPremiseValid(
					it.renameTo(filePathForRename.toFile()),
					() -> new GenericEvitaInternalError(
						"Failed to rename `" + it.getAbsolutePath() + "` to `" + filePathForRename.toAbsolutePath() + "`!",
						"Failed to rename one of the `" + this.catalogName + "` catalog files to target catalog name!"
					)
				);

				progressObserver.accept(i + 1, filesToRename.length);
			}
		} else {
			throw new GenericEvitaInternalError(
				"No file found in directory `" + this.catalogStoragePath.toAbsolutePath() + "`!",
				"Failed to rename catalog files to target catalog name!"
			);
		}

		final Path temporaryOriginal;
		if (targetPathExists) {
			temporaryOriginal = newPath.getParent().resolve(catalogNameToBeReplaced + "_renamed");
			Assert.isPremiseValid(
				newPath.toFile().renameTo(temporaryOriginal.toFile()),
				"Failed to rename original catalog directory `" + newPath.toAbsolutePath() + "`!"
			);
		} else {
			temporaryOriginal = null;
		}

		try {
			Assert.isPremiseValid(
				this.catalogStoragePath.toFile().renameTo(newPath.toFile()),
				"Failed to rename catalog directory `" + this.catalogStoragePath.toAbsolutePath() + "` to `" + newPath.toAbsolutePath() + "`!"
			);

			// finally remove original catalog contents
			ofNullable(temporaryOriginal)
				.ifPresent(FileUtils::deleteDirectory);

			return new DefaultCatalogPersistenceService(
				catalogNameToBeReplaced, this
			);
		} catch (RuntimeException ex) {
			// rename original directory back
			if (temporaryOriginal != null) {
				Assert.isPremiseValid(
					temporaryOriginal.toFile().renameTo(newPath.toFile()),
					() -> new GenericEvitaInternalError(
						"Failed to rename the original directory back to `" + newPath.toAbsolutePath() + "` the original catalog will not be available as well!",
						"Failing to rename the original directory back to the original catalog will not be available as well!",
						ex
					)
				);
			}
			throw ex;
		}
	}

	@Override
	@Nonnull
	public DefaultEntityCollectionPersistenceService replaceCollectionWith(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType
	) {
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = getCatalogHeader(
			catalogVersion);
		final CollectionFileReference replacedEntityTypeFileReference = catalogHeader.getEntityTypeFileIndexIfExists(
				entityType)
			.orElseThrow(
				() -> new CollectionNotFoundException(
					entityType));
		final CollectionFileReference newEntityTypeExistingFileReference = catalogHeader.getEntityTypeFileIndexIfExists(
				newEntityType)
			.orElseGet(
				() -> new CollectionFileReference(
					newEntityType,
					entityTypePrimaryKey,
					replacedEntityTypeFileReference.fileIndex() + 1,
					null
				));
		final CollectionFileReference newEntityTypeFileIndex = newEntityTypeExistingFileReference.incrementAndGet();
		final Path newFilePath = newEntityTypeFileIndex.toFilePath(this.catalogStoragePath);

		final DefaultEntityCollectionPersistenceService entityPersistenceService = this.entityCollectionPersistenceServices.get(
			new CollectionFileReference(
				entityType, entityTypePrimaryKey, replacedEntityTypeFileReference.fileIndex(), null)
		);
		Assert.isPremiseValid(
			entityPersistenceService != null,
			"Entity collection persistence service for `" + entityType + "` not found in catalog `" + this.catalogName + "`!"
		);

		final File newFile = newFilePath.toFile();
		final EntityCollectionFileHeader newEntityCollectionHeader;
		try {
			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(
				newFile.createNewFile(), "Cannot create new entity collection file: `" + newFilePath + "`!");
			try (final OutputStream fos = new BufferedOutputStream(
				new FileOutputStream(newFile), COMPACTION_OUTPUT_BUFFER_SIZE
			)) {
				newEntityCollectionHeader = entityPersistenceService.copySnapshotTo(
					catalogVersion, newEntityTypeFileIndex, fos, null);
			}
		} catch (RuntimeException | IOException ex) {
			// delete non-finished damaged file if exists
			if (newFile.exists()) {
				Assert.isPremiseValid(newFile.delete(), "Cannot remove unfinished file: `" + newFilePath + "`!");
			}
			if (ex instanceof RuntimeException runtimeException) {
				throw runtimeException;
			} else {
				throw new GenericEvitaInternalError(
					"Unexpected error during the entity collection renaming: " + ex.getMessage(),
					"Unexpected error during the entity collection renaming!",
					ex
				);
			}
		}

		final DefaultEntityCollectionPersistenceService renamedPersistenceService = this.entityCollectionPersistenceServices.compute(
			newEntityTypeFileIndex,
			(eType, oldValue) -> {
				Assert.isPremiseValid(
					oldValue == null,
					"Entity collection persistence service for `" + newEntityType + "` already exists in catalog `" + this.catalogName + "`!"
				);
				return createEntityCollectionPersistenceService(newEntityCollectionHeader);
			}
		);
		retireDataFile(
			catalogVersion - 1L,
			replacedEntityTypeFileReference.toFilePath(this.catalogStoragePath),
			() -> removeEntityCollectionPersistenceServiceAndClose(replacedEntityTypeFileReference)
		);
		return renamedPersistenceService;
	}

	@Override
	public void deleteEntityCollection(
		long catalogVersion,
		@Nonnull EntityCollectionFileHeader entityCollectionHeader
	) {
		final CollectionFileReference collectionFileReference = new CollectionFileReference(
			entityCollectionHeader.entityType(),
			entityCollectionHeader.entityTypePrimaryKey(),
			entityCollectionHeader.entityTypeFileIndex(),
			entityCollectionHeader.fileLocation()
		);
		retireDataFile(
			catalogVersion - 1L,
			collectionFileReference.toFilePath(this.catalogStoragePath),
			() -> removeEntityCollectionPersistenceServiceAndClose(collectionFileReference)
		);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return Stream.empty();
		} else {
			return theCatalogWal.getCommittedMutationStream(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return Stream.empty();
		} else {
			// Honour the requested starting version - the argument used to be discarded, which contradicted both this
			// method's own contract and its engine-side counterpart
			// (`DefaultEnginePersistenceService#getReversedCommittedMutationStream`), and made every caller asking for
			// history from an older version walk the whole write-ahead log back from the head instead. The result was
			// still correct because the change-capture predicate filters on the same version afterwards; only the
			// amount of log scanned was wrong.
			//
			// When no version is requested the walk starts at the newest APPLIED version rather than the newest
			// checkpointed one: with a checkpoint interval configured the latter lags by up to a whole interval, and
			// starting there would hide every mutation committed since. The two coincide when every round
			// checkpoints.
			return theCatalogWal.getCommittedReversedMutationStream(
				catalogVersion == null ? getLastAppliedCatalogVersion() : catalogVersion
			);
		}
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedLiveMutationStream(
		long startCatalogVersion, long requestedCatalogVersion) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return Stream.empty();
		} else {
			return theCatalogWal.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(
				startCatalogVersion, requestedCatalogVersion
			);
		}
	}

	@Override
	public long getLastCatalogVersionInMutationStream() {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return 0L;
		} else {
			return theCatalogWal.getLastWrittenVersion();
		}
	}

	@Override
	public long getFirstCatalogVersionInMutationStream() {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return -1L;
		} else {
			return theCatalogWal.getFirstVersionOfCurrentWalFile();
		}
	}

	@Override
	public void forgetVolatileData() {
		this.catalogStoragePartPersistenceService.values()
			.forEach(OffsetIndexStoragePartPersistenceService::forgetVolatileData);
		for (DefaultEntityCollectionPersistenceService collectionPersistenceServices : this.entityCollectionPersistenceServices.values()) {
			collectionPersistenceServices.getStoragePartPersistenceService().forgetVolatileData();
		}
	}

	@Nonnull
	@Override
	public PaginatedList<MaterializedVersionBlock> getCatalogVersions(
		@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		final Path bootstrapFilePath = this.catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final int recordCount = CatalogBootstrap.getRecordCount(length);
			final int pageNumber = PaginatedList.isRequestedResultBehindLimit(page, pageSize, recordCount) ? 1 : page;
			try (
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
					bootstrapFilePath, this.bootstrapStorageSettings, this.bootstrapStorageSettings
				)
			) {
				final List<MaterializedVersionBlock> materializedVersionBlocks = new ArrayList<>(pageSize);
				if (timeFlow == TimeFlow.FROM_OLDEST_TO_NEWEST) {
					final int firstNumber = PaginatedList.getFirstItemNumberForPage(pageNumber, pageSize);
					CatalogBootstrap previousBootstrap = null;
					for (int i = Math.max(0, firstNumber - 1); i < Math.min(firstNumber + pageSize, recordCount); i++) {
						final CatalogBootstrap currentBootstrap = deserializeCatalogBootstrapRecord(
							CatalogBootstrap.getPositionForRecord(i), readHandle
						);
						if (i == 0) {
							materializedVersionBlocks.add(
								new MaterializedVersionBlock(
									resolveBlockStartVersionOf(currentBootstrap),
									currentBootstrap.catalogVersion(),
									currentBootstrap.timestamp()
								)
							);
						} else {
							if (previousBootstrap != null) {
								materializedVersionBlocks.add(
									new MaterializedVersionBlock(
										Math.min(
											currentBootstrap.catalogVersion(), previousBootstrap.catalogVersion() + 1),
										currentBootstrap.catalogVersion(),
										currentBootstrap.timestamp()
									)
								);
							}
						}
						previousBootstrap = currentBootstrap;
					}
				} else {
					final int firstNumber = recordCount - (((pageNumber - 1) * pageSize) + 1);
					CatalogBootstrap nextBootstrap = null;
					for (int i = firstNumber; i >= Math.max(firstNumber - pageSize, -1); i--) {
						if (i == -1) {
							if (nextBootstrap != null) {
								materializedVersionBlocks.add(
									new MaterializedVersionBlock(
										resolveBlockStartVersionOf(nextBootstrap),
										nextBootstrap.catalogVersion(),
										nextBootstrap.timestamp()
									)
								);
							}
						} else {
							final CatalogBootstrap currentBootstrap = deserializeCatalogBootstrapRecord(
								CatalogBootstrap.getPositionForRecord(i), readHandle
							);
							if (nextBootstrap != null) {
								materializedVersionBlocks.add(
									new MaterializedVersionBlock(
										currentBootstrap.catalogVersion() + 1,
										nextBootstrap.catalogVersion(),
										nextBootstrap.timestamp()
									)
								);
							}
							nextBootstrap = currentBootstrap;
						}
					}
				}
				return new PaginatedList<>(
					pageNumber,
					pageSize,
					recordCount,
					materializedVersionBlocks
				);
			} catch (Exception e) {
				throw new UnexpectedIOException(
					"Failed to open catalog bootstrap file `" + bootstrapFile.getAbsolutePath() + "`!",
					"Failed to open catalog bootstrap file!",
					e
				);
			}
		} else {
			return PaginatedList.emptyList();
		}
	}

	@Nonnull
	@Override
	public MaterializedVersionBlock getFirstCatalogVersionAfter(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException {
		return getCatalogVersionAt(moment, 0);
	}

	@Override
	public MaterializedVersionBlock getLastCatalogVersionBefore(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException {
		return getCatalogVersionAt(moment, -1);
	}

	@Nonnull
	@Override
	public List<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (catalogVersion.length == 0 || theCatalogWal == null) {
			return Collections.emptyList();
		}
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		final Path bootstrapFilePath = this.catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			// try to resolve stored versions for all requested catalog versions
			final List<MaterializedVersionBlock> storedVersions = createMaterializedVersionBlocks(
				catalogVersion
			);
			final LongSet lookedUpVersions = new LongHashSet(catalogVersion.length);
			Arrays.stream(catalogVersion).forEach(lookedUpVersions::add);

			final WriteAheadLogVersionDescriptor[] result = new WriteAheadLogVersionDescriptor[catalogVersion.length];
			for (MaterializedVersionBlock materializedVersionBlock : storedVersions) {
				final List<WriteAheadLogVersionDescriptor> descriptors = theCatalogWal.getWriteAheadLogVersionDescriptor(
					lookedUpVersions,
					materializedVersionBlock
				);
				for (WriteAheadLogVersionDescriptor descriptor : descriptors) {
					// not optimal, but we don't expect many versions to be requested at once
					result[ArrayUtils.indexOf(descriptor.version(), catalogVersion)] = descriptor;
				}
				descriptors.forEach(it -> lookedUpVersions.removeAll(it.version()));
			}

			// versions unknown to history (purged, or never committed) leave their slot in `result` null - the
			// documented contract is to omit them, not to hand callers a positionally-aligned array full of holes
			return Arrays.stream(result)
				.filter(Objects::nonNull)
				.toList();
		} else {
			return Collections.emptyList();
		}
	}

	@Override
	public void purgeAllObsoleteFiles() {
		try {
			final CatalogBootstrap catalogBootstrap = this.storageSettings.timeTravelEnabled() ?
				// if time travel is enabled we need to keep all the files that are referenced in the bootstrap file
				getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageSettings).orElse(this.bootstrapUsed) :
				// otherwise we can remove all the files that are not referenced in the current catalog header
				this.bootstrapUsed;

			final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = fetchCatalogHeader(
				catalogBootstrap);
			final Pattern catalogDataFilePattern = CatalogPersistenceService.getCatalogDataStoreFileNamePattern(
				this.catalogName);
			final File[] filesToDelete = Objects.requireNonNull(
				this.catalogStoragePath.toFile()
					.listFiles((dir, name) -> {
						// bootstrap file is never removed
						if (name.equals(getCatalogBootstrapFileName(this.catalogName))) {
							return false;
						}
						// WAL is never removed
						if (name.endsWith(WAL_FILE_SUFFIX)) {
							return false;
						}
						// actual catalog data file is not removed
						final Matcher catalogFileMatcher = catalogDataFilePattern.matcher(name);
						if (catalogFileMatcher.matches() && Integer.parseInt(
							catalogFileMatcher.group(1)) >= catalogBootstrap.catalogFileIndex()) {
							return false;
						}
						// collection data files are not removed if they are referenced in the current catalog header
						if (name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX)) {
							final EntityTypePrimaryKeyAndFileIndex parsedName = CatalogPersistenceService.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(
								name);
							return catalogHeader.getEntityTypeFileIndexes()
								.stream()
								.filter(
									it -> parsedName.entityTypePrimaryKey() == it.entityTypePrimaryKey())
								.map(it -> parsedName.fileIndex() < it.fileIndex())
								.findAny().orElse(false);
						}
						// all other files are removed
						return true;
					})
			);
			// delete and inform
			if (filesToDelete.length > 0) {
				log.info(
					"Purging obsolete files for catalog `{}`: {}",
					this.catalogName,
					Arrays.stream(filesToDelete).map(File::getName).collect(Collectors.joining(", "))
				);
				for (File file : filesToDelete) {
					Assert.isPremiseValid(file.delete(), "Failed to delete file `" + file.getAbsolutePath() + "`!");
				}
			}
		} catch (Exception ex) {
			log.warn(
				"Failed to purge obsolete files for catalog `{}`: {}",
				this.catalogName,
				ex.getMessage(),
				ex
			);
		}
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> createBackupTask(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) throws TemporalDataNotAvailableException {
		// A backup is only as recent as the bootstrap record it copies, and it may be taken WITHOUT the write-ahead
		// log - in which case that record is the sole pointer to the data and a stale one silently yields an older
		// catalog rather than a broken one. Settle any outstanding checkpoint first. This also makes an explicitly
		// requested `catalogVersion` that was committed moments ago resolvable, instead of reading as not yet
		// existing.
		checkpoint();
		final CatalogBootstrap bootstrapRecord;
		if (catalogVersion != null) {
			bootstrapRecord = getCatalogBootstrapForSpecificVersion(
				this.catalogName, this.bootstrapStorageSettings, catalogVersion
			);
		} else if (pastMoment != null) {
			bootstrapRecord = getCatalogBootstrapForSpecificMoment(
				this.catalogName, this.bootstrapStorageSettings, pastMoment
			);
		} else {
			bootstrapRecord = this.bootstrapUsed;
		}
		return new BackupTask(
			this.catalogName, pastMoment, catalogVersion, includingWAL,
			bootstrapRecord, this.exportService, this,
			onStart, onComplete
		);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> createFullBackupTask(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		// same reasoning as createBackupTask - the task captures the last checkpointed version on construction
		checkpoint();
		return new FullBackupTask(
			this.catalogName,
			this.exportService,
			this,
			onStart, onComplete
		);
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> duplicateCatalog(
		@Nonnull String targetCatalogName,
		@Nonnull StorageOptions storageOptions
	) throws DirectoryNotEmptyException, InvalidStoragePathException {
		final Path targetFolder = pathForCatalog(targetCatalogName, storageOptions.storageDirectory());

		// verify target folder does not exist or is empty, create it
		verifyDirectory(targetFolder, true);

		// collect all file paths into a collection and sort them
		final List<FileInfo> filesToCopy;
		try (Stream<Path> files = Files.walk(this.catalogStoragePath)) {
			filesToCopy = files
				.filter(Files::isRegularFile)
				.map(path -> {
					try {
						final String fileName = path.getFileName().toString();
						final String suffix = FileUtils.getFileExtension(fileName).orElse(null);
						final Integer suffixPriority = suffix == null ? null : ALLOWED_SUFFIXES_WITH_PRIORITY.get(
							"." + suffix);
						return suffixPriority == null ?
							null :
							new FileInfo(
								path,
								suffix,
								suffixPriority,
								Files.readAttributes(path, BasicFileAttributes.class)
									.creationTime()
									.toInstant()
							);
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to read file attributes for `" + path + "`: " + e.getMessage(),
							"Failed to read file attributes!",
							e
						);
					}
				})
				.filter(Objects::nonNull)
				.sorted(
					Comparator.comparing(FileInfo::suffixPriority)
						.thenComparing(FileInfo::creationTime))
				.toList();
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to collect files in source catalog directory: " + e.getMessage(),
				"Failed to collect files in source catalog directory!",
				e
			);
		}

		return new ProgressingFuture<>(
			filesToCopy.size(),
			progressingFuture -> {
				try {
					int copiedFiles = 0;

					// iterate over prepared list of paths and copy files with catalog rename
					for (final FileInfo fileInfo : filesToCopy) {
						final Path sourceFile = fileInfo.path();
						final String originalFileName = sourceFile.getFileName().toString();
						final String targetFileName = getFileNameWithCatalogRename(
							originalFileName, targetCatalogName
						);
						final Path targetFile = targetFolder.resolve(targetFileName);

						Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
						progressingFuture.updateProgress(++copiedFiles);
					}

					// create restoration flag, so that the schema name is adapted automatically after activation
					Assert.isPremiseValid(
						targetFolder.resolve(RESTORE_FLAG).toFile().createNewFile(),
						() -> new UnexpectedIOException(
							"Unexpected exception occurred while duplicating catalog " + targetCatalogName + ": unable to create restore flag file!",
							"Unexpected exception occurred while duplicating catalog - unable to create restore flag file!"
						)
					);

					return null;
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to duplicate catalog: " + e.getMessage(),
						"Failed to duplicate catalog!",
						e
					);
				}
			}
		);
	}

	@Override
	public void verifyIntegrity() {
		// both assertions below are "the catalog is fully checkpointed" assertions, and callers follow this method
		// with `purgeAllObsoleteFiles()` - so settle any outstanding checkpoint first. Purging write-ahead log files
		// covering versions whose bootstrap record was never written would throw away the only record of them.
		checkpoint();
		Assert.isPremiseValid(
			getCatalogHeader(this.bootstrapUsed.catalogVersion()).version() == this.bootstrapUsed.catalogVersion(),
			"Catalog version mismatch! Expected `" + this.bootstrapUsed.catalogVersion() + "` but found `" + getCatalogHeader(
				this.bootstrapUsed.catalogVersion()).version() + "`!"
		);
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal != null) {
			Assert.isPremiseValid(
				theCatalogWal.getLastWrittenVersion() == this.bootstrapUsed.catalogVersion(),
				"Catalog WAL version mismatch! Expected `" + this.bootstrapUsed.catalogVersion() + "` but found `" + theCatalogWal.getLastWrittenVersion() + "`!"
			);
		}
	}

	@Override
	public long getSizeOnDiskInBytes() {
		return FileUtils.getDirectorySize(this.catalogStoragePath);
	}

	/**
	 * Releases the subset of resources that the load ctor has already opened by the time
	 * {@link #verifyAndUpgradeStorageFormat} is invoked. Invoked from the ctor catch block when
	 * that call (or anything after it, before field init is complete) throws — so the retry loop
	 * driven by {@link CatalogRequiresUpgradeException} does not leak file handles per cycle.
	 *
	 * Cannot reuse the full {@link #close()} because some final fields (such as
	 * {@code entityCollectionPersistenceServices}) are assigned only after the risky section and
	 * would trip a NullPointerException on early failure.
	 */
	@SuppressWarnings("ConstantValue")
	private void closeOnConstructorFailure() {
		IOUtils.closeQuietly(
			this.offHeapMemoryManager::close,
			this.obsoleteFileMaintainer::close,
			this.observableOutputKeeper::close
		);
		final BootstrapWriteOnlyFileHandle bootstrapWriteOnlyFileHandle =
			this.bootstrapWriteHandle != null ? this.bootstrapWriteHandle.get() : null;
		if (bootstrapWriteOnlyFileHandle != null) {
			IOUtils.closeQuietly(bootstrapWriteOnlyFileHandle::close);
		}
		if (this.catalogStoragePartPersistenceService != null) {
			IOUtils.closeQuietly(
				this.catalogStoragePartPersistenceService
					.values()
					.stream()
					.map(service -> (ExceptionThrowingRunnable) service::close)
					.toArray(ExceptionThrowingRunnable[]::new)
			);
			this.catalogStoragePartPersistenceService.clear();
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			// the guard only ever reads and reclaims history - a run in flight during shutdown has nothing to settle
			if (this.timeTravelSizeGuardTask != null) {
				IOUtils.closeQuietly(this.timeTravelSizeGuardTask::close);
			}
			// stop the ticker and settle the debt before anything is torn down: no service may go away still owing
			// a device flush, or the bytes it wrote would depend on the operating system getting round to them.
			// The bootstrap record is deliberately NOT written here - it is a checkpoint pointer, and leaving it at
			// the last checkpoint simply means restart resumes from there and replays the rest from the WAL.
			if (this.checkpointCoordinator != null) {
				try {
					this.checkpointCoordinator.forcePendingSyncs();
				} catch (RuntimeException ex) {
					// deliberately NOT quiet: shutdown must not be aborted by this, but a final device flush that
					// failed means bytes this service wrote may never reach the disk, and an operator who is never
					// told cannot know that the catalog needs its write-ahead log to come back whole
					log.error(
						"Final device flush of catalog `{}` failed - data written since the last checkpoint may not " +
							"have reached the disk and will have to be replayed from the write-ahead log!",
						this.catalogName, ex
					);
				}
				IOUtils.closeQuietly(this.checkpointCoordinator::close);
			}
			// close WAL
			final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
			if (theCatalogWal != null) {
				this.walWriteLock.lock();
				try {
					IOUtils.closeQuietly(theCatalogWal::close);
				} finally {
					this.walWriteLock.unlock();
				}
			}
			// close all services
			IOUtils.closeQuietly(
				this.entityCollectionPersistenceServices
					.values()
					.stream()
					.map(service -> (ExceptionThrowingRunnable) service::close)
					.toArray(ExceptionThrowingRunnable[]::new)
			);
			this.entityCollectionPersistenceServices.clear();
			// close current file offset index
			IOUtils.closeQuietly(
				this.catalogStoragePartPersistenceService
					.values()
					.stream()
					.map(service -> (ExceptionThrowingRunnable) service::close)
					.toArray(ExceptionThrowingRunnable[]::new)
			);
			this.catalogPersistenceServiceVersions = ArrayUtils.EMPTY_LONG_ARRAY;
			this.catalogStoragePartPersistenceService.clear();
			// close bootstrap file
			final BootstrapWriteOnlyFileHandle bootstrapWriteOnlyFileHandle = this.bootstrapWriteHandle.get();
			if (bootstrapWriteOnlyFileHandle != null) {
				IOUtils.closeQuietly(
					bootstrapWriteOnlyFileHandle::close
				);
			}
			// close off heap manager, maintainer and observable output keeper
			IOUtils.closeQuietly(
				// close off heap manager
				this.offHeapMemoryManager::close,
				// purge obsolete files
				this.obsoleteFileMaintainer::close,
				// close observable output keeper
				this.observableOutputKeeper::close
			);
		}
	}

	@Override
	public void catalogConsumersLeft(
		long lastKnownMinimalActiveVersionRead,
		long lastKnownMinimalActiveVersionWritten
	) {
		final long lastKnownMinimalActiveVersion = Math.min(
			lastKnownMinimalActiveVersionRead,
			lastKnownMinimalActiveVersionWritten
		);
		this.catalogStoragePartPersistenceService.values().forEach(
			it -> it.purgeHistoryOlderThan(lastKnownMinimalActiveVersion));
		this.obsoleteFileMaintainer.catalogConsumersLeft(
			lastKnownMinimalActiveVersionRead,
			lastKnownMinimalActiveVersionWritten
		);
		this.entityCollectionPersistenceServices.values()
			.forEach(
				it -> it.catalogConsumersLeft(
					lastKnownMinimalActiveVersionRead,
					lastKnownMinimalActiveVersionWritten
				)
			);
	}

	@Override
	public void catalogVersionPinned(long catalogVersion) {
		// taken under the horizon lock so that a pin and a horizon advance can never interleave: either the pin lands
		// first and `advanceHistoryHorizon` observes it when it samples the retention floor, or the advance completes
		// first and the caller - which must re-verify its version is still reachable once the pin is in place - sees
		// that it lost. A pin registered outside this lock can be taken against a version whose files are being
		// deleted at that very moment, which is how a point-in-time backup loses the data underneath it.
		// The lock is uncontended unless time travel is enabled, which is opt-in and off by default.
		this.historyHorizonLock.lock();
		try {
			this.obsoleteFileMaintainer.catalogVersionPinned(catalogVersion);
		} finally {
			this.historyHorizonLock.unlock();
		}
	}

	/**
	 * Returns the catalog version of the oldest bootstrap record still retained on disk - the lower end of the window
	 * time travel can currently reach. Read under the horizon lock, because the driver that trims history replaces the
	 * very file this reads.
	 *
	 * A consumer that needs the whole retained window rather than one particular version - a full backup copies every
	 * file in the catalog folder - pins this value for its lifetime, which clamps
	 * {@link WalPurgeCallback#effectivePurgeVersion(long)} down to it and so freezes reclamation entirely until the pin
	 * is released. Reading it a moment before the pin is taken errs in the safe direction: the horizon only ever rises,
	 * so a stale value can only be lower than the truth, and pinning lower merely holds back more than necessary.
	 *
	 * @return the oldest retained catalog version, or {@link #getLastCatalogVersion()} when no bootstrap record can be
	 * read at all
	 */
	/**
	 * Returns the lowest catalog version any consumer still holds - the floor every reclamation is clamped to. Zero
	 * when nothing is held at all.
	 *
	 * Package-private so a test can assert that a pin was taken and released without reaching into the maintainer.
	 *
	 * @return the current retention floor
	 */
	long getRetentionFloor() {
		return this.obsoleteFileMaintainer.getRetentionFloor();
	}

	public long getOldestRetainedCatalogVersion() {
		this.historyHorizonLock.lock();
		try {
			return getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageSettings)
				.map(CatalogBootstrap::catalogVersion)
				.orElseGet(this::getLastCatalogVersion);
		} finally {
			this.historyHorizonLock.unlock();
		}
	}

	@Override
	public void catalogVersionReleased(long catalogVersion) {
		this.obsoleteFileMaintainer.catalogVersionReleased(catalogVersion);
		// retry whatever the floor refused while this pin was held. If another pin still holds it back the retry
		// simply records it again, so this converges rather than losing the request on the first release
		final long owedRequest = this.pendingHistoryHorizonRequest.getAndSet(-1L);
		if (owedRequest > -1L) {
			advanceHistoryHorizon(owedRequest);
		}
		// a released pin can only lower what is in use, so the budget may now be able to give up what it deferred
		scheduleTimeTravelSizeGuard();
	}

	@Override
	public boolean isNew() {
		// if the service is new (not yet stored) there should be only one value in the map
		return this.catalogStoragePartPersistenceService.values()
			.stream()
			.anyMatch(OffsetIndexStoragePartPersistenceService::isNew);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void flushTrappedUpdates(
		long catalogVersion,
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull IntConsumer trappedUpdatedProgress
	) {
		final int[] counter = {0};
		final int division = Math.max(200, trappedChanges.getTrappedChangesCount() / 100);

		// now store all the entity trapped updates
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart storagePart = it.next();
			if (storagePart instanceof RemovedStoragePart removedStoragePart) {
				storagePartPersistenceService.removeStoragePart(
					catalogVersion,
					removedStoragePart.getStoragePartPKOrElseThrowException(),
					removedStoragePart.containerType()
				);
			} else if (storagePart instanceof DeferredRemovalStoragePart deferredRemoval) {
				// a removal whose primary key can only be resolved store-side (e.g. a freed granular
				// GlobalUniqueIndex leaf page whose streamId is a compressor dictionary id) — resolve it against the
				// live compressor and remove it. The read-only view suffices: the stream was registered when the page
				// was first written. Mirrors the entity-collection flush drain.
				final long removedPartPK = deferredRemoval.computeUniquePartIdAndSet(
					storagePartPersistenceService.getReadOnlyKeyCompressor()
				);
				storagePartPersistenceService.removeStoragePart(
					catalogVersion, removedPartPK, deferredRemoval.removedContainerType()
				);
			} else {
				storagePartPersistenceService.putStoragePart(catalogVersion, storagePart);
			}

			// Increment the counter and update progress every X items
			if (++counter[0] % division == 0) {
				trappedUpdatedProgress.accept(counter[0]);
			}
		}

		// Final progress update if there are remaining items
		if (counter[0] % division != 0) {
			trappedUpdatedProgress.accept(counter[0]);
		}
	}

	/**
	 * Creates new instance of the catalog offset index storage part persistence service for the specified catalog
	 * version (header).
	 *
	 * @param catalogBootstrap the catalog header
	 * @return the new instance of the catalog offset index storage part persistence service
	 */
	@Nonnull
	public CatalogOffsetIndexStoragePartPersistenceService createCatalogOffsetIndexStoragePartService(
		@Nonnull CatalogBootstrap catalogBootstrap
	) {
		return CatalogOffsetIndexStoragePartPersistenceService.create(
			this.catalogName,
			this.catalogStoragePath.resolve(
				getCatalogDataStoreFileName(this.catalogName, catalogBootstrap.catalogFileIndex())),
			this.storageSettings,
			catalogBootstrap,
			this.recordTypeRegistry,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			VERSIONED_KRYO_FACTORY,
			nonFlushedBlock -> this.reportNonFlushedContents(this.catalogName, nonFlushedBlock),
			oldestRecordTimestamp -> reportOldestHistoricalRecord(this.catalogName, oldestRecordTimestamp.orElse(null)),
			this.checkpointCoordinator
		);
	}

	/**
	 * Creates new instance of the entity collection persistence service for the specified entity type.
	 *
	 * @param entityCollectionHeader the entity collection header
	 * @return the new instance of the entity collection persistence service
	 */
	@Nonnull
	public DefaultEntityCollectionPersistenceService createEntityCollectionPersistenceService(
		@Nonnull EntityCollectionFileHeader entityCollectionHeader
	) {
		return new DefaultEntityCollectionPersistenceService(
			this.bootstrapUsed.catalogVersion(),
			this.catalogName,
			this.catalogStoragePath,
			entityCollectionHeader,
			this.storageSettings,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			this.recordTypeRegistry,
			this.checkpointCoordinator
		);
	}

	/**
	 * Creates and returns a new Checksum instance based on the storage settings.
	 *
	 * @return a non-null Checksum object configured according to the storage settings
	 */
	@Nonnull
	public Checksum createChecksum() {
		return this.storageSettings.createChecksum();
	}

	/**
	 * Creates the coordinator that defers the data file device flush to an interval, or returns null when every
	 * round is to checkpoint on its own.
	 *
	 * There is nothing to defer when {@link StorageOptions#syncWrites()} is off - the writes never reach the device
	 * on that setting anyway - so the two options stay orthogonal instead of one silently re-enabling the other.
	 *
	 * @param catalogName     name of the catalog, for observability
	 * @param storageSettings settings carrying both the interval and the sync-writes switch
	 * @param scheduler       scheduler used to arm the ticker
	 * @return the coordinator, or null to checkpoint at the end of every round
	 */
	@Nullable
	private CheckpointCoordinator createCheckpointCoordinator(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Scheduler scheduler
	) {
		final long checkpointInterval = storageSettings.checkpointIntervalInMillis();
		return checkpointInterval > 0 && storageSettings.syncWrites() ?
			new CheckpointCoordinator(
				catalogName, checkpointInterval, scheduler, this.checkpointLock, this::performDeferredCheckpoint
			) :
			null;
	}

	/**
	 * Makes everything written so far durable and writes the bootstrap record that points at it, if a checkpoint is
	 * still owed. Does nothing when checkpointing happens at the end of every round.
	 *
	 * Callers are the operations that need the on-disk catalog to be **self-sufficient** rather than merely
	 * crash-consistent - notably a backup, which may be taken without the write-ahead log, leaving the bootstrap
	 * record as the only pointer to the data. A stale pointer there does not produce a corrupt backup; it produces a
	 * silently **older** one, which is worse for being plausible.
	 */
	public void checkpoint() {
		if (this.checkpointCoordinator != null) {
			this.checkpointCoordinator.checkpointIfOwed();
		}
	}

	/**
	 * Publishes the checkpoint a previous round deferred: writes the bootstrap record that round already built and
	 * lets the write-ahead log know it may stop retaining everything up to it.
	 *
	 * Deliberately does **nothing** to the catalog offset index. The round that deferred left the index at its own
	 * version and built the matching record; all that is left here is to make the bytes durable - which happens at
	 * the fence inside {@link #writeCatalogBootstrap} - and publish the pointer. That is what makes this callable
	 * from the ticker or a backup thread while another round is in flight.
	 *
	 * Runs under the coordinator's lock, and only when a checkpoint is genuinely owed.
	 */
	private void performDeferredCheckpoint() {
		final CatalogBootstrap preparedBootstrap = Objects.requireNonNull(
			this.deferredCheckpointBootstrap,
			"A checkpoint is owed but no bootstrap record was prepared for it!"
		);
		final long catalogVersion = preparedBootstrap.catalogVersion();
		this.bootstrapUsed = writeCatalogBootstrap(catalogVersion, this.catalogName, preparedBootstrap);
		this.deferredCheckpointBootstrap = null;
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal != null) {
			theCatalogWal.walProcessedUntil(catalogVersion);
		}
		Objects.requireNonNull(this.checkpointCoordinator).noteCheckpointCompleted();
	}

	/**
	 * Records a bootstrap in the catalog.
	 *
	 * @param catalogVersion        the version of the catalog
	 * @param newCatalogName        the name of the new catalog
	 * @param catalogFileIndex      the index of the catalog file
	 * @param dataStoreMemoryBuffer the data store memory buffer
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		int catalogFileIndex,
		@Nullable DataStoreMemoryBuffer dataStoreMemoryBuffer
	) {
		return recordBootstrap(
			catalogVersion, newCatalogName, catalogFileIndex, getNowEpochMillis(), dataStoreMemoryBuffer);
	}

	/**
	 * Records a bootstrap in the catalog: builds the record and immediately writes it.
	 *
	 * Callers that must not publish the pointer yet - a trunk round running under a checkpoint interval - use
	 * {@link #prepareBootstrap} and {@link #writeCatalogBootstrap} separately instead. Everything ordering-sensitive
	 * lives in the former, so it has to run on the thread that wrote the storage parts being pointed at.
	 *
	 * @param catalogVersion        the version of the catalog
	 * @param newCatalogName        the name of the new catalog
	 * @param catalogFileIndex      the index of the catalog file
	 * @param timestamp             the timestamp of the boot record
	 * @param dataStoreMemoryBuffer the data store memory buffer
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		int catalogFileIndex,
		long timestamp,
		@Nullable DataStoreMemoryBuffer dataStoreMemoryBuffer
	) {
		return writeCatalogBootstrap(
			catalogVersion,
			newCatalogName,
			prepareBootstrap(
				catalogVersion, newCatalogName, catalogFileIndex, timestamp, dataStoreMemoryBuffer)
		);
	}

	/**
	 * Builds the bootstrap record for the given version **without publishing it**: advances the catalog offset index
	 * to that version and, when the thresholds say so, compacts the catalog data file first.
	 *
	 * **This must run on the thread that wrote the storage parts the record will address.** Promotion asserts that the
	 * version being flushed is at least the newest one present in the index, so a thread doing this for version `V`
	 * while a round is midway through writing `V+1` fails that assertion - and would otherwise have produced a record
	 * naming `V` while addressing `V+1`. Deferring the *write* of the record is safe; deferring this is not.
	 *
	 * @param catalogVersion        the version of the catalog
	 * @param newCatalogName        the name of the new catalog
	 * @param catalogFileIndex      the index of the catalog file to build upon
	 * @param timestamp             the timestamp of the boot record
	 * @param dataStoreMemoryBuffer the data store memory buffer
	 * @return the bootstrap record, not yet written to the bootstrap file
	 */
	@Nonnull
	private CatalogBootstrap prepareBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		int catalogFileIndex,
		long timestamp,
		@Nullable DataStoreMemoryBuffer dataStoreMemoryBuffer
	) {
		final OffsetDateTime bootstrapWriteTime = Instant.ofEpochMilli(timestamp)
			.atZone(ZoneId.systemDefault())
			.toOffsetDateTime();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final OffsetIndexDescriptor flushedDescriptor = storagePartPersistenceService.flush(catalogVersion);
		final CatalogBootstrap bootstrapRecord;
		final boolean catalogIntervalElapsed = isCompactionIntervalElapsed(
			getNowEpochMillis(), this.lastCatalogCompactionAtMillis, this.storageSettings.minCompactionIntervalMilliseconds()
		);
		if (shouldCompact(
			flushedDescriptor.getFileSize() > this.storageSettings.fileSizeCompactionThresholdBytes(),
			flushedDescriptor.getActiveRecordShare(),
			this.storageSettings.minimalActiveRecordShare(),
			this.storageSettings.maxWasteActiveShare(),
			catalogIntervalElapsed
		)) {

			final DataFileCompactEvent event = new DataFileCompactEvent(
				this.catalogName,
				FileType.CATALOG,
				this.catalogName
			);

			final int newCatalogFileIndex = catalogFileIndex + 1;
			final String compactedFileName = getCatalogDataStoreFileName(newCatalogName, newCatalogFileIndex);
			final OffsetIndexDescriptor compactedDescriptor;
			try (
				final FileOutputStream compactedFileStream = new FileOutputStream(
					this.catalogStoragePath.resolve(compactedFileName).toFile()
				);
				final OutputStream fos = new BufferedOutputStream(
					compactedFileStream, COMPACTION_OUTPUT_BUFFER_SIZE
				)
			) {
				compactedDescriptor = storagePartPersistenceService.copySnapshotTo(catalogVersion, fos, null);
				// The bootstrap record built right below points into this file and is itself fsynced. Closing a
				// BufferedOutputStream only pushes its buffer into the page cache, so without this the durable
				// pointer could outlive the data it addresses: a crash here would leave recovery following a
				// committed bootstrap record into a file that was never written.
				fos.flush();
				if (this.storageSettings.syncWrites()) {
					compactedFileStream.getFD().sync();
				}
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Error occurred while compacting catalog data file: " + e.getMessage(),
					"Error occurred while compacting catalog data file.",
					e
				);
			}
			bootstrapRecord = new CatalogBootstrap(
				catalogVersion,
				newCatalogFileIndex,
				bootstrapWriteTime,
				compactedDescriptor.fileLocation()
			);

			try {
				this.cpsvLock.lockInterruptibly();

				final long currentVersion = this.catalogPersistenceServiceVersions[this.catalogPersistenceServiceVersions.length - 1];
				final CatalogOffsetIndexStoragePartPersistenceService newPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
					this.catalogName,
					this.catalogStoragePath.resolve(compactedFileName),
					this.storageSettings,
					bootstrapRecord,
					this.recordTypeRegistry,
					this.offHeapMemoryManager,
					this.observableOutputKeeper,
					VERSIONED_KRYO_FACTORY,
					nonFlushedBlock -> this.reportNonFlushedContents(this.catalogName, nonFlushedBlock),
					oldestRecordTimestamp -> DefaultCatalogPersistenceService.reportOldestHistoricalRecord(
						this.catalogName, oldestRecordTimestamp.orElse(null)),
					this.checkpointCoordinator
				);
				final CatalogOffsetIndexStoragePartPersistenceService previousService = this.catalogStoragePartPersistenceService.put(
					catalogVersion,
					newPersistenceService
				);
				if (previousService != null) {
					previousService.close();
					if (catalogVersion == 0) {
						this.warmUpVersionCardinality++;
					} else {
						throw new GenericEvitaInternalError(
							"Persistence storage instance is unexpectedly already registered!",
							"Persistence storage instance for version `" + catalogVersion + "` is unexpectedly already registered!"
						);
					}
				} else {
					this.catalogPersistenceServiceVersions = ArrayUtils.insertLongIntoOrderedArray(
						catalogVersion, this.catalogPersistenceServiceVersions);
				}

				retireDataFile(
					catalogVersion,
					this.catalogStoragePath.resolve(getCatalogDataStoreFileName(newCatalogName, catalogFileIndex)),
					() -> removeCatalogPersistenceServiceForVersion(currentVersion)
				);

				if (dataStoreMemoryBuffer instanceof WarmUpDataStoreMemoryBuffer warmUpDataStoreMemoryBuffer) {
					warmUpDataStoreMemoryBuffer.setPersistenceService(newPersistenceService);
				}

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new GenericEvitaInternalError(
					"Failed to lock the catalog persistence service for catalog `" + this.catalogName + "`!",
					"Failed to lock the catalog persistence service!",
					e
				);
			} finally {
				if (this.cpsvLock.isHeldByCurrentThread()) {
					this.cpsvLock.unlock();
				}
			}

			this.lastCatalogCompactionAtMillis = getNowEpochMillis();

			// emit the event
			event.finish().commit();

		} else {
			bootstrapRecord = new CatalogBootstrap(
				catalogVersion,
				catalogFileIndex,
				bootstrapWriteTime,
				flushedDescriptor.fileLocation()
			);
		}
		return bootstrapRecord;
	}

	/**
	 * Hands a data file over to {@link ObsoleteFileMaintainer} and schedules the time travel size guard.
	 *
	 * With time travel enabled the maintainer runs the removal lambda but skips the `delete()`, and that skipped delete
	 * is the entire disk cost of the feature - which makes these call sites the only events that grow retained history
	 * and therefore the only ones the guard needs to be driven by. No polling is needed.
	 *
	 * @param catalogVersion the last catalog version that may still use the file
	 * @param path           path of the file being retired
	 * @param removalLambda  lambda releasing the in-memory resources bound to the file
	 */
	private void retireDataFile(long catalogVersion, @Nonnull Path path, @Nonnull Runnable removalLambda) {
		this.obsoleteFileMaintainer.removeFileWhenNotUsed(catalogVersion, path, removalLambda);
		scheduleTimeTravelSizeGuard();
	}

	/**
	 * Schedules the time travel size guard, unless it cannot bind at all (time travel off, or no limit configured).
	 */
	private void scheduleTimeTravelSizeGuard() {
		final DelayedAsyncTask theGuardTask = this.timeTravelSizeGuardTask;
		if (theGuardTask != null) {
			theGuardTask.schedule();
		}
	}

	/**
	 * The single seam through which catalog history is given up. Trims the bootstrap file down to the requested
	 * version and reclaims every data file that no retained record can reach any more.
	 *
	 * Two independent drivers call this and neither knows about the other: write-ahead log retention, which reports
	 * the first version its files can still supply, and the {@link StorageOptions#timeTravelSizeLimitBytes()} guard,
	 * which reports the oldest generation that fits the disk budget. Whichever floor is higher wins, which is what the
	 * monotone {@link #historyHorizon} expresses - a request derived from a stale view must never walk the horizon
	 * back over files the other driver already deleted. Both are additionally clamped by the active-reader floor, so
	 * neither can pull data out from under a session that is still reading it.
	 *
	 * The two steps only make sense together and under one lock: the purge does not take the version as its threshold,
	 * it re-derives the threshold from the oldest record left in the freshly trimmed bootstrap file.
	 *
	 * @param requestedFirstVersionToBeKept the first catalog version whose history should still be reachable
	 */
	void advanceHistoryHorizon(long requestedFirstVersionToBeKept) {
		if (requestedFirstVersionToBeKept <= this.historyHorizon.get()) {
			// the horizon already sits at or above the raw request - clamping it to the retention floor can only lower
			// it further, so there is nothing left below it to reclaim and no reason to sample the floor at all
			return;
		}
		this.historyHorizonLock.lock();
		try {
			// the floor is sampled under the lock, never before it: `catalogVersionPinned` takes this same lock, so
			// a pin this sample does not observe cannot have been taken yet, and one taken afterwards blocks here and
			// then finds the horizon already moved. Sampling before the lock leaves exactly the window a point-in-time
			// backup falls into - it pins the version it resolved, the sample taken moments earlier never sees the pin,
			// and this call deletes the files the backup is about to read
			final long effectiveVersionToBeKept = this.walPurgeCallback.effectivePurgeVersion(
				requestedFirstVersionToBeKept);
			// re-check under the lock, the competing driver may have moved past us while we were waiting for it
			if (effectiveVersionToBeKept <= this.historyHorizon.get()) {
				if (effectiveVersionToBeKept < requestedFirstVersionToBeKept) {
					// the floor - not the other driver - is what refused this, so the request is still owed. Kept at
					// the highest value asked for, and retried when the pin holding it back goes away
					this.pendingHistoryHorizonRequest.accumulateAndGet(requestedFirstVersionToBeKept, Math::max);
				}
				return;
			}
			// first trim the bootstrap records, then reclaim the files the remaining records cannot reach
			trimBootstrapFile(effectiveVersionToBeKept);
			this.walPurgeCallback.purgeFilesUpTo(effectiveVersionToBeKept);
			// the marker is set only once both steps have succeeded. Setting it first would make a failed trim
			// permanent: the retry arrives with the same version, the monotonicity check above swallows it, and the
			// bootstrap file stays untrimmed for the rest of the catalog's life
			this.historyHorizon.set(effectiveVersionToBeKept);
		} finally {
			this.historyHorizonLock.unlock();
		}
	}

	/**
	 * Creates the task that reclaims unreachable data files and keeps retained history within
	 * {@link StorageOptions#timeTravelSizeLimitBytes()}, or returns `null` when time travel is switched off entirely.
	 * Compaction is the only event that grows history - with time travel enabled the compacted-away file is kept
	 * instead of deleted - so the task is driven by compaction rather than polled.
	 *
	 * The task binds whenever time travel is on, including when the operator asked for an unlimited budget: the budget
	 * is only half of what it does. Reclaiming files no retained bootstrap record can reach is a correctness property
	 * of time travel rather than a budget feature, and it is needed most precisely where history is unlimited.
	 *
	 * @param catalogName     name of the catalog the task belongs to
	 * @param storageSettings settings deciding whether the task can bind at all
	 * @param scheduler       scheduler the task is planned on
	 * @return the guard task, or `null` when time travel is off
	 */
	@Nullable
	private DelayedAsyncTask createTimeTravelSizeGuardTask(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Scheduler scheduler
	) {
		if (!storageSettings.timeTravelEnabled()) {
			return null;
		}
		return new DelayedAsyncTask(
			catalogName, "Time travel size guard",
			scheduler,
			this::enforceTimeTravelSizeLimit,
			0L, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Moves the history horizon up until the retained history fits into
	 * {@link StorageOptions#timeTravelSizeLimitBytes()}. Runs off the commit thread on the guard task.
	 *
	 * Costs one directory listing (where `File.length()` is a stat, not a read) plus `O(log n)` catalog header reads,
	 * because the retained size is monotone non-increasing in the horizon - see {@link TimeTravelRetention}.
	 *
	 * Package-private so a test can run it synchronously instead of racing the scheduler.
	 *
	 * @return always `-1`, the guard is scheduled by compaction and never re-plans itself
	 */
	long enforceTimeTravelSizeLimit() {
		// held across the measurement too, not just the advance: the bootstrap file is read record by record here and
		// the competing driver replaces that very file when it trims, which on platforms without POSIX rename
		// semantics cannot happen while a reader holds it open
		this.historyHorizonLock.lock();
		try {
			final long limitBytes = this.storageSettings.timeTravelSizeLimitBytes();
			// Files below what the oldest retained record pins are not history - no bootstrap record can reach them,
			// so no budget can justify keeping them. Reclaim them unconditionally, before measuring anything.
			// This is the only thing that ever reclaims a warm-up catalog's leftovers: warm-up rewrites the bootstrap
			// file down to a single record on every flush (see `getOrCreateNewBootstrapTempWriteHandle`), so a bulk
			// import compacting repeatedly with time travel on leaves every superseded data file stranded, and the
			// write-ahead log purge that would normally sweep them never runs because warm-up has no log.
			this.obsoleteFileMaintainer.reclaimUnreachableFiles();

			if (limitBytes < 0L) {
				// the operator asked for unlimited history - the sweep above still had to run, but nothing beyond it
				// applies: no budget means no horizon to compute and no reason to read a single bootstrap record
				return -1L;
			}

			final Path bootstrapFilePath = this.catalogStoragePath.resolve(
				getCatalogBootstrapFileName(this.catalogName));
			final int recordCount = CatalogBootstrap.getRecordCount(bootstrapFilePath.toFile().length());
			if (recordCount < 2) {
				// a single record pins the active data set only - there is no history left to give up
				return -1L;
			}

			final DataFileInventory inventory = scanDataFileInventory();
			final HorizonDecision decision;
			final long horizonCatalogVersion;
			try (
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
					bootstrapFilePath, this.bootstrapStorageSettings, this.bootstrapStorageSettings
				)
			) {
				decision = TimeTravelRetention.resolveHorizon(
					recordCount,
					recordIndex -> resolveGenerationPin(recordIndex, readHandle),
					inventory,
					limitBytes
				);
				horizonCatalogVersion = decision.recordIndex() == 0 ?
					-1L :
					deserializeCatalogBootstrapRecord(
						CatalogBootstrap.getPositionForRecord(decision.recordIndex()), readHandle
					).catalogVersion();
			}

			if (horizonCatalogVersion > -1L) {
				// an active reader still holding an older version outranks the budget - give it up for this round and
				// let the next compaction try again, rather than pulling data out from under a running session
				if (this.walPurgeCallback.effectivePurgeVersion(horizonCatalogVersion) < horizonCatalogVersion) {
					log.info(
						"Retained history of catalog `{}` exceeds `timeTravelSizeLimitBytes` ({} > {} bytes) but an " +
							"active reader still needs it - deferring until the reader leaves.",
						this.catalogName, decision.historyBytesBeforeAdvance(), limitBytes
					);
					return -1L;
				}
				log.info(
					"Retained history of catalog `{}` exceeded `timeTravelSizeLimitBytes` ({} > {} bytes) - " +
						"advancing the history horizon to catalog version `{}`, which leaves {} bytes of history.",
					this.catalogName, decision.historyBytesBeforeAdvance(), limitBytes,
					horizonCatalogVersion, decision.retainedHistoryBytes()
				);
				advanceHistoryHorizon(horizonCatalogVersion);
				// reported only once the horizon has actually moved - a round deferred by an active reader gave up
				// nothing, and saying otherwise would send an operator looking for history that is still there
				if (decision.historyCollapsedToNothing()) {
					log.warn(
						"Time travel history of catalog `{}` had to be given up entirely: keeping even the oldest " +
							"generation would cost more than the configured `timeTravelSizeLimitBytes` of {} bytes. " +
							"Raise the limit above the size of a single generation, or accept that time travel is " +
							"effectively disabled for this catalog.",
						this.catalogName, limitBytes
					);
				}
			}
		} catch (Exception ex) {
			// the guard is advisory - a failure must never take down the thread that scheduled it, and the next
			// compaction will schedule it again anyway
			log.warn(
				"Failed to enforce the time travel size limit for catalog `{}`: {}",
				this.catalogName, ex.getMessage(), ex
			);
		} finally {
			this.historyHorizonLock.unlock();
		}
		return -1L;
	}

	/**
	 * Resolves the generation pinned by the bootstrap record at the given index - the tuple of data files that all have
	 * to be present for that record to be readable.
	 *
	 * @param recordIndex         index of the record in the bootstrap file
	 * @param bootstrapReadHandle open read handle over the bootstrap file
	 * @return the pinned generation
	 */
	@Nonnull
	private GenerationPin resolveGenerationPin(int recordIndex, @Nonnull ReadOnlyFileHandle bootstrapReadHandle) {
		final CatalogBootstrap bootstrapRecord = deserializeCatalogBootstrapRecord(
			CatalogBootstrap.getPositionForRecord(recordIndex), bootstrapReadHandle
		);
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = fetchCatalogHeader(
			bootstrapRecord);
		final Collection<CollectionFileReference> fileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final Map<Integer, Integer> pinnedFileIndexes = CollectionUtils.createHashMap(fileIndexes.size());
		for (CollectionFileReference fileIndex : fileIndexes) {
			pinnedFileIndexes.put(fileIndex.entityTypePrimaryKey(), fileIndex.fileIndex());
		}
		return new GenerationPin(
			bootstrapRecord.catalogFileIndex(),
			pinnedFileIndexes,
			catalogHeader.lastEntityCollectionPrimaryKey()
		);
	}

	/**
	 * Measures how much disk the retained history occupies on top of the active data set - the quantity
	 * {@link StorageOptions#timeTravelSizeLimitBytes()} bounds, reported as a gauge on
	 * {@link CatalogStatisticsEvent}.
	 *
	 * Costs one directory listing plus two catalog header reads, next to the full-tree walk the same event already
	 * performs for its occupied-disk-space gauge.
	 *
	 * Package-private so a test can measure without going through a statistics event.
	 *
	 * @return retained history in bytes; `0` when time travel is off, when no history exists, or when the measurement
	 * itself failed - a statistics event must never be the thing that breaks
	 */
	long computeRetainedHistoryBytes() {
		if (!this.storageSettings.timeTravelEnabled()) {
			return 0L;
		}
		try {
			final Path bootstrapFilePath = this.catalogStoragePath.resolve(
				getCatalogBootstrapFileName(this.catalogName));
			final int recordCount = CatalogBootstrap.getRecordCount(bootstrapFilePath.toFile().length());
			if (recordCount < 2) {
				return 0L;
			}
			final DataFileInventory inventory = scanDataFileInventory();
			try (
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
					bootstrapFilePath, this.bootstrapStorageSettings, this.bootstrapStorageSettings
				)
			) {
				final long activeBytes = TimeTravelRetention.retainedBytes(
					inventory, resolveGenerationPin(recordCount - 1, readHandle));
				return TimeTravelRetention.retainedBytes(
					inventory, resolveGenerationPin(0, readHandle)) - activeBytes;
			}
		} catch (Exception ex) {
			log.warn(
				"Failed to measure the retained history of catalog `{}`: {}",
				this.catalogName, ex.getMessage()
			);
			return 0L;
		}
	}

	/**
	 * Lists the data files present in the catalog folder together with their sizes. Neither the write-ahead log nor the
	 * bootstrap file is included - the size limit constrains historical data files, while the WAL keeps its own
	 * independent retention bound.
	 *
	 * @return the inventory of data files on disk
	 */
	@Nonnull
	private DataFileInventory scanDataFileInventory() {
		final File catalogStorageFolder = this.catalogStoragePath.toFile();
		final File[] catalogFiles = ofNullable(
			catalogStorageFolder.listFiles((dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX))
		).orElse(new File[0]);
		final File[] collectionFiles = ofNullable(
			catalogStorageFolder.listFiles((dir, name) -> name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX))
		).orElse(new File[0]);

		final CatalogDataFile[] catalogDataFiles = new CatalogDataFile[catalogFiles.length];
		for (int i = 0; i < catalogFiles.length; i++) {
			final File file = catalogFiles[i];
			catalogDataFiles[i] = new CatalogDataFile(
				getIndexFromCatalogFileName(file.getName()), file.length());
		}
		final EntityCollectionDataFile[] collectionDataFiles = new EntityCollectionDataFile[collectionFiles.length];
		for (int i = 0; i < collectionFiles.length; i++) {
			final File file = collectionFiles[i];
			final EntityTypePrimaryKeyAndFileIndex parsedName = CatalogPersistenceService
				.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(file.getName());
			collectionDataFiles[i] = new EntityCollectionDataFile(
				parsedName.entityTypePrimaryKey(), parsedName.fileIndex(), file.length());
		}
		return new DataFileInventory(catalogDataFiles, collectionDataFiles);
	}

	/**
	 * Trims the bootstrap file so that it contains only records starting with the bootstrap record precedes the given
	 * timestamp and all the records following it.
	 *
	 * @param catalogVersion the first catalog version that should remain in bootstrap file
	 */
	void trimBootstrapFile(long catalogVersion) {
		// create tracking event
		final DataFileCompactEvent event = new DataFileCompactEvent(
			this.catalogName,
			FileType.BOOTSTRAP,
			this.catalogName
		);

		try {
			this.bootstrapWriteLock.lockInterruptibly();
			final BootstrapWriteOnlyFileHandle originalBootstrapHandle = this.bootstrapWriteHandle.get();
			final BootstrapWriteOnlyFileHandle newBootstrapHandle = createBootstrapTempWriteHandle(this.catalogName);

			// copy all bootstrap records since the timestamp to the new file
			copyAllNecessaryBootstrapRecords(
				catalogVersion, originalBootstrapHandle.getTargetFile(), newBootstrapHandle);

			// now close both handles
			originalBootstrapHandle.close();
			newBootstrapHandle.close();
			// try to atomically rewrite original bootstrap file
			FileUtils.rewriteTargetFileAtomically(
				newBootstrapHandle.getTargetFile(), originalBootstrapHandle.getTargetFile());
			// we should be the only writer here, so this should always pass
			Assert.isPremiseValid(
				this.bootstrapWriteHandle.compareAndSet(
					originalBootstrapHandle,
					createBootstrapWriteOnlyHandle(originalBootstrapHandle)
				),
				() -> new GenericEvitaInternalError(
					"Failed to replace the bootstrap write handle in a critical section!")
			);
			// remove the persistence services of every generation that just fell below the retained history
			final int obsoleteServiceCount = countObsoletePersistenceServices(
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(
					catalogVersion, this.catalogPersistenceServiceVersions)
			);
			for (int i = 0; i < obsoleteServiceCount; i++) {
				removeCatalogPersistenceServiceForVersion(this.catalogPersistenceServiceVersions[0]);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Failed to lock the bootstrap file for catalog `" + this.catalogName + "`!",
				"Failed to lock the bootstrap file!",
				e
			);
		} finally {
			this.bootstrapWriteLock.unlock();
			// emit the event
			event.finish().commit();
		}
	}

	/**
	 * Resolves the version at which the materialized block of the given bootstrap record starts. Used only for
	 * the **oldest** retained record, which has no predecessor to derive the block start from: its block reaches back
	 * as far as the write-ahead log can still supply, which is the first version present in the WAL file that record's
	 * catalog header points at.
	 *
	 * The WAL file index must come from the header's {@link CatalogHeader#walFileReference()}. It is emphatically
	 * **not** {@link CatalogBootstrap#catalogFileIndex()} - that counter is bumped by data file compaction while the
	 * WAL index is bumped by log rotation, so the two diverge as soon as either happens on its own. Feeding the
	 * catalog file index to the WAL made this resolve a foreign file's first version, or `-1` when no WAL file
	 * carried that index at all - and the `-1` propagated into {@link MaterializedVersionBlock#startVersion()}.
	 *
	 * @param catalogBootstrap the bootstrap record whose block start version is to be resolved.
	 * @return the first version covered by the record's block; never lower than the first version the WAL can supply
	 * and never higher than the record's own version.
	 */
	private long resolveBlockStartVersionOf(@Nonnull CatalogBootstrap catalogBootstrap) {
		final CatalogWriteAheadLog theCatalogWal = this.catalogWal;
		if (theCatalogWal == null) {
			return catalogBootstrap.catalogVersion();
		}
		final LogFileRecordReference walFileReference;
		try {
			walFileReference = fetchCatalogHeader(catalogBootstrap).walFileReference();
		} catch (Exception ex) {
			// the data file this record points at is unreadable - fall back to the record's own version rather than
			// reporting a block that starts nowhere
			log.warn(
				"Failed to read catalog header of bootstrap record for version `{}` of catalog `{}`: {}",
				catalogBootstrap.catalogVersion(), this.catalogName, ex.getMessage()
			);
			return catalogBootstrap.catalogVersion();
		}
		if (walFileReference == null) {
			return catalogBootstrap.catalogVersion();
		}
		final long firstVersionInWalFile = theCatalogWal.getFirstVersionOf(walFileReference.fileIndex());
		return firstVersionInWalFile < 0L ?
			catalogBootstrap.catalogVersion() :
			Math.min(catalogBootstrap.catalogVersion(), firstVersionInWalFile);
	}

	/**
	 * Verifies that the catalog name (derived from catalog directory) matches the catalog schema stored in the catalog
	 * file.
	 *
	 * @param catalogInstance               the catalog contract instance
	 * @param catalogVersion                the version of the catalog
	 * @param catalogStoragePath            the path to the catalog storage directory
	 * @param storagePartPersistenceService the storage part persistence service
	 * @param onDifferentCatalogName        the action to take when catalog names differ
	 * @param bootstrapUsed                 the bootstrap used to load the catalog
	 */
	private void verifyCatalogNameMatches(
		@Nonnull CatalogContract catalogInstance,
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogStoragePartPersistenceService<LogFileRecordReference, CollectionFileReference, PersistentStorageDescriptor> storagePartPersistenceService,
		@Nonnull OnDifferentCatalogName onDifferentCatalogName,
		@Nonnull CatalogBootstrap bootstrapUsed
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = storagePartPersistenceService.getCatalogHeader(
			catalogVersion);
		final boolean catalogNameIsSame = catalogHeader.catalogName().equals(this.catalogName);
		if (onDifferentCatalogName.equals(OnDifferentCatalogName.THROW_EXCEPTION)) {
			Assert.isTrue(
				catalogNameIsSame,
				() -> new UnexpectedCatalogContentsException(
					"Directory " + catalogStoragePath + " contains data of " + catalogHeader.catalogName() +
						" catalog. Cannot load catalog " + this.catalogName + " from this directory!"
				)
			);
		} else if (!catalogNameIsSame) {
			// update name in the catalog header
			storagePartPersistenceService.writeCatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				catalogVersion,
				catalogStoragePath,
				ofNullable(catalogHeader.walFileReference())
					.map(it -> new LogFileRecordReference(
						this.walFileNameProvider, it.fileIndex(), it.fileLocation(),
						it.cumulativeChecksum()
					))
					.orElse(null),
				catalogHeader.collectionFileIndex(),
				catalogHeader.catalogId(),
				this.catalogName,
				catalogHeader.catalogState(),
				catalogHeader.lastEntityCollectionPrimaryKey()
			);

			// update name in the catalog schema
			final CatalogSchemaStoragePart catalogSchemaStoragePart = CatalogSchemaStoragePart.deserializeWithCatalog(
				catalogInstance,
				() -> storagePartPersistenceService.getStoragePart(catalogVersion, 1, CatalogSchemaStoragePart.class)
			);
			final CatalogSchema catalogSchema = catalogSchemaStoragePart.catalogSchema();

			// this will not be recorded in the WAL, but it's ok since this is the first time the catalog is loaded
			final CatalogSchema updateCatalogSchema = CatalogSchema._internalBuild(
				catalogSchema.version() + 1,
				this.catalogName,
				NamingConvention.generate(this.catalogName),
				catalogSchema.getDescription(),
				null,
				catalogSchema.getCatalogEvolutionMode(),
				catalogSchema.getAttributes(),
				MutationEntitySchemaAccessor.INSTANCE
			);
			storagePartPersistenceService.putStoragePart(
				catalogVersion, new CatalogSchemaStoragePart(updateCatalogSchema)
			);

			final PersistentStorageDescriptor flushedDescriptor = storagePartPersistenceService.flush(catalogVersion);

			writeCatalogBootstrap(
				catalogVersion, this.catalogName,
				new CatalogBootstrap(
					catalogVersion,
					bootstrapUsed.catalogFileIndex(),
					OffsetDateTime.now(),
					flushedDescriptor.fileLocation()
				)
			);
		}
	}

	/**
	 * Verifies that the catalog name (derived from catalog directory) matches the catalog schema stored in the catalog
	 * file.
	 *
	 * @param catalogVersion                the version of the catalog
	 * @param catalogStoragePath            the path to the catalog storage directory
	 * @param storagePartPersistenceService the storage part persistence service
	 */
	private void verifyCatalogNameMatches(
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogStoragePartPersistenceService<LogFileRecordReference, CollectionFileReference, PersistentStorageDescriptor> storagePartPersistenceService
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = storagePartPersistenceService.getCatalogHeader(
			catalogVersion);
		final boolean catalogNameIsSame = catalogHeader.catalogName().equals(this.catalogName);
		Assert.isTrue(
			catalogNameIsSame,
			() -> new UnexpectedCatalogContentsException(
				"Directory " + catalogStoragePath + " contains data of " + catalogHeader.catalogName() +
					" catalog. Cannot load catalog " + this.catalogName + " from this directory!"
			)
		);
	}

	/**
	 * Method stores solely the {@link CatalogBootstrap} record to the catalog bootstrap file. You probably want to use
	 * more high-level method {@link #recordBootstrap(long, String, int, long, DataStoreMemoryBuffer)} or
	 * {@link #storeHeader(UUID, CatalogState, long, int, TransactionMutation, List, DataStoreMemoryBuffer)} instead.
	 *
	 * @param catalogVersion  the version of the catalog
	 * @param newCatalogName  the name of the catalog
	 * @param bootstrapRecord the bootstrap record to store
	 * @return the stored CatalogBootstrap object
	 */
	@Nonnull
	private CatalogBootstrap writeCatalogBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		@Nonnull CatalogBootstrap bootstrapRecord
	) {
		final Kryo kryo = this.walKryoPool.obtain();
		try {
			this.bootstrapWriteLock.lockInterruptibly();
			// THE FENCE. A bootstrap record is a pointer into the data files, and it must never become durable
			// before the bytes it addresses have. Forcing here rather than at the call sites makes that structural:
			// every writer of a bootstrap record - trunk round, compaction, go-live, rename, restore - is covered,
			// including ones added later that would never think to ask.
			if (this.checkpointCoordinator != null) {
				this.checkpointCoordinator.forcePendingSyncs();
			}
			final BootstrapWriteOnlyFileHandle originalBootstrapHandle = this.bootstrapWriteHandle.get();
			final BootstrapWriteOnlyFileHandle bootstrapHandle = getOrCreateNewBootstrapTempWriteHandle(
				catalogVersion, newCatalogName, originalBootstrapHandle
			);

			// append to the existing file (we will compact it when the WAL files are purged)
			bootstrapHandle.checkAndExecuteAndSync(
				"store bootstrap record",
				() -> {
				},
				output -> serializeBootstrapRecord(output, bootstrapRecord).payload(),
				(output, catalogBootstrap) -> catalogBootstrap
			);

			// replace the original handle with new one
			if (bootstrapHandle != originalBootstrapHandle) {
				originalBootstrapHandle.close();
				bootstrapHandle.close();
				// try to atomically rewrite original bootstrap file
				FileUtils.rewriteTargetFileAtomically(
					bootstrapHandle.getTargetFile(), originalBootstrapHandle.getTargetFile());
				// we should be the only writer here, so this should always pass
				Assert.isPremiseValid(
					this.bootstrapWriteHandle.compareAndSet(
						originalBootstrapHandle,
						createBootstrapWriteOnlyHandle(originalBootstrapHandle)
					),
					() -> new GenericEvitaInternalError(
						"Failed to replace the bootstrap write handle in a critical section!")
				);
			}

			// A retired generation only becomes history once the record that supersedes it is published, and that
			// publication may be deferred long after the compaction that scheduled the guard from `retireDataFile`.
			// Until then the newest published record still pins the retired file, so the guard counts it as active
			// and sees no new history at all. Scheduling here - at the one point every bootstrap record passes
			// through, deferred checkpoints included - is what makes the budget observe the generation that
			// scheduling on retirement alone would skip.
			scheduleTimeTravelSizeGuard();

			return bootstrapRecord;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Failed to lock the bootstrap file for catalog `" + this.catalogName + "`!",
				"Failed to lock the bootstrap file!",
				e
			);
		} finally {
			this.bootstrapWriteLock.unlock();
			this.walKryoPool.free(kryo);
			log.debug("Catalog `{}` stored to `{}`.", newCatalogName, this.catalogStoragePath);
		}
	}

	/**
	 * Verifies the storage protocol version of the current {@link CatalogHeader} and either upgrades
	 * it in place or signals that a deferred upgrade is required.
	 *
	 * **Eager vs deferred migrations:** the early migrations (v1→v2, v2→v3, v3→v4) are trivial header-level
	 * rewrites, so they run inline here whenever an old-protocol catalog is opened. The v4→v5 migration, however,
	 * rewrites every WAL file on disk and can fail partway through; to make that failure mode visible and crash-safe
	 * it is driven by {@link io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation}
	 * through the engine WAL. This method therefore throws {@link CatalogRequiresUpgradeException} on a v4 catalog
	 * unless {@code allowInlineV4ToV5Upgrade} explicitly permits the inline upgrade (used only by the mutation
	 * operator's work phase via {@link #runStorageProtocolUpgrade}).
	 *
	 * @param storagePartPersistenceFactory the factory for the storage part persistence service
	 * @param catalogVersion                the version of the catalog
	 * @param allowInlineV4ToV5Upgrade      when {@code true} the method runs the v4→v5 WAL rewrite
	 *                                      inline; when {@code false} (default for normal loads) it
	 *                                      throws {@link CatalogRequiresUpgradeException}
	 * @return the storage part persistence service
	 * @throws ObsoleteStorageProtocolException if the storage protocol version is not compatible with the current one
	 * @throws CatalogRequiresUpgradeException  when the catalog is on v4 and inline v4→v5 is not permitted
	 */
	@Nonnull
	private CatalogOffsetIndexStoragePartPersistenceService verifyAndUpgradeStorageFormat(
		@Nonnull Supplier<CatalogOffsetIndexStoragePartPersistenceService> storagePartPersistenceFactory,
		long catalogVersion,
		boolean allowInlineV4ToV5Upgrade
	) throws ObsoleteStorageProtocolException {
		CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = storagePartPersistenceFactory.get();
		CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader = storagePartPersistenceService.getCatalogHeader(
			catalogVersion);
		if (catalogHeader.storageProtocolVersion() == PersistenceService.STORAGE_PROTOCOL_VERSION) {
			return storagePartPersistenceService;
		} else {
			CatalogOffsetIndexStoragePartPersistenceService currentService = storagePartPersistenceService;
			do {
				final int catalogStorageProtocolVersion = catalogHeader.storageProtocolVersion();
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> currentCatalogHeader = catalogHeader;
				if (catalogStorageProtocolVersion == 1) {
					Migration_2024_11.upgradeFromStorageProtocolVersion_1_to_2(
						catalogHeader,
						this.catalogStoragePath,
						() -> updateStorageProtocolInCatalogHeader(
							currentCatalogHeader, currentService, 2
						)
					);
				} else if (catalogStorageProtocolVersion == 2) {
					// upgrade storage protocol version 2 to 3
					ConsoleWriter.writeLine(
						"Catalog `" + catalogHeader.catalogName() + "` contains storage protocol version 2 in its header, updating.",
						ConsoleColor.BRIGHT_BLUE
					);
					updateStorageProtocolInCatalogHeader(catalogHeader, storagePartPersistenceService, 3);
					ConsoleWriter.writeLine(
						"Catalog `" + catalogHeader.catalogName() + "` catalog header updated.",
						ConsoleColor.BRIGHT_BLUE
					);
				} else if (catalogStorageProtocolVersion == 3) {
					Migration_2025_6.upgradeFromStorageProtocolVersion_3_to_4(
						catalogHeader,
						currentService,
						this::createEntityCollectionPersistenceService,
						newCatalogHeader -> updateStorageProtocolInCatalogHeader(newCatalogHeader, currentService, 4)
					);
				} else if (catalogStorageProtocolVersion == 4) {
					if (!allowInlineV4ToV5Upgrade) {
						// v4→v5 rewrites every WAL file; defer to the engine-level mutation flow so
						// a partial failure is observable in the WAL and the next boot can auto-retry.
						// `loadCatalogInternal` catches this and issues `UpgradeCatalogFormatMutation`.
						throw new CatalogRequiresUpgradeException(
							catalogHeader.catalogName(),
							catalogStorageProtocolVersion,
							PersistenceService.STORAGE_PROTOCOL_VERSION
						);
					}
					Migration_2026_1.upgradeCatalogWalFiles(
						catalogHeader,
						this.catalogStoragePath,
						catalogHeader.walFileReference(),
						this.exportService,
						this.storageSettings,
						(correctedWalRef) -> {
							final LogFileRecordReference walRef = correctedWalRef != null
								? correctedWalRef
								: currentCatalogHeader.walFileReference();
							updateStorageProtocolInCatalogHeader(
								currentCatalogHeader, currentService, 5, walRef
							);
						}
					);
				} else if (catalogStorageProtocolVersion == 5) {
					Migration_2026_2.upgradeFromStorageProtocolVersion_5_to_6(
						catalogHeader,
						currentService,
						this::createEntityCollectionPersistenceService,
						newCatalogHeader -> updateStorageProtocolInCatalogHeader(newCatalogHeader, currentService, 6)
					);
				}
				// try to initialize the persistence service again - it should now have the correct storage protocol version
				storagePartPersistenceService = storagePartPersistenceFactory.get();
				catalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
				// rinse and repeat
			} while (catalogHeader.storageProtocolVersion() < PersistenceService.STORAGE_PROTOCOL_VERSION);

			return storagePartPersistenceService;
		}
	}

	/**
	 * Updates the storage protocol version in the catalog header and persists the updated information
	 * using the supplied catalog offset index storage service. It also updates the catalog bootstrap
	 * data after flushing the updated catalog header.
	 *
	 * @param catalogHeader                 The catalog header containing metadata about the catalog.
	 * @param storagePartPersistenceService The service used to manage persistence of the catalog
	 *                                      header and related storage parts.
	 * @param storageProtocolVersion        The new storage protocol version to be set in the catalog header.
	 */
	private void updateStorageProtocolInCatalogHeader(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		int storageProtocolVersion
	) {
		updateStorageProtocolInCatalogHeader(
			catalogHeader, storagePartPersistenceService, storageProtocolVersion,
			catalogHeader.walFileReference()
		);
	}

	/**
	 * Updates the storage protocol version in the catalog header and persists the updated information
	 * using the supplied catalog offset index storage service. It also updates the catalog bootstrap
	 * data after flushing the updated catalog header.
	 *
	 * This overload accepts an explicit WAL file reference, which is used instead of the one from
	 * the catalog header. This is needed during WAL migration when byte positions in the WAL file
	 * change and the stored reference needs to be corrected.
	 *
	 * @param catalogHeader                 the catalog header containing metadata about the catalog
	 * @param storagePartPersistenceService the service used to manage persistence of the catalog
	 *                                      header and related storage parts
	 * @param storageProtocolVersion        the new storage protocol version to be set
	 * @param walFileReference              the WAL file reference to store in the header
	 */
	private void updateStorageProtocolInCatalogHeader(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		int storageProtocolVersion,
		@Nullable LogFileRecordReference walFileReference
	) {
		storagePartPersistenceService.writeCatalogHeader(
			storageProtocolVersion,
			catalogHeader.version(),
			this.catalogStoragePath,
			walFileReference,
			catalogHeader.collectionFileIndex(),
			catalogHeader.catalogId(),
			catalogHeader.catalogName(),
			catalogHeader.catalogState(),
			catalogHeader.lastEntityCollectionPrimaryKey()
		);
		final OffsetIndexDescriptor flushedDescriptor = storagePartPersistenceService.flush(catalogHeader.version());
		this.bootstrapUsed = writeCatalogBootstrap(
			catalogHeader.version(),
			catalogHeader.catalogName(),
			new CatalogBootstrap(
				catalogHeader.version(),
				this.bootstrapUsed.catalogFileIndex(),
				OffsetDateTime.now(),
				flushedDescriptor.fileLocation()
			)
		);
	}

	/**
	 * Removes the persistence service from internal index and closes its resources.
	 *
	 * @param collectionFileReference the reference to the collection persistence service
	 */
	private void removeEntityCollectionPersistenceServiceAndClose(
		@Nonnull CollectionFileReference collectionFileReference
	) {
		final DefaultEntityCollectionPersistenceService persistenceService = this.entityCollectionPersistenceServices.remove(
			collectionFileReference);
		if (persistenceService != null) {
			persistenceService.close();
		}
	}

	/**
	 * Copy all necessary bootstrap records to the target bootstrap file from the input file starting from the given
	 * timestamp.
	 *
	 * @param sinceCatalogVersion   The catalog version from which the records should be copied (including).
	 * @param fromFile              The input file containing the bootstrap records.
	 * @param targetBootstrapHandle The handle of the target bootstrap file to copy the records to.
	 */
	private void copyAllNecessaryBootstrapRecords(
		long sinceCatalogVersion,
		@Nonnull Path fromFile,
		@Nonnull BootstrapWriteOnlyFileHandle targetBootstrapHandle
	) {
		final int recordCount = CatalogBootstrap.getRecordCount(
			fromFile.toFile().length()
		);
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				fromFile, this.bootstrapStorageSettings, this.bootstrapStorageSettings
			)
		) {
			targetBootstrapHandle.checkAndExecute(
				"copy bootstrap record",
				() -> {
				},
				output -> {
					for (int i = 0; i < recordCount; i++) {
						final long startPosition = CatalogBootstrap.getPositionForRecord(i);
						final CatalogBootstrap bootstrapRecord = deserializeCatalogBootstrapRecord(
							startPosition, readHandle);
						Assert.isPremiseValid(
							bootstrapRecord != null,
							() -> new GenericEvitaInternalError("Failed to read the bootstrap record from the file!")
						);

						if (bootstrapRecord.catalogVersion() >= sinceCatalogVersion) {
							// append to the new file
							serializeBootstrapRecord(output, bootstrapRecord);
						}
					}
					return null;
				}
			);
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + fromFile + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
	}

	/**
	 * Retrieves or creates a new write handle for catalog persistence. If the catalog version is 0,
	 * a new file handle is created and returned. Otherwise, the original bootstrap handle is returned.
	 *
	 * @param catalogVersion          The version of the catalog.
	 * @param newCatalogName          The name of the new catalog.
	 * @param originalBootstrapHandle The original bootstrap handle.
	 * @return The write handle for catalog persistence.
	 * @throws UnexpectedIOException If an error occurs while creating the temporary bootstrap file.
	 */
	@Nonnull
	private BootstrapWriteOnlyFileHandle getOrCreateNewBootstrapTempWriteHandle(
		long catalogVersion,
		@Nonnull String newCatalogName,
		@Nonnull BootstrapWriteOnlyFileHandle originalBootstrapHandle
	) {
		if (catalogVersion == 0) {
			return createBootstrapTempWriteHandle(newCatalogName);
		} else {
			return originalBootstrapHandle;
		}
	}

	/**
	 * Creates a new bootstrap write handle from an existing write-only file handle.
	 *
	 * @param originalBootstrapHandle the original write-only file handle used as the basis for creating the new bootstrap write handle
	 * @return a new WriteOnlyFileHandle with customized configurations based on the provided original handle
	 */
	@Nonnull
	private BootstrapWriteOnlyFileHandle createBootstrapWriteOnlyHandle(
		@Nonnull BootstrapWriteOnlyFileHandle originalBootstrapHandle
	) {
		return new BootstrapWriteOnlyFileHandle(
			originalBootstrapHandle.getTargetFile(),
			this.bootstrapStorageSettings.outputBufferSize(),
			this.bootstrapStorageSettings.syncWrites(),
			this.bootstrapStorageSettings.lockTimeoutSeconds(),
			this.bootstrapStorageSettings
		);
	}

	/**
	 * Creates a new temporary file handle for writing bootstrap data.
	 *
	 * @param newCatalogName the name of the new catalog
	 * @return a WriteOnlyFileHandle object representing the new temporary file
	 * @throws UnexpectedIOException if an error occurs while creating the temporary file
	 */
	@Nonnull
	private BootstrapWriteOnlyFileHandle createBootstrapTempWriteHandle(@Nonnull String newCatalogName) {
		try {
			// create new file and replace the former one with it
			return new BootstrapWriteOnlyFileHandle(
				Files.createTempFile(CatalogPersistenceService.getCatalogBootstrapFileName(newCatalogName), ".tmp"),
				this.bootstrapStorageSettings.outputBufferSize(),
				this.bootstrapStorageSettings.syncWrites(),
				this.bootstrapStorageSettings.lockTimeoutSeconds(),
				this.bootstrapStorageSettings
			);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to create temporary bootstrap file for catalog `" + newCatalogName + "`!",
				"Failed to create temporary bootstrap file!",
				e
			);
		}
	}

	/**
	 * Removes the catalog persistence service for the specified catalog version.
	 * The service may not necessarily match exactly the passed catalog version. If the catalog version is not found,
	 * method removes the service for the closest lower version - which should be valid for entire version span.
	 *
	 * Method is not thread safe and caller must ensure it's called only from single thread.
	 *
	 * @param catalogVersion the catalog version to remove the persistence service for
	 */
	private void removeCatalogPersistenceServiceForVersion(long catalogVersion) {
		try {
			this.cpsvLock.lockInterruptibly();
			if (catalogVersion == 0) {
				if (this.warmUpVersionCardinality > 1) {
					this.warmUpVersionCardinality--;
				} else if (this.warmUpVersionCardinality == 1) {
					this.warmUpVersionCardinality = 0;
					doRemoveCatalogPersistenceServiceForVersion(catalogVersion);
				}
			} else {
				doRemoveCatalogPersistenceServiceForVersion(catalogVersion);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Failed to lock the catalog persistence service for catalog `" + this.catalogName + "`!",
				"Failed to lock the catalog persistence service!",
				e
			);
		} finally {
			if (this.cpsvLock.isHeldByCurrentThread()) {
				this.cpsvLock.unlock();
			}
		}
	}

	/**
	 * Internal method for removing catalog persistence service for the specified catalog version. No locking,
	 * no cardinality checks - just removal. Method is meant to be called from {@link #removeCatalogPersistenceServiceForVersion(long)}
	 * only.
	 *
	 * @param catalogVersion the catalog version to remove the persistence service for
	 */
	private void doRemoveCatalogPersistenceServiceForVersion(long catalogVersion) {
		final int index = Arrays.binarySearch(this.catalogPersistenceServiceVersions, catalogVersion);
		final int lookupIndex = index >= 0 ? index : (-index - 2);
		if (lookupIndex >= 0 && lookupIndex < this.catalogPersistenceServiceVersions.length) {
			final long versionToRemove = this.catalogPersistenceServiceVersions[lookupIndex];
			this.catalogPersistenceServiceVersions = ArrayUtils.removeLongFromArrayOnIndex(
				this.catalogPersistenceServiceVersions, lookupIndex);
			// remove the service and release its resources
			final CatalogOffsetIndexStoragePartPersistenceService storageService = this.catalogStoragePartPersistenceService.remove(
				versionToRemove);
			storageService.close();
		} else {
			// the version to remove might already have been removed, so we do nothing
		}
	}

	/**
	 * Creates an index of the previous catalog versions based on the specified catalog version array and bootstrap file
	 * path. It uses binary search to quickly locate min / max version indexes and then indexes all versions between
	 * them and for each version stores the previous version.
	 *
	 * @param catalogVersions an array of catalog versions
	 * @return a {@link MaterializedVersionBlock} object that contains PREVIOUS VERSION and CURRENT VERSION TIMESTAMP
	 * (this is a little bit hacky, but we avoid declaring new record type)
	 */
	@Nonnull
	private List<MaterializedVersionBlock> createMaterializedVersionBlocks(
		@Nonnull long... catalogVersions
	) {
		final List<MaterializedVersionBlock> blocks = new ArrayList<>(catalogVersions.length);
		for (long catalogVersion : catalogVersions) {
			final CatalogBootstrap[] catalogBootstraps = localizeCatalogBootstrapPair(
				this.catalogName,
				this.bootstrapStorageSettings,
				catalogVersion,
				(catalogBootstrap, version) -> Long.compare(catalogBootstrap.catalogVersion(), version),
				0
			);
			blocks.add(
				new MaterializedVersionBlock(
					catalogBootstraps[0] == null ?
						resolveBlockStartVersionOf(catalogBootstraps[1]) :
						catalogBootstraps[0].catalogVersion() + 1,
					catalogBootstraps[1].catalogVersion(),
					catalogBootstraps[1].timestamp()
				)
			);
		}
		return blocks;
	}

	/**
	 * Reports changes in non-flushed record size every second.
	 *
	 * @param catalogName     name of the catalog
	 * @param nonFlushedBlock non-flushed block information
	 */
	private void reportNonFlushedContents(@Nonnull String catalogName, @Nonnull NonFlushedBlock nonFlushedBlock) {
		final long now = getNowEpochMillis();
		if (this.lastReportTimestamp < now - 1000 || nonFlushedBlock.recordCount() == 0) {
			this.lastReportTimestamp = now;
			new OffsetIndexNonFlushedEvent(
				catalogName,
				FileType.CATALOG,
				catalogName,
				nonFlushedBlock.recordCount(),
				nonFlushedBlock.estimatedMemorySizeInBytes()
			).commit();
		}
	}

	/**
	 * Finds the first available file index for the given entity type.
	 *
	 * @param entityType           the type of entity
	 * @param entityTypePrimaryKey the primary key of the entity type
	 * @return the first available file index
	 */
	private int findFirstAvailableFileIndex(@Nonnull String entityType, int entityTypePrimaryKey) {
		final Pattern pattern = getEntityCollectionDataStoreFileNamePattern(entityType, entityTypePrimaryKey);
		final File[] entityCollectionFiles = Objects.requireNonNull(
			this.catalogStoragePath.toFile()
				.listFiles(
					(dir, name) -> name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX)
				)
		);
		if (entityCollectionFiles.length == 0) {
			return 0;
		} else {
			int maxIndex = -1;
			for (File entityCollectionFile : entityCollectionFiles) {
				final String name = entityCollectionFile.getName();
				final Matcher matcher = pattern.matcher(name);
				if (matcher.matches()) {
					final int index = Integer.parseInt(matcher.group(1));
					if (maxIndex < index) {
						maxIndex = index;
					}
				}
			}
			return maxIndex + 1;
		}
	}

	/**
	 * Returns the bootstrap record and catalog header of the oldest catalog version still retained on disk (the first
	 * record in the bootstrap file). This is the basis for the WAL-rotation purge driven by
	 * {@link ObsoleteFileMaintainer}: the catalog data file referenced by this bootstrap is the lowest index that is
	 * kept, so deleting everything strictly below it never removes a file that a retained bootstrap record still
	 * references, and - because that file is by definition not eligible for deletion - the header read itself can never
	 * target an already purged file. This mirrors the time-travel branch of {@link #purgeAllObsoleteFiles()}.
	 *
	 * @return the data files info of the oldest retained catalog version, or {@code null} when no bootstrap is available
	 */
	@Nullable
	private DataFilesBulkInfo fetchOldestRetainedDataFilesInfo() {
		return getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageSettings)
			.map(it -> new DataFilesBulkInfo(it, fetchCatalogHeader(it)))
			.orElse(null);
	}

	/**
	 * Fetches the catalog header for the given catalog bootstrap record.
	 *
	 * @param bootstrap bootstrap record
	 * @return the catalog header
	 */
	@Nonnull
	private CatalogHeader<LogFileRecordReference, CollectionFileReference> fetchCatalogHeader(
		@Nonnull CatalogBootstrap bootstrap
	) {
		final String catalogFileName = getCatalogDataStoreFileName(this.catalogName, bootstrap.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		return readCatalogHeader(this.storageSettings, catalogFilePath, bootstrap, this.recordTypeRegistry);
	}

	/**
	 * Retrieves the materialized version block of the catalog at a given moment in time.
	 *
	 * @param moment The specific point in time to retrieve the catalog version information for.
	 *               If null, the method will consider the latest available moment and version.
	 * @param delta  The delta to apply to the resolved catalog version.
	 * @return A {@link MaterializedVersionBlock} representing the range of catalog versions and the corresponding timestamp
	 * at the specified or resolved moment.
	 * @throws TemporalDataNotAvailableException If unable to locate temporal data for the specified moment.
	 */
	@Nonnull
	private MaterializedVersionBlock getCatalogVersionAt(
		@Nullable OffsetDateTime moment,
		int delta
	) throws TemporalDataNotAvailableException {
		final CatalogBootstrap[] catalogBootstraps;
		if (moment == null) {
			final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
			final Path catalogStoragePath = this.bootstrapStorageSettings.storageDirectory().resolve(this.catalogName);
			final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
			final File bootstrapFile = bootstrapFilePath.toFile();
			if (bootstrapFile.exists()) {
				final int recordCount = CatalogBootstrap.getRecordCount(
					bootstrapFile.length()
				);
				if (recordCount >= 1) {
					catalogBootstraps = new CatalogBootstrap[]{
						null,
						deserializeCatalogBootstrapRecord(this.bootstrapStorageSettings, bootstrapFilePath, 0)
					};
				} else {
					throw new TemporalDataNotAvailableException();
				}
			} else {
				throw new TemporalDataNotAvailableException();
			}
		} else {
			catalogBootstraps = localizeCatalogBootstrapPair(
				this.catalogName,
				this.bootstrapStorageSettings,
				moment,
				(catalogBootstrap, timestamp) -> catalogBootstrap.timestamp().compareTo(timestamp),
				delta
			);
		}
		final long endVersion = Math.min(catalogBootstraps[1].catalogVersion(), getLastCatalogVersion());
		return new MaterializedVersionBlock(
			Math.min(
				endVersion,
				catalogBootstraps[0] == null ?
					resolveBlockStartVersionOf(catalogBootstraps[1]) :
					catalogBootstraps[0].catalogVersion() + 1
			),
			endVersion,
			catalogBootstraps[1].timestamp()
		);
	}

	/**
	 * Enumeration of possible actions to be taken when the catalog name is different from the target catalog name.
	 */
	enum OnDifferentCatalogName {
		/**
		 * Throw an exception when the catalog name is different from the target catalog name.
		 */
		THROW_EXCEPTION,
		/**
		 * Adapt the catalog name in the schema to the target catalog name.
		 */
		ADAPT

	}

	/**
	 * DTO for passing the identified conflict in entity names for certain naming convention.
	 */
	record EntityNamingConventionConflict(
		@Nonnull EntitySchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * DTO for holding file information during catalog duplication.
	 */
	record FileInfo(
		@Nonnull Path path,
		@Nonnull String suffix,
		int suffixPriority,
		@Nonnull Instant creationTime
	) {
	}

	/**
	 * Supplier that reads catalog bootstrap records from the catalog bootstrap file.
	 */
	private static class CatalogBootstrapSupplier implements Supplier<CatalogBootstrap>, Closeable {
		private final ReadOnlyFileHandle readHandle;
		private int position;

		public CatalogBootstrapSupplier(
			@Nonnull Path bootstrapFilePath,
			@Nonnull StorageSettings storageSettings
		) {
			this.readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath,
				storageSettings,
				storageSettings
			);
		}

		@Nullable
		@Override
		public CatalogBootstrap get() {
			if (this.position + CatalogBootstrap.BOOTSTRAP_RECORD_SIZE > this.readHandle.getLastWrittenPosition()) {
				return null;
			}
			final CatalogBootstrap catalogBootstrap = deserializeCatalogBootstrapRecord(this.position, this.readHandle);
			this.position += CatalogBootstrap.BOOTSTRAP_RECORD_SIZE;
			return catalogBootstrap;
		}

		@Override
		public void close() {
			this.readHandle.close();
		}
	}

}
