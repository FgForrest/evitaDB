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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.ConflictResolutionAndWalAppendingTransactionStage.ConflictResolutionAndWalAppendingTransactionTask;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.TestTags.WAL;

/**
 * Unit tests for {@link ConflictResolutionAndWalAppendingTransactionStage} focused on the catalog-version
 * bookkeeping that the stage performs on top of the shared {@link TransactionManager} counters.
 *
 * The stage assigns catalog versions, appends to the WAL, and — on failure — is required to notify
 * the transaction manager about how many versions should be "rolled back". Historically this rollback
 * amount was carried via instance fields on the stage, which caused the value to leak across invocations.
 * These tests pin the correct behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog version bookkeeping in the conflict-resolution / WAL appending stage")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(WAL)
class ConflictResolutionAndWalAppendingTransactionStageTest {

	private static final String CATALOG_NAME = "testCatalog";

	/**
	 * When a transaction fails AFTER its catalog version was assigned (e.g. WAL append throws), the stage
	 * must drop exactly that one version. A follow-up transaction that fails BEFORE the assignment step
	 * (e.g. inside `identifyConflicts`) must NOT trigger any further rollback — the failed transaction
	 * never incremented the counter, so decrementing it now would silently desynchronize assigned vs.
	 * written version tracking.
	 *
	 * This is the root cause of the "Transaction cannot be written to the WAL out of order" assertion
	 * firing out of the blue: a prior WAL-append failure leaves stale rollback state on the stage, and
	 * every subsequent `identifyConflicts` failure quietly rewinds the assignment counter by an extra
	 * version, eventually pushing it below the WAL's last written version.
	 */
	@Test
	@DisplayName("should not re-apply rollback when a later task fails before assigning a version")
	void shouldNotAccumulateDroppedVersionsAcrossFailedTasks() {
		final AtomicLong lastAssigned = new AtomicLong(312L);
		final AtomicLong lastWritten = new AtomicLong(312L);

		final TransactionManager tm = buildTransactionManagerMock(lastAssigned, lastWritten);

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);

		// T1 — identifyConflicts succeeds, WAL append throws a generic RuntimeException
		doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
		doThrow(new RuntimeException("simulated WAL fsync failure"))
			.when(tm).appendWalAndDiscard(any(), any(), any());

		feed(stage, newTask());

		// T1's rollback is correct: one version was assigned (313) and then released
		assertEquals(312L, lastAssigned.get(),
			"After T1 failed in WAL append the assignment counter must return to its pre-T1 value");

		// T2..T7 — identifyConflicts throws, so the assignment step is never reached.
		doThrow(new RuntimeException("simulated conflict"))
			.when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());

		for (int i = 2; i <= 7; i++) {
			feed(stage, newTask());
		}

		// Each of T2..T7 failed BEFORE the assignment step, so none of them should decrement the counter.
		assertEquals(312L, lastAssigned.get(),
			"Tasks that fail before assigning a catalog version must not decrement the assignment counter");
	}

	/**
	 * Verifies the complementary happy-path invariant: after a successful transaction the stage's
	 * rollback state is cleared, so a later task that fails before assigning a version does NOT cause
	 * any spurious decrement.
	 */
	@Test
	@DisplayName("should not rollback after a later task fails when the previous task committed cleanly")
	void shouldNotRollbackAfterSuccessfulCommitFollowedByEarlyFailure() {
		final AtomicLong lastAssigned = new AtomicLong(312L);
		final AtomicLong lastWritten = new AtomicLong(312L);

		final TransactionManager tm = buildTransactionManagerMock(lastAssigned, lastWritten);

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);

		// T1 succeeds end-to-end.
		doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
		doAnswer(inv -> {
			lastWritten.set(313L);
			return 0L;
		}).when(tm).appendWalAndDiscard(any(), any(), any());

		feed(stage, newTask());

		assertEquals(313L, lastAssigned.get(), "T1 must leave one version assigned");
		assertEquals(313L, lastWritten.get(), "T1 must leave one version written");

		// T2 fails inside identifyConflicts — no assignment should happen, no rollback either.
		doThrow(new RuntimeException("simulated conflict"))
			.when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());

		feed(stage, newTask());

		assertEquals(313L, lastAssigned.get(),
			"An early failure must not rewind the assignment counter below the last written version");
	}

	/**
	 * Creates a {@link TransactionManager} mock whose counters are backed by the provided atomics so
	 * that the test can observe the effect of `getNextCatalogVersionToAssign()` and
	 * `notifyCatalogVersionDropped(...)` calls.
	 */
	private static TransactionManager buildTransactionManagerMock(
		AtomicLong lastAssigned,
		AtomicLong lastWritten
	) {
		final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(synchronousExecutor).execute(any(Runnable.class));

		// the stage's diagnostic logging on the failure path reads first/last WAL versions through
		// the living catalog; getLivingCatalog() is @Nonnull so we must return a real (mocked)
		// Catalog, and the two mutation-stream accessors must return sane numeric values
		final Catalog livingCatalog = mock(Catalog.class);
		when(livingCatalog.getFirstCatalogVersionInMutationStream()).thenAnswer(inv -> -1L);
		when(livingCatalog.getLastCatalogVersionInMutationStream()).thenAnswer(inv -> lastWritten.get());

		final TransactionManager tm = mock(TransactionManager.class);
		when(tm.getCatalogName()).thenReturn(CATALOG_NAME);
		when(tm.getLastAssignedCatalogVersion()).thenAnswer(inv -> lastAssigned.get());
		when(tm.getNextCatalogVersionToAssign()).thenAnswer(inv -> lastAssigned.incrementAndGet());
		when(tm.getLastWrittenCatalogVersion()).thenAnswer(inv -> lastWritten.get());
		when(tm.getLastFinalizedCatalogVersion()).thenAnswer(inv -> lastWritten.get());
		when(tm.getLivingCatalog()).thenReturn(livingCatalog);
		when(tm.addDeltaAndEstimateCatalogSchemaVersion(anyInt())).thenReturn(0);
		when(tm.getRequestExecutor()).thenReturn(synchronousExecutor);
		// registerPendingCommitProgress is a void method on a mock — Mockito's default behaviour is
		// a no-op, which is exactly what this test wants (the registry's own dedicated test exercises
		// the registration and watchdog paths)
		doAnswer(inv -> {
			final int dropped = inv.getArgument(0);
			if (dropped > 0) {
				lastAssigned.addAndGet(-dropped);
			}
			return null;
		}).when(tm).notifyCatalogVersionDropped(anyInt(), anyInt());
		return tm;
	}

	/**
	 * Drives a task through the stage the same way {@link AbstractTransactionStage#onNext} would —
	 * calling `handleException` when `handleNext` throws — but without the `SubmissionPublisher`
	 * machinery.
	 */
	private static void feed(
		ConflictResolutionAndWalAppendingTransactionStage stage,
		ConflictResolutionAndWalAppendingTransactionTask task
	) {
		try {
			stage.handleNext(task);
		} catch (Throwable ex) {
			stage.handleException(task, ex);
		}
	}

	/**
	 * The point of the deferred WAL sync: transactions appended while a force is in flight must be carried
	 * by **one** subsequent force rather than paying for one each.
	 *
	 * The test pins both halves of that contract. It holds the first force open, feeds two more
	 * transactions through the stage while it is blocked — which only returns if the appender genuinely
	 * does not wait for the device — and then asserts that three transactions cost two forces, with the
	 * second one covering both stragglers at once.
	 *
	 * It also pins the durability ordering: while the first force is in flight, no transaction may be
	 * reported as WAL-persisted, because nothing has reached the device yet.
	 */
	@Test
	@DisplayName("should carry every transaction appended during a force with one single subsequent force")
	void shouldCoverTransactionsAppendedDuringAForceWithASingleForce() throws Exception {
		final AtomicLong lastAssigned = new AtomicLong(0L);
		final AtomicLong lastWritten = new AtomicLong(0L);
		final TransactionManager tm = buildTransactionManagerMock(lastAssigned, lastWritten);

		// the sync task needs a thread of its own - running it inline would make the appender the forcing
		// thread again and there would be nothing to overlap
		final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
		try {
			final CountDownLatch firstForceEntered = new CountDownLatch(1);
			final CountDownLatch releaseFirstForce = new CountDownLatch(1);
			final AtomicInteger forceCount = new AtomicInteger();
			final List<Long> publishedDurableVersions = Collections.synchronizedList(new ArrayList<>());

			doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
			doAnswer(inv -> 0L).when(tm).appendWalAndDiscard(any(), any(), any());
			doAnswer(inv -> {
				publishedDurableVersions.add(inv.getArgument(0));
				return null;
			}).when(tm).updateLastDurableCatalogVersion(anyLong());
			doAnswer(inv -> {
				if (forceCount.incrementAndGet() == 1) {
					firstForceEntered.countDown();
					assertTrue(
						releaseFirstForce.await(10, TimeUnit.SECONDS),
						"The test never released the first force!"
					);
				}
				return null;
			}).when(tm).syncWal();

			final ConflictResolutionAndWalAppendingTransactionStage stage =
				new ConflictResolutionAndWalAppendingTransactionStage(
					syncExecutor,
					100,
					tm,
					(task, ex) -> {}
				);

			final ConflictResolutionAndWalAppendingTransactionTask t1 = newTask();
			feed(stage, t1);
			assertTrue(
				firstForceEntered.await(10, TimeUnit.SECONDS),
				"The first transaction must trigger a force straight away - batching may never add latency " +
					"to an otherwise idle commit path"
			);

			// these two only get through while the device is busy if the appender really does not wait for it
			final ConflictResolutionAndWalAppendingTransactionTask t2 = newTask();
			feed(stage, t2);
			final ConflictResolutionAndWalAppendingTransactionTask t3 = newTask();
			feed(stage, t3);

			assertFalse(
				t1.commitProgress().onWalAppended().toCompletableFuture().isDone(),
				"No transaction may be reported as WAL-persisted before the force covering it completed"
			);

			releaseFirstForce.countDown();

			t1.commitProgress().onWalAppended().toCompletableFuture().get(10, TimeUnit.SECONDS);
			t2.commitProgress().onWalAppended().toCompletableFuture().get(10, TimeUnit.SECONDS);
			t3.commitProgress().onWalAppended().toCompletableFuture().get(10, TimeUnit.SECONDS);

			assertEquals(
				2, forceCount.get(),
				"Three transactions must cost two forces: one for the transaction that started the batch " +
					"and one covering everything that arrived while it was in flight"
			);
			assertEquals(
				List.of(1L, 3L), publishedDurableVersions,
				"The first force may only publish the version sampled before it started; the second must " +
					"publish both stragglers at once"
			);
		} finally {
			syncExecutor.shutdownNow();
		}
	}

	/**
	 * A force that fails means the device stopped accepting the log. Every transaction waiting on that
	 * force must be failed rather than silently acknowledged, and the stage must stop accepting new work -
	 * continuing would hand out commit acknowledgements that could never be honoured.
	 */
	@Test
	@DisplayName("should fail pending transactions and stop accepting more when the WAL cannot be forced")
	void shouldFailPendingTransactionsWhenForceFails() {
		final AtomicLong lastAssigned = new AtomicLong(0L);
		final AtomicLong lastWritten = new AtomicLong(0L);
		final TransactionManager tm = buildTransactionManagerMock(lastAssigned, lastWritten);

		doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
		doAnswer(inv -> 0L).when(tm).appendWalAndDiscard(any(), any(), any());
		doThrow(new RuntimeException("simulated device failure")).when(tm).syncWal();

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);

		final ConflictResolutionAndWalAppendingTransactionTask t1 = newTask();
		feed(stage, t1);
		assertTrue(
			t1.commitProgress().onWalAppended().toCompletableFuture().isCompletedExceptionally(),
			"A transaction whose force failed must be failed, never acknowledged"
		);

		final ConflictResolutionAndWalAppendingTransactionTask t2 = newTask();
		feed(stage, t2);
		assertTrue(
			t2.commitProgress().onWalAppended().toCompletableFuture().isCompletedExceptionally(),
			"Once the WAL cannot be made durable the stage must refuse further transactions"
		);
		verify(tm, times(1)).appendWalAndDiscard(any(), any(), any());
	}

	/**
	 * Builds a minimally populated commit task — the test only cares about the catalog-version
	 * bookkeeping side-effects driven by the mocked `TransactionManager`, so mutation counts, WAL
	 * size, and payloads are irrelevant.
	 */
	private static ConflictResolutionAndWalAppendingTransactionTask newTask() {
		final Set<ConflictKey> conflictKeys = Collections.emptySet();
		final LogRecordReference walReference = new LogRecordReference() {};
		return new ConflictResolutionAndWalAppendingTransactionTask(
			CATALOG_NAME,
			0L,
			UUID.randomUUID(),
			0,
			0L,
			0,
			conflictKeys,
			walReference,
			new CommitProgressRecord()
		);
	}

}
