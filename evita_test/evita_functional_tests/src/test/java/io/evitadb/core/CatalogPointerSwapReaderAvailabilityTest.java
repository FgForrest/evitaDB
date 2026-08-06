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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that readers survive a catalog rename and a catalog replacement.
 *
 * This is the criterion the pointer-only design of #649 was accepted against: renaming and replacing a catalog
 * must not interrupt clients reading it. The design makes that achievable by removing the folder copy and the
 * multi-step directory dance — the window in which anything is suspended shrinks from "as long as it takes to
 * move N bytes" to a single engine-state commit — but the guarantee has limits worth pinning down rather than
 * claiming away:
 *
 * - **An in-flight query always finishes.** Suspension closes a session only once the method running on it
 *   returns (`SessionRegistry#closeAllActiveSessionsAndSuspend` defers through `executeWhenMethodIsNotRunning`),
 *   so a query is never yanked mid-flight.
 * - **A session held open across the operation is not preserved.** It is force-closed, and a client that keeps
 *   one long-lived session must reopen. The common pattern — a session per query — is unaffected.
 * - **The catalog being replaced cannot stay readable**, because its data is what the operation destroys.
 *   Failures there are confined to the invalid-usage family and must never surface as an internal error.
 *
 * The tests therefore assert on the *kind* of every failure rather than on their absence, and on the fact that
 * no reader ever observes a half-applied state. Nothing here waits on wall-clock time: the readers loop until
 * told to stop, and the only bound is a generous join.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reader availability across a pointer-only rename and replace")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CatalogPointerSwapReaderAvailabilityTest implements EvitaTestSupport {
	/**
	 * Number of concurrent readers. Small on purpose — this test is about correctness under overlap, not
	 * throughput, and the suite runs forks that already contend for CPU.
	 */
	private static final int READER_COUNT = 4;
	private static final int BRANDS_IN_TARGET = 5;
	private static final int BRANDS_IN_SOURCE = 2;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogPointerSwapReaderAvailabilityTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
		populateWithBrands(TEST_CATALOG, BRANDS_IN_TARGET);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("A rename never breaks a reader, and the data is readable under the new name straight after")
	void shouldKeepReadersWholeAcrossARename() throws InterruptedException {
		final String renamedCatalogName = TEST_CATALOG + "_renamed";
		final ReaderPool readers = new ReaderPool(TEST_CATALOG);
		readers.start();

		this.evita.renameCatalog(TEST_CATALOG, renamedCatalogName);

		// Without this the test would prove almost nothing: `renameCatalog` returns fast enough that every
		// reader could plausibly have finished before the swap even started, and a pool that never overlapped
		// the operation cannot fail however broken the operation is.
		readers.awaitIterationsAfterOperation();
		readers.stopAndJoin();

		// Every failure a reader saw must be the honest one - the catalog it was asking for stopped existing at
		// the moment the rename committed. An internal error here would mean a reader reached a folder the
		// engine had already stopped pointing at, which is precisely the class of failure the pointer swap
		// exists to make impossible.
		assertTrue(
			readers.unexpectedFailures().isEmpty(),
			() -> "Readers must only ever see invalid-usage failures, but saw: " + readers.unexpectedFailures()
		);
		assertTrue(readers.successes() > 0, "The readers must have been running before the rename started!");
		assertEquals(
			Set.of(BRANDS_IN_TARGET), readers.observedSizes(),
			"No reader may observe a collection size the catalog never had!"
		);

		// and the data is where the new name now points
		this.evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(BRANDS_IN_TARGET, session.getEntityCollectionSize(Entities.BRAND));
			}
		);
	}

	@Test
	@DisplayName("A replace never lets a reader observe a half-applied state")
	void shouldNeverServeAHalfReplacedCatalog() throws InterruptedException {
		final String sourceCatalogName = TEST_CATALOG + "_source";
		this.evita.defineCatalog(sourceCatalogName);
		populateWithBrands(sourceCatalogName, BRANDS_IN_SOURCE);

		final ReaderPool readers = new ReaderPool(TEST_CATALOG);
		readers.start();

		this.evita.replaceCatalog(sourceCatalogName, TEST_CATALOG);

		readers.awaitIterationsAfterOperation();
		readers.stopAndJoin();

		assertTrue(
			readers.unexpectedFailures().isEmpty(),
			() -> "Readers must only ever see invalid-usage failures, but saw: " + readers.unexpectedFailures()
		);
		assertTrue(readers.successes() > 0, "The readers must have been running before the replace started!");
		// The pointer swap is atomic from a reader's point of view: the name resolves either to the folder it
		// resolved to before or to the one it resolves to after, and never to a folder mid-rewrite. A copy-based
		// implementation is what would let a size in between be observed.
		assertTrue(
			Set.of(BRANDS_IN_TARGET, BRANDS_IN_SOURCE).containsAll(readers.observedSizes()),
			() -> "Readers observed a collection size the catalog never had: " + readers.observedSizes()
		);
		// and they saw *both* sides of the swap, which is what makes the assertion above evidence rather than a
		// statement about a pool that stopped reading before anything happened
		assertEquals(
			Set.of(BRANDS_IN_TARGET, BRANDS_IN_SOURCE), readers.observedSizes(),
			"The readers must have kept serving across the swap, observing the catalog before and after it!"
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(BRANDS_IN_SOURCE, session.getEntityCollectionSize(Entities.BRAND));
			}
		);
	}

	/**
	 * Creates the requested number of brand entities in the named catalog and switches it to the live state, so
	 * the readers exercise the same code path a running installation would.
	 *
	 * @param catalogName catalog to populate
	 * @param brandCount  number of brand entities to create
	 */
	private void populateWithBrands(@Nonnull String catalogName, int brandCount) {
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
				for (int i = 1; i <= brandCount; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i));
				}
			}
		);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.goLiveAndClose();
			}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * A pool of threads querying one catalog in a tight loop, recording what they saw.
	 *
	 * Deliberately opens a session per query, which is how a client normally reads: the guarantee the design
	 * makes is about queries, not about sessions held open across a topology change.
	 */
	private final class ReaderPool {
		private final String catalogName;
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final CountDownLatch firstQueriesCompleted = new CountDownLatch(READER_COUNT);
		private final CountDownLatch iterationsAfterOperation = new CountDownLatch(READER_COUNT);
		private volatile boolean operationCompleted;
		private final AtomicInteger successes = new AtomicInteger();
		private final ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();
		private final Set<Integer> observedSizes = ConcurrentHashMap.newKeySet();
		private final List<Thread> threads = new ArrayList<>(READER_COUNT);

		ReaderPool(@Nonnull String catalogName) {
			this.catalogName = catalogName;
		}

		/**
		 * Spawns the readers and returns only once every one of them has completed a query, so the operation
		 * under test genuinely overlaps a running read rather than racing thread start-up.
		 */
		void start() throws InterruptedException {
			for (int i = 0; i < READER_COUNT; i++) {
				final Thread thread = new Thread(this::readUntilStopped, "reader-" + i);
				// daemon so a reader that somehow outlives the test cannot keep the surefire JVM alive
				thread.setDaemon(true);
				this.threads.add(thread);
				thread.start();
			}
			assertTrue(
				this.firstQueriesCompleted.await(30, TimeUnit.SECONDS),
				"The readers never got going, so the test would prove nothing!"
			);
		}

		/**
		 * Blocks until every reader has completed at least one further loop iteration *after* the operation
		 * under test returned, so the pool provably spans the swap instead of merely preceding it.
		 *
		 * A generous bound rather than a tight one: it returns the instant the readers get there, and only a
		 * genuine stall can exhaust it.
		 */
		void awaitIterationsAfterOperation() throws InterruptedException {
			this.operationCompleted = true;
			assertTrue(
				this.iterationsAfterOperation.await(30, TimeUnit.SECONDS),
				"The readers stopped interacting with the engine once the operation completed!"
			);
		}

		void stopAndJoin() throws InterruptedException {
			this.running.set(false);
			for (final Thread thread : this.threads) {
				thread.join(TimeUnit.SECONDS.toMillis(30));
				assertTrue(!thread.isAlive(), "A reader thread did not finish!");
			}
		}

		int successes() {
			return this.successes.get();
		}

		@Nonnull
		Set<Integer> observedSizes() {
			return this.observedSizes;
		}

		@Nonnull
		List<Throwable> unexpectedFailures() {
			return new ArrayList<>(this.unexpectedFailures);
		}

		private void readUntilStopped() {
			boolean firstQueryDone = false;
			boolean postOperationIterationDone = false;
			while (this.running.get()) {
				final boolean afterOperation = this.operationCompleted;
				try {
					CatalogPointerSwapReaderAvailabilityTest.this.evita.queryCatalog(
						this.catalogName,
						session -> {
							this.observedSizes.add(session.getEntityCollectionSize(Entities.BRAND));
						}
					);
					this.successes.incrementAndGet();
				} catch (EvitaInvalidUsageException ex) {
					// the well-defined family: the catalog stopped existing, or the session was refused while
					// the catalog it names was being swapped out. Both are answers, not breakages.
				} catch (Throwable ex) {
					this.unexpectedFailures.add(ex);
				}
				if (!firstQueryDone) {
					firstQueryDone = true;
					this.firstQueriesCompleted.countDown();
				}
				// sampled before the query rather than after, so the iteration counted here provably started
				// once the operation had already returned
				if (afterOperation && !postOperationIterationDone) {
					postOperationIterationDone = true;
					this.iterationsAfterOperation.countDown();
				}
			}
		}
	}

}
