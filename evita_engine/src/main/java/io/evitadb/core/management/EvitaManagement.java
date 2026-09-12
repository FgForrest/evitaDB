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
import io.evitadb.api.exception.IndexNotFoundException;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.system.EngineSettings;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogConsumerControl;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.exception.ExportServiceImplementationNotFoundException;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.executor.SequentialTask;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.ExportServiceFactory;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
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
import java.util.concurrent.CancellationException;
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
	 * Task type reported for the composed backup-restore-swap operation. Stated once and never derived from the
	 * steps it happens to be built from, because clients filter their task listings by it.
	 */
	public static final String RESTORE_TO_VERSION_TASK_TYPE = "RestoreCatalogToVersionTask";
	/**
	 * Infix marking a catalog as the scratch copy a restore-to-version unpacks into before it swaps it in.
	 *
	 * Deliberately a word rather than a bare random suffix. A crash between the temporary catalog being registered
	 * and the swap committing leaves it behind as an ordinary, fully-registered catalog that nothing sweeps - so
	 * the one thing that makes it recoverable is that an operator scanning the catalog listing can tell what it is
	 * and where it came from. It carries no meaning to the engine.
	 */
	private static final String TEMPORARY_NAME_INFIX = "_restore_";
	/**
	 * Longest prefix of a source catalog name that may be carried into the temporary catalog's name. The classifier
	 * format admits 255 characters and the rest of the name costs seventeen of them, so a source name longer than
	 * this is truncated rather than allowed to produce an unvalidatable name.
	 */
	private static final int MAX_TEMPORARY_NAME_PREFIX_LENGTH = 238;
	/**
	 * How many suffixes are tried before inventing a temporary catalog name is given up on. A collision needs both a
	 * matching random suffix and a matching prefix, so more than one attempt is already close to unreachable.
	 */
	private static final int TEMPORARY_NAME_ATTEMPTS = 100;
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

	@Nonnull
	@Override
	public Task<?, Void> restoreCatalogToVersion(
		@Nonnull String catalogName,
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		@Nullable String targetCatalogName
	) throws TemporalDataNotAvailableException, CatalogNotFoundException, EvitaInvalidUsageException {
		this.evita.assertActiveAndWritable();

		final CatalogContract sourceCatalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		// a placeholder has no persistence service and therefore no history to go back to - and it would fail deep
		// inside the backup task rather than here, where the client can still be told something useful
		Assert.isTrue(
			sourceCatalog instanceof Catalog,
			() -> new EvitaInvalidUsageException(
				"Catalog `" + catalogName + "` cannot be restored to an earlier version - it is not in a usable state."
			)
		);
		final String theTargetCatalogName = targetCatalogName == null ? catalogName : targetCatalogName;
		// The swap runs with `overwriteTarget`, which deliberately skips every check on the target name - it has to,
		// because overwriting an existing catalog is the whole point. That leaves a *new* target name unvalidated,
		// so it is validated here instead: a malformed one would otherwise only surface after the archive had been
		// written and unpacked, and a name colliding in some naming convention would not surface at all.
		ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, theTargetCatalogName);
		if (!this.evita.getCatalogNames().contains(theTargetCatalogName)) {
			CatalogSchema.checkCatalogNameIsAvailable(this.evita, theTargetCatalogName);
		}

		final String temporaryCatalogName = generateTemporaryCatalogName(catalogName);
		final CatalogConsumerControl consumerControl = this.evita.obtainCatalogSessionRegistry(catalogName)
			.map(registry -> registry.createCatalogConsumerControl(catalogName))
			.orElseThrow(() -> new CatalogNotFoundException(catalogName));
		// Built rather than submitted, so the archive is produced as the first step of the sequence below. The
		// construction is what resolves the requested version, so an unavailable one is raised *here*, synchronously,
		// instead of failing a task the client has already been handed.
		//
		// The WAL is deliberately excluded. Including it copies every log file wholesale, and a restore of such an
		// archive replays them forward to the head of the log - which lands on the current state and undoes the very
		// point-in-time this operation exists to reach.
		final ServerTask<?, FileForFetch> backupTask = ((Catalog) sourceCatalog).createBackupTask(
			// the backup holds the version it copies against reclamation, but it is not a session at that version -
			// registering it as one would make it a phantom read-write consumer of that version for the whole copy
			pastMoment, catalogVersion, false, consumerControl::pinCatalogVersion
		);
		try {
			final SequentialTask<Void> task = new SequentialTask<>(
				catalogName,
				RESTORE_TO_VERSION_TASK_TYPE,
				"Restoring catalog `" + theTargetCatalogName + "` from catalog `" + catalogName + "` " +
					(catalogVersion == null ?
						(pastMoment == null ? "at its current state" : "as of " + pastMoment) :
						"at version " + catalogVersion) + ".",
				backupTask,
				new PublishRestoredCatalogTask(
					catalogName, temporaryCatalogName, theTargetCatalogName,
					this.evita, this.exportService, this.fileManagementService,
					this::createRestorationSteps, backupTask
				)
			);
			this.scheduler.submit(task);
			return task;
		} catch (RuntimeException ex) {
			// the backup task pinned the version it is going to read the moment it was constructed, and only running
			// it or cancelling it gives that pin back. A sequence that never reached the queue will never run it, so
			// without this the catalog's retention floor stays frozen at that version for the rest of its life
			backupTask.cancel();
			throw ex;
		}
	}

	/**
	 * Invents a name for the catalog a restore unpacks into before it is swapped into its final name.
	 *
	 * The name has one hard requirement - nothing else may be using it, in any naming convention - and one that
	 * matters only when something goes wrong: it must say what it is. A crash between the temporary catalog being
	 * registered and the swap committing leaves it behind as an ordinary catalog nothing sweeps, so
	 * `<source>_restore_<hex>` is what lets an operator recognise the leftover and delete it. The prefix is
	 * truncated rather than appended to blindly, because a source catalog already near the classifier length limit
	 * would otherwise produce a name {@link ClassifierUtils#validateClassifierFormat} rejects.
	 *
	 * @param catalogName name of the catalog being restored
	 * @return a name no catalog currently holds, in any naming convention
	 */
	@Nonnull
	private String generateTemporaryCatalogName(@Nonnull String catalogName) {
		final String prefix = catalogName.length() > MAX_TEMPORARY_NAME_PREFIX_LENGTH ?
			catalogName.substring(0, MAX_TEMPORARY_NAME_PREFIX_LENGTH) : catalogName;
		for (int attempt = 0; attempt < TEMPORARY_NAME_ATTEMPTS; attempt++) {
			final String candidate = prefix + TEMPORARY_NAME_INFIX +
				UUIDUtil.randomUUID().toString().substring(0, 8);
			if (this.evita.getCatalogNames().contains(candidate)) {
				continue;
			}
			try {
				// not merely "no catalog is called this": two names that differ only in convention collide at
				// registration time, and that failure would land halfway through the restore rather than here
				CatalogSchema.checkCatalogNameIsAvailable(this.evita, candidate);
				return candidate;
			} catch (EvitaInvalidUsageException ignored) {
				// try another suffix
			}
		}
		throw new GenericEvitaInternalError(
			"Failed to invent a free temporary catalog name for catalog `" + catalogName + "` in " +
				TEMPORARY_NAME_ATTEMPTS + " attempts!",
			"Failed to invent a free temporary catalog name for the restored catalog!"
		);
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
		final RestorationSteps steps = createRestorationSteps(
			catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore
		);
		return steps.asSequentialTask(catalogName, "Restore catalog " + catalogName + " from backup.");
	}

	/**
	 * Builds the pair of steps that unpack a backup archive into a freshly allocated folder and then bind
	 * a catalog of the given name to it, together with the folder claim that spans them.
	 *
	 * Handed out as steps rather than as a finished task because the same pair is used by two different
	 * sequences - a plain restore, and the restore-to-version operation that wraps four more steps around it. Each
	 * of them turns the pair into its own {@link SequentialTask} through {@link RestorationSteps#asSequentialTask}
	 * rather than assembling one by hand, so the {@link RestorationSteps#releaseClaim()} wiring spelled out on
	 * {@link RestorationSteps} is guaranteed rather than a caller obligation.
	 *
	 * @param catalogName        name of the catalog to restore into
	 * @param fileId             id of the archive being restored
	 * @param pathToFile         path to the ZIP archive
	 * @param totalBytesExpected total bytes expected to be read from the archive
	 * @param deleteAfterRestore whether to delete the archive once it has been unpacked
	 * @return the two steps and the claim spanning them
	 */
	@Nonnull
	RestorationSteps createRestorationSteps(
		@Nonnull String catalogName,
		@Nonnull UUID fileId,
		@Nonnull Path pathToFile,
		long totalBytesExpected,
		boolean deleteAfterRestore
	) {
		// The name is client-supplied and reaches folder allocation before any mutation validates it -
		// `RestoreCatalogSchemaMutation` runs its check at *registration*, which is after a folder has been
		// created and the whole archive written into it. Checking here makes a malformed name a client error
		// rather than an internal one, and keeps it from ever reaching a path join.
		ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, catalogName);
		// Nothing is materialised here, only promised. A chunked upload creates its task on the first chunk and
		// submits it once the last one arrives, so allocating now would leave every abandoned upload holding a
		// directory, a consumed generation and an exclusive claim on the name. The claim lands when the
		// restore actually starts, and `claim` is what carries it from there to whichever of the registering
		// step and the release hook gets to it first.
		final RestoreFolderClaim claim = new RestoreFolderClaim();
		return new RestorationSteps(
			Catalog.createRestoreCatalogTask(
				catalogName,
				() -> claim.allocate(this.evita.getCatalogFolderContext(), catalogName),
				this.evita.getConfiguration().storage(),
				fileId, pathToFile, totalBytesExpected, deleteAfterRestore
			),
			new ClientRunnableTask<>(
				catalogName,
				"registerInactiveCatalog",
				"Registering restored catalog " + catalogName + ".",
				Void.class,
				session -> registerRestoredCatalogHoldingItsFolder(catalogName, claim)
			),
			claim
		);
	}

	/**
	 * Registers a restored catalog while holding its folder claim, so that nothing can take the name in between.
	 *
	 * The registering mutation resolves the restored folder **by catalog name**, so the claim has to still be
	 * held while it runs — a second restore that took the name in that window would make this one bind its
	 * catalog to the other one's half-written folder. Taking the claim out of the holder is what guarantees that:
	 * the completion hook can no longer release it underneath this call.
	 *
	 * Finding no claim is not an error in the code, it means the task was cancelled or failed and the hook has
	 * already given the name back. Registering anyway would publish a catalog the client was told it would not
	 * get, so this refuses instead.
	 *
	 * @param catalogName name of the catalog being registered
	 * @param claim       holder carrying the claim taken when the restore started
	 */
	private void registerRestoredCatalogHoldingItsFolder(
		@Nonnull String catalogName,
		@Nonnull RestoreFolderClaim claim
	) {
		final CatalogFolderReservation heldClaim = claim.takeClaim();
		if (heldClaim == null) {
			throw new CancellationException(
				"Restore of catalog `" + catalogName + "` ended before it could be registered - its folder claim " +
					"was already given back."
			);
		}
		try (heldClaim) {
			this.evita.registerRestoredCatalog(catalogName);
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
		CatalogStatisticsComponent.assertNotEmpty(components);
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

	@Nonnull
	@Override
	public IndexBrowseResult browseIndexes(
		@Nonnull String catalogName,
		@Nullable String entityType,
		@Nonnull IndexBrowseCriteria criteria
	) throws CatalogNotFoundException, CollectionNotFoundException, EvitaInvalidUsageException {
		// the owner is the only thing the entity type selects - both branches answer with the same rows, under the same
		// criteria, so the dispatch ends here rather than propagating into two parallel surfaces
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		return entityType == null ?
			catalog.browseIndexes(criteria) :
			catalog.getCollectionForEntityOrThrowException(entityType).browseIndexes(criteria);
	}

	@Nonnull
	@Override
	public IndexDetail getIndexDetail(
		@Nonnull String catalogName,
		@Nullable String entityType,
		int indexPrimaryKey
	) throws CatalogNotFoundException, CollectionNotFoundException, IndexNotFoundException {
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		return entityType == null ?
			catalog.describeIndex(indexPrimaryKey) :
			catalog.getCollectionForEntityOrThrowException(entityType).describeIndex(indexPrimaryKey);
	}

	@Nonnull
	@Override
	public List<SchemaCapabilityUsageStatistics> listCapabilityUsage(
		@Nonnull String catalogName,
		@Nullable String entityType
	) throws CatalogNotFoundException, CollectionNotFoundException {
		// the same dispatch `browseIndexes` makes, for the same reason: the entity type selects the owner and nothing
		// else, and both branches answer with the same rows
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		return entityType == null ?
			catalog.listCapabilityUsage() :
			catalog.getCollectionForEntityOrThrowException(entityType).listCapabilityUsage();
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
