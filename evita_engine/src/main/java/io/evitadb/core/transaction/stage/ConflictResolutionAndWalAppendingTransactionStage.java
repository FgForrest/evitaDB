/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent;
import io.evitadb.core.metric.event.transaction.TransactionAppendedToWalEvent;
import io.evitadb.core.metric.event.transaction.TransactionConflictEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.ConflictResolutionAndWalAppendingTransactionStage.ConflictResolutionAndWalAppendingTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.spi.store.catalog.exception.CatalogWriteAheadLastTransactionMismatchException;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Represents a transaction stage responsible for resolving conflicts during a transaction and assigning a new
 * catalog version to the transaction which makes a non-interrupted sequence increased by one with each committed
 * transaction (when no conflicts occur).
 *
 * It processes {@link ConflictResolutionAndWalAppendingTransactionTask} objects and produces {@link TrunkIncorporationTransactionTask}
 * objects.
 *
 * Tasks are still delivered to this stage strictly one at a time, but the stage no longer waits for the
 * WAL to reach the device before taking the next one. An append leaves the transaction merely written and
 * parks it on an internal queue; a separate sync task forces the WAL and only then notifies the client and
 * hands the transaction to trunk incorporation. Everything appended while a force is in flight is carried
 * by that force's successor, so one device sync serves a whole batch instead of one transaction - which is
 * what lifts sustained commit throughput off the per-transaction fsync ceiling. The trade is the usual one
 * for group commit: under load a transaction can wait for the tail of an in-flight force plus the next one,
 * so WAL-persistence latency at the high percentiles rises while throughput does.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public final class ConflictResolutionAndWalAppendingTransactionStage
	extends AbstractTransactionStage<ConflictResolutionAndWalAppendingTransactionTask>
	implements Flow.Processor<ConflictResolutionAndWalAppendingTransactionTask, TrunkIncorporationTransactionTask> {

	/**
	 * Publisher that emits {@link TrunkIncorporationTransactionTask} objects to be processed by the next stage.
	 */
	private final SubmissionPublisher<TrunkIncorporationTransactionTask> publisher;
	/**
	 * Executor the WAL syncing task is dispatched to. It is the same pool the publisher hands tasks to the
	 * next stage on - the sync task occupies a thread only while a force is actually in flight and
	 * re-dispatches itself instead of parking, so it costs no permanently blocked thread.
	 */
	private final Executor executor;
	/**
	 * Transactions whose bytes are in the WAL but which no force has covered yet, in append order.
	 *
	 * This queue *is* the group commit: the appending thread pushes onto it and returns immediately, so
	 * everything that accumulates while a force is in flight gets carried to the device by the next single
	 * force instead of paying for one each.
	 */
	private final ArrayDeque<PendingDurability> pendingDurability = new ArrayDeque<>(64);
	/**
	 * Guards {@link #pendingDurability}, which is touched by the appending thread and the syncing task - the
	 * appender keeps adding at the tail while the syncer drains the head, which is precisely what lets one device
	 * force cover a whole batch.
	 *
	 * It is not enough to make the deque itself thread-safe: {@link #releaseDurableTransactions} peeks the head and
	 * then removes it, and those two steps must be **atomic** against each other. Swapping in a concurrent
	 * collection would keep each individual operation safe while silently relying on an invariant enforced
	 * elsewhere - that {@link #syncInFlight} admits only one syncer at a time.
	 */
	private final ReentrantLock pendingDurabilityLock = new ReentrantLock();
	/**
	 * TRUE while a sync task is dispatched or running, so appends do not pile up redundant sync tasks.
	 */
	private final AtomicBoolean syncInFlight = new AtomicBoolean();
	/**
	 * Set to the failure that broke the WAL durability handshake, which permanently fail-stops this stage.
	 *
	 * A force that fails means the device is not accepting the log any more; every transaction appended
	 * after that point could never be made durable, so accepting more of them would hand out commit
	 * acknowledgements that can never be honoured. Deliberately one-way - recovering the WAL is a restart
	 * concern, not something a commit path should attempt.
	 */
	private volatile Throwable walDurabilityFailure;

	public ConflictResolutionAndWalAppendingTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull TransactionManager transactionManager,
		@Nonnull BiConsumer<TransactionTask, Throwable> onException
	) {
		super(transactionManager, onException);
		this.executor = executor;
		this.publisher = new SubmissionPublisher<>(executor, maxBufferCapacity);
	}

	@Override
	protected String getName() {
		return "conflict resolution";
	}

	@Override
	public void subscribe(Subscriber<? super TrunkIncorporationTransactionTask> subscriber) {
		this.publisher.subscribe(subscriber);
	}

	@Override
	public void handleNext(@Nonnull ConflictResolutionAndWalAppendingTransactionTask task) {

		// emit queue event
		task.transactionQueuedEvent().finish().commit();

		Assert.isPremiseValid(
			task.commitProgress() != null,
			"Future is unexpectedly null in the first stage!"
		);

		// refuse up front once the WAL has stopped being able to accept durable writes - see the field
		final Throwable durabilityFailure = this.walDurabilityFailure;
		if (durabilityFailure != null) {
			throw new TransactionException(
				"Write-ahead log of catalog `" + task.catalogName() + "` can no longer be made durable - " +
					"refusing to accept further transactions.",
				durabilityFailure
			);
		}

		final long expectedCatalogVersion = this.transactionManager.getLastAssignedCatalogVersion() + 1L;
		// track how many catalog versions / schema deltas must be rolled back if something throws below
		// resolveConflicts either succeeds (and both effects are applied) or rolls its own effects back
		// internally before propagating the exception, so no bookkeeping is required before it returns
		int droppedCatalogVersions = 0;
		int droppedCatalogSchemaVersionDelta = 0;
		try {
			// first resolve conflicts with previously committed transactions and reserve a catalog version
			final CommitVersions commitVersions = resolveConflicts(task, expectedCatalogVersion);
			droppedCatalogVersions = 1;
			droppedCatalogSchemaVersionDelta = task.catalogSchemaVersionDelta();
			// create the WAL append event up-front so it spans the actual append operation;
			// it is finalized and committed only after the reservation has been cleared below
			final TransactionAppendedToWalEvent walAppendEvent = new TransactionAppendedToWalEvent(task.catalogName());
			// then append the transaction to the shared WAL - a CatalogWriteAheadLastTransactionMismatchException
			// thrown here means the WAL is ahead of the transaction manager and is handled in the outer catch
			final long writtenLength = appendToSharedWal(task, commitVersions);
			// WAL append succeeded — the reserved version is now durable, clear the rollback
			// accounting BEFORE running any post-append bookkeeping (client notification,
			// last-written-version bump, event commit, push to next stage). Those side-effects
			// must not trigger a rollback if they fail, because the WAL itself is already durable
			// and the version cannot be reclaimed without corrupting the mutation stream
			droppedCatalogVersions = 0;
			droppedCatalogSchemaVersionDelta = 0;
			// the bytes are in the WAL, so the reserved version is spoken for and the append-ordering
			// check for the next transaction must already see it
			this.transactionManager.updateLastWrittenCatalogVersion(commitVersions.catalogVersion());
			// the client is deliberately NOT notified here, nor is the task pushed onward:
			// WAIT_FOR_WAL_PERSISTENCE promises the change survives a crash and nothing has reached the
			// device yet. Both happen once a force covers this transaction, which also keeps trunk
			// incorporation from ever checkpointing a version the WAL does not durably hold
			enqueueForDurability(
				new PendingDurability(task, commitVersions, walAppendEvent, writtenLength)
			);
		} catch (RuntimeException ex) {
			// count only genuine conflict-induced rollbacks - not WAL mismatches or other runtime failures -
			// with the resolved policy/layer/scope read straight off the caught exception's diagnostics.
			// Emitted before the rollback so the counter is independent of rollback success.
			if (ex instanceof ConflictingCatalogMutationException conflict) {
				new TransactionConflictEvent(task.catalogName(), conflict).commit();
			}
			rollbackFailedTask(
				task,
				expectedCatalogVersion,
				droppedCatalogVersions,
				droppedCatalogSchemaVersionDelta,
				ex
			);
			// rethrow the exception to be handled by the exception handler
			throw ex;
		}
	}

	/**
	 * Records a written-but-not-yet-durable transaction and makes sure a sync task is on its way.
	 *
	 * @param pending the transaction awaiting a force
	 */
	private void enqueueForDurability(@Nonnull PendingDurability pending) {
		this.pendingDurabilityLock.lock();
		try {
			this.pendingDurability.addLast(pending);
		} finally {
			this.pendingDurabilityLock.unlock();
		}
		scheduleSync();
	}

	/**
	 * Dispatches the sync task unless one is already dispatched or running - in which case that one will
	 * pick up whatever was just enqueued, because it re-reads the queue after every force.
	 */
	private void scheduleSync() {
		if (this.syncInFlight.compareAndSet(false, true)) {
			try {
				this.executor.execute(this::syncPendingTransactions);
			} catch (RuntimeException ex) {
				// the task never started, so nothing else will ever clear the flag
				this.syncInFlight.set(false);
				failPendingDurability(ex);
			}
		}
	}

	/**
	 * Forces the WAL and releases everything the force covered, repeating while transactions keep arriving.
	 *
	 * Each pass samples the queue's tail **before** forcing and credits only up to that version;
	 * transactions appended while the force is in flight are covered by the next pass. Sampling after the
	 * force would acknowledge transactions the device was never asked about.
	 */
	private void syncPendingTransactions() {
		try {
			while (true) {
				final long durableUpTo;
				this.pendingDurabilityLock.lock();
				try {
					final PendingDurability last = this.pendingDurability.peekLast();
					if (last == null) {
						break;
					}
					durableUpTo = last.commitVersions().catalogVersion();
				} finally {
					this.pendingDurabilityLock.unlock();
				}
				if (!forceAndRelease(durableUpTo)) {
					// the WAL is gone - `failPendingDurability` has already emptied the queue
					return;
				}
			}
		} finally {
			this.syncInFlight.set(false);
			// Hand the work on if anything is still queued. Two distinct cases reach this, and both need it:
			//
			// 1. An append landed between the last queue read and the flag clearing above. That appender saw the
			//    flag still set and skipped scheduling, so nobody else will.
			// 2. Something escaped the loop. The flag has just been cleared, but the queue was NOT emptied - unlike
			//    the fail-stop exit, where `failPendingDurability` empties it first. Leaving it here would strand
			//    written-but-unforced transactions until some later append happened to re-arm the syncer, and if no
			//    further write ever arrives their clients wait on durability forever.
			//
			// Deliberately inside the `finally` for case 2: a statement after the try block would be skipped by the
			// very exception that makes the re-dispatch necessary. Re-dispatching cannot spin - each pass of
			// `releaseDurableTransactions` removes its entry before doing anything that can throw, so progress is
			// made even when the failure repeats.
			if (hasPendingDurability()) {
				scheduleSync();
			}
		}
	}

	/**
	 * Forces the WAL, publishes the new durable version and releases every transaction it covered.
	 *
	 * @param durableUpTo the catalog version sampled before the force
	 * @return FALSE when the force failed and this stage has fail-stopped
	 */
	private boolean forceAndRelease(long durableUpTo) {
		try {
			this.transactionManager.syncWal();
			// published before the tasks are pushed onward, so trunk incorporation's round bound already
			// includes everything it is about to be handed
			this.transactionManager.updateLastDurableCatalogVersion(durableUpTo);
		} catch (RuntimeException ex) {
			failPendingDurability(ex);
			return false;
		}
		releaseDurableTransactions(durableUpTo);
		return true;
	}

	/**
	 * Notifies clients and hands to trunk incorporation every transaction now known to be durable, in
	 * append order.
	 *
	 * A failure while releasing one transaction must not strand the rest, so each is released under its own
	 * guard - the WAL itself is fine at this point, the transaction is durable, and the only thing that can
	 * throw here is downstream bookkeeping.
	 *
	 * @param durableUpTo the highest catalog version the completed force covered
	 */
	private void releaseDurableTransactions(long durableUpTo) {
		while (true) {
			final PendingDurability pending;
			this.pendingDurabilityLock.lock();
			try {
				final PendingDurability head = this.pendingDurability.peekFirst();
				if (head == null || head.commitVersions().catalogVersion() > durableUpTo) {
					break;
				}
				pending = this.pendingDurability.removeFirst();
			} finally {
				this.pendingDurabilityLock.unlock();
			}
			try {
				// notify the client - at this point the transaction genuinely survives a crash
				pending.task().commitProgress()
					.complete(
						CommitBehavior.WAIT_FOR_WAL_PERSISTENCE,
						pending.commitVersions(),
						this.transactionManager.getRequestExecutor()
					);
				// the event now spans the append *and* the wait for the force that covered it, which is
				// what "appended to WAL" costs a transaction from the commit path's point of view
				pending.walAppendEvent()
					.finish(pending.task().mutationCount() + 1, pending.writtenLength())
					.commit();
				// and continue with trunk incorporation
				push(
					pending.task(),
					new TrunkIncorporationTransactionTask(
						pending.task().catalogName(),
						pending.commitVersions().catalogVersion(),
						pending.commitVersions().catalogSchemaVersion(),
						pending.task().transactionId(),
						pending.task().commitProgress()
					),
					this.publisher
				);
			} catch (RuntimeException ex) {
				handleException(pending.task(), ex);
			}
		}
	}

	/**
	 * Fail-stops the stage after the WAL could not be made durable and fails every transaction that was
	 * waiting on that force.
	 *
	 * No attempt is made to roll the catalog versions back. Their bytes are in the WAL file already, the
	 * device has stopped accepting writes, and recovery truncates the unreadable tail on restart - trying
	 * to unwind counters in that state would add a second, less predictable failure on top of the first.
	 *
	 * @param cause the failure that broke the durability handshake
	 */
	private void failPendingDurability(@Nonnull Throwable cause) {
		if (this.walDurabilityFailure == null) {
			this.walDurabilityFailure = cause;
		}
		while (true) {
			final PendingDurability pending;
			this.pendingDurabilityLock.lock();
			try {
				pending = this.pendingDurability.pollFirst();
			} finally {
				this.pendingDurabilityLock.unlock();
			}
			if (pending == null) {
				break;
			}
			log.error(
				"Transaction {} (catalogVersion={}) on catalog `{}` was written to the WAL but could not be " +
					"made durable - failing it. No further transaction will be accepted by this catalog.",
				pending.task().transactionId(),
				pending.commitVersions().catalogVersion(),
				pending.task().catalogName(),
				cause
			);
			try {
				pending.task().commitProgress().completeExceptionally(cause);
			} catch (RuntimeException ex) {
				log.error(
					"Failed to notify transaction {} on catalog `{}` about the WAL durability failure.",
					pending.task().transactionId(), pending.task().catalogName(), ex
				);
			}
		}
	}

	/**
	 * @return TRUE when at least one transaction is still awaiting a force
	 */
	private boolean hasPendingDurability() {
		this.pendingDurabilityLock.lock();
		try {
			return !this.pendingDurability.isEmpty();
		} finally {
			this.pendingDurabilityLock.unlock();
		}
	}

	/**
	 * Releases every side-effect accumulated by a task whose processing threw between conflict
	 * resolution and completion of the WAL append (conflict keys, a reserved catalog version
	 * and a pending catalog-schema-version delta), and emits a descriptive ERROR line so the
	 * failure is diagnosable from logs alone.
	 *
	 * The rollback is intentionally split across three independent steps (conflict-key release,
	 * diagnostic log, catalog-version release) that are guarded against each other:
	 *
	 * 1. Conflict-key release is unconditional — it never throws and has no dependency on
	 *    the reservation counters.
	 * 2. The diagnostic log fires FIRST so that a subsequent failure inside the rollback call
	 *    (e.g. `Assert.isPremiseValid` in `notifyCatalogVersionDropped` tripping when the TM
	 *    counters have drifted out of sync with the living catalog) cannot silently replace
	 *    `ex` and strip the only evidence of the real cause.
	 * 3. `notifyCatalogVersionDropped` is wrapped in its own try/catch so a secondary failure
	 *    surfaces as a distinct log line rather than masking the primary exception.
	 *
	 * @param task the failed task
	 * @param expectedCatalogVersion the catalog version the task had tentatively reserved
	 * @param droppedCatalogVersions the number of versions to release (0 if the task failed
	 *                               before the reservation was made)
	 * @param droppedCatalogSchemaVersionDelta the schema-version delta to release
	 * @param ex the exception that terminated task processing
	 */
	private void rollbackFailedTask(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		long expectedCatalogVersion,
		int droppedCatalogVersions,
		int droppedCatalogSchemaVersionDelta,
		@Nonnull RuntimeException ex
	) {
		this.transactionManager.rollbackConflictKeys(expectedCatalogVersion);

		final int adjustedDroppedVersions = widenDroppedVersionsForWalMismatch(droppedCatalogVersions, ex);
		if (adjustedDroppedVersions > 0 || droppedCatalogSchemaVersionDelta > 0) {
			logRollbackDiagnostic(task, expectedCatalogVersion, adjustedDroppedVersions, droppedCatalogSchemaVersionDelta, ex);
			releaseReservedCatalogVersion(task, adjustedDroppedVersions, droppedCatalogSchemaVersionDelta);
		}
	}

	/**
	 * Widens the reservation rollback to cover the whole gap between the TM's `lastWritten`
	 * counter and the WAL's head whenever the failure was caused by a
	 * `CatalogWriteAheadLastTransactionMismatchException` — a signal that the WAL is further
	 * ahead than the TM's bookkeeping believes. For any other exception the caller's original
	 * count is returned unchanged.
	 *
	 * @param currentDroppedVersions the reservation count accumulated before the failure
	 * @param ex the exception that terminated task processing
	 * @return the potentially widened number of catalog versions to drop
	 */
	private int widenDroppedVersionsForWalMismatch(int currentDroppedVersions, @Nonnull RuntimeException ex) {
		if (ex instanceof CatalogWriteAheadLastTransactionMismatchException walMismatch) {
			return Math.toIntExact(
				walMismatch.getCurrentTransactionVersion() - this.transactionManager.getLastWrittenCatalogVersion()
			);
		}
		return currentDroppedVersions;
	}

	/**
	 * Emits a single ERROR line carrying the full TM/WAL state snapshot so the reason for the
	 * reservation rollback is diagnosable from logs alone. Guarded by its own try/catch: a
	 * failure in log-argument evaluation (or a logger misconfiguration) must never mask the
	 * original exception nor prevent the caller's subsequent rollback call from running.
	 */
	private void logRollbackDiagnostic(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		long expectedCatalogVersion,
		int droppedCatalogVersions,
		int droppedCatalogSchemaVersionDelta,
		@Nonnull RuntimeException ex
	) {
		try {
			final Catalog livingCatalog = this.transactionManager.getLivingCatalog();
			log.error(
				"Conflict-resolution/WAL-append stage failed for transaction {} on catalog `{}` " +
					"(reservedCatalogVersion={}, mutationCount={}, walSizeInBytes={}, " +
					"commitStartTime={}, catalogSchemaVersionDelta={}) - rolling back {} catalog " +
					"version(s) and {} catalog schema version delta(s); TransactionManager state " +
					"is lastAssigned={}, lastWritten={}, lastFinalized={}; living catalog version={}; " +
					"current WAL file holds versions [{}..{}].",
				task.transactionId(),
				task.catalogName(),
				expectedCatalogVersion,
				task.mutationCount(),
				task.walSizeInBytes(),
				task.commitProgress().getCommitStartTime(),
				task.catalogSchemaVersionDelta(),
				droppedCatalogVersions,
				droppedCatalogSchemaVersionDelta,
				this.transactionManager.getLastAssignedCatalogVersion(),
				this.transactionManager.getLastWrittenCatalogVersion(),
				this.transactionManager.getLastFinalizedCatalogVersion(),
				livingCatalog.getVersion(),
				livingCatalog.getFirstCatalogVersionInMutationStream(),
				livingCatalog.getLastCatalogVersionInMutationStream(),
				ex
			);
		} catch (Throwable logFailure) {
			// the diagnostic itself failed - emit a minimal fallback so the failure path is
			// still recorded and the logging error does not mask the real exception
			log.error(
				"Conflict-resolution/WAL-append stage failed for transaction {} on catalog `{}`; " +
					"diagnostic logging itself threw, see suppressed exception.",
				task.transactionId(), task.catalogName(), ex
			);
			log.error("Diagnostic logging failure:", logFailure);
		}
	}

	/**
	 * Releases the catalog-version and catalog-schema-version-delta reservation on the
	 * transaction manager. Guarded by its own try/catch: a secondary failure here (e.g. the
	 * `lastAssigned < livingCatalog.version` assertion inside `notifyCatalogVersionDropped`,
	 * the classic "TM drifted below the living catalog" sign) surfaces as a distinct log
	 * line instead of replacing the original exception in flight.
	 */
	private void releaseReservedCatalogVersion(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		int droppedCatalogVersions,
		int droppedCatalogSchemaVersionDelta
	) {
		try {
			this.transactionManager.notifyCatalogVersionDropped(
				droppedCatalogVersions,
				droppedCatalogSchemaVersionDelta
			);
		} catch (Throwable rollbackFailure) {
			log.error(
				"Failed to roll back {} reserved catalog version(s) / {} schema delta(s) after " +
					"the conflict-resolution failure above for transaction {} on catalog `{}`; " +
					"TransactionManager counters may now be inconsistent with the WAL.",
				droppedCatalogVersions,
				droppedCatalogSchemaVersionDelta,
				task.transactionId(),
				task.catalogName(),
				rollbackFailure
			);
		}
	}

	/**
	 * Resolves conflicts for a transaction task by identifying concurrent conflicts, assigning
	 * a new catalog version, and completing commit progress.
	 *
	 * @param task the {@link ConflictResolutionAndWalAppendingTransactionTask} containing the necessary details of the transaction,
	 *             including catalog name, conflict keys, and commit progress
	 * @param expectedCatalogVersion the expected catalog version prior to resolving conflicts
	 */
	@Nonnull
	private CommitVersions resolveConflicts(@Nonnull ConflictResolutionAndWalAppendingTransactionTask task, long expectedCatalogVersion) {
		final TransactionAcceptedEvent conflictResolutionEvent = new TransactionAcceptedEvent(task.catalogName());

		// the expected catalog version doubles as the reservation under which the transaction's conflict
		// keys are registered in the ring buffer — the successor check compares later snapshots against
		// this commit version, and rollbackFailedTask releases exactly the keys registered under it
		this.transactionManager.identifyConflicts(
			task.sessionCatalogVersion(),
			expectedCatalogVersion,
			task.commitProgress().getCommitStartTime(),
			task.conflictKeys()
		);

		// assign new catalog version — from this point on, an exception must roll back the reservation
		final long assignedCatalogVersion = this.transactionManager.getNextCatalogVersionToAssign();
		int appliedSchemaDelta = 0;
		boolean success = false;
		try {
			Assert.isPremiseValid(
				expectedCatalogVersion == assignedCatalogVersion,
				"Expected catalog version " + expectedCatalogVersion + " but got " + assignedCatalogVersion + "!"
			);

			final int estimatedSchemaVersion = this.transactionManager.addDeltaAndEstimateCatalogSchemaVersion(
				task.catalogSchemaVersionDelta()
			);
			appliedSchemaDelta = task.catalogSchemaVersionDelta();

			final CommitVersions commitVersions = new CommitVersions(assignedCatalogVersion, estimatedSchemaVersion);

			// enrol the record under its freshly assigned catalog version. Storing the commitVersions
			// alongside the record lets the trunk stage fan out WAIT_FOR_CHANGES_VISIBLE to every
			// record in a greedy batch, and the helper also kicks the periodic watchdog so a
			// dangling record eventually surfaces as a descriptive exception rather than a hang
			this.transactionManager.registerPendingCommitProgress(
				assignedCatalogVersion, task.commitProgress(), commitVersions
			);

			task.commitProgress()
				.complete(
					CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION,
					commitVersions,
					this.transactionManager.getRequestExecutor()
				);

			conflictResolutionEvent.finishWithResolution(TransactionResolution.COMMIT).commit();

			success = true;
			return commitVersions;
		} finally {
			// if we threw after reserving the version, release exactly what we applied
			if (!success) {
				this.transactionManager.notifyCatalogVersionDropped(1, appliedSchemaDelta);
			}
		}
	}

	/**
	 * Appends a transaction to the shared Write-Ahead Log (WAL) and discards the isolated WAL contents.
	 * This method performs only the durable append; post-append bookkeeping (notifying the client,
	 * bumping the last-written catalog version, committing the WAL-append metric event and pushing
	 * the task to the next stage) is handled by the caller after the rollback accounting has been
	 * cleared, so that a failure in those steps does not wrongly roll back a version that is already
	 * durable in the WAL.
	 *
	 * @param task the {@link ConflictResolutionAndWalAppendingTransactionTask} containing details of the transaction,
	 *             including catalog name, transaction ID, mutation count, WAL reference, and commit progress
	 * @return the number of bytes written to the WAL
	 */
	private long appendToSharedWal(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		@Nonnull CommitVersions commitVersions
	) {
		// append WAL and discard the contents of the isolated WAL
		try {
			log.debug("Appending transaction {} to WAL for catalog {}.", task.transactionId(), task.catalogName());

			return this.transactionManager.appendWalAndDiscard(
				task.commitProgress().getCommitStartTime(),
				new TransactionMutation(
					task.transactionId(),
					commitVersions.catalogVersion(),
					task.mutationCount(),
					task.walSizeInBytes(),
					OffsetDateTime.now()
				),
				task.walReference()
			);
		} catch (CatalogWriteAheadLastTransactionMismatchException ex) {
			final Catalog livingCatalog = this.transactionManager.getLivingCatalog();
			log.error(
				"Transaction/WAL version mismatch in catalog `{}` - transaction {} (reserved " +
					"catalogVersion={}, mutationCount={}, walSizeInBytes={}, commitStartTime={}) " +
					"could not be appended because the WAL reports currentTransactionVersion={} " +
					"while TransactionManager state is lastAssigned={}, lastWritten={}, " +
					"lastFinalized={}; current WAL file holds versions [{}..{}].",
				task.catalogName(),
				task.transactionId(),
				commitVersions.catalogVersion(),
				task.mutationCount(),
				task.walSizeInBytes(),
				task.commitProgress().getCommitStartTime(),
				ex.getCurrentTransactionVersion(),
				this.transactionManager.getLastAssignedCatalogVersion(),
				this.transactionManager.getLastWrittenCatalogVersion(),
				this.transactionManager.getLastFinalizedCatalogVersion(),
				livingCatalog.getFirstCatalogVersionInMutationStream(),
				livingCatalog.getLastCatalogVersionInMutationStream(),
				ex
			);
			throw ex;
		}
	}

	/**
	 * A transaction whose bytes are in the WAL but which no force has covered yet.
	 *
	 * @param task            the originating task - the client is notified and the trunk task built from it
	 *                        once the transaction turns durable
	 * @param commitVersions  the versions assigned to the transaction
	 * @param walAppendEvent  the metric event opened before the append, finished when the force lands
	 * @param writtenLength   number of bytes the append wrote
	 */
	private record PendingDurability(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		@Nonnull CommitVersions commitVersions,
		@Nonnull TransactionAppendedToWalEvent walAppendEvent,
		long writtenLength
	) {
	}

	/**
	 * Represents a task for resolving conflicts during a transaction.
	 *
	 * @param catalogName the name of the catalog the transaction is bound to
	 * @param sessionCatalogVersion the catalog version this transaction session started with (the SNAPSHOT isolation version)
	 * @param transactionId the ID of the transaction
	 * @param mutationCount the number of mutations in the transaction (excluding the leading mutation)
	 * @param walSizeInBytes the size of the WAL file in bytes (size of the mutations excluding the leading mutation)
	 * @param catalogSchemaVersionDelta the difference between catalog schema version at the start of transaction and
	 *                                  the end of transaction
	 * @param conflictKeys the set of conflict keys involved in the transaction
	 * @param walReference the reference to the WAL file
	 * @param commitProgress the commit progress record for the transaction
	 * @param transactionQueuedEvent the event to track the transaction
	 */
	@NonRepeatableTask
	public record ConflictResolutionAndWalAppendingTransactionTask(
		@Nonnull String catalogName,
		long sessionCatalogVersion,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		int catalogSchemaVersionDelta,
		@Nonnull Set<ConflictKey> conflictKeys,
		@Nonnull LogRecordReference walReference,
		@Nonnull CommitProgressRecord commitProgress,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public ConflictResolutionAndWalAppendingTransactionTask(
			@Nonnull String catalogName,
			long sessionCatalogVersion,
			@Nonnull UUID transactionId,
			int mutationCount,
			long walSizeInBytes,
			int catalogSchemaVersionDelta,
			@Nonnull Set<ConflictKey> conflictKeys,
			@Nonnull LogRecordReference walReference,
			@Nonnull CommitProgressRecord commitProgress
		) {
			this(
				catalogName,
				sessionCatalogVersion,
				transactionId,
				mutationCount,
				walSizeInBytes,
				catalogSchemaVersionDelta,
				conflictKeys,
				walReference,
				commitProgress,
				new TransactionQueuedEvent(catalogName, "transaction_acceptance")
			);
		}

		@Override
		public long catalogVersion() {
			throw new UnsupportedOperationException("No catalog version has been assigned yet!");
		}

		@Override
		public int catalogSchemaVersion() {
			throw new UnsupportedOperationException("No catalog version has been assigned yet!");
		}
	}

}
