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

/**
 * Marker exposing the {@code int} left boundary key of a node in an {@code int}-routed B+ tree (one whose internal
 * spine separates children by a primitive {@code int} key). It is implemented by every node — internal and leaf — of
 * the {@link TransactionalIntToLongBPlusTree} and {@link TransactionalElementBPlusTree} families, which lets
 * {@link AbstractIntKeyedInternalNode#leftBoundaryKeyOf(BPlusTreeNode)} read a child's boundary key through a single
 * cast without descending into the typed node class.
 *
 * The accessor lives on this dedicated marker rather than on the key-agnostic {@link BPlusTreeNode} SPI on purpose:
 * the shared SPI must never expose a typed key, so primitive trees never box. Trees that route on a wider key (e.g.
 * {@code long[]} / {@code Object[]} spines) do not implement this marker.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
interface IntBoundaryKeyedNode {

	/**
	 * Retrieves the left boundary (smallest) key contained within the node.
	 *
	 * @return the left boundary key of the node.
	 */
	int getLeftBoundaryKey();

}
