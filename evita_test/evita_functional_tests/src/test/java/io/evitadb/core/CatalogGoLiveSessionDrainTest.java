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

package io.evitadb.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.transaction.engine.operators.MakeCatalogAliveMutationOperator;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SESSION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the engine-level go-live ({@link Evita#makeCatalogAlive(String)}) drains the session that is
 * already open on a warm-up catalog before {@code Catalog#goLive()} publishes the ALIVE bootstrap record, so that
 * no write can land on the superseded {@link io.evitadb.core.catalog.Catalog} instance - the defect of issue
 * #1495. Draining ahead of the warm-up flush as well is the operator's preference, not the property under test.
 *
 * Driven against a real {@link Evita}: the behaviour under test is the interaction of the mutation operator, the
 * engine state, the session registry and the session proxy's deferred close, and a double for any of them would
 * test the double.
 *
 * The failure path of the same operator - a drain that gives up - costs the whole of
 * {@code SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS} in wall-clock time and lives in
 * {@code LongRunningCatalogGoLiveDrainTimeoutTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine-level go-live drains incumbent warm-up sessions")
@Tag(ENGINE)
@Tag(SESSION)
@Tag(MANAGEMENT)
class CatalogGoLiveSessionDrainTest implements EvitaTestSupport {
	private static final String CATALOG = "goLiveDrainCatalog";
	/**
	 * Loggers a successful go-live has to stay silent on, and they cover two different silences.
	 *
	 * The first two carry the forced close of a warm-up session: its termination callback's failure is swallowed
	 * into the close future, so the log is the only place it is observable.
	 *
	 * The other two carry the transition's own bookkeeping, every step of which is best-effort and locally caught -
	 * {@link MakeCatalogAliveMutationOperator}'s progress-reporting, live-view and change-capture blocks on the
	 * success path, its restore-failure block on the undo path, and {@link Catalog}'s post-publication block in
	 * {@code goLive()}. None of those is observable to any other assertion in this class: a go-live that quietly
	 * took one of them still reports success, still leaves the catalog ALIVE and still counts the brand.
	 */
	private static final List<Class<?>> CAPTURED_LOGGER_CLASSES = List.of(
		EvitaSession.class, CommitProgressRecord.class, MakeCatalogAliveMutationOperator.class, Catalog.class
	);
	/**
	 * How many session attempts {@link #shouldRefuseNewSessionWithGoingLiveExceptionWhileDraining()} makes once the
	 * suspension is provably standing. A handful is enough - the answer is decided by the same branch every time;
	 * the loop only guards against a lucky first attempt.
	 */
	private static final int REFUSAL_ATTEMPTS = 20;
	/**
	 * How far the log capture walks a cause chain looking for the go-live failure. Two levels is what the real
	 * chain uses; the bound exists so a self-referential chain cannot spin the appender.
	 */
	private static final int MAX_INSPECTED_CAUSE_DEPTH = 16;
	/**
	 * Name prefix of the fixture's own threads. Shared by the thread factory and the log capture, which uses it to
	 * tell this test's log output from that of the classes running beside it.
	 */
	private static final String FIXTURE_THREAD_PREFIX = "goLiveDrain-";
	/**
	 * Bound of every positive wait in this class, mirroring {@code LongRunningCatalogGoLiveDrainTimeoutTest}.
	 * Generous on purpose - it costs nothing on a passing run and still fails a genuine hang. The one wait it does
	 * NOT govern is the negative one in the second test, which is short precisely because it cannot fail spuriously.
	 */
	private static final long POSITIVE_WAIT_SECONDS = 30;

	private TestPaths paths;
	private Evita evita;
	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogGoLiveSessionDrainTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
			}
		);
		// daemon threads so that a leaked task cannot keep the surefire fork alive (rules/testing.md)
		this.executor = Executors.newCachedThreadPool(
			runnable -> {
				final Thread thread = new Thread(runnable, FIXTURE_THREAD_PREFIX + runnable.hashCode());
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	@AfterEach
	void tearDown() {
		// each field is guarded: a failure in `setUp` leaves the later ones null, and an unguarded dereference here
		// would report a NullPointerException from the teardown instead of the real cause
		if (this.executor != null) {
			this.executor.shutdownNow();
		}
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	@Test
	@DisplayName("closes an idle incumbent warm-up session before going live and keeps what it wrote")
	void shouldCloseIdleIncumbentWarmUpSessionBeforeGoingLive() throws Throwable {
		final EvitaSessionContract incumbent = this.evita.createReadWriteSession(CATALOG);
		final UUID incumbentId = incumbent.getId();
		incumbent.upsertEntity(incumbent.createNewEntity(Entities.BRAND, 1));
		// deliberately left open - this is the incumbent the engine-level go-live has to deal with

		// forcing a session out is an orderly close, not an incident: the drain must not leave an error behind
		assertNoErrorLoggedDuring(() -> this.evita.makeCatalogAlive(CATALOG));

		assertFalse(incumbent.isActive(), "the go-live must have closed the incumbent session");
		// the drain has to RECORD the close, not merely perform it: this is the whole of the answer a client whose
		// session vanished mid-request receives (`EvitaSessionService` reads it through
		// `Evita#wasSessionForcefullyClosedForCatalog`), and it is what tells them the server took the session
		// away rather than that they closed it themselves
		assertTrue(
			this.evita.wasSessionForcefullyClosedForCatalog(CATALOG, incumbentId),
			"the drain must have recorded the incumbent as forcefully closed"
		);
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
		// a write through the superseded session must be refused, never silently applied to a dead instance
		assertThrows(
			InstanceTerminatedException.class,
			() -> incumbent.upsertEntity(incumbent.createNewEntity(Entities.BRAND, 2))
		);
		assertEquals(1, brandCount());
	}

	@Test
	@DisplayName("holds the go-live until an in-flight warm-up write has landed, then flushes it")
	void shouldWaitForInFlightWarmUpWriteBeforeGoingLive() throws Throwable {
		final EvitaSessionContract incumbent = this.evita.createReadWriteSession(CATALOG);
		final CountDownLatch writeEntered = new CountDownLatch(1);
		final CountDownLatch releaseWrite = new CountDownLatch(1);
		// the capture spans the whole sequence, because the incumbent's close - and therefore anything it logs -
		// happens on the writer thread once the parked write is released, not while the go-live is being issued
		assertNoErrorLoggedDuring(
			() -> {
				final Future<EntityReferenceContract> write = this.executor.submit(
					() -> incumbent.upsertEntity(parkedUpsert(1, writeEntered, releaseWrite))
				);
				assertTrue(
					writeEntered.await(POSITIVE_WAIT_SECONDS, SECONDS), "the write never reached the collection"
				);

				final CompletableFuture<Void> goLive = CompletableFuture.runAsync(
					() -> this.evita.makeCatalogAlive(CATALOG), this.executor
				);
				// positive wait: the placeholder is installed synchronously before the drain begins, so observing it
				// proves the go-live has started and is now inside the drain
				awaitCatalogState(CatalogState.GOING_ALIVE);
				// negative wait: the go-live must be held by the parked write. Short on purpose - it cannot fail
				// spuriously, and lengthening it only makes every run slower (rules/testing.md)
				assertThrows(TimeoutException.class, () -> goLive.get(250, MILLISECONDS));

				releaseWrite.countDown();
				assertNotNull(
					write.get(POSITIVE_WAIT_SECONDS, SECONDS),
					"the write must complete on the still-current warm-up catalog"
				);
				goLive.get(POSITIVE_WAIT_SECONDS, SECONDS);

				assertFalse(incumbent.isActive(), "the drain must have closed the incumbent once its method returned");
				assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
				assertEquals(1, brandCount(), "the write that landed before the flush must be in the alive catalog");
			}
		);

		// The in-memory count above cannot tell a surviving write from a lost one, which is exactly the shape of
		// the defect this test exists to catch: a write landing on the superseded instance still shows up in the
		// running ALIVE catalog, because `EntityCollection#createIndexCopiesForNewCatalogAttachment` carries the
		// index objects across by reference - measured on the unfixed build as one brand in memory and none after
		// a reload. Restarting on the same storage directory is what makes this an assertion about storage.
		// Outside the log capture on purpose: shutting the engine down and booting it again writes through the
		// same two loggers the capture watches, and none of that is the drain it was written to police.
		reopenEvita();
		assertEquals(
			CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow(),
			"the go-live must have published an ALIVE bootstrap record, or the reload below proves nothing"
		);
		assertEquals(
			1, brandCount(),
			"the in-flight write must have reached STORAGE, not merely the running catalog - a reload from the " +
				"same directory is what the original defect failed"
		);
	}

	@Test
	@DisplayName("answers a session opened during the transition with CatalogGoingLiveException")
	void shouldRefuseNewSessionWithGoingLiveExceptionWhileDraining() throws Exception {
		final EvitaSessionContract incumbent = this.evita.createReadWriteSession(CATALOG);
		final CountDownLatch writeEntered = new CountDownLatch(1);
		final CountDownLatch releaseWrite = new CountDownLatch(1);
		final Future<EntityReferenceContract> write = this.executor.submit(
			() -> incumbent.upsertEntity(parkedUpsert(1, writeEntered, releaseWrite))
		);
		assertTrue(writeEntered.await(POSITIVE_WAIT_SECONDS, SECONDS));
		final CompletableFuture<Void> goLive = CompletableFuture.runAsync(
			() -> this.evita.makeCatalogAlive(CATALOG), this.executor
		);
		awaitCatalogState(CatalogState.GOING_ALIVE);
		final SessionRegistry registry;
		try {
			// Gated, because the placeholder alone does not prove the state this test is about: the operator installs
			// it and suspends the registry a moment later, and an attempt made in that gap answers
			// CatalogGoingLiveException for the wrong reason - measured at 96 of the first 200 attempts landing in the
			// gap. The gate asserts the premise itself: the compare-and-set in `closeAllActiveSessionsAndSuspend`
			// publishes the REJECT suspension before the drain loop registers a single close, so from here on every
			// attempt below runs under a standing suspension and the placeholder's answer has to beat the registry's
			// "catalog terminated".
			//
			// Waiting on the incumbent's `isActive()` would observe the same ordering one step later, but polling a
			// proxied method drives the session's non-volatile `nestLevel` from a second thread while the writer
			// thread owns it - a data race the test would be introducing on production state.
			registry = awaitSuspensionPublished();
			for (int attempt = 0; attempt < REFUSAL_ATTEMPTS; attempt++) {
				assertThrows(CatalogGoingLiveException.class, () -> this.evita.createReadOnlySession(CATALOG));
			}
		} finally {
			releaseWrite.countDown();
		}
		write.get(POSITIVE_WAIT_SECONDS, SECONDS);
		goLive.get(POSITIVE_WAIT_SECONDS, SECONDS);

		// The success path's `finally { resumeOperations }` named rather than implied. Until this assertion existed
		// it was proved only by a `brandCount()` in a *different* test not throwing - an unnamed side assertion a
		// future edit to that test would silently remove. Asserted on the very instance the suspension was observed
		// on, because a rename or replace hands one registry to another name and a name-keyed lookup can answer
		// about a different one.
		assertFalse(registry.isSuspended(), "a successful go-live must lift the suspension it took");
	}

	@Test
	@DisplayName("records the session-driven go-live's own session as forcefully closed and lifts its suspension")
	void shouldRecordSessionDrivenGoLiveAsForcedClose() throws Throwable {
		final EvitaSessionContract session = this.evita.createReadWriteSession(CATALOG);
		final UUID sessionId = session.getId();
		session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

		// the session-driven route closes ITSELF before the operator's drain runs, so the drain never sees the
		// session it would otherwise have recorded - which is why `EvitaSession#goLiveAndCloseWithProgress` adds the
		// id by hand
		assertNoErrorLoggedDuring(session::goLiveAndClose);

		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
		// The only assertion standing behind the otherwise redundant suspension that method takes: the operator's
		// own drain finds it standing, drains nothing and returns, so without this the call is provably dead weight
		// to a reader and the next cleanup deletes it.
		assertTrue(
			this.evita.wasSessionForcefullyClosedForCatalog(CATALOG, sessionId),
			"a session that took its own catalog live must be recorded as forcefully closed by the go-live"
		);
		// the suspension that method publishes is lifted by the operator, on this path as on the engine-level one
		assertFalse(
			this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow().isSuspended(),
			"the session-driven go-live must not leave the registry it suspended standing"
		);
		assertEquals(1, brandCount(), "the write the closing session flushed must be in the alive catalog");
	}

	@Test
	@DisplayName("goes live on a catalog nobody has a session on, installing the registry the transition needs")
	void shouldGoLiveOnCatalogWithNoSessionRegistry() throws Throwable {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
			}
		);
		// A registry is installed lazily, by the first session opened against the name, and nothing removes one
		// afterwards - so an engine that has served a session for this catalog can never reach the operator's
		// install branch again. Restarting is what makes the premise below true.
		reopenEvita();
		assertTrue(
			this.evita.getCatalogSessionRegistry(CATALOG).isEmpty(),
			"premise: a freshly reloaded catalog must have no registry, or the install branch is not the one taken"
		);

		assertNoErrorLoggedDuring(() -> this.evita.makeCatalogAlive(CATALOG));

		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
		// The registry the operator installed is one it created itself, suspended and then has to resume. A
		// regression leaving it suspended would wedge this catalog's name for the life of the process with nothing
		// else failing - the go-live itself reports success either way.
		assertFalse(
			this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow().isSuspended(),
			"the registry the operator installed for a catalog with none must not be left suspended"
		);
		assertEquals(1, brandCount(), "a session must be admitted again through the freshly installed registry");
	}

	/**
	 * An upsert whose schema verification parks on a latch. {@code EntityCollection#upsertEntity} calls
	 * {@link EntityUpsertMutation#verifyOrEvolveSchema(SealedCatalogSchema, SealedEntitySchema, boolean)} before it
	 * touches a single index, so a write parked here is "in flight but not yet landed" - exactly the state the drain
	 * must wait out.
	 *
	 * @param primaryKey primary key of the brand the mutation creates
	 * @param entered    counted down the moment the write reaches the collection
	 * @param release    awaited by the write; counting it down lets the write proceed
	 * @return the mutation to be passed to {@link EvitaSessionContract#upsertEntity(EntityMutation)}
	 */
	@Nonnull
	private static EntityMutation parkedUpsert(
		int primaryKey,
		@Nonnull CountDownLatch entered,
		@Nonnull CountDownLatch release
	) {
		return new EntityUpsertMutation(Entities.BRAND, primaryKey, EntityExistence.MUST_NOT_EXIST, List.of()) {
			@Nonnull
			@Override
			public Optional<LocalEntitySchemaMutation[]> verifyOrEvolveSchema(
				@Nonnull SealedCatalogSchema catalogSchema,
				@Nonnull SealedEntitySchema entitySchema,
				boolean entityCollectionEmpty
			) {
				entered.countDown();
				try {
					assertTrue(
						release.await(POSITIVE_WAIT_SECONDS, SECONDS), "the parked write was never released"
					);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				return super.verifyOrEvolveSchema(catalogSchema, entitySchema, entityCollectionEmpty);
			}
		};
	}

	/**
	 * Tells whether a log event carries a {@link CatalogGoingLiveException} anywhere in its cause chain.
	 *
	 * The go-live placeholder's exception is what a session's termination callback fails with when it resolves the
	 * catalog mid-transition, and it reaches the log wrapped in a {@code TransactionException} one level up, so the
	 * chain has to be walked rather than only its head inspected. Bounded rather than walked to null, because a
	 * cause chain that refers back into itself would otherwise spin here for ever.
	 *
	 * @param event the captured log event
	 * @return true when the event reports a failure against a catalog that was going live
	 */
	private static boolean carriesGoingLiveFailure(@Nonnull ILoggingEvent event) {
		IThrowableProxy current = event.getThrowableProxy();
		for (int depth = 0; current != null && depth < MAX_INSPECTED_CAUSE_DEPTH; depth++) {
			if (CatalogGoingLiveException.class.getName().equals(current.getClassName())) {
				return true;
			}
			final IThrowableProxy cause = current.getCause();
			current = cause == current ? null : cause;
		}
		return false;
	}

	/**
	 * Runs the action with an ERROR-level log capture attached to {@link #CAPTURED_LOGGER_CLASSES}, and fails when
	 * the action left any error entry behind.
	 *
	 * Neither of the two silences it polices reaches the caller. A drained session's termination callback failure
	 * is discarded by the drain along with the close future's exception, on purpose; and every step of the
	 * transition's own post-commit bookkeeping is best-effort and locally caught, because the go-live is durable by
	 * then and must not be undone by its own reporting. Both are therefore invisible to every assertion about the
	 * go-live itself, and reading the log is what makes them visible.
	 *
	 * @param action the go-live sequence to run under the capture
	 * @throws Throwable whatever the action throws
	 */
	private static void assertNoErrorLoggedDuring(@Nonnull Executable action) throws Throwable {
		// Test classes run concurrently inside one surefire fork (`junit-platform.properties`) and these loggers are
		// process-wide, so an unfiltered capture would blame this drain for an error another class's session logged
		// in the same second. THREE filters, because no one of them covers the case alone:
		//
		// - **by thread**, which catches an error reported on the thread that drove the go-live: the calling thread
		//   in `shouldCloseIdleIncumbentWarmUpSessionBeforeGoingLive`, where `makeCatalogAlive` is synchronous, and
		//   a fixture thread in `shouldWaitForInFlightWarmUpWriteBeforeGoingLive`;
		// - **by throwable**, which catches the error this test is actually about wherever it is reported. A warm-up
		//   close dispatches its flush to the transaction executor, and the termination callback that logs runs when
		//   the commit progress completes - so completion can land on an `Evita-transaction-N` worker that the thread
		//   filter rejects. It appears to run inline today because a one-entity flush is already complete when the
		//   composition is chained, and that is a race rather than an invariant: on a busier box the thread filter
		//   alone would silently stop catching anything.
		// - **by catalog name**, which catches the transition's own post-commit bookkeeping. That block runs in the
		//   `ProgressingFuture` completion lambda on an `Evita-transaction-N` worker, so the thread filter rejects
		//   it, and its throwable is whatever the notification failed with rather than a `CatalogGoingLiveException`,
		//   so the throwable filter rejects it too. Every one of those messages names the catalog, and this test's
		//   catalog name occurs nowhere else in the repository, so the branch cannot pick up a neighbouring class
		//   in the shared fork either.
		//
		// The throwable branch stays specific to `CatalogGoingLiveException`, so it cannot pick up a neighbouring
		// class: no other functional test takes a catalog live with a session still open on it.
		final String callingThreadName = Thread.currentThread().getName();
		final List<ILoggingEvent> errors = Collections.synchronizedList(new ArrayList<>());
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				if (eventObject.getLevel() != Level.ERROR) {
					return;
				}
				final String threadName = eventObject.getThreadName();
				if (callingThreadName.equals(threadName)
					|| threadName.startsWith(FIXTURE_THREAD_PREFIX)
					|| carriesGoingLiveFailure(eventObject)
					|| eventObject.getFormattedMessage().contains(CATALOG)) {
					errors.add(eventObject);
				}
			}
		};
		final List<Logger> capturedLoggers = new ArrayList<>(CAPTURED_LOGGER_CLASSES.size());
		for (Class<?> loggingClass : CAPTURED_LOGGER_CLASSES) {
			// asserted rather than assumed: an assumption here would abort the whole test method, silently erasing
			// the drain, the surviving write and the ALIVE transition along with the log check
			capturedLoggers.add(
				assertInstanceOf(
					Logger.class,
					LoggerFactory.getLogger(loggingClass),
					"Logback backend required to capture log output."
				)
			);
		}
		appender.start();
		capturedLoggers.forEach(it -> it.addAppender(appender));
		try {
			action.execute();
		} finally {
			capturedLoggers.forEach(it -> it.detachAppender(appender));
			appender.stop();
		}
		if (!errors.isEmpty()) {
			final ILoggingEvent firstError = errors.get(0);
			fail(
				"the go-live logged an error - the drain or its own bookkeeping: " + firstError.getFormattedMessage() +
					(firstError.getThrowableProxy() == null ?
						"" : " / " + firstError.getThrowableProxy().getClassName())
			);
		}
	}

	/**
	 * Closes the running engine and opens a new one on the same storage directory - the restart idiom the storage
	 * tests around this one use ({@code WarmUpCompactionReloadTest},
	 * {@code TransactionalMergeFlushFailureSuspendTest}).
	 *
	 * {@link Evita#waitUntilFullyInitialized()} is not optional: catalogs are loaded on the service pool, so the
	 * constructor returns while the catalog is still `BEING_ACTIVATED` and a query issued before that settles fails
	 * with `CatalogTransitioningException`, saying nothing about what the reload found.
	 *
	 * The field is reassigned, so the fixture's teardown closes the instance that is actually open. Should the
	 * reopen itself fail, the teardown closes the already-closed one instead - harmless, because {@link Evita#close()}
	 * is a compare-and-set.
	 */
	private void reopenEvita() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Spins until the go-live's drain has published its suspension on the catalog's registry, bounded at
	 * {@value #POSITIVE_WAIT_SECONDS} seconds.
	 *
	 * The registry exists by the time this is called - the incumbent session created it - and
	 * {@link SessionRegistry#isSuspended()} flips on the compare-and-set that opens
	 * {@link SessionRegistry#closeAllActiveSessionsAndSuspend}, which is the earliest moment from which a session
	 * request is guaranteed to meet a standing REJECT suspension.
	 *
	 * @return the registry the suspension was observed on, so that the caller asserting the resume asserts it on
	 *         the same instance rather than on whatever answers to the name later
	 */
	@Nonnull
	private SessionRegistry awaitSuspensionPublished() {
		final SessionRegistry registry = this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow();
		awaitUntil(registry::isSuspended, "the drain never published its suspension");
		return registry;
	}

	/**
	 * Spins until the catalog reports the given state, bounded at {@value #POSITIVE_WAIT_SECONDS} seconds. A
	 * positive wait with no latch to hang on: the state is an atomic read of the engine state, so the spin sees it
	 * the moment the updater publishes it.
	 *
	 * @param expected state the catalog has to reach
	 */
	private void awaitCatalogState(@Nonnull CatalogState expected) {
		awaitUntil(
			() -> this.evita.getCatalogState(CATALOG).orElseThrow() == expected,
			"catalog never reached " + expected
		);
	}

	/**
	 * Spins until the condition holds, bounded at {@value #POSITIVE_WAIT_SECONDS} seconds. The condition is checked
	 * before the deadline is, so a condition that is already true never fails on an exhausted bound.
	 *
	 * @param condition the state the caller is waiting for
	 * @param message   what to report when it never arrives
	 */
	private static void awaitUntil(@Nonnull BooleanSupplier condition, @Nonnull String message) {
		final long deadline = System.nanoTime() + SECONDS.toNanos(POSITIVE_WAIT_SECONDS);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, message);
			Thread.onSpinWait();
		}
	}

	/**
	 * Counts the brands the catalog currently holds, through a session of its own.
	 *
	 * @return number of entities in the brand collection
	 */
	private int brandCount() {
		final Function<EvitaSessionContract, Integer> counter =
			session -> session.getEntityCollectionSize(Entities.BRAND);
		return this.evita.queryCatalog(CATALOG, counter);
	}
}
