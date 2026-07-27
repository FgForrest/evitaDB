/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Iterator;

import static java.lang.Long.bitCount;
import static java.lang.Long.numberOfTrailingZeros;

/**
 * Dense {@link Container}: a fixed word bitmap covering one 65536-wide Roaring chunk.
 *
 * The chunk is stored as a `long[1024]` (8 KB, {@link #MAX_CAPACITY_LONG} words of 64 bits). A value
 * `v` in `[0, 65536)` is present iff its bit is set, i.e. `bitmap[v >>> 6] & (1L << (v & 63))` is
 * non-zero: the word index is `v >>> 6` and the bit position within the word is `v & 63`. The set
 * bit count is kept in {@link #cardinality} so {@link #getCardinality()} is O(1); bit-parallel
 * operations against another `BitmapContainer` run in a constant 1024 word steps regardless of
 * population.
 *
 * This representation is chosen once a chunk holds more than {@link ArrayContainer#DEFAULT_MAX_SIZE}
 * (4096) values, where the flat 8 KB bitmap is cheaper than a sorted `char[]`. Mutations that drop
 * the cardinality to {@link ArrayContainer#DEFAULT_MAX_SIZE} or below demote the result back to an
 * {@link ArrayContainer}; when the set collapses into few enough runs, {@link #runOptimize()} (and
 * the run-producing operators) convert it to a {@link RunContainer} instead.
 *
 * Instances are mutable: the `i`-prefixed operators (`iand`, `ior`, `ixor`, ...) mutate in place,
 * while the unprefixed operators leave the receiver untouched and return a fresh container.
 */
public final class BitmapContainer extends Container implements Cloneable {
	/**
	 * Number of distinct values a single chunk spans: the full 16-bit key space `[0, 65536)`.
	 */
	public static final int MAX_CAPACITY = 1 << 16;

	/**
	 * Size of the backing bitmap in bytes (8192 = 65536 bits).
	 */
	private static final int MAX_CAPACITY_BYTE = MAX_CAPACITY / Byte.SIZE;

	/**
	 * Number of 64-bit words in the backing bitmap (1024).
	 */
	private static final int MAX_CAPACITY_LONG = MAX_CAPACITY / Long.SIZE;

	@Serial private static final long serialVersionUID = 3L;

	// bail out early when the number of runs is excessive, without
	// an exact count (just a decent lower bound)
	private static final int BLOCKSIZE = 128;

	// 64 words can have max 32 runs per word, max 2k runs

	/**
	 * optimization flag: whether the cardinality of the bitmaps is maintained through branchless
	 * operations
	 */
	private static final boolean USE_BRANCHLESS = true;

	/**
	 * Serialized size of a bitmap container, which is constant (8192 bytes) regardless of population.
	 * The parameter exists only for overload symmetry with `ArrayContainer` and is ignored.
	 *
	 * @param unusedCardinality ignored; present for signature symmetry
	 * @return the fixed serialized size in bytes
	 */
	protected static int serializedSizeInBytes(final int unusedCardinality) {
		return MAX_CAPACITY / 8;
	}

	/**
	 * Fixed 1024-word bitmap (8 KB) backing the chunk: value `v` is present iff
	 * `bitmap[v >>> 6] & (1L << (v & 63))` is non-zero. Never reassigned after construction.
	 */
	@Nonnull final long[] bitmap;

	/**
	 * Number of set bits, kept in sync with {@link #bitmap} by every mutator so
	 * {@link #getCardinality()} stays O(1). A sentinel of `-1` marks the count as unknown after a lazy
	 * union (`ilazyor`/`lazyor`); {@link #repairAfterLazy()} recomputes it before the container is
	 * exposed.
	 */
	int cardinality;

	// nruns value for which RunContainer.serializedSizeInBytes ==
	// BitmapContainer.getArraySizeInBytes()
	private static final int MAXRUNS = (MAX_CAPACITY_BYTE - 2) / 4;

	/**
	 * Create a bitmap container with all bits set to false
	 */
	public BitmapContainer() {
		this.cardinality = 0;
		this.bitmap = new long[MAX_CAPACITY_LONG];
	}

	/**
	 * Create a bitmap container with a run of ones from firstOfRun to lastOfRun. Caller must ensure
	 * that the range isn't so small that an ArrayContainer should have been created instead
	 *
	 * @param firstOfRun first index
	 * @param lastOfRun  last index (range is exclusive)
	 */
	public BitmapContainer(final int firstOfRun, final int lastOfRun) {
		this.cardinality = lastOfRun - firstOfRun;
		this.bitmap = new long[MAX_CAPACITY / 64];
		Util.setBitmapRange(this.bitmap, firstOfRun, lastOfRun);
	}

	/**
	 * Defensive-copy constructor used by {@link #clone()}: snapshots `newBitmap` so the new container
	 * shares no state with the source.
	 *
	 * @param newCardinality number of set bits in `newBitmap`
	 * @param newBitmap      source words, copied into the new container
	 */
	private BitmapContainer(final int newCardinality, @Nonnull final long[] newBitmap) {
		this.cardinality = newCardinality;
		this.bitmap = Arrays.copyOf(newBitmap, newBitmap.length);
	}

	/**
	 * Create a new container, no copy is made.
	 *
	 * @param newBitmap      content
	 * @param newCardinality desired cardinality.
	 */
	public BitmapContainer(@Nonnull final long[] newBitmap, final int newCardinality) {
		this.cardinality = newCardinality;
		this.bitmap = newBitmap;
	}

	/**
	 * Returns a copy with every value in `[begin, end)` added; the receiver is left unchanged.
	 *
	 * @param begin first value to set (inclusive)
	 * @param end   end of the range (exclusive)
	 * @return a fresh container holding the union (adding never demotes)
	 * @throws IllegalArgumentException if the range is malformed or exceeds `[0, 65536)`
	 */
	@Nonnull
	@Override
	public Container add(final int begin, final int end) {
		// may need to convert to a RunContainer
		if (end == begin) {
			return clone();
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		BitmapContainer answer = clone();
		int prevOnesInRange = answer.cardinalityInRange(begin, end);
		Util.setBitmapRange(answer.bitmap, begin, end);
		answer.updateCardinality(prevOnesInRange, end - begin);
		return answer;
	}

	/**
	 * Sets bit `i` in place, adjusting {@link #cardinality} only if the bit was previously clear.
	 *
	 * @param i value to add
	 * @return this container (adding never triggers demotion)
	 */
	@Nonnull
	@Override
	public Container add(final char i) {
		final long previous = this.bitmap[i >>> 6];
		long newval = previous | (1L << i);
		this.bitmap[i >>> 6] = newval;
		if (USE_BRANCHLESS) {
			this.cardinality += (int) ((previous ^ newval) >>> i);
		} else if (previous != newval) {
			++this.cardinality;
		}
		return this;
	}

	/**
	 * Intersects with an {@link ArrayContainer}; the result cannot exceed `value2`, so it is always an
	 * {@link ArrayContainer}. Runs in O(`value2` cardinality) with O(1) bit tests per candidate.
	 *
	 * @param value2 container to intersect with
	 * @return the intersection as an {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public ArrayContainer and(@Nonnull final ArrayContainer value2) {
		final ArrayContainer answer = new ArrayContainer(value2.content.length);
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			answer.content[answer.cardinality] = v;
			answer.cardinality += this.bitValue(v);
		}
		return answer;
	}

	/**
	 * Intersects two bitmaps with 1024 word-wise ANDs, returning a {@link BitmapContainer} when the
	 * result stays dense or an {@link ArrayContainer} once it falls to
	 * {@link ArrayContainer#DEFAULT_MAX_SIZE} or below.
	 *
	 * @param value2 container to intersect with
	 * @return the intersection, container type chosen by the resulting cardinality
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final BitmapContainer value2) {
		int newCardinality = andCardinality(value2);
		if (newCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
			final BitmapContainer answer = new BitmapContainer();
			for (int k = 0; k < answer.bitmap.length; ++k) {
				answer.bitmap[k] = this.bitmap[k] & value2.bitmap[k];
			}
			answer.cardinality = newCardinality;
			return answer;
		}
		ArrayContainer ac = new ArrayContainer(newCardinality);
		Util.fillArrayAND(ac.content, this.bitmap, value2.bitmap);
		ac.cardinality = newCardinality;
		return ac;
	}

	/**
	 * Intersects with a {@link RunContainer}; delegates to its run-aware implementation.
	 *
	 * @param x container to intersect with
	 * @return the intersection
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final RunContainer x) {
		return x.and(this);
	}

	/**
	 * Counts set bits shared with `value2` without materializing the intersection.
	 *
	 * @param value2 container to test against
	 * @return size of the intersection
	 */
	@Override
	public int andCardinality(@Nonnull final ArrayContainer value2) {
		int answer = 0;
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			answer += this.bitValue(v);
		}
		return answer;
	}

	/**
	 * Counts set bits shared with `value2` via 1024 `AND`-then-popcount word steps.
	 *
	 * @param value2 container to test against
	 * @return size of the intersection
	 */
	@Override
	public int andCardinality(@Nonnull final BitmapContainer value2) {
		int newCardinality = 0;
		for (int k = 0; k < this.bitmap.length; ++k) {
			newCardinality += Long.bitCount(this.bitmap[k] & value2.bitmap[k]);
		}
		return newCardinality;
	}

	/**
	 * Counts set bits shared with a {@link RunContainer}; delegates to its run-aware implementation.
	 *
	 * @param x container to test against
	 * @return size of the intersection
	 */
	@Override
	public int andCardinality(@Nonnull final RunContainer x) {
		return x.andCardinality(this);
	}

	/**
	 * Returns a copy with every value of `value2` removed (set difference `this \ value2`), demoting
	 * to an {@link ArrayContainer} if the result falls to {@link ArrayContainer#DEFAULT_MAX_SIZE} or
	 * below.
	 *
	 * @param value2 values to subtract
	 * @return the difference container
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final ArrayContainer value2) {
		final BitmapContainer answer = clone();
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			final int i = (v) >>> 6;
			long w = answer.bitmap[i];
			long aft = w & (~(1L << v));
			answer.bitmap[i] = aft;
			answer.cardinality -= (w ^ aft) >>> v;
		}
		if (answer.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer.toArrayContainer();
		}
		return answer;
	}

	/**
	 * Returns the set difference `this \ value2` computed with 1024 word-wise `AND NOT` steps,
	 * demoting to an {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param value2 values to subtract
	 * @return the difference container
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final BitmapContainer value2) {
		int newCardinality = 0;
		for (int k = 0; k < this.bitmap.length; ++k) {
			newCardinality += Long.bitCount(this.bitmap[k] & (~value2.bitmap[k]));
		}
		if (newCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
			final BitmapContainer answer = new BitmapContainer();
			for (int k = 0; k < answer.bitmap.length; ++k) {
				answer.bitmap[k] = this.bitmap[k] & (~value2.bitmap[k]);
			}
			answer.cardinality = newCardinality;
			return answer;
		}
		ArrayContainer ac = new ArrayContainer(newCardinality);
		Util.fillArrayANDNOT(ac.content, this.bitmap, value2.bitmap);
		ac.cardinality = newCardinality;
		return ac;
	}

	/**
	 * Returns the set difference `this \ x` by clearing each run of `x` from a copy, demoting to an
	 * {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param x runs to subtract
	 * @return the difference container
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final RunContainer x) {
		// could be rewritten as return andNot(x.toBitmapOrArrayContainer());
		BitmapContainer answer = this.clone();
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = (x.getValue(rlepos));
			int end = start + (x.getLength(rlepos)) + 1;
			int prevOnesInRange = answer.cardinalityInRange(start, end);
			Util.resetBitmapRange(answer.bitmap, start, end);
			answer.updateCardinality(prevOnesInRange, 0);
		}
		if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer;
		} else {
			return answer.toArrayContainer();
		}
	}

	/**
	 * Consistency self-check: verifies the container is legitimately dense (cardinality above
	 * {@link ArrayContainer#DEFAULT_MAX_SIZE}) and that {@link #cardinality} matches the recomputed
	 * popcount.
	 *
	 * @return `true` when the invariant holds, `false` otherwise
	 */
	@Nonnull
	@Override
	public Boolean validate() {
		if (this.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return false;
		}
		int computed_cardinality = 0;
		for (int k = 0; k < this.bitmap.length; k++) {
			computed_cardinality += Long.bitCount(this.bitmap[k]);
		}
		return this.cardinality == computed_cardinality;
	}

	/**
	 * Clears every bit and resets the cardinality to zero.
	 */
	@Override
	public void clear() {
		if (this.cardinality != 0) {
			this.cardinality = 0;
			Arrays.fill(this.bitmap, 0);
		}
	}

	/**
	 * Returns a deep copy that shares no bitmap words with this container.
	 */
	@Nonnull
	@Override
	public BitmapContainer clone() {
		return new BitmapContainer(this.cardinality, this.bitmap);
	}

	/**
	 * Returns `true` when no bit is set.
	 */
	@Override
	public boolean isEmpty() {
		return this.cardinality == 0;
	}

	/**
	 * Recomputes the cardinality of the bitmap.
	 */
	void computeCardinality() {
		this.cardinality = 0;
		for (int k = 0; k < this.bitmap.length; k++) {
			this.cardinality += Long.bitCount(this.bitmap[k]);
		}
	}

	/**
	 * Counts set bits in the half-open range `[start, end)`. When the range spans more than half the
	 * universe and {@link #cardinality} is known, counts the (smaller) complement outside the range
	 * and subtracts it instead.
	 *
	 * @param start range start (inclusive)
	 * @param end   range end (exclusive)
	 * @return number of set bits within the range
	 */
	int cardinalityInRange(final int start, final int end) {
		if (this.cardinality != -1 && end - start > MAX_CAPACITY / 2) {
			int before = Util.cardinalityInBitmapRange(this.bitmap, 0, start);
			int after = Util.cardinalityInBitmapRange(this.bitmap, end, MAX_CAPACITY);
			return this.cardinality - before - after;
		}
		return Util.cardinalityInBitmapRange(this.bitmap, start, end);
	}

	/**
	 * Adjusts {@link #cardinality} after a range rewrite that replaced `prevOnes` set bits with
	 * `newOnes` set bits.
	 *
	 * @param prevOnes set-bit count in the range before the rewrite
	 * @param newOnes  set-bit count in the range after the rewrite
	 */
	void updateCardinality(final int prevOnes, final int newOnes) {
		int oldCardinality = this.cardinality;
		this.cardinality = oldCardinality - prevOnes + newOnes;
	}

	/**
	 * Tests membership of a single value in O(1) via a direct bit lookup.
	 *
	 * @param i value to test
	 * @return `true` if the bit is set
	 */
	@Override
	public boolean contains(final char i) {
		return (this.bitmap[i >>> 6] & (1L << i)) != 0;
	}

	/**
	 * Tests whether every value in the half-open range `[minimum, supremum)` is present.
	 *
	 * @param minimum  range start (inclusive)
	 * @param supremum range end (exclusive)
	 * @return `true` if the whole range is set
	 */
	@Override
	public boolean contains(final int minimum, final int supremum) {
		int start = minimum >>> 6;
		int end = supremum >>> 6;
		long first = -(1L << minimum);
		long last = ((1L << supremum) - 1);
		if (start == end) {
			return ((this.bitmap[end] & first & last) == (first & last));
		}
		if ((this.bitmap[start] & first) != first) {
			return false;
		}
		if (end < this.bitmap.length && (this.bitmap[end] & last) != last) {
			return false;
		}
		for (int i = start + 1; i < this.bitmap.length && i < end; ++i) {
			if (this.bitmap[i] != -1L) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tests whether this container is a superset of `bitmapContainer` (every set bit of the argument
	 * is also set here). Runs in O(1024) word steps.
	 *
	 * @param bitmapContainer candidate subset
	 * @return `true` if this container contains all of `bitmapContainer`
	 */
	@Override
	protected boolean contains(@Nonnull final BitmapContainer bitmapContainer) {
		if ((this.cardinality != -1) && (bitmapContainer.cardinality != -1)) {
			if (this.cardinality < bitmapContainer.cardinality) {
				return false;
			}
		}
		for (int i = 0; i < bitmapContainer.bitmap.length; ++i) {
			if ((this.bitmap[i] & bitmapContainer.bitmap[i]) != bitmapContainer.bitmap[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tests whether every run of `runContainer` is fully present in this container.
	 *
	 * @param runContainer candidate subset
	 * @return `true` if this container contains all of `runContainer`
	 */
	@Override
	protected boolean contains(@Nonnull final RunContainer runContainer) {
		final int runCardinality = runContainer.getCardinality();
		if (this.cardinality != -1) {
			if (this.cardinality < runCardinality) {
				return false;
			}
		} else {
			int card = this.cardinality;
			if (card < runCardinality) {
				return false;
			}
		}
		for (int i = 0; i < runContainer.numberOfRuns(); ++i) {
			int start = (runContainer.getValue(i));
			int length = (runContainer.getLength(i));
			if (!contains(start, start + length)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tests whether every value of `arrayContainer` is present in this container.
	 *
	 * @param arrayContainer candidate subset
	 * @return `true` if this container contains all of `arrayContainer`
	 */
	@Override
	protected boolean contains(@Nonnull final ArrayContainer arrayContainer) {
		if (arrayContainer.cardinality != -1) {
			if (this.cardinality < arrayContainer.cardinality) {
				return false;
			}
		}
		for (int i = 0; i < arrayContainer.cardinality; ++i) {
			if (!contains(arrayContainer.content[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the value of bit `i` as `0` or `1`, enabling branchless cardinality accumulation.
	 *
	 * @param i value to test
	 * @return `1` if the bit is set, `0` otherwise
	 */
	int bitValue(final char i) {
		return (int) (this.bitmap[i >>> 6] >>> i) & 1;
	}

	/**
	 * Reads 1024 little-endian words from `in`, recomputing {@link #cardinality} from the loaded bits.
	 *
	 * @param in source stream, positioned at the start of the serialized bitmap
	 * @throws IOException on read failure
	 */
	@Override
	public void deserialize(@Nonnull final DataInput in) throws IOException {
		// little endian
		this.cardinality = 0;
		for (int k = 0; k < this.bitmap.length; ++k) {
			long w = Long.reverseBytes(in.readLong());
			this.bitmap[k] = w;
			this.cardinality += Long.bitCount(w);
		}
	}

	/**
	 * Two containers are equal when they represent the same set of values; comparison against a
	 * {@link RunContainer} is delegated to it.
	 *
	 * @param o object to compare with
	 * @return `true` if `o` holds the same values
	 */
	@Override
	// o.equals(this) is intentional cross-type content equality; cardinality is mutable by design
	@SuppressWarnings({"EqualsBetweenInconvertibleTypes", "NonFinalFieldReferenceInEquals"})
	public boolean equals(@Nullable final Object o) {
		if (o instanceof BitmapContainer srb) {
			if (srb.cardinality != this.cardinality) {
				return false;
			}
			return Arrays.equals(this.bitmap, srb.bitmap);
		} else if (o instanceof RunContainer) {
			return o.equals(this);
		}
		return false;
	}

	/**
	 * Appends every set value, OR-ed with `mask` (the high-bit prefix), into `x` starting at index
	 * `i`, in ascending order.
	 *
	 * @param x    destination array (must have room for {@link #cardinality} entries from `i`)
	 * @param i    first write position in `x`
	 * @param mask high-bit prefix added to each 16-bit value
	 */
	@Override
	public void fillLeastSignificant16bits(@Nonnull final int[] x, final int i, final int mask) {
		int pos = i;
		int base = mask;
		for (int k = 0; k < this.bitmap.length; ++k) {
			long bitset = this.bitmap[k];
			while (bitset != 0) {
				x[pos++] = base + numberOfTrailingZeros(bitset);
				bitset &= (bitset - 1);
			}
			base += 64;
		}
	}

	/**
	 * Toggles bit `i` in place, demoting to an {@link ArrayContainer} when the flip clears a bit and
	 * drops the cardinality to {@link ArrayContainer#DEFAULT_MAX_SIZE}.
	 *
	 * @param i value to flip
	 * @return this container, or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container flip(final char i) {
		int index = i >>> 6;
		long bef = this.bitmap[index];
		long mask = 1L << i;
		if (this.cardinality == ArrayContainer.DEFAULT_MAX_SIZE + 1) { // this is
			// the
			// uncommon
			// path
			if ((bef & mask) != 0) {
				--this.cardinality;
				this.bitmap[index] &= ~mask;
				return this.toArrayContainer();
			}
		}
		// check whether a branchy version could be faster
		this.cardinality += 1 - 2 * (int) ((bef & mask) >>> i);
		this.bitmap[index] ^= mask;
		return this;
	}

	/**
	 * Returns the byte footprint of the word bitmap (always 8192).
	 */
	@Override
	public int getArraySizeInBytes() {
		return MAX_CAPACITY_BYTE;
	}

	/**
	 * Returns the number of set bits in O(1); may be `-1` while awaiting {@link #repairAfterLazy()}.
	 */
	@Override
	public int getCardinality() {
		return this.cardinality;
	}

	/**
	 * Returns an iterator over the set values in descending order.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getReverseCharIterator() {
		return new ReverseBitmapContainerCharIterator(this.bitmap);
	}

	/**
	 * Returns an iterator over the set values in ascending order.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getCharIterator() {
		return new BitmapContainerCharIterator(this.bitmap);
	}

	/**
	 * Returns an ascending iterator that also exposes the 1-based rank of each value.
	 */
	@Nonnull
	@Override
	public PeekableCharRankIterator getCharRankIterator() {
		return new BitmapContainerCharRankIterator(this.bitmap);
	}

	/**
	 * Returns a batch iterator that drains set values into caller-supplied buffers.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator getBatchIterator() {
		return new BitmapBatchIterator(this);
	}

	/**
	 * Returns the in-memory size of the backing words in bytes (always 8192).
	 */
	@Override
	public int getSizeInBytes() {
		return this.bitmap.length * 8;
	}

	/**
	 * Hash derived from the bitmap words, consistent with {@link #equals(Object)}.
	 */
	@Override
	public int hashCode() {
		return Arrays.hashCode(this.bitmap);
	}

	/**
	 * Adds every value in `[begin, end)` in place.
	 *
	 * @param begin first value to set (inclusive)
	 * @param end   end of the range (exclusive)
	 * @return this container
	 * @throws IllegalArgumentException if the range is malformed or exceeds `[0, 65536)`
	 */
	@Nonnull
	@Override
	public Container iadd(final int begin, final int end) {
		// may need to convert to a RunContainer
		if (end == begin) {
			return this;
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		int prevOnesInRange = cardinalityInRange(begin, end);
		Util.setBitmapRange(this.bitmap, begin, end);
		updateCardinality(prevOnesInRange, end - begin);
		return this;
	}

	/**
	 * Intersects with `b2` in place. In lazy mode (cardinality `-1`) the array is masked directly into
	 * the bitmap; otherwise the operation is delegated so the smaller array result is produced.
	 *
	 * @param b2 container to intersect with
	 * @return this container in lazy mode, otherwise the intersection (usually an {@link ArrayContainer})
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final ArrayContainer b2) {
		if (-1 == this.cardinality) {
			// actually we can avoid allocating in lazy mode
			Util.intersectArrayIntoBitmap(this.bitmap, b2.content, b2.cardinality);
			return this;
		} else {
			return b2.and(this);
		}
	}

	/**
	 * Intersects with `b2` in place via 1024 word ANDs, demoting to an {@link ArrayContainer} when the
	 * result is no longer dense (except in lazy mode, where the count is deferred).
	 *
	 * @param b2 container to intersect with
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final BitmapContainer b2) {
		if (-1 == this.cardinality) {
			// in lazy mode, just intersect the bitmaps, can repair afterwards
			for (int i = 0; i < this.bitmap.length; ++i) {
				this.bitmap[i] &= b2.bitmap[i];
			}
			return this;
		} else {
			int newCardinality = andCardinality(b2);
			if (newCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
				for (int k = 0; k < this.bitmap.length; ++k) {
					this.bitmap[k] = this.bitmap[k] & b2.bitmap[k];
				}
				this.cardinality = newCardinality;
				return this;
			}
			ArrayContainer ac = new ArrayContainer(newCardinality);
			Util.fillArrayAND(ac.content, this.bitmap, b2.bitmap);
			ac.cardinality = newCardinality;
			return ac;
		}
	}

	/**
	 * Intersects with a {@link RunContainer} in place by clearing every gap between its runs, demoting
	 * to an {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param x container to intersect with
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final RunContainer x) {
		// could probably be replaced with return iand(x.toBitmapOrArrayContainer());
		final int card = x.getCardinality();
		if (-1 != this.cardinality && card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// no point in doing it in-place, unless it's a lazy operation
			ArrayContainer answer = new ArrayContainer(card);
			answer.cardinality = 0;
			for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
				int runStart = (x.getValue(rlepos));
				int runEnd = runStart + (x.getLength(rlepos));
				for (int runValue = runStart; runValue <= runEnd; ++runValue) {
					answer.content[answer.cardinality] = (char) runValue;
					answer.cardinality += this.bitValue((char) runValue);
				}
			}
			return answer;
		}
		int start = 0;
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int end = x.getValue(rlepos);
			if (-1 == this.cardinality) {
				Util.resetBitmapRange(this.bitmap, start, end);
			} else {
				int prevOnes = cardinalityInRange(start, end);
				Util.resetBitmapRange(this.bitmap, start, end);
				updateCardinality(prevOnes, 0);
			}
			start = end + x.getLength(rlepos) + 1;
		}
		if (-1 == this.cardinality) {
			// in lazy mode don't try to trim
			Util.resetBitmapRange(this.bitmap, start, MAX_CAPACITY);
		} else {
			int ones = cardinalityInRange(start, MAX_CAPACITY);
			Util.resetBitmapRange(this.bitmap, start, MAX_CAPACITY);
			updateCardinality(ones, 0);
			if (getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				return toArrayContainer();
			}
		}
		return this;
	}

	/**
	 * Removes every value of `b2` in place (set difference), demoting to an {@link ArrayContainer}
	 * when the result is no longer dense.
	 *
	 * @param b2 values to subtract
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final ArrayContainer b2) {
		for (int k = 0; k < b2.cardinality; ++k) {
			this.remove(b2.content[k]);
		}
		if (this.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return this.toArrayContainer();
		}
		return this;
	}

	/**
	 * Subtracts `b2` in place via 1024 word `AND NOT` steps, demoting to an {@link ArrayContainer}
	 * when the result is no longer dense.
	 *
	 * @param b2 values to subtract
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final BitmapContainer b2) {
		int newCardinality = 0;
		for (int k = 0; k < this.bitmap.length; ++k) {
			newCardinality += Long.bitCount(this.bitmap[k] & (~b2.bitmap[k]));
		}
		if (newCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
			for (int k = 0; k < this.bitmap.length; ++k) {
				this.bitmap[k] = this.bitmap[k] & (~b2.bitmap[k]);
			}
			this.cardinality = newCardinality;
			return this;
		}
		ArrayContainer ac = new ArrayContainer(newCardinality);
		Util.fillArrayANDNOT(ac.content, this.bitmap, b2.bitmap);
		ac.cardinality = newCardinality;
		return ac;
	}

	/**
	 * Subtracts every run of `x` in place, demoting to an {@link ArrayContainer} when the result is no
	 * longer dense.
	 *
	 * @param x runs to subtract
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final RunContainer x) {
		// could probably be replaced with return iandNot(x.toBitmapOrArrayContainer());
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = (x.getValue(rlepos));
			int end = start + (x.getLength(rlepos)) + 1;
			int prevOnesInRange = cardinalityInRange(start, end);
			Util.resetBitmapRange(this.bitmap, start, end);
			updateCardinality(prevOnesInRange, 0);
		}
		if (getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return this;
		} else {
			return toArrayContainer();
		}
	}

	/**
	 * In-place lazy union with an {@link ArrayContainer}: sets the bits but leaves {@link #cardinality}
	 * as `-1` (unknown) to skip per-value counting. Callers must invoke {@link #repairAfterLazy()}
	 * before exposing the result.
	 *
	 * @param value2 values to add
	 * @return this container
	 */
	@Nonnull
	Container ilazyor(@Nonnull final ArrayContainer value2) {
		this.cardinality = -1; // invalid
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			final int i = (v) >>> 6;
			this.bitmap[i] |= (1L << v);
		}
		return this;
	}

	/**
	 * In-place lazy union with another bitmap (1024 word ORs), deferring the cardinality recount to
	 * {@link #repairAfterLazy()}.
	 *
	 * @param x values to add
	 * @return this container
	 */
	@Nonnull
	Container ilazyor(@Nonnull final BitmapContainer x) {
		this.cardinality = -1; // invalid
		for (int k = 0; k < this.bitmap.length; k++) {
			this.bitmap[k] |= x.bitmap[k];
		}
		return this;
	}

	/**
	 * In-place lazy union with a {@link RunContainer}, deferring the cardinality recount to
	 * {@link #repairAfterLazy()}.
	 *
	 * @param x runs to add
	 * @return this container
	 */
	@Nonnull
	Container ilazyor(@Nonnull final RunContainer x) {
		// could be implemented as return ilazyor(x.toTemporaryBitmap());
		this.cardinality = -1; // invalid
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = (x.getValue(rlepos));
			int end = start + (x.getLength(rlepos)) + 1;
			Util.setBitmapRange(this.bitmap, start, end);
		}
		return this;
	}

	/**
	 * Flips every bit in `[firstOfRange, lastOfRange)` in place, demoting to an {@link ArrayContainer}
	 * when the result is no longer dense.
	 *
	 * @param firstOfRange range start (inclusive)
	 * @param lastOfRange  range end (exclusive)
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container inot(final int firstOfRange, final int lastOfRange) {
		int prevOnes = cardinalityInRange(firstOfRange, lastOfRange);
		Util.flipBitmapRange(this.bitmap, firstOfRange, lastOfRange);
		updateCardinality(prevOnes, lastOfRange - firstOfRange - prevOnes);
		if (this.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return toArrayContainer();
		}
		return this;
	}

	/**
	 * Tests whether any value of `value2` is also present here.
	 *
	 * @param value2 container to test against
	 * @return `true` if the two share at least one value
	 */
	@Override
	public boolean intersects(@Nonnull final ArrayContainer value2) {
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			if (this.contains(value2.content[k])) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tests for a non-empty intersection with `value2`, short-circuiting on the first overlapping word.
	 *
	 * @param value2 container to test against
	 * @return `true` if the two share at least one value
	 */
	@Override
	public boolean intersects(@Nonnull final BitmapContainer value2) {
		for (int k = 0; k < this.bitmap.length; ++k) {
			if ((this.bitmap[k] & value2.bitmap[k]) != 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tests for a non-empty intersection with a {@link RunContainer}; delegates to it.
	 *
	 * @param x container to test against
	 * @return `true` if the two share at least one value
	 */
	@Override
	public boolean intersects(@Nonnull final RunContainer x) {
		return x.intersects(this);
	}

	/**
	 * Tests whether any value in the half-open range `[minimum, supremum)` is present.
	 *
	 * @param minimum  range start (inclusive)
	 * @param supremum range end (exclusive)
	 * @return `true` if at least one bit in the range is set
	 * @throws RuntimeException if the range is malformed or exceeds `[0, 65536)`
	 */
	@Override
	public boolean intersects(final int minimum, final int supremum) {
		if ((minimum < 0) || (supremum < minimum) || (supremum > (1 << 16))) {
			throw new RuntimeException("This should never happen (bug).");
		}
		int start = minimum >>> 6;
		int end = supremum >>> 6;
		if (start == end) {
			return ((this.bitmap[end] & (-(1L << minimum) & ((1L << supremum) - 1))) != 0);
		}
		if ((this.bitmap[start] & -(1L << minimum)) != 0) {
			return true;
		}
		if (end < this.bitmap.length && (this.bitmap[end] & ((1L << supremum) - 1)) != 0) {
			return true;
		}
		for (int i = 1 + start; i < end && i < this.bitmap.length; ++i) {
			if (this.bitmap[i] != 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds every value of `value2` in place, keeping {@link #cardinality} current.
	 *
	 * @param value2 values to add
	 * @return this container (a union never demotes)
	 */
	@Nonnull
	@Override
	public BitmapContainer ior(@Nonnull final ArrayContainer value2) {
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			final int i = (value2.content[k]) >>> 6;

			long bef = this.bitmap[i];
			long aft = bef | (1L << value2.content[k]);
			this.bitmap[i] = aft;
			if (USE_BRANCHLESS) {
				this.cardinality += (int) ((bef - aft) >>> 63);
			} else {
				if (bef != aft) {
					this.cardinality++;
				}
			}
		}
		return this;
	}

	/**
	 * Unions `b2` into this bitmap via 1024 word ORs, promoting to the full {@link RunContainer} when
	 * every bit ends up set.
	 *
	 * @param b2 values to add
	 * @return this container, or {@link RunContainer#full()} when saturated
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final BitmapContainer b2) {
		for (int k = 0; k < this.bitmap.length & k < b2.bitmap.length; k++) {
			this.bitmap[k] |= b2.bitmap[k];
		}
		computeCardinality();
		if (isFull()) {
			return RunContainer.full();
		}
		return this;
	}

	/**
	 * Unions the runs of `x` into this bitmap in place, promoting to the full {@link RunContainer}
	 * when every bit ends up set.
	 *
	 * @param x runs to add
	 * @return this container, or {@link RunContainer#full()} when saturated
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final RunContainer x) {
		// could probably be replaced with return ior(x.toBitmapOrArrayContainer());
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = (x.getValue(rlepos));
			int end = start + (x.getLength(rlepos)) + 1;
			int prevOnesInRange = cardinalityInRange(start, end);
			Util.setBitmapRange(this.bitmap, start, end);
			updateCardinality(prevOnesInRange, end - start);
		}
		if (isFull()) {
			return RunContainer.full();
		}
		return this;
	}

	/**
	 * Removes every value in `[begin, end)` in place, demoting to an {@link ArrayContainer} when the
	 * result is no longer dense.
	 *
	 * @param begin first value to clear (inclusive)
	 * @param end   end of the range (exclusive)
	 * @return this container or the demoted {@link ArrayContainer}
	 * @throws IllegalArgumentException if the range is malformed or exceeds `[0, 65536)`
	 */
	@Nonnull
	@Override
	public Container iremove(final int begin, final int end) {
		if (end == begin) {
			return this;
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		int prevOnesInRange = cardinalityInRange(begin, end);
		Util.resetBitmapRange(this.bitmap, begin, end);
		updateCardinality(prevOnesInRange, 0);
		if (getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return toArrayContainer();
		}
		return this;
	}

	/**
	 * Returns a boxed {@link Character} iterator over the set values in ascending order; `remove()` is
	 * unsupported.
	 *
	 * @return an ascending iterator over the set values
	 */
	@Nonnull
	@Override
	public Iterator<Character> iterator() {
		return new Iterator<>() {
			@Nonnull final CharIterator si = BitmapContainer.this.getCharIterator();

			@Override
			public boolean hasNext() {
				return this.si.hasNext();
			}

			@Nonnull
			@Override
			public Character next() {
				return this.si.next();
			}

			@Override
			public void remove() {
				throw new RuntimeException("unsupported operation: remove");
			}
		};
	}

	/**
	 * Toggles every value of `value2` in place (symmetric difference), demoting to an
	 * {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param value2 values to toggle
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final ArrayContainer value2) {
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char vc = value2.content[k];
			long mask = 1L << vc;
			final int index = (vc) >>> 6;
			long ba = this.bitmap[index];
			// check whether a branchy version could be faster
			this.cardinality += 1 - 2 * (int) ((ba & mask) >>> vc);
			this.bitmap[index] = ba ^ mask;
		}
		if (this.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return this.toArrayContainer();
		}
		return this;
	}

	/**
	 * XORs `b2` into this bitmap via 1024 word steps (symmetric difference), demoting to an
	 * {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param b2 values to toggle
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final BitmapContainer b2) {
		// do this first because we have to compute the xor no matter what, and this loop gets
		// vectorized and is faster than computing the cardinality or filling the array
		for (int k = 0; k < this.bitmap.length & k < b2.bitmap.length; ++k) {
			this.bitmap[k] ^= b2.bitmap[k];
		}
		// now count the bits
		computeCardinality();
		if (this.cardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
			return this;
		}
		return toArrayContainer();
	}

	/**
	 * XORs the runs of `x` into this bitmap in place, demoting to an {@link ArrayContainer} when the
	 * result is no longer dense.
	 *
	 * @param x runs to toggle
	 * @return this container or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final RunContainer x) {
		// could probably be replaced with return ixor(x.toBitmapOrArrayContainer());
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = x.getValue(rlepos);
			int end = start + x.getLength(rlepos) + 1;
			int prevOnes = cardinalityInRange(start, end);
			Util.flipBitmapRange(this.bitmap, start, end);
			updateCardinality(prevOnes, end - start - prevOnes);
		}
		if (this.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return this;
		} else {
			return toArrayContainer();
		}
	}

	/**
	 * Returns a copy unioned with `value2`, leaving cardinality unknown (`-1`) for a later
	 * {@link #repairAfterLazy()}; the receiver is unchanged.
	 *
	 * @param value2 values to add
	 * @return a fresh lazily-unioned container
	 */
	@Nonnull
	protected Container lazyor(@Nonnull final ArrayContainer value2) {
		BitmapContainer answer = this.clone();
		answer.cardinality = -1; // invalid
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			final int i = (v) >>> 6;
			answer.bitmap[i] |= (1L << v);
		}
		return answer;
	}

	/**
	 * Returns a fresh bitmap unioned with `x` via 1024 word ORs, leaving cardinality unknown (`-1`)
	 * for a later {@link #repairAfterLazy()}.
	 *
	 * @param x values to add
	 * @return a fresh lazily-unioned container
	 */
	@Nonnull
	protected Container lazyor(@Nonnull final BitmapContainer x) {
		BitmapContainer answer = new BitmapContainer();
		answer.cardinality = -1; // invalid
		for (int k = 0; k < this.bitmap.length; k++) {
			answer.bitmap[k] = this.bitmap[k] | x.bitmap[k];
		}
		return answer;
	}

	/**
	 * Returns a copy unioned with the runs of `x`, leaving cardinality unknown (`-1`) for a later
	 * {@link #repairAfterLazy()}.
	 *
	 * @param x runs to add
	 * @return a fresh lazily-unioned container
	 */
	@Nonnull
	protected Container lazyor(@Nonnull final RunContainer x) {
		BitmapContainer bc = clone();
		bc.cardinality = -1; // invalid
		for (int rlepos = 0; rlepos < x.nbrruns; ++rlepos) {
			int start = (x.getValue(rlepos));
			int end = start + (x.getLength(rlepos)) + 1;
			Util.setBitmapRange(bc.bitmap, start, end);
		}
		return bc;
	}

	/**
	 * Returns a container holding only the first `maxcardinality` values in ascending order, choosing
	 * an {@link ArrayContainer} when that bound is small enough.
	 *
	 * @param maxcardinality maximum number of values to retain
	 * @return the truncated container
	 */
	@Nonnull
	@Override
	public Container limit(final int maxcardinality) {
		if (maxcardinality >= this.cardinality) {
			return clone();
		}
		if (maxcardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			ArrayContainer ac = new ArrayContainer(maxcardinality);
			int pos = 0;
			for (int k = 0; (ac.cardinality < maxcardinality) && (k < this.bitmap.length); ++k) {
				long bitset = this.bitmap[k];
				while ((ac.cardinality < maxcardinality) && (bitset != 0)) {
					ac.content[pos++] = (char) (k * 64 + numberOfTrailingZeros(bitset));
					ac.cardinality++;
					bitset &= (bitset - 1);
				}
			}
			return ac;
		}
		long[] newBitmap = new long[MAX_CAPACITY / 64];
		BitmapContainer bc = new BitmapContainer(newBitmap, maxcardinality);
		int s = (select(maxcardinality));
		int usedwords = (s + 63) / 64;
		System.arraycopy(this.bitmap, 0, newBitmap, 0, usedwords);
		int lastword = s % 64;
		if (lastword != 0) {
			bc.bitmap[s / 64] &= (0xFFFFFFFFFFFFFFFFL >>> (64 - lastword));
		}
		return bc;
	}

	/**
	 * Populates this (empty) bitmap from the values of `arrayContainer`, adopting its cardinality;
	 * inverse of {@link #toArrayContainer()}.
	 *
	 * @param arrayContainer source values
	 */
	void loadData(@Nonnull final ArrayContainer arrayContainer) {
		this.cardinality = arrayContainer.cardinality;
		for (int k = 0; k < arrayContainer.cardinality; ++k) {
			final char x = arrayContainer.content[k];
			this.bitmap[(x) / 64] |= (1L << x);
		}
	}

	/**
	 * Find the index of the next set bit greater or equal to i, returns -1 if none found.
	 *
	 * @param i starting index
	 * @return index of the next set bit
	 */
	public int nextSetBit(final int i) {
		int x = i >> 6;
		long w = this.bitmap[x];
		w >>>= i;
		if (w != 0) {
			return i + numberOfTrailingZeros(w);
		}
		for (++x; x < this.bitmap.length; ++x) {
			if (this.bitmap[x] != 0) {
				return x * 64 + numberOfTrailingZeros(this.bitmap[x]);
			}
		}
		return -1;
	}

	/**
	 * Find the index of the next clear bit greater or equal to i.
	 *
	 * @param i starting index
	 * @return index of the next clear bit
	 */
	private int nextClearBit(final int i) {
		int x = i >> 6;
		long w = ~this.bitmap[x];
		w >>>= i;
		if (w != 0) {
			return i + numberOfTrailingZeros(w);
		}
		for (++x; x < this.bitmap.length; ++x) {
			long map = ~this.bitmap[x];
			if (map != 0) {
				return x * 64 + numberOfTrailingZeros(map);
			}
		}
		return MAX_CAPACITY;
	}

	/**
	 * Returns a copy with every bit in `[firstOfRange, lastOfRange)` flipped; the receiver is
	 * unchanged.
	 *
	 * @param firstOfRange range start (inclusive)
	 * @param lastOfRange  range end (exclusive)
	 * @return the complemented container
	 */
	@Nonnull
	@Override
	public Container not(final int firstOfRange, final int lastOfRange) {
		BitmapContainer answer = clone();
		return answer.inot(firstOfRange, lastOfRange);
	}

	/**
	 * Counts maximal runs of consecutive set bits by scanning word boundaries in O(1024) word steps.
	 *
	 * @return the exact number of runs
	 */
	@Override
	int numberOfRuns() {
		int numRuns = 0;
		long nextWord = this.bitmap[0];

		for (int i = 0; i < this.bitmap.length - 1; i++) {
			long word = nextWord;
			nextWord = this.bitmap[i + 1];
			numRuns += Long.bitCount((~word) & (word << 1)) + (int) ((word >>> 63) & ~nextWord);
		}

		long word = nextWord;
		numRuns += Long.bitCount((~word) & (word << 1));
		if ((word & 0x8000000000000000L) != 0) {
			numRuns++;
		}

		return numRuns;
	}

	/**
	 * Computes the number of runs
	 *
	 * @return the number of runs
	 */
	public int numberOfRunsAdjustment() {
		int ans = 0;
		long nextWord = this.bitmap[0];
		for (int i = 0; i < this.bitmap.length - 1; i++) {
			final long word = nextWord;

			nextWord = this.bitmap[i + 1];
			ans += (int) ((word >>> 63) & ~nextWord);
		}
		final long word = nextWord;

		if ((word & 0x8000000000000000L) != 0) {
			ans++;
		}
		return ans;
	}

	/**
	 * Counts runs in the bitmap only until the total exceeds `mustNotExceed`, returning early once run
	 * optimization is clearly not worthwhile. The result is a lower bound, not the exact run count.
	 *
	 * @param mustNotExceed run count beyond which further counting is pointless
	 * @return a lower bound on the number of runs
	 */
	public int numberOfRunsLowerBound(final int mustNotExceed) {
		int numRuns = 0;

		for (int blockOffset = 0; blockOffset + BLOCKSIZE <= this.bitmap.length; blockOffset += BLOCKSIZE) {

			for (int i = blockOffset; i < blockOffset + BLOCKSIZE; i++) {
				long word = this.bitmap[i];
				numRuns += Long.bitCount((~word) & (word << 1));
			}
			if (numRuns > mustNotExceed) {
				return numRuns;
			}
		}
		return numRuns;
	}

	/**
	 * Returns a copy unioned with `value2`, promoting to the full {@link RunContainer} when saturated;
	 * the receiver is unchanged.
	 *
	 * @param value2 values to add
	 * @return the union container
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final ArrayContainer value2) {
		final BitmapContainer answer = clone();
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char v = value2.content[k];
			final int i = (v) >>> 6;
			long w = answer.bitmap[i];
			long aft = w | (1L << v);
			answer.bitmap[i] = aft;
			if (USE_BRANCHLESS) {
				answer.cardinality += (int) ((w - aft) >>> 63);
			} else {
				if (w != aft) {
					answer.cardinality++;
				}
			}
		}
		if (answer.isFull()) {
			return RunContainer.full();
		}
		return answer;
	}

	/**
	 * Returns `true` when every value in `[0, 65536)` is set.
	 */
	@Override
	public boolean isFull() {
		return this.cardinality == MAX_CAPACITY;
	}

	/**
	 * Returns a copy unioned with `value2`; the receiver is unchanged.
	 *
	 * @param value2 values to add
	 * @return the union container
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final BitmapContainer value2) {
		BitmapContainer value1 = this.clone();
		return value1.ior(value2);
	}

	/**
	 * Returns the union with a {@link RunContainer}; delegates to it.
	 *
	 * @param x values to add
	 * @return the union container
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final RunContainer x) {
		return x.or(this);
	}

	/**
	 * Find the index of the previous set bit less than or equal to i, returns -1 if none found.
	 *
	 * @param i starting index
	 * @return index of the previous set bit
	 */
	int prevSetBit(final int i) {
		int x = i >> 6; // i / 64 with sign extension
		long w = this.bitmap[x];
		w <<= 64 - i - 1;
		if (w != 0) {
			return i - Long.numberOfLeadingZeros(w);
		}
		for (--x; x >= 0; --x) {
			if (this.bitmap[x] != 0) {
				return x * 64 + 63 - Long.numberOfLeadingZeros(this.bitmap[x]);
			}
		}
		return -1;
	}

	/**
	 * Find the index of the previous clear bit less than or equal to i.
	 *
	 * @param i starting index
	 * @return index of the previous clear bit
	 */
	private int prevClearBit(final int i) {
		int x = i >> 6; // i / 64 with sign extension
		long w = ~this.bitmap[x];
		w <<= 64 - (i + 1);
		if (w != 0) {
			return i - Long.numberOfLeadingZeros(w);
		}
		for (--x; x >= 0; --x) {
			long map = ~this.bitmap[x];
			if (map != 0) {
				return x * 64 + 63 - Long.numberOfLeadingZeros(map);
			}
		}
		return -1;
	}

	/**
	 * Returns the number of set values less than or equal to `lowbits` (its 1-based rank), computed
	 * with up to O(1024) popcount steps.
	 *
	 * @param lowbits value whose rank is requested
	 * @return count of set values in `[0, lowbits]`
	 */
	@Override
	public int rank(final char lowbits) {
		int leftover = (lowbits + 1) & 63;
		int answer = 0;
		for (int k = 0; k < (lowbits + 1) >>> 6; ++k) {
			answer += Long.bitCount(this.bitmap[k]);
		}
		if (leftover != 0) {
			answer += Long.bitCount(this.bitmap[(lowbits + 1) >>> 6] << (64 - leftover));
		}
		return answer;
	}

	/**
	 * Restores the bitmap from Java serialization; delegates to {@link #deserialize(DataInput)}.
	 *
	 * @param in object input stream
	 * @throws IOException on read failure
	 */
	@Override
	public void readExternal(final ObjectInput in) throws IOException {
		deserialize(in);
	}

	/**
	 * Returns a copy with every value in `[begin, end)` removed, demoting to an {@link ArrayContainer}
	 * when the result is no longer dense; the receiver is unchanged.
	 *
	 * @param begin first value to clear (inclusive)
	 * @param end   end of the range (exclusive)
	 * @return the resulting container
	 * @throws IllegalArgumentException if the range is malformed or exceeds `[0, 65536)`
	 */
	@Nonnull
	@Override
	public Container remove(final int begin, final int end) {
		if (end == begin) {
			return clone();
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		BitmapContainer answer = clone();
		int prevOnesInRange = answer.cardinalityInRange(begin, end);
		Util.resetBitmapRange(answer.bitmap, begin, end);
		answer.updateCardinality(prevOnesInRange, 0);
		if (answer.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer.toArrayContainer();
		}
		return answer;
	}

	/**
	 * Clears bit `i` in place, demoting to an {@link ArrayContainer} when removal drops the
	 * cardinality to {@link ArrayContainer#DEFAULT_MAX_SIZE}.
	 *
	 * @param i value to remove
	 * @return this container, or the demoted {@link ArrayContainer}
	 */
	@Nonnull
	@Override
	public Container remove(final char i) {
		int index = i >>> 6;
		long bef = this.bitmap[index];
		long mask = 1L << i;
		if (this.cardinality == ArrayContainer.DEFAULT_MAX_SIZE + 1) { // this is
			// the
			// uncommon
			// path
			if ((bef & mask) != 0) {
				--this.cardinality;
				this.bitmap[i >>> 6] = bef & ~mask;
				return this.toArrayContainer();
			}
		}
		long aft = bef & ~mask;
		this.cardinality -= (aft - bef) >>> 63;
		this.bitmap[index] = aft;
		return this;
	}

	/**
	 * Recomputes {@link #cardinality} after a lazy operation left it as `-1`, then converts to an
	 * {@link ArrayContainer} if sparse or to the full {@link RunContainer} if saturated.
	 *
	 * @return this container, or the more appropriate representation
	 */
	@Nonnull
	@Override
	public Container repairAfterLazy() {
		if (getCardinality() < 0) {
			computeCardinality();
			if (getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				return this.toArrayContainer();
			} else if (isFull()) {
				return RunContainer.full();
			}
		}
		return this;
	}

	/**
	 * Converts to a {@link RunContainer} when its run-length encoding would be smaller than the 8 KB
	 * bitmap, otherwise returns this container unchanged.
	 *
	 * @return a {@link RunContainer} or this container, whichever is more compact
	 */
	@Nonnull
	@Override
	public Container runOptimize() {
		int numRuns = numberOfRunsLowerBound(MAXRUNS); // decent choice

		int sizeAsRunContainerLowerBound = RunContainer.serializedSizeInBytes(numRuns);

		if (sizeAsRunContainerLowerBound >= getArraySizeInBytes()) {
			return this;
		}
		// else numRuns is a relatively tight bound that needs to be exact
		// in some cases (or if we need to make the runContainer the right
		// size)
		numRuns += numberOfRunsAdjustment();
		int sizeAsRunContainer = RunContainer.serializedSizeInBytes(numRuns);

		if (getArraySizeInBytes() > sizeAsRunContainer) {
			return new RunContainer(this, numRuns);
		} else {
			return this;
		}
	}

	/**
	 * Returns the `j`-th smallest set value (0-based). Scans from whichever end of the bitmap is
	 * closer to `j`, so it costs at most O(1024) word steps.
	 *
	 * @param j 0-based index into the ascending sequence of set values
	 * @return the value at rank `j`
	 * @throws IllegalArgumentException if `j` is at or beyond the cardinality
	 */
	@Override
	public char select(final int j) {
		if ( // cardinality != -1 && // omitted as (-1>>>1) > j as j < (1<<16)
			this.cardinality >>> 1 < j && j < this.cardinality) {
			int leftover = this.cardinality - j;
			for (int k = this.bitmap.length - 1; k >= 0; --k) {
				long w = this.bitmap[k];
				if (w != 0) {
					int bits = Long.bitCount(w);
					if (bits >= leftover) {
						return (char) (k * 64 + Util.select(w, bits - leftover));
					}
					leftover -= bits;
				}
			}
		} else {
			int leftover = j;
			for (int k = 0; k < this.bitmap.length; ++k) {
				long w = this.bitmap[k];
				if (w != 0) {
					int bits = Long.bitCount(this.bitmap[k]);
					if (bits > leftover) {
						return (char) (k * 64 + Util.select(this.bitmap[k], leftover));
					}
					leftover -= bits;
				}
			}
		}
		throw new IllegalArgumentException("Insufficient cardinality.");
	}

	/**
	 * Writes the 1024 words in little-endian byte order to `out`.
	 *
	 * @param out destination stream
	 * @throws IOException on write failure
	 */
	@Override
	public void serialize(@Nonnull final DataOutput out) throws IOException {
		// little endian
		for (long w : this.bitmap) {
			out.writeLong(Long.reverseBytes(w));
		}
	}

	/**
	 * Returns the on-disk size of the serialized bitmap in bytes (always 8192).
	 */
	@Override
	public int serializedSizeInBytes() {
		return serializedSizeInBytes(0);
	}

	/**
	 * Copies the data to an array container
	 *
	 * @return the array container
	 */
	@Nonnull
	ArrayContainer toArrayContainer() {
		ArrayContainer ac = new ArrayContainer(this.cardinality);
		if (this.cardinality != 0) {
			ac.loadData(this);
		}
		if (ac.getCardinality() != this.cardinality) {
			throw new RuntimeException("Internal error.");
		}
		return ac;
	}

	/**
	 * Renders the set values as a comma-separated list inside braces, e.g. `{1,2,7}`.
	 */
	@Nonnull
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("{}".length() + "-123456789,".length() * 256);
		final CharIterator i = this.getCharIterator();
		sb.append('{');
		while (i.hasNext()) {
			sb.append((int) (i.next()));
			if (i.hasNext()) {
				sb.append(',');
			}
		}
		sb.append('}');
		return sb.toString();
	}

	/**
	 * No-op: the bitmap is fixed-size and has no slack capacity to release.
	 */
	@Override
	public void trim() {
	}

	/**
	 * Writes the raw bitmap words to `out`; equivalent to {@link #serialize(DataOutput)}.
	 *
	 * @param out destination stream
	 * @throws IOException on write failure
	 */
	@Override
	public void writeArray(@Nonnull final DataOutput out) throws IOException {
		serialize(out);
	}

	/**
	 * Copies the bitmap words into `buffer` (which must be little-endian) and advances its position
	 * past them.
	 *
	 * @param buffer little-endian destination, positioned where the words should be written
	 */
	@Override
	public void writeArray(@Nonnull final ByteBuffer buffer) {
		assert buffer.order() == ByteOrder.LITTLE_ENDIAN;
		LongBuffer buf = buffer.asLongBuffer();
		buf.put(this.bitmap);
		int bytesWritten = this.bitmap.length * 8;
		buffer.position(buffer.position() + bytesWritten);
	}

	/**
	 * Writes the bitmap for Java serialization; delegates to {@link #serialize(DataOutput)}.
	 *
	 * @param out object output stream
	 * @throws IOException on write failure
	 */
	@Override
	public void writeExternal(final ObjectOutput out) throws IOException {
		serialize(out);
	}

	/**
	 * Returns a copy XOR-ed with `value2` (symmetric difference), demoting to an {@link ArrayContainer}
	 * when the result is no longer dense; the receiver is unchanged.
	 *
	 * @param value2 values to toggle
	 * @return the resulting container
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final ArrayContainer value2) {
		final BitmapContainer answer = clone();
		int c = value2.cardinality;
		for (int k = 0; k < c; ++k) {
			char vc = value2.content[k];
			final int index = vc >>> 6;
			final long mask = 1L << vc;
			final long val = answer.bitmap[index];
			// check whether a branchy version could be faster
			answer.cardinality += (int) (1 - 2 * ((val & mask) >>> vc));
			answer.bitmap[index] = val ^ mask;
		}
		if (answer.cardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer.toArrayContainer();
		}
		return answer;
	}

	/**
	 * Returns the symmetric difference with `value2` via 1024 word XORs, demoting to an
	 * {@link ArrayContainer} when the result is no longer dense.
	 *
	 * @param value2 values to toggle
	 * @return the resulting container
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final BitmapContainer value2) {
		int newCardinality = 0;
		for (int k = 0; k < this.bitmap.length; ++k) {
			newCardinality += Long.bitCount(this.bitmap[k] ^ value2.bitmap[k]);
		}
		if (newCardinality > ArrayContainer.DEFAULT_MAX_SIZE) {
			final BitmapContainer answer = new BitmapContainer();
			for (int k = 0; k < answer.bitmap.length; ++k) {
				answer.bitmap[k] = this.bitmap[k] ^ value2.bitmap[k];
			}
			answer.cardinality = newCardinality;
			return answer;
		}
		ArrayContainer ac = new ArrayContainer(newCardinality);
		Util.fillArrayXOR(ac.content, this.bitmap, value2.bitmap);
		ac.cardinality = newCardinality;
		return ac;
	}

	/**
	 * Returns the symmetric difference with a {@link RunContainer}; delegates to it.
	 *
	 * @param x values to toggle
	 * @return the resulting container
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final RunContainer x) {
		return x.xor(this);
	}

	/**
	 * Invokes `ic` for every set value, reconstructing the full 32-bit key by prefixing the high 16
	 * bits `msb`.
	 *
	 * @param msb high 16 bits (chunk key) OR-ed onto each 16-bit value
	 * @param ic  consumer receiving each reconstructed value
	 */
	@Override
	public void forEach(final char msb, @Nonnull final IntConsumer ic) {
		int high = msb << 16;
		for (int x = 0; x < this.bitmap.length; ++x) {
			long w = this.bitmap[x];
			while (w != 0) {
				ic.accept(((x << 6) + numberOfTrailingZeros(w)) | high);
				w &= (w - 1);
			}
		}
	}

	/**
	 * Reports the presence of every value in the chunk to `rrc`, with each callback position shifted
	 * by `offset`.
	 *
	 * @param offset base position added to each value in callbacks
	 * @param rrc    consumer receiving present/absent notifications for the whole chunk
	 */
	@Override
	public void forAll(final int offset, @Nonnull final RelativeRangeConsumer rrc) {
		for (int wordIndex = 0; wordIndex < this.bitmap.length; wordIndex++) {
			long word = this.bitmap[wordIndex];
			int bufferWordStart = offset + (wordIndex << 6);
			int bufferWordEnd = bufferWordStart + 64;
			addWholeWordToRangeConsumer(word, bufferWordStart, bufferWordEnd, rrc);
		}
	}

	/**
	 * Reports the presence of every value from `startValue` (inclusive) to the end of the chunk, with
	 * callback positions relative to `startValue`.
	 *
	 * @param startValue first value to report (inclusive)
	 * @param rrc        consumer receiving present/absent notifications
	 */
	@Override
	public void forAllFrom(final char startValue, @Nonnull final RelativeRangeConsumer rrc) {
		int startIndex = startValue >>> 6;
		for (int wordIndex = startIndex; wordIndex < this.bitmap.length; wordIndex++) {
			long word = this.bitmap[wordIndex];
			int wordStart = wordIndex << 6;
			int wordEnd = wordStart + 64;
			if (wordStart < startValue) {
				// startValue is in the middle of the word
				// some special cases for efficiency
				if (word == 0) {
					rrc.acceptAllAbsent(0, wordEnd - startValue);
				} else if (word == -1) { // all 1s
					rrc.acceptAllPresent(0, wordEnd - startValue);
				} else {
					int nextPos = startValue;
					while (word != 0) {
						int pos = wordStart + numberOfTrailingZeros(word);
						if (nextPos < pos) {
							rrc.acceptAllAbsent(nextPos - startValue, pos - startValue);
							rrc.acceptPresent(pos - startValue);
							nextPos = pos + 1;
						} else if (nextPos == pos) {
							rrc.acceptPresent(pos - startValue);
							nextPos++;
						} // else just we out before startValue, so ignore
						word &= (word - 1);
					}
					if (nextPos < wordEnd) {
						rrc.acceptAllAbsent(nextPos - startValue, wordEnd - startValue);
					}
				}
			} else {
				// startValue is aligned with word
				addWholeWordToRangeConsumer(word, wordStart - startValue, wordEnd - startValue, rrc);
			}
		}
	}

	/**
	 * Reports the presence of every value from the start of the chunk up to `endValue` (exclusive),
	 * with callback positions shifted by `offset`.
	 *
	 * @param offset   base position added to each value in callbacks
	 * @param endValue first value not reported (exclusive)
	 * @param rrc      consumer receiving present/absent notifications
	 */
	@Override
	public void forAllUntil(final int offset, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		int bufferEndPos = offset + endValue;
		for (int wordIndex = 0; wordIndex < this.bitmap.length; wordIndex++) {
			long word = this.bitmap[wordIndex];
			int bufferWordStart = offset + (wordIndex << 6);
			int bufferWordEnd = bufferWordStart + 64;
			if (bufferWordStart >= bufferEndPos) {
				return;
			}
			if (bufferEndPos < bufferWordEnd) {
				// we end on this word

				// some special cases for efficiency
				if (word == 0) {
					rrc.acceptAllAbsent(bufferWordStart, bufferEndPos);
				} else if (word == -1) { // all 1s
					rrc.acceptAllPresent(bufferWordStart, bufferEndPos);
				} else {
					int nextPos = bufferWordStart;
					while (word != 0) {
						int pos = bufferWordStart + numberOfTrailingZeros(word);
						if (bufferEndPos <= pos) {
							// we've moved past the end
							if (nextPos < bufferEndPos) {
								rrc.acceptAllAbsent(nextPos, bufferEndPos);
							}
							return;
						}
						if (nextPos < pos) {
							rrc.acceptAllAbsent(nextPos, pos);
							nextPos = pos;
						}
						rrc.acceptPresent(pos);
						nextPos++;
						word &= (word - 1);
					}
					if (nextPos < bufferEndPos) {
						rrc.acceptAllAbsent(nextPos, bufferEndPos);
					}
					return;
				}
			} else {
				addWholeWordToRangeConsumer(word, bufferWordStart, bufferWordEnd, rrc);
			}
		}
	}

	/**
	 * Reports the presence of every value in the half-open range `[startValue, endValue)`, with
	 * callback positions relative to `startValue`.
	 *
	 * @param startValue range start (inclusive)
	 * @param endValue   range end (exclusive)
	 * @param rrc        consumer receiving present/absent notifications
	 * @throws IllegalArgumentException if `endValue` is not greater than `startValue`
	 */
	@Override
	public void forAllInRange(final char startValue, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		if (endValue <= startValue) {
			throw new IllegalArgumentException(
				"startValue (" + startValue + ") must be less than endValue (" + endValue + ")");
		}
		int startIndex = startValue >>> 6;
		for (int wordIndex = startIndex; wordIndex < this.bitmap.length; wordIndex++) {
			long word = this.bitmap[wordIndex];
			int wordStart = wordIndex << 6;
			int wordEndExclusive = wordStart + 64;

			if (wordStart >= endValue) {
				return;
			}

			boolean startInWord = wordStart < startValue;
			boolean endInWord = endValue < wordEndExclusive;
			boolean wordAllZeroes = word == 0;
			boolean wordAllOnes = word == -1;

			if (startInWord && endInWord) {
				if (wordAllZeroes) {
					rrc.acceptAllAbsent(0, endValue - startValue);
				} else if (wordAllOnes) {
					rrc.acceptAllPresent(0, endValue - startValue);
				} else {
					int nextPos = startValue;
					while (word != 0) {
						int pos = wordStart + numberOfTrailingZeros(word);
						if (endValue <= pos) {
							// we've moved past the end
							if (nextPos < endValue) {
								rrc.acceptAllAbsent(nextPos - startValue, endValue - startValue);
							}
							return;
						}
						if (nextPos < pos) {
							rrc.acceptAllAbsent(nextPos - startValue, pos - startValue);
							rrc.acceptPresent(pos - startValue);
							nextPos = pos + 1;
						} else if (nextPos == pos) {
							rrc.acceptPresent(pos - startValue);
							nextPos++;
						}
						word &= (word - 1);
					}
					if (nextPos < endValue) {
						rrc.acceptAllAbsent(nextPos - startValue, endValue - startValue);
					}
				}
				return;
			} else if (startInWord) {
				if (wordAllZeroes) {
					rrc.acceptAllAbsent(0, 64 - (startValue - wordStart));
				} else if (wordAllOnes) {
					rrc.acceptAllPresent(0, 64 - (startValue - wordStart));
				} else {
					int nextPos = startValue;
					while (word != 0) {
						int pos = wordStart + numberOfTrailingZeros(word);
						if (nextPos < pos) {
							rrc.acceptAllAbsent(nextPos - startValue, pos - startValue);
							rrc.acceptPresent(pos - startValue);
							nextPos = pos + 1;
						} else if (nextPos == pos) {
							rrc.acceptPresent(pos - startValue);
							nextPos++;
						}
						word &= (word - 1);
					}
					if (nextPos < wordEndExclusive) {
						rrc.acceptAllAbsent(nextPos - startValue, wordEndExclusive - startValue);
					}
				}
			} else if (endInWord) {
				if (wordAllZeroes) {
					rrc.acceptAllAbsent(wordStart - startValue, endValue - startValue);
				} else if (wordAllOnes) {
					rrc.acceptAllPresent(wordStart - startValue, endValue - startValue);
				} else {
					int nextPos = wordStart;
					while (word != 0) {
						int pos = wordStart + numberOfTrailingZeros(word);
						if (endValue <= pos) {
							// we've moved past the end
							if (nextPos < endValue) {
								rrc.acceptAllAbsent(nextPos - startValue, endValue - startValue);
							}
							return;
						}
						if (nextPos < pos) {
							rrc.acceptAllAbsent(nextPos - startValue, pos - startValue);
							nextPos = pos;
						}
						rrc.acceptPresent(pos - startValue);
						nextPos++;
						word &= (word - 1);
					}
					if (nextPos < endValue) {
						rrc.acceptAllAbsent(nextPos - startValue, endValue - startValue);
					}
				}
				return;
			} else {
				addWholeWordToRangeConsumer(
					word, wordStart - startValue, wordEndExclusive - startValue, rrc);
			}
		}
	}

	/**
	 * Reports the present/absent pattern of a single 64-bit word to `rrc`, fast-pathing all-zero and
	 * all-one words.
	 *
	 * @param word            the word whose bits are reported
	 * @param bufferWordStart consumer-relative position of the word's first bit
	 * @param bufferWordEnd   consumer-relative position just past the word's last bit
	 * @param rrc             consumer receiving present/absent notifications
	 */
	private static void addWholeWordToRangeConsumer(
		long word, final int bufferWordStart, final int bufferWordEnd, @Nonnull final RelativeRangeConsumer rrc) {
		// some special cases for efficiency
		if (word == 0) {
			rrc.acceptAllAbsent(bufferWordStart, bufferWordEnd);
		} else if (word == -1) { // all 1s
			rrc.acceptAllPresent(bufferWordStart, bufferWordEnd);
		} else {
			int nextPos = bufferWordStart;
			while (word != 0) {
				int pos = bufferWordStart + numberOfTrailingZeros(word);
				if (nextPos < pos) {
					rrc.acceptAllAbsent(nextPos, pos);
					nextPos = pos;
				}
				rrc.acceptPresent(pos);
				nextPos++;
				word &= (word - 1);
			}
			if (nextPos < bufferWordEnd) {
				rrc.acceptAllAbsent(nextPos, bufferWordEnd);
			}
		}
	}

	/**
	 * Returns this container unchanged; it is already a {@link BitmapContainer}.
	 */
	@Nonnull
	@Override
	public BitmapContainer toBitmapContainer() {
		return this;
	}

	/**
	 * Copies all 1024 bitmap words into `words` starting at `position`.
	 *
	 * @param words    destination word array
	 * @param position first index written in `words`
	 */
	@Override
	public void copyBitmapTo(@Nonnull final long[] words, final int position) {
		System.arraycopy(this.bitmap, 0, words, position, this.bitmap.length);
	}

	/**
	 * Copies the first `length` bitmap words into `words` starting at `position`.
	 *
	 * @param words    destination word array
	 * @param position first index written in `words`
	 * @param length   number of leading words to copy
	 */
	public void copyBitmapTo(@Nonnull final long[] words, final int position, final int length) {
		System.arraycopy(this.bitmap, 0, words, position, length);
	}

	/**
	 * Returns the smallest set value greater than or equal to `fromValue`, or `-1` if none.
	 *
	 * @param fromValue lower bound (inclusive)
	 * @return next set value, or `-1`
	 */
	@Override
	public int nextValue(final char fromValue) {
		return nextSetBit((fromValue));
	}

	/**
	 * Returns the largest set value less than or equal to `fromValue`, or `-1` if none.
	 *
	 * @param fromValue upper bound (inclusive)
	 * @return previous set value, or `-1`
	 */
	@Override
	public int previousValue(final char fromValue) {
		return prevSetBit((fromValue));
	}

	/**
	 * Returns the smallest absent (clear) value greater than or equal to `fromValue`.
	 *
	 * @param fromValue lower bound (inclusive)
	 * @return next clear value (never `-1`; may equal {@link #MAX_CAPACITY})
	 */
	@Override
	public int nextAbsentValue(final char fromValue) {
		return nextClearBit((fromValue));
	}

	/**
	 * Returns the largest absent (clear) value less than or equal to `fromValue`, or `-1` if none.
	 *
	 * @param fromValue upper bound (inclusive)
	 * @return previous clear value, or `-1`
	 */
	@Override
	public int previousAbsentValue(final char fromValue) {
		return prevClearBit((fromValue));
	}

	/**
	 * Returns the smallest set value.
	 *
	 * @return the first set value
	 * @throws java.util.NoSuchElementException if the container is empty
	 */
	@Override
	public int first() {
		assertNonEmpty(this.cardinality == 0);
		int i = 0;
		while (i < this.bitmap.length - 1 && this.bitmap[i] == 0) {
			++i; // seek forward
		}
		// sizeof(long) * #empty words at start + number of bits preceding the first bit set
		return i * 64 + numberOfTrailingZeros(this.bitmap[i]);
	}

	/**
	 * Returns the largest set value.
	 *
	 * @return the last set value
	 * @throws java.util.NoSuchElementException if the container is empty
	 */
	@Override
	public int last() {
		assertNonEmpty(this.cardinality == 0);
		int i = this.bitmap.length - 1;
		while (i > 0 && this.bitmap[i] == 0) {
			--i; // seek backward
		}
		// sizeof(long) * #words from start - number of bits after the last bit set
		return (i + 1) * 64 - Long.numberOfLeadingZeros(this.bitmap[i]) - 1;
	}
}

/**
 * Forward {@link PeekableCharIterator} over a {@link BitmapContainer}'s `long[]` bitmap: yields set
 * values in ascending order by walking the words and clearing the lowest set bit at each step.
 */
class BitmapContainerCharIterator implements PeekableCharIterator {

	/**
	 * Residual bits of the current word; its lowest set bit locates the next value to emit.
	 */
	long w;
	/**
	 * Index of the current word within {@link #bitmap}.
	 */
	int x;

	/**
	 * Bitmap being iterated; not copied, so it must not be mutated during iteration.
	 */
	long[] bitmap;

	/**
	 * Creates an unbound iterator; {@link #wrap(long[])} must be called before use.
	 */
	BitmapContainerCharIterator() {
	}

	/**
	 * Creates an iterator positioned at the first set value of `p`.
	 *
	 * @param p bitmap to iterate over
	 */
	BitmapContainerCharIterator(@Nonnull final long[] p) {
		wrap(p);
	}

	/**
	 * Returns a shallow copy of the iteration cursor. The clone-unsupported path is unreachable
	 * because this iterator implements {@link Cloneable}.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator clone() {
		try {
			return (PeekableCharIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e); // unreachable, this iterator implements Cloneable
		}
	}

	/**
	 * Returns `true` while an unemitted set value remains.
	 */
	@Override
	public boolean hasNext() {
		return this.x < this.bitmap.length;
	}

	/**
	 * Returns the next set value in ascending order, clearing its bit from the current word.
	 */
	@Override
	public char next() {
		char answer = (char) (this.x * 64 + numberOfTrailingZeros(this.w));
		this.w &= (this.w - 1);
		while (this.w == 0) {
			++this.x;
			if (this.x == this.bitmap.length) {
				break;
			}
			this.w = this.bitmap[this.x];
		}
		return answer;
	}

	/**
	 * Returns the next set value widened to `int`.
	 */
	@Override
	public int nextAsInt() {
		return (next());
	}

	/**
	 * Read-only cursor: in-place element removal is not supported.
	 */
	@Override
	public void remove() {
		throw new RuntimeException("unsupported operation: remove");
	}

	/**
	 * Rebinds this iterator to `b` and positions it at the first set value.
	 *
	 * @param b bitmap to iterate over (not copied)
	 */
	public void wrap(@Nonnull final long[] b) {
		this.bitmap = b;
		for (this.x = 0; this.x < this.bitmap.length; ++this.x) {
			if ((this.w = this.bitmap[this.x]) != 0) {
				break;
			}
		}
	}

	/**
	 * Fast-forwards past all set values below `minval` so the next emitted value is at least `minval`.
	 *
	 * @param minval inclusive lower bound to advance to
	 */
	@Override
	public void advanceIfNeeded(final char minval) {
		if (!hasNext()) {
			return;
		}
		if (minval >= this.x * 64) {
			if (minval >= (this.x + 1) * 64) {
				this.x = minval / 64;
				this.w = this.bitmap[this.x];
			}
			this.w &= ~0L << (minval & 63);
			while (this.w == 0) {
				this.x++;
				if (!hasNext()) {
					return;
				}
				this.w = this.bitmap[this.x];
			}
		}
	}

	/**
	 * Returns the next set value without consuming it.
	 */
	@Override
	public char peekNext() {
		return (char) (this.x * 64 + numberOfTrailingZeros(this.w));
	}
}

/**
 * {@link BitmapContainerCharIterator} that additionally tracks the 1-based rank of the next value,
 * so callers can obtain a value and its position in the set in one pass.
 */
final class BitmapContainerCharRankIterator extends BitmapContainerCharIterator
	implements PeekableCharRankIterator {
	/**
	 * Rank (1-based) of the value that {@link #peekNext()} would return next.
	 */
	private int nextRank = 1;

	/**
	 * Creates a rank-aware iterator positioned at the first set value of `p`.
	 *
	 * @param p bitmap to iterate over
	 */
	BitmapContainerCharRankIterator(@Nonnull final long[] p) {
		super(p);
	}

	/**
	 * Returns the 1-based rank of the value that {@link #next()} would return.
	 */
	@Override
	public int peekNextRank() {
		return this.nextRank;
	}

	/**
	 * Emits the next value and advances the tracked rank.
	 */
	@Override
	public char next() {
		++this.nextRank;
		return super.next();
	}

	/**
	 * Fast-forwards to `minval`, keeping {@link #nextRank} in step by counting the skipped bits.
	 */
	@Override
	public void advanceIfNeeded(final char minval) {
		if (!hasNext()) {
			return;
		}
		if (minval >= this.x * 64) {
			if (minval >= (this.x + 1) * 64) {
				int nextX = minval / 64;
				this.nextRank += bitCount(this.w);
				for (this.x = this.x + 1; this.x < nextX; this.x++) {
					this.w = this.bitmap[this.x];
					this.nextRank += bitCount(this.w);
				}
				this.w = this.bitmap[nextX];
			}
			this.nextRank += bitCount(this.w);
			this.w &= ~0L << (minval & 63);
			this.nextRank -= bitCount(this.w);
			while (this.w == 0) {
				++this.x;
				if (!hasNext()) {
					return;
				}
				this.w = this.bitmap[this.x];
			}
		}
	}

	/**
	 * Returns a shallow copy of the iteration cursor, preserving the tracked rank.
	 */
	@Nonnull
	@Override
	public PeekableCharRankIterator clone() {
		return (PeekableCharRankIterator) super.clone();
	}
}

/**
 * Reverse {@link PeekableCharIterator} over a {@link BitmapContainer}'s `long[]` bitmap: yields set
 * values in descending order by walking words from the top and clearing the highest set bit each
 * step.
 */
final class ReverseBitmapContainerCharIterator implements PeekableCharIterator {

	/**
	 * Residual bits of the current word; its highest set bit locates the next value to emit.
	 */
	long word;
	/**
	 * Index of the current word within {@link #bitmap}; `-1` once exhausted.
	 */
	int position;

	/**
	 * Bitmap being iterated; not copied, so it must not be mutated during iteration.
	 */
	long[] bitmap;

	/**
	 * Creates an unbound iterator; {@link #wrap(long[])} must be called before use.
	 */
	ReverseBitmapContainerCharIterator() {
	}

	/**
	 * Creates an iterator positioned at the largest set value of `bitmap`.
	 *
	 * @param bitmap bitmap to iterate over
	 */
	ReverseBitmapContainerCharIterator(@Nonnull final long[] bitmap) {
		wrap(bitmap);
	}

	/**
	 * Returns a shallow copy of the iteration cursor. The clone-unsupported path is unreachable
	 * because this iterator implements {@link Cloneable}.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator clone() {
		try {
			return (PeekableCharIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e); // unreachable, this iterator implements Cloneable
		}
	}

	/**
	 * Returns `true` while an unemitted set value remains.
	 */
	@Override
	public boolean hasNext() {
		return this.position >= 0;
	}

	/**
	 * Returns the next set value in descending order, clearing its bit from the current word.
	 */
	@Override
	public char next() {
		int shift = Long.numberOfLeadingZeros(this.word) + 1;
		char answer = (char) ((this.position + 1) * 64 - shift);
		this.word &= (0xFFFFFFFFFFFFFFFEL >>> shift);
		while (this.word == 0) {
			--this.position;
			if (this.position < 0) {
				break;
			}
			this.word = this.bitmap[this.position];
		}
		return answer;
	}

	/**
	 * Returns the next set value widened to `int`.
	 */
	@Override
	public int nextAsInt() {
		return next();
	}

	/**
	 * Fast-backwards past all set values above `maxval` so the next emitted value is at most `maxval`.
	 *
	 * @param maxval inclusive upper bound to retreat to
	 */
	@Override
	public void advanceIfNeeded(final char maxval) {
		if (maxval < (this.position + 1) * 64) {
			if (maxval < this.position * 64) {
				this.position = maxval / 64;
			}
			long currentWord = this.bitmap[this.position];
			currentWord &= ~0L >>> (63 - (maxval & 63));
			// Descend through empty words, loading word 0 too (the upstream loop broke at position 0
			// before reading bitmap[0], discarding any value stored there).
			while (currentWord == 0 && this.position > 0) {
				this.position--;
				currentWord = this.bitmap[this.position];
			}
			this.word = currentWord;
			if (currentWord == 0) {
				// every remaining value lies above maxval; mark the iterator exhausted
				this.position = -1;
			}
		}
	}

	/**
	 * Returns the next set value without consuming it.
	 */
	@Override
	public char peekNext() {
		int shift = Long.numberOfLeadingZeros(this.word) + 1;
		return (char) ((this.position + 1) * 64 - shift);
	}

	@Override
	public void remove() {
		throw new RuntimeException("unsupported operation: remove");
	}

	/**
	 * Rebinds this iterator to `b` and positions it at the largest set value.
	 *
	 * @param b bitmap to iterate over (not copied)
	 */
	void wrap(@Nonnull final long[] b) {
		this.bitmap = b;
		for (this.position = this.bitmap.length - 1; this.position >= 0; --this.position) {
			if ((this.word = this.bitmap[this.position]) != 0) {
				break;
			}
		}
	}
}
