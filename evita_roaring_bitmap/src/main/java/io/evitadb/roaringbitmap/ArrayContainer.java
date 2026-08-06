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
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Sparse {@link Container} implementation: one 65536-wide chunk of a Roaring bitmap stored as a
 * sorted, duplicate-free array of unsigned 16-bit keys (`char[]`).
 *
 * This representation is chosen when a chunk holds relatively few values. Below
 * {@link #DEFAULT_MAX_SIZE} (4096) values a plain sorted array is both smaller and faster to scan
 * than a full {@link BitmapContainer} (dense `long[1024]`); once a mutating operation would push
 * the cardinality past that bound the container promotes itself to a `BitmapContainer` rather than
 * growing the array further. A {@link RunContainer} is preferred instead when the values form long
 * consecutive runs.
 *
 * Representation invariant: `content[0 .. cardinality-1]` is strictly ascending (sorted and
 * distinct) under unsigned 16-bit ordering, and `0 <= cardinality <= DEFAULT_MAX_SIZE`. The backing
 * array may be longer than `cardinality`; the trailing slots are unused scratch capacity.
 */
public final class ArrayContainer extends Container implements Cloneable {
	/**
	 * Initial backing-array capacity for a freshly created empty container, before any growth.
	 */
	private static final int DEFAULT_INIT_SIZE = 4;
	/**
	 * Combined-cardinality threshold above which a lazy union promotes to a {@link BitmapContainer}
	 * rather than merging into another array.
	 */
	private static final int ARRAY_LAZY_LOWERBOUND = 1024;

	/**
	 * Maximum cardinality kept as an `ArrayContainer`; at or above this the container is promoted to
	 * a denser {@link BitmapContainer}. A chunk holding this many values or fewer is cheaper as a
	 * sorted array than as a bitmap.
	 */
	static final int DEFAULT_MAX_SIZE = 4096; // containers with DEFAULT_MAX_SZE or less integers
	// should be ArrayContainers

	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Serialized byte size of an array container of the given cardinality: two bytes per value plus a
	 * two-byte cardinality header.
	 *
	 * @param cardinality number of stored values
	 * @return size in bytes of the serialized form
	 */
	protected static int serializedSizeInBytes(final int cardinality) {
		return cardinality * 2 + 2;
	}

	/**
	 * Number of values currently stored, i.e. the length of the valid prefix of {@link #content}.
	 * Always in `[0, DEFAULT_MAX_SIZE]` for a well-formed container.
	 */
	protected int cardinality = 0;

	/**
	 * Backing store of set bits as unsigned 16-bit keys, kept strictly ascending in
	 * `content[0 .. cardinality-1]`. May be allocated larger than {@link #cardinality}; slots beyond
	 * it are unused capacity.
	 */
	@Nonnull char[] content;

	/**
	 * Create an array container with default capacity
	 */
	public ArrayContainer() {
		this(DEFAULT_INIT_SIZE);
	}

	/**
	 * Creates an empty array container with the default initial capacity.
	 *
	 * @return a new empty container
	 */
	@Nonnull
	public static ArrayContainer empty() {
		return new ArrayContainer();
	}

	/**
	 * Create an array container with specified capacity
	 *
	 * @param capacity The capacity of the container
	 */
	public ArrayContainer(final int capacity) {
		this.content = new char[capacity];
	}

	/**
	 * Create an array container with a run of ones from firstOfRun to lastOfRun, inclusive. Caller is
	 * responsible for making sure the range is small enough that ArrayContainer is appropriate.
	 *
	 * @param firstOfRun first index
	 * @param lastOfRun  last index (range is exclusive)
	 */
	public ArrayContainer(final int firstOfRun, final int lastOfRun) {
		final int valuesInRange = lastOfRun - firstOfRun;
		this.content = new char[valuesInRange];
		for (int i = 0; i < valuesInRange; ++i) {
			this.content[i] = (char) (firstOfRun + i);
		}
		this.cardinality = valuesInRange;
	}

	/**
	 * Create a new container from existing values array. This copies the data.
	 *
	 * @param newCard    desired cardinality
	 * @param newContent actual values (length should equal or exceed cardinality)
	 */
	public ArrayContainer(final int newCard, @Nonnull final char[] newContent) {
		this.cardinality = newCard;
		this.content = Arrays.copyOf(newContent, newCard);
	}

	/**
	 * Wraps an existing values array directly, without copying; the caller must not mutate
	 * `newContent` afterwards and must guarantee it is already sorted and distinct. Cardinality is
	 * taken to be the full array length.
	 *
	 * @param newContent backing array of values in ascending order
	 */
	public ArrayContainer(@Nonnull final char[] newContent) {
		this.cardinality = newContent.length;
		this.content = newContent;
	}

	/**
	 * Returns a new container with every value in `[begin, end)` added, promoting to a
	 * {@link BitmapContainer} if the result would exceed {@link #DEFAULT_MAX_SIZE}. This container is
	 * left unchanged. Runs in O(n): binary search for the range bounds plus array copies.
	 *
	 * @throws IllegalArgumentException if the range is malformed or ends beyond the 16-bit universe
	 */
	@Nonnull
	@Override
	public Container add(final int begin, final int end) {
		if (end == begin) {
			return clone();
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		// TODO: may need to convert to a RunContainer
		int indexstart = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) begin);
		if (indexstart < 0) {
			indexstart = -indexstart - 1;
		}
		int indexend = Util.unsignedBinarySearch(this.content, indexstart, this.cardinality, (char) (end - 1));
		if (indexend < 0) {
			indexend = -indexend - 1;
		} else {
			indexend++;
		}
		int rangelength = end - begin;
		int newcardinality = indexstart + (this.cardinality - indexend) + rangelength;
		if (newcardinality > DEFAULT_MAX_SIZE) {
			BitmapContainer a = this.toBitmapContainer();
			return a.iadd(begin, end);
		}
		ArrayContainer answer = new ArrayContainer(newcardinality, this.content);
		System.arraycopy(
			this.content, indexend, answer.content, indexstart + rangelength, this.cardinality - indexend);
		for (int k = 0; k < rangelength; ++k) {
			answer.content[k + indexstart] = (char) (begin + k);
		}
		answer.cardinality = newcardinality;
		return answer;
	}

	/**
	 * Adds a single value, promoting to a {@link BitmapContainer} once {@link #DEFAULT_MAX_SIZE} is
	 * reached. Appending a value above the current maximum is O(1) amortized; an out-of-order
	 * insertion costs O(log n) to locate the slot plus O(n) to shift the tail.
	 *
	 * @param x value to add (unsigned 16-bit)
	 * @return this container, or a promoted {@link BitmapContainer}
	 */
	@Nonnull
	@Override
	public Container add(final char x) {
		if (this.cardinality == 0 || (this.cardinality > 0 && (x) > (this.content[this.cardinality - 1]))) {
			if (this.cardinality >= DEFAULT_MAX_SIZE) {
				return toBitmapContainer().add(x);
			}
			if (this.cardinality >= this.content.length) {
				increaseCapacity();
			}
			this.content[this.cardinality++] = x;
		} else {
			int loc = Util.unsignedBinarySearch(this.content, 0, this.cardinality, x);
			if (loc < 0) {
				// Transform the ArrayContainer to a BitmapContainer
				// when cardinality = DEFAULT_MAX_SIZE
				if (this.cardinality >= DEFAULT_MAX_SIZE) {
					return toBitmapContainer().add(x);
				}
				if (this.cardinality >= this.content.length) {
					increaseCapacity();
				}
				// insertion : shift the elements > x by one position to
				// the right
				// and put x in it's appropriate place
				System.arraycopy(this.content, -loc - 1, this.content, -loc, this.cardinality + loc + 1);
				this.content[-loc - 1] = x;
				++this.cardinality;
			}
		}
		return this;
	}

	/**
	 * Returns the iterator's next value, or -1 once it is exhausted. The -1 sentinel drives the merge
	 * loop in {@link #or(CharIterator, boolean)}.
	 */
	private static int advance(@Nonnull final CharIterator it) {
		if (it.hasNext()) {
			return (it.next());
		} else {
			return -1;
		}
	}

	/**
	 * Intersection with another array container as a new container; O(n + m) sorted merge.
	 */
	@Nonnull
	@Override
	public ArrayContainer and(@Nonnull final ArrayContainer value2) {
		ArrayContainer value1 = this;
		final int desiredCapacity = Math.min(value1.getCardinality(), value2.getCardinality());
		ArrayContainer answer = new ArrayContainer(desiredCapacity);
		answer.cardinality =
			Util.unsignedIntersect2by2(
				value1.content,
				value1.getCardinality(),
				value2.content,
				value2.getCardinality(),
				answer.content
			);
		return answer;
	}

	/**
	 * Intersection with a bitmap container; delegates to the bitmap's handler.
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final BitmapContainer x) {
		return x.and(this);
	}

	/**
	 * Intersection with a run container; delegates to the run container's handler.
	 */
	@Nonnull
	@Override
	// see andNot for an approach that might be better.
	public Container and(@Nonnull final RunContainer x) {
		return x.and(this);
	}

	/**
	 * Cardinality of the intersection with another array container without building it; O(n + m).
	 */
	@Override
	public int andCardinality(@Nonnull final ArrayContainer value2) {
		return Util.unsignedLocalIntersect2by2Cardinality(
			this.content, this.cardinality, value2.content, value2.getCardinality());
	}

	/**
	 * Cardinality of the intersection with a bitmap container; delegates to the bitmap.
	 */
	@Override
	public int andCardinality(@Nonnull final BitmapContainer x) {
		return x.andCardinality(this);
	}

	/**
	 * Cardinality of the intersection with a run container; delegates to the run container.
	 */
	@Override
	// see andNot for an approach that might be better.
	public int andCardinality(@Nonnull final RunContainer x) {
		return x.andCardinality(this);
	}

	/**
	 * Difference (this minus other) as a new array container; O(n + m) sorted merge.
	 */
	@Nonnull
	@Override
	public ArrayContainer andNot(@Nonnull final ArrayContainer value2) {
		ArrayContainer value1 = this;
		final int desiredCapacity = value1.getCardinality();
		ArrayContainer answer = new ArrayContainer(desiredCapacity);
		answer.cardinality =
			Util.unsignedDifference(
				value1.content,
				value1.getCardinality(),
				value2.content,
				value2.getCardinality(),
				answer.content
			);
		return answer;
	}

	/**
	 * Difference against a bitmap container as a new array container; O(n), one bit probe per value.
	 */
	@Nonnull
	@Override
	public ArrayContainer andNot(@Nonnull final BitmapContainer value2) {
		final ArrayContainer answer = new ArrayContainer(this.content.length);
		int pos = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			char val = this.content[k];
			answer.content[pos] = val;
			pos += 1 - value2.bitValue(val);
		}
		answer.cardinality = pos;
		return answer;
	}

	/**
	 * Difference against a run container as a new array container; O(n + runs) merge.
	 */
	@Nonnull
	@Override
	public ArrayContainer andNot(@Nonnull final RunContainer x) {
		if (x.numberOfRuns() == 0) {
			return clone();
		} else if (x.isFull()) {
			return ArrayContainer.empty();
		}
		int write = 0;
		int read = 0;
		ArrayContainer answer = new ArrayContainer(this.cardinality);
		for (int i = 0; i < x.numberOfRuns() && read < this.cardinality; ++i) {
			int runStart = (x.getValue(i));
			int runEnd = runStart + (x.getLength(i));
			if ((this.content[read]) > runEnd) {
				continue;
			}
			int firstInRun = Util.iterateUntil(this.content, read, this.cardinality, runStart);
			int toWrite = firstInRun - read;
			System.arraycopy(this.content, read, answer.content, write, toWrite);
			write += toWrite;

			read = Util.iterateUntil(this.content, firstInRun, this.cardinality, runEnd + 1);
		}
		System.arraycopy(this.content, read, answer.content, write, this.cardinality - read);
		write += this.cardinality - read;
		answer.cardinality = write;
		return answer;
	}

	/**
	 * Empties the container in O(1) by resetting the cardinality; the backing array is retained.
	 */
	@Override
	public void clear() {
		this.cardinality = 0;
	}

	/**
	 * Returns a deep copy backed by its own array trimmed to the current cardinality.
	 */
	@Nonnull
	@Override
	public ArrayContainer clone() {
		return new ArrayContainer(this.cardinality, this.content);
	}

	/**
	 * Whether the container holds no values.
	 */
	@Override
	public boolean isEmpty() {
		return this.cardinality == 0;
	}

	/**
	 * Always false: an array container never spans the entire 16-bit universe.
	 */
	@Override
	public boolean isFull() {
		return false;
	}

	/**
	 * Membership test via binary search; O(log n).
	 */
	@Override
	public boolean contains(final char x) {
		return Util.unsignedBinarySearch(this.content, 0, this.cardinality, x) >= 0;
	}

	/**
	 * Whether every value in `[minimum, supremum)` is present.
	 */
	@Override
	public boolean contains(final int minimum, final int supremum) {
		int maximum = supremum - 1;
		int start = Util.advanceUntil(this.content, -1, this.cardinality, (char) minimum);
		int end = Util.advanceUntil(this.content, start - 1, this.cardinality, (char) maximum);
		return start < this.cardinality
			&& end < this.cardinality
			&& end - start == maximum - minimum
			&& this.content[start] == (char) minimum
			&& this.content[end] == (char) maximum;
	}

	/**
	 * Whether this container is a superset of the given run container.
	 */
	@Override
	protected boolean contains(@Nonnull final RunContainer runContainer) {
		if (runContainer.getCardinality() > this.cardinality) {
			return false;
		}

		for (int i = 0; i < runContainer.numberOfRuns(); ++i) {
			int start = (runContainer.getValue(i));
			int length = (runContainer.getLength(i));
			if (!contains(start, start + length + 1)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether this container is a superset of another array container; O(n + m).
	 */
	@Override
	protected boolean contains(@Nonnull final ArrayContainer arrayContainer) {
		if (this.cardinality < arrayContainer.cardinality) {
			return false;
		}
		int i1 = 0, i2 = 0;
		while (i1 < this.cardinality && i2 < arrayContainer.cardinality) {
			if (this.content[i1] == arrayContainer.content[i2]) {
				++i1;
				++i2;
			} else if (this.content[i1] < arrayContainer.content[i2]) {
				++i1;
			} else {
				return false;
			}
		}
		return i2 == arrayContainer.cardinality;
	}

	/**
	 * Always false: an array container is never large enough to contain a bitmap container.
	 */
	@Override
	protected boolean contains(@Nonnull final BitmapContainer bitmapContainer) {
		return false;
	}

	/**
	 * Reads the cardinality then the values in little-endian order, regrowing `content` if needed.
	 */
	@Override
	public void deserialize(@Nonnull final DataInput in) throws IOException {
		this.cardinality = 0xFFFF & Character.reverseBytes(in.readChar());
		if (this.content.length < this.cardinality) {
			this.content = new char[this.cardinality];
		}
		for (int k = 0; k < this.cardinality; ++k) {
			this.content[k] = Character.reverseBytes(in.readChar());
		}
	}

	/**
	 * Appends a value to the tail, growing the backing array if full. Assumes ascending emission: the
	 * value must be greater than every value already stored.
	 */
	private void emit(final char val) {
		if (this.cardinality == this.content.length) {
			increaseCapacity(true);
		}
		this.content[this.cardinality++] = val;
	}

	/**
	 * Value equality: true for an array or run container holding exactly the same values.
	 */
	// content/cardinality are read while non-final on purpose: containers are mutable and equality
	// reflects their current contents.
	@SuppressWarnings({"EqualsBetweenInconvertibleTypes", "NonFinalFieldReferenceInEquals"})
	@Override
	public boolean equals(@Nullable final Object o) {
		if (o instanceof final ArrayContainer srb) {
			return Arrays.equals(this.content, 0, this.cardinality, srb.content, 0, srb.cardinality);
		} else if (o instanceof RunContainer) {
			// intentional cross-type dispatch: Container equality is content-based across the
			// array/bitmap/run representations
			return o.equals(this);
		}
		return false;
	}

	/**
	 * Writes each stored value OR-ed with `mask` into `x`, starting at index `i`.
	 */
	@Override
	public void fillLeastSignificant16bits(@Nonnull final int[] x, final int i, final int mask) {
		for (int k = 0; k < this.cardinality; ++k) {
			x[k + i] = (this.content[k]) | mask;
		}
	}

	/**
	 * Toggles a single value: removes it if present, otherwise inserts it (promoting to a
	 * {@link BitmapContainer} at {@link #DEFAULT_MAX_SIZE}). O(log n) search plus O(n) shift.
	 *
	 * @param x value to toggle
	 * @return this container, or a promoted {@link BitmapContainer}
	 */
	@Nonnull
	@Override
	public Container flip(final char x) {
		int loc = Util.unsignedBinarySearch(this.content, 0, this.cardinality, x);
		if (loc < 0) {
			// Transform the ArrayContainer to a BitmapContainer
			// when cardinality = DEFAULT_MAX_SIZE
			if (this.cardinality >= DEFAULT_MAX_SIZE) {
				BitmapContainer a = this.toBitmapContainer();
				a.add(x);
				return a;
			}
			if (this.cardinality >= this.content.length) {
				increaseCapacity();
			}
			// insertion : shift the elements > x by one position to
			// the right
			// and put x in it's appropriate place
			System.arraycopy(this.content, -loc - 1, this.content, -loc, this.cardinality + loc + 1);
			this.content[-loc - 1] = x;
			++this.cardinality;
		} else {
			System.arraycopy(this.content, loc + 1, this.content, loc, this.cardinality - loc - 1);
			--this.cardinality;
		}
		return this;
	}

	/**
	 * Byte size of the stored values (two bytes each), excluding container overhead.
	 */
	@Override
	public int getArraySizeInBytes() {
		return this.cardinality * 2;
	}

	/**
	 * Returns the number of stored values in O(1).
	 */
	@Override
	public int getCardinality() {
		return this.cardinality;
	}

	/**
	 * Peekable iterator over the values in descending order.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getReverseCharIterator() {
		return new ReverseArrayContainerCharIterator(this);
	}

	/**
	 * Peekable iterator over the values in ascending order.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getCharIterator() {
		return new ArrayContainerCharIterator(this);
	}

	/**
	 * Ascending iterator that also exposes each value's rank, which is simply its array index.
	 */
	@Nonnull
	@Override
	public PeekableCharRankIterator getCharRankIterator() {
		// for ArrayContainer there is no additional work, pos is known in advance
		return new ArrayContainerCharIterator(this);
	}

	/**
	 * Batch iterator that drains values into caller-provided buffers.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator getBatchIterator() {
		return new ArrayBatchIterator(this);
	}

	/**
	 * Estimated heap footprint: the stored values plus fixed object overhead.
	 */
	@Override
	public int getSizeInBytes() {
		return this.cardinality * 2 + 4;
	}

	/**
	 * Heap footprint: this object (an `int` cardinality and one reference) plus the `content` array measured
	 * at its allocated length. The gap against {@link #getSizeInBytes()} is widest here of the three
	 * encodings, because this is the only container whose backing array both grows geometrically and is
	 * never trimmed when values are removed.
	 */
	@Override
	public long getHeapSizeInBytes(@Nonnull HeapLayout layout) {
		return layout.sizeOfObject(Integer.BYTES + layout.referenceSize())
			+ layout.sizeOfArray(this.content.length, Character.BYTES);
	}

	/**
	 * Order-sensitive hash over the stored values.
	 */
	// content/cardinality are read while non-final on purpose: containers are mutable and the hash
	// reflects their current contents.
	@SuppressWarnings("NonFinalFieldReferencedInHashCode")
	@Override
	public int hashCode() {
		int hash = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			hash += 31 * hash + this.content[k];
		}
		return hash;
	}

	/**
	 * In-place variant of {@link #add(int, int)}: adds `[begin, end)` to this container, reusing the
	 * backing array when it has room and promoting to a {@link BitmapContainer} past
	 * {@link #DEFAULT_MAX_SIZE}.
	 *
	 * @throws IllegalArgumentException if the range is malformed or ends beyond the 16-bit universe
	 */
	@Nonnull
	@Override
	public Container iadd(final int begin, final int end) {
		// TODO: may need to convert to a RunContainer
		if (end == begin) {
			return this;
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		int indexstart = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) begin);
		if (indexstart < 0) {
			indexstart = -indexstart - 1;
		}
		int indexend = Util.unsignedBinarySearch(this.content, indexstart, this.cardinality, (char) (end - 1));
		if (indexend < 0) {
			indexend = -indexend - 1;
		} else {
			indexend++;
		}
		int rangelength = end - begin;
		int newcardinality = indexstart + (this.cardinality - indexend) + rangelength;
		if (newcardinality > DEFAULT_MAX_SIZE) {
			BitmapContainer a = this.toBitmapContainer();
			return a.iadd(begin, end);
		}
		/*
		 * b - index of begin(indexstart), e - index of end(indexend), |--| is current sequential
		 * indexes in content. Total 6 cases are possible, listed as below:
		 *
		 * case-1) |--------|b-e case-2) |----b---|e case-3) |---b---e---| case-4) b|----e---| case-5)
		 * b-e|------| case-6) b|-----|e
		 *
		 * In case of old approach, we did (1a) Array.copyOf in increaseCapacity ( # of elements copied
		 * -> cardinality), (1b) then we moved elements using System.arrayCopy ( # of elements copied ->
		 * cardinality -indexend), (1c) then we set all elements from begin to end ( # of elements set
		 * -> end - begin)
		 *
		 * With new approach, (2a) we set all elements from begin to end ( # of elements set -> end-
		 * begin), (2b) we only copy elements in current set which are not in range begin-end ( # of
		 * elements copied -> cardinality - (end-begin) )
		 *
		 * why is it faster? Logically we are doing less # of copies. Mathematically proof as below: ->
		 * 2a is same as 1c, so we can avoid. Assume, 2b < (1a+1b), lets prove this assumption.
		 * Substitute the values. (cardinality - (end-begin)) < ( 2*cardinality - indexend) , lowest
		 * possible value of indexend is 0 and equation holds true , hightest possible value of indexend
		 * is cardinality and equation holds true , hence "<" equation holds true always
		 */
		if (newcardinality >= this.content.length) {
			char[] destination = new char[calculateCapacity(newcardinality)];
			// if b > 0, we copy from 0 to b. Do nothing otherwise.
			System.arraycopy(this.content, 0, destination, 0, indexstart);
			// set values from b to e
			for (int k = 0; k < rangelength; ++k) {
				destination[k + indexstart] = (char) (begin + k);
			}
			/*
			 * so far cases - 1,2 and 6 are done Now, if e < cardinality, we copy from e to
			 * cardinality.Otherwise do noting this covers remaining 3,4 and 5 cases
			 */
			System.arraycopy(
				this.content, indexend, destination, indexstart + rangelength, this.cardinality - indexend);
			this.content = destination;
		} else {
			System.arraycopy(
				this.content, indexend, this.content, indexstart + rangelength, this.cardinality - indexend);
			for (int k = 0; k < rangelength; ++k) {
				this.content[k + indexstart] = (char) (begin + k);
			}
		}
		this.cardinality = newcardinality;
		return this;
	}

	/**
	 * In-place intersection with another array container; O(n + m).
	 */
	@Nonnull
	@Override
	public ArrayContainer iand(@Nonnull final ArrayContainer value2) {
		ArrayContainer value1 = this;
		value1.cardinality =
			Util.unsignedIntersect2by2(
				value1.content,
				value1.getCardinality(),
				value2.content,
				value2.getCardinality(),
				value1.content
			);
		return this;
	}

	/**
	 * In-place intersection with a bitmap container; keeps only values whose bit is set.
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final BitmapContainer value2) {
		int pos = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			char v = this.content[k];
			this.content[pos] = v;
			pos += value2.bitValue(v);
		}
		this.cardinality = pos;
		return this;
	}

	/**
	 * In-place intersection with a run container.
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final RunContainer x) {
		PeekableCharIterator it = x.getCharIterator();
		int removed = 0;
		for (int i = 0; i < this.cardinality; i++) {
			it.advanceIfNeeded(this.content[i]);
			if (it.peekNext() == this.content[i]) {
				this.content[i - removed] = this.content[i];
			} else {
				removed++;
			}
		}
		this.cardinality -= removed;
		return this;
	}

	/**
	 * In-place difference (this minus other); O(n + m).
	 */
	@Nonnull
	@Override
	public ArrayContainer iandNot(@Nonnull final ArrayContainer value2) {
		this.cardinality =
			Util.unsignedDifference(
				this.content,
				this.getCardinality(),
				value2.content,
				value2.getCardinality(),
				this.content
			);
		return this;
	}

	/**
	 * In-place difference against a bitmap container; drops values whose bit is set.
	 */
	@Nonnull
	@Override
	public ArrayContainer iandNot(@Nonnull final BitmapContainer value2) {
		int pos = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			char v = this.content[k];
			this.content[pos] = v;
			pos += 1 - value2.bitValue(v);
		}
		this.cardinality = pos;
		return this;
	}

	/**
	 * In-place difference against a run container.
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final RunContainer x) {
		PeekableCharIterator it = x.getCharIterator();
		int removed = 0;
		for (int i = 0; i < this.cardinality; i++) {
			it.advanceIfNeeded(this.content[i]);
			if (it.peekNext() != this.content[i]) {
				this.content[i - removed] = this.content[i];
			} else {
				removed++;
			}
		}
		this.cardinality -= removed;
		return this;
	}

	/**
	 * Grows the backing array by the standard growth policy, capped at {@link #DEFAULT_MAX_SIZE}.
	 */
	private void increaseCapacity() {
		increaseCapacity(false);
	}

	/**
	 * Grows the backing array. When `allowIllegalSize` is false the new capacity is capped at (and
	 * snapped up to) {@link #DEFAULT_MAX_SIZE}; passing true temporarily permits an over-max array,
	 * which is only safe while the oversized container is not handed back to callers.
	 *
	 * @param allowIllegalSize whether to bypass the {@link #DEFAULT_MAX_SIZE} cap
	 */
	private void increaseCapacity(final boolean allowIllegalSize) {
		int newCapacity = computeCapacity(this.content.length);
		// never allocate more than we will ever need
		if (newCapacity > ArrayContainer.DEFAULT_MAX_SIZE && !allowIllegalSize) {
			newCapacity = ArrayContainer.DEFAULT_MAX_SIZE;
		}
		// if we are within 1/16th of the max, go to max
		if (newCapacity > ArrayContainer.DEFAULT_MAX_SIZE - ArrayContainer.DEFAULT_MAX_SIZE / 16
			&& !allowIllegalSize) {
			newCapacity = ArrayContainer.DEFAULT_MAX_SIZE;
		}
		this.content = Arrays.copyOf(this.content, newCapacity);
	}

	/**
	 * Growth schedule for the backing array: seed to {@link #DEFAULT_INIT_SIZE} from empty, then
	 * double while small (`< 64`), grow by 1.5x up to 1024, and by 1.25x beyond, trading array slack
	 * against the number of reallocations.
	 *
	 * @param oldCapacity current array length
	 * @return proposed next capacity, before any maximum cap is applied
	 */
	private static int computeCapacity(final int oldCapacity) {
		return oldCapacity == 0
			? DEFAULT_INIT_SIZE
			: oldCapacity < 64
			  ? oldCapacity * 2
				: oldCapacity < 1024 ? oldCapacity * 3 / 2 : oldCapacity * 5 / 4;
	}

	/**
	 * Like {@link #computeCapacity(int)} but guarantees at least `min` slots, still capped at and
	 * snapped up to {@link #DEFAULT_MAX_SIZE}.
	 *
	 * @param min minimum required capacity
	 * @return capacity to allocate
	 */
	private int calculateCapacity(final int min) {
		int newCapacity = computeCapacity(this.content.length);
		if (newCapacity < min) {
			newCapacity = min;
		}
		// never allocate more than we will ever need
		if (newCapacity > ArrayContainer.DEFAULT_MAX_SIZE) {
			newCapacity = ArrayContainer.DEFAULT_MAX_SIZE;
		}
		// if we are within 1/16th of the max, go to max
		if (newCapacity > ArrayContainer.DEFAULT_MAX_SIZE - ArrayContainer.DEFAULT_MAX_SIZE / 16) {
			newCapacity = ArrayContainer.DEFAULT_MAX_SIZE;
		}
		return newCapacity;
	}

	/**
	 * In-place complement of the values in `[firstOfRange, lastOfRange)`, promoting to a
	 * {@link BitmapContainer} if the result would grow past {@link #DEFAULT_MAX_SIZE}.
	 */
	@Nonnull
	@Override
	public Container inot(final int firstOfRange, final int lastOfRange) {
		// TODO: may need to convert to a RunContainer
		// determine the span of array indices to be affected
		int startIndex = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) firstOfRange);
		if (startIndex < 0) {
			startIndex = -startIndex - 1;
		}
		int lastIndex =
			Util.unsignedBinarySearch(this.content, startIndex, this.cardinality, (char) (lastOfRange - 1));
		if (lastIndex < 0) {
			lastIndex = -lastIndex - 1 - 1;
		}
		final int currentValuesInRange = lastIndex - startIndex + 1;
		final int spanToBeFlipped = lastOfRange - firstOfRange;
		final int newValuesInRange = spanToBeFlipped - currentValuesInRange;
		final char[] buffer = new char[newValuesInRange];
		final int cardinalityChange = newValuesInRange - currentValuesInRange;
		final int newCardinality = this.cardinality + cardinalityChange;

		if (cardinalityChange > 0) { // expansion, right shifting needed
			if (newCardinality > this.content.length) {
				// so big we need a bitmap?
				if (newCardinality > DEFAULT_MAX_SIZE) {
					return toBitmapContainer().inot(firstOfRange, lastOfRange);
				}
				this.content = Arrays.copyOf(this.content, newCardinality);
			}
			// slide right the contents after the range
			System.arraycopy(
				this.content,
				lastIndex + 1,
				this.content,
				lastIndex + 1 + cardinalityChange,
				this.cardinality - 1 - lastIndex
			);
			negateRange(buffer, startIndex, lastIndex, firstOfRange, lastOfRange);
		} else { // no expansion needed
			negateRange(buffer, startIndex, lastIndex, firstOfRange, lastOfRange);
			if (cardinalityChange < 0) {
				// contraction, left sliding.
				// Leave array oversize
				System.arraycopy(
					this.content,
					startIndex + newValuesInRange - cardinalityChange,
					this.content,
					startIndex + newValuesInRange,
					newCardinality - (startIndex + newValuesInRange)
				);
			}
		}
		this.cardinality = newCardinality;
		return this;
	}

	/**
	 * Whether this container shares any value with another array container; O(n + m).
	 */
	@Override
	public boolean intersects(@Nonnull final ArrayContainer value2) {
		ArrayContainer value1 = this;
		return Util.unsignedIntersects(
			value1.content, value1.getCardinality(), value2.content, value2.getCardinality());
	}

	/**
	 * Whether this container shares any value with a bitmap container.
	 */
	@Override
	public boolean intersects(@Nonnull final BitmapContainer x) {
		return x.intersects(this);
	}

	/**
	 * Whether this container shares any value with a run container.
	 */
	@Override
	public boolean intersects(@Nonnull final RunContainer x) {
		return x.intersects(this);
	}

	/**
	 * Whether any stored value falls within `[minimum, supremum)`.
	 */
	@Override
	public boolean intersects(final int minimum, final int supremum) {
		if ((minimum < 0) || (supremum < minimum) || (supremum > (1 << 16))) {
			throw new RuntimeException("This should never happen (bug).");
		}
		int pos = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) minimum);
		int index = pos >= 0 ? pos : -pos - 1;
		return index < this.cardinality && (this.content[index]) < supremum;
	}

	/**
	 * In-place union with another array container, promoting to a {@link BitmapContainer} once the
	 * combined cardinality would exceed {@link #DEFAULT_MAX_SIZE}. O(n + m) merge.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final ArrayContainer value2) {
		int totalCardinality = this.getCardinality() + value2.getCardinality();
		if (totalCardinality > DEFAULT_MAX_SIZE) {
			return toBitmapContainer().lazyIOR(value2).repairAfterLazy();
		}
		if (totalCardinality >= this.content.length) {
			int newCapacity = calculateCapacity(totalCardinality);
			char[] destination = new char[newCapacity];
			this.cardinality =
				Util.unsignedUnion2by2(
					this.content, 0, this.cardinality, value2.content, 0, value2.cardinality, destination);
			this.content = destination;
		} else {
			System.arraycopy(this.content, 0, this.content, value2.cardinality, this.cardinality);
			this.cardinality =
				Util.unsignedUnion2by2(
					this.content,
					value2.cardinality,
					this.cardinality,
					value2.content,
					0,
					value2.cardinality,
					this.content
				);
		}
		return this;
	}

	/**
	 * In-place union with a bitmap container; delegates to the bitmap.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final BitmapContainer x) {
		return x.or(this);
	}

	/**
	 * In-place union with a run container; delegates to the run container.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final RunContainer x) {
		// possible performance issue, not taking advantage of possible inplace
		return x.or(this);
	}

	/**
	 * In-place removal of every value in `[begin, end)`; O(log n) to locate the bounds plus a tail shift.
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
		int indexstart = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) begin);
		if (indexstart < 0) {
			indexstart = -indexstart - 1;
		}
		int indexend = Util.unsignedBinarySearch(this.content, indexstart, this.cardinality, (char) (end - 1));
		if (indexend < 0) {
			indexend = -indexend - 1;
		} else {
			indexend++;
		}
		int rangelength = indexend - indexstart;
		System.arraycopy(
			this.content,
			indexstart + rangelength,
			this.content,
			indexstart,
			this.cardinality - indexstart - rangelength
		);
		this.cardinality -= rangelength;
		return this;
	}

	/**
	 * Boxing {@link Iterator} over the values in ascending order; prefer {@link #getCharIterator()} to
	 * avoid autoboxing each value.
	 */
	@Nonnull
	@Override
	public Iterator<Character> iterator() {
		return new Iterator<>() {
			short pos = 0;

			@Override
			public boolean hasNext() {
				return this.pos < ArrayContainer.this.cardinality;
			}

			@Nonnull
			@Override
			public Character next() {
				return ArrayContainer.this.content[this.pos++];
			}

			@Override
			public void remove() {
				ArrayContainer.this.removeAtIndex(this.pos - 1);
				this.pos--;
			}
		};
	}

	/**
	 * In-place symmetric difference with another array container.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final ArrayContainer value2) {
		return this.xor(value2);
	}

	/**
	 * In-place symmetric difference with a bitmap container; delegates to the bitmap.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final BitmapContainer x) {
		return x.xor(this);
	}

	/**
	 * In-place symmetric difference with a run container; delegates to the run container.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final RunContainer x) {
		// possible performance issue, not taking advantage of possible inplace
		return x.xor(this);
	}

	/**
	 * Returns a copy holding at most `maxcardinality` of the lowest values.
	 */
	@Nonnull
	@Override
	public Container limit(final int maxcardinality) {
		if (maxcardinality < this.getCardinality()) {
			return new ArrayContainer(maxcardinality, this.content);
		} else {
			return clone();
		}
	}

	/**
	 * Fills this container from the set bits of a bitmap container, used when demoting a
	 * {@link BitmapContainer} back to an array. Assumes `content` is already sized to hold the
	 * bitmap's cardinality.
	 */
	void loadData(@Nonnull final BitmapContainer bitmapContainer) {
		this.cardinality = bitmapContainer.cardinality;
		Util.fillArray(bitmapContainer.bitmap, this.content);
	}

	/**
	 * Complements the values in `[startRange, lastRange)` into `buffer`, then copies the result back
	 * over `content` starting at `startIndex`. Helper for {@link #inot(int, int)}; the caller
	 * guarantees the affected slice is non-empty and has room for the negated values.
	 */
	// for use in inot range known to be nonempty
	private void negateRange(
		@Nonnull final char[] buffer,
		final int startIndex,
		final int lastIndex,
		final int startRange,
		final int lastRange
	) {
		// compute the negation into buffer

		int outPos = 0;
		int inPos = startIndex; // value here always >= valInRange,
		// until it is exhausted
		// n.b., we can start initially exhausted.

		int valInRange = startRange;
		for (; valInRange < lastRange && inPos <= lastIndex; ++valInRange) {
			if ((char) valInRange != this.content[inPos]) {
				buffer[outPos++] = (char) valInRange;
			} else {
				++inPos;
			}
		}

		// if there are extra items (greater than the biggest
		// pre-existing one in range), buffer them
		for (; valInRange < lastRange; ++valInRange) {
			buffer[outPos++] = (char) valInRange;
		}

		if (outPos != buffer.length) {
			throw new RuntimeException(
				"negateRange: outPos " + outPos + " whereas buffer.length=" + buffer.length);
		}
		// copy back from buffer...caller must ensure there is room
		int i = startIndex;
		for (char item : buffer) {
			this.content[i++] = item;
		}
	}

	/**
	 * Non-mutating complement of `[firstOfRange, lastOfRange)`, returning a new container (or a
	 * {@link BitmapContainer} if it would grow past {@link #DEFAULT_MAX_SIZE}).
	 */
	// shares lots of code with inot; candidate for refactoring
	@Nonnull
	@Override
	public Container not(final int firstOfRange, final int lastOfRange) {
		// TODO: may need to convert to a RunContainer
		if (firstOfRange >= lastOfRange) {
			return clone(); // empty range
		}

		// determine the span of array indices to be affected
		int startIndex = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) firstOfRange);
		if (startIndex < 0) {
			startIndex = -startIndex - 1;
		}
		int lastIndex =
			Util.unsignedBinarySearch(this.content, startIndex, this.cardinality, (char) (lastOfRange - 1));
		if (lastIndex < 0) {
			lastIndex = -lastIndex - 2;
		}
		final int currentValuesInRange = lastIndex - startIndex + 1;
		final int spanToBeFlipped = lastOfRange - firstOfRange;
		final int newValuesInRange = spanToBeFlipped - currentValuesInRange;
		final int cardinalityChange = newValuesInRange - currentValuesInRange;
		final int newCardinality = this.cardinality + cardinalityChange;

		if (newCardinality > DEFAULT_MAX_SIZE) {
			return toBitmapContainer().not(firstOfRange, lastOfRange);
		}

		ArrayContainer answer = new ArrayContainer(newCardinality);

		// copy stuff before the active area
		System.arraycopy(this.content, 0, answer.content, 0, startIndex);

		int outPos = startIndex;
		int inPos = startIndex; // item at inPos always >= valInRange

		int valInRange = firstOfRange;
		for (; valInRange < lastOfRange && inPos <= lastIndex; ++valInRange) {
			if ((char) valInRange != this.content[inPos]) {
				answer.content[outPos++] = (char) valInRange;
			} else {
				++inPos;
			}
		}

		for (; valInRange < lastOfRange; ++valInRange) {
			answer.content[outPos++] = (char) valInRange;
		}

		// content after the active range
		for (int i = lastIndex + 1; i < this.cardinality; ++i) {
			answer.content[outPos++] = this.content[i];
		}
		answer.cardinality = newCardinality;
		return answer;
	}

	/**
	 * Counts maximal runs of consecutive values; used to decide whether a {@link RunContainer} would
	 * be a smaller encoding. O(n).
	 */
	@Override
	int numberOfRuns() {
		if (this.cardinality == 0) {
			return 0; // should never happen
		}
		int numRuns = 1;
		int oldv = (this.content[0]);
		for (int i = 1; i < this.cardinality; i++) {
			int newv = (this.content[i]);
			if (oldv + 1 != newv) {
				++numRuns;
			}
			oldv = newv;
		}
		return numRuns;
	}

	/**
	 * Union with another array container as a new container, promoting to a {@link BitmapContainer}
	 * once the combined cardinality would exceed {@link #DEFAULT_MAX_SIZE}. O(n + m) merge.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final ArrayContainer value2) {
		final ArrayContainer value1 = this;
		int totalCardinality = value1.getCardinality() + value2.getCardinality();
		if (totalCardinality > DEFAULT_MAX_SIZE) {
			return toBitmapContainer().lazyIOR(value2).repairAfterLazy();
		}
		ArrayContainer answer = new ArrayContainer(totalCardinality);
		answer.cardinality =
			Util.unsignedUnion2by2(
				value1.content,
				0,
				value1.getCardinality(),
				value2.content,
				0,
				value2.getCardinality(),
				answer.content
			);
		return answer;
	}

	/**
	 * Union with a bitmap container; delegates to the bitmap.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final BitmapContainer x) {
		return x.or(this);
	}

	/**
	 * Union with a run container; delegates to the run container.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final RunContainer x) {
		return x.or(this);
	}

	/**
	 * Union of this container with the values produced by an ascending iterator.
	 *
	 * @param it values in unsigned-ascending order
	 * @return a new container holding the union
	 */
	@Nonnull
	protected Container or(@Nonnull final CharIterator it) {
		return or(it, false);
	}

	/**
	 * Merges this container with an ascending iterator, emitting either the union (`exclusive` false)
	 * or the symmetric difference (`exclusive` true). Promotes the result to a {@link BitmapContainer}
	 * if it exceeds {@link #DEFAULT_MAX_SIZE}. O(n + m).
	 *
	 * @param it        values in unsigned-ascending order
	 * @param exclusive true to exclude values present in both inputs (XOR), false to keep them (OR)
	 * @return the merged container
	 */
	@Nonnull
	private Container or(@Nonnull final CharIterator it, final boolean exclusive) {
		ArrayContainer ac = new ArrayContainer();
		int myItPos = 0;
		ac.cardinality = 0;
		// do a merge. int -1 denotes end of input.
		int myHead = (myItPos == this.cardinality) ? -1 : (this.content[myItPos++]);
		int hisHead = advance(it);

		while (myHead != -1 && hisHead != -1) {
			if (myHead < hisHead) {
				ac.emit((char) myHead);
				myHead = (myItPos == this.cardinality) ? -1 : (this.content[myItPos++]);
			} else if (myHead > hisHead) {
				ac.emit((char) hisHead);
				hisHead = advance(it);
			} else {
				if (!exclusive) {
					ac.emit((char) hisHead);
				}
				hisHead = advance(it);
				myHead = (myItPos == this.cardinality) ? -1 : (this.content[myItPos++]);
			}
		}

		while (myHead != -1) {
			ac.emit((char) myHead);
			myHead = (myItPos == this.cardinality) ? -1 : (this.content[myItPos++]);
		}

		while (hisHead != -1) {
			ac.emit((char) hisHead);
			hisHead = advance(it);
		}

		if (ac.cardinality > DEFAULT_MAX_SIZE) {
			return ac.toBitmapContainer();
		} else {
			return ac;
		}
	}

	/**
	 * Number of stored values `<=` `lowbits`, via binary search; O(log n).
	 */
	@Override
	public int rank(final char lowbits) {
		int answer = Util.unsignedBinarySearch(this.content, 0, this.cardinality, lowbits);
		if (answer >= 0) {
			return answer + 1;
		} else {
			return -answer - 1;
		}
	}

	/**
	 * {@link java.io.Externalizable} hook; delegates to {@link #deserialize(DataInput)}.
	 */
	@Override
	public void readExternal(final ObjectInput in) throws IOException {
		deserialize(in);
	}

	/**
	 * Non-mutating removal of every value in `[begin, end)`, returning a new container.
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
		int indexstart = Util.unsignedBinarySearch(this.content, 0, this.cardinality, (char) begin);
		if (indexstart < 0) {
			indexstart = -indexstart - 1;
		}
		int indexend = Util.unsignedBinarySearch(this.content, indexstart, this.cardinality, (char) (end - 1));
		if (indexend < 0) {
			indexend = -indexend - 1;
		} else {
			indexend++;
		}
		int rangelength = indexend - indexstart;
		ArrayContainer answer = clone();
		System.arraycopy(
			this.content,
			indexstart + rangelength,
			answer.content,
			indexstart,
			this.cardinality - indexstart - rangelength
		);
		answer.cardinality = this.cardinality - rangelength;
		return answer;
	}

	/**
	 * Removes the value at array index `loc` by shifting the tail one slot left.
	 */
	void removeAtIndex(final int loc) {
		System.arraycopy(this.content, loc + 1, this.content, loc, this.cardinality - loc - 1);
		--this.cardinality;
	}

	/**
	 * Removes a single value if present; O(log n) search plus a tail shift.
	 */
	@Nonnull
	@Override
	public Container remove(final char x) {
		final int loc = Util.unsignedBinarySearch(this.content, 0, this.cardinality, x);
		if (loc >= 0) {
			removeAtIndex(loc);
		}
		return this;
	}

	/**
	 * No-op for array containers, which are always valid; returns this.
	 */
	@Nonnull
	@Override
	public Container repairAfterLazy() {
		return this;
	}

	/**
	 * Converts to a {@link RunContainer} when that would serialize smaller, otherwise returns this.
	 */
	@Nonnull
	@Override
	public Container runOptimize() {
		// TODO: consider borrowing the BitmapContainer idea of early
		// abandonment
		// with ArrayContainers, when the number of runs in the arrayContainer
		// passes some threshold based on the cardinality.
		int numRuns = numberOfRuns();
		int sizeAsRunContainer = RunContainer.serializedSizeInBytes(numRuns);
		if (getArraySizeInBytes() > sizeAsRunContainer) {
			return new RunContainer(this, numRuns); // this could be maybe
			// faster if initial
			// container is a bitmap
		} else {
			return this;
		}
	}

	/**
	 * Returns the value at rank `j` (the jth smallest) in O(1).
	 */
	@Override
	public char select(final int j) {
		return this.content[j];
	}

	/**
	 * Writes the cardinality header then the values, all in little-endian order.
	 */
	@Override
	public void serialize(@Nonnull final DataOutput out) throws IOException {
		out.writeShort(Character.reverseBytes((char) this.cardinality));
		// little endian
		for (int k = 0; k < this.cardinality; ++k) {
			out.writeShort(Character.reverseBytes(this.content[k]));
		}
	}

	/**
	 * Serialized size for the current cardinality; see {@link #serializedSizeInBytes(int)}.
	 */
	@Override
	public int serializedSizeInBytes() {
		return serializedSizeInBytes(this.cardinality);
	}

	/**
	 * Promotes this container to a {@link BitmapContainer} holding the same values; the data is
	 * copied, so this container is left unchanged.
	 *
	 * @return the equivalent bitmap container
	 */
	@Nonnull
	@Override
	public BitmapContainer toBitmapContainer() {
		BitmapContainer bc = new BitmapContainer();
		bc.loadData(this);
		return bc;
	}

	/**
	 * Sets, in `dest`, the bit for each stored value, treating `position` as the base word offset.
	 */
	@Override
	public void copyBitmapTo(@Nonnull final long[] dest, final int position) {
		for (int k = 0; k < this.cardinality; ++k) {
			final char x = this.content[k];
			dest[position + x / 64] |= 1L << x;
		}
	}

	/**
	 * First stored value `>=` `fromValue`, or -1 if none; O(log n) via galloping search.
	 */
	@Override
	public int nextValue(final char fromValue) {
		// An empty container has no stored value, so honour the "-1 if none" contract instead of
		// dereferencing content[cardinality - 1] == content[-1]. The mirror method previousValue
		// already returns gracefully on an empty container; nextValue must behave the same way.
		if (this.cardinality == 0) {
			return -1;
		}
		int index = Util.advanceUntil(this.content, -1, this.cardinality, fromValue);
		if (index == this.cardinality) {
			return fromValue == this.content[this.cardinality - 1] ? (fromValue) : -1;
		}
		return (this.content[index]);
	}

	/**
	 * Last stored value `<=` `fromValue`, or -1 if none.
	 */
	@Override
	public int previousValue(final char fromValue) {
		int index = Util.advanceUntil(this.content, -1, this.cardinality, fromValue);
		if (index != this.cardinality && this.content[index] == fromValue) {
			return (this.content[index]);
		}
		return index == 0 ? -1 : (this.content[index - 1]);
	}

	/**
	 * Smallest value `>=` `fromValue` that is absent; binary-searches the consecutive run above `fromValue`.
	 */
	@Override
	public int nextAbsentValue(final char fromValue) {
		int index = Util.advanceUntil(this.content, -1, this.cardinality, fromValue);
		if (index >= this.cardinality) {
			return (int) (fromValue);
		}
		if (index == this.cardinality - 1) {
			return fromValue == this.content[this.cardinality - 1] ? (int) (fromValue) + 1 : (int) (fromValue);
		}
		if (this.content[index] != fromValue) {
			return (int) (fromValue);
		}
		if (this.content[index + 1] > fromValue + 1) {
			return (int) (fromValue) + 1;
		}

		int low = index;
		int high = this.cardinality;

		while (low + 1 < high) {
			int mid = (high + low) >>> 1;
			if (mid - index < (this.content[mid]) - (int) (fromValue)) {
				high = mid;
			} else {
				low = mid;
			}
		}

		if (low == this.cardinality - 1) {
			return (this.content[this.cardinality - 1]) + 1;
		}

		assert (this.content[low]) + 1 < (this.content[high]);
		assert (this.content[low]) == (int) (fromValue) + (low - index);
		return (this.content[low]) + 1;
	}

	/**
	 * Largest value `<=` `fromValue` that is absent; binary-searches the consecutive run below `fromValue`.
	 */
	@Override
	public int previousAbsentValue(final char fromValue) {
		int index = Util.advanceUntil(this.content, -1, this.cardinality, fromValue);
		if (index >= this.cardinality) {
			return (int) (fromValue);
		}
		if (index == 0) {
			return fromValue == this.content[0] ? (int) (fromValue) - 1 : (int) (fromValue);
		}
		if (this.content[index] != fromValue) {
			return (int) (fromValue);
		}
		if (this.content[index - 1] < fromValue - 1) {
			return (int) (fromValue) - 1;
		}

		int low = -1;
		int high = index;

		// Binary search for the first index which differs by at least 2 from its
		// successor
		while (low + 1 < high) {
			int mid = (high + low) >>> 1;
			if (index - mid < (int) (fromValue) - (this.content[mid])) {
				low = mid;
			} else {
				high = mid;
			}
		}

		if (high == 0) {
			return (this.content[0]) - 1;
		}

		assert (this.content[low]) + 1 < (this.content[high]);
		assert (this.content[high]) == (int) (fromValue) - (index - high);
		return (this.content[high]) - 1;
	}

	/**
	 * Smallest stored value in O(1).
	 */
	@Override
	public int first() {
		assertNonEmpty(this.cardinality == 0);
		return (this.content[0]);
	}

	/**
	 * Checks the representation invariant: non-empty, cardinality within `(0, DEFAULT_MAX_SIZE]`, and
	 * values strictly ascending.
	 */
	@Nonnull
	@Override
	public Boolean validate() {
		if (this.cardinality <= 0) {
			return false;
		}
		if (this.cardinality > DEFAULT_MAX_SIZE) {
			return false;
		}
		for (int k = 1; k < this.cardinality; ++k) {
			if (this.content[k - 1] >= this.content[k]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Largest stored value in O(1).
	 */
	@Override
	public int last() {
		assertNonEmpty(this.cardinality == 0);
		return (this.content[this.cardinality - 1]);
	}

	/**
	 * Renders the values as a brace-enclosed comma-separated list, e.g. `{1,4,9}`.
	 */
	@Nonnull
	@Override
	public String toString() {
		if (this.cardinality == 0) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{}".length() + "-123456789,".length() * this.cardinality);
		sb.append('{');
		for (int i = 0; i < this.cardinality - 1; i++) {
			sb.append((int) (this.content[i]));
			sb.append(',');
		}
		sb.append((int) (this.content[this.cardinality - 1]));
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Shrinks the backing array to exactly the current cardinality, releasing unused capacity.
	 */
	@Override
	public void trim() {
		if (this.content.length == this.cardinality) {
			return;
		}
		this.content = Arrays.copyOf(this.content, this.cardinality);
	}

	/**
	 * Writes just the values (no cardinality header) in little-endian order.
	 */
	@Override
	public void writeArray(@Nonnull final DataOutput out) throws IOException {
		// little endian
		for (int k = 0; k < this.cardinality; ++k) {
			char v = this.content[k];
			out.writeChar(Character.reverseBytes(v));
		}
	}

	/**
	 * Writes just the values into a little-endian {@link ByteBuffer}, advancing its position.
	 */
	@Override
	public void writeArray(@Nonnull final ByteBuffer buffer) {
		assert buffer.order() == ByteOrder.LITTLE_ENDIAN;
		CharBuffer buf = buffer.asCharBuffer();
		buf.put(this.content, 0, this.cardinality);
		int bytesWritten = 2 * this.cardinality;
		buffer.position(buffer.position() + bytesWritten);
	}

	/**
	 * {@link java.io.Externalizable} hook; delegates to {@link #serialize(DataOutput)}.
	 */
	@Override
	public void writeExternal(final ObjectOutput out) throws IOException {
		serialize(out);
	}

	/**
	 * Symmetric difference with another array container as a new container, promoting to a
	 * {@link BitmapContainer} once the combined cardinality would exceed {@link #DEFAULT_MAX_SIZE}.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final ArrayContainer value2) {
		final ArrayContainer value1 = this;
		final int totalCardinality = value1.getCardinality() + value2.getCardinality();
		if (totalCardinality > DEFAULT_MAX_SIZE) {
			return toBitmapContainer().ixor(value2);
		}
		ArrayContainer answer = new ArrayContainer(totalCardinality);
		answer.cardinality =
			Util.unsignedExclusiveUnion2by2(
				value1.content,
				value1.getCardinality(),
				value2.content,
				value2.getCardinality(),
				answer.content
			);
		return answer;
	}

	/**
	 * Symmetric difference with a bitmap container; delegates to the bitmap.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final BitmapContainer x) {
		return x.xor(this);
	}

	/**
	 * Symmetric difference with a run container; delegates to the run container.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final RunContainer x) {
		return x.xor(this);
	}

	/**
	 * Symmetric difference of this container with the values produced by an ascending iterator.
	 *
	 * @param it values in unsigned-ascending order
	 * @return a new container holding the symmetric difference
	 */
	@Nonnull
	protected Container xor(@Nonnull final CharIterator it) {
		return or(it, true);
	}

	/**
	 * Feeds each stored value, prefixed with the 16-bit `msb` as its high half, to the consumer.
	 */
	@Override
	public void forEach(final char msb, @Nonnull final IntConsumer ic) {
		int high = msb << 16;
		for (int k = 0; k < this.cardinality; ++k) {
			ic.accept(this.content[k] | high);
		}
	}

	/**
	 * Reports presence and absence of every position in the 16-bit universe, offset by `offset`.
	 */
	@Override
	public void forAll(final int offset, @Nonnull final RelativeRangeConsumer rrc) {
		int next = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			int value = this.content[k];
			if (next < value) {
				// fill in the missing values until value
				rrc.acceptAllAbsent(offset + next, offset + value);
			}
			rrc.acceptPresent(offset + value);
			next = value + 1;
		}
		if (next <= Character.MAX_VALUE) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(offset + next, offset + Character.MAX_VALUE + 1);
		}
	}

	/**
	 * Like {@link #forAll(int, RelativeRangeConsumer)} but starting at `startValue`.
	 */
	@Override
	public void forAllFrom(final char startValue, @Nonnull final RelativeRangeConsumer rrc) {
		int loc = Util.unsignedBinarySearch(this.content, 0, this.cardinality, startValue);
		int startIndex;
		if (loc >= 0) {
			startIndex = loc;
		} else {
			// the value doesn't exist, this is the index of the nearest value
			startIndex = -loc - 1;
		}
		int next = startValue;
		for (int k = startIndex; k < this.cardinality; k++) {
			int value = this.content[k];
			if (next < value) {
				// fill in the missing values until value
				rrc.acceptAllAbsent(next - startValue, value - startValue);
			}
			rrc.acceptPresent(value - startValue);
			next = value + 1;
		}
		if (next <= Character.MAX_VALUE) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(next - startValue, Character.MAX_VALUE + 1 - startValue);
		}
	}

	/**
	 * Like {@link #forAll(int, RelativeRangeConsumer)} but stopping before `endValue`.
	 */
	@Override
	public void forAllUntil(
		final int offset, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		int next = 0;
		for (int k = 0; k < this.cardinality; ++k) {
			int value = this.content[k];
			if (endValue <= value) {
				// value is already beyond the end
				if (next < endValue) {
					rrc.acceptAllAbsent(offset + next, offset + endValue);
				}
				return;
			}
			if (next < value) {
				// fill in the missing values until value
				rrc.acceptAllAbsent(offset + next, offset + value);
			}
			rrc.acceptPresent(offset + value);
			next = value + 1;
		}
		if (next < endValue) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(offset + next, offset + endValue);
		}
	}

	/**
	 * Reports presence and absence for every position in `[startValue, endValue)`.
	 *
	 * @throws IllegalArgumentException if `endValue` is not greater than `startValue`
	 */
	@Override
	public void forAllInRange(
		final char startValue, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		if (endValue <= startValue) {
			throw new IllegalArgumentException(
				"startValue (" + startValue + ") must be less than endValue (" + endValue + ")");
		}
		int loc = Util.unsignedBinarySearch(this.content, 0, this.cardinality, startValue);
		// the value doesn't exist, this is the index of the nearest value
		int startIndex = loc >= 0 ? loc : -loc - 1;
		int next = startValue;
		for (int k = startIndex; k < this.cardinality; k++) {
			int value = this.content[k];
			if (endValue <= value) {
				// value is already beyond the end
				if (next < endValue) {
					rrc.acceptAllAbsent(next - startValue, endValue - startValue);
				}
				return;
			}
			if (next < value) {
				// fill in the missing values until value
				rrc.acceptAllAbsent(next - startValue, value - startValue);
			}
			rrc.acceptPresent(value - startValue);
			next = value + 1;
		}
		if (next < endValue) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(next - startValue, endValue - startValue);
		}
	}

	/**
	 * Union used by lazy aggregation: merges into a new array container, switching to a
	 * {@link BitmapContainer} above {@link #ARRAY_LAZY_LOWERBOUND}. Skips the exact size cap of
	 * {@link #or(ArrayContainer)}, so the result must later be normalized via
	 * {@link #repairAfterLazy()}.
	 */
	@Nonnull
	protected Container lazyor(@Nonnull final ArrayContainer value2) {
		final ArrayContainer value1 = this;
		int totalCardinality = value1.getCardinality() + value2.getCardinality();
		if (totalCardinality > ARRAY_LAZY_LOWERBOUND) {
			return toBitmapContainer().lazyIOR(value2);
		}
		ArrayContainer answer = new ArrayContainer(totalCardinality);
		answer.cardinality =
			Util.unsignedUnion2by2(
				value1.content,
				0,
				value1.getCardinality(),
				value2.content,
				0,
				value2.getCardinality(),
				answer.content
			);
		return answer;
	}
}

/**
 * Forward {@link PeekableCharRankIterator} over an {@link ArrayContainer}'s values. A value's rank
 * equals its array position, so ranks require no extra bookkeeping.
 */
final class ArrayContainerCharIterator implements PeekableCharRankIterator {
	/**
	 * Index within the parent's `content` of the next value to return.
	 */
	int pos;
	/**
	 * Container being iterated.
	 */
	private ArrayContainer parent;

	/**
	 * Creates an unbound iterator; call {@link #wrap(ArrayContainer)} before use.
	 */
	ArrayContainerCharIterator() {
	}

	/**
	 * Creates an iterator positioned at the first value of `p`.
	 */
	ArrayContainerCharIterator(@Nonnull final ArrayContainer p) {
		wrap(p);
	}

	/**
	 * Advances forward to the first value `>=` `minval`.
	 */
	@Override
	public void advanceIfNeeded(final char minval) {
		this.pos = Util.advanceUntil(this.parent.content, this.pos - 1, this.parent.cardinality, minval);
	}

	/**
	 * Rank of the next value, i.e. its 1-based position.
	 */
	@Override
	public int peekNextRank() {
		return this.pos + 1;
	}

	/**
	 * Shallow clone sharing the same parent container. The clone-unsupported path is unreachable
	 * because this iterator implements {@link Cloneable}.
	 */
	@Nonnull
	@Override
	public PeekableCharRankIterator clone() {
		try {
			return (PeekableCharRankIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e); // unreachable, this iterator implements Cloneable
		}
	}

	/**
	 * Whether any value remains.
	 */
	@Override
	public boolean hasNext() {
		return this.pos < this.parent.cardinality;
	}

	/**
	 * Returns the next value and advances.
	 */
	@Override
	public char next() {
		return this.parent.content[this.pos++];
	}

	/**
	 * Returns the next value as an unsigned int and advances.
	 */
	@Override
	public int nextAsInt() {
		return (this.parent.content[this.pos++]);
	}

	/**
	 * Returns the next value without advancing.
	 */
	@Override
	public char peekNext() {
		return this.parent.content[this.pos];
	}

	/**
	 * Removes the last returned value from the parent container.
	 */
	@Override
	public void remove() {
		this.parent.removeAtIndex(this.pos - 1);
		this.pos--;
	}

	/**
	 * (Re)binds this iterator to `p`, positioned at its first value.
	 */
	void wrap(@Nonnull final ArrayContainer p) {
		this.parent = p;
		this.pos = 0;
	}
}

/**
 * Reverse {@link PeekableCharIterator} over an {@link ArrayContainer}, walking values from largest
 * to smallest.
 */
final class ReverseArrayContainerCharIterator implements PeekableCharIterator {
	/**
	 * Index within the parent's `content` of the next value to return (walked downward).
	 */
	int pos;
	/**
	 * Container being iterated.
	 */
	private ArrayContainer parent;

	/**
	 * Creates an unbound iterator; call {@link #wrap(ArrayContainer)} before use.
	 */
	ReverseArrayContainerCharIterator() {
	}

	/**
	 * Creates an iterator positioned at the last (largest) value of `p`.
	 */
	ReverseArrayContainerCharIterator(@Nonnull final ArrayContainer p) {
		wrap(p);
	}

	/**
	 * Advances downward to the first value `<=` `maxval`, exhausting the cursor when this container
	 * holds no such value.
	 */
	@Override
	public void advanceIfNeeded(final char maxval) {
		final int candidate = Util.reverseUntil(this.parent.content, this.pos + 1, maxval);
		// reverseUntil saturates at index 0 instead of reporting "no match", so a container whose
		// smallest value still exceeds maxval would leave the cursor parked above the requested
		// bound; the iterator contract requires exhaustion there, as the bitmap and run cursors do
		this.pos = candidate == 0 && this.parent.content[0] > maxval ? -1 : candidate;
	}

	/**
	 * Shallow clone sharing the same parent container. The clone-unsupported path is unreachable
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
	 * Whether any value remains (position has not passed the start of the array).
	 */
	@Override
	public boolean hasNext() {
		return this.pos >= 0;
	}

	/**
	 * Returns the next value and steps toward smaller values.
	 */
	@Override
	public char next() {
		return this.parent.content[this.pos--];
	}

	/**
	 * Returns the next value as an unsigned int and steps toward smaller values.
	 */
	@Override
	public int nextAsInt() {
		return (this.parent.content[this.pos--]);
	}

	/**
	 * Returns the next value without advancing.
	 */
	@Override
	public char peekNext() {
		return this.parent.content[this.pos];
	}

	/**
	 * Removes the last returned value from the parent container.
	 */
	@Override
	public void remove() {
		this.parent.removeAtIndex(this.pos + 1);
		this.pos++;
	}

	/**
	 * (Re)binds this iterator to `p`, positioned at its last (largest) value.
	 */
	void wrap(@Nonnull final ArrayContainer p) {
		this.parent = p;
		this.pos = this.parent.cardinality - 1;
	}
}
