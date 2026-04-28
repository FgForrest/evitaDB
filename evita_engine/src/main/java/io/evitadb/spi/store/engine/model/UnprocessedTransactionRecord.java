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

package io.evitadb.spi.store.engine.model;

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Bundles everything a forward-replay caller needs to reconcile a WAL record that was durably committed but whose
 * matching engine state was never written. The record is "unprocessed" from the engine state's point of view — it
 * sits in the WAL past the engine state's `walReference` and has not yet been folded into the bootstrap.
 *
 * Returned by {@code EnginePersistenceService#getUnprocessedTransaction()} when the on-disk shape exhibits the
 * `walVersion == stateVersion + 1` crash-window pattern. After the startup invariant check there is at most one
 * such record per engine; the type is therefore single-valued, not a stream.
 *
 * The three fields travel together because the consumer needs all of them at once:
 *
 * - **version** drives the engine state version bump and feeds drift logging.
 * - **mutation** is what the operator's `replayCompletionState` recomputes from to produce the reconciled
 *   `ExpandedEngineState`.
 * - **walReference** is embedded into the new `EngineState` so the rewritten bootstrap points at the WAL record
 *   that actually committed this transaction.
 *
 * @param version      WAL version of the unprocessed transaction (== `stateVersion + 1` after invariant check)
 * @param mutation     engine-level business mutation to replay
 * @param walReference WAL record reference to embed into the reconciled engine state
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
public record UnprocessedTransactionRecord<T extends LogRecordReference>(
	long version,
	@Nonnull EngineMutation<?> mutation,
	@Nonnull T walReference
) {
}
