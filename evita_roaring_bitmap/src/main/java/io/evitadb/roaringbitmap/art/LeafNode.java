package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.longlong.LongUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.annotation.Nonnull;

/**
 * Terminal node of the Adaptive Radix Tree: it maps one fully expanded 48-bit key to the index of
 * the Roaring container that holds the low 16 bits of every long sharing that high prefix.
 *
 * The 48-bit key is stored split across a 32-bit `int` and a 16-bit `char` rather than a `long` so
 * that the {@link Node} header need not carry a prefix for leaves and the two halves serialize with
 * their natural widths. The tree uses lazy path expansion, so a leaf materializes the whole key only
 * once it is actually created.
 */
public class LeafNode extends Node {

	/**
	 * Top 32 bits of the 48-bit key.
	 */
	private int keyHigh;
	/**
	 * Low 16 bits of the 48-bit key, held as a `char` for unsigned semantics.
	 */
	private char keyLow;
	/**
	 * Index of the Roaring container this key resolves to, into the owning bitmap's container store.
	 */
	long containerIdx;
	/**
	 * Byte width of a leaf key on the wire: the high 48 bits, i.e. 6 bytes.
	 */
	public static final int LEAF_NODE_KEY_LENGTH_IN_BYTES = 6;

	/**
	 * constructor
	 *
	 * @param key          the 48 bit
	 * @param containerIdx the corresponding container index
	 */
	public LeafNode(@Nonnull byte[] key, long containerIdx) {
		super();
		setKeyFromShifted(LongUtils.fromKey(key));
		this.containerIdx = containerIdx;
	}

	/**
	 * constructor
	 *
	 * @param key          a long value,only the high 48 bit is valuable
	 * @param containerIdx the corresponding container index
	 */
	public LeafNode(long key, long containerIdx) {
		super();
		setKeyFromShifted(key);
		this.containerIdx = containerIdx;
	}

	@Override
	@Nonnull
	public LeafNode clone() {
		return new LeafNode(getKey() << 16, this.containerIdx);
	}

	@Override
	public void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException {
		dataOutput.writeInt(this.keyHigh);
		dataOutput.writeShort(this.keyLow);
		dataOutput.writeLong(Long.reverseBytes(this.containerIdx));
	}

	@Override
	public void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		if (byteBuffer.order() == ByteOrder.BIG_ENDIAN) {
			byteBuffer.putInt(this.keyHigh);
			byteBuffer.putChar(this.keyLow);
		} else {
			byteBuffer.putInt(Integer.reverseBytes(this.keyHigh));
			byteBuffer.putChar(Character.reverseBytes(this.keyLow));
		}
		byteBuffer.putLong(this.containerIdx);
	}

	@Override
	public void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException {
		this.keyHigh = dataInput.readInt();
		this.keyLow = dataInput.readChar();
		this.containerIdx = Long.reverseBytes(dataInput.readLong());
	}

	@Override
	public void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException {
		if (byteBuffer.order() == ByteOrder.BIG_ENDIAN) {
			this.keyHigh = byteBuffer.getInt();
			this.keyLow = byteBuffer.getChar();
		} else {
			this.keyHigh = Integer.reverseBytes(byteBuffer.getInt());
			this.keyLow = Character.reverseBytes(byteBuffer.getChar());
		}
		this.containerIdx = byteBuffer.getLong();
	}

	@Override
	public int serializeNodeBodySizeInBytes() {
		return LEAF_NODE_KEY_LENGTH_IN_BYTES + 8;
	}

	public long getContainerIdx() {
		return this.containerIdx;
	}

	/**
	 * The 48-bit key as its big-endian 6-byte form, the shape used to walk the tree byte by byte.
	 *
	 * @return the high 6 bytes of the key
	 */
	@Nonnull
	public byte[] getKeyBytes() {
		return LongUtils.highPart(getKey() << 16);
	}

	/**
	 * Reassembles the 48-bit key from its split `int`/`char` halves, right-aligned in the returned
	 * `long` (the low 16 bits are zero).
	 *
	 * @return the 48-bit key value
	 */
	public long getKey() {
		return (((long) this.keyHigh) & 0xFFFFFFFFL) << 16 | (((long) this.keyLow) & 0xFFFFL);
	}

	/**
	 * Sets the key from a long value, only the high 48 bits are used.
	 *
	 * @param key the long value representing the key
	 */
	private void setKeyFromShifted(long key) {
		this.keyHigh = (int) (key >> 32);
		this.keyLow = (char) (key >> 16);
	}

	@Override
	protected void serializeHeader(@Nonnull DataOutput dataOutput) throws IOException {
		// first byte: node type
		dataOutput.writeByte((byte) NodeType.LEAF_NODE.ordinal());
		// non null object count
		dataOutput.writeShort(0);
		dataOutput.writeByte(0);
	}

	@Override
	protected void serializeHeader(@Nonnull ByteBuffer byteBuffer) throws IOException {
		byteBuffer.put((byte) NodeType.LEAF_NODE.ordinal());
		byteBuffer.putShort((short) 0);
		byteBuffer.put((byte) 0);
	}

	@Override
	@Nonnull
	public String toString() {
		return "LeafNode{"
			+ "key="
			+ Long.toHexString(getKey())
			+ ", containerIdx="
			+ this.containerIdx
			+ '}';
	}
}
