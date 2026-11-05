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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ConflictingEngineMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogHandoverFailedException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a catalog rename or replace and the commits still travelling through the pipeline cannot damage each
 * other. The central case is a commit that must stay readable when the client renames or replaces its catalog right
 * after the commit returned, before the commit's trunk round has run.
 *
 * A commit closed with {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE} returns once its write-ahead log record is on
 * the device, and its trunk round runs after that. The round writes the catalog header under the name the catalog had
 * when its persistence service was created. If it lands between the relabel's bootstrap record and the close of the
 * service, the header carrying the former name is what the reopened service finds, sitting above the schema part the
 * relabel has already published under the incoming name. The relabel therefore waits for the round before it begins.
 *
 * The interleaving is forced rather than waited for, using nothing but public members:
 *
 * - **The round is held right after the acknowledgement.** The trunk task is pushed by the same thread that
 *   acknowledges the commit, right after it. With the immediate request executor the listener of the acknowledgement
 *   runs on that very thread, so while the listener waits the round has not even been queued. In production that gap
 *   is the thread being descheduled between the two statements.
 * - **The listener is registered in time.** The transaction pool is occupied before the session closes, so the commit
 *   cannot start until the listener is in place.
 * - **The round is let go at the relabel's only progress tick, or after {@link #HOLD_SECONDS}.** The tick arrives after
 *   the relabel's bootstrap record and before the close of the service, on the relabelling thread, which then waits
 *   for the round to finish. A relabel that waits for the round first cannot get there with the round still held, so
 *   the hold ends on its timer and the round lands before the relabel begins.
 *
 * The operation must succeed, retrying only a clean timeout, and support another write before both entities are read
 * after a restart.
 *
 * The class also covers the refusal when a round outlasts the drain budget, a caller's session suspension surviving a
 * round that lands, a late round of the replaced catalog, and rounds carrying schema changes, whose notifications the
 * operation can refuse.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog rename and replace racing the commit pipeline")
@Tag(STORAGE)
@Tag(TRANSACTION)
@Tag(MANAGEMENT)
class CatalogHandoverTrunkRoundRaceTest implements EvitaTestSupport {
	private static final String SOURCE = "trunkRoundSource";
	private static final String TARGET = "trunkRoundTarget";
	private static final String PRODUCT = "product";
	private static final String NAME = "name";
	/**
	 * Entity type defined by a schema-changing commit.
	 */
	private static final String BRAND = "brand";
	/**
	 * Entity type defined by the second schema-changing commit of a batch.
	 */
	private static final String CATEGORY = "category";
	private static final int BASELINE_PRIMARY_KEY = 1;
	private static final int ACKNOWLEDGED_PRIMARY_KEY = 2;
	/**
	 * Primary key of the product written by the second schema-changing commit of a batch.
	 */
	private static final int SECOND_ACKNOWLEDGED_PRIMARY_KEY = 4;
	private static final int TARGET_PRIMARY_KEY = 100;
	/**
	 * Answer of {@link #readAcknowledgedEntity} when the entity is there.
	 */
	private static final String READABLE = "readable";
	/**
	 * Size of the transaction pool, all of which the fixture occupies. Holding the round takes one thread and the
	 * relabel another, and the round needs one once it is let go.
	 */
	private static final int TRANSACTION_THREADS = 3;
	/**
	 * Long enough that no checkpoint fires on its own during the test, so the round defers and the close of the service
	 * is what publishes it.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * Bound of every positive wait here. It returns as soon as the awaited thread gets there, so it costs nothing on
	 * a passing run and only limits how long a genuine hang can take.
	 */
	private static final int WAIT_SECONDS = 30;
	/**
	 * How long the round is held at most. A negative wait: an implementation that waits for the round before the
	 * relabel pays it once, and on a loaded machine the only thing a short hold costs is sensitivity.
	 */
	private static final int HOLD_SECONDS = 2;
	/**
	 * How long a reopened catalog may stay in a transitional state.
	 */
	private static final int SETTLE_SECONDS = 30;
	/**
	 * Shortest time a refusal at the five-second drain budget may take, whether the budget that ran out belongs to the
	 * pipeline or to the sessions. Below the budget itself, so that clock granularity cannot fail it; a loaded machine
	 * can only make the refusal later.
	 */
	private static final long DRAIN_BUDGET_FLOOR_MILLIS = 4_500L;

	@Nullable private TestPaths paths;
	@Nullable private Evita evita;
	@Nullable private Evita reopened;

	@AfterEach
	void tearDown() {
		closeQuietly(this.reopened);
		closeQuietly(this.evita);
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	/**
	 * The operation arriving right after the acknowledgement of a commit whose trunk round has not run yet.
	 */
	@Nested
	@DisplayName("overtaking the trunk round of an acknowledged commit")
	class OvertakingTheRound {

		/**
		 * Verifies that the commit survives a rename to a free name.
		 */
		@Test
		@DisplayName("should keep the acknowledged commit readable when a rename overtakes its trunk round")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldKeepAcknowledgedCommitReadableWhenRenameOvertakesItsTrunkRound() throws IOException {
			assertAcknowledgedCommitSurvives(false);
		}

		/**
		 * Verifies that the commit survives a replace of an existing catalog.
		 */
		@Test
		@DisplayName("should keep the acknowledged commit readable when a replace overtakes its trunk round")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldKeepAcknowledgedCommitReadableWhenReplaceOvertakesItsTrunkRound() throws IOException {
			assertAcknowledgedCommitSurvives(true);
		}

	}

	/**
	 * A round held for longer than the operation is prepared to wait for it.
	 */
	@Nested
	@DisplayName("refused when a round outlasts the drain budget")
	class RefusalAtTheBudget {

		/**
		 * Verifies that a timed-out rename leaves its source serving and supports retry.
		 */
		@Test
		@DisplayName("should refuse a rename safely when a held round outlasts the drain budget")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldRefuseRenameWhenRoundOutlastsBudget() throws IOException {
			assertHeldRoundRefusal(false);
		}

		/**
		 * Verifies that a timed-out replace resumes both registries and supports retry.
		 */
		@Test
		@DisplayName("should refuse a replace safely and resume both registries when a round outlasts the budget")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldRefuseReplaceWhenRoundOutlastsBudget() throws IOException {
			assertHeldRoundRefusal(true);
		}

	}

	/**
	 * A round publishing around whatever else has hold of the catalog: a caller's suspension, or a finished replace.
	 */
	@Nested
	@DisplayName("with a round landing around the operation")
	class LandingRound {

		/**
		 * Verifies that pipeline publication cannot resume a caller-owned session suspension.
		 */
		@Test
		@DisplayName("should preserve an explicit session suspension when an acknowledged round lands")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldPreserveSuspensionWhenRoundLands() throws IOException {
			final Evita engine = startEngineUnderTest(configuration());
			defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);
			final HeldRound round = heldRound(engine, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, null);
			final SessionRegistry registry = engine.obtainCatalogSessionRegistry(SOURCE).orElseThrow();
			try {
				round.assertAcknowledged();
				registry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE);
				round.release();
				assertNull(
					awaitFailure(round.commit.onChangesVisible().toCompletableFuture()),
					"The round must land normally while the sessions are suspended."
				);
				assertTrue(registry.isSuspended(), "Landing a round must preserve its caller's suspension.");
				assertThrows(SessionBusyException.class, () -> engine.createReadOnlySession(SOURCE));
			} finally {
				round.release();
				registry.resumeOperations();
			}
			assertEntities(engine, SOURCE, ACKNOWLEDGED_PRIMARY_KEY);
		}

		/**
		 * Verifies that versions from the discarded target cannot overwrite the surviving lineage.
		 */
		@Test
		@DisplayName("should refuse a late higher-version round of the catalog that was replaced")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldRefuseLateRoundOfReplacedTarget() throws IOException {
			final Evita engine = startEngineUnderTest(configuration());
			defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);
			defineAliveCatalog(engine, TARGET, TARGET_PRIMARY_KEY);
			final Catalog source = (Catalog) engine.getCatalogInstanceOrThrowException(SOURCE);
			engine.updateCatalog(
				TARGET,
				session -> {
					session.upsertEntity(session.createNewEntity(PRODUCT, 101).setAttribute(NAME, "target-later"));
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
			);
			final Catalog oldTarget = (Catalog) engine.getCatalogInstanceOrThrowException(TARGET);
			assertTrue(
				oldTarget.getVersion() > source.getVersion(),
				"Precondition: the discarded target must be ahead, or the version comparison alone would refuse it."
			);
			engine.replaceCatalog(SOURCE, TARGET);
			assertFalse(engine.getEngineState().replaceCatalogReference(oldTarget));
			assertEquals(source.getCatalogId(), engine.getCatalogInstanceOrThrowException(TARGET).getCatalogId());
			assertEntities(engine, TARGET, BASELINE_PRIMARY_KEY);
			writeAndRead(engine, TARGET);
		}

	}

	/**
	 * A round that has to notify the engine of its schema changes after publishing, under the lock the operation holds.
	 */
	@Nested
	@DisplayName("with a round carrying a schema change")
	class SchemaChangingRound {

		/**
		 * Lets a schema-changing round land while the rename holds `engineStateLock`: the round reaches the live view
		 * and then waits for that lock to apply its queued schema notification.
		 *
		 * A commit that returned early has left the registry, so the rename goes ahead, and the notification is
		 * refused: by the rename's conflict keys, or by the old name being gone when the round was descheduled until
		 * the rename had finished. Should the test thread be descheduled past the budget before it lets the round go,
		 * the rename refuses cleanly instead, the round finishes normally and a retry succeeds. A commit waiting for
		 * its changes to become visible cannot leave the registry while the notification waits for the lock, so the
		 * drain gives up after its budget.
		 *
		 * @param behavior the stage the commit returns at
		 */
		@ParameterizedTest
		@EnumSource(CommitBehavior.class)
		@DisplayName("should damage nothing when a schema-changing round is in flight, at any commit behavior")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldDamageNothingWhenSchemaChangingRoundIsInFlight(@Nonnull CommitBehavior behavior) throws IOException {
			final EvitaConfiguration configuration = configuration();
			final Evita engine = startEngineUnderTest(configuration);
			defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);
			final HeldRound round = heldRound(engine, behavior, BRAND);
			final Throwable failure;
			final long started;
			try {
				round.assertAcknowledged();
				started = System.nanoTime();
				final CompletableFuture<CommitVersions> operation = relabel(engine, false, percent -> { });
				awaitSuspension(engine, operation);
				round.release();
				failure = awaitFailure(operation);
			} finally {
				round.release();
			}
			final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			final Throwable roundFailure = awaitFailure(round.commit.onChangesVisible().toCompletableFuture());
			assertNoHandoverMarker(failure);
			if (behavior == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
				assertInstanceOf(GenericEvitaInternalError.class, failure);
				assertTrue(
					failure.getMessage().contains("Some of the sessions didn't clean themselves"),
					"Expected the drain to give up, but the operation answered: " + failure
				);
				assertDrainBudgetSpent(elapsed);
				assertNull(roundFailure, "The round must finish normally after the operation gave up.");
				retryRelabel(engine, false);
			} else if (failure == null) {
				assertNotificationRefused(roundFailure);
			} else {
				assertInstanceOf(TransactionTimedOutException.class, failure);
				assertNull(roundFailure, "The round must finish normally after the operation refused to wait for it.");
				retryRelabel(engine, false);
			}
			assertEntities(engine, TARGET, ACKNOWLEDGED_PRIMARY_KEY);
			assertEntityTypeReadable(engine, BRAND);
			writeAndRead(engine, TARGET);
			assertEntityTypeReadable(restartAndRead(configuration), BRAND);
		}

		/**
		 * Lands two schema-changing commits in one trunk round while the rename waits for that round. The rename
		 * refuses the notification of the first, the refusal ends the round, and the notification of the second stays
		 * queued under the retired name, where nothing may hold it against the next commit on the renamed catalog.
		 *
		 * The batch is forced with one free thread of the transaction pool. It appends both commits, forces them
		 * together and parks in the listener of the second acknowledgement, with the durable version already at the
		 * second commit and no thread left for the trunk stage. Once let go, the trunk stage picks up both greedily.
		 * Without the batch the second commit would still be unpublished when the first waits for the rename's lock, so
		 * the wait would give up at its budget: the operation answering normally is what proves this test reaches the
		 * queued notification.
		 */
		@Test
		@DisplayName("should not fail a later commit when a rename refused a batch of schema notifications")
		@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
		void shouldNotFailLaterCommitWhenRenameRefusedBatchedSchemaNotifications() throws IOException {
			final EvitaConfiguration configuration = configuration();
			final Evita engine = startEngineUnderTest(configuration);
			defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);

			final CountDownLatch releaseFreeThread = occupyTransactionThreads(engine, 1);
			final CountDownLatch releaseOtherThreads = occupyTransactionThreads(engine, TRANSACTION_THREADS - 1);
			final Throwable failure;
			final CommitProgress firstCommit;
			final HeldRound round;
			try {
				firstCommit = commit(engine, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, BRAND, ACKNOWLEDGED_PRIMARY_KEY);
				round = new HeldRound(
					commit(engine, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, CATEGORY, SECOND_ACKNOWLEDGED_PRIMARY_KEY)
				);
				round.hold();
				releaseFreeThread.countDown();
				try {
					round.assertAcknowledged();
					final CompletableFuture<CommitVersions> operation = relabel(engine, false, percent -> { });
					awaitSuspension(engine, operation);
					round.release();
					releaseOtherThreads.countDown();
					failure = awaitFailure(operation);
				} finally {
					round.release();
				}
			} finally {
				// in the finally block, so that a failed precondition does not strand the threads of the pool
				releaseFreeThread.countDown();
				releaseOtherThreads.countDown();
			}

			assertNull(
				failure,
				"Precondition: the operation must answer normally. A refusal means the commits did not land in one " +
					"round, and then nothing below reaches the queued notification."
			);
			assertNotificationRefused(awaitFailure(firstCommit.onChangesVisible().toCompletableFuture()));
			assertNull(
				awaitFailure(round.commit.onChangesVisible().toCompletableFuture()),
				"The second commit must finish normally: its changes went live in the same round."
			);
			writeAndRead(engine, TARGET);

			final Evita restarted = restartAndRead(configuration);
			assertEntities(restarted, TARGET, SECOND_ACKNOWLEDGED_PRIMARY_KEY);
			assertEntityTypeReadable(restarted, BRAND);
			assertEntityTypeReadable(restarted, CATEGORY);
		}

	}

	/**
	 * Reads the acknowledged entity through a session on the catalog.
	 *
	 * @param engine      the engine to read through
	 * @param catalogName the name to read under
	 * @return {@link #READABLE}, or what was answered instead
	 */
	@Nonnull
	private static String readAcknowledgedEntity(@Nonnull Evita engine, @Nonnull String catalogName) {
		final Function<EvitaSessionContract, String> reader = session ->
			session.getEntity(PRODUCT, ACKNOWLEDGED_PRIMARY_KEY).isPresent() ? READABLE : "the entity is missing";
		try {
			return engine.queryCatalog(catalogName, reader);
		} catch (RuntimeException ex) {
			return ex.getClass().getSimpleName() + ": " + ex.getMessage();
		}
	}

	/**
	 * Waits for the catalog to leave its transitional state.
	 *
	 * @param engine      the engine to ask
	 * @param catalogName the name to ask about
	 * @return the state it has settled in, or the transitional one when it never left it
	 */
	@Nullable
	private static CatalogState settledState(@Nonnull Evita engine, @Nonnull String catalogName) {
		try {
			await()
				.atMost(SETTLE_SECONDS, TimeUnit.SECONDS)
				.pollInterval(20, TimeUnit.MILLISECONDS)
				.until(() -> engine.getCatalogState(catalogName).map(it -> !it.isTransitional()).orElse(true));
		} catch (ConditionTimeoutException ex) {
			// the state read below is what the assertion reports
		}
		return engine.getCatalogState(catalogName).orElse(null);
	}

	/**
	 * Creates a catalog with one entity, past its warm-up.
	 *
	 * @param engine      the engine to create it in
	 * @param catalogName the name of the catalog
	 * @param primaryKey  the primary key of the entity
	 */
	private static void defineAliveCatalog(@Nonnull Evita engine, @Nonnull String catalogName, int primaryKey) {
		engine.defineCatalog(catalogName);
		engine.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(NAME, String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
		engine.updateCatalog(
			catalogName,
			session -> {
				session.upsertEntity(session.createNewEntity(PRODUCT, primaryKey).setAttribute(NAME, "p" + primaryKey));
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Waits on a latch without letting an interrupt end the wait unnoticed.
	 *
	 * @param latch the latch
	 * @param bound how long to wait, in seconds
	 * @return true when the latch opened in time
	 */
	private static boolean awaitLatch(@Nonnull CountDownLatch latch, int bound) {
		try {
			return latch.await(bound, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Writes through the surviving name and reads the new entity back.
	 */
	private static void writeAndRead(@Nonnull Evita engine, @Nonnull String name) {
		engine.updateCatalog(
			name,
			session -> {
				session.upsertEntity(session.createNewEntity(PRODUCT, 3).setAttribute(NAME, "after"));
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		assertEntities(engine, name, 3);
	}

	/**
	 * Reads every requested entity through a newly admitted session.
	 */
	private static void assertEntities(@Nonnull Evita engine, @Nonnull String name, @Nonnull int... keys) {
		try (final EvitaSessionContract session = engine.createReadOnlySession(name)) {
			for (final int key : keys) {
				assertTrue(session.getEntity(PRODUCT, key).isPresent(), "Missing entity " + key + " under " + name);
			}
		}
	}

	/**
	 * Reads the schema and the entity a schema-changing commit defined, through the surviving name.
	 *
	 * @param engine     the engine to read through
	 * @param entityType the entity type the commit defined
	 */
	private static void assertEntityTypeReadable(@Nonnull Evita engine, @Nonnull String entityType) {
		engine.queryCatalog(TARGET, session -> {
			assertTrue(session.getEntitySchema(entityType).isPresent(), "Missing schema of " + entityType);
			assertTrue(session.getEntity(entityType, 1).isPresent(), "Missing entity of " + entityType);
			return null;
		});
	}

	/**
	 * A pre-handover refusal must never carry the irreversible handover marker.
	 */
	private static void assertNoHandoverMarker(@Nullable Throwable failure) {
		if (failure != null) {
			assertFalse(
				ExceptionUtils.causeChainContains(failure, CatalogHandoverFailedException.class),
				"Unexpected handover marker: " + failure
			);
		}
	}

	/**
	 * Requires one of the two refusals a queued schema notification can get from a rename: its conflict key, or the old
	 * name being gone when the round was descheduled until the rename had finished.
	 *
	 * @param roundFailure what the commit's visibility failed with
	 */
	private static void assertNotificationRefused(@Nullable Throwable roundFailure) {
		assertTrue(
			roundFailure instanceof ConflictingEngineMutationException ||
				roundFailure instanceof InvalidMutationException,
			"The schema notification of the round must be refused, but the round answered: " + roundFailure
		);
	}

	/**
	 * Requires a refusal to have taken the drain budget, and no more than a generous bound above it.
	 *
	 * @param elapsedMillis how long the operation took to answer
	 */
	private static void assertDrainBudgetSpent(long elapsedMillis) {
		assertTrue(
			elapsedMillis >= DRAIN_BUDGET_FLOOR_MILLIS && elapsedMillis < TimeUnit.SECONDS.toMillis(WAIT_SECONDS),
			"Expected the five-second budget: " + elapsedMillis
		);
	}

	/**
	 * Retries only timeout refusals, within a thirty-second bound; every other failure escapes immediately.
	 */
	private static void retryRelabel(@Nonnull Evita engine, boolean replace) {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
		do {
			try {
				if (replace) {
					engine.replaceCatalog(SOURCE, TARGET);
				} else {
					engine.renameCatalog(SOURCE, TARGET);
				}
				return;
			} catch (TransactionTimedOutException ex) {
				assertNoHandoverMarker(ex);
			}
		} while (System.nanoTime() - deadline < 0);
		fail("The relabel must succeed within thirty seconds.");
	}

	/**
	 * Starts a relabel on a daemon caller thread, preserving its precise exception for assertions.
	 */
	@Nonnull
	private static CompletableFuture<CommitVersions> relabel(
		@Nonnull Evita engine, boolean replace, @Nonnull IntConsumer observer
	) {
		final CompletableFuture<CommitVersions> result = new CompletableFuture<>();
		final Thread caller = new Thread(() -> {
			try {
				engine.applyMutation(new ModifyCatalogSchemaNameMutation(SOURCE, TARGET, replace), observer)
					.onCompletion().whenComplete((versions, failure) -> {
						if (failure == null) {
							result.complete(versions);
						} else {
							result.completeExceptionally(ExceptionUtils.unwrapCompletionWrappers(failure));
						}
					});
			} catch (Throwable ex) {
				result.completeExceptionally(ex);
			}
		}, "held-round-relabel");
		caller.setDaemon(true);
		caller.start();
		return result;
	}

	/**
	 * Waits until the relabel has suspended the sessions of its source, which is where it goes on to wait for the
	 * pipeline, or has answered without getting there.
	 *
	 * @param engine    the engine running the relabel
	 * @param operation the relabel
	 */
	private static void awaitSuspension(@Nonnull Evita engine, @Nonnull CompletableFuture<CommitVersions> operation) {
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(
			() -> operation.isDone() || engine.obtainCatalogSessionRegistry(SOURCE)
				.map(SessionRegistry::isSuspended).orElse(false)
		);
	}

	/**
	 * Writes a product, and optionally defines a new entity type with one entity, then closes the session without
	 * waiting for anything. The caller must have occupied the transaction pool, so that the commit has not started when
	 * this returns.
	 *
	 * @param engine        the engine to write through
	 * @param behavior      the stage the commit returns at
	 * @param newEntityType the entity type to define, or null for a commit that leaves the schema alone
	 * @param productKey    the primary key of the product
	 * @return the progress of the commit
	 */
	@Nonnull
	private static CommitProgress commit(
		@Nonnull Evita engine, @Nonnull CommitBehavior behavior, @Nullable String newEntityType, int productKey
	) {
		final EvitaSessionContract session = engine.createSession(
			new SessionTraits(SOURCE, behavior, SessionFlags.READ_WRITE)
		);
		if (newEntityType != null) {
			session.defineEntitySchema(newEntityType).withoutGeneratedPrimaryKey().updateVia(session);
			session.upsertEntity(session.createNewEntity(newEntityType, 1));
		}
		session.upsertEntity(session.createNewEntity(PRODUCT, productKey).setAttribute(NAME, "acknowledged"));
		final CommitProgress commit = session.closeNowWithProgress();
		assertFalse(
			commit.onWalAppended().toCompletableFuture().isDone(),
			"Precondition: the commit must not start before its listener is registered."
		);
		return commit;
	}

	/**
	 * Registers the hold before the occupied transaction pool can process the commit.
	 *
	 * @param engine        the engine to write through
	 * @param behavior      the stage the commit returns at
	 * @param newEntityType the entity type the commit defines, or null for a commit that leaves the schema alone
	 * @return the held round
	 */
	@Nonnull
	private static HeldRound heldRound(
		@Nonnull Evita engine, @Nonnull CommitBehavior behavior, @Nullable String newEntityType
	) {
		final CountDownLatch releasePool = occupyTransactionThreads(engine, TRANSACTION_THREADS);
		try {
			final HeldRound round = new HeldRound(commit(engine, behavior, newEntityType, ACKNOWLEDGED_PRIMARY_KEY));
			round.hold();
			return round;
		} finally {
			// in the finally block, so that a failed precondition does not strand the threads of the pool
			releasePool.countDown();
		}
	}

	/**
	 * Creates an engine with the immediate request executor, whichever way the test JVM is configured: that executor is
	 * what runs the listener of the acknowledgement on the thread that pushes the trunk task.
	 *
	 * @param configuration the configuration of the engine
	 * @return the engine, possibly still initializing
	 */
	@Nonnull
	private static Evita newEngine(@Nonnull EvitaConfiguration configuration) {
		return new Evita(configuration, true, null, null, true);
	}

	/**
	 * Creates an engine and waits for it.
	 *
	 * @param configuration the configuration of the engine
	 * @return the engine, fully initialized
	 */
	@Nonnull
	private static Evita startEngine(@Nonnull EvitaConfiguration configuration) {
		final Evita engine = newEngine(configuration);
		engine.waitUntilFullyInitialized();
		return engine;
	}

	/**
	 * Occupies threads of the transaction pool until the returned latch opens, so that a commit cannot start before its
	 * listener is registered.
	 *
	 * @param engine  the engine whose pool to occupy
	 * @param threads how many threads to occupy
	 * @return the latch that lets the threads go
	 */
	@Nonnull
	private static CountDownLatch occupyTransactionThreads(@Nonnull Evita engine, int threads) {
		final CountDownLatch occupied = new CountDownLatch(threads);
		final CountDownLatch release = new CountDownLatch(1);
		for (int i = 0; i < threads; i++) {
			engine.getTransactionExecutor().execute(
				() -> {
					occupied.countDown();
					awaitLatch(release, WAIT_SECONDS);
				}
			);
		}
		assertTrue(awaitLatch(occupied, WAIT_SECONDS), "Precondition: the transaction pool must be occupied.");
		return release;
	}

	/**
	 * Waits for a future to end. A future still pending at the bound fails the test at once, so that it can never pass
	 * for an expected failure.
	 *
	 * @param future the future
	 * @return what it failed with, or null when it succeeded
	 */
	@Nullable
	private static Throwable awaitFailure(@Nonnull CompletableFuture<CommitVersions> future) {
		try {
			future.get(WAIT_SECONDS, TimeUnit.SECONDS);
			return null;
		} catch (ExecutionException ex) {
			return ex.getCause();
		} catch (TimeoutException ex) {
			return fail("The future must end within " + WAIT_SECONDS + " seconds.", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return fail("Interrupted while waiting for the future to end.", ex);
		}
	}

	/**
	 * Closes an engine, without a broken catalog keeping the test from reporting what it found.
	 *
	 * @param engine the engine, if there is one
	 */
	private static void closeQuietly(@Nullable Evita engine) {
		if (engine != null) {
			try {
				engine.close();
			} catch (RuntimeException ex) {
				// the outcome has been read by now, and what it says is what the test reports
			}
		}
	}

	/**
	 * Creates isolated storage and the bounded transaction pool used by every held round.
	 */
	@Nonnull
	private EvitaConfiguration configuration() throws IOException {
		this.paths = createTestPaths("CatalogHandoverTrunkRoundRace");
		Files.createDirectories(this.paths.storage());
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.transactionThreadPool(
						ThreadPoolOptions.transactionThreadPoolBuilder()
							.minThreadCount(TRANSACTION_THREADS)
							.maxThreadCount(TRANSACTION_THREADS)
							.build()
					)
					.build()
			)
			.transaction(TransactionOptions.builder().checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS).build())
			.build();
	}

	/**
	 * Starts the engine a test works with and leaves it to {@link #tearDown()} to close.
	 *
	 * @param configuration the configuration of the engine
	 * @return the engine, fully initialized
	 */
	@Nonnull
	private Evita startEngineUnderTest(@Nonnull EvitaConfiguration configuration) {
		final Evita engine = startEngine(configuration);
		this.evita = engine;
		return engine;
	}

	/**
	 * Restarts the engine and verifies committed entities through the surviving name.
	 *
	 * @param configuration the configuration of the engine
	 * @return the restarted engine
	 */
	@Nonnull
	private Evita restartAndRead(@Nonnull EvitaConfiguration configuration) {
		this.evita.close();
		this.evita = null;
		final Evita restarted = startEngine(configuration);
		this.reopened = restarted;
		assertEquals(CatalogState.ALIVE, settledState(restarted, TARGET));
		assertEntities(restarted, TARGET, BASELINE_PRIMARY_KEY, ACKNOWLEDGED_PRIMARY_KEY, 3);
		return restarted;
	}

	/**
	 * Runs the forced interleaving and judges the outcome. Everything is read first and asserted together, so that a
	 * failure reports the state before the restart and after it, rather than stopping at the first one.
	 *
	 * @param replace true to replace an existing catalog, false to rename to a free name
	 */
	private void assertAcknowledgedCommitSurvives(boolean replace) throws IOException {
		final EvitaConfiguration configuration = configuration();
		final Evita engine = startEngineUnderTest(configuration);

		if (replace) {
			defineAliveCatalog(engine, TARGET, TARGET_PRIMARY_KEY);
		}
		defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);

		final HeldRound round = heldRound(engine, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, null);
		round.assertAcknowledged();
		final CompletableFuture<CommitVersions> visible = round.commit.onChangesVisible().toCompletableFuture();
		assertFalse(visible.isDone(), "Precondition: the round of the commit must not have finished yet.");
		final long liveVersion = engine.getCatalogInstance(SOURCE).map(CatalogContract::getVersion).orElse(-1L);
		assertTrue(
			liveVersion < round.acknowledgedVersion.get(),
			"Precondition: the live view must be behind the acknowledged version, or the round is not in flight."
		);

		final CompletableFuture<CommitVersions> operation = relabel(
			engine, replace, round.releasingAtFirstTick(visible)
		);

		// the tick ends the hold on a relabel that overtakes the round; this ends it on one that waits for the round
		awaitLatch(round.tickReached, HOLD_SECONDS);
		round.release();

		final Throwable relabelFailure = awaitFailure(operation);
		final Throwable roundFailure = awaitFailure(visible);
		assertNoHandoverMarker(relabelFailure);
		if (relabelFailure != null) {
			assertInstanceOf(TransactionTimedOutException.class, relabelFailure);
			retryRelabel(engine, replace);
		}
		writeAndRead(engine, TARGET);

		final CatalogState stateBeforeRestart = settledState(engine, TARGET);
		final String readBeforeRestart = readAcknowledgedEntity(engine, TARGET);

		closeQuietly(engine);
		this.evita = null;
		final Evita restarted = newEngine(configuration);
		this.reopened = restarted;
		Throwable bootFailure = null;
		try {
			restarted.waitUntilFullyInitialized();
		} catch (RuntimeException ex) {
			bootFailure = ex;
		}
		final CatalogState stateAfterRestart = settledState(restarted, TARGET);
		final String readAfterRestart = readAcknowledgedEntity(restarted, TARGET);

		final String outcome =
			"The relabel answered: " + relabelFailure + ". The round answered: " + roundFailure + ". ";
		final Throwable theBootFailure = bootFailure;
		assertAll(
			() -> assertEquals(
				CatalogState.ALIVE, stateBeforeRestart,
				outcome + "Catalog `" + TARGET + "` must stay usable."
			),
			() -> assertEquals(
				READABLE, readBeforeRestart,
				outcome + "The acknowledged commit must be served under `" + TARGET + "`."
			),
			() -> assertNull(
				theBootFailure,
				outcome + "The engine must start again after the operation."
			),
			() -> assertEquals(
				CatalogState.ALIVE, stateAfterRestart,
				outcome + "Catalog `" + TARGET + "` must load after a restart."
			),
			() -> assertEquals(
				READABLE, readAfterRestart,
				outcome + "The acknowledged commit must be served under `" + TARGET + "` after a restart."
			)
		);
		assertEntities(restarted, TARGET, ACKNOWLEDGED_PRIMARY_KEY, 3);
	}

	/**
	 * Holds a round until the operation answers, then proves refusal, recovery, retry and durable writes.
	 *
	 * @param replace true to replace an existing catalog, false to rename to a free name
	 */
	private void assertHeldRoundRefusal(boolean replace) throws IOException {
		final EvitaConfiguration configuration = configuration();
		final Evita engine = startEngineUnderTest(configuration);
		defineAliveCatalog(engine, SOURCE, BASELINE_PRIMARY_KEY);
		if (replace) {
			defineAliveCatalog(engine, TARGET, TARGET_PRIMARY_KEY);
		}
		final HeldRound round = heldRound(engine, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, null);
		try {
			round.assertAcknowledged();
			final long started = System.nanoTime();
			final Throwable failure = awaitFailure(relabel(engine, replace, percent -> { }));
			final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			assertNoHandoverMarker(failure);
			assertInstanceOf(TransactionTimedOutException.class, failure);
			assertDrainBudgetSpent(elapsed);
			assertEquals(CatalogState.ALIVE, settledState(engine, SOURCE));
			assertEntities(engine, SOURCE, BASELINE_PRIMARY_KEY);
			if (replace) {
				assertEntities(engine, TARGET, TARGET_PRIMARY_KEY);
			} else {
				assertTrue(engine.getCatalogInstance(TARGET).isEmpty());
			}
		} finally {
			round.release();
		}
		assertNull(
			awaitFailure(round.commit.onChangesVisible().toCompletableFuture()),
			"The round must finish normally after the operation refused to wait for it."
		);
		assertEntities(engine, SOURCE, ACKNOWLEDGED_PRIMARY_KEY);
		retryRelabel(engine, replace);
		writeAndRead(engine, TARGET);
		restartAndRead(configuration);
	}

	/**
	 * The listener that keeps the trunk round from being queued, for as long as the relabel needs to get past its
	 * bootstrap record.
	 */
	private static final class HeldRound {
		private final CommitProgress commit;
		private final CountDownLatch acknowledged = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private final CountDownLatch tickReached = new CountDownLatch(1);
		private final AtomicLong acknowledgedVersion = new AtomicLong(-1L);
		private final AtomicBoolean tickSeen = new AtomicBoolean();

		HeldRound(@Nonnull CommitProgress commit) {
			this.commit = commit;
		}

		/**
		 * Registers the listener of the acknowledgement. It runs on the thread that pushes the trunk task right after.
		 */
		void hold() {
			this.commit.onWalAppended().thenAccept(
				versions -> {
					this.acknowledgedVersion.set(versions.catalogVersion());
					this.acknowledged.countDown();
					awaitLatch(this.released, WAIT_SECONDS);
				}
			);
		}

		/**
		 * Waits for the acknowledgement of the commit: until it comes, there is no round in flight to race.
		 */
		void assertAcknowledged() {
			assertTrue(
				awaitLatch(this.acknowledged, WAIT_SECONDS),
				"Precondition: the commit must be acknowledged, or no round is in flight."
			);
		}

		/**
		 * Lets the round go.
		 */
		void release() {
			this.released.countDown();
		}

		/**
		 * Creates the observer of the relabel. The first tick above zero comes after its bootstrap record and before
		 * the close of its service: the observer lets the round go there and returns only when the round has finished,
		 * so the round lands in that gap every time.
		 *
		 * @param roundFinished completes when the round has finished
		 * @return the observer
		 */
		@Nonnull
		IntConsumer releasingAtFirstTick(@Nonnull CompletableFuture<CommitVersions> roundFinished) {
			return percent -> {
				if (percent > 0 && this.tickSeen.compareAndSet(false, true)) {
					this.tickReached.countDown();
					release();
					try {
						roundFinished.get(WAIT_SECONDS, TimeUnit.SECONDS);
					} catch (ExecutionException | TimeoutException ex) {
						// the test reads the fate of the round from the same future
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
			};
		}
	}

}
