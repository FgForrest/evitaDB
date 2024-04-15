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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage.WalAppendingTransactionTask;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Represents a stage in a catalog processing pipeline that appends isolated write-ahead log (WAL) entries to a shared
 * WAL. So that it can be consumed by later stages and also propagated to external subscribers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public final class WalAppendingTransactionStage
	extends AbstractTransactionStage<WalAppendingTransactionTask, TrunkIncorporationTransactionTask> {

	public WalAppendingTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull Catalog catalog
	) {
		super(executor, maxBufferCapacity, catalog);
	}

	@Override
	protected String getName() {
		return "WAL writer";
	}

	@Override
	protected void handleNext(@Nonnull WalAppendingTransactionTask task) {
		// append WAL and discard the contents of the isolated WAL
		this.liveCatalog.get().appendWalAndDiscard(
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
		@Nullable CompletableFuture<Long> future
	) implements TransactionTask {
	}

}
