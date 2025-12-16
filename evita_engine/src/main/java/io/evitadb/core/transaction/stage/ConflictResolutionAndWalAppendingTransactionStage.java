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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent;
import io.evitadb.core.metric.event.transaction.TransactionAppendedToWalEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.ConflictResolutionAndWalAppendingTransactionStage.ConflictResolutionAndWalAppendingTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.exception.CatalogWriteAheadLastTransactionMismatchException;
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
	/**
	 * Number of catalog versions that were dropped during the last transaction processing.
	 * This is used to notify the transaction manager about the dropped versions.
	 */
	private int droppedCatalogVersions;
	/**
	 * Delta of the catalog schema version that was dropped during the last transaction processing.
	 */
	private int droppedCatalogSchemaVersionDelta;

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

		// identify conflicts with other transactions
		final long expectedCatalogVersion = this.transactionManager.getLastAssignedCatalogVersion() + 1L;
		try {
			// first resolve conflicts with previously committed transactions
			final CommitVersions commitVersions = resolveConflicts(task, expectedCatalogVersion);
			// then append the transaction to the shared WAL
			appendToSharedWal(task, commitVersions);
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
			// rollback any conflict keys that were tentatively assigned
			this.transactionManager.rollbackConflictKeys(expectedCatalogVersion);
			// rethrow the exception to be handled by the exception handler
			throw ex;
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

		// assign new catalog version
		final long assignedCatalogVersion = this.transactionManager.getNextCatalogVersionToAssign();
		this.droppedCatalogVersions = 1;

		Assert.isPremiseValid(
			expectedCatalogVersion == assignedCatalogVersion,
			"Expected catalog version " + expectedCatalogVersion + " but got " + assignedCatalogVersion + "!"
		);

		final CommitVersions commitVersions = new CommitVersions(
			assignedCatalogVersion,
			this.transactionManager.addDeltaAndEstimateCatalogSchemaVersion(task.catalogSchemaVersionDelta())
		);

		task.commitProgress()
			.complete(
				CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION,
				commitVersions,
				this.transactionManager.getRequestExecutor()
			);

		conflictResolutionEvent.finishWithResolution(TransactionResolution.COMMIT).commit();

		return commitVersions;
	}

	/**
	 * Appends a transaction to the shared Write-Ahead Log (WAL) and discards the isolated WAL contents.
	 * This method ensures the transaction data is safely persisted to the WAL and updates the transaction
	 * manager's state.
	 *
	 * @param task the {@link ConflictResolutionAndWalAppendingTransactionTask} containing details of the transaction,
	 *             including catalog name, transaction ID, mutation count, WAL reference, and commit progress
	 */
	private void appendToSharedWal(
		@Nonnull ConflictResolutionAndWalAppendingTransactionTask task,
		@Nonnull CommitVersions commitVersions
	) {
		// create WALL appending event
		final TransactionAppendedToWalEvent walAppendEvent = new TransactionAppendedToWalEvent(task.catalogName());

		log.debug("Appending transaction {} to WAL for catalog {}.", task.transactionId(), task.catalogName());

		// append WAL and discard the contents of the isolated WAL
		final long writtenLength;
		try {
			writtenLength = this.transactionManager.appendWalAndDiscard(
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
			log.error(
				"Transaction mismatch between transaction manager and WAL {} vs. {} in catalog {}.",
				ex.getCurrentTransactionVersion(),
				this.transactionManager.getLastWrittenCatalogVersion(),
				task.catalogName(),
				ex
			);
			this.droppedCatalogVersions = Math.toIntExact(ex.getCurrentTransactionVersion() - this.transactionManager.getLastWrittenCatalogVersion());
			this.droppedCatalogSchemaVersionDelta = task.catalogSchemaVersionDelta();
			throw ex;
		}

		// notify client at this moment that the transaction is safely written to the WAL
		// the push to next stage might fail, but the WAL is already written
		task.commitProgress()
			.complete(
				CommitBehavior.WAIT_FOR_WAL_PERSISTENCE,
				commitVersions,
				this.transactionManager.getRequestExecutor()
			);

		this.transactionManager.updateLastWrittenCatalogVersion(commitVersions.catalogVersion());

		// now the WAL is safely written - no version is lost
		this.droppedCatalogVersions = 0;
		this.droppedCatalogSchemaVersionDelta = 0;

		// emit the event
		walAppendEvent.finish(
			task.mutationCount() + 1,
			writtenLength
		).commit();
	}

	@Override
	protected void handleException(@Nonnull ConflictResolutionAndWalAppendingTransactionTask task, @Nonnull Throwable ex) {
		try {
			if (this.droppedCatalogVersions > 0 || this.droppedCatalogSchemaVersionDelta > 0) {
				this.transactionManager.notifyCatalogVersionDropped(
					this.droppedCatalogVersions,
					this.droppedCatalogSchemaVersionDelta
				);
			}
		} finally {
			super.handleException(task, ex);
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
		@Nonnull OffHeapWithFileBackupReference walReference,
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
			@Nonnull OffHeapWithFileBackupReference walReference,
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
