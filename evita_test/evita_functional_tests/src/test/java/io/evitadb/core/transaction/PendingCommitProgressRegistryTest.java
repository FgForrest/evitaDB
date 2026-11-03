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

package io.evitadb.core.transaction;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.TransactionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Unit tests for {@link PendingCommitProgressRegistry}. The registry serves three purposes:
 *
 * 1. **Fan-out to greedy batches** — when a trunk-incorporation batch advances the live catalog
 *    past several transaction versions, the registry is used to complete
 *    {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE} for every record in the range immediately
 *    instead of waiting for the publisher to re-deliver each trunk task.
 * 2. **Watchdog for lost records** — a periodic sweep fails records the pipeline made no progress
 *    towards, or moved past, for longer than a bound, surfacing a descriptive exception to clients
 *    instead of a silent hang.
 * 3. **Shutdown safety net** — [PendingCommitProgressRegistry#failAllPending] fails every still
 *    registered record when the transaction manager is closed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PendingCommitProgressRegistry — in-flight index and watchdog for CommitProgressRecords")
@Tag(ENGINE)
@Tag(TRANSACTION)
class PendingCommitProgressRegistryTest {

	/**
	 * Executor that runs tasks synchronously on the calling thread — simplifies assertions about
	 * async completion order.
	 */
	private static final Executor SYNCHRONOUS = Runnable::run;

	/**
	 * Pre-completes the upstream stages of a record so the chain from `onWalAppended` is ready and
	 * any later `complete(WAIT_FOR_CHANGES_VISIBLE, ...)` call resolves synchronously.
	 */
	private static void preCompleteUpstreamStages(CommitProgressRecord record, CommitVersions versions) {
		record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
		record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
	}

	@Nested
	@DisplayName("Auto-deregistration on completion")
	class AutoDeregistrationTest {

		@Test
		@DisplayName("should remove record from the registry when onChangesVisible completes")
		void shouldRemoveRecordFromRegistryOnChangesVisibleCompletion() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(5L, 1);

			registry.register(5L, record, versions);
			assertEquals(1, registry.size(), "record must be tracked after registration");

			// complete all stages — auto-deregister on stage 3 completion
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, SYNCHRONOUS);

			assertEquals(
				0,
				registry.size(),
				"successful completion of onChangesVisible must auto-remove the record"
			);
		}

		@Test
		@DisplayName("should remove record from the registry when record fails exceptionally")
		void shouldRemoveRecordFromRegistryOnExceptionalCompletion() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(7L, 1);

			registry.register(7L, record, versions);
			assertEquals(1, registry.size());

			record.completeExceptionally(new RuntimeException("pipeline failure"));

			assertEquals(
				0,
				registry.size(),
				"exceptional completion must also auto-remove the record from the registry"
			);
		}
	}

	@Nested
	@DisplayName("Greedy-batch fan-out via completeChangesVisibleInRange")
	class FanOutTest {

		/**
		 * The hot-path scenario: trunk incorporation has just processed a greedy batch that advanced
		 * the live catalog from `v=100` to `v=105`. Records for `v=101..105` are still in the
		 * registry and semantically visible — the fan-out must complete their
		 * {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE} stage so clients are unblocked immediately.
		 */
		@Test
		@DisplayName("should complete WAIT_FOR_CHANGES_VISIBLE for every record in the range")
		void shouldCompleteChangesVisibleForRecordsInRange() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord r101 = new CommitProgressRecord();
			final CommitProgressRecord r105 = new CommitProgressRecord();
			final CommitVersions v101 = new CommitVersions(101L, 1);
			final CommitVersions v105 = new CommitVersions(105L, 1);

			registry.register(101L, r101, v101);
			registry.register(105L, r105, v105);
			preCompleteUpstreamStages(r101, v101);
			preCompleteUpstreamStages(r105, v105);

			// batch advanced from v=100 to v=105 — fan out to (100, 105]
			registry.completeChangesVisibleInRange(100L, 105L, SYNCHRONOUS);

			assertTrue(
				r101.isCompletedSuccessfully(),
				"record at the lower bound of the range must be completed successfully"
			);
			assertTrue(
				r105.isCompletedSuccessfully(),
				"record at the upper bound of the range (inclusive) must also be completed"
			);
			assertEquals(
				0, registry.size(),
				"all fanned-out records must auto-deregister after completion"
			);
		}

		@Test
		@DisplayName("should exclude records at the lower bound (exclusive) and above the upper bound")
		void shouldExcludeRecordsOutsideTheRange() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord lowBound = new CommitProgressRecord();
			final CommitProgressRecord inside = new CommitProgressRecord();
			final CommitProgressRecord aboveHigh = new CommitProgressRecord();
			final CommitVersions vLow = new CommitVersions(100L, 1);
			final CommitVersions vIn = new CommitVersions(103L, 1);
			final CommitVersions vHigh = new CommitVersions(110L, 1);

			registry.register(100L, lowBound, vLow);
			registry.register(103L, inside, vIn);
			registry.register(110L, aboveHigh, vHigh);
			preCompleteUpstreamStages(inside, vIn);

			registry.completeChangesVisibleInRange(100L, 105L, SYNCHRONOUS);

			assertFalse(
				lowBound.isDone(),
				"record at the exclusive lower bound must not be touched — the current trunk task's " +
					"record is completed separately and already auto-deregistered"
			);
			assertTrue(
				inside.isCompletedSuccessfully(),
				"record strictly inside the range must be completed"
			);
			assertFalse(
				aboveHigh.isDone(),
				"record above the upper bound must not be touched — those versions have not yet " +
					"been incorporated"
			);
			assertEquals(
				2, registry.size(),
				"only the inside record should have been removed; boundary records remain registered"
			);
		}

		@Test
		@DisplayName("should be a no-op when the range is empty")
		void shouldBeNoOpWhenRangeIsEmpty() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(5L, 1);

			registry.register(5L, record, versions);

			// identical bounds → empty range (exclusive lower == inclusive upper)
			registry.completeChangesVisibleInRange(5L, 5L, SYNCHRONOUS);
			// inverted bounds → empty range
			registry.completeChangesVisibleInRange(10L, 5L, SYNCHRONOUS);

			assertFalse(record.isDone(), "no record should be touched for an empty or inverted range");
			assertEquals(1, registry.size());
		}

		/**
		 * Idempotency matters because the publisher will still deliver trunk tasks for every version
		 * in the batch, each of which calls `complete(WAIT_FOR_CHANGES_VISIBLE, ...)` on the same
		 * record. {@link CommitProgressRecord} itself guards against double completion; the registry
		 * just has to not blow up on already-done records in the range.
		 */
		@Test
		@DisplayName("should be idempotent when the record is already completed")
		void shouldBeIdempotentWhenRecordAlreadyCompleted() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(50L, 1);

			registry.register(50L, record, versions);
			// upstream pipeline completed the record ahead of our fan-out
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, SYNCHRONOUS);
			// auto-deregister already removed the entry, but we still want the call to be a no-op

			registry.completeChangesVisibleInRange(0L, 100L, SYNCHRONOUS);

			assertTrue(
				record.isCompletedSuccessfully(),
				"already-completed record must not be overwritten by a follow-up fan-out"
			);
		}
	}

	@Nested
	@DisplayName("Watchdog for lost records via sweepStalledRecords")
	class StalledSweepTest {

		/**
		 * The bound every case sweeps with - the default `safetyDeadlineMs()` of the transaction manager.
		 */
		private static final Duration MAX_STALL = Duration.ofSeconds(100);
		/**
		 * The live view of every case that has not reached the swept records yet.
		 */
		private static final long LIVE_VERSION_BELOW = 4L;

		/**
		 * The time the registry reads, moved by each case at will.
		 */
		private long nowNanos;
		/**
		 * The registry reading {@link #nowNanos}.
		 */
		private final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry(() -> this.nowNanos);

		/**
		 * Moves the time the registry reads to the given number of seconds after its creation.
		 *
		 * @param seconds the seconds since the creation of the registry
		 */
		private void at(long seconds) {
			this.nowNanos = TimeUnit.SECONDS.toNanos(seconds);
		}

		/**
		 * The core watchdog scenario: the pipeline took nothing up for the whole bound, so every record waiting
		 * in it is lost and is failed with a descriptive exception instead of hanging its client.
		 */
		@Test
		@DisplayName("should fail every record the pipeline made no progress towards for the whole bound")
		void shouldFailRecordsThePipelineMadeNoProgressTowards() {
			final CommitProgressRecord first = new CommitProgressRecord();
			final CommitProgressRecord second = new CommitProgressRecord();
			at(0);
			this.registry.register(5L, first, new CommitVersions(5L, 1));
			at(10);
			this.registry.register(6L, second, new CommitVersions(6L, 1));

			at(110);
			final int failed = this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW);

			assertEquals(2, failed, "both records waiting in the stalled pipeline must be failed");
			for (CommitProgressRecord record : new CommitProgressRecord[]{first, second}) {
				final CompletableFuture<CommitVersions> changesVisible = record.onChangesVisible().toCompletableFuture();
				final ExecutionException ex = assertThrows(
					ExecutionException.class,
					() -> changesVisible.get(1, TimeUnit.SECONDS)
				);
				assertInstanceOf(
					TransactionException.class,
					ex.getCause(),
					"the sweep must raise a TransactionException so the failure is clearly identifiable"
				);
				assertTrue(
					ex.getCause().getMessage().contains("without the transaction pipeline making any progress"),
					"the failure must name the stall: " + ex.getCause().getMessage()
				);
			}
			assertEquals(0, this.registry.size(), "swept records must be purged from the registry");
		}

		@Test
		@DisplayName("should leave records registered for less than the bound untouched")
		void shouldLeaveYoungRecordsUntouched() {
			final CommitProgressRecord record = new CommitProgressRecord();
			at(0);
			this.registry.register(5L, record, new CommitVersions(5L, 1));

			this.nowNanos = MAX_STALL.toNanos() - 1;
			// even a live view that has reached the record does not make a young record lost
			final int failed = this.registry.sweepStalledRecords(MAX_STALL, 5L);

			assertEquals(0, failed, "nothing should be failed when all records are younger than the bound");
			assertFalse(record.isDone(), "a record younger than the bound must not be touched by the sweep");
			assertEquals(1, this.registry.size(), "young records must remain registered for the next sweep cycle");
		}

		/**
		 * A single large transaction can take longer to replay than the bound on a loaded host. Failing it would
		 * tell the client its commit was lost while the pipeline goes on to incorporate it - so every progress
		 * report restarts the bound, and only a pipeline that stops advancing fails the record.
		 */
		@Test
		@DisplayName("should spare a record for as long as the pipeline keeps making progress")
		void shouldSpareRecordsWhileThePipelineProgresses() {
			final CommitProgressRecord record = new CommitProgressRecord();
			at(0);
			this.registry.register(5L, record, new CommitVersions(5L, 1));

			for (long second = 50; second <= 500; second += 50) {
				at(second);
				this.registry.recordPipelineProgress();
				assertEquals(
					0, this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW),
					"a record must not be failed " + second + "s after its registration while the pipeline progresses"
				);
			}
			assertFalse(record.isDone(), "a record the pipeline keeps working towards must stay pending");

			// the pipeline stops advancing at 500s - the record is lost once the bound passes
			at(599);
			assertEquals(0, this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW));
			at(600);
			assertEquals(1, this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW));
			assertTrue(record.isCompletedExceptionally(), "a record the pipeline stopped advancing towards is lost");
		}

		/**
		 * A session creates its record when it opens, so the record's own start time includes everything the
		 * session did before it committed. The bound starts with the registration - the moment the pipeline
		 * takes the record over - or a long session would be failed as soon as its commit entered the pipeline.
		 */
		@Test
		@DisplayName("should not count the time the session spent before it committed")
		void shouldMeasureFromTheRegistrationRatherThanFromTheSessionStart() {
			final CommitProgressRecord recordOfLongSession = new CommitProgressRecord() {
				@Nonnull
				@Override
				public OffsetDateTime getCommitStartTime() {
					return OffsetDateTime.now().minusDays(1);
				}
			};
			at(1_000);
			this.registry.register(5L, recordOfLongSession, new CommitVersions(5L, 1));

			at(1_001);
			final int failed = this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW);

			assertEquals(0, failed, "a record registered a second ago must not be failed");
			assertFalse(recordOfLongSession.isDone(), "the age of the session must not make its commit look lost");
		}

		/**
		 * Progress of the pipeline spares only records it is still working towards. One the live view has
		 * already reached was skipped by its completion path, and no amount of further progress repairs that.
		 */
		@Test
		@DisplayName("should fail a record the live view moved past although the pipeline keeps making progress")
		void shouldFailRecordsTheLiveViewMovedPast() {
			final CommitProgressRecord skipped = new CommitProgressRecord();
			final CommitProgressRecord waiting = new CommitProgressRecord();
			at(0);
			this.registry.register(5L, skipped, new CommitVersions(5L, 1));
			this.registry.register(8L, waiting, new CommitVersions(8L, 1));

			at(100);
			this.registry.recordPipelineProgress();
			final int failed = this.registry.sweepStalledRecords(MAX_STALL, 6L);

			assertEquals(1, failed, "only the record the live view moved past must be failed");
			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> skipped.onChangesVisible().toCompletableFuture().get(1, TimeUnit.SECONDS)
			);
			assertTrue(
				ex.getCause().getMessage().contains("the live view has already moved past it"),
				"the failure must name the skipped completion: " + ex.getCause().getMessage()
			);
			assertFalse(waiting.isDone(), "a record above the live view is still being worked towards");
			assertEquals(1, this.registry.size(), "the record still being worked towards must stay registered");
		}

		/**
		 * If a record already completed between registration and sweep, the sweep should harvest the
		 * stale entry without calling `completeExceptionally` — the record is already done, the
		 * whenComplete auto-deregister callback may not have fired yet (observed between threads), or
		 * the sweep raced with a late removal.
		 */
		@Test
		@DisplayName("should remove already-completed entries without overwriting them")
		void shouldRemoveAlreadyCompletedEntriesWithoutOverwriting() {
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(5L, 1);
			at(0);
			this.registry.register(5L, record, versions);
			// complete the record successfully before the sweep — the auto-deregister should have
			// already removed it, but the test also verifies the sweep is robust to races
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, SYNCHRONOUS);

			at(1_000);
			final int failed = this.registry.sweepStalledRecords(MAX_STALL, LIVE_VERSION_BELOW);

			assertEquals(0, failed, "already-completed record must not be counted as failed");
			assertTrue(
				record.isCompletedSuccessfully(),
				"a record that completed successfully before the sweep must stay successful"
			);
		}
	}

	@Nested
	@DisplayName("Fail-all-pending (shutdown path)")
	class FailAllPendingTest {

		/**
		 * The shutdown scenario: the executor accepts a completion task that is later dropped by
		 * `shutdownNow`, or the transaction manager is closed while transactions are still in flight.
		 * In both cases the pipeline will never complete the affected records, so
		 * {@link PendingCommitProgressRegistry#failAllPending(String)} must fail them with a
		 * descriptive exception that surfaces the shutdown reason to waiters.
		 */
		@Test
		@DisplayName("should fail every pending record with a descriptive exception")
		void shouldFailEveryPendingRecordWithDescriptiveException() throws Exception {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord r1 = new CommitProgressRecord();
			final CommitProgressRecord r2 = new CommitProgressRecord();

			registry.register(3L, r1, new CommitVersions(3L, 1));
			registry.register(7L, r2, new CommitVersions(7L, 1));

			registry.failAllPending("the transaction manager is being closed");

			assertTrue(r1.isCompletedExceptionally());
			assertTrue(r2.isCompletedExceptionally());
			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> r1.onChangesVisible().toCompletableFuture().get(1, TimeUnit.SECONDS)
			);
			assertInstanceOf(TransactionException.class, ex.getCause());
			assertTrue(
				ex.getCause().getMessage().contains("the transaction manager is being closed"),
				"the exception must surface the supplied shutdown reason to the waiter"
			);
			assertEquals(0, registry.size(), "registry must be empty after failAllPending");
		}

		@Test
		@DisplayName("should not overwrite records already completed before failAllPending runs")
		void shouldNotOverwriteRecordsAlreadyCompletedBeforeFailAllPending() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();
			final CommitVersions versions = new CommitVersions(4L, 1);

			registry.register(4L, record, versions);
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, SYNCHRONOUS);

			registry.failAllPending("shutdown");

			assertTrue(
				record.isCompletedSuccessfully(),
				"a record that completed successfully before shutdown must stay successful"
			);
		}
	}
}
