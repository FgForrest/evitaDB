/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Sorted, parallel-array store of 16-bit {@link Container}s that backs a
 * {@link PersistentRoaringBitmap}. A roaring bitmap partitions the 32-bit value space into 2^16
 * chunks; this class keeps one entry per non-empty chunk, with `keys[i]` holding the chunk's high
 * 16 bits and `values[i]` the container of low 16 bits belonging to that chunk.
 *
 * Representation and invariants:
 *
 * - Only the first `size` slots of `keys`/`values` are live; the rest is spare capacity that grows
 * amortized (see {@link #extendArray}). `keys` and `values` are always kept the same length.
 * - Entries are ordered strictly ascending by unsigned key, so a key lookup is an `O(log size)`
 * unsigned binary search ({@link #getIndex}, {@link #getContainerIndex}) while an in-order
 * {@link #append(char, Container)} is amortized `O(1)`. The ordering invariant is asserted by
 * {@link #validate}.
 *
 * Copy-on-write: this class does not track container sharing itself. The owning
 * {@link PersistentRoaringBitmap} keeps a parallel `boolean[]` flag array indexed in lockstep with
 * these slots and clones a container before mutating it, so {@link #getContainerAtIndex} may return
 * an instance still aliased by another bitmap — a caller that does not own it must not mutate it in
 * place.
 *
 * Serialization uses the portable little-endian Roaring format ({@link #serialize(DataOutput)} /
 * {@link #deserialize(DataInput)}), not default Java serialization; `serialVersionUID` was bumped
 * to `8` when run containers were introduced. Not meant to be used by end users.
 */
final class RoaringArray implements Cloneable, Externalizable, AppendableStorage<Container> {
	private static final char SERIAL_COOKIE_NO_RUNCONTAINER = 12346;
	private static final char SERIAL_COOKIE = 12347;
	private static final int NO_OFFSET_THRESHOLD = 4;

	// bumped serialVersionUID with runcontainers, so default serialization
	// will not work...
	@Serial private static final long serialVersionUID = 8L;

	static final int INITIAL_CAPACITY = 4;

	/**
	 * Shared empty backing arrays used by {@link #clear} to reset the store without null-assigning the
	 * `@Nonnull` `keys`/`values` fields. Every `deserialize` reallocates exact-sized arrays before any
	 * live use, so these length-0 constants are never mutated (growth goes through {@code Arrays.copyOf}).
	 */
	private static final char[] EMPTY_KEYS = new char[0];
	private static final Container[] EMPTY_VALUES = new Container[0];

	/**
	 * Chunk keys — the high 16 bits (unsigned) of the values held by the matching `values[i]`
	 * container. Strictly ascending over `keys[0..size-1]`; slots at and beyond `size` are spare
	 * capacity. Non-null in every observable state — allocated by every constructor and only
	 * transiently reset inside {@link #clear}, which is immediately followed by reallocation during
	 * deserialization.
	 */
	@Nonnull char[] keys;

	/**
	 * Per-chunk containers holding the low 16 bits for the key at the same index; always the same
	 * length as `keys`, with only `values[0..size-1]` live. An entry may be a reference shared with
	 * another bitmap under copy-on-write (see the class comment). Non-null in every observable state,
	 * mirroring `keys`.
	 */
	@Nonnull Container[] values;

	/**
	 * Number of live entries — the length of the used `keys`/`values` prefix; the backing arrays are
	 * typically longer to leave room for amortized growth.
	 */
	int size = 0;

	/**
	 * Copy-on-write flag for `keys`/`values` themselves (as opposed to the containers they hold —
	 * see the class comment). When `true`, both arrays are co-owned by another `RoaringArray`
	 * (created by {@link PersistentRoaringBitmap#clone()}) and MUST NOT be written to in place —
	 * {@link #defrost()} must run first. Lets `clone()` share the backing arrays instead of copying
	 * them, deferring the O(size) copy to the first structural write, which in the hot MVCC commit
	 * path never comes (the cloned bitmap is read-only for the rest of its lifetime).
	 */
	boolean frozen = false;

	/**
	 * The array-level copy-on-write guard: if {@link #frozen}, replaces `keys`/`values` with private
	 * copies before the caller writes to them, mirroring `copyIfShared` in
	 * {@link PersistentRoaringBitmap} which does the same for individual containers. Must be called
	 * at the head of every method that writes into `keys[]`/`values[]` in place.
	 */
	private void defrost() {
		if (this.frozen) {
			this.keys = Arrays.copyOf(this.keys, this.size);
			this.values = Arrays.copyOf(this.values, this.size);
			this.frozen = false;
		}
	}

	/**
	 * Creates an empty store with the default backing capacity ({@link #INITIAL_CAPACITY}).
	 */
	RoaringArray() {
		this(INITIAL_CAPACITY);
	}

	/**
	 * Creates an empty store pre-sized to hold `initialCapacity` chunks before the first growth.
	 *
	 * @param initialCapacity backing-array length to allocate up front
	 */
	RoaringArray(final int initialCapacity) {
		this(new char[initialCapacity], new Container[initialCapacity], 0);
	}

	/**
	 * Adopts the given arrays by reference (no defensive copy) as the backing store. The caller
	 * guarantees that `keys[0..size-1]` is strictly ascending, that `keys` and `values` share a
	 * length, and that `size <= keys.length`.
	 *
	 * @param keys   chunk-key array taken over as-is
	 * @param values container array taken over as-is, parallel to `keys`
	 * @param size   number of live leading entries
	 */
	RoaringArray(@Nonnull final char[] keys, @Nonnull final Container[] values, final int size) {
		this.keys = keys;
		this.values = values;
		this.size = size;
	}

	/**
	 * Finds the smallest index greater than `pos` whose key is `>= x`, using a galloping
	 * (exponential) probe that falls back to binary search — efficient for the common case of
	 * scanning forward a short distance. Returns `size` when no such key exists. Based on code by
	 * O. Kaser.
	 *
	 * @param x   the minimal key sought (unsigned)
	 * @param pos index to advance past (exclusive lower bound)
	 * @return the smallest index greater than `pos` with `keys[index] >= x`, or `size` if none
	 */
	int advanceUntil(final char x, final int pos) {
		int lower = pos + 1;

		// special handling for a possibly common sequential case
		if (lower >= this.size || this.keys[lower] >= x) {
			return lower;
		}

		int spansize = 1; // could set larger
		// bootstrap an upper limit

		while (lower + spansize < this.size && (this.keys[lower + spansize]) < x) {
			spansize *= 2; // hoping for compiler will reduce to shift
		}
		int upper = (lower + spansize < this.size) ? lower + spansize : this.size - 1;

		// maybe we are lucky (could be common case when the seek ahead
		// expected to be small and sequential will otherwise make us look bad)
		if (this.keys[upper] == x) {
			return upper;
		}

		if (this.keys[upper] < x) { // means array has no item key >=
			// x
			return this.size;
		}

		// we know that the next-smallest span was too small
		lower += spansize / 2;

		// else begin binary search
		// invariant: array[lower]<x && array[upper]>x
		while (lower + 1 != upper) {
			int mid = (lower + upper) / 2;
			if (this.keys[mid] == x) {
				return mid;
			} else if (this.keys[mid] < x) {
				lower = mid;
			} else {
				upper = mid;
			}
		}
		return upper;
	}

	@Override
	public void append(final char key, @Nonnull final Container value) {
		defrost();
		if (this.size > 0 && key < this.keys[this.size - 1]) {
			throw new IllegalArgumentException("append only: " + (key) + " < " + (this.keys[this.size - 1]));
		}
		extendArray(1);
		this.keys[this.size] = key;
		this.values[this.size] = value;
		this.size++;
	}

	/**
	 * Bulk-appends every live entry of `roaringArray` after this array's entries, preserving order.
	 * All keys of the argument must be greater than this array's current last key. Containers are
	 * taken over by reference (shallow), not cloned.
	 *
	 * @param roaringArray source whose entries are appended
	 */
	void append(@Nonnull final RoaringArray roaringArray) {
		defrost();
		assert this.size == 0 || roaringArray.size == 0 || this.keys[this.size - 1] < roaringArray.keys[0];
		if (roaringArray.size != 0 && this.size != 0) {
			this.keys = Arrays.copyOf(this.keys, this.size + roaringArray.size);
			this.values = Arrays.copyOf(this.values, this.size + roaringArray.size);
			System.arraycopy(roaringArray.keys, 0, this.keys, this.size, roaringArray.size);
			System.arraycopy(roaringArray.values, 0, this.values, this.size, roaringArray.size);
			this.size += roaringArray.size;
		} else if (this.size == 0 && roaringArray.size != 0) {
			this.keys = Arrays.copyOf(roaringArray.keys, roaringArray.keys.length);
			this.values = Arrays.copyOf(roaringArray.values, roaringArray.values.length);
			this.size = roaringArray.size;
		}
	}

	/**
	 * Append copies of the values AFTER a specified key (may or may not be present) to end.
	 *
	 * @param sa          other array
	 * @param beforeStart given key is the largest key that we won't copy
	 */
	void appendCopiesAfter(@Nonnull final RoaringArray sa, final char beforeStart) {
		defrost();
		int startLocation = sa.getIndex(beforeStart);
		if (startLocation >= 0) {
			startLocation++;
		} else {
			startLocation = -startLocation - 1;
		}
		extendArray(sa.size - startLocation);

		for (int i = startLocation; i < sa.size; ++i) {
			this.keys[this.size] = sa.keys[i];
			this.values[this.size] = sa.values[i].clone();
			this.size++;
		}
	}

	/**
	 * Append copies of the values from another array, from the start
	 *
	 * @param sourceArray The array to copy from
	 * @param stoppingKey any equal or larger key in other array will terminate copying
	 */
	void appendCopiesUntil(@Nonnull final RoaringArray sourceArray, final char stoppingKey) {
		defrost();
		for (int i = 0; i < sourceArray.size; ++i) {
			if (sourceArray.keys[i] >= stoppingKey) {
				break;
			}
			extendArray(1);
			this.keys[this.size] = sourceArray.keys[i];
			this.values[this.size] = sourceArray.values[i].clone();
			this.size++;
		}
	}

	/**
	 * Append copy of the one value from another array
	 *
	 * @param sa    other array
	 * @param index index in the other array
	 */
	void appendCopy(@Nonnull final RoaringArray sa, final int index) {
		defrost();
		extendArray(1);
		this.keys[this.size] = sa.keys[index];
		this.values[this.size] = sa.values[index].clone();
		this.size++;
	}

	/**
	 * Append copies of the values from another array
	 *
	 * @param sa            other array
	 * @param startingIndex starting index in the other array
	 * @param end           endingIndex (exclusive) in the other array
	 */
	void appendCopy(@Nonnull final RoaringArray sa, final int startingIndex, final int end) {
		defrost();
		extendArray(end - startingIndex);
		for (int i = startingIndex; i < end; ++i) {
			this.keys[this.size] = sa.keys[i];
			this.values[this.size] = sa.values[i].clone();
			this.size++;
		}
	}

	/**
	 * Append the values from another array, no copy is made (use with care)
	 *
	 * @param sa            other array
	 * @param startingIndex starting index in the other array
	 * @param end           endingIndex (exclusive) in the other array
	 */
	void append(@Nonnull final RoaringArray sa, final int startingIndex, final int end) {
		defrost();
		extendArray(end - startingIndex);
		for (int i = startingIndex; i < end; ++i) {
			this.keys[this.size] = sa.keys[i];
			this.values[this.size] = sa.values[i];
			this.size++;
		}
	}

	private int binarySearch(final int begin, final int end, final char key) {
		return Util.unsignedBinarySearch(this.keys, begin, end, key);
	}

	/**
	 * Resets to empty by dropping the backing arrays entirely; they are reallocated on next use.
	 */
	private void clear() {
		// reset to shared empty arrays rather than null: the deserialize reallocation guard reads
		// `this.keys` right after this call, and the `@Nonnull` fields must never observably hold null.
		this.keys = EMPTY_KEYS;
		this.values = EMPTY_VALUES;
		this.size = 0;
	}

	/**
	 * If possible, recover wasted memory.
	 */
	public void trim() {
		defrost();
		this.keys = Arrays.copyOf(this.keys, this.size);
		this.values = Arrays.copyOf(this.values, this.size);
		for (final Container c : this.values) {
			c.trim();
		}
	}

	/**
	 * Returns a deep copy trimmed to `size`: the backing arrays are copied and every container is
	 * itself cloned, so the result shares no mutable state with this array.
	 *
	 * @return an independent deep copy
	 * @throws CloneNotSupportedException never in practice; inherited from {@link Object#clone}
	 */
	@Nonnull
	@Override
	public RoaringArray clone() throws CloneNotSupportedException {
		RoaringArray sa;
		sa = (RoaringArray) super.clone();
		sa.keys = Arrays.copyOf(this.keys, this.size);
		sa.values = Arrays.copyOf(this.values, this.size);
		for (int k = 0; k < this.size; ++k) {
			sa.values[k] = sa.values[k].clone();
		}
		sa.size = this.size;
		return sa;
	}

	/**
	 * Shifts the entries in `[begin, end)` down to start at `newBegin`, overwriting the destination
	 * range in place. Assumes `begin <= end` and `newBegin < begin`; does not adjust `size`.
	 *
	 * @param begin    first source index (inclusive)
	 * @param end      end source index (exclusive)
	 * @param newBegin destination start index
	 */
	void copyRange(final int begin, final int end, final int newBegin) {
		defrost();
		// assuming begin <= end and newBegin < begin
		final int range = end - begin;
		System.arraycopy(this.keys, begin, this.keys, newBegin, range);
		System.arraycopy(this.values, begin, this.values, newBegin, range);
	}

	/**
	 * Deserialize. If the DataInput is available as a byte[] or a ByteBuffer, you could prefer
	 * relying on {@link #deserialize(ByteBuffer)}. If the InputStream is `>= 8kB`, you could prefer
	 * relying on {@link #deserialize(DataInput, byte[])};
	 *
	 * @param in the DataInput stream
	 * @throws IOException          Signals that an I/O exception has occurred.
	 * @throws InvalidRoaringFormat if a Roaring Bitmap cookie is missing.
	 */
	public void deserialize(@Nonnull final DataInput in) throws IOException {
		this.clear();
		// little endian
		final int cookie = Integer.reverseBytes(in.readInt());
		if ((cookie & 0xFFFF) != SERIAL_COOKIE && cookie != SERIAL_COOKIE_NO_RUNCONTAINER) {
			throw new InvalidRoaringFormat("I failed to find a valid cookie.");
		}
		this.size =
			((cookie & 0xFFFF) == SERIAL_COOKIE)
				? (cookie >>> 16) + 1
				: Integer.reverseBytes(in.readInt());
		// logically we cannot have more than (1<<16) containers.
		if (this.size > (1 << 16)) {
			throw new InvalidRoaringFormat("Size too large");
		}
		if ((this.keys == null) || (this.keys.length < this.size) || this.frozen) {
			this.keys = new char[this.size];
			this.values = new Container[this.size];
			this.frozen = false;
		}

		byte[] bitmapOfRunContainers = null;
		boolean hasrun = (cookie & 0xFFFF) == SERIAL_COOKIE;
		if (hasrun) {
			bitmapOfRunContainers = new byte[(this.size + 7) / 8];
			in.readFully(bitmapOfRunContainers);
		}

		final char[] keys = new char[this.size];
		final int[] cardinalities = new int[this.size];
		final boolean[] isBitmap = new boolean[this.size];
		for (int k = 0; k < this.size; ++k) {
			keys[k] = Character.reverseBytes(in.readChar());
			cardinalities[k] = 1 + (0xFFFF & Character.reverseBytes(in.readChar()));

			isBitmap[k] = cardinalities[k] > ArrayContainer.DEFAULT_MAX_SIZE;
			if (bitmapOfRunContainers != null && (bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0) {
				isBitmap[k] = false;
			}
		}
		if ((!hasrun) || (this.size >= NO_OFFSET_THRESHOLD)) {
			// skipping the offsets
			in.skipBytes(this.size * 4);
		}
		// Reading the containers
		for (int k = 0; k < this.size; ++k) {
			Container val;
			if (isBitmap[k]) {
				final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];
				// little endian
				for (int l = 0; l < bitmapArray.length; ++l) {
					bitmapArray[l] = Long.reverseBytes(in.readLong());
				}
				val = new BitmapContainer(bitmapArray, cardinalities[k]);
			} else if (bitmapOfRunContainers != null
				&& ((bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0)) {
				// cf RunContainer.writeArray()
				int nbrruns = (Character.reverseBytes(in.readChar()));
				final char[] lengthsAndValues = new char[2 * nbrruns];

				for (int j = 0; j < 2 * nbrruns; ++j) {
					lengthsAndValues[j] = Character.reverseBytes(in.readChar());
				}
				val = new RunContainer(lengthsAndValues, nbrruns);
			} else {
				final char[] charArray = new char[cardinalities[k]];
				for (int l = 0; l < charArray.length; ++l) {
					charArray[l] = Character.reverseBytes(in.readChar());
				}
				val = new ArrayContainer(charArray);
			}
			this.keys[k] = keys[k];
			this.values[k] = val;
		}
	}

	/**
	 * Deserialize.
	 *
	 * @param in     the DataInput stream
	 * @param buffer The buffer gets overwritten with data during deserialization. You can pass a NULL
	 *               reference as a buffer. A buffer containing at least 8192 bytes might be ideal for
	 *               performance. It is recommended to reuse the buffer between calls to deserialize (in a
	 *               single-threaded context) for best performance.
	 * @throws IOException          Signals that an I/O exception has occurred.
	 * @throws InvalidRoaringFormat if a Roaring Bitmap cookie is missing.
	 */
	public void deserialize(@Nonnull final DataInput in, @Nullable byte[] buffer) throws IOException {
		if (buffer != null && buffer.length == 0) {
			// Get rid of this useless buffer
			buffer = null;
		} else if (buffer != null && buffer.length % 8 != 0) {
			// This is necessary not to handle manually the gap between a ShortBuffer|LongBuffer and the
			// provided byte[]
			throw new IllegalArgumentException(
				"We need a buffer with a length multiple of 8. was length=" + buffer.length);
		}

		this.clear();
		// little endian
		final int cookie = Integer.reverseBytes(in.readInt());
		if ((cookie & 0xFFFF) != SERIAL_COOKIE && cookie != SERIAL_COOKIE_NO_RUNCONTAINER) {
			throw new InvalidRoaringFormat("I failed to find a valid cookie.");
		}
		this.size =
			((cookie & 0xFFFF) == SERIAL_COOKIE)
				? (cookie >>> 16) + 1
				: Integer.reverseBytes(in.readInt());
		// logically we cannot have more than (1<<16) containers.
		if (this.size > (1 << 16)) {
			throw new InvalidRoaringFormat("Size too large");
		}
		if ((this.keys == null) || (this.keys.length < this.size) || this.frozen) {
			this.keys = new char[this.size];
			this.values = new Container[this.size];
			this.frozen = false;
		}

		byte[] bitmapOfRunContainers = null;
		boolean hasrun = (cookie & 0xFFFF) == SERIAL_COOKIE;
		if (hasrun) {
			bitmapOfRunContainers = new byte[(this.size + 7) / 8];
			in.readFully(bitmapOfRunContainers);
		}

		final char[] keys = new char[this.size];
		final int[] cardinalities = new int[this.size];
		final boolean[] isBitmap = new boolean[this.size];
		for (int k = 0; k < this.size; ++k) {
			keys[k] = Character.reverseBytes(in.readChar());
			cardinalities[k] = 1 + (0xFFFF & Character.reverseBytes(in.readChar()));

			isBitmap[k] = cardinalities[k] > ArrayContainer.DEFAULT_MAX_SIZE;
			if (bitmapOfRunContainers != null && (bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0) {
				isBitmap[k] = false;
			}
		}
		if ((!hasrun) || (this.size >= NO_OFFSET_THRESHOLD)) {
			// skipping the offsets
			in.skipBytes(this.size * 4);
		}

		// Reading the containers
		for (int k = 0; k < this.size; ++k) {
			Container val;
			if (isBitmap[k]) {
				final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];

				if (buffer == null) {
					// a buffer to load a Container in a single .readFully
					// We initialize it with the length of a BitmapContainer
					buffer = new byte[(BitmapContainer.MAX_CAPACITY / 64) * 8];
				}

				if (buffer.length < (BitmapContainer.MAX_CAPACITY / 64) * 8) {
					// We have been provided a rather small buffer

					for (int iBlock = 0; iBlock <= 8 * bitmapArray.length / buffer.length; iBlock++) {
						int start = buffer.length * iBlock;
						int end = Math.min(buffer.length * (iBlock + 1), 8 * bitmapArray.length);

						in.readFully(buffer, 0, end - start);

						// little endian
						ByteBuffer asByteBuffer = ByteBuffer.wrap(buffer);
						asByteBuffer.order(LITTLE_ENDIAN);

						LongBuffer asLongBuffer = asByteBuffer.asLongBuffer();
						asLongBuffer.rewind();
						asLongBuffer.get(bitmapArray, start / 8, (end - start) / 8);
					}

				} else {
					// Read the whole bitmapContainer in a single pass
					in.readFully(buffer, 0, bitmapArray.length * 8);

					// little endian
					ByteBuffer asByteBuffer = ByteBuffer.wrap(buffer);
					asByteBuffer.order(LITTLE_ENDIAN);

					LongBuffer asLongBuffer = asByteBuffer.asLongBuffer();
					asLongBuffer.rewind();
					asLongBuffer.get(bitmapArray);
				}
				val = new BitmapContainer(bitmapArray, cardinalities[k]);
			} else if (bitmapOfRunContainers != null
				&& ((bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0)) {
				// cf RunContainer.writeArray()
				int nbrruns = (Character.reverseBytes(in.readChar()));
				final char[] lengthsAndValues = new char[2 * nbrruns];

				if (buffer == null && lengthsAndValues.length > (BitmapContainer.MAX_CAPACITY / 64) * 8) {
					// a buffer to load a Container in a single .readFully
					// We initialize it with the length of a BitmapContainer
					buffer = new byte[(BitmapContainer.MAX_CAPACITY / 64) * 8];
				}

				if (buffer == null) {
					// The RunContainer is small: skip the buffer allocation
					for (int j = 0; j < lengthsAndValues.length; ++j) {
						lengthsAndValues[j] = Character.reverseBytes(in.readChar());
					}
				} else {
					for (int iBlock = 0; iBlock <= 2 * lengthsAndValues.length / buffer.length; iBlock++) {
						int start = buffer.length * iBlock;
						int end = Math.min(buffer.length * (iBlock + 1), 2 * lengthsAndValues.length);

						in.readFully(buffer, 0, end - start);

						// little endian
						ByteBuffer asByteBuffer = ByteBuffer.wrap(buffer);
						asByteBuffer.order(LITTLE_ENDIAN);

						CharBuffer asCharBuffer = asByteBuffer.asCharBuffer();
						asCharBuffer.rewind();
						asCharBuffer.get(lengthsAndValues, start / 2, (end - start) / 2);
					}
				}

				val = new RunContainer(lengthsAndValues, nbrruns);
			} else {
				final char[] charArray = new char[cardinalities[k]];

				if (buffer == null && charArray.length > (BitmapContainer.MAX_CAPACITY / 64) * 8) {
					// a buffer to load a Container in a single .readFully
					// We initialize it with the length of a BitmapContainer
					buffer = new byte[(BitmapContainer.MAX_CAPACITY / 64) * 8];
				}

				if (buffer == null) {
					// The ArrayContainer is small: skip the buffer allocation
					for (int j = 0; j < charArray.length; ++j) {
						charArray[j] = Character.reverseBytes(in.readChar());
					}
				} else {
					for (int iBlock = 0; iBlock <= 2 * charArray.length / buffer.length; iBlock++) {
						int start = buffer.length * iBlock;
						int end = Math.min(buffer.length * (iBlock + 1), 2 * charArray.length);

						in.readFully(buffer, 0, end - start);

						// little endian
						ByteBuffer asByteBuffer = ByteBuffer.wrap(buffer);
						asByteBuffer.order(LITTLE_ENDIAN);

						CharBuffer asCharBuffer = asByteBuffer.asCharBuffer();
						asCharBuffer.rewind();
						asCharBuffer.get(charArray, start / 2, (end - start) / 2);
					}
				}

				val = new ArrayContainer(charArray);
			}
			this.keys[k] = keys[k];
			this.values[k] = val;
		}
	}

	/**
	 * Deserialize (retrieve) this bitmap. See format specification at
	 * https://github.com/RoaringBitmap/RoaringFormatSpec
	 *
	 * The current bitmap is overwritten.
	 *
	 * It is not necessary that limit() on the input ByteBuffer indicates the end of the serialized
	 * data.
	 *
	 * After loading this PersistentRoaringBitmap, you can advance to the rest of the data (if there
	 * is more) by setting bbf.position(bbf.position() + bitmap.serializedSizeInBytes());
	 *
	 * Note that the input ByteBuffer is effectively copied (with the slice operation) so you should
	 * expect the provided ByteBuffer position/mark/limit/order to remain unchanged.
	 *
	 * @param bbf the byte buffer (can be mapped, direct, array backed etc.
	 */
	public void deserialize(@Nonnull final ByteBuffer bbf) {
		this.clear();

		// slice not to mutate the input ByteBuffer
		ByteBuffer buffer = bbf.slice();
		buffer.order(LITTLE_ENDIAN);
		final int cookie = buffer.getInt();
		if ((cookie & 0xFFFF) != SERIAL_COOKIE && cookie != SERIAL_COOKIE_NO_RUNCONTAINER) {
			throw new InvalidRoaringFormat("I failed to find one of the right cookies. " + cookie);
		}
		boolean hasRunContainers = (cookie & 0xFFFF) == SERIAL_COOKIE;
		this.size = hasRunContainers ? (cookie >>> 16) + 1 : buffer.getInt();
		// For now, we consider the limit is already set by the caller

		// logically we cannot have more than (1<<16) containers.
		if (this.size > (1 << 16)) {
			throw new InvalidRoaringFormat("Size too large");
		}
		if ((this.keys == null) || (this.keys.length < this.size) || this.frozen) {
			this.keys = new char[this.size];
			this.values = new Container[this.size];
			this.frozen = false;
		}

		byte[] bitmapOfRunContainers = null;
		boolean hasrun = (cookie & 0xFFFF) == SERIAL_COOKIE;
		if (hasrun) {
			bitmapOfRunContainers = new byte[(this.size + 7) / 8];
			buffer.get(bitmapOfRunContainers);
		}

		final char[] keys = new char[this.size];
		final int[] cardinalities = new int[this.size];
		final boolean[] isBitmap = new boolean[this.size];
		for (int k = 0; k < this.size; ++k) {
			keys[k] = buffer.getChar();
			cardinalities[k] = 1 + (0xFFFF & buffer.getChar());

			isBitmap[k] = cardinalities[k] > ArrayContainer.DEFAULT_MAX_SIZE;
			if (bitmapOfRunContainers != null && (bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0) {
				isBitmap[k] = false;
			}
		}
		if ((!hasrun) || (this.size >= NO_OFFSET_THRESHOLD)) {
			// skipping the offsets
			buffer.position(buffer.position() + this.size * 4);
		}

		// Reading the containers
		for (int k = 0; k < this.size; ++k) {
			Container val;
			if (isBitmap[k]) {
				final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];

				buffer.asLongBuffer().get(bitmapArray);
				buffer.position(buffer.position() + bitmapArray.length * 8);

				val = new BitmapContainer(bitmapArray, cardinalities[k]);
			} else if (bitmapOfRunContainers != null
				&& ((bitmapOfRunContainers[k / 8] & (1 << (k % 8))) != 0)) {
				// cf RunContainer.writeArray()
				int nbrruns = (buffer.getChar());
				final char[] lengthsAndValues = new char[2 * nbrruns];

				buffer.asCharBuffer().get(lengthsAndValues);
				buffer.position(buffer.position() + lengthsAndValues.length * 2);

				val = new RunContainer(lengthsAndValues, nbrruns);
			} else {
				final char[] charArray = new char[cardinalities[k]];

				buffer.asCharBuffer().get(charArray);
				buffer.position(buffer.position() + charArray.length * 2);

				val = new ArrayContainer(charArray);
			}
			this.keys[k] = keys[k];
			this.values[k] = val;
		}
	}

	// size/keys/values are mutable by design (append-in-place storage); comparing them is intentional
	@SuppressWarnings("NonFinalFieldReferenceInEquals")
	@Override
	public boolean equals(@Nullable final Object o) {
		if (o instanceof RoaringArray srb) {
			if (srb.size != this.size) {
				return false;
			}
			if (Arrays.equals(this.keys, 0, this.size, srb.keys, 0, srb.size)) {
				for (int i = 0; i < this.size; ++i) {
					if (!this.values[i].equals(srb.values[i])) {
						return false;
					}
				}
				return true;
			}
		}
		return false;
	}

	// make sure there is capacity for at least k more elements
	void extendArray(final int k) {
		defrost();
		// size + 1 could overflow
		if (this.size + k > this.keys.length) {
			int newCapacity;
			if (this.keys.length < 1024) {
				newCapacity = 2 * (this.size + k);
			} else {
				newCapacity = 5 * (this.size + k) / 4;
			}
			this.keys = Arrays.copyOf(this.keys, newCapacity);
			this.values = Arrays.copyOf(this.values, newCapacity);
		}
	}

	/**
	 * Looks up the slot for chunk key `x` by unsigned binary search in `O(log size)`.
	 *
	 * @param x the high-16-bit chunk key to find
	 * @return index of the matching entry, or `-(insertionPoint) - 1` (negative) if absent, following
	 * the `java.util.Arrays.binarySearch` convention
	 */
	int getContainerIndex(final char x) {
		return this.binarySearch(0, this.size, x);
	}

	/**
	 * Returns the container at raw slot `i` without bounds or sharing checks. Under copy-on-write the
	 * returned instance may still be aliased by another bitmap, so a caller that does not own it must
	 * clone it (via the owning {@link PersistentRoaringBitmap}) before any in-place mutation.
	 *
	 * @param i live slot index, `0 <= i < size`
	 * @return the container stored at that slot
	 */
	@Nonnull
	Container getContainerAtIndex(final int i) {
		return this.values[i];
	}

	/**
	 * Create a ContainerPointer for this RoaringArray
	 *
	 * @return a ContainerPointer
	 */
	@Nonnull
	public ContainerPointer getContainerPointer() {
		return getContainerPointer(0);
	}

	/**
	 * Create a ContainerPointer for this RoaringArray
	 *
	 * @param startIndex starting index in the container list
	 * @return a ContainerPointer
	 */
	@Nonnull
	public ContainerPointer getContainerPointer(final int startIndex) {
		return new ContainerPointer() {
			int k = startIndex;

			@Override
			public void advance() {
				++this.k;
			}

			@Nonnull
			@Override
			public ContainerPointer clone() {
				try {
					return (ContainerPointer) super.clone();
				} catch (CloneNotSupportedException e) {
					// unreachable: the pointer implements Cloneable
					throw new IllegalStateException(e);
				}
			}

			@Override
			public int compareTo(@Nonnull final ContainerPointer o) {
				if (key() != o.key()) {
					return key() - o.key();
				}
				return o.getCardinality() - getCardinality();
			}

			@Override
			public int getCardinality() {
				final Container container = Objects.requireNonNull(
					getContainer(), "container pointer accessed after exhaustion");
				return container.getCardinality();
			}

			@Nullable
			@Override
			public Container getContainer() {
				if (this.k >= RoaringArray.this.size) {
					return null;
				}
				return RoaringArray.this.values[this.k];
			}

			@Override
			public boolean isBitmapContainer() {
				final Container container = Objects.requireNonNull(
					getContainer(), "container pointer accessed after exhaustion");
				return container instanceof BitmapContainer;
			}

			@Override
			public boolean isRunContainer() {
				final Container container = Objects.requireNonNull(
					getContainer(), "container pointer accessed after exhaustion");
				return container instanceof RunContainer;
			}

			@Override
			public char key() {
				return RoaringArray.this.keys[this.k];
			}
		};
	}

	/**
	 * Looks up the slot for chunk key `x`, fast-pathing the empty array and a repeated last key
	 * before falling back to an `O(log size)` unsigned binary search.
	 *
	 * @param x the high-16-bit chunk key to find
	 * @return index of the matching entry, or `-(insertionPoint) - 1` (negative) if absent
	 */
	int getIndex(final char x) {
		// before the binary search, we optimize for frequent cases
		if ((this.size == 0) || (this.keys[this.size - 1] == x)) {
			return this.size - 1;
		}
		// no luck we have to go through the list
		return this.binarySearch(0, this.size, x);
	}

	/**
	 * Returns the high-16-bit chunk key at raw slot `i` (`0 <= i < size`).
	 *
	 * @param i live slot index
	 * @return the unsigned chunk key stored there
	 */
	char getKeyAtIndex(final int i) {
		return this.keys[i];
	}

	// size/keys/values are mutable by design (append-in-place storage); hashing them is intentional
	@SuppressWarnings("NonFinalFieldReferencedInHashCode")
	@Override
	public int hashCode() {
		int hashvalue = 0;
		for (int k = 0; k < this.size; ++k) {
			hashvalue = 31 * hashvalue + this.keys[k] * 0xF0F0F0 + this.values[k].hashCode();
		}
		return hashvalue;
	}

	/**
	 * Validate the RoaringArray. This is useful for checking a recently
	 * deserialized RoaringArray.
	 *
	 * @return true if the RoaringArray is valid
	 */
	public boolean validate() {
		for (int k = 0; k < this.size; ++k) {
			if (k > 0 && this.keys[k - 1] >= this.keys[k]) {
				return false;
			}
			if (this.values[k] == null) {
				return false;
			}
			if (!this.values[k].validate()) {
				return false;
			}
		}
		return true;
	}

	private boolean hasRunContainer() {
		for (int k = 0; k < this.size; ++k) {
			final Container ck = this.values[k];
			if (ck instanceof RunContainer) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Byte size of the serialized header (cookie, optional run-container bitmap and per-chunk
	 * offsets) that precedes the container payloads, matching the layout written by
	 * {@link #serialize(DataOutput)}.
	 */
	private int headerSize() {
		if (hasRunContainer()) {
			if (this.size < NO_OFFSET_THRESHOLD) { // for small bitmaps, we omit the offsets
				return 4 + (this.size + 7) / 8 + 4 * this.size;
			}
			return 4 + (this.size + 7) / 8 + 8 * this.size; // - 4 because we pack the size with the cookie
		} else {
			return 4 + 4 + 8 * this.size;
		}
	}

	// insert a new key, it is assumed that it does not exist
	void insertNewKeyValueAt(final int i, final char key, @Nonnull final Container value) {
		defrost();
		extendArray(1);
		System.arraycopy(this.keys, i, this.keys, i + 1, this.size - i);
		this.keys[i] = key;
		System.arraycopy(this.values, i, this.values, i + 1, this.size - i);
		this.values[i] = value;
		this.size++;
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException {
		deserialize(in);
	}

	/**
	 * Removes the entry at slot `i`, shifting the tail left and nulling the now-vacated last slot so
	 * no stale container reference is retained.
	 *
	 * @param i live slot index to remove
	 */
	void removeAtIndex(final int i) {
		defrost();
		System.arraycopy(this.keys, i + 1, this.keys, i, this.size - i - 1);
		this.keys[this.size - 1] = 0;
		System.arraycopy(this.values, i + 1, this.values, i, this.size - i - 1);
		this.values[this.size - 1] = null;
		this.size--;
	}

	/**
	 * Removes the entries in `[begin, end)`, shifting the tail left and clearing the vacated trailing
	 * slots. A no-op when `end <= begin`.
	 *
	 * @param begin first slot to remove (inclusive)
	 * @param end   end slot (exclusive)
	 */
	void removeIndexRange(final int begin, final int end) {
		if (end <= begin) {
			return;
		}
		defrost();
		final int range = end - begin;
		System.arraycopy(this.keys, end, this.keys, begin, this.size - end);
		System.arraycopy(this.values, end, this.values, begin, this.size - end);
		for (int i = 1; i <= range; ++i) {
			this.keys[this.size - i] = 0;
			this.values[this.size - i] = null;
		}
		this.size -= range;
	}

	/**
	 * Adopts freshly built backing arrays wholesale, replacing the current contents in a single step.
	 * Used by the bulk-merge union / xor path in {@link PersistentRoaringBitmap} to install a one-pass
	 * merge result instead of shifting entries per key (which is quadratic when the operands' keys are
	 * interleaved). Clears {@link #frozen} because the supplied arrays are privately owned by the
	 * caller and never aliased by a clone. The caller guarantees the ascending-key ordering invariant
	 * on `keys[0..size-1]` and that `size <= keys.length == values.length`.
	 *
	 * @param keys   chunk-key array taken over as-is
	 * @param values container array taken over as-is, parallel to `keys`
	 * @param size   number of live leading entries
	 */
	void adopt(@Nonnull final char[] keys, @Nonnull final Container[] values, final int size) {
		this.keys = keys;
		this.values = values;
		this.size = size;
		this.frozen = false;
	}

	/**
	 * Overwrites both the key and the container at slot `i` in place; the caller is responsible for
	 * preserving the ascending-key invariant.
	 *
	 * @param i   slot to overwrite
	 * @param key replacement chunk key
	 * @param c   replacement container
	 */
	void replaceKeyAndContainerAtIndex(final int i, final char key, @Nonnull final Container c) {
		defrost();
		this.keys[i] = key;
		this.values[i] = c;
	}

	/**
	 * Truncates the live prefix to `newLength`, nulling the dropped container slots. Only shrinks —
	 * assumes `newLength <= size`.
	 *
	 * @param newLength new live-entry count
	 */
	void resize(final int newLength) {
		defrost();
		Arrays.fill(this.keys, newLength, this.size, (char) 0);
		Arrays.fill(this.values, newLength, this.size, null);
		this.size = newLength;
	}

	/**
	 * Serialize.
	 *
	 * The current bitmap is not modified.
	 *
	 * @param out the DataOutput stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void serialize(@Nonnull final DataOutput out) throws IOException {
		int startOffset;
		boolean hasrun = hasRunContainer();
		if (hasrun) {
			out.writeInt(Integer.reverseBytes(SERIAL_COOKIE | ((this.size - 1) << 16)));
			byte[] bitmapOfRunContainers = new byte[(this.size + 7) / 8];
			for (int i = 0; i < this.size; ++i) {
				if (this.values[i] instanceof RunContainer) {
					bitmapOfRunContainers[i / 8] |= (1 << (i % 8));
				}
			}
			out.write(bitmapOfRunContainers);
			if (this.size < NO_OFFSET_THRESHOLD) {
				startOffset = 4 + 4 * this.size + bitmapOfRunContainers.length;
			} else {
				startOffset = 4 + 8 * this.size + bitmapOfRunContainers.length;
			}
		} else { // backwards compatibility
			out.writeInt(Integer.reverseBytes(SERIAL_COOKIE_NO_RUNCONTAINER));
			out.writeInt(Integer.reverseBytes(this.size));
			startOffset = 4 + 4 + 4 * this.size + 4 * this.size;
		}
		for (int k = 0; k < this.size; ++k) {
			out.writeShort(Character.reverseBytes(this.keys[k]));
			out.writeShort(Character.reverseBytes((char) (this.values[k].getCardinality() - 1)));
		}
		if ((!hasrun) || (this.size >= NO_OFFSET_THRESHOLD)) {
			// writing the containers offsets
			for (int k = 0; k < this.size; k++) {
				out.writeInt(Integer.reverseBytes(startOffset));
				startOffset = startOffset + this.values[k].getArraySizeInBytes();
			}
		}
		for (int k = 0; k < this.size; ++k) {
			this.values[k].writeArray(out);
		}
	}

	/**
	 * Serialize.
	 *
	 * The current bitmap is not modified.
	 *
	 * @param buffer the ByteBuffer to write to
	 */
	public void serialize(@Nonnull final ByteBuffer buffer) {
		ByteBuffer buf = buffer.order() == LITTLE_ENDIAN ? buffer : buffer.slice().order(LITTLE_ENDIAN);
		int startOffset;
		boolean hasrun = hasRunContainer();
		if (hasrun) {
			buf.putInt(SERIAL_COOKIE | ((this.size - 1) << 16));
			int offset = buf.position();
			for (int i = 0; i < this.size; i += 8) {
				int runMarker = 0;
				for (int j = 0; j < 8 && i + j < this.size; ++j) {
					if (this.values[i + j] instanceof RunContainer) {
						runMarker |= (1 << j);
					}
				}
				buf.put((byte) runMarker);
			}
			int runMarkersLength = buf.position() - offset;
			if (this.size < NO_OFFSET_THRESHOLD) {
				startOffset = 4 + 4 * this.size + runMarkersLength;
			} else {
				startOffset = 4 + 8 * this.size + runMarkersLength;
			}
		} else { // backwards compatibility
			buf.putInt(SERIAL_COOKIE_NO_RUNCONTAINER);
			buf.putInt(this.size);
			startOffset = 4 + 4 + 4 * this.size + 4 * this.size;
		}
		for (int k = 0; k < this.size; ++k) {
			buf.putChar(this.keys[k]);
			buf.putChar((char) (this.values[k].getCardinality() - 1));
		}
		if ((!hasrun) || (this.size >= NO_OFFSET_THRESHOLD)) {
			// writing the containers offsets
			for (int k = 0; k < this.size; ++k) {
				buf.putInt(startOffset);
				startOffset = startOffset + this.values[k].getArraySizeInBytes();
			}
		}
		for (int k = 0; k < this.size; ++k) {
			this.values[k].writeArray(buf);
		}
		if (buf != buffer) {
			buffer.position(buffer.position() + buf.position());
		}
	}

	/**
	 * Report the number of bytes required for serialization.
	 *
	 * @return the size in bytes
	 */
	public int serializedSizeInBytes() {
		int count = headerSize();
		for (int k = 0; k < this.size; ++k) {
			count += this.values[k].getArraySizeInBytes();
		}
		return count;
	}

	/**
	 * Replaces the container at slot `i`, leaving its key unchanged.
	 *
	 * @param i slot to overwrite
	 * @param c replacement container
	 */
	void setContainerAtIndex(final int i, @Nonnull final Container c) {
		defrost();
		this.values[i] = c;
	}

	/**
	 * Returns the number of live entries (non-empty chunks) currently stored.
	 *
	 * @return the live-entry count
	 */
	int size() {
		return this.size;
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		serialize(out);
	}

	/**
	 * Gets the smallest unsigned (first) integer in the array.
	 *
	 * @return the smallest unsigned (first) integer in the array
	 * @throws NoSuchElementException if empty
	 */
	public int first() {
		assertNonEmpty();
		final char firstKey = this.keys[0];
		final Container container = this.values[0];
		return firstKey << 16 | container.first();
	}

	/**
	 * Gets the largest unsigned (last) integer in the array.
	 *
	 * @return the largest unsigned (last) integer in the array
	 * @throws NoSuchElementException if empty
	 */
	public int last() {
		assertNonEmpty();
		final char lastKey = this.keys[this.size - 1];
		final Container container = this.values[this.size - 1];
		return lastKey << 16 | container.last();
	}

	/**
	 * Gets the smallest signed integer in the array.
	 *
	 * @return the smallest signed integer in the array
	 * @throws NoSuchElementException if empty
	 */
	public int firstSigned() {
		assertNonEmpty();
		int index = advanceUntil((char) (1 << 15), -1);
		if (index == this.size) { // no negatives
			index = 0;
		}
		final char key = this.keys[index];
		final Container container = this.values[index];
		return key << 16 | container.first();
	}

	/**
	 * Gets the largest signed integer in the array.
	 *
	 * @return the largest signed integer in the array
	 * @throws NoSuchElementException if empty
	 */
	public int lastSigned() {
		assertNonEmpty();
		int index = advanceUntil((char) (1 << 15), -1) - 1;
		if (index == -1) { // no positives
			index += this.size;
		}
		final char key = this.keys[index];
		final Container container = this.values[index];
		return key << 16 | container.last();
	}

	private void assertNonEmpty() {
		if (this.size == 0) {
			throw new NoSuchElementException("Empty RoaringArray");
		}
	}
}
