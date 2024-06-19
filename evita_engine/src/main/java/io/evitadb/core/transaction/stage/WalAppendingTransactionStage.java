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
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.metric.event.transaction.TransactionAppendedToWalEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage.WalAppendingTransactionTask;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Represents a stage in a catalog processing pipeline that appends isolated write-ahead log (WAL) entries to a shared
 * WAL. So that it can be consumed by later stages and also propagated to external subscribers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public final class WalAppendingTransactionStage
	extends AbstractTransactionStage<WalAppendingTransactionTask, TrunkIncorporationTransactionTask> {
	/**
	 * Contains consumer that compensates the catalog version in case of a failure in previous stages of the pipeline.
	 */
	private final IntConsumer catalogVersionCompensator;
	/**
	 * Contains last catalog version appended successfully to the WAL.
	 */
	private long lastWrittenCatalogVersion = -1L;

	public WalAppendingTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull Catalog catalog,
		@Nonnull IntConsumer catalogVersionCompensator,
		@Nonnull Runnable onException
	) {
		super(executor, maxBufferCapacity, catalog, onException);
		this.catalogVersionCompensator = catalogVersionCompensator;
	}

	@Override
	protected String getName() {
		return "WAL writer";
	}

	@Override
	protected void handleNext(@Nonnull WalAppendingTransactionTask task) {
		// emit queue event
		task.transactionQueuedEvent().finish().commit();

		Assert.isPremiseValid(
			lastWrittenCatalogVersion == -1 || lastWrittenCatalogVersion == task.catalogVersion() - 1,
			"Transaction cannot be written to the WAL out of order. " +
				"Expected version " + (lastWrittenCatalogVersion + 1) + ", got " + task.catalogVersion() + "."
		);

		// create WALL appending event
		final TransactionAppendedToWalEvent event = new TransactionAppendedToWalEvent(task.catalogName());

		// append WAL and discard the contents of the isolated WAL
		final long writtenLength = this.liveCatalog.get().appendWalAndDiscard(
			new TransactionMutation(
				task.transactionId(),
				task.catalogVersion(),
				task.mutationCount(),
				task.walSizeInBytes(),
				OffsetDateTime.now()
			),
			task.walReference()
		);
		// and continue with trunk incorporation
		push(
			task,
			new TrunkIncorporationTransactionTask(
				task.catalogName(),
				task.catalogVersion(),
				task.transactionId(),
				task.commitBehaviour(),
				task.commitBehaviour() != CommitBehavior.WAIT_FOR_WAL_PERSISTENCE ? task.future() : null
			)
		);

		// emit the event
		event.finish(
			task.mutationCount() + 1,
			writtenLength
		).commit();

		this.lastWrittenCatalogVersion = task.catalogVersion();
	}

	@Override
	protected void handleException(@Nonnull WalAppendingTransactionTask task, @Nonnull Throwable ex) {
		this.catalogVersionCompensator.accept(1);
		super.handleException(task, ex);
	}

	/**
	 * Represents a task for resolving conflicts during a transaction.
	 *
	 * @param catalogName the name of the catalog the transaction is bound to
	 * @param catalogVersion assigned catalog version (the sequence number of the next catalog version)
	 * @param transactionId the ID of the transaction
	 * @param mutationCount the number of mutations in the transaction (excluding the leading mutation)
	 * @param walSizeInBytes the size of the WAL file in bytes (size of the mutations excluding the leading mutation)
	 * @param walReference the reference to the WAL file
	 * @param commitBehaviour requested stage to wait for during commit
	 * @param future the future to complete when the transaction propagates to requested stage
	 */
	public record WalAppendingTransactionTask(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable CompletableFuture<Long> future,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public WalAppendingTransactionTask(@Nonnull String catalogName, long catalogVersion, @Nonnull UUID transactionId, int mutationCount, long walSizeInBytes, @Nonnull OffHeapWithFileBackupReference walReference, @Nonnull CommitBehavior commitBehaviour, @Nullable CompletableFuture<Long> future) {
			this(catalogName, catalogVersion, transactionId, mutationCount, walSizeInBytes, walReference, commitBehaviour, future, new TransactionQueuedEvent(catalogName, "wal_appending"));
		}
	}

}
