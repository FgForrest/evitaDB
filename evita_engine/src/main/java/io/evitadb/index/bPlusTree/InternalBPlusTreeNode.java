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
 * Key-type-agnostic contract of an internal (routing) node, exposing exactly the operations
 * {@link AbstractTransactionalBPlusTree} needs to maintain the tree structure — walking children, dropping a merged-away
 * child, and refreshing a separator key from its child — without ever observing the concrete key array type. The
 * key-typed routing operations (`searchIndex(key)`, `adaptToLeafSplit(key, …)`, `getLeftBoundaryKey()`) stay on the
 * concrete internal-node classes of each tree, where they remain monomorphic and never box.
 *
 * @param <N> the concrete internal-node type (used as its own transactional diff layer and as the sibling type)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface InternalBPlusTreeNode<N extends InternalBPlusTreeNode<N>>
	extends BPlusTreeNode<N> {

	/**
	 * Retrieves the children nodes of the current internal node but only for READ-ONLY purposes.
	 *
	 * @return an array of BPlusTreeNode elements representing the children of the current node.
	 */
	@Nonnull
	BPlusTreeNode<?>[] getChildren();

	/**
	 * Removes a child (and the separator key that introduced it) from this internal node — used after two of its
	 * children have been merged into one, so the now-redundant child slot and its key must be dropped.
	 *
	 * @param keyIndex   the index of the separator key to remove
	 * @param childIndex the index of the child pointer to remove
	 */
	void removeChildOnIndex(int keyIndex, int childIndex);

	/**
	 * Refreshes the separator key stored at the given index so that it again equals the left boundary key of the child
	 * node at that position. Used after a structural change (borrow/merge/split) shifted the boundary of a child.
	 *
	 * @param index the index of the separator key to refresh
	 * @param node  the child node whose left boundary key becomes the new separator value
	 */
	void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node);

}
