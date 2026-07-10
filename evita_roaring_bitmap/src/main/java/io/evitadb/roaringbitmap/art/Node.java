package io.evitadb.roaringbitmap.art;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Common base for every node of the Adaptive Radix Tree: the inner {@link BranchNode} variants
 * ({@link Node4}, {@link Node16}, {@link Node48}, {@link Node256}) and the terminal
 * {@link LeafNode}.
 *
 * Its main responsibility is the serialization framework shared by all node kinds. Each node is
 * written as a fixed header (node-type tag, child count, compressed-prefix length and bytes)
 * followed by a type-specific body; the static {@link #deserialize} entry points read the header,
 * dispatch on the {@link NodeType} ordinal to allocate the matching concrete node, then fill in the
 * body. Both a stream ({@link DataInput}/{@link DataOutput}) and a random-access {@link ByteBuffer}
 * form are supported so the tree can be persisted to and mapped from disk.
 */
public abstract class Node {

	public Node() {
	}

	@Override
	@Nonnull
	protected abstract Node clone();

	/**
	 * Sorts a small child array in place by unsigned key byte using insertion sort, keeping each
	 * child aligned with its key.
	 *
	 * Insertion sort is preferred over a general-purpose sort because these arrays hold at most 16
	 * entries, where its lower constant factor and lack of allocation win.
	 *
	 * @param key      the key bytes, one per child, reordered in place to ascending unsigned order
	 * @param children the child nodes, moved in lock-step with their keys
	 * @param left     inclusive lower bound of the range to sort
	 * @param right    inclusive upper bound of the range to sort
	 * @return the same `key` array, now sorted
	 */
	@Nonnull
	protected static byte[] sortSmallByteArray(
		@Nonnull byte[] key, @Nonnull Node[] children, int left, int right) { // x
		for (int i = left, j = i; i < right; j = ++i) {
			final byte ai = key[i + 1];
			final Node child = children[i + 1];
			final int unsignedByteAi = Byte.toUnsignedInt(ai);
			while (unsignedByteAi < Byte.toUnsignedInt(key[j])) {
				key[j + 1] = key[j];
				children[j + 1] = children[j];
				if (j-- == left) {
					break;
				}
			}
			key[j + 1] = ai;
			children[j + 1] = child;
		}
		return key;
	}

	/**
	 * Writes this node (header followed by type-specific body) to the given stream.
	 *
	 * @param dataOutput the sink to write to
	 * @throws IOException if the underlying stream fails
	 */
	public void serialize(@Nonnull DataOutput dataOutput) throws IOException {
		serializeHeader(dataOutput);
		serializeNodeBody(dataOutput);
	}

	/**
	 * Writes this node (header followed by type-specific body) into the given buffer at its current
	 * position.
	 *
	 * @param byteBuffer the buffer to write into
	 * @throws IOException if writing fails
	 */
	public void serialize(@Nonnull ByteBuffer byteBuffer) throws IOException {
		serializeHeader(byteBuffer);
		serializeNodeBody(byteBuffer);
	}

	/**
	 * Total number of bytes {@link #serialize} will emit for this node, header plus body. Lets callers
	 * size a buffer exactly before writing.
	 *
	 * @return the size in bytes
	 */
	public int serializeSizeInBytes() {
		int size = 0;
		size += serializeHeaderSizeInBytes();
		size += serializeNodeBodySizeInBytes();
		return size;
	}

	/**
	 * Reads a node back from a stream: decodes the header, allocates the concrete subtype indicated by
	 * the {@link NodeType} tag, then reads its body.
	 *
	 * @param dataInput the input byte stream
	 * @return the reconstructed node, or `null` if the tag byte matches no known node type
	 * @throws IOException if the underlying stream fails
	 */
	@Nullable
	public static Node deserialize(@Nonnull DataInput dataInput) throws IOException {
		final Node node = deserializeHeader(dataInput);
		if (node != null) {
			node.deserializeNodeBody(dataInput);
			return node;
		}
		return null;
	}

	/**
	 * Buffer counterpart to {@link #deserialize(DataInput)}: reads a node starting at the buffer's
	 * current position.
	 *
	 * @param byteBuffer the buffer to read from
	 * @return the reconstructed node, or `null` if the tag byte matches no known node type
	 * @throws IOException if reading fails
	 */
	@Nullable
	public static Node deserialize(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final Node node = deserializeHeader(byteBuffer);
		if (node != null) {
			node.deserializeNodeBody(byteBuffer);
			return node;
		}
		return null;
	}

	/**
	 * serialize the node's body content
	 *
	 * @param dataOutput the DataOutput
	 * @throws IOException exception indicates serialization errors
	 */
	abstract void serializeNodeBody(@Nonnull DataOutput dataOutput) throws IOException;

	/**
	 * serialize the node's body content
	 *
	 * @param byteBuffer the ByteBuffer
	 * @throws IOException exception indicates serialization errors
	 */
	abstract void serializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException;

	/**
	 * deserialize the node's body content
	 *
	 * @param dataInput the DataInput
	 * @throws IOException exception indicates deserialization errors
	 */
	abstract void deserializeNodeBody(@Nonnull DataInput dataInput) throws IOException;

	/**
	 * deserialize the node's body content
	 *
	 * @param byteBuffer the ByteBuffer
	 * @throws IOException exception indicates deserialization errors
	 */
	abstract void deserializeNodeBody(@Nonnull ByteBuffer byteBuffer) throws IOException;

	/**
	 * the serialized size except the common node header part
	 *
	 * @return the size in bytes
	 */
	public abstract int serializeNodeBodySizeInBytes();

	/**
	 * Writes the common node header to a stream: node-type tag, child count and compressed prefix.
	 * {@link BranchNode} appends the prefix bytes; {@link LeafNode} carries no prefix.
	 *
	 * @param dataOutput the sink to write to
	 * @throws IOException if the underlying stream fails
	 */
	protected abstract void serializeHeader(@Nonnull DataOutput dataOutput) throws IOException;

	/**
	 * Buffer counterpart to {@link #serializeHeader(DataOutput)}.
	 *
	 * @param byteBuffer the buffer to write into
	 * @throws IOException if writing fails
	 */
	protected abstract void serializeHeader(@Nonnull ByteBuffer byteBuffer) throws IOException;

	/**
	 * Byte size of the fixed header: one byte node type, two bytes child count, one byte prefix
	 * length. {@link BranchNode} overrides this to add the variable prefix bytes.
	 *
	 * @return the header size in bytes
	 */
	protected int serializeHeaderSizeInBytes() {
		return 1 + 2 + 1;
	}

	/**
	 * Reads the shared header and allocates the concrete node the {@link NodeType} tag selects,
	 * populating its prefix and child count but not its body. Returns `null` for an unrecognized tag.
	 */
	@Nullable
	private static Node deserializeHeader(@Nonnull DataInput dataInput) throws IOException {
		final int nodeTypeOrdinal = dataInput.readByte();
		final short count = Short.reverseBytes(dataInput.readShort());
		final byte prefixLength = dataInput.readByte();
		final byte[] prefix;
		if (prefixLength == 0) {
			prefix = Art.EMPTY_BYTES;
		} else {
			prefix = new byte[prefixLength];
			dataInput.readFully(prefix);
		}
		if (nodeTypeOrdinal == NodeType.NODE4.ordinal()) {
			final Node4 node4 = new Node4(prefixLength);
			node4.prefix = prefix;
			node4.count = count;
			return node4;
		}
		if (nodeTypeOrdinal == NodeType.NODE16.ordinal()) {
			final Node16 node16 = new Node16(prefixLength);
			node16.prefix = prefix;
			node16.count = count;
			return node16;
		}
		if (nodeTypeOrdinal == NodeType.NODE48.ordinal()) {
			final Node48 node48 = new Node48(prefixLength);
			node48.prefix = prefix;
			node48.count = count;
			return node48;
		}
		if (nodeTypeOrdinal == NodeType.NODE256.ordinal()) {
			final Node256 node256 = new Node256(prefixLength);
			node256.prefix = prefix;
			node256.count = count;
			return node256;
		}
		if (nodeTypeOrdinal == NodeType.LEAF_NODE.ordinal()) {
			return new LeafNode(0L, 0);
		}
		return null;
	}

	/**
	 * Buffer counterpart to {@link #deserializeHeader(DataInput)}.
	 */
	@Nullable
	private static Node deserializeHeader(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final int nodeTypeOrdinal = byteBuffer.get();
		final short count = byteBuffer.getShort();
		final byte prefixLength = byteBuffer.get();
		final byte[] prefix;
		if (prefixLength == 0) {
			prefix = Art.EMPTY_BYTES;
		} else {
			prefix = new byte[prefixLength];
			byteBuffer.get(prefix);
		}
		if (nodeTypeOrdinal == NodeType.NODE4.ordinal()) {
			final Node4 node4 = new Node4(prefixLength);
			node4.prefix = prefix;
			node4.count = count;
			return node4;
		}
		if (nodeTypeOrdinal == NodeType.NODE16.ordinal()) {
			final Node16 node16 = new Node16(prefixLength);
			node16.prefix = prefix;
			node16.count = count;
			return node16;
		}
		if (nodeTypeOrdinal == NodeType.NODE48.ordinal()) {
			final Node48 node48 = new Node48(prefixLength);
			node48.prefix = prefix;
			node48.count = count;
			return node48;
		}
		if (nodeTypeOrdinal == NodeType.NODE256.ordinal()) {
			final Node256 node256 = new Node256(prefixLength);
			node256.prefix = prefix;
			node256.count = count;
			return node256;
		}
		if (nodeTypeOrdinal == NodeType.LEAF_NODE.ordinal()) {
			return new LeafNode(0L, 0);
		}
		return null;
	}
}
