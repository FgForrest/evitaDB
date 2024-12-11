/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.dataType;


import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.utils.ArrayUtils.*;

/**
 * Represents a B+ Tree data structure specifically designed for integer keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class IntBPlusTree<V> {
	/**
	 * Maximum number of keys per leaf node. Use odd number. The number of keys in internal nodes is one more.
	 */
	@Getter private final int blockSize;
	/**
	 * Minimum number of keys per node.
	 */
	@Getter private final int minBlockSize;
	/**
	 * The type of the values stored in the tree.
	 */
	@Getter private final Class<V> valueType;
	/**
	 * Number of elements in the tree.
	 */
	private int size;
	/**
	 * Root node of the tree.
	 */
	@Getter(AccessLevel.PACKAGE)
	private BPlusTreeNode<?> root;

	/**
	 * Finds the parent node of the specified target node within the B+ tree starting
	 * from the given current node.
	 *
	 * @param current The current BPlusTreeNode from which the search begins.
	 * @param target  The target BPlusTreeNode for which the parent needs to be found.
	 * @return An Optional containing the parent BPlusInternalTreeNode if the parent
	 * is found, or an empty Optional if the target node has no parent within
	 * this subtree.
	 */
	@Nonnull
	private static Optional<BPlusInternalTreeNode> findParent(@Nonnull BPlusTreeNode<?> current, @Nonnull BPlusTreeNode<?> target) {
		if (current instanceof BPlusInternalTreeNode currentInternalNode) {
			return currentInternalNode.findParentOf(target);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Rewires the sibling pointers of BPlusTreeNodes involved in a split operation.
	 *
	 * @param node       The original node that is being replaced during the sibling rewiring process.
	 * @param leftNode   The new left sibling node resulting from the split operation.
	 * @param rightRight The new right sibling node resulting from the split operation.
	 */
	private static <N extends BPlusTreeNode<N>> void rewireSiblings(
		@Nonnull N node,
		@Nonnull N leftNode,
		@Nonnull N rightRight
	) {
		final N previousNode = node.getPreviousNode();
		if (previousNode != null) {
			previousNode.setNextNode(leftNode);
		}
		leftNode.setPreviousNode(previousNode);
		leftNode.setNextNode(rightRight);
		rightRight.setPreviousNode(leftNode);
		final N nextNode = node.getNextNode();
		if (nextNode != null) {
			nextNode.setPreviousNode(rightRight);
		}
		rightRight.setNextNode(nextNode);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param blockSize maximum number of values in a leaf node
	 * @param valueType the type of the values stored in the tree
	 */
	public IntBPlusTree(int blockSize, @Nonnull Class<V> valueType) {
		this(blockSize, blockSize / 2, valueType);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param blockSize    maximum number of values in a leaf node
	 * @param minBlockSize minimum number of values in a leaf node
	 * @param valueType    the type of the values stored in the tree
	 */
	public IntBPlusTree(int blockSize, int minBlockSize, @Nonnull Class<V> valueType) {
		Assert.isPremiseValid(blockSize >= 3, "Block size must be at least 3");
		Assert.isPremiseValid(blockSize % 2 == 1, "Block size must be an odd number");
		this.blockSize = blockSize;
		this.minBlockSize = minBlockSize;
		this.valueType = valueType;
		this.root = new BPlusLeafTreeNode<>(blockSize, valueType);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(int key, @Nonnull V value) {
		final BPlusLeafTreeNode<V> leaf = findLeaf(key);
		this.size += leaf.insert(key, value) ? 1 : 0;

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			splitLeafNode(leaf);
		}
	}

	/**
	 * Deletes the entry associated with the specified key from the B+ tree.
	 * The method locates the appropriate leaf node containing the key and
	 * removes the entry from it, ensuring that the B+ tree properties are
	 * maintained after deletion.
	 *
	 * @param key the key whose associated entry is to be removed from the B+ tree
	 */
	public void delete(int key) {
		final LeafWithPath<V> leafWithPath = findLeafWithPath(key);
		final BPlusLeafTreeNode<V> leaf = leafWithPath.leaf();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getKeys()[0];
		this.size -= leaf.delete(key) ? 1 : 0;

		final List<BPlusInternalTreeNode> parentPath = leafWithPath.path();
		// if the head of the leaf has been removed, we need to update parent keys accordingly
		if (headRemoved) {
			updateParentKeys(
				parentPath,
				parentPath.get(leafWithPath.path.size() - 1).getChildIndex(key, leaf),
				leaf
			);
		}

		consolidate(
			leaf,
			parentPath,
			parentPath.size() - 1
		);
	}

	/**
	 * Searches for the value associated with the given key in the B+ tree.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return an Optional containing the value associated with the key if it is present,
	 * or an empty Optional if the key is not found in the tree
	 */
	@Nonnull
	public Optional<V> search(int key) {
		return findLeaf(key).search(key);
	}

	/**
	 * Returns the number of elements currently stored in the B+ tree.
	 *
	 * @return the size of the tree, represented as the number of elements it contains
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	public OfInt keyIterator() {
		return new ForwardKeyIterator(findLeaf(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public OfInt keyReverseIterator() {
		return new ReverseKeyIterator(findLeaf(Integer.MAX_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right.
	 *
	 * @return an iterator that traverses the B+ tree values from left to right
	 */
	@Nonnull
	public Iterator<V> valueIterator() {
		return new ForwardValueIterator<>(findLeaf(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left.
	 *
	 * @return an iterator that traverses the B+ tree values from right to left
	 */
	@Nonnull
	public Iterator<V> valueReverseIterator() {
		return new ReverseValueIterator<>(findLeaf(Integer.MAX_VALUE));
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(1_024);
		this.root.toVerboseString(sb, 0, 3);
		return sb.toString();
	}

	/**
	 * Consolidates a B+ tree node with its siblings if it has fewer keys
	 * than the minimum block size. The method tries to merge the node
	 * with its left or right siblings and adjusts the tree structure
	 * accordingly.
	 *
	 * @param node  the current B+ tree node to be consolidated
	 * @param path  the path of internal tree nodes leading to the current node
	 * @param index the index of the current node in the path
	 */
	private void consolidate(
		@Nonnull BPlusLeafTreeNode<V> node,
		@Nonnull List<BPlusInternalTreeNode> path,
		int index
	) {
		// node has less than minBlockSize keys, check its siblings
		if (node.size() < this.minBlockSize && path.size() > index && index >= 0) {
			boolean nodeIsEmpty = node.size() == 0;
			final BPlusInternalTreeNode parent = path.get(index);
			final BPlusLeafTreeNode<V> previousNode = node.getPreviousNode();
			final int previousNodeIndexInParent = previousNode == null ? -1 : parent.getChildIndex(previousNode.getKeys()[0], previousNode);
			// if previous node with current node exists and shares the same parent
			// and we can steal from the left sibling
			if (previousNodeIndexInParent > -1 && previousNode.size() > this.minBlockSize) {
				// steal half of the surplus data from the left sibling
				node.stealFromLeft(Math.max(1, (previousNode.size() - this.minBlockSize) / 2));
				// update parent keys, but only if node was empty - which means first key was added
				if (nodeIsEmpty) {
					updateParentKeys(path, previousNodeIndexInParent + 1, node);
				}
				return;
			}

			final BPlusLeafTreeNode<V> nextNode = node.getNextNode();
			final int nextNodeIndexInParent = nextNode == null ? -1 : parent.getChildIndex(nextNode.getKeys()[0], nextNode);
			// if next node with current node exists and shares the same parent
			// and we can steal from the right sibling
			if (nextNodeIndexInParent > -1 && nextNode.size() > this.minBlockSize) {
				// steal half of the surplus data from the right sibling
				node.stealFromRight(Math.max(1, (nextNode.size() - this.minBlockSize) / 2));
				// update parent keys of the next node - we've stolen its first key
				updateParentKeys(path, nextNodeIndexInParent, nextNode);
				// update parent keys, but only if node was empty - which means first key was added
				if (nodeIsEmpty) {
					updateParentKeys(path, nextNodeIndexInParent - 1, node);
				}
				return;
			}

			// if previous node with current node can be merged and share the same parent
			if (previousNodeIndexInParent > -1 && previousNode.size() + node.size() < this.blockSize) {
				throw new UnsupportedOperationException("Merging with left sibling not implemented yet");
				/* TODO JNO - not implemented (hard)
				// merge nodes
				node.mergeWithLeft();
				// remove the removed child from the parent
				parent.removeChildOnIndex(previousNodeIndexInParent);
				*/
			}

			// if next node with current node can be merged and share the same parent
			if (nextNodeIndexInParent > -1 && nextNode.getPeek() + node.getPeek() + 2 < this.blockSize) {
				throw new UnsupportedOperationException("Merging with right sibling not implemented yet");
				/* TODO JNO - not implemented (hard)
				// merge nodes
				node.mergeWithRight();
				// remove the removed child from the parent
				parent.removeChildOnIndex(nextNodeIndexInParent);
				 */
			}
		}
	}

	/**
	 * Updates the keys in the parent nodes of a B+ Tree based on changes in a specific path.
	 * This method propagates changes up the tree as necessary.
	 *
	 * @param path A list of B+ internal tree nodes representing the path from the root to the updated node.
	 * @param indexToUpdate The index of the key to be updated in the parent node.
	 * @param previouslyUpdatedNode The child node that was previously updated and requires the parent node key to be aligned.
	 */
	private static void updateParentKeys(
		@Nonnull List<BPlusInternalTreeNode> path,
		int indexToUpdate,
		@Nonnull BPlusTreeNode<?> previouslyUpdatedNode
	) {
		// first child doesn't have a key in the parent
		if (indexToUpdate > 0) {
			for (int i = path.size() - 1; i >= 0; i--) {
				BPlusInternalTreeNode immediateParent = path.get(i);
				immediateParent.updateKeyForNode(indexToUpdate, previouslyUpdatedNode);
				previouslyUpdatedNode = immediateParent;
				indexToUpdate = i > 0 ? path.get(i - 1).getChildIndex(immediateParent.getLeftBoundaryKey(), immediateParent) : -1;
				if (indexToUpdate <= 0) {
					break;
				}
			}
		}
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private BPlusLeafTreeNode<V> findLeaf(int key) {
		BPlusTreeNode<?> node = this.root;
		while (node instanceof BPlusInternalTreeNode internalNode) {
			node = internalNode.search(key);
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<V>) node;
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private LeafWithPath<V> findLeafWithPath(int key) {
		if (this.root instanceof BPlusInternalTreeNode rootInternalNode) {
			final ArrayList<BPlusInternalTreeNode> path = new ArrayList<>((int) (Math.log(this.size()) + 1));
			path.add(rootInternalNode);
			final BPlusLeafTreeNode<V> leaf = findLeafWithPath(rootInternalNode, key, path);
			return new LeafWithPath<>(path, leaf);
		} else {
			//noinspection unchecked
			return new LeafWithPath<>(List.of(), (BPlusLeafTreeNode<V>) this.root);
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the leaf node responsible
	 * for the specified key. It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param key         The key for which the corresponding leaf node is to be found.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 * @return The leaf tree node that should contain the specified key.
	 */
	@Nonnull
	private BPlusLeafTreeNode<V> findLeafWithPath(
		@Nonnull BPlusInternalTreeNode currentNode,
		int key,
		@Nonnull List<BPlusInternalTreeNode> path
	) {
		final BPlusTreeNode<?> child = currentNode.search(key);
		if (child instanceof BPlusInternalTreeNode childInternalNode) {
			path.add(childInternalNode);
			return findLeafWithPath(childInternalNode, key, path);
		} else {
			//noinspection unchecked
			return (BPlusLeafTreeNode<V>) child;
		}
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf The leaf node to be split
	 */
	private void splitLeafNode(@Nonnull BPlusLeafTreeNode<V> leaf) {
		final int mid = this.blockSize / 2;
		final int[] originKeys = leaf.getKeys();
		final V[] originValues = leaf.getValues();

		// Move half the keys to the new arrays of the left leaf node
		//noinspection unchecked
		final BPlusLeafTreeNode<V> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			new int[this.blockSize],
			(V[]) Array.newInstance(this.valueType, this.blockSize),
			0,
			mid
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusLeafTreeNode<V> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			originKeys,
			originValues,
			mid,
			leftLeaf.getKeys().length
		);

		// rewire the leaf node pointers
		rewireSiblings(leaf, leftLeaf, rightLeaf);

		// If the root splits, create a new root
		if (leaf == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.blockSize,
				rightLeaf.getKeys()[0],
				leftLeaf, rightLeaf
			);
		} else {
			replaceNodeInParentInternalNode(
				leaf,
				leftLeaf,
				rightLeaf,
				rightLeaf.getKeys()[0]
			);
		}
	}

	/**
	 * Replaces a node in its parent with two new nodes as part of the B+ tree splitting process.
	 * This method is used when a node is split and the parent needs to be updated
	 * to reflect the split structure.
	 *
	 * @param original The original BPlusTreeNode that is being replaced.
	 * @param left     The left child BPlusTreeNode resulting from the split, containing keys less than the new partition key.
	 * @param right    The right child BPlusTreeNode resulting from the split, containing keys greater than the new partition key.
	 * @param key      The partition key that separates the left and right nodes.
	 * @throws GenericEvitaInternalError if the parent node cannot be found for the original node.
	 */
	private void replaceNodeInParentInternalNode(
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right,
		int key
	) {
		findParent(this.root, original)
			.ifPresentOrElse(
				parent -> {
					parent.split(key, original, left, right);

					if (parent.isFull()) {
						splitInternalNode(parent);
					}
				},
				() -> {
					throw new GenericEvitaInternalError("Parent node not found for insertion");
				}
			);
	}

	/**
	 * Splits a full internal node in a B+ tree into two separate nodes to maintain the properties of the B+ tree.
	 * The method creates two new nodes: a left node containing the lower half of the original node's keys and
	 * a right node containing the upper half. If the node being split is the root of the tree,
	 * a new root node is created. Otherwise, the parent node is updated to reflect the split.
	 *
	 * @param internal The internal node to be split. It must not be null and must contain a number of keys
	 *                 that necessitate splitting to maintain the B+ tree properties.
	 */
	private void splitInternalNode(@Nonnull BPlusInternalTreeNode internal) {
		final int mid = this.blockSize / 2;
		final int[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node
		final BPlusInternalTreeNode leftInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			new int[this.blockSize],
			new BPlusTreeNode[this.blockSize + 1],
			0,
			mid
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusInternalTreeNode rightInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			originKeys,
			originChildren,
			mid + 1,
			leftInternal.getKeys().length
		);

		// rewire the leaf node pointers
		rewireSiblings(internal, leftInternal, rightInternal);

		// If the root splits, create a new root
		if (internal == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.blockSize,
				rightInternal.getLeftBoundaryKey(),
				leftInternal, rightInternal
			);
		} else {
			replaceNodeInParentInternalNode(
				internal,
				leftInternal,
				rightInternal,
				rightInternal.getLeftBoundaryKey()
			);
		}
	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	interface BPlusTreeNode<N extends BPlusTreeNode<N>> {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		int[] getKeys();

		/**
		 * Retrieves the peek index (last usable value) of the B+ Tree node's values / children.
		 *
		 * @return the peek value of the node, indicating the last usable index in the node's values / children array.
		 */
		int getPeek();

		/**
		 * Returns number of values in this node - i.e. peek + 1.
		 *
		 * @return number of values in this node
		 */
		default int size() {
			return getPeek() + 1;
		}

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
		 * Retrieves the previous node in the B+ Tree structure, if present.
		 *
		 * @return the previous node of type N, or null if there is no previous node.
		 */
		@Nullable
		N getPreviousNode();

		/**
		 * Sets the previous node in the B+ Tree structure for the current node.
		 * The previous node is typically a sibling or adjacent node in the B+ Tree structure.
		 *
		 * @param previousNode the node to set as the previous node. Can be null if there is no previous node.
		 */
		void setPreviousNode(@Nullable N previousNode);

		/**
		 * Retrieves the next node in the B+ Tree structure, if available.
		 *
		 * @return the next node of type N, or null if there is no next node.
		 */
		@Nullable
		N getNextNode();

		/**
		 * Sets the next node in the B+ Tree structure for the current node.
		 * The next node is typically the immediate successor or adjacent node in the B+ Tree.
		 *
		 * @param nextNode the node to set as the next node. Can be null if there is no next node.
		 */
		void setNextNode(@Nullable N nextNode);

	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	static class BPlusInternalTreeNode implements BPlusTreeNode<BPlusInternalTreeNode> {
		/**
		 * The keys stored in this node.
		 */
		@Getter private final int[] keys;

		/**
		 * The children of this node.
		 */
		@Getter private final BPlusTreeNode<?>[] children;

		/**
		 * Link to the previous internal node.
		 * Although these data are not used in generic implementations, keeping those pointers allows us to keep
		 * tree balanced during deletions (merges).
		 */
		@Nullable @Getter @Setter
		private BPlusInternalTreeNode previousNode;

		/**
		 * Link to the next internal node.
		 * Although these data are not used in generic implementations, keeping those pointers allows us to keep
		 * tree balanced during deletions (merges).
		 */
		@Nullable @Getter @Setter
		private BPlusInternalTreeNode nextNode;

		/**
		 * Index of the last occupied position in the children array.
		 */
		@Getter private int peek;

		public BPlusInternalTreeNode(
			int blockSize,
			int key,
			@Nonnull BPlusTreeNode<?> leftLeaf,
			@Nonnull BPlusTreeNode<?> rightLeaf
		) {
			this.keys = new int[blockSize];
			this.children = new BPlusTreeNode[blockSize + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
		}

		public BPlusInternalTreeNode(
			int[] originKeys, BPlusTreeNode<?>[] originChildren,
			int[] keys, BPlusTreeNode<?>[] values,
			int start, int end
		) {
			this.keys = keys;
			this.children = values;
			// Copy the keys and children from the origin arrays
			for (int i = start; i < end; i++) {
				this.keys[i - start] = originKeys[i];
				this.children[i - start] = originChildren[i];
			}
			this.children[end - start] = originChildren[end];
			this.peek = end - start;
		}

		/**
		 * Finds the parent node of the specified target node within the B+ tree.
		 *
		 * @param targetNode The target BPlusTreeNode for which the parent needs to be found.
		 * @return An Optional containing the parent BPlusInternalTreeNode if the parent is found,
		 * or an empty Optional if the target node has no parent within this subtree.
		 */
		@Nonnull
		public Optional<BPlusInternalTreeNode> findParentOf(@Nonnull BPlusTreeNode<?> targetNode) {
			for (final BPlusTreeNode<?> child : this.children) {
				if (child == targetNode) {
					// Parent found
					return Optional.of(this);
				}
				if (child instanceof BPlusInternalTreeNode childInternalNode) {
					final Optional<BPlusInternalTreeNode> possibleParent = childInternalNode.findParentOf(targetNode);
					if (possibleParent.isPresent()) {
						return possibleParent;
					}
				}
			}
			// Parent not found
			return Optional.empty();
		}

		/**
		 * Splits a B+ Tree node by inserting a new key into the node's keys array and updating its children accordingly.
		 * This method is used for managing the internal structure of a B+ Tree when a node needs to be divided due to
		 * overflow.
		 *
		 * @param key      The integer key to be inserted into the B+ Tree node.
		 * @param original The original B+ Tree node that is the child of the internal node. This node is being split into two nodes.
		 * @param left     The left child BPlusTreeNode resulting from the split, containing keys less than the inserted key.
		 * @param right    The right child BPlusTreeNode resulting from the split, containing keys greater than the inserted key.
		 */
		public void split(
			int key,
			@Nonnull BPlusTreeNode<?> original,
			@Nonnull BPlusTreeNode<?> left,
			@Nonnull BPlusTreeNode<?> right
		) {
			// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
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
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public BPlusTreeNode<?> search(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			return insertionPosition.alreadyPresent() ?
				this.children[insertionPosition.position() + 1] : this.children[insertionPosition.position()];
		}

		/**
		 * Retrieves the index position of the specified child node within the children array.
		 * The search is performed up to the current peek position.
		 *
		 * @param key  the key to search for within the children array.
		 * @param node the BPlusTreeNode whose index needs to be found within the children array.
		 * @return the index of the specified child node if found; otherwise, -1 indicating that the node is not present in the children array.
		 */
		public int getChildIndex(int key, @Nonnull BPlusTreeNode<?> node) {
			if (this.getPeek() < 0) {
				return -1;
			} else {
				final int keyIndex = Arrays.binarySearch(this.keys, 0, this.size(), key);
				if (keyIndex < 0) {
					// first children key is not present in the internal node keys array
					final BPlusTreeNode<?> firstChild = this.children[0];
					return (
						firstChild instanceof BPlusInternalTreeNode internalNodeChild ?
							internalNodeChild.getLeftBoundaryKey() == key : firstChild.getKeys()[0] == key) ? 0 : -1;
				} else {
					Assert.isPremiseValid(
						this.children[keyIndex + 1] == node,
						"Key index does not match the child node!" // this should never happen
					);
					return keyIndex + 1;
				}
			}
		}

		/**
		 * Retrieves the left boundary key of the BPlusInternalTreeNode. This key is the smallest key contained
		 * within the leftmost child of the current internal tree node. If the leftmost child is an internal node itself,
		 * the method recursively retrieves the left boundary key of that internal node.
		 *
		 * @return the left boundary key of the BPlusInternalTreeNode.
		 */
		public int getLeftBoundaryKey() {
			return this.children[0] instanceof BPlusInternalTreeNode leftInternalNode ?
				leftInternalNode.getLeftBoundaryKey() : this.children[0].getKeys()[0];
		}

		/**
		 * Removes a child node from the children array at the specified index.
		 * This operation shifts all subsequent child nodes one position to the left,
		 * effectively overwriting the array element at the given index. The size of
		 * the array remains unchanged, but the number of meaningful elements (peek)
		 * is decremented.
		 *
		 * @param index The position of the child node to be removed from the children array.
		 *              It must be within the bounds of the current number of children (peek).
		 */
		public <N extends BPlusTreeNode<N>> void removeChildOnIndex(int index) {
			//noinspection unchecked
			final BPlusTreeNode<N> child = (BPlusTreeNode<N>) this.children[index];
			if (child.getPreviousNode() != null) {
				child.getPreviousNode().setNextNode(child.getNextNode());
			}
			if (child.getNextNode() != null) {
				child.getNextNode().setPreviousNode(child.getPreviousNode());
			}

			removeIntFromSameArrayOnIndex(this.keys, index);
			removeRecordFromSameArrayOnIndex(this.children, index);
			this.peek--;
		}

		/**
		 * Updates the key associated with the specified index in the internal node.
		 * The key to update must correspond to the given child node at the specified index.
		 *
		 * @param index The index in the keys array where the key needs to be updated.
		 *              Must be greater than 0 and within the bounds of the current keys array.
		 * @param node  The BPlusTreeNode whose first key will replace the key at the specified index in the internal node.
		 *              Must match the child node of this internal node at the specified index.
		 */
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node) {
			Assert.isPremiseValid(
				index > 0,
				"Leftmost child node does not have a key in the parent node!"
			);
			Assert.isPremiseValid(
				this.children[index] == node,
				"Node to update key for must match the child node at the specified index!"
			);
			this.keys[index - 1] = node instanceof BPlusInternalTreeNode internalNode ?
				internalNode.getLeftBoundaryKey() : node.getKeys()[0];
		}

		@Override
		public boolean isFull() {
			return this.peek == this.children.length - 1;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces)).append("< ").append(this.keys[0]).append(":\n");
			this.children[0].toVerboseString(sb, level + 1, indentSpaces);
			sb.append("\n");
			for (int i = 1; i <= this.peek; i++) {
				final int key = this.keys[i - 1];
				final BPlusTreeNode<?> child = this.children[i];
				sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
				child.toVerboseString(sb, level + 1, indentSpaces);
				if (i < this.peek) {
					sb.append("\n");
				}
			}
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

	}

	/**
	 * B+ Tree Node class to represent leaf node with associated values.
	 */
	static class BPlusLeafTreeNode<V> implements BPlusTreeNode<BPlusLeafTreeNode<V>> {
		/**
		 * The keys stored in this node.
		 */
		@Getter private final int[] keys;

		/**
		 * The values stored in this node. Index i corresponds to the value associated with key i.
		 */
		@Getter private final V[] values;

		/**
		 * Link to the previous leaf node.
		 */
		@Nullable @Getter @Setter
		private BPlusLeafTreeNode<V> previousNode;

		/**
		 * Link to the next leaf node.
		 */
		@Nullable @Getter @Setter
		private BPlusLeafTreeNode<V> nextNode;

		/**
		 * Index of the last occupied position in the keys array.
		 */
		@Getter @Setter private int peek;

		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<V> valueType
		) {
			this.keys = new int[blockSize];
			//noinspection unchecked
			this.values = (V[]) Array.newInstance(valueType, blockSize);
			this.previousNode = null;
			this.nextNode = null;
			this.peek = -1;
		}

		public BPlusLeafTreeNode(
			int[] originKeys, V[] originValues,
			int[] keys, V[] values,
			int start, int end
		) {
			this.keys = keys;
			this.values = values;
			// Copy the keys and values from the origin arrays
			for (int i = start; i < end; i++) {
				keys[i - start] = originKeys[i];
				values[i - start] = originValues[i];
			}
			this.previousNode = null;
			this.nextNode = null;
			this.peek = end - start - 1;
		}

		/**
		 * Searches for a value in the node's key-value pairs by the specified key.
		 * If the key is found, returns an Optional containing the associated value;
		 * otherwise returns an empty Optional.
		 *
		 * @param key the key to search for in the leaf node
		 * @return an Optional containing the value associated with the specified key if found;
		 * otherwise, an empty Optional
		 */
		@Nonnull
		public Optional<V> search(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(this.values[insertionPosition.position()]) : Optional.empty();
		}

		/**
		 * Steals a specified number of values from the end of the left sibling node.
		 *
		 * @param numberOfTailValues the number of values to steal from the left sibling node.
		 */
		public void stealFromLeft(int numberOfTailValues) {
			final BPlusLeafTreeNode<V> prevNode = Objects.requireNonNull(this.previousNode);
			System.arraycopy(prevNode.getKeys(), prevNode.size() - numberOfTailValues, this.keys, this.peek + 1, numberOfTailValues);
			System.arraycopy(prevNode.getValues(), prevNode.size() - numberOfTailValues, this.values, this.peek + 1, numberOfTailValues);
			this.peek += numberOfTailValues;
			prevNode.setPeek(prevNode.getPeek() - numberOfTailValues);
		}

		/**
		 * Steals a specified number of values from the start of the right sibling node.
		 *
		 * @param numberOfHeadValues the number of values to steal from the right sibling node.
		 */
		public void stealFromRight(int numberOfHeadValues) {
			final BPlusLeafTreeNode<V> nextNode = Objects.requireNonNull(this.nextNode);
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, numberOfHeadValues);
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			System.arraycopy(nextNode.getKeys(), numberOfHeadValues + 1, nextNode.getKeys(), 0, nextNode.size());
			System.arraycopy(nextNode.getValues(), numberOfHeadValues + 1, nextNode.getValues(), 0, nextNode.size());
			this.peek += numberOfHeadValues;
		}

		@Override
		public boolean isFull() {
			return this.peek == this.values.length - 1;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			for (int i = 0; i <= this.peek; i++) {
				sb.append(this.keys[i]).append(":").append(this.values[i]);
				if (i < this.peek) {
					sb.append(", ");
				}
			}
		}

		/**
		 * Merges the current leaf node with the left sibling leaf node.
		 */
		public void mergeWithLeft() {
			final BPlusLeafTreeNode<V> nodeToMergeWith = Objects.requireNonNull(this.previousNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek + 1);
			System.arraycopy(this.values, 0, this.values, mergePeek + 1, this.peek + 1);
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, 0, mergePeek + 1);
			System.arraycopy(nodeToMergeWith.getValues(), 0, this.values, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
		}

		/**
		 * Merges the current leaf node with the right sibling leaf node.
		 */
		public void mergeWithRight() {
			final BPlusLeafTreeNode<V> nodeToMergeWith = Objects.requireNonNull(this.nextNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1);
			System.arraycopy(nodeToMergeWith.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
			this.peek += mergePeek + 1;
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		/**
		 * Deletes a key-value pair from the BPlusLeafTreeNode based on the specified key.
		 * If the key is found within the node, it removes the corresponding entry,
		 * maintains the node's internal structure, and decrements the count of stored items.
		 *
		 * @param key the key of the entry to be removed from the leaf node
		 * @return true if the key was found and removed, false otherwise
		 * @throws GenericEvitaInternalError if the key is not found in the node
		 */
		public boolean delete(int key) {
			final int index = Arrays.binarySearch(this.keys, 0, this.peek + 1, key);

			if (index >= 0) {
				removeIntFromSameArrayOnIndex(this.keys, index);
				removeRecordFromSameArrayOnIndex(this.values, index);
				this.peek--;
				return true;
			} else {
				return false;
			}
		}

		/**
		 * Inserts a key-value pair into a specified leaf node of the B+ tree.
		 * Adjusts the position of the key and maintains the order of keys within the leaf node.
		 * If the key already exists, this method will add it in the correct position to maintain order.
		 *
		 * @param key   the key to be inserted into the leaf node
		 * @param value the value associated with the key, must not be null
		 * @return true if new key was inserted, otherwise false
		 */
		private boolean insert(int key, @Nonnull V value) {
			Assert.isPremiseValid(
				this.peek < this.keys.length - 1,
				"Cannot insert into a full leaf node, split the node first!"
			);

			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			if (insertionPosition.alreadyPresent()) {
				this.keys[insertionPosition.position()] = key;
				this.values[insertionPosition.position()] = value;
				return false;
			} else {
				insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
				insertRecordIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
				this.peek++;
				return true;
			}
		}

	}

	/**
	 * Represents a result of finding a leaf node in a B+ tree along with the path taken
	 * to reach that leaf node. This is useful for operations that need to track the
	 * traversed path of internal nodes leading to a specific leaf.
	 *
	 * @param <V>  the type of the value stored in the leaf node
	 * @param path a non-null list of internal tree nodes representing the path from the root
	 *             to the leaf node in the B+ tree
	 * @param leaf a non-null leaf tree node where the search or modification operation is targeted
	 */
	private record LeafWithPath<V>(
		@Nonnull List<BPlusInternalTreeNode> path,
		@Nonnull BPlusLeafTreeNode<V> leaf
	) {
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardKeyIterator implements OfInt {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<?> currentLeaf;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardKeyIterator(@Nonnull BPlusLeafTreeNode<?> leaf) {
			this.currentLeaf = leaf;
			this.currentKeyIndex = 0;
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext || this.currentLeaf == null || this.currentKeyIndex > this.currentLeaf.getPeek()) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.currentLeaf.getKeys()[this.currentKeyIndex];
			if (this.currentKeyIndex < this.currentLeaf.getPeek()) {
				this.currentKeyIndex++;
			} else {
				this.currentLeaf = this.currentLeaf.getNextNode();
				this.currentKeyIndex = 0;
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return key;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class ReverseKeyIterator implements OfInt {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<?> currentLeaf;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseKeyIterator(@Nonnull BPlusLeafTreeNode<?> leaf) {
			this.currentLeaf = leaf;
			this.currentKeyIndex = this.currentLeaf.getPeek();
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext || this.currentLeaf == null || this.currentKeyIndex < 0) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.currentLeaf.getKeys()[this.currentKeyIndex];
			if (this.currentKeyIndex > 0) {
				this.currentKeyIndex--;
			} else {
				this.currentLeaf = this.currentLeaf.getPreviousNode();
				this.currentKeyIndex = this.currentLeaf == null ? -1 : this.currentLeaf.getPeek();
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return key;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardValueIterator<V> implements Iterator<V> {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<V> currentLeaf;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardValueIterator(@Nonnull BPlusLeafTreeNode<V> leaf) {
			this.currentLeaf = leaf;
			this.currentValueIndex = 0;
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Override
		public V next() {
			if (!this.hasNext || this.currentLeaf == null || this.currentValueIndex > this.currentLeaf.getPeek()) {
				throw new NoSuchElementException("No more elements available");
			}
			final V value = this.currentLeaf.getValues()[this.currentValueIndex];
			if (this.currentValueIndex < this.currentLeaf.getPeek()) {
				this.currentValueIndex++;
			} else {
				this.currentLeaf = this.currentLeaf.getNextNode();
				this.currentValueIndex = 0;
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class ReverseValueIterator<V> implements Iterator<V> {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<V> currentLeaf;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseValueIterator(@Nonnull BPlusLeafTreeNode<V> leaf) {
			this.currentLeaf = leaf;
			this.currentValueIndex = this.currentLeaf.getPeek();
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Override
		public V next() {
			if (!this.hasNext || this.currentLeaf == null || this.currentValueIndex < 0) {
				throw new NoSuchElementException("No more elements available");
			}
			final V value = this.currentLeaf.getValues()[this.currentValueIndex];
			if (this.currentValueIndex > 0) {
				this.currentValueIndex--;
			} else {
				this.currentLeaf = this.currentLeaf.getPreviousNode();
				this.currentValueIndex = this.currentLeaf == null ? -1 : this.currentLeaf.getPeek();
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return value;
		}
	}

}
