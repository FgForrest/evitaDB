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
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.session.SessionRegistry;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SESSION;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the engine-level go-live ({@link Evita#makeCatalogAlive(String)}) drains the session that is
 * already open on a warm-up catalog before the warm-up flush runs, so that no write can land on the superseded
 * {@link io.evitadb.core.catalog.Catalog} instance - the defect of issue #1495.
 *
 * Driven against a real {@link Evita}: the behaviour under test is the interaction of the mutation operator, the
 * engine state, the session registry and the session proxy's deferred close, and a double for any of them would
 * test the double.
 *
 * The failure path of the same operator - a drain that gives up - needs more than the drain's five-second bound
 * and lives in {@code LongRunningCatalogGoLiveDrainTimeoutTest}.
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
	 * Loggers a forcefully closed warm-up session reports through. The termination callback's failure is swallowed
	 * into the close future, so the log is the only place it is observable.
	 */
	private static final Class<?>[] CAPTURED_LOGGER_CLASSES = {EvitaSession.class, CommitProgressRecord.class};
	/**
	 * How many session attempts test three makes once the suspension is provably standing. A handful is enough - the
	 * answer is decided by the same branch every time; the loop only guards against a lucky first attempt.
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
		incumbent.upsertEntity(incumbent.createNewEntity(Entities.BRAND, 1));
		// deliberately left open - this is the incumbent the engine-level go-live has to deal with

		// forcing a session out is an orderly close, not an incident: the drain must not leave an error behind
		assertNoErrorLoggedDuring(() -> this.evita.makeCatalogAlive(CATALOG));

		assertFalse(incumbent.isActive(), "the go-live must have closed the incumbent session");
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
				assertTrue(writeEntered.await(30, SECONDS), "the write never reached the collection");

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
				assertNotNull(write.get(30, SECONDS), "the write must complete on the still-current warm-up catalog");
				goLive.get(30, SECONDS);

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
		assertTrue(writeEntered.await(30, SECONDS));
		final CompletableFuture<Void> goLive = CompletableFuture.runAsync(
			() -> this.evita.makeCatalogAlive(CATALOG), this.executor
		);
		awaitCatalogState(CatalogState.GOING_ALIVE);
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
			awaitSuspensionPublished();
			for (int attempt = 0; attempt < REFUSAL_ATTEMPTS; attempt++) {
				assertThrows(CatalogGoingLiveException.class, () -> this.evita.createReadOnlySession(CATALOG));
			}
		} finally {
			releaseWrite.countDown();
		}
		write.get(30, SECONDS);
		goLive.get(30, SECONDS);
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
					assertTrue(release.await(30, SECONDS), "the parked write was never released");
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
	 * Runs the action with an ERROR-level log capture attached to the loggers a forcefully closed warm-up session
	 * reports through, and fails when the action left any error entry behind.
	 *
	 * A drained session's termination callback failure never reaches the caller - the drain discards the close
	 * future's exception on purpose - so an orderly forced close that is quietly failing is invisible to every
	 * assertion about the go-live itself. Reading the log is what makes it visible.
	 *
	 * @param action the go-live sequence to run under the capture
	 * @throws Throwable whatever the action throws
	 */
	private static void assertNoErrorLoggedDuring(@Nonnull Executable action) throws Throwable {
		// Test classes run concurrently inside one surefire fork (`junit-platform.properties`) and these two loggers
		// are process-wide, so an unfiltered capture would blame this drain for an error another class's session
		// logged in the same second. TWO filters, because neither covers the case alone:
		//
		// - **by thread**, which catches an error reported on the thread that drove the go-live: the calling thread
		//   in test one, where `makeCatalogAlive` is synchronous, and a fixture thread in test two;
		// - **by throwable**, which catches the error this test is actually about wherever it is reported. A warm-up
		//   close dispatches its flush to the transaction executor, and the termination callback that logs runs when
		//   the commit progress completes - so completion can land on an `Evita-transaction-N` worker that the thread
		//   filter rejects. It appears to run inline today because a one-entity flush is already complete when the
		//   composition is chained, and that is a race rather than an invariant: on a busier box the thread filter
		//   alone would silently stop catching anything.
		//
		// The throwable branch stays specific to `CatalogGoingLiveException`, so it cannot pick up a neighbouring
		// class: no other functional test takes a catalog live with a session still open on it.
		final String callingThreadName = Thread.currentThread().getName();
		final List<ILoggingEvent> errors = Collections.synchronizedList(new ArrayList<>());
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(@Nonnull ILoggingEvent eventObject) {
				if (eventObject.getLevel() != Level.ERROR) {
					return;
				}
				final String threadName = eventObject.getThreadName();
				if (callingThreadName.equals(threadName)
					|| threadName.startsWith(FIXTURE_THREAD_PREFIX)
					|| carriesGoingLiveFailure(eventObject)) {
					errors.add(eventObject);
				}
			}
		};
		final List<Logger> capturedLoggers = new ArrayList<>(CAPTURED_LOGGER_CLASSES.length);
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
				"the drain logged an error while closing the incumbent: " + firstError.getFormattedMessage() +
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
	 * Spins until the go-live's drain has published its suspension on the catalog's registry, bounded at 30 s.
	 *
	 * The registry exists by the time this is called - the incumbent session created it - and
	 * {@link SessionRegistry#isSuspended()} flips on the compare-and-set that opens
	 * {@link SessionRegistry#closeAllActiveSessionsAndSuspend}, which is the earliest moment from which a session
	 * request is guaranteed to meet a standing REJECT suspension.
	 */
	private void awaitSuspensionPublished() {
		final SessionRegistry registry = this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow();
		final long deadline = System.nanoTime() + SECONDS.toNanos(30);
		while (!registry.isSuspended()) {
			assertTrue(System.nanoTime() < deadline, "the drain never published its suspension");
			Thread.onSpinWait();
		}
	}

	/**
	 * Spins until the catalog reports the given state, bounded at 30 s. A positive wait with no latch to hang on:
	 * the state is an atomic read of the engine state, so the spin sees it the moment the updater publishes it.
	 *
	 * @param expected state the catalog has to reach
	 */
	private void awaitCatalogState(@Nonnull CatalogState expected) {
		final long deadline = System.nanoTime() + SECONDS.toNanos(30);
		while (this.evita.getCatalogState(CATALOG).orElseThrow() != expected) {
			assertTrue(System.nanoTime() < deadline, "catalog never reached " + expected);
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
