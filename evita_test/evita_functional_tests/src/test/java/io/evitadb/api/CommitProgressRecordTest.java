/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Unit tests for {@link CommitProgressRecord} focused on completion semantics and
 * resilience against callback / chaining misbehaviour.
 *
 * Tests cover:
 * - Exception propagation across chained stages
 * - Defensive handling of a throwing `terminationSequence` callback
 * - Idempotency of completion methods
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("CommitProgressRecord functionality")
@Tag(CONTRACT)
@Tag(QUERY)
class CommitProgressRecordTest implements EvitaTestSupport {

	/**
	 * Helper executor that queues tasks without running them, giving tests deterministic
	 * control over when (or whether) async completions fire.
	 */
	private static final class DeferredExecutor implements Executor {
		private final Deque<Runnable> pending = new ArrayDeque<>();

		@Override
		public void execute(@Nonnull Runnable command) {
			this.pending.addLast(command);
		}

		void runAll() {
			while (!this.pending.isEmpty()) {
				this.pending.removeFirst().run();
			}
		}
	}

	@Nested
	@DisplayName("Stage chaining")
	class StageChainingTest {

		/**
		 * Pins the exception-propagation contract of the stage chain: when stage 1 is still pending
		 * at the time stage 2 is asked to complete, the chain must forward any failure of stage 1 to
		 * stage 2. A naïve `previousStage.whenComplete((result, throwable) -> thisStage.complete(result))`
		 * drops the exception and completes the chained stage with `null`, silently swallowing the
		 * failure — this test guards against that regression.
		 */
		@Test
		@DisplayName("Should propagate exception to chained WAL stage when conflict resolution fails")
		void shouldPropagateExceptionToChainedWalStageWhenConflictResolutionFails()
			throws InterruptedException, TimeoutException {
			final DeferredExecutor executor = new DeferredExecutor();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(1L, 1);

			// stage 1: scheduled async but supplier has not run yet
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
			// stage 2: chains to stage 1 which is still pending
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, executor);

			// now something fails the whole record
			final RuntimeException failure = new RuntimeException("simulated failure");
			record.completeExceptionally(failure);

			// stage 2 must propagate the exception, not complete successfully with null
			final CompletableFuture<CommitVersions> stage2 = record.onWalAppended().toCompletableFuture();
			assertTrue(stage2.isDone(), "stage 2 should be done");
			assertTrue(
				stage2.isCompletedExceptionally(),
				"stage 2 must propagate the exception from stage 1 rather than complete with null"
			);
			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> stage2.get(1, TimeUnit.SECONDS)
			);
			assertInstanceOf(RuntimeException.class, ex.getCause());
		}

		/**
		 * Symmetrical test for the stage 2 → stage 3 chain. Reproduces the same bug at the
		 * second chaining link.
		 */
		@Test
		@DisplayName("Should propagate exception to chained visibility stage when WAL stage fails")
		void shouldPropagateExceptionToChainedVisibilityStageWhenWalStageFails()
			throws InterruptedException, TimeoutException {
			final DeferredExecutor executor = new DeferredExecutor();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(1L, 1);

			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, executor);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, executor);

			final RuntimeException failure = new RuntimeException("simulated visibility failure");
			record.completeExceptionally(failure);

			final CompletableFuture<CommitVersions> stage3 = record.onChangesVisible().toCompletableFuture();
			assertTrue(stage3.isDone(), "stage 3 should be done");
			assertTrue(
				stage3.isCompletedExceptionally(),
				"stage 3 must propagate the exception rather than complete with null"
			);
			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> stage3.get(1, TimeUnit.SECONDS)
			);
			assertInstanceOf(RuntimeException.class, ex.getCause());
		}

		/**
		 * Happy path sanity check: when stage 1 eventually completes successfully, chained
		 * stages should complete with the same versions.
		 */
		@Test
		@DisplayName("Should propagate versions to chained stages on happy path")
		void shouldPropagateVersionsToChainedStagesOnHappyPath()
			throws ExecutionException, InterruptedException, TimeoutException {
			final DeferredExecutor executor = new DeferredExecutor();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(42L, 7);

			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, executor);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, executor);

			// now let the async supplier of stage 1 run, which cascades to stages 2 and 3
			executor.runAll();

			assertEquals(
				versions,
				record.onConflictResolved().toCompletableFuture().get(1, TimeUnit.SECONDS)
			);
			assertEquals(
				versions,
				record.onWalAppended().toCompletableFuture().get(1, TimeUnit.SECONDS)
			);
			assertEquals(
				versions,
				record.onChangesVisible().toCompletableFuture().get(1, TimeUnit.SECONDS)
			);
			assertTrue(record.isCompletedSuccessfully());
		}
	}

	@Nested
	@DisplayName("Termination sequence callback")
	class TerminationSequenceTest {

		/**
		 * The doc states the termination callback "must not throw", but it's user-supplied code and
		 * we cannot rely on that. If the callback does throw, the record must still complete every
		 * stage — otherwise the stage (and every stage chained off it) would stay pending forever
		 * and every client waiting on it would hang.
		 */
		@Test
		@DisplayName("Should complete stage even when terminationSequence throws on WAIT_FOR_CHANGES_VISIBLE")
		void shouldCompleteStageEvenWhenTerminationSequenceThrowsOnChangesVisible() {
			final AtomicInteger callbackInvocations = new AtomicInteger();
			final CommitProgressRecord record = new CommitProgressRecord(
				(versions, throwable) -> {
					callbackInvocations.incrementAndGet();
					throw new RuntimeException("callback blows up");
				}
			);
			record.setTerminationStage(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE);

			// must not throw — defensive wrapping must isolate the callback
			record.complete(new CommitVersions(1L, 1));

			assertEquals(
				1,
				callbackInvocations.get(),
				"termination callback must be invoked exactly once at the configured stage"
			);
			assertTrue(
				record.onConflictResolved().toCompletableFuture().isDone(),
				"stage 1 must complete even if termination callback throws"
			);
			assertTrue(
				record.onWalAppended().toCompletableFuture().isDone(),
				"stage 2 must complete even if termination callback throws"
			);
			assertTrue(
				record.onChangesVisible().toCompletableFuture().isDone(),
				"stage 3 must complete even if termination callback throws"
			);
			assertTrue(
				record.isCompletedSuccessfully(),
				"record must complete successfully despite a throwing callback"
			);
		}

		/**
		 * Symmetric test for the async `complete(CommitBehavior, CommitVersions, Executor)`
		 * variant on stage 1 with a throwing termination callback.
		 */
		@Test
		@DisplayName("Should complete stage even when terminationSequence throws on async WAIT_FOR_CONFLICT_RESOLUTION")
		void shouldCompleteStageEvenWhenTerminationSequenceThrowsOnAsyncConflictResolution() {
			final DeferredExecutor executor = new DeferredExecutor();
			final CommitProgressRecord record = new CommitProgressRecord(
				(versions, throwable) -> {
					throw new RuntimeException("callback blows up");
				}
			);
			record.setTerminationStage(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION);

			final CommitVersions versions = new CommitVersions(1L, 1);
			// must not throw — defensive wrapping must isolate the callback
			try {
				record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
			} catch (Throwable t) {
				fail("complete(...) must swallow callback exceptions; got " + t);
			}
			executor.runAll();

			assertTrue(
				record.onConflictResolved().toCompletableFuture().isDone(),
				"stage 1 async completion must still fire despite a throwing callback"
			);
		}

		/**
		 * Ensures that `completeExceptionally` similarly survives a throwing callback and
		 * still propagates the exception to every pending stage.
		 */
		@Test
		@DisplayName("Should propagate exception to all stages even when terminationSequence throws")
		void shouldPropagateExceptionToAllStagesEvenWhenTerminationSequenceThrows() {
			final CommitProgressRecord record = new CommitProgressRecord(
				(versions, throwable) -> {
					throw new RuntimeException("callback blows up");
				}
			);
			record.setTerminationStage(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE);

			record.completeExceptionally(new IllegalStateException("root cause"));

			assertTrue(record.onConflictResolved().toCompletableFuture().isCompletedExceptionally());
			assertTrue(record.onWalAppended().toCompletableFuture().isCompletedExceptionally());
			assertTrue(record.onChangesVisible().toCompletableFuture().isCompletedExceptionally());
			assertFalse(record.isCompletedSuccessfully());
			assertTrue(record.isCompletedExceptionally());
		}
	}
}
