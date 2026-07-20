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
 * (a) A participant registers a *probe key* at its invariant-changing mutation seams via
 * {@link TransactionalLayerMaintainer#registerDirtyScopeToken}. Participants are compared by identity; probe keys by
 * value, so an unchanged boundary touched by successive ops registers once.
 *
 * (b) The receiver decides the view: the same {@link #validateDirtyScope(Collection)} method serves the pre-commit
 * pass — called on the live baseline instance, so it validates through the transactional diff-layered view, before
 * the mutations reach the shared write-ahead log — and the post-replay pass — called on the freshly merged copy, so
 * it validates plain merged state, before that copy propagates to the live view.
 *
 * (c) Keys are relocation hints only — the participant descends its current tree to the leaf the key routes to and
 * validates that leaf's own re-derived boundaries. A stale key (e.g. one captured before a savepoint-reverted layer,
 * or before the leaf it named was merged away) at worst routes the descent to a real current leaf that is checked
 * redundantly; a sound tree cannot fail that check, so it can never cause a false positive. Registering keys rather
 * than node objects is deliberate: it keeps no leaf, array or element pinned to the registry until commit, and makes
 * the "registered token whose backing array was blanked in place by an aliasing holder" failure class
 * unrepresentable.
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
	 * Validates this participant's dirty scope against the probe keys it (or its predecessor, for the post-replay pass)
	 * registered during the transaction. Each implementation defines its key type and how it relocates a leaf by it; see
	 * the concrete overrides (e.g. the B+ tree variants) for structure-specific detail.
	 *
	 * @param dirtyScopeTokens the probe keys registered for this participant; never validated in place, only used to
	 *                         relocate the current leaf to check
	 * @throws DataStructureCorruptedException when the relocated state violates the participant's invariants
	 */
	void validateDirtyScope(@Nonnull Collection<Object> dirtyScopeTokens);

}
