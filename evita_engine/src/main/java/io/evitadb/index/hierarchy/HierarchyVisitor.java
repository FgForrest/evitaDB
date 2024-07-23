/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.hierarchy;

import javax.annotation.Nonnull;

/**
 * HierarchyVisitor interface allows to {@link HierarchyIndex#traverseHierarchy(HierarchyVisitor, int...) traverse}
 * hierarchical structure in {@link HierarchyIndex} data structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface HierarchyVisitor {

	/**
	 * Method is called on each node in the hierarchical tree of the {@link HierarchyIndex}. The visitor itself controls
	 * the traversal by calling `traverser.run()` at the place it needs to be called on current `node` children.
	 * ChildrenTraverser might be also completely ignored when subtree should not be traversed at all.
	 *
	 * @param node      the visited hierarchy node
	 * @param level     the depth level of visited hierarchy node
	 * @param distance  the distance from the top node the visitor started to traversing
	 * @param traverser the lambda to traverse hierarchy of the visited hierarchy node
	 */
	void visit(@Nonnull HierarchyNode node, int level, int distance, @Nonnull Runnable traverser);

}
