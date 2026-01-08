/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.catalog;

import io.evitadb.api.configuration.BackupScheduleOptions;
import io.evitadb.api.configuration.BackupType;
import io.evitadb.api.configuration.ScheduleOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.executor.ScheduledTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.cron.CronSchedule;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.store.catalog.header.model.CollectionReference;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.utils.StringUtils;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Interface providing support for scheduling automated backup tasks in the EvitaDB catalog system.
 *
 * <p>This interface is designed to be mixed into catalog implementations that require automated backup capabilities.
 * It provides methods for initializing and managing scheduled backup tasks based on configurable cron expressions
 * and retention policies. The interface supports two types of backups:</p>
 *
 * <ul>
 *     <li><b>Full Backup</b>: A complete backup of the entire catalog including all data and metadata.</li>
 *     <li><b>Snapshot Backup</b>: An incremental or point-in-time backup that captures the catalog state.</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 *     <li><b>Automated Scheduling</b>: Backups are scheduled using cron expressions, allowing flexible timing configurations.</li>
 *     <li><b>Retention Management</b>: Automatically enforces retention policies by removing old backups beyond the configured limit.</li>
 *     <li><b>Multiple Backup Types</b>: Supports both full and snapshot backups with independent schedules.</li>
 *     <li><b>Logging and Monitoring</b>: Provides detailed logging of backup operations including success/failure status and file sizes.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>Implementing classes should call {@link #initializeBackupTasks} during catalog initialization to set up
 * the scheduled backup tasks. The method returns a list of {@link ScheduledTask} instances that can be managed
 * by the implementing class.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * List<ScheduledTask> backupTasks = initializeBackupTasks(
 *     catalogName,
 *     scheduleConfiguration,
 *     scheduler,
 *     exportService,
 *     persistenceService,
 *     log
 * );
 * }</pre>
 *
 * <h2>Retention Policy</h2>
 * <p>The retention policy is applied after each successful backup execution. The system maintains only the most
 * recent N backups (where N is the configured retention count), and automatically deletes older backups. Backups
 * are ordered by creation time, with the most recent backups being preserved.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The backup execution is handled by the provided {@link Scheduler}, which manages concurrent execution.
 * Implementations should ensure that the provided persistence service and export service are thread-safe.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
interface ScheduledBackupSupport {

	/**
	 * Initializes and returns a list of scheduled backup tasks based on the provided backup schedule configuration.
	 * This method configures backup tasks (e.g., full backups or snapshots) for a catalog and schedules them
	 * according to the specified cron expressions and retention policies.
	 *
	 * @param catalogName           The name of the catalog for which backup tasks are being initialized.
	 * @param scheduleConfiguration The schedule configuration containing backup options such as backup type,
	 *                              cron expressions, and retention policies.
	 * @param scheduler             The scheduler responsible for executing the backup tasks at the defined intervals.
	 * @param persistenceService    The persistence service used to create and manage backup tasks, including full
	 *                              backups and snapshot backups.
	 * @return An unmodifiable list of scheduled backup tasks that have been initialized based on the provided
	 * configuration.
	 */
	@Nonnull
	default List<ScheduledTask> initializeBackupTasks(
		@Nonnull String catalogName,
		@Nonnull ScheduleOptions scheduleConfiguration,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService,
		@Nonnull CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> persistenceService,
		@Nonnull Logger log
	) {
		final List<BackupScheduleOptions> configuredBackups = scheduleConfiguration.backup();
		final List<ScheduledTask> theScheduledTasks = new ArrayList<>(configuredBackups.size());
		for (BackupScheduleOptions configuredBackup : configuredBackups) {
			final BackupType backupType = configuredBackup.backupType();
			final CronSchedule cronSchedule = CronSchedule.fromExpression(configuredBackup.cron());
			final int retention = configuredBackup.retention();
			final ScheduledTask scheduledTask = switch (backupType) {
				case FULL -> new ScheduledTask(
					catalogName,
					"AutomatedFullBackup",
					scheduler,
					() -> executeAutomaticBackup(
						catalogName,
						"full backup",
						persistenceService::createSystemFullBackupTask,
						scheduler,
						exportService,
						log,
						retention
					),
					cronSchedule
				);
				case SNAPSHOT -> new ScheduledTask(
					catalogName,
					"AutomatedSnapshotBackup",
					scheduler,
					() -> executeAutomaticBackup(
						catalogName,
						"snapshot backup",
						persistenceService::createSystemBackupTask,
						scheduler,
						exportService,
						log,
						retention
					),
					cronSchedule
				);
			};
			theScheduledTasks.add(scheduledTask);
			scheduledTask.schedule();
		}
		return Collections.unmodifiableList(theScheduledTasks);
	}

	/**
	 * Executes an automatic backup for a specific catalog and backup type, logs the result,
	 * and applies a retention policy to manage older backups.
	 *
	 * @param catalogName The name of the catalog for which the backup is being performed. Must not be null.
	 * @param backupType The type of backup being performed (e.g., "full" or "snapshot"). Must not be null.
	 * @param taskSupplier A supplier that provides the {@link ServerTask} responsible for executing the backup. Must not be null.
	 * @param exportService The service used to manage export operations and apply retention policies. Must not be null.
	 * @param log The logger used to record information about the backup process. Must not be null.
	 * @param retention The number of most recent backups to retain. Backups exceeding this number are deleted.
	 */
	private static void executeAutomaticBackup(
		@Nonnull String catalogName,
		@Nonnull String backupType,
		@Nonnull Supplier<ServerTask<?, FileForFetch>> taskSupplier,
		@Nonnull Scheduler scheduler,
		@Nonnull ExportService exportService,
		@Nonnull Logger log,
		int retention
	) {
		final ServerTask<?, FileForFetch> task = taskSupplier.get();
		final FileForFetch fileForFetch = scheduler.submit(task).join();
		if (fileForFetch == null || fileForFetch.totalSizeInBytes() <= 0L) {
			log.error(
				"Scheduled {} of catalog {} produced invalid file.",
				backupType,
				catalogName
			);
		} else {
			log.info(
				"Scheduled {} of catalog {} completed with size of {}.",
				backupType,
				catalogName,
				StringUtils.formatByteSize(fileForFetch.totalSizeInBytes())
			);

			applyRetentionPolicy(
				catalogName, backupType,
				Objects.requireNonNull(fileForFetch.origin()),
				exportService,
				log,
				retention
			);
		}
	}

	/**
	 * Applies a retention policy to remove old scheduled backups for a catalog.
	 *
	 * This method fetches the list of backups associated with the specified catalog and origin,
	 * identifies backups exceeding the retention policy, and deletes them using the provided
	 * export service. Logs are generated for each deleted backup to provide visibility into the process.
	 *
	 * @param catalogName   The name of the catalog for which the retention policy is being applied. Must not be null.
	 * @param backupType    The type of backup being processed (e.g., "full" or "snapshot"). Must not be null.
	 * @param origin        The origin identifiers to filter the backups. Must not be null.
	 * @param exportService The export service responsible for managing and deleting backups. Must not be null.
	 * @param log           The logger used for logging information about deleted backups. Must not be null.
	 * @param retention     The number of recent backups to retain. Backups exceeding this number are deleted.
	 */
	private static void applyRetentionPolicy(
		@Nonnull String catalogName,
		@Nonnull String backupType,
		@Nonnull String[] origin,
		@Nonnull ExportService exportService,
		@Nonnull Logger log,
		int retention
	) {
		// enforce retention policy
		final List<FileForFetch> filesToDelete = new ArrayList<>(retention + 4);
		PaginatedList<FileForFetch> existingBackups;
		int pageNumber = 1;
		do {
			existingBackups = exportService.listFilesToFetch(
				pageNumber, 1000, Set.of(catalogName), Set.of(origin)
			);
			final List<FileForFetch> data = existingBackups.getData();
			for (int i = 0; i < data.size(); i++) {
				final FileForFetch existingBackup = data.get(i);
				final int fileNumber = (pageNumber - 1) * 1000 + i;
				if (fileNumber >= retention) {
					filesToDelete.add(existingBackup);
				}
			}
			pageNumber++;
		} while (existingBackups.hasNext() && pageNumber < 100);

		// delete old backups
		for (FileForFetch fileToDelete : filesToDelete) {
			exportService.deleteFile(fileToDelete.fileId());
			log.info(
				"Deleted old scheduled {} of catalog {} with size of {}.",
				backupType,
				catalogName,
				StringUtils.formatByteSize(fileToDelete.totalSizeInBytes())
			);
		}
	}

}
