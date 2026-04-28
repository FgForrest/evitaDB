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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.core.Evita;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.EngineStateUpdater;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Operator that executes a specific {@link io.evitadb.api.requestResponse.mutation.EngineMutation}
 * and wires it into the two-phase engine state update performed by
 * {@link io.evitadb.core.transaction.engine.EngineTransactionManager}.
 *
 * Responsibilities:
 * - Provide a human-readable operation name for progress reporting.
 * - Execute the mutation logic and return a {@link io.evitadb.api.requestResponse.progress.ProgressingFuture}
 *   that completes with the mutation result.
 * - Call the supplied engine state updaters at appropriate points:
 *   - transition updater is used before the heavy work starts to update in-memory state
 *     (e.g., mark catalog as transitioning) so other mutations can see the transient state.
 *   - completion updater is used after the mutation finishes to persist persistent log and the new engine state,
 *     and to publish the new version.
 *
 * Each operator implementation is responsible only for the mutation-specific work. Concurrency control,
 * conflict detection and WAL ordering are handled by EngineTransactionManager.
 *
 * Type parameters:
 * - S: result type returned from the mutation.
 * - T: concrete type of {@link EngineMutation} supported by the operator.
 *
 * See also {@link io.evitadb.core.transaction.engine.EngineTransactionManager} for details on
 * how operators are orchestrated and how state updaters participate in the two-phase update.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EngineMutationOperator<S, T extends EngineMutation<S>> {

	/**
	 * Returns a short, human‑readable name of the operation represented by the provided mutation.
	 *
	 * The returned value is used by {@link io.evitadb.core.transaction.engine.EngineTransactionManager}
	 * to label {@link io.evitadb.api.requestResponse.progress.Progress} and related progress records,
	 * so keep it concise and in present tense (e.g., "Create catalog `demo`").
	 *
	 * @param engineMutation non-null mutation instance; must not be modified by the operator
	 * @return non-null operation name suitable for progress reporting
	 */
	@Nonnull
	String getOperationName(@Nonnull T engineMutation);

	/**
	 * Executes the provided engine {@code mutation} and wires its lifecycle into the
	 * two‑phase engine state update.
	 *
	 * Contract:
	 * - The operator may perform long‑running work asynchronously and must return a
	 *   {@link io.evitadb.api.requestResponse.progress.ProgressingFuture} that completes with the
	 *   mutation result.
	 * - The {@code transitionEngineStateUpdater} must be invoked exactly once before the heavy work
	 *   starts to update in‑memory state (pre‑mutation phase).
	 * - The {@code completionEngineStateUpdater} must be invoked exactly once after the mutation
	 *   finishes successfully so the transaction manager can append persistent log, persist the new state and
	 *   publish the new version (post‑mutation phase).
	 *
	 * Notes:
	 * - Both updaters must carry the same {@code transactionId} and {@code mutation} context they are
	 *   associated with.
	 * - Implementations should avoid side effects outside of Evita and state updaters.
	 *
	 * @param transactionId unique id of the encompassing engine transaction; non-null
	 * @param mutation concrete mutation to execute; non-null
	 * @param evita Evita instance providing access to engine state and services; non-null
	 * @param transitionEngineStateUpdater consumer that will be called with an {@link EngineStateUpdater}
	 *                                    to perform pre‑mutation state transition; non-null
	 * @param completionEngineStateUpdater consumer that will be called with an {@link EngineStateUpdater}
	 *                                     to perform post‑mutation completion and persistence; non-null
	 * @return non-null future that completes with the mutation result or fails exceptionally
	 */
	@Nonnull
	ProgressingFuture<S> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull T mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	);

	/**
	 * Pure re-computation of the completion-phase `ExpandedEngineState` for forward WAL replay.
	 *
	 * Called by `EngineTransactionManager.replayCrashedMutationIfNeeded` to reconcile the engine
	 * state when startup observes `walV == stateV + 1` — i.e., an OS-level crash happened inside
	 * the fused critical section of `appendWalAndStoreState` between the WAL append and the
	 * bootstrap rewrite. The WAL entry is durable and the work-phase side effects (folder
	 * creation, catalog instance build, etc.) already happened in the original crashed run; this
	 * method just re-derives the completion-phase engine-state snapshot without re-running those
	 * side effects.
	 *
	 * Contract:
	 *
	 * - Implementations **must not** perform writes to disk, emit CDC/metric events, open new
	 *   catalog instances, or close existing ones. The work phase already happened in the
	 *   crashed run, so duplicating those side effects would either corrupt on-disk state or
	 *   double-emit observability records.
	 * - Implementations **may** apply *idempotent* in-memory toggles to already-open Catalog
	 *   instances when the toggle is the only way to make the live in-memory representation
	 *   consistent with the replayed engine-state snapshot (e.g., flipping a `readOnly` flag
	 *   that the original work phase already flipped on the live instance before the crash).
	 *   Such toggles must be no-ops when re-applied, must not allocate new lifecycle resources
	 *   (no opens/closes), and must not produce externally observable side effects beyond the
	 *   in-memory flag flip.
	 * - Implementations **may** read already-persisted state (e.g., load a catalog instance
	 *   from a folder that is known to exist on disk because the work phase wrote it before the
	 *   crash).
	 * - The primary purpose remains rebuilding the `ExpandedEngineState` that the original
	 *   `applyMutation` completion updater would have produced.
	 * - Returning `Optional.empty()` (the default) signals that this mutation type does not
	 *   support forward replay safely. The transaction manager will then log a loud error and
	 *   wedge the engine rather than silently proceeding — this is intentional, because silent
	 *   corruption is worse than an operator-visible failure.
	 *
	 * @param mutation       the concrete engine mutation committed to the WAL
	 * @param targetVersion  the engine state version to apply (equals the WAL entry version)
	 * @param currentState   the current in-memory `ExpandedEngineState` (at `targetVersion - 1`)
	 * @param evita          the owning Evita instance for read-only lookups
	 * @return the reconciled `ExpandedEngineState` at `targetVersion`, or `Optional.empty()` if
	 *         this operator does not support forward replay
	 */
	@Nonnull
	default Optional<ExpandedEngineState> replayCompletionState(
		@Nonnull T mutation,
		long targetVersion,
		@Nonnull ExpandedEngineState currentState,
		@Nonnull Evita evita
	) {
		return Optional.empty();
	}

}
