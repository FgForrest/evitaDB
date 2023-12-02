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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.BufferedChangeSet;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.exception.InvalidFileNameException;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.fileOffsetIndex.FileOffsetIndex;
import io.evitadb.store.fileOffsetIndex.FileOffsetIndexDescriptor;
import io.evitadb.store.fileOffsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.fileOffsetIndex.model.FileOffsetIndexRecordTypeRegistry;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.service.SerializationService;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.exception.HeaderFileNotFound;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * DefaultEntityCollectionPersistenceService class encapsulates main logic of {@link Catalog}
 * serialization to persistent storage and also deserializing the catalog contents back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class DefaultCatalogPersistenceService implements CatalogPersistenceService, PersistenceService {

	/**
	 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
	 * {@link ObservableOutput#getBuffer()} to use them for serialization. There buffers are not necessary when there are
	 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
	 */
	final ObservableOutputKeeper observableOutputKeeper;
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
	private final FileOffsetIndexRecordTypeRegistry recordTypeRegistry;
	/**
	 * Memory key-value store for indexes and schema.
	 */
	private final FileOffsetIndex memTable;
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
	private final ConcurrentHashMap<String, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServices;
	private final Pool<SerializationService<CatalogBootstrap>> headerSerializationServicePool = new Pool<>(true, false, 8) {
		@Override
		protected SerializationService<CatalogBootstrap> create() {
			return new CatalogBootstrapSerializationService(
				CatalogHeaderKryoConfigurer.INSTANCE
					.andThen(SharedClassesConfigurer.INSTANCE)
			);
		}
	};
	/**
	 * Contains reference to the catalog header collecting all crucial information about all entity collections.
	 * The catalog header is loaded in constructor and because it's immutable it needs to be replaced with each
	 * {@link #storeHeader(CatalogState, long, int, List)} call.
	 */
	@Getter @Nonnull
	private CatalogBootstrap catalogBootstrap;

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull StorageOptions storageOptions
	) {
		this.storageOptions = storageOptions;
		this.catalogName = catalogName;
		this.catalogStoragePath = pathForNewCatalog(catalogName, storageOptions.storageDirectoryOrDefault());
		this.verifyDirectory(this.catalogStoragePath, true);
		this.catalogBootstrap = new CatalogBootstrap(
			CatalogState.WARMING_UP, 0L,
			new CatalogHeader(catalogName, 0),
			Collections.emptyList()
		);
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(16);
		this.recordTypeRegistry = new FileOffsetIndexRecordTypeRegistry();
		this.memTable = new FileOffsetIndex(
			this.catalogStoragePath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(catalogName)),
			new FileOffsetIndexDescriptor(
				this.catalogBootstrap.getCatalogHeader(),
				this.createTypeKryoInstance(),
				false
			),
			storageOptions,
			recordTypeRegistry,
			observableOutputKeeper
		);
	}

	public DefaultCatalogPersistenceService(
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull StorageOptions storageOptions
	) {
		this.storageOptions = storageOptions;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.catalogBootstrap = readHeader(
			this.catalogStoragePath,
			this.verifyDirectory(this.catalogStoragePath, false)
		);
		this.observableOutputKeeper = new ObservableOutputKeeper(storageOptions);
		this.entityCollectionPersistenceServices = CollectionUtils.createConcurrentHashMap(
			ofNullable(this.catalogBootstrap).map(it -> it.getCollectionHeaders().size()).orElse(16)
		);
		this.recordTypeRegistry = new FileOffsetIndexRecordTypeRegistry();
		this.memTable = new FileOffsetIndex(
			this.catalogStoragePath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(catalogName)),
			new FileOffsetIndexDescriptor(
				this.catalogBootstrap.getCatalogHeader(),
				this.createTypeKryoInstance(),
				this.catalogBootstrap.getCatalogState() == CatalogState.ALIVE
			),
			storageOptions,
			recordTypeRegistry,
			observableOutputKeeper
		);
	}

	private DefaultCatalogPersistenceService(
		@Nonnull FileOffsetIndexRecordTypeRegistry memTableRecordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull FileOffsetIndex memTable,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ConcurrentHashMap<String, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServices,
		@Nonnull CatalogBootstrap catalogBootstrap
	) {
		this.recordTypeRegistry = memTableRecordTypeRegistry;
		this.observableOutputKeeper = observableOutputKeeper;
		this.catalogName = catalogName;
		this.catalogStoragePath = catalogStoragePath;
		this.memTable = memTable;
		this.storageOptions = storageOptions;
		this.entityCollectionPersistenceServices = entityCollectionPersistenceServices;
		this.catalogBootstrap = catalogBootstrap;
	}

	/**
	 * Creates {@link Kryo} instance that is usable for deserializing catalog content.
	 */
	public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
		return kryoKeyInputs -> VersionedKryoFactory.createKryo(
			kryoKeyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(new EntityStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
				.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
		);
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
		final CatalogIndexStoragePart catalogIndexStoragePart = this.memTable.get(1, CatalogIndexStoragePart.class);
		if (catalogIndexStoragePart == null) {
			return new CatalogIndex(catalog);
		} else {
			final Set<AttributeKey> sharedAttributeUniqueIndexes = catalogIndexStoragePart.getSharedAttributeUniqueIndexes();
			final Map<AttributeKey, GlobalUniqueIndex> sharedUniqueIndexes = CollectionUtils.createHashMap(sharedAttributeUniqueIndexes.size());
			for (AttributeKey attributeKey : sharedAttributeUniqueIndexes) {
				final long partId = GlobalUniqueIndexStoragePart.computeUniquePartId(attributeKey, getReadOnlyKeyCompressor());
				final GlobalUniqueIndexStoragePart sharedUniqueIndexStoragePart = this.memTable.get(partId, GlobalUniqueIndexStoragePart.class);
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
	public void storeHeader(@Nonnull CatalogState catalogState, long transactionId, int lastEntityCollectionPrimaryKey, @Nonnull List<EntityCollectionHeader> entityHeaders) {
		writeHeader(catalogName, this.catalogStoragePath, catalogState, transactionId, lastEntityCollectionPrimaryKey, entityHeaders);
	}

	@Nonnull
	@Override
	public EntityCollectionPersistenceService createEntityCollectionPersistenceService(@Nonnull String entityType, int entityTypePrimaryKey) {
		return this.entityCollectionPersistenceServices.computeIfAbsent(entityType, eType -> new DefaultEntityCollectionPersistenceService(
			pathForEntityCollectionFile(eType),
			ofNullable(catalogBootstrap.getEntityTypeHeader(eType))
				.orElseGet(() -> new EntityCollectionHeader(eType, entityTypePrimaryKey)),
			storageOptions,
			observableOutputKeeper,
			recordTypeRegistry,
			catalogBootstrap.supportsTransaction()
		));
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
	public void delete() {
		FileUtils.deleteDirectory(catalogStoragePath);
	}

	@Nonnull
	@Override
	public CatalogPersistenceService replaceWith(@Nonnull String catalogNameToBeReplaced, @Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced, @Nonnull CatalogSchema catalogSchema, long transactionId) {
		final Path newPath = pathForNewCatalog(catalogNameToBeReplaced, storageOptions.storageDirectoryOrDefault());
		final boolean targetPathExists = newPath.toFile().exists();
		if (targetPathExists) {
			Assert.isPremiseValid(newPath.toFile().isDirectory(), () -> "Path `" + newPath.toAbsolutePath() + "` is not a directory!");
		}

		// first replace name of the catalog in the catalog schema in catalog that replaces the original
		CatalogSchemaStoragePart.serializeWithCatalogName(
			catalogNameToBeReplaced,
			catalogNameVariationsToBeReplaced,
			() -> {
				putStoragePart(transactionId, new CatalogSchemaStoragePart(catalogSchema));
				return null;
			}
		);

		// store the catalog that replaces the original header
		final CatalogHeader catalogHeader = this.catalogBootstrap.getCatalogHeader();
		writeHeader(
			catalogNameToBeReplaced,
			catalogStoragePath,
			this.catalogBootstrap.getCatalogState(),
			transactionId,
			catalogHeader.getLastEntityCollectionPrimaryKey(),
			this.catalogBootstrap.getEntityTypeHeaders()
		);

		// close the catalog
		this.close();

		// name files in the directory that replaces the original first
		ofNullable(
			this.catalogStoragePath
				.toFile()
				.listFiles((dir, name) -> name.startsWith(this.catalogName + "."))
		)
			.stream()
			.flatMap(Arrays::stream)
			.forEach(it -> {
				final Path filePath = it.toPath();
				final String fileNameToRename = filePath.getFileName().toString().substring(this.catalogName.length());
				final Path filePathForRename = filePath.getParent()
					.resolve(catalogNameToBeReplaced + fileNameToRename);
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
				catalogNameToBeReplaced,
				newPath,
				new FileOffsetIndex(
					newPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(catalogNameToBeReplaced)),
					new FileOffsetIndexDescriptor(
						this.catalogBootstrap.getCatalogHeader(),
						this.createTypeKryoInstance(),
						this.catalogBootstrap.getCatalogState() == CatalogState.ALIVE
					),
					storageOptions,
					recordTypeRegistry,
					observableOutputKeeper
				),
				this.storageOptions,
				this.entityCollectionPersistenceServices,
				this.catalogBootstrap
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
	public void replaceCollectionWith(@Nonnull String entityType, int entityTypePrimaryKey, @Nonnull String newEntityType, long transactionId) {
		final File oldFile = pathForEntityCollectionFile(entityType).toFile();
		final File newFile = pathForEntityCollectionFile(newEntityType).toFile();
		final CatalogBootstrap originalBootstrap = this.catalogBootstrap;
		Optional<DefaultEntityCollectionPersistenceService> entityServiceForRename = Optional.empty();
		Optional<DefaultEntityCollectionPersistenceService> conflictingService = Optional.empty();
		File tmpFile = null;
		try {
			entityServiceForRename = ofNullable(this.entityCollectionPersistenceServices.get(entityType));
			conflictingService = ofNullable(this.entityCollectionPersistenceServices.remove(newEntityType));

			entityServiceForRename.ifPresent(EntityCollectionPersistenceService::close);
			conflictingService.ifPresent(EntityCollectionPersistenceService::close);

			// define new catalog header (this should be still covered by transaction)
			final EntityCollectionHeader entityHeaderToRename = catalogBootstrap.getEntityTypeHeader(entityType);
			this.catalogBootstrap = new CatalogBootstrap(
				this.catalogBootstrap.getCatalogState(),
				transactionId,
				this.catalogBootstrap.getCatalogHeader(),
				Stream.concat(
						catalogBootstrap.getEntityTypeHeaders()
							.stream()
							.filter(it -> !Objects.equals(it.getEntityType(), newEntityType) && !Objects.equals(it.getEntityType(), entityType)),
						Stream.of(
							new EntityCollectionHeader(
								newEntityType,
								entityTypePrimaryKey,
								entityHeaderToRename
							)
						)
					)
					.collect(Collectors.toList())
			);

			// now copy and overwrite the original file
			if (newFile.exists()) {
				tmpFile = File.createTempFile("evita", newFile.getName());
				Assert.isPremiseValid(tmpFile.delete(), "Cannot remove temporary file: `" + tmpFile.toPath() + "`");
				Assert.isPremiseValid(newFile.renameTo(tmpFile), "Cannot backup old entity collection file: `" + newFile.toPath() + "` to `" + tmpFile.toPath() + "`");
			}
			Assert.isPremiseValid(oldFile.renameTo(newFile), "Cannot move old entity collection file: `" + oldFile.toPath() + "` to `" + newFile.toPath() + "`");

			// let the service recreate itself
			this.entityCollectionPersistenceServices.remove(entityType);

		} catch (RuntimeException | IOException ex) {
			// rollback changes in map and recreate the original EntityCollectionPersistenceService
			if (tmpFile != null && tmpFile.exists()) {
				Assert.isPremiseValid(tmpFile.renameTo(newFile), "Cannot restore backed-up entity collection file: `" + tmpFile.toPath() + "` to `" + newFile.toPath() + "`");
			}
			this.catalogBootstrap = originalBootstrap;
			entityServiceForRename.ifPresent(it -> this.entityCollectionPersistenceServices.put(entityType, it));
			conflictingService.ifPresent(it -> this.entityCollectionPersistenceServices.put(
				newEntityType,
				new DefaultEntityCollectionPersistenceService(
					it,
					storageOptions,
					observableOutputKeeper,
					recordTypeRegistry,
					catalogBootstrap.supportsTransaction()
				)
			));
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
		final EntityCollectionPersistenceService entityCollectionPersistenceService = Objects.requireNonNull(this.entityCollectionPersistenceServices.get(entityType));
		entityCollectionPersistenceService.delete();
		this.entityCollectionPersistenceServices.remove(entityType);
	}

	@Override
	public void flushTrappedUpdates(@Nonnull BufferedChangeSet<CatalogIndexKey, CatalogIndex> bufferedChangeSet) {
		// now store all entity trapped updates
		bufferedChangeSet.getTrappedMemTableUpdates()
			.forEach(it -> memTable.put(0L, it));
	}

	/*
		PERSISTENCE SERVICE METHODS
	 */

	@Override
	public void close() {
		// close all services
		for (EntityCollectionPersistenceService entityCollectionPersistenceService : this.entityCollectionPersistenceServices.values()) {
			entityCollectionPersistenceService.close();
		}
		this.entityCollectionPersistenceServices.clear();
		// close current memTable
		if (this.memTable.isOperative()) {
			this.memTable.close();
		}
	}

	@Override
	public boolean isClosed() {
		return !this.memTable.isOperative();
	}

	@Override
	public <T extends StoragePart> T getStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		return this.memTable.get(primaryKey, containerType);
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long primaryKey, @Nonnull Class<T> containerType) {
		return this.memTable.getBinary(primaryKey, containerType);
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long transactionId, @Nonnull T container) {
		return this.memTable.put(transactionId, container);
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		return memTable.remove(primaryKey, containerType);
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		return memTable.contains(primaryKey, containerType);
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.memTable.getReadOnlyKeyCompressor();
	}

	/**
	 * Method writes header into the mem table.
	 */
	private void writeHeader(
		@Nonnull String theCatalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull CatalogState catalogState,
		long transactionId,
		int lastEntityCollectionPrimaryKey,
		@Nonnull Collection<EntityCollectionHeader> entityHeaders
	) {
		final long start = System.nanoTime();

		final long previousVersion = this.memTable.getVersion();
		final FileOffsetIndexDescriptor newDescriptor = this.memTable.flush(transactionId);
		final CatalogHeader catalogHeader;
		// when versions are equal - nothing has changed, and we can reuse old header
		if (newDescriptor.getVersion() > previousVersion || !Objects.equals(catalogName, theCatalogName)) {
			catalogHeader = new CatalogHeader(
				newDescriptor.getVersion(),
				newDescriptor.getFileLocation(),
				newDescriptor.getCompressedKeys(),
				theCatalogName,
				lastEntityCollectionPrimaryKey
			);
		} else {
			catalogHeader = this.catalogBootstrap.getCatalogHeader();
		}

		final SerializationService<CatalogBootstrap> catalogHeaderSerializationService = headerSerializationServicePool.obtain();
		try {

			final CatalogBootstrap catalogBootstrap = new CatalogBootstrap(
				catalogState,
				transactionId,
				catalogHeader,
				entityHeaders
			);

			final Path headerFile = catalogStoragePath.resolve(CatalogPersistenceService.getCatalogHeaderFileName(this.catalogName));
			try (final FileOutputStream os = new FileOutputStream(headerFile.toFile())) {
				catalogHeaderSerializationService.serialize(catalogBootstrap, os);
				logStatistics(catalogBootstrap, "has been written");
				this.catalogBootstrap = catalogBootstrap;
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to store Evita header file `" + headerFile + "`! All data are worthless :(",
					"Failed to store Evita header file! All data are worthless :(", e
				);
			}

		} finally {
			headerSerializationServicePool.free(catalogHeaderSerializationService);
			log.info("Catalog stored in " + StringUtils.formatNano(System.nanoTime() - start));
		}
	}

	/**
	 * Deserializes catalog from persistent storage. As of now the catalog is read entirely from scratch from
	 * catalog storage directory. Contents of the directory must contain previously serialized catalog of the identical
	 * catalog otherwise exception is thrown.
	 *
	 * @throws HeaderFileNotFound                 when target directory contains no header file
	 * @throws UnexpectedCatalogContentsException when directory contains different catalog data or no data at all
	 * @throws UnexpectedIOException              in case of any unknown IOException
	 */
	@Nonnull
	private CatalogBootstrap readHeader(@Nonnull Path catalogDirectory, @Nonnull String catalogName) {
		final long start = System.nanoTime();
		final SerializationService<CatalogBootstrap> serializationService = headerSerializationServicePool.obtain();
		try {
			final Path headerFilePath = catalogDirectory.resolve(CatalogPersistenceService.getCatalogHeaderFileName(catalogName));
			final File headerFile = headerFilePath.toFile();
			if (!headerFile.exists()) {
				throw new HeaderFileNotFound(catalogDirectory, headerFile);
			}
			final CatalogBootstrap catalogBootstrap;
			try (final FileInputStream is = new FileInputStream(headerFile)) {
				catalogBootstrap = serializationService.deserialize(is);
				logStatistics(catalogBootstrap, "is being loaded");
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to read Evita bootstrap file `" + headerFilePath + "`! No data will be readable for the catalog.",
					"Failed to read Evita bootstrap file! No data will be readable for the catalog.", e
				);
			}
			Assert.isTrue(
				catalogBootstrap.getCatalogHeader().getCatalogName().equals(catalogName),
				() -> new UnexpectedCatalogContentsException(
					"Directory " + catalogDirectory + " contains data of " + catalogBootstrap.getCatalogHeader().getCatalogName() +
						" catalog. Cannot load catalog " + catalogName + " from this directory!"
				)
			);
			return catalogBootstrap;
		} finally {
			headerSerializationServicePool.free(serializationService);
		}
	}

	/**
	 * Check whether target directory exists and whether it is really directory.
	 *
	 * @return name of the directory (e.g. catalog name)
	 */
	@Nonnull
	private String verifyDirectory(@Nonnull Path storageDirectory, boolean requireEmpty) {
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
	private Path pathForNewCatalog(@Nonnull String catalogName, @Nonnull Path storageDirectory) {
		try {
			return storageDirectory.resolve(catalogName);
		} catch (InvalidPathException ex) {
			throw new InvalidFileNameException("Name `" + catalogName + "` cannot be converted a valid file name: " + ex.getMessage() + "! Please rename the catalog.");
		}
	}

	/**
	 * Creates path for entity collection with specified name.
	 */
	@Nonnull
	private Path pathForEntityCollectionFile(@Nonnull String entityType) {
		return catalogStoragePath.resolve(
			CatalogPersistenceService.getEntityCollectionDataStoreFileName(
				StringUtils.toCamelCase(entityType)
			)
		);
	}

	/**
	 * Logs statistics about the catalog and entities in it to the logger.
	 */
	private void logStatistics(@Nonnull CatalogBootstrap catalogHeader, @Nonnull String operation) {
		if (log.isInfoEnabled()) {
			final StringBuilder stats = new StringBuilder("Catalog `" + catalogHeader.getCatalogHeader().getCatalogName() + "` " + operation + " and ");
			final Map<String, EntityCollectionHeader> collectionHeaders = catalogHeader.getCollectionHeaders();
			if (collectionHeaders.isEmpty()) {
				stats.append("it's empty.");
			} else {
				stats.append("it contains:");
				for (EntityCollectionHeader entityTypeHeader : collectionHeaders.values()) {
					stats.append("\n\t- ")
						.append(entityTypeHeader.getEntityType())
						.append(" (")
						.append(entityTypeHeader.getRecordCount())
						.append(")");
				}
			}
			log.info(stats.toString());
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
