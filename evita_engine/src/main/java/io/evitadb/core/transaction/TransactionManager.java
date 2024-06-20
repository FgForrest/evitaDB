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

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.core.Catalog;
import io.evitadb.core.async.ObservableExecutorService;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.transaction.stage.AbstractTransactionStage;
import io.evitadb.core.transaction.stage.CatalogSnapshotPropagationTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage.ConflictResolutionTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.ProcessResult;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;
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
@Slf4j
public class TransactionManager {
	/**
	 * Contains the latest version of the catalog written to WAL - this practically represents a sequence
	 * number increased with each committed transaction and denotes the next catalog version.
	 */
	private final AtomicLong lastAssignedCatalogVersion;
	/**
	 * Represents reference to the currently active catalog version in the "live view" of the evitaDB engine.
	 */
	protected final AtomicReference<Catalog> livingCatalog;
	/**
	 * Java {@link java.util.concurrent.Flow} implementation that allows to process transactional tasks in
	 * asynchronous reactive manner.
	 */
	private final AtomicReference<SubmissionPublisher<ConflictResolutionTransactionTask>> transactionalPipeline = new AtomicReference<>();
	/**
	 * Contains last catalog version appended successfully to the WAL.
	 */
	private final AtomicLong lastWrittenCatalogVersion;
	/**
	 * Contains the ID of the last finalized transaction. This is used to skip already processed transaction.
	 */
	private final AtomicLong lastFinalizedCatalogVersion;
	/**
	 * Contains reference to the current catalog snapshot this trunk incorporation task will be building upon.
	 * The catalog is being exchanged regularly and the instance of the TransactionManager is not recreated - i.e. stays
	 * the same for different catalog versions and is propagated throughout the whole lifetime of the "logical" catalog.
	 *
	 * This catalog might not be visible yet in evita instance and may differ from {@link #livingCatalog}.
	 */
	private final AtomicReference<Catalog> lastFinalizedCatalog;

	public TransactionManager(
		@Nonnull Catalog catalog,
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		this.lastFinalizedCatalog = new AtomicReference<>(catalog);
		this.livingCatalog = new AtomicReference<>(catalog);
		this.lastAssignedCatalogVersion = new AtomicLong(catalog.getVersion());
		this.lastWrittenCatalogVersion = new AtomicLong(catalog.getVersion());
		this.lastFinalizedCatalogVersion = new AtomicLong(catalog.getVersion());
		getTransactionalPublisher(
			serverOptions, transactionOptions, scheduler, transactionalExecutor, newCatalogVersionConsumer
		);
	}

	/**
	 * Processes the write-ahead log and returns the catalog instance that is the result of the processing.
	 *
	 * @return the catalog instance after processing the write-ahead log
	 */
	@Nonnull
	public Catalog processWriteAheadLog(
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		long nextCatalogVersion,
		long timeoutMs,
		boolean alive
	) {
		return getTrunkIncorporationStage(
			serverOptions, transactionOptions, scheduler, transactionalExecutor, newCatalogVersionConsumer
		)
			.processTransactions(nextCatalogVersion, timeoutMs, alive)
			.map(ProcessResult::catalog)
			.orElseGet(this.lastFinalizedCatalog::get);
	}

	/**
	 * Commits the transaction to the transactional pipeline.
	 */
	public void commit(
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull IsolatedWalPersistenceService walPersistenceService,
		@Nonnull CompletableFuture<Long> transactionFinalizationFuture
	) {
		getTransactionalPublisher(
			serverOptions, transactionOptions, scheduler, transactionalExecutor, newCatalogVersionConsumer
		).offer(
			new ConflictResolutionTransactionTask(
				getCatalogName(),
				transactionId,
				walPersistenceService.getMutationCount(),
				walPersistenceService.getMutationSizeInBytes(),
				walPersistenceService.getWalReference(),
				commitBehaviour,
				transactionFinalizationFuture
			),
			(subscriber, task) -> {
				invalidateTransactionalPublisher();
				transactionFinalizationFuture.completeExceptionally(
					new TransactionException(
						"Conflict resolution transaction queue is full! Transaction cannot be processed at the moment."
					)
				);
				return false;
			}
		);
	}

	/**
	 * Returns the current catalog instance that is visible as living catalog instance to all the queries.
	 *
	 * @return the living catalog instance visible to all queries
	 */
	@Nonnull
	public Catalog getLivingCatalog() {
		return this.livingCatalog.get();
	}

	/**
	 * Returns reference to the current catalog snapshot this trunk incorporation task will be building upon.
	 * The catalog is being exchanged regularly and the instance of the TransactionManager is not recreated - i.e. stays
	 * the same for different catalog versions and is propagated throughout the whole lifetime of the "logical" catalog.
	 *
	 * This catalog might not be visible yet in evita instance and may differ from {@link #livingCatalog}.
	 *
	 * @return the latest catalog instance visible only to trunk incorporation stage
	 */
	@Nonnull
	public Catalog getLastFinalizedCatalog() {
		return this.lastFinalizedCatalog.get();
	}

	/**
	 * Returns the name of the catalog this transaction manager is bound to.
	 *
	 * @return the name of the catalog
	 */
	@Nonnull
	public String getCatalogName() {
		return this.livingCatalog.get().getName();
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

	/**
	 * This method registers the number of catalog versions that were dropped due to the processor being overloaded
	 * or the WAL appending failing. We need to lower newly assigned catalog versions so that they take the dropped
	 * versions into account and produce a consistent sequence of catalog versions.
	 */
	public void notifyCatalogVersionDropped(int numberOfDroppedCatalogVersions) {
		this.lastAssignedCatalogVersion.addAndGet(-numberOfDroppedCatalogVersions);
		Assert.isPremiseValid(
			this.lastAssignedCatalogVersion.get() >= this.livingCatalog.get().getVersion(),
			"Unexpected catalog version " + this.livingCatalog.get().getVersion() + " vs. " + this.lastAssignedCatalogVersion + "!"
		);
	}

	/**
	 * This method is called to assign a new catalog version to a newly committed / accepted transaction.
	 *
	 * @return the next catalog version to assign
	 */
	public long getNextCatalogVersionToAssign() {
		return this.lastAssignedCatalogVersion.incrementAndGet();
	}

	/**
	 * Informs transactional pipeline jobs that the catalog version has advanced due to external reasons (such as
	 * catalog renaming).
	 */
	public void advanceVersion(long catalogVersion) {
		// we need to advance the version to the latest committed version
		Assert.isPremiseValid(
			this.lastAssignedCatalogVersion.get() <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + this.lastAssignedCatalogVersion + "!"
		);
		this.lastAssignedCatalogVersion.set(catalogVersion);
		Assert.isPremiseValid(
			this.lastFinalizedCatalogVersion.get() <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + this.lastFinalizedCatalogVersion + "!"
		);
		this.lastFinalizedCatalogVersion.set(catalogVersion);
	}

	/**
	 * Returns the last catalog version successfully written to WAL.
	 *
	 * @return the last written catalog version
	 */
	public long getLastWrittenCatalogVersion() {
		return this.lastWrittenCatalogVersion.get();
	}

	/**
	 * Updates the last catalog version written to WAL to the given value.
	 *
	 * @param catalogVersion the last written catalog version
	 */
	public void updateLastWrittenCatalogVersion(long catalogVersion) {
		Assert.isPremiseValid(
			this.lastWrittenCatalogVersion.get() < catalogVersion,
			"Catalog versions written to WAL must be in order! " +
				"Expected " + (this.lastWrittenCatalogVersion.get() + 1) + ", got " + catalogVersion + "."
		);
		Assert.isPremiseValid(
			this.lastAssignedCatalogVersion.get() >= catalogVersion,
			"Last assigned catalog version is expected to be larger or same as WAL written version! " +
				"Expected " + this.lastAssignedCatalogVersion.get() + ", got " + catalogVersion + "."
		);
		this.lastWrittenCatalogVersion.set(catalogVersion);
	}

	/**
	 * Returns the last catalog version incorporated in {@link #lastFinalizedCatalog} instance.
	 *
	 * @return the last incorporated catalog version
	 */
	public long getLastFinalizedCatalogVersion() {
		return this.lastFinalizedCatalogVersion.get();
	}

	/**
	 * Updates the last finalized catalog version to the given value.
	 *
	 * @param lastFinalizedCatalog        the last finalized catalog
	 * @param lastFinalizedCatalogVersion the last finalized catalog version
	 */
	public void updateLastFinalizedDatalog(@Nonnull Catalog lastFinalizedCatalog, long lastFinalizedCatalogVersion) {
		Assert.isPremiseValid(
			this.lastFinalizedCatalogVersion.get() < lastFinalizedCatalogVersion,
			"Catalog versions must be in order! " +
				"Expected " + (this.lastFinalizedCatalogVersion.get() + 1) + ", got " + lastFinalizedCatalogVersion + "."
		);
		Assert.isPremiseValid(
			lastFinalizedCatalog.getVersion() == lastFinalizedCatalogVersion,
			"Catalog version must match the catalog version number!"
		);
		this.lastFinalizedCatalog.set(lastFinalizedCatalog);
		this.lastFinalizedCatalogVersion.set(lastFinalizedCatalogVersion);
	}

	/**
	 * Notifies the system that a catalog is present in the live view.
	 * This method is used to indicate that a catalog is currently available in the live view.
	 */
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog livingCatalog) {
		Assert.isPremiseValid(
			this.livingCatalog.get().getVersion() < livingCatalog.getVersion(),
			"Catalog versions must be in order! " +
				"Expected " + this.livingCatalog.get().getVersion() + ", got " + livingCatalog.getVersion() + "."
		);
		Assert.isPremiseValid(
			this.lastFinalizedCatalogVersion.get() >= livingCatalog.getVersion(),
			"Catalog versions must be in order! " +
				"Expected " + this.lastFinalizedCatalogVersion.get() + ", got " + livingCatalog.getVersion() + "."
		);
		this.lastAssignedCatalogVersion.updateAndGet(current -> Math.max(current, livingCatalog.getVersion()));
		this.livingCatalog.set(livingCatalog);

		if (this.lastFinalizedCatalogVersion.getAndUpdate(current -> Math.max(current, livingCatalog.getVersion())) < livingCatalog.getVersion()) {
			this.lastFinalizedCatalog.set(livingCatalog);
		}
	}

	/**
	 * Retrieves the TrunkIncorporationTransactionStage from the transactional pipeline.
	 *
	 * @return The TrunkIncorporationTransactionStage.
	 */
	@Nonnull
	private TrunkIncorporationTransactionStage getTrunkIncorporationStage(
		@Nonnull ServerOptions serverOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		SubmissionPublisher<?> current = getTransactionalPublisher(
			serverOptions, transactionOptions, scheduler, transactionalExecutor, newCatalogVersionConsumer
		);
		while (current != null && !current.isClosed()) {
			//noinspection unchecked
			final List<Subscriber<?>> subscribers = (List<Subscriber<?>>) current.getSubscribers();
			Assert.isPremiseValid(
				current.isClosed() || subscribers.size() == 1,
				"Only one subscriber is expected, " + subscribers.size() + " found!"
			);
			for (Subscriber<?> subscriber : subscribers) {
				if (subscriber instanceof TrunkIncorporationTransactionStage stage) {
					return stage;
				} else if (subscriber instanceof AbstractTransactionStage<?, ?> transactionStage) {
					current = transactionStage;
				} else {
					current = null;
				}
			}
		}
		throw new GenericEvitaInternalError(
			"TrunkIncorporationTransactionStage is not present in the transactional pipeline!"
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
	private SubmissionPublisher<ConflictResolutionTransactionTask> getTransactionalPublisher(
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
					transactionalExecutor, maxBufferCapacity, this,
					this::invalidateTransactionalPublisher
				);
				final WalAppendingTransactionStage stage2 = new WalAppendingTransactionStage(
					transactionalExecutor, maxBufferCapacity, this,
					this::invalidateTransactionalPublisher
				);
				final TrunkIncorporationTransactionStage stage3 = new TrunkIncorporationTransactionStage(
					scheduler, maxBufferCapacity, this,
					transactionOptions.flushFrequencyInMillis(),
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

}
