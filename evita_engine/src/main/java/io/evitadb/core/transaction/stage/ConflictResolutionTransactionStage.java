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
import io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage.ConflictResolutionTransactionTask;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage.WalAppendingTransactionTask;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
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
 * It processes {@link ConflictResolutionTransactionTask} objects and produces {@link WalAppendingTransactionTask}
 * objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public final class ConflictResolutionTransactionStage
	extends AbstractTransactionStage<ConflictResolutionTransactionTask>
	implements Flow.Processor<ConflictResolutionTransactionTask, WalAppendingTransactionTask> {

	/**
	 * Publisher that emits {@link WalAppendingTransactionTask} objects to be processed by the next stage.
	 */
	private final SubmissionPublisher<WalAppendingTransactionTask> publisher;


	public ConflictResolutionTransactionStage(
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
	public void subscribe(Subscriber<? super WalAppendingTransactionTask> subscriber) {
		this.publisher.subscribe(subscriber);
	}

	@Override
	public void handleNext(@Nonnull ConflictResolutionTransactionTask task) {

		// emit queue event
		task.transactionQueuedEvent().finish().commit();

		Assert.isPremiseValid(
			task.commitProgress() != null,
			"Future is unexpectedly null on first stage!"
		);

		final TransactionAcceptedEvent event = new TransactionAcceptedEvent(task.catalogName());

		// identify conflicts with other transactions
		this.transactionManager.identifyConflicts();

		// assign new catalog version
		final WalAppendingTransactionTask targetTask = new WalAppendingTransactionTask(
			task.catalogName(),
			this.transactionManager.getNextCatalogVersionToAssign(),
			this.transactionManager.addDeltaAndEstimateCatalogSchemaVersion(task.catalogSchemaVersionDelta),
			task.catalogSchemaVersionDelta,
			task.transactionId(),
			task.mutationCount(),
			task.walSizeInBytes(),
			task.walReference(),
			task.commitProgress()
		);

		push(task, targetTask, this.publisher);

		task.commitProgress()
			.complete(
				CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION,
				new CommitVersions(targetTask.catalogVersion(), targetTask.catalogSchemaVersion()),
				this.transactionManager.getRequestExecutor()
			);

		event.finishWithResolution(TransactionResolution.COMMIT).commit();
	}

	@Override
	protected void handleException(@Nonnull ConflictResolutionTransactionTask task, @Nonnull Throwable ex) {
		this.transactionManager.notifyCatalogVersionDropped(1, task.catalogSchemaVersionDelta);
		super.handleException(task, ex);
	}

	/**
	 * Represents a task for resolving conflicts during a transaction.
	 *
	 * @param catalogName the name of the catalog the transaction is bound to
	 * @param transactionId the ID of the transaction
	 * @param mutationCount the number of mutations in the transaction (excluding the leading mutation)
	 * @param walSizeInBytes the size of the WAL file in bytes (size of the mutations excluding the leading mutation)
	 * @param catalogSchemaVersionDelta the difference between catalog schema version at the start of transaction and
	 *                                  the end of transaction
	 * @param walReference the reference to the WAL file
	 * @param commitProgress the commit progress record for the transaction
	 * @param transactionQueuedEvent the event to track the transaction
	 */
	@NonRepeatableTask
	public record ConflictResolutionTransactionTask(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		int catalogSchemaVersionDelta,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitProgressRecord commitProgress,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public ConflictResolutionTransactionTask(
			@Nonnull String catalogName,
			@Nonnull UUID transactionId,
			int mutationCount,
			long walSizeInBytes,
			int catalogSchemaVersionDelta,
			@Nonnull OffHeapWithFileBackupReference walReference,
			@Nonnull CommitProgressRecord commitProgress
		) {
			this(
				catalogName,
				transactionId,
				mutationCount,
				walSizeInBytes,
				catalogSchemaVersionDelta,
				walReference,
				commitProgress,
				new TransactionQueuedEvent(catalogName, "conflict_resolution")
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
