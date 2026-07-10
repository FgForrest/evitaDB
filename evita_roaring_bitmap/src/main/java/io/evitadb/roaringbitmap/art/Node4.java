package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.longlong.IntegerUtil;
import io.evitadb.roaringbitmap.longlong.LongUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Smallest inner node of the Adaptive Radix Tree, holding up to 4 children.
 *
 * The up to four key bytes are packed into a single `int` (one byte per position, kept sorted
 * ascending) instead of a separate array, so a lookup is a short unrolled scan with no extra
 * allocation. Overflowing the fourth slot promotes the node to a {@link Node16} via {@link #insert};
 * dropping to a single child collapses this node into that child (merging the prefix) via
 * {@link #remove}.
 */
public class Node4 extends BranchNode {

	/**
	 * The up to `count` key bytes, packed big-endian into an `int` (key byte for position `i` lives in
	 * byte `3 - i`) and kept in ascending unsigned order.
	 */
	int key = 0;
	/**
	 * Child slots, index-aligned with the packed {@link #key} bytes.
	 */
	@Nonnull Node[] children = new Node[4];

	public Node4(int compressedPrefixSize) {
		super(compressedPrefixSize);
	}

	@Override
	@Nonnull
	protected Node4 clone() {
		final Node4 clone = new Node4(this.prefixLength());
		clone.key = this.key;
		postClone(clone, this.children, clone.children);
		return clone;
	}

	@Override
	@Nonnull
	protected NodeType nodeType() {
		return NodeType.NODE4;
	}

	@Override
	public int getChildPos(byte k) {
		for (int i = 0; i < this.count; i++) {
			final int shiftLeftLen = (3 - i) * 8;
			final byte v = (byte) (this.key >> shiftLeftLen);
			if (v == k) {
				return i;
			}
		}
		return ILLEGAL_IDX;
	}

	@Override
	@Nonnull
	public SearchResult getNearestChildPos(byte k) {
		final byte[] firstBytes = IntegerUtil.toBDBytes(this.key);
		return binarySearchWithResult(firstBytes, 0, this.count, k);
	}

	@Override
	public byte getChildKey(int pos) {
		final int shiftLeftLen = (3 - pos) * 8;
		return (byte) (this.key >> shiftLeftLen);
	}

	@Override
	@Nonnull
	public Node getChild(int pos) {
		return this.children[pos];
	}

	@Override
	@Nullable
	public Node getChildAtKey(byte key) {
		final int pos = getChildPos(key);
		return (pos != ILLEGAL_IDX) ? this.children[pos] : null;
	}

	@Override
	public void replaceNode(int pos, @Nonnull Node freshOne) {
		this.children[pos] = freshOne;
	}

	@Override
	public int getMinPos() {
		return 0;
	}

	@Override
	public int getNextLargerPos(int pos) {
		if (pos == ILLEGAL_IDX) {
			return 0;
		}
		pos++;
		return pos < this.count ? pos : ILLEGAL_IDX;
	}

	@Override
	public int getMaxPos() {
		return this.count - 1;
	}

	@Override
	public int getNextSmallerPos(int pos) {
		if (pos == ILLEGAL_IDX) {
			return this.count - 1;
		}
		pos--;
		return pos >= 0 ? pos : ILLEGAL_IDX;
	}

	/**
	 * insert the child node into this with the key byte
	 *
	 * @param childNode the child node
	 * @param key       the key byte
	 * @return the input node4 or an adaptive generated node16
	 */
	@Override
	@Nonnull
	protected BranchNode insert(@Nonnull Node childNode, byte key) {
		if (this.count < 4) {
			// insert leaf into current node
			this.key = IntegerUtil.setByte(this.key, key, this.count);
			this.children[this.count] = childNode;
			this.count++;
			insertionSort(this);
			return this;
		} else {
			// grow to Node16
			final Node16 node16 = new Node16(this.prefixLength());
			node16.count = 4;
			node16.firstV = LongUtils.initWithFirst4Byte(this.key);
			System.arraycopy(this.children, 0, node16.children, 0, 4);
			copyPrefix(this, node16);
			return node16.insert(childNode, key);
		}
	}

	@Override
	@Nonnull
	public Node remove(int pos) {
		assert pos < this.count;
		this.count--;
		this.key = IntegerUtil.shiftLeftFromSpecifiedPosition(this.key, pos, (4 - pos - 1));
		for (; pos < this.count; pos++) {
			this.children[pos] = this.children[pos + 1];
		}
		this.children[pos] = null;
		if (this.count == 1) {
			// shrink to the child node
			final Node childNode = this.children[0];
			if (childNode instanceof BranchNode) {
				final BranchNode child = (BranchNode) childNode;
				final byte childPrefixLength = child.prefixLength();
				final byte thisPrefixLength = this.prefixLength();
				final byte newLength = (byte) (childPrefixLength + thisPrefixLength + 1);
				final byte[] newPrefix = new byte[newLength];
				System.arraycopy(this.prefix, 0, newPrefix, 0, thisPrefixLength);
				newPrefix[thisPrefixLength] = IntegerUtil.firstByte(this.key);
				System.arraycopy(child.prefix, 0, newPrefix, thisPrefixLength + 1, childPrefixLength);
				child.prefix = newPrefix;
			}
			return childNode;
		}
		return this;
	}

	@Override
	public void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException {
		dataOutput.writeInt(Integer.reverseBytes(this.key));
	}

	/**
	 * serialize the node's body content
	 */
	@Override
	public void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		byteBuffer.putInt(this.key);
	}

	@Override
	public void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException {
		final int v = dataInput.readInt();
		this.key = Integer.reverseBytes(v);
	}

	/**
	 * deserialize the node's body content
	 */
	@Override
	public void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		this.key = byteBuffer.getInt();
	}

	@Override
	public int serializeNodeBodySizeInBytes() {
		return 4;
	}

	@Override
	public void replaceChildren(@Nonnull Node[] children) {
		System.arraycopy(children, 0, this.children, 0, this.count);
	}

	/**
	 * Re-sorts the packed {@link #key} bytes (and their children) into ascending unsigned order after
	 * an append, so that the ordered-traversal and nearest-key lookups stay correct.
	 *
	 * @param node4 the node whose freshly appended key needs to be sorted into place
	 */
	private static void insertionSort(@Nonnull Node4 node4) {
		final byte[] key = IntegerUtil.toBDBytes(node4.key);
		final byte[] sortedKey = sortSmallByteArray(key, node4.children, 0, node4.count - 1);
		node4.key = IntegerUtil.fromBDBytes(sortedKey);
	}
}
