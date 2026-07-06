/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import javax.annotation.Nonnull;

/**
 * Root read-only contract of a 32-bit (unsigned `int`) immutable bitmap.
 *
 * Exposes membership tests, cardinality, ordered iteration, rank/select queries,
 * range scans and serialization. Positions are treated as unsigned 32-bit integers
 * unless a method name explicitly says `signed`. Implementations such as
 * {@link BitmapDataProvider} and {@link PersistentRoaringBitmap} must honour every
 * guarantee documented here.
 */
public interface ImmutableBitmapDataProvider {
	/**
	 * Tests whether `x` is a member of this bitmap (equivalent to `BitSet.get`).
	 *
	 * @param x integer value to test
	 * @return `true` if `x` is present
	 */
	boolean contains(int x);

	/**
	 * Returns the number of distinct integers stored in the bitmap (i.e. the number of
	 * set bits). Computed internally as a 64-bit number and narrowed to `int`.
	 *
	 * @return the cardinality as a 32-bit value
	 */
	int getCardinality();

	/**
	 * Returns the number of distinct integers stored in the bitmap (i.e. the number of
	 * set bits) as a full 64-bit result.
	 *
	 * @return the cardinality as a 64-bit value
	 */
	long getLongCardinality();

	/**
	 * Visits every value in the bitmap in ascending order and passes it to `ic`.
	 *
	 * Usage:
	 *
	 * ```java
	 * bitmap.forEach(value -> {
	 * // do something with value
	 * });
	 * ```
	 *
	 * @param ic consumer invoked once per set bit
	 */
	void forEach(@Nonnull IntConsumer ic);

	/**
	 * Returns an iterator over the set bits in unsigned ascending order. For bulk
	 * traversal prefer {@link #forEach}.
	 *
	 * @return iterator over set bits in unsigned ascending order
	 */
	@Nonnull
	PeekableIntIterator getIntIterator();

	/**
	 * Returns an iterator over the set bits in signed ascending order.
	 *
	 * @return iterator over set bits in signed ascending order
	 */
	@Nonnull
	PeekableIntIterator getSignedIntIterator();

	/**
	 * Returns an iterator over the set bits in unsigned descending order.
	 *
	 * @return iterator over set bits in unsigned descending order
	 */
	@Nonnull
	IntIterator getReverseIntIterator();

	/**
	 * Returns an `ORDERED`, `DISTINCT`, `SORTED`, `SIZED` stream of the set bits in
	 * unsigned ascending order.
	 *
	 * @return ascending int stream
	 */
	@Nonnull
	public default IntStream stream() {
		final int characteristics =
			Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.SIZED;
		final Spliterator.OfInt x =
			Spliterators.spliterator(
				new RoaringOfInt(getIntIterator()), getCardinality(), characteristics);
		return StreamSupport.intStream(x, false);
	}

	/**
	 * Returns an `ORDERED`, `DISTINCT`, `SIZED` stream of the set bits in descending
	 * order.
	 *
	 * @return descending int stream
	 */
	@Nonnull
	public default IntStream reverseStream() {
		final int characteristics = Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED;
		final Spliterator.OfInt x =
			Spliterators.spliterator(
				new RoaringOfInt(getReverseIntIterator()), getCardinality(), characteristics);
		return StreamSupport.intStream(x, false);
	}

	/**
	 * Returns a batch iterator, which may be faster than the other iterators for bulk
	 * consumption.
	 *
	 * @return iterator that yields values in batches
	 */
	@Nonnull
	BatchIterator getBatchIterator();

	/**
	 * Estimates the in-memory footprint of this structure. Computed internally as a
	 * 64-bit counter and narrowed to `int`.
	 *
	 * @return estimated memory usage in bytes
	 */
	int getSizeInBytes();

	/**
	 * Estimates the in-memory footprint of this structure as a full 64-bit value.
	 *
	 * @return estimated memory usage in bytes
	 */
	long getLongSizeInBytes();

	/**
	 * Tests whether the bitmap contains no set bit.
	 *
	 * @return `true` if the bitmap is empty
	 */
	boolean isEmpty();

	/**
	 * Creates a new bitmap of the same class holding at most `x` of the smallest values
	 * of this bitmap.
	 *
	 * @param x maximum cardinality of the result
	 * @return a new bitmap with cardinality no greater than `x`
	 */
	@Nonnull
	ImmutableBitmapDataProvider limit(int x);

	/**
	 * Returns the number of values smaller than or equal to `x`. The rank of the
	 * smallest value is `1`; a value below the smallest yields `0`; `rank(infinity)`
	 * equals {@link #getCardinality()}. Computed internally as a 64-bit number.
	 *
	 * Reference:
	 * [Ranking in statistics](https://en.wikipedia.org/wiki/Ranking#Ranking_in_statistics)
	 *
	 * @param x inclusive upper limit
	 * @return the rank as a 32-bit value
	 */
	int rank(int x);

	/**
	 * Same as {@link #rank(int)} but returns a full 64-bit value; `rankLong(infinity)`
	 * equals {@link #getLongCardinality()}.
	 *
	 * Reference:
	 * [Ranking in statistics](https://en.wikipedia.org/wiki/Ranking#Ranking_in_statistics)
	 *
	 * @param x inclusive upper limit
	 * @return the rank as a 64-bit value
	 */
	long rankLong(int x);

	/**
	 * Counts the values in the half-open interval `[start, end)`. Calling
	 * `rangeCardinality(0, 0x100000000)` yields the total cardinality
	 * ({@link #getLongCardinality()}).
	 *
	 * @param start inclusive lower limit
	 * @param end   exclusive upper limit
	 * @return number of values in `[start, end)`, between `0` and `0x100000000`
	 */
	long rangeCardinality(long start, long end);

	/**
	 * Returns the `j`-th smallest value stored in the bitmap, `0`-based. This differs
	 * in convention from {@link #rank(int)}, which returns `1` for the smallest value.
	 *
	 * Reference:
	 * [Selection algorithm](https://en.wikipedia.org/wiki/Selection_algorithm)
	 *
	 * @param j `0`-based index of the value, must be smaller than the cardinality
	 * @return the value at index `j`
	 * @throws IllegalArgumentException if `j` is not smaller than the cardinality
	 */
	int select(int j);

	/**
	 * Returns the smallest unsigned value in this bitmap.
	 *
	 * @return the smallest unsigned value
	 * @throws NoSuchElementException if the bitmap is empty
	 */
	int first();

	/**
	 * Returns the largest unsigned value in this bitmap.
	 *
	 * @return the largest unsigned value
	 * @throws NoSuchElementException if the bitmap is empty
	 */
	int last();

	/**
	 * Returns the smallest signed value in this bitmap.
	 *
	 * @return the smallest signed value
	 * @throws NoSuchElementException if the bitmap is empty
	 */
	int firstSigned();

	/**
	 * Returns the largest signed value in this bitmap.
	 *
	 * @return the largest signed value
	 * @throws NoSuchElementException if the bitmap is empty
	 */
	int lastSigned();

	/**
	 * Returns the smallest present value greater than or equal to `fromValue`
	 * (interpreted as unsigned), or `-1` if none exists. Not necessarily an efficient
	 * way to iterate through the values.
	 *
	 * @param fromValue inclusive lower bound
	 * @return the smallest present value at or above `fromValue`, or `-1` if none
	 */
	long nextValue(int fromValue);

	/**
	 * Returns the largest present value less than or equal to `fromValue` (interpreted
	 * as unsigned), or `-1` if none exists. Not an efficient way to iterate backwards.
	 *
	 * @param fromValue inclusive upper bound
	 * @return the largest present value at or below `fromValue`, or `-1` if none
	 */
	long previousValue(int fromValue);

	/**
	 * Returns the smallest absent value greater than or equal to `fromValue`
	 * (interpreted as unsigned), or `-1` if none exists. Not necessarily an efficient
	 * way to iterate through the values.
	 *
	 * @param fromValue inclusive lower bound
	 * @return the smallest absent value at or above `fromValue`, or `-1` if none
	 */
	long nextAbsentValue(int fromValue);

	/**
	 * Returns the largest absent value less than or equal to `fromValue` (interpreted
	 * as unsigned), or `-1` if none exists. Not necessarily an efficient way to iterate
	 * through the values.
	 *
	 * @param fromValue inclusive upper bound
	 * @return the largest absent value at or below `fromValue`, or `-1` if none
	 */
	long previousAbsentValue(int fromValue);

	/**
	 * Serializes this bitmap to `out`. The current bitmap is not modified.
	 *
	 * @param out the target data output
	 * @throws IOException if an I/O error occurs
	 */
	void serialize(@Nonnull DataOutput out) throws IOException;

	/**
	 * Serializes this bitmap to a `ByteBuffer`. Preferred when serializing to a
	 * `byte[]` or to a `String` (via `Base64.getEncoder().encodeToString`).
	 *
	 * Regardless of the endianness of the provided buffer, data is written using
	 * little-endian order as required by the RoaringBitmap serialization format. The
	 * current bitmap is not modified.
	 *
	 * ```java
	 * byte[] array = new byte[mrb.serializedSizeInBytes()];
	 * mrb.serialize(ByteBuffer.wrap(array));
	 * ```
	 *
	 * @param buffer the target byte buffer
	 */
	void serialize(@Nonnull ByteBuffer buffer);

	/**
	 * Reports the number of bytes written by the `serialize` methods. The
	 * `writeExternal` path produces a higher count due to Java serialization overhead.
	 *
	 * @return the serialized size in bytes
	 */
	int serializedSizeInBytes();

	/**
	 * Returns the set values as a sorted array.
	 *
	 * @return array of the set values in ascending order
	 */
	@Nonnull
	int[] toArray();

	/**
	 * Returns the number of containers backing this bitmap.
	 *
	 * @return the number of containers
	 */
	int getContainerCount();

	/**
	 * Adapts an {@link IntIterator} to {@link PrimitiveIterator.OfInt} so the bitmap
	 * can back a stream. The two iterator interfaces do not share a common type, hence
	 * this bridge.
	 */
	static final class RoaringOfInt implements PrimitiveIterator.OfInt {
		@Nonnull private final IntIterator iterator;

		public RoaringOfInt(@Nonnull final IntIterator iterator) {
			this.iterator = iterator;
		}

		@Override
		public int nextInt() {
			return this.iterator.next();
		}

		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}
	}
}
