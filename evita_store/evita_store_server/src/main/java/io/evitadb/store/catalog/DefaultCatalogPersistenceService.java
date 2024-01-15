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
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
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
import io.evitadb.store.service.SerializationService;
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
import java.util.Iterator;
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
 * TODO JNO - implementovat proces uzavírání a odstraňování servis, koukajících do file indexů pro něž už neexistují transakce
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class DefaultCatalogPersistenceService implements CatalogPersistenceService {
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
	 * TODO JNO - document me
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
	@Nonnull
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
	 * The map contains index of already created {@link EntityCollectionPersistenceService entity collection services}.
	 * Instances of these services are costly and also contain references to the state, so that they must be kept as
	 * singletons.
	 */
	@Nonnull
	private final ConcurrentHashMap<CollectionFileReference, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServices;
	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	private final WriteOnlyHandle bootstrapWriteHandle;
	/**
	 * TODO JNO - document me
	 */
	private final CatalogBootstrap bootstrapUsed;
	/**
	 * TODO JNO - DOCUMENT ME
	 */
	@Nullable private final CatalogWriteAheadLog catalogWal;
	/**
	 * Pool contains instances of {@link SerializationService} that are used for serializing mutations in WAL.
	 */
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};

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
	 * TODO JNO - document me
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
	 * TODO JNO - document me
	 */
	private static void verifyCatalogNameMatches(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull StoragePartPersistenceService storagePartPersistenceService
	) {
		// verify that the catalog schema is the same as the one in the catalog directory
		final CatalogSchemaStoragePart catalogSchemaStoragePart = CatalogSchemaStoragePart.deserializeWithCatalog(
			catalogInstance,
			() -> storagePartPersistenceService.getStoragePart(1, CatalogSchemaStoragePart.class)
		);
		Assert.isTrue(
			catalogSchemaStoragePart.catalogSchema().getName().equals(catalogName),
			() -> new UnexpectedCatalogContentsException(
				"Directory " + catalogStoragePath + " contains data of " + catalogSchemaStoragePart.catalogSchema().getName() +
					" catalog. Cannot load catalog " + catalogName + " from this directory!"
			)
		);
	}

	@Nullable
	private static CatalogWriteAheadLog getWalFileChannel(
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
		@Nonnull StorageOptions storageOptions
	) {
		this.storageOptions = storageOptions;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			storageOptions.transactionMemoryBufferLimitSizeBytes(),
			storageOptions.transactionMemoryRegionCount()
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

			final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
			this.catalogWal = getWalFileChannel(
				catalogName, this.catalogStoragePath, catalogHeader, catalogKryoPool, storageOptions
			);
		} finally {
			this.observableOutputKeeper.free();
		}
	}

	public DefaultCatalogPersistenceService(
		@Nonnull CatalogContract catalogInstance,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageOptions storageOptions
	) {
		this.storageOptions = storageOptions;
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			storageOptions.transactionMemoryBufferLimitSizeBytes(),
			storageOptions.transactionMemoryRegionCount()
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
			observableOutputKeeper
		);

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		this.catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogName,
			catalogFileName,
			catalogFilePath,
			storageOptions,
			this.bootstrapUsed,
			recordTypeRegistry,
			offHeapMemoryManager, observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
		verifyCatalogNameMatches(catalogInstance, catalogName, catalogStoragePath, this.catalogStoragePartPersistenceService);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			this.catalogStoragePartPersistenceService.getCatalogHeader().getEntityTypeFileIndexes().size()
		);
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
		this.catalogWal = getWalFileChannel(
			catalogName, this.catalogStoragePath, catalogHeader, catalogKryoPool, storageOptions
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
		@Nonnull StorageOptions storageOptions
	) {
		this.bootstrapUsed = bootstrapUsed;
		this.recordTypeRegistry = fileOffsetIndexRecordTypeRegistry;
		this.observableOutputKeeper = observableOutputKeeper;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.bootstrapWriteHandle = bootstrapWriteHandle;
		this.storageOptions = storageOptions;

		final String catalogFileName = getCatalogDataStoreFileName(catalogName, this.bootstrapUsed.catalogFileIndex());
		final Path catalogFilePath = this.catalogStoragePath.resolve(catalogFileName);
		this.catalogStoragePartPersistenceService = CatalogOffsetIndexStoragePartPersistenceService.create(
			catalogName,
			catalogFileName,
			catalogFilePath,
			storageOptions,
			this.bootstrapUsed,
			recordTypeRegistry,
			offHeapMemoryManager, observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			this.catalogStoragePartPersistenceService.getCatalogHeader().getEntityTypeFileIndexes().size()
		);
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
		this.catalogWal = getWalFileChannel(
			catalogName, this.catalogStoragePath, catalogHeader, catalogKryoPool, storageOptions
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
		return this.catalogStoragePartPersistenceService.getCatalogHeader();
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
		final CatalogIndexStoragePart catalogIndexStoragePart = this.catalogStoragePartPersistenceService.getStoragePart(1, CatalogIndexStoragePart.class);
		if (catalogIndexStoragePart == null) {
			return new CatalogIndex(catalog);
		} else {
			final Set<AttributeKey> sharedAttributeUniqueIndexes = catalogIndexStoragePart.getSharedAttributeUniqueIndexes();
			final Map<AttributeKey, GlobalUniqueIndex> sharedUniqueIndexes = CollectionUtils.createHashMap(sharedAttributeUniqueIndexes.size());
			for (AttributeKey attributeKey : sharedAttributeUniqueIndexes) {
				final long partId = GlobalUniqueIndexStoragePart.computeUniquePartId(attributeKey, this.catalogStoragePartPersistenceService.getReadOnlyKeyCompressor());
				final GlobalUniqueIndexStoragePart sharedUniqueIndexStoragePart = this.catalogStoragePartPersistenceService.getStoragePart(partId, GlobalUniqueIndexStoragePart.class);
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
		@Nonnull List<EntityCollectionHeader> entityHeaders
	) {
		// first store all entity collection headers if they differ
		final CatalogHeader currentCatalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
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
			/* TODO JNO - tady přidat referenci na transakci ve WAL od prvního záznamu až po úplně poslední */
			null,
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
		recordBootstrap(catalogVersion, this.catalogName, this.bootstrapUsed.catalogFileIndex());
	}

	@Nonnull
	@Override
	public EntityCollectionPersistenceService createEntityCollectionPersistenceService(
		@Nonnull String entityType,
		int entityTypePrimaryKey
	) {
		final EntityCollectionHeader entityCollectionHeader = ofNullable(
			this.catalogStoragePartPersistenceService.getStoragePart(
				entityTypePrimaryKey, EntityCollectionHeader.class
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
			collectionFileReference.entityTypePrimaryKey(), EntityCollectionHeader.class
		);
	}

	@Nonnull
	@Override
	public IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId) {
		return new DefaultIsolatedWalService(
			transactionId,
			this.catalogKryoPool.obtain(),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.storageOptions.transactionWorkDirectory().resolve(transactionId + ".wal"),
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
		Assert.isPremiseValid(this.catalogWal != null, "Cannot append WAL to catalog that doesn't have WAL!");
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

		// first changes and replace name of the catalog in the catalog schema in catalog that replaces the original
		CatalogSchemaStoragePart.serializeWithCatalogName(
			catalogNameToBeReplaced,
			catalogNameVariationsToBeReplaced,
			() -> {
				final CatalogSchemaStoragePart storagePart = new CatalogSchemaStoragePart(catalogSchema);
				this.catalogStoragePartPersistenceService.putStoragePart(storagePart.getStoragePartPK(), storagePart);
				return null;
			}
		);

		// store the catalog that replaces the original header
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
		final long catalogVersion = catalogHeader.version() + 1;
		catalogStoragePartPersistenceService.writeCatalogHeader(
			STORAGE_PROTOCOL_VERSION,
			catalogVersion,
			/* TODO JNO - tady přidat referenci na transakci ve WAL od prvního záznamu až po úplně poslední */
			null,
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
				this.storageOptions
			);
		} catch (RuntimeException ex) {
			// rename original directory back
			if (temporaryOriginal != null) {
				Assert.isPremiseValid(
					temporaryOriginal.toFile().renameTo(newPath.toFile()),
					() -> new EvitaInternalError(
						"Failed to rename original directory back to `" + newPath.toAbsolutePath() + "` the original catalog will not be available as well!",
						"Failed to rename original directory back to the original catalog will not be available as well!",
						ex
					)
				);
			}
			throw ex;
		}
	}

	@Override
	public void replaceCollectionWith(@Nonnull String entityType, int entityTypePrimaryKey, @Nonnull String newEntityType) {
		final CatalogHeader catalogHeader = this.catalogStoragePartPersistenceService.getCatalogHeader();
		final CollectionFileReference newEntityTypeExistingFileIndex = catalogHeader.getEntityTypeFileIndexIfExists(entityType)
			.orElseGet(() -> new CollectionFileReference(entityType, entityTypePrimaryKey, 0, null));
		final CollectionFileReference newEntityTypeFileIndex = newEntityTypeExistingFileIndex.incrementAndGet();
		final File newFile = newEntityTypeFileIndex.toFilePath(catalogStoragePath).toFile();

		final DefaultEntityCollectionPersistenceService entityPersistenceService = this.entityCollectionPersistenceServices.get(
			newEntityTypeExistingFileIndex
		);
		Assert.isPremiseValid(
			entityPersistenceService != null,
			"Entity collection persistence service  for `" + entityType + "` not found in catalog `" + catalogName + "`!"
		);

		try {
			// define new catalog header (this should be still covered by transaction)
			final CatalogHeader newCatalogHeader = new CatalogHeader(
				STORAGE_PROTOCOL_VERSION,
				catalogHeader.version() + 1,
				// TODO JNO - tohle bude taky asi součástí nějaké WAL operace?!
				null,
				Stream.concat(
						catalogHeader.collectionFileIndex()
							.values()
							.stream()
							.filter(it -> !Objects.equals(it.entityType(), newEntityType) && !Objects.equals(it.entityType(), entityType)),
						Stream.of(newEntityTypeFileIndex)
					)
					.collect(
						Collectors.toMap(
							CollectionFileReference::entityType,
							Function.identity()
						)
					),
				catalogHeader.compressedKeys(),
				catalogHeader.catalogName(),
				catalogHeader.catalogState(),
				catalogHeader.lastEntityCollectionPrimaryKey()
			);

			// now copy living snapshot of the entity collection to a new file
			Assert.isPremiseValid(newFile.createNewFile(), "Cannot create new entity collection file: `" + newFile.toPath() + "`!");
			entityPersistenceService.copySnapshotTo(newFile);
		} catch (RuntimeException | IOException ex) {
			// delete non-finished damaged file if exists
			if (newFile.exists()) {
				Assert.isPremiseValid(newFile.delete(), "Cannot remove unfinished file: `" + newFile.toPath() + "`!");
			}
			if (ex instanceof RuntimeException runtimeException) {
				throw runtimeException;
			} else {
				throw new EvitaInternalError(
					"Unexpected error during entity collection rename: " + ex.getMessage(),
					"Unexpected error during entity collection rename!",
					ex
				);
			}
		}
	}

	@Override
	public void deleteEntityCollection(@Nonnull String entityType) {
		final CollectionFileReference entityFileIndex = this.catalogStoragePartPersistenceService.getCatalogHeader()
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
	public Iterator<Mutation> getCommittedMutationIterator(long catalogVersion) {
		return null;
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

	private CatalogBootstrap recordBootstrap(long catalogVersion, @Nonnull String newCatalogName, int catalogFileIndex) {
		final PersistentStorageDescriptor newDescriptor = this.catalogStoragePartPersistenceService.flush(catalogVersion);
		final Kryo kryo = catalogKryoPool.obtain();
		try {
			return this.bootstrapWriteHandle.checkAndExecuteAndSync(
				"store bootstrap record",
				() -> {
				},
				output -> {
					return new StorageRecord<>(
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
					).payload();
				},
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
