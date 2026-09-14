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

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.Evita;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.core.executor.ClientCallableTask;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.management.RestorationSteps.RestorationStepsFactory;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.core.transaction.engine.EngineMutationPrecondition;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives the second step of the restore-to-version sequence directly, so the half of it that only runs when
 * something goes wrong can be asserted at all.
 *
 * The task's whole constructor is injected - the engine, the export service, the work directory and the
 * restoration steps all arrive as parameters - so every phase boundary is reachable from a single thread with no
 * engine boot behind it. That matters most for the outcomes an end-to-end test cannot place deterministically: a
 * cancellation landing at a *known* boundary, an archive that disappeared between the backup writing it and this
 * task reading it back, and a clean-up that itself fails while unwinding another failure.
 *
 * The cancellations here are driven from inside the export-service stub rather than from a second thread - the
 * same seam-from-inside-a-step technique `SequentialTaskTest` uses - so the cancel lands at the boundary the test
 * names instead of wherever the scheduler happened to be.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Publishing a restored catalog")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(TASK)
class PublishRestoredCatalogTaskTest {

	/**
	 * Name of the catalog whose past state is being restored - and, in every test here, also the name the restored
	 * state is published under, which is the ordinary in-place shape of the operation.
	 */
	private static final String SOURCE_CATALOG = "sourceCatalog";
	/**
	 * Name the archive is unpacked under before the swap.
	 */
	private static final String TEMPORARY_CATALOG = "sourceCatalog_restore_0a1b2c3d";
	/**
	 * Stand-in for the archive contents. Nothing here unpacks it - the unpacking step is a stub - so its only job
	 * is to be a non-empty byte sequence the copy has to move into the work directory.
	 */
	private static final byte[] ARCHIVE_CONTENT = "a backup archive".getBytes(StandardCharsets.UTF_8);

	private Path workDirectory;
	private Path storageDirectory;
	private FileManagementService fileManagementService;
	private Evita evita;
	private RecordingExportService exportService;
	private FileForFetch archive;
	/**
	 * Folder the engine currently binds {@link #TEMPORARY_CATALOG} to, as the clean-up sees it.
	 *
	 * The task removes its scratch catalog only while the name still denotes the folder it allocated, so a test
	 * that expects a removal has to publish that binding the way a real registering step would. Left empty, the
	 * name denotes nothing and nothing is removed.
	 */
	private AtomicReference<CatalogFolderId> scratchFolderBinding;

	/**
	 * Builds a progress that is already complete, the shape every engine mutation takes on the happy path here.
	 *
	 * @param <T> type of the mutation's result
	 * @return the completed progress
	 */
	@Nonnull
	private static <T> Progress<T> completedProgress() {
		return new StubProgress<>(CompletableFuture.completedFuture(null), listener -> {
		});
	}

	/**
	 * Builds a progress that has already failed with the given cause.
	 *
	 * @param cause the failure the mutation reports
	 * @param <T>   type of the mutation's result
	 * @return the failed progress
	 */
	@Nonnull
	private static <T> Progress<T> failedProgress(@Nonnull Throwable cause) {
		final CompletableFuture<T> completion = new CompletableFuture<>();
		completion.completeExceptionally(cause);
		return new StubProgress<>(completion, listener -> {
		});
	}

	/**
	 * Builds a completed progress that feeds the given percentages to whoever subscribes to it.
	 *
	 * The feed runs inside `addProgressListener` on the subscriber's own thread, which is what makes the
	 * percentages observable at an exact point of the task's execution rather than at some later moment.
	 *
	 * @param listenerFeed receives the subscribed listener and drives it
	 * @param <T>          type of the mutation's result
	 * @return the progress
	 */
	@Nonnull
	private static <T> Progress<T> progressFeeding(@Nonnull Consumer<IntConsumer> listenerFeed) {
		return new StubProgress<>(CompletableFuture.completedFuture(null), listenerFeed);
	}

	/**
	 * Builds a restoration step that does nothing but record that it ran.
	 *
	 * @param name      name of the step
	 * @param execution flipped when the step runs
	 * @return the step
	 */
	@Nonnull
	private static ClientRunnableTask<Void> recordingStep(@Nonnull String name, @Nonnull AtomicBoolean execution) {
		return new ClientRunnableTask<>(
			"restoreCatalog", name, null, () -> execution.set(true), TaskTrait.CAN_BE_CANCELLED
		);
	}

	/**
	 * Builds a restoration step that fails without having claimed anything.
	 *
	 * @param failure raised as soon as the step runs
	 * @return the step
	 */
	@Nonnull
	private static ClientRunnableTask<Void> failingStep(@Nonnull Supplier<RuntimeException> failure) {
		return new ClientRunnableTask<>(
			"restoreCatalog", "unpack", null,
			() -> {
				throw failure.get();
			},
			TaskTrait.CAN_BE_CANCELLED
		);
	}

	/**
	 * Builds a restoration step that takes the folder claim exactly as the real unpacking step does, and then
	 * optionally fails.
	 *
	 * @param claim         claim the step allocates into
	 * @param folderContext context the folder is allocated through
	 * @param catalogName   name the folder is allocated for
	 * @param failure       raised once the folder has been allocated, or `null` to let the step succeed
	 * @return the step
	 */
	@Nonnull
	private static ClientRunnableTask<Void> allocatingStep(
		@Nonnull RestoreFolderClaim claim,
		@Nonnull CatalogFolderContext folderContext,
		@Nonnull String catalogName,
		@Nonnull Supplier<RuntimeException> failure
	) {
		return new ClientRunnableTask<>(
			"restoreCatalog", "unpack", null,
			() -> {
				claim.allocate(folderContext, catalogName);
				final RuntimeException raised = failure.get();
				if (raised != null) {
					throw raised;
				}
			},
			TaskTrait.CAN_BE_CANCELLED
		);
	}

	@BeforeEach
	void setUp(@TempDir Path workDirectory, @TempDir Path storageDirectory) {
		this.workDirectory = workDirectory;
		this.storageDirectory = storageDirectory;
		this.fileManagementService = new FileManagementService(
			StorageOptions.builder()
				.storageDirectory(storageDirectory)
				.workDirectory(workDirectory)
				.build()
		);
		this.evita = mock(Evita.class);
		this.scratchFolderBinding = new AtomicReference<>();
		// answered rather than returned: an engine state is an immutable snapshot, and the binding it has to carry
		// changes while the task runs - the unpacking step publishes it - so each read builds the state as it is
		// at that moment
		when(this.evita.getEngineState()).thenAnswer(invocation -> currentEngineState());
		this.exportService = new RecordingExportService();
		this.archive = new FileForFetch(
			UUIDUtil.randomUUID(), "archive.zip", null, "application/zip",
			ARCHIVE_CONTENT.length, OffsetDateTime.now(), new String[]{"backupCatalog"}
		);
	}

	@AfterEach
	void tearDown() {
		this.fileManagementService.close();
	}

	/**
	 * Builds the engine state as the task would find it at this moment: binding {@link #TEMPORARY_CATALOG} to
	 * whatever the restoration steps have published for it, and nothing else to anything.
	 *
	 * A real state rather than a stubbed one, so the clean-up's ownership question is put to the engine's own
	 * binding lookup instead of to an answer this test wrote.
	 *
	 * @return state carrying the scratch name's current binding, if it has one
	 */
	@Nonnull
	private ExpandedEngineState currentEngineState() {
		final CatalogFolderId scratchFolder = this.scratchFolderBinding.get();
		return ExpandedEngineState.create(
			EngineState.<LogRecordReference>builder()
				.catalogFolders(
					scratchFolder == null ?
						EngineState.NO_FOLDER_BINDINGS :
						new CatalogFolderBinding[]{new CatalogFolderBinding(TEMPORARY_CATALOG, scratchFolder)}
				)
				.build(),
			Map.of()
		);
	}

	/**
	 * Builds the task under test over the given backup step and restoration steps.
	 *
	 * @param backupTask   the preceding step, consulted for the archive it produced
	 * @param stepsFactory builds the unpack-and-register steps
	 * @return the task, not yet issued
	 */
	@Nonnull
	private PublishRestoredCatalogTask taskWith(
		@Nonnull ServerTask<?, FileForFetch> backupTask,
		@Nonnull RestorationStepsFactory stepsFactory
	) {
		return taskWith(backupTask, stepsFactory, null);
	}

	/**
	 * Builds the task under test, saying which catalog held the target name when the operation was submitted.
	 *
	 * @param backupTask             the preceding step, consulted for the archive it produced
	 * @param stepsFactory           builds the unpack-and-register steps
	 * @param expectedTargetFolderId folder the target name was bound to at submission, or `null` when nothing
	 *                               held it
	 * @return the task, not yet issued
	 */
	@Nonnull
	private PublishRestoredCatalogTask taskWith(
		@Nonnull ServerTask<?, FileForFetch> backupTask,
		@Nonnull RestorationStepsFactory stepsFactory,
		@Nullable CatalogFolderId expectedTargetFolderId
	) {
		return new PublishRestoredCatalogTask(
			SOURCE_CATALOG, TEMPORARY_CATALOG, SOURCE_CATALOG, expectedTargetFolderId,
			this.evita, this.exportService, this.fileManagementService, stepsFactory, backupTask
		);
	}

	/**
	 * Stubs the swap so that it reports success whatever expectations it is handed. The expectations are what the
	 * tests below assert on, so the stub must not constrain them.
	 */
	private void stubSwap() {
		when(
			this.evita.replaceCatalogWithProgress(
				eq(TEMPORARY_CATALOG), eq(SOURCE_CATALOG), any(EngineMutationPrecondition[].class)
			)
		).thenReturn(completedProgress());
	}

	/**
	 * Reads back the expectations the task handed to the swap.
	 *
	 * @return every precondition passed, in the order given
	 */
	@Nonnull
	private List<EngineMutationPrecondition> capturedPreconditions() {
		// captured as the array rather than as the component type: a component-typed captor stands for exactly one
		// vararg, so it would not match the two expectations the swap actually hands over
		final ArgumentCaptor<EngineMutationPrecondition[]> captor =
			ArgumentCaptor.forClass(EngineMutationPrecondition[].class);
		verify(this.evita).replaceCatalogWithProgress(
			eq(TEMPORARY_CATALOG), eq(SOURCE_CATALOG), captor.capture()
		);
		return List.of(captor.getValue());
	}

	/**
	 * Stubs the swap so that it reports the passed failure whatever expectations it is handed.
	 *
	 * @param cause failure the swap reports
	 */
	private void stubFailingSwap(@Nonnull RuntimeException cause) {
		when(
			this.evita.replaceCatalogWithProgress(
				eq(TEMPORARY_CATALOG), eq(SOURCE_CATALOG), any(EngineMutationPrecondition[].class)
			)
		).thenReturn(failedProgress(cause));
	}

	/**
	 * Builds a backup step that has already run and produced this fixture's archive.
	 *
	 * @return the completed step
	 */
	@Nonnull
	private ServerTask<?, FileForFetch> completedBackupTask() {
		final FileForFetch producedArchive = this.archive;
		final ClientCallableTask<Void, FileForFetch> backupTask = new ClientCallableTask<>(
			"backupCatalog", "Backing up catalog `" + SOURCE_CATALOG + "`.", null,
			theTask -> producedArchive, TaskTrait.CAN_BE_CANCELLED
		);
		backupTask.transitionToIssued();
		backupTask.execute();
		return backupTask;
	}

	/**
	 * Builds restoration steps that record their execution and take the folder claim, but touch nothing else.
	 *
	 * The claim is allocated, and the resulting folder published as the scratch name's binding, because that is
	 * what the real unpacking and registering steps do between them - and together they are what the clean-up
	 * consults before removing anything. Steps that skipped either half would put every test that uses them on
	 * the "that name is somebody else's" branch, where nothing is removed, which is the opposite of what most of
	 * them are written to observe.
	 *
	 * @param unpackRan   flipped when the unpacking step runs
	 * @param registerRan flipped when the registering step runs
	 * @return the factory handing those steps out
	 */
	@Nonnull
	private RestorationStepsFactory noOpSteps(
		@Nonnull AtomicBoolean unpackRan,
		@Nonnull AtomicBoolean registerRan
	) {
		final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(this.storageDirectory);
		final RestoreFolderClaim claim = new RestoreFolderClaim();
		return (catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore) -> new RestorationSteps(
			new ClientRunnableTask<>(
				"restoreCatalog", "unpack", null,
				() -> {
					claim.allocate(folderContext, catalogName);
					// what the real registering step does next: the name now denotes this restore's own folder
					this.scratchFolderBinding.set(claim.allocatedFolderId());
					unpackRan.set(true);
				},
				TaskTrait.CAN_BE_CANCELLED
			),
			recordingStep("register", registerRan),
			claim
		);
	}

	/**
	 * Builds restoration steps that record nothing and touch nothing.
	 *
	 * @return the factory handing those steps out
	 */
	@Nonnull
	private RestorationStepsFactory noOpSteps() {
		return noOpSteps(new AtomicBoolean(), new AtomicBoolean());
	}

	@Nested
	@DisplayName("Publishing")
	class Publishing {

		@Test
		@DisplayName("Demands the target name is still free when nothing held it at submission")
		void shouldDemandTheTargetIsStillFreeWhenItWasFree() {
			// Minutes pass before the swap runs, and another operation may create a catalog under the name in
			// between. Swapping on the name alone would destroy it on the strength of an observation made before
			// it existed, so the swap has to say it expected the name to be free rather than merely to be free of
			// anything it recognises.
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps(), null);
			task.transitionToIssued();

			task.execute();

			final List<EngineMutationPrecondition> preconditions = capturedPreconditions();
			assertTrue(
				preconditions.contains(EngineMutationPrecondition.expectingUnbound(SOURCE_CATALOG)),
				"A restore aimed at a free name must require it to still be free at the swap, otherwise it " +
					"overwrites whatever took the name meanwhile. Handed: " + preconditions
			);
		}

		@Test
		@DisplayName("Demands the target is still the very catalog that held the name at submission")
		void shouldDemandTheTargetIsStillTheSameCatalog() {
			// `overwriteTarget` says *that name*, not *that catalog*. A catalog dropped and recreated under the
			// target name while the restore ran is a different catalog, and replacing it was never what the client
			// asked for - so the expectation is pinned to the folder, which never repeats for a name.
			final CatalogFolderId targetAtSubmission = new CatalogFolderId(SOURCE_CATALOG + "_7");
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps(), targetAtSubmission);
			task.transitionToIssued();

			task.execute();

			final List<EngineMutationPrecondition> preconditions = capturedPreconditions();
			assertTrue(
				preconditions.contains(
					EngineMutationPrecondition.expectingBoundTo(SOURCE_CATALOG, targetAtSubmission)
				),
				"A restore aimed at an occupied name must require that very catalog to still be there, not " +
					"merely the name. Handed: " + preconditions
			);
		}

		@Test
		@DisplayName("Demands the scratch catalog is still the one this restore created")
		void shouldDemandTheScratchIsStillOurs() {
			// The other end of the same swap, and previously unguarded: the scratch name is ordinary once the
			// claim is released, so another operation may drop this restore's scratch catalog and create its own
			// under the same name. Publishing that one under the target name would serve a stranger's data as the
			// restored result.
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps(), null);
			task.transitionToIssued();

			task.execute();

			final CatalogFolderId ourScratch = scratchFolderBinding.get();
			assertNotNull(ourScratch, "The restoration steps must have recorded the folder they allocated.");
			final List<EngineMutationPrecondition> preconditions = capturedPreconditions();
			assertTrue(
				preconditions.contains(
					EngineMutationPrecondition.expectingBoundTo(TEMPORARY_CATALOG, ourScratch)
				),
				"The swap must require the scratch name to still hold this restore's own catalog. Handed: " +
					preconditions
			);
		}

		@Test
		@DisplayName("Removes the intermediate archive once the swap has committed")
		void shouldRemoveTheArchiveOnceTheSwapCommits() {
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			task.execute();

			// asserted here rather than end-to-end because a listing that comes back empty cannot tell "the archive
			// was removed" apart from "no archive was ever created"
			assertEquals(
				List.of(archive.fileId()), exportService.deletedFiles,
				"The intermediate archive must be removed exactly once, by id."
			);
			verify(evita, never()).deleteCatalogIfExistsWithProgress(anyString());
			assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
			assertEquals(100, task.getStatus().progress());
		}

		@Test
		@DisplayName("Reports the unpacking progress inside its own band rather than raw")
		void shouldForwardTheUnpackingProgressIntoItsOwnBand() {
			// The unpacking is the widest band of the operation and it already knows exactly how far it has got -
			// the archive's size is threaded down to it for that very purpose. Dropping the number leaves the task
			// sitting at the fetched mark for however long the archive takes to unpack, which on a large catalog is
			// the longest stretch a monitoring client sees without a single update.
			final List<Integer> reportedProgress = new ArrayList<>(1);
			final AtomicReference<PublishRestoredCatalogTask> holder = new AtomicReference<>();
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestorationStepsFactory reportingSteps =
				(catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore) -> {
					final RestoreFolderClaim claim = new RestoreFolderClaim();
					return new RestorationSteps(
						new ClientRunnableTask<Void>(
							"restoreCatalog", "unpack", null,
							(Consumer<ClientRunnableTask<Void>>) step -> {
								claim.allocate(folderContext, catalogName);
								scratchFolderBinding.set(claim.allocatedFolderId());
								// read from inside the step, the only moment the sequence is in flight - the phase
								// runs inline on this very thread, so there is no later point to observe it from
								step.updateProgress(100);
								reportedProgress.add(holder.get().getStatus().progress());
							},
							TaskTrait.CAN_BE_CANCELLED
						),
						recordingStep("register", new AtomicBoolean()),
						claim
					);
				};
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), reportingSteps);
			holder.set(task);
			task.transitionToIssued();

			task.execute();

			// the unpacking step finished and the registering step not yet started puts the sequence at its own
			// (100 + 0) / 2 = 50 %, which lands halfway up the 10..55 band: 10 + (50 * 45) / 100
			assertEquals(
				List.of(32), reportedProgress,
				"The unpacking must be reported between the fetched and unpacked marks, never as its own percentage."
			);
		}

		@Test
		@DisplayName("Reports the load progress inside its own band rather than raw")
		void shouldForwardTheActivationProgressIntoItsOwnBand() {
			// the band is arithmetic over three private constants nothing reads back, so an inverted band or a
			// reordered constant surfaces only as a progress bar jumping backwards on a live monitoring client
			final List<Integer> reportedProgress = new ArrayList<>(2);
			final AtomicReference<PublishRestoredCatalogTask> holder = new AtomicReference<>();
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(
				progressFeeding(
					listener -> {
						listener.accept(0);
						reportedProgress.add(holder.get().getStatus().progress());
						listener.accept(100);
						reportedProgress.add(holder.get().getStatus().progress());
					}
				)
			);
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			holder.set(task);
			task.transitionToIssued();

			task.execute();

			assertEquals(
				List.of(55, 90), reportedProgress,
				"The load must be reported between the unpacked and activated marks, never as its own percentage."
			);
		}
	}

	@Nested
	@DisplayName("Failure paths")
	class FailurePaths {

		@Test
		@DisplayName("Drops the temporary catalog and keeps the archive when the swap fails")
		void shouldDropTheTemporaryCatalogAndKeepTheArchiveWhenTheSwapFails() {
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubFailingSwap(new IllegalStateException("the swap failed"));
			when(evita.deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG))
				.thenReturn(Optional.of(completedProgress()));
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			final CompletionException thrown = assertThrows(CompletionException.class, task::execute);

			assertEquals("the swap failed", thrown.getCause().getMessage());
			verify(evita).deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG);
			// the kept archive is a promise made to the operator in writing: a failed run leaves it listed among
			// the files to fetch, so the restore can be retried from it by hand
			assertTrue(
				exportService.deletedFiles.isEmpty(),
				"A failed restore must keep its archive - it is the operator's cheapest way to retry."
			);
			assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		}

		@Test
		@DisplayName("Drops the temporary catalog when the load fails before the swap is attempted")
		void shouldDropTheTemporaryCatalogWhenTheActivationFails() {
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG))
				.thenReturn(failedProgress(new IllegalStateException("the catalog could not be loaded")));
			when(evita.deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG))
				.thenReturn(Optional.of(completedProgress()));
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			final CompletionException thrown = assertThrows(CompletionException.class, task::execute);

			assertEquals("the catalog could not be loaded", thrown.getCause().getMessage());
			verify(evita, never()).replaceCatalogWithProgress(
				anyString(), anyString(), any(EngineMutationPrecondition[].class));
			verify(evita).deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG);
			assertTrue(exportService.deletedFiles.isEmpty());
		}

		@Test
		@DisplayName("Names the export retention when the archive disappeared before it could be read back")
		void shouldNameTheExportRetentionWhenTheArchiveDisappeared() {
			final UUID missingFileId = archive.fileId();
			exportService.beforeFetch = () -> {
				throw new FileForFetchNotFoundException(missingFileId);
			};
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			final UnexpectedIOException thrown = assertThrows(UnexpectedIOException.class, task::execute);

			// the diagnosis is the whole point of this branch: an operator told only "the archive is gone" goes
			// hunting a corruption that is not there, instead of looking at the retention that removed it
			assertTrue(
				thrown.getPublicMessage().contains("size limit"),
				() -> "The public message must point at the export retention, but read: " +
					thrown.getPublicMessage()
			);
			assertTrue(
				thrown.getMessage().contains(SOURCE_CATALOG),
				() -> "The internal message must name the catalog, but read: " + thrown.getMessage()
			);
		}

		@Test
		@DisplayName("Names the catalog when the archive cannot be copied into the work directory")
		void shouldNameTheCatalogWhenTheArchiveCannotBeReadBack() {
			exportService.contentSupplier = () -> new InputStream() {
				@Override
				public int read() throws IOException {
					throw new IOException("no space left on device");
				}
			};
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			final UnexpectedIOException thrown = assertThrows(UnexpectedIOException.class, task::execute);

			// `IOUtils#copy` catches the IOException itself and rethrows the unchecked UnexpectedIOException, so a
			// copy that fails never reaches a `catch (IOException)` - it has to be caught as what it actually is, or
			// the failure escapes carrying a generic copy message and a work-directory path naming nothing the
			// operator can place
			assertTrue(
				thrown.getMessage().contains(SOURCE_CATALOG),
				() -> "A copy failure must be attributed to the catalog being restored, but read: " +
					thrown.getMessage()
			);
		}

		@Test
		@DisplayName("Refuses to publish when the preceding backup step produced no archive")
		void shouldRefuseToPublishWhenTheBackupStepProducedNoArchive() {
			// the enclosing sequence stops at the first failing step, so reaching this one without an archive is a
			// broken invariant rather than a runtime outcome - and it must be named as one before anything is touched
			final ClientCallableTask<Void, FileForFetch> neverRunBackup = new ClientCallableTask<>(
				"backupCatalog", "Backing up catalog `" + SOURCE_CATALOG + "`.", null,
				theTask -> archive, TaskTrait.CAN_BE_CANCELLED
			);
			final PublishRestoredCatalogTask task = taskWith(neverRunBackup, noOpSteps());
			task.transitionToIssued();

			assertThrows(GenericEvitaInternalError.class, task::execute);

			assertTrue(exportService.fetchedFiles.isEmpty(), "Nothing may be fetched before the premise holds.");
			verifyNoInteractions(evita);
		}

		@Test
		@DisplayName("Leaves the temporary name alone when the unpacking never claimed it")
		void shouldNotRemoveACatalogItNeverClaimed() {
			// The temporary name is checked for availability when the operation is submitted and only claimed
			// minutes later, by the unpacking step. Failing to claim it is precisely the case where somebody
			// else got there first - so the catalog answering to that name is theirs, and removing it on this
			// restore's behalf would destroy a catalog that has nothing to do with the restore.
			final RestorationStepsFactory unclaimedSteps =
				(catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore) -> new RestorationSteps(
					failingStep(() -> new IllegalStateException("Catalog `" + catalogName + "` is already claimed!")),
					recordingStep("register", new AtomicBoolean()),
					new RestoreFolderClaim()
				);
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), unclaimedSteps);
			task.transitionToIssued();

			assertThrows(IllegalStateException.class, task::execute);

			verify(evita, never()).deleteCatalogIfExistsWithProgress(anyString());
			assertTrue(
				exportService.deletedFiles.isEmpty(),
				"A restore that failed must keep its archive so it can be retried by hand."
			);
		}

		@Test
		@DisplayName("Leaves the temporary name alone when another catalog has taken it since")
		void shouldNotRemoveACatalogThatTookTheScratchNameLater() {
			// The folder claim is given back the moment the registering step finishes, so from then on the
			// scratch name is an ordinary catalog name: another operation may drop this restore's catalog and
			// create its own under it. That catalog is bound to a folder of its own, which is what tells the two
			// apart - "this restore once allocated something" cannot, and would delete a stranger's catalog.
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestorationStepsFactory hijackedSteps =
				(catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore) -> {
					final RestoreFolderClaim claim = new RestoreFolderClaim();
					return new RestorationSteps(
						new ClientRunnableTask<>(
							"restoreCatalog", "unpack", null,
							() -> {
								claim.allocate(folderContext, catalogName);
								// the name now denotes somebody else's folder, not the one just allocated
								scratchFolderBinding.set(new CatalogFolderId(catalogName + "_99"));
							},
							TaskTrait.CAN_BE_CANCELLED
						),
						recordingStep("register", new AtomicBoolean()),
						claim
					);
				};
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubFailingSwap(new IllegalStateException("the swap failed"));
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), hijackedSteps);
			task.transitionToIssued();

			assertThrows(CompletionException.class, task::execute);

			verify(evita, never()).deleteCatalogIfExistsWithProgress(anyString());
		}

		@Test
		@DisplayName("Lets the original failure out when the clean-up fails as well")
		void shouldNotMaskTheOriginalFailureWhenTheCleanUpAlsoFails() {
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubFailingSwap(new IllegalStateException("the swap failed"));
			when(evita.deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG))
				.thenThrow(new IllegalStateException("the temporary catalog could not be dropped"));
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			task.transitionToIssued();

			final CompletionException thrown = assertThrows(CompletionException.class, task::execute);

			// a secondary error raised inside a `finally` replaces whatever was already unwinding, so the clean-up
			// swallowing its own failure is the only thing keeping the real cause reportable
			assertEquals(
				"the swap failed", thrown.getCause().getMessage(),
				"The clean-up must never replace the failure that brought the task there."
			);
		}
	}

	@Nested
	@DisplayName("Cancellation")
	class Cancellation {

		@Test
		@DisplayName("Stops at the first phase boundary after the archive was fetched")
		void shouldStopAtTheFirstPhaseBoundaryAfterTheArchiveWasFetched() {
			final AtomicBoolean unpackRan = new AtomicBoolean();
			final AtomicBoolean registerRan = new AtomicBoolean();
			final AtomicReference<PublishRestoredCatalogTask> holder = new AtomicReference<>();
			// cancelling from inside the fetch puts the cancel at a boundary the test names, instead of wherever a
			// second thread would have happened to land
			exportService.beforeFetch = () -> holder.get().cancel();
			final PublishRestoredCatalogTask task = taskWith(
				completedBackupTask(), noOpSteps(unpackRan, registerRan)
			);
			holder.set(task);
			task.transitionToIssued();

			task.execute();

			assertTrue(task.getFutureResult().isCancelled(), "The result future was not cancelled.");
			assertFalse(unpackRan.get(), "The archive was unpacked after the task had been cancelled.");
			assertFalse(registerRan.get(), "The temporary catalog was registered after the task was cancelled.");
			verify(evita, never()).replaceCatalogWithProgress(
				anyString(), anyString(), any(EngineMutationPrecondition[].class));
			// cancelled before the unpacking step, so the folder was never allocated and nothing was ever
			// registered under this name. Removing it regardless would be removing whatever else happens to
			// answer to it - see the clean-up's ownership test below
			verify(evita, never()).deleteCatalogIfExistsWithProgress(anyString());
			assertTrue(
				exportService.deletedFiles.isEmpty(),
				"A cancelled restore keeps its archive on the same terms as a failed one."
			);
		}

		@Test
		@DisplayName("Leaves no local copy of the archive in the work directory when cancelled")
		void shouldNotLeaveTheFetchedArchiveInTheWorkDirectoryWhenCancelled() {
			final AtomicReference<PublishRestoredCatalogTask> holder = new AtomicReference<>();
			exportService.beforeFetch = () -> holder.get().cancel();
			final PublishRestoredCatalogTask task = taskWith(completedBackupTask(), noOpSteps());
			holder.set(task);
			task.transitionToIssued();

			task.execute();

			// the unpacking step is the only thing that ever deletes this file on its own - it opens it with
			// DELETE_ON_CLOSE - so a cancel landing before that step would orphan a full compressed copy of the
			// catalog in the work directory, and repeating a cancelled restore would grow it without bound
			assertFalse(
				Files.exists(workDirectory.resolve(archive.fileId() + ".zip")),
				"A cancelled restore must take its local copy of the archive with it."
			);
		}
	}

	@Nested
	@DisplayName("Folder claim")
	class FolderClaim {

		@Test
		@DisplayName("Releases the folder claim once the archive has been unpacked")
		void shouldReleaseTheFolderClaimWhenTheUnpackingSucceeded() {
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();
			when(evita.activateCatalogWithProgress(TEMPORARY_CATALOG)).thenReturn(completedProgress());
			stubSwap();
			final PublishRestoredCatalogTask task = taskWith(
				completedBackupTask(), claimingSteps(claim, folderContext, () -> null)
			);
			task.transitionToIssued();

			task.execute();

			assertNameIsClaimableAgain(folderContext);
		}

		@Test
		@DisplayName("Releases the folder claim even when the unpacking threw")
		void shouldReleaseTheFolderClaimWhenTheUnpackingThrew() {
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();
			when(evita.deleteCatalogIfExistsWithProgress(TEMPORARY_CATALOG))
				.thenReturn(Optional.of(completedProgress()));
			final PublishRestoredCatalogTask task = taskWith(
				completedBackupTask(),
				claimingSteps(claim, folderContext, () -> new IllegalStateException("the archive is not a ZIP"))
			);
			task.transitionToIssued();

			assertThrows(IllegalStateException.class, task::execute);

			// a leaked claim fails nothing visibly - it permanently blocks one catalog name, and only a later
			// create, restore or duplicate of that name ever notices
			assertNameIsClaimableAgain(folderContext);
		}

		/**
		 * Builds restoration steps whose unpacking step takes the folder claim, exactly as the real one does.
		 *
		 * @param claim         claim the unpacking step allocates into
		 * @param folderContext context the folder is allocated through
		 * @param failure       raised once the folder has been allocated, or answering `null` to let it succeed
		 * @return the factory handing those steps out
		 */
		@Nonnull
		private RestorationStepsFactory claimingSteps(
			@Nonnull RestoreFolderClaim claim,
			@Nonnull CatalogFolderContext folderContext,
			@Nonnull Supplier<RuntimeException> failure
		) {
			return (catalogName, fileId, pathToFile, totalBytesExpected, deleteAfterRestore) -> new RestorationSteps(
				allocatingStep(claim, folderContext, catalogName, failure),
				recordingStep("register", new AtomicBoolean()),
				claim
			);
		}

		/**
		 * Asserts that the temporary catalog's name can be materialised again, which is only true once the claim
		 * this restore took has been given back.
		 *
		 * @param folderContext context the name was claimed in
		 */
		private void assertNameIsClaimableAgain(@Nonnull CatalogFolderContext folderContext) {
			try (final CatalogFolderReservation reclaimed = folderContext.allocateFolderFor(TEMPORARY_CATALOG)) {
				assertNotNull(
					reclaimed.folderId(),
					"The restore left its folder claim held - the catalog name is now un-materialisable."
				);
			}
		}
	}

	/**
	 * Export service that answers with a canned archive and records what was asked of it.
	 *
	 * Only the three methods this task uses are implemented; the rest refuse loudly rather than answering
	 * something plausible, so a future call added to the task shows up as a failure instead of as silence.
	 */
	private static class RecordingExportService implements ExportService {
		/**
		 * Ids the task asked to read back, in order.
		 */
		private final List<UUID> fetchedFiles = new ArrayList<>(2);
		/**
		 * Ids the task asked to remove, in order.
		 */
		private final List<UUID> deletedFiles = new ArrayList<>(2);
		/**
		 * Supplies the archive contents; replaced by tests that need the copy itself to fail.
		 */
		private Supplier<InputStream> contentSupplier = () -> new ByteArrayInputStream(ARCHIVE_CONTENT);
		/**
		 * Runs before the archive is handed out - the seam tests use to cancel the task, or to make the archive
		 * disappear, at a boundary they name.
		 */
		private Runnable beforeFetch = () -> {
		};

		@Nonnull
		@Override
		public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
			throw new UnsupportedOperationException("Listing is not part of publishing a restored catalog!");
		}

		@Nonnull
		@Override
		public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
			throw new UnsupportedOperationException("Metadata lookup is not part of publishing a restored catalog!");
		}

		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName,
			@Nullable String description,
			@Nonnull String contentType,
			@Nullable String origin
		) {
			throw new UnsupportedOperationException("Publishing a restored catalog writes no export file!");
		}

		@Nonnull
		@Override
		public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
			this.beforeFetch.run();
			this.fetchedFiles.add(fileId);
			return this.contentSupplier.get();
		}

		@Override
		public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
			this.deletedFiles.add(fileId);
		}

		@Override
		public long purgeFiles() {
			throw new UnsupportedOperationException("Retention is not driven by publishing a restored catalog!");
		}

		@Override
		public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
			throw new UnsupportedOperationException("Retention is not driven by publishing a restored catalog!");
		}

		@Override
		public void close() {
			// nothing is held open
		}
	}

	/**
	 * Progress whose completion and whose percentage feed are both supplied by the test.
	 *
	 * @param <T> type of the tracked operation's result
	 */
	private static class StubProgress<T> implements Progress<T> {
		/**
		 * The completion the task joins on.
		 */
		private final CompletableFuture<T> completion;
		/**
		 * Invoked with each subscribed listener, on the subscriber's own thread.
		 */
		private final Consumer<IntConsumer> listenerFeed;
		/**
		 * Last percentage fed to the listeners.
		 */
		private int percentCompleted;

		StubProgress(@Nonnull CompletableFuture<T> completion, @Nonnull Consumer<IntConsumer> listenerFeed) {
			this.completion = completion;
			this.listenerFeed = listenerFeed;
		}

		@Override
		public int percentCompleted() {
			return this.percentCompleted;
		}

		@Nonnull
		@Override
		public CompletionStage<T> onCompletion() {
			return this.completion;
		}

		@Override
		public boolean isCompletedSuccessfully() {
			return this.completion.isDone() && !this.completion.isCompletedExceptionally();
		}

		@Override
		public boolean isCompletedExceptionally() {
			return this.completion.isCompletedExceptionally();
		}

		@Override
		public void addProgressListener(@Nonnull IntConsumer intConsumer) {
			this.listenerFeed.accept(
				percent -> {
					this.percentCompleted = percent;
					intConsumer.accept(percent);
				}
			);
		}

		@Override
		public void removeProgressListener(@Nonnull IntConsumer intConsumer) {
			// the task never removes its listener
		}
	}

}
