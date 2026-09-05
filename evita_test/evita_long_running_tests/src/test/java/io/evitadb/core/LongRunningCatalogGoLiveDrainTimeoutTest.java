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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.transaction.engine.operators.MakeCatalogAliveMutationOperator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.SLOW;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the failure half of {@link MakeCatalogAliveMutationOperator}: a go-live whose drain gives up must leave
 * the catalog exactly as it found it - the warm-up instance back behind its name and the session registry no longer
 * suspended - rather than wedging it behind a `GOING_ALIVE` placeholder for the life of the process.
 *
 * The success half is pinned deterministically in the fast loop by `CatalogGoLiveSessionDrainTest`
 * (evita_test/evita_functional_tests, io.evitadb.core). This class is here and not there for one reason: making the
 * drain fail costs the drain's own bound in wall-clock time, and that bound is hard-coded.
 *
 * **Why this failure seam and no other.** The operator can also fail in `Catalog#flush()` and in `Catalog#goLive()`,
 * but neither is a usable seam for a fast test: a forced flush failure runs `markUnpublishable` and races its own
 * scheduled deactivation, so the assertions about the restored catalog can pass vacuously against a catalog that
 * was deactivated instead of restored. The drain's give-up is the only failure that is side-effect free - nothing
 * has been popped, written or published when it fires.
 *
 * **Calibration (the bound is hard-coded, and this test is priced by it).**
 *
 * - The bound is `SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS`, five seconds: the drain's `do/while` gives every
 *   incumbent that long to leave and then fails the premise `Some of the sessions didn't clean themselves` as a
 *   {@link GenericEvitaInternalError}. That constant's javadoc carries the other half of this statement, for
 *   whoever edits the bound without reading this file.
 * - The test parks a warm-up write inside `EntityUpsertMutation#verifyOrEvolveSchema` and holds it there until the
 *   go-live has already failed. The drain therefore always sees a session whose method has not returned, its forced
 *   close is deferred by `EvitaSessionProxy`, and the loop can only time out. There is no race to lose: the parked
 *   write is released by the test thread, after `makeCatalogAlive` has thrown.
 * - **How it goes blunt.** Only by raising that five-second bound past the 30 s positive waits below - the write
 *   would then be released while the drain is still running, the drain would succeed, and the whole failure path
 *   would go untested while the test stayed green. Raising the bound therefore means re-pricing every 30 s wait
 *   here. Lowering it, or making the drain cheaper, cannot blunt anything: the test only gets faster.
 * - **Measured:** the method runs in about 6 s on a 24-core Linux box, OpenJDK 17.0.20, otherwise idle (5.7 s to
 *   6.5 s across runs) - five seconds of drain plus fixture, engine boot and two go-lives. Reactor run:
 *   `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
 *
 * **Counterfactuals (all three measured; each one lands on a different assertion).**
 *
 * 1. Comment out the drain in `MakeCatalogAliveMutationOperator#applyMutation`
 *    (`sessionRegistry.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(REJECT))`): the go-live runs to
 *    completion while the write is still parked, and the first assertion fails with
 *    `Expected java.lang.Throwable to be thrown, but nothing was thrown.` - which is issue #1495 itself.
 * 2. Comment out `undoOperations.accept(ex)` in that method's `catch (Throwable ex)`: the operator still rethrows,
 *    so the first assertion passes and the failure lands on the undo instead - `expected: <WARMING_UP> but was:
 *    <GOING_ALIVE>`. The suspension assertion sits behind that one and is therefore never reached, which is why
 *    the third counterfactual exists.
 * 3. Comment out only the last line of `undoOperations` - the `SessionRegistry::resumeOperations` call - leaving the
 *    state restore in place: the catalog comes back as `WARMING_UP`, so the assertion above it passes and the
 *    failure lands on the suspension instead - `the failed go-live must not leave the registry suspended ==>
 *    expected: <false> but was: <true>`. This is what proves the resume half of the undo, which counterfactual 2
 *    cannot reach.
 *
 * Run it with:
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningCatalogGoLiveDrainTimeoutTest -Dsurefire.failIfNoSpecifiedTests=false
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SLOW)
@Tag(ENGINE)
@Tag(SESSION)
@DisplayName("Long-running go-live drain timeout and undo")
class LongRunningCatalogGoLiveDrainTimeoutTest implements EvitaTestSupport {
	private static final String CATALOG = "goLiveDrainTimeoutCatalog";
	/**
	 * Name prefix of the fixture's own threads, so that a stack dump taken from a stuck run says which test parked
	 * the writer.
	 */
	private static final String FIXTURE_THREAD_PREFIX = "goLiveDrainTimeout-";
	/**
	 * Bound of every positive wait in this class. Generous on purpose - it costs nothing on a passing run and still
	 * fails a genuine hang - but it is also what prices the drain's five-second bound; see the class javadoc.
	 */
	private static final long POSITIVE_WAIT_SECONDS = 30;

	private TestPaths paths;
	private Evita evita;
	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("LongRunningCatalogGoLiveDrainTimeoutTest");
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
	@DisplayName("restores the warm-up catalog and lifts the suspension when the incumbent outlives the drain")
	void shouldRestoreWarmUpCatalogWhenIncumbentOutlivesTheDrain() throws Exception {
		final EvitaSessionContract incumbent = this.evita.createReadWriteSession(CATALOG);
		final CountDownLatch writeEntered = new CountDownLatch(1);
		final CountDownLatch releaseWrite = new CountDownLatch(1);
		final Future<EntityReferenceContract> write = this.executor.submit(
			() -> incumbent.upsertEntity(parkedUpsert(1, writeEntered, releaseWrite))
		);
		assertTrue(writeEntered.await(POSITIVE_WAIT_SECONDS, SECONDS), "the write never reached the collection");

		// The drain gives up after five seconds with the write still parked - the go-live must fail, not proceed.
		// Called on THIS thread and asserted raw: the operator's synchronous part - placeholder, drain, flush future
		// - runs on the caller's thread inside `EngineTransactionManager#applyMutation`, before any `Progress`
		// exists, so the drain's error arrives unwrapped. No `CompletionException` was observed here, and none can
		// be: `EvitaContract#makeCatalogAlive`'s `join()` is never reached on this path.
		final Throwable failure = assertThrows(
			Throwable.class,
			() -> this.evita.makeCatalogAlive(CATALOG),
			"the go-live must not succeed while a warm-up write is still in flight"
		);
		assertInstanceOf(GenericEvitaInternalError.class, failure);
		// pins WHICH internal error: without this the assertion above would be satisfied by any premise failure
		// anywhere in the operator, and the test would stop naming the path it claims to take
		assertTrue(
			failure.getMessage().contains("didn't clean themselves"),
			"the failure must be the drain giving up, but was: " + failure.getMessage()
		);

		// undo, half one: the warm-up catalog is back behind its name, not a GOING_ALIVE placeholder
		assertEquals(CatalogState.WARMING_UP, this.evita.getCatalogState(CATALOG).orElseThrow());
		// undo, half two: the suspension the drain published has been lifted, so the catalog admits sessions again
		assertFalse(
			this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow().isSuspended(),
			"the failed go-live must not leave the registry suspended"
		);

		// the incumbent's own write still lands - on the restored instance, which is the same object it has held
		// since it was opened - and its deferred close fires on the writer thread when the parked method returns
		releaseWrite.countDown();
		assertNotNull(
			write.get(POSITIVE_WAIT_SECONDS, SECONDS),
			"the write must complete on the restored warm-up catalog"
		);
		awaitIncumbentDeparted(incumbent);
		assertEquals(1, brandCount(), "the write must be visible in the restored warm-up catalog");

		// and a second go-live, with nothing parked, succeeds. The wait is for the read-only session `brandCount()`
		// just closed: its removal from the registry is finished by the flush executor, and leaving it to the
		// operator's own drain would make this step depend on a second drain rather than on the undo under test
		awaitNoActiveSessions("the counting session never left the registry");
		this.evita.makeCatalogAlive(CATALOG);
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
		assertEquals(1, brandCount(), "the write must have survived into the alive catalog");
	}

	/**
	 * An upsert whose schema verification parks on a latch. {@code EntityCollection#upsertEntity} calls
	 * {@link EntityUpsertMutation#verifyOrEvolveSchema(SealedCatalogSchema, SealedEntitySchema, boolean)} before it
	 * touches a single index, so a write parked here is "in flight but not yet landed" - and its session's forced
	 * close is deferred by {@code EvitaSessionProxy} until the method returns, which is what makes the drain time
	 * out rather than succeed.
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
						release.await(POSITIVE_WAIT_SECONDS, SECONDS),
						"the parked write was never released"
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
	 * Waits until the incumbent session has left for good: out of the registry first, then reporting itself
	 * inactive. Bounded at {@value #POSITIVE_WAIT_SECONDS} seconds.
	 *
	 * @param incumbent the session the failed drain abandoned
	 */
	private void awaitIncumbentDeparted(@Nonnull EvitaSessionContract incumbent) {
		awaitNoActiveSessions("the incumbent never left the registry after its parked write returned");
		// Ordered strictly after the registry check, never before it. A session removes itself from the registry
		// from INSIDE its own termination callback, while `EvitaSession#beingClosed` is still set - and `isActive()`
		// reports a session in that window as ACTIVE. Asking here costs a handful of calls, on this thread alone,
		// with the writer thread long finished; spinning on it from a thread that shares the session with a running
		// method would instead drive the session's `nestLevel` concurrently, which is production state.
		final long deadline = System.nanoTime() + SECONDS.toNanos(POSITIVE_WAIT_SECONDS);
		while (incumbent.isActive()) {
			assertTrue(System.nanoTime() < deadline, "the incumbent never reported itself closed");
			Thread.onSpinWait();
		}
	}

	/**
	 * Spins until the catalog's registry holds no session at all, bounded at {@value #POSITIVE_WAIT_SECONDS}
	 * seconds. A positive wait with no latch to hang on, and deliberately the cheapest observation available: the
	 * count is read off the registry's own map and touches no session state, so it can be asked in a tight loop
	 * without perturbing what it is measuring.
	 *
	 * It is also exactly the precondition a warm-up catalog imposes on the next session - `SessionRegistry` admits
	 * one at a time and answers `ConcurrentInitializationException` to the second - so anything opening a session
	 * below has to wait for it anyway.
	 *
	 * @param reason what to report when the registry never empties
	 */
	private void awaitNoActiveSessions(@Nonnull String reason) {
		final SessionRegistry registry = this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow();
		final long deadline = System.nanoTime() + SECONDS.toNanos(POSITIVE_WAIT_SECONDS);
		while (registry.countActiveSessions().activeSessions() > 0) {
			assertTrue(System.nanoTime() < deadline, reason);
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
