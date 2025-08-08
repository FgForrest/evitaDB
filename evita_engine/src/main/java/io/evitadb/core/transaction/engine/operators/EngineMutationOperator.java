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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.engine.EngineStateUpdater;

import javax.annotation.Nonnull;
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

}
