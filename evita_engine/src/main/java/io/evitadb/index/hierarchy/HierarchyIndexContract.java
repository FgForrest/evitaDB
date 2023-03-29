/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.hierarchy;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * HierarchyIndexContract describes the API of {@link HierarchyIndex} that maintains data structures for fast accessing
 * hierarchical structures to return parent nodes or child nodes of the entity.
 *
 * Purpose of this contract interface is to ease using {@link @lombok.experimental.Delegate} annotation
 * in {@link io.evitadb.index.EntityIndex} and minimize the amount of the code in this complex class by automatically
 * delegating all {@link HierarchyIndexContract} methods to the {@link HierarchyIndex} implementation that is part
 * of this index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface HierarchyIndexContract {

	/**
	 * Method indexes `entityPrimaryKey` placement in the hierarchy tree using information about its `parentPrimaryKey`
	 * and `orderAmongSiblings`.
	 *
	 * It allows out-of-order hierarchy tree indexing where children can be indexed before their parent. Such entities
	 * are collected in the {@link #getOrphanHierarchyNodes()} array until their parent dependency is fulfilled. When the time comes they
	 * are moved from {@link #getOrphanHierarchyNodes()} to {@link #getRootHierarchyNodes()} (recursively).
	 */
	void setHierarchyFor(int entityPrimaryKey, @Nullable Integer parentPrimaryKey, int orderAmongSiblings);

	/**
	 * Method removes information about `entityPrimaryKey` placement from the index. It doesn't matter whether the key
	 * is inside {@link #getOrphanHierarchyNodes()} or places in the living tree {@link #getRootHierarchyNodes()}.
	 */
	void removeHierarchyFor(int entityPrimaryKey);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRoot(int...)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromRootFormula(@Nullable int... excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from all root nodes excluding the set of nodes and their sub-trees
	 * specified in `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromRoot(@Nullable int... excludedNodeTrees);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRootDownTo(int, int...)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromRootDownToFormula(int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns nodes that are reachable from all root nodes down to specified number of `levels`, excluding
	 * the set of nodes and their sub-trees specified in `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromRootDownTo(int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItself(int, int...)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself),
	 * excluding the set of nodes and their sub-trees specified in `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItselfDownTo(int, int, int...)}
	 * wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentIncludingItselfDownToFormula(int parentNode, int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself)
	 * down to specified number of `levels`, excluding the set of nodes and their sub-trees specified in
	 * `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParent(int, int...)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentFormula(int parentNode, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself),
	 * excluding the set of nodes and their sub-trees specified in `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParent(int parentNode, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentDownTo(int, int, int...)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentDownToFormula(int parentNode, int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself)
	 * down to specified number of `levels`, excluding the set of nodes and their sub-trees specified in
	 * `excludedNodeTrees` parameter.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels, @Nullable int... excludedNodeTrees);

	/**
	 * Method returns primary keys of all nodes from root node to `theNode` traversing entire hierarchy.
	 */
	@Nonnull
	Integer[] listHierarchyNodesFromRootToTheNode(int theNode);

	/**
	 * Method returns primary keys of all nodes from root node to `theNode` traversing entire hierarchy. The result
	 * also contains `theNode` as the last element of the array.
	 */
	@Nonnull
	Integer[] listHierarchyNodesFromRootToTheNodeIncludingSelf(int theNode);

	/**
	 * Method returns result of {@link #getRootHierarchyNodes()} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getRootHierarchyNodesFormula();

	/**
	 * Method returns all nodes that are present on the `root` level (i.e. that have no parent themselves).
	 */
	@Nonnull
	Bitmap getRootHierarchyNodes();

	/**
	 * Method returns result of {@link #getHierarchyNodesForParent(int)} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getHierarchyNodesForParentFormula(int parentNode);

	/**
	 * Method returns all children of the `parentNode`.
	 */
	@Nonnull
	Bitmap getHierarchyNodesForParent(int parentNode);

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	void traverseHierarchyFromNode(@Nonnull HierarchyVisitor visitor, int rootNode, boolean excludingRoot, @Nullable int... excludingNodes);

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	void traverseHierarchy(@Nonnull HierarchyVisitor visitor, @Nullable int... excludingNodes);

	/**
	 * Method returns all nodes that are not reachable from the root nodes. We call them orphans. These orphans are
	 * automatically attached to the tree whenever their parent is indexed and attached to the tree (i.e. parent must
	 * not be orphan itself).
	 */
	@Nonnull
	Bitmap getOrphanHierarchyNodes();

	/**
	 * Method returns the size of the reachable part of the tree.
	 */
	int getHierarchySize();

	/**
	 * Method returns absolute size of the items in the hierarchy index.
	 */
	int getHierarchySizeIncludingOrphans();

	/**
	 * Method returns true if hierarchy index contains no data.
	 */
	boolean isHierarchyIndexEmpty();
}
