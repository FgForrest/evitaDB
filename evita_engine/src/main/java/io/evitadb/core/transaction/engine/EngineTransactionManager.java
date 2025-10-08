/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.SystemObservableExecutorService;
import io.evitadb.core.transaction.engine.operators.*;
import io.evitadb.function.Functions;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
	private final EnginePersistenceService enginePersistenceService;
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
	 */
	public EngineTransactionManager(
		@Nonnull Evita evita,
		@Nonnull SystemChangeObserver changeObserver,
		@Nonnull ObservableExecutorService executor,
		@Nonnull EnginePersistenceService enginePersistenceService
	) {
		final Path storageDirectory = evita.getConfiguration().storage().storageDirectory();

		this.engineMutationOperators = new HashMap<>(16);
		// register all engine mutation operators that are used to process specific types of mutations
		this.engineMutationOperators.put(CreateCatalogSchemaMutation.class, new CreateCatalogMutationOperator(storageDirectory));
		this.engineMutationOperators.put(DuplicateCatalogMutation.class, new DuplicateCatalogMutationOperator(storageDirectory));
		this.engineMutationOperators.put(MakeCatalogAliveMutation.class, new MakeCatalogAliveMutationOperator(storageDirectory));
		this.engineMutationOperators.put(ModifyCatalogSchemaNameMutation.class, new ModifyCatalogSchemaNameMutationOperator());
		this.engineMutationOperators.put(ModifyCatalogSchemaMutation.class, new ModifyCatalogSchemaMutationOperator());
		this.engineMutationOperators.put(RemoveCatalogSchemaMutation.class, new RemoveCatalogSchemaMutationOperator(storageDirectory));
		this.engineMutationOperators.put(RestoreCatalogSchemaMutation.class, new RestoreCatalogSchemaMutationOperator(storageDirectory));
		this.engineMutationOperators.put(SetCatalogMutabilityMutation.class, new SetCatalogMutabilityMutationOperator());
		this.engineMutationOperators.put(SetCatalogStateMutation.class, new SetCatalogStateMutationOperator(storageDirectory));

		this.evita = evita;
		this.changeObserver = changeObserver;
		this.engineExecutor = new SystemObservableExecutorService("engineExecutor", executor);
		this.enginePersistenceService = enginePersistenceService;
		this.engineMutationWaitIntervalInMillis = this.evita.getConfiguration().server().transactionTimeoutInMilliseconds();
		final ExpandedEngineState engineState = this.evita.getEngineState();
		truncateWalFile(engineState);
		this.lastStoredEngineStateVersion = engineState.version();
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
		// on completion remove the mutation conflicting keys from the processed mutations
		final Runnable onFinalize = () -> engineMutation.getConflictKeys()
		                                                .forEach(this.processedEngineMutations::remove);

		try {
			if (this.engineStateLock.tryLock(this.engineMutationWaitIntervalInMillis, TimeUnit.MILLISECONDS)) {
				final UUID transactionId = UUIDUtil.randomUUID();
				this.engineStateLock.lock();
				try {
					// verify that we can perform the mutation
					verifyEngineMutationIsNotInConflictWithOthers(engineMutation);
					// verify that we can perform the mutation
					engineMutation.verifyApplicability(this.evita);
					// append the mutation to the WAL
					engineMutation.getConflictKeys().forEach(
						key -> this.processedEngineMutations.put(key, transactionId));
				} finally {
					this.engineStateLock.unlock();
				}

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
			// when exception is thrown, cleanup the processed mutations
			onFinalize.run();
			throw e;
		} catch (InterruptedException e) {
			// do nothing
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
		IOUtils.closeQuietly(this.enginePersistenceService::close);
	}

	/**
	 * Truncates the write-ahead log (WAL) file associated with the provided {@link ExpandedEngineState}.
	 * This method ensures that any non-processed actions within the WAL are executed,
	 * and subsequently truncates the WAL file through the engine persistence service.
	 *
	 * @param engineState the current expanded engine state containing the reference to the WAL file;
	 *                    must not be null
	 */
	private void truncateWalFile(@Nonnull ExpandedEngineState engineState) {
		// if log contains unexpected content, truncate it
		ofNullable(engineState.walFileReference())
			.ifPresent(this.enginePersistenceService::truncateWalFile);
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
		@Nullable Runnable onCompletion
	) {
		@SuppressWarnings("unchecked")
		final EngineMutationOperator<T, EngineMutation<T>> engineMutationOperator =
			(EngineMutationOperator<T, EngineMutation<T>>) this.engineMutationOperators.get(engineMutation.getClass());
		Assert.isPremiseValid(engineMutationOperator != null, "Unknown engine mutation operator for mutation: " + engineMutation.getClass());

		final Consumer<ProgressRecord<T>> onProgressExecution;
		final Consumer<ProgressRecord<T>> onProgressCompletion;
		if (engineMutation instanceof TopLevelCatalogMutation<?> catalogMutation) {
			onProgressExecution = progress -> this.currentCatalogMutations.put(catalogMutation.getCatalogName(), progress);
			onProgressCompletion = progress -> {
				this.currentCatalogMutations.remove(catalogMutation.getCatalogName());
				if (onCompletion != null) {
					onCompletion.run();
				}
			};
		} else {
			onProgressExecution = Functions.noOpConsumer();
			onProgressCompletion = onCompletion == null ?
				Functions.noOpConsumer() :
				progress -> onCompletion.run();
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
			this.engineExecutor
		);
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

			// first store the mutation into the persistence service
			final EngineMutation<?> engineMutation = engineStateUpdater.getEngineMutation();
			final TransactionMutationWithWalFileReference txMutationWithWalFileReference = this.enginePersistenceService.appendWal(
				nextStateVersion,
				engineStateUpdater.getTransactionId(),
				engineMutation
			);
			// notify system observer about the mutation
			this.changeObserver.processMutation(txMutationWithWalFileReference.transactionMutation());
			this.changeObserver.processMutation(engineMutation);

			// create new engine state with the incremented version, and store it in the persistence service
			final ExpandedEngineState nextEngineState = engineStateUpdater.apply(nextStateVersion, this.evita.getEngineState());
			final EngineState theEngineState = nextEngineState.engineState(
				txMutationWithWalFileReference.walFileReference(),
				nextStateVersion
			);
			this.enginePersistenceService.storeEngineState(theEngineState);
			this.lastStoredEngineStateVersion++;
			this.evita.setNextEngineState(nextEngineState);
			// finally, notify the change observer about the new version
			this.changeObserver.notifyVersionPresentInLiveView(nextStateVersion);
		} finally {
			this.engineStateLock.unlock();
		}
	}

	/**
	 * Ensures the provided {@link EngineMutation} does not conflict with previously processed mutations. This method
	 * iterates through the conflict keys of the given mutation and checks if any of them overlap with those of
	 * already processed mutations. If a conflict is detected, a {@link ConflictingEngineMutationException} is thrown.
	 *
	 * @param engineMutation the mutation to be checked for conflicts; must not be null
	 * @throws ConflictingEngineMutationException if the provided mutation conflicts with already processed mutations
	 */
	private void verifyEngineMutationIsNotInConflictWithOthers(@Nonnull EngineMutation<?> engineMutation) {
		engineMutation
			.getConflictKeys()
			.forEach(
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
