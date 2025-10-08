/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexBuilder;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.CatalogStoragePartPersistenceService;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * A default persistence service for storing and retrieving the offset index and catalog header of a catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class CatalogOffsetIndexStoragePartPersistenceService extends OffsetIndexStoragePartPersistenceService
	implements CatalogStoragePartPersistenceService {

	/**
	 * The current catalog header represents the header information of a catalog, which contains all the necessary
	 * information to fully restore the catalog state and indexes from persistent storage. The variable is initialized
	 * lazily.
	 */
	private CatalogHeader currentCatalogHeader;

	/**
	 * Creates a CatalogOffsetIndexStoragePartPersistenceService object with the given parameters.
	 * The code cannot be directly in the constructor, because we need to execute
	 * {@link #loadOffsetIndex(String, Path, StorageOptions, CatalogBootstrap, OffsetIndexRecordTypeRegistry, ObservableOutputKeeper, Function, Consumer, Consumer, Consumer)}
	 * and within it initialize the {@link #currentCatalogHeader} variable. This cannot be done in the consturctor
	 * because the super constructor needs to be called first.
	 *
	 * @param catalogName            The name of the catalog.
	 * @param catalogFilePath        The file path of the catalog.
	 * @param storageOptions         The storage options.
	 * @param transactionOptions     The transaction options.
	 * @param catalogBootstrap       The last catalog bootstrap.
	 * @param recordRegistry         The record type registry for offset index.
	 * @param offHeapMemoryManager   The off-heap memory manager.
	 * @param observableOutputKeeper The observable output keeper.
	 * @param kryoFactory            The factory to create Kryo instances.
	 * @return A CatalogOffsetIndexStoragePartPersistenceService object.
	 */
	@Nonnull
	public static CatalogOffsetIndexStoragePartPersistenceService create(
		@Nonnull String catalogName,
		@Nonnull Path catalogFilePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull CatalogBootstrap catalogBootstrap,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historyKeptObserver
	) {
		final AtomicReference<CatalogHeader> catalogHeaderRef = new AtomicReference<>();
		final OffsetIndex offsetIndex = loadOffsetIndex(
			catalogName, catalogFilePath, storageOptions,
			catalogBootstrap, recordRegistry, observableOutputKeeper,
			kryoFactory, nonFlushedBlockObserver, historyKeptObserver,
			catalogHeaderRef::set
		);
		return new CatalogOffsetIndexStoragePartPersistenceService(
			catalogBootstrap.catalogVersion(),
			catalogHeaderRef.get(),
			transactionOptions,
			offsetIndex,
			offHeapMemoryManager, observableOutputKeeper,
			kryoFactory
		);
	}

	/**
	 * This is a special constructor used only when catalog is renamed. It builds on previous instance of the service
	 * and reuses all data present in memory. Except the placement on disk nothing else is actually changed.
	 *
	 * @return a new instance of the service with the same data as the previous one but different file placement
	 */
	@Nonnull
	public static CatalogOffsetIndexStoragePartPersistenceService create(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogFilePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull CatalogBootstrap lastCatalogBootstrap,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historyKeptObserver,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService previous
	) {
		final CatalogHeader catalogHeader = previous.getCatalogHeader(catalogVersion);
		final OffsetIndex previousOffsetIndex = previous.offsetIndex;
		final OffsetIndexDescriptor offsetIndexDescriptor = new OffsetIndexDescriptor(
			previousOffsetIndex.getVersion(),
			previousOffsetIndex.getFileOffsetIndexLocation(),
			catalogHeader.compressedKeys(),
			kryoFactory,
			previousOffsetIndex.getActiveRecordShare(previousOffsetIndex.getTotalSizeBytes()),
			catalogFilePath.toFile().length()
		);

		final OffsetIndex offsetIndex = new OffsetIndex(
			lastCatalogBootstrap.catalogVersion(),
			catalogFilePath,
			storageOptions,
			recordRegistry,
			new WriteOnlyFileHandle(
				catalogName,
				FileType.CATALOG,
				catalogName,
				storageOptions,
				catalogFilePath,
				observableOutputKeeper
			),
			nonFlushedBlockObserver,
			historyKeptObserver,
			previousOffsetIndex,
			offsetIndexDescriptor
		);
		return new CatalogOffsetIndexStoragePartPersistenceService(
			lastCatalogBootstrap.catalogVersion(),
			catalogHeader,
			transactionOptions,
			offsetIndex,
			offHeapMemoryManager, observableOutputKeeper,
			kryoFactory
		);
	}

	/**
	 * Reads the catalog header bound to the given catalog file path and catalog bootstrap.
	 *
	 * @param catalogFilePath  The file path of the catalog.
	 * @param catalogBootstrap The last catalog bootstrap.
	 * @param recordRegistry   The record type registry for offset index.
	 * @return The loaded offset index.
	 */
	@Nonnull
	public static CatalogHeader readCatalogHeader(
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path catalogFilePath,
		@Nonnull CatalogBootstrap catalogBootstrap,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry
	) {
		final FileLocation fileLocation = catalogBootstrap.fileLocation();
		Assert.isPremiseValid(fileLocation != null, "File location must be present for catalog");
		final RecordKey catalogHeaderRecord = new RecordKey(recordRegistry.idFor(CatalogHeader.class), 1L);
		return OffsetIndex.readSingleRecord(
			storageOptions,
			catalogFilePath,
			fileLocation,
			catalogHeaderRecord,
			(indexBuilder, theInput) -> {
				// and load the catalog header
				final FileLocation catalogHeaderLocation = indexBuilder.getFileLocationFor(
					catalogHeaderRecord
				).orElseThrow(
					() -> new GenericEvitaInternalError(
						"Catalog header not found!",
						"Catalog header not found in the offset index file `" + catalogFilePath + "`."
					)
				);
				final Kryo kryo = KryoFactory.createKryo(
					SharedClassesConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				);
				return ofNullable(
					StorageRecord.read(
						theInput, catalogHeaderLocation,
						(input, recordLength, control) -> kryo.readObject(input, CatalogHeader.class)
					).payload()
				)
					.orElseThrow(() -> new GenericEvitaInternalError(
							"Catalog header not found!",
							"Catalog header not found in the offset index file `" + catalogFilePath + "`."
						)
					);
			}
		);
	}

	/**
	 * Loads an offset index descriptor based on the given parameters.
	 *
	 * @param catalogFilePath       the file to load offset index descriptor from
	 * @param recordRegistry        the record registry
	 * @param kryoFactory           the factory to create Kryo instances
	 * @param catalogHeaderConsumer the consumer to accept the catalog header
	 * @param indexBuilder          the index builder to use
	 * @param theInput              the observable input
	 * @param fileLocation          the file location to read record from
	 * @return the loaded offset index descriptor
	 */
	@Nonnull
	static OffsetIndexDescriptor loadOffsetIndexDescriptor(
		@Nonnull Path catalogFilePath,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nonnull Consumer<CatalogHeader> catalogHeaderConsumer,
		@Nonnull OffsetIndexBuilder indexBuilder,
		@Nonnull ObservableInput<?> theInput,
		@Nonnull FileLocation fileLocation
	) {
		// and load the catalog header
		final FileLocation catalogHeaderLocation = indexBuilder.getFileLocationFor(
			new RecordKey(recordRegistry.idFor(CatalogHeader.class), 1L)
		).orElseThrow(
			() -> new GenericEvitaInternalError(
				"Catalog header not found!",
				"Catalog header not found in the offset index file `" + catalogFilePath + "`."
			)
		);
		final Kryo kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
		);
		final CatalogHeader theCatalogHeader = Objects.requireNonNull(
			StorageRecord.read(
				theInput, catalogHeaderLocation,
				(input, recordLength, control) -> kryo.readObject(input, CatalogHeader.class)
			).payload()
		);

		catalogHeaderConsumer.accept(theCatalogHeader);
		return new OffsetIndexDescriptor(
			theCatalogHeader.version(),
			fileLocation,
			theCatalogHeader.compressedKeys(),
			kryoFactory,
			// we don't know here yet - this will be recomputed on the first flush
			theCatalogHeader.activeRecordShare(),
			catalogFilePath.toFile().length()
		);
	}

	/**
	 * Loads an offset index based on the given parameters.
	 *
	 * @param catalogName            The name of the catalog.
	 * @param catalogFilePath        The file path of the catalog.
	 * @param storageOptions         The storage options.
	 * @param catalogBootstrap       The last catalog bootstrap.
	 * @param recordRegistry         The record type registry for offset index.
	 * @param observableOutputKeeper The observable output keeper.
	 * @param kryoFactory            The factory to create Kryo instances.
	 * @param catalogHeaderConsumer  The consumer to accept the catalog header.
	 * @return The loaded offset index.
	 */
	@Nonnull
	private static OffsetIndex loadOffsetIndex(
		@Nonnull String catalogName,
		@Nonnull Path catalogFilePath,
		@Nonnull StorageOptions storageOptions,
		@Nonnull CatalogBootstrap catalogBootstrap,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nullable Consumer<NonFlushedBlock> nonFlushedBlockObserver,
		@Nullable Consumer<Optional<OffsetDateTime>> historyKeptObserver,
		@Nonnull Consumer<CatalogHeader> catalogHeaderConsumer
	) {
		final FileLocation fileLocation = catalogBootstrap.fileLocation();
		if (fileLocation == null) {
			// create new offset index
			final OffsetIndex newOffsetIndex = new OffsetIndex(
				catalogBootstrap.catalogVersion(),
				new OffsetIndexDescriptor(
					0L,
					FileLocation.EMPTY,
					Map.of(),
					kryoFactory,
					// we don't know here yet - this will be recomputed on first flush
					1.0, 0L
				),
				storageOptions,
				recordRegistry,
				new WriteOnlyFileHandle(
					catalogName,
					FileType.CATALOG,
					catalogName,
					storageOptions,
					catalogFilePath,
					observableOutputKeeper
				),
				nonFlushedBlockObserver,
				historyKeptObserver
			);
			final CatalogHeader newHeader = new CatalogHeader(UUID.randomUUID(), catalogName);
			newOffsetIndex.put(0L, newHeader);
			catalogHeaderConsumer.accept(newHeader);
			return newOffsetIndex;
		} else {
			Assert.isPremiseValid(
				catalogFilePath.toFile().exists(),
				() -> new UnexpectedIOException(
					"Catalog file `" + catalogFilePath + "` does not exist!",
					"Catalog file does not exist!"
				)
			);
			// load existing offset index
			return new OffsetIndex(
				catalogBootstrap.catalogVersion(),
				catalogFilePath,
				fileLocation,
				storageOptions,
				recordRegistry,
				new WriteOnlyFileHandle(
					catalogName,
					FileType.CATALOG,
					catalogName,
					storageOptions,
					catalogFilePath,
					observableOutputKeeper
				),
				nonFlushedBlockObserver,
				historyKeptObserver,
				(indexBuilder, theInput) -> loadOffsetIndexDescriptor(
					catalogFilePath, recordRegistry, kryoFactory, catalogHeaderConsumer,
					indexBuilder, theInput, fileLocation
				)
			);
		}
	}

	private CatalogOffsetIndexStoragePartPersistenceService(
		long catalogVersion,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory
	) {
		super(
			catalogVersion,
			catalogHeader.catalogName(),
			catalogHeader.catalogName(),
			FileType.CATALOG,
			transactionOptions,
			offsetIndex,
			offHeapMemoryManager,
			observableOutputKeeper,
			kryoFactory
		);
		this.currentCatalogHeader = catalogHeader;
	}

	@Nonnull
	@Override
	public CatalogHeader getCatalogHeader(long catalogVersion) {
		if (this.currentCatalogHeader == null) {
			this.currentCatalogHeader = Objects.requireNonNull(
				this.offsetIndex.get(catalogVersion, 1L, CatalogHeader.class)
			);
		}
		return this.currentCatalogHeader;
	}

	@Override
	public void writeCatalogHeader(
		int storageProtocolVersion,
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nullable LogFileRecordReference walFileLocation,
		@Nonnull Map<String, CollectionFileReference> collectionFileReferenceIndex,
		@Nonnull UUID catalogId,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		int lastEntityCollectionPrimaryKey
	) {
		final CatalogHeader newCatalogHeader = new CatalogHeader(
			storageProtocolVersion,
			catalogVersion,
			walFileLocation,
			collectionFileReferenceIndex,
			this.offsetIndex.getCompressedKeys(),
			catalogId,
			catalogName,
			catalogState,
			lastEntityCollectionPrimaryKey,
			this.offsetIndex.getActiveRecordShare(catalogStoragePath.resolve(this.name).toFile().length())
		);
		putStoragePart(catalogVersion, newCatalogHeader);
		this.currentCatalogHeader = newCatalogHeader;
	}

	@Override
	public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
		if (this.offsetIndex.isOperative()) {
			this.offsetIndex.purge(lastKnownMinimalActiveVersion - 1L);
		}
	}

}
