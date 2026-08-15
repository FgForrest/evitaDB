/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.transaction.engine;


import io.evitadb.api.exception.ConflictingEngineMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.*;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.transaction.engine.operators.*;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.spi.store.engine.model.UnprocessedTransactionRecord;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * EngineTransactionManager coordinates execution of engine-level mutations that change global
 * EvitaDB engine state (e.g. creating, duplicating, modifying, restoring or removing catalogs).
 *
 * Key responsibilities:
 * - Serialize updates to the engine state using a reentrant lock, while still allowing
 *   multiple non-conflicting engine mutations to proceed in parallel. The parallelism is
 *   guarded by conflict keys reported by the individual mutations. When a conflict is detected,
 *   a {@link io.evitadb.api.exception.ConflictingEngineMutationException} is thrown.
 * - Provide progress tracking for long-running top-level catalog mutations via {@link Progress}.
 * - Persist committed mutations to the engine Write-Ahead Log (WAL) in the order they finish,
 *   not the order they started, improving resiliency and restart semantics.
 * - Keep the authoritative engine state in sync with the persistence layer and notify
 *   the {@link SystemChangeObserver} about newly committed versions.
 * - Expose a system {@link java.util.concurrent.Executor} for asynchronous engine-level tasks
 *   that should not time out.
 *
 * Concurrency and WAL notes:
 * - Before a mutation is executed, its conflict keys are recorded so that subsequent mutations can
 *   detect conflicts early. The keys are removed when the mutation completes.
 * - The engine state is updated in two steps: a pre-mutation update (in-memory) and a post-mutation
 *   update (persisting WAL and the new engine state, then publishing the new version to observers).
 * - WAL is truncated on startup if the previous run left unfinished records.
 *
 * Usage outline:
 * - Call {@link #applyMutation(EngineMutation, java.util.function.IntConsumer)} to run a mutation
 *   and obtain a {@link Progress} handle to observe completion.
 * - Use {@link #getCommittedMutationStream(long)} or
 *   {@link #getReversedCommittedMutationStream(Long)} to iterate through committed WAL entries.
 *
 * Thread-safety:
 * - All state transitions are protected by an internal lock. The lock acquisition respects the
 *   configured transaction timeout and throws {@link TransactionTimedOutException} if it cannot be
 *   obtained in time.
 *
 * This class is closeable and ensures the underlying persistence service is closed on shutdown.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class EngineTransactionManager implements Closeable {
	/**
	 * Index of engine mutation operators that are used to process specific types of mutations.
	 */
	private final Map<Class<? extends EngineMutation<?>>, EngineMutationOperator<?, ?>> engineMutationOperators;
	/**
	 * Evita instance this transaction manager is bound to.
	 */
	private final Evita evita;
	/**
	 * Change observer that is used to notify all registered subscribers about changes in the catalogs.
	 */
	private final SystemChangeObserver changeObserver;
	/**
	 * Executor service that wraps "transactionExecutor" and is used for executing asynchronous engine level tasks
	 * that never timeout.
	 */
	private final ObservableExecutorService engineExecutor;
	/**
	 * Persistence service that is used to store and retrieve {@link EngineState} information from the persistence
	 * storage.
	 */
	private final EnginePersistenceService<LogRecordReference> enginePersistenceService;
	/**
	 * Everything the engine knows about catalog storage folders. Held here — beyond being handed to the operators
	 * — so that the engine-state commit path can discharge the tombstones of folders already confirmed gone.
	 */
	private final CatalogFolderContext folderContext;
	/**
	 * Lock that is used to synchronize access to the engine state.
	 */
	private final ReentrantLock engineStateLock = new ReentrantLock();
	/**
	 * Set contains conflict keys generated by engine mutations that were written to the WAL, but were not yet
	 * processed by the engine. I.e. it contains currently processed and non-finished mutations that are being applied.
	 */
	private final ConcurrentHashMap<ConflictKey, UUID> processedEngineMutations = CollectionUtils.createConcurrentHashMap(64);
	/**
	 * Map that keeps track of currently running mutations for each catalog.
	 */
	private final Map<String, Progress<?>> currentCatalogMutations = CollectionUtils.createConcurrentHashMap(64);
	/**
	 * Wait interval in milliseconds that the transaction manager will wait for the engine state lock.
	 */
	private final long engineMutationWaitIntervalInMillis;

	private long lastStoredEngineStateVersion;

	/**
	 * Wedge flag set by `replayCrashedMutationIfNeeded` when it encounters a `walV == stateV + 1`
	 * situation that it cannot safely reconcile — a missing operator for the crashed mutation type
	 * or an operator whose `replayCompletionState` explicitly opts out of forward replay. Once
	 * wedged, every public mutation-issuing entry point refuses to run until an operator intervenes
	 * and hand-reconciles the bootstrap file.
	 *
	 * Also set at runtime by `updateEngineStateAfterEngineMutation` when a commit that is already
	 * durable cannot be published in memory. That is the same hazard arriving by a different route:
	 * the durable version has moved and the live state has not, so the next append would build on a
	 * snapshot the engine can no longer vouch for.
	 *
	 * Structural WAL corruption (header without body) is NOT routed through this flag; the
	 * persistence layer throws `WriteAheadLogCorruptedException` (with `WalKind.ENGINE`) directly, aborting the
	 * `EngineTransactionManager` constructor and failing engine boot — a corrupt WAL cannot be
	 * trusted for any subsequent reads either, so a runtime wedge would give a false sense that
	 * the running engine is in a recoverable state.
	 *
	 * The flag exists because leaving `lastStoredEngineStateVersion` at `stateV` while the WAL is
	 * already at `stateV + 1` invites the next `appendWalAndStoreState` call to try to append at
	 * `stateV + 1` again — duplicating (or clobbering) the crashed mutation. A loud fail-fast is
	 * always better than silent corruption, so we gate every mutation entry on this flag.
	 *
	 * Marked `volatile` because it is genuinely set on a runtime path (the post-durability publish
	 * failure above), not only during construction — a mutation on one thread must be refused by
	 * the wedge another thread has just raised.
	 */
	private volatile boolean wedged;

	/**
	 * Human-readable reason captured at the moment the engine was wedged, surfaced verbatim in the
	 * exception thrown from `applyMutation` so operators can see exactly which crashed mutation
	 * tripped the recovery and why replay could not proceed. Marked `volatile` for the same reason
	 * as `wedged`.
	 */
	@Nullable private volatile String wedgeReason;

	/**
	 * Convenience constructor used by tests and standalone code paths that do not exercise the per-catalog
	 * format-upgrade flow. Wires `NoOpUpgradeExecutor`, which only logs the intent without touching disk.
	 * Production code uses the five-arg constructor with `DefaultUpgradeExecutor` injected by `Evita` at boot.
	 *
	 * @param evita Evita instance this manager operates on; must not be null
	 * @param changeObserver system observer notified about transaction and engine mutations; must not be null
	 * @param executor underlying executor to be used for engine tasks; must not be null
	 * @param enginePersistenceService engine persistence service for WAL and engine state; must not be null
	 */
	public EngineTransactionManager(
		@Nonnull Evita evita,
		@Nonnull SystemChangeObserver changeObserver,
		@Nonnull ObservableExecutorService executor,
		@Nonnull EnginePersistenceService<LogRecordReference> enginePersistenceService
	) {
		this(evita, changeObserver, executor, enginePersistenceService, UpgradeExecutor.NoOpUpgradeExecutor.INSTANCE);
	}

	/**
	 * Creates a new EngineTransactionManager bound to the provided Evita instance.
	 *
	 * Initialization details:
	 * - Registers all known engine mutation operators.
	 * - Wraps the provided executor in a system-level observable executor to run engine tasks.
	 * - Stores the provided persistence service used for WAL and engine state storage.
	 * - Reads configuration to determine the lock wait timeout for engine mutations.
	 * - Truncates the WAL if a stale reference is present in the current engine state.
	 *
	 * @param evita Evita instance this manager operates on; must not be null
	 * @param changeObserver system observer notified about transaction and engine mutations; must not be null
	 * @param executor underlying executor to be used for engine tasks; must not be null
	 * @param enginePersistenceService engine persistence service for WAL and engine state; must not be null
	 * @param upgradeExecutor per-catalog storage-protocol upgrade executor; must not be null
	 */
	public EngineTransactionManager(
		@Nonnull Evita evita,
		@Nonnull SystemChangeObserver changeObserver,
		@Nonnull ObservableExecutorService executor,
		@Nonnull EnginePersistenceService<LogRecordReference> enginePersistenceService,
		@Nonnull UpgradeExecutor upgradeExecutor
	) {
		// Single choke point for the catalog-name-to-folder mapping. Every operator acts on catalog
		// folders through this context rather than joining the catalog name onto the storage directory, which
		// is what let the folder be decoupled from the name in one implementation instead of at every site.
		// Checked here rather than left to fail at first use: every operator captures the context at
		// construction but only dereferences it when a mutation runs, so a missing one would otherwise
		// surface as an NPE deep inside an unrelated operator - possibly during WAL replay at boot.
		final CatalogFolderContext folderContext = Objects.requireNonNull(
			evita.getCatalogFolderContext(),
			"Catalog folder context is not available on the Evita instance!"
		);
		this.folderContext = folderContext;

		this.engineMutationOperators = new HashMap<>(16);
		// register all engine mutation operators that are used to process specific types of mutations
		this.engineMutationOperators.put(
			CreateCatalogSchemaMutation.class, new CreateCatalogMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			DuplicateCatalogMutation.class, new DuplicateCatalogMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			MakeCatalogAliveMutation.class, new MakeCatalogAliveMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			MarkCatalogMissingMutation.class, new MarkCatalogMissingMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			ModifyCatalogSchemaNameMutation.class, new ModifyCatalogSchemaNameMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			ModifyCatalogSchemaMutation.class, new ModifyCatalogSchemaMutationOperator()
		);
		this.engineMutationOperators.put(
			RemoveCatalogSchemaMutation.class, new RemoveCatalogSchemaMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			RestoreCatalogSchemaMutation.class, new RestoreCatalogSchemaMutationOperator(folderContext)
		);
		this.engineMutationOperators.put(
			SetCatalogMutabilityMutation.class, new SetCatalogMutabilityMutationOperator()
		);
		this.engineMutationOperators.put(
			SetCatalogStateMutation.class, new SetCatalogStateMutationOperator(folderContext)
		);
		// Per-catalog format-upgrade infrastructure. The operator drives the state transitions
		// (`OUT_OF_DATE → BEING_UPGRADED → prior state`); the injected `upgradeExecutor` (production:
		// `DefaultUpgradeExecutor`; tests/standalone: `NoOpUpgradeExecutor`) performs the actual on-disk migration
		// during the work phase.
		this.engineMutationOperators.put(
			UpgradeCatalogFormatMutation.class,
			new UpgradeCatalogFormatMutationOperator(folderContext, upgradeExecutor)
		);

		this.evita = evita;
		this.changeObserver = changeObserver;
		this.engineExecutor = executor;
		this.enginePersistenceService = enginePersistenceService;
		this.engineMutationWaitIntervalInMillis = this.evita.getConfiguration().server().transactionTimeoutInMilliseconds();
		final ExpandedEngineState engineState = this.evita.getEngineState();
		// Prime `lastStoredEngineStateVersion` up-front so the forward-replay path can update it
		// via the normal post-replay bookkeeping. Priming must happen BEFORE the replay call
		// because `replayCrashedMutationIfNeeded` rewrites this field on a successful replay.
		this.lastStoredEngineStateVersion = engineState.version();
		// Forward WAL replay for the `walV == stateV + 1` crash window MUST run BEFORE `truncateWalFile`. Otherwise
		// the crashed WAL entry at `walV` — which the bootstrap's `walReference` does NOT cover — would be truncated
		// away before the replay can read it, silently losing the committed mutation. After a successful replay
		// the bootstrap is rewritten at `walV` with a matching `walReference`, so truncation at that point becomes
		// a no-op by construction.
		final boolean replayed = replayCrashedMutationIfNeeded(engineState);
		if (replayed) {
			// A replay can commit a folder tombstone, and the boot drain that would act on it ran a layer down in
			// the persistence-service constructor - before this, and against the state the crash left. Its ordering
			// is deliberate and stays as it is: draining against an already-healed state would cost the ability to
			// diagnose a drifted boot from the disk it left behind. So the tombstone gets a second, narrower pass
			// here instead, and the superseded folder goes on the boot that recovered rather than the one after it.
			//
			// Only reachable on a boot that replayed something, so a steady-state boot pays nothing for it. Each
			// discharge is noted the same way the divergence drain notes its own, and dropped from persisted state
			// by whichever engine mutation commits next.
			for (final CatalogFolderId drained : this.enginePersistenceService.drainRetiredFolders()) {
				this.folderContext.noteFolderDrained(drained);
			}
		} else {
			// Truncation exists to discard the tail of a commit that never finished writing. After a successful
			// replay there is no such tail: the trailing record was read in full, applied, and made durable in the
			// bootstrap, and the startup invariant guarantees nothing follows it. Running truncation anyway
			// **destroyed that record** - the in-memory snapshot handed to `setNextEngineState` is the one the
			// operator rebuilt, and it still carries the WAL reference from before the crash, so the log was cut
			// back to the version the bootstrap had just moved past.
			//
			// The result was a bootstrap at `walV` over a WAL at `walV - 1`, which is in no allowed row of the
			// startup table: the **next** boot refused to start. A recovery that bricked the installation one
			// restart later, and only when no other commit had happened in between to paper over the gap.
			truncateWalFile(this.evita.getEngineState());
		}
	}

	/**
	 * Applies a given {@link EngineMutation} to the engine and returns a {@link Progress} object representing the
	 * status and result of the mutation. This method acquires a lock on the engine state, validates the mutation, appends
	 * it to the persistence layer, and processes it. If the mutation is not applicable, or the lock cannot be acquired
	 * within the specified time, an exception is thrown.
	 *
	 * @param <T> the type of result returned by the mutation process
	 * @param engineMutation the mutation to be applied to the engine; must not be null
	 * @param progressObserver an optional observer to track mutation progress; may be null
	 * @return a {@link Progress} object representing the mutation's execution status and result
	 * @throws TransactionTimedOutException if the engine state lock cannot be acquired in the allotted time
	 * @throws InvalidMutationException if the specified mutation is not applicable
	 */
	@Nonnull
	public <T> Progress<T> applyMutation(
		@Nonnull EngineMutation<T> engineMutation,
		@Nullable IntConsumer progressObserver
	) {
		// Fail fast if the engine is wedged — by forward WAL replay during startup, or by a durable
		// commit that could not be published in memory; see `wedged`. Continuing would let the next
		// append build on a stale snapshot and reuse a version the WAL already committed, clobbering
		// that record and masking the original incident.
		if (this.wedged) {
			throw new GenericEvitaInternalError(
				"Engine is wedged and refuses further mutations: "
					+ (this.wedgeReason == null ? "<unknown reason>" : this.wedgeReason)
			);
		}
		// engine mutations are catalog-scoped and emit their fixed catalog conflict key regardless of the
		// resolution; NONE mirrors the historical empty policy set passed here.
		final Set<ConflictKey> conflictKeys = engineMutation.collectConflictKeys(
				new ConflictGenerationContext(new ConflictResolution(ConflictPolicy.NONE))
			)
			.collect(Collectors.toSet());

		UUID transactionId = null;
		boolean keysRegistered = false;
		try {
			if (this.engineStateLock.tryLock(this.engineMutationWaitIntervalInMillis, TimeUnit.MILLISECONDS)) {
				transactionId = UUIDUtil.randomUUID();
				this.engineStateLock.lock();
				try {
					// verify that we can perform the mutation
					verifyEngineMutationIsNotInConflictWithOthers(engineMutation, conflictKeys);
					// verify that we can perform the mutation
					engineMutation.verifyApplicability(this.evita);
					// register conflict keys for this transaction so concurrent mutations can detect conflicts
					final UUID registeredTxId = transactionId;
					conflictKeys.forEach(key -> this.processedEngineMutations.put(key, registeredTxId));
					keysRegistered = true;
				} finally {
					this.engineStateLock.unlock();
				}

				// value-sensitive removal — guarantees we never evict another transaction's entry
				// even when its conflict key collides with ours
				final UUID finalTxId = transactionId;
				final Runnable onFinalize = () -> conflictKeys.forEach(
					key -> this.processedEngineMutations.remove(key, finalTxId)
				);
				return applyMutationInternal(
					transactionId,
					engineMutation,
					progressObserver,
					onFinalize
				);

			} else {
				throw new TransactionTimedOutException(
					"EvitaDB transaction timed out while waiting for engine state lock! " +
						"Please increase `evitaDB.server.transactionTimeoutInMilliseconds` setting."
				);
			}
		} catch (RuntimeException e) {
			// only clean up keys this transaction actually registered — otherwise we would
			// silently drop the conflict guard of a different in-flight transaction that
			// happens to share a conflict key with us
			if (keysRegistered) {
				final UUID finalTxId = transactionId;
				conflictKeys.forEach(key -> this.processedEngineMutations.remove(key, finalTxId));
			}
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TransactionTimedOutException("Interrupted while waiting for an engine state lock!");
		} finally {
			if (this.engineStateLock.isHeldByCurrentThread()) {
				this.engineStateLock.unlock();
			}
		}
	}

	/**
	 * Retrieves the mutation progress of the engine for a specified catalog.
	 *
	 * @param catalogName the name of the catalog whose mutation progress is to be retrieved; must not be null
	 * @return an Optional containing the mutation progress if present, or an empty Optional if no progress data exists for the specified catalog
	 */
	@Nonnull
	public Optional<Progress<?>> getEngineMutationProgress(@Nonnull String catalogName) {
		return ofNullable(this.currentCatalogMutations.get(catalogName));
	}

	/**
	 * Returns internal system executor that is used for executing asynchronous engine level tasks.
	 * @return the executor that is used for executing asynchronous engine level tasks
	 */
	@Nonnull
	public Executor getExecutor() {
		return this.engineExecutor;
	}

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param version version of the engine to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	public Stream<EngineMutation<?>> getCommittedMutationStream(long version) {
		return this.enginePersistenceService.getCommittedMutationStream(version);
	}

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param version version of the engine to start the stream with, if null is provided the stream will start
	 *                with the last committed transaction
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	public Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version) {
		return this.enginePersistenceService.getReversedCommittedMutationStream(version);
	}

	/**
	 * Closes the transaction manager and releases all resources associated with it. This method ensures that the
	 * underlying persistence service is properly closed to prevent resource leaks.
	 */
	public void close() {
		// wait for all engine level tasks to complete
		CompletableFuture.allOf(
			this.currentCatalogMutations.values()
				.stream()
				.map(it -> it.onCompletion().toCompletableFuture())
				.toArray(CompletableFuture[]::new)
		).join();
		// close the engine executor
		IOUtils.closeQuietly(
			this.changeObserver::close,
			this.enginePersistenceService::close
		);
	}

	/**
	 * Cuts the write-ahead log back to the record the given state's reference ends at, discarding anything beyond
	 * it as the residue of a commit that never finished writing.
	 *
	 * **Never call this after a successful forward replay.** The state's reference is then the *pre-crash* one —
	 * the operator rebuilds state, not WAL positions — so it would cut away the very record the replay recovered.
	 * There is nothing to discard on that path anyway: the trailing record was read whole before it was applied.
	 *
	 * @param engineState the current expanded engine state containing the reference to the WAL file;
	 *                    must not be null
	 */
	private void truncateWalFile(@Nonnull ExpandedEngineState engineState) {
		// if log contains unexpected content, truncate it
		ofNullable(engineState.walFileReference())
			.ifPresent(this.enginePersistenceService::truncateWriteAheadLog);
	}

	/**
	 * Sets the wedge flag and stashes `reason` for inclusion in the `applyMutation` exception
	 * thrown when a subsequent mutation is attempted. All unsupported or impossible branches of
	 * `replayCrashedMutationIfNeeded` must route through this method so the engine refuses to
	 * accept further mutations until an operator reconciles the on-disk state by hand.
	 *
	 * @param reason human-readable explanation of why the engine was wedged
	 */
	private void wedge(@Nonnull String reason) {
		this.wedged = true;
		this.wedgeReason = reason;
	}

	/**
	 * Forward-replay recovery for the single OS-crash window in which the WAL advanced but the matching bootstrap
	 * rewrite did not.
	 *
	 * `appendWalAndStoreState` fuses the WAL append and the bootstrap rewrite into a single critical section. The
	 * only way to produce `walV == stateV + 1` on startup is an OS-level crash between the two on-disk writes inside
	 * that critical section. The WAL entry is durable, the work-phase side effects already happened in the operator
	 * before the fused call, but the bootstrap still records the old version. This method reconciles the bootstrap
	 * by:
	 *
	 * 1. Reading the committed mutation at `walV` from the WAL.
	 * 2. Looking up the operator for that mutation class.
	 * 3. Asking the operator for a pure recomputation of the completion-phase
	 *    `ExpandedEngineState` via `replayCompletionState`.
	 * 4. Persisting the reconciled snapshot via `rewriteEngineStateAtNextVersion` — which writes
	 *    the bootstrap file but does NOT append to the WAL.
	 *
	 * If no operator supports forward replay for the crashed mutation type, the method logs an
	 * ERROR, routes through `wedge(...)` to mark the engine as unusable, and returns without
	 * mutating on-disk state. The wedge flag is subsequently checked by every public mutation
	 * entry point so no caller can append a colliding WAL record after an unsupported replay.
	 *
	 * Called once during the transaction manager constructor, BEFORE `truncateWalFile` and after
	 * `lastStoredEngineStateVersion` has been primed from the current engine state. The ordering
	 * is load-bearing: truncating the WAL first would shred the crashed record at `walV` before
	 * this method gets a chance to read it, silently losing the committed mutation.
	 *
	 * @param engineState the initial expanded engine state at `stateV` just restored from disk
	 */
	private boolean replayCrashedMutationIfNeeded(@Nonnull ExpandedEngineState engineState) {
		final long walVersion = this.enginePersistenceService.getLastVersionInMutationStream();
		final long stateVersion = engineState.version();
		if (walVersion == stateVersion) {
			// Steady state — nothing to replay.
			return false;
		}
		if (walVersion == 0L && stateVersion == 1L) {
			// Never-used service — initial engine state exists at version 1 before any WAL
			// file is created. Both boot paths must treat `(walV=0, stateV=1)` as legitimate.
			return false;
		}
		if (walVersion != stateVersion + 1L) {
			// The constructor of DefaultEnginePersistenceService enforces the allowed
			// combinations, so this branch is theoretically unreachable. Log at ERROR
			// level to make any accidental regression visible rather than silently ignoring it.
			log.error(
				"Unexpected WAL / engine-state drift after startup validation: " +
					"walVersion={}, stateVersion={}. Skipping forward replay.",
				walVersion, stateVersion
			);
			wedge(
				"Unexpected WAL / engine-state drift after startup validation: walVersion="
					+ walVersion + ", stateVersion=" + stateVersion + ". Further mutations refused "
					+ "until the on-disk state is reconciled by an operator."
			);
			return false;
		}

		// Fetch the unprocessed transaction record at walVersion. The record bundles everything the replay path
		// needs — the engine mutation drives state recomputation, the WAL reference is embedded into the new
		// `EngineState` so the bootstrap file points at the matching WAL record.
		//
		// The persistence layer throws `WriteAheadLogCorruptedException` (WalKind.ENGINE) directly when it detects a
		// header-without-body in the WAL, so corruption surfaces from this constructor and aborts boot —
		// a corrupt WAL is genuinely fatal and must not be masked by a runtime wedge.
		//
		// `Optional.empty()` would only mean "no work to do", which contradicts the precondition asserted
		// just above (`walVersion == stateVersion + 1`). It indicates an internal inconsistency between
		// `getLastVersionInMutationStream` and `getUnprocessedTransaction`, so we surface it as a defense-
		// in-depth internal error rather than silently wedging.
		final UnprocessedTransactionRecord<LogRecordReference> record =
			this.enginePersistenceService.getUnprocessedTransaction()
				.orElseThrow(() -> new GenericEvitaInternalError(
					"Forward WAL replay requested at version " + walVersion + " (state at version "
						+ stateVersion + ") but the persistence service reported no unprocessed transaction. " +
						"This contradicts the startup invariant `walVersion == stateVersion + 1` and points to " +
						"an inconsistency between `getLastVersionInMutationStream` and `getUnprocessedTransaction`."
				));
		final EngineMutation<?> crashedMutation = record.mutation();
		final LogRecordReference replayWalReference = record.walReference();

		@SuppressWarnings({"rawtypes"}) final EngineMutationOperator operator =
			this.engineMutationOperators.get(crashedMutation.getClass());
		if (operator == null) {
			log.error(
				"Forward WAL replay requested at version {} for mutation type {}, but no operator " +
					"is registered for that type. Engine state will remain at version {} — " +
					"operator intervention required.",
				walVersion, crashedMutation.getClass().getName(), stateVersion
			);
			wedge(
				"Forward WAL replay requested at version " + walVersion + " for mutation type "
					+ crashedMutation.getClass().getName() + " but no operator is registered. "
					+ "Engine wedged — operator intervention required."
			);
			return false;
		}

		// Ask the operator to reconstruct the completion-phase state purely from the mutation.
		// Operators that do not support forward replay return Optional.empty(), which we treat as
		// a loud wedge rather than silently corrupting state.
		@SuppressWarnings({"unchecked"}) final Optional<ExpandedEngineState> replayedOpt =
			operator.replayCompletionState(crashedMutation, walVersion, engineState, this.evita);
		if (replayedOpt.isEmpty()) {
			log.error(
				"Forward WAL replay is not supported for mutation type {} at version {}. Engine " +
					"state will remain at version {} — operator intervention required to " +
					"reconcile the bootstrap file manually.",
				crashedMutation.getClass().getName(), walVersion, stateVersion
			);
			wedge(
				"Forward WAL replay is not supported for mutation type "
					+ crashedMutation.getClass().getName() + " at version " + walVersion
					+ ". Engine wedged — operator intervention required to reconcile the bootstrap "
					+ "file manually."
			);
			return false;
		}
		final ExpandedEngineState replayedEngineState = replayedOpt.get();

		// Persist the reconciled snapshot. rewriteEngineStateAtNextVersion writes the bootstrap
		// file without appending to the WAL, which is exactly the right thing to do here —
		// the WAL already contains the committed mutation at walVersion.
		final EngineState<LogRecordReference> finalEngineState =
			replayedEngineState.engineState(replayWalReference, walVersion);
		this.enginePersistenceService.rewriteEngineStateAtNextVersion(finalEngineState);

		this.lastStoredEngineStateVersion = walVersion;
		this.evita.setNextEngineState(replayedEngineState);

		log.info("Forward-replayed mutation at version {} after crash recovery.", walVersion);
		return true;
	}

	/**
	 * Applies a mutation to the internal engine state, processes the mutation with an observer,
	 * and performs appropriate operations based on the specific type of engine mutation.
	 *
	 * The transaction is written AFTER the engine mutation is applied to the engine state, on the contrary to
	 * the write-ahead log (WAL) where the transaction is written BEFORE the engine mutation is applied. This change
	 * is to allow:
	 *
	 * - execute multiple engine-level mutations in parallel as long as they don't conflict with each other
	 * - avoid writing mutation to the log if the mutation cannot be finished (for example due to OOM condition or whatever)
	 *   which would cause the mutation to be retried again when engine is restarted leading to the same OOM condition
	 * - log contains mutations in order they are finished rather than in order they were requested
	 *
	 * @param engineMutation                 the mutation to be applied to the engine state
	 * @param progressObserver               an observer to track the progress of the mutation; can be a no-op if null
	 * @param <T>                            the type of result returned by the mutation process
	 * @return a Progress object representing the status and result of the executed mutation
	 */
	@Nonnull
	private <T> Progress<T> applyMutationInternal(
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<T> engineMutation,
		@Nullable IntConsumer progressObserver,
		@Nonnull Runnable onCompletion
	) {
		if (engineMutation instanceof ServerModifyCatalogSchemaMutation smcsm) {
			// this specific kind of mutation occurs only in Catalog transaction manager when already accepted
			// and verified transaction is replayed against the engine, so here we just need to write it to the
			// engine WAL and broadcast the change to the observers - but we don't need really to apply this mutation
			// on the catalog level, as the operator normally does, because the change is already incorporated by
			// the catalog transaction manager
			updateEngineStateAfterEngineMutation(
				ModifyCatalogSchemaMutationOperator.increaseEngineVersionOnly(
					transactionId,
					smcsm.getDelegate()
				)
			);
			onCompletion.run();
			//noinspection unchecked
			return (Progress<T>) ProgressRecord.completed("engineUpdate", Void.class);
		} else {
			@SuppressWarnings("unchecked") final EngineMutationOperator<T, EngineMutation<T>> engineMutationOperator =
				(EngineMutationOperator<T, EngineMutation<T>>) this.engineMutationOperators.get(
					engineMutation.getClass());
			Assert.isPremiseValid(
				engineMutationOperator != null,
				"Unknown engine mutation operator for mutation: " + engineMutation.getClass()
			);

			final Consumer<ProgressRecord<T>> onProgressExecution;
			final Consumer<ProgressRecord<T>> onProgressCompletion;
			if (engineMutation instanceof TopLevelCatalogMutation<?> catalogMutation) {
				onProgressExecution = progress -> this.currentCatalogMutations.put(
					catalogMutation.getCatalogName(), progress);
				onProgressCompletion = progress -> {
					this.currentCatalogMutations.remove(catalogMutation.getCatalogName());
					onCompletion.run();
				};
			} else {
				onProgressExecution = Functions.noOpConsumer();
				onProgressCompletion = progress -> onCompletion.run();
			}

			return new ProgressRecord<>(
				engineMutationOperator.getOperationName(engineMutation),
				progressObserver == null ? Functions.noOpIntConsumer() : progressObserver,
				engineMutationOperator.applyMutation(
					transactionId, engineMutation, this.evita,
					this::updateEngineStateBeforeEngineMutation,
					this::updateEngineStateAfterEngineMutation
				),
				onProgressExecution,
				onProgressCompletion,
				ProgressingFuture.unrejectableExecutor(this.engineExecutor)
			);
		}
	}

	/**
	 * Updates the engine state prior to applying a mutation. This method acquires a lock to ensure
	 * thread-safe operations on the engine state, applies the provided state updater function
	 * to derive the next engine state, and updates the engine with the new state.
	 *
	 * @param engineStateUpdater a function that modifies the current engine state and returns the updated state; must not be null
	 */
	private void updateEngineStateBeforeEngineMutation(@Nonnull EngineStateUpdater engineStateUpdater) {
		this.engineStateLock.lock();
		try {
			this.evita.setNextEngineState(
				engineStateUpdater.apply(
					this.lastStoredEngineStateVersion + 1, this.evita.getEngineState()
				)
			);
		} finally {
			this.engineStateLock.unlock();
		}
	}

	/**
	 * Updates the state of the engine after applying a mutation. This method creates a new engine state
	 * with an incremented version, updates the persistence layer with the new state, and notifies
	 * observers about the change.
	 *
	 * @param engineStateUpdater A function that takes the current engine state and returns an updated version of it.
	 */
	private void updateEngineStateAfterEngineMutation(@Nonnull EngineStateUpdater engineStateUpdater) {
		this.engineStateLock.lock();
		try {
			final long nextStateVersion = this.lastStoredEngineStateVersion + 1;

			// Build the next in-memory engine state up-front. The persistence-layer
			// state factory invoked by `appendWalAndStoreState` will derive the
			// persisted `EngineState` from this snapshot by embedding the fresh
			// WAL reference.
			final EngineMutation<?> engineMutation = engineStateUpdater.getEngineMutation();
			// Tombstones of folders that are provably gone are discharged here rather than by whoever deleted
			// them: the delete happens *after* its own commit, so the operator that performed it has no further
			// commit to record the fact in. Riding on the next engine mutation - any engine mutation - is what
			// keeps a discharged tombstone from being carried in persisted state for the lifetime of the
			// installation. The snapshot is taken before the state is built so that a folder drained concurrently
			// is left for the following commit rather than being forgotten below without having been pruned.
			final Set<CatalogFolderId> drainedFolders = Set.copyOf(this.folderContext.getDrainedFolders());
			final ExpandedEngineState mutatedEngineState = engineStateUpdater.apply(
				nextStateVersion, this.evita.getEngineState()
			);
			final ExpandedEngineState nextEngineState = drainedFolders.isEmpty() ?
				mutatedEngineState :
				ExpandedEngineState.builder(mutatedEngineState)
					// the version stays exactly where the mutation put it - discharging a tombstone is bookkeeping
					// that rides along with a commit, never a commit of its own
					.withVersion(nextStateVersion)
					.withoutRetiredFolders(drainedFolders)
					.build();

			// Fused WAL append + bootstrap rewrite. The persistence service takes its WAL lock for the whole
			// critical section, so there is no longer a window in which engine state could advance without a
			// matching WAL entry.
			// Explicit generic cast to recover the parameterized signature for Java's lambda
			// type inference — the field is intentionally stored as a raw type, so we narrow
			// here just for this call.
			final TransactionMutationWithWalReference txMutationWithWalReference =
				this.enginePersistenceService.appendWalAndStoreState(
					nextStateVersion,
					engineStateUpdater.getTransactionId(),
					engineMutation,
					txRef -> nextEngineState.engineState(
						txRef.walReference(),
						nextStateVersion
					)
				);

			// **The mutation is durable from here, and nothing below may report failure.** The write-ahead log
			// record is appended and the bootstrap rewritten, so it survives a restart whatever happens next.
			// A caller told "this failed" would be told something untrue, and would act on it: an operator
			// undoes bookkeeping the durable state has already recorded, and a client retries an operation that
			// has already happened. Everything after this line is therefore best-effort and logged - the same
			// rule the operators apply to their own post-commit work, and for the same reason.
			//
			// Reporting nothing costs nothing that reporting would have recovered. The realistic failure here is
			// a change observer closing underneath an in-flight operation, and the capture it refuses is lost
			// either way: `processMutation` is what fills the replay buffer, so failing the caller's future
			// neither redelivers the event nor lets anyone else recover it. It only adds a lie to a loss.
			try {
				// notify system observer about the mutation - before the publish, as it always has been
				this.changeObserver.processMutation(txMutationWithWalReference.transactionMutation());
				this.changeObserver.processMutation(engineMutation);
			} catch (Throwable ex) {
				log.error(
					"Change data capture dispatch failed for engine state version `{}`, which is already " +
						"durable - subscribers have missed this mutation and cannot replay it, because the " +
						"buffer they would replay from is filled by the dispatch that just failed.",
					nextStateVersion, ex
				);
			}
			// **The publish, alone in its own guard.** It is the only statement past the boundary whose failure
			// is not survivable, so it is the only one allowed to wedge - see the catch below for why, and the
			// second block for why nothing else may join it there.
			try {
				// Published unconditionally, whatever the observers did: durable state sitting ahead of the state
				// every reader resolves against is a split brain nobody can compensate for, and - because the
				// version counter moves with it - the next mutation would otherwise append at a version the log
				// already holds.
				this.lastStoredEngineStateVersion++;
				this.evita.setNextEngineState(nextEngineState);
			} catch (Throwable ex) {
				// **Wedged rather than merely logged**, and this is the one post-durability failure that earns
				// it, because the version counter has already moved. The next mutation would then derive its
				// snapshot from `evita.getEngineState()` - still the pre-commit state - and persist it at the
				// advanced version, durably erasing whatever this commit recorded: a catalog binding, a
				// tombstone. And it would do so silently, once per commit, for as long as the process runs.
				//
				// Refusing further mutations is what stops that, and it is the same escalation the forward-replay
				// path already uses for a state it cannot reconcile on its own. The caller of *this* mutation is
				// still not told it failed - it did not, it is durable - but nothing else is allowed to build on
				// a snapshot the engine can no longer vouch for.
				wedge(
					"engine state version `" + nextStateVersion + "` was committed durably but could not be " +
						"published in memory: " + ex.getMessage()
				);
				log.error(
					"Publishing engine state version `{}` in memory failed after it had been made durable. The " +
						"mutation survives a restart, but this process now refuses further engine mutations - " +
						"restart it to resume from the durable state.",
					nextStateVersion, ex
				);
				// Returned rather than fallen through: every statement below reads `nextEngineState` as the state
				// that is now live, and it is not. Their work is retention bookkeeping for a version no reader
				// can see, and the engine accepts no further mutation to make use of it.
				return;
			}
			try {
				// only now is the pruning durable, so the confirmations that produced it can be forgotten
				this.folderContext.forgetDrainedFolders(drainedFolders);
				// and only now can a generation counter be retired, for the same reason: the evidence that the name
				// holds nothing any more is exactly the pruned state that was just made durable
				retireGenerationSequences(mutatedEngineState, nextEngineState, drainedFolders);
				// finally, notify the change observer about the new version
				this.changeObserver.notifyVersionPresentInLiveView(nextStateVersion);
			} catch (Throwable ex) {
				// **Logged and never wedged**, in deliberate contrast to the publish above. These three are
				// retention bookkeeping and a notification, and the next commit performs all of them again from
				// the state it publishes then - a drained folder stays confirmed, a generation counter stays
				// unretired, a subscriber wakes one version late. Nothing here can put the durable and live
				// halves out of step, which is the only thing the wedge exists to prevent.
				//
				// The distinction is not academic: `notifyVersionPresentInLiveView` walks change-capture
				// subscribers and `SystemChangeObserver` refuses it outright once closed, exactly as
				// `processMutation` does two blocks above. Wedging on that would refuse every future mutation
				// across every catalog because a subscriber went away during a shutdown - stopping the engine to
				// protect state that is, by then, perfectly in step.
				log.error(
					"Post-publish bookkeeping for engine state version `{}` did not complete. The version is " +
						"durable and live, so this costs only retention work and a change-capture wake-up that " +
						"the next commit performs again.",
					nextStateVersion, ex
				);
			}
		} finally {
			this.engineStateLock.unlock();
		}
	}

	/**
	 * Discards the folder generation counters of catalog names the commit just stopped referring to.
	 *
	 * The counters live in an engine-scoped {@link SequenceService} whose maps are
	 * append-only, so without this a server that churns catalogs retains one entry per catalog name ever
	 * materialised, for the life of the process. Nothing behaved wrongly — it is pure retention — and the
	 * discard is likewise pure bookkeeping, which is why it rides along with a commit rather than being one.
	 *
	 * **The tombstone drain is the only place this can be decided**, and that is not an implementation
	 * convenience. Dropping a catalog does not free its name: the folder removal is *owed* rather than done, and
	 * a tombstone is a standing order to delete one specific directory. Retiring the counter while such an order
	 * is outstanding would let a recreated catalog of the same name draw the number the order names, and — if
	 * the directory happened to be gone already — bind itself to a token something is still under instructions
	 * to destroy. Only once the last of a name's tombstones has been pruned is that unreachable.
	 *
	 * A name is retired when the durable state that has just been published carries no binding for it and no
	 * tombstone naming it, and nothing is materialising it. That rule is loose in three deliberate ways:
	 *
	 * - **Litter is not consulted.** Folders a failed attempt left behind are invisible to the engine state, so
	 *   a restarted counter can walk back onto one. That costs a number rather than data:
	 *   `CatalogFolderAllocator` treats a directory it cannot create as a number to burn and draws the next,
	 *   bounded by its attempt limit. The residual trade is that a name with as many surviving litter folders as
	 *   that limit now fails allocation where a monotonic counter would have stepped over them.
	 * - **A tombstone is matched by the catalog name it records, not by the shape of its token.** Renaming is a
	 *   pointer swap that leaves the folder where it is, so a tombstone can carry `orders` while its token reads
	 *   `products_3`. Such a token is never *nominated* here either — the drain nominates `orders` — so the old
	 *   name simply keeps its counter. That is retention this does not reclaim, not a counter retired unsafely.
	 * - **A folder both bound and tombstoned is safe by classification**, not by timing: boot classification
	 *   ranks `REFERENCED` above `RETIRED`, so a directory a live catalog occupies is never drained whatever
	 *   else claims it.
	 *
	 * @param stateBeforePruning state carrying the tombstones about to be discharged, so their names are readable
	 * @param stateAfterPruning  durable state the commit published
	 * @param drainedFolders     folders whose tombstones this commit discharged
	 */
	private void retireGenerationSequences(
		@Nonnull ExpandedEngineState stateBeforePruning,
		@Nonnull ExpandedEngineState stateAfterPruning,
		@Nonnull Set<CatalogFolderId> drainedFolders
	) {
		if (drainedFolders.isEmpty()) {
			return;
		}
		final RetiredFolder[] remainingTombstones = stateAfterPruning.engineState().retiredFolders();
		for (final RetiredFolder discharged : stateBeforePruning.engineState().retiredFolders()) {
			if (!drainedFolders.contains(discharged.folderId())) {
				continue;
			}
			// A name with several folders draining at once is nominated once per folder; the repeat calls below
			// are no-ops, which is cheaper than de-duplicating a set that almost always holds one entry.
			final String catalogName = discharged.catalogName();
			if (stateAfterPruning.boundFolderIdFor(catalogName) != null
				|| this.folderContext.isMaterialising(catalogName)
				|| namedByAnyOf(remainingTombstones, catalogName)) {
				continue;
			}
			// Safe against this service and only this one: it is the engine-scoped instance holding nothing but
			// `CATALOG_GENERATION` counters. `removeSequences` drops *every* sequence recorded for the name, so
			// against a catalog-scoped service it would take that catalog's entity primary-key sequences with it.
			this.evita.getCatalogGenerationSequences().removeSequences(catalogName);
		}
	}

	/**
	 * Tells whether any of the passed tombstones was recorded for the given catalog name.
	 *
	 * @param tombstones  tombstones to search
	 * @param catalogName name to look for
	 * @return true when at least one tombstone names the catalog
	 */
	private static boolean namedByAnyOf(@Nonnull RetiredFolder[] tombstones, @Nonnull String catalogName) {
		for (final RetiredFolder tombstone : tombstones) {
			if (catalogName.equals(tombstone.catalogName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Ensures the provided {@link EngineMutation} does not conflict with previously processed mutations. This method
	 * iterates through the conflict keys of the given mutation and checks if any of them overlap with those of
	 * already processed mutations. If a conflict is detected, a {@link ConflictingEngineMutationException} is thrown.
	 *
	 * @param engineMutation the mutation to be checked for conflicts; must not be null
	 * @throws ConflictingEngineMutationException if the provided mutation conflicts with already processed mutations
	 */
	private void verifyEngineMutationIsNotInConflictWithOthers(
		@Nonnull EngineMutation<?> engineMutation,
		@Nonnull Set<ConflictKey> conflictKeys
	) {
		conflictKeys.forEach(
			conflictKey -> {
				final UUID txUUID = this.processedEngineMutations.get(conflictKey);
				if (txUUID != null) {
					throw new ConflictingEngineMutationException(
						"Engine mutation `" + engineMutation.getClass().getSimpleName() + "` with key `" +
							conflictKey + "` is in conflict with already processed transaction `" +
							txUUID + "`!"
					);
				}
			}
		);
	}

}
