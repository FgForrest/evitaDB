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

import io.evitadb.core.transaction.memory.TransactionalLayerProducer;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Common, key-type-agnostic contract of a node in any B+ tree of the family. It is the seam through which
 * {@link AbstractTransactionalBPlusTree} drives the structure-maintaining algorithms (descent, split, merge, borrow)
 * without ever observing the concrete key array type ({@code long[]}, {@code int[]}, {@code Object[]}, …). The typed
 * key access (`getKeys()`, `getLeftBoundaryKey()`, `searchIndex(key)`) deliberately stays on the concrete node
 * classes of each tree so that primitive trees never box their keys.
 *
 * Each node uses itself as its own transactional memory layer — hence the self-recursive
 * {@code N extends BPlusTreeNode<N>} bound and the {@link TransactionalLayerProducer} super-interface where both the
 * diff layer type and the produced type are {@code N}.
 *
 * @param <N> the concrete node type, used both as the transactional diff layer and the sibling type in
 *            borrow/merge operations
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface BPlusTreeNode<N extends BPlusTreeNode<N>>
	extends
	TransactionalLayerProducer<N, N>,
	Serializable {

	/**
	 * Retrieves the peek index (last usable value) of the B+ Tree node's values / children.
	 *
	 * @return the peek value of the node, indicating the last usable index in the node's values / children array.
	 */
	int getPeek();

	/**
	 * Sets the peek index of the B+ Tree node. The peek index indicates the last
	 * usable position in the node's values or children array.
	 *
	 * @param peek the new peek index to set for the node
	 */
	void setPeek(int peek);

	/**
	 * Returns number of values in this node - i.e. peek + 1.
	 *
	 * @return number of values in this node
	 */
	default int size() {
		return getPeek() + 1;
	}

	/**
	 * Returns number of keys in this node - which differs between leaf and internal nodes.
	 *
	 * @return number of keys in this node
	 */
	int keyCount();

	/**
	 * Checks if the current B+ Tree leaf node is full, meaning all available slots are occupied.
	 *
	 * @return true if the node is full, false otherwise.
	 */
	boolean isFull();

	/**
	 * Converts the B+ Tree Node to a string representation with a specified level and indentation.
	 *
	 * @param sb           the StringBuilder to which the string representation will be appended.
	 * @param level        the current level of the node in the B+ Tree hierarchy.
	 * @param indentSpaces the number of spaces to use for indenting the string representation.
	 */
	void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces);

	/**
	 * Steals a specified number of values from the end of the left sibling node.
	 *
	 * @param numberOfTailValues the number of values to steal from the left sibling node.
	 * @param previousNode       the left sibling node from which to steal values.
	 */
	void stealFromLeft(int numberOfTailValues, @Nonnull N previousNode);

	/**
	 * Steals a specified number of values from the start of the right sibling node.
	 *
	 * @param numberOfHeadValues the number of values to steal from the right sibling node.
	 * @param nextNode           the right sibling node from which to steal values.
	 */
	void stealFromRight(int numberOfHeadValues, @Nonnull N nextNode);

	/**
	 * Merges the current leaf node with the left sibling leaf node.
	 */
	void mergeWithLeft(@Nonnull N previousNode);

	/**
	 * Merges the current leaf node with the right sibling leaf node.
	 */
	void mergeWithRight(@Nonnull N nextNode);

}
