package io.evitadb.roaringbitmap.art;

import static java.lang.Long.numberOfTrailingZeros;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Largest inner node of the Adaptive Radix Tree: a full 256-way fan-out where every possible key
 * byte addresses a child slot directly, so lookup and update are a single array access with no
 * indirection.
 *
 * Because the pointer array is sparse, an accompanying 256-bit occupancy {@link #bitmapMask} tracks
 * which slots are populated, letting ordered traversal ({@link #getMinPos},
 * {@link #getNextLargerPos} and their reverse) skip empty runs with word-level
 * {@code numberOfTrailingZeros}/{@code numberOfLeadingZeros}. This is the top of the growth ladder;
 * {@link #remove} demotes back to a {@link Node48} once occupancy drops far enough.
 */
public class Node256 extends BranchNode {

	/**
	 * Child slots indexed directly by unsigned key byte; a `null` slot means that key byte is absent.
	 */
	@Nonnull Node[] children = new Node[256];
	/**
	 * 256-bit occupancy map (bit `k` set iff `children[k]` is present), enabling fast ordered scans.
	 */
	@Nonnull long[] bitmapMask = new long[4];
	/**
	 * All-ones `long` used to build the shift masks that clear already-scanned bits.
	 */
	private static final long LONG_MASK = 0xffffffffffffffffL;

	public Node256(int compressedPrefixSize) {
		super(compressedPrefixSize);
	}

	@Override
	@Nonnull
	protected Node256 clone() {
		final Node256 clone = new Node256(this.prefixLength());
		System.arraycopy(this.bitmapMask, 0, clone.bitmapMask, 0, this.bitmapMask.length);
		postClone(clone, this.children, clone.children);
		return clone;
	}

	@Override
	@Nonnull
	protected NodeType nodeType() {
		return NodeType.NODE256;
	}

	@Override
	public int getChildPos(byte k) {
		final int pos = Byte.toUnsignedInt(k);
		if (this.children[pos] != null) {
			return pos;
		}
		return ILLEGAL_IDX;
	}

	@Override
	@Nullable
	public Node getChildAtKey(byte key) {
		return this.children[Byte.toUnsignedInt(key)];
	}

	@Override
	@Nonnull
	public SearchResult getNearestChildPos(byte k) {
		final int pos = Byte.toUnsignedInt(k);
		if (this.children[pos] != null) {
			return SearchResult.found(pos);
		}
		return SearchResult.notFound(getNextSmallerPos(pos), getNextLargerPos(pos));
	}

	@Override
	public byte getChildKey(int pos) {
		return (byte) pos;
	}

	@Override
	@Nonnull
	public Node getChild(int pos) {
		return this.children[pos];
	}

	@Override
	public void replaceNode(int pos, @Nonnull Node freshOne) {
		this.children[pos] = freshOne;
	}

	@Override
	public int getMinPos() {
		for (int i = 0; i < 4; i++) {
			final long longVal = this.bitmapMask[i];
			if (longVal == 0) {
				continue;
			}
			final int v = Long.numberOfTrailingZeros(longVal);
			return i * 64 + v;
		}
		return ILLEGAL_IDX;
	}

	@Override
	public int getNextLargerPos(int pos) {
		if (pos == ILLEGAL_IDX) {
			pos = 0;
		} else {
			pos++;
		}
		int longPos = pos >> 6;
		if (longPos >= 4) {
			return ILLEGAL_IDX;
		}
		long longVal = this.bitmapMask[longPos] & (LONG_MASK << pos);
		while (true) {
			if (longVal != 0) {
				return (longPos * 64) + Long.numberOfTrailingZeros(longVal);
			}
			if (++longPos == 4) {
				return ILLEGAL_IDX;
			}
			longVal = this.bitmapMask[longPos];
		}
	}

	@Override
	public int getMaxPos() {
		for (int i = 3; i >= 0; i--) {
			final long longVal = this.bitmapMask[i];
			if (longVal == 0) {
				continue;
			}
			final int v = Long.numberOfLeadingZeros(longVal);
			return i * 64 + (63 - v);
		}
		return ILLEGAL_IDX;
	}

	@Override
	public int getNextSmallerPos(int pos) {
		if (pos == ILLEGAL_IDX) {
			pos = 256;
		}
		if (pos == 0) {
			return ILLEGAL_IDX;
		}
		pos--;
		int longPos = pos >>> 6;
		long longVal = this.bitmapMask[longPos] & (LONG_MASK >>> -(pos + 1));
		while (true) {
			if (longVal != 0) {
				return (longPos + 1) * 64 - 1 - Long.numberOfLeadingZeros(longVal);
			}
			if (longPos-- == 0) {
				return ILLEGAL_IDX;
			}
			longVal = this.bitmapMask[longPos];
		}
	}

	/**
	 * insert the child node into the node256 node with the key byte
	 *
	 * @param child the child node
	 * @param key   the key byte
	 * @return the node256 node
	 */
	@Override
	@Nonnull
	protected Node256 insert(@Nonnull Node child, byte key) {
		this.count++;
		final int i = Byte.toUnsignedInt(key);
		this.children[i] = child;
		setBit(key, this.bitmapMask);
		return this;
	}

	/**
	 * Marks the slot for the given key byte as occupied in the 256-bit `bitmapMask`. Shared with
	 * {@link Node48#insert} when it promotes into a Node256.
	 *
	 * @param key        the key byte whose occupancy bit to set
	 * @param bitmapMask the four-word occupancy map to mutate
	 */
	static void setBit(byte key, @Nonnull long[] bitmapMask) {
		final int i = Byte.toUnsignedInt(key);
		final int longIdx = i >>> 6;
		final long previous = bitmapMask[longIdx];
		final long newVal = previous | (1L << i);
		bitmapMask[longIdx] = newVal;
	}

	@Override
	@Nonnull
	public Node remove(int pos) {
		this.children[pos] = null;
		final int longPos = pos >>> 6;
		this.bitmapMask[longPos] &= ~(1L << pos);
		this.count--;
		if (this.count <= 36) {
			final Node48 node48 = new Node48(this.prefixLength());
			int j = 0;
			int currentPos = ILLEGAL_IDX;
			while ((currentPos = getNextLargerPos(currentPos)) != ILLEGAL_IDX) {
				final Node child = getChild(currentPos);
				node48.children[j] = child;
				Node48.setOneByte(currentPos, (byte) j, node48.childIndex);
				j++;
			}
			node48.count = (short) j;
			copyPrefix(this, node48);
			return node48;
		}
		return this;
	}

	@Override
	public void replaceChildren(@Nonnull Node[] children) {
		if (children.length == this.children.length) {
			// short circuit path
			this.children = children;
			return;
		}
		int offset = 0;
		int x = 0;
		for (long longv : this.bitmapMask) {
			int w = 0;
			while (longv != 0) {
				final int pos = x * 64 + numberOfTrailingZeros(longv);
				this.children[pos] = children[offset + w];
				longv &= (longv - 1);
				w++;
			}
			offset += w;
			x++;
		}
	}

	@Override
	public void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException {
		for (long longv : this.bitmapMask) {
			dataOutput.writeLong(Long.reverseBytes(longv));
		}
	}

	@Override
	public void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final LongBuffer longBuffer = byteBuffer.asLongBuffer();
		longBuffer.put(this.bitmapMask);
		byteBuffer.position(byteBuffer.position() + 4 * 8);
	}

	@Override
	public void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException {
		for (int i = 0; i < 4; i++) {
			final long longv = Long.reverseBytes(dataInput.readLong());
			this.bitmapMask[i] = longv;
		}
	}

	@Override
	public void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final LongBuffer longBuffer = byteBuffer.asLongBuffer();
		longBuffer.get(this.bitmapMask);
		byteBuffer.position(byteBuffer.position() + 4 * 8);
	}

	@Override
	public int serializeNodeBodySizeInBytes() {
		return 4 * 8;
	}
}
