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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a transaction incorporated into the trunk by the write-ahead log draining task becomes visible, the
 * same way a transaction incorporated by the trunk-incorporation stage does.
 *
 * The draining task is the pipeline's fallback: {@link TransactionManager#retryTransactionProcessing} schedules it
 * whenever a trunk-incorporation task fails, and it then drains every durable transaction the trunk has not yet
 * incorporated. Incorporating a transaction finalizes a new catalog version, but only publishing that version into
 * the live view makes it readable - and when the trunk-incorporation stage later receives the same transaction, it
 * finds it already finalized and only waits for the live view to reach it.
 *
 * The test drives a real engine and makes the draining task, not the trunk-incorporation stage, incorporate one
 * committed transaction - deterministically, without relying on timing. The session committing it closes at
 * {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, so its termination callback runs on the pipeline thread after the
 * transaction became durable and before the thread hands the transaction to the trunk-incorporation stage. The
 * callback schedules the draining task exactly as a failed trunk-incorporation task would and holds the hand-over
 * back until the draining task has finalized the transaction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionManager - publication of a transaction incorporated by the WAL draining task")
@Tag(ENGINE)
@Tag(TRANSACTION)
class TransactionManagerWalDrainPublicationTest implements EvitaTestSupport {
	/**
	 * Name of the catalog the test writes to.
	 */
	private static final String CATALOG_NAME = "walDrainPublicationCatalog";
	/**
	 * Attribute every committed product carries.
	 */
	private static final String ATTRIBUTE_NAME = "name";
	/**
	 * Primary key of the product committed and made visible before the drained transaction.
	 */
	private static final int VISIBLE_PRODUCT_PK = 1;
	/**
	 * Primary key of the product committed by the transaction the draining task incorporates.
	 */
	private static final int DRAINED_PRODUCT_PK = 2;
	/**
	 * Upper bound of the termination callback's wait for the draining task. The task runs about a second after it is
	 * scheduled, so the bound only ends a wait for something that is not going to happen.
	 */
	private static final long DRAIN_WAIT_SECONDS = 30L;
	/**
	 * Upper bound of the wait for the committed transaction to become visible. Well below the pipeline's safety
	 * deadline of at least 60 seconds, after which the trunk-incorporation stage fails the commit instead.
	 */
	private static final long VISIBILITY_WAIT_SECONDS = 10L;

	/**
	 * Identifier of the session whose termination callback hands the transaction to the draining task - set only
	 * while that one session is being closed, so no other session's termination is affected.
	 */
	private final AtomicReference<UUID> drainedSessionId = new AtomicReference<>();
	/**
	 * Number of times the termination callback took the transaction to the draining task.
	 */
	private final AtomicInteger drainHandOverCount = new AtomicInteger();
	/**
	 * Durable catalog version seen when the termination callback started.
	 */
	private final AtomicLong durableVersionAtHandOver = new AtomicLong(-1L);
	/**
	 * Finalized catalog version seen when the termination callback started.
	 */
	private final AtomicLong finalizedVersionAtHandOver = new AtomicLong(-1L);
	/**
	 * Finalized catalog version seen when the termination callback finished.
	 */
	private final AtomicLong finalizedVersionAfterDrain = new AtomicLong(-1L);
	/**
	 * Failure inside the termination callback, which must never throw into the pipeline.
	 */
	private final AtomicReference<Throwable> handOverFailure = new AtomicReference<>();

	private TestPaths paths;
	private Evita evita;
	private TransactionManager transactionManager;
	/** Binding to install during WAL handoff, when testing a retired incarnation. */
	private volatile Catalog replacement;
	/** Holds the handoff until the WAL completion listener is registered. */
	private final CountDownLatch releaseWalCompletion = new CountDownLatch(1);

	/**
	 * Waits until the passed version supplier reaches the target or the bound elapses.
	 *
	 * @param versionSupplier supplier of the version to watch
	 * @param targetVersion   the version to wait for
	 * @param boundInSeconds  upper bound of the wait
	 * @return the last version read
	 */
	private static long awaitVersion(
		@Nonnull LongSupplier versionSupplier,
		long targetVersion,
		long boundInSeconds
	) {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(boundInSeconds);
		while (true) {
			final long version = versionSupplier.getAsLong();
			if (version >= targetVersion || System.nanoTime() >= deadline) {
				return version;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5L));
		}
	}

	/**
	 * Describes the state of a commit stage future for an assertion message.
	 *
	 * @param future the future to describe
	 * @return `pending`, `completed` or `failed with <exception>`
	 */
	@Nonnull
	private static String describe(@Nonnull CompletableFuture<CommitVersions> future) {
		if (!future.isDone()) {
			return "pending";
		} else if (future.isCompletedExceptionally()) {
			return "failed with " + future.exceptionNow();
		} else {
			return "completed";
		}
	}

	/**
	 * Starts an engine with an alive catalog and a visible product.
	 *
	 * @throws IOException if the test storage directory cannot be created
	 */
	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(TransactionManagerWalDrainPublicationTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths).build(),
			null,
			this::handOverToWalDrainer
		);
		this.evita.waitUntilFullyInitialized();
		this.evita.defineCatalog(CATALOG_NAME);
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, VISIBLE_PRODUCT_PK)
						.setAttribute(ATTRIBUTE_NAME, "visible")
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		this.transactionManager = liveCatalog().getTransactionManager();
	}

	/**
	 * Closes the engine and removes its test files.
	 */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Checks the live view, a new session and commit progress after a WAL drain.
	 *
	 * @throws Exception if the commit progress cannot be observed
	 */
	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("should publish into the live view the version the WAL draining task incorporated")
	void shouldPublishTheVersionTheWalDrainerIncorporated() throws Exception {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(CATALOG_NAME, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, SessionFlags.READ_WRITE)
		);
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, DRAINED_PRODUCT_PK).setAttribute(ATTRIBUTE_NAME, "drained")
		);
		this.drainedSessionId.set(session.getId());
		final CommitProgress commitProgress = session.closeNowWithProgress();
		final long committedVersion = commitProgress.onWalAppended()
			.toCompletableFuture()
			.get(DRAIN_WAIT_SECONDS + 10L, TimeUnit.SECONDS)
			.catalogVersion();

		// preconditions - they hold with and without the defect, and prove the draining task took the transaction
		assertNull(this.handOverFailure.get(), "precondition: the termination callback must not fail");
		assertEquals(
			1, this.drainHandOverCount.get(),
			"precondition: the termination callback must hand exactly one transaction to the draining task"
		);
		assertEquals(
			committedVersion, this.durableVersionAtHandOver.get(),
			"precondition: the transaction must be durable before it is handed to the draining task"
		);
		assertEquals(
			committedVersion - 1L, this.finalizedVersionAtHandOver.get(),
			"precondition: nothing may have incorporated the transaction before the draining task"
		);
		assertEquals(
			committedVersion, this.finalizedVersionAfterDrain.get(),
			"precondition: the draining task must have incorporated the transaction before the trunk-incorporation " +
				"stage received it"
		);

		// the transaction now reaches the trunk-incorporation stage, which finds it already finalized
		final CompletableFuture<CommitVersions> changesVisible = commitProgress.onChangesVisible()
			.toCompletableFuture();
		final long sessionVersion = awaitVersion(this::readCatalogVersion, committedVersion, VISIBILITY_WAIT_SECONDS);
		try {
			// the stage completes the record right after the live view reaches the version, so a short grace suffices
			changesVisible.get(1L, TimeUnit.SECONDS);
		} catch (TimeoutException | ExecutionException ignored) {
			// the state of the record is described in the observations below
		}

		final long liveVersion = this.transactionManager.getLivingCatalog().getVersion();
		final long finalizedVersion = this.transactionManager.getLastFinalizedCatalogVersion();
		final long durableVersion = this.transactionManager.getLastDurableCatalogVersion();
		final boolean drainedProductReadable = isProductReadable(DRAINED_PRODUCT_PK);
		final boolean visibleProductReadable = isProductReadable(VISIBLE_PRODUCT_PK);
		final String observations = "committed version " + committedVersion +
			", live version " + liveVersion +
			", finalized version " + finalizedVersion +
			", durable version " + durableVersion +
			", new session reads version " + sessionVersion +
			", new session reads the drained product: " + drainedProductReadable +
			", new session reads the earlier product: " + visibleProductReadable +
			", onChangesVisible " + describe(changesVisible) +
			" (after waiting " + VISIBILITY_WAIT_SECONDS + " s)";

		assertEquals(
			finalizedVersion, liveVersion,
			"The live view must reach the version the WAL draining task finalized - " + observations
		);
		assertEquals(
			committedVersion, sessionVersion,
			"A new session must read the version the WAL draining task finalized - " + observations
		);
		assertTrue(
			drainedProductReadable,
			"A new session must read the change the WAL draining task incorporated - " + observations
		);
		assertTrue(
			changesVisible.isDone() && !changesVisible.isCompletedExceptionally(),
			"The commit must report its changes visible - " + observations
		);

		this.evita.updateCatalog(
			CATALOG_NAME,
			normalSession -> {
				normalSession.upsertEntity(
					normalSession.createNewEntity(Entities.PRODUCT, DRAINED_PRODUCT_PK + 1)
						.setAttribute(ATTRIBUTE_NAME, "later")
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		assertEquals(committedVersion + 1, readCatalogVersion());
		assertTrue(isProductReadable(DRAINED_PRODUCT_PK + 1));
	}

	/**
	 * Installs a different incarnation at the engine binding and drains the old catalog before terminating it.
	 *
	 * @throws Exception if the commit result or replacement catalog cannot be observed
	 */
	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("should fail visibility when the engine rejects the retired catalog snapshot")
	void shouldFailPendingVisibilityOfAReplacedCatalog() throws Exception {
		final TestPaths replacementPaths = createTestPaths("publicationReplacement");
		final Catalog outgoing = liveCatalog();
		final long outgoingVersion = outgoing.getVersion();
		try (final Evita replacementEngine = new Evita(newTestEvitaConfigurationBuilder(replacementPaths).build())) {
			replacementEngine.waitUntilFullyInitialized();
			replacementEngine.defineCatalog(CATALOG_NAME);
			this.replacement = assertInstanceOf(
				Catalog.class, replacementEngine.getCatalogInstance(CATALOG_NAME).orElseThrow()
			);

			final EvitaSessionContract session = this.evita.createSession(
				new SessionTraits(CATALOG_NAME, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, SessionFlags.READ_WRITE)
			);
			session.upsertEntity(
				session.createNewEntity(Entities.PRODUCT, DRAINED_PRODUCT_PK).setAttribute(ATTRIBUTE_NAME, "outgoing")
			);
			this.drainedSessionId.set(session.getId());
			final CommitProgress commit = session.closeNowWithProgress();
			final CompletableFuture<Void> drained = commit.onWalAppended().thenRun(() -> {
				this.evita.setNextEngineState(this.evita.getEngineState().withUpdatedCatalogInstance(this.replacement));
				this.transactionManager.drainWal();
			}).toCompletableFuture();
			this.releaseWalCompletion.countDown();
			drained.get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS);

			assertEquals(
				outgoingVersion + 1,
				commit.onWalAppended().toCompletableFuture().get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS).catalogVersion()
			);
			assertNull(this.handOverFailure.get(), "The old manager must drive the swap before termination");
			assertSame(this.replacement, liveCatalog());
			final ExecutionException failure = assertThrows(
				ExecutionException.class,
				() -> commit.onChangesVisible().toCompletableFuture().get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS)
			);
			// the drainer's retirement fails the record, unless the trunk task waiting for the live view sees the
			// retirement first - both name the retired incarnation
			final TransactionException cause = assertInstanceOf(TransactionException.class, failure.getCause());
			assertTrue(
				String.valueOf(cause.getMessage()).contains("catalog incarnation"),
				"The commit must fail because its catalog incarnation was retired: " + cause.getMessage()
			);
			assertEquals(outgoingVersion, this.transactionManager.getLivingCatalog().getVersion());
		} finally {
			this.releaseWalCompletion.countDown();
			this.evita.setNextEngineState(this.evita.getEngineState().withUpdatedCatalogInstance(outgoing));
			cleanupTestPaths(replacementPaths);
		}
	}

	/**
	 * Session termination callback of the engine. For the one session the test arms, it runs on the pipeline thread
	 * after the transaction became durable and before the thread hands it to the trunk-incorporation stage: it
	 * schedules the draining task the way a failed trunk-incorporation task does and holds the hand-over back until
	 * the draining task has finalized the transaction. In the replacement test it instead waits for the listener
	 * that changes the engine binding to be registered. A failure is recorded for the test.
	 *
	 * @param session the session being terminated
	 */
	private void handOverToWalDrainer(@Nonnull EvitaSessionContract session) {
		final UUID armedSessionId = this.drainedSessionId.get();
		if (armedSessionId == null || !armedSessionId.equals(session.getId())) {
			return;
		}
		try {
			final TransactionManager theTransactionManager = this.transactionManager;
			if (this.replacement != null) {
				assertTrue(this.releaseWalCompletion.await(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS));
				return;
			}
			final long durableVersion = theTransactionManager.getLastDurableCatalogVersion();
			this.durableVersionAtHandOver.set(durableVersion);
			this.finalizedVersionAtHandOver.set(theTransactionManager.getLastFinalizedCatalogVersion());
			theTransactionManager.retryTransactionProcessing(
				new TrunkIncorporationTransactionTask(
					CATALOG_NAME,
					durableVersion,
					theTransactionManager.getLivingCatalog().getSchema().version(),
					UUID.randomUUID(),
					new CommitProgressRecord()
				),
				new TransactionTimedOutException("Trunk incorporation of the transaction failed.")
			);
			this.finalizedVersionAfterDrain.set(
				awaitVersion(theTransactionManager::getLastFinalizedCatalogVersion, durableVersion, DRAIN_WAIT_SECONDS)
			);
			this.drainHandOverCount.incrementAndGet();
		} catch (Throwable ex) {
			this.handOverFailure.set(ex);
		}
	}

	/**
	 * Returns the catalog version a newly created session reads.
	 *
	 * @return the catalog version of a new session
	 */
	private long readCatalogVersion() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG_NAME)) {
			return session.getCatalogVersion();
		}
	}

	/**
	 * Returns whether a newly created session reads the product with the passed primary key.
	 *
	 * @param primaryKey the primary key of the product
	 * @return `true` when the product is readable
	 */
	private boolean isProductReadable(int primaryKey) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG_NAME)) {
			return session.getEntity(Entities.PRODUCT, primaryKey, attributeContentAll()).isPresent();
		}
	}

	/**
	 * Returns the catalog instance currently in the live view.
	 *
	 * @return the live catalog
	 */
	@Nonnull
	private Catalog liveCatalog() {
		return assertInstanceOf(Catalog.class, this.evita.getCatalogInstance(CATALOG_NAME).orElseThrow());
	}
}
