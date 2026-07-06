package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.longlong.LongUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Adaptive Radix Tree (ART) mapping the high 48 bits of a 64-bit value to the index of the
 * {@link io.evitadb.roaringbitmap.Container} that stores its low 16 bits. Together with
 * {@link Containers} it backs {@link io.evitadb.roaringbitmap.longlong.HighLowContainer}.
 *
 * Every {@link LeafNode} holds one 6-byte (48-bit) key and its container index; inner nodes
 * ({@link Node4}, {@link Node16}, {@link Node48}, {@link Node256}) adapt their fan-out to the number
 * of children and path-compress shared key bytes into a node prefix. Keys are compared as unsigned
 * bytes (dictionary order), so iteration yields ascending numeric order.
 *
 * Not thread-safe: callers mutating a tree must synchronise externally, or {@link #clone()} it for
 * isolated readers. See the reference design at https://db.in.tum.de/~leis/papers/ART.pdf.
 */
public class Art {

	/**
	 * Root node of the tree; {@code null} while no key is stored.
	 */
	@Nullable private Node root;
	/**
	 * Number of keys — equivalently {@link LeafNode}s — currently held.
	 */
	private long keySize = 0;

	/**
	 * Shared zero-length array reused as the prefix of nodes that carry no compressed path.
	 */
	@Nonnull final static byte[] EMPTY_BYTES = new byte[0];

	public Art() {
		this.root = null;
	}

	@Override
	@Nonnull
	@SuppressWarnings("CloneDoesntCallSuperClone") // deep copy; super.clone() would alias nodes
	public Art clone() {
		final Art art = new Art();
		art.keySize = this.keySize;
		if (this.root != null) {
			art.root = this.root.clone();
		}
		return art;
	}

	public boolean isEmpty() {
		return this.root == null;
	}

	/**
	 * insert the 48 bit key and the corresponding containerIdx
	 *
	 * @param key          the high 48 bit of the long data
	 * @param containerIdx the container index
	 */
	public void insert(@Nonnull byte[] key, long containerIdx) {
		final Node freshRoot = insert(this.root, key, 0, containerIdx);
		if (freshRoot != this.root) {
			this.root = freshRoot;
		}
		this.keySize++;
	}

	/**
	 * Looks up the container index stored under the given 48-bit key.
	 *
	 * @param key the high 48 bits of the long value, as a 6-byte array
	 * @return the container index, or {@link BranchNode#ILLEGAL_IDX} (-1) when the key is absent
	 */
	public long findByKey(@Nonnull byte[] key) {
		final Node node = findByKey(this.root, key, 0);
		if (node != null) {
			final LeafNode leafNode = (LeafNode) node;
			return leafNode.containerIdx;
		}
		return BranchNode.ILLEGAL_IDX;
	}

	/**
	 * Looks up the container index for the given value's high 48 bits.
	 *
	 * @param key a long whose high 48 bits form the lookup key
	 * @return the container index, or {@link BranchNode#ILLEGAL_IDX} (-1) when the key is absent
	 */
	public long findByKey(long key) {
		final LeafNode node = findByKey(this.root, key);
		if (node != null) {
			return node.containerIdx;
		}
		return BranchNode.ILLEGAL_IDX;
	}

	@Nullable
	private static Node findByKey(@Nullable Node node, @Nonnull byte[] key, int depth) {
		while (node != null) {
			if (node instanceof LeafNode) {
				final LeafNode leafNode = (LeafNode) node;
				final byte[] leafNodeKeyBytes = leafNode.getKeyBytes();
				if (depth == LeafNode.LEAF_NODE_KEY_LENGTH_IN_BYTES) {
					return leafNode;
				}
				final int mismatchIndex =
					Arrays.mismatch(
						leafNodeKeyBytes,
						depth,
						LeafNode.LEAF_NODE_KEY_LENGTH_IN_BYTES,
						key,
						depth,
						LeafNode.LEAF_NODE_KEY_LENGTH_IN_BYTES
					);
				if (mismatchIndex != -1) {
					return null;
				}
				return leafNode;
			}
			final BranchNode branchNode = (BranchNode) node;
			final byte branchNodePrefixLength = branchNode.prefixLength();
			if (branchNodePrefixLength > 0) {
				final int commonLength =
					commonPrefixLength(key, depth, key.length, branchNode.prefix, 0, branchNodePrefixLength);
				if (commonLength != branchNodePrefixLength) {
					return null;
				}
				// common prefix is the same ,then increase the depth
				depth += branchNodePrefixLength;
			}
			final int pos = branchNode.getChildPos(key[depth]);
			if (pos == BranchNode.ILLEGAL_IDX) {
				return null;
			}
			node = branchNode.getChild(pos);
			depth++;
		}
		return null;
	}

	@Nullable
	private static LeafNode findByKey(@Nullable Node node, long key) {
		int depth = 0;
		while (node != null) {
			//compare branch node first, its most common case
			if (node instanceof final BranchNode branchNode) {
				final byte branchNodePrefixLength = branchNode.prefixLength();
				if (branchNodePrefixLength > 0) {
					// Perf note: exposing the prefix as a long would turn this byte loop into an O(1)
					// long mask-and-compare.
					final byte[] prefix = branchNode.prefix;
					for (int i = 0; i < branchNodePrefixLength; i++) {
						// compare the prefix byte with the key byte
						if (prefix[i] != LongUtils.getByte(key, depth + i)) {
							return null;
						}
					}
					// common prefix is the same ,then increase the depth
					depth += branchNodePrefixLength;
				}
				node = branchNode.getChildAtKey(LongUtils.getByte(key, depth));
				depth++;
			} else {
				final LeafNode leafNode = (LeafNode) node;
				final long leafNodeKey = leafNode.getKey();
				return leafNodeKey == LongUtils.rightShiftHighPart(key) ? leafNode : null;
			}
		}
		return null;
	}

	/**
	 * a convenient method to traverse the key space in ascending order.
	 *
	 * @param containers input containers
	 * @return the key iterator
	 */
	@Nonnull
	public KeyIterator iterator(@Nullable Containers containers) {
		return new KeyIterator(this, containers);
	}

	/**
	 * remove the key from the art if it's there.
	 *
	 * @param key the high 48 bit key
	 * @return the corresponding containerIdx or -1 indicating not exist
	 */
	public long remove(@Nonnull byte[] key) {
		final Toolkit toolkit = removeSpecifyKey(this.root, key, 0);
		if (toolkit != null) {
			return toolkit.matchedContainerId;
		}
		return BranchNode.ILLEGAL_IDX;
	}

	@Nullable
	protected Toolkit removeSpecifyKey(@Nullable Node node, @Nonnull byte[] key, int dep) {
		if (node == null) {
			return null;
		}
		if (node instanceof final LeafNode leafNode) {
			// root is null
			if (leafMatch(leafNode, key, dep)) {
				// remove this node
				if (leafNode == this.root) {
					this.root = null;
				}
				this.keySize--;
				return new Toolkit(null, leafNode.getContainerIdx(), null);
			} else {
				return null;
			}
		}
		final BranchNode branchNode = (BranchNode) node;
		final byte branchNodePrefixLength = branchNode.prefixLength();
		if (branchNodePrefixLength > 0) {
			final int commonLength =
				commonPrefixLength(key, dep, key.length, branchNode.prefix, 0, branchNodePrefixLength);
			if (commonLength != branchNodePrefixLength) {
				return null;
			}
			dep += branchNodePrefixLength;
		}
		final int pos = branchNode.getChildPos(key[dep]);
		if (pos != BranchNode.ILLEGAL_IDX) {
			final Node child = branchNode.getChild(pos);
			if (child instanceof LeafNode && leafMatch((LeafNode) child, key, dep)) {
				// found matched leaf node from the current node.
				final Node freshNode = branchNode.remove(pos);
				this.keySize--;
				if (branchNode == this.root && freshNode != branchNode) {
					this.root = freshNode;
				}
				final long matchedContainerIdx = ((LeafNode) child).getContainerIdx();
				final Toolkit toolkit = new Toolkit(freshNode, matchedContainerIdx, branchNode);
				toolkit.needToVerifyReplacing = true;
				return toolkit;
			} else {
				final Toolkit toolkit = removeSpecifyKey(child, key, dep + 1);
				if (toolkit != null
					&& toolkit.needToVerifyReplacing
					&& toolkit.freshMatchedParentNode != null
					&& toolkit.freshMatchedParentNode != toolkit.originalMatchedParentNode) {
					// meaning find the matched key and the shrinking happened
					branchNode.replaceNode(pos, toolkit.freshMatchedParentNode);
					toolkit.needToVerifyReplacing = false;
					return toolkit;
				}
				if (toolkit != null) {
					return toolkit;
				}
			}
		}
		return null;
	}

	/**
	 * Mutable carrier threaded back up the {@link #removeSpecifyKey} recursion so an ancestor can learn
	 * the removed key's container index and re-link any child that an adaptive node shrink replaced.
	 */
	static class Toolkit {

		@Nullable Node freshMatchedParentNode; // indicating a fresh parent node while the original
		// parent node shrunk and changed
		long matchedContainerId; // holding the matched key's corresponding container index id
		@Nullable Node
			originalMatchedParentNode; // holding the matched key's leaf node's original old parent node
		boolean needToVerifyReplacing = false; // indicate whether the shrinking node's parent

		// node has replaced its corresponding child node

		Toolkit(
			@Nullable Node freshMatchedParentNode, long matchedContainerId, @Nullable Node originalMatchedParentNode) {
			this.freshMatchedParentNode = freshMatchedParentNode;
			this.matchedContainerId = matchedContainerId;
			this.originalMatchedParentNode = originalMatchedParentNode;
		}
	}

	private static boolean leafMatch(@Nonnull LeafNode leafNode, @Nonnull byte[] key, int dep) {
		final byte[] leafNodeKeyBytes = leafNode.getKeyBytes();
		final int mismatchIndex =
			Arrays.mismatch(
				leafNodeKeyBytes,
				dep,
				LeafNode.LEAF_NODE_KEY_LENGTH_IN_BYTES,
				key,
				dep,
				LeafNode.LEAF_NODE_KEY_LENGTH_IN_BYTES
			);
		if (mismatchIndex == -1) {
			return true;
		} else {
			return false;
		}
	}

	@Nonnull
	private static Node insert(@Nullable Node node, @Nonnull byte[] key, int depth, long containerIdx) {
		if (node == null) {
			return new LeafNode(key, containerIdx);
		}
		if (node instanceof LeafNode) {
			final LeafNode leafNode = (LeafNode) node;
			final byte[] prefix = leafNode.getKeyBytes();
			final int commonPrefix = commonPrefixLength(prefix, depth, prefix.length, key, depth, key.length);

			final Node4 node4 = new Node4(commonPrefix);
			// copy common prefix
			System.arraycopy(key, depth, node4.prefix, 0, commonPrefix);
			// generate two leaf nodes as the children of the fresh node4
			node4.insert(leafNode, prefix[depth + commonPrefix]);
			final LeafNode anotherLeaf = new LeafNode(key, containerIdx);
			node4.insert(anotherLeaf, key[depth + commonPrefix]);
			// replace the current node with this internal node4
			return node4;
		}
		final BranchNode branchNode = (BranchNode) node;
		final byte branchNodePrefixLength = branchNode.prefixLength();
		// to a inner node case
		if (branchNodePrefixLength > 0) {
			// find the mismatch position
			final int mismatchPos =
				Arrays.mismatch(branchNode.prefix, 0, branchNodePrefixLength, key, depth, key.length);
			if (mismatchPos != branchNodePrefixLength) {
				final Node4 node4 = new Node4(mismatchPos);
				// copy prefix
				System.arraycopy(branchNode.prefix, 0, node4.prefix, 0, mismatchPos);
				// split the current internal node, spawn a fresh node4 and let the
				// current internal node as its children.
				node4.insert(branchNode, branchNode.prefix[mismatchPos]);
				final int newPrefixLength = (int) branchNodePrefixLength - (mismatchPos + 1);
				// move the remained common prefix of the initial internal node
				// as the new prefix is always > 0, we just allocate and fill the new prefix
				branchNode.prefix = Arrays.copyOfRange(branchNode.prefix, mismatchPos + 1, branchNodePrefixLength);

				final LeafNode leafNode = new LeafNode(key, containerIdx);
				node4.insert(leafNode, key[mismatchPos + depth]);
				return node4;
			}
			depth += branchNodePrefixLength;
		}
		final int pos = branchNode.getChildPos(key[depth]);
		if (pos != BranchNode.ILLEGAL_IDX) {
			// insert the key as current internal node's children's child node.
			final Node child = branchNode.getChild(pos);
			final Node freshOne = insert(child, key, depth + 1, containerIdx);
			if (freshOne != child) {
				branchNode.replaceNode(pos, freshOne);
			}
			return branchNode;
		}
		// insert the key as a child leaf node of the current internal node
		final LeafNode leafNode = new LeafNode(key, containerIdx);
		return branchNode.insert(leafNode, key[depth]);
	}

	// find common prefix length
	static int commonPrefixLength(
		@Nonnull byte[] key1, int aFromIndex, int aToIndex, @Nonnull byte[] key2, int bFromIndex, int bToIndex) {
		final int aLength = aToIndex - aFromIndex;
		final int bLength = bToIndex - bFromIndex;
		final int minLength = Math.min(aLength, bLength);
		final int mismatchIndex = Arrays.mismatch(key1, aFromIndex, aToIndex, key2, bFromIndex, bToIndex);

		if (aLength != bLength && mismatchIndex >= minLength) {
			return minLength;
		}
		return mismatchIndex;
	}

	/**
	 * @return the root node, or {@code null} when the tree is empty.
	 */
	@Nullable
	public Node getRoot() {
		return this.root;
	}

	@Nonnull
	private LeafNode getExtremeLeaf(boolean reverse) {
		Node parent = getRoot();
		for (int depth = 0; depth < AbstractShuttle.MAX_DEPTH; depth++) {
			if (parent instanceof BranchNode) {
				final BranchNode branchNode = (BranchNode) parent;
				final int childIndex = reverse ? branchNode.getMaxPos() : branchNode.getMinPos();
				parent = branchNode.getChild(childIndex);
			}
		}
		return (LeafNode) Objects.requireNonNull(parent, "non-empty tree always descends to a leaf");
	}

	/**
	 * @return the leaf holding the smallest key
	 * @throws ClassCastException if called on an empty tree
	 */
	@Nonnull
	public LeafNode first() {
		return getExtremeLeaf(false);
	}

	/**
	 * @return the leaf holding the largest key
	 * @throws ClassCastException if called on an empty tree
	 */
	@Nonnull
	public LeafNode last() {
		return getExtremeLeaf(true);
	}

	/**
	 * Serialises the tree to `dataOutput`: the key count followed by a pre-order traversal of every
	 * node. Integers are written little-endian to match the persisted layout.
	 *
	 * @param dataOutput destination stream
	 * @throws IOException on write failure
	 */
	public void serializeArt(@Nonnull DataOutput dataOutput) throws IOException {
		dataOutput.writeLong(Long.reverseBytes(this.keySize));
		serialize(this.root, dataOutput);
	}

	/**
	 * Rebuilds the tree from the little-endian layout produced by {@link #serializeArt(DataOutput)},
	 * replacing the current contents.
	 *
	 * @param dataInput source stream
	 * @throws IOException on read failure
	 */
	public void deserializeArt(@Nonnull DataInput dataInput) throws IOException {
		this.keySize = Long.reverseBytes(dataInput.readLong());
		this.root = deserialize(dataInput);
	}

	/**
	 * Serialises the tree into `byteBuffer` using the buffer's own byte order (unlike
	 * {@link #serializeArt(DataOutput)}, which always writes little-endian).
	 *
	 * @param byteBuffer destination buffer
	 * @throws IOException on write failure
	 */
	public void serializeArt(@Nonnull ByteBuffer byteBuffer) throws IOException {
		byteBuffer.putLong(this.keySize);
		serialize(this.root, byteBuffer);
	}

	/**
	 * Rebuilds the tree from `byteBuffer`, reading with the buffer's own byte order and replacing the
	 * current contents.
	 *
	 * @param byteBuffer source buffer
	 * @throws IOException on read failure
	 */
	public void deserializeArt(@Nonnull ByteBuffer byteBuffer) throws IOException {
		this.keySize = byteBuffer.getLong();
		this.root = deserialize(byteBuffer);
	}

	/**
	 * Iterates over the leaves (and thereby the keys) in key order.
	 *
	 * @param reverse    `false` for ascending, `true` for descending key order
	 * @param containers container store bound to the iterator so {@link LeafNodeIterator#remove()} can
	 *                   free a removed key's container; may be `null` when removal is not needed
	 * @return the leaf iterator
	 */
	@Nonnull
	public LeafNodeIterator leafNodeIterator(boolean reverse, @Nullable Containers containers) {
		return new LeafNodeIterator(this, reverse, containers);
	}

	/**
	 * Iterates over the leaves in key order starting at `bound`, positioning on the leaf whose key
	 * equals `bound` or, when absent, the next key in iteration direction.
	 *
	 * @param bound      inclusive starting key
	 * @param reverse    `false` for ascending, `true` for descending key order
	 * @param containers container store bound to the iterator (see {@link #leafNodeIterator}); may be `null`
	 * @return the leaf iterator positioned at `bound`
	 */
	@Nonnull
	public LeafNodeIterator leafNodeIteratorFrom(long bound, boolean reverse, @Nullable Containers containers) {
		return new LeafNodeIterator(this, reverse, containers, bound);
	}

	private static void serialize(@Nullable Node node, @Nonnull DataOutput dataOutput) throws IOException {
		if (node instanceof BranchNode) {
			final BranchNode branchNode = (BranchNode) node;
			// serialize the internal node itself first
			branchNode.serialize(dataOutput);
			// then all the internal node's children
			int nexPos = branchNode.getNextLargerPos(BranchNode.ILLEGAL_IDX);
			while (nexPos != BranchNode.ILLEGAL_IDX) {
				// serialize all the not null child node
				final Node child = branchNode.getChild(nexPos);
				serialize(child, dataOutput);
				nexPos = branchNode.getNextLargerPos(nexPos);
			}
		} else {
			// serialize the leaf node
			Objects.requireNonNull(node, "non-null leaf expected here").serialize(dataOutput);
		}
	}

	private static void serialize(@Nullable Node node, @Nonnull ByteBuffer byteBuffer) throws IOException {
		if (node instanceof final BranchNode branchNode) {
			// serialize the internal node itself first
			branchNode.serialize(byteBuffer);
			// then all the internal node's children
			int nexPos = branchNode.getNextLargerPos(BranchNode.ILLEGAL_IDX);
			while (nexPos != BranchNode.ILLEGAL_IDX) {
				// serialize all the not null child node
				final Node child = branchNode.getChild(nexPos);
				serialize(child, byteBuffer);
				nexPos = branchNode.getNextLargerPos(nexPos);
			}
		} else {
			// serialize the leaf node
			Objects.requireNonNull(node, "non-null leaf expected here").serialize(byteBuffer);
		}
	}

	@Nullable
	private static Node deserialize(@Nonnull DataInput dataInput) throws IOException {
		final Node oneNode = Node.deserialize(dataInput);
		if (oneNode == null) {
			return null;
		}
		if (oneNode instanceof LeafNode) {
			return oneNode;
		} else {
			final BranchNode branch = (BranchNode) oneNode;
			// internal node
			final int count = branch.count;
			// all the not null child nodes
			final Node[] children = new Node[count];
			for (int i = 0; i < count; i++) {
				final Node child = deserialize(dataInput);
				children[i] = child;
			}
			branch.replaceChildren(children);
			return branch;
		}
	}

	@Nullable
	private static Node deserialize(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final Node oneNode = Node.deserialize(byteBuffer);
		if (oneNode == null) {
			return null;
		}
		if (oneNode instanceof LeafNode) {
			return oneNode;
		} else {
			final BranchNode branchNode = (BranchNode) oneNode;
			// internal node
			final int count = branchNode.count;
			// all the not null child nodes
			final Node[] children = new Node[count];
			for (int i = 0; i < count; i++) {
				final Node child = deserialize(byteBuffer);
				children[i] = child;
			}
			branchNode.replaceChildren(children);
			return branchNode;
		}
	}

	/**
	 * @return the exact number of bytes {@link #serializeArt(DataOutput)} will write, including the
	 * 8-byte key-count header
	 */
	public long serializeSizeInBytes() {
		return serializeSizeInBytes(this.root) + 8;
	}

	/**
	 * @return the number of keys stored in the tree.
	 */
	public long getKeySize() {
		return this.keySize;
	}

	private static long serializeSizeInBytes(@Nullable Node node) {
		if (node instanceof BranchNode) {
			final BranchNode branchNode = (BranchNode) node;
			// serialize the internal node itself first
			final int currentNodeSize = branchNode.serializeSizeInBytes();
			// then all the internal node's children
			long childrenTotalSize = 0L;
			int nexPos = branchNode.getNextLargerPos(BranchNode.ILLEGAL_IDX);
			while (nexPos != BranchNode.ILLEGAL_IDX) {
				// serialize all the not null child node
				final Node child = branchNode.getChild(nexPos);
				final long childSize = serializeSizeInBytes(child);
				nexPos = branchNode.getNextLargerPos(nexPos);
				childrenTotalSize += childSize;
			}
			return currentNodeSize + childrenTotalSize;
		} else {
			// serialize the leaf node
			return Objects.requireNonNull(node, "non-null leaf expected here").serializeSizeInBytes();
		}
	}
}
