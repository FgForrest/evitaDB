/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.Catalog;
import io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage.ConflictResolutionTransactionTask;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage.WalAppendingTransactionTask;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

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
	extends AbstractTransactionStage<ConflictResolutionTransactionTask, WalAppendingTransactionTask> {

	/**
	 * Contains current version of the catalog - this practically represents a sequence number increased with each
	 * committed transaction and denotes the next catalog version.
	 */
	private final AtomicLong catalogVersion;

	public ConflictResolutionTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull Catalog catalog
	) {
		super(executor, maxBufferCapacity, catalog);
		this.catalogVersion = new AtomicLong(catalog.getVersion());
	}

	@Override
	protected String getName() {
		return "conflict resolution";
	}

	@Override
	public void handleNext(@Nonnull ConflictResolutionTransactionTask task) {

		// emit queue event
		task.transactionQueuedEvent().finish().commit();

		Assert.isPremiseValid(
			task.future() != null,
			"Future is unexpectedly null on first stage!"
		);

		final TransactionAcceptedEvent event = new TransactionAcceptedEvent(task.catalogName());

		// identify conflicts with other transaction
		// TOBEDONE JNO #503 - implement conflict resolution
		// assign new catalog version
		push(
			task,
			new WalAppendingTransactionTask(
				task.catalogName(),
				this.catalogVersion.incrementAndGet(),
				task.transactionId(),
				task.mutationCount(),
				task.walSizeInBytes(),
				task.walReference(),
				task.commitBehaviour(),
				task.commitBehaviour() != CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION ? task.future() : null
			)
		);

		event.finishWithResolution(TransactionResolution.COMMIT).commit();
	}

	@Override
	protected void handleException(@Nonnull ConflictResolutionTransactionTask task, @Nonnull Throwable ex) {
		notifyCatalogVersionDropped(1);
		super.handleException(task, ex);
	}

	/**
	 * This method registers the number of catalog versions that were dropped due to the processor being overloaded
	 * or the WAL appending failing. We need to lower newly assigned catalog versions so that they take the dropped
	 * versions into account and produce a consistent sequence of catalog versions.
	 */
	public void notifyCatalogVersionDropped(int numberOfDroppedCatalogVersions) {
		this.catalogVersion.addAndGet(-numberOfDroppedCatalogVersions);
	}

	@Override
	public void advanceVersion(long catalogVersion) {
		// we need to advance the version to the latest committed version
		Assert.isPremiseValid(
			this.catalogVersion.get() <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + this.catalogVersion + "!"
		);
		this.catalogVersion.set(catalogVersion);
	}

	@Override
	public void updateCatalogReference(@Nonnull Catalog catalog) {
		super.updateCatalogReference(catalog);
		// at this moment, the catalog transitions from non-transactional to transactional state
		if (!this.catalogVersion.compareAndSet(0, catalog.getVersion())) {
			Assert.isPremiseValid(
				this.catalogVersion.get() >= catalog.getVersion(),
				"Unexpected catalog version " + catalog.getVersion() + " vs. " + this.catalogVersion + "!"
			);
		}
	}

	/**
	 * Represents a task for resolving conflicts during a transaction.
	 *
	 * @param catalogName the name of the catalog the transaction is bound to
	 * @param transactionId the ID of the transaction
	 * @param mutationCount the number of mutations in the transaction (excluding the leading mutation)
	 * @param walSizeInBytes the size of the WAL file in bytes (size of the mutations excluding the leading mutation)
	 * @param walReference the reference to the WAL file
	 * @param commitBehaviour requested stage to wait for during commit
	 * @param future the future to complete when the transaction propagates to requested stage
	 */
	public record ConflictResolutionTransactionTask(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull CompletableFuture<Long> future,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public ConflictResolutionTransactionTask {
			Assert.isPremiseValid(
				future != null,
				"Future is unexpectedly null!"
			);
		}

		public ConflictResolutionTransactionTask(@Nonnull String catalogName, @Nonnull UUID transactionId, int mutationCount, long walSizeInBytes, @Nonnull OffHeapWithFileBackupReference walReference, @Nonnull CommitBehavior commitBehaviour, @Nonnull CompletableFuture<Long> future) {
			this(catalogName, transactionId, mutationCount, walSizeInBytes, walReference, commitBehaviour, future, new TransactionQueuedEvent(catalogName, "conflict_resolution"));
		}

		@Override
		public long catalogVersion() {
			throw new UnsupportedOperationException("No catalog version has been assigned yet!");
		}
	}

}
