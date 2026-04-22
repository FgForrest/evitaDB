/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent;
import io.evitadb.core.metric.event.transaction.TransactionAppendedToWalEvent;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiConsumer;

/**
 * Represents a transaction stage responsible for resolving conflicts during a transaction and assigning a new
 * catalog version to the transaction which makes a non-interrupted sequence increased by one with each committed
 * transaction (when no conflicts occur).
 *
 * It processes {@link ConflictResolutionAndWalAppendingTransactionTask} objects and produces {@link TrunkIncorporationTransactionTask}
 * objects.
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

	public ConflictResolutionAndWalAppendingTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull TransactionManager transactionManager,
		@Nonnull BiConsumer<TransactionTask, Throwable> onException
	) {
		super(transactionManager, onException);
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
			// notify client at this moment that the transaction is safely written to the WAL
			// the push to next stage might fail, but the WAL is already written
			task.commitProgress()
				.complete(
					CommitBehavior.WAIT_FOR_WAL_PERSISTENCE,
					commitVersions,
					this.transactionManager.getRequestExecutor()
				);
			this.transactionManager.updateLastWrittenCatalogVersion(commitVersions.catalogVersion());
			// emit the event
			walAppendEvent.finish(task.mutationCount() + 1, writtenLength).commit();
			// and continue with trunk incorporation
			push(
				task,
				new TrunkIncorporationTransactionTask(
					task.catalogName(),
					commitVersions.catalogVersion(),
					commitVersions.catalogSchemaVersion(),
					task.transactionId(),
					task.commitProgress()
				),
				this.publisher
			);
		} catch (RuntimeException ex) {
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

		this.transactionManager.identifyConflicts(
			task.sessionCatalogVersion(),
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
