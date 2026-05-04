/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * Tests `RestoreCatalogSchemaMutationOperator` — the state-transition driver that registers a previously-unloaded
 * catalog folder into the engine.
 *
 * Asymmetry vs `CreateCatalogMutationOperator`: a freshly-created catalog lands in `WARMING_UP`, but a restored
 * catalog lands in `INACTIVE`. The operator does this by passing an `UnusableCatalog(INACTIVE)` placeholder to
 * `Builder#withCatalog(...)` — which routes non-`Catalog` instances into the `inactiveCatalogs` array, not
 * `activeCatalogs`. These tests pin that behaviour for all three call paths described in the operator JavaDoc:
 *
 * 1. **Restore from backup** — folder is on disk, catalog name is unknown to the engine,
 * 2. **Auto-discovery** — same as (1) but driven by Evita's boot reconciliation, indistinguishable here,
 * 3. **Flapping recovery** — folder reappeared and the name is currently parked in `missingCatalogs`; the operator
 *    must clear the missing-bucket entry through `Builder#withRestoredFromMissing(...)`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("RestoreCatalogSchemaMutationOperator tests")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
@Tag(MANAGEMENT)
class RestoreCatalogSchemaMutationOperatorTest {

	private static final String CATALOG_NAME = "restoredCatalog";

	/**
	 * Builds a minimal `ExpandedEngineState` with the supplied bucket arrays and the supplied catalogs map. Used as
	 * the starting point for the completion updater.
	 *
	 * @param activeCatalogs   names to register as active
	 * @param inactiveCatalogs names to register as inactive
	 * @param missingCatalogs  names to register as missing
	 * @param catalogs         in-memory catalog instances keyed by name (typically empty for restore tests, since
	 *                         the restored catalog has no in-memory presence yet)
	 * @return a starting engine state with version `1L`
	 */
	@Nonnull
	private static ExpandedEngineState buildStartingState(
		@Nonnull String[] activeCatalogs,
		@Nonnull String[] inactiveCatalogs,
		@Nonnull String[] missingCatalogs,
		@Nonnull Map<String, CatalogContract> catalogs
	) {
		final EngineState<LogRecordReference> engineState = new EngineState<>(
			1,
			1L,
			OffsetDateTime.now(),
			null,
			activeCatalogs,
			inactiveCatalogs,
			ArrayUtils.EMPTY_STRING_ARRAY,
			missingCatalogs
		);
		return ExpandedEngineState.create(engineState, catalogs);
	}

	/**
	 * Drives the operator's `applyMutation` and the resulting completion updater against the supplied starting
	 * state, returning the captured snapshot after the completion phase has been applied. The operator's work phase
	 * (folder-existence check + completion-updater accept) runs synchronously when the future is executed.
	 *
	 * @param storageDirectory the storage root used to construct the operator
	 * @param startingState    starting engine state passed to the completion updater
	 * @param transitionUpdater mock transition updater — must NOT be invoked (restore is single-phase)
	 * @param completionUpdater mock completion updater — captures the resulting state transformation
	 * @return the captured `ExpandedEngineState` produced by the completion updater (version 2)
	 */
	@Nonnull
	private static ExpandedEngineState applyRestoreMutation(
		@Nonnull Path storageDirectory,
		@Nonnull ExpandedEngineState startingState,
		@Nonnull Consumer<EngineStateUpdater> transitionUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionUpdater
	) throws Exception {
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		final RestoreCatalogSchemaMutationOperator operator =
			new RestoreCatalogSchemaMutationOperator(storageDirectory);
		final RestoreCatalogSchemaMutation mutation = new RestoreCatalogSchemaMutation(CATALOG_NAME);

		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater
		);
		// Restore is structured as a single-phase work-then-completion: the future body runs the completion
		// accept side-effect when executed. A single-thread pool is used so the verify() below is deterministic.
		future.execute(Executors.newSingleThreadExecutor());
		final Void result = future.get(5, TimeUnit.SECONDS);
		assertNull(result);

		final ArgumentCaptor<EngineStateUpdater> completionCaptor =
			ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(completionUpdater).accept(completionCaptor.capture());
		return completionCaptor.getValue().apply(2L, startingState);
	}

	/**
	 * Asserts the restored catalog landed in the INACTIVE bucket: the name is in `inactiveCatalogs`, NOT in
	 * `activeCatalogs`, and the in-memory catalogs map serves an `UnusableCatalog(INACTIVE)` placeholder whose
	 * data-serving methods raise `CatalogInactiveException`.
	 */
	private static void assertRestoredAsInactive(
		@Nonnull ExpandedEngineState state,
		@Nonnull Path expectedCatalogFolder
	) {
		// Bucket invariant — the operator uses `withCatalog(UnusableCatalog)` which routes non-`Catalog`
		// instances into the inactive bucket, so the restore deliberately lands in INACTIVE rather than
		// WARMING_UP. This is the asymmetry vs `CreateCatalogMutationOperator` that the test suite documents.
		assertArrayEquals(new String[]{CATALOG_NAME}, state.engineState().inactiveCatalogs());
		assertEquals(0, state.engineState().activeCatalogs().length);

		final Optional<CatalogContract> restored = state.getCatalog(CATALOG_NAME);
		assertTrue(restored.isPresent());
		assertInstanceOf(UnusableCatalog.class, restored.get());
		assertEquals(CatalogState.INACTIVE, restored.get().getCatalogState());
		assertEquals(CATALOG_NAME, restored.get().getName());

		// Storage path on the placeholder must be the resolved catalog folder — operators read this back
		// through `getCatalogStoragePath()` to surface a concrete location in error messages.
		final UnusableCatalog placeholder = (UnusableCatalog) restored.get();
		assertEquals(expectedCatalogFolder, placeholder.getCatalogStoragePath());

		// Data-serving access must raise CatalogInactiveException — the placeholder's cause function
		// is `CatalogInactiveException::new`, so any data path enforces "load it first" semantics.
		final CatalogInactiveException inactive = assertThrows(
			CatalogInactiveException.class, restored.get()::getSchema
		);
		assertNotNull(inactive.getMessage());
		assertTrue(
			inactive.getMessage().contains(CATALOG_NAME),
			"CatalogInactiveException message should embed the catalog name for operator visibility."
		);
	}

	@Test
	@DisplayName("should produce a descriptive operation name embedding the catalog name")
	void shouldProduceDescriptiveOperationName(@TempDir Path storageDirectory) {
		// The operation name is surfaced in progress reports and CDC notifications. Asserting the
		// exact format guards against accidental wording drift downstream operators may grep for.
		final RestoreCatalogSchemaMutationOperator operator =
			new RestoreCatalogSchemaMutationOperator(storageDirectory);
		final RestoreCatalogSchemaMutation mutation = new RestoreCatalogSchemaMutation(CATALOG_NAME);

		assertEquals(
			"Restoring catalog `" + CATALOG_NAME + "`",
			operator.getOperationName(mutation)
		);
	}

	@Test
	@DisplayName("should reject restore when the catalog folder does not exist on disk")
	void shouldRejectRestoreWhenFolderMissing(@TempDir Path storageDirectory) {
		// Pre-condition: the folder must already be on disk before the operator runs (the operator-level
		// "restore" mutation is the engine's acknowledgement, the actual file work happens in the upload
		// pipeline upstream). If the folder is missing the operator must fail loudly via Assert.isTrue
		// rather than silently registering a catalog with no data.
		final Evita evita = mock(Evita.class);
		final RestoreCatalogSchemaMutationOperator operator =
			new RestoreCatalogSchemaMutationOperator(storageDirectory);
		final RestoreCatalogSchemaMutation mutation = new RestoreCatalogSchemaMutation(CATALOG_NAME);

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
			mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
			mock(Consumer.class);

		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> operator.applyMutation(
				UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater
			)
		);
		assertTrue(
			exception.getMessage().contains(CATALOG_NAME),
			"The folder-missing error must name the catalog so operators can spot the typo / dispatch error."
		);
		// Neither updater may be invoked when the precondition fails — the engine state must stay
		// untouched so the next attempt (after a manual upload) starts from the same snapshot.
		verifyNoInteractions(transitionUpdater);
		verifyNoInteractions(completionUpdater);
	}

	@Nested
	@DisplayName("Restore happy path — catalog lands in INACTIVE")
	class HappyPath {

		@Test
		@DisplayName("should register restored catalog in INACTIVE bucket (asymmetry vs CreateCatalogMutationOperator)")
		void shouldRegisterRestoredCatalogInInactiveBucket(@TempDir Path storageDirectory) throws Exception {
			// Pre-create the catalog folder on disk; the operator's `Assert.isTrue(catalogFolder.toFile().exists())`
			// must see a real directory or it short-circuits before any state update.
			final Path catalogFolder = Files.createDirectory(storageDirectory.resolve(CATALOG_NAME));

			final ExpandedEngineState startingState = buildStartingState(
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY,
				new HashMap<>()
			);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterRestore = applyRestoreMutation(
				storageDirectory, startingState, transitionUpdater, completionUpdater
			);

			assertRestoredAsInactive(afterRestore, catalogFolder);
			assertEquals(2L, afterRestore.version());
			// missingCatalogs stays empty for the auto-discovery / restore-from-backup branches —
			// the call to `withRestoredFromMissing` is unconditional but a no-op when the bucket
			// did not contain the catalog name.
			assertEquals(0, afterRestore.engineState().missingCatalogs().length);

			// Restore is single-phase — the transition updater must never be invoked.
			verifyNoInteractions(transitionUpdater);
		}

		@Test
		@DisplayName("should clear missing-bucket entry when restoring a previously-missing catalog (flapping recovery)")
		void shouldClearMissingBucketWhenRestoringFlappingCatalog(@TempDir Path storageDirectory) throws Exception {
			// Flapping-recovery branch: the catalog name is currently parked in `missingCatalogs` (a previous
			// reconciliation observed the folder gone) and the folder has now reappeared. The operator must
			// transparently move it from missing → inactive in one builder chain, otherwise the catalog
			// would remain in missing AND inactive simultaneously and downstream observers would see a
			// stuck "missing" entry forever.
			final Path catalogFolder = Files.createDirectory(storageDirectory.resolve(CATALOG_NAME));

			// Starting state: catalog registered as missing, served by an UnusableCatalog(MISSING) stub.
			final UnusableCatalog missingPlaceholder = new UnusableCatalog(
				CATALOG_NAME,
				CatalogState.MISSING,
				catalogFolder,
				(cn, path) -> new IllegalStateException("unused in test")
			);
			final Map<String, CatalogContract> catalogs = new HashMap<>();
			catalogs.put(CATALOG_NAME, missingPlaceholder);
			final ExpandedEngineState startingState = buildStartingState(
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY,
				new String[]{CATALOG_NAME},
				catalogs
			);
			// Sanity-check the precondition — the catalog is in the missing bucket only.
			assertArrayEquals(
				new String[]{CATALOG_NAME}, startingState.engineState().missingCatalogs()
			);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterRestore = applyRestoreMutation(
				storageDirectory, startingState, transitionUpdater, completionUpdater
			);

			// Primary invariant — the catalog moved out of missing and into inactive.
			assertRestoredAsInactive(afterRestore, catalogFolder);
			assertEquals(0, afterRestore.engineState().missingCatalogs().length);
			assertEquals(2L, afterRestore.version());

			verifyNoInteractions(transitionUpdater);
		}

	}

	@Test
	@DisplayName("applyMutation should return a non-null ProgressingFuture that completes with null")
	void shouldReturnFutureCompletingWithNull(@TempDir Path storageDirectory) throws Exception {
		// Even though Restore has no result payload, the contract requires `applyMutation` to
		// return a non-null `ProgressingFuture<Void>` that completes successfully — downstream
		// callers chain on it.
		Files.createDirectory(storageDirectory.resolve(CATALOG_NAME));

		final ExpandedEngineState startingState = buildStartingState(
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			new HashMap<>()
		);
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
			mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
			mock(Consumer.class);

		final RestoreCatalogSchemaMutationOperator operator =
			new RestoreCatalogSchemaMutationOperator(storageDirectory);
		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(),
			new RestoreCatalogSchemaMutation(CATALOG_NAME),
			evita,
			transitionUpdater,
			completionUpdater
		);

		assertNotNull(future);
		future.execute(Executors.newSingleThreadExecutor());
		assertNull(future.get(5, TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("replayCompletionState should return Optional.empty() — restore is not safe to forward-replay")
	void shouldNotSupportForwardReplay(@TempDir Path storageDirectory) throws IOException {
		// The operator JavaDoc explicitly opts out of forward replay because the folder-existence
		// precondition is side-effect dependent on a successful work phase. The default
		// `Optional.empty()` from `EngineMutationOperator` makes the engine wedge loudly rather than
		// silently re-registering an inactive catalog whose folder may never have existed.
		final RestoreCatalogSchemaMutationOperator operator =
			new RestoreCatalogSchemaMutationOperator(storageDirectory);
		final RestoreCatalogSchemaMutation mutation = new RestoreCatalogSchemaMutation(CATALOG_NAME);
		final ExpandedEngineState startingState = buildStartingState(
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			new HashMap<>()
		);
		final Evita evita = mock(Evita.class);

		final Optional<ExpandedEngineState> replay =
			operator.replayCompletionState(mutation, 2L, startingState, evita);

		assertTrue(replay.isEmpty());
	}

}
