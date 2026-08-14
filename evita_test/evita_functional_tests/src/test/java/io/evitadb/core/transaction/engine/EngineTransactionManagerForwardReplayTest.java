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

package io.evitadb.core.transaction.engine;


import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.engine.DefaultEnginePersistenceService;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static io.evitadb.spi.store.engine.EnginePersistenceService.STORAGE_PROTOCOL_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Tests the forward WAL replay path of `EngineTransactionManager`.
 *
 * Covers two regressions:
 *
 * 1. `truncateWalFile` was being invoked BEFORE `replayCrashedMutationIfNeeded`, so the WAL entry
 * at `walV = stateV + 1` was deleted before the replay logic had a chance to read it. The
 * replay then saw an empty WAL at the target version and silently lost the committed mutation.
 * 2. After a replay fell into an unsupported-mutation path (`replayCompletionState` returning
 * `Optional.empty()`), the engine continued accepting subsequent `applyMutation` calls.
 * Because `lastStoredEngineStateVersion` still reflected the pre-crash version while the WAL
 * had already advanced, the next append computed a colliding version and either overwrote the
 * crashed record or failed with a confusing downstream error. The correct behaviour is to
 * wedge the engine at the transaction-manager layer and refuse further mutations until an
 * operator reconciles the state manually.
 *
 * The tests boot the transaction manager with a real `DefaultEnginePersistenceService` backed by
 * a `@TempDir` — the service is the only component whose crash-recovery behaviour we need to
 * exercise truthfully. The surrounding `Evita`, `SystemChangeObserver` and executor wiring is
 * mocked to keep the fixture minimal and the failure mode precise.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EngineTransactionManager forward WAL replay")
@Tag(ENGINE)
@Tag(TRANSACTION)
class EngineTransactionManagerForwardReplayTest implements EvitaTestSupport {
	private TestPaths paths;
	private StorageOptions storageOptions;
	private TransactionOptions transactionOptions;
	private Scheduler scheduler;
	@Nullable private DefaultEnginePersistenceService persistenceService;

	/**
	 * Builds the minimal `ExpandedEngineState` snapshot that reflects the current on-disk
	 * `EngineState` — no live catalogs are required because the test mutations only touch the
	 * missing/active arrays.
	 *
	 * @param engineState persisted snapshot read from disk
	 * @return an `ExpandedEngineState` that wraps `engineState` with an empty catalogs map
	 */
	@Nonnull
	private static ExpandedEngineState wrapAsExpanded(@Nonnull EngineState<?> engineState) {
		@SuppressWarnings({"rawtypes", "unchecked"}) final EngineState<io.evitadb.spi.store.catalog.shared.model.LogRecordReference> typedState =
			(EngineState) engineState;
		return ExpandedEngineState.create(typedState, Collections.emptyMap());
	}

	/**
	 * Builds a mock `Evita` that exposes only the fields `EngineTransactionManager` reads during
	 * construction: configuration (storage directory + transaction timeout) and the engine state.
	 * `setNextEngineState` updates the supplied reference so replay can observe the replayed
	 * snapshot.
	 *
	 * @param storageDirectory storage root of the backing persistence service
	 * @param initialState     initial expanded engine state to return from `getEngineState()`
	 * @param lastSetState     sink updated by `setNextEngineState(...)`; holds the latest snapshot
	 * @return a partial `Evita` mock suitable for driving the transaction manager constructor
	 */
	@Nonnull
	private static Evita mockEvita(
		@Nonnull Path storageDirectory,
		@Nonnull ExpandedEngineState initialState,
		@Nonnull AtomicReference<ExpandedEngineState> lastSetState
	) {
		lastSetState.set(initialState);
		final StorageOptions storage = StorageOptions.builder().storageDirectory(storageDirectory).build();
		final ServerOptions server = ServerOptions.builder().transactionTimeoutInMilliseconds(5_000L).build();
		final EvitaConfiguration configuration = mock(EvitaConfiguration.class);
		when(configuration.storage()).thenReturn(storage);
		when(configuration.server()).thenReturn(server);
		final Evita evita = mock(Evita.class);
		when(evita.getConfiguration()).thenReturn(configuration);
		when(evita.getCatalogFolderContext()).thenReturn(TestCatalogFolderContexts.onDirectory(storageDirectory));
		when(evita.getEngineState()).thenAnswer(invocation -> lastSetState.get());
		doAnswer(invocation -> {
			lastSetState.set(invocation.getArgument(0, ExpandedEngineState.class));
			return null;
		}).when(evita).setNextEngineState(any(ExpandedEngineState.class));
		return evita;
	}

	/**
	 * Linear scan over the catalogs array — the order is not part of the contract we verify here
	 * so a cheap `equals` loop is sufficient and avoids the overhead of building a `Set`.
	 */
	private static boolean containsCatalog(@Nonnull String[] catalogs, @Nonnull String needle) {
		for (final String candidate : catalogs) {
			if (needle.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(this.getClass().getSimpleName());
		assertTrue(this.paths.storage().toFile().mkdirs(), "Cannot create test storage directory.");
		assertTrue(this.paths.work().toFile().mkdirs(), "Cannot create test work directory.");
		this.storageOptions = StorageOptions.builder()
			.storageDirectory(this.paths.storage())
			.workDirectory(this.paths.work())
			.build();
		this.transactionOptions = TransactionOptions.builder()
			.transactionMemoryBufferLimitSizeBytes(1024 << 10)
			.transactionMemoryRegionCount(4)
			.build();
		this.scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
		this.persistenceService = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		if (this.persistenceService != null && !this.persistenceService.isClosed()) {
			this.persistenceService.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should replay crashed mutation before truncating WAL so the committed record is not lost")
	void shouldReplayCrashedMutationBeforeTruncatingWal() {
		// Step 1 — set up a pre-crash state with `walV == stateV + 1`.
		//
		// We first commit a real mutation at v2 via the fused primitive so the bootstrap file has a
		// `walReference` that points to the end of the v2 record. We then append v3 via the
		// non-fused helper so the WAL advances to v3 while the bootstrap stays pinned at v2 with
		// its old walReference (ending at v2). This is precisely the shape `appendWalAndStoreState`
		// leaves behind when the OS crashes between the WAL append and the bootstrap rewrite.
		appendCommittedMutationAtVersion2("catalogA");
		this.persistenceService.appendWal(
			3L, UUID.randomUUID(), new MarkCatalogMissingMutation("catalogB")
		);
		this.persistenceService.close();

		// Step 2 — reopen the persistence service; it must now report walV=3, stateV=2.
		this.persistenceService = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		);
		assertEquals(
			2L, this.persistenceService.getEngineState().version(),
			"Bootstrap must still be at the pre-crash version before replay runs."
		);
		assertEquals(
			3L, this.persistenceService.getLastVersionInMutationStream(),
			"WAL must already contain the committed v3 record before replay runs."
		);

		// Step 3 — boot the transaction manager. Without the fix, `truncateWalFile` runs first and
		// shreds the v3 record (the bootstrap's walReference ends at v2), so the subsequent
		// `replayCrashedMutationIfNeeded` call finds an empty WAL at v3 and wedges the engine at
		// v2. With the fix, replay runs first, reads v3 from the WAL, reconciles the bootstrap to
		// v3, and `truncateWalFile` then becomes a no-op.
		final ExpandedEngineState initialState = wrapAsExpanded(this.persistenceService.getEngineState());
		final AtomicReference<ExpandedEngineState> lastSetState = new AtomicReference<>();
		final Evita evita = mockEvita(this.paths.storage(), initialState, lastSetState);
		final SystemChangeObserver changeObserver = mock(SystemChangeObserver.class);
		final ObservableExecutorService executor = mock(ObservableExecutorService.class);

		@SuppressWarnings({"rawtypes"}) final EnginePersistenceService rawService = this.persistenceService;

		//noinspection unchecked
		try (
			EngineTransactionManager manager = new EngineTransactionManager(
				evita, changeObserver, executor, rawService
			)
		) {
			// The persistence service must now be caught up at v3 — both the bootstrap file and the
			// WAL must agree and the replay must have pushed the catalog into `missingCatalogs`.
			final EngineState<LogFileRecordReference> persisted = this.persistenceService.getEngineState();
			assertEquals(
				3L, persisted.version(),
				"Forward replay must advance the bootstrap to walV; without the fix the WAL entry at v3 is "
					+ "truncated before replay reads it and the bootstrap is left at v2."
			);
			assertEquals(
				3L, this.persistenceService.getLastVersionInMutationStream(),
				"WAL must still report the committed v3 record after replay; if it reports less the "
					+ "committed mutation was truncated and silently lost."
			);
			final String[] missing = persisted.missingCatalogs();
			assertTrue(
				containsCatalog(missing, "catalogA") && containsCatalog(missing, "catalogB"),
				"Replayed engine state must keep both committed MarkCatalogMissing entries. Actual: "
					+ String.join(", ", missing)
			);
		} finally {
			this.persistenceService = null;
		}

		assertStorageIsRestartable();
	}

	@Test
	@DisplayName("should refuse further mutations when forward replay is unsupported for crashed mutation")
	void shouldRefuseFurtherMutationsWhenForwardReplayUnsupported() {
		// `CreateCatalogSchemaMutation` has no `replayCompletionState` override — its operator
		// returns the default `Optional.empty()`, which signals "replay not supported". When the
		// transaction manager encounters this during startup it must wedge and refuse subsequent
		// `applyMutation` calls; otherwise the next append would attempt to write at the same
		// version as the crashed WAL entry and either overwrite it or fail with a confusing error.
		this.persistenceService.appendWal(
			2L, UUID.randomUUID(), new CreateCatalogSchemaMutation("unsupportedCatalog")
		);
		this.persistenceService.close();

		// Reopen — the persistence service boots fine (D.1 permits walV == stateV + 1).
		this.persistenceService = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		);

		final ExpandedEngineState initialState = wrapAsExpanded(this.persistenceService.getEngineState());
		final AtomicReference<ExpandedEngineState> lastSetState = new AtomicReference<>();
		final Evita evita = mockEvita(this.paths.storage(), initialState, lastSetState);
		final SystemChangeObserver changeObserver = mock(SystemChangeObserver.class);
		final ObservableExecutorService executor = mock(ObservableExecutorService.class);

		@SuppressWarnings({"rawtypes"}) final EnginePersistenceService rawService = this.persistenceService;

		//noinspection unchecked
		try (
			EngineTransactionManager manager = new EngineTransactionManager(
				evita, changeObserver, executor, rawService
			)
		) {
			// The replay must have logged the unsupported-replay error and left the bootstrap at v1.
			assertEquals(1L, this.persistenceService.getEngineState().version());

			// Any subsequent mutation must be rejected loudly. Without the fix the call would either
			// succeed (silently overwriting the crashed WAL entry) or fail inside the persistence
			// layer with a version-collision error whose root cause is hard to identify.
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> manager.applyMutation(
					new MarkCatalogMissingMutation("anyCatalog"),
					null
				)
			);
			final String message = error.getMessage();
			assertTrue(
				message != null && message.toLowerCase().contains("wedg"),
				"Expected a wedged-engine error message, got: " + message
			);
		} finally {
			this.persistenceService = null;
		}
	}

	@Test
	@DisplayName("should replay a crashed catalog removal rather than wedging, leaving the folder tombstoned")
	void shouldReplayCrashedCatalogRemoval() {
		// A removal used to be unreplayable because its completion phase wiped the folder, and recovery had no
		// way to tell how far that wipe had got. Committing a tombstone instead makes the completion state pure
		// bookkeeping - a name removal and a tombstone, both idempotent - so a crash between the WAL append and
		// the bootstrap rewrite stops bricking startup.
		appendCommittedStateWithBoundCatalog("catalogA", "catalogA_1");
		this.persistenceService.appendWal(
			3L, UUID.randomUUID(), new RemoveCatalogSchemaMutation("catalogA")
		);
		this.persistenceService.close();

		this.persistenceService = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		);

		final ExpandedEngineState initialState = wrapAsExpanded(this.persistenceService.getEngineState());
		final AtomicReference<ExpandedEngineState> lastSetState = new AtomicReference<>();
		final Evita evita = mockEvita(this.paths.storage(), initialState, lastSetState);
		final SystemChangeObserver changeObserver = mock(SystemChangeObserver.class);
		final ObservableExecutorService executor = mock(ObservableExecutorService.class);

		@SuppressWarnings({"rawtypes"}) final EnginePersistenceService rawService = this.persistenceService;

		//noinspection unchecked
		try (
			EngineTransactionManager manager = new EngineTransactionManager(
				evita, changeObserver, executor, rawService
			)
		) {
			final EngineState<LogFileRecordReference> persisted = this.persistenceService.getEngineState();
			// reaching v3 at all is the assertion that replay ran: a wedge would leave the bootstrap at v2
			assertEquals(
				3L, persisted.version(),
				"Forward replay must advance the bootstrap to walV; a wedged engine leaves it at v2."
			);
			assertFalse(
				containsCatalog(persisted.activeCatalogs(), "catalogA"),
				"The replayed state must no longer carry the removed catalog."
			);
			assertEquals(
				1, persisted.retiredFolders().length,
				"The replayed state must carry the tombstone the completion updater would have staged."
			);
			assertEquals("catalogA_1", persisted.retiredFolders()[0].folderId().id());
			// the wipe itself is deliberately NOT re-attempted here - the tombstone is the instruction, and the
			// boot drain acts on it
		} finally {
			this.persistenceService = null;
		}

		assertStorageIsRestartable();
	}

	/**
	 * Reopens the storage from scratch and asserts the two version counters agree, which is what makes the next
	 * boot possible at all.
	 *
	 * **Every replay test ends with this, and none of them may substitute an assertion made through the service
	 * that just ran.** A live handle answers from its own counters, so it reports the state the code intended;
	 * only a reopen reports what the next process will actually find. That gap hid a real defect for as long as
	 * this test existed: `shouldReplayCrashedMutationBeforeTruncatingWal` asserted the recovered record was still
	 * in the WAL, and passed, while on disk the truncation that followed the replay had already cut it away. The
	 * bootstrap was left one version ahead of the log — a combination `DefaultEnginePersistenceService` accepts
	 * nowhere — so the following boot refused to start, provided nothing had committed in between to close the gap.
	 */
	private void assertStorageIsRestartable() {
		try (final DefaultEnginePersistenceService reopened = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		)) {
			assertEquals(
				reopened.getEngineState().version(), reopened.getLastVersionInMutationStream(),
				"After a replay the bootstrap and the WAL must agree, or the next boot refuses to start!"
			);
		}
	}

	/**
	 * Primes the storage directory with a committed state at version 2 in which one catalog is active and bound
	 * to a folder, which is the precondition a removal needs.
	 *
	 * The mutation payload at v2 is immaterial - the bootstrap's `walReference` covers that record, so it is
	 * never replayed; only the `EngineState` it publishes matters.
	 *
	 * @param catalogName name of the catalog to register as active
	 * @param folderName  folder token to bind it to
	 */
	private void appendCommittedStateWithBoundCatalog(
		@Nonnull String catalogName,
		@Nonnull String folderName
	) {
		this.persistenceService.appendWalAndStoreState(
			2L,
			UUID.randomUUID(),
			new MarkCatalogMissingMutation(catalogName),
			txRef -> EngineState.<LogFileRecordReference>builder()
				.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
				.version(2L)
				.introducedAt(OffsetDateTime.now())
				.walFileReference((LogFileRecordReference) txRef.walReference())
				.activeCatalogs(new String[]{catalogName})
				.inactiveCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.readOnlyCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.missingCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.catalogFolders(
					new CatalogFolderBinding[]{
						new CatalogFolderBinding(catalogName, new CatalogFolderId(folderName))
					}
				)
				.build()
		);
	}

	/**
	 * Primes the storage directory with exactly one committed mutation at version 2 via the fused
	 * WAL-first primitive. After this call both the WAL and the bootstrap file agree at version 2
	 * and the bootstrap's `walReference` points to the end of the freshly appended record.
	 *
	 * @param catalogName logical name used inside the `MarkCatalogMissingMutation` payload
	 */
	private void appendCommittedMutationAtVersion2(@Nonnull String catalogName) {
		this.persistenceService.appendWalAndStoreState(
			2L,
			UUID.randomUUID(),
			new MarkCatalogMissingMutation(catalogName),
			txRef -> EngineState.<LogFileRecordReference>builder()
				.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
				.version(2L)
				.introducedAt(OffsetDateTime.now())
				.walFileReference((LogFileRecordReference) txRef.walReference())
				.activeCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.inactiveCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.readOnlyCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
				.missingCatalogs(new String[]{catalogName})
				.build()
		);
	}

}
