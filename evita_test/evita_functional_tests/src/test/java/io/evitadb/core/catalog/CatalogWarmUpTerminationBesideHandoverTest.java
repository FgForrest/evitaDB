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

package io.evitadb.core.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Pins how {@link Catalog#terminate()} of a `WARMING_UP` catalog meets the two handovers that write a bootstrap record
 * of their own: go-live, which publishes the ALIVE record and supersedes the warm-up instance, and a rename, which
 * relabels the catalog's folder and reopens its storage under the new name.
 *
 * Neither is a flush, so neither was serialized with the termination: engine shutdown terminates the catalogs it
 * captured while an accepted go-live or rename can still be running on the engine executor, and the termination closed
 * the storage beneath a bootstrap write in progress. Each handover now runs under the lock the termination takes to
 * begin, and is refused once the termination has begun - the termination either waits for the handover to finish, or
 * the handover writes nothing.
 *
 * The tests call the two methods the operators call, {@link Catalog#goLive()} and {@link Catalog#replace}, directly,
 * and hold each inside its bootstrap write through the one seam it offers: go-live asks whether it may still publish
 * right before it writes, which a spy pauses, and the rename reports its progress once its relabel is published,
 * which the progress consumer of its future pauses. Where the termination itself must be held, a spy pauses it once it
 * has begun, at the point where it asks whether it may publish - the spy shares every mutable holder of the catalog.
 * Where engine shutdown itself has to meet a running go-live, the go-live goes through the engine and its client
 * progress observer pauses it once it runs. Every assertion about what was published reads a fresh engine opened over
 * a copy of the storage taken right after the termination.
 */
@DisplayName("Termination of a warming-up catalog beside its go-live or rename")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SESSION)
@Tag(SCHEMA)
class CatalogWarmUpTerminationBesideHandoverTest implements EvitaTestSupport {

	/** The name the rename gives the catalog. */
	private static final String RENAMED_CATALOG = "renamedCatalog";
	/** Primary key of the product published by the set-up session. */
	private static final int PUBLISHED_PRODUCT_PK = 1;
	/** Bound of every positive wait of this test. */
	private static final long POSITIVE_WAIT_SECONDS = 30L;
	/** Name prefix of the fixture's own threads, so a stack dump of a stuck run says which test owns them. */
	private static final String FIXTURE_THREAD_PREFIX = "catalog-warm-up-termination-beside-handover-test-";

	/** Releases whatever a test parked - a termination or a handover; counted down again by the tear-down. */
	private final CountDownLatch releaseParked = new CountDownLatch(1);
	/** Counted down once a parked termination has begun and reached its publication decision. */
	private final CountDownLatch terminationParked = new CountDownLatch(1);
	/** Numbers the fixture threads. */
	private final AtomicInteger fixtureThreadCounter = new AtomicInteger();
	private TestPaths paths;
	private TestPaths snapshotPaths;
	private Evita evita;
	private ExecutorService fixtureThreads;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogWarmUpTerminationBesideHandover");
		this.snapshotPaths = createTestPaths("CatalogWarmUpTerminationBesideHandoverSnapshot");
		this.fixtureThreads = Executors.newCachedThreadPool(
			runnable -> {
				final Thread thread = new Thread(
					runnable, FIXTURE_THREAD_PREFIX + this.fixtureThreadCounter.incrementAndGet()
				);
				thread.setDaemon(true);
				return thread;
			}
		);
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		// published by the close of this session - the last published state every test starts from
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.createNewEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK).upsertVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		// a test that failed before releasing what it parked must not leave it parked behind the engine close
		this.releaseParked.countDown();
		this.fixtureThreads.shutdownNow();
		this.fixtureThreads.awaitTermination(POSITIVE_WAIT_SECONDS, SECONDS);
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
		cleanupTestPaths(this.snapshotPaths);
	}

	@Nested
	@DisplayName("when a go-live is writing its ALIVE bootstrap record")
	class GoLiveUnderWay {

		@Test
		@DisplayName("should let the termination wait for the go-live")
		void shouldWaitForGoLiveUnderWayBeforeTerminating() throws IOException {
			final CatalogWarmUpTerminationBesideHandoverTest test = CatalogWarmUpTerminationBesideHandoverTest.this;
			final Catalog catalog = test.catalog();
			// go-live asks whether it may still publish right before it writes the ALIVE record - the spy pauses there
			final CountDownLatch goLiveWriting = new CountDownLatch(1);
			final Catalog goingLive = spy(catalog);
			doAnswer(
				invocation -> {
					goLiveWriting.countDown();
					awaitLatch(test.releaseParked, "the go-live was never released");
					return invocation.callRealMethod();
				}
			).when(goingLive).assertPublishable();
			final Future<Catalog> goLive = test.fixtureThreads.submit(goingLive::goLive);
			awaitLatch(goLiveWriting, "the go-live never reached its ALIVE bootstrap write");

			final CountDownLatch terminated = new CountDownLatch(1);
			final Future<?> termination = test.fixtureThreads.submit(terminating(catalog, terminated));
			final boolean terminatedDuringGoLive = queuedOnPublicationLockOrTerminated(catalog, terminated);
			test.releaseParked.countDown();
			awaitCompletion(goLive);
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertFalse(
				terminatedDuringGoLive,
				"the termination must wait for a go-live under way rather than close the storage beneath its " +
					"ALIVE bootstrap write"
			);
			assertTrue(
				goLive.isDone() && !goLive.isCancelled() && failureOf(goLive) == null,
				() -> "the go-live the termination waited for must complete, but it failed with: " + failureOf(goLive)
			);
			assertEquals(
				Optional.of(CatalogState.ALIVE), test.readSnapshotCatalogState(TEST_CATALOG),
				"the go-live the termination waited for must be published"
			);
		}
	}

	@Nested
	@DisplayName("when a go-live starts after the termination began")
	class GoLiveAfterTerminationBegan {

		@Test
		@DisplayName("should refuse the go-live before it writes anything")
		void shouldRefuseGoLiveStartingAfterTerminationBegan() throws IOException {
			final CatalogWarmUpTerminationBesideHandoverTest test = CatalogWarmUpTerminationBesideHandoverTest.this;
			final Catalog catalog = test.catalog();
			final Future<?> termination = test.fixtureThreads.submit(test.parkingTermination(catalog)::terminate);
			awaitLatch(test.terminationParked, "the termination never reached its publication decision");

			assertThrows(
				InstanceTerminatedException.class, catalog::goLive,
				"a go-live starting after the termination began must be refused"
			);
			test.releaseParked.countDown();
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertEquals(
				Optional.of(CatalogState.WARMING_UP), test.readSnapshotCatalogState(TEST_CATALOG),
				"the refused go-live must leave the catalog at its last published, warming-up state"
			);
		}
	}

	@Nested
	@DisplayName("when engine shutdown clears the engine state under a running go-live")
	class GoLiveOutlivedByEngineState {

		@Test
		@DisplayName("should release the catalog the go-live cannot put back behind its name")
		void shouldReleaseCatalogGoLiveCannotPutBackWhenShutdownClearedEngineState() {
			final CatalogWarmUpTerminationBesideHandoverTest test = CatalogWarmUpTerminationBesideHandoverTest.this;
			final Catalog catalog = test.catalog();
			// the go-live reports its progress from the engine executor once it runs - by then its placeholder stands
			// behind the catalog's name - and the observer pauses that report
			final AtomicReference<Thread> submitting = new AtomicReference<>();
			final AtomicBoolean reported = new AtomicBoolean();
			final CountDownLatch goLiveRunning = new CountDownLatch(1);
			final IntConsumer observer = percentage -> {
				if (Thread.currentThread() != submitting.get() && reported.compareAndSet(false, true)) {
					goLiveRunning.countDown();
					awaitLatch(test.releaseParked, "the go-live was never released");
				}
			};
			final Future<Progress<CommitVersions>> goLive = test.fixtureThreads.submit(
				() -> {
					submitting.set(Thread.currentThread());
					return test.evita.applyMutation(new MakeCatalogAliveMutation(TEST_CATALOG), observer);
				}
			);
			awaitLatch(goLiveRunning, "the go-live never started running");

			// the shutdown terminates the placeholder it finds behind the name, clears the engine state and then waits
			// for the go-live, which can neither record its transition nor put a catalog back behind the name
			final Future<?> shutdown = test.fixtureThreads.submit(test.evita::close);
			awaitEngineStateCleared(test.evita);
			test.releaseParked.countDown();
			awaitCompletion(shutdown);

			final Throwable goLiveFailure = failureOf(futureOf(goLive));
			assertInstanceOf(
				InstanceTerminatedException.class, rootCause(goLiveFailure),
				"the go-live must fail on the engine state the shutdown cleared, but it ended with: " + goLiveFailure
			);
			assertTrue(
				catalog.isTerminated(),
				"the catalog a go-live could not put back behind its name must be released, not left open"
			);
		}
	}

	@Nested
	@DisplayName("when a rename is relabelling the catalog")
	class RenameUnderWay {

		@Test
		@DisplayName("should let the termination wait for the rename")
		void shouldWaitForRenameUnderWayBeforeTerminating() {
			final CatalogWarmUpTerminationBesideHandoverTest test = CatalogWarmUpTerminationBesideHandoverTest.this;
			final Catalog catalog = test.catalog();
			final ProgressingFuture<CatalogContract> renaming = catalog.replace(renamedSchema(catalog), null);
			// the rename reports its progress once, synchronously, as soon as its relabelled bootstrap record is
			// published and before it reopens the storage - the progress consumer pauses that report
			final CountDownLatch relabelPublished = new CountDownLatch(1);
			final AtomicBoolean reported = new AtomicBoolean();
			renaming.setProgressConsumer(
				(done, total) -> {
					if (reported.compareAndSet(false, true)) {
						relabelPublished.countDown();
						awaitLatch(test.releaseParked, "the rename was never released");
					}
				}
			);
			final Future<?> rename = test.fixtureThreads.submit(() -> renaming.execute(Runnable::run));
			awaitLatch(relabelPublished, "the rename never published its relabel");

			final CountDownLatch terminated = new CountDownLatch(1);
			final Future<?> termination = test.fixtureThreads.submit(terminating(catalog, terminated));
			final boolean terminatedDuringRename = queuedOnPublicationLockOrTerminated(catalog, terminated);
			test.releaseParked.countDown();
			awaitCompletion(rename);
			awaitCompletion(renaming);
			awaitCompletion(termination);

			assertFalse(
				terminatedDuringRename,
				"the termination must wait for a rename under way rather than close the storage it is relabelling"
			);
			assertTrue(
				renaming.isDone() && failureOf(renaming) == null,
				() -> "the rename the termination waited for must complete, but it failed with: " +
					failureOf(renaming)
			);
		}
	}

	@Nested
	@DisplayName("when a rename starts after the termination began")
	class RenameAfterTerminationBegan {

		@Test
		@DisplayName("should refuse the rename before it relabels anything")
		void shouldRefuseRenameStartingAfterTerminationBegan() throws IOException {
			final CatalogWarmUpTerminationBesideHandoverTest test = CatalogWarmUpTerminationBesideHandoverTest.this;
			final Catalog catalog = test.catalog();
			final Future<?> termination = test.fixtureThreads.submit(test.parkingTermination(catalog)::terminate);
			awaitLatch(test.terminationParked, "the termination never reached its publication decision");

			final ProgressingFuture<CatalogContract> renaming = catalog.replace(renamedSchema(catalog), null);
			renaming.execute(Runnable::run);
			final Throwable failure = failureOf(renaming);
			test.releaseParked.countDown();
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertInstanceOf(
				InstanceTerminatedException.class, rootCause(failure),
				"a rename starting after the termination began must be refused, but it ended with: " + failure
			);
			assertEquals(
				Optional.of(CatalogState.WARMING_UP), test.readSnapshotCatalogState(TEST_CATALOG),
				"the refused rename must leave the catalog under its own name"
			);
		}
	}

	/**
	 * Returns the schema the rename of the catalog to {@link #RENAMED_CATALOG} installs, built the way the rename
	 * operator builds it.
	 *
	 * @param catalog the catalog to rename
	 * @return the renamed catalog schema
	 */
	@Nonnull
	private static CatalogSchemaContract renamedSchema(@Nonnull Catalog catalog) {
		return new ModifyCatalogSchemaNameMutation(TEST_CATALOG, RENAMED_CATALOG, false)
			.mutate(catalog.getSchema())
			.updatedCatalogSchema();
	}

	/**
	 * Returns a task terminating the catalog and counting the latch down once the termination returned.
	 *
	 * @param catalog    the catalog to terminate
	 * @param terminated counted down once the termination returned
	 * @return the task
	 */
	@Nonnull
	private static Runnable terminating(@Nonnull Catalog catalog, @Nonnull CountDownLatch terminated) {
		return () -> {
			try {
				catalog.terminate();
			} finally {
				terminated.countDown();
			}
		};
	}

	/**
	 * Waits, within the positive bound, until the termination either queues on the catalog's warm-up publication lock
	 * or finishes without it. A thread blocked inside a lock acquisition signals nothing a latch could carry, so the
	 * lock itself is asked.
	 *
	 * @param catalog    the catalog being terminated
	 * @param terminated counted down once the termination returned
	 * @return true when the termination finished instead of queuing
	 */
	private static boolean queuedOnPublicationLockOrTerminated(
		@Nonnull Catalog catalog,
		@Nonnull CountDownLatch terminated
	) {
		final ReentrantLock publicationLock = publicationLock(catalog);
		final long deadline = System.nanoTime() + SECONDS.toNanos(POSITIVE_WAIT_SECONDS);
		while (!publicationLock.hasQueuedThreads() && terminated.getCount() > 0) {
			assertTrue(System.nanoTime() < deadline, "the termination never reached the publication lock");
			LockSupport.parkNanos(MILLISECONDS.toNanos(1L));
		}
		return terminated.getCount() == 0;
	}

	/**
	 * Returns the lock a handover holds while it writes its bootstrap record, which the termination takes to begin.
	 * Read reflectively, because whether a thread is blocked on it is the one thing the termination leaves no other
	 * trace of.
	 *
	 * @param catalog the catalog
	 * @return the catalog's warm-up publication lock
	 */
	@Nonnull
	private static ReentrantLock publicationLock(@Nonnull Catalog catalog) {
		try {
			final Field field = Catalog.class.getDeclaredField("warmUpPublicationLock");
			field.setAccessible(true);
			return (ReentrantLock) field.get(catalog);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("The warm-up publication lock of the catalog is not readable.", ex);
		}
	}

	/**
	 * Waits, within the positive bound, until the engine has cleared its state - the step of its close that comes
	 * after it terminated the catalogs it found and before it waits for the engine mutations still running. Nothing
	 * signals that step, so the engine is asked.
	 *
	 * @param evita the engine being closed
	 */
	private static void awaitEngineStateCleared(@Nonnull Evita evita) {
		final long deadline = System.nanoTime() + SECONDS.toNanos(POSITIVE_WAIT_SECONDS);
		while (evita.getEngineState() != null) {
			assertTrue(System.nanoTime() < deadline, "the engine never cleared its state");
			LockSupport.parkNanos(MILLISECONDS.toNanos(1L));
		}
	}

	/**
	 * Returns the completion of the progress a completed future returned.
	 *
	 * @param progressFuture the completed future returning the progress of an engine mutation
	 * @return the completion of that progress, as a future
	 */
	@Nonnull
	private static Future<?> futureOf(@Nonnull Future<? extends Progress<?>> progressFuture) {
		try {
			return progressFuture.get(POSITIVE_WAIT_SECONDS, SECONDS).onCompletion().toCompletableFuture();
		} catch (ExecutionException | TimeoutException ex) {
			return fail("the engine mutation was not accepted", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Returns the failure a completed future ended with.
	 *
	 * @param future the future, completed
	 * @return the failure, or `null` when the future completed normally
	 */
	@Nullable
	private static Throwable failureOf(@Nonnull Future<?> future) {
		try {
			future.get(POSITIVE_WAIT_SECONDS, SECONDS);
			return null;
		} catch (ExecutionException ex) {
			return ex.getCause();
		} catch (TimeoutException ex) {
			return fail("a future did not complete in " + POSITIVE_WAIT_SECONDS + " s", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Returns the innermost cause of a failure.
	 *
	 * @param failure the failure, or `null`
	 * @return the innermost cause, or `null` when there was no failure
	 */
	@Nullable
	private static Throwable rootCause(@Nullable Throwable failure) {
		Throwable cause = failure;
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * Waits for a latch the fixture owns, failing when it is not counted down in time.
	 *
	 * @param latch   the latch to await
	 * @param message the failure reported when the wait runs out
	 */
	private static void awaitLatch(@Nonnull CountDownLatch latch, @Nonnull String message) {
		try {
			assertTrue(latch.await(POSITIVE_WAIT_SECONDS, SECONDS), message);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Waits for a future to complete, whatever its outcome - the tests assert how it completed separately.
	 *
	 * @param task the future to await
	 */
	private static void awaitCompletion(@Nonnull Future<?> task) {
		try {
			task.get(POSITIVE_WAIT_SECONDS, SECONDS);
		} catch (ExecutionException ignored) {
			// the outcome is asserted by the caller where it matters
		} catch (TimeoutException ex) {
			fail("a future did not complete in " + POSITIVE_WAIT_SECONDS + " s", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Returns a spy of the catalog whose termination parks once it has begun, at the point where it asks whether it
	 * may publish, until {@link #releaseParked} is counted down. The spy shares every mutable holder of the catalog,
	 * so a handover of the catalog itself meets the termination of the spy.
	 *
	 * @param catalog the catalog
	 * @return the spy to terminate
	 */
	@Nonnull
	private Catalog parkingTermination(@Nonnull Catalog catalog) {
		final AtomicInteger decisions = new AtomicInteger();
		final Catalog terminating = spy(catalog);
		doAnswer(
			invocation -> {
				final Object publishable = invocation.callRealMethod();
				if (decisions.incrementAndGet() == 1) {
					this.terminationParked.countDown();
					awaitLatch(this.releaseParked, "the termination was never resumed");
				}
				return publishable;
			}
		).when(terminating).isPublishable();
		return terminating;
	}

	/**
	 * Returns the catalog instance the engine serves under the test catalog name.
	 *
	 * @return the catalog; never null
	 */
	@Nonnull
	private Catalog catalog() {
		return assertInstanceOf(Catalog.class, this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow());
	}

	/**
	 * Copies the whole storage directory of the running engine aside, so what reached the disk can be read by a
	 * fresh engine while the original one is still open.
	 *
	 * @throws IOException when the storage cannot be copied
	 */
	private void snapshotStorage() throws IOException {
		final Path source = this.paths.storage();
		final Path target = this.snapshotPaths.storage();
		final List<Path> entries;
		try (final Stream<Path> tree = Files.walk(source)) {
			entries = tree.toList();
		}
		for (final Path entry : entries) {
			final Path destination = target.resolve(source.relativize(entry).toString());
			if (Files.isDirectory(entry)) {
				Files.createDirectories(destination);
			} else {
				Files.createDirectories(destination.getParent());
				Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Opens a fresh engine over the storage snapshot and reads the state the catalog was published in.
	 *
	 * @param catalogName name the catalog is registered under in the snapshot
	 * @return the state of the catalog as it reached the disk, empty when the snapshot holds no such catalog
	 */
	@Nonnull
	private Optional<CatalogState> readSnapshotCatalogState(@Nonnull String catalogName) {
		try (final Evita snapshotEvita = new Evita(newTestEvitaConfigurationBuilder(this.snapshotPaths).build())) {
			snapshotEvita.waitUntilFullyInitialized();
			return snapshotEvita.getCatalogState(catalogName);
		}
	}

}
