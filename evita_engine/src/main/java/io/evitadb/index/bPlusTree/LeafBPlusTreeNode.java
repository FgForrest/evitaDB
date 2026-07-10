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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;

/**
 * Key-type-agnostic contract of a leaf (value-holding) node, exposing exactly the leaf-page bookkeeping
 * {@link AbstractTransactionalBPlusTree} needs to drive the granular per-leaf persistence handshake — the leaf's logical
 * page sequence, its transaction-aware change-detection flag, and a value-erased read of its payload — without ever
 * observing the concrete key array type. The key-typed leaf operations (`getKeys()`, `getValue(key)`,
 * `getLeftBoundaryKey()`) stay on the concrete leaf classes of each tree, where they remain monomorphic and never box.
 *
 * Only the variants that actually page (today {@link TransactionalLongBPlusTree}, consumed by the range index) implement
 * this contract; the value-erased {@link #getValueArray()} lets the shared {@link LeafPageHandle} emission view
 * materialize a page's contents from a leaf held behind this SPI.
 *
 * @param <N> the concrete leaf-node type (used as its own transactional diff layer and as the sibling type)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface LeafBPlusTreeNode<N extends LeafBPlusTreeNode<N>>
	extends BPlusTreeNode<N> {

	/**
	 * Returns the leaf's logical persistence page sequence, or {@link AbstractTransactionalBPlusTree#UNASSIGNED_PAGE_SEQUENCE}
	 * when the leaf has not been assigned a page yet (a split-born or freshly created leaf). NOT transactional — it is
	 * structural bookkeeping carried across the commit-merge.
	 *
	 * @return the page sequence or {@link AbstractTransactionalBPlusTree#UNASSIGNED_PAGE_SEQUENCE}
	 */
	int getPageSequence();

	/**
	 * Stamps this leaf's logical persistence page sequence. Direct (non-transactional) write — the stamp lands on the
	 * live (source) node so the commit-merge carries it forward into the committed tree.
	 *
	 * @param pageSequence the page sequence to assign
	 */
	void setPageSequence(int pageSequence);

	/**
	 * Returns the change-detection flag, read transaction-aware (the in-flight transaction's layer value when one
	 * exists, otherwise the committed value): `true` when this leaf has been mutated since the last flush emitted its
	 * page. It is the deterministic replacement for a content hash — every mutation site sets it, so a real change can
	 * never be suppressed.
	 *
	 * @return true when the leaf must be (re)written
	 */
	boolean isDirty();

	/**
	 * Clears the change-detection flag once the emitter has collected this leaf's page for the current flush.
	 * Transaction-aware: clears the layer's flag in a transaction (the merge produces a clean committed instance
	 * regardless), otherwise the committed instance in place.
	 */
	void clearDirty();

	/**
	 * Returns the leaf's values, in ascending key order, as a value-erased array — the read-your-writes (transaction-aware)
	 * contents the {@link LeafPageHandle} captures to materialize the page. Only the first {@link #getPeek()}+1 elements
	 * are occupied. The returned array is the live backing array (not a copy); callers must not mutate it.
	 *
	 * @return the value array, value-erased to {@code Object[]}
	 */
	@Nonnull
	Object[] getValueArray();

}
