package io.evitadb.roaringbitmap.art;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract inner (non-leaf) node of the Adaptive Radix Tree. Each branch owns a compressed path
 * {@link #prefix} — the run of key bytes common to its whole subtree, collapsed into a single node
 * to shorten the tree — plus a set of children indexed by the next key byte.
 *
 * The concrete subtypes {@link Node4}, {@link Node16}, {@link Node48} and {@link Node256} differ
 * only in how densely they store that byte-to-child mapping; a node adapts to the next larger layout
 * when {@link #insert} overflows it and shrinks back on {@link #remove}, keeping memory proportional
 * to actual fan-out. This class defines the navigation contract they all implement: positional and
 * key-based child lookup, ordered traversal ({@link #getMinPos} / {@link #getNextLargerPos} and
 * their reverse), and nearest-neighbour search via {@link #getNearestChildPos}.
 *
 * Positions returned by these methods are opaque per-subtype child slots, not key bytes; the
 * sentinel {@link #ILLEGAL_IDX} marks "no such child".
 */
public abstract class BranchNode extends Node {

	/**
	 * Compressed path: key bytes shared by every key under this node, elided from the children.
	 */
	@Nonnull protected byte[] prefix;
	/**
	 * Number of non-null children. Never exceeds 256, but is held as a `short` so the full 0..256
	 * range fits a signed type and index arithmetic stays branch-free.
	 */
	protected short count;
	/**
	 * Sentinel position meaning "no matching child" returned by the lookup and traversal methods.
	 */
	public static final int ILLEGAL_IDX = -1;

	/**
	 * constructor
	 *
	 * @param compressedPrefixSize the prefix byte array size,less than or equal to 6
	 */
	public BranchNode(int compressedPrefixSize) {
		super();
		this.prefix = compressedPrefixSize == 0 ? Art.EMPTY_BYTES : new byte[compressedPrefixSize];
		this.count = 0;
	}

	/**
	 * Finishes a subtype `clone()` by deep-copying the shared branch state into the fresh node:
	 * the child count, an independent copy of the {@link #prefix}, and a recursive clone of every
	 * non-null child. Subtypes clone their own key layout first, then delegate here.
	 *
	 * @param newlyCloned the freshly allocated clone to populate
	 * @param oldChildren this node's child slots
	 * @param newChildren the clone's child slots to fill
	 */
	public void postClone(@Nonnull BranchNode newlyCloned, @Nonnull Node[] oldChildren, @Nonnull Node[] newChildren) {
		newlyCloned.count = this.count;
		// the prefix could in principle be shared, but that is fragile and saves very little
		if (this.prefix.length > 0) {
			newlyCloned.prefix = Arrays.copyOf(this.prefix, this.prefix.length);
		}
		for (int i = 0; i < oldChildren.length; i++) {
			if (oldChildren[i] != null) {
				newChildren[i] = oldChildren[i].clone();
			}
		}
	}

	/**
	 * The layout tag of this concrete branch, used to write the node-type byte during serialization.
	 */
	@Nonnull
	protected abstract NodeType nodeType();

	/**
	 * Length of the compressed {@link #prefix} in bytes (at most 6 for a 48-bit key path).
	 */
	protected byte prefixLength() {
		return (byte) this.prefix.length;
	}

	/**
	 * search the position of the input byte key in the node's key byte array part
	 *
	 * @param key       the input key byte array
	 * @param fromIndex inclusive
	 * @param toIndex   exclusive
	 * @param k         the target key byte value
	 * @return the array offset of the target input key 'k' or -1 to not found
	 */
	public static int binarySearch(@Nonnull byte[] key, int fromIndex, int toIndex, byte k) {
		final int inputUnsignedByte = Byte.toUnsignedInt(k);
		int low = fromIndex;
		int high = toIndex - 1;

		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int midVal = Byte.toUnsignedInt(key[mid]);

			if (midVal < inputUnsignedByte) {
				low = mid + 1;
			} else if (midVal > inputUnsignedByte) {
				high = mid - 1;
			} else {
				return mid; // key found
			}
		}
		// key not found.
		return ILLEGAL_IDX;
	}

	/**
	 * Like {@link #binarySearch} but, on a miss, also reports the flanking positions so callers can
	 * navigate to the nearest smaller/larger key. Backs {@link #getNearestChildPos} for the
	 * array-backed nodes.
	 *
	 * @param key       the sorted (ascending unsigned) key byte array to search
	 * @param fromIndex inclusive lower bound
	 * @param toIndex   exclusive upper bound
	 * @param k         the target key byte
	 * @return a found result carrying the exact position, or a not-found result carrying the
	 * surrounding positions
	 */
	@Nonnull
	static SearchResult binarySearchWithResult(@Nonnull byte[] key, int fromIndex, int toIndex, byte k) {
		final int inputUnsignedByte = Byte.toUnsignedInt(k);
		int low = fromIndex;
		int high = toIndex - 1;

		while (low != high) {
			final int mid = (low + high + 1) >>> 1; // ceil
			final int midVal = Byte.toUnsignedInt(key[mid]);

			if (midVal > inputUnsignedByte) {
				high = mid - 1;
			} else {
				low = mid;
			}
		}
		final int val = Byte.toUnsignedInt(key[low]);
		if (val == inputUnsignedByte) {
			return SearchResult.found(low);
		} else if (val < inputUnsignedByte) {
			final int highIndex = low + 1;
			return SearchResult.notFound(low, highIndex < toIndex ? highIndex : BranchNode.ILLEGAL_IDX);
		} else {
			return SearchResult.notFound(low - 1, low); // low - 1 == ILLEGAL_IDX if low == 0
		}
	}

	/**
	 * insert the LeafNode as a child of the current internal node
	 *
	 * @param childNode the leaf node
	 * @param key       the key byte reference to the child leaf node
	 * @return an adaptive changed node of the input 'current' node
	 */
	@Nonnull
	protected abstract BranchNode insert(@Nonnull Node childNode, byte key);

	/**
	 * copy the prefix between two nodes
	 *
	 * @param src the source node
	 * @param dst the destination node
	 */
	public static void copyPrefix(@Nonnull BranchNode src, @Nonnull BranchNode dst) {
		System.arraycopy(src.prefix, 0, dst.prefix, 0, src.prefixLength());
	}

	/**
	 * replace the node's children according to the given children parameter while doing the
	 * deserialization phase.
	 *
	 * @param children all the not null children nodes in key byte ascending order,no null element
	 */
	abstract void replaceChildren(@Nonnull Node[] children);

	/**
	 * get the position of a child corresponding to the input key 'k'
	 *
	 * @param k a key value of the byte range
	 * @return the child position corresponding to the key 'k'
	 */
	public abstract int getChildPos(byte k);

	/**
	 * Finds the child slot for key byte `k`, or — when `k` is absent — the positions of its nearest
	 * present neighbours. This is what lets range and ordered iteration land on the closest existing
	 * key rather than failing on a gap.
	 *
	 * @param key a key value of the byte range
	 * @return a result indicating whether the key was found and the positions of the child
	 * corresponding to it or to its immediate neighbours
	 */
	@Nonnull
	public abstract SearchResult getNearestChildPos(byte key);

	/**
	 * get the corresponding key byte of the requested position
	 *
	 * @param pos the position
	 * @return the corresponding key byte
	 */
	public abstract byte getChildKey(int pos);

	/**
	 * get the child at the specified position in the node, the 'pos' range from 0 to count
	 *
	 * @param pos the position
	 * @return a Node corresponding to the input position
	 */
	@Nonnull
	public abstract Node getChild(int pos);

	/**
	 * get the child at the specified key in the node.
	 * the behavior is equivalent to {@code
	 * int pos = getChildPos(key);
	 * return (pos != ILLEGAL_IDX) ? getChild(pos) : null;
	 * }
	 * but subclasses may be able to provide a more efficient implementation
	 *
	 * @param key the position
	 * @return a Node corresponding to the input position, or null if not found
	 */
	@Nullable
	public abstract Node getChildAtKey(byte key);

	/**
	 * replace the position child to the fresh one
	 *
	 * @param pos      the position
	 * @param freshOne the fresh node to replace the old one
	 */
	public abstract void replaceNode(int pos, @Nonnull Node freshOne);

	/**
	 * get the position of the min element in current node.
	 *
	 * @return the minimum key's position
	 */
	public abstract int getMinPos();

	/**
	 * get the next position in the node
	 *
	 * @param pos current position,-1 to start from the min one
	 * @return the next larger byte key's position which is close to 'pos' position,-1 for end
	 */
	public abstract int getNextLargerPos(int pos);

	/**
	 * get the max child's position
	 *
	 * @return the max byte key's position
	 */
	public abstract int getMaxPos();

	/**
	 * get the next smaller element's position
	 *
	 * @param pos the position,-1 to start from the largest one
	 * @return the next smaller key's position which is close to input 'pos' position,-1 for end
	 */
	public abstract int getNextSmallerPos(int pos);

	/**
	 * remove the specified position child
	 *
	 * @param pos the position to remove
	 * @return an adaptive changed fresh node of the current node
	 */
	@Nonnull
	public abstract Node remove(int pos);

	@Override
	protected void serializeHeader(@Nonnull DataOutput dataOutput) throws IOException {
		// first byte: node type
		dataOutput.writeByte((byte) this.nodeType().ordinal());
		// non null object count
		dataOutput.writeShort(Short.reverseBytes(this.count));
		final byte prefixLength = this.prefixLength();
		dataOutput.writeByte(prefixLength);
		if (prefixLength > 0) {
			dataOutput.write(this.prefix, 0, prefixLength);
		}
	}

	@Override
	protected void serializeHeader(@Nonnull ByteBuffer byteBuffer) throws IOException {
		byteBuffer.put((byte) this.nodeType().ordinal());
		byteBuffer.putShort(this.count);
		final byte prefixLength = this.prefixLength();
		byteBuffer.put(prefixLength);
		if (prefixLength > 0) {
			byteBuffer.put(this.prefix, 0, prefixLength);
		}
	}

	@Override
	protected int serializeHeaderSizeInBytes() {
		return super.serializeHeaderSizeInBytes() + prefixLength();
	}


}
