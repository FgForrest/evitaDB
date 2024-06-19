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

package io.evitadb.core.transaction;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.Catalog;
import io.evitadb.core.async.ObservableExecutorService;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.transaction.stage.CatalogSnapshotPropagationTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage.ConflictResolutionTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage;
import io.evitadb.store.spi.IsolatedWalPersistenceService;

import javax.annotation.Nonnull;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Transaction manager is propagated through different versions / instances of the same catalog and is responsible for
 * managing the transaction processing pipeline. This pipeline or its parts might be closed anytime due to
 * the {@link RejectedExecutionException} and needs to be recreated from scratch when this happens. There must be no
 * more than single active transaction pipeline at a time.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TransactionManager {
	/**
	 * Java {@link java.util.concurrent.Flow} implementation that allows to process transactional tasks in
	 * asynchronous reactive manner.
	 */
	private final AtomicReference<SubmissionPublisher<ConflictResolutionTransactionTask>> transactionalPipeline = new AtomicReference<>();

	public TransactionManager(
		@Nonnull Catalog catalog,
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		getTransactionalPublisher(
			catalog, serverOptions, transactionOptions, scheduler, transactionalExecutor, newCatalogVersionConsumer
		);
	}

	/**
	 * Method lazily creates and returns the transaction pipeline. The transaction processing consists of 4 stages:
	 *
	 * - conflict resolution (and catalog version sequence number assignment)
	 * - WAL appending (writing {@link IsolatedWalPersistenceService} to the shared catalog WAL)
	 * - trunk incorporation (applying transaction from shared WAL in order to the shared catalog view)
	 * - catalog snapshot propagation (propagating new catalog version to the "live view" of the evitaDB engine)
	 *
	 * @return the submission publisher for conflict resolution transaction tasks
	 */
	@Nonnull
	public SubmissionPublisher<ConflictResolutionTransactionTask> getTransactionalPublisher(
		@Nonnull Catalog catalog,
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		final SubmissionPublisher<ConflictResolutionTransactionTask> thePipeline = transactionalPipeline.get();
		if (thePipeline != null && !thePipeline.isClosed()) {
			return thePipeline;
		} else {
			synchronized (this.transactionalPipeline) {
				final int maxBufferCapacity = serverOptions.transactionThreadPool().queueSize();

				final SubmissionPublisher<ConflictResolutionTransactionTask> txPublisher = new SubmissionPublisher<>(
					transactionalExecutor, maxBufferCapacity
				);
				final ConflictResolutionTransactionStage stage1 = new ConflictResolutionTransactionStage(
					transactionalExecutor, maxBufferCapacity, catalog,
					this::invalidateTransactionalPublisher
				);
				final WalAppendingTransactionStage stage2 = new WalAppendingTransactionStage(
					transactionalExecutor, maxBufferCapacity, catalog,
					stage1::notifyCatalogVersionDropped,
					this::invalidateTransactionalPublisher
				);
				final TrunkIncorporationTransactionStage stage3 = new TrunkIncorporationTransactionStage(
					scheduler, maxBufferCapacity,
					catalog, transactionOptions.flushFrequencyInMillis(),
					this::invalidateTransactionalPublisher
				);
				final CatalogSnapshotPropagationTransactionStage stage4 = new CatalogSnapshotPropagationTransactionStage(
					newCatalogVersionConsumer
				);

				txPublisher.subscribe(stage1);
				stage1.subscribe(stage2);
				stage2.subscribe(stage3);
				stage3.subscribe(stage4);

				this.transactionalPipeline.set(txPublisher);

				return txPublisher;
			}
		}
	}

	/**
	 * This method is called when any of the {@link SubmissionPublisher}
	 * gets closed - for example due to the exception in the processing of the transactional task. One of the possible
	 * issues is that the system can't keep up and throws {@link RejectedExecutionException}.
	 *
	 * In such a situation, the submission publisher is automatically closed and needs to be recreated from scratch.
	 * This is design decision form the authors of the {@link java.util.concurrent.Flow} API.
	 */
	public void invalidateTransactionalPublisher() {
		synchronized (this.transactionalPipeline) {
			ofNullable(this.transactionalPipeline.getAndSet(null))
				.ifPresent(SubmissionPublisher::close);

		}
	}
}
