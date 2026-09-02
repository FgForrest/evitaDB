/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

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
	 * The level reported for a node whose depth in the tree cannot be determined - a node that is not reachable from
	 * any root, because an ancestor above it was deleted, was never created, or the chain closes into a ring. Levels
	 * of real nodes start at 1 for a root, so this value can never collide with one.
	 *
	 * Every consumer of a level has to treat it as "unknown" rather than as "shallower than everything": a bound
	 * expressed as a level cannot cut a chain whose depth is unknown, which is why
	 * {@link io.evitadb.api.query.require.HierarchyDistance} exists for bounds that must hold on a broken chain.
	 */
	int UNKNOWN_LEVEL = -1;

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
	 * @param entityPrimaryKey the primary key of the entity whose placement should be removed
	 * @return primary key of the parent node of the removed node (if any)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when no placement was ever made for the entity
	 */
	 @Nullable
	Integer removeNode(int entityPrimaryKey);

	/**
	 * Tolerant sibling of {@link #removeNode(int)}: removes the placement of `entityPrimaryKey` if the index holds
	 * one, and does nothing at all if it does not.
	 *
	 * This is the tear-down entry point for the paths where a missing placement is a legitimate state rather than a
	 * programming error - an entity leaving the index, or leaving a scope. Not every entity of a hierarchical
	 * collection has a placement: a collection that becomes hierarchical, or widens the scopes it indexes hierarchy
	 * in, only has the entities of its live global index re-placed, so anything sitting in another scope at that
	 * moment carries no placement and can still be deleted or moved afterwards. {@link #removeNode(int)} keeps its
	 * assertion for the paths where an absent placement really would be a bug - a `removeParent` on an entity the
	 * index is supposed to hold.
	 *
	 * @param entityPrimaryKey the primary key of the entity whose placement should be removed if there is one
	 * @return primary key of the parent node of the removed node, or `null` when the node was a root or was not
	 *         present in the index at all
	 */
	@Nullable
	Integer removeNodeIfPresent(int entityPrimaryKey);

	/**
	 * Method returns all nodes that are reachable from all root nodes traversed in particular mode and sorted on each
	 * level using provided sorter function.
	 */
	@Nonnull
	Bitmap listHierarchyNodesFromRoot(@Nonnull TraversalMode traversalMode, @Nonnull UnaryOperator<int[]> levelSorter);

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
	 * Generates a bitmap that includes the given nodes along with their parent nodes.
	 *
	 * @param nodes a bitmap representing the nodes to include along with their parents; must not be null
	 * @return a new bitmap including the provided nodes and their parent nodes
	 */
	@Nonnull
	Bitmap listNodesIncludingParents(@Nonnull Bitmap nodes);

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
	 * Method traverses the hierarchy from the passed node up to the root node, visiting the node itself and every
	 * ancestor above it the index still holds. Unlike the downward traversals this one passes through orphan nodes,
	 * and it stops silently at the first ancestor the index does not hold - which is what a deleted ancestor and an
	 * entity upserted with a parent primary key that was never created both look like. Nothing is visited at all when
	 * the passed node itself is not present in the index.
	 *
	 * The `level` handed to the visitor is the node's absolute depth in the whole tree - 1 for a root - but only when
	 * the walk actually reached a root. When it ended at a break or at a ring instead, the depth of the fragment is
	 * not knowable and **every** node the walk visited is reported at {@link #UNKNOWN_LEVEL}, which is the same answer
	 * the downward traversals give for a node outside the reachable tree. `distance` always counts from the passed
	 * node (0) upwards and is therefore unaffected by a break.
	 *
	 * Both callers - the `hierarchyContent` parents fetch and the parent hierarchy statistics computer - turn `level`
	 * into a `stopAt(level(N))` decision, and a level bound never cuts a chain of unknown depth: inventing a
	 * fragment-relative depth instead would silently drop reachable ancestors, because
	 * {@link io.evitadb.api.query.require.HierarchyLevel} is defined as an absolute depth and the offset between the
	 * fragment and the real tree is exactly what a break makes unknowable. A caller that needs a bound which still
	 * holds on a broken chain expresses it as a {@link io.evitadb.api.query.require.HierarchyDistance}.
	 *
	 * A ring of nodes pointing at one another is treated exactly like a break, placed at the node the walk would
	 * otherwise have to visit a second time, so every node of the fragment is visited once and the traversal always
	 * terminates. Such a ring is a legal state of the index and not a corrupted one: re-pointing a node at one of its
	 * own descendants detaches that node together with its whole subtree - all of them become orphans - and leaves the
	 * detached fragment closing on itself. A ring has no top, so nothing above the revisited node can be reported.
	 *
	 * @param visitor the visitor to invoke for the passed node and for each ancestor above it the index still holds
	 * @param node    the primary key of the node the upward walk starts from
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

}
