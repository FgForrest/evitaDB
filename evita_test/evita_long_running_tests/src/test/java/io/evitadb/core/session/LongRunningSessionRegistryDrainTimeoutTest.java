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

package io.evitadb.core.session;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.SLOW;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the arm of {@link SessionRegistry#closeAllActiveSessionsAndSuspend(SuspendOperation)} that the drain's
 * bounded wait was written for and that no other test reaches: a forced close that has **already started** and whose
 * completion never arrives.
 *
 * **Why another class, when `LongRunningCatalogGoLiveDrainTimeoutTest` already times a drain out.** That test parks
 * a warm-up write inside the schema check, so `EvitaSessionProxy` postpones the forced close, `futures` stays empty
 * and {@link CompletableFuture#allOf(CompletableFuture[])} over an empty array is complete before it is awaited -
 * the drain merely spins its budget out. Restoring the unbounded {@code join()} the range replaced therefore leaves
 * that test green: the bounded {@code get(remainingNanos, NANOSECONDS)} is never entered at all. Here the close runs
 * inline - nothing has ever entered a business method on the proxy, so
 * {@code EvitaSessionProxy#executeWhenMethodIsNotRunning} runs the lambda immediately - and the future it collects is
 * one that is never completed, which is the only way into the wait itself.
 *
 * **Driven against mocks, and it has to be.** A real session whose close hangs means a hung flush, which no engine
 * fixture can produce without a production seam; the registry's own admission path, on the other hand, takes any
 * {@link EvitaSession} the caller supplies, which `LongRunningSessionRegistryWarmUpAdmissionTest` already relies on.
 * The mock never runs a termination callback, so it never leaves {@code activeSessions} - which is exactly the state
 * the drain has to give up on.
 *
 * **The third method is about the deferred arm's COST rather than its outcome** - what the draining thread does
 * with the budget it cannot shorten. That arm collects no future at all, so the wait above it returns instantly and
 * the only thing standing between the drain and a pegged core is the park between passes.
 *
 * **Calibration, and the one hazard.** Every timing bound below is priced by
 * {@code SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS}, mirrored here as {@link #DRAIN_GIVE_UP_BUDGET_MILLIS};
 * raising the production constant means re-pricing this class. The counterfactual for the first method is the
 * unbounded {@code join()}, under which the test **hangs rather than fails** - a counterfactual that hangs is still
 * a counterfactual, but a reader expecting a red assertion will misdiagnose the run. Kill the fork and read the
 * stack: it stands in {@code CompletableFuture.waitingGet}. The third method's counterfactual is measured in both
 * directions and quoted at {@link #BUSY_SPIN_CPU_PERMILLE}. The whole class costs about 11 s - two budgets and
 * one interrupted wait.
 *
 * Run it with:
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningSessionRegistryDrainTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SLOW)
@Tag(ENGINE)
@Tag(SESSION)
@DisplayName("Long-running session drain bound and interruption")
class LongRunningSessionRegistryDrainTimeoutTest {
	private static final String TEST_CATALOG = "drainTimeoutCatalog";
	private static final long CATALOG_VERSION = 1L;
	/**
	 * Mirror of {@code SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS}, which is private. Every bound in this class is
	 * expressed as a fraction or a multiple of it, so that raising the production constant is a visible re-pricing
	 * rather than a silent one: at five seconds the first method costs the whole budget, and the third measures the
	 * processor time spent over it.
	 */
	private static final long DRAIN_GIVE_UP_BUDGET_MILLIS = 5000L;
	/**
	 * Bound of every positive wait in this class. Generous on purpose - it costs nothing on a passing run and still
	 * fails a genuine hang.
	 */
	private static final long POSITIVE_WAIT_SECONDS = 30;
	/**
	 * Name prefix of the fixture's own threads, so that a stack dump taken from a stuck run says which test parked
	 * which thread.
	 */
	private static final String FIXTURE_THREAD_PREFIX = "drainTimeout-";
	/**
	 * The share of the drain's wall-clock budget - in parts per thousand - that the draining thread may spend **on a
	 * processor** before the wait is a busy-spin rather than a wait.
	 *
	 * **Measured on both sides, not estimated** (24-core Linux box, OpenJDK 17, otherwise idle): the drain parking
	 * ten milliseconds between passes spends **43 ms of processor time over 5 002 ms of waiting** - 0.86 % - while
	 * the same drain re-looping immediately, which is what this class was written against, spends **4 966 ms over
	 * 5 002 ms**, or 99.3 %. Ten percent sits almost exactly halfway between the two on a logarithmic scale, an
	 * order of magnitude clear of each, which is what keeps scheduling noise, a loaded box or a co-scheduled
	 * surefire fork from moving the answer across it.
	 */
	private static final long BUSY_SPIN_CPU_PERMILLE = 100;

	@Test
	@DisplayName("Gives up on a forced close that never completes instead of waiting for it for ever")
	void shouldGiveUpOnACloseThatNeverCompletes() {
		final SessionRegistry registry = newRegistry();
		final CountDownLatch closeStarted = new CountDownLatch(1);
		final EvitaSession session = mockSession();
		// the close STARTS and never finishes - the state an unbounded `join` waited out for ever, and the only one
		// that reaches the bounded wait at all
		Mockito.when(session.closeNow(Mockito.any())).thenAnswer(
			invocation -> {
				closeStarted.countDown();
				return new CompletableFuture<CommitVersions>();
			}
		);
		registry.addSession(false, () -> session);

		final long start = System.nanoTime();
		final GenericEvitaInternalError failure = assertThrows(
			GenericEvitaInternalError.class,
			() -> registry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT),
			"a drain whose close never completes must fail its premise, not report the catalog quiesced"
		);
		final long elapsedNanos = System.nanoTime() - start;

		// pins WHICH internal error, so that the assertion cannot be satisfied by any other premise in the registry
		assertTrue(
			failure.getMessage().contains("didn't clean themselves"),
			"the failure must be the drain giving up, but was: " + failure.getMessage()
		);
		// The load-bearing half, and the reason this class exists: it proves the close was STARTED, so a future was
		// collected and the wait below had something to wait on. Without it the same exception is produced by the
		// deferred arm, where `allOf` is complete before it is awaited and the bound is never exercised.
		assertEquals(
			0L, closeStarted.getCount(),
			"the drain never started the close, so it never entered the bounded wait this test is about"
		);
		// The wait actually waited: anything materially shorter would mean the loop fell through without awaiting
		// the future at all. Only ever made MORE true by a loaded box, so it cannot flake.
		assertTrue(
			elapsedNanos >= MILLISECONDS.toNanos(DRAIN_GIVE_UP_BUDGET_MILLIS) / 10 * 9,
			"the drain returned before spending its budget - it cannot have awaited the close future"
		);
		// And it was BOUNDED. Secondary to the fact that this method returned at all - under the unbounded `join`
		// the call never comes back and the test hangs rather than fails - but it still catches a bound raised past
		// what this class is priced for.
		assertTrue(
			elapsedNanos < SECONDS.toNanos(POSITIVE_WAIT_SECONDS),
			"the drain waited far past its budget - the wait is no longer bounded by it"
		);
	}

	@Test
	@DisplayName("Abandons the wait and hands the interrupt on when the draining thread is interrupted")
	void shouldRestoreTheInterruptFlagWhenTheDrainIsInterrupted() throws Exception {
		final SessionRegistry registry = newRegistry();
		final CountDownLatch closeStarted = new CountDownLatch(1);
		final EvitaSession session = mockSession();
		Mockito.when(session.closeNow(Mockito.any())).thenAnswer(
			invocation -> {
				closeStarted.countDown();
				return new CompletableFuture<CommitVersions>();
			}
		);
		registry.addSession(false, () -> session);

		final AtomicReference<Throwable> outcome = new AtomicReference<>();
		final AtomicBoolean interruptFlagOnUnwind = new AtomicBoolean();
		final AtomicLong finishedAt = new AtomicLong();
		final CountDownLatch drainFinished = new CountDownLatch(1);
		// on a thread of its own, because the interrupt has to be delivered to the thread that is inside the wait
		final Thread drain = new Thread(
			() -> {
				try {
					registry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);
				} catch (Throwable ex) {
					outcome.set(ex);
				} finally {
					// read HERE rather than after the join: whether the flag survived the drain's own unwinding is
					// the whole question, and reading it from another thread would answer a different one
					interruptFlagOnUnwind.set(Thread.currentThread().isInterrupted());
					finishedAt.set(System.nanoTime());
					drainFinished.countDown();
				}
			},
			FIXTURE_THREAD_PREFIX + "interrupted"
		);
		drain.setDaemon(true);
		drain.start();

		assertTrue(
			closeStarted.await(POSITIVE_WAIT_SECONDS, SECONDS),
			"the drain never started the close it then waits on"
		);
		// No race to lose either way: `CompletableFuture#get(long, TimeUnit)` tests the interrupt flag on entry, so
		// an interrupt landing between the close and the wait is honoured just as one landing inside it is
		final long interruptedAt = System.nanoTime();
		drain.interrupt();

		assertTrue(
			drainFinished.await(POSITIVE_WAIT_SECONDS, SECONDS),
			"the interrupted drain never unwound"
		);
		assertNotNull(outcome.get(), "the interrupted drain reported the catalog quiesced");
		assertInstanceOf(GenericEvitaInternalError.class, outcome.get());
		assertTrue(
			outcome.get().getMessage().contains("didn't clean themselves"),
			"the failure must be the drain's own premise, but was: " + outcome.get().getMessage()
		);
		assertTrue(
			interruptFlagOnUnwind.get(),
			"the drain swallowed the interrupt instead of restoring the flag for whoever owns the thread"
		);
		// half the budget: the real path takes microseconds, while a swallowed interrupt would leave the loop
		// spinning out whatever is left of the five seconds
		assertTrue(
			finishedAt.get() - interruptedAt < MILLISECONDS.toNanos(DRAIN_GIVE_UP_BUDGET_MILLIS) / 2,
			"the interrupted drain spun out the rest of its budget instead of abandoning the wait"
		);
	}

	@Test
	@DisplayName("Waits out a deferred close without burning a processor for the whole budget")
	void shouldNotBurnTheProcessorWhileWaitingOutADeferredClose() throws Exception {
		final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		// a capability gate rather than a wait: without per-thread processor time there is nothing to measure, and
		// measuring wall time alone would assert the drain's budget, which two other tests already do
		assumeTrue(
			threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled(),
			"per-thread processor time is not available on this JVM"
		);

		final SessionRegistry registry = newRegistry();
		final EvitaSession session = mockSession();
		Mockito.when(session.closeNow(Mockito.any()))
			.thenReturn(CompletableFuture.completedFuture(new CommitVersions(CATALOG_VERSION, 1)));
		final CountDownLatch methodEntered = new CountDownLatch(1);
		final CountDownLatch releaseMethod = new CountDownLatch(1);
		Mockito.when(session.getEntityCollectionSize(Mockito.anyString())).thenAnswer(
			invocation -> {
				methodEntered.countDown();
				assertTrue(
					releaseMethod.await(POSITIVE_WAIT_SECONDS, SECONDS),
					"the parked method was never released"
				);
				return 0;
			}
		);
		final EvitaInternalSessionContract proxy = registry.addSession(false, () -> session);

		final Thread caller = new Thread(
			() -> proxy.getEntityCollectionSize("brand"),
			FIXTURE_THREAD_PREFIX + "parked"
		);
		caller.setDaemon(true);
		caller.start();
		try {
			// With a method in flight the proxy POSTPONES the forced close, so the drain collects no future at all
			// and every pass finds an already-complete `allOf`. That is the deferred arm - the one the go-live's
			// own long-running test creates - and the loop can then only wait its budget out.
			assertTrue(
				methodEntered.await(POSITIVE_WAIT_SECONDS, SECONDS),
				"the parked method never reached the session"
			);

			final long cpuStart = threads.getCurrentThreadCpuTime();
			final long start = System.nanoTime();
			assertThrows(
				GenericEvitaInternalError.class,
				() -> registry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT)
			);
			final long elapsedNanos = System.nanoTime() - start;
			final long cpuNanos = threads.getCurrentThreadCpuTime() - cpuStart;

			// The drain has to WAIT its budget out rather than spin it out: it re-scans `activeSessions` on a
			// thread that `MakeCatalogAliveMutationOperator` holds the engine state lock on, so a hung incumbent
			// costing a pegged core would push every concurrent engine mutation towards its own lock timeout. The
			// counterfactual is the same loop re-looping straight into the next pass - measured in both
			// directions, see `BUSY_SPIN_CPU_PERMILLE`.
			assertTrue(
				cpuNanos * 1000 < elapsedNanos * BUSY_SPIN_CPU_PERMILLE,
				"the drain busy-spins instead of waiting (spent " + cpuNanos / 1_000_000 +
					" ms of processor time over " + elapsedNanos / 1_000_000 + " ms of waiting)"
			);
		} finally {
			releaseMethod.countDown();
			caller.join(SECONDS.toMillis(POSITIVE_WAIT_SECONDS));
		}
	}

	/**
	 * Builds a registry over a mocked catalog, exactly as {@code LongRunningSessionRegistryWarmUpAdmissionTest}
	 * does. One catalog mock per registry: the supplier is resolved from inside the registration when the version
	 * pin is taken, and a mock minted there would be a fresh instance per session.
	 *
	 * @return a registry with no session in it, never NULL
	 */
	@Nonnull
	private static SessionRegistry newRegistry() {
		final Catalog catalog = Mockito.mock(Catalog.class, Mockito.withSettings().stubOnly());
		return new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> catalog,
			SessionRegistry.createDataStore()
		);
	}

	/**
	 * Builds a session stub complete enough to travel the registration path and to be picked up by the drain - the
	 * registry reads its id, catalog name, catalog version and traits, hands its creation timestamp to the closing
	 * event, and asks whether it is still active before taking it away from its owner.
	 *
	 * Not {@code stubOnly}, unlike the admission sweep's: these mocks are stubbed with answers that count latches
	 * down, and a handful of invocations per test is nothing to retain.
	 *
	 * @return a mocked session that reports itself active and never removes itself from the registry, never NULL
	 */
	@Nonnull
	private static EvitaSession mockSession() {
		final EvitaSession session = Mockito.mock(EvitaSession.class);
		Mockito.when(session.getId()).thenReturn(UUID.randomUUID());
		Mockito.when(session.getCatalogName()).thenReturn(TEST_CATALOG);
		Mockito.when(session.getCatalogVersion()).thenReturn(CATALOG_VERSION);
		Mockito.when(session.getSessionTraits()).thenReturn(new SessionTraits(TEST_CATALOG));
		Mockito.when(session.getCreated()).thenReturn(OffsetDateTime.now());
		// the drain asks twice - once through the proxy to decide whether to bother, once inside the close lambda -
		// and a session that answered `false` would be skipped, leaving nothing to wait for
		Mockito.when(session.isActive()).thenReturn(true);
		return session;
	}
}
