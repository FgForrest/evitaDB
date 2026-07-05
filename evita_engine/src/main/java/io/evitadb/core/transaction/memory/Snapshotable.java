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

package io.evitadb.core.transaction.memory;

import javax.annotation.Nonnull;

/**
 * SPI implemented by a transactional diff-layer object (a `*Changes` instance produced by
 * {@link TransactionalLayerCreator#createLayer()}) that supports capturing and restoring its mutable state. It is the
 * rollback counterpart of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory} (which is the
 * commit-side merge): {@link #snapshot()} captures the layer's current diff state into an opaque memento, and
 * {@link #restore(Object)} resets the layer back to exactly that captured state, undoing every modification made in
 * between.
 *
 * It enables a savepoint over a {@link TransactionalLayerMaintainer} (see its `openSavepoint` / `rollbackSavepoint` /
 * `commitSavepoint`) to revert a single failed entity mutation while the surrounding transaction keeps running — the
 * partial-rollback capability required by client batch upserts where one entity may legitimately fail and be skipped.
 *
 * Two invariants every implementation must uphold:
 *
 * 1. **Memento independence.** {@link #snapshot()} must copy the layer's mutable containers deeply enough that a later
 *    mutation of the layer cannot mutate the memento, and {@link #restore(Object)} must copy *out of* the memento (or
 *    the memento must be documented single-use), so that the same memento can be restored more than once and remains a
 *    faithful representation of the snapshot moment. Primitive / immutable-value fields are copied by value and need no
 *    defensive cloning.
 *
 * 2. **Nested-layer boundary.** A layer's memento captures only *its own* diff. Producer or element *values* the layer
 *    holds (e.g. nested {@link TransactionalLayerProducer} instances stored as map values or array elements) are
 *    captured by reference only — their internal mutable state is the responsibility of *their own* `Snapshotable`,
 *    coordinated by the maintainer-level savepoint that snapshots the entire reachable layer forest. An implementation
 *    must therefore never deep-copy such values, and never reach into their internal state on restore.
 *
 * Implementing this interface is opt-in: it is intentionally kept independent of {@link TransactionalLayerProducer}
 * because not every producer's layer is a genuine accumulating diff (some are degenerate single-value layers, others
 * are rebuildable derived caches whose memento is a cheap invalidation rather than a copy).
 *
 * @param <M> the memento type — a per-implementation, immutable carrier of exactly that layer's mutable state
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface Snapshotable<M> {

	/**
	 * Captures the current mutable state of this diff layer into an opaque memento that is independent of any
	 * subsequent mutation of the layer (see the memento-independence invariant in the type JavaDoc).
	 *
	 * @return a memento that {@link #restore(Object)} can later use to reset this layer to its current state
	 */
	@Nonnull
	M snapshot();

	/**
	 * Resets this diff layer back to the exact state captured by the given memento, undoing every modification made
	 * since the memento was produced. Implementations must copy state out of the memento (or treat the memento as
	 * single-use) so repeated restores from the same memento are safe.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	void restore(@Nonnull M memento);

	/**
	 * Releases a memento whose savepoint has been CLOSED — either committed (the changes made while the savepoint was
	 * open are kept) or rolled back (after {@link #restore(Object)} has already rewound the layer). In both cases any
	 * state the layer stashed to support a potential {@link #restore(Object)} of this memento can be discarded. Called
	 * by {@link TransactionalLayerMaintainer#commitSavepoint} and {@link TransactionalLayerMaintainer#rollbackSavepoint}
	 * for every layer touched while the savepoint was open.
	 *
	 * The default is a no-op: it suits every implementation whose {@link #snapshot()} produces a fully self-contained
	 * memento and keeps no per-savepoint scratch state on the layer. Implementations that keep such scratch state — e.g.
	 * an undo journal recording inverse operations while the savepoint is open (see {@link UndoJournal}) — override this
	 * to drain that state when the savepoint closes, so it does not accumulate for the rest of the transaction.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer, whose savepoint is now
	 *                closed
	 */
	default void releaseMemento(@Nonnull M memento) {
		// no-op by default: a self-contained memento leaves no per-savepoint scratch state to drain
	}

}
