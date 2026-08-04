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

package io.evitadb.core.management;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.DefaultExportOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.ExportServiceImplementationNotFoundException;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.executor.SequentialTask;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.ExportServiceFactory;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.Assert;
import io.evitadb.utils.Functions;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Main implementation of {@link EvitaManagementContract}.
 *
 * @see EvitaManagementContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
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
	private final ExportService exportService;
	/**
	 * File management utility.
	 */
	private final FileManagementService fileManagementService;
	/**
	 * Supplier that provides the configuration.
	 */
	@Setter private Supplier<String> configurationSupplier;

	public EvitaManagement(@Nonnull Evita evita) {
		this.evita = evita;
		this.scheduler = evita.getServiceExecutor();
		this.fileManagementService = new FileManagementService(evita.getConfiguration().storage());

		final ExportOptions exportOptions = evita.getConfiguration().export();
		final String implementationCode = exportOptions.getImplementationCode();

		final ServiceLoader<ExportServiceFactory> svcLoader = ServiceLoader.load(
			ExportServiceFactory.class
		);

		final Predicate<ExportServiceFactory> exportSelector;
		final Function<ExportServiceFactory, ExportOptions> exportOptionsProvider;
		if (DefaultExportOptions.INSTANCE.getImplementationCode().equals(implementationCode)) {
			// select the factory with highest priority
			exportSelector = Functions.alwaysTrue();
			exportOptionsProvider = ExportServiceFactory::createDefaultOptions;
		} else {
			// select by implementation code
			exportSelector = factory -> factory.getImplementationCode().equals(implementationCode);
			exportOptionsProvider = factory -> exportOptions;
		}

		// Match factory by implementation code from the export options
		this.exportService = svcLoader.stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(ExportServiceFactory::getPriority).reversed())
			.filter(exportSelector)
			.findFirst()
			.map(factory -> factory.create(exportOptionsProvider.apply(factory), this.scheduler, this.fileManagementService))
			.orElseThrow(() -> new ExportServiceImplementationNotFoundException(implementationCode));

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
	public ExportService exportService() {
		return this.exportService;
	}

	/**
	 * Returns the initialized internal file management service.
	 * @return the file management service
	 */
	@Nonnull
	public FileManagementService fileManagementService() {
		return this.fileManagementService;
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
		final Path tempFile = this.fileManagementService.createTempFile(fileId + ".zip");
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
		try (final InputStream inputStream = this.exportService.fetchFile(fileId)) {
			final Path managedTempFile = this.fileManagementService.createTempFile(fileId + ".zip");
			IOUtils.copy(inputStream, managedTempFile);
			final SequentialTask<Void> task = createRestorationTask(
				catalogName, fileId, managedTempFile,
				managedTempFile.toFile().length(),
				true
			);
			this.scheduler.submit(task);
			return task;
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Unexpected exception occurred while preparing catalog file for restoration: " + e.getMessage(),
				"Unexpected exception occurred while preparing catalog file for restoration!",
				e
			);
		}
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
		return this.exportService.listFilesToFetch(page, pageSize, origin);
	}

	@Nonnull
	@Override
	public Optional<FileForFetch> getFileToFetch(@Nonnull UUID fileId) {
		return this.exportService.getFile(fileId);
	}

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException, UnexpectedIOException {
		this.evita.assertActive();
		return this.exportService.fetchFile(fileId);
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		this.evita.assertActiveAndWritable();
		this.exportService.deleteFile(fileId);
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

		final EngineState<?> engineState = this.evita.getEngineState().engineState();

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

	@Nonnull
	@Override
	public EngineSettings getEngineSettings() {
		// deliberately not `assertActiveAndWritable` - the exposed values carry nothing sensitive
		// and clients need them to interpret the server's behaviour also when the engine was
		// booted in read-only mode
		final EvitaConfiguration configuration = this.evita.getConfiguration();
		return new EngineSettings(
			configuration.transaction().conflictPolicy(),
			configuration.storage().timeTravelEnabled(),
			configuration.server().changeDataCapture().enabled(),
			configuration.server().trafficRecording().enabled(),
			configuration.cache().enabled()
		);
	}

	@Nonnull
	@Override
	public CatalogStatistics getCatalogStatistics(
		@Nonnull String catalogName,
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws CatalogNotFoundException, EvitaInvalidUsageException {
		return this.evita.getCatalogInstanceOrThrowException(catalogName).getStatistics(components);
	}

	@Nonnull
	@Override
	public Collection<CatalogStatistics> getAllCatalogStatistics(
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws EvitaInvalidUsageException {
		// validated once, up front, because the per-catalog isolation below deliberately swallows every runtime
		// exception - without this, a malformed request would come back as "every catalog is unusable" instead of as
		// the caller's error it is
		CatalogStatisticsComponent.assertCatalogLevel(components);
		final Collection<CatalogContract> catalogs = this.evita.getCatalogs();
		final List<CatalogStatistics> statistics = new ArrayList<>(catalogs.size());
		for (final CatalogContract catalog : catalogs) {
			statistics.add(getCatalogStatisticsSafely(catalog, components));
		}
		// ordered by catalog name so that a client rendering a list does not see it reshuffle between two polls
		statistics.sort(Comparator.comparing(it -> it.identity().catalogName()));
		return statistics;
	}

	@Nonnull
	@Override
	public EntityCollectionStatistics getEntityCollectionStatistics(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Set<CatalogStatisticsComponent> components
	) throws CatalogNotFoundException, CollectionNotFoundException, EvitaInvalidUsageException {
		return this.evita.getCatalogInstanceOrThrowException(catalogName)
			.getCollectionForEntityOrThrowException(entityType)
			.getStatistics(components);
	}

	@Override
	public void close() {
		IOUtils.closeQuietly(
			this.exportService::close,
			this.fileManagementService::close
		);
	}

	/**
	 * Computes one catalog's statistics without letting a failure take the whole instance-wide answer down.
	 *
	 * A corrupted catalog needs no protection here - it answers for itself through `UnusableCatalog`, reporting
	 * {@link ComponentAvailability#CATALOG_UNUSABLE} per component. What this guards is the narrower race in which a
	 * catalog is being deactivated or replaced *while the loop walks it*: it was in the collection a moment ago and
	 * throws by the time it is asked. Reporting that catalog as unusable, with the exception named in the reason, is
	 * strictly better than failing a call that describes every other catalog correctly.
	 *
	 * There is deliberately no {@link ComponentAvailability} value meaning "the call blew up" - adding one would
	 * spend a permanent wire number on a case that may never occur in practice, and `CATALOG_UNUSABLE` is already
	 * true of a catalog that cannot answer.
	 *
	 * @param catalog    the catalog to describe
	 * @param components the components the caller asked for
	 * @return the catalog's snapshot, or an unusable-catalog snapshot carrying the failure reason
	 */
	@Nonnull
	private static CatalogStatistics getCatalogStatisticsSafely(
		@Nonnull CatalogContract catalog,
		@Nonnull Set<CatalogStatisticsComponent> components
	) {
		try {
			return catalog.getStatistics(components);
		} catch (RuntimeException ex) {
			log.error("Failed to compute statistics of catalog `" + catalog.getName() + "`!", ex);
			final CatalogStatistics.Builder builder = CatalogStatistics.builder(
				new CatalogIdentity(
					null, catalog.getName(), null, -1L, false, true, false, false, -1
				)
			);
			for (final CatalogStatisticsComponent component : components) {
				if (component != CatalogStatisticsComponent.IDENTITY) {
					builder.withUnavailable(
						component,
						ComponentAvailability.CATALOG_UNUSABLE,
						"Statistics of catalog `" + catalog.getName() + "` could not be computed: " + ex.getMessage()
					);
				}
			}
			return builder.build();
		}
	}

}
