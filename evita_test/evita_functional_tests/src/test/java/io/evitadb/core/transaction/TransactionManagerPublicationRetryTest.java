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

import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.Functions;
import io.evitadb.index.mutation.local.MissingHistogramValueBehavior;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises publication retries with a real catalog and a controlled live-view consumer.
 * The first trunk task can pause inside publication and again at session termination. These gates control
 * when a later WAL round can finalize and when the trunk subscriber can observe the drainer's work.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionManager retries publication of finalized catalog versions")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class TransactionManagerPublicationRetryTest implements EvitaTestSupport {
	private static final String CATALOG_NAME = "publicationRetryCatalog";
	private static final int WAIT_SECONDS = 30;

	private final CountDownLatch publicationEntered = new CountDownLatch(1);
	private final CountDownLatch releasePublication = new CountDownLatch(1);
	private final CountDownLatch terminationEntered = new CountDownLatch(1);
	private final CountDownLatch releaseTermination = new CountDownLatch(1);
	/** Signals when the trunk subscriber has advanced past the second task. */
	private final CountDownLatch thirdPublication = new CountDownLatch(1);

	private TestPaths paths;
	private Evita evita;
	private Catalog catalog;
	private TransactionManager transactionManager;
	private long firstVersion;
	private boolean pauseBeforeVisibility;

	/**
	 * Waits for the test thread to release a pipeline callback.
	 *
	 * @param latch latch that releases the callback
	 */
	private static void awaitRelease(@Nonnull CountDownLatch latch) {
		try {
			assertTrue(latch.await(90, TimeUnit.SECONDS), "The test must release the pipeline callback");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	/**
	 * Opens an alive catalog with the live-view consumer supplied at construction.
	 *
	 * @throws IOException if the test storage directory cannot be created
	 */
	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(TransactionManagerPublicationRetryTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().requestThreadPool(
					ThreadPoolOptions.requestThreadPoolBuilder().minThreadCount(1).maxThreadCount(1).build()
				).build())
				.build(),
			true, null, null, false
		);
		this.evita.waitUntilFullyInitialized();
		this.catalog = new Catalog(
			CatalogSchema._internalBuild(
				CATALOG_NAME, NamingConvention.generate(CATALOG_NAME), null, Set.of(),
				EmptyEntitySchemaAccessor.INSTANCE
			),
			NoCacheSupervisor.INSTANCE,
			this.evita,
			this.evita.getProxyFactory(),
			this.evita.management().exportService(),
			this.evita.management().fileManagementService(),
			this::publish,
			DefaultTracingContext.INSTANCE,
			MissingHistogramValueBehavior.REFUSE
		);
		this.catalog.updateSchema(this.evita, null, new CreateEntitySchemaMutation(Entities.PRODUCT));
		this.catalog = this.catalog.goLive();
		this.catalog.notifyCatalogPresentInLiveView();
		this.transactionManager = this.catalog.getTransactionManager();
		this.firstVersion = this.catalog.getVersion() + 1;
	}

	/**
	 * Releases both callback gates and closes the catalog and engine.
	 */
	@AfterEach
	void tearDown() {
		this.releasePublication.countDown();
		this.releaseTermination.countDown();
		try {
			if (this.catalog != null) {
				this.catalog.terminate();
			}
		} finally {
			if (this.evita != null) {
				this.evita.close();
			}
			cleanupTestPaths(this.paths);
		}
	}

	/**
	 * Temporarily holds the propagation lock after the first round installs its catalog.
	 *
	 * @throws Exception if a commit stage cannot be observed
	 */
	@Test
	@DisplayName("should publish a drained snapshot after the propagation lock becomes available")
	void shouldRetryPublicationWhenThePropagationLockIsBusy() throws Exception {
		final CommitProgress firstCommit = holdFirstTrunkTask();
		final CommitProgress secondCommit = commitProduct(2, null);
		final CommitVersions secondVersion = awaitWal(secondCommit);

		assertEquals(this.firstVersion, liveVersion());
		assertEquals(0, this.transactionManager.drainWal(), "The busy propagation lock must cause a retry");
		assertEquals(secondVersion.catalogVersion(), this.transactionManager.getLastFinalizedCatalogVersion());
		assertEquals(this.firstVersion, liveVersion(), "The drained snapshot must wait for the propagation lock");

		releasePublicationAndAwaitRecovery(secondVersion, firstCommit, secondCommit);
	}

	/**
	 * Leaves later durable work in the WAL while an earlier round is finalized but has no retention entry.
	 *
	 * @throws Exception if a commit stage cannot be observed
	 */
	@Test
	@DisplayName("should defer new replay until the finalized predecessor is published")
	void shouldDeferReplayBehindAnUnpublishedPredecessor() throws Exception {
		this.pauseBeforeVisibility = true;
		final CommitProgress firstCommit = holdFirstTrunkTask();
		final CommitProgress secondCommit = commitProduct(2, null);
		final CommitVersions secondVersion = awaitWal(secondCommit);

		assertTrue(
			this.transactionManager.processTransactions(
				secondVersion.catalogVersion(), 0, true, true, Functions.noOpLongConsumer()
			).isEmpty(),
			"The later durable transaction must stay in the WAL until its predecessor is visible"
		);
		assertEquals(this.firstVersion, this.transactionManager.getLastFinalizedCatalogVersion());
		assertEquals(this.firstVersion - 1, liveVersion());

		releasePublicationAndAwaitRecovery(secondVersion, firstCommit, secondCommit);
	}

	/**
	 * Injects a close failure on the real WAL stream after replay and verifies that the round can be retried.
	 *
	 * @throws Exception if a commit stage cannot be observed
	 */
	@Test
	@DisplayName("should leave the finalized cursor unchanged when closing the WAL reader fails")
	void shouldRetryAfterClosingTheWalReaderFails() throws Exception {
		final CommitProgress firstCommit = holdFirstTrunkTask();
		final CommitProgress secondCommit = commitProduct(2, null);
		final CommitVersions secondVersion = awaitWal(secondCommit);
		final UnexpectedIOException failure = new UnexpectedIOException("WAL reader close failed");

		assertSame(failure, assertThrows(
			UnexpectedIOException.class,
			() -> this.transactionManager.processTransactions(
				secondVersion.catalogVersion(), 0, true, true, Functions.noOpLongConsumer(),
				(fromVersion, catalog) -> catalog.getCommittedLiveMutationStream(
					fromVersion, secondVersion.catalogVersion(), VersionSource.INTERNAL
				).onClose(() -> { throw failure; })
			)
		));
		assertEquals(this.firstVersion, this.transactionManager.getLastFinalizedCatalogVersion());
		assertEquals(this.firstVersion, liveVersion(), "The failed round must not publish anything");
		this.releasePublication.countDown();
		assertTrue(this.terminationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
		assertEquals(-1, this.transactionManager.drainWal(), "The same durable transaction must replay successfully");
		assertEquals(secondVersion, secondCommit.onChangesVisible().toCompletableFuture().get(
			WAIT_SECONDS, TimeUnit.SECONDS
		));
	}

	/**
	 * Delivers the drained round to the waiting trunk subscriber while its visibility future is still queued.
	 *
	 * @throws Exception if a commit stage cannot be observed
	 */
	@Test
	@DisplayName("should run session termination once when the drainer and trunk both complete visibility")
	void shouldTerminateTheDrainedSessionOnce() throws Exception {
		holdFirstTrunkTask();
		final AtomicInteger terminations = new AtomicInteger();
		final CommitProgress secondCommit = commitProduct(2, __ -> terminations.incrementAndGet());
		awaitWal(secondCommit);
		final CountDownLatch requestWorkerEntered = new CountDownLatch(1);
		final CountDownLatch releaseRequestWorker = new CountDownLatch(1);
		this.evita.getRequestExecutor().execute(() -> {
			requestWorkerEntered.countDown();
			awaitRelease(releaseRequestWorker);
		});
		try {
			assertTrue(requestWorkerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
			this.releasePublication.countDown();
			assertTrue(this.terminationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
			assertEquals(-1, this.transactionManager.drainWal());
			assertEquals(1, terminations.get());
			assertFalse(secondCommit.onChangesVisible().toCompletableFuture().isDone());

			this.releaseTermination.countDown();
			commitProduct(3, null);
			assertTrue(
				this.thirdPublication.await(WAIT_SECONDS, TimeUnit.SECONDS), "The trunk must pass the second task"
			);
			assertFalse(secondCommit.onChangesVisible().toCompletableFuture().isDone());
			assertEquals(1, terminations.get(), "The trunk must share the drainer's completion claim");
		} finally {
			releaseRequestWorker.countDown();
		}
		secondCommit.onChangesVisible().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Forces the owning trunk publication to fail while an earlier round holds the propagation lock.
	 *
	 * @throws Exception if the second commit's WAL durability cannot be observed
	 */
	@Test
	@DisplayName("should settle the owning task's failure before handing its round to recovery")
	void shouldSettlePublicationFailureBeforeRetry() throws Exception {
		holdFirstTrunkTask();
		final CommitProgressRecord progress = assertInstanceOf(CommitProgressRecord.class, commitProduct(2, null));
		final CommitVersions versions = awaitWal(progress);
		final TransactionManager.ProcessResult round = this.transactionManager.processTransactions(
			versions.catalogVersion(), 0, true, true, Functions.noOpLongConsumer()
		).orElseThrow();

		final TransactionTimedOutException failure = assertThrows(
			TransactionTimedOutException.class,
			() -> this.transactionManager.propagateCatalogSnapshot(
				round.previousCatalogVersion(), round.catalog(),
				cause -> this.transactionManager.getPendingCommitProgressRegistry().failChangesVisible(
					versions.catalogVersion(), progress, cause
				)
			)
		);
		assertTrue(progress.onChangesVisible().toCompletableFuture().isCompletedExceptionally());
		assertSame(failure, progress.onChangesVisible().toCompletableFuture().exceptionNow());
	}

	/**
	 * Opens a write session directly against the test catalog.
	 *
	 * @param terminationCallback optional callback run when the changes become visible
	 * @return the open write session
	 */
	@Nonnull
	private EvitaSession openSession(@Nullable EvitaSessionTerminationCallback terminationCallback) {
		return new EvitaSession(
			this.evita,
			this.catalog,
			new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE),
			terminationCallback,
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE,
			new SessionTraits(CATALOG_NAME, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE),
			__ -> {
				throw new AssertionError("The fixture never pins a catalog version");
			}
		);
	}

	/**
	 * Installs a snapshot, pausing the first round on the selected side of the visibility marker.
	 *
	 * @param snapshot catalog published by the trunk stage or drainer
	 */
	private void publish(@Nonnull Catalog snapshot) {
		if (snapshot.getVersion() == this.firstVersion && this.pauseBeforeVisibility) {
			this.publicationEntered.countDown();
			awaitRelease(this.releasePublication);
		}
		snapshot.notifyCatalogPresentInLiveView();
		if (snapshot.getVersion() == this.firstVersion + 2) {
			this.thirdPublication.countDown();
		}
		if (snapshot.getVersion() == this.firstVersion && !this.pauseBeforeVisibility) {
			this.publicationEntered.countDown();
			awaitRelease(this.releasePublication);
		}
	}

	/**
	 * Commits a product asynchronously.
	 *
	 * @param primaryKey product to write
	 * @param terminationCallback optional callback run at visibility
	 * @return the running commit's progress
	 */
	@Nonnull
	private CommitProgress commitProduct(
		int primaryKey, @Nullable EvitaSessionTerminationCallback terminationCallback
	) {
		final EvitaSession session = openSession(terminationCallback);
		session.upsertEntity(session.createNewEntity(Entities.PRODUCT, primaryKey));
		return session.closeNowWithProgress();
	}

	/**
	 * Starts the preceding trunk task and waits until it holds the propagation lock.
	 *
	 * @return progress of the preceding commit
	 * @throws InterruptedException if the test thread was interrupted
	 */
	@Nonnull
	private CommitProgress holdFirstTrunkTask() throws InterruptedException {
		final CommitProgress firstCommit = commitProduct(
			1,
			__ -> {
				this.terminationEntered.countDown();
				awaitRelease(this.releaseTermination);
			}
		);
		assertTrue(this.publicationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "The trunk stage must be publishing");
		return firstCommit;
	}

	/**
	 * Waits for the commit's WAL durability.
	 *
	 * @param progress commit progress to wait on
	 * @return the versions assigned to the durable transaction
	 * @throws Exception if WAL durability cannot be observed
	 */
	@Nonnull
	private static CommitVersions awaitWal(@Nonnull CommitProgress progress) throws Exception {
		return progress.onWalAppended().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Releases the earlier publication and lets the actual scheduler retry while the trunk subscriber is held.
	 *
	 * @param secondVersion the durable transaction whose visibility is awaited
	 * @param firstCommit   progress of the commit holding the trunk subscriber
	 * @param secondCommit  progress that the drainer must complete
	 * @throws Exception if the first commit cannot be released or the later snapshot never becomes visible
	 */
	private void releasePublicationAndAwaitRecovery(
		@Nonnull CommitVersions secondVersion,
		@Nonnull CommitProgress firstCommit,
		@Nonnull CommitProgress secondCommit
	) throws Exception {
		try {
			this.releasePublication.countDown();
			assertTrue(
				this.terminationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"The earlier publication must finish while its trunk task is still running");
			assertFalse(firstCommit.onChangesVisible().toCompletableFuture().isDone());
			try {
				assertEquals(
					secondVersion,
					secondCommit.onChangesVisible().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS)
				);
			} catch (TimeoutException ex) {
				throw new AssertionError(
					"The drainer must publish finalized version " + secondVersion.catalogVersion() +
						" and complete its progress while the trunk task is held (live version " + liveVersion() + ").",
					ex
				);
			}
			assertEquals(secondVersion.catalogVersion(), liveVersion());
		} finally {
			this.releaseTermination.countDown();
		}
	}

	/**
	 * Returns the version currently serving queries.
	 *
	 * @return live catalog version
	 */
	private long liveVersion() {
		return this.transactionManager.getLivingCatalog().getVersion();
	}
}
