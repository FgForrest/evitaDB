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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PendingCommitProgressRegistry}, the watchdog that fails dangling
 * {@link CommitProgressRecord}s when the catalog advances past their assigned version without
 * completing them through the normal transaction pipeline.
 *
 * The registry is the last line of defence against commit-progress hangs: even if a bug in the
 * transaction pipeline forgets to complete a record, this sweep will fail the record once the
 * catalog version advances past it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PendingCommitProgressRegistry — watchdog for dangling CommitProgressRecords")
class PendingCommitProgressRegistryTest {

	/**
	 * Executor that runs tasks synchronously on the calling thread — simplifies assertions about
	 * async completion order.
	 */
	private static final Executor SYNCHRONOUS = Runnable::run;

	@Nested
	@DisplayName("Auto-deregistration on successful completion")
	class AutoDeregistrationTest {

		@Test
		@DisplayName("should remove record from the registry when onChangesVisible completes")
		void shouldRemoveRecordFromRegistryOnChangesVisibleCompletion() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();

			registry.register(5L, record);
			assertEquals(1, registry.size(), "record must be tracked after registration");

			// complete all stages — auto-deregister on stage 3 completion
			final CommitVersions versions = new CommitVersions(5L, 1);
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

			registry.register(7L, record);
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
	@DisplayName("Sweep dangling records when catalog advances")
	class SweepTest {

		/**
		 * The core watchdog scenario: a record was registered with version 5 but the transaction
		 * pipeline dropped it on the floor (e.g. a missed completion branch, or an executor dropping
		 * a scheduled async completion). The catalog later advances to version 10 — the sweep must
		 * fail the dangling record so any client awaiting it is unblocked with a descriptive error
		 * rather than hanging forever.
		 */
		@Test
		@DisplayName("should fail dangling record when catalog advances past its version")
		void shouldFailDanglingRecordWhenCatalogAdvancesPastItsVersion()
			throws InterruptedException, TimeoutException {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();

			registry.register(5L, record);
			// catalog advances to version 10 without our record ever being completed
			registry.sweepUpTo(10L);

			assertTrue(
				record.isCompletedExceptionally(),
				"dangling record must be failed when the catalog advances past its version"
			);
			final CompletableFuture<CommitVersions> changesVisible =
				record.onChangesVisible().toCompletableFuture();
			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> changesVisible.get(1, TimeUnit.SECONDS)
			);
			assertInstanceOf(
				TransactionException.class,
				ex.getCause(),
				"the sweep must raise a TransactionException so the failure is clearly identifiable"
			);
			assertEquals(
				0,
				registry.size(),
				"swept records must be purged from the registry"
			);
		}

		@Test
		@DisplayName("should only sweep records at or below the advance threshold")
		void shouldOnlySweepRecordsAtOrBelowTheAdvanceThreshold() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord lowRecord = new CommitProgressRecord();
			final CommitProgressRecord equalRecord = new CommitProgressRecord();
			final CommitProgressRecord highRecord = new CommitProgressRecord();

			registry.register(3L, lowRecord);
			registry.register(5L, equalRecord);
			registry.register(7L, highRecord);

			registry.sweepUpTo(5L);

			assertTrue(
				lowRecord.isCompletedExceptionally(),
				"records below the threshold must be swept"
			);
			assertTrue(
				equalRecord.isCompletedExceptionally(),
				"records equal to the threshold must also be swept (the version is considered finalized)"
			);
			assertFalse(
				highRecord.isDone(),
				"records above the threshold must not be touched"
			);
			assertEquals(
				1,
				registry.size(),
				"only the above-threshold record should remain in the registry"
			);
		}

		@Test
		@DisplayName("should not overwrite already-completed records during sweep")
		void shouldNotOverwriteAlreadyCompletedRecordsDuringSweep() {
			final PendingCommitProgressRegistry registry = new PendingCommitProgressRegistry();
			final CommitProgressRecord record = new CommitProgressRecord();

			registry.register(5L, record);
			// complete the record successfully before the sweep happens (simulating a race where the
			// record completes through the normal pipeline just before the sweep)
			final CommitVersions versions = new CommitVersions(5L, 1);
			record.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, SYNCHRONOUS);
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, SYNCHRONOUS);

			registry.sweepUpTo(10L);

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

			registry.register(3L, r1);
			registry.register(7L, r2);

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

			registry.register(4L, record);
			final CommitVersions versions = new CommitVersions(4L, 1);
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
