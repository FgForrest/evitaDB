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
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyByParentIncludingSelfBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyForParentBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyRootsBitmapSupplier;
import io.evitadb.index.hierarchy.suppliers.HierarchyRootsDownBitmapSupplier;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart.LevelIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static java.util.Optional.empty;
import static java.util.Optional.of;
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
 * {@link #levelIndex} values are sorted by primary key in ascending order so that the entire hierarchy tree
 * is available immediately after the scan.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyIndex
	implements HierarchyIndexContract,
	VoidTransactionMemoryProducer<HierarchyIndex>,
	IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 4121668650337515744L;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * Index contains information about every entity that has parent specified no matter
	 * whether it's part of the tree reachable from the {@link #roots} or {@link #orphans}. Key of the index is
	 * {@link HierarchyNode#entityPrimaryKey()}.
	 */
	private final TransactionalMap<Integer, HierarchyNode> itemIndex;
	/**
	 * List contains entity primary keys of all entities that have hierarchy placement set to root level (i.e. without
	 * any parent).
	 */
	private final TransactionalIntArray roots;
	/**
	 * Index contains information about children of all entities having parents specified.
	 * Every entity in {@link #itemIndex} has also record in this entity but only in case they are reachable from
	 * {@link #roots} - either with empty array or array of its children.If the entity is not reachable from any root
	 * entity it is placed into {@link #orphans} and is not present in this index.
	 */
	private final TransactionalMap<Integer, TransactionalIntArray> levelIndex;
	/**
	 * Array contains entity primary keys of all entities that are not reachable from {@link #roots}. This simple list
	 * contains also children of orphan parents - i.e. primary keys of all unreachable entities that have parent
	 * specified.
	 */
	private final TransactionalIntArray orphans;
	/**
	 * Contains cached result of {@link #getAllHierarchyNodesFormula()} call.
	 */
	@Nullable private volatile Formula memoizedAllNodeFormula;

	/**
	 * Creates a new empty hierarchy index.
	 */
	public HierarchyIndex() {
		this.dirty = new TransactionalBoolean();
		this.roots = new TransactionalIntArray(ArrayUtils.EMPTY_INT_ARRAY);
		this.levelIndex = new TransactionalMap<>(new HashMap<>(32), TransactionalIntArray.class, TransactionalIntArray::new);
		this.itemIndex = new TransactionalMap<>(new HashMap<>(32));
		this.orphans = new TransactionalIntArray();
		this.memoizedAllNodeFormula = EmptyFormula.INSTANCE;
	}

	/**
	 * Creates a new hierarchy index pre-populated with existing data.
	 *
	 * @param roots      array of root entity primary keys (sorted ascending)
	 * @param levelIndex map from parent entity primary key to its direct children's primary keys
	 * @param itemIndex  map from entity primary key to its {@link HierarchyNode} information
	 * @param orphans    array of entity primary keys not reachable from any root node
	 */
	public HierarchyIndex(@Nonnull int[] roots, @Nonnull Map<Integer, TransactionalIntArray> levelIndex, @Nonnull Map<Integer, HierarchyNode> itemIndex, @Nonnull int[] orphans) {
		this.dirty = new TransactionalBoolean();
		this.roots = new TransactionalIntArray(roots);
		this.levelIndex = new TransactionalMap<>(levelIndex, TransactionalIntArray.class, TransactionalIntArray::new);
		this.itemIndex = new TransactionalMap<>(itemIndex);
		this.orphans = new TransactionalIntArray(orphans);
		this.memoizedAllNodeFormula = createAllHierarchyNodesFormula();
	}

	/**
	 * Creates a new hierarchy index pre-populated from persisted {@link LevelIndex} storage parts.
	 *
	 * @param roots      array of root entity primary keys (sorted ascending)
	 * @param levelIndex array of {@link LevelIndex} entries loaded from persistent storage
	 * @param itemIndex  map from entity primary key to its {@link HierarchyNode} information
	 * @param orphans    array of entity primary keys not reachable from any root node
	 */
	public HierarchyIndex(@Nonnull int[] roots, @Nonnull LevelIndex[] levelIndex, @Nonnull Map<Integer, HierarchyNode> itemIndex, @Nonnull int[] orphans) {
		this(
			roots,
			Arrays.stream(levelIndex)
				.collect(
					Collectors.toMap(
						LevelIndex::parentId,
						it -> new TransactionalIntArray(it.childrenIds()),
						(ar1, ar2) -> {
							throw new IllegalStateException("Duplicate key found in level index!");
						},
						HashMap::new
					)
				),
			itemIndex,
			orphans
		);
	}

	/**
	 * Initializes root nodes from an existing bitmap during the bootstrap phase.
	 * This method must be called only once, before any nodes are added to the index.
	 *
	 * @param rootNodes bitmap of entity primary keys to register as root hierarchy nodes
	 * @throws IllegalArgumentException if the index already contains items
	 */
	@Override
	public void initRootNodes(@Nonnull Bitmap rootNodes) {
		Assert.isPremiseValid(this.itemIndex.isEmpty(), "This method should be called only for bootstrap!");

		this.dirty.setToTrue();
		for (Integer rootNode : rootNodes) {
			final HierarchyNode newHierarchyNode = new HierarchyNode(rootNode, null);
			this.itemIndex.put(rootNode, newHierarchyNode);
			this.roots.add(rootNode);
			this.levelIndex.put(rootNode, new TransactionalIntArray());
		}
	}

	/**
	 * Registers a new node in the hierarchy or updates its parent reference.
	 *
	 * If the node previously existed in the hierarchy, it is first removed from its old position
	 * before being placed at the new location. If the parent is not yet registered, the node is
	 * placed in the {@link #orphans} collection until its parent becomes available.
	 *
	 * @param entityPrimaryKey the primary key of the entity to register
	 * @param parentPrimaryKey the primary key of the entity's parent, or `null` for root-level nodes
	 */
	@Override
	public void addNode(int entityPrimaryKey, @Nullable Integer parentPrimaryKey) {
		Assert.isTrue(
			parentPrimaryKey == null || parentPrimaryKey != entityPrimaryKey,
			"Entity cannot refer to itself in a hierarchy placement!"
		);

		this.dirty.setToTrue();
		final HierarchyNode newHierarchyNode = new HierarchyNode(entityPrimaryKey, parentPrimaryKey);

		// remove previous location
		internalRemoveHierarchy(entityPrimaryKey);
		// register new location
		this.itemIndex.put(entityPrimaryKey, newHierarchyNode);

		if (parentPrimaryKey == null) {
			this.roots.add(entityPrimaryKey);
			// create the children set
			createChildrenSetFromOrphansRecursively(entityPrimaryKey);
		} else {
			final Optional<TransactionalIntArray> parentRef = ofNullable(this.levelIndex.get(parentPrimaryKey));
			if (parentRef.isPresent()) {
				parentRef.get().add(entityPrimaryKey);
				// create the children set
				createChildrenSetFromOrphansRecursively(entityPrimaryKey);
			} else {
				this.orphans.add(entityPrimaryKey);
			}
		}
		if (!isTransactionAvailable()) {
			resetMemoizedValues();
		}
	}

	/**
	 * Removes a node from the hierarchy and makes all its children orphans.
	 *
	 * The removed node's children are recursively moved to the {@link #orphans} collection
	 * because they are no longer reachable from any root.
	 *
	 * @param entityPrimaryKey the primary key of the entity to remove from the hierarchy
	 * @return the primary key of the removed node's parent, or `null` if the node was a root
	 * @throws IllegalArgumentException if no hierarchy placement was set for the given entity
	 */
	@Override
	public Integer removeNode(int entityPrimaryKey) {
		final HierarchyNode removedNode = internalRemoveHierarchy(entityPrimaryKey);
		Assert.notNull(removedNode, "No hierarchy was set for entity with primary key " + entityPrimaryKey + "!");
		this.dirty.setToTrue();
		if (!isTransactionAvailable()) {
			resetMemoizedValues();
		}
		return removedNode.parentEntityPrimaryKey();
	}

	/**
	 * Returns a bitmap of all hierarchy nodes reachable from the roots, ordered according to the
	 * specified traversal mode.
	 *
	 * @param traversalMode the traversal strategy — depth-first or breadth-first
	 * @param levelSorter   operator applied to children arrays at each level to reorder them
	 * @return bitmap of all non-orphan hierarchy node primary keys in traversal order
	 */
	@Nonnull
	@Override
	public Bitmap listHierarchyNodesFromRoot(
		@Nonnull TraversalMode traversalMode,
		@Nonnull UnaryOperator<int[]> levelSorter
	) {
		final CompositeIntArray result = new CompositeIntArray();
		final int[] rootNodeIds = this.roots.getArray();
		final int[] currentLevel = levelSorter.apply(rootNodeIds);

		// now execute the traversal
		if (traversalMode == TraversalMode.DEPTH_FIRST) {
			for (int rootNodeId : currentLevel) {
				result.add(rootNodeId);
				depthFirstTraversal(rootNodeId, levelSorter, result);
			}
		} else {
			result.addAll(currentLevel, 0, currentLevel.length);
			breadthFirstTraversal(0, levelSorter, result);
		}
		return result.isEmpty() ?
			EmptyBitmap.INSTANCE : new ArrayBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all hierarchy nodes from the roots downward,
	 * filtered by the provided predicate.
	 *
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and their subtrees are included
	 * @return deferred formula with the set of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromRootFormula(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		return new DeferredFormula(
			new HierarchyRootsDownBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				hierarchyFilteringPredicate
			)
		);
	}

	/**
	 * Returns a bitmap of all hierarchy nodes reachable from the roots that pass the filtering predicate.
	 *
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromRoot(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final CompositeIntArray result = new CompositeIntArray();
		for (int nodeId : this.roots.getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				result.add(nodeId);
				final TransactionalIntArray children = this.levelIndex.get(nodeId);
				if (children != null) {
					addRecursively(hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
				}
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a bitmap of all hierarchy nodes reachable from the roots down to a specified depth,
	 * filtered by the provided predicate.
	 *
	 * @param levels                      maximum depth to traverse (1 = roots + direct children)
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromRootDownTo(int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final CompositeIntArray result = new CompositeIntArray();
		for (Integer nodeId : this.roots.getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				result.add(nodeId);
				final TransactionalIntArray children = this.levelIndex.get(nodeId);
				if (children != null) {
					addRecursively(hierarchyFilteringPredicate, result, children, levels - 1);
				}
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all hierarchy nodes in the subtree rooted at
	 * `parentNode`, including the parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode        the primary key of the node to use as the subtree root
	 * @param excludedNodeTrees predicate that controls which nodes and their subtrees are excluded
	 * @return deferred formula with the set of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentIncludingSelfBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, excludedNodeTrees
			)
		);
	}

	/**
	 * Returns a bitmap of all hierarchy nodes in the subtree rooted at `parentNode`, including the
	 * parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode                  the primary key of the node to use as the subtree root
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys, or an empty bitmap if the parent is excluded
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			if (this.itemIndex.containsKey(parentNode)) {
				result.add(parentNode);
			} else {
				return EmptyBitmap.INSTANCE;
			}
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a bitmap of all hierarchy nodes in the subtree rooted at `parentNode` down to a specified
	 * depth, including the parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode                  the primary key of the node to use as the subtree root
	 * @param levels                      maximum depth to traverse (1 = parent + direct children)
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			result.add(parentNode);
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(hierarchyFilteringPredicate, result, children, levels - 1);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all hierarchy nodes in the subtree rooted at
	 * `parentNode`, excluding the parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode        the primary key of the node to use as the subtree root
	 * @param excludedNodeTrees predicate that controls which nodes and their subtrees are excluded
	 * @return deferred formula with the set of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyByParentBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode, excludedNodeTrees
			)
		);
	}

	/**
	 * Returns a bitmap of all hierarchy nodes in the subtree rooted at `parentNode`, excluding the
	 * parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode                  the primary key of the node to use as the subtree root
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			if (children != null) {
				addRecursively(hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a bitmap of all hierarchy nodes in the subtree rooted at `parentNode` down to a specified
	 * depth, excluding the parent node itself, filtered by the provided predicate.
	 *
	 * @param parentNode                  the primary key of the node to use as the subtree root
	 * @param levels                      maximum depth to traverse (0 = direct children only)
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees to include
	 * @return bitmap of matching hierarchy node primary keys
	 * @throws IllegalArgumentException if the parent node is not present in the index
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		assertNodeInIndex(parentNode);
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				addRecursively(hierarchyFilteringPredicate, result, children, levels);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all root-level hierarchy nodes, filtered by the
	 * provided predicate.
	 *
	 * @param excludedNodeTrees predicate that controls which root nodes and their subtrees are excluded
	 * @return deferred formula with the set of matching root hierarchy node primary keys
	 */
	@Nonnull
	@Override
	public Formula getRootHierarchyNodesFormula(@Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyRootsBitmapSupplier(
				this,
				new long[]{this.roots.getId(), this.levelIndex.getId()},
				excludedNodeTrees
			)
		);
	}

	/**
	 * Returns a bitmap of all root-level hierarchy nodes that pass the filtering predicate.
	 *
	 * @param hierarchyFilteringPredicate predicate determining which root nodes to include
	 * @return bitmap of matching root hierarchy node primary keys, or an empty bitmap if no roots exist
	 */
	@Override
	@Nonnull
	public Bitmap getRootHierarchyNodes(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		return this.roots.isEmpty() ?
			EmptyBitmap.INSTANCE :
			new BaseBitmap(Arrays.stream(this.roots.getArray()).filter(hierarchyFilteringPredicate).toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates the parent node together with its direct children,
	 * filtered by the provided predicate.
	 *
	 * @param parentNode        the primary key of the node whose group is to be computed
	 * @param excludedNodeTrees predicate that controls which nodes are excluded from the result
	 * @return deferred formula with the parent node and its direct children primary keys
	 */
	@Nonnull
	@Override
	public Formula getHierarchyNodesForParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		return new DeferredFormula(
			new HierarchyForParentBitmapSupplier(
				this, new long[]{this.roots.getId(), this.levelIndex.getId()},
				parentNode,
				excludedNodeTrees
			)
		);
	}

	/**
	 * Returns a bitmap containing the `parentNode` and its direct children that pass the filtering
	 * predicate. Returns an empty bitmap if the `parentNode` is not present in the index.
	 *
	 * @param parentNode                  the primary key of the parent node
	 * @param hierarchyFilteringPredicate predicate determining which nodes to include
	 * @return bitmap with the parent and its direct children, or an empty bitmap if the parent is absent
	 */
	@Override
	@Nonnull
	public Bitmap getHierarchyNodesForParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNode theParentNode = this.itemIndex.get(parentNode);
		if (theParentNode == null) {
			return EmptyBitmap.INSTANCE;
		} else {
			final TransactionalIntArray childrenIds = this.levelIndex.get(parentNode);
			return childrenIds == null || childrenIds.isEmpty() ?
				new BaseBitmap(parentNode) :
				new BaseBitmap(
					IntStream.concat(
							IntStream.of(parentNode),
							childrenIds.stream()
						)
						.filter(hierarchyFilteringPredicate)
						.toArray()
				);
		}
	}

	/**
	 * Returns the primary key of the parent node for the given node.
	 *
	 * @param forNode the primary key of the node whose parent is to be retrieved
	 * @return an {@link OptionalInt} containing the parent primary key, or empty if the node is a root
	 * @throws IllegalArgumentException if the node is not present in the index
	 */
	@Nonnull
	@Override
	public OptionalInt getParentNode(int forNode) {
		final HierarchyNode node = getHierarchyNodeOrThrowException(forNode);
		return Optional.ofNullable(node.parentEntityPrimaryKey())
			.map(OptionalInt::of)
			.orElse(OptionalInt.empty());
	}

	/**
	 * Returns a bitmap containing all provided nodes together with all their ancestor nodes up to the
	 * root. Shared ancestors are de-duplicated by the underlying bitmap structure.
	 *
	 * @param nodes bitmap of entity primary keys whose ancestors should be included
	 * @return bitmap containing the original nodes and all their ancestors
	 * @throws IllegalArgumentException if any node in the input is not present in the index
	 */
	@Nonnull
	@Override
	public Bitmap listNodesIncludingParents(@Nonnull Bitmap nodes) {
		final RoaringBitmap output = new RoaringBitmap();
		for (Integer nodeId : nodes) {
			output.add(nodeId);
			HierarchyNode hierarchyNode = getHierarchyNodeOrThrowException(nodeId);
			while (hierarchyNode.parentEntityPrimaryKey() != null) {
				if (!output.checkedAdd(hierarchyNode.parentEntityPrimaryKey())) {
					break;
				}
				hierarchyNode = getHierarchyNodeOrThrowException(hierarchyNode.parentEntityPrimaryKey());
			}
		}
		return output.isEmpty() ?
			EmptyBitmap.INSTANCE :
			new BaseBitmap(output);
	}

	/**
	 * Traverses the hierarchy subtree starting from the given `rootNode`, invoking the `visitor` for
	 * each node that passes the `havingPredicate`.
	 *
	 * @param visitor          the visitor to invoke for each traversed node
	 * @param rootNode         the primary key of the node to start traversal from
	 * @param excludingRoot    if `true`, the `rootNode` itself is skipped and only its descendants are visited
	 * @param havingPredicate  predicate that filters which nodes to visit
	 */
	@Override
	public void traverseHierarchyFromNode(@Nonnull HierarchyVisitor visitor, int rootNode, boolean excludingRoot, @Nonnull HierarchyFilteringPredicate havingPredicate) {
		traverseHierarchyInternal(
			visitor, rootNode, excludingRoot, havingPredicate
		);
	}

	/**
	 * Traverses the hierarchy from the given `node` upward to the root, invoking the `visitor` for
	 * the node and each of its ancestors. Traversal is silently skipped if the node is an orphan.
	 *
	 * @param visitor the visitor to invoke for the node and each ancestor
	 * @param node    the primary key of the node to start the upward traversal from
	 */
	@Override
	public void traverseHierarchyToRoot(@Nonnull HierarchyVisitor visitor, int node) {
		final HierarchyNode theNode = this.itemIndex.get(node);
		// if the node is missing, just skip traversal
		if (theNode != null) {
			HierarchyNode hierarchyNode = theNode;
			int nodeLevel = 1;
			while (hierarchyNode.parentEntityPrimaryKey() != null) {
				nodeLevel++;
				final Optional<HierarchyNode> parentNode = getParentNodeOrThrowException(hierarchyNode);
				if (parentNode.isPresent()) {
					hierarchyNode = parentNode.get();
				} else {
					// no traversal will happen - orphan found
					return;
				}
			}

			final AtomicReference<TraverserFactory> factoryHolder = new AtomicReference<>();
			final TraverserFactory childrenTraverseCreator = (nodeId, level, distance) ->
				() -> {
					final HierarchyNode parent = getHierarchyNodeOrThrowException(nodeId);
					visitor.visit(
						parent, level, distance,
						ofNullable(parent.parentEntityPrimaryKey())
							.map(it -> factoryHolder.get().apply(it, level - 1, distance + 1))
							.orElse(() -> {
							})
					);
				};
			factoryHolder.set(childrenTraverseCreator);

			int finalNodeLevel = nodeLevel;
			visitor.visit(
				theNode,
				nodeLevel, 0,
				ofNullable(theNode.parentEntityPrimaryKey())
					.map(it -> childrenTraverseCreator.apply(it, finalNodeLevel - 1, 1))
					.orElse(() -> {
					})
			);
		}
	}

	/**
	 * Traverses the entire hierarchy tree starting from all root nodes, invoking the `visitor` for
	 * each node that passes the `havingPredicate`.
	 *
	 * @param visitor         the visitor to invoke for each traversed node
	 * @param havingPredicate predicate that filters which nodes and subtrees to visit
	 */
	@Override
	public void traverseHierarchy(@Nonnull HierarchyVisitor visitor, @Nonnull HierarchyFilteringPredicate havingPredicate) {
		traverseHierarchyInternal(
			visitor, null, null, havingPredicate
		);
	}

	/**
	 * Returns a bitmap of all entity primary keys that are registered in the hierarchy but whose
	 * parent nodes have not been indexed yet. Orphans are not reachable from any root node.
	 *
	 * @return bitmap of orphan entity primary keys
	 */
	@Override
	@Nonnull
	public Bitmap getOrphanHierarchyNodes() {
		return new BaseBitmap(this.orphans.getArray());
	}

	/**
	 * Returns the count of hierarchy nodes that are reachable from the roots (i.e., excluding orphans).
	 *
	 * @return number of non-orphan hierarchy nodes
	 */
	@Override
	public int getHierarchySize() {
		return this.itemIndex.size() - this.orphans.getLength();
	}

	/**
	 * Returns the total count of all hierarchy nodes including orphans.
	 *
	 * @return total number of registered hierarchy nodes
	 */
	@Override
	public int getHierarchySizeIncludingOrphans() {
		return this.itemIndex.size();
	}

	/**
	 * Returns `true` if the hierarchy index contains no nodes at all (neither roots nor orphans).
	 *
	 * @return `true` if the index is empty
	 */
	@Override
	public boolean isHierarchyIndexEmpty() {
		return this.itemIndex.isEmpty();
	}

	/**
	 * Method returns formula that contains all nodes attached to the tree (i.e. except {@link #orphans}.
	 */
	@Nonnull
	public Formula getAllHierarchyNodesFormula() {
		// if there is transaction open, and there are changes in the hierarchy data, we can't use the cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return createAllHierarchyNodesFormula();
		} else {
			Formula result = this.memoizedAllNodeFormula;
			if (result == null) {
				result = createAllHierarchyNodesFormula();
				this.memoizedAllNodeFormula = result;
			}
			return result;
		}
	}

	/**
	 * Returns count of children nodes from root down to specified count of levels.
	 */
	public int getHierarchyNodeCountFromRootDownTo(int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		int sum = 0;
		for (Integer nodeId : this.roots.getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				sum++;
				final TransactionalIntArray children = this.levelIndex.get(nodeId);
				if (children != null) {
					sum += countRecursively(hierarchyFilteringPredicate, children, levels - 1);
				}
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` excluding the subtrees defined in `hierarchyFilteringPredicate`.
	 */
	public int getHierarchyNodeCountFromParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		int sum = 0;
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			if (children != null) {
				sum += countRecursively(hierarchyFilteringPredicate, children, Integer.MAX_VALUE);
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` down to specified count of `levels` excluding the subtrees defined
	 * in `hierarchyFilteringPredicate`.
	 */
	public int getHierarchyNodeCountFromParentDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		assertNodeInIndex(parentNode);
		int sum = 0;
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = this.levelIndex.get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				sum += children.getLength();
				sum += countRecursively(hierarchyFilteringPredicate, children, levels);
			}
		}
		return sum;
	}

	/**
	 * Returns count of root hierarchy nodes.
	 */
	public int getRootHierarchyNodeCount(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		return this.roots.isEmpty() ? 0 : (int) (Arrays.stream(this.roots.getArray()).filter(hierarchyFilteringPredicate).count());
	}

	/**
	 * Returns count of children for passed parent.
	 */
	public int getHierarchyNodeCountForParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNode theParentNode = this.itemIndex.get(parentNode);
		if (theParentNode == null) {
			return 0;
		} else {
			final TransactionalIntArray childrenIds = this.levelIndex.get(parentNode);
			return childrenIds == null || childrenIds.isEmpty() ?
				1 :
				(int) IntStream.concat(
						IntStream.of(parentNode),
						childrenIds.stream()
					)
					.filter(hierarchyFilteringPredicate)
					.count();
		}
	}

	/**
	 * Returns a human-readable representation of the hierarchy tree starting from all root nodes,
	 * followed by a listing of orphan nodes.
	 *
	 * @return multi-line string representation of the hierarchy structure
	 */
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(128);
		for (Integer rootId : this.roots.getArray()) {
			sb.append(rootId).append("\n");
			final TransactionalIntArray nodeIds = this.levelIndex.get(rootId);
			if (nodeIds != null) {
				toStringChildrenRecursively(nodeIds, 1, sb);
			}
		}
		sb.append("Orphans: ").append(this.orphans);
		return sb.toString();
	}

	/**
	 * Method creates container for storing any of hierarchy index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			return new HierarchyIndexStoragePart(
				entityIndexPrimaryKey, this.itemIndex,
				this.roots.getArray(),
				this.levelIndex
					.entrySet()
					.stream()
					.map(it -> new HierarchyIndexStoragePart.LevelIndex(it.getKey(), it.getValue().getArray()))
					.toArray(LevelIndex[]::new),
				this.orphans.getArray()
			);
		} else {
			return null;
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	/**
	 * Resets the dirty flag after all changes have been persisted to storage.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Creates a new {@link HierarchyIndex} with all pending transactional changes merged into a
	 * committed state. Returns the current instance unchanged if no modifications were made.
	 *
	 * @param layer              the transactional layer (unused for this type, always `null`)
	 * @param transactionalLayer the maintainer providing committed copies of transactional structures
	 * @return a new committed copy, or `this` if the index was not modified
	 */
	@Nonnull
	@Override
	public HierarchyIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new HierarchyIndex(
				transactionalLayer.getStateCopyWithCommittedChanges(this.roots),
				transactionalLayer.getStateCopyWithCommittedChanges(this.levelIndex),
				transactionalLayer.getStateCopyWithCommittedChanges(this.itemIndex),
				transactionalLayer.getStateCopyWithCommittedChanges(this.orphans)
			);
		} else {
			return this;
		}
	}

	/**
	 * Removes transactional memory layers for this index and all its transactional sub-structures.
	 *
	 * @param transactionalLayer the maintainer from which layers should be removed
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.roots.removeLayer(transactionalLayer);
		this.levelIndex.removeLayer(transactionalLayer);
		this.itemIndex.removeLayer(transactionalLayer);
		this.orphans.removeLayer(transactionalLayer);
	}

	/**
	 * Removes the hierarchy placement for the given entity, making all its children orphans.
	 * Shared by both {@link #addNode} (to clear a previous placement) and {@link #removeNode}.
	 *
	 * @param entityPrimaryKey the primary key of the entity to remove from the hierarchy
	 * @return the removed {@link HierarchyNode}, or `null` if the entity was not in the index
	 */
	@Nullable
	private HierarchyNode internalRemoveHierarchy(int entityPrimaryKey) {
		// remove optional previous location
		if (this.itemIndex.containsKey(entityPrimaryKey)) {
			final HierarchyNode previousLocation = this.itemIndex.remove(entityPrimaryKey);
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
					this.roots.remove(entityPrimaryKey);
				} else {
					final TransactionalIntArray recomputedValue = this.levelIndex.computeIfPresent(
						previousLocation.parentEntityPrimaryKey(),
						(epk, parentNodeChildren) -> {
							parentNodeChildren.remove(entityPrimaryKey);
							return parentNodeChildren;
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

	/**
	 * Throws an exception if the given node is not present in the item index.
	 *
	 * @param parentNode the primary key of the node whose presence to verify
	 * @throws IllegalArgumentException if the node is absent from the index
	 */
	private void assertNodeInIndex(int parentNode) {
		Assert.isTrue(this.itemIndex.containsKey(parentNode), "Parent node `" + parentNode + "` is not present in the index!");
	}

	/**
	 * Returns the {@link HierarchyNode} for the given primary key, throwing an exception if absent.
	 *
	 * @param theNode the primary key of the node to retrieve
	 * @return the hierarchy node
	 * @throws IllegalArgumentException if the node is not present in the index
	 */
	@Nonnull
	private HierarchyNode getHierarchyNodeOrThrowException(int theNode) {
		HierarchyNode hierarchyNode = this.itemIndex.get(theNode);
		Assert.isTrue(hierarchyNode != null, "The node `" + theNode + "` is not present in the index!");
		return hierarchyNode;
	}

	/**
	 * Returns the parent {@link HierarchyNode} for the given node, or an empty optional if the node
	 * is a root or its parent is an orphan. Throws an exception if the parent is expected to exist
	 * but is missing from the index.
	 *
	 * @param hierarchyNode the node whose parent to look up
	 * @return optional parent node, or empty if the node is a root or its parent is an orphan
	 * @throws IllegalArgumentException if the parent is expected but unexpectedly absent
	 */
	@Nonnull
	private Optional<HierarchyNode> getParentNodeOrThrowException(@Nonnull HierarchyNode hierarchyNode) {
		if (hierarchyNode.parentEntityPrimaryKey() == null || this.orphans.contains(hierarchyNode.parentEntityPrimaryKey())) {
			return empty();
		} else {
			final HierarchyNode parentNode = this.itemIndex.get(hierarchyNode.parentEntityPrimaryKey());
			Assert.isTrue(parentNode != null, "The node parent `" + hierarchyNode.parentEntityPrimaryKey() + "` is unexpectedly not present in the index!");
			return of(parentNode);
		}
	}

	/**
	 * Recursively moves the entire subtree of the given entity to the orphan collection and removes
	 * the corresponding entries from the level index.
	 *
	 * @param entityPrimaryKey the primary key of the entity whose subtree becomes orphaned
	 */
	private void makeOrphansRecursively(int entityPrimaryKey) {
		final TransactionalIntArray removedNodeChildren = this.levelIndex.remove(entityPrimaryKey);
		if (removedNodeChildren != null) {
			final OfInt it = removedNodeChildren.iterator();
			while (it.hasNext()) {
				final int removedNodeChild = it.nextInt();
				this.orphans.add(removedNodeChild);
				makeOrphansRecursively(removedNodeChild);
			}
			removedNodeChildren.removeLayer();
		}
	}

	/**
	 * Scans the orphan collection for entities whose parent matches `entityPrimaryKey`, promotes them
	 * to the level index as children of the given entity, and recursively processes their own orphaned
	 * children as well.
	 *
	 * @param entityPrimaryKey the primary key of the newly placed entity whose orphaned children to claim
	 */
	private void createChildrenSetFromOrphansRecursively(int entityPrimaryKey) {
		final CompositeIntArray children = new CompositeIntArray();
		final OfInt it = this.orphans.iterator();
		while (it.hasNext()) {
			final int orphanId = it.next();
			final HierarchyNode orphan = this.itemIndex.get(orphanId);
			if (orphan != null && Objects.equals(entityPrimaryKey, orphan.parentEntityPrimaryKey())) {
				children.add(orphanId);
			}
		}
		final TransactionalIntArray childrenArray = new TransactionalIntArray(children.toArray());
		final OfInt childrenIt = childrenArray.iterator();
		while (childrenIt.hasNext()) {
			int formerOrphanId = childrenIt.nextInt();
			this.orphans.remove(formerOrphanId);
			createChildrenSetFromOrphansRecursively(formerOrphanId);
		}
		this.levelIndex.put(entityPrimaryKey, childrenArray);
	}

	/**
	 * Recursively adds child nodes to `result`, traversing down to the specified number of `levels`.
	 *
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and subtrees are included
	 * @param result                      the array to accumulate matching primary keys into
	 * @param children                    the direct children of the current node
	 * @param levels                      remaining levels to traverse (0 = no further recursion)
	 */
	private void addRecursively(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate, @Nonnull CompositeIntArray result, @Nonnull TransactionalIntArray children, int levels) {
		final OfInt it = children.iterator();
		while (it.hasNext()) {
			int childId = it.nextInt();
			if (hierarchyFilteringPredicate.test(childId)) {
				result.add(childId);
				if (levels > 0) {
					final TransactionalIntArray childrenOfChildren = this.levelIndex.get(childId);
					if (childrenOfChildren != null) {
						addRecursively(hierarchyFilteringPredicate, result, childrenOfChildren, levels - 1);
					}
				}
			}
		}
	}

	/**
	 * Recursively counts child nodes, traversing down to the specified number of `levels`.
	 *
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and subtrees are counted
	 * @param children                    the direct children of the current node
	 * @param levels                      remaining levels to traverse (0 = no further recursion)
	 * @return count of matching nodes in the subtree
	 */
	private int countRecursively(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate, @Nonnull TransactionalIntArray children, int levels) {
		int sum = 0;
		final OfInt it = children.iterator();
		while (it.hasNext()) {
			int childId = it.nextInt();
			if (hierarchyFilteringPredicate.test(childId)) {
				sum++;
				if (levels > 0) {
					final TransactionalIntArray childrenOfChildren = this.levelIndex.get(childId);
					if (childrenOfChildren != null) {
						sum += countRecursively(hierarchyFilteringPredicate, childrenOfChildren, levels - 1);
					}
				}
			}
		}
		return sum;
	}

	/**
	 * Recursively appends indented string representations of child nodes to the builder.
	 *
	 * @param nodeIds the direct children to process
	 * @param indent  the current indentation level (multiplied by 3 for spaces)
	 * @param sb      the string builder to append to
	 */
	private void toStringChildrenRecursively(@Nonnull TransactionalIntArray nodeIds, int indent, @Nonnull StringBuilder sb) {
		final OfInt it = nodeIds.iterator();
		while (it.hasNext()) {
			int nodeId = it.nextInt();
			ofNullable(this.levelIndex.get(nodeId))
				.ifPresent(node -> {
					sb.append(" ".repeat(3 * indent)).append(nodeId).append("\n");
					toStringChildrenRecursively(node, indent + 1, sb);
				});
		}
	}

	/**
	 * Internal implementation shared by {@link #traverseHierarchy} and {@link #traverseHierarchyFromNode}.
	 * Traverses the hierarchy visiting nodes via the provided `visitor`, filtered by `predicate`.
	 *
	 * @param visitor       the visitor invoked for each traversed node
	 * @param rootNode      the starting node for the traversal, or `null` to start from all roots
	 * @param excludingRoot if non-null and `true`, the `rootNode` is skipped (only its children are visited)
	 * @param predicate     predicate that filters which nodes to visit
	 */
	private void traverseHierarchyInternal(
		@Nonnull HierarchyVisitor visitor,
		@Nullable Integer rootNode,
		@Nullable Boolean excludingRoot,
		@Nonnull HierarchyFilteringPredicate predicate
	) {
		final TraverserFactory childrenTraverseCreator = getTraverserFactory(visitor, predicate);

		final Collection<HierarchyNode> rootNodes;
		final int level;
		final int distance;
		if (rootNode == null) {
			level = 1;
			distance = 1;
			rootNodes = Arrays.stream(this.roots.getArray())
				.filter(predicate)
				.mapToObj(this.itemIndex::get)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		} else if (ofNullable(excludingRoot).orElse(false)) {
			final HierarchyNode rootHierarchyNode = this.itemIndex.get(rootNode);
			if (rootHierarchyNode == null) {
				level = 0;
				rootNodes = Collections.emptyList();
			} else {
				level = this.computeLevel(rootHierarchyNode);
				rootNodes = ofNullable(this.levelIndex.get(rootNode))
					.stream()
					.flatMapToInt(TransactionalIntArray::stream)
					.filter(predicate)
					.mapToObj(this.itemIndex::get)
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			}
			distance = 1;
		} else {
			final HierarchyNode rootHierarchyNode = this.itemIndex.get(rootNode);
			if (rootHierarchyNode == null) {
				rootNodes = Collections.emptyList();
				level = 0;
			} else {
				rootNodes = Collections.singletonList(rootHierarchyNode);
				level = this.computeLevel(rootHierarchyNode);
			}
			distance = 0;
		}

		for (HierarchyNode examinedNode : rootNodes) {
			visitor.visit(
				examinedNode, level, distance,
				childrenTraverseCreator.apply(examinedNode.entityPrimaryKey(), level + 1, distance + 1)
			);
		}
	}

	/**
	 * Creates a {@link TraverserFactory} that produces child-visiting {@link Runnable} lambdas for
	 * use in hierarchy traversal. The factory uses an {@link java.util.concurrent.atomic.AtomicReference}
	 * to hold a reference to itself, enabling the produced runnables to recursively create runnables
	 * for the next depth level without explicit recursion — each runnable retrieves the factory from
	 * the holder at call time to generate the traversal continuation for its own children.
	 *
	 * The produced runnables look up the direct children of the given parent node from `levelIndex`,
	 * filter them through `predicate`, resolve them via `itemIndex`, and invoke `visitor` for each
	 * surviving child together with a freshly produced child-level runnable.
	 *
	 * @param visitor   the visitor invoked for each node encountered during traversal
	 * @param predicate predicate that filters which child nodes are visited; nodes that do not match
	 *                  are skipped along with their entire subtree
	 * @return a self-referencing {@link TraverserFactory} ready for recursive traversal
	 */
	@Nonnull
	private TraverserFactory getTraverserFactory(
		@Nonnull HierarchyVisitor visitor,
		@Nonnull HierarchyFilteringPredicate predicate
	) {
		final AtomicReference<TraverserFactory> factoryHolder = new AtomicReference<>();
		final TraverserFactory childrenTraverseCreator = (childrenId, level, distance) ->
			() -> {
				final Collection<HierarchyNode> children = ofNullable(this.levelIndex.get(childrenId))
					.map(it ->
						it.stream()
							.filter(predicate)
							.mapToObj(this.itemIndex::get)
							.filter(Objects::nonNull)
							.collect(Collectors.toList())
					)
					.orElse(Collections.emptyList());
				for (HierarchyNode child : children) {
					visitor.visit(
						child, level, distance,
						factoryHolder.get().apply(child.entityPrimaryKey(), level + 1, distance + 1)
					);
				}
			};
		factoryHolder.set(childrenTraverseCreator);
		return childrenTraverseCreator;
	}

	/**
	 * Returns the level of the passed hierarchy node in the hierarchy tree.
	 *
	 * @param rootNode the node to compute level for
	 * @return level of the node or -1 if the node is not part of the tree
	 */
	private int computeLevel(@Nonnull HierarchyNode rootNode) {
		try {
			int level = 1;
			HierarchyNode theNode = rootNode;
			while (theNode.parentEntityPrimaryKey() != null) {
				final Optional<HierarchyNode> parentNode = getParentNodeOrThrowException(theNode);
				if (parentNode.isPresent()) {
					theNode = parentNode.get();
					level++;
				} else {
					return -1;
				}
			}
			return level;
		} catch (EvitaInvalidUsageException ex) {
			return -1;
		}
	}

	/**
	 * Creates a formula that contains all hierarchy nodes except orphans.
	 */
	@Nonnull
	private Formula createAllHierarchyNodesFormula() {
		final Set<Integer> nodeIds = this.itemIndex.keySet();
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (Integer nodeId : nodeIds) {
			writer.add(nodeId);
		}
		final RoaringBitmap roaringBitmap = writer.get();

		final OfInt it = this.orphans.iterator();
		while (it.hasNext()) {
			roaringBitmap.remove(it.next());
		}
		return roaringBitmap.isEmpty() ?
			EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(roaringBitmap));
	}

	/**
	 * Method resets all memoized values.
	 */
	private void resetMemoizedValues() {
		this.memoizedAllNodeFormula = null;
	}

	/**
	 * Performs a breadth-first traversal of a hierarchy tree starting from the specified root node.
	 * Traverses the tree level by level, applying a sorter to children at each level, and stores the traversal result.
	 *
	 * @param previousLevelStart index of the first parent node in the result array
	 * @param levelSorter        a {@link UnaryOperator} to sort the children nodes at each level during the traversal
	 * @param result             a {@link CompositeIntArray} to store the result of the traversal
	 */
	private void breadthFirstTraversal(
		int previousLevelStart,
		@Nonnull UnaryOperator<int[]> levelSorter,
		@Nonnull CompositeIntArray result
	) {
		final int initialSize = result.getSize();
		int cnt = 0;
		final OfInt it = result.iteratorFrom(previousLevelStart);
		final int terminalCnt = initialSize - previousLevelStart;
		while (it.hasNext() && cnt++ < terminalCnt) {
			int rootNodeId = it.next();
			final TransactionalIntArray children = this.levelIndex.get(rootNodeId);
			if (children != null) {
				final int[] childrenIds = children.getArray();
				if (childrenIds.length > 0) {
					final int[] currentLevel = levelSorter.apply(childrenIds);
					result.addAll(currentLevel, 0, currentLevel.length);
				}
			}
		}
		if (result.getSize() > initialSize) {
			breadthFirstTraversal(initialSize, levelSorter, result);
		}
	}

	/**
	 * Performs a depth-first traversal of a hierarchy tree starting from the specified root node.
	 * Traverses the tree recursively, applying a sorter to children at each level, and stores the traversal result.
	 *
	 * @param rootNodeId  the ID of the root node from which to start the traversal
	 * @param levelSorter a {@link UnaryOperator} to sort the children nodes at each level during the traversal
	 * @param result      a {@link CompositeIntArray} to store the result of the traversal
	 */
	private void depthFirstTraversal(
		int rootNodeId,
		@Nonnull UnaryOperator<int[]> levelSorter,
		@Nonnull CompositeIntArray result
	) {
		final TransactionalIntArray children = this.levelIndex.get(rootNodeId);
		if (children != null) {
			final int[] childrenIds = children.getArray();
			final int[] currentLevel = levelSorter.apply(childrenIds);
			for (int nodeId : currentLevel) {
				result.add(nodeId);
				depthFirstTraversal(nodeId, levelSorter, result);
			}
		}
	}

	/**
	 * Interface allows to define a factory function accepting multiple placement information and create a traverser
	 * logic from it.
	 */
	private interface TraverserFactory {

		/**
		 * Creates a lambda that will traverse contents of the `hierarchyNodeId` on specific `level` and `distance` from
		 * the originally accessed root node.
		 */
		@Nonnull
		Runnable apply(int hierarchyNodeId, int level, int distance);

	}

}
