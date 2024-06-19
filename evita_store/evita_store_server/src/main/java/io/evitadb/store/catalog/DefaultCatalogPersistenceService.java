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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogVersionBeyondTheHorizonListener;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.storage.OffsetIndexHistoryKeptEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexNonFlushedEvent;
import io.evitadb.core.metric.event.transaction.WalStatisticsEvent;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.store.catalog.ObsoleteFileMaintainer.DataFilesBulkInfo;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.exception.InvalidFileNameException;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
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
import io.evitadb.store.spi.exception.BootstrapFileNotFound;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService.readCatalogHeader;
import static io.evitadb.store.spi.CatalogPersistenceService.*;
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
public class DefaultCatalogPersistenceService implements CatalogPersistenceService, CatalogVersionBeyondTheHorizonListener {

	/**
	 * Factory function that configures new instance of the versioned kryo factory.
	 */
	static final Function<VersionedKryoKeyInputs, VersionedKryo> VERSIONED_KRYO_FACTORY = kryoKeyInputs -> VersionedKryoFactory.createKryo(
		kryoKeyInputs.version(),
		SchemaKryoConfigurer.INSTANCE
			.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
			.andThen(SharedClassesConfigurer.INSTANCE)
			.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
	);

	/**
	 * This supplier is overridden in tests to provide deterministic time. Do not use elsewhere.
	 */
	static LongSupplier CURRENT_TIME_MILLIS = System::currentTimeMillis;

	/**
	 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
	 * {@link ObservableOutput#getBuffer()} to use them for serialization. There buffers are not necessary when there are
	 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
	 */
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * The off-heap memory manager instance that is used for allocating off-heap memory regions for storing data.
	 */
	private final OffHeapMemoryManager offHeapMemoryManager;
	/**
	 * The name of the catalog that maps to {@link EntitySchema#getName()}.
	 */
	@Nonnull
	private final String catalogName;
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
	private final StorageOptions storageOptions;
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
	private final AtomicReference<WriteOnlyFileHandle> bootstrapWriteHandle;
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
	private final Pool<Kryo> catalogKryoPool = new Pool<>(true, false, 16) {
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
		Assert.isTrue(storageDirectoryFile.exists(), () -> new InvalidStoragePathException("Storage path doesn't exist: " + storageDirectory));
		Assert.isTrue(storageDirectoryFile.isDirectory(), () -> new InvalidStoragePathException("Storage path doesn't represent a directory: " + storageDirectory));
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
			throw new InvalidFileNameException("Name `" + catalogName + "` cannot be converted a valid file name: " + ex.getMessage() + "! Please rename the catalog.");
		}
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
			return of(readCatalogBootstrap(storageOptions, bootstrapFilePath, 0));
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
			() -> new TemporalDataNotAvailableException(pastMoment)
		);
		Assert.isTrue(
			getFirstCatalogBootstrap(catalogName, storageOptions)
				.map(it -> it.timestamp().compareTo(pastMoment) <= 0)
				.orElse(false),
			() -> new TemporalDataNotAvailableException(pastMoment)
		);

		return getCatalogBootstrapRecordStream(catalogName, storageOptions)
			.takeWhile(current -> !current.timestamp().isAfter(pastMoment))
			.reduce((previous, current) -> current)
			.orElseGet(() -> getLastCatalogBootstrap(catalogName, storageOptions));
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
			return readCatalogBootstrap(storageOptions, bootstrapFilePath, lastMeaningfulPosition);
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
	 * Deserializes the catalog bootstrap record from the file on specified position.
	 *
	 * @param storageOptions    the storage options
	 * @param bootstrapFilePath the path to the catalog bootstrap file
	 * @param fromPosition      the position in the file to read the record from
	 * @return the catalog bootstrap record
	 */
	@Nonnull
	private static CatalogBootstrap readCatalogBootstrap(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path bootstrapFilePath,
		long fromPosition
	) {
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
		) {
			return readCatalogBootstrap(fromPosition, readHandle);
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
	private static CatalogBootstrap readCatalogBootstrap(long fromPosition, @Nonnull ReadOnlyFileHandle readHandle) {
		return readHandle.execute(
			input -> StorageRecord.read(
				input,
				new FileLocation(fromPosition, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
				(theInput, recordLength) -> new CatalogBootstrap(
					theInput.readLong(),
					theInput.readInt(),
					Instant.ofEpochMilli(theInput.readLong()).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
					new FileLocation(
						theInput.readLong(),
						theInput.readInt()
					)
				)
			)
		).payload();
	}

	/**
	 * Verifies that the catalog name (derived from catalog directory) matches the catalog schema stored in the catalog
	 * file.
	 *
	 * @param catalogInstance               the catalog contract instance
	 * @param catalogName                   the catalog name to verify
	 * @param catalogStoragePath            the path to the catalog storage directory
	 * @param storagePartPersistenceService the storage part persistence service
	 */
	private static void verifyCatalogNameMatches(
		@Nonnull CatalogContract catalogInstance,
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull OnDifferentCatalogName onDifferentCatalogName
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogHeader catalogHeader = storagePartPersistenceService.getCatalogHeader(catalogVersion);
		final boolean catalogNameIsSame = catalogHeader.catalogName().equals(catalogName);
		if (onDifferentCatalogName.equals(OnDifferentCatalogName.THROW_EXCEPTION)) {
			Assert.isTrue(
				catalogNameIsSame,
				() -> new UnexpectedCatalogContentsException(
					"Directory " + catalogStoragePath + " contains data of " + catalogHeader.catalogName() +
						" catalog. Cannot load catalog " + catalogName + " from this directory!"
				)
			);
		} else if (!catalogNameIsSame) {
			// update name in the catalog header
			storagePartPersistenceService.writeCatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				catalogVersion,
				catalogStoragePath,
				ofNullable(catalogHeader.walFileReference())
					.map(it -> new WalFileReference(catalogName, it.fileIndex(), it.fileLocation()))
					.orElse(null),
				catalogHeader.collectionFileIndex(),
				catalogHeader.catalogId(),
				catalogName,
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
				catalogName,
				NamingConvention.generate(catalogName),
				catalogSchema.getDescription(),
				catalogSchema.getCatalogEvolutionMode(),
				catalogSchema.getAttributes(),
				MutationEntitySchemaAccessor.INSTANCE
			);
			storagePartPersistenceService.putStoragePart(
				catalogVersion, new CatalogSchemaStoragePart(updateCatalogSchema)
			);
		}
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
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Consumer<OffsetDateTime> bootstrapFileTrimFunction,
		@Nonnull LongConsumer onWalPurgeCallback
	) {
		WalFileReference currentWalFileRef = catalogHeader.walFileReference();
		if (catalogHeader.catalogState() == CatalogState.ALIVE && currentWalFileRef == null) {
			// set up new empty WAL file
			currentWalFileRef = new WalFileReference(catalogName, 0, null);
			final Path walFilePath = catalogStoragePath.resolve(getWalFileName(catalogName, currentWalFileRef.fileIndex()));
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
					catalogVersion, catalogName, catalogStoragePath, catalogKryoPool,
					storageOptions, transactionOptions, scheduler,
					bootstrapFileTrimFunction, onWalPurgeCallback
				)
			)
			.orElse(null);
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
	private static CatalogWriteAheadLog createWalIfAnyWalFilePresent(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Consumer<OffsetDateTime> bootstrapFileTrimFunction,
		@Nonnull LongConsumer onWalPurgeCallback,
		@Nonnull Path catalogFilePath,
		@Nonnull Pool<Kryo> kryoPool
	) {
		final File[] walFiles = catalogFilePath
			.toFile()
			.listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX));
		return walFiles == null || walFiles.length == 0 ?
			null :
			new CatalogWriteAheadLog(
				catalogVersion, catalogName, catalogFilePath, kryoPool,
				storageOptions, transactionOptions, scheduler,
				bootstrapFileTrimFunction, onWalPurgeCallback
			);
	}

	/**
	 * Reads a catalog version from a file handle and returns a CatalogVersion object.
	 *
	 * @param readHandle        The file handle used for reading the catalog version.
	 * @param positionForRecord The position in the file for the catalog record.
	 * @return The CatalogVersion object containing the catalog version and timestamp.
	 */
	@Nonnull
	private static CatalogVersion readCatalogVersion(@Nonnull ReadOnlyFileHandle readHandle, long positionForRecord) {
		final CatalogBootstrap catalogBootstrap = readHandle.execute(
			input -> StorageRecord.read(
				input,
				new FileLocation(positionForRecord, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
				(theInput, recordLength) -> new CatalogBootstrap(
					theInput.readLong(),
					theInput.readInt(),
					Instant.ofEpochMilli(theInput.readLong()).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
					new FileLocation(
						theInput.readLong(),
						theInput.readInt()
					)
				)
			)
		).payload();

		return new CatalogVersion(
			catalogBootstrap.catalogVersion(),
			catalogBootstrap.timestamp()
		);
	}

	/**
	 * Serializes the bootstrap record to the output and returns the {@link StorageRecord}.
	 *
	 * @param output          the output to write the record to
	 * @param bootstrapRecord the bootstrap record to serialize
	 * @return the {@link StorageRecord} with the serialized bootstrap record
	 */
	@Nonnull
	private static StorageRecord<CatalogBootstrap> serializeBootstrapRecord(
		@Nonnull ObservableOutput<?> output,
		@Nonnull CatalogBootstrap bootstrapRecord
	) {
		return new StorageRecord<>(
			output, bootstrapRecord.catalogVersion(), true,
			theOutput -> {
				theOutput.writeLong(bootstrapRecord.catalogVersion());
				theOutput.writeInt(bootstrapRecord.catalogFileIndex());
				theOutput.writeLong(bootstrapRecord.timestamp().toInstant().toEpochMilli());
				theOutput.writeLong(bootstrapRecord.fileLocation().startingPosition());
				theOutput.writeInt(bootstrapRecord.fileLocation().recordLength());
				return bootstrapRecord;
			}
		);
	}

	/**
	 * Copies the bootstrap record to the specified file handle.
	 *
	 * @param newBootstrapHandle The handle to the file where the bootstrap record will be copied.
	 * @param bootstrapRecord    The bootstrap record to be copied.
	 */
	private static void copyBootstrapRecord(
		@Nonnull WriteOnlyFileHandle newBootstrapHandle,
		@Nonnull CatalogBootstrap bootstrapRecord
	) {
		newBootstrapHandle.checkAndExecute(
			"copy bootstrap record",
			() -> {
			},
			output -> new StorageRecord<>(
				output, bootstrapRecord.catalogVersion(), true,
				theOutput -> {
					theOutput.writeLong(bootstrapRecord.catalogVersion());
					theOutput.writeInt(bootstrapRecord.catalogFileIndex());
					theOutput.writeLong(bootstrapRecord.timestamp().toInstant().toEpochMilli());
					theOutput.writeLong(bootstrapRecord.fileLocation().startingPosition());
					theOutput.writeInt(bootstrapRecord.fileLocation().recordLength());
					return bootstrapRecord;
				}
			).payload()
		);
	}

	/**
	 * Reports changes in historical records kept.
	 *
	 * @param catalogName            name of the catalog
	 * @param oldestHistoricalRecord oldest historical record
	 */
	private static void reportOldestHistoricalRecord(@Nonnull String catalogName, @Nullable OffsetDateTime oldestHistoricalRecord) {
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

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			catalogName,
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = pathForCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
		verifyDirectory(this.catalogStoragePath, true);
		this.observableOutputKeeper = new ObservableOutputKeeper(catalogName, storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			storageOptions.timeTravelEnabled(),
			this::fetchDataFilesInfo
		);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		final CatalogBootstrap lastCatalogBootstrap = getLastCatalogBootstrap(verifiedCatalogName, storageOptions);
		this.bootstrapWriteHandle = new AtomicReference<>(
			new WriteOnlyFileHandle(
				this.catalogName,
				FileType.CATALOG,
				this.catalogName,
				this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
				this.observableOutputKeeper
			)
		);

		final long catalogVersion = 0L;
		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName,
			storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile, this.obsoleteFileMaintainer::firstAvailableCatalogVersionChanged,
			this.catalogStoragePath, this.catalogKryoPool
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, lastCatalogBootstrap.catalogFileIndex());
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
				recordTypeRegistry,
				offHeapMemoryManager,
				observableOutputKeeper,
				VERSIONED_KRYO_FACTORY,
				nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestRecordTimestamp -> reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null))
			)
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};

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
		@Nonnull Scheduler scheduler
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			catalogName,
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = pathForCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
		this.observableOutputKeeper = new ObservableOutputKeeper(catalogName, storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(
			catalogName,
			scheduler,
			this.catalogStoragePath,
			storageOptions.timeTravelEnabled(),
			this::fetchDataFilesInfo
		);
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		this.bootstrapUsed = getLastCatalogBootstrap(verifiedCatalogName, storageOptions);
		this.bootstrapWriteHandle = new AtomicReference<>(
			new WriteOnlyFileHandle(
				this.catalogName,
				FileType.CATALOG,
				this.catalogName,
				this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
				this.observableOutputKeeper
			)
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		final long catalogVersion = this.bootstrapUsed.catalogVersion();
		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogVersion, catalogName, storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile, this.obsoleteFileMaintainer::firstAvailableCatalogVersionChanged,
			this.catalogStoragePath, this.catalogKryoPool
		);

		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService =
			CatalogOffsetIndexStoragePartPersistenceService.create(
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
			);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};

		final File restoreFlagFile = catalogStoragePath.resolve(CatalogPersistenceService.RESTORE_FLAG).toFile();
		verifyCatalogNameMatches(
			catalogInstance, catalogVersion, catalogName, catalogStoragePath,
			catalogStoragePartPersistenceService, restoreFlagFile.exists() ?
				OnDifferentCatalogName.ADAPT : OnDifferentCatalogName.THROW_EXCEPTION
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
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes().size()
		);
	}

	private DefaultCatalogPersistenceService(
		@Nonnull DefaultCatalogPersistenceService previous,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService previousCatalogStoragePartPersistenceService,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogBootstrap bootstrapUsed,
		@Nonnull WriteOnlyFileHandle bootstrapWriteHandle,
		@Nonnull Map<CollectionFileReference, DefaultEntityCollectionPersistenceService> previousEntityCollectionPersistenceServices
	) {
		this.bootstrapUsed = bootstrapUsed;
		this.recordTypeRegistry = previous.recordTypeRegistry;
		this.observableOutputKeeper = previous.observableOutputKeeper;
		this.offHeapMemoryManager = previous.offHeapMemoryManager;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.bootstrapWriteHandle = new AtomicReference<>(bootstrapWriteHandle);
		this.storageOptions = previous.storageOptions;
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

			final DefaultEntityCollectionPersistenceService previousService = previousEntityCollectionPersistenceServices.get(reference);
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
	public void emitStartObservabilityEvents() {
		// emit statistics event
		final CatalogHeader catalogHeader = getCatalogHeader(this.bootstrapUsed.catalogVersion());
		new CatalogStatisticsEvent(
			this.catalogName,
			catalogHeader.getEntityTypeFileIndexes().size(),
			FileUtils.getDirectorySize(this.catalogStoragePath),
			getFirstCatalogBootstrap(this.catalogName, this.storageOptions)
				.map(CatalogBootstrap::timestamp)
				.orElse(null)
		).commit();
		// emit WAL events if it exists
		if (this.catalogWal != null) {
			this.catalogWal.emitObservabilityEvents();
		}
	}

	@Override
	public void emitDeleteObservabilityEvents() {
		// emit statistics event
		new CatalogStatisticsEvent(
			this.catalogName,
			0,
			0,
			null
		).commit();
		// emit WAL events if it exists
		if (this.catalogWal != null) {
			new WalStatisticsEvent(
				this.catalogName,
				null
			).commit();
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
				() -> new GenericEvitaInternalError("Catalog version " + catalogVersion + " not found in the catalog persistence service versions!")
			);
			return this.catalogStoragePartPersistenceService.get(
				this.catalogPersistenceServiceVersions[lookupIndex]
			);
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
	public void verifyEntityType(@Nonnull Collection<EntityCollection> existingEntityCollections, @Nonnull String entityType)
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
				.filter(nameVariant -> nameVariant.getValue().equals(nameVariants.get(nameVariant.getKey())))
				.map(nameVariant -> new EntityNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
			)
			.forEach(conflict -> {
				throw new EntityTypeAlreadyPresentInCatalogSchemaException(
					catalogName, conflict.conflictingSchema(), entityType,
					conflict.convention(), conflict.conflictingName()
				);
			});
	}

	@Nonnull
	@Override
	public CatalogIndex readCatalogIndex(@Nonnull Catalog catalog) {
		final long catalogVersion = catalog.getVersion();
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
		final CatalogIndexStoragePart catalogIndexStoragePart = storagePartPersistenceService.getStoragePart(catalogVersion, 1, CatalogIndexStoragePart.class);
		if (catalogIndexStoragePart == null) {
			return new CatalogIndex();
		} else {
			final Set<AttributeKey> sharedAttributeUniqueIndexes = catalogIndexStoragePart.getSharedAttributeUniqueIndexes();
			final Map<AttributeKey, GlobalUniqueIndex> sharedUniqueIndexes = CollectionUtils.createHashMap(sharedAttributeUniqueIndexes.size());
			for (AttributeKey attributeKey : sharedAttributeUniqueIndexes) {
				final long partId = GlobalUniqueIndexStoragePart.computeUniquePartId(attributeKey, storagePartPersistenceService.getReadOnlyKeyCompressor());
				final GlobalUniqueIndexStoragePart sharedUniqueIndexStoragePart = storagePartPersistenceService.getStoragePart(catalogVersion, partId, GlobalUniqueIndexStoragePart.class);
				Assert.isPremiseValid(
					sharedUniqueIndexStoragePart != null,
					"Shared unique index not found for attribute `" + attributeKey + "`!"
				);
				final GlobalAttributeSchemaContract attributeSchema = catalog.getSchema().getAttribute(attributeKey.attributeName())
					.orElseThrow(() -> new EvitaInvalidUsageException("Catalog index references attribute `" + attributeKey.attributeName() + "` but such attribute is not found in catalog schema!"));
				sharedUniqueIndexes.put(
					attributeKey,
					new GlobalUniqueIndex(
						attributeKey, attributeSchema.getPlainType(),
						sharedUniqueIndexStoragePart.getUniqueValueToRecordId(),
						sharedUniqueIndexStoragePart.getLocaleIndex()
					)
				);
			}
			return new CatalogIndex(catalogIndexStoragePart.getVersion(), sharedUniqueIndexes);
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
		@Nonnull DataStoreMemoryBuffer<CatalogIndexKey, CatalogIndex> dataStoreMemoryBuffer
	) {
		// first we need to execute transition to alive state
		if (catalogState == CatalogState.ALIVE && catalogVersion == 0L) {
			this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex(), dataStoreMemoryBuffer);
			catalogVersion = 1L;
		}
		// first store all entity collection headers if they differ
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
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
			catalogStoragePath,
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
				getFirstCatalogBootstrap(this.catalogName, this.storageOptions)
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
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
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
			eType -> new DefaultEntityCollectionPersistenceService(
				this.bootstrapUsed.catalogVersion(),
				this.catalogName,
				this.catalogStoragePath,
				entityCollectionHeader,
				this.storageOptions,
				this.transactionOptions,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				this.recordTypeRegistry
			)
		);
	}

	@Nonnull
	@Override
	public Optional<EntityCollectionPersistenceService> flush(
		long catalogVersion,
		@Nonnull HeaderInfoSupplier headerInfoSupplier,
		@Nonnull EntityCollectionHeader entityCollectionHeader,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex> dataStoreBuffer) {
		final CollectionFileReference collectionFileReference =
			new CollectionFileReference(
				entityCollectionHeader.entityType(),
				entityCollectionHeader.entityTypePrimaryKey(),
				entityCollectionHeader.entityTypeFileIndex(),
				entityCollectionHeader.fileLocation()
			);
		final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = this.entityCollectionPersistenceServices.get(collectionFileReference);
		if (entityCollectionPersistenceService == null) {
			return empty();
		} else {
			final OffsetIndexDescriptor newDescriptor = entityCollectionPersistenceService.flush(catalogVersion, headerInfoSupplier);
			if (newDescriptor.getActiveRecordShare() < this.storageOptions.minimalActiveRecordShare() &&
				newDescriptor.getFileSize() > this.storageOptions.fileSizeCompactionThresholdBytes()) {
				final EntityCollectionHeader compactedHeader = entityCollectionPersistenceService.compact(catalogName, catalogVersion, headerInfoSupplier);
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
				if (dataStoreBuffer instanceof WarmUpDataStoreMemoryBuffer<EntityIndexKey, EntityIndex> warmUpDataStoreMemoryBuffer) {
					warmUpDataStoreMemoryBuffer.setPersistenceService(newPersistenceService.getStoragePartPersistenceService());
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
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
		return storagePartPersistenceService.getStoragePart(
			catalogVersion,
			entityTypePrimaryKey,
			EntityCollectionHeader.class
		);
	}

	@Nonnull
	@Override
	public IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId) {
		return new DefaultIsolatedWalService(
			transactionId,
			this.catalogKryoPool.obtain(),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.transactionOptions.transactionWorkDirectory()
					.resolve(transactionId.toString())
					.resolve(transactionId + ".wal"),
				this.observableOutputKeeper,
				this.offHeapMemoryManager
			)
		);
	}

	@Override
	public void delete() {
		FileUtils.deleteDirectory(catalogStoragePath);
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
					this.bootstrapUsed.catalogVersion(), this.catalogName, this.catalogStoragePath, catalogHeader, this.catalogKryoPool,
					this.storageOptions, this.transactionOptions, this.scheduler,
					this::trimBootstrapFile,
					this.obsoleteFileMaintainer::firstAvailableCatalogVersionChanged
				);
			}
			Assert.isPremiseValid(
				walReference.getBuffer().isPresent() || walReference.getFilePath().isPresent(),
				"Unexpected WAL reference - neither off-heap buffer nor file reference present!"
			);

			return this.catalogWal.append(transactionMutation, walReference);
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
			return this.catalogWal.getFirstNonProcessedTransaction(
				getCatalogHeader(catalogVersion).walFileReference()
			);
		}
	}

	@Nonnull
	@Override
	public CatalogPersistenceService replaceWith(
		long catalogVersion,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull DataStoreMemoryBuffer<CatalogIndexKey, CatalogIndex> dataStoreMemoryBuffer
	) {
		final Path newPath = pathForCatalog(catalogNameToBeReplaced, storageOptions.storageDirectoryOrDefault());
		final boolean targetPathExists = newPath.toFile().exists();
		if (targetPathExists) {
			Assert.isPremiseValid(newPath.toFile().isDirectory(), () -> "Path `" + newPath.toAbsolutePath() + "` is not a directory!");
		}

		// store the catalog that replaces the original header
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
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
		ofNullable(
			this.catalogStoragePath
				.toFile()
				.listFiles((dir, name) -> name.startsWith(this.catalogName))
		)
			.stream()
			.flatMap(Arrays::stream)
			.forEach(it -> {
				final Path filePath = it.toPath();
				final String fileNameToRename;
				if (it.getName().equals(getCatalogBootstrapFileName(this.catalogName))) {
					fileNameToRename = getCatalogBootstrapFileName(catalogNameToBeReplaced);
				} else if (it.getName().equals(getCatalogDataStoreFileName(this.catalogName, catalogIndex))) {
					fileNameToRename = getCatalogDataStoreFileName(catalogNameToBeReplaced, catalogIndex);
				} else {
					return;
				}
				final Path filePathForRename = filePath.getParent().resolve(fileNameToRename);
				Assert.isPremiseValid(
					it.renameTo(filePathForRename.toFile()),
					() -> new GenericEvitaInternalError(
						"Failed to rename `" + it.getAbsolutePath() + "` to `" + filePathForRename.toAbsolutePath() + "`!",
						"Failed to rename one of the `" + this.catalogName + "` catalog files to target catalog name!"
					)
				);
			});

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
				new WriteOnlyFileHandle(
					catalogNameToBeReplaced,
					FileType.CATALOG,
					catalogNameToBeReplaced,
					newPath.resolve(getCatalogBootstrapFileName(catalogNameToBeReplaced)),
					this.observableOutputKeeper
				),
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
		final CollectionFileReference replacedEntityTypeFileReference = catalogHeader.getEntityTypeFileIndexIfExists(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final CollectionFileReference newEntityTypeExistingFileReference = catalogHeader.getEntityTypeFileIndexIfExists(newEntityType)
			.orElseGet(() -> new CollectionFileReference(newEntityType, entityTypePrimaryKey, replacedEntityTypeFileReference.fileIndex() + 1, null));
		final CollectionFileReference newEntityTypeFileIndex = newEntityTypeExistingFileReference.incrementAndGet();
		final Path newFilePath = newEntityTypeFileIndex.toFilePath(this.catalogStoragePath);

		final DefaultEntityCollectionPersistenceService entityPersistenceService = this.entityCollectionPersistenceServices.get(
			new CollectionFileReference(entityType, entityTypePrimaryKey, replacedEntityTypeFileReference.fileIndex(), null)
		);
		Assert.isPremiseValid(
			entityPersistenceService != null,
			"Entity collection persistence service for `" + entityType + "` not found in catalog `" + catalogName + "`!"
		);

		final File newFile = newFilePath.toFile();
		final EntityCollectionHeader newEntityCollectionHeader;
		try {
			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(newFile.createNewFile(), "Cannot create new entity collection file: `" + newFilePath + "`!");
			try (final FileOutputStream fos = new FileOutputStream(newFile)) {
				newEntityCollectionHeader = entityPersistenceService.copySnapshotTo(catalogVersion, newEntityTypeFileIndex, fos);
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
					"Entity collection persistence service for `" + newEntityType + "` already exists in catalog `" + catalogName + "`!"
				);
				final EntityCollectionHeader entityHeader = entityPersistenceService.getEntityCollectionHeader();
				return new DefaultEntityCollectionPersistenceService(
					this.bootstrapUsed.catalogVersion(),
					this.catalogName,
					this.catalogStoragePath,
					new EntityCollectionHeader(
						newEntityTypeExistingFileReference.entityType(),
						newEntityTypeFileIndex.entityTypePrimaryKey(),
						newEntityTypeFileIndex.fileIndex(),
						entityHeader.recordCount(),
						entityHeader.lastPrimaryKey(),
						entityHeader.lastEntityIndexPrimaryKey(),
						entityHeader.activeRecordShare(),
						newEntityCollectionHeader,
						entityHeader.globalEntityIndexId(),
						entityHeader.usedEntityIndexIds()
					),
					this.storageOptions,
					this.transactionOptions,
					this.offHeapMemoryManager,
					this.observableOutputKeeper,
					this.recordTypeRegistry
				);
			}
		);
		this.obsoleteFileMaintainer.removeFileWhenNotUsed(
			catalogVersion,
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
			collectionFileReference.toFilePath(catalogStoragePath),
			() -> removeEntityCollectionPersistenceServiceAndClose(collectionFileReference)
		);
	}

	@Nonnull
	@Override
	public Stream<Mutation> getCommittedMutationStream(long catalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedMutationStream(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public Stream<Mutation> getReversedCommittedMutationStream(long catalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedReversedMutationStream(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public Stream<Mutation> getCommittedLiveMutationStream(long catalogVersion) {
		if (this.catalogWal == null) {
			return Stream.empty();
		} else {
			return this.catalogWal.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(catalogVersion);
		}
	}

	@Override
	public long getLastCatalogVersionInMutationStream() {
		if (this.catalogWal == null) {
			return 0L;
		} else {
			return this.catalogWal.getLastWrittenCatalogVersion();
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
	public PaginatedList<CatalogVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final int recordCount = CatalogBootstrap.getRecordCount(length);
			final int pageNumber = PaginatedList.isRequestedResultBehindLimit(page, pageSize, recordCount) ? 1 : page;
			try (
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
			) {
				final List<CatalogVersion> catalogVersions = new ArrayList<>(pageSize);
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
	public Stream<CatalogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		if (catalogVersion.length == 0) {
			return Stream.empty();
		}
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final Map<Long, CatalogVersion> catalogVersionPreviousVersions = createPreviousCatalogVersionsIndex(
				catalogVersion, bootstrapFile, bootstrapFilePath
			);
			return this.catalogWal == null ?
				Stream.empty() :
				Arrays.stream(catalogVersion)
					.mapToObj(
						cv -> ofNullable(catalogVersionPreviousVersions.get(cv))
							.map(it -> this.catalogWal.getCatalogVersionDescriptor(cv, it.version(), it.timestamp()))
							.orElse(null))
					.filter(Objects::nonNull);
		} else {
			return Stream.empty();
		}
	}

	@Override
	public void purgeAllObsoleteFiles() {
		final CatalogHeader catalogHeader = getCatalogHeader(this.bootstrapUsed.catalogVersion());
		final Pattern catalogDataFilePattern = CatalogPersistenceService.getCatalogDataStoreFileNamePattern(catalogName);
		final File[] filesToDelete = this.catalogStoragePath.toFile()
			.listFiles((dir, name) -> {
				// bootstrap file is never removed
				if (name.equals(getCatalogBootstrapFileName(catalogName))) {
					return false;
				}
				// WAL is never removed
				if (name.endsWith(WAL_FILE_SUFFIX)) {
					return false;
				}
				// actual catalog data file is not removed
				final Matcher catalogFileMatcher = catalogDataFilePattern.matcher(name);
				if (catalogFileMatcher.matches() && Integer.parseInt(catalogFileMatcher.group(1)) == this.bootstrapUsed.catalogFileIndex()) {
					return false;
				}
				// collection data files are not removed if they are referenced in the current catalog header
				if (name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX)) {
					for (CollectionFileReference fileReference : catalogHeader.collectionFileIndex().values()) {
						if (fileReference.toFilePath(this.catalogStoragePath).toFile().getName().equals(name)) {
							return false;
						}
					}
				}
				// all other files are removed
				return true;
			});
		// delete and inform
		if (filesToDelete.length > 0) {
			log.info(
				"Purging obsolete files for catalog `{}`: {}",
				catalogName,
				Arrays.stream(filesToDelete).map(File::getName).collect(Collectors.joining(", "))
			);
			for (File file : filesToDelete) {
				Assert.isPremiseValid(file.delete(), "Failed to delete file `" + file.getAbsolutePath() + "`!");
			}
		}
	}

	@Nonnull
	@Override
	public Path backup(@Nonnull UUID id, @Nullable OffsetDateTime pastMoment, boolean includingWAL, @Nonnull IntConsumer progressUpdater) throws TemporalDataNotAvailableException {
		final long catalogVersion = pastMoment == null ?
			this.bootstrapUsed.catalogVersion() :
			getCatalogBootstrapForSpecificMoment(this.catalogName, this.storageOptions, pastMoment).catalogVersion();

		final Path backupFolder = this.transactionOptions.transactionWorkDirectory().resolve("backup");
		if (!backupFolder.toFile().exists()) {
			Assert.isPremiseValid(backupFolder.toFile().mkdirs(), "Failed to create backup folder `" + backupFolder + "`!");
		}
		final Path backupFile = backupFolder.resolve(id + ".zip");
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(backupFile.toFile())))) {
			zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/"));
			zipOutputStream.closeEntry();

			// first store all the active contents of the entity collection data files
			final CatalogHeader catalogHeader = getCatalogHeader(catalogVersion);
			final Map<String, EntityCollectionHeader> entityHeaders = CollectionUtils.createHashMap(
				catalogHeader.getEntityTypeFileIndexes().size()
			);
			for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
				final String entityDataFileName = CatalogPersistenceService.getEntityCollectionDataStoreFileName(
					entityTypeFileIndex.entityType(),
					entityTypeFileIndex.entityTypePrimaryKey(),
					0
				);
				zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + entityDataFileName));
				final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = getOrCreateEntityCollectionPersistenceService(
					catalogVersion, entityTypeFileIndex.entityType(), entityTypeFileIndex.entityTypePrimaryKey()
				);
				final EntityCollectionHeader newEntityCollectionHeader = entityCollectionPersistenceService.copySnapshotTo(
					catalogVersion,
					new CollectionFileReference(
						entityTypeFileIndex.entityType(),
						entityTypeFileIndex.entityTypePrimaryKey(),
						0,
						entityTypeFileIndex.fileLocation()
					),
					zipOutputStream
				);
				entityHeaders.put(
					entityTypeFileIndex.entityType(),
					newEntityCollectionHeader
				);
				zipOutputStream.closeEntry();
			}

			// then write the active contents of the catalog file
			final String catalogDataStoreFileName = CatalogPersistenceService.getCatalogDataStoreFileName(this.catalogName, 0);
			zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + catalogDataStoreFileName));

			final OffsetIndexDescriptor catalogDataFileDescriptor = getStoragePartPersistenceService(catalogVersion)
				.copySnapshotTo(
					catalogVersion, zipOutputStream,
					Stream.concat(
							Stream.of(
								new CatalogHeader(
									STORAGE_PROTOCOL_VERSION,
									catalogHeader.version() + 1,
									catalogHeader.walFileReference(),
									entityHeaders.values().stream()
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
									catalogHeader.compressedKeys(),
									catalogHeader.catalogId(),
									catalogHeader.catalogName(),
									catalogHeader.catalogState(),
									catalogHeader.lastEntityCollectionPrimaryKey(),
									1.0 // all entries are active
								)
							),
							entityHeaders.values().stream()
						)
						.map(StoragePart.class::cast)
						.toArray(StoragePart[]::new)
				);
			zipOutputStream.closeEntry();

			// store the WAL file with all records written after the catalog version
			if (includingWAL) {
				try (final Stream<Path> walFileStream = Files.list(catalogStoragePath)) {
					walFileStream
						.filter(it -> it.getFileName().toString().endsWith(WAL_FILE_SUFFIX))
						.forEach(walFile -> {
							try {
								zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + walFile.getFileName()));
								Files.copy(walFile, zipOutputStream);
								zipOutputStream.closeEntry();
							} catch (IOException e) {
								throw new UnexpectedIOException(
									"Failed to backup WAL file `" + walFile + "`!",
									"Failed to backup WAL file!",
									e
								);
							}
						});
				}
			}

			// finally, store the catalog bootstrap
			final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
			zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + bootstrapFileName));
			// store used bootstrap
			final ObservableOutput<ZipOutputStream> boostrapOutput = new ObservableOutput<>(
				zipOutputStream,
				CatalogBootstrap.BOOTSTRAP_RECORD_SIZE,
				CatalogBootstrap.BOOTSTRAP_RECORD_SIZE << 1,
				0L
			);
			final StorageRecord<CatalogBootstrap> catalogBootstrapStorageRecord = serializeBootstrapRecord(
				boostrapOutput,
				new CatalogBootstrap(
					catalogVersion,
					0,
					OffsetDateTime.now(),
					catalogDataFileDescriptor.fileLocation()
				)
			);
			boostrapOutput.flush();
			Assert.isPremiseValid(
				catalogBootstrapStorageRecord.fileLocation().recordLength() == CatalogBootstrap.BOOTSTRAP_RECORD_SIZE,
				"Unexpected bootstrap record size: " + catalogBootstrapStorageRecord.fileLocation().recordLength()
			);
			zipOutputStream.closeEntry();
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to backup catalog `" + this.catalogName + "`!",
				"Failed to backup catalog!",
				e
			);
		}

		return backupFile;
	}

	@Override
	public void close() {
		try {
			// close WAL
			if (this.catalogWal != null) {
				this.catalogWal.close();
			}
			// close off heap manager
			this.offHeapMemoryManager.close();
			// purge obsolete files
			this.obsoleteFileMaintainer.close();
			// close all services
			this.entityCollectionPersistenceServices.values()
				.forEach(DefaultEntityCollectionPersistenceService::close);
			this.entityCollectionPersistenceServices.clear();
			// close current file offset index
			this.catalogStoragePartPersistenceService.values()
				.forEach(OffsetIndexStoragePartPersistenceService::close);
			this.catalogStoragePartPersistenceService.clear();
			// close observable output keeper
			this.observableOutputKeeper.close();
		} catch (IOException e) {
			// ignore / log - we tried to close everything
			log.error("Failed to close catalog persistence service `" + this.catalogName + "`!", e);
		}
	}

	@Override
	public void catalogVersionBeyondTheHorizon(@Nullable Long minimalActiveCatalogVersion) {
		this.catalogStoragePartPersistenceService.values().forEach(it -> it.purgeHistoryEqualAndLaterThan(minimalActiveCatalogVersion));
		this.obsoleteFileMaintainer.catalogVersionBeyondTheHorizon(minimalActiveCatalogVersion);
		this.entityCollectionPersistenceServices.values()
			.forEach(it -> it.catalogVersionBeyondTheHorizon(minimalActiveCatalogVersion));
	}

	@Override
	public boolean isNew() {
		// if the service is new (not yet stored) there should be only one value in the map
		return this.catalogStoragePartPersistenceService.values()
			.stream()
			.anyMatch(OffsetIndexStoragePartPersistenceService::isNew);
	}

	@Override
	public void flushTrappedUpdates(long catalogVersion, @Nonnull DataStoreIndexChanges<CatalogIndexKey, CatalogIndex> dataStoreIndexChanges) {
		// now store all the entity trapped updates
		dataStoreIndexChanges.popTrappedUpdates()
			.forEach(it -> getStoragePartPersistenceService(catalogVersion).putStoragePart(catalogVersion, it));
	}

	@Override
	public boolean isClosed() {
		return this.catalogStoragePartPersistenceService.isEmpty();
	}

	/**
	 * Records a bootstrap in the catalog.
	 *
	 * @param catalogVersion   the version of the catalog
	 * @param newCatalogName   the name of the new catalog
	 * @param catalogFileIndex the index of the catalog file
	 * @param dataStoreMemoryBuffer the data store memory buffer
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		int catalogFileIndex,
		@Nullable DataStoreMemoryBuffer<CatalogIndexKey, CatalogIndex> dataStoreMemoryBuffer
	) {
		return recordBootstrap(catalogVersion, newCatalogName, catalogFileIndex, getNowEpochMillis(), dataStoreMemoryBuffer);
	}

	/**
	 * Records a bootstrap in the catalog.
	 *
	 * @param catalogVersion   the version of the catalog
	 * @param newCatalogName   the name of the new catalog
	 * @param catalogFileIndex the index of the catalog file
	 * @param timestamp        the timestamp of the boot record
	 * @param dataStoreMemoryBuffer the data store memory buffer
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(
		long catalogVersion,
		@Nonnull String newCatalogName,
		int catalogFileIndex,
		long timestamp,
		@Nullable DataStoreMemoryBuffer<CatalogIndexKey, CatalogIndex> dataStoreMemoryBuffer
	) {
		final OffsetDateTime bootstrapWriteTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toOffsetDateTime();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
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
			try (final FileOutputStream fos = new FileOutputStream(this.catalogStoragePath.resolve(compactedFileName).toFile())) {
				compactedDescriptor = storagePartPersistenceService.copySnapshotTo(catalogVersion, fos);
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
					nonFlushedBlock -> this.reportNonFlushedContents(catalogName, nonFlushedBlock),
					oldestRecordTimestamp -> DefaultCatalogPersistenceService.reportOldestHistoricalRecord(catalogName, oldestRecordTimestamp.orElse(null))
				);
				this.catalogStoragePartPersistenceService.put(
					catalogVersion,
					newPersistenceService
				);
				this.catalogPersistenceServiceVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, this.catalogPersistenceServiceVersions);

				this.obsoleteFileMaintainer.removeFileWhenNotUsed(
					catalogVersion,
					this.catalogStoragePath.resolve(getCatalogDataStoreFileName(newCatalogName, catalogFileIndex)),
					() -> removeCatalogPersistenceServiceForVersion(currentVersion)
				);

				if (dataStoreMemoryBuffer instanceof WarmUpDataStoreMemoryBuffer<CatalogIndexKey, CatalogIndex> warmUpDataStoreMemoryBuffer) {
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
		final Kryo kryo = this.catalogKryoPool.obtain();
		try {
			this.bootstrapWriteLock.lockInterruptibly();
			final WriteOnlyFileHandle originalBootstrapHandle = this.bootstrapWriteHandle.get();
			final WriteOnlyFileHandle bootstrapHandle = getOrCreateNewBootstrapTempWriteHandle(
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
				FileUtils.rewriteTargetFileAtomically(bootstrapHandle.getTargetFile(), originalBootstrapHandle.getTargetFile());
				// we should be the only writer here, so this should always pass
				Assert.isPremiseValid(
					this.bootstrapWriteHandle.compareAndSet(
						originalBootstrapHandle,
						new WriteOnlyFileHandle(
							originalBootstrapHandle.getTargetFile(),
							this.observableOutputKeeper
						)
					),
					() -> new GenericEvitaInternalError("Failed to replace the bootstrap write handle in a critical section!")
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
			this.catalogKryoPool.free(kryo);
			log.debug("Catalog `{}` stored to `{}`.", newCatalogName, catalogStoragePath);
		}
	}

	/**
	 * Trims the bootstrap file so that it contains only records starting with the bootstrap record precedes the given
	 * timestamp and all the records following it.
	 *
	 * @param toTimestamp the timestamp to trim the bootstrap from (including a single record before the timestamp)
	 */
	void trimBootstrapFile(@Nonnull OffsetDateTime toTimestamp) {
		// create tracking event
		final DataFileCompactEvent event = new DataFileCompactEvent(
			this.catalogName,
			FileType.BOOTSTRAP,
			this.catalogName
		);

		try {
			this.bootstrapWriteLock.lockInterruptibly();
			final WriteOnlyFileHandle originalBootstrapHandle = this.bootstrapWriteHandle.get();
			final WriteOnlyFileHandle newBootstrapHandle = createNewBootstrapTempWriteHandle(this.catalogName);

			// copy all bootstrap records since the timestamp to the new file
			copyAllNecessaryBootstrapRecords(toTimestamp, originalBootstrapHandle.getTargetFile(), newBootstrapHandle);

			// now close both handles
			originalBootstrapHandle.close();
			newBootstrapHandle.close();
			// try to atomically rewrite original bootstrap file
			FileUtils.rewriteTargetFileAtomically(newBootstrapHandle.getTargetFile(), originalBootstrapHandle.getTargetFile());
			// we should be the only writer here, so this should always pass
			Assert.isPremiseValid(
				this.bootstrapWriteHandle.compareAndSet(
					originalBootstrapHandle,
					new WriteOnlyFileHandle(
						originalBootstrapHandle.getTargetFile(),
						this.observableOutputKeeper
					)
				),
				() -> new GenericEvitaInternalError("Failed to replace the bootstrap write handle in a critical section!")
			);
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
	 * Removes the persistence service from internal index and closes its resources.
	 *
	 * @param collectionFileReference the reference to the collection persistence service
	 */
	private void removeEntityCollectionPersistenceServiceAndClose(@Nonnull CollectionFileReference collectionFileReference) {
		final DefaultEntityCollectionPersistenceService persistenceService = this.entityCollectionPersistenceServices.remove(collectionFileReference);
		persistenceService.close();
	}

	/**
	 * Copy all necessary bootstrap records to the target bootstrap file from the input file starting from the given
	 * timestamp.
	 *
	 * @param toTimestamp           The timestamp to start copying bootstrap records from.
	 * @param fromFile              The input file containing the bootstrap records.
	 * @param targetBootstrapHandle The handle of the target bootstrap file to copy the records to.
	 */
	private void copyAllNecessaryBootstrapRecords(
		@Nonnull OffsetDateTime toTimestamp,
		@Nonnull Path fromFile,
		@Nonnull WriteOnlyFileHandle targetBootstrapHandle
	) {
		final int recordCount = CatalogBootstrap.getRecordCount(
			fromFile.toFile().length()
		);
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
				fromFile,
				storageOptions.computeCRC32C()
			);
		) {
			boolean inValidRange = false;
			CatalogBootstrap previousBootstrapRecord = null;
			CatalogBootstrap bootstrapRecord;
			for (int i = 0; i < recordCount; i++) {
				final long startPosition = CatalogBootstrap.getPositionForRecord(i);
				bootstrapRecord = readHandle.execute(
					input -> StorageRecord.read(
						input,
						new FileLocation(startPosition, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
						(theInput, recordLength) -> new CatalogBootstrap(
							theInput.readLong(),
							theInput.readInt(),
							Instant.ofEpochMilli(theInput.readLong()).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
							new FileLocation(
								theInput.readLong(),
								theInput.readInt()
							)
						)
					)
				).payload();

				if (inValidRange) {
					// append to the new file
					copyBootstrapRecord(targetBootstrapHandle, bootstrapRecord);
				} else if (toTimestamp.isBefore(bootstrapRecord.timestamp())) {
					// write the record preceding the valid range and mark the range as valid
					if (previousBootstrapRecord != null) {
						copyBootstrapRecord(targetBootstrapHandle, previousBootstrapRecord);
					}
					copyBootstrapRecord(targetBootstrapHandle, bootstrapRecord);
					inValidRange = true;
				} else {
					previousBootstrapRecord = bootstrapRecord;
				}
			}
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
	private WriteOnlyFileHandle getOrCreateNewBootstrapTempWriteHandle(
		long catalogVersion,
		@Nonnull String newCatalogName,
		@Nonnull WriteOnlyFileHandle originalBootstrapHandle
	) {
		if (catalogVersion == 0) {
			return createNewBootstrapTempWriteHandle(newCatalogName);
		} else {
			return originalBootstrapHandle;
		}
	}

	/**
	 * Creates a new temporary file handle for writing bootstrap data.
	 *
	 * @param newCatalogName the name of the new catalog
	 * @return a WriteOnlyFileHandle object representing the new temporary file
	 * @throws UnexpectedIOException if an error occurs while creating the temporary file
	 */
	@Nonnull
	private WriteOnlyFileHandle createNewBootstrapTempWriteHandle(@Nonnull String newCatalogName) {
		try {
			// create new file and replace the former one with it
			return new WriteOnlyFileHandle(
				Files.createTempFile(CatalogPersistenceService.getCatalogBootstrapFileName(newCatalogName), ".tmp"),
				this.observableOutputKeeper
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
	 * Mehod is not thread safe and caller must ensure it's called only from single thread.
	 *
	 * @param catalogVersion the catalog version to remove the persistence service for
	 */
	private void removeCatalogPersistenceServiceForVersion(long catalogVersion) {
		try {
			this.cpsvLock.lockInterruptibly();
			final int index = Arrays.binarySearch(this.catalogPersistenceServiceVersions, catalogVersion);
			final int lookupIndex = index >= 0 ? index : (-index - 2);
			Assert.isPremiseValid(
				lookupIndex >= 0 && lookupIndex < this.catalogPersistenceServiceVersions.length,
				() -> new GenericEvitaInternalError("Catalog version " + catalogVersion + " not found in the catalog persistence service versions!")
			);
			final long versionToRemove = this.catalogPersistenceServiceVersions[lookupIndex];
			this.catalogPersistenceServiceVersions = ArrayUtils.removeLongFromArrayOnIndex(this.catalogPersistenceServiceVersions, lookupIndex);
			// remove the service and release its resources
			final CatalogOffsetIndexStoragePartPersistenceService storageService = this.catalogStoragePartPersistenceService.remove(versionToRemove);
			storageService.close();
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
	 * Creates an index of the previous catalog versions based on the specified catalog version array and bootstrap file
	 * path. It uses binary search to quickly locate min / max version indexes and then indexes all versions between
	 * them and for each version stores the previous version.
	 *
	 * @param catalogVersion an array of catalog versions
	 * @return a {@link CatalogVersion} object that contains PREVIOUS VERSION and CURRENT VERSION TIMESTAMP
	 * (this is a little bit hacky, but we avoid declaring new record type)
	 */
	@Nonnull
	private Map<Long, CatalogVersion> createPreviousCatalogVersionsIndex(
		@Nonnull long[] catalogVersion,
		@Nonnull File bootstrapFile,
		@Nonnull Path bootstrapFilePath
	) {
		final long length = bootstrapFile.length();
		final int recordCount = CatalogBootstrap.getRecordCount(length);
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
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
			final Map<Long, CatalogVersion> catalogVersionPreviousVersions = CollectionUtils.createHashMap(
				normalizedMaxCvIndex - normalizedMinCvIndex + 1
			);
			CatalogVersion previousVersion = minCvIndex == -1 ? new CatalogVersion(-1L, OffsetDateTime.MIN) : null;
			for (int i = normalizedMinCvIndex; i <= normalizedMaxCvIndex; i++) {
				final CatalogVersion cv = readCatalogVersion(readHandle, CatalogBootstrap.getPositionForRecord(i));
				if (previousVersion != null) {
					catalogVersionPreviousVersions.put(
						cv.version(),
						new CatalogVersion(previousVersion.version(), cv.timestamp())
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
		final File[] entityCollectionFiles = this.catalogStoragePath.toFile().listFiles(
			(dir, name) -> name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX)
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
		return getCatalogBootstrapRecordStream(this.catalogName, this.storageOptions)
			.filter(it -> it.catalogVersion() == catalogVersion)
			.map(it -> {
				final String catalogFileName = getCatalogDataStoreFileName(catalogName, it.catalogFileIndex());
				final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
				return new DataFilesBulkInfo(
					it,
					readCatalogHeader(catalogFilePath, it, this.recordTypeRegistry)
				);
			})
			.findFirst()
			.orElse(null);
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
	 * Supplier that reads catalog bootstrap records from the catalog bootstrap file.
	 */
	private static class CatalogBootstrapSupplier implements Supplier<CatalogBootstrap>, Closeable {
		private final ReadOnlyFileHandle readHandle;
		private int position;

		public CatalogBootstrapSupplier(
			@Nonnull Path bootstrapFilePath,
			@Nonnull StorageOptions storageOptions
		) {
			this.readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
		}

		@Override
		public CatalogBootstrap get() {
			if (this.position + CatalogBootstrap.BOOTSTRAP_RECORD_SIZE > this.readHandle.getLastWrittenPosition()) {
				return null;
			}
			final CatalogBootstrap catalogBootstrap = readCatalogBootstrap(this.position, this.readHandle);
			this.position += CatalogBootstrap.BOOTSTRAP_RECORD_SIZE;
			return catalogBootstrap;
		}

		@Override
		public void close() {
			this.readHandle.close();
		}
	}
}
