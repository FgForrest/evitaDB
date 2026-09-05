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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.component.IndexComponent;
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
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

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
 * are collected in the `orphans` array until their parent dependency is fulfilled. When the time comes they are moved
 * from `orphans` into `levelIndex`, which holds the direct children of every node reachable from a root.
 *
 * The tree can be reconstructed by traversing the `roots` array, acquiring their children from `levelIndex` and
 * scanning deeply level by level using the same index. Nodes in `roots` and in the `levelIndex` values are sorted by
 * primary key in ascending order so that the entire hierarchy tree is available immediately after the scan.
 *
 * **Those structures are allocated by the first node written, not by construction.** Every entity index in the catalog
 * owns a hierarchy index whether or not its entity type is hierarchical, and almost none are — so until a node arrives
 * this index holds nothing at all, and every read resolves that absence to "the hierarchy is empty": an empty bitmap,
 * a zero count, or {@link EmptyFormula#INSTANCE}. The allocation and publication rules are on {@link #nodeStore}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyIndex
	implements HierarchyIndexContract,
	VoidTransactionMemoryProducer<HierarchyIndex>,
	IndexDataStructure, IndexComponent, Serializable {
	@Serial private static final long serialVersionUID = 4121668650337515744L;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * The four transactional structures a populated hierarchy is made of, or `null` while this index holds no node
	 * at all.
	 *
	 * # Why the four structures are allocated together, and only on demand
	 *
	 * Every entity index in the catalog owns a {@link HierarchyIndex}, whether or not its entity type is
	 * hierarchical, and almost none of them are: a production e-commerce catalog carried 564,187 entity indexes and
	 * 647 hierarchy nodes in total. The four structures below cost 208 B per index even when they hold nothing, which
	 * is over a hundred megabytes of empty scaffolding on such a catalog.
	 *
	 * They are therefore held in one {@link HierarchyNodeStore}, allocated by the first node written and **absent**
	 * until then. One holder rather than four lazy fields, because the four are not independent: a single
	 * {@link #addNode} populates {@link HierarchyNodeStore#itemIndex()} and one of the other three, so per-field
	 * laziness would recover nothing that per-store laziness does not, and would cost four null checks per read
	 * instead of one.
	 *
	 * Three properties make it safe, and none of them may be dropped:
	 *
	 * - **The field is `volatile`.** A {@link TransactionalMap}'s own fields are final, but the state of the
	 *   structures it wraps is not, so without the volatile write a concurrent reader could observe a half-published
	 *   store.
	 * - **Creation is double-checked under `synchronized (this)`.** Two concurrent write transactions can reach the
	 *   same index; if both built a store, the loser's diff layers would be keyed on orphan instances while every
	 *   later read found the winner, losing that transaction's writes silently.
	 * - **The merge copy re-checks emptiness.** {@link #createCopyWithMergedTransactionalMemory} rebuilds the index
	 *   from the committed structures, and the four-argument constructor leaves the store absent when all four came
	 *   back empty, so a hierarchy that was emptied does not carry the scaffolding forward for ever.
	 *
	 * **Persistence is unchanged.** The store is a purely in-memory grouping: {@link #collectModifiedStorageParts}
	 * still emits the same {@link HierarchyIndexStoragePart} from the same four structures, and an absent store means
	 * an empty hierarchy, which {@link #isHierarchyIndexEmpty} already reports as "nothing to write". No storage part,
	 * no manifest entry and no serializer sees this field.
	 *
	 * **An absent store reads as an empty hierarchy**, in every accessor — including the five `...Formula` methods,
	 * which hand back {@link EmptyFormula#INSTANCE} instead of a {@link DeferredFormula}. That substitution is an
	 * equivalence rather than a shortcut: every supplier such a formula would have wrapped
	 * ({@link HierarchyRootsBitmapSupplier}, {@link HierarchyRootsDownBitmapSupplier},
	 * {@link HierarchyByParentBitmapSupplier}, {@link HierarchyByParentIncludingSelfBitmapSupplier},
	 * {@link HierarchyForParentBitmapSupplier}) resolves to an accessor that answers an absent store with an empty
	 * bitmap and none of them asserts node presence — so the deferred computation could only ever have produced
	 * empty, and {@link #getAllHierarchyNodesFormula} already collapses an empty bitmap to that same constant.
	 *
	 * The two exceptions are {@link #listHierarchyNodesFromParentDownTo} and
	 * {@link #getHierarchyNodeCountFromParentDownTo}, which owe the caller a rejection for a parent the hierarchy
	 * does not hold. They resolve the store through {@link #assertNodeInIndex}, so an index that never received a
	 * node rejects an unknown parent exactly as one holding a tree does.
	 *
	 * A first write that is later rolled back leaves an empty store behind on the pre-commit instance, because a
	 * write inside a transaction has to have somewhere to put its diff. It is bounded by what construction used to
	 * cost unconditionally, and the committed copy is rebuilt without it.
	 */
	@Nullable private volatile HierarchyNodeStore nodeStore;
	/**
	 * Contains cached result of {@link #getAllHierarchyNodesFormula()} call.
	 *
	 * # Only the bitmap is memoized, never the formula wrapping it
	 *
	 * A {@link Formula} node carries **per-query** state:
	 * {@link io.evitadb.core.query.algebra.AbstractFormula#initialize(io.evitadb.core.query.QueryExecutionContext)}
	 * writes the executing query's context onto every node of the plan it is part of, and that context transitively
	 * reaches the session and the entire catalog generation the query ran against. A formula held for the lifetime
	 * of this index would therefore pin the first session that ever used it until the hierarchy is next written to.
	 *
	 * Memoizing the bitmap keeps the expensive part — the `O(nodes)` walk in
	 * {@link #createAllHierarchyNodesBitmap()} — and the {@link ConstantFormula} built around it per call is a
	 * handful of bytes over the shared bitmap. It is cheap in CPU too, but only because the same instance is handed
	 * out every time: the memo is a `BaseBitmap` with no transactional id, so the formula's cache key comes from
	 * hashing its contents, and {@link io.evitadb.index.bitmap.Bitmap#getContentHash} memoizes that `O(nodes)` walk
	 * on the bitmap. See `FilterIndex#memoizedAllRecords` for the measured figures.
	 *
	 * Do not turn this back into a `Formula` field.
	 */
	@Nullable private volatile Bitmap memoizedAllNodes;

	/**
	 * Creates a new empty hierarchy index.
	 */
	public HierarchyIndex() {
		this.dirty = new TransactionalBoolean();
		// the node store is left absent - the first node written allocates it through getOrCreateNodeStore()
		this.memoizedAllNodes = EmptyBitmap.INSTANCE;
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
		// a hierarchy that came back empty from the commit merge (or from disk) is left with NO store at all, which is
		// what stops an emptied hierarchy carrying its scaffolding forward into every later snapshot
		this.nodeStore = roots.length == 0 && levelIndex.isEmpty() && itemIndex.isEmpty() && orphans.length == 0 ?
			null :
			new HierarchyNodeStore(
				new TransactionalIntArray(roots),
				new TransactionalMap<>(levelIndex, TransactionalIntArray.class, TransactionalIntArray::new),
				new TransactionalMap<>(itemIndex),
				new TransactionalIntArray(orphans)
			);
		this.memoizedAllNodes = createAllHierarchyNodesBitmap();
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
	 * The four transactional structures a populated hierarchy is made of, grouped so they can be allocated together
	 * on the first node written and stay absent until then — see the {@link #nodeStore} javadoc for why they are one
	 * unit rather than four lazy fields.
	 *
	 * A carrier and nothing more: it owns no behaviour, is never replaced once published (the structures inside it
	 * are what change), and is never handed outside this class.
	 *
	 * @param roots      entity primary keys of all entities placed at root level, sorted ascending
	 * @param levelIndex direct children of every entity reachable from a root, keyed by the parent's primary key.
	 *                   Every reachable entity has an entry — possibly an empty array — and an unreachable one has
	 *                   none, which is why a `null` lookup here *is* the reachability test
	 * @param itemIndex  every entity placed in this hierarchy — roots, reachable descendants and orphans alike —
	 *                   keyed by its primary key, so its size is the whole population the index knows about
	 * @param orphans    entity primary keys of all entities not reachable from any root, including the descendants
	 *                   of an orphaned parent
	 */
	private record HierarchyNodeStore(
		@Nonnull TransactionalIntArray roots,
		@Nonnull TransactionalMap<Integer, TransactionalIntArray> levelIndex,
		@Nonnull TransactionalMap<Integer, HierarchyNode> itemIndex,
		@Nonnull TransactionalIntArray orphans
	) {
	}

	/**
	 * Returns the node store, allocating it on the first write that needs it.
	 *
	 * Never call this from a read path: a read resolves an absent store to "the hierarchy is empty", and
	 * materialising the four structures in order to find nothing in them would give back exactly the scaffolding this
	 * laziness exists to avoid. The double-checked publication is explained on {@link #nodeStore}.
	 *
	 * @return the store, freshly created when this is the first node written to this index
	 */
	@Nonnull
	private HierarchyNodeStore getOrCreateNodeStore() {
		final HierarchyNodeStore existing = this.nodeStore;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			if (this.nodeStore == null) {
				this.nodeStore = new HierarchyNodeStore(
					new TransactionalIntArray(ArrayUtils.EMPTY_INT_ARRAY),
					new TransactionalMap<>(new HashMap<>(32), TransactionalIntArray.class, TransactionalIntArray::new),
					new TransactionalMap<>(new HashMap<>(32)),
					new TransactionalIntArray()
				);
			}
			return this.nodeStore;
		}
	}

	/**
	 * Initializes root nodes from an existing bitmap during the bootstrap phase.
	 * This method must be called only once, before any nodes are added to the index.
	 *
	 * The index is marked dirty even when `rootNodes` is empty and no store is therefore allocated, so a later
	 * flush still owes an (empty) storage part — which is why {@link #createStoragePart} carries a branch for an
	 * absent store.
	 *
	 * @param rootNodes bitmap of entity primary keys to register as root hierarchy nodes
	 * @throws GenericEvitaInternalError if the index already contains items
	 */
	@Override
	public void initRootNodes(@Nonnull Bitmap rootNodes) {
		final HierarchyNodeStore existing = this.nodeStore;
		Assert.isPremiseValid(
			existing == null || existing.itemIndex().isEmpty(),
			"This method should be called only for bootstrap!"
		);

		this.dirty.setToTrue();
		if (rootNodes.isEmpty()) {
			// a bootstrap that brings no node must not materialise the store - see the nodeStore javadoc
			return;
		}
		final HierarchyNodeStore store = getOrCreateNodeStore();
		for (Integer rootNode : rootNodes) {
			final HierarchyNode newHierarchyNode = new HierarchyNode(rootNode, null);
			store.itemIndex().put(rootNode, newHierarchyNode);
			store.roots().add(rootNode);
			store.levelIndex().put(rootNode, new TransactionalIntArray());
		}
		if (!isTransactionAvailable()) {
			// the constructor seeds the memo with an empty bitmap, and the roots registered above have just made it
			// wrong - outside a transaction the memo is what every all-nodes read is served from
			resetMemoizedValues();
		}
	}

	/**
	 * Registers a new node in the hierarchy or updates its parent reference.
	 *
	 * If the node previously existed in the hierarchy, it is first removed from its old position
	 * before being placed at the new location. If the parent is not yet registered, the node is
	 * placed among the `orphans` until its parent becomes available.
	 *
	 * This is the write that allocates the node store on an index that has never held one.
	 *
	 * @param entityPrimaryKey the primary key of the entity to register
	 * @param parentPrimaryKey the primary key of the entity's parent, or `null` for root-level nodes
	 * @throws EvitaInvalidUsageException if the entity is passed as its own parent
	 */
	@Override
	public void addNode(int entityPrimaryKey, @Nullable Integer parentPrimaryKey) {
		Assert.isTrue(
			parentPrimaryKey == null || parentPrimaryKey != entityPrimaryKey,
			"Entity cannot refer to itself in a hierarchy placement!"
		);

		this.dirty.setToTrue();
		final HierarchyNode newHierarchyNode = new HierarchyNode(entityPrimaryKey, parentPrimaryKey);
		final HierarchyNodeStore store = getOrCreateNodeStore();

		// remove previous location
		internalRemoveHierarchy(store, entityPrimaryKey);
		// register new location
		store.itemIndex().put(entityPrimaryKey, newHierarchyNode);

		if (parentPrimaryKey == null) {
			store.roots().add(entityPrimaryKey);
			// create the children set
			createChildrenSetFromOrphansRecursively(store, entityPrimaryKey);
		} else {
			final Optional<TransactionalIntArray> parentRef = ofNullable(store.levelIndex().get(parentPrimaryKey));
			if (parentRef.isPresent()) {
				parentRef.get().add(entityPrimaryKey);
				// create the children set
				createChildrenSetFromOrphansRecursively(store, entityPrimaryKey);
			} else {
				store.orphans().add(entityPrimaryKey);
			}
		}
		if (!isTransactionAvailable()) {
			resetMemoizedValues();
		}
	}

	/**
	 * Removes a node from the hierarchy and makes all its children orphans.
	 *
	 * The removed node's children are recursively moved to the `orphans` collection because they are no longer
	 * reachable from any root.
	 *
	 * @param entityPrimaryKey the primary key of the entity to remove from the hierarchy
	 * @return the primary key of the removed node's parent, or `null` if the node was a root
	 * @throws EvitaInvalidUsageException if no hierarchy placement was set for the given entity
	 */
	@Override
	public Integer removeNode(int entityPrimaryKey) {
		final HierarchyNodeStore store = this.nodeStore;
		// nothing was ever placed in this hierarchy, so there is no placement to remove - the assertion below is the
		// same failure an unknown entity primary key produced before the store became lazy
		final HierarchyNode removedNode = store == null ? null : internalRemoveHierarchy(store, entityPrimaryKey);
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		final int[] rootNodeIds = store.roots().getArray();
		final int[] currentLevel = levelSorter.apply(rootNodeIds);

		// now execute the traversal
		if (traversalMode == TraversalMode.DEPTH_FIRST) {
			for (int rootNodeId : currentLevel) {
				result.add(rootNodeId);
				depthFirstTraversal(store, rootNodeId, levelSorter, result);
			}
		} else {
			result.addAll(currentLevel, 0, currentLevel.length);
			breadthFirstTraversal(store, 0, levelSorter, result);
		}
		return result.isEmpty() ?
			EmptyBitmap.INSTANCE : new ArrayBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all hierarchy nodes from the roots downward,
	 * filtered by the provided predicate.
	 *
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and their subtrees are included
	 * @return deferred formula with the set of matching hierarchy node primary keys, or
	 * {@link EmptyFormula#INSTANCE} while this index holds no node — the same set the deferred computation would
	 * have produced
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromRootFormula(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// nothing to defer: an absent store provably answers empty, and owns no transactional id to key a memo
			// on - the equivalence argument is on the nodeStore field
			return EmptyFormula.INSTANCE;
		}
		return new DeferredFormula(
			new HierarchyRootsDownBitmapSupplier(
				this, new long[]{store.roots().getId(), store.levelIndex().getId()},
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		for (int nodeId : store.roots().getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				result.add(nodeId);
				final TransactionalIntArray children = store.levelIndex().get(nodeId);
				if (children != null) {
					addRecursively(store, hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		for (Integer nodeId : store.roots().getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				result.add(nodeId);
				final TransactionalIntArray children = store.levelIndex().get(nodeId);
				if (children != null) {
					addRecursively(store, hierarchyFilteringPredicate, result, children, levels - 1);
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
	 * @return deferred formula with the set of matching hierarchy node primary keys, or
	 * {@link EmptyFormula#INSTANCE} while this index holds no node — the same set the deferred computation would
	 * have produced
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentIncludingItselfFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// nothing to defer: an absent store provably answers empty, and owns no transactional id to key a memo
			// on - the equivalence argument is on the nodeStore field
			return EmptyFormula.INSTANCE;
		}
		return new DeferredFormula(
			new HierarchyByParentIncludingSelfBitmapSupplier(
				this, new long[]{store.roots().getId(), store.levelIndex().getId()},
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
	 * @return bitmap of matching hierarchy node primary keys, or an empty bitmap if the parent is excluded by the
	 * predicate or is not present in the index
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItself(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			if (store.itemIndex().containsKey(parentNode)) {
				result.add(parentNode);
			} else {
				return EmptyBitmap.INSTANCE;
			}
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			if (children != null) {
				addRecursively(store, hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
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
	 * @return bitmap of matching hierarchy node primary keys, or an empty bitmap if the parent is excluded or is
	 * not present in the index
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentIncludingItselfDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			if (!store.itemIndex().containsKey(parentNode)) {
				// a primary key this hierarchy does not hold heads no subtree of it, so it must not reach the result
				// - the depth-unlimited sibling rejects it the same way
				return EmptyBitmap.INSTANCE;
			}
			result.add(parentNode);
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			if (children != null) {
				addRecursively(store, hierarchyFilteringPredicate, result, children, levels - 1);
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
	 * @return deferred formula with the set of matching hierarchy node primary keys, or
	 * {@link EmptyFormula#INSTANCE} while this index holds no node — the same set the deferred computation would
	 * have produced
	 */
	@Override
	@Nonnull
	public Formula getListHierarchyNodesFromParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// nothing to defer: an absent store provably answers empty, and owns no transactional id to key a memo
			// on - the equivalence argument is on the nodeStore field
			return EmptyFormula.INSTANCE;
		}
		return new DeferredFormula(
			new HierarchyByParentBitmapSupplier(
				this, new long[]{store.roots().getId(), store.levelIndex().getId()},
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			if (children != null) {
				addRecursively(store, hierarchyFilteringPredicate, result, children, Integer.MAX_VALUE);
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
	 * @throws EvitaInvalidUsageException if the parent node is not present in the index — including on an index that
	 *                                    has never received a node, which holds it just as little
	 */
	@Override
	@Nonnull
	public Bitmap listHierarchyNodesFromParentDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = assertNodeInIndex(this.nodeStore, parentNode);
		final CompositeIntArray result = new CompositeIntArray();
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				addRecursively(store, hierarchyFilteringPredicate, result, children, levels);
			}
		}
		return new BaseBitmap(result.toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates all root-level hierarchy nodes, filtered by the
	 * provided predicate.
	 *
	 * @param excludedNodeTrees predicate that controls which root nodes and their subtrees are excluded
	 * @return deferred formula with the set of matching root hierarchy node primary keys, or
	 * {@link EmptyFormula#INSTANCE} while this index holds no node — the same set the deferred computation would
	 * have produced
	 */
	@Nonnull
	@Override
	public Formula getRootHierarchyNodesFormula(@Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// nothing to defer: an absent store provably answers empty, and owns no transactional id to key a memo
			// on - the equivalence argument is on the nodeStore field
			return EmptyFormula.INSTANCE;
		}
		return new DeferredFormula(
			new HierarchyRootsBitmapSupplier(
				this,
				new long[]{store.roots().getId(), store.levelIndex().getId()},
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		return store.roots().isEmpty() ?
			EmptyBitmap.INSTANCE :
			new BaseBitmap(Arrays.stream(store.roots().getArray()).filter(hierarchyFilteringPredicate).toArray());
	}

	/**
	 * Returns a deferred formula that lazily evaluates the parent node together with its direct children,
	 * filtered by the provided predicate.
	 *
	 * @param parentNode        the primary key of the node whose group is to be computed
	 * @param excludedNodeTrees predicate that controls which nodes are excluded from the result
	 * @return deferred formula with the parent node and its direct children primary keys, or
	 * {@link EmptyFormula#INSTANCE} while this index holds no node — the same set the deferred computation would
	 * have produced
	 */
	@Nonnull
	@Override
	public Formula getHierarchyNodesForParentFormula(int parentNode, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// nothing to defer: an absent store provably answers empty, and owns no transactional id to key a memo
			// on - the equivalence argument is on the nodeStore field
			return EmptyFormula.INSTANCE;
		}
		return new DeferredFormula(
			new HierarchyForParentBitmapSupplier(
				this, new long[]{store.roots().getId(), store.levelIndex().getId()},
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final HierarchyNode theParentNode = store.itemIndex().get(parentNode);
		if (theParentNode == null) {
			return EmptyBitmap.INSTANCE;
		} else {
			final TransactionalIntArray childrenIds = store.levelIndex().get(parentNode);
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
	 * @throws EvitaInvalidUsageException if the node is not present in the index
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
	 * @throws EvitaInvalidUsageException if any node in the input is not present in the index
	 */
	@Nonnull
	@Override
	public Bitmap listNodesIncludingParents(@Nonnull Bitmap nodes) {
		final PersistentRoaringBitmap output = new PersistentRoaringBitmap();
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return;
		}
		final HierarchyNode theNode = store.itemIndex().get(node);
		// if the node is missing, just skip traversal
		if (theNode != null) {
			HierarchyNode hierarchyNode = theNode;
			int nodeLevel = 1;
			while (hierarchyNode.parentEntityPrimaryKey() != null) {
				nodeLevel++;
				final Optional<HierarchyNode> parentNode = getParentNodeOrThrowException(store, hierarchyNode);
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
	 * Once a store exists a fresh {@link BaseBitmap} is materialised on every call, even when there is no orphan at
	 * all; only an index that has never received a node hands back the shared {@link EmptyBitmap#INSTANCE}. Polling
	 * this in a loop therefore allocates per call — what it returns is never retained by the index.
	 *
	 * @return bitmap of orphan entity primary keys
	 */
	@Override
	@Nonnull
	public Bitmap getOrphanHierarchyNodes() {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		return new BaseBitmap(store.orphans().getArray());
	}

	/**
	 * Returns the count of hierarchy nodes that are reachable from the roots (i.e., excluding orphans).
	 *
	 * @return number of non-orphan hierarchy nodes
	 */
	@Override
	public int getHierarchySize() {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		return store.itemIndex().size() - store.orphans().getLength();
	}

	/**
	 * Returns the total count of all hierarchy nodes including orphans.
	 *
	 * @return total number of registered hierarchy nodes
	 */
	@Override
	public int getHierarchySizeIncludingOrphans() {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		return store.itemIndex().size();
	}

	/**
	 * Returns `true` if the hierarchy index contains no nodes at all (neither roots nor orphans).
	 *
	 * @return `true` if the index is empty
	 */
	@Override
	public boolean isHierarchyIndexEmpty() {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return true;
		}
		return store.itemIndex().isEmpty();
	}

	/**
	 * Returns the heap this hierarchy occupies, in bytes — the node index, the per-parent children index, both id
	 * arrays and the memoized all-nodes bitmap. The carrier grouping the first four is charged only once it has been
	 * allocated, so an index that never received a node is priced at its own object and its dirty flag alone.
	 *
	 * Every boxed id is charged to the structure holding it, including a node's parent key even where an equal box is
	 * a key of the node index: the two are boxed at different sites and are two objects, and rule 1 charges a box per
	 * holder rather than letting `-XX:AutoBoxCacheMax` decide what the reading says.
	 *
	 * {@link #memoizedAllNodes} is the one memo in the index layer that is charged **unconditionally**. It is built
	 * by writing every node id and removing the orphans, so nothing else in the catalog holds it — unlike a filter
	 * index's all-records memo, which resolves to the value tree's own bitmap whenever the tree has a single bucket
	 * and is therefore charged only above that. No formula scaffolding is priced here because none is retained: the
	 * {@link ConstantFormula} wrapping this bitmap is built fresh per call and dies with the query. An empty
	 * hierarchy memoizes {@link EmptyBitmap#INSTANCE} and charges nothing at all.
	 *
	 * This walks every node, so it is `O(nodes)` — it belongs to the index detail call and must never be called from a
	 * query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// a HierarchyNode is a record of the node's own primary key and a boxed parent key, which is this node's
		// alone - a root node has none and pays only for the slot
		final long hierarchyNode = layout.sizeOfObject(Integer.BYTES + layout.referenceSize());
		// id, then the dirty / nodeStore / memoizedAllNodes slots
		long size = layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes();
		final HierarchyNodeStore store = this.nodeStore;
		if (store != null) {
			// the store's own carrier object, then the four structures it groups. An ABSENT store is charged nothing,
			// which is what it costs: an index whose entity type is not hierarchical never allocates one
			size += layout.sizeOfObject(4L * layout.referenceSize())
				+ store.roots().getHeapSizeInBytes()
				+ store.orphans().getHeapSizeInBytes()
				+ store.itemIndex().getHeapSizeInBytes(
					key -> boxedInteger,
					node -> node.parentEntityPrimaryKey() == null ? hierarchyNode : hierarchyNode + boxedInteger
				)
				+ store.levelIndex().getHeapSizeInBytes(key -> boxedInteger, TransactionalIntArray::getHeapSizeInBytes);
		}
		final Bitmap memoizedNodes = this.memoizedAllNodes;
		if (memoizedNodes != null) {
			// the node-id bitmap this index materialized for the cache - nothing else in the catalog holds it
			size += memoizedNodes.getHeapSizeInBytes();
		}
		return size;
	}

	/**
	 * Method returns formula that contains all nodes attached to the tree (i.e. every node except the `orphans`).
	 *
	 * A **fresh** formula is returned on every call. What is memoized is the bitmap behind it — see
	 * {@link #memoizedAllNodes} for why an index must never hand out the same formula instance twice.
	 *
	 * @return {@link ConstantFormula} over the attached nodes, or {@link EmptyFormula#INSTANCE} when there are none
	 */
	@Nonnull
	public Formula getAllHierarchyNodesFormula() {
		final Bitmap allNodes = getAllHierarchyNodes();
		return allNodes.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(allNodes);
	}

	/**
	 * Method returns bitmap of all nodes attached to the tree (i.e. every node except the `orphans`). The result is
	 * memoized outside transactions and recomputed while a transaction holds uncommitted hierarchy changes.
	 *
	 * @return bitmap of every node reachable from a root, empty while the hierarchy holds none
	 */
	@Nonnull
	public Bitmap getAllHierarchyNodes() {
		// if there is transaction open, and there are changes in the hierarchy data, we can't use the cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return createAllHierarchyNodesBitmap();
		} else {
			Bitmap result = this.memoizedAllNodes;
			if (result == null) {
				result = createAllHierarchyNodesBitmap();
				this.memoizedAllNodes = result;
			}
			return result;
		}
	}

	/**
	 * Returns count of children nodes from root down to specified count of levels.
	 *
	 * @param levels                      maximum depth to traverse (1 = roots + direct children)
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees are counted
	 * @return number of matching nodes, the roots themselves included; 0 while the hierarchy holds no node
	 */
	public int getHierarchyNodeCountFromRootDownTo(int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		int sum = 0;
		for (Integer nodeId : store.roots().getArray()) {
			if (hierarchyFilteringPredicate.test(nodeId)) {
				sum++;
				final TransactionalIntArray children = store.levelIndex().get(nodeId);
				if (children != null) {
					sum += countRecursively(store, hierarchyFilteringPredicate, children, levels - 1);
				}
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` excluding the subtrees defined in `hierarchyFilteringPredicate`.
	 *
	 * @param parentNode                  the primary key of the parent whose descendants are counted
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees are counted
	 * @return number of matching descendants, the parent itself excluded; 0 when the parent heads no subtree here
	 */
	public int getHierarchyNodeCountFromParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		int sum = 0;
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			if (children != null) {
				sum += countRecursively(store, hierarchyFilteringPredicate, children, Integer.MAX_VALUE);
			}
		}
		return sum;
	}

	/**
	 * Returns count of children of the `parentNode` down to specified count of `levels` excluding the subtrees defined
	 * in `hierarchyFilteringPredicate`.
	 *
	 * @param parentNode                  the primary key of the parent whose descendants are counted
	 * @param levels                      maximum depth to traverse (0 = direct children only)
	 * @param hierarchyFilteringPredicate predicate determining which nodes and subtrees are counted
	 * @return the subtree size, in which the direct children of `parentNode` are counted **twice** — once
	 * unfiltered and once again through the predicate-driven recursion. That arithmetic is the established
	 * expectation of this method; the sibling {@link #getHierarchyNodeCountFromParent} counts each node once.
	 * @throws EvitaInvalidUsageException if the parent node is not present in the index — including on an index
	 *                                    that has never received a node, which holds it just as little
	 */
	public int getHierarchyNodeCountFromParentDownTo(int parentNode, int levels, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = assertNodeInIndex(this.nodeStore, parentNode);
		int sum = 0;
		if (hierarchyFilteringPredicate.test(parentNode)) {
			final TransactionalIntArray children = store.levelIndex().get(parentNode);
			// requested node might be in the orphans
			if (children != null) {
				sum += children.getLength();
				sum += countRecursively(store, hierarchyFilteringPredicate, children, levels);
			}
		}
		return sum;
	}

	/**
	 * Returns count of root hierarchy nodes.
	 *
	 * @param hierarchyFilteringPredicate predicate determining which root nodes are counted
	 * @return number of matching root nodes; 0 while the hierarchy holds no node
	 */
	public int getRootHierarchyNodeCount(@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		return store.roots().isEmpty() ?
			0 :
			(int) (Arrays.stream(store.roots().getArray()).filter(hierarchyFilteringPredicate).count());
	}

	/**
	 * Returns count of the `parentNode` together with its direct children — the size of the bitmap
	 * {@link #getHierarchyNodesForParent} builds, not the number of children alone.
	 *
	 * @param parentNode                  the primary key of the parent node
	 * @param hierarchyFilteringPredicate predicate determining which of the parent and its children are counted
	 * @return number of matching nodes, or 0 if the parent is not present in the index. A childless parent counts 1
	 * without consulting the predicate, mirroring the bitmap variant.
	 */
	public int getHierarchyNodeCountForParent(int parentNode, @Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return 0;
		}
		final HierarchyNode theParentNode = store.itemIndex().get(parentNode);
		if (theParentNode == null) {
			return 0;
		} else {
			final TransactionalIntArray childrenIds = store.levelIndex().get(parentNode);
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written, which renders exactly as an allocated-but-empty hierarchy did: no root
			// lines, and an empty orphan list
			return "Orphans: []";
		}
		final StringBuilder sb = new StringBuilder(128);
		for (Integer rootId : store.roots().getArray()) {
			sb.append(rootId).append("\n");
			final TransactionalIntArray nodeIds = store.levelIndex().get(rootId);
			if (nodeIds != null) {
				toStringChildrenRecursively(store, nodeIds, 1, sb);
			}
		}
		sb.append("Orphans: ").append(store.orphans());
		return sb.toString();
	}

	/**
	 * Method creates container for storing any of hierarchy index from memory to the persistent storage.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index, which the written part is linked back to
	 * @return the part to write, or `null` when nothing has changed since the last flush. An index marked dirty by a
	 * bootstrap that brought no node still writes the empty shape an eagerly allocated hierarchy wrote.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			final HierarchyNodeStore store = this.nodeStore;
			if (store == null) {
				// a hierarchy that was marked dirty without ever receiving a node writes exactly what an eagerly
				// allocated empty one wrote - the persisted shape is unchanged by the store being absent
				return new HierarchyIndexStoragePart(
					entityIndexPrimaryKey, Map.of(), ArrayUtils.EMPTY_INT_ARRAY, new LevelIndex[0],
					ArrayUtils.EMPTY_INT_ARRAY
				);
			}
			return new HierarchyIndexStoragePart(
				entityIndexPrimaryKey, store.itemIndex(),
				store.roots().getArray(),
				store.levelIndex()
					.entrySet()
					.stream()
					.map(it -> new HierarchyIndexStoragePart.LevelIndex(it.getKey(), it.getValue().getArray()))
					.toArray(LevelIndex[]::new),
				store.orphans().getArray()
			);
		} else {
			return null;
		}
	}

	/**
	 * Component-loop entry point: emits a dirty `HierarchyIndexStoragePart` into `trappedChanges`
	 * if hierarchy data has changed, and announces presence into the manifest whenever this index
	 * carries any data — so the parent `EntityIndex` knows to flip the `hierarchyIndex` bit on the
	 * `EntityIndexStoragePart`.
	 *
	 * @param entityIndexPrimaryKey the parent entity index PK, used to link the storage part back
	 * @param manifest the shared manifest gathered for this commit cycle
	 * @param trappedChanges the accumulator collecting modified storage parts
	 */
	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		final StoragePart part = createStoragePart(entityIndexPrimaryKey);
		if (part != null) {
			trappedChanges.addChangeToStore(part);
		}
		if (!isHierarchyIndexEmpty()) {
			manifest.markHierarchyPresent();
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
	 * @param transactionalLayer the maintainer providing committed copies of transactional structures
	 * @return a new committed copy, or `this` when the index was not modified — or was marked dirty by a write
	 * that never reached a node, leaving no structure to merge
	 */
	@Nonnull
	@Override
	public HierarchyIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final HierarchyNodeStore store = this.nodeStore;
		if (isDirty && store != null) {
			// the four-argument constructor drops the store again when all four came back empty, so a hierarchy that
			// was emptied in this transaction does not carry its scaffolding into the next snapshot
			return new HierarchyIndex(
				transactionalLayer.getStateCopyWithCommittedChanges(store.roots()),
				transactionalLayer.getStateCopyWithCommittedChanges(store.levelIndex()),
				transactionalLayer.getStateCopyWithCommittedChanges(store.itemIndex()),
				transactionalLayer.getStateCopyWithCommittedChanges(store.orphans())
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
		this.dirty.removeLayer(transactionalLayer);
		final HierarchyNodeStore store = this.nodeStore;
		if (store != null) {
			// a store that was never allocated owns no diff layer, so there is nothing to discharge for it
			store.roots().removeLayer(transactionalLayer);
			store.levelIndex().removeLayer(transactionalLayer);
			store.itemIndex().removeLayer(transactionalLayer);
			store.orphans().removeLayer(transactionalLayer);
		}
	}

	/**
	 * Removes the hierarchy placement for the given entity, making all its children orphans.
	 * Shared by both {@link #addNode} (to clear a previous placement) and {@link #removeNode}.
	 *
	 * @param store            the node store the removal operates on, resolved once by the caller
	 * @param entityPrimaryKey the primary key of the entity to remove from the hierarchy
	 * @return the removed {@link HierarchyNode}, or `null` if the entity was not in the index
	 */
	@Nullable
	private HierarchyNode internalRemoveHierarchy(@Nonnull HierarchyNodeStore store, int entityPrimaryKey) {
		// remove optional previous location
		if (store.itemIndex().containsKey(entityPrimaryKey)) {
			final HierarchyNode previousLocation = store.itemIndex().remove(entityPrimaryKey);
			if (store.orphans().contains(entityPrimaryKey)) {
				// the node was already orphan - we can safely remove the information
				store.orphans().remove(entityPrimaryKey);
				return previousLocation;
			}
			// clean references in previous tree
			if (previousLocation != null) {
				// register all children as orphans
				makeOrphansRecursively(store, entityPrimaryKey);
				// clear references in parent node
				if (previousLocation.parentEntityPrimaryKey() == null) {
					store.roots().remove(entityPrimaryKey);
				} else {
					final TransactionalIntArray recomputedValue = store.levelIndex().computeIfPresent(
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
	 * Verifies that the given node is present in the item index and hands back the store that holds it.
	 *
	 * The check must happen before a caller resolves the absent store to "the hierarchy is empty": a hierarchy that
	 * never received a node holds the requested parent just as little as one that received a node and lost it again,
	 * and both owe the caller the same rejection.
	 *
	 * @param store      the node store of this index, or `null` while no node has ever been written to it
	 * @param parentNode the primary key of the node whose presence to verify
	 * @return the store, guaranteed to hold the node
	 * @throws EvitaInvalidUsageException if the node is absent from the index
	 */
	@Nonnull
	private HierarchyNodeStore assertNodeInIndex(@Nullable HierarchyNodeStore store, int parentNode) {
		if (store == null) {
			// no node has ever been written to this index, so it cannot hold the requested parent
			throw new EvitaInvalidUsageException("Parent node `" + parentNode + "` is not present in the index!");
		}
		Assert.isTrue(store.itemIndex().containsKey(parentNode), "Parent node `" + parentNode + "` is not present in the index!");
		return store;
	}

	/**
	 * Returns the {@link HierarchyNode} for the given primary key, throwing an exception if absent.
	 *
	 * @param theNode the primary key of the node to retrieve
	 * @return the hierarchy node
	 * @throws EvitaInvalidUsageException if the node is not present in the index
	 */
	@Nonnull
	private HierarchyNode getHierarchyNodeOrThrowException(int theNode) {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			throw new EvitaInvalidUsageException("The node `" + theNode + "` is not present in the index!");
		}
		final HierarchyNode hierarchyNode = store.itemIndex().get(theNode);
		Assert.isTrue(hierarchyNode != null, "The node `" + theNode + "` is not present in the index!");
		return hierarchyNode;
	}

	/**
	 * Returns the parent {@link HierarchyNode} for the given node, or an empty optional if the node
	 * is a root or its parent is an orphan. Throws an exception if the parent is expected to exist
	 * but is missing from the index.
	 *
	 * @param store         the node store holding the node whose parent to look up
	 * @param hierarchyNode the node whose parent to look up
	 * @return optional parent node, or empty if the node is a root or its parent is an orphan
	 * @throws EvitaInvalidUsageException if the parent is expected but unexpectedly absent
	 */
	@Nonnull
	private Optional<HierarchyNode> getParentNodeOrThrowException(
		@Nonnull HierarchyNodeStore store,
		@Nonnull HierarchyNode hierarchyNode
	) {
		if (hierarchyNode.parentEntityPrimaryKey() == null || store.orphans().contains(hierarchyNode.parentEntityPrimaryKey())) {
			return empty();
		} else {
			final HierarchyNode parentNode = store.itemIndex().get(hierarchyNode.parentEntityPrimaryKey());
			Assert.isTrue(parentNode != null, "The node parent `" + hierarchyNode.parentEntityPrimaryKey() + "` is unexpectedly not present in the index!");
			return of(parentNode);
		}
	}

	/**
	 * Recursively moves the entire subtree of the given entity to the orphan collection and removes
	 * the corresponding entries from the level index.
	 *
	 * @param store            the node store the orphaning operates on, resolved once by the caller
	 * @param entityPrimaryKey the primary key of the entity whose subtree becomes orphaned
	 */
	private void makeOrphansRecursively(@Nonnull HierarchyNodeStore store, int entityPrimaryKey) {
		final TransactionalIntArray removedNodeChildren = store.levelIndex().remove(entityPrimaryKey);
		if (removedNodeChildren != null) {
			final OfInt it = removedNodeChildren.iterator();
			while (it.hasNext()) {
				final int removedNodeChild = it.nextInt();
				store.orphans().add(removedNodeChild);
				makeOrphansRecursively(store, removedNodeChild);
			}
			removedNodeChildren.removeLayer();
		}
	}

	/**
	 * Scans the orphan collection for entities whose parent matches `entityPrimaryKey`, promotes them
	 * to the level index as children of the given entity, and recursively processes their own orphaned
	 * children as well.
	 *
	 * @param store            the node store the promotion operates on, resolved once by the caller
	 * @param entityPrimaryKey the primary key of the newly placed entity whose orphaned children to claim
	 */
	private void createChildrenSetFromOrphansRecursively(@Nonnull HierarchyNodeStore store, int entityPrimaryKey) {
		final CompositeIntArray children = new CompositeIntArray();
		final OfInt it = store.orphans().iterator();
		while (it.hasNext()) {
			final int orphanId = it.next();
			final HierarchyNode orphan = store.itemIndex().get(orphanId);
			if (orphan != null && Objects.equals(entityPrimaryKey, orphan.parentEntityPrimaryKey())) {
				children.add(orphanId);
			}
		}
		final TransactionalIntArray childrenArray = new TransactionalIntArray(children.toArray());
		final OfInt childrenIt = childrenArray.iterator();
		while (childrenIt.hasNext()) {
			int formerOrphanId = childrenIt.nextInt();
			store.orphans().remove(formerOrphanId);
			createChildrenSetFromOrphansRecursively(store, formerOrphanId);
		}
		store.levelIndex().put(entityPrimaryKey, childrenArray);
	}

	/**
	 * Recursively adds child nodes to `result`, traversing down to the specified number of `levels`.
	 *
	 * @param store                       the node store the traversal reads, resolved once by the caller
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and subtrees are included
	 * @param result                      the array to accumulate matching primary keys into
	 * @param children                    the direct children of the current node
	 * @param levels                      remaining levels to traverse (0 = no further recursion)
	 */
	private void addRecursively(
		@Nonnull HierarchyNodeStore store,
		@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate,
		@Nonnull CompositeIntArray result,
		@Nonnull TransactionalIntArray children,
		int levels
	) {
		final OfInt it = children.iterator();
		while (it.hasNext()) {
			int childId = it.nextInt();
			if (hierarchyFilteringPredicate.test(childId)) {
				result.add(childId);
				if (levels > 0) {
					final TransactionalIntArray childrenOfChildren = store.levelIndex().get(childId);
					if (childrenOfChildren != null) {
						addRecursively(store, hierarchyFilteringPredicate, result, childrenOfChildren, levels - 1);
					}
				}
			}
		}
	}

	/**
	 * Recursively counts child nodes, traversing down to the specified number of `levels`.
	 *
	 * @param store                       the node store the traversal reads, resolved once by the caller
	 * @param hierarchyFilteringPredicate predicate that controls which nodes and subtrees are counted
	 * @param children                    the direct children of the current node
	 * @param levels                      remaining levels to traverse (0 = no further recursion)
	 * @return count of matching nodes in the subtree
	 */
	private int countRecursively(
		@Nonnull HierarchyNodeStore store,
		@Nonnull HierarchyFilteringPredicate hierarchyFilteringPredicate,
		@Nonnull TransactionalIntArray children,
		int levels
	) {
		int sum = 0;
		final OfInt it = children.iterator();
		while (it.hasNext()) {
			int childId = it.nextInt();
			if (hierarchyFilteringPredicate.test(childId)) {
				sum++;
				if (levels > 0) {
					final TransactionalIntArray childrenOfChildren = store.levelIndex().get(childId);
					if (childrenOfChildren != null) {
						sum += countRecursively(store, hierarchyFilteringPredicate, childrenOfChildren, levels - 1);
					}
				}
			}
		}
		return sum;
	}

	/**
	 * Recursively appends indented string representations of child nodes to the builder.
	 *
	 * @param store   the node store the traversal reads, resolved once by the caller
	 * @param nodeIds the direct children to process
	 * @param indent  the current indentation level (multiplied by 3 for spaces)
	 * @param sb      the string builder to append to
	 */
	private void toStringChildrenRecursively(
		@Nonnull HierarchyNodeStore store,
		@Nonnull TransactionalIntArray nodeIds,
		int indent,
		@Nonnull StringBuilder sb
	) {
		final OfInt it = nodeIds.iterator();
		while (it.hasNext()) {
			int nodeId = it.nextInt();
			ofNullable(store.levelIndex().get(nodeId))
				.ifPresent(node -> {
					sb.append(" ".repeat(3 * indent)).append(nodeId).append("\n");
					toStringChildrenRecursively(store, node, indent + 1, sb);
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
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so there is nothing to traverse
			return;
		}
		final TraverserFactory childrenTraverseCreator = getTraverserFactory(store, visitor, predicate);

		final Collection<HierarchyNode> rootNodes;
		final int level;
		final int distance;
		if (rootNode == null) {
			level = 1;
			distance = 1;
			rootNodes = Arrays.stream(store.roots().getArray())
				.filter(predicate)
				.mapToObj(store.itemIndex()::get)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		} else if (ofNullable(excludingRoot).orElse(false)) {
			final HierarchyNode rootHierarchyNode = store.itemIndex().get(rootNode);
			if (rootHierarchyNode == null) {
				level = 0;
				rootNodes = Collections.emptyList();
			} else {
				level = computeLevel(store, rootHierarchyNode);
				rootNodes = ofNullable(store.levelIndex().get(rootNode))
					.stream()
					.flatMapToInt(TransactionalIntArray::stream)
					.filter(predicate)
					.mapToObj(store.itemIndex()::get)
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			}
			distance = 1;
		} else {
			final HierarchyNode rootHierarchyNode = store.itemIndex().get(rootNode);
			if (rootHierarchyNode == null) {
				rootNodes = Collections.emptyList();
				level = 0;
			} else {
				rootNodes = Collections.singletonList(rootHierarchyNode);
				level = computeLevel(store, rootHierarchyNode);
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
	 * @param store     the node store the traversal reads, resolved once by the caller
	 * @param visitor   the visitor invoked for each node encountered during traversal
	 * @param predicate predicate that filters which child nodes are visited; nodes that do not match
	 *                  are skipped along with their entire subtree
	 * @return a self-referencing {@link TraverserFactory} ready for recursive traversal
	 */
	@Nonnull
	private TraverserFactory getTraverserFactory(
		@Nonnull HierarchyNodeStore store,
		@Nonnull HierarchyVisitor visitor,
		@Nonnull HierarchyFilteringPredicate predicate
	) {
		final AtomicReference<TraverserFactory> factoryHolder = new AtomicReference<>();
		final TraverserFactory childrenTraverseCreator = (childrenId, level, distance) ->
			() -> {
				final Collection<HierarchyNode> children = ofNullable(store.levelIndex().get(childrenId))
					.map(it ->
						it.stream()
							.filter(predicate)
							.mapToObj(store.itemIndex()::get)
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
	 * @param store    the node store holding the node
	 * @param rootNode the node to compute level for
	 * @return level of the node or -1 if the node is not part of the tree
	 */
	private int computeLevel(@Nonnull HierarchyNodeStore store, @Nonnull HierarchyNode rootNode) {
		try {
			int level = 1;
			HierarchyNode theNode = rootNode;
			while (theNode.parentEntityPrimaryKey() != null) {
				final Optional<HierarchyNode> parentNode = getParentNodeOrThrowException(store, theNode);
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
	 * Creates a bitmap that contains all hierarchy nodes except orphans.
	 */
	@Nonnull
	private Bitmap createAllHierarchyNodesBitmap() {
		final HierarchyNodeStore store = this.nodeStore;
		if (store == null) {
			// no node has ever been written to this index, so the hierarchy is empty by construction
			return EmptyBitmap.INSTANCE;
		}
		final Set<Integer> nodeIds = store.itemIndex().keySet();
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (Integer nodeId : nodeIds) {
			writer.add(nodeId);
		}
		final PersistentRoaringBitmap roaringBitmap = writer.get();

		final OfInt it = store.orphans().iterator();
		while (it.hasNext()) {
			roaringBitmap.remove(it.next());
		}
		return roaringBitmap.isEmpty() ?
			EmptyBitmap.INSTANCE : new BaseBitmap(roaringBitmap);
	}

	/**
	 * Method resets all memoized values.
	 */
	private void resetMemoizedValues() {
		this.memoizedAllNodes = null;
	}

	/**
	 * Performs a breadth-first traversal of a hierarchy tree starting from the specified root node.
	 * Traverses the tree level by level, applying a sorter to children at each level, and stores the traversal result.
	 *
	 * @param store              the node store the traversal reads, resolved once by the caller
	 * @param previousLevelStart index of the first parent node in the result array
	 * @param levelSorter        a {@link UnaryOperator} to sort the children nodes at each level during the traversal
	 * @param result             a {@link CompositeIntArray} to store the result of the traversal
	 */
	private void breadthFirstTraversal(
		@Nonnull HierarchyNodeStore store,
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
			final TransactionalIntArray children = store.levelIndex().get(rootNodeId);
			if (children != null) {
				final int[] childrenIds = children.getArray();
				if (childrenIds.length > 0) {
					final int[] currentLevel = levelSorter.apply(childrenIds);
					result.addAll(currentLevel, 0, currentLevel.length);
				}
			}
		}
		if (result.getSize() > initialSize) {
			breadthFirstTraversal(store, initialSize, levelSorter, result);
		}
	}

	/**
	 * Performs a depth-first traversal of a hierarchy tree starting from the specified root node.
	 * Traverses the tree recursively, applying a sorter to children at each level, and stores the traversal result.
	 *
	 * @param store       the node store the traversal reads, resolved once by the caller
	 * @param rootNodeId  the ID of the root node from which to start the traversal
	 * @param levelSorter a {@link UnaryOperator} to sort the children nodes at each level during the traversal
	 * @param result      a {@link CompositeIntArray} to store the result of the traversal
	 */
	private void depthFirstTraversal(
		@Nonnull HierarchyNodeStore store,
		int rootNodeId,
		@Nonnull UnaryOperator<int[]> levelSorter,
		@Nonnull CompositeIntArray result
	) {
		final TransactionalIntArray children = store.levelIndex().get(rootNodeId);
		if (children != null) {
			final int[] childrenIds = children.getArray();
			final int[] currentLevel = levelSorter.apply(childrenIds);
			for (int nodeId : currentLevel) {
				result.add(nodeId);
				depthFirstTraversal(store, nodeId, levelSorter, result);
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
