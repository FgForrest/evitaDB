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

package io.evitadb.api.functional.schema;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.functional.schema.CatalogUnpublishableBarrierAssertions.subscribeToSettledState;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises barrier deactivation while the refusing engine mutation retains its catalog conflict key.
 */
@DisplayName("Unpublishable catalog deactivation retries")
@Tag(ENGINE)
@Tag(SCHEMA)
class CatalogUnpublishableDeactivationRetryTest implements EvitaTestSupport {
	private static final String CATALOG = "deactivationRetry";
	private TestPaths paths;
	private Evita evita;
	private final CountDownLatch closingSession = new CountDownLatch(1);

	/** Creates an engine with a single asynchronous service worker so a log gate also gates its retry task. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogUnpublishableDeactivationRetryTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.serviceThreadPool(ThreadPoolOptions.serviceThreadPoolBuilder()
						.minThreadCount(1).maxThreadCount(1).build())
					.build())
				.build(),
			true, null,
			session -> {
				if (!this.evita.isActive()) {
					this.closingSession.countDown();
				}
			},
			false
		);
		this.evita.defineCatalog(CATALOG);
	}

	/** Releases the engine and its per-test storage. */
	@AfterEach
	void tearDown() {
		try {
			if (this.evita != null) {
				this.evita.close();
			}
		} finally {
			if (this.paths != null) {
				cleanupTestPaths(this.paths);
			}
		}
	}

	/** Holds the refusing mutation beyond six observed admission conflicts, then lets deactivation settle. */
	@Test
	@DisplayName("should deactivate when the refusing mutation outlasts six admission attempts")
	void shouldDeactivateWhenRefusingMutationOutlastsSixAdmissionAttempts() throws Exception {
		try (
			final RetryCapture capture = new RetryCapture(false);
			final CatalogUnpublishableBarrierAssertions.SettledStateLatch inactive =
				subscribeToSettledState(this.evita, CATALOG, CatalogState.INACTIVE);
			final HeldMutation holder = refuseSchemaChange()
		) {
			holder.awaitHeld();
			capture.awaitConflicts();
			holder.releaseAndAssertRefused();
			inactive.await();
			assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(CATALOG).orElseThrow());
			assertTrue(capture.errors.isEmpty(), () -> "Unexpected deactivation errors: " + capture.errors);
		}
	}

	/** Drops the catalog during the conflict handler's log call and checks that the handler stops deactivation. */
	@Test
	@DisplayName("should stop when the originating catalog is dropped while waiting")
	void shouldStopWhenOriginatingCatalogIsDroppedWhileWaiting() throws Exception {
		final Catalog origin = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		try (final RetryCapture capture = new RetryCapture(true); final HeldMutation holder = refuseSchemaChange()) {
			holder.awaitHeld();
			capture.awaitConflicts();
			holder.releaseAndAssertRefused();
			this.evita.deleteCatalogIfExists(CATALOG);
			assertTrue(origin.isTerminated());
			capture.release.countDown();
			capture.assertStopped(this.evita, "handleFailedDeactivation");
		}
	}

	/** Drops the catalog after the conflict handler schedules its retry but before that retry can execute. */
	@Test
	@DisplayName("should stop a queued retry when the originating catalog is dropped")
	void shouldStopQueuedRetryWhenOriginatingCatalogIsDropped() throws Exception {
		final Catalog origin = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		try (final RetryCapture capture = new RetryCapture(true); final HeldMutation holder = refuseSchemaChange()) {
			holder.awaitHeld();
			capture.awaitConflicts();
			final CountDownLatch workerHeld = new CountDownLatch(1);
			final CountDownLatch releaseWorker = new CountDownLatch(1);
			final CompletableFuture<Void> worker = CompletableFuture.runAsync(
				() -> {
					workerHeld.countDown();
					await(releaseWorker);
				},
				this.evita.getServiceExecutor()
			);
			try (final AutoCloseable workerGate = () -> {
				releaseWorker.countDown();
				worker.get(30, TimeUnit.SECONDS);
			}) {
				capture.release.countDown();
				// This worker runs after the conflict handler returns, ahead of its delayed retry.
				await(workerHeld);
				holder.releaseAndAssertRefused();
				this.evita.deleteCatalogIfExists(CATALOG);
				assertTrue(origin.isTerminated());
			}
			capture.assertStopped(this.evita, "attemptDeactivation");
		}
	}

	/** Checks that closing the engine during the deactivation wait logs no deactivation ERROR. */
	@Test
	@DisplayName("should log no deactivation error when the engine closes while waiting")
	void shouldLogNoDeactivationErrorWhenEngineClosesWhileWaiting() throws Exception {
		try (final RetryCapture capture = new RetryCapture(true); final HeldMutation holder = refuseSchemaChange()) {
			holder.awaitHeld();
			capture.awaitConflicts();
			final CompletableFuture<Void> closed = CompletableFuture.runAsync(this.evita::close);
			try (final AutoCloseable shutdown = () -> {
				holder.release.countDown();
				capture.release.countDown();
				// the asynchronous close must finish before teardown deletes the fixture directories it still uses
				closed.get(30, TimeUnit.SECONDS);
			}) {
				await(this.closingSession);
				assertFalse(this.evita.isActive());
			}
			holder.releaseAndAssertRefused();
			assertTrue(capture.errors.isEmpty(), () -> "Unexpected deactivation errors: " + capture.errors);
		}
	}

	/**
	 * Submits a histogram whose referenced attribute is absent, raising the barrier inside the engine mutation.
	 *
	 * @return holder that gates only the engine-thread 100 percent notification
	 */
	@Nonnull
	private HeldMutation refuseSchemaChange() {
		final EvitaSessionContract session = this.evita.createReadWriteSession(CATALOG);
		session.defineEntitySchema("target");
		final ModifyEntitySchemaMutation refused = session.defineEntitySchema("reader")
			.withReferenceToEntity("target", "target", Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFiltering().bucketed("histogram",
					ExpressionFactory.parse("$reference.referencedEntity?.attributes['missing']")))
			.toMutation().orElseThrow();
		final HeldMutation holder = new HeldMutation();
		holder.progress = this.evita.applyMutation(
			new ModifyCatalogSchemaMutation(CATALOG, session.getId(), refused),
			percent -> {
				// The zero percent notification runs on the submitter under the engine lock.
				if (percent == 100) {
					holder.held.countDown();
					try {
						await(holder.release);
					} catch (Throwable ex) {
						// ProgressRecord swallows observer exceptions; assert them on the test thread.
						holder.observerFailure.compareAndSet(null, ex);
					}
				}
			}
		);
		return holder;
	}

	/**
	 * Awaits a positive synchronization signal with a hang bound.
	 *
	 * @param latch signal to await
	 */
	private static void await(@Nonnull CountDownLatch latch) {
		try {
			assertTrue(latch.await(30, TimeUnit.SECONDS), "Synchronization signal did not arrive");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			fail("Interrupted while awaiting synchronization", ex);
		}
	}

	/** Retains the mutation's conflict key until the test releases its progress observer. */
	private static final class HeldMutation implements AutoCloseable {
		private final CountDownLatch held = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicReference<Throwable> observerFailure = new AtomicReference<>();
		private Progress<CommitVersions> progress;

		/** Waits until the barrier has been raised and the observer holds finalization. */
		private void awaitHeld() {
			await(this.held);
		}

		/** Releases the observer, waits for finalization, and checks both the refusal and observer outcome. */
		private void releaseAndAssertRefused() {
			this.release.countDown();
			final ExecutionException refusal = assertThrows(ExecutionException.class,
				() -> this.progress.onCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS));
			assertInstanceOf(InvalidSchemaMutationException.class, refusal.getCause());
			assertTrue(refusal.getCause().getMessage().contains("does not exist on entity type 'target'"));
			assertNull(this.observerFailure.get(), () -> "Observer failed: " + this.observerFailure.get());
		}

		@Override
		public void close() {
			// Unconditional even when a test assertion fails, so engine shutdown cannot wait on the test.
			releaseAndAssertRefused();
		}
	}

	/** Captures this catalog's deactivation diagnostics and can block the worker while it logs the sixth conflict. */
	private static final class RetryCapture extends UnsynchronizedAppenderBase<ILoggingEvent> implements AutoCloseable {
		private final Logger logger = (Logger) LoggerFactory.getLogger(Catalog.class);
		private final Level originalLevel = this.logger.getLevel();
		private final CountDownLatch observed = new CountDownLatch(1);
		private final CountDownLatch stopped = new CountDownLatch(1);
		private final List<String> afterStop = new CopyOnWriteArrayList<>();
		private final AtomicReference<StackTraceElement[]> stopCallerData = new AtomicReference<>();
		private final CountDownLatch release;
		private final AtomicInteger conflicts = new AtomicInteger();
		private final List<String> errors = new CopyOnWriteArrayList<>();
		private final AtomicReference<Throwable> appenderFailure = new AtomicReference<>();

		/**
		 * Attaches the capture and enables deactivation diagnostics until it is closed.
		 *
		 * @param gate whether to block the worker during the sixth conflict record until the test releases it
		 */
		private RetryCapture(boolean gate) {
			this.release = new CountDownLatch(gate ? 1 : 0);
			start();
			this.logger.addAppender(this);
			this.logger.setLevel(Level.DEBUG);
		}

		@Override
		protected void append(@Nonnull ILoggingEvent event) {
			// Materialize Logback's lazy message on the emitting thread before handing signals to the test.
			final String message = event.getFormattedMessage();
			if (!message.contains("`" + CATALOG + "`")) {
				return;
			}
			final boolean conflict = event.getLevel() == Level.DEBUG
				&& message.startsWith("Deactivation of catalog `" + CATALOG + "` met an admission conflict");
			if (this.stopped.getCount() == 0 && (conflict || event.getLevel() == Level.ERROR)) {
				this.afterStop.add(message);
			}
			if (event.getLevel() == Level.DEBUG && message.equals(
				"Stopping deactivation of catalog `" + CATALOG + "`: " +
					"the catalog instance that raised the barrier has ended."
			)) {
				// Resolve the first stop record's lazy caller data on the emitting thread.
				this.stopCallerData.compareAndSet(null, event.getCallerData());
				this.stopped.countDown();
			} else if (event.getLevel() == Level.ERROR && message.startsWith("Failed to deactivate catalog")) {
				// Raising the barrier deliberately logs another ERROR, which is the trigger under test.
				this.errors.add(message);
				this.observed.countDown();
			} else if (conflict) {
				if (this.conflicts.incrementAndGet() == 6) {
					this.observed.countDown();
					try {
						await(this.release);
					} catch (Throwable ex) {
						this.appenderFailure.compareAndSet(null, ex);
					}
				}
			}
		}

		/** Waits for six admission conflicts or a deactivation error, then checks the conflict count. */
		private void awaitConflicts() {
			await(this.observed);
			assertTrue(this.conflicts.get() >= 6,
				() -> "Expected at least 6 admission conflicts, observed " + this.conflicts +
					"; errors: " + this.errors);
		}

		/**
		 * Waits for the stop record and for its task to return. Checks the caller, catalog absence, and diagnostics
		 * captured through the worker fence.
		 *
		 * @param evita engine whose single service worker runs the deactivation attempts
		 * @param expectedStopMethod name of the `Catalog` method whose termination check must log the stop record
		 */
		private void assertStopped(@Nonnull Evita evita, @Nonnull String expectedStopMethod) {
			await(this.stopped);
			// The single worker runs this fence after the task that emitted the stop record returns.
			final CountDownLatch stopTaskReturned = new CountDownLatch(1);
			evita.getServiceExecutor().execute(stopTaskReturned::countDown);
			await(stopTaskReturned);
			final StackTraceElement[] callerData = this.stopCallerData.get();
			assertTrue(callerData.length > 0, "Stop record has no caller data");
			assertEquals(Catalog.class.getName(), callerData[0].getClassName());
			assertEquals(expectedStopMethod, callerData[0].getMethodName());
			assertTrue(evita.getCatalogState(CATALOG).isEmpty());
			assertFalse(evita.getCatalogNames().contains(CATALOG));
			assertTrue(this.afterStop.isEmpty(), () -> "Unexpected records after stopping: " + this.afterStop);
			assertTrue(this.errors.isEmpty(), () -> "Unexpected deactivation errors: " + this.errors);
		}

		@Override
		public void close() {
			try {
				this.release.countDown();
				this.logger.detachAppender(this);
				this.logger.setLevel(this.originalLevel);
				stop();
			} finally {
				assertNull(this.appenderFailure.get(), () -> "Appender failed: " + this.appenderFailure.get());
			}
		}
	}
}
