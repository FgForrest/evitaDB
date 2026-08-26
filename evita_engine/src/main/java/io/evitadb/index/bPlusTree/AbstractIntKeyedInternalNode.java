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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.WarmUpTouchStamped;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;
import java.util.function.ToLongFunction;

import static io.evitadb.core.transaction.memory.WarmUpSavepoint.writeLayer;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfIntInOrderedArray;
import static io.evitadb.utils.ArrayUtils.insertIntIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.insertRecordIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeIntFromSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRecordFromSameArrayOnIndex;

/**
 * Shared internal (routing) node for the {@code int}-routed B+ trees of the family —
 * {@link TransactionalIntToLongBPlusTree} and {@link TransactionalElementBPlusTree}. Both trees route descent on a
 * primitive {@code int} separator key, so their internal spines are byte-for-byte identical: an {@code int[]} of
 * separator keys, a parallel {@link BPlusTreeNode}{@code []} of children, and the copy-on-write transactional
 * bookkeeping that keeps a committed (shared) node and its in-transaction diff layer from corrupting one another. That
 * entire ~700-line body lives here exactly once; the only per-tree leftovers are the constructors and the
 * {@link #createNode} factory, because a generic base cannot {@code new SELF}.
 *
 * The class is deliberately limited to the two {@code int}-routed trees and is **not** pushed up into the fully
 * key-agnostic {@link AbstractTransactionalBPlusTree}: the {@code long[]}- and {@code Object[]}-routed trees keep their
 * own typed nodes because folding them in would force the key array behind boxing or a virtual column — the exact
 * allocation / dispatch regression this family is engineered to avoid. Sharing is free here precisely because the two
 * spines already use the identical {@code int[]} representation.
 *
 * Each node uses itself as its own transactional memory diff layer (hence the self-recursive {@code SELF} bound and the
 * {@link InternalBPlusTreeNode}{@code <SELF>} super-interface). Because every tree instantiates exactly one concrete
 * subclass, every call site of the inherited accessors is monomorphic and inlines as it did before extraction — the
 * hoist changes neither the object layout (identical fields) nor the allocation behaviour (identical {@code int[]} /
 * {@code BPlusTreeNode[]} copies).
 *
 * @param <SELF> the concrete internal-node type, used both as the transactional diff layer and as the sibling type in
 *               borrow / merge operations
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract class AbstractIntKeyedInternalNode<SELF extends AbstractIntKeyedInternalNode<SELF>>
	implements
	InternalBPlusTreeNode<SELF>,
	IntBoundaryKeyedNode,
	Snapshotable<AbstractIntKeyedInternalNode.IntKeyedInternalNodeMemento> {
	@Serial private static final long serialVersionUID = -6245889213004517882L;
	/**
	 * This node's first-touch mark for the warm-up savepoint mechanism: the stamp of the
	 * {@link WarmUpSavepoint} that most recently captured this node's memento.
	 * {@link WarmUpTouchStamped} carries the requirements the field has to meet, and why breaking
	 * one of them corrupts a rollback rather than merely slowing it down.
	 *
	 * Deliberately NOT serialized, NOT carried into the memento, and NOT copied by
	 * {@code createCopyWithMergedTransactionalMemory} — it describes one live instance's
	 * relationship to one open savepoint, so a copy inheriting a live stamp would claim a capture
	 * that never happened.
	 */
	@Getter @Setter private transient long warmUpTouchStamp;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
	 * themselves (the same class) as their transactional memory layer, and if this layer were to also use
	 * transactional memory, it would create an infinite loop. This flag prevents that behavior.
	 */
	private final boolean transactionalLayer;
	/**
	 * The keys stored in this node. Cross-instance access from sibling nodes (typed as {@code SELF}) during
	 * borrow / merge requires the field to be inherited, hence {@code protected} rather than {@code private}.
	 */
	protected int[] keys;
	/**
	 * The children of this node. {@code protected} for the same cross-{@code SELF}-instance reason as {@link #keys}.
	 */
	protected BPlusTreeNode<?>[] children;
	/**
	 * Index of the last occupied position in the children array. {@code protected} for the same
	 * cross-{@code SELF}-instance reason as {@link #keys}.
	 */
	protected int peek;

	/**
	 * Returns the left boundary key of an arbitrary node reached through the key-agnostic {@link BPlusTreeNode} SPI
	 * (e.g. an element of an internal node's children array). The primitive-key accessor lives on the
	 * {@link IntBoundaryKeyedNode} marker so it stays out of the shared SPI (which must never expose a typed key);
	 * every node of an {@code int}-routed tree implements it, so the cast is always safe.
	 *
	 * @param node the node whose left boundary key is requested
	 * @return the left boundary (smallest) key of the node
	 */
	static int leftBoundaryKeyOf(@Nonnull BPlusTreeNode<?> node) {
		return ((IntBoundaryKeyedNode) node).getLeftBoundaryKey();
	}

	/**
	 * Creates a new internal node with a single key separating two child nodes. This constructor is used when creating
	 * a new root after a split operation.
	 *
	 * @param blockSize          the maximum number of keys this node can hold
	 * @param key                the initial key separating the two child nodes
	 * @param leftLeaf           the left child node
	 * @param rightLeaf          the right child node
	 * @param transactionalLayer whether this node participates in the transactional memory layer
	 */
	protected AbstractIntKeyedInternalNode(
		int blockSize,
		int key,
		@Nonnull BPlusTreeNode<?> leftLeaf,
		@Nonnull BPlusTreeNode<?> rightLeaf,
		boolean transactionalLayer
	) {
		this.keys = new int[blockSize];
		this.children = new BPlusTreeNode[blockSize + 1];
		this.keys[0] = key;
		this.children[0] = leftLeaf;
		this.children[1] = rightLeaf;
		this.peek = 1;
		this.transactionalLayer = transactionalLayer;
	}

	/**
	 * Creates a new internal node by copying a range of keys and children from existing arrays. This constructor is
	 * used during node split operations.
	 *
	 * @param originKeys         the source array of keys to copy from
	 * @param originChildren     the source array of child nodes to copy from
	 * @param keyStart           the start index (inclusive) in the origin keys array
	 * @param keyEnd             the end index (exclusive) in the origin keys array
	 * @param childrenStart      the start index (inclusive) in the origin children array
	 * @param childrenEnd        the end index (exclusive) in the origin children array
	 * @param transactionalLayer whether this node participates in the transactional memory layer
	 */
	protected AbstractIntKeyedInternalNode(
		@Nonnull int[] originKeys,
		@Nonnull BPlusTreeNode<?>[] originChildren,
		int keyStart, int keyEnd,
		int childrenStart, int childrenEnd,
		boolean transactionalLayer
	) {
		// we always create a new array for keys and children
		this.keys = new int[originKeys.length];
		this.children = new BPlusTreeNode[originChildren.length];
		// Copy the keys and children from the origin arrays
		System.arraycopy(originKeys, keyStart, this.keys, 0, keyEnd - keyStart);
		System.arraycopy(originChildren, childrenStart, this.children, 0, childrenEnd - childrenStart);
		this.peek = childrenEnd - childrenStart - 1;
		this.transactionalLayer = transactionalLayer;
	}

	/**
	 * Creates a new internal node that adopts the given arrays by reference (no copy). Used by {@link #createLayer()}
	 * and {@link #createCopyWithMergedTransactionalMemory} when wrapping an existing key / child layout.
	 *
	 * @param originKeys         the keys array to adopt
	 * @param originChildren     the children array to adopt
	 * @param originPeek         the index of the last occupied child slot
	 * @param transactionalLayer whether this node participates in the transactional memory layer
	 */
	protected AbstractIntKeyedInternalNode(
		@Nonnull int[] originKeys,
		@Nonnull BPlusTreeNode<?>[] originChildren,
		int originPeek,
		boolean transactionalLayer
	) {
		// the arrays are adopted as-is - the caller guarantees they are not shared with another live node
		this.keys = originKeys;
		this.children = originChildren;
		this.peek = originPeek;
		this.transactionalLayer = transactionalLayer;
	}

	/**
	 * Returns this node's separator keys in a transaction-aware way — the transaction's diff layer when one exists,
	 * otherwise the committed array. This is the read-only seam the int-keyed leaves' key markers and the consistency
	 * verifier observe; callers must not mutate the result in place (use {@link #getKeysForUpdate()} for that).
	 *
	 * @return the separator keys currently visible to the caller, never {@code null}
	 */
	@Nonnull
	public int[] getKeys() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return this.keys;
		} else {
			return layer.keys;
		}
	}

	@Override
	public int getPeek() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return this.peek;
		} else {
			return layer.peek;
		}
	}

	@Override
	public void setPeek(int peek) {
		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			final int originPeek = this.peek;
			this.peek = peek;
			if (peek < originPeek) {
				Arrays.fill(this.keys, Math.max(0, peek), originPeek, 0);
				Arrays.fill(this.children, peek + 1, originPeek + 1, null);
			}
		} else {
			final int originPeek = layer.peek;
			layer.peek = peek;
			if (peek < originPeek) {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					layer.keys = new int[this.keys.length];
					System.arraycopy(this.keys, 0, layer.keys, 0, originPeek);
				} else {
					Arrays.fill(layer.keys, Math.max(0, peek), originPeek, 0);
				}
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, originPeek + 1);
				} else {
					Arrays.fill(layer.children, peek + 1, originPeek + 1, null);
				}
			}
		}
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
		final VMLayout layout = VMLayout.current();
		// id + warmUpTouchStamp + transactionalLayer + keys/children slots + peek
		long size = layout.sizeOfObject(2L * Long.BYTES + 1L + 2L * layout.referenceSize() + Integer.BYTES);
		size += layout.sizeOfArray(this.keys.length, Integer.BYTES);
		size += layout.sizeOfArray(this.children.length, layout.referenceSize());
		// separator keys are `int` values inside the array, so unlike a boxed-key tree there is nothing here for
		// the sizer to price and no way for one key to be counted twice
		// THIS instance's own count, deliberately not `keyCount()`: the accessor resolves the calling thread's
		// transactional layer, and that layer is a separate node object with its OWN `children` array. Bounding the
		// array measured above by another object's count walks slots this one never filled
		// `peek` is the last occupied index, so the child count is peek+1 - and NOT clamped at zero: a node emptied
		// by a merge carries peek == -1 with `children[0]` already nulled, and clamping would walk that slot
		final int childCount = this.peek + 1;
		for (int i = 0; i < childCount; i++) {
			size += this.children[i].getHeapSizeInBytes(elementSizer);
		}
		return size;
	}

	@Override
	public int keyCount() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return Math.max(this.peek, 0);
		} else {
			return Math.max(layer.peek, 0);
		}
	}

	@Override
	public boolean isFull() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return this.peek == this.children.length - 1;
		} else {
			return layer.peek == layer.children.length - 1;
		}
	}

	@Override
	public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
		final int[] theKeys;
		final BPlusTreeNode<?>[] theChildren;
		final int thePeek;
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			theKeys = this.keys;
			theChildren = this.children;
			thePeek = this.peek;
		} else {
			theKeys = layer.keys;
			theChildren = layer.children;
			thePeek = layer.peek;
		}
		sb.append(" ".repeat(level * indentSpaces)).append("< ").append(theKeys[0]).append(":\n");
		theChildren[0].toVerboseString(sb, level + 1, indentSpaces);
		sb.append("\n");
		for (int i = 1; i <= thePeek; i++) {
			final int key = theKeys[i - 1];
			final BPlusTreeNode<?> child = theChildren[i];
			sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
			child.toVerboseString(sb, level + 1, indentSpaces);
			if (i < thePeek) {
				sb.append("\n");
			}
		}
	}

	@Override
	public void stealFromLeft(int numberOfTailValues, @Nonnull SELF previousNode) {
		Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			// we preserve all the current node children
			System.arraycopy(this.children, 0, this.children, numberOfTailValues, this.peek + 1);
			// then move the children from the previous node
			System.arraycopy(
				previousNode.getChildren(), previousNode.size() - numberOfTailValues, this.children, 0,
				numberOfTailValues
			);
			// we need to preserve all the current node keys
			System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek);
			// our original first child newly produces its own key
			this.keys[numberOfTailValues - 1] = leftBoundaryKeyOf(this.children[numberOfTailValues]);
			// and now we can copy the keys from the previous node - but except the first one
			System.arraycopy(
				previousNode.getKeys(), previousNode.keyCount() - numberOfTailValues + 1, this.keys, 0,
				numberOfTailValues - 1
			);
			// and update the peek indexes
			this.peek += numberOfTailValues;
			previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
		} else {
			decoupleTransactionalArrays();
			previousNode.decoupleTransactionalArrays();
			// we preserve all the current node children
			System.arraycopy(layer.children, 0, layer.children, numberOfTailValues, layer.peek + 1);
			// then move the children from the previous node
			System.arraycopy(
				previousNode.getChildrenForUpdate(), previousNode.size() - numberOfTailValues, layer.children, 0,
				numberOfTailValues
			);
			// we need to preserve all the current node keys
			System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek);
			// our original first child newly produces its own key
			layer.keys[numberOfTailValues - 1] = leftBoundaryKeyOf(layer.children[numberOfTailValues]);
			// and now we can copy the keys from the previous node - but except the first one
			System.arraycopy(
				previousNode.getKeysForUpdate(), previousNode.keyCount() - numberOfTailValues + 1, layer.keys, 0,
				numberOfTailValues - 1
			);
			// and update the peek indexes
			layer.peek += numberOfTailValues;
			previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
		}
	}

	@Override
	public void stealFromRight(int numberOfHeadValues, @Nonnull SELF nextNode) {
		Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			// the right sibling may be a committed (shared) node while `this` is a transaction-local node
			// (transactionalLayer == false): steal-from-right SHIFTS the sibling's arrays in place, so it must
			// decouple them first or it would corrupt the shared committed state. The ...ForUpdate accessors
			// decouple a committed sibling inside a transaction and are in-place no-ops outside one.
			final BPlusTreeNode<?>[] nextNodeChildren = nextNode.getChildrenForUpdate();
			System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
			System.arraycopy(
				nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

			// set the key for the first child of the next node
			this.keys[this.peek] = leftBoundaryKeyOf(this.children[this.peek + 1]);

			// we move the keys from the next node for all copied children
			final int[] nextNodeKeys = nextNode.getKeysForUpdate();
			System.arraycopy(nextNodeKeys, 0, this.keys, this.peek + 1, numberOfHeadValues - 1);
			// we need to shift the keys in the next node
			System.arraycopy(
				nextNodeKeys, numberOfHeadValues, nextNodeKeys, 0, nextNodeKeys.length - numberOfHeadValues);

			// and update the peek indexes
			this.peek += numberOfHeadValues;
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
		} else {
			decoupleTransactionalArrays();
			nextNode.decoupleTransactionalArrays();

			// we move all the children
			final BPlusTreeNode<?>[] nextNodeChildrenForUpdate = nextNode.getChildrenForUpdate();
			System.arraycopy(nextNodeChildrenForUpdate, 0, layer.children, layer.peek + 1, numberOfHeadValues);
			System.arraycopy(
				nextNodeChildrenForUpdate, numberOfHeadValues, nextNodeChildrenForUpdate, 0,
				nextNode.size() - numberOfHeadValues
			);

			// set the key for the first child of the next node
			layer.keys[layer.peek] = leftBoundaryKeyOf(layer.children[layer.peek + 1]);

			// we move the keys from the next node for all copied children
			final int[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
			System.arraycopy(nextNodeKeysForUpdate, 0, layer.keys, layer.peek + 1, numberOfHeadValues - 1);
			// we need to shift the keys in the next node
			System.arraycopy(
				nextNodeKeysForUpdate, numberOfHeadValues, nextNodeKeysForUpdate, 0,
				nextNodeKeysForUpdate.length - numberOfHeadValues
			);

			// and update the peek indexes
			layer.peek += numberOfHeadValues;
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
		}
	}

	@Override
	public void mergeWithLeft(@Nonnull SELF previousNode) {
		// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
		// with a single child (peek == 0) is collapsed before another deletion could drain it further,
		// so the shift arithmetic below assumes this node already holds at least one child
		Assert.isPremiseValid(
			getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
		);
		final int mergePeek = previousNode.getPeek();

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek);
			this.keys[mergePeek] = leftBoundaryKeyOf(this.children[0]);
			System.arraycopy(this.children, 0, this.children, mergePeek + 1, this.peek + 1);
			System.arraycopy(previousNode.getKeys(), 0, this.keys, 0, mergePeek);
			System.arraycopy(previousNode.getChildren(), 0, this.children, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
			previousNode.setPeek(-1);
		} else {
			decoupleTransactionalArrays();
			// we don't need to do: nodeToMergeWith.decoupleTransactionalArrays();
			// the other node will be fully merged to this node, so its arrays remain unmodified by this operation
			System.arraycopy(layer.keys, 0, layer.keys, mergePeek + 1, layer.peek);
			layer.keys[mergePeek] = leftBoundaryKeyOf(layer.children[0]);
			System.arraycopy(layer.children, 0, layer.children, mergePeek + 1, layer.peek + 1);
			System.arraycopy(previousNode.getKeysForUpdate(), 0, layer.keys, 0, mergePeek);
			System.arraycopy(previousNode.getChildrenForUpdate(), 0, layer.children, 0, mergePeek + 1);
			layer.peek += mergePeek + 1;
			previousNode.setPeek(-1);
		}
	}

	@Override
	public void mergeWithRight(@Nonnull SELF nextNode) {
		// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
		// with a single child (peek == 0) is collapsed before another deletion could drain it further,
		// so the separator-key write below assumes this node already holds at least one child
		Assert.isPremiseValid(
			getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
		);
		final int mergePeek = nextNode.getPeek();

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			System.arraycopy(nextNode.getChildren(), 0, this.children, this.peek + 1, mergePeek + 1);
			this.keys[this.peek] = leftBoundaryKeyOf(nextNode.getChildren()[0]);
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek);
			this.peek += mergePeek + 1;
			nextNode.setPeek(-1);
		} else {
			decoupleTransactionalArrays();
			// we don't need to do: nodeToMergeWith.decoupleTransactionalArrays();
			// the other node will be fully merged to this node, so its arrays remain unmodified by this operation
			System.arraycopy(nextNode.getChildrenForUpdate(), 0, layer.children, layer.peek + 1, mergePeek + 1);
			layer.keys[layer.peek] = leftBoundaryKeyOf(layer.children[layer.peek + 1]);
			System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, mergePeek);
			layer.peek += mergePeek + 1;
			nextNode.setPeek(-1);
		}
	}

	@Override
	public int getLeftBoundaryKey() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return leftBoundaryKeyOf(this.children[0]);
		} else {
			return leftBoundaryKeyOf(layer.children[0]);
		}
	}

	/**
	 * Retrieves the keys of the current node for updating. If a transactional layer is active, it ensures that updates
	 * are performed on an independent copy of the keys array within the transactional layer.
	 *
	 * @return an array of integers representing the keys of the current node, adjusted for the transactional layer if
	 * applicable.
	 */
	@Nonnull
	public int[] getKeysForUpdate() {
		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			return this.keys;
		} else {
			// internal arrays may have been still identical to the original arrays
			// we need to copy them in the transactional layer, before modifying

			//noinspection ArrayEquality
			if (layer.keys == this.keys) {
				layer.keys = new int[this.keys.length];
				System.arraycopy(this.keys, 0, layer.keys, 0, this.keys.length);
			}
			return layer.keys;
		}
	}

	@Nonnull
	@Override
	public BPlusTreeNode<?>[] getChildren() {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		if (layer == null) {
			return this.children;
		} else {
			return layer.children;
		}
	}

	/**
	 * Retrieves the children nodes of the current BPlusTree node for updating. If a transactional layer is active, it
	 * ensures that the updates are performed on an independent copy of the children array contained within the
	 * transactional layer.
	 *
	 * @return an array of BPlusTreeNode elements representing the children of the current node, adjusted for the
	 * transactional layer if applicable.
	 */
	@Nonnull
	public BPlusTreeNode<?>[] getChildrenForUpdate() {
		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			return this.children;
		} else {
			// internal arrays may have been still identical to the original arrays
			// we need to copy them in the transactional layer, before modifying

			//noinspection ArrayEquality
			if (layer.children == this.children) {
				layer.children = new BPlusTreeNode[this.children.length];
				System.arraycopy(this.children, 0, layer.children, 0, this.children.length);
			}
			return layer.children;
		}
	}

	/**
	 * Adapts this internal node to a child split: inserts the new key into the keys array and the new right node into
	 * the children array, replacing the original child with the left node.
	 *
	 * @param key      The integer key to be inserted into the node.
	 * @param original The original child node that has been split into two nodes.
	 * @param left     The left child resulting from the split (keys less than the inserted key).
	 * @param right    The right child resulting from the split (keys greater than the inserted key).
	 */
	public void adaptToLeafSplit(
		int key,
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right
	) {
		Assert.isPremiseValid(
			!this.isFull(),
			"Internal node must not be full to accommodate two leaf nodes after their split!"
		);

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
				key, this.keys, 0, this.peek);
			Assert.isPremiseValid(
				original == this.children[insertionPosition.position()],
				"Original node must be the child of the internal node!"
			);
			Assert.isPremiseValid(
				!insertionPosition.alreadyPresent(),
				"Key already present in the internal node!"
			);

			insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
			this.children[insertionPosition.position()] = left;
			insertRecordIntoSameArrayOnIndex(right, this.children, insertionPosition.position() + 1);
			this.peek++;
		} else {
			decoupleTransactionalArrays();

			// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
				key, layer.keys, 0, layer.peek);
			Assert.isPremiseValid(
				original == layer.children[insertionPosition.position()],
				"Original node must be the child of the internal node!"
			);
			Assert.isPremiseValid(
				!insertionPosition.alreadyPresent(),
				"Key already present in the internal node!"
			);

			insertIntIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
			layer.children[insertionPosition.position()] = left;
			insertRecordIntoSameArrayOnIndex(right, layer.children, insertionPosition.position() + 1);
			layer.peek++;
		}
	}

	/**
	 * Searches for the child index that should contain the given key. This method avoids allocating a NodeWithIndex
	 * record.
	 *
	 * Allocation-free: {@link Arrays#binarySearch(int[], int, int, int)} is folded directly into the child-index
	 * mapping instead of routing through `ArrayUtils.computeInsertPositionOfIntInOrderedArray`, which allocates an
	 * `ArrayUtils.InsertionPosition` record this method immediately collapsed to a single `int` in BOTH branches.
	 * Escape analysis was measurably not eliminating it - the record accounted for 1.59 GB of allocation per 60 s of
	 * bulk ingest, the single largest allocation site on the sort-attribute insert path.
	 *
	 * A non-negative `binarySearch` result means an exact key hit, which routes to the child one slot to the RIGHT
	 * (the former `alreadyPresent ? position + 1` branch); a negative result encodes `-insertionPoint - 1`, and the
	 * insertion point IS the child index (the former `: position` branch). At `peek == 0` a binary search over an
	 * empty range returns `-1`, yielding child index `0` - matching the `toIndex <= fromIndex` guard the previous
	 * implementation inherited from `computeInsertPositionOfIntInOrderedArray`.
	 *
	 * @param key the integer key to search for within the B+ tree.
	 * @return the index of the child that should contain the specified key.
	 */
	public int searchIndex(int key) {
		final SELF layer = this.transactionalLayer ?
			Transaction.getTransactionalMemoryLayerIfExists(this) :
			null;
		final int[] theKeys = layer == null ? this.keys : layer.keys;
		final int thePeek = layer == null ? this.peek : layer.peek;
		final int index = Arrays.binarySearch(theKeys, 0, thePeek, key);
		return index >= 0 ? index + 1 : -index - 1;
	}

	@Override
	public void removeChildOnIndex(int keyIndex, int childIndex) {
		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			removeIntFromSameArrayOnIndex(this.keys, keyIndex);
			this.keys[this.peek - 1] = 0;
			removeRecordFromSameArrayOnIndex(this.children, childIndex);
			this.children[this.peek] = null;
			this.peek--;
		} else {
			decoupleTransactionalArrays();

			removeIntFromSameArrayOnIndex(layer.keys, keyIndex);
			layer.keys[layer.peek - 1] = 0;

			// the removed children may have had its own transactional layer, which needs to be removed
			if (Transaction.getTransactionalMemoryLayerIfExists(layer.children[childIndex]) != null) {
				layer.children[childIndex].removeLayer();
			}

			removeRecordFromSameArrayOnIndex(layer.children, childIndex);
			layer.children[layer.peek] = null;
			layer.peek--;
		}
	}

	@Override
	public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node) {
		Assert.isPremiseValid(
			index > 0,
			"Leftmost child node does not have a key in the parent node!"
		);

		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer == null) {
			Assert.isPremiseValid(
				this.children[index] == node,
				"Node to update key for must match the child node at the specified index!"
			);
			this.keys[index - 1] = leftBoundaryKeyOf(node);
		} else {
			decoupleTransactionalArrays();
			Assert.isPremiseValid(
				layer.children[index] == node,
				"Node to update key for must match the child node at the specified index!"
			);
			layer.keys[index - 1] = leftBoundaryKeyOf(node);
		}
	}

	@Override
	public SELF createLayer() {
		return createNode(this.keys, this.children, this.peek, false);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayer(this);
	}

	/**
	 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Only the keys and children
	 * arrays and the peek index are mutable here; both arrays are cloned (shallow — the primitive keys are value types
	 * and the child nodes own their own transactional layers and are snapshotted independently) so that a later
	 * mutation, or a repeated {@link #restore}, cannot corrupt the memento.
	 *
	 * @return an independent snapshot of this internal node's array structure
	 */
	@Nonnull
	@Override
	public IntKeyedInternalNodeMemento snapshot() {
		return new IntKeyedInternalNodeMemento(this.keys.clone(), this.children.clone(), this.peek);
	}

	/**
	 * Restores the array structure captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed so
	 * the memento stays reusable for a repeated restore.
	 *
	 * @param memento the state previously captured by {@link #snapshot}
	 */
	@Override
	public void restore(@Nonnull IntKeyedInternalNodeMemento memento) {
		this.keys = memento.keys().clone();
		this.children = memento.children().clone();
		this.peek = memento.peek();
	}

	/**
	 * Immutable savepoint memento of an {@code int}-keyed internal node's copy-on-write array structure. The arrays are
	 * private clones owned by the memento (see {@link #snapshot}); the primitive keys and child-node references they
	 * hold are shared by design. Shared by both {@code int}-routed trees, since their internal spines are identical.
	 *
	 * @param keys     clone of the separator-key array
	 * @param children clone of the child-pointer array
	 * @param peek     the last occupied child index
	 */
	record IntKeyedInternalNodeMemento(
		@Nonnull int[] keys,
		@Nonnull BPlusTreeNode<?>[] children,
		int peek
	) {
	}

	@Nonnull
	@Override
	public SELF createCopyWithMergedTransactionalMemory(
		@Nullable SELF layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final int[] theKeys;
		final BPlusTreeNode<?>[] theChildren;
		final int thePeek;
		if (layer == null) {
			theKeys = this.keys;
			theChildren = this.children;
			thePeek = this.peek;
		} else {
			theKeys = layer.keys;
			theChildren = layer.children;
			thePeek = layer.peek;
		}

		BPlusTreeNode<?>[] newChildren = null;
		for (int i = 0; i < thePeek + 1; i++) {
			final BPlusTreeNode<?> child = transactionalLayer.getStateCopyWithCommittedChanges(theChildren[i]);
			if (newChildren == null && child != theChildren[i]) {
				newChildren = new BPlusTreeNode[theChildren.length];
				System.arraycopy(theChildren, 0, newChildren, 0, i);
			}
			if (newChildren != null) {
				newChildren[i] = child;
			}
		}

		if (newChildren != null) {
			return createNode(theKeys, newChildren, thePeek, true);
		} else if (layer != null) {
			return createNode(theKeys, theChildren, thePeek, true);
		} else if (!this.transactionalLayer) {
			// nodes created during splits/merges are built with transactionalLayer=false so they do
			// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
			// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
			return createNode(theKeys, theChildren, thePeek, true);
		} else {
			return self();
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(64);
		toVerboseString(sb, 0, 3);
		return sb.toString();
	}

	/**
	 * Creates a concrete subclass instance that adopts the given arrays by reference (no copy). This is the single seam
	 * a generic base cannot fill itself ({@code new SELF} is impossible) — each {@code int}-routed tree implements it
	 * by delegating to its own array-adopting constructor.
	 *
	 * @param keys               the keys array to adopt
	 * @param children           the children array to adopt
	 * @param peek               the index of the last occupied child slot
	 * @param transactionalLayer whether the produced node participates in the transactional memory layer
	 * @return the produced concrete internal node
	 */
	@Nonnull
	protected abstract SELF createNode(
		@Nonnull int[] keys,
		@Nonnull BPlusTreeNode<?>[] children,
		int peek,
		boolean transactionalLayer
	);

	/**
	 * Internal arrays may have been still identical to the original arrays we need to copy them in the transactional
	 * layer before modifying. {@code protected} so the borrow / merge code can decouple a sibling node (typed as
	 * {@code SELF}) in addition to {@code this}.
	 */
	protected void decoupleTransactionalArrays() {
		final SELF layer = writeLayer(this, this.transactionalLayer);
		if (layer != null) {
			//noinspection ArrayEquality
			if (layer.keys == this.keys) {
				layer.keys = new int[this.keys.length];
				System.arraycopy(this.keys, 0, layer.keys, 0, this.peek);
			}
			//noinspection ArrayEquality
			if (layer.children == this.children) {
				layer.children = new BPlusTreeNode[this.children.length];
				System.arraycopy(this.children, 0, layer.children, 0, this.peek + 1);
			}
		}
	}

	/**
	 * Returns {@code this} narrowed to the concrete self type. The cast is always safe: {@code SELF} is by contract the
	 * concrete subclass of this instance.
	 *
	 * @return this node as its concrete {@code SELF} type
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private SELF self() {
		return (SELF) this;
	}

}
