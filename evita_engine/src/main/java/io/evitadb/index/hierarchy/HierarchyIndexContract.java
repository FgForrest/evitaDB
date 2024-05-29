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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.OptionalInt;

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
	 * Method initializes all existing nodes as root nodes. This method should be called at the moment the index is
	 * created for the first time and all existing entities which are already present but have no parent relation set
	 * should become as initial root nodes, so that all that are added after that could use them as their parents.
	 */
	void initRootNodes(@Nonnull Bitmap rootNodes);

	/**
	 * Method indexes `entityPrimaryKey` placement in the hierarchy tree using information about its `parentPrimaryKey`
	 * and `orderAmongSiblings`.
	 *
	 * It allows out-of-order hierarchy tree indexing where children can be indexed before their parent. Such entities
	 * are collected in the {@link #getOrphanHierarchyNodes()} array until their parent dependency is fulfilled. When the time comes they
	 * are moved from {@link #getOrphanHierarchyNodes()} to {@link #getRootHierarchyNodes()} (recursively).
	 */
	void addNode(int entityPrimaryKey, @Nullable Integer parentPrimaryKey);

	/**
	 * Method removes information about `entityPrimaryKey` placement from the index. It doesn't matter whether the key
	 * is inside {@link #getOrphanHierarchyNodes()} or places in the living tree {@link #getRootHierarchyNodes()}.
	 *
	 * @return primary key of the parent node of the removed node (if any)
	 */
	 @Nullable
	Integer removeNode(int entityPrimaryKey);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRoot(HierarchyFilteringPredicate)}  wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromRootFormula() {
		return getListHierarchyNodesFromRootFormula(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRoot(HierarchyFilteringPredicate)}  wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromRootFormula(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns all nodes that are reachable from all root nodes.
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromRoot() {
		return listHierarchyNodesFromRoot(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are reachable from all root nodes that satisfy the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromRoot(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRootDownTo(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromRootDownToFormula(int levels) {
		return getListHierarchyNodesFromRootDownToFormula(levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromRootDownTo(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromRootDownToFormula(int levels, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns nodes that are reachable from all root nodes down to specified number of `levels`.
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromRootDownTo(int levels) {
		return listHierarchyNodesFromRootDownTo(levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns nodes that are reachable from all root nodes down to specified number of `levels` that satisfy
	 * the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromRootDownTo(int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItself(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode) {
		return getListHierarchyNodesFromParentIncludingItselfFormula(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItself(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself).
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode) {
		return listHierarchyNodesFromParentIncludingItself(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself),
	 * that satisfy the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItselfDownTo(int, int, HierarchyFilteringPredicate)}
	 * wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromParentIncludingItselfDownToFormula(int parentNode, int levels) {
		return getListHierarchyNodesFromParentIncludingItselfDownToFormula(parentNode, levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentIncludingItselfDownTo(int, int, HierarchyFilteringPredicate)}
	 * wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentIncludingItselfDownToFormula(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself)
	 * down to specified number of `levels`.
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels) {
		return listHierarchyNodesFromParentIncludingItselfDownTo(parentNode, levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (including the parent node itself)
	 * down to specified number of `levels`, that satisfy the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParent(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromParentFormula(int parentNode) {
		return getListHierarchyNodesFromParentFormula(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParent(int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself).
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromParent(int parentNode) {
		return listHierarchyNodesFromParent(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself),
	 * that satisfy the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentDownTo(int, int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getListHierarchyNodesFromParentDownToFormula(int parentNode, int levels) {
		return getListHierarchyNodesFromParentDownToFormula(parentNode, levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #listHierarchyNodesFromParentDownTo(int, int, HierarchyFilteringPredicate)} wrapped as lazy lambda
	 * in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getListHierarchyNodesFromParentDownToFormula(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself)
	 * down to specified number of `levels`.
	 */
	@Nonnull
	default Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels) {
		return listHierarchyNodesFromParentDownTo(parentNode, levels, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are reachable from the specified `parentNode` (excluding the parent node itself)
	 * down to specified number of `levels`, filtering only those nodes that satisfy the predicate.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate);

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
	 * Method returns result of {@link #getRootHierarchyNodes(HierarchyFilteringPredicate)} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getRootHierarchyNodesFormula() {
		return getRootHierarchyNodesFormula(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #getRootHierarchyNodes(HierarchyFilteringPredicate)} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getRootHierarchyNodesFormula(@Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all nodes that are present on the `root` level (i.e. that have no parent themselves).
	 */
	@Nonnull
	default Bitmap getRootHierarchyNodes() {
		return getRootHierarchyNodes(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all nodes that are present on the `root` level (i.e. that have no parent themselves).
	 */
	@Nonnull
	Bitmap getRootHierarchyNodes(@Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns result of {@link #getHierarchyNodesForParent(int)} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	default Formula getHierarchyNodesForParentFormula(int parentNode) {
		return getHierarchyNodesForParentFormula(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns result of {@link #getHierarchyNodesForParent(int)} wrapped as lazy lambda in {@link DeferredFormula}.
	 */
	@Nonnull
	Formula getHierarchyNodesForParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method returns all children of the `parentNode`.
	 */
	@Nonnull
	default Bitmap getHierarchyNodesForParent(int parentNode) {
		return getHierarchyNodesForParent(parentNode, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method returns all children of the `parentNode`.
	 */
	@Nonnull
	Bitmap getHierarchyNodesForParent(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Returns primary key of the parent node for the node with primary key passed in argument.
	 *
	 * @param forNode primary key of the node whose parent should be returned
	 * @return empty result if the node is the root node
	 */
	@Nonnull
	OptionalInt getParentNode(int forNode);

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	default void traverseHierarchyFromNode(@Nonnull HierarchyVisitor visitor, int rootNode, boolean excludingRoot) {
		traverseHierarchyFromNode(visitor, rootNode, excludingRoot, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	void traverseHierarchyFromNode(@Nonnull HierarchyVisitor visitor, int rootNode, boolean excludingRoot, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes from the node up to the root node.
	 */
	void traverseHierarchyToRoot(@Nonnull HierarchyVisitor visitor, int node);

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	default void traverseHierarchy(@Nonnull HierarchyVisitor visitor) {
		traverseHierarchy(visitor, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	/**
	 * Method traverses entire hierarchy of (non-orphan) nodes, depth first. Visitor will first visit the leaf nodes
	 * according to ordering specified on nodes and progresses up to the root. When one root node is examined, next
	 * one leafs will be visited next.
	 */
	void traverseHierarchy(@Nonnull HierarchyVisitor visitor, @Nonnull HierarchyFilteringPredicate excludedNodeTrees);

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

	/**
	 * Method return comparator that orders hierarchy entity primary keys by the depth-first-search order of their
	 * hierarchical tree.
	 */
	@Nonnull
	Comparator<Integer> getHierarchyComparator();

}
