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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.Optional;

/**
 * Represents a B+ Tree data structure specifically designed for integer keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class IntBPlusTree<V> {
	/**
	 * Maximum number of keys per node.
	 */
	private final int order;
	/**
	 * The type of the values stored in the tree.
	 */
	private final Class<V> valueType;
	/**
	 * Root node of the tree.
	 */
	private BPlusTreeNode root;

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
	private static Optional<BPlusInternalTreeNode> findParent(@Nonnull BPlusTreeNode current, @Nonnull BPlusTreeNode target) {
		if (current instanceof BPlusInternalTreeNode currentInternalNode) {
			return currentInternalNode.findParentOf(target);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param order maximum number of keys per node
	 */
	public IntBPlusTree(int order, @Nonnull Class<V> valueType) {
		if (order < 3) {
			throw new IllegalArgumentException("Order must be at least 3");
		}
		this.order = order;
		this.valueType = valueType;
		this.root = new BPlusLeafTreeNode<>(order, valueType);
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
		leaf.insert(leaf, key, value);

		// Split the leaf node if it exceeds the order
		if (leaf.isFull()) {
			splitLeafNode(leaf);
		}
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

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(1_024);
		this.root.toVerboseString(sb, 0, 3);
		return sb.toString();
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
		BPlusTreeNode node = this.root;
		while (node instanceof BPlusInternalTreeNode internalNode) {
			node = internalNode.search(key);
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<V>) node;
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf The leaf node to be split
	 */
	private void splitLeafNode(@Nonnull BPlusLeafTreeNode<V> leaf) {
		final int mid = (this.order + 1) / 2;
		final int[] originKeys = leaf.getKeys();
		final V[] originValues = leaf.getValues();

		// Move half the keys to the new arrays of the left leaf node
		//noinspection unchecked
		final BPlusLeafTreeNode<V> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			new int[this.order],
			(V[]) Array.newInstance(this.valueType, this.order),
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

		leftLeaf.setPreviousNode(leaf.getPreviousNode());
		leftLeaf.setNextNode(rightLeaf);
		rightLeaf.setPreviousNode(leftLeaf);
		rightLeaf.setNextNode(leaf.getNextNode());

		// If the root splits, create a new root
		if (leaf == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.order,
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
		@Nonnull BPlusTreeNode original,
		@Nonnull BPlusTreeNode left,
		@Nonnull BPlusTreeNode right,
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
		final int mid = (this.order + 1) / 2;
		final int[] originKeys = internal.getKeys();
		final BPlusTreeNode[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node
		final BPlusInternalTreeNode leftLeaf = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			new int[this.order],
			new BPlusTreeNode[this.order],
			0,
			mid
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusInternalTreeNode rightLeaf = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			originKeys,
			originChildren,
			mid,
			leftLeaf.getKeys().length
		);

		// If the root splits, create a new root
		if (internal == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.order,
				rightLeaf.getKeys()[0],
				leftLeaf, rightLeaf
			);
		} else {
			replaceNodeInParentInternalNode(
				internal,
				leftLeaf,
				rightLeaf,
				rightLeaf.getKeys()[0]
			);
		}
	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	interface BPlusTreeNode {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		int[] getKeys();

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

	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	static class BPlusInternalTreeNode implements BPlusTreeNode {
		/**
		 * The keys stored in this node.
		 */
		@Getter private final int[] keys;

		/**
		 * The children of this node.
		 */
		@Getter private final BPlusTreeNode[] children;

		/**
		 * Index of the last occupied position in the keys array.
		 */
		private int peek;

		public BPlusInternalTreeNode(
			int order,
			int key,
			@Nonnull BPlusTreeNode leftLeaf,
			@Nonnull BPlusTreeNode rightLeaf
		) {
			this.keys = new int[order];
			this.children = new BPlusTreeNode[order + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
		}

		public BPlusInternalTreeNode(
			int[] originKeys, BPlusTreeNode[] originValues,
			int[] keys, BPlusTreeNode[] values,
			int start, int end
		) {
			this.keys = keys;
			this.children = values;
			// Copy the keys and children from the origin arrays
			for (int i = start; i < end; i++) {
				keys[i - start] = originKeys[i];
				values[i - start] = originValues[i];
			}
			this.peek = end - start - 1;
		}

		/**
		 * Finds the parent node of the specified target node within the B+ tree.
		 *
		 * @param targetNode The target BPlusTreeNode for which the parent needs to be found.
		 * @return An Optional containing the parent BPlusInternalTreeNode if the parent is found,
		 * or an empty Optional if the target node has no parent within this subtree.
		 */
		@Nonnull
		public Optional<BPlusInternalTreeNode> findParentOf(@Nonnull BPlusTreeNode targetNode) {
			for (final BPlusTreeNode child : this.children) {
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
			@Nonnull BPlusTreeNode original,
			@Nonnull BPlusTreeNode left,
			@Nonnull BPlusTreeNode right
		) {
			// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			Assert.isPremiseValid(
				original == this.children[insertionPosition.position()],
				"Original node must be the child of the internal node!"
			);
			Assert.isPremiseValid(
				!insertionPosition.alreadyPresent(),
				"Key already present in the internal node!"
			);

			ArrayUtils.insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
			this.children[insertionPosition.position()] = left;
			ArrayUtils.insertRecordIntoSameArrayOnIndex(right, this.children, insertionPosition.position() + 1);
			this.peek++;
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public BPlusTreeNode search(int key) {
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			return insertionPosition.alreadyPresent() ?
				this.children[insertionPosition.position() + 1] : this.children[insertionPosition.position()];
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
				final BPlusTreeNode child = this.children[i];
				sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
				child.toVerboseString(sb, level + 1, indentSpaces);
				sb.append("\n");
			}
		}

	}

	/**
	 * B+ Tree Node class to represent leaf node with associated values.
	 */
	static class BPlusLeafTreeNode<V> implements BPlusTreeNode {
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
		private int peek;

		public BPlusLeafTreeNode(
			int order,
			@Nonnull Class<V> valueType
		) {
			this.keys = new int[order];
			//noinspection unchecked
			this.values = (V[]) Array.newInstance(valueType, order);
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
		 * Searches for a value in the node's key-value pairs by the specified key.
		 * If the key is found, returns an Optional containing the associated value;
		 * otherwise returns an empty Optional.
		 *
		 * @param key the key to search for in the leaf node
		 * @return an Optional containing the value associated with the specified key if found;
		 *         otherwise, an empty Optional
		 */
		@Nonnull
		public Optional<V> search(int key) {
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(this.values[insertionPosition.position()]) : Optional.empty();
		}

		/**
		 * Inserts a key-value pair into a specified leaf node of the B+ tree.
		 * Adjusts the position of the key and maintains the order of keys within the leaf node.
		 * If the key already exists, this method will add it in the correct position to maintain order.
		 *
		 * @param leaf  the leaf node where the key-value pair will be inserted
		 * @param key   the key to be inserted into the leaf node
		 * @param value the value associated with the key, must not be null
		 */
		private void insert(@Nonnull BPlusLeafTreeNode<V> leaf, int key, @Nonnull V value) {
			Assert.isPremiseValid(
				this.peek < this.keys.length - 1,
				"Cannot insert into a full leaf node, split the node first!"
			);

			final int[] keys = leaf.getKeys();
			final V[] values = leaf.getValues();

			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(key, keys, 0, this.peek + 1);
			if (insertionPosition.alreadyPresent()) {
				this.keys[insertionPosition.position()] = key;
				this.values[insertionPosition.position()] = value;
			} else {
				ArrayUtils.insertIntIntoSameArrayOnIndex(key, keys, insertionPosition.position());
				ArrayUtils.insertRecordIntoSameArrayOnIndex(value, values, insertionPosition.position());
				this.peek++;
			}
		}

	}

}
