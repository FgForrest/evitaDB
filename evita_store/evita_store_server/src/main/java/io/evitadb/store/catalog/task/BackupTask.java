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

package io.evitadb.store.catalog.task;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.executor.ClientCallableTask;
import io.evitadb.core.executor.Interruptible;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.file.ExportFileService.ExportFileHandle;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.catalog.task.BackupTask.BackupSettings;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.serializeBootstrapRecord;
import static io.evitadb.store.spi.CatalogPersistenceService.STORAGE_PROTOCOL_VERSION;
import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogBootstrapFileName;
import static java.util.Optional.ofNullable;

/**
 * Task responsible for backing up the catalog data and WAL files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class BackupTask extends ClientCallableTask<BackupSettings, FileForFetch> {
	private final String catalogName;
	private final CatalogBootstrap bootstrapRecord;
	private final AtomicReference<ExportFileService> exportFileService;
	private final AtomicReference<DefaultCatalogPersistenceService> catalogPersistenceService;
	private final AtomicReference<LongConsumer> onComplete;

	public BackupTask(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nonnull CatalogBootstrap bootstrapRecord,
		@Nonnull ExportFileService exportFileService,
		@Nonnull DefaultCatalogPersistenceService catalogPersistenceService,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		super(
			catalogName,
			BackupTask.class.getSimpleName(),
			"Catalog " + catalogName + " backup" +
				(pastMoment == null ? " with current data" : " snapshot at " + pastMoment) +
				(catalogVersion == null ? "" : " for version " + catalogVersion) +
				(includingWAL ? "" : ", including WAL"),
			new BackupSettings(pastMoment, catalogVersion, includingWAL),
			(task) -> ((BackupTask) task).doBackup(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED
		);
		Assert.isPremiseValid(
			catalogVersion == null || bootstrapRecord.catalogVersion() == catalogVersion,
			"Catalog version " + catalogVersion + " is not the same as the one in the bootstrap record " +
				bootstrapRecord.catalogVersion() + "!"
		);
		this.catalogName = catalogName;
		this.bootstrapRecord = bootstrapRecord;
		this.exportFileService = new AtomicReference<>(exportFileService);
		this.catalogPersistenceService = new AtomicReference<>(catalogPersistenceService);
		this.onComplete = new AtomicReference<>(onComplete);
		if (onStart != null) {
			onStart.accept(this.bootstrapRecord.catalogVersion());
		}
	}

	/**
	 * Method executes the backup logic for this catalog with particular settings.
	 *
	 * @return the path to the created backup file
	 */
	@Nonnull
	private FileForFetch doBackup() {
		final DefaultCatalogPersistenceService defaultCatalogPersistenceService = this.catalogPersistenceService.get();
		final ExportFileService exportFileService = this.exportFileService.get();
		Assert.isPremiseValid(
			defaultCatalogPersistenceService != null && exportFileService != null,
			"Backup has already been executed or the task has been interrupted! Resources are cleared!"
		);
		try {
			final BackupSettings settings = getStatus().settings();
			final OffsetDateTime thePastMoment = settings.pastMoment();
			final Long theHistoricalCatalogVersion = settings.catalogVersion();
			final boolean theIncludingWAL = settings.includingWAL();
			final long catalogVersion = this.bootstrapRecord.catalogVersion();

			log.info("Starting backup of catalog `{}` at version {}.", this.catalogName, catalogVersion);

			final Path backupFolder = defaultCatalogPersistenceService.getStorageOptions().exportDirectory();
			if (!backupFolder.toFile().exists()) {
				Assert.isPremiseValid(backupFolder.toFile().mkdirs(), "Failed to create backup folder `" + backupFolder + "`!");
			}

			final ExportFileHandle exportFileHandle = exportFileService.storeFile(
				"backup_" + this.catalogName + "_" +
					(thePastMoment == null && theHistoricalCatalogVersion == null ?
						"actual_" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) :
						"historical_" + (thePastMoment == null ? theHistoricalCatalogVersion : thePastMoment.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
					) + ".zip",
				"The backup of the " +
					(thePastMoment == null && theHistoricalCatalogVersion == null ? "actual " : "historical " + (thePastMoment == null ? theHistoricalCatalogVersion : thePastMoment)) +
					"catalog `" + this.catalogName + "`" + (theIncludingWAL ? " including WAL." : "."),
				"application/zip",
				this.getClass().getSimpleName()
			);

			try {
				try (final Closeables closeables = new Closeables()) {
					final CatalogOffsetIndexStoragePartPersistenceService catalogOffsetIndexPersistenceService = thePastMoment == null && theHistoricalCatalogVersion == null ?
						defaultCatalogPersistenceService.getStoragePartPersistenceService(catalogVersion) :
						closeables.add(defaultCatalogPersistenceService.createCatalogOffsetIndexStoragePartService(this.bootstrapRecord));

					try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(exportFileHandle.outputStream()))) {
						zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/"));
						zipOutputStream.closeEntry();

						// first store all the active contents of the entity collection data files
						final CatalogHeader catalogHeader = catalogOffsetIndexPersistenceService.getCatalogHeader(catalogVersion);
						final Map<String, EntityCollectionHeader> entityHeaders = CollectionUtils.createHashMap(
							catalogHeader.getEntityTypeFileIndexes().size()
						);

						// collect all entity collection services and calculate total record count to backup
						final ServicesAndStatistics servicesAndStatistics = getServicesAndStatistics(
							catalogVersion, thePastMoment, theHistoricalCatalogVersion, theIncludingWAL,
							defaultCatalogPersistenceService, catalogOffsetIndexPersistenceService,
							catalogHeader, closeables
						);

						int backedUpRecords = 0;
						for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
							backedUpRecords = backupEntityCollectionDataFile(
								catalogVersion, backedUpRecords, entityTypeFileIndex, zipOutputStream, servicesAndStatistics,
								entityHeaders
							);
						}

						// then write the active contents of the catalog file
						final String catalogDataStoreFileName = CatalogPersistenceService.getCatalogDataStoreFileName(this.catalogName, 0);
						zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + catalogDataStoreFileName));

						final OffsetIndexDescriptor catalogDataFileDescriptor = backupCatalogDataFile(
							catalogVersion, backedUpRecords, catalogOffsetIndexPersistenceService, zipOutputStream,
							servicesAndStatistics, catalogHeader, entityHeaders
						);
						backedUpRecords += servicesAndStatistics.catalogServiceRecordCount();

						// store the WAL file with all records written after the catalog version
						if (theIncludingWAL) {
							backupWAL(backedUpRecords, servicesAndStatistics, zipOutputStream);
						}

						// finally, store the catalog bootstrap
						backupBootstrapRecord(catalogVersion, zipOutputStream, catalogDataFileDescriptor);
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to backup catalog `" + this.catalogName + "`!",
							"Failed to backup catalog!",
							e
						);
					}
				}

				log.info("Backup of catalog `{}` at version {} completed.", this.catalogName, catalogVersion);

				return ofNullable(exportFileHandle.fileForFetchFuture().getNow(null))
					.orElseThrow(
						() -> new GenericEvitaInternalError(
							"File for fetch should be generated in close method and" +
								" should be already available by now."
						)
					);
			} catch (RuntimeException exception) {
				// remove the files
				ofNullable(exportFileHandle.fileForFetchFuture().getNow(null))
					.ifPresent(it -> exportFileService.deleteFile(it.fileId()));

				throw exception;
			}
		} finally {
			tearDown();
		}
	}

	@Override
	public boolean cancel() {
		final boolean cancel = super.cancel();
		if (cancel) {
			tearDown();
		}
		return cancel;
	}

	/**
	 * Cleans up resources used by this task.
	 */
	private void tearDown() {
		// free references to expensive resources
		this.catalogPersistenceService.set(null);
		this.exportFileService.set(null);
		final LongConsumer onComplete = this.onComplete.getAndSet(null);
		if (onComplete != null) {
			onComplete.accept(this.bootstrapRecord.catalogVersion());
		}
	}

	/**
	 * Copies the active contents of the catalog data file to the backup.
	 */
	@Interruptible
	@Nonnull
	private OffsetIndexDescriptor backupCatalogDataFile(
		long catalogVersion,
		int processedRecords,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService catalogPersistenceService,
		@Nonnull ZipOutputStream zipOutputStream,
		@Nonnull ServicesAndStatistics servicesAndStatistics,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Map<String, EntityCollectionHeader> entityHeaders
	) throws IOException {
		final OffsetIndexDescriptor catalogDataFileDescriptor = catalogPersistenceService
			.copySnapshotTo(
				catalogVersion,
				zipOutputStream,
				recordsCopied -> doUpdateProgress(processedRecords + recordsCopied, servicesAndStatistics.totalRecords()),
				Stream.concat(
						Stream.of(
							new CatalogHeader(
								STORAGE_PROTOCOL_VERSION,
								catalogHeader.version(),
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
		return catalogDataFileDescriptor;
	}

	/**
	 * Copies the active contents of the entity collection data file to the backup.
	 */
	@Interruptible
	private int backupEntityCollectionDataFile(
		long catalogVersion,
		int backedUpRecords,
		@Nonnull CollectionFileReference entityTypeFileIndex,
		@Nonnull ZipOutputStream zipOutputStream,
		@Nonnull ServicesAndStatistics servicesAndStatistics,
		@Nonnull Map<String, EntityCollectionHeader> entityHeaders
	) throws IOException {
		final String entityDataFileName = CatalogPersistenceService.getEntityCollectionDataStoreFileName(
			entityTypeFileIndex.entityType(),
			entityTypeFileIndex.entityTypePrimaryKey(),
			0
		);
		zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + entityDataFileName));
		final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = servicesAndStatistics.getServiceByEntityTypePrimaryKey(entityTypeFileIndex.entityTypePrimaryKey());
		final int finalBackedUpRecords = backedUpRecords;
		final EntityCollectionHeader newEntityCollectionHeader = entityCollectionPersistenceService
			.copySnapshotTo(
				catalogVersion,
				new CollectionFileReference(
					entityTypeFileIndex.entityType(),
					entityTypeFileIndex.entityTypePrimaryKey(),
					0,
					entityTypeFileIndex.fileLocation()
				),
				zipOutputStream,
				recordsCopied -> doUpdateProgress(finalBackedUpRecords + recordsCopied, servicesAndStatistics.totalRecords())
			);
		entityHeaders.put(
			entityTypeFileIndex.entityType(),
			newEntityCollectionHeader
		);
		zipOutputStream.closeEntry();
		return backedUpRecords + servicesAndStatistics.getServiceRecordCount(entityTypeFileIndex.entityTypePrimaryKey());
	}

	/**
	 * Copies the WAL files to the backup.
	 */
	@Interruptible
	private void backupWAL(
		int backedUpRecords,
		@Nonnull ServicesAndStatistics servicesAndStatistics,
		@Nonnull ZipOutputStream zipOutputStream
	) {
		for (int i = 0; i < servicesAndStatistics.walFiles().length; i++) {
			final Path walFile = servicesAndStatistics.walFiles()[i];
			try {
				zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + walFile.getFileName()));
				Files.copy(walFile, zipOutputStream);
				zipOutputStream.closeEntry();
				doUpdateProgress(backedUpRecords + i + 1, servicesAndStatistics.totalRecords());
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to backup WAL file `" + walFile + "`!",
					"Failed to backup WAL file!",
					e
				);
			}
		}
	}

	/**
	 * Stores the catalog bootstrap record to the backup.
	 */
	@Interruptible
	private void backupBootstrapRecord(
		long catalogVersion,
		@Nonnull ZipOutputStream zipOutputStream,
		@Nonnull OffsetIndexDescriptor catalogDataFileDescriptor
	) throws IOException {
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + bootstrapFileName));

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
	}

	/**
	 * Updates the progress of the backup task based on the number of processed records.
	 *
	 * @param processedRecords the records processed so far
	 * @param totalRecords     the total number of records to process
	 */
	@Interruptible
	private void doUpdateProgress(int processedRecords, int totalRecords) {
		this.updateProgress((int) (((float) processedRecords / (float) totalRecords) * 100.0));
	}

	/**
	 * Collects all entity collection services and calculates total record count to backup.
	 */
	@Nonnull
	private static ServicesAndStatistics getServicesAndStatistics(
		long catalogVersion,
		@Nullable OffsetDateTime thePastMoment,
		@Nullable Long theHistoricalCatalogVersion,
		boolean theIncludingWAL,
		@Nonnull DefaultCatalogPersistenceService defaultCatalogPersistenceService,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService catalogPersistenceService,
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull Closeables closeables
	) throws IOException {
		int totalRecords = 0;
		final Map<Integer, ServiceWithStatistics> entityCollectionPersistenceServices = new HashMap<>();
		final int catalogServiceRecordCount = catalogPersistenceService.countStorageParts(catalogVersion);
		totalRecords += catalogServiceRecordCount;

		for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
			final EntityCollectionHeader entityCollectionHeader = catalogPersistenceService.getStoragePart(
				catalogVersion,
				entityTypeFileIndex.entityTypePrimaryKey(),
				EntityCollectionHeader.class
			);
			Assert.isPremiseValid(
				entityCollectionHeader != null,
				"Entity collection header for entity type `" + entityTypeFileIndex.entityType() + "` was unexpectedly not created!"
			);
			final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = thePastMoment == null && theHistoricalCatalogVersion == null ?
				defaultCatalogPersistenceService.getOrCreateEntityCollectionPersistenceService(
					catalogVersion, entityTypeFileIndex.entityType(), entityTypeFileIndex.entityTypePrimaryKey()
				) :
				closeables.add(
					defaultCatalogPersistenceService.createEntityCollectionPersistenceService(entityCollectionHeader)
				);
			final ServiceWithStatistics serviceStats = new ServiceWithStatistics(
				entityCollectionPersistenceService,
				entityCollectionPersistenceService.getStoragePartPersistenceService().countStorageParts(catalogVersion)
			);
			entityCollectionPersistenceServices.put(
				entityTypeFileIndex.entityTypePrimaryKey(),
				serviceStats
			);
			totalRecords += serviceStats.totalRecordCount();
		}

		final Path[] walFiles;
		if (theIncludingWAL) {
			try (final Stream<Path> walFileStream = Files.list(defaultCatalogPersistenceService.getCatalogStoragePath())) {
				walFiles = walFileStream
					.filter(it -> it.getFileName().toString().endsWith(WAL_FILE_SUFFIX))
					.toArray(Path[]::new);
			}
		} else {
			walFiles = new Path[0];
		}

		return new ServicesAndStatistics(
			totalRecords + walFiles.length,
			catalogServiceRecordCount,
			entityCollectionPersistenceServices,
			walFiles
		);
	}

	/**
	 * Record contains total record count and index of services.
	 */
	private record ServicesAndStatistics(
		int totalRecords,
		int catalogServiceRecordCount,
		@Nonnull Map<Integer, ServiceWithStatistics> serviceIndex,
		@Nonnull Path[] walFiles
	) {

		@Nonnull
		public DefaultEntityCollectionPersistenceService getServiceByEntityTypePrimaryKey(int entityTypePrimaryKey) {
			return ofNullable(this.serviceIndex.get(entityTypePrimaryKey)).map(ServiceWithStatistics::service).orElseThrow();
		}

		public int getServiceRecordCount(int entityTypePrimaryKey) {
			return ofNullable(this.serviceIndex.get(entityTypePrimaryKey)).map(ServiceWithStatistics::totalRecordCount).orElseThrow();
		}

	}

	/**
	 * Record contains reference to service along with total record count it manages.
	 */
	private record ServiceWithStatistics(
		@Nonnull DefaultEntityCollectionPersistenceService service,
		int totalRecordCount
	) {
	}

	/**
	 * Settings for this instance of backup task.
	 *
	 * @param pastMoment     the date and time to create SNAPSHOT backup from
	 * @param catalogVersion precise catalog version to create backup for, or null to create backup for the latest version,
	 *                       when set not null, the pastMoment parameter is ignored
	 * @param includingWAL   whether to include WAL files in the backup
	 */
	public record BackupSettings(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) implements Serializable {

		@Nonnull
		@Override
		public String toString() {
			return Objects.requireNonNull(
				StringUtils.capitalize(
					(this.pastMoment == null ? "" : "pastMoment=" + EvitaDataTypes.formatValue(this.pastMoment) + ", ") +
						(this.catalogVersion == null ? "" : "catalogVersion=" + this.catalogVersion + ", ") +
						"includingWAL=" + this.includingWAL
				)
			);
		}
	}

	/**
	 * Closeable objects aggregator.
	 */
	private static class Closeables implements AutoCloseable {
		private final List<AutoCloseable> closeables = new LinkedList<>();

		/**
		 * Adds a new closeable to the list of closeables that are closed when this object is closed.
		 *
		 * @param item the closeable to add
		 * @param <T>  the type of the closeable
		 * @return the same closeable
		 */
		@Nonnull
		public <T extends Closeable> T add(@Nonnull T item) {
			this.closeables.add(item);
			return item;
		}

		@Override
		public void close() {
			this.closeables.forEach(it -> {
				try {
					it.close();
				} catch (Exception e) {
					log.error("Failed to close resource!", e);
				}
			});
		}
	}

}
