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
import io.evitadb.core.metric.event.transaction.TransactionIncorporatedToTrunkEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.UpdatedCatalogTransactionTask;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * This stage of transaction processing reads the changes recorded in the WAL and applies them to the last snapshot
 * of the catalog. This task tries to execute the transaction greedily within the given timeout, so it may process
 * multiple transaction for each arrived task. It's because the merging of the diff changes in a transactional
 * memory layer is an expensive operation and we want to minimize the number of such operations.
 *
 * Upon already performed task arrival, the task is immediately marked as processed and the processing is skipped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public final class TrunkIncorporationTransactionStage
	extends AbstractTransactionStage<TrunkIncorporationTransactionTask, UpdatedCatalogTransactionTask> {
	/**
	 * The timeout in nanoseconds determining the maximum time the task is allowed to consume multiple transaction.
	 * It might be exceeded if the single transaction processing takes too long, but if the single transaction is very
	 * fast it tries to process next one until the timeout is exceeded.
	 */
	private final long timeout;

	public TrunkIncorporationTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull TransactionManager transactionManager,
		long timeoutInMillis,
		@Nonnull BiConsumer<TransactionTask, Throwable> onException
	) {
		super(executor, maxBufferCapacity, transactionManager, onException);
		this.timeout = timeoutInMillis * 1_000_000;
	}

	@Override
	protected String getName() {
		return "trunk incorporation";
	}

	@Override
	protected void handleNext(@Nonnull TrunkIncorporationTransactionTask task) {
		if (task.catalogVersion() <= this.transactionManager.getLastFinalizedCatalogVersion()) {
			// the transaction has been already processed
			if (task.future() != null) {
				// we can't mark transaction as processed until it's propagated to the "live view"
				this.transactionManager.waitUntilLiveVersionReaches(task.catalogVersion());
				task.future().complete(task.catalogVersion());
			}
			log.info("Skipping version " + task.catalogVersion() + " as it has been already processed.");
		} else {
			// emit queue event
			task.transactionQueuedEvent().finish().commit();

			final TransactionIncorporatedToTrunkEvent event = new TransactionIncorporatedToTrunkEvent(this.transactionManager.getCatalogName());
			this.transactionManager.processTransactions(
				task.catalogVersion(),
				this.timeout,
				true
			)
				.ifPresentOrElse(
					result -> {
						// and propagate it to the live view
						push(
							task,
							new UpdatedCatalogTransactionTask(
								result.catalog(),
								result.lastTransactionId(),
								task.commitBehaviour(),
								task.future(),
								result.commitTimesOfProcessedTransactions()
							)
						);

						// emit event
						event.finish(
							result.processedAtomicMutations(),
							result.processedLocalMutations(),
							result.commitTimesOfProcessedTransactions().length
						).commit();
					},
					() -> {
						// and terminate the task
						terminate(
							task,
							new UpdatedCatalogTransactionTask(
								this.transactionManager.getLastFinalizedCatalog(),
								task.transactionId(),
								task.commitBehaviour(),
								task.future(),
								new OffsetDateTime[0]
							)
						);

						// emit event
						event.finish(
							0,
							0,
							0
						).commit();
					});
		}
	}

	/**
	 * Represents a task for trunk incorporation during a transaction.
	 * Trunk incorporation is the process of incorporating changes from WAL into a trunk (shared) catalog snapshot.
	 *
	 * @param catalogName     The name of the catalog associated with the transaction.
	 * @param catalogVersion  The version of the catalog associated with the transaction.
	 * @param transactionId   The ID of the transaction.
	 * @param commitBehaviour The commit behavior to use during trunk incorporation.
	 * @param future          The CompletableFuture that can be used to obtain the long value representing the outcome of the task.
	 *                        It may be null if the outcome is not available or not needed.
	 * @see TransactionTask
	 */
	public record TrunkIncorporationTransactionTask(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable CompletableFuture<Long> future,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public TrunkIncorporationTransactionTask(
			@Nonnull String catalogName,
			long catalogVersion,
			@Nonnull UUID transactionId,
			@Nonnull CommitBehavior commitBehaviour,
			@Nullable CompletableFuture<Long> future
		) {
			this(
				catalogName, catalogVersion, transactionId, commitBehaviour, future,
				new TransactionQueuedEvent(catalogName, "trunk_incorporation")
			);
		}

	}

	/**
	 * Represents a task for new catalog version propagation to the "life view". I.e. when the newly built catalog
	 * trunk (SNAPSHOT) is propagated to the "live view" of the evitaDB engine.
	 */
	public record UpdatedCatalogTransactionTask(
		@Nonnull Catalog catalog,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable CompletableFuture<Long> future,
		@Nonnull OffsetDateTime[] commitTimestamps,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public UpdatedCatalogTransactionTask(
			@Nonnull Catalog catalog,
			@Nonnull UUID transactionId,
			@Nonnull CommitBehavior commitBehaviour,
			@Nullable CompletableFuture<Long> future,
			@Nonnull OffsetDateTime[] commitTimestamps
		) {
			this(catalog, transactionId, commitBehaviour, future, commitTimestamps, new TransactionQueuedEvent(catalog.getName(), "catalog_propagation"));
		}

		@Nonnull
		@Override
		public String catalogName() {
			return catalog.getName();
		}

		@Override
		public long catalogVersion() {
			return catalog.getVersion();
		}


	}

}
