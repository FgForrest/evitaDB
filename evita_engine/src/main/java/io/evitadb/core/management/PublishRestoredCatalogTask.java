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

package io.evitadb.core.management;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.SequentialTask;
import io.evitadb.core.management.RestorationSteps.RestorationStepsFactory;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a freshly created backup archive into the catalog served under a given name.
 *
 * The second and final step of the restore-to-version sequence: an earlier step produced an archive of the catalog
 * as it was at some past version, and this one unpacks it into a temporary catalog, loads that catalog, and then
 * swaps it into the target name. Everything it does is a call into an operation that already exists on its own -
 * the value here is the ordering and what happens when one of them fails.
 *
 * **Why this is not simply more steps of the enclosing sequence.** The archive is produced by the step before it,
 * and the unpacking step has to be told the archive's id, its location and its size at construction time. Those are
 * chosen by {@link ExportService} while the backup runs, so no amount of rearranging lets the unpacking step be
 * built when the sequence is assembled. It is built here instead, at the moment its inputs exist.
 *
 * **The swap is the only client-visible moment.** Unpacking and loading happen against a temporary catalog nobody
 * is querying, while the catalog being replaced keeps serving reads and writes; only the final
 * {@link Evita#replaceCatalogWithProgress} makes the restored data the answer clients get. Writes committed to the
 * replaced catalog in the meantime are discarded with it - see
 * {@link io.evitadb.api.EvitaManagementContract#restoreCatalogToVersion}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
class PublishRestoredCatalogTask extends ClientRunnableTask<PublishRestoredCatalogTask.PublishSettings> {
	/**
	 * Progress reported once the archive has been copied out of the export service and is ready to unpack.
	 */
	private static final int PROGRESS_ARCHIVE_FETCHED = 10;
	/**
	 * Progress reported once the archive has been unpacked and the temporary catalog registered.
	 */
	private static final int PROGRESS_UNPACKED = 55;
	/**
	 * Progress reported once the temporary catalog has been loaded and is ready to be swapped in.
	 */
	private static final int PROGRESS_ACTIVATED = 90;

	/**
	 * The engine the catalogs live in, used to activate the temporary catalog and to swap it into the target name.
	 */
	private final Evita evita;
	/**
	 * Service holding the archive the preceding backup step produced. The archive is read back through it rather
	 * than from a path of our own choosing, because an export service is not necessarily a local file system - the
	 * S3-backed implementation has no local path at all.
	 */
	private final ExportService exportService;
	/**
	 * Allocates the work-directory file the archive is copied into before it is unpacked.
	 */
	private final FileManagementService fileManagementService;
	/**
	 * Builds the unpack-and-register steps once the archive exists.
	 */
	private final RestorationStepsFactory restorationStepsFactory;
	/**
	 * The backup step of the enclosing sequence, consulted for the archive it produced.
	 */
	private final ServerTask<?, FileForFetch> backupTask;

	PublishRestoredCatalogTask(
		@Nonnull String catalogName,
		@Nonnull String temporaryCatalogName,
		@Nonnull String targetCatalogName,
		@Nonnull Evita evita,
		@Nonnull ExportService exportService,
		@Nonnull FileManagementService fileManagementService,
		@Nonnull RestorationStepsFactory restorationStepsFactory,
		@Nonnull ServerTask<?, FileForFetch> backupTask
	) {
		super(
			catalogName,
			"publishRestoredCatalog",
			"Publishing restored catalog `" + targetCatalogName + "`.",
			new PublishSettings(temporaryCatalogName, targetCatalogName),
			task -> ((PublishRestoredCatalogTask) task).doPublish(),
			TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED
		);
		this.evita = evita;
		this.exportService = exportService;
		this.fileManagementService = fileManagementService;
		this.restorationStepsFactory = restorationStepsFactory;
		this.backupTask = backupTask;
	}

	/**
	 * Unpacks the archive into the temporary catalog, loads it and swaps it into the target name.
	 */
	private void doPublish() {
		final PublishSettings settings = getStatus().settings();
		final String temporaryCatalogName = settings.temporaryCatalogName();
		final String targetCatalogName = settings.targetCatalogName();
		final FileForFetch archive = Objects.requireNonNull(
			this.backupTask.getFutureResult().getNow(null),
			"The backup step completed without producing an archive!"
		);

		boolean published = false;
		try {
			// the archive is pulled through the export service rather than read from a path we compute ourselves:
			// the service may be backed by object storage, where no local path exists, and RestoreTask needs a
			// local file to unpack
			final Path localArchive = fetchArchiveLocally(archive);
			updateProgress(PROGRESS_ARCHIVE_FETCHED);

			abortIfCancelled();
			unpackInto(temporaryCatalogName, archive.fileId(), localArchive, archive.totalSizeInBytes());
			updateProgress(PROGRESS_UNPACKED);

			// loading is the expensive half of the operation - it reads the whole catalog back and rebuilds its
			// indexes - and it happens while the catalog being replaced is still serving
			abortIfCancelled();
			activate(temporaryCatalogName);
			updateProgress(PROGRESS_ACTIVATED);

			// past this point there is nothing to compensate: the swap either happens or it does not, and the
			// temporary catalog is cleaned up below in either case
			abortIfCancelled();
			this.evita.replaceCatalogWithProgress(temporaryCatalogName, targetCatalogName)
				.onCompletion()
				.toCompletableFuture()
				.join();
			published = true;
			updateProgress(100);
		} finally {
			cleanUp(published, archive.fileId(), temporaryCatalogName);
		}
	}

	/**
	 * Copies the archive out of the export service into the work directory, where it can be unpacked.
	 *
	 * @param archive descriptor of the archive the backup step produced
	 * @return path of the local copy
	 */
	@Nonnull
	private Path fetchArchiveLocally(@Nonnull FileForFetch archive) {
		final Path localArchive = this.fileManagementService.createTempFile(archive.fileId() + ".zip");
		try (final InputStream inputStream = this.exportService.fetchFile(archive.fileId())) {
			IOUtils.copy(inputStream, localArchive);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to read back the backup archive of catalog `" + getStatus().catalogName() +
					"`: " + e.getMessage(),
				"Failed to read back the backup archive of the catalog!",
				e
			);
		}
		return localArchive;
	}

	/**
	 * Unpacks the archive into a temporary catalog and registers it, inactive.
	 *
	 * Runs the same two steps a plain restore runs, inline on this task's own thread. They are wrapped in their
	 * own {@link SequentialTask} rather than executed one after the other, because that is what attaches the
	 * folder-claim release to a future that completes on every outcome - including the unpacking failing and the
	 * registering step never running at all.
	 *
	 * @param temporaryCatalogName name the archive is unpacked under
	 * @param fileId               id of the archive
	 * @param localArchive         local copy of the archive
	 * @param totalSizeInBytes     size of the archive, used to report unpacking progress
	 */
	private void unpackInto(
		@Nonnull String temporaryCatalogName,
		@Nonnull UUID fileId,
		@Nonnull Path localArchive,
		long totalSizeInBytes
	) {
		final RestorationSteps steps = this.restorationStepsFactory.create(
			temporaryCatalogName, fileId, localArchive, totalSizeInBytes, true
		);
		final SequentialTask<Void> restoration = new SequentialTask<>(
			temporaryCatalogName,
			"Restoring catalog `" + temporaryCatalogName + "` from the backup archive.",
			steps.unpackStep(),
			steps.registerStep()
		);
		restoration.getFutureResult().whenComplete((result, ex) -> steps.releaseClaim());
		// a task only runs while its status is QUEUED, and it is the scheduler that normally puts it there. This
		// sequence is never submitted - it runs inline on this task's thread - so it has to be issued by hand
		restoration.transitionToIssued();
		restoration.execute();
		// `execute` swallows nothing, but a sequence that was cancelled returns null rather than throwing - reading
		// the future is what turns that into the CancellationException this task must unwind with
		restoration.getFutureResult().join();
	}

	/**
	 * Loads the temporary catalog, forwarding the load progress into this task's own band.
	 *
	 * @param temporaryCatalogName name of the catalog to load
	 */
	private void activate(@Nonnull String temporaryCatalogName) {
		final int band = PROGRESS_ACTIVATED - PROGRESS_UNPACKED;
		final Progress<Void> activation = this.evita.activateCatalogWithProgress(temporaryCatalogName);
		activation.addProgressListener(
			percent -> updateProgress(PROGRESS_UNPACKED + (percent * band) / 100)
		);
		activation.onCompletion().toCompletableFuture().join();
	}

	/**
	 * Raises {@link CancellationException} when this task has been cancelled.
	 *
	 * Checked explicitly between phases because the phases themselves are not uniformly interruptible:
	 * {@link CompletableFuture#join()} ignores interrupts, so a cancellation landing inside an engine mutation
	 * is not observed until that mutation returns. Stopping at the next phase boundary is the guarantee this
	 * task gives - never stopping mid-mutation.
	 */
	private void abortIfCancelled() {
		if (getFutureResult().isCancelled() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException(
				"Publishing of the restored catalog was cancelled before it could be completed."
			);
		}
	}

	/**
	 * Removes what this task must not leave behind.
	 *
	 * On success the archive is an implementation detail nobody asked for, so it goes; on failure it is kept, since
	 * it is a complete backup of the requested version and the operator's cheapest way to retry by hand. The
	 * temporary catalog is the mirror image: it is dropped whenever the swap did not happen, and after a swap it
	 * no longer exists under that name at all.
	 *
	 * Neither removal may mask the failure that brought us here, so both are logged rather than thrown.
	 *
	 * @param published            whether the swap completed
	 * @param archiveFileId        id of the intermediate archive
	 * @param temporaryCatalogName name of the temporary catalog
	 */
	private void cleanUp(
		boolean published,
		@Nonnull UUID archiveFileId,
		@Nonnull String temporaryCatalogName
	) {
		if (published) {
			try {
				this.exportService.deleteFile(archiveFileId);
			} catch (RuntimeException e) {
				log.warn(
					"Failed to remove the intermediate backup archive `{}` of catalog `{}` - it stays available " +
						"for download and has to be removed manually.",
					archiveFileId, getStatus().catalogName(), e
				);
			}
		} else {
			try {
				this.evita.deleteCatalogIfExistsWithProgress(temporaryCatalogName)
					.ifPresent(progress -> progress.onCompletion().toCompletableFuture().join());
			} catch (RuntimeException e) {
				log.warn(
					"Failed to remove the temporary catalog `{}` left behind by a restore that did not complete - " +
						"it has to be removed manually.",
					temporaryCatalogName, e
				);
			}
		}
	}

	/**
	 * Settings of this task, shown to clients as part of its status.
	 *
	 * @param temporaryCatalogName name the archive is unpacked under before the swap
	 * @param targetCatalogName    name the restored catalog ends up being served under
	 */
	record PublishSettings(
		@Nonnull String temporaryCatalogName,
		@Nonnull String targetCatalogName
	) implements Serializable {

		@Nonnull
		@Override
		public String toString() {
			return "targetCatalogName: `" + this.targetCatalogName + '`' +
				", temporaryCatalogName: `" + this.temporaryCatalogName + '`';
		}
	}

}
