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
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogVersionBeyondTheHorizonListener;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.attribute.GlobalUniqueIndex;
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
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.CatalogStoragePartPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
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
	 * The storage part persistence service implementation.
	 */
	@Nonnull
	private final CatalogStoragePartPersistenceService catalogStoragePartPersistenceService;
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
	private final WriteOnlyHandle bootstrapWriteHandle;
	/**
	 * Contains the instance of {@link CatalogBootstrap} that contains the last bootstrap record that is currently used.
	 */
	private CatalogBootstrap bootstrapUsed;
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
			try {
				final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, storageOptions.computeCRC32C());
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
		@Nonnull StorageOptions storageOptions
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
					catalogName, catalogStoragePath, walFileReference, catalogKryoPool, storageOptions
				)
			)
			.orElse(null);
	}

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = pathForNewCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
		verifyDirectory(this.catalogStoragePath, true);
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		final CatalogBootstrap lastCatalogBootstrap = getLastCatalogBootstrap(
			this.catalogStoragePath, verifiedCatalogName, storageOptions
		);
		this.bootstrapWriteHandle = new WriteOnlyFileHandle(
			this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
			this.observableOutputKeeper
		);
		final String catalogFileName = getCatalogDataStoreFileName(catalogName, lastCatalogBootstrap.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);

		try {
			this.observableOutputKeeper.prepare();
			this.catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
				catalogName,
				catalogFileName,
				catalogFilePath,
				storageOptions,
				transactionOptions,
				lastCatalogBootstrap,
				recordTypeRegistry,
				offHeapMemoryManager,
				observableOutputKeeper,
				VERSIONED_KRYO_FACTORY
			);
			this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(16);

			if (lastCatalogBootstrap.fileLocation() == null) {
				this.bootstrapUsed = recordBootstrap(0, this.catalogName, 0);
			} else {
				this.bootstrapUsed = lastCatalogBootstrap;
			}
		} finally {
			this.observableOutputKeeper.free();
		}
	}

	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions);
		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		final String verifiedCatalogName = verifyDirectory(this.catalogStoragePath, false);
		this.bootstrapUsed = getLastCatalogBootstrap(
			catalogStoragePath, verifiedCatalogName, storageOptions
		);
		this.bootstrapWriteHandle = new WriteOnlyFileHandle(
			this.catalogStoragePath.resolve(getCatalogBootstrapFileName(catalogName)),
			this.observableOutputKeeper
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		this.catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogName,
			catalogFileName,
			catalogFilePath,
			storageOptions,
			transactionOptions,
			this.bootstrapUsed,
			this.recordTypeRegistry,
			this.offHeapMemoryManager,
			this.observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
		final long catalogVersion = this.bootstrapUsed.catalogVersion();
		verifyCatalogNameMatches(
			catalogInstance, catalogVersion, catalogName, catalogStoragePath,
			this.catalogStoragePartPersistenceService
		);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			this.catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion).getEntityTypeFileIndexes().size()
		);
	}

	private DefaultCatalogPersistenceService(
		@Nonnull OffsetIndexRecordTypeRegistry fileOffsetIndexRecordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogBootstrap bootstrapUsed,
		@Nonnull WriteOnlyHandle bootstrapWriteHandle,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions
	) {
		this.bootstrapUsed = bootstrapUsed;
		this.recordTypeRegistry = fileOffsetIndexRecordTypeRegistry;
		this.observableOutputKeeper = observableOutputKeeper;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.bootstrapWriteHandle = bootstrapWriteHandle;
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		this.catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogName,
			catalogFileName,
			catalogFilePath,
			storageOptions,
			transactionOptions,
			this.bootstrapUsed,
			recordTypeRegistry,
			offHeapMemoryManager, observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			this.catalogStoragePartPersistenceService.getCatalogHeader(this.bootstrapUsed.catalogVersion()).getEntityTypeFileIndexes().size()
		);
	}

	@Nonnull
	@Override
	public CatalogStoragePartPersistenceService getStoragePartPersistenceService() {
		return catalogStoragePartPersistenceService;
	}

	@Nonnull
	@Override
	public CatalogHeader getCatalogHeader() {
		return this.catalogStoragePartPersistenceService.getCatalogHeader(bootstrapUsed.catalogVersion());
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
		final CatalogIndexStoragePart catalogIndexStoragePart = this.catalogStoragePartPersistenceService.getStoragePart(catalogVersion, 1, CatalogIndexStoragePart.class);
		if (catalogIndexStoragePart == null) {
			return new CatalogIndex(catalog);
		} else {
			final Set<AttributeKey> sharedAttributeUniqueIndexes = catalogIndexStoragePart.getSharedAttributeUniqueIndexes();
			final Map<AttributeKey, GlobalUniqueIndex> sharedUniqueIndexes = CollectionUtils.createHashMap(sharedAttributeUniqueIndexes.size());
			for (AttributeKey attributeKey : sharedAttributeUniqueIndexes) {
				final long partId = GlobalUniqueIndexStoragePart.computeUniquePartId(attributeKey, this.catalogStoragePartPersistenceService.getReadOnlyKeyCompressor());
				final GlobalUniqueIndexStoragePart sharedUniqueIndexStoragePart = this.catalogStoragePartPersistenceService.getStoragePart(catalogVersion, partId, GlobalUniqueIndexStoragePart.class);
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
		final CatalogHeader currentCatalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader(catalogVersion);
		for (EntityCollectionHeader entityHeader : entityHeaders) {
			final FileLocation currentLocation = entityHeader.fileLocation();
			final Optional<FileLocation> previousLocation = currentCatalogHeader
				.getEntityTypeFileIndexIfExists(entityHeader.entityType())
				.map(CollectionFileReference::fileLocation);
			// if the location is different, store the header
			if (!previousLocation.map(it -> it.equals(currentLocation)).orElse(false)) {
				this.catalogStoragePartPersistenceService.putStoragePart(catalogVersion, entityHeader);
			}
		}

		this.catalogStoragePartPersistenceService.writeCatalogHeader(
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
	public EntityCollectionPersistenceService createEntityCollectionPersistenceService(
		@Nonnull String entityType,
		int entityTypePrimaryKey
	) {
		final EntityCollectionHeader entityCollectionHeader = ofNullable(
			this.catalogStoragePartPersistenceService.getStoragePart(
				bootstrapUsed.catalogVersion(), entityTypePrimaryKey, EntityCollectionHeader.class
			)
		).orElseGet(() -> new EntityCollectionHeader(entityType, entityTypePrimaryKey));
		return this.entityCollectionPersistenceServices.computeIfAbsent(
			new CollectionFileReference(
				entityType,
				entityTypePrimaryKey,
				entityCollectionHeader.entityTypeFileIndex(),
				entityCollectionHeader.fileLocation()
			),
			eType -> new DefaultEntityCollectionPersistenceService(
				catalogStoragePath,
				entityCollectionHeader,
				storageOptions,
				transactionOptions,
				offHeapMemoryManager,
				observableOutputKeeper,
				recordTypeRegistry
			)
		);
	}

	@Nonnull
	@Override
	public EntityCollectionHeader getEntityCollectionHeader(@Nonnull CollectionFileReference collectionFileReference) {
		return this.catalogStoragePartPersistenceService.getStoragePart(
			bootstrapUsed.catalogVersion(), collectionFileReference.entityTypePrimaryKey(), EntityCollectionHeader.class
		);
	}

	@Nonnull
	@Override
	public IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId) {
		return new DefaultIsolatedWalService(
			transactionId,
			this.catalogKryoPool.obtain(),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.transactionOptions.transactionWorkDirectory().resolve(transactionId + ".wal"),
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
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		if (this.catalogWal == null) {
			final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader(bootstrapUsed.catalogVersion());
			this.catalogWal = getCatalogWriteAheadLog(
				catalogName, this.catalogStoragePath, catalogHeader, catalogKryoPool, storageOptions
			);
		}
		Assert.isPremiseValid(
			walReference.getBuffer().isPresent() || walReference.getFilePath().isPresent(),
			"Unexpected WAL reference - neither off-heap buffer nor file reference present!"
		);

		this.catalogWal.append(transactionMutation, walReference);
	}

	@Nonnull
	@Override
	public CatalogPersistenceService replaceWith(
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
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader(bootstrapUsed.catalogVersion());
		final long catalogVersion = catalogHeader.catalogState() == CatalogState.WARMING_UP ?
			0L : catalogHeader.version() + 1;

		// first changes and replace name of the catalog in the catalog schema in catalog that replaces the original
		CatalogSchemaStoragePart.serializeWithCatalogName(
			catalogNameToBeReplaced,
			catalogNameVariationsToBeReplaced,
			() -> {
				final CatalogSchemaStoragePart storagePart = new CatalogSchemaStoragePart(catalogSchema);
				this.catalogStoragePartPersistenceService.putStoragePart(catalogVersion, storagePart);
				return null;
			}
		);

		catalogStoragePartPersistenceService.writeCatalogHeader(
			STORAGE_PROTOCOL_VERSION,
			catalogVersion,
			catalogHeader.walFileReference(),
			catalogHeader.collectionFileIndex(),
			catalogNameToBeReplaced,
			catalogHeader.catalogState(),
			catalogHeader.lastEntityCollectionPrimaryKey()
		);
		final CatalogBootstrap newCatalogBootstrap = recordBootstrap(
			catalogVersion,
			catalogNameToBeReplaced,
			this.bootstrapUsed.catalogFileIndex()
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
				} else if (it.getName().equals(getCatalogDataStoreFileName(this.catalogName, this.bootstrapUsed.catalogFileIndex()))) {
					fileNameToRename = getCatalogDataStoreFileName(catalogNameToBeReplaced, this.bootstrapUsed.catalogFileIndex());
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
				this.transactionOptions
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
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType,
		long catalogVersion
	) {
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader(bootstrapUsed.catalogVersion());
		final CollectionFileReference replacedEntityTypeFileReference = catalogHeader.getEntityTypeFileIndexIfExists(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final CollectionFileReference newEntityTypeExistingFileReference = catalogHeader.getEntityTypeFileIndexIfExists(newEntityType)
			.orElseGet(() -> new CollectionFileReference(newEntityType, entityTypePrimaryKey, 0, null));
		final CollectionFileReference newEntityTypeFileIndex = newEntityTypeExistingFileReference.incrementAndGet();
		final Path newFilePath = newEntityTypeFileIndex.toFilePath(catalogStoragePath);

		final DefaultEntityCollectionPersistenceService entityPersistenceService = this.entityCollectionPersistenceServices.get(
			new CollectionFileReference(entityType, entityTypePrimaryKey, replacedEntityTypeFileReference.fileIndex(), null)
		);
		Assert.isPremiseValid(
			entityPersistenceService != null,
			"Entity collection persistence service for `" + entityType + "` not found in catalog `" + catalogName + "`!"
		);

		final File newFile = newFilePath.toFile();
		final PersistentStorageDescriptor persistentStorageDescriptor;
		try {
			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(newFile.createNewFile(), "Cannot create new entity collection file: `" + newFilePath + "`!");
			persistentStorageDescriptor = entityPersistenceService.copySnapshotTo(newFilePath, catalogVersion);
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

		return this.entityCollectionPersistenceServices.compute(
			newEntityTypeFileIndex,
			(eType, oldValue) -> {
				Assert.isPremiseValid(
					oldValue == null,
					"Entity collection persistence service for `" + newEntityType + "` already exists in catalog `" + catalogName + "`!"
				);
				final EntityCollectionHeader entityHeader = entityPersistenceService.getCatalogEntityHeader();
				return new DefaultEntityCollectionPersistenceService(
					catalogStoragePath,
					new EntityCollectionHeader(
						newEntityTypeExistingFileReference.entityType(),
						newEntityTypeFileIndex.entityTypePrimaryKey(),
						newEntityTypeFileIndex.fileIndex(),
						entityHeader.recordCount(),
						entityHeader.lastPrimaryKey(),
						entityHeader.lastEntityIndexPrimaryKey(),
						persistentStorageDescriptor,
						entityHeader.globalEntityIndexId(),
						entityHeader.usedEntityIndexIds()
					),
					storageOptions,
					transactionOptions,
					offHeapMemoryManager,
					observableOutputKeeper,
					recordTypeRegistry
				);
			}
		);
	}

	@Override
	public void deleteEntityCollection(@Nonnull String entityType) {
		final CollectionFileReference entityFileIndex = this.catalogStoragePartPersistenceService.getCatalogHeader(bootstrapUsed.catalogVersion())
			.getEntityTypeFileIndexIfExists(entityType)
			.orElseGet(() -> new CollectionFileReference(entityType, 0, 0, null));
		final EntityCollectionPersistenceService entityCollectionPersistenceService = Objects.requireNonNull(
			this.entityCollectionPersistenceServices.get(entityFileIndex)
		);
		entityCollectionPersistenceService.delete();
		this.entityCollectionPersistenceServices.remove(entityFileIndex);
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

	@Override
	public long getLastCatalogVersionInMutationStream() {
		if (this.catalogWal == null) {
			return 0L;
		} else {
			return this.catalogWal.getLastWrittenCatalogVersion();
		}
	}

	@Override
	public void catalogVersionBeyondTheHorizon(@Nonnull String catalogName, long catalogVersion, boolean activeSessionsToOlderVersions) {
		if (!activeSessionsToOlderVersions && catalogName.equals(this.catalogName)) {
			this.catalogStoragePartPersistenceService.purgeHistoryEqualAndLaterThan(catalogVersion);
		}
	}

	@Override
	public void forgetVolatileData() {
		this.catalogStoragePartPersistenceService.forgetVolatileData();
		for (DefaultEntityCollectionPersistenceService collectionPersistenceServices : entityCollectionPersistenceServices.values()) {
			collectionPersistenceServices.getStoragePartPersistenceService().forgetVolatileData();
		}
	}

	@Override
	public void close() {
		try {
			// close all services
			for (EntityCollectionPersistenceService entityCollectionPersistenceService : this.entityCollectionPersistenceServices.values()) {
				entityCollectionPersistenceService.close();
			}
			this.entityCollectionPersistenceServices.clear();
			// close current file offset index
			this.catalogStoragePartPersistenceService.close();
			// close WAL
			if (this.catalogWal != null) {
				this.catalogWal.close();
			}
		} catch (IOException e) {
			// ignore / log - we tried to close everything
			log.error("Failed to close catalog persistence service `" + this.catalogName + "`!", e);
		}
	}

	@Override
	public boolean isNew() {
		return this.catalogStoragePartPersistenceService.isNew();
	}

	@Override
	public void prepare() {
		observableOutputKeeper.prepare();
	}

	@Override
	public void release() {
		observableOutputKeeper.free();
	}

	@Override
	public <T> T executeWriteSafely(@Nonnull Supplier<T> lambda) {
		if (observableOutputKeeper.isPrepared()) {
			return lambda.get();
		} else {
			try {
				observableOutputKeeper.prepare();
				return lambda.get();
			} finally {
				observableOutputKeeper.free();
			}
		}
	}

	@Override
	public void flushTrappedUpdates(@Nonnull DataStoreIndexChanges<CatalogIndexKey, CatalogIndex> dataStoreIndexChanges) {
		// now store all entity trapped updates
		dataStoreIndexChanges.popTrappedUpdates()
			.forEach(it -> this.catalogStoragePartPersistenceService.putStoragePart(0L, it));
	}

	@Override
	public boolean isClosed() {
		return this.catalogStoragePartPersistenceService.isClosed();
	}

	/**
	 * Records a bootstrap in the catalog.
	 *
	 * @param catalogVersion the version of the catalog
	 * @param newCatalogName the name of the new catalog
	 * @param catalogFileIndex the index of the catalog file
	 * @return the recorded CatalogBootstrap object
	 */
	@Nonnull
	private CatalogBootstrap recordBootstrap(long catalogVersion, @Nonnull String newCatalogName, int catalogFileIndex) {
		final PersistentStorageDescriptor newDescriptor = this.catalogStoragePartPersistenceService.flush(catalogVersion);
		final Kryo kryo = catalogKryoPool.obtain();
		try {
			return this.bootstrapWriteHandle.checkAndExecuteAndSync(
				"store bootstrap record",
				() -> {
				},
				output -> new StorageRecord<>(
					output, catalogVersion, true,
					theOutput -> {
						final long now = System.currentTimeMillis();
						final CatalogBootstrap catalogBootstrap = new CatalogBootstrap(
							catalogVersion, catalogFileIndex,
							Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
							newDescriptor.fileLocation()
						);

						theOutput.writeLong(catalogBootstrap.catalogVersion());
						theOutput.writeInt(catalogBootstrap.catalogFileIndex());
						theOutput.writeLong(now);
						theOutput.writeLong(catalogBootstrap.fileLocation().startingPosition());
						theOutput.writeInt(catalogBootstrap.fileLocation().recordLength());
						return catalogBootstrap;
					}
				).payload(),
				(output, catalogBootstrap) -> catalogBootstrap
			);
		} finally {
			catalogKryoPool.free(kryo);
			log.debug("Catalog `{}` stored to `{}`.", newCatalogName, catalogStoragePath);
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
