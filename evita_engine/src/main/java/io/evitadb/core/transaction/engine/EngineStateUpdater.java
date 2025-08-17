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


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.function.LongObjectFunction;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Functional interface used by {@link io.evitadb.core.transaction.engine.EngineTransactionManager}
 * to transition the {@link io.evitadb.core.ExpandedEngineState} during engine mutations.
 *
 * It extends {@link java.util.function.UnaryOperator} and therefore implements one method
 * {@code apply(ExpandedEngineState)} that must return the next state. Implementations are executed
 * in two distinct phases:
 *
 * 1. Transition phase (pre-mutation):
 *    - Called by the transaction manager before the heavy work begins.
 *    - Typically adjusts in-memory state to reflect a transient status (e.g., catalog is
 *      being created, duplicated, renamed, etc.).
 *
 * 2. Completion phase (post-mutation):
 *    - Called after the mutation has finished successfully.
 *    - The transaction manager writes the WAL entry and persists the returned engine state
 *      as the new authoritative state and publishes the new version.
 *
 * The updater carries contextual metadata so the transaction manager can store correct WAL records
 * and notify observers:
 * - {@link #getTransactionId()} — unique id of the enclosing engine transaction.
 * - {@link #getEngineMutation()} — the concrete engine mutation being processed.
 *
 * See the JavaDoc in {@link io.evitadb.core.transaction.engine.EngineTransactionManager} for details
 * about locking and ordering guarantees.
 *
 * Note: The implementations are expected to be side-effect free except for computing the next state.
 * The transaction manager is responsible for persistence, notifications and synchronization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EngineStateUpdater extends LongObjectFunction<ExpandedEngineState, ExpandedEngineState> {

	/**
	 * Returns unique identifier of the engine transaction this updater belongs to.
	 *
	 * Used by {@link io.evitadb.core.transaction.engine.EngineTransactionManager} when appending the
	 * WAL record and correlating notifications with the transaction. The value must be stable for the
	 * lifetime of the updater instance.
	 *
	 * @return non-null transaction identifier
	 */
	@Nonnull
	UUID getTransactionId();

	/**
	 * Returns the concrete {@link io.evitadb.api.requestResponse.mutation.EngineMutation} that this
	 * updater is associated with.
	 *
	 * {@link io.evitadb.core.transaction.engine.EngineTransactionManager} uses this information when:
	 * - writing the WAL record
	 * - notifying {@link io.evitadb.core.cdc.SystemChangeObserver}
	 *
	 * Implementations should return the same (effectively immutable) mutation instance that the operator
	 * executes.
	 *
	 * @return non-null engine mutation associated with this updater
	 */
	@Nonnull
	EngineMutation<?> getEngineMutation();

}
