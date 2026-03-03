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

import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.CommutativeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceAttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ServerModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.RingBuffer.OutsideScopeException;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.cdc.CatalogChangeObserver;
import io.evitadb.core.cdc.ChangeCatalogObserverContract;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.transaction.conflict.AttributeDeltaResolver;
import io.evitadb.core.transaction.conflict.CommutativeConflictResolver;
import io.evitadb.core.transaction.conflict.ConflictRingBuffer;
import io.evitadb.core.transaction.conflict.ConflictRingBuffer.CatalogVersionIndex;
import io.evitadb.core.transaction.conflict.ReferenceAttributeDeltaResolver;
import io.evitadb.core.transaction.conflict.VersionedConflictKey;
import io.evitadb.core.transaction.stage.ConflictResolutionAndWalAppendingTransactionStage;
import io.evitadb.core.transaction.stage.ConflictResolutionAndWalAppendingTransactionStage.ConflictResolutionAndWalAppendingTransactionTask;
import io.evitadb.core.transaction.stage.TransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.mutation.ServerEntityRemoveMutation;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
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
public class TransactionManager implements Closeable {
	/**
	 * Reference to the evitaDB instance this transaction manager belongs to.
	 */
	private final Evita evita;
	/**
	 * Represents reference to the currently active catalog version in the "live view" of the evitaDB engine.
	 */
	protected final AtomicReference<Catalog> livingCatalog;
	/**
	 * Reference to the configuration of the evitaDB engine.
	 */
	private final EvitaConfiguration configuration;
	/**
	 * The executor service used for notifying clients about transaction completion.
	 */
	@Getter private final ObservableExecutorService requestExecutor;
	/**
	 * The executor used for handling transactional tasks.
	 */
	private final ObservableExecutorService transactionalExecutor;
	/**
	 * The maximum time in milliseconds the system will wait for a writing transaction to be accepted.
	 */
	private final long transactionAcceptanceTimeout;
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
	 * Contains the last schema version of the catalog. This is used to estimate the proper catalog schema version
	 * in a particular catalog version.
	 */
	private final AtomicInteger accumulatedCatalogSchemaVersionDelta;
	/**
	 * Contains the last visible schema version of the catalog. This is used to estimate the proper catalog schema version
	 * in a particular catalog version.
	 */
	private final AtomicInteger lastCatalogSchemaVersion;
	/**
	 * Java {@link java.util.concurrent.Flow} implementation that allows to process transactional tasks in
	 * asynchronous reactive manner.
	 */
	private final SubmissionPublisher<ConflictResolutionAndWalAppendingTransactionTask> transactionalPipeline;
	/**
	 * Change observer that is used to notify all registered {@link io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher} about changes in the
	 * catalog.
	 */
	@Getter private final ChangeCatalogObserverContract changeObserver;
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
	 * Delta in catalog schema version that was incorporated into the last finalized catalog and could be deduced from
	 * {@link #accumulatedCatalogSchemaVersionDelta} when this version of catalog becomes visible.
	 */
	private final ConcurrentSkipListSet<FinalizedCatalogVersion> lastFinalizedCatalogVersionSchemaDelta;
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
	 * Queue of mutations that are waiting for their changes to be incorporated into the last finalized catalog.
	 * We use linked list because we expect very short queue most of the time and we want to avoid array resizing,
	 * that is usually empty.
	 */
	private final Deque<ModifyCatalogSchemaMutationWithCatalogVersion> engineMutationsQueue = new LinkedList<>();
	/**
	 * Conflict ring buffer that holds the conflict keys for recent catalog versions.
	 */
	private final ConflictRingBuffer conflictRingBuffer;
	/**
	 * Set of conflict policies that are used in this transaction manager.
	 */
	private final EnumSet<ConflictPolicy> conflictPolicy;
	/**
	 * Indicates whether any of the conflict policies is granular.
	 */
	private final boolean granularConflictPolicy;
	/**
	 * Name of the catalog.
	 */
	private String catalogName;

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
					log.debug("Materializing catalog version: {}", lastTransactionMutation.getVersion());
					return transactionHandler.commitCatalogChanges(
						lastTransactionMutation.getVersion(),
						lastTransactionMutation
					);
				} catch (RuntimeException ex) {
					log.error("Error while committing transaction: " + lastTransactionMutation.getVersion() + ".", ex);
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
			latestCatalog.getLastCatalogVersionInMutationStream() > lastTransaction.getVersion();
	}

	public TransactionManager(
		@Nonnull Catalog catalog,
		@Nonnull Evita evita,
		@Nonnull Scheduler scheduler,
		@Nonnull ObservableExecutorService requestExecutor,
		@Nonnull ObservableExecutorService transactionalExecutor,
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer,
		long catalogVersion
	) {
		this.evita = evita;
		this.configuration = evita.getConfiguration();
		this.conflictPolicy = this.configuration.transaction().conflictPolicy();
		this.granularConflictPolicy = this.conflictPolicy.stream().anyMatch(ConflictPolicy::isGranular);
		this.requestExecutor = requestExecutor;
		this.transactionalExecutor = transactionalExecutor;
		this.transactionalPipeline = createTransactionalPublisher();
		this.newCatalogVersionConsumer = newCatalogVersionConsumer;
		this.transactionAcceptanceTimeout = this.configuration.transaction().waitForTransactionAcceptanceInMillis();
		final ChangeDataCaptureOptions cdcOptions = this.configuration.server().changeDataCapture();
		this.changeObserver = cdcOptions.enabled() ?
			new CatalogChangeObserver(
				cdcOptions,
				requestExecutor,
				scheduler,
				catalog
			) :
			ChangeCatalogObserverContract.NO_OP;

		this.lastFinalizedCatalog = new AtomicReference<>(catalog);
		this.livingCatalog = new AtomicReference<>(catalog);
		this.catalogName = catalog.getName();
		// fetch from the persistence store initially - might be greater than current version
		this.lastAssignedCatalogVersion = new AtomicLong(catalogVersion);
		this.lastCatalogSchemaVersion = new AtomicInteger(catalog.getSchema().version());
		this.accumulatedCatalogSchemaVersionDelta = new AtomicInteger(0);
		this.lastWrittenCatalogVersion = new AtomicLong(this.lastAssignedCatalogVersion.get());
		// this is the catalog version really used (propagated in indexes)
		this.lastFinalizedCatalogVersion = new AtomicLong(catalog.getVersion());
		this.lastFinalizedCatalogVersionSchemaDelta = new ConcurrentSkipListSet<>();

		Assert.isPremiseValid(
			this.lastWrittenCatalogVersion.get() >= this.lastAssignedCatalogVersion.get(),
			"The last finalized catalog version must be greater or equal to last assigned catalog version!"
		);

		this.conflictRingBuffer = new ConflictRingBuffer(
			this.catalogName,
			catalog.getVersion(),
			catalog.getVersion(),
			this.configuration.transaction().conflictRingBufferSize()
		);
		this.walDrainingTask = new DelayedAsyncTask(
			catalog.getName(), "WAL draining task",
			scheduler,
			this::drainWal,
			1000, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Processes the write-ahead log and returns the catalog instance that is the result of the processing.
	 *
	 * @return the catalog instance after processing the write-ahead log
	 */
	@Nonnull
	public Optional<ProcessResult> processEntireWriteAheadLog(
		long nextCatalogVersion,
		@Nonnull LongConsumer progressCallback
	) {
		return processTransactions(
			nextCatalogVersion,
			Long.MAX_VALUE,
			false,
			true, // we should obtain lock here easily, since this is called only on catalog instantiation
			progressCallback
		);
	}

	/**
	 * Commits the transaction to the transactional pipeline.
	 */
	public void commit(
		long sessionCatalogVersion,
		@Nonnull UUID transactionId,
		int catalogSchemaVersionDelta,
		@Nonnull IsolatedWalPersistenceService walPersistenceService,
		@Nonnull CommitProgressRecord commitProgress
	) {
		this.transactionalPipeline.offer(
			new ConflictResolutionAndWalAppendingTransactionTask(
				getCatalogName(),
				sessionCatalogVersion,
				transactionId,
				walPersistenceService.getMutationCount(),
				walPersistenceService.getMutationSizeInBytes(),
				catalogSchemaVersionDelta,
				walPersistenceService.getConflictKeys(),
				walPersistenceService.getWalReference(),
				commitProgress
			),
			(subscriber, task) -> {
				commitProgress.completeExceptionally(
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
		return this.catalogName;
	}

	/**
	 * This method is called when any of the {@link SubmissionPublisher}
	 * gets closed - for example due to the exception in the processing of the transactional task. One of the possible
	 * issues is that the system can't keep up and throws {@link RejectedExecutionException}.
	 *
	 * In such a situation, the submission publisher is automatically closed and needs to be recreated from scratch.
	 * This is design decision form the authors of the {@link java.util.concurrent.Flow} API.
	 */
	public void retryTransactionProcessing(@Nonnull TransactionTask task, @Nonnull Throwable ex) {
		if (task instanceof TrunkIncorporationTransactionTask) {
			this.walDrainingTask.schedule();
		}
	}

	/**
	 * This method registers the number of catalog versions that were dropped due to the processor being overloaded
	 * or the WAL appending failing. We need to lower newly assigned catalog versions so that they take the dropped
	 * versions into account and produce a consistent sequence of catalog versions.
	 */
	public void notifyCatalogVersionDropped(int numberOfDroppedCatalogVersions, int schemaVersionDelta) {
		if (numberOfDroppedCatalogVersions > 0) {
			this.lastAssignedCatalogVersion.addAndGet(-numberOfDroppedCatalogVersions);
			this.accumulatedCatalogSchemaVersionDelta.addAndGet(-schemaVersionDelta);
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
	 * This method estimates the catalog schema version based on the given delta. The catalog schema version cannot be
	 * known upfront at the transaction commit time, because it is not known how parallel transactions queue up. Multiple
	 * transaction may have updated the schema in parallel and the version is dependent on the order of the transactions
	 * in the queue.
	 *
	 * @param delta the delta to add to the last catalog schema version
	 * @return the estimated catalog schema version
	 */
	public int addDeltaAndEstimateCatalogSchemaVersion(int delta) {
		return this.lastCatalogSchemaVersion.get() + this.accumulatedCatalogSchemaVersionDelta.addAndGet(delta);
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
		if (theLastWrittenCatalogVersion < catalogVersion) {
			updateLastWrittenCatalogVersion(catalogVersion);
		}
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
		try {
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
		} finally {
			this.conflictRingBuffer.setEffectiveLastCatalogVersion(catalogVersion);
		}
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
	public void updateLastFinalizedCatalog(
		@Nonnull Catalog lastFinalizedCatalog,
		long lastFinalizedCatalogVersion,
		int incorporatedCatalogSchemaVersionDelta
	) {
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
		this.lastFinalizedCatalogVersionSchemaDelta.add(
			new FinalizedCatalogVersion(lastFinalizedCatalogVersion, incorporatedCatalogSchemaVersionDelta)
		);
	}

	/**
	 * Notifies the system that a catalog is present in the live view.
	 * This method is used to indicate that a catalog is currently available in the live view.
	 */
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog livingCatalog) {
		final Catalog previousLivingCatalog = getLivingCatalog();
		final long catalogVersion = livingCatalog.getVersion();
		if (catalogVersion > 0L) {
			Assert.isPremiseValid(
				previousLivingCatalog.getVersion() < catalogVersion || (previousLivingCatalog == livingCatalog),
				"Catalog versions must be in order! " +
					"Expected " + previousLivingCatalog.getVersion() + ", got " + catalogVersion + "."
			);
			final long theLastFinalizedVersion = getLastFinalizedCatalogVersion();
			Assert.isPremiseValid(
				theLastFinalizedVersion >= catalogVersion,
				"Catalog versions must be in order! " +
					"Expected " + theLastFinalizedVersion + ", got " + catalogVersion + "."
			);
		}

		this.livingCatalog.set(livingCatalog);
		this.catalogName = livingCatalog.getName();

		this.lastCatalogSchemaVersion.updateAndGet(current -> Math.max(current, livingCatalog.getSchema().version()));
		this.lastFinalizedCatalogVersionSchemaDelta.removeIf(
			finalizedCatalogVersion -> {
				if (finalizedCatalogVersion.catalogVersion() <= catalogVersion) {
					// remove finalized catalog version that is older than the current living catalog
					// and update the accumulated schema version delta
					this.accumulatedCatalogSchemaVersionDelta.addAndGet(-finalizedCatalogVersion.incorporatedSchemaVersionDelta());
					return true;
				} else {
					return false;
				}
			}
		);

		if (this.lastFinalizedCatalogVersion.getAndUpdate(current -> Math.max(current, catalogVersion)) <= catalogVersion) {
			this.lastFinalizedCatalog.set(livingCatalog);
		}

		this.changeObserver.notifyCatalogPresentInLiveView(livingCatalog);
	}

	/**
	 * This method identifies concurrent transaction commits based on passed mutation keys.
	 */
	public void identifyConflicts(
		long catalogVersion,
		@Nonnull OffsetDateTime commitTimestamp,
		@Nonnull Set<ConflictKey> conflictKeys
	) {
		try {
			// calculate the rest of the timeout
			final long timeout = this.transactionAcceptanceTimeout - Duration.between(
				OffsetDateTime.now(), commitTimestamp
			).toMillis();
            if (this.conflictResolutionLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                final Catalog theLivingCatalog = getLivingCatalog();
                final long livingCatalogVersion = theLivingCatalog.getVersion();
                final Map<CommutativeConflictKey<?>, CommutativeConflictResolver<?>> aggregates =
                    initializeAggregatesIfNecessary(conflictKeys);

                try {
                    this.conflictRingBuffer.forEachSince(
                        catalogVersion,
                        vck -> examineConflictKey(
                            vck.conflictKey(),
                            conflictKeys,
                            theLivingCatalog,
                            aggregates,
                            vck.version(),
                            livingCatalogVersion
                        )
                    );
                } catch (OutsideScopeException e) {
                    // this means that the conflict ring buffer has already cleared the catalog version
                    // and was able to check only partial set of conflict keys
                    identifyConflictsInOldCommittedTransactions(
                        catalogVersion,
                        conflictKeys,
                        theLivingCatalog,
                        aggregates,
                        e.getEffectiveStart()
                    );
                } finally {
                    int index = 0;
                    for (ConflictKey conflictKey : conflictKeys) {
                        if (conflictKey instanceof CommutativeConflictKey<?> cck && cck.isConstrainedToRange()) {
                            Assert.isPremiseValid(
                                aggregates != null,
                                "Aggregates map must be initialized when commutative conflict keys are present!"
                            );
                            //noinspection unchecked
                            final CommutativeConflictKey<Object> ccko = (CommutativeConflictKey<Object>) cck;
                            // if the commutative conflict key is present in this transaction, we need to
                            //noinspection unchecked
                            final CommutativeConflictResolver<Object> resolver = ofNullable((CommutativeConflictResolver<Object>) aggregates.get(cck))
                                .orElseGet(() -> createCommutativeResolver(ccko, theLivingCatalog));
                            // 1. add the aggregate from previous transactions
                            final Object accumulatedValue = ccko.aggregate(resolver.accumulatedValue(), ccko.deltaValue());
                            // 2. check whether the result is within the allowed range
                            ccko.assertInAllowedRange(this.catalogName, catalogVersion, accumulatedValue);
                        }
                        this.conflictRingBuffer.offer(new VersionedConflictKey(catalogVersion, index++, conflictKey));
                    }
                }
			} else {
				throw new TransactionTimedOutException(
					"Conflict resolution lock timed out! Waited for " + timeout + " ms of maximum waiting time " + this.transactionAcceptanceTimeout + " ms."
				);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError("Conflict resolution lock interrupted!", e);
		} finally {
            if (this.conflictResolutionLock.isHeldByCurrentThread()) {
                this.conflictResolutionLock.unlock();
            }
		}
	}

    /**
     * Initializes and returns a mutable map of commutative conflict keys to their resolvers
     * if at least one {@link ConflictKey} in the given set is an instance of
     * {@link CommutativeConflictKey} and is constrained to a range. Otherwise, returns null.
     *
     * @param conflictKeys the set of conflict keys to evaluate for initialization
     * @return a lazily initialized map if required, otherwise null
     */
    @Nullable
    private static Map<CommutativeConflictKey<?>, CommutativeConflictResolver<?>> initializeAggregatesIfNecessary(
        @Nonnull Set<ConflictKey> conflictKeys
    ) {
        for (ConflictKey conflictKey : conflictKeys) {
            if (conflictKey instanceof CommutativeConflictKey<?> cck && cck.isConstrainedToRange()) {
                return new LazyHashMap<>(32);
            }
        }
        return null;
    }

    /**
	 * Identifies conflicts in old committed transactions within a catalog over a specified range of versions.
	 * This method iterates through all committed live mutations from the given catalog version up to a specified version,
	 * and checks if any of the conflict keys provided are present in the mutations. If a conflict is detected,
	 * it throws a {@link ConflictingCatalogMutationException}.
	 *
	 * @param catalogVersion the starting version of the catalog to check for conflicts in committed transactions
	 * @param conflictKeys a set of conflict keys to check against in the committed transactions
	 * @param until the upper bound versioned conflict key, up to which committed transactions are analyzed
	 */
	private void identifyConflictsInOldCommittedTransactions(
		long catalogVersion,
		@Nonnull Set<ConflictKey> conflictKeys,
        @Nonnull Catalog theLivingCatalog,
        @Nullable Map<CommutativeConflictKey<?>, CommutativeConflictResolver<?>> aggregates,
		@Nonnull CatalogVersionIndex until
	) {
		final ConflictGenerationContext context = new ConflictGenerationContext();
        long livingCatalogVersion = theLivingCatalog.getVersion();
		long processedCatalogVersion = catalogVersion;
		final Iterator<CatalogBoundMutation> mutationIterator = getLivingCatalog()
			.getCommittedLiveMutationStream(catalogVersion, until.catalogVersion())
			.iterator();

		int index = -1;
		while (mutationIterator.hasNext()) {
			final Mutation mutation = mutationIterator.next();
			if (mutation instanceof TransactionMutation tm) {
				processedCatalogVersion = tm.getVersion();
				index = 0;
			} else {
				index++;
			}

			if (until.catalogVersion() == processedCatalogVersion && index == until.index()) {
				// stop at the mutation that is the upper bound
				break;
			}

			final Iterator<ConflictKey> conflictKeyIterator = mutation
				.collectConflictKeys(context, this.conflictPolicy)
				.iterator();
			while (conflictKeyIterator.hasNext()) {
				final ConflictKey conflictKey = conflictKeyIterator.next();
                examineConflictKey(
                    conflictKey, conflictKeys, theLivingCatalog, aggregates,
                    processedCatalogVersion, livingCatalogVersion
                );
            }
		}
	}

    /**
     * Examines the specified conflict key and determines if conflicts exist based on the provided parameters.
     * Handles commutative conflict keys and processes them against the current catalog state.
     *
     * @param conflictKey The conflict key to be examined.
     * @param conflictKeys A set of conflict keys to check against for conflicts.
     * @param theLivingCatalog The current state of the catalog.
     * @param aggregates A map of commutative conflict keys and their corresponding aggregated values.
     *                   Can be null if it makes no sense to accumulate commutative keys.
     * @param processedCatalogVersion The version of the catalog being processed.
     * @param livingCatalogVersion The current version of the living catalog.
     * @throws ConflictingCatalogMutationException if the conflict key is already present in the current transaction.
     */
    private <T> void examineConflictKey(
        @Nonnull ConflictKey conflictKey,
        @Nonnull Set<ConflictKey> conflictKeys,
        @Nonnull Catalog theLivingCatalog,
        @Nullable Map<CommutativeConflictKey<?>, CommutativeConflictResolver<?>> aggregates,
        long processedCatalogVersion,
        long livingCatalogVersion
    ) {
        // accumulate commutative conflict keys later than current living catalog version
        if (conflictKey instanceof CommutativeConflictKey<?> cck) {
            if (aggregates != null && processedCatalogVersion > livingCatalogVersion) {
                aggregates.compute(
                    cck,
                    (key, existingAggregate) -> {
                        if (existingAggregate == null) {
                            return createCommutativeResolver(cck, theLivingCatalog);
                        } else {
                            //noinspection unchecked
                            ((CommutativeConflictResolver<T>)existingAggregate).accumulate((T) cck.deltaValue());
                            return existingAggregate;
                        }
                    }
                );
            }
        } else if (conflictKeys.contains(conflictKey)) {
            // check whether any of the conflict keys is present in the current transaction
            throw new ConflictingCatalogMutationException(
                this.catalogName,
                conflictKey,
                processedCatalogVersion
            );
        }
    }

    /**
     * Creates a commutative conflict resolver for the given conflict key and catalog.
     * This method determines the specific type of the commutative conflict key and
     * creates an appropriate conflict resolver to handle the commutative resolution logic.
     *
     * @param conflictKey the commutative conflict key to resolve, must not be null
     * @param theLivingCatalog the catalog instance used in resolution, must not be null
     * @return a commutative conflict resolver appropriate for the provided key and catalog
     * @throws GenericEvitaInternalError if the conflict key type is not supported
     */
    @Nonnull
    private static <T> CommutativeConflictResolver<T> createCommutativeResolver(
        @Nonnull CommutativeConflictKey<T> conflictKey,
        @Nonnull Catalog theLivingCatalog
    ) {
        if (conflictKey instanceof AttributeDeltaConflictKey attributeDeltaConflictKey) {
            //noinspection unchecked
            return (CommutativeConflictResolver<T>) new AttributeDeltaResolver(
                theLivingCatalog,
                attributeDeltaConflictKey
            );
        } else if (conflictKey instanceof ReferenceAttributeDeltaConflictKey attributeDeltaConflictKey) {
            //noinspection unchecked
            return (CommutativeConflictResolver<T>) new ReferenceAttributeDeltaResolver(
                theLivingCatalog,
                attributeDeltaConflictKey
            );
        } else {
            throw new GenericEvitaInternalError(
                "Unsupported commutative conflict key type: " + conflictKey.getClass().getName() + "!"
            );
        }
    }

    /**
	 * Releases all conflict keys up to the specified catalog version (including).
	 *
	 * @param catalogVersion the catalog version up to which all conflict keys should be released.
	 */
	public void releaseConflictKeys(
		long catalogVersion
	) {
		this.conflictRingBuffer.clearAllUntil(catalogVersion);
	}

	/**
	 * Rolls back and clears all conflict keys in the buffer that have been added
	 * in the specified catalog version or after it.
	 *
	 * @param sinceCatalogVersion the catalog version after which all conflict keys
	 *                            will be rolled back and cleared
	 */
	public void rollbackConflictKeys(long sinceCatalogVersion) {
		this.conflictRingBuffer.clearAllAfter(sinceCatalogVersion);
	}

	/**
	 * This method writes the contents to the WAL and discards the contents of the isolated WAL.
	 *
	 * @param transactionMutation the leading transaction mutation to write to the WAL
	 * @param walReference        the reference to the WAL file
	 * @return the length of the written WAL contents
	 */
	public long appendWalAndDiscard(
		@Nonnull OffsetDateTime commitTimestamp,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull LogRecordReference walReference
	) {
		try {
			// calculate the rest of the timeout
			final long timeout = this.transactionAcceptanceTimeout - Duration.between(
				OffsetDateTime.now(), commitTimestamp
			).toMillis();
			// try to obtain the lock within the timeout
			if (this.walAppendingLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
				final long theLastWrittenCatalogVersion = this.lastWrittenCatalogVersion.get();
				Assert.isPremiseValid(
					theLastWrittenCatalogVersion <= 0 || theLastWrittenCatalogVersion + 1 == transactionMutation.getVersion(),
					"Transaction cannot be written to the WAL out of order. " +
						"Expected version " + (theLastWrittenCatalogVersion + 1) + ", got " + transactionMutation.getVersion() + "."
				);
				return getLivingCatalog()
					.appendWalAndDiscard(
						transactionMutation,
						walReference
					);
			} else {
				throw new TransactionTimedOutException(
					"WAL appending lock timed out! Waited for " + timeout + " ms of maximum waiting time " + this.transactionAcceptanceTimeout + " ms."
				);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
	 * @param waitForLock        Indicates whether to wait for the trunk incorporation lock.
	 * @param progressCallback   A callback to report progress during transaction processing.
	 * @return The processed transaction.
	 */
	@Nonnull
	public Optional<ProcessResult> processTransactions(
		long nextCatalogVersion,
		long timeoutMs,
		boolean alive,
		boolean waitForLock,
		@Nonnull LongConsumer progressCallback
	) {
		try {
			final boolean locked;
			if (waitForLock) {
				this.trunkIncorporationLock.lock();
				locked = true;
			} else {
				locked = this.trunkIncorporationLock.tryLock(0, TimeUnit.MILLISECONDS);
			}
			if (locked) {
				long firstTransactionId = -1;
				TransactionMutation lastTransactionMutation;
				Transaction lastTransaction = null;
				final Catalog newCatalog;

				int atomicMutationCount = 0;
				int localMutationCount = 0;

				final ArrayList<OffsetDateTime> processed = new ArrayList<>(64);
				final long lastFinalizedVersion = getLastFinalizedCatalogVersion();
				final Catalog latestCatalog = getLastFinalizedCatalog();

				Stream<CatalogBoundMutation> committedMutationStream = null;
				try {
					// prepare finalizer that doesn't finish the catalog automatically but on demand
					final TransactionTrunkFinalizer transactionHandler = new TransactionTrunkFinalizer(latestCatalog);
					// read the mutations from the WAL since the last finalized version
					// (but at least 2 - this is the first processable transaction number after going live)
					// if the transaction failed we need to replay it again
					final long readFromVersion = Math.max(lastFinalizedVersion + 1, 2);
					if (alive) {
						committedMutationStream = latestCatalog.getCommittedLiveMutationStream(readFromVersion, this.lastWrittenCatalogVersion.get());
					} else {
						committedMutationStream = latestCatalog.getCommittedMutationStream(readFromVersion);
					}
					final Iterator<CatalogBoundMutation> mutationIterator = committedMutationStream.iterator();
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
							firstTransactionId = firstTransactionId == -1 ? ((TransactionMutation) leadingMutation).getVersion() : firstTransactionId;

							final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
							long finalNextExpectedCatalogVersion = nextExpectedCatalogVersion;
							Assert.isPremiseValid(
								transactionMutation.getVersion() == nextExpectedCatalogVersion,
								() -> new GenericEvitaInternalError(
									"Unexpected catalog version! " +
										"Transaction mutation catalog version: " + transactionMutation.getVersion() + ", " +
										"last finalized catalog version: " + finalNextExpectedCatalogVersion + "."
								)

							);

							log.debug("Starting transaction: {}", transactionMutation);

							// prepare "replay" transaction
							lastTransaction = createTransaction(transactionMutation, lastTransaction, transactionHandler);

							// and replay all the mutations of the entire transaction from the WAL
							// this cannot be interrupted even if the timeout is exceeded and must be fully applied
							final int[] processedCounts = replayMutationsOnCatalog(
								this.evita,
								transactionMutation,
								lastTransaction,
								mutationIterator
							);
							atomicMutationCount += processedCounts[0] + 1;
							localMutationCount += processedCounts[1];

							// this is the last mutation in the transaction, close the replay mutation now
							lastTransaction.close();
							lastTransactionMutation = transactionMutation;

							processed.add(transactionMutation.getCommitTimestamp());
							nextExpectedCatalogVersion++;

							progressCallback.accept(lastTransactionMutation.getVersion());
							log.debug("Processed transaction: {}", lastTransactionMutation);
						} while (
							// there is something to process
							mutationIterator.hasNext() &&
								(
									// we haven't reached expected version
									lastTransactionMutation.getVersion() < nextCatalogVersion ||
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
						lastTransactionMutation.getVersion(),
						newCatalog.getSchema().version() - this.lastCatalogSchemaVersion.get()
					);

					log.debug("Finalizing catalog: {}", lastTransactionMutation.getVersion());

				} catch (RuntimeException ex) {
					// we need to forget about the data written to disk, but not yet propagated to indexes (volatile data)
					latestCatalog.forgetVolatileData();
					final Catalog catalog = this.lastFinalizedCatalog.get();
					this.changeObserver.forgetMutationsAfter(catalog, catalog.getVersion());

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
				return empty();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
				while (
					!this.engineMutationsQueue.isEmpty() &&
						this.engineMutationsQueue.peek().catalogVersion() <= newCatalogVersion.getVersion()
				) {
					// apply the mutation to the living catalog
					final ModifyCatalogSchemaMutationWithCatalogVersion mcsmwcv = Objects.requireNonNull(
						this.engineMutationsQueue.poll()
					);
					this.evita.applyMutation(
						new ServerModifyCatalogSchemaMutation(
							mcsmwcv.catalogVersion(),
							mcsmwcv.schemaVersion(),
							mcsmwcv.engineMutation()
						)
					);
				}
			} else {
				throw new TransactionTimedOutException("Catalog propagation lock timed out!");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
	 * Registers an observer to capture changes based on the provided request.
	 *
	 * @param request the request containing the criteria and configuration for capturing changes
	 * @return an instance of ChangeCapturePublisher that allows the caller to manage the registered observer
	 */
	@Nonnull
	public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
		return this.changeObserver.registerObserver(request);
	}

	@Override
	public void close() throws IOException {
		IOUtils.closeQuietly(
			this.transactionalPipeline::close,
			this.changeObserver::close,
			this.walDrainingTask::close
		);
		this.livingCatalog.set(null);
		this.lastFinalizedCatalog.set(null);
	}

	/**
	 * Returns the last assigned catalog version to a transaction.
	 *
	 * @return the last assigned catalog version
	 */
	public long getLastAssignedCatalogVersion() {
		return this.lastAssignedCatalogVersion.get();
	}

	/**
	 * Retrieves the set of conflict policies associated with the transaction configuration.
	 *
	 * @return a non-null set of ConflictPolicy objects representing the conflict policies.
	 */
	@Nonnull
	public Set<ConflictPolicy> getConflictPolicy() {
		return this.conflictPolicy;
	}

	/**
	 * Determines if a granular (sub-entity level) conflict policy is used.
	 *
	 * @return true if a granular conflict policy is enabled; false otherwise
	 */
	public boolean hasGranularConflictPolicy() {
		return this.granularConflictPolicy;
	}

	/**
	 * Emits observability events by delegating to internal components.
	 * This method triggers the emission of observability events from
	 * the conflictRingBuffer and changeObserver components.
	 */
	public void emitObservabilityEvents() {
		this.conflictRingBuffer.emitObservabilityEvents();
		this.changeObserver.emitObservabilityEvents();
	}

	/**
	 * Sends the task simulating the WAL stage finalization with tasks that drains entire contents of the WAL in
	 * the trunk incorporation stage. This should handle the situation when last transaction was not processed due
	 * to queues being full. When no other transaction comes the WAL will forever contain more records than are
	 * incorporated in the catalog.
	 */
	private long drainWal() {
		try {
			this.processTransactions(
				this.lastWrittenCatalogVersion.get(),
				this.configuration.transaction().flushFrequencyInMillis(),
				true,
				false, // we should not wait for the lock here - if its already running it will process the transactions
				Functions.noOpLongConsumer()
			);
		} catch (TransactionTimedOutException ex) {
			// reschedule again
			return 0;
		}
		// pause the task
		return -1;
	}

	/**
	 * Method creates and returns the transaction pipeline. The transaction processing consists of 3 stages:
	 *
	 * - conflict resolution (and catalog version sequence number assignment)
	 * - WAL appending (writing {@link IsolatedWalPersistenceService} to the shared catalog WAL)
	 * - trunk incorporation (applying transaction from shared WAL in order to the shared catalog view) plus
	 * catalog snapshot propagation (propagating new catalog version to the "live view" of the evitaDB engine)
	 *
	 * @return the submission publisher for conflict resolution transaction tasks
	 */
	@Nonnull
	private SubmissionPublisher<ConflictResolutionAndWalAppendingTransactionTask> createTransactionalPublisher() {
		final int maxBufferCapacity = this.configuration.server().transactionThreadPool().queueSize();
		final Executor unrejectableExecutor = ProgressingFuture.unrejectableExecutor(this.transactionalExecutor);

		final SubmissionPublisher<ConflictResolutionAndWalAppendingTransactionTask> txPublisher = new SubmissionPublisher<>(
			unrejectableExecutor, maxBufferCapacity
		);
		final ConflictResolutionAndWalAppendingTransactionStage stage1 = new ConflictResolutionAndWalAppendingTransactionStage(
			unrejectableExecutor, maxBufferCapacity, this,
			// do nothing on error
			(transactionTask, throwable) -> {
			}
		);
		final TrunkIncorporationTransactionStage stage2 = new TrunkIncorporationTransactionStage(
			this,
			this.configuration.transaction().flushFrequencyInMillis(),
			this::retryTransactionProcessing
		);

		txPublisher.subscribe(stage1);
		stage1.subscribe(stage2);
		return txPublisher;
	}

	/**
	 * Replays mutations in the given transaction on the current catalog.
	 *
	 * @param transactionMutation The transaction mutation containing the catalog version and mutation details.
	 * @param transaction         The transaction object to execute the mutations in.
	 * @param mutationIterator    The iterator containing the mutations to replay.
	 */
	private int[] replayMutationsOnCatalog(
		@Nonnull Evita evita,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull Transaction transaction,
		@Nonnull Iterator<CatalogBoundMutation> mutationIterator
	) {
		return Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				final Catalog lastFinalizedCatalog = getLastFinalizedCatalog();
				final long nextCatalogVersion = transactionMutation.getVersion();
				lastFinalizedCatalog.setVersion(nextCatalogVersion);
				this.changeObserver.processMutation(transactionMutation);
				// init mutation counter
				int atomicMutationCount = 0;
				int localMutationCount = 0;
				CompositeObjectArray<LocalCatalogSchemaMutation> schemaMutations = null;
				while (atomicMutationCount < transactionMutation.getMutationCount() && mutationIterator.hasNext()) {
					final CatalogBoundMutation mutation = mutationIterator.next();
					log.debug("Processing mutation: {}", mutation);
					atomicMutationCount++;
					if (mutation instanceof EntityUpsertMutation entityUpsertMutation) {
						lastFinalizedCatalog.applyMutation(
							evita,
							new ServerEntityUpsertMutation(
								entityUpsertMutation,
								EnumSet.allOf(ImplicitMutationBehavior.class),
								false, false
							)
						);
						localMutationCount += entityUpsertMutation.getLocalMutations().size();
					} else if (mutation instanceof EntityRemoveMutation entityRemoveMutation) {
						lastFinalizedCatalog.applyMutation(
							evita,
							new ServerEntityRemoveMutation(
								entityRemoveMutation, false, false
							)
						);
						localMutationCount += entityRemoveMutation.getLocalMutations().size();
					} else if (mutation instanceof LocalCatalogSchemaMutation lcsm) {
						lastFinalizedCatalog.updateSchema(evita, null, lcsm);
						schemaMutations = schemaMutations == null ? new CompositeObjectArray<>(LocalCatalogSchemaMutation.class) : schemaMutations;
						schemaMutations.add(lcsm);
						localMutationCount++;
					} else {
						throw new GenericEvitaInternalError(
							"Unsupported mutation type: " + mutation.getClass() + "!"
						);
					}

					this.changeObserver.processMutation(mutation);
				}
				// we should have processed all the mutations by now and the mutation count should match
				Assert.isPremiseValid(
					atomicMutationCount == transactionMutation.getMutationCount(),
					"Unexpected transaction `" + transactionMutation.getTransactionId() + "` mutation count! " +
						"Transaction mutation mutation count: " + transactionMutation.getMutationCount() + ", " +
						"actual mutation count: " + atomicMutationCount + "."
				);
				if (schemaMutations != null) {
					final SealedCatalogSchema actualSchema = lastFinalizedCatalog.getSchema();
					this.engineMutationsQueue.add(
						new ModifyCatalogSchemaMutationWithCatalogVersion(
							new ModifyCatalogSchemaMutation(
								actualSchema.getName(),
								null,
								schemaMutations.toArray()
							),
							nextCatalogVersion,
							actualSchema.version()
						)
					);
				}
				return new int[]{atomicMutationCount, localMutationCount};
			}
		);
	}

	/**
	 * Result of the {@link #processTransactions(long, long, boolean, boolean, LongConsumer)} method.
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

	/**
	 * Represents a finalized version of a catalog with its associated version number
	 * and schema version delta.
	 *
	 * This record is immutable and encapsulates the details of a catalog's finalized
	 * state, including the catalog version and the difference in schema versions.
	 *
	 * @param catalogVersion                 The version number of the catalog.
	 * @param incorporatedSchemaVersionDelta The difference or delta in the schema version.
	 */
	record FinalizedCatalogVersion(
		long catalogVersion,
		int incorporatedSchemaVersionDelta
	) implements Comparable<FinalizedCatalogVersion> {

		@Override
		public int compareTo(FinalizedCatalogVersion other) {
			return Long.compare(this.catalogVersion, other.catalogVersion);
		}
	}

	/**
	 * Internal record that keeps pairs of engine mutation and the catalog version it is visible in. This tuple is
	 * propagated to engine WAL when the catalog version is incorporated in the trunk.
	 * @param engineMutation the engine mutation to propagate
	 * @param catalogVersion the catalog version the mutation is visible in
	 * @param schemaVersion the schema version which contains the altered schema
	 */
	private record ModifyCatalogSchemaMutationWithCatalogVersion(
		@Nonnull ModifyCatalogSchemaMutation engineMutation,
		long catalogVersion,
		int schemaVersion
	) {}

}
