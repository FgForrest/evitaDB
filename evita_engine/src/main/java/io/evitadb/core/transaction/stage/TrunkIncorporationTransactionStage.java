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
import io.evitadb.core.Catalog;
import io.evitadb.core.metric.event.transaction.NewCatalogVersionPropagatedEvent;
import io.evitadb.core.metric.event.transaction.TransactionIncorporatedToTrunkEvent;
import io.evitadb.core.metric.event.transaction.TransactionProcessedEvent;
import io.evitadb.core.metric.event.transaction.TransactionQueuedEvent;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.TransactionManager.ProcessResult;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.function.Functions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
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
	extends AbstractTransactionStage<TrunkIncorporationTransactionTask> {
	/**
	 * The timeout in nanoseconds determining the maximum time the task is allowed to consume multiple transaction.
	 * It might be exceeded if the single transaction processing takes too long, but if the single transaction is very
	 * fast it tries to process next one until the timeout is exceeded.
	 */
	private final long timeout;

	public TrunkIncorporationTransactionStage(
		@Nonnull TransactionManager transactionManager,
		long timeoutInMillis,
		@Nonnull BiConsumer<TransactionTask, Throwable> onException
	) {
		super(transactionManager, onException);
		this.timeout = timeoutInMillis * 1_000_000;
	}

	@Override
	protected String getName() {
		return "trunk incorporation";
	}

	@Override
	protected void handleNext(@Nonnull TrunkIncorporationTransactionTask task) {
		final CommitVersions commitVersions = new CommitVersions(task.catalogVersion(), task.catalogSchemaVersion());
		if (task.catalogVersion() <= this.transactionManager.getLastFinalizedCatalogVersion()) {
			// the transaction has been already processed
			// but we can't mark transaction as processed until it's propagated to the "live view"
			this.transactionManager.waitUntilLiveVersionReaches(task.catalogVersion());
			task.commitProgress().complete(
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE,
				commitVersions,
				this.transactionManager.getRequestExecutor()
			);
			log.debug("Skipping version " + task.catalogVersion() + " as it has been already processed.");
		} else {
			// emit queue event
			task.transactionQueuedEvent().finish().commit();

			synchronized (this) {
				try {
					this.wait(50);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}

			final TransactionIncorporatedToTrunkEvent event = new TransactionIncorporatedToTrunkEvent(this.transactionManager.getCatalogName());
			this.transactionManager.processTransactions(
					task.catalogVersion(),
					this.timeout,
					true,
					true, // we want to wait for lock so that we can complete the commit progress record
					Functions.noOpLongConsumer()
				)
				.ifPresentOrElse(
					result -> {
						// emit event
						event.finish(
							result.processedAtomicMutations(),
							result.processedLocalMutations(),
							result.commitTimesOfProcessedTransactions().length
						).commit();

						// propagate catalog to shared view
						propagateCatalogToSharedView(result, task.commitProgress(), commitVersions);
					},
					() -> {
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
	 * Propagates the catalog snapshot to a shared view, processes results, and emits related events.
	 * This method is responsible for handling the transaction snapshot propagation and tracking the progress
	 * of the commit operation.
	 *
	 * @param result               the result of transaction processing that contains details about the catalog
	 *                             and commit times of processed transactions
	 * @param commitProgressRecord an object that represents the progress of the ongoing commit operation
	 * @param commitVersions       the version details of the catalog and catalog schema after the commit operation
	 */
	private void propagateCatalogToSharedView(
		@Nonnull ProcessResult result,
		@Nonnull CommitProgressRecord commitProgressRecord,
		@Nonnull CommitVersions commitVersions
	) {
		final Catalog catalog = result.catalog();
		final String catalogName = catalog.getName();

		final NewCatalogVersionPropagatedEvent event = new NewCatalogVersionPropagatedEvent(catalogName);
		try {
			this.transactionManager.propagateCatalogSnapshot(catalog);
			log.debug("Snapshot propagating task for catalog `" + catalogName + "` completed (" + catalog.getEntityTypes() + ")!");
			commitProgressRecord.complete(
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE,
				commitVersions,
				this.transactionManager.getRequestExecutor()
			);
		} catch (Throwable ex) {
			log.error("Error while processing snapshot propagating task for catalog `" + catalogName + "`!", ex);
			commitProgressRecord.completeExceptionally(ex);
		}

		// emit the event
		event.finish(result.commitTimesOfProcessedTransactions().length).commit();

		// emit transaction processed events
		final OffsetDateTime now = OffsetDateTime.now();
		for (OffsetDateTime commitTime : result.commitTimesOfProcessedTransactions()) {
			new TransactionProcessedEvent(catalogName, Duration.between(commitTime, now)).commit();
		}
	}

	/**
	 * Represents a task for trunk incorporation during a transaction.
	 * Trunk incorporation is the process of incorporating changes from WAL into a trunk (shared) catalog snapshot.
	 *
	 * @param catalogName          The name of the catalog associated with the transaction.
	 * @param catalogVersion       The version of the catalog associated with the transaction.
	 * @param catalogSchemaVersion assigned catalog schema version (the sequence number of the next catalog schema version)
	 * @param transactionId        The ID of the transaction.
	 * @param commitProgress       The CompletableFuture that can be used to obtain the long value representing the outcome of the task.
	 *                             It may be null if the outcome is not available or not needed.
	 * @see TransactionTask
	 */
	public record TrunkIncorporationTransactionTask(
		@Nonnull String catalogName,
		long catalogVersion,
		int catalogSchemaVersion,
		@Nonnull UUID transactionId,
		@Nonnull CommitProgressRecord commitProgress,
		@Nonnull TransactionQueuedEvent transactionQueuedEvent
	) implements TransactionTask {

		public TrunkIncorporationTransactionTask(
			@Nonnull String catalogName,
			long catalogVersion,
			int catalogSchemaVersion,
			@Nonnull UUID transactionId,
			@Nonnull CommitProgressRecord commitProgress
		) {
			this(
				catalogName, catalogVersion, catalogSchemaVersion, transactionId, commitProgress,
				new TransactionQueuedEvent(catalogName, "trunk_incorporation")
			);
		}

	}

}
