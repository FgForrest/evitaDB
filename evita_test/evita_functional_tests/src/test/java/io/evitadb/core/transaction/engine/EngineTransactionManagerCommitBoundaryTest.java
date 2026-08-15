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
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ServerModifyCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.engine.DefaultEnginePersistenceService;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests where the engine commit's **durability boundary** falls, and what a failure on either side of it is
 * allowed to mean.
 *
 * `appendWalAndStoreState` is the boundary: it appends the write-ahead log record and rewrites the bootstrap in
 * one critical section, and once it returns the mutation survives a restart whatever happens next. The work that
 * follows it - notifying change-capture subscribers, discharging tombstones, retiring generation counters - can
 * still fail, and two things must hold when it does.
 *
 * **The in-memory publish must not be lost.** `SystemChangeObserver#processMutation` throws when the observer has
 * closed underneath an in-flight operation, and it runs before the publish. Left unguarded, that leaves durable
 * state ahead of the state every reader resolves against - a split brain no caller can compensate for, and one
 * that no restart reports, because the restart simply reads the durable half and looks fine. It also strands the
 * version counter, so the next mutation would append at a version the log already holds.
 *
 * **The failure must not be reported.** A caller told "this failed" would be told something untrue and would act
 * on it: an operator undoes bookkeeping the durable state already records, and a client retries an operation that
 * has already happened. Reporting costs something and recovers nothing - the capture the observer refused is lost
 * either way, because `processMutation` is what fills the buffer a subscriber would replay from. So everything
 * past the boundary is best-effort and logged, which is the same rule the operators apply to their own
 * post-commit work. See `ModifyCatalogSchemaNameMutationOperator`, whose failure path is only able to choose
 * between two answers because no committed operation can reach it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The engine commit's durability boundary")
@Tag(ENGINE)
@Tag(TRANSACTION)
class EngineTransactionManagerCommitBoundaryTest implements EvitaTestSupport {
	private static final String CATALOG_NAME = "someCatalog";

	private TestPaths paths;
	private StorageOptions storageOptions;
	private TransactionOptions transactionOptions;
	private Scheduler scheduler;
	@Nullable private DefaultEnginePersistenceService persistenceService;

	/**
	 * Wraps a persisted snapshot as the expanded state the transaction manager reads, with no live catalogs -
	 * the mutation driven below touches nothing but the engine version.
	 *
	 * @param engineState persisted snapshot read from disk
	 * @return an `ExpandedEngineState` wrapping it with an empty catalogs map
	 */
	@Nonnull
	private static ExpandedEngineState wrapAsExpanded(@Nonnull EngineState<?> engineState) {
		@SuppressWarnings({"rawtypes", "unchecked"})
		final EngineState<LogRecordReference> typedState = (EngineState) engineState;
		return ExpandedEngineState.create(typedState, Collections.emptyMap());
	}

	/**
	 * Builds a mock `Evita` exposing only what the transaction manager reads, with `setNextEngineState` routed
	 * into the supplied sink so the test can tell whether the in-memory publish happened.
	 *
	 * @param storageDirectory storage root of the backing persistence service
	 * @param initialState     initial expanded engine state to answer `getEngineState()` with
	 * @param lastSetState     sink updated by `setNextEngineState(...)`
	 * @return a partial `Evita` mock sufficient to drive one engine mutation
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
		// `ModifyCatalogSchemaMutation#verifyApplicability` refuses a catalog the engine does not list, and that
		// check runs before the commit this test is about ever starts.
		when(evita.getCatalogNames()).thenReturn(Set.of(CATALOG_NAME));
		when(evita.getConfiguration()).thenReturn(configuration);
		when(evita.getCatalogFolderContext()).thenReturn(TestCatalogFolderContexts.onDirectory(storageDirectory));
		when(evita.getEngineState()).thenAnswer(invocation -> lastSetState.get());
		doAnswer(invocation -> {
			lastSetState.set(invocation.getArgument(0, ExpandedEngineState.class));
			return null;
		}).when(evita).setNextEngineState(any(ExpandedEngineState.class));
		return evita;
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
	@DisplayName("should publish the committed state and not report failure when an observer refuses it")
	void shouldPublishAndNotReportFailureWhenTheObserverRefusesACommittedMutation() {
		final ExpandedEngineState initialState = wrapAsExpanded(this.persistenceService.getEngineState());
		final long versionBefore = initialState.version();
		final AtomicReference<ExpandedEngineState> lastSetState = new AtomicReference<>();
		final Evita evita = mockEvita(this.paths.storage(), initialState, lastSetState);

		// Stands in for the one failure this window really produces: the observer closing underneath an
		// in-flight operation, which `assertActive` turns into a throw on purpose (issue #1151). It fires after
		// the write-ahead log record has been appended and the bootstrap rewritten, so the mutation it refuses
		// is one that has already happened.
		final SystemChangeObserver changeObserver = mock(SystemChangeObserver.class);
		doThrow(new GenericEvitaInternalError("The change observer has already closed!"))
			.when(changeObserver).processMutation(any());
		final ObservableExecutorService executor = mock(ObservableExecutorService.class);

		@SuppressWarnings({"rawtypes"}) final EnginePersistenceService rawService = this.persistenceService;

		//noinspection unchecked
		try (
			EngineTransactionManager manager = new EngineTransactionManager(
				evita, changeObserver, executor, rawService
			)
		) {
			// `ServerModifyCatalogSchemaMutation` is the one engine mutation that reaches the commit without an
			// operator and without a live catalog - it only advances the engine version - which is exactly the
			// bare commit this test is about.
			assertDoesNotThrow(
				() -> manager.applyMutation(
					new ServerModifyCatalogSchemaMutation(
						1L, 1, new ModifyCatalogSchemaMutation(CATALOG_NAME, UUID.randomUUID())
					),
					null
				),
				"A mutation that is already durable must not be reported as failed because a change observer " +
					"refused it. The caller would undo bookkeeping the durable state already records, or retry an " +
					"operation that has already happened - and neither redelivers the capture that was lost."
			);

			final ExpandedEngineState published = lastSetState.get();
			assertNotNull(published, "The engine state must have been published in memory.");
			assertEquals(
				versionBefore + 1, published.version(),
				"The in-memory publish must survive an observer that refused the mutation. Skipped, it leaves " +
					"durable state ahead of the state every reader resolves against - and nothing reports that, " +
					"because a restart reads the durable half and looks healthy."
			);
		} finally {
			this.persistenceService.close();
			this.persistenceService = null;
		}

		// Read back through a fresh service rather than the one that just ran: a live handle answers from its
		// own counters, so it reports what the code intended rather than what the next process will find.
		try (final DefaultEnginePersistenceService reopened = new DefaultEnginePersistenceService(
			this.storageOptions, this.transactionOptions, this.scheduler
		)) {
			assertEquals(
				versionBefore + 1, reopened.getEngineState().version(),
				"The mutation was durable before the observer refused it, so the next boot must find it."
			);
		}
	}

}
