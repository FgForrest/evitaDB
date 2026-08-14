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
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CatalogMissingException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Tests `MarkCatalogMissingMutationOperator` — the state-transition driver that records the engine's awareness of a
 * catalog whose on-disk folder has gone missing.
 *
 * Scope: the operator is exercised in isolation (no full Evita boot). The tests cover:
 *
 * - the full source-bucket matrix (active / inactive / read-only) → `missingCatalogs`,
 * - idempotency when applied to a state that already has the catalog parked in `missingCatalogs`,
 * - parity between `applyMutation` (single-shot completion updater) and `replayCompletionState`
 *   (forward-replay re-derivation) — the duplicated branch flagged by the gap report,
 * - the `UnusableCatalog(MISSING)` placeholder rejecting access with `CatalogMissingException`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("MarkCatalogMissingMutationOperator tests")
@Tag(ENGINE)
@Tag(TRANSACTION)
class MarkCatalogMissingMutationOperatorTest {

	private static final String CATALOG_NAME = "doomedCatalog";
	private static final Path STORAGE_DIRECTORY = Paths.get("target", "mark-missing-operator-test");

	/**
	 * Builds a minimal `ExpandedEngineState` placing the supplied catalog in the requested bucket. The bucket is
	 * inferred from the boolean knobs: at most one of `active`, `inactive`, `readOnly` should be `true` — `readOnly`
	 * implies the catalog name is also recorded in `inactiveCatalogs` so the state is consistent (the read-only flag
	 * is orthogonal to the active/inactive split, but tests focus on the read-only bucket as a third source state).
	 *
	 * @param catalog  the catalog instance to register
	 * @param active   when `true`, the catalog name lands in `activeCatalogs`
	 * @param inactive when `true`, the catalog name lands in `inactiveCatalogs`
	 * @param readOnly when `true`, the catalog name lands in `readOnlyCatalogs` (and `inactiveCatalogs`)
	 * @return engine state with the catalog placed in the requested bucket
	 */
	@Nonnull
	private static ExpandedEngineState buildStartingState(
		@Nonnull CatalogContract catalog,
		boolean active,
		boolean inactive,
		boolean readOnly
	) {
		final String[] activeCatalogs = active ? new String[]{catalog.getName()} : ArrayUtils.EMPTY_STRING_ARRAY;
		final String[] inactiveCatalogs = (inactive || readOnly)
			? new String[]{catalog.getName()}
			: ArrayUtils.EMPTY_STRING_ARRAY;
		final String[] readOnlyCatalogs = readOnly
			? new String[]{catalog.getName()}
			: ArrayUtils.EMPTY_STRING_ARRAY;
		final EngineState<LogRecordReference> engineState = new EngineState<>(
			1,
			1L,
			OffsetDateTime.now(),
			null,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs,
			ArrayUtils.EMPTY_STRING_ARRAY
		);
		final Map<String, CatalogContract> catalogs = new HashMap<>();
		catalogs.put(catalog.getName(), catalog);
		return ExpandedEngineState.create(engineState, catalogs);
	}

	/**
	 * Builds a starting state where the catalog is already parked in the `missingCatalogs` bucket — used to verify
	 * the idempotent re-apply scenario.
	 */
	@Nonnull
	private static ExpandedEngineState buildAlreadyMissingState(@Nonnull CatalogContract placeholder) {
		final EngineState<LogRecordReference> engineState = new EngineState<>(
			1,
			1L,
			OffsetDateTime.now(),
			null,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			new String[]{placeholder.getName()}
		);
		final Map<String, CatalogContract> catalogs = new HashMap<>();
		catalogs.put(placeholder.getName(), placeholder);
		return ExpandedEngineState.create(engineState, catalogs);
	}

	/**
	 * Drives the operator's `applyMutation` and the resulting completion updater against the supplied starting
	 * state, returning the captured snapshot after the completion phase has been applied.
	 */
	@Nonnull
	private static ExpandedEngineState applyMissingMutation(
		@Nonnull ExpandedEngineState startingState,
		@Nonnull Consumer<EngineStateUpdater> transitionUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionUpdater
	) throws Exception {
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		final MarkCatalogMissingMutationOperator operator =
			new MarkCatalogMissingMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));
		final MarkCatalogMissingMutation mutation = new MarkCatalogMissingMutation(CATALOG_NAME);

		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater
		);
		// MISSING is a single-phase operator — the completion updater is invoked synchronously inside
		// applyMutation, so executing the future is purely to drain the no-op work phase and surface any
		// stray exception.
		future.execute(Executors.newSingleThreadExecutor());
		future.get(5, TimeUnit.SECONDS);

		final ArgumentCaptor<EngineStateUpdater> completionCaptor =
			ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(completionUpdater).accept(completionCaptor.capture());
		return completionCaptor.getValue().apply(2L, startingState);
	}

	/**
	 * Asserts the post-MISSING engine-state invariants: the catalog is absent from the active / inactive /
	 * read-only arrays, present in `missingCatalogs`, and the in-memory map serves an `UnusableCatalog(MISSING)`
	 * placeholder that throws `CatalogMissingException` on access.
	 */
	private static void assertMissingState(@Nonnull ExpandedEngineState state) {
		assertEquals(0, state.engineState().activeCatalogs().length);
		assertEquals(0, state.engineState().inactiveCatalogs().length);
		assertEquals(0, state.engineState().readOnlyCatalogs().length);
		assertArrayEquals(new String[]{CATALOG_NAME}, state.engineState().missingCatalogs());
		assertFalse(state.isReadOnly(CATALOG_NAME));

		final Optional<CatalogContract> placeholder = state.getCatalog(CATALOG_NAME);
		assertTrue(placeholder.isPresent());
		assertInstanceOf(UnusableCatalog.class, placeholder.get());
		assertEquals(CatalogState.MISSING, placeholder.get().getCatalogState());
		assertEquals(CATALOG_NAME, placeholder.get().getName());

		// Any data-serving access must surface CatalogMissingException — this is the user-visible
		// contract the placeholder enforces.
		final CatalogMissingException missing = assertThrows(
			CatalogMissingException.class, () -> placeholder.get().getSchema()
		);
		assertEquals(CATALOG_NAME, missing.getCatalogName());
	}

	@Test
	@DisplayName("should produce a descriptive operation name embedding the catalog name")
	void shouldProduceDescriptiveOperationName() {
		// The operation name is surfaced in progress reports and CDC notifications. Asserting the
		// exact format guards against accidental wording drift that downstream operators may grep for.
		final MarkCatalogMissingMutationOperator operator =
			new MarkCatalogMissingMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));
		final MarkCatalogMissingMutation mutation = new MarkCatalogMissingMutation(CATALOG_NAME);

		assertEquals(
			"Marking catalog `" + CATALOG_NAME + "` as missing",
			operator.getOperationName(mutation)
		);
	}

	@Test
	@DisplayName("transition updater must not be invoked — MISSING is a single-phase operator")
	void shouldNotInvokeTransitionUpdater() throws Exception {
		// The operator's contract is to skip the transition updater entirely — there is no long-running
		// work between pre- and post-mutation, so going straight to the completion updater is the correct
		// (and observable) shape. Regressions that re-introduce a transition phase would silently move
		// the catalog through a transient state nobody expects.
		final CatalogContract aliveCatalog = mock(CatalogContract.class);
		when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
		when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

		final ExpandedEngineState startingState = buildStartingState(aliveCatalog, true, false, false);

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
			mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
			mock(Consumer.class);

		applyMissingMutation(startingState, transitionUpdater, completionUpdater);

		verifyNoInteractions(transitionUpdater);
	}

	@Nested
	@DisplayName("State transition matrix → MISSING")
	class StateTransitionMatrix {

		@Test
		@DisplayName("should move catalog from active bucket into missing bucket")
		void shouldMoveActiveCatalogIntoMissingBucket() throws Exception {
			final CatalogContract aliveCatalog = mock(CatalogContract.class);
			when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
			when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

			final ExpandedEngineState startingState =
				buildStartingState(aliveCatalog, true, false, false);
			// Sanity-check the precondition — catalog starts in activeCatalogs, not in any other bucket.
			assertArrayEquals(
				new String[]{CATALOG_NAME}, startingState.engineState().activeCatalogs()
			);
			assertEquals(0, startingState.engineState().missingCatalogs().length);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterMissing =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			assertMissingState(afterMissing);
			assertEquals(2L, afterMissing.version());
		}

		@Test
		@DisplayName("should move catalog from inactive bucket into missing bucket")
		void shouldMoveInactiveCatalogIntoMissingBucket() throws Exception {
			final CatalogContract inactiveCatalog = mock(CatalogContract.class);
			when(inactiveCatalog.getName()).thenReturn(CATALOG_NAME);
			when(inactiveCatalog.getCatalogState()).thenReturn(CatalogState.INACTIVE);

			final ExpandedEngineState startingState =
				buildStartingState(inactiveCatalog, false, true, false);
			assertArrayEquals(
				new String[]{CATALOG_NAME}, startingState.engineState().inactiveCatalogs()
			);
			assertEquals(0, startingState.engineState().missingCatalogs().length);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterMissing =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			assertMissingState(afterMissing);
			assertEquals(2L, afterMissing.version());
		}

		@Test
		@DisplayName("should clear read-only flag and move catalog into missing bucket")
		void shouldClearReadOnlyFlagAndMoveCatalogIntoMissingBucket() throws Exception {
			// Read-only catalogs live in `inactiveCatalogs` AND `readOnlyCatalogs`. The MISSING transition
			// must clear both — the placeholder is no longer a read-only catalog, it is unusable.
			final CatalogContract readOnlyCatalog = mock(CatalogContract.class);
			when(readOnlyCatalog.getName()).thenReturn(CATALOG_NAME);
			when(readOnlyCatalog.getCatalogState()).thenReturn(CatalogState.INACTIVE);

			final ExpandedEngineState startingState =
				buildStartingState(readOnlyCatalog, false, false, true);
			assertArrayEquals(
				new String[]{CATALOG_NAME}, startingState.engineState().readOnlyCatalogs()
			);
			assertTrue(startingState.isReadOnly(CATALOG_NAME));
			assertEquals(0, startingState.engineState().missingCatalogs().length);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterMissing =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			assertMissingState(afterMissing);
			assertEquals(2L, afterMissing.version());
		}

	}

	@Nested
	@DisplayName("Idempotency and replay parity")
	class IdempotencyAndReplay {

		@Test
		@DisplayName("re-applying to an already-MISSING state must be a no-op")
		void shouldBeIdempotentWhenCatalogAlreadyMissing() throws Exception {
			// Re-applying the mutation against a snapshot that already has the catalog parked in
			// `missingCatalogs` must produce the same shape: still in missing-only bucket, still served
			// by an `UnusableCatalog(MISSING)` placeholder.
			final UnusableCatalog existingPlaceholder = TestCatalogFolderContexts
				.onDirectory(STORAGE_DIRECTORY)
				.createUnusableCatalog(
					CATALOG_NAME,
					CatalogState.MISSING,
					(cn, folderId, root) -> new CatalogMissingException(cn)
				);
			final ExpandedEngineState startingState = buildAlreadyMissingState(existingPlaceholder);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterReapply =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			assertMissingState(afterReapply);
			assertEquals(2L, afterReapply.version());
		}

		@Test
		@DisplayName(
			"replayCompletionState should produce the same shape as applyMutation's completion updater"
		)
		void shouldProduceParityBetweenApplyAndReplay() throws Exception {
			// The duplicated branch flagged by the gap report: `applyMutation` (single-phase) and
			// `replayCompletionState` (forward-replay re-derivation) must converge on identical shape so
			// a `walV == stateV + 1` crash window does not produce two divergent recoveries.
			final CatalogContract aliveCatalog = mock(CatalogContract.class);
			when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
			when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

			final ExpandedEngineState startingState =
				buildStartingState(aliveCatalog, true, false, false);

			// Branch A — apply the mutation through the regular completion updater path.
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);
			final ExpandedEngineState afterApply =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			// Branch B — re-derive the same completion-phase snapshot through replayCompletionState.
			final MarkCatalogMissingMutationOperator operator =
				new MarkCatalogMissingMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));
			final MarkCatalogMissingMutation mutation = new MarkCatalogMissingMutation(CATALOG_NAME);
			final Evita evita = mock(Evita.class);

			final Optional<ExpandedEngineState> replayResult =
				operator.replayCompletionState(mutation, 2L, startingState, evita);
			assertTrue(replayResult.isPresent());
			final ExpandedEngineState afterReplay = replayResult.get();

			// Parity assertions — same version, same bucket placements, same placeholder shape.
			assertEquals(afterApply.version(), afterReplay.version());
			assertArrayEquals(
				afterApply.engineState().activeCatalogs(),
				afterReplay.engineState().activeCatalogs()
			);
			assertArrayEquals(
				afterApply.engineState().inactiveCatalogs(),
				afterReplay.engineState().inactiveCatalogs()
			);
			assertArrayEquals(
				afterApply.engineState().readOnlyCatalogs(),
				afterReplay.engineState().readOnlyCatalogs()
			);
			assertArrayEquals(
				afterApply.engineState().missingCatalogs(),
				afterReplay.engineState().missingCatalogs()
			);
			assertMissingState(afterReplay);
		}

	}

	@Nested
	@DisplayName("UnusableCatalog placeholder rejects access")
	class UnusableCatalogPlaceholder {

		@Test
		@DisplayName("getSchema must throw CatalogMissingException carrying the catalog name")
		void shouldRejectGetSchemaWithCatalogMissingException() throws Exception {
			// Verifies the user-facing contract of the MISSING placeholder: any data-serving call
			// (here `getSchema()`) must surface a CatalogMissingException whose message names the
			// missing catalog, so operators get an actionable error instead of NPE-on-NULL-catalog.
			final CatalogContract aliveCatalog = mock(CatalogContract.class);
			when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
			when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

			final ExpandedEngineState startingState =
				buildStartingState(aliveCatalog, true, false, false);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterMissing =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);

			final Optional<CatalogContract> placeholder = afterMissing.getCatalog(CATALOG_NAME);
			assertTrue(placeholder.isPresent());

			final CatalogMissingException missing = assertThrows(
				CatalogMissingException.class, () -> placeholder.get().getSchema()
			);
			assertNotNull(missing.getMessage());
			assertTrue(
				missing.getMessage().contains(CATALOG_NAME),
				"CatalogMissingException message should embed the catalog name for operator visibility."
			);
			assertEquals(CATALOG_NAME, missing.getCatalogName());
		}

		@Test
		@DisplayName("getCatalogState returns MISSING without throwing — observability path")
		void shouldExposeMissingStateViaGetCatalogState() throws Exception {
			// The placeholder must answer `getCatalogState()` and `getName()` without throwing, otherwise
			// engine-level introspection (CDC, list-catalogs API) could not even report the MISSING
			// status. The cause function only fires for data-serving methods.
			final CatalogContract aliveCatalog = mock(CatalogContract.class);
			when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
			when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

			final ExpandedEngineState startingState =
				buildStartingState(aliveCatalog, true, false, false);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final ExpandedEngineState afterMissing =
				applyMissingMutation(startingState, transitionUpdater, completionUpdater);
			final Optional<CatalogContract> placeholder = afterMissing.getCatalog(CATALOG_NAME);
			assertTrue(placeholder.isPresent());

			// These two accessors are explicitly safe on UnusableCatalog and constitute the bare-minimum
			// observability surface MISSING catalogs expose to operators.
			assertEquals(CatalogState.MISSING, placeholder.get().getCatalogState());
			assertEquals(CATALOG_NAME, placeholder.get().getName());
		}

	}

	@Test
	@DisplayName("applyMutation should return a non-null ProgressingFuture that completes with null")
	void shouldReturnFutureCompletingWithNull() throws Exception {
		// Even though MISSING is a single-phase operator with no work to do, the contract requires
		// `applyMutation` to return a non-null `ProgressingFuture<Void>` that completes successfully —
		// downstream callers chain on it.
		final CatalogContract aliveCatalog = mock(CatalogContract.class);
		when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
		when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

		final ExpandedEngineState startingState =
			buildStartingState(aliveCatalog, true, false, false);
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
			mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
			mock(Consumer.class);

		final MarkCatalogMissingMutationOperator operator =
			new MarkCatalogMissingMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));
		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(),
			new MarkCatalogMissingMutation(CATALOG_NAME),
			evita,
			transitionUpdater,
			completionUpdater
		);

		assertNotNull(future);
		future.execute(Executors.newSingleThreadExecutor());
		assertNull(future.get(5, TimeUnit.SECONDS));
	}

}
