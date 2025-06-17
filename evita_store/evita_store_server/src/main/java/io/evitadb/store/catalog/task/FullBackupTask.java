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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.task.FullBackupTask.BackupSettings;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.evitadb.store.spi.CatalogPersistenceService.BOOT_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static java.util.Optional.ofNullable;

/**
 * Task responsible for backing up the catalog data folder with all the data files and WAL files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class FullBackupTask extends ClientCallableTask<BackupSettings, FileForFetch> {
	private final String catalogName;
	private final AtomicReference<ExportFileService> exportFileService;
	private final AtomicReference<LongConsumer> onComplete;
	private final AtomicReference<DefaultCatalogPersistenceService> catalogPersistenceService;
	private final long lastCatalogVersion;

	public FullBackupTask(
		@Nonnull String catalogName,
		@Nonnull ExportFileService exportFileService, @Nonnull DefaultCatalogPersistenceService catalogPersistenceService,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		super(
			catalogName,
			FullBackupTask.class.getSimpleName(),
			"Catalog " + catalogName + " full backup",
			new BackupSettings(),
			(task) -> ((FullBackupTask) task).doBackup(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED
		);
		this.catalogName = catalogName;
		this.catalogPersistenceService = new AtomicReference<>(catalogPersistenceService);
		this.exportFileService = new AtomicReference<>(exportFileService);
		this.onComplete = new AtomicReference<>(onComplete);
		this.lastCatalogVersion = catalogPersistenceService.getLastCatalogVersion();
		if (onStart != null) {
			onStart.accept(this.lastCatalogVersion);
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
	 * Method executes the backup logic for this catalog with particular settings.
	 *
	 * @return the path to the created backup file
	 */
	@Nonnull
	private FileForFetch doBackup() {
		final ExportFileService exportFileService = this.exportFileService.get();
		Assert.isPremiseValid(
			exportFileService != null,
			"Backup has already been executed or the task has been interrupted! Resources are cleared!"
		);
		try {
			log.info("Starting full backup of catalog `{}`.", this.catalogName);

			final Path backupFolder = exportFileService.getExportDirectory();
			if (!backupFolder.toFile().exists()) {
				Assert.isPremiseValid(backupFolder.toFile().mkdirs(), "Failed to create backup folder `" + backupFolder + "`!");
			}

			final ExportFileHandle exportFileHandle = exportFileService.storeFile(
				"full_backup_" + this.catalogName + "_" +
					OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + ".zip",
				"The full backup of the " + "catalog `" + this.catalogName + "` at version `" + this.lastCatalogVersion + "`.",
				"application/zip",
				this.getClass().getSimpleName()
			);

			try {
				final Path catalogStoragePath = this.catalogPersistenceService.get().getCatalogStoragePath();
				final AtomicInteger backedUpFiles = new AtomicInteger(0);
				final int totalFiles;
				try (Stream<Path> files = Files.walk(catalogStoragePath)) {
					totalFiles = Math.toIntExact(files.count());
				}

				try (ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(exportFileHandle.outputStream()))) {
					zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/"));
					zipOutputStream.closeEntry();

					// first, store the catalog bootstrap that contains pointers to other files
					backup(catalogStoragePath, zipOutputStream, backedUpFiles, totalFiles, BOOT_FILE_SUFFIX, "bootstrap");

					// then write the contents of the catalog file
					backup(catalogStoragePath, zipOutputStream, backedUpFiles, totalFiles, CatalogPersistenceService.CATALOG_FILE_SUFFIX, "catalog");

					// then all the entity collection data files
					backup(catalogStoragePath, zipOutputStream, backedUpFiles, totalFiles, CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX, "entity collection");

					// finally store all the WAL file with all records written so far
					backup(catalogStoragePath, zipOutputStream, backedUpFiles, totalFiles, WAL_FILE_SUFFIX, "write-ahead log");

				}

				log.info("Backup of catalog `{}` completed.", this.catalogName);

				return ofNullable(exportFileHandle.fileForFetchFuture().getNow(null))
					.orElseThrow(
						() -> new GenericEvitaInternalError(
							"File for fetch should be generated in close method and should be already available by now."
						)
					);
			} catch (Exception exception) {
				// remove the files
				ofNullable(exportFileHandle.fileForFetchFuture().getNow(null))
					.ifPresent(it -> exportFileService.deleteFile(it.fileId()));

				if (exception instanceof RuntimeException re) {
					throw re;
				} else {
					throw new UnexpectedIOException(
						"Failed to create full backup of catalog `" + this.catalogName + "`!",
						"Failed to create full backup!",
						exception
					);
				}
			}
		} finally {
			tearDown();
		}
	}

	/**
	 * Performs a backup of files from the specified catalog storage path into a zip output stream.
	 * Filters files based on the provided suffix and updates the progress as files are processed.
	 *
	 * @param catalogStoragePath the root path of the catalog storage to back up, must not be null
	 * @param zipOutputStream    the zip output stream where files will be written, must not be null
	 * @param backedUpFiles      an atomic counter tracking the number of files successfully backed up, must not be null
	 * @param totalFiles         the total number of files expected to be backed up
	 * @param suffix             the file suffix used to filter files for backup, must not be null
	 * @param contents           a descriptor for the type of files being backed up, used in error messages, must not be null
	 * @throws IOException if an I/O error occurs during the backup process
	 */
	private void backup(
		@Nonnull Path catalogStoragePath,
		@Nonnull ZipOutputStream zipOutputStream,
		@Nonnull AtomicInteger backedUpFiles,
		int totalFiles,
		@Nonnull String suffix,
		@Nonnull String contents
	) throws IOException {
		try (Stream<Path> files = Files.walk(catalogStoragePath)) {
			files.filter(path -> path.getFileName().toString().endsWith(suffix))
				// sort files by their name to ensure consistent order
				.sorted(Comparator.comparing(o -> o.getName(o.getNameCount() - 1)))
				.forEach(file -> {
					try {
						final String relativePath = catalogStoragePath.relativize(file).toString();
						zipOutputStream.putNextEntry(new ZipEntry(this.catalogName + "/" + relativePath));
						Files.copy(file, zipOutputStream);
						zipOutputStream.closeEntry();
						doUpdateProgress(backedUpFiles.incrementAndGet(), totalFiles);
					} catch (IOException e) {
						throw new UnexpectedIOException(
							"Failed to backup " + contents + " file `" + file + "`!",
							"Failed to backup " + contents + " file!",
							e
						);
					}
				});
		}
	}

	/**
	 * Cleans up resources used by this task.
	 */
	private void tearDown() {
		// free references to expensive resources
		this.catalogPersistenceService.set(null);
		this.exportFileService.set(null);
		this.catalogPersistenceService.set(null);
		final LongConsumer onComplete = this.onComplete.getAndSet(null);
		if (onComplete != null) {
			onComplete.accept(this.lastCatalogVersion);
		}
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
	 * Settings for this instance of backup task.
	 */
	public record BackupSettings() implements Serializable {

		@Nonnull
		@Override
		public String toString() {
			return "Full backup.";
		}
	}

}
