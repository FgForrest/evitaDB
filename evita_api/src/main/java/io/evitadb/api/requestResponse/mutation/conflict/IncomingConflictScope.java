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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Pre-indexed view of the conflict keys produced by the transaction currently being committed (the
 * *incoming* transaction), used to decide whether a conflict key produced by an already committed
 * transaction overlaps with it. Replaces the historical pure-equality matcher with proper containment
 * matching along the {@link ConflictKey#parentConflictKey()} ancestry chain.
 *
 * Two conflict keys conflict when one *contains* the other, i.e. one sits on the ancestry chain of the
 * other. Containment is answered in two directions:
 *
 * - **incoming contains committed** — some incoming key is an ancestor-or-self of the committed key.
 *   Answered by walking the committed key's own ancestry chain and testing membership in the raw
 *   incoming key set ({@link #exact}). The self step reproduces the historical equality behaviour, so
 *   nothing that used to conflict stops conflicting.
 * - **committed contains incoming** — the committed key is an ancestor-or-self of some incoming key.
 *   Answered by testing the committed key against {@link #coveredAncestors}, which holds the self and
 *   every ancestor of every incoming key.
 *
 * {@link CatalogConflictKey} sits above {@link CollectionConflictKey} but is not reachable through the
 * field-derivable {@code parentConflictKey()} chain (a collection key carries no catalog name), so
 * catalog-wide containment is handled as a dedicated special case: an incoming catalog key spans the
 * whole catalog ({@link #spansCatalog}), and a committed catalog key contains every incoming key.
 *
 * Instances are immutable and cheap to query; build one per incoming transaction via {@link #of(Set)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class IncomingConflictScope {
	/**
	 * The conflict keys of the incoming transaction, verbatim.
	 */
	@Nonnull private final Set<ConflictKey> exact;
	/**
	 * The self and every ancestor (via {@link ConflictKey#parentConflictKey()}) of every incoming key.
	 */
	@Nonnull private final Set<ConflictKey> coveredAncestors;
	/**
	 * True when the incoming transaction carries a {@link CatalogConflictKey}, i.e. it writes
	 * catalog-wide and therefore contains every conceivable finer key.
	 */
	private final boolean spansCatalog;

	private IncomingConflictScope(
		@Nonnull Set<ConflictKey> exact,
		@Nonnull Set<ConflictKey> coveredAncestors,
		boolean spansCatalog
	) {
		this.exact = exact;
		this.coveredAncestors = coveredAncestors;
		this.spansCatalog = spansCatalog;
	}

	/**
	 * Builds a scope from the incoming transaction's conflict keys, pre-computing the ancestor closure
	 * needed to answer containment queries in constant time per committed key.
	 *
	 * @param incomingKeys the conflict keys produced by the transaction being committed
	 * @return an immutable, queryable scope
	 */
	@Nonnull
	public static IncomingConflictScope of(@Nonnull Set<ConflictKey> incomingKeys) {
		// pre-size for the self plus a short ancestry chain per incoming key (attribute → entity →
		// collection is the deepest common chain), avoiding rehashing on the common case
		final Set<ConflictKey> coveredAncestors = CollectionUtils.createHashSet(incomingKeys.size() * 4);
		boolean spansCatalog = false;
		for (ConflictKey incomingKey : incomingKeys) {
			if (incomingKey instanceof CatalogConflictKey) {
				spansCatalog = true;
			}
			for (ConflictKey node = incomingKey; node != null; node = node.parentConflictKey()) {
				coveredAncestors.add(node);
			}
		}
		return new IncomingConflictScope(incomingKeys, coveredAncestors, spansCatalog);
	}

	/**
	 * Decides whether an absolute (non-commutative) conflict key produced by an already committed
	 * transaction conflicts with this incoming scope. Applies full bidirectional containment.
	 *
	 * @param committedKey the conflict key of the already committed transaction
	 * @return true when the two transactions' write scopes overlap
	 */
	public boolean conflictsWithAbsolute(@Nonnull ConflictKey committedKey) {
		// incoming wrote catalog-wide → it contains every finer committed key
		if (this.spansCatalog) {
			return true;
		}
		// committed wrote catalog-wide → it contains every incoming key (guard against the empty
		// incoming set, which cannot overlap with anything)
		if (committedKey instanceof CatalogConflictKey) {
			return !this.exact.isEmpty();
		}
		// committed contains incoming: the committed key is an ancestor-or-self of some incoming key
		if (this.coveredAncestors.contains(committedKey)) {
			return true;
		}
		// incoming contains committed: some incoming key is an ancestor-or-self of the committed key
		// (the self step at node == committedKey reproduces the historical equality match)
		for (ConflictKey node = committedKey; node != null; node = node.parentConflictKey()) {
			if (this.exact.contains(node)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Decides whether a commutative (range-constrained delta) conflict key produced by an already
	 * committed transaction conflicts with an *absolute* write in this incoming scope — the
	 * delete/set-vs-delta case the commutative accumulation path cannot express.
	 *
	 * The probe deliberately starts at the committed key's parent (skipping the self step): two
	 * deltas of the same key are equal at the self level, yet they must *commute* rather than
	 * conflict, so self-equality must not trip a conflict here. Walking the absolute ancestor chain
	 * (attribute → entity → collection) only matches incoming absolute keys, never an incoming delta
	 * of the same key. Direction "committed contains incoming" is omitted because a delta key is a
	 * leaf and never an ancestor of any key.
	 *
	 * @param committedKey the commutative conflict key of the already committed transaction
	 * @return true when an incoming absolute (or catalog-wide) write overlaps the committed delta
	 */
	public boolean conflictsWithCommutative(@Nonnull ConflictKey committedKey) {
		// incoming wrote catalog-wide → no concurrent delta can be merged into it
		if (this.spansCatalog) {
			return true;
		}
		for (ConflictKey node = committedKey.parentConflictKey(); node != null; node = node.parentConflictKey()) {
			if (this.exact.contains(node)) {
				return true;
			}
		}
		return false;
	}

}
