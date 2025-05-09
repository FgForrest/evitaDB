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

package io.evitadb.core.transaction;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.core.async.DelayedAsyncTask;
import io.evitadb.core.async.ObservableExecutorService;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.transaction.stage.CatalogSnapshotPropagationTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionTransactionStage.ConflictResolutionTransactionTask;
import io.evitadb.core.transaction.stage.TransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.UpdatedCatalogTransactionTask;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage;
import io.evitadb.core.transaction.stage.WalAppendingTransactionStage.WalAppendingTransactionTask;
import io.evitadb.core.transaction.stage.mutation.ServerEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
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
	 * Represents reference to the currently active catalog version in the "live view" of the evitaDB engine.
	 */
	protected final AtomicReference<Catalog> livingCatalog;
	/**
	 * Reference to the configuration of the evitaDB engine.
	 */
	private final EvitaConfiguration configuration;
	/**
	 * The scheduler service used for scheduling tasks.
	 */
	private final Scheduler scheduler;
	/**
	 * The executor used for handling transactional tasks.
	 */
	private final ObservableExecutorService transactionalExecutor;
	/**
	 * Lambda function that is called when a new catalog version is available.
	 */
	private final Consumer<Catalog> newCatalogVersionConsumer;
	/**
	 * Contains the latest version created for appending to the WAL - this practically represents a sequence
	 * number increased with each committed transaction and denotes the next catalog version.
	 */
	private final AtomicLong lastAssignedCatalogVersion;
	/**
	 * Java {@link java.util.concurrent.Flow} implementation that allows to process transactional tasks in
	 * asynchronous reactive manner.
	 */
	private final AtomicReference<SubmissionPublisher<ConflictResolutionTransactionTask>> transactionalPipeline = new AtomicReference<>();
	/**
	 * Contains the last catalog version appended successfully to the WAL (i.e. {@link #lastAssignedCatalogVersion} that
	 * finally arrived to WAL file).
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
	/**
	 * Task that is scheduled to drain the WAL and process the transactions that are not yet processed when there is
	 * emergency situation when some of the tasks was not processed.
	 */
	private final DelayedAsyncTask walDrainingTask;
	/**
	 * Variable that contains last version to be drained from the WAL by scheduled task if not processed.
	 */
	private final AtomicLong versionToDrain = new AtomicLong();
	/**
	 * Lock used for conflict resolution.
	 */
	private final ReentrantLock conflictResolutionLock = new ReentrantLock(true);
	/**
	 * Lock used for appending to WAL.
	 */
	private final ReentrantLock walAppendingLock = new ReentrantLock(true);
	/**
	 * Lock used for incorporation of transactions written to WAL.
	 */
	private final ReentrantLock trunkIncorporationLock = new ReentrantLock(true);
	/**
	 * Lock used for propagating new catalog versions to live view.
	 */
	private final ReentrantLock catalogPropagationLock = new ReentrantLock(true);

	/**
	 * Creates a new transaction based on the given parameters.
	 *
	 * @param transactionMutation The transaction mutation object.
	 * @param previousTransaction The previous transaction, can be null if there is no previous transaction.
	 * @param transactionHandler  The transaction trunk finalizer object.
	 * @return The newly created transaction.
	 */
	@Nonnull
	private static Transaction createTransaction(
		@Nonnull TransactionMutation transactionMutation,
		@Nullable Transaction previousTransaction,
		@Nonnull TransactionTrunkFinalizer transactionHandler
	) {
		return previousTransaction == null ?
			new Transaction(
				transactionMutation.getTransactionId(),
				transactionHandler,
				true
			)
			:
			new Transaction(
				transactionMutation.getTransactionId(),
				transactionHandler,
				previousTransaction.getTransactionalMemory(),
				true
			);
	}

	/**
	 * Commits the changes made to the shared catalog.
	 *
	 * @param lastTransactionMutation The last transaction mutation made on the catalog.
	 * @param transaction             The transaction used to commit the changes.
	 * @param transactionHandler      The handler responsible for finalizing the transaction.
	 */
	@Nonnull
	private static Catalog commitChangesToSharedCatalog(
		@Nonnull TransactionMutation lastTransactionMutation,
		@Nonnull Transaction transaction,
		@Nonnull TransactionTrunkFinalizer transactionHandler
	) {
		return Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				try {
					log.debug("Materializing catalog version: {}", lastTransactionMutation.getCatalogVersion());
					return transactionHandler.commitCatalogChanges(
						lastTransactionMutation.getCatalogVersion(),
						lastTransactionMutation
					);
				} catch (RuntimeException ex) {
					log.error("Error while committing transaction: " + lastTransactionMutation.getCatalogVersion() + ".", ex);
					throw ex;
				}
			}
		);
	}

	/**
	 * Returns true if the current time minus start is within timeoutMs and there is enough data to process.
	 */
	private static boolean thereIsEnoughDataAndTime(
		long timeoutMs,
		long start,
		@Nonnull Catalog latestCatalog,
		@Nonnull TransactionMutation lastTransaction
	) {
		return System.currentTimeMillis() - start < timeoutMs &&
			// and the next transaction is fully written by previous stage
			latestCatalog.getLastCatalogVersionInMutationStream() > lastTransaction.getCatalogVersion();
	}

	public TransactionManager(
		@Nonnull Catalog catalog,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		this.configuration = configuration;
		this.scheduler = scheduler;
		this.transactionalExecutor = transactionalExecutor;
		this.newCatalogVersionConsumer = newCatalogVersionConsumer;
		this.lastFinalizedCatalog = new AtomicReference<>(catalog);
		this.livingCatalog = new AtomicReference<>(catalog);
		// fetch from the persistence store initially - might be greater than current version
		this.lastAssignedCatalogVersion = new AtomicLong(catalog.getLastCatalogVersionInMutationStream());
		this.lastWrittenCatalogVersion = new AtomicLong(this.lastAssignedCatalogVersion.get());
		// this is the catalog version really used (propagated in indexes)
		this.lastFinalizedCatalogVersion = new AtomicLong(catalog.getVersion());

		Assert.isPremiseValid(
			this.lastWrittenCatalogVersion.get() >= this.lastAssignedCatalogVersion.get(),
			"The last finalized catalog version must be greater or equal to last assigned catalog version!"
		);

		this.walDrainingTask = new DelayedAsyncTask(
			catalog.getName(), "WAL draining task",
			scheduler,
			this::drainWal,
			1000, TimeUnit.MILLISECONDS
		);
		// init the publisher
		getTransactionalPublisher();
	}

	/**
	 * Processes the write-ahead log and returns the catalog instance that is the result of the processing.
	 *
	 * @return the catalog instance after processing the write-ahead log
	 */
	@Nonnull
	public Catalog processWriteAheadLog(
		long nextCatalogVersion,
		long timeoutMs,
		boolean alive
	) {
		return processTransactions(nextCatalogVersion, timeoutMs, alive)
			.map(ProcessResult::catalog)
			.orElseGet(this.lastFinalizedCatalog::get);
	}

	/**
	 * Commits the transaction to the transactional pipeline.
	 */
	public void commit(
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull IsolatedWalPersistenceService walPersistenceService,
		@Nonnull CompletableFuture<Long> transactionFinalizationFuture
	) {
		getTransactionalPublisher().offer(
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
	 * This method is called when any of the {@link SubmissionPublisher}
	 * gets closed - for example due to the exception in the processing of the transactional task. One of the possible
	 * issues is that the system can't keep up and throws {@link RejectedExecutionException}.
	 *
	 * In such a situation, the submission publisher is automatically closed and needs to be recreated from scratch.
	 * This is design decision form the authors of the {@link java.util.concurrent.Flow} API.
	 */
	public void invalidateTransactionalPublisher(@Nonnull TransactionTask task, @Nonnull Throwable ex) {
		synchronized (this.transactionalPipeline) {
			ofNullable(this.transactionalPipeline.getAndSet(null))
				.ifPresent(SubmissionPublisher::close);

			if ((task instanceof WalAppendingTransactionTask && ex.getCause() instanceof RejectedExecutionException) ||
				task instanceof TrunkIncorporationTransactionTask ||
				task instanceof UpdatedCatalogTransactionTask
			) {
				this.versionToDrain.updateAndGet(operand -> Math.max(operand, task.catalogVersion()));
				this.walDrainingTask.schedule();
			}
		}
	}

	/**
	 * This method registers the number of catalog versions that were dropped due to the processor being overloaded
	 * or the WAL appending failing. We need to lower newly assigned catalog versions so that they take the dropped
	 * versions into account and produce a consistent sequence of catalog versions.
	 */
	public void notifyCatalogVersionDropped(int numberOfDroppedCatalogVersions) {
		if (numberOfDroppedCatalogVersions > 0) {
			this.lastAssignedCatalogVersion.addAndGet(-numberOfDroppedCatalogVersions);
			final Catalog theLivingCatalog = getLivingCatalog();
			final long theLastAssignedCatalogVersion = getLastAssignedCatalogVersion();
			Assert.isPremiseValid(
				theLastAssignedCatalogVersion >= theLivingCatalog.getVersion(),
				"Unexpected catalog version " + theLivingCatalog.getVersion() + " vs. " + theLastAssignedCatalogVersion + "!"
			);
		} else if (numberOfDroppedCatalogVersions < 0) {
			throw new GenericEvitaInternalError("Negative number of dropped catalog versions!");
		}
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
		final long theLastAssignedCatalogVersion = getLastAssignedCatalogVersion();
		Assert.isPremiseValid(
			theLastAssignedCatalogVersion <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + theLastAssignedCatalogVersion + "!"
		);
		this.lastAssignedCatalogVersion.set(catalogVersion);
		final long theLastWrittenCatalogVersion = getLastWrittenCatalogVersion();
		Assert.isPremiseValid(
			theLastWrittenCatalogVersion <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + theLastWrittenCatalogVersion + "!"
		);
		this.lastWrittenCatalogVersion.set(catalogVersion);
		final long theLastFinalizedCatalogVersion = getLastFinalizedCatalogVersion();
		Assert.isPremiseValid(
			theLastFinalizedCatalogVersion <= catalogVersion,
			"Unexpected catalog version " + catalogVersion + " vs. " + theLastFinalizedCatalogVersion + "!"
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
		final long theLastWrittenCatalogVersion = getLastWrittenCatalogVersion();
		Assert.isPremiseValid(
			theLastWrittenCatalogVersion < catalogVersion,
			"Catalog versions written to WAL must be in order! " +
				"Expected " + (theLastWrittenCatalogVersion + 1) + ", got " + catalogVersion + "."
		);
		final long theLastAssignedCatalogVersion = getLastAssignedCatalogVersion();
		Assert.isPremiseValid(
			theLastAssignedCatalogVersion >= catalogVersion,
			"Last assigned catalog version is expected to be larger or same as WAL written version! " +
				"Expected " + theLastAssignedCatalogVersion + ", got " + catalogVersion + "."
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
	public void updateLastFinalizedCatalog(@Nonnull Catalog lastFinalizedCatalog, long lastFinalizedCatalogVersion) {
		final long theLastFinalizedCatalogVersion = getLastFinalizedCatalogVersion();
		Assert.isPremiseValid(
			theLastFinalizedCatalogVersion < lastFinalizedCatalogVersion,
			"Catalog versions must be in order! " +
				"Expected " + (theLastFinalizedCatalogVersion + 1) + ", got " + lastFinalizedCatalogVersion + "."
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
		final Catalog theLivingCatalog = getLivingCatalog();
		if (livingCatalog.getVersion() > 0L) {
			Assert.isPremiseValid(
				theLivingCatalog.getVersion() < livingCatalog.getVersion() || (theLivingCatalog == livingCatalog),
				"Catalog versions must be in order! " +
					"Expected " + theLivingCatalog.getVersion() + ", got " + livingCatalog.getVersion() + "."
			);
			final long theLastFinalizedVersion = getLastFinalizedCatalogVersion();
			Assert.isPremiseValid(
				theLastFinalizedVersion >= livingCatalog.getVersion(),
				"Catalog versions must be in order! " +
					"Expected " + theLastFinalizedVersion + ", got " + livingCatalog.getVersion() + "."
			);
		}
		this.lastAssignedCatalogVersion.updateAndGet(current -> Math.max(current, livingCatalog.getVersion()));
		this.livingCatalog.set(livingCatalog);

		if (this.lastFinalizedCatalogVersion.getAndUpdate(current -> Math.max(current, livingCatalog.getVersion())) <= livingCatalog.getVersion()) {
			this.lastFinalizedCatalog.set(livingCatalog);
		}
	}

	/**
	 * This method identifies concurrent transaction commits based on passed mutation keys.
	 */
	public void identifyConflicts() {
		try {
			if (this.conflictResolutionLock.tryLock(0, TimeUnit.MILLISECONDS)) {
				// TOBEDONE JNO #503 - implement conflict resolution
			} else {
				throw new TransactionTimedOutException("Conflict resolution lock timed out!");
			}
		} catch (InterruptedException e) {
			throw new GenericEvitaInternalError("Conflict resolution lock interrupted!", e);
		} finally {
			if (this.conflictResolutionLock.isHeldByCurrentThread()) {
				this.conflictResolutionLock.unlock();
			}
		}
	}

	/**
	 * This method writes the contents to the WAL and discards the contents of the isolated WAL.
	 *
	 * @param transactionMutation the leading transaction mutation to write to the WAL
	 * @param walReference        the reference to the WAL file
	 * @return the length of the written WAL contents
	 */
	public long appendWalAndDiscard(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	) {
		try {
			if (this.walAppendingLock.tryLock(0, TimeUnit.MILLISECONDS)) {
				final long theLastWrittenCatalogVersion = this.lastWrittenCatalogVersion.get();
				Assert.isPremiseValid(
					theLastWrittenCatalogVersion <= 0 || theLastWrittenCatalogVersion + 1 == transactionMutation.getCatalogVersion(),
					"Transaction cannot be written to the WAL out of order. " +
						"Expected version " + (theLastWrittenCatalogVersion + 1) + ", got " + transactionMutation.getCatalogVersion() + "."
				);
				return getLivingCatalog()
					.appendWalAndDiscard(
						transactionMutation,
						walReference
					);
			} else {
				throw new TransactionTimedOutException("WAL appending lock timed out!");
			}
		} catch (InterruptedException e) {
			throw new GenericEvitaInternalError("WAL appending lock interrupted!", e);
		} finally {
			if (this.walAppendingLock.isHeldByCurrentThread()) {
				this.walAppendingLock.unlock();
			}
		}
	}

	/**
	 * Processes transactions by reading mutations from the WAL and replaying them on the catalog.
	 *
	 * @param nextCatalogVersion The catalog version of the next transaction to be processed at minimum
	 * @param timeoutMs          The maximum time in milliseconds to process transactions.
	 * @param alive              Indicates whether to process live transactions or not.
	 * @return The processed transaction.
	 */
	@Nonnull
	public Optional<ProcessResult> processTransactions(long nextCatalogVersion, long timeoutMs, boolean alive) {
		try {
			if (this.trunkIncorporationLock.tryLock(0, TimeUnit.MILLISECONDS)) {
				long firstTransactionId = -1;
				TransactionMutation lastTransactionMutation;
				Transaction lastTransaction = null;
				final Catalog newCatalog;

				int atomicMutationCount = 0;
				int localMutationCount = 0;

				final ArrayList<OffsetDateTime> processed = new ArrayList<>(64);
				final long lastFinalizedVersion = getLastFinalizedCatalogVersion();
				final Catalog latestCatalog = getLastFinalizedCatalog();

				Stream<Mutation> committedMutationStream = null;
				try {
					// prepare finalizer that doesn't finish the catalog automatically but on demand
					final TransactionTrunkFinalizer transactionHandler = new TransactionTrunkFinalizer(latestCatalog);
					// read the mutations from the WAL since the last finalized version
					// (but at least 2 - this is the first processable transaction number after going live)
					// if the transaction failed we need to replay it again
					final long readFromVersion = Math.max(lastFinalizedVersion + 1, 2);
					if (alive) {
						committedMutationStream = latestCatalog.getCommittedLiveMutationStream(readFromVersion, nextCatalogVersion);
					} else {
						committedMutationStream = latestCatalog.getCommittedMutationStream(readFromVersion);
					}
					final Iterator<Mutation> mutationIterator = committedMutationStream.iterator();
					if (!mutationIterator.hasNext()) {
						// previous execution already processed all the mutations
						return empty();
					} else {
						long nextExpectedCatalogVersion = lastFinalizedVersion + 1;
						// and process them
						final long start = System.currentTimeMillis();
						do {
							Mutation leadingMutation = mutationIterator.next();
							// the first mutation of the transaction bulk must be transaction mutation
							Assert.isPremiseValid(leadingMutation instanceof TransactionMutation, "First mutation must be transaction mutation!");
							firstTransactionId = firstTransactionId == -1 ? ((TransactionMutation) leadingMutation).getCatalogVersion() : firstTransactionId;

							final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
							long finalNextExpectedCatalogVersion = nextExpectedCatalogVersion;
							Assert.isPremiseValid(
								transactionMutation.getCatalogVersion() == nextExpectedCatalogVersion,
								() -> new GenericEvitaInternalError(
									"Unexpected catalog version! " +
										"Transaction mutation catalog version: " + transactionMutation.getCatalogVersion() + ", " +
										"last finalized catalog version: " + finalNextExpectedCatalogVersion + "."
								)

							);

							log.debug("Starting transaction: {}", transactionMutation);

							// prepare "replay" transaction
							lastTransaction = createTransaction(transactionMutation, lastTransaction, transactionHandler);

							// and replay all the mutations of the entire transaction from the WAL
							// this cannot be interrupted even if the timeout is exceeded and must be fully applied
							final int[] processedCounts = replayMutationsOnCatalog(transactionMutation, lastTransaction, mutationIterator);
							atomicMutationCount += processedCounts[0] + 1;
							localMutationCount += processedCounts[1];

							// this is the last mutation in the transaction, close the replay mutation now
							lastTransaction.close();
							lastTransactionMutation = transactionMutation;

							processed.add(transactionMutation.getCommitTimestamp());
							nextExpectedCatalogVersion = transactionMutation.getCatalogVersion() + 1;

							log.debug("Processed transaction: {}", lastTransaction);
						} while (
							// there is something to process
							mutationIterator.hasNext() &&
								(
									// we haven't reached expected version
									lastTransactionMutation.getCatalogVersion() < nextCatalogVersion ||
										// there is another transaction waiting and we still have a time
										thereIsEnoughDataAndTime(timeoutMs, start, latestCatalog, lastTransactionMutation)
								)
						);

						log.debug(
							"Processed {} transactions ({} atomic mutations, {} local mutations) in {} ms",
							processed.size(), atomicMutationCount, localMutationCount, (System.currentTimeMillis() - start)
						);
					}

					// we've run out of mutation, or the timeout has been exceeded, create a new catalog version now
					// and update the last finalized transaction ID and catalog version
					newCatalog = commitChangesToSharedCatalog(lastTransactionMutation, lastTransaction, transactionHandler);
					updateLastFinalizedCatalog(
						newCatalog,
						lastTransactionMutation.getCatalogVersion()
					);

					log.debug("Finalizing catalog: {}", lastTransactionMutation.getCatalogVersion());

				} catch (RuntimeException ex) {
					// we need to forget about the data written to disk, but not yet propagated to indexes (volatile data)
					latestCatalog.forgetVolatileData();

					// rethrow the exception - we will have to re-try the transaction
					throw ex;
				} finally {
					if (committedMutationStream != null) {
						committedMutationStream.close();
					}
				}

				Assert.isPremiseValid(lastTransaction != null, "Transaction must not be null!");
				final ProcessResult processResult = new ProcessResult(
					lastTransaction.getTransactionId(),
					atomicMutationCount,
					localMutationCount,
					newCatalog,
					processed.toArray(OffsetDateTime[]::new)
				);

				// we can't push another catalog version until the previous one is propagated to the "live view"
				waitUntilLiveVersionReaches(lastFinalizedVersion);

				return of(processResult);
			} else {
				throw new TransactionTimedOutException("Trunk incorporation lock timed out!");
			}
		} catch (InterruptedException e) {
			throw new GenericEvitaInternalError("Trunk incorporation lock interrupted!", e);
		} finally {
			if (this.trunkIncorporationLock.isHeldByCurrentThread()) {
				this.trunkIncorporationLock.unlock();
			}
		}
	}

	/**
	 * Propagates the new catalog version to the "live view" of the evitaDB engine.
	 *
	 * @param newCatalogVersion the new catalog version to propagate
	 */
	public void propagateCatalogSnapshot(@Nonnull Catalog newCatalogVersion) {
		try {
			if (this.catalogPropagationLock.tryLock(0, TimeUnit.MILLISECONDS)) {
				this.newCatalogVersionConsumer.accept(newCatalogVersion);
			} else {
				throw new TransactionTimedOutException("Catalog propagation lock timed out!");
			}
		} catch (InterruptedException e) {
			throw new GenericEvitaInternalError("Catalog propagation lock interrupted!", e);
		} finally {
			if (this.catalogPropagationLock.isHeldByCurrentThread()) {
				this.catalogPropagationLock.unlock();
			}
		}
	}

	/**
	 * Waits until the catalog version in the "live view" reaches the specified version.
	 *
	 * @param catalogVersion The catalog version to wait for in the "live view".
	 */
	public void waitUntilLiveVersionReaches(long catalogVersion) {
		while (getLivingCatalog().getVersion() < catalogVersion) {
			// wait until the catalog version is propagated to the "live view"
			Thread.onSpinWait();
		}
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
	 * Returns the last assigned catalog version to a transaction.
	 *
	 * @return the last assigned catalog version
	 */
	private long getLastAssignedCatalogVersion() {
		return this.lastAssignedCatalogVersion.get();
	}

	/**
	 * Sends the task simulating the WAL stage finalization with tasks that drains entire contents of the WAL in
	 * the trunk incorporation stage. This should handle the situation when last transaction was not processed due
	 * to queues being full. When no other transaction comes the WAL will forever contain more records than are
	 * incorporated in the catalog.
	 */
	private long drainWal() {
		final long theLastFinalizedCatalogVersion = getLastFinalizedCatalogVersion();
		final long catalogVersionToDrain = this.versionToDrain.getAndSet(0L);
		if (catalogVersionToDrain > 0L && catalogVersionToDrain > theLastFinalizedCatalogVersion) {
			try {
				this.processTransactions(
					catalogVersionToDrain,
					this.configuration.transaction().flushFrequencyInMillis(),
					true
				);
			} catch (TransactionTimedOutException ex) {
				// reschedule again
				return 0;
			}
		}
		// pause the task
		return -1;
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
	private SubmissionPublisher<ConflictResolutionTransactionTask> getTransactionalPublisher() {
		final SubmissionPublisher<ConflictResolutionTransactionTask> thePipeline = transactionalPipeline.get();
		if (thePipeline != null && !thePipeline.isClosed()) {
			return thePipeline;
		} else {
			synchronized (this.transactionalPipeline) {
				final int maxBufferCapacity = this.configuration.server().transactionThreadPool().queueSize();

				final SubmissionPublisher<ConflictResolutionTransactionTask> txPublisher = new SubmissionPublisher<>(
					this.transactionalExecutor, maxBufferCapacity
				);
				final ConflictResolutionTransactionStage stage1 = new ConflictResolutionTransactionStage(
					this.transactionalExecutor, maxBufferCapacity, this,
					this::invalidateTransactionalPublisher
				);
				final WalAppendingTransactionStage stage2 = new WalAppendingTransactionStage(
					this.transactionalExecutor, maxBufferCapacity, this,
					this::invalidateTransactionalPublisher
				);
				final TrunkIncorporationTransactionStage stage3 = new TrunkIncorporationTransactionStage(
					this.scheduler, maxBufferCapacity, this,
					this.configuration.transaction().flushFrequencyInMillis(),
					this::invalidateTransactionalPublisher
				);
				final CatalogSnapshotPropagationTransactionStage stage4 = new CatalogSnapshotPropagationTransactionStage(this);

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
	 * Replays mutations in the given transaction on the current catalog.
	 *
	 * @param transactionMutation The transaction mutation containing the catalog version and mutation details.
	 * @param transaction         The transaction object to execute the mutations in.
	 * @param mutationIterator    The iterator containing the mutations to replay.
	 */
	private int[] replayMutationsOnCatalog(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull Transaction transaction,
		@Nonnull Iterator<Mutation> mutationIterator
	) {
		return Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				final Catalog lastFinalizedCatalog = getLastFinalizedCatalog();
				lastFinalizedCatalog.setVersion(transactionMutation.getCatalogVersion());
				// init mutation counter
				int atomicMutationCount = 0;
				int localMutationCount = 0;
				while (atomicMutationCount < transactionMutation.getMutationCount() && mutationIterator.hasNext()) {
					final Mutation mutation = mutationIterator.next();
					log.debug("Processing mutation: {}", mutation);
					atomicMutationCount++;
					if (mutation instanceof EntityUpsertMutation entityUpsertMutation) {
						lastFinalizedCatalog.applyMutation(
							new ServerEntityUpsertMutation(
								entityUpsertMutation,
								EnumSet.allOf(ImplicitMutationBehavior.class),
								false, false
							)
						);
						localMutationCount += entityUpsertMutation.getLocalMutations().size();
					} else if (mutation instanceof EntityRemoveMutation entityRemoveMutation) {
						lastFinalizedCatalog.applyMutation(
							new ServerEntityRemoveMutation(
								entityRemoveMutation, false, false
							)
						);
						localMutationCount += entityRemoveMutation.getLocalMutations().size();
					} else {
						lastFinalizedCatalog.applyMutation(mutation);
						localMutationCount++;
					}
				}
				// we should have processed all the mutations by now and the mutation count should match
				Assert.isPremiseValid(
					atomicMutationCount == transactionMutation.getMutationCount(),
					"Unexpected transaction `" + transactionMutation.getTransactionId() + "` mutation count! " +
						"Transaction mutation mutation count: " + transactionMutation.getMutationCount() + ", " +
						"actual mutation count: " + atomicMutationCount + "."
				);
				return new int[]{atomicMutationCount, localMutationCount};
			}
		);
	}

	/**
	 * Result of the {@link #processTransactions(long, long, boolean)} method.
	 *
	 * @param lastTransactionId                  the ID of the last processed transaction
	 * @param processedAtomicMutations           the number of processed atomic mutations
	 * @param processedLocalMutations            the number of processed local mutations
	 * @param catalog                            the catalog after the processing
	 * @param commitTimesOfProcessedTransactions commit times of all processed transactions
	 */
	public record ProcessResult(
		@Nonnull UUID lastTransactionId,
		int processedAtomicMutations,
		int processedLocalMutations,
		@Nonnull Catalog catalog,
		@Nonnull OffsetDateTime[] commitTimesOfProcessedTransactions
	) {
	}

}
