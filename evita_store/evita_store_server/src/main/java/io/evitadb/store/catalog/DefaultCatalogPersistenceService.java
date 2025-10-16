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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogConsumersListener;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
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
import io.evitadb.store.catalog.ObsoleteFileMaintainer.DataFilesBulkInfo;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.catalog.task.BackupTask;
import io.evitadb.store.catalog.task.FullBackupTask;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.exception.InvalidFileNameException;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.exception.StoredProtocolVersionNotSupportedException;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.BootstrapWriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.CatalogStoragePartPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.HeaderInfoSupplier;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.store.spi.exception.BootstrapFileNotFound;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
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
import io.evitadb.utils.IOUtils.IOExceptionThrowingRunnable;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService.readCatalogHeader;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getEntityCollectionDataStoreFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getEntityCollectionDataStoreFileNamePattern;
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
public class DefaultCatalogPersistenceService implements CatalogPersistenceService, CatalogConsumersListener {

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
	 * This supplier is overridden in tests to provide deterministic time. Do not use elsewhere.
	 */
	static LongSupplier CURRENT_TIME_MILLIS = System::currentTimeMillis;

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
	private final ExportFileService exportFileService;
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
	@Getter @Nonnull
	private final StorageOptions storageOptions;
	/**
	 * Contains information about storage configuration options modified to match requirements of the bootstrap file.
	 * The bootstrap file requires fixed record size and thus must not be compressed.
	 */
	@Getter @Nonnull
	private final StorageOptions bootstrapStorageOptions;
	/**
	 * Contains information about transaction configuration options.
	 */
	@Nonnull
	private final TransactionOptions transactionOptions;
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
	 * Scheduled executor service is used for planning maintenance tasks on the data level.
	 */
	@Nonnull private final Scheduler scheduler;
	/**
	 * Obsolete file maintainer takes care of deleting files that are no longer referenced by any of the sessions.
	 */
	private final ObsoleteFileMaintainer obsoleteFileMaintainer;
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
	 */
	@Nullable private CatalogWriteAheadLog catalogWal;
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
	 * @param catalogName    the name of the catalog
	 * @param storageOptions the storage options for reading the bootstrap file
	 * @return the stream of catalog bootstrap records
	 */
	@Nonnull
	public static Stream<CatalogBootstrap> getCatalogBootstrapRecordStream(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageOptions.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final CatalogBootstrapSupplier supplier = new CatalogBootstrapSupplier(
				bootstrapFilePath, storageOptions
			);
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
	 * @param catalogName    The name of the catalog.
	 * @param storageOptions The storage options for reading the bootstrap file.
	 * @return The first catalog bootstrap or NULL if the catalog bootstrap file is empty.
	 * @throws UnexpectedIOException If there is an error opening the catalog bootstrap file.
	 */
	@Nonnull
	static Optional<CatalogBootstrap> getFirstCatalogBootstrap(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageOptions.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			return of(deserializeCatalogBootstrapRecord(storageOptions, bootstrapFilePath, 0));
		} else {
			return empty();
		}
	}

	/**
	 * Retrieves the catalog bootstrap that is valid for passed date and time for a given catalog.
	 *
	 * @param catalogName    The name of the catalog.
	 * @param storageOptions The storage options for reading the bootstrap file.
	 * @param pastMoment     The moment in time to search for the first catalog bootstrap.
	 * @return The first catalog bootstrap or NULL if the catalog bootstrap file is empty.
	 * @throws UnexpectedIOException             If there is an error opening the catalog bootstrap file.
	 * @throws TemporalDataNotAvailableException If the catalog bootstrap starts with later record than the specified
	 *                                           moment or is in the future
	 */
	@Nonnull
	static CatalogBootstrap getCatalogBootstrapForSpecificMoment(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetDateTime pastMoment
	) {
		Assert.isTrue(
			pastMoment.isBefore(OffsetDateTime.now()),
			() -> new TemporalDataNotAvailableException()
		);
		final Optional<CatalogBootstrap> firstCatalogBootstrap = getFirstCatalogBootstrap(catalogName, storageOptions);
		final OffsetDateTime firstTimestamp = firstCatalogBootstrap.map(CatalogBootstrap::timestamp).orElse(null);
		Assert.isTrue(
			firstCatalogBootstrap
				.map(it -> !it.timestamp().isAfter(pastMoment))
				.orElse(false),
			() -> firstTimestamp == null ?
				new TemporalDataNotAvailableException() :
				new TemporalDataNotAvailableException(firstTimestamp)
		);

		try (final Stream<CatalogBootstrap> catalogBootstrapRecordStream = getCatalogBootstrapRecordStream(
			catalogName, storageOptions)) {
			return catalogBootstrapRecordStream
				.takeWhile(current -> !current.timestamp().isAfter(pastMoment))
				.reduce((previous, current) -> current)
				.orElseThrow(() -> new TemporalDataNotAvailableException(firstTimestamp));
		}
	}

	/**
	 * Retrieves the catalog bootstrap that is valid for passed date and time for a given catalog.
	 *
	 * @param catalogName    The name of the catalog.
	 * @param storageOptions The storage options for reading the bootstrap file.
	 * @param catalogVersion The version to search for the catalog bootstrap record.
	 * @return The first catalog bootstrap or NULL if the catalog bootstrap file is empty.
	 * @throws UnexpectedIOException             If there is an error opening the catalog bootstrap file.
	 * @throws TemporalDataNotAvailableException If the catalog bootstrap starts with later record than the specified
	 *                                           moment or is in the future
	 */
	@Nonnull
	static CatalogBootstrap getCatalogBootstrapForSpecificVersion(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		long catalogVersion
	) {
		final Optional<CatalogBootstrap> firstBootstrap = getFirstCatalogBootstrap(catalogName, storageOptions);
		final long firstCatalogVersion = firstBootstrap.map(CatalogBootstrap::catalogVersion).orElse(0L);
		Assert.isTrue(
			firstBootstrap
				.map(it -> it.catalogVersion() <= catalogVersion)
				.orElse(false),
			() -> new TemporalDataNotAvailableException(firstCatalogVersion)
		);

		try (final Stream<CatalogBootstrap> catalogBootstrapRecordStream = getCatalogBootstrapRecordStream(
			catalogName, storageOptions)) {
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
	 * Retrieves the last catalog bootstrap for a given catalog. If the last bootstrap record was not fully written,
	 * the previous one is returned instead. The correctness is verified by fixed length of the bootstrap record and
	 * CRC32C checksum of the record.
	 *
	 * @param catalogName    The name of the catalog.
	 * @param storageOptions The storage options for reading the bootstrap file.
	 * @return The last catalog bootstrap.
	 * @throws UnexpectedIOException If there is an error opening the catalog bootstrap file.
	 * @throws BootstrapFileNotFound If the catalog bootstrap file is not found.
	 */
	@Nonnull
	static CatalogBootstrap getLastCatalogBootstrap(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageOptions.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final long lastMeaningfulPosition = CatalogBootstrap.getLastMeaningfulPosition(length);
			return deserializeCatalogBootstrapRecord(storageOptions, bootstrapFilePath, lastMeaningfulPosition);
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
	 * Creates a CatalogWriteAheadLog if there are any WAL files present in the catalog file path.
	 *
	 * @param catalogVersion     the version of the catalog
	 * @param catalogName        the name of the catalog
	 * @param storageOptions     the storage options
	 * @param transactionOptions the transaction options
	 * @param scheduler          the executor service
	 * @param catalogFilePath    the path to the catalog file
	 * @param kryoPool           the Kryo pool
	 * @return a CatalogWriteAheadLog object if WAL files are present, otherwise null
	 */
	@Nullable
	static CatalogWriteAheadLog createWalIfAnyWalFilePresent(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer bootstrapFileTrimFunction,
		@Nonnull Supplier<WalPurgeCallback> onWalPurgeCallback,
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
				catalogVersion, catalogName, walFileNameProvider, catalogFilePath, kryoPool,
				storageOptions, transactionOptions, scheduler,
				bootstrapFileTrimFunction, onWalPurgeCallback.get()
			);
		}
	}

	/**
	 * Deserializes the catalog bootstrap record from the file on specified position.
	 *
	 * @param storageOptions    the storage options
	 * @param bootstrapFilePath the path to the catalog bootstrap file
	 * @param fromPosition      the position in the file to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap deserializeCatalogBootstrapRecord(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path bootstrapFilePath,
		long fromPosition
	) {
		return deserializeCatalogBootstrapRecord(
			storageOptions, bootstrapFilePath, fromPosition,
			DefaultCatalogPersistenceService::deserializeCatalogBootstrapRecord
		);
	}

	/**
	 * Deserializes the catalog bootstrap record from the file on specified position.
	 *
	 * @param storageOptions    the storage options
	 * @param bootstrapFilePath the path to the catalog bootstrap file
	 * @param fromPosition      the position in the file to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap deserializeCatalogBootstrapRecord(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path bootstrapFilePath,
		long fromPosition,
		@Nonnull BiFunction<Long, ReadOnlyFileHandle, CatalogBootstrap> reader
	) {
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions)
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
		long fromPosition, @Nonnull ReadOnlyFileHandle readHandle) {
		final StorageRecord<CatalogBootstrap> storageRecord = readHandle.execute(
			input -> {
				Assert.isPremiseValid(
					!input.isCompressionEnabled(),
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
	 * @param storageOptions     The storage options for the catalog.
	 * @return The CatalogWriteAheadLog object for the given catalog, and creates new if it doesn't exists and catalog
	 * is in transactional mode, it returns NULL if catalog is in warm-up mode
	 */
	@Nullable
	private static CatalogWriteAheadLog getCatalogWriteAheadLog(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull IntFunction<String> walFileNameProvider,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull LongConsumer bootstrapFileTrimFunction,
		@Nonnull Supplier<WalPurgeCallback> onWalPurgeCallback
	) {
		LogFileRecordReference currentWalFileRef = catalogHeader.walFileReference();
		if (catalogHeader.catalogState() == CatalogState.ALIVE && currentWalFileRef == null) {
			// set up new empty WAL file
			currentWalFileRef = new LogFileRecordReference(walFileNameProvider, 0, null);
			final Path walFilePath = catalogStoragePath.resolve(
				walFileNameProvider.apply(currentWalFileRef.fileIndex()));
			Assert.isPremiseValid(
				!walFilePath.toFile().exists(),
				() -> new UnexpectedIOException(
					"WAL file `" + walFilePath + "` is not expected to exist at this point, but it does!",
					"WAL file is not expected to exist at this point, but it does!"
				)
			);
			try {
				Assert.isPremiseValid(
					walFilePath.toFile().createNewFile(),
					() -> new UnexpectedIOException(
						"WAL file `" + walFilePath + "` was unexpectedly not created!",
						"WAL file was unexpectedly not created!"
					)
				);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to create WAL file `" + walFilePath + "`!",
					"Failed to create WAL file!",
					e
				);
			}
		}
		return ofNullable(currentWalFileRef)
			.map(
				walFileReference -> new CatalogWriteAheadLog(
					catalogVersion, catalogName, walFileNameProvider,
					catalogStoragePath, catalogKryoPool,
					storageOptions, transactionOptions, scheduler,
					bootstrapFileTrimFunction, onWalPurgeCallback.get()
				)
			)
			.orElse(null);
	}

	/**
	 * Reads a catalog version from a file handle and returns a StoredVersion object.
	 *
	 * @param readHandle        The file handle used for reading the catalog version.
	 * @param positionForRecord The position in the file for the catalog record.
	 * @return The StoredVersion object containing the catalog version and timestamp.
	 */
	@Nonnull
	private static StoredVersion readCatalogVersion(@Nonnull ReadOnlyFileHandle readHandle, long positionForRecord) {
		final CatalogBootstrap catalogBootstrap = deserializeCatalogBootstrapRecord(positionForRecord, readHandle);
		return new StoredVersion(
			catalogBootstrap.catalogVersion(),
			catalogBootstrap.timestamp()
		);
	}

	/**
	 * Reports changes in historical records kept.
	 *
	 * @param catalogName            name of the catalog
	 * @param oldestHistoricalRecord oldest historical record
	 */
	private static void reportOldestHistoricalRecord(
		@Nonnull String catalogName, @Nullable OffsetDateTime oldestHistoricalRecord) {
		new OffsetIndexHistoryKeptEvent(
			catalogName,
			FileType.CATALOG,
			catalogName,
			oldestHistoricalRecord
		).commit();
	}

	/**
	 * Returns current date & time in epoch milliseconds.
	 *
	 * @return current date & time in epoch milliseconds
	 */
	private static long getNowEpochMillis() {
		return CURRENT_TIME_MILLIS.getAsLong();
	}

	/**
	 * The only place with fixed record size is the bootstrap file, which means we must not allow compression for it
	 * even if it would be enabled in the main configuration. Compression would ultimately lead to variable record size
	 * and we would not be able to read the records correctly.
	 *
	 * @param storageOptions the storage options
	 * @return the storage options with compression disabled
	 */
	@Nonnull
	private static StorageOptions modifyStorageOptionsForBootstrapFile(@Nonnull StorageOptions storageOptions) {
		return StorageOptions.builder(storageOptions)
		                     .outputBufferSize(CatalogBootstrap.BOOTSTRAP_RECORD_SIZE)
		                     .computeCRC32(true)
		                     .compress(false)
		                     .build();
	}

	/**
	 * Retrieves last catalog bootstrap from the bootstrap file and returns it if the file is not empty.
	 * When the bootstrap file is in old format, it performs automatic upgrade on it and all catalog files.
	 *
	 * @param catalogName    name of the catalog
	 * @param storageOptions bootstrap storage options
	 * @return the last catalog bootstrap after the upgrade has been performed, otherwise exception is thrown
	 */
	@Nonnull
	private static CatalogBootstrap getLastCatalogBootstrapWithAutomaticUpgrade(
		@Nonnull String catalogName,
		@Nonnull StorageOptions bootstrapStorageOptions,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ExportFileService exportFileService
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = bootstrapStorageOptions.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final long lastMeaningfulPosition = CatalogBootstrap.getLastMeaningfulPosition(length);
			try {
				return deserializeCatalogBootstrapRecord(
					bootstrapStorageOptions, bootstrapFilePath, lastMeaningfulPosition);
			} catch (CorruptedRecordException ex) {
				// corruption may signalize old format
				final long lastMeaningfulOldPosition = Migration_2025_1.getOldLastMeaningfulPosition(length);
				// this will either read old bootstrap and verify CRC32C checksum or throw exception
				final CatalogBootstrap oldBootstrap = deserializeCatalogBootstrapRecord(
					bootstrapStorageOptions, bootstrapFilePath, lastMeaningfulOldPosition,
					Migration_2025_1::deserializeOldCatalogBootstrapRecord
				);
				// upgrade the bootstrap file and all catalog files
				Migration_2025_1.upgradeCatalogFiles(
					catalogName, bootstrapStorageOptions, storageOptions, catalogStoragePath, bootstrapFilePath,
					exportFileService
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
	 * Creates a write-only file handle for the bootstrap catalog file.
	 *
	 * @param catalogName        the name of the catalog for which the bootstrap handle is to be created
	 * @param storageOptions     the storage options to configure the file handle
	 * @param catalogStoragePath the path to the catalog storage directory
	 * @return a new instance of {@code WriteOnlyFileHandle} configured for the catalog bootstrap file
	 */
	@Nonnull
	static BootstrapWriteOnlyFileHandle createBootstrapWriteOnlyHandle(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path catalogStoragePath
	) {
		return new BootstrapWriteOnlyFileHandle(
			catalogName,
			FileType.CATALOG,
			catalogName,
			storageOptions,
			catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName))
		);
	}

	/**
	 * Returns the file name with renaming the files that contain original catalog name.
	 * This method is based on the logic from RestoreTask.
	 *
	 * @param fileName            the original file name
	 * @param catalogName         the new catalog name
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

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
	) {
		this.storageOptions = storageOptions;
		this.bootstrapStorageOptions = modifyStorageOptionsForBootstrapFile(this.storageOptions);
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.exportFileService = exportFileService;
		this.offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			catalogName,
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = pathForCatalog(catalogName, this.storageOptions.storageDirectory());
		verifyDirectory(this.catalogStoragePath, true);
		this.observableOutputKeeper = new ObservableOutputKeeper(catalogName, this.storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			this.storageOptions.timeTravelEnabled(),
			this::fetchDataFilesInfo
		);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		final CatalogBootstrap lastCatalogBootstrap = getLastCatalogBootstrap(
			verifiedCatalogName, this.bootstrapStorageOptions);
		this.bootstrapWriteHandle = new AtomicReference<>(
			createBootstrapWriteOnlyHandle(catalogName, this.bootstrapStorageOptions, this.catalogStoragePath)
		);

		final long catalogVersion = 0L;
		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName, this.walFileNameProvider,
			this.storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile,
			this.obsoleteFileMaintainer::createWalPurgeCallback,
			this.catalogStoragePath,
			this.walKryoPool
		);

		final String catalogFileName = getCatalogDataStoreFileName(
			catalogName, lastCatalogBootstrap.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFilePath,
				this.storageOptions,
				this.transactionOptions,
				lastCatalogBootstrap,
				this.recordTypeRegistry,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				VERSIONED_KRYO_FACTORY,
				nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null))
			)
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.warmUpVersionCardinality = 1;

		if (lastCatalogBootstrap.fileLocation() == null) {
			this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, 0, null);
		} else {
			this.bootstrapUsed = lastCatalogBootstrap;
		}

		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(16);
	}

	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportFileService exportFileService
	) {
		this.storageOptions = storageOptions;
		this.bootstrapStorageOptions = modifyStorageOptionsForBootstrapFile(this.storageOptions);
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.exportFileService = exportFileService;
		this.offHeapMemoryManager = new CatalogOffHeapMemoryManager(
			catalogName,
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = pathForCatalog(catalogName, this.storageOptions.storageDirectory());
		this.observableOutputKeeper = new ObservableOutputKeeper(catalogName, this.storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			this.storageOptions.timeTravelEnabled(),
			this::fetchDataFilesInfo
		);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		// TOBEDONE #538 - introduced with #650 and could be removed later when no version prior to 2025.2 is used
		// TOBEDONE #538 - original contents: getLastCatalogBootstrap(verifiedCatalogName, this.bootstrapStorageOptions);
		this.bootstrapUsed = getLastCatalogBootstrapWithAutomaticUpgrade(
			verifiedCatalogName, this.bootstrapStorageOptions, this.storageOptions, exportFileService
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			createBootstrapWriteOnlyHandle(catalogName, this.bootstrapStorageOptions, this.catalogStoragePath)
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		final long catalogVersion = this.bootstrapUsed.catalogVersion();
		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName, this.walFileNameProvider,
			this.storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile, this.obsoleteFileMaintainer::createWalPurgeCallback,
			this.catalogStoragePath, this.walKryoPool
		);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService = verifyAndUpgradeStorageFormat(
			() -> CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFilePath,
				this.storageOptions,
				this.transactionOptions,
				this.bootstrapUsed,
				this.recordTypeRegistry,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				VERSIONED_KRYO_FACTORY,
				nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null))
			),
			this.bootstrapUsed.catalogVersion()
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
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes().size()
		);

		try {
			final File restoreFlagFile = this.catalogStoragePath.resolve(CatalogPersistenceService.RESTORE_FLAG)
			                                                    .toFile();
			verifyCatalogNameMatches(
				catalogInstance, catalogVersion, this.catalogStoragePath,
				catalogStoragePartPersistenceService, restoreFlagFile.exists() ?
					OnDifferentCatalogName.ADAPT : OnDifferentCatalogName.THROW_EXCEPTION,
				this.bootstrapUsed
			);
			if (restoreFlagFile.exists()) {
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
	}

	private DefaultCatalogPersistenceService(
		@Nonnull DefaultCatalogPersistenceService previous,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService previousCatalogStoragePartPersistenceService,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogBootstrap bootstrapUsed,
		@Nonnull BootstrapWriteOnlyFileHandle bootstrapWriteHandle,
		@Nonnull Map<CollectionFileReference, DefaultEntityCollectionPersistenceService> previousEntityCollectionPersistenceServices
	) {
		this.bootstrapUsed = bootstrapUsed;
		this.recordTypeRegistry = previous.recordTypeRegistry;
		this.observableOutputKeeper = previous.observableOutputKeeper;
		this.offHeapMemoryManager = previous.offHeapMemoryManager;
		this.exportFileService = previous.exportFileService;
		this.catalogName = catalogName;
		this.walFileNameProvider = index -> CatalogPersistenceService.getWalFileName(catalogName, index);
		this.catalogStoragePath = catalogStoragePath;
		this.bootstrapWriteHandle = new AtomicReference<>(bootstrapWriteHandle);
		this.storageOptions = previous.storageOptions;
		this.bootstrapStorageOptions = previous.bootstrapStorageOptions;
		this.transactionOptions = previous.transactionOptions;
		this.scheduler = previous.scheduler;
		this.obsoleteFileMaintainer = previous.obsoleteFileMaintainer;
		this.catalogWal = previous.catalogWal;

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		final long catalogVersion = this.bootstrapUsed.catalogVersion();

		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogVersion,
			catalogFileName,
			catalogFilePath,
			this.storageOptions,
			this.transactionOptions,
			this.bootstrapUsed,
			this.recordTypeRegistry,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			VERSIONED_KRYO_FACTORY,
			nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
			oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null)),
			previousCatalogStoragePartPersistenceService
		);
		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.warmUpVersionCardinality = catalogVersion == 0 ? 1 : 0;

		final CatalogHeader catalogHeader = catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogHeader.getEntityTypeFileIndexes().size()
		);

		// we rebuild the storage services for all entity collections building upon previous versions
		for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
			final CollectionFileReference reference = new CollectionFileReference(
				entityTypeFileIndex.entityType(),
				entityTypeFileIndex.entityTypePrimaryKey(),
				entityTypeFileIndex.fileIndex(),
				entityTypeFileIndex.fileLocation()
			);

			final DefaultEntityCollectionPersistenceService previousService = previousEntityCollectionPersistenceServices.get(
				reference);
			this.entityCollectionPersistenceServices.put(
				reference,
				new DefaultEntityCollectionPersistenceService(
					this.bootstrapUsed.catalogVersion(),
					this.catalogName,
					this.catalogStoragePath,
					previousService,
					this.storageOptions,
					this.recordTypeRegistry
				)
			);
		}
	}

	@Override
	public void emitObservabilityEvents() {
		// emit statistics event
		final CatalogHeader catalogHeader = getCatalogHeader(this.bootstrapUsed.catalogVersion());
		new CatalogStatisticsEvent(
			this.catalogName,
			catalogHeader.getEntityTypeFileIndexes().size(),
			FileUtils.getDirectorySize(this.catalogStoragePath),
			getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageOptions)
				.map(CatalogBootstrap::timestamp)
				.orElse(null)
		).commit();
		// emit WAL events if it exists
		if (this.catalogWal != null) {
			this.catalogWal.emitObservabilityEvents();
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

	@Nonnull
	@Override
	public CatalogHeader getCatalogHeader(long catalogVersion) {
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
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
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
				sharedUniqueIndexes.put(
					attributeKey,
					new GlobalUniqueIndex(
						scope,
						attributeKey,
						attributeSchema.getPlainType(),
						sharedUniqueIndexStoragePart.getUniqueValueToRecordId(),
						sharedUniqueIndexStoragePart.getLocaleIndex()
					)
				);
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
		@Nonnull List<EntityCollectionHeader> entityHeaders,
		@Nonnull DataStoreMemoryBuffer dataStoreMemoryBuffer
	) {
		// first we need to execute transition to alive state
		if (catalogState == CatalogState.ALIVE && catalogVersion == 0L) {
			this.bootstrapUsed = recordBootstrap(
				catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex(), dataStoreMemoryBuffer);
			catalogVersion = 1L;
		}
		// first store all entity collection headers if they differ
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader currentCatalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
		for (EntityCollectionHeader entityHeader : entityHeaders) {
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
		this.bootstrapUsed = recordBootstrap(
			catalogVersion,
			this.catalogName,
			this.bootstrapUsed.catalogFileIndex(),
			dataStoreMemoryBuffer
		);

		// notify WAL that the new version was successfully stored
		if (this.catalogWal != null) {
			this.catalogWal.walProcessedUntil(catalogVersion);
		}

		// emit event if the number of collections has changed
		if (getNowEpochMillis() - this.lastCatalogStatisticsTimestamp > 1000) {
			new CatalogStatisticsEvent(
				this.catalogName,
				entityHeaders.size(),
				FileUtils.getDirectorySize(this.catalogStoragePath),
				getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageOptions)
					.map(CatalogBootstrap::timestamp)
					.orElse(null)
			).commit();
			this.lastCatalogStatisticsTimestamp = getNowEpochMillis();
		}
	}

	@Nonnull
	@Override
	public DefaultEntityCollectionPersistenceService getOrCreateEntityCollectionPersistenceService(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey
	) {
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final EntityCollectionHeader entityCollectionHeader = ofNullable(
			storagePartPersistenceService.getStoragePart(
				catalogVersion, entityTypePrimaryKey, EntityCollectionHeader.class
			)
		)
			.orElseGet(
				() -> new EntityCollectionHeader(
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
		@Nonnull EntityCollectionHeader entityCollectionHeader,
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
			if (newDescriptor.version() > previousVersion &&
				newDescriptor.getActiveRecordShare() < this.storageOptions.minimalActiveRecordShare() &&
				newDescriptor.getFileSize() > this.storageOptions.fileSizeCompactionThresholdBytes()
			) {
				log.info(
					"Compacting catalog `{}` entity collection `{}`, size exceeds threshold `{}` and active record share is `{}`%, " +
						"entity collection files on disk consume `{}` bytes.",
					this.catalogName,
					entityCollectionHeader.entityType(),
					newDescriptor.getFileSize(),
					newDescriptor.getActiveRecordShare(),
					entityCollectionPersistenceService.getSizeOnDiskInBytes()
				);

				final EntityCollectionHeader compactedHeader = entityCollectionPersistenceService.compact(
					this.catalogName, catalogVersion, headerInfoSupplier);
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
						this.storageOptions,
						this.transactionOptions,
						this.offHeapMemoryManager,
						this.observableOutputKeeper,
						this.recordTypeRegistry
					)
				);
				if (dataStoreBuffer instanceof WarmUpDataStoreMemoryBuffer warmUpDataStoreMemoryBuffer) {
					warmUpDataStoreMemoryBuffer.setPersistenceService(
						newPersistenceService.getStoragePartPersistenceService());
				}
				this.obsoleteFileMaintainer.removeFileWhenNotUsed(
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
	public EntityCollectionHeader getEntityCollectionHeader(
		long catalogVersion,
		int entityTypePrimaryKey
	) {
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		return Objects.requireNonNull(
			storagePartPersistenceService.getStoragePart(
				catalogVersion,
				entityTypePrimaryKey,
				EntityCollectionHeader.class
			)
		);
	}

	@Override
	public void goLive(long catalogVersion) {
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader currentCatalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
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
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader currentCatalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
		boolean hasChanges = false;
		for (EntityCollectionHeader entityHeader : entityCollectionHeaders) {
			final FileLocation currentLocation = entityHeader.fileLocation();
			final Optional<FileLocation> previousLocation = currentCatalogHeader
				.getEntityTypeFileIndexIfExists(entityHeader.entityType())
				.map(CollectionFileReference::fileLocation);
			// if the location is different, store the header
			if (!previousLocation.map(it -> it.equals(currentLocation)).orElse(false)) {
				storagePartPersistenceService.putStoragePart(catalogVersion, entityHeader);
				hasChanges = true;
			}
		}

		if (hasChanges) {
			storagePartPersistenceService.writeCatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				catalogVersion,
				this.catalogStoragePath,
				currentCatalogHeader.walFileReference(),
				Arrays.stream(entityCollectionHeaders)
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
				currentCatalogHeader.catalogId(),
				currentCatalogHeader.catalogName(),
				currentCatalogHeader.catalogState(),
				currentCatalogHeader.lastEntityCollectionPrimaryKey()
			);
		}
	}

	@Nonnull
	@Override
	public IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId) {
		return new DefaultIsolatedWalService(
			transactionId,
			this.walKryoPool.obtain(),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.transactionOptions.transactionWorkDirectory()
				                       .resolve(transactionId.toString())
				                       .resolve(transactionId + ".wal"),
				this.storageOptions,
				this.observableOutputKeeper,
				this.offHeapMemoryManager
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
	public long appendWalAndDiscard(
		long catalogVersion,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		try (walReference) {
			if (this.catalogWal == null) {
				final CatalogHeader catalogHeader = getCatalogHeader(catalogVersion);
				this.catalogWal = getCatalogWriteAheadLog(
					this.bootstrapUsed.catalogVersion(), this.catalogName, this.walFileNameProvider,
					this.catalogStoragePath, catalogHeader, this.walKryoPool,
					this.storageOptions, this.transactionOptions, this.scheduler,
					this::trimBootstrapFile,
					this.obsoleteFileMaintainer::createWalPurgeCallback
				);
			}
			Assert.isPremiseValid(
				walReference.getBuffer().isPresent() || walReference.getFilePath().isPresent(),
				"Unexpected WAL reference - neither off-heap buffer nor file reference present!"
			);
			Assert.isPremiseValid(
				this.catalogWal != null,
				"Catalog WAL is unexpectedly not present!"
			);

			return Objects.requireNonNull(this.catalogWal.append(transactionMutation, walReference).fileLocation())
			              .recordLength();
		}
	}

	@Nonnull
	@Override
	public Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(
		long catalogVersion
	) {
		if (this.catalogWal == null) {
			return Optional.empty();
		} else {
			return this.catalogWal.getFirstNonProcessedTransaction(getCatalogHeader(catalogVersion).walFileReference())
				.map(TransactionMutationWithWalFileReference::transactionMutation);
		}
	}

	@Nonnull
	@Override
	public CatalogPersistenceService replaceWith(
		long catalogVersion,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull DataStoreMemoryBuffer dataStoreMemoryBuffer,
		@Nonnull BiIntConsumer progressObserver
	) {
		final Path newPath = pathForCatalog(catalogNameToBeReplaced, this.storageOptions.storageDirectory());
		final boolean targetPathExists = newPath.toFile().exists();
		if (targetPathExists) {
			Assert.isPremiseValid(
				newPath.toFile().isDirectory(), () -> "Path `" + newPath.toAbsolutePath() + "` is not a directory!");
		}

		// store the catalog that replaces the original header
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final CatalogHeader catalogHeader = getCatalogHeader(catalogVersion);
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
		final CatalogBootstrap newCatalogBootstrap = recordBootstrap(
			newCatalogVersion,
			catalogNameToBeReplaced,
			catalogIndex,
			dataStoreMemoryBuffer
		);

		final HashMap<CollectionFileReference, DefaultEntityCollectionPersistenceService> previousEntityCollectionServices = new HashMap<>(
			this.entityCollectionPersistenceServices
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
				this,
				storagePartPersistenceService,
				catalogNameToBeReplaced,
				newPath,
				newCatalogBootstrap,
				createBootstrapWriteOnlyHandle(catalogNameToBeReplaced, this.bootstrapStorageOptions, newPath),
				previousEntityCollectionServices
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
	public EntityCollectionPersistenceService replaceCollectionWith(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType
	) {
		final CatalogHeader catalogHeader = getCatalogHeader(catalogVersion);
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
		final EntityCollectionHeader newEntityCollectionHeader;
		try {
			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(
				newFile.createNewFile(), "Cannot create new entity collection file: `" + newFilePath + "`!");
			try (final FileOutputStream fos = new FileOutputStream(newFile)) {
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
		this.obsoleteFileMaintainer.removeFileWhenNotUsed(
			catalogVersion - 1L,
			replacedEntityTypeFileReference.toFilePath(this.catalogStoragePath),
			() -> removeEntityCollectionPersistenceServiceAndClose(replacedEntityTypeFileReference)
		);
		return renamedPersistenceService;
	}

	@Override
	public void deleteEntityCollection(
		long catalogVersion,
		@Nonnull EntityCollectionHeader entityCollectionHeader
	) {
		final CollectionFileReference collectionFileReference = new CollectionFileReference(
			entityCollectionHeader.entityType(),
			entityCollectionHeader.entityTypePrimaryKey(),
			entityCollectionHeader.entityTypeFileIndex(),
			entityCollectionHeader.fileLocation()
		);
		this.obsoleteFileMaintainer.removeFileWhenNotUsed(
			catalogVersion - 1L,
			collectionFileReference.toFilePath(this.catalogStoragePath),
			() -> removeEntityCollectionPersistenceServiceAndClose(collectionFileReference)
		);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedMutationStream(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedReversedMutationStream(getLastCatalogVersion());
		}
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedLiveMutationStream(
		long startCatalogVersion, long requestedCatalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(
				startCatalogVersion, requestedCatalogVersion);
		}
	}

	@Override
	public long getLastCatalogVersionInMutationStream() {
		if (this.catalogWal == null) {
			return 0L;
		} else {
			return this.catalogWal.getLastWrittenVersion();
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
	public PaginatedList<StoredVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		final Path bootstrapFilePath = this.catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final int recordCount = CatalogBootstrap.getRecordCount(length);
			final int pageNumber = PaginatedList.isRequestedResultBehindLimit(page, pageSize, recordCount) ? 1 : page;
			try (
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
					bootstrapFilePath, this.bootstrapStorageOptions)
			) {
				final List<StoredVersion> catalogVersions = new ArrayList<>(pageSize);
				if (timeFlow == TimeFlow.FROM_OLDEST_TO_NEWEST) {
					final int firstNumber = PaginatedList.getFirstItemNumberForPage(pageNumber, pageSize);
					for (int i = firstNumber; i < Math.min(firstNumber + pageSize, recordCount); i++) {
						catalogVersions.add(
							readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(i))
						);
					}
				} else {
					final int firstNumber = recordCount - (((pageNumber - 1) * pageSize) + 1);
					for (int i = firstNumber; i > Math.max(firstNumber - pageSize, -1); i--) {
						catalogVersions.add(
							readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(i))
						);
					}
				}
				return new PaginatedList<>(
					pageNumber,
					pageSize,
					recordCount,
					catalogVersions
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
	public StoredVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		final CatalogBootstrap bootstrap;
		if (moment == null) {
			bootstrap = DefaultCatalogPersistenceService.getFirstCatalogBootstrap(
				                                            this.catalogName, this.bootstrapStorageOptions
			                                            )
			                                            .orElseThrow(TemporalDataNotAvailableException::new);
		} else {
			bootstrap = DefaultCatalogPersistenceService.getCatalogBootstrapForSpecificMoment(
				this.catalogName, this.bootstrapStorageOptions, moment
			);
		}
		return new StoredVersion(bootstrap.catalogVersion(), bootstrap.timestamp());
	}

	@Nonnull
	@Override
	public Stream<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		if (catalogVersion.length == 0) {
			return Stream.empty();
		}
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		final Path bootstrapFilePath = this.catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final Map<Long, StoredVersion> catalogVersionPreviousVersions = createPreviousCatalogVersionsIndex(
				catalogVersion, bootstrapFile, bootstrapFilePath
			);
			return this.catalogWal == null ?
				Stream.empty() :
				Arrays.stream(catalogVersion)
				      .mapToObj(
					      cv -> ofNullable(catalogVersionPreviousVersions.get(cv))
						      .map(it -> this.catalogWal.getWriteAheadLogVersionDescriptor(
							      cv, it.version(),
							      it.introducedAt()
						      ))
						      .orElse(null))
				      .filter(Objects::nonNull);
		} else {
			return Stream.empty();
		}
	}

	@Override
	public void purgeAllObsoleteFiles() {
		try {
			final CatalogBootstrap catalogBootstrap = this.storageOptions.timeTravelEnabled() ?
				// if time travel is enabled we need to keep all the files that are referenced in the bootstrap file
				getFirstCatalogBootstrap(this.catalogName, this.bootstrapStorageOptions).orElse(this.bootstrapUsed) :
				// otherwise we can remove all the files that are not referenced in the current catalog header
				this.bootstrapUsed;

			final CatalogHeader catalogHeader = fetchCatalogHeader(catalogBootstrap);
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
		final CatalogBootstrap bootstrapRecord;
		if (catalogVersion != null) {
			bootstrapRecord = getCatalogBootstrapForSpecificVersion(
				this.catalogName, this.bootstrapStorageOptions, catalogVersion);
		} else if (pastMoment != null) {
			bootstrapRecord = getCatalogBootstrapForSpecificMoment(
				this.catalogName, this.bootstrapStorageOptions, pastMoment);
		} else {
			bootstrapRecord = this.bootstrapUsed;
		}
		return new BackupTask(
			this.catalogName, pastMoment, catalogVersion, includingWAL,
			bootstrapRecord, this.exportFileService, this,
			onStart, onComplete
		);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> createFullBackupTask(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		return new FullBackupTask(
			this.catalogName,
			this.exportFileService,
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
						final Integer suffixPriority = suffix == null ? null : ALLOWED_SUFFIXES_WITH_PRIORITY.get("." + suffix);
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
		Assert.isPremiseValid(
			getCatalogHeader(this.bootstrapUsed.catalogVersion()).version() == this.bootstrapUsed.catalogVersion(),
			"Catalog version mismatch! Expected `" + this.bootstrapUsed.catalogVersion() + "` but found `" + getCatalogHeader(
				this.bootstrapUsed.catalogVersion()).version() + "`!"
		);
		if (this.catalogWal != null) {
			Assert.isPremiseValid(
				this.catalogWal.getLastWrittenVersion() == this.bootstrapUsed.catalogVersion(),
				"Catalog WAL version mismatch! Expected `" + this.bootstrapUsed.catalogVersion() + "` but found `" + this.catalogWal.getLastWrittenVersion() + "`!"
			);
		}
	}

	@Override
	public long getSizeOnDiskInBytes() {
		return FileUtils.getDirectorySize(this.catalogStoragePath);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			// close WAL
			if (this.catalogWal != null) {
				IOUtils.closeQuietly(this.catalogWal::close);
			}
			// close all services
			IOUtils.closeQuietly(
				this.entityCollectionPersistenceServices
					.values()
					.stream()
					.map(service -> (IOExceptionThrowingRunnable) service::close)
					.toArray(IOExceptionThrowingRunnable[]::new)
			);
			this.entityCollectionPersistenceServices.clear();
			// close current file offset index
			IOUtils.closeQuietly(
				this.catalogStoragePartPersistenceService
					.values()
					.stream()
					.map(service -> (IOExceptionThrowingRunnable) service::close)
					.toArray(IOExceptionThrowingRunnable[]::new)
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
	public void consumersLeft(long lastKnownMinimalActiveVersion) {
		this.catalogStoragePartPersistenceService.values().forEach(
			it -> it.purgeHistoryOlderThan(lastKnownMinimalActiveVersion));
		this.obsoleteFileMaintainer.consumersLeft(lastKnownMinimalActiveVersion);
		this.entityCollectionPersistenceServices.values()
		                                        .forEach(it -> it.consumersLeft(lastKnownMinimalActiveVersion));
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
			storagePartPersistenceService.putStoragePart(catalogVersion, it.next());

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
			this.storageOptions,
			this.transactionOptions,
			catalogBootstrap,
			this.recordTypeRegistry,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			VERSIONED_KRYO_FACTORY,
			nonFlushedBlock -> this.reportNonFlushedContents(this.catalogName, nonFlushedBlock),
			oldestRecordTimestamp -> reportOldestHistoricalRecord(this.catalogName, oldestRecordTimestamp.orElse(null))
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
		@Nonnull EntityCollectionHeader entityCollectionHeader
	) {
		return new DefaultEntityCollectionPersistenceService(
			this.bootstrapUsed.catalogVersion(),
			this.catalogName,
			this.catalogStoragePath,
			entityCollectionHeader,
			this.storageOptions,
			this.transactionOptions,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			this.recordTypeRegistry
		);
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
	 * Records a bootstrap in the catalog.
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
		final OffsetDateTime bootstrapWriteTime = Instant.ofEpochMilli(timestamp)
		                                                 .atZone(ZoneId.systemDefault())
		                                                 .toOffsetDateTime();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(
			catalogVersion);
		final OffsetIndexDescriptor flushedDescriptor = storagePartPersistenceService.flush(catalogVersion);
		final CatalogBootstrap bootstrapRecord;
		if (flushedDescriptor.getActiveRecordShare() < this.storageOptions.minimalActiveRecordShare() &&
			flushedDescriptor.getFileSize() > this.storageOptions.fileSizeCompactionThresholdBytes()) {

			final DataFileCompactEvent event = new DataFileCompactEvent(
				this.catalogName,
				FileType.CATALOG,
				this.catalogName
			);

			final int newCatalogFileIndex = catalogFileIndex + 1;
			final String compactedFileName = getCatalogDataStoreFileName(newCatalogName, newCatalogFileIndex);
			final OffsetIndexDescriptor compactedDescriptor;
			try (final FileOutputStream fos = new FileOutputStream(
				this.catalogStoragePath.resolve(compactedFileName).toFile())) {
				compactedDescriptor = storagePartPersistenceService.copySnapshotTo(catalogVersion, fos, null);
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
					this.storageOptions,
					this.transactionOptions,
					bootstrapRecord,
					this.recordTypeRegistry,
					this.offHeapMemoryManager,
					this.observableOutputKeeper,
					VERSIONED_KRYO_FACTORY,
					nonFlushedBlock -> this.reportNonFlushedContents(this.catalogName, nonFlushedBlock),
					oldestRecordTimestamp -> DefaultCatalogPersistenceService.reportOldestHistoricalRecord(
						this.catalogName, oldestRecordTimestamp.orElse(null))
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

				this.obsoleteFileMaintainer.removeFileWhenNotUsed(
					catalogVersion,
					this.catalogStoragePath.resolve(getCatalogDataStoreFileName(newCatalogName, catalogFileIndex)),
					() -> removeCatalogPersistenceServiceForVersion(currentVersion)
				);

				if (dataStoreMemoryBuffer instanceof WarmUpDataStoreMemoryBuffer warmUpDataStoreMemoryBuffer) {
					warmUpDataStoreMemoryBuffer.setPersistenceService(newPersistenceService);
				}

			} catch (InterruptedException e) {
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
		return writeCatalogBootstrap(catalogVersion, newCatalogName, bootstrapRecord);
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
			// remove old persistence storages
			final InsertionPosition position = ArrayUtils.computeInsertPositionOfLongInOrderedArray(
				catalogVersion, this.catalogPersistenceServiceVersions);
			if (position.alreadyPresent()) {
				for (int i = 0; i < position.position(); i++) {
					removeCatalogPersistenceServiceForVersion(this.catalogPersistenceServiceVersions[0]);
				}
			}
		} catch (InterruptedException e) {
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
	 * Verifies that the catalog name (derived from catalog directory) matches the catalog schema stored in the catalog
	 * file.
	 *
	 * @param catalogInstance               the catalog contract instance
	 * @param catalogStoragePath            the path to the catalog storage directory
	 * @param storagePartPersistenceService the storage part persistence service
	 */
	private void verifyCatalogNameMatches(
		@Nonnull CatalogContract catalogInstance,
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull OnDifferentCatalogName onDifferentCatalogName,
		@Nonnull CatalogBootstrap bootstrapUsed
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogHeader catalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
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
					.map(it -> new LogFileRecordReference(this.walFileNameProvider, it.fileIndex(), it.fileLocation()))
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

			return bootstrapRecord;
		} catch (InterruptedException e) {
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
	 * Method verifies the storage protocol version of current {@link CatalogHeader} and attempts to upgrade it to
	 * the current storage protocol version if possible. Otherwise the exception is thrown.
	 *
	 * @param storagePartPersistenceFactory the factory for the storage part persistence service
	 * @param catalogVersion                the version of the catalog
	 * @return the storage part persistence service
	 * @throws ObsoleteStorageProtocolException if the storage protocol version is not compatible with the current one
	 */
	@Nonnull
	private CatalogOffsetIndexStoragePartPersistenceService verifyAndUpgradeStorageFormat(
		@Nonnull Supplier<CatalogOffsetIndexStoragePartPersistenceService> storagePartPersistenceFactory,
		long catalogVersion
	) throws ObsoleteStorageProtocolException {
		CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = storagePartPersistenceFactory.get();
		CatalogHeader catalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
		if (catalogHeader.storageProtocolVersion() == PersistenceService.STORAGE_PROTOCOL_VERSION) {
			return storagePartPersistenceService;
		} else {
			CatalogOffsetIndexStoragePartPersistenceService currentService = storagePartPersistenceService;
			do {
				final int catalogStorageProtocolVersion = catalogHeader.storageProtocolVersion();
				final CatalogHeader currentCatalogHeader = catalogHeader;
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
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		int storageProtocolVersion
	) {
		storagePartPersistenceService.writeCatalogHeader(
			storageProtocolVersion,
			catalogHeader.version(),
			this.catalogStoragePath,
			catalogHeader.walFileReference(),
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
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(fromFile, this.bootstrapStorageOptions)
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
			this.bootstrapStorageOptions
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
				this.bootstrapStorageOptions
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
	 * @param catalogVersion an array of catalog versions
	 * @return a {@link StoredVersion} object that contains PREVIOUS VERSION and CURRENT VERSION TIMESTAMP
	 * (this is a little bit hacky, but we avoid declaring new record type)
	 */
	@Nonnull
	private Map<Long, StoredVersion> createPreviousCatalogVersionsIndex(
		@Nonnull long[] catalogVersion,
		@Nonnull File bootstrapFile,
		@Nonnull Path bootstrapFilePath
	) {
		final long length = bootstrapFile.length();
		final int recordCount = CatalogBootstrap.getRecordCount(length);
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath, this.bootstrapStorageOptions)
		) {
			final int minCvIndex = ArrayUtils.binarySearch(
				index -> readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(index)),
				Arrays.stream(catalogVersion).min().orElseThrow(),
				recordCount,
				(cv, minimalCatalogVersion) -> Long.compare(cv.version(), minimalCatalogVersion)
			);
			final int maxCvIndex = ArrayUtils.binarySearch(
				index -> readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(index)),
				Arrays.stream(catalogVersion).max().orElseThrow(),
				recordCount,
				(cv, maximalCatalogVersion) -> Long.compare(cv.version(), maximalCatalogVersion)
			);
			// iterate over records between those versions
			final int normalizedMinCvIndex = Math.max(0, minCvIndex < 0 ? -minCvIndex - 2 : minCvIndex - 1);
			final int normalizedMaxCvIndex = Math.min(recordCount, maxCvIndex < 0 ? -maxCvIndex - 1 : maxCvIndex);
			final Map<Long, StoredVersion> catalogVersionPreviousVersions = CollectionUtils.createHashMap(
				normalizedMaxCvIndex - normalizedMinCvIndex + 1
			);
			StoredVersion previousVersion = minCvIndex == -1 ? new StoredVersion(-1L, OffsetDateTime.MIN) : null;
			for (int i = normalizedMinCvIndex; i <= normalizedMaxCvIndex; i++) {
				final StoredVersion cv = readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(i));
				if (previousVersion != null) {
					catalogVersionPreviousVersions.put(
						cv.version(),
						new StoredVersion(previousVersion.version(), cv.introducedAt())
					);
				}
				previousVersion = cv;
			}
			return catalogVersionPreviousVersions;
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + bootstrapFile.getAbsolutePath() + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
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
	 * This method finds bootstrap record for the given catalog version and returns its record along with the appropriate
	 * catalog header.
	 *
	 * @param catalogVersion the catalog version
	 * @return the catalog header and the bootstrap record
	 */
	@Nullable
	private DataFilesBulkInfo fetchDataFilesInfo(long catalogVersion) {
		try (final Stream<CatalogBootstrap> catalogBootstrapRecordStream = getCatalogBootstrapRecordStream(
			this.catalogName, this.bootstrapStorageOptions)) {
			final AtomicReference<CatalogBootstrap> lastExaminedBootstrap = new AtomicReference<>();
			return catalogBootstrapRecordStream
				.peek(lastExaminedBootstrap::set)
				.filter(it -> it.catalogVersion() == catalogVersion)
				.map(it -> new DataFilesBulkInfo(it, fetchCatalogHeader(it)))
				.findFirst()
				.orElseGet(() -> {
					// when particular catalog version is not found
					final CatalogBootstrap catalogBootstrap = lastExaminedBootstrap.get();
					// we return the first bootstrap record that is greater than the requested catalog version
					return catalogBootstrap.catalogVersion() > catalogVersion ?
						new DataFilesBulkInfo(catalogBootstrap, fetchCatalogHeader(catalogBootstrap)) : null;
				});
		}
	}

	/**
	 * Fetches the catalog header for the given catalog bootstrap record.
	 *
	 * @param bootstrap bootstrap record
	 * @return the catalog header
	 */
	@Nonnull
	private CatalogHeader fetchCatalogHeader(@Nonnull CatalogBootstrap bootstrap) {
		final String catalogFileName = getCatalogDataStoreFileName(this.catalogName, bootstrap.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		return readCatalogHeader(this.storageOptions, catalogFilePath, bootstrap, this.recordTypeRegistry);
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
			@Nonnull StorageOptions storageOptions
		) {
			this.readHandle = new ReadOnlyFileHandle(
				bootstrapFilePath,
				storageOptions
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
