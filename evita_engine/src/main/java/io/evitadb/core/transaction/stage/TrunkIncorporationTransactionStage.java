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
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.TransactionTrunkFinalizer;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.UpdatedCatalogTransactionTask;
import io.evitadb.core.transaction.stage.mutation.VerifiedEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.VerifiedEntityUpsertMutation;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

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
	 * Represents reference to the currently active catalog version in the "live view" of the evitaDB engine.
	 * It may be a little older reference than in {@link #catalog}.
	 */
	private final AtomicReference<Catalog> liveCatalog;
	/**
	 * The timeout in nanoseconds determining the maximum time the task is allowed to consume multiple transaction.
	 * It might be exceeded if the single transaction processing takes too long, but if the single transaction is very
	 * fast it tries to process next one until the timeout is exceeded.
	 */
	private final long timeout;
	/**
	 * Contains reference to the current catalog snapshot this task will be building upon. The catalog is being exchanged
	 * regularly and the instance of the TrunkIncorporationTransactionStage is not recreated - i.e. stays the same for
	 * different catalog versions and is propagated throughout the whole lifetime of the "logical" catalog.
	 *
	 * This catalog is replaced with new version each time {@link #handleNext(TrunkIncorporationTransactionTask)} is
	 * finished.
	 */
	@Getter
	private Catalog catalog;
	/**
	 * Contains the ID of the last finalized transaction. This is used to skip already processed transaction.
	 */
	private long lastFinalizedCatalogVersion;

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

	public TrunkIncorporationTransactionStage(
		@Nonnull Executor executor,
		int maxBufferCapacity,
		@Nonnull Catalog catalog,
		long timeoutInMillis
	) {
		super(executor, maxBufferCapacity, catalog.getName());
		this.catalog = catalog;
		this.liveCatalog = new AtomicReference<>(catalog);
		this.timeout = timeoutInMillis * 1_000_000;
		this.lastFinalizedCatalogVersion = catalog.getVersion();
	}

	/**
	 * Processes transactions by reading mutations from the WAL and replaying them on the catalog.
	 *
	 * @param nextCatalogVersion The catalog version of the next transaction to be processed.
	 * @param timeoutMs          The maximum time in milliseconds to process transactions.
	 * @param alive              Indicates whether to process live transactions or not.
	 * @return The processed transaction.
	 */
	@Nonnull
	public UUID processTransactions(long nextCatalogVersion, long timeoutMs, boolean alive) {
		TransactionMutation lastTransaction = null;
		Transaction transaction = null;
		try {
			// prepare finalizer that doesn't finish the catalog automatically but on demand
			final TransactionTrunkFinalizer transactionHandler = new TransactionTrunkFinalizer(this.catalog);
			// read the mutations from the WAL since the current task version
			// if the transaction failed we need to replay it again
			final Iterator<Mutation> mutationIterator;
			if (alive) {
				mutationIterator = this.catalog
					.getCommittedLiveMutationStream(Math.min(nextCatalogVersion, this.lastFinalizedCatalogVersion + 1))
					.iterator();
			} else {
				mutationIterator = this.catalog
					.getCommittedMutationStream(Math.min(nextCatalogVersion, this.lastFinalizedCatalogVersion + 1))
					.iterator();
			}
			// and process them
			if (mutationIterator.hasNext()) {
				int txCount = 0;
				final long start = System.nanoTime();
				do {
					txCount++;
					Mutation leadingMutation = mutationIterator.next();
					// the first mutation of the transaction bulk must be transaction mutation
					Assert.isPremiseValid(leadingMutation instanceof TransactionMutation, "First mutation must be transaction mutation!");

					final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
					Assert.isPremiseValid(
						transactionMutation.getCatalogVersion() == this.lastFinalizedCatalogVersion + 1 ||
							(lastTransaction != null && transactionMutation.getCatalogVersion() == lastTransaction.getCatalogVersion() + 1),
						() -> new EvitaInternalError(
							"Unexpected catalog version! " +
								"Transaction mutation catalog version: " + transactionMutation.getCatalogVersion() + ", " +
								"last finalized catalog version: " + this.lastFinalizedCatalogVersion + "."
						)

					);

					log.debug("Starting transaction: {}", transactionMutation);

					// prepare "replay" transaction
					transaction = createTransaction(transactionMutation, transaction, transactionHandler);

					// and replay all the mutations of the entire transaction from the WAL
					// this cannot be interrupted even if the timeout is exceeded and must be fully applied
					replayMutationsOnCatalog(transactionMutation, transaction, mutationIterator);

					// this is the last mutation in the transaction, close the replay mutation now
					transaction.close();
					lastTransaction = transactionMutation;

					log.debug("Processed transaction: {}", transaction);
				} while (
					// try to process next transaction if the timeout is not exceeded
					System.nanoTime() - start < timeoutMs &&
						mutationIterator.hasNext() &&
						// and the next transaction is fully written by previous stage
						this.catalog.getLastCatalogVersionInMutationStream() > lastTransaction.getCatalogVersion()
				);

				log.debug("Processed {} transactions in {} ms", txCount, (System.nanoTime() - start) / 1_000_000.0);
			}

			// we've run out of mutation, or the timeout has been exceeded, create a new catalog version now
			this.catalog = commitChangesToSharedCatalog(lastTransaction, transaction, transactionHandler);

			// update the last finalized transaction ID and catalog version
			this.lastFinalizedCatalogVersion = lastTransaction.getCatalogVersion();

			log.debug("Finalizing catalog: {}", this.lastFinalizedCatalogVersion);

		} catch (RuntimeException ex) {
			// we need to forget about the data written to disk, but not yet propagated to indexes (volatile data)
			this.catalog.forgetVolatileData();

			// rethrow the exception - we will have to re-try the transaction
			throw ex;
		}

		Assert.isPremiseValid(transaction != null, "Transaction must not be null!");
		return transaction.getTransactionId();
	}

	@Override
	protected String getName() {
		return "trunk incorporation";
	}

	@Override
	protected void handleNext(@Nonnull TrunkIncorporationTransactionTask task) {
		if (task.catalogVersion() <= this.lastFinalizedCatalogVersion) {
			// the transaction has been already processed
			if (task.future() != null) {
				// we can't mark transaction as processed until it's propagated to the "live view"
				waitUntilLiveVersionReaches(task.catalogVersion());
				task.future().complete(task.catalogVersion());
			}
		} else {
			final long lastCatalogVersionInLiveView = this.catalog.getVersion();
			final UUID lastTransactionId = processTransactions(
				task.catalogVersion(), this.timeout, true
			);

			// we can't push another catalog version until the previous one is propagated to the "live view"
			waitUntilLiveVersionReaches(lastCatalogVersionInLiveView);

			// and propagate it to the live view
			push(
				task,
				new UpdatedCatalogTransactionTask(
					this.catalog,
					lastTransactionId,
					task.commitBehaviour(),
					task.future()
				)
			);
		}
	}

	@Override
	public void updateCatalogReference(@Nonnull Catalog catalog) {
		this.liveCatalog.set(catalog);
		if (catalog.getVersion() > this.catalog.getVersion()) {
			this.catalog = catalog;
			// at this moment, the catalog transitions from non-transactional to transactional state
			if (this.lastFinalizedCatalogVersion == 0L) {
				this.lastFinalizedCatalogVersion = catalog.getVersion();
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
	private void replayMutationsOnCatalog(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull Transaction transaction,
		@Nonnull Iterator<Mutation> mutationIterator
	) {
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				this.catalog.setVersion(transactionMutation.getCatalogVersion());
				// init mutation counter
				int mutationCount = 0;
				while (mutationCount < transactionMutation.getMutationCount() && mutationIterator.hasNext()) {
					final Mutation mutation = mutationIterator.next();
					log.debug("Processing mutation: {}", mutation);
					mutationCount++;
					if (mutation instanceof EntityUpsertMutation entityUpsertMutation) {
						catalog.applyMutation(new VerifiedEntityUpsertMutation(entityUpsertMutation));
					} else if (mutation instanceof EntityRemoveMutation entityRemoveMutation) {
						catalog.applyMutation(new VerifiedEntityRemoveMutation(entityRemoveMutation));
					} else {
						catalog.applyMutation(mutation);
					}
				}
				// we should have processed all the mutations by now and the mutation count should match
				Assert.isPremiseValid(
					mutationCount == transactionMutation.getMutationCount(),
					"Unexpected transaction `" + transactionMutation.getTransactionId() + "` mutation count! " +
						"Transaction mutation mutation count: " + transactionMutation.getMutationCount() + ", " +
						"actual mutation count: " + mutationCount + "."
				);
			}
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
	private Catalog commitChangesToSharedCatalog(
		@Nonnull TransactionMutation lastTransactionMutation,
		@Nonnull Transaction transaction,
		@Nonnull TransactionTrunkFinalizer transactionHandler
	) {
		return Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				try {
					log.debug("Materializing catalog version: {}", this.lastFinalizedCatalogVersion);
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
	 * Waits until the catalog version in the "live view" reaches the specified version.
	 *
	 * @param lastCatalogVersionInLiveView The catalog version to wait for in the "live view".
	 */
	private void waitUntilLiveVersionReaches(long lastCatalogVersionInLiveView) {
		while (this.liveCatalog.get().getVersion() < lastCatalogVersionInLiveView) {
			// wait until the catalog version is propagated to the "live view"
			Thread.onSpinWait();
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
		@Nullable CompletableFuture<Long> future
	) implements TransactionTask {
	}

	/**
	 * Represents a task for new catalog version propagation to the "life view". I.e. when the newly built catalog
	 * trunk (SNAPSHOT) is propagated to the "live view" of the evitaDB engine.
	 */
	public record UpdatedCatalogTransactionTask(
		@Nonnull Catalog catalog,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable CompletableFuture<Long> future
	) implements TransactionTask {

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
