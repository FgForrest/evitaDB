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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogVersionBeyondTheHorizonListener;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.scheduling.Scheduler;
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
import io.evitadb.store.model.PersistentStorageDescriptor;
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
import io.evitadb.store.spi.StoragePartPersistenceService;
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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	private static final Function<VersionedKryoKeyInputs, VersionedKryo> VERSIONED_KRYO_FACTORY = kryoKeyInputs -> VersionedKryoFactory.createKryo(
		kryoKeyInputs.version(),
		SchemaKryoConfigurer.INSTANCE
			.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
			.andThen(SharedClassesConfigurer.INSTANCE)
			.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
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
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
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
	 * Check whether target directory exists and whether it is really directory.
	 *
	 * @return name of the directory (e.g. catalog name)
	 */
	@Nonnull
	private static String verifyDirectory(@Nonnull Path storageDirectory, boolean requireEmpty) {
		final File storageDirectoryFile = storageDirectory.toFile();
		if (!storageDirectoryFile.exists()) {
			//noinspection ResultOfMethodCallIgnored
			storageDirectoryFile.mkdirs();
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
	private static Path pathForNewCatalog(@Nonnull String catalogName, @Nonnull Path storageDirectory) {
		try {
			return storageDirectory.resolve(catalogName);
		} catch (InvalidPathException ex) {
			throw new InvalidFileNameException("Name `" + catalogName + "` cannot be converted a valid file name: " + ex.getMessage() + "! Please rename the catalog.");
		}
	}

	/**
	 * Retrieves the last catalog bootstrap for a given catalog. If the last bootstrap record was not fully written,
	 * the previous one is returned instead. The correctness is verified by fixed length of the bootstrap record and
	 * CRC32C checksum of the record.
	 *
	 * @param catalogStoragePath The path to the catalog storage directory.
	 * @param catalogName        The name of the catalog.
	 * @param storageOptions     The storage options for reading the bootstrap file.
	 * @return The last catalog bootstrap.
	 * @throws UnexpectedIOException If there is an error opening the catalog bootstrap file.
	 * @throws BootstrapFileNotFound If the catalog bootstrap file is not found.
	 */
	@Nonnull
	private static CatalogBootstrap getLastCatalogBootstrap(
		@Nonnull Path catalogStoragePath,
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final long lastMeaningfulPosition = CatalogBootstrap.getLastMeaningfulPosition(length);
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
			try {
				return readHandle.execute(
					input -> StorageRecord.read(
						input,
						new FileLocation(lastMeaningfulPosition, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
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
			} catch (Exception e) {
				throw new UnexpectedIOException(
					"Failed to open catalog bootstrap file `" + bootstrapFile.getAbsolutePath() + "`!",
					"Failed to open catalog bootstrap file!",
					e
				);
			} finally {
				readHandle.forceClose();
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
		@Nonnull StoragePartPersistenceService storagePartPersistenceService
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogSchemaStoragePart catalogSchemaStoragePart = CatalogSchemaStoragePart.deserializeWithCatalog(
			catalogInstance,
			() -> storagePartPersistenceService.getStoragePart(catalogVersion, 1, CatalogSchemaStoragePart.class)
		);
		Assert.isTrue(
			catalogSchemaStoragePart.catalogSchema().getName().equals(catalogName),
			() -> new UnexpectedCatalogContentsException(
				"Directory " + catalogStoragePath + " contains data of " + catalogSchemaStoragePart.catalogSchema().getName() +
					" catalog. Cannot load catalog " + catalogName + " from this directory!"
			)
		);
	}

	/**
	 * Retrieves the instance of shared WAL service for a given catalog if the catalog is in transactional mode.
	 *
	 * BEWARE: work with {@link CatalogWriteAheadLog} is not thread safe and must be synchronized!
	 *
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
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Pool<Kryo> catalogKryoPool,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Consumer<OffsetDateTime> bootstrapFileTrimFunction
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
					catalogName, catalogStoragePath, catalogKryoPool,
					storageOptions, transactionOptions, scheduler,
					bootstrapFileTrimFunction
				)
			)
			.orElse(null);
	}

	/**
	 * Creates a CatalogWriteAheadLog if there are any WAL files present in the catalog file path.
	 *
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
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Consumer<OffsetDateTime> bootstrapFileTrimFunction,
		@Nonnull Path catalogFilePath,
		@Nonnull Pool<Kryo> kryoPool
	) {
		final File[] walFiles = catalogFilePath
			.toFile()
			.listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX));
		return walFiles == null || walFiles.length == 0 ?
			null :
			new CatalogWriteAheadLog(
				catalogName, catalogFilePath, kryoPool,
				storageOptions, transactionOptions, scheduler,
				bootstrapFileTrimFunction
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

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(scheduler);
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = pathForNewCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
		verifyDirectory(this.catalogStoragePath, true);
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		final CatalogBootstrap lastCatalogBootstrap = getLastCatalogBootstrap(
			this.catalogStoragePath, verifiedCatalogName, storageOptions
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			new WriteOnlyFileHandle(
				this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
				this.observableOutputKeeper
			)
		);
		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogName, storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile,
			this.catalogStoragePath, this.catalogKryoPool
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, lastCatalogBootstrap.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		final long catalogVersion = 0L;
		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFileName,
				catalogFilePath,
				this.storageOptions,
				this.transactionOptions,
				lastCatalogBootstrap,
				recordTypeRegistry,
				offHeapMemoryManager,
				observableOutputKeeper,
				VERSIONED_KRYO_FACTORY
			)
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};

		if (lastCatalogBootstrap.fileLocation() == null) {
			this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, 0);
		} else {
			this.bootstrapUsed = lastCatalogBootstrap;
		}

		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(16);
	}

	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.obsoleteFileMaintainer = new ObsoleteFileMaintainer(scheduler);
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions, scheduler);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		this.bootstrapUsed = getLastCatalogBootstrap(
			catalogStoragePath, verifiedCatalogName, storageOptions
		);
		this.bootstrapWriteHandle = new AtomicReference<>(
			new WriteOnlyFileHandle(
				this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
				this.observableOutputKeeper
			)
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		this.catalogWal = createWalIfAnyWalFilePresent(
			catalogName, storageOptions, transactionOptions, scheduler,
			this::trimBootstrapFile,
			this.catalogStoragePath, this.catalogKryoPool
		);

		final long catalogVersion = this.bootstrapUsed.catalogVersion();
		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService =
			CatalogOffsetIndexStoragePartPersistenceService.create(
				this.catalogName,
				catalogFileName,
				catalogFilePath,
				this.storageOptions,
				this.transactionOptions,
				this.bootstrapUsed,
				this.recordTypeRegistry,
				this.offHeapMemoryManager,
				this.observableOutputKeeper,
				VERSIONED_KRYO_FACTORY
			);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		verifyCatalogNameMatches(
			catalogInstance, catalogVersion, catalogName, catalogStoragePath, catalogStoragePartPersistenceService
		);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes().size()
		);
	}

	private DefaultCatalogPersistenceService(
		@Nonnull OffsetIndexRecordTypeRegistry fileOffsetIndexRecordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogBootstrap bootstrapUsed,
		@Nonnull WriteOnlyFileHandle bootstrapWriteHandle,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObsoleteFileMaintainer obsoleteFileMaintainer,
		@Nullable CatalogWriteAheadLog catalogWal
	) {
		this.bootstrapUsed = bootstrapUsed;
		this.recordTypeRegistry = fileOffsetIndexRecordTypeRegistry;
		this.observableOutputKeeper = observableOutputKeeper;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.bootstrapWriteHandle = new AtomicReference<>(bootstrapWriteHandle);
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;
		this.obsoleteFileMaintainer = obsoleteFileMaintainer;
		this.catalogWal = catalogWal;

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		final long catalogVersion = this.bootstrapUsed.catalogVersion();

		final CatalogOffsetIndexStoragePartPersistenceService catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogName,
			catalogFileName,
			catalogFilePath,
			storageOptions,
			transactionOptions,
			this.bootstrapUsed,
			this.recordTypeRegistry,
			offHeapMemoryManager, observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
		this.catalogStoragePartPersistenceService = CollectionUtils.createConcurrentHashMap(16);
		this.catalogStoragePartPersistenceService.put(
			catalogVersion,
			catalogStoragePartPersistenceService
		);
		this.catalogPersistenceServiceVersions = new long[]{catalogVersion};
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes().size()
		);
	}

	@Nonnull
	@Override
	public CatalogOffsetIndexStoragePartPersistenceService getStoragePartPersistenceService(long catalogVersion) {
		final int index = Arrays.binarySearch(this.catalogPersistenceServiceVersions, catalogVersion);
		final int lookupIndex = index >= 0 ? index : (-index - 2);
		Assert.isPremiseValid(
			lookupIndex >= 0 && lookupIndex < this.catalogPersistenceServiceVersions.length,
			() -> new EvitaInternalError("Catalog version " + catalogVersion + " not found in the catalog persistence service versions!")
		);
		return this.catalogStoragePartPersistenceService.get(
			this.catalogPersistenceServiceVersions[lookupIndex]
		);
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
			return new CatalogIndex(catalog);
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
						attributeKey, attributeSchema.getPlainType(), catalog,
						sharedUniqueIndexStoragePart.getUniqueValueToRecordId(),
						sharedUniqueIndexStoragePart.getLocaleIndex()
					)
				);
			}
			return new CatalogIndex(catalog, catalogIndexStoragePart.getVersion(), sharedUniqueIndexes);
		}
	}

	@Override
	public void storeHeader(
		@Nonnull CatalogState catalogState,
		long catalogVersion,
		int lastEntityCollectionPrimaryKey,
		@Nullable TransactionMutation lastProcessedTransaction,
		@Nonnull List<EntityCollectionHeader> entityHeaders
	) {
		// first we need to execute transition to alive state
		if (catalogState == CatalogState.ALIVE && catalogVersion == 0L) {
			this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex());
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
			this.catalogName,
			catalogState,
			lastEntityCollectionPrimaryKey
		);
		this.bootstrapUsed = recordBootstrap(catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex());
	}

	@Nonnull
	@Override
	public EntityCollectionPersistenceService getOrCreateEntityCollectionPersistenceService(
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
					findFirstAvailableFileIndex(entityType)
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
		@Nonnull EntityCollectionHeader entityCollectionHeader
	) {
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
				final EntityCollectionHeader compactedHeader = entityCollectionPersistenceService.compact(catalogVersion, headerInfoSupplier);
				final DefaultEntityCollectionPersistenceService newPersistenceService = this.entityCollectionPersistenceServices.computeIfAbsent(
					new CollectionFileReference(
						entityCollectionHeader.entityType(),
						entityCollectionHeader.entityTypePrimaryKey(),
						compactedHeader.entityTypeFileIndex(),
						compactedHeader.fileLocation()
					),
					eType -> new DefaultEntityCollectionPersistenceService(
						catalogVersion,
						this.catalogStoragePath,
						compactedHeader,
						this.storageOptions,
						this.transactionOptions,
						this.offHeapMemoryManager,
						this.observableOutputKeeper,
						this.recordTypeRegistry
					)
				);
				this.obsoleteFileMaintainer.removeFileWhenNotUsed(
					catalogVersion,
					this.catalogStoragePath.resolve(
						getEntityCollectionDataStoreFileName(entityCollectionHeader.entityType(), entityCollectionHeader.entityTypeFileIndex())
					),
					() -> this.entityCollectionPersistenceServices.remove(collectionFileReference)
				);
				Assert.isPremiseValid(
					newPersistenceService.getEntityCollectionHeader().equals(compactedHeader),
					() -> new EvitaInternalError("Unexpected header mismatch!")
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
	public void appendWalAndDiscard(
		long catalogVersion,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		try (walReference) {
			if (this.catalogWal == null) {
				final CatalogHeader catalogHeader = getCatalogHeader(catalogVersion);
				this.catalogWal = getCatalogWriteAheadLog(
					this.catalogName, this.catalogStoragePath, catalogHeader, this.catalogKryoPool,
					this.storageOptions, this.transactionOptions, this.scheduler, this::trimBootstrapFile
				);
			}
			Assert.isPremiseValid(
				walReference.getBuffer().isPresent() || walReference.getFilePath().isPresent(),
				"Unexpected WAL reference - neither off-heap buffer nor file reference present!"
			);

			this.catalogWal.append(transactionMutation, walReference);
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
		@Nonnull CatalogSchema catalogSchema
	) {
		final Path newPath = pathForNewCatalog(catalogNameToBeReplaced, storageOptions.storageDirectoryOrDefault());
		final boolean targetPathExists = newPath.toFile().exists();
		if (targetPathExists) {
			Assert.isPremiseValid(newPath.toFile().isDirectory(), () -> "Path `" + newPath.toAbsolutePath() + "` is not a directory!");
		}

		// store the catalog that replaces the original header
		final CatalogStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
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
			catalogHeader.walFileReference(),
			catalogHeader.collectionFileIndex(),
			catalogNameToBeReplaced,
			catalogHeader.catalogState(),
			catalogHeader.lastEntityCollectionPrimaryKey()
		);

		final int catalogIndex = this.bootstrapUsed.catalogFileIndex();
		final CatalogBootstrap newCatalogBootstrap = recordBootstrap(
			newCatalogVersion,
			catalogNameToBeReplaced,
			catalogIndex
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
					() -> new EvitaInternalError(
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
				this.recordTypeRegistry,
				this.observableOutputKeeper,
				this.offHeapMemoryManager,
				catalogNameToBeReplaced,
				newPath,
				newCatalogBootstrap,
				new WriteOnlyFileHandle(
					newPath.resolve(getCatalogBootstrapFileName(catalogNameToBeReplaced)),
					this.observableOutputKeeper
				),
				this.storageOptions,
				this.transactionOptions,
				this.scheduler,
				this.obsoleteFileMaintainer,
				this.catalogWal
			);
		} catch (RuntimeException ex) {
			// rename original directory back
			if (temporaryOriginal != null) {
				Assert.isPremiseValid(
					temporaryOriginal.toFile().renameTo(newPath.toFile()),
					() -> new EvitaInternalError(
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
			.orElseGet(() -> new CollectionFileReference(newEntityType, entityTypePrimaryKey, findFirstAvailableFileIndex(newEntityType), null));
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
		final PersistentStorageDescriptor newPersistentStorageDescriptor;
		try {
			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(newFile.createNewFile(), "Cannot create new entity collection file: `" + newFilePath + "`!");
			newPersistentStorageDescriptor = entityPersistenceService.copySnapshotTo(newFilePath, catalogVersion);
		} catch (RuntimeException | IOException ex) {
			// delete non-finished damaged file if exists
			if (newFile.exists()) {
				Assert.isPremiseValid(newFile.delete(), "Cannot remove unfinished file: `" + newFilePath + "`!");
			}
			if (ex instanceof RuntimeException runtimeException) {
				throw runtimeException;
			} else {
				throw new EvitaInternalError(
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
					this.catalogStoragePath,
					new EntityCollectionHeader(
						newEntityTypeExistingFileReference.entityType(),
						newEntityTypeFileIndex.entityTypePrimaryKey(),
						newEntityTypeFileIndex.fileIndex(),
						entityHeader.recordCount(),
						entityHeader.lastPrimaryKey(),
						entityHeader.lastEntityIndexPrimaryKey(),
						newPersistentStorageDescriptor,
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
			() -> this.entityCollectionPersistenceServices.remove(replacedEntityTypeFileReference)
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
		final Runnable removeCollection = () -> {
			final DefaultEntityCollectionPersistenceService persistenceService = this.entityCollectionPersistenceServices.remove(collectionFileReference);
			persistenceService.close();
		};
		this.obsoleteFileMaintainer.removeFileWhenNotUsed(
			catalogVersion - 1L,
			collectionFileReference.toFilePath(catalogStoragePath),
			removeCollection
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
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
			try {
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
			} finally {
				readHandle.forceClose();
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
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(long catalogVersion, @Nonnull String newCatalogName, int catalogFileIndex) {
		return recordBootstrap(catalogVersion, newCatalogName, catalogFileIndex, System.currentTimeMillis());
	}

	/**
	 * Records a bootstrap in the catalog.
	 *
	 * @param catalogVersion   the version of the catalog
	 * @param newCatalogName   the name of the new catalog
	 * @param catalogFileIndex the index of the catalog file
	 * @param timestamp the timestamp of the boot record
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	CatalogBootstrap recordBootstrap(long catalogVersion, @Nonnull String newCatalogName, int catalogFileIndex, long timestamp) {
		final OffsetDateTime bootstrapWriteTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toOffsetDateTime();
		final CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService = getStoragePartPersistenceService(catalogVersion);
		final OffsetIndexDescriptor flushedDescriptor = storagePartPersistenceService.flush(catalogVersion);
		final CatalogBootstrap bootstrapRecord;
		if (flushedDescriptor.getActiveRecordShare() < this.storageOptions.minimalActiveRecordShare() &&
			flushedDescriptor.getFileSize() > this.storageOptions.fileSizeCompactionThresholdBytes()) {
			final int newCatalogFileIndex = catalogFileIndex + 1;
			final String compactedFileName = getCatalogDataStoreFileName(newCatalogName, newCatalogFileIndex);
			final OffsetIndexDescriptor compactedDescriptor = storagePartPersistenceService.copySnapshotTo(
				this.catalogStoragePath.resolve(compactedFileName),
				catalogVersion
			);
			bootstrapRecord = new CatalogBootstrap(
				catalogVersion,
				newCatalogFileIndex,
				bootstrapWriteTime,
				compactedDescriptor.fileLocation()
			);
			this.obsoleteFileMaintainer.removeFileWhenNotUsed(
				catalogVersion,
				this.catalogStoragePath.resolve(getCatalogDataStoreFileName(newCatalogName, catalogFileIndex)),
				() -> removeCatalogPersistenceServiceForVersion(catalogVersion)
			);
			this.catalogStoragePartPersistenceService.put(
				catalogVersion,
				CatalogOffsetIndexStoragePartPersistenceService.create(
					this.catalogName,
					compactedFileName,
					this.catalogStoragePath.resolve(compactedFileName),
					this.storageOptions,
					this.transactionOptions,
					bootstrapRecord,
					this.recordTypeRegistry,
					this.offHeapMemoryManager,
					this.observableOutputKeeper,
					VERSIONED_KRYO_FACTORY
				)
			);
			this.catalogPersistenceServiceVersions = ArrayUtils.insertLongIntoOrderedArray(catalogVersion, this.catalogPersistenceServiceVersions);
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
			final WriteOnlyFileHandle originalBootstrapHandle = this.bootstrapWriteHandle.get();
			final WriteOnlyFileHandle bootstrapHandle = getOrCreateNewBootstrapTempWriteHandle(
				catalogVersion, newCatalogName, originalBootstrapHandle
			);

			// append to the existing file (we will compact it when the WAL files are purged)
			bootstrapHandle.checkAndExecuteAndSync(
				"store bootstrap record",
				() -> {
				},
				output -> new StorageRecord<>(
					output, catalogVersion, true,
					theOutput -> {
						theOutput.writeLong(bootstrapRecord.catalogVersion());
						theOutput.writeInt(bootstrapRecord.catalogFileIndex());
						theOutput.writeLong(timestamp);
						theOutput.writeLong(bootstrapRecord.fileLocation().startingPosition());
						theOutput.writeInt(bootstrapRecord.fileLocation().recordLength());
						return bootstrapRecord;
					}
				).payload(),
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
					() -> new EvitaInternalError("Failed to replace the bootstrap write handle in a critical section!")
				);
			}

			return bootstrapRecord;
		} finally {
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
			() -> new EvitaInternalError("Failed to replace the bootstrap write handle in a critical section!")
		);
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
		final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(
			fromFile,
			storageOptions.computeCRC32C()
		);
		try {
			boolean inValidRange = false;
			CatalogBootstrap previousBootstrapRecord = null;
			CatalogBootstrap bootstrapRecord = null;
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
		} finally {
			readHandle.forceClose();
		}
	}

	/**
	 * Copies the bootstrap record to the specified file handle.
	 *
	 * @param newBootstrapHandle The handle to the file where the bootstrap record will be copied.
	 * @param bootstrapRecord The bootstrap record to be copied.
	 */
	private static void copyBootstrapRecord(
		@Nonnull WriteOnlyFileHandle newBootstrapHandle,
		@Nonnull CatalogBootstrap bootstrapRecord
	) {
		newBootstrapHandle.checkAndExecute(
			"copy bootstrap record",
			() -> {},
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
		final int index = Arrays.binarySearch(this.catalogPersistenceServiceVersions, catalogVersion);
		final int lookupIndex = index >= 0 ? index : (-index - 2);
		Assert.isPremiseValid(
			lookupIndex >= 0 && lookupIndex < this.catalogPersistenceServiceVersions.length,
			() -> new EvitaInternalError("Catalog version " + catalogVersion + " not found in the catalog persistence service versions!")
		);
		final long versionToRemove = this.catalogPersistenceServiceVersions[lookupIndex];
		this.catalogPersistenceServiceVersions = ArrayUtils.removeLongFromArrayOnIndex(this.catalogPersistenceServiceVersions, lookupIndex);
		this.catalogStoragePartPersistenceService.remove(versionToRemove);
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
		final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
		try {
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
		} finally {
			readHandle.forceClose();
		}
	}

	/**
	 * Finds the first available file index for the given entity type.
	 *
	 * @param entityType the type of entity
	 * @return the first available file index
	 */
	private int findFirstAvailableFileIndex(@Nonnull String entityType) {
		final Pattern pattern = getEntityCollectionDataStoreFileNamePattern(entityType);
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
	 * DTO for passing the identified conflict in entity names for certain naming convention.
	 */
	record EntityNamingConventionConflict(
		@Nonnull EntitySchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

}
