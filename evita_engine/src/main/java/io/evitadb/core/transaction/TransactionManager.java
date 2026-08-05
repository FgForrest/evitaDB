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

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.CommutativeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.EffectiveConflictResolutionResolver;
import io.evitadb.api.requestResponse.mutation.conflict.IncomingConflictScope;
import io.evitadb.api.requestResponse.mutation.conflict.ResolvedConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceAttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ServerModifyCatalogSchemaMutation;
import io.evitadb.api.statistics.ActivityStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
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
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException.WalKind;
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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

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
	 * Number of busy-spin attempts in
	 * {@link #waitUntilVersionReaches(LongSupplier, long, long, String)} before the wait falls back
	 * to parking. Catalog-version propagation to the live view normally lands within microseconds,
	 * so the spin window keeps the hot path latency-free while a genuine stall stops burning a full
	 * core.
	 */
	private static final int SPIN_ATTEMPTS_BEFORE_PARK = 4_096;
	/**
	 * Interval in nanoseconds the bounded wait parks for between live-version checks once the spin
	 * window of {@link #SPIN_ATTEMPTS_BEFORE_PARK} attempts is exhausted.
	 */
	private static final long PARK_INTERVAL_NANOS = 100_000L;
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
	 *
	 * "Written" means the bytes were handed to the WAL, **not** that they reached the device - see
	 * {@link #lastDurableCatalogVersion} for that. The append sequence check and every rollback /
	 * diagnostic path want this counter, because they reason about what the WAL file already contains.
	 */
	private final AtomicLong lastWrittenCatalogVersion;
	/**
	 * Contains the last catalog version whose WAL bytes are durable on the device.
	 *
	 * Trails {@link #lastWrittenCatalogVersion} by however many transactions the appender managed to write
	 * while a force was in flight. Anything that would make a transaction irreversible to the outside world
	 * must be gated on this counter rather than on `lastWritten`: acknowledging the client, and - more
	 * subtly - letting trunk incorporation checkpoint the version into the data files, because a bootstrap
	 * record pointing past the durable end of the WAL would come back after a crash as a catalog whose WAL
	 * is missing its own head.
	 */
	private final AtomicLong lastDurableCatalogVersion;
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
	 * Periodic watchdog that fails entries in {@link #pendingCommitProgressRegistry} whose progress
	 * has been pending for longer than the worst-case pipeline latency. Acts as the last line of
	 * defence against dangling commit progress records during live operation — the normal
	 * completion path is handled by the stages themselves; this task is only triggered when a
	 * record is somehow dropped on the floor (e.g. an executor drained an async completion task
	 * before it could run, or an unhandled exception skipped a completion path).
	 */
	private final DelayedAsyncTask pendingProgressSweepTask;
	/**
	 * Lock used for conflict resolution.
	 */
	private final ReentrantLock conflictResolutionLock = new ReentrantLock(true);
	/**
	 * Lock used for appending to WAL.
	 *
	 * Do not read its fair-lock shape as evidence that appends contend: they cannot. The appending stage is
	 * a `Flow` subscriber that requests one task at a time and `SubmissionPublisher` delivers to a
	 * subscriber serially, so there is only ever one thread appending. What this lock still buys is the
	 * acceptance-timeout gate in {@link #appendWalAndDiscard} - a transaction that has already spent its
	 * budget queuing gives up here instead of joining the back of the line.
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
	 * Watchdog registry that tracks in-flight {@link CommitProgressRecord}s by assigned catalog version
	 * and fails any record that the transaction pipeline drops on the floor once the catalog advances
	 * past its version. Exposed so the stages can register their records and the propagation path can
	 * trigger the sweep.
	 */
	@Getter private final PendingCommitProgressRegistry pendingCommitProgressRegistry = new PendingCommitProgressRegistry();
	/**
	 * Set once a trunk-incorporation flush or merge has failed, which suspends all further transaction processing for
	 * this catalog; `null` while the catalog is healthy.
	 *
	 * A failed flush leaves the persisted page baselines describing a write that never landed. A retry against those
	 * baselines is what turns a transient I/O failure into a corrupt catalog, so once one has failed no further
	 * transaction may be processed until an operator reloads the catalog. Reads are deliberately unaffected: the
	 * in-memory tree is correct — only the persisted state and the baselines lie — so readers keep being served.
	 */
	private final AtomicReference<CatalogSuspension> suspension = new AtomicReference<>();
	/**
	 * Conflict ring buffer that holds the conflict keys for recent catalog versions.
	 */
	private final ConflictRingBuffer conflictRingBuffer;
	/**
	 * Effective conflict resolution that is used in this transaction manager.
	 */
	private final ConflictResolution conflictResolution;
	/**
	 * Counters and short-window rates behind {@link CatalogStatisticsComponent#ACTIVITY}, sampled at the moment a
	 * transaction's bytes reach the write-ahead log.
	 *
	 * This is the right home for them precisely because this instance is *not* recreated per catalog version - see
	 * {@link #lastFinalizedCatalog} - so the counters survive every generation switch a write load produces. They are
	 * process-scoped all the same: a catalog reopened after a restart starts from zero, which is what
	 * {@link #countingSince} records.
	 */
	private final AtomicReference<ActivityAccumulation> activity = new AtomicReference<>(ActivityAccumulation.NONE);
	/**
	 * Transactions discarded at session close because the session was marked rollback-only. Counted separately from
	 * {@link #activity} because they never reach the pipeline at all - the count arrives from
	 * {@link io.evitadb.core.session.SessionRegistry}, not from any commit stage.
	 */
	private final AtomicLong transactionsRolledBack = new AtomicLong();
	/**
	 * Transactions rejected by conflict resolution. Counted separately from {@link #activity} for the same reason:
	 * a conflicted transaction never gets as far as the write-ahead log, so it has no sample to contribute.
	 */
	private final AtomicLong transactionsConflicted = new AtomicLong();
	/**
	 * The instant the counters above were zeroed, which is when this manager - and with it the catalog it serves - was
	 * created. Reported alongside the counters so a client can tell "since the catalog was opened" from "ever".
	 */
	private final OffsetDateTime countingSince = OffsetDateTime.now();
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
		this.conflictResolution = this.configuration.transaction().conflictPolicy();
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

		// The WAL is the source of truth for "what catalog versions have been written". The persisted
		// catalog header (catalogVersion) only tracks the last *finalized* version. If the server
		// crashed after a transaction was appended to the WAL but before the trunk-incorporation
		// stage could persist the new header, the WAL will be ahead. In that case we must seed
		// lastAssigned / lastWritten from the WAL — otherwise processEntireWriteAheadLog() advances
		// only lastFinalized, leaving lastAssigned / lastWritten stuck at the header version, and the
		// very next user transaction reserves a version that is already durable in the WAL, tripping
		// the "Invalid catalog version / expected N+1, got N" assertion in CurrentMutationLogFile.
		final long walLastWrittenVersion = catalog.getLastCatalogVersionInMutationStream();
		final long walFirstWrittenVersion = catalog.getFirstCatalogVersionInMutationStream();
		final long bootstrapAssignedVersion = Math.max(catalogVersion, walLastWrittenVersion);

		this.lastAssignedCatalogVersion = new AtomicLong(bootstrapAssignedVersion);
		this.lastCatalogSchemaVersion = new AtomicInteger(catalog.getSchema().version());
		this.accumulatedCatalogSchemaVersionDelta = new AtomicInteger(0);
		this.lastWrittenCatalogVersion = new AtomicLong(bootstrapAssignedVersion);
		// whatever the WAL already contains at bootstrap is durable by definition - it survived a restart
		this.lastDurableCatalogVersion = new AtomicLong(bootstrapAssignedVersion);
		// this is the catalog version really used (propagated in indexes) - WAL replay will advance
		// this to match lastWritten before any new transactions can be accepted
		this.lastFinalizedCatalogVersion = new AtomicLong(catalog.getVersion());
		this.lastFinalizedCatalogVersionSchemaDelta = new ConcurrentSkipListSet<>();

		Assert.isPremiseValid(
			this.lastWrittenCatalogVersion.get() >= this.lastAssignedCatalogVersion.get(),
			"The last written catalog version must be greater or equal to last assigned catalog version!"
		);

		// baseline INFO log - always emitted so operators have an anchor point for "normal"
		// bootstrap state; any subsequent runtime divergence can be correlated against this line
		log.info(
			"TransactionManager bootstrapping catalog `{}`: catalogVersion={}, " +
				"walFirstVersionInCurrentFile={}, walLastWrittenVersion={}, " +
				"seededLastAssigned={}, catalogSchemaVersion={}.",
			this.catalogName,
			catalogVersion,
			walFirstWrittenVersion,
			walLastWrittenVersion,
			bootstrapAssignedVersion,
			this.lastCatalogSchemaVersion.get()
		);

		// highlight the "WAL ahead of header" case so the replay/seeding is visible in ops logs
		if (walLastWrittenVersion > catalogVersion) {
			log.warn(
				"Catalog `{}` header version {} is behind the WAL (last written version {}). " +
					"{} transaction(s) will be replayed and the assigned/written counters have " +
					"been seeded from the WAL to avoid re-using versions already persisted there.",
				this.catalogName,
				catalogVersion,
				walLastWrittenVersion,
				walLastWrittenVersion - catalogVersion
			);
		}

		// sanity-check the bootstrap catalog version against the WAL: the catalog header
		// should never claim a version higher than the last TransactionMutation actually
		// persisted in the WAL - that would mean the WAL is truncated / lost / replaced
		// under a materialized catalog and downstream recovery would silently skip
		// transactions. The reverse (WAL ahead of the catalog version) is expected -
		// those transactions are replayed by processEntireWriteAheadLog().
		if (walLastWrittenVersion > 0L && catalogVersion > walLastWrittenVersion) {
			log.error(
				"Catalog `{}` is being bootstrapped with catalog version {} which is " +
					"ahead of the last version {} written to the Write-Ahead Log " +
					"(first version in current WAL file: {}). " +
					"This indicates a WAL/bootstrap-record inconsistency - transactions " +
					"between {} and {} will be missing from the mutation stream and " +
					"cannot be replayed. Check bootstrap file integrity and WAL retention.",
				this.catalogName,
				catalogVersion,
				walLastWrittenVersion,
				walFirstWrittenVersion,
				walLastWrittenVersion,
				catalogVersion
			);
		}

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
		// watchdog runs at half the transaction-acceptance timeout so a truly dangling record gets
		// flagged within one or two ticks of crossing the age threshold
		final long sweepIntervalMs = Math.max(5_000L, this.transactionAcceptanceTimeout / 2);
		this.pendingProgressSweepTask = new DelayedAsyncTask(
			catalog.getName(), "Pending commit progress sweep task",
			scheduler,
			this::sweepDanglingCommitProgress,
			sweepIntervalMs, TimeUnit.MILLISECONDS
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
		Assert.isPremiseValid(
			this.suspension.get() == null,
			"Cannot process the write-ahead log of a suspended catalog."
		);
		return processTransactions(
			nextCatalogVersion,
			Long.MAX_VALUE,
			false,
			true, // we should obtain lock here easily, since this is called only on catalog instantiation
			progressCallback
		);
	}

	/**
	 * Suspends all further transaction processing for this catalog after a trunk-incorporation flush or merge failed,
	 * and fails every commit already parked on the pipeline so no client waits forever. Only the FIRST failure is kept:
	 * it is the one that describes what actually broke.
	 *
	 * Deliberately does NOT stop readers. The in-memory catalog is still correct — it is the persisted state and the
	 * page baselines that no longer agree — so the safe move is to stop writing, not to stop serving.
	 *
	 * @param cause the flush/merge failure that forced the suspension
	 * @param durable whether the failed version had already reached disk when the failure struck
	 * @param failedCatalogVersion the catalog version whose incorporation failed
	 */
	public void suspend(@Nonnull Throwable cause, boolean durable, long failedCatalogVersion) {
		final long servingCatalogVersion = getLastFinalizedCatalogVersion();
		final CatalogSuspension theSuspension = new CatalogSuspension(
			cause, durable, failedCatalogVersion, servingCatalogVersion
		);
		if (this.suspension.compareAndSet(null, theSuspension)) {
			if (durable) {
				// the version reached disk but its merge into the live catalog failed: the bytes on disk describe a
				// state whose validation did not pass, and a deterministic failure will re-fail on replay, so a plain
				// reload may not be enough
				log.error(
					"Catalog `{}` SUSPENDED after version {} failed to be incorporated AFTER it reached disk (serving " +
						"version {}). Reads continue to be served from memory, writes are refused. Disk holds a SUSPECT " +
						"version {} - verify it before reloading; a restore may be required.",
					this.catalogName, failedCatalogVersion, servingCatalogVersion, failedCatalogVersion, cause
				);
			} else {
				log.error(
					"Catalog `{}` SUSPENDED after version {} failed to be written (serving version {}). Reads continue " +
						"to be served from memory, writes are refused. Disk is intact at version {} - reloading the " +
						"catalog replays the transaction from the WAL.",
					this.catalogName, failedCatalogVersion, servingCatalogVersion, servingCatalogVersion, cause
				);
			}
		}
		// whether or not we won the race, no parked client may be left waiting on a pipeline that will never run again
		this.pendingCommitProgressRegistry.failAllPending(
			"the catalog has been suspended after a failed transaction incorporation"
		);
	}

	/**
	 * Returns the suspension that stopped this catalog's transaction processing, if any.
	 *
	 * @return the suspension, or empty while the catalog is healthy
	 */
	@Nonnull
	public Optional<CatalogSuspension> getSuspension() {
		return Optional.ofNullable(this.suspension.get());
	}

	/**
	 * Tells whether the given catalog version had already reached disk — i.e. whether a failure struck AFTER the write
	 * was durable (during the merge into the live catalog) or BEFORE it (during the write itself). The two are not
	 * symmetric and the operator needs to tell them apart: a pre-durability failure leaves disk at the previous version
	 * and reloading simply replays the transaction, whereas a post-durability failure leaves disk holding a version
	 * whose incorporation did not pass, which a reload would land straight back on.
	 *
	 * @param catalogVersion the version whose incorporation failed
	 * @return true when the version is already on disk
	 */
	private boolean isVersionPersisted(long catalogVersion) {
		return getLastFinalizedCatalog().getLastPersistedCatalogVersion() >= catalogVersion;
	}

	/**
	 * Describes why a catalog stopped processing transactions, and what an operator is left holding.
	 *
	 * @param cause                the flush/merge failure that forced the suspension
	 * @param durable              whether {@link #failedCatalogVersion} had already reached disk when it failed; when
	 *                             true the disk holds a SUSPECT version, when false the disk is intact at
	 *                             {@link #servingCatalogVersion}
	 * @param failedCatalogVersion the catalog version whose incorporation failed
	 * @param servingCatalogVersion the version readers continue to be served from memory
	 */
	public record CatalogSuspension(
		@Nonnull Throwable cause,
		boolean durable,
		long failedCatalogVersion,
		long servingCatalogVersion
	) {
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
		// a suspended catalog will never incorporate another transaction, so accepting this one would park the client
		// on a pipeline that cannot run; refuse it here, at the point of acceptance, exactly as an overloaded queue does
		final CatalogSuspension theSuspension = this.suspension.get();
		if (theSuspension != null) {
			commitProgress.completeExceptionally(
				new TransactionException(
					"Catalog `" + this.catalogName + "` is suspended after a failed transaction incorporation at " +
						"version " + theSuspension.failedCatalogVersion() + " and accepts no further writes until it " +
						"is reloaded. Reads are unaffected.",
					theSuspension.cause()
				)
			);
			return;
		}
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
			// an externally advanced version (e.g. a catalog rename) is already persisted by whoever
			// advanced it - there is no pending force to wait for, so durability tracks it immediately
			updateLastDurableCatalogVersion(catalogVersion);
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
	 * Returns the last catalog version whose WAL bytes are durable on the device.
	 *
	 * @return the last durable catalog version
	 */
	public long getLastDurableCatalogVersion() {
		return this.lastDurableCatalogVersion.get();
	}

	/**
	 * Publishes the fact that every catalog version up to (and including) the given one is durable.
	 *
	 * Monotonic by construction: the value must have been sampled before the force that covered it, and
	 * forces are issued in append order, so a later call can never carry a lower version. It is asserted
	 * rather than silently clamped, because a regression here would mean the durability handshake itself
	 * is broken and the difference is not something to paper over.
	 *
	 * @param catalogVersion the last catalog version made durable
	 */
	public void updateLastDurableCatalogVersion(long catalogVersion) {
		final long previous = this.lastDurableCatalogVersion.getAndSet(catalogVersion);
		Assert.isPremiseValid(
			previous <= catalogVersion,
			"Durable catalog version must never go backwards! " +
				"Was " + previous + ", got " + catalogVersion + "."
		);
		Assert.isPremiseValid(
			catalogVersion <= getLastWrittenCatalogVersion(),
			"Durable catalog version must never outrun the written one! " +
				"Written " + getLastWrittenCatalogVersion() + ", got " + catalogVersion + "."
		);
	}

	/**
	 * Makes every WAL append issued so far durable. Callers must sample the version they intend to declare
	 * durable **before** calling this - see {@link Catalog#syncWal()}.
	 */
	public void syncWal() {
		getLivingCatalog().syncWal();
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

		// invariant: lastFinalized must never outrun lastWritten. If it does, the TM's bookkeeping
		// has drifted away from the WAL — typically because lastWritten was seeded only from the
		// persisted catalog header at bootstrap, and WAL replay advanced lastFinalized without
		// advancing lastAssigned / lastWritten. Emit a loud one-liner so the next occurrence is
		// diagnosable without having to reconstruct the state from a pile of surrounding logs.
		final long theLastWrittenCatalogVersion = getLastWrittenCatalogVersion();
		if (theLastWrittenCatalogVersion < lastFinalizedCatalogVersion) {
			log.error(
				"Catalog `{}` version bookkeeping invariant violated: lastFinalized advanced to {} " +
					"while lastWritten is still {} (lastAssigned={}). Any subsequent user transaction " +
					"will reserve a catalog version already durable in the WAL and fail with " +
					"CatalogWriteAheadLastTransactionMismatchException. This typically means " +
					"lastWritten/lastAssigned were not seeded from the WAL at bootstrap or a " +
					"replay path updated lastFinalized without keeping the other counters in sync.",
				this.catalogName,
				lastFinalizedCatalogVersion,
				theLastWrittenCatalogVersion,
				getLastAssignedCatalogVersion()
			);
		}
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
	 *
	 * Two transactions are successors when the incoming transaction's snapshot version is greater than or equal
	 * to the committed transaction's assigned catalog version — only then the incoming transaction could see
	 * the committed changes. The examination window therefore covers every conflict key committed with a catalog
	 * version in range `(sessionCatalogVersion, lastWrittenCatalogVersion]`: the ring buffer scan starts at
	 * `sessionCatalogVersion + 1` and the WAL fallback stream contract starts at the very same version
	 * (see {@link Catalog#getCommittedLiveMutationStream(long, long)}).
	 *
	 * When no conflict is found, the incoming transaction's own keys are registered in the ring buffer under
	 * `reservedCatalogVersion` — the catalog version this transaction is assigned right after this check — so
	 * that later transactions with older snapshots compare against this transaction's commit version, never its
	 * snapshot version. Keys of a rejected transaction are not registered at all; any keys registered before
	 * a range-constraint violation are removed by {@link #rollbackConflictKeys(long)} driven by the stage.
	 *
	 * @param sessionCatalogVersion  the catalog version the committing transaction started with (its snapshot)
	 * @param reservedCatalogVersion the catalog version this transaction will be assigned when it is accepted
	 * @param commitTimestamp        the timestamp the commit entered the pipeline (for timeout accounting)
	 * @param conflictKeys           the conflict keys produced by the committing transaction
	 */
	public void identifyConflicts(
		long sessionCatalogVersion,
		long reservedCatalogVersion,
		@Nonnull OffsetDateTime commitTimestamp,
		@Nonnull Set<ConflictKey> conflictKeys
	) {
		try {
			// remaining acceptance-timeout budget after the time already spent queuing; commitTimestamp is in
			// the past, so elapsed = now - commitTimestamp and the budget shrinks as queue delay grows, clamped
			// at zero so an over-budget transaction does not wait (never a negative tryLock argument)
			final long elapsedMillis = Duration.between(commitTimestamp, OffsetDateTime.now()).toMillis();
			final long timeout = Math.max(0L, this.transactionAcceptanceTimeout - elapsedMillis);
			if (this.conflictResolutionLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
				// the ring buffer requires monotonically increasing versions and the rollback logic relies
				// on the registered version being the one the single-threaded conflict-resolution stage
				// assigns right after this check succeeds
				final long theLastAssignedCatalogVersion = getLastAssignedCatalogVersion();
				Assert.isPremiseValid(
					reservedCatalogVersion == theLastAssignedCatalogVersion + 1,
					"Reserved catalog version " + reservedCatalogVersion + " must directly follow " +
						"the last assigned catalog version " + theLastAssignedCatalogVersion + "!"
				);
				final Catalog theLivingCatalog = getLivingCatalog();
				final long livingCatalogVersion = theLivingCatalog.getVersion();
				final Map<Object, CommutativeConflictResolver<?>> aggregates =
					initializeAggregatesIfNecessary(conflictKeys);
				// pre-index the incoming transaction's conflict keys once so every committed key can be
				// tested by containment (ancestry chain) rather than raw equality
				final IncomingConflictScope incomingScope = IncomingConflictScope.of(conflictKeys);

				try {
					// keys registered at exactly `sessionCatalogVersion` belong to a predecessor whose
					// changes the snapshot already saw, so the scan starts one version above it
					this.conflictRingBuffer.forEachSince(
						sessionCatalogVersion + 1,
						vck -> examineConflictKey(
							vck.conflictKey(),
							incomingScope,
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
						sessionCatalogVersion,
						incomingScope,
						theLivingCatalog,
						aggregates,
						e.getEffectiveStart()
					);
				}

				// no overlapping committed key was found — validate range-constrained commutative keys
				// against the accumulated in-flight deltas and register the incoming transaction's keys
				// under its reserved commit version (a conflicting transaction never registers its keys)
				int index = 0;
				for (ConflictKey conflictKey : conflictKeys) {
					if (conflictKey instanceof CommutativeConflictKey<?> cck && cck.isConstrainedToRange()) {
						Assert.isPremiseValid(
							aggregates != null,
							"Aggregates map must be initialized when commutative conflict keys are present!"
						);
						//noinspection unchecked
						final CommutativeConflictKey<Object> ccko = (CommutativeConflictKey<Object>) cck;
						//noinspection unchecked
						final CommutativeConflictResolver<Object> committedDeltas =
							(CommutativeConflictResolver<Object>) aggregates.get(ccko.aggregationKey());
						// the accumulated value is the stored base plus every delta committed in the
						// overlap window plus this transaction's own delta; when no committed delta shares
						// the aggregation slot, a resolver built from this key already yields base + delta
						final Object accumulatedValue = committedDeltas == null ?
							createCommutativeResolver(ccko, theLivingCatalog).accumulatedValue() :
							ccko.aggregate(committedDeltas.accumulatedValue(), ccko.deltaValue());
						ccko.assertInAllowedRange(this.catalogName, reservedCatalogVersion, accumulatedValue);
					}
					this.conflictRingBuffer.offer(new VersionedConflictKey(reservedCatalogVersion, index++, conflictKey));
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
    private static Map<Object, CommutativeConflictResolver<?>> initializeAggregatesIfNecessary(
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
	 * This method iterates through all committed live mutations following the session catalog version (the WAL
	 * stream contract starts with the transaction that evolved the catalog to `sessionCatalogVersion + 1`) up
	 * to the point where the conflict ring buffer takes over, and checks whether any recomputed conflict key
	 * overlaps the incoming transaction. If a conflict is detected, it throws
	 * a {@link ConflictingCatalogMutationException}.
	 *
	 * @param sessionCatalogVersion the snapshot version of the committing transaction; only mutations committed
	 *                              after this version are examined
	 * @param incomingScope the pre-indexed conflict scope of the transaction being committed
	 * @param until the oldest versioned conflict key still covered by the conflict ring buffer
	 */
	private void identifyConflictsInOldCommittedTransactions(
		long sessionCatalogVersion,
		@Nonnull IncomingConflictScope incomingScope,
		@Nonnull Catalog theLivingCatalog,
		@Nullable Map<Object, CommutativeConflictResolver<?>> aggregates,
		@Nonnull CatalogVersionIndex until
	) {
		// Recompute the aged-out transactions' conflict keys through the same schema-effective resolution
		// the live WAL-write path and the conflictException() diagnostics below use: the effective
		// per-entity resolution is resolved against the living catalog's current schema (entity schema →
		// catalog schema → engine default). This fallback exists precisely to test the incoming commit
		// against these evicted-but-still-in-window transactions, so recomputing with the bare engine
		// default would be wrong whenever an entity type carries a per-entity-type override that diverges
		// from it: a too-coarse recomputed key would false-positive (reject a non-overlapping commit) and a
		// too-fine one would mask a real overlap, flipping the containment verdict either way. Historical
		// schemas are deliberately not reconstructed — the current schema is authoritative here, exactly as
		// in conflictException(); the two paths must agree on the resolution so the thrown diagnostics match
		// the verdict.
		final ConflictGenerationContext context = new ConflictGenerationContext(
			this.conflictResolution,
			theLivingCatalog.getSchema(),
			entityType -> theLivingCatalog.getEntitySchema(entityType).orElse(null)
		);
		final long livingCatalogVersion = theLivingCatalog.getVersion();
		long processedCatalogVersion = sessionCatalogVersion;
		final Iterator<CatalogBoundMutation> mutationIterator = getLivingCatalog()
			.getCommittedLiveMutationStream(sessionCatalogVersion, until.catalogVersion())
			.iterator();

		while (mutationIterator.hasNext()) {
			final Mutation mutation = mutationIterator.next();
			if (mutation instanceof TransactionMutation tm) {
				processedCatalogVersion = tm.getVersion();
				// stop where the conflict ring buffer takes over: when the buffer's effective start points
				// at the first conflict key of the boundary transaction (index 0), that transaction is
				// fully covered by the buffer scan; when the buffer retained only a suffix of the boundary
				// transaction's keys (index > 0), the whole boundary transaction is examined here as well —
				// the ring buffer indexes conflict-key ordinals while this stream yields mutations, so the
				// two index domains cannot be matched exactly and the conservative overlap is preferred.
				// Re-examining the retained suffix is safe: absolute keys yield the same verdict, and
				// commutative deltas are only accumulated for versions ahead of the living catalog, which
				// the buffer's oldest transaction cannot be unless the buffer is sized smaller than the
				// in-flight transaction window
				if (processedCatalogVersion > until.catalogVersion() ||
					(processedCatalogVersion == until.catalogVersion() && until.index() == 0)) {
					break;
				}
			}

			final Iterator<ConflictKey> conflictKeyIterator = mutation
				.collectConflictKeys(context)
				.iterator();
			while (conflictKeyIterator.hasNext()) {
				final ConflictKey conflictKey = conflictKeyIterator.next();
				examineConflictKey(
					conflictKey, incomingScope, theLivingCatalog, aggregates,
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
     * @param incomingScope The pre-indexed conflict scope of the transaction being committed.
     * @param theLivingCatalog The current state of the catalog.
     * @param aggregates A map of commutative conflict keys and their corresponding aggregated values.
     *                   Can be null if it makes no sense to accumulate commutative keys.
     * @param processedCatalogVersion The version of the catalog being processed.
     * @param livingCatalogVersion The current version of the living catalog.
     * @throws ConflictingCatalogMutationException if the conflict key overlaps the current transaction.
     */
    private <T> void examineConflictKey(
        @Nonnull ConflictKey conflictKey,
        @Nonnull IncomingConflictScope incomingScope,
        @Nonnull Catalog theLivingCatalog,
        @Nullable Map<Object, CommutativeConflictResolver<?>> aggregates,
        long processedCatalogVersion,
        long livingCatalogVersion
    ) {
        // accumulate commutative conflict keys later than current living catalog version
        if (conflictKey instanceof CommutativeConflictKey<?> cck) {
            if (aggregates != null && processedCatalogVersion > livingCatalogVersion) {
                aggregates.compute(
                    cck.aggregationKey(),
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
            // A committed commutative delta still conflicts with an incoming *absolute* write of the same
            // (or a containing) scope — the delete/set-vs-delta case the accumulation above cannot express.
            // Probing from the parent keeps delta-vs-delta of the same key commuting.
            if (incomingScope.conflictsWithCommutative(conflictKey)) {
                throw conflictException(conflictKey, processedCatalogVersion, theLivingCatalog);
            }
        } else if (incomingScope.conflictsWithAbsolute(conflictKey)) {
            // the committed key's write scope overlaps the incoming transaction's write scope
            throw conflictException(conflictKey, processedCatalogVersion, theLivingCatalog);
        }
    }

	/**
	 * Builds a {@link ConflictingCatalogMutationException} enriched with the conflict-resolution diagnostics
	 * for the offending key: the policy that was in force for its scope and the schema layer that policy was
	 * resolved from. This runs only on the cold conflict-reporting path (a conflict is about to abort the
	 * transaction), so the extra schema lookup and allocation are inconsequential.
	 *
	 * The resolution is derived from the living catalog's current schema rather than reconstructed from the
	 * historical schema in effect when the conflicting change committed — see the note on the historical
	 * recompute path; the two agree in every window where they can both fire. A catalog-wide key carries no
	 * entity type, so the entity schema is skipped and the catalog/engine levels report the layer.
	 *
	 * @param conflictKey             the committed key that overlaps the incoming transaction, must not be null
	 * @param processedCatalogVersion the catalog version at which the conflicting change committed
	 * @param theLivingCatalog        the current catalog whose schema resolves the effective policy, must not be null
	 * @return the enriched exception ready to be thrown
	 */
	@Nonnull
	private ConflictingCatalogMutationException conflictException(
		@Nonnull ConflictKey conflictKey,
		long processedCatalogVersion,
		@Nonnull Catalog theLivingCatalog
	) {
		final String entityType = conflictKey.entityType();
		final ResolvedConflictResolution resolved = EffectiveConflictResolutionResolver.resolveWithSource(
			theLivingCatalog.getSchema(),
			entityType == null ? null : theLivingCatalog.getEntitySchema(entityType).orElse(null),
			this.conflictResolution
		);
		return new ConflictingCatalogMutationException(
			this.catalogName, conflictKey, processedCatalogVersion, resolved.resolution(), resolved.layer()
		);
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
	 * The append is left **written but not durable** - the caller owns the durability handshake and must
	 * pair it with {@link #syncWal()} plus {@link #updateLastDurableCatalogVersion(long)} before the
	 * transaction is acknowledged or handed to trunk incorporation. That is what lets a single device sync
	 * cover a whole batch of transactions rather than one each.
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
			// remaining acceptance-timeout budget after the time already spent queuing; commitTimestamp is in
			// the past, so elapsed = now - commitTimestamp and the budget shrinks as queue delay grows, clamped
			// at zero so an over-budget transaction does not wait (never a negative tryLock argument)
			final long elapsedMillis = Duration.between(commitTimestamp, OffsetDateTime.now()).toMillis();
			final long timeout = Math.max(0L, this.transactionAcceptanceTimeout - elapsedMillis);
			// try to obtain the lock within the timeout
			if (this.walAppendingLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
				final long theLastWrittenCatalogVersion = this.lastWrittenCatalogVersion.get();
				Assert.isPremiseValid(
					theLastWrittenCatalogVersion <= 0 || theLastWrittenCatalogVersion + 1 == transactionMutation.getVersion(),
					"Transaction cannot be written to the WAL out of order. " +
						"Expected version " + (theLastWrittenCatalogVersion + 1) + ", got " + transactionMutation.getVersion() + "."
				);
				return getLivingCatalog()
					.appendWalAndDiscardDeferringSync(
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
	 * @throws WriteAheadLogCorruptedException when the mutation stream is empty but the finalized
	 *                                          version has not yet reached {@code nextCatalogVersion}
	 *                                          — i.e. a committed transaction was expected but could
	 *                                          not be read from the WAL
	 */
	@Nonnull
	public Optional<ProcessResult> processTransactions(
		long nextCatalogVersion,
		long timeoutMs,
		boolean alive,
		boolean waitForLock,
		@Nonnull LongConsumer progressCallback
	) {
		// Gate every drain of the WAL, before the trunk lock is taken or a single mutation is read. This is the point
		// the suspension has to bite: a freshly appended transaction schedules the draining task on its own, entirely
		// independently of the path that failed, so refusing commits alone would not stop the catalog from trying
		// again. Returning empty (rather than throwing) is what makes the draining task PAUSE instead of rescheduling.
		if (this.suspension.get() != null) {
			return Optional.empty();
		}
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
				// the version whose changes we got as far as collecting + writing, or -1 if we never got that far;
				// see the catch below
				long collectingVersion = -1;

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
						// bounded by the DURABLE version, not the written one: a greedy round drains as far as
						// this bound allows and then checkpoints what it incorporated into the data files. Were
						// the bound `lastWritten`, a round could checkpoint a version whose WAL bytes are still
						// only in the page cache, and a crash in that window would leave a catalog claiming a
						// version its own WAL no longer reaches
						committedMutationStream = latestCatalog.getCommittedLiveMutationStream(
							readFromVersion, getLastDurableCatalogVersion()
						);
					} else {
						committedMutationStream = latestCatalog.getCommittedMutationStream(
							readFromVersion
						);
					}
					final Iterator<CatalogBoundMutation> mutationIterator = committedMutationStream.iterator();
					if (!mutationIterator.hasNext()) {
						// an empty stream is only legitimately "already processed" when the finalized version
						// has actually reached the version we were asked to process; if it has not, the WAL
						// delivered nothing for a version we must reach — reporting "someone else did it"
						// would silently finalize at a stale version and leave the commit-progress record
						// (and any client awaiting it) hanging forever
						if (lastFinalizedVersion >= nextCatalogVersion) {
							// previous execution already processed all the mutations
							return empty();
						}
						throw new WriteAheadLogCorruptedException(
							WalKind.CATALOG,
							"WAL mutation stream of catalog `" + getCatalogName() + "` went dry at finalized " +
								"version " + lastFinalizedVersion + " without reaching requested version " +
								nextCatalogVersion + " (last written version " +
								this.lastWrittenCatalogVersion.get() + ", last durable version " +
								this.lastDurableCatalogVersion.get() + "). A committed transaction was not " +
								"readable - refusing to report it as already processed.",
							"Write-ahead log mutation stream ended before reaching a committed transaction."
						);
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
					// From here on the collect has begun: the flush pops every trapped change, advances every page
					// baseline and writes, and the merge then publishes those baselines. A failure anywhere inside
					// leaves the baselines describing a write that may never have landed, which is precisely the state
					// no retry may run against - so the scope is POSITIONAL (where we failed), never by exception type.
					collectingVersion = lastTransactionMutation.getVersion();
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

					if (collectingVersion >= 0) {
						// a failed flush/merge: suspend rather than retry. The retry is what would diff the next flush
						// against the baselines this one left behind, and it can never succeed by repetition anyway -
						// a deterministic failure spins forever, a transient one corrupts.
						suspend(ex, isVersionPersisted(collectingVersion), collectingVersion);
					}
					// rethrow the exception - a failure BEFORE the collect (an unreadable WAL tail, a replay error) is
					// still safely retryable and keeps its bounded retry
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
	 * Waits until the catalog version in the "live view" reaches the specified version. The wait is
	 * bounded by {@link #safetyDeadlineMs()} — the same threshold the dangling-commit sweeper uses — so
	 * it never fires under normal back-pressure, only on a genuine propagation stall. On expiry
	 * a {@link TransactionTimedOutException} is thrown, which every caller translates into an
	 * exceptional completion of the affected commit-progress record (or a watchdog reschedule)
	 * instead of spinning a core forever.
	 *
	 * @param catalogVersion The catalog version to wait for in the "live view".
	 * @throws TransactionTimedOutException when the live view does not reach the version in time
	 */
	public void waitUntilLiveVersionReaches(long catalogVersion) {
		waitUntilVersionReaches(
			() -> getLivingCatalog().getVersion(),
			catalogVersion,
			safetyDeadlineMs(),
			getCatalogName()
		);
	}

	/**
	 * Bounded wait for a monotonically increasing version to reach the expected value. Busy-spins for
	 * {@link #SPIN_ATTEMPTS_BEFORE_PARK} attempts (propagation normally lands within microseconds) and
	 * then parks in {@link #PARK_INTERVAL_NANOS} intervals so a genuine stall does not burn a core.
	 * Package-private so the bounded-wait contract can be unit-tested without a full manager instance.
	 *
	 * @param liveVersion    supplier of the currently visible version
	 * @param catalogVersion the version that must be reached
	 * @param deadlineMs     maximum time to wait in milliseconds
	 * @param catalogName    name of the catalog used in the timeout message
	 * @throws TransactionTimedOutException when the version does not reach the expected value in time
	 */
	static void waitUntilVersionReaches(
		@Nonnull LongSupplier liveVersion,
		long catalogVersion,
		long deadlineMs,
		@Nonnull String catalogName
	) {
		final long deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000L;
		int spins = 0;
		long currentVersion;
		while ((currentVersion = liveVersion.getAsLong()) < catalogVersion) {
			// overflow-safe monotonic comparison mandated by the System.nanoTime() contract
			if (System.nanoTime() - deadlineNanos >= 0) {
				throw new TransactionTimedOutException(
					"Live view of catalog `" + catalogName + "` did not reach version " + catalogVersion +
						" within " + deadlineMs + " ms (stuck at version " + currentVersion +
						"); refusing to wait indefinitely."
				);
			}
			if (spins < SPIN_ATTEMPTS_BEFORE_PARK) {
				Thread.onSpinWait();
				spins++;
			} else {
				LockSupport.parkNanos(PARK_INTERVAL_NANOS);
			}
		}
	}

	/**
	 * Returns the safety threshold in milliseconds after which a pipeline wait or a pending
	 * commit-progress record is considered genuinely stalled. Five times the acceptance timeout gives
	 * ample headroom for back-pressure spikes and executor queue drain times; a floor of 60s keeps the
	 * threshold sensible on deployments that tune the acceptance timeout very low.
	 *
	 * @return the stall-detection deadline in milliseconds
	 */
	private long safetyDeadlineMs() {
		return Math.max(60_000L, this.transactionAcceptanceTimeout * 5);
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
			this.walDrainingTask::close,
			this.pendingProgressSweepTask::close
		);
		// fail any still-registered commit progress records so clients waiting on their
		// CompletionStages are unblocked with a descriptive exception rather than hanging forever
		// (e.g. when the request executor accepted an async completion task that shutdownNow
		// then drained before it could run, or when a transaction was in-flight at shutdown)
		this.pendingCommitProgressRegistry.failAllPending("the transaction manager is being closed");
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
	 * Retrieves the effective conflict resolution associated with the transaction configuration.
	 *
	 * @return a non-null {@link ConflictResolution} representing the effective conflict resolution.
	 */
	@Nonnull
	public ConflictResolution getConflictResolution() {
		return this.conflictResolution;
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
	 * Records a transaction whose bytes have reached the write-ahead log.
	 *
	 * Called from the appending stage at its point of no return - after the append succeeded and before any of the
	 * bookkeeping that must not be able to roll the version back. That is deliberately *not* the moment the
	 * transaction becomes visible to readers: once the bytes are in the log the transaction is committed whatever
	 * happens downstream, and counting it at trunk incorporation instead would also count every transaction replayed
	 * from the log at startup as if it had just been written.
	 *
	 * @param mutationCount mutations the transaction carried
	 * @param walBytes      bytes it appended to the write-ahead log
	 */
	public void recordCommittedTransaction(int mutationCount, long walBytes) {
		final long now = System.currentTimeMillis();
		this.activity.updateAndGet(current -> current.sampled(mutationCount, walBytes, now));
	}

	/**
	 * Records a transaction discarded at session close because the session was marked rollback-only.
	 */
	public void recordRolledBackTransaction() {
		this.transactionsRolledBack.incrementAndGet();
	}

	/**
	 * Records a transaction rejected by conflict resolution.
	 */
	public void recordConflictedTransaction() {
		this.transactionsConflicted.incrementAndGet();
	}

	/**
	 * Describes how much write work this catalog has done and how fast it is doing it.
	 *
	 * The rates are read through the accumulation's `effective...` methods rather than off its fields, so an idle
	 * catalog reports a rate falling towards zero instead of the load it last saw - see {@link ActivityAccumulation}
	 * for why that correction cannot be applied when the sample is taken.
	 *
	 * The pipeline depth is passed in rather than read here, because the caller has already read the very watermarks
	 * it is the span of. Reading them a second time would let this component and
	 * {@link io.evitadb.api.statistics.CommitPipelineStatistics} describe two different moments of the same pipeline
	 * within one response.
	 *
	 * Only meaningful for a transactional catalog; the caller reports
	 * {@link io.evitadb.api.statistics.ComponentAvailability#FEATURE_DISABLED} otherwise.
	 *
	 * @param pipelineDepth versions accepted but not yet visible to readers, as the caller measured them
	 * @return the {@link CatalogStatisticsComponent#ACTIVITY} component
	 */
	@Nonnull
	public ActivityStatistics describeActivity(long pipelineDepth) {
		final ActivityAccumulation current = this.activity.get();
		final long now = System.currentTimeMillis();
		return new ActivityStatistics(
			current.transactionsCommitted(),
			this.transactionsRolledBack.get(),
			this.transactionsConflicted.get(),
			current.mutationsApplied(),
			current.walBytesAppended(),
			pipelineDepth,
			current.effectiveTransactionsPerSecond(now),
			current.effectiveMutationsPerSecond(now),
			current.effectiveWalBytesPerSecond(now),
			this.countingSince
		);
	}

	/**
	 * Sends the task simulating the WAL stage finalization with tasks that drains entire contents of
	 * the WAL in the trunk incorporation stage. This should handle the situation when last transaction
	 * was not processed due to queues being full. When no other transaction comes the WAL will forever
	 * contain more records than are incorporated in the catalog.
	 *
	 * @return `0` to reschedule immediately when a transient condition (busy lock or a momentarily
	 * unreadable WAL tail) prevented draining, `-1` to pause the task once draining completed
	 */
	private long drainWal() {
		try {
			this.processTransactions(
				// the drainer may only chase versions that are already durable - same reasoning as the
				// round bound inside `processTransactions`
				getLastDurableCatalogVersion(),
				this.configuration.transaction().flushFrequencyInMillis(),
				true,
				false, // we should not wait for the lock here - if its already running it will process the transactions
				Functions.noOpLongConsumer()
			);
		} catch (TransactionTimedOutException | WriteAheadLogCorruptedException ex) {
			// Best-effort background drainer: both conditions are transient here and get retried on the
			// next tick. A busy trunk lock means another incorporation is already running; a WAL read that
			// momentarily cannot see a just-appended version (same-JVM file-length visibility lag) clears on
			// a later pass. A genuinely persistent inconsistency is surfaced loudly on the client commit
			// path by the trunk-incorporation stage; the drainer owns no commit-progress record and must not
			// let an uncaught throw permanently pause this task.
			return 0;
		}
		// pause the task
		return -1;
	}

	/**
	 * Periodic watchdog body for {@link #pendingProgressSweepTask}. Fails every record in
	 * {@link #pendingCommitProgressRegistry} whose pending age exceeds a safety threshold derived
	 * from the configured transaction-acceptance timeout — if a record has been pending that long
	 * the pipeline has certainly dropped it and any client awaiting it is better served by a
	 * descriptive exception than by silence.
	 *
	 * The task pauses when the registry drains and re-schedules itself as soon as a new record is
	 * registered (via {@link #registerPendingCommitProgress}).
	 *
	 * @return `0` to re-schedule with the default delay when the registry is non-empty, `-1` to
	 * pause when there is nothing left to watch over
	 */
	private long sweepDanglingCommitProgress() {
		this.pendingCommitProgressRegistry.sweepRecordsOlderThan(Duration.ofMillis(safetyDeadlineMs()));
		return this.pendingCommitProgressRegistry.size() > 0 ? 0L : -1L;
	}

	/**
	 * Registers a commit progress record in the pending registry and schedules the time-bounded
	 * watchdog so it starts running while at least one record is in flight. The sweep task will
	 * pause itself automatically once the registry drains again.
	 *
	 * @param catalogVersion catalog version assigned by conflict resolution
	 * @param record         the progress record waiting for pipeline completion
	 * @param commitVersions versions captured at registration time, used by the trunk stage's
	 *                       greedy-batch fan-out to supply the termination callback
	 */
	public void registerPendingCommitProgress(
		long catalogVersion,
		@Nonnull CommitProgressRecord record,
		@Nonnull CommitVersions commitVersions
	) {
		this.pendingCommitProgressRegistry.register(catalogVersion, record, commitVersions);
		this.pendingProgressSweepTask.schedule();
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
