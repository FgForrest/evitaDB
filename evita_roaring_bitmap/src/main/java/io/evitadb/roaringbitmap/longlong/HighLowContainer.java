package io.evitadb.roaringbitmap.longlong;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

import io.evitadb.roaringbitmap.Container;
import io.evitadb.roaringbitmap.art.Art;
import io.evitadb.roaringbitmap.art.BranchNode;
import io.evitadb.roaringbitmap.art.ContainerIterator;
import io.evitadb.roaringbitmap.art.Containers;
import io.evitadb.roaringbitmap.art.KeyIterator;
import io.evitadb.roaringbitmap.art.LeafNode;
import io.evitadb.roaringbitmap.art.LeafNodeIterator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ART-backed store mapping each 48-bit high key to the 16-bit {@link Container} holding the low bits
 * of the values that share that key — the core data structure behind
 * {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap}.
 *
 * Two structures cooperate:
 *
 * - `art` — an adaptive radix tree ({@link Art}) keyed by the 6-byte big-endian high key, whose
 * leaves store a `long` container index rather than the container itself.
 * - `containers` — a compact side store ({@link Containers}) that holds the actual `Container`
 * instances, addressed by the index the tree points to.
 *
 * Splitting the mapping this way keeps the tree small (it moves only `long` indices) and lets
 * containers be iterated and serialized independently. Because the high key is big-endian, the
 * tree's byte-dictionary ordering matches the unsigned ordering of the 64-bit values.
 */
public class HighLowContainer {

	/**
	 * Adaptive radix tree mapping each 6-byte big-endian high key to a container index.
	 */
	@Nonnull private Art art;
	/**
	 * Side store of the low-16 containers, addressed by the index held in the ART leaves.
	 */
	@Nonnull private Containers containers;
	/**
	 * Serialization marker written when the store holds no keys.
	 */
	private static final byte EMPTY_TAG = 0;
	/**
	 * Serialization marker written when the store holds at least one key.
	 */
	private static final byte NOT_EMPTY_TAG = 1;

	public HighLowContainer() {
		this.art = new Art();
		this.containers = new Containers();
	}

	/**
	 * Deep-copies the store: both the ART tree and the container side store are cloned, so the copy
	 * can be mutated without affecting this instance.
	 */
	@Nonnull
	@Override
	// deep copy via a fresh store with cloned art + containers; shallow Object.clone() is inappropriate here
	@SuppressWarnings("CloneDoesntCallSuperClone")
	public HighLowContainer clone() {
		final HighLowContainer cloned = new HighLowContainer();
		cloned.art = this.art.clone();
		cloned.containers = this.containers.clone();
		return cloned;
	}

	/**
	 * Resolves the low-16 container stored at the given index in the {@link Containers} side store.
	 *
	 * @param containerIdx an index previously handed out by {@link #put} or carried by a
	 *                     {@link ContainerWithIndex}
	 * @return the container occupying that slot
	 */
	@Nonnull
	public Container getContainer(long containerIdx) {
		return this.containers.getContainer(containerIdx);
	}

	/**
	 * Looks up the container for a 48-bit high key given in its 6-byte big-endian form.
	 *
	 * @param highPart the 48 bit key array
	 * @return the matching container paired with its slot index, or `null` if no such key is present
	 */
	@Nullable
	public ContainerWithIndex searchContainer(@Nonnull byte[] highPart) {
		final long containerIdx = this.art.findByKey(highPart);
		if (containerIdx < 0) {
			return null;
		} else {
			final Container container = this.containers.getContainer(containerIdx);
			return new ContainerWithIndex(container, containerIdx);
		}
	}

	/**
	 * Looks up the container for a 48-bit high key given as a right-aligned `long` (low 48 bits).
	 *
	 * @param highPart the 48 bit key
	 * @return the matching container paired with its slot index, or `null` if no such key is present
	 */
	@Nullable
	public ContainerWithIndex searchContainer(long highPart) {
		final long containerIdx = this.art.findByKey(highPart);
		if (containerIdx < 0) {
			return null;
		} else {
			final Container container = this.containers.getContainer(containerIdx);
			return new ContainerWithIndex(container, containerIdx);
		}
	}

	/**
	 * Registers a new high key: appends `container` to the side store and maps `highPart` to its
	 * index in the ART tree. Intended for a key not yet present — an existing key's container is
	 * updated in place through {@link #replaceContainer(long, Container)} instead.
	 *
	 * @param highPart  the 48 bit key, in 6-byte big-endian form
	 * @param container the container holding the low bits for that key
	 */
	public void put(@Nonnull byte[] highPart, @Nonnull Container container) {
		final long containerIdx = this.containers.addContainer(container);
		this.art.insert(highPart, containerIdx);
	}

	/**
	 * Removes the mapping for the given 48-bit key. The container slot is freed only when the key was
	 * actually present — {@link Art#remove(byte[])} reports {@link BranchNode#ILLEGAL_IDX} on a miss,
	 * which is then a no-op.
	 *
	 * @param highPart the 48 bit key, in 6-byte big-endian form
	 */
	public void remove(@Nonnull byte[] highPart) {
		final long containerIdx = this.art.remove(highPart);
		if (containerIdx != BranchNode.ILLEGAL_IDX) {
			this.containers.remove(containerIdx);
		}
	}

	/**
	 * get a container iterator
	 *
	 * @return a container iterator
	 */
	@Nonnull
	public ContainerIterator containerIterator() {
		return this.containers.iterator();
	}

	/**
	 * get a key iterator
	 *
	 * @return a key iterator
	 */
	@Nonnull
	public KeyIterator highKeyIterator() {
		return this.art.iterator(this.containers);
	}

	/**
	 * @param reverse true ascending order, false: descending order
	 * @return the leaf node iterator
	 */
	@Nonnull
	public LeafNodeIterator highKeyLeafNodeIterator(boolean reverse) {
		return this.art.leafNodeIterator(reverse, this.containers);
	}

	/**
	 * Leaf-node iterator that starts from the given high-key bound rather than from an end of the tree.
	 *
	 * @param bound   the high key to start iterating from
	 * @param reverse iteration direction, matching {@link #highKeyLeafNodeIterator(boolean)}
	 * @return the leaf node iterator
	 */
	@Nonnull
	public LeafNodeIterator highKeyLeafNodeIteratorFrom(long bound, boolean reverse) {
		return this.art.leafNodeIteratorFrom(bound, reverse, this.containers);
	}

	/**
	 * replace the specified position one with a fresh container
	 *
	 * @param containerIdx the position of the container
	 * @param container    the fresh container
	 */
	public void replaceContainer(long containerIdx, @Nonnull Container container) {
		this.containers.replace(containerIdx, container);
	}

	/**
	 * whether it's empty
	 *
	 * @return true: empty,false: not empty
	 */
	public boolean isEmpty() {
		return this.art.isEmpty();
	}

	private void assertNonEmpty() {
		if (isEmpty()) {
			throw new NoSuchElementException("Empty " + this.getClass().getSimpleName());
		}
	}

	/**
	 * Gets the first value in the array
	 *
	 * @return the first value in the array
	 * @throws NoSuchElementException if empty
	 */
	public long first() {
		assertNonEmpty();

		final LeafNode firstNode = this.art.first();
		final long containerIdx = firstNode.getContainerIdx();
		final Container container = getContainer(containerIdx);
		final byte[] high = firstNode.getKeyBytes();
		final char low = (char) container.first();
		return LongUtils.toLong(high, low);
	}

	/**
	 * Gets the last value in the array
	 *
	 * @return the last value in the array
	 * @throws NoSuchElementException if empty
	 */
	public long last() {
		assertNonEmpty();

		final LeafNode lastNode = this.art.last();
		final long containerIdx = lastNode.getContainerIdx();
		final Container container = getContainer(containerIdx);
		final byte[] high = lastNode.getKeyBytes();
		final char low = (char) container.last();
		return LongUtils.toLong(high, low);
	}

	/**
	 * Compares two high keys by unsigned byte-dictionary order — the ordering the ART tree relies on,
	 * which coincides with the unsigned ordering of the underlying 64-bit values. A shorter array that
	 * is a prefix of the other sorts first; a `null` array sorts after any non-null one.
	 *
	 * @param a the first key, or `null`
	 * @param b the second key, or `null`
	 * @return a negative integer, zero, or a positive integer as `a` is less than, equal to, or
	 * greater than `b`
	 */
	public static int compareUnsigned(@Nullable byte[] a, @Nullable byte[] b) {
		if (a == null) {
			return b == null ? 0 : 1;
		}
		if (b == null) {
			return -1;
		}
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			final int aVal = a[i] & 0xff;
			final int bVal = b[i] & 0xff;
			if (aVal != bVal) {
				return Integer.compare(aVal, bVal);
			}
		}
		return Integer.compare(a.length, b.length);
	}

	/**
	 * Serializes the store into `buffer` using the portable little-endian layout: a single tag byte
	 * ({@code EMPTY_TAG} / {@code NOT_EMPTY_TAG}) followed, when non-empty, by the ART tree and the
	 * containers. A buffer whose byte order is not already little-endian is sliced into a
	 * little-endian view and the original position advanced to match.
	 *
	 * @param buffer the ByteBuffer should be large enough to hold the data
	 * @throws IOException indicate exception happened
	 */
	public void serialize(@Nonnull ByteBuffer buffer) throws IOException {
		final ByteBuffer byteBuffer =
			buffer.order() == LITTLE_ENDIAN ? buffer : buffer.slice().order(LITTLE_ENDIAN);
		if (this.art.isEmpty()) {
			byteBuffer.put(EMPTY_TAG);
			if (byteBuffer != buffer) {
				buffer.position(buffer.position() + byteBuffer.position());
			}
			return;
		} else {
			byteBuffer.put(NOT_EMPTY_TAG);
		}
		this.art.serializeArt(byteBuffer);
		this.containers.serialize(byteBuffer);
		if (byteBuffer != buffer) {
			buffer.position(buffer.position() + byteBuffer.position());
		}
	}

	/**
	 * Restores the store from the little-endian layout written by {@link #serialize(ByteBuffer)},
	 * discarding any current contents first. An {@code EMPTY_TAG} leaves the store empty.
	 *
	 * @param buffer the ByteBuffer
	 * @throws IOException indicate exception happened
	 */
	public void deserialize(@Nonnull ByteBuffer buffer) throws IOException {
		final ByteBuffer byteBuffer =
			buffer.order() == LITTLE_ENDIAN ? buffer : buffer.slice().order(LITTLE_ENDIAN);
		clear();
		final byte emptyTag = byteBuffer.get();
		if (emptyTag == EMPTY_TAG) {
			return;
		}
		this.art.deserializeArt(byteBuffer);
		this.containers.deserialize(byteBuffer);
	}

	/**
	 * serialized size in bytes
	 *
	 * @return the size in bytes
	 */
	public long serializedSizeInBytes() {
		long totalSize = 1L;
		if (this.art.isEmpty()) {
			return totalSize;
		}
		totalSize += this.art.serializeSizeInBytes();
		totalSize += this.containers.serializedSizeInBytes();
		return totalSize;
	}

	/**
	 * Serializes the store to a stream using the same tag-then-body layout as
	 * {@link #serialize(ByteBuffer)}: a single empty/non-empty tag byte followed, when non-empty, by
	 * the ART tree and the containers.
	 *
	 * @param dataOutput the output stream
	 * @throws IOException indicate the io exception happened
	 */
	public void serialize(@Nonnull DataOutput dataOutput) throws IOException {
		if (this.art.isEmpty()) {
			dataOutput.writeByte(EMPTY_TAG);
			return;
		} else {
			dataOutput.writeByte(NOT_EMPTY_TAG);
		}
		this.art.serializeArt(dataOutput);
		this.containers.serialize(dataOutput);
	}

	/**
	 * Restores the store from the stream written by {@link #serialize(DataOutput)}, discarding any
	 * current contents first. An {@code EMPTY_TAG} leaves the store empty.
	 *
	 * @param dataInput the input byte stream
	 * @throws IOException indicate the io exception happened
	 */
	public void deserialize(@Nonnull DataInput dataInput) throws IOException {
		clear();
		final byte emptyTag = dataInput.readByte();
		if (emptyTag == EMPTY_TAG) {
			return;
		}
		this.art.deserializeArt(dataInput);
		this.containers.deserialize(dataInput);
	}

	/**
	 * clear to be a empty fresh one
	 */
	public void clear() {
		this.art = new Art();
		this.containers = new Containers();
	}

	@Override
	// 'art'/'containers' are swapped only by clear(); hashCode is computed over the live contents, consistent with equals()
	@SuppressWarnings("NonFinalFieldReferencedInHashCode")
	public int hashCode() {
		int hashCode = 0;
		final KeyIterator keyIterator = highKeyIterator();
		while (keyIterator.hasNext()) {
			final byte[] key = keyIterator.next();
			int result = 1;
			for (final byte element : key) {
				result = 31 * result + element;
			}
			final long containerIdx = keyIterator.currentContainerIdx();
			final Container container = this.containers.getContainer(containerIdx);
			hashCode = 31 * hashCode + result + container.hashCode();
		}
		return hashCode;
	}

	@Override
	// 'art' is swapped only by clear(); equality compares the live contents, consistent with hashCode()
	@SuppressWarnings("NonFinalFieldReferenceInEquals")
	public boolean equals(Object object) {
		if (object instanceof HighLowContainer) {
			final HighLowContainer otherHighLowContainer = (HighLowContainer) object;
			if (this.art.getKeySize() != otherHighLowContainer.art.getKeySize()) {
				return false;
			}
			final KeyIterator thisKeyIte = this.highKeyIterator();
			while (thisKeyIte.hasNext()) {
				final byte[] thisHigh = thisKeyIte.next();
				final long containerIdx = thisKeyIte.currentContainerIdx();
				final Container thisContainer = this.getContainer(containerIdx);
				final ContainerWithIndex containerWithIndex = otherHighLowContainer.searchContainer(thisHigh);
				if (containerWithIndex == null) {
					return false;
				}
				final Container otherContainer = containerWithIndex.getContainer();
				if (!thisContainer.equals(otherContainer)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}
}
