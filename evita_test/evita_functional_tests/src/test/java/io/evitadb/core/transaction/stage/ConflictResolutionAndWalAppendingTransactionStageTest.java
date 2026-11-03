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
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscription;
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
import static org.mockito.Mockito.never;
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
	private static final int REGION_COUNT = 16;

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
	 * Conflict resolution registers every commit progress record, so taking up a new commit only queues another record
	 * behind those waiting for trunk incorporation. Reporting it as pipeline progress would let steady traffic keep the
	 * dangling-commit watchdog from ever failing records a stalled trunk incorporation holds.
	 */
	@Test
	@DisplayName("should not report pipeline progress when it takes up a commit")
	void shouldNotReportPipelineProgressWhenTakingUpACommit() {
		final AtomicLong lastAssigned = new AtomicLong(312L);
		final AtomicLong lastWritten = new AtomicLong(312L);

		final TransactionManager tm = buildTransactionManagerMock(lastAssigned, lastWritten);
		doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
		doAnswer(inv -> {
			lastWritten.set(313L);
			return 0L;
		}).when(tm).appendWalAndDiscard(any(), any(), any());

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);
		stage.onSubscribe(
			new Subscription() {
				@Override
				public void request(long n) {
					// the test hands the task over directly
				}

				@Override
				public void cancel() {
					// nothing to cancel
				}
			}
		);

		stage.onNext(newTask());

		assertEquals(313L, lastWritten.get(), "the commit must have passed the stage");
		verify(tm, never()).recordPipelineProgress();
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
	 * A transaction refused in conflict resolution never reaches the shared WAL, so the append never releases its
	 * isolated WAL contents. The stage has to, otherwise every refused transaction keeps its off-heap region - and with
	 * it the catalog's whole memory block, which is freed only once every region is back.
	 */
	@Test
	@DisplayName("should release the isolated WAL of a transaction refused in conflict resolution")
	void shouldReleaseIsolatedWalOfTransactionRefusedInConflictResolution() {
		final TransactionManager tm = buildTransactionManagerMock(new AtomicLong(312L), new AtomicLong(312L));
		doThrow(new RuntimeException("simulated conflict"))
			.when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);

		try (final CatalogOffHeapMemoryManager memoryManager = newMemoryManager()) {
			feed(stage, newTask(newOffHeapWalReference(memoryManager)));

			assertEquals(REGION_COUNT, memoryManager.getFreeRegions());
		}
	}

	/**
	 * A transaction whose WAL append throws before copying its contents has to release them as well.
	 */
	@Test
	@DisplayName("should release the isolated WAL of a transaction whose WAL append failed")
	void shouldReleaseIsolatedWalOfTransactionWhoseWalAppendFailed() {
		final TransactionManager tm = buildTransactionManagerMock(new AtomicLong(312L), new AtomicLong(312L));
		doNothing().when(tm).identifyConflicts(anyLong(), anyLong(), any(), any());
		doThrow(new RuntimeException("simulated WAL lock timeout"))
			.when(tm).appendWalAndDiscard(any(), any(), any());

		final ConflictResolutionAndWalAppendingTransactionStage stage =
			new ConflictResolutionAndWalAppendingTransactionStage(
				Runnable::run,
				100,
				tm,
				(task, ex) -> {}
			);

		try (final CatalogOffHeapMemoryManager memoryManager = newMemoryManager()) {
			feed(stage, newTask(newOffHeapWalReference(memoryManager)));

			assertEquals(REGION_COUNT, memoryManager.getFreeRegions());
		}
	}

	/**
	 * Once the WAL cannot be made durable the stage refuses every further transaction up front - and has to release
	 * the isolated WAL of each.
	 */
	@Test
	@DisplayName("should release the isolated WAL of a transaction refused because the WAL cannot be made durable")
	void shouldReleaseIsolatedWalOfTransactionRefusedAfterDurabilityFailure() {
		final TransactionManager tm = buildTransactionManagerMock(new AtomicLong(0L), new AtomicLong(0L));
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
		feed(stage, newTask());

		try (final CatalogOffHeapMemoryManager memoryManager = newMemoryManager()) {
			final ConflictResolutionAndWalAppendingTransactionTask refused =
				newTask(newOffHeapWalReference(memoryManager));
			feed(stage, refused);

			assertTrue(refused.commitProgress().onWalAppended().toCompletableFuture().isCompletedExceptionally());
			assertEquals(REGION_COUNT, memoryManager.getFreeRegions());
		}
	}

	/**
	 * Creates an off-heap memory manager whose regions hold the isolated WAL of the tasks in the release tests.
	 *
	 * @return the memory manager
	 */
	@Nonnull
	private static CatalogOffHeapMemoryManager newMemoryManager() {
		return new CatalogOffHeapMemoryManager(CATALOG_NAME, 1024, REGION_COUNT, ChecksumFactory.NO_OP);
	}

	/**
	 * Creates a WAL reference to isolated WAL contents held in a region of the given manager, the way an isolated WAL
	 * kept in off-heap memory hands its contents over to the pipeline.
	 *
	 * @param memoryManager the manager to acquire the region from
	 * @return the reference, whose closing releases the region
	 */
	@Nonnull
	private static OffHeapWithFileBackupReference newOffHeapWalReference(
		@Nonnull CatalogOffHeapMemoryManager memoryManager
	) {
		final OffHeapMemoryOutputStream region = memoryManager.acquireRegionOutputStream().orElseThrow();
		assertEquals(REGION_COUNT - 1, memoryManager.getFreeRegions());
		return OffHeapWithFileBackupReference.withByteBuffer(region.getByteBuffer(), 0, 0L, region::close);
	}

	/**
	 * Builds a minimally populated commit task — the test only cares about the catalog-version
	 * bookkeeping side-effects driven by the mocked `TransactionManager`, so mutation counts, WAL
	 * size, and payloads are irrelevant.
	 */
	private static ConflictResolutionAndWalAppendingTransactionTask newTask() {
		return newTask(new LogRecordReference() {});
	}

	/**
	 * Builds a minimally populated commit task carrying the given WAL reference.
	 *
	 * @param walReference the reference to the isolated WAL contents of the transaction
	 * @return the task
	 */
	@Nonnull
	private static ConflictResolutionAndWalAppendingTransactionTask newTask(@Nonnull LogRecordReference walReference) {
		final Set<ConflictKey> conflictKeys = Collections.emptySet();
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
