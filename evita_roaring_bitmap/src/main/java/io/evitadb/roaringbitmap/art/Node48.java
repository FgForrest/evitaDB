package io.evitadb.roaringbitmap.art;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Third inner-node size of the Adaptive Radix Tree, holding up to 48 children.
 *
 * At this fan-out a full 256-entry pointer array (like {@link Node256}) would waste space while a
 * sorted key scan (like {@link Node16}) would be too slow, so Node48 adds a level of indirection: a
 * 256-entry byte index keyed directly by the key byte maps to a slot in a dense 48-element child
 * array. Lookup is therefore O(1) on the key byte with no pointer array larger than the actual
 * child count. Overflow promotes to a {@link Node256} via {@link #insert}; shrinking past 12
 * children demotes back to a {@link Node16} via {@link #remove}.
 */
public class Node48 extends BranchNode {

	/**
	 * Number of index bytes packed per `long` word of {@link #childIndex}.
	 */
	static final int BYTES_PER_LONG = 8;
	/**
	 * Number of `long` words needed to hold the 256-entry {@link #childIndex} (256 / 8).
	 */
	static final int LONGS_USED = 256 / BYTES_PER_LONG;
	/**
	 * Right-shift to turn a key byte into its `childIndex` word position (2^3 == `BYTES_PER_LONG`).
	 */
	static final int INDEX_SHIFT = 3;
	/**
	 * Mask selecting the byte-within-word position of a key byte inside a `childIndex` word.
	 */
	static final int POS_MASK = 0x7;
	/**
	 * 256 slots — one per possible key byte — each holding the index (0..47) of the matching child in
	 * {@link #children}, or {@link #EMPTY_VALUE} when that key byte is absent. Packed eight bytes per
	 * `long` (big-endian within the word) to keep the whole index at 256 bytes.
	 */
	@Nonnull long[] childIndex = new long[LONGS_USED];
	/**
	 * Dense child pointers; only the first {@link BranchNode#count} slots are live, addressed via {@link #childIndex}.
	 */
	@Nonnull Node[] children = new Node[48];
	/**
	 * Marker byte in {@link #childIndex} meaning "no child for this key byte".
	 */
	static final byte EMPTY_VALUE = (byte) 0xFF;
	/**
	 * A `childIndex` word with all eight entries set to {@link #EMPTY_VALUE}; the empty-word initial value.
	 */
	static final long INIT_LONG_VALUE = 0xFFffFFffFFffFFffL;

	public Node48(int compressedPrefixSize) {
		super(compressedPrefixSize);
		Arrays.fill(this.childIndex, INIT_LONG_VALUE);
	}

	@Override
	@Nonnull
	protected Node48 clone() {
		final Node48 clone = new Node48(this.prefixLength());
		System.arraycopy(this.childIndex, 0, clone.childIndex, 0, LONGS_USED);
		postClone(clone, this.children, clone.children);
		return clone;
	}


	@Override
	@Nonnull
	protected NodeType nodeType() {
		return NodeType.NODE48;
	}

	@Override
	public int getChildPos(byte k) {
		final int unsignedIdx = Byte.toUnsignedInt(k);
		final int childIdx = childrenIdx(unsignedIdx, this.childIndex);
		if (childIdx != EMPTY_VALUE) {
			return unsignedIdx;
		}
		return ILLEGAL_IDX;
	}

	@Override
	@Nonnull
	public SearchResult getNearestChildPos(byte k) {
		final int unsignedIdx = Byte.toUnsignedInt(k);
		final int childIdx = childrenIdx(unsignedIdx, this.childIndex);
		if (childIdx != EMPTY_VALUE) {
			return SearchResult.found(unsignedIdx);
		}
		return SearchResult.notFound(getNextSmallerPos(unsignedIdx), getNextLargerPos(unsignedIdx));
	}

	@Override
	public byte getChildKey(int pos) {

		return (byte) pos;
	}

	@Override
	@Nonnull
	public Node getChild(int pos) {
		final byte idx = childrenIdx(pos, this.childIndex);
		return this.children[idx];
	}

	@Override
	@Nullable
	public Node getChildAtKey(byte key) {
		final int unsignedIdx = Byte.toUnsignedInt(key);
		final int childIdx = childrenIdx(unsignedIdx, this.childIndex);
		return (childIdx != EMPTY_VALUE) ? this.children[childIdx] : null;
	}

	@Override
	public void replaceNode(int pos, @Nonnull Node freshOne) {
		final byte idx = childrenIdx(pos, this.childIndex);
		this.children[idx] = freshOne;
	}

	@Override
	public int getMinPos() {
		int pos = 0;
		for (int i = 0; i < LONGS_USED; i++) {
			final long longv = this.childIndex[i];
			if (longv == INIT_LONG_VALUE) {
				// skip over empty bytes
				pos += BYTES_PER_LONG;
				continue;
			} else {
				for (int j = 0; j < BYTES_PER_LONG; j++) {
					final byte v = (byte) (longv >>> ((BYTES_PER_LONG - 1 - j) << INDEX_SHIFT));
					if (v != EMPTY_VALUE) {
						return pos;
					}
					pos++;
				}
			}
		}
		return ILLEGAL_IDX;
	}

	@Override
	public int getNextLargerPos(int pos) {
		// ILLEGAL_IDX (== -1) sorts before position 0, so the following pos++ yields 0 regardless
		pos++;
		int i = pos >>> INDEX_SHIFT;
		for (; i < LONGS_USED; i++) {
			final long longv = this.childIndex[i];
			if (longv == INIT_LONG_VALUE) {
				// skip over empty bytes
				pos = (pos + BYTES_PER_LONG) & 0xF8;
				continue;
			}

			for (int j = pos & POS_MASK; j < BYTES_PER_LONG; j++) {
				final int shiftNum = (BYTES_PER_LONG - 1 - j) << INDEX_SHIFT;
				final byte v = (byte) (longv >>> shiftNum);
				if (v != EMPTY_VALUE) {
					return pos;
				}
				pos++;
			}
		}
		return ILLEGAL_IDX;
	}

	@Override
	public int getMaxPos() {
		int pos = 255;
		for (int i = (LONGS_USED - 1); i >= 0; i--) {
			final long longv = this.childIndex[i];
			if (longv == INIT_LONG_VALUE) {
				pos -= BYTES_PER_LONG;
				continue;
			} else {
				// the zeroth value is stored in the MSB, but because we are searching from high to low
				// across all bytes, we can avoid the "double negative" of starting at 7 and j-- to 0
				// and then shifting by (7-j)*8
				for (int j = 0; j < BYTES_PER_LONG; j++) {
					final byte v = (byte) (longv >>> (j << INDEX_SHIFT));
					if (v != EMPTY_VALUE) {
						return pos;
					}
					pos--;
				}
			}
		}
		return ILLEGAL_IDX;
	}

	@Override
	public int getNextSmallerPos(int pos) {
		if (pos == ILLEGAL_IDX) {
			pos = 256;
		}
		pos--;
		int i = pos >>> INDEX_SHIFT;
		for (; i >= 0 && i < LONGS_USED; i--) {
			final long longv = this.childIndex[i];
			if (longv == INIT_LONG_VALUE) {
				// skip over empty bytes
				pos -= Math.min(BYTES_PER_LONG, (pos & POS_MASK) + 1);
				continue;
			}
			// because we are starting potentially at non aligned location, we need to start at 7
			// (or less) and decrement to zero, and then unpack the long correctly.
			for (int j = pos & POS_MASK; j >= 0; j--) {
				final int shiftNum = (BYTES_PER_LONG - 1 - j) << INDEX_SHIFT;
				final byte v = (byte) (longv >>> shiftNum);
				if (v != EMPTY_VALUE) {
					return pos;
				}
				pos--;
			}
		}
		return ILLEGAL_IDX;
	}

	/**
	 * insert a child node into the node48 node with the key byte
	 *
	 * @param child the child node
	 * @param key   the key byte
	 * @return the node48 or an adaptive generated node256
	 */
	@Override
	@Nonnull
	protected BranchNode insert(@Nonnull Node child, byte key) {
		if (this.count < 48) {
			// insert leaf node into current node
			int pos = this.count;
			if (this.children[pos] != null) {
				pos = 0;
				while (this.children[pos] != null) {
					pos++;
				}
			}
			this.children[pos] = child;
			final int unsignedByte = Byte.toUnsignedInt(key);
			setOneByte(unsignedByte, (byte) pos, this.childIndex);
			this.count++;
			return this;
		} else {
			// grow to Node256
			final Node256 node256 = new Node256(this.prefixLength());
			int currentPos = ILLEGAL_IDX;
			while ((currentPos = this.getNextLargerPos(currentPos)) != ILLEGAL_IDX) {
				final Node childNode = this.getChild(currentPos);
				node256.children[currentPos] = childNode;
				Node256.setBit((byte) currentPos, node256.bitmapMask);
			}
			node256.count = this.count;
			copyPrefix(this, node256);
			return node256.insert(child, key);
		}
	}

	@Override
	@Nonnull
	public Node remove(int pos) {
		final byte idx = childrenIdx(pos, this.childIndex);
		setOneByte(pos, EMPTY_VALUE, this.childIndex);
		this.children[idx] = null;
		this.count--;
		if (this.count <= 12) {
			// shrink to node16
			final Node16 node16 = new Node16(this.prefixLength());
			int j = 0;
			final ByteBuffer byteBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
			int currentPos = ILLEGAL_IDX;
			while ((currentPos = getNextLargerPos(currentPos)) != ILLEGAL_IDX) {
				final Node child = getChild(currentPos);
				byteBuffer.put(j, (byte) currentPos);
				node16.children[j] = child;
				j++;
			}
			node16.firstV = byteBuffer.getLong(0);
			node16.secondV = byteBuffer.getLong(8);
			node16.count = (short) j;
			copyPrefix(this, node16);
			return node16;
		}
		return this;
	}

	@Override
	public void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException {
		for (int i = 0; i < LONGS_USED; i++) {
			final long longv = this.childIndex[i];
			dataOutput.writeLong(Long.reverseBytes(longv));
		}
	}

	@Override
	public void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final LongBuffer longBuffer = byteBuffer.asLongBuffer();
		longBuffer.put(this.childIndex);
		byteBuffer.position(byteBuffer.position() + LONGS_USED * BYTES_PER_LONG);
	}

	@Override
	public void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException {
		for (int i = 0; i < LONGS_USED; i++) {
			this.childIndex[i] = Long.reverseBytes(dataInput.readLong());
		}
	}

	@Override
	public void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final LongBuffer longBuffer = byteBuffer.asLongBuffer();
		longBuffer.get(this.childIndex);
		byteBuffer.position(byteBuffer.position() + LONGS_USED * BYTES_PER_LONG);
	}

	@Override
	public int serializeNodeBodySizeInBytes() {
		return LONGS_USED * BYTES_PER_LONG;
	}

	@Override
	void replaceChildren(@Nonnull Node[] children) {
		int step = 0;
		for (int i = 0; i < LONGS_USED; i++) {
			long longv = Long.reverseBytes(this.childIndex[i]);
			if (longv != INIT_LONG_VALUE) {
				for (int j = 0; j < BYTES_PER_LONG; j++) {
					final long currentByte = longv & 0xFF;
					if (currentByte != 0xFF) {
						this.children[(int) currentByte] = children[step];
						step++;
					}
					longv >>>= 8;
				}
			}
		}
	}

	/**
	 * Reads the child-slot index stored for key byte `pos` out of the packed `childIndex`.
	 *
	 * @param pos        the key byte (0..255) to look up
	 * @param childIndex the packed 256-entry index
	 * @return the child array slot (0..47), or {@link #EMPTY_VALUE} if the key byte is absent
	 */
	private static byte childrenIdx(int pos, @Nonnull long[] childIndex) {
		final int longPos = pos >>> INDEX_SHIFT;
		final int bytePos = pos & POS_MASK;
		final long longV = childIndex[longPos];
		return (byte) ((longV) >>> ((BYTES_PER_LONG - 1 - bytePos) << INDEX_SHIFT));
	}

	/**
	 * Writes child-slot index `v` for key byte `pos` into the packed `childIndex`, leaving the other
	 * seven entries of the affected word untouched.
	 *
	 * @param pos        the key byte (0..255) to set
	 * @param v          the child array slot to store, or {@link #EMPTY_VALUE} to clear it
	 * @param childIndex the packed 256-entry index to mutate
	 */
	static void setOneByte(int pos, byte v, @Nonnull long[] childIndex) {
		final int longPos = pos >>> INDEX_SHIFT;
		final int bytePos = pos & POS_MASK;
		final int shift = (BYTES_PER_LONG - 1 - bytePos) << INDEX_SHIFT;
		final long preVal = childIndex[longPos];
		final long newVal = (preVal & ~(0xFFL << shift)) | (Byte.toUnsignedLong(v) << shift);
		childIndex[longPos] = newVal;
	}
}
