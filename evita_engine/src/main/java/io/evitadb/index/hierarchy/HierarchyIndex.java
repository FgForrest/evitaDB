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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentDownToLevelWithExcludesBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentDownToLevelWithExcludesIncludingSelfBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentWithExcludesBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentWithExcludesIncludingSelfBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyRootsBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyRootsDownToLevelWithExcludesBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyRootsWithExcludesBitmapSupplier;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.index.transactionalMemory.VoidTransactionMemoryProducer;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;

/**
 * Hierarchy index collocates information about hierarchical tree structure of the entities. Index itself doesn't keep
 * the information in the form of tree because we don't have a tree implementation that is transactional memory compliant.
 *
 * Index allows out-of-order hierarchy tree creation where children can be indexed before their parent. Such entities
 * are collected in the {@link #orphans} array until their parent dependency is fulfilled. When the time comes they
 * are moved from {@link #orphans} to {@link #levelIndex}.
 *
 * The tree can be reconstructed by traversing {@link #roots} acquiring their children from {@link #levelIndex} and
 * scanning deeply level by level using information from {@link #levelIndex}. Nodes in {@link #roots} and
 * {@link #levelIndex} values are sorted by {@link HierarchyNode#order()} so that the entire hierarchy tree
 * is available immediately after the scan.
 *
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyIndex implements HierarchyIndexContract, VoidTransactionMemoryProducer<HierarchyIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 4121668650337515744L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * Index contains information about every entity that has {@link HierarchicalPlacementContract} specified no matter
	 * whether it's part of the tree reachable from the {@link #roots} or {@link #orphans}. Key of the index is
	 * {@link HierarchyNode#entityPrimaryKey()}.
	 */
	private final TransactionalMap<Integer, HierarchyNode> itemIndex;
	/**
	 * List contains entity primary keys of all entities that have hierarchy placement set to root level (i.e. without
	 * any parent). List contains ids sorted by {@link HierarchicalPlacementContract#getOrderAmongSiblings()}.
	 */
	private final TransactionalList<Integer> roots;
	/**
	 * Index contains information about children of all entities having {@link HierarchicalPlacementContract} specified.
	 * Every entity in {@link #itemIndex} has also record in this entity but only in case they are reachable from
	 * {@link #roots} - either with empty array or array of its children sorted by their
	 * {@link HierarchicalPlacementContract#getOrderAmongSiblings()}. If the entity is not reachable from any root
	 * entity it's places into {@link #orphans} and is not present in this index.
	 */
	private final TransactionalMap<Integer, int[]> levelIndex;
	/**
	 * Array contains entity primary keys of all entities that are not reachable from {@link #roots}. This simple list
	 * contains also children of orphan parents - i.e. primary keys of all unreachable entities that have
	 * {@link HierarchicalPlacementContract} specified.
	 */
	private final TransactionalIntArray orphans;
	/**
	 * Internal comparator implementation that is initialized once and used multiple times.
	 */
	@SuppressWarnings("TransientFieldNotInitialized")
	private transient IntBinaryOperator intComparator;

	public HierarchyIndex() {
		this.dirty = new TransactionalBoolean();
		this.roots = new TransactionalList<>(new LinkedList<>());
		this.levelIndex = new TransactionalMap<>(new HashMap<>());
		this.itemIndex = new TransactionalMap<>(new HashMap<>());
		this.orphans = new TransactionalIntArray();
		this.intComparator = createIntComparator();
	}

	public HierarchyIndex(@Nonnull List<Integer> roots, Map<Integer, int[]> levelIndex, @Nonnull Map<Integer, HierarchyNode> itemIndex, @Nonnull int[] orphans) {
		this.dirty = new TransactionalBoolean();
		this.roots = new TransactionalList<>(roots);
		this.levelIndex = new TransactionalMap<>(levelIndex);
		this.itemIndex = new TransactionalMap<>(itemIndex);
		this.orphans = new TransactionalIntArray(orphans);
		this.intComparator = createIntComparator();
	}

	@Override
	public void setHierarchyFor(int entityPrimaryKey, @Nullable Integer parentPrimaryKey, int orderAmongSiblings) {
		Assert.isTrue(
			parentPrimaryKey == null || parentPrimaryKey != entityPrimaryKey,
			"Entity cannot refer to itself in a hierarchy placement!"
		);

		this.dirty.setToTrue();
		final HierarchyNode newHierarchyNode = new HierarchyNode(entityPrimaryKey, parentPrimaryKey, orderAmongSiblings);

		// remove previous location
		internalRemoveHierarchy(entityPrimaryKey);
		// register new location
		itemIndex.put(entityPrimaryKey, newHierarchyNode);

		final IntBinaryOperator theIntComparator = getIntComparator();
		if (parentPrimaryKey == null) {
			roots.add(entityPrimaryKey);
			roots.sort(theIntComparator::applyAsInt);
			// create the children set
			createChildrenSetFromOrphansRecursively(entityPrimaryKey);
		} else {
			final boolean parentFound = levelIndex.computeIfPresent(
				parentPrimaryKey,
				(ppk, oldValue) -> ArrayUtils.insertIntIntoOrderedArray(entityPrimaryKey, oldValue, theIntComparator)
			) != null;
			if (parentFound) {
				// create the children set
				createChildrenSetFromOrphansRecursively(entityPrimaryKey);
			} else {
				this.orphans.add(entityPrimaryKey);
			}
		}
	}

	@Override
	public void removeHierarchyFor(int entityPrimaryKey) {
		final HierarchyNode removedNode = internalRemoveHierarchy(entityPrimaryKey);
		Assert.notNull(removedNode, "No hierarchy was set for entity with primary key " + entityPrimaryKey + "!");
		this.dirty.setToTrue();
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromRootFormula(int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyRootsWithExcludesBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromRoot(int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		for (Integer nodeId : this.roots) {
			if (!excludedSet.contains(nodeId)) {
				result.add(nodeId);
				final int[] children = this.levelIndex.get(nodeId);
				if (children != null) {
					addRecursively(excludedSet, result, children, Integer.MAX_VALUE);
				}
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromRootDownToFormula(int levels, int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyRootsDownToLevelWithExcludesBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				levels, excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromRootDownTo(int levels, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		for (Integer nodeId : this.roots) {
			if (!excludedSet.contains(nodeId)) {
				result.add(nodeId);
				final int[] children = this.levelIndex.get(nodeId);
				if (children != null) {
					addRecursively(excludedSet, result, children, levels - 1);
				}
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode, int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentWithExcludesIncludingSelfBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		if (!excludedSet.contains(parentNode)) {
			result.add(parentNode);
			final int[] children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(excludedSet, result, children, Integer.MAX_VALUE);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentIncludingItselfDownToFormula(int parentNode, int levels, int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentDownToLevelWithExcludesIncludingSelfBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, levels, excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		if (!excludedSet.contains(parentNode)) {
			result.add(parentNode);
			final int[] children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(excludedSet, result, children, levels - 1);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentFormula(int parentNode, int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentWithExcludesBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParent(int parentNode, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		if (!excludedSet.contains(parentNode)) {
			final int[] children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(excludedSet, result, children, Integer.MAX_VALUE);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentDownToFormula(int parentNode, int levels, int... excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentDownToLevelWithExcludesBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, levels, excludedNodeTrees
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels, int... excludedNodeTrees) {
		assertNodeInIndex(parentNode);
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		final CompositeIntArray result = new CompositeIntArray();
		if (!excludedSet.contains(parentNode)) {
			final int[] children = this.levelIndex.get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				addRecursively(excludedSet, result, children, levels);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	@Nonnull
	@Override
	public Integer[] listHierarchyNodesFromRootToTheNode(int theNode) {
		HierarchyNode hierarchyNode = getHierarchyNodeOrThrowException(theNode);
		final CompositeObjectArray<Integer> result = new CompositeObjectArray<>(Integer.class);
		while (hierarchyNode.parentEntityPrimaryKey() != null) {
			result.add(hierarchyNode.parentEntityPrimaryKey());
			hierarchyNode = getParentNodeOrThrowException(theNode, hierarchyNode);
		}
		final Integer[] theResult = result.toArray();
		ArrayUtils.reverse(theResult);
		return theResult;
	}

	@Nonnull
	@Override
	public Integer[] listHierarchyNodesFromRootToTheNodeIncludingSelf(int theNode) {
		HierarchyNode hierarchyNode = getHierarchyNodeOrThrowException(theNode);
		final CompositeObjectArray<Integer> result = new CompositeObjectArray<>(Integer.class);
		result.add(theNode);
		while (hierarchyNode.parentEntityPrimaryKey() != null) {
			result.add(hierarchyNode.parentEntityPrimaryKey());
			hierarchyNode = getParentNodeOrThrowException(theNode, hierarchyNode);
		}
		final Integer[] theResult = result.toArray();
		ArrayUtils.reverse(theResult);
		return theResult;
	}

	@Override
	@Nonnull
	public Formula getRootHierarchyNodesFormula() {
		return new DeferredFormula(
			new HierarchyRootsBitmapSupplier(
				this,
				new long[]{this.roots.getId(), this.levelIndex.getId()}
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap getRootHierarchyNodes() {
		return this.roots.isEmpty() ?
			EmptyBitmap.INSTANCE : new BaseBitmap(this.roots.stream().mapToInt(it -> it).toArray());
	}

	@Override
	@Nonnull
	public Formula getHierarchyNodesForParentFormula(int parentNode) {
		return new DeferredFormula(
			new HierarchyByParentBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode
			)
		);
	}

	@Override
	@Nonnull
	public Bitmap getHierarchyNodesForParent(int parentNode) {
		final int[] childrenIds = this.levelIndex.get(parentNode);
		return ArrayUtils.isEmpty(childrenIds) ? EmptyBitmap.INSTANCE : new BaseBitmap(childrenIds);
	}

	@Override
	public void traverseHierarchyFromNode(@Nonnull HierarchyVisitor visitor, int rootNode, boolean excludingRoot, int... excludingNodes) {
		traverseHierarchyInternal(visitor, rootNode, excludingRoot, excludingNodes);
	}

	@Override
	public void traverseHierarchy(@Nonnull HierarchyVisitor visitor) {
		traverseHierarchyInternal(visitor, null, null, null);
	}

	@Override
	@Nonnull
	public Bitmap getOrphanHierarchyNodes() {
		return new BaseBitmap(this.orphans.getArray());
	}

	@Override
	public int getHierarchySize() {
		return this.itemIndex.size() - this.orphans.getLength();
	}

	@Override
	public int getHierarchySizeIncludingOrphans() {
		return this.itemIndex.size();
	}

	@Override
	public boolean isHierarchyIndexEmpty() {
		return this.itemIndex.isEmpty();
	}

	/**
	 * Returns count of children nodes from root down to specified count of levels.
	 */
	public int getHierarchyNodeCountFromRootDownTo(int levels, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		int sum = 0;
		for (Integer nodeId : this.roots) {
			if (!excludedSet.contains(nodeId)) {
				sum++;
				final int[] children = this.levelIndex.get(nodeId);
				if (children != null) {
					sum += countRecursively(excludedSet, children, levels - 1);
				}
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` excluding the subtrees defined in `excludedNodeTrees`.
	 */
	public int getHierarchyNodeCountFromParent(int parentNode, int... excludedNodeTrees) {
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		int sum = 0;
		if (!excludedSet.contains(parentNode)) {
			final int[] children = this.levelIndex.get(parentNode);
			if (children != null) {
				sum += countRecursively(excludedSet, children, Integer.MAX_VALUE);
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` down to specified count of `levels` excluding the subtrees defined
	 * in `excludedNodeTrees`.
	 */
	public int getHierarchyNodeCountFromParentDownTo(int parentNode, int levels, int... excludedNodeTrees) {
		assertNodeInIndex(parentNode);
		final IntSet excludedSet = IntHashSet.from(excludedNodeTrees);
		int sum = 0;
		if (!excludedSet.contains(parentNode)) {
			final int[] children = this.levelIndex.get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				sum += children.length;
				sum += countRecursively(excludedSet, children, levels);
			}
		}
		return sum;
	}

	/**
	 * Returns count of root hierarchy nodes.
	 */
	public int getRootHierarchyNodeCount() {
		return this.roots.isEmpty() ? 0 : this.roots.size();
	}

	/**
	 * Returns count of children for passed parent.
	 */
	public int getHierarchyNodeCountForParent(int parentNode) {
		return ofNullable(this.levelIndex.get(parentNode)).map(it -> it.length).orElse(0);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		for (Integer rootId : roots) {
			sb.append(rootId).append("\n");
			toStringChildrenRecursively(levelIndex.get(rootId), 1, sb);
		}
		sb.append("Orphans: ").append(orphans);
		return sb.toString();
	}

	/**
	 * Method creates container for storing any of hierarchy index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			return new HierarchyIndexStoragePart(
				entityIndexPrimaryKey, this.itemIndex, this.roots, this.levelIndex, this.orphans.getArray()
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nonnull
	@Override
	public HierarchyIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		// we can safely throw away dirty flag now
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty, transaction);
		return new HierarchyIndex(
			transactionalLayer.getStateCopyWithCommittedChanges(this.roots, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.levelIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.itemIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.orphans, transaction)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.roots.removeLayer(transactionalLayer);
		this.levelIndex.removeLayer(transactionalLayer);
		this.itemIndex.removeLayer(transactionalLayer);
		this.orphans.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private IntBinaryOperator getIntComparator() {
		if (this.intComparator == null) {
			this.intComparator = createIntComparator();
		}
		return this.intComparator;
	}

	@Nonnull
	private IntBinaryOperator createIntComparator() {
		return (o1, o2) -> {
			// first compare the order of the items
			final int o1Order = this.itemIndex.get(o1).order();
			final int o2Order = this.itemIndex.get(o2).order();
			if (o1Order == o2Order) {
				// if its same sort items by their id
				return Integer.compare(o1, o2);
			} else {
				return Integer.compare(o1Order, o2Order);
			}
		};
	}

	private HierarchyNode internalRemoveHierarchy(int entityPrimaryKey) {
		// remove optional previous location
		if (itemIndex.containsKey(entityPrimaryKey)) {
			final HierarchyNode previousLocation = itemIndex.remove(entityPrimaryKey);
			if (this.orphans.contains(entityPrimaryKey)) {
				// the node was already orphan - we can safely remove the information
				this.orphans.remove(entityPrimaryKey);
				return previousLocation;
			}
			// clean references in previous tree
			if (previousLocation != null) {
				// register all children as orphans
				makeOrphansRecursively(entityPrimaryKey);
				// clear references in parent node
				if (previousLocation.parentEntityPrimaryKey() == null) {
					roots.remove((Integer) entityPrimaryKey);
				} else {
					final int[] recomputedValue = levelIndex.computeIfPresent(
						previousLocation.parentEntityPrimaryKey(),
						(epk, parentNodeChildren) -> {
							final int index = ArrayUtils.binarySearch(
								parentNodeChildren, entityPrimaryKey, (o1, o2) -> {
									final HierarchyNode o1Node = o1 == entityPrimaryKey ? previousLocation : this.itemIndex.get(o1);
									final HierarchyNode o2Node = o2 == entityPrimaryKey ? previousLocation : this.itemIndex.get(o2);
									// first compare the order of the items
									final int orderComparison = Integer.compare(o1Node.order(), o2Node.order());
									if (orderComparison == 0) {
										// if its same sort items by their id
										return Integer.compare(o1, o2);
									} else {
										return orderComparison;
									}
								}
							);
							Assert.isPremiseValid(
								index > -1,
								"Hierarchy node " + entityPrimaryKey + " unexpectedly not found in children index!"
							);
							return ArrayUtils.removeIntFromArrayOnIndex(parentNodeChildren, index);
						}
					);
					Assert.isPremiseValid(
						recomputedValue != null,
						"Hierarchy node " + entityPrimaryKey + " unexpectedly not found in item index!"
					);
				}
			}
			return previousLocation;
		} else {
			return null;
		}
	}

	private void assertNodeInIndex(int parentNode) {
		Assert.isTrue(this.itemIndex.containsKey(parentNode), "Parent node `" + parentNode + "` is not present in the index!");
	}

	@Nonnull
	private HierarchyNode getHierarchyNodeOrThrowException(int theNode) {
		HierarchyNode hierarchyNode = this.itemIndex.get(theNode);
		Assert.isTrue(hierarchyNode != null, "The node `" + theNode + "` is not present in the index!");
		return hierarchyNode;
	}

	@Nonnull
	private HierarchyNode getParentNodeOrThrowException(int theNode, @Nonnull HierarchyNode hierarchyNode) {
		hierarchyNode = this.itemIndex.get(hierarchyNode.parentEntityPrimaryKey());
		Assert.isPremiseValid(hierarchyNode != null, "The node parent `" + theNode + "` is unexpectedly not present in the index!");
		return hierarchyNode;
	}

	private void makeOrphansRecursively(int entityPrimaryKey) {
		final int[] removedNodeChildren = levelIndex.remove(entityPrimaryKey);
		this.orphans.addAll(removedNodeChildren);
		for (int removedNodeChild : removedNodeChildren) {
			makeOrphansRecursively(removedNodeChild);
		}
	}

	private void createChildrenSetFromOrphansRecursively(int entityPrimaryKey) {
		final CompositeIntArray children = new CompositeIntArray();
		final OfInt it = this.orphans.iterator();
		while (it.hasNext()) {
			final int orphanId = it.next();
			final HierarchyNode orphan = itemIndex.get(orphanId);
			if (Objects.equals(entityPrimaryKey, orphan.parentEntityPrimaryKey())) {
				children.add(orphanId);
			}
		}
		final int[] childrenArray = children.toArray();
		ArrayUtils.sortArray(this.getIntComparator()::applyAsInt, childrenArray);
		for (int formerOrphanId : childrenArray) {
			this.orphans.remove(formerOrphanId);
		}
		this.levelIndex.put(entityPrimaryKey, childrenArray);
		for (int childrenId : childrenArray) {
			createChildrenSetFromOrphansRecursively(childrenId);
		}
	}

	private void addRecursively(@Nonnull IntSet excludedSet, @Nonnull CompositeIntArray result, @Nonnull int[] children, int levels) {
		for (int childId : children) {
			if (!excludedSet.contains(childId)) {
				result.add(childId);
				if (levels > 0) {
					final int[] childrenOfChildren = this.levelIndex.get(childId);
					if (childrenOfChildren != null) {
						addRecursively(excludedSet, result, childrenOfChildren, levels - 1);
					}
				}
			}
		}
	}

	private int countRecursively(@Nonnull IntSet excludedSet, @Nonnull int[] children, int levels) {
		int sum = 0;
		for (int childId : children) {
			if (!excludedSet.contains(childId)) {
				sum++;
				if (levels > 0) {
					final int[] childrenOfChildren = this.levelIndex.get(childId);
					if (childrenOfChildren != null) {
						sum += countRecursively(excludedSet, childrenOfChildren, levels - 1);
					}
				}
			}
		}
		return sum;
	}

	private void toStringChildrenRecursively(int[] nodeIds, int indent, StringBuilder sb) {
		for (int nodeId : nodeIds) {
			sb.append(" ".repeat(3 * indent)).append(nodeId).append("\n");
			toStringChildrenRecursively(levelIndex.get(nodeId), indent + 1, sb);
		}
	}

	private void traverseHierarchyInternal(@Nonnull HierarchyVisitor visitor, @Nullable Integer rootNode, @Nullable Boolean excludingRoot, @Nullable int[] excludingNodes) {
		final IntSet excludedNodes = new IntHashSet();
		if (excludingNodes != null) {
			Arrays.stream(excludingNodes).forEach(excludedNodes::add);
		}
		final AtomicReference<IntFunction<Runnable>> factoryHolder = new AtomicReference<>();
		final IntFunction<Runnable> childrenTraverseCreator = childrenId ->
			() -> {
				final Collection<HierarchyNode> children = ofNullable(this.levelIndex.get(childrenId))
					.map(it ->
						Arrays.stream(it)
							.filter(x -> !excludedNodes.contains(x))
							.mapToObj(this.itemIndex::get)
							.filter(Objects::nonNull)
							.sorted()
							.collect(Collectors.toList())
					)
					.orElse(Collections.emptyList());
				for (HierarchyNode child : children) {
					visitor.visit(child, factoryHolder.get().apply(child.entityPrimaryKey()));
				}
			};
		factoryHolder.set(childrenTraverseCreator);

		final Collection<HierarchyNode> rootNodes;
		if (rootNode == null) {
			rootNodes = this.roots.stream()
				.filter(it -> !excludedNodes.contains(it))
				.map(this.itemIndex::get)
				.filter(Objects::nonNull)
				.sorted()
				.collect(Collectors.toList());
		} else if (!ofNullable(excludingRoot).orElse(false)) {
			rootNodes = ofNullable(this.itemIndex.get(rootNode))
				.map(Collections::singletonList)
				.orElse(Collections.emptyList());
		} else {
			rootNodes = ofNullable(this.levelIndex.get(rootNode))
				.map(Arrays::stream)
				.orElse(IntStream.empty())
				.filter(it -> !excludedNodes.contains(it))
				.mapToObj(this.itemIndex::get)
				.filter(Objects::nonNull)
				.sorted()
				.collect(Collectors.toList());
		}

		for (HierarchyNode examinedNode : rootNodes) {
			visitor.visit(examinedNode, childrenTraverseCreator.apply(examinedNode.entityPrimaryKey()));
		}
	}

}
