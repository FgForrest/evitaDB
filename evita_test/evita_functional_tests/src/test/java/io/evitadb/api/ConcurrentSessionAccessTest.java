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

package io.evitadb.api;

import io.evitadb.api.exception.ConcurrentSessionAccessException;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the thread-ownership guard in `EvitaSessionProxy`:
 *
 * - read-only sessions must tolerate parallel reads from multiple threads — the GraphQL API opens a single
 *   read-only session per query operation and fans individual root fields out to the request executor
 *   (see `AsyncDataFetcher`), so several `Evita-request-*` threads legitimately share one session
 * - read-write sessions must stay single-threaded — a second thread entering any business method while
 *   another thread executes one must be rejected with {@link ConcurrentSessionAccessException}, because
 *   the session's transactional diff layers and warm-up index trees are not thread-safe
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(SESSION)
@DisplayName("Evita session concurrency guard")
class ConcurrentSessionAccessTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_NAME = "name";
	private static final int PRODUCT_COUNT = 50;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("ConcurrentSessionAccessTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths).build()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.updateVia(session);
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.createNewEntity(Entities.PRODUCT, i)
						.setAttribute(ATTRIBUTE_NAME, "product-" + i)
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("read-only session tolerates parallel reads from multiple threads")
	void shouldAllowParallelReadsInReadOnlySession() throws Exception {
		final int threadCount = 4;
		final int rounds = 50;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final CyclicBarrier barrier = new CyclicBarrier(threadCount);
			final List<Throwable> concurrentAccessFailures = new CopyOnWriteArrayList<>();
			final AtomicInteger successfulReads = new AtomicInteger();

			final List<Future<?>> futures = executeInParallel(
				executor, threadCount, barrier,
				(threadIndex, round) -> {
					if (round >= rounds) {
						return false;
					}
					try {
						final int primaryKey = (round + threadIndex) % PRODUCT_COUNT + 1;
						session.getEntity(Entities.PRODUCT, primaryKey)
							.orElseThrow(() -> new IllegalStateException("Entity `" + primaryKey + "` is unexpectedly missing!"));
						successfulReads.incrementAndGet();
					} catch (ConcurrentSessionAccessException ex) {
						concurrentAccessFailures.add(ex);
					}
					return true;
				}
			);
			for (final Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}

			assertTrue(
				concurrentAccessFailures.isEmpty(),
				"Parallel reads in a read-only session must not be rejected, but " +
					concurrentAccessFailures.size() + " calls failed with ConcurrentSessionAccessException " +
					"(" + successfulReads.get() + " reads succeeded)."
			);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("read-write session rejects a second thread with ConcurrentSessionAccessException")
	void shouldRejectConcurrentAccessToReadWriteSession() throws Exception {
		final int threadCount = 2;
		final int maxRounds = 2_000;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			final CyclicBarrier barrier = new CyclicBarrier(threadCount);
			final AtomicBoolean guardTriggered = new AtomicBoolean(false);

			final List<Future<?>> futures = executeInParallel(
				executor, threadCount, barrier,
				(threadIndex, round) -> {
					if (round >= maxRounds || guardTriggered.get()) {
						return false;
					}
					try {
						final int primaryKey = round % PRODUCT_COUNT + 1;
						if (threadIndex == 0) {
							// writer thread mutates an existing entity
							session.createNewEntity(Entities.PRODUCT, primaryKey)
								.setAttribute(ATTRIBUTE_NAME, "updated-" + round)
								.upsertVia(session);
						} else {
							// reader thread races the writer on the very same session
							session.getEntity(Entities.PRODUCT, primaryKey);
						}
					} catch (ConcurrentSessionAccessException ex) {
						guardTriggered.set(true);
					}
					return true;
				}
			);
			for (final Future<?> future : futures) {
				future.get(120, TimeUnit.SECONDS);
			}

			assertTrue(
				guardTriggered.get(),
				"Concurrent access to a read-write session must be rejected with " +
					"ConcurrentSessionAccessException, but " + maxRounds + " barrier-synchronized rounds " +
					"never triggered the guard."
			);
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Submits `threadCount` workers to the executor that execute rounds in lock-step: every round starts with
	 * a barrier rendezvous so all workers enter their round body at (almost) the same instant, maximizing the
	 * overlap of the session calls performed inside. A worker signals it is done by returning `false` from its
	 * round body; the exiting worker breaks the barrier deliberately so the remaining workers do not hang
	 * waiting for a peer that will never arrive — they observe the broken barrier and exit their loop, too.
	 *
	 * @param executor    executor to run the workers on
	 * @param threadCount number of parallel workers
	 * @param barrier     barrier shared by all workers, sized to `threadCount`
	 * @param roundBody   logic executed by each worker per round; returns `false` to stop looping
	 * @return futures of all submitted workers, completing when the respective worker exits its loop
	 */
	@Nonnull
	private static List<Future<?>> executeInParallel(
		@Nonnull ExecutorService executor,
		int threadCount,
		@Nonnull CyclicBarrier barrier,
		@Nonnull RoundBody roundBody
	) {
		final List<Future<?>> futures = new ArrayList<>(threadCount);
		for (int i = 0; i < threadCount; i++) {
			final int threadIndex = i;
			futures.add(
				executor.submit(() -> {
					int round = 0;
					try {
						while (true) {
							try {
								barrier.await(10, TimeUnit.SECONDS);
							} catch (BrokenBarrierException ex) {
								// a peer exited and broke the barrier — stop looping
								return null;
							}
							if (!roundBody.execute(threadIndex, round)) {
								return null;
							}
							round++;
						}
					} finally {
						// release peers possibly waiting for this worker at the barrier
						barrier.reset();
					}
				})
			);
		}
		return futures;
	}

	/**
	 * Logic executed by a single worker in one barrier-synchronized round.
	 */
	@FunctionalInterface
	private interface RoundBody {

		/**
		 * Executes one round of the worker.
		 *
		 * @param threadIndex zero-based index of the executing worker
		 * @param round       zero-based round number
		 * @return `true` to continue with the next round, `false` to stop
		 * @throws Exception any failure propagated to the test via {@link Future#get}
		 */
		boolean execute(int threadIndex, int round) throws Exception;
	}

}
