/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.SessionTraits;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.async.SequentialTask;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.utils.VersionUtils;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Main implementation of {@link EvitaManagementContract}.
 *
 * @see EvitaManagementContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EvitaManagement implements EvitaManagementContract {
	/**
	 * Contains reference to the main evita service.
	 */
	private final Evita evita;
	/**
	 * Contains reference to Evita service executor / scheduler.
	 */
	private final Scheduler serviceExecutor;
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
		this.serviceExecutor = evita.getServiceExecutor();
		this.exportFileService = new ExportFileService(evita.getConfiguration().storage());
		this.started = OffsetDateTime.now();
		this.configurationSupplier = evita.getConfiguration()::toString;
	}

	/**
	 * Returns the initialized export file service.
	 * @return the export file service
	 */
	@Nonnull
	public ExportFileService exportFileService() {
		return exportFileService;
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
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		this.evita.assertActive();
		try (final EvitaSessionContract session = this.evita.createSession(new SessionTraits(catalogName))) {
			return session.backupCatalog(pastMoment, includingWAL).getFutureResult();
		}
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(
		@Nonnull String catalogName,
		long totalBytesExpected,
		@Nonnull InputStream inputStream
	) throws UnexpectedIOException {
		this.evita.assertActive();
		final SequentialTask<Void> task = new SequentialTask<>(
			catalogName,
			"Restore catalog " + catalogName + " from backup.",
			Catalog.createRestoreCatalogTask(
				catalogName, this.evita.getConfiguration().storage(), totalBytesExpected, inputStream
			),
			this.evita.createLoadCatalogTask(catalogName)
		);
		this.serviceExecutor.submit(task);
		return task;
	}

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalog(@Nonnull String catalogName, @Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evita.assertActive();
		final FileForFetch file = this.exportFileService.getFile(fileId)
			.orElseThrow(() -> new FileForFetchNotFoundException(fileId));
		try {
			final SequentialTask<Void> task = new SequentialTask<>(
				catalogName,
				"Restore catalog " + catalogName + " from backup.",
				Catalog.createRestoreCatalogTask(
					catalogName, this.evita.getConfiguration().storage(),
					file.totalSizeInBytes(),
					this.exportFileService.createInputStream(file)
				),
				this.evita.createLoadCatalogTask(catalogName)
			);
			this.serviceExecutor.submit(task);
			return task;
		} catch (IOException e) {
			throw new FileForFetchNotFoundException(fileId);
		}
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
		return this.serviceExecutor.getTasks(taskType);
	}

	@Nonnull
	@Override
	public PaginatedList<TaskStatus<?, ?>> listTaskStatuses(int page, int pageSize) {
		this.evita.assertActive();
		return this.serviceExecutor.listTaskStatuses(page, pageSize);
	}

	@Nonnull
	@Override
	public Optional<TaskStatus<?, ?>> getTaskStatus(@Nonnull UUID jobId) {
		this.evita.assertActive();
		return this.serviceExecutor.getTaskStatus(jobId);
	}

	@Nonnull
	@Override
	public Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId) {
		this.evita.assertActive();
		return this.serviceExecutor.getTaskStatuses(jobId);
	}

	@Override
	public boolean cancelTask(@Nonnull UUID jobId) {
		this.evita.assertActive();
		return this.serviceExecutor.cancelTask(jobId);
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nullable String origin) {
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
		this.evita.assertActive();
		this.exportFileService.deleteFile(fileId);
	}

	@Nonnull
	@Override
	public SystemStatus getSystemStatus() {
		final Collection<CatalogContract> catalogs = this.evita.getCatalogs();
		final int corruptedCatalogs = (int) catalogs
			.stream()
			.filter(it -> it instanceof CorruptedCatalog)
			.count();

		return new SystemStatus(
			VersionUtils.readVersion(),
			this.started,
			Duration.between(this.started, OffsetDateTime.now()),
			this.evita.getConfiguration().name(),
			corruptedCatalogs,
			catalogs.size() - corruptedCatalogs
		);
	}

	@Nonnull
	@Override
	public String getConfiguration() {
		return this.configurationSupplier.get();
	}
}
