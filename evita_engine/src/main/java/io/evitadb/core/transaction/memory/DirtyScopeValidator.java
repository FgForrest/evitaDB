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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.exception.DataStructureCorruptedException;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Contract implemented by any transactional data structure that participates in commit-time structural integrity
 * validation, in two phases:
 *
 * (a) A participant registers opaque *tokens* at its invariant-changing mutation seams via
 * {@link TransactionalLayerMaintainer#registerDirtyScopeToken}. Tokens and participants are both compared by
 * identity.
 *
 * (b) The receiver decides the view: the same {@link #validateDirtyScope(Collection)} method serves the pre-commit
 * pass — called on the live baseline instance, so it validates through the transactional diff-layered view, before
 * the mutations reach the shared write-ahead log — and the post-replay pass — called on the freshly merged copy, so
 * it validates plain merged state, before that copy propagates to the live view.
 *
 * (c) Tokens are hints only, never validated in place — the participant must re-locate the current state they point
 * at. A stale token (e.g. one pointing at a savepoint-reverted layer, or an element merged away since registration)
 * at worst causes a redundant check; it can never cause a false positive.
 *
 * (d) Validation must be read-only with respect to transactional memory — it must not create new diff layers,
 * because the post-replay pass runs with layer creation already disabled.
 *
 * (e) Two-phase responsibility split: the pre-commit pass is orchestrated centrally by
 * {@link TransactionalLayerMaintainer#validateDirtyScopesBeforeCommit()}; the post-replay pass is self-service — a
 * participant that registers tokens MUST pull its own {@link TransactionalLayerMaintainer#getDirtyScopeTokens} inside
 * its {@code createCopyWithMergedTransactionalMemory} and validate the merged copy itself.
 *
 * (f) Violations surface as {@link DataStructureCorruptedException}.
 */
public interface DirtyScopeValidator {

	/**
	 * Validates this participant's dirty scope against the tokens it (or its predecessor, for the post-replay pass)
	 * registered during the transaction. Each implementation defines what a token is and how it is relocated; see the
	 * concrete overrides (e.g. the B+ tree variants) for structure-specific detail.
	 *
	 * @param dirtyScopeTokens the tokens registered for this participant; never validated in place, only used to
	 *                         relocate the current state to check
	 * @throws DataStructureCorruptedException when the relocated state violates the participant's invariants
	 */
	void validateDirtyScope(@Nonnull Collection<Object> dirtyScopeTokens);

}
