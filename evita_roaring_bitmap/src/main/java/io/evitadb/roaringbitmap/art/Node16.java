package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.longlong.LongUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Second inner-node size of the Adaptive Radix Tree, holding up to 16 children.
 *
 * The up to 16 key bytes are packed into two `long`s ({@link #firstV}, {@link #secondV}), eight
 * bytes each, kept in ascending unsigned order so lookups run as a binary search — split across the
 * two words once more than eight keys are present. Overflow promotes to a {@link Node48} via
 * {@link #insert}; shrinking past 3 children demotes back to a {@link Node4} via {@link #remove}.
 */
public class Node16 extends BranchNode {

	/**
	 * First eight key bytes packed big-endian into a `long`, ascending unsigned.
	 */
	long firstV = 0L;
	/**
	 * Key bytes 8..15 packed big-endian into a `long`, ascending unsigned; used only past 8 keys.
	 */
	long secondV = 0L;
	/**
	 * Child slots, index-aligned with the key bytes across {@link #firstV} then {@link #secondV}.
	 */
	@Nonnull Node[] children = new Node[16];

	public Node16(int compressionLength) {
		super(compressionLength);
	}

	@Override
	@Nonnull
	protected Node16 clone() {
		final Node16 clone = new Node16(this.prefixLength());
		clone.firstV = this.firstV;
		clone.secondV = this.secondV;
		postClone(clone, this.children, clone.children);
		return clone;
	}

	@Override
	@Nonnull
	protected NodeType nodeType() {
		return NodeType.NODE16;
	}

	@Override
	public int getChildPos(byte k) {
		final byte[] firstBytes = LongUtils.toBDBytes(this.firstV);
		if (this.count <= 8) {
			return binarySearch(firstBytes, 0, this.count, k);
		} else {
			int pos = binarySearch(firstBytes, 0, 8, k);
			if (pos != ILLEGAL_IDX) {
				return pos;
			} else {
				final byte[] secondBytes = LongUtils.toBDBytes(this.secondV);
				pos = binarySearch(secondBytes, 0, (this.count - 8), k);
				if (pos != ILLEGAL_IDX) {
					return 8 + pos;
				} else {
					return ILLEGAL_IDX;
				}
			}
		}
	}

	@Override
	@Nullable
	public Node getChildAtKey(byte key) {
		final int pos = getChildPos(key);
		return (pos != ILLEGAL_IDX) ? this.children[pos] : null;
	}

	@Override
	@Nonnull
	public SearchResult getNearestChildPos(byte k) {
		final byte[] firstBytes = LongUtils.toBDBytes(this.firstV);
		if (this.count <= 8) {
			return binarySearchWithResult(firstBytes, 0, this.count, k);
		} else {
			final SearchResult firstResult = binarySearchWithResult(firstBytes, 0, 8, k);
			// given the values are "in order" if we found a match or a value larger than
			// the target we are done.
			if (firstResult.outcome == SearchResult.Outcome.FOUND || firstResult.hasNextLargerPos()) {
				return firstResult;
			} else {
				final byte[] secondBytes = LongUtils.toBDBytes(this.secondV);
				final SearchResult secondResult = binarySearchWithResult(secondBytes, 0, (this.count - 8), k);

				switch (secondResult.outcome) {
					case FOUND:
						return SearchResult.found(8 + secondResult.getKeyPos());
					case NOT_FOUND:
						int lowPos = secondResult.getNextSmallerPos();
						int highPos = secondResult.getNextLargerPos();
						// don't map -1 into the legal range by adding 8!
						if (lowPos >= 0) {
							lowPos += 8;
						}
						if (highPos >= 0) {
							highPos += 8;
						}

						if (firstResult.hasNextLargerPos() == false
							&& secondResult.hasNextSmallerPos() == false) {
							// this happens when the result is in the gap of the two ranges, the correct
							// "smaller value" is that of first result.
							lowPos = firstResult.getNextSmallerPos();
						}

						return SearchResult.notFound(lowPos, highPos);

					default:
						throw new IllegalStateException("There only two possible search outcomes");
				}
			}
		}
	}

	@Override
	public byte getChildKey(int pos) {
		int posInLong;
		if (pos <= 7) {
			posInLong = pos;
			final byte[] firstBytes = LongUtils.toBDBytes(this.firstV);
			return firstBytes[posInLong];
		} else {
			posInLong = pos - 8;
			final byte[] secondBytes = LongUtils.toBDBytes(this.secondV);
			return secondBytes[posInLong];
		}
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
	 * insert a child into the node with the key byte
	 *
	 * @param child the child node to be inserted
	 * @param key   the key byte
	 * @return the adaptive changed node of the parent node16
	 */
	@Override
	@Nonnull
	protected BranchNode insert(@Nonnull Node child, byte key) {
		if (this.count < 8) {
			// first
			final byte[] bytes = LongUtils.toBDBytes(this.firstV);
			bytes[this.count] = key;
			this.children[this.count] = child;
			sortSmallByteArray(bytes, this.children, 0, this.count);
			this.count++;
			this.firstV = LongUtils.fromBDBytes(bytes);
			return this;
		} else if (this.count < 16) {
			// second
			final ByteBuffer byteBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
			byteBuffer.putLong(this.firstV);
			byteBuffer.putLong(this.secondV);
			byteBuffer.put(this.count, key);
			this.children[this.count] = child;
			sortSmallByteArray(byteBuffer.array(), this.children, 0, this.count);
			this.count++;
			this.firstV = byteBuffer.getLong(0);
			this.secondV = byteBuffer.getLong(8);
			return this;
		} else {
			final Node48 node48 = new Node48(this.prefixLength());
			for (int i = 0; i < 8; i++) {
				final int unsignedIdx = Byte.toUnsignedInt((byte) (this.firstV >>> ((7 - i) << 3)));
				// i won't be beyond 48
				Node48.setOneByte(unsignedIdx, (byte) i, node48.childIndex);
				node48.children[i] = this.children[i];
			}
			final byte[] secondBytes = LongUtils.toBDBytes(this.secondV);
			for (int i = 8; i < this.count; i++) {
				final byte v = secondBytes[i - 8];
				final int unsignedIdx = Byte.toUnsignedInt(v);
				// i won't be beyond 48
				Node48.setOneByte(unsignedIdx, (byte) i, node48.childIndex);
				node48.children[i] = this.children[i];
			}
			copyPrefix(this, node48);
			node48.count = this.count;
			return node48.insert(child, key);
		}
	}

	@Override
	@Nonnull
	public Node remove(int pos) {
		this.children[pos] = null;
		final ByteBuffer byteBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
		final byte[] bytes = byteBuffer.putLong(this.firstV).putLong(this.secondV).array();
		System.arraycopy(bytes, pos + 1, bytes, pos, (16 - pos - 1));
		System.arraycopy(this.children, pos + 1, this.children, pos, (16 - pos - 1));
		this.firstV = byteBuffer.getLong(0);
		this.secondV = byteBuffer.getLong(8);
		this.count--;
		if (this.count <= 3) {
			// shrink to node4
			final Node4 node4 = new Node4(prefixLength());
			// copy the keys
			node4.key = (int) (this.firstV >> 32);
			System.arraycopy(this.children, 0, node4.children, 0, this.count);
			node4.count = this.count;
			copyPrefix(this, node4);
			return node4;
		}
		return this;
	}

	@Override
	public void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException {
		// little endian
		dataOutput.writeLong(Long.reverseBytes(this.firstV));
		dataOutput.writeLong(Long.reverseBytes(this.secondV));
	}

	@Override
	public void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		byteBuffer.putLong(this.firstV);
		byteBuffer.putLong(this.secondV);
	}

	@Override
	public void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException {
		this.firstV = Long.reverseBytes(dataInput.readLong());
		this.secondV = Long.reverseBytes(dataInput.readLong());
	}

	@Override
	public void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		this.firstV = byteBuffer.getLong();
		this.secondV = byteBuffer.getLong();
	}

	@Override
	public int serializeNodeBodySizeInBytes() {
		return 16;
	}

	@Override
	public void replaceChildren(@Nonnull Node[] children) {
		System.arraycopy(children, 0, this.children, 0, this.count);
	}
}
