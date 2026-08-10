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

import io.evitadb.api.CatalogVersionPin;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.executor.ClientCallableTask;
import io.evitadb.core.executor.Interruptible;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.store.catalog.CatalogDirectoryReadHold;
import io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.DefaultEntityCollectionPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.catalog.task.BackupTask.BackupSettings;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.model.StorageRecord;
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
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.STORAGE_PROTOCOL_VERSION;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.serializeBootstrapRecord;
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
	private final AtomicReference<ExportService> exportFileService;
	private final AtomicReference<DefaultCatalogPersistenceService> catalogPersistenceService;
	/**
	 * Holds the version this task is reading against reclamation. Idempotent and bound to the catalog instance that
	 * granted it, so the constructor's unwind path and {@link #tearDown()} cannot release it twice, and a catalog
	 * replaced mid-backup cannot be handed a release it never granted - either would decrement the pin of whichever
	 * other consumer holds that version and quietly take their protection away.
	 */
	private final CatalogVersionPin versionPin;
	/**
	 * Holds the catalog folder while a warm-up snapshot is being copied, `null` outside warm-up where the version pin
	 * is sufficient on its own. Released by {@link #tearDown()}.
	 */
	@Nullable private final CatalogDirectoryReadHold directoryReadHold;

	public BackupTask(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nonnull CatalogBootstrap bootstrapRecord,
		@Nonnull ExportService exportService,
		@Nonnull DefaultCatalogPersistenceService catalogPersistenceService,
		@Nullable LongFunction<CatalogVersionPin> onStart
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
		this.exportFileService = new AtomicReference<>(exportService);
		this.catalogPersistenceService = new AtomicReference<>(catalogPersistenceService);
		// this task reads everything through the bootstrap record it captured, so the version pin below is normally the
		// whole protection it needs - the trim is clamped to that version, so the record stays retained and the sweep
		// only ever removes what it cannot reach. Warm-up is the exception: every flush rewrites the bootstrap file
		// down to a single record, so the record captured here can be stranded while the task still holds it, and the
		// sweep - which re-derives its threshold from whatever record is oldest *now* - would take its files
		this.directoryReadHold = bootstrapRecord.catalogVersion() == 0L ?
			catalogPersistenceService.acquireDirectoryReadHold() : null;
		// everything from here on has to unwind the hold itself. A constructor that throws leaves no object behind:
		// `tearDown` is unreachable, and the caller's cancel-on-rejected-submission has no task to cancel. A hold left
		// open by that path is not a delayed reclamation, it is the permanent end of reclamation for this catalog -
		// silently, because the exception it rides out on looks perfectly handled
		CatalogVersionPin pin = CatalogVersionPin.NONE;
		try {
			if (onStart != null) {
				final long backedUpVersion = this.bootstrapRecord.catalogVersion();
				// from here on the pin is registered, and everything below can throw: the reachability check reads the
				// bootstrap file under the horizon lock. A pin left behind by that throw is not a delayed reclamation
				// either - it is the retention floor of this catalog frozen at this version for the rest of its life
				pin = onStart.apply(backedUpVersion);
				// the record was resolved before the pin was taken, and history can be given up in between - by the
				// time the pin lands, the files this record points at may already have been reclaimed. The pin itself
				// makes the check conclusive rather than another guess: once it is registered no further advance can
				// pass this version, so a window that is open now stays open for as long as the task holds it
				final long oldestRetainedVersion = catalogPersistenceService.getOldestRetainedCatalogVersion();
				if (oldestRetainedVersion > backedUpVersion) {
					throw new TemporalDataNotAvailableException(oldestRetainedVersion);
				}
			}
		} catch (RuntimeException ex) {
			try {
				if (this.directoryReadHold != null) {
					this.directoryReadHold.close();
				}
			} catch (RuntimeException unwindFailure) {
				// `ex` is the exception that explains why this task does not exist - it must be the one that gets out
				ex.addSuppressed(unwindFailure);
			} finally {
				// in a `finally` for the same reason `tearDown` puts it there: giving the folder back re-drives the
				// reclamation it deferred, which is real work that can throw. Closing a lease that was never granted
				// is a no-op, so this needs no flag tracking whether the pin landed
				pin.close();
			}
			throw ex;
		}
		this.versionPin = pin;
	}

	/**
	 * Method executes the backup logic for this catalog with particular settings.
	 *
	 * @return the path to the created backup file
	 */
	@Nonnull
	private FileForFetch doBackup() {
		final DefaultCatalogPersistenceService defaultCatalogPersistenceService = this.catalogPersistenceService.get();
		final ExportService exportService = this.exportFileService.get();
		Assert.isPremiseValid(
			defaultCatalogPersistenceService != null && exportService != null,
			"Backup has already been executed or the task has been interrupted! Resources are cleared!"
		);
		try {
			final BackupSettings settings = getStatus().settings();
			final OffsetDateTime thePastMoment = settings.pastMoment();
			final Long theHistoricalCatalogVersion = settings.catalogVersion();
			final boolean theIncludingWAL = settings.includingWAL();
			final long catalogVersion = this.bootstrapRecord.catalogVersion();

			log.info("Starting backup of catalog `{}` at version {}.", this.catalogName, catalogVersion);

			final ExportFileHandle exportFileHandle = exportService.storeFile(
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
						final CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader =
							catalogOffsetIndexPersistenceService.getCatalogHeader(catalogVersion);
						final Map<String, EntityCollectionFileHeader> entityHeaders = CollectionUtils.createHashMap(
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
						backupBootstrapRecord(
							catalogVersion, zipOutputStream, catalogDataFileDescriptor, defaultCatalogPersistenceService
						);
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to backup catalog `" + this.catalogName + "`!",
							"Failed to backup catalog!",
							e
						);
					}
				}

				log.info("Backup of catalog `{}` at version {} completed.", this.catalogName, catalogVersion);

				try {
					return exportFileHandle.fileForFetchFuture().get();
				} catch (Exception e) {
					throw new GenericEvitaInternalError(
						"Unexpected error when retrieving the backup file for catalog `" + this.catalogName + "`: " + e.getMessage(),
						"Failed to retrieve the backup file for catalog `" + this.catalogName + "` after successful creation!",
						e
					);
				}
			} catch (RuntimeException exception) {
				// remove the files
				ofNullable(exportFileHandle.fileForFetchFuture().getNow(null))
					.ifPresent(it -> exportService.deleteFile(it.fileId()));

				throw exception;
			}
		} finally {
			tearDown();
		}
	}

	@Override
	public boolean cancel() {
		final boolean cancel = super.cancel();
		// the tear-down is deliberately NOT gated on `cancel` - `Scheduler#addTaskToQueue` fails the task before it
		// throws the rejection, so the `cancel()` that `Catalog#submitBackupTask` runs on that path finds the future
		// already done and answers FALSE while the constructor-acquired version pin and folder hold are still held.
		// Gating cleanup on that answer leaks both for the life of the catalog, and since this task pins the version
		// it is reading, that stops every reclamation the catalog would otherwise do. `tearDown` is exactly-once by
		// construction, so running it on an already-finished task costs nothing
		tearDown();
		return cancel;
	}

	/**
	 * Cleans up resources used by this task.
	 */
	private void tearDown() {
		// free references to expensive resources
		this.catalogPersistenceService.set(null);
		this.exportFileService.set(null);
		try {
			if (this.directoryReadHold != null) {
				// idempotent, so the paths that reach this method twice give the folder back exactly once
				this.directoryReadHold.close();
			}
		} finally {
			// in a `finally` because giving the folder back re-drives the reclamation it deferred, which is real work
			// that can throw - and a pin left behind by that throw freezes the catalog's retention floor for good
			this.versionPin.close();
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
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull Map<String, EntityCollectionFileHeader> entityHeaders
	) throws IOException {
		final OffsetIndexDescriptor catalogDataFileDescriptor = catalogPersistenceService
			.copySnapshotTo(
				catalogVersion,
				zipOutputStream,
				recordsCopied -> doUpdateProgress(processedRecords + recordsCopied, servicesAndStatistics.totalRecords()),
				Stream.concat(
						Stream.of(
							new CatalogHeader<>(
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
		@Nonnull Map<String, EntityCollectionFileHeader> entityHeaders
	) throws IOException {
		final String entityDataFileName = CatalogPersistenceService.getEntityCollectionDataStoreFileName(
			entityTypeFileIndex.entityType(),
			entityTypeFileIndex.entityTypePrimaryKey(),
			0
		);
		zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + entityDataFileName));
		final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService = servicesAndStatistics.getServiceByEntityTypePrimaryKey(entityTypeFileIndex.entityTypePrimaryKey());
		final int finalBackedUpRecords = backedUpRecords;
		final EntityCollectionFileHeader newEntityCollectionHeader = entityCollectionPersistenceService
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
		@Nonnull OffsetIndexDescriptor catalogDataFileDescriptor,
		@Nonnull DefaultCatalogPersistenceService catalogPersistenceService
	) throws IOException {
		final String bootstrapFileName = getCatalogBootstrapFileName(this.catalogName);
		zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + bootstrapFileName));

		final ObservableOutput<ZipOutputStream> boostrapOutput = new ObservableOutput<>(
			zipOutputStream,
			CatalogBootstrap.BOOTSTRAP_RECORD_SIZE,
			CatalogBootstrap.BOOTSTRAP_RECORD_SIZE << 1,
			0L,
			catalogPersistenceService.createChecksum(),
			null
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
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull Closeables closeables
	) throws IOException {
		int totalRecords = 0;
		final Map<Integer, ServiceWithStatistics> entityCollectionPersistenceServices = new HashMap<>();
		final int catalogServiceRecordCount = catalogPersistenceService.countStorageParts(catalogVersion);
		totalRecords += catalogServiceRecordCount;

		for (CollectionFileReference entityTypeFileIndex : catalogHeader.getEntityTypeFileIndexes()) {
			final EntityCollectionFileHeader entityCollectionHeader = catalogPersistenceService.getStoragePart(
				catalogVersion,
				entityTypeFileIndex.entityTypePrimaryKey(),
				EntityCollectionFileHeader.class
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
