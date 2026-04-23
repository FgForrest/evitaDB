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

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
		doNothing().when(tm).identifyConflicts(anyLong(), any(), any());
		doThrow(new RuntimeException("simulated WAL fsync failure"))
			.when(tm).appendWalAndDiscard(any(), any(), any());

		feed(stage, newTask());

		// T1's rollback is correct: one version was assigned (313) and then released
		assertEquals(312L, lastAssigned.get(),
			"After T1 failed in WAL append the assignment counter must return to its pre-T1 value");

		// T2..T7 — identifyConflicts throws, so the assignment step is never reached.
		doThrow(new RuntimeException("simulated conflict"))
			.when(tm).identifyConflicts(anyLong(), any(), any());

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
		doNothing().when(tm).identifyConflicts(anyLong(), any(), any());
		doAnswer(inv -> {
			lastWritten.set(313L);
			return 0L;
		}).when(tm).appendWalAndDiscard(any(), any(), any());

		feed(stage, newTask());

		assertEquals(313L, lastAssigned.get(), "T1 must leave one version assigned");
		assertEquals(313L, lastWritten.get(), "T1 must leave one version written");

		// T2 fails inside identifyConflicts — no assignment should happen, no rollback either.
		doThrow(new RuntimeException("simulated conflict"))
			.when(tm).identifyConflicts(anyLong(), any(), any());

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
