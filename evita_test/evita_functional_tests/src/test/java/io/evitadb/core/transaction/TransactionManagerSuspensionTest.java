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

package io.evitadb.core.transaction;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.transaction.TransactionManager.CatalogSuspension;
import io.evitadb.core.transaction.TransactionManager.ProcessResult;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the suspension contract of {@link TransactionManager}: once a trunk incorporation has failed at or after the
 * point where it began collecting and writing, the catalog must stop processing transactions rather than retry.
 *
 * The failure is driven through a real thrown flush rather than by calling {@link TransactionManager#suspend} directly,
 * because the behaviour that has to be pinned is not the existence of a suspension — it is that the catalog **does not
 * try again**. A retry re-runs the flush against the page baselines the failed attempt already advanced, which is how
 * a transient write failure turns into a corrupt catalog on disk; and when the failure is deterministic instead, the
 * retry merely spins forever. The retry has no good case. Suspending stops the write path while leaving readers alone,
 * which is the whole point: the in-memory catalog is still correct, only its persisted image is not.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionManager — suspension after a failed transaction incorporation")
@Tag(ENGINE)
@Tag(TRANSACTION)
class TransactionManagerSuspensionTest {
	private static final String CATALOG_NAME = "suspensionCatalog";
	private static final long INITIAL_VERSION = 10L;
	/** The version of the single transaction waiting in the WAL — the one whose incorporation is made to fail. */
	private static final long FAILING_VERSION = INITIAL_VERSION + 1;

	private Catalog catalog;
	private TransactionManager transactionManager;

	/**
	 * Builds the transaction mutation the WAL hands out. It carries no mutations of its own: the replay loop is bounded
	 * by {@code getMutationCount()}, so an empty transaction replays trivially and lets the drain reach the flush —
	 * which is the only part of the pipeline these tests are about.
	 *
	 * @return a fresh, empty transaction mutation at {@link #FAILING_VERSION}
	 */
	@Nonnull
	private static CatalogBoundMutation transactionMutation() {
		return new TransactionMutation(UUID.randomUUID(), FAILING_VERSION, 0, 0L, OffsetDateTime.now());
	}

	@BeforeEach
	void setUp() {
		final SealedCatalogSchema catalogSchema = mock(SealedCatalogSchema.class);
		when(catalogSchema.version()).thenReturn(1);
		when(catalogSchema.getConflictResolution()).thenReturn(Optional.empty());

		this.catalog = mock(Catalog.class);
		when(this.catalog.getName()).thenReturn(CATALOG_NAME);
		when(this.catalog.getVersion()).thenReturn(INITIAL_VERSION);
		when(this.catalog.getSchema()).thenReturn(catalogSchema);
		when(this.catalog.getLastCatalogVersionInMutationStream()).thenReturn(INITIAL_VERSION);
		when(this.catalog.getFirstCatalogVersionInMutationStream()).thenReturn(INITIAL_VERSION);
		when(this.catalog.getEntitySchema(anyString())).thenReturn(Optional.empty());
		// a fresh stream per call - a Stream is single-use, and what happens on the SECOND drain is the whole subject
		// of these tests, so handing out an already-consumed one would fake the very result being asserted
		when(this.catalog.getCommittedLiveMutationStream(anyLong(), anyLong()))
			.thenAnswer(invocation -> Stream.of(transactionMutation()));

		final EvitaConfiguration configuration = EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.changeDataCapture(ChangeDataCaptureOptions.builder().enabled(false).build())
					.build()
			)
			.build();
		final Evita evita = mock(Evita.class);
		when(evita.getConfiguration()).thenReturn(configuration);

		final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(synchronousExecutor).execute(any(Runnable.class));

		this.transactionManager = new TransactionManager(
			this.catalog,
			evita,
			mock(Scheduler.class),
			synchronousExecutor,
			synchronousExecutor,
			newCatalog -> {
			},
			INITIAL_VERSION
		);
	}

	/**
	 * Drains the write-ahead log exactly the way the trunk-incorporation task does.
	 *
	 * @return the process result, empty when the drain did nothing
	 */
	@Nonnull
	private Optional<ProcessResult> drain() {
		return this.transactionManager.processTransactions(
			FAILING_VERSION, 1_000L, true, false, version -> {
			}
		);
	}

	/**
	 * Arms the catalog's flush to fail the way a full disk would, counting every attempt that reaches it.
	 *
	 * @return the counter of flush attempts
	 */
	@Nonnull
	private AtomicInteger armFailingFlush() {
		final AtomicInteger flushAttempts = new AtomicInteger();
		doAnswer(invocation -> {
			flushAttempts.incrementAndGet();
			throw new UnexpectedIOException(
				"no space left on device", "The catalog could not be written to disk."
			);
		}).when(this.catalog).flush(anyLong(), any());
		return flushAttempts;
	}

	@Test
	@DisplayName("never attempts an incorporation whose flush failed a second time")
	void shouldNeverRetryTheIncorporationWhoseFlushFailed() {
		final AtomicInteger flushAttempts = armFailingFlush();

		assertThrows(
			UnexpectedIOException.class, this::drain,
			"self-check: the drain must get all the way to the flush and fail there, not somewhere earlier"
		);
		assertEquals(1, flushAttempts.get(), "self-check: the first drain must have reached the flush exactly once");

		// the draining task is rescheduled by every freshly appended transaction, entirely independently of the path
		// that just failed - so the gate has to stop the drain STARTING, and it has to do so by returning empty rather
		// than throwing, or the task would reschedule itself forever instead of pausing
		assertTrue(drain().isEmpty(), "a drain after a failed incorporation must do nothing at all");
		assertEquals(
			1, flushAttempts.get(),
			"the failed incorporation must never be attempted again: the second flush would diff its page baselines " +
				"against the ones the failed flush already advanced, which is exactly what corrupts the catalog"
		);
	}

	@Test
	@DisplayName("still retries a failure that happened before the collect began, and does not suspend")
	void shouldKeepRetryingWhenTheFailureHappenedBeforeTheCollectBegan() {
		// a replay failure strikes before a single byte is written and before any page baseline moves, so it leaves
		// nothing behind for a later flush to diff against - it stays safely retryable, and taking the catalog down
		// for it would turn a recoverable hiccup into an outage
		doThrow(new GenericEvitaInternalError("the write-ahead log tail could not be read"))
			.when(this.catalog).setVersion(anyLong());

		assertThrows(GenericEvitaInternalError.class, this::drain);

		assertTrue(
			this.transactionManager.getSuspension().isEmpty(),
			"a pre-collect failure must not suspend: the scope is positional (where it failed), never by exception type"
		);
		assertThrows(
			GenericEvitaInternalError.class, this::drain,
			"...and the catalog must still be willing to try again - the gate must not have closed on it"
		);
	}

	@Test
	@DisplayName("reports a pre-durability failure as leaving disk intact at the version still being served")
	void shouldReportAPreDurabilityFailureWithTheVersionPair() {
		armFailingFlush();
		// nothing was ever persisted, so the flush threw before its bytes landed
		when(this.catalog.getLastPersistedCatalogVersion()).thenReturn(INITIAL_VERSION);

		assertThrows(UnexpectedIOException.class, this::drain);

		final CatalogSuspension suspension = this.transactionManager.getSuspension().orElseThrow();
		assertEquals(
			FAILING_VERSION, suspension.failedCatalogVersion(),
			"the operator needs the version whose incorporation failed"
		);
		assertEquals(
			INITIAL_VERSION, suspension.servingCatalogVersion(),
			"...paired with the version readers are still being served, which tells reload apart from restore"
		);
		assertFalse(
			suspension.durable(),
			"the write never landed, so disk is still at the serving version and a reload recovers cleanly"
		);
		assertInstanceOf(
			UnexpectedIOException.class, suspension.cause(),
			"the suspension must carry the failure that actually broke it, so the alert can name the cause"
		);
	}

	@Test
	@DisplayName("reports a post-durability failure as leaving a suspect version on disk")
	void shouldReportAPostDurabilityFailureWithTheVersionPair() {
		armFailingFlush();
		// the failing version reached disk before the incorporation broke down - the asymmetric, worse case: memory
		// serves N-1 while disk holds a suspect N, so a reload lands ON the suspect version rather than away from it
		when(this.catalog.getLastPersistedCatalogVersion()).thenReturn(FAILING_VERSION);

		assertThrows(UnexpectedIOException.class, this::drain);

		final CatalogSuspension suspension = this.transactionManager.getSuspension().orElseThrow();
		assertTrue(
			suspension.durable(),
			"the failing version is already on disk: this is restore/repair territory, not a reload"
		);
		assertEquals(FAILING_VERSION, suspension.failedCatalogVersion());
		assertEquals(INITIAL_VERSION, suspension.servingCatalogVersion());
	}

	@Test
	@DisplayName("keeps the first failure, because later ones are merely its consequence")
	void shouldKeepTheFirstFailure() {
		// suspend() is called directly here on purpose: once suspended the gate turns every later drain away, so
		// a second failure can only ever arrive from a drain that was already in flight when the first one suspended
		final IOException firstCause = new IOException("no space left on device");
		this.transactionManager.suspend(firstCause, false, FAILING_VERSION);
		this.transactionManager.suspend(new IOException("a later, derived failure"), true, FAILING_VERSION + 4);

		final CatalogSuspension suspension = this.transactionManager.getSuspension().orElseThrow();
		assertEquals(
			firstCause, suspension.cause(),
			"the FIRST failure is the one that describes what actually broke; later ones are its consequence"
		);
		assertEquals(
			FAILING_VERSION, suspension.failedCatalogVersion(),
			"the version pair must describe the original failure, not the derived one"
		);
	}

	@Test
	@DisplayName("refuses a fresh commit at acceptance instead of parking it on a pipeline that will never run")
	void shouldRefuseFreshCommitsOnceSuspended() {
		armFailingFlush();
		assertThrows(UnexpectedIOException.class, this::drain);

		final CommitProgressRecord commitProgress = new CommitProgressRecord();
		this.transactionManager.commit(
			INITIAL_VERSION,
			UUID.randomUUID(),
			0,
			mock(IsolatedWalPersistenceService.class),
			commitProgress
		);

		assertTrue(
			commitProgress.onConflictResolved().toCompletableFuture().isCompletedExceptionally(),
			"a suspended catalog must refuse a new write immediately, never leave the client waiting on it"
		);
	}

	@Test
	@DisplayName("fails a commit already parked on the pipeline when the catalog suspends, so no client hangs")
	void shouldFailACommitAlreadyParkedOnThePipelineWhenSuspended() {
		// a commit accepted before the failure sits parked on the visibility stage, waiting for an incorporation the
		// pipeline will now never run - refusing fresh commits does not cover it, only draining the registry does
		final CommitProgressRecord parked = new CommitProgressRecord();
		this.transactionManager.registerPendingCommitProgress(
			FAILING_VERSION, parked, new CommitVersions(FAILING_VERSION, 1)
		);
		assertFalse(
			parked.onChangesVisible().toCompletableFuture().isDone(),
			"self-check: the parked record must still be in flight before the suspension"
		);

		this.transactionManager.suspend(new IOException("no space left on device"), false, FAILING_VERSION);

		assertTrue(
			parked.onChangesVisible().toCompletableFuture().isCompletedExceptionally(),
			"a commit parked on a pipeline that will never run again must be completed exceptionally in bounded time, " +
				"never left waiting"
		);
	}
}
