
/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spi.store.engine;

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.UnprocessedTransactionRecord;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * This interface represents a link between {@link EngineState} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting engine state to/from durable
 * storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface EnginePersistenceService<T extends LogRecordReference>
	extends PersistenceService, CatalogFolderOperations {
	String ENGINE_NAME = "evitaDB";

	/**
	 * Returns name of the bootstrap file that contains lead information to fetching the engine header in fixed record
	 * size format. This file can be traversed by jumping on expected offsets.
	 */
	@Nonnull
	static String getBootstrapFileName() {
		return ENGINE_NAME + BOOT_FILE_SUFFIX;
	}

	/**
	 * Returns name of the Write-Ahead-Log file that contains all mutations that were not yet propagated to the boot file.
	 *
	 * @param fileIndex index of the WAL file
	 * @return name of the WAL file
	 */
	@Nonnull
	static String getWalFileName(int fileIndex) {
		return ENGINE_NAME + '_' + fileIndex + WAL_FILE_SUFFIX;
	}

	/**
	 * Returns the last version that was written to the persistent storage.
	 *
	 * @return the last version that was written to the persistent storage
	 */
	long getVersion();

	/**
	 * Returns {@link EngineState} stored in current boot file.
	 *
	 * @return the state of the engine
	 */
	@Nonnull
	EngineState<T> getEngineState();

	/**
	 * Returns the divergence between the catalog inventory recorded in the persisted engine state and the catalog
	 * inventory the backing store currently reports, detected during this service's construction.
	 *
	 * The persistence service does **not** apply this divergence — it is the caller's responsibility (specifically
	 * `Evita`, after `EngineTransactionManager` is wired) to drain it through WAL-backed engine mutations so the
	 * reconciliation is observable and the WAL-first invariant is preserved. Implementations must compute the
	 * divergence as a pure value at boot and return the same instance on every call (idempotent — the engine state
	 * advances through `appendWalAndStoreState` once mutations are applied, so a subsequent boot will detect no
	 * remaining divergence). Returns {@link CatalogInventoryDivergence#EMPTY} for fresh engines or when there is
	 * nothing to reconcile.
	 *
	 * @return the divergence; never null, possibly {@link CatalogInventoryDivergence#EMPTY}
	 */
	@Nonnull
	CatalogInventoryDivergence getPendingCatalogInventoryDivergence();

	/**
	 * Stores {@link EngineState} stored in current boot file.
	 *
	 * @param engineState the state of the engine to store
	 */
	void storeEngineState(@Nonnull EngineState<T> engineState);

	/**
	 * Fused WAL-first commit: appends the transaction mutation to the WAL and writes the matching engine state in
	 * a single indivisible critical section.
	 *
	 * This method exists to make WAL-first ordering architecturally unforgeable at the persistence layer. Callers
	 * must no longer perform `appendWal` + `storeEngineState` back-to-back — a refactoring mistake between the two
	 * calls could advance engine state without a matching WAL entry and silently violate the startup invariant.
	 *
	 * **Atomicity guarantees.** The implementation takes a single internal WAL lock for
	 * the whole operation and:
	 *
	 * 1. validates that `version` equals `getEngineState().version() + 1`;
	 * 2. appends the mutation to the WAL;
	 * 3. invokes `stateFactory` with the fresh `TransactionMutationWithWalReference` so
	 *    the factory can embed the WAL reference into the returned `EngineState`;
	 * 4. writes the returned engine state to the bootstrap file;
	 * 5. updates the in-memory engine state.
	 *
	 * On a successful return, both the WAL `getLastVersionInMutationStream()` and the
	 * engine state `getEngineState().version()` equal `version`. The D.1 startup
	 * invariant is therefore satisfied by construction.
	 *
	 * **Failure semantics.** If the factory throws, the implementation rolls the WAL
	 * append back (Option A — all-or-nothing) so that the persistence service is left
	 * in the same observable state as before the call (or at worst in a state from
	 * which a clean reboot succeeds). The throwable is propagated to the caller.
	 *
	 * @param version        the new engine state version; must equal the current
	 *                       engine state version incremented by one
	 * @param transactionId  unique identifier of the transaction being committed
	 * @param mutation       engine-level mutation to append to the WAL
	 * @param stateFactory   function that receives the newly written
	 *                       `TransactionMutationWithWalReference` and must return the
	 *                       new engine state at `version` embedding the supplied WAL
	 *                       reference
	 * @return the `TransactionMutationWithWalReference` that identifies the WAL record
	 *         written for this transaction
	 */
	@Nonnull
	TransactionMutationWithWalReference appendWalAndStoreState(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation,
		@Nonnull Function<TransactionMutationWithWalReference, EngineState<T>> stateFactory
	);

	/**
	 * Retrieves the first non-processed transaction in the WAL.
	 *
	 * @param version version of the engine
	 * @return the first non-processed transaction in the WAL
	 */
	@Nonnull
	Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(long version);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * @param version version of the engine to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<EngineMutation<?>> getCommittedMutationStream(long version);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine state to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * @param version version of the engine state to start the stream with, if null is provided then the stream will
	 *                start with the last transaction in the WAL
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version);

	/**
	 * Truncates the log file to the given {@link LogRecordReference}. This method synchronizes the log file contents
	 * to be on par with current engine state.
	 *
	 * @param walReference the reference to the log file that should be truncated to
	 */
	void truncateWriteAheadLog(@Nonnull T walReference);

	/**
	 * Rewrites the bootstrap file so that engine state version catches up with a WAL entry that was durably committed
	 * but whose matching bootstrap rewrite never completed.
	 *
	 * Only callable during forward WAL replay. The WAL must already contain a committed mutation at
	 * `newState.version()`; this method just reconciles the bootstrap file with that committed entry without
	 * appending to WAL.
	 *
	 * Preconditions:
	 *
	 * - `newState.version() == getEngineState().version() + 1`
	 * - `getLastVersionInMutationStream() == newState.version()`
	 *
	 * Both preconditions are asserted by the implementation via `Assert.isPremiseValid`.
	 *
	 * This method must never be used on the normal commit path — use `appendWalAndStoreState`
	 * instead, which fuses the WAL append and the bootstrap rewrite into a single critical section.
	 *
	 * @param newState engine state at the next version, embedding the WAL reference that corresponds
	 *                 to the already-committed WAL entry; must not be null
	 */
	void rewriteEngineStateAtNextVersion(@Nonnull EngineState<T> newState);

	/**
	 * Returns the single transaction record sitting in the WAL past the engine state's `walReference`. The
	 * persistence service computes the result as `mutationLog.getFirstNonProcessedTransaction(stateWalRef)` plus
	 * the corresponding engine mutation body, fused into one read so callers get the complete forward-replay
	 * payload (`version`, `mutation`, `walReference`) in a single round-trip.
	 *
	 * After the startup invariant check there is at most one such record per engine — see the
	 * `walVersion == stateVersion + 1` crash-window contract enforced by `DefaultEnginePersistenceService` at
	 * construction time.
	 *
	 * Contract:
	 *
	 * - Returns `Optional.empty()` only when there is genuinely no work: the WAL has not been initialised yet,
	 *   or the engine state's `walReference` already covers everything in the WAL.
	 * - Throws `WriteAheadLogCorruptedException` (with `WalKind.ENGINE`) when a transaction header is found at
	 *   some version V but its engine-mutation body is missing — either because we crossed into the next
	 *   transaction's header without seeing a body, or because the mutation stream ended mid-record. Both
	 *   conditions violate the structural invariant of the engine WAL (every committed `TransactionMutation`
	 *   header must be followed by exactly one engine-mutation body).
	 *
	 * Used by `EngineTransactionManager#replayCrashedMutationIfNeeded` to recover the crashed mutation, run its
	 * operator's `replayCompletionState`, and persist the reconciled engine state via
	 * `rewriteEngineStateAtNextVersion`.
	 *
	 * @return the unprocessed transaction record or `Optional.empty()` when nothing is past the engine state
	 * @throws io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException when the engine WAL
	 *         contains a transaction header without a matching engine-mutation body
	 */
	@Nonnull
	Optional<UnprocessedTransactionRecord<T>> getUnprocessedTransaction();

	/**
	 * Retrieves the last engine state version written in the WAL stream.
	 *
	 * @return the last engine state version written in the WAL stream
	 */
	long getLastVersionInMutationStream();

	/**
	 * Method closes this persistence service.
	 */
	@Override
	void close();
}
