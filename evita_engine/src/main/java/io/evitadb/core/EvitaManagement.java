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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.executor.SequentialTask;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Main implementation of {@link EvitaManagementContract}.
 *
 * @see EvitaManagementContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EvitaManagement implements EvitaManagementContract, Closeable {
	/**
	 * Contains reference to the main evita service.
	 */
	private final Evita evita;
	/**
	 * Contains reference to Evita service executor / scheduler.
	 */
	private final Scheduler scheduler;
	/**
	 * This variable represents the starting date and time.
	 */
	private final OffsetDateTime started;
	/**
	 * File service that maintains exported files and purges them eventually.
	 */
	private final ExportFileService exportFileService;
	/**
	 * Supplier that provides the configuration.
	 */
	@Setter private Supplier<String> configurationSupplier;

	public EvitaManagement(@Nonnull Evita evita) {
		this.evita = evita;
		this.scheduler = evita.getServiceExecutor();
		this.exportFileService = new ExportFileService(evita.getConfiguration().storage(), this.scheduler);
		this.started = OffsetDateTime.now();
		this.configurationSupplier = evita.getConfiguration()::toString;
	}

	/**
	 * Registers a task to be kept in the waiting queue until it can be executed.
	 *
	 * @param task The task to be registered and added to the waiting queue.
	 */
	public void registerWaitingTask(@Nonnull ServerTask<?, ?> task) {
		this.scheduler.registerWaitingTask(task);
	}

	/**
	 * Retrieves a task from the waiting queue based on the provided registration identifier.
	 *
	 * @param taskPredicate predicate to filter the task
	 * @return An {@link Optional} containing the {@link ServerTask} if found, otherwise an empty {@link Optional}.
	 */
	public Optional<ServerTask<?, ?>> getWaitingTask(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		return this.scheduler.findTask(taskPredicate);
	}

	/**
	 * Submits a task from the waiting queue based on the provided registration identifier.
	 *
	 * @param taskPredicate predicate to filter the task
	 */
	public void submitWaitingTask(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		this.scheduler.submitWaitingTask(taskPredicate);
	}

	/**
	 * Returns the initialized export file service.
	 * @return the export file service
	 */
	@Nonnull
	public ExportFileService exportFileService() {
		return this.exportFileService;
	}

	@Nonnull
	@Override
	public CatalogStatistics[] getCatalogStatistics() {
		return this.evita.getCatalogs()
			.stream()
			.map(CatalogContract::getStatistics)
			.sorted(Comparator.comparing(CatalogStatistics::catalogName))
			.toArray(CatalogStatistics[]::new);
	}

	@Nonnull
	@Override
	public CompletableFuture<FileForFetch> backupCatalog(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		this.evita.assertActiveAndWritable();
		// we need writable session for backup
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(catalogName)) {
			return session.backupCatalog(pastMoment, catalogVersion, includingWAL).getFutureResult();
		}
	}

	@Nonnull
	@Override
	public CompletableFuture<FileForFetch> fullBackupCatalog(@Nonnull String catalogName) {
		this.evita.assertActiveAndWritable();
		// we need writable session for backup
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(catalogName)) {
			return session.fullBackupCatalog().getFutureResult();
		}
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(
		@Nonnull String catalogName,
		long totalBytesExpected,
		@Nonnull InputStream inputStream
	) throws UnexpectedIOException {
		this.evita.assertActiveAndWritable();
		// if the file is not a locally stored export file, store it to the export directory first
		final UUID fileId = UUIDUtil.randomUUID();
		final Path tempFile = this.exportFileService.createTempFile(fileId + ".zip");
		try {
			final long bytesCopied = Files.copy(
				inputStream, tempFile,
				StandardCopyOption.REPLACE_EXISTING
			);
			Assert.isPremiseValid(
				bytesCopied == totalBytesExpected,
				"Unexpected number of bytes copied (" + bytesCopied + "B instead of " + totalBytesExpected + "B)!"
			);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Unexpected exception occurred while storing catalog file for restoration: " + e.getMessage(),
				"Unexpected exception occurred while storing catalog file for restoration!",
				e
			);
		}
		final SequentialTask<Void> task = createRestorationTask(catalogName, fileId, tempFile, totalBytesExpected, true);
		this.scheduler.submit(task);
		return task;
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(@Nonnull String catalogName, @Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evita.assertActiveAndWritable();
		final FileForFetch file = this.exportFileService.getFile(fileId)
			.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
		final SequentialTask<Void> task = createRestorationTask(
			catalogName, file.fileId(), this.exportFileService.getPathOf(file),
			file.totalSizeInBytes(), false
		);
		this.scheduler.submit(task);
		return task;
	}

	/**
	 * Creates a restoration task for a catalog, which consists of multiple sequential steps:
	 * restoring the catalog from a backup and loading the catalog. This method does not submit the task to the executor.
	 *
	 * @param catalogName          The name of the catalog to be restored.
	 * @param fileId			   The ID of the file to be restored.
	 * @param pathToFile		   The path to the ZIP file containing the backup.
	 * @param totalBytesExpected total bytes expected to be read from the input stream
	 * @param deleteAfterRestore whether to delete the ZIP file after restore
	 * @return A {@link SequentialTask} that represents the restoration task for the specified catalog.
	 */
	@Nonnull
	public SequentialTask<Void> createRestorationTask(
		@Nonnull String catalogName,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalBytesExpected,
		boolean deleteAfterRestore
	) {
		return new SequentialTask<>(
			catalogName,
			"Restore catalog " + catalogName + " from backup.",
			Catalog.createRestoreCatalogTask(
				catalogName, this.evita.getConfiguration().storage(),
				fileId, pathToFile, totalBytesExpected, deleteAfterRestore
			),
			new ClientRunnableTask<>(
				catalogName,
				"registerInactiveCatalog",
				"Registering restored catalog " + catalogName + ".",
				Void.class,
				session -> this.evita.registerRestoredCatalog(catalogName)
			)
		);
	}

	/**
	 * Returns the task statuses of the given task type.
	 * @param taskType the type of the task
	 * @return the list of task statuses
	 * @param <T> the type of the task
	 */
	@Nonnull
	public <T extends ServerTask<?, ?>> Collection<T> getTaskStatuses(@Nonnull Class<T> taskType) {
		this.evita.assertActive();
		return this.scheduler.getTasks(taskType);
	}

	@Nonnull
	@Override
	public PaginatedList<TaskStatus<?, ?>> listTaskStatuses(
		int page,
		int pageSize,
		@Nullable String[] taskType,
		@Nonnull TaskSimplifiedState... states
	) {
		this.evita.assertActive();
		return this.scheduler.listTaskStatuses(page, pageSize, taskType, states);
	}

	@Nonnull
	@Override
	public Optional<TaskStatus<?, ?>> getTaskStatus(@Nonnull UUID jobId) {
		this.evita.assertActive();
		return this.scheduler.getTaskStatus(jobId);
	}

	@Nonnull
	@Override
	public Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId) {
		this.evita.assertActive();
		return this.scheduler.getTaskStatuses(jobId);
	}

	@Override
	public boolean cancelTask(@Nonnull UUID jobId) {
		this.evita.assertActiveAndWritable();
		return this.scheduler.cancelTask(jobId);
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
		this.evita.assertActive();
		return this.exportFileService.listFilesToFetch(page, pageSize, origin);
	}

	@Nonnull
	@Override
	public Optional<FileForFetch> getFileToFetch(@Nonnull UUID fileId) {
		return this.exportFileService.getFile(fileId);
	}

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException, UnexpectedIOException {
		this.evita.assertActive();
		return this.exportFileService.fetchFile(fileId);
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evita.assertActiveAndWritable();
		this.exportFileService.deleteFile(fileId);
	}

	@Nonnull
	@Override
	public SystemStatus getSystemStatus() {
		final Collection<CatalogContract> catalogs = this.evita.getCatalogs();
		int corruptedCatalogs = 0;
		int inactiveCatalogs = 0;
		for (CatalogContract catalog : catalogs) {
			switch (catalog.getCatalogState()) {
				case CORRUPTED -> corruptedCatalogs++;
				case INACTIVE -> inactiveCatalogs++;
			}
		}

		final EngineState engineState = this.evita.getEngineState().engineState();

		return new SystemStatus(
			VersionUtils.readVersion(),
			this.started,
			engineState.version(),
			engineState.introducedAt(),
			Duration.between(this.started, OffsetDateTime.now()),
			this.evita.getConfiguration().name(),
			corruptedCatalogs,
			catalogs.size() - corruptedCatalogs,
			inactiveCatalogs
		);
	}

	@Nonnull
	@Override
	public String getConfiguration() {
		this.evita.assertActiveAndWritable();
		return this.configurationSupplier.get();
	}

	@Override
	public void close() {
		this.exportFileService.close();
	}

}
