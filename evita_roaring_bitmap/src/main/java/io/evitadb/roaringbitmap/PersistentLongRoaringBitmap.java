package io.evitadb.roaringbitmap;

import io.evitadb.roaringbitmap.art.ContainerIterator;
import io.evitadb.roaringbitmap.art.KeyIterator;
import io.evitadb.roaringbitmap.art.LeafNode;
import io.evitadb.roaringbitmap.art.LeafNodeIterator;
import io.evitadb.roaringbitmap.longlong.ContainerWithIndex;
import io.evitadb.roaringbitmap.longlong.HighLowContainer;
import io.evitadb.roaringbitmap.longlong.LongConsumerRelativeRangeAdapter;
import io.evitadb.roaringbitmap.longlong.LongUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A compressed, memory-frugal bitmap for sets of **unsigned 64-bit** longs — evitaDB's 64-bit
 * counterpart to {@link PersistentRoaringBitmap}. Because Java has no unsigned long, values are
 * ordered per {@link Long#compareUnsigned} / {@link Long#toUnsignedString}, so the ascending
 * sequence runs `0, 1, ..., Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1, ..., -1`.
 *
 * Each value is split into a 48-bit high key and a 16-bit low part. The high key selects a
 * {@link Container} holding that chunk's low-16-bit members; the containers live in an
 * Adaptive-Radix-Tree-backed {@link HighLowContainer} ({@link #highLowContainer}), keyed in
 * ascending unsigned order so every binary operation runs as a linear key merge. Containers pick
 * the densest of the array / bitmap / run encodings, so memory tracks the data shape rather than
 * the value range. This is the ART-based Roaring64 layout, distinct from the 32/32-split
 * `Roaring64NavigableMap`.
 *
 * Mutability and thread-safety: instances are mutable and **not** thread-safe; concurrent
 * modification (or modification concurrent with reads) must be guarded externally. The static
 * `or`/`and`/`xor`/`andNot` combinators leave their operands unchanged and deep-copy every
 * carried-over container, so — unlike the 32-bit twin — this variant performs no copy-on-write
 * container sharing; they are safe to call as long as the inputs are not concurrently modified.
 *
 * Credits: derived from the RoaringBitmap project
 * (https://github.com/RoaringBitmap/RoaringBitmap) by Daniel Lemire et al., Apache License 2.0.
 * Vendored into evitaDB and reshaped by FG Forrest, a.s. See the module `LICENSE`, `AUTHORS` and
 * `NOTICE` files.
 */
public class PersistentLongRoaringBitmap implements Externalizable, LongBitmapDataProvider {

	/**
	 * ART-backed sorted map from a value's 48-bit high key to the {@link Container} holding that
	 * chunk's low-16-bit members — the backbone of the bitmap. Kept in ascending unsigned key order
	 * so every binary operation runs as a linear key merge.
	 */
	@Nonnull private HighLowContainer highLowContainer;

	/**
	 * Creates an empty bitmap.
	 */
	public PersistentLongRoaringBitmap() {
		this.highLowContainer = new HighLowContainer();
	}

	/**
	 * Adds the 32-bit `x` as an unsigned long (zero-extended into the low 32 bits), so int-keyed
	 * data can be fed into a 64-bit bitmap without sign extension.
	 *
	 * @param x 32-bit value, treated as unsigned
	 */
	public void addInt(int x) {
		this.addLong(Util.toUnsignedLong(x));
	}

	/**
	 * Add the value to the container (set the value to "true"), whether it already appears or not.
	 *
	 * Java lacks native unsigned longs but the x argument is considered to be unsigned. Within
	 * bitmaps, numbers are ordered according to{@link Long#toUnsignedString}. We order the numbers
	 * like 0, 1, ..., 9223372036854775807, -9223372036854775808, -9223372036854775807,..., -1.
	 *
	 * @param x long value
	 */
	@Override
	public void addLong(long x) {
		final byte[] high = LongUtils.highPart(x);
		final char low = LongUtils.lowPart(x);
		final ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(high);
		if (containerWithIndex != null) {
			final Container container = containerWithIndex.getContainer();
			final Container freshOne = container.add(low);
			this.highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
		} else {
			final ArrayContainer arrayContainer = new ArrayContainer();
			arrayContainer.add(low);
			this.highLowContainer.put(high, arrayContainer);
		}
	}

	/**
	 * Returns the number of distinct integers added to the bitmap (e.g., number of bits set).
	 *
	 * @return the cardinality
	 */
	@Override
	public long getLongCardinality() {
		if (this.highLowContainer.isEmpty()) {
			return 0L;
		}
		final Iterator<Container> containerIterator = this.highLowContainer.containerIterator();
		long cardinality = 0L;
		while (containerIterator.hasNext()) {
			final Container container = containerIterator.next();
			cardinality += container.getCardinality();
		}
		return cardinality;
	}

	/**
	 * @return the cardinality as an int
	 * @throws UnsupportedOperationException if the cardinality does not fit in an int
	 */
	public int getIntCardinality() throws UnsupportedOperationException {
		final long cardinality = this.getLongCardinality();
		if (cardinality > Integer.MAX_VALUE) {
			// a cardinality that fits in an unsigned (but not signed) int is not yet handled here
			throw new UnsupportedOperationException(
				"Can not call .getIntCardinality as the cardinality is bigger than Integer.MAX_VALUE");
		}
		return (int) cardinality;
	}

	/**
	 * Return the jth value stored in this bitmap.
	 *
	 * @param j index of the value
	 * @return the value
	 * @throws IllegalArgumentException if j is out of the bounds of the bitmap cardinality
	 */
	@Override
	public long select(final long j) throws IllegalArgumentException {
		long left = j;
		final LeafNodeIterator leafNodeIterator = this.highLowContainer.highKeyLeafNodeIterator(false);
		while (leafNodeIterator.hasNext()) {
			final LeafNode leafNode = leafNodeIterator.next();
			final long containerIdx = leafNode.getContainerIdx();
			final Container container = this.highLowContainer.getContainer(containerIdx);
			final int card = container.getCardinality();
			if (left >= card) {
				left = left - card;
			} else {
				final byte[] high = leafNode.getKeyBytes();
				final int leftAsUnsignedInt = (int) left;
				final char low = container.select(leftAsUnsignedInt);
				return LongUtils.toLong(high, low);
			}
		}
		return this.throwSelectInvalidIndex(j);
	}

	private long throwSelectInvalidIndex(long j) {
		throw new IllegalArgumentException(
			"select " + j + " when the cardinality is " + this.getLongCardinality());
	}

	/**
	 * Get the first (smallest) integer in this PersistentRoaringBitmap,
	 * that is, returns the minimum of the set.
	 *
	 * @return the first (smallest) integer
	 * @throws NoSuchElementException if empty
	 */
	@Override
	public long first() {
		return this.highLowContainer.first();
	}

	/**
	 * Get the last (largest) integer in this PersistentRoaringBitmap,
	 * that is, returns the maximum of the set.
	 *
	 * @return the last (largest) integer
	 * @throws NoSuchElementException if empty
	 */
	@Override
	public long last() {
		return this.highLowContainer.last();
	}

	/**
	 * For better performance, consider the Use the {@link #forEach forEach} method.
	 *
	 * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
	 */
	@Nonnull
	public Iterator<Long> iterator() {
		final LongIterator it = this.getLongIterator();

		return new Iterator<>() {

			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public Long next() {
				return it.next();
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	/**
	 * Visits every set value in ascending unsigned order, recombining each 48-bit high key with its
	 * container's low 16 bits. Preferred over {@link #getLongIterator()} for full scans as it avoids
	 * per-value iterator bookkeeping.
	 *
	 * @param lc consumer invoked once per set value
	 */
	@Override
	public void forEach(@Nonnull final LongConsumer lc) {
		final KeyIterator keyIterator = this.highLowContainer.highKeyIterator();
		while (keyIterator.hasNext()) {
			final byte[] high = keyIterator.next();
			final long containerIdx = keyIterator.currentContainerIdx();
			final Container container = this.highLowContainer.getContainer(containerIdx);
			final PeekableCharIterator charIterator = container.getCharIterator();
			while (charIterator.hasNext()) {
				final char low = charIterator.next();
				final long v = LongUtils.toLong(high, low);
				lc.accept(v);
			}
		}
	}

	/**
	 * Consume presence information for all values in the range [start, start + length).
	 *
	 * @param start  Lower bound of values to consume.
	 * @param length Maximum number of values to consume.
	 * @param rrc    Code to be executed for each present or absent value.
	 */
	public void forAllInRange(long start, int length, @Nonnull final RelativeRangeConsumer rrc) {
		final LeafNodeIterator leafIterator =
			this.highLowContainer.highKeyLeafNodeIteratorFrom(start, false);
		if (!leafIterator.hasNext()) {
			rrc.acceptAllAbsent(0, length);
			return; // nothing else to do
		}
		final long end = start + length;
		final long endHigh = LongUtils.rightShiftHighPart(end);
		long filledUntil = start;

		LeafNode node = leafIterator.next();
		long high = node.getKey();
		while (high <= endHigh) {
			// fill missing values until start of container
			final long containerStart = LongUtils.toLong(high, (char) 0);
			if (filledUntil < containerStart) {
				rrc.acceptAllAbsent((int) (filledUntil - start), (int) (containerStart - start));
				filledUntil = containerStart;
			}
			// Inspect Container
			final long containerIdx = node.getContainerIdx();
			final Container container = this.highLowContainer.getContainer(containerIdx);
			final long containerEnd = LongUtils.toLong(high, Character.MAX_VALUE) + 1;
			final int containerRangeStartOffset = (int) (filledUntil - start);

			final boolean startInContainer = containerStart < start;
			final boolean endInContainer = end < containerEnd;

			if (startInContainer && endInContainer) {
				// Only part of the container is in range
				final char containerRangeStart = LongUtils.lowPart(start);
				final char containerRangeEnd = LongUtils.lowPart(end);
				container.forAllInRange(LongUtils.lowPart(start), LongUtils.lowPart(end), rrc);
				filledUntil += containerRangeEnd - containerRangeStart;
			} else if (startInContainer) { //  && !endInContainer
				// range begins within the container
				final char containerRangeStart = LongUtils.lowPart(start);
				container.forAllFrom(containerRangeStart, rrc);
				filledUntil += BitmapContainer.MAX_CAPACITY - containerRangeStart;
			} else if (endInContainer) { // && !startInContainer
				// range end within the container
				final char containerRangeEnd = LongUtils.lowPart(end);
				container.forAllUntil(containerRangeStartOffset, containerRangeEnd, rrc);
				filledUntil += containerRangeEnd;
			} else {
				container.forAll(containerRangeStartOffset, rrc);
				filledUntil += BitmapContainer.MAX_CAPACITY;
			}
			if (leafIterator.hasNext()) {
				node = leafIterator.next();
				high = node.getKey();
			} else {
				break;
			}
		}
		// next container (if any) is beyond the end, but there may be missing values in between
		if (filledUntil < end) {
			rrc.acceptAllAbsent((int) (filledUntil - start), length);
		}
	}

	/**
	 * Consume each value present in the range [start, start + length).
	 *
	 * @param start  Lower bound of values to consume.
	 * @param length Maximum number of values to consume.
	 * @param lc     Code to be executed for each present value.
	 */
	public void forEachInRange(long start, int length, @Nonnull final LongConsumer lc) {
		this.forAllInRange(start, length, new LongConsumerRelativeRangeAdapter(start, lc));
	}

	@Override
	public long rankLong(long id) {
		long result = 0;
		final long high = LongUtils.rightShiftHighPart(id);
		final byte[] highBytes = LongUtils.highPart(id);
		final char low = LongUtils.lowPart(id);
		final ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(highBytes);
		final KeyIterator keyIterator = this.highLowContainer.highKeyIterator();
		if (containerWithIndex == null) {
			while (keyIterator.hasNext()) {
				final long highKey = keyIterator.nextKey();
				if (highKey > high) {
					break;
				} else {
					final long containerIdx = keyIterator.currentContainerIdx();
					final Container container = this.highLowContainer.getContainer(containerIdx);
					result += container.getCardinality();
				}
			}
		} else {
			while (keyIterator.hasNext()) {
				final long key = keyIterator.nextKey();
				final long containerIdx = keyIterator.currentContainerIdx();
				final Container container = this.highLowContainer.getContainer(containerIdx);
				if (key == high) {
					result += container.rank(low);
					break;
				} else {
					result += container.getCardinality();
				}
			}
		}
		return result;
	}

	/**
	 * In-place bitwise OR (union) operation. The current bitmap is modified.
	 *
	 * @param x2 other bitmap
	 */
	public void or(@Nonnull final PersistentLongRoaringBitmap x2) {
		if (this == x2) {
			return;
		}
		final KeyIterator highIte2 = x2.highLowContainer.highKeyIterator();
		while (highIte2.hasNext()) {
			final byte[] high = highIte2.next();
			final long containerIdx = highIte2.currentContainerIdx();
			final Container container2 = x2.highLowContainer.getContainer(containerIdx);
			final ContainerWithIndex containerWithIdx = this.highLowContainer.searchContainer(high);
			if (containerWithIdx == null) {
				final Container container2clone = container2.clone();
				this.highLowContainer.put(high, container2clone);
			} else {
				final Container freshContainer = containerWithIdx.getContainer().ior(container2);
				this.highLowContainer.replaceContainer(containerWithIdx.getContainerIdx(), freshContainer);
			}
		}
	}

	/**
	 * Bitwise OR (union) operation. The provided bitmaps are *not* modified. This operation is
	 * thread-safe as long as the provided bitmaps remain unchanged.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return result of the operation
	 */
	@Nonnull
	public static PersistentLongRoaringBitmap or(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		final PersistentLongRoaringBitmap result = new PersistentLongRoaringBitmap();
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		final KeyIterator it2 = x2.highLowContainer.highKeyIterator();

		byte[] highKey1 = null, highKey2 = null;
		if (it1.hasNext()) {
			highKey1 = it1.next();
		}
		if (it2.hasNext()) {
			highKey2 = it2.next();
		}

		while (highKey1 != null || highKey2 != null) {
			final int compare = HighLowContainer.compareUnsigned(highKey1, highKey2);
			if (compare == 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				final Container orResult = container1.or(container2);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey1, "highKey1 is non-null when keys compare equal"), orResult);

				highKey1 = it1.hasNext() ? it1.next() : null;
				highKey2 = it2.hasNext() ? it2.next() : null;
			} else if (compare < 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey1, "highKey1 is non-null when it precedes highKey2"),
					container1.clone()
				);

				highKey1 = it1.hasNext() ? it1.next() : null;
			} else {
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey2, "highKey2 is non-null when it precedes highKey1"),
					container2.clone()
				);

				highKey2 = it2.hasNext() ? it2.next() : null;
			}
		}
		return result;
	}

	/**
	 * In-place bitwise XOR (symmetric difference) operation. The current bitmap is modified.
	 *
	 * @param x2 other bitmap
	 */
	public void xor(@Nonnull final PersistentLongRoaringBitmap x2) {
		if (x2 == this) {
			this.clear();
			return;
		}
		final KeyIterator keyIterator = x2.highLowContainer.highKeyIterator();
		while (keyIterator.hasNext()) {
			final byte[] high = keyIterator.next();
			final long containerIdx = keyIterator.currentContainerIdx();
			final Container container = x2.highLowContainer.getContainer(containerIdx);
			final ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(high);
			if (containerWithIndex == null) {
				final Container containerClone2 = container.clone();
				this.highLowContainer.put(high, containerClone2);
			} else {
				final Container freshOne = containerWithIndex.getContainer().ixor(container);
				this.highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
			}
		}
	}

	/**
	 * Bitwise XOR (symmetric difference) operation. The provided bitmaps are *not* modified. This
	 * operation is thread-safe as long as the provided bitmaps remain unchanged.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return result of the operation
	 */
	@Nonnull
	public static PersistentLongRoaringBitmap xor(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		final PersistentLongRoaringBitmap result = new PersistentLongRoaringBitmap();
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		final KeyIterator it2 = x2.highLowContainer.highKeyIterator();

		byte[] highKey1 = null, highKey2 = null;
		if (it1.hasNext()) {
			highKey1 = it1.next();
		}
		if (it2.hasNext()) {
			highKey2 = it2.next();
		}

		while (highKey1 != null || highKey2 != null) {
			final int compare = HighLowContainer.compareUnsigned(highKey1, highKey2);
			if (compare == 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				final Container xorResult = container1.xor(container2);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey1, "highKey1 is non-null when keys compare equal"), xorResult);

				highKey1 = it1.hasNext() ? it1.next() : null;
				highKey2 = it2.hasNext() ? it2.next() : null;
			} else if (compare < 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey1, "highKey1 is non-null when it precedes highKey2"),
					container1.clone()
				);

				highKey1 = it1.hasNext() ? it1.next() : null;
			} else {
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				result.highLowContainer.put(
					Objects.requireNonNull(highKey2, "highKey2 is non-null when it precedes highKey1"),
					container2.clone()
				);

				highKey2 = it2.hasNext() ? it2.next() : null;
			}
		}
		return result;
	}

	/**
	 * In-place bitwise AND (intersection) operation. The current bitmap is modified.
	 *
	 * @param x2 other bitmap
	 */
	public void and(@Nonnull final PersistentLongRoaringBitmap x2) {
		if (x2 == this) {
			return;
		}
		final KeyIterator thisIterator = this.highLowContainer.highKeyIterator();
		while (thisIterator.hasNext()) {
			final byte[] highKey = thisIterator.next();
			final long containerIdx = thisIterator.currentContainerIdx();
			final ContainerWithIndex containerWithIdx = x2.highLowContainer.searchContainer(highKey);
			if (containerWithIdx == null) {
				thisIterator.remove();
			} else {
				final Container container1 = this.highLowContainer.getContainer(containerIdx);
				final Container freshContainer = container1.iand(containerWithIdx.getContainer());
				if (!freshContainer.isEmpty()) {
					this.highLowContainer.replaceContainer(containerIdx, freshContainer);
				} else {
					thisIterator.remove();
				}
			}
		}
	}

	/**
	 * Bitwise AND (intersection) operation. The provided bitmaps are *not* modified. This operation
	 * is thread-safe as long as the provided bitmaps remain unchanged.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return result of the operation
	 */
	@Nonnull
	public static PersistentLongRoaringBitmap and(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		final PersistentLongRoaringBitmap result = new PersistentLongRoaringBitmap();
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		while (it1.hasNext()) {
			final byte[] highKey = it1.next();
			final long containerIdx1 = it1.currentContainerIdx();
			final ContainerWithIndex containerWithIdx2 = x2.highLowContainer.searchContainer(highKey);
			if (containerWithIdx2 != null) {
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				final Container container2 = containerWithIdx2.getContainer();
				final Container andResult = container1.and(container2);
				if (!andResult.isEmpty()) {
					result.highLowContainer.put(highKey, andResult);
				}
			}
		}

		return result;
	}

	/**
	 * Checks whether the two bitmaps intersect. This can be much faster than calling "and" and
	 * checking the cardinality of the result.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return true if they intersect
	 */
	public static boolean intersects(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		final KeyIterator it2 = x2.highLowContainer.highKeyIterator();

		byte[] highKey1 = it1.hasNext() ? it1.next() : null;
		byte[] highKey2 = it2.hasNext() ? it2.next() : null;

		while (highKey1 != null && highKey2 != null) {
			final int compare = HighLowContainer.compareUnsigned(highKey1, highKey2);
			if (compare == 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				if (container1.intersects(container2)) {
					return true;
				}
				highKey1 = it1.hasNext() ? it1.next() : null;
				highKey2 = it2.hasNext() ? it2.next() : null;
			} else if (compare < 0) {
				highKey1 = it1.hasNext() ? it1.next() : null;
			} else {
				highKey2 = it2.hasNext() ? it2.next() : null;
			}
		}

		return false;
	}

	/**
	 * Cardinality of Bitwise AND (intersection) operation. The provided bitmaps are *not* modified.
	 * This operation is thread-safe as long as the provided bitmaps remain unchanged.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return as if you did and(x1,x2).getCardinality()
	 */
	public static long andCardinality(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		long cardinality = 0;
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		final KeyIterator it2 = x2.highLowContainer.highKeyIterator();

		byte[] highKey1 = null, highKey2 = null;
		if (it1.hasNext()) {
			highKey1 = it1.next();
		}
		if (it2.hasNext()) {
			highKey2 = it2.next();
		}

		while (highKey1 != null && highKey2 != null) {
			final int compare = HighLowContainer.compareUnsigned(highKey1, highKey2);
			if (compare == 0) {
				final long containerIdx1 = it1.currentContainerIdx();
				final long containerIdx2 = it2.currentContainerIdx();
				final Container container1 = x1.highLowContainer.getContainer(containerIdx1);
				final Container container2 = x2.highLowContainer.getContainer(containerIdx2);
				cardinality += container1.andCardinality(container2);
				highKey1 = it1.hasNext() ? it1.next() : null;
				highKey2 = it2.hasNext() ? it2.next() : null;
			} else if (compare < 0) {
				highKey1 = it1.hasNext() ? it1.next() : null;
			} else {
				highKey2 = it2.hasNext() ? it2.next() : null;
			}
		}
		return cardinality;
	}

	/**
	 * In-place bitwise ANDNOT (difference) operation. The current bitmap is modified.
	 *
	 * @param x2 other bitmap
	 */
	public void andNot(@Nonnull final PersistentLongRoaringBitmap x2) {
		if (x2 == this) {
			this.clear();
			return;
		}
		final KeyIterator thisKeyIterator = this.highLowContainer.highKeyIterator();
		while (thisKeyIterator.hasNext()) {
			final byte[] high = thisKeyIterator.next();
			final long containerIdx = thisKeyIterator.currentContainerIdx();
			final ContainerWithIndex containerWithIdx2 = x2.highLowContainer.searchContainer(high);
			if (containerWithIdx2 != null) {
				final Container thisContainer = this.highLowContainer.getContainer(containerIdx);
				final Container freshContainer = thisContainer.iandNot(containerWithIdx2.getContainer());
				this.highLowContainer.replaceContainer(containerIdx, freshContainer);
				if (!freshContainer.isEmpty()) {
					this.highLowContainer.replaceContainer(containerIdx, freshContainer);
				} else {
					thisKeyIterator.remove();
				}
			}
		}
	}

	/**
	 * Bitwise ANDNOT (difference) operation. The provided bitmaps are *not* modified. This operation
	 * is thread-safe as long as the provided bitmaps remain unchanged.
	 *
	 * @param x1 first bitmap
	 * @param x2 other bitmap
	 * @return result of the operation
	 */
	@Nonnull
	public static PersistentLongRoaringBitmap andNot(
		@Nonnull final PersistentLongRoaringBitmap x1, @Nonnull final PersistentLongRoaringBitmap x2) {
		final PersistentLongRoaringBitmap result = new PersistentLongRoaringBitmap();
		final KeyIterator it1 = x1.highLowContainer.highKeyIterator();
		while (it1.hasNext()) {
			final byte[] highKey = it1.next();
			final long containerIdx = it1.currentContainerIdx();
			final ContainerWithIndex containerWithIdx2 = x2.highLowContainer.searchContainer(highKey);
			final Container container1 = x1.highLowContainer.getContainer(containerIdx);
			if (containerWithIdx2 != null) {
				final Container andNotResult = container1.andNot(containerWithIdx2.getContainer());
				if (!andNotResult.isEmpty()) {
					result.highLowContainer.put(highKey, andNotResult);
				}
			} else {
				result.highLowContainer.put(highKey, container1.clone());
			}
		}

		return result;
	}

	/**
	 * Complements the bits in the given range, from rangeStart (inclusive) rangeEnd (exclusive). The
	 * given bitmap is unchanged.
	 *
	 * @param rangeStart inclusive beginning of range, in [0, 0xffffffffffffffff]
	 * @param rangeEnd   exclusive ending of range, in [0, 0xffffffffffffffff + 1]
	 */
	public void flip(final long rangeStart, final long rangeEnd) {

		if (rangeEnd >= 0 && rangeStart >= rangeEnd) {
			// both numbers in positive range, and start is beyond end, nothing to do.
			return;
		} else if (rangeStart < 0 && rangeStart >= rangeEnd) {
			// both numbers in negative range, and start is beyond end, nothing to do.
			return;
		} else if (rangeStart < 0 && rangeEnd > 0) {
			// start is neg which is "higher" and end is above zero thus, nothing to do.
			return;
		}

		final byte[] hbStart = LongUtils.highPart(rangeStart);
		final char lbStart = LongUtils.lowPart(rangeStart);
		final char lbLast = LongUtils.lowPart(rangeEnd - 1L);

		final long shStart = LongUtils.rightShiftHighPart(rangeStart);
		final long shEnd = LongUtils.rightShiftHighPart(rangeEnd - 1L);

		// this per-chunk loop can be accelerated considerably
		for (long hb = shStart; hb <= shEnd; ++hb) {
			// first container may contain partial range
			final int containerStart = (hb == shStart) ? lbStart : 0;
			// last container may contain partial range
			final int containerLast = (hb == shEnd) ? lbLast : LongUtils.maxLowBitAsInteger();

			final ContainerWithIndex cwi =
				this.highLowContainer.searchContainer(
					LongUtils.highPartInPlace(LongUtils.leftShiftHighPart(hb), hbStart));

			if (cwi != null) {
				final long i = cwi.getContainerIdx();
				final Container c = cwi.getContainer().inot(containerStart, containerLast + 1);
				if (!c.isEmpty()) {
					this.highLowContainer.replaceContainer(i, c);
				} else {
					this.highLowContainer.remove(hbStart);
				}
			} else {
				final Container newContainer = Container.rangeOfOnes(containerStart, containerLast + 1);
				this.highLowContainer.put(hbStart, newContainer);
			}
		}
	}

	/**
	 * {@link PersistentLongRoaringBitmap} instances are serializable. However, contrary to
	 * {@link PersistentRoaringBitmap}, the serialization format is not well-defined: for now, it is
	 * strongly coupled with Java standard serialization. Just like the serialization may be
	 * incompatible between various Java versions, {@link PersistentLongRoaringBitmap} instances are
	 * subject to incompatibilities. Moreover, even on a given Java
	 * versions, the serialization format may change from one PersistentRoaringBitmap version to another
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		this.serialize(out);
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException {
		this.deserialize(in);
	}

	/**
	 * A string describing the bitmap.
	 *
	 * @return the string
	 */
	@Nonnull
	@Override
	public String toString() {
		final StringBuilder answer =
			new StringBuilder("{}".length() + "-1234567890123456789,".length() * 256);
		final LongIterator i = this.getLongIterator();
		answer.append('{');
		if (i.hasNext()) {
			answer.append(i.next());
		}
		while (i.hasNext()) {
			answer.append(',');
			// to avoid using too much memory, we limit the size
			if (answer.length() > 0x80000) {
				answer.append('.').append('.').append('.');
				break;
			}
			answer.append(i.next());
		}
		answer.append("}");
		return answer.toString();
	}

	/**
	 * For better performance, consider the Use the {@link #forEach forEach} method.
	 *
	 * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
	 */
	@Nonnull
	@Override
	public PeekableLongIterator getLongIterator() {
		final LeafNodeIterator leafNodeIterator = this.highLowContainer.highKeyLeafNodeIterator(false);
		return new ForwardPeekableIterator(leafNodeIterator);
	}

	// for testing only
	@Nonnull
	LeafNodeIterator getLeafNodeIterator() {
		return this.highLowContainer.highKeyLeafNodeIterator(false);
	}

	/**
	 * Produce an iterator over the values in this bitmap starting from `minval`.
	 *
	 * @param minval the lower bound of the iterator returned
	 * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
	 */
	@Nonnull
	public PeekableLongIterator getLongIteratorFrom(long minval) {
		final LeafNodeIterator leafNodeIterator = this.highLowContainer.highKeyLeafNodeIteratorFrom(minval, false);
		final ForwardPeekableIterator fpi = new ForwardPeekableIterator(leafNodeIterator);
		fpi.advanceIfNeeded(minval); // make sure the lower end is advanced as well
		return fpi;
	}

	/**
	 * Tests membership by locating the container for `x`'s 48-bit high key and probing its low 16
	 * bits; short-circuits to `false` when no container exists for that high key.
	 *
	 * @param x value to test, treated as unsigned
	 * @return `true` if `x` is present
	 */
	@Override
	public boolean contains(long x) {
		final long high = LongUtils.highPartOnly(x);
		final ContainerWithIndex containerWithIdx = this.highLowContainer.searchContainer(high);
		if (containerWithIdx == null) {
			return false;
		}
		final char low = LongUtils.lowPart(x);
		return containerWithIdx.getContainer().contains(low);
	}

	@Override
	public int getSizeInBytes() {
		return (int) this.getLongSizeInBytes();
	}

	/**
	 * Estimate of the memory usage of this data structure. This can be expected to be within 1% of
	 * the true memory usage in common usage scenarios.
	 * If exact measures are needed, we recommend using dedicated libraries
	 * such as ehcache-sizeofengine.
	 *
	 * In adversarial cases, this estimate may be 10x the actual memory usage. For example, if
	 * you insert a single random value in a bitmap, then over a 100 bytes may be used by the JVM
	 * whereas this function may return an estimate of 32 bytes.
	 *
	 * The same will be true in the "sparse" scenario where you have a small set of
	 * random-looking integers spanning a wide range of values.
	 *
	 * These are considered adversarial cases because, as a general rule,
	 * if your data looks like a set
	 * of random integers, Roaring bitmaps are probably not the right data structure.
	 *
	 * Note that you can serialize your Roaring Bitmaps to disk and then construct
	 * ImmutableRoaringBitmap instances from a ByteBuffer. In such cases, the Java heap
	 * usage will be significantly less than
	 * what is reported.
	 *
	 * If your main goal is to compress arrays of integers, there are other libraries
	 * that are maybe more appropriate
	 * such as JavaFastPFOR.
	 *
	 * Note, however, that in general, random integers (as produced by random number
	 * generators or hash functions) are not compressible.
	 * Trying to compress random data is an adversarial use case.
	 *
	 * @return estimated memory usage.
	 * @see <a href="https://github.com/lemire/JavaFastPFOR">JavaFastPFOR</a>
	 */
	@Override
	public long getLongSizeInBytes() {
		// 'serializedSizeInBytes' is a better than nothing estimation of the memory footprint
		// It would generally be an optimistic estimator (by underestimating the size in memory)
		return this.serializedSizeInBytes();
	}

	@Override
	public boolean isEmpty() {
		return this.highLowContainer.isEmpty();
	}

	/**
	 * Capping cardinality is not implemented for the 64-bit bitmap; unlike the
	 * {@link ImmutableLongBitmapDataProvider#limit(long)} contract, no bitmap is returned.
	 *
	 * @param x requested maximal cardinality (ignored)
	 * @return never returns normally
	 * @throws UnsupportedOperationException always
	 */
	@Nonnull
	@Override
	public ImmutableLongBitmapDataProvider limit(long x) {
		throw new UnsupportedOperationException("not implemented in this vendored fork");
	}

	/**
	 * Use a run-length encoding where it is estimated as more space efficient
	 *
	 * @return whether a change was applied
	 */
	public boolean runOptimize() {
		boolean hasChanged = false;
		final ContainerIterator containerIterator = this.highLowContainer.containerIterator();
		while (containerIterator.hasNext()) {
			final Container container = containerIterator.next();
			final Container freshContainer = container.runOptimize();
			if (freshContainer instanceof RunContainer) {
				hasChanged = true;
				containerIterator.replace(freshContainer);
			}
		}
		return hasChanged;
	}

	/**
	 * Serialize this bitmap.
	 *
	 * Unlike PersistentRoaringBitmap, there is no specification for now: it may change from one java version to
	 * another, and from one PersistentRoaringBitmap version to another.
	 *
	 * Consider calling {@link #runOptimize} before serialization to improve compression.
	 *
	 * The current bitmap is not modified.
	 *
	 * @param out the DataOutput stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	@Override
	public void serialize(@Nonnull final DataOutput out) throws IOException {
		this.highLowContainer.serialize(out);
	}

	/**
	 * Serialize this bitmap, please make sure the size of the serialized bytes is
	 * smaller enough that ByteBuffer can hold it.
	 *
	 * @param byteBuffer the ByteBuffer
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void serialize(@Nonnull final ByteBuffer byteBuffer) throws IOException {
		this.highLowContainer.serialize(byteBuffer);
	}

	/**
	 * Deserialize (retrieve) this bitmap.
	 *
	 * Unlike PersistentRoaringBitmap, there is no specification for now: it may change from one java version to
	 * another, and from one PersistentRoaringBitmap version to another.
	 *
	 * The current bitmap is overwritten.
	 *
	 * @param in the DataInput stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void deserialize(@Nonnull final DataInput in) throws IOException {
		this.clear();
		this.highLowContainer.deserialize(in);
		this.removeEmpty();
	}

	/**
	 * Deserialize (retrieve) this bitmap.
	 *
	 * Unlike PersistentRoaringBitmap, there is no specification for now: it may change from one java version to
	 * another, and from one PersistentRoaringBitmap version to another.
	 *
	 * The current bitmap is overwritten.
	 *
	 * @param in the ByteBuffer stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void deserialize(@Nonnull final ByteBuffer in) throws IOException {
		this.clear();
		this.highLowContainer.deserialize(in);
		this.removeEmpty();
	}

	/**
	 * Remove empty containers. It's an invariant that there should be no empty containers in the current implementation.
	 * However, it is possible that the serialized form may have come from another codebase (previous implementation or
	 * different language), so it is prudent to enforce.
	 */
	private void removeEmpty() {
		if (!this.highLowContainer.isEmpty()) {
			final KeyIterator keyIterator = this.highLowContainer.highKeyIterator();
			while (keyIterator.hasNext()) {
				keyIterator.next();
				final long containerIdx = keyIterator.currentContainerIdx();
				final Container container = this.highLowContainer.getContainer(containerIdx);
				if (container.isEmpty()) {
					keyIterator.remove();
				}
			}
		}
	}

	@Override
	public long serializedSizeInBytes() {
		return this.highLowContainer.serializedSizeInBytes();
	}

	/**
	 * reset to an empty bitmap; result occupies as much space a newly created bitmap.
	 */
	public void clear() {
		this.highLowContainer.clear();
	}

	/**
	 * Return the set values as an array, if the cardinality is smaller than 2147483648. The long
	 * values are in sorted order.
	 *
	 * @return array representing the set values.
	 */
	@Nonnull
	@Override
	public long[] toArray() {
		final long cardinality = this.getLongCardinality();
		if (cardinality > Integer.MAX_VALUE) {
			throw new IllegalStateException("The cardinality does not fit in an array");
		}

		final long[] array = new long[(int) cardinality];

		int pos = 0;
		final LongIterator it = this.getLongIterator();

		while (it.hasNext()) {
			array[pos++] = it.next();
		}
		return array;
	}

	/**
	 * Generate a bitmap with the specified values set to true. The provided longs values don't have
	 * to be in sorted order, but it may be preferable to sort them from a performance point of view.
	 *
	 * @param dat set values
	 * @return a new bitmap
	 */
	@Nonnull
	public static PersistentLongRoaringBitmap bitmapOf(@Nonnull final long... dat) {
		final PersistentLongRoaringBitmap ans = new PersistentLongRoaringBitmap();
		ans.add(dat);
		return ans;
	}

	/**
	 * If present remove the specified integer (effectively, sets its bit value to false)
	 *
	 * @param x integer value representing the index in a bitmap
	 */
	public void remove(final long x) {
		final byte[] highKey = LongUtils.highPart(x);
		final ContainerWithIndex containerWithIdx = this.highLowContainer.searchContainer(highKey);
		if (containerWithIdx != null) {
			final char low = LongUtils.lowPart(x);
			containerWithIdx.getContainer().remove(low);
			if (containerWithIdx.getContainer().isEmpty()) {
				this.highLowContainer.remove(highKey);
			}
		}
	}

	/**
	 * Set all the specified values to true. This can be expected to be slightly faster than calling
	 * "add" repeatedly. The provided integers values don't have to be in sorted order, but it may be
	 * preferable to sort them from a performance point of view.
	 *
	 * @param dat set values
	 */
	public void add(@Nonnull final long... dat) {
		for (final long oneLong : dat) {
			this.addLong(oneLong);
		}
	}

	/**
	 * Add to the current bitmap all longs in [rangeStart,rangeEnd).
	 *
	 * @param rangeStart inclusive beginning of range
	 * @param rangeEnd   exclusive ending of range
	 * @deprecated as this may be confused with adding individual longs
	 */
	@Deprecated
	public void add(final long rangeStart, final long rangeEnd) {
		this.addRange(rangeStart, rangeEnd);
	}

	/**
	 * Add to the current bitmap all longs in [rangeStart,rangeEnd).
	 *
	 * @param rangeStart inclusive beginning of range
	 * @param rangeEnd   exclusive ending of range
	 */
	public void addRange(final long rangeStart, final long rangeEnd) {
		if (rangeEnd == 0 || Long.compareUnsigned(rangeStart, rangeEnd) >= 0) {
			throw new IllegalArgumentException("Invalid range [" + rangeStart + "," + rangeEnd + ")");
		}

		final long startHigh = LongUtils.rightShiftHighPart(rangeStart);
		final int startLow = LongUtils.lowPart(rangeStart);
		final long endHigh = LongUtils.rightShiftHighPart(rangeEnd - 1);
		final int endLow = LongUtils.lowPart(rangeEnd - 1);

		long rangeStartVal = rangeStart;
		long startHighKey = LongUtils.rightShiftHighPart(rangeStart);
		byte[] startHighKeyBytes = LongUtils.highPart(rangeStart);
		while (startHighKey <= endHigh) {
			final int containerStart = startHighKey == startHigh ? startLow : 0;
			// last container may contain partial range
			final int containerLast = startHighKey == endHigh ? endLow : Util.maxLowBitAsInteger();
			final ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(startHighKeyBytes);
			if (containerWithIndex != null) {
				final long containerIdx = containerWithIndex.getContainerIdx();
				final Container freshContainer =
					this.highLowContainer.getContainer(containerIdx).iadd(containerStart, containerLast + 1);
				this.highLowContainer.replaceContainer(containerIdx, freshContainer);
			} else {
				final Container freshContainer = Container.rangeOfOnes(containerStart, containerLast + 1);
				this.highLowContainer.put(startHighKeyBytes, freshContainer);
			}

			if (LongUtils.isMaxHigh(startHighKey)) {
				break;
			}
			// increase the high
			rangeStartVal = rangeStartVal + (containerLast - containerStart) + 1;
			startHighKey = LongUtils.rightShiftHighPart(rangeStartVal);
			startHighKeyBytes = LongUtils.highPart(rangeStartVal);
		}
	}

	/**
	 * Returns a peekable iterator over all set values in **descending** unsigned order; the
	 * preferred entry point for backward scans. See {@link #getReverseLongIteratorFrom(long)} to
	 * start below a given upper bound.
	 *
	 * @return an iterator traversing set bits in descending sorted order
	 */
	@Nonnull
	@Override
	public PeekableLongIterator getReverseLongIterator() {
		final LeafNodeIterator leafNodeIterator = this.highLowContainer.highKeyLeafNodeIterator(true);
		return new ReversePeekableIterator(leafNodeIterator);
	}

	/**
	 * Produce an iterator over the values in this bitmap starting from `maxval`.
	 *
	 * @param maxval the upper bound of the iterator returned
	 * @return a custom iterator over set bits, the bits are traversed in descending sorted order
	 */
	@Nonnull
	public PeekableLongIterator getReverseLongIteratorFrom(long maxval) {
		final LeafNodeIterator leafNodeIterator = this.highLowContainer.highKeyLeafNodeIteratorFrom(maxval, true);
		final ReversePeekableIterator rpi = new ReversePeekableIterator(leafNodeIterator);
		rpi.advanceIfNeeded(maxval); // make sure the lower end is advanced as well
		return rpi;
	}

	/**
	 * Removes `x` if present and, to reclaim memory, drops the whole container once its last value is
	 * cleared — upholding the invariant that no empty container is retained.
	 *
	 * @param x value to remove, treated as unsigned
	 */
	@Override
	public void removeLong(long x) {
		final byte[] high = LongUtils.highPart(x);
		final ContainerWithIndex containerWithIdx = this.highLowContainer.searchContainer(high);
		if (containerWithIdx != null) {
			final char low = LongUtils.lowPart(x);
			final Container container = containerWithIdx.getContainer();
			final Container freshContainer = container.remove(low);
			if (freshContainer.isEmpty()) {
				// Attempt to remove empty container to save memory
				this.highLowContainer.remove(high);
			} else {
				this.highLowContainer.replaceContainer(containerWithIdx.getContainerIdx(), freshContainer);
			}
		}
	}

	/**
	 * remove the allocated unused memory space
	 */
	@Override
	public void trim() {
		if (this.highLowContainer.isEmpty()) {
			return;
		}
		final KeyIterator keyIterator = this.highLowContainer.highKeyIterator();
		while (keyIterator.hasNext()) {
			final long containerIdx = keyIterator.currentContainerIdx();
			final Container container = this.highLowContainer.getContainer(containerIdx);
			if (container.isEmpty()) {
				keyIterator.remove();
			} else {
				container.trim();
			}
		}
	}

	@Override
	// 'highLowContainer' is reassigned only by clone(); hashCode delegates to its live contents, consistent with equals()
	@SuppressWarnings("NonFinalFieldReferencedInHashCode")
	public int hashCode() {
		return this.highLowContainer.hashCode();
	}

	@Override
	// 'highLowContainer' is reassigned only by clone(); equality compares live contents, consistent with hashCode()
	@SuppressWarnings("NonFinalFieldReferenceInEquals")
	public boolean equals(@Nullable final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (this.getClass() != obj.getClass()) {
			return false;
		}
		final PersistentLongRoaringBitmap other = (PersistentLongRoaringBitmap) obj;
		return Objects.equals(this.highLowContainer, other.highLowContainer);
	}

	/**
	 * Add the value if it is not already present, otherwise remove it.
	 *
	 * @param x long value
	 */
	public void flip(final long x) {
		final byte[] high = LongUtils.highPart(x);
		final ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(high);
		if (containerWithIndex == null) {
			this.addLong(x);
		} else {
			final char low = LongUtils.lowPart(x);
			final Container freshOne = containerWithIndex.getContainer().flip(low);
			this.highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
		}
	}

	/**
	 * Returns a deep, fully independent copy: every container is cloned, so later mutations on either
	 * bitmap never affect the other. Mainly exercised by benchmarks.
	 *
	 * @return an independent copy of this bitmap
	 */
	@Nonnull
	@Override
	// deep copy via a fresh bitmap and cloned highLowContainer; shallow Object.clone() is inappropriate here
	@SuppressWarnings("CloneDoesntCallSuperClone")
	public PersistentLongRoaringBitmap clone() {
		final PersistentLongRoaringBitmap result = new PersistentLongRoaringBitmap();
		result.highLowContainer = this.highLowContainer.clone();
		return result;
	}

	/**
	 * Two-level cursor that yields whole 64-bit values by walking the ART leaf nodes (48-bit high
	 * keys) in the outer loop and the selected container's low-16-bit values in the inner loop. The
	 * traversal direction (ascending or descending) is supplied by the concrete subclass through
	 * {@link #getIterator} and {@link #compare}, letting the forward and reverse iterators share all
	 * high/low recombination and `advanceIfNeeded` seeking logic.
	 */
	private abstract class PeekableIterator implements PeekableLongIterator {
		/**
		 * Outer cursor over ART leaf nodes, one per non-empty 48-bit high key.
		 */
		@Nonnull private final LeafNodeIterator keyIte;
		/**
		 * 48-bit high key of the leaf currently feeding {@link #charIterator}; null before the first
		 * advance and used to recombine each low value back into a full 64-bit long.
		 */
		@Nullable private byte[] high;
		/**
		 * Inner cursor over the current container's low-16-bit values; null once exhausted.
		 */
		@Nullable private PeekableCharIterator charIterator;

		PeekableIterator(@Nonnull final LeafNodeIterator keyIte) {
			this.keyIte = keyIte;
		}

		/**
		 * Supplies the container's low-value cursor in the subclass's traversal direction (forward or
		 * reverse), so shared code can iterate without knowing the direction.
		 */
		@Nonnull
		abstract PeekableCharIterator getIterator(@Nonnull Container container);

		/**
		 * Direction-aware ordering test used while seeking: returns `true` once `next` has reached or
		 * passed `val` in the subclass's traversal order (`>=` forward, `<=` reverse).
		 */
		abstract boolean compare(long next, long val);

		@Override
		public boolean hasNext() {
			if (this.charIterator != null && this.charIterator.hasNext()) {
				return true;
			}
			while (this.keyIte.hasNext()) {
				final LeafNode leafNode = this.keyIte.next();
				this.high = leafNode.getKeyBytes();
				final long containerIdx = leafNode.getContainerIdx();
				final Container container = PersistentLongRoaringBitmap.this.highLowContainer.getContainer(
					containerIdx);
				this.charIterator = this.getIterator(container);
				if (this.charIterator.hasNext()) {
					return true;
				}
			}
			return false;
		}

		@Override
		public long next() {
			if (this.hasNext()) {
				final char low = Objects.requireNonNull(this.charIterator, "PeekableIterator low cursor is exhausted or not positioned").next();
				return LongUtils.toLong(Objects.requireNonNull(this.high, "PeekableIterator high key is not positioned"), low);
			} else {
				throw new IllegalStateException("empty");
			}
		}

		@Override
		public void advanceIfNeeded(long minval) {
			if (!this.hasNext()) {
				return;
			}
			if (this.compare(this.peekNext(), minval)) {
				return;
			}
			// empty bitset
			if (this.high == null) {
				return;
			}

			final long minHigh = LongUtils.rightShiftHighPart(minval);
			final long high = LongUtils.toLong(this.high);
			if (minHigh != high) {
				// advance outer
				if (this.keyIte.hasNext()) {
					LeafNode leafNode = this.keyIte.next();
					this.high = leafNode.getKeyBytes();
					if (this.compare(leafNode.getKey(), minHigh)) {
						final long containerIdx = leafNode.getContainerIdx();
						final Container container = PersistentLongRoaringBitmap.this.highLowContainer.getContainer(
							containerIdx);
						this.charIterator = this.getIterator(container);
						if (!this.charIterator.hasNext()) {
							return;
						}
					} else {
						this.keyIte.seek(minval);
						if (this.keyIte.hasNext()) {
							leafNode = this.keyIte.next();
							this.high = leafNode.getKeyBytes();
							final long containerIdx = leafNode.getContainerIdx();
							final Container container = PersistentLongRoaringBitmap.this.highLowContainer.getContainer(
								containerIdx);
							this.charIterator = this.getIterator(container);
							if (!this.charIterator.hasNext()) {
								return;
							}
						} else {
							// make sure we don't accidentally continue at the previous iterator position
							// after stepping to the end.
							this.charIterator = null;
							return;
						}
					}
				}
			}

			final byte[] minHighBytes = LongUtils.highPart(minval);
			if (Arrays.equals(this.high, minHighBytes)) {
				// advance inner
				final char low = LongUtils.lowPart(minval);
				Objects.requireNonNull(this.charIterator, "PeekableIterator low cursor is exhausted or not positioned").advanceIfNeeded(low);
			}
		}

		@Override
		public long peekNext() {
			if (this.hasNext()) {
				final char low = Objects.requireNonNull(this.charIterator, "PeekableIterator low cursor is exhausted or not positioned").peekNext();
				return LongUtils.toLong(Objects.requireNonNull(this.high, "PeekableIterator high key is not positioned"), low);
			} else {
				throw new IllegalStateException("empty");
			}
		}

		@Nonnull
		@Override
		public PeekableLongIterator clone() {
			throw new UnsupportedOperationException("not implemented in this vendored fork");
		}
	}

	/**
	 * Ascending traversal: scans each container's low values forward and, when seeking, stops at the
	 * first value that is `>=` the target.
	 */
	private class ForwardPeekableIterator extends PeekableIterator {

		public ForwardPeekableIterator(@Nonnull final LeafNodeIterator keyIte) {
			super(keyIte);
		}

		@Nonnull
		@Override
		PeekableCharIterator getIterator(@Nonnull Container container) {
			return container.getCharIterator();
		}

		@Override
		boolean compare(long next, long val) {
			return Long.compareUnsigned(next, val) >= 0;
		}
	}

	/**
	 * Descending traversal: scans each container's low values in reverse and, when seeking, stops at
	 * the first value that is `<=` the target.
	 */
	private class ReversePeekableIterator extends PeekableIterator {
		public ReversePeekableIterator(@Nonnull final LeafNodeIterator keyIte) {
			super(keyIte);
		}

		@Nonnull
		@Override
		PeekableCharIterator getIterator(@Nonnull Container container) {
			return container.getReverseCharIterator();
		}

		@Override
		boolean compare(long next, long val) {
			return Long.compareUnsigned(next, val) <= 0;
		}
	}
}
