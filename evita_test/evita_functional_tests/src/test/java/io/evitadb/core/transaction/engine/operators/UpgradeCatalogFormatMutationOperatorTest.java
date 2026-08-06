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
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Tests `UpgradeCatalogFormatMutationOperator` — the state-transition driver for the per-catalog storage-protocol
 * upgrade introduced by.
 *
 * Scope: the operator is tested in isolation (without booting a full Evita runtime). The operator's contract is to
 * drive the catalog through `<prior state> → BEING_UPGRADED → <prior state>` via the two engine-state updaters it
 * receives; the real upgrade work-phase is delegated to an injected `UpgradeExecutor` so the operator body stays
 * small and deterministic. These tests exercise that contract by capturing both updaters and applying them to a
 * synthetic `ExpandedEngineState`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UpgradeCatalogFormatMutationOperator tests")
@Tag(ENGINE)
@Tag(TRANSACTION)
class UpgradeCatalogFormatMutationOperatorTest {

	private static final String CATALOG_NAME = "upgradableCatalog";
	private static final Path STORAGE_DIRECTORY = Paths.get("target", "upgrade-operator-test");

	/**
	 * Builds a minimal `ExpandedEngineState` whose only active catalog is the supplied one. Used as the starting
	 * state for both updater phases so the transition and completion updates are applied to a representative input.
	 */
	@Nonnull
	private static ExpandedEngineState buildStartingState(@Nonnull CatalogContract catalog) {
		final EngineState<LogRecordReference> engineState = new EngineState<>(
			1,
			1L,
			OffsetDateTime.now(),
			null,
			new String[]{catalog.getName()},
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY
		);
		final Map<String, CatalogContract> catalogs = new HashMap<>();
		catalogs.put(catalog.getName(), catalog);
		return ExpandedEngineState.create(engineState, catalogs);
	}

	@Test
	@DisplayName("should transition catalog through BEING_UPGRADED and land back at prior state")
	void shouldTransitionCatalogThroughBeingUpgradedAndBack() throws Exception {
		// Prepare a catalog in ALIVE that will be "upgraded".
		final CatalogContract aliveCatalog = mock(CatalogContract.class);
		when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
		when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

		final ExpandedEngineState startingState = buildStartingState(aliveCatalog);
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		// A recording upgrade executor verifies the work phase is driven through the injected seam rather than
		// through any eager Migration_* code.
		final AtomicInteger upgradeInvocationCount = new AtomicInteger();
		final UpgradeExecutor recordingExecutor = catalogName -> {
			assertEquals(CATALOG_NAME, catalogName);
			upgradeInvocationCount.incrementAndGet();
		};

		final UpgradeCatalogFormatMutationOperator operator =
			new UpgradeCatalogFormatMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY), recordingExecutor);

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater = mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);

		final UpgradeCatalogFormatMutation mutation = new UpgradeCatalogFormatMutation(CATALOG_NAME, 4, 5);
		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(),
			mutation,
			evita,
			transitionUpdater,
			completionUpdater
		);

		// Transition phase must have been invoked synchronously with a BEING_UPGRADED placeholder.
		final ArgumentCaptor<EngineStateUpdater> transitionCaptor = ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(transitionUpdater).accept(transitionCaptor.capture());
		final ExpandedEngineState afterTransition = transitionCaptor.getValue().apply(2L, startingState);
		final Optional<CatalogContract> transitionCatalog = afterTransition.getCatalog(CATALOG_NAME);
		assertTrue(transitionCatalog.isPresent());
		assertInstanceOf(UnusableCatalog.class, transitionCatalog.get());
		assertEquals(CatalogState.BEING_UPGRADED, transitionCatalog.get().getCatalogState());
		assertEquals(2L, afterTransition.version());

		// Execute the future — this triggers the work phase (recording executor) and the completion updater.
		// A direct executor would suffice; a single-thread pool is used so the completion is deterministic without
		// racing the verify() call below.
		future.execute(Executors.newSingleThreadExecutor());
		final Void result = future.get(5, TimeUnit.SECONDS);
		assertNull(result);
		assertEquals(1, upgradeInvocationCount.get(), "Upgrade executor must be invoked exactly once.");

		// Completion phase must have been invoked with an updater that restores the original reference.
		final ArgumentCaptor<EngineStateUpdater> completionCaptor = ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(completionUpdater).accept(completionCaptor.capture());
		final ExpandedEngineState afterCompletion = completionCaptor.getValue().apply(3L, afterTransition);
		final Optional<CatalogContract> restoredCatalog = afterCompletion.getCatalog(CATALOG_NAME);
		assertTrue(restoredCatalog.isPresent());
		// Reference equality — the completion updater must restore the exact catalog instance we captured so the
		// catalog lands back in its prior operational state (`ALIVE`, because the mock returns ALIVE).
		assertSame(aliveCatalog, restoredCatalog.get());
		assertEquals(CatalogState.ALIVE, restoredCatalog.get().getCatalogState());
		assertEquals(3L, afterCompletion.version());
	}

	@Test
	@DisplayName("should expose a human-readable operation name embedding catalog and protocol versions")
	void shouldProduceDescriptiveOperationName() {
		// The operation name is surfaced in progress reports and CDC notifications; both `from` and
		// `to` versions must appear so operators can identify what migration is in flight.
		final UpgradeCatalogFormatMutationOperator operator =
			new UpgradeCatalogFormatMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));
		final UpgradeCatalogFormatMutation mutation = new UpgradeCatalogFormatMutation(CATALOG_NAME, 4, 5);

		assertEquals(
			"Upgrading catalog `" + CATALOG_NAME + "` from protocol v4 to v5",
			operator.getOperationName(mutation)
		);
	}

	@Test
	@DisplayName("transition phase should keep the catalog name in activeCatalogs (crash-safe retry invariant)")
	void shouldPreserveActiveBucketDuringTransition() {
		// Guards the core crash-safety property of : while the upgrade is mid-flight, the catalog name
		// must remain in the same persisted bucket it occupied before. If a crash happens mid-work-phase, the
		// next boot sees the name still in activeCatalogs, reloads the (still-v4) catalog, and the
		// `verifyAndUpgradeStorageFormat` + retry-hook pair re-issues the upgrade mutation. Moving the name
		// to inactiveCatalogs mid-transition (as `withCatalog(UnusableCatalog)` would do) would break that loop.
		final CatalogContract aliveCatalog = mock(CatalogContract.class);
		when(aliveCatalog.getName()).thenReturn(CATALOG_NAME);
		when(aliveCatalog.getCatalogState()).thenReturn(CatalogState.ALIVE);

		final ExpandedEngineState startingState = buildStartingState(aliveCatalog);
		// Sanity-check the starting state has the catalog in activeCatalogs — the buildStartingState helper
		// places it there; explicit assertion documents the precondition so the test stays meaningful if the
		// helper changes later.
		assertEquals(1, startingState.engineState().activeCatalogs().length);
		assertEquals(CATALOG_NAME, startingState.engineState().activeCatalogs()[0]);
		assertEquals(0, startingState.engineState().inactiveCatalogs().length);

		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		final UpgradeCatalogFormatMutationOperator operator =
			new UpgradeCatalogFormatMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater = mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);

		final UpgradeCatalogFormatMutation mutation = new UpgradeCatalogFormatMutation(CATALOG_NAME, 4, 5);
		operator.applyMutation(UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater);

		// Capture the transition updater and apply it to the starting state to observe the resulting snapshot.
		final ArgumentCaptor<EngineStateUpdater> transitionCaptor = ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(transitionUpdater).accept(transitionCaptor.capture());
		final ExpandedEngineState afterTransition = transitionCaptor.getValue().apply(2L, startingState);

		// Primary invariant: the catalog name must remain in activeCatalogs and must NOT appear in
		// inactiveCatalogs, even though the in-memory reference has been swapped for a BEING_UPGRADED placeholder.
		assertEquals(1, afterTransition.engineState().activeCatalogs().length);
		assertEquals(CATALOG_NAME, afterTransition.engineState().activeCatalogs()[0]);
		assertEquals(0, afterTransition.engineState().inactiveCatalogs().length);

		// Secondary invariant: the in-memory catalogs map must now serve a BEING_UPGRADED placeholder so any
		// concurrent access fails fast with a transient error instead of racing the upgrade.
		final Optional<CatalogContract> transitionCatalog = afterTransition.getCatalog(CATALOG_NAME);
		assertTrue(transitionCatalog.isPresent());
		assertInstanceOf(UnusableCatalog.class, transitionCatalog.get());
		assertEquals(CatalogState.BEING_UPGRADED, transitionCatalog.get().getCatalogState());
	}

	@Test
	@DisplayName(
		"completion phase should keep catalog in activeCatalogs when priorCatalog is an UnusableCatalog"
	)
	void shouldPreserveActiveBucketAfterCompletionPhaseOnBootPath() throws Exception {
		// Scenario — mirrors the boot path described in the retry flow: the engine has
		// already registered the catalog name in `activeCatalogs`, but the load hasn't yet installed
		// a live `Catalog`, so the engine state still holds an `UnusableCatalog` placeholder in the
		// `BEING_ACTIVATED` state. When the auto-issued `UpgradeCatalogFormatMutation` runs over that
		// snapshot the completion updater must not move the name into `inactiveCatalogs` just because
		// `priorCatalog instanceof Catalog` is false.
		final CatalogContract priorCatalog = TestCatalogFolderContexts
			.onDirectory(STORAGE_DIRECTORY)
			.createUnusableCatalog(
				CATALOG_NAME,
				CatalogState.BEING_ACTIVATED,
				(cn, folderId, root) -> new IllegalStateException("unused in test")
			);

		final ExpandedEngineState startingState = buildStartingState(priorCatalog);
		assertEquals(1, startingState.engineState().activeCatalogs().length);
		assertEquals(CATALOG_NAME, startingState.engineState().activeCatalogs()[0]);
		assertEquals(0, startingState.engineState().inactiveCatalogs().length);

		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(startingState);

		final UpgradeCatalogFormatMutationOperator operator =
			new UpgradeCatalogFormatMutationOperator(TestCatalogFolderContexts.onDirectory(STORAGE_DIRECTORY));

		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater = mock(Consumer.class);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);

		final UpgradeCatalogFormatMutation mutation = new UpgradeCatalogFormatMutation(CATALOG_NAME, 4, 5);
		final ProgressingFuture<Void> future = operator.applyMutation(
			UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater
		);

		// Drive the transition phase.
		final ArgumentCaptor<EngineStateUpdater> transitionCaptor = ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(transitionUpdater).accept(transitionCaptor.capture());
		final ExpandedEngineState afterTransition = transitionCaptor.getValue().apply(2L, startingState);

		// Drive the work phase — triggers the completion updater capture.
		future.execute(Executors.newSingleThreadExecutor());
		future.get(5, TimeUnit.SECONDS);

		// Drive the completion phase.
		final ArgumentCaptor<EngineStateUpdater> completionCaptor = ArgumentCaptor.forClass(EngineStateUpdater.class);
		verify(completionUpdater).accept(completionCaptor.capture());
		final ExpandedEngineState afterCompletion = completionCaptor.getValue().apply(3L, afterTransition);

		// After the completion phase the catalog name must still live in `activeCatalogs` and must NOT
		// have been moved to `inactiveCatalogs`. The operator uses `withInFlightPlaceholder` to update
		// only the in-memory catalogs map without touching the active/inactive arrays — so the boot-path
		// auto-retry loop can find the name in its original bucket on the next load pass.
		assertEquals(1, afterCompletion.engineState().activeCatalogs().length);
		assertEquals(CATALOG_NAME, afterCompletion.engineState().activeCatalogs()[0]);
		assertEquals(0, afterCompletion.engineState().inactiveCatalogs().length);
	}
}
